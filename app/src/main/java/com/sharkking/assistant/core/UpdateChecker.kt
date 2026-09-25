package com.sharkking.assistant.core

import android.util.Log
import com.sharkking.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一次检查的结果 */
sealed interface UpdateResult {
    /** 有新版 */
    data class Available(val info: ReleaseInfo) : UpdateResult
    /** 已是最新 */
    data object UpToDate : UpdateResult
    /** 查不动（断网、被墙、接口限流），静默处理 */
    data class Failed(val reason: String) : UpdateResult
}

data class ReleaseInfo(
    /** 展示用版本名，如 1.0.95 */
    val versionName: String,
    /** 比对用的数值版本，由 tag 推导 */
    val versionCode: Int,
    /** 发布说明，即 release 正文 */
    val notes: String,
    /** GitHub 原始下载直链 */
    val rawUrl: String,
    /** 附件字节数，0 表示未知 */
    val sizeBytes: Long,
) {
    val sizeText: String
        get() = if (sizeBytes <= 0) "未知大小"
        else String.format("%.1f MB", sizeBytes / 1048576.0)
}

object UpdateChecker {
    private const val TAG = "再攀-更新"

    /**
     * 下载加速通道。国内直连 GitHub 常年几十 KB/s，镜像能快一到两个数量级。
     * 镜像随时可能失效，所以留多个按顺序兜底，最后一项是直连。
     */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "", // 直连兜底
    )

    /**
     * 国内兜底检查用的固定附件名。
     *
     * api.github.com 在国内被间歇性拦截，查不到就永远不提示更新，而下载本身
     * 走镜像是通的。所以每次 release 除 APK 外还固定带一个 latest.json
     * （版本名/大小/说明），检查失败时改走「镜像 + 固定地址」拿它：
     * GitHub 的 releases/latest/download/<名> 永远指向最新 release 的同名附件，
     * 三个镜像都已实测能代理这个地址（会跟随 302）。
     * 因此附件名必须跨版本不变，发版脚本 tools/publish_release.py 负责生成。
     */
    private const val APK_ASSET = "yuduoduo-latest.apk"
    private const val LATEST_JSON = "latest.json"

    private fun latestAssetUrl(asset: String): String =
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/$asset"

    /** 把 tag 或版本名解析成可比较的整数。1.0.95 -> 10095 */
    fun parseVersionCode(raw: String): Int {
        val nums = Regex("\\d+").findAll(raw.trim().removePrefix("v").removePrefix("V"))
            .map { it.value.toIntOrNull() ?: 0 }
            .toList()
        if (nums.isEmpty()) return 0
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 1_000_000 + minor * 10_000 + patch
    }

    /** 本机版本，与 parseVersionCode 同一量纲 */
    fun currentVersionCode(): Int = parseVersionCode(BuildConfig.VERSION_NAME)

    /** 给下载直链套上加速前缀 */
    fun mirrorUrls(rawUrl: String): List<String> =
        MIRRORS.map { if (it.isEmpty()) rawUrl else it + rawUrl }

    /** 首选下载地址 */
    fun preferredUrl(rawUrl: String): String = mirrorUrls(rawUrl).first()

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching { fetchLatest() }
            .fold(
                onSuccess = { info ->
                    if (info == null) UpdateResult.Failed("没有找到可用的发布")
                    else if (info.versionCode > currentVersionCode()) UpdateResult.Available(info)
                    else UpdateResult.UpToDate
                },
                onFailure = { e ->
                    Log.w(TAG, "检查更新失败", e)
                    UpdateResult.Failed(e.message ?: "网络异常")
                },
            )
    }

    private fun fetchLatest(): ReleaseInfo? {
        // 先走官方 API；api.github.com 在国内常被拦，失败再走镜像的固定附件地址
        fetchLatestFromApi()?.let { return it }
        return fetchLatestViaAsset()
    }

    private fun fetchLatestFromApi(): ReleaseInfo? {
        val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        val body = httpGet(api) ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = obj.optString("tag_name").ifBlank { return null }

        val assets = obj.optJSONArray("assets")
        var url = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
        }
        if (url.isBlank()) return null

        return ReleaseInfo(
            versionName = obj.optString("name").ifBlank { tag }.removePrefix("v"),
            versionCode = parseVersionCode(tag),
            notes = obj.optString("body").trim(),
            rawUrl = url,
            sizeBytes = size,
        )
    }

    /**
     * 国内兜底：镜像 + 固定附件地址拿 latest.json。
     * 依赖发版时带上了这个附件（见类顶注释），没有就查不到，属发布方失误。
     */
    private fun fetchLatestViaAsset(): ReleaseInfo? {
        val jsonUrl = latestAssetUrl(LATEST_JSON)
        for (prefix in MIRRORS) {
            val body = httpGet(prefix + jsonUrl) ?: continue
            return parseLatestJson(body) ?: continue
        }
        return null
    }

    /** 解析 latest.json。纯函数便于单测。 */
    fun parseLatestJson(body: String): ReleaseInfo? = runCatching {
        val o = JSONObject(body)
        val ver = o.optString("versionName").ifBlank { return null }
        ReleaseInfo(
            versionName = ver.removePrefix("v"),
            versionCode = parseVersionCode(ver),
            notes = o.optString("notes"),
            rawUrl = latestAssetUrl(APK_ASSET),
            sizeBytes = o.optLong("sizeBytes"),
        )
    }.getOrNull()

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "zaipan-updater")
        }
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "接口返回 ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }
}
