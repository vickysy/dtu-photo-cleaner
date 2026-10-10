package com.dtu.cleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.2/2.3：视频判断、截图识字、文字区分不同页、拍摄地点切场景、口味 */
class V22Test {
    private fun vf(frames: Int = 5, dark: Int = 0, plain: Int = 0, blurry: Int = 0, hashes: LongArray = LongArray(frames) { it * 0x1111L }, color: Int = 100) =
        VideoFeatures(frames, dark, plain, blurry, hashes, ByteArray(48) { color.toByte() })

    private fun video(id: Long, durMs: Long, f: VideoFeatures, timeMs: Long = id * 1_000_000, camera: Boolean = true, w: Int = 3840, size: Long = 100_000_000) =
        VideoInfo(id, timeMs, durMs, size, w, w * 9 / 16, camera, false, f)

    @Test
    fun darkAndPlainVideos() {
        assertEquals(listOf(VideoReason.DARK), VideoRules.reasons(video(1, 30_000, vf(dark = 5))))
        assertEquals(listOf(VideoReason.PLAIN), VideoRules.reasons(video(2, 30_000, vf(plain = 4))))
        // 只有一帧黑（比如中间走进了隧道）不算
        assertTrue(VideoRules.reasons(video(3, 30_000, vf(dark = 1))).isEmpty())
        // 全程模糊只是提示
        val blur = VideoRules.reasons(video(4, 30_000, vf(blurry = 5)))
        assertEquals(listOf(VideoReason.BLURRY), blur)
        assertFalse(blur.first().confident)
    }

    @Test
    fun duplicateVideoKeepsCameraOriginal() {
        val h = longArrayOf(0x0F0F0F0F0F0F0F0FL, 0x00FF00FF00FF00FFL, 0x1234567812345678L, 0x7777777777777777L, 0x0000FFFF0000FFFFL)
        val original = video(1, 12_000, vf(hashes = h), camera = true, w = 3840, size = 80_000_000)
        // 微信转存的：分辨率低、画面一样、时长差一点点
        val wechat = video(2, 12_300, vf(hashes = h.map { it xor 0x3L }.toLongArray()), camera = false, w = 1280, size = 6_000_000)
        val other = video(3, 12_100, vf(hashes = LongArray(5) { -1L shl it }), camera = true)
        val d = VideoRules.duplicates(listOf(original, wechat, other))
        assertEquals(mapOf(2L to 1L), d)
    }

    @Test
    fun takesGroupsNearbySimilarVideos() {
        val h = LongArray(5) { 0x5555555555555555L }
        val a = video(1, 20_000, vf(hashes = h), timeMs = 0)
        val b = video(2, 25_000, vf(hashes = h), timeMs = 5 * 60_000)
        val far = video(3, 20_000, vf(hashes = h), timeMs = 3 * 3_600_000)
        val takes = VideoRules.takes(listOf(a, b, far))
        assertEquals(1, takes.size)
        assertEquals(listOf(1L, 2L), takes[0].map { it.id })
    }

    @Test
    fun compressionEstimate() {
        // 手机上典型的 4K 视频：51 Mbps、10 秒 ≈ 64 MB → 1080p 8 Mbps ≈ 10 MB
        val est = VideoRules.estimateSize(10_000, 51_000_000, VideoRules.targetBitrate(false, 30f))
        assertEquals(10_160_000L, est)
        assertTrue(VideoRules.worthCompressing(64_000_000, est))
        // 本来就小的不压
        assertFalse(VideoRules.worthCompressing(15_000_000, 10_000_000))
    }

    @Test
    fun screenshotKinds() {
        assertEquals(ShotKind.PICKUP, ScreenText.classify("【菜鸟驿站】您的包裹已到站，取件码 3-2-1045", emptyList()))
        assertEquals(ShotKind.CODE, ScreenText.classify("您的验证码为 482913，5分钟内有效", emptyList()))
        assertEquals(ShotKind.ID, ScreenText.classify("中华人民共和国 居民身份证 签发机关", emptyList()))
        assertEquals(ShotKind.QR, ScreenText.classify("扫一扫加入群聊", listOf("https://weixin.qq.com/g/abc")))
        assertNull(ScreenText.classify("今天天气不错", emptyList()))
        // 证件不能被当成可删的
        assertTrue(ShotKind.ID.important && !ShotKind.ID.disposable)
    }

