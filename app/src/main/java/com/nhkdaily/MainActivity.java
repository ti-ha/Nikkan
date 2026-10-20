package com.nhkdaily;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One screen, no controls: today's NHK Easy article in a clean reader view.
 * Same article all day, a new random (unseen) one each day.
 *
 * NHK Easy now sits behind NHK ONE. Each launch we first visit NHK's own
 * "abroad" authorisation link (the same trick nhk-easy-task uses), which
 * accepts the terms and sets the session cookies. Then we read the article
 * list JSON with those cookies, pick one, and show it in the reader.
 * If that ever stops working we fall back to showing NHK's pages so you can
 * get through the terms by hand.
 */
public class MainActivity extends Activity {

    private static final String EASY = "https://news.web.nhk/news/easy/";
    private static final String LIST_URL = EASY + "list/";
    private static final String TOP_URL = EASY;
    private static final String AUTH_URL = "https://news.web.nhk/tix/build_authorize?"
            + "idp=a-alaz&profileType=abroad"
            + "&redirect_uri=https%3A%2F%2Fnews.web.nhk%2Fnews%2Feasy%2F"
            + "&entity=none&area=130&pref=13&jisx0402=13101&postal=1000001";
    private static final String[] FEEDS = { EASY + "top-list.json", EASY + "news-list.json" };
    private static final Pattern NEWS_ID =
            Pattern.compile("\"(?:news_id|newsId)\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    private static final String GATE_TEST =
            "/内容について確認しました|サービスを利用しない|サービスの利用を開始する|受信契約|放送局/.test(document.body ? document.body.innerText : '')";

    /** Returns {gate: bool, links: [article urls]} for the current page. */
    private static final String LINKS_JS = """
        (function(){
          var out = {};
          document.querySelectorAll('a[href]').forEach(function(a){
            var h = a.href.split('#')[0].split('?')[0];
            var i = h.indexOf('/news/easy/');
            if (i < 0) return;
            var rest = h.substring(i + 11);
            if (!rest || /^(list|ogp|about|index|help|faq)/i.test(rest)) return;
            if (/\\.html$/.test(rest) || /^[A-Za-z]{0,4}\\d{6,}/.test(rest)) out[h] = 1;
          });
          var form = document.querySelectorAll('input:not([type=hidden]),select,textarea').length > 0;
          return JSON.stringify({gate: GATE, form: form, links: Object.keys(out)});
        })()
        """.replace("GATE", GATE_TEST);

    /** Pulls the article out of NHK's page and replaces the page with a reader layout. */
    private static final String READER_JS = """
        (function(dark, furi){
          if (document.body && document.body.dataset.reader === '1') return 'ok';
          var tx = document.body ? document.body.innerText : '';
          if (/内容について確認しました|サービスを利用しない|サービスの利用を開始する|受信契約/.test(tx)) return 'gate';
          var sels = ['#js-article-body', '.article-main__body', '.article-body',
                      'article .body', '[class*="article"][class*="body"]', 'article', 'main'];
          var body = null;
          for (var i = 0; i < sels.length; i++) {
            var e = document.querySelector(sels[i]);
            if (e && e.textContent.trim().length > 80) { body = e; break; }
          }
          if (!body) return 'none';
          var t = document.querySelector('.article-main__title, .article-title, article h1, h1');
          var d = document.querySelector('.article-main__date, .article-date, time');
          var fix = function(img){
            var lazy = img.getAttribute('data-src') || img.getAttribute('data-original') ||
                       img.getAttribute('data-lazy-src');
            if (lazy) img.setAttribute('src', lazy);
            if (img.getAttribute('src')) img.setAttribute('src', img.src);
            img.removeAttribute('loading'); img.removeAttribute('width'); img.removeAttribute('height');
            img.removeAttribute('style'); img.removeAttribute('class');
          };
          var hero = '';
          if (!body.querySelector('img')) {
            var hi = document.querySelector('.article-main__picture img, .article-main__figure img,' +
                       '.article-figure img, .article-main img, article figure img, article img, main figure img');
            if (hi) { var c = hi.cloneNode(true); fix(c); if (c.getAttribute('src')) hero = c.outerHTML; }
            if (!hero) {
              var og = document.querySelector('meta[property="og:image"]');
              var u = og && og.content;
              if (u && !/logo|default|ogp|common/i.test(u)) hero = '<img src="' + u + '">';
            }
          }
          var title = t ? t.innerHTML : '';
          var date = d ? d.textContent.trim() : '';
          var b = body.cloneNode(true);
          b.querySelectorAll('script,style,iframe,button,audio,video,form,nav,aside,svg')
           .forEach(function(x){ x.remove(); });
          b.querySelectorAll('a').forEach(function(a){
            var s = document.createElement('span'); s.innerHTML = a.innerHTML; a.replaceWith(s);
          });
          b.querySelectorAll('img').forEach(fix);
          var tmp = document.createElement('div'); tmp.innerHTML = title;
          tmp.querySelectorAll('a').forEach(function(a){
            var s = document.createElement('span'); s.innerHTML = a.innerHTML; a.replaceWith(s);
          });
          title = tmp.innerHTML;
          var bg = dark ? '#16161a' : '#faf8f3', fg = dark ? '#e6e3dc' : '#222', mu = dark ? '#8a877f' : '#8a857a';
          document.head.innerHTML =
            '<meta name="viewport" content="width=device-width,initial-scale=1">' +
            '<style>' +
            'html,body{margin:0;background:' + bg + ';color:' + fg + ';}' +
            'body{font-family:"Noto Serif CJK JP","Noto Serif JP",serif;font-size:21px;line-height:2.1;' +
            '-webkit-text-size-adjust:100%;}' +
            '.r{max-width:640px;margin:0 auto;padding:56px 22px 96px;}' +
            '.dt{font-family:sans-serif;font-size:13px;color:' + mu + ';letter-spacing:.05em;margin-bottom:10px;}' +
            'h1{font-size:26px;line-height:1.9;margin:0 0 28px;font-weight:700;}' +
            'p{margin:0 0 1.3em;}' +
            'rt{font-size:.5em;color:' + mu + ';}' +
            'html.nofuri rt,html.nofuri rp{display:none;}' +
            'img{display:block;width:100%;height:auto;border-radius:8px;margin:0 0 28px;}' +
            'figure{margin:0;} figcaption{font-family:sans-serif;font-size:13px;color:' + mu + ';' +
            'line-height:1.6;margin:-18px 0 28px;}' +
            '*{background:transparent!important;}' +
            '</style>';
          document.body.removeAttribute('class');
          document.body.removeAttribute('style');
          document.body.innerHTML = '<div class="r">' +
            (date ? '<div class="dt">' + date + '</div>' : '') +
            (title ? '<h1>' + title + '</h1>' : '') + hero +
            '<div class="b">' + b.innerHTML + '</div></div>';
          document.documentElement.classList.toggle('nofuri', !furi);
          document.body.dataset.reader = '1';
          window.scrollTo(0, 0);
          return 'ok';
        })
        """;

    private enum Mode { IDLE, AUTH, PICK_LIST, PICK_TOP, READ }

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Mode mode = Mode.IDLE;
    private String shownDate = null;
    private String articleUrl = null;
    private boolean readerShown = false;
    private boolean sawGate = false;
    private boolean authHandled = false;
    private long phaseStart = 0;
    private long offSiteSince = 0;
    private int readerTries = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int bg = bgColor();
        getWindow().getDecorView().setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!isDark()) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.setVisibility(View.INVISIBLE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Once the article is showing, ignore link taps.
                return readerShown && r.isForMainFrame() && r.hasGesture();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (mode == Mode.AUTH && url != null && url.startsWith(EASY)) {
                    afterAuth();
                } else if (mode == Mode.READ && !readerShown) {
                    readerTries = 0;
                    handler.removeCallbacks(readerLoop);
                    handler.post(readerLoop);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && mode != Mode.IDLE) {
                    fail("今日の記事を読み込めませんでした。<br>Couldn't load today's article.<br>"
                            + "Open the app again when you're online.");
                }
            }
        });
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!today().equals(shownDate)) {
            loadToday();
        } else if (readerShown) {
            web.evaluateJavascript("document.documentElement.classList.toggle('nofuri',"
                    + !SettingsActivity.furigana(this) + ")", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (!readerShown && mode != Mode.AUTH && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---- Daily flow -------------------------------------------------------

    private void loadToday() {
        handler.removeCallbacksAndMessages(null);
        shownDate = today();
        readerShown = false;
        sawGate = false;
        authHandled = false;
        web.setVisibility(View.INVISIBLE);

        // Step 1: let NHK set fresh session cookies via the "abroad" profile.
        mode = Mode.AUTH;
        web.loadUrl(AUTH_URL);
        handler.postDelayed(this::afterAuth, 15000); // don't wait forever
    }

    /** Step 2: re-open today's article, or fetch the list and pick one. */
    private void afterAuth() {
        if (mode != Mode.AUTH || authHandled) return;
        authHandled = true;
        handler.removeCallbacksAndMessages(null);

        SharedPreferences prefs = prefs();
        String saved = prefs.getString("url", null);
        if (saved != null && shownDate.equals(prefs.getString("date", ""))) {
            read(saved);
            return;
        }

        final String cookies = CookieManager.getInstance().getCookie(EASY);
        new Thread(() -> {
            Set<String> ids = fetchIds(cookies);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || mode != Mode.AUTH) return;
                if (ids.isEmpty()) {
                    // Couldn't read the list directly: fall back to NHK's pages.
                    startPick(Mode.PICK_LIST, LIST_URL);
                } else {
                    List<String> urls = new ArrayList<>();
                    for (String id : ids) urls.add(EASY + id + "/" + id + ".html");
                    choose(urls);
                }
            });
        }).start();
    }

    private static Set<String> fetchIds(String cookies) {
        Set<String> ids = new LinkedHashSet<>();
        for (String feed : FEEDS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(feed).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
                c.setRequestProperty("Referer", EASY);
                c.setRequestProperty("Accept", "application/json, text/plain, */*");
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    Matcher m = NEWS_ID.matcher(out.toString("UTF-8"));
                    while (m.find()) ids.add(m.group(1));
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // try the next feed
            }
        }
        return ids;
    }

    private void startPick(Mode m, String url) {
        mode = m;
        phaseStart = SystemClock.elapsedRealtime();
        offSiteSince = 0;
        web.loadUrl(url);
        handler.removeCallbacks(pickLoop);
        handler.postDelayed(pickLoop, 800);
    }

    /**
     * Fallback: polls whatever NHK page is showing until article links appear.
     * If nothing turns up quickly, the page is shown so you can get through
     * NHK ONE's terms steps by hand; never times out on you mid-way.
     */
    private final Runnable pickLoop = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.PICK_LIST && mode != Mode.PICK_TOP) return;
            web.evaluateJavascript(LINKS_JS, raw -> {
                if (mode != Mode.PICK_LIST && mode != Mode.PICK_TOP) return;
                boolean gate = false, form = false;
                List<String> links = new ArrayList<>();
                try {
                    JSONObject o = new JSONObject((String) new JSONTokener(raw).nextValue());
                    gate = o.optBoolean("gate");
                    form = o.optBoolean("form");
                    JSONArray a = o.getJSONArray("links");
                    for (int i = 0; i < a.length(); i++) links.add(a.getString(i));
                } catch (Exception ignored) {
                }

                if (!links.isEmpty()) {
                    choose(links);
                    return;
                }

                long now = SystemClock.elapsedRealtime();
                String url = web.getUrl() == null ? "" : web.getUrl();
                boolean onEasy = url.contains("/news/easy");

                if (gate) sawGate = true;
                if (gate || now - phaseStart > 5000) web.setVisibility(View.VISIBLE);

                if (!onEasy && !gate && !form) {
                    if (offSiteSince == 0) offSiteSince = now;
                    if (now - offSiteSince > 4000) {
                        startPick(Mode.PICK_LIST, LIST_URL);
                        return;
                    }
                } else {
                    offSiteSince = 0;
                }

                if (onEasy && !gate && !form && mode == Mode.PICK_LIST && now - phaseStart > 10000) {
                    startPick(Mode.PICK_TOP, TOP_URL);
                    return;
                }
                handler.postDelayed(this, 1000);
            });
        }
    };

    private void choose(List<String> urls) {
        SharedPreferences prefs = prefs();
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
        List<String> fresh = new ArrayList<>();
        for (String u : urls) if (!seen.contains(u)) fresh.add(u);
        List<String> pool = fresh.isEmpty() ? urls : fresh;
        String url = pool.get(random.nextInt(pool.size()));

        seen.add(url);
        prefs.edit()
                .putString("date", shownDate)
                .putString("url", url)
                .putStringSet("seen", seen)
                .apply();
        read(url);
    }

    private void read(String url) {
        handler.removeCallbacksAndMessages(null);
        mode = Mode.READ;
        articleUrl = url;
        readerShown = false;
        web.setVisibility(View.INVISIBLE);
        web.loadUrl(url);
    }

    /** NHK fills the article in with script after load, so keep trying briefly. */
    private final Runnable readerLoop = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.READ || readerShown) return;
            String js = READER_JS + "(" + isDark() + "," + SettingsActivity.furigana(MainActivity.this) + ")";
            web.evaluateJavascript(js, result -> {
                if (mode != Mode.READ || readerShown) return;
                if (result != null && result.contains("ok")) {
                    readerShown = true;
                    web.setVisibility(View.VISIBLE);
                } else if (result != null && result.contains("gate")) {
                    sawGate = true;
                    web.setVisibility(View.VISIBLE);
                    handler.postDelayed(this, 1000);
                } else if (sawGate && !articleUrl.equals(web.getUrl())) {
                    sawGate = false;
                    web.setVisibility(View.INVISIBLE);
                    web.loadUrl(articleUrl);
                } else if (++readerTries < 16) {
                    handler.postDelayed(this, 500);
                } else {
                    web.setVisibility(View.VISIBLE); // show the page as-is rather than nothing
                }
            });
        }
    };

    private void fail(String html) {
        handler.removeCallbacksAndMessages(null);
        mode = Mode.IDLE;
        shownDate = null; // try again next time the app comes to the front
        String fg = isDark() ? "#8a877f" : "#8a857a";
        String bg = isDark() ? "#16161a" : "#faf8f3";
        web.loadDataWithBaseURL(null,
                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                        + "<body style='margin:0;background:" + bg + ";color:" + fg + ";font-family:sans-serif;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh;"
                        + "text-align:center;line-height:1.8;padding:0 32px;box-sizing:border-box'>"
                        + "<div>" + html + "</div></body></html>",
                "text/html", "UTF-8", null);
        web.setVisibility(View.VISIBLE);
    }

    // ---- Helpers ----------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences("daily", MODE_PRIVATE);
    }

    private int bgColor() {
        return isDark() ? Color.parseColor("#16161a") : Color.parseColor("#faf8f3");
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
