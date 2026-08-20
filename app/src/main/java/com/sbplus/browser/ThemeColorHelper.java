package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ThemeColorHelper — 自定义主题色.
 *
 * 结构:
 *  主列表(8 行):
 *    主页图标 / 主页文字 / 设置文字(展开2项:标题/说明) / 设置页大栏目背景色
 *    / 网页文字 / 网页背景 / 开关(展开3项:开启/滑块/关闭)
 *
 * 独立 slot(10 个)存储 0xRRGGBB; -1 = 未启用(保持默认).
 */
public final class ThemeColorHelper {

    // slot 常量
    public static final int S_HOME_ICON = 0;
    public static final int S_HOME_TEXT = 1;
    public static final int S_SETTINGS_TITLE = 2;
    public static final int S_SETTINGS_DESC = 3;
    public static final int S_SETTINGS_BG = 4;
    public static final int S_WEB_TEXT = 5;
    public static final int S_WEB_BG = 6;
    public static final int S_SWITCH_ON = 7;
    public static final int S_SWITCH_THUMB = 8;
    public static final int S_SWITCH_OFF = 9;

    private static final String[] KEYS = {
        "theme_home_icon", "theme_home_text", "theme_settings_title", "theme_settings_desc",
        "theme_settings_bg", "theme_web_text", "theme_web_bg",
        "theme_switch_on", "theme_switch_thumb", "theme_switch_off"
    };

    // 色板
    private static final int[] PRESETS = {
        0xFF69F0AE, 0xFF00C853, 0xFF03A9F4, 0xFF3E91FF, 0xFF7C4DFF,
        0xFFD81B60, 0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF212121,
        0xFF505E81, 0xFF8C6836, 0xFFFFFFFF, 0xFF000000, 0xFF808080
    };

    private ThemeColorHelper() {}

    public static String prefName() { return "sbplus_prefs"; }

