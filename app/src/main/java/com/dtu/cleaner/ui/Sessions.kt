package com.dtu.cleaner.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dtu.cleaner.core.RankedGroup
import com.dtu.cleaner.core.Scene
import com.dtu.cleaner.core.SceneDetector
import com.dtu.cleaner.data.MediaItem

sealed interface Screen {
    data object Home : Screen
    class Groups(val session: SimilarSession) : Screen
    class Scenes(val session: SceneSession) : Screen
    class Grid(val session: GridSession) : Screen
    data object Trash : Screen
    data object Settings : Screen
    /** 截图笔记：连续截的图整理成长图/文字 */
    data object Notes : Screen
    /** 视频压缩 */
    data object Compress : Screen
    /** 视频场景精选 */
    class VideoScenes(val session: VideoSceneSession) : Screen
    /** 刚拍的：拍完马上看评分、删掉拍得不好的 */
    data object Recent : Screen
    /** 文字与证件：按内容分好类的截图和照片 */
    data object Docs : Screen
    /** 分类相册里的一类（证件、聊天记录、文章与文件……）：平时看，要用时多选 */
    class Album(val session: AlbumSession) : Screen
}

/**
 * 分类相册：像相册一样点开就看；点“选择”再多选（也能一划多选），拼长图、分享、设为好评、删除。
 * “用完就没用的”这类一打开就在选择状态，3 天前的已经勾上。
 */
class AlbumSession(
    val title: String,
    val note: String,
    val entries: List<GridEntry>,
    /** 能设为好评（聊天记录、好评本身） */
    val praiseable: Boolean,
    /** 一打开就是选择状态（清理用的类） */
    val startSelecting: Boolean = false,
) {
    var selecting by mutableStateOf(startSelecting)
    val selected = mutableStateMapOf<Long, Unit>()
    val deleted = mutableStateMapOf<Long, Unit>()
    var filter by mutableStateOf<String?>(null)

    init {
        if (startSelecting) entries.forEach { if (it.suggested) selected[it.item.id] = Unit }
    }

    fun visible(praise: Set<Long>): List<GridEntry> = entries.filter {
        it.item.id !in deleted && when (val f = filter) {
            null -> true
            PRAISE -> it.item.id in praise
            else -> f in it.tags
        }
    }

    fun selectedItems(): List<MediaItem> = entries.filter { it.item.id in selected && it.item.id !in deleted }.map { it.item }

    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected[id] = Unit }

    companion object {
        const val PRAISE = "好评"
    }
}

/**
 * 视频场景精选：每个场景推荐留几段，其余默认勾选；每个场景自己的“删除”只删这一个场景。
 */
class VideoSceneSession(initial: List<com.dtu.cleaner.core.VideoScene>, val itemOf: (Long) -> MediaItem?) {
    val scenes = mutableStateListOf<com.dtu.cleaner.core.VideoScene>().apply { addAll(initial) }
    val marks = mutableStateMapOf<Long, Boolean>()
    val deleted = mutableStateMapOf<Long, Unit>()
    val done = mutableStateMapOf<Long, Unit>()

    init { initial.forEach { reset(it) } }

    fun visible(sc: com.dtu.cleaner.core.VideoScene) = sc.members.filter { it.id !in deleted }

    val active: List<com.dtu.cleaner.core.VideoScene> get() = scenes.filter { it.key !in done && visible(it).size >= 2 }

    fun isMarked(id: Long) = marks[id] == true

    fun toggle(id: Long) { marks[id] = !isMarked(id) }

    fun reset(sc: com.dtu.cleaner.core.VideoScene) = sc.members.forEach { marks[it.id] = it.id !in sc.keep }

    fun setKeep(sc: com.dtu.cleaner.core.VideoScene, k: Int) {
        val i = scenes.indexOfFirst { it.key == sc.key }
        if (i < 0) return
        val n = visible(sc)
        val ns = com.dtu.cleaner.core.VideoScenes.build(n, k.coerceIn(1, n.size))
        scenes[i] = ns
        reset(ns)
    }

    fun selectedIdsIn(sc: com.dtu.cleaner.core.VideoScene): List<Long> = visible(sc).filter { isMarked(it.id) }.map { it.id }

    fun selectedIn(sc: com.dtu.cleaner.core.VideoScene): List<MediaItem> = selectedIdsIn(sc).mapNotNull { itemOf(it) }

    fun keptIn(sc: com.dtu.cleaner.core.VideoScene): List<Long> = visible(sc).filter { !isMarked(it.id) }.map { it.id }

    fun selectedItems(): List<MediaItem> = active.flatMap { selectedIn(it) }

