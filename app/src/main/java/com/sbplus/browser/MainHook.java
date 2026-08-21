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

    /** 当前 hook 实例(供静态 JS 桥回调调用实例方法)。 */
    public static volatile MainHook sInstance;


    // ================= 多语言(跟随系统语言) =================
    // 浏览器进程读不到模块的 strings.xml 资源,因此用内置中英双语字典,
    // 根据系统 Locale 返回对应语言。默认英文,中文返回中文。
    private static String T(String zh, String en) {
        try {
            String lang = java.util.Locale.getDefault().getLanguage();
            if ("zh".equals(lang)) return zh;
        } catch (Throwable t) {
            // ignore
        }
        return en;
    }

    // 三语言版本:zh/en/ja
    private static String T3(String zh, String en, String ja) {
        try {
            String lang = java.util.Locale.getDefault().getLanguage();
            if ("zh".equals(lang)) return zh;
            if ("ja".equals(lang)) return ja;
        } catch (Throwable t) {
            // ignore
        }
        return en;
    }


    private static volatile boolean sInThemeText = false;

    // Global application Context (captured from SBrowserApplication.onCreate).
    private static volatile Context sAppContext;
    private static volatile int sBgDumpCount = 0;
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
    private static final String KEY_UA_GROUPS = "ua_groups";
    private static final String KEY_UA_CUSTOM = "ua_custom";
    private static final String KEY_UA_GROUP_PREFIX = "ua_grp_";
    private static final String ARG_PAGE = "sbplus_page";
    private static final String PAGE_DOWNLOADER_PICKER = "downloader_picker";
    private static final String PAGE_REGION_PICKER = "region_picker";
    private static final String PAGE_UA_PICKER = "ua_picker";
    private static final String PAGE_CLEAN_SETTINGS_PICKER = "clean_settings_picker";
    private static final String PAGE_VIDEO_BG_PICKER = "video_bg_picker";
    private static final String PAGE_SNIFF_SETTINGS = "sniff_settings";
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
    // 模块自身版本号(编译期确定,连 app/build.gradle 的 versionName)。
    // 浏览器进程无法加载 BuildConfig,这里作为 prefs 缺失时的兜底。
    private static final String APP_VERSION = "2.3";
    private static final String KEY_ENABLE_HOME_CLEAR_TEXT = "enable_home_clear_text";
    private static final String KEY_ENABLE_HOME_MOVE_BTN = "enable_home_move_btn";
    private static final String KEY_ENABLE_USERSCRIPT = "enable_userscript";
    private static final String KEY_ENABLE_SNIFF = "enable_sniff";
    private static final String KEY_DISABLED_USERSCRIPTS = "disabled_userscripts";

    /** 当前详情页绑定的脚本文件名(供 setChecked hook 写回 prefs)。 */
    private static String sDetailFileName = null;
    private static final int REQUEST_USERSCRIPT_PICK = 61002;
    public static final int REQUEST_FONT_PICK = 61004;
    public static final int REQUEST_HOME_LOGO_PICK = 61005;
    /** 主页美化页 Logo 区引用(用于添加后刷新): [screen, ctx, cl]。 */
    public static Object[] sHomeLogoScreen = null;
    private static int sHomeLogoSbHeight;
    private static android.app.Dialog sHomeLogoPageDlg;
    private static Runnable sHomeLogoPageRebuild;
    private static android.view.View sHomeLogoSbView;
    private static android.view.View sHomeLogoBgView;
    private static android.widget.ImageView sHomeLogoIv;
    private static boolean sHomeLogoAnimHookDone;
    private static float sHomeLogoSbInitY = -1f;
    private static android.view.Choreographer.FrameCallback sLogoFrameCb;
    private static boolean sLogoFrameRunning;
    private static float sLogoSbBaseTop = -1f;
    private static float sLogoBaseTop = -1f;
    private static float sLogoSbPrevTop = -1f;
    private static final android.view.ViewTreeObserver.OnPreDrawListener sLogoPreDraw = new android.view.ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            try {
                if (sHomeLogoSbView == null && sHomeLogoIv != null) {
                    try { logoSizeLimitStatic(sHomeLogoIv); } catch (Throwable ignored) {}
                }
                if (sHomeLogoSbView == null || sHomeLogoIv == null || sHomeLogoIv.getParent() == null) {
                    sLogoSbBaseTop = -1f;
                    sLogoSbPrevTop = -1f;
                    return true;
                }
                if (!HomeLogoHelper.isFollow(sHomeLogoIv.getContext())) return true;
                int[] sbLoc = new int[2];
                int[] lgLoc = new int[2];
                sHomeLogoSbView.getLocationInWindow(sbLoc);
                sHomeLogoIv.getLocationInWindow(lgLoc);
                if (sLogoSbPrevTop < 0f) {
                    sLogoSbPrevTop = sbLoc[1];
                } else {
                    float delta = sbLoc[1] - sLogoSbPrevTop;
                    if (Math.abs(delta) > 0.3f) {
                        sHomeLogoIv.setTranslationY(sHomeLogoIv.getTranslationY() + delta);
                    }
                    sLogoSbPrevTop = sbLoc[1];
                }
            } catch (Throwable ignored) {}
            return true;
        }
    };
    private static final String KEY_USERSCRIPT_SOURCES = "userscript_sources";

    // Default target downloaders (overridable).
    private static final String DEFAULT_ADM_PACKAGE = "com.dv.adm";
    private static final String DEFAULT_1DM_PACKAGE = "idm.internet.download.manager";
    private static final String DEFAULT_IDM_PLUS_PACKAGE = "idm.internet.download.manager.plus";

    private static final String[][] PRESET_DOWNLOADERS = new String[][]{
            {T("ADM(高级下载管理器)", "ADM (Advanced Download Manager)"), DEFAULT_ADM_PACKAGE},
            {T("1DM(Internet Download Manager)", "1DM (Internet Download Manager)"), DEFAULT_1DM_PACKAGE},
            {T("IDM+(Internet Download Manager Plus)", "IDM+ (Internet Download Manager Plus)"), DEFAULT_IDM_PLUS_PACKAGE},
    };

    // User-Agent presets for the "浏览器标识" (UA override) feature.
    // label -> full UA string.
    private static final String[][] PRESET_UAS = new String[][]{
            {T("桌面 Chrome(Windows)", "Desktop Chrome (Windows)"), "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"},
            {T("Android Chrome(手机)", "Android Chrome (Mobile)"), "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"},
            {"iPhone Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"},
    };

    // 随机浏览器标识:每次启动随机刷新的 UA 池(覆盖手机/电脑 × 多系统 × 多浏览器)。
    // Android 9~17、iOS 15.0~18.2、Windows/macOS/Linux;浏览器含 Chrome/Firefox/Edge/Safari/Opera/Vivaldi/Brave/UC 等。
        // UA 随机池分组元数据: {组名, 起始索引, 结束索引(不含)}。与下方 RANDOM_UAS 注释分组一一对应。
    private static final String[][] UA_GROUPS = new String[][]{
            {T3("Android 手机 (Chrome)","Android Phone (Chrome)","Androidスマホ (Chrome)"), "0", "16"},
            {T3("Android 其他浏览器","Android Other Browsers","Android他ブラウザ"), "16", "23"},
            {T3("iPhone / iPad","iPhone / iPad","iPhone / iPad"), "23", "35"},
            {T3("桌面 Windows","Desktop Windows","デスクトップ Windows"), "35", "45"},
            {T3("桌面 macOS","Desktop macOS","デスクトップ macOS"), "45", "51"},
            {T3("桌面 Linux","Desktop Linux","デスクトップ Linux"), "51", "55"},
    };
private static final String[] RANDOM_UAS = new String[]{
            // -- Android 手机 --
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
            // -- iPhone / iPad --
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
            // -- 桌面 Windows --
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
            // -- 桌面 macOS --
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
            // -- 桌面 Linux --
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 OPR/86.0.4363.32",
    };

    // 主设置页(settings_fragment.xml)的所有可屏蔽项目:preference key -> 中文标题。
    // key 以 "@" 开头的是特殊项(非 preference):@search = 搜索框、@update_card = 更新提示卡片。
    private static final String[][] SETTINGS_ITEMS = new String[][]{
            {"@search", T("顶部的搜索", "Top search bar")},
            {"@update_card", T("更新提示卡片", "Update notice card")},
            {"pref_parental_control_notice", T("家庭组织者管理提示", "Parental controls notice")},
            {"cloud_sync", T("与三星云同步", "Sync with Samsung Cloud")},
            {"pref_browsing_assist", T("浏览助手", "Browsing assist")},
            {"pref_drawing_assist", T("绘图助手", "Drawing assist")},
            {"set_homepage", T("主页", "Homepage")},
            {"set_search_engine", T("地址栏搜索", "Address bar search")},
            {"pref_auto_close_unused_tabs", T("自动关闭未使用的页面", "Auto-close unused tabs")},
            {"layout_and_menu", T("布局和菜单", "Layout and menus")},
            {"display", T("网页查看和滚动", "Pages and scrolling")},
            {"privacy", T("安全与隐私", "Privacy and security")},
            {"personal_data", T("个人浏览数据", "Personal browsing data")},
            {"sites_and_contents", T("网站和下载", "Sites and downloads")},
            {"pref_notifications", T("通知", "Notifications")},
            {"useful_features", T("实用功能", "Useful features")},
            {"pref_privacy_notice", T("隐私声明", "Privacy notice")},
            {"notice_board", T("隐私声明历史记录", "Privacy notice history")},
            {"pref_permissions", T("权限", "Permissions")},
            {"pref_leave_internet", T("停止使用三星浏览器", "Stop using Samsung Internet")},
            {"about", T("关于三星浏览器", "About Samsung Internet")},
            {"pref_contact_us", T("联系我们", "Contact us")},
    };

    // Country/region ISO codes for the "锁定国家/地区" feature (region lock).
    // Mirrors Samsung Browser's own "Feature variation test > Country iso code" options
    // (res/values/arrays.xml pref_country_iso_code_values).
    private static final String[][] PRESET_REGIONS = new String[][]{
            {T("阿根廷", "Argentina"), "AR"},
            {T("巴西", "Brazil"), "BR"},
            {T("加拿大", "Canada"), "CA"},
            {T("中国大陆", "Mainland China"), "CN"},
            {T("德国", "Germany"), "DE"},
            {T("西班牙", "Spain"), "ES"},
            {T("法国", "France"), "FR"},
            {T("英国", "United Kingdom"), "GB"},
            {T("印度", "India"), "IN"},
            {T("意大利", "Italy"), "IT"},
            {T("日本", "Japan"), "JP"},
            {T("韩国", "South Korea"), "KR"},
            {T("俄罗斯", "Russia"), "RU"},
            {T("土耳其", "Turkey"), "TR"},
            {T("美国", "United States"), "US"},
            {T("越南", "Vietnam"), "VN"},
            {T("其他", "Other"), "Other"},
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

    // 油猴脚本注入去重:realTab -> 已注入的 URL(避免 onLoadFinished 重复触发重复注入)。
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

    // 精简设置页:每项的 CheckBox(key -> checkbox),用于回显勾选状态。
    private static final java.util.Map<String, android.widget.CheckBox> sCleanCheckBoxes =
            new java.util.concurrent.ConcurrentHashMap<String, android.widget.CheckBox>();

    // Whether the downloader picker sub-page is currently the shown page (tracked by us, since
    // Samsung's getTopFragment() keys off back-stack count which is wrong for backstack-less pages).
    private static volatile boolean sInPickerPage;
    private static volatile String sCurrentPickerPage; // 精确记录当前子页,用于返回逻辑(详情/列表往返)
    // 油猴脚本菜单:追踪当前页面注入的脚本(url -> 已注入的脚本名列表)和当前 tab 对象。
    private static final java.util.Map<String, java.util.List<String>> sActiveScriptsByUrl = new java.util.HashMap<String, java.util.List<String>>();
    private static volatile Object sCurrentRealTab;
    private static volatile String sCurrentUrl;
    private static final java.util.Map<String, String> requireCache = new java.util.HashMap<String, String>(); // @require 库缓存:url -> js 内容
    private static final java.util.Map<String, String> resourceCache = new java.util.HashMap<String, String>(); // @resource 资源缓存:name -> 内容
    private static volatile boolean sRegionPageActive;
    // 资源嗅探状态:JS 回调写入,主线程等待读取
    private static volatile String sSniffedMediaJson = null;
    private static volatile boolean sSniffPending = false;
    /** 网络层嗅探收集的媒体 URL 列表(线程安全)。 */
    private static final java.util.Set<String> sNetworkSniffedUrls = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private static volatile android.app.Activity sSniffActivity = null;
    private static final Object sSniffLock = new Object();
    @Override
    public void initZygote(StartupParam startupParam) {
        prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        sInstance = this;

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
    hookNetworkSniff(lpparam.classLoader);
        hookThemeHook(lpparam.classLoader);
        hookGlobalFont(lpparam.classLoader);

        // ===== 临时诊断: 定位"页面背景变蓝"来源 (hook setColorFilter 蓝色) =====
        try {
            final int[] diagCount = { 0 };
            XposedHelpers.findAndHookMethod("android.graphics.drawable.Drawable", lpparam.classLoader,
                "setColorFilter", int.class, android.graphics.PorterDuff.Mode.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (diagCount[0] > 60) return;
                        try {
                            int col = ((Integer) param.args[0]).intValue();
                            if (col == 0xFF03A9F4) {
                                Object d = param.thisObject;
                                String dn = d.getClass().getName();
                                if (dn.contains("BitmapDrawable") || dn.contains("LayerDrawable")
                                        || dn.contains("GradientDrawable") || dn.contains("ColorDrawable")) {
                                    diagCount[0]++;
                                    XposedBridge.log("[SBPlus] DIAG-BLUE setColorFilter on " + dn + " (" + diagCount[0] + ")");
                                    if (diagCount[0] <= 6) {
                                        java.lang.StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                        StringBuilder sb2 = new StringBuilder("[SBPlus] DIAG-BLUE stack:");
                                        int n = 0;
                                        for (java.lang.StackTraceElement e : st) {
                                            if (e.getClassName().startsWith("com.sbplus")) {
                                                sb2.append(" -> " + e.getClassName().substring(e.getClassName().lastIndexOf('.')+1) + "." + e.getMethodName() + ":" + e.getLineNumber());
                                                if (n++ > 5) break;
                                            }
                                        }
                                        XposedBridge.log(sb2.toString());
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable e) {
            XposedBridge.log("[SBPlus] DIAG hook fail: " + e);
        }
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
     * radio - RadioPreferenceGroup.setChecked(key) is the mutual-exclusion entry point, so we
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
     * "自定义下载器" row (key = sbplus_dl_custom) - no dialog, direct on-row input.
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
                    try {
                        applySettingsRowBg(param.thisObject, param.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] applySettingsRowBg error: " + t);
                    }
                }
            });
            XposedBridge.log("[SBPlus] onBindViewHolder hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookInlineEdit failed: " + t);
        }
    }

    /** Decorate a picker row: preset rows get a radio dot; the custom row gets an EditText. */
    /** 设置页栏目背景: 对设置页的 preference 行背景上色(S_SETTINGS_BG)。 */
    private void applySettingsRowBg(Object preference, Object holder) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            int bg = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SETTINGS_BG);
            if (bg == -1) return;
            Object itemView = XposedHelpers.getObjectField(holder, "itemView");
            if (!(itemView instanceof android.view.View)) return;
            if (!isInSettingsScreen((android.view.View) itemView)) return;
            android.view.View root = (android.view.View) itemView;
            // 只对"圆角栏目块"上色: root 的 SeslRippleDrawable(圆角背景)
            // 跳过栏目标题行(ColorDrawable / AppCompatTextView)= 横条, 保持原样
            try {
                android.graphics.drawable.Drawable d = root.getBackground();
                if (d == null) return;
                String dcls = d.getClass().getName();
                boolean isRounded = dcls.contains("Sesl") || dcls.contains("Ripple") || dcls.contains("Rounded");
                if (!isRounded) return; // 普通 ColorDrawable = 横条/标题, 跳过
                try { d.setTint(bg); } catch (Throwable ignoredT) {}
                try { d.setColorFilter(new android.graphics.PorterDuffColorFilter(bg, android.graphics.PorterDuff.Mode.SRC_IN)); } catch (Throwable ignoredC) {}
                root.invalidate();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean isSniffPageKey(String key) {
        // 资源嗅探子页的下载设置条目(普通设置,非单选)固定 key, 注入圆点时需排除
        return "sbplus_dl_mode".equals(key)
                || "sbplus_dl_threads".equals(key)
                || "sbplus_dl_parallel".equals(key)
                || "sbplus_dl_list".equals(key)
                || "sbplus_dl_convertmp4".equals(key);
    }

    private void decoratePickerRow(Object preference, Object holder) {
        try {
            String key = (String) XposedHelpers.callMethod(preference, "getKey");
            if (key == null) return;
            if (key.startsWith("sbplus_dl_") && !isSniffPageKey(key)) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;
                if ("sbplus_dl_custom".equals(key)) {
                    // 自定义下载器行: 选中圆点 + 内联编辑
                    injectRadioDot(root, key);
                    injectInlineEdit(root, key);
                } else {
                    // 预设下载器行: 注入选中圆点(单选标记)
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
            } else if ("sbplus_userscript_detail_enable".equals(key)) {
                // 详情页"启用脚本"开关:onBindViewHolder 渲染时强制同步存储状态,
                // 修复 SwitchPreferenceCustom 在 addPreference 前 setChecked 不刷新 UI 的问题
                try {
                    Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                    if (itemView instanceof android.view.View) {
                        android.widget.Switch sw = findChildSwitch((android.view.View) itemView);
                        String fn = sDetailFileName;
                        if (sw != null && fn != null) {
                            sw.setChecked(isUserscriptFileEnabled(fn));
                            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                                    try { setUserscriptFileEnabled(sDetailFileName, isChecked); } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }
                } catch (Throwable ignored) {}
            } else if (key.startsWith("sbplus_clean_")) {
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View root = (android.view.View) itemView;
                injectCleanCheckBox(root, key);
                // 压缩行高 + 字号,让 23 项尽量一屏放下。
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
            } else if ("sbplus_dl_convertmp4".equals(key)) {
                // 视频资源转 MP4:确保右侧 Switch 与存储状态同步,并隐藏可能的左侧图标/圆点
                try {
                    Object itemViewX = XposedHelpers.getObjectField(holder, "itemView");
                    if (!(itemViewX instanceof android.view.View)) return;
                    android.view.View root = (android.view.View) itemViewX;
                    // 隐藏左侧图标区(圆点/竖线可能来源)
                    try {
                        android.view.View iconFrame = root.findViewById(android.R.id.icon);
                        if (iconFrame != null) iconFrame.setVisibility(android.view.View.GONE);
                    } catch (Throwable ignored) {}
                    try {
                        android.view.View iconFrame2 = root.findViewById(android.R.id.icon_frame);
                        if (iconFrame2 != null) iconFrame2.setVisibility(android.view.View.GONE);
                    } catch (Throwable ignored) {}
                    // 同步 Switch 状态 + 挂钩持久化
                    android.widget.Switch sw2 = findChildSwitch(root);
                    if (sw2 != null) {
                        final android.content.Context c2 = root.getContext();
                        boolean on = c2.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE)
                                .getBoolean("dl_convert_mp4", true);
                        sw2.setChecked(on);
                        sw2.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                                try {
                                    c2.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("dl_convert_mp4", isChecked).commit();
                                    XposedBridge.log("[SBPlus] convert-mp4(switch) -> " + isChecked);
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                } catch (Throwable t2) { XposedBridge.log("[SBPlus] convert-mp4 row err: " + t2); }
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
                edit.setHint(T("输入包名,如 com.dv.adm", "Enter package name, e.g. com.dv.adm"));
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
            android.widget.Toast.makeText(edit.getContext(), T("已启用: ", "Enabled: ") + v,
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
                edit.setHint(T("输入 UA 字符串", "Enter UA string"));
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
            android.widget.Toast.makeText(edit.getContext(), T("已启用自定义 UA", "Custom UA enabled"),
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
                            try {
                                registerDownloadListReceiver(sAppContext);
                            } catch (Throwable ignore) {}
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

            // 离开地区页时复位 sRegionPageActive,避免返回后在其它页面误触发滚动补偿。
            // 注:onDestroyView 在新版三星里可能被移到父类/改名,单独容错,失败不影响其余 hook。
            try {
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
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] onDestroyView hook failed(ignored): " + t);
            }

            XposedBridge.log("[SBPlus] PreferenceFragmentCustom.onCreatePreferences hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] submenu hook failed: " + t);
        }

        // 精简设置页:两列网格。三星在 onCreateLayoutManager 里创建默认
        // LinearLayoutManager,只有在这里替换才不会被后续覆盖。
        try {
            // onCreateLayoutManager 定义在父类 H2/A(PreferenceFragmentCompat 的混淆名),
            // 不在 PreferenceFragmentCustom 自身,必须 hook 父类。
            Class<?> prefFragCls = findPreferenceParent(cl);
            if (prefFragCls == null) {
                XposedBridge.log("[SBPlus] onCreateLayoutManager skipped: parent not found");
                return;
            }
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
            // 长列表/选择器子页:统一底部加 padding,避免最后一项被底部栏遮挡。
            // (除 region_picker 外,它走 collapseAppBar 顶部折叠方案,不叠加 bottom padding)
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

    /** 需要底部 padding 的长列表/选择器子页(region_picker 走 collapseAppBar,不在此列)。 */
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

    /** 给列表页的 RecyclerView 底部加 padding,确保最后一项能完整滚出(不被底部栏遮挡)。 */
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
            // createPreferenceScreen 被混淆成 a(Context) 等名,用多候选回退自适应
            Object newScreen = callMethodByCandidates(pm,
                    new String[]{"createPreferenceScreen", "a", "b", "c"},
                    new Class<?>[]{Context.class}, new Object[]{ctx});
            if (newScreen == null) {
                XposedBridge.log("[SBPlus] submenu could not create PreferenceScreen (all candidates failed)");
                return;
            }
            XposedHelpers.callMethod(frag, "setPreferenceScreen", newScreen);
            screen = newScreen;
        }
        if (screen == null) {
            XposedBridge.log("[SBPlus] submenu could not create PreferenceScreen");
            return;
        }

        ClassLoader cl = fragCls.getClassLoader();

        if (PAGE_DOWNLOADER_PICKER.equals(page) || PAGE_REGION_PICKER.equals(page) || PAGE_SNIFF_SETTINGS.equals(page)) {
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
        } else if (PAGE_SNIFF_SETTINGS.equals(page)) {
            injectSniffSettingsPicker(ctx, cl, screen);
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

            // 「资源嗅探」作为可点击进入子页的入口(带右侧指示竖线箭头)。
            Object sniffPref = buildSniffSwitch(ctx, cl);
            boolean addedSniff = (Boolean) XposedHelpers.callMethod(screen, "addPreference", sniffPref);
            XposedBridge.log("[SBPlus] sniff item injected: " + addedSniff);

            Object pref = buildExternalDownloaderSwitch(ctx, cl);
            boolean added = (Boolean) XposedHelpers.callMethod(screen, "addPreference", pref);
            XposedBridge.log("[SBPlus] submenu item injected: " + added);

            Object gridPref = buildGridMenuSwitch(ctx, cl);
            boolean addedGrid = (Boolean) XposedHelpers.callMethod(screen, "addPreference", gridPref);
            XposedBridge.log("[SBPlus] grid menu item injected: " + addedGrid);

            // -- 主页美化入口 --
            try {
                Class<?> homePrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object homePref = XposedHelpers.newInstance(homePrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(homePref, "setTitle", T("主页美化", "Homepage Beautify"));
                XposedHelpers.callMethod(homePref, "setKey", "sbplus_home_beautify");
                XposedHelpers.callMethod(homePref, "setSummary", T("视频背景 / 搜索框文字 / 添加快捷方式按钮", "Video background / search text / add shortcut button"));
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

            // -- Cookie 管理入口 --
            try {
                Class<?> cookiePrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object cookiePref = XposedHelpers.newInstance(cookiePrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(cookiePref, "setTitle", T("Cookie 管理", "Cookie Manager"));
                XposedHelpers.callMethod(cookiePref, "setKey", "sbplus_cookie_manager");
                XposedHelpers.callMethod(cookiePref, "setSummary", T("查看 / 修改网页 Cookie(登录态)", "View / edit website cookies (login state)"));
                try {
                    Class<?> listenerType = listenerParamType(cookiePref.getClass(), "setOnPreferenceClickListener");
                    Object onCookieClick = java.lang.reflect.Proxy.newProxyInstance(cl,
                            new Class[]{listenerType},
                            new java.lang.reflect.InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                    try {
                                        if (m.getName().equals("onPreferenceClick")) {
                                            Object clicked = args[0];
                                            Object actObj = XposedHelpers.callMethod(clicked, "getContext");
                                            if (actObj instanceof android.app.Activity) {
                                                showCookieDialog((android.app.Activity) actObj);
                                            }
                                            return Boolean.TRUE;
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[SBPlus] cookie navigate error: " + t);
                                    }
                                    return Boolean.FALSE;
                                }
                            });
                    XposedHelpers.callMethod(cookiePref, "setOnPreferenceClickListener", onCookieClick);
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] cookie click bind failed: " + t);
                }
                XposedHelpers.callMethod(screen, "addPreference", cookiePref);
                XposedBridge.log("[SBPlus] cookie entry injected");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] cookie entry error: " + t);
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

            // -- 书签管理入口 --
            final Context bmFinalCtx = ctx;
            try {
                Class<?> bmPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object bmPref = XposedHelpers.newInstance(bmPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(bmPref, "setTitle", T("书签管理", "Bookmark Manager"));
                XposedHelpers.callMethod(bmPref, "setKey", "sbplus_bookmark_manager");
                XposedHelpers.callMethod(bmPref, "setSummary", T("导入 / 导出书签(Chrome/Edge/Firefox 通用)", "Import / export bookmarks (Chrome/Edge/Firefox)"));
                bindPreferenceClick(bmPref, cl, new Runnable() { public void run() { showBookmarkManagerDialog(bmFinalCtx); } });
                XposedHelpers.callMethod(screen, "addPreference", bmPref);
                XposedBridge.log("[SBPlus] bookmark manager item injected");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] bookmark manager inject error: " + t);
            }

            // -- 版本号(自动探测更新)--
            final Context verFinalCtx = ctx;
            try {
                Class<?> verPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                final Object verPref = XposedHelpers.newInstance(verPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(verPref, "setTitle", T("版本号", "Version"));
                XposedHelpers.callMethod(verPref, "setKey", "sbplus_version");
                String localVer = readModuleVersion();
                XposedHelpers.callMethod(verPref, "setSummary", T("当前 ", "Current ") + localVer + T("(自动检测更新中...)", " (checking for updates...)"));
                bindPreferenceClick(verPref, cl, new Runnable() { public void run() { checkUpdateInteractive(verFinalCtx); } });
                XposedHelpers.callMethod(screen, "addPreference", verPref);
                // 后台自动检测最新版本,有新版本则在 summary 提示
                final String localVerF = localVer;
                new Thread(new Runnable() { public void run() {
                    try {
                        String remote = checkLatestVersionOnline();
                        if (remote != null && versionNewer(remote, localVerF)) {
                            String disp = stripV(remote);
                            final String msg = T("当前 ", "Current ") + localVerF + T(",有新版 ", ", new version ") + disp + T(",点击更新", ". Tap to update");
                            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                            main.post(new Runnable() { public void run() {
                                try { XposedHelpers.callMethod(verPref, "setSummary", msg); } catch (Throwable ignored) {}
                            }});
                        } else {
                            final String msg = T("当前 ", "Current ") + localVerF + T("(已是最新)", " (up to date)");
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

            // -- 项目地址 --
            final Context projFinalCtx = ctx;
            try {
                Class<?> projPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
                Object projPref = XposedHelpers.newInstance(projPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(projPref, "setTitle", T("项目地址", "Project URL"));
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

    /** 从模块 prefs 读版本号(MainActivity 写入),读不到则用编译期常量 APP_VERSION。 */
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

    /** 查询 GitHub 最新 release 的 tag(如 v2.1),失败返回 null。 */
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

    /** 手动检测更新:后台查 GitHub 最新 release,有更新弹确认框。 */
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
                        android.widget.Toast.makeText(ctx, T("检测失败:", "Check failed: ") + fErr, android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean newer = versionNewer(fTag, fLocal);
                    if (newer) {
                        showUpdateDialog(ctx, fTag, fBody, fApk);
                    } else {
                        android.widget.Toast.makeText(ctx, T("已是最新版本(", "Already up to date (") + fTag + T(")", ")"), android.widget.Toast.LENGTH_SHORT).show();
                    }
                }});
            }
        }).start();
    }

    /** 弹更新确认框,确认后浏览器打开下载地址。 */
    private void showUpdateDialog(final Context ctx, String tag, String body, final String apkUrl) {
        try {
            String note = body;
            if (note == null || note.trim().isEmpty()) note = T("(无更新说明)", " (no release notes)");
            if (note.length() > 500) note = note.substring(0, 500) + "...";
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle(T("发现新版本:", "New version: ") + tag);
            b.setMessage(T("当前版本:", "Current version: ") + readModuleVersion() + "\n\n" + note);
            b.setPositiveButton(T("下载更新", "Download update"), new android.content.DialogInterface.OnClickListener() {
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
            b.setNegativeButton(T("取消", "Cancel"), null);
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUpdateDialog error: " + t);
        }
    }

    /** 剥离 tag 前导的 'v',用于展示(如 v2.0 -> 2.0)。 */
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
     * onBindViewHolder hook (decoratePickerRow) - pure code, no XML inflation.
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

        // Custom downloader row - inline EditText injected in onBindViewHolder.
        Object custom = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(custom, "setTitle", T("自定义下载器", "Custom Downloader"));
        XposedHelpers.callMethod(custom, "setKey", "sbplus_dl_custom");
        boolean isCustom = !isPreset(current);
        XposedHelpers.callMethod(custom, "setSummary", isCustom ? (T("当前使用: ", "Current: ") + current) : T("输入包名并确认", "Enter package name and confirm"));
        bindCustomClick(custom, cl, screen);
        XposedHelpers.callMethod(screen, "addPreference", custom);

        XposedBridge.log("[SBPlus] downloader picker injected");
    }

    /** Fill the region picker sub-page: 17 country rows (radio dots), mirroring the
     *  downloader picker exactly (PreferenceCustom rows + injectRadioDot in onBindViewHolder).
     *  No ScrollView / custom layout - Samsung's own RecyclerView handles scrolling, theming,
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
                                            T("已选择: ", "Selected: ") + label, android.widget.Toast.LENGTH_SHORT).show();
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
                                            T("已选择: ", "Selected: ") + label, android.widget.Toast.LENGTH_SHORT).show();
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
    private void showCookieDialog(final android.app.Activity act) {
        try {
            final Context ctx = act;
            final java.util.List<String> hosts = CookieHelper.listHosts(ctx);
            final java.util.Map<String,Integer> countMap = new java.util.LinkedHashMap<>();
            for (String h : hosts) countMap.put(h, CookieHelper.readHostCookies(ctx, hostKeyOf(h)).size());

            int pad = dp(ctx, 10);
            final android.widget.EditText search = new android.widget.EditText(ctx);
            search.setHint(T("搜索网站域名...", "Search site domain..."));
            search.setSingleLine(true);
            search.setPadding(pad, pad, pad, pad);

            final java.util.List<String> fullList = new java.util.ArrayList<>(hosts);
            final java.util.List<String> shown = new java.util.ArrayList<>(hosts);
            final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                    ctx, android.R.layout.simple_list_item_1, new java.util.ArrayList<String>());

            android.widget.ListView lv = new android.widget.ListView(ctx);
            lv.setAdapter(adapter);
            lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                    String h = shown.get(position);
                    editSiteCookies(act, h);
                }
            });

            android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.addView(search);
            box.addView(lv, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle(T("Cookie 管理 (", "Cookie Manager (") + hosts.size() + T(" 个网站)", " sites)"));
            b.setView(box);
            b.setNegativeButton(T("关闭", "Close"), null);
            final android.app.AlertDialog dlg = b.create();

            final java.lang.Runnable refresh = new java.lang.Runnable() {
                @Override public void run() {
                    String q = search.getText().toString().trim().toLowerCase();
                    shown.clear();
                    for (String h : fullList) {
                        if (q.isEmpty() || h.toLowerCase().contains(q)) shown.add(h);
                    }
                    adapter.clear();
                    for (String h : shown) {
                        Integer c = countMap.get(h);
                        adapter.add(h + "   (" + (c == null ? 0 : c) + ")");
                    }
                    adapter.notifyDataSetChanged();
                }
            };
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void afterTextChanged(android.text.Editable s) { refresh.run(); }
                @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
                @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            });
            if (shown.size() == 0) adapter.add(T("（无 Cookie）", "(no cookies)"));
            refresh.run();
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] cookie list error: " + t);
        }
    }

    private static String hostKeyOf(String host) { return host.startsWith(".") ? host : "." + host; }

    /** 编辑单个网站的 cookie(每行 name=value, 可保存/删/清空)。 */
    private void editSiteCookies(final android.app.Activity act, final String host) {
        try {
            final Context ctx = act;
            final String rawHost = hostKeyOf(host);
            int pad = dp(ctx, 12);
            android.widget.TextView hostLabel = new android.widget.TextView(ctx);
            hostLabel.setText(host);
            hostLabel.setTextSize(15);
            hostLabel.setTypeface(hostLabel.getTypeface(), android.graphics.Typeface.BOLD);

            final android.widget.EditText cookieInput = new android.widget.EditText(ctx);
            cookieInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            cookieInput.setSingleLine(false);
            cookieInput.setMinLines(12);
            cookieInput.setHint(T("每行一个 Cookie, 格式 name=value", "One cookie per line: name=value"));
            cookieInput.setPadding(pad, pad, pad, pad);
            loadHostInto(cookieInput, ctx, rawHost);

            android.widget.Button refreshBtn = new android.widget.Button(ctx);
            refreshBtn.setText(T("刷新列表", "Refresh"));
            refreshBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    loadHostInto(cookieInput, ctx, rawHost);
                }
            });

            android.widget.Button deleteBtn = new android.widget.Button(ctx);
            deleteBtn.setText(T("删除选中行", "Delete focus line"));

            android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.addView(hostLabel);
            android.widget.TextView hint = new android.widget.TextView(ctx);
            hint.setText(T("每行 name=value。改完点[保存]写回; 想清除某条就删掉该行再保存; [清空该站]删除全部。",
                    "name=value per line. Edit then Save. Delete a line to remove it. Clear removes all."));
            hint.setTextSize(12);
            hint.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
            box.addView(hint);
            box.addView(refreshBtn);
            box.addView(cookieInput);

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle(T("编辑 Cookie · ", "Cookies · ") + host);
            b.setView(box);
            b.setPositiveButton(T("保存", "Save"), null);
            b.setNeutralButton(T("清空该站", "Clear site"), null);
            b.setNegativeButton(T("返回", "Back"), null);
            final android.app.AlertDialog dlg = b.create();
            dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override public void onShow(android.content.DialogInterface dialogInterface) {
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            String editor = cookieInput.getText().toString();
                            java.util.List<String[]> kvs = new java.util.ArrayList<>();
                            for (String line : editor.split("\n")) {
                                String t = line.trim();
                                if (t.isEmpty()) continue;
                                int eq = t.indexOf('=');
                                if (eq > 0) kvs.add(new String[]{ t.substring(0, eq).trim(), t.substring(eq + 1).trim(), "/" });
                            }
                            // 先清空该站再全量写入(保证覆盖/删除一致性)
                            CookieHelper.clearHost(ctx, rawHost);
                            int n = CookieHelper.setCookies(ctx, rawHost, kvs);
                            toast(ctx, T("已写回 ", "Saved ") + n + T(" 个 Cookie", " cookie(s)"));
                            XposedBridge.log("[SBPlus] cookie saved n=" + n + " host=" + host + " (需刷新/重载页面生效)");
                            dlg.dismiss();
                        }
                    });
                    dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            int n = CookieHelper.clearHost(ctx, rawHost);
                            toast(ctx, T("已清除 ", "Cleared ") + n + T(" 个 Cookie", " cookie(s)"));
                            cookieInput.setText("");
                            XposedBridge.log("[SBPlus] cookie cleared n=" + n + " host=" + host);
                        }
                    });
                }
            });
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] cookie edit error: " + t);
        }
    }

    private static void loadHostInto(android.widget.EditText tv, Context ctx, String rawHost) {
        java.util.List<String[]> rows = CookieHelper.readHostCookies(ctx, rawHost);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) sb.append(r[0]).append("=").append(r[1]).append("\n");
        tv.setText(sb.toString());
        XposedBridge.log("[SBPlus] cookie load host=" + rawHost + " rows=" + rows.size());
    }

    private void toast(Context ctx, String msg) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    /**
     * Custom downloader input dialog.
     */
    private void showCustomDownloaderDialog(final android.app.Activity act) {
        final Context ctx = act;
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setHint(T("输入包名,例如 com.dv.adm", "Enter package name, e.g. com.dv.adm"));
        input.setSingleLine(true);
        input.setText(downloaderPackage());
        int pad = dp(ctx, 16);
        input.setPadding(pad, pad, pad, pad);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
        b.setTitle(T("自定义下载器", "Custom Downloader"));
        b.setMessage(T("输入下载器应用包名", "Enter the downloader app package name"));
        b.setView(input);
        b.setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                String p = input.getText().toString().trim();
                if (p.isEmpty()) {
                    android.widget.Toast.makeText(ctx, T("包名不能为空", "Package name cannot be empty"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                saveDownloaderPackage(p);
                android.widget.Toast.makeText(ctx, T("已保存: ", "Saved: ") + p,
                        android.widget.Toast.LENGTH_SHORT).show();
                XposedBridge.log("[SBPlus] custom downloader saved: " + p);
                LogWriter.log("picker", "custom downloader saved: " + p);
            }
        });
        b.setNegativeButton(T("取消", "Cancel"), null);
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

    /** 弹窗内多行文本的最大可用宽度(px):屏幕宽度的 85%。 */
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

        // SBPlus main entry - a plain tappable row (like "Homepage" or "Search engine")
        // that navigates into a real sub-menu page.
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", "SBPlus");
        XposedHelpers.callMethod(pref, "setKey", "sbplus_main");
        XposedHelpers.callMethod(pref, "setSummary", T("下载桥与增强功能", "Download bridge & enhancements"));

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

        XposedHelpers.callMethod(pref, "setTitle", T("启用外部下载器", "Enable external downloader"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_external_downloader");
        XposedHelpers.callMethod(pref, "setSummary", T("下载转交给第三方下载器(ADM/1DM/IDM+)", "Forward downloads to a third-party manager (ADM/1DM/IDM+)"));
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

        XposedHelpers.callMethod(pref, "setTitle", T("启用网格菜单", "Enable grid menu"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_grid_menu");
        XposedHelpers.callMethod(pref, "setSummary", T("更多菜单改为两行×5列网格,左右翻页", "More menu as a 2-row x 5-col grid, swipe to switch pages"));
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

        XposedHelpers.callMethod(pref, "setTitle", T("锁定国家/地区", "Lock country/region"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_region_lock");
        String code = regionCode();
        XposedHelpers.callMethod(pref, "setSummary", code.isEmpty() ? T("点击选择要锁定的国家/地区", "Tap to choose a country/region to lock") : (T("当前: ", "Current: ") + code));
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

    /** 随机浏览器标识:每次启动随机刷新 UA。 */
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

    /** 随机挑选一个 UA(每次启动调用一次,模拟"每次启动随机刷新")。 */
    private String randomUa() {
        try {
            java.util.Random rnd = new java.util.Random();
            java.util.List<String> plats = new java.util.ArrayList<>();
            if (getUaPlatEnabled("android")) plats.add("android");
            if (getUaPlatEnabled("ios")) plats.add("ios");
            if (getUaPlatEnabled("windows")) plats.add("windows");
            if (getUaPlatEnabled("macos")) plats.add("macos");
            if (getUaPlatEnabled("linux")) plats.add("linux");
            if (plats.isEmpty()) plats.add("android"); // fallback

            java.util.List<String> brws = new java.util.ArrayList<>();
            if (getUaBrwEnabled("chrome")) brws.add("chrome");
            if (getUaBrwEnabled("safari")) brws.add("safari");
            if (getUaBrwEnabled("edge")) brws.add("edge");
            if (getUaBrwEnabled("firefox")) brws.add("firefox");
            if (brws.isEmpty()) brws.add("chrome");

            String plat = plats.get(rnd.nextInt(plats.size()));
            // 过滤浏览器(iOS 只 Safari,Android 无 Safari)
            java.util.List<String> validBrw = new java.util.ArrayList<>();
            for (String b : brws) {
                if ("ios".equals(plat)) {
                    if ("safari".equals(b)) validBrw.add(b);
                } else if ("android".equals(plat)) {
                    if (!"safari".equals(b)) validBrw.add(b);
                } else {
                    validBrw.add(b);
                }
            }
            if (validBrw.isEmpty()) validBrw.add("chrome");
            String brw = validBrw.get(rnd.nextInt(validBrw.size()));

            return buildDynamicUaBv(plat, brw, rnd);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] randomUa error: " + t);
            return null;
        }
    }

    private String buildDynamicUaBv(String plat, String brw, java.util.Random rnd) {
        if ("android".equals(plat)) return buildAndroidUaBv(brw, rnd);
        if ("ios".equals(plat)) return buildIosUaBv(rnd);
        if ("windows".equals(plat)) return buildDesktopUaBv("windows", brw, rnd);
        if ("macos".equals(plat)) return buildDesktopUaBv("macos", brw, rnd);
        if ("linux".equals(plat)) return buildDesktopUaBv("linux", brw, rnd);
        return null;
    }

    private String buildAndroidUaBv(String brw, java.util.Random rnd) {
        String[] vers = splitComma(getUaParam("android_vers", "13,14,15,16,17,18"));
        String[] devs = splitComma(getUaParam("android_devs", "SM-G9910,Pixel 8,Pixel 9,M2012K11AC"));
        if (vers.length == 0) vers = new String[]{"15", "16", "17"};
        if (devs.length == 0) devs = new String[]{"SM-G9910", "Pixel 8"};
        String ver = vers[rnd.nextInt(vers.length)];
        String dev = devs[rnd.nextInt(devs.length)];
        String range = getUaParam("chrome_range", "90-150");
        int[] cr = parseRange(range, 90, 150);
        int major = cr[0] + rnd.nextInt(cr[1] - cr[0] + 1);
        int minor = rnd.nextInt(8);
        int build = 3000 + rnd.nextInt(2000);
        int patch = 100 + rnd.nextInt(200);

        if ("chrome".equals(brw)) {
            return "Mozilla/5.0 (Linux; Android " + ver + "; " + dev +
                ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                "." + build + "." + patch + " Mobile Safari/537.36";
        } else if ("edge".equals(brw)) {
            return "Mozilla/5.0 (Linux; Android " + ver + "; " + dev +
                ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                "." + build + "." + patch + " Mobile Safari/537.36 EdgA/" + major + "." + minor + "." + build;
        } else if ("firefox".equals(brw)) {
            return "Mozilla/5.0 (Android " + ver + "; Mobile; rv:" + major + ".0) Gecko/" + major + ".0 Firefox/" + major + ".0";
        }
        return null;
    }

    private String buildIosUaBv(java.util.Random rnd) {
        String[] vers = splitComma(getUaParam("ios_vers", "15.0,16.0,17.0,18.0"));
        if (vers.length == 0) vers = new String[]{"17.0", "18.0"};
        String ver = vers[rnd.nextInt(vers.length)];
        String v2 = ver.replace('.', '_');
        boolean ipad = rnd.nextBoolean();
        String dev = ipad ? "iPad" : "iPhone";
        String osName = ipad ? "CPU OS " : "CPU iPhone OS ";
        return "Mozilla/5.0 (" + dev + "; " + osName + v2 +
            " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/" + ver +
            " Mobile/15E148 Safari/604.1";
    }

    private String buildDesktopUaBv(String plat, String brw, java.util.Random rnd) {
        String[] tokens = splitComma(getUaParam("desktop_tokens",
            "Windows NT 10.0; Win64; x64,Macintosh; Intel Mac OS X 10_15_7,X11; Linux x86_64"));
        if (tokens.length == 0) tokens = new String[]{"Windows NT 10.0; Win64; x64"};
        String os = tokens[rnd.nextInt(tokens.length)];
        String range = getUaParam("chrome_range", "90-150");
        int[] cr = parseRange(range, 90, 150);
        int major = cr[0] + rnd.nextInt(cr[1] - cr[0] + 1);
        int minor = rnd.nextInt(8);
        int build = 3000 + rnd.nextInt(2000);
        int patch = 100 + rnd.nextInt(200);

        if ("firefox".equals(brw)) {
            return "Mozilla/5.0 (" + os + "; rv:" + major + ".0) Gecko/20100101 Firefox/" + major + ".0";
        } else if ("edge".equals(brw)) {
            return "Mozilla/5.0 (" + os + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                major + "." + minor + "." + build + "." + patch + " Safari/537.36 Edg/" + major + "." + minor + "." + build;
        } else if ("safari".equals(brw) && plat.equals("macos")) {
            return "Mozilla/5.0 (" + os + ") AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
        }
        // chrome fallback
        return "Mozilla/5.0 (" + os + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
            major + "." + minor + "." + build + "." + patch + " Safari/537.36";
    }

    private int[] parseRange(String s, int defMin, int defMax) {
        try {
            String[] p = s.split("-");
            if (p.length == 2) {
                int a = Integer.parseInt(p[0].trim());
                int b = Integer.parseInt(p[1].trim());
                return new int[]{a, b};
            }
        } catch (Throwable ignored) {}
        return new int[]{defMin, defMax};
    }


    /** 该分类是否有用户手动编辑过的 UA 列表(区别于内置 fallback 区间)。 */
    private boolean hasEditedGroup(int gi) {
        try {
            if (sAppContext != null) {
                String v = processPrefs(sAppContext).getString(KEY_UA_GROUP_PREFIX + gi, "");
                return v != null && v.length() > 0;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 按 BetterVia 思路动态合成 UA:平台->浏览器->模板填充随机成分。 */
    private String buildDynamicUa(int gi, java.util.Random rnd) {
        try {
            if (gi == 0 || gi == 1) return buildAndroidUa(gi == 1, rnd);
            if (gi == 2) return buildIosUa(rnd);
            if (gi == 3) return buildDesktopUa("windows", rnd);
            if (gi == 4) return buildDesktopUa("macos", rnd);
            if (gi == 5) return buildDesktopUa("linux", rnd);
            return buildAndroidUa(false, rnd);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] buildDynamicUa error: " + t);
            return null;
        }
    }

    /** Android UA:Chrome/Edge 模板随机版本号;Firefox 模板随机主版本。 */
    private String buildAndroidUa(boolean otherBrowsers, java.util.Random rnd) {
        try {
            String[] models = {"SM-G9910","SM-S9080","SM-S9180","SM-G9960","SM-S9010","SM-A5360",
                    "Pixel 7","Pixel 7 Pro","Pixel 8","Pixel 8 Pro","Pixel 9","Pixel 9 Pro","Pixel 6a",
                    "M2012K11AC","M2007J3SC","23046PNC9C","23127PN0CC","2211133C","22081212C",
                    "PGT-AN00","ALN-AL80","BRA-AL00","BKL-AL20","VOG-L29","ELS-AN00","LIO-AN00",
                    "CPH2581","PHN110","PJV110","PJG110","RMX3850","RMX3706","RMX3888","OnePlus 12","OnePlus ACE 3",
                    "LE2120","NE2210","PHB110","XQ-DQ72","XQ-CT72","V2357A","V2405A","V2429A",
                    "24090RA29C","24094RAD4C","25010PN30C","Redmi K70","Redmi Note 13 Pro"};
            String[] vers = {"13","14","14.5","15","15.1","15.2","15.3","16","16.1","16.2","17","17.1","18","18.1"};
            String model = models[rnd.nextInt(models.length)];
            String ver = vers[rnd.nextInt(vers.length)];
            int major = 90 + rnd.nextInt(60);          // Chrome 90..149
            int minor = 0 + rnd.nextInt(8);            // .0..7
            int build = 3000 + rnd.nextInt(2000);      // 3000..4999
            int patch = 100 + rnd.nextInt(200);        // 100..299
            if (!otherBrowsers) {
                // 分类0:Chrome
                return "Mozilla/5.0 (Linux; Android " + ver + "; " + model +
                        ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                        "." + build + "." + patch + " Mobile Safari/537.36";
            }
            // 分类1:随机 Chrome/Edge/Firefox/Opera
            int which = rnd.nextInt(4);
            if (which == 0) {
                return "Mozilla/5.0 (Linux; Android " + ver + "; " + model +
                        ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                        "." + build + "." + patch + " Mobile Safari/537.36";
            }
            if (which == 1) {
                return "Mozilla/5.0 (Linux; Android " + ver + "; " + model +
                        ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                        "." + build + "." + patch + " Mobile Safari/537.36 EdgA/" + major + "." + minor + "." + build + "." + patch;
            }
            if (which == 2) {
                return "Mozilla/5.0 (Android " + ver + "; " + model + "; rv:" + major +
                        ".0) Gecko/20100101 Firefox/" + major + ".0";
            }
            return "Mozilla/5.0 (Linux; Android " + ver + "; " + model +
                    ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor +
                    "." + build + "." + patch + " Mobile Safari/537.36 OPR/" + major + "." + minor + "." + build;
        } catch (Throwable t) { return null; }
    }

    /** iOS UA:Safari 模板随机 iOS 版本与设备。 */
    private String buildIosUa(java.util.Random rnd) {
        try {
            String[] vers = {"15.6.1","16.0","16.7.2","17.0","17.4","17.5","18.0","18.1","18.2","18.3","18.4"};
            String ver = vers[rnd.nextInt(vers.length)];
            String v2 = ver.replace('.', '_');
            boolean ipad = rnd.nextBoolean();
            String dev = ipad ? "iPad" : "iPhone";
            String osName = ipad ? "CPU OS " : "CPU iPhone OS ";
            int patch = 100 + rnd.nextInt(9000);
            return "Mozilla/5.0 (" + dev + "; " + osName + v2 + " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/" + ver + " Mobile/15E1" + (100 + rnd.nextInt(99)) + " Safari/604.1";
        } catch (Throwable t) { return null; }
    }

    /** 桌面 UA:Windows/macOS/Linux 模板 + Chrome/Edge/Firefox 随机版本。 */
    private String buildDesktopUa(String plat, java.util.Random rnd) {
        try {
            int major = 90 + rnd.nextInt(60);
            int minor = 0 + rnd.nextInt(8);
            int build = 3000 + rnd.nextInt(2000);
            int patch = 100 + rnd.nextInt(200);
            int which = rnd.nextInt(3);
            String os = "";
            if ("windows".equals(plat)) {
                String[] wv = {"Windows NT 10.0; Win64; x64","Windows NT 10.0; WOW64","Windows NT 11.0; Win64; x64","Windows NT 6.1; Win64; x64"};
                os = wv[rnd.nextInt(wv.length)];
            } else if ("macos".equals(plat)) {
                String[] mv = {"Macintosh; Intel Mac OS X 10_15_7","Macintosh; Intel Mac OS X 11_7_10","Macintosh; Intel Mac OS X 12_7_6","Macintosh; Intel Mac OS X 13_6_6","Macintosh; Intel Mac OS X 14_5"};
                os = mv[rnd.nextInt(mv.length)];
            } else {
                String[] lv = {"X11; Linux x86_64","X11; Ubuntu; Linux x86_64","X11; Fedora; Linux x86_64","X11; Linux x86_64; rv:"+major+".0"};
                os = lv[rnd.nextInt(lv.length)];
            }
            String base = "Mozilla/5.0 (" + os + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + "." + minor + "." + build + "." + patch + " Safari/537.36";
            if (which == 1) return base + " Edg/" + major + "." + minor + "." + build;
            if (which == 2) return "Mozilla/5.0 (" + os + "; rv:" + major + ".0) Gecko/20100101 Firefox/" + major + ".0";
            return base;
        } catch (Throwable t) { return null; }
    }

    /** 读取某分类最终生效的 UA 列表:用户编辑覆盖优先,否则用内置静态数组区间。 */
    private java.util.List<String> loadGroupUas(int gi) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_UA_GROUP_PREFIX + gi, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String k : raw.split("\n")) {
                        String t = k.trim();
                        if (!t.isEmpty()) list.add(t);
                    }
                    if (!list.isEmpty()) return list;
                }
            }
            // 兜底:内置静态数组 [start,end)
            if (gi >= 0 && gi < UA_GROUPS.length) {
                int s = Integer.parseInt(UA_GROUPS[gi][1]);
                int e = Integer.parseInt(UA_GROUPS[gi][2]);
                if (s < 0) s = 0; if (e > RANDOM_UAS.length) e = RANDOM_UAS.length;
                for (int i = s; i < e && i < RANDOM_UAS.length; i++) list.add(RANDOM_UAS[i]);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    /** 保存某分类用户编辑后的 UA 列表(每行一条,空列表则清除覆盖、回退内置)。 */
    private void saveGroupUas(int gi, java.util.List<String> list) {
        try {
            if (sAppContext != null) {
                java.util.List<String> clean = new java.util.ArrayList<String>();
                for (String k : list) { String t = k.trim(); if (!t.isEmpty()) clean.add(t); }
                if (clean.isEmpty()) {
                    processPrefs(sAppContext).edit().remove(KEY_UA_GROUP_PREFIX + gi).commit();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String k : clean) { if (sb.length() > 0) sb.append("\n"); sb.append(k); }
                    processPrefs(sAppContext).edit().putString(KEY_UA_GROUP_PREFIX + gi, sb.toString()).commit();
                }
            }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] save group UA error: " + t); }
    }

    private java.util.Set<Integer> loadEnabledUaGroups() {
        java.util.Set<Integer> set = new java.util.LinkedHashSet<Integer>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_UA_GROUPS, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String k : raw.split(",")) {
                        try { if (!k.trim().isEmpty()) set.add(Integer.parseInt(k.trim())); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private void saveEnabledUaGroups(java.util.Set<Integer> set) {
        try {
            if (sAppContext != null) {
                StringBuilder sb = new StringBuilder();
                for (Integer k : set) { if (sb.length() > 0) sb.append(","); sb.append(k); }
                processPrefs(sAppContext).edit().putString(KEY_UA_GROUPS, sb.toString()).commit();
            }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] save UA groups error: " + t); }
    }

    private java.util.List<String> loadCustomUas() {
        java.util.List<String> list = new java.util.ArrayList<String>();
        try {
            if (sAppContext != null) {
                String raw = processPrefs(sAppContext).getString(KEY_UA_CUSTOM, "");
                if (raw != null && !raw.isEmpty()) {
                    for (String k : raw.split("\n")) {
                        String t = k.trim();
                        if (!t.isEmpty()) list.add(t);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private void saveCustomUas(java.util.List<String> list) {
        try {
            if (sAppContext != null) {
                StringBuilder sb = new StringBuilder();
                for (String k : list) { if (sb.length() > 0) sb.append("\n"); sb.append(k.trim()); }
                processPrefs(sAppContext).edit().putString(KEY_UA_CUSTOM, sb.toString()).commit();
            }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] save custom UA error: " + t); }
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

    // ---- 精简设置页(屏蔽设置项) ----

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

    /** 切换某一项:勾选=加入屏蔽,取消=移除。 */
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

    /** 主开关:精简设置页。 */
    private Object buildCleanSettingsSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", T("精简设置页", "Streamlined settings"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_clean_settings");
        XposedHelpers.callMethod(pref, "setSummary", T("屏蔽设置页里不需要的项目", "Hide unneeded items in the settings page"));
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

    /** 主开关:屏蔽更新(清除更新通知/弹窗 + 阻断更新检查网络连接 + 屏蔽升级组件)。 */
    private Object buildBlockUpdateSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", T("屏蔽更新和小红点", "Block updates & red dots"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_block_update");
        XposedHelpers.callMethod(pref, "setSummary", T("彻底屏蔽浏览器的更新检查、更新弹窗与升级组件", "Block update checks, update dialogs and upgrade components"));
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

    /** 主页美化:去除主页搜索框内文字。 */
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

    /** 主页美化:移动添加快捷方式按钮到主页设置旁。 */
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

    /** 主开关:主页视频背景。 */
    private Object buildVideoBgSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);

        XposedHelpers.callMethod(pref, "setTitle", T("主页视频背景", "Homepage video background"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_video_bg");
        String path = videoBgPath();
        XposedHelpers.callMethod(pref, "setSummary",
                path.isEmpty() ? T("点击选择视频文件作为主页背景", "Tap to choose a video as homepage background") : (T("已设置视频背景", "Video background set")));
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

    /** 主页美化子页:视频背景 / 去搜索框文字 / 移动添加快捷方式按钮。 */
    // ================= 自定义主题色(替换莫奈) =================
    private void hookThemeHook(ClassLoader cl) {
        try {
            // 全局文字改色: 设置页/主页文字按 slot
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl,
                "setText", CharSequence.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        applyTextColor(param.thisObject);
                    }
                });
            // setTextColor 直接钩: 覆盖走 ColorStateList / 样式 的小字
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl,
                    "setTextColor", int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (sInThemeText) return;
                                if (!(param.thisObject instanceof android.widget.TextView)) return;
                                android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                                int c = themeTextColorFor(tv);
                                if (c == -1 || c == tv.getCurrentTextColor()) return;
                                sInThemeText = true;
                                try { tv.setTextColor(c); } finally { sInThemeText = false; }
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored) {}
            // View 挂载兜底: 遍历时也套用主题色
            XposedHelpers.findAndHookMethod("android.view.View", cl,
                "onAttachedToWindow", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof android.widget.TextView) applyTextColor(param.thisObject);
                    }
                });
            // 绘制兜底: 设置页文字被三星重设成蓝/默认色时强制 correction
            try {
                            final java.util.Set<String> dumpBlueSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.Set<String> dumpProbe = java.util.concurrent.ConcurrentHashMap.newKeySet();
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl,
                    "onDraw", android.graphics.Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (sInThemeText) return;
                                Object o = param.thisObject;
                                if (!(o instanceof android.widget.TextView)) return;
                                android.widget.TextView tvd = (android.widget.TextView) o;
                                android.content.Context ctxd = sAppContext;
                                if (ctxd == null) return;
                                String txtD = "";
                                try { CharSequence tt = tvd.getText(); if (tt!=null) txtD = tt.toString(); } catch (Throwable ignored) {}
                                int curD;
                                try { curD = tvd.getCurrentTextColor(); } catch (Throwable e2) { return; }
                                // tabs_icon(底部工具栏页面数) 强制用 S_HOME_ICON 色
                                {
                                    int tid = tvd.getId();
                                    String tname = null;
                                    try { tname = tvd.getResources().getResourceEntryName(tid); } catch (Throwable ignored) {}
                                    if ("tabs_icon".equals(tname)) {
                                        android.content.Context tctx = sAppContext;
                                        int icol = (tctx != null) ? ThemeColorHelper.getSlot(tctx, ThemeColorHelper.S_HOME_ICON) : -1;
                                        if (icol != -1 && curD != icol) {
                                            sInThemeText = true;
                                            try { tvd.setTextColor(icol); } finally { sInThemeText = false; }
                                        }
                                        // 页面图标: tabs_icon 的 background(标签图形) 染 S_HOME_ICON 色(与数字统一)
                                        if (icol != -1) {
                                            android.graphics.drawable.Drawable tbg = tvd.getBackground();
                                            if (tbg != null) {
                                                tbg.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                                            }
                                        }
                                        // 诊断: dump 父布局(action_tabs)结构, 定位"页面图标"
                                        try {
                                            android.view.ViewParent par = tvd.getParent();
                                            if (par instanceof android.view.ViewGroup) {
                                                android.view.ViewGroup pg = (android.view.ViewGroup) par;
                                                for (int ci=0; ci<pg.getChildCount(); ci++) {
                                                    android.view.View cv = pg.getChildAt(ci);
                                                    String cidn="";
                                                    try { cidn = cv.getResources().getResourceEntryName(cv.getId()); } catch (Throwable ignoredX) {}
                                                    String bid="bg="+(cv.getBackground()!=null?cv.getBackground().getClass().getSimpleName():"null");
                                                    String draw = "";
                                                    if (cv instanceof android.widget.ImageView) {
                                                        android.graphics.drawable.Drawable dd = ((android.widget.ImageView)cv).getDrawable();
                                                        draw = "drawable="+(dd!=null?dd.getClass().getSimpleName():"null");
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignoredT) {}
                                        return;
                                    }
                                }
                                // 探测: 只打印一次每条文字(不设色), 确认 hook 运行
                                if (dumpProbe.add(Integer.toHexString(System.identityHashCode(o)))) {
                                    String txtP = "";
                                    try { CharSequence tp = tvd.getText(); if (tp!=null) txtP = tp.toString(); } catch (Throwable ignoredP) {}
                                    XposedBridge.log("[SBPlus] DRAW cur=#" + Integer.toHexString(curD) + " cls=" + o.getClass().getName() + " txt=" + txtP);
                                }
                                int rr=(curD>>16)&0xff, gg=(curD>>8)&0xff, bb=curD&0xff;
                                if (bb > 90 && bb > (rr+40) && bb > (gg+40) && txtD.length()>0) {
                                    String id = Integer.toHexString(System.identityHashCode(o));
                                    if (dumpBlueSeen.add(id)) {
                                        float spD=0;
                                        try { spD = tvd.getTextSize()/tvd.getResources().getDisplayMetrics().scaledDensity; } catch (Throwable ignored) {}
                                        XposedBridge.log("[SBPlus] BLUE " + spD + "sp cur=#" + Integer.toHexString(curD)
                                            + " act=" + curActivityName(tvd) + " cls=" + o.getClass().getName()
                                            + " txt=" + txtD);
                                    }
                                }
                                boolean inS = isInSettingsScreen(tvd);
                                if (!inS) return;
                                int targetD = themeTextColorFor(tvd);
                                if (targetD == -1) return;
                                sInThemeText = true;
                                try {
                                    tvd.setTextColor(targetD);
                                    try { tvd.setLinkTextColor(targetD); } catch (Throwable ignoredL) {}
                                    try { tvd.getPaint().setColor(targetD); } catch (Throwable ignoredP) {}
                                    try { tvd.invalidate(); } catch (Throwable ignoredI) {}
                                } finally { sInThemeText = false; }
                                // 无条件再触发一次重绘以确保强制
                                try { tvd.postInvalidate(); } catch (Throwable ignoredQ) {}
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored2) {}

            } catch (Throwable ignored2) {}
            // getTextColors: 三星值文字用 ColorStateList 渲染, 直接覆盖其解析
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl,
                    "getTextColors", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (sInThemeText) return;
                                Object o = param.thisObject;
                                if (!(o instanceof android.widget.TextView)) return;
                                android.widget.TextView tvv = (android.widget.TextView) o;
                                android.content.Context ctx = sAppContext;
                                if (ctx == null || !isThemeActive()) return;
                                if (!isInSettingsScreen(tvv)) return;
                                int t = themeTextColorFor(tvv);
                                if (t == -1) return;
                                param.setResult(new android.content.res.ColorStateList(
                                    new int[][]{ new int[]{} }, new int[]{ t }));
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored2b) {}
            // 主页图标(S_HOME_ICON): 给浏览器 UI 图标(含三星 Toolbar/AppCompat 家族)染主题色
            // 标准 ImageView + AppCompat 家族 + 三星 Toolbar 自定义图标, 都 hook setImageDrawable/setImageResource
            String[][] iconHooks = {
                {"android.widget.ImageView", "setImageDrawable", "drawable"},
                {"android.widget.ImageView", "setImageResource", "res"},
                {"androidx.appcompat.widget.AppCompatImageView", "setImageDrawable", "drawable"},
                {"androidx.appcompat.widget.AppCompatImageButton", "setImageDrawable", "drawable"},
                {"androidx.appcompat.widget.AppCompatImageButton", "setImageResource", "res"},
            };
            for (String[] h : iconHooks) {
                try {
                    XposedHelpers.findAndHookMethod(h[0], cl,
                        h[1], (h[2].equals("res") ? int.class : (Class<?>) android.graphics.drawable.Drawable.class),
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                tintHomeIcon(param.thisObject);
                            }
                        });
                } catch (Throwable ignored) {}
            }
            // 三星 Toolbar 自定义图标类(重写 setImageDrawable 不调父类): 按类名 hook 其 setImageDrawable
            for (String tn : new String[]{
                "com.sec.android.app.sbrowser.common.widget.ToolbarImageButton",
                "com.sec.android.app.sbrowser.common.widget.ToolbarImageView"}) {
                try {
                    XposedHelpers.findAndHookMethod(tn, cl,
                        "setImageDrawable", android.graphics.drawable.Drawable.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                tintHomeIcon(param.thisObject);
                            }
                        });
                } catch (Throwable ignored) {}
            }

            // onDraw 兜底: 任何浏览器 UI ImageView 重绘时都确保染主题色(覆盖动态菜单图标)
            try {
                XposedHelpers.findAndHookMethod("android.widget.ImageView", cl, "onDraw",
                    android.graphics.Canvas.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object o = param.thisObject;
                                if (o instanceof android.widget.ImageView) {
                                    ensureIconTint((android.widget.ImageView) o);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored) {}
            // 开关三色
            try {
                XposedHelpers.findAndHookMethod("androidx.appcompat.widget.SwitchCompat", cl,
                    "setChecked", boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            applySwitchColor(param.thisObject);
                        }
                    });
            } catch (Throwable ignored) {}
            // 探测: 记录真实 WebViewClient 类名(三星 override onPageFinished 不调 super)
            try {
                XposedHelpers.findAndHookMethod("android.webkit.WebView", cl,
                    "setWebViewClient", android.webkit.WebViewClient.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (param.args.length>0 && param.args[0]!=null)
                                    XposedBridge.log("[SBPlus] WebViewClient cls=" + param.args[0].getClass().getName());
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored) {}
            // 网页文字/背景: CSS 注入 (S_WEB_TEXT / S_WEB_BG)
            try {
                XposedHelpers.findAndHookMethod(android.webkit.WebViewClient.class,
                    "onPageFinished", android.webkit.WebView.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                XposedBridge.log("[SBPlus] onPageFinished fired url=" + (param.args.length>1?String.valueOf(param.args[1]):""));
                                // 页面加载完成:同步嗅探/油猴图标显隐(网页显示,主页隐藏)
                                if (param.args.length > 1 && param.args[1] instanceof String) {
                                    showToolbarIconsForWeb(String.valueOf(param.args[1]));
                                }
                                Object wv = param.args[0];
                                if (!isThemeActive()) return;
                                if (!(wv instanceof android.webkit.WebView)) return;
                                android.content.Context ctx = sAppContext;
                                if (ctx == null) return;
                                int wtext = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_WEB_TEXT);
                                int wbg = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_WEB_BG);
                                if (wtext == -1 && wbg == -1) return;
                                String css = buildWebThemeCss(wtext, wbg);
                                final android.webkit.WebView fwv = (android.webkit.WebView) wv;
                                try {
                                    fwv.evaluateJavascript("(function(){" +
                                        "var e=document.getElementById('sbplusTheme');" +
                                        "if(e){e.parentNode.removeChild(e);}" +
                                        "var s=document.createElement('style');s.id='sbplusTheme';" +
                                        "s.textContent='" + css + "';" +
                                        "(document.head||document.documentElement).appendChild(s);" +
                                        "})();", null);
                                } catch (Throwable e2) {
                                    try { fwv.loadUrl("javascript:(function(){" +
                                        "var s=document.createElement('style');s.id='sbplusTheme';" +
                                        "s.textContent='" + css + "';" +
                                        "(document.head||document.documentElement).appendChild(s);" +
                                        "})();"); } catch (Throwable ignored3) {}
                                }
                                XposedBridge.log("[SBPlus] web theme injected text=#" + Integer.toHexString(wtext)
                                    + " bg=#" + Integer.toHexString(wbg));
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignoredW) {}
            XposedBridge.log("[SBPlus] theme hook registered");
            try {
                android.content.Context dctx = sAppContext;
                if (dctx != null) {
                    XposedBridge.log("[SBPlus] SLOTS master=" + isThemeMasterEnabled() + " active=" + isThemeActive());
                    for (int si = 0; si <= ThemeColorHelper.S_SWITCH_OFF; si++) {
                        int sv = ThemeColorHelper.getSlot(dctx, si);
                        XposedBridge.log("[SBPlus] SLOT[" + si + "]=" + (si==ThemeColorHelper.S_HOME_ICON?"HOME_ICON":si==ThemeColorHelper.S_HOME_TEXT?"HOME_TEXT":si==ThemeColorHelper.S_SETTINGS_TITLE?"SETTINGS_TITLE":si==ThemeColorHelper.S_SETTINGS_DESC?"SETTINGS_DESC":si==ThemeColorHelper.S_SETTINGS_BG?"SETTINGS_BG":si==ThemeColorHelper.S_WEB_TEXT?"WEB_TEXT":si==ThemeColorHelper.S_WEB_BG?"WEB_BG":si==ThemeColorHelper.S_SWITCH_ON?"SWITCH_ON":si==ThemeColorHelper.S_SWITCH_THUMB?"SWITCH_THUMB":"SWITCH_OFF") + "=#" + Integer.toHexString(sv));
                    }
                }
            } catch (Throwable ignoredS) {}

            // Dialog.show hook: 弹窗出现时强制染色工具栏图标
            try {
                XposedHelpers.findAndHookMethod(android.app.Dialog.class, "show", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            android.app.Dialog dlg = (android.app.Dialog) param.thisObject;
                            android.view.Window w = dlg.getWindow();
                            if (w != null && w.getDecorView() != null) {
                                final android.view.View root = w.getDecorView().getRootView();
                                root.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        forceApplyAllToolbarIcons(root);
                                    }
                                }, 300);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[SBPlus] Dialog.show hooked for icon tinting");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] Dialog.show hook failed: " + t);
            }

            // PopupWindow hook: 弹窗出现时强制染色工具栏图标
            try {
                XposedHelpers.findAndHookMethod(android.widget.PopupWindow.class, "showAsDropDown",
                        android.view.View.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            android.widget.PopupWindow pw = (android.widget.PopupWindow) param.thisObject;
                            android.view.View cv = pw.getContentView();
                            if (cv != null) {
                                final android.view.View root = cv.getRootView();
                                root.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        forceApplyAllToolbarIcons(root);
                                    }
                                }, 300);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedHelpers.findAndHookMethod(android.widget.PopupWindow.class, "showAtLocation",
                        android.view.View.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            android.widget.PopupWindow pw = (android.widget.PopupWindow) param.thisObject;
                            android.view.View cv = pw.getContentView();
                            if (cv != null) {
                                final android.view.View root = cv.getRootView();
                                root.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        forceApplyAllToolbarIcons(root);
                                    }
                                }, 300);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[SBPlus] PopupWindow hooks registered for icon tinting");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] PopupWindow hook failed: " + t);
            }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] theme hook err: " + t); }
    }

        private String buildWebThemeCss(int text, int bg) {
        StringBuilder sb = new StringBuilder();
        if (text != -1) {
            String t = String.format("%06X", text & 0xFFFFFF);
            sb.append("*{color:#").append(t).append("!important;}");
        }
        if (bg != -1) {
            String b = String.format("%06X", bg & 0xFFFFFF);
            // 统一背景: 清空各层元素背景, 露出 body 的统一主题背景色
            sb.append("*{background-color:transparent!important;}");
            sb.append("html,body{background-color:#").append(b).append("!important;}");
        }
        return sb.toString();
    }

private boolean isThemeMasterEnabled() {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null) return false;
            return ctx.getSharedPreferences("sbplus_prefs", android.content.Context.MODE_PRIVATE)
                    .getBoolean("theme_color_enabled", false);
        } catch (Throwable t) { return false; }
    }

    private boolean isThemeActive() {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null) return false;
            if (!isThemeMasterEnabled()) return false;
            for (int s = 0; s < ThemeColorHelper.S_SWITCH_OFF + 1; s++) {
                if (ThemeColorHelper.getSlot(ctx, s) != -1) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void applyTextColor(Object obj) {
        try {
            if (!(obj instanceof android.widget.TextView)) return;
            android.widget.TextView tv = (android.widget.TextView) obj;
            int c = themeTextColorFor(tv);
            if (c == -1 || c == tv.getCurrentTextColor()) return;
            tv.setTextColor(c);
        } catch (Throwable ignored) {}
    }

    /** 设置页: 标题(>=13sp)用标题色, 说明用说明色; 非设置页用主页文字色. 未设置返回 -1. */
    private int themeTextColorFor(android.view.View v) {
        android.content.Context ctx = sAppContext;
        if (ctx == null) return -1;
        if (!isThemeActive()) return -1;
        boolean settings = isInSettingsScreen(v);
        if (v instanceof android.widget.TextView) {
            float sp;
            try {
                sp = ((android.widget.TextView) v).getTextSize()
                        / v.getResources().getDisplayMetrics().scaledDensity;
            } catch (Throwable e) { sp = 14f; }
            int out;
            // 特例: 底部工具栏"页面数"(tabs_icon)用 S_HOME_ICON 色(与工具栏图标统一)
            int vid = v.getId();
            String vn = null;
            try { vn = v.getResources().getResourceEntryName(vid); } catch (Throwable ignored) {}
            if ("tabs_icon".equals(vn)) {
                out = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            } else if (settings) {
                out = sp >= 13f
                        ? ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SETTINGS_TITLE)
                        : ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SETTINGS_DESC);
            } else {
                out = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_TEXT);
            }
            if (out != -1) {
                String txt = "";
                try { CharSequence t = ((android.widget.TextView) v).getText(); if (t!=null) txt = t.toString(); } catch (Throwable ignored) {}
                int grav = -1;
                try { grav = ((android.widget.TextView) v).getGravity(); } catch (Throwable ignored2) {}
                String cls = v.getClass().getSimpleName();
                int cur = 0;
                try { cur = ((android.widget.TextView) v).getCurrentTextColor(); } catch (Throwable ignored2) {}
                XposedBridge.log("[SBPlus] themeText " + (settings?"SET":"HOME") + " " + sp + "sp grav=" + grav + " cls=" + cls + " cur=#" + Integer.toHexString(cur) + " '" + txt + "' -> #" + Integer.toHexString(out));
            } else if (settings) {
                // 设置页有文字但未命中: 也打印, 便于排查蓝色小字
                String txt = "";
                try { CharSequence t = ((android.widget.TextView) v).getText(); if (t!=null) txt = t.toString(); } catch (Throwable ignored) {}
                if (txt != null && txt.length() > 0) {
                    int cur = 0; float sp0=0;
                    try { cur = ((android.widget.TextView) v).getCurrentTextColor(); sp0 = ((android.widget.TextView) v).getTextSize()/((android.widget.TextView) v).getResources().getDisplayMetrics().scaledDensity; } catch (Throwable ignored2) {}
                    XposedBridge.log("[SBPlus] themeText-MISS SET " + sp0 + "sp cur=#" + Integer.toHexString(cur) + " '" + txt + "'");
                }
            } else if (!settings) {
                // 非设置页: 定位蓝色小字
                String txt = "";
                try { CharSequence t = ((android.widget.TextView) v).getText(); if (t!=null) txt = t.toString(); } catch (Throwable ignored) {}
                int cur = 0; float sp0=0;
                try { cur = ((android.widget.TextView) v).getCurrentTextColor(); sp0 = ((android.widget.TextView) v).getTextSize()/((android.widget.TextView) v).getResources().getDisplayMetrics().scaledDensity; } catch (Throwable ignored2) {}
                // 蓝色系文字(蓝明显强) 即打印
                int r0=(cur>>16)&0xff, g0=(cur>>8)&0xff, b0=cur&0xff;
                if (b0 > 90 && b0 > (r0+40) && b0 > (g0+40)) {
                    String act="";
                    try {
                        android.content.Context cw = v.getContext();
                        while (cw != null) {
                            if (cw instanceof android.app.Activity) { act = cw.getClass().getName(); break; }
                            if (cw instanceof android.content.ContextWrapper) cw = ((android.content.ContextWrapper) cw).getBaseContext();
                            else break;
                        }
                    } catch (Throwable ignored3) {}
                    XposedBridge.log("[SBPlus] themeText-BLUE " + sp0 + "sp cur=#" + Integer.toHexString(cur) + " act=" + act + " class=" + v.getClass().getName() + " '" + txt + "'");
                }
            }
            return out;
        }
        return settings ? ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SETTINGS_TITLE)
                        : ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_TEXT);
    }

    /** 沿 view 的 context 链判断是否处于设置页(Activity 类名含 settings/preference). */
    private String curActivityName(android.view.View v) {
        try {
            android.content.Context cw = v.getContext();
            while (cw != null) {
                if (cw instanceof android.app.Activity) return cw.getClass().getName();
                if (cw instanceof android.content.ContextWrapper) cw = ((android.content.ContextWrapper) cw).getBaseContext();
                else break;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private boolean isInSettingsScreen(android.view.View v) {
        try {
            android.content.Context c = v.getContext();
            while (c != null) {
                if (c instanceof android.app.Activity) {
                    String n = c.getClass().getName().toLowerCase();
                    if (n.contains("setting") || n.contains("preference")) return true;
                    return false;
                }
                if (c instanceof android.content.ContextWrapper) {
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                } else break;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 判断是否浏览器工具栏/菜单图标(SBrowserMainActivity 内), 位于非设置页. */
    private boolean isHomeScreen(android.view.View v) {
        try {
            // 排除主页背景层级(背景 ImageView/纹理/QuickAccess 不应染色)
            try {
                android.view.ViewParent pp0 = v.getParent();
                while (pp0 != null) {
                    String pn0 = pp0.getClass().getName().toLowerCase();
                    if (pn0.contains("custombackground") || pn0.contains("quickaccess")
                            || pn0.contains("videoview") || pn0.contains("textureview")
                            || pn0.contains("reelbackground") || pn0.contains("mainlayoutbackground")) return false;
                    pp0 = pp0.getParent();
                }
            } catch (Throwable ignored) {}
            android.content.Context c = v.getContext();
            while (c != null) {
                if (c instanceof android.app.Activity) {
                    String n = c.getClass().getName().toLowerCase();
                    if (n.contains("setting") || n.contains("preference")
                            || n.contains("download") || n.contains("sniff")
                            || n.contains("userscript")) return false;
                    if (n.contains("sbrowser") || n.contains("sbrowsermain")) return true;
                    return false;
                }
                if (c instanceof android.content.ContextWrapper) {
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                } else break;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 是否浏览器 UI 图标(排除设置页/主页背景/专用页; 覆盖工具栏+菜单等). */
    private boolean isBrowserUiIcon(android.view.View v) {
        try {
            // 排除背景类 id (大图背景, 绝不染)
            try {
                String vidn = v.getResources().getResourceEntryName(v.getId());
                if (vidn.contains("background") || vidn.equals("custom_background")
                        || vidn.contains("backdrop") || vidn.contains("wallpaper")) return false;
                // 排除地址栏的跳转App图标(open_in_app/launch_app/external_app 等)
                if (vidn.contains("open_in") || vidn.contains("launch_app") || vidn.contains("external")
                        || vidn.contains("open_with") || vidn.contains("jump_to")) return false;
                // 地址栏底部工具图标(toolbar_ 前缀)全部排除染色,仅保留刷新与收藏
                if (vidn.startsWith("toolbar_") && !vidn.equals("toolbar_reload")
                        && !vidn.equals("toolbar_bookmarks") && !vidn.equals("toolbar_bookmark") && !vidn.equals("bookmark_star_icon")) return false;
            } catch (Throwable ignoredV) {}
            // 排除主页背景层级 + 标签页网格(网页缩略图/多标签页缩略图绝不染)
            try {
                android.view.ViewParent pp0 = v.getParent();
                while (pp0 != null) {
                    String pn0 = pp0.getClass().getName().toLowerCase();
                    if (pn0.contains("custombackground") || pn0.contains("quickaccess")
                            || pn0.contains("videoview") || pn0.contains("textureview")
                            || pn0.contains("reelbackground") || pn0.contains("mainlayoutbackground")
                            || pn0.contains("multitab") || pn0.contains("tabgrid") || pn0.contains("tabpage")
                            || pn0.contains("tabswitcher") || pn0.contains("gallerygrid")
                            || pn0.contains("recyclerview") || pn0.contains("gridview")) return false;
                    pp0 = pp0.getParent();
                }
            } catch (Throwable ignored) {}
            android.content.Context c = v.getContext();
            while (c != null) {
                if (c instanceof android.app.Activity) {
                    String n = c.getClass().getName().toLowerCase();
                    if (n.contains("setting") || n.contains("preference")
                            || n.contains("download") || n.contains("sniff")
                            || n.contains("userscript") || n.contains("sites")
                            || n.contains("bookmark") || n.contains("history")) return false;
                    // 仅拦浏览器自身包
                    if (n.contains("sbrowser")) return true;
                    return false;
                }
                if (c instanceof android.content.ContextWrapper) {
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                } else break;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 主页图标 -> S_HOME_ICON. 未设置返回 -1. */
    private int themeHomeIconColor() {
        android.content.Context ctx = sAppContext;
        if (ctx == null || !isThemeActive()) return -1;
        return ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
    }

    /** 开关三色应用. */
    private void applySwitchColor(Object sw) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || sw == null) return;
            if (!isThemeActive()) return;
            int on = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SWITCH_ON);
            int off = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SWITCH_OFF);
            int thumb = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_SWITCH_THUMB);
            if (on == -1 && off == -1 && thumb == -1) return;
            int onCol = on == -1 ? 0xFF3E91FF : on;
            int offCol = off == -1 ? 0xFF3A3A3E : off;
            android.content.res.ColorStateList track = new android.content.res.ColorStateList(
                new int[][]{
                    new int[]{ android.R.attr.state_checked },
                    new int[]{}
                },
                new int[]{ onCol, offCol });
            try { sw.getClass().getMethod("setTrackTintList", android.content.res.ColorStateList.class).invoke(sw, track); } catch (Throwable ignored) {}
            if (thumb != -1) {
                android.content.res.ColorStateList tl = new android.content.res.ColorStateList(
                    new int[][]{ new int[]{} }, new int[]{ thumb });
                try { sw.getClass().getMethod("setThumbTintList", android.content.res.ColorStateList.class).invoke(sw, tl); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void injectHomeBeautify(Context ctx, ClassLoader cl, Object screen) {
        Object videoBgPref = buildVideoBgSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", videoBgPref);

        Object clearTextPref = buildHomeClearTextSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", clearTextPref);

        Object moveBtnPref = buildHomeMoveBtnSwitch(ctx, cl);
        XposedHelpers.callMethod(screen, "addPreference", moveBtnPref);

        Object themeEntry = ThemeColorHelper.buildEntry(ctx, cl);
        if (themeEntry != null) { XposedHelpers.callMethod(screen, "addPreference", themeEntry); }

        // -- 字体入口 --
        try {
            Object fontEntry = FontHelper.buildEntry(ctx, cl);
            if (fontEntry != null) { XposedHelpers.callMethod(screen, "addPreference", fontEntry); }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] font entry error: " + t);
        }


        // -- 主页 Logo 入口 --
        try {
            sHomeLogoScreen = new Object[]{ screen, ctx, cl };
            Object logoEntry = buildHomeLogoEntry(ctx, cl);
            if (logoEntry != null) { XposedHelpers.callMethod(screen, "addPreference", logoEntry); }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] home logo entry error: " + t);
        }

        // -- 主页时钟入口 --
        try {
            Object clockEntry = buildHomeClockEntry(ctx, cl);
            if (clockEntry != null) { XposedHelpers.callMethod(screen, "addPreference", clockEntry); }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] home clock entry error: " + t);
        }

        XposedBridge.log("[SBPlus] home beautify page injected (theme color + font)");
    }

        /** 入口:添加主页 Logo(选本地图片/GIF),显示在搜索框上方居中。 */
    /** 入口:添加主页 Logo(选本地图片/GIF),纯文字条目(无开关)+竖线图标。 */
    /** 入口:主页 Logo(带启用开关+竖线)。行点击弹列表对话框: 显示已导入图片+删除+应用状态。 */
    /** 入口:主页 Logo(Switch 条目: 右侧开关=启用/停用; 行点击=弹管理列表; 竖线图标)。 */
    private Object buildHomeLogoEntry(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
            Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{android.content.Context.class}, ctx);
            int cnt = HomeLogoHelper.listLogos(ctx).size();
            XposedHelpers.callMethod(pref, "setTitle", T("主页 Logo", "Home Logo"));
            XposedHelpers.callMethod(pref, "setKey", "sbplus_home_logo_entry");
            if (cnt == 0) {
                XposedHelpers.callMethod(pref, "setSummary", T("点击添加 Logo 图片到主页", "Tap to add a logo image"));
            } else if (HomeLogoHelper.isEnabled(ctx)) {
                XposedHelpers.callMethod(pref, "setSummary",
                        T("已添加 " + cnt + " 张，点按管理（使用中）", cnt + " logo(s), tap to manage (in use)"));
            } else {
                XposedHelpers.callMethod(pref, "setSummary",
                        T("已添加 " + cnt + " 张，点按管理（已停用）", cnt + " logo(s), tap to manage (off)"));
            }
            XposedHelpers.callMethod(pref, "setChecked", HomeLogoHelper.isEnabled(ctx));
            XposedHelpers.callMethod(pref, "setSelectable", true);
            try { XposedHelpers.callMethod(pref, "setDividerVisible", true); } catch (Throwable ignored) {}
            // 开关: 独立切换启用/停用(不弹列表)
            try {
                Class<?> chgType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
                Object chgL = java.lang.reflect.Proxy.newProxyInstance(cl,
                        new Class[]{chgType},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                try {
                                    if (m.getName().equals("onPreferenceChange")) {
                                        boolean on = Boolean.TRUE.equals(args[1]);
                                        HomeLogoHelper.setEnabled(ctx, on);
                                        toastOnMain(on ? T("已启用主页 Logo", "Logo on") : T("已停用主页 Logo", "Logo off"));
                                        refreshHomeLogoSection();
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] logo switch err: " + t);
                                }
                                return Boolean.FALSE;
                            }
                        });
                XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", chgL);
            } catch (Throwable ignored) {}
            // 行点击: 拦截默认切换,弹管理列表
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
                                        while (actObj instanceof android.content.ContextWrapper
                                                && !(actObj instanceof android.app.Activity)) {
                                            actObj = ((android.content.ContextWrapper) actObj).getBaseContext();
                                        }
                                        if (actObj instanceof android.app.Activity) {
                                            showHomeLogoListDialog((android.app.Activity) actObj, ctx);
                                        }
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] home logo click err: " + t);
                                }
                                return Boolean.FALSE;
                            }
                        });
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
            } catch (Throwable ignored) {}
            return pref;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] buildHomeLogoEntry err: " + t);
            return null;
        }
    }

    /** 入口: 主页时钟(Switch 条目, 右侧开关=启用/停用; 行点击=设置样式/秒/位置/大小)。 */
    private Object buildHomeClockEntry(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
            Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{android.content.Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", T("主页时钟", "Home Clock"));
            XposedHelpers.callMethod(pref, "setKey", "sbplus_home_clock_entry");
            boolean en = HomeClockHelper.isEnabled(ctx);
            XposedHelpers.callMethod(pref, "setSummary",
                    en ? T("使用中，点按设置", "On, tap to set")
                       : T("点击设置主页时钟", "Tap to set home clock"));
            XposedHelpers.callMethod(pref, "setChecked", en);
            XposedHelpers.callMethod(pref, "setSelectable", true);
            try { XposedHelpers.callMethod(pref, "setDividerVisible", true); } catch (Throwable ignored) {}
            // 开关: 独立切换启用/停用
            try {
                Class<?> chgType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
                Object chgL = java.lang.reflect.Proxy.newProxyInstance(cl,
                        new Class[]{chgType},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                try {
                                    if (m.getName().equals("onPreferenceChange")) {
                                        boolean on = Boolean.TRUE.equals(args[1]);
                                        HomeClockHelper.setEnabled(ctx, on);
                                        toastOnMain(on ? T("已启用主页时钟", "Clock on") : T("已停用主页时钟", "Clock off"));
                                        refreshHomeClock();
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] clock switch err: " + t);
                                }
                                return Boolean.FALSE;
                            }
                        });
                XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", chgL);
            } catch (Throwable ignored) {}
            // 行点击: 拦截默认切换,弹设置对话框
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
                                        while (actObj instanceof android.content.ContextWrapper
                                                && !(actObj instanceof android.app.Activity)) {
                                            actObj = ((android.content.ContextWrapper) actObj).getBaseContext();
                                        }
                                        if (actObj instanceof android.app.Activity) {
                                            showHomeClockSettingsDialog((android.app.Activity) actObj, ctx);
                                        }
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] home clock click err: " + t);
                                }
                                return Boolean.FALSE;
                            }
                        });
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", onPreferenceClick);
            } catch (Throwable ignored) {}
            return pref;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] buildHomeClockEntry err: " + t);
            return null;
        }
    }

    /** 主页时钟设置对话框: 样式(普通/翻页) + 精确到秒 + 位置/大小。 */
    private void showHomeClockSettingsDialog(final android.app.Activity act, final android.content.Context ctx) {
        try {
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(act)
                    .setTitle(T("主页时钟设置", "Home Clock Settings"))
                    .setPositiveButton(T("确定", "OK"), null)
                    .setNegativeButton(T("取消", "Cancel"), null)
                    .create();
            final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(act, 20);
            root.setPadding(pad, dp(act,8), pad, 0);

            // 精确到秒
            final android.widget.LinearLayout rowSec = new android.widget.LinearLayout(act);
            rowSec.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowSec.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rowSecLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            rowSecLp.topMargin = dp(act, 10);
            rowSec.setLayoutParams(rowSecLp);
            final android.widget.TextView tvSec = new android.widget.TextView(act);
            tvSec.setText(T("精确到秒", "Show seconds"));
            tvSec.setTextSize(14);
            tvSec.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Switch swSec = new android.widget.Switch(act);
            swSec.setChecked(HomeClockHelper.isSeconds(ctx));
            rowSec.addView(tvSec);
            rowSec.addView(swSec);
            root.addView(rowSec);

            // 跟随搜索框动画
            final android.widget.LinearLayout rowFol = new android.widget.LinearLayout(act);
            rowFol.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowFol.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rowFolLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            rowFolLp.topMargin = dp(act, 6);
            rowFol.setLayoutParams(rowFolLp);
            final android.widget.TextView tvFol = new android.widget.TextView(act);
            tvFol.setText(T("跟随搜索框动画", "Follow search-bar"));
            tvFol.setTextSize(14);
            tvFol.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Switch swFol = new android.widget.Switch(act);
            swFol.setChecked(HomeClockHelper.isFollow(ctx));
            rowFol.addView(tvFol);
            rowFol.addView(swFol);
            root.addView(rowFol);

            // 位置/大小按钮
            final android.widget.Button btnPos = new android.widget.Button(act);
            btnPos.setText(T("位置与大小", "Position & size"));
            android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            btnLp.topMargin = dp(act, 12);
            btnPos.setLayoutParams(btnLp);
            btnPos.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try { showClockPosDialog(act, ctx); }
                    catch (Throwable t) { XposedBridge.log("[SBPlus] clock pos err: " + t); }
                }
            });
            root.addView(btnPos);

            // 复位按钮
            final android.widget.Button btnReset = new android.widget.Button(act);
            btnReset.setText(T("复位", "Reset"));
            android.widget.LinearLayout.LayoutParams btnLp2 = new android.widget.LinearLayout.LayoutParams(-1, -2);
            btnLp2.topMargin = dp(act, 6);
            btnReset.setLayoutParams(btnLp2);
            btnReset.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        HomeClockHelper.setPosX(ctx, 50);
                        HomeClockHelper.setPosY(ctx, 30);
                        HomeClockHelper.setSizePct(ctx, 100);
                        toastOnMain(T("已恢复默认", "Reset to default"));
                        refreshHomeClock();
                    } catch (Throwable ignored) {}
                }
            });
            root.addView(btnReset);

            dlg.setView(root);
            dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override public void onShow(android.content.DialogInterface d) {
                    try {
                        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                try {
                                    HomeClockHelper.setSeconds(ctx, swSec.isChecked());
                                    HomeClockHelper.setFollow(ctx, swFol.isChecked());
                                    try { refreshHomeClock(); } catch (Throwable ignored2) {}
                                    toastOnMain(T("已保存", "Saved"));
                                    dlg.dismiss();
                                    refreshHomeClock();
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showHomeClockSettingsDialog err: " + t);
        }
    }

    /** 时钟位置/大小对话框: X/Y 百分比 + 大小, SeekBar + 输入框, 含复位。 */
    private void showClockPosDialog(final android.app.Activity act, final android.content.Context ctx) {
        try {
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(act)
                    .setTitle(T("时钟位置与大小", "Clock position & size"))
                    .setPositiveButton(T("保存", "Save"), null)
                    .setNegativeButton(T("取消", "Cancel"), null)
                    .create();
            final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(act, 20);
            root.setPadding(pad, dp(act,8), pad, 0);

            final int[] curX = { HomeClockHelper.getPosX(ctx) };
            final int[] curY = { HomeClockHelper.getPosY(ctx) };
            final int[] curSize = { HomeClockHelper.getSizePct(ctx) };

            // X
            final android.widget.TextView tvX = new android.widget.TextView(act);
            tvX.setText(T("X 位置: " + curX[0] + "%", "X position: " + curX[0] + "%"));
            tvX.setTextSize(13);
            final android.widget.SeekBar sbX = new android.widget.SeekBar(act);
            sbX.setMax(100);
            sbX.setProgress(curX[0]);
            final android.widget.EditText etX = new android.widget.EditText(act);
            etX.setText(String.valueOf(curX[0]));
            etX.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etX.setSingleLine(true);
            etX.setTextSize(13);
            android.widget.LinearLayout rowX = new android.widget.LinearLayout(act);
            rowX.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowX.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etX.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowX.addView(sbX, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowX.addView(etX);
            sbX.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curX[0] = p; tvX.setText(T("X 位置: " + p + "%", "X position: " + p + "%")); etX.setText(String.valueOf(p)); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvX);
            root.addView(rowX);

            // Y
            final android.widget.TextView tvY = new android.widget.TextView(act);
            tvY.setText(T("Y 位置: " + curY[0] + "%", "Y position: " + curY[0] + "%"));
            tvY.setTextSize(13);
            android.widget.LinearLayout.LayoutParams mpY = new android.widget.LinearLayout.LayoutParams(-2,-2);
            mpY.topMargin = dp(act,8);
            tvY.setLayoutParams(mpY);
            final android.widget.SeekBar sbY = new android.widget.SeekBar(act);
            sbY.setMax(100);
            sbY.setProgress(curY[0]);
            final android.widget.EditText etY = new android.widget.EditText(act);
            etY.setText(String.valueOf(curY[0]));
            etY.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etY.setSingleLine(true);
            etY.setTextSize(13);
            android.widget.LinearLayout rowY = new android.widget.LinearLayout(act);
            rowY.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowY.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etY.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowY.addView(sbY, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowY.addView(etY);
            sbY.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curY[0] = p; tvY.setText(T("Y 位置: " + p + "%", "Y position: " + p + "%")); etY.setText(String.valueOf(p)); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvY);
            root.addView(rowY);

            // 大小
            final android.widget.TextView tvSize = new android.widget.TextView(act);
            tvSize.setText(T("大小: " + curSize[0] + "%", "Size: " + curSize[0] + "%"));
            tvSize.setTextSize(13);
            android.widget.LinearLayout.LayoutParams mpS = new android.widget.LinearLayout.LayoutParams(-2,-2);
            mpS.topMargin = dp(act,8);
            tvSize.setLayoutParams(mpS);
            final android.widget.SeekBar sbSize = new android.widget.SeekBar(act);
            sbSize.setMax(180);
            sbSize.setProgress(curSize[0] - 20);
            final android.widget.EditText etSize = new android.widget.EditText(act);
            etSize.setText(String.valueOf(curSize[0]));
            etSize.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etSize.setSingleLine(true);
            etSize.setTextSize(13);
            android.widget.LinearLayout rowS = new android.widget.LinearLayout(act);
            rowS.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowS.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etSize.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowS.addView(sbSize, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowS.addView(etSize);
            sbSize.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curSize[0] = p + 20; tvSize.setText(T("大小: " + curSize[0] + "%", "Size: " + curSize[0] + "%")); etSize.setText(String.valueOf(curSize[0])); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvSize);
            root.addView(rowS);

            // 复位
            final android.widget.Button btnReset = new android.widget.Button(act);
            btnReset.setText(T("复位", "Reset"));
            android.widget.LinearLayout.LayoutParams resetLp = new android.widget.LinearLayout.LayoutParams(-2, -2);
            resetLp.topMargin = dp(act, 10);
            resetLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            btnReset.setLayoutParams(resetLp);
            btnReset.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        HomeClockHelper.setPosX(ctx, 50);
                        HomeClockHelper.setPosY(ctx, 30);
                        HomeClockHelper.setSizePct(ctx, 100);
                        toastOnMain(T("已恢复默认", "Reset to default"));
                        refreshHomeClock();
                        dlg.dismiss();
                    } catch (Throwable ignored) {}
                }
            });
            root.addView(btnReset);

            dlg.setView(root);
            dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override public void onShow(android.content.DialogInterface d) {
                    try {
                        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                try {
                                    int vx = Integer.parseInt(etX.getText().toString().trim());
                                    int vy = Integer.parseInt(etY.getText().toString().trim());
                                    int vs = Integer.parseInt(etSize.getText().toString().trim());
                                    if (vx < 0) vx = 0; if (vx > 100) vx = 100;
                                    if (vy < 0) vy = 0; if (vy > 100) vy = 100;
                                    if (vs < 20) vs = 20; if (vs > 200) vs = 200;
                                    HomeClockHelper.setPosX(ctx, vx);
                                    HomeClockHelper.setPosY(ctx, vy);
                                    HomeClockHelper.setSizePct(ctx, vs);
                                    toastOnMain(T("位置已保存", "Position saved"));
                                    dlg.dismiss();
                                    refreshHomeClock();
                                } catch (Throwable t2) { toastShort(T("输入无效", "Invalid input")); }
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showClockPosDialog err: " + t);
        }
    }

    /** 主页 Logo 管理[全屏页面]: 图片列表 + 启用开关 + 应用/删除 + 添加。 */
    private void showHomeLogoListDialog(final android.app.Activity act, final android.content.Context ctx) {
        try {
            final android.app.Dialog dlg = new android.app.Dialog(act);
            try { dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); } catch (Throwable ignored) {}
            final android.widget.LinearLayout page = new android.widget.LinearLayout(act);
            page.setOrientation(android.widget.LinearLayout.VERTICAL);
            int dm = (int) act.getResources().getDisplayMetrics().density;

            // ===== 顶部栏: 标题 + 关闭 =====
            final android.widget.LinearLayout topBar = new android.widget.LinearLayout(act);
            topBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            topBar.setPadding(dp(act,16), dp(act,14), dp(act,16), dp(act,14));
            try { topBar.setBackgroundColor(0xFFF0F1F3); } catch (Throwable ignored) {}
            final android.widget.TextView tvTitle = new android.widget.TextView(act);
            tvTitle.setTextSize(18);
            tvTitle.setText(T("主页 Logo", "Home Logo"));
            tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Button btnClose = new android.widget.Button(act);
            btnClose.setText(T("关闭", "Close"));
            btnClose.setTextSize(14);
            btnClose.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
            });
            topBar.addView(tvTitle);
            topBar.addView(btnClose);
            page.addView(topBar);

            // 跟随搜索框动画开关
            final android.widget.LinearLayout folRow = new android.widget.LinearLayout(act);
            folRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            folRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            folRow.setPadding(dp(act,16), dp(act,4), dp(act,16), dp(act,4));
            final android.widget.TextView folTxt = new android.widget.TextView(act);
            folTxt.setText(T("跟随搜索框动画", "Follow search-bar"));
            folTxt.setTextSize(15);
            folTxt.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Switch folSw = new android.widget.Switch(act);
            folSw.setChecked(HomeLogoHelper.isFollow(ctx));
            folSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    try {
                        HomeLogoHelper.setFollow(ctx, on);
                        toastOnMain(on ? T("已开启跟随", "Follow on") : T("已关闭跟随", "Follow off"));
                    } catch (Throwable ignored) {}
                }
            });
            folRow.addView(folTxt);
            folRow.addView(folSw);
            page.addView(folRow);
            try { folRow.setBackgroundColor(0x0DFFFFFF); } catch (Throwable ignored) {}

            // 列表区(可滚动)
            final android.widget.ScrollView sv = new android.widget.ScrollView(act);
            final android.widget.LinearLayout listBox = new android.widget.LinearLayout(act);
            listBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            listBox.setPadding(dp(act,16), dp(act,8), dp(act,16), dp(act,8));
            sv.addView(listBox);

            final Runnable[] rebuildListRef = new Runnable[1];
            final Runnable rebuildList = new Runnable() {
                @Override public void run() {
                    try {
                        listBox.removeAllViews();
                        final java.util.List<String> logos = HomeLogoHelper.listLogos(ctx);
                        final String cur = HomeLogoHelper.currentPath(ctx);
                        tvTitle.setText(T("主页 Logo（" + logos.size() + " 张）", "Home Logo (" + logos.size() + ")"));
                        if (logos.isEmpty()) {
                            android.widget.TextView tvEmpty = new android.widget.TextView(act);
                            tvEmpty.setText(T("还没有 Logo，点下方按钮添加一张", "No logos yet. Add one below."));
                            tvEmpty.setPadding(0, dp(act,30), 0, dp(act,30));
                            tvEmpty.setGravity(android.view.Gravity.CENTER);
                            tvEmpty.setTextColor(0xFF888888);
                            listBox.addView(tvEmpty);
                            return;
                        }
                        for (final String name : logos) {
                            java.io.File f = new java.io.File(HomeLogoHelper.dirFor(ctx), name);
                            boolean isCur = f.getAbsolutePath().equals(cur);
                            final android.widget.LinearLayout row = new android.widget.LinearLayout(act);
                            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                            row.setPadding(dp(act,8), dp(act,10), dp(act,8), dp(act,10));
                            row.setBackgroundResource(android.R.drawable.list_selector_background);
                            // 缩略图
                            final android.widget.ImageView iv = new android.widget.ImageView(act);
                            android.view.ViewGroup.LayoutParams ilp = new android.view.ViewGroup.LayoutParams(dp(act,52), dp(act,52));
                            iv.setLayoutParams(ilp);
                            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                            try {
                                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                                if (bmp != null) iv.setImageBitmap(bmp);
                            } catch (Throwable ignored) {}
                            row.addView(iv);
                            // 文件名 + 状态
                            final android.widget.LinearLayout col = new android.widget.LinearLayout(act);
                            col.setOrientation(android.widget.LinearLayout.VERTICAL);
                            col.setPadding(dp(act,12), 0, dp(act,8), 0);
                            col.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                            android.widget.TextView tvName = new android.widget.TextView(act);
                            tvName.setText(name);
                            tvName.setTextSize(14);
                            tvName.setSingleLine(true);
                            tvName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                            android.widget.TextView tvState = new android.widget.TextView(act);
                            tvState.setTextSize(12);
                            if (isCur) {
                                tvState.setText(T("● 使用中", "● In use"));
                                tvState.setTextColor(0xFF2E7CF6);
                            } else {
                                tvState.setText(T("点按应用此 Logo", "Tap to apply"));
                                tvState.setTextColor(0xFF888888);
                            }
                            col.addView(tvName);
                            col.addView(tvState);
                            // 透明背景小开关
                            final android.widget.LinearLayout alphaRow = new android.widget.LinearLayout(act);
                            alphaRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                            alphaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                            android.widget.TextView tvAlpha = new android.widget.TextView(act);
                            tvAlpha.setText(T("透明背景", "Transparent bg"));
                            tvAlpha.setTextSize(11);
                            tvAlpha.setTextColor(0xFF666666);
                            final android.widget.Switch swAlpha = new android.widget.Switch(act);
                            swAlpha.setChecked(HomeLogoHelper.isAlphaBg(ctx, name));
                            swAlpha.setScaleX(0.7f);
                            swAlpha.setScaleY(0.7f);
                            swAlpha.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                    try {
                                        HomeLogoHelper.setAlphaBg(ctx, name, on);
                                        toastOnMain(on ? T("已开启透明背景", "Transparent bg on") : T("已关闭透明背景", "Transparent bg off"));
                                    } catch (Throwable t) { XposedBridge.log("[SBPlus] alpha sw err: " + t); }
                                }
                            });
                            alphaRow.addView(tvAlpha);
                            alphaRow.addView(swAlpha);
                            col.addView(alphaRow);
                            row.addView(col);
                            // 位置按钮
                            final android.widget.Button btnPos = new android.widget.Button(act);
                            btnPos.setText(T("位置", "Pos"));
                            btnPos.setTextSize(12);
                            btnPos.setMinWidth(dp(act,40));
                            btnPos.setMinHeight(dp(act,44));
                            btnPos.setOnClickListener(new android.view.View.OnClickListener() {
                                @Override public void onClick(android.view.View v) {
                                    try { showLogoPosDialog(act, ctx, name); }
                                    catch (Throwable t) { XposedBridge.log("[SBPlus] logo pos err: " + t); }
                                }
                            });
                            row.addView(btnPos);
                            // 删除按钮
                            final android.widget.Button btnDel = new android.widget.Button(act);
                            btnDel.setText("✕");
                            btnDel.setTextSize(16);
                            btnDel.setMinWidth(dp(act,44));
                            btnDel.setMinHeight(dp(act,44));
                            btnDel.setOnClickListener(new android.view.View.OnClickListener() {
                                @Override public void onClick(android.view.View v) {
                                    try {
                                        HomeLogoHelper.removeLogo(ctx, name);
                                        toastOnMain(T("已删除", "Deleted"));
                                        refreshHomeLogoSection();
                                        rebuildListRef[0].run();
                                    } catch (Throwable t) { XposedBridge.log("[SBPlus] logo del err: " + t); }
                                }
                            });
                            row.addView(btnDel);
                            // 整行点击 = 应用
                            row.setOnClickListener(new android.view.View.OnClickListener() {
                                @Override public void onClick(android.view.View v) {
                                    try {
                                        HomeLogoHelper.setCurrent(ctx, name);
                                        HomeLogoHelper.setEnabled(ctx, true);
                                        toastOnMain(T("已应用 Logo", "Logo applied"));
                                        refreshHomeLogoSection();
                                        rebuildListRef[0].run();
                                    } catch (Throwable t) { XposedBridge.log("[SBPlus] logo apply err: " + t); }
                                }
                            });
                            listBox.addView(row);
                        }
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] logo list rebuild err: " + t); }
                }
            };

            // ===== 底部: 添加图片 =====
            final android.widget.Button btnAdd = new android.widget.Button(act);
            btnAdd.setText(T("＋ 添加图片", "+ Add image"));
            btnAdd.setTextSize(15);
            btnAdd.setAllCaps(false);
            btnAdd.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        act.startActivityForResult(intent, REQUEST_HOME_LOGO_PICK);
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] logo add err: " + t); }
                }
            });

            page.addView(sv, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            page.addView(btnAdd, new android.widget.LinearLayout.LayoutParams(-1, dp(act,52)));
            dlg.setContentView(page);
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                w.setGravity(android.view.Gravity.CENTER);
            }
            dlg.show();
            rebuildListRef[0] = rebuildList;
            rebuildList.run();

            // onActivityResult 里 addLogoFromUri 成功后要刷新此页
            sHomeLogoPageDlg = dlg;
            sHomeLogoPageRebuild = rebuildList;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showHomeLogoListDialog err: " + t);
        }
    }
    /** 位置设置对话框: X/Y 百分比 + 大小百分比, SeekBar + 输入框。 */
    private void showLogoPosDialog(final android.app.Activity act, final android.content.Context ctx, final String name) {
        try {
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(act)
                    .setTitle(T("位置与大小 - " + name, "Position & size - " + name))
                    .setPositiveButton(T("保存", "Save"), null)
                    .setNegativeButton(T("取消", "Cancel"), null)
                    .create();
            final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(act, 20);
            root.setPadding(pad, dp(act,8), pad, 0);

            final int[] curX = { HomeLogoHelper.getPosX(ctx, name) };
            final int[] curY = { HomeLogoHelper.getPosY(ctx, name) };
            final int[] curSize = { HomeLogoHelper.getSizePct(ctx, name) };

            // ===== X =====
            final android.widget.TextView tvX = new android.widget.TextView(act);
            tvX.setText(T("X 位置: " + curX[0] + "%", "X position: " + curX[0] + "%"));
            tvX.setTextSize(13);
            final android.widget.SeekBar sbX = new android.widget.SeekBar(act);
            sbX.setMax(100);
            sbX.setProgress(curX[0]);
            final android.widget.EditText etX = new android.widget.EditText(act);
            etX.setText(String.valueOf(curX[0]));
            etX.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etX.setSingleLine(true);
            etX.setTextSize(13);
            android.widget.LinearLayout rowX = new android.widget.LinearLayout(act);
            rowX.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowX.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etX.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowX.addView(sbX, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowX.addView(etX);
            sbX.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curX[0] = p; tvX.setText(T("X 位置: " + p + "%", "X position: " + p + "%")); etX.setText(String.valueOf(p)); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvX);
            root.addView(rowX);

            // ===== Y =====
            final android.widget.TextView tvY = new android.widget.TextView(act);
            tvY.setText(T("Y 位置: " + curY[0] + "%", "Y position: " + curY[0] + "%"));
            tvY.setTextSize(13);
            android.widget.LinearLayout.MarginLayoutParams mpY = new android.widget.LinearLayout.LayoutParams(-2,-2);
            mpY.topMargin = dp(act,8);
            tvY.setLayoutParams(mpY);
            final android.widget.SeekBar sbY = new android.widget.SeekBar(act);
            sbY.setMax(100);
            sbY.setProgress(curY[0]);
            final android.widget.EditText etY = new android.widget.EditText(act);
            etY.setText(String.valueOf(curY[0]));
            etY.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etY.setSingleLine(true);
            etY.setTextSize(13);
            android.widget.LinearLayout rowY = new android.widget.LinearLayout(act);
            rowY.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowY.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etY.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowY.addView(sbY, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowY.addView(etY);
            sbY.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curY[0] = p; tvY.setText(T("Y 位置: " + p + "%", "Y position: " + p + "%")); etY.setText(String.valueOf(p)); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvY);
            root.addView(rowY);

            // ===== 大小 =====
            final android.widget.TextView tvSize = new android.widget.TextView(act);
            tvSize.setText(T("大小: " + curSize[0] + "%", "Size: " + curSize[0] + "%"));
            tvSize.setTextSize(13);
            android.widget.LinearLayout.MarginLayoutParams mpS = new android.widget.LinearLayout.LayoutParams(-2,-2);
            mpS.topMargin = dp(act,8);
            tvSize.setLayoutParams(mpS);
            final android.widget.SeekBar sbSize = new android.widget.SeekBar(act);
            sbSize.setMax(180);
            sbSize.setProgress(curSize[0] - 20);
            final android.widget.EditText etSize = new android.widget.EditText(act);
            etSize.setText(String.valueOf(curSize[0]));
            etSize.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etSize.setSingleLine(true);
            etSize.setTextSize(13);
            android.widget.LinearLayout rowS = new android.widget.LinearLayout(act);
            rowS.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            rowS.setGravity(android.view.Gravity.CENTER_VERTICAL);
            etSize.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(act,64), -2));
            rowS.addView(sbSize, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            rowS.addView(etSize);
            sbSize.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) { curSize[0] = p + 20; tvSize.setText(T("大小: " + curSize[0] + "%", "Size: " + curSize[0] + "%")); etSize.setText(String.valueOf(curSize[0])); }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
            });
            root.addView(tvSize);
            root.addView(rowS);

            // 复位按钮: 恢复默认位置和大小
            final android.widget.Button btnReset = new android.widget.Button(act);
            btnReset.setText(T("复位", "Reset"));
            btnReset.setTextSize(13);
            final android.widget.LinearLayout.LayoutParams resetLp = new android.widget.LinearLayout.LayoutParams(-2, -2);
            resetLp.topMargin = dp(act, 10);
            resetLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            btnReset.setLayoutParams(resetLp);
            btnReset.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        HomeLogoHelper.setPosX(ctx, name, 50);
                        HomeLogoHelper.setPosY(ctx, name, 20);
                        HomeLogoHelper.setSizePct(ctx, name, 100);
                        toastOnMain(T("已恢复默认位置与大小", "Reset to default"));
                        dlg.dismiss();
                        refreshHomeLogoSection();
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] reset pos err: " + t); }
                }
            });
            root.addView(btnReset);

            dlg.setView(root);
            dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override public void onShow(android.content.DialogInterface d) {
                    try {
                        android.widget.Button pos = dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                        pos.setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                try {
                                    // 输入框覆盖 seekbar
                                    int vx = Integer.parseInt(etX.getText().toString().trim());
                                    int vy = Integer.parseInt(etY.getText().toString().trim());
                                    int vs = Integer.parseInt(etSize.getText().toString().trim());
                                    if (vx < 0) vx = 0; if (vx > 100) vx = 100;
                                    if (vy < 0) vy = 0; if (vy > 100) vy = 100;
                                    if (vs < 50) vs = 50; if (vs > 200) vs = 200;
                                    HomeLogoHelper.setPosX(ctx, name, vx);
                                    HomeLogoHelper.setPosY(ctx, name, vy);
                                    HomeLogoHelper.setSizePct(ctx, name, vs);
                                    toastOnMain(T("位置已保存", "Position saved"));
                                    dlg.dismiss();
                                    refreshHomeLogoSection();
                                } catch (Throwable t) { toastShort(T("输入无效", "Invalid input")); }
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
            dlg.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showLogoPosDialog err: " + t);
        }
    }


    /** 刷新主页美化页的 Logo 区(开关状态+数字) + 重绘主页 Logo。 */
    private void refreshHomeLogoSection() {
        try {
            // 重绘主页 Logo: 移除旧的再从 bgView 重新挂载
            try {
                if (sHomeLogoBgView != null) {
                    android.view.View bg = sHomeLogoBgView;
                    android.view.ViewGroup parent = (android.view.ViewGroup) bg.getParent();
                    if (parent != null) {
                        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                            android.view.View c = parent.getChildAt(i);
                            if (c instanceof android.widget.ImageView && c.getTag() != null
                                    && "sbplus_home_logo".equals(c.getTag())) {
                                parent.removeViewAt(i);
                                if (c == sHomeLogoIv) sHomeLogoIv = null;
                            }
                        }
                    }
                    try { attachHomeLogo(bg); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            if (sHomeLogoScreen == null) return;
            Object screen = sHomeLogoScreen[0];
            Context ctx = (android.content.Context) sHomeLogoScreen[1];
            ClassLoader cl = (ClassLoader) sHomeLogoScreen[2];
            try {
                // 不重建条目: 只更新旧条目的摘要/开关状态, 保证列表顺序完全不变
                Object oldEntry = XposedHelpers.callMethod(screen, "findPreference", "sbplus_home_logo_entry");
                if (oldEntry != null) {
                    int cnt = HomeLogoHelper.listLogos(ctx).size();
                    if (cnt == 0) {
                        XposedHelpers.callMethod(oldEntry, "setSummary", T("点击添加 Logo 图片到主页", "Tap to add a logo image"));
                    } else if (HomeLogoHelper.isEnabled(ctx)) {
                        XposedHelpers.callMethod(oldEntry, "setSummary",
                                T("已添加 " + cnt + " 张，点按管理（使用中）", cnt + " logo(s), tap to manage (in use)"));
                    } else {
                        XposedHelpers.callMethod(oldEntry, "setSummary",
                                T("已添加 " + cnt + " 张，点按管理（已停用）", cnt + " logo(s), tap to manage (off)"));
                    }
                    XposedHelpers.callMethod(oldEntry, "setChecked", HomeLogoHelper.isEnabled(ctx));
                    try { XposedHelpers.callMethod(screen, "notifyChanged"); } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refreshHomeLogoSection err: " + t);
        }
    }




/** 开关:去除主页搜索框内文字(搜索或输入网址)。 */
    private Object buildHomeClearTextSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", T("去除搜索框内文字", "Remove search box text"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_home_clear_text");
        XposedHelpers.callMethod(pref, "setSummary", T("隐藏主页搜索框里的搜索或输入网址提示文字", "Hide the \"Search or type URL\" hint in the search box"));
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

    /** 开关:移动添加快捷方式按钮到主页设置旁。 */
    private Object buildHomeMoveBtnSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", T("移动添加快捷方式按钮", "Move \"add shortcut\" button"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_home_move_btn");
        XposedHelpers.callMethod(pref, "setSummary", T("把添加快捷方式按钮移到主页设置左边并统一大小", "Move the add-shortcut button next to homepage settings and unify its size"));
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

    /** 视频背景选择子页:显示当前视频文件 + 选择入口 + 使用提示。 */
    private void injectVideoBgPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        String cur = videoBgPath();

        // 当前视频路径展示行。
        Object status = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(status, "setTitle", T("当前视频", "Current video"));
        XposedHelpers.callMethod(status, "setKey", "sbplus_videobg_status");
        XposedHelpers.callMethod(status, "setSummary", cur.isEmpty() ? T("尚未选择视频", "No video selected yet") : cur);
        XposedHelpers.callMethod(screen, "addPreference", status);

        // 选择视频文件行(跳转到文件管理器)。
        Object choose = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(choose, "setTitle", T("选择视频文件", "Choose video file"));
        XposedHelpers.callMethod(choose, "setKey", "sbplus_videobg_choose");
        XposedHelpers.callMethod(choose, "setSummary", T("通过系统文件管理器选择(建议 mp4)", "Pick via the system file picker (mp4 recommended)"));
        bindVideoBgChooseClick(choose, cl);
        XposedHelpers.callMethod(screen, "addPreference", choose);

        // 清除已选视频行。
        Object clear = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(clear, "setTitle", T("清除视频", "Clear video"));
        XposedHelpers.callMethod(clear, "setKey", "sbplus_videobg_clear");
        XposedHelpers.callMethod(clear, "setSummary", T("只清设置,保留视频文件", "Only clear the setting, keep the video file"));
        bindVideoBgClearClick(clear, cl);
        XposedHelpers.callMethod(screen, "addPreference", clear);

        // 删除视频文件行。
        Object del = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(del, "setTitle", T("删除视频", "Delete video"));
        XposedHelpers.callMethod(del, "setKey", "sbplus_videobg_delete");
        XposedHelpers.callMethod(del, "setSummary", T("删除已复制到 Movies/SBPlus 的视频文件", "Delete the video copied to Movies/SBPlus"));
        bindVideoBgDeleteClick(del, cl);
        XposedHelpers.callMethod(screen, "addPreference", del);

        XposedBridge.log("[SBPlus] video bg picker injected");
    }

    /** 选择视频:启动系统文件选择器(ACTION_OPEN_DOCUMENT,只选视频)。 */
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
                                            android.widget.Toast.makeText(act, T("无法打开文件选择器: ", "Cannot open file picker: ") + t.getMessage(),
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
                                        android.widget.Toast.makeText((Context) ctxObj, T(T("已清除视频背景", "Video background cleared"), "Video background cleared"),
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

    /** 删除已复制到 Movies/SBPlus 的所有视频文件(含历史重命名残留),并清掉当前设置。 */
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
                                            deleted > 0 ? (T("已删除 ", "Deleted ") + deleted + T(" 个视频文件", " video files")) : T("没有可删除的视频", "No videos to delete"),
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

    /** 通过 MediaStore 删除 Movies/SBPlus 目录下所有 SBPlus 视频(含 (1) 等重命名),返回删除数量。 */
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
            // 2) 兜底:直接删除物理目录下遗漏的 .mp4 文件(可能存在未入库的残留)。
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

    /** 填充精简设置页:列出所有设置项,每项一个 CheckBox,两列网格显示。 */
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

    /** 点击某项:切换勾选状态 + 更新 CheckBox 显示。 */
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
                                            nowHidden ? (T("已屏蔽: ", "Hidden: ") + title) : (T("已取消屏蔽: ", "Unhidden: ") + title),
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

    /** 在精简设置行注入 CheckBox(替代 radio dot)。 */
    private void injectCleanCheckBox(final android.view.View root, final String prefKey) {
        try {
            android.view.View iconFrame = root.findViewById(android.R.id.icon_frame);
            android.view.ViewGroup target;
            if (iconFrame instanceof android.view.ViewGroup) {
                target = (android.view.ViewGroup) iconFrame;
            } else {
                target = (android.view.ViewGroup) root;
            }
            // 清空 target 里已有的 CheckBox/RadioButton,避免 onBindViewHolder 复用导致叠加两个框。
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

    /** 三星 Sesl checkbox drawable 查找(失败返回 0)。 */
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

        XposedHelpers.callMethod(pref, "setTitle", T("浏览器标识", "Browser identity (UA)"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_ua_override");
        XposedHelpers.callMethod(pref, "setSummary", T("伪装 User-Agent(桌面 Chrome / 手机 / iPhone / 自定义)", "Spoof User-Agent (Desktop Chrome / Mobile / iPhone / Custom)"));
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

        // 随机浏览器标识(单选行,与下方 preset 互斥)
        Object randomRow = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(randomRow, "setTitle", T("随机浏览器标识", "Random UA"));
        XposedHelpers.callMethod(randomRow, "setKey", "sbplus_ua_random");
        XposedHelpers.callMethod(randomRow, "setSummary", T("每次启动随机刷新 UA(覆盖多平台/多系统/多浏览器)", "Randomize UA on each start (multi-platform)"));
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

        // 自定义 UA:点选后 inline 输入框可编辑固定 UA
        boolean isCustomUa = !isPresetUa(current) && current.length() > 0;
        Object custom = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(custom, "setTitle", T("自定义 UA", "Custom UA"));
        XposedHelpers.callMethod(custom, "setKey", "sbplus_ua_custom");
        XposedHelpers.callMethod(custom, "setSummary", isCustomUa ? (T("当前: ", "Current: ") + current)
                : T("输入 UA 并确认", "Enter UA and confirm"));
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
                                                T("已选择: ", "Selected: ") + label, android.widget.Toast.LENGTH_SHORT).show();
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
                                    Object ctxObj = XposedHelpers.callMethod(clicked, "getContext");
                                    if (ctxObj instanceof Context) {
                                        showUaGroupDialog((Context) ctxObj);
                                    }
                                    refreshRadioDots("sbplus_ua_random");
                                    XposedBridge.log("[SBPlus] random UA dialog shown");
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

    /** 弹出"随机浏览器标识"选项对话框:勾选参与随机的分类 + 管理自定义 UA。 */

    // ========== BetterVia-style Random UA helpers ==========
    private boolean getUaPlatEnabled(String plat) {
        return processPrefs(sAppContext).getBoolean("sbplus_ua_plat_" + plat,
            "android".equals(plat) || "ios".equals(plat));
    }
    private void setUaPlatEnabled(String plat, boolean v) {
        processPrefs(sAppContext).edit().putBoolean("sbplus_ua_plat_" + plat, v).apply();
    }
    private boolean getUaBrwEnabled(String brw) {
        return processPrefs(sAppContext).getBoolean("sbplus_ua_brw_" + brw,
            "chrome".equals(brw) || "safari".equals(brw));
    }
    private void setUaBrwEnabled(String brw, boolean v) {
        processPrefs(sAppContext).edit().putBoolean("sbplus_ua_brw_" + brw, v).apply();
    }
    private String getUaParam(String key, String def) {
        return processPrefs(sAppContext).getString("sbplus_ua_p_" + key, def);
    }
    private void setUaParam(String key, String val) {
        processPrefs(sAppContext).edit().putString("sbplus_ua_p_" + key, val).apply();
    }
    private String[] splitComma(String s) {
        if (s == null || s.trim().isEmpty()) return new String[0];
        String[] arr = s.split(",");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String x : arr) {
            String t = x.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

private void showUaGroupDialog(final Context ctx) {
        // BetterVia 风格:平台checkbox + 浏览器checkbox + 参数编辑框
        try {
            final android.widget.LinearLayout ll = new android.widget.LinearLayout(ctx);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dp(ctx, 16);
            ll.setPadding(pad, pad, pad, pad);

            // === 平台区 ===
            android.widget.TextView tvPlat = new android.widget.TextView(ctx);
            tvPlat.setText(T("平台 Platform", "Platform"));
            tvPlat.setTextSize(14);
            tvPlat.setTextColor(0xFF666666);
            tvPlat.setPadding(0, 0, 0, dp(ctx, 6));
            ll.addView(tvPlat);

            final String[] plats = {"android", "ios", "windows", "macos", "linux"};
            final String[] platNames = {
                T("Android", "Android"),
                T("iOS", "iOS"),
                T("Windows", "Windows"),
                T("macOS", "macOS"),
                T("Linux", "Linux")
            };
            final java.util.Map<String, android.widget.CheckBox> cbPlat = new java.util.LinkedHashMap<>();
            for (int i = 0; i < plats.length; i++) {
                android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
                cb.setText(platNames[i]);
                cb.setChecked(getUaPlatEnabled(plats[i]));
                cb.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
                cbPlat.put(plats[i], cb);
                ll.addView(cb);
            }

            // === 浏览器区 ===
            android.widget.TextView tvBrw = new android.widget.TextView(ctx);
            tvBrw.setText(T("浏览器 Browser", "Browser"));
            tvBrw.setTextSize(14);
            tvBrw.setTextColor(0xFF666666);
            tvBrw.setPadding(0, dp(ctx, 12), 0, dp(ctx, 6));
            ll.addView(tvBrw);

            final String[] brws = {"chrome", "safari", "edge", "firefox"};
            final String[] brwNames = {
                T("Chrome", "Chrome"),
                T("Safari", "Safari"),
                T("Edge", "Edge"),
                T("Firefox", "Firefox")
            };
            final java.util.Map<String, android.widget.CheckBox> cbBrw = new java.util.LinkedHashMap<>();
            for (int i = 0; i < brws.length; i++) {
                android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
                cb.setText(brwNames[i]);
                cb.setChecked(getUaBrwEnabled(brws[i]));
                cb.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
                cbBrw.put(brws[i], cb);
                ll.addView(cb);
            }

            // === 参数编辑区 ===
            android.widget.TextView tvParam = new android.widget.TextView(ctx);
            tvParam.setText(T("参数配置(逗号分隔)", "Parameters (comma-separated)"));
            tvParam.setTextSize(14);
            tvParam.setTextColor(0xFF666666);
            tvParam.setPadding(0, dp(ctx, 12), 0, dp(ctx, 6));
            ll.addView(tvParam);

            // Android 版本
            addEditRow(ctx, ll, T("Android 版本", "Android Versions"),
                getUaParam("android_vers", "13,14,15,16,17,18"), "android_vers");
            // Android 设备
            addEditRow(ctx, ll, T("Android 设备", "Android Devices"),
                getUaParam("android_devs", "SM-G9910,Pixel 8,Pixel 9,M2012K11AC,CPH2581,OnePlus 12"), "android_devs");
            // iOS 版本
            addEditRow(ctx, ll, T("iOS 版本", "iOS Versions"),
                getUaParam("ios_vers", "15.0,16.0,17.0,17.4,18.0,18.3"), "ios_vers");
            // 桌面 OS tokens
            addEditRow(ctx, ll, T("桌面 OS", "Desktop OS"),
                getUaParam("desktop_tokens", "Windows NT 10.0; Win64; x64,Macintosh; Intel Mac OS X 10_15_7,X11; Linux x86_64"), "desktop_tokens");
            // Chrome 版本范围(主版本号)
            addEditRow(ctx, ll, T("Chrome 版本范围", "Chrome Version Range"),
                getUaParam("chrome_range", "90-150"), "chrome_range");

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(ll);
            sv.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle(T("随机浏览器标识 - 高级配置", "Random UA - Advanced"));
            b.setView(sv);
            b.setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dlg, int which) {
                    // 保存平台
                    for (java.util.Map.Entry<String, android.widget.CheckBox> e : cbPlat.entrySet()) {
                        setUaPlatEnabled(e.getKey(), e.getValue().isChecked());
                    }
                    // 保存浏览器
                    for (java.util.Map.Entry<String, android.widget.CheckBox> e : cbBrw.entrySet()) {
                        setUaBrwEnabled(e.getKey(), e.getValue().isChecked());
                    }
                    // 参数已在 addEditRow 的 TextWatcher 中实时保存
                    saveRandomUaEnabled(true);
                    try {
                        android.widget.Toast.makeText(ctx, T("已保存:下次启动随机生效", "Saved: takes effect on next start"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    XposedBridge.log("[SBPlus] BetterVia-style UA config saved");
                }
            });
            b.setNegativeButton(T("取消", "Cancel"), null);
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] show UA advanced dialog error: " + t);
        }
    }

    private void addEditRow(Context ctx, android.widget.LinearLayout parent, String label, String value, final String key) {
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(0xFF333333);
        tv.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));
        parent.addView(tv);

        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(value);
        et.setTextSize(12);
        et.setTextColor(0xFF111111);  // 深色文字
        et.setHintTextColor(0xFF999999);  // 灰色提示
        et.setBackgroundColor(0xFFF5F5F5);  // 浅灰背景
        et.setMinLines(2);  // 最少2行
        et.setMaxLines(6);  // 最多6行
        et.setVerticalScrollBarEnabled(true);  // 超过最大行数显示滚动条
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);  // 顶部对齐
        et.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        et.setBackgroundColor(0xFFF5F5F5);
        et.setSingleLine(false);
        et.setMaxLines(3);
        parent.addView(et);

        // 实时保存
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                setUaParam(key, s.toString().trim());
            }
        });
    }


    /** 弹出某分类的多行 UA 编辑框:每行一条,可增删改,保存后记住。 */
    private void showGroupUaEditor(final Context ctx, final int gi) {
        try {
            final java.util.List<String> cur = new java.util.ArrayList<String>(loadGroupUas(gi));
            java.util.List<String> init = new java.util.ArrayList<String>(cur);
            final android.widget.EditText input = new android.widget.EditText(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < init.size(); i++) { if (sb.length() > 0) sb.append("\n"); sb.append(init.get(i)); }
            input.setText(sb.toString());
            input.setSingleLine(false);
            input.setMinLines(6);
            input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            int p = dp(ctx, 16);
            input.setPadding(p, p, p, p);
            input.setHorizontallyScrolling(false);
            input.setTextColor(0xFF333333);
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle(UA_GROUPS[gi][0] + T(" - 编辑 UA(每行一条)", " - edit UAs (one per line)"));
            b.setView(input);
            b.setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d2, int w2) {
                    String t = (input.getText() == null) ? "" : input.getText().toString();
                    java.util.List<String> out = new java.util.ArrayList<String>();
                    for (String line : t.split("\n")) { String lt = line.trim(); if (!lt.isEmpty()) out.add(lt); }
                    saveGroupUas(gi, out);
                    XposedBridge.log("[SBPlus] UA group " + gi + " edited -> " + out.size() + " uas");
                }
            });
            b.setNegativeButton(T("取消", "Cancel"), null);
            b.show();
        } catch (Throwable t) { XposedBridge.log("[SBPlus] show group UA editor error: " + t); }
    }

    private void refreshCustomUaList(final Context ctx, final android.widget.LinearLayout box, final java.util.List<String> customs) {
        try {
            box.removeAllViews();
            if (customs.isEmpty()) {
                android.widget.TextView none = new android.widget.TextView(ctx);
                none.setText(T("(暂无自定义 UA)", "(no custom UA)"));
                none.setTextColor(0xFFBBBBBB);
                none.setTextSize(13);
                box.addView(none);
                return;
            }
            for (int i = 0; i < customs.size(); i++) {
                final int idx = i;
                android.widget.TextView tv = new android.widget.TextView(ctx);
                String t = customs.get(i);
                tv.setText((i + 1) + ". " + (t.length() > 46 ? t.substring(0, 43) + "..." : t));
                tv.setTextSize(12);
                tv.setTextColor(0xFF666666);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(ctx, 4);
                tv.setLayoutParams(lp);
                box.addView(tv);
                tv.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(android.view.View v) {
                        customs.remove(idx);
                        refreshCustomUaList(ctx, box, customs);
                        return true;
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] refresh custom UA list error: " + t); }
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

    /**
     * 自适应:取得三星 PreferenceFragmentCustom 的父类。
     * PreferenceFragmentCustom 继承被混淆的 androidx PreferenceFragmentCompat(旧版本叫 H2.A),
     * 不写死混淆名,通过明文类名动态取 superclass,随浏览器版本自动适配。
     */
    private Class<?> findPreferenceParent(ClassLoader cl) {
        try {
            Class<?> custom = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom", cl);
            Class<?> parent = custom.getSuperclass();
            if (parent != null) {
                XposedBridge.log("[SBPlus] PreferenceFragmentCustom parent = " + parent.getName());
            }
            return parent;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] resolve preference parent failed: " + t);
            return null;
        }
    }

    /**
     * 自适应:对目标对象按候选方法名列表调用,返回第一个成功结果。
     * 替换像 PreferenceManager#a(Context) 这种被混淆的方法名,候选顺序标准名优先再试混淆名。
     */
    private Object callMethodByCandidates(Object target, String[] candidates,
                                          Class<?>[] argTypes, Object[] args) {
        if (target == null) return null;
        for (String name : candidates) {
            try {
                Object res = XposedHelpers.callMethod(target, name, args);
                XposedBridge.log("[SBPlus] callMethodByCandidates hit: " + name);
                return res;
            } catch (Throwable t) {
                // 尝试下一个候选
            }
        }
        return null;
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

    /** 手动导航到脚本详情子页(传脚本文件名)。 */
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
     * UA override: 浏览器标识 开关开启时,在 SBrowserCommandLine.initialize() 完成后,
     * 追加 Chromium 标准 switch "user-agent"(TerraceCommandLine.appendSwitchWithValue),
     * 完整替换 UA(而不是三星 csc-feature-user-agent 的拼接)。需重启浏览器后生效。
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

    /** 屏蔽更新:阻断更新检查入口 + 追加官方 disable-update-dialog switch(屏蔽弹窗)。 */
    private void hookBlockUpdate(ClassLoader cl) {
        try {
            Class<?> updateMgr = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.stub.UpdateManager", cl);

            // 自动检查入口(页面加载完成后自动检查)。
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

        // 兜底:阻断底层商店检查(Galaxy Store / Google Play 的网络 AIDL 调用)。
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

        // 屏蔽设置页顶部「更新应用程序」卡片(独立于精简设置页开关)。
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

        // 屏蔽「关于」页更新按钮:把 UPDATE 状态降级为 NO_UPDATE,更新按钮永不显示。
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

        // 彻底禁止应用升级:阻断跳转商店(callAppStore)。
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

        // 屏蔽「关于」红点:hasNewUpdate 返回 false,设置页徽标不再计入更新。
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

        // 屏蔽「更多」/「设置」入口的聚合红点(getSettingsBadgeCount 累加更新+AI+隐私等各类提示)。
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

        // 屏蔽「更多」按钮小红点:强制 updateOptionMenuBadgeVisibility 的参数为 0。
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
     * 主页视频背景:
     *  (1) 在浏览器设置页里选择视频后,startActivityForResult 的结果回到
     *      SettingsActivity.onActivityResult,我们在这里拿到 content:// URI,
     *      把视频复制到公共目录(/sdcard/SBPlus/video_bg.mp4),存下绝对路径。
     *  (2) 主页背景 View(QuickAccessCustomBackground)是 QuickAccessMainLayout 的第一个
     *      子 View(ImageView)。开关开启且路径有效时,叠一个 VideoView 到它上面循环
     *      静音播放,同时亮起 dim_layer 遮罩以保证内容可读。
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
                                        if (fn != null) saveSource(fn, T("本地导入", "Local import"));
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                fn == null ? T("导入失败", "Import failed") : (T("已导入脚本: ", "Imported script: ") + fn),
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        XposedBridge.log("[SBPlus] userscript imported: " + fn);
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("读取文件失败", "Failed to read file"), android.widget.Toast.LENGTH_SHORT).show();
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
                                            showBookmarkTreeDialog(act, T("选择要导入的书签", "Select bookmarks to import"), tree, false);
                                        } else {
                                            android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                    T("无法获取界面环境", "Cannot get UI context"), android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("读取文件失败", "Failed to read file"), android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if (req == REQUEST_FONT_PICK) {
                                    int res = (Integer) param.args[1];
                                    android.content.Intent data = (android.content.Intent) param.args[2];
                                    if (res != android.app.Activity.RESULT_OK || data == null
                                            || data.getData() == null) return;
                                    android.net.Uri uri = data.getData();
                                    boolean ok = FontHelper.addFontFromUri((android.content.Context) param.thisObject, uri);
                                    if (ok) {
                                        // 自动选中刚添加的字体并启用
                                        java.util.List<String> list = FontHelper.listFonts((android.content.Context) param.thisObject);
                                        String newest = list.isEmpty() ? "" : list.get(list.size() - 1);
                                        if (!newest.isEmpty()) {
                                            FontHelper.selectFont((android.content.Context) param.thisObject, newest);
                                            FontHelper.setEnabled((android.content.Context) param.thisObject, true);
                                        }
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("字体已添加: ", "Font added: ") + newest,
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        XposedBridge.log("[SBPlus] font added: " + newest);
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("字体添加失败", "Failed to add font"), android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if (req == REQUEST_HOME_LOGO_PICK) {
                                    int res = (Integer) param.args[1];
                                    android.content.Intent data = (android.content.Intent) param.args[2];
                                    if (res != android.app.Activity.RESULT_OK || data == null
                                            || data.getData() == null) return;
                                    android.net.Uri uri = data.getData();
                                    String saved = HomeLogoHelper.addLogoFromUri((android.content.Context) param.thisObject, uri);
                                    if (saved != null && !saved.isEmpty()) {
                                        // 永久读取权限(ACTION_OPEN_DOCUMENT 返回的 URI 需要 takePersistableUriPermission)
                                        try {
                                            int flags = data.getFlags() & (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                            ((android.app.Activity) param.thisObject).getContentResolver().takePersistableUriPermission(uri, flags);
                                        } catch (Throwable ignoredPerm) {}
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("Logo 已添加", "Logo added"), android.widget.Toast.LENGTH_SHORT).show();
                                        XposedBridge.log("[SBPlus] home logo saved: " + saved);
                                        // 刷新主页美化页的 Logo 区(数字+列表)
                                        refreshHomeLogoSection();
                                        // 若管理页面还开着,就地刷新图片列表
                                        try {
                                            if (sHomeLogoPageRebuild != null && sHomeLogoPageDlg != null
                                                    && sHomeLogoPageDlg.isShowing()) {
                                                sHomeLogoPageRebuild.run();
                                            }
                                        } catch (Throwable ignoredRebuild) {}
                                    } else {
                                        android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                                T("Logo 添加失败", "Failed to add logo"), android.widget.Toast.LENGTH_SHORT).show();
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
                                            T("视频背景已设置", "Video background set"), android.widget.Toast.LENGTH_SHORT).show();
                                    XposedBridge.log("[SBPlus] video bg saved: " + saved);
                                } else {
                                    android.widget.Toast.makeText((android.content.Context) param.thisObject,
                                            T("视频复制失败", "Failed to copy video"), android.widget.Toast.LENGTH_SHORT).show();
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
        //     -> 三星自定义方法(非标准 View.onFinishInflate),在背景 View 置为 VISIBLE 时调用,
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
                                try { attachHomeLogo(param.thisObject); } catch (Throwable ignoredLogo) {}
                                try { attachHomeClock(param.thisObject); } catch (Throwable ignoredClock) {}
                                // 主页背景出现 -> 隐藏嗅探/油猴图标(确保主页不显示)
                                try {
                                    final android.view.View bgV = (android.view.View) param.thisObject;
                                    bgV.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            hideToolbarIcons();
                                        }
                                    }, 300);
                                    bgV.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            hideToolbarIcons();
                                        }
                                    }, 1500);
                                } catch (Throwable ignored) {}
                                dumpHomeIcons(param.thisObject);
                                startToolbarIconSync();
                                // 强制染色底部工具栏图标(用 Activity 整个窗口 decor view)
                                final android.view.View bg = (android.view.View) param.thisObject;
                                final android.app.Activity act = sCurrentActivity;
                                if (act != null && act.getWindow() != null
                                        && act.getWindow().getDecorView() != null) {
                                    final android.view.View root = act.getWindow().getDecorView();
                                    root.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            forceApplyAllToolbarIcons(root);
                                        }
                                    }, 1000);
                                    root.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            forceApplyAllToolbarIcons(root);
                                        }
                                    }, 2500);
                                    root.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            forceApplyAllToolbarIcons(root);
                                        }
                                    }, 4000);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] attachVideoBackground error: " + t);
                            }
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessCustomBackground.onFinishInflate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] video bg view hook failed: " + t);
        }

        // (3) 主页 UI 改造:移动T("添加快捷方式", "Add shortcut")按钮 + 搜索框透明化。
        try {
            applyQuickAccessUiTweaks(cl);
            XposedBridge.log("[SBPlus] quickaccess ui tweaks applied");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] quickaccess ui tweaks failed: " + t);
        }
    }

    /** 把选中的视频 content URI 通过 MediaStore 插到公共 Video 集合,返回可访问的 content URI(失败返回 null)。 */
    // ==================== 全局自定义字体 ====================
    private java.util.WeakHashMap<android.widget.TextView, java.lang.Boolean> sFontTinted = new java.util.WeakHashMap<>();
    private java.util.Set<android.widget.TextView> sPendingFontViews =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<android.widget.TextView, java.lang.Boolean>());
    private volatile android.graphics.Typeface sFontTypeface = null;
    private volatile String fontPathCache = "";
    private volatile boolean sFontLoading = false;

    /** 全局自定义字体: 把浏览器所有 TextView 的字体换成用户选的自定义字体(后台加载, 不阻塞主线程)。 */
    private void hookGlobalFont(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setTypeface",
                    android.graphics.Typeface.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { applyFontForce((android.widget.TextView) param.thisObject); } catch (Throwable ignored) {}
                        }
                    });
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setTypeface",
                    android.graphics.Typeface.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (sFontHookDiag++ % 50 == 0)
                                    XposedBridge.log("[SBPlus] FONT setTypeface hook firing, shouldApply="
                                        + (sAppContext != null && FontHelper.shouldApply(sAppContext)));
                                applyFontForce((android.widget.TextView) param.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    });
            // hook setText: 覆盖面更大, 让更多界面 TextView 应用自定义字体
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                        java.lang.CharSequence.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try { applyFontToTextView((android.widget.TextView) param.thisObject); } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                        int.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try { applyFontToTextView((android.widget.TextView) param.thisObject); } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable ignored) {}
            // onDraw 兜底: 覆盖所有被绘制的 TextView(安全版, 只对未标记的 setTypeface 一次, 不加 postInvalidate 不打日志)
            try {
                XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "onDraw",
                        android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try { applyFontToTextView((android.widget.TextView) param.thisObject); } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable ignored) {}
            // onAttachedToWindow: 每个 View 挂载到窗口时刷新字体(覆盖后动态创建的面板/菜单 TextView)
            try {
                XposedHelpers.findAndHookMethod("android.view.View", cl, "onAttachedToWindow",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    android.view.View v = (android.view.View) param.thisObject;
                                    if (v == null) return;
                                    if (sAppContext == null || !FontHelper.shouldApply(sAppContext)) return;
                                    if (sFontTypeface != null) {
                                        if (v instanceof android.widget.TextView) {
                                            if (!sFontTinted.containsKey(v)) applyFontToTextView((android.widget.TextView) v);
                                        } else {
                                            applyFontRecursive(v, sFontTypeface);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable ignoredAtw) {}
            XposedBridge.log("[SBPlus] global font hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] global font hook failed: " + t);
        }
    }

    /** 递归遍历 View 树, 对每个 TextView 显式设置字体并强制重绘。 */
    private void applyFontRecursive(android.view.View view, android.graphics.Typeface tf) {
        try {
            if (view instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) view;
                if (!sFontTinted.containsKey(tv)) {
                    sFontTinted.put(tv, java.lang.Boolean.TRUE);
                    tv.setTypeface(tf);
                    tv.requestLayout();
                    tv.invalidate();
                }
                return;
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    applyFontRecursive(vg.getChildAt(i), tf);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 强制同步: setTypeface hook 内每次校验, 若 tv 当前字体不是我们的则设回。用“本次设置级”防递归锁。 */
    private final java.util.Set<Object> sTvSettingFonts = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private void applyFontForce(android.widget.TextView tv) {
        try {
            if (tv == null) return;
            // 本次 setTypeface 正在执行(嵌套 hook), 防无限递归
            if (sTvSettingFonts.contains(tv)) return;
            if (sAppContext == null || !FontHelper.shouldApply(sAppContext)) return;
            if (sFontTypeface == null) return;
            String p = FontHelper.selectedPath(sAppContext);
            if (p == null || !p.equals(fontPathCache)) { ensureFontLoadedAsync(); return; }
            android.graphics.Typeface cur = null;
            try { cur = tv.getTypeface(); } catch (Throwable ignored) {}
            if (cur == sFontTypeface) return; // 已是我们的字体, 不动
            sTvSettingFonts.add(tv);
            try {
                tv.setTypeface(sFontTypeface);
            } finally {
                sTvSettingFonts.remove(tv);
            }
        } catch (Throwable ignored) {}
    }

    private void applyFontToTextView(android.widget.TextView tv) {
        try {
            if (tv == null) return;
            if (sFontTinted.containsKey(tv)) return; // 已换过, 跳过
            if (sAppContext == null || !FontHelper.shouldApply(sAppContext)) return;
            String p = FontHelper.selectedPath(sAppContext);
            if (sFontDiagDetail++ % 20 == 0)
                XposedBridge.log("[SBPlus] FONT apply path=" + p + " sel='" + FontHelper.selectedName(sAppContext)
                    + "' ext=" + (sAppContext != null ? FontHelper.selectedPath(sAppContext) : "null"));
            if (p == null || p.isEmpty()) return;
            try { sPendingFontViews.add(tv); } catch (Throwable ignored) {}
            if (sFontTypeface == null || !p.equals(fontPathCache)) {
                if (sFontDiagDetail++ % 60 == 0) XposedBridge.log("[SBPlus] FONT cache-miss tf=" + (sFontTypeface != null) + " pc='" + fontPathCache + "'");
                ensureFontLoadedAsync();
                return;
            }
            if (sFontDiagDetail++ % 60 == 0) XposedBridge.log("[SBPlus] FONT cache-hit applying");
            try {
                sFontTinted.put(tv, java.lang.Boolean.TRUE); // 先标记, 防止 setTypeface 递归再进
                tv.setTypeface(sFontTypeface);
                if (sFontDiagCount++ % 100 == 0) XposedBridge.log("[SBPlus] FONT applied path=" + p);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
    private int sFontDiagCount = 0;
    private int sFontDiagDetail = 0;
    private int sFontHookDiag = 0;

    /** 后台线程加载 Typeface, 完成后在主线程刷新所有待应用/已应用的 TextView。 */
    private void ensureFontLoadedAsync() {
        if (sAppContext == null) return;
        final String p = FontHelper.selectedPath(sAppContext);
        if (p == null || p.isEmpty()) return;
        if (sFontTypeface != null && p.equals(fontPathCache)) return;
        if (sFontLoading) {
            // 上次加载未完成或卡住: 若已超过超时则允许重试, 否则跳过本次
            long now = System.currentTimeMillis();
            if (sFontLoadStart != 0 && (now - sFontLoadStart) < 15000L) return;
            sFontLoading = false;
        }
        sFontLoading = true;
        sFontLoadStart = System.currentTimeMillis();
        XposedBridge.log("[SBPlus] FONT loading start p=" + p);
        final android.content.Context c = sAppContext;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final android.graphics.Typeface tf = FontHelper.loadTypeface(c);
                    if (tf != null) {
                        sFontTypeface = tf;
                        fontPathCache = p;
                        XposedBridge.log("[SBPlus] FONT loaded OK path=" + p);
                        // 主线程刷新所有待应用的 TextView + 递归遍历当前 Activity 全树强制生效
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.post(new Runnable() {
                            public void run() {
                                try {
                                    android.app.Activity act = sCurrentActivity;
                                    if (act != null && act.getWindow() != null && act.getWindow().getDecorView() != null) {
                                        applyFontRecursive(act.getWindow().getDecorView().getRootView(), tf);
                                    }
                                    java.util.List<android.widget.TextView> list;
                                    synchronized (sPendingFontViews) {
                                        list = new java.util.ArrayList<>(sPendingFontViews);
                                    }
                                    for (final android.widget.TextView v : list) {
                                        try {
                                            if (v != null && !sFontTinted.containsKey(v)) { v.setTypeface(tf); sFontTinted.put(v, java.lang.Boolean.TRUE); }
                                        } catch (Throwable ignored) {}
                                    }
                                    XposedBridge.log("[SBPlus] FONT tree refresh done");
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                } catch (Throwable ignored) {} finally {
                    sFontLoading = false;
                    XposedBridge.log("[SBPlus] FONT loading done");
                }
            }
        }).start();
    }
    private volatile long sFontLoadStart = 0;

    private String copyVideoToPublicDir(android.content.Context ctx, android.net.Uri uri) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            in = cr.openInputStream(uri);
            if (in == null) return null;

            // 先把源视频整个读入内存,拿到真实长度(用于后续正确写入 SIZE 元数据)。
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[65536];
            int r;
            while ((r = in.read(tmp)) > 0) bos.write(tmp, 0, r);
            in.close();
            in = null;
            byte[] videoBytes = bos.toByteArray();

            // 插入公共 Video 集合,显式写入 SIZE 与时长无关的关键元数据。
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

            // 清除 pending 标记,再次确认 SIZE 已正确。
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
            done.put(android.provider.MediaStore.Video.Media.SIZE, videoBytes.length);
            cr.update(outUri, done, null, null);

            // 反查真实文件绝对路径(_data),返回给播放层直接 setVideoPath。
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

    /** 主页 UI 改造:需求1--把T("添加快捷方式", "Add shortcut")按钮移到"主页设置"左边并统一大小;需求2--搜索或输入网址横线透明化。 */
    private void applyQuickAccessUiTweaks(ClassLoader cl) {
        // ---- 需求2:搜索框(假地址栏)透明化 ----
        try {
            Class<?> dummyBar = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessDummyUrlBar", cl);
            XposedHelpers.findAndHookMethod(dummyBar, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                final android.view.View bar = (android.view.View) param.thisObject;
                                // 主页搜索框(假地址栏)实例: logo/时钟跟随动画的基准目标
                                sHomeLogoSbView = bar;
                                try {
                                    sHomeLogoSbHeight = bar.getHeight();
                                } catch (Throwable ignored) {}
                                if (!isHomeClearTextEnabled()) return;
                                // 延后执行两步,防止 viewmodel/observer 重置样式
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

        // ---- 需求1:在"主页设置"按钮左边插入等大的T("添加快捷方式", "Add shortcut")按钮,并隐藏网格里的原添加格子 ----
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
                                    // 主页布局每次重建时, 按最新偏好重新挂载 logo/时钟(开关/大小修改后回主页立即生效)
                                    try {
                                        if (sHomeLogoBgView != null) {
                                            android.view.ViewGroup bgp = (android.view.ViewGroup) sHomeLogoBgView.getParent();
                                            if (bgp != null && sHomeLogoBgView.isAttachedToWindow()) {
                                                refreshHomeLogoSection();
                                            }
                                        }
                                    } catch (Throwable t2) { XposedBridge.log("[SBPlus] relogo err: " + t2); }
                                    try {
                                        if (sHomeClockBg != null && sHomeClockBg.isAttachedToWindow()) {
                                            refreshHomeClock();
                                        }
                                    } catch (Throwable t3) { XposedBridge.log("[SBPlus] reclock err: " + t3); }
                                }
                            }, 400);
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessMainLayout.onFinishInflate hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] main layout hook failed: " + t);
        }

        // 视图挂回窗口时(从后台恢复/重建), 按最新偏好重新挂载 logo/时钟
        try {
            Class<?> mainLayout2 = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessMainLayout", cl);
            XposedHelpers.findAndHookMethod(mainLayout2, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                final android.view.View root = (android.view.View) param.thisObject;
                                root.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            if (sHomeLogoBgView != null && sHomeLogoBgView.isAttachedToWindow()) {
                                                refreshHomeLogoSection();
                                            }
                                        } catch (Throwable t2) { XposedBridge.log("[SBPlus] relogo2 err: " + t2); }
                                        try {
                                            if (sHomeClockBg != null && sHomeClockBg.isAttachedToWindow()) {
                                                refreshHomeClock();
                                            }
                                        } catch (Throwable t3) { XposedBridge.log("[SBPlus] reclock2 err: " + t3); }
                                    }
                                }, 500);
                            } catch (Throwable ignored) {}
                        }
                    });
            XposedBridge.log("[SBPlus] QuickAccessMainLayout.onAttachedToWindow hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] main layout attach hook failed: " + t);
        }
    }

    /** 在主页根 View 上:把T("添加快捷方式", "Add shortcut")按钮插到"主页设置"按钮左边,隐藏原网格添加格子。 */
    private void rearrangeQuickAccessButtons(android.view.View root) {
        int mgmtId = resId("general_management", "id");
        int addContainerId = resId("add_view_container", "id");
        // 原"添加"格子的图标(layer-list:圆底 + "+"号,自带 tint):按深/浅色主题选择
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
        // mgmt 的父容器(通常 wrap_content 的 RelativeLayout)在 header 的 LinearLayout 里;
        // 若 mgmt 父是 RelativeLayout,则新按钮要插到它的父(LinearLayout)中、mgmt 父之前,
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

        // 新按钮:放在与 mgmt 同级的容器里(mgmt 通常在 RelativeLayout 内,插到它左边)。
        // 用 mgmt 的 context 创建(保留 Activity 主题,避免图标/ripple 无 tint),并复制其图标与尺寸。
        android.content.Context mgmtCtx = mgmt.getContext();
        android.widget.ImageButton addBtn = new android.widget.ImageButton(mgmtCtx);
        addBtn.setContentDescription(T("添加快捷方式", "Add shortcut"));
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

        // 若插到 LinearLayout:给按钮设置与 mgmt 相同的尺寸,并垂直居中,右边距与"主页设置↔头像"间距一致
        if (insertTarget instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout ll = (android.widget.LinearLayout) insertTarget;
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            // 主页设置与头像之间是 account 的 marginStart(10dip),新按钮与主页设置也用同样的右边距
            lp.setMarginEnd((int) (10 * sAppContext.getResources().getDisplayMetrics().density));
            addBtn.setLayoutParams(lp);
            ll.addView(addBtn, insertIndex);
        } else if (insertTarget instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout rl = (android.widget.RelativeLayout) insertTarget;
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(size, size);
            if (insertIndex == rl.indexOfChild(mgmt)) {
                // 直接在 mgmt 之前(左侧)
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

    /** 保留搜索框背景与放大镜,只清空提示文字、去掉 elevation 阴影(保留框/图标/点击热区)。 */
    private void applyDummyBarTransparent(android.view.View bar) {
        if (bar == null) return;
        try {
            // 只清空提示文字(搜索或输入网址),完全保留搜索框的默认外观:
            // 背景框(mCardBlurView.foreground)、放大镜、外层柔和阴影(ShadowDrawHelper 8dp elevation)
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

    /** 触发T("添加快捷方式", "Add shortcut"):反射调用 QuickAccessIconRecyclerAdapter.showAddShortcutDialog()(真正入口)。 */
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

    /** 在 root 视图树中查找 QuickAccessIconRecyclerAdapter(主页图标网格的适配器)。 */
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

    /** 在 QuickAccessCustomBackground 所在父容器叠加 SurfaceView+MediaPlayer,循环播放背景视频。 */
    /** 遍历某 View 子树, 打印底部区域(屏幕下半部)的 ImageView 图标信息. */
    private void dumpBottomIcons(android.view.View root) {
        try {
            java.util.List<android.view.View> ivs = new java.util.ArrayList<>();
            collectImageViews(root, ivs);
            XposedBridge.log("[SBPlus] BOTICON dump: total ImageView=" + ivs.size());
            android.graphics.Rect vr = new android.graphics.Rect();
            root.getGlobalVisibleRect(vr);
            int h = root.getHeight();
            for (android.view.View v : ivs) {
                int[] xy = new int[2];
                try { v.getLocationOnScreen(xy); } catch (Throwable ignored) { continue; }
                // 打印所有, 区分底部
                String tag = (h > 0 && xy[1] > h / 2) ? "BOTTOM" : "top   ";
                String ref = "";
                try { ref = v.getResources().getResourceName(v.getId()); } catch (Throwable ignored) { ref = "id=" + v.getId(); }
                String dcls = "";
                try {
                    android.graphics.drawable.Drawable d = ((android.widget.ImageView) v).getDrawable();
                    dcls = d == null ? "null" : d.getClass().getSimpleName();
                } catch (Throwable ignored) {}
                XposedBridge.log("[SBPlus] BOTICON " + tag + " cls=" + v.getClass().getSimpleName()
                        + " y=" + xy[1] + " " + ref + " drawable=" + dcls);
            }
        } catch (Throwable ignored) {}
    }

    /** 给 TextView 的 compound drawable 图标染上 S_HOME_ICON 主题色. */
    private void tintCompoundDrawables(Object tvObj) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return;
            if (!(tvObj instanceof android.widget.TextView)) return;
            android.widget.TextView tv = (android.widget.TextView) tvObj;
            if (!isBrowserUiIcon(tv)) return;
            android.graphics.drawable.Drawable[] dr = tv.getCompoundDrawables();
            for (android.graphics.drawable.Drawable d : dr) {
                if (d != null) { try { d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {}
    }

    /** 强制染色所有底部工具栏图标(无论是否已染,每次都重新染,覆盖三星重置). */
    private void dumpThemeSlots(android.content.Context ctx) {
        try {
            int[] slots = { ThemeColorHelper.S_HOME_ICON, ThemeColorHelper.S_HOME_TEXT,
                ThemeColorHelper.S_SETTINGS_TITLE, ThemeColorHelper.S_SETTINGS_DESC,
                ThemeColorHelper.S_SETTINGS_BG, ThemeColorHelper.S_WEB_TEXT,
                ThemeColorHelper.S_WEB_BG, ThemeColorHelper.S_SWITCH_ON,
                ThemeColorHelper.S_SWITCH_THUMB, ThemeColorHelper.S_SWITCH_OFF };
            StringBuilder sb = new StringBuilder("[SBPlus] SLOTS");
            for (int s : slots) {
                int v = ThemeColorHelper.getSlot(ctx, s);
                sb.append(" [" + s + "=" + (v==-1?"unset":("#"+Integer.toHexString(v))) + "]");
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {}
    }

    private void forceApplyAllToolbarIcons(android.view.View root) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            dumpThemeSlots(ctx);
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return;
            String[] targetIds = {
                "action_backward", "action_forward", "action_home", "action_bookmarks",
                "bottombar_option_menu", "bottombar_browsing_assist",
                "navigation_bar_item_icon_view", "news_feed_tab_add_button_icon"
            };
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (String t : targetIds) targets.add(t);
            // 遍历所有 View(不只 ImageView),按 id 匹配
            java.util.List<android.view.View> allViews = new java.util.ArrayList<>();
            collectAllViews(root, allViews);
            int done = 0;
            java.util.Set<String> found = new java.util.HashSet<>();
            for (android.view.View v : allViews) {
                String idName = "";
                try { idName = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) { continue; }
                if (!targets.contains(idName)) continue;
                found.add(idName);
                // "添加快捷方式"图标: 只染中间+号, 外环保持原色 -> 用 tintAddButtonPlus 反射拆 path
                if ("news_feed_tab_add_button_icon".equals(idName)) {
                    if (v instanceof android.widget.ImageView) {
                        tintAddButtonPlus((android.widget.ImageView) v, icol);
                    }
                    continue;
                }
                // 尝试染色: 这些 target id 是工具栏图标按钮(非背景), 直接染 drawable
                if (v instanceof android.widget.ImageView) {
                    try {
                        android.widget.ImageView iv = (android.widget.ImageView) v;
                        android.graphics.drawable.Drawable d = iv.getDrawable();
                        if (d != null) {
                            iv.setImageTintList(android.content.res.ColorStateList.valueOf(icol));
                            iv.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
                            d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                            done++;
                        }
                    } catch (Throwable ignored) {}
                }
                // 非 ImageView 一律不染 background(避免页面级背景被染)
            }
            // 报告找到和未找到的
            java.util.Set<String> missing = new java.util.HashSet<>(targets);
            missing.removeAll(found);
            XposedBridge.log("[SBPlus] forceApply: " + done + " tinted, found=" + found + ", missing=" + missing);
        } catch (Throwable ignored) {}
    }

    /** 遍历所有 View (递归). */
    private static void collectAllViews(android.view.View v, java.util.List<android.view.View> out) {
        if (v == null) return;
        out.add(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAllViews(g.getChildAt(i), out);
            }
        }
    }

    /** 按 id 匹配底部工具栏图标并染上 S_HOME_ICON 主题色. */
    private void applyToolbarIconTint(android.view.View root) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return;
            String[] targetIds = {
                "action_backward", "action_forward", "action_home", "action_bookmarks",
                "bottombar_option_menu", "bottombar_browsing_assist",
                "navigation_bar_item_icon_view", "news_feed_tab_add_button_icon"
            };
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (String t : targetIds) targets.add(t);
            java.util.List<android.view.View> ivs = new java.util.ArrayList<>();
            collectImageViews(root, ivs);
            int done = 0;
            for (android.view.View v : ivs) {
                if (!(v instanceof android.widget.ImageView)) continue;
                String idName = "";
                try { idName = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) { continue; }
                if (!targets.contains(idName)) continue;
                try {
                    // 这些 target id 本身就是工具栏图标按钮(非背景), 直接染即可
                    ((android.widget.ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(icol));
                    ((android.widget.ImageView) v).setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
                    android.graphics.drawable.Drawable d = ((android.widget.ImageView) v).getDrawable();
                    if (d != null) {
                        d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                    done++;
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("[SBPlus] BOTICON applyTint icol=" + icol + " tinted=" + done);
        } catch (Throwable ignored) {}
    }

    private void collectImageViews(android.view.View v, java.util.List<android.view.View> out) {
        if (v == null) return;
        if (v instanceof android.widget.ImageView) out.add(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectImageViews(g.getChildAt(i), out);
        }
    }

    /** 从当前主页背景 View 找到 Activity 根视图并 dump 底部图标. */
    private void dumpHomeIcons(Object bg) {
        try {
            if (!(bg instanceof android.view.View)) return;
            android.view.View v = (android.view.View) bg;
            android.content.Context c = v.getContext();
            while (c != null && !(c instanceof android.app.Activity)) {
                if (c instanceof android.content.ContextWrapper) c = ((android.content.ContextWrapper) c).getBaseContext();
                else break;
            }
            if (c instanceof android.app.Activity) {
                android.app.Activity a = (android.app.Activity) c;
                final android.view.View root = a.getWindow().getDecorView();
                final android.view.View parent = v;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            XposedBridge.log("[SBPlus] BOTICON root=" + root.getClass().getName());
                            android.view.View rv = (android.view.View) parent.getRootView();
                            dumpBottomIcons(rv);
                            applyToolbarIconTint(rv);
                        } catch (Throwable ignored) {}
                    }
                }, 1200);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            android.view.View rv2 = (android.view.View) parent.getRootView();
                            applyToolbarIconTint(rv2);
                        } catch (Throwable ignored) {}
                    }
                }, 2600);
            }
        } catch (Throwable ignored) {}
    }

    /** 已染主题色的 ImageView 记录(懒染色: 只在未染/被覆盖时重染, 避免每帧重复导致卡顿). */
    private static java.util.WeakHashMap<android.widget.ImageView, Boolean> sIconTinted = new java.util.WeakHashMap<>();

    /** 懒染色: 若该图标尚未染成 S_HOME_ICON(或被三星覆盖回原色)则重染; 否则跳过省性能. */
    private void ensureIconTint(android.widget.ImageView iv) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return;
            // 地址栏内的图标: 只染刷新按钮和收藏,其他(含跳转App图标)全部跳过
            android.view.ViewGroup tg = sToolbarParentCache;
            if (tg != null) {
                android.view.ViewParent ip = iv.getParent();
                while (ip != null) {
                    if (ip == tg) {
                        String idn = "";
                        try { idn = iv.getResources().getResourceEntryName(iv.getId()); } catch (Throwable ignored) {}
                        if (!idn.equals("toolbar_reload") && !idn.equals("bookmark_star_icon")) {
                            android.graphics.drawable.Drawable dd = iv.getDrawable();
                            String cfinfo = "none";
                            if (dd != null) {
                                android.graphics.ColorFilter cf = dd.getColorFilter();
                                if (cf instanceof android.graphics.PorterDuffColorFilter) {
                                    try { cfinfo = Integer.toHexString(reflectColorFilterColor(cf)); } catch (Throwable ignored) { cfinfo = "cf"; }
                                } else if (cf != null) { cfinfo = cf.getClass().getSimpleName(); }
                            }
                            // 强制清除地址栏非刷新/收藏图标的任何染色(drawable + view层 tint),恢复原色
                            try {
                                if (dd != null) dd.clearColorFilter();
                            } catch (Throwable ignored) {}
                            try { iv.setImageTintList(null); } catch (Throwable ignored) {}
                            try { iv.clearColorFilter(); } catch (Throwable ignored) {}
                            if (false) XposedBridge.log("[SBPlus] IC-SKIP addrbar id=" + idn + " cf=" + cfinfo + " cleared");
                            return;
                        }
                        break;
                    }
                    ip = ip.getParent();
                }
            }
            if (!isBrowserUiIcon(iv)) {
                // 诊断:浏览助手为何被跳过
                try {
                    String idn = iv.getResources().getResourceEntryName(iv.getId());
                    if (idn.equals("bottombar_browsing_assist")) {
                        XposedBridge.log("[SBPlus] ICDIAG bottombar_browsing_assist REJECTED by isBrowserUiIcon");
                    }
                } catch (Throwable ignored) {}
                return;
            }
            // 网页真实内容图标/缩略图绝不染色(favicon/thumbnail/screenshot 等)
            try {
                String idn2 = iv.getResources().getResourceEntryName(iv.getId());
                String idl2 = idn2.toLowerCase();
                if (idl2.contains("favicon") || idl2.contains("thumb") || idl2.contains("screenshot")
                        || idl2.contains("snapshot") || idl2.contains("capture") || idl2.contains("preview")
                        || idl2.contains("site_icon") || idl2.contains("website_icon")
                        || idl2.contains("webpage") || idl2.contains("page_icon")) {
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                android.graphics.drawable.Drawable d = iv.getDrawable();
                // 诊断+保护: 大尺寸/位图背景不染(避免背景图被染蓝); onDraw 时 view 宽高可能为0, 用 drawable intrinsic 判断
                try {
                    android.graphics.drawable.Drawable curd = iv.getDrawable();
                    if (curd != null) {
                        int iw = curd.getIntrinsicWidth();
                        int ih = curd.getIntrinsicHeight();
                        if (iw > 220 || ih > 220) {
                            String idn = "";
                            try { idn = iv.getResources().getResourceEntryName(iv.getId()); } catch (Throwable ignored) {}
                            XposedBridge.log("[SBPlus] BGBIG2 skip id=" + idn + " intrinsic=" + iw + "x" + ih + " drawable=" + curd.getClass().getSimpleName());
                            return;
                        }
                    }
                } catch (Throwable ignoredDim) {}
                if (d != null) {
                    // 校验 drawable 当前 colorFilter 色值: 若已是主题色则跳过(省性能)
                    android.graphics.ColorFilter cf = d.getColorFilter();
                    if (cf instanceof android.graphics.PorterDuffColorFilter) {
                        int cur = reflectColorFilterColor(cf);
                        if (cur == icol) {
                            return; // 已染且未被覆盖
                        }
                    }
                }
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(icol));
                iv.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
                if (d != null) {
                    // onDraw 时 view 已有真实渲染尺寸: 尺寸大(背景)跳过, 小(图标)染
                    try {
                        int w = iv.getWidth();
                        int h = iv.getHeight();
                        if (w > 240 || h > 240) {
                            String idn = "";
                            try { idn = iv.getResources().getResourceEntryName(iv.getId()); } catch (Throwable ignored) {}
                            XposedBridge.log("[SBPlus] BGBIG-render skip id=" + idn + " w=" + w + " h=" + h + " drawable=" + d.getClass().getSimpleName());
                            return;
                        }
                    } catch (Throwable ignoredDim) {}
                                            {
                            String idn = "";
                            try { idn = iv.getResources().getResourceEntryName(iv.getId()); } catch (Throwable ignored) {}
                            if (false) XposedBridge.log("[SBPlus] IC-TINT icol=" + Integer.toHexString(icol) + " id=" + idn + " class=" + iv.getClass().getSimpleName()
                                    + " w=" + iv.getWidth() + " h=" + iv.getHeight() + " dr=" + d.getClass().getSimpleName()
                                    + " ctx=" + (iv.getContext()!=null?iv.getContext().getClass().getName():"null"));
                        }
                        d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                } else {
                    // drawable=null,尝试染 background (浏览助手等特殊图标)
                    android.graphics.drawable.Drawable bg = iv.getBackground();
                    if (bg != null) {
                        bg.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                }
                sIconTinted.put(iv, java.lang.Boolean.TRUE);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** 反射取 PorterDuffColorFilter 的颜色值(编译期无 getColor 才用反射). */
    private int reflectColorFilterColor(android.graphics.ColorFilter cf) {
        try {
            java.lang.reflect.Method m = cf.getClass().getMethod("getColor");
            m.setAccessible(true);
            return ((Integer) m.invoke(cf)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 无条件强制染色任何相对小的图标 ImageView (仅当主题激活; 不依赖 isBrowserUiIcon 判断, drawable+background 都染). */
    private void forceTintImageView(android.widget.ImageView iv) {
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return;
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return;
            // 跳过核心背景图片(避免误染大图)
            if (!isBrowserUiIcon(iv)) return;
            // 地址栏内的图标: 只染刷新按钮和收藏,其他(含跳转App图标)全部跳过
            android.view.ViewGroup tg2 = sToolbarParentCache;
            if (tg2 != null) {
                android.view.ViewParent ip2 = iv.getParent();
                while (ip2 != null) {
                    if (ip2 == tg2) {
                        String idn2 = "";
                        try { idn2 = iv.getResources().getResourceEntryName(iv.getId()); } catch (Throwable ignored2) {}
                        if (!idn2.equals("toolbar_reload") && !idn2.equals("bookmark_star_icon")) {
                            try { if (iv.getDrawable()!=null) iv.getDrawable().clearColorFilter(); } catch (Throwable ignored2b) {}
                            try { iv.setImageTintList(null); } catch (Throwable ignored2c) {}
                            try { iv.clearColorFilter(); } catch (Throwable ignored2d) {}
                            return;
                        }
                        break;
                    }
                    ip2 = ip2.getParent();
                }
            }
            // 尺寸保护: 大尺寸 view 是背景/大图, 不是图标, 跳过(避免页面背景被染)
            try {
                int w = iv.getWidth();
                int h = iv.getHeight();
                if (w > 240 || h > 240) return;
            } catch (Throwable ignoredDim) {}
            // 特例: "添加快捷方式"图标只染中间 + 号, 外环保持原色
            int vid = iv.getId();
            String vidn = null;
            try { vidn = iv.getResources().getResourceEntryName(vid); } catch (Throwable ignored) {}
            if ("news_feed_tab_add_button_icon".equals(vidn)) {
                tintAddButtonPlus(iv, icol);
                return;
            }
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(icol));
            iv.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d != null) d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
            android.graphics.drawable.Drawable bg = iv.getBackground();
            if (bg != null) {
                // 背景是纯色/位移背景(非图形图标)时跳过, 只染小而明确的图标背景
                String bgn = bg.getClass().getSimpleName().toLowerCase();
                if (!bgn.contains("ripple") && !bgn.contains("statelist") && !bgn.contains("inset")
                        && !bgn.contains("gradient") && !bgn.contains("bitmap")) {
                    bg.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 只染"添加快捷方式"图标中间的 + 号(外环/圆保持原色). 先反射 dump vector 内部 path 结构. */
    private void tintAddButtonPlus(android.widget.ImageView iv, int icol) {
        try {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d == null) return;
            XposedBridge.log("[SBPlus] ADDBTN cls=" + d.getClass().getName());
            java.lang.reflect.Method dumpM = null;
            try {
                dumpM = android.graphics.drawable.Drawable.class.getDeclaredMethod("getConstantState");
            } catch (Throwable ignored) {}
            // 反射访问 VectorDrawable 内部树 (VGroup/VFullPath)
            try {
                Object state = null;
                try {
                    java.lang.reflect.Method gcs = android.graphics.drawable.Drawable.class.getDeclaredMethod("getConstantState");
                    gcs.setAccessible(true);
                    state = gcs.invoke(d);
                    XposedBridge.log("[SBPlus] ADDBTN state=" + (state==null?"null":state.getClass().getName()));
                } catch (Throwable e) {
                    XposedBridge.log("[SBPlus] ADDBTN state fail=" + e);
                }
                Object root = null;
                // 从 state 找字段: mTree(VGroup) / mVGTargets 等
                if (state != null) {
                    for (java.lang.reflect.Field f : getAllFields(state.getClass())) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(state);
                            if (val != null && val.getClass().getName().contains("VGroup")) {
                                root = val; XposedBridge.log("[SBPlus] ADDBTN tree field=" + f.getName()); break;
                            }
                        } catch (Throwable ignoredF) {}
                    }
                }
                if (root == null) {
                    // 直接在当前 drawable 对象上找 VGroup 字段
                    for (java.lang.reflect.Field f : getAllFields(d.getClass())) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(d);
                            if (val != null && val.getClass().getName().contains("VGroup")) { root = val; break; }
                        } catch (Throwable ignoredF) {}
                    }
                }
                if (root == null) {
                    XposedBridge.log("[SBPlus] ADDBTN tree not found");
                } else {
                    XposedBridge.log("[SBPlus] ADDBTN root=" + root.getClass().getName());
                    dumpVectorNode(root, "", 0);
                }
            } catch (Throwable e) {
                XposedBridge.log("[SBPlus] ADDBTN refl fail=" + e);
            }
        } catch (Throwable ignored) {}
    }

    private java.util.List<java.lang.reflect.Field> getAllFields(Class<?> c) {
        java.util.List<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { java.util.Collections.addAll(out, cur.getDeclaredFields()); } catch (Throwable ignored) {}
            cur = cur.getSuperclass();
        }
        return out;
    }

    private void dumpVectorNode(Object node, String indent, int depth) {
        if (node == null || depth > 10) return;
        try {
            String cls = node.getClass().getSimpleName();
            StringBuilder sb = new StringBuilder("[SBPlus] ADDBTN " + indent + cls);
            if (cls.contains("VFullPath")) {
                for (java.lang.reflect.Field f : getAllFields(node.getClass())) {
                    String n = f.getName();
                    if (n.equals("mFillColor") || n.equals("mStrokeColor") || n.equals("mStrokeWidth") || n.equals("mFillAlpha")
                            || n.equals("mFillType") || n.equals("mTrimPathStart") || n.equals("mTrimPathEnd")) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(node);
                            if (val instanceof int[]) {
                                int[] ia = (int[]) val;
                                sb.append(" " + n + "=#[0x" + (ia.length>0?Integer.toHexString(ia[0]):"?") + "]len" + ia.length);
                            } else {
                                sb.append(" " + n + "=" + val);
                            }
                        } catch (Throwable ignoredE) {}
                    }
                }
                // 若上面没匹配到任何字段, 打印前几个 int/long 字段名兜底定位
                boolean any = sb.indexOf("mFillColor")>0 || sb.indexOf("mStrokeColor")>0 || sb.indexOf("mFillType")>0 || sb.indexOf("mTrimPathStart")>0 || sb.indexOf("mStrokeWidth")>0;
                if (!any) {
                    int cnt=0;
                    for (java.lang.reflect.Field f : getAllFields(node.getClass())) {
                        if (cnt>=10) break;
                        Class<?> t = f.getType();
                        if (t==int.class || t==long.class || t==float.class || t==boolean.class || t==int[].class) {
                            try { f.setAccessible(true); Object v=f.get(node); sb.append(" {"+f.getName()+"="+ (v instanceof int[] ? "arr" : v) +"}"); cnt++; } catch (Throwable ignoredE) {}
                        }
                    }
                }
            } else {
                for (java.lang.reflect.Field f : getAllFields(node.getClass())) {
                    if (f.getName().equals("mBaseWidth") || f.getName().equals("mBaseHeight")) {
                        f.setAccessible(true);
                        try { sb.append(" " + f.getName() + "=" + f.get(node)); } catch (Throwable ignoredE) {}
                    }
                }
            }
            XposedBridge.log(sb.toString());
            // 只递归子节点字段(值是 VGroup/VFullPath 或含它们的数组/list)
            for (java.lang.reflect.Field f : getAllFields(node.getClass())) {
                Object val = null;
                try { f.setAccessible(true); val = f.get(node); } catch (Throwable ignoredF) { continue; }
                if (val == null) continue;
                if (val.getClass().getName().contains("VGroup") || val.getClass().getName().contains("VFullPath")) {
                    dumpVectorNode(val, indent+"  ", depth+1);
                } else if (val instanceof java.util.List) {
                    for (Object child : (java.util.List<?>) val) {
                        if (child != null && (child.getClass().getName().contains("VGroup") || child.getClass().getName().contains("VFullPath"))) {
                            dumpVectorNode(child, indent+"  ", depth+1);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 给浏览器 UI 图标染上 S_HOME_ICON 主题色(返回 true 表示已染). */
    private boolean tintHomeIcon(Object viewObj) {
        try {
            if (sInThemeText) return false;
            android.content.Context ctx = sAppContext;
            if (ctx == null || !isThemeActive()) return false;
            int icol = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
            if (icol == -1) return false;
            if (!(viewObj instanceof android.view.View)) return false;
            android.view.View vv = (android.view.View) viewObj;
            if (!(vv instanceof android.widget.ImageView)) return false;
            if (!isBrowserUiIcon(vv)) return false;
            // 尺寸保护: 背景图/大图不染(避免书签/历史/启动页背景被染蓝).
            // 注意: hook 触发时 view 宽高可能为0, 改用 drawable 自身 intrinsic 尺寸判断.
            try {
                android.graphics.drawable.Drawable dd0 = ((android.widget.ImageView) vv).getDrawable();
                if (dd0 != null) {
                    int iw = dd0.getIntrinsicWidth();
                    int ih = dd0.getIntrinsicHeight();
                    if (iw > 220 || ih > 220) {
                        XposedBridge.log("[SBPlus] BGBIG3 skip intrinsic " + iw + "x" + ih + " drawable=" + dd0.getClass().getSimpleName());
                        return false;
                    }
                }
            } catch (Throwable ignoredDim) {}
            // 特例: "添加快捷方式"图标只染中间+号, 外环保持原色
            try {
                String vidn = vv.getResources().getResourceEntryName(vv.getId());
                if ("news_feed_tab_add_button_icon".equals(vidn)) {
                    int icol2 = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_HOME_ICON);
                    tintAddButtonPlus((android.widget.ImageView) vv, icol2);
                    return true;
                }
            } catch (Throwable ignoredN) {}
            android.graphics.drawable.Drawable d = ((android.widget.ImageView) vv).getDrawable();
            // 类型过滤: setImageDrawable hook 触发时 view 无渲染尺寸, 无法区分图标/背景, 只染明确图标类型
            // (Vector/StateList/Inset 等). Bitmap/Layer 可能是背景大图, 一律不染(由渲染尺寸的方法处理小图标 Bitmap).
            boolean iconLike = false;
            if (d != null) {
                String dn = d.getClass().getName();
                iconLike = dn.contains("VectorDrawable") || dn.contains("StateListDrawable")
                        || dn.contains("InsetDrawable") || dn.contains("AnimatedVectorDrawable")
                        || dn.contains("MaskDrawable") || dn.contains("RotateDrawable");
                if (!iconLike) {
                    XposedBridge.log("[SBPlus] BGBIG4 skip(type) " + d.getClass().getSimpleName() + " " + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight());
                    return false;
                }
            } else {
                return false;
            }
            try {
                ((android.widget.ImageView) vv).setImageTintList(android.content.res.ColorStateList.valueOf(icol));
                ((android.widget.ImageView) vv).setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Throwable ignored) {}
            if (d != null) {
                try { d.setColorFilter(icol, android.graphics.PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 在主页背景上叠加主页 Logo(居中偏上,搜索框上方;支持 GIF 动画)。 */
    /** 按父容器类型创建匹配的 LayoutParams(RelativeLayout/FrameLayout/LinearLayout)。 */
    private static android.view.ViewGroup.LayoutParams makeLp(android.view.ViewGroup parent, int w, int h) {
        try {
            if (parent instanceof android.widget.RelativeLayout) {
                return new android.widget.RelativeLayout.LayoutParams(w, h);
            }
            if (parent instanceof android.widget.LinearLayout) {
                return new android.widget.LinearLayout.LayoutParams(w, h);
            }
        } catch (Throwable ignored) {}
        return new android.widget.FrameLayout.LayoutParams(w, h);
    }

    /** 统一设置 Logo 布局参数居中(按 LayoutParams 类型) + topMargin。 */
    private static void centerLogoLp(android.view.ViewGroup.LayoutParams lp, int topMargin) {
        try {
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                ((android.widget.FrameLayout.LayoutParams) lp).gravity =
                        android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                ((android.widget.FrameLayout.LayoutParams) lp).topMargin = topMargin;
            } else if (lp instanceof android.widget.RelativeLayout.LayoutParams) {
                android.widget.RelativeLayout.LayoutParams rl = (android.widget.RelativeLayout.LayoutParams) lp;
                rl.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
                rl.topMargin = topMargin;
            } else if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) lp).gravity =
                        android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                ((android.widget.LinearLayout.LayoutParams) lp).topMargin = topMargin;
            }
        } catch (Throwable ignored) {}
    }

    /** 计算 Logo 尺寸上限: [maxW, maxH] = 搜索框宽度 x 2x搜索框高度(找不到搜索框则屏幕宽90% x 屏高20%)。static 版供 onPreDraw 使用。 */
    private static int[] logoSizeLimitStatic(android.view.View anyView) {
        return logoSizeLimit(anyView);
    }

    /** 计算 Logo 尺寸上限: [maxW, maxH] = 搜索框宽度 x 2x搜索框高度(找不到搜索框则屏幕宽90% x 屏高20%)。 */
    private static int[] logoSizeLimit(android.view.View anyView) {
        try {
            android.util.DisplayMetrics dm = anyView.getResources().getDisplayMetrics();
            int maxW = (int)(dm.widthPixels * 0.9f);
            int maxH = (int)(dm.heightPixels * 0.20f);
            // 优先使用 hook 到的 QuickAccessDummyUrlBar(主页搜索框)
            try {
                if (sHomeLogoSbView != null && sHomeLogoSbView.getWidth() > 0) {
                    maxW = sHomeLogoSbView.getWidth();
                    maxH = (int)(sHomeLogoSbView.getHeight() * 2.0f);
                    sHomeLogoSbHeight = sHomeLogoSbView.getHeight();
                    return new int[]{ maxW, maxH };
                }
            } catch (Throwable ignored) {}
            // 尝试从视图树找搜索框(omnibox/search); 避免命中地址栏 url_bar_parent
            try {
                android.view.ViewGroup root = (android.view.ViewGroup) anyView.getRootView();
                java.util.List<android.view.View> all = new java.util.ArrayList<android.view.View>();
                collectAllViews(root, all);
                for (android.view.View v : all) {
                    String idn = "";
                    try { idn = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) { continue; }
                    if (idn == null || idn.length() == 0) continue;
                    String idl = idn.toLowerCase();
                    if (idl.contains("url_bar_parent") || idl.contains("urlbar") && idl.contains("parent")) continue;
                    String cls = v.getClass().getName().toLowerCase();
                    boolean isQuickDummy = cls.contains("quickaccessdummyurlbar");
                    if (isQuickDummy || idl.contains("search") || idl.contains("omnibox") || idl.contains("quickaccess_header")
                            || idl.contains("address")) {
                        if (v.getWidth() > 0) {
                            maxW = v.getWidth();
                            maxH = (int)(v.getHeight() * 2.0f);
                            sHomeLogoSbHeight = v.getHeight();
                            sHomeLogoSbView = v;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return new int[]{ maxW, maxH };
        } catch (Throwable t) {
            return new int[]{ 360, 120 };
        }
    }

    /** 把 Logo 图片纯色背景变透明: 取四角平均色为背景色, 容差内像素alpha=0, 边缘轻微羽化。GIF动画不处理。 */
    private static android.graphics.Bitmap makeLogoBgTransparent(android.graphics.Bitmap src) {
        try {
            if (src == null) return null;
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            android.graphics.Bitmap out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            // 采样四角颜色(取 3x3 区域均值)
            long r = 0, g = 0, b = 0;
            int n = 0;
            int[][] corners = { {0,0}, {w-1,0}, {0,h-1}, {w-1,h-1} };
            int[] px = new int[4];
            for (int[] c : corners) {
                px[0] = c[0]; px[1] = c[1];
                if (px[0] < 0) px[0] = 0; if (px[1] < 0) px[1] = 0;
                if (px[0] >= w) px[0] = w-1; if (px[1] >= h) px[1] = h-1;
                int col = out.getPixel(px[0], px[1]);
                r += (col >> 16) & 0xFF; g += (col >> 8) & 0xFF; b += col & 0xFF;
                n++;
            }
            int br = (int)(r / n), bg = (int)(g / n), bb = (int)(b / n);
            // 容差
            int tol = 48;
            int[] buf = new int[w * h];
            out.getPixels(buf, 0, w, 0, 0, w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int col = buf[i];
                    int cr = (col >> 16) & 0xFF, cg = (col >> 8) & 0xFF, cb = col & 0xFF;
                    int dist = Math.abs(cr - br) + Math.abs(cg - bg) + Math.abs(cb - bb);
                    if (dist < tol) {
                        // 边缘柔化: 越接近背景色 alpha 越低
                        int a = 255 * (dist * 3) / (tol * 3 + 1);
                        if (a > 255) a = 255;
                        if (a < 0) a = 0;
                        buf[i] = (a << 24) | (cr << 16) | (cg << 8) | cb;
                    }
                }
            }
            out.setPixels(buf, 0, w, 0, 0, w, h);
            XposedBridge.log("[SBPlus] logo bg transparent bg=#" + Integer.toHexString(br) + Integer.toHexString(bg) + Integer.toHexString(bb));
            return out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] makeLogoBgTransparent err: " + t);
            return null;
        }
    }

    /** GIF 逐帧背景透明化: Movie 解码每帧抠背景, 组装 AnimationDrawable。失败返回 null。 */
    private static android.graphics.drawable.AnimationDrawable decodeGifTransparent(android.content.Context ctx, java.io.File f) {
        try {
            android.graphics.Movie mv = android.graphics.Movie.decodeFile(f.getAbsolutePath());
            if (mv == null) return null;
            int w = mv.width();
            int h = mv.height();
            if (w <= 0 || h <= 0) return null;
            int dur = mv.duration();
            if (dur <= 0) dur = 800;
            // 每 100ms 一帧, 最多 30 帧控制内存
            int step = 100;
            int frameCount = Math.max(1, Math.min(30, dur / step + 1));
            android.graphics.drawable.AnimationDrawable ad = new android.graphics.drawable.AnimationDrawable();
            for (int i = 0; i < frameCount; i++) {
                android.graphics.Bitmap frm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(frm);
                int t = i * step;
                if (t > dur) t = dur;
                mv.setTime(t);
                mv.draw(c, 0, 0);
                android.graphics.Bitmap bt = makeLogoBgTransparent(frm);
                if (bt != null) {
                    ad.addFrame(new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bt), step);
                } else {
                    ad.addFrame(new android.graphics.drawable.BitmapDrawable(ctx.getResources(), frm), step);
                }
                if (i != frameCount - 1) { try { Thread.sleep(1); } catch (Throwable ignored) {} }
            }
            ad.setOneShot(false);
            return ad;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] decodeGifTransparent err: " + t);
            return null;
        }
    }

    private void attachHomeLogo(Object bgViewObj) {
        try {
            if (!(bgViewObj instanceof android.view.View)) return;
            android.view.View bg = (android.view.View) bgViewObj;
            sHomeLogoBgView = bg;
            if (!HomeLogoHelper.isEnabled(bg.getContext())) return;
            String path = HomeLogoHelper.currentPath(bg.getContext());
            if (path == null || path.isEmpty()) return;
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;

            android.view.ViewGroup parent = (android.view.ViewGroup) bg.getParent();
            if (parent == null) return;

            // 清掉旧的 Logo ImageView,避免重复叠加
            try {
                for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                    android.view.View c = parent.getChildAt(i);
                    if (c instanceof android.widget.ImageView && c.getTag() != null
                            && "sbplus_home_logo".equals(c.getTag())) parent.removeViewAt(i);
                }
            } catch (Throwable ignored) {}

            final android.widget.ImageView iv = new android.widget.ImageView(bg.getContext());
            iv.setTag("sbplus_home_logo");
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            final boolean alphaOn = HomeLogoHelper.isAlphaBg(bg.getContext(), new java.io.File(path).getName());
            try {
                // API 28+: ImageDecoder 解码, 自动得到 AnimatedImageDrawable(GIF/WebP 动画) 或 BitmapDrawable
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(new java.io.File(path));
                android.graphics.drawable.Drawable dr = android.graphics.ImageDecoder.decodeDrawable(src);
                if (dr instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    if (alphaOn) {
                        // GIF 透明化: 逐帧抠背景, 用 AnimationDrawable 重建动画
                        android.graphics.drawable.AnimationDrawable ad = decodeGifTransparent(bg.getContext(), new java.io.File(path));
                        if (ad != null && ad.getNumberOfFrames() > 0) {
                            iv.setImageDrawable(ad);
                            ad.start();
                        } else {
                            iv.setImageDrawable(dr);
                            ((android.graphics.drawable.AnimatedImageDrawable) dr).start();
                        }
                    } else {
                        iv.setImageDrawable(dr);
                        ((android.graphics.drawable.AnimatedImageDrawable) dr).start();
                    }
                } else if (dr instanceof android.graphics.drawable.BitmapDrawable && alphaOn) {
                    android.graphics.Bitmap bmp2 = ((android.graphics.drawable.BitmapDrawable) dr).getBitmap();
                    android.graphics.Bitmap bt = makeLogoBgTransparent(bmp2);
                    if (bt != null) iv.setImageBitmap(bt);
                } else {
                    iv.setImageDrawable(dr);
                }
            } catch (Throwable t1) {
                try {
                    // 兕底: BitmapFactory 静态解码
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
                    if (bmp != null) {
                        if (HomeLogoHelper.isAlphaBg(bg.getContext(), new java.io.File(path).getName())) {
                            android.graphics.Bitmap bt = makeLogoBgTransparent(bmp);
                            if (bt != null) iv.setImageBitmap(bt);
                            else iv.setImageBitmap(bmp);
                        } else {
                            iv.setImageBitmap(bmp);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 尺寸限制: 宽<=搜索框宽度, 高<=3x搜索框高度; 超出等比缩小
            int[] lim = logoSizeLimit(bg);
            int iw = 0, ih = 0;
            try {
                android.graphics.drawable.Drawable drp = iv.getDrawable();
                if (drp != null) {
                    iw = drp.getIntrinsicWidth();
                    ih = drp.getIntrinsicHeight();
                }
            } catch (Throwable ignored) {}
            if (iw <= 0 || ih <= 0) { iw = 400; ih = 200; }
            float ratio = Math.min(1.0f, Math.min((float)lim[0] / iw, (float)lim[1] / ih));
            int lw = Math.max(1, (int)(iw * ratio));
            int lh = Math.max(1, (int)(ih * ratio));
            android.view.ViewGroup.LayoutParams lp = makeLp(parent, lw, lh);
            centerLogoLp(lp, 1);
            parent.addView(iv, lp);
            sHomeLogoIv = iv;
            XposedBridge.log("[SBPlus] home logo size " + iw + "x" + ih + " -> " + lw + "x" + lh + " (lim " + lim[0] + "x" + lim[1] + ")");
            // 等布局完成后精确定位: 屏幕垂直约 18% 高度处(搜索框上方), 水平居中
            // 若父视图尚未布局(宽高为0), 延迟重试直到就绪, 确保大小/位置必然生效
            iv.post(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.ViewGroup p2 = (android.view.ViewGroup) iv.getParent();
                        if (p2 == null) return;
                        int pw2 = p2.getWidth();
                        int ph2 = p2.getHeight();
                        if (pw2 <= 0 || ph2 <= 0) {
                            try { iv.postDelayed(this, 150); } catch (Throwable ignored) {}
                            return;
                        }
                        // 搜索框基准尚未就绪(QuickAccessDummyUrlBar 未 attach) -> 延迟重试, 确保尺寸限制正确
                        if (sHomeLogoSbView == null) {
                            try { logoSizeLimitStatic(p2); } catch (Throwable ignored) {}
                            if (sHomeLogoSbView == null) {
                                try { iv.postDelayed(this, 150); } catch (Throwable ignored) {}
                                return;
                            }
                        }
                        int pw = pw2;
                        int ph = ph2;
                        if (pw > 0 && ph > 0) {
                            String nm = new java.io.File(path).getName();
                            int[] lim2 = logoSizeLimit((android.view.View) iv.getParent());
                            int iw2 = iv.getWidth();
                            int ih2 = iv.getHeight();
                            if (iw2 <= 0 || ih2 <= 0) { iw2 = 200; ih2 = 100; }
                            float ratio2 = Math.min(1.0f, Math.min((float)lim2[0] / iw2, (float)lim2[1] / ih2));
                            int sizePct = HomeLogoHelper.getSizePct(bg.getContext(), nm);
                            float sizeMul = sizePct / 100f;
                            int lw2 = Math.max(1, (int)(iw2 * ratio2 * sizeMul));
                            int lh2 = Math.max(1, (int)(ih2 * ratio2 * sizeMul));
                            // 自定义位置: X/Y 百分比(锚点=中心)
                            int px = HomeLogoHelper.getPosX(bg.getContext(), nm);
                            int py = HomeLogoHelper.getPosY(bg.getContext(), nm);
                            int left = (int)(pw * px / 100f) - lw2 / 2;
                            int top = (int)(ph * py / 100f) - lh2 / 2;
                            if (left < 0) left = 0;
                            if (left + lw2 > pw) left = Math.max(0, pw - lw2);
                            if (top < 0) top = 0;
                            if (top + lh2 > ph) top = Math.max(0, ph - lh2);
                            android.view.ViewGroup.LayoutParams lp2 = makeLp(p2, lw2, lh2);
                            // 用 margin 精确定位(不用 gravity, 直接设置位置)
                            if (lp2 instanceof android.widget.FrameLayout.LayoutParams) {
                                android.widget.FrameLayout.LayoutParams fl = (android.widget.FrameLayout.LayoutParams) lp2;
                                fl.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                                fl.leftMargin = left;
                                fl.topMargin = top;
                            } else if (lp2 instanceof android.widget.RelativeLayout.LayoutParams) {
                                android.widget.RelativeLayout.LayoutParams rl = (android.widget.RelativeLayout.LayoutParams) lp2;
                                rl.leftMargin = left;
                                rl.topMargin = top;
                            } else if (lp2 instanceof android.widget.LinearLayout.LayoutParams) {
                                android.widget.LinearLayout.LayoutParams ll = (android.widget.LinearLayout.LayoutParams) lp2;
                                ll.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                                ll.leftMargin = left;
                                ll.topMargin = top;
                            }
                            iv.setLayoutParams(lp2);
                            XposedBridge.log("[SBPlus] logo pos x=" + px + "% y=" + py + "% size=" + sizePct + "% -> left=" + left + " top=" + top + " " + lw2 + "x" + lh2);
                            // 搜索框动画跟随: 挂到搜索框 VTO(动画期间搜索框每帧重绘, 必然触发), 每帧跟随
                            try {
                                sHomeLogoIv.setTranslationY(0f);
                                if (sHomeLogoSbView == null) { logoSizeLimitStatic(iv); }
                                if (sHomeLogoSbView != null) {
                                    sLogoSbPrevTop = -1f;
                                    sLogoSbBaseTop = -1f;
                                    final android.view.ViewTreeObserver vtoSb = sHomeLogoSbView.getViewTreeObserver();
                                    if (vtoSb != null && vtoSb.isAlive()) {
                                        vtoSb.removeOnPreDrawListener(sLogoPreDraw);
                                        vtoSb.addOnPreDrawListener(sLogoPreDraw);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[SBPlus] home logo attached " + path);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] attachHomeLogo error: " + t);
        }
    }

    // ===== 主页时钟 =====
    private static android.view.View sHomeClockTv;
    private static android.view.View sHomeClockBg;
    private static android.os.Handler sHomeClockHandler;
    private static Runnable sHomeClockTick;
    private static final int sHomeClockCharColor = 0xFFE8EAED;
    private static float sClockSbPrevTop = -1f;
    private static boolean sClockFollowLogged = false;
    private static boolean sClockFollowRegistered = false;
    private static final android.view.ViewTreeObserver.OnPreDrawListener sClockPreDraw = new android.view.ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            try {
                if (sHomeLogoSbView == null || sHomeClockTv == null || sHomeClockTv.getParent() == null) {
                    sClockSbPrevTop = -1f;
                    return true;
                }
                if (!HomeClockHelper.isFollow(sHomeClockTv.getContext())) return true;
                int[] sbLoc = new int[2];
                sHomeLogoSbView.getLocationInWindow(sbLoc);
                if (sClockSbPrevTop < 0f) {
                    sClockSbPrevTop = sbLoc[1];
                } else {
                    float delta = sbLoc[1] - sClockSbPrevTop;
                    if (Math.abs(delta) > 0.3f) {
                        sHomeClockTv.setTranslationY(sHomeClockTv.getTranslationY() + delta);
                    }
                    sClockSbPrevTop = sbLoc[1];
                }
            } catch (Throwable ignored) {}
            return true;
        }
    };
    /** 主页时钟: 支持秒, 自定义位置大小。挂载到主页背景父容器。 */
    private void attachHomeClock(Object bgViewObj) {
        try {
            if (!(bgViewObj instanceof android.view.View)) return;
            final android.view.View bg = (android.view.View) bgViewObj;
            sHomeClockBg = bg;
            if (!HomeClockHelper.isEnabled(bg.getContext())) return;
            // 确保搜索框 view 已探测(时钟跟随用; logo 可能未挂载导致未探测)
            try {
                if (sHomeLogoSbView == null) {
                    logoSizeLimit(bg);
                }
            } catch (Throwable ignored) {}
            final android.view.ViewGroup parent = (android.view.ViewGroup) bg.getParent();
            if (parent == null) return;

            // 清掉旧的时钟,避免重复叠加
            try {
                for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                    android.view.View c = parent.getChildAt(i);
                    if (c.getTag() != null && "sbplus_home_clock".equals(c.getTag())) {
                        parent.removeViewAt(i);
                        if (c == sHomeClockTv) sHomeClockTv = null;
                    }
                }
            } catch (Throwable ignored) {}

            final android.widget.TextView tv = new android.widget.TextView(bg.getContext());
            tv.setTag("sbplus_home_clock");
            tv.setTextColor(sHomeClockCharColor);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tv.setShadowLayer(6f, 0f, 2f, 0xAA000000);
            tv.setGravity(android.view.Gravity.CENTER);
            try { tv.setIncludeFontPadding(false); } catch (Throwable ignored) {}
            int sizePct = HomeClockHelper.getSizePct(bg.getContext());
            float baseFont = bg.getContext().getResources().getDisplayMetrics().widthPixels * 0.11f;
            final float fontPx = baseFont * sizePct / 100f;
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPx);

            // 先写入初始时间
            try {
                java.util.Date now0 = new java.util.Date();
                boolean secs0 = HomeClockHelper.isSeconds(bg.getContext());
                tv.setText(secs0
                        ? String.format("%02d:%02d:%02d", now0.getHours(), now0.getMinutes(), now0.getSeconds())
                        : String.format("%02d:%02d", now0.getHours(), now0.getMinutes()));
            } catch (Throwable ignored) {}

            // WRAP_CONTENT 自适应, 避免文字被裁剪
            parent.addView(tv, makeLp(parent, -2, -2));
            sHomeClockTv = tv;

            sHomeClockTick = new Runnable() {
                @Override public void run() {
                    try {
                        if (sHomeClockTv == null || sHomeClockTv.getParent() == null) return;
                        boolean secs = HomeClockHelper.isSeconds(bg.getContext());
                        java.util.Date now = new java.util.Date();
                        String t = secs
                                ? String.format("%02d:%02d:%02d", now.getHours(), now.getMinutes(), now.getSeconds())
                                : String.format("%02d:%02d", now.getHours(), now.getMinutes());
                        android.widget.TextView tt = (android.widget.TextView) sHomeClockTv;
                        if (!t.equals(tt.getText().toString())) tt.setText(t);
                        // 跟随自愈: 每 200ms 检查 onPreDraw 监听是否挂好; 探测已完成(hook QuickAccessDummyUrlBar), 一般一次即成
                        try {
                            if (sHomeLogoSbView != null && !sClockFollowRegistered) {
                                android.view.ViewTreeObserver vtoSb = sHomeLogoSbView.getViewTreeObserver();
                                if (vtoSb != null && vtoSb.isAlive()) {
                                    sClockSbPrevTop = -1f;
                                    sHomeClockTv.setTranslationY(0f);
                                    vtoSb.removeOnPreDrawListener(sClockPreDraw);
                                    vtoSb.addOnPreDrawListener(sClockPreDraw);
                                    sClockFollowRegistered = true;
                                }
                            }
                        } catch (Throwable ignored2) {}
                        sHomeClockHandler.postDelayed(this, 200);
                    } catch (Throwable ignored) {}
                }
            };
            if (sHomeClockHandler == null) sHomeClockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            sHomeClockHandler.removeCallbacksAndMessages(null);
            sHomeClockHandler.post(sHomeClockTick);

            // 布局完成后精确定位(百分比锚点中心); 若父视图尚未布局, 延迟重试直到就绪
            tv.post(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.ViewGroup p2 = (android.view.ViewGroup) tv.getParent();
                        if (p2 == null) return;
                        int pw = p2.getWidth();
                        int ph = p2.getHeight();
                        if (pw <= 0 || ph <= 0) {
                            try { tv.postDelayed(this, 150); } catch (Throwable ignored) {}
                            return;
                        }
                        int px = HomeClockHelper.getPosX(bg.getContext());
                        int py = HomeClockHelper.getPosY(bg.getContext());
                        int tw = tv.getWidth();
                        int th = tv.getHeight();
                        if (tw <= 0 || th <= 0) { tv.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)); tw = tv.getMeasuredWidth(); th = tv.getMeasuredHeight(); }
                        int left = (int)(pw * px / 100f) - tw / 2;
                        int top = (int)(ph * py / 100f) - th / 2;
                        if (left < 0) left = 0;
                        if (left + tw > pw) left = Math.max(0, pw - tw);
                        if (top < 0) top = 0;
                        if (top + th > ph) top = Math.max(0, ph - th);
                        android.view.ViewGroup.LayoutParams lp2 = makeLp(p2, tw, th);
                        if (lp2 instanceof android.widget.FrameLayout.LayoutParams) {
                            android.widget.FrameLayout.LayoutParams fl = (android.widget.FrameLayout.LayoutParams) lp2;
                            fl.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                            fl.leftMargin = left;
                            fl.topMargin = top;
                        } else if (lp2 instanceof android.widget.RelativeLayout.LayoutParams) {
                            android.widget.RelativeLayout.LayoutParams rl = (android.widget.RelativeLayout.LayoutParams) lp2;
                            rl.leftMargin = left;
                            rl.topMargin = top;
                        } else if (lp2 instanceof android.widget.LinearLayout.LayoutParams) {
                            android.widget.LinearLayout.LayoutParams ll = (android.widget.LinearLayout.LayoutParams) lp2;
                            ll.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                            ll.leftMargin = left;
                            ll.topMargin = top;
                        }
                        tv.setLayoutParams(lp2);
                        // 跟随: 由 tick 轮询自愈挂载, 这里仅清零基准
                        sHomeClockTv.setTranslationY(0f);
                        sClockSbPrevTop = -1f;
                        sClockFollowRegistered = false;
                    } catch (Throwable t2) { XposedBridge.log("[SBPlus] clock pos err: " + t2); }
                }
            });
            XposedBridge.log("[SBPlus] home clock attached");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] attachHomeClock error: " + t);
        }
    }

    /** 刷新主页时钟(设置变更后重挂载)。 */
    private void refreshHomeClock() {
        try {
            if (sHomeClockTv != null && sHomeClockTv.getParent() != null) {
                android.view.ViewGroup parent = (android.view.ViewGroup) sHomeClockTv.getParent();
                try { parent.removeView(sHomeClockTv); } catch (Throwable ignored) {}
                sHomeClockTv = null;
                if (sHomeClockHandler != null) sHomeClockHandler.removeCallbacksAndMessages(null);
            }
            if (sHomeClockTv == null && sHomeClockBg != null) {
                try { attachHomeClock(sHomeClockBg); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refreshHomeClock err: " + t);
        }
    }

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

            // 清掉旧的 TextureView,避免重复叠加。
            try {
                for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                    android.view.View c = parent.getChildAt(i);
                    if (c instanceof android.view.TextureView) parent.removeViewAt(i);
                }
            } catch (Throwable ignored) {}

            // TextureView 是普通 View,参与正常 View 层级绘制,不会被
            // QuickAccessMainLayout 的不透明背景色盖住(SurfaceView 有此问题)。
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


    // ============ 油猴脚本(Userscript)支持 ============

    private boolean isSniffEnabled() {
        try {
            if (sAppContext != null) return processPrefs(sAppContext).getBoolean(KEY_ENABLE_SNIFF, true);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] isSniffEnabled error: " + t);
        }
        return true;
    }

    private void saveSniffEnabled(boolean enabled) {
        try {
            if (sAppContext != null) processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_SNIFF, enabled).commit();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveSniffEnabled error: " + t);
        }
    }

    private boolean isUserscriptEnabled() {
        try {
            if (sAppContext != null) return processPrefs(sAppContext).getBoolean(KEY_ENABLE_USERSCRIPT, false);
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveUserscriptEnabled(boolean enabled) {
        try {
            if (sAppContext != null) processPrefs(sAppContext).edit().putBoolean(KEY_ENABLE_USERSCRIPT, enabled).commit();
            // 关闭总开关时,立即尝试移除已注入的地址栏油猴图标(若当前 Activity 可用)
            if (!enabled) removeUserscriptToolbarButtonFromCurrent();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] save userscript enabled error: " + t);
        }
    }

    /** 从当前可见 Activity 的 view 树中移除地址栏油猴图标(幂等)。 */
    private void removeUserscriptToolbarButtonFromCurrent() {
        try {
            android.app.Activity act = sCurrentActivity;
            if (act == null) return;
            android.view.View root = act.findViewById(android.R.id.content);
            if (root == null) return;
            removeViewByTagRecursive(root, "sbplus_monkey_btn");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] remove userscript btn error: " + t);
        }
    }

    private void removeViewByTagRecursive(android.view.View v, String tag) {
        try {
            if (v == null) return;
            if (tag.equals(v.getTag())) {
                android.view.View p = (android.view.View) v.getParent();
                if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(v);
                return;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    removeViewByTagRecursive(vg.getChildAt(i), tag);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 被禁用的脚本文件名集合(按 fileName 区分,不影响脚本文件本身)。 */
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

    /** 某个脚本文件是否启用(未在禁用列表即启用)。 */
    private boolean isUserscriptFileEnabled(String fileName) {
        return !disabledUserscripts().contains(fileName);
    }

    private void setUserscriptFileEnabled(String fileName, boolean enabled) {
        java.util.Set<String> set = disabledUserscripts();
        if (enabled) set.remove(fileName); else set.add(fileName);
        saveDisabledUserscripts(set);
    }

    /** 脚本目录:浏览器外部文件目录下的 userscripts/ */
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

    /** 主开关:油猴脚本。 */
    private Object buildUserscriptSwitch(Context ctx, ClassLoader cl) {
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
        Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(pref, "setTitle", T("油猴脚本", "Userscripts"));
        XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_userscript");
        java.io.File dir = userscriptDir();
        int count = countUserscripts(dir);
        XposedHelpers.callMethod(pref, "setSummary", count > 0 ? (T("已加载 ", "Loaded ") + count + T(" 个脚本,目录: ", " scripts, dir: ") + (dir == null ? "?" : dir.getAbsolutePath())) : "脚本目录: " + (dir == null ? "未初始化" : dir.getAbsolutePath()));
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

    private Object buildSniffSwitch(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
            Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", T("资源嗅探", "Media Sniffer"));
            XposedHelpers.callMethod(pref, "setKey", "sbplus_sniff_settings");
            XposedHelpers.callMethod(pref, "setSummary", T("嗅探音频/视频/图片并下载(含下载设置)", "Sniff audio/video/images & download (incl. download settings)"));
            XposedHelpers.callMethod(pref, "setChecked", isSniffEnabled());
            XposedHelpers.callMethod(pref, "setSelectable", true);
            // 显示分隔线(竖线).
            try { XposedHelpers.callMethod(pref, "setDividerVisible", true); } catch (Throwable ignored) {}
            // 开关变化 -> 保存启用状态
            try {
                Class<?> changeListener = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
                Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{changeListener},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                    saveSniffEnabled(en);
                                    XposedBridge.log("[SBPlus] sniff switch -> " + en);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable ignored) {}
                            return Boolean.FALSE;
                        }
                    });
                java.lang.reflect.Method set = pref.getClass().getMethod("setOnPreferenceChangeListener", changeListener);
                set.invoke(pref, listener);
            } catch (Throwable t) { XposedBridge.log("[SBPlus] sniff switch bind err: " + t); }
            // 点击 -> 进入「资源嗅探」子页.
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
                                            a.putString(ARG_PAGE, PAGE_SNIFF_SETTINGS);
                                            navigateToFragment((android.app.Activity) actObj,
                                                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom", a);
                                            sInPickerPage = true;
                                            sCurrentPickerPage = PAGE_SNIFF_SETTINGS;
                                        }
                                        return Boolean.TRUE;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] sniff navigate error: " + t);
                                }
                                return Boolean.FALSE;
                            }
                        });
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", clickListener);
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] sniff click bind failed: " + t);
            }
            return pref;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] build sniff entry error: " + t);
            return null;
        }
    }

    private void navigateToSniffSettings(android.app.Activity act) {
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putString(ARG_PAGE, PAGE_SNIFF_SETTINGS);
            navigateToFragment(act,
                    "com.sec.android.app.sbrowser.common.settings.PreferenceFragmentCustom",
                    args);
            sInPickerPage = true;
            sCurrentPickerPage = PAGE_SNIFF_SETTINGS;
            XposedBridge.log("[SBPlus] navigated to sniff settings");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] navigateToSniffSettings error: " + t);
        }
    }

    /** 资源嗅探子页:嗅探开关 + 下载方式 + 线程数/任务数 + 打开下载列表。 */
    private void injectSniffSettingsPicker(final Context ctx, final ClassLoader cl, Object screen) {
        try {
            // 下载方式
            final Object modePref = buildPreferenceCustom(ctx, cl);
            XposedHelpers.callMethod(modePref, "setTitle", T("视频下载方式", "Video download mode"));
            XposedHelpers.callMethod(modePref, "setKey", "sbplus_dl_mode");
            refreshModeSummary(ctx, modePref);
            final Object[] modePrefRef = new Object[]{modePref};
            bindPreferenceClick(modePref, cl, new Runnable() { @Override public void run() { pickDownloadMode(ctx, modePrefRef); } });
            XposedHelpers.callMethod(screen, "addPreference", modePref);

            // 视频资源转 MP4:标准开关条目,onBindViewHolder 里处理圆点+同步状态
            try {
                Class<?> switchPrefCls = XposedHelpers.findClass(
                        "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
                final Object conv = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
                XposedHelpers.callMethod(conv, "setKey", "sbplus_dl_convertmp4");
                boolean convOn = ctx.getSharedPreferences("samsung_download_bridge", Context.MODE_PRIVATE)
                        .getBoolean("dl_convert_mp4", true);
                XposedHelpers.callMethod(conv, "setTitle", T("视频资源转 MP4", "Convert video to MP4"));
                XposedHelpers.callMethod(conv, "setChecked", convOn);
                XposedHelpers.callMethod(conv, "setSelectable", false);
                try { XposedHelpers.callMethod(conv, "setIcon", (Object) null); } catch (Throwable ignored) {}
                try { XposedHelpers.callMethod(conv, "setIconSpaceReserved", false); } catch (Throwable ignored) {}
                try { XposedHelpers.callMethod(conv, "setDividerVisible", false); } catch (Throwable ignored) {}
                try { XposedHelpers.callMethod(conv, "setSummary", (CharSequence) null); } catch (Throwable ignored) {}
                Class<?> lt = listenerParamType(conv.getClass(), "setOnPreferenceChangeListener");
                Object cl2 = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{lt},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean en = args[1] instanceof Boolean && (Boolean) args[1];
                                    ctx.getSharedPreferences("samsung_download_bridge", Context.MODE_PRIVATE)
                                            .edit().putBoolean("dl_convert_mp4", en).commit();
                                    XposedBridge.log("[SBPlus] convert-mp4 -> " + en);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable ignored) {}
                            return Boolean.FALSE;
                        }
                    });
                XposedHelpers.callMethod(conv, "setOnPreferenceChangeListener", cl2);
                XposedHelpers.callMethod(screen, "addPreference", conv);
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] convert-mp4 switch err: " + t);
            }

            // 分片下载线程数
            final Object threadsPref = buildPreferenceCustom(ctx, cl);
            XposedHelpers.callMethod(threadsPref, "setTitle", T("分片下载线程数", "Download threads"));
            XposedHelpers.callMethod(threadsPref, "setKey", "sbplus_dl_threads");
            refreshThreadsSummary(ctx, threadsPref);
            bindPreferenceClick(threadsPref, cl, new Runnable() { @Override public void run() { editNumber(ctx, "download_threads", 16, 1, 32, threadsPref); } });
            XposedHelpers.callMethod(screen, "addPreference", threadsPref);

            // 同时下载任务数
            final Object parallelPref = buildPreferenceCustom(ctx, cl);
            XposedHelpers.callMethod(parallelPref, "setTitle", T("同时下载任务数", "Parallel tasks"));
            XposedHelpers.callMethod(parallelPref, "setKey", "sbplus_dl_parallel");
            refreshParallelSummary(ctx, parallelPref);
            bindPreferenceClick(parallelPref, cl, new Runnable() { @Override public void run() { editNumber(ctx, "download_parallel", 2, 1, 10, parallelPref); } });
            XposedHelpers.callMethod(screen, "addPreference", parallelPref);

            // 下载管理
            final Object listPref = buildPreferenceCustom(ctx, cl);
            XposedHelpers.callMethod(listPref, "setTitle", T("下载管理", "Download manager"));
            XposedHelpers.callMethod(listPref, "setSummary", T("查看下载状态、打开文件、删除", "View status, open files, delete"));
            XposedHelpers.callMethod(listPref, "setKey", "sbplus_dl_list");
            bindPreferenceClick(listPref, cl, new Runnable() { @Override public void run() {
                try {
                    showDownloadList();
                } catch (Throwable t) { XposedBridge.log("[SBPlus] 下载管理 click err: " + t); }
            } });
            XposedHelpers.callMethod(screen, "addPreference", listPref);

            XposedBridge.log("[SBPlus] sniff settings submenu injected");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectSniffSettingsPicker error: " + t);
        }
    }

    /** 「启用资源嗅探」开关(置于资源嗅探子页顶部). */
    private Object buildEnableSniffSwitch(Context ctx, ClassLoader cl) {
        try {
            Class<?> switchPrefCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);
            Object pref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(pref, "setTitle", T("启用资源嗅探", "Enable media sniffer"));
            XposedHelpers.callMethod(pref, "setKey", "sbplus_enable_sniff");
            XposedHelpers.callMethod(pref, "setSummary", T("在地址栏显示嗅探图标,识别页面音频/视频/图片并下载", "Show sniffer icon in address bar"));
            XposedHelpers.callMethod(pref, "setChecked", isSniffEnabled());
            XposedHelpers.callMethod(pref, "setSelectable", true);
            try { XposedHelpers.callMethod(pref, "setDividerVisible", true); } catch (Throwable ignored) {}
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    boolean enabled = args[1] instanceof Boolean && (Boolean) args[1];
                                    saveSniffEnabled(enabled);
                                    XposedBridge.log("[SBPlus] sniff toggled: " + enabled);
                                    applySniffSwitchIcon(enabled);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] sniff listener error: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
            return pref;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] buildEnableSniffSwitch error: " + t);
            return null;
        }
    }

    private Object buildPreferenceCustom(Context ctx, ClassLoader cl) {
        Class<?> prefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        return XposedHelpers.newInstance(prefCls, new Class[]{Context.class}, ctx);
    }

    private void refreshModeSummary(Context ctx, Object pref) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE);
            String mode = sp.getString("dl_mode", "internal");
            XposedHelpers.callMethod(pref, "setSummary",
                    "internal".equals(mode) ? T("内置下载器(多线程 + 转 MP4)", "Built-in (multi-thread + MP4)") : T("外部下载器(转交第三方)", "External downloader"));
        } catch (Throwable ignored) {}
    }
    private void refreshThreadsSummary(Context ctx, Object pref) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE);
            XposedHelpers.callMethod(pref, "setSummary", T("当前 ", "Current ") + sp.getInt("download_threads", 16));
        } catch (Throwable ignored) {}
    }
    private void refreshParallelSummary(Context ctx, Object pref) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE);
            XposedHelpers.callMethod(pref, "setSummary", T("当前 ", "Current ") + sp.getInt("download_parallel", 2));
        } catch (Throwable ignored) {}
    }

    private void pickDownloadMode(final Context ctx, final Object[] modePrefRef) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE);
            final String cur = sp.getString("dl_mode", "internal");
            String[] items = new String[]{ T("内置下载器(多线程+MP4,推荐)", "Built-in (recommended)"),
                                           T("外部下载器(转交第三方)", "External downloader") };
            int idx = "external".equals(cur) ? 1 : 0;
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(ctx)
                .setTitle(T("视频下载方式", "Video download mode"))
                .setSingleChoiceItems(items, idx, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dlg, int which) {
                        String mode = (which == 1) ? "external" : "internal";
                        sp.edit().putString("dl_mode", mode).commit();
                        com.sbplus.browser.SbDownloadManager.setParallelCapacity(sp.getInt("download_parallel", 2));
                        refreshModeSummary(ctx, modePrefRef[0]);
                        dlg.dismiss();
                    }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .create();
            d.show();
        } catch (Throwable t) { XposedBridge.log("[SBPlus] pickDownloadMode: " + t); }
    }

    private void editNumber(final Context ctx, final String key, final int def, final int min, final int max, final Object pref) {
        try {
            final android.content.SharedPreferences sp = ctx.getSharedPreferences("samsung_download_bridge", android.content.Context.MODE_PRIVATE);
            final android.widget.EditText et = new android.widget.EditText(ctx);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(sp.getInt(key, def)));
            final int pad = (int)(16 * ctx.getResources().getDisplayMetrics().density);
            et.setPadding(pad, pad, pad, pad);
            new android.app.AlertDialog.Builder(ctx)
                .setTitle((min<=10&&max<=10) ? T("任务数(1-10)", "Tasks (1-10)") : T("线程数(1-32)", "Threads (1-32)"))
                .setView(et)
                .setPositiveButton(T("确定", "OK"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            v = Math.max(min, Math.min(max, v));
                            sp.edit().putInt(key, v).commit();
                            if ("download_threads".equals(key)) refreshThreadsSummary(ctx, pref);
                            else if ("download_parallel".equals(key)) { refreshParallelSummary(ctx, pref); com.sbplus.browser.SbDownloadManager.setParallelCapacity(v); }
                        } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .create().show();
        } catch (Throwable t) { XposedBridge.log("[SBPlus] editNumber: " + t); }
    }


    /** 嗅探开关变化时,立即从当前地址栏移除图标(启用则等下次布局重建自动出现)。 */
    private void applySniffSwitchIcon(final boolean enabled) {
        try {
            if (sCurrentActivity == null) return;
            sCurrentActivity.runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    android.view.ViewGroup root = (android.view.ViewGroup) sCurrentActivity.getWindow().getDecorView();
                    removeViewByTagRecursive(root, "sbplus_sniff_btn");
                    if (enabled) {
                        XposedBridge.log("[SBPlus] sniff enabled, icon will appear on next layout build");
                    } else {
                        XposedBridge.log("[SBPlus] sniff icon removed");
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] applySniffSwitchIcon error: " + t);
                }
            }});
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] applySniffSwitchIcon outer error: " + t);
        }
    }


    /** 油猴脚本子页:脚本列表(启用/删除)+ 添加/更新/下载操作。 */
    private void injectUserscriptPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);
        Class<?> switchPrefCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.SwitchPreferenceCustom", cl);

        java.io.File dir = userscriptDir();
        java.util.List<UserscriptMeta> metas = loadUserscripts();

        // -- 操作区 --
        Object addPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(addPref, "setTitle", T("添加脚本", "Add script"));
        XposedHelpers.callMethod(addPref, "setKey", "sbplus_userscript_add");
        XposedHelpers.callMethod(addPref, "setSummary", T("粘贴脚本内容", "Paste script content"));
        bindPreferenceClick(addPref, cl, new Runnable() { public void run() { launchAddUserscript(); } });
        XposedHelpers.callMethod(screen, "addPreference", addPref);

        Object importPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(importPref, "setTitle", T("导入脚本", "Import script"));
        XposedHelpers.callMethod(importPref, "setKey", "sbplus_userscript_import");
        XposedHelpers.callMethod(importPref, "setSummary", T("从本地选择 .user.js 文件导入", "Import a .user.js file from local storage"));
        bindPreferenceClick(importPref, cl, new Runnable() { public void run() { launchUserscriptFilePicker(); } });
        XposedHelpers.callMethod(screen, "addPreference", importPref);

        Object updatePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(updatePref, "setTitle", T("更新所有脚本", "Update all scripts"));
        XposedHelpers.callMethod(updatePref, "setKey", "sbplus_userscript_update");
        XposedHelpers.callMethod(updatePref, "setSummary", T("检测所有脚本的更新(需脚本声明 @updateURL/@downloadURL)", "Check for updates of all scripts (requires @updateURL/@downloadURL)"));
        bindPreferenceClick(updatePref, cl, new Runnable() { public void run() { updateAllUserscripts(); } });
        XposedHelpers.callMethod(screen, "addPreference", updatePref);

        Object dlPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(dlPref, "setTitle", T("下载脚本", "Download script"));
        XposedHelpers.callMethod(dlPref, "setKey", "sbplus_userscript_dl");
        XposedHelpers.callMethod(dlPref, "setSummary", T("打开脚本源列表,选择网站后安装的 .user.js 会自动保存", "Open the source list; .user.js installed from the site is saved automatically"));
        bindPreferenceClick(dlPref, cl, new Runnable() { public void run() { openGreasyFork(); } });
        XposedHelpers.callMethod(screen, "addPreference", dlPref);

        // 目录路径展示行。
        Object dirPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(dirPref, "setTitle", T("脚本目录", "Script directory"));
        XposedHelpers.callMethod(dirPref, "setKey", "sbplus_userscript_dir");
        XposedHelpers.callMethod(dirPref, "setSummary", dir == null ? T("目录未初始化", "Directory not initialized") : dir.getAbsolutePath());
        XposedHelpers.callMethod(screen, "addPreference", dirPref);

                // -- 脚本列表入口 --
        Object listPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(listPref, "setTitle", T("脚本列表 (", "Script list (") + metas.size() + ")");
        XposedHelpers.callMethod(listPref, "setKey", "sbplus_userscript_list");
        XposedHelpers.callMethod(listPref, "setSummary", T("点击管理已安装的脚本", "Tap to manage installed scripts"));
        bindPreferenceClick(listPref, cl, new Runnable() {
            public void run() {
                android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
                if (act != null) navigateToUserscriptList(act);
            }
        });
        XposedHelpers.callMethod(screen, "addPreference", listPref);

        XposedBridge.log("[SBPlus] userscript picker injected, scripts=" + metas.size());
    }

    /** 脚本列表子页:列出所有已安装脚本,每行点击进入详情。 */
    private void injectUserscriptListPicker(Context ctx, ClassLoader cl, Object screen) {
        Class<?> prefCustomCls = XposedHelpers.findClass(
                "com.sec.android.app.sbrowser.common.settings.PreferenceCustom", cl);

        java.util.List<UserscriptMeta> metas = loadUserscripts();

        if (metas.isEmpty()) {
            Object emptyPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(emptyPref, "setTitle", T("暂无脚本", "No scripts"));
            XposedHelpers.callMethod(emptyPref, "setKey", "sbplus_userscript_list_empty");
            XposedHelpers.callMethod(emptyPref, "setSummary", T("返回后点「添加脚本」或「下载脚本」", "Go back and tap \"Add script\" or \"Download script\""));
            XposedHelpers.callMethod(screen, "addPreference", emptyPref);
        } else {
            for (int i = 0; i < metas.size(); i++) {
                final UserscriptMeta meta = metas.get(i);
                Object row = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
                String enabledTag = isUserscriptFileEnabled(meta.fileName) ? "" : T(" [已停用]", " [disabled]");
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

    /** 脚本详情子页:启用开关 + 编辑 + 配置页 + 匹配规则 + 删除。 */
    private void injectUserscriptDetailPicker(Context ctx, ClassLoader cl, Object screen, String fileName) {
        sDetailFileName = fileName;
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
            XposedHelpers.callMethod(emptyPref, "setTitle", T("脚本不存在", "Script not found"));
            XposedHelpers.callMethod(emptyPref, "setKey", "sbplus_userscript_detail_empty");
            XposedHelpers.callMethod(emptyPref, "setSummary", T("文件可能已被删除", "The file may have been deleted"));
            XposedHelpers.callMethod(screen, "addPreference", emptyPref);
            return;
        }

        final UserscriptMeta meta = target;

        // 标题行(名字 + 版本 + 作者/描述)。
        Object titlePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(titlePref, "setTitle", meta.name);
        XposedHelpers.callMethod(titlePref, "setKey", "sbplus_userscript_detail_title");
        StringBuilder tsum = new StringBuilder();
        if (!meta.version.isEmpty()) tsum.append(T("版本 ", "Version ")).append(meta.version);
        if (!meta.description.isEmpty()) { if (tsum.length() > 0) tsum.append(" · "); tsum.append(meta.description); }
        XposedHelpers.callMethod(titlePref, "setSummary", tsum.toString());
        XposedHelpers.callMethod(screen, "addPreference", titlePref);

        // 启用开关。
        Object enPref = XposedHelpers.newInstance(switchPrefCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(enPref, "setTitle", T("启用脚本", "Enable script"));
        XposedHelpers.callMethod(enPref, "setKey", "sbplus_userscript_detail_enable");
        XposedHelpers.callMethod(enPref, "setSummary", T("关闭后脚本不会注入页面", "Script is not injected into pages when off"));
        XposedHelpers.callMethod(enPref, "setChecked", isUserscriptFileEnabled(meta.fileName));
        bindUserscriptEnableChange(enPref, cl, meta.fileName);
        XposedHelpers.callMethod(screen, "addPreference", enPref);
        // 三星 SwitchPreferenceCustom 的 setChecked 在 RecyclerView 渲染前调用不刷新 UI,
        // 延迟到渲染完成后强制重设一次,确保开关显示与存储状态一致
        final Object fEn = enPref;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    XposedHelpers.callMethod(fEn, "setChecked", isUserscriptFileEnabled(fileName));
                    try { XposedHelpers.callMethod(fEn, "notifyChanged"); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        }, 350);

        // 编辑。
        Object editPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(editPref, "setTitle", T("编辑源码", "Edit source"));
        XposedHelpers.callMethod(editPref, "setKey", "sbplus_userscript_detail_edit");
        XposedHelpers.callMethod(editPref, "setSummary", T("修改后保存覆盖原文件", "Saves and overwrites the original file after editing"));
        bindPreferenceClick(editPref, cl, new Runnable() { public void run() { editUserscript(meta.fileName); } });
        XposedHelpers.callMethod(screen, "addPreference", editPref);

        // 导出。
        Object exportPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(exportPref, "setTitle", T("导出脚本", "Export script"));
        XposedHelpers.callMethod(exportPref, "setKey", "sbplus_userscript_detail_export");
        XposedHelpers.callMethod(exportPref, "setSummary", T("复制到 Download/SBPlus/ 目录", "Copies to Download/SBPlus/ folder"));
        bindPreferenceClick(exportPref, cl, new Runnable() { public void run() { exportUserscript(meta.fileName, meta.name); } });
        XposedHelpers.callMethod(screen, "addPreference", exportPref);

        // 来源显示(真实记录 > @downloadURL > @homepageURL > 本地导入)。
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
            srcText = T("本地导入", "Local import");
        }
        if (srcUrl == null && srcText != null && (srcText.startsWith("http://") || srcText.startsWith("https://"))) {
            srcUrl = srcText;
        }
        final String srcUrlFinal = srcUrl;
        {
            Object homePref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
            XposedHelpers.callMethod(homePref, "setTitle", T("来源", "Source"));
            XposedHelpers.callMethod(homePref, "setKey", "sbplus_userscript_detail_home");
            XposedHelpers.callMethod(homePref, "setSummary", srcText);
            if (srcUrlFinal != null && !srcUrlFinal.isEmpty()) {
                bindPreferenceClick(homePref, cl, new Runnable() { public void run() { openUrl(srcUrlFinal); } });
            }
            XposedHelpers.callMethod(screen, "addPreference", homePref);
        }

        // 匹配规则展示。
        Object matchPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(matchPref, "setTitle", T("匹配规则", "Match rules"));
        XposedHelpers.callMethod(matchPref, "setKey", "sbplus_userscript_detail_match");
        StringBuilder ms = new StringBuilder();
        for (String s : meta.match) ms.append("match: ").append(s).append(" · ");
        for (String s : meta.include) ms.append("include: ").append(s).append(" · ");
        if (ms.length() == 0) ms.append(T(T("无匹配规则", "No match rules"), "No match rules"));
        XposedHelpers.callMethod(matchPref, "setSummary", ms.toString());
        XposedHelpers.callMethod(screen, "addPreference", matchPref);

        // 删除。
        Object delPref = XposedHelpers.newInstance(prefCustomCls, new Class[]{Context.class}, ctx);
        XposedHelpers.callMethod(delPref, "setTitle", T("删除脚本", "Delete script"));
        XposedHelpers.callMethod(delPref, "setKey", "sbplus_userscript_detail_del");
        XposedHelpers.callMethod(delPref, "setSummary", T("从目录删除此脚本文件", "Deletes this script file from the directory"));
        bindPreferenceClick(delPref, cl, new Runnable() { public void run() { deleteUserscript(meta.fileName, meta.name); } });
        XposedHelpers.callMethod(screen, "addPreference", delPref);

        XposedBridge.log("[SBPlus] userscript detail injected: " + meta.name);
    }

    /** 通用点击绑定:点击后执行 runnable。 */
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
            // 方案:实例级 onPreferenceChange 监听,仅在用户点击开关时写 prefs。
            // 避免全局 setChecked hook:该 hook 会拦截所有 setChecked 调用(含初始化显示),
            // 且每次进详情页都会重复安装,导致状态被反复覆盖(主页/设置状态不同步 bug)。
            Class<?> listenerType = listenerParamType(pref.getClass(), "setOnPreferenceChangeListener");
            Object changeListener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.getName().equals("onPreferenceChange")) {
                                    if (args == null || args.length < 2) return Boolean.FALSE;
                                    Object newVal = args[1];
                                    if (!(newVal instanceof Boolean)) return Boolean.FALSE;
                                    boolean c = (Boolean) newVal;
                                    String fn = sDetailFileName != null ? sDetailFileName : fileName;
                                    setUserscriptFileEnabled(fn, c);
                                    XposedBridge.log("[SBPlus] userscript enable change: " + fn + " -> " + c);
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] userscript enable change err: " + t);
                            }
                            return Boolean.FALSE;
                        }
                    });
            XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeListener);
            XposedBridge.log("[SBPlus] userscript enable listener bound: " + fileName);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] bindUserscriptEnableChange failed: " + t);
        }
    }

    private android.widget.Switch findChildSwitch(android.view.View root) {
        if (root instanceof android.widget.Switch) return (android.widget.Switch) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.Switch s = findChildSwitch(vg.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
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
            toastOnMain(T("已删除脚本: ", "Script deleted: ") + name);
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
                toastOnMain(T("导出失败:脚本目录未初始化", "Export failed: script directory not initialized"));
                return;
            }
            java.io.File src = new java.io.File(dir, fileName);
            if (!src.exists()) {
                toastOnMain(T("导出失败:源文件不存在", "Export failed: source file not found"));
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
            toastOnMain(T("已导出到:\n", "Exported to:\n") + dst.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] exportUserscript error: " + t);
            toastOnMain(T("导出失败: ", "Export failed: ") + t.getMessage());
        }
    }

    /** 构建脚本摘要(描述 + 匹配规则数)。 */
    private String buildUserscriptSummary(UserscriptMeta meta) {
        int rules = meta.match.size() + meta.include.size();
        StringBuilder sum = new StringBuilder();
        if (!meta.description.isEmpty()) {
            sum.append(meta.description);
            sum.append("  ·  ");
        }
        sum.append(T("匹配规则 ", "Match rules: ")).append(rules).append(T(" 条", " items"));
        return sum.toString();
    }

    /** 编辑已有脚本:加载其内容到编辑器,保存时覆盖原文件。 */
    private void editUserscript(final String fileName) {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
            java.io.File dir = userscriptDir();
            if (dir == null) { toastOnMain(T("脚本目录未初始化", "Script directory not initialized")); return; }
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
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
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

    /** 读取自定义源(格式:每行 name|url)。 */
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

    /** 合并源列表:自定义在前,预置后。 */
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
            names[sources.size()] = T("+ 添加网址", "+ Add URL");
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle(T("选择脚本源", "Choose script source"));
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

    /** 弹出添加网址对话框(名字 + 网址)。 */
    private void showAddSourceDialog(final android.app.Activity act) {
        try {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(act);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setPadding(48, 24, 48, 8);
            final android.widget.EditText nameEt = new android.widget.EditText(act);
            nameEt.setHint(T("名称(如:我的源)", "Name (e.g. My source)"));
            final android.widget.EditText urlEt = new android.widget.EditText(act);
            urlEt.setHint(T("网址(如:https://example.com/)", "URL (e.g. https://example.com/)"));
            urlEt.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            ll.addView(nameEt);
            ll.addView(urlEt);
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle(T("添加脚本源", "Add script source"));
            b.setView(ll);
            b.setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    String n = nameEt.getText().toString().trim();
                    String u = urlEt.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) { toastOnMain(T("名称和网址不能为空", "Name and URL cannot be empty")); return; }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    java.util.List<ScriptSource> list = customSources();
                    list.add(new ScriptSource(n, u));
                    saveCustomSources(list);
                    toastOnMain(T("已添加脚本源: ", "Script source added: ") + n);
                }
            });
            b.setNegativeButton(T("取消", "Cancel"), null);
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


    /** 启动添加脚本(单一输入窗口,预置模板 + 保存前检测)。 */
    private void launchAddUserscript() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
            showUserscriptEditorDialog(act);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] launchAddUserscript error: " + t);
        }
    }

    /** 脚本编辑对话框:全屏、内容可滚动,预置模板,点保存先校验再写入。 */
    private void showUserscriptEditorDialog(final android.app.Activity act) {
        showUserscriptEditorDialog(act, null, USERSCRIPT_TEMPLATE);
    }

    /** 核心编辑器:新增(fileName=null)或编辑已有脚本(fileName=原文件名)。 */
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
            tv.setText(fileName == null ? T("编写脚本", "New script") : T("编辑脚本", "Edit script"));
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
            cancelBtn.setText(T("取消", "Cancel"));
            cancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { /* dialog 由下方引用关闭 */ }
            });

            android.widget.Button saveBtn = new android.widget.Button(act);
            saveBtn.setText(T("保存", "Save"));
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
                        toastOnMain(T("脚本无效,未保存:", "Invalid script, not saved: ") + err);
                        return;
                    }
                    String fn;
                    if (fileName != null && !fileName.isEmpty()) {
                        fn = overwriteUserscriptContent(fileName, content);
                        saveSource(fileName, getSource(fileName) != null ? getSource(fileName) : T("手动添加", "Manual add"));
                    } else {
                        fn = saveUserscriptContent(content);
                        if (fn != null) saveSource(fn, T("手动添加", "Manual add"));
                    }
                    toastOnMain(fn == null ? T("保存失败", "Save failed") : ("已保存: " + fn));
                    refreshCurrentUserscriptPicker();
                    dialog.dismiss();
                }
            });

            dialog.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showUserscriptEditorDialog error: " + t);
        }
    }

    /** 脚本模板(预置 ==UserScript== 头)。 */
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

    /** 校验脚本:返回 null 表示合法,否则返回错误信息。 */
    private String validateUserscript(String content) {
        if (content == null || content.trim().isEmpty()) return T("内容为空", "Content is empty");
        // 必须以 ==UserScript== 开头(允许前导空白/注释)。
        if (!content.contains("==UserScript==")) return T("缺少 ==UserScript== 声明头", "Missing ==UserScript== header");
        if (!content.contains("==/UserScript==")) return T("缺少 ==/UserScript== 结束标记", "Missing ==/UserScript== end marker");
        UserscriptMeta meta = UserscriptMeta.parse(content);
        if (meta == null || meta.name.isEmpty()) return T("缺少 @name", "Missing @name");
        // 必须有至少一条匹配规则,否则邮箱般全站注入风险太高,强制要求。
        if (meta.match.isEmpty() && meta.include.isEmpty()) return T("缺少 @match 或 @include 匹配规则", "Missing @match or @include rule");
        // 至少有可执行代码(metadata 之后非空)。
        String after = meta.code;
        if (after == null || after.trim().isEmpty()) return T("没有可执行的脚本代码", "No executable script code");
        return null;
    }

    /** 粘贴脚本内容对话框。 */
    private void showPasteUserscriptDialog() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
            final android.widget.EditText et = new android.widget.EditText(act);
            et.setHint(T("粘贴完整 .user.js 脚本内容(含 ==UserScript== 头)", "Paste the full .user.js content (with ==UserScript== header)"));
            et.setMinLines(8);
            et.setMaxLines(16);
            et.setGravity(android.view.Gravity.TOP);
            new android.app.AlertDialog.Builder(act)
                    .setTitle(T("粘贴脚本内容", "Paste script content"))
                    .setView(et)
                    .setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            String content = et.getText().toString();
                            if (content.trim().isEmpty()) { toastOnMain(T("内容为空", "Content is empty")); return; }
                            String fn = saveUserscriptContent(content);
                            toastOnMain(fn == null ? T("保存失败", "Save failed") : ("已保存: " + fn));
                            refreshCurrentUserscriptPicker();
                        }
                    })
                    .setNegativeButton(T("取消", "Cancel"), null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showPasteUserscriptDialog error: " + t);
        }
    }

    /** 启动文件选择器导入 .user.js。 */
    private void launchUserscriptFilePicker() {
        try {
            android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            act.startActivityForResult(i, REQUEST_USERSCRIPT_PICK);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] launchUserscriptFilePicker error: " + t);
        }
    }

    /** 将脚本内容写入目录,返回文件名(自动按 @name 生成,冲突加序号)。 */
    private String saveUserscriptContent(String content) {
        try {
            java.io.File dir = userscriptDir();
            if (dir == null) return null;
            // 从内容解析名字,生成文件名。
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

    /** 覆盖已有脚本文件(编辑模式),返回文件名;失败返回 null。 */
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

    /** 文件名合法化(移除 Windows/Android 非法字符)。 */
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

    /** 用 emoji 字符生成位图(用于设置项图标)。 */
    private android.graphics.Bitmap emojiBitmap(android.content.Context ctx, String emoji, int sizePx) {
        try {
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            android.graphics.Paint pt = new android.graphics.Paint();
            pt.setAntiAlias(true);
            pt.setTextSize(sizePx * 0.8f);
            pt.setTextAlign(android.graphics.Paint.Align.CENTER);
            android.graphics.Paint.FontMetrics fm = pt.getFontMetrics();
            float y = (sizePx - fm.ascent - fm.descent) / 2f;
            cv.drawText(emoji, sizePx / 2f, y, pt);
            return bm;
        } catch (Throwable ignored) { return null; }
    }

    /** 记录脚本来源(下载地址 / 本地导入 / 手动添加)。 */
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

    /** 读取脚本来源;无记录返回空。 */
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

    /** 更新所有脚本(异步下载对比版本)。 */
    private void updateAllUserscripts() {
        java.util.List<UserscriptMeta> metas = loadUserscripts();
        int updatable = 0;
        for (UserscriptMeta m : metas) {
            String src = !m.updateURL.isEmpty() ? m.updateURL : m.downloadURL;
            if (!src.isEmpty()) updatable++;
        }
        final int total = updatable;
        if (total == 0) {
            toastOnMain(T("没有可检测更新的脚本(需声明 @updateURL 或 @downloadURL)", "No scripts to check (need @updateURL or @downloadURL)"));
            return;
        }
        toastOnMain(T("开始检测 ", "Checking ") + total + T(" 个脚本更新...", " scripts for updates..."));
        new Thread(new Runnable() {
            @Override public void run() {
                int updated = 0;
                for (UserscriptMeta m : metas) {
                    String src = !m.updateURL.isEmpty() ? m.updateURL : m.downloadURL;
                    if (src.isEmpty()) continue;
                    try {
                        String remote = httpGet(src);
                        if (remote == null || !isUserscriptContentValid(remote)) continue;
                        UserscriptMeta rm = UserscriptMeta.parse(remote);
                        if (rm.version.isEmpty() || m.version.isEmpty() || rm.version.equals(m.version)) {
                            continue; // 无版本或版本相同,跳过
                        }
                        // 有更新,覆盖写入。
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
                toastOnMain(T("更新完成:更新了 ", "Updated ") + u + T(" 个脚本", " scripts"));
                refreshCurrentUserscriptPicker();
            }
        }).start();
    }

    /** 从 url 下载 .user.js 到目录(供下载拦截使用)。 */
    private void downloadUserscriptToDir(String url) {
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String content = httpGet(url);
                        if (content == null || !isUserscriptContentValid(content)) { toastOnMain(T("下载不完整,请重试: ", "Download incomplete, please retry: ") + url); return; }
                        String fn = saveUserscriptContent(content);
                        if (fn != null) saveSource(fn, url);
                        toastOnMain(fn == null ? T("保存失败", "Save failed") : ("已安装脚本: " + fn));
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

    /** 简单 HTTP GET,返回响应体字符串。用 read() 批量读+长超时,避免大脚本下载中断截断。 */
    private String httpGet(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (SBPlus Userscript)");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            String out = new String(bos.toByteArray(), "UTF-8");
            if (code >= 400) {
                XposedBridge.log("[SBPlus] httpGet HTTP " + code + " for " + url);
                return null;
            }
            return out;
        }
 catch (Throwable t) {
            XposedBridge.log("[SBPlus] httpGet error: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 校验油猴脚本内容完整性:非空且同时包含 ==UserScript== 开头块与 ==/UserScript== 结束。 */
    private boolean isUserscriptContentValid(String content) {
        if (content == null || content.length() < 30) return false;
        return content.indexOf("==UserScript==") >= 0 && content.indexOf("==/UserScript==") >= 0;
    }

    /** 判断 url/文件名是否为油猴脚本(.user.js,兼容被加 .txt 后缀的情况)。 */
    private boolean isUserScriptUrl(String url, String fileName) {
        try {
            if (fileName != null) {
                String f = fileName.toLowerCase();
                // 兼容 .user.js 被浏览器误加 .txt 后缀(如 a.user.js.txt)
                if (f.contains(".user.js")) return true;
            }
            if (url == null) return false;
            String u = url.toLowerCase();
            int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
            int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
            return u.contains(".user.js");
        } catch (Throwable t) { return false; }
    }

    /** 读取 content:// URI 文本内容(用于导入本地 .user.js 文件)。 */
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

    // ==================== 书签管理(导出/导入 HTML 书签) ====================

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
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
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

    /** 书签管理对话框:导入 / 导出。 */
    private void showBookmarkManagerDialog(final Context ctx) {
        try {
            final android.app.Activity act = sCurrentActivity != null ? sCurrentActivity
                    : (ctx instanceof android.app.Activity ? (android.app.Activity) ctx : null);
            if (act == null) { toastOnMain(T("无法获取界面环境", "Cannot get UI context")); return; }
            final String[] items = new String[]{ T("导入书签", "Import bookmarks"), T("导出书签", "Export bookmarks") };
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle(T("书签管理", "Bookmark Manager"));
            b.setItems(items, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dlg, int which) {
                    if (which == 0) {
                        launchBookmarkFilePicker();
                    } else {
                        final BookmarkNode tree = buildBookmarkTree(readBookmarkNodes());
                        showBookmarkTreeDialog(act, T("选择要导出的书签", "Select bookmarks to export"), tree, true);
                    }
                }
            });
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showBookmarkManagerDialog error: " + t);
        }
    }

    // ==================== 书签树形勾选对话框 ====================

    /** 弹书签树勾选对话框:勾选要导出/导入的节点。 */
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
            allBtn.setText(T("全选", "Select all"));
            android.widget.Button noneBtn = new android.widget.Button(act);
            noneBtn.setText(T("全不选", "Select none"));
            btnRow.addView(allBtn, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnRow.addView(noneBtn, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            body.addView(btnRow);

            // 树容器(放在 ScrollView 里),高度按屏幕动态计算
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

            // 复选框容器回填:记录每个节点对应的 CheckBox,供全选/全不选使用
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
            b.setNegativeButton(T("取消", "Cancel"), null);
            b.setPositiveButton(isExport ? T("导出所选", "Export selected") : T("导入所选", "Import selected"),
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

    /** 收集所有节点(含根本身不入列)。 */
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

                // 文件夹名(点名字也展开)
                android.widget.TextView label = new android.widget.TextView(act);
                label.setText(child.title == null ? T("(文件夹)", "(folder)") : child.title);
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
                // 书签:空白占位 + 复选框 + 标题
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
                label.setText(child.title == null ? T("(无标题)", "(untitled)") : child.title);
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

    /** 导出:只把勾选的节点序列化成 HTML。 */
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
            toastOnMain(T("已导出 ", "Exported ") + cnt + T(" 个书签:", " bookmarks: ") + out.getAbsolutePath());
            XposedBridge.log("[SBPlus] export selected: " + cnt + " -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doExportSelected error: " + t);
            toastOnMain(T("导出失败", "Export failed"));
        }
    }

    /** 递归生成仅勾选节点的 HTML,返回计数。 */
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

    /** 导入:只把勾选的节点写入 BOOKMARKS 表。 */
    private void doImportSelected(BookmarkNode root) {
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(bookmarkDbPath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            int cnt = insertCheckedTree(db, root, 0);
            db.setTransactionSuccessful();
            toastOnMain(T("已导入 ", "Imported ") + cnt + T(" 个书签,请重启浏览器生效", " bookmarks. Restart the browser to apply"));
            XposedBridge.log("[SBPlus] import selected: " + cnt);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doImportSelected error: " + t);
            toastOnMain(T("导入失败", "Import failed"));
        } finally {
            if (db != null) {
                try { if (db.inTransaction()) db.endTransaction(); } catch (Throwable ignored) {}
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 递归插入仅勾选的节点,返回计数。 */
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
        // 逐段扫描 <DT> 条目,识别 <H3>(文件夹)和 <A>(书签)
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

            // 处理 </DL> 归约:弹栈
            // 简单做法:每遇到一个 </DL> 且栈深>1 就弹一次(对应一个文件夹闭合)
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

    /** 刷新当前油猴子页(重新进入)。 */
    private void refreshCurrentUserscriptPicker() {
        try {
            // 简单起见:记录一个标记,下次进入时 reload;这里不做内存级刷新,
            // 改用 toast 提示用户返回重进。
            toastOnMain(T("返回后重新进入即可看到更新", "Re-enter to see the update"));
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] refresh error: " + t);
        }
    }

    /** 打开脚本目录(直接提示路径,避免 FileProvider 跨包引用问题)。 */
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
                                        android.widget.Toast.makeText(ctx, T("目录未初始化", "Directory not initialized"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else {
                                        android.widget.Toast.makeText(ctx, T("脚本目录:\n", "Script directory:\n") + dir.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show();
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

    /** 扫描并解析脚本目录,返回解析出的脚本元数据列表。 */
    private java.util.List<UserscriptMeta> loadUserscripts() {
        java.util.List<UserscriptMeta> list = new java.util.ArrayList<UserscriptMeta>();
        try {
            java.io.File dir = userscriptDir();
            if (dir == null || !dir.exists()) return list;
            java.io.File[] files = dir.listFiles();
            if (files == null) return list;
            java.util.Map<String, UserscriptMeta> byName = new java.util.LinkedHashMap<String, UserscriptMeta>();
            for (java.io.File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".user.js")) continue;
                try {
                    String content = readFileText(f);
                    UserscriptMeta meta = UserscriptMeta.parse(content);
                    if (meta != null && !meta.name.isEmpty()) {
                        meta.fileName = f.getName();
                        UserscriptMeta prev = byName.get(meta.name);
                        if (prev == null) {
                            byName.put(meta.name, meta);
                        } else {
                            // 同名重复副本:若当前文件名更短(更可能是干净主文件),则保留当前并记录。
                            XposedBridge.log("[SBPlus] userscript duplicate ignored (keep=" + prev.fileName
                                    + ", drop=" + f.getName() + ") name=" + meta.name);
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] parse userscript " + f.getName() + " error: " + t);
                }
            }
            list.addAll(byName.values());
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

    /** 油猴脚本元数据(@name / @match / @include / @exclude / @run-at)。 */
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
            // 提取 metadata block(// ==UserScript== 到 // ==/UserScript==)
            String metaBlock = "";
            int ms = content.indexOf("==UserScript==");
            int me = content.indexOf("==/UserScript==");
            if (ms >= 0 && me > ms) {
                metaBlock = content.substring(ms, me);
                m.code = content.substring(me + "==/UserScript==".length());
            } else {
                // 无 metadata block:视为损坏/非标准脚本,直接丢弃,避免 "anonymous" 幽灵项刷屏菜单。
                return null;
            }
            String[] lines = metaBlock.split("\n");
            for (String ln : lines) {
                String l = ln.trim();
                while (l.startsWith("//")) { l = l.substring(2).trim(); }  // 去掉 // 前缀,如 // @name
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

    /** 简化 URL 匹配:支持 * 通配符;不含通配符时做包含/精确匹配。 */
    private static boolean matchGlob(String pattern, String url) {
        try {
            if (pattern == null || pattern.isEmpty()) return false;
            String p = pattern.trim();
            if (!p.contains("*")) return url.contains(p);
            // 手动转义正则特殊字符,仅保留 * 作为通配符 -> .*
            String esc = p.replace("\\", "\\\\").replace(".", "\\.").replace("+", "\\+").replace("?", "\\?").replace("(", "\\(").replace(")", "\\)").replace("[", "\\[").replace("]", "\\]").replace("^", "\\^").replace("$", "\\$").replace("|", "\\|").replace("{", "\\{").replace("}", "\\}");
            String regex = esc.replace("*", ".*");
            return url.matches(".*" + regex + ".*");
        } catch (Throwable t) { return false; }
    }

    /** GM API 引擎(精简版),注入每个匹配页面。 */
    private static final String GM_API_JS =
        "(function(){" +
        "  var SP=window.__sbplus__||{};" +
        "  function pushLog(l){try{if(window.__sbplusLog)window.__sbplusLog(l);else console.log('[SBPlus] '+l);}catch(e){}}" +
        "  var GM={};" +
        // 存储:桥接到 Java(若未注入 __sbplus__,则回退 localStorage)
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
        "  GM.registerMenuCommand=function(name,fn,acc){try{window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REG:'+name+'@'+(window.__sbplus_current_tag__||'NULL'));window.__sbplus_menus__=window.__sbplus_menus__||{};var tag=window.__sbplus_current_tag__||'__default__';if(!window.__sbplus_menus__[tag])window.__sbplus_menus__[tag]=[];var arr=window.__sbplus_menus__[tag];var found=-1;for(var i=0;i<arr.length;i++){if(arr[i].n===name){found=i;break;}}if(found>=0){arr[found].f=fn;arr[found].id=found;return found;}var id=arr.length;arr.push({n:name,f:fn});return id;}catch(e){window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REGERR:'+e);return 0;}};" +
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
                                injectWebTheme(param.thisObject, (String) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectWebTheme error: " + t);
                            }
                            try {
                                injectWebFont(param.thisObject, (String) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectWebFont error: " + t);
                            }
                            try {
                                injectUserscripts(param.thisObject, (String) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log("[SBPlus] injectUserscripts error: " + t);
                            }
                        }
                    });
            // 页面开始加载时就注册 JS 桥,确保 window.__sbplus__ 在页面上下文建立时就存在。
            XposedHelpers.findAndHookMethod(tabEventHandler, "onLoadStarted", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                updateCurrentRealTab(param.thisObject);
                                registerJsBridge(param.thisObject);
                                // 任意网页开始加载 -> 显示嗅探/油猴图标
                                showToolbarIconsForWeb(null);
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

    /** 在页面加载完成后,把匹配的油猴脚本注入当前 Tab。 */
    /**
     * 地址栏油猴图标:hook LocationBarButtonLayout.onFinishInflate,在刷新按钮旁注入一个
     * 油猴图标。点击图标弹出「当前页面生效脚本」列表,点脚本可查看/触发其菜单命令。
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
                injectSniffToolbarButton(param.thisObject, cl);
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] injectUserscriptToolbarButton error: " + t);
                    }
                }
            });
            XposedBridge.log("[SBPlus] LocationBarButtonLayout.onFinishInflate hooked for userscript toolbar");

            // 根据地址栏状态(网页 vs 主页)显示/隐藏油猴+嗅探图标
            try {
                XposedHelpers.findAndHookMethod(layoutCls, "updateLocationBarEndIcon",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    syncToolbarIconsForHomeState(param.thisObject);
                                } catch (Throwable ignored) {}
                            }
                        });
                XposedBridge.log("[SBPlus] LocationBarButtonLayout.updateLocationBarEndIcon hooked");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] hook updateLocationBarEndIcon failed: " + t);
            }

            // 布局变化时同步图标显隐(切主页/网页/滚动都触发,状态及时)
            try {
                XposedHelpers.findAndHookMethod(layoutCls, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    syncToolbarIconsForHomeState(param.thisObject);
                                } catch (Throwable ignored) {}
                            }
                        });
                XposedBridge.log("[SBPlus] LocationBarButtonLayout.onLayout hooked");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] hook onLayout failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookUserscriptToolbar failed: " + t);
        }
    }

    /** 在刷新按钮旁插入油猴图标按钮(幂等)。 */
    private void injectUserscriptToolbarButton(final Object layoutObj, final ClassLoader cl) {
        try {
            final Object urlBarParent = XposedHelpers.getObjectField(layoutObj, "mUrlBarParent");
            if (!(urlBarParent instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup parent = (android.view.ViewGroup) urlBarParent;
            sToolbarParentCache = parent;
            android.view.View already = parent.findViewWithTag("sbplus_monkey_btn");
            // 总开关关闭时:不注入,并移除已存在的图标(用户切回浏览器后图标消失)
            if (!isUserscriptEnabled()) {
                if (already != null) {
                    try { ((android.view.ViewGroup) already.getParent()).removeView(already); } catch (Throwable ignored) {}
                }
                return;
            }
            if (already != null) return;

            final Context ctx = parent.getContext();
            Object reloadBtn = XposedHelpers.getObjectField(layoutObj, "mReloadButton");
            Object copyBtn = XposedHelpers.getObjectField(layoutObj, "mCopyButton");
            Object zoomBtn = XposedHelpers.getObjectField(layoutObj, "mZoomButton");
            int insertIndex = -1;
            // 优先放 copy 按钮之后(即跳转App等右侧图标的最前面)。
            if (copyBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) copyBtn) + 1;
            } else if (zoomBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) zoomBtn) + 1;
            } else if (reloadBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) reloadBtn);
            }
            if (insertIndex < 0) insertIndex = parent.getChildCount();

            int iconSize = (int)(getDimen(ctx, "location_bar_icon_size", 40) * 1.15f);
            int iconHeight = getDimen(ctx, "location_bar_height", 48);
            int margin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 1.4f);

            android.widget.TextView btn = new android.widget.TextView(ctx);
            btn.setText("\uD83D\uDC35");
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(27, iconSize * 0.66f));
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTag("sbplus_monkey_btn");
            btn.setContentDescription(T("[SBPlus] 油猴脚本", "[SBPlus] Userscripts"));
            btn.setPadding(margin, 0, margin, 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    iconSize, iconHeight);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            lp.leftMargin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 0.6f);
            lp.rightMargin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 0.4f);
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
            // 注入后若当前为主页则立即隐藏
            if (isHomeUrl(sCurrentUrl)) btn.setVisibility(android.view.View.GONE);
            XposedBridge.log("[SBPlus] userscript toolbar button injected at index " + insertIndex);
            startToolbarIconSync();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUserscriptToolbarButton inner error: " + t);
        }
    }

    /** 注入「资源嗅探」图标按钮(在油猴图标之前)。 */
    private void injectSniffToolbarButton(final Object layoutObj, final ClassLoader cl) {
        try {
            final Object urlBarParent = XposedHelpers.getObjectField(layoutObj, "mUrlBarParent");
            if (!(urlBarParent instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup parent = (android.view.ViewGroup) urlBarParent;
            sToolbarParentCache = parent;
            android.view.View already = parent.findViewWithTag("sbplus_sniff_btn");
            // 开关关闭时不注入,并移除已存在图标(用户切回浏览器后图标消失)
            if (!isSniffEnabled()) {
                if (already != null) {
                    try { ((android.view.ViewGroup) already.getParent()).removeView(already); } catch (Throwable ignored) {}
                }
                return;
            }
            if (already != null) return;

            final Context ctx = parent.getContext();
            Object reloadBtn = XposedHelpers.getObjectField(layoutObj, "mReloadButton");
            Object copyBtn = XposedHelpers.getObjectField(layoutObj, "mCopyButton");
            Object zoomBtn = XposedHelpers.getObjectField(layoutObj, "mZoomButton");
            Object monkeyBtn = parent.findViewWithTag("sbplus_monkey_btn");
            int insertIndex = -1;
            // 显式放在油猴🐵图标之前
            if (monkeyBtn instanceof android.view.View) {
                insertIndex = parent.indexOfChild((android.view.View) monkeyBtn) - 1;
            }
            if (insertIndex < 0) {
                if (copyBtn instanceof android.view.View) {
                    insertIndex = parent.indexOfChild((android.view.View) copyBtn) + 1;
                } else if (zoomBtn instanceof android.view.View) {
                    insertIndex = parent.indexOfChild((android.view.View) zoomBtn) + 1;
                } else if (reloadBtn instanceof android.view.View) {
                    insertIndex = parent.indexOfChild((android.view.View) reloadBtn);
                }
            }
            if (insertIndex < 0) insertIndex = parent.getChildCount();

            int iconSize = (int)(getDimen(ctx, "location_bar_icon_size", 40) * 1.15f);
            int iconHeight = getDimen(ctx, "location_bar_height", 48);
            int margin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 1.4f);

            android.widget.TextView btn = new android.widget.TextView(ctx);
            btn.setText("\uD83D\uDC3D");
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(27, iconSize * 0.66f));
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTag("sbplus_sniff_btn");
            btn.setContentDescription(T("资源嗅探", "Media Sniffer"));
            btn.setPadding(margin, 0, margin, 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    iconSize, iconHeight);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            lp.leftMargin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 0.6f);
            lp.rightMargin = (int)(getDimen(ctx, "location_bar_icon_margin", 6) * 0.6f);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    try {
                        android.app.Activity act = resolveActivityFromView(v);
                        if (act != null) { sCurrentActivity = act; sSniffActivity = act; }
                        sniffCurrentPage();
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] sniff button onclick error: " + t);
                    }
                }
            });

            parent.addView(btn, insertIndex);
            // 注入后若当前为主页则立即隐藏
            if (isHomeUrl(sCurrentUrl)) btn.setVisibility(android.view.View.GONE);
            XposedBridge.log("[SBPlus] sniff toolbar button injected at index " + insertIndex);
            startToolbarIconSync();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectSniffToolbarButton inner error: " + t);
        }
    }


    /** 根据地址栏状态(主页/新标签页 vs 网页)显示或隐藏油猴与嗅探图标。 */
    /** 主页出现时,隐藏嗅探/油猴图标(从 Activity 全树找). */
    private void hideToolbarIcons() {
        try {
            android.app.Activity act = sCurrentActivity;
            if (act == null || act.getWindow() == null || act.getWindow().getDecorView() == null) return;
            final android.view.View root = act.getWindow().getDecorView();
            root.post(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.View sniff = root.findViewWithTag("sbplus_sniff_btn");
                        android.view.View monkey = root.findViewWithTag("sbplus_monkey_btn");
                        if (sniff != null && sniff.getVisibility() != android.view.View.GONE) sniff.setVisibility(android.view.View.GONE);
                        if (monkey != null && monkey.getVisibility() != android.view.View.GONE) monkey.setVisibility(android.view.View.GONE);
                        XposedBridge.log("[SBPlus] hideToolbarIcons(on home) sniff=" + (sniff != null) + " monkey=" + (monkey != null));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hideToolbarIcons err: " + t);
        }
    }

    /** 网页加载完成时,显示嗅探/油猴图标. 仅当是真实网络页面(http/https)时才显示,主页本地页不显示. */
    private void showToolbarIconsForWeb(final String pageUrl) {
        try {
            // 仅真实网络页面显示; 若非网络页面且是主页则保持隐藏
            String probe = pageUrl != null ? pageUrl : sCurrentUrl;
            if (probe != null && !probe.isEmpty()) {
                String low = probe.toLowerCase();
                if (!low.startsWith("http://") && !low.startsWith("https://")) {
                    // 非 http 页面:若是主页则隐藏,否则不管
                    if (isHomeUrl(probe)) { hideToolbarIcons(); }
                    return;
                }
            } else {
                // 无 URL 可判断:若当前还没进真实网页(un 更新)则按主页处理
                if (probe == null || probe.isEmpty()) { /* 让其他同步点决定 */ }
            }
            android.app.Activity act = sCurrentActivity;
            if (act == null || act.getWindow() == null || act.getWindow().getDecorView() == null) return;
            final android.view.View root = act.getWindow().getDecorView();
            root.post(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.View sniff = root.findViewWithTag("sbplus_sniff_btn");
                        android.view.View monkey = root.findViewWithTag("sbplus_monkey_btn");
                        if (sniff != null && sniff.getVisibility() != android.view.View.VISIBLE) sniff.setVisibility(android.view.View.VISIBLE);
                        if (monkey != null && monkey.getVisibility() != android.view.View.VISIBLE) monkey.setVisibility(android.view.View.VISIBLE);
                        XposedBridge.log("[SBPlus] showToolbarIconsForWeb(force VISIBLE) sniff=" + (sniff != null) + " monkey=" + (monkey != null));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showToolbarIconsForWeb err: " + t);
        }
    }

    /** URL 是否为主页/新标签页(空、about:、chrome:、edge:、samsung: 等). */
    /** 缓存最近一次成功找到的地址栏父容器,供定时同步复用(避免 decor 树找不到). */
    private static android.view.ViewGroup sToolbarParentCache = null;

    /** URL 是否为主页/新标签页(互联网本地主页 internet-native://newtab/ 等). */
    private boolean isHomeUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        String t = u.trim();
        return t.isEmpty() || t.equalsIgnoreCase("about:blank")
                || t.startsWith("about:") || t.startsWith("chrome:")
                || t.startsWith("edge:") || t.startsWith("samsung:")
                || t.startsWith("internet-native:")
                || t.contains("quickaccess") || t.contains("newtab") || t.contains("NTP");
    }

    private void syncToolbarIconsForHomeState(Object layoutObj) {
        try {
            Object urlBarParent = XposedHelpers.getObjectField(layoutObj, "mUrlBarParent");
            if (!(urlBarParent instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup parent = (android.view.ViewGroup) urlBarParent;
            sToolbarParentCache = parent;
            // 判断当前是否主页/新标签页
            boolean home = isHomeUrl(sCurrentUrl);
            int vis = home ? android.view.View.GONE : android.view.View.VISIBLE;
            android.view.View s = parent.findViewWithTag("sbplus_sniff_btn");
            android.view.View m = parent.findViewWithTag("sbplus_monkey_btn");
            if (s != null && s.getVisibility() != vis) s.setVisibility(vis);
            if (m != null && m.getVisibility() != vis) m.setVisibility(vis);
            XposedBridge.log("[SBPlus] syncToolbarIcons home=" + home + " url=" + (sCurrentUrl == null ? "null" : sCurrentUrl) + " sniff=" + (s != null) + " monkey=" + (m != null));
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] syncToolbarIconsForHomeState err: " + t);
        }
    }

    /** 从缓存地址栏父容器隐藏嗅探/油猴图标(主页后台调用). */
    private void hideToolbarIconsViaCache() {
        try {
            android.view.ViewGroup tg = sToolbarParentCache;
            if (tg == null) return;
            android.view.View sniff = tg.findViewWithTag("sbplus_sniff_btn");
            android.view.View monkey = tg.findViewWithTag("sbplus_monkey_btn");
            if (sniff != null && sniff.getVisibility() != android.view.View.GONE) sniff.setVisibility(android.view.View.GONE);
            if (monkey != null && monkey.getVisibility() != android.view.View.GONE) monkey.setVisibility(android.view.View.GONE);
        } catch (Throwable t) { XposedBridge.log("[SBPlus] hideToolbarIconsViaCache err: " + t); }
    }

    /** 定时同步(每800ms)嗅探/油猴图标显隐,确保最终状态正确. */
    private static boolean syncPeriodicStarted = false;
    private void startToolbarIconSync() {
        if (syncPeriodicStarted) return;
        syncPeriodicStarted = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    android.app.Activity act = sCurrentActivity;
                    if (act != null && act.getWindow() != null && act.getWindow().getDecorView() != null) {
                        boolean onHome = isHomeUrl(sCurrentUrl);
                        // 优先用缓存的地址栏父容器(能找到图标),找不到再试 decor 全树
                        android.view.View sniff = null, monkey = null;
                        android.view.ViewGroup tg = sToolbarParentCache;
                        if (tg != null) {
                            sniff = tg.findViewWithTag("sbplus_sniff_btn");
                            monkey = tg.findViewWithTag("sbplus_monkey_btn");
                        }
                        if (sniff == null && monkey == null) {
                            android.view.View root = act.getWindow().getDecorView();
                            sniff = root.findViewWithTag("sbplus_sniff_btn");
                            monkey = root.findViewWithTag("sbplus_monkey_btn");
                        }
                        if (sniff != null || monkey != null) {
                            int vis = onHome ? android.view.View.GONE : android.view.View.VISIBLE;
                            if (sniff != null && sniff.getVisibility() != vis) sniff.setVisibility(vis);
                            if (monkey != null && monkey.getVisibility() != vis) monkey.setVisibility(vis);
                            XposedBridge.log("[SBPlus] syncPeriodic home=" + onHome + " url=" + (sCurrentUrl==null?"null":sCurrentUrl) + " sniff=" + (sniff!=null?sniff.getVisibility():-1) + " monkey=" + (monkey!=null?monkey.getVisibility():-1));
                        }
                    }
                } catch (Throwable t) { XposedBridge.log("[SBPlus] syncPeriodic err: " + t); }
                h.postDelayed(this, 800);
            }
        });
        XposedBridge.log("[SBPlus] startToolbarIconSync launched");
    }

    /** 是否处于主页: URL 非 http 网络页,或界面存在 QuickAccess 主页背景. */
    private boolean isHomeNow(android.app.Activity act) {
        try {
            String u = sCurrentUrl;
            // 优先按 URL: 明确网络页则不是主页
            if (u != null && !u.isEmpty()) {
                String low = u.toLowerCase();
                if (low.startsWith("http://") || low.startsWith("https://")) {
                    // 是网络页,还要确认当前界面不是主页(残留 URL 情况)
                    if (!hasQuickAccessBackground(act)) return false;
                }
            }
            return true;
        } catch (Throwable ignore) { return true; }
    }

    /** 当前界面是否显示主页 QuickAccess 背景. */
    private boolean hasQuickAccessBackground(android.app.Activity act) {
        try {
            final android.view.View root = act.getWindow().getDecorView();
            final boolean[] found = new boolean[]{false};
            try {
                String bgClsName = "com.sec.android.app.sbrowser.quickaccess.ui.page.QuickAccessCustomBackground";
                java.util.Stack<android.view.View> stack = new java.util.Stack<android.view.View>();
                stack.push(root);
                while (!stack.isEmpty()) {
                    android.view.View v = stack.pop();
                    if (v == null) continue;
                    if (v.getClass().getName().equals(bgClsName)) { found[0] = true; break; }
                    if (v instanceof android.view.ViewGroup) {
                        android.view.ViewGroup g = (android.view.ViewGroup) v;
                        for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                    }
                }
            } catch (Throwable ignored) {}
            return found[0];
        } catch (Throwable t) { return false; }
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
            // 包一层 ScrollView,限制最大高度为屏幕 60%,过时可滚动、避免末尾项被截断。
            wrapWithScroll(pop, ctx, root);
            showPopup(pop, anchor, ctx);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showAnchoredList error: " + t);
        }
    }

    /** 把弹窗内容包进 ScrollView,限制最大高度并保证可滚动,避免列表过长时末尾项被截断。 */
    private void wrapWithScroll(final android.widget.PopupWindow pop, final Context ctx, final android.view.View content) {
        try {
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            // 限宽:内容不超过屏幕宽的 85%。
            int maxW = (int) (screenW * 0.85f);
            int maxH = (int) (screenH * 0.6f);
            // 第一步:用 AT_MOST(限宽) 测宽度,拿到真实宽度(UNSPECIFIED 会让 weight=1 的 child 得到 0 宽)。
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST);
            int hSpec0 = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
            content.measure(wSpec, hSpec0);
            int contentW = Math.min(content.getMeasuredWidth(), maxW);
            // 第二步:用已确定宽度测高度(宽度固定后,weight=1 的 child 才得到正确宽度与换行)。
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
            // 裁剪关闭(API 21+),避免系统自动裁剪弹窗导致高度丢失。
            try { pop.setClippingEnabled(false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] wrapWithScroll error: " + t);
        }
    }

    /** 主线程安全地显示弹窗,锚点失效时兜底定位到屏幕右上。锚点在屏幕下半时向上展开。 */
    private void showPopup(final android.widget.PopupWindow pop, final android.view.View anchor, final Context ctx) {
        final int offY = dp(ctx, 6);
        final Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    if (anchor != null && anchor.getWindowToken() != null) {
                        // 判断锚点位置:在屏幕下半部则向上展开,避免底部工具栏锚点导致弹窗被屏幕底部截断。
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

    /** 弹出「当前页面生效脚本」列表(锚定到图标下方)。 */
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

            // 加载匹配当前页面的脚本(含 fileName/enabled,供开关使用)。
            final java.util.List<UserscriptMeta> matched = new java.util.ArrayList<UserscriptMeta>();
            java.util.List<UserscriptMeta> metas = loadUserscripts();
            for (UserscriptMeta m : metas) {
                if (m.matches(url)) matched.add(m);
            }

            final Object fTerrace = terrace;
            if (matched.isEmpty()) {
                java.util.List<String> emptyHint = new java.util.ArrayList<String>();
                emptyHint.add(T("本页面没有生效的油猴脚本", "No active userscripts on this page"));
                showAnchoredList(anchor, T("油猴脚本", "Userscripts"), emptyHint, null);
                return;
            }
            showScriptSwitchList(anchor, T("当前页面脚本", "Page scripts"), matched, new com.sbplus.browser.MainHook.ItemClickListener() {
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

    /** 带开关的脚本列表弹窗:每行 = 脚本名(可换行) + 启用开关。 */
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

                // 开关在前,名字在后;开关与名字之间留 12dp 空隙。
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
            // 包一层 ScrollView,限制最大高度为屏幕 60%,过时可滚动、避免末尾项被截断。
            wrapWithScroll(pop, ctx, root);
            showPopup(pop, anchor, ctx);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showScriptSwitchList error: " + t);
        }
    }

    /** 弹出某脚本在当前页面注册的菜单命令列表(锚定到图标下方),点命令触发回调。 */
    private void showUserscriptMenuCommandPopup(final String scriptName, final Object terrace, final android.view.View anchor) {
        try {
            if (terrace == null) {
                XposedBridge.log("[SBPlus] menu popup: terrace null");
                return;
            }
            final String tag = quoteJsonString(scriptName);
            final String js = "JSON.stringify((window.__sbplus_menus__&&window.__sbplus_menus__[" + tag + "]||[]).map(function(m){return m.n;}));";
            // 调试:同时打所有 tag 和本脚本 tag。
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
                            // Terrace 对字符串返回值多做了一层 JSON 编码(result 形如 "[\"...\"]"),先解一层。
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
                            emptyHint.add(T("此脚本没有可配置的菜单命令", "This script has no configurable menu commands"));
                            showAnchoredList(anchor, scriptName, emptyHint, null);
                            return;
                        }
                        showAnchoredList(anchor, scriptName + T(" · 菜单", " · Menu"), items, new com.sbplus.browser.MainHook.ItemClickListener() {
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

    /** 用 Terrace 执行一段 JS,并通过回调拿字符串结果(listener 可空)。 */
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

    /** 网页文字/背景主题: 页面加载完成后注入 CSS(S_WEB_TEXT/S_WEB_BG)。 */
    private void injectWebTheme(Object tabEventHandlerObj, String url) {
        try {
            if (!isThemeActive()) return;
            android.content.Context ctx = sAppContext;
            if (ctx == null) return;
            int wtext = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_WEB_TEXT);
            int wbg = ThemeColorHelper.getSlot(ctx, ThemeColorHelper.S_WEB_BG);
            if (wtext == -1 && wbg == -1) return;
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab");
            if (tab == null) return;
            Object realTab = XposedHelpers.callMethod(tab, "getTab");
            if (realTab == null) return;
            String css = buildWebThemeCss(wtext, wbg);
            final String js = "(function(){" +
                    "var e=document.getElementById('sbplusTheme');" +
                    "if(e){e.parentNode.removeChild(e);}" +
                    "var s=document.createElement('style');s.id='sbplusTheme';" +
                    "s.textContent='" + css + "';" +
                    "(document.head||document.documentElement).appendChild(s);" +
                    "})();";
            evaluateJsWithResult(realTab, js, null);
            XposedBridge.log("[SBPlus] web theme injected url=" + url + " text=#" + Integer.toHexString(wtext) + " bg=#" + Integer.toHexString(wbg));
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectWebTheme error: " + t);
        }
    }

    /** 网页字体: 页面加载完成后注入 @font-face + 全局字体族(三星引擎真实回调)。 */
    private void injectWebFont(Object tabEventHandlerObj, String url) {
        // 安全版: 尝试 @font-face 引用字体文件。base64 data URI 对 10MB+ 中文字体注入会 OOM/闪退, 故回退到 file://
        // (file:// 可能被引擎拦; 此注入为尽力而为, 不注入也绝不崩溃)。
        try {
            android.content.Context ctx = sAppContext;
            if (ctx == null || !FontHelper.shouldApply(ctx)) return;
            String fp = FontHelper.selectedPath(ctx);
            if (fp == null || fp.isEmpty()) return;
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab");
            if (tab == null) return;
            Object realTab = XposedHelpers.callMethod(tab, "getTab");
            if (realTab == null) return;
            int sbsize = 0;
            try { sbsize = new java.io.File(fp).length() > 3_000_000 ? 1 : 0; } catch (Throwable ignored) {}
            if (sbsize != 0) { // 字体过大: 放弃 base64 注入, 避免 OOM
                XposedBridge.log("[SBPlus] web font skipped (too large " + fp.length() + ")");
                return;
            }
            String css = "@font-face{font-family:\"SBPlusFont\";src:url(\"file://" + fp + "\");}" +
                    "*{font-family:\"SBPlusFont\" !important}input,textarea{font-family:\"SBPlusFont\" !important}";
            String js = "(function(){" +
                    "try{var e=document.getElementById('sbplusFont');" +
                    "if(e){e.parentNode.removeChild(e);}}" +
                    "catch(err){}" +
                    "var s=document.createElement('style');s.id='sbplusFont';" +
                    "s.textContent='" + css + "';" +
                    "(document.head||document.documentElement).appendChild(s);" +
                    "})();";
            evaluateJsWithResult(realTab, js, null);
            XposedBridge.log("[SBPlus] web font injected (safe) path=" + fp);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectWebFont error: " + t);
        }
    }

    private void injectUserscripts(Object tabEventHandlerObj, String url) {
        XposedBridge.log("[SBPlus] injectUserscripts ENTER url=" + url + " enabled=" + isUserscriptEnabled());
        // 每次 tab/页面切换都同步图标显隐:主页隐藏,网页显示(用准确 url,不依赖残留 sCurrentUrl)
        try {
            boolean home = isHomeUrl(url);
            sCurrentUrl = url;
            final boolean fhome = home;
            final int fvis = home ? android.view.View.GONE : android.view.View.VISIBLE;
            android.view.ViewGroup tg = sToolbarParentCache;
            final android.view.ViewGroup ftg = tg;
            if (tg != null) {
                android.view.View s1 = tg.findViewWithTag("sbplus_sniff_btn");
                android.view.View m1 = tg.findViewWithTag("sbplus_monkey_btn");
                if (s1 != null && s1.getVisibility() != fvis) s1.setVisibility(fvis);
                if (m1 != null && m1.getVisibility() != fvis) m1.setVisibility(fvis);
            }
            // 若缓存还没建立(首次),延迟多试几次
            if (fhome) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            hideToolbarIconsViaCache();
                        } catch (Throwable ignored) {}
                    }
                }, 300);
            }
            XposedBridge.log("[SBPlus] injectUserscripts syncIcons home=" + fhome + " url=" + url);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectUserscripts syncIcons err: " + t);
        }
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

            // 脚本源站点:无条件注入 GM API,伪造"脚本管理器已安装",
            // 让 ScriptCat/GreasyFork 的"安装"按钮走正常下载路径而不弹引导。
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

    // ================= 资源嗅探(音频/视频下载) =================

    /** 页面嗅探 JS:扫描 <audio>/<video> 元素 + 已加载媒体资源,上报给 __sbplus__.reportMedia。 */
        private static final String SNIFF_JS =
        "(function(){var W=window;var st;var out=[];try{st=W.__sbplusSniffStore__;}catch(e){st=null;}if(!st){st={list:[],seen:{}};W.__sbplusSniffStore__=st;}function add(u,t,ti,w,h,du){try{if(!u)return;if(u.indexOf('blob:')===0||u.indexOf('data:')===0)return;if(st.seen[u])return;st.seen[u]=1;st.list.push({url:u,type:t||'',title:ti||'',w:w||0,h:h||0,dur:du||0});}catch(e){}}function typeOf(u){try{var lo=u.toLowerCase();var x=lo.split(/[?#]/)[0];var q=lo.indexOf('?')>=0?lo.substring(lo.indexOf('?')+1):'';if(/\\.(jpe?g|png|gif|webp|bmp|svg|avif|ico)$/.test(x))return 'image';if(/\\.(mp3|m4a|aac|ogg|opus|wav|flac)$/.test(x))return 'audio';if(/\\.(mp4|m4v|webm|mkv|flv|mov|ts|m4s|mpd|m3u8)$/.test(x)){if(/audio|mime=audio|audio\\/mp4|audio\\/mpeg/.test(q))return 'audio';return 'video';}if(/upgcx\\/|bilivideo\\.com\\//.test(lo)&&/\\.m4s|\\.mp4|\\.ts/.test(lo))return 'video';if(/\\/audio\\//.test(lo))return 'audio';return '';}catch(e){return '';}}function scanDoc(doc,isIframe){try{var imgs=doc.querySelectorAll('img');for(var mi=0;mi<imgs.length;mi++){var ig=imgs[mi];var isrc=ig.currentSrc||ig.src||(ig.getAttribute&&ig.getAttribute('data-src'));if(isrc)add(isrc,'image',(ig.alt||''));}var alinks=doc.querySelectorAll('a[href]');for(var ai=0;ai<alinks.length;ai++){var ah=alinks[ai].getAttribute('href');if(ah&&typeOf(ah)==='image'){add(ah,'image','');}}var els=doc.querySelectorAll('video,audio');for(var i=0;i<els.length;i++){var e=els[i];var s=e.currentSrc||e.src;if(s)add(s,(e.tagName==='VIDEO'?'video':'audio'),(e.title||doc.title),(e.videoWidth||0),(e.videoHeight||0),(e.duration||0));var ss=e.querySelectorAll('source');for(var j=0;j<ss.length;j++){var so=ss[j].src;if(so)add(so,(e.tagName==='VIDEO'?'video':'audio'),(e.title||doc.title),(e.videoWidth||0),(e.videoHeight||0),(e.duration||0));}}if(!isIframe){var iframes=doc.querySelectorAll('iframe');for(var fi=0;fi<iframes.length;fi++){try{var ifrm=iframes[fi];if(ifrm.contentDocument){scanDoc(ifrm.contentDocument,true);}}catch(e){}}}}catch(e){}}function scanNow(){try{function biliApi(){try{var ww=window;try{ww=ww.wrappedJSObject||ww;}catch(e){}function emitDash(dd){try{if(!dd)return false;var dv=dd.video||[];var da=dd.audio||[];var op={};var tag='biliApi:'+dv.length+'v';for(var oi=0;oi<dv.length;oi++){var vo=dv[oi];if(!vo||!vo.baseUrl)continue;var wid=vo.width||0,hei=vo.height||0;var key=wid+'x'+hei+'|'+(vo.codecs||'');if(op[key])continue;op[key]=1;var qn=vo.id||0;var lb2='';if(qn===127)lb2='8K';else if(qn===126)lb2='Dolby';else if(qn===125)lb2='HDR';else if(qn===120)lb2='4K';else if(qn===116)lb2='1080P60';else if(qn===112)lb2='1080P+';else if(qn===80)lb2='1080P';else if(qn===74)lb2='720P60';else if(qn===64)lb2='720P';else if(qn===32)lb2='480P';else if(qn===16)lb2='360P';else lb2=wid+'x'+hei;var cc=(vo.codecs||'').indexOf('avc')>=0?'AVC':((vo.codecs||'').indexOf('hev')>=0?'HEVC':'AV1');add(vo.baseUrl,'video',lb2+' '+cc+' ['+tag+']',wid,hei,0,'bilibili');}for(var oi2=0;oi2<da.length;oi2++){var ao=da[oi2];if(ao&&ao.baseUrl)add(ao.baseUrl,'audio','audio '+Math.round((ao.bandwidth||0)/1000)+'k ['+tag+']',0,0,0,'bilibili');}return true;}catch(e){return false;}}function parsePlayInfo(){try{var pi=ww.__playinfo__;if(!pi)return false;var d=pi.data||pi.result||pi;var dash=d&&d.dash;if(dash&&emitDash(dash))return true;if(d&&d.durl){for(var di=0;di<d.durl.length;di++){var du=d.durl[di];if(du&&du.url)add(du.url,'video','B站 '+(du.order||0),(du.width||0),(du.height||0),0,'bilibili');}return d.durl.length>0;}return false;}catch(e){return false;}}if(parsePlayInfo())return;var ist=ww.__INITIAL_STATE__;var bv='',cid=0;if(ist){if(ist.videoData&&ist.videoData.bvid)bv=ist.videoData.bvid;if(ist.videoData&&ist.videoData.cid)cid=ist.videoData.cid;if(!bv&&ist.epInfo&&ist.epInfo.bvid)bv=ist.epInfo.bvid;if(!cid&&ist.epInfo&&ist.epInfo.cid)cid=ist.epInfo.cid;}if(!bv||!cid)return;var xhr=new XMLHttpRequest();xhr.open('GET','https://api.bilibili.com/x/player/playurl?bvid='+encodeURIComponent(bv)+'&cid='+cid+'&qn=127&fnval=4048&fourk=1',false);xhr.send(null);var jt=JSON.parse(xhr.responseText);var dd=jt&&jt.data&&jt.data.dash?jt.data.dash:null;if(dd)emitDash(dd);}catch(e){}}try{biliApi();}catch(e){}function sniffAwemeText(tx){try{var j=JSON.parse(tx);var dd=j.aweme_detail||(j.itemInfo&&j.itemInfo.itemStruct);var arr=[];if(dd){arr.push(dd);}if(j.aweme_list){arr=arr.concat(j.aweme_list);}for(var ai=0;ai<arr.length;ai++){var an=arr[ai];if(an&&an.video&&an.video.play_addr&&an.video.play_addr.url_list){for(var kj=0;kj<an.video.play_addr.url_list.length;kj++){var ku=an.video.play_addr.url_list[kj];if(ku&&ku.indexOf('http')===0){add(ku,'video','DouyinVideo',0,0,0,'douyin');}}}}}catch(e){}}function siteParsers(){try{var host=(location.hostname||'').toLowerCase();function walkState(obj,depth,seen,cb){try{if(!obj||typeof obj!=='object'||depth>9)return;if(seen.has(obj))return;seen.add(obj);cb(obj);var ks=Object.keys(obj);if(ks.length>300)ks=ks.slice(0,300);for(var i=0;i<ks.length;i++){walkState(obj[ks[i]],depth+1,seen,cb);}}catch(e){}}function deepScan(cb){try{var keys=['__INITIAL_STATE__','__NEXT_DATA__','_SSR_DATA_','__NUXT__','__INITIAL_SSR_STATE__','__REDUX_STATE__','__pinia','odin','__data','initialState','WEIBO_DATA','__wb_data__','__preloadData','videoInfo','RENDER_DATA'];var seen=new WeakSet();for(var i=0;i<keys.length;i++){try{var v=window[keys[i]];if(v)walkState(v,0,seen,cb);}catch(e){}}}catch(e){}}if(host.indexOf('douyin.com')>=0||host.indexOf('iesdouyin.com')>=0){try{deepScan(function(o){try{if(o&&typeof o.aweme_id==='string'&&o.video){var v=o.video;var urls=[];var pl=v.play_addr||v.play_addr_h264||v.play_addr_h265;if(pl&&pl.url_list){for(var ui=0;ui<pl.url_list.length;ui++){var u=pl.url_list[ui];if(u&&u.indexOf('http')===0)urls.push(u);}}var bits=v.bit_rate||[];for(var bi=0;bi<bits.length;bi++){var bpl=bits[bi].play_addr;if(bpl&&bpl.url_list){for(var bj=0;bj<bpl.url_list.length;bj++){var bu=bpl.url_list[bj];if(bu&&bu.indexOf('http')===0)urls.push(bu);}}}if(v.download_addr&&v.download_addr.url_list){for(var di=0;di<v.download_addr.url_list.length;di++){var du2=v.download_addr.url_list[di];if(du2&&du2.indexOf('http')===0)urls.push(du2);}}var seenU={};for(var si=0;si<urls.length;si++){var uu=urls[si];if(!seenU[uu]){seenU[uu]=1;var label=v.ratio||'';if(bits.length>0){for(var qi=0;qi<bits.length;qi++){if(bits[qi].play_addr&&bits[qi].play_addr.url_list&&bits[qi].play_addr.url_list.indexOf(uu)>=0){label=(bits[qi].quality_desc||(''+Math.round((bits[qi].bit_rate||0)/1000)+'kbps'));break;}}}add(uu,'video','DouyinVideo',0,0,0,'douyin');}}}}catch(e){}});}catch(e){}}if(host.indexOf('kuaishou.com')>=0){try{var rs=performance.getEntriesByType('resource');for(var ki=0;ki<rs.length;ki++){var rk=rs[ki]&&rs[ki].name;if(rk&&/kuaishou\\.com.*\\.(mp4|m3u8)/i.test(rk))add(rk,'video','KuaishouVideo',0,0,0,'kuaishou');}var vs=document.querySelectorAll('video');for(var vj=0;vj<vs.length;vj++){var vsrc=vs[vj].currentSrc||vs[vj].src;if(vsrc&&vsrc.indexOf('http')===0&&/kuaishou/i.test(vsrc))add(vsrc,'video','KuaishouVideo',0,0,0,'kuaishou');}}catch(e){}}if(host.indexOf('xiaohongshu.com')>=0||host.indexOf('xhslink.com')>=0||host.indexOf('xhs.cn')>=0){try{deepScan(function(o){try{var note=o.note||o.noteDetail;if(note&&note.video&&note.video.media&&note.video.media.stream){var st=note.video.media.stream;var h264=st.h264&&st.h264[0]||st.av1&&st.av1[0]||st.h265&&st.h265[0];if(h264&&h264.masterUrl){add(h264.masterUrl,'video','XhsVideo',0,0,0,'xiaohongshu');}}}catch(e){}});var rs2=performance.getEntriesByType('resource');for(var xi=0;xi<rs2.length;xi++){var rx=rs2[xi]&&rs2[xi].name;if(rx&&(/xhscdn\\.com.*\\.mp4/i.test(rx)||/sns-video.*\\.mp4/i.test(rx)||/snscdn\\.com.*\\.mp4/i.test(rx)))add(rx,'video','XhsVideo',0,0,0,'xiaohongshu');}}catch(e){}}if(host.indexOf('weibo.com')>=0||host.indexOf('weibo.cn')>=0){try{deepScan(function(o){try{if(o.video_sources&&Array.isArray(o.video_sources)){for(var wi=0;wi<o.video_sources.length;wi++){var ws=o.video_sources[wi];var wu=ws&&(ws.url||ws.stream_url||ws.src);if(wu&&wu.indexOf('http')===0)add(wu,'video','WeiboVideo',0,0,0,'weibo');}}if(o.media_info&&o.media_info.stream_url){add(o.media_info.stream_url,'video','WeiboVideo',0,0,0,'weibo');}}catch(e){}});var rs3=performance.getEntriesByType('resource');for(var bi2=0;bi2<rs3.length;bi2++){var rb=rs3[bi2]&&rs3[bi2].name;if(rb&&/f\\.us\\.sinaimg\\.cn.*\\.mp4/i.test(rb))add(rb,'video','WeiboVideo',0,0,0,'weibo');}}catch(e){}}if(host.indexOf('acfun.cn')>=0){try{var vinf=window.videoInfo;if(vinf&&vinf.currentVideoInfo&&vinf.currentVideoInfo.ksPlayJson){var kp=JSON.parse(vinf.currentVideoInfo.ksPlayJson);var sets=kp&&kp.adaptationSet;if(sets&&sets.length>0){var reps=sets[0].representation||[];for(var ai=0;ai<reps.length;ai++){var au=reps[ai].url;if(au)add(au,'video','AcFunVideo',0,0,0,'acfun');}}}}catch(e){}}if(host.indexOf('bilibili.com')>=0){try{biliApi();}catch(e){}}if(host.indexOf('toutiao.com')>=0||host.indexOf('ippzone.com')>=0||host.indexOf('pipigx.com')>=0){try{deepScan(function(o){try{if(o.video&&o.video.play_addr&&o.video.play_addr.url_list){for(var ti=0;ti<o.video.play_addr.url_list.length;ti++){var tu=o.video.play_addr.url_list[ti];if(tu&&tu.indexOf('http')===0)add(tu,'video','ToutiaoVideo',0,0,0,'toutiao');}}if(o.videoResource&&o.videoResource.normal&&o.videoResource.normal.url){add(o.videoResource.normal.url,'video','ToutiaoVideo',0,0,0,'toutiao');}}catch(e){}});}catch(e){}}}catch(e){}}try{siteParsers();}catch(e){}scanDoc(document,false);try{var scs=document.querySelectorAll('script');for(var si=0;si<scs.length;si++){var st=scs[si].textContent||'';if(st.indexOf('m3u8')<0&&st.indexOf('.mp4')<0&&st.indexOf('.ts')<0&&st.indexOf('m4s')<0)continue;var sp=st.split(/['\"]/);for(var sj=0;sj<sp.length;sj++){var sv=sp[sj];if(sv.length<10||sv.length>500)continue;if(sv.indexOf('m3u8')<0&&sv.indexOf('.mp4')<0&&sv.indexOf('.ts')<0&&sv.indexOf('m4s')<0)continue;var su=sv.replace(/\\\\/g,'');if(su.indexOf('http://')!==0&&su.indexOf('https://')!==0){if(su.indexOf('//')===0)su='https:'+su;else if(su.indexOf('/')===0)su='https:'+su;else continue;}var st2=typeOf(su);if(st2)add(su,st2,'');}}}catch(e){}var rs=performance.getEntriesByType('resource');for(var k=0;k<rs.length;k++){var r=rs[k];if(!r||!r.name)continue;var t=typeOf(r.name);if(t){add(r.name,t,'');}}}catch(e){}}if(!W.__sbplusSniffHooked__){W.__sbplusSniffHooked__=true;try{document.addEventListener('loadedmetadata',function(ev){try{var m=ev.target;if(m&&(m.tagName==='AUDIO'||m.tagName==='VIDEO')){var s=m.currentSrc||m.src;if(s)add(s,(m.tagName==='VIDEO'?'video':'audio'),m.title||'',(m.videoWidth||0),(m.videoHeight||0),(m.duration||0));}}catch(e){}},true);}catch(e){}try{var obs=new PerformanceObserver(function(list){try{var es=list.getEntries();for(var i=0;i<es.length;i++){var r=es[i];if(!r||!r.name)continue;var t=typeOf(r.name);if(t){add(r.name,t,'');}}}catch(e){}});obs.observe({entryTypes:['resource']});}catch(e){}var of=window.fetch;if(of&&!W.__sbplusSniffFetch__){W.__sbplusSniffFetch__=true;window.fetch=function(){try{var a=arguments;var u=(typeof a[0]==='string')?a[0]:(a[0]&&a[0].url?a[0].url:'');var p=of.apply(this,a);if(u){var t=typeOf(u);if(t){add(u,t,'');}var lo=(u||'').toLowerCase();if(lo.indexOf('douyin.com')>=0&&lo.indexOf('/aweme/')>=0){try{p.then(function(r){r.clone().text().then(sniffAwemeText).catch(function(){});}).catch(function(){});}catch(e1){}}}return p;}catch(e){try{return of.apply(this,arguments);}catch(e2){return Promise.reject(e2);}}};}var ox=XMLHttpRequest.prototype.open;if(ox&&!W.__sbplusSniffXhr__){W.__sbplusSniffXhr__=true;XMLHttpRequest.prototype.open=function(m,u){try{var t=typeOf(u);if(t){add(u,t,'');}var lo=(u||'').toLowerCase();if(lo.indexOf('douyin.com')>=0&&lo.indexOf('/aweme/')>=0){var oxt=this;this.addEventListener('load',function(){try{sniffAwemeText(oxt.responseText||'');}catch(e3){}});}return ox.apply(this,arguments);}catch(e4){}};}}scanNow();var r=JSON.stringify(st.list);if(W.__sbplus__){try{W.__sbplus__.reportMedia(r);}catch(e){}}return r;})();";

    /** 入口:嗅探当前页面媒体资源。返回 true 表示已触发。 */
    /** 从 View 的 context 链向上找 Activity(ContextWrapper 递归)。 */
    private android.app.Activity resolveActivityFromView(android.view.View v) {
        try {
            if (v == null) return null;
            // 1) 沿 view 的 context 链(可能含 ContextThemeWrapper/ContextWrapper)
            android.content.Context c = v.getContext();
            java.util.Set<android.content.Context> seen = new java.util.HashSet<android.content.Context>();
            while (c instanceof android.content.Context) {
                if (seen.contains(c)) break; seen.add(c);
                if (c instanceof android.app.Activity) return (android.app.Activity) c;
                if (c instanceof android.content.ContextWrapper) {
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                } else break;
            }
            // 2) 沿 view 的父链找 Window/Activity 宿主
            android.view.View w = (android.view.View) v;
            while (w != null) {
                android.content.Context wc = w.getContext();
                int guard = 0;
                while (wc instanceof android.content.Context) {
                    if (wc instanceof android.app.Activity) return (android.app.Activity) wc;
                    if (wc instanceof android.content.ContextWrapper) {
                        wc = ((android.content.ContextWrapper) wc).getBaseContext();
                    } else break;
                    if (++guard > 20) break;
                }
                if (w.getParent() instanceof android.view.View) w = (android.view.View) w.getParent();
                else break;
            }
        } catch (Throwable ignored) {}
        return sCurrentActivity;
    }

    private boolean sniffCurrentPage() {
        try {
            if (sCurrentRealTab == null) {
                LogWriter.log("sniff", "no current tab");
                toastShort(T("没有找到当前页面", "No active page found"));
                return false;
            }
            // 确保 JS 桥已注册(嗅探独立于油猴开关)
            registerJsBridgeForSniff(sCurrentRealTab);
            // 标记"等待中",设置 2s 超时
            synchronized (sSniffLock) {
                sSniffedMediaJson = null;
                sSniffPending = true;
            }
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    synchronized (sSniffLock) {
                        if (sSniffPending) {
                            sSniffPending = false;
                            sSniffedMediaJson = null;
                            toastShort(T("没有发现可下载的资源", "No downloadable resources found"));
                        }
                    }
                }
            }, 2500);
            injectSniffJs(sCurrentRealTab);
            XposedBridge.log("[SBPlus] sniff JS injected, waiting callback...");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] sniffCurrentPage error: " + t);
            return false;
        }
    }

    /** 确保嗅探用 JS 桥已注入到 realTab(幂等)。 */
    private void registerJsBridgeForSniff(Object realTab) {
        try {
            XposedHelpers.callMethod(realTab, "addJavaScriptInterface", new SbplusJsBridge(), "__sbplus__");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] registerJsBridgeForSniff error: " + t);
        }
    }

    /** 注入嗅探 JS 并用 evaluateJavascript 返回值回调拿结果(不依赖 JS 桥)。 */
    private void injectSniffJs(final Object realTab) {
        try {
            // Tab 类没有 Android 的 evaluateJavascript(ValueCallback<String>),
            // 用三星自己的 evaluateJavaScript(String, TerraceJavaScriptCallback)(与油猴注入同一机制)。
            final java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean(false);
            final MainHook self = this;
            com.sbplus.browser.MainHook.JsResultListener lsn = new com.sbplus.browser.MainHook.JsResultListener() {
                @Override public void onResult(final String r) {
                    if (r == null) return;
                    if (!delivered.compareAndSet(false, true)) return;  // 去重
                    self.handleSniffResult(r);
                }
            };
            evaluateJsWithResult(realTab, SNIFF_JS, lsn);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectSniffJs error: " + t);
            synchronized (sSniffLock) { sSniffPending = false; }
        }
    }

    /** 处理嗅探 JS 返回值(可能来自 TerraceJavaScriptCallback 或 reportMedia 桥)。 */
    private void handleSniffResult(final String raw) {
        try {
            XposedBridge.log("[SBPlus] sniff result RAW head: " + (raw == null ? "null" : raw.substring(0, Math.min(120, raw.length()))));
            synchronized (sSniffLock) { sSniffPending = false; }
            if (raw == null || raw.isEmpty()) {
                toastShort(T("没有发现可下载的资源", "No downloadable resources found"));
                XposedBridge.log("[SBPlus] sniff result empty");
                return;
            }
            String v = raw.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            XposedBridge.log("[SBPlus] sniff result JSON head: " + (v == null ? "null" : v.substring(0, Math.min(120, v.length()))));
            final String data = v;
            final MainHook self = this;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() { @Override public void run() {
                try {
                    XposedBridge.log("[SBPlus] sniff SHOW begin");
                    self.showMediaDialog(data);
                    XposedBridge.log("[SBPlus] sniff SHOW end");
                } catch (Throwable t) { XposedBridge.log("[SBPlus] sniff result handler error: " + t); }
            }});
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] handleSniffResult error: " + t);
        }
    }


    /** SbplusJsBridge.reportMedia 回调入口(静态)。 */
    public static void onSniffedMedia(String jsons) {
        try {
            XposedBridge.log("[SBPlus] onSniffedMedia got: " + (jsons == null ? "null" : jsons.length() + " chars"));
            if (jsons == null) return;
            synchronized (sSniffLock) {
                if (!sSniffPending) return;   // 已被另一通道或超时处理,去重
            }
            final String data = jsons;
            final MainHook inst = sInstance;
            if (inst != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() { @Override public void run() {
                    try { inst.handleSniffResult(data); } catch (Throwable t) { XposedBridge.log("[SBPlus] onSniffedMedia post error: " + t); }
                }});
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] onSniffedMedia error: " + t);
        }
    }

    /** 从 URL 提取扩展名(小写,带点),取不到返回 type 默认。 */
    private String parseExt(String url, String type) {
        try {
            String path = url.split("[?#]")[0];
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase();
                if (ext.length() <= 6 && ext.matches("[a-z0-9]+")) return "." + ext;
            }
        } catch (Throwable ignored) {}
        return "video".equals(type) ? ".mp4" : ("audio".equals(type) ? ".mp3" : ".jpg");
    }

    /** 秒数格式化为 mm:ss / h:mm:ss。 */
    private String fmtDuration(double sec) {
        try {
            if (sec <= 0 || Double.isNaN(sec) || Double.isInfinite(sec)) return "?";
            int s = (int) Math.round(sec);
            int hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
            if (hh > 0) return hh + ":" + (mm < 10 ? "0" : "") + mm + ":" + (ss < 10 ? "0" : "") + ss;
            return mm + ":" + (ss < 10 ? "0" : "") + ss;
        } catch (Throwable ignored) { return "?"; }
    }

    /** 从 URL 提取扩展名+清晰度标签(如 720P),用于标题后缀。 */
    private String videoQuality(String url, int vW, int vH) {
        try {
            String u = url.toLowerCase();
            if (u.contains("2160") || u.contains("4k")) return "4K";
            if (u.contains("1440") || u.contains("2k")) return "2K";
            if (u.contains("1080") || vH >= 1000) return "1080P";
            if (u.contains("720") || (vH >= 600 && vH < 1000)) return "720P";
            if (u.contains("480") || (vH >= 400 && vH < 600)) return "480P";
            if (vH > 0) return vH + "P";
        } catch (Throwable ignored) {}
        return "";
    }

    /** 批量下载:选中项 <= 10 逐个加入;> 10 打包成 zip 后加入。 */
    private void downloadMany(final java.util.List<Integer> idxList,
                              final java.util.List<String> urls,
                              final java.util.List<String> types,
                              final java.util.List<String> titles) {
        try {
            // ---- m3u8 播放列表分流: 选中含 m3u8 时走内置解析+多线程+MP4, 不走ADM ----
            try {
                // 挑出所有 m3u8 项
                java.util.List<Integer> m3 = new java.util.ArrayList<Integer>();
                java.util.List<Integer> notM3 = new java.util.ArrayList<Integer>();
                boolean hasAny = false;
                for (int i : idxList) {
                    boolean isM3 = false;
                    if (i >= 0 && i < urls.size()) {
                        String u = urls.get(i);
                        String pl = u.split("[?#]")[0].toLowerCase();
                        if (pl.endsWith(".m3u8")) isM3 = true;
                        else if (pl.endsWith(".m3u")) isM3 = true;
                    }
                    if (isM3) { m3.add(Integer.valueOf(i)); hasAny = true; }
                    else notM3.add(Integer.valueOf(i));
                }
                if (hasAny) {
                    final java.util.List<Integer> fM3 = new java.util.ArrayList<Integer>(m3);
                    final java.util.List<Integer> fNot = new java.util.ArrayList<Integer>(notM3);
                    final java.util.List<String> fUrls = urls, fTypes = types, fTitles = titles;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            int cfgParallel = 2;
                            try { cfgParallel = prefs.getInt("download_parallel", 2); } catch (Throwable ignored) {}
                            if (cfgParallel < 1) cfgParallel = 1;
                            if (cfgParallel > 10) cfgParallel = 10;
                            com.sbplus.browser.SbDownloadManager.acquireTaskSlot(cfgParallel);
                            try {
                                final int[] ok = new int[]{0};
                                for (Integer mi : fM3) {
                                    try {
                                        String url = fUrls.get(mi.intValue());
                                        String ti = (mi.intValue() < fTitles.size()) ? fTitles.get(mi.intValue()) : null;
                                        boolean done = false;
                                        String dlMode = "internal";
                                        try { dlMode = prefs.getString("dl_mode", "internal"); } catch (Throwable ignored) {}
                                        if ("external".equals(dlMode)) {
                                            // 外部下载器模式: 转交 ADM 等
                                            DownloadMeta meta = new DownloadMeta();
                                            meta.url = url;
                                            meta.fileName = (ti != null && !ti.isEmpty()) ? ti : shortUrl(url);
                                            meta.mimeType = "application/x-mpegURL";
                                            try { done = dispatchToDownloader(meta); } catch (Throwable t) { XposedBridge.log("[SBPlus] external m3u8 dispatch: " + t); done = false; }
                                            if (done) ok[0]++;
                                        } else {
                                            done = downloadM3u8(url, ti);
                                            if (done) ok[0]++;
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[SBPlus] m3u8 download error: " + t);
                                    }
                                }
                                final int don = ok[0];
                                android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper());
                                hh.post(new Runnable() { @Override public void run() {
                                    if (don > 0) toastShort(T("MP4 下载完成: " + don + " 个", "MP4 downloaded: " + don + " file(s)"));
                                    else toastShort(T("MP4 下载失败", "MP4 download failed"));
                                }});
                                if (!fNot.isEmpty()) {
                                    try { downloadMany(fNot, fUrls, fTypes, fTitles); } catch (Throwable t) { XposedBridge.log("[SBPlus] not-m3u8 rest error: " + t); }
                                }
                                XposedBridge.log("[SBPlus] m3u8 batch done m3=" + fM3.size() + " ok=" + don);
                            } finally {
                                com.sbplus.browser.SbDownloadManager.releaseTaskSlot();
                            }
                        }
                    }).start();
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] m3u8 branch error: " + t);
            }
            // ---- 需求2: 分段(视频音频)自动识别合并,与包装zip不冲突 ----
            // ---- B站 DASH 音视频配对识别: 优先于普通分段(避免把 -1视频/-2音频 当分段拼坏) ----
            try {
                final java.util.Set<Integer> pairIdx = new java.util.HashSet<Integer>();
                final java.util.List<int[]> pairs = findAllDashPairs(idxList, urls, types);
                for (int[] p : pairs) { pairIdx.add(Integer.valueOf(p[0])); pairIdx.add(Integer.valueOf(p[1])); }
                if (!pairs.isEmpty()) {
                    final java.util.List<int[]> fPairs = pairs;
                    final java.util.List<String> fUrls = urls, fTypes = types, fTitles = titles;
                    final java.util.List<Integer> fRest = new java.util.ArrayList<Integer>();
                    for (int i : idxList) if (!pairIdx.contains(Integer.valueOf(i))) fRest.add(Integer.valueOf(i));
                    final boolean hasRest = !fRest.isEmpty();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final int[] ok = new int[]{0};
                            try {
                                com.sbplus.browser.SbDownloadManager.acquireTaskSlot(1);
                                try {
                                    for (int[] p : fPairs) {
                                        try {
                                            String ti = (p[0] < fTitles.size()) ? fTitles.get(p[0]) : null;
                                            if (ti == null || ti.isEmpty()) ti = (p[1] < fTitles.size()) ? fTitles.get(p[1]) : null;
                                            java.io.File out = downloadBiliDashPair(fUrls.get(p[0]), fUrls.get(p[1]), ti);
                                            if (out != null) ok[0]++;
                                        } catch (Throwable t) { XposedBridge.log("[SBPlus] dash pair download error: " + t); }
                                    }
                                } finally {
                                    com.sbplus.browser.SbDownloadManager.releaseTaskSlot();
                                }
                            } catch (Throwable t) { XposedBridge.log("[SBPlus] dash pairs error: " + t); }
                            int don = ok[0];
                            android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper());
                            hh.post(new Runnable() { @Override public void run() {
                                toastShort(don > 0 ? T("B站视频合并完成: " + don, "Bilibili done: " + don) : T("B站视频合并失败", "Bilibili merge failed"));
                            }});
                            if (hasRest && !fRest.isEmpty()) {
                                try { downloadMany(fRest, fUrls, fTypes, fTitles); } catch (Throwable t) { XposedBridge.log("[SBPlus] dash rest error: " + t); }
                            }
                        }
                    }).start();
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] dash pair branch error: " + t);
            }
            try {
                java.util.List<java.util.List<Integer>> groups = groupSegments(idxList, urls, types);
                if (groups != null && !groups.isEmpty()) {
                    // 拆分出被合并的分片索引,以及剩余非分段项
                    java.util.Set<Integer> mergedSet = new java.util.HashSet<Integer>();
                    for (java.util.List<Integer> g : groups) for (Integer i : g) mergedSet.add(i);
                    java.util.List<Integer> rest = new java.util.ArrayList<Integer>();
                    for (int i : idxList) if (!mergedSet.contains(Integer.valueOf(i))) rest.add(Integer.valueOf(i));
                    final java.util.List<java.util.List<Integer>> fg = new java.util.ArrayList<java.util.List<Integer>>(groups);
                    final java.util.List<String> fUrls = urls, fTypes = types, fTitles = titles;
                    final boolean hasRest = !rest.isEmpty();
                    final java.util.List<Integer> fRest = rest;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final int[] ok = new int[]{0};
                            for (java.util.List<Integer> g : fg) {
                                try {
                                    java.io.File out = downloadAndMergeSegments(g, fUrls, fTypes, fTitles);
                                    if (out != null) ok[0]++;
                                } catch (Throwable t) {
                                    XposedBridge.log("[SBPlus] merge segment group error: " + t);
                                }
                            }
                            final int done = ok[0];
                            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                            h.post(new Runnable() { @Override public void run() {
                                if (done > 0) {
                                    toastShort(T("分段合成完成: " + done + " 个文件", "Merged: " + done + " file(s)"));
                                } else {
                                    toastShort(T("分段合成失败", "Merge failed"));
                                }
                            }});
                            // 剩余非分段项:重新交由 downloadMany 自身的逻辑处理(含 >10 打包 zip)
                            if (hasRest && !fRest.isEmpty()) {
                                try { downloadMany(fRest, fUrls, fTypes, fTitles); } catch (Throwable t) { XposedBridge.log("[SBPlus] rest download error: " + t); }
                            }
                            XposedBridge.log("[SBPlus] segments merged groups=" + fg.size() + " ok=" + done + " rest=" + fRest.size());
                        }
                    }).start();
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] segment grouping error: " + t);
            }
            int c = idxList.size();
            // 所有项(无论多少)逐个下载: 分段已在前面合并处理, 这里每个独立项单独下载为任务.
            final java.util.List<Integer> fIdx = new java.util.ArrayList<Integer>(idxList);
            final java.util.List<String> fUrls = urls, fTypes = types, fTitles = titles;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int[] done = new int[]{0};
                    final int[] failed = new int[]{0};
                    try {
                        int cfgParallel = 2;
                        try { cfgParallel = prefs.getInt("download_parallel", 2); } catch (Throwable ignored) {}
                        if (cfgParallel < 1) cfgParallel = 1;
                        if (cfgParallel > 10) cfgParallel = 10;
                        com.sbplus.browser.SbDownloadManager.acquireTaskSlot(cfgParallel);
                        try {
                            int cfgThreads = 16;
                            try { cfgThreads = prefs.getInt("download_threads", 16); } catch (Throwable ignored) {}
                            final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(6, cfgThreads / 2)));
                            final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
                            int N = fIdx.size();
                            for (int w = 0; w < Math.max(1, Math.min(4, cfgParallel)); w++) {
                                pool.execute(new Runnable() {
                                    @Override public void run() {
                                        while (true) {
                                            int k = next.getAndIncrement();
                                            if (k >= fIdx.size()) break;
                                            int realIdx = fIdx.get(k).intValue();
                                            try {
                                                boolean ok = downloadOneItem(fUrls.get(realIdx), fTypes.get(realIdx),
                                                        (realIdx < fTitles.size()) ? fTitles.get(realIdx) : null);
                                                if (ok) done[0]++; else failed[0]++;
                                            } catch (Throwable t) { failed[0]++; }
                                        }
                                    }
                                });
                            }
                            pool.shutdown();
                            try { pool.awaitTermination(60, java.util.concurrent.TimeUnit.MINUTES); } catch (Throwable ignored) {}
                        } finally {
                            com.sbplus.browser.SbDownloadManager.releaseTaskSlot();
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] batch download error: " + t);
                    }
                    final int dOk = done[0], dFail = failed[0];
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(new Runnable() { @Override public void run() {
                        try {
                            if (dFail > 0) toastShort(T("下载完成: 成功 " + dOk + " 失败 " + dFail, "Done: ok " + dOk + " fail " + dFail));
                            else toastShort(T("全部 " + dOk + " 个下载完成", "All " + dOk + " downloaded"));
                        } catch (Throwable ignored) {}
                    }});
                    XposedBridge.log("[SBPlus] batch download done ok=" + dOk + " fail=" + dFail);
                }
            }).start();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadMany error: " + t);
        }
    }


    /** 展示嗅探结果对话框;用户选择媒体后 dispatch 到第三方下载器。 */
    /** 单个媒体项下载(注册任务,支持取消清理): 视频/音频落盘 Download/SBPlus/, 视频 .ts 直链转 MP4. 返回 success. */
    private boolean downloadOneItem(final String url, final String type, final String title) {
        try {
            String baseName = null;
            if (title != null && !title.isEmpty()) baseName = sanitizeFileName(title);
            if (baseName == null || baseName.isEmpty()) {
                String uu = url;
                int hq = uu.indexOf('?'); if (hq > 0) uu = uu.substring(0, hq);
                int hh = uu.indexOf('#'); if (hh > 0) uu = uu.substring(0, hh);
                int slash = uu.lastIndexOf('/');
                String last = slash >= 0 ? uu.substring(slash + 1) : uu;
                if (last == null || last.isEmpty()) last = uu;
                int dot = last.lastIndexOf('.');
                if (dot > 0) last = last.substring(0, dot);
                if (last == null || last.isEmpty()) last = "media";
                baseName = sanitizeFileName(last);
            }
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
            if (!dir.exists()) dir.mkdirs();

            String path = url.split("[?#]")[0].toLowerCase();
            boolean isTs = path.endsWith(".ts");
            boolean isM4s = path.endsWith(".m4s");
            boolean isVideoLike = "video".equals(type) || isTs || isM4s || path.endsWith(".mp4") || path.endsWith(".m4v");
            boolean isAudio = "audio".equals(type) || path.endsWith(".mp3") || path.endsWith(".m4a")
                    || path.endsWith(".aac") || path.endsWith(".ogg") || path.endsWith(".opus");

            final String taskId = "dl_" + System.currentTimeMillis();
            final com.sbplus.browser.SbDownloadManager.Task task =
                    com.sbplus.browser.SbDownloadManager.register(taskId, baseName);
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING;
            task.url = url;
            task.kind = "single";
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);

            byte[] b = httpGetBytes(url);
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) {
                try { com.sbplus.browser.SbDownloadManager.remove(taskId); } catch (Throwable ignored) {}
                return false;
            }
            if (b == null || b.length == 0) {
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
                task.detail = "下载失败";
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                return false;
            }

            java.io.File outFile;
            if (isTs) {
                // .ts 直链: 先落盘 .ts 再转 MP4
                java.io.File tsTmp = new java.io.File(dir, baseName + ".ts");
                int nn = 1;
                while (tsTmp.exists()) { tsTmp = new java.io.File(dir, baseName + "_" + nn + ".ts"); nn++; }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(tsTmp);
                try { fo.write(b); } finally { fo.close(); }
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING;
                task.detail = "转换 MP4";
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                java.io.File mp4 = smartConvert(tsTmp, baseName, task, sAppContext);
                if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) {
                    try { tsTmp.delete(); } catch (Throwable ignored) {}
                    try { if (mp4 != null) mp4.delete(); } catch (Throwable ignored) {}
                    try { com.sbplus.browser.SbDownloadManager.remove(taskId); } catch (Throwable ignored) {}
                    return false;
                }
                if (mp4 != null && mp4.exists() && mp4.length() > 0) {
                    try { tsTmp.delete(); } catch (Throwable ignored) {}
                    outFile = mp4;
                } else {
                    outFile = tsTmp;
                }
            } else {
                String ext = parseExt(url, type);
                if (!ext.startsWith(".")) ext = isAudio ? ".mp3" : ".mp4";
                outFile = new java.io.File(dir, baseName + ext);
                int nn = 1;
                while (outFile.exists()) { outFile = new java.io.File(dir, baseName + "_" + nn + ext); nn++; }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(outFile);
                try { fo.write(b); } finally { fo.close(); }
            }
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_DONE;
            task.outPath = outFile.getAbsolutePath();
            task.totalBytes = outFile.length();
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            try {
                if (sAppContext != null) {
                    android.content.Intent scan = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    scan.setData(android.net.Uri.fromFile(outFile));
                    sAppContext.sendBroadcast(scan);
                }
            } catch (Throwable ignored) {}
            XposedBridge.log("[SBPlus] single item downloaded -> " + outFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadOneItem error: " + t);
            return false;
        }
    }

    /** 展示嗅探结果对话框;用户选择媒体后 dispatch 到第三方下载器。 */
    /** 需求2: 识别分段组。返回 group 列表(每个 group 是 idxList 中属于同一分段视频的多个索引,按序号排序)。*/
    private java.util.List<java.util.List<Integer>> groupSegments(final java.util.List<Integer> idxList,
                                                                  final java.util.List<String> urls,
                                                                  final java.util.List<String> types) {
        java.util.List<java.util.List<Integer>> result = new java.util.ArrayList<java.util.List<Integer>>();
        try {
            // map: base -> ordered map of seq->idx
            java.util.Map<String, java.util.TreeMap<Integer, Integer>> map = new java.util.LinkedHashMap<String, java.util.TreeMap<Integer, Integer>>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                String[] sb = segmentInfo(url);
                String base = sb[0];
                if (base == null) continue;
                // B站 DASH m4s 音视频分离流不参与普通分段合并(避免把 -1视频/-2音频 当分段拼坏)
                if (url != null) {
                    String ul = url.toLowerCase();
                    if ((ul.contains("bilivideo.com") || ul.contains("upos-sz") || ul.contains("upgcx"))
                            && ul.contains(".m4s")) {
                        continue;
                    }
                }
                int seq = 0;
                try { seq = Integer.parseInt(sb[1]); } catch (Throwable ignored) { seq = 0; }
                java.util.TreeMap<Integer, Integer> m = map.get(base);
                if (m == null) { m = new java.util.TreeMap<Integer, Integer>(); map.put(base, m); }
                m.put(seq, Integer.valueOf(i));
            }
            for (java.util.Map.Entry<String, java.util.TreeMap<Integer, Integer>> e : map.entrySet()) {
                java.util.TreeMap<Integer, Integer> m = e.getValue();
                // 至少 2 个分片才合并
                if (m.size() < 2) continue;
                // 必须能按 1,2,3... 连续排序 (允许 0,1,2 或 1,2,3)
                java.util.List<Integer> seqs = new java.util.ArrayList<Integer>(m.keySet());
                java.util.List<Integer> group = new java.util.ArrayList<Integer>();
                for (Integer k : seqs) group.add(m.get(k));
                if (group.size() >= 2) result.add(group);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] groupSegments error: " + t);
        }
        return result;
    }

    /** 返回 {base, seq}。若 URL 是分段 m4s 则 base!=null;否则 base==null。 */
    /** B站 DASH 音视频配对检测. 返回所有 [videoIdx, audioIdx] 对. 规则: 同 base(bilivideo m4s) 下 video尾号-1 + audio尾号-2. */
    private java.util.List<int[]> findAllDashPairs(final java.util.List<Integer> idxList,
                                                  final java.util.List<String> urls,
                                                  final java.util.List<String> types) {
        java.util.List<int[]> result = new java.util.ArrayList<int[]>();
        try {
            // base -> audioIdx (bilivideo m4s 音频)
            java.util.Map<String, Integer> audioByBase = new java.util.HashMap<String, Integer>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                if (url == null) continue;
                String lower = url.toLowerCase();
                boolean bili = lower.contains("bilivideo.com") || lower.contains("upos-sz") || lower.contains("upgcx");
                if (!bili) continue;
                String path = url.split("[?#]")[0];
                String lowerPath = path.toLowerCase();
                if (!lowerPath.endsWith(".m4s") && !lowerPath.endsWith(".m4a")) continue;
                String noExt = path.substring(0, path.lastIndexOf('.'));
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([-_])(\\d+)$").matcher(noExt);
                if (!mm.find()) continue;
                String base = noExt.substring(0, mm.start());
                if (base.isEmpty()) continue;
                String seq = mm.group(2);
                String type = (i < types.size()) ? types.get(i) : "";
                boolean looksAudio = "audio".equals(type) || seq.equals("2") || lower.contains("mime=audio") || lower.contains("audio/mp4");
                boolean looksVideo = "video".equals(type) || seq.equals("1") || lower.contains("mime=video") || lower.contains("video/mp4");
                if (looksAudio && !looksVideo) audioByBase.put(base, Integer.valueOf(i));
            }
            // 第二遍: 找视频流与音频配对
            java.util.Set<Integer> used = new java.util.HashSet<Integer>();
            for (int i : idxList) {
                if (i < 0 || i >= urls.size()) continue;
                String url = urls.get(i);
                if (url == null) continue;
                String lower = url.toLowerCase();
                if (!(lower.contains("bilivideo.com") || lower.contains("upos-sz") || lower.contains("upgcx"))) continue;
                String path = url.split("[?#]")[0];
                if (!path.toLowerCase().endsWith(".m4s")) continue;
                String noExt = path.substring(0, path.lastIndexOf('.'));
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([-_])(\\d+)$").matcher(noExt);
                if (!mm.find()) continue;
                String base = noExt.substring(0, mm.start());
                String seq = mm.group(2);
                String type = (i < types.size()) ? types.get(i) : "";
                boolean looksVideo = "video".equals(type) || seq.equals("1") || lower.contains("mime=video") || lower.contains("video/mp4");
                if (!looksVideo) continue;
                if (used.contains(Integer.valueOf(i))) continue;
                Integer ai = audioByBase.get(base);
                if (ai == null && audioByBase.size() == 1) ai = audioByBase.values().iterator().next();
                if (ai != null && !used.contains(ai) && ai.intValue() != i) {
                    result.add(new int[]{i, ai.intValue()});
                    used.add(Integer.valueOf(i)); used.add(ai);
                }
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] findAllDashPairs error: " + t);
            return result;
        }
    }

    /** 下载 B站 DASH 音视频对: 分别下载 video/audio m4s, 然后用 MediaMuxer 双轨合并为一个 MP4. */
    private java.io.File downloadBiliDashPair(String vUrl, String aUrl, String title) {
        java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
        if (!dir.exists()) dir.mkdirs();
        String baseName = null;
        if (title != null && !title.isEmpty()) baseName = sanitizeFileName(title);
        if (baseName == null || baseName.isEmpty()) baseName = "bilibili_" + System.currentTimeMillis();
        String n = baseName;
        int nn = 1;
        while (new java.io.File(dir, n + ".mp4").exists()) { n = baseName + "_" + nn; nn++; }

        final String taskId = "dl_" + System.currentTimeMillis();
        final com.sbplus.browser.SbDownloadManager.Task task =
                com.sbplus.browser.SbDownloadManager.register(taskId, baseName);
        task.status = com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING;
        task.url = vUrl;
        task.kind = "dash";
        task.detail = "下载音视频流";
        com.sbplus.browser.SbDownloadManager.post(sAppContext, task);

        try {
            byte[] v = httpGetBytes(vUrl);
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) { cleanupTaskFile(task); return null; }
            if (v == null || v.length == 0) {
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "视频流下载失败";
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task); return null;
            }
            byte[] a = httpGetBytes(aUrl);
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) { cleanupTaskFile(task); return null; }
            if (a == null || a.length == 0) {
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "音频流下载失败";
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task); return null;
            }
            // 落盘临时文件 (.video.m4s / .audio.m4s)
            java.io.File vTmp = new java.io.File(dir, n + ".video.m4s");
            java.io.File aTmp = new java.io.File(dir, n + ".audio.m4s");
            java.io.FileOutputStream vf = new java.io.FileOutputStream(vTmp);
            try { vf.write(v); } finally { vf.close(); }
            java.io.FileOutputStream af = new java.io.FileOutputStream(aTmp);
            try { af.write(a); } finally { af.close(); }
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING;
            task.detail = "合并音视频";
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            java.io.File out = muxTwoFiles(vTmp, aTmp, new java.io.File(dir, n + ".mp4"), task);
            vTmp.delete(); aTmp.delete();
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) { cleanupTaskFile(task); try { if (out != null) out.delete(); } catch (Throwable ignored) {} return null; }
            if (out == null || !out.exists() || out.length() <= 0) {
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "合并失败";
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task); return null;
            }
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_DONE;
            task.outPath = out.getAbsolutePath();
            task.totalBytes = out.length();
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            try {
                if (sAppContext != null) {
                    android.content.Intent scan = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    scan.setData(android.net.Uri.fromFile(out));
                    sAppContext.sendBroadcast(scan);
                }
            } catch (Throwable ignored) {}
            XposedBridge.log("[SBPlus] bili dash merged -> " + out.getAbsolutePath() + " (" + out.length() + ")");
            return out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadBiliDashPair error: " + t);
            return null;
        }
    }

    /** 双文件 mux 合并: 将 videoFile 的视频轨 + audioFile 的音频轨写入 mp4Out. */
    private java.io.File muxTwoFiles(java.io.File videoFile, java.io.File audioFile, java.io.File mp4Out,
                                     com.sbplus.browser.SbDownloadManager.Task task) {
        android.media.MediaExtractor vx = null, ax = null;
        android.media.MediaMuxer mx = null;
        try {
            vx = new android.media.MediaExtractor();
            vx.setDataSource(videoFile.getAbsolutePath());
            ax = new android.media.MediaExtractor();
            ax.setDataSource(audioFile.getAbsolutePath());
            int vTrack = -1, aTrack = -1;
            String vMime = null, aMime = null;
            android.media.MediaFormat vfmt = null, afmt = null;
            for (int i = 0; i < vx.getTrackCount(); i++) {
                android.media.MediaFormat f = vx.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vTrack = i; vMime = mime; vfmt = f; break; }
            }
            for (int i = 0; i < ax.getTrackCount(); i++) {
                android.media.MediaFormat f = ax.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { aTrack = i; aMime = mime; afmt = f; break; }
            }
            if (vTrack < 0 || aTrack < 0) {
                XposedBridge.log("[SBPlus] muxTwoFiles: missing track v=" + vTrack + " a=" + aTrack);
                return null;
            }
            mx = new android.media.MediaMuxer(mp4Out.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mv = mx.addTrack(vfmt);
            int ma = mx.addTrack(afmt);
            mx.start();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            // 写视频轨
            vx.selectTrack(vTrack);
            long vFirst = -1;
            while (true) {
                int sz = vx.readSampleData(buf, 0);
                if (sz < 0) break;
                long t = vx.getSampleTime();
                if (vFirst < 0) vFirst = t;
                info.offset = 0; info.size = sz;
                info.presentationTimeUs = t - vFirst;
                info.flags = (vx.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mx.writeSampleData(mv, buf, info);
                if (!vx.advance()) break;
                if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) return null;
            }
            // 写音频轨
            ax.selectTrack(aTrack);
            long aFirst = -1;
            while (true) {
                int sz = ax.readSampleData(buf, 0);
                if (sz < 0) break;
                long t = ax.getSampleTime();
                if (aFirst < 0) aFirst = t;
                info.offset = 0; info.size = sz;
                info.presentationTimeUs = t - aFirst;
                info.flags = (ax.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mx.writeSampleData(ma, buf, info);
                if (!ax.advance()) break;
                if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) return null;
            }
            mx.stop();
            mx.release(); mx = null;
            return mp4Out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] muxTwoFiles error: " + t);
            try { mp4Out.delete(); } catch (Throwable ignored) {}
            return null;
        } finally {
            try { if (vx != null) vx.release(); } catch (Throwable ignored) {}
            try { if (ax != null) ax.release(); } catch (Throwable ignored) {}
            try { if (mx != null) mx.release(); } catch (Throwable ignored) {}
        }
    }

    /** 取消时清理任务文件并移除任务. */
    private void cleanupTaskFile(com.sbplus.browser.SbDownloadManager.Task task) {
        try {
            if (task == null) return;
            if (task.outPath != null && !task.outPath.isEmpty()) {
                try { new java.io.File(task.outPath).delete(); } catch (Throwable ignored) {}
            }
            try {
                if (task.name != null && !task.name.isEmpty()) {
                    java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
                    String base = sanitizeFileName(task.name);
                    java.io.File[] files = dir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            String fn = f.getName();
                            if (fn.startsWith(base + ".") || fn.startsWith(base + "_")) {
                                try { f.delete(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
            task.detail = "已取消";
            try { com.sbplus.browser.SbDownloadManager.remove(task.id); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** 返回 {base, seq}。若 URL 是分段 m4s 则 base!=null;否则 base==null。 */
    private String[] segmentInfo(String url) {
        try {
            if (url == null) return new String[]{null, "0"};
            String path = url.split("[?#]")[0];
            String lower = path.toLowerCase();
            if (!lower.endsWith(".m4s") && !lower.endsWith(".ts") && !lower.endsWith(".mp4") && !lower.endsWith(".m4v")
                    && !lower.endsWith(".m4a") && !lower.endsWith(".aac") && !lower.endsWith(".mp3")
                    && !lower.endsWith(".ogg") && !lower.endsWith(".opus")) {
                return new String[]{null, "0"};
            }
            // 去掉扩展名后,找末尾的序号模式: -N / _N
            String noExt = path.substring(0, path.lastIndexOf('.'));
            // 匹配末尾 "-数字" 或 "_数字" (可以是 _数字_数字 等,取最后一段数字)
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([-_])(\\d+)$").matcher(noExt);
            if (mm.find()) {
                String base = noExt.substring(0, mm.start());
                String seq = mm.group(2);
                // 排除: base 为空 或 base 本身就是纯数字编号(如 foo/123/ )不构成分段
                if (base.isEmpty()) return new String[]{null, "0"};
                return new String[]{base, seq};
            }
            return new String[]{null, "0"};
        } catch (Throwable t) {
            return new String[]{null, "0"};
        }
    }

    /** 下载一个分段组的所有分片,按顺序拼接保存到 Download/SBPlus/*。返回输出文件或 null。 */
    /** 判断 URL 是否 m3u8 播放列表。 */
    private boolean isM3u8Url(String url) {
        try {
            if (url == null) return false;
            String p = url.split("[?#]")[0].toLowerCase();
            return p.endsWith(".m3u8") || p.endsWith(".m3u");
        } catch (Throwable t) { return false; }
    }

    /** 解析 m3u8 内容返回分片绝对 URL 列表; 若是 variant 列表返回 null(需要先解析出子列表)。 */
    private java.util.List<String> parseM3u8Segments(String content, String baseUrl) {
        try {
            if (content == null) return null;
            java.util.List<String> segs = new java.util.ArrayList<String>();
            boolean variant = false;
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-STREAM-INF")) { variant = true; continue; }
                if (line.startsWith("#")) continue;
                if (variant) { segs.add(resolveUrl(line, baseUrl)); variant = false; }
            }
            return segs;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] parseM3u8 error: " + t);
            return null;
        }
    }

    /** 解析 m3u8 内容返回分片(.ts/.m4s) 绝对 URL 列表; variant 已展开。 */
    private java.util.List<String> parseM3u8Ts(String content, String baseUrl) {
        try {
            if (content == null) return new java.util.ArrayList<String>();
            java.util.List<String> segs = new java.util.ArrayList<String>();
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;
                String resolved = resolveUrl(line, baseUrl);
                String pl = resolved.split("[?#]")[0].toLowerCase();
                if (pl.endsWith(".ts") || pl.endsWith(".m4s") || pl.endsWith(".aac") || pl.endsWith(".mp3")) {
                    segs.add(resolved);
                }
            }
            return segs;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] parseM3u8Ts error: " + t);
            return new java.util.ArrayList<String>();
        }
    }

    /** 相对/绝对 URL 统一解析为绝对 URL。 */
    private String resolveUrl(String u, String base) {
        try {
            if (u == null) return base;
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
            if (u.startsWith("//")) return "https:" + u;
            if (base == null) return u;
            int q = base.indexOf('?');
            String baseN = (q >= 0) ? base.substring(0, q) : base;
            int slash = baseN.lastIndexOf('/');
            String dir = (slash >= 0) ? baseN.substring(0, slash + 1) : baseN + "/";
            return dir + u;
        } catch (Throwable t) { return u; }
    }

    /** 下载 m3u8 获取文本内容。 */
    private String httpGetText(String url) {
        try {
            byte[] b = httpGetBytes(url);
            if (b == null) return null;
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] httpGetText error: " + t);
            return null;
        }
    }
    /** 下载 m3u8 视频为 MP4 (解析播放列表 -> 多线程下载分片 -> tsToMp4)。成功返回 true。 */
    /**
     * 高效下载分片列表并顺序拼接成单一文件.
     * 策略: 固定并发线程池 + 工作队列(每线程循环取序号) + 每分片独立落盘 + 按序拼接.
     * 优点: 无短板效应, 高并发, 内存安全.
     */
    private java.io.File downloadSegmentsHighConcurrent(final java.util.List<String> segs,
                                                        final java.io.File tmpDir,
                                                        final String baseName,
                                                        final com.sbplus.browser.SbDownloadManager.Task task,
                                                        final android.content.Context ctx) {
        try {
            final int N = segs.size();
            if (N == 0) return null;
            int cfgThreads = 16;
            try { cfgThreads = prefs.getInt("download_threads", 16); } catch (Throwable ignored) {}
            if (cfgThreads < 1) cfgThreads = 1;
            if (cfgThreads > 32) cfgThreads = 32;
            final int CONCURRENCY = cfgThreads;
            final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicLong bytesDone = new java.util.concurrent.atomic.AtomicLong(0);
            final java.util.List<String> fSegs = segs;
            final java.io.File fTmp = tmpDir;
            final String fBase = baseName;
            final java.util.concurrent.CountDownLatch allDone = new java.util.concurrent.CountDownLatch(1);
            final String fTaskId = (task != null) ? task.id : null;

            // 续传: 扫描已存在的 .part_ 文件, 对应序号直接算完成(跳过下载).
            // 取消续传时也可利用: 重新触发同名下载 => 已下载分片不再重复下载.
            final boolean[] skipped = new boolean[N];
            for (int s = 0; s < N; s++) {
                java.io.File pf = new java.io.File(fTmp, fBase + ".part_" + s);
                if (pf.exists() && pf.length() > 0) {
                    skipped[s] = true;
                    long sz = pf.length();
                    okCount.incrementAndGet();
                    bytesDone.addAndGet(sz);
                    if (task != null) {
                        task.partCount = (int)(okCount.get());
                        task.partTotal = N;
                        task.totalBytes = bytesDone.get();
                    }
                }
            }
            if (okCount.get() >= N) {
                // 所有分片已存在 -> 直接拼接
                java.io.File out = mergeParts(fTmp, fBase, N);
                if (out != null) { XposedBridge.log("[SBPlus] seg all cached, merged"); return out; }
            }

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(CONCURRENCY);
            // 每个工作线程循环取序号下载, 直到取完
            final int workerCount = CONCURRENCY;
            for (int w = 0; w < workerCount; w++) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            while (true) {
                                // 取消: 立即中断下载循环
                                if (com.sbplus.browser.SbDownloadManager.isCancelled(fTaskId)) {
                                    return;
                                }
                                // 暂停: 不再取新序号(保留已下载分片). 恢复后由新线程继续.
                                if (com.sbplus.browser.SbDownloadManager.isPaused(fTaskId)) {
                                    return;
                                }
                                int seq = next.getAndIncrement();
                                if (seq >= N) break;
                                if (skipped[seq]) continue;
                                byte[] b = null;
                                // 失败自动重试: 最多 3 次, 间隔递增(0.5s/1s/2s)
                                for (int retry = 0; retry < 3 && b == null; retry++) {
                                    try {
                                        b = httpGetBytes(fSegs.get(seq));
                                    } catch (Throwable t) {
                                        b = null;
                                    }
                                    if (b == null || b.length == 0) {
                                        if (retry < 2) {
                                            try { java.lang.Thread.sleep(500L * (retry + 1)); } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                                try {
                                    if (b != null && b.length > 0) {
                                        java.io.File pf = new java.io.File(fTmp, fBase + ".part_" + seq);
                                        java.io.FileOutputStream po = new java.io.FileOutputStream(pf);
                                        try { po.write(b); } finally { po.close(); }
                                        bytesDone.addAndGet(b.length);
                                        okCount.incrementAndGet();
                                        // 进度更新
                                        if (task != null) {
                                            task.partCount = (int)(okCount.get());
                                            task.partTotal = N;
                                            task.totalBytes = bytesDone.get();
                                            long now = System.currentTimeMillis();
                                            if (now - task.lastTime > 300) {
                                                task.speedBps = (long)((bytesDone.get() - task.lastBytes) * 1000.0 / (now - task.lastTime));
                                                task.lastTime = now;
                                                task.lastBytes = bytesDone.get();
                                                com.sbplus.browser.SbDownloadManager.post(ctx, task);
                                            }
                                        }
                                    } else {
                                        failCount.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failCount.incrementAndGet();
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            // 等待所有 worker 结束(它们循环直到序号取完或暂停/取消)
            pool.shutdown();
            try { pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES); } catch (Throwable ignored) {}
            XposedBridge.log("[SBPlus] seg download done ok=" + okCount.get() + " fail=" + failCount.get() + " bytes=" + bytesDone.get()
                    + " paused=" + com.sbplus.browser.SbDownloadManager.isPaused(fTaskId)
                    + " cancelled=" + com.sbplus.browser.SbDownloadManager.isCancelled(fTaskId));

            // 取消: 删除所有已下载分片, 返回 null
            if (com.sbplus.browser.SbDownloadManager.isCancelled(fTaskId)) {
                XposedBridge.log("[SBPlus] seg cancelled, deleting parts");
                for (int s = 0; s < N; s++) {
                    try { new java.io.File(fTmp, fBase + ".part_" + s).delete(); } catch (Throwable ignored) {}
                }
                try { new java.io.File(fTmp, fBase + ".ts.merge").delete(); } catch (Throwable ignored) {}
                return null;
            }

            if (okCount.get() == 0) return null;

            // 暂停: 保留分片文件, 返回 null (任务保持暂停态, 恢复后重新进入续传)
            if (com.sbplus.browser.SbDownloadManager.isPaused(fTaskId)) {
                XposedBridge.log("[SBPlus] seg paused, parts kept for resume");
                return null;
            }

            // 按序拼接
            java.io.File out = mergeParts(fTmp, fBase, N);
            return out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadSegmentsHighConcurrent error: " + t);
            return null;
        }
    }

    /** 按序拼接 .part_ 文件为单个 .ts.merge 文件. */
    private java.io.File mergeParts(java.io.File fTmp, String fBase, int N) {
        try {
            java.io.File out = new java.io.File(fTmp, fBase + ".ts.merge");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                for (int seq = 0; seq < N; seq++) {
                    java.io.File pf = new java.io.File(fTmp, fBase + ".part_" + seq);
                    if (pf.exists()) {
                        java.io.InputStream in = new java.io.FileInputStream(pf);
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                        in.close();
                        pf.delete();
                    } else {
                        XposedBridge.log("[SBPlus] seg #" + seq + " missing (skipped)");
                    }
                }
                fos.flush();
            } finally { fos.close(); }
            return out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] mergeParts error: " + t);
            return null;
        }
    }

    /** 注册通知点击 -> 打开下载列表 的广播接收器(动态注册在浏览器进程). */
    private void registerDownloadListReceiver(final android.content.Context ctx) {
        try {
            android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    try {
                        String action = i.getAction();
                        if (action == null) return;
                        if (action.equals("com.sbplus.browser.ACTION_SHOW_DOWNLOADS")) {
                            showDownloadList();
                        } else if (action.equals("com.sbplus.browser.ACTION_CANCEL_DL")) {
                            String id = i.getStringExtra("task_id");
                            if (id != null) com.sbplus.browser.SbDownloadManager.cancel(sAppContext, id);
                        } else if (action.equals("com.sbplus.browser.ACTION_PAUSE_DL")) {
                            String id = i.getStringExtra("task_id");
                            if (id != null) com.sbplus.browser.SbDownloadManager.pause(id);
                        } else if (action.equals("com.sbplus.browser.ACTION_RESUME_DL")) {
                            String id = i.getStringExtra("task_id");
                            if (id != null) resumeTask(id);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[SBPlus] download list receiver error: " + t);
                    }
                }
            };
            android.content.IntentFilter flt = new android.content.IntentFilter();
            flt.addAction("com.sbplus.browser.ACTION_SHOW_DOWNLOADS");
            flt.addAction("com.sbplus.browser.ACTION_CANCEL_DL");
            flt.addAction("com.sbplus.browser.ACTION_PAUSE_DL");
            flt.addAction("com.sbplus.browser.ACTION_RESUME_DL");
            try {
                // Android 13+ 需指定导出标志; 通知按钮广播由系统代发, 需 EXPORTED
                ctx.registerReceiver(rcv, flt, android.content.Context.RECEIVER_EXPORTED);
            } catch (Throwable t2) {
                ctx.registerReceiver(rcv, flt);
            }
            XposedBridge.log("[SBPlus] download list receiver registered");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] registerDownloadListReceiver error: " + t);
        }
    }

    /** 在浏览器进程弹出一个下载任务列表对话框. */
    /** 下载设置子对话框: 线程数 / 并行任务数 / 下载方式(内置 vs 外部). */
    private void showDownloadSettingsDialog(final android.app.Activity act) {
        try {
            final android.content.Context ctx = act != null ? act : sAppContext;
            final android.content.SharedPreferences prefs = ctx.getSharedPreferences("samsung_download_bridge",
                    android.content.Context.MODE_PRIVATE);
            int curThreads = prefs.getInt("download_threads", 16);
            int curParallel = prefs.getInt("download_parallel", 2);
            String curMode = prefs.getString("dl_mode", "internal"); // internal=内置, external=外部

            final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            final int pad = (int)(14 * act.getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            // 下载方式
            android.widget.TextView modeLbl = new android.widget.TextView(act);
            modeLbl.setText(T("下载方式", "Download mode"));
            modeLbl.setTextSize(16); modeLbl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            root.addView(modeLbl);
            final android.widget.RadioGroup modeG = new android.widget.RadioGroup(act);
            modeG.setOrientation(android.widget.RadioGroup.VERTICAL);
            final android.widget.RadioButton rInternal = new android.widget.RadioButton(act);
            rInternal.setText(T("内置下载器(多线程+MP4,推荐)", "Built-in (multi-thread + MP4, recommended)"));
            final android.widget.RadioButton rExternal = new android.widget.RadioButton(act);
            rExternal.setText(T("转到外部下载器(ADM 等)", "External downloader (ADM etc.)"));
            modeG.addView(rInternal); modeG.addView(rExternal);
            if ("external".equals(curMode)) rExternal.setChecked(true); else rInternal.setChecked(true);
            root.addView(modeG);
            android.widget.TextView modeHint = new android.widget.TextView(act);
            modeHint.setText(T("内置:嗅探后多线程下载并转 MP4;外部:转交给已设置的下载器连接", "Built-in: download + convert MP4; External: hand off to configured downloader"));
            modeHint.setTextSize(11); modeHint.setTextColor(0xFF888888);
            root.addView(modeHint);

            // 线程数
            android.widget.TextView threadsLbl = new android.widget.TextView(act);
            threadsLbl.setText(T("分片下载线程数(1-32)", "Download threads (1-32)"));
            threadsLbl.setTextSize(14);
            android.widget.LinearLayout.LayoutParams tlLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            tlLp.topMargin = pad;
            root.addView(threadsLbl, tlLp);
            final android.widget.EditText threadsIn = new android.widget.EditText(act);
            threadsIn.setSingleLine(true); threadsIn.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            threadsIn.setText(String.valueOf(curThreads));
            root.addView(threadsIn);

            // 并行任务数
            android.widget.TextView parLbl = new android.widget.TextView(act);
            parLbl.setText(T("同时下载的任务数(1-10)", "Parallel tasks (1-10)"));
            parLbl.setTextSize(14);
            android.widget.LinearLayout.LayoutParams plLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            plLp.topMargin = pad;
            root.addView(parLbl, plLp);
            final android.widget.EditText parIn = new android.widget.EditText(act);
            parIn.setSingleLine(true); parIn.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            parIn.setText(String.valueOf(curParallel));
            root.addView(parIn);

            android.widget.ScrollView sv = new android.widget.ScrollView(act);
            sv.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
            new android.app.AlertDialog.Builder(act)
                .setTitle(T("下载设置", "Download Settings"))
                .setView(sv)
                .setPositiveButton(T("保存", "Save"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            String mode = rExternal.isChecked() ? "external" : "internal";
                            int threads = 16, parallel = 2;
                            try { threads = Integer.parseInt(threadsIn.getText().toString().trim()); } catch (Throwable ignored) {}
                            try { parallel = Integer.parseInt(parIn.getText().toString().trim()); } catch (Throwable ignored) {}
                            threads = Math.max(1, Math.min(32, threads));
                            parallel = Math.max(1, Math.min(10, parallel));
                            prefs.edit()
                                .putString("dl_mode", mode)
                                .putInt("download_threads", threads)
                                .putInt("download_parallel", parallel)
                                .commit();
                            com.sbplus.browser.SbDownloadManager.setParallelCapacity(parallel);
                            toastShort(T("已保存下载设置", "Download settings saved"));
                        } catch (Throwable t) { XposedBridge.log("[SBPlus] save settings: " + t); }
                    }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .create().show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showDownloadSettingsDialog: " + t);
        }
    }

    private void showDownloadList() {
        try {
            final android.app.Activity act = sCurrentActivity != null ? sCurrentActivity : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null);
            if (act == null) { toastShort(T("无活动窗口", "No active window")); return; }
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
                        root.setOrientation(android.widget.LinearLayout.VERTICAL);
                        int pad = (int)(act.getResources().getDisplayMetrics().density * 14 + 0.5f);
                        root.setPadding(pad, dp(act,6), pad, dp(act,4));

                        // 操作栏: 全选 | 删除选中 | 清除记录
                        final android.widget.LinearLayout opBar = new android.widget.LinearLayout(act);
                        opBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        final android.widget.Button bAll = new android.widget.Button(act);
                        final android.widget.Button bDel = new android.widget.Button(act);
                        final android.widget.Button bClear = new android.widget.Button(act);
                        bAll.setText(T("全选", "Select all"));
                        bDel.setText(T("删除选中", "Delete selected"));
                        bClear.setText(T("取消任务", "Cancel tasks"));
                        bAll.setTextSize(12); bDel.setTextSize(12); bClear.setTextSize(12);
                        int bpd = dp(act,6);
                        bAll.setPadding(bpd,bpd,bpd,bpd); bDel.setPadding(bpd,bpd,bpd,bpd); bClear.setPadding(bpd,bpd,bpd,bpd);
                        bAll.setMinHeight(dp(act,4)); bDel.setMinHeight(dp(act,4)); bClear.setMinHeight(dp(act,4));
                        java.util.Set<String> selectedSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
                        boolean[] allChecked = new boolean[]{false};
                        android.widget.LinearLayout.LayoutParams ob1 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
                        android.widget.LinearLayout.LayoutParams ob2 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
                        android.widget.LinearLayout.LayoutParams ob3 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
                        ob1.setMargins(0,dp(act,4),dp(act,4),dp(act,10));
                        ob2.setMargins(dp(act,4),dp(act,4),dp(act,4),dp(act,10));
                        ob3.setMargins(dp(act,4),dp(act,4),0,dp(act,10));
                        opBar.addView(bAll, ob1);
                        opBar.addView(bDel, ob2);
                        opBar.addView(bClear, ob3);
                        root.addView(opBar);

                        final android.widget.LinearLayout list = new android.widget.LinearLayout(act);
                        list.setOrientation(android.widget.LinearLayout.VERTICAL);
                        // 列表包一层 ScrollView, 任务多时可滚动, 最大高度占屏幕~75%
                        final android.widget.ScrollView listScroller = new android.widget.ScrollView(act);
                        listScroller.setFillViewport(false);
                        listScroller.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -2));
                        android.widget.LinearLayout.LayoutParams svLp = new android.widget.LinearLayout.LayoutParams(-1, 0, 1f);
                        root.addView(listScroller, svLp);
                        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

                        bAll.setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                allChecked[0] = !allChecked[0];
                                java.util.List<com.sbplus.browser.SbDownloadManager.Task> ts = com.sbplus.browser.SbDownloadManager.all();
                                selectedSet.clear();
                                if (allChecked[0]) for (com.sbplus.browser.SbDownloadManager.Task tt : ts) selectedSet.add(tt.id);
                                bAll.setText(allChecked[0] ? T("全不选", "Deselect all") : T("全选", "Select all"));
                                refreshListIn(act, root, list, h, selectedSet, bAll, bDel);
                            }
                        });
                        bDel.setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                try {
                                    if (selectedSet.isEmpty()) { toastShort(T("未选中任务", "No task selected")); return; }
                                    if (selectedSet.size() == 1) {
                                        confirmDeleteDownload(act, selectedSet.iterator().next());
                                    } else {
                                        java.util.List<String> ids = new java.util.ArrayList<String>(selectedSet);
                                        confirmDeleteBatch(act, ids);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        bClear.setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) {
                                try {
                                    java.util.List<com.sbplus.browser.SbDownloadManager.Task> ts = com.sbplus.browser.SbDownloadManager.all();
                                    boolean any = false;
                                    for (com.sbplus.browser.SbDownloadManager.Task tt : ts) {
                                        if (tt.status == com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING
                                                || tt.status == com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING) {
                                            if (tt.outPath != null && !tt.outPath.isEmpty()) {
                                                try { new java.io.File(tt.outPath).delete(); } catch (Throwable ignored) {}
                                            }
                                            com.sbplus.browser.SbDownloadManager.cancel(sAppContext, tt.id);
                                            com.sbplus.browser.SbDownloadManager.remove(tt.id);
                                            any = true;
                                        }
                                    }
                                    selectedSet.clear();
                                    if (any) toastShort(T("已取消进行中任务(文件已删除)", "Cancelled running tasks (files removed)"));
                                    else toastShort(T("没有进行中的任务", "No running tasks"));
                                    refreshListIn(act, root, list, h, selectedSet, bAll, bDel);
                                } catch (Throwable ignored) {}
                            }
                        });

                        final Object[] dlgRef = new Object[1];
                        refreshListIn(act, root, list, h, selectedSet, bAll, bDel);
                        // 限制对话框最大高度≈屏幕 85%, 避免列表溢出屏幕无法滚动
                        android.widget.FrameLayout wrap = new android.widget.FrameLayout(act);
                        int maxH = (int)(act.getResources().getDisplayMetrics().heightPixels * 0.85f);
                        wrap.addView(root, new android.widget.FrameLayout.LayoutParams(-1, maxH));
                        dlgRef[0] = new android.app.AlertDialog.Builder(act)
                            .setTitle((CharSequence) null)
                            .setView(wrap)
                            .setPositiveButton(T("关闭", "Close"), null)
                            .create();
                        ((android.app.AlertDialog) dlgRef[0]).show();
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] showDownloadList ui error: " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log("[SBPlus] showDownloadList error: " + t); }
    }

    private void refreshListIn(final android.app.Activity act, final android.widget.LinearLayout root,
                               final android.widget.LinearLayout list, final android.os.Handler h,
                               final java.util.Set<String> selectedSet,
                               final android.widget.Button bAll, final android.widget.Button bDel) {
        try {
            list.removeAllViews();
            java.util.List<com.sbplus.browser.SbDownloadManager.Task> tasks = com.sbplus.browser.SbDownloadManager.all();
            if (tasks.isEmpty()) {
                android.widget.TextView empty = new android.widget.TextView(act);
                empty.setText(T("暂无下载任务", "No download tasks"));
                empty.setTextColor(0xFF888888);
                empty.setPadding(0, dp(act, 8), 0, 0);
                list.addView(empty);
            }
            boolean any = false;
            for (final com.sbplus.browser.SbDownloadManager.Task t : tasks) {
                any = true;
                // 每任务一行: 勾选框 + 信息 + 进度
                final android.widget.LinearLayout row = new android.widget.LinearLayout(act);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(act, 4), 0, dp(act, 4));
                final android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setChecked(selectedSet.contains(t.id));
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                        if (isChecked) selectedSet.add(t.id); else selectedSet.remove(t.id);
                        if (bAll != null) {
                            java.util.List<com.sbplus.browser.SbDownloadManager.Task> ts = com.sbplus.browser.SbDownloadManager.all();
                            boolean all = !ts.isEmpty();
                            for (com.sbplus.browser.SbDownloadManager.Task tt : ts) if (!selectedSet.contains(tt.id)) { all = false; break; }
                            bAll.setText(all ? T("全不选", "Deselect all") : T("全选", "Select all"));
                        }
                    }
                });
                row.addView(cb, new android.widget.LinearLayout.LayoutParams(-2, -2));

                android.widget.LinearLayout col = new android.widget.LinearLayout(act);
                col.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.widget.TextView name = new android.widget.TextView(act);
                name.setText(t.name);
                name.setTextSize(14);
                name.setTextColor(0xFF1B1B1B);
                name.setMaxLines(1);
                col.addView(name);
                android.widget.TextView sub = new android.widget.TextView(act);
                sub.setText(statusText(t) + (t.speedBps > 0 && t.status == com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING
                    ? " · " + com.sbplus.browser.SbDownloadManager.fmtSpeed(t.speedBps) + " · " + etaText(t) : ""));
                sub.setTextSize(12);
                sub.setTextColor(0xFF888888);
                col.addView(sub);
                android.widget.ProgressBar pb = new android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(t.percent());
                if (t.status == com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING && t.partTotal <= 0) pb.setIndeterminate(true);
                if (t.status == com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING) pb.setIndeterminate(true);
                if (t.status == com.sbplus.browser.SbDownloadManager.STATUS_FAILED) { pb.setProgress(0); }
                col.addView(pb);
                android.widget.LinearLayout.LayoutParams colLp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
                colLp.gravity = android.view.Gravity.CENTER_VERTICAL;
                row.addView(col, colLp);
                // 暂停/继续按钮 (仅 m3u8 支持续传)
                boolean canPause = "m3u8".equals(t.kind);
                boolean isPaused = com.sbplus.browser.SbDownloadManager.isPaused(t.id);
                if (canPause && (t.status == com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING || isPaused)) {
                    final String fid = t.id;
                    final android.widget.Button btnPause = new android.widget.Button(act);
                    btnPause.setAllCaps(false);
                    btnPause.setTextSize(12);
                    btnPause.setMinWidth(0);
                    btnPause.setMinimumWidth(0);
                    btnPause.setPadding(dp(act, 6), 0, dp(act, 6), 0);
                    btnPause.setText(isPaused ? T("继续", "Resume") : T("暂停", "Pause"));
                    btnPause.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            try {
                                if (com.sbplus.browser.SbDownloadManager.isPaused(fid)) {
                                    android.content.Intent ri = new android.content.Intent("com.sbplus.browser.ACTION_RESUME_DL");
                                    ri.putExtra("task_id", fid);
                                    sAppContext.sendBroadcast(ri);
                                } else {
                                    android.content.Intent pi = new android.content.Intent("com.sbplus.browser.ACTION_PAUSE_DL");
                                    pi.putExtra("task_id", fid);
                                    sAppContext.sendBroadcast(pi);
                                    toastShort(T("已暂停,已下载分片保留", "Paused, parts kept"));
                                }
                                refreshListIn(act, root, list, h, selectedSet, bAll, bDel);
                            } catch (Throwable t2) { XposedBridge.log("[SBPlus] pause btn error: " + t2); }
                        }
                    });
                    android.widget.LinearLayout.LayoutParams bpLp = new android.widget.LinearLayout.LayoutParams(-2, -2);
                    bpLp.gravity = android.view.Gravity.CENTER_VERTICAL;
                    bpLp.leftMargin = dp(act, 4);
                    row.addView(btnPause, bpLp);
                }
                list.addView(row);
            }
            // 刷新按钮状态
            if (bAll != null) {
                java.util.List<com.sbplus.browser.SbDownloadManager.Task> ts = com.sbplus.browser.SbDownloadManager.all();
                boolean all = !ts.isEmpty();
                for (com.sbplus.browser.SbDownloadManager.Task tt : ts) if (!selectedSet.contains(tt.id)) { all = false; break; }
                if (!ts.isEmpty()) bAll.setText(all ? T("全不选", "Deselect all") : T("全选", "Select all"));
            }
            // 1.5秒后自动刷新(进度/速度同步)
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try { if (list.getParent() != null) refreshListIn(act, root, list, h, selectedSet, bAll, bDel); } catch (Throwable ignored) {}
                }
            }, 1500);
        } catch (Throwable t) { XposedBridge.log("[SBPlus] refreshListIn error: " + t); }
    }

    /** 根据已用时间与完成比例估算剩余时间. */
    private String etaText(com.sbplus.browser.SbDownloadManager.Task t) {
        try {
            int p = t.percent();
            if (p <= 0 || p >= 100) return "";
            long elapsed = System.currentTimeMillis() - t.lastTime;
            if (elapsed < 0) elapsed = 0;
            long etaMs = (long)(elapsed * (100.0 / p - 1.0));
            long sec = etaMs / 1000;
            if (sec <= 0) return T("剩余 <1s", "<1s left");
            long hh = sec / 3600, mm = (sec % 3600) / 60, ss = sec % 60;
            String s = hh > 0 ? String.format("%dh%02dm", hh, mm) : (mm > 0 ? String.format("%dm%02ds", mm, ss) : String.format("%ds", ss));
            return T("剩余 ", "ETA ") + s;
        } catch (Throwable t2) { return ""; }
    }

    /** 批量删除(文件+记录). */
    private void confirmDeleteBatch(final android.app.Activity act, final java.util.List<String> ids) {
        try {
            String msg = T("删除选中的 ", "Delete ") + ids.size() + T(" 个任务(文件+记录)?", " tasks (files + records)?");
            new android.app.AlertDialog.Builder(act)
                .setTitle(T("确认删除", "Confirm delete"))
                .setMessage(msg)
                .setPositiveButton(T("删除", "Delete"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        for (String id : ids) {
                            try {
                                com.sbplus.browser.SbDownloadManager.Task t = com.sbplus.browser.SbDownloadManager.get(id);
                                if (t != null && t.outPath != null && !t.outPath.isEmpty()) {
                                    try { new java.io.File(t.outPath).delete(); } catch (Throwable ignored) {}
                                }
                                com.sbplus.browser.SbDownloadManager.remove(id);
                                com.sbplus.browser.SbDownloadManager.cancel(sAppContext, id);
                            } catch (Throwable ignored) {}
                        }
                        toastShort(T("已删除", "Deleted"));
                    }
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .create().show();
        } catch (Throwable t) { XposedBridge.log("[SBPlus] confirmDeleteBatch: " + t); }
    }

    /** 打开已下载的文件(系统媒体播放器). */
    private void openDownloadedFile(android.app.Activity act, String id) {
        try {
            com.sbplus.browser.SbDownloadManager.Task t = com.sbplus.browser.SbDownloadManager.get(id);
            if (t == null || t.outPath == null || t.outPath.isEmpty()) { toastShort(T("文件不存在", "File not found")); return; }
            java.io.File f = new java.io.File(t.outPath);
            if (!f.exists()) { toastShort(T("文件不存在", "File not found")); return; }
            String mime;
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".mp4")) mime = "video/mp4";
            else if (lower.endsWith(".mkv")) mime = "video/x-matroska";
            else if (lower.endsWith(".m4a")) mime = "audio/mp4";
            else if (lower.endsWith(".mp3")) mime = "audio/mpeg";
            else mime = "video/*";
            android.net.Uri uri = android.net.Uri.fromFile(f);
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            it.setDataAndType(uri, mime);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try { act.startActivity(it); }
            catch (Throwable t2) { toastShort(T("无法打开,可用文件管理器打开", "Cannot open")); }
        } catch (Throwable t) { XposedBridge.log("[SBPlus] openDownloadedFile: " + t); }
    }

    /** 确认删除: 询问是否连同文件一起删除. */
    private void confirmDeleteDownload(final android.app.Activity act, final String id) {
        try {
            new android.app.AlertDialog.Builder(act)
                .setTitle(T("删除下载", "Delete download"))
                .setMessage(T("同时删除已下载的文件?", "Also delete the downloaded file?"))
                .setPositiveButton(T("连同文件删除", "Delete file too"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            com.sbplus.browser.SbDownloadManager.Task t = com.sbplus.browser.SbDownloadManager.get(id);
                            if (t != null && t.outPath != null && !t.outPath.isEmpty()) {
                                try { new java.io.File(t.outPath).delete(); } catch (Throwable ignored) {}
                            }
                            com.sbplus.browser.SbDownloadManager.remove(id);
                            com.sbplus.browser.SbDownloadManager.cancel(sAppContext, id);
                            toastShort(T("已删除", "Deleted"));
                        } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton(T("仅删记录", "Record only"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.sbplus.browser.SbDownloadManager.remove(id);
                        com.sbplus.browser.SbDownloadManager.cancel(sAppContext, id);
                        toastShort(T("已删除记录", "Record deleted"));
                    }
                })
                .create().show();
        } catch (Throwable t) { XposedBridge.log("[SBPlus] confirmDelete: " + t); }
    }


    private String statusText(com.sbplus.browser.SbDownloadManager.Task t) {
        try {
            switch (t.status) {
                case com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING:
                    String sizeTxt = "";
                    if (t.totalSizeBytes > 0) {
                        sizeTxt = fmtSize(t.totalBytes) + "/" + fmtSize(t.totalSizeBytes);
                    } else if (t.totalBytes > 0) {
                        sizeTxt = fmtSize(t.totalBytes);
                    }
                    return T((!sizeTxt.isEmpty() ? sizeTxt + " · " : "") + "下载中 " + t.percent() + "%", (!sizeTxt.isEmpty() ? sizeTxt + " · " : "") + "DL " + t.percent() + "%");
                case com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING:
                    return T("转换 MP4 中...", "Converting MP4...");
                case com.sbplus.browser.SbDownloadManager.STATUS_DONE:
                    String sizeDone = t.totalSizeBytes > 0 ? fmtSize(t.totalSizeBytes) : (t.totalBytes > 0 ? fmtSize(t.totalBytes) : "");
                    return T((!sizeDone.isEmpty() ? sizeDone + " · " : "") + "已完成", (!sizeDone.isEmpty() ? sizeDone + " · " : "") + "Done");
                default:
                    return T("失败", "Failed");
            }
        } catch (Throwable t2) { return ""; }
    }

    private String detailText(com.sbplus.browser.SbDownloadManager.Task t) {
        try {
            String s = t.partCount + "/" + t.partTotal + " · " + com.sbplus.browser.SbDownloadManager.fmtSpeed(t.speedBps);
            if (t.detail != null && !t.detail.isEmpty()) s += " · " + t.detail;
            return s;
        } catch (Throwable t2) { return ""; }
    }

    /** 继续被暂停的任务: 清除暂停标记后重新触发下载(利用已存在分片续传). */
    private void resumeTask(final String id) {
        try {
            com.sbplus.browser.SbDownloadManager.Task t = com.sbplus.browser.SbDownloadManager.get(id);
            if (t == null) return;
            com.sbplus.browser.SbDownloadManager.resume(id);
            t.status = com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING;
            t.detail = "续传中";
            t.partCount = 0;
            t.partTotal = 0;
            com.sbplus.browser.SbDownloadManager.post(sAppContext, t);
            final String url = t.url;
            final String name = t.name;
            if (url == null || url.isEmpty()) { toastShort(T("该任务不支持续传", "This task cannot resume")); return; }
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        com.sbplus.browser.SbDownloadManager.acquireTaskSlot(2);
                        try {
                            downloadM3u8Internal(url, name, id);
                        } finally {
                            com.sbplus.browser.SbDownloadManager.releaseTaskSlot();
                        }
                    } catch (Throwable t2) {
                        XposedBridge.log("[SBPlus] resumeTask error: " + t2);
                    }
                }
            }).start();
        } catch (Throwable t2) {
            XposedBridge.log("[SBPlus] resumeTask error: " + t2);
        }
    }

    private boolean downloadM3u8(String m3u8Url, String title) {
        return downloadM3u8Internal(m3u8Url, title, null);
    }

    private boolean downloadM3u8Internal(String m3u8Url, String title, String reuseId) {
        try {
            XposedBridge.log("[SBPlus] downloadM3u8 start: " + m3u8Url);
            // 文件名
            String baseName = null;
            if (title != null && !title.isEmpty()) baseName = sanitizeFileName(title);
            if (baseName == null || baseName.isEmpty()) {
                // 从 URL 提取最后一个有意义的路径段作为文件名(去掉 query/hash)
                String uu = m3u8Url;
                int hq = uu.indexOf('?'); if (hq > 0) uu = uu.substring(0, hq);
                int hh = uu.indexOf('#'); if (hh > 0) uu = uu.substring(0, hh);
                int slash = uu.lastIndexOf('/');
                String last = slash >= 0 ? uu.substring(slash + 1) : uu;
                if (last == null || last.isEmpty()) last = uu;
                // 去掉扩展名
                int dot = last.lastIndexOf('.');
                if (dot > 0) last = last.substring(0, dot);
                if (last == null || last.isEmpty()) last = "video";
                baseName = sanitizeFileName(last);
            }

            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
            if (!dir.exists()) dir.mkdirs();

            // register task for notification + list
            final String taskId;
            final com.sbplus.browser.SbDownloadManager.Task task;
            if (reuseId != null && com.sbplus.browser.SbDownloadManager.get(reuseId) != null) {
                taskId = reuseId;
                task = com.sbplus.browser.SbDownloadManager.get(reuseId);
                com.sbplus.browser.SbDownloadManager.resume(reuseId);   // 清暂停标记
            } else {
                taskId = "dl_" + System.currentTimeMillis();
                task = com.sbplus.browser.SbDownloadManager.register(taskId, baseName);
            }
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING;
            task.url = m3u8Url;
            task.kind = "m3u8";
            task.detail = "解析中";
            task.partCount = 0;
            task.partTotal = 0;
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);

            // 1. 下载主列表
            String masterText = httpGetText(m3u8Url);
            if (masterText == null) { XposedBridge.log("[SBPlus] m3u8: master download failed"); return false; }

            // 2. 递归解析: 可能是 variant 列表, 取最后一个子列表
            String currentUrl = m3u8Url;
            java.util.List<String> segs = parseM3u8Ts(masterText, currentUrl);
            if (segs.isEmpty()) {
                // 是 variant: 取第一个分辩率子列表
                java.util.List<String> variants = parseM3u8Segments(masterText, currentUrl);
                if (variants != null && !variants.isEmpty()) {
                    String subUrl = variants.get(variants.size() - 1);
                    XposedBridge.log("[SBPlus] m3u8 variant -> " + subUrl);
                    String subText = httpGetText(subUrl);
                    if (subText != null) {
                        segs = parseM3u8Ts(subText, subUrl);
                    }
                }
            }
            if (segs.isEmpty()) {
                XposedBridge.log("[SBPlus] m3u8: no segments found");
                return false;
            }
            XposedBridge.log("[SBPlus] m3u8 segments: " + segs.size());

            // 3. 高并发下载分片 -> 顺序拼接 .ts
            XposedBridge.log("[SBPlus] m3u8 parts dl start, id=" + taskId);
            java.io.File tsTmp = downloadSegmentsHighConcurrent(segs, dir, baseName, task, sAppContext);
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) {
                XposedBridge.log("[SBPlus] m3u8 cancelled, cleanup");
                try { if (tsTmp != null) tsTmp.delete(); } catch (Throwable ignored) {}
                if (task != null) {
                    task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
                    task.detail = "已取消";
                }
                return false;
            }
            if (com.sbplus.browser.SbDownloadManager.isPaused(taskId)) {
                XposedBridge.log("[SBPlus] m3u8 paused, keep parts");
                if (task != null) {
                    task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
                    task.detail = "已暂停";
                    com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                }
                return false;
            }
            if (tsTmp == null || !tsTmp.exists() || tsTmp.length() <= 0) {
                XposedBridge.log("[SBPlus] m3u8: all segments failed");
                if (task != null) {
                    task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
                    task.detail = "分片下载失败(已重试)";
                    com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                }
                return false;
            }
            XposedBridge.log("[SBPlus] m3u8 merged ts " + tsTmp.getAbsolutePath() + " (" + tsTmp.length() + " bytes, parts=" + segs.size() + ")");
            // 4. 转 MP4
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING;
            task.detail = "TS " + (tsTmp.length()/1048576) + "MB";
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            java.io.File mp4 = smartConvert(tsTmp, baseName, task, sAppContext);
            // 转换期间被取消: 删产物
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) {
                XposedBridge.log("[SBPlus] m3u8 cancelled during convert, delete files");
                try { tsTmp.delete(); } catch (Throwable ignored) {}
                try { if (mp4 != null) mp4.delete(); } catch (Throwable ignored) {}
                if (task != null) { task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "已取消"; }
                return false;
            }
            if (mp4 != null && mp4.exists() && mp4.length() > 0) {
                try { tsTmp.delete(); } catch (Throwable ignored) {}
                try {
                    if (sAppContext != null) {
                        android.content.Intent scan = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        scan.setData(android.net.Uri.fromFile(mp4));
                        sAppContext.sendBroadcast(scan);
                    }
                } catch (Throwable ignored) {}
                task.status = com.sbplus.browser.SbDownloadManager.STATUS_DONE;
                task.outPath = mp4.getAbsolutePath();
                task.partCount = task.partTotal;
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                XposedBridge.log("[SBPlus] m3u8 -> MP4 OK: " + mp4.getAbsolutePath());
                return true;
            }
            // 失败保留 .ts
            java.io.File tsFinal = new java.io.File(dir, baseName + ".ts");
            int n2 = 1;
            while (tsFinal.exists()) { tsFinal = new java.io.File(dir, baseName + "_" + n2 + ".ts"); n2++; }
            try { tsTmp.renameTo(tsFinal); } catch (Throwable ignored) {}
            XposedBridge.log("[SBPlus] m3u8 mp4 fail, kept ts: " + tsFinal.getAbsolutePath());
            return false;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadM3u8 error: " + t);
            return false;
        }
    }

    private java.io.File downloadAndMergeSegments(final java.util.List<Integer> group,
                                                  final java.util.List<String> urls,
                                                  final java.util.List<String> types,
                                                  final java.util.List<String> titles) {
        try {
            if (group == null || group.isEmpty()) return null;
            String baseName = null;
            String ext = ".ts";
            for (int i : group) {
                if (i >= 0 && i < titles.size() && titles.get(i) != null && !titles.get(i).isEmpty()) {
                    baseName = titles.get(i);
                    break;
                }
            }
            if (baseName == null || baseName.isEmpty()) {
                baseName = sanitizeFileName(fileNameFromUrl(urls.get(group.get(0))));
                int q = baseName.indexOf('.');
                if (q > 0) baseName = baseName.substring(0, q);
            } else {
                baseName = sanitizeFileName(baseName);
            }
            try {
                String u = urls.get(group.get(0));
                String p = u.split("[?#]")[0].toLowerCase();
                if (p.endsWith(".mp4") || p.endsWith(".m4v")) ext = ".mp4";
                else if (p.endsWith(".m4a")) ext = ".m4a";
                else if (p.endsWith(".mp3")) ext = ".mp3";
                else if (p.endsWith(".aac")) ext = ".aac";
                else if (p.endsWith(".ts")) ext = ".ts";
            } catch (Throwable ignored) {}

            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "SBPlus");
            if (!dir.exists()) dir.mkdirs();

            // ===== 1. 高并发下载分片(独立落盘+按序拼接) =====
            final String taskId = "dl_" + System.currentTimeMillis();
            final com.sbplus.browser.SbDownloadManager.Task task =
                    com.sbplus.browser.SbDownloadManager.register(taskId, baseName);
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_DOWNLOADING;
            task.url = urls.get(group.get(0));
            task.kind = "seg";
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            final int N = group.size();
            if (N == 0) return null;
            // 转成有序 URL 列表
            final java.util.List<String> segUrls = new java.util.ArrayList<String>();
            for (int i : group) {
                if (i >= 0 && i < urls.size()) segUrls.add(urls.get(i));
            }
            if (segUrls.isEmpty()) return null;
            java.io.File tsTmp = downloadSegmentsHighConcurrent(segUrls, dir, baseName, task, sAppContext);
            if (com.sbplus.browser.SbDownloadManager.isCancelled(taskId)) {
                XposedBridge.log("[SBPlus] merge cancelled, cleanup");
                try { if (tsTmp != null) tsTmp.delete(); } catch (Throwable ignored) {}
                if (task != null) { task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "已取消"; }
                return null;
            }
            if (com.sbplus.browser.SbDownloadManager.isPaused(taskId)) {
                XposedBridge.log("[SBPlus] merge paused, keep parts");
                if (task != null) { task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; task.detail = "已暂停"; com.sbplus.browser.SbDownloadManager.post(sAppContext, task); }
                return null;
            }
            if (tsTmp == null || !tsTmp.exists() || tsTmp.length() <= 0) {
                XposedBridge.log("[SBPlus] downloadAndMergeSegments: all segments failed");
                return null;
            }
            XposedBridge.log("[SBPlus] merged ts " + tsTmp.getAbsolutePath() + " (" + tsTmp.length() + " bytes, parts=" + segUrls.size() + ")");
            // ===== 3. 转成 MP4 (MediaExtractor + MediaMuxer) =====
            task.status = com.sbplus.browser.SbDownloadManager.STATUS_CONVERTING;
            task.detail = "TS " + (tsTmp.length()/1048576) + "MB";
            com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
            java.io.File result = null;
            boolean isVideo = ext.equals(".ts") || ext.equals(".mp4");
            if (isVideo) {
                java.io.File mp4 = smartConvert(tsTmp, baseName, task, sAppContext);
                if (mp4 != null && mp4.exists() && mp4.length() > 0) {
                    result = mp4;
                    try { tsTmp.delete(); } catch (Throwable ignored) {}
                    task.status = com.sbplus.browser.SbDownloadManager.STATUS_DONE;
                    task.outPath = mp4.getAbsolutePath();
                    task.partCount = task.partTotal;
                    com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                    XposedBridge.log("[SBPlus] converted to MP4: " + mp4.getAbsolutePath());
                } else {
                    // 转 MP4 失败, 保留 .ts
                    java.io.File tsFinal = new java.io.File(dir, baseName + ".ts");
                    int n2 = 1;
                    while (tsFinal.exists()) { tsFinal = new java.io.File(dir, baseName + "_" + n2 + ".ts"); n2++; }
                    try { tsTmp.renameTo(tsFinal); } catch (Throwable ignored) {}
                    result = tsFinal;
                                        task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED;
                    task.detail = "MP4 转换失败, 已保留 TS";
                    com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                    XposedBridge.log("[SBPlus] mp4 conversion failed, kept ts: " + tsFinal.getAbsolutePath());
                }
            } else {
                // 音频等, 直接改名
                java.io.File finalFile = new java.io.File(dir, baseName + ext);
                int n3 = 1;
                while (finalFile.exists()) { finalFile = new java.io.File(dir, baseName + "_" + n3 + ext); n3++; }
                try { tsTmp.renameTo(finalFile); } catch (Throwable ignored) {}
                                task.status = com.sbplus.browser.SbDownloadManager.STATUS_DONE;
                task.outPath = finalFile.getAbsolutePath();
                task.partCount = task.partTotal;
                com.sbplus.browser.SbDownloadManager.post(sAppContext, task);
                result = finalFile;
            }

            if (result != null) {
                try {
                    if (sAppContext != null) {
                        android.content.Intent scan = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        scan.setData(android.net.Uri.fromFile(result));
                        sAppContext.sendBroadcast(scan);
                    }
                } catch (Throwable ignored) {}
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] downloadAndMergeSegments error: " + t);
            return null;
        }
    }
    /** 智能转换: TS 一律优先真转码(H.264 也转, remux TS 坑太多: 音频多帧/时间戳/格式兼容),
     *  转码失败回退 remux。 */
    private java.io.File smartConvert(final java.io.File tsFile, final String baseName,
                                      final com.sbplus.browser.SbDownloadManager.Task task,
                                      final android.content.Context ctx) {
        try {
            // 先试真转码(H.264+AAC 输出, 播放器 100% 兼容)
            java.io.File r = transcodeTsToMp4(tsFile, baseName, task, ctx);
            if (r != null && r.exists() && r.length() > 0) return r;
            XposedBridge.log("[SBPlus] smartConvert transcode failed/unusable, fallback remux");
            return tsToMp4(tsFile, baseName, task, ctx);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] smartConvert error: " + t);
            return tsToMp4(tsFile, baseName, task, ctx);
        }
    }

    /** 用 MediaExtractor 读 TS, MediaMuxer 封装成 MP4 (纯 remux 不重编码)。返回 mp4 文件或 null。 */
    private java.io.File tsToMp4(final java.io.File tsFile, final String baseName,
                                  final com.sbplus.browser.SbDownloadManager.Task task,
                                  final android.content.Context ctx) {
        try {
            final android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            extractor.setDataSource(tsFile.getAbsolutePath());
            final int trackCount = extractor.getTrackCount();
            android.media.MediaMuxer muxer = null;
            final java.util.List<Integer> muxerTracks = new java.util.ArrayList<Integer>();
            try {
                final java.util.List<android.media.MediaFormat> formats = new java.util.ArrayList<android.media.MediaFormat>();
                for (int i = 0; i < trackCount; i++) {
                    final android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                    formats.add(fmt);
                    String mime = "";
                    try { mime = fmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    XposedBridge.log("[SBPlus] tsToMp4 track[" + i + "] mime=" + mime);
                }
                if (formats.isEmpty()) { extractor.release(); return null; }
                final java.io.File out = new java.io.File(tsFile.getParentFile(), baseName + ".mp4");
                muxer = new android.media.MediaMuxer(out.getAbsolutePath(),
                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                for (int i = 0; i < trackCount; i++) {
                    android.media.MediaFormat fmt = formats.get(i);
                    // 音频若缺 csd-0, 尝试从首个 sample 的 ADTS 头补 AudioSpecificConfig
                    String mime = "";
                    try { mime = fmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    if (mime != null && mime.equals("audio/mp4a-latm") && !fmt.containsKey("csd-0")) {
                        byte[] cfg = decodeAacCsdFromExtractor(extractor, i);
                        if (cfg != null) {
                            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg));
                            XposedBridge.log("[SBPlus] tsToMp4 audio csd-0 via decoder (adv)");
                            // 从 csd-0 (AudioSpecificConfig) 解析真实采样率/声道并覆盖 format(HE-AAC SBR 关键)
                            try {
                                int[] srch = parseAsc(cfg);
                                if (srch != null) {
                                    fmt.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, srch[0]);
                                    fmt.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, srch[1]);
                                    XposedBridge.log("[SBPlus] tsToMp4 audio fmt synced sr=" + srch[0] + " ch=" + srch[1]);
                                }
                            } catch (Throwable ignoredAsc) {}
                        } else {
                            int[] p = sniffAacFromExtractor(extractor, i);
                            if (p != null) {
                                byte[] cfg2 = buildAudioSpecificConfig(p[0], p[1]);
                                if (cfg2 != null) {
                                    fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg2));
                                    XposedBridge.log("[SBPlus] tsToMp4 audio csd-0 set sr=" + p[0] + " ch=" + p[1]);
                                }
                            }
                        }
                    }
                    muxerTracks.add(Integer.valueOf(muxer.addTrack(fmt)));
                }
                muxer.start();
                // 大 buffer: 高码率关键帧可达数 MB, 小 buffer 截断会写坏帧导致跳帧/花屏
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 * 1024 * 1024);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                long firstPts = -1;
                long lastPts = -1;
                long videoPrevPts = -1;
                for (int ti = 0; ti < trackCount; ti++) {
                    firstPts = -1; lastPts = -1; videoPrevPts = -1;
                    // 转换进度: 按轨更新
                    if (task != null) {
                        task.detail = "转换 " + (ti + 1) + "/" + trackCount + " 轨";
                        task.partCount = (ti + 1);
                        task.partTotal = trackCount;
                        com.sbplus.browser.SbDownloadManager.post(ctx, task);
                    }
                    for (int u = 0; u < trackCount; u++) { try { extractor.unselectTrack(u); } catch (Throwable ignored) {} }
                    extractor.selectTrack(ti);
                    String mime = "";
                    try { mime = formats.get(ti).getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                    boolean isAac = mime != null && mime.equals("audio/mp4a-latm");
                    boolean isVideo = mime != null && mime.startsWith("video/");
                    int idx = muxerTracks.get(ti).intValue();
                    boolean needSeek = true;
                    while (true) {
                        int sampleSize = extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) break;
                        int off = 0, size = sampleSize;
                        if (isAac && sampleSize >= 7) {
                            // 完整跳过 ID3 标签 + ADTS 头(单帧或多帧都处理)
                            int pos = 0;
                            while (pos + 10 <= sampleSize
                                    && (buf.get(pos) & 0xFF) == 'I' && (buf.get(pos + 1) & 0xFF) == 'D' && (buf.get(pos + 2) & 0xFF) == '3') {
                                int tagSize = ((buf.get(pos + 6) & 0x7F) << 21) | ((buf.get(pos + 7) & 0x7F) << 14)
                                        | ((buf.get(pos + 8) & 0x7F) << 7) | (buf.get(pos + 9) & 0x7F);
                                pos += 10 + tagSize;
                                if (pos > sampleSize) { pos = sampleSize; break; }
                            }
                            if (pos + 7 <= sampleSize) {
                                byte b0 = buf.get(pos), b1 = buf.get(pos + 1);
                                if ((b0 & 0xFF) == 0xFF && (b1 & 0xF0) == 0xF0) {
                                    // 逐帧剥掉 ADTS 头, 帧体前移
                                    int src = pos, dst = pos;
                                    while (src + 7 <= sampleSize
                                            && (buf.get(src) & 0xFF) == 0xFF && (buf.get(src + 1) & 0xF0) == 0xF0) {
                                        int fl = ((buf.get(src + 3) & 0x03) << 11) | ((buf.get(src + 4) & 0xFF) << 3) | ((buf.get(src + 5) & 0xE0) >> 5);
                                        int h2 = ((buf.get(src + 1) >> 1) & 0x01) == 1 ? 7 : 9;
                                        if (fl < h2 || src + fl > sampleSize) break;
                                        System.arraycopy(buf.array(), src + h2, buf.array(), dst, fl - h2);
                                        dst += (fl - h2);
                                        src += fl;
                                    }
                                    if (dst > pos) {
                                        off = pos;
                                        size = dst - pos;
                                    }
                                }
                            }
                        }
                        info.offset = off;
                        info.size = size;
                        long pts0 = extractor.getSampleTime();
                        if (firstPts < 0) firstPts = pts0;
                        long pts = pts0 - firstPts;
                        // 音视频独立做时间戳归一化:
                        //  - 视频: PTS 原样(允许 B 帧回跳; 若回跳严重则匀速推进兜底)
                        //  - 音频: 防回退防 0, 保底+1000
                        if (isVideo) {
                            if (videoPrevPts >= 0 && pts + 20000 < videoPrevPts) {
                                // 严重回跳(>20ms): 说明 PTS 乱序严重, 匀速推进避免播放器跳帧
                                pts = videoPrevPts + 33333; // ~30fps 兜底
                            }
                            videoPrevPts = pts;
                            lastPts = pts;
                        } else {
                            if (pts < lastPts) pts = lastPts + 1000;
                            lastPts = pts;
                        }
                        info.presentationTimeUs = pts;
                        info.flags = (extractor.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        try {
                            muxer.writeSampleData(idx, buf, info);
                        } catch (Throwable we) {
                            if (needSeek) { needSeek = false; }
                        }
                        if (!extractor.advance()) break;
                        // 转换期间被取消: 中断并标记
                        if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                            XposedBridge.log("[SBPlus] tsToMp4 cancelled mid-convert");
                            try { out.delete(); } catch (Throwable ignored) {}
                            return null;
                        }
                    }
                }
                muxer.stop();
                muxer.release();
                muxer = null;
                if (task != null) { task.detail = "转换完成"; task.partCount = task.partTotal; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
                XposedBridge.log("[SBPlus] tsToMp4 OK -> " + out.getAbsolutePath());
                return out;
            } finally {
                try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] tsToMp4 error: " + t);
            if (task != null) { task.detail = "转换失败: " + t; task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            return null;
        }
    }

    /** 真正的转码: MediaCodec 解码任意视频/音频(HEVC/VP9/AV1/AAC...)再重编码为 H.264+AAC MP4。
     *  解决 remux 产物播放器不兼容导致的跳帧/无声音/花屏。 */
    private java.io.File transcodeTsToMp4(final java.io.File tsFile, final String baseName,
                                          final com.sbplus.browser.SbDownloadManager.Task task,
                                          final android.content.Context ctx) {
        android.media.MediaExtractor extractor = null;
        android.media.MediaMuxer muxer = null;
        android.media.MediaCodec vDec = null, vEnc = null, aDec = null, aEnc = null;
        android.view.Surface encSurface = null;
        java.io.File out = null;
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) {
                XposedBridge.log("[SBPlus] transcode requires API 23+, fallback remux");
                return tsToMp4(tsFile, baseName, task, ctx);
            }
            extractor = new android.media.MediaExtractor();
            extractor.setDataSource(tsFile.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) return null;
            android.media.MediaFormat vFmt = null, aFmt = null;
            int vTrack = -1, aTrack = -1;
            for (int i = 0; i < trackCount; i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = "";
                try { mime = f.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                if (mime != null) {
                    if (vTrack < 0 && mime.startsWith("video/")) { vFmt = f; vTrack = i; }
                    else if (aTrack < 0 && mime.startsWith("audio/")) { aFmt = f; aTrack = i; }
                }
            }
            boolean hasVideo = vTrack >= 0, hasAudio = aTrack >= 0;
            if (!hasVideo && !hasAudio) return null;
            final int[] aSrHolder = new int[]{44100, 2}; // 音频采样率/声道(方法级, 供两处使用)
            out = new java.io.File(tsFile.getParentFile(), baseName + ".mp4");
            muxer = new android.media.MediaMuxer(out.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int encVTrack = -1, encATrack = -1;
            boolean needVInfo = hasVideo, needAInfo = hasAudio;

            // ---------- 视频: 解码器 -> Surface -> H.264 编码器 ----------
            if (hasVideo) {
                String vMime = "";
                try { vMime = vFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                int vw = 0, vh = 0;
                try { vw = vFmt.getInteger(android.media.MediaFormat.KEY_WIDTH); vh = vFmt.getInteger(android.media.MediaFormat.KEY_HEIGHT); } catch (Throwable ignored) {}
                if (vMime == null || vMime.isEmpty() || vw <= 0 || vh <= 0) throw new RuntimeException("bad video fmt");
                XposedBridge.log("[SBPlus] transcode video " + vMime + " " + vw + "x" + vh);
                vDec = android.media.MediaCodec.createDecoderByType(vMime);
                // 保底: 有些封装 csd 缺失, 由解码器自己探测
                vEnc = android.media.MediaCodec.createEncoderByType("video/avc");
                int bitrate = Math.max(1200000, vw * vh * 4); // ~4Mbps@1080p, 低分辨率也保底
                android.media.MediaFormat encFmt = android.media.MediaFormat.createVideoFormat("video/avc", vw, vh);
                encFmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                encFmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
                encFmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30);
                encFmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                encFmt.setInteger(android.media.MediaFormat.KEY_BITRATE_MODE,
                        android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                encFmt.setInteger(android.media.MediaFormat.KEY_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                encFmt.setInteger(android.media.MediaFormat.KEY_LEVEL, android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42);
                vEnc.configure(encFmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                encSurface = vEnc.createInputSurface();
                vEnc.start();
                // 解码器输出 Surface 直连编码器输入
                vDec.configure(vFmt, encSurface, null, 0);
                vDec.start();
                // 编码器输出格式 -> muxer 轨
                android.media.MediaFormat vOutFmt = vEnc.getOutputFormat();
                encVTrack = muxer.addTrack(vOutFmt);
                needVInfo = false;
            }

            // ---------- 音频: 源已是 AAC, 直接 remux 复制(不重编码, 1秒完成无损不卡死) ----------
            boolean aRemux = false;
            if (hasAudio) {
                String aMime = "";
                try { aMime = aFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {}
                int sr = 0, ch = 0;
                try { sr = aFmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE); ch = aFmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT); } catch (Throwable ignored) {}
                if (sr <= 0) sr = 44100;
                if (ch <= 0) ch = 2;
                aSrHolder[0] = sr;
                aSrHolder[1] = ch;
                XposedBridge.log("[SBPlus] transcode audio " + aMime + " sr=" + sr + " ch=" + ch + " -> remux copy");
                aRemux = true;
                try {
                    android.media.MediaFormat aOutFmt = aFmt;
                    encATrack = muxer.addTrack(aOutFmt);
                    needAInfo = false;
                } catch (Throwable at) {
                    XposedBridge.log("[SBPlus] audio remux addTrack failed: " + at + " (audio will be skipped, video only)");
                    aRemux = false;
                    encATrack = -1;
                    needAInfo = false;
                }
            }
            muxer.start();

            final int TIMEOUT = 12000;
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 * 1024 * 1024);
            long totalUs = 0;
            String vMime2 = "";
            if (hasVideo) { try { vMime2 = vFmt.getString(android.media.MediaFormat.KEY_MIME); } catch (Throwable ignored) {} }
            XposedBridge.log("[SBPlus] transcode start, vTrack=" + vTrack + " aTrack=" + aTrack);

            // ---------- 视频转码主循环(解码->渲染->编码->mux) ----------
            if (hasVideo) {
                extractor.unselectTrack(vTrack);
                extractor.selectTrack(vTrack);
                int[] decIn = new int[0];
                byte[] decInBufs = null;
                boolean vEosIn = false, vEosOut = false;
                long vPts = 0;
                long vOutBase = -1, vLastOutPts = -1;
                android.media.MediaCodec.BufferInfo vInfo = new android.media.MediaCodec.BufferInfo();
                java.nio.ByteBuffer[] decOutBufs = null;
                int safety = 0;
                while (!vEosOut && safety < 400000) {
                    safety++;
                    if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                        XposedBridge.log("[SBPlus] transcode video cancelled");
                        try { out.delete(); } catch (Throwable ignored) {}
                        return null;
                    }
                    // 喂解码器输入
                    if (!vEosIn) {
                        int inIdx = vDec.dequeueInputBuffer(TIMEOUT);
                        if (inIdx >= 0) {
                            java.nio.ByteBuffer inBuf = vDec.getInputBuffer(inIdx);
                            int sz = extractor.readSampleData(inBuf, 0);
                            if (sz < 0) {
                                vDec.queueInputBuffer(inIdx, 0, 0, 0,
                                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                vEosIn = true;
                            } else {
                                long t = extractor.getSampleTime();
                                vDec.queueInputBuffer(inIdx, 0, sz, t, 0);
                                if (task != null && (task.partCount % 500 == 0)) {
                                    task.detail = "转码中 " + (t / 1000000) + "s";
                                    com.sbplus.browser.SbDownloadManager.post(ctx, task);
                                }
                                extractor.advance();
                            }
                        }
                    }
                    // 解码器输出: 渲染到编码器 Surface
                    android.media.MediaCodec.BufferInfo dInfo = new android.media.MediaCodec.BufferInfo();
                    int dOut = vDec.dequeueOutputBuffer(dInfo, 5000);
                    if (dOut >= 0) {
                        boolean render = dInfo.size > 0;
                        vDec.releaseOutputBuffer(dOut, render);
                        if ((dInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vEosOut = true;
                    } else if (dOut == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore
                    }
                    // 编码器输出 -> muxer
                    int eOut = vEnc.dequeueOutputBuffer(vInfo, 5000);
                    if (eOut >= 0) {
                        java.nio.ByteBuffer eBuf = vEnc.getOutputBuffer(eOut);
                        if (vInfo.size > 0 && eBuf != null) {
                            // PTS 归一化到 0 起点 + 强制单调(源 PTS 乱序/大数会导致播放器跳帧)
                            long p = vInfo.presentationTimeUs;
                            if (vOutBase < 0) vOutBase = p;
                            long np = p - vOutBase;
                            if (np < 0) np = 0;
                            if (vLastOutPts >= 0 && np <= vLastOutPts) np = vLastOutPts + 33333; // ~30fps 兜底顺延
                            vLastOutPts = np;
                            vInfo.presentationTimeUs = np;
                            eBuf.position(vInfo.offset);
                            eBuf.limit(vInfo.offset + vInfo.size);
                            try {
                                totalUs = np;
                                muxer.writeSampleData(encVTrack, eBuf, vInfo);
                            } catch (Throwable we) {
                                XposedBridge.log("[SBPlus] transcode v write err: " + we);
                            }
                        }
                        vEnc.releaseOutputBuffer(eOut, false);
                        if ((vInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vEosOut = true;
                        if (task != null && (task.partCount % 500 == 0)) {
                            task.detail = "转码 " + (vInfo.presentationTimeUs / 1000000) + "s";
                            com.sbplus.browser.SbDownloadManager.post(ctx, task);
                        }
                    } else if (eOut == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore
                    }
                }
                // 关闭视频解码器/编码器
                try { vDec.stop(); vDec.release(); vDec = null; } catch (Throwable ignored) {}
                try { vEnc.stop(); vEnc.release(); vEnc = null; } catch (Throwable ignored) {}
                encSurface = null;
                XposedBridge.log("[SBPlus] transcode video done, lastPts=" + totalUs);
            }

            // ---------- 音频转码主循环(解码->桥接->编码->mux) ----------
            // ---------- 音频: 源已是 AAC, 直接 remux 复制到 MP4(每秒千帧级, 不卡死无损) ----------
            if (hasAudio) {
                extractor.unselectTrack(aTrack);
                extractor.selectTrack(aTrack);
                java.nio.ByteBuffer aBuf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024);
                android.media.MediaCodec.BufferInfo aInfo = new android.media.MediaCodec.BufferInfo();
                long aBaseUs = -1, aLastPts = -1;
                int aSafety = 0;
                boolean aEos = false;
                while (!aEos && aSafety < 3000000) {
                    aSafety++;
                    if (task != null && com.sbplus.browser.SbDownloadManager.isCancelled(task.id)) {
                        XposedBridge.log("[SBPlus] transcode audio cancelled");
                        try { out.delete(); } catch (Throwable ignored) {}
                        return null;
                    }
                    aBuf.clear();
                    int sz = extractor.readSampleData(aBuf, 0);
                    if (sz < 0) {
                        aEos = true;
                        break;
                    }
                    long t = extractor.getSampleTime();
                    if (aBaseUs < 0) aBaseUs = t;
                    long np = t - aBaseUs;
                    if (np < 0) np = 0;
                    if (aLastPts >= 0 && np <= aLastPts) np = aLastPts + 1000; // 单调兜底
                    aLastPts = np;
                    aInfo.offset = 0;
                    aInfo.size = sz;
                    aInfo.presentationTimeUs = np;
                    aInfo.flags = 0;
                    aBuf.position(0);
                    aBuf.limit(sz);
                    try {
                        muxer.writeSampleData(encATrack, aBuf, aInfo);
                    } catch (Throwable we) {
                        XposedBridge.log("[SBPlus] audio copy write err: " + we);
                    }
                    extractor.advance();
                    if (task != null && (aSafety % 5000 == 0)) {
                        task.detail = "音频 " + (np / 1000000) + "s";
                        com.sbplus.browser.SbDownloadManager.post(ctx, task);
                    }
                }
                XposedBridge.log("[SBPlus] transcode audio done (remux copy, samples=" + aSafety + ")");
            }

            if (needVInfo || needAInfo) throw new RuntimeException("codec output format missing");
            muxer.stop();
            muxer.release();
            muxer = null;
            if (task != null) { task.detail = "转换完成"; task.partCount = task.partTotal; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            XposedBridge.log("[SBPlus] transcode OK -> " + out.getAbsolutePath() + " dur=" + (totalUs / 1000000) + "s");
            return out;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] transcodeTsToMp4 error: " + t);
            try { if (out != null) out.delete(); } catch (Throwable ignored) {}
            if (task != null) { task.detail = "转换失败: " + t; task.status = com.sbplus.browser.SbDownloadManager.STATUS_FAILED; com.sbplus.browser.SbDownloadManager.post(ctx, task); }
            return null;
        } finally {
            try { if (aDec != null) aDec.release(); } catch (Throwable ignored) {}
            try { if (aEnc != null) aEnc.release(); } catch (Throwable ignored) {}
            try { if (vDec != null) vDec.release(); } catch (Throwable ignored) {}
            try { if (vEnc != null) vEnc.release(); } catch (Throwable ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
        }
    }

    /** 解析 AudioSpecificConfig: 返回 [采样率, 声道数], 失败 null。 */
    private static int[] parseAsc(byte[] asc) {
        try {
            if (asc == null || asc.length < 2) return null;
            int b0 = asc[0] & 0xFF, b1 = asc[1] & 0xFF;
            int sfIdx = ((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01);
            int chCfg = (b1 >> 3) & 0x0F;
            int[] srt = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                         16000, 12000, 11025, 8000, 7350};
            if (sfIdx >= 0 && sfIdx < srt.length && chCfg >= 1 && chCfg <= 7) {
                return new int[]{srt[sfIdx], chCfg};
            }
            // SBR/PS: 前 5 bit 是 audioObjectType=5(HE-AAC), 后跟 samplingFrequencyIndex 在更高位
            // 常见 HE-AAC: 0x2B 0x92... 直接尝试从第 2 字节解析
            if (sfIdx > 12 || chCfg < 1 || chCfg > 7) {
                int ext = ((asc[2] & 0xF8) >> 3) & 0x1F; // 简化: 尝试
                if (ext > 0 && ext < srt.length) return new int[]{srt[ext], chCfg > 0 && chCfg <= 7 ? chCfg : 2};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 解码 AAC 首帧拿真实 csd-0(正确处理 HE-AAC/SBR 采样率), 失败返回 null。
     *  原理: 用系统 MediaCodec 解码第一帧, 从输出 format 的 csd-0 得到播放器认的 ASC。 */
    private byte[] decodeAacCsdFromExtractor(android.media.MediaExtractor ext, int track) {
        try {
            int tcnt = ext.getTrackCount();
            for (int u = 0; u < tcnt; u++) { try { ext.unselectTrack(u); } catch (Throwable ignored) {} }
            ext.selectTrack(track);
            java.nio.ByteBuffer fb = java.nio.ByteBuffer.allocate(4096);
            int n = ext.readSampleData(fb, 0);
            if (n < 7) return null;
            byte[] raw = new byte[n];
            fb.position(0);
            fb.get(raw);
            // 剥 ADTS 头
            byte b0 = raw[0], b1 = raw[1];
            if ((b0 & 0xFF) == 0xFF && (b1 & 0xF0) == 0xF0) {
                int protectionAbsent = (b1 >> 1) & 0x01;
                int hdr = protectionAbsent == 1 ? 7 : 9;
                byte[] payload = new byte[n - hdr];
                System.arraycopy(raw, hdr, payload, 0, n - hdr);
                android.media.MediaCodec codec = null;
                try {
                    android.media.MediaFormat inFmt = new android.media.MediaFormat();
                    inFmt.setString(android.media.MediaFormat.KEY_MIME, "audio/mp4a-latm");
                    inFmt.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, 44100);
                    inFmt.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, 2);
                    codec = android.media.MediaCodec.createDecoderByType("audio/mp4a-latm");
                    codec.configure(inFmt, null, null, 0);
                    codec.start();
                    int inIdx = codec.dequeueInputBuffer(1000000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        inBuf.put(payload);
                        long pts = ext.getSampleTime();
                        codec.queueInputBuffer(inIdx, 0, payload.length, pts, 0);
                    }
                    android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
                    int outIdx = codec.dequeueOutputBuffer(bi, 2000000);
                    if (outIdx >= 0) {
                        android.media.MediaFormat outFmt = codec.getOutputFormat();
                        java.nio.ByteBuffer csd = outFmt.getByteBuffer("csd-0");
                        if (csd != null) {
                            byte[] out = new byte[csd.remaining()];
                            csd.get(out);
                            return out;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] decodeAacCsd error: " + t);
                } finally {
                    try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
                    try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] decodeAacCsdFromExtractor error: " + t);
        }
        return null;
    }

    /** 从 extractor 选中 track 的首个 sample 读 ADTS 头, 返回 {采样率, 声道}. */
    private int[] sniffAacFromExtractor(android.media.MediaExtractor ext, int track) {
        try {
            int save = -1;
            try { save = ext.getSampleTrackIndex(); } catch (Throwable ignored) {}
            for (int u = 0; u < ext.getTrackCount(); u++) { try { ext.unselectTrack(u); } catch (Throwable ignored) {} }
            ext.selectTrack(track);
            java.nio.ByteBuffer hb = java.nio.ByteBuffer.allocate(64);
            int n = ext.readSampleData(hb, 0);
            if (n < 7) return null;
            byte b0 = hb.get(0), b1 = hb.get(1);
            if ((b0 & 0xFF) != 0xFF || (b1 & 0xF0) != 0xF0) return null;
            int sfIdx = (b1 >> 2) & 0x0F;
            int chan = ((b1 & 0x01) << 2) | ((hb.get(2) >> 6) & 0x03);
            int[] srTab = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
            int sr = (sfIdx >= 0 && sfIdx < srTab.length) ? srTab[sfIdx] : -1;
            return new int[]{sr, chan};
        } catch (Throwable t) { return null; }
    }

    private byte[] buildAudioSpecificConfig(int sr, int ch) {
        int sfIdx;
        switch (sr) {
            case 96000: sfIdx = 0; break;
            case 88200: sfIdx = 1; break;
            case 64000: sfIdx = 2; break;
            case 48000: sfIdx = 3; break;
            case 44100: sfIdx = 4; break;
            case 32000: sfIdx = 5; break;
            case 24000: sfIdx = 6; break;
            case 22050: sfIdx = 7; break;
            case 16000: sfIdx = 8; break;
            case 12000: sfIdx = 9; break;
            case 11025: sfIdx = 10; break;
            case 8000:  sfIdx = 11; break;
            default: sfIdx = -1;
        }
        if (sfIdx < 0 || ch < 1 || ch > 8) return null;
        int asc = (2 << 11) | (sfIdx << 7) | (ch << 3);
        return new byte[] { (byte)((asc >> 8) & 0xFF), (byte)(asc & 0xFF) };
    }


private boolean showMediaDialog(String json) {
        try {
            XposedBridge.log("[SBPlus] DIALOG enter len=" + (json == null ? -1 : json.length()));
            final java.util.List<String> urls = new java.util.ArrayList<String>();
            final java.util.List<String> titles = new java.util.ArrayList<String>();
            final java.util.List<String> types = new java.util.ArrayList<String>();
            final java.util.List<String> vSite = new java.util.ArrayList<String>();
            final java.util.List<Integer> vW = new java.util.ArrayList<Integer>();
            final java.util.List<Integer> vH = new java.util.ArrayList<Integer>();
            final java.util.List<Double> vDur = new java.util.ArrayList<Double>();
            int videoCount = 0, audioCount = 0, imageCount = 0;
            // 合并网络层嗅探的 URL
            mergeNetworkSniffedUrls(urls, types, titles, vSite);
            try {
                String s = json;
                try { s = s.replace("\\\"", "\""); } catch (Throwable ignored) {}
                int bp = 0;
                while (true) {
                    int oi = s.indexOf("\"url\"", bp);
                    if (oi < 0) break;
                    int c1 = s.indexOf(':', oi);
                    if (c1 < 0) break;
                    int q1 = s.indexOf('"', c1 + 1);
                    if (q1 < 0) break;
                    int q2 = q1 + 1;
                    while (q2 < s.length()) {
                        if (s.charAt(q2) == '"' && s.charAt(q2 - 1) != '\\') break;
                        q2++;
                    }
                    String u = s.substring(q1 + 1, q2);
                    int t1 = s.indexOf("\"type\"", q2);
                    int c2 = t1 > 0 ? s.indexOf(':', t1) : -1;
                    String tp = "";
                    if (c2 > 0) {
                        int r1 = s.indexOf('"', c2 + 1);
                        if (r1 > 0) { int r2 = r1 + 1; while (r2 < s.length()) { if (s.charAt(r2) == '"' && s.charAt(r2 - 1) != '\\') break; r2++; } tp = s.substring(r1 + 1, r2); }
                    }
                    int ti1 = s.indexOf("\"title\"", t1 > 0 ? t1 : q2);
                    int c3 = ti1 > 0 ? s.indexOf(':', ti1) : -1;
                    String ti = "";
                    if (c3 > 0) {
                        int rr1 = s.indexOf('"', c3 + 1);
                        if (rr1 > 0) { int rr2 = rr1 + 1; while (rr2 < s.length()) { if (s.charAt(rr2) == '"' && s.charAt(rr2 - 1) != '\\') break; rr2++; } ti = s.substring(rr1 + 1, rr2); }
                    }
                    // 站点标记(嗅探 JS 的 site 字段)
                    String site = "";
                    { int s1 = s.indexOf("\"site\"", q2); if (s1 > 0) { int cs = s.indexOf(':', s1); if (cs > 0) { int d1 = s.indexOf(',', cs); int d2 = s.indexOf('}', cs); int de = d1 > 0 ? Math.min(d1, d2) : d2; if (de > cs) { site = s.substring(cs + 1, de).trim(); if (site.startsWith("\"") && site.length() >= 2) site = site.substring(1, site.length() - 1); } } } }
                    if (!u.isEmpty()) {
                        urls.add(u); types.add(tp); titles.add(ti);
                        vSite.add(site);
                        int w = 0, h = 0; double du = 0;
                        int w1 = s.indexOf("\"w\"", q2);
                        if (w1 > 0) { int cw = s.indexOf(':', w1); if (cw > 0) { int d1 = s.indexOf(',', cw); int d2 = s.indexOf('}', cw); int de = d1 > 0 ? Math.min(d1, d2) : d2; if (de > cw) { try { w = Integer.parseInt(s.substring(cw + 1, de).trim()); } catch (Throwable ignored) {} } } }
                        int h1 = s.indexOf("\"h\"", q2);
                        if (h1 > 0) { int ch = s.indexOf(':', h1); if (ch > 0) { int d1 = s.indexOf(',', ch); int d2 = s.indexOf('}', ch); int de = d1 > 0 ? Math.min(d1, d2) : d2; if (de > ch) { try { h = Integer.parseInt(s.substring(ch + 1, de).trim()); } catch (Throwable ignored) {} } } }
                        int du1 = s.indexOf("\"dur\"", q2);
                        if (du1 > 0) { int cd = s.indexOf(':', du1); if (cd > 0) { int d1 = s.indexOf(',', cd); int d2 = s.indexOf('}', cd); int de = d1 > 0 ? Math.min(d1, d2) : d2; if (de > cd) { try { du = Double.parseDouble(s.substring(cd + 1, de).trim()); } catch (Throwable ignored) {} } } }
                        vW.add(w); vH.add(h); vDur.add(du);
                        if ("audio".equals(tp)) audioCount++;
                        else if ("image".equals(tp)) imageCount++;
                        else videoCount++;
                    }
                    bp = q2 + 1;
                }
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] DIALOG parse error: " + t);
            }
            XposedBridge.log("[SBPlus] DIALOG urls=" + urls.size() + " (manual parse)");
            if (urls.isEmpty()) {
                toastShort(T("没有发现可下载的资源", "No downloadable resources found on this page"));
                return true;
            }
            final int n = urls.size();
            final boolean[] checked = new boolean[n];
            for (int i = 0; i < n; i++) checked[i] = false;  // 默认不全选,用户手动全选
            android.app.Activity act0 = sSniffActivity != null ? sSniffActivity
                    : (sCurrentActivity != null ? sCurrentActivity
                    : (sAppContext instanceof android.app.Activity ? (android.app.Activity) sAppContext : null));
            XposedBridge.log("[SBPlus] showMediaDialog act=" + (act0 != null ? act0.getClass().getName() : "NULL") + " n=" + n);
            if (act0 == null) {
                try {
                    if (sCurrentRealTab != null) {
                        Object vw = XposedHelpers.callMethod(sCurrentRealTab, "getView");
                        if (vw instanceof android.view.View) act0 = resolveActivityFromView((android.view.View) vw);
                    }
                } catch (Throwable ignored) {}
                if (act0 == null) return false;
            }
            final android.app.Activity act = act0;
            final String typeSummary = T("图片 ", "Images ") + imageCount + T(" / 音频 ", " / Audio ") + audioCount
                    + T(" / 视频 ", " / Video ") + videoCount;
            final String title = T("资源嗅探", "Media Sniffer");

            // ============ 自定义 View 对话框 ============
            final android.widget.LinearLayout root = new android.widget.LinearLayout(act);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            final int pad = (int)(14 * act.getResources().getDisplayMetrics().density);
            root.setPadding(pad, (int)(6*act.getResources().getDisplayMetrics().density), pad, 0);

            // 标题行: 左=资源嗅探, 中=已选数量(居中), 右=下载列表入口(靠右)
            final android.widget.LinearLayout headerRow = new android.widget.LinearLayout(act);
            headerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerRow.setPadding(0, dp(act,10), 0, dp(act,4));
            final android.widget.TextView tvHeaderLeft = new android.widget.TextView(act);
            tvHeaderLeft.setText(title);
            tvHeaderLeft.setTextSize(17);
            tvHeaderLeft.setTypeface(tvHeaderLeft.getTypeface(), android.graphics.Typeface.BOLD);
            // 已选数量状态条(居中, 每次勾选/取消/全选时刷新)
            final android.widget.TextView tvSelCount = new android.widget.TextView(act);
            tvSelCount.setTextColor(0xFF1E88E5);
            tvSelCount.setTextSize(13);
            tvSelCount.setGravity(android.view.Gravity.CENTER);
            // 下载列表入口(靠右)
            final android.widget.TextView tvDlEntry = new android.widget.TextView(act);
            tvDlEntry.setText(T("下载列表", "DL list"));
            tvDlEntry.setTextSize(14);
            tvDlEntry.setTextColor(0xFF1E88E5);
            tvDlEntry.setPadding(0, dp(act,4), dp(act,8), dp(act,4));
            tvDlEntry.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            tvDlEntry.setClickable(true);
            tvDlEntry.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        showDownloadList();
                    } catch (Throwable t) { XposedBridge.log("[SBPlus] 嗅探弹窗下载列表 click err: " + t); }
                }
            });
            // 三段等宽权重,各自控制对齐:左/中(居中)/右
            android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            hlp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            headerRow.addView(tvHeaderLeft, hlp);
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            clp.gravity = android.view.Gravity.CENTER;
            headerRow.addView(tvSelCount, clp);
            android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            dlp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            headerRow.addView(tvDlEntry, dlp);
            final Runnable updateSelCount = new Runnable() { @Override public void run() {
                try {
                    int cc = 0; for (int i = 0; i < n; i++) if (checked[i]) cc++;
                    tvSelCount.setText(T("已选 ", "Selected ") + cc + " / " + n + T(" 个文件", " files"));
                } catch (Throwable ignored) {}
            }};
            updateSelCount.run();
            root.addView(headerRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
            // Tab 行
            final android.widget.LinearLayout tabRow = new android.widget.LinearLayout(act);
            tabRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            final android.widget.TextView tvTab1 = new android.widget.TextView(act);
            final android.widget.TextView tvTab2 = new android.widget.TextView(act);
            final android.widget.TextView tvTab3 = new android.widget.TextView(act);
            tvTab1.setText(T("▶ 视频", "Video") + " (" + videoCount + ")");
            tvTab2.setText(T("♪ 音频", "Audio") + " (" + audioCount + ")");
            tvTab3.setText(T("🖼 图片", "Image") + " (" + imageCount + ")");
            tvTab1.setTextSize(15); tvTab2.setTextSize(15); tvTab3.setTextSize(15);
            tvTab1.setPadding(pad, 10, pad, 10); tvTab2.setPadding(pad, 10, pad, 10); tvTab3.setPadding(pad, 10, pad, 10);
            tvTab1.setGravity(android.view.Gravity.CENTER); tvTab2.setGravity(android.view.Gravity.CENTER); tvTab3.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams t1p = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            android.widget.LinearLayout.LayoutParams t2p = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            android.widget.LinearLayout.LayoutParams t3p = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tabRow.addView(tvTab1, t1p); tabRow.addView(tvTab2, t2p); tabRow.addView(tvTab3, t3p);
            root.addView(tabRow, new android.widget.LinearLayout.LayoutParams(-1, -2));



            // 内容容器(用 FrameLayout 承载可见页)
            final android.widget.FrameLayout content = new android.widget.FrameLayout(act);
            final int contentH = (int)(380 * act.getResources().getDisplayMetrics().density);
            root.addView(content, new android.widget.LinearLayout.LayoutParams(-1, contentH));

            // 三个页面(图片 GridView / 音视频 ListView)
            final android.widget.GridView gridImages = new android.widget.GridView(act);
            gridImages.setNumColumns(3);
            gridImages.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
            gridImages.setVerticalSpacing(6); gridImages.setHorizontalSpacing(6);
            gridImages.setPadding(4, 4, 4, 4);
            final android.widget.ListView listVideo = new android.widget.ListView(act);
            final android.widget.ListView listAudio = new android.widget.ListView(act);
            content.addView(gridImages, new android.widget.FrameLayout.LayoutParams(-1, -1));
            content.addView(listVideo, new android.widget.FrameLayout.LayoutParams(-1, -1));
            content.addView(listAudio, new android.widget.FrameLayout.LayoutParams(-1, -1));

            // 按类型分组索引
            final java.util.List<Integer> imgIdx = new java.util.ArrayList<Integer>();
            final java.util.List<Integer> vidIdx = new java.util.ArrayList<Integer>();
            final java.util.List<Integer> audIdx = new java.util.ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                String t = types.get(i);
                if ("image".equals(t)) imgIdx.add(i);
                else if ("audio".equals(t)) audIdx.add(i);
                else vidIdx.add(i);
            }

            // ===== GridView 适配器:图片缩略图 + WxH + 大小 =====
            android.widget.BaseAdapter gridAdp = new android.widget.BaseAdapter() {
                @Override public int getCount() { return imgIdx.size(); }
                @Override public Object getItem(int p) { return imgIdx.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public android.view.View getView(final int p, android.view.View cv, android.view.ViewGroup parent) {
                    final int realIdx = imgIdx.get(p);
                    android.widget.FrameLayout cell;
                    if (cv instanceof android.widget.FrameLayout) {
                        cell = (android.widget.FrameLayout) cv;
                    } else {
                        cell = new android.widget.FrameLayout(act);
                        cell.setPadding(2,2,2,2);
                    }
                    cell.removeAllViews();
                    // ⚠️ 关键修复:在添加子view之前就设置cell可点击(解决第一列/第三列难点问题)
                    cell.setClickable(true);
                    cell.setFocusable(true);
                    // 缩略图
                    final android.widget.ImageView iv = new android.widget.ImageView(act);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    iv.setBackgroundColor(0xFF222222);
                    final String u = urls.get(realIdx);
                    iv.setTag("loading");
                    // 固定格子高度(防不同尺寸遮住/错位)
                    final int cellH = (int)(150 * act.getResources().getDisplayMetrics().density);
                    cell.setLayoutParams(new android.widget.GridView.LayoutParams(-1, cellH));
                    // 背景线程下载缩略图
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                byte[] bytes = httpGetBytes(u);
                                if (bytes == null || bytes.length == 0) return;
                                final android.graphics.Bitmap bmp = decodeSampledBitmap(bytes, 200, 200);
                                if (bmp == null) return;
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(new Runnable() { @Override public void run() {
                                    try { iv.setImageBitmap(bmp); } catch (Throwable ignored) {}
                                }});
                            } catch (Throwable ignored) {}
                        }
                    }).start();
                    cell.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    // 选中遮罩层:半透明蓝覆盖在缩略图上,让选中状态一眼可见
                    final android.view.View selOverlay = new android.view.View(act);
                    selOverlay.setBackgroundColor(0x661E88E5);
                    selOverlay.setClickable(false);
                    selOverlay.setFocusable(false);  // 禁用点击,避免拦截 cell.onClick
                    selOverlay.setFocusable(false);
                    selOverlay.setVisibility(checked[realIdx] ? android.view.View.VISIBLE : android.view.View.GONE);
                    cell.addView(selOverlay, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    // 底部信息条:WxH + 大小 + 格式
                    final android.widget.TextView info = new android.widget.TextView(act);
                    info.setTextColor(0xFFFFFFFF);
                    info.setTextSize(10);
                    info.setBackgroundColor(0x88000000);
                    info.setPadding(4,2,4,2);
                    final String dim = parseDim(urls.get(realIdx));
                    final String ext = parseExt(urls.get(realIdx), types.get(realIdx));
                    String sizeStr = "?";
                    info.setText(dim + "  " + sizeStr + ext);
                    android.widget.FrameLayout.LayoutParams infop = new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM);
                    cell.addView(info, infop);
                    // HEAD 请求大小(后台)
                    final String fu = urls.get(realIdx);
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final String sz = httpHeadSize(fu);
                                if (sz == null) return;
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(new Runnable() { @Override public void run() {
                                    try { info.setText(dim + "  " + sz + ext); } catch (Throwable ignored) {}
                                }});
                            } catch (Throwable ignored) {}
                        }
                    }).start();
                    // 选中状态:点击切换 (蓝色边框 + 右上角勾)
                    final android.widget.FrameLayout fcell = cell;
                    final android.widget.TextView chkBadge = new android.widget.TextView(act);
                    chkBadge.setText("✓");
                    chkBadge.setTextSize(18);
                    chkBadge.setTextColor(0xFFFFFFFF);
                    chkBadge.setGravity(android.view.Gravity.CENTER);
                    chkBadge.setBackgroundColor(0xFF1E88E5);
                    chkBadge.setClickable(false);
                    chkBadge.setFocusable(false);  // 禁用点击,避免拦截 cell.onClick
                    chkBadge.setFocusable(false);
                    chkBadge.setVisibility(checked[realIdx] ? android.view.View.VISIBLE : android.view.View.GONE);
                    android.widget.FrameLayout.LayoutParams chkP = new android.widget.FrameLayout.LayoutParams(dp(act,20), dp(act,20), android.view.Gravity.TOP | android.view.Gravity.RIGHT);
                    chkP.setMargins(0, dp(act,2), dp(act,2), 0);
                    cell.addView(chkBadge, chkP);
                    chkBadge.bringToFront();
                    final Runnable refreshCell = new Runnable() { @Override public void run() {
                        try {
                            fcell.setBackgroundColor(checked[realIdx] ? 0xFF1565C0 : 0x22000000);
                            fcell.setPadding(dp(act,3), dp(act,3), dp(act,3), dp(act,3));
                            selOverlay.setVisibility(checked[realIdx] ? android.view.View.VISIBLE : android.view.View.GONE);
                            chkBadge.setVisibility(checked[realIdx] ? android.view.View.VISIBLE : android.view.View.GONE);
                            updateSelCount.run();
                        } catch (Throwable ignored) {}
                    }};
                    // 禁用所有子view的触摸拦截(解决第一列难以点击的问题)
                    iv.setClickable(false);
                    iv.setFocusable(false);
                    selOverlay.setClickable(false);
                    selOverlay.setFocusable(false);
                    chkBadge.setClickable(false);
                    chkBadge.setFocusable(false);
                    // 强制拦截触摸事件(解决子view在某些列阻断点击的问题)

                    cell.setOnTouchListener(new android.view.View.OnTouchListener() {

                        @Override public boolean onTouch(android.view.View v, android.view.MotionEvent event) {

                            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {

                                v.performClick();

                            }

                            return true; // 消费事件,不让子view拦截

                        }

                    });

                    cell.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            checked[realIdx] = !checked[realIdx];
                            refreshCell.run();
                        }
                    });
                    refreshCell.run();
                    return cell;
                }
            };
            gridImages.setAdapter(gridAdp);

            // ===== ListView 适配器(视频/音频) =====
            final android.widget.BaseAdapter adpVideo = new android.widget.BaseAdapter() {
                @Override public int getCount() { return vidIdx.size(); }
                @Override public Object getItem(int p) { return vidIdx.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public android.view.View getView(final int p, android.view.View cv, android.view.ViewGroup parent) {
                    final int realIdx = vidIdx.get(p);
                    android.widget.LinearLayout row;
                    if (cv instanceof android.widget.LinearLayout) {
                        row = (android.widget.LinearLayout) cv;
                    } else {
                        row = new android.widget.LinearLayout(act);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        row.setPadding(8, 8, 8, 8);
                    }
                    row.removeAllViews();
                    final android.widget.CheckBox cb = new android.widget.CheckBox(act);
                    cb.setChecked(checked[realIdx]);
                    // 禁用 CheckBox 自身点击,统一由行 onClick 处理勾选,避免双重翻转导致勾不上
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb, new android.widget.LinearLayout.LayoutParams(-2, -2));

                    // 缩略图占位(视频封面通常拿不到,用 🎬 图标 + 深色底代表)
                    final android.widget.TextView thumb = new android.widget.TextView(act);
                    thumb.setText("\uD83C\uDFAC");
                    thumb.setTextSize(26);
                    thumb.setGravity(android.view.Gravity.CENTER);
                    thumb.setBackgroundColor(0xFFEEEEEE);
                    int th = (int)(64 * act.getResources().getDisplayMetrics().density);
                    int tw = (int)(96 * act.getResources().getDisplayMetrics().density);
                    row.addView(thumb, new android.widget.LinearLayout.LayoutParams(tw, th));

                    // 标题 + 详情
                    android.widget.LinearLayout col = new android.widget.LinearLayout(act);
                    col.setOrientation(android.widget.LinearLayout.VERTICAL);
                    col.setPadding(8, 0, 0, 0);
                    final String q = videoQuality(urls.get(realIdx), vW.get(realIdx), vH.get(realIdx));
                    final String ext = parseExt(urls.get(realIdx), types.get(realIdx));
                    final String dur = fmtDuration(vDur.get(realIdx));
                    String ti = titles.get(realIdx);
                    String siteTag = (realIdx < vSite.size() && !vSite.get(realIdx).isEmpty()) ? "[" + vSite.get(realIdx) + "] " : "";
                    String titleLine = siteTag + (ti == null || ti.isEmpty() ? fileNameFromUrl(urls.get(realIdx)) : ti);
                    android.widget.TextView tx1 = new android.widget.TextView(act);
                    tx1.setText(titleLine);
                    tx1.setTextSize(14);
                    tx1.setMaxLines(1);
                    tx1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tx1.setTextColor(0xFF111111);
                    col.addView(tx1, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    final android.widget.TextView tx2 = new android.widget.TextView(act);
                    tx2.setTextSize(11);
                    tx2.setTextColor(0xFF777777);
                    tx2.setText((q.length() > 0 ? q + "  " : "") + ext + "  " + T("时长 ", "Dur ") + dur);
                    col.addView(tx2, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    android.widget.TextView tx3 = new android.widget.TextView(act);
                    tx3.setText(urls.get(realIdx));
                    tx3.setTextSize(10);
                    tx3.setMaxLines(1);
                    tx3.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tx3.setTextColor(0xFF999999);
                    col.addView(tx3, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    // 大小(后台 HEAD)
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final String sz = httpHeadSize(urls.get(realIdx));
                                if (sz == null) return;
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(new Runnable() { @Override public void run() {
                                    try { tx2.setText((q.length() > 0 ? q + "  " : "") + ext + "  " + sz + "  " + T("时长 ", "Dur ") + dur); } catch (Throwable ignored) {}
                                }});
                            } catch (Throwable ignored) {}
                        }
                    }).start();
                    row.addView(col, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

                    // 整行点击切换选中 (选中浅蓝背景 + 勾框)
                    row.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            checked[realIdx] = !checked[realIdx]; cb.setChecked(checked[realIdx]);
                            row.setBackgroundColor(checked[realIdx] ? 0x331E88E5 : 0x00000000);
                            updateSelCount.run();
                        }
                    });
                    row.setBackgroundColor(checked[realIdx] ? 0x331E88E5 : 0x00000000);
                    return row;
                }
            };
            final android.widget.BaseAdapter adpAudio = new android.widget.BaseAdapter() {
                @Override public int getCount() { return audIdx.size(); }
                @Override public Object getItem(int p) { return audIdx.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public android.view.View getView(final int p, android.view.View cv, android.view.ViewGroup parent) {
                    final int realIdx = audIdx.get(p);
                    android.widget.LinearLayout row;
                    if (cv instanceof android.widget.LinearLayout) {
                        row = (android.widget.LinearLayout) cv;
                    } else {
                        row = new android.widget.LinearLayout(act);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        row.setPadding(8, 8, 8, 8);
                    }
                    row.removeAllViews();
                    final android.widget.CheckBox cb = new android.widget.CheckBox(act);
                    cb.setChecked(checked[realIdx]);
                    // 禁用 CheckBox 自身点击,统一由行 onClick 处理勾选,避免双重翻转导致勾不上
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb, new android.widget.LinearLayout.LayoutParams(-2, -2));
                    // ♪ 图标(不下载缩略图)
                    final android.widget.TextView icon = new android.widget.TextView(act);
                    icon.setText("\u266A");
                    icon.setTextSize(26);
                    icon.setGravity(android.view.Gravity.CENTER);
                    icon.setBackgroundColor(0xFFF5F5F5);
                    int th2 = (int)(48 * act.getResources().getDisplayMetrics().density);
                    int tw2 = (int)(48 * act.getResources().getDisplayMetrics().density);
                    row.addView(icon, new android.widget.LinearLayout.LayoutParams(tw2, th2));
                    // 标题 + 详情
                    android.widget.LinearLayout col = new android.widget.LinearLayout(act);
                    col.setOrientation(android.widget.LinearLayout.VERTICAL);
                    col.setPadding(8, 0, 0, 0);
                    final String ext = parseExt(urls.get(realIdx), types.get(realIdx));
                    final String dur = fmtDuration(vDur.get(realIdx));
                    String ti = titles.get(realIdx);
                    String siteTag = (realIdx < vSite.size() && !vSite.get(realIdx).isEmpty()) ? "[" + vSite.get(realIdx) + "] " : "";
                    String titleLine = siteTag + (ti == null || ti.isEmpty() ? fileNameFromUrl(urls.get(realIdx)) : ti);
                    android.widget.TextView tx1 = new android.widget.TextView(act);
                    tx1.setText(titleLine);
                    tx1.setTextSize(14);
                    tx1.setMaxLines(1);
                    tx1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tx1.setTextColor(0xFF111111);
                    col.addView(tx1, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    final android.widget.TextView tx2 = new android.widget.TextView(act);
                    tx2.setTextSize(11);
                    tx2.setTextColor(0xFF777777);
                    tx2.setText(ext + "  " + T("时长 ", "Dur ") + dur);
                    col.addView(tx2, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    android.widget.TextView tx3 = new android.widget.TextView(act);
                    tx3.setText(urls.get(realIdx));
                    tx3.setTextSize(10);
                    tx3.setMaxLines(1);
                    tx3.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tx3.setTextColor(0xFF999999);
                    col.addView(tx3, new android.widget.LinearLayout.LayoutParams(-1, -2));
                    // 大小(后台 HEAD)
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final String sz = httpHeadSize(urls.get(realIdx));
                                if (sz == null) return;
                                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                                h.post(new Runnable() { @Override public void run() {
                                    try { tx2.setText(ext + "  " + sz + "  " + T("时长 ", "Dur ") + dur); } catch (Throwable ignored) {}
                                }});
                            } catch (Throwable ignored) {}
                        }
                    }).start();
                    row.addView(col, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
                    // 整行点击切换选中 (选中浅蓝背景 + 勾框)
                    row.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            checked[realIdx] = !checked[realIdx]; cb.setChecked(checked[realIdx]);
                            row.setBackgroundColor(checked[realIdx] ? 0x331E88E5 : 0x00000000);
                            updateSelCount.run();
                        }
                    });
                    row.setBackgroundColor(checked[realIdx] ? 0x331E88E5 : 0x00000000);
                    return row;
                }
            };
            listVideo.setAdapter(adpVideo);
            listAudio.setAdapter(adpAudio);

            // Tab 切换
            final Runnable[] showPage = new Runnable[1];
            showPage[0] = new Runnable() {
                @Override public void run() {
                    // 占位,下面重新赋值
                }
            };
            final int[] curTab = new int[]{0};
            showPage[0] = new Runnable() {
                @Override public void run() {
                    try {
                        gridImages.setVisibility(curTab[0]==2 ? android.view.View.VISIBLE : android.view.View.GONE);
                        listVideo.setVisibility(curTab[0]==0 ? android.view.View.VISIBLE : android.view.View.GONE);
                        listAudio.setVisibility(curTab[0]==1 ? android.view.View.VISIBLE : android.view.View.GONE);
                        tvTab1.setTextColor(curTab[0]==0 ? 0xFF1E88E5 : 0xFF666666);
                        tvTab2.setTextColor(curTab[0]==1 ? 0xFF1E88E5 : 0xFF666666);
                        tvTab3.setTextColor(curTab[0]==2 ? 0xFF1E88E5 : 0xFF666666);
                    } catch (Throwable ignored) {}
                }
            };
            tvTab1.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { curTab[0]=0; showPage[0].run(); } });
            tvTab2.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { curTab[0]=1; showPage[0].run(); } });
            tvTab3.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { curTab[0]=2; showPage[0].run(); } });

            // 左右滑动切换 tab
            final android.view.GestureDetector.SimpleOnGestureListener gl = new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent e) { return false; }
                @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                    try {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(dx) > 100) {
                            if (dx < 0) curTab[0] = Math.min(2, curTab[0] + 1);
                            else curTab[0] = Math.max(0, curTab[0] - 1);
                            showPage[0].run();
                            return true;
                        }
                    } catch (Throwable ignored) {}
                    return false;
                }
            };
            final android.view.GestureDetector gd = new android.view.GestureDetector(act, gl);
            android.view.View.OnTouchListener tabTouch = new android.view.View.OnTouchListener() {
                @Override public boolean onTouch(android.view.View v, android.view.MotionEvent ev) {
                    try { return gd.onTouchEvent(ev); } catch (Throwable ignored) { return false; }
                }
            };
            content.setOnTouchListener(tabTouch);
            gridImages.setOnTouchListener(tabTouch);
            listVideo.setOnTouchListener(tabTouch);
            listAudio.setOnTouchListener(tabTouch);
            showPage[0].run();

            // 底部按钮:刷新 + 全选/取消全选 + 下载 + 取消
            final android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            final android.widget.Button btnRefresh = new android.widget.Button(act);
            final android.widget.Button btnAll = new android.widget.Button(act);
            final android.widget.Button btnDl = new android.widget.Button(act);
            final android.widget.Button btnCancel = new android.widget.Button(act);
            final android.widget.Button btnPaste = new android.widget.Button(act);
            btnRefresh.setText(T("刷新", "Refresh"));
            btnAll.setText(T("全选", "Select All"));
            btnDl.setText(T("下载", "Download"));
            btnCancel.setText(T("取消", "Cancel"));
            btnPaste.setText(T("粘贴", "Paste"));
            android.widget.LinearLayout.LayoutParams b0p = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            android.widget.LinearLayout.LayoutParams b1p = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            android.widget.LinearLayout.LayoutParams b2p = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            android.widget.LinearLayout.LayoutParams b3p = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            android.widget.LinearLayout.LayoutParams b4p = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            btnRow.addView(btnRefresh, b0p); btnRow.addView(btnAll, b1p); btnRow.addView(btnDl, b2p); btnRow.addView(btnCancel, b3p); btnRow.addView(btnPaste, b4p);
            root.addView(btnRow, new android.widget.LinearLayout.LayoutParams(-1, -2));

            btnPaste.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try { showPasteDownloadDialog(act); } catch (Throwable t) { XposedBridge.log("[SBPlus] paste dl error: " + t); }
                }
            });

            final boolean[] allSelected = new boolean[]{false};
            btnAll.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    allSelected[0] = !allSelected[0];
                    // 只操作当前tab对应类型的资源(curTab: 0=video, 1=audio, 2=image)
                    String currentType = (curTab[0] == 0) ? "video" : ((curTab[0] == 1) ? "audio" : "image");
                    for (int i = 0; i < n; i++) {
                        if (types.get(i).equals(currentType)) {
                            checked[i] = allSelected[0];
                        }
                    }
                    btnAll.setText(allSelected[0] ? T("取消全选", "Deselect All") : T("全选", "Select All"));
                    gridAdp.notifyDataSetChanged();
                    listVideo.invalidateViews(); listAudio.invalidateViews();
                    updateSelCount.run();
                }
            });
            btnDl.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    java.util.List<Integer> sel = new java.util.ArrayList<Integer>();
                    for (int i = 0; i < n; i++) if (checked[i]) sel.add(i);
                    if (sel.isEmpty()) { toastShort(T("未选择任何资源", "Nothing selected")); return; }
                    downloadMany(sel, urls, types, titles);
                }
            });
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(act)
                .setTitle((CharSequence) null)
                .setView(root)
                .setCancelable(true)
                .create();
            btnCancel.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
            });
            btnRefresh.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                    // 重新嗅探:必须在主线程执行 evaluateJavaScript,且走 sniffCurrentPage 完整流程
                    // (注册 JS 桥 + 置 pending + 超时兜底),SNIFF_JS 返回累积全量自动开新面板
                    try { sniffCurrentPage(); } catch (Throwable ignored) {}
                }
            });
            dlg.show();
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showMediaDialog error: " + t);
            return false;
        }
    }

    /** 手动输入链接下载(m3u8 / mp4 / m4s 直链等)。参考脚本 1166 的"自输入链接下载"。 */
    private void showPasteDownloadDialog(final android.app.Activity act) {
        try {
            final android.widget.LinearLayout ll = new android.widget.LinearLayout(act);
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setPadding(48, 24, 48, 8);
            final android.widget.EditText urlEt = new android.widget.EditText(act);
            urlEt.setHint(T("粘贴 m3u8/mp4/m4s 等下载链接", "Paste m3u8/mp4/m4s download URL"));
            urlEt.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            ll.addView(urlEt);
            // 读取剪贴板自动填入
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
                    if (cs != null) {
                        String cst = cs.toString().trim();
                        if (cst.length() > 0 && (cst.startsWith("http://") || cst.startsWith("https://"))) urlEt.setText(cst);
                    }
                }
            } catch (Throwable ignored) {}
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            b.setTitle(T("粘贴链接下载", "Paste URL & Download"));
            b.setView(ll);
            b.setPositiveButton(T("下载", "Download"), new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String u = urlEt.getText().toString().trim();
                    if (u.isEmpty()) { toastShort(T("链接不能为空", "URL is empty")); return; }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                    String up = u.toLowerCase();
                    if (up.contains(".m3u8") || up.contains(".mpd")) {
                        downloadM3u8(u, "");
                    } else if (up.indexOf("bilivideo.com") >= 0 || up.indexOf("upos-sz") >= 0 || up.indexOf("upgcx") >= 0) {
                        // 单个 B 站 m4s 直链:按视频/音频处理
                        downloadOneItem(u, up.endsWith(".m4a") ? "audio" : "video", "");
                    } else {
                        downloadOneItem(u, detectMediaType(u), "");
                    }
                }
            });
            b.setNegativeButton(T("取消", "Cancel"), null);
            b.show();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showPasteDownloadDialog error: " + t);
        }
    }

    /** 从 URL 后缀解析尺寸,如 @384w_216h_1c.webp -> 384x216 */
    private String parseDim(String url) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("@(\\d+)w_(\\d+)h").matcher(url);
            if (m.find()) return m.group(1) + "x" + m.group(2);
            m = java.util.regex.Pattern.compile("[/_](\\d+)x(\\d+)[/._]").matcher(url);
            if (m.find()) return m.group(1) + "x" + m.group(2);
        } catch (Throwable ignored) {}
        return "?";
    }

    /** HEAD 请求获取文件大小,返回 "1.2MB"/"350KB"/null */
    private String httpHeadSize(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Referer", url);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code == 200 || code == 206) {
                long len = conn.getContentLengthLong();
                conn.disconnect();
                if (len > 0) return fmtSize(len);
            } else if (code == 405) {
                // HEAD 不支持则 GET 读前 8KB(只取 Content-Length)
                conn.disconnect();
                java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) u.openConnection();
                c2.setRequestMethod("GET");
                c2.setConnectTimeout(5000); c2.setReadTimeout(5000);
                c2.setRequestProperty("Range", "bytes=0-8191");
                c2.setRequestProperty("Referer", url);
                long len2 = c2.getContentLengthLong();
                if (len2 > 0) { c2.disconnect(); return fmtSize(len2); }
                long total = len2 + 0;
                c2.disconnect();
                if (total > 0) return fmtSize(total);
            } else {
                conn.disconnect();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** GET 下载字节(缩略图) */
    private byte[] httpGetBytes(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer", url);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return null; }
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            is.close(); conn.disconnect();
            return bos.toByteArray();
        } catch (Throwable ignored) { return null; }
    }

    /** 采样解码 Bitmap(避免 OOM) */
    private android.graphics.Bitmap decodeSampledBitmap(byte[] data, int reqW, int reqH) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int bw = o.outWidth, bh = o.outHeight;
            int sample = 1;
            while (bw / sample > reqW * 2 && bh / sample > reqH * 2) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable ignored) { return null; }
    }

    /** 格式化大小 */
    private String fmtSize(long len) {
        try {
            if (len >= 1048576) return String.format("%.1fMB", len / 1048576.0);
            return (len / 1024) + "KB";
        } catch (Throwable ignored) { return "?"; }
    }

    private void sniffDownload(String url, String type, String title) {
        try {
            DownloadMeta meta = new DownloadMeta();
            meta.url = url;
            String ext = "video".equals(type) ? "mp4" : ("audio".equals(type) ? "mp3" : "jpg");
            if (title != null && !title.isEmpty()) meta.fileName = title + "." + ext;
            meta.mimeType = "video".equals(type) ? "video/*" : ("audio".equals(type) ? "audio/*" : "image/*");
            boolean ok = dispatchToDownloader(meta);
            XposedBridge.log("[SBPlus] sniff download " + url + " -> " + ok);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] sniffDownload error: " + t);
        }
    }

    private String shortUrl(String url) {
        try {
            String u = url;
            if (u.length() > 60) u = u.substring(0, 57) + "...";
            return u;
        } catch (Throwable t) { return url; }
    }

    private void toastShort(final String msg) {
        try {
            final android.content.Context ctx = sAppContext;
            if (ctx == null) return;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(new Runnable() { @Override public void run() {
                try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) {}
            }});
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] toastShort error: " + t);
        }
    }


    /** 从 TabEventHandler 对象拿到真实 Tab,并注册 __sbplus__ JS 桥(幂等)。 */
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

    /** 无条件更新当前活动的 realTab(嗅探等不依赖油猴开关的功能使用)。 */
    private void updateCurrentRealTab(Object tabEventHandlerObj) {
        try {
            Object tab = XposedHelpers.getObjectField(tabEventHandlerObj, "mTab");
            if (tab == null) return;
            Object realTab = XposedHelpers.callMethod(tab, "getTab");
            if (realTab != null) sCurrentRealTab = realTab;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] updateCurrentRealTab error: " + t);
        }
    }


    /** 从缓存拼接脚本声明的 @require 外部库(主线程调用,只读缓存不做网络)。 */
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

    /** 后台线程预下载脚本依赖的 @require 库到缓存;全部就绪后回到主线程执行注入。 */
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
                    // 下载 @resource 资源(name + 空格 + url)
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

    /** 生成 JSON 双引号字符串字面量(含外层引号)。 */
    private String jsonQuote(String s) {
        if (s == null) return "\"\"";
        String e = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + e + "\"";
    }

    /** 主线程执行注入:只读缓存拼装 + evaluateJavaScript。 */
    private void doInjectOnMain(java.util.List<UserscriptMeta> metas, String url, Object realTab) {
        try {
            // 注册 __sbplus__ JS 桥(脚本执行时用 GM_xmlhttpRequest 跨域)。
            try {
                XposedHelpers.callMethod(realTab, "addJavaScriptInterface", new SbplusJsBridge(), "__sbplus__");
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] addJavaScriptInterface error: " + t);
            }

            // 一次性拼接注入:@resource 资源 + GM API 引擎 + 逐脚本(@require + tag + 主体)。
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
                // 为每个脚本重绑定带捕获 tag 的 registerMenuCommand(闭包),避免脚本异步
                // 注册菜单时读到被后续脚本覆盖的全局 current_tag,导致菜单错记到别的脚本名下。
                all.append("(function(){var __scoped_tag__=").append(jsonQuote(m.name)).append(";");
                all.append("var __scoped_orig__=window.GM_registerMenuCommand;");
                all.append("var __scoped_reg__=function(name,fn,acc){try{window.__sbplus_menus__=window.__sbplus_menus__||{};if(!window.__sbplus_menus__[__scoped_tag__])window.__sbplus_menus__[__scoped_tag__]=[];var arr=window.__sbplus_menus__[__scoped_tag__];var found=-1;for(var i=0;i<arr.length;i++){if(arr[i].n===name){found=i;break;}}if(found>=0){arr[found].f=fn;arr[found].id=found;return found;}var id=arr.length;arr.push({n:name,f:fn});window.__sbplus_dbg__=window.__sbplus_dbg__||[];window.__sbplus_dbg__.push('REG:'+name+'@'+__scoped_tag__);return id;}catch(e){return 0;}};");
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
            // 确认式注入:注入后 600ms 读回探针,失败则最多重试 3 次(每次间隔递增)。
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
            // 网页加载确认后,强制显示嗅探/油猴图标(兜底:不依赖 updateLocationBarEndIcon)
            showToolbarIconsForWeb(url);
            XposedBridge.log("[SBPlus] userscript injected for " + url + " (" + countMatched(metas, url) + " scripts)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] doInjectOnMain error: " + t);
        }
    }

    /** 是否为脚本源站点(需要伪造脚本管理器)。 */
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
     * 精简设置页:主开关开启时,hook SettingsFragment.initPreferences()(三星加载完所有
     * 设置项后),遍历被勾选的 key,findPreference(key).setVisible(false)。
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
                            // 特殊项:无条件隐藏无 key 的「隐私」分类标题(装饰性空标题)。
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

        // 特殊项 @search:屏蔽设置页顶部搜索(阻止展开 + 隐藏搜索入口)。
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

    /** 隐藏无 key 的「隐私」分类标题(PreferenceCategory,按 title 遍历匹配)。返回隐藏数量。 */
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
                // 匹配「隐私」「Privacy」标题的分类(无 key)。
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
        // [已禁用] 早期误判 SeslRecyclerView 会掉 ACTION_MOVE 导致滑不动,
        // 于是额外 scrollBy 补偿;但实际三星原生滚动正常,补偿造成"双倍滚动→跳动"。
        // 现今设备原生滚动已可正常工作,这里不再额外补偿。保留方法仅为避免调用处报错。
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
                            // 染更多菜单图标: 无条件强制染(不依赖懒判断,防翻页后新 holder 未染色/被覆盖)
                            try {
                                Object iv = XposedHelpers.getObjectField(holder, "itemView");
                                if (iv instanceof android.view.View) {
                                    java.util.List<android.view.View> ivs = new java.util.ArrayList<>();
                                    collectAllViews((android.view.View) iv, ivs);
                                    for (android.view.View v : ivs) {
                                        if (v instanceof android.widget.ImageView) {
                                            forceTintImageView((android.widget.ImageView) v);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
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
            // NOTE: do NOT reset sPageIndicator here - cacheGridRefs fires far more often
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
            //     (RecyclerView weight=1 squeezes any below-dots to zero) - the dots are painted
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
            // A plain tap (no drag/fling) must NOT snap - otherwise tapping an icon
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
                                // 油猴脚本:总开关开启时,优先拦截 .user.js,自己下载保存,不走下载桥。
                                boolean isUs = isUserscriptEnabled();
                                boolean isUjs = isUserScriptUrl(meta.url, meta.fileName);
                                XposedBridge.log("[SBPlus] pre-download check: enabled=" + isUs + " isUserJs=" + isUjs + " url=" + meta.url);
                                if (isUs && isUjs) {
                                    XposedBridge.log("[SBPlus] .user.js detected (pre-download): " + meta.url);
                                    android.widget.Toast.makeText(sAppContext, T("正在安装脚本...", "Installing script..."), android.widget.Toast.LENGTH_SHORT).show();
                                    downloadUserscriptToDir(meta.url);
                                    // 关闭空白 tab / 告知 native 拒绝下载,避免回退导航。
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
                                // 油猴脚本:拦截 .user.js 下载,自动保存到脚本目录。
                                if (isUserscriptEnabled() && isUserScriptUrl(meta.url, meta.fileName)) {
                                    XposedBridge.log("[SBPlus] .user.js detected, intercept: " + meta.url);
                                    android.widget.Toast.makeText(sAppContext, T("正在安装脚本...", "Installing script..."), android.widget.Toast.LENGTH_SHORT).show();
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
    /** 网络层嗅探:hook 底层网络请求,收集所有媒体资源 URL(包括 iframe 内的)。 */
    private void hookNetworkSniff(ClassLoader cl) {
        try {
            // Hook WebViewClient.shouldInterceptRequest - WebView 加载所有资源的入口
            // 这个方法会拦截主框架和所有 iframe 的资源请求
            XposedHelpers.findAndHookMethod(
                android.webkit.WebViewClient.class,
                "shouldInterceptRequest",
                android.webkit.WebView.class,
                android.webkit.WebResourceRequest.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isSniffEnabled()) return;

                            android.webkit.WebResourceRequest request =
                                (android.webkit.WebResourceRequest) param.args[1];

                            if (request != null && request.getUrl() != null) {
                                String url = request.getUrl().toString();
                                String type = detectMediaType(url);

                                if (type != null && !type.isEmpty()) {
                                    sNetworkSniffedUrls.add(url);
                                    XposedBridge.log("[SBPlus] WebView intercept: " + type + " -> " +
                                            url.substring(0, Math.min(100, url.length())));
                                }
                            }
                        } catch (Throwable t) {
                            // 静默
                        }
                    }
                }
            );

            XposedBridge.log("[SBPlus] hookNetworkSniff: WebViewClient.shouldInterceptRequest hooked");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hookNetworkSniff failed: " + t);
        }
    }

    /** 检测 URL 是否是媒体资源,返回类型(video/audio/image)或 null。 */
    private String detectMediaType(String url) {
        try {
            if (url == null || url.isEmpty()) return null;
            String lower = url.toLowerCase();

            // 过滤掉明显的非媒体资源
            if (lower.startsWith("data:") || lower.startsWith("blob:")) return null;
            if (lower.contains("/api/") || lower.contains("/analytics")) return null;
            if (lower.contains(".css") || lower.contains(".js") || lower.contains(".json")) return null;
            if (lower.contains(".woff") || lower.contains(".ttf") || lower.contains(".eot")) return null;

            // 提取路径(去掉查询参数)
            String path = lower.split("[?#]")[0];

            // 视频格式
            if (path.matches(".*\\.(mp4|m4v|webm|mkv|flv|mov|avi|wmv|mpg|mpeg|3gp|m4s|ts|mpd)$")) {
                return "video";
            }

            // 音频格式
            if (path.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|wma)$")) {
                return "audio";
            }

            // 图片格式
            if (path.matches(".*\\.(jpe?g|png|gif|webp|bmp|svg|avif|ico)$")) {
                return "image";
            }

            // 特殊域名识别(B站、优酷等)
            if (lower.contains("bilivideo.com") && (lower.contains(".m4s") || lower.contains(".mp4"))) {
                return "video";
            }

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 合并网络层嗅探的 URL 到 showMediaDialog 的数据中。 */
    /** 从 URL 提取真实文件名(最后路径段, 去 query/fragment, URL解码); 无有效文件名返回短URL。 */
    private static String fileNameFromUrl(String url) {
        try {
            if (url == null) return "";
            String u = url;
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int f = u.indexOf('#');
            if (f >= 0) u = u.substring(0, f);
            int slash = u.lastIndexOf('/');
            String name = slash >= 0 ? u.substring(slash + 1) : u;
            if (!name.isEmpty()) {
                try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Throwable ignored) {}
                name = name.replace('\\', '/');
                // 排除无意义文件名
                if (!name.equals("/") && !name.isEmpty()
                        && !name.equals("index.html") && !name.equals("index.htm")
                        && !name.matches("^[0-9]+$")) {
                    return name;
                }
            }
        } catch (Throwable ignored) {}
        // 静态fallback: 去掉协议后截断
        try {
            String t = url.replaceFirst("^[a-zA-Z]+://", "");
            if (t.length() > 40) t = t.substring(0, 40);
            return t;
        } catch (Throwable ignored) {}
        return "";
    }

    private void mergeNetworkSniffedUrls(java.util.List<String> urls, java.util.List<String> types, java.util.List<String> titles,
                                         java.util.List<String> sites) {
        try {
            synchronized (sNetworkSniffedUrls) {
                for (String url : sNetworkSniffedUrls) {
                    // 去重:如果 JS 嗅探已收集过,跳过
                    if (urls.contains(url)) continue;

                    String type = detectMediaType(url);
                    if (type != null) {
                        urls.add(url);
                        types.add(type);
                        titles.add(""); // 网络层嗅探没有 title
                        if (sites != null) sites.add("");
                    }
                }

                // 清空收集列表,为下次嗅探准备
                sNetworkSniffedUrls.clear();
            }
            XposedBridge.log("[SBPlus] merged network sniffed URLs: " + sNetworkSniffedUrls.size());
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] mergeNetworkSniffedUrls error: " + t);
        }
    }

}

