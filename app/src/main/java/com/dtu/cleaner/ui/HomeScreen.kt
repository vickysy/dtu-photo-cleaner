package com.dtu.cleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtu.cleaner.analysis.Phase
import com.dtu.cleaner.analysis.Progress
import com.dtu.cleaner.core.Similarity
import com.dtu.cleaner.data.Library
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/**
 * 首页：照片当主角。
 * 最上面一个大数字“现在可以腾出多少”，一个按钮从最占地方的那类开始整理；
 * 下面每类一张照片拼图卡片，清完的类收进“已清完”。只用蓝色一个主色。
 */
@Composable
fun HomeScreen(vm: MainViewModel, onRequestFullAccess: () -> Unit) {
    val lib = vm.library
    val p by vm.progress.collectAsState()
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 90 } }
    val ctx = LocalContext.current
    val cats = remember(lib) { lib?.let { categories(it) } ?: emptyList() }
    val recentCount = remember(lib) {
        val cutoff = System.currentTimeMillis() - 86_400_000L
        lib?.images?.count { it.isCameraShot && it.timeMs >= cutoff } ?: 0
    }

    IosPage(
        navBar = {
            IosNavBar("D图", showTitle = collapsed, divider = collapsed) {
                NavIcon(Icons.Outlined.Settings, "设置") { vm.push(Screen.Settings) }
            }
        },
        toast = vm.snackbar,
    ) {
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { LargeTitle("D图") }

            if (vm.crashed) {
                item {
                    InsetGroup {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                            Text("D图 上次意外退出了", style = IosType.headline, color = ios.label)
                            Text("可以把问题报告发给作者，帮忙修好它。报告里没有照片，也没有文件名。", style = IosType.footnote, color = ios.secondary)
                            Row {
                                TextAction("发送报告", { vm.sendReport(ctx) }, style = IosType.subhead)
                                Spacer(Modifier.width(24.dp))
                                TextAction("不用了", { vm.dismissCrash() }, color = ios.secondary, style = IosType.subhead)
                            }
                        }
                    }
                }
            }

            if (vm.access == Access.PARTIAL) {
                item {
                    InsetGroup(footer = "D图 需要看到整个相册，才能找出重复和拍糊的照片。") {
                        IosRow(
                            "只允许了访问部分照片", subtitle = "点这里改成“全部允许”",
                            icon = Icons.Rounded.Collections, iconBg = ios.blue, divider = false, onClick = onRequestFullAccess,
                        )
                    }
                }
            }

            // 刚拍了照片：拍完马上看评分
            if (recentCount > 0) {
                item {
                    InsetGroup {
                        IosRow(
                            "刚拍的", subtitle = "最近 24 小时拍了 ${recentCount} 张，看评分，当场删掉拍得不好的",
                            icon = Icons.Rounded.PhotoCamera, iconBg = ios.blue, divider = false,
                            onClick = { vm.openRecent() },
                        )
                    }
                }
            }

            item { Hero(vm, lib, p, cats) }

            // 视频压缩单独一张卡：省的空间往往比删照片多得多
            if (lib != null && (lib.compressSaving > 50_000_000L || lib.compressedPending.isNotEmpty())) {
                item { CompressCard(lib) { vm.openCompress() } }
            }

            if (vm.showTips) item { TipsCard { vm.dismissTips() } }

            if (lib != null) {
                val todo = cats.filter { it.count > 0 }
                val done = if (lib.features.isEmpty()) emptyList() else cats.filter { it.count == 0 && it.needsAnalysis }
                if (todo.isNotEmpty() || done.isNotEmpty()) {
                    item { SectionHeader("建议清理") }
                    // 两列照片卡片
                    todo.chunked(2).forEachIndexed { i, pair ->
                        item(key = "tiles$i") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { c -> CategoryTile(c, Modifier.weight(1f)) { c.open(vm) } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    // 清完的类收成一行
                    if (done.isNotEmpty()) item(key = "done") { DoneRow(done.map { it.title }) }
                }

                if (lib.images.isNotEmpty()) {
                    item { SectionHeader("浏览") }
                    if (lib.aesthetic.isNotEmpty()) item { AestheticCard(lib) { vm.openGrid(GridKind.AESTHETIC) } }
                    item {
                        InsetGroup {
                            IosRow(
                                "文字与证件", subtitle = docsSummary(lib),
                                icon = Icons.Rounded.Badge, iconBg = ios.blue,
                                divider = lib.sessions.isNotEmpty() || lib.appImages.isNotEmpty(),
                                onClick = { vm.openDocs() },
                            )
                            if (lib.sessions.isNotEmpty()) {
                                IosRow(
                                    "截图笔记", subtitle = "${lib.sessions.size} 串连续截图，拼长图、整理文字",
                                    icon = Icons.Rounded.Notes, iconBg = ios.blue, divider = lib.appImages.isNotEmpty(),
                                    onClick = { vm.openNotes() },
                                )
                            }
                            if (lib.appImages.isNotEmpty()) {
                                val top = remember(lib) {
                                    lib.appImages.groupingBy { it.source }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString("、") { it.key }
                                }
                                IosRow(
                                    "App 存的图", subtitle = "${Fmt.count(lib.appImages.size)} 张，$top…",
                                    value = Fmt.bytes(lib.appImages.sumOf { it.size }),
                                    icon = Icons.Rounded.Apps, iconBg = ios.blue, divider = false,
                                    onClick = { vm.openGrid(GridKind.APP_IMAGES) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                InsetGroup(footer = "删掉的先放在这里，30 天后自动彻底删除。放在这里时仍然占空间。") {
                    IosRow(
                        "最近删除",
                        subtitle = if (vm.trash.isEmpty()) "空的" else "${Fmt.count(vm.trash.size)} 项",
                        value = if (vm.trash.isEmpty()) null else Fmt.bytes(vm.trash.sumOf { it.size }),
                        icon = Icons.Rounded.Delete, iconBg = ios.gray, divider = false,
                        onClick = { vm.refreshTrash(); vm.push(Screen.Trash) },
                    )
                }
            }

            val (n, b) = vm.cleaned
            if (n > 0) {
                item { Footnote("D图 已帮你清理 ${Fmt.count(n)} 项，共 ${Fmt.bytes(b)}", Modifier.fillMaxWidth(), TextAlign.Center) }
            }
        }
    }
}

// ---------- 分类 ----------

/** 首页上的一类建议 */
private class Category(
    val title: String,
    /** 卡片副标题，比如“71 个场景，每个留几张” */
    val summary: String,
    val count: Int,
    /** 预选删除能腾出的空间；大视频不预选，算 0 */
    val reclaim: Long,
    /** 卡片上显示的大小 */
    val sizeText: String,
    val thumbs: List<MediaItem>,
    /** 要分析过才知道有没有（相似、场景、废片）；截图、视频不用分析 */
    val needsAnalysis: Boolean,
    val open: (MainViewModel) -> Unit,
)

private fun categories(lib: Library): List<Category> {
    val sceneThumbs = lib.scenes.asSequence().mapNotNull { sc -> sc.highlights.firstOrNull()?.let { lib.byId[it] } }.take(4).toList()
    val groupThumbs = lib.groups.asSequence().mapNotNull { g -> g.members.firstOrNull { it.best }?.let { lib.byId[it.photo.id] } }.take(4).toList()
    val sure = lib.bad.filter { it.confident }
    val old = lib.oldScreenshots()
    return listOf(
        Category(
            "场景精选", "${lib.scenes.size} 个场景，各留几张", lib.scenes.size, lib.sceneDeleteBytes,
            Fmt.bytes(lib.sceneDeleteBytes), sceneThumbs, true,
        ) { it.openScenes() },
        Category(
            "相似照片", "${lib.groups.size} 组，各留最好的", lib.groups.size, lib.groupDeleteBytes,
            Fmt.bytes(lib.groupDeleteBytes), groupThumbs, true,
        ) { it.openGroups() },
        Category(
            "废片", "${lib.bad.size} 张拍糊或误拍", lib.bad.size, sure.sumOf { it.item.size },
            Fmt.bytes(lib.bad.sumOf { it.item.size }), lib.bad.take(4).map { it.item }, true,
        ) { it.openGrid(GridKind.BAD) },
        Category(
            "截图与录屏", if (old.isEmpty()) "${lib.screenshots.size} 项，都是最近的" else "${old.size} 项超过 ${lib.settings.screenshotDays} 天",
            lib.screenshots.size, old.sumOf { it.size },
            Fmt.bytes(lib.screenshots.sumOf { it.size }), lib.screenshots.take(4), false,
        ) { it.openGrid(GridKind.SCREENSHOTS) },
        Category(
            "大视频", "${lib.bigVideos.size} 个，自己挑", lib.bigVideos.size, 0L,
            Fmt.bytes(lib.bigVideos.sumOf { it.size }), lib.bigVideos.take(4), false,
        ) { it.openGrid(GridKind.BIG_VIDEOS) },
        Category(
            "视频场景精选", "${lib.videoScenes.size} 个场景拍了好几段", lib.videoScenes.size, lib.videoSceneDeleteBytes,
            Fmt.bytes(lib.videoSceneDeleteBytes),
            lib.videoScenes.asSequence().mapNotNull { sc -> sc.keep.firstOrNull()?.let { lib.byId[it] } }.take(4).toList(), true,
        ) { it.openVideoScenes() },
        Category(
            "可删的视频", videoSummary(lib), lib.videoSuggest.size,
            lib.videoSuggest.filter { it.confident }.sumOf { it.item.size },
            Fmt.bytes(lib.videoSuggest.sumOf { it.item.size }), lib.videoSuggest.take(4).map { it.item }, true,
        ) { it.openGrid(GridKind.VIDEOS) },
    )
}

/** “3 个黑屏 · 2 个重复”这种：挑最多的两类说 */
private fun videoSummary(lib: Library): String {
    val counts = lib.videoSuggest.flatMap { v -> v.reasons.take(1) }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(2)
    return if (counts.isEmpty()) "黑屏、误录、重复的" else counts.joinToString("，") { "${it.value} 个${it.key.label}" }
}

// ---------- 顶部大卡片 ----------

@Composable
private fun Hero(vm: MainViewModel, lib: Library?, p: Progress, cats: List<Category>) {
    val st = vm.storage
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(ios.card)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
    ) {
        when {
            p.running -> Analyzing(vm, p)
            lib == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ios.gray)
            }
            lib.features.isEmpty() || lib.outdated > 0 -> FirstAnalysis(vm, lib, p)
            else -> Reclaim(vm, lib, p, cats)
        }

        if (st != null) {
            Spacer(Modifier.height(18.dp))
            val used = (st.total - st.free).toFloat() / st.total.coerceAtLeast(1)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(ios.fill)) {
                Box(Modifier.fillMaxWidth(used.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(ios.secondary))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text("已用 ${Fmt.bytes(st.total - st.free)} / ${Fmt.bytes(st.total)}", style = IosType.caption, color = ios.secondary, modifier = Modifier.weight(1f))
                if (lib != null) Text("照片 ${Fmt.bytes(lib.imageBytes)} · 视频 ${Fmt.bytes(lib.videoBytes)}", style = IosType.caption, color = ios.secondary)
            }
        }
    }
}

private val DisplayStyle = TextStyle(fontSize = 56.sp, lineHeight = 62.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)

/** 大数字：数字大、单位小 */
@Composable
private fun BigNumber(bytes: Long) {
    val text = Fmt.bytes(bytes)
    Text(
        buildAnnotatedString {
            append(text.substringBeforeLast(' '))
            withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)) { append(" " + text.substringAfterLast(' ')) }
        },
        style = DisplayStyle, color = ios.label,
    )
}

