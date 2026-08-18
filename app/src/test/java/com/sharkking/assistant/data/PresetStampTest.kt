package com.sharkking.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 预置脚本指纹逻辑。
 *
 * 原先用「同步过一次」的布尔标志，导致升级后新增的脚本（自动蟠桃、
 * 盐场无限视距）被整段跳过、永远进不来。改成按清单指纹判断后，
 * 清单一变就重新同步。这里验指纹对增删改都敏感。
 */
class PresetStampTest {

    /** 复刻 PresetScripts.assetStamp 的算法，assets 在 JVM 测试里取不到 */
    private fun stamp(entries: List<Pair<String, Int>>): String {
        val raw = entries.sortedBy { it.first }
            .joinToString(";") { "${it.first}:${it.second}" }
        val h = java.security.MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray())
        return h.joinToString("") { "%02x".format(it) }.take(16)
    }

    @Test
    fun sameListGivesSameStamp() {
        val a = listOf("洗炼加速.js" to 3800, "无限阵容.js" to 316000)
        assertEquals(stamp(a), stamp(a))
        // 顺序不同不应影响结果
        assertEquals(stamp(a), stamp(a.reversed()))
    }

    @Test
    fun addingScriptChangesStamp() {
        val before = listOf("洗炼加速.js" to 3800)
        val after = before + ("自动蟠桃.js" to 1483000)
        assertNotEquals("新增脚本必须让指纹变化，否则老用户拿不到", stamp(before), stamp(after))
    }

    @Test
    fun removingScriptChangesStamp() {
        val before = listOf("洗炼加速.js" to 3800, "旧脚本.js" to 100)
        val after = listOf("洗炼加速.js" to 3800)
        assertNotEquals(stamp(before), stamp(after))
    }

    @Test
    fun contentUpdateChangesStamp() {
        // 武将升级从旧版换成 56KB 新版，名字没变但大小变了
        val before = listOf("武将升级.js" to 49500)
        val after = listOf("武将升级.js" to 57300)
        assertNotEquals("内容更新也要能触发同步", stamp(before), stamp(after))
    }

    @Test
    fun stampIsShortEnoughForPrefs() {
        val big = (1..40).map { "脚本$it.js" to it * 1000 }
        assertEquals("指纹应为固定 16 字符", 16, stamp(big).length)
    }
}