    @Test
    fun differentDocumentPagesAreNotSimilar() {
        val page1 = ScreenText.signature("第一章 总则 第一条 为了规范管理，提高效率，根据有关规定，制定本办法。第二条 本办法适用于全体员工。")
        val page2 = ScreenText.signature("第三章 考核 第十条 年度考核分为优秀、合格、不合格三个等次，考核结果作为奖惩依据。")
        val page1Again = ScreenText.signature("第一章 总则 第一条 为了规范管理，提高效率，根据有关规定，制定本办法。第二条 本办法适用于全体员工")
        assertTrue(ScreenText.differentPages(page1, page2))
        assertFalse(ScreenText.differentPages(page1, page1Again))
        // 字太少（风景照里的路牌）不参与判断
        assertFalse(ScreenText.differentPages(ScreenText.signature("出口"), page2))
    }

    @Test
    fun noteDropsStatusBarAndRepeatedLines() {
        val note = ScreenText.buildNote(
            "直播笔记",
            listOf("14:32\n95%\n老师讲：复利的本质\n第一点 时间", "14:35\n老师讲：复利的本质\n第二点 耐心"),
        )
        assertTrue("第一点 时间" in note && "第二点 耐心" in note)
        assertEquals(1, Regex("老师讲：复利的本质").findAll(note).count())
        assertFalse("14:32" in note || "95%" in note)
    }

    @Test
    fun screenshotSessions() {
        val shots = (0 until 5).map { ShotSessions.Shot(it.toLong(), it * 60_000L, 1080, 2400) } +
            (0 until 2).map { ShotSessions.Shot(100L + it, 5 * 3_600_000L + it * 60_000L, 1080, 2400) }
        val s = ShotSessions.split(shots)
        assertEquals(1, s.size)
        assertEquals(5, s[0].ids.size)
    }

    private fun photo(id: Long, minute: Long, lat: Double = Double.NaN, lng: Double = Double.NaN) = PhotoInfo(
        id, minute * 60_000, 4000, 3000, 3_000_000, false,
        ImageFeatures(id * 7919, 0L, ByteArray(48) { 100 }, 900f, 900f, 120f, 50f, 0.01f, 0.01f, 200f, 2000f, 3f, 0.3f),
        null, 5f, null, lat, lng,
    )

    @Test
    fun scenesSplitWhenLocationJumps() {
        // 同一个小时里拍了两个地方：西湖 12 张、10 公里外的灵隐寺 12 张，中间只隔 5 分钟
        val a = (0 until 12).map { photo(it.toLong(), it.toLong(), 30.2590, 120.1388) }
        val b = (0 until 12).map { photo(100L + it, 17L + it, 30.2420, 120.0960) }
        assertEquals(listOf(12, 12), SceneDetector.segment(a + b).map { it.size })
        // 没有地点的照片照旧只按时间切
        val noLoc = (0 until 24).map { photo(it.toLong(), it.toLong()) }
        assertEquals(1, SceneDetector.segment(noLoc).size)
    }

    @Test
    fun tasteMovesSlowly() {
        assertEquals(Taste.DEFAULT, Taste.from(0, 0).aestheticWeight, 1e-6f)
        assertTrue(Taste.from(10, 0).aestheticWeight > Taste.DEFAULT)
        assertTrue(Taste.from(0, 10).aestheticWeight < Taste.DEFAULT)
        // 一两票几乎不动
        assertTrue(Taste.from(1, 0).aestheticWeight - Taste.DEFAULT < 0.05f)
        assertTrue(Taste.from(1000, 0).aestheticWeight <= 0.6f)
    }
}

/** 视频场景精选 */
class VideoScenesTest {
    private fun clip(id: Long, startMin: Long, durSec: Long, hash: Long, aes: Float, blurry: Int = 0, color: Int = 100, fav: Boolean = false) = VideoInfo(
        id, startMin * 60_000, durSec * 1000, durSec * 6_000_000, 3840, 2160, true, fav,
        VideoFeatures(5, 0, 0, blurry, LongArray(5) { hash }, ByteArray(48) { color.toByte() }, aes),
    )

    @Test
    fun segmentsByGapBetweenClips() {
        val a = (0 until 5).map { clip(it.toLong(), it * 3L, 60, it * 999L, 5f) }          // 0..12 分钟，每段 1 分钟
        val b = (0 until 3).map { clip(10L + it, 120L + it * 2, 30, it * 777L, 5f) }       // 两小时后只有 3 段：不够
        val c = (0 until 4).map { clip(20L + it, 300L + it * 10, 30, it * 555L, 5f) }      // 间隔 10 分钟：算一个场景
        assertEquals(listOf(5, 4), VideoScenes.segment(a + b + c).map { it.size })
    }

