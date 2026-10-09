package com.dtu.cleaner.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 像 iPhone 相册那样“一划多选”：
 * - 按在照片上横着划：划过的照片一起选中（从已选中的照片开始划，就是一起取消）；
 * - 划开以后再上下拖：起点到手指之间的整片都选上；拖到边缘会自动滚动；
 * - 一开始就竖着滑：照常滚动；点一下、长按不受影响。
 */
class DragSelectState(
    private val hitTest: (Offset) -> Long?,
    private val keys: () -> List<Long>,
    private val isSelected: (Long) -> Boolean,
    private val setSelected: (Long, Boolean) -> Unit,
) {
    var autoScroll by mutableFloatStateOf(0f)
    private var startIndex = -1
    private var selectMode = true
    private var lastRange: IntRange? = null
    private val original = HashMap<Long, Boolean>()
    private var lastPos = Offset.Zero
    var onItemChanged: () -> Unit = {}

    /** 手指位置上的照片 key（不是照片的格子，比如顶部说明，返回 null） */
    fun keyAt(pos: Offset): Long? = hitTest(pos)

    fun begin(key: Long): Boolean {
        val idx = keys().indexOf(key)
        if (idx < 0) return false
        startIndex = idx
        selectMode = !isSelected(key)
        original.clear()
        lastRange = null
        apply(idx)
        return true
    }

    fun update(pos: Offset) {
        lastPos = pos
        if (startIndex < 0) return
        val key = keyAt(pos) ?: return
        val idx = keys().indexOf(key)
        if (idx >= 0) apply(idx)
    }

    /** 自动滚动后，手指没动但下面的照片变了 */
    fun refresh() = update(lastPos)

    private fun apply(idx: Int) {
        val range = if (idx >= startIndex) startIndex..idx else idx..startIndex
        if (range == lastRange) return
        val list = keys()
        // 离开范围的照片恢复原样
        lastRange?.forEach { i -> if (i !in range) list.getOrNull(i)?.let { k -> original[k]?.let { setSelected(k, it) } } }
        range.forEach { i ->
            val k = list.getOrNull(i) ?: return@forEach
            if (k !in original) original[k] = isSelected(k)
            setSelected(k, selectMode)
        }
        lastRange = range
        onItemChanged()
    }

    fun end() {
        startIndex = -1
        lastRange = null
        original.clear()
        autoScroll = 0f
    }
}

@Composable
fun rememberDragSelect(
    scroll: ScrollableState,
    hitTest: (Offset) -> Long?,
    keys: () -> List<Long>,
    isSelected: (Long) -> Boolean,
    setSelected: (Long, Boolean) -> Unit,
): DragSelectState {
    val haptics = LocalHapticFeedback.current
    val state = remember(scroll) { DragSelectState(hitTest, keys, isSelected, setSelected) }
    state.onItemChanged = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    // 拖到上下边缘时自动滚动
    LaunchedEffect(state.autoScroll) {
        while (state.autoScroll != 0f) {
            scroll.scrollBy(state.autoScroll)
            state.refresh()
            delay(16)
        }
    }
    return state
}

/** 标准网格（LazyVerticalGrid）：直接用网格的布局信息找手指下的照片 */
@Composable
fun rememberDragSelect(
    grid: LazyGridState,
    keys: () -> List<Long>,
    isSelected: (Long) -> Boolean,
    setSelected: (Long, Boolean) -> Unit,
): DragSelectState = rememberDragSelect(
    grid,
    hitTest = { pos ->
        val p = pos.round()
        grid.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(p) }?.key as? Long
    },
    keys, isSelected, setSelected,
)

/**
 * 卡片里的小格子（相似照片、场景精选）：每个格子用 [tile] 报告自己在屏幕上的位置，
 * 列表用 [tileContainer] 报告自己的位置，手指坐标换算后就能找到是哪张。
 */
class TileRegistry {
    private var origin = Offset.Zero
    private val rects = HashMap<Long, Rect>()

    fun keyAt(local: Offset): Long? {
        val p = local + origin
        return rects.entries.firstOrNull { it.value.contains(p) }?.key
    }

    fun setOrigin(o: Offset) { origin = o }
    fun put(key: Long, r: Rect) { rects[key] = r }
    fun remove(key: Long) { rects.remove(key) }
}

fun Modifier.tileContainer(reg: TileRegistry): Modifier = onGloballyPositioned { reg.setOrigin(it.positionInRoot()) }

fun Modifier.tile(reg: TileRegistry, key: Long): Modifier = composed {
    DisposableEffect(reg, key) { onDispose { reg.remove(key) } }
    onGloballyPositioned { reg.put(key, it.boundsInRoot()) }
}

fun Modifier.dragSelect(state: DragSelectState): Modifier = pointerInput(state) {
    val edge = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startKey = state.keyAt(down.position) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var dx = 0f
        var dy = 0f
        var selecting = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!selecting) {
                dx += change.position.x - change.previousPosition.x
                dy += change.position.y - change.previousPosition.y
                if (abs(dx) > slop || abs(dy) > slop) {
                    // 先横着动才算“划选”，先竖着动就是滚动，交给列表
                    if (abs(dx) > abs(dy) * 1.2f && state.begin(startKey)) selecting = true else break
                } else continue
            }
            change.consume() // 不让列表滚动、不触发单击
            val h = size.height.toFloat()
            val y = change.position.y
            state.autoScroll = when {
                y > h - edge -> (y - (h - edge)) / edge * 28f
                y < edge -> -(edge - y) / edge * 28f
                else -> 0f
            }
            state.update(change.position)
        }
        state.end()
    }
}
