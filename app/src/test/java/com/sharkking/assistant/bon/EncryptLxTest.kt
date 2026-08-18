package com.sharkking.assistant.bon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验 LX("pl") 封装。
 *
 * 生成 bin 必须用 pl：游戏发 authuser 时带 O4e-Encoding: lx 头，
 * 给 px 格式会被服务端判「指令解析错误」。玩家用的 bin 解析工具
 * 也只认 LZ4 帧（px 会报 ERROR_headerVersion_wrong）。
 */
class EncryptLxTest {

    private fun realBin(): ByteArray? =
        listOf("../鱼神(1).bin", "../../鱼神(1).bin", "鱼神(1).bin")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?.readBytes()

    @Test
    fun frameHeaderMatchesRealBin() {
        val frame = Lz4.frameUncompressed("test".toByteArray())
        // magic
        assertEquals(0x04.toByte(), frame[0])
        assertEquals(0x22.toByte(), frame[1])
        assertEquals(0x4D.toByte(), frame[2])
        assertEquals(0x18.toByte(), frame[3])
        // FLG / BD 与真实 bin 相同
        assertEquals(0x40.toByte(), frame[4])
        assertEquals(0x70.toByte(), frame[5])
        // 帧头校验：xxh32(FLG,BD)>>8&0xFF，Node 侧算出 0xDF
        assertEquals("帧头校验和应为 0xDF", 0xDF.toByte(), frame[6])
        println("帧头: " + frame.take(11).joinToString(" ") { "%02x".format(it) })
    }

    @Test
    fun encryptLxRoundTrip() {
        val payload = "奶蛙 LX 往返测试 with ASCII and 中文".toByteArray()
        val enc = XyCrypto.encryptLX(payload)
        assertEquals(XyCrypto.Kind.LX, XyCrypto.detect(enc))
        assertArrayEquals(payload, XyCrypto.decrypt(enc))
        // 随机密钥，多跑几轮确认位嵌入没有边界问题
        repeat(200) {
            val e = XyCrypto.encryptLX(payload)
            assertEquals(XyCrypto.Kind.LX, XyCrypto.detect(e))
            assertArrayEquals(payload, XyCrypto.decrypt(e))
        }
    }

    /** 用真实 bin 的明文重新封装，应能解回同样内容 */
    @Test
    fun repackRealBinAsLx() {
        val raw = realBin() ?: run { println("跳过：未找到样本 bin"); return }
        val plain = XyCrypto.decrypt(raw)
        val relx = XyCrypto.encryptLX(plain)

        assertEquals(XyCrypto.Kind.LX, XyCrypto.detect(relx))
        assertArrayEquals("重新封装应能解出同一明文", plain, XyCrypto.decrypt(relx))

        @Suppress("UNCHECKED_CAST")
        val obj = BonDecoder().decode(XyCrypto.decrypt(relx)) as Map<String, Any?>
        assertEquals("hortor", obj["platform"])
        assertTrue(obj.containsKey("info"))
        println("真实 ${raw.size} 字节 → 重封装 ${relx.size} 字节，内容一致")
    }

    /** 小数据也要能过：帧结构不能依赖长度超过加密区(100字节) */
    @Test
    fun shortPayloadStillValid() {
        for (n in listOf(0, 1, 16, 88, 89, 90, 200)) {
            val data = ByteArray(n) { (it % 251).toByte() }
            val enc = XyCrypto.encryptLX(data)
            assertArrayEquals("长度 $n 往返失败", data, XyCrypto.decrypt(enc))
        }
    }
}
