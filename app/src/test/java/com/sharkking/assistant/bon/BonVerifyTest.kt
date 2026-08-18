package com.sharkking.assistant.bon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实 bin 文件校验 Kotlin 版 BON / 加解密与 Node 参考实现一致。
 * 期望值来自 Node 侧实测：1072 字节 → 解密 1057 字节，
 * platform=hortor, timestamp=1757382691, sign=bdf75a31377a190a60636dc750155450
 */
class BonVerifyTest {

    private fun sample(): ByteArray? {
        for (p in listOf("../鱼神(1).bin", "../../鱼神(1).bin", "鱼神(1).bin")) {
            val f = File(p)
            if (f.exists()) return f.readBytes()
        }
        return null
    }

    @Test
    fun decryptAndDecodeRealBin() {
        val raw = sample() ?: run {
            println("跳过：未找到样本 bin")
            return
        }
        println("原始 ${raw.size} 字节，类型 ${XyCrypto.detect(raw)}")
        assertEquals(XyCrypto.Kind.LX, XyCrypto.detect(raw))

        val plain = XyCrypto.decrypt(raw)
        println("解密后 ${plain.size} 字节")
        assertEquals(1057, plain.size)

        @Suppress("UNCHECKED_CAST")
        val obj = BonDecoder().decode(plain) as Map<String, Any?>
        println("字段: ${obj.keys.joinToString(", ")}")
        assertEquals("hortor", obj["platform"])
        assertEquals("mix", obj["platformExt"])
        assertEquals(null, obj["serverId"])
        assertEquals(0, obj["scene"])
        assertEquals("", obj["referrerInfo"])

        @Suppress("UNCHECKED_CAST")
        val info = obj["info"] as Map<String, Any?>
        assertEquals(1757382691, info["timestamp"])
        assertEquals("bdf75a31377a190a60636dc750155450", info["sign"])
        assertEquals(896, (info["encryptCombUser"] as String).length)
        println("info 校验通过: timestamp=${info["timestamp"]} sign=${info["sign"]}")
    }

    @Test
    fun bonRoundTripIsByteIdentical() {
        val raw = sample() ?: return
        val plain = XyCrypto.decrypt(raw)
        val obj = BonDecoder().decode(plain)
        val re = BonEncoder().encode(obj)
        println("原始明文 ${plain.size} → 重新编码 ${re.size}")
        var firstDiff = -1
        for (i in 0 until minOf(plain.size, re.size)) {
            if (plain[i] != re[i]) { firstDiff = i; break }
        }
        if (firstDiff >= 0) {
            println("首个差异 @$firstDiff: ${"%02x".format(plain[firstDiff])} vs ${"%02x".format(re[firstDiff])}")
        }
        assertTrue("BON 编码必须逐字节还原原文", plain.contentEquals(re))
    }

    @Test
    fun encryptXRoundTrip() {
        val payload = "奶蛙测试 payload with 中文 and ASCII".toByteArray()
        val enc = XyCrypto.encryptX(payload)
        assertEquals(XyCrypto.Kind.X, XyCrypto.detect(enc))
        assertTrue(XyCrypto.decryptX(enc).contentEquals(payload))
        // 随机密钥，多跑几轮确认位嵌入没有边界问题
        repeat(200) {
            val e = XyCrypto.encryptX(payload)
            assertTrue(XyCrypto.decryptX(e).contentEquals(payload))
        }
    }

    @Test
    fun generateServerBoundBin() {
        val raw = sample() ?: return
        @Suppress("UNCHECKED_CAST")
        val account = BonDecoder().decode(XyCrypto.decrypt(raw)) as Map<String, Any?>
        val bound = LinkedHashMap(account).apply { this["serverId"] = 21953 }
        val bin = XyCrypto.encryptX(BonEncoder().encode(bound))

        @Suppress("UNCHECKED_CAST")
        val back = BonDecoder().decode(XyCrypto.decrypt(bin)) as Map<String, Any?>
        assertEquals(21953, back["serverId"])
        @Suppress("UNCHECKED_CAST")
        val bi = back["info"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val ai = account["info"] as Map<String, Any?>
        assertEquals(ai["sign"], bi["sign"])
        assertEquals(ai["encryptCombUser"], bi["encryptCombUser"])
        println("生成绑定 bin ${bin.size} 字节, serverId=${back["serverId"]}, 凭据一致")
    }

    @Test
    fun bonPrimitiveTypes() {
        val cases = listOf<Any?>(
            null, true, false, 0, -1, 42, Int.MAX_VALUE, Int.MIN_VALUE,
            "", "hello", "中文字符串", 3.5, -0.25,
            listOf(1, 2, 3), listOf<Any?>("a", null, true),
            mapOf("k" to "v", "n" to 1),
        )
        for (c in cases) {
            val enc = BonEncoder().encode(c)
            val dec = BonDecoder().decode(enc)
            assertEquals("类型往返失败: $c", c.toString(), dec.toString())
        }
        // 字符串引用池：重复字符串应写成引用且能正确还原
        val dup = mapOf("a" to "same", "b" to "same", "c" to "same")
        @Suppress("UNCHECKED_CAST")
        val out = BonDecoder().decode(BonEncoder().encode(dup)) as Map<String, Any?>
        assertEquals("same", out["a"])
        assertEquals("same", out["b"])
        assertEquals("same", out["c"])
        println("基础类型与字符串引用池校验通过")
    }
}
