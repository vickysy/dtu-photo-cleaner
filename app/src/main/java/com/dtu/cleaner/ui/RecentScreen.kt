package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.util.Fmt

/** 刚拍的：先现场打分（几秒钟），好了就是一张照片格子，低分的已经勾上 */
@Composable
fun RecentScreen(vm: MainViewModel) {
    val r = vm.recent
    val session = r?.session
    if (session != null) {
        GridReviewScreen(vm, session, subtitle = r.label)
        return
    }
    BackHandler { vm.back() }
    IosPage(navBar = { IosNavBar("刚拍的", onBack = { vm.back() }) }, toast = vm.snackbar) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InsetGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (r == null || r.total == 0 && r.label.isEmpty()) "正在找刚拍的照片…" else "正在给${r.label}的照片打分",
                        style = IosType.headline, color = ios.label,
                    )
                    if (r != null && r.total > 0) {
                        Text("${Fmt.count(r.done)} / ${Fmt.count(r.total)}", style = IosType.subhead, color = ios.secondary)
                        IosProgress(r.done.toFloat() / r.total)
                    }
                    Text("看清不清楚、构图好不好看、有没有人闭眼，全在手机里算。", style = IosType.footnote, color = ios.secondary)
                }
            }
        }
    }
}
