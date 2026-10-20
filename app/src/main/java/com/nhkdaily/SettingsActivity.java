package com.nhkdaily;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Opened from Android Settings → Apps → NHK Daily → the gear /
 * "Additional settings in the app". Not shown in the launcher.
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "settings";
    private static final String KEY_FURIGANA = "furigana";

    public static boolean furigana(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FURIGANA, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("一日の記事 設定");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        Switch furi = new Switch(this);
        furi.setText("Show furigana");
        furi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        furi.setGravity(Gravity.CENTER_VERTICAL);
        furi.setMinHeight(dp(56));
        furi.setChecked(furigana(this));
        furi.setOnCheckedChangeListener((b, on) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_FURIGANA, on).apply());

        TextView hint = new TextView(this);
        hint.setText("Reading aids shown above kanji in the article.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setAlpha(0.6f);

        root.addView(furi);
        root.addView(hint);
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
