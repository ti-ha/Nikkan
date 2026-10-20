package com.nhkdaily;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://www3.nhk.or.jp/news/easy/";
    private static final String[] FEEDS = {
            BASE + "news-list.json",
            BASE + "top-list.json",
    };
    private static final Pattern NEWS_ID =
            Pattern.compile("\"news_id\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

    /** Pulls the article out of NHK's page and replaces the page with a reader layout. */
    private static final String READER_JS = """
        (function(dark, furi){
          if (document.body && document.body.dataset.reader === '1') return 'ok';
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

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String shownDate = null;
    private int attempts = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int bg = isDark() ? Color.parseColor("#16161a") : Color.parseColor("#faf8f3");
        getWindow().getDecorView().setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!isDark()) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.setVisibility(View.INVISIBLE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Stay on the article: ignore any link taps.
                return r.isForMainFrame() && r.hasGesture();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url.startsWith("http")) {
                    attempts = 0;
                    tryReader();
                } else {
                    web.setVisibility(View.VISIBLE);
                }
            }
        });
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Loads on first open, and swaps in the new article if the app was
        // left open overnight.
        if (!today().equals(shownDate)) {
            loadToday();
        } else {
            // Apply a furigana change made in settings without reloading.
            web.evaluateJavascript("document.documentElement.classList.toggle('nofuri',"
                    + !SettingsActivity.furigana(this) + ")", null);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** NHK sometimes fills the article in with script after load, so retry briefly. */
    private void tryReader() {
        String js = READER_JS + "(" + isDark() + "," + SettingsActivity.furigana(this) + ")";
        web.evaluateJavascript(js, result -> {
            if (result != null && result.contains("ok")) {
                web.setVisibility(View.VISIBLE);
            } else if (++attempts < 12) {
                handler.postDelayed(this::tryReader, 500);
            } else {
                web.setVisibility(View.VISIBLE); // show the page as-is rather than nothing
            }
        });
    }

    private void loadToday() {
        final String today = today();
        shownDate = today;
        web.setVisibility(View.INVISIBLE);
        final SharedPreferences prefs = getSharedPreferences("daily", MODE_PRIVATE);

        String savedUrl = prefs.getString("url", null);
        if (savedUrl != null && today.equals(prefs.getString("date", ""))) {
            web.loadUrl(savedUrl);
            return;
        }

        new Thread(() -> {
            Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
            String id = pickArticle(seen);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (id == null) {
                    shownDate = null; // try again next time the app comes to the front
                    showMessage("今日の記事を読み込めませんでした。<br>"
                            + "Couldn't load today's article.<br>Open the app again when you're online.");
                    return;
                }
                String url = BASE + id + "/" + id + ".html";
                seen.add(id);
                prefs.edit()
                        .putString("date", today)
                        .putString("url", url)
                        .putStringSet("seen", seen)
                        .apply();
                web.loadUrl(url);
            });
        }).start();
    }

    private void showMessage(String html) {
        String fg = isDark() ? "#8a877f" : "#8a857a";
        String bg = isDark() ? "#16161a" : "#faf8f3";
        web.loadDataWithBaseURL(null,
                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                        + "<body style='margin:0;background:" + bg + ";color:" + fg + ";font-family:sans-serif;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh;"
                        + "text-align:center;line-height:1.8;padding:0 32px;box-sizing:border-box'>"
                        + "<div>" + html + "</div></body></html>",
                "text/html", "UTF-8", null);
    }

    private String pickArticle(Set<String> seen) {
        for (String feed : FEEDS) {
            try {
                String json = fetch(feed);
                Set<String> ids = new LinkedHashSet<>();
                Matcher m = NEWS_ID.matcher(json);
                while (m.find()) ids.add(m.group(1));
                if (ids.isEmpty()) continue;

                List<String> fresh = new ArrayList<>();
                for (String id : ids) if (!seen.contains(id)) fresh.add(id);
                List<String> pool = fresh.isEmpty() ? new ArrayList<>(ids) : fresh;
                return pool.get(new Random().nextInt(pool.size()));
            } catch (Exception ignored) {
                // try the next feed
            }
        }
        return null;
    }

    private static String fetch(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NHKDaily");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String s = out.toString("UTF-8");
            return s.startsWith("﻿") ? s.substring(1) : s;
        } finally {
            c.disconnect();
        }
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
