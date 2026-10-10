package com.dtu.cleaner.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dtu.cleaner.data.MediaItem

/**
 * 看大图时的“不看这个文件夹”：浏览器、视频 App 的缓存图、别人存进来的东西，删不掉也不想看到，
 * 确认后整个文件夹从轻了相册里消失（文件不删），以后也不分析；设置里能恢复。
 * [onHidden] 收到文件夹路径，页面据此把这个文件夹的图从当前列表拿掉。
 */
@Composable
fun rememberHideFolder(vm: MainViewModel, itemOf: (Long) -> MediaItem?, onHidden: (String) -> Unit): ViewerAction {
    var asking by remember { mutableStateOf<MediaItem?>(null) }
    asking?.let { m ->
        val label = m.bucket.ifEmpty { m.relPath.trimEnd('/').substringAfterLast('/') }.ifEmpty { "这个" }
        val n = vm.countIn(m.relPath)
        IosAlert(
            title = "不看「$label」这个文件夹？",
            message = "里面的 ${if (n > 0) "$n 项" else "图"}以后不会出现在轻了相册里，也不再分析。文件不会删除；在 设置 → 不看的文件夹 里能恢复。\n位置：${m.relPath}",
            actions = listOf(
                AlertAction("取消", AlertAction.Role.Cancel) { asking = null },
                AlertAction("不看了") {
                    asking = null
                    vm.hideFolder(m.relPath, label)
                    onHidden(m.relPath)
                },
            ),
            onDismiss = { asking = null },
        )
    }
    return ViewerAction(Icons.Rounded.VisibilityOff, { "不看这个文件夹" }) { id -> asking = itemOf(id) }
}
