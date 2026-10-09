package com.dtu.cleaner.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    /** 和手机“设置-存储”一样按 1000 进位 */
    fun bytes(b: Long): String {
        if (b < 1000) return "$b B"
        val kb = b / 1000.0
        if (kb < 1000) return "%.0f KB".format(kb)
        val mb = kb / 1000.0
        if (mb < 1000) return if (mb < 10) "%.1f MB".format(mb) else "%.0f MB".format(mb)
        val gb = mb / 1000.0
        return if (gb < 100) "%.1f GB".format(gb) else "%.0f GB".format(gb)
    }

    fun count(n: Int): String = "%,d".format(n)

    fun dateTime(ms: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val pattern = if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return SimpleDateFormat(pattern, Locale.CHINA).format(Date(ms))
    }

    /** “10月4日 周六”；不是今年的带年份 */
    fun day(ms: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val week = "日一二三四五六"[c.get(Calendar.DAY_OF_WEEK) - 1]
        val d = SimpleDateFormat(if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日", Locale.CHINA).format(Date(ms))
        return "$d 周$week"
    }

    fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))

    fun date(ms: Long): String = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ms))

    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun daysLeft(expiresSec: Long): String {
        val d = ((expiresSec * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
        return "剩 $d 天"
    }
}
