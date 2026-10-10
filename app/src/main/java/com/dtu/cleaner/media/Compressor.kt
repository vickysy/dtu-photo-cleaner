package com.dtu.cleaner.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dtu.cleaner.App
import com.dtu.cleaner.analysis.KeepAliveService
import com.dtu.cleaner.core.VideoRules
import com.dtu.cleaner.data.LibraryBuilder
import com.dtu.cleaner.data.MediaItem
import com.dtu.cleaner.util.AppLog
import com.dtu.cleaner.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频压缩：全在手机上转码（硬件编码），不上传。
 * 4K 转 1080p（或保留 4K）、H.265；压好的视频放在原视频旁边（同一个文件夹、同样的拍摄时间和地点），
 * 文件名后面加“_压缩”。原视频不动，等用户看过、确认后才放进“最近删除”。
 */
@OptIn(UnstableApi::class)
object Compressor {
    data class State(
        val running: Boolean = false,
        val current: MediaItem? = null,
        /** 当前这个的进度 0..100 */
        val percent: Int = 0,
        val done: Int = 0,
        val total: Int = 0,
        /** 这一轮压好的：原视频 id */
        val finished: List<Long> = emptyList(),
        val failed: List<Pair<MediaItem, String>> = emptyList(),
    )

    private val state = MutableStateFlow(State())
    val progress: StateFlow<State> = state.asStateFlow()
    private var job: Job? = null
    private const val TAG = "compress"

