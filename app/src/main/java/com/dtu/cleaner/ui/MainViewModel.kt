package com.dtu.cleaner.ui

import android.app.Application
import android.app.PendingIntent
import android.app.usage.StorageStatsManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtu.cleaner.App
import com.dtu.cleaner.analysis.AnalysisRunner
import com.dtu.cleaner.analysis.Background
import com.dtu.cleaner.data.Library
import com.dtu.cleaner.data.LibraryBuilder
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.data.MediaStoreSource
import com.dtu.cleaner.util.AppLog
import com.dtu.cleaner.util.Fmt
import com.dtu.cleaner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Access { NONE, PARTIAL, FULL }

class StorageInfo(val total: Long, val free: Long)

enum class DeleteMode {
    /** 移到系统回收站（可恢复） */
    TRASH,
    /** 直接彻底删除 */
    DELETE,
    /** 回收站里彻底删除 */
    PURGE,
    /** 从回收站恢复 */
    RESTORE,
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val settings = app.settings.flow
    val progress = AnalysisRunner.progress

    var access by mutableStateOf(Access.NONE)
        private set
    var library by mutableStateOf<Library?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var storage by mutableStateOf<StorageInfo?>(null)
        private set
    var trash by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var cleaned by mutableStateOf(0 to 0L)
        private set

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val snackbar = SnackbarHostState()
    val deleteRequests = Channel<IntentSenderRequest>(Channel.BUFFERED)

    private val ui = application.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    /** 首页的“用之前先知道”卡片，点过“知道了”就不再出现 */
    var showTips by mutableStateOf(!ui.getBoolean("tipsSeen", false))
        private set

    /** 上次意外退出了，问用户要不要发问题报告 */
    var crashed by mutableStateOf(AppLog.hasPendingCrash())
        private set

    /** 这台手机不支持“移到回收站”时，问用户要不要直接删 */
    var trashFallback by mutableStateOf<TrashFallback?>(null)
        private set

    class TrashFallback(val items: List<MediaItem>, val reviewed: Collection<Long>, val onDone: (Set<Long>) -> Unit)

    private var refreshFailures = 0
    private var autoStarted = false
    private var dirty = false
    private var refreshing = false
    private var refreshAgain = false

