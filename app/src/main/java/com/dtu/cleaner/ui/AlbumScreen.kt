package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/**
 * 分类相册（证件、聊天记录、文章与文件、好评……）：
 * - 平时像相册一样：点一下看大图，大图下面能分享、设为好评、删除；
 * - 点右上角“选择”（或长按一张）进入选择：点一下勾选，按住横着一划连续选；
 *   底部能拼成长图分享（按拍摄时间从早到晚）、原图分享、设为好评、删除。
 */
@Composable
fun AlbumScreen(vm: MainViewModel, s: AlbumSession) {
    val ctx = LocalContext.current
    val praise = vm.praise
    val visible = s.visible(praise)
    val selectedItems = s.selectedItems()
    var viewer by remember { mutableStateOf<Int?>(null) }
    val sources = remember(s) { s.entries.flatMap { it.tags }.distinct() }
    val praisedCount = s.entries.count { it.item.id in praise && it.item.id !in s.deleted }
    val allSelected = visible.isNotEmpty() && visible.all { it.item.id in s.selected }
    val gridState = rememberLazyGridState()
    val drag = rememberDragSelect(
        gridState,
        keys = { s.visible(vm.praise).map { it.item.id } },
        isSelected = { it in s.selected },
        setSelected = { id, sel -> if (sel) s.selected[id] = Unit else s.selected.remove(id) },
    )

    fun exitSelecting() {
        s.selecting = false
        s.selected.clear()
    }

    fun delete(items: List<MediaItem>) {
        vm.delete(items, vm.deleteModeFromSettings()) { ids -> ids.forEach { s.deleted[it] = Unit; s.selected.remove(it) } }
    }

    BackHandler { if (s.selecting && !s.startSelecting) exitSelecting() else vm.back() }

    IosPage(
        navBar = {
            IosNavBar(s.title, onBack = { vm.back() }) {
                if (visible.isEmpty()) return@IosNavBar
                if (s.selecting) {
                    NavAction(if (allSelected) "全不选" else "全选") {
                        if (allSelected) visible.forEach { s.selected.remove(it.item.id) } else visible.forEach { s.selected[it.item.id] = Unit }
                    }
                    if (!s.startSelecting) NavAction("取消", bold = true) { exitSelecting() }
                } else {
                    NavAction("选择") { s.selecting = true }
                }
            }
        },
        toast = vm.snackbar,
        toolbar = if (!s.selecting) null else ({
            IosToolbar {
                val any = selectedItems.isNotEmpty()
                val pics = selectedItems.count { !it.isVideo }
                Column(Modifier.weight(1.3f)) {
                    Text("已选 ${selectedItems.size} 张", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text(Fmt.bytes(selectedItems.sumOf { it.size }), style = IosType.caption, color = ios.secondary)
                }
                ToolAction(Icons.Rounded.ViewDay, if (vm.stitching) "拼图中…" else "拼长图", enabled = pics >= 1 && !vm.stitching, modifier = Modifier.weight(1f)) {
                    // 截图笔记：长图和整理好的文字一起发
                    val text = s.noteTitle?.let { vm.noteOf(selectedItems, it) }
                    vm.stitchAndShare(ctx, selectedItems, s.title, text) { if (!s.startSelecting) exitSelecting() }
                }
                s.noteTitle?.let { title ->
                    val note = if (any) vm.noteOf(selectedItems, title) else null
                    ToolAction(Icons.AutoMirrored.Rounded.Notes, "整理文字", enabled = note != null, modifier = Modifier.weight(1f)) {
                        note?.let { com.dtu.cleaner.media.NoteMaker.shareText(ctx, it, "把笔记发给…") }
                    }
                }
                ToolAction(Icons.Rounded.IosShare, "分享", enabled = any, modifier = Modifier.weight(1f)) { vm.shareItems(ctx, selectedItems) }
                if (s.praiseable) {
                    val allPraised = any && selectedItems.all { it.id in praise }
                    ToolAction(Icons.Rounded.Star, if (allPraised) "取消好评" else "设为好评", enabled = any, tint = ios.yellow, modifier = Modifier.weight(1f)) {
                        vm.setPraise(selectedItems.map { it.id }, !allPraised)
                        if (!s.startSelecting) exitSelecting()
                    }
                }
                ToolAction(Icons.Rounded.Delete, "删除", enabled = any, tint = ios.red, modifier = Modifier.weight(1f)) { delete(selectedItems) }
            }
        }),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize().then(if (s.selecting) Modifier.dragSelect(drag) else Modifier),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp, bottom = 10.dp)) {
                    Footnote(
                        s.note + "\n" + if (s.selecting) "点照片勾选，按住横着一划可以连续选，长按看大图。"
                        else "点开看大图；点右上角“选择”可以多选，拼成长图发给朋友或 Get笔记。",
                    )
                    if (sources.size > 1 || (s.praiseable && praisedCount > 0 && s.title != AlbumSession.PRAISE)) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterPill("全部", s.filter == null) { s.filter = null }
                            if (s.praiseable && praisedCount > 0 && s.title != AlbumSession.PRAISE) {
                                FilterPill("好评 $praisedCount", s.filter == AlbumSession.PRAISE) {
                                    s.filter = if (s.filter == AlbumSession.PRAISE) null else AlbumSession.PRAISE
                                }
                            }
                            if (sources.size > 1) sources.forEach { t ->
                                val n = s.entries.count { t in it.tags && it.item.id !in s.deleted }
                                FilterPill("$t $n", s.filter == t) { s.filter = if (s.filter == t) null else t }
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(if (s.filter == AlbumSession.PRAISE) "还没有设为好评的" else "这里没有图片了") }
            items(visible, key = { it.item.id }) { e ->
                val id = e.item.id
                PhotoCell(
                    e.item, id in s.selected,
                    onToggle = { if (s.selecting) s.toggle(id) else viewer = visible.indexOf(e) },
                    // 长按：看图时进入选择并选中这张；选择时看大图
                    onOpen = { if (s.selecting) viewer = visible.indexOf(e) else { s.selecting = true; s.selected[id] = Unit } },
                    label = if (id in praise) "好评" else null,
                    labelIcon = if (id in praise) Icons.Rounded.Star else null,
                    selectable = s.selecting,
                    aspect = s.aspect,
                    px = 360,
                )
            }
        }
    }

    viewer?.let { start ->
        if (visible.isEmpty()) { viewer = null; return@let }
        val actions = buildList {
            add(ViewerAction(Icons.Rounded.IosShare, { "分享" }) { id -> visible.firstOrNull { it.item.id == id }?.let { vm.shareItems(ctx, listOf(it.item)) } })
            if (s.praiseable) add(
                ViewerAction(
                    Icons.Rounded.Star, { id -> if (id in vm.praise) "取消好评" else "设为好评" },
                    tint = { id -> if (id in vm.praise) Gold else Color.White },
                ) { id -> vm.setPraise(listOf(id), id !in vm.praise) },
            )
            add(ViewerAction(Icons.Rounded.Delete, { "删除" }) { id -> visible.firstOrNull { it.item.id == id }?.let { delete(listOf(it.item)) } })
        }
        Viewer(
            entries = visible.map { e ->
                ViewerEntry(
                    e.item,
                    "${Fmt.dateTime(e.item.timeMs)} · ${Fmt.bytes(e.item.size)} · ${e.item.width}×${e.item.height}" +
                        if (e.item.bucket.isNotEmpty()) "\n相册：${e.item.bucket}" else "",
                )
            },
            start = start.coerceIn(0, visible.size - 1),
            isMarked = { null },
            onToggle = {},
            onDismiss = { viewer = null },
            actions = actions,
        )
    }
}

/** 底部工具栏里的一个按钮：图标 + 小字 */
@Composable
private fun ToolAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, tint: Color = ios.accent, onClick: () -> Unit) {
    Column(modifier.iosPress(enabled, onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) tint else ios.tertiary, modifier = Modifier.size(24.dp))
        Text(label, style = IosType.caption2, color = if (enabled) ios.label else ios.tertiary, maxLines = 1)
    }
}
