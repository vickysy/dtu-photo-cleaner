package com.dtu.cleaner.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 人脸检测结果。[minEyesOpen]/[avgSmile] 为 -1 表示没法判断。 */
class FaceInfo(val count: Int, val minEyesOpen: Float, val avgSmile: Float)

class PhotoInfo(
    val id: Long,
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val favorite: Boolean,
    val features: ImageFeatures,
    /** null = 还没做人脸分析 */
    val faces: FaceInfo?,
    /** 美感分 1..10（NIMA 模型）；null 或 ≤0 = 不知道 */
    val aesthetic: Float? = null,
    /** 照片里识别出的文字的指纹（[ScreenText.signature]）；null = 没识别过 */
    val textSig: IntArray? = null,
    /** 拍摄地点（照片 EXIF 里的 GPS）；NaN = 不知道 */
    val lat: Double = Double.NaN,
    val lng: Double = Double.NaN,
) {
    val aestheticKnown: Boolean get() = aesthetic != null && aesthetic > 0f
    val hasLocation: Boolean get() = !lat.isNaN() && !lng.isNaN()
}

data class GroupParams(
    /** 相邻两张照片间隔超过这个时间就不算“同一时期”。 */
    val maxGapMs: Long = 120_000,
    /** 指纹差异 ≤ 这个值：几乎肯定是同一画面。 */
    val strictBits: Int = 14,
    /** 指纹差异 ≤ 这个值且颜色也接近：同一场景换了点角度。 */
    val looseBits: Int = 30,
    /** 颜色差异上限（0..1）。 */
    val maxColorDist: Float = 0.06f,
    /** 每组建议保留几张。 */
    val keepCount: Int = 1,
) {
    companion object {
        /** 0 严格 / 1 标准 / 2 宽松 */
        fun forStrictness(level: Int, gapSec: Int, keep: Int) = when (level) {
            0 -> GroupParams(gapSec * 1000L, 10, 22, 0.045f, keep)
            2 -> GroupParams(gapSec * 1000L, 18, 38, 0.08f, keep)
            else -> GroupParams(gapSec * 1000L, 14, 30, 0.06f, keep)
        }
    }
}

object Similarity {
    fun hashDistance(a: ImageFeatures, b: ImageFeatures): Int =
        java.lang.Long.bitCount(a.dHash xor b.dHash) + java.lang.Long.bitCount(a.vHash xor b.vHash)

    fun colorDistance(a: ByteArray, b: ByteArray): Float {
        var s = 0
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s / (a.size * 255f)
    }

    fun isSimilar(a: ImageFeatures, b: ImageFeatures, p: GroupParams): Boolean {
        val h = hashDistance(a, b)
        if (h > p.looseBits) return false
        val c = colorDistance(a.color, b.color)
        return if (h <= p.strictBits) c <= p.maxColorDist * 2 else c <= p.maxColorDist
    }

    fun sameAspect(a: PhotoInfo, b: PhotoInfo): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        val ra = max(a.width, a.height).toFloat() / min(a.width, a.height)
        val rb = max(b.width, b.height).toFloat() / min(b.width, b.height)
        return abs(ra - rb) < 0.02f
    }

    /** 内容完全一样（重复保存、微信转存的同一张）。 */
    fun isDuplicate(a: PhotoInfo, b: PhotoInfo): Boolean =
        hashDistance(a.features, b.features) <= 3 &&
            colorDistance(a.features.color, b.features.color) <= 0.02f &&
            sameAspect(a, b)
}

