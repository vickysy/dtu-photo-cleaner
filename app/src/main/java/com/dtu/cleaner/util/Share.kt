package com.dtu.cleaner.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dtu.cleaner.BuildConfig
import com.dtu.cleaner.data.AppSettings
import com.dtu.cleaner.data.Library
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 通过系统分享（微信、QQ 等）发文件：问题报告、安装包。 */
object Share {
    private fun authority(ctx: Context) = "${ctx.packageName}.files"

    private fun send(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 生成问题报告：手机型号、系统版本、照片数量、出错记录。不含照片和文件名。 */
    fun report(ctx: Context, lib: Library?, settings: AppSettings, access: String, trashCount: Int): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        return buildString {
            appendLine("轻了相册 问题报告")
            appendLine("生成时间：$now")
            appendLine("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            appendLine("手机：${AppLog.device()}")
            appendLine("相册权限：$access")
            if (lib != null) {
                appendLine("照片 ${lib.images.size} 张，视频 ${lib.videos.size} 个；已分析 ${lib.features.size} 张，未分析 ${lib.unanalyzed} 张，未评分 ${lib.unscored} 张")
                appendLine(
                    "建议：相似 ${lib.groups.size} 组，场景 ${lib.scenes.size} 个，废片 ${lib.bad.size} 张，" +
                        "截图 ${lib.screenshots.size} 项，大视频 ${lib.bigVideos.size} 个，可删视频 ${lib.videoSuggest.size} 个，可压缩 ${lib.compressible.size} 个，视频场景 ${lib.videoScenes.size} 个；未分析视频 ${lib.videosUnanalyzed} 个",
                )
            } else {
                appendLine("相册还没读出来")
            }
            appendLine("最近删除：$trashCount 项")
            appendLine("设置：$settings")
            appendLine()
            append(AppLog.logsForReport())
        }
    }

    fun sendReport(ctx: Context, text: String) {
        val dir = File(ctx.cacheDir, "report").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "轻了相册问题报告-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".txt"
        val f = File(dir, name).apply { writeText(text) }
        send(ctx, f, "text/plain", "把问题报告发给…")
    }

    /** 把手机上装着的这个安装包发给朋友 */
    fun sendApp(ctx: Context) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val name = "轻了相册-v${BuildConfig.VERSION_NAME}.apk"
        val f = File(dir, name)
        val src = File(ctx.applicationInfo.sourceDir)
        if (!f.exists() || f.length() != src.length()) {
            dir.listFiles()?.forEach { it.delete() }
            src.copyTo(f, overwrite = true)
        }
        send(ctx, f, "application/vnd.android.package-archive", "把轻了相册发给…")
    }
}
