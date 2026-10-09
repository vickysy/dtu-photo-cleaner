package com.dtu.cleaner.ui

import com.dtu.cleaner.core.ImageFeatures
import com.dtu.cleaner.core.PhotoInfo
import com.dtu.cleaner.core.SceneDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景精选按场景删除：删一个场景不能连带别的场景（v2.0 修的问题） */
class SceneSessionTest {
    private fun shot(id: Long, minute: Long) = PhotoInfo(
        id, minute * 60_000, 4000, 3000, 3_000_000, false,
        ImageFeatures(id * 7919, 0L, ByteArray(48) { (id % 200).toByte() }, 900f, 900f, 120f, 50f, 0.01f, 0.01f, 200f, 2000f, 3f, 0.3f),
        null, 5f,
    )

    /** 两个场景：各 20 张，中间隔一小时 */
    private fun session(): SceneSession {
        val a = (0 until 20).map { shot(it.toLong(), it.toLong()) }
        val b = (0 until 20).map { shot(100L + it, 120L + it) }
        val scenes = SceneDetector.segment(a + b).map { SceneDetector.build(it) }
        assertEquals(2, scenes.size)
        return SceneSession(scenes) { null }
    }

    @Test
    fun deletingOneSceneOnlyTouchesThatScene() {
        val s = session()
        val (first, second) = s.scenes.toList()
        val one = s.selectedIdsIn(first)
        assertEquals(20 - first.keepCount, one.size)
        assertTrue(one.all { id -> first.members.any { it.id == id } })
        assertTrue(one.none { id -> second.members.any { it.id == id } })

        // 删掉第一个场景勾选的，它就处理完了；全部删除只剩第二个场景
        s.onDeleted(one.toSet())
        s.markDone(listOf(first).filter { s.selectedIdsIn(it).isEmpty() })
        assertEquals(listOf(second.key), s.active.map { it.key })
        assertEquals(s.selectedIdsIn(second), s.selectedIds())
    }

    @Test
    fun keptSceneDropsOutOfBulkDelete() {
        val s = session()
        val (first, second) = s.scenes.toList()
        s.markDone(listOf(first))
        assertTrue(s.selectedIds().none { id -> first.members.any { it.id == id } })
        assertEquals(20 - second.keepCount, s.selectedIds().size)
    }

    @Test
    fun keptInOnlyListsThatScene() {
        val s = session()
        val (first, _) = s.scenes.toList()
        s.toggle(first.members.last().id.let { id -> if (s.isMarked(id)) id else first.members.first { s.isMarked(it.id) }.id })
        assertEquals(first.keepCount + 1, s.keptIn(first).size)
    }
}