    @Test
    fun keepsLongClearPrettyAndDifferentClips() {
        // 8 段：两个画面（A、B），A 画面里有一段又长又好看，一段很短，一段全程模糊
        val clips = listOf(
            clip(1, 0, 45, 0L, 6.2f),                       // A：长、好看 → 应该留
            clip(2, 2, 40, 0L, 6.1f),                       // A：和 1 几乎一样 → 不该再留
            clip(3, 4, 4, 0L, 6.0f),                        // A：只有 4 秒
            clip(4, 6, 40, 0L, 5.0f, blurry = 5),           // A：全程模糊
            clip(5, 8, 35, -1L, 5.6f, color = 200),         // B：另一个画面 → 应该留
            clip(6, 10, 30, -1L, 5.4f, color = 200),
            clip(7, 12, 20, -1L, 5.0f, color = 200),
            clip(8, 14, 25, 0x0F0F0F0FL, 4.0f, color = 40),
        )
        val sc = VideoScenes.build(clips, 2)
        assertEquals(listOf(1L, 5L), sc.keep)
        assertTrue(3L !in sc.keep && 4L !in sc.keep)
    }

    @Test
    fun favoritesAlwaysKept() {
        val clips = (0 until 6).map { clip(it.toLong(), it.toLong(), 30, it * 31L, 5f + it * 0.1f, fav = it == 0) }
        assertTrue(0L in VideoScenes.build(clips, 2).keep)
    }
}

/** 文字与证件：分类、打码、标题 */
class DocKindsTest {
    @Test
    fun chatScreenshots() {
        assertEquals(ShotKind.CHAT, ScreenText.classify("张三 撤回了一条消息\n好的", emptyList()))
        // 微信绿气泡、底色平
        assertEquals(ShotKind.CHAT, ScreenText.classify("在吗\n在\n明天几点\n九点", emptyList(), TextLayout(8, 4, 3, switches = 4, coverage = 0.3f, wechatGreen = 40, background = 55)))
        // 光是左右交替不算（海报、设置页也这样）
        assertNull(ScreenText.classify("在吗\n在\n明天几点\n九点", emptyList(), TextLayout(8, 4, 3, switches = 4, coverage = 0.3f)))
        assertNull(ScreenText.classify("在吗\n在", emptyList(), TextLayout(2, 1, 1)))
    }

    @Test
    fun cameraPhotosOnlyIdDocAndTicket() {
        val page = "第一章 总则 ".repeat(30)
        assertEquals(ShotKind.DOC, ScreenText.classify(page, emptyList(), TextLayout(12, 0, 0, 0, 0.2f), camera = true, paper = 0.9f))
        assertEquals(ShotKind.ID, ScreenText.classify("中华人民共和国 居民身份证", emptyList(), camera = true))
        // 相机拍到路边的“取件”招牌，不算取件码截图
        assertNull(ScreenText.classify("菜鸟驿站 取件", emptyList(), camera = true))
        assertEquals(ShotKind.TICKET, ScreenText.classify("增值税电子普通发票 价税合计 壹佰元整 开票日期 2026年10月1日", emptyList(), TextLayout(6, 0, 0, 0, 0.3f), camera = true, paper = 0.7f))
        // 试卷上的“考场号 座位号”不是车票，字多就算文件照片
        val exam = "四年级数学期末测试卷 姓名 班级 考场号 座位号 " + "一、填空题 每空1分 ".repeat(12)
        assertEquals(ShotKind.DOC, ScreenText.classify(exam, emptyList(), TextLayout(20, 0, 0, 0, 0.3f), camera = true, paper = 0.8f))
    }

    @Test
    fun idNumbersAreMasked() {
        val masked = ScreenText.maskNumbers("公民身份号码 11010519491231002X")
        assertEquals("公民身份号码 11**************2X", masked)
        // 短数字（日期、金额）不动
        assertEquals("2026年10月 100元", ScreenText.maskNumbers("2026年10月 100元"))
    }

    @Test
    fun statusBarIsNotATitle() {
        assertEquals("直播：复利的本质", ScreenText.headline("12:05會nD\n直播：复利的本质\n第一点"))
        assertTrue(ScreenText.cleanLines("14:32 5G ◎ ⊕\n老师讲：复利").none { it.startsWith("14:32") })
    }
}

/** 真实相册里分错过的几种：点单页面、界面截图、试卷、文章里提到“护照” */
class DocKindsStrictTest {
    @Test
    fun orderingPageIsNotChat() {
        // 咖啡点单：左边商品、右边价格，排布很像聊天气泡
        val menu = "SHORT咖啡\n拿铁\n¥18\n燕麦拿铁\n¥22\n美式\n¥15\n加入购物车\n月售 300+"
        assertNull(ScreenText.classify(menu, emptyList(), TextLayout(10, 5, 5)))
    }

