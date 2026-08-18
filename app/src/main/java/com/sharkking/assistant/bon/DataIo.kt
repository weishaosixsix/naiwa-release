package com.sharkking.assistant.bon

/** 字节流读取原语，全部小端 */
internal class DataReader(private val data: ByteArray) {

    var position = 0
        private set

    private fun need(n: Int) {
        if (position + n > data.size) throw BonException("read eof at $position need $n")
    }

    fun readUInt8(): Int {
        need(1)
        return data[position++].toInt() and 0xFF
    }

    fun readInt32(): Int {
        need(4)
        val v = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8) or
            ((data[position + 2].toInt() and 0xFF) shl 16) or
            ((data[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return v
    }

    fun readInt64(): Long {
        need(8)
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (data[position + i].toLong() and 0xFF)
        }
        position += 8
        return v
    }

    fun readFloat32(): Float = Float.fromBits(readInt32())

    fun readFloat64(): Double = Double.fromBits(readInt64())

    /** 7-bit varint，与 .NET Read7BitEncodedInt 一致 */
    fun read7BitInt(): Int {
        var value = 0
        var shift = 0
        var count = 0
        while (true) {
            if (count++ == 5) throw BonException("Format_Bad7BitInt32")
            val b = readUInt8()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return value
    }

    fun readUtf(): String {
        val len = read7BitInt()
        need(len)
        val s = String(data, position, len, Charsets.UTF_8)
        position += len
        return s
    }

    fun readBytes(len: Int): ByteArray {
        need(len)
        val out = data.copyOfRange(position, position + len)
        position += len
        return out
    }
}

/** 字节流写入原语，全部小端 */
internal class DataWriter {

    private var buf = ByteArray(256)
    private var size = 0

    private fun ensure(n: Int) {
        if (size + n <= buf.size) return
        var cap = buf.size
        while (cap < size + n) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    fun writeUInt8(v: Int) {
        ensure(1)
        buf[size++] = (v and 0xFF).toByte()
    }

    fun writeInt32(v: Int) {
        ensure(4)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v shr 8) and 0xFF).toByte()
        buf[size++] = ((v shr 16) and 0xFF).toByte()
        buf[size++] = ((v shr 24) and 0xFF).toByte()
    }

    fun writeInt64(v: Long) {
        ensure(8)
        for (i in 0..7) buf[size++] = ((v shr (i * 8)) and 0xFF).toByte()
    }

    fun writeFloat64(v: Double) = writeInt64(v.toRawBits())

    fun write7BitInt(value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v != 0) writeUInt8(b or 0x80) else { writeUInt8(b); break }
        }
    }

    fun writeUtf(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        write7BitInt(bytes.size)
        writeBytes(bytes)
    }

    fun writeBytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
