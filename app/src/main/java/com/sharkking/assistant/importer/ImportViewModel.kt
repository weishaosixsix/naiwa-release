package com.sharkking.assistant.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入流程的阶段 */
enum class ImportStage { IDLE, QR_WAITING, LOGGING_IN, QUERYING, PICKING, DONE }

/**
 * 扫码 / 短信登录导入的状态与流程编排。
 *
 * 轮询用协程而非裸定时器，随 ViewModel 一起取消，避免离开界面后
 * 继续打微信接口。
 */
class ImportViewModel : ViewModel() {

    val stage = mutableStateOf(ImportStage.IDLE)
    val message = mutableStateOf("")
    val busy = mutableStateOf(false)

    val qrBitmap = mutableStateOf<Bitmap?>(null)
    val qrHint = mutableStateOf("")

    val mobile = mutableStateOf("")
    val smsCode = mutableStateOf("")
    val countdown = mutableStateOf(0)

    /** 查到的角色与勾选状态 */
    val roles = mutableStateListOf<RoleInfo>()
    val selected = mutableStateListOf<Int>()

    private var credential: BinBuilder.Credential? = null
    private var pollJob: Job? = null
    private var countdownJob: Job? = null

    override fun onCleared() {
        pollJob?.cancel()
        countdownJob?.cancel()
    }

    fun reset() {
        pollJob?.cancel()
        stage.value = ImportStage.IDLE
        message.value = ""
        qrBitmap.value = null
        qrHint.value = ""
        roles.clear()
        selected.clear()
        credential = null
        busy.value = false
    }

    // ---------- 扫码 ----------

    fun startQrLogin() {
        pollJob?.cancel()
        busy.value = true
        message.value = ""
        viewModelScope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) { WxLogin.createQrCode() }
                qrBitmap.value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(ticket.imageBytes, 0, ticket.imageBytes.size)
                }
                if (qrBitmap.value == null) throw ImportException("二维码图片无法解码")
                stage.value = ImportStage.QR_WAITING
                qrHint.value = "请用微信扫码"
                busy.value = false
                pollLoop(ticket.uuid)
            } catch (e: Exception) {
                busy.value = false
                message.value = e.message ?: "获取二维码失败"
            }
        }
    }

    private fun pollLoop(uuid: String) {
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val result = try {
                    withContext(Dispatchers.IO) { WxLogin.pollQrCode(uuid) }
                } catch (e: Exception) {
                    qrHint.value = "轮询出错: ${e.message}"
                    continue
                }
                when (result.state) {
                    WxScanState.CONFIRMED -> {
                        qrHint.value = "授权成功，正在登录"
                        loginWith { WxLogin.loginByWxCode(result.code!!) }
                        return@launch
                    }
                    WxScanState.EXPIRED, WxScanState.REJECTED -> {
                        qrHint.value = result.state.label + "，请重新获取"
                        stage.value = ImportStage.IDLE
                        qrBitmap.value = null
                        return@launch
                    }
                    else -> qrHint.value = result.state.label
                }
            }
            qrHint.value = "二维码已超时，请重新获取"
            stage.value = ImportStage.IDLE
            qrBitmap.value = null
        }
    }

    // ---------- 短信 ----------

    fun sendSms() {
        val m = mobile.value.trim()
        if (!Regex("^1[3-9]\\d{9}$").matches(m)) {
            message.value = "请输入有效的手机号"
            return
        }
        busy.value = true
        message.value = ""
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { WxLogin.sendSmsCode(m) }
                message.value = "验证码已发送，请注意接收"
                startCountdown()
            } catch (e: Exception) {
                message.value = e.message ?: "发送失败"
            } finally {
                busy.value = false
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            countdown.value = 120
            while (countdown.value > 0) {
                delay(1000)
                countdown.value -= 1
            }
        }
    }

    fun loginBySms() {
        val m = mobile.value.trim()
        val c = smsCode.value.trim()
        if (c.length < 4) {
            message.value = "请输入有效的验证码"
            return
        }
        loginWith { WxLogin.loginBySms(m, c) }
    }

    /** 登录 → 查区服，两步合一 */
    private fun loginWith(block: suspend () -> LoginResult) {
        busy.value = true
        stage.value = ImportStage.LOGGING_IN
        message.value = ""
        viewModelScope.launch {
            try {
                val login = withContext(Dispatchers.IO) { block() }
                stage.value = ImportStage.QUERYING
                message.value = "登录成功，正在查询区服"
                // timestamp / sign 必须用服务端签发的原值
                val cred = BinBuilder.credentialFromCombUser(
                    login.combUser,
                    login.timestamp,
                    login.sign,
                )
                credential = cred
                val list = withContext(Dispatchers.IO) { BinBuilder.queryRoles(cred) }
                roles.clear()
                roles.addAll(list)
                selected.clear()
                selected.addAll(list.indices)
                stage.value = ImportStage.PICKING
                message.value = "共找到 ${list.size} 个角色，请选择要导入的"
            } catch (e: Exception) {
                stage.value = ImportStage.IDLE
                message.value = e.message ?: "登录失败"
            } finally {
                busy.value = false
            }
        }
    }

    /** 用已有 bin 文件查区服，不需要重新登录 */
    fun queryFromBin(binBytes: ByteArray) {
        busy.value = true
        message.value = ""
        stage.value = ImportStage.QUERYING
        viewModelScope.launch {
            try {
                val cred = BinBuilder.parseCredential(binBytes)
                credential = cred
                val list = withContext(Dispatchers.IO) { BinBuilder.queryRoles(cred) }
                roles.clear()
                roles.addAll(list)
                selected.clear()
                selected.addAll(list.indices)
                stage.value = ImportStage.PICKING
                message.value = "共找到 ${list.size} 个角色"
            } catch (e: Exception) {
                stage.value = ImportStage.IDLE
                // 两种常见失败原因表现相似，分开提示省得用户以为是软件坏了
                val raw = e.message ?: "解析失败"
                message.value = when {
                    raw.contains("解码") || raw.contains("缺少 info") ->
                        "$raw\n该文件可能不是有效的 bin，或已损坏"
                    raw.contains("凭据") || raw.contains("角色列表") ->
                        "$raw\n登录态可能已过期，请改用扫码或手机号导入"
                    else -> raw
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun toggle(index: Int) {
        if (selected.contains(index)) selected.remove(index) else selected.add(index)
    }

    fun selectAll(on: Boolean) {
        selected.clear()
        if (on) selected.addAll(roles.indices)
    }

    /** 生成选中角色的 bin */
    fun buildSelected(): List<GeneratedBin> {
        val cred = credential ?: return emptyList()
        val picked = selected.sorted().mapNotNull { roles.getOrNull(it) }
        return BinBuilder.buildBins(cred, picked)
    }
}
