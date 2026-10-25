package com.nhkdaily;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to NHK Easy (now behind NHK ONE). Plain Java, no Android types, so
 * it can run on a background thread and be tested off-device.
 *
 * Sign-in uses NHK's own "abroad" authorisation link, the same approach as
 * github.com/nhk-news-web-easy/nhk-easy-task: following its redirects sets
 * the session cookies that the article list and pages need.
 */
public class NhkClient {

    public static final String EASY = "https://news.web.nhk/news/easy/";
    static String authUrl = "https://news.web.nhk/tix/build_authorize?"
            + "idp=a-alaz&profileType=abroad"
            + "&redirect_uri=https%3A%2F%2Fnews.web.nhk%2Fnews%2Feasy%2F"
            + "&entity=none&area=130&pref=13&jisx0402=13101&postal=1000001";
    static String[] feeds = { EASY + "top-list.json", EASY + "news-list.json" };
    static String easyBase = EASY;

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:143.0) Gecko/20100101 Firefox/143.0";

    public interface Progress {
        /** fraction 0..1, plus a Japanese and English description of the step. */
        void step(float fraction, String jp, String en);
    }

    public static class Item {
        public String id, title, titleRuby, date, image;
        /** Page address (regular NHK); NHK Easy pages are built from the id. */
        public String url;
        /** Regular NHK News rather than NHK Easy. */
        public boolean regular;
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private volatile boolean signedIn = false;
    /** Cookie header captured from the in-app browser's NHK sign-in, if any. */
    private volatile String browserCookies = null;

    /**
     * Use the session from the in-app browser. NHK's sign-in can involve
     * script, so the app signs in with a real (hidden) browser and hands the
     * resulting cookies over here.
     */
    public void useBrowserCookies(String cookieHeader) {
        if (cookieHeader != null && !cookieHeader.trim().isEmpty()) {
            browserCookies = cookieHeader.trim();
            signedIn = true;
        }
    }

    // ---- HTTP with manual redirects + cookies ------------------------------

    private static class Response {
        int code;
        byte[] body;
        String url;
    }

    private Response get(String address, String referer) throws IOException {
        String url = address;
        for (int hop = 0; hop < 12; hop++) {
            URI uri = URI.create(url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "ja,en;q=0.8");
            if (referer != null) c.setRequestProperty("Referer", referer);
            List<String> jar = new ArrayList<>();
            for (Map.Entry<String, List<String>> e
                    : cookies.get(uri, new HashMap<>()).entrySet()) {
                if (e.getKey().equalsIgnoreCase("Cookie")) jar.addAll(e.getValue());
            }
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (browserCookies != null && (host.equals("nhk") || host.endsWith(".nhk"))) {
                jar.add(browserCookies);
            }
            if (!jar.isEmpty()) c.setRequestProperty("Cookie", String.join("; ", jar));
            try {
                int code = c.getResponseCode();
                cookies.put(uri, c.getHeaderFields());
                String location = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null) {
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                Response r = new Response();
                r.code = code;
                r.url = url;
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                r.body = in == null ? new byte[0] : readAll(in);
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private Response getSignedIn(String url, String referer) throws IOException {
        if (!signedIn) signIn();
        Response r = get(url, referer);
        if ((r.code == 401 || r.code == 403) && browserCookies == null) {
            signIn(); // no browser session: try the plain sign-in once more
            r = get(url, referer);
        }
        return r;
    }

    public void signIn() throws IOException {
        get(authUrl, easyBase);
        signedIn = true;
    }

    // ---- Article list -------------------------------------------------------

    public List<Item> list() throws IOException {
        Map<String, Item> items = new LinkedHashMap<>();
        IOException last = null;
        for (String feed : feeds) {
            try {
                Response r = getSignedIn(feed, easyBase);
                if (r.code != 200) {
                    last = new IOException("Article list: NHK said " + r.code
                            + (r.code == 401 ? " (not signed in)" : "")
                            + (browserCookies == null ? " · no browser session" : ""));
                    continue;
                }
                String text = new String(r.body, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                collect(new JSONTokener(text).nextValue(), items);
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (items.isEmpty()) throw last != null ? last : new IOException("No articles listed");
        return new ArrayList<>(items.values());
    }

    /** Recent regular NHK News articles (public sitemap, RSS as a fallback). */
    public List<Item> listRegular() throws IOException {
        IOException last = null;
        for (String feed : new String[]{RegularNews.sitemapUrl, RegularNews.rssUrl}) {
            try {
                Response r = get(feed, RegularNews.BASE);
                if (r.code != 200) {
                    last = new IOException("Regular news list: NHK said " + r.code);
                    continue;
                }
                List<Item> items = RegularNews.parseList(new String(r.body, StandardCharsets.UTF_8));
                if (!items.isEmpty()) return items;
                last = new IOException("Regular news list was empty");
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No regular articles listed");
    }

    private static void collect(Object node, Map<String, Item> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String id = o.optString("news_id", o.optString("newsId", ""));
            if (!id.isEmpty() && id.matches("[A-Za-z0-9]+")) {
                if (!out.containsKey(id)) out.put(id, toItem(id, o));
                return;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) collect(o.opt(keys.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        }
    }

    private static Item toItem(String id, JSONObject o) {
        Item it = new Item();
        it.id = id;
        it.title = pick(o, "title");
        it.titleRuby = pick(o, "title_with_ruby", "titleWithRuby");
        String time = pick(o, "news_prearranged_time", "newsPrearrangedTime");
        if (time.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            it.date = Integer.parseInt(time.substring(0, 4)) + "年"
                    + Integer.parseInt(time.substring(5, 7)) + "月"
                    + Integer.parseInt(time.substring(8, 10)) + "日";
        } else {
            it.date = "";
        }
        String img = pick(o, "news_easy_image_uri", "newsEasyImageUri");
        if (img.isEmpty() || img.equals("''")) img = pick(o, "news_web_image_uri", "newsWebImageUri");
        it.image = img.isEmpty() || img.equals("''") ? null : resolve(img, easyBase + id + "/");
        return it;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !v.equals("null")) return v;
        }
        return "";
    }

    private static String resolve(String ref, String base) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Download one article into a folder --------------------------------

    /**
     * Downloads the article and its images into dir: content.html (the reader
     * fragment) plus image files. Writes to a temp folder first so a half-done
     * download never looks complete.
     */
    public void download(Item item, File dir, Progress p) throws IOException {
        String url = item.url != null ? item.url : easyBase + item.id + "/" + item.id + ".html";
        p.step(0.45f, "記事をダウンロードしています", "Downloading the article");
        Response r = getSignedIn(url, item.regular ? RegularNews.BASE : easyBase);
        if (r.code != 200) throw new IOException("Article page: NHK said " + r.code);

        Document doc = Jsoup.parse(new String(r.body, StandardCharsets.UTF_8), url);
        Element body;
        if (item.regular) {
            body = RegularNews.extract(doc);
        } else {
            body = doc.getElementById("js-article-body");
            if (body == null) body = doc.selectFirst(".article-main__body, .article-body, [class*=article][class*=body]");
            if (body == null) throw new IOException("Couldn't find the article text on the page");
        }

        body.select("script, style, iframe, button, audio, video, form, nav, aside, svg, noscript").remove();
        body.select("a").unwrap();
        for (Element el : body.getAllElements()) {
            el.removeAttr("style");
            el.removeAttr("class");
            el.removeAttr("id");
        }

        // Title (keep furigana)
        String titleHtml = item.regular && item.title != null ? escape(item.title) : item.titleRuby;
        if (titleHtml == null || titleHtml.isEmpty()) {
            Element h = doc.selectFirst(".article-main__title, .article-title, h1");
            titleHtml = h != null ? h.html() : (item.title == null ? "" : escape(item.title));
        }
        Element titleEl = Jsoup.parseBodyFragment(titleHtml).body();
        titleEl.select("a").unwrap();
        titleHtml = titleEl.html();

        String date = item.date;
        if (date == null || date.isEmpty()) {
            Element d = doc.selectFirst(".article-main__date, .article-date, time");
            date = d != null ? d.text() : "";
        }

        // Images: the article's own, or the headline picture.
        List<Element> imgs = new ArrayList<>(body.select("img"));
        String hero = null;
        if (imgs.isEmpty()) {
            hero = item.image;
            if (hero == null) {
                Element hi = doc.selectFirst(".article-main__picture img, .article-main__figure img, "
                        + ".article-figure img, article figure img");
                if (hi != null) hero = imageSrc(hi);
            }
            if (hero == null) {
                Element og = doc.selectFirst("meta[property=og:image]");
                String u = og != null ? og.absUrl("content") : "";
                if (!u.isEmpty() && !u.contains("/ogp/") && !u.matches("(?i).*(logo|default|common).*")) hero = u;
            }
        }

        File tmp = new File(dir.getParentFile(), dir.getName() + ".part");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) throw new IOException("Couldn't create " + tmp);

        int total = imgs.size() + (hero != null ? 1 : 0);
        int done = 0;
        String heroFile = null;
        if (hero != null) {
            progressImages(p, done, total);
            heroFile = saveImage(hero, url, tmp, "hero");
            done++;
        }
        for (int i = 0; i < imgs.size(); i++) {
            progressImages(p, done, total);
            Element img = imgs.get(i);
            String src = imageSrc(img);
            String saved = src == null ? null : saveImage(src, url, tmp, "img" + i);
            String alt = img.attr("alt");
            img.clearAttributes();
            if (saved == null) {
                img.remove();
            } else {
                img.attr("src", saved);
                if (!alt.isEmpty()) img.attr("alt", alt);
            }
            done++;
        }

        StringBuilder html = new StringBuilder();
        if (!date.isEmpty()) html.append("<div class=\"dt\">").append(escape(date)).append("</div>");
        if (!titleHtml.isEmpty()) html.append("<h1>").append(titleHtml).append("</h1>");
        if (heroFile != null) html.append("<img src=\"").append(heroFile).append("\" alt=\"\">");
        html.append("<div class=\"b\">").append(body.html()).append("</div>");

        try (OutputStream out = new FileOutputStream(new File(tmp, "content.html"))) {
            out.write(html.toString().getBytes(StandardCharsets.UTF_8));
        }
        deleteRecursive(dir);
        if (!tmp.renameTo(dir)) throw new IOException("Couldn't save the article");
        p.step(1f, "準備できました", "Ready");
    }

    private static void progressImages(Progress p, int done, int total) {
        p.step(0.6f + 0.35f * done / Math.max(1, total), "画像をダウンロードしています",
                total > 1 ? "Downloading pictures (" + (done + 1) + " of " + total + ")"
                        : "Downloading the picture");
    }

    private static String imageSrc(Element img) {
        for (String a : new String[]{"data-src", "data-original", "data-lazy-src", "src"}) {
            String v = img.absUrl(a);
            if (!v.isEmpty() && !v.startsWith("data:")) return v;
        }
        return null;
    }

    /** Saves an image, returns its file name, or null if it couldn't be fetched. */
    private String saveImage(String src, String referer, File dir, String name) {
        try {
            boolean nhk = src.contains(".nhk");
            Response r = nhk ? getSignedIn(src, referer) : get(src, referer);
            if (r.code != 200 || r.body.length == 0) return null;
            String ext = src.replaceAll("[?#].*$", "").replaceAll("^.*\\.", "").toLowerCase();
            if (!ext.matches("jpe?g|png|gif|webp")) ext = "jpg";
            String file = name + "." + ext;
            try (OutputStream out = new FileOutputStream(new File(dir, file))) {
                out.write(r.body);
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Small helpers -----------------------------------------------------

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Ids we've already used, so a new pick can avoid them. */
    public static List<Item> unseen(List<Item> all, Collection<String> seen) {
        List<Item> fresh = new ArrayList<>();
        for (Item i : all) if (!seen.contains(i.id)) fresh.add(i);
        return fresh;
    }

}
