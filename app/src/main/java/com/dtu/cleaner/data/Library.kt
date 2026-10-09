package com.dtu.cleaner.data

import android.content.Context
import com.dtu.cleaner.analysis.Analyzer
import com.dtu.cleaner.core.BadReason
import com.dtu.cleaner.core.BadShotRules
import com.dtu.cleaner.core.GroupParams
import com.dtu.cleaner.core.Grouper
import com.dtu.cleaner.core.ImageFeatures
import com.dtu.cleaner.core.PhotoInfo
import com.dtu.cleaner.core.RankedGroup
import com.dtu.cleaner.core.Ranker
import com.dtu.cleaner.core.Scene
import com.dtu.cleaner.core.SceneDetector
import com.dtu.cleaner.core.ScreenText
import com.dtu.cleaner.core.ShotKind
import com.dtu.cleaner.core.ShotSession
import com.dtu.cleaner.core.ShotSessions
import com.dtu.cleaner.core.Taste
import com.dtu.cleaner.core.VideoInfo
import com.dtu.cleaner.core.VideoReason
import com.dtu.cleaner.core.VideoRules
import com.dtu.cleaner.core.VideoScene
import com.dtu.cleaner.core.VideoScenes

class BadItem(val item: MediaItem, val reasons: List<BadReason>) {
    /** 只有“可能模糊”这种拿不准的，不默认勾选 */
    val confident: Boolean get() = reasons.any { it != BadReason.MAYBE_BLURRY }
}

/** 建议删的视频；[keepId] = 重复保存时留下的那一个 */
class VideoSuggestion(val item: MediaItem, val reasons: List<VideoReason>, val keepId: Long? = null) {
    val confident: Boolean get() = reasons.any { it.confident }
}

/** 值得压缩的视频：[estimated] 是压成 1080p 后大概多大 */
class CompressCandidate(val item: MediaItem, val estimated: Long)

/** 压好了、等用户确认删原视频的一对 */
class CompressedPair(val source: MediaItem, val output: MediaItem)

