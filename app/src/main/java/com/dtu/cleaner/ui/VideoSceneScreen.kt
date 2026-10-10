package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtu.cleaner.core.VideoScene
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/**
 * 视频场景精选：同一个场景拍了好几段，推荐留几段精华，其余默认勾选。
 * 每个场景的“删除”只删这个场景；“全部删除…”要先确认。
 */
@Composable
fun VideoSceneScreen(vm: MainViewModel, s: VideoSceneSession) {
    var viewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val scenes = s.active
    val all = s.selectedItems()

    fun deleteScenes(targets: List<VideoScene>) {
        val items = targets.flatMap { s.selectedIn(it) }
        val kept = targets.flatMap { s.keptIn(it) }
        vm.delete(items, vm.deleteModeFromSettings(), kept) { ids ->
            s.onDeleted(ids)
            if (ids.isNotEmpty()) s.markDone(targets.filter { s.selectedIdsIn(it).isEmpty() })
        }
    }

    fun keepScene(sc: VideoScene) {
        val ids = s.visible(sc).map { it.id }
        vm.markReviewed(ids)
        s.markDone(listOf(sc))
        vm.toast("已保留这个场景的 ${ids.size} 段，以后不再提示")
    }

    BackHandler { vm.back() }

    IosPage(
        navBar = { IosNavBar("视频场景精选", onBack = { vm.back() }) },
        toast = vm.snackbar,
        toolbar = if (scenes.isEmpty()) null else ({
            IosToolbar {
                Column(Modifier.weight(1f)) {
                    Text("还有 ${scenes.size} 个场景", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text(
                        if (all.isEmpty()) "都设成了全部保留" else "全部按勾选处理：删 ${all.size} 段 · ${Fmt.bytes(all.sumOf { it.size })}",
                        style = IosType.caption, color = ios.secondary,
                    )
                }
                CapsuleButton("全部删除…", { confirmAll = true }, color = ios.red, filled = false, enabled = all.isNotEmpty())
            }
        }),
    ) {
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Footnote(
                    "同一个地方接连拍了好几段视频时，帮你推荐留哪几段：时长够、画面清楚、构图好看，而且尽量是不同的内容。" +
                        "其余的已经勾选。几段视频内容往往不一样，长按播放看一眼再删；只点这个场景自己的“删除”，不会动别的。",
                )
            }
            if (scenes.isEmpty()) item { EmptyState("视频场景都处理完了") }
            items(scenes, key = { it.key }) { sc ->
                VideoSceneCard(
                    s, sc,
                    onPlay = { list, i -> viewer = list to i },
                    onDelete = { deleteScenes(listOf(sc)) },
                    onKeep = { keepScene(sc) },
                )
            }
        }
    }

    if (confirmAll) {
        val trash = vm.deleteModeFromSettings() == DeleteMode.TRASH
        IosAlert(
            title = "删除 ${scenes.size} 个场景里的 ${all.size} 段视频？",
            message = "包括你还没播放看过的场景。每个场景按现在的勾选处理，推荐的留下，其余 ${Fmt.bytes(all.sumOf { it.size })} " +
                (if (trash) "移到“最近删除”，30 天内能恢复。" else "直接彻底删除，无法恢复。") +
                "\n只想删一个场景，点那个场景卡片上的“删除”。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { confirmAll = false },
                AlertAction("全部删除", AlertAction.Role.Destructive) { confirmAll = false; deleteScenes(s.active) },
            ),
            onDismiss = { confirmAll = false },
        )
    }

    viewer?.let { (list, start) ->
        Viewer(
            entries = list.map { ViewerEntry(it, "${Fmt.dateTime(it.timeMs)} · ${Fmt.duration(it.durationMs)} · ${Fmt.bytes(it.size)}") },
            start = start,
            isMarked = { id -> s.isMarked(id) },
            onToggle = { id -> s.toggle(id) },
            onDismiss = { viewer = null },
        )
    }
}

