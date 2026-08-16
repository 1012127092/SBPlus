package com.sbplus.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XSharedPreferences;

/**
 * SBPlus module entry / status screen.
 *
 * Shows the current version (tap to check for updates), a project link, and buttons
 * to jump into the embedded settings sub-page and the log manager. On launch it also
 * performs a silent update check (notification-style toast only, no dialog).
 */
public class MainActivity extends Activity {

    public static final String PREFS_NAME = "samsung_download_bridge";
    public static final String KEY_VERSION_NAME = "version_name";
    public static final String KEY_VERSION_CODE = "version_code";

    private TextView mVersionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 把版本号写入 prefs，供浏览器进程的 SBPlus 菜单读取（XSharedPreferences）。
        // 注意：必须用 makeWorldReadable 让浏览器进程（不同 UID）可读，否则读到旧值/空值。
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_VERSION_NAME, BuildConfig.VERSION_NAME)
                .putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE)
                .apply();
        try {
            XSharedPreferences xp = new XSharedPreferences(getPackageName(), PREFS_NAME);
            xp.makeWorldReadable();
            xp.reload();
        } catch (Throwable ignored) {}

        mVersionView = findViewById(R.id.tv_version);
        TextView projectView = findViewById(R.id.tv_project);
        Button openSettingsBtn = findViewById(R.id.btn_open_settings);
        Button openLogsBtn = findViewById(R.id.btn_open_logs);

        mVersionView.setText("版本 " + BuildConfig.VERSION_NAME + "（点击检测更新）");

        // 项目地址：点击用浏览器打开 GitHub 仓库
        projectView.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(UpdateChecker.projectUrl())));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开项目地址", Toast.LENGTH_SHORT).show();
            }
        });

        // 版本号：点击手动检测更新（有更新弹窗确认下载）
        mVersionView.setOnClickListener(v -> checkUpdate(true));

        openSettingsBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.sec.android.app.sbrowser",
                        "com.sec.android.app.sbrowser.settings.SettingsActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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

        // 启动时静默检测更新（只提示，不弹窗）
        checkUpdate(false);
    }

    /**
     * Query latest release from GitHub.
     *
     * @param interactive true: pull-to-check (dialog on success/failure);
     *                    false: auto-check on launch (toast only, no dialog).
     */
    private void checkUpdate(final boolean interactive) {
        final String local = BuildConfig.VERSION_NAME;
        mVersionView.setEnabled(false);
        UpdateChecker.check(local, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info, String error) {
                mVersionView.setEnabled(true);
                if (error != null) {
                    if (interactive) {
                        Toast.makeText(MainActivity.this,
                                "检测失败：" + error, Toast.LENGTH_LONG).show();
                    }
                    // silent failure: stay quiet
                    return;
                }
                if (info == null) return;
                if (info.newer) {
                    if (interactive) {
                        showUpdateDialog(info);
                    } else {
                        // auto-check: toast only
                        Toast.makeText(MainActivity.this,
                                "发现新版本 " + info.tagName + "，点击版本号更新",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    if (interactive) {
                        Toast.makeText(MainActivity.this,
                                "已是最新版本（" + info.tagName + "）",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void showUpdateDialog(final UpdateChecker.UpdateInfo info) {
        String note = info.body;
        if (note == null || note.trim().isEmpty()) note = "（无更新说明）";
        if (note.length() > 500) note = note.substring(0, 500) + "…";

        final String downloadUrl = info.downloadUrl;

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("发现新版本：" + info.tagName);
        b.setMessage("当前版本：" + BuildConfig.VERSION_NAME + "\n\n" + note);
        b.setPositiveButton("下载更新", (d, w) -> {
            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                UpdateChecker.openDownload(MainActivity.this, downloadUrl);
                Toast.makeText(MainActivity.this,
                        "已打开下载页面，下载后请手动安装", Toast.LENGTH_LONG).show();
            } else {
                UpdateChecker.openDownload(MainActivity.this, UpdateChecker.projectUrl());
                Toast.makeText(MainActivity.this,
                        "未找到 apk 资源，已打开项目页面", Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }
}
