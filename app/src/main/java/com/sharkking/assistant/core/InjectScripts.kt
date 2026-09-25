package com.sharkking.assistant.core

/**
 * 注入到 WebView 的 JS 片段，移植自 iOS 版原生壳内嵌的脚本。
 *
 * 注意：iOS 版中的「连点器」(autoClicker) 已按需求移除，本文件不包含该功能。
 */
object InjectScripts {

    /** 登录请求体替换：把发往登录接口的 POST body 换成当前选中账号的 bin 数据 */
    val XHR_INTERCEPT = """
(function(){
    if(window.__xhrIntercepted) return;
    window.__xhrIntercepted = true;
    var _open = XMLHttpRequest.prototype.open;
    var _send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){
        this._m = m; this._u = u;
        this._isTarget = (m === 'POST' &&
            /hortorgames\.com\/login\/(authuser|serverlist)/.test(u));
        return _open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(b){
        if (this._isTarget && window.__activeBinHex) {
            console.log('[奶蛙] 拦截XHR:', this._u);
            var hex = window.__activeBinHex;
            var arr = new Uint8Array(hex.length / 2);
            for (var i = 0; i < arr.length; i++) {
                arr[i] = parseInt(hex.substr(i * 2, 2), 16);
            }
            return _send.call(this, arr.buffer);
        }
        return _send.call(this, b);
    };
    console.log('[奶蛙] XHR拦截已启动');
})();
""".trimIndent()

    /** 全局错误吞噬 + 安全剪贴板 + execCommand 兜底 */
    val COMPAT = """
(function(){
    if(window.__compatInstalled) return;
    window.__compatInstalled = true;

    // 1. 全局错误吞噬：游戏在缺少原生 SDK 时会抛异常，放行会中断加载
    window.onerror = function(){ return true; };
    window.addEventListener('error', function(e){
        e.preventDefault(); e.stopPropagation();
    }, true);
    window.addEventListener('unhandledrejection', function(e){
        e.preventDefault(); e.stopPropagation();
    }, true);

    // 2. 安全剪贴板
    var _cb = '';
    window.setClipboard = function(t){
        if (t === undefined || t === null) return;
        var text = (typeof t === 'object') ? (t.text || JSON.stringify(t)) : String(t);
        try {
            if (window.AndroidBridge && AndroidBridge.setClipboard) {
                AndroidBridge.setClipboard(text);
                return;
            }
        } catch(e) {}
        _cb = text;
    };

    // 3. 安全 execCommand：拦掉 copy/paste 避免游戏内触发系统面板
    try {
        var _ec = document.execCommand;
        document.execCommand = function(cmd){
            if (cmd === 'copy') {
                try {
                    _cb = window.getSelection ? window.getSelection().toString() : '';
                } catch(e) {}
                return true;
            }
            if (cmd === 'paste') return true;
            return _ec.apply(this, arguments);
        };
    } catch(e) {}

    // 4. 防止 copy 冒泡到系统
    document.addEventListener('copy', function(e){ e.stopPropagation(); }, true);

    console.log('[奶蛙] 兼容脚本已注入');
})();
""".trimIndent()