/** 某一时刻相册的整理结果，所有界面都从这里取数据。 */
class Library(
    val images: List<MediaItem>,
    val videos: List<MediaItem>,
    val byId: Map<Long, MediaItem>,
    val features: Map<Long, ImageFeatures>,
    /** 已评过的美感分（1..10） */
    val aesthetic: Map<Long, Float>,
    /** 还没分析的照片数 */
    val unanalyzed: Int,
    /** 其中因为判断规则升级、需要重新分析的照片数 */
    val outdated: Int,
    /** 相似组里还没做美感评分的照片数（升级到带美感评分的版本后要补一次） */
    val unscored: Int,
    /** 还没抽帧分析的视频数 */
    val videosUnanalyzed: Int,
    val groups: List<RankedGroup>,
    /** 场景精选：同一个地方连续拍了很多张 */
    val scenes: List<Scene>,
    val bad: List<BadItem>,
    val screenshots: List<MediaItem>,
    /** 截图识字后的分类 */
    val shotKinds: Map<Long, ShotKind>,
    /** 所有识过字的图（截图、相机照片、App 存的图）的分类：证件、聊天记录、文章、文件照片…… */
    val docKinds: Map<Long, ShotKind>,
    /** 还没识字的图（全相册识字没跑完时提示用） */
    val textsPending: Int,
    /** 截图里识别出的字（整理笔记用） */
    val shotTexts: Map<Long, String>,
    /** 连续截的一串图（直播、课程），可以整理成笔记 */
    val sessions: List<ShotSession>,
    val bigVideos: List<MediaItem>,
    /** 黑屏、误录、重复保存、不到 3 秒…… */
    val videoSuggest: List<VideoSuggestion>,
    val compressible: List<CompressCandidate>,
    val compressedPending: List<CompressedPair>,
    /** 视频场景精选：同一个场景拍了好几段 */
    val videoScenes: List<VideoScene>,
    /** 微信、小红书、得到……存到相册里的图 */
    val appImages: List<MediaItem>,
    val taste: Taste,
    val settings: AppSettings,
) {
    val imageBytes: Long = images.sumOf { it.size }
    val videoBytes: Long = videos.sumOf { it.size }
    val groupDeleteCount: Int = groups.sumOf { it.suggestedDelete.size }
    val groupDeleteBytes: Long = groups.sumOf { g -> g.suggestedDelete.sumOf { it.photo.size } }
    val sceneDeleteCount: Int = scenes.sumOf { it.members.size - it.highlights.size }
    val sceneDeleteBytes: Long = scenes.sumOf { sc -> sc.members.filter { it.id !in sc.highlights }.sumOf { it.size } }
    val compressSaving: Long = compressible.sumOf { it.item.size - it.estimated }
    val videoSceneDeleteBytes: Long = videoScenes.sumOf { sc -> sc.members.filter { it.id !in sc.keep }.sumOf { it.size } }

    fun oldScreenshots(nowMs: Long = System.currentTimeMillis()): List<MediaItem> {
        val cutoff = nowMs - settings.screenshotDays * 86_400_000L
        return screenshots.filter { it.timeMs < cutoff }
    }

    /**
     * 截图要不要预先勾上：证件永远不勾；取件码、验证码、付款码、二维码这类用完就没用的，3 天前的就勾；
     * 其余的按设置里的天数。
     */
    fun screenshotSuggested(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean {
        val kind = shotKinds[item.id]
        if (kind?.important == true) return false
        val age = nowMs - item.timeMs
        return age > settings.screenshotDays * 86_400_000L || (kind?.disposable == true && age > 3 * 86_400_000L)
    }

    /** 所有建议删除的加起来（同一张只算一次）：张数和字节数。大视频不预选，不算。 */
    fun reclaimable(): Pair<Int, Long> {
        val ids = HashSet<Long>()
        groups.forEach { g -> g.suggestedDelete.forEach { ids.add(it.photo.id) } }
        scenes.forEach { sc -> sc.members.forEach { if (it.id !in sc.highlights) ids.add(it.id) } }
        bad.forEach { if (it.confident) ids.add(it.item.id) }
        screenshots.forEach { if (screenshotSuggested(it)) ids.add(it.id) }
        videoSuggest.forEach { if (it.confident) ids.add(it.item.id) }
        videoScenes.forEach { sc -> sc.members.forEach { if (it.id !in sc.keep) ids.add(it.id) } }
        return ids.size to ids.sumOf { byId[it]?.size ?: 0L }
    }
}

object LibraryBuilder {
    /**
     * 分析算法有变化时 +1，旧缓存会自动重新分析。
     * v2：模糊判定区分背景虚化、暗光、手抖。v3：改为从原图解码分析（系统缩略图在部分手机上太小）。
     */
    const val FEATURE_VERSION = 3

    /** D图 压缩出来的视频文件名里带这个，不再建议压缩 */
    const val COMPRESSED_MARK = "_压缩"

    /** 太小的图（表情包、缩略图）不参与模糊判断和相似分组 */
    private const val MIN_SIDE = 400

    fun eligibleForAnalysis(it: MediaItem): Boolean =
        !it.isScreenshot && it.mime != "image/gif" && (it.maxSide == 0 || it.maxSide >= MIN_SIDE)

    /** 用户口味：从改过的推荐里攒的票数 */
    fun taste(ctx: Context): Taste {
        val p = ctx.getSharedPreferences("taste", Context.MODE_PRIVATE)
        return Taste.from(p.getInt("aes", 0), p.getInt("tech", 0))
    }

    fun build(ctx: Context, db: AnalysisDb, settings: AppSettings): Library {
        val images = MediaStoreSource.query(ctx, video = false)
        val videos = MediaStoreSource.query(ctx, video = true)
        val stored = db.loadAll()
        val stamps = db.stamps()
        val reviewed = db.reviewedIds()
        val texts = db.loadTexts()
        val taste = taste(ctx)

        val features = HashMap<Long, ImageFeatures>()
        var unanalyzed = 0
        var outdated = 0
        val infos = ArrayList<PhotoInfo>()
        val cameraInfos = ArrayList<PhotoInfo>()
        for (m in images) {
            if (!eligibleForAnalysis(m)) continue
            val st = stamps[m.id]
            if (st == null || st.modified != m.modified || st.size != m.size || st.version != FEATURE_VERSION) {
                unanalyzed++
                if (st != null && st.version != FEATURE_VERSION) outdated++
                continue
            }
            val sf = stored[m.id] ?: continue
            val f = sf.features ?: continue
            features[m.id] = f
            val sig = texts[m.id]?.takeIf { it.modified == m.modified }?.let { ScreenText.signature(it.text) }
            val info = PhotoInfo(m.id, m.timeMs, m.width, m.height, m.size, m.favorite, f, sf.faces, sf.aesthetic, sig, sf.lat, sf.lng)
            if (m.isCameraShot) cameraInfos.add(info)
            if (BadShotRules.excludedFromGrouping(f)) continue
            infos.add(info)
        }

        val params = GroupParams.forStrictness(settings.strictness, settings.gapSec, settings.keepPerGroup)
        val rawGroups = Grouper.group(infos, params)
        // 场景：只看相机拍的；用户已经处理过（大部分照片都标记过保留）的场景不再出现
        val scenes = SceneDetector.segment(cameraInfos)
            .filter { members -> members.count { it.id in reviewed } < members.size * 0.8 }
            .map { SceneDetector.build(it, taste = taste) }
            .sortedByDescending { it.startTime }
        val unscored = (rawGroups.flatten() + scenes.flatMap { it.members } + cameraInfos).distinctBy { it.id }
            .count { it.aesthetic == null && !BadShotRules.excludedFromGrouping(it.features) }
        val groups = rawGroups
            .map { Ranker.rank(it, settings.keepPerGroup, taste) }
            .filter { g -> g.members.any { it.photo.id !in reviewed } && g.suggestedDelete.isNotEmpty() }
            .sortedByDescending { it.startTime }

        val proposed = HashSet<Long>()
        groups.forEach { g -> g.suggestedDelete.forEach { proposed.add(it.photo.id) } }

        val bad = images.asSequence()
            .filter { eligibleForAnalysis(it) && it.isCameraShot && !it.favorite && it.id !in proposed && it.id !in reviewed }
            .mapNotNull { m ->
                val f = features[m.id] ?: return@mapNotNull null
                val r = BadShotRules.reasons(f, settings.blurSensitivity)
                if (r.isEmpty()) null else BadItem(m, r)
            }
            .sortedByDescending { it.item.timeMs }
            .toList()

        // 截图：识字分类、连续截图整理成笔记
        val screenshots = (images.filter { it.isScreenshot } + videos.filter { it.isScreenshot })
            .filter { it.id !in reviewed && !it.favorite }
            .sortedByDescending { it.timeMs }
        val shotTexts = HashMap<Long, String>()
        val shotKinds = HashMap<Long, ShotKind>()
        val docKinds = HashMap<Long, ShotKind>()
        var textsPending = 0
        for (s in images) {
            val t = texts[s.id]?.takeIf { it.modified == s.modified }
            // 没识过、或者识字规则升级了（旧结果先照用，等重新识）
            if ((t == null || t.version != Analyzer.TEXT_VERSION) && s.mime != "image/gif" && (s.maxSide == 0 || s.maxSide >= 400)) textsPending++
            if (t == null) continue
            // D图 自己的界面截图、D图 拼的长图不分类
            val selfMade = "com_dtu_cleaner" in s.name || "com.dtu.cleaner" in s.name || "D图笔记" in s.relPath
            val paper = stored[s.id]?.features?.color?.let { ScreenText.paperFraction(it) } ?: -1f
            val kind = if (selfMade) null else ScreenText.classify(t.text, t.codes, t.layout, s.isCameraShot, paper)
            // 证件、聊天记录这些是“找东西”用的，选过保留的也照样列出来
            if (kind != null) docKinds[s.id] = kind
            if (s.isScreenshot) {
                shotTexts[s.id] = t.text
                kind?.let { shotKinds[s.id] = it }
            }
        }
        val sessions = ShotSessions.split(
            images.filter { it.isScreenshot && it.mime != "image/gif" }.map { ShotSessions.Shot(it.id, it.timeMs, it.width, it.height) },
        )

        // 视频：抽帧结果 → 黑屏、误录、重复保存、同场景多段；再加上不到 3 秒的
        val vstored = db.loadVideos()
        var videosUnanalyzed = 0
        val vinfos = ArrayList<VideoInfo>()
        for (m in videos) {
            if (m.isScreenshot) continue
            val sv = vstored[m.id]
            if (sv == null || sv.modified != m.modified || sv.size != m.size || sv.version != Analyzer.VIDEO_VERSION) {
                videosUnanalyzed++
                continue
            }
            val f = sv.features ?: continue
            vinfos.add(VideoInfo(m.id, m.timeMs, m.durationMs, m.size, m.width, m.height, m.isCameraShot, m.favorite, f))
        }
        val dups = VideoRules.duplicates(vinfos)
        // 视频场景：同一个场景拍了好几段；用户处理过（大部分标记过保留）的不再出现
        val videoScenes = VideoScenes.segment(vinfos.filter { it.id !in dups })
            .filter { members -> members.count { it.id in reviewed } < members.size * 0.8 }
            .map { VideoScenes.build(it) }
            .sortedByDescending { it.startTime }
        val vById = vinfos.associateBy { it.id }
        val videoSuggest = videos.asSequence()
            .filter { !it.isScreenshot && !it.favorite && it.id !in reviewed }
            .mapNotNull { m ->
                val r = ArrayList<VideoReason>()
                if (m.id in dups) r.add(VideoReason.DUPLICATE)
                if (m.durationMs in 1..2999) r.add(VideoReason.SHORT)
                vById[m.id]?.let { v -> VideoRules.reasons(v).forEach { if (it !in r) r.add(it) } }
                if (r.isEmpty()) null else VideoSuggestion(m, r, dups[m.id])
            }
            .sortedByDescending { it.item.timeMs }
            .toList()

        // 压缩：相机拍的、码率高的视频；D图 压出来的、已经压过的不再压；收藏的不动
        val compressedMap = db.loadCompressed()
        val videoById = videos.associateBy { it.id }
        val compressedPending = compressedMap.mapNotNull { (src, out) ->
            val s = videoById[src] ?: return@mapNotNull null
            val o = videoById[out] ?: return@mapNotNull null
            CompressedPair(s, o)
        }
        val compressible = videos.asSequence()
            .filter { it.isCameraShot && !it.isScreenshot && !it.favorite && COMPRESSED_MARK !in it.name && it.durationMs > 0 && it.id !in compressedMap }
            .mapNotNull { m ->
                val est = VideoRules.estimateSize(m.durationMs, m.bitrate, VideoRules.targetBitrate(false, 30f))
                if (VideoRules.worthCompressing(m.size, est)) CompressCandidate(m, est) else null
            }
            .sortedByDescending { it.item.size - it.estimated }
            .toList()

        val appImages = images.filter { !it.isCameraShot && !it.isScreenshot && it.id !in reviewed && !it.favorite }
            .sortedByDescending { it.timeMs }

        val bigBytes = settings.bigVideoMb * 1_000_000L
        val bigVideos = videos.filter { it.size >= bigBytes && it.id !in reviewed }.sortedByDescending { it.size }

        val byId = HashMap<Long, MediaItem>(images.size + videos.size)
        images.forEach { byId[it.id] = it }
        videos.forEach { byId[it.id] = it }

        return Library(
            images = images,
            videos = videos,
            byId = byId,
            features = features,
            aesthetic = cameraInfos.filter { it.aestheticKnown }.associate { it.id to it.aesthetic!! },
            unanalyzed = unanalyzed,
            outdated = outdated,
            unscored = unscored,
            videosUnanalyzed = videosUnanalyzed,
            groups = groups,
            scenes = scenes,
            bad = bad,
            screenshots = screenshots,
            shotKinds = shotKinds,
            docKinds = docKinds,
            textsPending = textsPending,
            shotTexts = shotTexts,
            sessions = sessions,
            bigVideos = bigVideos,
            videoSuggest = videoSuggest,
            compressible = compressible,
            compressedPending = compressedPending,
            videoScenes = videoScenes,
            appImages = appImages,
            taste = taste,
            settings = settings,
        )
    }
}
