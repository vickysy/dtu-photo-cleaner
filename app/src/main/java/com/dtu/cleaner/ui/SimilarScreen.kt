package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.RankedGroup
import com.dtu.cleaner.core.RankedPhoto
import com.dtu.cleaner.util.Fmt

/**
 * 相似照片：一页看完，一次删完。勾选 = 要删除；“推荐”是建议保留的那张。
 * “角度不同”那一类容易判断错，不帮用户勾，只标出推荐。删之前都要在确认框里看一眼要删的。
 */
@Composable
fun SimilarScreen(vm: MainViewModel, s: SimilarSession) {
    var viewer by remember { mutableStateOf<Pair<List<RankedPhoto>, Int>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val near = s.near
    val varied = s.varied
    val shown = s.shown
    val selected = s.selectedItems(shown)
    val listState = rememberLazyListState()
    val tiles = remember(s) { TileRegistry() }
    val drag = rememberDragSelect(
        listState, hitTest = tiles::keyAt,
        keys = { s.shown.flatMap { g -> s.visibleMembers(g).map { it.photo.id } } },
        isSelected = { s.isMarked(it) },
        setSelected = { id, sel -> s.marks[id] = sel },
    )

    BackHandler { vm.back() }

    IosPage(
        navBar = { IosNavBar("相似照片", onBack = { vm.back() }) },
        toast = vm.snackbar,
        toolbar = {
            IosToolbar {
                Column(Modifier.weight(1f)) {
                    Text("已选 ${selected.size} 张", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text("${Fmt.bytes(selected.sumOf { it.size })} · 只删当前分类", style = IosType.caption, color = ios.secondary)
                }
                CapsuleButton("删除", color = ios.red, enabled = selected.isNotEmpty(), onClick = { confirm = true })
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.tileContainer(tiles).dragSelect(drag),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "seg") {
                SegmentedControl(
                    listOf(0 to "几乎一样 ${near.size}", 1 to "角度不同 ${varied.size}"), s.tab, { s.tab = it },
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
            }
            item(key = "hint${s.tab}") {
                Column {
                    PreviewHint(vm, Modifier.padding(bottom = 10.dp))
                    Footnote(
                        if (s.tab == 0) "连拍、重复保存的照片，画面几乎一样。每组已经挑好最好的一张，其余的勾选了。点照片切换，按住横着一划可以连续选或取消；点右上角放大镜看大图。"
                        else "同一个地方换了角度或构图拍的。这类最容易判断错（比如两页不同的文件、不同的人），所以没帮你勾：每组标了“推荐”留的那张，“差不多”是机器也分不出高下的。想删的点一下，或者点“全部按推荐”。",
                    )
                    Row(Modifier.padding(horizontal = 32.dp)) {
                        TextAction("全部按推荐", { shown.forEach { s.reset(it) } }, style = IosType.subhead)
                        Spacer(Modifier.width(24.dp))
                        TextAction("全部不选", { shown.forEach { s.keepAll(it) } }, style = IosType.subhead)
                    }
                }
            }
            if (shown.isEmpty()) item(key = "empty") { EmptyState("这一类已经清理完了") }
            items(shown, key = { it.key }) { g -> GroupCard(s, g, tiles) { list, i -> viewer = list to i } }
        }
    }

    if (confirm) {
        val groups = s.shown.count { g -> s.visibleMembers(g).any { s.isMarked(it.photo.id) } }
        DeleteConfirm(
            vm, s.selectedItems(s.shown),
            isMarked = { id -> s.isMarked(id) },
            onToggle = { id -> s.toggle(id) },
            onConfirm = { confirm = false; deleteShown(vm, s) },
            onDismiss = { confirm = false },
            // 跨了好几组时说清楚删的是哪些，免得以为只删眼前这一组
            note = if (groups > 1) "“${if (s.tab == 0) "几乎一样" else "角度不同"}”里 $groups 组勾上的，包括没滑到的组。" else null,
        )
    }

    val hideFolder = rememberHideFolder(vm, { id -> s.itemOf(id) }) { rel ->
        s.groups.forEach { g -> g.members.forEach { m -> if (s.itemOf(m.photo.id)?.relPath?.startsWith(rel) == true) s.deleted[m.photo.id] = Unit } }
        viewer = null
    }
    viewer?.let { (list, start) ->
        Viewer(
            entries = list.map { rp -> ViewerEntry(s.itemOf(rp.photo.id)!!, photoInfo(rp), rp.tags.filter { it !in QUIET_TAGS }) },
            start = start,
            isMarked = { id -> s.isMarked(id) },
            onToggle = { id -> s.toggle(id) },
            onDismiss = { viewer = null },
            actions = listOf(hideFolder),
        )
    }
}

private fun deleteShown(vm: MainViewModel, s: SimilarSession) {
    val groups = s.shown
    val votes = s.tasteVotes(groups)
    vm.delete(s.selectedItems(groups), vm.deleteModeFromSettings(), s.keptIds(groups), onRestored = s::onRestored) { ids ->
        if (ids.isNotEmpty()) vm.recordTaste(votes)
        s.onDeleted(ids)
    }
}

@Composable
private fun GroupCard(s: SimilarSession, g: RankedGroup, tiles: TileRegistry, onOpen: (List<RankedPhoto>, Int) -> Unit) {
    val members = s.visibleMembers(g)
    InsetGroup {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.dateTime(g.startTime), style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                Text(
                    " · ${members.size} 张" + if (g.duplicate) " · 完全重复" else "",
                    style = IosType.subhead, color = ios.secondary, modifier = Modifier.weight(1f),
                )
                if (s.allKept(g)) TextAction("按推荐", { s.reset(g) }, style = IosType.subhead)
                else TextAction("全部保留", { s.keepAll(g) }, style = IosType.subhead)
            }
            Spacer(Modifier.height(6.dp))
            PhotoGridRows(members, 4) { rp, mod ->
                PhotoCell(
                    s.itemOf(rp.photo.id)!!, s.isMarked(rp.photo.id),
                    onToggle = { s.toggle(rp.photo.id) }, onOpen = { onOpen(members, members.indexOf(rp)) },
                    modifier = mod.tile(tiles, rp.photo.id).clip(RoundedCornerShape(6.dp)),
                    label = if (rp.best) "推荐" else rp.tags.firstOrNull { it !in QUIET_TAGS },
                )
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CheckCircle, null, tint = ios.green, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = IosType.headline, color = ios.label, textAlign = TextAlign.Center)
    }
}

/** 这些标签信息量小，不显示（“差不多”要显示：提醒这两张机器也分不出高下） */
private val QUIET_TAGS = setOf("最佳", "相似", "少了人")

private fun photoInfo(rp: RankedPhoto): String {
    val f = rp.photo.features
    return "${Fmt.dateTime(rp.photo.timeMs)} · ${rp.photo.width}×${rp.photo.height} · ${Fmt.bytes(rp.photo.size)}\n" +
        "评分 ${rp.score} · 清晰度 ${f.peakSharpness.toInt()}" +
        (if (rp.photo.aestheticKnown) " · 美感 ${"%.1f".format(rp.photo.aesthetic)}" else "") +
        (rp.photo.faces?.let { if (it.count > 0) " · ${it.count} 张人脸" else "" } ?: "")
}
