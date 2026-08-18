package com.sharkking.assistant.data

import android.content.Context
import android.util.Log

/**
 * 打包在 assets/scripts 下的预置脚本。
 *
 * 升级时做一次同步：新增的补进来、内容变了的更新、包里已删除的清掉。
 * 用户自己导入的脚本（preset=false）完全不受影响。
 */
object PresetScripts {

    private const val TAG = "再攀-Preset"
    private const val DIR = "scripts"

    /** 这几个默认启用，其余默认关闭 */
    private val DEFAULT_ENABLED = setOf(
        "洗炼加速.js",
        "洗炼跳过红色.js",
    )

    /** 常驻脚本：关掉会导致游戏卡死，不允许禁用或删除 */
    private val LOCKED = setOf("00-省电模式修复.js")

    /**
     * 包内脚本清单的指纹（名称 + 各自大小）。
     *
     * 用它替代「同步过一次」的布尔标志：脚本增删或内容变化时指纹就变，
     * 老用户升级后能自动拿到新脚本。含大小是为了内容更新也能触发同步。
     */
    fun assetStamp(ctx: Context): String {
        val names = runCatching { ctx.assets.list(DIR) }.getOrNull()
            ?.filter { it.endsWith(".js", true) }
            ?.sorted()
            ?: return "none"
        return names.joinToString(";") { name ->
            val size = runCatching {
                ctx.assets.open("$DIR/$name").use { it.available() }
            }.getOrDefault(0)
            "$name:$size"
        }.let { raw ->
            // 清单文本可能很长，压成短哈希再存
            val h = java.security.MessageDigest.getInstance("SHA-1")
                .digest(raw.toByteArray())
            h.joinToString("") { "%02x".format(it) }.take(16)
        }
    }

    /**
     * 与包内脚本对齐。返回 新增数 to 移除数。
     *
     * 更新已有脚本时保留用户的开关状态，只换代码 —— 用户手动开过的
     * 脚本不该因为一次升级被重置。
     */
    fun syncInto(ctx: Context, store: AppStore): Pair<Int, Int> {
        val names = runCatching { ctx.assets.list(DIR) }.getOrNull()
            ?.filter { it.endsWith(".js", true) }
            ?.sorted()
            ?: return 0 to 0

        var added = 0
        for (name in names) {
            val read = runCatching {
                ctx.assets.open("$DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val code = read.getOrNull()
            if (code == null) {
                Log.w(TAG, "预置脚本读取失败 $name: ${read.exceptionOrNull()?.message}")
                continue
            }

            val existing = store.scripts.firstOrNull { it.name == name }
            if (existing == null) {
                store.addPresetScript(
                    name = name,
                    code = code,
                    enabled = name in DEFAULT_ENABLED,
                    locked = name in LOCKED,
                )
                added++
            } else {
                store.updatePresetScript(
                    id = existing.id,
                    code = code,
                    locked = name in LOCKED,
                )
            }
        }

        // 包里已经不带的旧预置脚本要清掉，但绝不动用户自己导入的
        val removed = store.removeStalePresets(names.toSet())
        if (added > 0 || removed > 0) {
            Log.i(TAG, "预置脚本同步: 新增 $added, 移除 $removed, 包内共 ${names.size}")
        }
        return added to removed
    }
}
