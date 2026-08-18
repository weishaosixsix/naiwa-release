package com.sharkking.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GM_SHIM 里 ROLE.id 别名的取值规则。
 *
 * 真机实测游戏只给 ROLE.roleId（706795317），不给 ROLE.id。
 * 而多数脚本用 ROLE.id 判断会话是否就绪（游戏增强面板因此做了
 * id -> roleId -> userId 三级兜底），只认 id 的脚本会永远等不到。
 * 这里用 Kotlin 复刻同一套优先级，保证补值逻辑不误伤已有 id。
 */
class RoleIdAliasTest {

    /** 与 GM_SHIM 中 alias() 的取值顺序一致 */
    private fun resolveId(id: Any?, roleId: Any?, userId: Any?): Any? {
        // 已有非空 id 就不动，避免覆盖游戏自己的值
        if (id != null && id != "") return id
        if (roleId != null && roleId != "") return roleId
        if (userId != null && userId != "") return userId
        return null
    }

    @Test
    fun 真机场景_只有roleId时补上() {
        assertEquals(706795317L, resolveId(id = null, roleId = 706795317L, userId = null))
    }

    @Test
    fun 已有id时不覆盖() {
        assertEquals("abc", resolveId(id = "abc", roleId = 706795317L, userId = null))
    }

    @Test
    fun 空字符串id视为缺失() {
        assertEquals(123L, resolveId(id = "", roleId = 123L, userId = null))
    }

    @Test
    fun 退到userId() {
        assertEquals(999L, resolveId(id = null, roleId = null, userId = 999L))
    }

    @Test
    fun 三者皆空时不补值() {
        // 返回 null 表示继续轮询等待，而不是写入一个假 id
        assertNull(resolveId(id = null, roleId = null, userId = null))
        assertNull(resolveId(id = "", roleId = "", userId = ""))
    }

    @Test
    fun roleId优先于userId() {
        assertEquals(706795317L, resolveId(id = null, roleId = 706795317L, userId = 999L))
    }
}
