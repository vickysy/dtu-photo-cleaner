package com.dtu.cleaner.core

import kotlin.math.abs
import kotlin.math.ceil

/**
 * 一个视频抽几帧（10%、30%、50%、70%、90% 处）算出来的特征。
 * 每一帧都用和照片一样的方法（[ImageMetrics]）算，所以“暗、纯色、模糊”的标准和照片一致。
 */
class VideoFeatures(
    /** 抽到的帧数（解码失败的不算） */
    val frames: Int,
    /** 黑乎乎的帧（镜头被挡、晚上口袋里） */
    val darkFrames: Int,
    /** 几乎纯色的帧（对着墙、地面、口袋） */
    val plainFrames: Int,
    /** 模糊的帧 */
    val blurryFrames: Int,
    /** 每帧的画面指纹 */
    val hashes: LongArray,
    /** 中间那帧的颜色分布 */
    val color: ByteArray,
    /** 中间那帧的美感分 1..10；null = 没评 */
    val aesthetic: Float? = null,
)

enum class VideoReason(val label: String, val confident: Boolean) {
    DARK("黑屏或挡住了", true),
    PLAIN("误录：没拍到东西", true),
    DUPLICATE("重复保存", true),
    SHORT("不到 3 秒", true),
    BLURRY("可能全程模糊", false),
    TAKES("同一场景拍了好几段", false),
}

class VideoInfo(
    val id: Long,
    val timeMs: Long,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    /** 相机拍的（不是微信存的、剪映导出的） */
    val camera: Boolean,
    val favorite: Boolean,
    val f: VideoFeatures,
)

object VideoRules {
    /** 至少这么多帧都是黑的/纯色的，才算整段没拍到东西 */
    private const val MOSTLY = 0.8f

    private fun mostly(n: Int, total: Int) = total >= 3 && n >= ceil(total * MOSTLY).toInt()

    /** 单个视频自己的问题（重复、多段要和别的视频比，见 [duplicates]、[takes]） */
    fun reasons(v: VideoInfo): List<VideoReason> {
        val f = v.f
        val out = ArrayList<VideoReason>(2)
        when {
            mostly(f.darkFrames, f.frames) -> out.add(VideoReason.DARK)
            mostly(f.plainFrames, f.frames) -> out.add(VideoReason.PLAIN)
            // 每一帧都糊才提示，而且不默认勾选：手机视频为了流畅本来就比照片软
            f.frames >= 3 && f.blurryFrames == f.frames -> out.add(VideoReason.BLURRY)
        }
        return out
    }

    /** 两段视频是不是同一个视频（重复保存、微信转存的压缩版） */
    fun isDuplicate(a: VideoInfo, b: VideoInfo): Boolean {
        if (abs(a.durationMs - b.durationMs) > 700) return false
        val n = minOf(a.f.hashes.size, b.f.hashes.size)
        if (n < 3) return false
        var same = 0
        for (i in 0 until n) if (java.lang.Long.bitCount(a.f.hashes[i] xor b.f.hashes[i]) <= 10) same++
        return same >= n - 1
    }

    /**
     * 找出重复保存的视频：每组留一个（相机原片优先，其次分辨率高、文件大的），其余返回待删。
     * 返回 待删 id → 留下的那个 id。
     */
    fun duplicates(videos: List<VideoInfo>): Map<Long, Long> {
        val sorted = videos.sortedBy { it.durationMs }
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            return r
        }
        for (i in sorted.indices) {
            var j = i + 1
            while (j < sorted.size && sorted[j].durationMs - sorted[i].durationMs <= 700) {
                if (isDuplicate(sorted[i], sorted[j])) parent[find(sorted[j].id)] = find(sorted[i].id)
                j++
            }
        }
        val groups = sorted.groupBy { find(it.id) }.values.filter { it.size >= 2 }
        val out = HashMap<Long, Long>()
        for (g in groups) {
            // 留：收藏的 > 相机原片 > 不糊的 > 分辨率高的 > 文件大的
            val keep = g.maxWith(compareBy<VideoInfo>({ it.favorite }, { it.camera }, { -it.f.blurryFrames }, { it.width.toLong() * it.height }, { it.size }))
            g.filter { it.id != keep.id && !it.favorite }.forEach { out[it.id] = keep.id }
        }
        return out
    }

    /**
     * 同一个地方接连拍了好几段（比如孩子表演录了三四遍）：时间挨着、画面像。
     * 只提示，不帮你挑：哪段最好只有你知道。返回 ≥2 段的组，按时间排。
     */
    fun takes(videos: List<VideoInfo>, maxGapMs: Long = 15 * 60_000L): List<List<VideoInfo>> {
        val sorted = videos.filter { it.camera && it.f.frames > 0 }.sortedBy { it.timeMs }
        val out = ArrayList<List<VideoInfo>>()
        var cur = ArrayList<VideoInfo>()
        for (v in sorted) {
            val prev = cur.lastOrNull()
            if (prev != null && v.timeMs - (prev.timeMs + prev.durationMs) <= maxGapMs && looksAlike(prev, v)) {
                cur.add(v)
            } else {
                if (cur.size >= 2) out.add(cur)
                cur = arrayListOf(v)
            }
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    /** 两段视频拍的是同一个场景：任意两帧画面相近、颜色也接近 */
    fun looksAlike(a: VideoInfo, b: VideoInfo): Boolean {
        val color = Similarity.colorDistance(a.f.color, b.f.color)
        if (color > 0.08f) return false
        var best = 64
        for (x in a.f.hashes) for (y in b.f.hashes) best = minOf(best, java.lang.Long.bitCount(x xor y))
        return best <= 14
    }

    // ---------- 压缩 ----------

    /** 压缩后的目标码率：1080p 约 8 Mbps（H.265），保留 4K 约 20 Mbps */
    fun targetBitrate(keep4k: Boolean, fps: Float): Int {
        val base = if (keep4k) 20_000_000 else 8_000_000
        return if (fps > 45f) (base * 1.5f).toInt() else base
    }

    /** 估计压缩后的大小（字节）：视频按目标码率，加 128 kbps 音频 */
    fun estimateSize(durationMs: Long, sourceBitrate: Long, target: Int): Long {
        val v = minOf(sourceBitrate.takeIf { it > 0 } ?: Long.MAX_VALUE, target.toLong())
        return (v + 128_000L) * durationMs / 8000L
    }

    /** 值得压：能省一半以上，而且至少省 20 MB */
    fun worthCompressing(size: Long, estimated: Long): Boolean = estimated < size / 2 && size - estimated > 20_000_000L
}
