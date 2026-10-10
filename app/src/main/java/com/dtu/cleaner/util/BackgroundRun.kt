package com.dtu.cleaner.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行：小米、荣耀这些手机切到别的 App 十来分钟就会把我们清掉，分析只能下次打开再接着做。
 * 用户允许“不受省电优化限制”后，系统基本不再清理（小米还要在“省电策略”里选“无限制”）。
 */
object BackgroundRun {
    fun allowed(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)

    /** 弹系统的“允许在后台运行？”；个别系统没有这个弹窗，就打开本 App 的设置页 */
    fun request(ctx: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(ask) }.onFailure { runCatching { ctx.startActivity(details) } }
    }
}
