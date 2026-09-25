package com.sharkking.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 把 assets/renderer 展开到缓存目录，供 LocalHttpServer 作为静态根。
 * 对应 iOS 版「已复制渲染文件到缓存目录」那一步。
 *
 * 2026-09-23 改用内容指纹判断缓存是否有效。
 *
 * 原来是一个写死的标记文件名（`.copied_v95`），只要这个文件存在就直接复用缓存。
 * 问题是它跟包内资源的内容没有任何关系：往 renderer 里加了新文件、或者游戏更新
 * 换了带 hash 的文件名，标记还在，缓存就被判定为"有效"，新文件永远进不来 ——
 * 表现是游戏页面白屏（引擎文件 404），而脚本列表却是好的（脚本直接从 assets 读，
 * 不走这个缓存）。加游戏客户端文件时就是这么踩进去的。
 *
 * 现在标记存的是包内 renderer 清单的指纹（路径 + 大小），清单一变就重新展开，
 * 不需要任何人记得手动升版本号。清单不包含文件内容，内容变了但大小没变属于
 * 极端情况，真要覆盖可以改 STAMP_PREFIX 强制全量重建。
 */
object RendererCache {

    private const val TAG = "奶蛙"
    private const val ASSET_ROOT = "renderer"
    private const val STAMP = ".copied_stamp"
    private const val STAMP_PREFIX = "r1"

    fun ensure(ctx: Context): File {
        val dir = File(ctx.cacheDir, "renderer")
        val stampFile = File(dir, STAMP)
        val want = assetStamp(ctx)
        val have = runCatching { stampFile.readText() }.getOrNull()

        if (have == want && dir.isDirectory) {
            Log.i(TAG, "渲染缓存目录有效")
            return dir
        }

        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        val counter = intArrayOf(0, 0)   // [成功, 失败]
        copyDir(ctx, ASSET_ROOT, dir, counter)
        Log.i(TAG, "已复制渲染文件到缓存目录（成功 ${counter[0]}，失败 ${counter[1]}）")

        // 有文件没复制成功就不写指纹：下次启动重试，别把一个残缺的目录当成有效缓存
        if (counter[1] == 0) {
            runCatching { stampFile.writeText(want) }
                .onFailure { Log.w(TAG, "写入渲染缓存指纹失败: ${it.message}") }
        } else {
            Log.w(TAG, "有 ${counter[1]} 个文件未复制成功，本次不写指纹，下次启动会重试")
        }
        return dir
    }

    /** 包内 renderer 清单指纹。只取名字和大小，够用且便宜。 */
    private fun assetStamp(ctx: Context): String {
        val sb = StringBuilder()
        collectNames(ctx, ASSET_ROOT, sb)
        val md = MessageDigest.getInstance("SHA-1")
        md.update(sb.toString().toByteArray())
        return STAMP_PREFIX + "-" + md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun collectNames(ctx: Context, assetPath: String, sb: StringBuilder) {
        val children = runCatching { ctx.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            // 是文件：记下路径与大小（拿不到大小记 -1，不影响判定"变了没有"）
            val size = runCatching { ctx.assets.open(assetPath).use { it.available() } }.getOrDefault(-1)
            sb.append(assetPath).append(':').append(size).append('\n')
            return
        }
        for (name in children.sorted()) {
            collectNames(ctx, "$assetPath/$name", sb)
        }
    }

    private fun copyDir(ctx: Context, assetPath: String, outDir: File, counter: IntArray) {
        val children = runCatching { ctx.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            if (copyFile(ctx, assetPath, outDir)) counter[0]++ else counter[1]++
            return
        }
        outDir.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val sub = runCatching { ctx.assets.list(childAsset) }.getOrNull()
            if (sub.isNullOrEmpty()) {
                if (copyFile(ctx, childAsset, File(outDir, name))) counter[0]++ else counter[1]++
            } else {
                copyDir(ctx, childAsset, File(outDir, name), counter)
            }
        }
    }

    private fun copyFile(ctx: Context, assetPath: String, outFile: File): Boolean =
        runCatching {
            outFile.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
        }.onFailure {
            Log.w(TAG, "复制失败 $assetPath: ${it.message}")
        }.isSuccess
}
