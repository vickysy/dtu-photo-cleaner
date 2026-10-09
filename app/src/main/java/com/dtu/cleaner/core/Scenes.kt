package com.dtu.cleaner.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * “场景精选”：一次出游/一个地方连续拍了几十张、构图各不相同，只想留几张精华。
 *
 * 1. 按拍摄时间切场景：相邻两张间隔超过 [SceneDetector.GAP_MS] 就算新场景；张数够多的才处理。
 * 2. 给场景里每张照片打分：美感（构图色彩）为主，曝光、有没有人闭眼为辅，拍糊的直接垫底。
 * 3. 挑精华时兼顾“好”和“不重复”（MMR）：已经挑了一张某个画面，和它很像的就降权，
 *    这样挑出来的几张覆盖不同的画面，而不是五张几乎一样的。
 */
class Scene(
    val key: Long,
    /** 按时间排序 */
    val members: List<PhotoInfo>,
    /** 推荐保留的照片 id（按推荐顺序） */
    val highlights: List<Long>,
    val keepCount: Int,
) {
    val startTime: Long get() = members.first().timeMs
    val endTime: Long get() = members.last().timeMs
}

object SceneDetector {
    /** 相邻两张超过 20 分钟没拍，就算换了场景 */
    const val GAP_MS = 20 * 60_000L
    /** 少于这么多张不算“拍太多”，交给“相似照片”处理 */
    const val MIN_PHOTOS = 10
    /** 相邻两张拍摄地点隔了这么远（米），就算换了地方，哪怕时间挨着（比如开车路过好几个景点） */
    const val MOVE_METERS = 1500.0

    /** 两点之间的距离（米），球面近似 */
    fun distanceMeters(a: PhotoInfo, b: PhotoInfo): Double {
        if (!a.hasLocation || !b.hasLocation) return 0.0
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val x = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(a.lat)) * kotlin.math.cos(Math.toRadians(b.lat)) * kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(x.coerceIn(0.0, 1.0)))
    }

    fun segment(photos: List<PhotoInfo>, gapMs: Long = GAP_MS, minPhotos: Int = MIN_PHOTOS): List<List<PhotoInfo>> {
        if (photos.isEmpty()) return emptyList()
        val sorted = photos.sortedBy { it.timeMs }
        val out = ArrayList<List<PhotoInfo>>()
        var cur = ArrayList<PhotoInfo>()
        for (p in sorted) {
            // 隔太久，或者两张都有地点而且离得远：换场景
            if (cur.isNotEmpty() && (p.timeMs - cur.last().timeMs > gapMs || distanceMeters(cur.last(), p) > MOVE_METERS)) {
                if (cur.size >= minPhotos) out.add(cur)
                cur = ArrayList()
            }
            cur.add(p)
        }
        if (cur.size >= minPhotos) out.add(cur)
        return out
    }

    /** 默认保留几张：十几张留 3 张，二三十张留 4 张，更多留 5 张 */
    fun defaultKeep(n: Int): Int = when {
        n < 20 -> 3
        n < 40 -> 4
        else -> 5
    }

    fun build(members: List<PhotoInfo>, keep: Int = defaultKeep(members.size), taste: Taste = Taste.NORMAL): Scene {
        val sorted = members.sortedBy { it.timeMs }
        return Scene(sorted.first().id, sorted, pick(sorted, keep, taste).map { it.id }, keep)
    }

    /** 单张照片在场景里的质量分 0..1 */
    fun quality(p: PhotoInfo, aesMean: Float, aesStd: Float, taste: Taste = Taste.NORMAL): Float {
        val aesN = if (p.aestheticKnown && aesStd > 0f) (0.5f + 0.22f * (p.aesthetic!! - aesMean) / aesStd).coerceIn(0f, 1f) else 0.5f
        val expo = Ranker.exposureScore(p.features)
        val fi = p.faces
        val face = when {
            fi == null || fi.count == 0 -> 0.6f
            fi.minEyesOpen in 0f..0.35f -> 0.1f // 有人闭眼
            else -> 0.7f + 0.3f * max(0f, fi.avgSmile)
        }
        // 默认美感占 0.62；用户更看重好看/清楚时跟着调，其余按 6:4 分给曝光和人脸
        val aesW = (0.62f * taste.aestheticWeight / Taste.DEFAULT).coerceIn(0.35f, 0.8f)
        val rest = 1f - aesW
        var q = aesW * aesN + rest * 0.6f * expo + rest * 0.4f * face
        when (BadShotRules.blurLevel(p.features, 1)) {
            2 -> q *= 0.15f
            1 -> q *= 0.5f
        }
        return q
    }

    /** 两张照片有多像（0..1）：画面结构、颜色、拍摄时间都算 */
    fun similarity(a: PhotoInfo, b: PhotoInfo, sceneSpanMs: Long): Float {
        // 同一处换个角度拍，构图指纹会差不少，但颜色相近、时间相邻；所以颜色和时间的权重不能低
        val h = Similarity.hashDistance(a.features, b.features)
        val simHash = (1f - h / 64f).coerceIn(0f, 1f)
        val simColor = (1f - Similarity.colorDistance(a.features.color, b.features.color) / 0.2f).coerceIn(0f, 1f)
        val tau = max(180_000f, sceneSpanMs * 0.1f)
        val simTime = exp(-abs(a.timeMs - b.timeMs) / tau)
        return 0.35f * simHash + 0.35f * simColor + 0.3f * simTime
    }

    /** 挑 [k] 张：每次选“质量高、又和已选的不像”的那张；收藏的照片一律保留 */
    fun pick(members: List<PhotoInfo>, k: Int, taste: Taste = Taste.NORMAL): List<PhotoInfo> {
        if (members.isEmpty()) return emptyList()
        val known = members.filter { it.aestheticKnown }.map { it.aesthetic!! }
        val mean = if (known.isEmpty()) 0f else known.average().toFloat()
        val std = if (known.size < 2) 0f else sqrt(known.map { (it - mean) * (it - mean) }.average().toFloat())
        val q = members.associate { it.id to quality(it, mean, std, taste) }
        val span = members.maxOf { it.timeMs } - members.minOf { it.timeMs }

        val picked = ArrayList<PhotoInfo>()
        members.filter { it.favorite }.forEach { picked.add(it) }
        val target = max(k, picked.size)
        while (picked.size < min(target, members.size)) {
            var best: PhotoInfo? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (m in members) {
                if (m in picked) continue
                val redundancy = picked.maxOfOrNull { similarity(m, it, span) } ?: 0f
                val s = q.getValue(m.id) - LAMBDA * redundancy
                if (s > bestScore) { bestScore = s; best = m }
            }
            picked.add(best ?: break)
        }
        return picked
    }

    /** 不重复的权重：越大越倾向于挑不同画面 */
    private const val LAMBDA = 0.8f
}