object Grouper {
    /**
     * 把照片分成“同一时期同一场景”的组，再把跨时间的完全重复照片并进来。
     * 只返回 ≥ 2 张的组，组内按时间排序。
     */
    fun group(photos: List<PhotoInfo>, p: GroupParams): List<List<PhotoInfo>> {
        if (photos.size < 2) return emptyList()
        val sorted = photos.sortedBy { it.timeMs }
        val parent = IntArray(sorted.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        // 1) 时间窗口内的相似照片。每个“活跃组”记住最近几张成员。
        class Active(val anchor: Int, val recent: ArrayDeque<Int>, var lastTime: Long)
        val active = ArrayList<Active>()
        for (i in sorted.indices) {
            val ph = sorted[i]
            active.removeAll { ph.timeMs - it.lastTime > p.maxGapMs }
            var best: Active? = null
            var bestD = Int.MAX_VALUE
            for (g in active) {
                // 防止“一路平移”串成一大组：和组里第一张也不能差太远
                if (Similarity.hashDistance(sorted[g.anchor].features, ph.features) > p.looseBits + 10) continue
                for (m in g.recent) {
                    val o = sorted[m]
                    // 两页不同的文件、两张不同的 PPT：画面像，但字对不上，不算同一组
                    if (Similarity.isSimilar(o.features, ph.features, p) && !ScreenText.differentPages(o.textSig, ph.textSig)) {
                        val d = Similarity.hashDistance(o.features, ph.features)
                        if (d < bestD) { bestD = d; best = g }
                    }
                }
            }
            if (best != null) {
                union(i, best.anchor)
                best.recent.addLast(i)
                if (best.recent.size > 6) best.recent.removeFirst()
                best.lastTime = ph.timeMs
            } else {
                active.add(Active(i, ArrayDeque(listOf(i)), ph.timeMs))
            }
        }

        // 2) 跨时间的完全重复：按 dHash 的 4 段 16 位分桶找候选（距离 ≤3 必有一段完全相同）
        val buckets = HashMap<Long, MutableList<Int>>()
        for (i in sorted.indices) {
            val h = sorted[i].features.dHash
            for (k in 0 until 4) {
                val chunk = (h ushr (k * 16)) and 0xFFFF
                buckets.getOrPut((k.toLong() shl 16) or chunk) { ArrayList(2) }.add(i)
            }
        }
        for (list in buckets.values) {
            if (list.size < 2 || list.size > 300) continue // 超大桶多半是纯色/天空，不值得两两比
            for (x in 0 until list.size) for (y in x + 1 until list.size) {
                val a = list[x]
                val b = list[y]
                if (find(a) != find(b) && Similarity.isDuplicate(sorted[a], sorted[b])) union(a, b)
            }
        }

        val comps = LinkedHashMap<Int, MutableList<PhotoInfo>>()
        for (i in sorted.indices) comps.getOrPut(find(i)) { ArrayList() }.add(sorted[i])
        return comps.values.filter { it.size >= 2 }.map { g -> g.sortedBy { it.timeMs } }
    }
}

class RankedPhoto(
    val photo: PhotoInfo,
    /** 0..100 */
    val score: Int,
    val keep: Boolean,
    val best: Boolean,
    val tags: List<String>,
    /** 技术分 0..1（清晰、曝光、人脸），用来学用户的口味 */
    val tech: Float = 0f,
)

/**
 * 用户的口味：挑照片时美感（构图色彩）占多大比重。默认 0.35，其余看清晰、曝光、有没有人闭眼。
 * 从用户改过的推荐里学：留下的那张美感高、技术分低，说明更看重好看；反过来说明更看重清楚。
 */
class Taste(val aestheticWeight: Float = DEFAULT) {
    companion object {
        const val DEFAULT = 0.35f
        val NORMAL = Taste()

        /** 投票数 → 权重。票少时几乎不动，最多在 0.15..0.6 之间 */
        fun from(aesVotes: Int, techVotes: Int): Taste {
            val total = aesVotes + techVotes
            if (total == 0) return NORMAL
            val lean = (aesVotes - techVotes).toFloat() / (total + 6)
            return Taste((DEFAULT + 0.3f * lean).coerceIn(0.15f, 0.6f))
        }
    }
}

class RankedGroup(
    /** 用组里最早一张的 id 当组 id */
    val key: Long,
    val members: List<RankedPhoto>,
    val duplicate: Boolean,
    /**
     * 组里每张都和推荐那张“几乎一样”（连拍、重复保存）：这类识别很可靠，可以放心一键清理。
     * false = 同一场景但换了角度/构图，最容易判断错（两页不同的文件、不同的人），要用户看一眼。
     */
    val nearIdentical: Boolean = false,
) {
    val startTime: Long get() = members.minOf { it.photo.timeMs }
    val suggestedDelete: List<RankedPhoto> get() = members.filter { !it.keep }

    /** 有几张拍得差不多、机器很难分高下（仍给出了推荐） */
    val closeCall: Boolean get() = members.any { CLOSE_TAG in it.tags }

    companion object {
        const val CLOSE_TAG = "差不多"
    }
}

object Ranker {
    fun exposureScore(f: ImageFeatures): Float {
        var e = 1f
        e -= min(1f, f.brightFrac * 3f) * 0.6f
        e -= min(1f, f.darkFrac * 2f) * 0.4f
        if (f.mean < 50f) e -= (50f - f.mean) / 50f * 0.4f
        if (f.mean > 215f) e -= (f.mean - 215f) / 40f * 0.4f
        return e.coerceIn(0f, 1f)
    }