    /**
     * 油猴（Tampermonkey）兼容层。
     *
     * 部分脚本依赖这些 API，缺了会静默退出：拿不到 `unsafeWindow` 或
     * `GM_xmlhttpRequest` 就一直空转轮询，表现为什么都不显示。
     *
     * GM_xmlhttpRequest 必须转发到原生：脚本要访问外部域名，
     * 页面内的 XHR/fetch 会被同源策略拦掉。油猴自己也是这么实现的。
     */
    val GM_SHIM = """
(function(){
    if(window.__gmShimInstalled) return;
    window.__gmShimInstalled = true;

    // 没有沙箱隔离，unsafeWindow 就是 window 本身
    if (!window.unsafeWindow) window.unsafeWindow = window;

    // ---- 存储：GM_setValue / GM_getValue ----
    var PREFIX = '__gm_';
    if (!window.GM_setValue) window.GM_setValue = function(k, v){
        try { localStorage.setItem(PREFIX + k, JSON.stringify(v)); } catch(e) {}
    };
    if (!window.GM_getValue) window.GM_getValue = function(k, def){
        try {
            var s = localStorage.getItem(PREFIX + k);
            return s === null ? def : JSON.parse(s);
        } catch(e) { return def; }
    };
    if (!window.GM_deleteValue) window.GM_deleteValue = function(k){
        try { localStorage.removeItem(PREFIX + k); } catch(e) {}
    };
    if (!window.GM_listValues) window.GM_listValues = function(){
        var out = [];
        try {
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                if (k && k.indexOf(PREFIX) === 0) out.push(k.slice(PREFIX.length));
            }
        } catch(e) {}
        return out;
    };

    // ---- 样式 ----
    if (!window.GM_addStyle) window.GM_addStyle = function(css){
        var s = document.createElement('style');
        s.textContent = css;
        (document.head || document.documentElement).appendChild(s);
        return s;
    };

    // ---- 跨域请求：转发到原生 ----
    var pending = {};
    var seq = 0;
    window.__gmResolve = function(id, json){
        var cb = pending[id];
        if (!cb) return;
        delete pending[id];
        var res;
        try { res = JSON.parse(json); } catch(e) { res = { status: 0, error: 'bad response' }; }
        res.readyState = 4;
        res.response = res.responseText;
        // 脚本自己的回调抛错不能冒泡出去，否则会打断后续脚本
        try {
            if (res.error || !res.status) {
                cb.onerror && cb.onerror(res);
            } else {
                cb.onload && cb.onload(res);
            }
            cb.onreadystatechange && cb.onreadystatechange(res);
        } catch(e) {
            console.warn('[奶蛙] 脚本请求回调异常: ' + (e && e.message));
        }
    };

    function gmxhr(opts){
        opts = opts || {};
        var id = 'gm' + (++seq) + '_' + Date.now();
        pending[id] = opts;
        var payload = {
            url: opts.url,
            method: (opts.method || 'GET').toUpperCase(),
            headers: opts.headers || {},
            data: (opts.data === undefined || opts.data === null) ? null : String(opts.data),
            timeout: opts.timeout || 20000,
        };
        try {
            AndroidBridge.gmRequest(id, JSON.stringify(payload));
        } catch(e) {
            // 原生桥不可用时退回普通 XHR，同源请求仍然能成
            delete pending[id];
            // 原生桥不可用时退回普通 XHR，同源请求仍然能work
            try {
                var x = new XMLHttpRequest();
                x.open(payload.method, payload.url, true);
                Object.keys(payload.headers).forEach(function(k){
                    try { x.setRequestHeader(k, payload.headers[k]); } catch(e2) {}
                });
                x.onload = function(){
                    opts.onload && opts.onload({
                        status: x.status, statusText: x.statusText,
                        responseText: x.responseText, response: x.responseText,
                        responseHeaders: x.getAllResponseHeaders(), readyState: 4,
                    });
                };
                x.onerror = function(){ opts.onerror && opts.onerror({ status: 0, error: 'network' }); };
                x.send(payload.data);
            } catch(e3) {
                opts.onerror && opts.onerror({ status: 0, error: String(e3 && e3.message) });
            }
        }
        return { abort: function(){ delete pending[id]; } };
    }
    if (!window.GM_xmlhttpRequest) window.GM_xmlhttpRequest = gmxhr;

    // 新版 GM.* 命名空间
    if (!window.GM) {
        window.GM = {
            xmlHttpRequest: gmxhr,
            setValue: function(k, v){ return Promise.resolve(window.GM_setValue(k, v)); },
            getValue: function(k, d){ return Promise.resolve(window.GM_getValue(k, d)); },
            deleteValue: function(k){ return Promise.resolve(window.GM_deleteValue(k)); },
            addStyle: function(c){ return Promise.resolve(window.GM_addStyle(c)); },
            setClipboard: function(t){ return Promise.resolve(window.GM_setClipboard(t)); },
        };
    }

    // ---- 其余零散 API ----
    if (!window.GM_setClipboard) window.GM_setClipboard = function(text){
        try { AndroidBridge.setClipboard(String(text)); return; } catch(e) {}
        try { navigator.clipboard && navigator.clipboard.writeText(String(text)); } catch(e) {}
    };
    // 单窗口环境开不了新标签，转成同窗口跳转的空操作，只记录避免脚本报错
    if (!window.GM_openInTab) window.GM_openInTab = function(url){
        console.log('[奶蛙] GM_openInTab 已忽略: ' + url);
        return { close: function(){}, closed: false };
    };
    if (!window.GM_notification) window.GM_notification = function(o){
        var text = (o && (o.text || o.title)) || String(o);
        console.log('[奶蛙] 脚本通知: ' + text);
    };
    if (!window.GM_registerMenuCommand) window.GM_registerMenuCommand = function(name){
        console.log('[奶蛙] 脚本菜单项(未挂载): ' + name);
        return name;
    };
    if (!window.GM_unregisterMenuCommand) window.GM_unregisterMenuCommand = function(){};
    // 有脚本会绑定 GM_download，缺了它取值为 undefined，调用即抛错
    if (!window.GM_download) window.GM_download = function(o){
        var url = (o && o.url) || o;
        console.log('[奶蛙] 脚本请求下载(已忽略): ' + String(url).slice(0, 120));
        if (o && o.onerror) o.onerror({ error: 'not_supported' });
    };
    if (!window.GM_getResourceText) window.GM_getResourceText = function(){ return ''; };
    if (!window.GM_getResourceURL) window.GM_getResourceURL = function(){ return ''; };
    if (!window.GM_log) window.GM_log = function(){ console.log.apply(console, arguments); };
    if (!window.GM_info) window.GM_info = {
        scriptHandler: 'NaiwaAssistant',
        version: '1.0',
        script: { name: 'userscript', version: '1.0', grant: ['none'] },
    };

    // 真机实测：游戏只给 ROLE.roleId，不给 ROLE.id。
    // 但多数脚本用 ROLE.id 判断"会话是否就绪"（游戏增强面板正是因此做了
    // id→roleId→userId 三级兜底），只认 id 的脚本会永远等不到、静默不工作。
    // 这里把 id 补成 roleId 的别名，取值时才计算，避免抢在游戏赋值之前。
    (function(){
        function alias(){
            var R = window.ROLE;
            if (!R || typeof R !== 'object') return false;
            if (R.id != null && R.id !== '') return true;
            var rid = R.roleId != null ? R.roleId : R.userId;
            if (rid == null || rid === '') return false;
            try {
                Object.defineProperty(R, 'id', {
                    get: function(){ return this.roleId != null ? this.roleId : this.userId; },
                    configurable: true,
                });
                console.log('[奶蛙] 已补 ROLE.id = ' + rid);
                return true;
            } catch(e) { try { R.id = rid; return true; } catch(e2) { return false; } }
        }
        if (alias()) return;
        // 角色数据在登录完成后才有，轮询到位为止（最多 120 秒）
        var t = 0;
        var iv = setInterval(function(){
            if (alias() || ++t > 240) clearInterval(iv);
        }, 500);
    })();

    console.log('[奶蛙] 油猴兼容层已注入');
})();
""".trimIndent()

