package com.nhkdaily;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Japanese speech as MP3, from online neural voices:
 *  1. Microsoft Edge "Read Aloud" (ja-JP-NanamiNeural): the most natural.
 *     Protocol as implemented by github.com/rany2/edge-tts.
 *  2. Google Translate's voice, if Edge is unavailable.
 * Plain Java (no Android types) so it can be tested off-device.
 */
final class Tts {

    static String edgeUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    static String googleUrl = "https://translate.google.com/translate_tts";
    static final String DEFAULT_VOICE = "ja-JP-NanamiNeural";

    private static final String EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Seconds to add to the phone's clock (Microsoft rejects tokens from a skewed clock). */
    private static volatile long clockSkew = 0;

    private Tts() {
    }

    // ---- Edge ---------------------------------------------------------------

    static byte[] edge(String text, String voice) throws IOException {
        try {
            return edgeOnce(text, voice);
        } catch (Wss.HandshakeException e) {
            // 403 usually means our clock is off: correct from the server's Date and retry once.
            String date = e.headers.get("date");
            if (e.status != 403 || date == null) throw e;
            try {
                SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                long server = f.parse(date).getTime() / 1000;
                clockSkew = server - System.currentTimeMillis() / 1000;
            } catch (Exception parse) {
                throw e;
            }
            return edgeOnce(text, voice);
        }
    }

    private static byte[] edgeOnce(String text, String voice) throws IOException {
        long now = System.currentTimeMillis() / 1000 + clockSkew;
        String url = edgeUrl
                + "?TrustedClientToken=" + EdgeToken.TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + id()
                + "&Sec-MS-GEC=" + EdgeToken.generate(now)
                + "&Sec-MS-GEC-Version=" + EdgeToken.SEC_MS_GEC_VERSION;

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Pragma", "no-cache");
        h.put("Cache-Control", "no-cache");
        h.put("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");
        h.put("User-Agent", EDGE_UA);
        h.put("Accept-Language", "en-US,en;q=0.9");
        h.put("Cookie", "muid=" + hex16() + ";");

        try (Wss ws = Wss.connect(url, h, 15000)) {
            String ts = jsDate();
            ws.sendText("X-Timestamp:" + ts + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\""
                    + "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");
            ws.sendText("X-RequestId:" + id() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + ts + "Z\r\n" // the stray Z matches Edge itself
                    + "Path:ssml\r\n\r\n"
                    + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                    + xml(clean(text))
                    + "</prosody></voice></speak>");

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            while (true) {
                Wss.Message m = ws.read();
                if (m == null) break;
                if (!m.binary) {
                    if (m.text().contains("Path:turn.end")) break;
                    continue;
                }
                byte[] d = m.data;
                if (d.length < 2) continue;
                int headerLen = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                if (2 + headerLen > d.length) continue;
                String headers = new String(d, 2, headerLen, StandardCharsets.UTF_8);
                if (!headers.contains("Path:audio")) continue;
                audio.write(d, 2 + headerLen, d.length - 2 - headerLen);
            }
            if (audio.size() == 0) throw new IOException("No audio received");
            return audio.toByteArray();
        }
    }

    // ---- Google -------------------------------------------------------------

    static byte[] google(String text) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        List<String> parts = chunks(clean(text), 180);
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            String url = googleUrl + "?ie=UTF-8&client=tw-ob&tl=ja&total=" + parts.size()
                    + "&idx=" + i + "&textlen=" + p.length()
                    + "&q=" + URLEncoder.encode(p, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", EDGE_UA);
            c.setRequestProperty("Referer", "https://translate.google.com/");
            try {
                if (c.getResponseCode() != 200) {
                    throw new IOException("Google voice said " + c.getResponseCode());
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
                }
            } finally {
                c.disconnect();
            }
        }
        if (all.size() == 0) throw new IOException("No audio received");
        return all.toByteArray(); // MP3 frames concatenate cleanly
    }

    /** Splits text into pieces of at most max chars, preferring sentence breaks. */
    static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = -1;
            for (String p : new String[]{"。", "！", "？", "!", "?", "、", "，", ",", " ", "　"}) {
                int i = rest.lastIndexOf(p, max - 1);
                if (i > max / 3) {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    // ---- helpers ------------------------------------------------------------

    /** The service rejects a few control characters. */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            sb.append((ch <= 8) || (ch >= 11 && ch <= 12) || (ch >= 14 && ch <= 31) ? ' ' : ch);
        }
        return sb.toString();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String jsDate() {
        SimpleDateFormat f = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(System.currentTimeMillis() + clockSkew * 1000));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String hex16() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
