package com.nhkdaily;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regular NHK News (NHK ONE). The article list comes from NHK's public news
 * sitemap (RSS as a fallback). Article text is found the way browser
 * "reader modes" do it, by looking for the block with the most real
 * paragraphs, so it doesn't depend on NHK's internal class names.
 */
final class RegularNews {

    static String sitemapUrl = "https://news.web.nhk/sitemap/sitemap-news-nationwide-article.xml";
    static String rssUrl = "https://news.web.nhk/n-data/conf/na/rss/cat0.xml";
    static final String BASE = "https://news.web.nhk/newsweb/";

    private static final Pattern ARTICLE = Pattern.compile("/newsweb/na/(n[a-z]-[0-9A-Za-z]+)");
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff]");
    /** Breaking-news and live-update items: too short or constantly changing to read. */
    private static final Pattern SKIP_TITLE = Pattern.compile("^【|速報|ライブ|中継|詳しく|動画で");
    /** Class/id words that mark page furniture (whole words only, so "shared-layout" isn't caught). */
    private static final Pattern BOILERPLATE = Pattern.compile("(?i)(^|[\\s_-])(related|recommend|ranking|"
            + "share|sns|breadcrumb|banner|footer|menu|navi|pickup|taglist|tags|movielist|videolist|"
            + "movie|video|program|modal|consent|gate|ad|ads|promo)(?=$|[\\s_-])");

    private RegularNews() {
    }

    // ---- Article list --------------------------------------------------------

    /** Parses the news sitemap; falls back to treating the text as RSS. */
    static List<NhkClient.Item> parseList(String xml) {
        Document d = Jsoup.parse(xml, "", Parser.xmlParser());
        Map<String, NhkClient.Item> out = new LinkedHashMap<>();
        for (Element u : d.getElementsByTag("url")) {
            Element loc = u.getElementsByTag("loc").first();
            Element title = u.getElementsByTag("news:title").first();
            Element date = u.getElementsByTag("news:publication_date").first();
            add(out, loc == null ? "" : loc.text(), title == null ? "" : title.text(),
                    date == null ? "" : date.text());
        }
        for (Element i : d.getElementsByTag("item")) { // RSS
            Element link = i.getElementsByTag("link").first();
            Element title = i.getElementsByTag("title").first();
            Element date = i.getElementsByTag("pubDate").first();
            add(out, link == null ? "" : link.text(), title == null ? "" : title.text(),
                    date == null ? "" : date.text());
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, NhkClient.Item> out, String url, String title, String date) {
        Matcher m = ARTICLE.matcher(url);
        if (!m.find() || title.isEmpty() || SKIP_TITLE.matcher(title).find()) return;
        NhkClient.Item it = new NhkClient.Item();
        it.id = m.group(1);
        it.url = url.trim();
        it.title = title.trim();
        it.titleRuby = null; // regular news has no furigana
        it.date = japaneseDate(date);
        it.regular = true;
        out.putIfAbsent(it.id, it);
    }

    /** "2026-10-24T21:53+09:00" or an RSS date → "2026年10月24日". */
    static String japaneseDate(String s) {
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(s);
        if (iso.find()) {
            return Integer.parseInt(iso.group(1)) + "年" + Integer.parseInt(iso.group(2)) + "月"
                    + Integer.parseInt(iso.group(3)) + "日";
        }
        Matcher rss = Pattern.compile("(\\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\\d{4})")
                .matcher(s);
        if (rss.find()) {
            int month = "JanFebMarAprMayJunJulAugSepOctNovDec".indexOf(rss.group(2)) / 3 + 1;
            return rss.group(3) + "年" + month + "月" + Integer.parseInt(rss.group(1)) + "日";
        }
        return "";
    }

    // ---- Article text --------------------------------------------------------

    /**
     * Returns a clean element holding the article's paragraphs, subheadings
     * and pictures, in order. Throws if the page only has a preview (NHK's
     * terms screen) or no article text at all.
     */
    static Element extract(Document doc) throws IOException {
        String pageText = doc.body() == null ? "" : doc.body().text();
        boolean gate = pageText.contains("ご利用意向の確認") || pageText.contains("内容について確認しました");

        Element fromJson = fromJsonLd(doc);
        if (fromJson != null) return fromJson;

        Document d = doc.clone();
        d.select("script, style, noscript, iframe, svg, button, form, nav, footer, aside, dialog").remove();
        d.select("h1").remove(); // the title is shown separately

        // Paragraph-like blocks and how much real text each holds.
        Map<Element, Integer> weight = new IdentityHashMap<>();
        for (Element e : d.body().getAllElements()) {
            if (inFurniture(e)) continue;
            int w = paragraphWeight(e);
            if (w > 0) weight.put(e, w);
        }
        if (weight.isEmpty()) throw notFound(gate);

        // Total paragraph text under every element.
        Map<Element, Integer> total = new HashMap<>();
        for (Map.Entry<Element, Integer> e : weight.entrySet()) {
            for (Element a = e.getKey(); a != null; a = a.parent()) total.merge(a, e.getValue(), Integer::sum);
        }

        // Walk down from <body> while one child holds almost all of the text.
        Element container = d.body();
        while (true) {
            int here = total.getOrDefault(container, 0);
            Element next = null;
            for (Element child : container.children()) {
                if (total.getOrDefault(child, 0) >= here * 0.85) {
                    next = child;
                    break;
                }
            }
            if (next == null || weight.containsKey(next)) break;
            container = next;
        }

        Element out = new Element("div");
        int chars = 0;
        String lastText = "";
        for (Element e : container.getAllElements()) {
            if (inFurniture(e)) continue;
            String tag = e.tagName();
            if (tag.matches("h[2-4]")) {
                String t = e.text().trim();
                if (t.length() >= 2 && t.length() <= 60 && JAPANESE.matcher(t).find()) {
                    out.appendElement("h2").text(t);
                }
            } else if (tag.equals("img")) {
                Element img = out.appendElement("img");
                for (String a : new String[]{"data-src", "data-original", "data-lazy-src", "src"}) {
                    String abs = e.absUrl(a);
                    if (!abs.isEmpty()) img.attr(a, abs);
                }
                if (!e.attr("alt").isEmpty()) img.attr("alt", e.attr("alt"));
            } else if (tag.equals("figcaption")) {
                String t = e.text().trim();
                if (!t.isEmpty()) out.appendElement("figcaption").text(t);
            } else if (weight.containsKey(e) && !insideCounted(e, weight, container)) {
                for (String t : paragraphText(e).split("\n{2,}|(?<=。)\n")) {
                    t = t.replace('\n', ' ').trim();
                    if (t.isEmpty() || t.equals(lastText)) continue;
                    out.appendElement("p").text(t);
                    chars += t.length();
                    lastText = t;
                }
            }
        }

        boolean truncated = lastText.endsWith("…") || lastText.endsWith("...");
        if (chars < 80 || (gate && (truncated || chars < 300))) throw notFound(gate);
        return out;
    }

    /** Inside a related-articles list, share bar, menu, ad... (page-level wrappers don't count). */
    private static boolean inFurniture(Element e) {
        for (Element a = e; a != null; a = a.parent()) {
            String tag = a.tagName();
            if (tag.equals("body") || tag.equals("html") || tag.equals("main") || tag.equals("article")) break;
            if (BOILERPLATE.matcher(a.className()).find() || BOILERPLATE.matcher(a.id()).find()) return true;
        }
        return false;
    }

    /** Text weight of a paragraph-like element, or 0 if it isn't one. */
    private static int paragraphWeight(Element e) {
        String tag = e.tagName();
        String t;
        if (tag.equals("p")) {
            t = e.text().trim();
            if (t.length() < 15) return 0;
        } else if (tag.equals("div") || tag.equals("section") || tag.equals("span")) {
            t = e.ownText().trim(); // text sitting directly in a block, e.g. split by <br>
            if (t.length() < 40) return 0;
        } else {
            return 0;
        }
        if (!JAPANESE.matcher(t).find()) return 0;
        int linkText = 0;
        for (Element a : e.getElementsByTag("a")) linkText += a.text().length();
        if (linkText > t.length() / 2) return 0; // mostly links: a list of other articles
        return t.length();
    }

    /** The element's text with <br> kept as line breaks (two in a row = new paragraph). */
    private static String paragraphText(Element e) {
        StringBuilder sb = new StringBuilder();
        if (e.tagName().equals("p")) {
            Element c = e.clone();
            c.select("br").after("\u0001");
            sb.append(c.text().replace("\u0001", "\n"));
        } else {
            for (org.jsoup.nodes.Node n : e.childNodes()) { // only text sitting directly in the block
                if (n instanceof org.jsoup.nodes.TextNode) sb.append(((org.jsoup.nodes.TextNode) n).text());
                else if (n instanceof Element && ((Element) n).tagName().equals("br")) sb.append('\n');
                else if (n instanceof Element && !((Element) n).isBlock()) sb.append(((Element) n).text());
            }
        }
        return sb.toString().replaceAll("[ \\t]*\n[ \\t]*", "\n").trim();
    }

    /** True if an ancestor (below the container) was already taken as a paragraph. */
    private static boolean insideCounted(Element e, Map<Element, Integer> weight, Element container) {
        for (Element a = e.parent(); a != null && a != container; a = a.parent()) {
            if (a.tagName().equals("p") && weight.containsKey(a)) return true;
        }
        return false;
    }

    private static IOException notFound(boolean gate) {
        return new IOException(gate
                ? "Regular NHK only showed a preview (its terms screen). Try again, or switch back to NHK Easy."
                : "Couldn't find the article text on the regular NHK page");
    }

    // ---- Structured data, if the page has it ---------------------------------

    private static Element fromJsonLd(Document doc) {
        for (Element s : doc.select("script[type=application/ld+json]")) {
            try {
                String body = articleBody(new JSONTokener(s.data()).nextValue());
                if (body != null && body.trim().length() > 60) {
                    Element out = new Element("div");
                    for (String para : body.split("\\n+")) {
                        if (!para.trim().isEmpty()) out.appendElement("p").text(para.trim());
                    }
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String articleBody(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String b = o.optString("articleBody", "");
            if (!b.isEmpty()) return b;
            java.util.Iterator<String> keys = o.keys(); // keySet() isn't public on Android
            while (keys.hasNext()) {
                String r = articleBody(o.opt(keys.next()));
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = articleBody(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
