package com.dtu.cleaner.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.dtu.cleaner.core.BadShotRules
import com.dtu.cleaner.core.RankedPhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断数据：把每张照片的分析结果和 App 的判断写成一张 CSV 表，存到“下载/D图诊断/”。
 * 只包含文件名和打分数字，不包含照片本身。用来调判断规则。
 */
object DiagnosticExport {
    private val HEADER = listOf(
        "name", "path", "time", "size", "width", "height", "camera", "screenshot", "favorite", "reviewed",
        "analyzed", "sharp", "peak", "ratio", "aniso", "mean", "std", "p95", "dark", "bright",
        "faces", "eyes", "smile", "aes", "blur_level", "bad", "group", "group_size", "score", "keep", "best", "tags",
        "near", "dhash", "vhash",
    )

    /** 返回写好的文件名 */
    fun export(ctx: Context, db: AnalysisDb, lib: Library): String {
        val stored = db.loadAll()
        val reviewed = db.reviewedIds()
        val groupOf = HashMap<Long, Triple<Int, Int, RankedPhoto>>()
        val nearOf = HashMap<Long, Boolean>()
        lib.groups.forEachIndexed { gi, g ->
            g.members.forEach { groupOf[it.photo.id] = Triple(gi + 1, g.members.size, it); nearOf[it.photo.id] = g.nearIdentical }
        }
        val badOf = lib.bad.associateBy { it.item.id }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val sb = StringBuilder("﻿") // 让 Excel 认出 UTF-8
        sb.append(HEADER.joinToString(",")).append('\n')
        for (m in lib.images) {
            val sf = stored[m.id]
            val f = lib.features[m.id]
            val g = groupOf[m.id]
            val row = listOf(
                m.name, m.relPath, fmt.format(Date(m.timeMs)), m.size, m.width, m.height,
                m.isCameraShot, m.isScreenshot, m.favorite, m.id in reviewed,
                f != null,
                f?.sharpness, f?.peakSharpness, f?.focusRatio, f?.anisotropy, f?.mean, f?.std, f?.p95, f?.darkFrac, f?.brightFrac,
                sf?.faces?.count, sf?.faces?.minEyesOpen, sf?.faces?.avgSmile, sf?.aesthetic,
                f?.let { BadShotRules.blurLevel(it, lib.settings.blurSensitivity) },
                badOf[m.id]?.reasons?.joinToString("|") { it.label },
                g?.first, g?.second, g?.third?.score, g?.third?.keep, g?.third?.best, g?.third?.tags?.joinToString("|"),
                nearOf[m.id], f?.let { java.lang.Long.toHexString(it.dHash) }, f?.let { java.lang.Long.toHexString(it.vHash) },
            )
            sb.append(row.joinToString(",") { csv(it) }).append('\n')
        }

        val name = "dtu-diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/D图诊断")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建文件")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        return name
    }

    private fun csv(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Float -> if (v.isNaN()) "" else "%.3f".format(Locale.US, v)
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
