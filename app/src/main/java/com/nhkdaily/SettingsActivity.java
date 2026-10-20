package com.nhkdaily;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Opened from Android Settings → Apps → Nikkan → the gear /
 * "Additional settings in the app". Not shown in the launcher.
 */
public class SettingsActivity extends Activity {

    public static final String FURI_ALL = "all";
    public static final String FURI_WK = "wk";
    public static final String FURI_NONE = "none";

    private static final String PREFS = "settings";
    private static final String KEY_MODE = "furigana_mode";
    private static final String KEY_OLD_BOOL = "furigana";
    private static final String KEY_LEVEL = "wk_level";

    public static String furiganaMode(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String m = p.getString(KEY_MODE, null);
        if (m != null) return m;
        // Older versions stored a simple on/off switch.
        return p.getBoolean(KEY_OLD_BOOL, true) ? FURI_ALL : FURI_NONE;
    }

    public static int wkLevel(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LEVEL, 1);
    }

    /** Every WaniKani kanji at the given level and below, as one string. */
    public static String knownKanji(Context c, int level) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("wanikani_levels.txt"), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                if (++n > level) break;
                sb.append(line.trim());
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private LinearLayout levelRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Nikkan settings");
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(heading("Furigana"));

        RadioGroup group = new RadioGroup(this);
        RadioButton all = radio("Show all furigana");
        RadioButton wk = radio("Hide furigana on kanji I know from WaniKani");
        RadioButton none = radio("Hide all furigana");
        group.addView(all);
        group.addView(wk);
        group.addView(none);
        root.addView(group);

        // WaniKani level picker
        levelRow = new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        levelRow.setGravity(Gravity.CENTER_VERTICAL);
        levelRow.setPadding(dp(32), dp(8), 0, 0);

        TextView label = new TextView(this);
        label.setText("My WaniKani level");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        levelRow.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        NumberPicker level = new NumberPicker(this);
        level.setMinValue(1);
        level.setMaxValue(60);
        level.setWrapSelectorWheel(false);
        level.setValue(wkLevel(this));
        level.setOnValueChangedListener((p, oldV, newV) ->
                prefs.edit().putInt(KEY_LEVEL, newV).apply());
        levelRow.addView(level);
        root.addView(levelRow);

        TextView hint = new TextView(this);
        hint.setText("Kanji from your current level and every level before it are treated as known. "
                + "A word keeps its furigana unless you know every kanji in it. "
                + "No API key needed: the level list is built into the app.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setAlpha(0.6f);
        hint.setPadding(dp(32), dp(4), 0, 0);
        root.addView(hint);
        final View hintView = hint;

        String mode = furiganaMode(this);
        (FURI_WK.equals(mode) ? wk : FURI_NONE.equals(mode) ? none : all).setChecked(true);
        setLevelVisible(FURI_WK.equals(mode), hintView);

        group.setOnCheckedChangeListener((g, id) -> {
            String m = id == wk.getId() ? FURI_WK : id == none.getId() ? FURI_NONE : FURI_ALL;
            prefs.edit().putString(KEY_MODE, m).apply();
            setLevelVisible(FURI_WK.equals(m), hintView);
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void setLevelVisible(boolean on, View hint) {
        levelRow.setVisibility(on ? View.VISIBLE : View.GONE);
        hint.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setAlpha(0.7f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private RadioButton radio(String text) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setMinHeight(dp(52));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