    fun start(app: App, items: List<MediaItem>, keep4k: Boolean, onEach: () -> Unit) {
        if (job?.isActive == true || items.isEmpty()) return
        state.value = State(running = true, total = items.size)
        // Transformer 要在有 Looper 的线程上用：放在主线程，转码本身在硬件编解码器里跑，不卡界面
        job = app.scope.launch(Dispatchers.Main) {
            KeepAliveService.acquire(app, TAG)
            try {
                for ((i, item) in items.withIndex()) {
                    if (!isActive) break
                    state.value = state.value.copy(current = item, percent = 0, done = i)
                    KeepAliveService.update(app, "正在压缩视频", "第 ${i + 1} / ${items.size} 个", i, items.size)
                    try {
                        val outId = compressOne(app, item, keep4k)
                        app.db.putCompressed(item.id, outId)
                        state.value = state.value.copy(finished = state.value.finished + item.id)
                        onEach()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.event("压缩失败（${Fmt.bytes(item.size)}，${item.width}×${item.height}）", e)
                        state.value = state.value.copy(failed = state.value.failed + (item to (e.message ?: e.javaClass.simpleName)))
                    }
                }
            } finally {
                state.value = state.value.copy(running = false, current = null, done = state.value.finished.size + state.value.failed.size)
                KeepAliveService.release(app, TAG)
                File(app.cacheDir, "compress").listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** 压一个：先压到 App 自己的临时文件，再放进相册。返回新视频在相册里的 id */
    private suspend fun compressOne(app: App, src: MediaItem, keep4k: Boolean): Long {
        val dir = File(app.cacheDir, "compress").apply { mkdirs() }
        val tmp = File(dir, "${src.id}.mp4")
        tmp.delete()
        // 临时文件 + 放进相册的那份，要两倍空间；再留 1 GB 余量
        val need = VideoRules.estimateSize(src.durationMs, src.bitrate, VideoRules.targetBitrate(keep4k, 60f)) * 2 + 1_000_000_000L
        if (StatFs(app.cacheDir.path).availableBytes < need) error("手机空间不够，先清空“最近删除”再压")

        val meta = withContext(Dispatchers.IO) { readMeta(app, src) }
        val result = transcode(app, src, keep4k, meta, tmp)
        if (result.fileSizeBytes <= 0 && tmp.length() <= 0) error("压缩结果是空的")
        // 核对：分辨率、时长都要对得上，不然作废（原视频不动）
        withContext(Dispatchers.IO) { verify(app, src, keep4k, tmp) }
        if (tmp.length() >= src.size) {
            tmp.delete()
            error("压完反而更大，跳过")
        }
        return withContext(Dispatchers.IO) { publish(app, src, tmp).also { tmp.delete() } }
    }

    private fun verify(ctx: Context, src: MediaItem, keep4k: Boolean, file: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val expectShort = min(min(src.width, src.height), if (keep4k) 2160 else 1080)
            if (min(w, h) < expectShort * 0.9f) { file.delete(); error("压出来只有 ${w}×${h}，比预期小，作废了") }
            if (src.durationMs > 0 && kotlin.math.abs(dur - src.durationMs) > 1500) { file.delete(); error("压出来的时长不对，作废了") }
        } finally {
            runCatching { r.release() }
        }
    }

    private class Meta(val fps: Float, val lat: Float?, val lng: Float?)

    private fun readMeta(ctx: Context, src: MediaItem): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, src.uri)
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
            val fps = if (frames != null && src.durationMs > 0) frames * 1000f / src.durationMs else 30f
            // 形如 "+31.2304+121.4737/"
            val loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val m = loc?.let { Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
            Meta(fps, m?.groupValues?.get(1)?.toFloatOrNull(), m?.groupValues?.get(2)?.toFloatOrNull())
        } catch (e: Exception) {
            Meta(30f, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** 编码器能不能编这么大（横着编，竖拍靠旋转标记） */
    private fun canEncode(mime: String, longSide: Int, shortSide: Int): Boolean =
        EncoderUtil.getSupportedEncoders(mime).any { info ->
            runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(longSide, shortSide) }.getOrDefault(false)
        }

    private suspend fun transcode(ctx: Context, src: MediaItem, keep4k: Boolean, meta: Meta, out: File): ExportResult {
        // 短边缩到 1080（或 2160）；横拍竖拍都按显示方向算
        val target = if (keep4k) 2160 else 1080
        val w = src.width
        val h = src.height
        val shortSide = min(min(w, h), target)
        val longSide = if (min(w, h) > 0) (max(w, h).toFloat() * shortSide / min(w, h) / 2).roundToInt() * 2 else shortSide * 16 / 9
        // 优先 H.265；这台手机的 H.265 编码器编不了这么大，就用 H.264（体积大一些）；都不行就不压，免得压出一个很糊的
        val mime = when {
            canEncode(MimeTypes.VIDEO_H265, longSide, shortSide) -> MimeTypes.VIDEO_H265
            canEncode(MimeTypes.VIDEO_H264, longSide, shortSide) -> MimeTypes.VIDEO_H264
            else -> error("这台手机的视频编码器不支持 ${longSide}×${shortSide}，没压")
        }
        val bitrate = VideoRules.targetBitrate(keep4k, meta.fps).let { if (mime == MimeTypes.VIDEO_H265) it else (it * 1.5f).toInt() }
        val effects = if (w > 0 && h > 0 && min(w, h) > target) {
            listOf(Presentation.createForHeight(if (w >= h) shortSide else longSide))
        } else emptyList()

        // 不让编码器自己“降级”分辨率：宁可失败也不要压出一个小视频
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(false)
            .build()
        // 写进新文件：拍摄时间、地点和原视频一样，相册里排的位置不变
        val muxer = InAppMuxer.Factory.Builder()
            .setMetadataProvider { entries ->
                entries.removeAll { it is Mp4TimestampData }
                val t = Mp4TimestampData.unixTimeToMp4TimeSeconds(src.timeMs)
                entries.add(Mp4TimestampData(t, t))
                if (meta.lat != null && meta.lng != null) {
                    entries.removeAll { it is Mp4LocationData }
                    entries.add(Mp4LocationData(meta.lat, meta.lng))
                }
            }
            .build()
        val item = EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(src.uri))
            .setEffects(Effects(emptyList(), effects))
            .build()

        return suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(mime)
                .setEncoderFactory(encoder)
                .setMuxerFactory(muxer)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(item, out.absolutePath)
            // 每半秒更新一次进度
            val ticker = ProgressHolder()
            val poll = (ctx.applicationContext as App).scope.launch(Dispatchers.Main) {
                while (isActive && cont.isActive) {
                    if (transformer.getProgress(ticker) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        state.value = state.value.copy(percent = ticker.progress)
                    }
                    delay(500)
                }
            }
            cont.invokeOnCancellation {
                poll.cancel()
                transformer.cancel()
            }
        }
    }

    /** 把压好的临时文件放进相册：和原视频同一个文件夹、同样的拍摄时间 */
    private fun publish(ctx: Context, src: MediaItem, file: File): Long {
        val cr = ctx.contentResolver
        val name = src.name.substringBeforeLast('.') + LibraryBuilder.COMPRESSED_MARK + ".mp4"
        val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, src.relPath.ifEmpty { "DCIM/Camera/" })
            put(MediaStore.MediaColumns.DATE_TAKEN, src.timeMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = cr.insert(coll, values) ?: error("没法在相册里新建文件")
        try {
            cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } ?: error("没法写入相册")
            cr.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, src.timeMs)
            }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return ContentUris.parseId(uri)
    }

    /** 不要压缩版：删掉自己建的文件（不用系统确认） */
    fun deleteOutputs(ctx: Context, outputs: List<MediaItem>): Int =
        outputs.sumOf { runCatching { ctx.contentResolver.delete(it.uri, null, null) }.getOrDefault(0) }
}
