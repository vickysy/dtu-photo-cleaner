package com.dtu.cleaner.analysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dtu.cleaner.App
import com.dtu.cleaner.MainActivity
import com.dtu.cleaner.R
import com.dtu.cleaner.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分析任务跑在 App 级协程里；同时开一个前台服务（通知栏显示进度），
 * 让系统别在锁屏/切到后台时把 App 杀掉。服务起不来也不影响分析本身。
 */
object AnalysisRunner {
    private val state = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = state.asStateFlow()
    private var job: Job? = null

    /**
     * 开始分析（已经在跑就返回正在跑的那个）。
     * [foreground] = 用户在 App 里点的：挂通知栏进度、开前台服务；后台充电时跑的不挂通知。
     */
    fun start(app: App, foreground: Boolean = true): Job {
        job?.let { if (it.isActive) return it }
        val j = app.scope.launch(Dispatchers.Default) {
            if (foreground) KeepAliveService.acquire(app, TAG)
            try {
                Analyzer(app, app.db, app.settings.value).run { p ->
                    state.value = p
                    if (foreground) KeepAliveService.update(app, "D图 正在整理相册", p.label + if (p.total > 0) " ${p.done} / ${p.total}" else "…", p.done, p.total)
                }
            } catch (e: CancellationException) {
                state.value = Progress(Phase.IDLE, finishedAt = System.currentTimeMillis(), message = "已暂停，下次打开会接着分析")
            } catch (e: Throwable) {
                AppLog.event("分析出错", e)
                state.value = Progress(Phase.FAILED, finishedAt = System.currentTimeMillis(), message = "分析出错：${e.message}")
            } finally {
                if (foreground) KeepAliveService.release(app, TAG)
            }
        }
        job = j
        return j
    }

    fun cancel() { job?.cancel() }

    private const val TAG = "analysis"
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15 给“数据同步”类前台服务每天 6 小时上限，到点必须自己停，否则系统会让 App 崩溃。
    // 分析、压缩本身不受影响（已经做完的不会丢），只是不再挂通知。
    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    /**
     * 分析和视频压缩共用一个前台服务（通知栏显示进度，免得被系统杀掉）。
     * 谁要用就 [acquire]，用完 [release]；都用完了才停。服务起不来也不影响干活，只是没有通知。
     */
    companion object {
        private const val CHANNEL = "analysis"
        private const val NOTIF_ID = 1
        private var lastUpdate = 0L
        private val holders = HashSet<String>()
        private var title = "D图 正在整理相册"
        private var text = "…"
        private var done = 0
        private var total = 0

        @Synchronized
        fun acquire(ctx: Context, tag: String) {
            holders.add(tag)
            ensureChannel(ctx)
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
                // 某些情况下系统不允许启动前台服务，照样干活，只是没有通知
            }
        }

        @Synchronized
        fun release(ctx: Context, tag: String) {
            holders.remove(tag)
            if (holders.isEmpty()) ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        fun update(ctx: Context, title: String, text: String, done: Int, total: Int) {
            this.title = title
            this.text = text
            this.done = done
            this.total = total
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 800 && done != total) return
            lastUpdate = now
            if (holders.isEmpty()) return
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx))
            } catch (_: SecurityException) {
            }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "整理进度", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(ctx: Context): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sparkle)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(total.coerceAtLeast(0), done, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
