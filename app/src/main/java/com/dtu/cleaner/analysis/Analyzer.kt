package com.dtu.cleaner.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.dtu.cleaner.core.BadReason
import com.dtu.cleaner.core.BadShotRules
import com.dtu.cleaner.core.FaceInfo
import com.dtu.cleaner.core.GroupParams
import com.dtu.cleaner.core.Grouper
import com.dtu.cleaner.core.ImageFeatures
import com.dtu.cleaner.core.ImageMetrics
import com.dtu.cleaner.core.PhotoInfo
import com.dtu.cleaner.core.Ranker
import com.dtu.cleaner.core.SceneDetector
import com.dtu.cleaner.core.ScreenText
import com.dtu.cleaner.core.ShotKind
import com.dtu.cleaner.core.TextBox
import com.dtu.cleaner.core.TextLayout
import com.dtu.cleaner.core.VideoFeatures
import com.dtu.cleaner.data.AnalysisDb
import com.dtu.cleaner.data.AppSettings
import com.dtu.cleaner.data.LibraryBuilder
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.data.MediaStoreSource
import com.dtu.cleaner.data.TextRow
import com.dtu.cleaner.util.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Phase { IDLE, SCANNING, FEATURES, FACES, GEO, TEXT, VIDEOS, TEXT_ALL, DONE, FAILED }

data class Progress(
    val phase: Phase = Phase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    /** 每次分析完成时更新，界面据此刷新 */
    val finishedAt: Long = 0,
    val message: String? = null,
) {
    val running: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total

    /** 界面上显示的这一步在做什么 */
    val label: String get() = when (phase) {
        Phase.SCANNING -> "正在读取相册"
        Phase.FEATURES -> "正在分析照片"
        Phase.FACES -> "正在评分：清晰、闭眼、构图"
        Phase.GEO -> "正在读取拍摄地点"
        Phase.TEXT -> "正在识别截图和文件里的字"
        Phase.VIDEOS -> "正在分析视频"
        Phase.TEXT_ALL -> "正在找证件和拍的文件"
        else -> "分析完成"
    }
}

/**
 * 分析流程（每一步的结果都存进本地数据库，下次只做新的）：
 * 1. 每张照片从原图解码到 640px，算指纹/清晰度/曝光；
 * 2. 先分一次组，只对“组里的照片”做人脸检测（看有没有人闭眼）、给相机照片打美感分；
 * 3. 读相机照片的拍摄地点（场景按地点切开）；
 * 4. 识别截图里的字和二维码（取件码、验证码……），以及“角度不同”的相似照片里的字（两页不同的文件分开）；
 * 5. 每个视频抽 5 帧，看是不是黑屏、误录、全程模糊，找重复保存的视频。
 */
