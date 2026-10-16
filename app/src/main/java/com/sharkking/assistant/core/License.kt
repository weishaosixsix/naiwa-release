package com.sharkking.assistant.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 激活密钥(秘钥)校验。
 *
 * 设计:
 *  - 密钥一次性使用:服务端核销并绑定设备ID,同一密钥换设备会被拒绝;
 *    同一设备重装后重输同一密钥放行(否则重装即废)。
 *  - 服务端不可达时:已激活的用户不受影响(本地状态为凭);每次启动
 *    静默复查一次,只有服务端明确说"无效/已被他人使用"才退回激活页。
 *  - 这是 UI 层的准入控制,配合服务端的一次性核销:密钥无法被两人共用;
 *    对 APK 逆向改包不做对抗(代价高于收益)。
 */
object License {

    private const val TAG = "奶蛙-激活"
    private const val PREFS = "license"
    private const val BASE = "http://122.51.231.89:666/ydc"

    data class State(
        val activated: Boolean,
        val key: String,
        val token: String,
        val deviceId: String,
    )

    /** 设备标识:ANDROID_ID + 机型 + 包名 的哈希,稳定且不涉及隐私字段 */
    fun deviceId(ctx: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: ""
        val raw = "$androidId|${Build.MODEL}|${ctx.packageName}"
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "android-$hash"
    }

    fun state(ctx: Context): State {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            activated = sp.getBoolean("activated", false),
            key = sp.getString("key", "") ?: "",
            token = sp.getString("token", "") ?: "",
            deviceId = sp.getString("deviceId", "") ?: "",
        )
    }

    private fun store(ctx: Context, key: String, token: String, deviceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("activated", true)
            .putString("key", key)
            .putString("token", token)
            .putString("deviceId", deviceId)
            .apply()
    }

    private fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("activated", false).apply()
    }

    /** 激活结果:成功回 token;失败回可展示的中文原因 */
    sealed class ActivateResult {
        data class Ok(val token: String) : ActivateResult()
        data class Fail(val message: String) : ActivateResult()
    }

    /**
     * 激活(阻塞,调用方放后台线程)。
     * 服务端约定:POST /api/activate {key, deviceId, deviceName}
     *  200 {ok:true, token}   404 密钥无效   409 已在其他设备使用   403 已作废
     */
    fun activate(ctx: Context, keyInput: String): ActivateResult {
        val key = keyInput.trim().uppercase()
        if (key.length < 8) return ActivateResult.Fail("请输入完整的密钥")
        val dev = deviceId(ctx)
        return try {
            val resp = post("$BASE/api/activate", JSONObject().apply {
                put("key", key)
                put("deviceId", dev)
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
            })
            when (resp.first) {
                200 -> {
                    val token = resp.second.optString("token")
                    if (token.isNotEmpty()) {
                        store(ctx, key, token, dev)
                        Log.i(TAG, "激活成功")
                        ActivateResult.Ok(token)
                    } else {
                        ActivateResult.Fail("服务端响应异常")
                    }
                }
                404 -> ActivateResult.Fail("密钥无效,请核对后重试")
                409 -> ActivateResult.Fail("该密钥已在其他设备使用过")
                403 -> ActivateResult.Fail("该密钥已被作废")
                else -> ActivateResult.Fail("激活失败(错误 ${resp.first})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "激活请求失败: ${e.message}")
            ActivateResult.Fail("网络不可用,请检查网络后重试")
        }
    }

    /** 复查回调:true=仍然有效(或网络不通,放行);false=明确失效(应退回激活页) */
    fun verifyAsync(ctx: Context, onResult: (Boolean) -> Unit) {
        val st = state(ctx)
        if (!st.activated || st.key.isEmpty() || st.token.isEmpty()) {
            onResult(true)   // 未激活的交给激活页,这里不管
            return
        }
        Thread {
            var valid = true
            try {
                val resp = post("$BASE/api/verify", JSONObject().apply {
                    put("key", st.key)
                    put("deviceId", deviceId(ctx))
                    put("token", st.token)
                })
                // 200=有效;403=明确失效;其它(5xx/超时)一律放行
                if (resp.first == 403) valid = false
            } catch (e: Exception) {
                Log.i(TAG, "复查跳过(网络不通): ${e.message}")
            }
            if (!valid) clear(ctx)
            onResult(valid)
        }.start()
    }

    /** 简单 POST JSON,返回 (httpStatus, json);异常向上抛由调用方归为网络问题 */
    private fun post(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            return code to json
        } finally {
            conn.disconnect()
        }
    }
}
