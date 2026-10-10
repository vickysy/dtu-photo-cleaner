package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.ScreenText
import com.dtu.cleaner.core.ShotSession
import com.dtu.cleaner.data.Library
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.media.NoteMaker
import com.dtu.cleaner.util.AppLog
import com.dtu.cleaner.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图笔记：看直播、上网课时连续截的一串图，一键拼成长图、整理出文字，发给得到、微信、备忘录。
 */
@Composable
fun NotesScreen(vm: MainViewModel) {
    val lib = vm.library ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    /** 正在拼长图的那串 */
    val busy = remember { mutableStateMapOf<Long, Unit>() }
    /** 处理完删掉了截图的那串 */
    val gone = remember { mutableStateMapOf<Long, Unit>() }
    var viewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    val sessions = lib.sessions.filter { it.startMs !in gone }

    BackHandler { vm.back() }

    fun shotsOf(s: ShotSession) = s.ids.mapNotNull { lib.byId[it] }
    fun titleOf(s: ShotSession) = NoteMaker.title(s.startMs, s.ids.firstNotNullOfOrNull { lib.shotTexts[it]?.let(ScreenText::headline) })
    fun noteOf(s: ShotSession) = ScreenText.buildNote(titleOf(s), s.ids.map { lib.shotTexts[it] ?: "" })

    IosPage(navBar = { IosNavBar("截图笔记", onBack = { vm.back() }) }, toast = vm.snackbar) {
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Footnote(
                    "连续截的一串截图（看直播、上网课、刷长文）在这里。点缩略图看大图；点“挑几张”只选要的那几张，" +
                        "再拼成长图、整理成文字，发给得到、微信、Get笔记或备忘录。长图存在相册的“轻了笔记”里，原来的截图要你自己决定删不删。",
                )
            }
            if (sessions.isEmpty()) item { EmptyState("没有连续截的截图") }
            items(sessions, key = { it.startMs }) { s ->
                val shots = shotsOf(s)
                val textCount = s.ids.count { !lib.shotTexts[it].isNullOrBlank() }
                SessionCard(
                    s, shots, titleOf(s), textCount,
                    preview = ScreenText.cleanLines(s.ids.joinToString("\n") { lib.shotTexts[it] ?: "" }).take(3).joinToString(" / "),
                    busy = s.startMs in busy,
                    onOpen = { i -> viewer = shots to i },
                    onLongImage = {
                        busy[s.startMs] = Unit
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { NoteMaker.longImages(ctx, shots, titleOf(s)) } }
                            busy.remove(s.startMs)
                            r.onSuccess { uris ->
                                vm.toast("已拼成 ${uris.size} 张长图，存在相册“轻了笔记”里")
                                NoteMaker.shareImages(ctx, uris, if (textCount > 0) noteOf(s) else null, "把长图发给…")
                            }.onFailure {
                                AppLog.event("拼长图失败（${shots.size} 张）", it)
                                vm.toast("拼长图失败：${it.message}")
                            }
                        }
                    },
                    onText = { NoteMaker.shareText(ctx, noteOf(s), "把笔记发给…") },
                    onShareAll = { NoteMaker.shareImages(ctx, shots.map { it.uri }, null, "把截图发给…") },
                    onPick = { vm.openShotSession(s, titleOf(s)) },
                    onDelete = { vm.delete(shots, vm.deleteModeFromSettings()) { ids -> if (ids.isNotEmpty()) gone[s.startMs] = Unit } },
                )
            }
        }
    }

    viewer?.let { (list, start) ->
        Viewer(
            entries = list.map { ViewerEntry(it, Fmt.dateTime(it.timeMs)) },
            start = start,
            isMarked = { null },
            onToggle = {},
            onDismiss = { viewer = null },
        )
    }
}

@Composable
private fun SessionCard(
    s: ShotSession,
    shots: List<MediaItem>,
    title: String,
    textCount: Int,
    preview: String,
    busy: Boolean,
    onOpen: (Int) -> Unit,
    onLongImage: () -> Unit,
    onText: () -> Unit,
    onShareAll: () -> Unit,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    InsetGroup {
        Column(Modifier.padding(top = 14.dp, bottom = 10.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = IosType.headline, color = ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.day(s.startMs)} ${Fmt.hm(s.startMs)}–${Fmt.hm(s.endMs)} · ${shots.size} 张" +
                            if (textCount > 0) " · ${textCount} 张认出了字" else "",
                        style = IosType.footnote, color = ios.secondary,
                    )
                }
                // 不想全部都要：打开这一串，挑几张再拼、再整理
                TextAction("挑几张 ›", onPick, style = IosType.subhead, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shots.forEachIndexed { i, m ->
                    ThumbImage(m, 300, Modifier.width(72.dp).height(140.dp).clip(RoundedCornerShape(8.dp)).iosPress { onOpen(i) })
                }
            }
            if (preview.isNotBlank()) {
                Text(
                    preview, style = IosType.footnote, color = ios.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ios.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("正在拼长图…", style = IosType.subhead, color = ios.secondary)
                } else {
                    CapsuleButton("全部拼成长图", onLongImage)
                }
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (textCount > 0) {
                    TextAction("全部整理成文字", onText, style = IosType.subhead)
                    Spacer(Modifier.width(20.dp))
                }
                TextAction("分享原图", onShareAll, style = IosType.subhead)
                Spacer(Modifier.weight(1f))
                TextAction("删掉这 ${shots.size} 张", onDelete, color = ios.red, style = IosType.subhead)
            }
        }
    }
}
