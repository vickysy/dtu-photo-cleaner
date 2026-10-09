package com.dtu.cleaner.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 一个场景里拍的好几段视频；[keep] 是推荐留下的（按推荐顺序） */
class VideoScene(val key: Long, val members: List<VideoInfo>, val keep: List<Long>, val keepCount: Int) {
    val startTime: Long get() = members.first().timeMs
    val endTime: Long get() = members.last().let { it.timeMs + it.durationMs }
    val totalDurationMs: Long get() = members.sumOf { it.durationMs }
}

/**
 * 视频场景精选：同一个地方、前后挨着拍了好几段（孩子表演录了好几遍、演唱会录了十几段）。
 * 和照片不一样，几段视频内容往往不同，所以只“推荐”留哪几段，删不删每个场景由用户自己点。
 *
 * 推荐留下的：时长够、画面清楚、不黑、构图好看（中间那帧的美感分），而且尽量彼此不重复。
 */
object VideoScenes {
    /** 上一段结束到下一段开始超过 20 分钟，就算换了场景 */
    const val GAP_MS = 20 * 60_000L
    /** 一个场景至少这么多段才值得整理 */
    const val MIN_VIDEOS = 4

    fun segment(videos: List<VideoInfo>, gapMs: Long = GAP_MS, min: Int = MIN_VIDEOS): List<List<VideoInfo>> {
        val sorted = videos.filter { it.camera && it.f.frames > 0 }.sortedBy { it.timeMs }
        val out = ArrayList<List<VideoInfo>>()
        var cur = ArrayList<VideoInfo>()
        for (v in sorted) {
            val prev = cur.lastOrNull()
            if (prev != null && v.timeMs - (prev.timeMs + prev.durationMs) > gapMs) {
                if (cur.size >= min) out.add(cur)
                cur = ArrayList()
            }
            cur.add(v)
        }
        if (cur.size >= min) out.add(cur)
        return out
    }

    /** 默认留几段：6 段以内留 2 段，12 段以内留 3 段，更多留 4 段 */
    fun defaultKeep(n: Int): Int = when {
        n <= 6 -> 2
        n <= 12 -> 3
        else -> 4
    }

    /** 单段视频在场景里的质量分 0..1 */
    fun quality(v: VideoInfo, aesMean: Float, aesStd: Float): Float {
        val f = v.f
        val frames = f.frames.coerceAtLeast(1).toFloat()
        // 时长：5 秒以下的多半是没录完或误按，30 秒以上就算够长
        val dur = ((v.durationMs - 3_000f) / 27_000f).coerceIn(0f, 1f)
        val clear = 1f - f.blurryFrames / frames
        val lit = 1f - (f.darkFrames + f.plainFrames) / frames
        val aes = f.aesthetic
        val aesN = if (aes != null && aes > 0f && aesStd > 0f) (0.5f + 0.22f * (aes - aesMean) / aesStd).coerceIn(0f, 1f) else 0.5f
        return (0.3f * dur + 0.2f * clear + 0.4f * aesN + 0.1f * 1f) * (0.3f + 0.7f * lit)
    }

    /** 两段视频有多像 0..1：画面、颜色、拍摄时间 */
    fun similarity(a: VideoInfo, b: VideoInfo, span: Long): Float {
        var best = 64
        for (x in a.f.hashes) for (y in b.f.hashes) best = min(best, java.lang.Long.bitCount(x xor y))
        val hashSim = (1f - best / 32f).coerceIn(0f, 1f)
        val colorSim = (1f - Similarity.colorDistance(a.f.color, b.f.color) / 0.2f).coerceIn(0f, 1f)
        val tau = max(3 * 60_000f, span * 0.1f)
        val timeSim = exp(-kotlin.math.abs(a.timeMs - b.timeMs) / tau)
        return 0.5f * hashSim + 0.2f * colorSim + 0.3f * timeSim
    }

    /** 挑 [k] 段：收藏的先留；其余看“好”和“跟已挑的不重复”（MMR） */
    fun pick(members: List<VideoInfo>, k: Int): List<VideoInfo> {
        if (members.isEmpty()) return emptyList()
        val known = members.mapNotNull { it.f.aesthetic?.takeIf { a -> a > 0f } }
        val mean = if (known.isEmpty()) 0f else known.average().toFloat()
        val std = if (known.size < 2) 0f else sqrt(known.map { (it - mean) * (it - mean) }.average().toFloat())
        val q = members.associate { it.id to quality(it, mean, std) }
        val span = members.maxOf { it.timeMs } - members.minOf { it.timeMs }
        val picked = ArrayList<VideoInfo>()
        members.filter { it.favorite }.forEach { picked.add(it) }
        val target = max(k, picked.size)
        while (picked.size < min(target, members.size)) {
            var best: VideoInfo? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (m in members) {
                if (m in picked) continue
                val redundancy = picked.maxOfOrNull { similarity(m, it, span) } ?: 0f
                val s = q.getValue(m.id) - 0.6f * redundancy
                if (s > bestScore) { bestScore = s; best = m }
            }
            picked.add(best ?: break)
        }
        return picked
    }

    fun build(members: List<VideoInfo>, keep: Int = defaultKeep(members.size)): VideoScene {
        val sorted = members.sortedBy { it.timeMs }
        return VideoScene(sorted.first().id, sorted, pick(sorted, keep).map { it.id }, keep)
    }
}
