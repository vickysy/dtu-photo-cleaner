package com.dtu.cleaner.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.dtu.cleaner.data.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 截图笔记：一串截图（看直播、上网课截的）
 * - 拼成长图：每张缩到同样宽度上下接起来，太长就分成几张，存进相册“D图笔记”；
 * - 分享：长图和整理好的文字一起发给得到、微信、备忘录。
 */
object NoteMaker {
    private const val WIDTH = 1080
    /** 一张长图最高这么多像素，再长很多 App 打不开，就分成几张 */
    private const val MAX_HEIGHT = 12_000
    private const val GAP = 12

    /** 拼长图，返回存进相册的几张图 */
    fun longImages(ctx: Context, shots: List<MediaItem>, title: String): List<Uri> {
        val out = ArrayList<Uri>()
        var page = ArrayList<Bitmap>()
        var height = 0
        fun flush() {
            if (page.isEmpty()) return
            out.add(save(ctx, stitch(page, height), "${title}-${out.size + 1}"))
            page.forEach { it.recycle() }
            page = ArrayList()
            height = 0
        }
        for (s in shots) {
            val bmp = decode(ctx, s) ?: continue
            if (height > 0 && height + GAP + bmp.height > MAX_HEIGHT) flush()
            height += (if (height > 0) GAP else 0) + bmp.height
            page.add(bmp)
        }
        flush()
        return out
    }

    private fun decode(ctx: Context, m: MediaItem): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, m.uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            if (w > WIDTH) d.setTargetSize(WIDTH, (info.size.height * WIDTH.toFloat() / w).roundToInt())
        }
    } catch (e: Exception) {
        null
    }

    private fun stitch(parts: List<Bitmap>, height: Int): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        var y = 0
        for (b in parts) {
            val h = (b.height * WIDTH.toFloat() / b.width).roundToInt()
            c.drawBitmap(b, null, Rect(0, y, WIDTH, y + h), null)
            y += h + GAP
        }
        return out
    }

    /** 文件名只留汉字、字母、数字，其余换成下划线，别太长 */
    private fun safeName(name: String): String =
        name.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").replace(Regex("_+"), "_").trim('_').take(40).ifEmpty { "截图笔记" }

    private fun save(ctx: Context, bmp: Bitmap, rawName: String): Uri {
        val name = safeName(rawName)
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/D图笔记/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("没法在相册里新建图片")
        try {
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        } finally {
            bmp.recycle()
        }
        return uri
    }

    fun title(startMs: Long, headline: String?): String {
        val d = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(startMs))
        return if (headline != null) "$headline（$d）" else "截图笔记 $d"
    }

    /** 分享图片（可以带文字）：微信、得到、备忘录都能收 */
    fun shareImages(ctx: Context, uris: List<Uri>, text: String?, chooserTitle: String) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        if (!text.isNullOrBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        ctx.startActivity(Intent.createChooser(i, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, text: String, chooserTitle: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
