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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
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
    private static final String KEY_LANG = "translate_to";

    /** Google Translate code, native name, English name. */
    static final String[][] LANGUAGES = {
            {"en", "English", "English"},
            {"zh-CN", "简体中文", "Chinese (Simplified)"},
            {"zh-TW", "繁體中文", "Chinese (Traditional)"},
            {"ko", "한국어", "Korean"},
            {"es", "Español", "Spanish"},
            {"fr", "Français", "French"},
            {"de", "Deutsch", "German"},
            {"it", "Italiano", "Italian"},
            {"pt", "Português", "Portuguese"},
            {"nl", "Nederlands", "Dutch"},
            {"sv", "Svenska", "Swedish"},
            {"pl", "Polski", "Polish"},
            {"ru", "Русский", "Russian"},
            {"uk", "Українська", "Ukrainian"},
            {"tr", "Türkçe", "Turkish"},
            {"ar", "العربية", "Arabic"},
            {"hi", "हिन्दी", "Hindi"},
            {"ne", "नेपाली", "Nepali"},
            {"vi", "Tiếng Việt", "Vietnamese"},
            {"th", "ไทย", "Thai"},
            {"id", "Bahasa Indonesia", "Indonesian"},
            {"ms", "Bahasa Melayu", "Malay"},
            {"tl", "Filipino", "Filipino"},
            {"my", "မြန်မာ", "Burmese"},
            {"mn", "Монгол", "Mongolian"},
    };

    private static final String KEY_DAILY_FONT = "daily_font";

    /** "A different font each day" (off by default). */
    public static boolean dailyFont(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DAILY_FONT, false);
    }

    public static String translateTo(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "en");
    }

    /** The language's own name, shown as the label on the translation card. */
    public static String languageLabel(String code) {
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return l[1];
        return code;
    }

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

        // A different font each day
        TextView fh = heading("Reading");
        fh.setPadding(0, dp(28), 0, dp(4));
        root.addView(fh);

        Switch font = new Switch(this);
        font.setText("A different font each day");
        font.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        font.setMinHeight(dp(52));
        font.setChecked(dailyFont(this));
        font.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(KEY_DAILY_FONT, on).apply());
        root.addView(font);

        StringBuilder styles = new StringBuilder();
        for (String[] f : Fonts.FONTS) {
            if (styles.length() > 0) styles.append(", ");
            styles.append(f[1].toLowerCase(java.util.Locale.ROOT));
        }
        TextView fontHint = new TextView(this);
        fontHint.setText("See the same kanji in different styles, so you don't get used to just one. "
                + "Rotates through ten open-source fonts: " + styles + ".");
        fontHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fontHint.setAlpha(0.6f);
        root.addView(fontHint);

        // Translation language
        TextView th = heading("Translate selected text into");
        th.setPadding(0, dp(28), 0, dp(4));
        root.addView(th);

        String[] names = new String[LANGUAGES.length];
        int current = 0;
        String chosen = translateTo(this);
        for (int i = 0; i < LANGUAGES.length; i++) {
            names[i] = LANGUAGES[i][1].equals(LANGUAGES[i][2])
                    ? LANGUAGES[i][1] : LANGUAGES[i][1] + "  ·  " + LANGUAGES[i][2];
            if (LANGUAGES[i][0].equals(chosen)) current = i;
        }
        Spinner lang = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        lang.setAdapter(adapter);
        lang.setSelection(current);
        lang.setMinimumHeight(dp(52));
        lang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putString(KEY_LANG, LANGUAGES[pos][0]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(lang);

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
