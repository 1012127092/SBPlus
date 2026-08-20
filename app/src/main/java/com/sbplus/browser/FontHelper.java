package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FontHelper — 自定义字体（B方案：SAF 添加字体文件，列表管理多字体）。
 *
 * 字体文件统一存到应用私有外部目录 fonts/ 下（*.ttf|otf），
 * 用 ASCII 安全文件名（font_时间戳.ttf），彻底避开中文/特殊字符编码问题。
 *
 * 主页美化里"字体"条目是开关（启用/停用），点击弹出字体列表：
 *   每个已添加字体：点击选中生效，有删除按钮可删文件。
 *   底部有"添加字体"入口：发起 SAF 选 .ttf/.otf 复制到目录。
 *
 * prefs: sbplus_prefs
 *   font_selected : 当前选中的字体文件名，空=未选（用默认）
 *   font_enabled  : 自定义字体开关
 */
public final class FontHelper {

    public static final String KEY_SELECTED = "font_selected";
    public static final String KEY_ENABLED = "font_enabled";

    private FontHelper() {}

    public static String prefName() { return "sbplus_prefs"; }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE);
    }

    /** 字体目录：优先应用外部私有目录（必然可写），回退到内部私有目录。 */
    private static File dir(Context ctx) {
        File d = null;
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base != null) { d = new File(base, "fonts"); }
        } catch (Throwable ignored) {}
        if (d == null) d = new File(ctx.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ============ 存取 ============
    public static boolean isEnabled(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ENABLED, false); } catch (Throwable ignored) {}
        return false;
    }
    public static void setEnabled(Context ctx, boolean en) {
        try { sp(ctx).edit().putBoolean(KEY_ENABLED, en).apply(); } catch (Throwable ignored) {}
    }

    public static String selectedName(Context ctx) {
        try { return sp(ctx).getString(KEY_SELECTED, ""); } catch (Throwable ignored) {}
        return "";
    }
    public static void selectFont(Context ctx, String name) {
        try { sp(ctx).edit().putString(KEY_SELECTED, name == null ? "" : name).apply(); } catch (Throwable ignored) {}
    }

    /** 扫描字体目录，返回所有 *.ttf|otf 文件名，排序。 */
    public static List<String> listFonts(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            File[] fs = dir(ctx).listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"))) {
                        out.add(f.getName());
                    }
                }
            }
            Collections.sort(out);
        } catch (Throwable ignored) {}
        return out;
    }

    /** 删除指定字体文件。 */
    public static boolean deleteFont(Context ctx, String name) {
        try {
            File f = new File(dir(ctx), name);
            boolean ok = f.exists() && f.delete();
            if (ok && name.equals(selectedName(ctx))) selectFont(ctx, "");
            return ok;
        } catch (Throwable ignored) { return false; }
    }

    /** 当前选中字体的绝对路径，无则空。 */
    public static String selectedPath(Context ctx) {
        String n = selectedName(ctx);
        if (n == null || n.isEmpty()) return "";
        File f = new File(dir(ctx), n);
        return f.exists() ? f.getAbsolutePath() : "";
    }

    /** 是否应用（开关开且选中文件存在）。 */
    public static boolean shouldApply(Context ctx) {
        try {
            if (!isEnabled(ctx)) return false;
            String p = selectedPath(ctx);
            return p != null && !p.isEmpty();
        } catch (Throwable ignored) {}
        return false;
    }

    /** 加载选中字体，失败返回 null。 */
    public static Typeface loadTypeface(Context ctx) {
        try {
            String p = selectedPath(ctx);
            if (p == null || p.isEmpty()) { XposedBridgeLog("load: no selected path"); return null; }
            File f = new File(p);
            if (!f.exists()) { XposedBridgeLog("load: file missing " + p); return null; }
            Typeface tf = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Typeface.Builder(f).build()
                    : Typeface.createFromFile(f);
            if (tf == null) { XposedBridgeLog("load: builder returned null"); return null; }
            XposedBridgeLog("load OK tf=" + tf);
            return tf;
        } catch (Throwable t) { XposedBridgeLog("load err: " + t); return null; }
    }

    /** 复制 URI 字体到目录：文件名用 ASCII 安全名(时间戳), 并保存'原文件名->存储名'映射供列表显示。 */
    public static boolean addFontFromUri(Context ctx, android.net.Uri uri) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            String display = queryDisplayName(ctx, uri);
            boolean isOtf = display != null && display.toLowerCase().endsWith(".otf");
            String name = "font_" + System.currentTimeMillis() + (isOtf ? ".otf" : ".ttf");
            File target = new File(dir(ctx), name);
            int k = 1;
            while (target.exists()) {
                target = new File(dir(ctx), (isOtf ? "font_o" : "font_") + (System.currentTimeMillis() + (k++)) + (isOtf ? ".otf" : ".ttf"));
            }
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) { XposedBridgeLog("add font: openInputStream null for " + uri); return false; }
            out = new java.io.FileOutputStream(target);
            byte[] tmp = new byte[65536];
            int r;
            while ((r = in.read(tmp)) > 0) out.write(tmp, 0, r);
            out.flush(); out.close(); out = null;
            in.close(); in = null;
            // 保存显示名->存储名 映射(供列表显示原文件名)
            String stored = target.getName();
            mapSaveDisplay(ctx, stored, (display == null || display.isEmpty()) ? stored : display);
            XposedBridgeLog("add font OK -> " + target.getAbsolutePath() + " display=" + display);
            return true;
        } catch (Throwable t) { XposedBridgeLog("add font err: " + t); return false; }
        finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    // 显示名映射: prefs map "font_display_<storedName>" -> 原文件名
    private static void mapSaveDisplay(Context ctx, String stored, String display) {
        try { sp(ctx).edit().putString("font_display_" + stored, display).apply(); } catch (Throwable ignored) {}
    }
    public static String displayName(Context ctx, String stored) {
        try {
            String d = sp(ctx).getString("font_display_" + stored, "");
            if (d != null && !d.isEmpty()) return d;
        } catch (Throwable ignored) {}
        return stored;
    }

    private static String queryDisplayName(Context ctx, android.net.Uri uri) {
        try {
            android.database.Cursor cur = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0 && cur.getString(idx) != null) return cur.getString(idx);
                    }
                } finally { cur.close(); }
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        if (last != null) {
            int q = last.indexOf('?');
            if (q >= 0) last = last.substring(0, q);
            return last;
        }
        return null;
    }

    // ============ 设置条目 ============

    /** "字体"条目：SwitchPreferenceCustom 开关 + 点击弹字体列表。 */
    public static Object buildEntry(final Context ctx, ClassLoader cl) {
        try {
            Class<?> switchCls = Class.forName(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", false, cl);
            Object pref = switchCls.getConstructor(Context.class).newInstance(ctx);
            setProperty(pref, "setTitle", "自定义字体");
            setProperty(pref, "setKey", "sbplus_font");
            setProperty(pref, "setSummary", "开关启用后点击配置/管理字体");
            setProperty(pref, "setChecked", Boolean.valueOf(isEnabled(ctx)));
            try { setProperty(pref, "setSelectable", Boolean.TRUE); } catch (Throwable ignored) {}
            try { setProperty(pref, "setDividerVisible", Boolean.TRUE); } catch (Throwable ignored) {}

            // 点击 -> 弹字体列表
            bindClickListener(pref, cl, new Runnable() {
                @Override public void run() {
                    openList(ctx);
                }
            });

            // 开关切换 -> 保存 enabled
            bindChangeListener(pref, ctx, cl, new Runnable() {
                @Override public void run() {}
            });
            return pref;
        } catch (Throwable t) { XposedBridgeLog("buildEntry err: " + t); return null; }
    }

    /** 弹字体列表对话框：每行字体名+删除按钮，点行选中。 */
    public static void openList(final Context ctx) {
        try {
            final android.app.Activity act = (ctx instanceof android.app.Activity) ? (android.app.Activity) ctx : null;
            final List<String> fonts = listFonts(ctx);
            final String sel = selectedName(ctx);

            android.widget.LinearLayout ll = new android.widget.LinearLayout(ctx);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(ctx, 6);
            ll.setPadding(pad, pad, pad, pad);

            if (fonts.isEmpty()) {
                android.widget.TextView empty = new android.widget.TextView(ctx);
                empty.setText("（暂无字体，点下方添加字体添加）");
                empty.setTextSize(14); empty.setTextColor(0xFF888888);
                empty.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
                ll.addView(empty);
            } else {
                for (final String name : fonts) {
                    ll.addView(buildFontRow(ctx, name, name.equals(sel), act));
                }
            }

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle("自定义字体");
            b.setView(ll);
            b.setPositiveButton("添加字体", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { pickFont(ctx, act); }
            });
            b.setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    selectFont(ctx, ""); setEnabled(ctx, false);
                    toast(ctx, "已恢复系统默认字体");
                }
            });
            b.setNegativeButton("关闭", null);
            b.show();
        } catch (Throwable t) { XposedBridgeLog("openList err: " + t); }
    }

    private static android.view.View buildFontRow(final Context ctx, final String name, boolean used, final android.app.Activity act) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        row.setBackgroundColor(used ? 0xFFE3F2FD : 0x00000000);

        android.widget.TextView label = new android.widget.TextView(ctx);
        label.setText((used ? "[使用中] " : "") + displayName(ctx, name));
        label.setTextSize(15);
        label.setTextColor(0xFF000000);
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        // 删除按钮
        android.widget.Button del = new android.widget.Button(ctx);
        del.setText("删除");
        del.setTextSize(12);
        del.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                deleteFont(ctx, name);
                toast(ctx, "已删除: " + name);
                openList(ctx);
            }
        });
        row.addView(del);

        // 点行选中
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                selectFont(ctx, name);
                setEnabled(ctx, true);
                toast(ctx, "字体已应用: " + name);
            }
        });
        return row;
    }

    /** 发起 SAF 选 .ttf/.otf 添加。 */
    private static void pickFont(Context ctx, android.app.Activity act) {
        try {
            if (act == null) { toast(ctx, "无法获取界面环境"); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            // 用 */* 显示所有文件，避免 MIME 过滤把 .ttf/.otf 隐藏掉
            i.setType("*/*");
            act.startActivityForResult(i, MainHook.REQUEST_FONT_PICK);
        } catch (Throwable t) { XposedBridgeLog("pick err: " + t); toast(ctx, "无法打开文件选择器"); }
    }

    // ============ 反射工具 ============
    private static void setProperty(Object pref, String method, Object value) {
        try { de.robv.android.xposed.XposedHelpers.callMethod(pref, method, value); } catch (Throwable ignored) {}
    }
    private static Class<?> listenerParamType(Class<?> cls, String methodName) {
        try {
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length > 0) return m.getParameterTypes()[0];
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static void bindClickListener(final Object pref, ClassLoader cl, final Runnable onClick) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            if (listenerType == null) return;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                try { onClick.run(); } catch (Throwable ignored) {}
                                return Boolean.TRUE;
                            }
                        } catch (Throwable ignored) {}
                        return Boolean.FALSE;
                    }
                });
            pref.getClass().getMethod("setOnPreferenceClickListener", listenerType).invoke(pref, proxy);
        } catch (Throwable ignored) {}
    }
    private static void bindChangeListener(final Object pref, final Context ctx, ClassLoader cl, final Runnable onToggle) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            if (listenerType == null) return;
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                setEnabled(ctx, en);
                                try { onToggle.run(); } catch (Throwable ignored) {}
                                return Boolean.TRUE;
                            }
                        } catch (Throwable ignored) {}
                        return Boolean.FALSE;
                    }
                });
            pref.getClass().getMethod("setOnPreferenceChangeListener", listenerType).invoke(pref, proxy);
        } catch (Throwable ignored) {}
    }

    private static void XposedBridgeLog(String m) {
        try { de.robv.android.xposed.XposedBridge.log("[SBPlus] " + m); } catch (Throwable ignored) {}
    }
    private static void toast(Context ctx, String msg) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    private static int dp(Context ctx, int v) {
        try { return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f); } catch (Throwable t) { return v; }
    }
}
