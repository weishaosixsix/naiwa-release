package com.sharkking.assistant.importer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 极简 HTTP 客户端。
 *
 * 用 HttpURLConnection 而不引 OkHttp：需求只是带自定义头发几个请求。
 * 关键点是必须能自由设置 Host / Referer / User-Agent —— 这些在
 * WebView 里是禁止修改头，所以登录流程不能放到 JS 侧做。
 */
object Http {

    class Response(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private const val TIMEOUT = 20000

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, headers, null)

    fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Response = request("POST", url, headers, body)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }

            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { readAll(it, conn.contentEncoding) } ?: ByteArray(0)
            return Response(status, raw, conn.headerFields ?: emptyMap())
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream, encoding: String?): ByteArray {
        val stream = if (encoding?.contains("gzip", true) == true) GZIPInputStream(input) else input
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
