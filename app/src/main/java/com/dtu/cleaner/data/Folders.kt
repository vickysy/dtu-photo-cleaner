package com.dtu.cleaner.data

import android.content.Context

/**
 * 哪些文件夹不算“照片”：
 * - 自动排除缓存：浏览器、视频 App 看视频时自动存下的封面、预览帧，有的系统（鸿蒙上见过）会把它们
 *   放进公共文件夹，系统相册把它们当照片，一下子多出几千张，而且归那个 App 管、删不掉。
 *   隐藏文件夹（名字以 . 开头）、名字里带 cache / thumbnail / thumbs 的、Android/data 和 Android/obb 里的，一律不算。
 * - 用户自己选了“不看这个文件夹”的（存在本机，设置里能恢复），连同里面的子文件夹。
 * 不算的图既不显示，也不分析；文件本身不动。
 */
object Folders {
    private const val PREFS = "folders"
    private const val KEY = "hidden"
    private val CACHE_WORDS = listOf("cache", "thumbnail", "thumbs")

    /** 缓存、隐藏文件夹：[relPath] 是 MediaStore 的 RELATIVE_PATH（如 "DCIM/Camera/"） */
    fun isJunk(relPath: String): Boolean {
        val segs = relPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return false
        if (segs[0].equals("android", true) && segs.size > 1 && (segs[1].equals("data", true) || segs[1].equals("obb", true))) return true
        return segs.any { s -> s.startsWith(".") || CACHE_WORDS.any { it in s.lowercase() } }
    }

    fun hidden(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun hide(ctx: Context, relPath: String) = edit(ctx) { it + relPath }

    fun unhide(ctx: Context, relPath: String) = edit(ctx) { it - relPath }

    private fun edit(ctx: Context, change: (Set<String>) -> Set<String>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY, change(p.getStringSet(KEY, emptySet()).orEmpty())).apply()
    }

    /** 这一项要不要排除（缓存，或者在用户隐藏的文件夹及其子文件夹里） */
    fun excluded(relPath: String, hidden: Set<String>): Boolean =
        isJunk(relPath) || hidden.any { h -> h.isNotEmpty() && relPath.startsWith(h) }
}
