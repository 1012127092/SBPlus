# SBPlus

A Samsung Internet (Samsung Internet Browser) enhancement module based on **LSPosed**.

Adds features not officially provided by the browser, covering downloads, userscripts, privacy, and UI beautification.

> 中文说明见文末 / Chinese description at the bottom.

## Features

1. **Download Bridge** — forward browser download requests to a third-party download manager (ADM / IDM+ / 1DM, package name customizable), passing cookies / UA / Referer, with optional blocking of the native download for a true "takeover".
2. **Settings Integration + Logging** — injects an SBPlus submenu into the browser settings, with a built-in log viewer.
3. **Via-style Grid Menu** — turns the "More" menu into a multi-column grid, with drag-to-reorder and add/remove icons.
4. **Region Switch (Country ISO Code)** — switches the browser region to one of 17 countries, affecting all region-related behavior.
5. **Browser Identity (UA Spoofing)** — fully replaces the User-Agent (Desktop Chrome / Mobile / iPhone / custom).
6. **Streamlined Settings Page** — master switch plus multi-select hiding for unneeded settings items.
7. **Block Updates & Red Dots** — completely blocks browser updates, clears update notifications / popups / red dots, and blocks update checks and store redirects.
8. **Homepage Video Background** — plays a dynamic video on the browser homepage (loop, muted).
9. **Userscript Manager** — a full built-in userscript manager: list / add / save / delete / toggle, `.user.js` download interception, source management (`@updateURL` / `@downloadURL`), "update all", built-in lightweight GM API (`GM_setValue` / `GM_getValue` / `GM_registerMenuCommand`, etc.), script detail page + share import.
10. **Bookmark Management** — import / export bookmarks (Chrome / Edge / Firefox universal HTML format), tree checkbox dialog.
11. **Random UA** — 55 real UA strings, with random rotation.
12. **Homepage Beautification** — personalization of homepage search box and other page elements.
13. **Version + Project URL + Auto Update Check** — shows version and project URL in both the module home and the browser menu, with auto update checking on launch.

## Requirements

1. Rooted device with **LSPosed** installed (via Magisk module).
2. After installing the module APK, enable it in LSPosed Manager.
3. Set the scope to **Samsung Internet** (`com.sec.android.app.sbrowser`) or the Beta build (`com.sec.android.app.sbrowser.beta`).
4. Restart the browser (or the device).

## Source & Feedback

- Source code: https://github.com/1012127092/SBPlus
- Feedback: file an issue in the source repository.

## Disclaimer

This module is for learning and research only. Do not use it for any illegal or non-compliant purposes.

---

## 中文说明

三星浏览器（Samsung Internet）增强模块，基于 **LSPosed** 框架，为三星浏览器补充官方未提供的功能。

### 已实现功能

1. **下载桥接**：把浏览器下载请求转交给第三方下载器（ADM / IDM+ / 1DM，包名可自定义），传递 Cookie / UA / Referer 等登录态，可选阻断原生下载实现真正接管。
2. **设置集成 + 日志**：在浏览器设置页注入 SBPlus 子菜单，内置日志查看。
3. **Via 风格网格菜单**：把「更多」菜单改造成多列网格，支持拖拽排序、增删图标。
4. **改区（Country ISO Code）**：切换浏览器地区到 17 国之一，影响所有区域相关行为。
5. **浏览器标识（UA 伪装）**：完整替换 UA（桌面 Chrome / 手机 / iPhone / 自定义）。
6. **精简设置页**：主开关 + 多项多选屏蔽，隐藏不需要的设置项。
7. **屏蔽更新和小红点**：彻底屏蔽浏览器更新，清除更新通知 / 弹窗 / 红点，阻断更新检查与商店跳转。
8. **主页视频背景**：让浏览器主页背景播放动态视频（循环静音播放）。
9. **油猴脚本管理**：完整的内置油猴脚本管理器——列表 / 添加 / 保存 / 删除 / 开关、拦截 `.user.js` 下载引导安装、源管理（`@updateURL` / `@downloadURL`）、「更新所有脚本」、内置精简版 GM API、脚本详情页 + 分享导入。
10. **书签管理**：导入 / 导出书签（Chrome / Edge / Firefox 通用 HTML 格式），树形勾选对话框。
11. **随机浏览器标识（UA）**：内置 55 条真实 UA 池，支持随机轮换。
12. **主页美化子菜单**：主页搜索框等页面元素的个性化设置。
13. **版本号 + 项目地址 + 自动检测更新**：模块首页与浏览器菜单均显示版本号与项目地址，启动时自动检测更新。

### 使用前提

1. 手机已 root，并安装 **LSPosed**（Magisk 模块方式）。
2. 安装本模块 APK 后，在 LSPosed Manager 中启用本模块。
3. 作用域勾选 **三星浏览器**（`com.sec.android.app.sbrowser`）或 Beta 版（`com.sec.android.app.sbrowser.beta`）。
4. 重启三星浏览器（或重启系统）。

### 反馈与源码

- 源码：https://github.com/1012127092/SBPlus
- 反馈：在源码仓库提交 issue

### 免责声明

本模块仅供学习研究使用，请勿用于任何违法违规用途。