    private fun faceScore(fi: FaceInfo?, maxFaces: Int): Float {
        if (fi == null) return 0.5f
        val countRatio = if (maxFaces == 0) 1f else fi.count.toFloat() / maxFaces
        val eyes = if (fi.minEyesOpen < 0) 0.5f else fi.minEyesOpen
        val smile = if (fi.avgSmile < 0) 0.3f else fi.avgSmile
        return 0.4f * countRatio + 0.45f * eyes + 0.15f * smile
    }

    /** 清晰度差到最佳那张的 e^-1.5（约 22%）以下记 0 分。 */
    private const val SHARP_SCALE = 1.5f

    /**
     * 两张照片的指纹差异 ≤ 这个值，就算“几乎同一个取景”（连拍、手稍微动了一下）。
     * 只有这种情况下比“谁细节多”才等于比“谁更清楚”；取景不同时，杂乱的前景会被误当成细节。
     */
    const val SAME_FRAMING_BITS = 10

    /** 美感分（1..10）差不到这么多，就算“差不多”（仍然推荐分高的那张，只是提醒用户） */
    const val AESTHETIC_MARGIN = 0.15f

    fun sameFraming(a: PhotoInfo, b: PhotoInfo): Boolean =
        Similarity.hashDistance(a.features, b.features) <= SAME_FRAMING_BITS

