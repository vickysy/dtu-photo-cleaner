package com.dtu.cleaner.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options

/** 用系统缩略图显示（相册 App 自己也用它），比解码原图快得多，视频也适用。 */
data class Thumb(val uri: Uri, val px: Int)

class ThumbFetcher(private val ctx: Context, private val data: Thumb) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bmp = ctx.contentResolver.loadThumbnail(data.uri, Size(data.px, data.px), null)
        return DrawableResult(BitmapDrawable(ctx.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val ctx: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(ctx, data)
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "${data.uri}#${data.px}"
}
