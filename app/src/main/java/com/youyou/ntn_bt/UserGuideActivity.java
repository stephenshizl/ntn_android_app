package com.youyou.ntn_bt;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Offline guide. Reading instructions never sends commands to the terminal. */
public final class UserGuideActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding); scroll.addView(body); setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        TextView title = new TextView(this); title.setText(R.string.guide_title); title.setTextSize(24); body.addView(title);
        int[][] sections = {
                {R.string.guide_connect_title, R.string.guide_connect},
                {R.string.guide_test_title, R.string.guide_test},
                {R.string.guide_settings_title, R.string.guide_settings},
                {R.string.guide_mode_title, R.string.guide_mode},
                {R.string.guide_name_title, R.string.guide_name},
                {R.string.guide_gnss_title, R.string.guide_gnss},
                {R.string.guide_ota_title, R.string.guide_ota},
                {R.string.guide_errors_title, R.string.guide_errors}};
        for (int[] section : sections) {
            TextView heading = new TextView(this); heading.setText(section[0]); heading.setTextSize(18);
            heading.setPadding(0, padding, 0, padding / 2); body.addView(heading);
            TextView text = new TextView(this); text.setText(section[1]); text.setTextIsSelectable(true); body.addView(text);
        }
        Button back = new Button(this); back.setText(R.string.guide_back); back.setOnClickListener(v -> finish()); body.addView(back);
    }
}
