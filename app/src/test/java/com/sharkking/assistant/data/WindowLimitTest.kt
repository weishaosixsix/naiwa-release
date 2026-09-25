package com.sharkking.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口上限是个 `const val`，编译期就被内联成字面量 —— dex 里既查不到源码文本，
 * 也查不到 `= 6` 这样的痕迹，改错了不看代码是发现不了的。用测试锁住取值。
 *
 * 上限只是内存安全的保守值，不是平台限制：每个窗口是一个完整 WebView 在跑
 * 一份 Cocos WebGL 游戏，多个 WebView 还共用渲染进程，开太多会整个渲染进程
 * 一起被回收。调大请配合真机实测。
 */
class WindowLimitTest {

    @Test
    fun 上限不低于原先的6个() {
        assertTrue(
            "WINDOW_LIMIT 被改小了，多开能力会退化",
            AppStore.WINDOW_LIMIT >= 6,
        )
    }

    @Test
    fun 上限当前取12() {
        assertEquals(12, AppStore.WINDOW_LIMIT)
    }
}