    fun onDeleted(ids: Set<Long>) = ids.forEach { deleted[it] = Unit }

    fun markDone(list: Collection<com.dtu.cleaner.core.VideoScene>) = list.forEach { done[it.key] = Unit }
}

/**
 * “相似照片”一页看完：所有组一路往下滑，AI 已经按建议勾好要删的，用户只改不对的，最后一次删完。
 * 分两类：几乎一样（连拍/重复，可靠）和 同一场景换了角度（容易判断错，需要看一眼）。
 */
class SimilarSession(val groups: List<RankedGroup>, val itemOf: (Long) -> MediaItem?) {
    /** 照片 id → 是否标记删除（初始值来自 AI 建议） */
    val marks = mutableStateMapOf<Long, Boolean>()
    val deleted = mutableStateMapOf<Long, Unit>()
    var tab by mutableIntStateOf(if (groups.any { it.nearIdentical }) 0 else 1)

    init { groups.forEach { reset(it) } }

    val near: List<RankedGroup> get() = groups.filter { it.nearIdentical && visibleMembers(it).size >= 2 }
    val varied: List<RankedGroup> get() = groups.filter { !it.nearIdentical && visibleMembers(it).size >= 2 }

    fun visibleMembers(g: RankedGroup) = g.members.filter { it.photo.id !in deleted }

    fun isMarked(id: Long) = marks[id] == true

    fun toggle(id: Long) { marks[id] = !isMarked(id) }

    /** 恢复成 AI 的建议 */
    fun reset(g: RankedGroup) = g.members.forEach { marks[it.photo.id] = !it.keep }

    fun keepAll(g: RankedGroup) = g.members.forEach { marks[it.photo.id] = false }

    fun allKept(g: RankedGroup) = visibleMembers(g).none { isMarked(it.photo.id) }

    /** 当前标签页里的组 */
    val shown: List<RankedGroup> get() = if (tab == 0) near else varied

    /** 只删当前这一类里勾选的 */
    fun selectedItems(inGroups: List<RankedGroup> = shown): List<MediaItem> = inGroups.asSequence()
        .flatMap { visibleMembers(it).asSequence() }
        .filter { isMarked(it.photo.id) }
        .mapNotNull { itemOf(it.photo.id) }
        .toList()

    /** 删除后，这一类里留下来的照片以后不再提示 */
    fun keptIds(inGroups: List<RankedGroup> = shown): List<Long> =
        inGroups.flatMap { visibleMembers(it) }.filter { !isMarked(it.photo.id) }.map { it.photo.id }

    fun onDeleted(ids: Set<Long>) = ids.forEach { deleted[it] = Unit }

    /**
     * 用户改过推荐的组里，留下的和删掉的两两比较：美感高但没那么清楚的被留下 → 更看重好看；反过来 → 更看重清楚。
     * 返回 (美感票, 清晰票)。
     */
    fun tasteVotes(inGroups: List<RankedGroup>): Pair<Int, Int> {
        var aes = 0
        var tech = 0
        for (g in inGroups) {
            val vis = visibleMembers(g)
            if (vis.none { isMarked(it.photo.id) == it.keep }) continue // 没改过
            val kept = vis.filter { !isMarked(it.photo.id) }
            val dropped = vis.filter { isMarked(it.photo.id) }
            for (k in kept) for (d in dropped) {
                val ka = k.photo.aesthetic ?: continue
                val da = d.photo.aesthetic ?: continue
                val aesDiff = ka - da
                val techDiff = k.tech - d.tech
                if (kotlin.math.abs(aesDiff) < 0.15f || kotlin.math.abs(techDiff) < 0.05f) continue
                if ((aesDiff > 0) == (techDiff > 0)) continue // 两样都更好，说明不了口味
                if (aesDiff > 0) aes++ else tech++
            }
        }
        return aes to tech
    }
}

/**
 * 场景精选：每个场景推荐几张精华，其余默认勾选删除。用户可以调保留张数、展开逐张改。
 * 删除按场景来：每个场景卡片自己的“删除”只删这个场景；处理完（删了或全部保留）的场景从列表里消失。
 * 底部“全部按推荐删除”才会一次处理所有还没处理的场景，并且要先确认。
 */
class SceneSession(initial: List<Scene>, val taste: com.dtu.cleaner.core.Taste = com.dtu.cleaner.core.Taste.NORMAL, val itemOf: (Long) -> MediaItem?) {
    val scenes = mutableStateListOf<Scene>().apply { addAll(initial) }
    /** 照片 id → 是否标记删除 */
    val marks = mutableStateMapOf<Long, Boolean>()
    val deleted = mutableStateMapOf<Long, Unit>()
    val expanded = mutableStateMapOf<Long, Unit>()
    /** 已经处理完的场景（删过或全部保留），不再显示 */
    val done = mutableStateMapOf<Long, Unit>()
    /** true = 场景里的照片按美感分从高到低排；false = 按拍摄时间 */
    var sortByAes by mutableStateOf(false)

