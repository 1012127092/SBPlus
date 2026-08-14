package com.sbplus.browser;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Log management screen: view, export and delete module logs, plus edit retention
 * settings (keep-days and max size). Logs live in this app's filesDir via LogProvider,
 * and retention settings are shared to the hook-side cleanup via SharedPreferences.
 */
public class LogManagerActivity extends Activity {

    private static final Uri LOG_URI = Uri.parse("content://com.sbplus.browser.log");
    private static final String CONFIG_PREFS = "sbplus_log_config";

    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        SharedPreferences cfg = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE);

        // --- retention config ---
        TextView cfgLabel = new TextView(this);
        cfgLabel.setText("日志保留策略");
        cfgLabel.setTextSize(15);
        cfgLabel.setTextColor(0xFF1B1B1B);
        root.addView(cfgLabel);

        TextView daysLabel = new TextView(this);
        daysLabel.setText("保留天数（超过此天数的日志自动删除）");
        daysLabel.setTextSize(12);
        daysLabel.setTextColor(0xFF666666);
        daysLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(daysLabel);

        final EditText daysInput = new EditText(this);
        daysInput.setHint("默认 7 天");
        daysInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        daysInput.setText(String.valueOf(cfg.getInt("log_keep_days", LogProvider.DEFAULT_KEEP_DAYS)));
        root.addView(daysInput);

        TextView mbLabel = new TextView(this);
        mbLabel.setText("容量上限（日志总大小超过此值，自动删除最早的日志）");
        mbLabel.setTextSize(12);
        mbLabel.setTextColor(0xFF666666);
        mbLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(mbLabel);

        final EditText mbInput = new EditText(this);
        mbInput.setHint("默认 10 MB");
        mbInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mbInput.setText(String.valueOf(cfg.getInt("log_max_mb", LogProvider.DEFAULT_MAX_MB)));
        root.addView(mbInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存设置");
        saveBtn.setOnClickListener(v -> {
            try {
                int days = Integer.parseInt(daysInput.getText().toString().trim());
                int mb = Integer.parseInt(mbInput.getText().toString().trim());
                if (days < 1) days = 1;
                if (mb < 1) mb = 1;
                cfg.edit().putInt("log_keep_days", days).putInt("log_max_mb", mb).commit();
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(saveBtn);

        // --- action buttons ---
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button viewBtn = new Button(this);
        viewBtn.setText("刷新日志");
        viewBtn.setOnClickListener(v -> loadLogs());
        btns.addView(viewBtn);

        Button exportBtn = new Button(this);
        exportBtn.setText("导出");
        exportBtn.setOnClickListener(v -> exportLogs());
        btns.addView(exportBtn);

        Button delBtn = new Button(this);
        delBtn.setText("删除");
        delBtn.setOnClickListener(v -> {
            getContentResolver().delete(LOG_URI, null, null);
            logText.setText("（日志已清空）");
            Toast.makeText(this, "日志已删除", Toast.LENGTH_SHORT).show();
        });
        btns.addView(delBtn);

        root.addView(btns);

        // --- log view ---
        logText = new TextView(this);
        logText.setTextIsSelectable(true);
        logText.setTextSize(11);
        logText.setTextColor(0xFF333333);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setText("点击「刷新日志」以查看");
        root.addView(logText);

        loadLogs();
    }

    private void loadLogs() {
        try {
            Cursor c = getContentResolver().query(
                    Uri.withAppendedPath(LOG_URI, "content"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                String text = c.getString(0);
                logText.setText(text == null || text.isEmpty() ? "（暂无日志）" : text);
                c.close();
            } else {
                logText.setText("（暂无日志）");
            }
        } catch (Throwable t) {
            logText.setText("读取日志失败: " + t);
        }
    }

    private void exportLogs() {
        String content = null;
        try {
            Cursor c = getContentResolver().query(
                    Uri.withAppendedPath(LOG_URI, "content"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                content = c.getString(0);
                c.close();
            }
        } catch (Throwable ignored) {}

        if (content == null || content.isEmpty()) {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "SBPlus 日志");
        send.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(send, "导出日志"));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
