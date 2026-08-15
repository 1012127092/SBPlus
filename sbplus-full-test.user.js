// ==UserScript==

// @name         SBPlus 综合测试脚本

// @namespace    sbplus.test

// @version      3.0.0

// @description  测试 SBPlus 管理器：GM API + 设置面板 + 网页设置页(@resource) + 配置存取

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

// @grant        GM_openInTab

// @grant        GM_setClipboard

// @grant        GM_getResourceText

// @grant        GM_info

// @resource     configPage https://raw.githubusercontent.com/1012127092/SBPlus/main/assets/config-page.html

// ==/UserScript==



(function () {

    'use strict';



    var PREFIX = 'test_';



    function g(k, d) { try { return GM_getValue(PREFIX + k, d); } catch (e) { return d; } }

    function s(k, v) { try { GM_setValue(PREFIX + k, String(v)); } catch (e) {} }



    // ============ 运行标记徽章 ============

    GM_addStyle('#sbplus-test-badge { position:fixed; right:12px; bottom:12px; z-index:2147483600; background:#2ecc71; color:#fff; padding:8px 14px; border-radius:20px; font:bold 13px/1.4 sans-serif; box-shadow:0 2px 8px rgba(0,0,0,.3); }');

    function showBadge(text) {

        var el = document.getElementById('sbplus-test-badge');

        if (!el) { el = document.createElement('div'); el.id = 'sbplus-test-badge'; document.body.appendChild(el); }

        el.textContent = text;

    }

    showBadge('SBPlus 脚本运行正常');



    // ============ run_count ============

    try {

        var cnt = parseInt(GM_getValue('run_count', '0'), 10) || 0; cnt++;

        GM_setValue('run_count', String(cnt));

        GM_log('[SBPlus测试] run_count=' + cnt);

    } catch (e) { GM_log('[SBPlus测试] setValue err ' + e.message); }

    try { GM_log('[SBPlus测试] handler=' + (GM_info && GM_info.scriptHandler) + ' ver=' + (GM_info && GM_info.version)); } catch (e) {}



    // ============ 弹出面板样式 ============

    GM_addStyle(

        '#sbplus-panel { position:fixed; top:8%; left:50%; transform:translateX(-50%); width:340px; max-width:92vw; max-height:82vh; overflow:auto; z-index:2147483500; background:#fff; color:#222; border-radius:12px; box-shadow:0 8px 40px rgba(0,0,0,.4); font:13px/1.5 sans-serif; padding:16px; box-sizing:border-box; }' +

        '#sbplus-panel h3 { margin:0 0 12px; font-size:16px; }' +

        '#sbplus-panel .row { margin:10px 0; }' +

        '#sbplus-panel label { display:block; font-size:12px; color:#666; margin-bottom:4px; }' +

        '#sbplus-panel input[type=text], #sbplus-panel input[type=number], #sbplus-panel select, #sbplus-panel textarea, #sbplus-panel input[type=color] { width:100%; padding:6px 8px; border:1px solid #ccc; border-radius:6px; font-size:13px; box-sizing:border-box; }' +

        '#sbplus-panel input[type=range] { width:100%; }' +

        '#sbplus-panel button { padding:8px 14px; border:1px solid #ccc; border-radius:6px; background:#f5f5f5; cursor:pointer; font-size:13px; margin:2px; }' +

        '#sbplus-panel button.primary { background:#1e88e5; color:#fff; border-color:#1e88e5; }' +

        '#sbplus-panel .chk { display:flex; align-items:center; gap:6px; margin:3px 0; }' +

        '#sbplus-panel .chk span { margin:0; color:#222; font-size:13px; }' +

        '#sbplus-panel .note { font-size:11px; color:#999; margin-top:8px; }' +

        '#sbplus-panel .close { float:right; border:none; background:none; font-size:18px; cursor:pointer; color:#999; padding:0 4px; }'

    );



    var panelOpen = false;

    function buildPanel() {

        if (panelOpen) return;

        panelOpen = true;

        var p = document.createElement('div'); p.id = 'sbplus-panel';

        var html = '';

        html += '<button class="close" id="sp-close">✕</button><h3>设置面板测试</h3>';

        html += '<div class="row"><label>文本输入框</label><input type="text" id="sp-text" placeholder="输入任意内容并保存"></div>';

        html += '<div class="row"><label>数字输入框</label><input type="number" id="sp-number" min="0" max="100" step="1"></div>';

        html += '<div class="row"><label>下拉选择框</label><select id="sp-select"><option value="a">选项 A</option><option value="b">选项 B</option><option value="c">选项 C</option></select></div>';

        html += '<div class="row"><label>复选框</label><div class="chk"><input type="checkbox" id="sp-check"><span>启用此功能</span></div></div>';

        html += '<div class="row"><label>单选按钮组</label>' +

            '<div class="chk"><input type="radio" name="sp-radio" value="low"><span>低</span></div>' +

            '<div class="chk"><input type="radio" name="sp-radio" value="mid"><span>中</span></div>' +

            '<div class="chk"><input type="radio" name="sp-radio" value="high"><span>高</span></div></div>';

        html += '<div class="row"><label>范围滑块 (<span id="sp-range-val">50</span>%)</label><input type="range" id="sp-range" min="0" max="100" value="50"></div>';

        html += '<div class="row"><label>颜色选择器</label><input type="color" id="sp-color" value="#1e88e5"></div>';

        html += '<div class="row"><label>多行文本(textarea)</label><textarea id="sp-area" rows="3" placeholder="多行内容"></textarea></div>';

        html += '<div class="row">' +

            '<button class="primary" id="sp-save">保存全部</button>' +

            '<button id="sp-reset">恢复默认</button>' +

            '<button id="sp-test">测试按钮(改徽章)</button>' +

            '</div>';

        html += '<div class="note" id="sp-note"></div>';

        p.innerHTML = html;

        document.body.appendChild(p);



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



        document.getElementById('sp-range').addEventListener('input', function (e) { document.getElementById('sp-range-val').textContent = e.target.value; });

        document.getElementById('sp-close').addEventListener('click', function () { closePanel(); });



        document.getElementById('sp-save').addEventListener('click', function () {

            GM_log('[SBPlus测试] save 按钮点击');

            s('text', document.getElementById('sp-text').value);

            s('number', document.getElementById('sp-number').value);

            s('select', document.getElementById('sp-select').value);

            s('check', String(document.getElementById('sp-check').checked));

            var r = document.querySelector('input[name="sp-radio"]:checked'); if (r) s('radio', r.value);

            s('range', document.getElementById('sp-range').value);

            s('color', document.getElementById('sp-color').value);

            s('area', document.getElementById('sp-area').value);

            setNote('已保存：' + new Date().toLocaleTimeString());

            GM_log('[SBPlus测试] 设置面板保存配置完成');

            try { GM_notification('配置已保存', '测试通知'); } catch (e) { setNote('GM_notification 报错：' + e.message); }

        });



        document.getElementById('sp-reset').addEventListener('click', function () {

            GM_log('[SBPlus测试] reset 按钮点击');

            try {

                GM_deleteValue(PREFIX + 'text'); GM_deleteValue(PREFIX + 'number'); GM_deleteValue(PREFIX + 'select');

                GM_deleteValue(PREFIX + 'check'); GM_deleteValue(PREFIX + 'radio'); GM_deleteValue(PREFIX + 'range');

                GM_deleteValue(PREFIX + 'color'); GM_deleteValue(PREFIX + 'area');

                document.getElementById('sp-text').value = '';

                document.getElementById('sp-number').value = '0';

                document.getElementById('sp-select').value = 'a';

                document.getElementById('sp-check').checked = false;

                var rr = document.querySelectorAll('input[name="sp-radio"]');

                for (var i = 0; i < rr.length; i++) rr[i].checked = (rr[i].value === 'mid');

                document.getElementById('sp-range').value = '50';

                document.getElementById('sp-range-val').textContent = '50';

                document.getElementById('sp-color').value = '#1e88e5';

                document.getElementById('sp-area').value = '';

                setNote('已清空所有配置并重置表单');

            } catch (e) { setNote('deleteValue 报错：' + e.message); }

        });



        document.getElementById('sp-test').addEventListener('click', function () {

            GM_log('[SBPlus测试] test 按钮点击');

            var sec = new Date().getSeconds();

            showBadge('按钮反馈第 ' + sec + ' 秒');

            setNote('测试按钮触发，徽章已更新为「第 ' + sec + ' 秒」');

        });



        try {

            var lid = GM_addValueChangeListener(PREFIX + 'text', function (name, oldVal, newVal, remote) {

                setNote('valueChange 回调: ' + name + ' = ' + newVal);

            });

            setNote('addValueChangeListener 返回 id=' + lid);

        } catch (e) { setNote('addValueChangeListener 报错：' + e.message); }

    }

    function setNote(t) { var n = document.getElementById('sp-note'); if (n) n.textContent = t; }

    function closePanel() { var p = document.getElementById('sbplus-panel'); if (p) p.parentNode.removeChild(p); panelOpen = false; }



    // ============ 网页设置页（@resource + GM_getResourceText） ============

    GM_addStyle(

        '#sbplus-page-overlay { position:fixed; inset:0; z-index:2147483600; background:rgba(0,0,0,.55); display:flex; align-items:center; justify-content:center; }' +

        '#sbplus-page { width:92vw; max-width:640px; max-height:88vh; overflow:auto; background:#fff; color:#222; border-radius:12px; font:13px/1.5 sans-serif; box-sizing:border-box; }' +

        '#sbplus-page .sp-page-header { padding:16px 20px 8px; border-bottom:1px solid #eee; }' +

        '#sbplus-page .sp-page-header h2 { margin:0; font-size:18px; }' +

        '#sbplus-page .sp-sub { font-size:12px; color:#888; margin:4px 0 0; }' +

        '#sbplus-page .sp-tabs { display:flex; gap:4px; padding:10px 20px 0; border-bottom:1px solid #eee; }' +

        '#sbplus-page .sp-tab { padding:8px 16px; border:none; background:none; cursor:pointer; font-size:13px; color:#666; border-bottom:2px solid transparent; }' +

        '#sbplus-page .sp-tab.active { color:#1e88e5; border-bottom-color:#1e88e5; font-weight:bold; }' +

        '#sbplus-page .sp-tab-panel { padding:16px 20px; }' +

        '#sbplus-page .sp-row { margin:12px 0; }' +

        '#sbplus-page .sp-row label { display:block; font-size:12px; color:#666; margin-bottom:5px; }' +

        '#sbplus-page .sp-row label.sp-inline { display:flex; align-items:center; gap:6px; font-size:13px; color:#222; }' +

        '#sbplus-page input[type=text], #sbplus-page input[type=number], #sbplus-page select, #sbplus-page textarea, #sbplus-page input[type=color] { width:100%; padding:7px 9px; border:1px solid #ccc; border-radius:6px; font-size:13px; box-sizing:border-box; }' +

        '#sbplus-page input[type=range] { width:100%; }' +

        '#sbplus-page .sp-table { width:100%; border-collapse:collapse; font-size:13px; }' +

        '#sbplus-page .sp-table td { padding:8px 10px; border-bottom:1px solid #eee; }' +

        '#sbplus-page .sp-table td:first-child { color:#888; width:40%; }' +

        '#sbplus-page .sp-page-footer { padding:12px 20px; border-top:1px solid #eee; display:flex; align-items:center; gap:8px; }' +

        '#sbplus-page .sp-btn { padding:8px 16px; border:1px solid #ccc; border-radius:6px; background:#f5f5f5; cursor:pointer; font-size:13px; }' +

        '#sbplus-page .sp-btn.primary { background:#1e88e5; color:#fff; border-color:#1e88e5; }' +

        '#sbplus-page .sp-note { font-size:11px; color:#999; margin-left:auto; }'

    );



    var pageOpen = false;

    function buildPage() {

        if (pageOpen) return;

        var resource = '';

        try { resource = GM_getResourceText('configPage'); } catch (e) { resource = ''; }

        GM_log('[SBPlus测试] 网页设置页 resource 长度=' + resource.length);



        var overlay = document.createElement('div');

        overlay.id = 'sbplus-page-overlay';

        var box = document.createElement('div');

        box.id = 'sbplus-page';

        if (resource && resource.indexOf('sp-page') >= 0) {

            box.innerHTML = resource;

        } else {

            box.innerHTML = '<div class="sp-page-header"><h2>网页设置页（兜底）</h2><p class="sp-sub">@resource 未加载（GM_getResourceText 返回空），使用内联兜底 HTML</p></div>' +

                '<div class="sp-tab-panel"><div class="sp-row"><label>说明</label><p class="sp-sub">resource 长度=' + resource.length + '，说明 @resource 下载失败或未注入，请检查 GitHub raw 是否可访问。</p></div></div>';

        }

        overlay.appendChild(box);

        document.body.appendChild(overlay);

        pageOpen = true;



        // 绑定事件（若 resource 加载成功）

        function bind(k) { var el = document.getElementById(k); return el; }



        // 标签页切换

        var tabs = box.querySelectorAll('.sp-tab');

        for (var i = 0; i < tabs.length; i++) {

            tabs[i].addEventListener('click', function (e) {

                var t = e.currentTarget.getAttribute('data-tab');

                var allTabs = box.querySelectorAll('.sp-tab');

                for (var j = 0; j < allTabs.length; j++) allTabs[j].classList.remove('active');

                e.currentTarget.classList.add('active');

                var panels = box.querySelectorAll('.sp-tab-panel');

                for (var k = 0; k < panels.length; k++) {

                    panels[k].style.display = (panels[k].id === 'tab-' + t) ? 'block' : 'none';

                }

            });

        }



        // 回填

        var elName = bind('pg-name'); if (elName) elName.value = g('pg_name', '');

        var elInt = bind('pg-interval'); if (elInt) elInt.value = g('pg_interval', '30');

        var elTheme = bind('pg-theme'); if (elTheme) elTheme.value = g('pg_theme', 'light');

        var elNotif = bind('pg-notify'); if (elNotif) elNotif.checked = (g('pg_notify', 'false') === 'true');

        var elAuto = bind('pg-autostart'); if (elAuto) elAuto.checked = (g('pg_autostart', 'false') === 'true');

        var elCss = bind('pg-css'); if (elCss) elCss.value = g('pg_css', '');

        var elExc = bind('pg-exclude'); if (elExc) elExc.value = g('pg_exclude', '');

        var elOp = bind('pg-opacity'); if (elOp) { elOp.value = g('pg_opacity', '100'); var ov = bind('pg-opacity-val'); if (ov) ov.textContent = elOp.value; }

        var elAcc = bind('pg-accent'); if (elAcc) elAcc.value = g('pg_accent', '#1e88e5');



        var infoName = bind('pg-info-name'); if (infoName) infoName.textContent = (GM_info && GM_info.script && GM_info.script.name) || 'SBPlus 综合测试脚本';

        var infoVer = bind('pg-info-ver'); if (infoVer) infoVer.textContent = '3.0.0';

        var infoHandler = bind('pg-info-handler'); if (infoHandler) infoHandler.textContent = (GM_info && GM_info.scriptHandler) || 'SBPlus';

        var infoNote = bind('pg-info-note'); if (infoNote) infoNote.textContent = 'resource 长度=' + resource.length;



        // 滑块

        var elOp2 = bind('pg-opacity');

        if (elOp2) elOp2.addEventListener('input', function (e) { var ov = bind('pg-opacity-val'); if (ov) ov.textContent = e.target.value; });



        // 保存

        var elSave = bind('pg-save');

        if (elSave) elSave.addEventListener('click', function () {

            GM_log('[SBPlus测试] 网页设置页保存');

            try {

                s('pg_name', elName ? elName.value : '');

                s('pg_interval', elInt ? elInt.value : '');

                s('pg_theme', elTheme ? elTheme.value : '');

                s('pg_notify', String(elNotif ? elNotif.checked : false));

                s('pg_autostart', String(elAuto ? elAuto.checked : false));

                s('pg_css', elCss ? elCss.value : '');

                s('pg_exclude', elExc ? elExc.value : '');

                s('pg_opacity', elOp2 ? elOp2.value : '');

                s('pg_accent', elAcc ? elAcc.value : '');

                var note = bind('pg-note'); if (note) note.textContent = '已保存网页设置';

                try { GM_notification('网页设置已保存', '测试'); } catch (e2) {}

            } catch (e) {

                var note = bind('pg-note'); if (note) note.textContent = '保存报错：' + e.message;

            }

        });



        // 关闭

        var elClose = bind('pg-close');

        if (elClose) elClose.addEventListener('click', function () { closePage(); });

        overlay.addEventListener('click', function (e) { if (e.target === overlay) closePage(); });

    }

    function closePage() { var o = document.getElementById('sbplus-page-overlay'); if (o) o.parentNode.removeChild(o); pageOpen = false; }



    // ============ 菜单命令 ============

    GM_registerMenuCommand('显示运行状态', function () {

        var c = GM_getValue('run_count', '0'); showBadge('已运行 ' + c + ' 次');

        GM_log('[SBPlus测试] 菜单: 显示运行状态 → run_count=' + c);

    });

    GM_registerMenuCommand('置顶/取消置顶徽章', function () {

        var el = document.getElementById('sbplus-test-badge'); if (!el) return;

        var style = el.style;

        style.bottom = (style.bottom === '12px') ? 'auto' : '12px';

        style.top = (style.top === '12px') ? 'auto' : '12px';

        style.right = '12px';

        showBadge('徽章已挪动');

    });

    GM_registerMenuCommand('测试 GM_log', function () {

        GM_log('[SBPlus测试] 菜单: 测试日志，时间=' + new Date().toISOString());

        showBadge('已记录日志，请查日志');

    });

    GM_registerMenuCommand('测试 openInTab', function () {

        try { GM_openInTab('https://example.com'); showBadge('已尝试打开新标签'); }

        catch (e) { showBadge('openInTab 报错 ' + e.message); }

    });

    GM_registerMenuCommand('测试 setClipboard', function () {

        try { GM_setClipboard('SBPlus 剪贴板测试 ' + new Date().toISOString()); showBadge('已尝试写剪贴板'); }

        catch (e) { showBadge('setClipboard 报错 ' + e.message); }

    });

    GM_registerMenuCommand('打开设置面板', function () { buildPanel(); GM_log('[SBPlus测试] 设置面板已打开'); });

    GM_registerMenuCommand('关闭设置面板', function () { closePanel(); });

    GM_registerMenuCommand('打开网页设置页', function () { buildPage(); GM_log('[SBPlus测试] 网页设置页已打开'); });

    GM_registerMenuCommand('打开独立设置页(新标签)', function () {

        var html = '<!DOCTYPE html><html><head><meta charset="utf-8"><title>独立设置页</title><style>' +

            'body{font:14px/1.6 sans-serif;padding:20px;max-width:640px;margin:0 auto;color:#222}' +

            'h1{font-size:20px}' +

            'label{display:block;margin:10px 0 4px;font-size:12px;color:#666}' +

            'input,select,textarea{width:100%;padding:7px;border:1px solid #ccc;border-radius:6px;box-sizing:border-box;font-size:13px}' +

            'button{padding:8px 16px;border:none;border-radius:6px;background:#1e88e5;color:#fff;font-size:13px;cursor:pointer;margin-top:12px}' +

            '.badge{display:inline-block;background:#2ecc71;color:#fff;padding:4px 10px;border-radius:12px;font-size:12px}' +

            '</style></head><body>' +

            '<h1>独立设置页（新标签）</h1>' +

            '<p><span class="badge">独立页面</span> 这是通过 GM_openInTab + data: URL 打开的完整设置页。</p>' +

            '<div><label>标题</label><input type="text" id="t"></div>' +

            '<div><label>模式</label><select id="m"><option value="a">A</option><option value="b">B</option></select></div>' +

            '<div><label>备注</label><textarea id="n" rows="3"></textarea></div>' +

            '</body></html>';

        var url = 'data:text/html;charset=utf-8,' + encodeURIComponent(html);

        try { GM_openInTab(url); showBadge('已尝试打开独立设置页'); }

        catch (e) { showBadge('openInTab 报错 ' + e.message); }

    });

    GM_registerMenuCommand('读取全部配置', function () {

        var vals = []; try { vals = GM_listValues(); } catch (e) {}

        var out = [];

        for (var i = 0; i < vals.length; i++) if (vals[i].indexOf(PREFIX) === 0) out.push(vals[i] + '=' + GM_getValue(vals[i], '(null)'));

        var runc = GM_getValue('run_count', '0');

        GM_log('[SBPlus测试] 配置项: ' + (out.length ? out.join('; ') : '(无)') + ' | run_count=' + runc);

        alert('当前配置：\n' + (out.length ? out.join('\n') : '(空)') + '\nrun_count=' + runc);

    });



    GM_log('[SBPlus测试] 脚本加载完成，已注册 10 个菜单命令');

})();