    /**
     * 修 vh 单位失效。
     *
     * 真机实测：`100vh` 被算成 0px，而 `100%` 正常等于视口高度。
     * 原因是 WebSettings.useWideViewPort=true（多开缩放要用它）让 Blink
     * 的视口高度不确定，vh 基准退化为 0。
     *
     * 后果是所有用 vh 限高的脚本面板都被压扁：白玉彩玉 `max-height:88vh`
     * 变 0，面板只剩 2px（一条白线）；无限阵容 `max-height:78vh` 变 0，
     * 只显示标题。
     *
     * 修法：遍历脚本插入的样式表，把 vh 换算成实际像素后重写规则。
     * 不用 CSS 硬编码具体选择器 —— 那样每加一个脚本都要改代码。
     *
     * 2026-09-23 查出它其实早已整体失效，鲸鱼面板（`.xianxia-panel.expanded
     * { max-height: 92vh }`）就是被压成 2px 的，规则始终没被替换过：
     *
     * **真凶：CSS 嵌套之后不能拿「有 cssRules」判断是不是容器规则。**
     * 浏览器支持 CSS Nesting 以后，每条普通 CSSStyleRule 也带一个 cssRules
     * （通常是空列表，但为真值）。原来的 `if (r.cssRules) { 递归; continue; }`
     * 于是对每一条规则都成立，递归进空列表就 continue 掉了规则自己的声明 ——
     * 所有样式表规则一条都改不到，只有行内样式那个分支在工作。
     * 这段代码写的时候还没有 CSS 嵌套，后来是无声失效的，注释里提到的
     * 白玉彩玉 88vh、无限阵容 78vh 应该都是同一个原因。
     * 改法：先处理规则自身的声明（有没有 style），再在有嵌套子规则时递归。
     *
     * 顺带修掉两处会漏改/改坏的地方：
     * 1. 视口高度原本在注入时采样一次就闭包进 toPx。注入这一刻页面往往还没
     *    布局，取到 0 会把 vh 全换算成 `0px` **写进样式表**，不可逆。改成
     *    每次现取，取不到就跳过、留给重试。
     * 2. patchAll 原本只在注入时和 STYLE/LINK 节点新增时执行一次，且观察器
     *    只认元素节点，漏掉「先插空 <style> 再填内容」。改成补跑几次兜底，
     *    并补上文本节点与 characterData 的判断。
     *
     * 改写明细留在 window.__vhFixStats，排查时可直接看改了几处、哪几条。
     */
    val VH_FIX = """
(function(){
    if(window.__vhFixInstalled) return;
    window.__vhFixInstalled = true;

    // 视口高度每次现取：注入时页面可能还没布局，取到 0 会把 vh 换算成 0px 写进样式表
    function realHeight(){
        var h = window.innerHeight || document.documentElement.clientHeight || 0;
        return (isFinite(h) && h > 0) ? h : 0;
    }

    // vh -> px。放在 calc() 里也能正确参与运算。
    function toPx(css, real){
        return css.replace(/(-?[\d.]+)vh/g, function(_, n){
            return (parseFloat(n) * real / 100).toFixed(1) + 'px';
        });
    }

    var PROPS = ['max-height','min-height','height','top','bottom','padding-bottom','margin-bottom'];

    var changed = [];
    window.__vhFixStats = { runs: 0, patched: 0, skippedNoViewport: 0, changed: changed };

    function patchSheet(sheet, real){
        var rules;
        try { rules = sheet.cssRules; } catch(e) { return 0; }   // 跨域样式表读不了
        if (!rules) return 0;
        var n = 0;
        for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            // 先处理规则自己的声明。
            // 注意不能拿「有 cssRules」当「是容器」用：CSS 嵌套之后每条普通
            // 规则都带一个（通常为空的）cssRules，按老写法会 continue 掉每一条，
            // 于是所有样式表规则都改不到 —— 这就是面板被 vh 压扁的真凶。
            if (r.style) {
                for (var p = 0; p < PROPS.length; p++) {
                    var v = r.style.getPropertyValue(PROPS[p]);
                    if (!v || v.indexOf('vh') < 0) continue;
                    var out = toPx(v, real);
                    r.style.setProperty(PROPS[p], out, r.style.getPropertyPriority(PROPS[p]));
                    if (changed.length < 40) {
                        changed.push((r.selectorText || '?') + ' {' + PROPS[p] + ': ' + v + ' -> ' + out + '}');
                    }
                    n++;
                }
            }
            // 再进嵌套：@media 等分组规则，以及 CSS 嵌套的子规则
            if (r.cssRules && r.cssRules.length) n += patchSheet(r, real);
        }
        return n;
    }

    // 复查用：还有多少条规则含 vh（跨域样式表读不到，不计入）
    function countLeft(){
        var n = 0;
        function scan(rs){
            for (var j = 0; j < rs.length; j++) {
                var r = rs[j];
                if (r.style) {
                    for (var p = 0; p < PROPS.length; p++) {
                        var v = r.style.getPropertyValue(PROPS[p]);
                        if (v && v.indexOf('vh') >= 0) n++;
                    }
                }
                if (r.cssRules && r.cssRules.length) scan(r.cssRules);
            }
        }
        for (var i = 0; i < document.styleSheets.length; i++) {
            var rules;
            try { rules = document.styleSheets[i].cssRules; } catch(e) { continue; }
            if (rules) scan(rules);
        }
        return n;
    }

    function patchAll(){
        var real = realHeight();
        if (!real) return -1;                 // 视口还没就绪：一条都不改，等下一次重试
        var total = 0;
        for (var i = 0; i < document.styleSheets.length; i++) {
            total += patchSheet(document.styleSheets[i], real);
        }
        // 行内样式里的 vh 同样要换
        document.querySelectorAll('[style*="vh"]').forEach(function(el){
            var s = el.getAttribute('style');
            if (s && /[\d.]vh/.test(s)) { el.setAttribute('style', toPx(s, real)); total++; }
        });
        return total;
    }

    // 已改过的规则不再含 vh，所以重复执行是幂等的，可以放心补跑
    function run(tag){
        window.__vhFixStats.runs++;
        var c = patchAll();
        if (c < 0) {
            window.__vhFixStats.skippedNoViewport++;
            console.log('[奶蛙] vh 修正: 视口未就绪(' + tag + ')，稍后重试');
            return 0;
        }
        window.__vhFixStats.patched += c;
        var left = countLeft();
        console.log('[奶蛙] vh 修正 ' + c + ' 处(' + tag + ')' +
            (left ? '，仍有 ' + left + ' 处含 vh' : ''));
        return c;
    }

    // 先确认 vh 真的坏了，正常的机型不要动
    var probe = document.createElement('div');
    probe.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:100vh;';
    document.body.appendChild(probe);
    var vh100 = probe.getBoundingClientRect().height;
    document.body.removeChild(probe);

    var probeReal = realHeight();
    if (probeReal && vh100 > probeReal * 0.5) {
        console.log('[奶蛙] vh 正常(100vh=' + vh100.toFixed(0) + 'px)，无需修正');
        return;
    }
    console.log('[奶蛙] vh 失效(100vh=' + vh100.toFixed(0) +
        'px, 视口高 ' + probeReal + 'px)，开始替换');

    run('初次');
    // 样式表是脚本陆续插入的，规则也可能晚于节点插入才解析出来，补跑几次兜底
    [1000, 3000, 8000].forEach(function(ms){
        setTimeout(function(){ run('重试' + ms + 'ms'); }, ms);
    });

    // 新样式表：既看节点新增，也看 <style> 内容被填进去
    var pending = null;
    function schedule(){
        if (pending) return;
        pending = setTimeout(function(){ pending = null; run('新样式表'); }, 50);
    }
    new MutationObserver(function(muts){
        var need = false;
        muts.forEach(function(m){
            Array.prototype.slice.call(m.addedNodes).forEach(function(n){
                if (n.nodeType === 1 && (n.tagName === 'STYLE' || n.tagName === 'LINK')) need = true;
                // 先插空 <style> 再填内容：填进去的是文本节点，只认元素节点会漏
                if (n.nodeType === 3 && n.parentNode && n.parentNode.tagName === 'STYLE') need = true;
            });
            if (m.type === 'characterData' && m.target && m.target.parentNode &&
                m.target.parentNode.tagName === 'STYLE') need = true;
        });
        if (need) schedule();
    }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
""".trimIndent()

