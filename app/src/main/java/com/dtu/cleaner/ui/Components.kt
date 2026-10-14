package com.dtu.cleaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.Fmt
import com.dtu.cleaner.util.Thumb

@Composable
fun ThumbImage(
    item: MediaItem,
    px: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    bg: Color = ios.fill,
) {
    AsyncImage(
        model = Thumb(item.uri, px),
        contentDescription = item.name,
        contentScale = contentScale,
        modifier = modifier.background(bg),
    )
}

/** 大图：从原图按需要的尺寸解码（系统缩略图在部分手机上太小，放大会糊） */
@Composable
fun PreviewImage(item: MediaItem, px: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(item.uri).size(px).build(),
        contentDescription = item.name,
        contentScale = contentScale,
        modifier = modifier.background(ios.fill),
    )
}

/** 照片上的原因标签：坏原因用红底，好的用绿底，其余灰底 */
@Composable
fun SmartTag(text: String) {
    val good = text in setOf("最佳", "推荐", "保留原图", "已收藏", "精华")
    val bad = text in setOf("模糊", "更模糊", "有人闭眼", "过曝", "偏暗", "太暗", "误拍", "重复", "压缩副本", "分辨率低", "没那张好看")
    PhotoBadge(text, bg = when {
        good -> Keep.copy(alpha = 0.9f)
        bad -> Danger.copy(alpha = 0.9f)
        else -> Color.Black.copy(alpha = 0.45f)
    })
}

private val overlayText = TextStyle(
    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f),
)

/**
 * “照片”App 风格的方格：点一下勾选/取消，点右上角放大镜或长按看大图。勾选 = 要删除。
 * 左上角原因标签，右上角放大镜，右下角勾选圈，左下角视频时长或附加信息。
 * 没有勾选圈的时候（只是看图）点一下就是看大图，不显示放大镜。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoCell(
    item: MediaItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    footer: String? = null,
    px: Int = 320,
    labelBg: Color? = null,
    /** false = 只是看图（分类相册没进入选择状态），不显示勾选圈 */
    selectable: Boolean = true,
    aspect: Float = 1f,
    labelIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Box(
        modifier.aspectRatio(aspect)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onToggle, onLongClick = onOpen,
            ),
    ) {
        ThumbImage(item, px, Modifier.fillMaxSize())
        if (selectable && selected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.22f)))
        // 标签太长时截掉，别盖住右上角的放大镜
        if (label != null) Box(Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp, end = if (selectable) 28.dp else 4.dp)) {
            when {
                labelIcon != null -> PhotoBadge(label, icon = labelIcon, iconTint = Gold)
                labelBg != null -> PhotoBadge(label, bg = labelBg)
                else -> SmartTag(label)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.isVideo) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                Text(Fmt.duration(item.durationMs), style = overlayText)
            }
            if (footer != null) Text((if (item.isVideo) " · " else "") + footer, style = overlayText)
        }
        if (selectable) {
            SelectCircle(selected, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            ZoomButton(onOpen, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** 格子右上角的放大镜：点它看大图。按的范围比图标大一圈，好点中 */
@Composable
fun ZoomButton(onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.ZoomIn) {
    Box(
        modifier.size(32.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, "看大图", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** 方格网格：每行 [cols] 张，间距 2dp（和“照片”App 一样紧凑） */
@Composable
fun <T> PhotoGridRows(items: List<T>, cols: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** 美感分（1..10）四舍五入到一位小数：显示、配色、统计都用它，保证“看到的分数”和颜色一致 */
fun aesRounded(score: Float): Float = kotlin.math.round(score * 10f) / 10f

fun aesText(score: Float) = "%.1f".format(aesRounded(score))

/** 低分标红的界线（含）：显示为 4.3 分及以下 */
const val AES_LOW = 4.3f
/** 高分标绿的界线（含）：显示为 5.5 分及以上 */
const val AES_HIGH = 5.5f

fun aesIsLow(score: Float) = aesRounded(score) <= AES_LOW + 1e-4f

fun aesColor(score: Float): Color = when {
    aesIsLow(score) -> Danger.copy(alpha = 0.9f)
    aesRounded(score) >= AES_HIGH - 1e-4f -> Keep.copy(alpha = 0.9f)
    else -> Color.Black.copy(alpha = 0.5f)
}