    init {
        viewModelScope.launch {
            progress.map { it.finishedAt }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        viewModelScope.launch { settings.drop(1).collect { refresh() } }
    }

    private var lastRefreshAt = 0L

    /** 回到 App 时调用：权限变了、或者离上次读相册超过一分钟（期间可能拍了新照片），就重新读 */
    fun onAccessChanged(a: Access) {
        val changed = a != access
        access = a
        if (a != Access.NONE && pendingRecent) {
            pendingRecent = false
            openRecent()
        }
        val stale = System.currentTimeMillis() - lastRefreshAt > 60_000
        if (a != Access.NONE && (changed || library == null || (stale && current == Screen.Home))) refresh(autoAnalyze = true)
    }

    fun refresh(autoAnalyze: Boolean = false) {
        if (refreshing) { refreshAgain = true; return }
        refreshing = true
        viewModelScope.launch {
            loading = true
            try {
                do {
                    refreshAgain = false
                    val lib = withContext(Dispatchers.IO) { LibraryBuilder.build(app, app.db, app.settings.value) }
                    library = lib
                    refreshFailures = 0
                    lastRefreshAt = System.currentTimeMillis()
                    if (autoAnalyze && !autoStarted && (lib.unanalyzed > 0 || lib.unscored > 0 || lib.videosUnanalyzed > 0 || lib.textsPending > 0) && !progress.value.running) {
                        autoStarted = true
                        startAnalysis()
                    }
                } while (refreshAgain)
                refreshStorage()
                refreshTrash()
                cleaned = withContext(Dispatchers.IO) { app.db.cleanedTotal() }
            } catch (e: SecurityException) {
                access = Access.NONE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 读相册或缓存出错：记下来，别让整个 App 崩掉；稍后再试一次
                AppLog.event("读取相册出错", e)
                if (++refreshFailures <= 2) viewModelScope.launch {
                    kotlinx.coroutines.delay(1500)
                    refresh()
                } else {
                    snackbar.showSnackbar("读取相册出错了，可以在“设置 → 反馈问题”里把报告发给作者")
                }
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    private suspend fun refreshStorage() {
        storage = withContext(Dispatchers.IO) {
            try {
                val ssm = app.getSystemService(StorageStatsManager::class.java)
                StorageInfo(ssm.getTotalBytes(StorageManager.UUID_DEFAULT), ssm.getFreeBytes(StorageManager.UUID_DEFAULT))
            } catch (e: Exception) {
                val st = StatFs(Environment.getDataDirectory().path)
                StorageInfo(st.totalBytes, st.availableBytes)
            }
        }
    }

    fun toast(msg: String) {
        viewModelScope.launch { snackbar.showSnackbar(msg) }
    }

    fun refreshTrash() {
        viewModelScope.launch { trash = withContext(Dispatchers.IO) { MediaStoreSource.queryTrashed(app) } }
    }

    fun startAnalysis() { AnalysisRunner.start(app) }

    fun pauseAnalysis() = AnalysisRunner.cancel()

    fun reanalyzeAll() {
        viewModelScope.launch {
            AnalysisRunner.cancel()
            withContext(Dispatchers.IO) { app.db.clearFeatures() }
            startAnalysis()
        }
    }

    /** 把分析数据导出成 CSV（下载/D图诊断/），用来调判断规则 */
    fun exportDiagnostics() {
        val lib = library ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { com.dtu.cleaner.data.DiagnosticExport.export(app, app.db, lib) } }
            snackbar.showSnackbar(r.fold({ "已导出到 下载/D图诊断/$it" }, { "导出失败：${it.message}" }))
        }
    }

    fun resetReviewed() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.db.clearReviewed() }
            refresh()
            snackbar.showSnackbar("已清除，之前选择保留的照片会重新出现在建议里")
        }
    }

    /** 用户明确选择保留的照片，以后不再建议删除。 */
    fun markReviewed(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.db.addReviewed(ids) }
            refresh() // 先写完再刷新，首页的数字才对
        }
    }

    fun updateSettings(transform: (com.dtu.cleaner.data.AppSettings) -> com.dtu.cleaner.data.AppSettings) {
        val before = app.settings.value
        app.settings.update(transform)
        val after = app.settings.value
        if (before.autoAnalyze != after.autoAnalyze || before.monthlyReminder != after.monthlyReminder) {
            runCatching { Background.schedule(app) }.onFailure { AppLog.event("安排后台整理失败", it) }
        }
    }

    fun dismissTips() {
        showTips = false
        ui.edit().putBoolean("tipsSeen", true).apply()
    }

    fun dismissCrash() {
        crashed = false
        AppLog.clearPending()
    }

    private fun accessLabel() = when (access) {
        Access.FULL -> "全部照片"
        Access.PARTIAL -> "部分照片"
        Access.NONE -> "没有权限"
    }

    /** 生成问题报告，用系统分享发出去（微信、QQ…） */
    fun sendReport(ctx: android.content.Context) {
        dismissCrash()
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { Share.report(app, library, app.settings.value, accessLabel(), trash.size) }
            runCatching { Share.sendReport(ctx, text) }.onFailure {
                AppLog.event("分享问题报告失败", it)
                snackbar.showSnackbar("没能打开分享：${it.message}")
            }
        }
    }

    /** 把 D图 安装包发给朋友 */
    fun shareApp(ctx: android.content.Context) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Share.sendApp(ctx) } }
            r.onFailure {
                AppLog.event("分享安装包失败", it)
                snackbar.showSnackbar("没能打开分享：${it.message}")
            }
        }
    }

    // ---------- 页面跳转 ----------

    fun push(s: Screen) { stack.add(s) }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (current == Screen.Home && dirty) {
            dirty = false
            refresh()
        }
    }

    fun openGroups() {
        val lib = library ?: return
        push(Screen.Groups(SimilarSession(lib.groups) { lib.byId[it] }))
    }

    fun openScenes() {
        val lib = library ?: return
        push(Screen.Scenes(SceneSession(lib.scenes, lib.taste) { lib.byId[it] }))
    }

    fun openGrid(kind: GridKind) {
        val lib = library ?: return
        val entries = when (kind) {
            GridKind.BAD -> lib.bad.map { b -> GridEntry(b.item, b.confident, b.reasons.map { it.label }) }
            GridKind.SCREENSHOTS -> {
                val cutoff = System.currentTimeMillis() - lib.settings.screenshotDays * 86_400_000L
                lib.screenshots.map { s ->
                    val tags = ArrayList<String>()
                    lib.shotKinds[s.id]?.let { tags.add(it.label) }
                    if (s.isVideo) tags.add("录屏")
                    if (s.timeMs < cutoff) tags.add("超过${lib.settings.screenshotDays}天")
                    GridEntry(s, lib.screenshotSuggested(s), tags)
                }
            }
            GridKind.BIG_VIDEOS -> lib.bigVideos.map { GridEntry(it, false, emptyList()) }
            GridKind.VIDEOS -> lib.videoSuggest.map { v -> GridEntry(v.item, v.confident, v.reasons.map { it.label }) }
            GridKind.APP_IMAGES -> lib.appImages.map { GridEntry(it, false, listOf(it.source)) }
            GridKind.RECENT -> { openRecent(); return }
            GridKind.DOCS -> { openDocs(); return }
            GridKind.AESTHETIC -> lib.images.filter { it.id in lib.aesthetic }.map { GridEntry(it, false, emptyList(), lib.aesthetic[it.id]) }
        }
        push(Screen.Grid(GridSession(kind, entries)))
    }

    fun openNotes() { if (library != null) push(Screen.Notes) }

    fun openDocs() { if (library != null) push(Screen.Docs) }

    /** 打开“文字与证件”里的一类：用完就没用的 3 天前的预先勾上，证件永远不勾 */
    fun openDocGroup(title: String, kinds: Set<com.dtu.cleaner.core.ShotKind>, note: String) {
        val lib = library ?: return
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        val entries = lib.docKinds.entries.filter { it.value in kinds }
            .mapNotNull { (id, kind) -> lib.byId[id]?.let { m -> GridEntry(m, kind.disposable && m.timeMs < cutoff && !m.favorite, listOf(kind.label, m.source)) } }
            .sortedByDescending { it.item.timeMs }
        push(Screen.Grid(GridSession(GridKind.DOCS, entries, title, note)))
    }

    // ---------- 刚拍的 ----------

    /** 打分进度；[session] 好了就显示照片 */
    data class RecentState(val done: Int = 0, val total: Int = 0, val label: String = "", val session: GridSession? = null)

    var recent by mutableStateOf<RecentState?>(null)
        private set

    /** 从桌面快捷方式进来时，权限还没确认，先记着 */
    private var pendingRecent = false

    fun requestRecent() {
        if (access == Access.NONE) pendingRecent = true else openRecent()
    }

    /** 最近 24 小时拍的照片（没有就看 3 天、7 天），只给这些打分，几秒钟出结果 */
    fun openRecent() {
        if (current == Screen.Recent) return
        push(Screen.Recent)
        recent = RecentState()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val camera = withContext(Dispatchers.IO) { MediaStoreSource.query(app, video = false) }.filter { it.isCameraShot }
            val (label, items) = listOf(1 to "最近 24 小时", 3 to "最近 3 天", 7 to "最近 7 天")
                .map { (d, l) -> l to camera.filter { it.timeMs >= now - d * 86_400_000L } }
                .firstOrNull { it.second.isNotEmpty() } ?: ("最近 7 天" to emptyList())
            recent = RecentState(0, items.size, label)
            try {
                withContext(Dispatchers.Default) {
                    com.dtu.cleaner.analysis.Analyzer(app, app.db, app.settings.value).quick(items) { d, t -> recent = recent?.copy(done = d, total = t) }
                }
                val entries = withContext(Dispatchers.IO) { recentEntries(items) }
                recent = recent?.copy(session = GridSession(GridKind.RECENT, entries))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.event("刚拍的打分出错", e)
                recent = recent?.copy(session = GridSession(GridKind.RECENT, items.map { GridEntry(it, false, emptyList()) }))
                snackbar.showSnackbar("打分出错了，照片先列出来")
            }
        }
    }

    /** 给刚拍的照片打标签、决定预先勾哪些：低分、明显拍糊、连拍里不是最好的 */
    private fun recentEntries(items: List<MediaItem>): List<GridEntry> {
        val settings = app.settings.value
        val stored = app.db.loadAll()
        val infos = items.mapNotNull { m ->
            val sf = stored[m.id] ?: return@mapNotNull null
            val f = sf.features ?: return@mapNotNull null
            com.dtu.cleaner.core.PhotoInfo(m.id, m.timeMs, m.width, m.height, m.size, m.favorite, f, sf.faces, sf.aesthetic)
        }
        val byId = infos.associateBy { it.id }
        val params = com.dtu.cleaner.core.GroupParams.forStrictness(settings.strictness, settings.gapSec, 1)
        val taste = LibraryBuilder.taste(app)
        val groups = com.dtu.cleaner.core.Grouper.group(infos.filter { !com.dtu.cleaner.core.BadShotRules.excludedFromGrouping(it.features) }, params)
            .map { com.dtu.cleaner.core.Ranker.rank(it, 1, taste) }
        val worseInBurst = groups.filter { it.nearIdentical }.flatMap { g -> g.members.filter { !it.keep }.map { it.photo.id } }.toHashSet()
        val similar = groups.filter { !it.nearIdentical }.flatMap { g -> g.members.filter { !it.keep }.map { it.photo.id } }.toHashSet()
        return items.sortedByDescending { it.timeMs }.map { m ->
            val info = byId[m.id]
            val score = info?.aesthetic?.takeIf { it > 0f }
            val bad = info?.let { com.dtu.cleaner.core.BadShotRules.reasons(it.features, settings.blurSensitivity) } ?: emptyList()
            val eyesClosed = info?.faces?.let { it.count > 0 && it.minEyesOpen in 0f..0.35f } == true
            val tags = ArrayList<String>()
            if (score != null && aesIsLow(score)) tags.add("低分")
            bad.forEach { tags.add(it.label) }
            if (m.id in worseInBurst) tags.add("连拍里不是最好的")
            if (m.id in similar) tags.add("和别的很像")
            if (eyesClosed) tags.add("有人闭眼")
            val suggested = !m.favorite && (
                (score != null && aesIsLow(score)) ||
                    bad.any { it != com.dtu.cleaner.core.BadReason.MAYBE_BLURRY } ||
                    m.id in worseInBurst
                )
            GridEntry(m, suggested, tags, score)
        }
    }

    fun openVideoScenes() {
        val lib = library ?: return
        push(Screen.VideoScenes(VideoSceneSession(lib.videoScenes) { lib.byId[it] }))
    }

    fun openCompress() { if (library != null) push(Screen.Compress) }

    // ---------- 口味 ----------

    /** 用户改了推荐又删除了：记下这次说明他更看重好看还是清楚 */
    fun recordTaste(votes: Pair<Int, Int>) {
        val (aes, tech) = votes
        if (aes == 0 && tech == 0) return
        val p = app.getSharedPreferences("taste", android.content.Context.MODE_PRIVATE)
        p.edit().putInt("aes", p.getInt("aes", 0) + aes).putInt("tech", p.getInt("tech", 0) + tech).apply()
        dirty = true
    }

    fun resetTaste() {
        app.getSharedPreferences("taste", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        refresh()
        toast("已恢复默认口味")
    }

    /** (美感票, 清晰票) */
    fun tasteVotes(): Pair<Int, Int> {
        val p = app.getSharedPreferences("taste", android.content.Context.MODE_PRIVATE)
        return p.getInt("aes", 0) to p.getInt("tech", 0)
    }

    // ---------- 视频压缩 ----------

    val compress = com.dtu.cleaner.media.Compressor.progress

    fun startCompress(items: List<MediaItem>, keep4k: Boolean) {
        com.dtu.cleaner.media.Compressor.start(app, items, keep4k) { refresh() }
    }

    fun stopCompress() = com.dtu.cleaner.media.Compressor.cancel()

    /** 压缩版留下，原视频放进最近删除（系统会弹窗确认） */
    fun keepCompressed(pairs: List<com.dtu.cleaner.data.CompressedPair>) {
        delete(pairs.map { it.source }, deleteModeFromSettings()) { ids ->
            if (ids.isNotEmpty()) viewModelScope.launch {
                withContext(Dispatchers.IO) { app.db.removeCompressed(ids) }
                refresh()
            }
        }
    }

    /** 不要压缩版：删掉压出来的文件，原视频不动 */
    fun discardCompressed(pairs: List<com.dtu.cleaner.data.CompressedPair>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                val n = com.dtu.cleaner.media.Compressor.deleteOutputs(app, pairs.map { it.output })
                app.db.removeCompressed(pairs.map { it.source.id })
                n
            }
            refresh()
            snackbar.showSnackbar("已删掉 $n 个压缩版，原视频没动")
        }
    }

    // ---------- 删除 / 恢复：都要经过系统确认弹窗 ----------

    private class DeleteJob(
        val chunks: List<List<MediaItem>>,
        val mode: DeleteMode,
        val reviewed: Collection<Long>,
        val onDone: (Set<Long>) -> Unit,
    ) {
        var index = 0
        val succeeded = HashSet<Long>()
    }

    private var job: DeleteJob? = null

    /** 照片/视频交给系统删除或恢复；[reviewed] 是用户明确保留的，以后不再提示。 */
    fun delete(items: List<MediaItem>, mode: DeleteMode, reviewed: Collection<Long> = emptyList(), onDone: (Set<Long>) -> Unit = {}) {
        if (items.isEmpty()) {
            if (reviewed.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { app.db.addReviewed(reviewed) }
            onDone(emptySet())
            return
        }
        if (job != null) return
        val j = DeleteJob(items.chunked(CHUNK), mode, reviewed, onDone)
        job = j
        launchChunk(j)
    }

    /** “删除”按钮对应的模式：看设置里是放回收站还是直接删。 */
    fun deleteModeFromSettings() = if (app.settings.value.useTrash) DeleteMode.TRASH else DeleteMode.DELETE

    private fun launchChunk(j: DeleteJob) {
        val uris = j.chunks[j.index].map { it.uri }
        val cr = app.contentResolver
        val pi: PendingIntent = try {
            when (j.mode) {
                DeleteMode.TRASH -> MediaStore.createTrashRequest(cr, uris, true)
                DeleteMode.RESTORE -> MediaStore.createTrashRequest(cr, uris, false)
                DeleteMode.DELETE, DeleteMode.PURGE -> MediaStore.createDeleteRequest(cr, uris)
            }
        } catch (e: Exception) {
            job = null
            AppLog.event("系统删除请求失败（${j.mode}，${uris.size} 项）", e)
            if (j.mode == DeleteMode.TRASH && j.index == 0) {
                // 有的手机不支持“移到回收站”：问用户要不要直接删
                trashFallback = TrashFallback(j.chunks.flatten(), j.reviewed, j.onDone)
                return
            }
            viewModelScope.launch { snackbar.showSnackbar("系统拒绝了这次操作：${e.message}") }
            finish(j)
            return
        }
        deleteRequests.trySend(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    fun answerTrashFallback(deleteNow: Boolean) {
        val f = trashFallback ?: return
        trashFallback = null
        if (deleteNow) delete(f.items, DeleteMode.DELETE, f.reviewed, f.onDone) else f.onDone(emptySet())
    }

    fun onDeleteResult(ok: Boolean) {
        val j = job ?: return
        if (ok) {
            j.chunks[j.index].forEach { j.succeeded.add(it.id) }
            j.index++
            if (j.index < j.chunks.size) {
                launchChunk(j)
                return
            }
        }
        job = null
        finish(j)
    }

    private fun finish(j: DeleteJob) {
        val ids: Set<Long> = j.succeeded
        val items = j.chunks.flatten().filter { it.id in ids }
        val bytes = items.sumOf { it.size }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (j.mode) {
                    DeleteMode.TRASH, DeleteMode.DELETE -> if (items.isNotEmpty()) {
                        // 用户在系统弹窗点了“取消”就什么都不记
                        app.db.addReviewed(j.reviewed)
                        app.db.addCleaned(items.size, bytes)
                    }
                    DeleteMode.RESTORE -> if (items.isNotEmpty()) {
                        app.db.addCleaned(-items.size, -bytes)
                        // 特意恢复的照片说明想留着，以后不再建议删除
                        app.db.addReviewed(ids)
                    }
                    DeleteMode.PURGE -> Unit
                }
            }
            cleaned = withContext(Dispatchers.IO) { app.db.cleanedTotal() }
            refreshTrash()
            refreshStorage()
            if (items.isNotEmpty()) {
                val n = items.size
                snackbar.showSnackbar(
                    when (j.mode) {
                        DeleteMode.TRASH -> "已移到回收站 $n 项（${Fmt.bytes(bytes)}），清空回收站后才真正腾出空间"
                        DeleteMode.DELETE -> "已彻底删除 $n 项，腾出 ${Fmt.bytes(bytes)}"
                        DeleteMode.PURGE -> "已彻底删除 $n 项，腾出 ${Fmt.bytes(bytes)}"
                        DeleteMode.RESTORE -> "已恢复 $n 项"
                    },
                )
            }
        }
        if (ids.isNotEmpty()) dirty = true
        j.onDone(ids)
    }

    companion object {
        /** 一次交给系统的数量，太多会超出系统弹窗的传输上限 */
        private const val CHUNK = 500
    }
}