    fun rank(group: List<PhotoInfo>, keepCount: Int, taste: Taste = Taste.NORMAL): RankedGroup {
        val members = group.sortedBy { it.timeMs }
        val n = members.size
        val pixels = members.map { (it.width.toLong() * it.height).coerceAtLeast(1) }
        val maxPixels = pixels.max()
        val resSpread = maxPixels.toFloat() / pixels.min()
        val maxSize = members.maxOf { it.size }.coerceAtLeast(1)
        val maxFaces = members.maxOf { it.faces?.count ?: 0 }

        // 清晰度 = 相对清晰度（只和取景几乎一样的照片比） × 绝对清晰度（本身是不是糊的，和“废片”同一套标准）
        val sharpLog = members.map { ln(1f + max(0f, it.features.sharpness)) }
        val relSharp = FloatArray(n) { i ->
            var top = sharpLog[i]
            for (j in 0 until n) if (j != i && sameFraming(members[i], members[j])) top = max(top, sharpLog[j])
            ((sharpLog[i] - top) / SHARP_SCALE + 1f).coerceIn(0f, 1f)
        }
        val absSharp = FloatArray(n) { i ->
            when (BadShotRules.blurLevel(members[i].features, 1)) {
                2 -> 0.15f
                1 -> 0.6f
                else -> 1f
            }
        }
        // 技术分：清不清楚、曝光、有没有人闭眼
        val tech = FloatArray(n) { i ->
            val m = members[i]
            val sharpN = relSharp[i] * absSharp[i]
            val expo = exposureScore(m.features)
            if (maxFaces > 0) 0.55f * sharpN + 0.15f * expo + 0.30f * faceScore(m.faces, maxFaces)
            else 0.80f * sharpN + 0.20f * expo
        }
        // 美感分：构图、色彩。个别照片没评上分时，按组里其他照片的平均分算
        val known = members.filter { it.aestheticKnown }.map { it.aesthetic!! }
        val aesKnown = known.size >= 2
        val aes = FloatArray(n) { i -> if (members[i].aestheticKnown) members[i].aesthetic!! else if (known.isEmpty()) 0f else known.average().toFloat() }
        val aesMax = if (aesKnown) aes.max() else 0f

        val scores = FloatArray(n) { i ->
            val m = members[i]
            val w = taste.aestheticWeight
            val quality = if (aesKnown) (1f - w) * tech[i] + w * ((aes[i] - aesMax) + 1f).coerceIn(0f, 1f) else tech[i]
            val res = pixels[i].toFloat() / maxPixels
            val sizeR = m.size.toFloat() / maxSize
            // 分辨率差别大（原图 vs 微信压缩副本）时，原图优先；否则主要看画质
            val s = if (resSpread > 1.3f) 0.6f * quality + 0.4f * res
            else 0.92f * quality + 0.05f * res + 0.03f * sizeR
            s - i * 0.0001f // 完全一样时留最早那张
        }

        val order = members.indices.sortedByDescending { scores[it] }
        val keepIdx = order.take(keepCount.coerceAtLeast(1)).toMutableSet()
        members.forEachIndexed { i, m -> if (m.favorite) keepIdx.add(i) }
        val bestIdx = order.first()
        val best = members[bestIdx]
        val dupOfBest = BooleanArray(n) { i -> i != bestIdx && Similarity.isDuplicate(members[i], best) && sameQuality(members[i], best) }
        val duplicate = members.indices.all { it == bestIdx || dupOfBest[it] }

        // “差不多”：取景不同、都不糊、技术分接近，美感分也分不出高下。
        // 真实相册里这种组绝大多数是同一场景随手多拍了几张，留哪张都行，所以照样给出推荐，只打个标签提醒。
        val close = BooleanArray(n) { i ->
            i !in keepIdx && !dupOfBest[i] && !sameFraming(members[i], best) &&
                absSharp[i] == 1f && absSharp[bestIdx] == 1f &&
                tech[i] >= tech[bestIdx] - 0.12f &&
                (!aesKnown || abs(aes[i] - aes[bestIdx]) < AESTHETIC_MARGIN)
        }

        val ranked = members.mapIndexed { i, m ->
            val tags = ArrayList<String>()
            val f = m.features
            if (i == bestIdx) tags.add(if (duplicate) "保留原图" else "最佳")
            if (m.favorite) tags.add("已收藏")
            if (i != bestIdx) {
                if (dupOfBest[i]) {
                    tags.add(if (pixels[i] < pixels[bestIdx] * 0.77f) "压缩副本" else "重复")
                } else if (close[i]) {
                    tags.add(RankedGroup.CLOSE_TAG)
                } else {
                    when {
                        absSharp[i] <= 0.15f -> tags.add("模糊")
                        absSharp[i] < 1f -> tags.add("可能模糊")
                        relSharp[i] < 0.73f -> tags.add("更模糊") // 和取景几乎一样的那张比，细节少了 1/3 以上
                    }
                    val fi = m.faces
                    if (fi != null && fi.count > 0 && fi.minEyesOpen in 0f..0.35f) tags.add("有人闭眼")
                    if (fi != null && maxFaces > 0 && fi.count < maxFaces) tags.add("少了人")
                    if (f.brightFrac > 0.15f) tags.add("过曝")
                    if (f.mean < 45f) tags.add("偏暗")
                    if (pixels[i] < pixels[bestIdx] * 0.77f) tags.add("分辨率低")
                    if (aesKnown && !sameFraming(m, best) && aes[bestIdx] - aes[i] >= AESTHETIC_MARGIN) tags.add("没那张好看")
                    if (tags.isEmpty()) tags.add("相似")
                }
            }
            RankedPhoto(m, (scores[i].coerceIn(0f, 1f) * 100).roundToInt(), i in keepIdx, i == bestIdx, tags, tech[i])
        }
        val nearIdentical = members.indices.all { it == bestIdx || dupOfBest[it] || sameFraming(members[it], best) }
        return RankedGroup(members.first().id, ranked, duplicate, nearIdentical)
    }

