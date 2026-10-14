package com.dtu.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtu.cleaner.BuildConfig
import com.dtu.cleaner.util.BackgroundRun
import com.dtu.cleaner.util.Fmt

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val st by vm.settings.collectAsState()
    var askReanalyze by remember { mutableStateOf(false) }
    var askReport by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 允许后台运行：从系统设置回来时重新看一下
    var bgAllowed by remember { mutableStateOf(BackgroundRun.allowed(ctx)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { bgAllowed = BackgroundRun.allowed(ctx) }
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 70 } }
    BackHandler { vm.back() }

    IosPage(
        navBar = { IosNavBar("设置", onBack = { vm.back() }, showTitle = collapsed, divider = collapsed) },
        toast = vm.snackbar,
    ) {
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { LargeTitle("设置") }
            item {
                InsetGroup(header = "相似照片", footer = "两张照片在这个时间内拍的、画面又像，才算一组。") {
                    Choice("拍摄间隔", listOf(30 to "30秒", 120 to "2分钟", 600 to "10分钟"), st.gapSec) { v -> vm.updateSettings { it.copy(gapSec = v) } }
                    Choice("多像算一组", listOf(0 to "很像", 1 to "标准", 2 to "宽松"), st.strictness) { v -> vm.updateSettings { it.copy(strictness = v) } }
                    Choice("每组保留", listOf(1 to "1张", 2 to "2张", 3 to "3张"), st.keepPerGroup, divider = false) { v -> vm.updateSettings { it.copy(keepPerGroup = v) } }
                }
            }
            item {
                InsetGroup(header = "废片与截图", footer = "模糊判定“宽松”只挑完全拍废的，“严格”会多列一些拿不准的出来。") {
                    Choice("模糊判定", listOf(0 to "宽松", 1 to "标准", 2 to "严格"), st.blurSensitivity) { v -> vm.updateSettings { it.copy(blurSensitivity = v) } }
                    Choice("截图预选", listOf(7 to "7天前", 30 to "30天前", 90 to "90天前"), st.screenshotDays) { v -> vm.updateSettings { it.copy(screenshotDays = v) } }
                    Choice("大视频", listOf(100 to "100MB", 300 to "300MB", 1000 to "1GB"), st.bigVideoMb, divider = false) { v -> vm.updateSettings { it.copy(bigVideoMb = v) } }
                }
            }
            item {
                InsetGroup(
                    header = "删除方式",
                    footer = if (st.useTrash) "先放进轻了相册的“最近删除”（首页最下面），30 天内可以恢复；手机自带相册里不一定看得到。要马上腾出空间，记得去那里点“全部删除”。"
                    else "删除后直接腾出空间，但找不回来。",
                ) {
                    Choice("删除后", listOf(1 to "进回收站", 0 to "直接删除"), if (st.useTrash) 1 else 0) { v ->
                        vm.updateSettings { it.copy(useTrash = v == 1) }
                    }
                    IosRow(
                        "最近删除", subtitle = "误删了在这里恢复",
                        value = if (vm.trash.isEmpty()) "空的" else "${Fmt.count(vm.trash.size)} 项",
                        divider = false,
                        onClick = { vm.refreshTrash(); vm.push(Screen.Trash) },
                    )
                }
            }
            item {
                val (aes, tech) = remember(vm.library) { vm.tasteVotes() }
                val w = com.dtu.cleaner.core.Taste.from(aes, tech).aestheticWeight
                val label = when {
                    aes + tech == 0 -> "还没学到"
                    w > 0.4f -> "你更看重构图好看"
                    w < 0.3f -> "你更看重拍得清楚"
                    else -> "清楚和好看差不多一样重要"
                }
                InsetGroup(
                    header = "学到的口味",
                    footer = "你在相似照片里改了推荐（留下的不是推荐的那张）再删除时，会记下来，以后挑照片更像你。" +
                        if (aes + tech > 0) "已经学了 ${aes + tech} 次。" else "",
                ) {
                    IosRow(label, subtitle = "构图美感占 ${(w * 100).toInt()}%，其余看清晰、曝光、有没有人闭眼", divider = aes + tech > 0)
                    if (aes + tech > 0) IosActionRow("恢复默认口味", divider = false) { vm.resetTaste() }
                }
            }
            item {
                InsetGroup(
                    header = "自动整理",
                    footer = "小米、荣耀、华为等手机切到别的 App 一会儿就会清掉后台任务。允许后台运行后，分析就不会中断；" +
                        "小米手机还要在 设置 → 应用设置 → 应用管理 → 轻了相册 → 省电策略 里选“无限制”。没跑也没关系，分析好的都存着，打开 App 会接着分析。",
                ) {
                    IosRow(
                        "后台运行",
                        subtitle = if (bgAllowed) "已允许，切到别的 App 也会接着分析" else "没允许：切走一会儿可能被系统清掉",
                        value = if (bgAllowed) "已允许" else "去允许",
                        onClick = if (bgAllowed) null else ({ BackgroundRun.request(ctx) }),
                    )
                    IosSwitchRow("充电时分析新照片", "每天找个充电的时候，把新拍的照片分析好", st.autoAnalyze) { v ->
                        vm.updateSettings { it.copy(autoAnalyze = v) }
                    }
                    IosSwitchRow("每月提醒我清理", "能腾出 500 MB 以上时发一条通知", st.monthlyReminder, divider = false) { v ->
                        vm.updateSettings { it.copy(monthlyReminder = v) }
                    }
                }
            }
            item {
                // 不看的文件夹：浏览器、视频 App 的缓存图这类删不掉又不想看到的
                InsetGroup(
                    header = "不看的文件夹",
                    footer = "这些文件夹里的图不显示、也不分析，文件本身没动。浏览器、视频 App 的缓存（名字以 . 开头、带 cache 或 thumbnail 的文件夹）本来就不算。" +
                        "看大图时点“不看这个文件夹”就能加进来。",
                ) {
                    if (vm.hiddenFolders.isEmpty()) {
                        IosRow("还没有", subtitle = "都在正常显示", divider = false)
                    } else {
                        vm.hiddenFolders.forEachIndexed { i, rel ->
                            IosRow(
                                rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel }, subtitle = rel, value = "恢复",
                                divider = i < vm.hiddenFolders.size - 1, onClick = { vm.unhideFolder(rel) },
                            )
                        }
                    }
                }
            }
            item {
                InsetGroup(
                    header = "分享与反馈",
                    footer = "用微信发安装包时，对方收到的文件名结尾可能多出“.1”，在文件管理里把 .1 删掉就能安装。",
                ) {
                    IosActionRow("把轻了相册发给朋友") { vm.shareApp(ctx) }
                    IosActionRow("反馈问题", divider = false) { askReport = true }
                }
            }
            item {
                InsetGroup(header = "其他") {
                    IosActionRow("导出诊断数据") { vm.exportDiagnostics() }
                    IosActionRow("让保留过的照片重新参与建议") { vm.resetReviewed() }
                    IosActionRow("重新分析全部照片", color = ios.red, divider = false) { askReanalyze = true }
                }
            }
            item {
                Text(
                    "轻了相册 ${BuildConfig.VERSION_NAME}\n所有分析都在手机里完成。没有联网权限，照片不会离开手机。\n" +
                        "升级：拿到新安装包直接安装就行，分析结果和设置都会保留。\n" +
                        "美感评分使用 idealo 开源的 NIMA 模型（Apache-2.0），LiteRT 版本来自 litert-community。",
                    style = IosType.footnote, color = ios.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
            }
        }
    }

    if (askReport) {
        IosAlert(
            title = "发送问题报告？",
            message = "报告里有：手机型号、系统版本、App 版本、照片和视频的数量、出错记录。没有照片，也没有文件名。生成后用微信或 QQ 发给把轻了相册分享给你的人。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { askReport = false },
                AlertAction("生成并发送") { askReport = false; vm.sendReport(ctx) },
            ),
            onDismiss = { askReport = false },
        )
    }

    if (askReanalyze) {
        IosAlert(
            title = "重新分析全部照片？",
            message = "会清掉现有的分析结果从头再来。一般不需要。",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { askReanalyze = false },
                AlertAction("重新分析", AlertAction.Role.Destructive) { askReanalyze = false; vm.reanalyzeAll(); vm.back() },
            ),
            onDismiss = { askReanalyze = false },
        )
    }
}

/** 设置行：左边标题，右边分段选择器 */
@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, value: T, divider: Boolean = true, onSelect: (T) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = IosType.body, color = ios.label, modifier = Modifier.weight(1f))
            SegmentedControl(options, value, onSelect, Modifier.width(if (options.size == 2) 180.dp else 204.dp))
        }
        if (divider) IosDivider()
    }
}
