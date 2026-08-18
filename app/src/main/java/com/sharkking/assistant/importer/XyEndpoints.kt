package com.sharkking.assistant.importer

/**
 * 咸鱼之王与微信开放平台的端点和伪装头。
 *
 * 这些值必须与官方客户端一致，服务端会校验 Referer / UA / 签名指纹。
 * 版本号随游戏更新会失效，所以集中放在这里便于调整。
 */
object XyEndpoints {

    // 咸鱼之王在微信开放平台的身份
    const val WX_APPID = "wxfb0d5667e5cb1c44"
    const val BUNDLE_ID = "com.hortor.games.xyzw"
    const val WX_SCOPE = "snsapi_base,snsapi_userinfo,snsapi_friend,snsapi_message"

    // 正式安装包的签名指纹，服务端据此校验包体
    const val SIGN_PRINT = "E6:F7:FE:A9:EC:8E:24:D0:4F:2A:32:50:28:78:E1:C5:5E:70:81:13"

    const val HORTOR_SDK_VERSION = "4.2.1-cn-release"
    const val APP_VERSION = "1.84.5-wx"

    /** 抓包实测的 App 端版本号，与小游戏端的 APP_VERSION 不同 */
    const val ANDROID_VERSION = "1.4.0"
    const val CRYPT_VERSION = "1.1.0"

    /**
     * 走 iOS 客户端身份。抓包解密出的明文里 version 就是这个值，
     * 与 crypt/mix 取规则时用的必须一致，否则服务端下发的码本对不上。
     */
    const val IOS_VERSION = "0.33.0"
    const val IOS_SDK_VERSION = "1.9.3"

    fun qrConnect() =
        "https://open.weixin.qq.com/connect/app/qrconnect" +
            "?appid=$WX_APPID&bundleid=$BUNDLE_ID&scope=$WX_SCOPE&state=weixin"

    fun qrImage(uuid: String) = "https://open.weixin.qq.com/connect/qrcode/$uuid"

    fun qrPoll(uuid: String) =
        "https://long.open.weixin.qq.com/connect/l/qrconnect" +
            "?uuid=$uuid&f=url&_=${System.currentTimeMillis()}"

    fun serverList() = "https://xxz-xyzw.hortorgames.com/login/serverlist?_seq=3"

    fun smsCode() =
        "https://ucenter-app-server.hortorgames.com/ucenter-app-server/api/v1/login/verify/code"

    /** 取加密规则（码本）。免鉴权 GET，参数照抓包原样 */
    fun cryptMix(deviceId: String) =
        "https://comb-platform.hortorgames.com/comb-login-server/api/v1/login/crypt/mix" +
            "?combGameId=xyzw_mix&deviceUniqueId=$deviceId&gameTp=app" +
            "&packageName=$BUNDLE_ID&system=ios&version=$IOS_VERSION"

    /**
     * App 登录。query 照抓包原样：system=ios、version=0.33.0、
     * deviceUniqueId 是无前缀的大写 UUID。请求体是加密后的 base64 文本。
     */
    fun combLoginApp(deviceId: String, version: String = IOS_VERSION) =
        "https://comb-platform.hortorgames.com/comb-login-server/api/v1/login" +
            "?gameId=xyzwapp&gameTp=app&system=ios&cryptVersion=$CRYPT_VERSION" +
            "&version=$version&deviceUniqueId=$deviceId" +
            "&timestamp=${System.currentTimeMillis()}"

    /** 伪装成 Mac 微信小游戏客户端，用于 hortor 游戏主服 */
    const val UA_MINIGAME =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36 " +
            "MicroMessenger/6.8.0(0x16080000) NetType/WIFI MiniProgramEnv/Mac " +
            "MacWechat/WMPF MacWechat/3.8.7(0x13080710) XWEB/1191"

    /** 咸鱼之王小程序 appid 的数字形式 + 小游戏版本号 */
    const val REFERER_MINIGAME =
        "https://appservice.qq.com/1112173744/1.66.5/page-frame.html"

    /** 伪装成安卓微信内置浏览器，用于微信开放平台 */
    const val UA_WX_ANDROID =
        "Mozilla/5.0 (Linux; Android 7.0; Mi-4c Build/NRD90M; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/53.0.2785.49 Mobile MQQBrowser/6.2 " +
            "TBS/043632 Safari/537.36 MicroMessenger/6.6.1.1220(0x26060135) NetType/WIFI " +
            "Language/zh_CN miniProgram"

    /** 抓包里登录与 crypt/mix 用的 iPhone UA */
    const val UA_IOS =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** 伪装成安卓 App */
    const val UA_APP =
        "Mozilla/5.0 (Linux; Android 10; 23116PN5BC Build/HUAWEIJNY-AL10; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/74.0.3729.186 " +
            "Mobile Safari/537.36"
}

/** 微信扫码状态码 */
enum class WxScanState(val code: Int, val label: String) {
    CONFIRMED(405, "已确认授权"),
    SCANNED(404, "已扫码，等待确认"),
    WAITING(408, "等待扫码"),
    EXPIRED(402, "二维码已过期"),
    REJECTED(403, "已取消授权"),
    UNKNOWN(-1, "未知状态");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
