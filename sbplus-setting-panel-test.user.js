// ==UserScript==
// @name         SBPlus 设置面板测试脚本
// @namespace    sbplus.settingtest
// @version      1.0.0
// @description  测试 SBPlus 管理器：网页风格设置面板 + 各类表单控件 + 配置存取
// @match        *://*/*
// @run-at       document-end
// @grant        GM_addStyle
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_deleteValue
// @grant        GM_listValues
// @grant        GM_log
// @grant        GM_registerMenuCommand
// @grant        GM_unregisterMenuCommand
// @grant        GM_addValueChangeListener
// @grant        GM_removeValueChangeListener
// @grant        GM_notification
// @grant        GM_info
// @grant        GM_setClipboard
// ==/UserScript==

(function () {
    'use strict';

    var PREFIX = 'settingtest_';

    function g(k, d) { try { return GM_getValue(PREFIX + k, d); } catch (e) { return d; } }
    function s(k, v) { try { GM_setValue(PREFIX + k, String(v)); } catch (e) {} }

    // ============ 面板样式 ============
    GM_addStyle(
        '#sbplus-panel { position:fixed; top:10%; left:50%; transform:translateX(-50%); width:340px; max-width:92vw; max-height:80vh; overflow:auto; z-index:2147483000; background:#fff; color:#222; border-radius:12px; box-shadow:0 8px 40px rgba(0,0,0,.35); font:13px/1.5 sans-serif; padding:16px; box-sizing:border-box; }' +
        '#sbplus-panel h3 { margin:0 0 12px; font-size:16px; }' +
        '#sbplus-panel .row { margin:10px 0; }' +
        '#sbplus-panel label { display:block; font-size:12px; color:#666; margin-bottom:4px; }' +
        '#sbplus-panel input[type=text], #sbplus-panel input[type=number], #sbplus-panel select, #sbplus-panel textarea, #sbplus-panel input[type=color] { width:100%; padding:6px 8px; border:1px solid #ccc; border-radius:6px; font-size:13px; box-sizing:border-box; }' +
        '#sbplus-panel input[type=range] { width:100%; }' +
        '#sbplus-panel button { padding:8px 14px; border:1px solid #ccc; border-radius:6px; background:#f5f5f5; cursor:pointer; font-size:13px; margin:2px; }' +
        '#sbplus-panel button.primary { background:#1e88e5; color:#fff; border-color:#1e88e5; }' +
        '#sbplus-panel .chk { display:flex; align-items:center; gap:6px; }' +
        '#sbplus-panel .chk span { margin:0; color:#222; }' +
        '#sbplus-panel .note { font-size:11px; color:#999; margin-top:8px; }' +
        '#sbplus-panel .close { float:right; border:none; background:none; font-size:18px; cursor:pointer; color:#999; padding:0 4px; }'
    );

    var panelOpen = false;

    function buildPanel() {
        if (panelOpen) return;
        panelOpen = true;

        var p = document.createElement('div');
        p.id = 'sbplus-panel';

        var html = '';
        html += '<button class="close" id="sp-close">✕</button><h3>设置面板测试</h3>';

        // 文本框
        html += '<div class="row"><label>文本输入框</label><input type="text" id="sp-text" placeholder="输入任意内容并保存"></div>';

        // 数字
        html += '<div class="row"><label>数字输入框</label><input type="number" id="sp-number" min="0" max="100" step="1"></div>';

        // 下拉框
        html += '<div class="row"><label>下拉选择框</label><select id="sp-select"><option value="a">选项 A</option><option value="b">选项 B</option><option value="c">选项 C</option></select></div>';

        // 复选框
        html += '<div class="row"><label>复选框</label><div class="chk"><input type="checkbox" id="sp-check"><span>启用此功能</span></div></div>';

        // 单选
        html += '<div class="row"><label>单选按钮组</label>' +
            '<div class="chk"><input type="radio" name="sp-radio" value="low"><span>低</span></div>' +
            '<div class="chk"><input type="radio" name="sp-radio" value="mid"><span>中</span></div>' +
            '<div class="chk"><input type="radio" name="sp-radio" value="high"><span>高</span></div></div>';

        // 滑块
        html += '<div class="row"><label>范围滑块 (<span id="sp-range-val">50</span>%)</label><input type="range" id="sp-range" min="0" max="100" value="50"></div>';

        // 颜色
        html += '<div class="row"><label>颜色选择器</label><input type="color" id="sp-color" value="#1e88e5"></div>';

        // 多行文本
        html += '<div class="row"><label>多行文本(textarea)</label><textarea id="sp-area" rows="3" placeholder="多行内容"></textarea></div>';

        // 按钮
        html += '<div class="row">' +
            '<button class="primary" id="sp-save">保存全部</button>' +
            '<button id="sp-reset">恢复默认</button>' +
            '<button id="sp-test">测试按钮(改标题)</button>' +
            '</div>';

        html += '<div class="note" id="sp-note"></div>';

        p.innerHTML = html;
        document.body.appendChild(p);

        // 回填已存配置
        document.getElementById('sp-text').value = g('text', '');
        document.getElementById('sp-number').value = g('number', '0');
        document.getElementById('sp-select').value = g('select', 'a');
        document.getElementById('sp-check').checked = (g('check', 'false') === 'true');
        var radios = document.querySelectorAll('input[name="sp-radio"]');
        var savedRadio = g('radio', 'mid');
        for (var i = 0; i < radios.length; i++) if (radios[i].value === savedRadio) radios[i].checked = true;
        document.getElementById('sp-range').value = g('range', '50');
        document.getElementById('sp-range-val').textContent = g('range', '50');
        document.getElementById('sp-color').value = g('color', '#1e88e5');
        document.getElementById('sp-area').value = g('area', '');

        // 滑块实时显示
        document.getElementById('sp-range').addEventListener('input', function (e) {
            document.getElementById('sp-range-val').textContent = e.target.value;
        });

        // 关闭
        document.getElementById('sp-close').addEventListener('click', function () { closePanel(); });

        // 保存按钮
        document.getElementById('sp-save').addEventListener('click', function () {
            s('text', document.getElementById('sp-text').value);
            s('number', document.getElementById('sp-number').value);
            s('select', document.getElementById('sp-select').value);
            s('check', String(document.getElementById('sp-check').checked));
            var r = document.querySelector('input[name="sp-radio"]:checked');
            if (r) s('radio', r.value);
            s('range', document.getElementById('sp-range').value);
            s('color', document.getElementById('sp-color').value);
            s('area', document.getElementById('sp-area').value);
            setNote('已保存：' + new Date().toLocaleTimeString());
            GM_log('[SBPlus设置面板] 保存配置完成');
            // 测通知
            try { GM_notification('配置已保存', '测试通知'); } catch (e) { setNote('GM_notification 报错：' + e.message); }
        });

        // 恢复默认
        document.getElementById('sp-reset').addEventListener('click', function () {
            try { GM_deleteValue(PREFIX + 'text'); GM_deleteValue(PREFIX + 'number'); GM_deleteValue(PREFIX + 'select'); GM_deleteValue(PREFIX + 'check'); GM_deleteValue(PREFIX + 'radio'); GM_deleteValue(PREFIX + 'range'); GM_deleteValue(PREFIX + 'color'); GM_deleteValue(PREFIX + 'area'); setNote('已清空所有配置'); } catch (e) { setNote('deleteValue 报错：' + e.message); }
        });

        // 测试按钮
        document.getElementById('sp-test').addEventListener('click', function () {
            document.title = 'SBPlus 设置面板测试 ' + new Date().getSeconds();
            setNote('标题已改为当前秒数');
        });

        // 测 addValueChangeListener（若无回调则说明是空实现）
        try {
            var lid = GM_addValueChangeListener(PREFIX + 'text', function (name, oldVal, newVal, remote) {
                setNote('valueChange 回调触发: ' + name + ' = ' + newVal);
            });
            setNote('addValueChangeListener 返回 id=' + lid);
        } catch (e) {
            setNote('addValueChangeListener 报错：' + e.message);
        }
    }

    function setNote(t) {
        var n = document.getElementById('sp-note');
        if (n) n.textContent = t;
    }

    function closePanel() {
        var p = document.getElementById('sbplus-panel');
        if (p) p.parentNode.removeChild(p);
        panelOpen = false;
    }

    // 注册菜单命令「打开设置面板」
    GM_registerMenuCommand('打开设置面板', function () {
        buildPanel();
        GM_log('[SBPlus设置面板] 面板已打开');
    });

    GM_registerMenuCommand('关闭设置面板', function () {
        closePanel();
    });

    // 也注册一个「读取全部配置」命令，验证存取
    GM_registerMenuCommand('读取全部配置', function () {
        var vals = [];
        try { vals = GM_listValues(); } catch (e) {}
        var out = [];
        for (var i = 0; i < vals.length; i++) {
            if (vals[i].indexOf(PREFIX) === 0) out.push(vals[i] + '=' + GM_getValue(vals[i], '(null)'));
        }
        GM_log('[SBPlus设置面板] 配置项: ' + (out.length ? out.join('; ') : '(无)'));
        alert('当前配置：\n' + (out.length ? out.join('\n') : '(空)'));
    });

    GM_log('[SBPlus设置面板] 脚本加载完成，已注册 3 个菜单命令');
})();
