package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.VideoRules
import com.dtu.cleaner.data.CompressCandidate
import com.dtu.cleaner.util.Fmt

/**
 * 视频压缩：4K 视频转成 1080p（或保留 4K）的 H.265，体积小很多，手机上看几乎没区别。
 * 压好先放在原视频旁边，你看过、确认了，原视频才进“最近删除”。
 */
@Composable
fun CompressScreen(vm: MainViewModel) {
    val lib = vm.library ?: return
    val c by vm.compress.collectAsState()
    var keep4k by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<Long, Unit>() }
    var viewer by remember { mutableStateOf<Pair<List<ViewerEntry>, Int>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // 正在压的、刚压好的不再列为候选
    val busyIds = remember(c.running, lib) { c.finished.toSet() }
    val candidates = lib.compressible.filter { it.item.id !in busyIds }
    fun estimate(x: CompressCandidate) =
        if (keep4k) VideoRules.estimateSize(x.item.durationMs, x.item.bitrate, VideoRules.targetBitrate(true, 30f)) else x.estimated
    val chosen = candidates.filter { it.item.id in selected }
    val chosenSaving = chosen.sumOf { (it.item.size - estimate(it)).coerceAtLeast(0) }
    val totalSaving = candidates.sumOf { (it.item.size - estimate(it)).coerceAtLeast(0) }
    val pending = lib.compressedPending
    val drag = rememberDragSelect(
        gridState,
        keys = { candidates.map { it.item.id } },
        isSelected = { it in selected },
        setSelected = { id, sel -> if (sel) selected[id] = Unit else selected.remove(id) },
    )

    BackHandler { vm.back() }

    IosPage(
        navBar = {
            IosNavBar("压缩视频", onBack = { vm.back() }) {
                if (!c.running && candidates.isNotEmpty()) {
                    val all = candidates.all { it.item.id in selected }
                    NavAction(if (all) "全不选" else "全选") {
                        if (all) selected.clear() else candidates.forEach { selected[it.item.id] = Unit }
                    }
                }
            }
        },
        toast = vm.snackbar,
        toolbar = if (c.running || candidates.isEmpty()) null else ({
            IosToolbar {
                Column(Modifier.weight(1f)) {
                    Text("已选 ${chosen.size} 个", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text("约省 ${Fmt.bytes(chosenSaving)}", style = IosType.caption, color = ios.secondary)
                }
                CapsuleButton("开始压缩", { confirm = true }, enabled = chosen.isNotEmpty())
            }
        }),
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
                Column(Modifier.padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Footnote(
                        "把视频转成更省空间的 H.265 格式，在手机上看几乎没区别。压好的视频放在原视频旁边，拍摄时间、地点不变；" +
                            "原视频要你看过、确认后才放进“最近删除”。全在手机上转码，很耗电，建议边充电边压。收藏的视频不压。",
                    )
                    if (c.running) RunningCard(vm, c)
                    if (c.failed.isNotEmpty() && !c.running) {
                        Footnote("有 ${c.failed.size} 个没压成功：${c.failed.first().second}", color = ios.red)
                    }
                    if (pending.isNotEmpty()) PendingCard(vm, pending) { list, i -> viewer = list to i }
                    if (candidates.isNotEmpty()) {
                        SegmentedControl(
                            listOf(false to "1080p（推荐）", true to "保留 4K"), keep4k, { keep4k = it },
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        )
                        Footnote(
                            (if (keep4k) "保留 4K 分辨率，只换成更省空间的格式。" else "转成 1080p 高清，手机、电视上看都够清楚。") +
                                "\n${candidates.size} 个视频全压大约能省 ${Fmt.bytes(totalSaving)}。点视频选择，按住横着一划可以连续选，长按播放。",
                        )
                    }
                }
            }
            if (candidates.isEmpty() && pending.isEmpty() && !c.running) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("没有需要压缩的视频") }
            }
            items(candidates, key = { it.item.id }) { x ->
                PhotoCell(
                    x.item, x.item.id in selected,
                    onToggle = { if (x.item.id in selected) selected.remove(x.item.id) else selected[x.item.id] = Unit },
                    onOpen = { viewer = listOf(ViewerEntry(x.item, "${Fmt.dateTime(x.item.timeMs)} · ${x.item.width}×${x.item.height} · ${Fmt.bytes(x.item.size)}")) to 0 },
                    footer = "省 ${Fmt.bytes((x.item.size - estimate(x)).coerceAtLeast(0))}",
                )
            }
        }
    }

    if (confirm) {
        IosAlert(
            title = "压缩 ${chosen.size} 个视频？",
            message = "大约省 ${Fmt.bytes(chosenSaving)}。手机会转码一段时间，可以锁屏，进度在通知栏。压好后先放在原视频旁边，原视频不会自动删。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { confirm = false },
                AlertAction("开始") {
                    confirm = false
                    vm.startCompress(chosen.map { it.item }, keep4k)
                    selected.clear()
                },
            ),
            onDismiss = { confirm = false },
        )
    }

    viewer?.let { (list, start) ->
        Viewer(entries = list, start = start, isMarked = { null }, onToggle = {}, onDismiss = { viewer = null })
    }
}

