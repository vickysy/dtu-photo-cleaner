package com.dtu.cleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenesTest {
    private fun shot(id: Long, minute: Long, dHash: Long, aes: Float?, peak: Float = 2000f, fav: Boolean = false, color: Int = 100) = PhotoInfo(
        id, minute * 60_000, 4000, 3000, 3_000_000, fav,
        ImageFeatures(dHash, 0L, ByteArray(48) { color.toByte() }, 900f, 900f, 120f, 50f, 0.01f, 0.01f, 200f, peak, 3f, 0.3f),
        null, aes,
    )

    @Test
    fun segmentsByTimeGap() {
        val a = (0 until 12).map { shot(it.toLong(), it.toLong(), 0, 5f) }          // 0..11 分钟
        val b = (0 until 5).map { shot(100L + it, 60L + it, 0, 5f) }                 // 一小时后，只有 5 张
        val c = (0 until 15).map { shot(200L + it, 200L + it * 2, 0, 5f) }           // 再过两小时，15 张
        val scenes = SceneDetector.segment(a + b + c)
        assertEquals(listOf(12, 15), scenes.map { it.size })
    }

    @Test
    fun picksBestAndAvoidsNearDuplicates() {
        // 30 张：三个画面（构图、颜色都不同），每个画面 10 张；画面 A 的美感分最高
        val photos = ArrayList<PhotoInfo>()
        var id = 0L
        val views = listOf(Triple(0L, 6.0f, 40), Triple(0xFFFFFFFFL, 5.2f, 120), Triple(-1L, 5.0f, 200)) // 画面 A/B/C
        for ((v, base, color) in views) repeat(10) { i -> photos.add(shot(id++, id, v, base + i * 0.01f, color = color)) }
        val picks = SceneDetector.pick(photos, 3)
        assertEquals(3, picks.size)
        // 三张应当来自三个不同画面，而不是三张都是美感分最高的画面 A
        assertEquals(3, picks.map { it.features.dHash }.distinct().size)
        assertEquals(0L, picks.first().features.dHash)
    }

    @Test
    fun blurryPhotoIsNotAHighlight() {
        val photos = (0 until 12).map { i ->
            if (i == 3) shot(i.toLong(), i.toLong(), i * 0x1111L, 7.5f, peak = 10f) // 美感分最高但拍糊了
            else shot(i.toLong(), i.toLong(), i * 0x1111L, 5f)
        }
        val picks = SceneDetector.pick(photos, 4)
        assertFalse(picks.any { it.id == 3L })
    }

    @Test
    fun favoritesAlwaysKept() {
        val photos = (0 until 12).map { i -> shot(i.toLong(), i.toLong(), i * 0x1111L, 5f + i * 0.1f, fav = i == 0) }
        val sc = SceneDetector.build(photos, 3)
        assertTrue(0L in sc.highlights)
        assertEquals(listOf(0L), sc.favorites)
        assertEquals(3, sc.keepCount)
        assertEquals(4, sc.highlights.size) // 3 张精华 + 1 张收藏
    }

    @Test
    fun manyFavoritesDoNotEatTheKeepCount() {
        // 用户的真实情况：一个场景 30 张，收藏了 13 张；“留 4 张”应该是另外挑 4 张，而不是显示 17 张精华
        val photos = (0 until 30).map { i -> shot(i.toLong(), i.toLong(), i * 0x1111L, 5f + (i % 7) * 0.1f, fav = i < 13) }
        val sc = SceneDetector.build(photos, 4)
        assertEquals(4, sc.keepCount)
        assertEquals(13, sc.favorites.size)
        val picks = sc.highlights - sc.favorites.toSet()
        assertEquals(4, picks.size)
        assertTrue(picks.all { it >= 13L })
        assertEquals(17, sc.highlights.size) // 都留着：4 张精华 + 13 张收藏
    }

    @Test
    fun defaultKeepGrowsWithSize() {
        assertEquals(3, SceneDetector.defaultKeep(12))
        assertEquals(4, SceneDetector.defaultKeep(30))
        assertEquals(5, SceneDetector.defaultKeep(80))
    }
}
