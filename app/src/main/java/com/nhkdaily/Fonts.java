package com.nhkdaily;

import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "A different font each day". Ten open-source (SIL OFL) Japanese fonts from
 * Google Fonts, in deliberately different styles, so you see the same kanji
 * drawn many ways. Japanese fonts are huge, so we ask Google Fonts for just
 * the characters in that day's article (usually well under 1 MB) and save it
 * in the day's folder, where it's deleted along with the article.
 */
final class Fonts {

    /** Google Fonts family, and a short description of its style. */
    static final String[][] FONTS = {
            {"Noto Serif JP", "Mincho (serif)"},
            {"Noto Sans JP", "Gothic (sans-serif)"},
            {"Zen Maru Gothic", "Rounded gothic"},
            {"Klee One", "Textbook handwriting"},
            {"Shippori Mincho", "Traditional mincho"},
            {"Zen Kurenaido", "Felt-pen handwriting"},
            {"Yuji Syuku", "Brush calligraphy"},
            {"Yomogi", "Casual handwriting"},
            {"Kaisei Decol", "Decorative mincho"},
            {"DotGothic16", "Pixel"},
    };

    static String cssUrl = "https://fonts.googleapis.com/css2";
    static final String CSS_FILE = "font.css";

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"; // a modern browser gets WOFF2
    private static final Pattern URL_IN_CSS = Pattern.compile("url\\((['\"]?)(https?://[^)'\"]+)\\1\\)");

    private Fonts() {
    }

    static boolean has(File dayDir) {
        return new File(dayDir, CSS_FILE).isFile();
    }

    /** Downloads `family`, cut down to the characters in the day's article, into dayDir. */
    static void fetch(String family, File dayDir) throws IOException {
        String html = new String(Files.readAllBytes(new File(dayDir, "content.html").toPath()),
                StandardCharsets.UTF_8);
        // Includes furigana (still real text in the saved file) and the date.
        String text = Jsoup.parse(html).text() + "0123456789年月日。、「」『』（）・ー〜！？…";

        Set<Integer> seen = new LinkedHashSet<>();
        StringBuilder chars = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (!Character.isWhitespace(cp) && seen.add(cp)) chars.appendCodePoint(cp);
        });

        String url = cssUrl + "?family=" + URLEncoder.encode(family, "UTF-8")
                + "&display=swap&text=" + URLEncoder.encode(chars.toString(), "UTF-8");
        String css = new String(get(url), StandardCharsets.UTF_8);

        File tmp = new File(dayDir, CSS_FILE + ".part");
        Matcher m = URL_IN_CSS.matcher(css);
        StringBuffer local = new StringBuffer();
        int n = 0;
        while (m.find()) {
            String remote = m.group(2);
            String ext = remote.contains(".woff2") || css.contains("format('woff2')") ? "woff2" : "ttf";
            String name = "font" + n++ + "." + ext;
            try (OutputStream out = new FileOutputStream(new File(dayDir, name))) {
                out.write(get(remote));
            }
            m.appendReplacement(local, Matcher.quoteReplacement("url('" + name + "')"));
        }
        m.appendTail(local);
        if (n == 0) throw new IOException("Google Fonts sent no font for " + family);

        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(local.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dayDir, CSS_FILE))) throw new IOException("Couldn't save the font");
    }

    private static byte[] get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("Google Fonts said " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
