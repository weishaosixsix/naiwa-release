package com.sharkking.assistant.importer

import android.util.Log
import org.json.JSONObject
import java.util.UUID

private const val TAG = "奶蛙-登录"

/** 二维码申请结果 */
data class QrTicket(val uuid: String, val imageBytes: ByteArray)

/** 轮询结果：状态 + 授权成功时的 code */
data class QrPollResult(val state: WxScanState, val code: String? = null)

/**
 * 微信扫码授权与手机号验证码登录。
 *
 * 全部走原生 HTTP：需要伪造 Host / Referer / UA / X-Requested-With，
 * 这些在浏览器里属于禁止修改头。
 */
object WxLogin {

    /**
     * 短信验证码类型。服务端只回「参数错误」不说明合法值，
     * 需要抓一次官方 App 的真实请求才能确定。
     */
    var SMS_CODE_TYPE = "login"


    /** 申请二维码，返回 uuid 与 PNG 字节 */
    fun createQrCode(): QrTicket {
        DeviceInfo.newSession()
        val html = Http.get(
            XyEndpoints.qrConnect(),
            mapOf(
                "Host" to "open.weixin.qq.com",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to XyEndpoints.UA_WX_ANDROID,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                // 微信内置浏览器会带宿主包名
                "X-Requested-With" to "com.muhua.w0",
            ),
        ).text()

        // uuid 只出现在二维码图片地址里：
        // <img class="auth_qrcode" src="https://open.weixin.qq.com/connect/qrcode/XXXX">
        val uuid = Regex("qrcode/([A-Za-z0-9_\\-]+)").find(html)?.groupValues?.get(1)
            ?: Regex("uuid=([A-Za-z0-9_\\-]+)").find(html)?.groupValues?.get(1)
            ?: Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: throw ImportException(
                "未能解析二维码 uuid。响应片段: " +
                    html.take(150).replace(Regex("\\s+"), " ")
            )

        val img = Http.get(
            XyEndpoints.qrImage(uuid),
            mapOf(
                "Host" to "open.weixin.qq.com",
                "User-Agent" to XyEndpoints.UA_WX_ANDROID,
            ),
        )
        if (img.status != 200 || img.body.isEmpty()) {
            throw ImportException("二维码图片下载失败 (HTTP ${img.status})")
        }
        Log.i(TAG, "二维码已获取 uuid=$uuid 图片=${img.body.size}字节")
        return QrTicket(uuid, img.body)
    }

    /** 轮询扫码状态。返回 CONFIRMED 时 code 非空 */
    fun pollQrCode(uuid: String): QrPollResult {
        val body = Http.get(
            XyEndpoints.qrPoll(uuid),
            mapOf(
                "Host" to "long.open.weixin.qq.com",
                "User-Agent" to XyEndpoints.UA_WX_ANDROID,
            ),
        ).text()

        val errCode = Regex("wx_errcode\\s*=\\s*(\\d+)").find(body)
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val state = WxScanState.of(errCode)
        if (state != WxScanState.CONFIRMED) return QrPollResult(state)

        // 响应形如 window.wx_redirecturl='https://...?code=xxx&state=weixin';
        // 值一定被引号包裹，所以要匹配引号内的整段，不能把引号放进排除集
        val redirect = Regex("wx_redirecturl\\s*=\\s*['\"]([^'\"]*)['\"]")
            .find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: throw ImportException(
                "授权成功但跳转地址为空。响应: ${body.take(200)}"
            )
        val code = Regex("[?&]code=([^&'\"]+)").find(redirect)?.groupValues?.get(1)
            ?: throw ImportException("跳转地址中没有 code: $redirect")
        Log.i(TAG, "扫码授权成功")
        return QrPollResult(state, code)
    }

