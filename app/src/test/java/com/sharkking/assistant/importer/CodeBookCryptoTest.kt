package com.sharkking.assistant.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实抓包的登录请求体校验码本 XOR 加密。
 *
 * 样本来自 iOS 客户端一次短信登录：密文 base64 存在 decode-login-body.txt，
 * 加密参数存在 cryptmix.json。Node 侧已验证往返一致，这里校验 Kotlin 实现等价。
 */
class CodeBookCryptoTest {

    private fun find(name: String): File? =
        listOf("../_bontest/$name", "../../_bontest/$name", "_bontest/$name")
            .map { File(it) }
            .firstOrNull { it.exists() }

    private fun rule(): CodeBookCrypto.Rule? {
        val f = find("cryptmix.json") ?: return null
        val json = f.readText()
        fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        fun num(k: String) = Regex("\"$k\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
        return CodeBookCrypto.Rule(
            codeBook = str("codeBook") ?: return null,
            swapTimes = num("swapTimes") ?: return null,
            keySkip = num("keySkip") ?: return null,
            keyOffset = num("keyOffset") ?: return null,
        )
    }

    @Test
    fun decryptRealLoginBody() {
        val r = rule() ?: run { println("跳过：未找到 cryptmix.json"); return }
        val body = find("decode-login-body.txt")?.readText()?.trim()
            ?: run { println("跳过：未找到抓包密文"); return }

        assertEquals(4096, r.codeBook.length)
        assertEquals(6, r.swapTimes)

        val plain = CodeBookCrypto.decrypt(body, r)
        println("解出明文 ${plain.length} 字符")
        println(plain.take(120))

        assertTrue("应为 JSON", plain.trimStart().startsWith("{"))
        for (k in listOf("smsCode", "mobile", "gameId", "caidInfo", "deviceUniqueId")) {
            assertTrue("缺字段 $k", plain.contains("\"$k\""))
        }
        assertTrue(plain.contains("xyzwapp"))

        // 往返：重新加密应与抓包逐字符一致
        assertEquals("重新加密应还原原文", body, CodeBookCrypto.encrypt(plain, r))
    }

    @Test
    fun transCodeIsReversibleShape() {
        val r = rule() ?: return
        val tc = CodeBookCrypto.transCode(r.codeBook, r.swapTimes)
        assertEquals(r.codeBook.length, tc.length)
        // 换位只重排，字符多重集不变
        assertEquals(r.codeBook.toList().sorted(), tc.toList().sorted())

        val key = CodeBookCrypto.getKey(tc, r.keySkip)
        assertEquals(4096 / 3, key.length)
    }
}