    /**
     * UI 修正样式自检 + 面板布局上报。
     *
     * 修正规则本体在 renderer/naiwa-uifix.css。这里额外把脚本插到 body 的
     * 浮层尺寸和位置打到日志：面板"跑到下面"、"只显示标题"这类问题，
     * 靠看代码猜不出来（我已经猜错过几次），必须拿真机的实际数值。
     */
    val SCRIPT_UI_FIX = """
(function(){
    if(window.__scriptUiFixInstalled) return;
    window.__scriptUiFixInstalled = true;

    var applied = window.getComputedStyle(document.body).display === 'block';
    console.log('[奶蛙] UI修正样式' + (applied ? '已生效' : '未生效(检查 naiwa-uifix.css)'));

    // 面板布局只打到日志，供接 USB 时排查
    function logPanels(){
        var vw = window.innerWidth, vh = window.innerHeight;
        console.log('[奶蛙UI] 视口 ' + vw + 'x' + vh);
        Array.prototype.slice.call(document.body.children).forEach(function(el){
            if (el.id === 'Cocos2dGameContainer' || el.id === 'GameCanvas') return;
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'LINK') return;
            var r = el.getBoundingClientRect();
            var cs = window.getComputedStyle(el);
            var flag = '';
            if (r.bottom > vh) flag += ' 超出底部' + Math.round(r.bottom - vh) + 'px';
            if (r.right > vw) flag += ' 超出右侧' + Math.round(r.right - vw) + 'px';
            if (r.height < 4 && cs.display !== 'none') flag += ' 高度塌陷';
            console.log('[奶蛙UI] <' + el.tagName.toLowerCase() +
                (el.id ? '#' + el.id : '') + '> ' +
                Math.round(r.width) + 'x' + Math.round(r.height) +
                ' @(' + Math.round(r.left) + ',' + Math.round(r.top) + ')' +
                ' pos=' + cs.position + ' disp=' + cs.display +
                ' top=' + cs.top + ' bottom=' + cs.bottom + flag);
        });
    }
    // 脚本建 UI 需要时间，延迟采样一次
    setTimeout(logPanels, 8000);
})();
""".trimIndent()

