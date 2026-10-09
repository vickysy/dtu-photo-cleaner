package com.dtu.cleaner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** 相邻照片间隔多少秒以内算“同一时期” */
    val gapSec: Int = 120,
    /** 相似判定：0 严格 / 1 标准 / 2 宽松 */
    val strictness: Int = 1,
    /** 每组建议保留几张 */
    val keepPerGroup: Int = 1,
    /** 模糊灵敏度：0 低 / 1 中 / 2 高 */
    val blurSensitivity: Int = 1,
    /** 超过多少天的截图才预选删除 */
    val screenshotDays: Int = 30,
    /** 多大的视频算“大视频”（MB） */
    val bigVideoMb: Int = 300,
    /** true = 先放回收站（可恢复）；false = 直接彻底删除 */
    val useTrash: Boolean = true,
    /** 充电时自动分析新拍的照片 */
    val autoAnalyze: Boolean = true,
    /** 每月提醒一次能腾出多少空间 */
    val monthlyReminder: Boolean = true,
)

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    private fun read() = AppSettings(
        gapSec = prefs.getInt("gapSec", 120),
        strictness = prefs.getInt("strictness", 1),
        keepPerGroup = prefs.getInt("keepPerGroup", 1),
        blurSensitivity = prefs.getInt("blurSensitivity", 1),
        screenshotDays = prefs.getInt("screenshotDays", 30),
        bigVideoMb = prefs.getInt("bigVideoMb", 300),
        useTrash = prefs.getBoolean("useTrash", true),
        autoAnalyze = prefs.getBoolean("autoAnalyze", true),
        monthlyReminder = prefs.getBoolean("monthlyReminder", true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(state.value)
        prefs.edit()
            .putInt("gapSec", s.gapSec)
            .putInt("strictness", s.strictness)
            .putInt("keepPerGroup", s.keepPerGroup)
            .putInt("blurSensitivity", s.blurSensitivity)
            .putInt("screenshotDays", s.screenshotDays)
            .putInt("bigVideoMb", s.bigVideoMb)
            .putBoolean("useTrash", s.useTrash)
            .putBoolean("autoAnalyze", s.autoAnalyze)
            .putBoolean("monthlyReminder", s.monthlyReminder)
            .apply()
        state.value = s
    }
}
