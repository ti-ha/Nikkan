package com.nhkdaily;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen, no controls: today's NHK Easy article in a clean reader view.
 *
 * Articles are downloaded and tidied in the background, then saved in a
 * folder per day. While you read today's, tomorrow's is fetched, so it opens
 * instantly. Each launch deletes any folder from an earlier day.
 */
public class MainActivity extends Activity {

    private static final String RETRY = "nikkan://retry";

    /** One background thread for all network work, shared across launches. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    /** Translations get their own thread so they never wait behind a download. */
    private static final ExecutorService TRANSLATE = Executors.newSingleThreadExecutor();
    private static final NhkClient NHK = new NhkClient();

    /** When the hidden browser last signed in to NHK (per app process). */
    private static long signedInAt = 0;
    private static final long SESSION_MS = 60 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebView authWeb;
    private Speaker speaker;
    /** Font family the reader is currently drawn in (null = the normal font). */
    private String renderedFont = null;
    private Runnable afterSignIn;
    private String shownDate = null;
    private boolean readerShown = false;
    private boolean loadingShown = false;
    private float lastFraction = 0f;
    private String lastJp = "", lastEn = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int bg = Color.parseColor(isDark() ? "#16161a" : "#faf8f3");
        getWindow().getDecorView().setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!isDark()) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        web = new ReaderWebView(this);
        web.setBackgroundColor(bg);
        speaker = new Speaker(this, state -> {
            if (alive()) web.evaluateJavascript("window.setSpeech&&setSpeech('" + state + "')", null);
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);       // saved articles and pictures live in app storage
        s.setBlockNetworkLoads(true);     // the reader never touches the network itself
        // Long-press selection stays on; the menu that pops up is our own (see ReaderWebView).
        web.setLongClickable(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (RETRY.equals(r.getUrl().toString())) {
                    shownDate = null;
                    start();
                }
                return true; // never navigate anywhere
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (loadingShown) pushProgress(); // in case updates arrived before the page was ready
            }
        });
        web.addJavascriptInterface(new Bridge(), "Nikkan");
        setContentView(web);
    }

    /**
     * WebView with Android's text-selection toolbar emptied out (no Read aloud,
     * Translate, Share...). Selection handles still work; the reader page
     * shows its own small menu instead.
     */
    static class ReaderWebView extends WebView {
        ReaderWebView(Context c) {
            super(c);
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback) {
            return super.startActionMode(new EmptyMenu(callback));
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return super.startActionMode(new EmptyMenu(callback), type);
        }
    }

    private static class EmptyMenu extends ActionMode.Callback2 {
        private final ActionMode.Callback inner;

        EmptyMenu(ActionMode.Callback inner) {
            this.inner = inner;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            inner.onCreateActionMode(mode, menu);
            menu.clear();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            inner.onPrepareActionMode(mode, menu);
            menu.clear();
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            inner.onDestroyActionMode(mode);
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (inner instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) inner).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }

    /** Called from the reader page when you select text. */
    private class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Nikkan", text));
            });
        }

        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (alive()) speaker.speak(text, Tts.DEFAULT_VOICE);
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            runOnUiThread(() -> {
                if (alive()) speaker.stop();
            });
        }

        @JavascriptInterface
        public void translate(int id, String text) {
            final String target = SettingsActivity.translateTo(MainActivity.this);
            final String label = SettingsActivity.languageLabel(target);
            TRANSLATE.submit(() -> {
                String result;
                try {
                    result = Translator.translate(text, target);
                } catch (Exception e) {
                    result = e instanceof java.net.UnknownHostException
                            ? "Can't translate: no internet connection"
                            : "Couldn't translate right now";
                }
                final String r = result;
                runOnUiThread(() -> {
                    if (alive()) {
                        web.evaluateJavascript("window.showTranslation&&showTranslation(" + id + ","
                                + JSONObject.quote(r) + "," + JSONObject.quote(label) + ")", null);
                    }
                });
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!today().equals(shownDate)) {
            start();
        } else if (readerShown) {
            if (!sourceMatches(shownDate)) { // article source changed in settings
                shownDate = null;
                start();
                return;
            }
            applyFurigana(); // settings may have changed
            refreshFont();
            web.evaluateJavascript("window.last=''", null); // re-translate in a newly chosen language
        }
    }

    @Override
    protected void onPause() {
        if (speaker != null) speaker.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (speaker != null) speaker.shutdown();
        handler.removeCallbacksAndMessages(null);
        if (authWeb != null) authWeb.destroy();
        authWeb = null;
        if (web != null) web.destroy();
        web = null;
        super.onDestroy();
    }

    // ---- Daily flow -------------------------------------------------------

    private void start() {
        final String today = today();
        final String tomorrow = dayOffset(1);
        shownDate = today;
        readerShown = false;
        cleanUp(today);
        speaker.clearCache();

        if (isReady(today) && !sourceMatches(today)) deleteDay(today); // source setting changed

        if (isReady(today)) {
            showArticle(today);
            refreshFont();
            signInThen(() -> WORK.submit(() -> {
                if (isReady(tomorrow) && !sourceMatches(tomorrow)) deleteDay(tomorrow);
                prepareQuietly(tomorrow);
                ensureFont(tomorrow);
            }));
            return;
        }

        showLoading();
        progress(0.08f, "NHKに接続しています", "Connecting to NHK");
        signInThen(() -> WORK.submit(() -> {
            try {
                prepare(today, this::progress);
                if (SettingsActivity.dailyFont(this) && !Fonts.has(dayDir(today))) {
                    progress(1f, "今日のフォントを準備しています", "Getting today's font");
                    ensureFont(today);
                }
                runOnUiThread(() -> {
                    if (alive() && today.equals(shownDate)) showArticle(today);
                });
                if (isReady(tomorrow) && !sourceMatches(tomorrow)) deleteDay(tomorrow);
                prepareQuietly(tomorrow);
                ensureFont(tomorrow);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (alive() && today.equals(shownDate)) showError(e);
                });
            }
        }));
    }

    // ---- NHK sign-in in a hidden browser --------------------------------------

    /**
     * Opens NHK's "abroad" sign-in link in an invisible WebView (a real
     * browser, so any script in the sign-in runs), then hands the resulting
     * cookies to the downloader. Skipped if we signed in within the hour.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void signInThen(Runnable next) {
        if (SystemClock.elapsedRealtime() - signedInAt < SESSION_MS && signedInAt != 0) {
            next.run();
            return;
        }
        if (afterSignIn != null) { // a sign-in is already running: chain onto it
            Runnable prev = afterSignIn;
            afterSignIn = () -> { prev.run(); next.run(); };
            return;
        }
        afterSignIn = next;
        if (authWeb == null) {
            authWeb = new WebView(this);
            WebSettings s = authWeb.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(authWeb, true);
            authWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    if (url != null && url.startsWith(NhkClient.EASY)) {
                        // Landed back on NHK Easy: signed in. Give its script a moment.
                        handler.removeCallbacks(finishSignIn);
                        handler.postDelayed(finishSignIn, 800);
                    }
                }
            });
        }
        authWeb.loadUrl(NhkClient.authUrl);
        handler.postDelayed(finishSignIn, 25000); // carry on regardless after 25s
    }

    /** Union of two Cookie headers (cookies can be scoped to /news/easy/ or /newsweb/). */
    private static String mergeCookies(String a, String b) {
        java.util.LinkedHashMap<String, String> all = new java.util.LinkedHashMap<>();
        for (String header : new String[]{a, b}) {
            if (header == null) continue;
            for (String pair : header.split(";")) {
                String t = pair.trim();
                int eq = t.indexOf('=');
                if (eq > 0) all.putIfAbsent(t.substring(0, eq), t);
            }
        }
        return all.isEmpty() ? null : String.join("; ", all.values());
    }

    private final Runnable finishSignIn = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            Runnable next = afterSignIn;
            afterSignIn = null;
            if (next == null) return;
            CookieManager cm = CookieManager.getInstance();
            cm.flush();
            String cookies = mergeCookies(cm.getCookie(NhkClient.EASY), cm.getCookie(RegularNews.BASE));
            if (cookies != null && !cookies.isEmpty()) {
                NHK.useBrowserCookies(cookies);
                signedInAt = SystemClock.elapsedRealtime();
            }
            if (authWeb != null) authWeb.loadUrl("about:blank");
            next.run();
        }
    };

    /** Gets the article for a date into its folder, unless it's already there. Runs on WORK. */
    private void prepare(String date, NhkClient.Progress p) throws Exception {
        if (isReady(date)) return;
        final String source = sourceFor(date);
        p.step(0.18f, "記事の一覧を取得しています", "Getting the list of articles");
        List<NhkClient.Item> items;
        if (SettingsActivity.SOURCE_REGULAR.equals(source)) {
            items = NHK.listRegular();
        } else {
            try {
                items = NHK.list();
            } catch (Exception first) {
                NHK.signIn(); // fresh session and one more go
                items = NHK.list();
            }
        }
        p.step(0.3f, "記事を選んでいます", "Choosing an article");

        SharedPreferences prefs = prefs();
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen_ids", new HashSet<>()));
        List<NhkClient.Item> pool = NhkClient.unseen(items, seen);
        if (pool.isEmpty()) pool = items;
        // Don't give tomorrow the same article as today (or vice versa).
        String other = prefs.getString("id_" + (date.equals(today()) ? dayOffset(1) : today()), "");
        if (pool.size() > 1) pool.removeIf(i -> i.id.equals(other));
        NhkClient.Item pick = pool.get(new Random().nextInt(pool.size()));

        NHK.download(pick, dayDir(date), p);

        seen.add(pick.id);
        prefs.edit().putStringSet("seen_ids", seen).putString("id_" + date, pick.id)
                .putString("src_" + date, source).apply();
    }

    private void prepareQuietly(String date) {
        try {
            prepare(date, (f, jp, en) -> { });
        } catch (Exception ignored) {
            // No problem: it'll be fetched when that day comes.
        }
    }

    // ---- Article source (NHK Easy / regular NHK / mix) -------------------------------

    /** Which source a date's article comes from: the setting, or for "mix" alternating days. */
    private String sourceFor(String date) {
        String s = SettingsActivity.articleSource(this);
        if (!SettingsActivity.SOURCE_MIX.equals(s)) return s;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long day = f.parse(date).getTime() / 86_400_000L;
            return day % 2 == 0 ? SettingsActivity.SOURCE_EASY : SettingsActivity.SOURCE_REGULAR;
        } catch (Exception e) {
            return SettingsActivity.SOURCE_EASY;
        }
    }

    private boolean sourceMatches(String date) {
        String saved = prefs().getString("src_" + date, SettingsActivity.SOURCE_EASY);
        return saved.equals(sourceFor(date));
    }

    private void deleteDay(String date) {
        NhkClient.deleteRecursive(dayDir(date));
        prefs().edit().remove("id_" + date).remove("src_" + date).remove("font_" + date).apply();
    }

    // ---- A different font each day ------------------------------------------------

    /** The font for a date: picked once at random, never the same as the day before. */
    private String fontFor(String date) {
        SharedPreferences prefs = prefs();
        String chosen = prefs.getString("font_" + date, null);
        if (chosen != null) return chosen;
        String yesterday = prefs.getString("font_" + shiftDate(date, -1), "");
        String tomorrow = prefs.getString("font_" + shiftDate(date, 1), "");
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String[] f : Fonts.FONTS) {
            if (!f[0].equals(yesterday) && !f[0].equals(tomorrow)) pool.add(f[0]);
        }
        chosen = pool.get(new Random().nextInt(pool.size()));
        prefs.edit().putString("font_" + date, chosen).apply();
        return chosen;
    }

    /** Downloads the day's font if the setting is on and it isn't there yet. Runs off the UI thread. */
    private boolean ensureFont(String date) {
        if (!SettingsActivity.dailyFont(this) || !isReady(date)) return false;
        if (Fonts.has(dayDir(date))) return true;
        try {
            Fonts.fetch(fontFor(date), dayDir(date));
            return true;
        } catch (Exception e) {
            return false; // the article just shows in the normal font
        }
    }

    /** Redraws the reader if the font it should use has changed (e.g. setting just switched on). */
    private void refreshFont() {
        if (!readerShown || shownDate == null) return;
        final String date = shownDate;
        boolean on = SettingsActivity.dailyFont(this);
        if (!on) {
            if (renderedFont != null) showArticle(date);
            return;
        }
        if (Fonts.has(dayDir(date))) {
            if (renderedFont == null) showArticle(date);
            return;
        }
        WORK.submit(() -> {
            if (ensureFont(date)) {
                runOnUiThread(() -> {
                    if (alive() && readerShown && date.equals(shownDate)) showArticle(date);
                });
            }
        });
    }

    private static String shiftDate(String date, int days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(date));
            c.add(Calendar.DAY_OF_YEAR, days);
            return f.format(c.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    /** Deletes every saved day before today (i.e. the day after you read it). */
    private void cleanUp(String today) {
        File[] days = articlesDir().listFiles();
        if (days == null) return;
        for (File d : days) {
            String name = d.getName().replace(".part", "");
            if (name.compareTo(today) < 0) {
                NhkClient.deleteRecursive(d);
                prefs().edit().remove("id_" + name).remove("font_" + name).remove("src_" + name).apply();
            }
        }
    }

    // ---- Screens ----------------------------------------------------------

    private void showLoading() {
        loadingShown = true;
        lastFraction = 0.02f;
        lastJp = "準備しています";
        lastEn = "Getting ready";
        web.loadDataWithBaseURL(null, Pages.loading(isDark()), "text/html", "UTF-8", null);
    }

    private void progress(float fraction, String jp, String en) {
        runOnUiThread(() -> {
            if (!alive() || !loadingShown) return;
            lastFraction = fraction;
            lastJp = jp;
            lastEn = en;
            pushProgress();
        });
    }

    private void pushProgress() {
        web.evaluateJavascript("window.setStep&&setStep(" + lastFraction + ","
                + JSONObject.quote(lastJp) + "," + JSONObject.quote(lastEn) + ")", null);
    }

    private void showArticle(String date) {
        loadingShown = false;
        String content = readFile(new File(dayDir(date), "content.html"));
        String base = "file://" + dayDir(date).getAbsolutePath() + "/";
        renderedFont = SettingsActivity.dailyFont(this) && Fonts.has(dayDir(date)) ? fontFor(date) : null;
        web.loadDataWithBaseURL(base, Pages.reader(content, isDark(), furiganaScript(), renderedFont),
                "text/html", "UTF-8", null);
        readerShown = true;
    }

    private void showError(Exception e) {
        loadingShown = false;
        shownDate = null; // try again next time the app is opened
        signedInAt = 0;   // and sign in to NHK afresh
        web.loadDataWithBaseURL(null, Pages.error(isDark(), RETRY, describe(e)),
                "text/html", "UTF-8", null);
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "No internet connection";
        if (e instanceof java.net.SocketTimeoutException) return "NHK took too long to answer";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void applyFurigana() {
        web.evaluateJavascript(furiganaScript(), null);
    }

    private String furiganaScript() {
        String mode = SettingsActivity.furiganaMode(this);
        String known = SettingsActivity.FURI_WK.equals(mode)
                ? SettingsActivity.knownKanji(this, SettingsActivity.wkLevel(this)) : "";
        return "applyFuri(" + JSONObject.quote(mode) + "," + JSONObject.quote(known) + ")";
    }

    // ---- Storage ----------------------------------------------------------

    private File articlesDir() {
        File d = new File(getCacheDir(), "articles");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File dayDir(String date) {
        return new File(articlesDir(), date);
    }

    private boolean isReady(String date) {
        return new File(dayDir(date), "content.html").isFile();
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private boolean alive() {
        return web != null && !isFinishing() && !isDestroyed();
    }

    private SharedPreferences prefs() {
        return getApplicationContext().getSharedPreferences("daily", MODE_PRIVATE);
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static String today() {
        return dayOffset(0);
    }

    private static String dayOffset(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }
}
