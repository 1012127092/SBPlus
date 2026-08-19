# SBPlus

Samsung Browser 增强模块（**LSPosed** 模块）。

以 LSPosed 为运行框架，为三星浏览器（Samsung Internet，包名 `com.sec.android.app.sbrowser`）
补充官方未提供的功能。

---

## English

A **LSPosed** module that enhances Samsung Internet Browser (package `com.sec.android.app.sbrowser`) with features not provided officially.

### Features

1. **Download Bridge** — forward downloads to a third-party manager (ADM / IDM+ / 1DM, customizable package), passing Cookie / UA / Referer; optionally block the native download.
2. **Settings Integration + Logging** — inject an SBPlus submenu into browser settings, with a built-in log viewer.
3. **Via-style Grid Menu** — turn the "More" menu into a multi-column grid, drag-to-reorder, add/remove icons.
4. **Region Switch** — switch the browser region to one of 17 countries.
5. **UA Spoofing** — fully replace the User-Agent (Desktop Chrome / Mobile / iPhone / custom).
6. **Streamlined Settings** — master switch + multi-select hiding for unneeded items.
7. **Block Updates & Red Dots** — block browser updates, clear update notifications / popups / red dots.
8. **Homepage Video Background** — play a video on the homepage (loop, muted).
9. **Userscript Manager** — full built-in manager: list / add / save / delete / toggle, `.user.js` interception, source management, "update all", lightweight GM API, detail page + share import.
10. **Bookmark Management** — import / export bookmarks (Chrome / Edge / Firefox HTML format), tree checkbox dialog.
11. **Random UA** — 55 real UA strings with random rotation; supports platform/browser multi-select and custom parameters.
12. **Media Sniffer** — sniff and download video/audio/image resources; supports tab-based select-all (video/audio/image independent), segmented video (m4s/ts) auto-merge.
13. **Homepage Beautification** — personalize the homepage search box and other elements.
14. **Version + Project URL + Auto Update Check** — version and project URL shown in module home and browser menu, with auto update checking.

### Requirements

1. Rooted device with **LSPosed** installed (via Magisk).
2. Install the module APK, then enable it in LSPosed Manager.
3. Set scope to **Samsung Internet** (`com.sec.android.app.sbrowser`) or Beta (`com.sec.android.app.sbrowser.beta`).
4. Restart the browser (or device).

### Build

