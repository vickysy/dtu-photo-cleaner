package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.core.ShotKind
import com.dtu.cleaner.data.Library
import com.dtu.cleaner.util.Fmt

/** “文字与证件”里的一类 */
private class DocGroup(val title: String, val kinds: Set<ShotKind>, val note: String)

private val GROUPS = listOf(
    DocGroup("证件", setOf(ShotKind.ID), "身份证、银行卡、护照、驾驶证、社保卡、营业执照……D图 永远不会建议删除证件。证件上的长串号码在手机里也是打了码存的。"),
    DocGroup("聊天记录", setOf(ShotKind.CHAT), "聊天截图。"),
    DocGroup("文章文字", setOf(ShotKind.LONG_TEXT), "长文、笔记、PPT 这类字很多的截图。连续截的一串可以在“截图笔记”里拼成长图、整理成文字。"),
    DocGroup("文件照片", setOf(ShotKind.DOC), "用相机拍的文件、白板、课件、书页。"),
    DocGroup("票据行程", setOf(ShotKind.TICKET), "车票、机票、门票、发票、小票。"),
    DocGroup("订单物流", setOf(ShotKind.ORDER), "订单、退款、物流截图。"),
    DocGroup("用完就没用的", setOf(ShotKind.PICKUP, ShotKind.CODE, ShotKind.PAY, ShotKind.QR), "取件码、验证码、付款码、二维码。3 天前的已经勾选。"),
)

/** 文字与证件：按图里的字分好类，找证件、聊天记录、拍的文件都方便 */
@Composable
fun DocsScreen(vm: MainViewModel) {
    val lib = vm.library ?: return
    BackHandler { vm.back() }
    val groups = remember(lib) {
        GROUPS.map { g -> g to lib.docKinds.entries.filter { it.value in g.kinds }.mapNotNull { lib.byId[it.key] }.sortedByDescending { it.timeMs } }
            .filter { it.second.isNotEmpty() }
    }

    IosPage(navBar = { IosNavBar("文字与证件", onBack = { vm.back() }) }, toast = vm.snackbar) {
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Footnote(
                    "D图 认了每张截图和照片里的字，按内容分好类，找东西方便。全在手机里识别，不上传。" +
                        if (lib.textsPending > 0) "\n还有 ${Fmt.count(lib.textsPending)} 张没识字，充电时会接着识。" else "",
                    Modifier.padding(bottom = 8.dp),
                )
            }
            if (groups.isEmpty()) item { EmptyState(if (lib.textsPending > 0) "还在识字，晚点再来看" else "没有找到带字的截图和照片") }
            groups.chunked(2).forEachIndexed { i, pair ->
                item(key = "row$i") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (g, items) ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(ios.card)
                                    .iosPress { vm.openDocGroup(g.title, g.kinds, g.note) }.padding(8.dp),
                            ) {
                                Mosaic(items.take(4))
                                Spacer(Modifier.height(10.dp))
                                Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 4.dp)) {
                                    Text(g.title, style = IosType.headline, color = ios.label, maxLines = 1)
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

/** 首页一行说明：“证件 3 · 聊天记录 40 · 文章文字 12” */
fun docsSummary(lib: Library): String {
    val counts = GROUPS.map { g -> g.title to lib.docKinds.values.count { it in g.kinds } }.filter { it.second > 0 }
    return if (counts.isEmpty()) "按图里的字分类：证件、聊天记录、文件照片……"
    else counts.take(3).joinToString(" · ") { "${it.first} ${it.second}" }
}
