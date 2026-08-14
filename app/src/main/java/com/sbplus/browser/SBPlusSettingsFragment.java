package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * SBPlus embedded settings screen.
 *
 * Runs INSIDE the Samsung Browser settings activity (launched via the browser's own
 * fragment navigation). Extends plain androidx.fragment.app.Fragment (not obfuscated).
 *
 * IMPORTANT: This Fragment runs in the browser process, so it must NOT reference the
 * module's R.* resource IDs directly (the browser's Resources cannot resolve them).
 * All UI is built programmatically here for that reason.
 *
 * Settings are persisted to the module's own SharedPreferences (same name the hook
 * side reads via XSharedPreferences).
 */
public class SBPlusSettingsFragment extends Fragment {

    private static final String PREFS_NAME = "samsung_download_bridge";
    private static final String KEY_DOWNLOADER_PACKAGE = "downloader_package";
    private static final String DEFAULT_ADM_PACKAGE = "com.dv.adm";

    private EditText mPkgInput;
    private TextView mStatusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Color.rgb(247, 247, 247));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Status card
        mStatusText = new TextView(ctx);
        mStatusText.setTextColor(Color.rgb(0x33, 0x33, 0x33));
        mStatusText.setTextSize(13);
        mStatusText.setLineSpacing(dp(3), 1f);
        mStatusText.setBackgroundColor(Color.WHITE);
        mStatusText.setPadding(pad, pad, pad, pad);
        root.addView(mStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Section title
        TextView title = new TextView(ctx);
        title.setText("下载器包名");
        title.setTextColor(Color.rgb(0x1B, 0x1B, 0x1B));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(16);
        root.addView(title, titleLp);

        // Package input
        mPkgInput = new EditText(ctx);
        mPkgInput.setHint(DEFAULT_ADM_PACKAGE);
        mPkgInput.setSingleLine(true);
        mPkgInput.setTextSize(14);
        mPkgInput.setTextColor(Color.rgb(0x1B, 0x1B, 0x1B));
        mPkgInput.setBackgroundColor(Color.WHITE);
        mPkgInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(8);
        root.addView(mPkgInput, inputLp);

        // Hint
        TextView hint = new TextView(ctx);
        hint.setText("ADM=com.dv.adm   |   1DM=idm.internet.download.manager");
        hint.setTextColor(Color.rgb(0x88, 0x88, 0x88));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        root.addView(hint, hintLp);

        // Save button
        Button save = new Button(ctx);
        save.setText("保存");
        save.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(16);
        root.addView(save, saveLp);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Load current value
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mPkgInput.setText(prefs.getString(KEY_DOWNLOADER_PACKAGE, DEFAULT_ADM_PACKAGE));
        refreshStatus();

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = mPkgInput.getText().toString().trim();
                if (p.isEmpty()) {
                    Toast.makeText(ctx, "包名不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString(KEY_DOWNLOADER_PACKAGE, p).commit();
                Toast.makeText(ctx, "已保存: " + p, Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });

        return scroll;
    }

    private void refreshStatus() {
        if (mStatusText == null) return;
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String pkg = prefs.getString(KEY_DOWNLOADER_PACKAGE, DEFAULT_ADM_PACKAGE);
        mStatusText.setText(
                "SBPlus（LSPosed 模块）\n\n"
                + "目标应用：三星浏览器\n"
                + "下载桥：已启用\n"
                + "当前下载器：" + pkg + "\n\n"
                + "修改下载器包名后点击「保存」，重启浏览器生效。");
    }

    private int dp(int v) {
        return (int) (v * requireContext().getResources().getDisplayMetrics().density + 0.5f);
    }
}
