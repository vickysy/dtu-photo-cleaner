package com.dtu.cleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GroupingTest {
    /** 生成一张有纹理的测试图：几个随机色块 + 细条纹。 */
    private fun scene(seed: Int, w: Int = 160, h: Int = 120, shift: Int = 0, blur: Int = 0, gain: Float = 1f): IntArray {
        val rnd = Random(seed)
        val base = IntArray(w * h)
        val blocks = List(6) { IntArray(7) { rnd.nextInt(256) } }
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x + shift
            var r = 40
            var g = 40
            var b = 40
            for (bl in blocks) {
                val bx = bl[0] * w / 256
                val by = bl[1] * h / 256
                if (sx in bx until bx + w / 3 && y in by until by + h / 3) { r = bl[2]; g = bl[3]; b = bl[4] }
            }
            if ((sx / 3 + y / 7) % 2 == 0) { r = (r + 40).coerceAtMost(255); g = (g + 40).coerceAtMost(255) }
            base[y * w + x] = rgb((r * gain).toInt(), (g * gain).toInt(), (b * gain).toInt())
        }
        return if (blur > 0) boxBlur(base, w, h, blur) else base
    }

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val p = src[yy * w + xx]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
            }
            out[y * w + x] = rgb(sr / n, sg / n, sb / n)
        }
        return out
    }

    private fun photo(id: Long, t: Long, px: IntArray, faces: FaceInfo? = null, fav: Boolean = false) =
        PhotoInfo(id, t, 4000, 3000, 3_000_000, fav, ImageMetrics.compute(px, 160, 120), faces)

    @Test
    fun blurReducesSharpness() {
        val sharp = ImageMetrics.compute(scene(1), 160, 120)
        val blurred = ImageMetrics.compute(scene(1, blur = 2), 160, 120)
        assertTrue(sharp.sharpness > blurred.sharpness * 3)
    }

    @Test
    fun burstIsGroupedAndSharpestKept() {
        val photos = listOf(
            photo(1, 0, scene(7, blur = 2)),
            photo(2, 1_000, scene(7)),
            photo(3, 2_000, scene(7, shift = 2, blur = 1)),
            photo(4, 3_000, scene(99)), // 另一个场景
        )
        val groups = Grouper.group(photos, GroupParams())
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L, 3L), groups[0].map { it.id })
        val ranked = Ranker.rank(groups[0], 1)
        assertEquals(2L, ranked.members.single { it.keep }.photo.id)
        assertEquals(2, ranked.suggestedDelete.size)
    }

    @Test
    fun farApartInTimeIsNotGrouped() {
        val photos = listOf(photo(1, 0, scene(7)), photo(2, 3_600_000, scene(7, shift = 2)))
        assertTrue(Grouper.group(photos, GroupParams()).isEmpty())
    }

    @Test
    fun exactDuplicatesGroupedAcrossTime() {
        val photos = listOf(photo(1, 0, scene(7)), photo(2, 86_400_000L * 30, scene(7)))
        val groups = Grouper.group(photos, GroupParams())
        assertEquals(1, groups.size)
        assertTrue(Ranker.rank(groups[0], 1).duplicate)
    }

    @Test
    fun closedEyesLoses() {
        val open = FaceInfo(2, 0.95f, 0.6f)
        val closed = FaceInfo(2, 0.05f, 0.6f)
        val g = listOf(photo(1, 0, scene(5), closed), photo(2, 500, scene(5), open))
        val ranked = Ranker.rank(g, 1)
        assertEquals(2L, ranked.members.single { it.keep }.photo.id)
        assertTrue("有人闭眼" in ranked.members.first { it.photo.id == 1L }.tags)
    }

    @Test
    fun favoritesAlwaysKept() {
        val g = listOf(photo(1, 0, scene(5, blur = 2), fav = true), photo(2, 500, scene(5)))
        val ranked = Ranker.rank(g, 1)
        assertTrue(ranked.members.all { it.keep })
    }

    @Test
    fun blackFrameIsPlainNotBlurry() {
        val black = IntArray(160 * 120) { rgb(5, 5, 6) }
        val f = ImageMetrics.compute(black, 160, 120)
        val r = BadShotRules.reasons(f, 1)
        assertTrue(BadReason.PLAIN in r)
        assertFalse(BadReason.BLURRY in r)
    }

    /** 横向运动模糊（手抖）：每个像素取左右 [len] 个像素的平均 */
    private fun motionBlur(src: IntArray, w: Int, h: Int, len: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (d in -len / 2..len / 2) {
                val p = src[y * w + (x + d).coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            val n = len / 2 * 2 + 1
            out[y * w + x] = rgb(sr / n, sg / n, sb / n)
        }
        return out
    }

    /** 背景虚化：只有中间一块清楚，其余重度模糊 */
    private fun bokeh(seed: Int): IntArray {
        val sharp = scene(seed)
        val blurred = boxBlur(boxBlur(sharp, 160, 120, 4), 160, 120, 4)
        return IntArray(sharp.size) { i ->
            val x = i % 160
            val y = i / 160
            if (x in 60 until 100 && y in 40 until 80) sharp[i] else blurred[i]
        }
    }

    @Test
    fun bokehIsNotBlurry() {
        for (seed in 1..5) {
            val f = ImageMetrics.compute(bokeh(seed), 160, 120)
            assertTrue("seed $seed 应识别为背景虚化, ratio=${f.focusRatio}", BadShotRules.hasBokeh(f))
            for (lv in 0..2) assertFalse("seed $seed 灵敏度 $lv 不该判模糊, peak=${f.peakSharpness}", BadShotRules.isBlurry(f, lv))
        }
    }

    @Test
    fun dimButSharpIsNotBlurry() {
        val f = ImageMetrics.compute(scene(3, gain = 0.3f), 160, 120)
        assertFalse("peak=${f.peakSharpness}", BadShotRules.isBlurry(f, 1))
    }

    @Test
    fun uniformBlurIsBlurry() {
        // 真正拍废的照片：整张糊成一片
        var px = scene(3)
        repeat(4) { px = boxBlur(px, 160, 120, 4) }
        val f = ImageMetrics.compute(px, 160, 120)
        assertTrue("peak=${f.peakSharpness} ratio=${f.focusRatio}", BadShotRules.isBlurry(f, 1))
    }

    @Test
    fun motionBlurIsNeverPreselected() {
        // 只是某个方向没细节（手抖，或者百叶窗、大桥这类线条）：最多“可能模糊”，绝不默认勾选
        val f = ImageMetrics.compute(motionBlur(scene(3), 160, 120, 15), 160, 120)
        assertTrue("aniso=${f.anisotropy}", BadShotRules.blurLevel(f, 2) < 2)
    }

    @Test
    fun borderlineBlurIsOnlyMaybe() {
        fun feat(peak: Float, ratio: Float, aniso: Float) = ImageFeatures(
            0, 0, ByteArray(48), 0f, 0f, 120f, 50f, 0f, 0f, 200f,
            peakSharpness = peak, focusRatio = ratio, anisotropy = aniso,
        )
        // 整张均匀、中档阈值 45：低于 27 才算“明显模糊”
        assertEquals(2, BadShotRules.blurLevel(feat(20f, 3f, 0.3f), 1))
        assertEquals(1, BadShotRules.blurLevel(feat(35f, 3f, 0.3f), 1))
        assertEquals(0, BadShotRules.blurLevel(feat(300f, 3f, 0.3f), 1))
        // 背景虚化、主体清楚：同样的 35 不算模糊
        assertEquals(0, BadShotRules.blurLevel(feat(35f, 50f, 0.3f), 1))
        // 线条多的清楚照片（百叶窗、大桥）：不算
        assertEquals(0, BadShotRules.blurLevel(feat(900f, 3f, 0.01f), 1))
        // 细节少又有方向性：只提示
        assertEquals(1, BadShotRules.blurLevel(feat(100f, 3f, 0.01f), 1))
        // 缩略图太小、不可信：不判断
        assertEquals(0, BadShotRules.blurLevel(feat(-1f, 0f, 1f), 2))
        assertEquals(listOf(BadReason.MAYBE_BLURRY), BadShotRules.reasons(feat(35f, 3f, 0.3f), 1))
    }

    /** 手工造特征：[dHash] 决定“取景”（不同 dHash 差很多位 = 取景不同），清晰度可控 */
    private fun shot(id: Long, dHash: Long, sharp: Float, peak: Float, aes: Float? = null) = PhotoInfo(
        id, id * 60_000, 4000, 3000, 3_000_000, false,
        ImageFeatures(dHash, 0L, ByteArray(48) { 100 }, sharp, sharp, 110f, 50f, 0.01f, 0.01f, 200f, peak, 3f, 0.3f),
        null, aes,
    )

    @Test
    fun differentFramingIsNotCalledBlurrier() {
        // 帐篷那种情况：两张都清楚，取景不同，第一张前景杂乱所以“细节”更多
        val a = shot(1, 0L, sharp = 1082f, peak = 3000f)
        val b = shot(2, 0xFFFFFL, sharp = 679f, peak = 2000f)
        assertTrue(Similarity.hashDistance(a.features, b.features) > Ranker.SAME_FRAMING_BITS)
        val g = Ranker.rank(listOf(a, b), 1)
        assertTrue(g.members.none { "更模糊" in it.tags })
        assertEquals("照样给出一个推荐", 1, g.members.count { it.keep })
        assertTrue(g.closeCall)
    }

    @Test
    fun aestheticPicksBetterComposition() {
        val a = shot(1, 0L, sharp = 1082f, peak = 3000f, aes = 5.0f)
        val b = shot(2, 0xFFFFFL, sharp = 679f, peak = 2000f, aes = 5.8f)
        val g = Ranker.rank(listOf(a, b), 1)
        assertEquals(2L, g.members.single { it.keep }.photo.id)
        assertTrue("没那张好看" in g.members.first { it.photo.id == 1L }.tags)
    }

    @Test
    fun aestheticTooCloseStillRecommendsOne() {
        val a = shot(1, 0L, sharp = 1082f, peak = 3000f, aes = 5.5f)
        val b = shot(2, 0xFFFFFL, sharp = 679f, peak = 2000f, aes = 5.6f)
        val g = Ranker.rank(listOf(a, b), 1)
        assertEquals(2L, g.members.single { it.keep }.photo.id)
        assertTrue(RankedGroup.CLOSE_TAG in g.members.first { it.photo.id == 1L }.tags)
    }

    @Test
    fun missingAestheticUsesGroupAverage() {
        val a = shot(1, 0L, sharp = 900f, peak = 2500f, aes = 5.0f)
        val b = shot(2, 0xFFFFFL, sharp = 900f, peak = 2500f, aes = 5.9f)
        val c = shot(3, 0xFFFFF000000L, sharp = 900f, peak = 2500f, aes = null)
        val g = Ranker.rank(listOf(a, b, c), 1)
        assertEquals(2L, g.members.single { it.keep }.photo.id)
    }

    @Test
    fun blurryBurstFrameLosesEvenWithHigherAesthetic() {
        // 同一取景的连拍：糊的那张美感分再高也该删
        val sharp = shot(1, 0L, sharp = 900f, peak = 2500f, aes = 5.0f)
        val blurry = shot(2, 1L, sharp = 30f, peak = 10f, aes = 5.6f)
        val g = Ranker.rank(listOf(sharp, blurry), 1)
        assertEquals(1L, g.members.single { it.keep }.photo.id)
        assertTrue("模糊" in g.members.first { it.photo.id == 2L }.tags)
    }
}