    // ============ 读取 ============
    public static int getSlot(Context ctx, int slot) {
        try {
            return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).getInt(KEYS[slot], -1);
        } catch (Throwable t) { return -1; }
    }
    public static boolean isSet(Context ctx, int slot) { return getSlot(ctx, slot) != -1; }
    public static void setSlot(Context ctx, int slot, int color) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putInt(KEYS[slot], color).apply(); }
        catch (Throwable ignored) {}
    }
    public static void clearSlot(Context ctx, int slot) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putInt(KEYS[slot], -1).apply(); }
        catch (Throwable ignored) {}
    }
    /** 读 slot, 未设置返回 defaultValue. */
    public static int color(Context ctx, int slot, int def) {
        int c = getSlot(ctx, slot);
        return c == -1 ? def : c;
    }

    private static String MASTER_KEY() { return "theme_color_enabled"; }
    private static boolean isMasterEnabled(Context ctx) {
        try { return ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).getBoolean(MASTER_KEY(), false); }
        catch (Throwable t) { return false; }
    }
    private static void setMasterEnabled(Context ctx, boolean on) {
        try { ctx.getSharedPreferences(prefName(), Context.MODE_PRIVATE).edit().putBoolean(MASTER_KEY(), on).apply(); }
        catch (Throwable ignored) {}
    }

    // ================= 设置入口条目 =================
    public static Object buildEntry(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = Class.forName(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", false, cl);
            Object pref = switchPrefCls.getConstructor(Context.class).newInstance(ctx);
            setProperty(pref, "setTitle", "自定义主题色");
            setProperty(pref, "setKey", "theme_color_enabled");
            setProperty(pref, "setSummary", "开关启用后，点击配置各项颜色");
            setProperty(pref, "setChecked", Boolean.valueOf(isMasterEnabled(ctx)));
            try { setProperty(pref, "setSelectable", Boolean.TRUE); } catch (Throwable ignored) {}
            try { setProperty(pref, "setDividerVisible", Boolean.TRUE); } catch (Throwable ignored) {}

            // 点击条目 -> 弹配色列表
            bindClick(pref, cl, new Runnable() {
                @Override public void run() { showList(ctx, 0); }
            });

            // 切换开关 -> 保存 enabled
            try {
                Class<?> changeListener = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
                Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{changeListener},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                    setMasterEnabled(ctx, en);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable ignored) {}
                            return Boolean.FALSE;
                        }
                    });
                java.lang.reflect.Method set = pref.getClass().getMethod("setOnPreferenceChangeListener", changeListener);
                set.invoke(pref, listener);
            } catch (Throwable t) { log("change bind err " + t); }

            return pref;
        } catch (Throwable t) { log("entry: " + t); return null; }
    }

    // 分组定义
    static final int[][] CHILDREN = {
        null, null,
        new int[]{ S_SETTINGS_TITLE, S_SETTINGS_DESC },   // 设置文字 -> 标题/说明
        null, null, null,
        new int[]{ S_SWITCH_ON, S_SWITCH_THUMB, S_SWITCH_OFF }   // 开关 -> 3 色
    };
    static final String[] ROOT_ZH = { "主页图标","主页文字","设置文字","设置页大栏目背景色","网页文字","网页背景","开关" };
    static final String[][] CHILD_ZH = {
        null, null, { "标题","说明" }, null, null, null,
        { "开启色","滑块色(拇指)","关闭色" }
    };
    static final int[] ROOT_SLOT = { S_HOME_ICON, S_HOME_TEXT, -1, S_SETTINGS_BG, S_WEB_TEXT, S_WEB_BG, -1 };

    /** level 0=主列表, 1=子列表(rootIndex 指定父行). */
    static void showList(final Context ctx, int rootIndex) {
        try {
            boolean isChild = rootIndex != 0;
            final int parentRoot = rootIndex;
            int[] rows; String[] labels; final int[] slots;
            String title;
            if (isChild) {
                rows = CHILDREN[parentRoot];
                labels = CHILD_ZH[parentRoot];
                slots = rows;
                title = labels[0].length() > 0 ? ROOT_ZH[parentRoot] : ROOT_ZH[parentRoot];
            } else {
                rows = new int[]{0,1,2,3,4,5,6};
                labels = ROOT_ZH;
                slots = ROOT_SLOT;
                title = "自定义主题色";
            }

            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 6);
            ll.setPadding(pad, pad, pad, pad);

            for (int i = 0; i < rows.length; i++) {
                final int slot = slots[i];
                final int rowRoot = isChild ? parentRoot : rows[i];
                String label = labels[i];
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));

                // 色块(仅叶子有)
                View swatch = new View(ctx);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28));
                slp.rightMargin = dp(ctx, 12);
                swatch.setLayoutParams(slp);
                int scolor = slot == -1 ? 0xFFCCCCCC : getSlot(ctx, slot);
                swatch.setBackgroundColor(scolor == -1 ? 0xFFCCCCCC : scolor);
                row.addView(swatch);

                // 名称 + 展开提示
                TextView tv = new TextView(ctx);
                String suffix = (!isChild && CHILDREN[rows[i]] != null) ? "  ›" : "";
                tv.setText(label + suffix);
                tv.setTextSize(15);
                tv.setTextColor(0xFF000000);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);

                // 状态
                TextView st = new TextView(ctx);
                st.setText(slot == -1 ? "" : (getSlot(ctx, slot) == -1 ? "(默认)" : "#" + hex6(getSlot(ctx, slot))));
                st.setTextSize(12);
                st.setTextColor(0xFF888888);
                row.addView(st);

                // 点击: 父行进子列表, 叶子进颜色选择
                if (slot == -1) {
                    final int childEntry = rows[i];
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showList(ctx, childEntry); }
                    });
                } else {
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showColorPicker(ctx, slot, getSlot(ctx, slot)); }
                    });
                    row.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            clearSlot(ctx, slot); showList(ctx, isChild ? parentRoot : 0);
                            toast(ctx, "已恢复默认"); return true;
                        }
                    });
                }
                ll.addView(row);
            }

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(ll);
            new android.app.AlertDialog.Builder(ctx)
                .setTitle(title + (isChild ? "" : "\n(长按某项恢复默认)"))
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
        } catch (Throwable t) { log("list: " + t); }
    }

    // ================= 颜色选择 =================
    static void showColorPicker(final Context ctx, final int slot, int current) {
        try {
            final int startColor = current == -1 ? 0xFF505E81 : current;
            final LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 14);
            root.setPadding(pad, pad, pad, pad);

            final View preview = new View(ctx);
            preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44)));
            preview.setBackgroundColor(startColor);
            root.addView(preview);
            root.addView(spacer(ctx, dp(ctx, 8)));

            final EditText hexEt = new EditText(ctx);
            hexEt.setText("#" + hex6(startColor));
            hexEt.setSingleLine(true);
            hexEt.setTextSize(15);
            hexEt.setTextColor(0xFF000000);
            hexEt.setGravity(Gravity.CENTER);
            root.addView(hexEt);
            root.addView(spacer(ctx, dp(ctx, 8)));

            final HsvPicker picker = new HsvPicker(ctx);
            picker.attach(hexEt, preview);
            picker.setColor(startColor);
            picker.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 330)));
            root.addView(picker);
            root.addView(spacer(ctx, dp(ctx, 8)));

            LinearLayout presetRow = new LinearLayout(ctx);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView presetLabel = new TextView(ctx);
            presetLabel.setText("预设:");
            presetLabel.setTextSize(13);
            presetLabel.setTextColor(0xFF666666);
            presetRow.addView(presetLabel);
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            for (final int pc : PRESETS) {
                View sw = new View(ctx);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
                slp.leftMargin = dp(ctx, 6);
                sw.setLayoutParams(slp);
                sw.setBackgroundColor(pc);
                sw.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        hexEt.setText("#" + hex6(pc)); picker.setColor(pc); preview.setBackgroundColor(pc);
                    }
                });
                inner.addView(sw);
            }
            hs.addView(inner);
            presetRow.addView(hs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(presetRow);

            new android.app.AlertDialog.Builder(ctx)
                .setTitle(labelFor(slot))
                .setView(root)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        int c = parseHex(hexEt.getText().toString());
                        if (c != -1) { setSlot(ctx, slot, c); toast(ctx, "已保存"); }
                        else toast(ctx, "颜色格式错误");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Throwable t) { log("picker: " + t); }
    }

    static String labelFor(int slot) {
        switch (slot) {
            case S_HOME_ICON: return "主页图标";
            case S_HOME_TEXT: return "主页文字";
            case S_SETTINGS_TITLE: return "设置文字 · 标题";
            case S_SETTINGS_DESC: return "设置文字 · 说明";
            case S_SETTINGS_BG: return "设置页大栏目背景色";
            case S_WEB_TEXT: return "网页文字";
            case S_WEB_BG: return "网页背景";
            case S_SWITCH_ON: return "开关 · 开启色";
            case S_SWITCH_THUMB: return "开关 · 滑块色";
            case S_SWITCH_OFF: return "开关 · 关闭色";
            default: return "颜色";
        }
    }

    // ============ 工具 ============
    static String hex6(int c) {
        String h = Integer.toHexString(c & 0xFFFFFF);
        while (h.length() < 6) h = "0" + h;
        return h.toUpperCase();
    }
    static int parseHex(String s) {
        try {
            s = s.trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() == 6) return (int)(0xFF000000L | Long.parseLong(s, 16));
        } catch (Throwable ignored) {}
        return -1;
    }
    static int dp(Context ctx, float v) { return (int)(v * ctx.getResources().getDisplayMetrics().density + 0.5f); }
    static View spacer(Context ctx, int h) { View v = new View(ctx); v.setLayoutParams(new LinearLayout.LayoutParams(1, h)); return v; }
    static void toast(Context ctx, String msg) { try { android.widget.Toast.makeText(ctx, msg, 0).show(); } catch (Throwable ignored) {} }
    static void log(String msg) { try { de.robv.android.xposed.XposedBridge.log("[SBPlus] themecolor " + msg); } catch (Throwable ignored) {} }
    static Context getCtx(Object pref) { try { return (Context) de.robv.android.xposed.XposedHelpers.callMethod(pref, "getContext"); } catch (Throwable t) { return null; } }
    static void setProperty(Object obj, String method, Object arg) {
        try { de.robv.android.xposed.XposedHelpers.callMethod(obj, method, arg); } catch (Throwable t) { log("call " + method + " err " + t); }
    }

    static Class<?> listenerParamType(Class<?> cls, String methodName) {
        try {
            for (java.lang.reflect.Method mm : cls.getMethods()) {
                if (mm.getName().equals(methodName)) {
                    Class<?>[] pts = mm.getParameterTypes();
                    if (pts.length == 1) return pts[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    static void bindClick(Object pref, ClassLoader cl, final Runnable r) {
        try {
            java.lang.reflect.Method mm = null;
            for (java.lang.reflect.Method m : pref.getClass().getMethods()) {
                if (m.getName().equals("setOnPreferenceClickListener") && m.getParameterTypes().length == 1) { mm = m; break; }
            }
            if (mm == null) return;
            final Class<?> lc = mm.getParameterTypes()[0];
            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{lc},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try { if (m.getName().equals("onPreferenceClick")) { r.run(); return Boolean.TRUE; } }
                        catch (Throwable t) {}
                        return Boolean.FALSE;
                    }
                });
            mm.invoke(pref, listener);
        } catch (Throwable t) { log("bind: " + t); }
    }
}
