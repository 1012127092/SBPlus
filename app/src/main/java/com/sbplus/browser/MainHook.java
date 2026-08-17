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
    private static volatile android.app.Activity sCurrentActivity;

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
    private static final String KEY_ENABLE_RANDOM_UA = "enable_random_ua";
    private static final String ARG_PAGE = "sbplus_page";
    private static final String PAGE_DOWNLOADER_PICKER = "downloader_picker";
    private static final String PAGE_REGION_PICKER = "region_picker";
    private static final String PAGE_UA_PICKER = "ua_picker";
    private static final String PAGE_CLEAN_SETTINGS_PICKER = "clean_settings_picker";
    private static final String PAGE_VIDEO_BG_PICKER = "video_bg_picker";
    private static final String PAGE_HOME_BEAUTIFY = "home_beautify";
    private static final String PAGE_USERSCRIPT_PICKER = "userscript_picker";
    private static final String PAGE_USERSCRIPT_DETAIL = "userscript_detail";
    private static final String PAGE_USERSCRIPT_LIST = "userscript_list";
    private static final String ARG_USCRIPT_FILE = "sbplus_userscript_file";
    private static final String KEY_ENABLE_CLEAN_SETTINGS = "enable_clean_settings";
    private static final String KEY_HIDDEN_SETTINGS = "hidden_settings";
    private static final String KEY_ENABLE_BLOCK_UPDATE = "enable_block_update";
    private static final String KEY_ENABLE_VIDEO_BG = "enable_video_bg";
    private static final String KEY_VIDEO_BG_PATH = "video_bg_path";
    // 模块自身版本号（编译期确定，连 app/build.gradle 的 versionName）。
    // 浏览器进程无法加载 BuildConfig，这里作为 prefs 缺失时的兜底。
    private static final String APP_VERSION = "2.1";
    private static final String KEY_ENABLE_HOME_CLEAR_TEXT = "enable_home_clear_text";
    private static final String KEY_ENABLE_HOME_MOVE_BTN = "enable_home_move_btn";
    private static final String KEY_ENABLE_USERSCRIPT = "enable_userscript";
    private static final String KEY_DISABLED_USERSCRIPTS = "disabled_userscripts";
    private static final int REQUEST_USERSCRIPT_PICK = 61002;
    private static final String KEY_USERSCRIPT_SOURCES = "userscript_sources";

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

    // 随机浏览器标识：每次启动随机刷新的 UA 池（覆盖手机/电脑 × 多系统 × 多浏览器）。
    // Android 9~17、iOS 15.0~18.2、Windows/macOS/Linux；浏览器含 Chrome/Firefox/Edge/Safari/Opera/Vivaldi/Brave/UC 等。
    private static final String[] RANDOM_UAS = new String[]{
            // —— Android 手机 ——
            "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 9; SM-A505F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; Pixel 4a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 17; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            // Android 手机 其它浏览器
            "Mozilla/5.0 (Android 13; Mobile; rv:122.0) Gecko/122.0 Firefox/122.0",
            "Mozilla/5.0 (Android 11; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36 EdgA/122.0.2365.80",
            "Mozilla/5.0 (Linux; Android 12; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.88 Mobile Safari/537.36 OPR/50.0.2254.149155",
            "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36 Vivaldi/6.6.3271.48",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36 Brave/1.63.169",
            "Mozilla/5.0 (Linux; U; Android 12; zh-CN; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/15.0.0.1234 Mobile Safari/537.36",
            // —— iPhone / iPad ——
            "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 15_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0.6099.119 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/130.0 Mobile/15E148 Safari/605.1.15",
            "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            // —— 桌面 Windows ——
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.2277.98",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.2365.80",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 OPR/85.0.4341.75",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Vivaldi/6.6.3271.48",
            "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            // —— 桌面 macOS ——
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
            // —— 桌面 Linux ——
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 OPR/86.0.4363.32",
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

    // 油猴脚本注入去重：realTab -> 已注入的 URL（避免 onLoadFinished 重复触发重复注入）。
    private static final java.util.WeakHashMap<Object, String> sInjectedUrls =
            new java.util.WeakHashMap<>();

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
    private static volatile String sCurrentPickerPage; // 精确记录当前子页，用于返回逻辑（详情/列表往返）
    // 油猴脚本菜单：追踪当前页面注入的脚本（url -> 已注入的脚本名列表）和当前 tab 对象。
    private static final java.util.Map<String, java.util.List<String>> sActiveScriptsByUrl = new java.util.HashMap<String, java.util.List<String>>();
    private static volatile Object sCurrentRealTab;
    private static volatile String sCurrentUrl;
    private static final java.util.Map<String, String> requireCache = new java.util.HashMap<String, String>(); // @require 库缓存：url -> js 内容
    private static final java.util.Map<String, String> resourceCache = new java.util.HashMap<String, String>(); // @resource 资源缓存：name -> 内容
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
        hookUserscriptToolbar(lpparam.classLoader);
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
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
                                String cls = "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom";
                                if (PAGE_VIDEO_BG_PICKER.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_HOME_BEAUTIFY);
                                    sCurrentPickerPage = PAGE_HOME_BEAUTIFY;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back: video bg picker -> home beautify");
                                } else if (PAGE_HOME_BEAUTIFY.equals(sCurrentPickerPage)) {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back: home beautify -> main menu");
                                } else if (PAGE_USERSCRIPT_DETAIL.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_USERSCRIPT_LIST);
                                    sCurrentPickerPage = PAGE_USERSCRIPT_LIST;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back: detail -> list");
                                } else if (PAGE_USERSCRIPT_LIST.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_USERSCRIPT_PICKER);
                                    sCurrentPickerPage = PAGE_USERSCRIPT_PICKER;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back: list -> picker");
                                } else if (PAGE_USERSCRIPT_PICKER.equals(sCurrentPickerPage)) {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back: picker -> home");
                                } else {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(null);
                                    XposedBridge.log("[SBPlus] back from picker");
                                }
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
                                String cls = "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom";
                                if (PAGE_VIDEO_BG_PICKER.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_HOME_BEAUTIFY);
                                    sCurrentPickerPage = PAGE_HOME_BEAUTIFY;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up: video bg picker -> home beautify");
                                } else if (PAGE_HOME_BEAUTIFY.equals(sCurrentPickerPage)) {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up: home beautify -> main menu");
                                } else if (PAGE_USERSCRIPT_DETAIL.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_USERSCRIPT_LIST);
                                    sCurrentPickerPage = PAGE_USERSCRIPT_LIST;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up: detail -> list");
                                } else if (PAGE_USERSCRIPT_LIST.equals(sCurrentPickerPage)) {
                                    android.os.Bundle la = new android.os.Bundle();
                                    la.putString(ARG_PAGE, PAGE_USERSCRIPT_PICKER);
                                    sCurrentPickerPage = PAGE_USERSCRIPT_PICKER;
                                    navigateToFragment(act, cls, la);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up: list -> picker");
                                } else if (PAGE_USERSCRIPT_PICKER.equals(sCurrentPickerPage)) {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up: picker -> home");
                                } else {
                                    sCurrentPickerPage = null;
                                    sInPickerPage = false;
                                    navigateToFragment(act, cls, null);
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log("[SBPlus] up-navigate from picker");
                                }
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
                if ("sbplus_ua_random".equals(key)) {
                    checked = isRandomUaEnabled();
                } else if ("sbplus_ua_custom".equals(key)) {
                    String cur = userAgent();
                    checked = !isRandomUaEnabled() && true;
                    for (String[] e : PRESET_UAS) {
                        if (e[1].equals(cur)) { checked = false; break; }
                    }
                } else {
                    checked = !isRandomUaEnabled() && userAgent().equals(key.substring("sbplus_ua_".length()));
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

            // 离开地区页时复位 sRegionPageActive，避免返回后在其它页面误触发滚动补偿。
            XposedHelpers.findAndHookMethod(prefFrag, "onDestroyView",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.os.Bundle args = (android.os.Bundle)
                                        XposedHelpers.callMethod(param.thisObject, "getArguments");
                                if (args != null && PAGE_REGION_PICKER.equals(args.getString(ARG_PAGE))) {
                                    sRegionPageActive = false;
                                    XposedBridge.log("[SBPlus] region page left, scroll compensation off");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] region onDestroyView reset error: " + t);
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
            if (PAGE_REGION_PICKER.equals(page)) {
                sRegionPageActive = true;
            }
            // 长列表/选择器子页：统一底部加 padding，避免最后一项被底部栏遮挡。
            // （除 region_picker 外，它走 collapseAppBar 顶部折叠方案，不叠加 bottom padding）
            if (isBottomPadPage(page)) {
                applyListBottomPadding(frag);
                return;
            }
            if (!PAGE_REGION_PICKER.equals(page)) return;

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

    /** 需要底部 padding 的长列表/选择器子页（region_picker 走 collapseAppBar，不在此列）。 */
    private boolean isBottomPadPage(String page) {
        if (page == null) return false;
        return PAGE_DOWNLOADER_PICKER.equals(page)
                || PAGE_UA_PICKER.equals(page)
                || PAGE_CLEAN_SETTINGS_PICKER.equals(page)
                || PAGE_VIDEO_BG_PICKER.equals(page)
                || PAGE_HOME_BEAUTIFY.equals(page)
                || PAGE_USERSCRIPT_PICKER.equals(page)
                || PAGE_USERSCRIPT_DETAIL.equals(page)
                || PAGE_USERSCRIPT_LIST.equals(page);
    }

    /** 给列表页的 RecyclerView 底部加 padding，确保最后一项能完整滚出（不被底部栏遮挡）。 */
    private void applyListBottomPadding(Object frag) {
        try {
            Object rvObj = XposedHelpers.callMethod(frag, "getListView");
            if (!(rvObj instanceof android.view.View)) return;
            final android.view.View rv = (android.view.View) rvObj;
            rv.post(new Runnable() {
                @Override public void run() {
                    try {
                        int bottomPad = dp(rv.getContext(), 96);
                        int left = rv.getPaddingLeft();
                        int top = rv.getPaddingTop();
                        int right = rv.getPaddingRight();
                        rv.setPadding(left, top, right, rv.getPaddingBottom() + bottomPad);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
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
        } else if (PAGE_HOME_BEAUTIFY.equals(page)) {
            injectHomeBeautify(ctx, cl, screen);
        } else if (PAGE_USERSCRIPT_PICKER.equals(page)) {
            injectUserscriptPicker(ctx, cl, screen);
        } else if (PAGE_USERSCRIPT_DETAIL.equals(page)) {
            String usFile = null;
            try {
                android.os.Bundle usArgs = (android.os.Bundle) XposedHelpers.callMethod(frag, "getArguments");
                if (usArgs != null) usFile = usArgs.getString(ARG_USCRIPT_FILE);
            } catch (Throwable ignored) {}
            injectUserscriptDetailPicker(ctx, cl, screen, usFile);
        } else if (PAGE_USERSCRIPT_LIST.equals(page)) {
            injectUserscriptListPicker(ctx, cl, screen);
        } else {
            // 顺序按使用频率与功能相近聚类排列。
            Object userscriptPref = buildUserscriptSwitch(ctx, cl);
            boolean addedUserscript = (Boolean) XposedHelpers.callMethod(screen, "addPreference", userscriptPref);
            XposedBridge.log("[SBPlus] userscript item injected: " + addedUserscript);

            Object pref = buildExternalDownloaderSwitch(ctx, cl);
            boolean added = (Boolean) XposedHelpers.callMethod(screen, "addPreference", pref);
            XposedBridge.log("[SBPlus] submenu item injected: " + added);

            Object gridPref = buildGridMenuSwitch(ctx, cl);
            boolean addedGrid = (Boolean) XposedHelpers.callMethod(screen, "addPreference", gridPref);
            XposedBridge.log("[SBPlus] grid menu item injected: " + addedGrid);

            // —— 主页美化入口 ——
            try {
                Class<?> homePrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object homePref = XposedHelpers.newInstance(homePrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(homePref, "setTitle", "主页美化");
                XposedHelpers.callMethod(homePref, "setKey", "sbplus_home_beautify");
                XposedHelpers.callMethod(homePref, "setSummary", "视频背景 / 搜索框文字 / 添加快捷方式按钮");
                try {
                    Class<?> listenerType = listenerParamType(homePref.getClass(), "setOnPreferenceClickListener");
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
                                                navigateToHomeBeautify((android.app.Activity) actObj);
                                            }
                                            return Boolean.TRUE;
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[SBPlus] home beautify navigate error: " + t);
                                    }
                                    return Boolean.FALSE;
                                }
                            });
                    XposedHelpers.callMethod(homePref, "setOnPreferenceClickListener", onPreferenceClick);
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] home beautify click bind failed: " + t);
                }
                XposedHelpers.callMethod(screen, "addPreference", homePref);
                XposedBridge.log("[SBPlus] home beautify entry injected");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] home beautify entry error: " + t);
            }

            Object uaPref = buildUaSwitch(ctx, cl);
            boolean addedUa = (Boolean) XposedHelpers.callMethod(screen, "addPreference", uaPref);
            XposedBridge.log("[SBPlus] ua override item injected: " + addedUa);

            Object regionPref = buildRegionLockSwitch(ctx, cl);
            boolean addedRegion = (Boolean) XposedHelpers.callMethod(screen, "addPreference", regionPref);
            XposedBridge.log("[SBPlus] region lock item injected: " + addedRegion);

            Object cleanPref = buildCleanSettingsSwitch(ctx, cl);
            boolean addedClean = (Boolean) XposedHelpers.callMethod(screen, "addPreference", cleanPref);
            XposedBridge.log("[SBPlus] clean settings item injected: " + addedClean);

            Object blockUpdatePref = buildBlockUpdateSwitch(ctx, cl);
            boolean addedBlockUpdate = (Boolean) XposedHelpers.callMethod(screen, "addPreference", blockUpdatePref);
            XposedBridge.log("[SBPlus] block update item injected: " + addedBlockUpdate);

            // —— 书签管理入口 ——
            final Context bmFinalCtx = ctx;
            try {
                Class<?> bmPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object bmPref = XposedHelpers.newInstance(bmPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(bmPref, "setTitle", "书签管理");
                XposedHelpers.callMethod(bmPref, "setKey", "sbplus_bookmark_manager");
                XposedHelpers.callMethod(bmPref, "setSummary", "导入 / 导出书签（Chrome/Edge/Firefox 通用）");
                bindPreferenceClick(bmPref, cl, new Runnable() { public void run() { showBookmarkManagerDialog(bmFinalCtx); } });
                XposedHelpers.callMethod(screen, "addPreference", bmPref);
                XposedBridge.log("[SBPlus] bookmark manager item injected");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] bookmark manager inject error: " + t);
            }

            // —— 版本号（自动探测更新）——
            final Context verFinalCtx = ctx;
            try {
                Class<?> verPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                final Object verPref = XposedHelpers.newInstance(verPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(verPref, "setTitle", "版本号");
                XposedHelpers.callMethod(verPref, "setKey", "sbplus_version");
                String localVer = readModuleVersion();
                XposedHelpers.callMethod(verPref, "setSummary", "当前 " + localVer + "（自动检测更新中…）");
                bindPreferenceClick(verPref, cl, new Runnable() { public void run() { checkUpdateInteractive(verFinalCtx); } });
                XposedHelpers.callMethod(screen, "addPreference", verPref);
                // 后台自动检测最新版本，有新版本则在 summary 提示
                final String localVerF = localVer;
                new Thread(new Runnable() { public void run() {
                    try {
                        String remote = checkLatestVersionOnline();
                        if (remote != null && versionNewer(remote, localVerF)) {
                            String disp = stripV(remote);
                            final String msg = "当前 " + localVerF + "，有新版 " + disp + "，点击更新";
                            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                            main.post(new Runnable() { public void run() {
                                try { XposedHelpers.callMethod(verPref, "setSummary", msg); } catch (Throwable ignored) {}
                            }});
                        } else {
                            final String msg = "当前 " + localVerF + "（已是最新）";
                            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                            main.post(new Runnable() { public void run() {
                                try { XposedHelpers.callMethod(verPref, "setSummary", msg); } catch (Throwable ignored) {}
                            }});
                        }
                    } catch (Throwable ignored) {}
                }}).start();
                XposedBridge.log("[SBPlus] version item injected (auto-check enabled)");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] version item inject error: " + t);
            }

            // —— 项目地址 ——
            final Context projFinalCtx = ctx;
            try {
                Class<?> projPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object projPref = XposedHelpers.newInstance(projPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(projPref, "setTitle", "项目地址");
                XposedHelpers.callMethod(projPref, "setKey", "sbplus_project_url");
                XposedHelpers.callMethod(projPref, "setSummary", "github.com/1012127092/SBPlus");
                bindPreferenceClick(projPref, cl, new Runnable() { public void run() { openProjectPage(projFinalCtx); } });
                XposedHelpers.callMethod(screen, "addPreference", projPref);
                XposedBridge.log("[SBPlus] project url item injected");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] project url item inject error: " + t);
            }
        }
    }

    /** 从模块 prefs 读版本号（MainActivity 写入），读不到则用编译期常量 APP_VERSION。 */
    private String readModuleVersion() {
        try {
            XSharedPreferences xp = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            xp.makeWorldReadable();
            String v = xp.getString("version_name", null);
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) {}
        return APP_VERSION;
    }

    /** 用浏览器打开项目主页。 */
    private void openProjectPage(Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/1012127092/SBPlus"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] openProjectPage error: " + t);
        }
    }

    /** 查询 GitHub 最新 release 的 tag（如 v2.1），失败返回 null。 */
    private String checkLatestVersionOnline() {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.github.com/repos/1012127092/SBPlus/releases/latest").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "SBPlus");
            int code = c.getResponseCode();
            if (code != 200) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            String tag = o.optString("tag_name", "");
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** 手动检测更新：后台查 GitHub 最新 release，有更新弹确认框。 */
    private void checkUpdateInteractive(final Context ctx) {
        final String local = readModuleVersion();
        new Thread(new Runnable() {
            @Override public void run() {
                String tag = null, body = null, apkUrl = null, error = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("https://api.github.com/repos/1012127092/SBPlus/releases/latest").openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "SBPlus");
                    int code = c.getResponseCode();
                    if (code == 200) {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        r.close();
                        org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                        tag = o.optString("tag_name", "");
                        body = o.optString("body", "");
                        org.json.JSONArray assets = o.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                String u = assets.getJSONObject(i).optString("browser_download_url", null);
                                if (u != null && u.endsWith(".apk")) { apkUrl = u; break; }
                            }
                        }
                    } else {
                        error = "HTTP " + code;
                    }
                    c.disconnect();
                } catch (Throwable e) {
                    error = e.getMessage();
                }
                final String fTag = tag, fBody = body, fApk = apkUrl, fErr = error;
                final String fLocal = local;
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(new Runnable() { @Override public void run() {
                    if (fErr != null) {
                        android.widget.Toast.makeText(ctx, "检测失败：" + fErr, android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean newer = versionNewer(fTag, fLocal);
                    if (newer) {
                        showUpdateDialog(ctx, fTag, fBody, fApk);
                    } else {
                        android.widget.Toast.makeText(ctx, "已是最新版本（" + fTag + "）", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }});
            }
        }).start();
    }

    /** 弹更新确认框，确认后浏览器打开下载地址。 */
    private void showUpdateDialog(final Context ctx, String tag, String body, final String apkUrl) {
        try {
            String note = body;
            if (note == null || note.trim().isEmpty()) note = "（无更新说明）";
            if (note.length() > 500) note = note.substring(0, 500) + "…";
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle("发现新版本：" + tag);
            b.setMessage("当前版本：" + readModuleVersion() + "\n\n" + note);
            b.setPositiveButton("下载更新", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String url = (apkUrl != null && !apkUrl.isEmpty())
                            ? apkUrl : "https://github.com/1012127092/SBPlus";
                    try {
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url));
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] open update download error: " + t);
                    }
                }
            });
            b.setNegativeButton("取消", null);
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUpdateDialog error: " + t);
        }
    }

    /** 剥离 tag 前导的 'v'，用于展示（如 v2.0 -> 2.0）。 */
    private String stripV(String s) {
        if (s == null) return "";
        String r = s.trim();
        if (r.toLowerCase().startsWith("v")) r = r.substring(1);
        return r;
    }

    /** 比较版本号 remote > local。 */
    private boolean versionNewer(String remote, String local) {
        String r = (remote == null ? "" : remote).trim();
        String l = (local == null ? "" : local).trim();
        if (r.isEmpty()) return false;
        if (l.isEmpty()) return true;
        if (r.toLowerCase().startsWith("v")) r = r.substring(1);
        if (l.toLowerCase().startsWith("v")) l = l.substring(1);
        try {
            String[] rp = r.split("\\.");
            String[] lp = l.split("\\.");
            int n = Math.max(rp.length, lp.length);
            for (int i = 0; i < n; i++) {
                int rv = i < rp.length ? Integer.parseInt(rp[i].trim()) : 0;
                int lv = i < lp.length ? Integer.parseInt(lp[i].trim()) : 0;
                if (rv != lv) return rv > lv;
            }
            return false;
        } catch (NumberFormatException e) {
            return !r.equals(l);
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

    /** 弹窗内多行文本的最大可用宽度（px）：屏幕宽度的 85%。 */
    private int screenPopupMaxWidth(Context ctx) {
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        return (int) (screenW * 0.85f);
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

    /** 随机浏览器标识：每次启动随机刷新 UA。 */
    private boolean isRandomUaEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_RANDOM_UA, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveRandomUaEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_RANDOM_UA, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save random UA enabled error: " + t);
        }
    }

    /** 随机挑选一个 UA（每次启动调用一次，模拟“每次启动随机刷新”）。 */
    private String randomUa() {
        try {
            int idx = new java.util.Random().nextInt(RANDOM_UAS.length);
            return RANDOM_UAS[idx];
        } catch (Throwable t) {
            return null;
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

    /** 主页美化：去除主页搜索框内文字。 */
    private boolean isHomeClearTextEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_HOME_CLEAR_TEXT, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveHomeClearTextEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_HOME_CLEAR_TEXT, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save home clear text error: " + t);
        }
    }

    /** 主页美化：移动“添加快捷方式”按钮到主页设置旁。 */
    private boolean isHomeMoveBtnEnabled() {
        try {
            if (sAppContext != null) {
                return processPrefs(sAppContext).getBoolean(KEY_ENABLE_HOME_MOVE_BTN, false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveHomeMoveBtnEnabled(boolean enabled) {
        try {
            if (sAppContext != null) {
                processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_HOME_MOVE_BTN, enabled).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save home move btn error: " + t);
        }
    }

    private void navigateToHomeBeautify(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_HOME_BEAUTIFY);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            sCurrentPickerPage = PAGE_HOME_BEAUTIFY;
            XposedBridge.log("[SBPlus] navigated to home beautify");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToHomeBeautify error: " + t);
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
            sCurrentPickerPage = PAGE_VIDEO_BG_PICKER;
            XposedBridge.log("[SBPlus] navigated to video bg picker (back -> home beautify)");
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

    /** 主页美化子页：视频背景 / 去搜索框文字 / 移动添加快捷方式按钮。 */
    private void injectHomeBeautify(Context ctx, ClassLoader cl, Object screen) {
        Object videoBgPref = buildVideoBgSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", videoBgPref);

        Object clearTextPref = buildHomeClearTextSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", clearTextPref);

        Object moveBtnPref = buildHomeMoveBtnSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", moveBtnPref);

        XposedBridge.log("[SBPlus] home beautify page injected");
    }

    /** 开关：去除主页搜索框内文字（“搜索或输入网址”）。 */
    private Object buildHomeClearTextSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", "去除搜索框内文字");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_home_clear_text");
        XposedHelpers.callMethod(pref, "setSummary", "隐藏主页搜索框里的“搜索或输入网址”提示文字");
        XposedHelpers.callMethod(pref, "setChecked", isHomeClearTextEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);

        Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
        Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                boolean enabled = args[1] instanceof Boolean && (Boolean) args[1];
                                saveHomeClearTextEnabled(enabled);
                                XposedBridge.log("[SBPlus] home clear text toggled: " + enabled);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] home clear text listener error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        return pref;
    }

    /** 开关：移动“添加快捷方式”按钮到主页设置旁。 */
    private Object buildHomeMoveBtnSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", "移动添加快捷方式按钮");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_home_move_btn");
        XposedHelpers.callMethod(pref, "setSummary", "把“添加快捷方式”按钮移到主页设置左边并统一大小");
        XposedHelpers.callMethod(pref, "setChecked", isHomeMoveBtnEnabled());
        XposedHelpers.callMethod(pref, "setSelectable", true);

        Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
        Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                new Class[]{listenerType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        try {
                            if (m.getName().equals("onPreferenceChange")) {
                                boolean enabled = args[1] instanceof Boolean && (Boolean) args[1];
                                saveHomeMoveBtnEnabled(enabled);
                                XposedBridge.log("[SBPlus] home move btn toggled: " + enabled);
                                return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] home move btn listener error: " + t);
                        }
                        return Boolean.FALSE;
                    }
                });
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
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

    /** Fill the UA picker sub-page: 随机开关 + 3 presets + 1 custom, mirroring injectDownloaderPicker. */
    private void injectUaPicker(Context ctx, ClassLoader cl, Object screen) {
        final String current = userAgent();
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        // 随机浏览器标识（单选行，与下方 preset 互斥）
        Object randomRow = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(randomRow, "setTitle", "随机浏览器标识");
        XposedHelpers.callMethod(randomRow, "setKey", "sbplus_ua_random");
        XposedHelpers.callMethod(randomRow, "setSummary", "每次启动随机刷新 UA（覆盖多平台/多系统/多浏览器）");
        bindUaRandomClick(randomRow, cl, screen);
        XposedHelpers.callMethod(screen, "addPreference", randomRow);

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
                                    saveRandomUaEnabled(false);
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

    /** Bind click on the "随机浏览器标识" row: enable random mode + refresh dots. */
    private void bindUaRandomClick(Object pref, ClassLoader cl, final Object screen) {
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
                                    saveRandomUaEnabled(true);
                                    Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (ctxObj instanceof Context) {
                                        android.widget.Toast.makeText((Context) ctxObj,
                                                "已启用随机浏览器标识（重启后随机刷新）", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    refreshRadioDots("sbplus_ua_random");
                                    XposedBridge.log("[SBPlus] random UA selected");
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] random UA click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] random UA click bind failed: " + t);
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
                                    saveRandomUaEnabled(false);
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

    /** 手动导航到脚本详情子页（传脚本文件名）。 */
    private void navigateToUserscriptDetail(android.app.Activity act, String fileName) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_USERSCRIPT_DETAIL);
            args.putString(ARG_USCRIPT_FILE, fileName);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            sCurrentPickerPage = PAGE_USERSCRIPT_DETAIL;
            XposedBridge.log("[SBPlus] navigated to userscript detail: " + fileName);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToUserscriptDetail error: " + t);
        }
    }

    /** 手动导航到脚本列表子页。 */
    private void navigateToUserscriptList(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_USERSCRIPT_LIST);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            sCurrentPickerPage = PAGE_USERSCRIPT_LIST;
            XposedBridge.log("[SBPlus] navigated to userscript list");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToUserscriptList error: " + t);
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
                                    String ua = null;
                                    if (isRandomUaEnabled()) {
                                        ua = randomUa();
                                    } else {
                                        ua = userAgent();
                                    }
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
                                if (req == REQUEST_USERSCRIPT_PICK) {
                                    int res = (Integer) param.args[1];
                                    android.content.Intent data = (android.content.Intent) param.args[2];
                                    if (res != android.app.Activity.RESULT_OK || data == null
                                            || data.getData() == null) return;
                                    android.net.Uri uri = data.getData();
                                    String content = readUriText((android.content.Context) param.thisObject, uri);
                                    if (content != null && !content.isEmpty()) {
                                        String fn = saveUserscriptContent(content);
                                        if (fn != null) saveSource(fn, "本地导入");
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                fn == null ? "导入失败" : ("已导入脚本: " + fn),
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        XposedBridge.log("[SBPlus] userscript imported: " + fn);
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                "读取文件失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if (req == REQUEST_BOOKMARK_PICK) {
                                    int res = (Integer) param.args[1];
                                    android.content.Intent data = (android.content.Intent) param.args[2];
                                    if (res != android.app.Activity.RESULT_OK || data == null
                                            || data.getData() == null) return;
                                    android.net.Uri uri = data.getData();
                                    String content = readUriText((android.content.Context) param.thisObject, uri);
                                    if (content != null && !content.isEmpty()) {
                                        final BookmarkNode tree = parseBookmarkHtml(content);
                                        final android.app.Activity act = sCurrentActivity != null ? sCurrentActivity
                                                : (param.thisObject instanceof android.app.Activity
                                                        ? (android.app.Activity) param.thisObject : null);
                                        if (act != null) {
                                            showBookmarkTreeDialog(act, "选择要导入的书签", tree, false);
                                        } else {
                                            android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                    "无法获取界面环境", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                "读取文件失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
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

        // (3) 主页 UI 改造：移动"添加快捷方式"按钮 + 搜索框透明化。
        try {
            applyQuickAccessUiTweaks(cl);
            XposedBridge.log("[SBPlus] quickaccess ui tweaks applied");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] quickaccess ui tweaks failed: " + t);
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

    /** 主页 UI 改造：需求1——把"添加快捷方式"按钮移到"主页设置"左边并统一大小；需求2——"搜索或输入网址"横线透明化。 */
    private void applyQuickAccessUiTweaks(ClassLoader cl) {
        // ---- 需求2：搜索框（假地址栏）透明化 ----
        try {
            Class<?> dummyBar = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessDummyUrlBar", cl);
            XposedHelpers.findAndHookMethod(dummyBar, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                final android.view.View bar = (android.view.View) param.thisObject;
                                if (!isHomeClearTextEnabled()) return;
                                // 延后执行两步，防止 viewmodel/observer 重置样式
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(new Runnable() { @Override public void run() {
                                    applyDummyBarTransparent(bar);
                                }});
                                h.postDelayed(new Runnable() { @Override public void run() {
                                    applyDummyBarTransparent(bar);
                                }}, 700);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] dummy url bar tweak err: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessDummyUrlBar.onAttachedToWindow hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] dummy url bar hook failed: " + t);
        }

        // ---- 需求1：在"主页设置"按钮左边插入等大的"添加快捷方式"按钮，并隐藏网格里的原添加格子 ----
        try {
            Class<?> mainLayout = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessMainLayout", cl);
            XposedHelpers.findAndHookMethod(mainLayout, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final android.view.View root = (android.view.View) param.thisObject;
                            root.postDelayed(new Runnable() {
                                @Override public void run() {
                                    try {
                                        if (!isHomeMoveBtnEnabled()) return;
                                        rearrangeQuickAccessButtons(root);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[SBPlus] rearrange err: " + t);
                                    }
                                }
                            }, 400);
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessMainLayout.onFinishInflate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] main layout hook failed: " + t);
        }
    }

    /** 在主页根 View 上：把"添加快捷方式"按钮插到"主页设置"按钮左边，隐藏原网格添加格子。 */
    private void rearrangeQuickAccessButtons(android.view.View root) {
        int mgmtId = resId("general_management", "id");
        int addContainerId = resId("add_view_container", "id");
        // 原“添加”格子的图标（layer-list：圆底 + “+”号，自带 tint）：按深/浅色主题选择
        int addIconRes = resId("quickaccess_tap_to_add_drawable", "drawable");
        int addIconResDark = resId("quickaccess_tap_to_add_drawable_dark_mode", "drawable");
        boolean dark = isDarkTheme();
        int iconRes = dark ? addIconResDark : addIconRes;
        if (iconRes == 0) iconRes = dark ? addIconRes : addIconResDark;
        if (iconRes == 0) iconRes = resId("internet_ic_add", "drawable");
        if (iconRes == 0) iconRes = resId("internet_ic_qa_add", "drawable");

        android.view.View mgmt = root.findViewById(mgmtId);
        android.view.View addContainer = root.findViewById(addContainerId);

        // 隐藏网格里的原"添加"格子
        if (addContainer != null && addContainer.getVisibility() != android.view.View.GONE) {
            addContainer.setVisibility(android.view.View.GONE);
            XposedBridge.log("[SBPlus] add_view_container hidden");
        }

        if (mgmt == null) {
            XposedBridge.log("[SBPlus] general_management not found (mgmtId=" + mgmtId + ")");
            return;
        }
        android.view.ViewParent parent = mgmt.getParent();
        if (!(parent instanceof android.view.ViewGroup)) {
            XposedBridge.log("[SBPlus] mgmt parent not ViewGroup");
            return;
        }
        android.view.ViewGroup mgmtParent = (android.view.ViewGroup) parent;
        // mgmt 的父容器（通常 wrap_content 的 RelativeLayout）在 header 的 LinearLayout 里；
        // 若 mgmt 父是 RelativeLayout，则新按钮要插到它的父（LinearLayout）中、mgmt 父之前，
        // 否则 LEFT_OF 规则会把按钮压成 0 宽。
        android.view.ViewGroup insertTarget;
        int insertIndex;
        if (mgmtParent instanceof android.widget.RelativeLayout
                && mgmtParent.getParent() instanceof android.widget.LinearLayout) {
            insertTarget = (android.view.ViewGroup) mgmtParent.getParent();
            insertIndex = insertTarget.indexOfChild(mgmtParent);
        } else {
            insertTarget = mgmtParent;
            insertIndex = mgmtParent.indexOfChild(mgmt);
        }

        // 幂等
        if (mgmt.getTag() != null && "sbplus_add_btn_inserted".equals(mgmt.getTag())) return;
        mgmt.setTag("sbplus_add_btn_inserted");

        int size = mgmt.getLayoutParams() != null ? mgmt.getLayoutParams().width : -1;
        if (size <= 0) size = mgmt.getWidth();
        if (size <= 0) size = (int) (24 * sAppContext.getResources().getDisplayMetrics().density);

        // 新按钮：放在与 mgmt 同级的容器里（mgmt 通常在 RelativeLayout 内，插到它左边）。
        // 用 mgmt 的 context 创建（保留 Activity 主题，避免图标/ripple 无 tint），并复制其图标与尺寸。
        android.content.Context mgmtCtx = mgmt.getContext();
        android.widget.ImageButton addBtn = new android.widget.ImageButton(mgmtCtx);
        addBtn.setContentDescription("添加快捷方式");
        addBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        addBtn.setBackground(mgmt.getBackground());
        // 尺寸与主页设置按钮一致
        addBtn.setPadding(mgmt.getPaddingLeft(), mgmt.getPaddingTop(), mgmt.getPaddingRight(), mgmt.getPaddingBottom());
        if (iconRes != 0) {
            addBtn.setImageResource(iconRes);
        }
        addBtn.setFocusable(true);
        addBtn.setClickable(true);
        addBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                try { triggerAddShortcut(root); }
                catch (Throwable t) { XposedBridge.log("[SBPlus] add btn click err: " + t); }
            }
        });

        // 若插到 LinearLayout：给按钮设置与 mgmt 相同的尺寸，并垂直居中，右边距与“主页设置↔头像”间距一致
        if (insertTarget instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout ll = (android.widget.LinearLayout) insertTarget;
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            // 主页设置与头像之间是 account 的 marginStart(10dip)，新按钮与主页设置也用同样的右边距
            lp.setMarginEnd((int) (10 * sAppContext.getResources().getDisplayMetrics().density));
            addBtn.setLayoutParams(lp);
            ll.addView(addBtn, insertIndex);
        } else if (insertTarget instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout rl = (android.widget.RelativeLayout) insertTarget;
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(size, size);
            if (insertIndex == rl.indexOfChild(mgmt)) {
                // 直接在 mgmt 之前（左侧）
                lp.addRule(android.widget.RelativeLayout.LEFT_OF, mgmt.getId());
                lp.addRule(android.widget.RelativeLayout.ALIGN_TOP, mgmt.getId());
                lp.addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, mgmt.getId());
            }
            rl.addView(addBtn, lp);
        } else {
            addBtn.setLayoutParams(new android.view.ViewGroup.LayoutParams(size, size));
            insertTarget.addView(addBtn, insertIndex);
        }
        XposedBridge.log("[SBPlus] add shortcut button inserted before general_management");
    }

    private int resId(String name, String type) {
        try {
            return sAppContext.getResources().getIdentifier(name, type, sAppContext.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 保留搜索框背景与放大镜，只清空提示文字、去掉 elevation 阴影（保留框/图标/点击热区）。 */
    private void applyDummyBarTransparent(android.view.View bar) {
        if (bar == null) return;
        try {
            // 只清空提示文字（“搜索或输入网址”），完全保留搜索框的默认外观：
            // 背景框（mCardBlurView.foreground）、放大镜、外层柔和阴影（ShadowDrawHelper 8dp elevation）
            if (bar instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) bar;
                for (int i = 0; i < g.getChildCount(); i++) {
                    clearDummyTextOnly(g.getChildAt(i));
                }
            }
            XposedBridge.log("[SBPlus] dummy bar: only text cleared, everything else default");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] applyDummyBarTransparent err: " + t);
        }
    }

    private void clearDummyTextOnly(android.view.View v) {
        if (v == null) return;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clearDummyTextOnly(g.getChildAt(i));
            return;
        }
        if (v instanceof android.widget.TextView) {
            ((android.widget.TextView) v).setText("");
        }
    }

    /** 触发"添加快捷方式"：反射调用 QuickAccessIconRecyclerAdapter.showAddShortcutDialog()（真正入口）。 */
    private void triggerAddShortcut(android.view.View root) {
        try {
            Object adapter = findIconRecyclerAdapter(root);
            if (adapter != null) {
                java.lang.reflect.Method m = adapter.getClass().getDeclaredMethod("showAddShortcutDialog");
                m.setAccessible(true);
                m.invoke(adapter);
                XposedBridge.log("[SBPlus] showAddShortcutDialog invoked via reflection");
                return;
            }
            XposedBridge.log("[SBPlus] icon recycler adapter not found");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] triggerAddShortcut err: " + t);
        }
    }

    /** 在 root 视图树中查找 QuickAccessIconRecyclerAdapter（主页图标网格的适配器）。 */
    private Object findIconRecyclerAdapter(android.view.View root) {
        try {
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View c = g.getChildAt(i);
                    if (c instanceof android.view.ViewGroup) {
                        String cn = c.getClass().getName();
                        if (cn.contains("RecyclerView")) {
                            Object a = null;
                            try {
                                java.lang.reflect.Method gm = c.getClass().getMethod("getAdapter");
                                a = gm.invoke(c);
                            } catch (Throwable ignored) {}
                            if (a != null && a.getClass().getName().contains("QuickAccessIconRecyclerAdapter")) {
                                return a;
                            }
                        }
                        Object r = findIconRecyclerAdapter(c);
                        if (r != null) return r;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判断当前是否深色主题。 */
    private boolean isDarkTheme() {
        try {
            int mode = sAppContext.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
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

    /** 被禁用的脚本文件名集合（按 fileName 区分，不影响脚本文件本身）。 */
    private java.util.Set<String> disabledUserscripts() {
        java.util.Set<String> set = new java.util.HashSet<String>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_DISABLED_USERSCRIPTS, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String k : raw.split(",")) if (k != null && !k.isEmpty()) set.add(k);
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private void saveDisabledUserscripts(java.util.Set<String> set) {
        try {
            if (sAppContext != null) {
                StringBuilder sb = new StringBuilder();
                for (String k : set) { if (sb.length() > 0) sb.append(","); sb.append(k); }
                processPrefs(sAppContext).edit().putString(KEY_DISABLED_USERSCRIPTS, sb.toString()).commit();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save disabled userscripts error: " + t);
        }
    }

    /** 某个脚本文件是否启用（未在禁用列表即启用）。 */
    private boolean isUserscriptFileEnabled(String fileName) {
        return !disabledUserscripts().contains(fileName);
    }

    private void setUserscriptFileEnabled(String fileName, boolean enabled) {
        java.util.Set<String> set = disabledUserscripts();
        if (enabled) set.remove(fileName); else set.add(fileName);
        saveDisabledUserscripts(set);
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

        // 点击跳转到脚本管理子页。
        try {
            Class<?> clickListenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object clickListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{clickListenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    Object clicked = args[0];
                                    Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (actObj instanceof android.app.Activity) {
                                        android.os.Bundle a = new android.os.Bundle();
                                        a.putString(ARG_PAGE, PAGE_USERSCRIPT_PICKER);
                                        navigateToFragment((android.app.Activity) actObj,
                                                "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom", a);
                                        sInPickerPage = true;
                                        sCurrentPickerPage = PAGE_USERSCRIPT_PICKER;
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] userscript navigate error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", clickListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] userscript click bind failed: " + t);
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

    /** 油猴脚本子页：脚本列表（启用/删除）+ 添加/更新/下载操作。 */
    private void injectUserscriptPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);

        java.io.File dir = userscriptDir();
        java.util.List<UserscriptMeta> metas = loadUserscripts();

        // —— 操作区 ——
        Object addPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(addPref, "setTitle", "添加脚本");
        XposedHelpers.callMethod(addPref, "setKey", "sbplus_userscript_add");
        XposedHelpers.callMethod(addPref, "setSummary", "粘贴脚本内容");
        bindPreferenceClick(addPref, cl, new Runnable() { public void run() { launchAddUserscript(); } });
        XposedHelpers.callMethod(screen, "addPreference", addPref);

        Object importPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(importPref, "setTitle", "导入脚本");
        XposedHelpers.callMethod(importPref, "setKey", "sbplus_userscript_import");
        XposedHelpers.callMethod(importPref, "setSummary", "从本地选择 .user.js 文件导入");
        bindPreferenceClick(importPref, cl, new Runnable() { public void run() { launchUserscriptFilePicker(); } });
        XposedHelpers.callMethod(screen, "addPreference", importPref);

        Object updatePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(updatePref, "setTitle", "更新所有脚本");
        XposedHelpers.callMethod(updatePref, "setKey", "sbplus_userscript_update");
        XposedHelpers.callMethod(updatePref, "setSummary", "检测所有脚本的更新（需脚本声明 @updateURL/@downloadURL）");
        bindPreferenceClick(updatePref, cl, new Runnable() { public void run() { updateAllUserscripts(); } });
        XposedHelpers.callMethod(screen, "addPreference", updatePref);

        Object dlPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(dlPref, "setTitle", "下载脚本");
        XposedHelpers.callMethod(dlPref, "setKey", "sbplus_userscript_dl");
        XposedHelpers.callMethod(dlPref, "setSummary", "打开脚本源列表，选择网站后安装的 .user.js 会自动保存");
        bindPreferenceClick(dlPref, cl, new Runnable() { public void run() { openGreasyFork(); } });
        XposedHelpers.callMethod(screen, "addPreference", dlPref);

        // 目录路径展示行。
        Object dirPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(dirPref, "setTitle", "脚本目录");
        XposedHelpers.callMethod(dirPref, "setKey", "sbplus_userscript_dir");
        XposedHelpers.callMethod(dirPref, "setSummary", dir == null ? "目录未初始化" : dir.getAbsolutePath());
        XposedHelpers.callMethod(screen, "addPreference", dirPref);

                // —— 脚本列表入口 ——
        Object listPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(listPref, "setTitle", "脚本列表 (" + metas.size() + ")");
        XposedHelpers.callMethod(listPref, "setKey", "sbplus_userscript_list");
        XposedHelpers.callMethod(listPref, "setSummary", "点击管理已安装的脚本");
        bindPreferenceClick(listPref, cl, new Runnable() {
            public void run() {
                android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
                if (act != null) navigateToUserscriptList(act);
            }
        });
        XposedHelpers.callMethod(screen, "addPreference", listPref);

        XposedBridge.log("[SBPlus] userscript picker injected, scripts=" + metas.size());
    }

    /** 脚本列表子页：列出所有已安装脚本，每行点击进入详情。 */
    private void injectUserscriptListPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        java.util.List<UserscriptMeta> metas = loadUserscripts();

        if (metas.isEmpty()) {
            Object emptyPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(emptyPref, "setTitle", "暂无脚本");
            XposedHelpers.callMethod(emptyPref, "setKey", "sbplus_userscript_list_empty");
            XposedHelpers.callMethod(emptyPref, "setSummary", "返回后点「添加脚本」或「下载脚本」");
            XposedHelpers.callMethod(screen, "addPreference", emptyPref);
        } else {
            for (int i = 0; i < metas.size(); i++) {
                final UserscriptMeta meta = metas.get(i);
                Object row = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
                String enabledTag = isUserscriptFileEnabled(meta.fileName) ? "" : " [已停用]";
                XposedHelpers.callMethod(row, "setTitle", meta.name + (meta.version.isEmpty() ? "" : "  v" + meta.version) + enabledTag);
                XposedHelpers.callMethod(row, "setKey", "sbplus_userscript_row_" + i);
                XposedHelpers.callMethod(row, "setSummary", buildUserscriptSummary(meta));
                bindPreferenceClick(row, cl, new Runnable() {
                    public void run() {
                        android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
                        if (act != null) navigateToUserscriptDetail(act, meta.fileName);
                    }
                });
                XposedHelpers.callMethod(screen, "addPreference", row);
            }
        }

        XposedBridge.log("[SBPlus] userscript list injected, scripts=" + metas.size());
    }

    /** 脚本详情子页：启用开关 + 编辑 + 配置页 + 匹配规则 + 删除。 */
    private void injectUserscriptDetailPicker(Context ctx, ClassLoader cl, Object screen, String fileName) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);

        UserscriptMeta target = null;
        java.util.List<UserscriptMeta> metas = loadUserscripts();
        for (UserscriptMeta m : metas) {
            if (m.fileName != null && m.fileName.equals(fileName)) { target = m; break; }
        }
        if (target == null) {
            Object emptyPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(emptyPref, "setTitle", "脚本不存在");
            XposedHelpers.callMethod(emptyPref, "setKey", "sbplus_userscript_detail_empty");
            XposedHelpers.callMethod(emptyPref, "setSummary", "文件可能已被删除");
            XposedHelpers.callMethod(screen, "addPreference", emptyPref);
            return;
        }

        final UserscriptMeta meta = target;

        // 标题行（名字 + 版本 + 作者/描述）。
        Object titlePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(titlePref, "setTitle", meta.name);
        XposedHelpers.callMethod(titlePref, "setKey", "sbplus_userscript_detail_title");
        StringBuilder tsum = new StringBuilder();
        if (!meta.version.isEmpty()) tsum.append("版本 ").append(meta.version);
        if (!meta.description.isEmpty()) { if (tsum.length() > 0) tsum.append(" · "); tsum.append(meta.description); }
        XposedHelpers.callMethod(titlePref, "setSummary", tsum.toString());
        XposedHelpers.callMethod(screen, "addPreference", titlePref);

        // 启用开关。
        Object enPref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(enPref, "setTitle", "启用脚本");
        XposedHelpers.callMethod(enPref, "setKey", "sbplus_userscript_detail_enable");
        XposedHelpers.callMethod(enPref, "setSummary", "关闭后脚本不会注入页面");
        XposedHelpers.callMethod(enPref, "setChecked", isUserscriptFileEnabled(meta.fileName));
        bindUserscriptEnableChange(enPref, cl, meta.fileName);
        XposedHelpers.callMethod(screen, "addPreference", enPref);

        // 编辑。
        Object editPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(editPref, "setTitle", "编辑源码");
        XposedHelpers.callMethod(editPref, "setKey", "sbplus_userscript_detail_edit");
        XposedHelpers.callMethod(editPref, "setSummary", "修改后保存覆盖原文件");
        bindPreferenceClick(editPref, cl, new Runnable() { public void run() { editUserscript(meta.fileName); } });
        XposedHelpers.callMethod(screen, "addPreference", editPref);

        // 导出。
        Object exportPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(exportPref, "setTitle", "导出脚本");
        XposedHelpers.callMethod(exportPref, "setKey", "sbplus_userscript_detail_export");
        XposedHelpers.callMethod(exportPref, "setSummary", "复制到 Download/SBPlus/ 目录");
        bindPreferenceClick(exportPref, cl, new Runnable() { public void run() { exportUserscript(meta.fileName, meta.name); } });
        XposedHelpers.callMethod(screen, "addPreference", exportPref);

        // 来源显示（真实记录 > @downloadURL > @homepageURL > 本地导入）。
        String recordedSource = getSource(meta.fileName);
        String srcText = recordedSource;
        String srcUrl = null;
        if ((srcText == null || srcText.isEmpty()) && meta.downloadURL != null && !meta.downloadURL.isEmpty()) {
            srcText = meta.downloadURL;
            srcUrl = meta.downloadURL;
        } else if ((srcText == null || srcText.isEmpty()) && meta.homepageURL != null && !meta.homepageURL.isEmpty()) {
            srcText = meta.homepageURL;
            srcUrl = meta.homepageURL;
        } else if (srcText == null || srcText.isEmpty()) {
            srcText = "本地导入";
        }
        if (srcUrl == null && srcText != null && (srcText.startsWith("http://") || srcText.startsWith("https://"))) {
            srcUrl = srcText;
        }
        final String srcUrlFinal = srcUrl;
        {
            Object homePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(homePref, "setTitle", "来源");
            XposedHelpers.callMethod(homePref, "setKey", "sbplus_userscript_detail_home");
            XposedHelpers.callMethod(homePref, "setSummary", srcText);
            if (srcUrlFinal != null && !srcUrlFinal.isEmpty()) {
                bindPreferenceClick(homePref, cl, new Runnable() { public void run() { openUrl(srcUrlFinal); } });
            }
            XposedHelpers.callMethod(screen, "addPreference", homePref);
        }

        // 匹配规则展示。
        Object matchPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(matchPref, "setTitle", "匹配规则");
        XposedHelpers.callMethod(matchPref, "setKey", "sbplus_userscript_detail_match");
        StringBuilder ms = new StringBuilder();
        for (String s : meta.match) ms.append("match: ").append(s).append(" · ");
        for (String s : meta.include) ms.append("include: ").append(s).append(" · ");
        if (ms.length() == 0) ms.append("无匹配规则");
        XposedHelpers.callMethod(matchPref, "setSummary", ms.toString());
        XposedHelpers.callMethod(screen, "addPreference", matchPref);

        // 删除。
        Object delPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(delPref, "setTitle", "删除脚本");
        XposedHelpers.callMethod(delPref, "setKey", "sbplus_userscript_detail_del");
        XposedHelpers.callMethod(delPref, "setSummary", "从目录删除此脚本文件");
        bindPreferenceClick(delPref, cl, new Runnable() { public void run() { deleteUserscript(meta.fileName, meta.name); } });
        XposedHelpers.callMethod(screen, "addPreference", delPref);

        XposedBridge.log("[SBPlus] userscript detail injected: " + meta.name);
    }

    /** 通用点击绑定：点击后执行 runnable。 */
    private void bindPreferenceClick(Object pref, ClassLoader cl, final Runnable action) {
        try {
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceClickListener");
            Object onPreferenceClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceClick")) {
                                    // 从被点击的 preference 捕获当前 Activity。
                                    try {
                                        Object ctx = XposedHelpers.callMethod(args[0], "getContext");
                                        if (ctx instanceof android.app.Activity) sCurrentActivity = (android.app.Activity) ctx;
                                    } catch (Throwable ignored) {}
                                    action.run();
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] preference click error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] bindPreferenceClick failed: " + t);
        }
    }

    /** 绑定脚本启用开关。 */
    private void bindUserscriptEnableChange(Object pref, ClassLoader cl, final String fileName) {
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
                                    setUserscriptFileEnabled(fileName, enabled);
                                    XposedBridge.log("[SBPlus] userscript " + fileName + " enabled=" + enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] userscript enable error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] bindUserscriptEnableChange failed: " + t);
        }
    }

    /** 删除脚本文件。 */
    private void deleteUserscript(String fileName, String name) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null) return;
            if (fileName != null && !fileName.isEmpty()) {
                java.io.File f = new java.io.File(dir, fileName);
                if (f.exists()) f.delete();
            }
            // 同时从禁用列表移除。
            java.util.Set<String> set = disabledUserscripts();
            if (fileName != null) set.remove(fileName);
            saveDisabledUserscripts(set);
            toastOnMain("已删除脚本: " + name);
            // 刷新当前子页。
            refreshCurrentUserscriptPicker();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] deleteUserscript error: " + t);
        }
    }

    /** 导出单个脚本到公共下载目录 Download/SBPlus/。 */
    private void exportUserscript(String fileName, String name) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null || fileName == null || fileName.isEmpty()) {
                toastOnMain("导出失败：脚本目录未初始化");
                return;
            }
            java.io.File src = new java.io.File(dir, fileName);
            if (!src.exists()) {
                toastOnMain("导出失败：源文件不存在");
                return;
            }
            java.io.File outDir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
            if (!outDir.exists()) outDir.mkdirs();
            String safeName = (name == null || name.trim().isEmpty()) ? fileName : sanitizeFileName(name);
            if (!safeName.endsWith(".user.js")) safeName = safeName + ".user.js";
            java.io.File dst = new java.io.File(outDir, safeName);
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            toastOnMain("已导出到:\n" + dst.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] exportUserscript error: " + t);
            toastOnMain("导出失败: " + t.getMessage());
        }
    }

    /** 构建脚本摘要（描述 + 匹配规则数）。 */
    private String buildUserscriptSummary(UserscriptMeta meta) {
        int rules = meta.match.size() + meta.include.size();
        StringBuilder sum = new StringBuilder();
        if (!meta.description.isEmpty()) {
            sum.append(meta.description);
            sum.append("  ·  ");
        }
        sum.append("匹配规则 ").append(rules).append(" 条");
        return sum.toString();
    }

    /** 编辑已有脚本：加载其内容到编辑器，保存时覆盖原文件。 */
    private void editUserscript(final String fileName) {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            java.io.File dir = userscriptDir();
            if (dir == null) { toastOnMain("脚本目录未初始化"); return; }
            java.io.File f = new java.io.File(dir, fileName);
            String content;
            try {
                content = readFileText(f);
            } catch (Throwable e) {
                content = "";
            }
            showUserscriptEditorDialog(act, fileName, content);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] editUserscript error: " + t);
        }
    }

    /** 打开脚本源选择对话框。 */
    private void openGreasyFork() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            showSourcePickerDialog(act);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] openGreasyFork error: " + t);
        }
    }

    /** 脚本源结构。 */
    private static class ScriptSource {
        String name;
        String url;
        ScriptSource(String n, String u) { name = n; url = u; }
    }

    /** 预置脚本源。 */
    private ScriptSource[] defaultSources() {
        return new ScriptSource[]{
                new ScriptSource("ScriptCat", "https://scriptcat.org/zh-CN/search"),
                new ScriptSource("Userscript.Zone", "https://www.userscript.zone/"),
                new ScriptSource("GreasyFork", "https://greasyfork.org/zh-CN/scripts")
        };
    }

    /** 读取自定义源（格式：每行 name|url）。 */
    private java.util.List<ScriptSource> customSources() {
        java.util.List<ScriptSource> list = new java.util.ArrayList<ScriptSource>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_USERSCRIPT_SOURCES, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String line : raw.split("\n")) {
                        if (line == null || line.trim().isEmpty()) continue;
                        int bar = line.indexOf('|');
                        if (bar < 0) continue;
                        String n = line.substring(0, bar).trim();
                        String u = line.substring(bar + 1).trim();
                        if (!n.isEmpty() && !u.isEmpty()) list.add(new ScriptSource(n, u));
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] customSources error: " + t);
        }
        return list;
    }

    private void saveCustomSources(java.util.List<ScriptSource> list) {
        try {
            if (sAppContext == null) return;
            StringBuilder sb = new StringBuilder();
            for (ScriptSource src : list) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(src.name).append("|").append(src.url);
            }
            processPrefs(sAppContext).edit().putString(KEY_USERSCRIPT_SOURCES, sb.toString()).commit();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveCustomSources error: " + t);
        }
    }

    /** 合并源列表：自定义在前，预置后。 */
    private java.util.List<ScriptSource> allSources() {
        java.util.List<ScriptSource> list = customSources();
        for (ScriptSource s : defaultSources()) list.add(s);
        return list;
    }

    /** 弹出脚本源选择对话框。 */
    private void showSourcePickerDialog(final android.app.Activity act) {
        try {
            final java.util.List<ScriptSource> sources = allSources();
            final String[] names = new String[sources.size() + 1];
            for (int i = 0; i < sources.size(); i++) names[i] = sources.get(i).name;
            names[sources.size()] = "＋ 添加网址";
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle("选择脚本源");
            b.setItems(names, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    if (which == sources.size()) {
                        showAddSourceDialog(act);
                    } else {
                        openUrl(sources.get(which).url);
                    }
                }
            });
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showSourcePickerDialog error: " + t);
        }
    }

    /** 弹出添加网址对话框（名字 + 网址）。 */
    private void showAddSourceDialog(final android.app.Activity act) {
        try {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(act);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setPadding(48, 24, 48, 8);
            final android.widget.EditText nameEt = new android.widget.EditText(act);
            nameEt.setHint("名称（如：我的源）");
            final android.widget.EditText urlEt = new android.widget.EditText(act);
            urlEt.setHint("网址（如：https://example.com/）");
            urlEt.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            ll.addView(nameEt);
            ll.addView(urlEt);
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle("添加脚本源");
            b.setView(ll);
            b.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = nameEt.getText().toString().trim();
                    String u = urlEt.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) { toastOnMain("名称和网址不能为空"); return; }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    java.util.List<ScriptSource> list = customSources();
                    list.add(new ScriptSource(n, u));
                    saveCustomSources(list);
                    toastOnMain("已添加脚本源: " + n);
                }
            });
            b.setNegativeButton("取消", null);
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showAddSourceDialog error: " + t);
        }
    }

    /** 用浏览器打开网址。 */
    private void openUrl(String url) {
        try {
            if (sAppContext == null || url == null || url.isEmpty()) return;
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            sAppContext.startActivity(i);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] openUrl error: " + t);
        }
    }


    /** 启动添加脚本（单一输入窗口，预置模板 + 保存前检测）。 */
    private void launchAddUserscript() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            showUserscriptEditorDialog(act);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] launchAddUserscript error: " + t);
        }
    }

    /** 脚本编辑对话框：全屏、内容可滚动，预置模板，点保存先校验再写入。 */
    private void showUserscriptEditorDialog(final android.app.Activity act) {
        showUserscriptEditorDialog(act, null, USERSCRIPT_TEMPLATE);
    }

    /** 核心编辑器：新增（fileName=null）或编辑已有脚本（fileName=原文件名）。 */
    private void showUserscriptEditorDialog(final android.app.Activity act, final String fileName, final String initialContent) {
        try {
            final android.widget.EditText et = new android.widget.EditText(act);
            et.setText(initialContent == null ? USERSCRIPT_TEMPLATE : initialContent);
            et.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
            et.setHorizontallyScrolling(true);
            et.setVerticalScrollBarEnabled(true);
            et.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
            et.setTextSize(14);
            et.setTypeface(android.graphics.Typeface.MONOSPACE);
            et.setPadding(24, 24, 24, 24);
            et.setBackgroundColor(0xFF1E1E1E);
            et.setTextColor(0xFFDDDDDD);
            et.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);

            android.widget.LinearLayout ll = new android.widget.LinearLayout(act);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);

            // 顶部标题栏。
            android.widget.TextView tv = new android.widget.TextView(act);
            tv.setText(fileName == null ? "编写脚本" : "编辑脚本");
            tv.setTextSize(18);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, 24, 0, 24);
            tv.setTextColor(0xFF000000);
            ll.addView(tv, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            // 编辑区占满剩余空间。
            ll.addView(et, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            // 底部按钮栏。
            android.widget.LinearLayout btns = new android.widget.LinearLayout(act);
            btns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            btns.setPadding(16, 16, 16, 16);

            android.widget.Button cancelBtn = new android.widget.Button(act);
            cancelBtn.setText("取消");
            cancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { /* dialog 由下方引用关闭 */ }
            });

            android.widget.Button saveBtn = new android.widget.Button(act);
            saveBtn.setText("保存");
            saveBtn.setTextColor(0xFF000000);

            android.widget.LinearLayout.LayoutParams bp = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            btns.addView(cancelBtn, bp);
            btns.addView(saveBtn, bp);
            ll.addView(btns, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            final android.app.Dialog dialog = new android.app.Dialog(act);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            dialog.setContentView(ll);
            android.view.Window win = dialog.getWindow();
            if (win != null) {
                win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }

            cancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { dialog.dismiss(); }
            });
            saveBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    String content = et.getText().toString();
                    String err = validateUserscript(content);
                    if (err != null) {
                        toastOnMain("脚本无效，未保存：" + err);
                        return;
                    }
                    String fn;
                    if (fileName != null && !fileName.isEmpty()) {
                        fn = overwriteUserscriptContent(fileName, content);
                        saveSource(fileName, getSource(fileName) != null ? getSource(fileName) : "手动添加");
                    } else {
                        fn = saveUserscriptContent(content);
                        if (fn != null) saveSource(fn, "手动添加");
                    }
                    toastOnMain(fn == null ? "保存失败" : ("已保存: " + fn));
                    refreshCurrentUserscriptPicker();
                    dialog.dismiss();
                }
            });

            dialog.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUserscriptEditorDialog error: " + t);
        }
    }

    /** 脚本模板（预置 ==UserScript== 头）。 */
    private static final String USERSCRIPT_TEMPLATE =
        "// ==UserScript==\n" +
        "// @name        我的脚本\n" +
        "// @namespace   https://example.com/\n" +
        "// @version     1.0\n" +
        "// @description 在这里写描述\n" +
        "// @match       *://*/*\n" +
        "// @run-at      document-end\n" +
        "// ==/UserScript==\n" +
        "\n" +
        "(function() {\n" +
        "    'use strict';\n" +
        "\n" +
        "    // 在这里写你的脚本代码\n" +
        "\n" +
        "})();\n";

    /** 校验脚本：返回 null 表示合法，否则返回错误信息。 */
    private String validateUserscript(String content) {
        if (content == null || content.trim().isEmpty()) return "内容为空";
        // 必须以 ==UserScript== 开头（允许前导空白/注释）。
        if (!content.contains("==UserScript==")) return "缺少 ==UserScript== 声明头";
        if (!content.contains("==/UserScript==")) return "缺少 ==/UserScript== 结束标记";
        UserscriptMeta meta = UserscriptMeta.parse(content);
        if (meta == null || meta.name.isEmpty()) return "缺少 @name";
        // 必须有至少一条匹配规则，否则邮箱般全站注入风险太高，强制要求。
        if (meta.match.isEmpty() && meta.include.isEmpty()) return "缺少 @match 或 @include 匹配规则";
        // 至少有可执行代码（metadata 之后非空）。
        String after = meta.code;
        if (after == null || after.trim().isEmpty()) return "没有可执行的脚本代码";
        return null;
    }

    /** 粘贴脚本内容对话框。 */
    private void showPasteUserscriptDialog() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            final android.widget.EditText et = new android.widget.EditText(act);
            et.setHint("粘贴完整 .user.js 脚本内容（含 ==UserScript== 头）");
            et.setMinLines(8);
            et.setMaxLines(16);
            et.setGravity(android.view.Gravity.TOP);
            new android.app.AlertDialog.Builder(act)
                    .setTitle("粘贴脚本内容")
                    .setView(et)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            String content = et.getText().toString();
                            if (content.trim().isEmpty()) { toastOnMain("内容为空"); return; }
                            String fn = saveUserscriptContent(content);
                            toastOnMain(fn == null ? "保存失败" : ("已保存: " + fn));
                            refreshCurrentUserscriptPicker();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showPasteUserscriptDialog error: " + t);
        }
    }

    /** 启动文件选择器导入 .user.js。 */
    private void launchUserscriptFilePicker() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            act.startActivityForResult(i, REQUEST_USERSCRIPT_PICK);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] launchUserscriptFilePicker error: " + t);
        }
    }

    /** 将脚本内容写入目录，返回文件名（自动按 @name 生成，冲突加序号）。 */
    private String saveUserscriptContent(String content) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null) return null;
            // 从内容解析名字，生成文件名。
            UserscriptMeta meta = UserscriptMeta.parse(content);
            XposedBridge.log("[SBPlus] saveUserscript parsed name='" + meta.name + "' version=" + meta.version);
            String base = sanitizeFileName(meta.name.isEmpty() ? "script" : meta.name);
            java.io.File f = new java.io.File(dir, base + ".user.js");
            int n = 1;
            while (f.exists()) { f = new java.io.File(dir, base + "_" + (n++) + ".user.js"); }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            return f.getName();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveUserscriptContent error: " + t);
            return null;
        }
    }

    /** 覆盖已有脚本文件（编辑模式），返回文件名；失败返回 null。 */
    private String overwriteUserscriptContent(String fileName, String content) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null) return null;
            java.io.File f = new java.io.File(dir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            return f.getName();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] overwriteUserscriptContent error: " + t);
            return null;
        }
    }

    /** 文件名合法化（移除 Windows/Android 非法字符）。 */
    private String sanitizeFileName(String name) {
        if (name == null) return "script";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|'
                    || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }        String r = sb.toString().trim();
        if (r.isEmpty()) r = "script";
        if (r.length() > 60) r = r.substring(0, 60);
        return r;
    }

    /** 记录脚本来源（下载地址 / 本地导入 / 手动添加）。 */
    private void saveSource(String fileName, String source) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null || fileName == null) return;
            java.io.File f = new java.io.File(dir, "_sources.json");
            org.json.JSONObject obj = new org.json.JSONObject();
            if (f.exists()) {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] buf = new byte[(int) f.length()];
                    in.read(buf);
                    in.close();
                    obj = new org.json.JSONObject(new String(buf, "UTF-8"));
                } catch (Throwable t) {}
            }
            obj.put(fileName, source == null ? "" : source);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(obj.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveSource error: " + t);
        }
    }

    /** 读取脚本来源；无记录返回空。 */
    private String getSource(String fileName) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null || fileName == null) return null;
            java.io.File f = new java.io.File(dir, "_sources.json");
            if (!f.exists()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            in.read(buf);
            in.close();
            org.json.JSONObject obj = new org.json.JSONObject(new String(buf, "UTF-8"));
            return obj.optString(fileName, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 更新所有脚本（异步下载对比版本）。 */
    private void updateAllUserscripts() {
        java.util.List<UserscriptMeta> metas = loadUserscripts();
        int updatable = 0;
        for (UserscriptMeta m : metas) {
            String src = !m.updateURL.isEmpty() ? m.updateURL : m.downloadURL;
            if (!src.isEmpty()) updatable++;
        }
        final int total = updatable;
        if (total == 0) {
            toastOnMain("没有可检测更新的脚本（需声明 @updateURL 或 @downloadURL）");
            return;
        }
        toastOnMain("开始检测 " + total + " 个脚本更新...");
        new Thread(new Runnable() {
            @Override public void run() {
                int updated = 0;
                for (UserscriptMeta m : metas) {
                    String src = !m.updateURL.isEmpty() ? m.updateURL : m.downloadURL;
                    if (src.isEmpty()) continue;
                    try {
                        String remote = httpGet(src);
                        if (remote == null || remote.isEmpty()) continue;
                        UserscriptMeta rm = UserscriptMeta.parse(remote);
                        if (rm.version.isEmpty() || m.version.isEmpty() || rm.version.equals(m.version)) {
                            continue; // 无版本或版本相同，跳过
                        }
                        // 有更新，覆盖写入。
                        java.io.File dir = userscriptDir();
                        if (dir == null) continue;
                        java.io.File f = new java.io.File(dir, m.fileName);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                        fos.write(remote.getBytes("UTF-8"));
                        fos.close();
                        updated++;
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] update " + m.name + " error: " + t);
                    }
                }
                final int u = updated;
                toastOnMain("更新完成：更新了 " + u + " 个脚本");
                refreshCurrentUserscriptPicker();
            }
        }).start();
    }

    /** 从 url 下载 .user.js 到目录（供下载拦截使用）。 */
    private void downloadUserscriptToDir(String url) {
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String content = httpGet(url);
                        if (content == null || content.isEmpty()) { toastOnMain("下载失败: " + url); return; }
                        String fn = saveUserscriptContent(content);
                        if (fn != null) saveSource(fn, url);
                        toastOnMain(fn == null ? "保存失败" : ("已安装脚本: " + fn));
                        refreshCurrentUserscriptPicker();
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] downloadUserscriptToDir error: " + t);
                    }
                }
            }).start();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadUserscriptToDir error: " + t);
        }
    }

    /** 简单 HTTP GET，返回响应体字符串。 */
    private String httpGet(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (SBPlus Userscript)");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        }
 catch (Throwable t) {
            XposedBridge.log("[SBPlus] httpGet error: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 判断 url/文件名是否为油猴脚本（.user.js，兼容被加 .txt 后缀的情况）。 */
    private boolean isUserScriptUrl(String url, String fileName) {
        try {
            if (fileName != null) {
                String f = fileName.toLowerCase();
                // 兼容 .user.js 被浏览器误加 .txt 后缀（如 a.user.js.txt）
                if (f.contains(".user.js")) return true;
            }
            if (url == null) return false;
            String u = url.toLowerCase();
            int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
            int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
            return u.contains(".user.js");
        } catch (Throwable t) { return false; }
    }

    /** 读取 content:// URI 文本内容（用于导入本地 .user.js 文件）。 */
    private String readUriText(android.content.Context ctx, android.net.Uri uri) {
        java.io.InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] readUriText error: " + t);
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 书签管理（导出/导入 HTML 书签） ====================

    private static final int REQUEST_BOOKMARK_PICK = 61003;

    private static String bookmarkDbPath() {
        return "/data/data/com.sec.android.app.sbrowser/databases/SBrowser.db";
    }

    private static java.io.File bookmarkExportFile() {
        java.io.File dl = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        java.io.File dir = new java.io.File(dl, "SBPlus");
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, "bookmarks.html");
    }

    private void launchBookmarkFilePicker() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity
                    : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("text/html");
            i.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                    new String[]{ "text/html", "text/plain", "application/xhtml+xml" });
            act.startActivityForResult(i, REQUEST_BOOKMARK_PICK);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] launchBookmarkFilePicker error: " + t);
        }
    }

    /** 书签管理对话框：导入 / 导出。 */
    private void showBookmarkManagerDialog(final Context ctx) {
        try {
            final android.app.Activity act = sCurrentActivity != null ? sCurrentActivity
                    : (ctx instanceof android.app.Activity ? (android.app.Activity) ctx : null);
            if (act == null) { toastOnMain("无法获取界面环境"); return; }
            final String[] items = new String[]{ "导入书签", "导出书签" };
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle("书签管理");
            b.setItems(items, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dlg, int which) {
                    if (which == 0) {
                        launchBookmarkFilePicker();
                    } else {
                        final BookmarkNode tree = buildBookmarkTree(readBookmarkNodes());
                        showBookmarkTreeDialog(act, "选择要导出的书签", tree, true);
                    }
                }
            });
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showBookmarkManagerDialog error: " + t);
        }
    }

    // ==================== 书签树形勾选对话框 ====================

    /** 弹书签树勾选对话框：勾选要导出/导入的节点。 */
    private void showBookmarkTreeDialog(final android.app.Activity act, final String title,
            final BookmarkNode root, final boolean isExport) {
        try {
            final android.widget.LinearLayout body = new android.widget.LinearLayout(act);
            body.setOrientation(android.widget.LinearLayout.VERTICAL);
            body.setPadding(24, 16, 24, 16);

            // 顶部操作按钮行
            final android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, 0, 0, 8);

            android.widget.Button allBtn = new android.widget.Button(act);
            allBtn.setText("全选");
            android.widget.Button noneBtn = new android.widget.Button(act);
            noneBtn.setText("全不选");
            btnRow.addView(allBtn, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnRow.addView(noneBtn, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            body.addView(btnRow);

            // 树容器（放在 ScrollView 里），高度按屏幕动态计算
            final android.widget.LinearLayout tree = new android.widget.LinearLayout(act);
            tree.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.ScrollView sv = new android.widget.ScrollView(act);
            sv.addView(tree, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            int treeH = Math.max(420, (int) (screenH * 0.65f));
            android.widget.LinearLayout.LayoutParams svlp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, treeH);
            body.addView(sv, svlp);

            // 递归重绘树
            final Runnable[] rerender = new Runnable[1];
            rerender[0] = new Runnable() {
                @Override public void run() {
                    tree.removeAllViews();
                    renderTreeRows(tree, root, 0, act, rerender[0]);
                }
            };
            rerender[0].run();

            // 复选框容器回填：记录每个节点对应的 CheckBox，供全选/全不选使用
            final java.util.List<BookmarkNode> allNodes = new java.util.ArrayList<BookmarkNode>();
            root.expanded = true; // 默认展开顶层
            collectNodes(root, allNodes);

            allBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    setTreeChecked(root, true);
                    rerender[0].run();
                }
            });
            noneBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    setTreeChecked(root, false);
                    rerender[0].run();
                }
            });

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle(title);
            b.setView(body);
            b.setNegativeButton("取消", null);
            b.setPositiveButton(isExport ? "导出所选" : "导入所选",
                    new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface dlg, int which) {
                    if (isExport) {
                        doExportSelected(root);
                    } else {
                        doImportSelected(root);
                    }
                }
            });
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showBookmarkTreeDialog error: " + t);
        }
    }

    /** 收集所有节点（含根本身不入列）。 */
    private void collectNodes(BookmarkNode node, java.util.List<BookmarkNode> out) {
        for (BookmarkNode c : node.children) {
            out.add(c);
            collectNodes(c, out);
        }
    }

    /** 递归设置整棵树的勾选状态。 */
    private void setTreeChecked(BookmarkNode node, boolean checked) {
        for (BookmarkNode c : node.children) {
            c.checked = checked;
            setTreeChecked(c, checked);
        }
    }

    /** 递归渲染树行。 */
    private void renderTreeRows(final android.widget.LinearLayout tree, final BookmarkNode node,
            final int depth, final android.app.Activity act, final Runnable rerender) {
        for (final BookmarkNode child : node.children) {
            // 缩进
            android.widget.LinearLayout row = new android.widget.LinearLayout(act);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int indent = depth * 22;
            row.setPadding(indent, 2, 0, 2);

            if (child.folder == 1) {
                // 展开/折叠箭头
                android.widget.TextView arrow = new android.widget.TextView(act);
                arrow.setText(child.expanded ? "\u25BE " : "\u25B8 ");
                arrow.setTextSize(16);
                arrow.setPadding(0, 0, 4, 0);
                arrow.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        child.expanded = !child.expanded;
                        rerender.run();
                    }
                });
                row.addView(arrow);

                // 复选框
                android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setChecked(child.checked);
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                        child.checked = isChecked;
                        setTreeChecked(child, isChecked);
                        rerender.run();
                    }
                });
                row.addView(cb);

                // 文件夹名（点名字也展开）
                android.widget.TextView label = new android.widget.TextView(act);
                label.setText(child.title == null ? "(文件夹)" : child.title);
                label.setTextSize(16);
                label.setSingleLine(false);
                label.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        child.expanded = !child.expanded;
                        rerender.run();
                    }
                });
                row.addView(label, new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                tree.addView(row, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

                if (child.expanded) {
                    renderTreeRows(tree, child, depth + 1, act, rerender);
                }
            } else {
                // 书签：空白占位 + 复选框 + 标题
                android.widget.TextView spacer = new android.widget.TextView(act);
                spacer.setText("   ");
                row.addView(spacer);

                android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setChecked(child.checked);
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                        child.checked = isChecked;
                    }
                });
                row.addView(cb);

                android.widget.TextView label = new android.widget.TextView(act);
                label.setText(child.title == null ? "(无标题)" : child.title);
                label.setTextSize(15);
                label.setSingleLine(false);
                row.addView(label, new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                tree.addView(row, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    /** 导出：只把勾选的节点序列化成 HTML。 */
    private void doExportSelected(BookmarkNode root) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
            sb.append("<!-- This is an automatically generated file. -->\n");
            sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
            sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n");
            sb.append("<DL><p>\n");
            int cnt = appendCheckedHtml(sb, root, 0);
            sb.append("</DL><p>\n");
            java.io.File out = bookmarkExportFile();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            toastOnMain("已导出 " + cnt + " 个书签：" + out.getAbsolutePath());
            XposedBridge.log("[SBPlus] export selected: " + cnt + " -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doExportSelected error: " + t);
            toastOnMain("导出失败");
        }
    }

    /** 递归生成仅勾选节点的 HTML，返回计数。 */
    private int appendCheckedHtml(StringBuilder sb, BookmarkNode node, int depth) {
        int count = 0;
        for (BookmarkNode child : node.children) {
            if (!child.checked) continue;
            String pad = new String(new char[depth * 4]).replace('\0', ' ');
            if (child.folder == 1) {
                sb.append(pad).append("<DT><H3 ADD_DATE=\"0\" LAST_MODIFIED=\"0\">")
                        .append(htmlEscape(child.title)).append("</H3>\n");
                sb.append(pad).append("<DL><p>\n");
                int sub = appendCheckedHtml(sb, child, depth + 1);
                sb.append(pad).append("</DL><p>\n");
                count += sub;
            } else {
                String u = child.url == null ? "" : child.url;
                sb.append(pad).append("<DT><A HREF=\"").append(htmlEscape(u))
                        .append("\" ADD_DATE=\"0\">").append(htmlEscape(child.title)).append("</A>\n");
                count++;
            }
        }
        return count;
    }

    /** 导入：只把勾选的节点写入 BOOKMARKS 表。 */
    private void doImportSelected(BookmarkNode root) {
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(bookmarkDbPath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            int cnt = insertCheckedTree(db, root, 0);
            db.setTransactionSuccessful();
            toastOnMain("已导入 " + cnt + " 个书签，请重启浏览器生效");
            XposedBridge.log("[SBPlus] import selected: " + cnt);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doImportSelected error: " + t);
            toastOnMain("导入失败");
        } finally {
            if (db != null) {
                try { if (db.inTransaction()) db.endTransaction(); } catch (Throwable ignored) {}
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 递归插入仅勾选的节点，返回计数。 */
    private int insertCheckedTree(android.database.sqlite.SQLiteDatabase db, BookmarkNode node, long parentId) {
        int count = 0;
        for (BookmarkNode child : node.children) {
            if (!child.checked) continue;
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("FOLDER", child.folder);
            cv.put("PARENT", parentId);
            cv.put("TITLE", child.title == null ? "" : child.title);
            if (child.folder == 0) {
                cv.put("URL", child.url == null ? "" : child.url);
                cv.put("SURL", child.url == null ? "" : child.url);
            }
            cv.put("DELETED", 0);
            cv.put("DIRTY", 1);
            cv.put("CREATED", System.currentTimeMillis() / 1000);
            cv.put("MODIFIED", System.currentTimeMillis() / 1000);
            cv.put("EDITABLE", 1);
            cv.put("bookmark", 1);
            cv.put("type", 1);
            long newId = db.insert("BOOKMARKS", null, cv);
            count++;
            if (child.folder == 1) {
                count += insertCheckedTree(db, child, newId);
            }
        }
        return count;
    }


    static class BookmarkNode {
        long id;
        long folder;   // 0=书签, 1=文件夹
        long parent;
        String title;
        String url;
        boolean checked = true;    // 勾选状态
        boolean expanded = false;  // 文件夹展开状态
        java.util.List<BookmarkNode> children = new java.util.ArrayList<BookmarkNode>();
    }

    private java.util.List<BookmarkNode> readBookmarkNodes() {
        java.util.List<BookmarkNode> list = new java.util.ArrayList<BookmarkNode>();
        java.io.File tmp = new java.io.File(sAppContext.getCacheDir(), "sb_bm_dump.db");
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            copyFile(new java.io.File(bookmarkDbPath()), tmp);
            db = android.database.sqlite.SQLiteDatabase.openDatabase(tmp.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT _ID, FOLDER, PARENT, TITLE, URL, DELETED FROM BOOKMARKS", null);
            while (c != null && c.moveToNext()) {
                long deleted = c.isNull(5) ? 0 : c.getLong(5);
                if (deleted != 0) continue;
                BookmarkNode n = new BookmarkNode();
                n.id = c.getLong(0);
                n.folder = c.getLong(1);
                n.parent = c.getLong(2);
                n.title = c.getString(3);
                n.url = c.getString(4);
                list.add(n);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] readBookmarkNodes error: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
        return list;
    }

    private BookmarkNode buildBookmarkTree(java.util.List<BookmarkNode> nodes) {
        BookmarkNode root = new BookmarkNode();
        root.id = Long.MIN_VALUE;
        root.folder = 1;
        root.title = "Bookmarks";
        java.util.Map<Long, BookmarkNode> byId = new java.util.HashMap<Long, BookmarkNode>();
        for (BookmarkNode n : nodes) {
            byId.put(n.id, n);
            n.children = new java.util.ArrayList<BookmarkNode>();
        }
        for (BookmarkNode n : nodes) {
            BookmarkNode p = byId.get(n.parent);
            if (p != null && p != n) {
                p.children.add(n);
            } else {
                root.children.add(n);
            }
        }
        return root;
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        String q = String.valueOf((char) 34);
        String apos = String.valueOf((char) 39);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace(q, "&quot;").replace(apos, "&#39;");
    }

    private static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", String.valueOf((char) 34)).replace("&#39;", "'").replace("&#x27;", "'");
    }



    private String extractTagText(String html, int tagStart) {
        int gt = html.indexOf(">", tagStart);
        if (gt < 0) return "";
        int end = html.indexOf("</", gt);
        if (end < 0) end = html.length();
        return htmlUnescape(html.substring(gt + 1, end));
    }

    private String extractHref(String html, int aStart) {
        int gt = html.indexOf(">", aStart);
        if (gt < 0) return "";
        String tag = html.substring(aStart, gt);
        char q = (char) 34;
        int hrefIdx = tag.indexOf("HREF=");
        if (hrefIdx < 0) hrefIdx = tag.indexOf("href=");
        if (hrefIdx < 0) return "";
        int quote = tag.indexOf(q, hrefIdx);
        if (quote < 0) return "";
        int quote2 = tag.indexOf(q, quote + 1);
        if (quote2 < 0) return "";
        return htmlUnescape(tag.substring(quote + 1, quote2));
    }

    private BookmarkNode parseBookmarkHtml(String html) {
        BookmarkNode root = new BookmarkNode();
        root.folder = 1;
        root.title = "Bookmarks";
        java.util.Deque<BookmarkNode> stack = new java.util.ArrayDeque<BookmarkNode>();
        stack.push(root);
        // 逐段扫描 <DT> 条目，识别 <H3>（文件夹）和 <A>（书签）
        int pos = 0;
        int len = html.length();
        while (pos < len) {
            int dt = html.indexOf("<DT>", pos);
            if (dt < 0) break;
            int afterDt = dt + 4;
            int nextDt = html.indexOf("<DT>", afterDt);
            if (nextDt < 0) nextDt = len;
            int endDl = html.indexOf("</DL>", afterDt);
            int close = nextDt;
            if (endDl >= 0 && endDl < close) close = endDl;
            String seg = html.substring(afterDt, close);

            int h3 = seg.indexOf("<H3");
            int a = seg.indexOf("<A");
            if (h3 >= 0 && (a < 0 || h3 < a)) {
                BookmarkNode folder = new BookmarkNode();
                folder.folder = 1;
                folder.title = extractTagText(html, afterDt + h3);
                if (!stack.isEmpty()) stack.peek().children.add(folder);
                int dlOpen = seg.indexOf("<DL");
                int dlClose = seg.indexOf("</DL>");
                if (dlOpen >= 0 && (dlClose < 0 || dlOpen < dlClose)) {
                    stack.push(folder);
                }
            } else if (a >= 0) {
                BookmarkNode bm = new BookmarkNode();
                bm.folder = 0;
                bm.title = extractTagText(html, afterDt + a);
                bm.url = extractHref(html, afterDt + a);
                if (!stack.isEmpty()) stack.peek().children.add(bm);
            }

            // 处理 </DL> 归约：弹栈
            // 简单做法：每遇到一个 </DL> 且栈深>1 就弹一次（对应一个文件夹闭合）
            int dlEnds = 0;
            int sidx = 0;
            while (true) {
                int e = seg.indexOf("</DL>", sidx);
                if (e < 0) break;
                dlEnds++;
                sidx = e + 5;
            }
            for (int k = 0; k < dlEnds && stack.size() > 1; k++) stack.pop();

            pos = nextDt;
        }
        return root;
    }




    /** 主线程 Toast。 */
    private void toastOnMain(final String msg) {
        try {
            if (sAppContext == null) return;
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                android.widget.Toast.makeText(sAppContext, msg, android.widget.Toast.LENGTH_SHORT).show();
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        android.widget.Toast.makeText(sAppContext, msg, android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    /** 刷新当前油猴子页（重新进入）。 */
    private void refreshCurrentUserscriptPicker() {
        try {
            // 简单起见：记录一个标记，下次进入时 reload；这里不做内存级刷新，
            // 改用 toast 提示用户返回重进。
            toastOnMain("返回后重新进入即可看到更新");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refresh error: " + t);
        }
    }

    /** 打开脚本目录（直接提示路径，避免 FileProvider 跨包引用问题）。 */
    private void bindOpenUserscriptDirClick(Object pref, ClassLoader cl) {
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
                                    java.io.File dir = userscriptDir();
                                    if (dir == null) {
                                        android.widget.Toast.makeText(ctx, "目录未初始化", android.widget.Toast.LENGTH_SHORT).show();
                                    } else {
                                        android.widget.Toast.makeText(ctx, "脚本目录：\n" + dir.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show();
                                    }
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] open userscript dir error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] open userscript dir bind failed: " + t);
        }
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
                    if (meta != null && !meta.name.isEmpty()) {
                        meta.fileName = f.getName();
                        list.add(meta);
                    }
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
        java.util.List<String> requires = new java.util.ArrayList<String>(); // @require 外部库
        java.util.List<String> resources = new java.util.ArrayList<String>();   // @resource 名称 URL
        java.util.List<String> grants = new java.util.ArrayList<String>();      // @grant 需要的能力
        String code = "";
        String version = "";
        String description = "";
        String downloadURL = "";
        String updateURL = "";
        String fileName = "";
        String homepageURL = "";
        String namespace = "";

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
                while (l.startsWith("//")) { l = l.substring(2).trim(); }  // 去掉 // 前缀，如 // @name
                if (l.startsWith("@name:zh-CN")) { m.name = stripMetaValue(l, "@name:zh-CN"); }
                else if (l.startsWith("@name:zh")) { m.name = stripMetaValue(l, "@name:zh"); }
                else if (l.startsWith("@name:")) { m.name = stripMetaValue(l, "@name:"); }
                else if (l.startsWith("@name ") || l.equals("@name")) { m.name = stripMetaValue(l, "@name"); }
                else if (l.startsWith("@match")) { m.match.add(stripMetaValue(l, "@match")); }
                else if (l.startsWith("@include")) { m.include.add(stripMetaValue(l, "@include")); }
                else if (l.startsWith("@exclude")) { m.exclude.add(stripMetaValue(l, "@exclude")); }
                else if (l.startsWith("@run-at")) { m.runAt = stripMetaValue(l, "@run-at"); }
                else if (l.startsWith("@version")) { m.version = stripMetaValue(l, "@version"); }
                else if (l.startsWith("@description")) { m.description = stripMetaValue(l, "@description"); }
                else if (l.startsWith("@downloadURL")) { m.downloadURL = stripMetaValue(l, "@downloadURL"); }
                else if (l.startsWith("@updateURL")) { m.updateURL = stripMetaValue(l, "@updateURL"); }
                else if (l.startsWith("@homepageURL")) { m.homepageURL = stripMetaValue(l, "@homepageURL"); }
                else if (l.startsWith("@namespace")) { m.namespace = stripMetaValue(l, "@namespace"); }
                else if (l.startsWith("@homepage ")) { m.homepageURL = stripMetaValue(l, "@homepage"); }
                else if (l.startsWith("@homepage")) { m.homepageURL = stripMetaValue(l, "@homepage"); }
                else if (l.startsWith("@require")) { m.requires.add(stripMetaValue(l, "@require")); }
                else if (l.startsWith("@resource")) { m.resources.add(stripMetaValue(l, "@resource")); }
                else if (l.startsWith("@grant")) { m.grants.add(stripMetaValue(l, "@grant")); }
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
            // 手动转义正则特殊字符，仅保留 * 作为通配符 -> .*
            String esc = p.replace("\\", "\\\\").replace(".", "\\.").replace("+", "\\+").replace("?", "\\?").replace("(", "\\(").replace(")", "\\)").replace("[", "\\[").replace("]", "\\]").replace("^", "\\^").replace("$", "\\$").replace("|", "\\|").replace("{", "\\{").replace("}", "\\}");
            String regex = esc.replace("*", ".*");
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
        "  window.onerror=function(m,src,l,c){try{if(window.__sbplus__&&window.__sbplus__.gmLog)window.__sbplus__.gmLog('ERR '+m+' @'+src+':'+l+':'+c);}catch(e){}};" +
        "  window.addEventListener('unhandledrejection',function(e){try{if(window.__sbplus__&&window.__sbplus__.gmLog)window.__sbplus__.gmLog('PROMISE '+(e.reason&&e.reason.message||e.reason));}catch(x){}});" +
        "  var _vcl={};var _vcSeq=0;" +
        "  GM.setValue=function(k,v){try{var old=GM.getValue(k,null);var sv;try{sv=(typeof v==='object'&&v!==null)?JSON.stringify(v):String(v);}catch(e2){sv=String(v);}if(store&&store.gmSetValue)store.gmSetValue(k,sv);else localStorage.setItem('gm_'+k,sv);var ls=_vcl[k];if(ls)for(var i=0;i<ls.length;i++){try{ls[i].cb(k,old,sv,false);}catch(e){}}}catch(e){}};" +
        "  GM.getValue=function(k,d){try{var v=(store&&store.gmGetValue)?store.gmGetValue(k):localStorage.getItem('gm_'+k);if(v===null||v==='')return d;if(v==='[object Object]'||v==='undefined'||v==='[object Array]')return d;try{return JSON.parse(v);}catch(e2){return v;}}catch(e){return d;}};" +
        "  GM.deleteValue=function(k){try{if(store&&store.gmDeleteValue)store.gmDeleteValue(k);else localStorage.removeItem('gm_'+k);}catch(e){}};" +
        "  GM.listValues=function(){try{if(store&&store.gmListValues)return store.gmListValues();var a=[];for(var i=0;i<localStorage.length;i++){var kk=localStorage.key(i);if(kk&&kk.indexOf('gm_')===0)a.push(kk.substring(3));}return a;}catch(e){return[];}};" +
        "  GM.addStyle=function(css){try{var st=document.createElement('style');st.type='text/css';st.textContent=css;document.head.appendChild(st);}catch(e){}};" +
        "  GM.log=function(){try{pushLog(Array.prototype.join.call(arguments,' '));}catch(e){}};" +
        "  GM.info={scriptHandler:'SBPlus',version:'1.0',script:{name:'',version:''}};" +
        "  GM.xmlHttpRequest=function(o){try{var b=window.__sbplus__&&window.__sbplus__.gmXhr;if(b){try{if(window.__sbplus__.gmLog)window.__sbplus__.gmLog('XHR-BRIDGE '+o.method+' '+o.url);}catch(e){}var hd={};if(o.headers)for(var h in o.headers)hd[h]=o.headers[h];var j=b.gmXhr(o.method||'GET',o.url,JSON.stringify(hd),o.data||null);var p=JSON.parse(j);var r={status:p.status,statusText:'',responseText:p.responseText,response:p.responseText,responseHeaders:'',finalUrl:o.url};if(p.status>=200&&p.status<400){try{if(o.onload)o.onload(r);}catch(e){}}else{try{if(o.onerror)o.onerror(r);}catch(e){}}return;}try{if(window.__sbplus__&&window.__sbplus__.gmLog)window.__sbplus__.gmLog('XHR-FALLBACK '+o.method+' '+o.url);}catch(e){}var x=new XMLHttpRequest();x.open(o.method||'GET',o.url,true);x.onreadystatechange=function(){if(x.readyState===4){var r={status:x.status,statusText:x.statusText,responseText:x.responseText,response:x.responseText,responseHeaders:'',finalUrl:o.url};try{if(o.onload)o.onload(r);}catch(e){}}};if(o.headers){for(var h in o.headers)x.setRequestHeader(h,o.headers[h]);}if(o.timeout)x.timeout=o.timeout;x.send(o.data||null);}catch(e){try{if(o.onerror)o.onerror();}catch(e2){}}};" +
        "  GM.openInTab=function(url,opt){try{window.open(url,'_blank');}catch(e){}};" +
        "  GM.setClipboard=function(t){try{if(store&&store.gmSetClipboard)store.gmSetClipboard(String(t));}catch(e){}};" +
        "  GM.setValues=function(obj){try{if(obj)for(var k in obj)GM.setValue(k,obj[k]);}catch(e){}};" +
        "  GM.registerMenuCommand=function(name,fn,acc){try{window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REG:'+name+'@'+(window.__sbplus_current_tag__||'NULL'));window.__sbplus_menus__=window.__sbplus_menus__||{};var tag=window.__sbplus_current_tag__||'__default__';if(!window.__sbplus_menus__[tag])window.__sbplus_menus__[tag]=[];var id=window.__sbplus_menus__[tag].length;window.__sbplus_menus__[tag].push({n:name,f:fn});return id;}catch(e){window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REGERR:'+e);return 0;}};" +
        "  GM.unregisterMenuCommand=function(id){return 0;};" +
        "  GM.addValueChangeListener=function(k,cb){try{_vcl[k]=_vcl[k]||[];var id=++_vcSeq;_vcl[k].push({id:id,cb:cb});return id;}catch(e){return 0;}};" +
        "  GM.removeValueChangeListener=function(id){try{for(var k in _vcl){var ls=_vcl[k];for(var i=ls.length-1;i>=0;i--){if(ls[i].id===id)ls.splice(i,1);}}}catch(e){}};" +
                "  GM.notification=function(title,text,image,onclick){try{if(typeof Notification!=='undefined'&&Notification.permission==='granted'){var n=new Notification(String(title||''),{body:String(text||''),icon:image||null});if(onclick)n.onclick=onclick;}else if(typeof Notification!=='undefined'&&Notification.permission!=='denied'){try{Notification.requestPermission().then(function(p){if(p==='granted'){var n2=new Notification(String(title||''),{body:String(text||''),icon:image||null});if(onclick)n2.onclick=onclick;}}).catch(function(){});}catch(e2){}}else{alert((title||'')+' || '+(text||''));}}catch(e){try{alert((title||'')+' || '+(text||''));}catch(e2){}}};" +
"  GM.getResourceText=function(name){try{var r=window.__sbplus_resources__;return (r&&r[name])?r[name]:'';}catch(e){return '';}};" +
        "  var g={'GM':GM,'GM_setValue':GM.setValue,'GM_getValue':GM.getValue,'GM_deleteValue':GM.deleteValue,'GM_listValues':GM.listValues,'GM_addStyle':GM.addStyle,'GM_log':GM.log,'GM_info':GM.info,'GM_xmlhttpRequest':GM.xmlHttpRequest,'GM_openInTab':GM.openInTab,'GM_setClipboard':GM.setClipboard,'GM_setValues':GM.setValues,'GM_registerMenuCommand':GM.registerMenuCommand,'GM_unregisterMenuCommand':GM.unregisterMenuCommand,'GM_addValueChangeListener':GM.addValueChangeListener,'GM_removeValueChangeListener':GM.removeValueChangeListener,'GM_notification':GM.notification,'GM_getResourceText':GM.getResourceText,'unsafeWindow':window};" +
        "  for(var k in g){try{if(typeof window[k]==='undefined')window[k]=g[k];}catch(e){}};" +
        "  window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('GM_TYPEOF_'+typeof window.GM_registerMenuCommand);window.__sbplus_dbg__.push('GM_TYPEOF_UNDERSCORE_'+typeof window.GM_registerMenuCommand);" +
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
            // 页面开始加载时就注册 JS 桥，确保 window.__sbplus__ 在页面上下文建立时就存在。
            XposedHelpers.findAndHookMethod(tabEventHandler, "onLoadStarted", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                registerJsBridge(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] registerJsBridge(onLoadStarted) error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] TabEventHandler.onLoadFinished/onLoadStarted hooked for userscript");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] userscript hook failed: " + t);
        }
    }

    /** 在页面加载完成后，把匹配的油猴脚本注入当前 Tab。 */
    /**
     * 地址栏油猴图标：hook LocationBarButtonLayout.onFinishInflate，在刷新按钮旁注入一个
     * 油猴图标。点击图标弹出「当前页面生效脚本」列表，点脚本可查看/触发其菜单命令。
     */
    private void hookUserscriptToolbar(ClassLoader cl) {
        try {
            Class<?> layoutCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.omnibox.LocationBarButtonLayout", cl);
            XposedHelpers.findAndHookMethod(layoutCls, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        injectUserscriptToolbarButton(param.thisObject, cl);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] injectUserscriptToolbarButton error: " + t);
                    }
                }
            });
            XposedBridge.log("[SBPlus] LocationBarButtonLayout.onFinishInflate hooked for userscript toolbar");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookUserscriptToolbar failed: " + t);
        }
    }

    /** 在刷新按钮旁插入油猴图标按钮（幂等）。 */
    private void injectUserscriptToolbarButton(final Object layoutObj, final ClassLoader cl) {
        try {
            final Object urlBarParent = XposedHelpers.getObjectField(layoutObj, "mUrlBarParent");
            if (!(urlBarParent instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup parent = (android.view.ViewGroup) urlBarParent;
            android.view.View already = parent.findViewWithTag("sbplus_monkey_btn");
            if (already != null) return;

            final Context ctx = parent.getContext();
            Object reloadBtn = XposedHelpers.getObjectField(layoutObj, "mReloadButton");
            Object copyBtn = XposedHelpers.getObjectField(layoutObj, "mCopyButton");
            Object zoomBtn = XposedHelpers.getObjectField(layoutObj, "mZoomButton");
            int insertIndex = -1;
            // 优先放 copy 按钮之后（即跳转App等右侧图标的最前面）。
            if (copyBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) copyBtn) + 1;
            } else if (zoomBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) zoomBtn) + 1;
            } else if (reloadBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) reloadBtn);
            }
            if (insertIndex < 0) insertIndex = parent.getChildCount();

            int iconSize = getDimen(ctx, "location_bar_icon_size", 40);
            int iconHeight = getDimen(ctx, "location_bar_height", 48);
            int margin = getDimen(ctx, "location_bar_icon_margin", 6);

            android.widget.TextView btn = new android.widget.TextView(ctx);
            btn.setText("\uD83D\uDC35");
            btn.setTextSize(16);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTag("sbplus_monkey_btn");
            btn.setContentDescription("[SBPlus] 油猴脚本");
            btn.setPadding(margin, 0, margin, 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    iconSize, iconHeight);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    try {
                        showUserscriptScriptsPopup(layoutObj, cl, v);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] onclick userscript popup error: " + t);
                    }
                }
            });

            parent.addView(btn, insertIndex);
            XposedBridge.log("[SBPlus] userscript toolbar button injected at index " + insertIndex);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUserscriptToolbarButton inner error: " + t);
        }
    }

    /** 从 anchor 下方弹出一个列表 PopupWindow。onItem(itemIndex) 回调点击。 */
        private String dumpChars(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder();
        int n = Math.min(s.length(), 80);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b.append((int)c).append(",");
        }
        return b.toString();
    }

    private void showAnchoredList(final android.view.View anchor, final String title,
                                  final java.util.List<String> items,
                                  final com.sbplus.browser.MainHook.ItemClickListener onItem) {
        try {
            final Context ctx = anchor.getContext();
            final boolean dark = isDarkMode(ctx);

            final int BG      = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
            final int BG_POP  = dark ? 0xFF2A2A2A : 0xFFFFFFFF;
            final int FG      = dark ? 0xFFE6E6E6 : 0xFF111111;
            final int FG_SUB  = dark ? 0xFF9A9A9A : 0xFF777777;
            final int DIVIDER = dark ? 0xFF383838 : 0xFFE5E5E5;

            android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);

            boolean hasTitle = title != null && !title.isEmpty();
            if (hasTitle) {
                android.widget.TextView tvTitle = new android.widget.TextView(ctx);
                tvTitle.setText(title);
                tvTitle.setTextSize(13);
                tvTitle.setTextColor(FG_SUB);
                tvTitle.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 4));
                root.addView(tvTitle, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            final int maxTextW = screenPopupMaxWidth(ctx);
            final android.widget.PopupWindow[] popRef = new android.widget.PopupWindow[1];
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                final String label = items.get(i);
                android.widget.TextView tv = new android.widget.TextView(ctx);
                tv.setText(label);
                tv.setTextSize(15);
                tv.setTextColor(FG);
                tv.setSingleLine(false);
                tv.setMaxWidth(maxTextW);
                tv.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12));
                tv.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        if (popRef[0] != null) popRef[0].dismiss();
                        if (onItem != null) onItem.onItem(idx);
                    }
                });
                root.addView(tv, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                if (i < items.size() - 1) {
                    android.view.View dv = new android.view.View(ctx);
                    dv.setBackgroundColor(DIVIDER);
                    root.addView(dv, new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
            }

            android.widget.PopupWindow pop = new android.widget.PopupWindow(
                    root, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pop.setOutsideTouchable(true);
            pop.setFocusable(true);
            pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG_POP));
            popRef[0] = pop;
            // 包一层 ScrollView，限制最大高度为屏幕 60%，过时可滚动、避免末尾项被截断。
            wrapWithScroll(pop, ctx, root);
            showPopup(pop, anchor, ctx);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showAnchoredList error: " + t);
        }
    }

    /** 把弹窗内容包进 ScrollView，限制最大高度并保证可滚动，避免列表过长时末尾项被截断。 */
    private void wrapWithScroll(final android.widget.PopupWindow pop, final Context ctx, final android.view.View content) {
        try {
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            // 限宽：内容不超过屏幕宽的 85%。
            int maxW = (int) (screenW * 0.85f);
            int maxH = (int) (screenH * 0.6f);
            // 第一步：用 AT_MOST(限宽) 测宽度，拿到真实宽度（UNSPECIFIED 会让 weight=1 的 child 得到 0 宽）。
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST);
            int hSpec0 = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            content.measure(wSpec, hSpec0);
            int contentW = Math.min(content.getMeasuredWidth(), maxW);
            // 第二步：用已确定宽度测高度（宽度固定后，weight=1 的 child 才得到正确宽度与换行）。
            int wSpec2 = android.view.View.MeasureSpec.makeMeasureSpec(contentW, android.view.View.MeasureSpec.EXACTLY);
            int hSpec2 = android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST);
            content.measure(wSpec2, hSpec2);
            int contentH = content.getMeasuredHeight();
            int w = contentW > 0 ? contentW : maxW;
            int h = contentH > 0 && contentH < maxH ? contentH : maxH;

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            sv.setLayoutParams(new android.view.ViewGroup.LayoutParams(w, h));
            sv.addView(content, new android.view.ViewGroup.LayoutParams(w,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            pop.setContentView(sv);
            pop.setWidth(w);
            pop.setHeight(h);
            // 裁剪关闭（API 21+），避免系统自动裁剪弹窗导致高度丢失。
            try { pop.setClippingEnabled(false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] wrapWithScroll error: " + t);
        }
    }

    /** 主线程安全地显示弹窗，锚点失效时兜底定位到屏幕右上。锚点在屏幕下半时向上展开。 */
    private void showPopup(final android.widget.PopupWindow pop, final android.view.View anchor, final Context ctx) {
        final int offY = dp(ctx, 6);
        final Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    if (anchor != null && anchor.getWindowToken() != null) {
                        // 判断锚点位置：在屏幕下半部则向上展开，避免底部工具栏锚点导致弹窗被屏幕底部截断。
                        int[] loc = new int[2];
                        anchor.getLocationOnScreen(loc);
                        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
                        int anchorCenterY = loc[1] + anchor.getHeight() / 2;
                        if (anchorCenterY > screenH / 2) {
                            int popH = pop.getHeight() > 0 ? pop.getHeight() : 0;
                            int yOff = -(popH + anchor.getHeight() + offY);
                            pop.showAsDropDown(anchor, 0, yOff);
                        } else {
                            pop.showAsDropDown(anchor, 0, offY);
                        }
                    } else {
                        int[] loc = new int[2];
                        if (anchor != null) anchor.getLocationOnScreen(loc);
                        pop.showAtLocation(anchor, android.view.Gravity.TOP | android.view.Gravity.END,
                                dp(ctx, 8), (loc[1] > 0 ? loc[1] + dp(ctx, 40) : dp(ctx, 120)));
                    }
                } catch (Throwable t) { XposedBridge.log("[SBPlus] showPopup err: " + t); }
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(run);
        }
    }

    /** 判断系统是否为深色模式。 */
    private boolean isDarkMode(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return false; }
    }

    /** 列表项点击回调。 */
    public interface ItemClickListener {
        void onItem(int index);
    }

    /** 弹出「当前页面生效脚本」列表（锚定到图标下方）。 */
    private void showUserscriptScriptsPopup(final Object layoutObj, ClassLoader cl, final android.view.View anchor) {
        try {
            String url = null;
            Object terrace = null;
            try {
                Object delegate = XposedHelpers.getObjectField(layoutObj, "mTabDelegate");
                if (delegate != null) {
                    try { url = (String) XposedHelpers.callMethod(delegate, "getCurrentUrl"); } catch (Throwable t) {}
                    try { terrace = XposedHelpers.callMethod(delegate, "getTerrace"); } catch (Throwable t) {}
                }
            } catch (Throwable t) {}

            if (url == null) url = sCurrentUrl;
            if (terrace == null && sCurrentRealTab != null) terrace = sCurrentRealTab;

            // 加载匹配当前页面的脚本（含 fileName/enabled，供开关使用）。
            final java.util.List<UserscriptMeta> matched = new java.util.ArrayList<UserscriptMeta>();
            java.util.List<UserscriptMeta> metas = loadUserscripts();
            for (UserscriptMeta m : metas) {
                if (m.matches(url)) matched.add(m);
            }

            final Object fTerrace = terrace;
            if (matched.isEmpty()) {
                java.util.List<String> emptyHint = new java.util.ArrayList<String>();
                emptyHint.add("本页面没有生效的油猴脚本");
                showAnchoredList(anchor, "油猴脚本", emptyHint, null);
                return;
            }
            showScriptSwitchList(anchor, "当前页面脚本", matched, new com.sbplus.browser.MainHook.ItemClickListener() {
                @Override
                public void onItem(int index) {
                    try {
                        showUserscriptMenuCommandPopup(matched.get(index).name, fTerrace, anchor);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] menu popup error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUserscriptScriptsPopup error: " + t);
        }
    }

    /** 带开关的脚本列表弹窗：每行 = 脚本名（可换行） + 启用开关。 */
    private void showScriptSwitchList(final android.view.View anchor, final String title,
                                      final java.util.List<UserscriptMeta> scripts,
                                      final com.sbplus.browser.MainHook.ItemClickListener onItem) {
        try {
            final Context ctx = anchor.getContext();
            final boolean dark = isDarkMode(ctx);

            final int BG      = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
            final int BG_POP  = dark ? 0xFF2A2A2A : 0xFFFFFFFF;
            final int FG      = dark ? 0xFFE6E6E6 : 0xFF111111;
            final int FG_SUB  = dark ? 0xFF9A9A9A : 0xFF777777;
            final int DIVIDER = dark ? 0xFF383838 : 0xFFE5E5E5;

            android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);

            if (title != null && !title.isEmpty()) {
                android.widget.TextView tvTitle = new android.widget.TextView(ctx);
                tvTitle.setText(title);
                tvTitle.setTextSize(13);
                tvTitle.setTextColor(FG_SUB);
                tvTitle.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 4));
                root.addView(tvTitle, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            final int maxTextW = screenPopupMaxWidth(ctx) - dp(ctx, 16) - dp(ctx, 16) - dp(ctx, 12);
            final android.widget.PopupWindow[] popRef = new android.widget.PopupWindow[1];
            for (int i = 0; i < scripts.size(); i++) {
                final int idx = i;
                final UserscriptMeta meta = scripts.get(i);

                android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));

                android.widget.TextView tvName = new android.widget.TextView(ctx);
                tvName.setText(meta.name);
                tvName.setTextSize(15);
                tvName.setTextColor(FG);
                tvName.setSingleLine(false);
                tvName.setMaxWidth(maxTextW);

                final android.widget.Switch sw = new android.widget.Switch(ctx);
                sw.setChecked(isUserscriptFileEnabled(meta.fileName));
                sw.setTextOn(null);
                sw.setTextOff(null);
                sw.setShowText(false);
                sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                        setUserscriptFileEnabled(meta.fileName, checked);
                        XposedBridge.log("[SBPlus] switch script " + meta.fileName + " -> " + checked);
                    }
                });

                row.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        if (popRef[0] != null) popRef[0].dismiss();
                        if (onItem != null) onItem.onItem(idx);
                    }
                });

                // 开关在前，名字在后；开关与名字之间留 12dp 空隙。
                row.addView(sw);
                android.widget.LinearLayout.LayoutParams nameLp = new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nameLp.leftMargin = dp(ctx, 12);
                tvName.setLayoutParams(nameLp);
                row.addView(tvName);
                root.addView(row, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                if (i < scripts.size() - 1) {
                    android.view.View dv = new android.view.View(ctx);
                    dv.setBackgroundColor(DIVIDER);
                    root.addView(dv, new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
            }

            android.widget.PopupWindow pop = new android.widget.PopupWindow(
                    root, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pop.setOutsideTouchable(true);
            pop.setFocusable(true);
            pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG_POP));
            popRef[0] = pop;
            // 包一层 ScrollView，限制最大高度为屏幕 60%，过时可滚动、避免末尾项被截断。
            wrapWithScroll(pop, ctx, root);
            showPopup(pop, anchor, ctx);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showScriptSwitchList error: " + t);
        }
    }

    /** 弹出某脚本在当前页面注册的菜单命令列表（锚定到图标下方），点命令触发回调。 */
    private void showUserscriptMenuCommandPopup(final String scriptName, final Object terrace, final android.view.View anchor) {
        try {
            if (terrace == null) {
                XposedBridge.log("[SBPlus] menu popup: terrace null");
                return;
            }
            final String tag = quoteJsonString(scriptName);
            final String js = "JSON.stringify((window.__sbplus_menus__&&window.__sbplus_menus__[" + tag + "]||[]).map(function(m){return m.n;}));";
            // 调试：同时打所有 tag 和本脚本 tag。
            final String dbgJs = "JSON.stringify(Object.keys(window.__sbplus_menus__||{}))+'|'+" + tag + "+'|'+JSON.stringify(window.__sbplus_menus__||{})+'|DBG:'+JSON.stringify(window.__sbplus_dbg__||[])";
            evaluateJsWithResult(terrace, dbgJs, new com.sbplus.browser.MainHook.JsResultListener() {
                @Override public void onResult(String r) { XposedBridge.log("[SBPlus] menus dbg: " + r); }
            });
            evaluateJsWithResult(terrace, js, new com.sbplus.browser.MainHook.JsResultListener() {
                @Override
                public void onResult(String result) {
                    XposedBridge.log("[SBPlus] menu js result: [" + result + "] tag=" + tag);
                    try {
                        final java.util.List<String> cmdNames = new java.util.ArrayList<String>();
                        try {
                            // Terrace 对字符串返回值多做了一层 JSON 编码（result 形如 "[\"...\"]"），先解一层。
                            String raw = result;
                            try {
                                Object tmp = new org.json.JSONTokener(raw).nextValue();
                                if (tmp instanceof String) raw = (String) tmp;
                            } catch (Throwable t) {}
                            org.json.JSONArray arr = new org.json.JSONArray(raw);
                            for (int i = 0; i < arr.length(); i++) cmdNames.add(arr.optString(i));
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] JSON parse fail: " + t + " resultChars=" + dumpChars(result));
                        }

                        final java.util.List<String> items = cmdNames;
                        if (items.isEmpty()) {
                            java.util.List<String> emptyHint = new java.util.ArrayList<String>();
                            emptyHint.add("此脚本没有可配置的菜单命令");
                            showAnchoredList(anchor, scriptName, emptyHint, null);
                            return;
                        }
                        showAnchoredList(anchor, scriptName + " · 菜单", items, new com.sbplus.browser.MainHook.ItemClickListener() {
                            @Override
                            public void onItem(int which) {
                                try {
                                    String triggerJs = "(function(){var m=(window.__sbplus_menus__&&window.__sbplus_menus__[" + tag + "]||[]);var c=m[" + which + "];if(c&&c.f)try{c.f();}catch(e){}})();";
                                    evaluateJsWithResult(terrace, triggerJs, null);
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] trigger menu error: " + t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] menu result parse error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUserscriptMenuCommandPopup error: " + t);
        }
    }

    /** 用 Terrace 执行一段 JS，并通过回调拿字符串结果（listener 可空）。 */
    private void evaluateJsWithResult(Object terrace, final String js, final com.sbplus.browser.MainHook.JsResultListener listener) {
        try {
            Class<?> cbCls = XposedHelpers.findClass("com.sec.terrace.TerraceJavaScriptCallback", sModuleClassLoader);
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    sModuleClassLoader,
                    new Class[]{ cbCls },
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                            if (listener != null && args != null && args.length > 0) {
                                listener.onResult(String.valueOf(args[0]));
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(terrace, "evaluateJavaScript", js, cb);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] evaluateJsWithResult error: " + t);
        }
    }

    /** JS 结果回调。 */
    public interface JsResultListener {
        void onResult(String result);
    }

    private String quoteJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    private int getDimen(Context ctx, String name, int defPx) {
        try {
            int id = ctx.getResources().getIdentifier(name, "dimen", ctx.getPackageName());
            if (id != 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable t) {}
        return defPx;
    }

    private void injectUserscripts(Object tabEventHandlerObj, String url) {
        XposedBridge.log("[SBPlus] injectUserscripts ENTER url=" + url + " enabled=" + isUserscriptEnabled());
        if (!isUserscriptEnabled()) return;
        if (url == null || url.isEmpty()) return;
        java.util.List<UserscriptMeta> metas = loadUserscripts();
        boolean isSourceSite = isScriptSourceSite(url);

        try {
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab"); // SBrowserTab
            if (tab == null) { XposedBridge.log("[SBPlus] inject: mTab is null"); return; }
            Object realTab = XposedHelpers.callMethod(tab, "getTab"); // com.sec...tab.Tab
            if (realTab == null) { XposedBridge.log("[SBPlus] inject: realTab is null"); return; }
            String curUrl = (String) XposedHelpers.callMethod(realTab, "getUrl");
            if (curUrl == null) curUrl = url;

            // 脚本源站点：无条件注入 GM API，伪造“脚本管理器已安装”，
            // 让 ScriptCat/GreasyFork 的“安装”按钮走正常下载路径而不弹引导。
            if (isSourceSite) {
                injectJs(realTab, GM_API_JS);
                XposedBridge.log("[SBPlus] GM API injected to source site: " + curUrl);
                if (metas.isEmpty()) return;
            }

            if (metas.isEmpty()) return;

            prefetchRequires(metas, curUrl, realTab);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUserscripts error: " + t);
        }
    }

    /** 注入 JS 到 Tab。 */
    private void injectJs(Object realTab, String js) {
        try {
            XposedHelpers.callMethod(realTab, "evaluateJavaScript", js, null);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectJs error: " + t);
        }
    }

    /** 从 TabEventHandler 对象拿到真实 Tab，并注册 __sbplus__ JS 桥（幂等）。 */
    private void registerJsBridge(Object tabEventHandlerObj) {
        try {
            if (!isUserscriptEnabled()) { XposedBridge.log("[SBPlus] registerJsBridge: disabled"); return; }
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab");
            if (tab == null) { XposedBridge.log("[SBPlus] registerJsBridge: mTab null"); return; }
            Object realTab = XposedHelpers.callMethod(tab, "getTab");
            if (realTab == null) { XposedBridge.log("[SBPlus] registerJsBridge: realTab null"); return; }
            XposedHelpers.callMethod(realTab, "addJavaScriptInterface", new SbplusJsBridge(), "__sbplus__");
            XposedBridge.log("[SBPlus] registerJsBridge OK on " + realTab.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] registerJsBridge error: " + t);
        }
    }

    /** 从缓存拼接脚本声明的 @require 外部库（主线程调用，只读缓存不做网络）。 */
    private String loadRequires(UserscriptMeta meta) {
        StringBuilder out = new StringBuilder();
        if (meta.requires == null || meta.requires.isEmpty()) return "";
        int i = 0;
        for (String reqUrl : meta.requires) {
            String lib = null;
            synchronized (requireCache) {
                lib = requireCache.get(reqUrl);
            }
            if (lib != null && !lib.isEmpty()) {
                out.append("\n/* ==== @require ").append(i).append(" ==== */\n");
                out.append(lib).append("\n");
            }
            i++;
        }
        return out.toString();
    }

    /** 后台线程预下载脚本依赖的 @require 库到缓存；全部就绪后回到主线程执行注入。 */
    private void prefetchRequires(final java.util.List<UserscriptMeta> metas, final String url, final Object realTab) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (UserscriptMeta m : metas) {
                        if (!m.matches(url)) continue;
                        if (!isUserscriptFileEnabled(m.fileName)) continue;
                        if (m.requires == null) continue;
                        for (String reqUrl : m.requires) {
                            synchronized (requireCache) {
                                if (requireCache.containsKey(reqUrl)) continue;
                            }
                            try {
                                String lib = httpGet(reqUrl);
                                if (lib != null && !lib.isEmpty()) {
                                    synchronized (requireCache) { requireCache.put(reqUrl, lib); }
                                    XposedBridge.log("[SBPlus] @require cached: " + reqUrl);
                                } else {
                                    XposedBridge.log("[SBPlus] @require FAILED: " + reqUrl);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] @require error: " + reqUrl + " -> " + t);
                            }
                        }
                    }
                    // 下载 @resource 资源（name + 空格 + url）
                    for (UserscriptMeta m : metas) {
                        if (!m.matches(url)) continue;
                        if (!isUserscriptFileEnabled(m.fileName)) continue;
                        if (m.resources == null) continue;
                        for (String res : m.resources) {
                            if (res == null) continue;
                            String trimmed = res.trim();
                            int sp = trimmed.indexOf(' ');
                            if (sp <= 0) continue;
                            final String rName = trimmed.substring(0, sp).trim();
                            final String rUrl = trimmed.substring(sp + 1).trim();
                            if (rName.isEmpty() || rUrl.isEmpty()) continue;
                            synchronized (resourceCache) {
                                if (resourceCache.containsKey(rName)) continue;
                            }
                            try {
                                String content = httpGet(rUrl);
                                if (content != null && !content.isEmpty()) {
                                    synchronized (resourceCache) { resourceCache.put(rName, content); }
                                    XposedBridge.log("[SBPlus] @resource cached: " + rName);
                                } else {
                                    XposedBridge.log("[SBPlus] @resource FAILED: " + rName);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] @resource error: " + rName + " -> " + t);
                            }
                        }
                    }
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(new Runnable() {
                        @Override public void run() {
                            doInjectOnMain(metas, url, realTab);
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] prefetch error: " + t);
                }
            }
        }).start();
    }

    /** 把 @resource 资源缓存拼成 window.__sbplus_resources__ 注入段。 */
    private String buildResourcesJs() {
        java.util.Map<String, String> copy;
        synchronized (resourceCache) {
            copy = new java.util.HashMap<String, String>(resourceCache);
        }
        if (copy.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("window.__sbplus_resources__={};");
        for (java.util.Map.Entry<String, String> e : copy.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null) v = "";
            sb.append("window.__sbplus_resources__[").append(jsonQuote(k)).append("]=").append(jsonQuote(v)).append(";");
        }
        return sb.toString();
    }

    /** 生成 JSON 双引号字符串字面量（含外层引号）。 */
    private String jsonQuote(String s) {
        if (s == null) return "\"\"";
        String e = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + e + "\"";
    }

    /** 主线程执行注入：只读缓存拼装 + evaluateJavaScript。 */
    private void doInjectOnMain(java.util.List<UserscriptMeta> metas, String url, Object realTab) {
        try {
            // 注册 __sbplus__ JS 桥（脚本执行时用 GM_xmlhttpRequest 跨域）。
            try {
                XposedHelpers.callMethod(realTab, "addJavaScriptInterface", new SbplusJsBridge(), "__sbplus__");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] addJavaScriptInterface error: " + t);
            }

            // 一次性拼接注入：@resource 资源 + GM API 引擎 + 逐脚本（@require + tag + 主体）。
            StringBuilder all = new StringBuilder();
            String resourcesJs = buildResourcesJs();
            if (resourcesJs != null && !resourcesJs.isEmpty()) all.append(resourcesJs);
            all.append(GM_API_JS);
            all.append("\nwindow.__sbplus_probe_done__='done';window.__sbplus_gm_typeof__=(typeof GM_registerMenuCommand);window.__sbplus_menus__={};window.__sbplus_dbg__=[];\n");
            all.append("window.addEventListener('unhandledrejection',function(e){window.__sbplus_last_error__=('promise:'+(e&&e.reason&&e.reason.message?e.reason.message:e));});\n");
            all.append("window.onerror=function(m,s,l,c){window.__sbplus_last_error__=('err:'+m+' @ '+(l||0));return false;};\n");

            boolean anyMatch = false;
            for (UserscriptMeta m : metas) {
                if (!m.matches(url)) continue;
                if (!isUserscriptFileEnabled(m.fileName)) continue;
                anyMatch = true;
                all.append(loadRequires(m));
                all.append("\nwindow.__sbplus_current_tag__=").append(jsonQuote(m.name)).append(";\n");
                // 为每个脚本重绑定带捕获 tag 的 registerMenuCommand（闭包），避免脚本异步
                // 注册菜单时读到被后续脚本覆盖的全局 current_tag，导致菜单错记到别的脚本名下。
                all.append("(function(){var __scoped_tag__=").append(jsonQuote(m.name)).append(";");
                all.append("var __scoped_orig__=window.GM_registerMenuCommand;");
                all.append("var __scoped_reg__=function(name,fn,acc){try{window.__sbplus_menus__=window.__sbplus_menus__||{};if(!window.__sbplus_menus__[__scoped_tag__])window.__sbplus_menus__[__scoped_tag__]=[];var id=window.__sbplus_menus__[__scoped_tag__].length;window.__sbplus_menus__[__scoped_tag__].push({n:name,f:fn});window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REG:'+name+'@'+__scoped_tag__);return id;}catch(e){return 0;}};");
                all.append("window.GM_registerMenuCommand=__scoped_reg__;window.GM.registerMenuCommand=__scoped_reg__;window.GM_unregisterMenuCommand=function(){return 0;};window.GM.unregisterMenuCommand=function(){return 0;};").append("})();\n");
                all.append("\ntry{\n(function(){\n").append(m.code).append("\n})();\n}catch(e){window.__sbplus_last_error__=('script:'+window.__sbplus_current_tag__+':'+e.message+' stack:'+(e.stack||''));}\n");
                XposedBridge.log("[SBPlus] inject script '" + m.name + "' codeLen=" + (m.code == null ? 0 : m.code.length()));
            }
            if (!anyMatch) return;

            all.append("window.__sbplus_scripts_count__='").append(countMatched(metas, url)).append("';");
            final String allJs = all.toString();
            try {
                java.io.File dd = sAppContext.getExternalFilesDir(null);
                if (dd != null) {
                    java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dd, "sbplus_injected.js"));
                    fw.write(allJs);
                    fw.close();
                    XposedBridge.log("[SBPlus] dumped sbplus_injected.js len=" + allJs.length());
                }
            } catch (Throwable t) { XposedBridge.log("[SBPlus] dump err: " + t); }
            XposedBridge.log("[SBPlus] ALLLEN=" + allJs.length() + " HEAD=" + safeHead(allJs, 200));
            final String[] attempt = new String[]{ allJs };
            // 确认式注入：注入后 600ms 读回探针，失败则最多重试 3 次（每次间隔递增）。
            final int[] tries = new int[]{ 0 };
            final Runnable[] injectOnceRef = new Runnable[1];
            injectOnceRef[0] = new Runnable() {
                @Override public void run() {
                    try {
                        evaluateJsWithResult(realTab, attempt[0], new com.sbplus.browser.MainHook.JsResultListener() {
                            @Override public void onResult(String r) { XposedBridge.log("[SBPlus] inject result: " + r); }
                        });
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    final int cur = tries[0];
                                    evaluateJsWithResult(realTab, "window.__sbplus_probe_done__+'|'+window.__sbplus_gm_typeof__+'|'+window.__sbplus_scripts_count__+'|'+window.__sbplus_last_error__", new com.sbplus.browser.MainHook.JsResultListener() {
                                        @Override public void onResult(String r2) {
                                            XposedBridge.log("[SBPlus] PROBE(try=" + cur + "): " + r2);
                                            boolean bad = (r2 == null || r2.contains("undefined") || r2.length() == 0);
                                            if (bad && cur < 4) {
                                                tries[0] = cur + 1;
                                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(injectOnceRef[0], 800);
                                            } else {
                                                XposedBridge.log("[SBPlus] PROBE OK: " + r2);
                                            }
                                        }
                                    });
                                } catch (Throwable t) { XposedBridge.log("[SBPlus] PROBE err: " + t); }
                            }
                        }, 600);
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] inject err: " + t); }
                }
            };
            injectOnceRef[0].run();

            java.util.List<String> active = new java.util.ArrayList<String>();
            for (UserscriptMeta m : metas) {
                if (m.matches(url) && isUserscriptFileEnabled(m.fileName)) active.add(m.name);
            }
            sActiveScriptsByUrl.put(url, active);
            sCurrentUrl = url;
            sCurrentRealTab = realTab;
            XposedBridge.log("[SBPlus] userscript injected for " + url + " (" + countMatched(metas, url) + " scripts)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doInjectOnMain error: " + t);
        }
    }

    /** 是否为脚本源站点（需要伪造脚本管理器）。 */
    private boolean isScriptSourceSite(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("scriptcat.org")
                || u.contains("greasyfork.org")
                || u.contains("userscript.zone")
                || u.contains("openuserjs.org")
                || u.contains("sleazyfork.org");
    }

    private String safeHead(String s, int n) { if (s == null) return "null"; int m = Math.min(n, s.length()); return s.substring(0, m); }
    private String safeTail(String s, int n, int skip) { if (s == null) return "null"; int L = s.length(); int start = Math.max(0, L - n - skip); return s.substring(start, Math.min(start + n, L)); }

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
                            // 特殊项：无条件隐藏无 key 的「隐私」分类标题（装饰性空标题）。
                            count += hidePrivacyCategory(frag);
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

    /** 隐藏无 key 的「隐私」分类标题（PreferenceCategory，按 title 遍历匹配）。返回隐藏数量。 */
    private int hidePrivacyCategory(Object frag) {
        int n = 0;
        try {
            Object screen = XposedHelpers.callMethod(frag, "getPreferenceScreen");
            if (screen == null) return 0;
            int cnt = (Integer) XposedHelpers.callMethod(screen, "getPreferenceCount");
            XposedBridge.log("[SBPlus] hidePrivacyCategory: screen preferences = " + cnt);
            for (int i = 0; i < cnt; i++) {
                Object pref = XposedHelpers.callMethod(screen, "getPreference", i);
                if (pref == null) continue;
                CharSequence title = (CharSequence) XposedHelpers.callMethod(pref, "getTitle");
                String ts = title == null ? "<null>" : title.toString().trim();
                String cls = pref.getClass().getName();
                Object key = XposedHelpers.callMethod(pref, "getKey");
                XposedBridge.log("[SBPlus]   pref[" + i + "] cls=" + cls + " key=" + key + " title=" + ts);
                // 匹配「隐私」「Privacy」标题的分类（无 key）。
                if (!("隐私".equals(ts) || "Privacy".equals(ts))) continue;
                if (key != null) continue;
                boolean isCategory = cls.contains("PreferenceCategory");
                if (!isCategory) continue;
                XposedHelpers.callMethod(pref, "setVisible", false);
                n++;
            }
            XposedBridge.log("[SBPlus] hidePrivacyCategory hidden=" + n);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hidePrivacyCategory error: " + t);
        }
        return n;
    }

    private void hookRegionTouchScroll(ClassLoader cl) {
        // [已禁用] 早期误判 SeslRecyclerView 会掉 ACTION_MOVE 导致滑不动，
        // 于是额外 scrollBy 补偿；但实际三星原生滚动正常，补偿造成“双倍滚动→跳动”。
        // 现今设备原生滚动已可正常工作，这里不再额外补偿。保留方法仅为避免调用处报错。
        XposedBridge.log("[SBPlus] region touch scroll compensation DISABLED (native scroll works)");
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
                                // 油猴脚本：总开关开启时，优先拦截 .user.js，自己下载保存，不走下载桥。
                                boolean isUs = isUserscriptEnabled();
                                boolean isUjs = isUserScriptUrl(meta.url, meta.fileName);
                                XposedBridge.log("[SBPlus] pre-download check: enabled=" + isUs + " isUserJs=" + isUjs + " url=" + meta.url);
                                if (isUs && isUjs) {
                                    XposedBridge.log("[SBPlus] .user.js detected (pre-download): " + meta.url);
                                    android.widget.Toast.makeText(sAppContext, "正在安装脚本...", android.widget.Toast.LENGTH_SHORT).show();
                                    downloadUserscriptToDir(meta.url);
                                    // 关闭空白 tab / 告知 native 拒绝下载，避免回退导航。
                                    try {
                                        XposedHelpers.callMethod(param.thisObject, "ignoreDownload", guid, info);
                                    } catch (Throwable ig) {
                                        XposedBridge.log("[SBPlus] ignoreDownload failed: " + ig);
                                    }
                                    param.setResult(null);
                                    return;
                                }
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
                                // 油猴脚本：拦截 .user.js 下载，自动保存到脚本目录。
                                if (isUserscriptEnabled() && isUserScriptUrl(meta.url, meta.fileName)) {
                                    XposedBridge.log("[SBPlus] .user.js detected, intercept: " + meta.url);
                                    android.widget.Toast.makeText(sAppContext, "正在安装脚本...", android.widget.Toast.LENGTH_SHORT).show();
                                    downloadUserscriptToDir(meta.url);
                                    param.setResult(null);
                                    return;
                                }
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

