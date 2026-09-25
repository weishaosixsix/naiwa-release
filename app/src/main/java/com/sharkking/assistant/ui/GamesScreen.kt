package com.sharkking.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sharkking.assistant.core.GameWebViewHolder
import com.sharkking.assistant.data.AppStore
import com.sharkking.assistant.data.GameWindow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(store: AppStore, baseUrl: String?) {
    // 每个窗口对应一个 WebView 实例，跨重组保持
    val holders = remember { mutableMapOf<String, GameWebViewHolder>() }
    // 各窗口的脚本状态，标签栏与标题条共用
    val statusOf = remember { mutableStateMapOf<String, String>() }

    DisposableEffect(Unit) {
        onDispose {
            holders.values.forEach { it.destroy() }
            holders.clear()
        }
    }

    // 窗口关掉后回收对应实例
    LaunchedEffect(store.windows.size) {
        val alive = store.windows.map { it.id }.toSet()
        holders.keys.filter { it !in alive }.forEach {
            holders.remove(it)?.destroy()
        }
    }

    // 脚本开关变动时立即注入到所有已开窗口，不必重开
    LaunchedEffect(store.scriptsRevision.value) {
        if (store.scriptsRevision.value == 0) return@LaunchedEffect
        holders.values.forEach { it.injectUserScripts() }
    }

    // 同步器开关变动时立即补装。原来这个开关只改了状态、没有人重新注入，
    // 必须先关掉窗口重开才生效 —— 这正是「多开时同步不生效」的原因。
    LaunchedEffect(store.syncEnabled.value) {
        holders.values.forEach { it.injectSyncer() }
    }

    val tabMode = store.tabMode.value
    val current = store.currentWindow()

    // 标签模式：只有当前标签保持前台帧率，其余降到 5 帧省电；
    // 同步源也跟着当前标签走。
    LaunchedEffect(tabMode, current?.id, store.windows.size) {
        store.windows.forEach { w ->
            val active = w.id == current?.id
            holders[w.id]?.let {
                it.setBackgrounded(tabMode && !active)
            }
        }
    }

    val tip = rememberTipState()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // 顶栏压到 38dp，给游戏画面让出高度
        TopAppBar(
            title = {
                Text(
                    "窗口 ${store.windows.size}/${store.maxWindows.value}",
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            expandedHeight = 38.dp,
            actions = {
                // 显示模式：标签 / 一屏多窗口
                IconButton(onClick = {
                    val to = !tabMode
                    // setTabMode 切到标签模式时会顺带关掉同步器，
                    // 得在调用前记下原值，否则用户会以为同步自己关了
                    val syncWasOn = store.syncEnabled.value
                    store.setTabMode(to)
                    val name = if (to) "标签模式" else "分屏模式"
                    tip.show(
                        if (to && syncWasOn) "已切换到$name，同步器已关闭"
                        else "已切换到$name"
                    )
                }) {
                    Icon(
                        if (tabMode) Icons.Filled.ViewCarousel else Icons.Filled.GridView,
                        if (tabMode) "标签模式" else "分屏模式",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // 同步器总开关
                IconButton(onClick = {
                    val on = !store.syncEnabled.value
                    store.setSyncEnabled(on)
                    tip.show(if (on) "已开启同步器" else "已关闭同步器")
                }) {
                    Icon(
                        Icons.Filled.Sync,
                        "同步器",
                        tint = if (store.syncEnabled.value)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 窗口上限
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) {
                    Text("上限 ${store.maxWindows.value}")
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    (1..AppStore.WINDOW_LIMIT).forEach { n ->
                        DropdownMenuItem(
                            text = { Text("$n 个窗口") },
                            onClick = {
                                store.maxWindows.value = n
                                store.save()
                                expanded = false
                            },
                        )
                    }
                }
            }
        )

        if (baseUrl == null) {
            EmptyHint("HTTP服务器未就绪")
            return@Column
        }
        if (store.windows.isEmpty()) {
            EmptyHint("暂无游戏窗口，去账号页点击账号卡片打开游戏")
            return@Column
        }

        if (tabMode) {
            // 自绘标签栏而非 ScrollableTabRow：后者按下标取内部 tabPositions，
            // 而 composition 与 subcompose 测量阶段读到的窗口数可能不一致，
            // 新开窗口的那一帧就会 IndexOutOfBounds。这里不做任何索引操作。
            TabStrip(store = store, currentId = current?.id, statusOf = statusOf)
        }

        // 所有窗口都留在组合树里、都是全屏尺寸并互相重叠。
        // 隐藏靠 WebView 自身的 visibility（见 setBackgrounded）：
        // 布局尺寸不变所以不会重排，同时后台窗口不参与绘制。
        if (tabMode) {
            // 标签模式：所有窗口都是全屏尺寸、互相重叠，靠 WebView 自身的
            // visibility 切换（见 setBackgrounded），布局尺寸不变所以不会重排。
            // 全部子项都在同一个 Box 下、只按 id 增删，本身就是稳定的。
            Box(Modifier.fillMaxSize()) {
                store.windows.forEach { w ->
                    key(w.id) {
                        WindowPane(
                            store = store,
                            window = w,
                            baseUrl = baseUrl ?: "",
                            holders = holders,
                            compact = false,
                            onStatus = { statusOf[w.id] = it },
                            // 标题条只跟当前标签走，避免多份标题条叠在一起
                            showBar = w.id == current?.id,
                        )
                    }
                }
            }
        } else {
            // 分屏模式：所有窗口都放在同一个 Box 里，各自按网格算出的偏移与尺寸摆位。
            //
            // 不能用「按窗口数生成不同行列结构」的写法：窗口数一变，列数和每个窗口
            // 所属的行就变，Compose 会把移到另一行/列的面板当成新节点 —— 里面的
            // WebView 被重建，那个窗口的游戏就得从 CDN 重新下载一遍。实测表现是
            // 关掉一个窗口，其余窗口全部重新加载游戏，又慢又费流量。
            // 这里保持树的结构恒定（一个 Box + N 个带 key 的子项），增删窗口时
            // 已有的子项父容器不变、key 不变，状态与 WebView 都得以保留。
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 游戏是竖屏内容，纯竖向等分会把每格压成宽扁条，分列后单格比例才接近屏幕
                val cols = columnsFor(store.windows.size)
                val rows = (store.windows.size + cols - 1) / cols
                val cellW = maxWidth / cols
                val cellH = maxHeight / rows
                store.windows.forEachIndexed { i, w ->
                    key(w.id) {
                        Box(
                            Modifier
                                .offset(x = cellW * (i % cols), y = cellH * (i / cols))
                                .size(cellW, cellH)
                        ) {
                            WindowPane(
                                store = store,
                                window = w,
                                baseUrl = baseUrl ?: "",
                                holders = holders,
                                compact = store.windows.size > 1,
                                onStatus = { statusOf[w.id] = it },
                                showBar = true,
                            )
                        }
                    }
                }
            }
        }
    }

    // 浮在顶栏下方，盖在游戏画面之上
    TipHost(tip, Modifier.padding(top = 42.dp))
    }
}

/**
 * 竖屏手机下的分列策略。屏幕约 9:16，游戏内容同为竖屏，
 * 2×2 时单格比例与整屏一致，是多开的最佳情形。
 */
/**
 * 标签栏：横向可滑动，只列出已打开的窗口。
 * 完全按 id 匹配，不依赖下标，规避 TabRow 的越界问题。
 */
@Composable
private fun TabStrip(
    store: AppStore,
    currentId: String?,
    statusOf: Map<String, String>,
) {
    val scroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).horizontalScroll(scroll)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            store.windows.forEach { w ->
                key(w.id) {
                    TabChip(
                        title = w.title,
                        status = shortStatus(statusOf[w.id]),
                        selected = w.id == currentId,
                        onClick = { store.activeWindowId.value = w.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    title: String,
    status: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(end = 5.dp).height(27.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title + if (status.isNotEmpty()) "  $status" else "",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
        }
    }
}

/** 标签宽度有限，把注入状态压成几个字 */
private fun shortStatus(raw: String?): String = when {
    raw == null -> ""
    raw.startsWith("等待游戏加载") -> "加载中"
    raw.startsWith("已全部注入") -> "就绪"
    raw.startsWith("已注入") -> "就绪"
    raw.startsWith("无启用脚本") -> ""
    raw.startsWith("页面未就绪") -> "加载中"
    raw.startsWith("超时") -> "超时"
    raw.startsWith("执行出错") -> "出错"
    else -> ""
}

private fun columnsFor(count: Int): Int = when {
    count <= 2 -> 1   // 1~2 个走单列上下排，画面比横向切两半更宽
    count <= 6 -> 2   // 3~4 个时 2×2 单格仍是 9:16
    else -> 3
}

@Composable
private fun WindowPane(
    store: AppStore,
    window: GameWindow,
    baseUrl: String,
    holders: MutableMap<String, GameWebViewHolder>,
    compact: Boolean,
    onStatus: (String) -> Unit,
    showBar: Boolean = true,
) {
    val account = store.accountOf(window) ?: return
    val barHeight = if (compact) 22.dp else 30.dp
    val iconSize = if (compact) 15.dp else 19.dp
    var scriptStatus by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // 标签模式下各窗口互相重叠，非当前项的标题条要让位，
        // 但必须留出等高占位，否则 WebView 高度变化会引发重排。
        if (!showBar) {
            Spacer(Modifier.fillMaxWidth().height(barHeight))
        } else
        // 窗口标题条：标题隐藏 .bin 后缀。多窗口时压扁，给画面让出高度
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().height(barHeight)
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(window.title)
                        if (scriptStatus.isNotEmpty()) append(" · $scriptStatus")
                    },
                    style = if (compact) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 补注入尚未生效的脚本；已注入过的不会重复执行
                IconButton(
                    onClick = { holders[window.id]?.injectUserScripts() },
                    modifier = Modifier.size(barHeight),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, "补注入脚本",
                        modifier = Modifier.size(iconSize),
                    )
                }
                IconButton(
                    onClick = { holders[window.id]?.reload() },
                    modifier = Modifier.size(barHeight),
                ) {
                    Icon(
                        Icons.Filled.Refresh, "重新加载页面",
                        modifier = Modifier.size(iconSize),
                    )
                }
                IconButton(
                    onClick = { store.closeWindow(window.id) },
                    modifier = Modifier.size(barHeight),
                ) {
                    Icon(
                        Icons.Filled.Close, "关闭",
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 同一窗口若被重建（正常情况下 movableContentOf 已经避免），
                // 先销毁旧实例：旧的 WebView 会继续跑游戏、继续下资源，白吃流量
                holders.remove(window.id)?.destroy()
                val holder = GameWebViewHolder(
                    ctx = ctx,
                    binHex = account.binHex,
                    binLabel = account.displayName,
                    scriptsProvider = { store.enabledScripts() },
                    syncEnabledProvider = { store.syncEnabled.value },
                    onSyncTouch = { x, y, phase ->
                        // 本窗口的触摸 -> 广播给其余窗口
                        store.windows.filter { it.id != window.id }.forEach { other ->
                            holders[other.id]?.applySyncTouch(x, y, phase)
                        }
                    },
                    onScriptStatus = { scriptStatus = it; onStatus(it) },
                )
                holders[window.id] = holder
                holder.load("$baseUrl/index.html")
                holder.webView
            },
        )
    }
}