@Composable
private fun Reclaim(vm: MainViewModel, lib: Library, p: Progress, cats: List<Category>) {
    val (count, bytes) = remember(lib) { lib.reclaimable() }
    // 从最占地方的那类开始；预选的都清完了，还有大视频就去看大视频
    val next = cats.filter { it.count > 0 && it.reclaim > 0 }.maxByOrNull { it.reclaim }
        ?: cats.firstOrNull { it.count > 0 }
    if (bytes > 0) {
        Text("现在可以腾出", style = IosType.subhead, color = ios.secondary)
        BigNumber(bytes)
        Text(
            "约 ${Fmt.count(count)} 张照片和视频" + (next?.let { "，先从${it.title}开始" } ?: ""),
            style = IosType.subhead, color = ios.secondary,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = ios.green, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("相册很清爽", style = IosType.title2, color = ios.label)
                Text(if (next != null) "建议的都清完了，可以看看${next.title}" else "没有需要清理的", style = IosType.subhead, color = ios.secondary)
            }
        }
    }
    if (next != null) {
        Spacer(Modifier.height(16.dp))
        FilledButton(if (bytes > 0) "开始整理" else "看看${next.title}", { next.open(vm) }, Modifier.fillMaxWidth())
    }
    // 有新照片还没分析：不挡着大数字，提一句
    if (lib.unanalyzed > 0 || lib.unscored > 0 || lib.videosUnanalyzed > 0) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parts = listOfNotNull(
                lib.unanalyzed.takeIf { it > 0 }?.let { "${Fmt.count(it)} 张新照片" },
                lib.videosUnanalyzed.takeIf { it > 0 }?.let { "${Fmt.count(it)} 个视频" },
            )
            Text(
                if (parts.isNotEmpty()) "还有 ${parts.joinToString("、")}没分析" else "有 ${Fmt.count(lib.unscored)} 张照片还没评分",
                style = IosType.footnote, color = ios.secondary, modifier = Modifier.weight(1f),
            )
            TextAction("分析", { vm.startAnalysis() }, style = IosType.subhead)
        }
    }
    if (p.phase == Phase.FAILED) p.message?.let { Text(it, style = IosType.footnote, color = ios.red) }
}

