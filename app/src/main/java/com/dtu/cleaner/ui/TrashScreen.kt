package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/** 最近删除（系统回收站）：可以恢复，或者彻底删除腾出空间 */
@Composable
fun TrashScreen(vm: MainViewModel) {
    val items = vm.trash
    val selected = remember { mutableStateMapOf<Long, Unit>() }
    val sel = items.filter { it.id in selected }
    val target = sel.ifEmpty { items }
    var confirmPurge by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    val drag = rememberDragSelect(
        gridState,
        keys = { vm.trash.map { it.id } },
        isSelected = { it in selected },
        setSelected = { id, sel -> if (sel) selected[id] = Unit else selected.remove(id) },
    )

    BackHandler { vm.back() }

    IosPage(
        navBar = {
            IosNavBar("最近删除", onBack = { vm.back() }) {
                if (selected.isNotEmpty()) NavAction("取消") { selected.clear() }
            }
        },
        toast = vm.snackbar,
        toolbar = if (items.isEmpty()) null else ({
            IosToolbar {
                TextAction(
                    if (sel.isEmpty()) "全部恢复" else "恢复 ${sel.size} 项",
                    { vm.delete(target, DeleteMode.RESTORE) { ids -> ids.forEach { selected.remove(it) } } },
                )
                Spacer(Modifier.weight(1f))
                TextAction(
                    if (sel.isEmpty()) "全部删除" else "删除 ${sel.size} 项",
                    { confirmPurge = target }, color = ios.red,
                )
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
                Footnote(
                    "在轻了相册里删掉的照片和视频都在这里（手机自带相册的“最近删除”里不一定看得到）。误删了点“恢复”，就回到相册里了。" +
                        "它们会在显示的天数后彻底删除；在这里时仍然占用空间，想马上腾出空间，点“全部删除”。点照片可以只处理选中的，按住横着一划可以连续选。" +
                        if (items.isNotEmpty()) "\n共 ${items.size} 项 · ${Fmt.bytes(items.sumOf { it.size })}" else "",
                    Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
            }
            if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("没有最近删除的项目") }
            items(items, key = { it.id }) { m ->
                PhotoCell(
                    m, m.id in selected,
                    onToggle = { if (m.id in selected) selected.remove(m.id) else selected[m.id] = Unit },
                    onOpen = { viewer = items.indexOf(m) },
                    footer = Fmt.daysLeft(m.expiresSec),
                )
            }
        }
    }

    viewer?.let { start ->
        Viewer(
            entries = items.map { ViewerEntry(it, "${Fmt.dateTime(it.timeMs)} · ${Fmt.bytes(it.size)} · ${Fmt.daysLeft(it.expiresSec)}") },
            start = start,
            isMarked = { null },
            onToggle = {},
            onDismiss = { viewer = null },
        )
    }

    confirmPurge?.let { list ->
        IosAlert(
            title = "彻底删除 ${list.size} 项？",
            message = "将腾出 ${Fmt.bytes(list.sumOf { it.size })}。此操作无法撤销。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { confirmPurge = null },
                AlertAction("删除", AlertAction.Role.Destructive) {
                    confirmPurge = null
                    vm.delete(list, DeleteMode.PURGE) { ids -> ids.forEach { selected.remove(it) } }
                },
            ),
            onDismiss = { confirmPurge = null },
        )
    }
}