```bat
call <path-to-android-sdk>/env.bat
gradle assembleDebug --no-daemon
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

### Self-adaptation

The module resolves Samsung's obfuscated / version-dependent class and method names **at runtime**, instead of hardcoding them:

- **Dynamic parent resolution** — obtains the parent (obfuscated androidx class) of `PreferenceFragmentCustom` at launch, so it adapts to whichever obfuscation name the browser version uses.
- **Multi-candidate method fallback** — for obfuscated methods (e.g. `PreferenceManager`'s `createPreferenceScreen`), tries candidate names in order, standard first then obfuscated names.
- **Isolated error handling** — each hook is independently guarded; a failure in one does not break others or the browser itself.

Re-resolution happens on every browser start, so after a browser update the module adapts automatically without a module update.

---

## 中文

### 框架说明

- **目标框架：LSPosed**（接口兼容标准 Xposed API）
- 模块入口：`assets/xposed_init` → `com.sbplus.browser.MainHook`
- 兼容：LSPosed 可加载，老 Xposed / EdXposed 理论上也可加载

### 使用前提

1. 手机已 root，并安装 **LSPosed**（Magisk 模块方式）
2. 安装本模块 APK 后，打开 LSPosed Manager：
   - 启用本模块
   - **作用域（Scope）勾选「三星浏览器」**（`com.sec.android.app.sbrowser`）
3. 重启三星浏览器（或重启系统）

> 作用域需要时可在 LSPosed 界面手动勾选。

### 已实现功能

### 1. 下载桥接（第三方下载器接管）
把三星浏览器的下载请求转交给第三方下载器（ADM / IDM+ / 1DM，包名可自定义）。
- hook `TinDownloadController` 下载链路（`onDownloadStarted` 等）
- 传递 Cookie / User-Agent / Referer 等登录态信息
- 可选阻断原生下载（真正"接管"而非并行）

### 2. 设置集成 + 日志
在三星浏览器设置页注入 SBPlus 子菜单，并提供内置日志查看。

### 3. Via 风格网格菜单
把三星「更多」菜单从单列纵向列表改造成多列网格（Via 风格），支持拖拽排序、
添加/删除图标。

### 4. 改区（Country ISO Code）
通过 hook `CountryUtil.getCountryIsoCode()` 等入口，将浏览器地区切换到 17 国之一，
影响所有区域相关行为。

### 5. 浏览器标识（UA 伪装）
hook `SBrowserCommandLine.initialize()`，通过 `TerraceCommandLine.appendSwitchWithValue`
注入 `user-agent` switch，完整替换 UA（桌面 Chrome / 手机 / iPhone / 自定义）。需重启浏览器生效。

### 6. 精简设置页
主开关 + 23 项多选屏蔽，两列网格展示，隐藏不需要的设置项。

### 7. 屏蔽更新和小红点
独立总开关，彻底屏蔽浏览器更新：
- 清除更新通知 / 弹窗 / 红点（关于页、更多按钮、设置徽标）
- 阻断更新检查链路（`UpdateManager.checkUpdate*`）
- 阻断商店网络（`StubUtil.checkUpdateOnGalaxyStore` 等）
- 禁止跳转商店 / 升档
- 通过官方预留 `disable-update-dialog` switch 优雅屏蔽弹窗

### 8. 主页视频背景
让浏览器主页（新标签页/快速访问页）背景播放动态视频。
- 通过 MediaStore 将选中视频存入公共 `Movies/SBPlus/` 目录
- 用 `TextureView + MediaPlayer` 循环静音播放（TextureView 规避 SurfaceView 被不透明背景色盖住的问题）
- 子页提供「选择视频 / 清除视频 / 删除视频」三个操作

### 9. 油猴脚本管理（Userscript）
完整的内置油猴脚本管理器：
- 脚本列表 / 添加 / 保存 / 删除 / 开关（开关在前、名字在后）
- 拦截 `.user.js` 下载引导安装
- 支持源管理（`@updateURL` / `@downloadURL`）、「更新所有脚本」
- 内置精简版 GM API（`GM_setValue` / `GM_getValue` / `GM_registerMenuCommand` 等）
- 脚本详情页 + 分享导入

### 10. 书签管理
- 导入 / 导出书签（Chrome / Edge / Firefox 通用 HTML 格式）
- 树形勾选对话框（可选导入/导出指定节点）

### 11. 随机浏览器标识（UA）
内置 55 条真实 UA 池，支持随机轮换（桌面 Chrome / 手机 / iPhone / 自定义）。支持平台/浏览器多选和自定义参数动态生成。

### 12. 资源嗅探面板
嗅探并下载视频/音频/图片资源：
- 按类型分tab显示（视频/音频/图片独立全选）
- 显示已选文件数量
- 支持分片视频（m4s/ts）自动识别和二进制拼接下载
- 图片格子选中显示蓝色遮罩

### 13. 主页美化子菜单
主页搜索框等页面元素的个性化设置。

### 14. 版本号 + 项目地址 + 自动检测更新
- SBPlus 应用首页与浏览器 SBPlus 菜单里都显示版本号 + 项目地址
- 启动/进入页面时自动检测 GitHub 最新 release，有新版本在版本号后提示「点击更新」，点击后确认下载 apk

### 构建

```bat
call <path-to-android-sdk>\env.bat
gradle assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

### 自适应说明

模块对三星浏览器内部被混淆/随版本变化的类名与方法名做了**运行时自适应解析**，而非硬编码：

- **动态父类解析**：通过明文类 `PreferenceFragmentCustom` 动态获取其父类（androidx 混淆类），
  无论浏览器版本把混淆名改成 `H2.A` 还是其他，都能自动找到。
- **多候选方法回退**：对被混淆的方法（如 `PreferenceManager` 的 `createPreferenceScreen`）
  按候选名列表依次尝试，标准名优先、混淆名兜底。
- **独立容错**：每个 hook 点单独保护，单个失败不影响其他功能，也不影响浏览器自身。

浏览器每次启动时都会重新解析适配，浏览器更新后无需模块更新即可自动适配新版本。

### 开发环境

- Windows AMD64
- Java 17（Temurin）
- Gradle 8.7
- Android SDK 34+
- 依赖仓库：阿里云镜像（解决国内拉取 AGP 依赖超时问题）
- 测试设备：三星 Galaxy（Android 16 / SDK 36）

### 项目结构

```
SBPlus/
├── app/
│   ├── build.gradle              (namespace/applicationId = com.sbplus.browser)
│   └── src/main/
│       ├── AndroidManifest.xml   (xposedmodule 声明)
│       ├── assets/xposed_init    (模块入口)
│       ├── res/values/           (app_name=SBPlus, xposedscope)
│       └── java/com/sbplus/browser/
│           ├── MainHook.java               (核心 hook，功能 1-13)
│           ├── MainActivity.java           (模块首页/版本号/更新检测)
│           ├── UpdateChecker.java          (GitHub 最新版本查询)
│           ├── MenuReorderHelper.java      (网格菜单拖拽排序)
│           ├── MenuAddButtonHelper.java    (添加图标)
│           ├── MenuEditHelper.java         (编辑图标)
│           ├── LogWriter.java / LogProvider.java / LogManagerActivity.java (日志系统)
│           └── ...
├── build.gradle
├── settings.gradle               (rootProject.name=SBPlus)
└── gradle.properties
```
