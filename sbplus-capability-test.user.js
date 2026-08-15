// ==UserScript==
// @name         SBPlus 能力测试脚本
// @namespace    sbplus.test
// @version      1.0.0
// @description  测试 SBPlus 管理器各 GM API 能力
// @match        *://*/*
// @run-at       document-end
// @grant        GM_addStyle
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_log
// @grant        GM_registerMenuCommand
// @grant        GM_unregisterMenuCommand
// @grant        GM_openInTab
// @grant        GM_setClipboard
// @grant        GM_info
// ==/UserScript==

(function () {
    'use strict';

    // 1. 脚本主体执行标记：注入一个可见元素，证明脚本真的跑起来了
    GM_addStyle('#sbplus-test-badge { position:fixed; right:12px; bottom:12px; z-index:2147483647; background:#2ecc71; color:#fff; padding:8px 14px; border-radius:20px; font:bold 13px/1.4 sans-serif; box-shadow:0 2px 8px rgba(0,0,0,.3); }');

    function showBadge(text) {
        var el = document.getElementById('sbplus-test-badge');
        if (!el) {
            el = document.createElement('div');
            el.id = 'sbplus-test-badge';
            document.body.appendChild(el);
        }
        el.textContent = text;
    }

    showBadge('SBPlus 脚本运行正常');

    // 2. GM_info
    try {
        GM_log('[SBPlus测试] handler=' + (GM_info && GM_info.scriptHandler) + ' ver=' + (GM_info && GM_info.version));
    } catch (e) {}

    // 3. GM_setValue / GM_getValue 读写
    try {
        var cnt = parseInt(GM_getValue('run_count', '0'), 10) || 0;
        cnt++;
        GM_setValue('run_count', String(cnt));
        GM_log('[SBPlus测试] run_count=' + cnt);
    } catch (e) { GM_log('[SBPlus测试] setValue err ' + e.message); }

    // 4. GM_registerMenuCommand：注册多个菜单命令（管理器核心）
    var mid1 = GM_registerMenuCommand('显示运行状态', function () {
        var c = GM_getValue('run_count', '0');
        showBadge('已运行 ' + c + ' 次');
        GM_log('[SBPlus测试] 菜单: 显示运行状态 → run_count=' + c);
    });

    GM_registerMenuCommand('置顶/取消置顶徽章', function () {
        var el = document.getElementById('sbplus-test-badge');
        if (!el) return;
        var style = el.style;
        style.bottom = (style.bottom === '12px') ? 'auto' : '12px';
        style.top = (style.top === '12px') ? 'auto' : '12px';
        style.right = '12px';
        showBadge('徽章已挪动');
        GM_log('[SBPlus测试] 菜单: 置顶/取消置顶徽章');
    });

    GM_registerMenuCommand('测试 GM_log', function () {
        GM_log('[SBPlus测试] 菜单: 测试日志，时间=' + new Date().toISOString());
        showBadge('已记录日志，请查日志');
    });

    GM_registerMenuCommand('测试 openInTab', function () {
        try {
            GM_openInTab('https://example.com');
            showBadge('已尝试打开新标签');
        } catch (e) { showBadge('openInTab 报错 ' + e.message); }
    });

    GM_registerMenuCommand('测试 setClipboard', function () {
        try {
            GM_setClipboard('SBPlus 剪贴板测试 ' + new Date().toISOString());
            showBadge('已尝试写剪贴板');
        } catch (e) { showBadge('setClipboard 报错 ' + e.message); }
    });

    // 5. 标记菜单已注册（供管理器探针/调试读取）
    try {
        window.__sbplus_registered_menus__ = 5;
    } catch (e) {}

    GM_log('[SBPlus测试] 脚本主体执行完毕，已注册 5 个菜单命令');
})();
