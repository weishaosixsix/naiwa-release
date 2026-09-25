package com.sharkking.assistant.core

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

private const val TAG = "奶蛙"

/** 游戏资源包所在的 CDN 主机,只缓存它的资源树 */
private const val ASSET_HOST = "xxz-xyzw-res.hortorgames.com"

/** 主窗口触摸事件回调，用于同步器广播。phase 为 start/move/end/cancel */
typealias SyncTouchListener = (x: Float, y: Float, phase: String) -> Unit

/**
 * 游戏容器，对应 iOS 版的 GameWebView + Coordinator。
 * 不包含连点器。
 */
@SuppressLint("SetJavaScriptEnabled")
class GameWebViewHolder(
    private val ctx: Context,
    private val binHex: String,
    private val binLabel: String,
    private val scriptsProvider: () -> List<Triple<String, String, String>>,
    /**
     * 实时读取同步器开关，不能在构造时快照。
     * 开关可能在窗口开好之后才打开，快照值会让它永远看不到变更。
     */
    private val syncEnabledProvider: () -> Boolean,
    private val onSyncTouch: SyncTouchListener?,
    private val onScriptStatus: ((String) -> Unit)? = null,
) {
    val webView: WebView = WebView(ctx)
    private var pageReady = false
    private var backgrounded = false
    /** 渲染进程已终止，此 WebView 不可再用 */
    private var renderGone = false

    private companion object {
        const val BG_FPS = 5
        const val FG_FPS = 60
    }

    init {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            // 多开时视口变小，让页面按容器宽度自适应缩放而非横向裁切
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // 保持默认的缓存语义。
            //
            // 不要改成 LOAD_CACHE_ELSE_NETWORK：那是整个 WebView 的策略，
            // 连接口请求的 GET 也会被冻结成旧响应 —— 启动时要拿的加密码本
            // 拿了旧的，登录就和服务端对不上，游戏会卡在"正在加载平台"。
            // 资源包的"下一次就不用再下"由 AssetCache 精确处理，只拦那一棵
            // 版本化的资源树。
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
    }

    fun load(url: String) {
        Log.i(TAG, "HTTP加载: $url")
        webView.loadUrl(url)
    }

    fun destroy() {
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
    }

    fun reload() {
        Log.i(TAG, "收到刷新通知")
        pageReady = false
        // 页面重载后 window 上下文重建，已注入记录随之失效，无需手动清理
        webView.reload()
    }

    fun clearCache() {
        webView.clearCache(true)
        Log.i(TAG, "缓存已清除")
    }

    fun onLowMemory() {
        eval(InjectScripts.LOW_MEMORY)
    }

    /**
     * 标签模式下切到后台的窗口降到 5 帧，切回前台恢复。
     * 游戏逻辑与脚本靠 setInterval / 事件驱动，不受渲染帧率影响。
     */
    fun setBackgrounded(background: Boolean) {
        if (renderGone) return
        if (backgrounded == background) return
        backgrounded = background
        // INVISIBLE 保留布局尺寸（不触发 WebView 重排导致画面错乱），
        // 但完全跳过绘制：后台窗口不再占用绘制与合成开销，
        // JS 与定时器照常运行，后台账号不掉线。
        // 不动 layerType：软件层会让 WebGL 上下文丢失，画面会废掉。
        webView.visibility =
            if (background) android.view.View.INVISIBLE else android.view.View.VISIBLE
        if (!pageReady) return
        eval(InjectScripts.setFrameRate(if (background) BG_FPS else FG_FPS))
    }

    /**
     * 本窗口收到别的窗口同步过来的触摸：按阶段合成触摸事件。
     *
     * 必须 post 到 UI 线程。onSyncTouch 是 @JavascriptInterface 回调，运行在
     * WebView 的 JavaBridge 线程上，而 evaluateJavascript 只能在创建 WebView 的
     * 那个线程调用，否则抛「All WebView methods must be called on the same
     * thread」—— 而 eval 里的 runCatching 把这个异常吞掉了，表现就是同步器
     * 完全没有反应：广播执行了，接收端却一次都没被调用到。
     * 同一个类里其他桥接方法（gmRequest / onScriptStatus）都写了 webView.post，
     * 只有这里漏了。
     */
    fun applySyncTouch(x: Float, y: Float, phase: String) {
        webView.post {
            if (!pageReady) return@post
            eval("window.__applySyncTouch && window.__applySyncTouch($x, $y, '$phase');")
        }
    }

    /**
     * 装载同步器。
     *
     * 开关可能在窗口已经开好之后才打开，所以必须能随时补装。原来只在
     * onPageFinished 那个时机判断一次，用户开了开关也没有人重新注入，
     * 表现就是「多开时同步完全没反应」。注入脚本自带幂等标记，重复调用安全。
     */
    fun injectSyncer() {
        if (renderGone || !pageReady) return
        if (!syncEnabledProvider()) return
        // 两端都装：发送端负责上报，接收端负责合成，任意窗口两种都会用上
        eval(InjectScripts.SYNCER_SENDER)
        eval(InjectScripts.SYNCER_RECEIVER)
        Log.i(TAG, "同步器已注入")
    }

    private fun eval(js: String) {
        if (renderGone) return
        // 不要把失败吞掉：这里的异常曾经让"同步器没反应"查了很久查不出来
        runCatching { webView.evaluateJavascript(js, null) }
            .onFailure { Log.w(TAG, "evaluateJavascript 失败: ${it.message}") }
    }

    /** 页面开始加载时就要注入的脚本，越早越好 */
    private fun injectEarly() {
        eval(InjectScripts.COMPAT)
        // 油猴 API 必须在任何用户脚本之前就位：脚本拿不到 GM_* 会静默退出
        eval(InjectScripts.GM_SHIM)
        eval(InjectScripts.activateBin(binHex, binLabel))
        eval(InjectScripts.XHR_INTERCEPT)
        Log.i(TAG, "BIN数据注入成功")
    }

    /** 页面加载完成后注入 */
    private fun injectLate() {
        // 兜一次：onPageStarted 时注入的可能被页面导航清掉，
        // 而用户脚本是在这之后才装的，GM_* 必须存在
        eval(InjectScripts.GM_SHIM)
        // 必须在用户脚本之前：VH_FIX 用 MutationObserver 盯新样式表，
        // 装晚了先注入的脚本样式就漏掉了
        eval(InjectScripts.VH_FIX)
        eval(InjectScripts.SCRIPT_UI_FIX)
        eval(InjectScripts.CANVAS_GUARD)
        injectSyncer()
        injectUserScripts()
        // 页面就绪前的降帧请求会被忽略，这里补上
        if (backgrounded) eval(InjectScripts.setFrameRate(BG_FPS))
    }

    /**
     * 注入用户脚本。内容通过 provider 实时读取而非构造时快照，
     * 因此脚本页改开关后无需重开窗口。
     *
     * 关键点：这些脚本依赖 window.__require，而它由游戏主包（从 CDN 拉的
     * game bundle）在运行时创建，onPageFinished 时通常还不存在。所以先
     * 轮询等待 __require 就绪再注入，而不是立刻执行。
     */
    fun injectUserScripts() {
        if (renderGone) {
            onScriptStatus?.invoke("渲染已终止，请刷新")
            return
        }
        if (!pageReady) {
            onScriptStatus?.invoke("页面未就绪")
            return
        }
        val list = scriptsProvider()
        if (list.isEmpty()) {
            onScriptStatus?.invoke("无启用脚本")
            return
        }

        // 落盘后用 <script src> 加载，不把代码塞进 evaluateJavascript。
        // 后者走 Binder（约 1MB 上限），大脚本 base64 后会超限被静默截断，
        // 表现就是脚本一直不显示。
        val published = ScriptCache.publish(File(ctx.cacheDir, "renderer"), list)
        if (published.isEmpty()) {
            onScriptStatus?.invoke("脚本写入失败")
            return
        }

        // 逐个脚本独立注入并按 id 记录，已注入过的永不重复执行。
        // 之前整批拼成一个字符串共用一个标志，手动重注会把所有脚本
        // 重跑一遍——像自动蟠桃这类自身没做防重保护的脚本就会重复建 UI。
        val entries = published.joinToString(",") { (id, name, url) ->
            "{id:${jsStr(id)},name:${jsStr(name)},url:${jsStr(url)}}"
        }

        val js = """
(function(){
  var items = [$entries];
  window.__injectedScriptIds = window.__injectedScriptIds || {};
  var done = window.__injectedScriptIds;
  var live = {};
  items.forEach(function(it){ live[it.id] = 1; });
  // 已注入但现在被关掉的脚本：JS 执行过就无法撤销，只能刷新页面。
  // 明确告知而不是让用户反复点补注入却看不出变化。
  var stale = 0;
  for (var k in done) { if (done[k] && !live[k]) stale++; }
  var pending = items.filter(function(it){ return !done[it.id]; });
  if (pending.length === 0) {
    AndroidBridge.onScriptStatus(
      stale ? ('已关闭' + stale + '个，需刷新生效') : ('已全部注入 (' + items.length + ')')
    );
    return stale ? 'need_reload' : 'already';
  }
  // 按 src 逐个加载。串行执行是必须的：脚本之间可能有依赖，
  // 并行加载完成顺序不确定。
  function runAll(){
    var ok = 0, fail = 0, i = 0;
    function report(){
      AndroidBridge.onScriptStatus(
        '已注入 ' + ok + '/' + items.length +
        (fail ? (' 失败' + fail) : '') +
        (stale ? (' 关闭' + stale + '需刷新') : '')
      );
    }
    function next(){
      if (i >= pending.length) { report(); return; }
      var it = pending[i++];
      if (done[it.id]) { next(); return; }
      done[it.id] = true;    // 先置位，脚本内部报错也不重复执行
      var el = document.createElement('script');
      el.type = 'text/javascript';
      el.async = false;
      el.src = it.url;
      el.onload = function(){
        ok++;
        el.parentNode && el.parentNode.removeChild(el);
        next();
      };
      el.onerror = function(){
        fail++;
        // 加载失败要撤销标记，否则「补注入」会永远跳过这个脚本。
        // 与执行报错不同：文件没取到，脚本一行都没跑，重试是安全的。
        delete done[it.id];
        console.error('[奶蛙] 脚本加载失败: ' + it.name + ' <- ' + it.url);
        el.parentNode && el.parentNode.removeChild(el);
        next();
      };
      document.head.appendChild(el);
      // 每装一个就刷一次状态，大脚本加载慢时能看到进度
      if (i % 3 === 0) report();
    }
    next();
  }
  var tries = 0;
  function wait(){
    tries++;
    if (typeof window.__require === 'function') { runAll(); return; }
    if (tries > 240) {
      AndroidBridge.onScriptStatus('超时: 游戏模块未就绪，仍尝试注入');
      runAll();
      return;
    }
    if (tries % 20 === 0) {
      AndroidBridge.onScriptStatus('等待游戏加载... ' + Math.round(tries / 2) + 's');
    }
    setTimeout(wait, 500);
  }
  wait();
  return 'waiting:' + pending.length;
})();
""".trimIndent()
        runCatching {
            webView.evaluateJavascript(js) { Log.i(TAG, "脚本注入流程: $it") }
        }
    }

    private fun jsStr(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\r", "").replace("\n", "\\n") + "'"

    /** 在后台线程执行 GM_xmlhttpRequest，返回给 JS 的结果对象 */
    private fun doGmRequest(optionsJson: String): org.json.JSONObject {
        val o = org.json.JSONObject(optionsJson)
        val url = o.getString("url")
        val method = o.optString("method", "GET").uppercase()
        val body = if (o.isNull("data")) null else o.optString("data")

        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = o.optInt("timeout", 20000).coerceAtLeast(1000)
            readTimeout = connectTimeout
            instanceFollowRedirects = true
            o.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> setRequestProperty(k, h.optString(k)) }
            }
            if (body != null) {
                doOutput = true
                if (getRequestProperty("Content-Type") == null) {
                    setRequestProperty("Content-Type", "application/json;charset=utf-8")
                }
            }
        }

        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            // 4xx/5xx 的内容在 errorStream 里，脚本往往要读错误详情
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = StringBuilder()
            conn.headerFields.forEach { (k, v) ->
                if (k != null) headers.append(k).append(": ").append(v.joinToString(", ")).append("\r\n")
            }
            return org.json.JSONObject().apply {
                put("status", code)
                put("statusText", conn.responseMessage ?: "")
                put("responseText", text)
                put("responseHeaders", headers.toString())
                put("finalUrl", conn.url.toString())
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private inner class Client : WebViewClient() {

        /**
         * 游戏资源包的磁盘缓存:只拦 xxz-xyzw-res.hortorgames.com 下的资源请求。
         *
         * 游戏启动要拉两百多个文件(主包单个 16.8MB)。这些 URL 是版本化的
         * (remote/game/index.c2e0f.jsc),内容一变名字就变,所以缓存下来
         * 既不会拿到旧资源,又能省掉重复下载 —— 第二个窗口、重开窗口、
         * 重启应用都直接走本地磁盘,不再消耗流量。
         *
         * 游戏用自己的加载器(基于 XHR)取这些资源,页面与 CDN 是跨域的,
         * 所以合成响应**必须带 Access-Control-Allow-Origin** —— 上一版就是
         * 因为没带这个头,XHR 全被同源策略挡掉,游戏卡在加载
         * (桌面对照实验证实:同一响应,带头 XHR 成功,不带头被拦)。
         *
         * 任何异常都返回 null 交给系统正常加载,绝不把加载路径堵死。
         */
        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            val sub = assetSubPath(url) ?: return null
            val r = AssetCache.fetch(File(ctx.cacheDir, "gamecache"), url, sub)
            val file = r.file ?: run {
                Log.w(TAG, "资源未命中且取回失败(状态 ${r.status}): $sub")
                return null
            }
            // 命中/入库各记一条:拦截是否生效、缓存是否在省流量,看日志就知道
            Log.i(TAG, "资源缓存: $sub (${file.length() / 1024} KB)")
            return WebResourceResponse(
                r.contentType ?: "application/octet-stream",
                null,
                r.status,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                file.inputStream(),
            )
        }

        /** CDN 资源树内的路径;其余(接口、带查询串的)一律返回 null 不拦 */
        private fun assetSubPath(url: String): String? {
            val prefix = "https://$ASSET_HOST/"
            if (!url.startsWith(prefix) || url.contains('?')) return null
            val path = url.substring(prefix.length)
            if (path.isEmpty() || path.contains("..")) return null
            return if (path.all { it.isLetterOrDigit() || it in "._-/+" }) path else null
        }

        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
            injectEarly()
        }

        override fun onPageFinished(v: WebView?, url: String?) {
            pageReady = true
            injectLate()
        }

        /**
         * 渲染进程崩溃。不重写这个方法的话，渲染进程一挂会直接带崩
         * 整个 App —— 表现就是秒闪退，且 Java 层抓不到任何异常。
         * 返回 true 表示我们已自行处理，App 得以存活。
         */
        override fun onRenderProcessGone(
            v: WebView?, detail: android.webkit.RenderProcessGoneDetail?,
        ): Boolean {
            val crashed = detail?.didCrash() == true
            renderGone = true
            pageReady = false
            // 崩掉的 WebView 已不可用，必须移除，否则再碰它就会抛异常
            runCatching {
                (v?.parent as? android.view.ViewGroup)?.removeView(v)
                v?.destroy()
            }
            onScriptStatus?.invoke(if (crashed) "渲染崩溃" else "内存不足被回收")
            return true
        }

        override fun onReceivedError(
            v: WebView?, req: WebResourceRequest?, err: WebResourceError?,
        ) {
            if (req?.isForMainFrame == true) {
                Log.w(TAG, "页面加载失败: ${err?.description}")
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
            m ?: return false
            val tag = when (m.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "JS错误"
                ConsoleMessage.MessageLevel.WARNING -> "JS警告"
                else -> "JS"
            }
            Log.d(TAG, "$tag: ${m.message()}")
            return true
        }
    }

    /** JS 可调用的原生能力 */
    private inner class Bridge {

        /**
         * 同步器广播：把本窗口的触摸转发给其余窗口。
         *
         * 这里原来还有一道 `!syncMaster` 判断，只有"主窗口"会上报 —— 而主窗口
         * 只是最先打开的那个，界面上仅靠标题栏一个小标记辨认。点在别的窗口上
         * 毫无反应，用户只会得出"同步器没用"的结论（实测就是这样）。
         *
         * 现在任意窗口都能驱动同步。收到同步事件的窗口合成出来的触摸会被
         * __syncing 护栏挡住，不会再广播回去，所以不存在回环。
         */
        @JavascriptInterface
        fun onSyncTouch(json: String) {
            if (!syncEnabledProvider()) return
            runCatching {
                val o = org.json.JSONObject(json)
                onSyncTouch?.invoke(
                    o.getDouble("x").toFloat(),
                    o.getDouble("y").toFloat(),
                    o.optString("phase", "start"),
                )
            }
        }

        @JavascriptInterface
        fun onScriptStatus(msg: String) {
            Log.i(TAG, "脚本状态: $msg")
            webView.post { onScriptStatus?.invoke(msg) }
        }

        /**
         * GM_xmlhttpRequest 的原生转发。
         *
         * 油猴脚本靠它做跨域请求，而页面内的 XHR/fetch 受同源策略限制
         * 打不到外部域名。放到原生侧发就没有跨域概念，这也是油猴本身
         * 的实现方式。
         */
        @JavascriptInterface
        fun gmRequest(reqId: String, optionsJson: String) {
            Thread {
                val result = runCatching { doGmRequest(optionsJson) }
                val payload = result.getOrElse { e ->
                    org.json.JSONObject().apply {
                        put("error", e.message ?: e.javaClass.simpleName)
                        put("status", 0)
                    }
                }
                val js = "window.__gmResolve && window.__gmResolve(" +
                    "${jsStr(reqId)}, ${jsStr(payload.toString())})"
                webView.post { runCatching { webView.evaluateJavascript(js, null) } }
            }.start()
        }

        @JavascriptInterface
        fun setClipboard(text: String) {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("xuebi", text))
                toast("复制成功")
            }
        }

        @JavascriptInterface
        fun saveImage(dataUrl: String) {
            runCatching {
                val b64 = dataUrl.substringAfter("base64,", dataUrl)
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: run {
                        Log.w(TAG, "saveImage: 无法创建 Bitmap")
                        return
                    }
                saveBitmap(bmp)
                Log.i(TAG, "saveImage: 图片已保存到相册")
                toast("已保存到相册")
            }.onFailure { Log.w(TAG, "saveImage: base64 解码失败 ${it.message}") }
        }

        /** Android 10+ 走 MediaStore 插入，低版本回落到 legacy 相册 API */
        private fun saveBitmap(bmp: Bitmap) {
            val name = "xuebi_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/再攀之王")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } else {
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES
                    ),
                    "再攀之王"
                ).apply { mkdirs() }
                File(dir, name).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }

        private fun toast(s: String) {
            webView.post { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
        }
    }
}
