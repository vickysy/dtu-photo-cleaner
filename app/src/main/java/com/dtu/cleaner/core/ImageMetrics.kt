package com.dtu.cleaner.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从一张缩小后的图（最长边 [ANALYSIS_SIZE]）算出的特征。
 * 纯 Kotlin，不依赖 Android，方便在电脑上做单元测试和阈值校准。
 */
class ImageFeatures(
    /** 横向差值哈希（9x8 灰度），用来判断“是不是同一个画面”。 */
    val dHash: Long,
    /** 纵向差值哈希（8x9 灰度）。 */
    val vHash: Long,
    /** 4x4 网格 × RGB 平均色，共 48 字节，0..255 存成有符号 byte。 */
    val color: ByteArray,
    /** 清晰度：4 个方向里最弱方向的能量（只看最清晰的 10% 区块）。对焦模糊和手抖都会让它变低。 */
    val sharpness: Float,
    /** 各向同性拉普拉斯能量（同样只看最清晰的区块），仅作参考。 */
    val globalSharpness: Float,
    /** 平均亮度 0..255。 */
    val mean: Float,
    /** 亮度标准差，接近 0 说明画面几乎是纯色（误拍、口袋里拍到的黑屏）。 */
    val std: Float,
    /** 亮度 < 16 的像素占比。 */
    val darkFrac: Float,
    /** 亮度 > 250 的像素占比。 */
    val brightFrac: Float,
    /** 亮度的 95 分位数。 */
    val p95: Float,
    /**
     * 主体清晰度：画面里最清楚的 3 个区块（约 5% 面积）的细节能量。
     * 背景虚化的照片只要主体清楚，这个值就高。偏暗、发灰的照片会按明暗范围往上补偿（只补不压）。
     */
    val peakSharpness: Float,
    /** 最清楚的区块比全图中位数高多少倍。≥10 基本就是“主体清楚 + 背景虚化”。 */
    val focusRatio: Float,
    /** 最清楚那些区块里，最弱方向 / 最强方向 的细节能量。手抖时某个方向几乎为 0。 */
    val anisotropy: Float,
) {
    /** 清晰度不可信时（缩略图太小）用：peakSharpness = -1 表示“不判断模糊” */
    fun withoutSharpness() = ImageFeatures(
        dHash, vHash, color, sharpness, globalSharpness, mean, std, darkFrac, brightFrac, p95,
        peakSharpness = -1f, focusRatio = 0f, anisotropy = 1f,
    )
}

object ImageMetrics {
    const val ANALYSIS_SIZE = 512
    private const val TILES = 8
    private const val TOP_TILES = 6
    private const val PEAK_TILES = 3

    /** [argb] 为 w*h 个 0xAARRGGBB 像素。 */
    fun compute(argb: IntArray, w: Int, h: Int): ImageFeatures {
        require(w >= 9 && h >= 9 && argb.size >= w * h) { "image too small: ${w}x$h" }
        val n = w * h
        val gray = IntArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = y
            hist[y]++
        }

        var sum = 0.0
        var sumSq = 0.0
        for (v in 0 until 256) {
            sum += v.toDouble() * hist[v]
            sumSq += v.toDouble() * v * hist[v]
        }
        val mean = sum / n
        val std = sqrt(max(0.0, sumSq / n - mean * mean))
        var dark = 0
        for (v in 0 until 16) dark += hist[v]
        var bright = 0
        for (v in 251 until 256) bright += hist[v]
        val p5 = percentile(hist, n, 0.05)
        val p95 = percentile(hist, n, 0.95)

