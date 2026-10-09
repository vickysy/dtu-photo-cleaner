package com.dtu.cleaner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.dtu.cleaner.analysis.Background
import com.dtu.cleaner.data.AnalysisDb
import com.dtu.cleaner.data.SettingsStore
import com.dtu.cleaner.util.AppLog
import com.dtu.cleaner.util.ThumbFetcher
import com.dtu.cleaner.util.ThumbKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob())
    val db by lazy { AnalysisDb(this) }
    val settings by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        runCatching { Background.schedule(this) }.onFailure { AppLog.event("安排后台整理失败", it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ThumbKeyer())
            add(ThumbFetcher.Factory(this@App))
        }
        .crossfade(120)
        .build()
}
