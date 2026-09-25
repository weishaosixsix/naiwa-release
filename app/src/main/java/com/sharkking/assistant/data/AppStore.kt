package com.sharkking.assistant.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

/**
 * 全局状态与持久化，对应 iOS 版的 Store.swift（那边用 NSUserDefaults，
 * 这里用 SharedPreferences）。
 *
 * 模型字段全部不可变，任何修改都通过 copy() 替换列表中对应项来完成，
 * 这样 Compose 才能收到重组通知。
 */
class AppStore(private val ctx: Context) {

    companion object {
        private const val TAG = "奶蛙-Store"
        private const val PREF = "xuebi_store"
        private const val K_ACCOUNTS = "accounts"
        private const val K_GROUPS = "groups"
        private const val K_SCRIPTS = "scripts"
        private const val K_MAX_WIN = "max_windows"
        private const val K_PURGED_BUILTIN = "purged_builtin"
        // 版本号递增可让已安装的用户收到新增的预置脚本
        // 存的是包内脚本清单的指纹，清单一变就重新同步
        private const val K_PRESET_DONE = "preset_stamp"
        private const val K_TAB_MODE = "tab_mode"
        private const val K_DARK = "dark_theme"
        private const val K_SYNC = "sync_enabled"
        /**
         * 一次最多能开几个窗口，同时是「上限」下拉菜单的选项范围。
         *
         * 原来写死 6，是作者按内存留的保守值，**不是平台限制**。
         * 真正的约束是内存：每个窗口都是一个完整 WebView 在跑一份 Cocos
         * WebGL 游戏，而且要下 CDN 资源包、建纹理和 JS 堆；同一 App 的多个
         * WebView 还共用渲染进程，所以开太多不是"慢一点"，而是整个渲染进程
         * 一起被系统回收 —— onRenderProcessGone 兜得住不闪退，但那个时刻
         * 所有窗口会一起没掉。
         *
         * 因此调大请配合实测：逐个往上加，观察会不会出现窗口成片消失。
         * 6 是作者当时的机器上的舒服值；这里放宽到 12，按自己机器再调。
         */
        const val WINDOW_LIMIT = 12

        /**
         * v3 之前存下的脚本没有 preset 标记。这些名字是历史版本内置过的，
         * 用于在升级时识别并清理，避免残留。
         */
        private val LEGACY_PRESET_NAMES = setOf(
            "00-省电模式修复.js",
            "世界循环发消息.js",
            "十殿加速.js",
            "好友备注.js",
            "属性展示增强.js",
            "查看白玉彩玉使用记录.js",
            "洗炼加速.js",
            "洗炼跳过红色.js",
            "盐场无限视距.js",
            "盐场阵容显示.js",
            "自动蟠桃protected.js",
        )
    }

    private val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    val accounts = mutableStateListOf<AccountItem>()
    val groups = mutableStateListOf<AccountGroup>()
    val scripts = mutableStateListOf<UserScript>()
    val windows = mutableStateListOf<GameWindow>()

    val maxWindows = mutableStateOf(2)

    /**
     * 同步器开关。要持久化：它原来只在内存里，每次重启应用都回到关闭，
     * 用户会以为"同步器又坏了" —— 实际上是开关自己关了。
     */
    val syncEnabled = mutableStateOf(false)

    fun setSyncEnabled(on: Boolean) {
        syncEnabled.value = on
        prefs.edit().putBoolean(K_SYNC, on).apply()
    }

    /** 脚本启用状态的变更计数，游戏窗口据此重新注入，无需重开 */
    val scriptsRevision = mutableStateOf(0)

    /** true = 标签模式（一次显示一个，其余后台常驻）；false = 一屏多窗口 */
    val tabMode = mutableStateOf(false)

    /** 标签模式下当前激活的窗口 id */
    val activeWindowId = mutableStateOf<String?>(null)

    /**
     * true = 深夜模式，false = 白天模式。默认深夜，与原先写死的深色一致。
     *
     * 只影响软件外壳（列表页、顶栏、弹窗）。游戏本体是 WebView 里的
     * canvas 自绘，配色管不到它。
     */
    val darkTheme = mutableStateOf(true)

