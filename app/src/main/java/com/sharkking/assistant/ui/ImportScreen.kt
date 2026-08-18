package com.sharkking.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sharkking.assistant.data.AppStore
import com.sharkking.assistant.importer.ImportStage
import com.sharkking.assistant.importer.ImportViewModel

/**
 * 导入方式。
 *
 * 三条路径是对等的：都只是"取得账号级登录态"的不同手段，
 * 拿到之后查区服、展开成每区服一份 bin 的流程完全共用。
 */
private enum class ImportTab(val label: String) {
    Scan("微信扫码"), Sms("手机号"), Bin("bin 文件")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    store: AppStore,
    onClose: () -> Unit,
    vm: ImportViewModel = viewModel(),
) {
    var tab by remember { mutableStateOf(ImportTab.Scan) }
    var imported by remember { mutableStateOf(0) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("导入账号", style = MaterialTheme.typography.titleMedium) },
            expandedHeight = 44.dp,
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, "返回")
                }
            },
        )

        if (vm.stage.value == ImportStage.PICKING) {
            RolePicker(
                vm = vm,
                onImport = {
                    val bins = vm.buildSelected()
                    bins.forEach { store.addAccount(it.fileName.removeSuffix(".bin"), it.bytes) }
                    imported = bins.size
                    vm.reset()
                },
                onCancel = { vm.reset() },
            )
            return@Column
        }

        if (imported > 0) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("已导入 $imported 个账号", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "去账号页查看。点账号卡片即可启动游戏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { imported = 0 }) { Text("继续导入") }
                        TextButton(onClick = onClose) { Text("完成") }
                    }
                }
            }
            return@Column
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            ImportTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t; vm.reset() },
                    text = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }

        if (vm.message.value.isNotEmpty()) {
            Text(
                vm.message.value,
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (tab) {
            ImportTab.Scan -> ScanPane(vm)
            ImportTab.Sms -> SmsPane(vm)
            ImportTab.Bin -> BinPane(vm) { uris ->
                if (uris.isEmpty()) {
                    vm.message.value = "没有选择文件"
                } else {
                    var n = 0
                    uris.forEach { uri ->
                        runCatching {
                            val name = queryDocName(ctx, uri) ?: "未命名.bin"
                            if (!name.endsWith(".bin", true)) return@runCatching
                            val bytes = ctx.contentResolver.openInputStream(uri)!!
                                .use { it.readBytes() }
                            store.addAccount(name, bytes)
                            n++
                        }
                    }
                    if (n > 0) imported = n else vm.message.value = "文件读取失败"
                }
            }
        }
    }
}

/**
 * bin 文件导入，两种用法都在这里。
 *
 * 直接添加：多选、原样存，适合手上已有一批区服 bin。
 * 反查区服：单选，把该账号下所有区服的角色列出来挑。
 *
 * bin 里的凭据本身是账号级的（`encryptCombUser` 等），区服绑定只体现在
 * serverId 一个字段上，查询时置空即可当账号级用 —— 所以账号级 bin
 * 和已绑定区服的 bin 都能反查。
 */
@Composable
private fun BinPane(vm: ImportViewModel, onAddDirect: (List<Uri>) -> Unit) {
    val ctx = LocalContext.current

    val queryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            vm.message.value = "文件读取失败"
            return@rememberLauncherForActivityResult
        }
        vm.queryFromBin(bytes)
    }

    val directPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> onAddDirect(uris) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { queryPicker.launch(arrayOf("*/*")) },
            enabled = !vm.busy.value,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy.value) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("选一个 bin，反查全部区服")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "读出文件里绑定的账号，把该账号下所有区服的角色列出来供你挑选。" +
                "已绑定某个区服的 bin 也能反查。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))
        HorizontalDivider()
        Spacer(Modifier.height(22.dp))

        OutlinedButton(
            onClick = { directPicker.launch(arrayOf("*/*")) },
            enabled = !vm.busy.value,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("直接添加 bin 文件（可多选）") }
        Spacer(Modifier.height(6.dp))
        Text(
            "不查区服，原样导入。适合手上已经有一批区服 bin 的情况。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanPane(vm: ImportViewModel) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bmp = vm.qrBitmap.value
        if (bmp != null) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(240.dp),
            ) {
                Image(
                    bmp.asImageBitmap(),
                    "微信登录二维码",
                    Modifier.fillMaxSize().padding(8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(vm.qrHint.value, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.startQrLogin() }) { Text("刷新二维码") }
        } else {
            Spacer(Modifier.height(40.dp))
            if (vm.busy.value) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("获取中...", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = { vm.startQrLogin() }) { Text("获取二维码") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "扫码后会读取该微信绑定的全部游戏角色，" +
                "为每个区服生成一份账号数据。授权页显示的是游戏本身。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SmsPane(vm: ImportViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = vm.mobile.value,
            onValueChange = { vm.mobile.value = it.filter(Char::isDigit).take(11) },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Phone
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.smsCode.value,
                onValueChange = { vm.smsCode.value = it.filter(Char::isDigit).take(6) },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { vm.sendSms() },
                enabled = vm.countdown.value == 0 && !vm.busy.value,
            ) {
                Text(if (vm.countdown.value > 0) "${vm.countdown.value}s" else "发送验证码")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.loginBySms() },
            enabled = !vm.busy.value,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy.value) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("登录并查询区服")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "使用游戏绑定的手机号登录，会读取该账号下全部区服的角色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RolePicker(
    vm: ImportViewModel,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${vm.selected.size}/${vm.roles.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.selectAll(vm.selected.size < vm.roles.size) }) {
                Text(if (vm.selected.size < vm.roles.size) "全选" else "全不选")
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.roles) { i, role ->
                val on = vm.selected.contains(i)
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = { vm.toggle(i) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            role.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${role.serverId}服 · 战力${role.powerText()} · ID${role.roleId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onImport,
                enabled = vm.selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("导入 ${vm.selected.size} 个") }
        }
    }
}

/** 取系统选择器返回的文件名，用于判断后缀和命名账号 */
private fun queryDocName(ctx: android.content.Context, uri: Uri): String? {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment
}
