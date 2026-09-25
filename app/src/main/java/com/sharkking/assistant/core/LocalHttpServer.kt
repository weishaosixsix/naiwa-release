package com.sharkking.assistant.core

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对应 iOS 版的 LocalHTTPServer。
 *
 * 游戏必须通过 http:// 而非 file:// 加载：Cocos 引擎会做跨域检查，
 * 且 WebGL 上下文在 file:// 下受限。这里起一个只监听回环地址的
 * 极简 HTTP 服务，把解包后的 renderer/ 目录当静态根目录提供。
 */
class LocalHttpServer(private val rootDir: File) {

    companion object {
        private const val TAG = "奶蛙-HTTP"

        /** 用户脚本自带后端的典型路径，命中说明脚本把请求发错了地方 */
        private val AUTH_HINT =
            Regex("""device-auth|card-keys|device-config|licen[cs]e|/auth/""", RegexOption.IGNORE_CASE)

        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json",
            "wasm" to "application/wasm",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "ttf" to "font/ttf",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
        )
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    var port: Int = 0
        private set

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    fun start(): String? {
        if (!rootDir.isDirectory) {
            Log.e(TAG, "目录不存在: ${rootDir.absolutePath}")
            return null
        }
        return try {
            // 端口传 0 让系统自动分配，避免固定端口被占用
            val loopback = InetAddress.getByName("127.0.0.1")
            serverSocket = ServerSocket(0, 8, loopback)
            port = serverSocket!!.localPort
            running.set(true)
            pool.execute { acceptLoop() }
            Log.i(TAG, "服务器已就绪: $baseUrl")
            baseUrl
        } catch (e: Exception) {
            Log.e(TAG, "无法创建监听器: ${e.message}")
            null
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
        Log.i(TAG, "服务器已停止")
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                pool.execute { handle(client) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "服务器失败: ${e.message}")
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val rawPath = parts[1].substringBefore('?').substringBefore('#')
                val path = URLDecoder.decode(rawPath, "UTF-8")
                serveFile(path, s.getOutputStream())
            } catch (e: Exception) {
                Log.w(TAG, "请求处理失败: ${e.message}")
            }
        }
    }

    private fun serveFile(path: String, out: OutputStream) {
        val rel = path.trimStart('/').ifEmpty { "index.html" }

        val target = File(rootDir, rel)

        // 防目录穿越：解析后的真实路径必须仍在根目录内
        val canonicalRoot = rootDir.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalRoot)) {
            Log.w(TAG, "路径越界拒绝: $path")
            writeStatus(out, 403, "Forbidden")
            return
        }
        if (!target.isFile) {
            // 脚本用 location.origin 拼接自己的后端地址时，请求会打到这里。
            // 这类路径不是缺文件，而是脚本在找它自己的服务器，单独记一条
            // 日志便于排查。
            if (AUTH_HINT.containsMatchIn(rel)) {
                Log.w(TAG, "脚本把后端请求发到了本地服务: /$rel")
            } else {
                Log.w(TAG, "文件未找到: $path")
            }
            writeStatus(out, 404, "Not Found")
            return
        }

        val ext = target.name.substringAfterLast('.', "").lowercase()
        val mime = MIME[ext] ?: "application/octet-stream"
        val length = target.length()

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $length\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            // 必须 no-store 而不是 no-cache：WebView 那边用的是
            // LOAD_CACHE_ELSE_NETWORK，连过期内容都直接用，而 no-cache 只是
            // 「要校验」，会被跳过。本地文件在升级后内容会变（renderer 与
            // userscripts），一旦被缓存就会加载到旧文件 —— 表现为白屏。
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        // 流式拷贝而不是 readBytes()：游戏资源和大脚本动辄几 MB，
        // 整份读进内存在多开时会叠加，容易触发 OOM 把渲染进程带崩。
        target.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        val header = "HTTP/1.1 $code $text\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }
}
