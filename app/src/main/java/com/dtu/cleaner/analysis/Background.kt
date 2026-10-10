package com.dtu.cleaner.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dtu.cleaner.App
import com.dtu.cleaner.MainActivity
import com.dtu.cleaner.Perms
import com.dtu.cleaner.R
import com.dtu.cleaner.data.LibraryBuilder
import com.dtu.cleaner.ui.Access
import com.dtu.cleaner.util.AppLog
import com.dtu.cleaner.util.Fmt
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 后台整理：每天找一个充电的时候，把新拍的照片分析掉；每月看一次能腾出多少空间，多的话发个通知。
 * 系统（尤其国产手机的省电策略）不一定准时跑，跑不了也不影响打开 App 时手动分析。
 */
object Background {
    private const val WORK = "background-analysis"

    fun schedule(ctx: Context) {
        val s = (ctx.applicationContext as App).settings.value
        val wm = WorkManager.getInstance(ctx)
        if (!s.autoAnalyze && !s.monthlyReminder) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackgroundWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class BackgroundWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (Perms.access(app) != Access.FULL) return Result.success()
        val s = app.settings.value
        try {
            if (s.autoAnalyze) {
                val job = AnalysisRunner.start(app, foreground = false)
                try {
                    job.join()
                } catch (e: CancellationException) {
                    // 拔掉充电器之类，系统叫停：分析也停，已经分析的不会丢
                    job.cancel()
                    throw e
                }
            }
            if (s.monthlyReminder) MonthlyReminder.check(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.event("后台整理出错", e)
        }
        return Result.success()
    }
}

object MonthlyReminder {
    private const val CHANNEL = "monthly"
    private const val NOTIF_ID = 2
    private const val INTERVAL_MS = 30L * 86_400_000L
    /** 少于这么多就不打扰 */
    private const val MIN_BYTES = 500L * 1_000_000L

    fun check(app: App) {
        val prefs = app.getSharedPreferences("background", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("lastReminder", 0L)
        if (last == 0L) {
            // 刚装上不提醒，从现在开始算一个月
            prefs.edit().putLong("lastReminder", now).apply()
            return
        }
        if (now - last < INTERVAL_MS) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val lib = LibraryBuilder.build(app, app.db, app.settings.value)
        val (count, bytes) = lib.reclaimable()
        // minBytes 只是测试时用来调低门槛的，正常没有这个值
        if (bytes < prefs.getLong("minBytes", MIN_BYTES)) return
        notify(app, count, bytes)
        prefs.edit().putLong("lastReminder", now).apply()
    }

    private fun notify(ctx: Context, count: Int, bytes: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "每月清理提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle("这个月，相册清了没？")
            .setContentText("又能轻 ${Fmt.bytes(bytes)}：${Fmt.count(count)} 张重复、拍糊或多拍的，点开看看")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
