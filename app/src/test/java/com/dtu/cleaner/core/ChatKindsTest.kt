package com.dtu.cleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天记录：用户手机里 1,559 张非相机图片按旧规则分出 239 张“聊天记录”，真的只有 28 张；
 * 错的是海报、书封、展厅照片、App 界面（左右都有字，看起来像气泡）。这里是那几种的简化版。
 */
class ChatKindsTest {
    companion object {
        /** 微信式聊天：左边气泡左沿对齐、右边气泡右沿对齐，一左一右错开、从不并排 */
        fun bubbles(times: Int = 1) = TextLayout(
            6, 3, 3, switches = 4, coverage = 0.15f, times = times,
            boxes = listOf(
                TextBox(170, 150, 520, 180, 8), TextBox(480, 220, 820, 250, 6), TextBox(170, 300, 600, 360, 20),
                TextBox(400, 420, 820, 450, 9), TextBox(170, 500, 450, 530, 5), TextBox(560, 600, 820, 630, 4),
            ),
        )

        private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun wechatGreenBubbleOnFlatBackground() {
        // 微信聊天：浅灰底 #EDEDED 占大半，绿气泡 #95EC69 占一些
        val chat = IntArray(1000) { if (it < 80) rgb(149, 236, 103) else if (it < 300) rgb(255, 255, 255) else rgb(237, 237, 237) }
        val (green, bg) = ScreenText.pixelStats(chat)
        assertTrue(green >= 5 && bg >= 30)
        assertTrue(TextLayout(5, 2, 2, wechatGreen = green, background = bg).wechatChat)
    }

    @Test
    fun otherGreensAreNotWechat() {
        // 书封的品牌绿、球场的草绿、图表的绿柱子
        for (c in listOf(rgb(47, 196, 160), rgb(76, 160, 70), rgb(76, 175, 80), rgb(115, 215, 133))) {
            assertEquals(0, ScreenText.pixelStats(IntArray(500) { c }).first)
        }
        // 有一点微信绿，但画面花（照片、海报），底色不平
        val busy = IntArray(1000) { i -> if (i < 30) rgb(149, 236, 103) else rgb(i % 251, (i * 7) % 253, (i * 13) % 255) }
        val (green, bg) = ScreenText.pixelStats(busy)
        assertTrue(!TextLayout(5, 2, 2, wechatGreen = green, background = bg).wechatChat)
    }

    @Test
    fun postersWithTextOnBothSidesAreNotChats() {
        // 海报、课程表：左右都有字、来回交替，但左右并排在同一行，也没有微信绿
        val poster = TextLayout(
            8, 4, 4, switches = 6, coverage = 0.2f, times = 0,
            boxes = listOf(
                TextBox(60, 200, 400, 240, 6), TextBox(600, 205, 940, 240, 6),
                TextBox(60, 300, 400, 340, 6), TextBox(600, 300, 940, 345, 6),
            ),
        )
        assertNull(ScreenText.classify("主讲嘉宾\n直播时间\n扫码预约\n福利多多", emptyList(), poster))
        assertTrue(!poster.bubbles)
    }

    @Test
    fun timestampsNeedBubbles() {
        assertEquals(ShotKind.CHAT, ScreenText.classify("到家了吗\n刚到\n早点休息", emptyList(), bubbles(times = 2)))
        // 文件列表：每行都有时间，但文件名和日期并排
        val files = TextLayout(
            6, 3, 3, switches = 5, coverage = 0.2f, times = 1,
            boxes = listOf(TextBox(40, 200, 400, 230, 5), TextBox(700, 200, 960, 230, 2), TextBox(40, 260, 400, 290, 5), TextBox(700, 260, 960, 290, 2)),
        )
        assertNull(ScreenText.classify("会议纪要\n前天 00:18\n工作总结\n前天 00:09", emptyList(), files))
    }

    @Test
    fun voiceInputWordsAloneAreNotChat() {
        // 抖音直播、得到、豆包的输入框都有“按住说话”
        assertNull(ScreenText.classify("直播间\n按住说话\n点赞", emptyList(), TextLayout(3, 1, 1)))
        assertEquals(ShotKind.CHAT, ScreenText.classify("[语音] 12\"\n好的", emptyList()))
    }

    @Test
    fun layoutRoundTrip() {
        val l = bubbles()
        val back = TextLayout.decode(TextLayout(l.blocks, l.left, l.right, l.switches, l.coverage, l.times, l.boxes, 37, 52).encode())!!
        assertEquals(37, back.wechatGreen)
        assertEquals(52, back.background)
        assertEquals(6, back.boxes.size)
        assertTrue(back.bubbles)
        // 旧版存的排布（没有位置、颜色）照样读得出来
        assertEquals(-1, TextLayout.decode("b=5;l=2;r=2;s=2;a=12")!!.wechatGreen)
    }
}
