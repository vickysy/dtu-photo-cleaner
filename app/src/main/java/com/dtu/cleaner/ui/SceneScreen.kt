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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtu.cleaner.core.PhotoInfo
import com.dtu.cleaner.core.Scene
import com.dtu.cleaner.util.Fmt

/**
 * 场景精选：同一个地方连续拍了很多张，每个场景挑几张精华留下，其余的默认勾选删除。
 * 要删的照片默认摊开在每个场景下面；点删除先弹确认框，能一张张看大图再删。
 */
@Composable
fun SceneScreen(vm: MainViewModel, s: SceneSession) {
    var viewer by remember { mutableStateOf<Pair<Scene, Int>?>(null) }
    /** 要删的场景（按 key 记，确认框开着时勾选变了也能跟着算） */
    var pending by remember { mutableStateOf<Set<Long>?>(null) }
    var pendingAll by remember { mutableStateOf(false) }
    val scenes = s.active
    val all = s.selectedItems()
    val listState = rememberLazyListState()
    val tiles = remember(s) { TileRegistry() }
    val drag = rememberDragSelect(
        listState, hitTest = tiles::keyAt,
        keys = { s.active.filter { it.key in s.expanded }.flatMap { sc -> s.ordered(s.visible(sc)).map { it.id } } },
        isSelected = { s.isMarked(it) },
        setSelected = { id, sel -> s.marks[id] = sel },
    )

    /** 删除一批场景里勾选的照片；删干净了的场景算处理完 */
    fun deleteScenes(targets: List<Scene>) {
        val items = targets.flatMap { s.selectedIn(it) }
        val kept = targets.flatMap { s.keptIn(it) }
        vm.delete(items, vm.deleteModeFromSettings(), kept, onRestored = s::onRestored) { ids ->
            s.onDeleted(ids)
            if (ids.isNotEmpty()) s.markDone(targets.filter { s.selectedIdsIn(it).isEmpty() })
        }
    }

    fun keepScene(sc: Scene) {
        val ids = s.visible(sc).map { it.id }
        vm.markReviewed(ids)
        s.markDone(listOf(sc))
        vm.toast("已保留这个场景的 ${ids.size} 张，以后不再提示")
    }

    BackHandler { vm.back() }

    IosPage(
        navBar = { IosNavBar("场景精选", onBack = { vm.back() }) },
        toast = vm.snackbar,
        toolbar = if (scenes.isEmpty()) null else ({
            IosToolbar {
                Column(Modifier.weight(1f)) {
                    Text("还有 ${scenes.size} 个场景", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
                    Text(
                        if (all.isEmpty()) "都设成了全部保留" else "全部按勾选处理：删 ${all.size} 张 · ${Fmt.bytes(all.sumOf { it.size })}",
                        style = IosType.caption, color = ios.secondary,
                    )
                }
                CapsuleButton("全部删除…", color = ios.red, filled = false, enabled = all.isNotEmpty(), onClick = {
                    pendingAll = true
                    pending = s.active.map { it.key }.toSet()
                })
            }
        }),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.tileContainer(tiles).dragSelect(drag),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "hint") {
                Column {
                    PreviewHint(vm, Modifier.padding(bottom = 10.dp))
                    Footnote("同一个地方连续拍了很多张时，帮你挑出几张精华：拍得清楚、构图好，而且尽量是不同的画面。其余的已经勾选，摊开在每个场景下面，不想删的点一下。看好一个场景就点它自己的“删除”，只删这一个场景，不会动别的。")
                    SegmentedControl(
                        listOf(false to "按时间", true to "按美感"), s.sortByAes, { s.sortByAes = it },
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(),
                    )
                }
            }
            if (scenes.isEmpty()) item(key = "empty") { EmptyState("场景都处理完了") }
            items(scenes, key = { it.key }) { sc ->
                SceneCard(
                    s, sc, tiles,
                    onOpen = { i -> viewer = sc to i },
                    onDelete = { pendingAll = false; pending = setOf(sc.key) },
                    onKeep = { keepScene(sc) },
                )
            }
        }
    }


    viewer?.let { (sc, start) ->
        val members = s.ordered(s.visible(sc))
        Viewer(
            entries = members.map { p ->
                ViewerEntry(
                    s.itemOf(p.id)!!,
                    "${Fmt.dateTime(p.timeMs)} · ${Fmt.bytes(p.size)}" +
                        (if (p.aestheticKnown) "\n美感 ${"%.1f".format(p.aesthetic)} · 清晰度 ${p.features.peakSharpness.toInt()}" else ""),
                    when (p.id) { in sc.favorites -> listOf("已收藏"); in sc.highlights -> listOf("精华"); else -> emptyList() },
                )
            },
            start = start,
            isMarked = { id -> s.isMarked(id) },
            onToggle = { id -> s.toggle(id) },
            onDismiss = { viewer = null },
        )
    }

    pending?.let { keys ->
        val targets = s.active.filter { it.key in keys }
        DeleteConfirm(
            vm, targets.flatMap { s.selectedIn(it) },
            isMarked = { id -> s.isMarked(id) },
            onToggle = { id -> s.toggle(id) },
            onConfirm = { pending = null; deleteScenes(targets) },
            onDismiss = { pending = null },
            note = if (pendingAll) "${targets.size} 个场景里勾上的，包括还没滑到的场景。每个场景的精华会留下。" else null,
        )
    }
}

