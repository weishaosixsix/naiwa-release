package com.sharkking.assistant.bon

/**
 * BON 解码。返回的值域：null / Int / Long / Float / Double / String /
 * Boolean / ByteArray / LinkedHashMap<String, Any?> / List<Any?> / BonDate
 */
class BonDecoder {

    private lateinit var reader: DataReader
    private val strPool = ArrayList<String>()

    fun decode(data: ByteArray): Any? {
        reader = DataReader(data)
        strPool.clear()
        return read()
    }

    private fun read(): Any? = when (val tag = reader.readUInt8()) {
        Tag.NULL -> null
        Tag.INT32 -> reader.readInt32()
        Tag.INT64 -> reader.readInt64()
        Tag.FLOAT32 -> reader.readFloat32()
        Tag.FLOAT64 -> reader.readFloat64()
        Tag.STRING -> reader.readUtf().also { strPool.add(it) }
        Tag.BOOLEAN -> reader.readUInt8() == 1
        Tag.BINARY -> reader.readBytes(reader.read7BitInt())
        Tag.OBJECT -> {
            val n = reader.read7BitInt()
            val map = LinkedHashMap<String, Any?>(n.coerceAtMost(1024))
            repeat(n) {
                val k = read()
                map[k?.toString() ?: "null"] = read()
            }
            map
        }
        Tag.ARRAY -> {
            val n = reader.read7BitInt()
            val list = ArrayList<Any?>(n.coerceAtMost(4096))
            repeat(n) { list.add(read()) }
            list
        }
        Tag.DATETIME -> BonDate(reader.readInt64())
        Tag.STRING_REF -> {
            val i = reader.read7BitInt()
            if (i in strPool.indices) strPool[i] else ""
        }
        else -> throw BonException("BonDecoder unknown type $tag")
    }
}

/**
 * BON 编码。字符串首次出现写完整值并入池，重复出现写池索引 —— 这个规则
 * 必须和服务端一致，否则解码侧索引对不上。
 */
class BonEncoder {

    private lateinit var writer: DataWriter
    private val strIndex = LinkedHashMap<String, Int>()

    fun encode(value: Any?): ByteArray {
        writer = DataWriter()
        strIndex.clear()
        write(value)
        return writer.toByteArray()
    }

    private fun write(value: Any?) {
        when (value) {
            null -> writer.writeUInt8(Tag.NULL)

            is Boolean -> {
                writer.writeUInt8(Tag.BOOLEAN)
                writer.writeUInt8(if (value) 1 else 0)
            }

            is String -> {
                val existing = strIndex[value]
                if (existing != null) {
                    writer.writeUInt8(Tag.STRING_REF)
                    writer.write7BitInt(existing)
                } else {
                    writer.writeUInt8(Tag.STRING)
                    writer.writeUtf(value)
                    strIndex[value] = strIndex.size
                }
            }

            // JS 侧对整数一律走 Int32，超范围才退到 Float64。
            // 这里保持一致，否则字节流对不上。
            is Int -> {
                writer.writeUInt8(Tag.INT32)
                writer.writeInt32(value)
            }

            is Long -> {
                if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    writer.writeUInt8(Tag.INT32)
                    writer.writeInt32(value.toInt())
                } else {
                    writer.writeUInt8(Tag.FLOAT64)
                    writer.writeFloat64(value.toDouble())
                }
            }

            is Double -> {
                if (value == Math.floor(value) && !value.isInfinite() &&
                    value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()
                ) {
                    writer.writeUInt8(Tag.INT32)
                    writer.writeInt32(value.toInt())
                } else {
                    writer.writeUInt8(Tag.FLOAT64)
                    writer.writeFloat64(value)
                }
            }

            is Float -> write(value.toDouble())

            is BonDate -> {
                writer.writeUInt8(Tag.DATETIME)
                writer.writeInt64(value.millis)
            }

            is ByteArray -> {
                writer.writeUInt8(Tag.BINARY)
                writer.write7BitInt(value.size)
                writer.writeBytes(value)
            }

            is List<*> -> {
                writer.writeUInt8(Tag.ARRAY)
                writer.write7BitInt(value.size)
                value.forEach { write(it) }
            }

            is Map<*, *> -> {
                writer.writeUInt8(Tag.OBJECT)
                writer.write7BitInt(value.size)
                for ((k, v) in value) {
                    write(k?.toString())
                    write(v)
                }
            }

            else -> writer.writeUInt8(Tag.NULL)
        }
    }
}
