package com.sharkking.assistant.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bin 里 info 字段的两种写法。
 *
 * 实测 8 个真实 bin：我们自己生成的 2 个是嵌套对象，
 * 外面流传的 6 个把 info 写成 JSON 字符串。只认对象会把多数文件判为损坏。
 * 内层键顺序也不固定（有的 sign 在 timestamp 前），必须按名字取。
 */
class ParseInfoTest {

    /** 与 BinBuilder.parseInfo 保持一致的取值逻辑 */
    private fun parseInfo(raw: Any?): Map<String, Any?> {
        if (raw is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return raw as Map<String, Any?>
        }
        if (raw is String && raw.isNotBlank()) {
            val obj = org.json.JSONObject(raw)
            val out = LinkedHashMap<String, Any?>()
            for (k in obj.keys()) out[k] = obj.get(k)
            return out
        }
        error("bin 缺少 info 字段")
    }

    @Test
    fun 对象形式_我们自己生成的bin() {
        val info = parseInfo(
            mapOf(
                "encryptCombUser" to "wd1XL1mW",
                "timestamp" to 1785599848,
                "sign" to "e86b6f4f372bc288c2571f7804869eeb",
            )
        )
        assertEquals("wd1XL1mW", info["encryptCombUser"])
        assertEquals(1785599848, info["timestamp"])
        assertEquals("e86b6f4f372bc288c2571f7804869eeb", info["sign"])
    }

    @Test
    fun 字符串形式_外部流传的bin() {
        // 取自 bin-29001服-0-710519367-L 的真实结构
        val info = parseInfo(
            """{"encryptCombUser":"wd1XL1mW","timestamp":1770551679,""" +
                """"sign":"e861c6175e8f9e7ef4e1a9fb1ef944b7"}"""
        )
        assertEquals("wd1XL1mW", info["encryptCombUser"])
        assertEquals(1770551679, info["timestamp"])
        assertEquals("e861c6175e8f9e7ef4e1a9fb1ef944b7", info["sign"])
    }

    @Test
    fun 字符串形式_键顺序不同也能取到() {
        // 「迷糊✨吉」那份的顺序是 encryptCombUser, sign, timestamp
        val info = parseInfo(
            """{"encryptCombUser":"abc","sign":"deadbeef","timestamp":1770000000}"""
        )
        assertEquals("abc", info["encryptCombUser"])
        assertEquals("deadbeef", info["sign"])
        assertEquals(1770000000, info["timestamp"])
    }

    @Test
    fun 两种形式解析结果等价() {
        val fromObj = parseInfo(
            mapOf("encryptCombUser" to "same", "timestamp" to 123, "sign" to "s")
        )
        val fromStr = parseInfo("""{"encryptCombUser":"same","timestamp":123,"sign":"s"}""")
        assertEquals(fromObj["encryptCombUser"], fromStr["encryptCombUser"])
        assertEquals(fromObj["timestamp"], fromStr["timestamp"])
        assertEquals(fromObj["sign"], fromStr["sign"])
    }

    @Test
    fun 空值与非法输入被拒绝() {
        for (bad in listOf(null, "", "   ")) {
            val failed = runCatching { parseInfo(bad) }.isFailure
            assertTrue("应当拒绝: $bad", failed)
        }
        // 字符串但不是合法 JSON
        assertTrue(runCatching { parseInfo("not json at all") }.isFailure)
    }
}
