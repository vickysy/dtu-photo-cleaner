package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.ShotKind
import com.dtu.cleaner.data.Library
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt

/** 分类相册里的一类 */
private class DocGroup(
    val title: String,
    val kinds: Set<ShotKind>,
    val note: String,
    val icon: ImageVector,
    /** 能设为好评 */
    val praiseable: Boolean = false,
    /** 清理用的类：一打开就在选择状态，3 天前的已勾上 */
    val cleanup: Boolean = false,
)

private val GROUPS = listOf(
    DocGroup("证件", setOf(ShotKind.ID), "身份证、银行卡、护照、驾驶证、社保卡、营业执照……永远不会建议删除。证件上的长串号码在手机里也是打了码存的。", Icons.Rounded.Badge),
    DocGroup("聊天记录", setOf(ShotKind.CHAT), "微信聊天截图（认绿色气泡），和中间带时间的聊天记录。学员、客户夸你的，可以设为好评单独收着。", Icons.Rounded.Forum, praiseable = true),
    DocGroup("文章与文件", setOf(ShotKind.LONG_TEXT, ShotKind.DOC), "文章、笔记、PPT 截图，和拍的文件、书页、课件。", Icons.AutoMirrored.Rounded.Article),
    DocGroup("票据行程", setOf(ShotKind.TICKET), "车票、机票、门票、发票、小票。", Icons.AutoMirrored.Rounded.ReceiptLong),
    DocGroup("订单物流", setOf(ShotKind.ORDER), "订单、退款、物流截图。", Icons.Rounded.LocalShipping),
    DocGroup("用完就没用的", setOf(ShotKind.PICKUP, ShotKind.CODE, ShotKind.PAY, ShotKind.QR), "取件码、验证码、付款码、二维码。3 天前的已经勾选。", Icons.Rounded.QrCode2, cleanup = true),
)

private fun groupsOf(lib: Library): List<Pair<DocGroup, List<MediaItem>>> =
    GROUPS.map { g -> g to lib.docKinds.entries.filter { it.value in g.kinds }.mapNotNull { lib.byId[it.key] }.sortedByDescending { it.timeMs } }
        .filter { it.second.isNotEmpty() }

private fun open(vm: MainViewModel, g: DocGroup) = vm.openDocGroup(g.title, g.kinds, g.note, g.praiseable, g.cleanup)

/** 分类相册：按图里的字分好类，找证件、聊天记录、文章都方便；点开就是一个相册 */
@Composable
fun DocsScreen(vm: MainViewModel) {
    val lib = vm.library ?: return
    BackHandler { vm.back() }
    val groups = remember(lib) { groupsOf(lib) }
    val praised = remember(lib, vm.praise) { vm.praise.mapNotNull { lib.byId[it] }.sortedByDescending { it.timeMs } }

    IosPage(navBar = { IosNavBar("文字与证件", onBack = { vm.back() }) }, toast = vm.snackbar) {
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Footnote(
                    "按图里的字分好类，像相册一样点开就能看，多选可以拼成长图分享，也能删。全在手机里识别，不上传。" +
                        if (lib.textsPending > 0) "\n还有 ${Fmt.count(lib.textsPending)} 张没识字，充电时会接着识。" else "",
                    Modifier.padding(bottom = 8.dp),
                )
            }
            if (groups.isEmpty() && praised.isEmpty()) item { EmptyState(if (lib.textsPending > 0) "还在识字，晚点再来看" else "没有找到带字的截图和照片") }
            val tiles: List<Triple<String, List<MediaItem>, () -> Unit>> =
                (if (praised.isNotEmpty()) listOf(Triple("好评", praised) { vm.openPraise() }) else emptyList()) +
                    groups.map { (g, items) -> Triple(g.title, items) { open(vm, g) } }
            tiles.chunked(2).forEachIndexed { i, pair ->
                item(key = "row$i") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (title, items, onClick) ->
                            Column(Modifier.weight(1f).cardSurface(18.dp).iosPress(onClick = onClick).padding(8.dp)) {
                                Mosaic(items.take(4))
                                Spacer(Modifier.height(10.dp))
                                Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(title, style = IosType.headline, color = ios.label, maxLines = 1)
                                        if (title == "好评") Icon(Icons.Rounded.Star, null, tint = ios.yellow, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                                    }
                                    Text(
                                        "${Fmt.count(items.size)} 张 · ${Fmt.bytes(items.sumOf { it.size })}",
                                        style = IosType.footnote, color = ios.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 首页上的一排分类入口：好评、证件、聊天记录、文章与文件……点一下直接打开那个相册。
 * 用图标不用缩略图：首页上不露出证件照片。
 */
@Composable
fun DocShortcuts(vm: MainViewModel, lib: Library) {
    val groups = remember(lib) { groupsOf(lib) }
    val praisedCount = vm.praise.count { it in lib.byId }
    if (groups.isEmpty() && praisedCount == 0) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (praisedCount > 0) Shortcut(Icons.Rounded.Star, "好评", praisedCount, ios.yellowSoft, ios.yellow) { vm.openPraise() }
        groups.forEach { (g, items) -> Shortcut(g.icon, g.title, items.size, ios.brandSoft, ios.accent) { open(vm, g) } }
        Shortcut(Icons.Rounded.MoreHoriz, "全部分类", -1, ios.fill, ios.secondary) { vm.openDocs() }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, count: Int, bg: Color, tint: Color, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).iosPress(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = IosType.caption, color = ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count >= 0) Text(Fmt.count(count), style = IosType.caption2, color = ios.secondary, maxLines = 1)
    }
}

/** 首页一行说明：“证件 3 · 聊天记录 40 · 文章与文件 12” */
fun docsSummary(lib: Library): String {
    val counts = GROUPS.map { g -> g.title to lib.docKinds.values.count { it in g.kinds } }.filter { it.second > 0 }
    return if (counts.isEmpty()) "按图里的字分类：证件、聊天记录、文章与文件……"
    else counts.take(3).joinToString(" · ") { "${it.first} ${it.second}" }
}
