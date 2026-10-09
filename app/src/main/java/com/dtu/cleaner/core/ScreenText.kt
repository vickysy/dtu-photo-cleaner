package com.dtu.cleaner.core

/**
 * 图里的字（离线 OCR 识别出来的）能告诉我们这张图是什么：
 * 取件码、验证码、付款码这类用完就没用了；证件、票据要留着；聊天记录、文章、拍的文件方便集中找。
 * 截图和相机照片都会识字；相机照片只分证件、文件照片、票据这几类。
 */
enum class ShotKind(val label: String, val disposable: Boolean, val important: Boolean = false) {
    ID("证件", false, important = true),
    CHAT("聊天记录", false),
    LONG_TEXT("文章文字", false),
    DOC("文件照片", false),
    TICKET("票据行程", false),
    ORDER("订单物流", false),
    PAY("付款码", true),
    CODE("验证码", true),
    PICKUP("取件码", true),
    QR("二维码", true),
}

/**
 * 字块的排布（只数含汉字的字块，图上的数字角标、图标不算）：
 * [blocks] 字块数，[left] 靠左的气泡，[right] 靠右的气泡，[switches] 从上往下左右换边的次数，
 * [coverage] 所有字块占画面的比例 0..1（拍文件、书页时字铺满画面；咖啡旁露出半本书只占一小块）。
 */
class TextLayout(val blocks: Int, val left: Int, val right: Int, val switches: Int = 0, val coverage: Float = -1f) {
    /** 聊天记录：左右两边都有气泡，而且来回交替 */
    val chatLike: Boolean get() = blocks >= 5 && left >= 2 && right >= 2 && switches >= 2

    fun encode() = "b=$blocks;l=$left;r=$right;s=$switches;a=${(coverage * 100).toInt()}"

    companion object {
        fun decode(s: String?): TextLayout? {
            if (s.isNullOrEmpty()) return null
            val m = s.split(';').mapNotNull { kv -> kv.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }.toMap()
            return TextLayout(m["b"] ?: return null, m["l"] ?: 0, m["r"] ?: 0, m["s"] ?: 0, m["a"]?.let { it / 100f } ?: -1f)
        }
    }
}

object ScreenText {
    // 判断宁严勿宽：宁可少认几张，也不要把点单页面当聊天记录、把试卷当车票。

    /** 硬证据：出现就是证件 */
    private val ID_STRONG = listOf("居民身份证", "公民身份号码")
    /** 18 位身份证号（存的时候打过码的也认：前两位 + 星号 + 后两位） */
    private val ID_NUMBER = Regex("(?<![0-9])([1-9][0-9]{16}[0-9Xx]|[0-9]{2}\\*{12,16}[0-9Xx]{2})(?![0-9])")
    /** 卡证名称：还要整张图字不多（卡片、证照），长文章里提到“护照”不算 */
    private val ID_WEAK = listOf(
        "银行卡号", "护照", "驾驶证", "行驶证", "社会保障卡", "居民户口簿", "常住人口登记卡",
        "营业执照", "统一社会信用代码", "不动产权证", "房屋所有权证", "结婚证", "出生医学证明", "学生证", "工作证",
    )
    private val PAY_WORDS = listOf("付款码", "收款码", "向商家付款", "付款给商家")
    private val CODE_WORDS = listOf("验证码", "校验码", "动态码", "动态密码")
    private val PICKUP_STRONG = listOf("取件码", "取餐码", "提货码", "取货码")
    private val PICKUP_PLACES = listOf("驿站", "丰巢", "快递柜", "快递超市")
    private val PICKUP_CODE = Regex("\\d{1,3}-\\d{1,3}-\\d{3,5}")
    /** 一出现就是票据行程 */
    private val TICKET_STRONG = listOf("登机牌", "电子客票", "检票口", "12306", "价税合计", "电子发票", "行程单")
    /** 要两个以上同时出现 */
    private val TICKET_WEAK = listOf("车次", "座位号", "航班", "门票", "入场", "演出", "酒店", "发票", "小票", "收据", "合计金额", "出发", "到达")
    /** 相机拍的照片只认明确的票据词：试卷上也有“座位号”“考场”，不能算车票 */
    private val CAMERA_TICKET_WORDS = listOf("发票", "价税合计", "小票", "收据", "合计金额", "登机牌", "电子客票", "检票口", "门票")
    private val ORDER_STRONG = listOf("订单号", "订单编号", "运单号", "快递单号")
    private val ORDER_WEAK = listOf("实付", "退款", "物流", "已签收", "已发货", "待收货", "确认收货", "商品总价")
    private val CHAT_WORDS = listOf("撤回了一条消息", "[语音]", "[图片]", "[表情]", "[动画表情]", "按住说话", "按住 说话", "语音通话", "视频通话", "拍了拍", "以下为新消息")
    /** 聊天记录中间的时间分隔：“昨天 21:30”“星期三 10:12”“下午 3:05” */
    private val CHAT_TIME = Regex("^(昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|上午|下午|晚上|凌晨|中午|早上)\\s*\\d{1,2}[:：]\\d{2}$")
    /** 有价格的多半是商品、点单页面，不是聊天 */
    private val PRICE = Regex("[¥￥]\\s*\\d|\\d+(\\.\\d+)?元|月售|加入购物车|立即购买")

