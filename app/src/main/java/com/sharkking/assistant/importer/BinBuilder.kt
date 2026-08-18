package com.sharkking.assistant.importer

import android.util.Log
import com.sharkking.assistant.bon.BonDecoder
import com.sharkking.assistant.bon.BonEncoder
import com.sharkking.assistant.bon.XyCrypto

private const val TAG = "奶蛙-导入"

/** 一个区服里的角色 */
data class RoleInfo(
    val serverId: Int,
    val roleId: Long,
    val name: String,
    val power: Long,
    val level: Int,
    val loginAt: String,
) {
    /** 战力显示：亿 / 万 */
    fun powerText(): String = when {
        power >= 100_000_000L -> String.format("%.2f亿", power / 1e8)
        power >= 10_000L -> String.format("%.2f万", power / 1e4)
        else -> power.toString()
    }
}

/** 生成好的一份绑定区服 bin */
data class GeneratedBin(val fileName: String, val bytes: ByteArray, val role: RoleInfo)

/**
 * 账号级凭据 → 查询区服 → 生成每区服一份 bin。
 *
 * 这条链路已用真实 bin 实测通过：请求体就是 bin 的明文结构重新
 * 加密，响应用同一套 LX/X 解密 + BON 解码，body 需要二次解码。
 */
object BinBuilder {

    /** 账号级凭据：从 bin 解出，或由登录接口的 combUser 构造 */
    data class Credential(
        val platform: String,
        val platformExt: String,
        val info: Map<String, Any?>,
        val scene: Any?,
        val referrerInfo: Any?,
    ) {
        /** 还原成可发送 / 可存盘的对象；serverId 为 null 即账号级 */
        fun toMap(serverId: Int?): LinkedHashMap<String, Any?> = linkedMapOf(
            "platform" to platform,
            "platformExt" to platformExt,
            "info" to info,
            "serverId" to serverId,
            "scene" to scene,
            "referrerInfo" to referrerInfo,
        )
    }

    /**
     * info 字段有两种写法，都要认。
     *
     * 我们自己生成的是嵌套对象；而外面流传的 bin（占多数）把它写成
     * JSON 字符串。内层键顺序也不固定，只能按名字取。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseInfo(raw: Any?): Map<String, Any?> {
        if (raw is Map<*, *>) return raw as Map<String, Any?>
        if (raw is String && raw.isNotBlank()) {
            val obj = runCatching { org.json.JSONObject(raw) }.getOrNull()
                ?: throw ImportException("info 是字符串但不是合法 JSON，文件可能已损坏")
            val out = LinkedHashMap<String, Any?>()
            for (k in obj.keys()) out[k] = obj.get(k)
            return out
        }
        throw ImportException("bin 缺少 info 字段")
    }

    /** 从已有 bin 文件解析出账号级凭据 */
    @Suppress("UNCHECKED_CAST")
    fun parseCredential(binBytes: ByteArray): Credential {
        val plain = XyCrypto.decrypt(binBytes)
        val obj = BonDecoder().decode(plain) as? Map<String, Any?>
            ?: throw ImportException("bin 解码结果不是对象")
        val info = parseInfo(obj["info"])
        if (info["encryptCombUser"] == null) {
            throw ImportException("bin 里没有登录凭据（encryptCombUser），无法反查区服")
        }
        return Credential(
            platform = obj["platform"] as? String ?: "hortor",
            platformExt = obj["platformExt"] as? String ?: "mix",
            info = info,
            scene = obj["scene"] ?: 0,
            referrerInfo = obj["referrerInfo"] ?: "",
        )
    }

    /** 用登录得到的 combUser 构造凭据 */
    fun credentialFromCombUser(
        combUser: String,
        timestamp: Int,
        sign: String,
    ): Credential = Credential(
        platform = "hortor",
        platformExt = "mix",
        info = linkedMapOf(
            "encryptCombUser" to combUser,
            "timestamp" to timestamp,
            "sign" to sign,
        ),
        scene = 0,
        referrerInfo = "",
    )