class Analyzer(private val ctx: Context, private val db: AnalysisDb, private val settings: AppSettings) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL)

    suspend fun run(onProgress: (Progress) -> Unit) = coroutineScope {
        onProgress(Progress(Phase.SCANNING))
        val images = MediaStoreSource.query(ctx, video = false)
        val videos = MediaStoreSource.query(ctx, video = true)
        db.pruneMissing(images.map { it.id }.toHashSet())
        db.pruneMissingOther(videos.map { it.id }.toHashSet(), images.map { it.id }.toHashSet())
        val stamps = db.stamps()
        val todo = images.filter { m ->
            if (!LibraryBuilder.eligibleForAnalysis(m)) return@filter false
            val st = stamps[m.id]
            st == null || st.modified != m.modified || st.size != m.size || st.version != LibraryBuilder.FEATURE_VERSION
        } // 已按拍摄时间倒序：先分析最近的照片

        var done = 0
        onProgress(Progress(Phase.FEATURES, 0, todo.size))
        for (chunk in todo.chunked(48)) {
            ensureActive()
            val results = chunk.map { m -> async(decodeDispatcher) { m to features(m) } }.awaitAll()
            db.putFeatures(results, LibraryBuilder.FEATURE_VERSION)
            done += chunk.size
            onProgress(Progress(Phase.FEATURES, done, todo.size))
        }

        // 第二步：只对会被分到组里的照片做人脸检测和美感评分（还没做过的）
        val stored = db.loadAll()
        val infos = images.mapNotNull { m ->
            if (!LibraryBuilder.eligibleForAnalysis(m)) return@mapNotNull null
            val sf = stored[m.id] ?: return@mapNotNull null
            val f = sf.features ?: return@mapNotNull null
            if (BadShotRules.excludedFromGrouping(f)) return@mapNotNull null
            PhotoInfo(m.id, m.timeMs, m.width, m.height, m.size, m.favorite, f, sf.faces, sf.aesthetic)
        }
        // 用“宽松”参数分组，这样用户之后把相似度调宽也已经有数据
        val params = GroupParams.forStrictness(2, max(settings.gapSec, 120), settings.keepPerGroup)
        val byId = images.associateBy { it.id }
        val current = GroupParams.forStrictness(settings.strictness, settings.gapSec, settings.keepPerGroup)
        // 场景精选也要每张的美感分和人脸（相机拍的照片，按时间切场景）
        val cameraIds = images.filter { it.isCameraShot }.map { it.id }.toHashSet()
        val sceneMembers = SceneDetector.segment(infos.filter { it.id in cameraIds }).flatten()
        val looseGroups = Grouper.group(infos, params)
        val members = (looseGroups.flatten() + Grouper.group(infos, current).flatten() + sceneMembers).distinctBy { it.id }
        // 美感分：所有相机照片都评（用于“美感排行”）；人脸只看相似组和场景里的照片
        val candidates = (members + infos.filter { it.id in cameraIds }).distinctBy { it.id }
        val needFaces = members.filter { it.faces == null }.map { it.id }.toHashSet()
        val needAes = candidates.filter { it.aesthetic == null }.map { it.id }.toHashSet()
        val work = candidates.filter { it.id in needFaces || it.id in needAes }.mapNotNull { byId[it.id] }
        if (work.isNotEmpty()) {
            onProgress(Progress(Phase.FACES, 0, work.size))
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.08f)
                    .build(),
            )
            val scorer = if (needAes.isNotEmpty()) AestheticScorer.create(ctx) else null
            try {
                var fd = 0
                for (chunk in work.chunked(16)) {
                    ensureActive()
                    val rows = chunk.map { m ->
                        async(Dispatchers.IO) {
                            val bmp = try { loadBitmap(m, 720) } catch (e: Exception) { null }
                            val face = if (m.id in needFaces) bmp?.let { detectFaces(detector, it) } ?: FaceInfo(0, -1f, -1f) else null
                            // 评分失败记 -1，避免每次都重试
                            val aes = if (m.id in needAes && scorer != null) {
                                bmp?.let { runCatching { scorer.score(it) }.getOrNull() } ?: -1f
                            } else null
                            bmp?.recycle()
                            Triple(m.id, face, aes)
                        }
                    }.awaitAll()
                    db.putFaces(rows.mapNotNull { (id, face, _) -> face?.let { id to it } })
                    db.putAesthetic(rows.mapNotNull { (id, _, aes) -> aes?.let { id to it } })
                    fd += chunk.size
                    onProgress(Progress(Phase.FACES, fd, work.size))
                }
            } finally {
                detector.close()
                scorer?.close()
            }
        }

        // 第三步：拍摄地点（要“读取照片位置”权限；没有就跳过）
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val needGeo = images.filter { it.id in cameraIds && stored[it.id]?.let { s -> s.features != null && !s.geoRead } == true }
            if (needGeo.isNotEmpty()) {
                onProgress(Progress(Phase.GEO, 0, needGeo.size))
                var gd = 0
                for (chunk in needGeo.chunked(64)) {
                    ensureActive()
                    val rows = chunk.map { m -> async(decodeDispatcher) { m.id to readLocation(m) } }.awaitAll()
                    db.putGeo(rows)
                    gd += chunk.size
                    onProgress(Progress(Phase.GEO, gd, needGeo.size))
                }
            }
        }

        // 第四步：识字。截图全部识（分类、整理笔记），照片只识“角度不同”组里的（区分不同页的文件）
        val texts = db.loadTexts()
        fun needText(m: MediaItem) = texts[m.id].let { it == null || it.modified != m.modified || it.version != textVersion(m) }
        val shots = images.filter { it.isScreenshot && it.mime != "image/gif" && needText(it) }
        val docIds = looseGroups.filter { g -> !Ranker.rank(g, 1).nearIdentical }.flatten().map { it.id }.toHashSet()
        val docs = images.filter { it.id in docIds && needText(it) }
        val textWork = shots + docs
        recognizeText(textWork, Phase.TEXT, onProgress)

        // 第五步：视频抽帧（录屏不看）
        val vstored = db.loadVideos()
        val vtodo = videos.filter { m ->
            !m.isScreenshot && vstored[m.id].let { it == null || it.modified != m.modified || it.size != m.size || it.version != VIDEO_VERSION }
        }
        if (vtodo.isNotEmpty()) {
            onProgress(Progress(Phase.VIDEOS, 0, vtodo.size))
            // 中间那帧打美感分（视频场景精选挑精华用）；模型不是线程安全的，打分时排队
            val scorer = runCatching { AestheticScorer.create(ctx) }.getOrNull()
            try {
                var vd = 0
                for (chunk in vtodo.chunked(8)) {
                    ensureActive()
                    val rows = chunk.map { m -> async(videoDispatcher) { Triple(m, videoFeatures(m, scorer), VIDEO_VERSION) } }.awaitAll()
                    db.putVideos(rows)
                    vd += chunk.size
                    onProgress(Progress(Phase.VIDEOS, vd, vtodo.size))
                }
            } finally {
                scorer?.close()
            }
        }
        // 第六步：其余的照片也识字（找拍的证件、文件、票据）。最费时间，放在最后，充电时慢慢跑
        val texts2 = db.loadTexts()
        val rest = images.filter { m ->
            m.mime != "image/gif" && (m.maxSide == 0 || m.maxSide >= 400) &&
                texts2[m.id].let { it == null || it.modified != m.modified || it.version != textVersion(m) }
        }
        recognizeText(rest, Phase.TEXT_ALL, onProgress)

        onProgress(Progress(Phase.DONE, done, todo.size, finishedAt = System.currentTimeMillis()))
    }

    /**
     * 识字 + 二维码（截图和 App 存的图才扫二维码），记下字块排布（认聊天记录）。
     * 证件里的长串数字打码后再存。
     */
    private suspend fun recognizeText(work: List<MediaItem>, phase: Phase, onProgress: (Progress) -> Unit) = coroutineScope {
        if (work.isEmpty()) return@coroutineScope
        onProgress(Progress(phase, 0, work.size))
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val barcodes = BarcodeScanning.getClient()
        try {
            var td = 0
            for (chunk in work.chunked(8)) {
                ensureActive()
                val rows = chunk.map { m ->
                    async(Dispatchers.IO) {
                        val bmp = try { loadBitmap(m, if (m.isScreenshot) 1600 else if (m.isCameraShot) 1024 else 1280) } catch (e: Exception) { null }
                        val result = bmp?.let { runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(it, 0))) }.getOrNull() }
                        val layout = if (result != null && bmp != null) layoutOf(result, bmp) else null
                        val codes = if (!m.isCameraShot && bmp != null) {
                            runCatching { Tasks.await(barcodes.process(InputImage.fromBitmap(bmp, 0))).mapNotNull { it.rawValue ?: it.displayValue } }.getOrDefault(emptyList())
                        } else emptyList()
                        bmp?.recycle()
                        var text = result?.text ?: ""
                        if (ScreenText.classify(text, codes, layout, m.isCameraShot) == ShotKind.ID) text = ScreenText.maskNumbers(text)
                        TextRow(m, text, codes, layout)
                    }
                }.awaitAll()
                db.putTexts(rows) { textVersion(it) }
                td += chunk.size
                onProgress(Progress(phase, td, work.size))
            }
        } finally {
            recognizer.close()
            barcodes.close()
        }
    }

    /**
     * 字块排布：只数含汉字的字块（分数角标、图标不算）；气泡靠左/靠右；从上往下左右换了几次边；
     * 所有字块占画面多大。
     */
    private fun layoutOf(result: com.google.mlkit.vision.text.Text, bmp: Bitmap): TextLayout {
        val w = bmp.width
        val h = bmp.height
        val all = result.textBlocks.mapNotNull { it.boundingBox }
        val area = all.sumOf { it.width().toLong() * it.height() }.toFloat() / (w.toLong() * h).coerceAtLeast(1)
        val cjkBlocks = result.textBlocks.filter { b -> b.text.count { it in '\u4e00'..'\u9fff' } >= 2 && b.boundingBox != null }
            .sortedBy { it.boundingBox!!.top }
        val cjk = cjkBlocks.map { it.boundingBox!! }
        // 聊天气泡：左边的从头像右边开始、不到右边；右边的贴着右边、从中间往后开始
        val sides = cjk.map { r ->
            when {
                r.left < w * 0.3f && r.right < w * 0.78f -> -1
                r.left > w * 0.22f && r.right > w * 0.7f -> 1
                else -> 0
            }
        }
        var switches = 0
        var last = 0
        for (sd in sides) if (sd != 0) { if (last != 0 && sd != last) switches++; last = sd }
        // 聊天记录中间居中的时间：“10:12”“昨天 21:30”“2025年3月1日 上午10:12”（最上面状态栏的不算）
        val times = result.textBlocks.count { b ->
            val r = b.boundingBox ?: return@count false
            val c = (r.left + r.right) / 2f
            r.top > h * 0.06f && kotlin.math.abs(c - w / 2f) < w * 0.08f && r.width() < w * 0.6f &&
                ScreenText.CHAT_STAMP.matches(b.text.replace(" ", ""))
        }
        fun pm(v: Int, full: Int) = (v * 1000L / full.coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        val boxes = cjkBlocks.map { b ->
            val r = b.boundingBox!!
            TextBox(pm(r.left, w), pm(r.top, h), pm(r.right, w), pm(r.bottom, h), b.text.count { it in '\u4e00'..'\u9fff' })
        }
        // 颜色：缩到 72 像素宽看微信绿气泡、底色平不平
        val small = Bitmap.createScaledBitmap(bmp, 72, max(1, h * 72 / w.coerceAtLeast(1)), true)
        val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        if (small !== bmp) small.recycle()
        val (green, bg) = ScreenText.pixelStats(px)
        return TextLayout(cjk.size, sides.count { it == -1 }, sides.count { it == 1 }, switches, area.coerceIn(0f, 1f), times, boxes, green, bg)
    }

    /**
     * 只分析这几张（“刚拍的”用）：特征、人脸、美感分，几秒钟就好，不用等整个相册。
     * 结果照样存进数据库，之后的全相册分析会跳过它们。
     */
    suspend fun quick(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit) = coroutineScope {
        val stamps = db.stamps()
        val eligible = items.filter { LibraryBuilder.eligibleForAnalysis(it) }
        val todo = eligible.filter { m ->
            val st = stamps[m.id]
            st == null || st.modified != m.modified || st.size != m.size || st.version != LibraryBuilder.FEATURE_VERSION
        }
        val total = todo.size + eligible.size
        var done = 0
        onProgress(0, total)
        for (chunk in todo.chunked(8)) {
            ensureActive()
            db.putFeatures(chunk.map { m -> async(decodeDispatcher) { m to features(m) } }.awaitAll(), LibraryBuilder.FEATURE_VERSION)
            done += chunk.size
            onProgress(done, total)
        }
        val stored = db.loadAll()
        val work = eligible.filter { m -> stored[m.id]?.let { it.features != null && (it.faces == null || it.aesthetic == null) } == true }
        done += eligible.size - work.size
        onProgress(done, total)
        if (work.isEmpty()) return@coroutineScope
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build(),
        )
        val scorer = AestheticScorer.create(ctx)
        try {
            for (chunk in work.chunked(4)) {
                ensureActive()
                val rows = chunk.map { m ->
                    async(Dispatchers.IO) {
                        val sf = stored[m.id]
                        val bmp = try { loadBitmap(m, 720) } catch (e: Exception) { null }
                        val face = if (sf?.faces == null) bmp?.let { detectFaces(detector, it) } ?: FaceInfo(0, -1f, -1f) else null
                        val aes = if (sf?.aesthetic == null && scorer != null) {
                            bmp?.let { b -> runCatching { synchronized(scorer) { scorer.score(b) } }.getOrNull() } ?: -1f
                        } else null
                        bmp?.recycle()
                        Triple(m.id, face, aes)
                    }
                }.awaitAll()
                db.putFaces(rows.mapNotNull { (id, face, _) -> face?.let { id to it } })
                db.putAesthetic(rows.mapNotNull { (id, _, aes) -> aes?.let { id to it } })
                done += chunk.size
                onProgress(done, total)
            }
        } finally {
            detector.close()
            scorer?.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val videoDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * 从原图解码一个最长边约 [px] 的版本。
     * 不用系统缩略图：荣耀等系统给第三方 App 的缩略图常常只有两三百像素，放大后一定显得糊，
     * 会把好照片误判成模糊、美感分也不准。原图解码时按比例缩小（JPEG 可以直接按 1/2、1/4 解码），并不慢。
     */
    private fun loadBitmap(m: MediaItem, px: Int): Bitmap {
        val bmp = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, m.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val s = px.toFloat() / max(w, h)
                if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        } catch (e: Exception) {
            // 个别格式解不了时退回系统缩略图
            ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null)
        }
        return software(bmp)
    }

    private fun software(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() } else bmp

    /** 一张图缩到分析尺寸，算特征 */
    private fun metrics(src: Bitmap): ImageFeatures {
        val scale = ImageMetrics.ANALYSIS_SIZE.toFloat() / max(src.width, src.height)
        val w = max(9, (src.width * scale).roundToInt())
        val h = max(9, (src.height * scale).roundToInt())
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()
        return ImageMetrics.compute(px, w, h)
    }

    private fun features(m: MediaItem): ImageFeatures? = try {
        val src = loadBitmap(m, 640)
        val tooSmall = max(src.width, src.height) < MIN_THUMB
        val f = metrics(src)
        src.recycle()
        // 系统给的缩略图太小时，放大到 512 一定显得糊：指纹和颜色照用，模糊判断跳过
        if (tooSmall) f.withoutSharpness() else f
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** 照片 EXIF 里的 GPS；要原始文件（系统默认会把位置信息抹掉） */
    private fun readLocation(m: MediaItem): Pair<Double, Double>? = try {
        ctx.contentResolver.openInputStream(MediaStore.setRequireOriginal(m.uri))?.use { s ->
            ExifInterface(s).latLong?.let { (lat, lng) -> if (lat == 0.0 && lng == 0.0) null else lat to lng }
        }
    } catch (e: Exception) {
        null
    }

    /** 视频在 10%、30%、50%、70%、90% 处各取一帧（取最近的关键帧，快） */
    private fun videoFeatures(m: MediaItem, scorer: AestheticScorer?): VideoFeatures? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, m.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: m.durationMs
            if (dur <= 0) return null
            val points = if (dur < 3000) listOf(0.5) else listOf(0.1, 0.3, 0.5, 0.7, 0.9)
            var aes: Float? = null
            val mid = points.size / 2
            val frames = points.mapIndexedNotNull { i, p ->
                runCatching {
                    r.getScaledFrameAtTime((dur * p * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                }.getOrNull()?.let { b ->
                    software(b).let { s ->
                        if (i == mid && scorer != null) aes = runCatching { synchronized(scorer) { scorer.score(s) } }.getOrNull()
                        metrics(s).also { s.recycle() }
                    }
                }
            }
            if (frames.isEmpty()) return null
            val reasons = frames.map { BadShotRules.reasons(it, 1) }
            VideoFeatures(
                frames = frames.size,
                darkFrames = reasons.count { BadReason.DARK in it },
                plainFrames = reasons.count { BadReason.PLAIN in it },
                blurryFrames = reasons.count { BadReason.BLURRY in it || BadReason.MAYBE_BLURRY in it },
                hashes = LongArray(frames.size) { frames[it].dHash },
                color = frames[frames.size / 2].color,
                aesthetic = aes,
            )
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun detectFaces(detector: com.google.mlkit.vision.face.FaceDetector, bmp: Bitmap): FaceInfo = try {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        val minW = bmp.width * 0.06f
        val big = faces.filter { it.boundingBox.width() >= minW }
        var eyes = 2f
        var smileSum = 0f
        var smileN = 0
        for (f in big) {
            val l = f.leftEyeOpenProbability
            val r = f.rightEyeOpenProbability
            if (l != null && r != null) eyes = min(eyes, min(l, r))
            f.smilingProbability?.let { smileSum += it; smileN++ }
        }
        FaceInfo(big.size, if (eyes > 1f) -1f else eyes, if (smileN == 0) -1f else smileSum / smileN)
    } catch (e: Exception) {
        FaceInfo(0, -1f, -1f)
    }

    companion object {
        private const val PARALLEL = 4
        private const val MIN_THUMB = 448
        /** 识字/视频分析的规则变了就 +1，旧结果会重新分析 */
        /** v2：记下字块排布（认聊天记录），证件号打码。v3：只数汉字字块、左右交替、字占画面多大 */
        /** 截图和 App 存的图：5 起记下每个字块的位置和微信绿气泡（认聊天记录） */
        const val TEXT_VERSION = 5
        /** 相机照片只分证件、票据、文件，用不到字块位置，不用为了它重识 */
        const val CAMERA_TEXT_VERSION = 3

        fun textVersion(m: MediaItem) = if (m.isCameraShot) CAMERA_TEXT_VERSION else TEXT_VERSION
        /** v2：加了中间那帧的美感分 */
        const val VIDEO_VERSION = 2
    }
}
