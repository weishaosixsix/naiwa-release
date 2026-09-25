package com.sharkking.assistant.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 游戏资源包的磁盘缓存。
 *
 * 游戏每次启动要从 CDN 拉两百多个文件（主包单个就 16.8MB），但它们的 URL 都是
 * **版本化**的（`remote/game/index.c2e0f.jsc` 这种），内容变了文件名就变 ——
 * 所以这些内容可以永久留在本地，下次直接用，既不重复下也不再产生校验往返。
 *
 * 只处理这一棵资源树。接口请求（登录取码本、清单、开关状态）绝不经手：
 * 它们必须拿实时响应，缓存住了会直接卡住登录
 * （曾经把整个 WebView 的策略改成 LOAD_CACHE_ELSE_NETWORK，就是这么卡的）。
 *
 * 这个类不依赖任何 Android API，便于在 JVM 单测里直接验证。
 */
object AssetCache {

    /** 一次取回的结果。status 为 0 表示取不到（调用方应退回让系统正常加载） */
    class Result(val status: Int, val file: File?, val contentType: String?, val length: Long)

    /** 去重用的锁：同一资源可能被多个窗口同时请求，不串行化会重复下 16MB */
    private val locks = HashMap<String, Any>()

    private fun lockFor(key: String): Any = synchronized(locks) { locks.getOrPut(key) { Any() } }

    /**
     * 取回资源：命中磁盘缓存直接返回，否则下载并落盘。
     *
     * @param cacheRoot 缓存根目录
     * @param url       上游完整地址
     * @param subPath   缓存的相对路径（同时用作缓存键），已由调用方做过安全校验
     */
    fun fetch(cacheRoot: File, url: String, subPath: String): Result {
        val target = File(cacheRoot, subPath)

        cached(target)?.let { return it }

        synchronized(lockFor(subPath)) {
            // 等锁期间可能已被别的线程下好
            cached(target)?.let { return it }
            return download(url, target, subPath)
        }
    }

    private fun cached(target: File): Result? =
        if (target.isFile && target.length() > 0) {
            Result(200, target, contentTypeOf(target.name), target.length())
        } else {
            null
        }

    private fun download(url: String, target: File, subPath: String): Result {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                // 上游不是 200 就原样透传，不落盘（比如 404，下一次还得问服务器）
                Result(code, null, null, 0)
            } else {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                // 先写临时文件再改名：中途失败不会留下半截文件被当成长效缓存
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                Result(200, target, conn.contentType ?: contentTypeOf(subPath), target.length())
            }
        } catch (e: Exception) {
            Result(0, null, null, 0)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 上游没给 content-type 时按扩展名兜底 */
    fun contentTypeOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "js" -> "application/javascript"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "wasm" -> "application/wasm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
}