@Composable
private fun SceneCard(s: SceneSession, sc: Scene, tiles: TileRegistry, onOpen: (Int) -> Unit, onDelete: () -> Unit, onKeep: () -> Unit) {
    val members = s.ordered(s.visible(sc))
    val kept = members.filter { !s.isMarked(it.id) }
    val dropped = members.filter { s.isMarked(it.id) }
    val expanded = sc.key in s.expanded
    val modified = members.any { s.isMarked(it.id) == (it.id in sc.highlights) }

    InsetGroup {
        Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.day(sc.startTime), style = IosType.headline, color = ios.label)
                    Text("${Fmt.hm(sc.startTime)}–${Fmt.hm(sc.endTime)} · 拍了 ${members.size} 张", style = IosType.footnote, color = ios.secondary)
                    val favs = members.count { it.id in sc.favorites }
                    if (favs > 0) Text("另有 $favs 张收藏的也留着", style = IosType.footnote, color = ios.secondary)
                }
                // “留几张”只算挑出来的精华，收藏的不占名额
                val pickable = members.count { it.id !in sc.favorites }
                Stepper(
                    "留 ${sc.keepCount} 张",
                    onMinus = if (sc.keepCount > 1) ({ s.setKeep(sc, sc.keepCount - 1) }) else null,
                    onPlus = if (sc.keepCount < pickable) ({ s.setKeep(sc, sc.keepCount + 1) }) else null,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 保留的精华：大图横向滑动
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                kept.forEach { p -> HighlightTile(s, p, star = p.id in sc.highlights && p.id !in sc.favorites, showScore = s.sortByAes) { onOpen(members.indexOf(p)) } }
                if (kept.isEmpty()) {
                    Box(Modifier.height(132.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text("这个场景一张都不留", style = IosType.subhead, color = ios.secondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            IosDivider()
            Row(
                Modifier.fillMaxWidth().rowPress { if (expanded) s.expanded.remove(sc.key) else s.expanded[sc.key] = Unit }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (dropped.isEmpty()) "全部保留" else "其余 ${dropped.size} 张将删除",
                    style = IosType.body, color = ios.label, modifier = Modifier.weight(1f),
                )
                if (dropped.isNotEmpty()) Text(Fmt.bytes(dropped.sumOf { it.size }), style = IosType.body, color = ios.secondary)
                Spacer(Modifier.width(6.dp))
                Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (expanded) "收起" else "展开", tint = ios.tertiary)
            }
            if (expanded) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    PhotoGridRows(members, 5) { p, mod ->
                        PhotoCell(
                            s.itemOf(p.id)!!, s.isMarked(p.id),
                            onToggle = { s.toggle(p.id) }, onOpen = { onOpen(members.indexOf(p)) },
                            modifier = mod.tile(tiles, p.id).clip(RoundedCornerShape(4.dp)),
                            label = when {
                                s.sortByAes && p.aestheticKnown -> aesText(p.aesthetic!!)
                                p.id in sc.favorites -> "已收藏"
                                p.id in sc.highlights -> "精华"
                                else -> null
                            },
                            labelBg = if (s.sortByAes && p.aestheticKnown) aesColor(p.aesthetic!!) else null,
                            px = 240,
                        )
                    }
                }
                Text(
                    "点照片切换 删/留，按住横着一划可以连续切换；点右上角放大镜看大图",
                    style = IosType.caption, color = ios.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            IosDivider()
            // 每个场景自己的操作：只处理这一个场景
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dropped.isNotEmpty()) TextAction("全部保留", onKeep, style = IosType.subhead)
                if (modified) {
                    if (dropped.isNotEmpty()) Spacer(Modifier.width(20.dp))
                    TextAction("恢复推荐", { s.reset(sc) }, style = IosType.subhead)
                }
                Spacer(Modifier.weight(1f))
                if (dropped.isNotEmpty()) CapsuleButton("删除 ${dropped.size} 张", onDelete, color = ios.red)
                else CapsuleButton("完成", onKeep)
            }
        }
    }
}

/** 留下的精华：没有勾选圈，点一下看大图（改删/留在下面的格子里点） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightTile(s: SceneSession, p: PhotoInfo, star: Boolean, showScore: Boolean, onOpen: () -> Unit) {
    Box(
        Modifier.size(132.dp).clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onOpen, onLongClick = onOpen,
            ),
    ) {
        ThumbImage(s.itemOf(p.id)!!, 400, Modifier.fillMaxSize())
        if (star) PhotoBadge("精华", Modifier.align(Alignment.TopStart).padding(6.dp), icon = Icons.Rounded.Star, iconTint = Gold)
        if (showScore && p.aestheticKnown) {
            PhotoBadge("美感 ${aesText(p.aesthetic!!)}", Modifier.align(Alignment.BottomStart).padding(6.dp), bg = aesColor(p.aesthetic!!))
        }
    }
}

/** iOS 风格的步进器：文字 + [ − | + ] */
@Composable
private fun Stepper(label: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = IosType.subhead, color = ios.label)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(ios.fill), verticalAlignment = Alignment.CenterVertically) {
            StepperCell("−", onMinus)
            Box(Modifier.width(0.5.dp).fillMaxHeight().padding(vertical = 7.dp).background(ios.separator))
            StepperCell("+", onPlus)
        }
    }
}

@Composable
private fun StepperCell(text: String, onClick: (() -> Unit)?) {
    Box(
        Modifier.width(44.dp).fillMaxHeight().iosPress(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 20.sp, color = if (onClick != null) ios.label else ios.tertiary)
    }
}

