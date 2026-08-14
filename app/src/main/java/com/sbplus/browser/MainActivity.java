package com.sbplus.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

/**
 * SBPlus module entry / status screen.
 *
 * The user-visible launcher entry: shows the version, a button to jump straight into the
 * embedded SBPlus settings sub-page inside Samsung Browser, and a button to manage logs.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button openSettingsBtn = findViewById(R.id.btn_open_settings);
        Button openLogsBtn = findViewById(R.id.btn_open_logs);

        openSettingsBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.sec.android.app.sbrowser",
                        "com.sec.android.app.sbrowser.settings.SettingsActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Samsung's own deep-link: jump straight to the SBPlus sub-page
                // (PreferenceFragmentCustom is reused as our SBPlus container page).
                i.putExtra("sbrowser.settings.show_fragment",
                        "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom");
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                        "无法打开 SBPlus 设置: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        openLogsBtn.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogManagerActivity.class));
        });
    }
}
