package com.sharkking.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.sharkking.assistant.data.AccountGroup
import com.sharkking.assistant.data.AccountItem
import com.sharkking.assistant.data.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(store: AppStore, onOpenGame: () -> Unit, onOpenImport: () -> Unit = {}) {
    val ctx = LocalContext.current
    var actionTarget by remember { mutableStateOf<AccountItem?>(null) }
    var showNewGroup by remember { mutableStateOf(false) }
    var renameGroupTarget by remember { mutableStateOf<AccountGroup?>(null) }
    var moveTarget by remember { mutableStateOf<AccountItem?>(null) }
    var renameTarget by remember { mutableStateOf<AccountItem?>(null) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var toast by remember { mutableStateOf<String?>(null) }
    // 待导出的账号，等用户在系统选择器里选好位置后写入
    var exportPending by remember { mutableStateOf<AccountItem?>(null) }

    // 导出单个账号。走系统文件选择器，不需要任何存储权限
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val acc = exportPending
        exportPending = null
        if (uri == null || acc == null) {
            toast = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        toast = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(acc.binHex.hexToBytes()) }
            "已导出 ${acc.displayName}"
        }.getOrElse { "导出失败: ${it.message}" }
    }

    // 批量导出到某个文件夹
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? ->
        if (tree == null) {
            toast = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        toast = runCatching { exportAllTo(ctx, tree, store.accounts.toList()) }
            .getOrElse { "导出失败: ${it.message}" }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账号管理", style = MaterialTheme.typography.titleMedium) },
            expandedHeight = 44.dp,
            actions = {
                if (store.accounts.isNotEmpty()) {
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.Save, "导出全部账号")
                    }
                }
                IconButton(onClick = { showNewGroup = true }) {
                    Icon(Icons.Filled.CreateNewFolder, "新建分组")
                }
                // 统一入口：扫码 / 手机号 / bin（直接添加或反查区服）都在导入页里
                IconButton(onClick = onOpenImport) {
                    Icon(Icons.Filled.Add, "导入账号")
                }
            }
        )

        if (store.accounts.isEmpty()) {
            EmptyHint("点右上角 + 导入账号：微信扫码、手机号，或选 bin 文件")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            val sortedGroups = store.groups.sortedBy { it.order }
            sortedGroups.forEach { group ->
                val list = store.accountsInGroup(group.id)
                val isCollapsed = collapsed[group.id] == true

                item(key = "h_${group.id}") {
                    GroupHeader(
                        group = group,
                        count = list.size,
                        collapsed = isCollapsed,
                        onToggle = { collapsed[group.id] = !isCollapsed },
                        onLongClick = { renameGroupTarget = group },
                    )
                }
                if (!isCollapsed) {
                    items(list, key = { it.id }) { acc ->
                        AccountCard(
                            account = acc,
                            groupColor = Color(group.colorArgb),
                            onClick = { actionTarget = acc },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- 账号操作面板 ----
    actionTarget?.let { acc ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Text(
                acc.displayName,
                Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            SheetRow("打开游戏") {
                val w = store.openWindow(acc)
                actionTarget = null
                if (w == null) {
                    toast = "最多同时开启 ${store.maxWindows.value} 个游戏窗口"
                } else {
                    onOpenGame()
                }
            }
            SheetRow(if (acc.isPrimary) "取消主要账号" else "设为主要账号") {
                if (acc.isPrimary) store.clearPrimary(acc.id) else store.setPrimary(acc.id)
                actionTarget = null
            }
            SheetRow("移动到分组...") { moveTarget = acc; actionTarget = null }
            SheetRow("重命名") { renameTarget = acc; actionTarget = null }
            SheetRow("导出为 .bin 文件") {
                exportPending = acc
                actionTarget = null
                saver.launch("${acc.displayName}.bin")
            }
            SheetRow("删除", danger = true) {
                store.removeAccount(acc.id); actionTarget = null
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // ---- 移动到分组 ----
    moveTarget?.let { acc ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("选择目标分组") },
            text = {
                Column {
                    store.groups.sortedBy { it.order }.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    store.moveToGroup(acc.id, g.id); moveTarget = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(14.dp).clip(CircleShape)
                                    .background(Color(g.colorArgb))
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(g.name)
                            if (g.id == acc.groupId) {
                                Spacer(Modifier.weight(1f)); Text("当前")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ moveTarget = null }) { Text("取消") } },
        )
    }

    // ---- 重命名账号 ----
    renameTarget?.let { acc ->
        TextInputDialog(
            title = "重命名",
            initial = acc.displayName,
            onConfirm = { store.renameAccount(acc.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    // ---- 新建分组 ----
    if (showNewGroup) {
        GroupEditDialog(
            title = "创建分组",
            initialName = "",
            initialColor = AccountGroup.PALETTE[0].first,
            onConfirm = { n, c -> store.addGroup(n, c); showNewGroup = false },
            onDismiss = { showNewGroup = false },
        )
    }

    // ---- 分组重命名/改色/删除 ----
    renameGroupTarget?.let { g ->
        GroupEditDialog(
            title = "分组管理",
            initialName = g.name,
            initialColor = g.colorArgb,
            allowDelete = g.id != AccountGroup.DEFAULT_ID,
            onDelete = { store.removeGroup(g.id); renameGroupTarget = null },
            onConfirm = { n, c ->
                store.renameGroup(g.id, n); store.setGroupColor(g.id, c)
                renameGroupTarget = null
            },
            onDismiss = { renameGroupTarget = null },
        )
    }

    // 不走系统 Toast：MIUI 关掉通知权限后会连带屏蔽，提示可能完全不显示
    val tip = rememberTipState()
    toast?.let {
        LaunchedEffect(it) {
            tip.show(it)
            toast = null
        }
    }
    TipHost(tip, Modifier.padding(top = 48.dp))
    }
}

@Composable
private fun GroupHeader(
    group: AccountGroup,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(Color(group.colorArgb))
            )
            Spacer(Modifier.width(10.dp))
            Text(group.name, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Text("($count)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLongClick) { Text("管理") }
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountItem,
    groupColor: Color,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(groupColor))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.displayName, fontWeight = FontWeight.Medium)
                Text(
                    "${account.sizeBytes} 字节",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (account.isPrimary) {
                Icon(Icons.Filled.Star, "主要账号", tint = Color(0xFFFFC107))
            }
        }
    }
}

@Composable
fun SheetRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        color = if (danger) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun String.hexToBytes(): ByteArray {
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}

/** 批量写入用户选中的目录，返回给用户看的结果文案 */
private fun exportAllTo(
    ctx: android.content.Context,
    tree: Uri,
    accounts: List<AccountItem>,
): String {
    val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree)
        ?: return "无法访问所选文件夹"
    var ok = 0
    var fail = 0
    for (acc in accounts) {
        // 文件名里的非法字符会让创建失败
        val safe = acc.displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val created = dir.createFile("application/octet-stream", "$safe.bin")
        if (created == null) { fail++; continue }
        val done = runCatching {
            ctx.contentResolver.openOutputStream(created.uri)!!
                .use { it.write(acc.binHex.hexToBytes()) }
        }.isSuccess
        if (done) ok++ else fail++
    }
    return if (fail == 0) "已导出 $ok 个账号" else "已导出 $ok 个，失败 $fail 个"
}
