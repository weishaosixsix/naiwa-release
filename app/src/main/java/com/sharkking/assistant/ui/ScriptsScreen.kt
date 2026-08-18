package com.sharkking.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sharkking.assistant.BuildConfig
import com.sharkking.assistant.core.ReleaseInfo
import com.sharkking.assistant.core.UpdateChecker
import com.sharkking.assistant.core.UpdateResult
import com.sharkking.assistant.data.AppStore
import com.sharkking.assistant.data.UserScript
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(store: AppStore) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf<UserScript?>(null) }
    val tip = rememberTipState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            tip.show("没有选择文件")
            return@rememberLauncherForActivityResult
        }
        var n = 0
        uris.forEach { uri ->
            runCatching {
                val name = queryName(ctx, uri) ?: "未命名.js"
                if (!name.endsWith(".js", true)) return@runCatching
                val code = ctx.contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                store.addScript(name, code)
                n++
            }
        }
        tip.show(if (n > 0) "共导入 $n 个文件" else "文件读取失败")
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("脚本管理", style = MaterialTheme.typography.titleMedium) },
            expandedHeight = 44.dp,
            actions = {
                // 主题切换。只改软件外壳配色，游戏画面由 WebView 自绘，不受影响
                val dark = store.darkTheme.value
                IconButton(onClick = {
                    store.setDarkTheme(!dark)
                    tip.show(if (!dark) "已切换到深夜模式" else "已切换到白天模式")
                }) {
                    Icon(
                        if (dark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        if (dark) "深夜模式" else "白天模式",
                    )
                }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, "导入脚本")
                }
            }
        )

        if (store.scripts.isEmpty()) {
            EmptyHint("点击右上角 + 导入 .js 脚本文件")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "开启脚本后可在游戏窗口标题栏点 ▶ 补注入，已生效的不会重复执行。" +
                        "关闭脚本必须点 ⟳ 刷新游戏才会移除 —— 已经跑起来的 JS " +
                        "无法撤销，点 ▶ 对它没有作用。",
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(store.scripts.sortedBy { it.order }, key = { it.id }) { s ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f).clickable(enabled = !s.locked) { target = s }
                        ) {
                            Text(s.displayName, fontWeight = FontWeight.Medium)
                            Text(
                                if (s.locked) "${s.code.length} 字符 · 必需，无法关闭"
                                else "${s.code.length} 字符" + if (s.enabled) " · 已启用" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (s.locked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = s.enabled,
                            enabled = !s.locked,
                            onCheckedChange = { store.toggleScript(s.id, it) },
                        )
                    }
                }
            }
            item { VersionFooter(tip) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    target?.let { s ->
        ModalBottomSheet(onDismissRequest = { target = null }) {
            Text(
                s.displayName,
                Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            SheetRow(if (s.enabled) "禁用" else "启用") {
                store.toggleScript(s.id, !s.enabled); target = null
            }
            SheetRow("删除", danger = true) {
                store.removeScript(s.id); target = null
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    TipHost(tip, Modifier.padding(top = 48.dp))
    }
}

/**
 * 版本号与手动检查更新。放在列表底部，平时不占视线，
 * 但用户报问题时能一眼看到自己是哪个版本。
 */
@Composable
private fun VersionFooter(tip: TipState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<ReleaseInfo?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "当前版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "本软件免费，如遇收费即为诈骗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    when (val r = UpdateChecker.check()) {
                        is UpdateResult.Available -> found = r.info
                        is UpdateResult.UpToDate -> tip.show("已是最新版本")
                        is UpdateResult.Failed -> tip.show("检查失败：${r.reason}")
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "检查中" else "检查更新") }
    }

    found?.let { info ->
        UpdateDialog(info = info, onDismiss = { found = null })
    }
}

private fun queryName(ctx: android.content.Context, uri: Uri): String? {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment
}
