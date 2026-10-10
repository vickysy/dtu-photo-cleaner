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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.BadShotRules
import com.dtu.cleaner.util.Fmt

/** 废片 / 截图 / 大视频 / 短视频：“照片”App 式的方格，勾选 = 要删除 */
@Composable
fun GridReviewScreen(vm: MainViewModel, s: GridSession, subtitle: String? = null) {
    val visible = s.visible()
    val settings by vm.settings.collectAsState()
    val selectedItems = s.selectedItems()
    var viewer by remember { mutableStateOf<Pair<List<GridEntry>, Int>?>(null) }
    val allTags = remember(s) { s.entries.flatMap { it.tags }.distinct() }
    val allSelected = visible.isNotEmpty() && visible.all { it.item.id in s.selected }
    val gridState = rememberLazyGridState()
    val drag = rememberDragSelect(
        gridState,
        keys = { s.visible().map { it.item.id } },
        isSelected = { it in s.selected },
        setSelected = { id, sel -> if (sel) s.selected[id] = Unit else s.selected.remove(id) },
    )

    BackHandler { vm.back() }

    IosPage(
        navBar = {
            IosNavBar(s.title, onBack = { vm.back() }) {
                // 美感排行是全相册，不给“全选”，免得一下选中几千张
                if (visible.isNotEmpty() && s.kind != GridKind.AESTHETIC && s.kind != GridKind.RECENT) NavAction(if (allSelected) "全不选" else "全选") {
                    if (allSelected) visible.forEach { s.selected.remove(it.item.id) }
                    else visible.forEach { s.selected[it.item.id] = Unit }
                }
            }
        },
        toast = vm.snackbar,
        toolbar = {
            IosToolbar {
                Column(Modifier.weight(1f)) {
                    Text("已选 ${selectedItems.size} 项", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text("${Fmt.bytes(selectedItems.sumOf { it.size })} · 共 ${visible.size} 项", style = IosType.caption, color = ios.secondary)
                }
                CapsuleButton("删除", color = ios.red, enabled = selectedItems.isNotEmpty(), onClick = {
                    vm.delete(selectedItems, vm.deleteModeFromSettings(), s.explicitlyKept()) { ids ->
                        ids.forEach { s.deleted[it] = Unit; s.selected.remove(it) }
                    }
                })
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize().dragSelect(drag),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp, bottom = 10.dp)) {
                    Footnote((subtitle?.let { "$it · 共 ${s.entries.size} 张\n" } ?: "") + (s.note ?: hint(s.kind, settings.screenshotDays)))
                    if (s.kind == GridKind.AESTHETIC) {
                        SegmentedControl(
                            listOf(false to "从低到高", true to "从高到低"), s.sortDesc, { s.sortDesc = it },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(),
                        )
                    }
                    if (allTags.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterPill("全部", s.filter == null) { s.filter = null }
                            allTags.forEach { t ->
                                val n = s.entries.count { t in it.tags && it.item.id !in s.deleted }
                                if (n == 0) return@forEach
                                FilterPill("$t $n", s.filter == t) { s.filter = if (s.filter == t) null else t }
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("这里已经清干净了") }
            items(visible, key = { it.item.id }) { e ->
                PhotoCell(
                    e.item, e.item.id in s.selected,
                    onToggle = { if (e.item.id in s.selected) s.selected.remove(e.item.id) else s.selected[e.item.id] = Unit },
                    onOpen = { viewer = visible to visible.indexOf(e) },
                    label = e.score?.let { aesText(it) } ?: e.tags.firstOrNull { !it.startsWith("超过") },
                    labelBg = e.score?.let { aesColor(it) },
                    footer = when (s.kind) {
                        GridKind.SCREENSHOTS -> Fmt.date(e.item.timeMs)
                        GridKind.BIG_VIDEOS, GridKind.APP_IMAGES -> Fmt.bytes(e.item.size)
                        else -> null
                    },
                )
            }
        }
    }

    val hideFolder = rememberHideFolder(vm, { id -> s.entries.firstOrNull { it.item.id == id }?.item }) { rel ->
        s.entries.forEach { if (it.item.relPath.startsWith(rel)) { s.deleted[it.item.id] = Unit; s.selected.remove(it.item.id) } }
        s.filter = null
        viewer = null
    }
    viewer?.let { (list, start) ->
        Viewer(
            entries = list.map { e ->
                val f = vm.library?.features?.get(e.item.id)
                ViewerEntry(
                    e.item,
                    "${Fmt.dateTime(e.item.timeMs)} · ${Fmt.bytes(e.item.size)}" +
                        (if (e.item.isVideo) " · ${Fmt.duration(e.item.durationMs)}" else " · ${e.item.width}×${e.item.height}") +
                        (f?.let { "\n清晰度 ${it.peakSharpness.toInt()} · 亮度 ${it.mean.toInt()}" + if (BadShotRules.hasBokeh(it)) " · 背景虚化" else "" } ?: "") +
                        (vm.library?.aesthetic?.get(e.item.id)?.let { " · 美感 ${aesText(it)}" } ?: "") +
                        if (e.item.bucket.isNotEmpty()) "\n相册：${e.item.bucket}" else "",
                    e.tags,
                )
            },
            start = start,
            isMarked = { id -> id in s.selected },
            onToggle = { id -> if (id in s.selected) s.selected.remove(id) else s.selected[id] = Unit },
            onDismiss = { viewer = null },
            actions = listOf(hideFolder),
        )
    }
}

private fun hint(kind: GridKind, days: Int) = when (kind) {
    GridKind.BAD -> "明显拍废的已经勾选；标着“可能模糊”的拿不准，没勾，你看一眼再决定。点照片切换，按住横着一划可以连续选，长按看大图。"
    GridKind.SCREENSHOTS -> "认出了截图里的字：取件码、验证码、付款码这类 3 天前的、以及超过 $days 天的截图已经勾选；证件永远不勾。点照片切换，按住横着一划可以连续选，长按看大图。"
    GridKind.BIG_VIDEOS -> "按大小排序，最占地方的在前面。没有帮你勾选，点一下选中，横着一划连续选，长按播放。"
    GridKind.VIDEOS -> "每个视频抽了 5 帧来看：黑屏、没拍到东西、重复保存的、不到 3 秒的已经勾选；“可能全程模糊”的没勾，长按播放看一眼再决定。同一个场景拍了好几段的，在首页“视频场景精选”里挑。"
    GridKind.APP_IMAGES -> "微信、小红书、得到这些 App 存到相册里的图，按来源筛选。没有帮你勾选。点照片切换，按住横着一划可以连续选，长按看大图。"
    GridKind.DOCS -> "按图里的字分出来的。用完就没用的（取件码、验证码、付款码、二维码）3 天前的已经勾选，其余的没勾；证件永远不会建议删除。长按看大图。"
    GridKind.RECENT -> "刚拍的照片现场打了分（1–10 分，看构图色彩）。4.3 分及以下、明显拍糊、连拍里不是最好的已经勾选；有人闭眼的只标出来。分数只是参考，长按看大图再决定。"
    GridKind.AESTHETIC -> "相机拍的照片按美感分（1–10 分）排序，看构图和色彩；4.3 分及以下标红，5.5 分及以上标绿。没有帮你勾选，分数只是参考。按住照片横着一划可以连续选，划开后再往下拖能选一大片；长按看大图。"
}