    fun ordered(list: List<com.dtu.cleaner.core.PhotoInfo>) =
        if (sortByAes) list.sortedByDescending { it.aesthetic ?: 0f } else list

    init { initial.forEach { reset(it) } }

    fun visible(sc: Scene) = sc.members.filter { it.id !in deleted }

    /** 还要处理的场景 */
    val active: List<Scene> get() = scenes.filter { it.key !in done && visible(it).size >= 2 }

    fun isMarked(id: Long) = marks[id] == true

    fun toggle(id: Long) { marks[id] = !isMarked(id) }

    /** 按推荐：精华留下，其余勾选 */
    fun reset(sc: Scene) = sc.members.forEach { marks[it.id] = it.id !in sc.highlights }

    fun keepAll(sc: Scene) = sc.members.forEach { marks[it.id] = false }

    /** 改保留张数：重新挑精华 */
    fun setKeep(sc: Scene, k: Int) {
        val i = scenes.indexOfFirst { it.key == sc.key }
        if (i < 0) return
        val n = visible(sc)
        val ns = SceneDetector.build(n, k.coerceIn(1, n.size), taste)
        scenes[i] = ns
        if (sc.key in expanded && ns.key != sc.key) { expanded.remove(sc.key); expanded[ns.key] = Unit }
        reset(ns)
    }

    /** 这一个场景里勾选要删的 */
    fun selectedIdsIn(sc: Scene): List<Long> = visible(sc).filter { isMarked(it.id) }.map { it.id }

    fun selectedIn(sc: Scene): List<MediaItem> = selectedIdsIn(sc).mapNotNull { itemOf(it) }

    /** 这一个场景里留下的 */
    fun keptIn(sc: Scene): List<Long> = visible(sc).filter { !isMarked(it.id) }.map { it.id }

    /** 所有还没处理的场景里勾选要删的（“全部按推荐删除”用） */
    fun selectedItems(): List<MediaItem> = active.flatMap { selectedIn(it) }

    fun selectedIds(): List<Long> = active.flatMap { selectedIdsIn(it) }

    fun keptIds(): List<Long> = active.flatMap { keptIn(it) }

    fun onDeleted(ids: Set<Long>) = ids.forEach { deleted[it] = Unit }

    fun markDone(scenes: Collection<Scene>) = scenes.forEach { done[it.key] = Unit; expanded.remove(it.key) }
}

enum class GridKind(val title: String) {
    BAD("废片"),
    SCREENSHOTS("截图与录屏"),
    BIG_VIDEOS("大视频"),
    /** 黑屏、误录、重复保存、不到 3 秒、同场景多段 */
    VIDEOS("可删的视频"),
    AESTHETIC("美感排行"),
    /** 微信、小红书、得到……存到相册里的图 */
    APP_IMAGES("App 存的图"),
    /** 刚拍的照片：现场打分 */
    RECENT("刚拍的"),
    /** 文字与证件里的某一类 */
    DOCS("文字与证件"),
}

class GridEntry(val item: MediaItem, val suggested: Boolean, val tags: List<String>, val score: Float? = null)

class GridSession(val kind: GridKind, val entries: List<GridEntry>, val title: String = kind.title, val note: String? = null) {
    val selected = mutableStateMapOf<Long, Unit>()
    val deleted = mutableStateMapOf<Long, Unit>()
    var filter by mutableStateOf<String?>(null)

    init {
        entries.forEach { if (it.suggested) selected[it.item.id] = Unit }
    }

    /** 美感排行：false = 从低到高（先看最差的），true = 从高到低 */
    var sortDesc by mutableStateOf(false)

    fun visible(): List<GridEntry> {
        val list = entries.filter { it.item.id !in deleted && (filter == null || filter in it.tags) }
        if (kind != GridKind.AESTHETIC) return list
        return if (sortDesc) list.sortedByDescending { it.score ?: 0f } else list.sortedBy { it.score ?: 0f }
    }

    fun selectedItems(): List<MediaItem> = entries.filter { it.item.id in selected && it.item.id !in deleted }.map { it.item }

    /** AI 建议删但用户取消勾选的：用户明确想留，以后不再提示 */
    fun explicitlyKept(): List<Long> = entries.filter { it.suggested && it.item.id !in selected }.map { it.item.id }
}
