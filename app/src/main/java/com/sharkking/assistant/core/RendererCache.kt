package com.sharkking.assistant.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 assets/renderer 展开到缓存目录，供 LocalHttpServer 作为静态根。
 * 对应 iOS 版「已复制渲染文件到缓存目录」那一步。
 */
object RendererCache {

    private const val TAG = "奶蛙"
    private const val ASSET_ROOT = "renderer"
    // 改动 renderer 下任何文件都要升这个版本号，否则旧缓存不会被替换
    private const val STAMP = ".copied_v95"

    fun ensure(ctx: Context): File {
        val dir = File(ctx.cacheDir, "renderer")
        val stamp = File(dir, STAMP)
        if (stamp.exists()) {
            Log.i(TAG, "渲染缓存目录有效")
            return dir
        }
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        copyDir(ctx, ASSET_ROOT, dir)
        stamp.writeText(System.currentTimeMillis().toString())
        Log.i(TAG, "已复制渲染文件到缓存目录")
        return dir
    }

    private fun copyDir(ctx: Context, assetPath: String, outDir: File) {
        val children = runCatching { ctx.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            copyFile(ctx, assetPath, outDir)
            return
        }
        outDir.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val sub = runCatching { ctx.assets.list(childAsset) }.getOrNull()
            if (sub.isNullOrEmpty()) {
                copyFile(ctx, childAsset, File(outDir, name))
            } else {
                copyDir(ctx, childAsset, File(outDir, name))
            }
        }
    }

    private fun copyFile(ctx: Context, assetPath: String, outFile: File) {
        runCatching {
            outFile.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Log.w(TAG, "复制失败 $assetPath: ${it.message}") }
    }
}
