package com.nhkdaily;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Japanese → your chosen language via Google Translate's free web endpoint (no key needed). */
final class Translator {

    static String endpoint = "https://translate.googleapis.com/translate_a/single";

    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 200;
        }
    };

    private Translator() {
    }

    static String translate(String text, String target) throws IOException {
        String key = target + "\u0000" + text;
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        String url = endpoint + "?client=gtx&sl=ja&tl=" + URLEncoder.encode(target, "UTF-8")
                + "&dt=t&q="
                + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Google Translate said " + code);
            String body;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
            String result = parse(body);
            synchronized (CACHE) {
                CACHE.put(key, result);
            }
            return result;
        } finally {
            c.disconnect();
        }
    }

    /** Response looks like [[["English","日本語",...],["more","...",...]], ...]. */
    static String parse(String body) throws IOException {
        try {
            JSONArray segments = new JSONArray(body).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray seg = segments.optJSONArray(i);
                if (seg != null && !seg.isNull(0)) sb.append(seg.optString(0));
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) throw new IOException("Empty translation");
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Couldn't read the translation", e);
        }
    }
}