    /** 查询该凭据在哪些区服有角色 */
    @Suppress("UNCHECKED_CAST")
    fun queryRoles(cred: Credential): List<RoleInfo> {
        val payload = XyCrypto.encryptX(BonEncoder().encode(cred.toMap(null)))
        val res = Http.post(
            XyEndpoints.serverList(),
            mapOf(
                "Host" to "xxz-xyzw.hortorgames.com",
                "Referer" to XyEndpoints.REFERER_MINIGAME,
                "Content-Type" to "application/octet-stream",
                // 不能加 O4e-Encoding: lx —— 那是声明「请求体」的编码，
                // 我们发的是 px(X 加密)，声明不符服务端直接回「指令解析错误」。
                // 实测：带此头响应 105 字节报错，不带则正常返回角色列表。
                "User-Agent" to XyEndpoints.UA_MINIGAME,
            ),
            payload,
        )
        if (res.status != 200) throw ImportException("查询区服失败 (HTTP ${res.status})")
        if (res.body.isEmpty()) throw ImportException("查询区服返回空响应")
        // 正常响应有几 MB（含全区服列表），几百字节基本就是报错回包
        Log.i(TAG, "serverlist 响应 ${res.body.size} 字节")

        val outer = BonDecoder().decode(XyCrypto.decrypt(res.body)) as? Map<String, Any?>
            ?: throw ImportException("响应解码失败")
        val cmd = outer["cmd"] as? String
        Log.i(TAG, "serverlist 响应 cmd=$cmd 字段=${outer.keys.joinToString(",")}")
        if (cmd != null && cmd.contains("Error", true)) {
            throw ImportException("服务端返回错误: $cmd ${describe(outer)}")
        }

        // body 是嵌套的 BON 字节序列，要二次解码
        val bodyBytes = outer["body"] as? ByteArray
            ?: throw ImportException(
                "响应缺少 body。cmd=$cmd 字段=${outer.keys.joinToString(",")} ${describe(outer)}"
            )
        val body = BonDecoder().decode(bodyBytes) as? Map<String, Any?>
            ?: throw ImportException("body 解码失败")

        val roles = body["roles"] as? Map<String, Any?>
            ?: throw ImportException("未找到角色列表，凭据可能已失效")

        val out = ArrayList<RoleInfo>(roles.size)
        for ((key, v) in roles) {
            val r = v as? Map<String, Any?> ?: continue
            val sid = num(r["serverId"])?.toInt() ?: key.toIntOrNull() ?: continue
            out.add(
                RoleInfo(
                    serverId = sid,
                    roleId = num(r["roleId"])?.toLong() ?: 0L,
                    name = r["name"] as? String ?: "未命名",
                    power = num(r["power"])?.toLong() ?: 0L,
                    level = num(r["level"])?.toInt() ?: 0,
                    loginAt = r["loginAt"]?.toString() ?: "",
                )
            )
        }
        Log.i(TAG, "共解析出 ${out.size} 个角色")
        // 最近登录的排前面，方便用户找主号
        return out.sortedByDescending { it.loginAt }
    }

    /** 把响应对象压成一行摘要，便于在弹窗里看清服务端到底回了什么 */
    private fun describe(map: Map<String, Any?>): String =
        map.entries.take(8).joinToString(", ", "{", "}") { (k, v) ->
            val s = when (v) {
                null -> "null"
                is ByteArray -> "<${v.size}字节>"
                is Map<*, *> -> "{${v.keys.take(6).joinToString(",")}}"
                is List<*> -> "<列表${v.size}>"
                else -> v.toString().take(80)
            }
            "$k=$s"
        }

    private fun num(v: Any?): Number? = when (v) {
        is Number -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /**
     * 为选中的角色各生成一份绑定区服 bin。
     * 生成规则已验证：结构照抄账号级凭据，只替换 serverId。
     */
    fun buildBins(cred: Credential, roles: List<RoleInfo>): List<GeneratedBin> =
        roles.mapIndexed { i, role ->
            // 必须用 LX("pl")：游戏发 authuser 时声明 O4e-Encoding: lx，
            // 给 px 会被判「指令解析错误」，卡在「正在登录」
            val bytes = XyCrypto.encryptLX(BonEncoder().encode(cred.toMap(role.serverId)))
            val safe = role.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val name = "%02d-%s-%d服-%d-%s.bin".format(
                i + 1, role.powerText(), role.serverId, role.roleId, safe
            )
            GeneratedBin(name, bytes, role)
        }
}
