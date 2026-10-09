package com.dtu.cleaner.util

import android.content.Context
import android.os.Build
import com.dtu.cleaner.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地问题记录：崩溃和出错只写在手机里（App 自己的目录），不上传。
 * 用户在“设置 → 反馈问题”里生成报告，自己决定发给谁。
 */
object AppLog {
    private var dir: File? = null
    private const val MAX_EVENTS_BYTES = 128 * 1024
    private const val KEEP_CRASHES = 8
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** 记一条出错信息（不含照片文件名） */
    @Synchronized
    fun event(msg: String, e: Throwable? = null) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "events.log")
            if (f.length() > MAX_EVENTS_BYTES) {
                // 只留后一半
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            val line = buildString {
                append(stamp).append("  ").append(msg)
                if (e != null) append("：").append(e.javaClass.simpleName).append(' ').append(e.message ?: "")
                append('\n')
            }
            f.appendText(line)
        }
    }

    private fun writeCrash(t: Thread, e: Throwable) {
        val d = dir ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        File(d, "crash-${System.currentTimeMillis()}.txt").writeText(
            "时间：$stamp\n版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n线程：${t.name}\n\n$sw",
        )
        File(d, "pending").writeText("1")
        d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP_CRASHES)?.forEach { it.delete() }
    }

    /** 上次是不是意外退出了（还没提醒过用户） */
    fun hasPendingCrash(): Boolean = dir?.let { File(it, "pending").exists() } == true

    fun clearPending() { dir?.let { File(it, "pending").delete() } }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}（${Build.BRAND}）\n" +
            "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.DISPLAY}"

    /** 报告里的出错记录和崩溃记录，路径里的文件名换掉 */
    fun logsForReport(): String {
        val d = dir ?: return ""
        val events = File(d, "events.log").takeIf { it.exists() }?.readLines()?.takeLast(80)?.joinToString("\n").orEmpty()
        val crashes = d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.take(3)
            ?.joinToString("\n\n") { it.readText().lines().take(60).joinToString("\n") }.orEmpty()
        return scrub(
            "---- 最近出错 ----\n" + events.ifEmpty { "（没有）" } +
                "\n\n---- 崩溃记录 ----\n" + crashes.ifEmpty { "（没有）" },
        )
    }

    private val pathRe = Regex("""/storage/[^\s:'"]+""")

    fun scrub(s: String) = s.replace(pathRe, "/storage/…")
}
