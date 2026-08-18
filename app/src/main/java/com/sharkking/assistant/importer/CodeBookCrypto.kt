package com.sharkking.assistant.importer

/**
 * 334 SDK 的登录请求体加密。
 *
 * 算法从雪碧助手 renderer/hsdk-mock.js 的 CryptoModule 还原，
 * 并用真实抓包做过往返验证（重新加密与原文逐字节一致）。
 *
 * 五步：明文 JSON → base64 → 混淆码本 → 抽密钥 → XOR → base64
 */
object CodeBookCrypto {

    /** 服务端下发的加密规则 */
    data class Rule(
        val codeBook: String,
        val swapTimes: Int,
        val keySkip: Int,
        val keyOffset: Int,
    )

    /**
     * 递归交换左右两半，做 swapTimes 轮。
     * 长度为奇数时原实现返回 null，这里等价地直接返回原串。
     */
    fun transCode(str: String, swapTimes: Int): String {
        if (swapTimes <= 0) return str
        if (str.length % 2 != 0) return str
        val half = str.length / 2
        val right = str.substring(half)
        val left = str.substring(0, half)
        return transCode(right, swapTimes - 1) + transCode(left, swapTimes - 1)
    }

    /** 每隔 keySkip 个字符取一个，拼成密钥 */
    fun getKey(codeBook: String, keySkip: Int): String {
        if (codeBook.isEmpty() || keySkip <= 0) return codeBook
        val count = codeBook.length / keySkip
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(codeBook[i * keySkip])
        return sb.toString()
    }

    /** 逐字节 XOR，offset 在密钥长度内回绕。XOR 自反，加解密同一函数 */
    private fun xor(data: ByteArray, key: ByteArray, startOffset: Int): ByteArray {
        if (data.isEmpty() || key.isEmpty()) return data
        var offset = startOffset
        val out = ByteArray(data.size)
        for (i in data.indices) {
            if (offset >= key.size) offset = 0
            out[i] = (data[i].toInt() xor key[offset].toInt()).toByte()
            offset++
        }
        return out
    }

    /**
     * 明文 JSON → 服务端要的 base64 请求体。
     *
     * 中间那层 base64 的字符全在 ASCII 内，所以按 Latin1 取字节即可。
     */
    fun encrypt(json: String, rule: Rule): String {
        val inner = B64.encode(json.toByteArray(Charsets.UTF_8))
        val key = keyStream(rule)
        val enc = xor(
            inner.toByteArray(Charsets.ISO_8859_1),
            key.toByteArray(Charsets.ISO_8859_1),
            key.length shr rule.keyOffset,
        )
        return B64.encode(enc)
    }

    /** 反向解密，用于自测校验 */
    fun decrypt(body: String, rule: Rule): String {
        val key = keyStream(rule)
        val inner = String(
            xor(B64.decode(body), key.toByteArray(Charsets.ISO_8859_1), key.length shr rule.keyOffset),
            Charsets.ISO_8859_1,
        )
        return String(B64.decode(inner), Charsets.UTF_8)
    }

    private fun keyStream(rule: Rule): String =
        getKey(transCode(rule.codeBook, rule.swapTimes), rule.keySkip)
}

/**
 * 自带 Base64，不用 android.util.Base64。
 * 这样加密逻辑能在纯 JVM 单元测试里跑，不需要 Robolectric。
 */
internal object B64 {
    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(TABLE[(triple shr 18) and 63])
            sb.append(TABLE[(triple shr 12) and 63])
            sb.append(if (i + 1 < data.size) TABLE[(triple shr 6) and 63] else '=')
            sb.append(if (i + 2 < data.size) TABLE[triple and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length / 4 * 3)
        var acc = 0
        var bits = 0
        for (c in text) {
            val v = TABLE.indexOf(c)
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
