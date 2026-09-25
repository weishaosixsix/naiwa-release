package com.sharkking.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `tag 带 v 前缀与不带解析一致`() {
        assertEquals(
            UpdateChecker.parseVersionCode("1.0.95"),
            UpdateChecker.parseVersionCode("v1.0.95"),
        )
        assertEquals(
            UpdateChecker.parseVersionCode("1.0.95"),
            UpdateChecker.parseVersionCode("V1.0.95"),
        )
    }

    @Test
    fun `版本号大小关系正确`() {
        val v = UpdateChecker::parseVersionCode
        assertTrue(v("v1.0.95") > v("v1.0.94"))
        assertTrue(v("v1.1.0") > v("v1.0.99"))
        assertTrue(v("v2.0.0") > v("v1.99.99"))
        assertTrue(v("v1.0.100") > v("v1.0.99"))
    }

    @Test
    fun `相同版本不算新版`() {
        assertEquals(
            UpdateChecker.parseVersionCode("v1.0.95"),
            UpdateChecker.parseVersionCode("1.0.95"),
        )
    }

    @Test
    fun `缺省的段按 0 处理`() {
        val v = UpdateChecker::parseVersionCode
        assertEquals(v("v1.0.0"), v("v1"))
        assertEquals(v("v1.2.0"), v("v1.2"))
    }

    @Test
    fun `非法 tag 不抛异常`() {
        assertEquals(0, UpdateChecker.parseVersionCode(""))
        assertEquals(0, UpdateChecker.parseVersionCode("latest"))
        assertEquals(0, UpdateChecker.parseVersionCode("   "))
    }

    @Test
    fun `镜像列表以直连结尾且不重复`() {
        val raw = "https://github.com/o/r/releases/download/v1.0.95/zaipan.apk"
        val urls = UpdateChecker.mirrorUrls(raw)
        assertTrue("至少要有一个镜像加直连", urls.size >= 2)
        assertEquals("最后一项必须是原始直连", raw, urls.last())
        assertEquals("不应有重复通道", urls.size, urls.distinct().size)
    }

    @Test
    fun `每个镜像地址都完整包含原始直链`() {
        val raw = "https://github.com/o/r/releases/download/v1.0.95/zaipan.apk"
        UpdateChecker.mirrorUrls(raw).forEach { u ->
            assertTrue("$u 未包含原始直链", u.endsWith(raw))
            assertTrue("$u 不是 https", u.startsWith("https://"))
        }
    }

    @Test
    fun `首选地址是加速通道而非直连`() {
        val raw = "https://github.com/o/r/releases/download/v1.0.95/zaipan.apk"
        val first = UpdateChecker.preferredUrl(raw)
        assertTrue("首选应该带加速前缀", first.length > raw.length)
        assertTrue(first.endsWith(raw))
    }

    @Test
    fun `大小文案在未知时不显示数字`() {
        val info = ReleaseInfo("1.0.95", 1_000_095, "", "https://x/y.apk", 0)
        assertEquals("未知大小", info.sizeText)
    }

    @Test
    fun `大小文案按 MB 展示`() {
        val info = ReleaseInfo("1.0.95", 1_000_095, "", "https://x/y.apk", 12_310_000)
        assertTrue("实际为 ${info.sizeText}", info.sizeText.startsWith("11.7"))
        assertTrue(info.sizeText.endsWith("MB"))
    }

    // ---- 国内兜底:latest.json 解析(api 被墙时的检查通道) ----

    @Test
    fun `latest json 解析出版本与下载地址`() {
        val info = UpdateChecker.parseLatestJson(
            """{"versionName":"1.2.0","sizeBytes":12238411,"notes":"更新说明"}""",
        ) ?: throw AssertionError("合法 json 不应解析失败")
        assertEquals("1.2.0", info.versionName)
        assertEquals(UpdateChecker.parseVersionCode("1.2.0"), info.versionCode)
        assertEquals("更新说明", info.notes)
        assertEquals(12238411L, info.sizeBytes)
        assertTrue(
            "rawUrl 应指向固定的最新附件地址",
            info.rawUrl.endsWith("/releases/latest/download/yuduoduo-latest.apk"),
        )
    }

    @Test
    fun `latest json 的版本名带 v 前缀也能解析`() {
        val info = UpdateChecker.parseLatestJson("""{"versionName":"v1.2.0"}""")!!
        assertEquals("1.2.0", info.versionName)
    }

    @Test
    fun `latest json 缺版本名或损坏时返回 null 而非抛异常`() {
        assertEquals(null, UpdateChecker.parseLatestJson("""{"sizeBytes":1}"""))
        assertEquals(null, UpdateChecker.parseLatestJson("""{"versionName":""}"""))
        assertEquals(null, UpdateChecker.parseLatestJson("not json at all"))
        assertEquals(null, UpdateChecker.parseLatestJson(""))
    }
}
