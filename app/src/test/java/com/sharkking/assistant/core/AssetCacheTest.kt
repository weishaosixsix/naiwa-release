package com.sharkking.assistant.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 游戏资源包磁盘缓存的行为验证。
 *
 * 缓存直接决定"是不是每次进游戏都要重新下两百多个文件"，而它跑在加载路径上 ——
 * 弄错就是白屏或卡启动，所以这里用真实的 HTTP 往返测出来，不靠推断。
 *
 * 上游用一个手写的 ServerSocket（Android 单测类路径里没有 com.sun.net.httpserver），
 * 只够回一个最简 HTTP 响应。
 */
class AssetCacheTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var cacheRoot: File
    private val upstreamHits = AtomicInteger(0)

    @Before
    fun setUp() {
        cacheRoot = Files.createTempDirectory("assetcache-test").toFile()
        serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serverSocket.isClosed) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { serve(sock) }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
        cacheRoot.deleteRecursively()
    }

    /** 极简上游：/missing.json 回 404，其余回 200 + 一小段 body */
    private fun serve(sock: java.net.Socket) {
        sock.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            upstreamHits.incrementAndGet()

            val out = s.getOutputStream()
            if (path.endsWith("missing.json")) {
                out.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
            } else {
                val body = "body-of$path".toByteArray()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(body)
            }
            out.flush()
        }
    }

    private fun urlFor(sub: String) = "http://127.0.0.1:${serverSocket.localPort}/$sub"

    @Test
    fun 命中缓存后不再联网() {
        val sub = "remote/game/config.c2e0f.json"

        val first = AssetCache.fetch(cacheRoot, urlFor(sub), sub)
        assertEquals(200, first.status)
        assertEquals("第一次应该联网", 1, upstreamHits.get())
        assertTrue("应落盘", first.file?.isFile == true)
        assertEquals("body-of/$sub".length.toLong(), first.length)

        val second = AssetCache.fetch(cacheRoot, urlFor(sub), sub)
        assertEquals(200, second.status)
        assertEquals("第二次必须走磁盘缓存，不能再联网", 1, upstreamHits.get())
    }

    @Test
    fun 上游非200不落盘也不缓存() {
        val sub = "remote/missing.json"

        assertEquals(404, AssetCache.fetch(cacheRoot, urlFor(sub), sub).status)
        assertEquals(1, upstreamHits.get())

        AssetCache.fetch(cacheRoot, urlFor(sub), sub)
        assertEquals("404 不能缓存，下次还得问服务器", 2, upstreamHits.get())
    }

    @Test
    fun 不同路径各自独立缓存() {
        AssetCache.fetch(cacheRoot, urlFor("remote/a.json"), "remote/a.json")
        AssetCache.fetch(cacheRoot, urlFor("remote/b.json"), "remote/b.json")
        assertEquals(2, upstreamHits.get())

        AssetCache.fetch(cacheRoot, urlFor("remote/a.json"), "remote/a.json")
        AssetCache.fetch(cacheRoot, urlFor("remote/b.json"), "remote/b.json")
        assertEquals("都该命中缓存", 2, upstreamHits.get())
    }

    @Test
    fun 内容类型按扩展名兜底() {
        assertEquals("application/json", AssetCache.contentTypeOf("a/b.json"))
        assertEquals("application/javascript", AssetCache.contentTypeOf("index.5dadd.js"))
        assertEquals("image/png", AssetCache.contentTypeOf("icons/xx.png"))
        assertEquals("application/octet-stream", AssetCache.contentTypeOf("index.5dadd.jsc"))
    }
}
