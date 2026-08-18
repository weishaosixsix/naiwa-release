package com.sharkking.assistant.bon

import kotlin.random.Random

/**
 * 咸鱼之王报文加解密。
 *
 * 两种封装，靠前两字节区分：
 * - "px" (0x70 0x78) → X：整体单字节 XOR，前 4 字节是头
 * - "pl" (0x70 0x6C) → LX：只 XOR 前 100 字节，头 4 字节还原成
 *   LZ4 帧魔数后解压
 *
 * 密钥是一个 8 位值，拆成 8 个 bit 藏在第 3、4 字节的偶数位上
 * （掩码 0xAA 的补集），extractKey 负责取回。
 */
object XyCrypto {

    enum class Kind { X, LX, NONE }

    fun detect(data: ByteArray): Kind = when {
        data.size < 2 -> Kind.NONE
        data[0] == 0x70.toByte() && data[1] == 0x78.toByte() -> Kind.X
        data[0] == 0x70.toByte() && data[1] == 0x6C.toByte() -> Kind.LX
        else -> Kind.NONE
    }

    private fun extractKey(d: ByteArray): Int {
        val b2 = d[2].toInt()
        val b3 = d[3].toInt()
        return (((b2 shr 6) and 1) shl 7) or
            (((b2 shr 4) and 1) shl 6) or
            (((b2 shr 2) and 1) shl 5) or
            (((b2 shr 0) and 1) shl 4) or
            (((b3 shr 6) and 1) shl 3) or
            (((b3 shr 4) and 1) shl 2) or
            (((b3 shr 2) and 1) shl 1) or
            (((b3 shr 0) and 1) shl 0)
    }

    fun decryptX(data: ByteArray): ByteArray {
        if (data.size < 4) return data.copyOf()
        val buf = data.copyOf()
        val key = extractKey(buf).toByte()
        for (i in buf.size - 1 downTo 4) buf[i] = (buf[i].toInt() xor key.toInt()).toByte()
        return buf.copyOfRange(4, buf.size)
    }

    fun encryptX(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + 4)
        val rid = Random.nextInt()
        out[0] = (rid and 0xFF).toByte()
        out[1] = ((rid shr 8) and 0xFF).toByte()
        out[2] = ((rid shr 16) and 0xFF).toByte()
        out[3] = ((rid shr 24) and 0xFF).toByte()
        data.copyInto(out, 4)

        val key = 2 + Random.nextInt(248)
        for (i in out.indices.reversed()) out[i] = (out[i].toInt() xor key).toByte()

        out[0] = 0x70
        out[1] = 0x78
        out[2] = ((0xAA and out[2].toInt()) or
            (((key shr 7) and 1) shl 6) or
            (((key shr 6) and 1) shl 4) or
            (((key shr 5) and 1) shl 2) or
            (((key shr 4) and 1) shl 0)).toByte()
        out[3] = ((0xAA and out[3].toInt()) or
            (((key shr 3) and 1) shl 6) or
            (((key shr 2) and 1) shl 4) or
            (((key shr 1) and 1) shl 2) or
            (((key shr 0) and 1) shl 0)).toByte()
        return out
    }

    fun decryptLX(data: ByteArray): ByteArray {
        if (data.size < 4) return data.copyOf()
        val buf = data.copyOf()
        val key = extractKey(buf)
        val n = minOf(buf.size, 100)
        for (i in 2 until n) buf[i] = (buf[i].toInt() xor key).toByte()
        // 帧魔数被加密覆盖了，这里还原
        buf[0] = 0x04; buf[1] = 0x22; buf[2] = 0x4D; buf[3] = 0x18
        return Lz4.decompressFrame(buf)
    }

    /**
     * 封装成 LX("pl") 格式。生成 bin 必须用这个而不是 encryptX：
     *
     * 游戏发 authuser 时请求头带 O4e-Encoding: lx，声明请求体是 lx 编码。
     * 若实际给的是 px，服务端直接回「指令解析错误」，游戏卡在「正在登录」。
     * 玩家常用的 bin 解析工具同样只认 LZ4 帧，px 会报 headerVersion_wrong。
     */
    fun encryptLX(data: ByteArray): ByteArray {
        val frame = Lz4.frameUncompressed(data)
        val key = 2 + Random.nextInt(248)
        // 与解密对称：只 XOR [2,100)
        val n = minOf(frame.size, 100)
        for (i in 2 until n) frame[i] = (frame[i].toInt() xor key).toByte()
        frame[0] = 0x70
        frame[1] = 0x6C
        frame[2] = ((0xAA and frame[2].toInt()) or
            (((key shr 7) and 1) shl 6) or
            (((key shr 6) and 1) shl 4) or
            (((key shr 5) and 1) shl 2) or
            (((key shr 4) and 1) shl 0)).toByte()
        frame[3] = ((0xAA and frame[3].toInt()) or
            (((key shr 3) and 1) shl 6) or
            (((key shr 2) and 1) shl 4) or
            (((key shr 1) and 1) shl 2) or
            (((key shr 0) and 1) shl 0)).toByte()
        return frame
    }

    fun decrypt(data: ByteArray): ByteArray = when (detect(data)) {
        Kind.X -> decryptX(data)
        Kind.LX -> decryptLX(data)
        Kind.NONE -> data.copyOf()
    }
}