    /**
     * canvas 触摸看护。
     *
     * 只做一件事：canvas 的 pointerEvents 被脚本改成 none 时恢复它。
     *
     * 原先还会把「高 zIndex 的大浮层」设成 display:none 来清理遮挡，
     * 但这条规则区分不了遮挡层和脚本面板，误杀很严重：
     * 无限阵容面板里的 hero-team-list 初始是空 div 且 flex:1 撑满，
     * 搜索框和按钮区同理，全被判成遮挡层隐藏掉 —— 表现就是面板
     * 「只显示队伍管理四个字」。隐藏元素的收益远小于风险，去掉。
     */
    val CANVAS_GUARD = """
(function(){
    if(window.__canvasGuardInstalled) return;
    window.__canvasGuardInstalled = true;
    setInterval(function(){
        var canvas = document.getElementById('GameCanvas');
        if (!canvas) return;
        if (window.getComputedStyle(canvas).pointerEvents === 'none') {
            canvas.style.pointerEvents = 'auto';
            console.log('[奶蛙] 恢复 canvas 触摸');
        }
    }, 5000);
})();
""".trimIndent()

    /**
     * 同步器发送端：主窗口把触摸上报给原生，由原生广播给副窗口。
     *
     * 四个阶段都要发。原来只监听 touchstart，副窗口就只收到一个点，
     * 而游戏里主要靠按住拖动，结果是"同步开着但游戏不动"。
     */
    val SYNCER_SENDER = """
(function(){
    if(window.__syncerSenderInstalled) return;
    window.__syncerSenderInstalled = true;
    function send(e, phase){
        // 接收端合成的事件不要被本窗口的发送端再上报一次，否则会形成回环。
        // 现在只有主窗口会上报，本来也挡得住；这里是显式护栏，别改成靠单点判断。
        if (window.__syncing) return;
        var t = (e.changedTouches && e.changedTouches[0]) ||
                (e.touches && e.touches[0]);
        if (!t) return;
        try {
            AndroidBridge.onSyncTouch(JSON.stringify({
                x: t.clientX, y: t.clientY, phase: phase, t: Date.now()
            }));
        } catch(err) {}
    }
    // capture 阶段拦截，确保在游戏 stopPropagation 之前捕获
    var opts = { capture: true, passive: true };
    document.addEventListener('touchstart',  function(e){ send(e, 'start');  }, opts);
    document.addEventListener('touchmove',   function(e){ send(e, 'move');   }, opts);
    document.addEventListener('touchend',    function(e){ send(e, 'end');    }, opts);
    document.addEventListener('touchcancel', function(e){ send(e, 'cancel'); }, opts);
    console.log('[奶蛙] 同步器(发送端)已启用');
})();
""".trimIndent()

