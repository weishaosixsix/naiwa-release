package com.sharkking.assistant.core

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 把启用的脚本落盘到本地 HTTP 服务的根目录下，页面用 <script src> 加载。
 *
 * 为什么不直接把代码塞进 evaluateJavascript：
 * 该调用走 Binder，事务缓冲区约 1MB。脚本 base64 后体积膨胀 4/3 倍，
 * 大脚本单个可达数 MB、全部启用叠加更多，远超上限。超了会静默失败
 * ——不抛异常、不进 onConsoleMessage，表现就是脚本"一直不显示"。
 * 改走 HTTP 后 Binder 只传几十字节的 URL，多大的脚本都不受影响。
 */
object ScriptCache {

    private const val TAG = "奶蛙-脚本缓存"
    private const val SUBDIR = "userscripts"

    /** 文件名按内容哈希，内容不变就不重写，也天然避开中文名转义问题 */
    private fun keyOf(id: String, code: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(id.toByteArray())
        md.update(code.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    data class Entry(val id: String, val name: String, val url: String)

    /**
     * 写入脚本并返回可访问的相对路径。
     *
     * @param root LocalHttpServer 的静态根目录
     */
    fun publish(
        root: File,
        scripts: List<Triple<String, String, String>>,
    ): List<Entry> {
        val dir = File(root, SUBDIR)
        if (!dir.isDirectory) dir.mkdirs()

        val alive = mutableSetOf<String>()
        val out = mutableListOf<Entry>()

        for ((id, name, code) in scripts) {
            val file = File(dir, keyOf(id, code) + ".js")
            alive += file.name
            if (!file.exists() || file.length() == 0L) {
                val ok = runCatching { file.writeBytes(code.toByteArray(Charsets.UTF_8)) }
                if (ok.isFailure) {
                    Log.w(TAG, "写入失败 $name: ${ok.exceptionOrNull()?.message}")
                    continue
                }
            }
            out += Entry(id, name, "$SUBDIR/${file.name}")
        }

        // 脚本被改动或删除后，旧的哈希文件不会再被引用，清掉省空间
        runCatching {
            dir.listFiles()?.forEach { if (it.name !in alive) it.delete() }
        }

        Log.i(TAG, "已发布 ${out.size} 个脚本到 ${dir.absolutePath}")
        return out
    }
}