    fun setDarkTheme(on: Boolean) {
        darkTheme.value = on
        prefs.edit().putBoolean(K_DARK, on).apply()
    }

    fun setTabMode(on: Boolean) {
        tabMode.value = on
        // 标签模式下同步器影响的是看不见的后台账号，切换时一律先关掉
        if (on) setSyncEnabled(false)
        if (on && activeWindowId.value == null) {
            activeWindowId.value = windows.firstOrNull()?.id
        }
        prefs.edit().putBoolean(K_TAB_MODE, on).apply()
    }

    init {
        load()
        ensureDefaultGroup()
        purgeBuiltinAccount()
        // 用包内脚本清单的指纹判断要不要同步，而不是一次性布尔标志。
        // 之前用布尔值，升级后新增的脚本（自动蟠桃、盐场无限视距）
        // 会因为标志已是 true 而被整段跳过，永远进不来。
        val stamp = PresetScripts.assetStamp(ctx)
        if (prefs.getString(K_PRESET_DONE, null) != stamp) {
            PresetScripts.syncInto(ctx, this)
            prefs.edit().putString(K_PRESET_DONE, stamp).apply()
        }
    }

    /** 预置脚本导入，指定初始启用状态 */
    fun addPresetScript(name: String, code: String, enabled: Boolean, locked: Boolean = false) {
        scripts.add(
            UserScript(
                name = name,
                code = code,
                // 常驻脚本必须启用，不给关
                enabled = enabled || locked,
                order = (scripts.maxOfOrNull { it.order } ?: 0) + 1,
                locked = locked,
                preset = true,
            )
        )
        save()
    }

    /** 升级时替换预置脚本的代码，保留用户的开关选择 */
    fun updatePresetScript(id: String, code: String, locked: Boolean) {
        val i = scripts.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = scripts[i]
        if (old.code == code && old.locked == locked && old.preset) return
        scripts[i] = old.copy(
            code = code,
            locked = locked,
            enabled = old.enabled || locked,
            preset = true,
        )
        save()
        scriptsRevision.value++
    }

    /**
     * 清掉新包已不再内置的预置脚本。
     *
     * 只删 preset=true 的项。历史版本存的脚本没有这个标记，靠名字兜底
     * 识别，避免把用户自己导入的同名脚本连带删掉。
     */
    fun removeStalePresets(keepNames: Set<String>): Int {
        val stale = scripts.filter { s ->
            s.name !in keepNames && (s.preset || s.name in LEGACY_PRESET_NAMES)
        }
        if (stale.isEmpty()) return 0
        scripts.removeAll(stale)
        save()
        scriptsRevision.value++
        return stale.size
    }

    // ---------- 读写 ----------

    private fun load() {
        runCatching {
            prefs.getString(K_GROUPS, null)?.let {
                groups.addAll(JSONArray(it).map(AccountGroup::fromJson))
            }
            prefs.getString(K_ACCOUNTS, null)?.let {
                accounts.addAll(JSONArray(it).map(AccountItem::fromJson))
            }
            prefs.getString(K_SCRIPTS, null)?.let {
                scripts.addAll(JSONArray(it).map(UserScript::fromJson))
            }
            maxWindows.value = prefs.getInt(K_MAX_WIN, 2)
            tabMode.value = prefs.getBoolean(K_TAB_MODE, false)
            darkTheme.value = prefs.getBoolean(K_DARK, true)
            // 标签模式下同步器无意义，恢复时直接按关闭处理
            syncEnabled.value = !tabMode.value && prefs.getBoolean(K_SYNC, false)
        }.onFailure { Log.w(TAG, "读取失败: ${it.message}") }
    }

    fun save() {
        prefs.edit()
            .putString(K_GROUPS, JSONArray(groups.map { it.toJson() }).toString())
            .putString(K_ACCOUNTS, JSONArray(accounts.map { it.toJson() }).toString())
            .putString(K_SCRIPTS, JSONArray(scripts.map { it.toJson() }).toString())
            .putInt(K_MAX_WIN, maxWindows.value)
            .apply()
    }

