package com.dtu.cleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ZoomIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/**
 * 删之前最后看一眼：要删的是哪几张（一排小图）、删到哪里、误删了去哪找回。
 * 点小图或“一张张看大图”逐张过一遍，看的时候能改成保留；看完回到这里，张数跟着变。
 * [items] 由调用方按最新的勾选算好传进来；[note] 补充说明删的范围（比如“包括还没滑到的组”）。
 */
@Composable
fun DeleteConfirm(
    vm: MainViewModel,
    items: List<MediaItem>,
    isMarked: (Long) -> Boolean,
    onToggle: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    var review by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    val r = review
    if (r != null) {
        // 过一遍的时候列表不跟着变，改成保留的那张还在，可以再改回来
        Viewer(
            entries = r.first.map { m ->
                ViewerEntry(m, "${Fmt.dateTime(m.timeMs)} · ${Fmt.bytes(m.size)}" + if (m.isVideo) " · ${Fmt.duration(m.durationMs)}" else "")
            },
            start = r.second,
            isMarked = { id -> isMarked(id) },
            onToggle = onToggle,
            onDismiss = { review = null },
        )
        return
    }

    val trash = vm.deleteModeFromSettings() == DeleteMode.TRASH
    val unit = if (items.isNotEmpty() && items.all { it.isVideo }) "段视频" else "张"
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(14.dp)).background(if (ios.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2))) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (items.isEmpty()) "都改成保留了" else "删除这 ${items.size} $unit？",
                    style = IosType.headline, color = ios.label, textAlign = TextAlign.Center,
                )
                if (items.isNotEmpty()) {
                    Text(Fmt.bytes(items.sumOf { it.size }), style = IosType.footnote, color = ios.secondary)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).clickable { review = items to i }) {
                                ThumbImage(m, 200, Modifier.fillMaxSize())
                                if (m.isVideo) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                val where = when {
                    items.isEmpty() -> "这次不删了。"
                    trash -> "删掉的先放进首页最下面的「最近删除」，30\u00A0天内能恢复。"
                    else -> "设置里选了“直接删除”：删了就找不回来。"
                }
                Text(
                    (note?.let { "$it\n" } ?: "") + where,
                    style = IosType.footnote, color = ios.label, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
            if (items.isEmpty()) {
                AlertCell(AlertAction("好", AlertAction.Role.Cancel, onDismiss), Modifier.fillMaxWidth().height(46.dp))
            } else {
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clickable { review = items to 0 },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ZoomIn, null, tint = ios.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("一张张看大图", style = IosType.body, color = ios.accent)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                Row(Modifier.fillMaxWidth().height(46.dp)) {
                    AlertCell(AlertAction("取消", AlertAction.Role.Cancel, onDismiss), Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(ios.separator))
                    AlertCell(AlertAction("删除", AlertAction.Role.Destructive, onConfirm), Modifier.weight(1f))
                }
            }
        }
    }
}

/** 第一次进清理页面时提示一次：怎么看大图。点“知道了”以后不再出现 */
@Composable
fun PreviewHint(vm: MainViewModel, modifier: Modifier = Modifier, text: String = "点照片右上角的放大镜（或按住照片）看大图。大图里也能改成保留。") {
    if (!vm.showPreviewHint) return
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(ios.brand.copy(alpha = 0.10f)).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ZoomIn, null, tint = ios.brand, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = IosType.subhead, color = ios.label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextAction("知道了", { vm.dismissPreviewHint() }, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold))
    }
}
