package com.nhkdaily;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
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
    private static final NhkClient NHK = new NhkClient();

    private WebView web;
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

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);       // saved articles and pictures live in app storage
        s.setBlockNetworkLoads(true);     // the reader never touches the network itself
        // Long-press selection stays on, so Android's Translate / Copy menu works.
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
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!today().equals(shownDate)) {
            start();
        } else if (readerShown) {
            applyFurigana(); // settings may have changed
        }
    }

    @Override
    protected void onDestroy() {
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

        if (isReady(today)) {
            showArticle(today);
            WORK.submit(() -> prepareQuietly(tomorrow));
            return;
        }

        showLoading();
        WORK.submit(() -> {
            try {
                prepare(today, this::progress);
                runOnUiThread(() -> {
                    if (alive() && today.equals(shownDate)) showArticle(today);
                });
                prepareQuietly(tomorrow);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (alive() && today.equals(shownDate)) showError(e);
                });
            }
        });
    }

    /** Gets the article for a date into its folder, unless it's already there. Runs on WORK. */
    private void prepare(String date, NhkClient.Progress p) throws Exception {
        if (isReady(date)) return;
        p.step(0.08f, "NHKに接続しています", "Connecting to NHK");
        List<NhkClient.Item> items;
        try {
            items = NHK.list();
        } catch (Exception first) {
            NHK.signIn(); // fresh session and one more go
            items = NHK.list();
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
        prefs.edit().putStringSet("seen_ids", seen).putString("id_" + date, pick.id).apply();
    }

    private void prepareQuietly(String date) {
        try {
            prepare(date, (f, jp, en) -> { });
        } catch (Exception ignored) {
            // No problem: it'll be fetched when that day comes.
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
                prefs().edit().remove("id_" + name).apply();
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
        web.loadDataWithBaseURL(base, Pages.reader(content, isDark(), furiganaScript()),
                "text/html", "UTF-8", null);
        readerShown = true;
    }

    private void showError(Exception e) {
        loadingShown = false;
        shownDate = null; // try again next time the app is opened
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