    @Test
    fun appScreensAreNotArticles() {
        // D图 自己的界面：字不少，但都是零散的短标签
        val ui = (1..30).joinToString("\n") { listOf("相似照片", "32.2 GB", "相册已分析", "共 7,049 张", "刷新", "建议清理", "废片", "没有发现")[it % 8] }
        assertNull(ScreenText.classify(ui, emptyList()))
        val article = (1..8).joinToString("\n") { "复利的本质是时间，越早开始投资，时间带来的复合增长就越明显，这是第${it}段。" }
        assertEquals(ShotKind.LONG_TEXT, ScreenText.classify(article, emptyList()))
    }

    @Test
    fun chatNeedsRealEvidence() {
        // 时间行要配上对齐的气泡；光有时间不算（文件列表也有“前天 00:18”）
        assertEquals(ShotKind.CHAT, ScreenText.classify("昨天 21:30\n到家了吗\n刚到", emptyList(), ChatKindsTest.bubbles()))
        assertNull(ScreenText.classify("昨天 21:30\n到家了吗\n刚到", emptyList()))
        // 普通两栏页面，没有聊天的字、行也不短
        val twoColumns = "这是一段比较长的商品介绍文字，讲的是咖啡豆的产地和烘焙方式\n另一段很长的介绍文字，讲的是牛奶的来源和口感特点"
        assertNull(ScreenText.classify(twoColumns, emptyList(), TextLayout(8, 3, 3)))
    }

    @Test
    fun articleMentioningPassportIsNotId() {
        val text = (1..6).joinToString("\n") { "出国旅行前要准备好护照和签证，提前了解目的地的入境要求和天气情况，第${it}条。" }
        assertEquals(ShotKind.LONG_TEXT, ScreenText.classify(text, emptyList()))
        // 打过码的身份证号照样认得出（重新分类时用的是打码后的字）
        assertEquals(ShotKind.ID, ScreenText.classify("姓名 张三\n11**************19", emptyList(), camera = true))
    }

    @Test
    fun ticketsNeedTwoClues() {
        // 只有“出发”一个词（比如旅行游记）不算
        assertNull(ScreenText.classify("明天出发去海边", emptyList()))
        assertEquals(ShotKind.TICKET, ScreenText.classify("G1234 车次\n座位号 05车12A\n出发 08:30", emptyList()))
        assertEquals(ShotKind.TICKET, ScreenText.classify("电子客票行程单", emptyList()))
    }
}

/** 真实相册里“文件照片”“聊天记录”错得最多的两种 */
class DocKindsCoverageTest {
    private val bookText = (1..10).joinToString("\n") { "第${it}行，书页上的一段很长很长的文字内容，用来模拟拍到的书" }

    @Test
    fun photoWithABookInTheCornerIsNotADocument() {
        // 咖啡、早餐旁边露出半本书或电脑屏幕：画面大部分不是纸
        assertNull(ScreenText.classify(bookText, emptyList(), TextLayout(10, 0, 0, 0, 0.2f), camera = true, paper = 0.25f))
        // 展厅里拍展板：墙是白的，但字只占一小块
        assertNull(ScreenText.classify(bookText, emptyList(), TextLayout(10, 0, 0, 0, 0.06f), camera = true, paper = 0.6f))
        // 把书页拍满：算文件照片
        assertEquals(ShotKind.DOC, ScreenText.classify(bookText, emptyList(), TextLayout(10, 0, 0, 0, 0.3f), camera = true, paper = 0.8f))
        // 颜色网格：白纸 vs 咖啡
        assertEquals(1f, ScreenText.paperFraction(ByteArray(48) { 230.toByte() }), 1e-6f)
        assertEquals(0f, ScreenText.paperFraction(ByteArray(48) { i -> if (i % 3 == 0) 150.toByte() else 90.toByte() }), 1e-6f)
    }

    @Test
    fun photoGridsAndPostersAreNotChats() {
        // D图 界面那种照片格子：只有分数角标，没有汉字字块
        assertNull(ScreenText.classify("4.3\n4.1\n5.6\n4.3\n5.2\n4.0", emptyList(), TextLayout(0, 0, 0, 0, 0.05f)))
        // 左右都有字，但没有来回交替（两栏海报）
        assertNull(ScreenText.classify("活动时间\n报名方式\n活动地点\n联系电话", emptyList(), TextLayout(8, 4, 4, switches = 1, coverage = 0.2f)))
        // 真聊天：左右来回，还有微信绿气泡
        assertEquals(ShotKind.CHAT, ScreenText.classify("明天几点\n九点\n带伞吗\n要带", emptyList(), TextLayout(8, 4, 4, switches = 5, coverage = 0.25f, wechatGreen = 30, background = 50)))
    }
}