    /**
     * 发送短信验证码。
     *
     * 关键点：accountNum 放的是手机号本身（不是数量），而且必须带上
     * 整套设备与包体信息，缺字段服务端一律回「参数错误」。
     */
    fun sendSmsCode(mobile: String) {
        require(Regex("^1[3-9]\\d{9}$").matches(mobile)) { "手机号格式不正确" }
        // 开一次新会话：后续登录复用同一设备身份，避免被判成换设备
        val device = DeviceInfo.newSession()
        val payload = JSONObject().apply {
            put("gameId", "xyzwapp")
            put("gameTp", "app")
            put("accountNum", mobile)
            put("sysInfo", device.sysInfo())
            put("activeLoginMatchId", device.activeLoginMatchId())
            put("channel", "android")
            put("verifyCodeTp", "login")
            put("distinctId", device.distinctId)
            put("oaidThirdSdk", "")
            put("ipv6", "")
            put("limit", true)
            put("packageName", XyEndpoints.BUNDLE_ID)
            put("signPrint", XyEndpoints.SIGN_PRINT)
            put("androidId", device.androidId)
            put("oaId", "")
            put("oaid", "")
        }.toString()

        val res = Http.post(
            XyEndpoints.smsCode(),
            mapOf(
                "Host" to "ucenter-app-server.hortorgames.com",
                "Content-Type" to "application/json; charset=utf-8",
                "User-Agent" to XyEndpoints.UA_APP,
                "Accept-Encoding" to "gzip",
            ),
            payload.toByteArray(Charsets.UTF_8),
        )
        val text = res.text()
        Log.i(TAG, "发送验证码 HTTP ${res.status}: ${text.take(300)}")
        if (res.status != 200) throw ImportException("发送失败 (HTTP ${res.status})")

        // 错误信息包在 meta 里，不在顶层
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ImportException("响应不是 JSON: ${text.take(120)}")
        val meta = o.optJSONObject("meta")
        val err = meta?.optInt("errCode", 0) ?: o.optInt("errCode", 0)
        if (err != 0) {
            val msg = meta?.optString("errMsg") ?: o.optString("errMsg")
            throw ImportException("发送失败: $msg (errCode=$err)")
        }
    }

