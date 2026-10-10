package com.dtu.cleaner.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 鸿蒙上见过：浏览器看视频的缓存图被系统相册当成照片，一下子多出几千张、删不掉 */
class FoldersTest {
    @Test
    fun realAlbumsAreKept() {
        for (p in listOf(
            "DCIM/Camera/", "DCIM/", "Pictures/Screenshots/", "Pictures/WeiXin/", "Download/", "Movies/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "HONOR Share/", "Pictures/轻了笔记/",
        )) assertFalse(p, Folders.isJunk(p))
    }

    @Test
    fun cachesAndHiddenFoldersAreNotPhotos() {
        for (p in listOf(
            "Download/.cache/", "Pictures/.thumbnails/", "DCIM/.thumbnails/", "UCDownloads/VideoData/cache/",
            "Android/data/com.example.browser/files/", "Android/obb/x/", "QQBrowser/video/.video_cache/",
            "Movies/thumbs/", "browser/VideoCache/123/",
        )) assertTrue(p, Folders.isJunk(p))
    }

    @Test
    fun hiddenFolderCoversItsSubfolders() {
        val hidden = setOf("Pictures/SomeApp/")
        assertTrue(Folders.excluded("Pictures/SomeApp/", hidden))
        assertTrue(Folders.excluded("Pictures/SomeApp/2026/", hidden))
        assertFalse(Folders.excluded("Pictures/SomeAppOther/", hidden))
        assertFalse(Folders.excluded("Pictures/", hidden))
    }
}