@Composable
private fun Analyzing(vm: MainViewModel, p: Progress) {
    Text(p.label, style = IosType.subhead, color = ios.secondary)
    Text(if (p.total > 0) "${(p.fraction * 100).toInt()}%" else "…", style = DisplayStyle, color = ios.label)
    if (p.total > 0) Text("${Fmt.count(p.done)} / ${Fmt.count(p.total)}", style = IosType.subhead, color = ios.secondary)
    Spacer(Modifier.height(14.dp))
    IosProgress(p.fraction)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("可以锁屏，进度会显示在通知栏。", style = IosType.footnote, color = ios.secondary, modifier = Modifier.weight(1f))
        TextAction("暂停", { vm.pauseAnalysis() }, style = IosType.subhead)
    }
}

@Composable
private fun FirstAnalysis(vm: MainViewModel, lib: Library, p: Progress) {
    val first = lib.features.isEmpty()
    Text(if (first) "先把相册过一遍" else "判断规则升级了", style = IosType.title2, color = ios.label)
    Spacer(Modifier.height(4.dp))
    Text(
        if (first) "${Fmt.count(lib.images.size)} 张照片，全部在手机里分析。几千张两三分钟，几万张十几分钟，建议边充电边等。"
        else "需要重新分析 ${Fmt.count(lib.outdated)} 张。你之前选择保留或删除的记录都还在。",
        style = IosType.subhead, color = ios.secondary,
    )
    p.message?.let { Text(it, style = IosType.footnote, color = if (p.phase == Phase.FAILED) ios.red else ios.secondary) }
    Spacer(Modifier.height(16.dp))
    FilledButton(if (first) "开始分析" else "继续", { vm.startAnalysis() }, Modifier.fillMaxWidth())
}

