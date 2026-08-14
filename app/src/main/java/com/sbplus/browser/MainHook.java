package com.sbplus.browser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * SBPlus - Samsung Browser enhancements (LSPosed module).
 *
 * Runtime framework: LSPosed (compatible with standard Xposed API).
 * Target app: Samsung Internet, package com.sec.android.app.sbrowser.
 *
 * Feature 1: download bridge (redirect downloads to third-party downloaders).
 *   Approach:
 *     Hook Samsung Browser's download callback, capture download params
 *     (url / cookie / userAgent / fileName / referrer / mimeType),
 *     then dispatch an Intent to a user-configurable third-party downloader.
 *
 *   Hook points (reverse-engineered, v30.0.0.67):
 *     com.sec.terrace.browser.download.TinDownloadController
 *       - onDownloadStarted(TerraceDownloadInfo)        (preferred)
 *       - onPreDownloadRequest(...)                     (fallback)
 *       - requestDownload(...)                          (last resort)
 *
 *   Data object: com.sec.terrace.browser.download.TerraceDownloadInfo
 *     getters: getUrl() getOriginalUrl() getCookie() getUserAgent()
 *              getFileName() getReferrer() getContentDisposition() getMimeType()
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // Global application Context (captured from SBrowserApplication.onCreate).
    private static volatile Context sAppContext;

    private static final String SBROWSER_PACKAGE = "com.sec.android.app.sbrowser";
    private static final String SBROWSER_BETA_PACKAGE = "com.sec.android.app.sbrowser.beta";

    // Downloader config (user-customizable via XSharedPreferences).
    private static final String MODULE_PACKAGE = "com.sbplus.browser";
    private static final String PREFS_NAME = "samsung_download_bridge";
    private static final String KEY_DOWNLOADER_PACKAGE = "downloader_package";
    private static final String KEY_DOWNLOADER_CLASS = "downloader_activity";
    private static final String KEY_BLOCK_NATIVE = "block_native_download";
    private static final String KEY_ENABLE_BRIDGE = "enable_download_bridge";
    private static final String KEY_ENABLE_GRID_MENU = "enable_grid_menu";
    private static final String KEY_ENABLE_REGION_LOCK = "enable_region_lock";
    private static final String KEY_REGION_CODE = "region_code";
    private static final String KEY_ENABLE_UA = "enable_ua_override";
    private static final String KEY_UA = "ua_string";
    private static final String ARG_PAGE = "sbplus_page";
    private static final String PAGE_DOWNLOADER_PICKER = "downloader_picker";
    private static final String PAGE_REGION_PICKER = "region_picker";
    private static final String PAGE_UA_PICKER = "ua_picker";
    private static final String PAGE_CLEAN_SETTINGS_PICKER = "clean_settings_picker";
    private static final String PAGE_VIDEO_BG_PICKER = "video_bg_picker";
    private static final String KEY_ENABLE_CLEAN_SETTINGS = "enable_clean_settings";
    private static final String KEY_HIDDEN_SETTINGS = "hidden_settings";
    private static final String KEY_ENABLE_BLOCK_UPDATE = "enable_block_update";
    private static final String KEY_ENABLE_VIDEO_BG = "enable_video_bg";
    private static final String KEY_VIDEO_BG_PATH = "video_bg_path";
    private static final String KEY_ENABLE_USERSCRIPT = "enable_userscript";

    // Default target downloaders (overridable).
    private static final String DEFAULT_ADM_PACKAGE = "com.dv.adm";
    private static final String DEFAULT_1DM_PACKAGE = "idm.internet.download.manager";
    private static final String DEFAULT_IDM_PLUS_PACKAGE = "idm.internet.download.manager.plus";

    private static final String[][] PRESET_DOWNLOADERS = new String[][]{
            {"ADM（高级下载管理器）", DEFAULT_ADM_PACKAGE},
            {"1DM（Internet Download Manager）", DEFAULT_1DM_PACKAGE},
            {"IDM+（Internet Download Manager Plus）", DEFAULT_IDM_PLUS_PACKAGE},
    };

    // User-Agent presets for the "浏览器标识" (UA override) feature.
    // label -> full UA string.
    private static final String[][] PRESET_UAS = new String[][]{
            {"桌面 Chrome（Windows）", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"},
            {"Android Chrome（手机）", "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"},
            {"iPhone Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"},
    };

    // 主设置页（settings_fragment.xml）的所有可屏蔽项目：preference key -> 中文标题。
    // key 以 "@" 开头的是特殊项（非 preference）：@search = 搜索框、@update_card = 更新提示卡片。
    private static final String[][] SETTINGS_ITEMS = new String[][]{
            {"@search", "顶部的搜索"},
            {"@update_card", "更新提示卡片"},
            {"pref_parental_control_notice", "家庭组织者管理提示"},
            {"cloud_sync", "与三星云同步"},
            {"pref_browsing_assist", "浏览助手"},
            {"pref_drawing_assist", "绘图助手"},
            {"set_homepage", "主页"},
            {"set_search_engine", "地址栏搜索"},
            {"pref_auto_close_unused_tabs", "自动关闭未使用的页面"},
            {"layout_and_menu", "布局和菜单"},
            {"display", "网页查看和滚动"},
            {"privacy", "安全与隐私"},
            {"personal_data", "个人浏览数据"},
            {"sites_and_contents", "网站和下载"},
            {"pref_notifications", "通知"},
            {"useful_features", "实用功能"},
            {"pref_category_privacy", "隐私（小标题）"},
            {"pref_privacy_notice", "隐私声明"},
            {"notice_board", "隐私声明历史记录"},
            {"pref_permissions", "权限"},
            {"pref_leave_internet", "停止使用三星浏览器"},
            {"about", "关于三星浏览器"},
            {"pref_contact_us", "联系我们"},
    };

    // Country/region ISO codes for the "锁定国家/地区" feature (region lock).
    // Mirrors Samsung Browser's own "Feature variation test > Country iso code" options
    // (res/values/arrays.xml pref_country_iso_code_values).
    private static final String[][] PRESET_REGIONS = new String[][]{
            {"阿根廷", "AR"},
            {"巴西", "BR"},
            {"加拿大", "CA"},
            {"中国大陆", "CN"},
            {"德国", "DE"},
            {"西班牙", "ES"},
            {"法国", "FR"},
            {"英国", "GB"},
            {"印度", "IN"},
            {"意大利", "IT"},
            {"日本", "JP"},
            {"韩国", "KR"},
            {"俄罗斯", "RU"},
            {"土耳其", "TR"},
            {"美国", "US"},
            {"越南", "VN"},
            {"其他", "Other"},
    };

    // ADM's browser-intent entry point (activity-alias). Verified by reverse
    // engineering: com.dv.get.AEditorBrow filters on VIEW + http/https + *\/*.
    // Targeting it directly skips the system ResolverActivity chooser.
    private static final String DEFAULT_ADM_CLASS = "com.dv.get.AEditorBrow";

    private XSharedPreferences prefs;

    // Kept for later features / embedded fragment loading.
    private static volatile ClassLoader sModuleClassLoader;

    // The RadioPreferenceGroup backing the downloader picker (for radio-dot mutual exclusion).
    private static volatile Object sPickerGroup;

    // Region picker's RadioPreferenceGroup (for persisting the selected country ISO).
    private static volatile Object sRegionGroup;

    // Map of picker row key -> injected RadioButton (for manual mutual exclusion).
    private static final java.util.Map<String, android.widget.RadioButton> sRadioButtons =
            new java.util.concurrent.ConcurrentHashMap<>();

    // The injected custom-package EditText (tracked so the custom row click can read it).
    private static volatile android.widget.EditText sCustomEditText;

    // The downloader package that was active before the user tapped the custom dot (used to
    // revert when the custom input is left empty).
    private static volatile String sPrevPackage;

    // UA override: injected custom-UA EditText + previous UA before tapping the custom dot.
    private static volatile android.widget.EditText sUaCustomEditText;
    private static volatile String sPrevUa;

    // 精简设置页：每项的 CheckBox（key -> checkbox），用于回显勾选状态。
    private static final java.util.Map<String, android.widget.CheckBox> sCleanCheckBoxes =
            new java.util.concurrent.ConcurrentHashMap<String, android.widget.CheckBox>();

    // Whether the downloader picker sub-page is currently the shown page (tracked by us, since
    // Samsung's getTopFragment() keys off back-stack count which is wrong for backstack-less pages).
    private static volatile boolean sInPickerPage;
    private static volatile boolean sRegionPageActive;
    @Override
    public void initZygote(StartupParam startupParam) {
        prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!SBROWSER_PACKAGE.equals(lpparam.packageName)
                && !SBROWSER_BETA_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[SBPlus] loaded target: " + lpparam.packageName);
        LogWriter.log("core", "module loaded for " + lpparam.packageName);

        sModuleClassLoader = lpparam.classLoader;

        if (prefs == null) {
            prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
        }
        prefs.makeWorldReadable();
        prefs.reload();

        hookApplicationContext(lpparam.classLoader);
        hookPreDownloadRequestService(lpparam.classLoader);
        hookOnDownloadStarted(lpparam.classLoader);
        hookSettingsMenu(lpparam.classLoader);
        hookFragmentLoad(lpparam.classLoader);
        hookInlineEdit(lpparam.classLoader);
        hookBackPress(lpparam.classLoader);
        hookNavigateUp(lpparam.classLoader);
        hookRadioGroup(lpparam.classLoader);
        hookMoreMenuGrid(lpparam.classLoader);
        hookRegionLock(lpparam.classLoader);
        hookRegionTouchScroll(lpparam.classLoader);
        hookUaOverride(lpparam.classLoader);
        hookCleanSettings(lpparam.classLoader);
        hookBlockUpdate(lpparam.classLoader);
        hookVideoBackground(lpparam.classLoader);
        hookUserscript(lpparam.classLoader);
    }

    /**
     * (1) Prevent NullPointerException in RadioPreferenceGroup.onAttached when the group was
     * constructed with a null AttributeSet (mEntries/mEntryValues are null, but onAttached
     * iterates mEntries.length). (2) Persist the selected downloader when the user taps a
     * radio — RadioPreferenceGroup.setChecked(key) is the mutual-exclusion entry point, so we
     * read the tapped key there and save the matching package.
     */
    private void hookRadioGroup(ClassLoader cl) {
        try {
            Class<?> groupCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.RadioPreferenceGroup", cl);

            // (1) defang onAttached against null mEntries.
            try {
                XposedHelpers.findAndHookMethod(groupCls, "onAttached", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object self = param.thisObject;
                        if (self != sPickerGroup) return; // only our group
                        param.setResult(null); // skip auto-create-from-entries
                    }
                });
                XposedBridge.log("[SBPlus] RadioPreferenceGroup.onAttached hooked");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] onAttached hook failed: " + t);
            }

            // (2) persist selection on setChecked(key).
            try {
                XposedHelpers.findAndHookMethod(groupCls, "setChecked", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object self = param.thisObject;
                        String key = (String) param.args[0];
                        if (key == null) return;
                        if (self == sRegionGroup) {
                            saveRegionCode(key);
                            XposedBridge.log("[SBPlus] radio region selected: " + key);
                            return;
                        }
                        if (self != sPickerGroup) return;
                        if (key.startsWith("sbplus_dl_")) {
                            String pkg = key.substring("sbplus_dl_".length());
                            saveDownloaderPackage(pkg);
                            XposedBridge.log("[SBPlus] radio downloader selected: " + pkg);
                        }
                    }
                });
                XposedBridge.log("[SBPlus] RadioPreferenceGroup.setChecked hooked");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] setChecked hook failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookRadioGroup failed: " + t);
        }
    }

    /**
     * When the downloader picker is the top fragment, back should return to the SBPlus
     * switch page (not exit all the way to browser settings). safeReplaceFragment does not
     * add a back stack entry, so intercept onBackPressed and manually navigate back.
     */
    private void hookBackPress(ClassLoader cl) {
        try {
            Class<?> activityCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.SettingsActivity", cl);
            XposedHelpers.findAndHookMethod(activityCls, "onBackPressed",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[SBPlus] onBackPressed CALLED inPicker=" + sInPickerPage);
                            try {
                                if (!sInPickerPage) return;
                                Object act = param.thisObject;
                                // Navigate back to the plain SBPlus switch page.
                                sInPickerPage = false;
                                navigateToFragment(act,
                                        "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                                        null);
                                param.setResult(null);
                                XposedBridge.log("[SBPlus] back from downloader picker");
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] back press hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] onBackPressed hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookBackPress failed: " + t);
        }
    }

    /** The toolbar up/back arrow goes through PreferenceFragmentCustom.onNavigateUpClicked(),
     *  not onBackPressed(). Intercept it while the picker page is shown and navigate back to the
     *  SBPlus switch page, returning true so Samsung's default finish() is skipped. */
    private void hookNavigateUp(ClassLoader cl) {
        try {
            Class<?> prefFrag = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom", cl);
            XposedHelpers.findAndHookMethod(prefFrag, "onNavigateUpClicked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!sInPickerPage) return;
                                Object frag = param.thisObject;
                                Object act = XposedHelpers.callMethod(frag, "getActivity");
                                if (act == null) return;
                                sInPickerPage = false;
                                navigateToFragment(act,
                                        "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                                        null);
                                param.setResult(Boolean.TRUE);
                                XposedBridge.log("[SBPlus] up-navigate from downloader picker");
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] navigate-up hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] onNavigateUpClicked hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookNavigateUp failed: " + t);
        }
    }

    /** Return the actual top fragment (last in the FragmentManager's fragment list).
     *  Samsung's getTopFragment() keys off back-stack count, which is wrong for our
     *  backstack-less safeReplaceFragment pages, so we read the fragment list directly. */
    private Object topFragment(Object act) {
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            java.util.List<?> fragments = (java.util.List<?>)
                    XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null || fragments.isEmpty()) return null;
            for (int i = fragments.size() - 1; i >= 0; i--) {
                Object f = fragments.get(i);
                if (f != null && (Boolean) XposedHelpers.callMethod(f, "isAdded")) {
                    return f;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invoke SettingsActivity.safeReplaceFragment(className, args) via reflection. */
    private void navigateToFragment(Object act, String className, android.os.Bundle args) {
        try {
            java.lang.reflect.Method m = XposedHelpers.findMethodBestMatch(
                    act.getClass(), "safeReplaceFragment", String.class, android.os.Bundle.class);
            m.setAccessible(true);
            m.invoke(act, className, args);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToFragment error: " + t);
        }
    }

    /** Build an empty (non-null) AttributeSet to satisfy Samsung's themed attribute lookup. */
    private android.util.AttributeSet emptyAttributeSet() {
        try {
            org.xmlpull.v1.XmlPullParser parser = org.xmlpull.v1.XmlPullParserFactory
                    .newInstance().newPullParser();
            parser.setInput(new java.io.StringReader("<s/>"));
            int evt = parser.getEventType();
            while (evt != org.xmlpull.v1.XmlPullParser.START_TAG
                    && evt != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                evt = parser.next();
            }
            return android.util.Xml.asAttributeSet(parser);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] emptyAttributeSet error: " + t);
            return null;
        }
    }

    /**
     * Hook Preference.onBindViewHolder so we can inject an inline EditText into the
     * "自定义下载器" row (key = sbplus_dl_custom) — no dialog, direct on-row input.
     */
    private void hookInlineEdit(ClassLoader cl) {
        try {
            Class<?> prefCls = XposedHelpers.findClass("androidx.preference.Preference", cl);
            // Resolve onBindViewHolder by name + arity (its 1 param is the R8-obfuscated
            // PreferenceViewHolder), avoiding hard-coding the obfuscated class name.
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : prefCls.getDeclaredMethods()) {
                if (mm.getName().equals("onBindViewHolder") && mm.getParameterTypes().length == 1) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                XposedBridge.log("[SBPlus] onBindViewHolder not found");
                return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        decoratePickerRow(param.thisObject, param.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] decoratePickerRow error: " + t);
                    }
                }
            });
            XposedBridge.log("[SBPlus] onBindViewHolder hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookInlineEdit failed: " + t);
        }
    }

    /** Decorate a picker row: preset rows get a radio dot; the custom row gets an EditText. */
    private void decoratePickerRow(Object preference, Object holder) {
        try {
            String key = (String) XposedHelpers.callMethod(preference, "getKey");
            if (key == null) return;

            if (key.startsWith("sbplus_dl_")) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;

                if ("sbplus_dl_custom".equals(key)) {
                    // Custom row also gets a radio dot (left) + an inline EditText (right).
                    injectRadioDot(root, key);
                    injectInlineEdit(root, key);
                } else {
                    injectRadioDot(root, key);
                }
            } else if (key.startsWith("sbplus_region_")) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;
                injectRadioDot(root, key);
                // Shrink each region row so all 17 fit on one screen without clipping
                // text: reduce title/summary text size + vertical padding, and shrink the
                // row height (this page's Samsung-fork RecyclerView can't touch-scroll).
                try {
                    float d = root.getResources().getDisplayMetrics().density;
                    android.view.View title = root.findViewById(android.R.id.title);
                    android.view.View summary = root.findViewById(android.R.id.summary);
                    if (title instanceof android.widget.TextView) {
                        ((android.widget.TextView) title).setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
                    }
                    if (summary instanceof android.widget.TextView) {
                        ((android.widget.TextView) summary).setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
                    }
                    android.view.ViewGroup.LayoutParams lp = root.getLayoutParams();
                    if (lp != null) {
                        lp.height = (int) (44f * d + 0.5f);
                        root.setLayoutParams(lp);
                    }
                    root.setMinimumHeight(0);
                    root.setPadding(root.getPaddingLeft(), (int) (2f * d + 0.5f),
                            root.getPaddingRight(), (int) (2f * d + 0.5f));
                } catch (Throwable ignored) {}
            } else if (key.startsWith("sbplus_ua_")) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;

                if ("sbplus_ua_custom".equals(key)) {
                    injectRadioDot(root, key);
                    injectUaInlineEdit(root, key);
                } else {
                    injectRadioDot(root, key);
                }
            } else if (key.startsWith("sbplus_clean_")) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;
                injectCleanCheckBox(root, key);
                // 压缩行高 + 字号，让 23 项尽量一屏放下。
                try {
                    float d = root.getResources().getDisplayMetrics().density;
                    android.view.View title = root.findViewById(android.R.id.title);
                    android.view.View summary = root.findViewById(android.R.id.summary);
                    if (title instanceof android.widget.TextView) {
                        ((android.widget.TextView) title).setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
                    }
                    if (summary instanceof android.widget.TextView) {
                        ((android.widget.TextView) summary).setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
                    }
                    android.view.ViewGroup.LayoutParams lp = root.getLayoutParams();
                    if (lp != null) {
                        lp.height = (int) (48f * d + 0.5f);
                        root.setLayoutParams(lp);
                    }
                    root.setMinimumHeight(0);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] decoratePickerRow error: " + t);
        }
    }

    /** Inject a RadioButton (radio dot) into a preset downloader row, wired for mutual exclusion. */
    private void injectRadioDot(final android.view.View root, final String key) {
        try {
            // Prepend a RadioButton at the left of the row (inside the icon area).
            android.view.View iconFrame = root.findViewById(android.R.id.icon_frame);
            android.view.ViewGroup target = null;
            if (iconFrame instanceof android.view.ViewGroup) {
                target = (android.view.ViewGroup) iconFrame;
            } else {
                target = (android.view.ViewGroup) root; // fallback: the row root
            }

            final android.widget.RadioButton rb = new android.widget.RadioButton(root.getContext());
            rb.setClickable(false);
            rb.setFocusable(false);
            rb.setPadding(dp(root.getContext(), 4), 0, dp(root.getContext(), 4), 0);
            // Prefer Samsung's themed Sesl radio drawable for native look; fall back to AOSP.
            int dotDrawable = resolveSeslRadioDrawable(root.getContext());
            rb.setButtonDrawable(dotDrawable != 0 ? dotDrawable : android.R.drawable.btn_radio);

            boolean checked;
            if (key.startsWith("sbplus_region_")) {
                checked = regionCode().equals(key.substring("sbplus_region_".length()));
            } else if (key.startsWith("sbplus_ua_")) {
                if ("sbplus_ua_custom".equals(key)) {
                    String cur = userAgent();
                    checked = true;
                    for (String[] e : PRESET_UAS) {
                        if (e[1].equals(cur)) { checked = false; break; }
                    }
                } else {
                    checked = userAgent().equals(key.substring("sbplus_ua_".length()));
                }
            } else if ("sbplus_dl_custom".equals(key)) {
                // Custom row is checked when the current package is NOT one of the presets.
                String cur = downloaderPackage();
                checked = true;
                for (String[] e : PRESET_DOWNLOADERS) {
                    if (e[1].equals(cur)) { checked = false; break; }
                }
            } else {
                checked = downloaderPackage().equals(key.substring("sbplus_dl_".length()));
            }
            rb.setChecked(checked);

            target.addView(rb, 0, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            sRadioButtons.put(key, rb);
            XposedBridge.log("[SBPlus] radio dot injected: " + key);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectRadioDot error: " + t);
        }
    }

    /** Update all injected radio dots' checked state after a selection change. */
    private void refreshRadioDots(String selectedKey) {
        try {
            for (java.util.Map.Entry<String, android.widget.RadioButton> e : sRadioButtons.entrySet()) {
                android.widget.RadioButton rb = e.getValue();
                if (rb != null) {
                    rb.setChecked(e.getKey().equals(selectedKey));
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refreshRadioDots error: " + t);
        }
    }

    /** Try to resolve Samsung's themed Sesl radio-button drawable id (native-looking dot). */
    private int resolveSeslRadioDrawable(android.content.Context ctx) {
        try {
            android.content.res.Resources r = ctx.getResources();
            String pkg = ctx.getPackageName();
            int[] candidates = {
                r.getIdentifier("sesl_btn_radio", "drawable", pkg),
                r.getIdentifier("sesl_ic_radio_on", "drawable", pkg),
                r.getIdentifier("settings_button_radio", "drawable", pkg),
                r.getIdentifier("tw_btn_radio", "drawable", pkg),
            };
            for (int id : candidates) {
                if (id != 0) return id;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Inject the inline EditText into the custom-downloader row. */
    private void injectInlineEdit(android.view.View root, String key) {
        try {
            // The widget frame is on the right; Samsung's sesl layout keeps it GONE for
            // non-checkbox preferences, so force it visible and give the EditText real width.
            android.view.View widgetFrame = root.findViewById(android.R.id.widget_frame);
            if (widgetFrame == null) {
                XposedBridge.log("[SBPlus] widget_frame missing for inline edit");
                return;
            }
            widgetFrame.setVisibility(android.view.View.VISIBLE);
            if (widgetFrame instanceof android.view.ViewGroup) {
                android.view.ViewGroup wf = (android.view.ViewGroup) widgetFrame;
                wf.removeAllViews();

                final android.widget.EditText edit = new android.widget.EditText(root.getContext());
                edit.setHint("输入包名，如 com.dv.adm");
                edit.setSingleLine(true);
                edit.setTextSize(14f);
                edit.setPadding(dp(root.getContext(), 8), 0, dp(root.getContext(), 8), 0);
                // Pre-fill only when the active downloader is a custom (non-preset) package.
                String cur = downloaderPackage();
                if (!isPreset(cur)) {
                    edit.setText(cur);
                }
                sCustomEditText = edit;
                wf.addView(edit, new android.view.ViewGroup.LayoutParams(
                        dp(root.getContext(), 200),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                edit.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(android.view.View v, boolean hasFocus) {
                        if (!hasFocus) commitInlinePackage(edit);
                    }
                });

                XposedBridge.log("[SBPlus] inline edit attached for custom downloader");
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectInlineEdit error: " + t);
        }
    }

    private void commitInlinePackage(android.widget.EditText edit) {
        String v = edit.getText().toString().trim();
        if (!v.isEmpty()) {
            // Typed a package → save and keep the custom dot selected.
            saveDownloaderPackage(v);
            refreshRadioDots("sbplus_dl_custom");
            android.widget.Toast.makeText(edit.getContext(), "已启用: " + v,
                    android.widget.Toast.LENGTH_SHORT).show();
            XposedBridge.log("[SBPlus] custom downloader enabled: " + v);
        } else {
            // Left empty → revert to the previous selection.
            String prev = sPrevPackage;
            if (prev != null && !prev.isEmpty()) {
                saveDownloaderPackage(prev);
                String prevKey = isPreset(prev) ? ("sbplus_dl_" + prev) : "sbplus_dl_custom";
                refreshRadioDots(prevKey);
                XposedBridge.log("[SBPlus] custom input empty, reverted to: " + prev);
            }
        }
    }
    /** Inject the inline EditText for the custom UA row (mirrors injectInlineEdit). */
    private void injectUaInlineEdit(android.view.View root, String key) {
        try {
            android.view.View widgetFrame = root.findViewById(android.R.id.widget_frame);
            if (widgetFrame == null) return;
            widgetFrame.setVisibility(android.view.View.VISIBLE);
            if (widgetFrame instanceof android.view.ViewGroup) {
                android.view.ViewGroup wf = (android.view.ViewGroup) widgetFrame;
                wf.removeAllViews();

                final android.widget.EditText edit = new android.widget.EditText(root.getContext());
                edit.setHint("输入 UA 字符串");
                edit.setSingleLine(true);
                edit.setTextSize(12f);
                edit.setPadding(dp(root.getContext(), 8), 0, dp(root.getContext(), 8), 0);
                edit.setHorizontallyScrolling(true);
                String cur = userAgent();
                if (!isPresetUa(cur) && cur.length() > 0) {
                    edit.setText(cur);
                }
                sUaCustomEditText = edit;
                wf.addView(edit, new android.view.ViewGroup.LayoutParams(
                        dp(root.getContext(), 220),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                edit.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(android.view.View v, boolean hasFocus) {
                        if (!hasFocus) commitInlineUa(edit);
                    }
                });

                XposedBridge.log("[SBPlus] inline edit attached for custom UA");
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUaInlineEdit error: " + t);
        }
    }

    /** Commit the typed custom UA (mirrors commitInlinePackage). */
    private void commitInlineUa(android.widget.EditText edit) {
        String v = edit.getText().toString().trim();
        if (!v.isEmpty()) {
            saveUserAgent(v);
            refreshRadioDots("sbplus_ua_custom");
            android.widget.Toast.makeText(edit.getContext(), "已启用自定义 UA",
                    android.widget.Toast.LENGTH_SHORT).show();
            XposedBridge.log("[SBPlus] custom UA enabled (len=" + v.length() + ")");
        } else {
            String prev = sPrevUa;
            if (prev != null && !prev.isEmpty()) {
                saveUserAgent(prev);
                String prevKey = isPresetUa(prev) ? ("sbplus_ua_" + prev) : "sbplus_ua_custom";
                refreshRadioDots(prevKey);
                XposedBridge.log("[SBPlus] custom UA input empty, reverted");
            }
        }
    }


    private boolean isPreset(String pkg) {
        for (String[] e : PRESET_DOWNLOADERS) {
            if (e[1].equals(pkg)) return true;
        }
        return false;
    }

    /**
     * Hook SBrowserApplication.onCreate to capture the global Application Context.
     * This Context is required to startActivity() when dispatching downloads.
     */
    private void hookApplicationContext(ClassLoader cl) {
        try {
            Class<?> appCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.SBrowserApplication", cl);
            XposedHelpers.findAndHookMethod(appCls, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sAppContext = (Context) param.thisObject;
                            LogWriter.init(sAppContext);
                            LogWriter.log("core", "captured Application Context: " + sAppContext);
                            // One-time self-heal: if a previous buggy run wiped the "More" menu
                            // (Samsung then logs "onMenuKeyClicked: no Item"), restore defaults.
                            scheduleMenuSelfHeal(cl);
                        }
                    });
            XposedBridge.log("[SBPlus] SBrowserApplication.onCreate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] Application hook failed: " + t);
        }
    }

    /**
     * Self-heal the "More" menu. If a previous run saved an empty/blanked tools-menu list
     * (Samsung then refuses to open the menu, logging "onMenuKeyClicked: no Item"), reset it
     * to Samsung defaults once.
     */
    private void scheduleMenuSelfHeal(final ClassLoader cl) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        Class<?> mgrCls = XposedHelpers.findClass(
                                "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", cl);
                        Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
                        java.util.List<?> primary = (java.util.List<?>)
                                XposedHelpers.callMethod(mgr, "getToolsPrimaryMenus");
                        if (primary == null || primary.isEmpty()) {
                            XposedBridge.log("[SBPlus] menu empty -> resetToolsMenu()");
                            XposedHelpers.callMethod(mgr, "resetToolsMenu");
                        } else {
                            XposedBridge.log("[SBPlus] menu healthy (primary=" + primary.size() + ")");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] menuSelfHeal error: " + t);
                    }
                }
            }, 1500L);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] scheduleMenuSelfHeal error: " + t);
        }
    }

    /**
     * Inject an "SBPlus" entry into Samsung Browser's settings menu.
     *
     * The browser's main settings list is built in SettingsFragment.initPreferences()
     * (after addPreferencesFromResource of res/xml/settings_fragment.xml). We hook the
     * end of initPreferences and append a Samsung-styled Preference (PreferenceCustom)
     * so the entry matches the browser's look and feel. Tapping it opens the SBPlus
     * configuration screen (our module's own MainActivity).
     */
    private void hookSettingsMenu(ClassLoader cl) {
        try {
            Class<?> settingsFragment = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.SettingsFragment", cl);

            XposedHelpers.findAndHookMethod(settingsFragment, "initPreferences",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                injectSettingsEntry(param);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectSettingsEntry error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] SettingsFragment.initPreferences hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] settings menu hook failed: " + t);
        }
    }

    /**
     * Hook PreferenceFragmentCustom.onCreatePreferences(Bundle, String) to inject our SBPlus
     * sub-menu items. We reuse Samsung's own concrete, empty PreferenceFragmentCustom as the
     * sub-menu container (its onCreatePreferences is an empty override, so the page starts
     * blank and we fill it), which sidesteps the obfuscated androidx Fragment/lifecycle
     * interfaces that block a module-side Fragment.
     */
    private void hookFragmentLoad(ClassLoader cl) {
        try {
            Class<?> prefFrag = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom", cl);

            XposedHelpers.findAndHookMethod(prefFrag, "onCreatePreferences",
                    android.os.Bundle.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                injectSubMenuContent(param);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectSubMenuContent error: " + t);
                            }
                        }
                    });

            // Force the fragment's RecyclerView to be scrollable (Samsung reuses this empty
            // fragment for single-screen pages; with 17 region rows it must scroll, but the
            // CoordinatorLayout/AppBar can leave it non-scrolling). Log its real size too.
            XposedHelpers.findAndHookMethod(prefFrag, "onViewCreated",
                    android.view.View.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                fixRegionScroll(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] fixRegionScroll error: " + t);
                            }
                        }
                    });

            XposedBridge.log("[SBPlus] PreferenceFragmentCustom.onCreatePreferences hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] submenu hook failed: " + t);
        }

        // 精简设置页：两列网格。三星在 onCreateLayoutManager 里创建默认
        // LinearLayoutManager，只有在这里替换才不会被后续覆盖。
        try {
            // onCreateLayoutManager 定义在父类 H2/A（PreferenceFragmentCompat 的混淆名），
            // 不在 PreferenceFragmentCustom 自身，必须 hook 父类。
            Class<?> prefFragCls = XposedHelpers.findClass("H2.A", cl);
            XposedHelpers.findAndHookMethod(prefFragCls, "onCreateLayoutManager",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object frag = param.thisObject;
                                android.os.Bundle args = (android.os.Bundle)
                                        XposedHelpers.callMethod(frag, "getArguments");
                                if (args == null) return;
                                String page = args.getString(ARG_PAGE);
                                if (!PAGE_CLEAN_SETTINGS_PICKER.equals(page)) return;
                                Class<?> gridCls = XposedHelpers.findClass(
                                        "androidx.recyclerview.widget.GridLayoutManager", cl);
                                Object grid = XposedHelpers.newInstance(
                                        gridCls, new Class[]{int.class}, 2);
                                param.setResult(grid);
                                XposedBridge.log("[SBPlus] clean settings grid layout manager applied");
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] clean grid layout hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] onCreateLayoutManager hooked for clean grid");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] onCreateLayoutManager hook failed: " + t);
        }
    }

    /** Ensure the fragment's RecyclerView scrolls properly for the 17-row region list. */
    private void fixRegionScroll(Object frag) {
        try {
            String page = null;
            android.os.Bundle args = (android.os.Bundle) XposedHelpers.callMethod(frag, "getArguments");
            if (args != null) page = args.getString(ARG_PAGE);
            if (!PAGE_REGION_PICKER.equals(page)) return;
            sRegionPageActive = true;

            Object rvObj = XposedHelpers.callMethod(frag, "getListView");
            if (rvObj instanceof android.view.View) {
                final android.view.View rv = (android.view.View) rvObj;
                rv.post(new Runnable() {
                    @Override public void run() {
                        try {
                            collapseAppBar(rv);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] collapseAppBar error: " + t);
                        }
                    }
                });
            }
            XposedBridge.log("[SBPlus] region picker page active");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] fixRegionScroll error: " + t);
        }
    }

    /**
     * Walk up from the list to the CoordinatorLayout, find Samsung's expanded
     * AppBarLayout (the huge collapsible title) that eats ~40% of the screen, and
     * shrink it to wrap_content so the 17 region rows get the full height.
     */
    private void collapseAppBar(android.view.View rv) {
        android.view.ViewGroup parent = (android.view.ViewGroup) rv.getParent();
        for (int depth = 0; depth < 20 && parent != null; depth++) {
            String cn = parent.getClass().getName();
            if (cn.contains("CoordinatorLayout")) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    android.view.View child = parent.getChildAt(i);
                    String childCn = child.getClass().getName();
                    if (childCn.contains("AppBarLayout")
                            || childCn.contains("CollapsingToolbarLayout")) {
                        try {
                            XposedHelpers.callMethod(child, "setExpanded", false, false);
                        } catch (Throwable ignored) {}
                        android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
                        if (lp != null) {
                            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                            child.setLayoutParams(lp);
                        }
                        XposedBridge.log("[SBPlus] collapseAppBar: shrunk " + childCn);
                    }
                }
                break;
            }
            parent = (android.view.ViewGroup) parent.getParent();
        }
    }



    /**
     * Fill the (empty) SBPlus sub-menu page with our preference items. We only act when
     * the fragment instance is PreferenceFragmentCustom itself (not one of Samsung's
     * concrete subclasses), which uniquely identifies our SBPlus sub-menu page.
     */    private void injectSubMenuContent(MethodHookParam param) {
        Object frag = param.thisObject;
        if (frag == null) return;

        // Unique marker: our page is a bare PreferenceFragmentCustom, not a subclass.
        Class<?> fragCls = frag.getClass();
        String clsName = fragCls.getName();
        XposedBridge.log("[SBPlus] submenu onCreatePreferences fired for: " + clsName);
        if (!"com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom".equals(clsName)) {
            return; // some other Samsung settings page; leave it alone
        }

        Context ctx = (Context) XposedHelpers.callMethod(frag, "getContext");
        if (ctx == null) {
            XposedBridge.log("[SBPlus] submenu getContext null");
            return;
        }

        // Determine which SBPlus sub-page this is via the fragment arguments.
        String page = null;
        try {
            android.os.Bundle args = (android.os.Bundle) XposedHelpers.callMethod(frag, "getArguments");
            if (args != null) page = args.getString(ARG_PAGE);
        } catch (Throwable ignored) {}

        // Ensure a PreferenceScreen exists (the empty fragment does not create one).
        Object screen = XposedHelpers.callMethod(frag, "getPreferenceScreen");
        if (screen == null) {
            // PreferenceManager.createPreferenceScreen(Context) is obfuscated to H2/F#a(Context).
            Object pm = XposedHelpers.callMethod(frag, "getPreferenceManager");
            Object newScreen = XposedHelpers.callMethod(pm, "a", ctx);
            XposedHelpers.callMethod(frag, "setPreferenceScreen", newScreen);
            screen = newScreen;
        }
        if (screen == null) {
            XposedBridge.log("[SBPlus] submenu could not create PreferenceScreen");
            return;
        }

        ClassLoader cl = fragCls.getClassLoader();

        if (PAGE_DOWNLOADER_PICKER.equals(page) || PAGE_REGION_PICKER.equals(page)) {
            // Defend against duplicate injection: the fragment can be re-created (or its
            // onCreatePreferences fired more than once) with a stale screen that already
            // holds our items. Clear any existing children before re-populating.
            try {
                int existing = (Integer) XposedHelpers.callMethod(screen, "getPreferenceCount");
                if (existing > 0) {
                    XposedHelpers.callMethod(screen, "removeAll");
                    XposedBridge.log("[SBPlus] cleared " + existing + " stale prefs before region/downloader inject");
                }
            } catch (Throwable tt) {
                XposedBridge.log("[SBPlus] screen clear failed: " + tt);
            }
        }

        if (PAGE_DOWNLOADER_PICKER.equals(page)) {
            injectDownloaderPicker(ctx, cl, screen);
        } else if (PAGE_REGION_PICKER.equals(page)) {
            injectRegionPicker(ctx, cl, screen);
        } else if (PAGE_UA_PICKER.equals(page)) {
            injectUaPicker(ctx, cl, screen);
        } else if (PAGE_CLEAN_SETTINGS_PICKER.equals(page)) {
            injectCleanSettingsPicker(ctx, cl, screen, frag);
        } else if (PAGE_VIDEO_BG_PICKER.equals(page)) {
            injectVideoBgPicker(ctx, cl, screen);
        } else {
            Object pref = buildExternalDownloaderSwitch(ctx, cl);
            boolean added = (Boolean) XposedHelpers.callMethod(screen, "addPreference", pref);
            XposedBridge.log("[SBPlus] submenu item injected: " + added);

            Object gridPref = buildGridMenuSwitch(ctx, cl);
            boolean addedGrid = (Boolean) XposedHelpers.callMethod(screen, "addPreference", gridPref);
            XposedBridge.log("[SBPlus] grid menu item injected: " + addedGrid);

            Object regionPref = buildRegionLockSwitch(ctx, cl);
            boolean addedRegion = (Boolean) XposedHelpers.callMethod(screen, "addPreference", regionPref);
            XposedBridge.log("[SBPlus] region lock item injected: " + addedRegion);

            Object uaPref = buildUaSwitch(ctx, cl);
            boolean addedUa = (Boolean) XposedHelpers.callMethod(screen, "addPreference", uaPref);
            XposedBridge.log("[SBPlus] ua override item injected: " + addedUa);

            Object cleanPref = buildCleanSettingsSwitch(ctx, cl);
            boolean addedClean = (Boolean) XposedHelpers.callMethod(screen, "addPreference", cleanPref);
            XposedBridge.log("[SBPlus] clean settings item injected: " + addedClean);

            Object blockUpdatePref = buildBlockUpdateSwitch(ctx, cl);
            boolean addedBlockUpdate = (Boolean) XposedHelpers.callMethod(screen, "addPreference", blockUpdatePref);
            XposedBridge.log("[SBPlus] block update item injected: " + addedBlockUpdate);

            Object videoBgPref = buildVideoBgSwitch(ctx, cl);
            boolean addedVideoBg = (Boolean) XposedHelpers.callMethod(screen, "addPreference", videoBgPref);
            XposedBridge.log("[SBPlus] video bg item injected: " + addedVideoBg);

            Object userscriptPref = buildUserscriptSwitch(ctx, cl);
            boolean addedUserscript = (Boolean) XposedHelpers.callMethod(screen, "addPreference", userscriptPref);
            XposedBridge.log("[SBPlus] userscript item injected: " + addedUserscript);
        }
    }

    /**
     * Fill the downloader picker sub-page: 3 preset downloader rows (radio dots) + a custom
     * inline-package row. Radio dots and the inline EditText are injected in the
     * onBindViewHolder hook (decoratePickerRow) — pure code, no XML inflation.
     */
    private void injectDownloaderPicker(Context ctx, ClassLoader cl, Object screen) {
        final String current = downloaderPackage();
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        for (final String[] entry : PRESET_DOWNLOADERS) {
            final String label = entry[0];
            final String pkg = entry[1];
            Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", label);
            XposedHelpers.callMethod(pref, "setKey", "sbplus_dl_" + pkg);
            XposedHelpers.callMethod(pref, "setSummary", pkg);
            bindPickerClick(pref, cl, pkg, label, screen);
            XposedHelpers.callMethod(screen, "addPreference", pref);
        }

        // Custom downloader row — inline EditText injected in onBindViewHolder.
        Object custom = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(custom, "setTitle", "自定义下载器");
        XposedHelpers.callMethod(custom, "setKey", "sbplus_dl_custom");
        boolean isCustom = !isPreset(current);
        XposedHelpers.callMethod(custom, "setSummary", isCustom ? ("当前使用: " + current) : "输入包名并确认");
        bindCustomClick(custom, cl, screen);
        XposedHelpers.callMethod(screen, "addPreference", custom);

        XposedBridge.log("[SBPlus] downloader picker injected");
    }

    /** Fill the region picker sub-page: 17 country rows (radio dots), mirroring the
     *  downloader picker exactly (PreferenceCustom rows + injectRadioDot in onBindViewHolder).
     *  No ScrollView / custom layout — Samsung's own RecyclerView handles scrolling, theming,
     *  rounding and backgrounds. */
    private void injectRegionPicker(Context ctx, ClassLoader cl, Object screen) {
        final String current = regionCode();
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        for (final String[] entry : PRESET_REGIONS) {
            final String label = entry[0];
            final String code = entry[1];
            Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", label);
            XposedHelpers.callMethod(pref, "setKey", "sbplus_region_" + code);
            XposedHelpers.callMethod(pref, "setSummary", code);
            bindRegionClick(pref, cl, code, label);
            XposedHelpers.callMethod(screen, "addPreference", pref);
        }

        XposedBridge.log("[SBPlus] region picker injected (" + PRESET_REGIONS.length + " items)");
    }

    /** Bind click on a region row: save the code + refresh the radio dots. */
    private void bindRegionClick(Object pref, ClassLoader cl, final String code, final String label) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                Object clicked = args[0];
                                saveRegionCode(code);
                                Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                if (ctxObj instanceof Context) {
                                    android.widget.Toast.makeText((Context) ctxObj,
                                            "已选择: " + label, android.widget.Toast.LENGTH_SHORT).show();
                                }
                                refreshRadioDots("sbplus_region_" + code);
                                XposedBridge.log("[SBPlus] region selected: " + code);
                                LogWriter.log("picker", "region selected: " + code);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] region click error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] region click bind failed: " + t);
        }
    }

    /** Bind click on the custom-downloader row: tapping selects the radio dot + focuses the
     *  EditText and raises the soft keyboard. The package only takes effect once typed; leaving
     *  it empty reverts to the previous downloader. */
    private void bindCustomClick(Object pref, ClassLoader cl, final Object screen) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                Object clicked = args[0];
                                // Remember the previous selection so we can revert if input is left empty.
                                sPrevPackage = downloaderPackage();
                                // Visually select the custom dot immediately.
                                refreshRadioDots("sbplus_dl_custom");
                                // Focus the EditText and raise the soft keyboard.
                                android.widget.EditText edit = sCustomEditText;
                                Context ctx = (Context) XposedHelpers.callMethod(clicked, "getContext");
                                if (edit != null) {
                                    edit.requestFocus();
                                    android.view.inputmethod.InputMethodManager imm =
                                            (android.view.inputmethod.InputMethodManager)
                                                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                                    if (imm != null) {
                                        imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                                    }
                                }
                                XposedBridge.log("[SBPlus] custom dot selected, awaiting input");
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] custom click error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] custom click bind failed: " + t);
        }
    }

    /** Bind a click listener on a downloader picker row (obfuscated OnPreferenceClickListener). */
    private void bindPickerClick(Object pref, ClassLoader cl, final String pkg, final String label, final Object screen) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                Object clicked = args[0];
                                saveDownloaderPackage(pkg);
                                Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                if (ctxObj instanceof Context) {
                                    android.widget.Toast.makeText((Context) ctxObj,
                                            "已选择: " + label, android.widget.Toast.LENGTH_SHORT).show();
                                }
                                refreshRadioDots("sbplus_dl_" + pkg);
                                XposedBridge.log("[SBPlus] downloader selected: " + pkg);
                                LogWriter.log("picker", "downloader selected: " + pkg);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] picker click error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] picker click bind failed: " + t);
        }
    }

    /**
     * Show a settings dialog (programmatic UI, no module resources) on top of the browser's
     * settings activity. Contains: downloader package input + save button.
     */
    private void showSettingsDialog(final android.app.Activity act) {
        showCustomDownloaderDialog(act);
    }

    /**
     * Custom downloader input dialog.
     */
    private void showCustomDownloaderDialog(final android.app.Activity act) {
        final Context ctx = act;
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setHint("输入包名，例如 com.dv.adm");
        input.setSingleLine(true);
        input.setText(downloaderPackage());
        int pad = dp(ctx, 16);
        input.setPadding(pad, pad, pad, pad);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
        b.setTitle("自定义下载器");
        b.setMessage("输入下载器应用包名");
        b.setView(input);
        b.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                String p = input.getText().toString().trim();
                if (p.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "包名不能为空",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                saveDownloaderPackage(p);
                android.widget.Toast.makeText(ctx, "已保存: " + p,
                        android.widget.Toast.LENGTH_SHORT).show();
                XposedBridge.log("[SBPlus] custom downloader saved: " + p);
                LogWriter.log("picker", "custom downloader saved: " + p);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private String downloaderPackage() {
        return resolveDownloaderPackage();
    }

    private void saveDownloaderPackage(String pkg) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null) {
                XposedBridge.log("[SBPlus] save failed: no app context");
                return;
            }
            android.content.SharedPreferences sp = processPrefs(ctx);
            sp.edit().putString(KEY_DOWNLOADER_PACKAGE, pkg).commit();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save downloader package error: " + t);
        }
    }

    /** Process-local prefs (written by the embedded dialog, read by the download bridge). */
    private android.content.SharedPreferences processPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences("sbplus_config", android.content.Context.MODE_PRIVATE);
    }

    /** Resolve the downloader package: process-local value first, then module prefs. */
    private String resolveDownloaderPackage() {
        try {
            if (sAppContext != null) {
                String v = processPrefs(sAppContext).getString(KEY_DOWNLOADER_PACKAGE, null);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {}
        if (prefs != null) {
            String v = prefs.getString(KEY_DOWNLOADER_PACKAGE, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return DEFAULT_ADM_PACKAGE;
    }

    private String resolveDownloaderClass() {
        if (prefs != null) {
            String v = prefs.getString(KEY_DOWNLOADER_CLASS, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Append a Samsung-styled preference to the settings screen. Uses the browser's
     * own PreferenceCustom class so the row looks identical to other entries.
     */
    private void injectSettingsEntry(MethodHookParam param) {
        Object fragment = param.thisObject;
        // PreferenceFragmentCompat.getPreferenceScreen() returns the root PreferenceScreen.
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) {
            XposedBridge.log("[SBPlus] getPreferenceScreen() returned null, skip inject");
            return;
        }

        Context ctx = (Context) XposedHelpers.callMethod(fragment, "getContext");
        if (ctx == null) {
            XposedBridge.log("[SBPlus] getContext() returned null, skip inject");
            return;
        }

        ClassLoader cl = fragment.getClass().getClassLoader();

        // SBPlus main entry — a plain tappable row (like "Homepage" or "Search engine")
        // that navigates into a real sub-menu page.
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "SBPlus");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_main");
        XposedHelpers.callMethod(pref, "setSummary", "下载桥与增强功能");

        // Point the row at Samsung's own PreferenceFragmentCustom (a concrete, empty
        // PreferenceFragmentCompat subclass). Samsung loads it natively; we inject our
        // sub-menu items by hooking its onCreatePreferences. This avoids the obfuscated
        // androidx Fragment interfaces entirely (no class-resolution failure).
        XposedHelpers.callMethod(pref, "setFragment",
                "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom");

        boolean added = (Boolean) XposedHelpers.callMethod(screen, "addPreference", pref);
        XposedBridge.log("[SBPlus] SBPlus settings entry injected: " + added);
        if (added) LogWriter.log("menu", "SBPlus settings entry injected");
    }

    /**
     * Build the switch preference row used in the SBPlus sub-menu ("启用外部下载器").
     * Returns the constructed switch preference, wired to persist on toggle, and pointing
     * its row-tap at the downloader picker.
     */
    private Object buildExternalDownloaderSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "启用外部下载器");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_external_downloader");
        XposedHelpers.callMethod(pref, "setSummary", "下载转交给第三方下载器（ADM/1DM/IDM+）");
        XposedHelpers.callMethod(pref, "setChecked", isBridgeEnabled());

        // Must be selectable so performClick() routes into onClick() + onPreferenceTreeClick
        // (the fragment navigation below). A switch preference is NOT selectable by default,
        // which is why tapping the title had no effect.
        XposedHelpers.callMethod(pref, "setSelectable", true);

        // Divider line between title and switch (visual consistency with Samsung settings).
        try {
            XposedHelpers.callMethod(pref, "setDividerVisible", true);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] setDividerVisible failed: " + t);
        }

        // Row tap → manually navigate to the downloader picker sub-page. We do NOT rely on
        // Samsung's onPreferenceTreeClick/setFragment navigation (its PreferenceManager
        // callback wiring is broken by R8 field obfuscation), so we drive it ourselves.
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                Object clicked = args[0];
                                Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                if (actObj instanceof android.app.Activity) {
                                    navigateToDownloaderPicker((android.app.Activity) actObj);
                                }
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] picker navigate error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
            XposedBridge.log("[SBPlus] picker click listener bound");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] picker click bind failed: " + t);
        }

        // Listen for switch toggles (SetOnPreferenceChangeListener). We resolve the
        // listener interface type via reflection (its name is obfuscated to LH2/l, which
        // Class.forName can't reliably load across the dex split) by reading the target
        // method's parameter type.
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                Object newVal = args[1];
                                boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                saveBridgeEnabled(enabled);
                                XposedBridge.log("[SBPlus] bridge switch toggled: " + enabled);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] switch listener error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
            XposedBridge.log("[SBPlus] switch listener bound");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] switch listener bind failed: " + t);
        }

        return pref;
    }

    /** Build the "enable grid menu" switch (same pattern as the downloader switch). */
    private Object buildGridMenuSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "启用网格菜单");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_grid_menu");
        XposedHelpers.callMethod(pref, "setSummary", "更多菜单改为两行×5列网格，左右翻页");
        XposedHelpers.callMethod(pref, "setChecked", isGridMenuEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                Object newVal = args[1];
                                boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                saveGridMenuEnabled(enabled);
                                XposedBridge.log("[SBPlus] grid menu switch toggled: " + enabled);
                                LogWriter.log("grid", "grid menu toggled: " + enabled);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid switch listener error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
            XposedBridge.log("[SBPlus] grid switch listener bound");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid switch listener bind failed: " + t);
        }

        return pref;
    }

    /**
     * Build the "锁定国家/地区" switch row. Toggling enables/disables the region lock;
     * tapping the title navigates to the region picker sub-page (radio list of countries).
     */
    private Object buildRegionLockSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "锁定国家/地区");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_region_lock");
        String code = regionCode();
        XposedHelpers.callMethod(pref, "setSummary", code.isEmpty() ? "点击选择要锁定的国家/地区" : ("当前: " + code));
        XposedHelpers.callMethod(pref, "setChecked", isRegionLockEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);

        try {
            XposedHelpers.callMethod(pref, "setDividerVisible", true);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] region setDividerVisible failed: " + t);
        }

        // Switch toggle -> persist enable flag.
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                Object newVal = args[1];
                                boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                saveRegionLockEnabled(enabled);
                                XposedBridge.log("[SBPlus] region lock toggled: " + enabled);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] region switch listener error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] region switch listener bind failed: " + t);
        }

        // Row tap -> navigate to region picker sub-page.
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceClick")) {
                                Object clicked = args[0];
                                Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                if (actObj instanceof android.app.Activity) {
                                    navigateToRegionPicker((android.app.Activity) actObj);
                                }
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] region click error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
            XposedBridge.log("[SBPlus] region lock click listener bound");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] region lock click bind failed: " + t);
        }

        return pref;
    }

    private void navigateToRegionPicker(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_REGION_PICKER);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            XposedBridge.log("[SBPlus] navigated to region picker");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToRegionPicker error: " + t);
        }
    }
    /** Persist the chosen UA string (preset full string, or the custom value). */
    private String userAgent() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getString(KEY_UA, "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void saveUserAgent(String ua) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putString(KEY_UA, ua == null ? "" : ua).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save UA error: " + t);
        }
    }

    private boolean isUaEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_UA, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveUaEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_UA, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save UA enabled error: " + t);
        }
    }

    private boolean isPresetUa(String ua) {
        for (String[] e : PRESET_UAS) {
            if (e[1].equals(ua)) return true;
        }
        return false;
    }

    private void navigateToUaPicker(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_UA_PICKER);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            XposedBridge.log("[SBPlus] navigated to UA picker");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToUaPicker error: " + t);
        }
    }

    // ---- 精简设置页（屏蔽设置项） ----

    private boolean isCleanSettingsEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_CLEAN_SETTINGS, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveCleanSettingsEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_CLEAN_SETTINGS, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save clean settings enabled error: " + t);
        }
    }

    /** 返回被勾选要屏蔽的设置项 key 集合。 */
    private java.util.Set<String> hiddenSettings() {
        java.util.Set<String> set = new java.util.HashSet<String>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_HIDDEN_SETTINGS, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String k : raw.split(",")) {
                        if (k != null && !k.isEmpty()) set.add(k);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private void saveHiddenSettings(java.util.Set<String> set) {
        try {
            if (sAppContext != null) {
                StringBuilder sb = new StringBuilder();
                for (String k : set) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(k);
                }
                processPrefs(sAppContext).edit().putString(KEY_HIDDEN_SETTINGS, sb.toString()).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save hidden settings error: " + t);
        }
    }

    /** 切换某一项：勾选=加入屏蔽，取消=移除。 */
    private void toggleHiddenSetting(String key, boolean hidden) {
        java.util.Set<String> set = hiddenSettings();
        if (hidden) set.add(key); else set.remove(key);
        saveHiddenSettings(set);
    }

    private boolean isSettingHidden(String key) {
        return hiddenSettings().contains(key);
    }

    private boolean isBlockUpdateEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_BLOCK_UPDATE, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveBlockUpdateEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_BLOCK_UPDATE, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save block update error: " + t);
        }
    }

    private void navigateToCleanSettingsPicker(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_CLEAN_SETTINGS_PICKER);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            XposedBridge.log("[SBPlus] navigated to clean settings picker");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToCleanSettingsPicker error: " + t);
        }
    }

    /** 主开关：精简设置页。 */
    private Object buildCleanSettingsSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "精简设置页");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_clean_settings");
        XposedHelpers.callMethod(pref, "setSummary", "屏蔽设置页里不需要的项目");
        XposedHelpers.callMethod(pref, "setChecked", isCleanSettingsEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);
        try {
            XposedHelpers.callMethod(pref, "setDividerVisible", true);
        } catch (Throwable ignored) {}

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (actObj instanceof android.app.Activity) {
                                        navigateToCleanSettingsPicker((android.app.Activity) actObj);
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] clean settings navigate error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] clean settings click bind failed: " + t);
        }

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    Object newVal = args[1];
                                    boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                    saveCleanSettingsEnabled(enabled);
                                    XposedBridge.log("[SBPlus] clean settings toggle: " + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] clean settings listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] clean settings listener bind failed: " + t);
        }

        return pref;
    }

    /** 主开关：屏蔽更新（清除更新通知/弹窗 + 阻断更新检查网络连接 + 屏蔽升级组件）。 */
    private Object buildBlockUpdateSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "屏蔽更新和小红点");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_block_update");
        XposedHelpers.callMethod(pref, "setSummary", "彻底屏蔽浏览器的更新检查、更新弹窗与升级组件");
        XposedHelpers.callMethod(pref, "setChecked", isBlockUpdateEnabled());

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    Object newVal = args[1];
                                    boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                    saveBlockUpdateEnabled(enabled);
                                    XposedBridge.log("[SBPlus] block update toggled: " + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] block update listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] block update listener bind failed: " + t);
        }

        return pref;
    }

    // ---- 主页视频背景 ----

    private boolean isVideoBgEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_VIDEO_BG, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveVideoBgEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_VIDEO_BG, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save video bg enabled error: " + t);
        }
    }

    private String videoBgPath() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getString(KEY_VIDEO_BG_PATH, "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void saveVideoBgPath(String path) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putString(KEY_VIDEO_BG_PATH, path == null ? "" : path).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save video bg path error: " + t);
        }
    }

    private void navigateToVideoBgPicker(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_VIDEO_BG_PICKER);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            XposedBridge.log("[SBPlus] navigated to video bg picker");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToVideoBgPicker error: " + t);
        }
    }

    /** 主开关：主页视频背景。 */
    private Object buildVideoBgSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "主页视频背景");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_video_bg");
        String path = videoBgPath();
        XposedHelpers.callMethod(pref, "setSummary",
                path.isEmpty() ? "点击选择视频文件作为主页背景" : ("已设置视频背景"));
        XposedHelpers.callMethod(pref, "setChecked", isVideoBgEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);
        try {
            XposedHelpers.callMethod(pref, "setDividerVisible", true);
        } catch (Throwable ignored) {}

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (actObj instanceof android.app.Activity) {
                                        navigateToVideoBgPicker((android.app.Activity) actObj);
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] video bg navigate error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video bg click bind failed: " + t);
        }

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    Object newVal = args[1];
                                    boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                    saveVideoBgEnabled(enabled);
                                    XposedBridge.log("[SBPlus] video bg toggled: " + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] video bg listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video bg listener bind failed: " + t);
        }

        return pref;
    }

    /** 视频背景选择子页：显示当前视频文件 + 选择入口 + 使用提示。 */
    private void injectVideoBgPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        String cur = videoBgPath();

        // 当前视频路径展示行。
        Object status = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(status, "setTitle", "当前视频");
        XposedHelpers.callMethod(status, "setKey", "sbplus_videobg_status");
        XposedHelpers.callMethod(status, "setSummary", cur.isEmpty() ? "尚未选择视频" : cur);
        XposedHelpers.callMethod(screen, "addPreference", status);

        // 选择视频文件行（跳转到文件管理器）。
        Object choose = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(choose, "setTitle", "选择视频文件");
        XposedHelpers.callMethod(choose, "setKey", "sbplus_videobg_choose");
        XposedHelpers.callMethod(choose, "setSummary", "通过系统文件管理器选择（建议 mp4）");
        bindVideoBgChooseClick(choose, cl);
        XposedHelpers.callMethod(screen, "addPreference", choose);

        // 清除已选视频行。
        Object clear = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(clear, "setTitle", "清除视频");
        XposedHelpers.callMethod(clear, "setKey", "sbplus_videobg_clear");
        XposedHelpers.callMethod(clear, "setSummary", "只清设置，保留视频文件");
        bindVideoBgClearClick(clear, cl);
        XposedHelpers.callMethod(screen, "addPreference", clear);

        // 删除视频文件行。
        Object del = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(del, "setTitle", "删除视频");
        XposedHelpers.callMethod(del, "setKey", "sbplus_videobg_delete");
        XposedHelpers.callMethod(del, "setSummary", "删除已复制到 Movies/SBPlus 的视频文件");
        bindVideoBgDeleteClick(del, cl);
        XposedHelpers.callMethod(screen, "addPreference", del);

        XposedBridge.log("[SBPlus] video bg picker injected");
    }

    /** 选择视频：启动系统文件选择器（ACTION_OPEN_DOCUMENT，只选视频）。 */
    private void bindVideoBgChooseClick(Object pref, ClassLoader cl) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (actObj instanceof android.app.Activity) {
                                        android.app.Activity act = (android.app.Activity) actObj;
                                        android.content.Intent i = new android.content.Intent(
                                                android.content.Intent.ACTION_OPEN_DOCUMENT);
                                        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                                        i.setType("video/*");
                                        try {
                                            act.startActivityForResult(i, 61001);
                                        } catch (Throwable t) {
                                            android.widget.Toast.makeText(act, "无法打开文件选择器: " + t.getMessage(),
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] video choose click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video choose click bind failed: " + t);
        }
    }

    /** 清除已选视频。 */
    private void bindVideoBgClearClick(Object pref, ClassLoader cl) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    saveVideoBgPath("");
                                    Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (ctxObj instanceof Context) {
                                        android.widget.Toast.makeText((Context) ctxObj, "已清除视频背景",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    XposedBridge.log("[SBPlus] video bg cleared");
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] video clear click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video clear click bind failed: " + t);
        }
    }

    /** 删除已复制到 Movies/SBPlus 的所有视频文件（含历史重命名残留），并清掉当前设置。 */
    private void bindVideoBgDeleteClick(Object pref, ClassLoader cl) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Context ctx = (Context) XposedHelpers.callMethod(clicked, "getContext");
                                    int deleted = deleteVideoBgFiles(ctx);
                                    saveVideoBgPath("");
                                    saveVideoBgEnabled(false);
                                    android.widget.Toast.makeText(ctx,
                                            deleted > 0 ? ("已删除 " + deleted + " 个视频文件") : "没有可删除的视频",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    XposedBridge.log("[SBPlus] video files deleted: " + deleted);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] video delete click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video delete click bind failed: " + t);
        }
    }

    /** 通过 MediaStore 删除 Movies/SBPlus 目录下所有 SBPlus 视频（含 (1) 等重命名），返回删除数量。 */
    private int deleteVideoBgFiles(Context ctx) {
        int deleted = 0;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri base = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            // 1) 直接扫 Movies/SBPlus 目录下的文件。
            android.database.Cursor cur = cr.query(base,
                    new String[]{android.provider.MediaStore.Video.Media._ID,
                            android.provider.MediaStore.Video.Media.DISPLAY_NAME},
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH + "=?",
                    new String[]{android.os.Environment.DIRECTORY_MOVIES + "/SBPlus/"}, null);
            if (cur != null) {
                try {
                    while (cur.moveToNext()) {
                        long id = cur.getLong(0);
                        cr.delete(android.content.ContentUris.withAppendedId(base, id), null, null);
                        deleted++;
                    }
                } finally {
                    cur.close();
                }
            }
            // 2) 兜底：直接删除物理目录下遗漏的 .mp4 文件（可能存在未入库的残留）。
            java.io.File dir = new java.io.File(
                    java.io.File.separator + "storage" + java.io.File.separator + "emulated"
                            + java.io.File.separator + "0" + java.io.File.separator + "Movies"
                            + java.io.File.separator + "SBPlus");
            if (dir.exists() && dir.isDirectory()) {
                java.io.File[] files = dir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        if (f.isFile() && f.getName().toLowerCase().endsWith(".mp4") && f.delete()) {
                            deleted++;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] deleteVideoBgFiles error: " + t);
        }
        return deleted;
    }

    /** 填充精简设置页：列出所有设置项，每项一个 CheckBox，两列网格显示。 */
    private void injectCleanSettingsPicker(Context ctx, ClassLoader cl, Object screen, Object frag) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        for (final String[] item : SETTINGS_ITEMS) {
            final String key = item[0];
            final String title = item[1];
            Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", title);
            XposedHelpers.callMethod(pref, "setKey", "sbplus_clean_" + key);
            bindCleanRowClick(pref, cl, key, title, screen);
            XposedHelpers.callMethod(screen, "addPreference", pref);
        }
        XposedBridge.log("[SBPlus] clean settings picker injected (" + SETTINGS_ITEMS.length + " items)");
    }

    /** 点击某项：切换勾选状态 + 更新 CheckBox 显示。 */
    private void bindCleanRowClick(Object pref, ClassLoader cl, final String key, final String title, final Object screen) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    boolean nowHidden = !isSettingHidden(key);
                                    toggleHiddenSetting(key, nowHidden);
                                    // 更新 checkbox 状态
                                    android.widget.CheckBox cb = sCleanCheckBoxes.get("sbplus_clean_" + key);
                                    if (cb != null) cb.setChecked(nowHidden);
                                    android.widget.Toast.makeText((Context) XposedHelpers.callMethod(clicked, "getContext"),
                                            nowHidden ? ("已屏蔽: " + title) : ("已取消屏蔽: " + title),
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    XposedBridge.log("[SBPlus] clean setting toggled: " + key + " -> " + nowHidden);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] clean row click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] clean row click bind failed: " + t);
        }
    }

    /** 在精简设置行注入 CheckBox（替代 radio dot）。 */
    private void injectCleanCheckBox(final android.view.View root, final String prefKey) {
        try {
            android.view.View iconFrame = root.findViewById(android.R.id.icon_frame);
            android.view.ViewGroup target;
            if (iconFrame instanceof android.view.ViewGroup) {
                target = (android.view.ViewGroup) iconFrame;
            } else {
                target = (android.view.ViewGroup) root;
            }
            // 清空 target 里已有的 CheckBox/RadioButton，避免 onBindViewHolder 复用导致叠加两个框。
            if (target.getChildCount() > 0) {
                for (int i = target.getChildCount() - 1; i >= 0; i--) {
                    android.view.View c = target.getChildAt(i);
                    if (c instanceof android.widget.CheckBox || c instanceof android.widget.RadioButton) {
                        target.removeViewAt(i);
                    }
                }
            }
            String rawKey = prefKey.substring("sbplus_clean_".length());
            final android.widget.CheckBox cb = new android.widget.CheckBox(root.getContext());
            cb.setClickable(false);
            cb.setFocusable(false);
            cb.setText("");
            cb.setPadding(dp(root.getContext(), 4), 0, dp(root.getContext(), 4), 0);
            int cbDrawable = resolveSeslCheckboxDrawable(root.getContext());
            if (cbDrawable != 0) cb.setButtonDrawable(cbDrawable);
            cb.setChecked(isSettingHidden(rawKey));
            target.addView(cb, 0, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            sCleanCheckBoxes.put(prefKey, cb);
            XposedBridge.log("[SBPlus] clean checkbox injected: " + prefKey);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectCleanCheckBox error: " + t);
        }
    }

    /** 三星 Sesl checkbox drawable 查找（失败返回 0）。 */
    private int resolveSeslCheckboxDrawable(android.content.Context ctx) {
        try {
            android.content.res.Resources r = ctx.getResources();
            String pkg = ctx.getPackageName();
            int[] candidates = {
                r.getIdentifier("sesl_btn_check", "drawable", pkg),
                r.getIdentifier("sesl_checkbox", "drawable", pkg),
                r.getIdentifier("tw_btn_check", "drawable", pkg),
            };
            for (int id : candidates) {
                if (id != 0) return id;
            }
        } catch (Throwable ignored) {}
        return 0;
    }


    /** Main switch row: 浏览器标识 (UA override), mirroring buildExternalDownloaderSwitch. */
    private Object buildUaSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "浏览器标识");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_ua_override");
        XposedHelpers.callMethod(pref, "setSummary", "伪装 User-Agent（桌面 Chrome / 手机 / iPhone / 自定义）");
        XposedHelpers.callMethod(pref, "setChecked", isUaEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);
        try {
            XposedHelpers.callMethod(pref, "setDividerVisible", true);
        } catch (Throwable ignored) {}

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (actObj instanceof android.app.Activity) {
                                        navigateToUaPicker((android.app.Activity) actObj);
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] UA navigate error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] UA click bind failed: " + t);
        }

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    Object newVal = args[1];
                                    boolean enabled = newVal instanceof Boolean && (Boolean) newVal;
                                    saveUaEnabled(enabled);
                                    XposedBridge.log("[SBPlus] UA switch toggled: " + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] UA switch listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] UA switch listener bind failed: " + t);
        }

        return pref;
    }

    /** Fill the UA picker sub-page: 3 presets + 1 custom, mirroring injectDownloaderPicker. */
    private void injectUaPicker(Context ctx, ClassLoader cl, Object screen) {
        final String current = userAgent();
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        for (final String[] entry : PRESET_UAS) {
            final String label = entry[0];
            final String ua = entry[1];
            Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", label);
            XposedHelpers.callMethod(pref, "setKey", "sbplus_ua_" + ua);
            XposedHelpers.callMethod(pref, "setSummary", ua);
            bindUaClick(pref, cl, ua, label, screen);
            XposedHelpers.callMethod(screen, "addPreference", pref);
        }

        boolean isCustom = !isPresetUa(current) && current.length() > 0;
        Object custom = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(custom, "setTitle", "自定义 UA");
        XposedHelpers.callMethod(custom, "setKey", "sbplus_ua_custom");
        XposedHelpers.callMethod(custom, "setSummary", isCustom ? ("当前: " + current) : "输入 UA 并确认");
        bindUaCustomClick(custom, cl, screen);
        XposedHelpers.callMethod(screen, "addPreference", custom);

        XposedBridge.log("[SBPlus] ua picker injected (3 presets + custom)");
    }

    /** Bind click on a preset UA row: save the full UA string + refresh radio dots. */
    private void bindUaClick(Object pref, ClassLoader cl, final String ua, final String label, final Object screen) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    saveUserAgent(ua);
                                    Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (ctxObj instanceof Context) {
                                        android.widget.Toast.makeText((Context) ctxObj,
                                                "已选择: " + label, android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    refreshRadioDots("sbplus_ua_" + ua);
                                    XposedBridge.log("[SBPlus] UA selected: " + label);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] UA click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] UA click bind failed: " + t);
        }
    }

    /** Bind click on the custom UA row: select its dot + focus the inline EditText. */
    private void bindUaCustomClick(Object pref, ClassLoader cl, final Object screen) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    sPrevUa = userAgent();
                                    refreshRadioDots("sbplus_ua_custom");
                                    android.widget.EditText edit = sUaCustomEditText;
                                    Context ctx = (Context) XposedHelpers.callMethod(clicked, "getContext");
                                    if (edit != null) {
                                        edit.requestFocus();
                                        android.view.inputmethod.InputMethodManager imm =
                                                (android.view.inputmethod.InputMethodManager)
                                                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                                        if (imm != null) {
                                            imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                                        }
                                    }
                                    XposedBridge.log("[SBPlus] custom UA dot selected");
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] custom UA click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] custom UA click bind failed: " + t);
        }
    }


    /**
     * Fill the region picker sub-page with a radio list of countries/regions.
     * Selecting a row persists the ISO code and marks it as the active choice.
     */
    /**
     * Region picker: build our own ScrollView radio list and swap it into the fragment's
     * view. Fully self-managed (scroll + mutual exclusion + persistence), avoiding Samsung's
     * broken PreferenceGroup attach flow that caused duplicate rows and shared check states.
     */
    
    private int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Resolve a (possibly obfuscated) single-arg listener interface type from the setter. */
    private Class<?> listenerParamType(Class<?> cls, String setterName) {        for (java.lang.reflect.Method mm : cls.getMethods()) {
            if (mm.getName().equals(setterName) && mm.getParameterTypes().length == 1) {
                return mm.getParameterTypes()[0];
            }
        }
        throw new RuntimeException("no method " + setterName + " on " + cls);
    }

    private void navigateToDownloaderPicker(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_DOWNLOADER_PICKER);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            XposedBridge.log("[SBPlus] navigated to downloader picker");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToDownloaderPicker error: " + t);
        }
    }

    private boolean isBridgeEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_BRIDGE, true);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private void saveBridgeEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_BRIDGE, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save bridge enabled error: " + t);
        }
    }

    private boolean isGridMenuEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_GRID_MENU, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveGridMenuEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_GRID_MENU, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save grid menu enabled error: " + t);
        }
    }

    private boolean isRegionLockEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_REGION_LOCK, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveRegionLockEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_REGION_LOCK, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save region lock enabled error: " + t);
        }
    }

    private String regionCode() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getString(KEY_REGION_CODE, "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void saveRegionCode(String code) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putString(KEY_REGION_CODE, code).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save region code error: " + t);
        }
    }

    private boolean isPresetRegion(String code) {
        for (String[] e : PRESET_REGIONS) {
            if (e[1].equals(code)) return true;
        }
        return false;
    }

    /**
     * Region-lock feature: when the user enables "锁定国家/地区" and picks a country,
     * override DebugSettings.getCountryIsoCode() to return the chosen ISO code. This is the
     * same value the browser's hidden "Feature variation test" (about:debug) edits, so it
     * shifts all region-dependent behavior without a manual debug-menu walkthrough.
     */
    private void hookRegionLock(ClassLoader cl) {
        // Mirrors OneUIX's "force US" implementation, but switchable + persistent.
        // The browser's *real* country decision entry point is CountryUtil.getCountryIsoCode()
        // (isUsa()/isChina()/etc. all funnel through it). Hooked that + the two SystemProperties
        // geolocation entry points the browser also consults.
        final String COUNTRY_UTIL = "com.sec.android.app.sbrowser.common.device.CountryUtil";
        final String SYS_PROP = "com.sec.android.app.sbrowser.common.device.SystemProperties";
        final String APP_INFO = "com.sec.android.app.sbrowser.common.application.AppInfo";

        XC_MethodHook regionMethod = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!isRegionLockEnabled()) return;
                    String code = regionCode();
                    if (code == null || code.isEmpty()) return;
                    param.setResult(code);
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] region lock hook error: " + t);
                }
            }
        };

        try {
            Class<?> c = XposedHelpers.findClass(COUNTRY_UTIL, cl);
            XposedHelpers.findAndHookMethod(c, "getCountryIsoCode", regionMethod);
            XposedBridge.log("[SBPlus] CountryUtil.getCountryIsoCode hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] CountryUtil hook failed: " + t);
        }

        try {
            Class<?> c = XposedHelpers.findClass(SYS_PROP, cl);
            XposedHelpers.findAndHookMethod(c, "getCountryCodeintoLocaleForGED", regionMethod);
            XposedHelpers.findAndHookMethod(c, "getCscCountryIsoCode", regionMethod);
            XposedBridge.log("[SBPlus] SystemProperties region hooks done");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] SystemProperties hook failed: " + t);
        }

        try {
            Class<?> c = XposedHelpers.findClass(APP_INFO, cl);
            XposedHelpers.findAndHookMethod(c, "isCnApk", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (!isRegionLockEnabled()) return;
                        // Force non-CN when region lock is on (matches OneUIX).
                        param.setResult(false);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] isCnApk hook error: " + t);
                    }
                }
            });
            XposedBridge.log("[SBPlus] AppInfo.isCnApk hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] AppInfo hook failed: " + t);
        }
    }
    /**
     * UA override: when the 浏览器标识 switch is on and a UA string is chosen, return that
     * string from TerraceHelper.getUserAgent() (Samsung's native UA source) so every request
     * and navigator.userAgent reports the spoofed value.
     */
        /**
     * UA override: 浏览器标识 开关开启时，在 SBrowserCommandLine.initialize() 完成后，
     * 追加 Chromium 标准 switch "user-agent"（TerraceCommandLine.appendSwitchWithValue），
     * 完整替换 UA（而不是三星 csc-feature-user-agent 的拼接）。需重启浏览器后生效。
     */
    private void hookUaOverride(ClassLoader cl) {
        try {
            Class<?> cmdline = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.init.SBrowserCommandLine", cl);
            XposedHelpers.findAndHookMethod(cmdline, "initialize",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Class<?> tc = XposedHelpers.findClass(
                                        "com.sec.terrace.TerraceCommandLine", cl);
                                if (isBlockUpdateEnabled()) {
                                    XposedHelpers.callStaticMethod(tc, "appendSwitch",
                                            "disable-update-dialog");
                                    XposedBridge.log("[SBPlus] disable-update-dialog switch injected");
                                }
                                if (isUaEnabled()) {
                                    String ua = userAgent();
                                    if (ua != null && !ua.isEmpty()) {
                                        XposedHelpers.callStaticMethod(tc, "appendSwitchWithValue",
                                                "user-agent", ua);
                                        XposedBridge.log("[SBPlus] UA switch injected: " + ua);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] append switch failed: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] SBrowserCommandLine.initialize hooked for UA override");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] UA override hook failed: " + t);
        }
    }

    /** 屏蔽更新：阻断更新检查入口 + 追加官方 disable-update-dialog switch（屏蔽弹窗）。 */
    private void hookBlockUpdate(ClassLoader cl) {
        try {
            Class<?> updateMgr = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.stub.UpdateManager", cl);

            // 自动检查入口（页面加载完成后自动检查）。
            XposedHelpers.findAndHookMethod(updateMgr, "checkUpdateAtLoadFinishedIfAvailable",
                    android.app.Activity.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(null);
                        }
                    });

            // 手动/按情况检查。
            XposedHelpers.findAndHookMethod(updateMgr, "checkUpdateByCase", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isBlockUpdateEnabled()) param.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(updateMgr, "checkUpdate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isBlockUpdateEnabled()) param.setResult(null);
                }
            });

            XposedBridge.log("[SBPlus] UpdateManager update-check entry hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] block update hook failed: " + t);
        }

        // 兜底：阻断底层商店检查（Galaxy Store / Google Play 的网络 AIDL 调用）。
        try {
            Class<?> stubUtil = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.stub.StubUtil", cl);
            XposedHelpers.findAndHookMethod(stubUtil, "checkUpdateOnGalaxyStore",
                    XposedHelpers.findClass(
                            "com.sec.android.app.sbrowser.common.stub.StubRequest$StubListener", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(stubUtil, "checkUpdateForPackage",
                    String.class, String.class,
                    XposedHelpers.findClass(
                            "com.sec.android.app.sbrowser.common.stub.StubRequest$StubListener", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(null);
                        }
                    });
            XposedBridge.log("[SBPlus] StubUtil update network hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] stub update network hook failed: " + t);
        }

        // 屏蔽设置页顶部「更新应用程序」卡片（独立于精简设置页开关）。
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.utils.SettingsUtils", cl);
            XposedHelpers.findAndHookMethod(utils, "shouldShowUpdateCard",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(false);
                        }
                    });
            XposedBridge.log("[SBPlus] shouldShowUpdateCard (block update) hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] block update card hook failed: " + t);
        }

        // 屏蔽「关于」页更新按钮：把 UPDATE 状态降级为 NO_UPDATE，更新按钮永不显示。
        try {
            Class<?> stateCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.AboutFragment$State", cl);
            final Object noUpdateState =
                    XposedHelpers.getStaticObjectField(stateCls, "NO_UPDATE");

            Class<?> aboutFrag = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.AboutFragment", cl);
            XposedHelpers.findAndHookMethod(aboutFrag, "updateViews", stateCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isBlockUpdateEnabled()) return;
                            Object state = param.args[0];
                            if (state != null && state.toString().contains("UPDATE")
                                    && !state.equals(noUpdateState)) {
                                param.args[0] = noUpdateState;
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] AboutFragment.updateViews (block update) hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] about update button hook failed: " + t);
        }

        // 彻底禁止应用升级：阻断跳转商店（callAppStore）。
        try {
            Class<?> stubUtil2 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.stub.StubUtil", cl);
            XposedHelpers.findAndHookMethod(stubUtil2, "callAppStore",
                    android.app.Activity.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(null);
                        }
                    });
            XposedBridge.log("[SBPlus] StubUtil.callAppStore hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] callAppStore hook failed: " + t);
        }

        // 屏蔽「关于」红点：hasNewUpdate 返回 false，设置页徽标不再计入更新。
        try {
            Class<?> utils2 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.utils.SettingsUtils", cl);
            XposedHelpers.findAndHookMethod(utils2, "hasNewUpdate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(false);
                        }
                    });
            XposedBridge.log("[SBPlus] SettingsUtils.hasNewUpdate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hasNewUpdate hook failed: " + t);
        }

        // 屏蔽「更多」/「设置」入口的聚合红点（getSettingsBadgeCount 累加更新+AI+隐私等各类提示）。
        try {
            Class<?> utils3 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.utils.SettingsUtils", cl);
            XposedHelpers.findAndHookMethod(utils3, "getSettingsBadgeCount",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.setResult(0);
                        }
                    });
            XposedBridge.log("[SBPlus] SettingsUtils.getSettingsBadgeCount hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] getSettingsBadgeCount hook failed: " + t);
        }

        // 屏蔽「更多」按钮小红点：强制 updateOptionMenuBadgeVisibility 的参数为 0。
        try {
            Class<?> toolbarLayout = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.ToolbarButtonLayout", cl);
            XposedHelpers.findAndHookMethod(toolbarLayout,
                    "updateOptionMenuBadgeVisibility", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.args[0] = 0;
                        }
                    });
            XposedBridge.log("[SBPlus] ToolbarButtonLayout.updateOptionMenuBadgeVisibility hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] toolbar more badge hook failed: " + t);
        }

        try {
            Class<?> bottombar = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.Bottombar", cl);
            XposedHelpers.findAndHookMethod(bottombar,
                    "updateOptionMenuBadgeVisibility", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isBlockUpdateEnabled()) param.args[0] = 0;
                        }
                    });
            XposedBridge.log("[SBPlus] Bottombar.updateOptionMenuBadgeVisibility hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] bottombar more badge hook failed: " + t);
        }
    }

    /**
     * 主页视频背景：
     *  (1) 在浏览器设置页里选择视频后，startActivityForResult 的结果回到
     *      SettingsActivity.onActivityResult，我们在这里拿到 content:// URI，
     *      把视频复制到公共目录（/sdcard/SBPlus/video_bg.mp4），存下绝对路径。
     *  (2) 主页背景 View（QuickAccessCustomBackground）是 QuickAccessMainLayout 的第一个
     *      子 View（ImageView）。开关开启且路径有效时，叠一个 VideoView 到它上面循环
     *      静音播放，同时亮起 dim_layer 遮罩以保证内容可读。
     */
    private void hookVideoBackground(ClassLoader cl) {
        // (1) SettingsActivity.onActivityResult -> 接收选中的视频 URI 并复制到公共目录。
        try {
            Class<?> settingsActivity = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.SettingsActivity", cl);
            XposedHelpers.findAndHookMethod(settingsActivity, "onActivityResult",
                    int.class, int.class, android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int req = (Integer) param.args[0];
                                if (req != 61001) return;
                                int res = (Integer) param.args[1];
                                android.content.Intent data = (android.content.Intent) param.args[2];
                                if (res != android.app.Activity.RESULT_OK || data == null
                                        || data.getData() == null) return;
                                android.net.Uri uri = data.getData();
                                String saved = copyVideoToPublicDir((android.content.Context) param.thisObject, uri);
                                if (saved != null && !saved.isEmpty()) {
                                    saveVideoBgPath(saved);
                                    saveVideoBgEnabled(true);
                                    android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                            "视频背景已设置", android.widget.Toast.LENGTH_SHORT).show();
                                    XposedBridge.log("[SBPlus] video bg saved: " + saved);
                                } else {
                                    android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                            "视频复制失败", android.widget.Toast.LENGTH_SHORT).show();
                                    XposedBridge.log("[SBPlus] video bg copy failed");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] onActivityResult video error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] SettingsActivity.onActivityResult hooked for video bg");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] onActivityResult video hook failed: " + t);
        }

        // (2) QuickAccessCustomBackground.onFinishInflate(View$OnLayoutChangeListener, Runnable)
        //     -> 三星自定义方法（非标准 View.onFinishInflate），在背景 View 置为 VISIBLE 时调用，
        //        是叠加 VideoView 的最佳时机。
        try {
            Class<?> bgCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessCustomBackground", cl);
            Class<?> layoutListener = XposedHelpers.findClass(
                    "android.view.View$OnLayoutChangeListener", cl);
            XposedHelpers.findAndHookMethod(bgCls, "onFinishInflate",
                    layoutListener, Runnable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                attachVideoBackground(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] attachVideoBackground error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessCustomBackground.onFinishInflate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video bg view hook failed: " + t);
        }
    }

    /** 把选中的视频 content URI 通过 MediaStore 插到公共 Video 集合，返回可访问的 content URI（失败返回 null）。 */
    private String copyVideoToPublicDir(android.content.Context ctx, android.net.Uri uri) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            in = cr.openInputStream(uri);
            if (in == null) return null;

            // 先把源视频整个读入内存，拿到真实长度（用于后续正确写入 SIZE 元数据）。
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[65536];
            int r;
            while ((r = in.read(tmp)) > 0) bos.write(tmp, 0, r);
            in.close();
            in = null;
            byte[] videoBytes = bos.toByteArray();

            // 插入公共 Video 集合，显式写入 SIZE 与时长无关的关键元数据。
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "SBPlus_video_bg.mp4");
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(android.provider.MediaStore.Video.Media.SIZE, videoBytes.length);
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES + "/SBPlus");
            cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
            android.net.Uri outUri = cr.insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (outUri == null) return null;

            out = cr.openOutputStream(outUri);
            if (out == null) {
                cr.delete(outUri, null, null);
                return null;
            }
            out.write(videoBytes);
            out.flush();
            out.close();
            out = null;

            // 清除 pending 标记，再次确认 SIZE 已正确。
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
            done.put(android.provider.MediaStore.Video.Media.SIZE, videoBytes.length);
            cr.update(outUri, done, null, null);

            // 反查真实文件绝对路径（_data），返回给播放层直接 setVideoPath。
            android.database.Cursor cur = cr.query(outUri,
                    new String[]{android.provider.MediaStore.Video.Media.DATA}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        String data = cur.getString(0);
                        if (data != null && !data.isEmpty()) return data;
                    }
                } finally {
                    cur.close();
                }
            }
            return outUri.toString();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] copyVideoToPublicDir error: " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 在 QuickAccessCustomBackground 所在父容器叠加 SurfaceView+MediaPlayer，循环播放背景视频。 */
    private void attachVideoBackground(Object bgViewObj) {
        try {
            if (!(bgViewObj instanceof android.view.View)) return;
            android.view.View bg = (android.view.View) bgViewObj;
            if (!isVideoBgEnabled()) return;
            String path = videoBgPath();
            if (path == null || path.isEmpty()) return;
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;

            android.view.ViewGroup parent = (android.view.ViewGroup) bg.getParent();
            if (parent == null) return;

            // 清掉旧的 TextureView，避免重复叠加。
            try {
                for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                    android.view.View c = parent.getChildAt(i);
                    if (c instanceof android.view.TextureView) parent.removeViewAt(i);
                }
            } catch (Throwable ignored) {}

            // TextureView 是普通 View，参与正常 View 层级绘制，不会被
            // QuickAccessMainLayout 的不透明背景色盖住（SurfaceView 有此问题）。
            final android.view.TextureView tv = new android.view.TextureView(bg.getContext());
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();

            tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    try {
                        mp.reset();
                        XposedBridge.log("[SBPlus] mp setDataSource path=" + path);
                        mp.setDataSource(path);
                        mp.setLooping(true);
                        mp.setVolume(0f, 0f);
                        mp.setSurface(new android.view.Surface(st));
                        mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                            @Override public void onPrepared(android.media.MediaPlayer m) {
                                XposedBridge.log("[SBPlus] mp prepared, starting");
                                m.start();
                            }
                        });
                        mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                            @Override public boolean onError(android.media.MediaPlayer m, int what, int extra) {
                                XposedBridge.log("[SBPlus] mp error what=" + what + " extra=" + extra);
                                return false;
                            }
                        });
                        mp.prepareAsync();
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] mp surfaceAvailable error: " + t);
                    }
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignored) {}
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
            });

            android.view.ViewGroup.LayoutParams lp = new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            parent.addView(tv, 0, lp);
            bg.setVisibility(android.view.View.GONE);
            XposedBridge.log("[SBPlus] video background attached: " + path);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] attachVideoBackground error: " + t);
        }
    }


    // ============ 油猴脚本（Userscript）支持 ============

    private boolean isUserscriptEnabled() {
        try {
            if (sAppContext != null) return processPrefs(sAppContext).getBoolean(KEY_ENABLE_USERSCRIPT, false);
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveUserscriptEnabled(boolean enabled) {
        try {
            if (sAppContext != null) processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_USERSCRIPT, enabled).commit();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save userscript enabled error: " + t);
        }
    }

    /** 脚本目录：浏览器外部文件目录下的 userscripts/ */
    private java.io.File userscriptDir() {
        try {
            if (sAppContext == null) return null;
            java.io.File dir = new java.io.File(sAppContext.getExternalFilesDir(null), "SBPlus" + java.io.File.separator + "userscripts");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] userscriptDir error: " + t);
            return null;
        }
    }

    /** 主开关：油猴脚本。 */
    private Object buildUserscriptSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", "油猴脚本");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_userscript");
        java.io.File dir = userscriptDir();
        int count = countUserscripts(dir);
        XposedHelpers.callMethod(pref, "setSummary", count > 0 ? ("已加载 " + count + " 个脚本，目录: " + (dir == null ? "?" : dir.getAbsolutePath())) : "脚本目录: " + (dir == null ? "未初始化" : dir.getAbsolutePath()));
        XposedHelpers.callMethod(pref, "setChecked", isUserscriptEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);
        try { XposedHelpers.callMethod(pref, "setDividerVisible", true); } catch (Throwable ignored) {}

        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean enabled = args[1] instanceof Boolean && (Boolean) args[1];
                                    saveUserscriptEnabled(enabled);
                                    XposedBridge.log("[SBPlus] userscript toggled: " + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] userscript listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] userscript listener bind failed: " + t);
        }
        return pref;
    }

    /** 扫描并解析脚本目录，返回解析出的脚本元数据列表。 */
    private java.util.List<UserscriptMeta> loadUserscripts() {
        java.util.List<UserscriptMeta> list = new java.util.ArrayList<UserscriptMeta>();
        try {
            java.io.File dir = userscriptDir();
            if (dir == null || !dir.exists()) return list;
            java.io.File[] files = dir.listFiles();
            if (files == null) return list;
            for (java.io.File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".user.js")) continue;
                try {
                    String content = readFileText(f);
                    UserscriptMeta meta = UserscriptMeta.parse(content);
                    if (meta != null && !meta.name.isEmpty()) list.add(meta);
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] parse userscript " + f.getName() + " error: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] loadUserscripts error: " + t);
        }
        return list;
    }

    private int countUserscripts(java.io.File dir) {
        try {
            if (dir == null || !dir.exists()) return 0;
            java.io.File[] files = dir.listFiles();
            if (files == null) return 0;
            int c = 0;
            for (java.io.File f : files) if (f.isFile() && f.getName().endsWith(".user.js")) c++;
            return c;
        } catch (Throwable ignored) { return 0; }
    }

    private String readFileText(java.io.File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    /** 油猴脚本元数据（@name / @match / @include / @exclude / @run-at）。 */
    static class UserscriptMeta {
        String name = "";
        java.util.List<String> match = new java.util.ArrayList<String>();
        java.util.List<String> include = new java.util.ArrayList<String>();
        java.util.List<String> exclude = new java.util.ArrayList<String>();
        String runAt = "document-end";
        String code = "";

        static UserscriptMeta parse(String content) {
            UserscriptMeta m = new UserscriptMeta();
            // 提取 metadata block（// ==UserScript== 到 // ==/UserScript==）
            String metaBlock = "";
            int ms = content.indexOf("==UserScript==");
            int me = content.indexOf("==/UserScript==");
            if (ms >= 0 && me > ms) {
                metaBlock = content.substring(ms, me);
                m.code = content.substring(me + "==/UserScript==".length());
            } else {
                // 无 metadata block，整体当脚本，无匹配规则（默认全站）
                m.code = content;
                m.name = "anonymous";
            }
            String[] lines = metaBlock.split("\n");
            for (String ln : lines) {
                String l = ln.trim();
                if (l.startsWith("@name")) { m.name = l.substring("@name".length()).trim(); }
                else if (l.startsWith("@match")) { m.match.add(stripMetaValue(l, "@match")); }
                else if (l.startsWith("@include")) { m.include.add(stripMetaValue(l, "@include")); }
                else if (l.startsWith("@exclude")) { m.exclude.add(stripMetaValue(l, "@exclude")); }
                else if (l.startsWith("@run-at")) { m.runAt = stripMetaValue(l, "@run-at"); }
            }
            if (m.name.isEmpty()) m.name = "untitled";
            return m;
        }

        static String stripMetaValue(String line, String key) {
            String v = line.substring(key.length()).trim();
            while (v.startsWith("//")) v = v.substring(2).trim();
            return v.trim();
        }

        boolean matches(String url) {
            // 有 exclude 命中则直接不匹配
            for (String e : exclude) if (matchGlob(e, url)) return false;
            boolean any = match.isEmpty() && include.isEmpty();
            for (String p : match) if (matchGlob(p, url)) { any = true; break; }
            for (String p : include) if (matchGlob(p, url)) { any = true; break; }
            return any;
        }
    }

    /** 简化 URL 匹配：支持 * 通配符；不含通配符时做包含/精确匹配。 */
    private static boolean matchGlob(String pattern, String url) {
        try {
            if (pattern == null || pattern.isEmpty()) return false;
            String p = pattern.trim();
            if (!p.contains("*")) return url.contains(p);
            // 转 * 为正则 .*
            String regex = java.util.regex.Pattern.quote(p).replace("*", ".*");
            return url.matches(".*" + regex + ".*");
        } catch (Throwable t) { return false; }
    }

    /** GM API 引擎（精简版），注入每个匹配页面。 */
    private static final String GM_API_JS =
        "(function(){" +
        "  var SP=window.__sbplus__||{};" +
        "  function pushLog(l){try{if(window.__sbplusLog)window.__sbplusLog(l);else console.log('[SBPlus] '+l);}catch(e){}}" +
        "  var GM={};" +
        // 存储：桥接到 Java（若未注入 __sbplus__，则回退 localStorage）
        "  var store=(function(){try{return window.__sbplus__;}catch(e){return null;}})();" +
        "  GM.setValue=function(k,v){try{if(store&&store.gmSetValue)store.gmSetValue(k,String(v));else localStorage.setItem('gm_'+k,String(v));}catch(e){}};" +
        "  GM.getValue=function(k,d){try{if(store&&store.gmGetValue){var v=store.gmGetValue(k);return (v===null||v==='')?d:v;}var v=localStorage.getItem('gm_'+k);return (v===null)?d:v;}catch(e){return d;}};" +
        "  GM.deleteValue=function(k){try{if(store&&store.gmDeleteValue)store.gmDeleteValue(k);else localStorage.removeItem('gm_'+k);}catch(e){}};" +
        "  GM.listValues=function(){try{if(store&&store.gmListValues)return store.gmListValues();return [];}catch(e){return[];}};" +
        "  GM.addStyle=function(css){try{var st=document.createElement('style');st.type='text/css';st.textContent=css;document.head.appendChild(st);}catch(e){}};" +
        "  GM.log=function(){try{pushLog(Array.prototype.join.call(arguments,' '));}catch(e){}};" +
        "  GM.info={scriptHandler:'SBPlus',version:'1.0',script:{name:'',version:''}};" +
        "  GM.xmlHttpRequest=function(o){try{var x=new XMLHttpRequest();x.open(o.method||'GET',o.url,true);x.onreadystatechange=function(){if(x.readyState===4){var r={status:x.status,statusText:x.statusText,responseText:x.responseText,response:x.responseText,responseHeaders:'',finalUrl:o.url};try{if(o.onload)o.onload(r);}catch(e){}}};if(o.headers){for(var h in o.headers)x.setRequestHeader(h,o.headers[h]);}if(o.timeout)x.timeout=o.timeout;x.send(o.data||null);}catch(e){try{if(o.onerror)o.onerror();}catch(e2){}}};" +
        "  GM.openInTab=function(url,opt){try{window.open(url,'_blank');}catch(e){}};" +
        "  GM.setClipboard=function(t){try{if(store&&store.gmSetClipboard)store.gmSetClipboard(String(t));}catch(e){}};" +
        "  var g={'GM':GM,'GM_setValue':GM.setValue,'GM_getValue':GM.getValue,'GM_deleteValue':GM.deleteValue,'GM_listValues':GM.listValues,'GM_addStyle':GM.addStyle,'GM_log':GM.log,'GM_info':GM.info,'GM_xmlhttpRequest':GM.xmlHttpRequest,'GM_openInTab':GM.openInTab,'GM_setClipboard':GM.setClipboard,'unsafeWindow':window};" +
        "  for(var k in g){try{if(typeof window[k]==='undefined')window[k]=g[k];}catch(e){}};" +
        "  try{window.GM=window.GM||GM;}catch(e){};" +
        "})();";

    /** 油猴脚本注入核心 hook。 */
    private void hookUserscript(ClassLoader cl) {
        try {
            Class<?> tabEventHandler = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.sbrowser_tab.TabEventHandler", cl);
            XposedHelpers.findAndHookMethod(tabEventHandler, "onLoadFinished", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                injectUserscripts(param.thisObject, (String) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectUserscripts error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] TabEventHandler.onLoadFinished hooked for userscript");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] userscript hook failed: " + t);
        }
    }

    /** 在页面加载完成后，把匹配的油猴脚本注入当前 Tab。 */
    private void injectUserscripts(Object tabEventHandlerObj, String url) {
        if (!isUserscriptEnabled()) return;
        if (url == null || url.isEmpty()) return;
        java.util.List<UserscriptMeta> metas = loadUserscripts();
        if (metas.isEmpty()) return;

        try {
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab"); // SBrowserTab
            if (tab == null) return;
            Object realTab = XposedHelpers.callMethod(tab, "getTab"); // com.sec...tab.Tab
            if (realTab == null) return;
            String curUrl = (String) XposedHelpers.callMethod(realTab, "getUrl");
            if (curUrl == null) curUrl = url;

            StringBuilder sb = new StringBuilder();
            sb.append(GM_API_JS); // 先注入 GM API 引擎
            boolean anyMatch = false;
            for (UserscriptMeta m : metas) {
                if (!m.matches(curUrl)) continue;
                anyMatch = true;
                sb.append("\n(function(){"); sb.append(m.code); sb.append("\n})();");
            }
            if (!anyMatch) return;

            // 跳过 document-start 脚本（本版本先只做 document-end 注入）。
            XposedHelpers.callMethod(realTab, "evaluateJavaScript", sb.toString(), null);
            XposedBridge.log("[SBPlus] userscript injected for " + curUrl + " (" + countMatched(metas, curUrl) + " scripts)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUserscripts error: " + t);
        }
    }

    private int countMatched(java.util.List<UserscriptMeta> metas, String url) {
        int c = 0;
        for (UserscriptMeta m : metas) if (m.matches(url)) c++;
        return c;
    }


    /**
     * 精简设置页：主开关开启时，hook SettingsFragment.initPreferences()（三星加载完所有
     * 设置项后），遍历被勾选的 key，findPreference(key).setVisible(false)。
     */
    private void hookCleanSettings(ClassLoader cl) {
        try {
            Class<?> settingsFragment = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.SettingsFragment", cl);
            XposedHelpers.findAndHookMethod(settingsFragment, "initPreferences",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isCleanSettingsEnabled()) return;
                            java.util.Set<String> hidden = hiddenSettings();
                            if (hidden.isEmpty()) return;
                            Object frag = param.thisObject;
                            int count = 0;
                            for (String key : hidden) {
                                if (key.startsWith("@")) continue; // 特殊项单独处理
                                try {
                                    Object pref = XposedHelpers.callMethod(frag, "findPreference", key);
                                    if (pref != null) {
                                        XposedHelpers.callMethod(pref, "setVisible", false);
                                        count++;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            XposedBridge.log("[SBPlus] clean settings hidden " + count + " items");
                        }
                    });
            XposedBridge.log("[SBPlus] SettingsFragment.initPreferences hooked for clean settings");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] clean settings hook failed: " + t);
        }

        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.utils.SettingsUtils", cl);
            XposedHelpers.findAndHookMethod(utils, "shouldShowUpdateCard",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isCleanSettingsEnabled() && isSettingHidden("@update_card")) {
                                param.setResult(false);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] shouldShowUpdateCard hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] shouldShowUpdateCard hook failed: " + t);
        }

        // 特殊项 @search：屏蔽设置页顶部搜索（阻止展开 + 隐藏搜索入口）。
        try {
            Class<?> settingsActivity = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.settings.SettingsActivity", cl);
            XposedHelpers.findAndHookMethod(settingsActivity, "showSearchView",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isCleanSettingsEnabled() && isSettingHidden("@search")) {
                                param.setResult(null);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(settingsActivity, "onSearchSelected",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isCleanSettingsEnabled() && isSettingHidden("@search")) {
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] search view hooked for clean settings");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] search view hook failed: " + t);
        }
    }




    private float mLastRegionY;
    private int mRegionTouchLog;

    /**
     * Samsung's SeslRecyclerView skips vertical ACTION_MOVE deltas (its own scroll logic
     * drops them, seen as "last move skip"), so on the 17-row region list the list won't
     * scroll by touch. This hook does NOT swallow the native event or fight it; it only
     * *adds* a compensating scrollBy() after the native handler runs, so drag still works
     * while clicks/long-press stay intact.
     */
    private void hookRegionTouchScroll(ClassLoader cl) {
        try {
            Class<?> rv = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView", cl);
            XposedHelpers.findAndHookMethod(rv, "onTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!sRegionPageActive) return;
                    Object self = param.thisObject;
                    if (!(self instanceof android.view.View)) return;
                    android.view.View v = (android.view.View) self;
                    android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                    int action = ev.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        mLastRegionY = ev.getRawY();
                    } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                        float y = ev.getRawY();
                        int dy = (int) (mLastRegionY - y);
                        mLastRegionY = y;
                        if (dy != 0) {
                            XposedHelpers.callMethod(v, "scrollBy", 0, dy);
                            if (mRegionTouchLog++ % 20 == 0) {
                                Object off = XposedHelpers.callMethod(v, "computeVerticalScrollOffset");
                                XposedBridge.log("[SBPlus] scrollBy dy=" + dy + " offset=" + off);
                            }
                        }
                    }
                    if (action == android.view.MotionEvent.ACTION_UP) {
                        XposedBridge.log("[SBPlus] region RV class=" + v.getClass().getName()
                                + " super=" + v.getClass().getSuperclass().getName());
                    }
                }
            });
            XposedBridge.log("[SBPlus] region touch scroll compensation hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] region touch scroll hook failed: " + t);
        }
    }

    /**
     * Samsung's settings screen wraps the preference RecyclerView in a CoordinatorLayout
     * whose AppBarLayout (extended/collapsing toolbar) intercepts vertical touch events,
     * stealing swipes away from the list. On the 17-row region page that makes the list
     * feel impossible to scroll. When the region page is active, force AppBarLayout's
     * touch interception / drag handling to yield so the RecyclerView scrolls normally.
     */
    



    /**
     * Grid-menu feature. When the user enables "启用网格菜单", we reshape Samsung's "More"
     * menu from a single-column vertical list into a multi-column grid (Via-style), resized
     * to full width + 1/4 screen height + bottom-anchored.
     *
     * Three hook points on com.sec.android.app.sbrowser.toolbar.MoreMenuHandler:
     *   1. updateContentView(Z) after  -> swap LinearLayoutManager for GridLayoutManager.
     *   2. getHeight()         before -> return screenH/4 (instead of nearly full screen).
     *   3. updateDialogPosition(Z) after -> force width=screenW, gravity=BOTTOM.
     */
    private void hookMoreMenuGrid(ClassLoader cl) {
        final String handlerCls = "com.sec.android.app.sbrowser.toolbar.MoreMenuHandler";
        Class<?> moreMenuHandler;
        try {
            moreMenuHandler = XposedHelpers.findClass(handlerCls, cl);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] MoreMenuHandler not found: " + t);
            return;
        }

        // (1) Swap LinearLayoutManager -> GridLayoutManager(5) after updateContentView sets it.
        try {
            XposedHelpers.findAndHookMethod(moreMenuHandler, "updateContentView", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object recycler = XposedHelpers.getObjectField(param.thisObject, "mRecyclerView");
                            if (recycler == null) return;
                            Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (ctx == null) return;
                            // Two rows per page (spanCount=2), scrolled horizontally -> left/right
                            // paging. Samsung stripped the horizontal GridLayoutManager ctor, so
                            // create spanCount=2 (which hardcodes VERTICAL) then flip orientation.
                            Object grid = XposedHelpers.newInstance(
                                    XposedHelpers.findClass("androidx.recyclerview.widget.GridLayoutManager", cl),
                                    new Class[]{int.class}, 2);
                            XposedHelpers.callMethod(grid, "setOrientation", 0); // 0 = HORIZONTAL
                            XposedHelpers.callMethod(recycler, "setLayoutManager", grid);
                            // Nudge the first row down a touch from the menu's top edge.
                            try {
                                android.view.View rv = (android.view.View) recycler;
                                float d = rv.getResources().getDisplayMetrics().density;
                                int padTop = (int) (8f * d);
                                rv.setPadding(rv.getPaddingLeft(), padTop,
                                        rv.getPaddingRight(), rv.getPaddingBottom());
                            } catch (Throwable ignored) {}
                            // Pure-grid Via style: hide the dialog header (page title + share
                            // button + divider) so the icon grid owns the menu above the nav bar.
                            hideMenuChrome((android.view.View) recycler);
                            // Manual page snapping: on scroll idle, snap to the nearest page boundary
                            // (a page = RecyclerView width = 5 columns).
                            // --- Paging / indicator dots disabled: let the grid scroll freely. ---
                            // attachGridPager(recycler, cl);
                            // Latch refs for long-press reorder.
                            MenuReorderHelper.setClassLoader(cl);
                            MenuEditHelper.setClassLoader(cl);
                            Object adapter = XposedHelpers.getObjectField(param.thisObject, "mMenuAdapter");
                            if (adapter != null) {
                                cacheGridRefs(param.thisObject, recycler, adapter);
                                MenuReorderHelper.installTouchProxy();
                                // Append a trailing "+" add cell to the grid (adapter hooks).
                                MenuEditHelper.installGridAddItem(adapter, cl);
                                // Diagnostic: how many icons are addable right now?
                                java.util.List<android.view.MenuItem> addable = MenuEditHelper.getAddableMenus();
                                XposedBridge.log("[SBPlus] addable count = " + addable.size());
                            }
                            XposedBridge.log("[SBPlus] grid LayoutManager applied (HORIZONTAL 2 rows, 5 cols/page)");
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid updateContentView hook error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuHandler.updateContentView hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid updateContentView hook failed: " + t);
        }

        // (2) getHeight() -> wrap content (panel height driven by the grid + padding).
        try {
            XposedHelpers.findAndHookMethod(moreMenuHandler, "getHeight",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            param.setResult(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid getHeight hook error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuHandler.getHeight hooked (WRAP_CONTENT)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid getHeight hook failed: " + t);
        }

        // (3) updateDialogPosition(Z) after -> force full width + bottom gravity.
        try {
            XposedHelpers.findAndHookMethod(moreMenuHandler, "updateDialogPosition", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object dialog = XposedHelpers.getObjectField(param.thisObject, "mMoreMenuDialog");
                            if (dialog == null) return;
                            android.app.Dialog d = (android.app.Dialog) dialog;
                            // Taps on the empty area ABOVE the bottom menu currently do nothing:
                            // the dialog window claims a full-screen touch region, so a tap just
                            // above the menu is swallowed without dismissing. Force outside-touch
                            // to dismiss so tapping above the menu closes it.
                            try { d.setCanceledOnTouchOutside(true); } catch (Throwable ignore) {}
                            android.view.Window w = d.getWindow();
                            if (w == null) return;
                            // Clear FLAG_NOT_TOUCH_MODAL so outside taps actually dismiss instead
                            // of being forwarded to the activity underneath.
                            try {
                                int flags = w.getAttributes().flags;
                                flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                            } catch (Throwable ignore) {}
                            Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (ctx == null) return;
                            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
                            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
                            android.view.WindowManager.LayoutParams lp = w.getAttributes();
                            lp.width = screenW;
                            lp.y = 0;
                            lp.x = 0;
                            w.setAttributes(lp);
                            w.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.END);

                            XposedBridge.log("[SBPlus] dialogPos: screenH=" + screenH
                                    + " lpH=" + lp.height + " lpY=" + lp.y + " lpX=" + lp.x
                                    + " w=" + lp.width);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid dialog position hook error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuHandler.updateDialogPosition hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid updateDialogPosition hook failed: " + t);
        }

        // (4) Restyle each grid item to Via-style: icon on top, label below, centered.
        //     We keep Samsung's list item XML (so MenuItemHolder casts stay valid) and only
        //     flip the inner item_container to VERTICAL + center its children in code.
        //     We hook onCreateViewHolder (not onBind) so the changed LayoutParams are in
        //     place before RecyclerView measures the item.
        try {
            Class<?> adapterCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter", cl);
            final Class<?> holderCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter$MenuItemHolder", cl);
            XposedHelpers.findAndHookMethod(adapterCls, "onCreateViewHolder",
                android.view.ViewGroup.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object holder = param.getResult();
                            if (!holderCls.isInstance(holder)) return;
                            restyleGridItem(holder);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid item restyle error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuRecyclerAdapter.onCreateViewHolder hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid onCreateViewHolder hook failed: " + t);
        }

        // (5) Re-hide the divider (and tab-count / badge) AFTER Samsung's onBindViewHolder runs,
        //     because Samsung re-shows the divider for the first/middle items, which sinks the
        //     first "return" item below its row-mates.
        try {
            Class<?> adapterCls2 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter", cl);
            final Class<?> holderCls2 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter$MenuItemHolder", cl);
            XposedHelpers.findAndHookMethod(adapterCls2, "onBindViewHolder",
                XposedHelpers.findClass("androidx.recyclerview.widget.g1", cl), int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object holder = param.args[0];
                            if (!holderCls2.isInstance(holder)) return;
                            hideGridDecorations(holder);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid divider rehide error: " + t);
                        }
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuRecyclerAdapter.onBindViewHolder hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid onBindViewHolder hook failed: " + t);
        }

        // (6) Kill the first-item top offset. MoreMenuItemDecoration adds mSpace top padding to
        //     position 0 (the "return" item), which sinks it 21px below its row-mates in grid
        //     mode. Force the space to 0.
        try {
            Class<?> decoCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuItemDecoration", cl);
            XposedHelpers.findAndHookConstructor(decoCls, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isGridMenuEnabled()) return;
                        param.args[0] = 0;
                    }
                });
            XposedBridge.log("[SBPlus] MoreMenuItemDecoration ctor hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] grid item decoration hook failed: " + t);
        }
    }

    /** Flip a Samsung menu item's inner container to vertical (icon above, label below). */
    private void restyleGridItem(Object holder) {
        try {
            Object containerObj = XposedHelpers.getObjectField(holder, "mContainer");
            if (!(containerObj instanceof android.widget.LinearLayout)) return;
            android.widget.LinearLayout container = (android.widget.LinearLayout) containerObj;

            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP);

            // No manual top/bottom padding: with every label forced to a single line, all item
            // contents share the same height, so vertical centering keeps icons top-aligned
            // across the row AND closes the row gap. Padding here just re-introduces the big
            // icon<->label gap we removed.
            try {
                container.setPadding(0, 0, 0, 0);
            } catch (Throwable ignored) {}

            // Horizontal paging layout: fix each item's width = screenW/5 (5 columns per page)
            // and a fixed height for two rows. Without explicit sizing, GridLayoutManager in
            // HORIZONTAL mode (spanCount=2 rows) would not evenly distribute column widths.
            try {
                android.view.View root = (android.view.View) XposedHelpers.getObjectField(holder, "itemView");
                if (root != null) {
                    float density = container.getResources().getDisplayMetrics().density;
                    int screenW = container.getResources().getDisplayMetrics().widthPixels;
                    int screenH = container.getResources().getDisplayMetrics().heightPixels;
                    int itemW = screenW / 5;
                    int itemH = (int) (64f * density); // compact: icon(32) + single-line label
                    android.view.ViewGroup.LayoutParams rlp = root.getLayoutParams();
                    if (rlp != null) {
                        XposedBridge.log("[SBPlus] restyle rlpType=" + rlp.getClass().getSimpleName()
                                + " beforeW=" + rlp.width + " -> set " + itemW);
                        rlp.width = itemW;
                        rlp.height = itemH;
                        root.setLayoutParams(rlp);
                        root.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    int[] lp = new int[2];
                                    root.getLocationInWindow(lp);
                                    XposedBridge.log("[SBPlus] itemGeom pos? w=" + root.getWidth()
                                            + " mw=" + root.getMeasuredWidth() + " left=" + lp[0]
                                            + " rvW=" + ((android.view.View) root.getParent() != null
                                                ? ((android.view.View) root.getParent()).getWidth() : -1));
                                } catch (Throwable ignore) {}
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {}

            // Center each direct child horizontally. The icon container (a fixed-width
            // RelativeLayout) defaults to START in vertical LinearLayout, and the label keeps
            // a left margin from the horizontal list layout, both of which cause the offset.
            for (int i = 0; i < container.getChildCount(); i++) {
                android.view.View child = container.getChildAt(i);
                android.view.ViewGroup.LayoutParams clp = child.getLayoutParams();
                if (clp instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams llp =
                            (android.widget.LinearLayout.LayoutParams) clp;
                    llp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    child.setLayoutParams(llp);
                }
            }

            Object textObj = XposedHelpers.getObjectField(holder, "mText");
            if (textObj instanceof android.widget.TextView) {
                android.widget.TextView text = (android.widget.TextView) textObj;
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f);
                text.setGravity(android.view.Gravity.CENTER);
                text.setMaxLines(1);
                // Samsung gives the label 12dip vertical padding (from the horizontal-list
                // layout), which after we flip to vertical creates a large icon<->label gap and
                // pushes the label below the item bounds. Collapse it.
                text.setPadding(text.getPaddingLeft(), 0, text.getPaddingRight(), 0);
                android.view.ViewGroup.LayoutParams lp = text.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    ((android.view.ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
                    ((android.view.ViewGroup.MarginLayoutParams) lp).setMarginEnd(0);
                    ((android.view.ViewGroup.MarginLayoutParams) lp).topMargin = 0;
                    ((android.view.ViewGroup.MarginLayoutParams) lp).bottomMargin = 0;
                }
                text.setLayoutParams(lp);
            }

            // Hide the list divider (grid items have no dividers).
            try {
                Object dividerObj = XposedHelpers.getObjectField(holder, "mDivider");
                if (dividerObj instanceof android.view.View) {
                    ((android.view.View) dividerObj).setVisibility(android.view.View.GONE);
                }
            } catch (Throwable ignored) {}

            // Hide the badge row (it would occupy a full bottom line when vertical).
            try {
                Object badgeObj = XposedHelpers.getObjectField(holder, "mBadge");
                if (badgeObj instanceof android.view.View) {
                    ((android.view.View) badgeObj).setVisibility(android.view.View.GONE);
                }
            } catch (Throwable ignored) {}

            // Hide the tab-count overlay (first item "return" has a multi-window count FrameLayout
            // that otherwise shifts it down versus the other items).
            try {
                Object tabCountObj = XposedHelpers.getObjectField(holder, "mTabCountView");
                if (tabCountObj instanceof android.view.View) {
                    ((android.view.View) tabCountObj).setVisibility(android.view.View.GONE);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] restyleGridItem error: " + t);
        }
    }

    /** Re-hide divider/tab-count/badge after Samsung's onBindViewHolder re-shows them. */
    private void hideGridDecorations(Object holder) {
        try {
            Object dividerObj = XposedHelpers.getObjectField(holder, "mDivider");
            if (dividerObj instanceof android.view.View) {
                ((android.view.View) dividerObj).setVisibility(android.view.View.GONE);
            }
            Object badgeObj = XposedHelpers.getObjectField(holder, "mBadge");
            if (badgeObj instanceof android.view.View) {
                ((android.view.View) badgeObj).setVisibility(android.view.View.GONE);
            }
            Object tabCountObj = XposedHelpers.getObjectField(holder, "mTabCountView");
            if (tabCountObj instanceof android.view.View) {
                ((android.view.View) tabCountObj).setVisibility(android.view.View.GONE);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hideGridDecorations error: " + t);
        }
    }

    /**
     * Manual page snapping for the horizontal grid. Samsung stripped PagerSnapHelper from its
     * repackaged androidx, so we hook RecyclerView.setScrollState: when it becomes IDLE (0),
     * snap scrollX to the nearest page boundary (page = RecyclerView width = 5 columns).
     */
    private static volatile boolean sGridPagerHooked = false;
    private static volatile int sGridPage = 0; // current page index (cached)
    private static volatile int sPageCount = 1; // total pages (for indicator dots)
    private static volatile android.widget.LinearLayout sPageIndicator = null;
    private static volatile android.view.View sGridRecycler = null;
    private static volatile boolean sDotsGeomLogged = false;
    private static volatile float sDownX = 0f;
    private static volatile boolean sFlingFired = false;
    private static volatile boolean sDragMoved = false; // true once a real drag (not a tap) moved
    private static volatile long sLastScrollAnim = 0L;
    private static final float sPageSwipeThreshold = 80f; // px to count as a page turn

    /**
     * Cache the handler/adapter refs (via MenuReorderHelper) needed for long-press reorder.
     * We fetch the flattened item lists from the handler fields here so the drag gesture has
     * a stable ordered list to mutate.
     */
    @SuppressWarnings("unchecked")
    private void cacheGridRefs(Object handler, Object recycler, Object adapter) {
        try {
            sGridPage = 0; // menu re-opened: reset pager to first page
            // NOTE: do NOT reset sPageIndicator here — cacheGridRefs fires far more often
            // than menu-open (self-heal timer), and resetting it orphaned the dots so the
            // highlight never updated. The indicator re-attaches lazily in
            // ensureIndicator(recycler) instead.
            sPageCount = 1;
            java.util.List<android.view.MenuItem> primary =
                    (java.util.List<android.view.MenuItem>) XposedHelpers.getObjectField(handler, "mPrimaryMenuItems");
            java.util.List<android.view.MenuItem> secondary =
                    (java.util.List<android.view.MenuItem>) XposedHelpers.getObjectField(handler, "mSecondaryMenuItems");
            java.util.List<android.view.MenuItem> removed = null;
            try {
                removed = (java.util.List<android.view.MenuItem>) XposedHelpers.getObjectField(handler, "mToolbarRemovedMenuItems");
            } catch (Throwable ignored) {}
            MenuReorderHelper.cacheRefs(handler, recycler, adapter, primary, secondary, removed);
            XposedBridge.log("[SBPlus] cached grid refs: primary=" + (primary != null ? primary.size() : -1)
                    + " secondary=" + (secondary != null ? secondary.size() : -1)
                    + " removed=" + (removed != null ? removed.size() : -1));
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] cacheGridRefs error: " + t);
        }
    }

    /**
     * Pure-grid (Via-style) chrome strip: hide the dialog header (page title + share button +
     * divider) and the bottom nav bar (back/forward/bookmark/refresh), leaving only the icon
     * grid. IDs resolved lazily so we never hard-code numeric values.
     */
    private void hideMenuChrome(android.view.View recycler) {
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) recycler.getParent();
            if (parent == null) return;
            android.content.Context c = recycler.getContext();
            int headerId = c.getResources().getIdentifier("header_container", "id", c.getPackageName());
            if (headerId != 0) {
                android.view.View header = parent.findViewById(headerId);
                if (header != null) header.setVisibility(android.view.View.GONE);
            }
            XposedBridge.log("[SBPlus] menu header hidden (header=" + headerId + ")");

            // Diagnostic: log the real heights to find the dead strip above the grid.
            recycler.post(new Runnable() {
                @Override public void run() {
                    try {
                        int[] lp = new int[2];
                        recycler.getLocationInWindow(lp);
                        XposedBridge.log("[SBPlus] gridGeom recyclerH=" + recycler.getHeight()
                                + " recyclerTopInWindow=" + lp[1]
                                + " parentH=" + (parent != null ? parent.getHeight() : -1));
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] gridGeom err " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hideMenuChrome error: " + t);
        }
    }

    /** Keep the live grid reference and page count; dots are drawn by drawPageDots via onDrawOver. */
    private void attachPageIndicator(android.view.View recycler) {
        sGridRecycler = recycler;
        try {
            Object adapter = XposedHelpers.callMethod(recycler, "getAdapter");
            if (adapter != null) {
                int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
                sPageCount = (count + 9) / 10;
            }
        } catch (Throwable ignore) {}
    }

    private void ensureIndicator(android.view.View recycler) {
        sGridRecycler = recycler;
    }

    /** Public hook for MenuReorderHelper to refresh dots after add/remove changes count. */
    public static void refreshIndicatorIfNeeded() {
        try {
            if (sGridRecycler != null) {
                try {
                    Object adapter = XposedHelpers.callMethod(sGridRecycler, "getAdapter");
                    if (adapter != null) {
                        int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
                        int pages = Math.max(1, (count + 9) / 10);
                        sPageCount = pages;
                        if (sGridPage > pages - 1) sGridPage = pages - 1;
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refreshIndicatorIfNeeded error: " + t);
        }
    }

    private void attachGridPager(Object recycler, ClassLoader cl) {
        if (!(recycler instanceof android.view.View)) return;
        if (sGridPagerHooked) return;
        try {
            Class<?> rvCls = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", cl);

            // (A) Intercept fling -> page one page in the swipe direction.
            XposedHelpers.findAndHookMethod(rvCls, "fling", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object rv = param.thisObject;
                            if (!(rv instanceof android.view.View)) return;
                            if (!isGridMenu(rv)) return;
                            int velocityX = (Integer) param.args[0];
                            XposedBridge.log("[SBPlus] FLING velocityX=" + velocityX + " moved=" + sDragMoved);
                            if (velocityX == 0) return;
                            // A tap (no real drag) must not page. Only a real swipe (sDragMoved)
                            // or a strong fling velocity counts as a page turn.
                            if (!sDragMoved && Math.abs(velocityX) < 6000) {
                                XposedBridge.log("[SBPlus] FLING ignored (looks like a tap, vx=" + velocityX + ")");
                                return;
                            }
                            sFlingFired = true;
                            pageByVelocity((android.view.View) rv, velocityX);
                            param.setResult(true); // consume fling -> no continuous scroll
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid fling hook error: " + t);
                        }
                    }
                });

            // (B) Snap to nearest page on slow-drag release (IDLE).
            XposedHelpers.findAndHookMethod(rvCls, "setScrollState", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object rv = param.thisObject;
                            if (!(rv instanceof android.view.View)) return;
                            if (!isGridMenu(rv)) return;
                            int state = (Integer) param.args[0];
                            if (state != 0) return; // SCROLL_STATE_IDLE
                            snapGridToPage((android.view.View) rv);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid snap hook error: " + t);
                        }
                    }
                });

            // (C) Track touch DOWN/UP for slow-drag paging (no fling, manual drag release).
            XposedHelpers.findAndHookMethod(rvCls, "onTouchEvent",
                    XposedHelpers.findClass("android.view.MotionEvent", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isGridMenuEnabled()) return;
                            Object rv = param.thisObject;
                            if (!(rv instanceof android.view.View)) return;
                            if (!isGridMenu(rv)) return;
                            android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                            int action = ev.getActionMasked();
                            if (action == android.view.MotionEvent.ACTION_DOWN) {
                                sDownX = ev.getRawX();
                                sFlingFired = false;
                                sDragMoved = false;
                            } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                                if (Math.abs(ev.getRawX() - sDownX) > sPageSwipeThreshold) {
                                    sDragMoved = true;
                                }
                            } else if (action == android.view.MotionEvent.ACTION_UP
                                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                                final float dx = ev.getRawX() - sDownX;
                                final android.view.View rvv = (android.view.View) rv;
                                // Defer: let a possible fling (fast swipe) fire first; if none
                                // fires, treat this as a manual slow-drag page turn.
                                rvv.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        if (sFlingFired) { XposedBridge.log("[SBPlus] touch-up skipped (fling took it)"); sFlingFired = false; return; }
                                        XposedBridge.log("[SBPlus] touch-up dx=" + dx + " thr=" + sPageSwipeThreshold + " moved=" + sDragMoved);
                                        if (Math.abs(dx) > sPageSwipeThreshold) {
                                            XposedBridge.log("[SBPlus] touch-up drag dx=" + dx + " -> page turn");
                                            pageByVelocity(rvv, dx > 0 ? 1 : -1);
                                        }
                                    }
                                }, 50);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] grid touch hook error: " + t);
                        }
                    }
                });

            // (D) Draw the page-indicator dots directly on the RecyclerView canvas via the
            //     ItemDecoration.onDrawOver hook. This bypasses the LinearLayout weight problem
            //     (RecyclerView weight=1 squeezes any below-dots to zero) — the dots are painted
            //     on top of the grid every frame the RecyclerView draws, so they show immediately
            //     on open and follow page changes without any layout/attach timing dependency.
            try {
                Class<?> decoCls = XposedHelpers.findClass("androidx.recyclerview.widget.E0", cl);
                XposedHelpers.findAndHookMethod(decoCls, "onDrawOver",
                        android.graphics.Canvas.class, rvCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!isGridMenuEnabled()) return;
                                Object rv = param.args[1];
                                if (!(rv instanceof android.view.View)) return;
                                if (!isGridMenu(rv)) return;
                                android.graphics.Canvas canvas = (android.graphics.Canvas) param.args[0];
                                drawPageDots(canvas, (android.view.View) rv);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] onDrawOver dots error: " + t);
                            }
                        }
                    });
                XposedBridge.log("[SBPlus] E0.onDrawOver dots hook installed");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] E0.onDrawOver hook failed: " + t);
            }

            sGridPagerHooked = true;
            sGridRecycler = (android.view.View) recycler;
            XposedBridge.log("[SBPlus] grid pager hooked (fling paging + idle snap)");
            attachPageIndicator((android.view.View) recycler);
            // Diagnostic: report vertical positions of the first items (row layout).
            final android.view.ViewGroup rvdiag = (android.view.ViewGroup) recycler;
            rvdiag.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        int n = rvdiag.getChildCount();
                        StringBuilder sb = new StringBuilder("[SBPlus] rowDiag rvH=" + rvdiag.getHeight());
                        for (int i = 0; i < Math.min(n, 6); i++) {
                            android.view.View c = rvdiag.getChildAt(i);
                            sb.append(" | c" + i + " top=" + c.getTop() + " h=" + c.getHeight());
                        }
                        XposedBridge.log(sb.toString());
                    } catch (Throwable ignore) {}
                }
            }, 300);

            // Diagnostic: measure the real column width from adjacent children.
            final android.view.ViewGroup rvv = (android.view.ViewGroup) recycler;
            rvv.post(new Runnable() {
                @Override public void run() {
                    try {
                        int n = rvv.getChildCount();
                        if (n >= 2) {
                            android.view.View c0 = rvv.getChildAt(0);
                            android.view.View c1 = rvv.getChildAt(1);
                            XposedBridge.log("[SBPlus] colMeasure c0l=" + c0.getLeft()
                                    + " c1l=" + c1.getLeft() + " colW=" + (c1.getLeft() - c0.getLeft())
                                    + " c0w=" + c0.getWidth() + " rvW=" + rvv.getWidth());
                        }
                    } catch (Throwable ignore) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] attachGridPager error: " + t);
        }
    }

    /** True if this RecyclerView uses a GridLayoutManager (the grid menu). */
    private boolean isGridMenu(Object rv) {
        try {
            Object lm = XposedHelpers.callMethod(rv, "getLayoutManager");
            return lm != null && lm.getClass().getName().contains("GridLayoutManager");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Paint the page-indicator dots at the bottom of the grid RecyclerView. */
    private void drawPageDots(android.graphics.Canvas canvas, android.view.View rv) {
        try {
            Object adapter = XposedHelpers.callMethod(rv, "getAdapter");
            int count = 0;
            if (adapter != null) count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
            int pages = (count <= 0) ? sPageCount : (count + 9) / 10;
            if (pages <= 1) return;
            int W = rv.getWidth();
            int H = rv.getHeight();
            if (W <= 0 || H <= 0) return;
            float d = rv.getResources().getDisplayMetrics().density;
            int dotR = (int) (2.5f * d);
            int gap = (int) (11f * d);
            float baseY = H - (int) (30f * d);
            if (!sDotsGeomLogged) {
                sDotsGeomLogged = true;
                int[] lp = new int[2];
                rv.getLocationInWindow(lp);
                XposedBridge.log("[SBPlus] drawDots rvW=" + W + " rvH=" + H + " rvTopInWindow=" + lp[1]
                        + " baseY=" + baseY + " pages=" + pages);
            }
            float totalW = pages * (dotR * 2) + (pages - 1) * gap;
            float startX = (W - totalW) / 2f;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.FILL);
            for (int i = 0; i < pages; i++) {
                float cx = startX + i * (dotR * 2 + gap) + dotR;
                p.setColor(i == sGridPage ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawCircle(cx, baseY, dotR, p);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] drawPageDots error: " + t);
        }
    }

    /** Page one full page in the direction of velocityX (strict 5-col pages, all items
     *  sequential; any leftover items simply form a short last page, empty cells left blank). */
    private int getCurrentPage(android.view.View rv) {
        try {
            Object lm = XposedHelpers.callMethod(rv, "getLayoutManager");
            if (lm == null) return 0;
            int first = (Integer) XposedHelpers.callMethod(lm, "findFirstVisibleItemPosition");
            if (first <= 0) return 0;
            return first / 10;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void pageByVelocity(android.view.View rv, int velocityX) {
        try {
            int pageW = rv.getWidth();
            if (pageW <= 0) return;
            Object adapter = XposedHelpers.callMethod(rv, "getAdapter");
            if (adapter == null) return;
            int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
            if (count <= 0) return;
            int pages = (count + 9) / 10;               // 5 cols x 2 rows per page
            int fromPage = getCurrentPage(rv);
            if (fromPage < 0) fromPage = 0;
            if (fromPage > pages - 1) fromPage = pages - 1;
            sGridPage = fromPage;
            int target;
            if (velocityX > 0) {
                // circular: last page -> first page
                target = (fromPage + 1) % pages;
            } else {
                target = (fromPage - 1 + pages) % pages;
            }
            if (target == fromPage) return;
            sGridPage = target;
            sPageCount = pages;
            XposedBridge.log("[SBPlus] pageByVelocity page -> " + target
                    + " (count=" + count + " pages=" + pages + " vx=" + velocityX + " fromPage=" + sGridPage + ")");
            ensureIndicator(rv);
            scrollToPage(rv, target, pageW, count);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] pageByVelocity error: " + t);
        }
    }

    /** Snap to the current cached page (re-align after smooth scroll settles). */
    private void snapGridToPage(android.view.View rv) {
        try {
            // A plain tap (no drag/fling) must NOT snap — otherwise tapping an icon
            // while scrollX sits near a page boundary jumps to another page.
            if (!sDragMoved && !sFlingFired) return;
            sDragMoved = false;
            sFlingFired = false;
            // If a smooth scroll animation just started, skip: the trailing IDLE event
            // would restart the animation and cause the "page bounces back" bug.
            if (android.os.SystemClock.uptimeMillis() - sLastScrollAnim < 400) return;
            int pageW = rv.getWidth();
            if (pageW <= 0) return;
            Object adapter = XposedHelpers.callMethod(rv, "getAdapter");
            if (adapter == null) return;
            int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
            if (count <= 0) return;
            int pages = (count + 9) / 10;
            int target = getCurrentPage(rv);
            if (target < 0) target = 0;
            if (target > pages - 1) target = pages - 1;
            sGridPage = target;
            XposedBridge.log("[SBPlus] snapGridToPage align -> page " + target + " (pages=" + pages + ")");
            ensureIndicator(rv);
            scrollToPage(rv, target, pageW, count);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] snapGridToPage error: " + t);
        }
    }

    /** Animate the pager to the target page with a decelerating (bouncy) scroll. */
    private void scrollToPage(android.view.View rv, int page, int pageW, int count) {
        try {
            sLastScrollAnim = android.os.SystemClock.uptimeMillis();
            Object lm = XposedHelpers.callMethod(rv, "getLayoutManager");
            if (lm == null) return;
            int targetPos = page * 10;
            XposedBridge.log("[SBPlus] scrollToPage " + page + " smoothTo pos=" + targetPos);
            // Smooth-scroll to the first item of the target page (reliable with
            // GridLayoutManager; direct scrollBy/getScrollX didn't move the view).
            try {
                XposedHelpers.callMethod(rv, "smoothScrollToPosition", targetPos);
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(lm, "scrollToPositionWithOffset", targetPos, 0);
                } catch (Throwable t2) {
                    XposedHelpers.callMethod(lm, "scrollToPosition", targetPos);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] scrollToPage error: " + t);
        }
    }

    /**
     * PRIMARY hook point: DownloadManagerService.onPreDownloadRequest(TerraceDownloadInfo, long).
     *
     * This instance method is the Samsung Browser download handler's entry, invoked
     * BEFORE the browser shows its own download confirmation dialog. Hooking here lets
     * us dispatch to the third-party downloader and block the native download, so the
     * browser's own download dialog never appears.
     *
     * Signature (verified):
     *   com.sec.android.app.sbrowser.download.DownloadManagerService
     *     .onPreDownloadRequest(Lcom/sec/terrace/browser/download/TerraceDownloadInfo;J)V
     */
    private void hookPreDownloadRequestService(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.download.DownloadManagerService", cl);
            Class<?> infoCls = XposedHelpers.findClass(
                    "com.sec.terrace.browser.download.TerraceDownloadInfo", cl);

            XposedHelpers.findAndHookMethod(svc, "onPreDownloadRequest", infoCls, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.args[0];
                                long guid = (Long) param.args[1];
                                DownloadMeta meta = extractMeta(info);
                                XposedBridge.log("[SBPlus] onPreDownloadRequest(service) captured: " + meta);
                                boolean dispatched = dispatchToDownloader(meta);
                                if (dispatched && blockNativeDownload()) {
                                    // Mimic Samsung's own Knox-download-block path: call
                                    // ignoreDownload(guid, info) to (1) close the blank tab
                                    // and (2) tell the native side the download was rejected,
                                    // so it does NOT fall back to navigating the URL.
                                    try {
                                        XposedHelpers.callMethod(param.thisObject,
                                                "ignoreDownload", guid, info);
                                    } catch (Throwable ig) {
                                        XposedBridge.log("[SBPlus] ignoreDownload failed: " + ig);
                                    }
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] native download blocked (pre-download)");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] onPreDownloadRequest(service) hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] DownloadManagerService.onPreDownloadRequest hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] DownloadManagerService hook failed: " + t);
        }
    }

    /**
     * Preferred hook: onDownloadStarted(TerraceDownloadInfo).
     * The arg is a fully-assembled download info object with all fields.
     */
    private void hookOnDownloadStarted(ClassLoader cl) {
        try {
            Class<?> tinCtrl = XposedHelpers.findClass(
                    "com.sec.terrace.browser.download.TinDownloadController", cl);
            Class<?> infoCls = XposedHelpers.findClass(
                    "com.sec.terrace.browser.download.TerraceDownloadInfo", cl);

            XposedHelpers.findAndHookMethod(tinCtrl, "onDownloadStarted", infoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.args[0];
                                DownloadMeta meta = extractMeta(info);
                                XposedBridge.log("[SBPlus] onDownloadStarted captured: " + meta);
                                LogWriter.log("bridge", "onDownloadStarted " + meta);
                                boolean dispatched = dispatchToDownloader(meta);
                                // Block the native download only when we actually
                                // handed it off to the third-party downloader.
                                if (dispatched && blockNativeDownload()) {
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] native download blocked");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] onDownloadStarted hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] onDownloadStarted hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] onDownloadStarted hook failed: " + t);
        }
    }

    /**
     * Reflectively extract download metadata from TerraceDownloadInfo.
     */
    private DownloadMeta extractMeta(Object info) {
        DownloadMeta meta = new DownloadMeta();
        try {
            meta.url = safeStr(XposedHelpers.callMethod(info, "getUrl"));
            if (meta.url == null || meta.url.isEmpty()) {
                meta.url = safeStr(XposedHelpers.callMethod(info, "getOriginalUrl"));
            }
            meta.cookie = safeStr(XposedHelpers.callMethod(info, "getCookie"));
            meta.userAgent = safeStr(XposedHelpers.callMethod(info, "getUserAgent"));
            meta.fileName = safeStr(XposedHelpers.callMethod(info, "getFileName"));
            meta.referrer = safeStr(XposedHelpers.callMethod(info, "getReferrer"));
            meta.mimeType = safeStr(XposedHelpers.callMethod(info, "getMimeType"));
            meta.contentDisposition = safeStr(
                    XposedHelpers.callMethod(info, "getContentDisposition"));
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] extractMeta error: " + t);
        }
        return meta;
    }

    /**
     * Build and dispatch an Intent to the target downloader (using app Context).
     *
     * @return true if the download was successfully dispatched to the third-party
     *         downloader (caller may then block the native download); false if the
     *         dispatch failed and the native download should be left as fallback.
     */
    private boolean dispatchToDownloader(DownloadMeta meta) {
        try {
            if (!isBridgeEnabled()) {
                XposedBridge.log("[SBPlus] bridge disabled, skip dispatch");
                LogWriter.log("bridge", "bridge disabled, skip dispatch");
                return false;
            }
            if (meta == null || meta.url == null || meta.url.isEmpty()) {
                XposedBridge.log("[SBPlus] no valid url, skip");
                return false;
            }

            String pkg = resolveDownloaderPackage();
            String cls = resolveDownloaderClass();

            Intent intent = new Intent(Intent.ACTION_VIEW);
            // Use setData (no explicit type) so the downloader's http/https + */* filter
            // always matches regardless of the source mime type.
            intent.setData(Uri.parse(meta.url));

            if (cls != null && !cls.isEmpty()) {
                // User-specified component, or default (activity-alias resolved below).
                intent.setClassName(pkg, cls);
            } else if (DEFAULT_ADM_PACKAGE.equals(pkg)) {
                // Default ADM: pin the alias directly to avoid ResolverActivity.
                intent.setClassName(pkg, DEFAULT_ADM_CLASS);
            } else {
                // Other downloaders: restrict to the package, let the system resolve.
                intent.setPackage(pkg);
            }

            // ADM (com.dv.adm) reads these exact extra keys (reverse-engineered from
            // com.dv.get.AEditor.r3(Intent)): Cookie / User-Agent / Referer / Authorization.
            if (meta.cookie != null)    intent.putExtra("Cookie", meta.cookie);
            if (meta.userAgent != null) intent.putExtra("User-Agent", meta.userAgent);
            if (meta.referrer != null)  intent.putExtra("Referer", meta.referrer);
            // Authorization is not currently extracted; leave a hook for future.
            // if (meta.authorization != null) intent.putExtra("Authorization", meta.authorization);

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            if (sAppContext != null) {
                sAppContext.startActivity(intent);
                XposedBridge.log("[SBPlus] dispatched to: " + pkg + " url=" + meta.url);
                LogWriter.log("bridge", "dispatched to " + pkg + " url=" + meta.url);
                return true;
            } else {
                XposedBridge.log("[SBPlus] no app Context yet, skip dispatch");
                LogWriter.log("bridge", "no app Context yet, skip dispatch");
                return false;
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] dispatchToDownloader error: " + t);
            return false;
        }
    }

    /**
     * Whether to block the native Samsung Browser download after a successful
     * third-party dispatch. Default true; when disabled, both run in parallel.
     */
    private boolean blockNativeDownload() {
        try {
            return prefs.getBoolean(KEY_BLOCK_NATIVE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private String safeStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Download metadata container. */
    static class DownloadMeta {
        String url;
        String cookie;
        String userAgent;
        String fileName;
        String referrer;
        String mimeType;
        String contentDisposition;

        @Override
        public String toString() {
            return "url=" + url + " fileName=" + fileName + " mime=" + mimeType
                    + " cookie=" + (cookie == null ? "null" : "***(" + cookie.length() + " chars)");
        }
    }
}

