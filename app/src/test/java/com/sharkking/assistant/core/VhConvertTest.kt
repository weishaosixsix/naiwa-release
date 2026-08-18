package com.sharkking.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VH_FIX 里 vh->px 的换算规则。
 *
 * 真机实测 100vh 被算成 0px（useWideViewPort=true 导致 Blink 视口高度
 * 不确定），脚本的 max-height:88vh 全变成 0，面板被压成一条线。
 * 这里用 Kotlin 复刻同一个正则，验证换算和 calc() 场景都正确。
 */
class VhConvertTest {

    private val vhRe = Regex("""(-?[\d.]+)vh""")

    /** 与 VH_FIX 中的 toPx 保持一致 */
    private fun toPx(css: String, viewportHeight: Int): String =
        vhRe.replace(css) { m ->
            val n = m.groupValues[1].toDouble()
            String.format("%.1fpx", n * viewportHeight / 100)
        }

    @Test
    fun simpleValue() {
        // 白玉彩玉面板：88vh，真机视口 640
        assertEquals("563.2px", toPx("88vh", 640))
        // 无限阵容面板：78vh
        assertEquals("499.2px", toPx("78vh", 640))
        assertEquals("640.0px", toPx("100vh", 640))
    }

    @Test
    fun insideCalc() {
        // 白玉彩玉内容区用的是 calc，换算后必须仍是合法 calc 表达式
        assertEquals(
            "calc(563.2px - 42px)",
            toPx("calc(88vh - 42px)", 640),
        )
    }

    @Test
    fun negativeAndDecimal() {
        assertEquals("-64.0px", toPx("-10vh", 640))
        assertEquals("ededed", toPx("ededed", 640))   // 不含 vh 的不动
        assertEquals("140.8px", toPx("22.0vh", 640))
    }

    @Test
    fun doesNotTouchOtherUnits() {
        // vw / rem / vmin 不在处理范围内，必须原样保留
        val src = "88vw 2rem 50vmin 10px"
        assertEquals(src, toPx(src, 640))
    }

    @Test
    fun multipleOccurrences() {
        assertEquals(
            "320.0px 480.0px",
            toPx("50vh 75vh", 640),
        )
    }

    @Test
    fun realPanelHeightIsUsable() {
        // 修正后面板高度必须足够显示内容，而不是塌成一条线
        val px = toPx("88vh", 640).removeSuffix("px").toDouble()
        assertTrue("88vh 换算后应远大于塌陷阈值", px > 400)
    }
}
