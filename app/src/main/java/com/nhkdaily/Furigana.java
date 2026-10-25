package com.nhkdaily;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds furigana to articles that don't have any (regular NHK News).
 *
 * A Japanese word analyser (Kuromoji, see KuromojiAnalyzer) splits the text
 * into words with their readings; this class then puts each reading over
 * just the kanji it belongs to (食べる → 食[た]べる) and writes it back as
 * ordinary <ruby>, so all the furigana settings, WaniKani mode included,
 * work exactly as on NHK Easy.
 */
final class Furigana {

    /** Splits text into words: each entry is {surface, reading in katakana (or null)}. */
    interface Analyzer {
        List<String[]> tokenize(String text);
    }

    private Furigana() {
    }

    // ---- Whole article -------------------------------------------------------

    /**
     * Adds furigana to the title and body of a saved article (content.html).
     * Returns false if there was nothing to do.
     */
    static boolean addTo(File contentHtml, Analyzer analyzer) throws IOException {
        String html = new String(Files.readAllBytes(contentHtml.toPath()), StandardCharsets.UTF_8);
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        int added = 0;
        for (Element part : doc.body().select("h1, div.b")) added += annotate(part, analyzer);
        if (added == 0) return false;

        File tmp = new File(contentHtml.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(doc.body().html().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(contentHtml)) throw new IOException("Couldn't save furigana");
        return true;
    }

    /** Wraps kanji in every text node under root (skipping existing furigana). Returns words annotated. */
    static int annotate(Element root, Analyzer analyzer) {
        List<TextNode> nodes = new ArrayList<>();
        collect(root, nodes);
        int count = 0;
        for (TextNode t : nodes) {
            String text = t.getWholeText();
            if (!hasKanji(text)) continue;
            StringBuilder out = new StringBuilder();
            count += toHtml(text, analyzer, out);
            t.before(out.toString());
            t.remove();
        }
        return count;
    }

    private static void collect(Node n, List<TextNode> out) {
        for (Node c : n.childNodes()) {
            if (c instanceof TextNode) {
                out.add((TextNode) c);
            } else if (c instanceof Element) {
                String tag = ((Element) c).tagName();
                if (tag.equals("ruby") || tag.equals("rt") || tag.equals("rp")
                        || tag.equals("script") || tag.equals("style")) continue;
                collect(c, out);
            }
        }
    }

    // ---- One run of text -----------------------------------------------------

    /** Appends text as HTML with <ruby> on its kanji; returns how many words got furigana. */
    static int toHtml(String text, Analyzer analyzer, StringBuilder out) {
        int count = 0;
        for (String[] token : analyzer.tokenize(text)) {
            String surface = token[0];
            String reading = token.length > 1 ? token[1] : null;
            if (!hasKanji(surface) || reading == null || reading.isEmpty() || reading.equals("*")) {
                out.append(escape(surface));
                continue;
            }
            for (String[] part : align(surface, hiragana(reading))) {
                if (part[1] == null) {
                    out.append(escape(part[0]));
                } else {
                    out.append("<ruby>").append(escape(part[0])).append("<rt>")
                            .append(escape(part[1])).append("</rt></ruby>");
                }
            }
            count++;
        }
        return count;
    }

    /**
     * Splits a word into pieces, giving each run of kanji its share of the
     * reading: ("食べる", "たべる") → [食, た], [べる, null].
     * Falls back to the whole reading over the whole word if they don't line up.
     */
    static List<String[]> align(String surface, String reading) {
        List<String> runs = new ArrayList<>();
        List<Boolean> kanji = new ArrayList<>();
        int i = 0;
        while (i < surface.length()) {
            int cp = surface.codePointAt(i);
            boolean k = isKanji(cp);
            int j = i + Character.charCount(cp);
            while (j < surface.length() && isKanji(surface.codePointAt(j)) == k) {
                j += Character.charCount(surface.codePointAt(j));
            }
            runs.add(surface.substring(i, j));
            kanji.add(k);
            i = j;
        }

        StringBuilder regex = new StringBuilder("^");
        for (int r = 0; r < runs.size(); r++) {
            regex.append(kanji.get(r) ? "(.+?)" : Pattern.quote(hiragana(runs.get(r))));
        }
        regex.append("$");

        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex.toString()).matcher(reading);
        if (!m.matches()) {
            out.add(new String[]{surface, reading});
            return out;
        }
        int g = 1;
        for (int r = 0; r < runs.size(); r++) {
            out.add(new String[]{runs.get(r), kanji.get(r) ? m.group(g++) : null});
        }
        return out;
    }

    // ---- Characters ------------------------------------------------------------

    static boolean isKanji(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FFFF)
                || cp == '々' || cp == '〆' || cp == 'ヶ' || cp == 'ヵ';
    }

    static boolean hasKanji(String s) {
        return s.codePoints().anyMatch(cp -> isKanji(cp) && cp != 'ヶ' && cp != 'ヵ');
    }

    /** Katakana → hiragana (ー and anything else unchanged). */
    static String hiragana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) sb.append(c >= 'ァ' && c <= 'ヶ' && c != 'ヵ' && c != 'ヶ' ? (char) (c - 0x60) : c);
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