    private fun ensureDefaultGroup() {
        if (groups.none { it.id == AccountGroup.DEFAULT_ID }) {
            groups.add(
                0,
                AccountGroup(
                    id = AccountGroup.DEFAULT_ID,
                    name = "未分组",
                    colorArgb = AccountGroup.PALETTE[6].first,
                    order = 0,
                )
            )
            save()
        }
    }

    /**
     * 早期版本会把 assets 里的 bin 作为内置账号写进本地存储。
     * 现已改为完全由用户导入，这里做一次性清理，避免升级后残留。
     */
    private fun purgeBuiltinAccount() {
        if (prefs.getBoolean(K_PURGED_BUILTIN, false)) return
        val builtins = accounts.filter { it.builtin }
        if (builtins.isNotEmpty()) {
            val ids = builtins.map { it.id }.toSet()
            accounts.removeAll { it.builtin }
            windows.removeAll { it.accountId in ids }
            save()
            Log.i(TAG, "已清理内置账号 ${builtins.size} 个")
        }
        prefs.edit().putBoolean(K_PURGED_BUILTIN, true).apply()
    }

    // ---------- 账号 ----------

    fun addAccount(name: String, bytes: ByteArray): AccountItem {
        val item = AccountItem(
            name = name,
            binHex = bytes.toHex(),
            order = (accounts.maxOfOrNull { it.order } ?: 0) + 1,
        )
        accounts.add(item)
        save()
        return item
    }

    fun removeAccount(id: String) {
        accounts.removeAll { it.id == id }
        windows.removeAll { it.accountId == id }
        save()
    }

    fun renameAccount(id: String, newName: String) {
        updateAccount(id) { it.copy(name = newName) }
    }

    /** 主要账号固定在列表顶部，同一时间只有一个 */
    fun setPrimary(id: String) {
        for (i in accounts.indices) {
            val want = accounts[i].id == id
            if (accounts[i].isPrimary != want) {
                accounts[i] = accounts[i].copy(isPrimary = want)
            }
        }
        save()
    }

    fun clearPrimary(id: String) {
        updateAccount(id) { it.copy(isPrimary = false) }
    }

    fun moveToGroup(accountId: String, groupId: String) {
        updateAccount(accountId) { it.copy(groupId = groupId) }
    }

    fun reorderAccount(from: Int, to: Int) {
        if (from !in accounts.indices || to !in accounts.indices) return
        val item = accounts.removeAt(from)
        accounts.add(to, item)
        for (i in accounts.indices) {
            if (accounts[i].order != i) accounts[i] = accounts[i].copy(order = i)
        }
        save()
    }

    /** 排序：主要账号置顶，其余按 order */
    fun accountsInGroup(groupId: String): List<AccountItem> =
        accounts.filter { it.groupId == groupId }
            .sortedWith(compareByDescending<AccountItem> { it.isPrimary }.thenBy { it.order })

    private inline fun updateAccount(id: String, block: (AccountItem) -> AccountItem) {
        val i = accounts.indexOfFirst { it.id == id }
        if (i < 0) return
        accounts[i] = block(accounts[i])
        save()
    }

    // ---------- 分组 ----------

    fun addGroup(name: String, color: Int): AccountGroup {
        val g = AccountGroup(
            name = name, colorArgb = color,
            order = (groups.maxOfOrNull { it.order } ?: 0) + 1,
        )
        groups.add(g)
        save()
        return g
    }

    fun renameGroup(id: String, newName: String) {
        updateGroup(id) { it.copy(name = newName) }
    }

    fun setGroupColor(id: String, color: Int) {
        updateGroup(id) { it.copy(colorArgb = color) }
    }

    fun removeGroup(id: String) {
        if (id == AccountGroup.DEFAULT_ID) return
        for (i in accounts.indices) {
            if (accounts[i].groupId == id) {
                accounts[i] = accounts[i].copy(groupId = AccountGroup.DEFAULT_ID)
            }
        }
        groups.removeAll { it.id == id }
        save()
    }