        val small9x8 = areaResize(gray, w, h, 9, 8)
        var dHash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            dHash = dHash shl 1
            if (small9x8[y * 9 + x] < small9x8[y * 9 + x + 1] - HASH_EPS) dHash = dHash or 1L
        }
        val small8x9 = areaResize(gray, w, h, 8, 9)
        var vHash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            vHash = vHash shl 1
            if (small8x9[y * 8 + x] < small8x9[(y + 1) * 8 + x] - HASH_EPS) vHash = vHash or 1L
        }

        val color = colorGrid(argb, w, h)
        val sh = laplacian(gray, w, h)
        // 偏暗/发灰的照片细节能量天然低，按明暗范围补偿（范围越窄补得越多，最多 12 倍，从不往下压）
        val range = max(p95 - p5, 40).toFloat()
        val boost = ((180f / range) * (180f / range)).coerceIn(1f, 12f)

        return ImageFeatures(
            dHash = dHash,
            vHash = vHash,
            color = color,
            sharpness = sh.directional,
            globalSharpness = sh.iso,
            mean = mean.toFloat(),
            std = std.toFloat(),
            darkFrac = dark.toFloat() / n,
            brightFrac = bright.toFloat() / n,
            p95 = p95.toFloat(),
            peakSharpness = sh.peak * boost,
            focusRatio = sh.peak / (sh.median + 1f),
            anisotropy = sh.anisotropy,
        )
    }

    private fun percentile(hist: IntArray, n: Int, q: Double): Int {
        val target = (n * q).toInt()
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    private class Sharpness(
        val directional: Float,
        val iso: Float,
        val peak: Float,
        val median: Float,
        val anisotropy: Float,
    )

    /** 相邻格子亮度差不到这个值就当作“一样亮”，避免天空、白墙这种平坦区域因噪点乱翻位。 */
    private const val HASH_EPS = 1.5f

    internal fun areaResize(gray: IntArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        val out = FloatArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = max(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = max(x0 + 1, (tx + 1) * w / tw)
                var s = 0L
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) s += gray[row + x]
                }
                out[ty * tw + tx] = s.toFloat() / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    private fun colorGrid(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(48)
        for (ty in 0 until 4) {
            val y0 = ty * h / 4
            val y1 = max(y0 + 1, (ty + 1) * h / 4)
            for (tx in 0 until 4) {
                val x0 = tx * w / 4
                val x1 = max(x0 + 1, (tx + 1) * w / 4)
                var r = 0L
                var g = 0L
                var b = 0L
                // 隔点采样就够了
                var cnt = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val row = y * w
                    while (x < x1) {
                        val p = argb[row + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        cnt++
                        x += 2
                    }
                    y += 2
                }
                val k = (ty * 4 + tx) * 3
                out[k] = (r / cnt).toInt().toByte()
                out[k + 1] = (g / cnt).toInt().toByte()
                out[k + 2] = (b / cnt).toInt().toByte()
            }
        }
        return out
    }

    /**
     * 对 4 个方向（横、竖、两条对角线）分别求二阶差分能量，按 8x8 区块统计：
     * - directional：每个方向取最清晰的 [TOP_TILES] 个区块，再取最弱方向。用于同组照片之间比谁更清楚。
     * - peak / median：每个区块取 4 个方向的平均，最清楚 3 块的均值和全部区块的中位数。用于判断“废片”：
     *   背景虚化的照片 peak 高、median 很低；真正拍糊的照片 peak 本身就低。
     * - anisotropy：最清楚那些区块里最弱方向 / 最强方向。手抖时某个方向几乎没有细节。
     */
    private fun laplacian(gray: IntArray, w: Int, h: Int): Sharpness {
        val nt = TILES * TILES
        // 5 组累加：横、竖、对角、反对角、各向同性拉普拉斯
        val sum = Array(5) { DoubleArray(nt) }
        val sq = Array(5) { DoubleArray(nt) }
        val cnt = IntArray(nt)
        for (y in 1 until h - 1) {
            val row = y * w
            val ty = min(TILES - 1, y * TILES / h)
            for (x in 1 until w - 1) {
                val i = row + x
                val c2 = 2 * gray[i]
                val dxx = (c2 - gray[i - 1] - gray[i + 1]).toDouble()
                val dyy = (c2 - gray[i - w] - gray[i + w]).toDouble()
                val dd1 = (c2 - gray[i - w - 1] - gray[i + w + 1]).toDouble()
                val dd2 = (c2 - gray[i - w + 1] - gray[i + w - 1]).toDouble()
                val lap = dxx + dyy
                val t = ty * TILES + min(TILES - 1, x * TILES / w)
                sum[0][t] += dxx; sq[0][t] += dxx * dxx
                sum[1][t] += dyy; sq[1][t] += dyy * dyy
                sum[2][t] += dd1; sq[2][t] += dd1 * dd1
                sum[3][t] += dd2; sq[3][t] += dd2 * dd2
                sum[4][t] += lap; sq[4][t] += lap * lap
                cnt[t]++
            }
        }
        // var[k][t]：第 k 个方向在第 t 个区块的方差
        val vars = Array(5) { k ->
            FloatArray(nt) { t ->
                val c = cnt[t]
                if (c == 0) 0f else {
                    val m = sum[k][t] / c
                    (sq[k][t] / c - m * m).toFloat()
                }
            }
        }
        fun topMean(a: FloatArray, k: Int): Float {
            val sorted = a.sortedArrayDescending()
            var acc = 0f
            for (i in 0 until k) acc += sorted[i]
            return acc / k
        }
        val directional = min(min(topMean(vars[0], TOP_TILES), topMean(vars[1], TOP_TILES)), min(topMean(vars[2], TOP_TILES), topMean(vars[3], TOP_TILES)))

        val tileIso = FloatArray(nt) { t -> (vars[0][t] + vars[1][t] + vars[2][t] + vars[3][t]) / 4f }
        val order = (0 until nt).sortedByDescending { tileIso[it] }
        var peak = 0f
        for (i in 0 until PEAK_TILES) peak += tileIso[order[i]]
        peak /= PEAK_TILES
        val median = tileIso.sortedArray().let { (it[nt / 2 - 1] + it[nt / 2]) / 2f }
        val dirSum = FloatArray(4)
        for (i in 0 until TOP_TILES) for (k in 0 until 4) dirSum[k] += vars[k][order[i]]
        val anisotropy = (dirSum.min() / max(1e-6f, dirSum.max()))

        return Sharpness(directional, topMean(vars[4], TOP_TILES), peak, median, anisotropy)
    }
}