    /**
     * 拉取加密规则（码本）。免鉴权 GET。
     *
     * 参数里的 version 必须与登录时一致，否则服务端按另一套规则解密会失败。
     */
    fun fetchCryptRule(device: DeviceInfo): CodeBookCrypto.Rule {
        val res = Http.get(
            XyEndpoints.cryptMix(device.deviceUniqueId),
            mapOf(
                "Host" to "comb-platform.hortorgames.com",
                "User-Agent" to XyEndpoints.UA_IOS,
                "Accept" to "*/*",
                "Accept-Language" to "zh-Hans-CN;q=1",
                "Accept-Encoding" to "gzip",
            ),
        )
        val text = res.text()
        if (res.status != 200) throw ImportException("取加密规则失败 (HTTP ${res.status})")
        val rule = runCatching { JSONObject(text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("cryptRule")
            ?: throw ImportException("加密规则响应异常: ${text.take(150)}")
        val codeBook = rule.optString("codeBook")
        if (codeBook.isEmpty()) throw ImportException("加密规则里没有 codeBook")
        Log.i(TAG, "取得码本 ${codeBook.length} 字符 swap=${rule.optInt("swapTimes")}")
        return CodeBookCrypto.Rule(
            codeBook = codeBook,
            swapTimes = rule.optInt("swapTimes"),
            keySkip = rule.optInt("keySkip"),
            keyOffset = rule.optInt("keyOffset"),
        )
    }

    /**
     * 用短信验证码换取登录态。
     *
     * 字段与顺序照抓包解密出的真实明文，走 iOS 客户端身份。
     */
    fun loginBySms(mobile: String, smsCode: String): LoginResult {
        val device = DeviceInfo.session()
        val payload = JSONObject().apply {
            put("smsCode", smsCode)
            put("mac", "02:00:00:00:00:00")
            put("tp", "app-mobile")
            put("mobile", mobile)
            put("gameId", "xyzwapp")
            put("channel", "AppStore")
            put("idfa", "00000000-0000-0000-0000-000000000000")
            put("version", XyEndpoints.IOS_VERSION)
            put("distinctId", device.distinctId)
            put("activeLoginMatchId", device.distinctId)
            put("gameTp", "app")
            put("packageName", XyEndpoints.BUNDLE_ID)
            put("sysInfo", device.iosSysInfo())
            put("caidInfo", device.caidInfo())
            put("idfv", device.idfv)
            put("deviceUniqueId", device.deviceUniqueId)
        }.toString()

        return combLogin(device, payload)
    }

    /** 用微信授权 code 换取登录态，与短信共用同一登录端点 */
    fun loginByWxCode(code: String): LoginResult {
        val device = DeviceInfo.session()
        val payload = JSONObject().apply {
            put("code", code)
            put("state", "weixin")
            put("mac", "02:00:00:00:00:00")
            put("tp", "app-we")
            put("gameId", "xyzwapp")
            put("channel", "AppStore")
            put("idfa", "00000000-0000-0000-0000-000000000000")
            put("version", XyEndpoints.IOS_VERSION)
            put("distinctId", device.distinctId)
            put("activeLoginMatchId", device.distinctId)
            put("gameTp", "app")
            put("packageName", XyEndpoints.BUNDLE_ID)
            put("sysInfo", device.iosSysInfo())
            put("caidInfo", device.caidInfo())
            put("idfv", device.idfv)
            put("deviceUniqueId", device.deviceUniqueId)
        }.toString()

        return combLogin(device, payload)
    }

    /**
     * 提交登录请求。
     *
     * 必须把 timestamp 和 sign 一并带回：bin 里的 info 三件套是服务端
     * 签发的整体，自己伪造 sign 会被拒。实测这套凭据不会短期失效。
     */
    private fun combLogin(device: DeviceInfo, payload: String): LoginResult {
        // 请求体不是明文 JSON：先 base64、再用服务端下发的码本 XOR、再 base64。
        // 直接发明文会被判「解密错误 errCode=10024」。
        val rule = fetchCryptRule(device)
        val body = CodeBookCrypto.encrypt(payload, rule)
        Log.i(TAG, "登录请求体 明文${payload.length}字符 → 密文${body.length}字符")

        val res = Http.post(
            XyEndpoints.combLoginApp(device.deviceUniqueId),
            mapOf(
                "Host" to "comb-platform.hortorgames.com",
                "Content-Type" to "application/json; charset=utf-8",
                "User-Agent" to XyEndpoints.UA_IOS,
                "Accept" to "*/*",
                "Accept-Language" to "zh-Hans-CN;q=1",
                "Accept-Encoding" to "gzip",
            ),
            body.toByteArray(Charsets.US_ASCII),
        )
        val text = res.text()
        Log.i(TAG, "登录 HTTP ${res.status}: ${text.take(300)}")
        if (res.status != 200) throw ImportException("登录失败 (HTTP ${res.status})")

        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ImportException("登录响应不是 JSON: ${text.take(120)}")

        // hortor 的接口把状态放在 meta 里
        val meta = o.optJSONObject("meta")
        val err = meta?.optInt("errCode", 0) ?: o.optInt("errCode", 0)
        if (err != 0) {
            val msg = meta?.optString("errMsg") ?: o.optString("errMsg")
            throw ImportException("登录失败: $msg (errCode=$err)")
        }

        // combUser 可能是字符串，也可能是含三件套的对象，且可能嵌在 data 里。
        // 三件套必须同源：sign 是对 encryptCombUser+timestamp 的签名，混搭会被拒。
        val holder = findCredentialHolder(o)
            ?: throw ImportException("登录响应缺少 combUser，原文: ${text.take(300)}")

        val combUser = holder.optString("encryptCombUser")
        val ts = holder.optInt("timestamp", 0)
        val sign = holder.optString("sign")
        if (combUser.isEmpty() || sign.isEmpty() || ts == 0) {
            throw ImportException(
                "凭据不完整 combUser=${combUser.length}字符 ts=$ts sign=${sign.length}字符" +
                    "，原文: ${text.take(300)}"
            )
        }
        Log.i(TAG, "取得凭据 combUser=${combUser.length}字符 ts=$ts")
        return LoginResult(combUser, ts, sign, text)
    }

    /**
     * 深度查找带 encryptCombUser 的对象。
     *
     * 服务端把它放在 data.combUser 下，但层级随版本变过，直接递归找最稳。
     */
    private fun findCredentialHolder(root: JSONObject, depth: Int = 0): JSONObject? {
        if (depth > 4) return null
        if (root.has("encryptCombUser")) return root
        // combUser 也可能直接是那段 base64 字符串，此时三件套散在同层
        val direct = root.optString("combUser")
        if (direct.length > 64 && root.has("sign")) {
            return JSONObject().apply {
                put("encryptCombUser", direct)
                put("timestamp", root.optInt("timestamp", 0))
                put("sign", root.optString("sign"))
            }
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val child = root.optJSONObject(keys.next()) ?: continue
            findCredentialHolder(child, depth + 1)?.let { return it }
        }
        return null
    }
}

/** 登录得到的账号级凭据，三件套要整体保存 */
data class LoginResult(
    val combUser: String,
    val timestamp: Int,
    val sign: String,
    val rawJson: String,
)

/**
 * 伪造的设备信息。
 *
 * 发验证码那步走安卓模板（已实测能收到短信），登录那步走 iOS 模板
 * —— 抓包解密出的真实登录明文就是 iOS 那套，字段必须逐个对上。
 */
class DeviceInfo private constructor(
    val distinctId: String,
    val androidId: String,
    val deviceUniqueId: String,
    val oaid: String,
    val idfv: String,
    private val system: String,
    private val model: String,
    private val brand: String,
    private val iosModel: String,
    private val iosVer: String,
    private val hwModel: String,
) {
    /** 安卓模板，发验证码用 */
    fun sysInfo(): String = JSONObject().apply {
        put("system", system)
        put("hortorSDKVersion", XyEndpoints.HORTOR_SDK_VERSION)
        put("model", model)
        put("brand", brand)
    }.toString()

    /** iOS 模板，登录用。字段顺序照抓包明文 */
    fun iosSysInfo(): String = JSONObject().apply {
        put("system", "iOS $iosVer")
        put("model", iosModel)
        put("brand", "Apple")
        put("hortorSDKVersion", XyEndpoints.IOS_SDK_VERSION)
    }.toString()

    /** iOS 端的设备指纹集合，服务端用来做同设备判定 */
    fun caidInfo(): JSONObject = JSONObject().apply {
        put("carrierInfo", "unknown")
        put("machine", iosModel)
        put("mntId", "${hex(64).uppercase()}@/dev/disk1s1")
        put("sysFileTime", "%.6f".format(System.currentTimeMillis() / 1000.0))
        put("countryCode", "CN")
        put("deviceInitTime", "1679115838.749221083")
        put("deviceName", hex(32))
        put("systemVersion", iosVer)
        put("language", "zh-Hans-CN")
        put("memory", "5909987328")
        put("disk", "255866785792")
        put("bootTimeInSec", (System.currentTimeMillis() / 1000 - 86400).toString())
        put("timeZone", "28800")
        put("model", hwModel)
    }

    /** 形如 <13位毫秒时间戳>_<uuid>，uuid 与 distinctId 是同一个 */
    fun activeLoginMatchId(): String = "${System.currentTimeMillis()}_$distinctId"

    companion object {
        private val TEMPLATES = listOf(
            Triple("Android 12", "ALN-AL1", "HUAWEI"),
            Triple("Android 10", "23116PN5BC", "Xiaomi"),
        )

        /** iOS 机型三元组：机器标识 / 系统版本 / 硬件代号 */
        private val IOS_TEMPLATES = listOf(
            Triple("iPhone15,3", "26.4.2", "D74AP"),
            Triple("iPhone14,7", "18.6.1", "D27AP"),
        )

        private fun hex(n: Int): String {
            val cs = "0123456789abcdef"
            return (1..n).map { cs.random() }.joinToString("")
        }

        /** 同一次导入流程内复用，保证发码与登录是「同一台设备」 */
        @Volatile
        private var cached: DeviceInfo? = null

        fun session(): DeviceInfo = cached ?: random().also { cached = it }

        fun newSession(): DeviceInfo = random().also { cached = it }

        fun random(): DeviceInfo {
            val (system, model, brand) = TEMPLATES.random()
            val (iosModel, iosVer, hwModel) = IOS_TEMPLATES.random()
            // iOS 端 distinctId / deviceUniqueId / activeLoginMatchId 是同一个大写 UUID
            val uid = UUID.randomUUID().toString().uppercase()
            return DeviceInfo(
                distinctId = uid,
                androidId = hex(16),
                deviceUniqueId = uid,
                oaid = hex(32),
                idfv = UUID.randomUUID().toString().uppercase(),
                system = system,
                model = model,
                brand = brand,
                iosModel = iosModel,
                iosVer = iosVer,
                hwModel = hwModel,
            )
        }
    }
}

class ImportException(msg: String) : RuntimeException(msg)
