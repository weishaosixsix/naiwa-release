package com.sharkking.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 所有字段都是 val。Compose 的 mutableStateListOf 只在「元素被替换」时通知重组，
 * 就地修改 var 字段不会触发任何更新，因此状态变更统一走 copy() + 替换整项。
 */

/** 账号分组 */
data class AccountGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorArgb: Int,
    val order: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("color", colorArgb); put("order", order)
    }

    companion object {
        const val DEFAULT_ID = "default"

        val PALETTE = listOf(
            0xFFEF5350.toInt() to "红色",
            0xFFFF9800.toInt() to "橙色",
            0xFFFFC107.toInt() to "黄色",
            0xFF66BB6A.toInt() to "绿色",
            0xFF42A5F5.toInt() to "蓝色",
            0xFFAB47BC.toInt() to "紫色",
            0xFF78909C.toInt() to "灰色",
        )

        fun fromJson(o: JSONObject) = AccountGroup(
            id = o.getString("id"),
            name = o.getString("name"),
            colorArgb = o.getInt("color"),
            order = o.optInt("order", 0),
        )
    }
}

/**
 * 一个账号。binHex 是登录请求体的十六进制串，
 * 注入到 window.__activeBinHex 后由 XHR 劫持脚本使用。
 */
data class AccountItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val binHex: String,
    val groupId: String = AccountGroup.DEFAULT_ID,
    val isPrimary: Boolean = false,
    val order: Int = 0,
    val builtin: Boolean = false,
) {
    /** 列表与窗口标题隐藏 .bin 后缀 */
    val displayName: String
        get() = name.removeSuffix(".bin")

    val sizeBytes: Int get() = binHex.length / 2

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("bin", binHex)
        put("group", groupId); put("primary", isPrimary)
        put("order", order); put("builtin", builtin)
    }

    companion object {
        fun fromJson(o: JSONObject) = AccountItem(
            id = o.getString("id"),
            name = o.getString("name"),
            binHex = o.getString("bin"),
            groupId = o.optString("group", AccountGroup.DEFAULT_ID),
            isPrimary = o.optBoolean("primary", false),
            order = o.optInt("order", 0),
            builtin = o.optBoolean("builtin", false),
        )
    }
}

/**
 * 游戏脚本，可开关。
 *
 * locked 为真时是修复类脚本（如省电模式），关掉游戏就会卡死，所以不允许
 * 禁用或删除。preset 标记随包内置，升级时可整体替换；用户自己导入的
 * 脚本 preset 为假，升级流程绝不触碰。
 */
data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = false,
    val order: Int = 0,
    val locked: Boolean = false,
    val preset: Boolean = false,
) {
    val displayName: String get() = name.removeSuffix(".js")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("code", code)
        put("enabled", enabled); put("order", order)
        put("locked", locked); put("preset", preset)
    }

    companion object {
        fun fromJson(o: JSONObject) = UserScript(
            id = o.getString("id"),
            name = o.getString("name"),
            code = o.getString("code"),
            enabled = o.optBoolean("enabled", false),
            order = o.optInt("order", 0),
            locked = o.optBoolean("locked", false),
            preset = o.optBoolean("preset", false),
        )
    }
}

/** 一个游戏窗口的运行态 */
data class GameWindow(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val title: String,
    val isSyncMaster: Boolean = false,
)

internal fun <T> JSONArray.map(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { block(getJSONObject(it)) }