@Composable
private fun VideoSceneCard(
    s: VideoSceneSession,
    sc: VideoScene,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val members = s.visible(sc).mapNotNull { s.itemOf(it.id) }
    val kept = members.filter { !s.isMarked(it.id) }
    val dropped = members.filter { s.isMarked(it.id) }
    val modified = members.any { s.isMarked(it.id) == (it.id in sc.keep) }

    InsetGroup {
        Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.day(sc.startTime), style = IosType.headline, color = ios.label)
                    Text(
                        "${Fmt.hm(sc.startTime)}–${Fmt.hm(sc.endTime)} · ${members.size} 段 · 共 ${Fmt.duration(sc.totalDurationMs)}",
                        style = IosType.footnote, color = ios.secondary,
                    )
                    val favs = members.count { it.id in sc.favorites }
                    if (favs > 0) Text("另有 $favs 段收藏的也留着", style = IosType.footnote, color = ios.secondary)
                }
                val pickable = members.count { it.id !in sc.favorites }
                KeepStepper(
                    "留 ${sc.keepCount} 段",
                    onMinus = if (sc.keepCount > 1) ({ s.setKeep(sc, sc.keepCount - 1) }) else null,
                    onPlus = if (sc.keepCount < pickable) ({ s.setKeep(sc, sc.keepCount + 1) }) else null,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 所有片段一排横着放：留下的在前，点一下切换删/留，长按播放
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (kept + dropped).forEach { m ->
                    ClipTile(
                        m, marked = s.isMarked(m.id), star = m.id in sc.keep && m.id !in sc.favorites, favorite = m.id in sc.favorites,
                        onToggle = { s.toggle(m.id) }, onPlay = { onPlay(members, members.indexOf(m)) },
                    )
                }
            }
            Text(
                "点视频切换删/留，长按播放。打勾的会删掉。",
                style = IosType.caption, color = ios.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            IosDivider()
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dropped.isNotEmpty()) TextAction("全部保留", onKeep, style = IosType.subhead)
                if (modified) {
                    if (dropped.isNotEmpty()) Spacer(Modifier.width(20.dp))
                    TextAction("恢复推荐", { s.reset(sc) }, style = IosType.subhead)
                }
                Spacer(Modifier.weight(1f))
                if (dropped.isNotEmpty()) CapsuleButton("删除 ${dropped.size} 段 · ${Fmt.bytes(dropped.sumOf { it.size })}", onDelete, color = ios.red)
                else CapsuleButton("完成", onKeep)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipTile(m: MediaItem, marked: Boolean, star: Boolean, favorite: Boolean, onToggle: () -> Unit, onPlay: () -> Unit) {
    Box(
        Modifier.width(112.dp).height(150.dp).clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onToggle, onLongClick = onPlay,
            ),
    ) {
        ThumbImage(m, 360, Modifier.fillMaxSize())
        if (marked) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)))
        if (star) PhotoBadge("推荐", Modifier.align(Alignment.TopStart).padding(6.dp), icon = Icons.Rounded.Star, iconTint = Gold)
        if (favorite) PhotoBadge("已收藏", Modifier.align(Alignment.TopStart).padding(6.dp))
        PhotoBadge("${Fmt.duration(m.durationMs)} · ${Fmt.bytes(m.size)}", Modifier.align(Alignment.BottomStart).padding(6.dp))
        SelectCircle(marked, Modifier.align(Alignment.BottomEnd).padding(6.dp).padding(bottom = 22.dp))
    }
}

/** 留几段：文字 + [ − | + ] */
@Composable
private fun KeepStepper(label: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = IosType.subhead, color = ios.label)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(ios.fill), verticalAlignment = Alignment.CenterVertically) {
            StepCell("−", onMinus)
            Box(Modifier.width(0.5.dp).fillMaxHeight().padding(vertical = 7.dp).background(ios.separator))
            StepCell("+", onPlus)
        }
    }
}

@Composable
private fun StepCell(text: String, onClick: (() -> Unit)?) {
    Box(Modifier.width(44.dp).fillMaxHeight().iosPress(enabled = onClick != null) { onClick?.invoke() }, contentAlignment = Alignment.Center) {
        Text(text, fontSize = 20.sp, color = if (onClick != null) ios.label else ios.tertiary)
    }
}
