package com.dtu.cleaner.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val size: Long,
    /** 拍摄时间（毫秒）；没有就用修改时间 */
    val timeMs: Long,
    /** DATE_MODIFIED（秒），用来判断分析结果是否过期 */
    val modified: Long,
    /** 已按方向旋转过的宽高 */
    val width: Int,
    val height: Int,
    val bucket: String,
    val relPath: String,
    val mime: String,
    val durationMs: Long,
    val favorite: Boolean,
    val isScreenshot: Boolean,
    /** 只有回收站里的项目才有：到期自动删除的时间（秒） */
    val expiresSec: Long = 0,
    /** 视频码率（bit/s）；照片和不知道的是 0 */
    val bitrate: Long = 0,
) {
    val maxSide: Int get() = maxOf(width, height)

    /**
     * 相机拍的：DCIM/Camera、DCIM/100XXX、大疆这类相机文件夹，或者 DCIM 根目录里 IMG_/VID_ 开头的。
     * 小红书（Camera_XHS…）、剪映、美图之类存到 DCIM 里的不算：它们不是“拍糊了”，也不该进场景精选。
     */
    val isCameraShot: Boolean get() {
        val p = relPath.lowercase()
        if (!p.startsWith("dcim/")) return false
        val rest = p.removePrefix("dcim/")
        if (rest.isEmpty()) return CAMERA_NAME.containsMatchIn(name)
        return rest.startsWith("camera/") || CAMERA_DIR.containsMatchIn(rest) || "dji" in rest
    }

    /** 从哪来的：相机、截图、微信、小红书、得到…… */
    val source: String get() {
        if (isScreenshot) return "截图"
        if (isCameraShot) return "相机"
        val hay = "$relPath|$name".lowercase()
        return SOURCES.firstOrNull { (_, keys) -> keys.any { it in hay } }?.first ?: bucket.ifEmpty { "其他" }
    }

    companion object {
        private val CAMERA_NAME = Regex("^(IMG|VID|PXL|MVIMG)_\\d", RegexOption.IGNORE_CASE)
        private val CAMERA_DIR = Regex("^\\d{3}[a-z_]*/")
        private val SOURCES = listOf(
            "微信" to listOf("weixin", "micromsg", "wechat"),
            "QQ 音乐" to listOf("qqmusic"),
            "QQ" to listOf("/qq", "qq_images", "qqfile"),
            "小红书" to listOf("xhs", "xiaohongshu", "小红书"),
            "得到" to listOf("getnotes", "get_note", "得到"),
            "微信读书" to listOf("weread"),
            "抖音" to listOf("douyin", "aweme", "抖音"),
            "剪映" to listOf("jianying", "剪映", "capcut"),
            "美图" to listOf("meitu", "美图"),
            "大众点评" to listOf("dianping"),
            "美团" to listOf("meituan"),
            "浏览器" to listOf("browser"),
            "互传" to listOf("honor share", "huawei share"),
            "下载" to listOf("download"),
        )
    }
}

object MediaStoreSource {
    private val SCREEN_KEYWORDS = listOf(
        "screenshot", "screen_shot", "screencap", "screenrecord", "screen_record", "screen recorder",
        "截屏", "截图", "录屏", "屏幕录制",
    )

    fun collection(video: Boolean): Uri =
        if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val PROJECTION = arrayOf(
        MediaColumns._ID,
        MediaColumns.DISPLAY_NAME,
        MediaColumns.SIZE,
        MediaColumns.DATE_TAKEN,
        MediaColumns.DATE_ADDED,
        MediaColumns.DATE_MODIFIED,
        MediaColumns.WIDTH,
        MediaColumns.HEIGHT,
        MediaColumns.BUCKET_DISPLAY_NAME,
        MediaColumns.RELATIVE_PATH,
        MediaColumns.MIME_TYPE,
        MediaColumns.DURATION,
        MediaColumns.IS_FAVORITE,
        MediaColumns.ORIENTATION,
        MediaColumns.BITRATE,
    )

    fun query(ctx: Context, video: Boolean): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val coll = collection(video)
        ctx.contentResolver.query(coll, PROJECTION, null, null, "${MediaColumns.DATE_TAKEN} DESC")?.use { c ->
            read(c, coll, video, false, out)
        }
        return out
    }

    /** 安卓系统回收站里的照片和视频（Android 11+ 的标准回收站，30 天后自动删除）。 */
    fun queryTrashed(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (video in listOf(false, true)) {
            val coll = collection(video)
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaColumns.DATE_EXPIRES} ASC")
            }
            try {
                ctx.contentResolver.query(coll, PROJECTION + MediaColumns.DATE_EXPIRES, args, null)?.use { c ->
                    read(c, coll, video, true, out)
                }
            } catch (_: Exception) {
                // 个别系统不支持查询回收站，当作空的
            }
        }
        return out.sortedBy { it.expiresSec }
    }

    private fun read(c: Cursor, coll: Uri, video: Boolean, trashed: Boolean, out: MutableList<MediaItem>) {
        val iId = c.getColumnIndexOrThrow(MediaColumns._ID)
        val iName = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        val iSize = c.getColumnIndexOrThrow(MediaColumns.SIZE)
        val iTaken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        val iAdded = c.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        val iMod = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        val iW = c.getColumnIndexOrThrow(MediaColumns.WIDTH)
        val iH = c.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        val iBucket = c.getColumnIndexOrThrow(MediaColumns.BUCKET_DISPLAY_NAME)
        val iPath = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
        val iMime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        val iDur = c.getColumnIndexOrThrow(MediaColumns.DURATION)
        val iFav = c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)
        val iOri = c.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        val iExp = if (trashed) c.getColumnIndex(MediaColumns.DATE_EXPIRES) else -1
        val iRate = c.getColumnIndex(MediaColumns.BITRATE)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            val name = c.getString(iName) ?: ""
            val bucket = c.getString(iBucket) ?: ""
            val path = c.getString(iPath) ?: ""
            val modified = c.getLong(iMod)
            // 没有拍摄时间（截图、下载的图）时用文件修改时间；导入时间在换机/恢复备份后都会变成同一天
            var time = c.getLong(iTaken)
            if (time <= 0) time = modified * 1000
            if (time <= 0) time = c.getLong(iAdded) * 1000
            var w = c.getInt(iW)
            var h = c.getInt(iH)
            val ori = c.getInt(iOri)
            if (ori == 90 || ori == 270) { val t = w; w = h; h = t }
            val hay = "$bucket|$path|$name".lowercase()
            out.add(
                MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(coll, id),
                    isVideo = video,
                    name = name,
                    size = c.getLong(iSize),
                    timeMs = time,
                    modified = modified,
                    width = w,
                    height = h,
                    bucket = bucket,
                    relPath = path,
                    mime = c.getString(iMime) ?: "",
                    durationMs = if (video) c.getLong(iDur) else 0,
                    favorite = c.getInt(iFav) == 1,
                    isScreenshot = SCREEN_KEYWORDS.any { it in hay },
                    expiresSec = if (iExp >= 0) c.getLong(iExp) else 0,
                    bitrate = if (video && iRate >= 0) c.getLong(iRate) else 0,
                ),
            )
        }
    }
}
