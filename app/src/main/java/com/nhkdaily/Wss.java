package com.nhkdaily;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** A tiny WebSocket client: just enough for the Read Aloud voice service. */
final class Wss implements AutoCloseable {

    static final class Message {
        final boolean binary;
        final byte[] data;

        Message(boolean binary, byte[] data) {
            this.binary = binary;
            this.data = data;
        }

        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    static final class HandshakeException extends IOException {
        final int status;
        final Map<String, String> headers;

        HandshakeException(int status, Map<String, String> headers) {
            super("WebSocket handshake failed: HTTP " + status);
            this.status = status;
            this.headers = headers;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private Wss(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    static Wss connect(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        URI uri = URI.create(url);
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        plain.setSoTimeout(timeoutMs);
        Socket s = plain;
        if (secure) {
            // TLS on top of the connected socket, with SNI for the real host name.
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, host, port, true);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("Certificate doesn't match " + host);
            }
            s = ssl;
        }
        s.setSoTimeout(timeoutMs);

        Wss ws = new Wss(s);
        try {
            ws.handshake(uri, host, port, secure, headers);
        } catch (IOException e) {
            ws.close();
            throw e;
        }
        return ws;
    }

    private void handshake(URI uri, String host, int port, boolean secure,
                           Map<String, String> headers) throws IOException {
        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        boolean defaultPort = port == (secure ? 443 : 80);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n");
        req.append("Upgrade: websocket\r\nConnection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("Sec-WebSocket-Version")) continue;
            req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read the response head.
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (last4 != 0x0D0A0D0A) { // until the blank line ending the headers
            int b = in.read();
            if (b < 0) throw new EOFException("Connection closed during handshake");
            head.write(b);
            last4 = (last4 << 8) | b;
            if (head.size() > 65536) throw new IOException("Handshake response too large");
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        int status = 0;
        try {
            status = Integer.parseInt(lines[0].split(" ")[1]);
        } catch (Exception ignored) {
        }
        Map<String, String> resp = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) resp.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(c + 1).trim());
        }
        if (status != 101) throw new HandshakeException(status, resp);
    }

    void sendText(String text) throws IOException {
        send(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void send(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            f.write(0x80 | len);
        } else if (len < 65536) {
            f.write(0x80 | 126);
            f.write(len >>> 8);
            f.write(len);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) len) >>> (8 * i)));
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < len; i++) f.write(payload[i] ^ mask[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Next complete message, or null when the server closes the connection. */
    Message read() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int msgOpcode = -1;
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > 32L * 1024 * 1024) throw new IOException("Frame too large");
            byte[] mask = masked ? readFully(4) : null;
            byte[] payload = readFully((int) len);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

            if (opcode == 0x8) { // close
                return null;
            } else if (opcode == 0x9) { // ping
                send(0xA, payload);
                continue;
            } else if (opcode == 0xA) { // pong
                continue;
            }
            if (opcode != 0) msgOpcode = opcode;
            msg.write(payload);
            if (fin) return new Message(msgOpcode == 0x2, msg.toByteArray());
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("Connection closed");
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("Connection closed");
            off += r;
        }
        return buf;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