    private fun countIn(words: List<String>, t: String) = words.count { it in t }

    /**
     * [codes] 是图里识别出的二维码/条码内容；[camera] = 相机拍的照片（不是截图、不是 App 存的图）。
     */
    /**
     * 画面里“白纸”的比例 0..1：4×4 颜色网格里，浅色、不鲜艳的格子占多少。
     * 文件、书页、试卷大部分是纸；咖啡、展厅、海报、街景颜色丰富得多。
     */
    fun paperFraction(color: ByteArray): Float {
        if (color.size < 48) return -1f
        var n = 0
        for (i in 0 until 16) {
            val r = color[i * 3].toInt() and 0xFF
            val g = color[i * 3 + 1].toInt() and 0xFF
            val b = color[i * 3 + 2].toInt() and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mn >= 140 && mx - mn <= 45) n++
        }
        return n / 16f
    }

    /**
     * [paper] 是 [paperFraction]（相机照片用；不知道就是 -1，不认文件照片）。
     */
    fun classify(text: String, codes: List<String>, layout: TextLayout? = null, camera: Boolean = false, paper: Float = -1f): ShotKind? {
        val t = text.replace(" ", "").replace("\n", "")
        val coverage = layout?.coverage ?: -1f
        val lines = cleanLines(text)
        val chars = lines.sumOf { it.length }
        // 成段的文字：10 个字以上的长行占了大部分（界面截图都是零散的短标签）
        val longChars = lines.filter { it.length >= 10 }.sumOf { it.length }
        val paragraph = chars >= 150 && longChars >= chars * 0.6f
        val isId = ID_STRONG.any { it in t } || ID_NUMBER.containsMatchIn(t) ||
            (ID_WEAK.any { it in t } && chars < 400 && !paragraph)

        if (camera) {
            // 相机拍的：只认证件、票据、文件。路边招牌、菜单、包装上的零星字不算
            return when {
                isId -> ShotKind.ID
                CAMERA_TICKET_WORDS.any { it in t } && chars >= 30 && !paragraph && paper >= 0.3f -> ShotKind.TICKET
                // 文件照片：成段的字或者很多行，画面大部分是纸，字也占了相当一块
                // （咖啡、展厅、海报、街景里露出的字不算）
                chars >= 100 && (paragraph || lines.size >= 8) && paper >= 0.45f && coverage >= 0.12f -> ShotKind.DOC
                else -> null
            }
        }
        val hasPrice = PRICE.containsMatchIn(t)
        val shortLines = lines.isNotEmpty() && lines.count { it.length <= 20 } >= lines.size * 0.8f
        return when {
            isId -> ShotKind.ID
            PAY_WORDS.any { it in t } -> ShotKind.PAY
            CODE_WORDS.any { it in t } && Regex("\\d{4,8}").containsMatchIn(t) && chars < 300 -> ShotKind.CODE
            PICKUP_STRONG.any { it in t } || (PICKUP_PLACES.any { it in t } && PICKUP_CODE.containsMatchIn(t)) -> ShotKind.PICKUP
            TICKET_STRONG.any { it in t } || countIn(TICKET_WEAK, t) >= 2 && !paragraph -> ShotKind.TICKET
            ORDER_STRONG.any { it in t } || countIn(ORDER_WEAK, t) >= 2 -> ShotKind.ORDER
            CHAT_WORDS.any { it in t } || lines.any { CHAT_TIME.matches(it) } -> ShotKind.CHAT
            // 只靠排布：左右气泡来回交替、行都很短、没有价格
            layout != null && layout.chatLike && shortLines && !hasPrice -> ShotKind.CHAT
            codes.isNotEmpty() && chars < 150 -> ShotKind.QR
            // 文章：成段的长句子，字还得占画面相当一块（海报上的一句标语不算）
            paragraph && chars >= 200 && (coverage < 0f || coverage >= 0.2f) -> ShotKind.LONG_TEXT
            else -> null
        }
    }

    /** 证件里的长串数字（身份证号、卡号）不原样存：只留前两位和后两位 */
    fun maskNumbers(text: String): String =
        Regex("[0-9Xx]{6,}").replace(text) { m -> m.value.take(2) + "*".repeat(m.value.length - 4) + m.value.takeLast(2) }

    // ---------- 文字相似度：区分“两页不同的文件” ----------

    /** 文字的“指纹”：相邻两个字组成的词对（去掉空白和标点），排序去重 */
    fun signature(text: String): IntArray {
        val chars = text.filter { it.isLetterOrDigit() }
        if (chars.length < 2) return IntArray(0)
        val set = HashSet<Int>(chars.length)
        for (i in 0 until chars.length - 1) set.add(chars[i].code * 65599 + chars[i + 1].code)
        return set.toIntArray().also { it.sort() }
    }

    /** 两个指纹的重合度 0..1（Jaccard） */
    fun similarity(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var i = 0
        var j = 0
        var common = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { common++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return common.toFloat() / (a.size + b.size - common)
    }

    /** 字够多才拿来比：一页文件、一张 PPT。风景照里零星几个字不算 */
    const val MIN_SIG = 24

    /** 两张都是“满是字”的照片，而字基本对不上：是两页不同的东西，不算相似 */
    fun differentPages(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size < MIN_SIG || b.size < MIN_SIG) return false
        return similarity(a, b) < 0.3f
    }

    // ---------- 截图笔记 ----------

    /** 状态栏、导航栏里的零碎：时间、电量、信号 */
    private val NOISE = Regex("^(\\d{1,2}[:：]\\d{2}|\\d{1,3}%|[45]G|HD|WiFi|Wi-Fi|\\W{1,3})$", RegexOption.IGNORE_CASE)
    /** 以时间开头的短行：多半是状态栏（“12:05 ⊕ 🔔 5G”被识别成“12:05會nD”） */
    private val STATUS_BAR = Regex("^\\d{1,2}[:：]\\d{2}")

    fun cleanLines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { line ->
            line.length >= 2 && !NOISE.matches(line) && !(STATUS_BAR.containsMatchIn(line) && line.length <= 14) && readable(line)
        }

    /** 像话的行：汉字、字母、数字占多数（不是图标被识别成的乱码） */
    private fun readable(line: String): Boolean {
        val good = line.count { it.isLetterOrDigit() }
        return good >= line.length * 0.5f
    }

    /**
     * 把一组截图的文字整理成一篇笔记：按截图顺序，去掉状态栏零碎，
     * 前面截图里出现过的行（直播间标题、固定按钮、滚动重叠的部分）不再重复。
     */
    fun buildNote(title: String, pages: List<String>): String {
        val seen = HashSet<String>()
        val sb = StringBuilder(title).append("\n")
        pages.forEachIndexed { i, text ->
            val lines = cleanLines(text).filter { seen.add(it) }
            if (lines.isEmpty()) return@forEachIndexed
            sb.append("\n— ${i + 1} —\n")
            lines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** 一组截图的标题：第一张里第一行像样的文字 */
    fun headline(text: String): String? = cleanLines(text).firstOrNull { it.length in 4..30 }
}

/** 连续截的一串图（比如看直播截的），可以整理成一篇笔记 */
class ShotSession(val ids: List<Long>, val startMs: Long, val endMs: Long)

object ShotSessions {
    /**
     * 按时间切：相邻两张超过 [gapMs] 没截就算下一串；尺寸要一样（同一个 App、同一屏），至少 [minCount] 张。
     * [shots] 每项是 (id, 时间, 宽, 高)。
     */
    fun split(shots: List<Shot>, gapMs: Long = 10 * 60_000L, minCount: Int = 3): List<ShotSession> {
        val sorted = shots.sortedBy { it.timeMs }
        val out = ArrayList<ShotSession>()
        var cur = ArrayList<Shot>()
        fun flush() {
            if (cur.size >= minCount) out.add(ShotSession(cur.map { it.id }, cur.first().timeMs, cur.last().timeMs))
            cur = ArrayList()
        }
        for (s in sorted) {
            val prev = cur.lastOrNull()
            if (prev != null && (s.timeMs - prev.timeMs > gapMs || s.width != prev.width || s.height != prev.height)) flush()
            cur.add(s)
        }
        flush()
        return out.sortedByDescending { it.startMs }
    }

    class Shot(val id: Long, val timeMs: Long, val width: Int, val height: Int)
}
