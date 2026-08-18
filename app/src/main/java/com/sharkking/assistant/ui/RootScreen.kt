package com.sharkking.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.sharkking.assistant.BuildConfig
import com.sharkking.assistant.core.ReleaseInfo
import com.sharkking.assistant.core.UpdateChecker
import com.sharkking.assistant.core.UpdateResult
import com.sharkking.assistant.data.AppStore

enum class Tab(val label: String) {
    Accounts("账号"), Scripts("脚本"), Games("游戏")
}

@Composable
fun RootScreen(store: AppStore, baseUrl: String?) {
    var tab by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    // 每次启动都提示，避免被二次分发者拿去收费
    var showNotice by remember { mutableStateOf(true) }

    if (showNotice) {
        FreeNoticeDialog(onConfirm = { showNotice = false })
    }

    // 免费声明确认后再查更新，两个弹窗不会叠在一起
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(showNotice) {
        if (showNotice) return@LaunchedEffect
        val r = UpdateChecker.check()
        if (r is UpdateResult.Available) update = r.info
    }
    update?.let { info ->
        UpdateDialog(info = info, onDismiss = { update = null })
    }

    Box(Modifier.fillMaxSize()) {

    Scaffold(
        bottomBar = {
            // 默认 NavigationBar 有 80dp 高，这里压到 52dp
            NavigationBar(modifier = Modifier.height(52.dp)) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Accounts -> Icons.Filled.People
                                    Tab.Scripts -> Icons.Filled.Code
                                    Tab.Games -> Icons.Filled.SportsEsports
                                },
                                contentDescription = t.label,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        label = {
                            val n = if (t == Tab.Games && store.windows.isNotEmpty())
                                "${t.label}(${store.windows.size})" else t.label
                            Text(n, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (Tab.entries[tab]) {
                Tab.Accounts -> AccountsScreen(
                    store = store,
                    onOpenGame = { tab = Tab.Games.ordinal },
                    onOpenImport = { showImport = true },
                )
                Tab.Scripts -> ScriptsScreen(store)
                Tab.Games -> Unit
            }
            // 游戏页始终留在组合树里。若跟随 when 分支进出，WebView 会被销毁，
            // 游戏被迫重新加载。这里保持布局尺寸不变（避免 WebView 重排导致
            // 画面错乱），只在非游戏标签时整层平移出屏幕。
            val onGames = tab == Tab.Games.ordinal
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 只平移，不设 alpha。alpha<1 会强制分配整屏离屏缓冲，
                        // 叠上多个全屏 WebView 硬件层后会把 GPU 拖崩。
                        if (!onGames) translationX = 1e5f
                    }
            ) {
                GamesScreen(store, baseUrl)
            }
        }
    }

    // 导入页盖满全屏（含底栏），退出后回到账号页
    if (showImport) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ImportScreen(store = store, onClose = { showImport = false })
        }
    }

    }
}

/**
 * 免费声明。只能点「我知道了」关闭：点外部或返回键都不消失，
 * 确保用户真的看到这段话。
 */
@Composable
private fun FreeNoticeDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("免费声明") },
        text = {
            Column {
                Text(
                    "本软件完全免费提供，不存在任何收费项目。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "如果你是通过付费方式获得本软件的，说明你被骗了，请向对方索要退款。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "软件不会收取费用、不售卖卡密、不限制使用次数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("我知道了") }
        },
    )
}

/**
 * 新版本提示。点「下载」交给浏览器，不在应用内装包，
 * 这样不用申请安装未知来源应用的权限。
 */
@Composable
fun UpdateDialog(info: ReleaseInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "当前 ${BuildConfig.VERSION_NAME}，新版 ${info.versionName}（${info.sizeText}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "下载走加速通道，完成后点通知栏的文件安装即可，账号和脚本设置都会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = UpdateChecker.preferredUrl(info.rawUrl)
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                onDismiss()
            }) { Text("下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}