@Composable
private fun RunningCard(vm: MainViewModel, c: com.dtu.cleaner.media.Compressor.State) {
    InsetGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在压缩第 ${c.done + 1} / ${c.total} 个", style = IosType.headline, color = ios.label, modifier = Modifier.weight(1f))
                Text("${c.percent}%", style = IosType.subhead, color = ios.secondary)
            }
            c.current?.let { cur ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThumbImage(cur, 200, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text("${Fmt.dateTime(cur.timeMs)} · ${Fmt.duration(cur.durationMs)} · ${Fmt.bytes(cur.size)}", style = IosType.footnote, color = ios.secondary)
                }
            }
            IosProgress((c.done + c.percent / 100f) / c.total.coerceAtLeast(1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("可以锁屏，进度在通知栏。", style = IosType.footnote, color = ios.secondary, modifier = Modifier.weight(1f))
                TextAction("停止", { vm.stopCompress() }, color = ios.red, style = IosType.subhead)
            }
        }
    }
}

/** 压好了、等确认的：对比播放，留压缩版删原视频，或者不要压缩版 */
@Composable
private fun PendingCard(
    vm: MainViewModel,
    pending: List<com.dtu.cleaner.data.CompressedPair>,
    onCompare: (List<ViewerEntry>, Int) -> Unit,
) {
    val before = pending.sumOf { it.source.size }
    val after = pending.sumOf { it.output.size }
    InsetGroup(header = "压好了，等你确认") {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text("${pending.size} 个视频：${Fmt.bytes(before)} → ${Fmt.bytes(after)}", style = IosType.headline, color = ios.label)
            Text("点下面的视频，原视频和压缩版前后对比着看。满意了再删原视频。", style = IosType.footnote, color = ios.secondary)
            Spacer(Modifier.height(10.dp))
            pending.take(4).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().iosPress {
                        onCompare(
                            listOf(
                                ViewerEntry(p.source, "原视频 · ${p.source.width}×${p.source.height} · ${Fmt.bytes(p.source.size)}"),
                                ViewerEntry(p.output, "压缩版 · ${p.output.width}×${p.output.height} · ${Fmt.bytes(p.output.size)}"),
                            ),
                            0,
                        )
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThumbImage(p.source, 200, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.dateTime(p.source.timeMs), style = IosType.subhead, color = ios.label)
                        Text("${Fmt.bytes(p.source.size)} → ${Fmt.bytes(p.output.size)}", style = IosType.footnote, color = ios.secondary)
                    }
                    Text("对比", style = IosType.subhead, color = ios.accent)
                }
            }
            if (pending.size > 4) Text("还有 ${pending.size - 4} 个", style = IosType.footnote, color = ios.secondary)
            Spacer(Modifier.height(10.dp))
            FilledButton("删除 ${pending.size} 个原视频，省 ${Fmt.bytes(before - after)}", { vm.keepCompressed(pending) }, Modifier.fillMaxWidth(), color = ios.red)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Spacer(Modifier.weight(1f))
                TextAction("不要压缩版", { vm.discardCompressed(pending) }, color = ios.secondary, style = IosType.subhead)
            }
        }
    }
}