    /**
     * 同步器接收端：副窗口按收到的阶段合成触摸序列。
     *
     * 同一次触摸要跨阶段保持同一个 identifier，move/end 才能接上；
     * 每帧换 identifier 会被游戏当成全新的手势，拖动就断了。
     */
    val SYNCER_RECEIVER = """
(function(){
    if(window.__syncerRecvInstalled) return;
    window.__syncerRecvInstalled = true;

    var seq = 0;
    var active = null;      // 当前这次触摸：{ id, x, y }
    var activeEl = null;

    function host(){
        return document.getElementById('GameCanvas') ||
               document.getElementById('Cocos2dGameContainer') || document.body;
    }
    // Cocos2D 监听 canvas 上的 touch 事件，需要创建合法 Touch 对象
    function makeTouch(el, x, y, id){
        try {
            return new Touch({ identifier: id, target: el, clientX: x, clientY: y,
                               pageX: x, pageY: y, screenX: x, screenY: y });
        } catch(e) {
            return { identifier: id, target: el, clientX: x, clientY: y,
                     pageX: x, pageY: y, screenX: x, screenY: y };
        }
    }
    function fire(type, el, x, y, id, isEnd){
        var t = makeTouch(el, x, y, id);
        var live = isEnd ? [] : [t];
        var ev;
        try {
            ev = new TouchEvent(type, { touches: live, targetTouches: live,
                changedTouches: [t], bubbles: true, cancelable: true, view: window });
        } catch(e) {
            ev = document.createEvent('Event');
            ev.initEvent(type, true, true);
            ev.touches = live; ev.targetTouches = live; ev.changedTouches = [t];
        }
        // 标记为"同步合成"，发送端据此忽略，避免两个窗口互相回环
        window.__syncing = true;
        try { el.dispatchEvent(ev); } finally { window.__syncing = false; }
    }

    window.__applySyncTouch = function(x, y, phase){
        var el = host();
        if (!el) return;
        phase = phase || 'start';
        if (phase === 'start') {
            active = { id: ++seq, x: x, y: y };
            activeEl = el;
            fire('touchstart', el, x, y, active.id, false);
        } else if (phase === 'move') {
            // 没收到起点就忽略，免得留下半截手势
            if (!active) return;
            active.x = x; active.y = y;
            fire('touchmove', activeEl || el, x, y, active.id, false);
        } else {
            // 抬起用发送端给的落点（和真实手势一致），identifier 沿用这一次触摸的
            var id = active ? active.id : ++seq;
            fire(phase === 'cancel' ? 'touchcancel' : 'touchend',
                 activeEl || el, x, y, id, true);
            active = null; activeEl = null;
        }
    };
    console.log('[奶蛙] 同步器(接收端)已启用');
})();
""".trimIndent()

    /**
     * 标签模式下给非激活窗口降帧，省电减热。
     * 只动渲染帧率，setInterval 与事件 hook 不受影响，
     * 所以脚本逻辑照常运行（那几个内置脚本都不依赖帧率）。
     */
    fun setFrameRate(fps: Int): String = """
(function(){
  try {
    if (window.cc && cc.game && cc.game.setFrameRate) {
      cc.game.setFrameRate($fps);
      console.log('[奶蛙] 帧率切换为 $fps');
    }
  } catch(e) {}
})();
""".trimIndent()

    /** 内存告警时通知 JS 降频 + GC */
    val LOW_MEMORY = """
(function(){
    try {
        if (window.cc && cc.game) { cc.game.setFrameRate(30); }
        if (window.gc) window.gc();
        console.log('[奶蛙] 收到内存警告，已降频');
    } catch(e) {}
})();
""".trimIndent()

    /** 把选中账号的 bin 写入 window.__activeBinHex */
    fun activateBin(hex: String, label: String): String = """
(function(){
    window.__activeBinHex = '$hex';
    console.log('[奶蛙] 已注入BIN数据:', '$label', ${hex.length / 2});
})();
""".trimIndent()
}
