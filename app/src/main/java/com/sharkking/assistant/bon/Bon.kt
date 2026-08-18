package com.sharkking.assistant.bon

/**
 * BON (Binary Object Notation) 编解码。
 *
 * 咸鱼之王服务端用的自研二进制格式，等价于 .NET BinaryWriter 的布局：
 * 全部小端，字符串用 7-bit varint 长度前缀 + UTF-8。
 * 重复字符串会写成引用（类型码 99 + 池索引），编解码两侧必须
 * 用同一套入池规则，否则索引会错位。
 */

/** BON 值的类型码 */
internal object Tag {
    const val NULL = 0
    const val INT32 = 1
    const val INT64 = 2
    const val FLOAT32 = 3
    const val FLOAT64 = 4
    const val STRING = 5
    const val BOOLEAN = 6
    const val BINARY = 7
    const val OBJECT = 8
    const val ARRAY = 9
    const val DATETIME = 10
    const val STRING_REF = 99
}

/** BON 里的日期值，与普通整数区分开 */
data class BonDate(val millis: Long)

class BonException(msg: String) : RuntimeException(msg)