    private inline fun updateGroup(id: String, block: (AccountGroup) -> AccountGroup) {
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return
        groups[i] = block(groups[i])
        save()
    }

    // ---------- 脚本 ----------

    fun addScript(name: String, code: String): UserScript {
        val s = UserScript(
            name = name, code = code,
            order = (scripts.maxOfOrNull { it.order } ?: 0) + 1,
        )
        scripts.add(s)
        save()
        scriptsRevision.value++
        return s
    }

    /**
     * 互斥脚本组:同组同时只能开启一个,开启一个时自动关闭同组其他。
     * 蟠桃园自动化脚本会发同样的上船请求,同时开两个会互相抢。
     * 按 displayName(文件名去掉 .js)匹配,用户自己导入的同名脚本同样生效。
     */
    private val MUTEX_GROUPS: List<Set<String>> = listOf(
        setOf("自动蟠桃", "蟠桃择船"),
    )

    /**
     * 开关脚本。开启互斥组内的脚本时,自动关闭同组其他已开启的脚本。
     * 返回被自动关闭的脚本名(供 UI 提示),没有则为空列表。
     */
    fun toggleScript(id: String, enabled: Boolean): List<String> {
        val i = scripts.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        if (scripts[i].locked) return emptyList()
        scripts[i] = scripts[i].copy(enabled = enabled)
        val disabled = mutableListOf<String>()
        if (enabled) {
            val group = MUTEX_GROUPS.firstOrNull { scripts[i].displayName in it }
            if (group != null) {
                for (j in scripts.indices) {
                    if (j == i || !scripts[j].enabled) continue
                    if (scripts[j].displayName in group) {
                        scripts[j] = scripts[j].copy(enabled = false)
                        disabled.add(scripts[j].displayName)
                    }
                }
            }
        }
        save()
        scriptsRevision.value++
        return disabled
    }

    fun removeScript(id: String) {
        if (scripts.firstOrNull { it.id == id }?.locked == true) return
        scripts.removeAll { it.id == id }
        save()
        scriptsRevision.value++
    }

    /**
     * 已启用脚本列表，元素为 (id, 显示名, 代码)。
     * 按脚本粒度返回，便于注入侧逐个记录状态、避免重复执行。
     */
    fun enabledScripts(): List<Triple<String, String, String>> =
        scripts.filter { it.enabled }.sortedBy { it.order }
            .map { Triple(it.id, it.displayName, it.code) }

    // ---------- 窗口 ----------

    fun openWindow(account: AccountItem): GameWindow? {
        if (windows.size >= maxWindows.value) return null
        val w = GameWindow(
            accountId = account.id,
            title = account.displayName,
            isSyncMaster = windows.isEmpty(),
        )
        windows.add(w)
        // 新开的窗口直接成为标签模式下的当前项
        activeWindowId.value = w.id
        return w
    }

    fun closeWindow(id: String) {
        val idx = windows.indexOfFirst { it.id == id }
        val wasMaster = windows.getOrNull(idx)?.isSyncMaster == true
        windows.removeAll { it.id == id }
        if (wasMaster && windows.isNotEmpty()) {
            windows[0] = windows[0].copy(isSyncMaster = true)
        }
        // 关掉的正是当前标签时，落到相邻一项
        if (activeWindowId.value == id) {
            activeWindowId.value = windows.getOrNull(idx.coerceAtMost(windows.lastIndex))?.id
        }
    }

    /** 标签模式下的当前窗口，越界或未设置时回退到第一个 */
    fun currentWindow(): GameWindow? =
        windows.find { it.id == activeWindowId.value } ?: windows.firstOrNull()

    fun accountOf(w: GameWindow): AccountItem? = accounts.find { it.id == w.accountId }
}

fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    val digits = "0123456789abcdef"
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        hex[i * 2] = digits[v ushr 4]
        hex[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(hex)
}
