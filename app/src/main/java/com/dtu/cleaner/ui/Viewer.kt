package com.dtu.cleaner.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dtu.cleaner.data.MediaItem

class ViewerEntry(val item: MediaItem, val info: String, val tags: List<String> = emptyList())

/** 看大图时底部的一排按钮（分享、好评、删除）；文字和颜色可以随当前这张变 */
class ViewerAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: (Long) -> String,
    val tint: (Long) -> Color = { Color.White },
    val onClick: (Long) -> Unit,
)

/**
 * 全屏看大图：左右滑切换，双指/双击放大检查清晰度。
 * [isMarked] 返回 null 时不显示“保留/删除”按钮。
 */
@Composable
fun Viewer(
    entries: List<ViewerEntry>,
    start: Int,
    isMarked: (Long) -> Boolean?,
    onToggle: (Long) -> Unit,
    onDismiss: (Int) -> Unit,
    actions: List<ViewerAction> = emptyList(),
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (entries.size - 1).coerceAtLeast(0))) { entries.size }
    BackHandler { onDismiss(pager.currentPage) }
    // 直接盖在页面上（不用 Dialog：部分系统上 Dialog 铺不满全屏，底部按钮会被导航栏挡住）
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // 吃掉空白处的点击，免得点穿到下面的页面
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { entries[it].item.id }) { page ->
            val e = entries[page]
            if (e.item.isVideo) VideoPage(e.item, active = pager.currentPage == page)
            else ZoomableImage(e.item)
        }
        val e = entries.getOrNull(pager.currentPage)
        // 顶部：完成 + 第几张
        Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().height(44.dp)) {
            Text(
                "完成", style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).iosPress { onDismiss(pager.currentPage) }.padding(8.dp),
            )
            Text("${pager.currentPage + 1} / ${entries.size}", style = IosType.headline, color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        if (e != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (e.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { e.tags.forEach { SmartTag(it) } }
                Text(e.info, style = IosType.footnote, color = Color.White.copy(alpha = 0.85f))
                if (actions.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        actions.forEach { a ->
                            Column(
                                Modifier.weight(1f).iosPress { a.onClick(e.item.id) }.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(a.icon, null, tint = a.tint(e.item.id), modifier = Modifier.size(24.dp))
                                Text(a.label(e.item.id), style = IosType.caption, color = Color.White)
                            }
                        }
                    }
                }
                val marked = isMarked(e.item.id)
                if (marked != null) {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(if (marked) Danger else Color.White.copy(alpha = 0.18f))
                            .iosPress { onToggle(e.item.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (marked) "将删除这张 · 点这里改为保留" else "保留这张 · 点这里改为删除",
                            style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = (s - 1f) * size.width / 2f
        val my = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(item.uri).size(2400).build(),
        contentDescription = item.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(item.id) {
                // 只有双指或已放大时才吃掉手势，否则留给左右翻页
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        if (pointers >= 2 || scale > 1.05f) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            scale = (scale * z).coerceIn(1f, 6f)
                            offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + p, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean) {
    var playing by remember(item.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing && active) {
            val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9f
            // 用 TextureView 播放：VideoView（SurfaceView）画在窗口下层，会被上面的黑色背景盖住
            AndroidView(
                factory = { c -> VideoTexture(c, item.uri) },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f),
            )
        } else {
            ThumbImage(item, 1080, Modifier.fillMaxSize(), ContentScale.Fit, Color.Black)
            Icon(
                Icons.Default.PlayCircle, "播放", tint = Color.White,
                modifier = Modifier.size(72.dp).clip(CircleShape).clickable { playing = true },
            )
        }
    }
}

/** TextureView + MediaPlayer，循环播放，点一下暂停/继续。 */
private class VideoTexture(ctx: android.content.Context, private val uri: android.net.Uri) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
        setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.start() } }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        player = MediaPlayer().apply {
            try {
                setDataSource(context, uri)
                setSurface(s)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            } catch (_: Exception) {
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }
}
