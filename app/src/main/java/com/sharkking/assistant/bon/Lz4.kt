package com.sharkking.assistant.bon

/**
 * LZ4 帧格式。
 *
 * 自己实现而不引第三方库：算法本身很短，而 LX 报文的帧头是被
 * 加密破坏后人工还原的，用现成库反而容易在校验环节卡住。
 *
 * 只做解压 + 「未压缩块」封帧。不需要真正的压缩算法：实测真实
 * bin 的块标志位就是未压缩，客户端本来也没压。
 */
object Lz4 {

    private const val MAGIC = 0x184D2204

    /** 与真实 bin 一致：ver=1，其余标志位全 0 */
    private const val FLG = 0x40
    /** blockMaxSize 码 7 = 4MB */
    private const val BD = 0x70

    /**
     * 把明文包成 LZ4 帧，单个未压缩块。
     *
     * 布局：magic(4) + FLG(1) + BD(1) + HC(1) + blockSize(4) + data + endMark(4)
     * blockSize 最高位置 1 表示该块未压缩。
     */
    fun frameUncompressed(data: ByteArray): ByteArray {
        val out = ByteArray(11 + data.size + 4)
        writeLE32(out, 0, MAGIC)
        out[4] = FLG.toByte()
        out[5] = BD.toByte()
        // 帧头校验：xxh32(FLG,BD) 的第二字节。FLG/BD 固定，所以结果恒为 0xDF
        out[6] = (((xxh32(byteArrayOf(FLG.toByte(), BD.toByte())) ushr 8) and 0xFF)).toByte()
        writeLE32(out, 7, data.size or 0x80000000.toInt())
        data.copyInto(out, 11)
        writeLE32(out, 11 + data.size, 0)
        return out
    }

    private fun writeLE32(buf: ByteArray, at: Int, v: Int) {
        buf[at] = (v and 0xFF).toByte()
        buf[at + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[at + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    // xxHash32，仅用于算帧头校验，输入只有 2 字节所以不需要主循环
    private const val P1 = -1640531535   // 2654435761
    private const val P2 = -2048144777   // 2246822519
    private const val P3 = -1028477379   // 3266489917
    private const val P4 = 668265263
    private const val P5 = 374761393

    private fun xxh32(input: ByteArray, seed: Int = 0): Int {
        var h = seed + P5 + input.size
        var i = 0
        // 输入不足 16 字节，直接进尾部处理
        while (i + 4 <= input.size) {
            val k = (input[i].toInt() and 0xFF) or
                ((input[i + 1].toInt() and 0xFF) shl 8) or
                ((input[i + 2].toInt() and 0xFF) shl 16) or
                ((input[i + 3].toInt() and 0xFF) shl 24)
            h += k * P3
            h = Integer.rotateLeft(h, 17) * P4
            i += 4
        }
        while (i < input.size) {
            h += (input[i].toInt() and 0xFF) * P5
            h = Integer.rotateLeft(h, 11) * P1
            i++
        }
        h = h xor (h ushr 15)
        h *= P2
        h = h xor (h ushr 13)
        h *= P3
        h = h xor (h ushr 16)
        return h
    }

    fun decompressFrame(input: ByteArray): ByteArray {
        if (input.size < 7) throw BonException("LZ4 帧过短")
        val magic = (input[0].toInt() and 0xFF) or
            ((input[1].toInt() and 0xFF) shl 8) or
            ((input[2].toInt() and 0xFF) shl 16) or
            ((input[3].toInt() and 0xFF) shl 24)
        if (magic != MAGIC) throw BonException("LZ4 magic 不匹配")

        val flg = input[4].toInt() and 0xFF
        val blockChecksum = (flg shr 4) and 1 == 1
        val contentSize = (flg shr 3) and 1 == 1
        val contentChecksum = (flg shr 2) and 1 == 1
        val dictId = flg and 1 == 1

        var p = 7
        if (contentSize) p += 8
        if (dictId) p += 4

        val out = ArrayList<ByteArray>()
        var total = 0
        while (p + 4 <= input.size) {
            var blockSize = (input[p].toInt() and 0xFF) or
                ((input[p + 1].toInt() and 0xFF) shl 8) or
                ((input[p + 2].toInt() and 0xFF) shl 16) or
                ((input[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            if (blockSize == 0) break

            val uncompressed = blockSize and 0x80000000.toInt() != 0
            blockSize = blockSize and 0x7FFFFFFF
            if (blockSize < 0 || p + blockSize > input.size) {
                throw BonException("LZ4 块越界: size=$blockSize pos=$p len=${input.size}")
            }

            val block = if (uncompressed) {
                input.copyOfRange(p, p + blockSize)
            } else {
                decompressBlock(input, p, blockSize)
            }
            out.add(block)
            total += block.size
            p += blockSize
            if (blockChecksum) p += 4
        }
        if (contentChecksum) p += 4

        val result = ByteArray(total)
        var o = 0
        for (b in out) { b.copyInto(result, o); o += b.size }
        return result
    }

    /** LZ4 块解压：序列为 token + literal + match */
    private fun decompressBlock(src: ByteArray, offset: Int, length: Int): ByteArray {
        var ip = offset
        val end = offset + length
        // 输出大小未知，按块最大压缩比预留并按需增长
        var dst = ByteArray(length * 4 + 64)
        var op = 0

        fun grow(need: Int) {
            if (op + need <= dst.size) return
            var cap = dst.size
            while (cap < op + need) cap = cap shl 1
            dst = dst.copyOf(cap)
        }

        while (ip < end) {
            val token = src[ip++].toInt() and 0xFF

            var litLen = token shr 4
            if (litLen == 15) {
                while (ip < end) {
                    val b = src[ip++].toInt() and 0xFF
                    litLen += b
                    if (b != 255) break
                }
            }
            if (litLen > 0) {
                if (ip + litLen > end) throw BonException("LZ4 literal 越界")
                grow(litLen)
                src.copyInto(dst, op, ip, ip + litLen)
                ip += litLen
                op += litLen
            }

            // 块尾可能只有 literal，没有 match
            if (ip >= end) break
            if (ip + 2 > end) throw BonException("LZ4 offset 截断")

            val matchOffset = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            if (matchOffset == 0) throw BonException("LZ4 offset 为 0")

            var matchLen = token and 0x0F
            if (matchLen == 15) {
                while (ip < end) {
                    val b = src[ip++].toInt() and 0xFF
                    matchLen += b
                    if (b != 255) break
                }
            }
            matchLen += 4

            var mp = op - matchOffset
            if (mp < 0) throw BonException("LZ4 match 偏移越界")
            grow(matchLen)
            // 必须逐字节拷贝：match 区可能与输出区重叠（offset < len）
            repeat(matchLen) { dst[op++] = dst[mp++] }
        }
        return dst.copyOf(op)
    }
}