    /** 同一分辨率下清晰度也几乎一样 → 真的是同一个文件的拷贝，而不是连拍里的另一张。 */
    private fun sameQuality(a: PhotoInfo, b: PhotoInfo): Boolean {
        val pa = a.width.toLong() * a.height
        val pb = b.width.toLong() * b.height
        if (pa > 0 && pb > 0 && max(pa, pb).toFloat() / min(pa, pb) > 1.3f) return true
        val d = abs(ln(1f + max(0f, a.features.sharpness)) - ln(1f + max(0f, b.features.sharpness)))
        val fa = a.faces
        val fb = b.faces
        if (fa != null && fb != null && (fa.count != fb.count || abs(fa.minEyesOpen - fb.minEyesOpen) > 0.3f)) return false
        return d < 0.12f
    }
}

enum class BadReason(val label: String) {
    BLURRY("模糊"),
    /** 有点像糊了但拿不准：列出来，但不默认勾选 */
    MAYBE_BLURRY("可能模糊"),
    DARK("太暗"),
    OVEREXPOSED("过曝"),
    PLAIN("误拍"),
}

object BadShotRules {
    /**
     * 模糊阈值（主体清晰度，从原图解码到 512px 后计算），分 低 / 中 / 高 三档灵敏度。
     * 按一部荣耀手机上 5688 张真实相机照片校准：真正拍废的（手指挡镜头、完全没对上焦）都在 35 以下，
     * 而云、天空、夕阳、食物、睡着的人这类“画面本来就平滑”的好照片从 40 起步，所以阈值必须很低。
     * 低于阈值的 60% 才默认勾选，其余只列为“可能模糊”让用户自己看。宁可漏掉一些，也不误伤好照片。
     */
    /** 画面里有明显清楚的主体（典型的背景虚化）时 */
    val BLUR_WITH_SUBJECT = floatArrayOf(20f, 30f, 45f)
    /** 整张照片清晰程度差不多（没有虚化）时 */
    val BLUR_UNIFORM = floatArrayOf(30f, 45f, 70f)
    /** 最清楚的区块比中位数高这么多倍，就认为是“主体清楚 + 背景虚化” */
    const val SUBJECT_RATIO = 10f
    /** 最弱方向不到最强方向的 5%：可能是手抖拖出来的模糊（只做提示） */
    const val MOTION_ANISOTROPY = 0.05f

    /** 纯色/太暗的照片不参与相似分组，直接归到废片里。 */
    fun excludedFromGrouping(f: ImageFeatures): Boolean = f.std < 9f || (f.mean < 30f && f.p95 < 70f)

    fun hasBokeh(f: ImageFeatures): Boolean = f.focusRatio >= SUBJECT_RATIO

    fun isBlurry(f: ImageFeatures, sensitivity: Int): Boolean = blurLevel(f, sensitivity) > 0

    /** 0 = 不模糊，1 = 可能模糊（不默认勾选），2 = 明显模糊（默认勾选） */
    fun blurLevel(f: ImageFeatures, sensitivity: Int): Int {
        if (f.peakSharpness < 0f) return 0 // 缩略图太小，没法判断
        val lv = sensitivity.coerceIn(0, 2)
        val threshold = if (hasBokeh(f)) BLUR_WITH_SUBJECT[lv] else BLUR_UNIFORM[lv]
        return when {
            f.peakSharpness < threshold * 0.6f -> 2
            f.peakSharpness < threshold -> 1
            // 手抖只做提示：百叶窗、大桥、公路这类线条很多的照片也会“某个方向没细节”，真实照片里误伤很多
            f.anisotropy < MOTION_ANISOTROPY && f.peakSharpness < BLUR_UNIFORM[lv] * 3f -> 1
            else -> 0
        }
    }

    fun reasons(f: ImageFeatures, sensitivity: Int): List<BadReason> {
        val out = ArrayList<BadReason>(2)
        val plain = f.std < 9f
        if (plain) out.add(BadReason.PLAIN)
        if (f.mean < 30f && f.p95 < 70f) out.add(BadReason.DARK)
        if (f.mean > 232f && f.brightFrac > 0.55f) out.add(BadReason.OVEREXPOSED)
        // 纯色/全黑的画面本来就没有细节，不再重复报“模糊”
        if (!plain && out.isEmpty()) {
            when (blurLevel(f, sensitivity)) {
                2 -> out.add(BadReason.BLURRY)
                1 -> out.add(BadReason.MAYBE_BLURRY)
            }
        }
        return out
    }
}