// ---------- 卡片 ----------

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = IosType.title3, color = ios.label, modifier = Modifier.padding(start = 20.dp, end = 20.dp))
}

@Composable
private fun CategoryTile(c: Category, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(ios.card).iosPress(onClick = onClick).padding(8.dp)) {
        Mosaic(c.thumbs)
        Spacer(Modifier.height(10.dp))
        // 标题、大小、说明各占一行：半屏宽放不下“截图与录屏 118 MB”
        Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = IosType.headline, color = ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ios.tertiary, modifier = Modifier.size(20.dp))
            }
            Text(c.sizeText, style = IosType.title3, color = if (c.reclaim > 0) ios.blue else ios.label, maxLines = 1)
            Text(c.summary, style = IosType.footnote, color = ios.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 照片拼图：4 张 2×2，3 张左大右二，2 张左右各半，1 张铺满 */
@Composable
internal fun Mosaic(items: List<MediaItem>) {
    val gap = 2.dp
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(ios.fill)) {
        when (items.size) {
            0 -> Unit
            1 -> Cell(items[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Cell(items[0], Modifier.weight(1f).fillMaxHeight())
                Cell(items[1], Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Cell(items[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Cell(items[1], Modifier.weight(1f).fillMaxWidth())
                    Cell(items[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0 until 2) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Cell(items[r * 2], Modifier.weight(1f).fillMaxHeight())
                        Cell(items[r * 2 + 1], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(item: MediaItem, modifier: Modifier) {
    Box(modifier) {
        ThumbImage(item, 320, Modifier.fillMaxSize())
        if (item.isVideo) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp))
        }
    }
}

/** 清完的类：一行绿色对勾 */
@Composable
private fun DoneRow(titles: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(ios.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = ios.green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("已清完", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
        Spacer(Modifier.width(8.dp))
        Text(titles.joinToString("、"), style = IosType.subhead, color = ios.secondary, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/** 视频压缩：能省多少、有几个压好了等确认 */
@Composable
private fun CompressCard(lib: Library, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(ios.card)
            .iosPress(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(ios.fill)) {
            lib.compressible.firstOrNull()?.let { ThumbImage(it.item, 240, Modifier.fillMaxSize()) }
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("压缩视频", style = IosType.headline, color = ios.label)
            if (lib.compressedPending.isNotEmpty()) {
                Text("${lib.compressedPending.size} 个压好了，等你确认", style = IosType.footnote, color = ios.blue)
            }
            if (lib.compressSaving > 0) {
                Text(
                    "${Fmt.count(lib.compressible.size)} 个视频能再省 ${Fmt.bytes(lib.compressSaving)}",
                    style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.blue,
                )
                Text("转成 1080p H.265，手机上看几乎没区别", style = IosType.footnote, color = ios.secondary)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ios.tertiary, modifier = Modifier.size(22.dp))
    }
}

/** 美感排行：一排你拍得最好的照片 */
@Composable
private fun AestheticCard(lib: Library, onClick: () -> Unit) {
    // 美感分最高的 4 张，画面几乎一样的只取一张
    val top = remember(lib) {
        val groupOf = HashMap<Long, Long>()
        lib.groups.forEach { g -> g.members.forEach { groupOf[it.photo.id] = g.key } }
        val picked = ArrayList<MediaItem>()
        val feats = ArrayList<com.dtu.cleaner.core.ImageFeatures>()
        val groups = HashSet<Long>()
        for (e in lib.aesthetic.entries.sortedByDescending { it.value }) {
            val item = lib.byId[e.key] ?: continue
            val f = lib.features[e.key]
            // 首页只是展示，比分组更严：构图有点像、颜色又很接近的也算重复
            if (f != null && feats.any { o ->
                    val h = Similarity.hashDistance(f, o)
                    h <= 20 || (h <= 50 && Similarity.colorDistance(f.color, o.color) <= 0.06f)
                }
            ) continue
            val g = groupOf[e.key]
            if (g != null && !groups.add(g)) continue
            picked.add(item)
            if (f != null) feats.add(f)
            if (picked.size == 4) break
        }
        picked
    }
    val low = remember(lib) { lib.aesthetic.values.count { aesIsLow(it) } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(ios.card)
            .iosPress(onClick = onClick).padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until 4) {
                Box(Modifier.weight(1f).aspectRatio(1f).background(ios.fill)) {
                    top.getOrNull(i)?.let { ThumbImage(it, 240, Modifier.fillMaxSize()) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("美感排行", style = IosType.headline, color = ios.label)
                Text(
                    "${Fmt.count(lib.aesthetic.size)} 张按 1–10 分排好" + if (low > 0) " · ${Fmt.count(low)} 张 4.3 分及以下" else "",
                    style = IosType.footnote, color = ios.secondary,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ios.tertiary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TipsCard(onDismiss: () -> Unit) {
    InsetGroup(header = "用之前先知道") {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tip(Icons.Rounded.CheckCircle, ios.blue, "勾上的是要删的", "没勾的会留下。每组照片、每个场景都能单独删，不会连带别的。")
            Tip(Icons.Rounded.Delete, ios.gray, "删了先进“最近删除”", "30 天内能恢复。清空“最近删除”才真正腾出空间。")
            Tip(Icons.Rounded.Lock, ios.green, "照片不出手机", "分析全在手机里完成，D图 没有联网权限。")
            Row {
                Spacer(Modifier.weight(1f))
                TextAction("知道了", onDismiss, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, tint: Color, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.label)
            Text(text, style = IosType.footnote, color = ios.secondary)
        }
    }
}
