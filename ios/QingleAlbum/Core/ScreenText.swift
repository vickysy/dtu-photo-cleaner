import Foundation

/**
 图里的字（手机上离线识别出来的）能告诉我们这张图是什么：
 取件码、验证码、付款码这类用完就没用了；证件、票据要留着；聊天记录、文章、拍的文件方便集中找。
 和安卓版 core/ScreenText.kt 同一套规则（宁严勿宽：宁可少认几张，也不要认错）。
 */
enum ShotKind: String, Codable, CaseIterable {
    case id = "证件", chat = "聊天记录", longText = "文章文字", doc = "文件照片", ticket = "票据行程", order = "订单物流"
    case pay = "付款码", code = "验证码", pickup = "取件码", qr = "二维码"

    /// 用完就没用了
    var disposable: Bool { [.pay, .code, .pickup, .qr].contains(self) }
    /// 永远不建议删
    var important: Bool { self == .id }
}

/// 一行含汉字的字在画面里的位置，千分比（0..1000），n 是汉字个数
struct TextBox: Codable {
    let l: Int, t: Int, r: Int, b: Int, n: Int
}

/**
 字的排布（只数含汉字的行，图上的数字角标、图标不算）：
 blocks 行数，left/right 靠左/靠右的，switches 从上往下左右换边的次数，coverage 字占画面的比例，
 times 居中的时间行（聊天记录中间的“昨天 21:30”），wechatGreen 微信绿气泡颜色占的千分比，background 最多的那种颜色占的百分比。
 不知道的记 -1。
 */
struct TextLayout: Codable {
    var blocks: Int
    var left: Int
    var right: Int
    var switches: Int
    var coverage: Float
    var times: Int
    var boxes: [TextBox]
    var wechatGreen: Int
    var background: Int

    /// 微信聊天：有微信绿气泡（#95EC69 一带），底色又很平。海报、书封、球场的绿都不是这个颜色
    var wechatChat: Bool { wechatGreen >= 5 && background >= 30 }

    /// 气泡排布（去掉标题栏和输入框）：左边的左沿对齐、右边的右沿对齐，而且左右两边从不并排在同一行
    /// （表格、设置页、海报的两栏都会并排）
    var bubbles: Bool {
        let body = boxes.filter { $0.t > 80 && $0.b < 920 }
        let l = body.filter { $0.l < 300 && $0.r < 780 }
        let r = body.filter { $0.l > 220 && $0.r > 700 && !($0.l < 300 && $0.r < 780) }
        if l.count + r.count < 3 { return false }
        let sideBySide = l.contains { a in r.contains { c in Float(min(a.b, c.b) - max(a.t, c.t)) > 0.3 * Float(min(a.b - a.t, c.b - c.t)) } }
        return !sideBySide && max(Self.aligned(l.map { $0.l }), Self.aligned(r.map { $0.r })) >= 0.75
    }

    /// 离中位数 40‰ 以内的比例
    private static func aligned(_ v: [Int]) -> Float {
        if v.count < 2 { return 0 }
        let m = v.sorted()[v.count / 2]
        return Float(v.filter { abs($0 - m) <= 40 }.count) / Float(v.count)
    }
}

enum ScreenText {
    private static let idStrong = ["居民身份证", "公民身份号码"]
    /// 18 位身份证号（打过码的也认：前两位 + 星号 + 后两位）
    private static let idNumber = try! NSRegularExpression(pattern: "(?<![0-9])([1-9][0-9]{16}[0-9Xx]|[0-9]{2}\\*{12,16}[0-9Xx]{2})(?![0-9])")
    /// 卡证名称：还要整张图字不多（卡片、证照），长文章里提到“护照”不算
    private static let idWeak = ["银行卡号", "护照", "驾驶证", "行驶证", "社会保障卡", "居民户口簿", "常住人口登记卡",
                                 "营业执照", "统一社会信用代码", "不动产权证", "房屋所有权证", "结婚证", "出生医学证明", "学生证", "工作证"]
    private static let payWords = ["付款码", "收款码", "向商家付款", "付款给商家"]
    private static let codeWords = ["验证码", "校验码", "动态码", "动态密码"]
    private static let pickupStrong = ["取件码", "取餐码", "提货码", "取货码"]
    private static let pickupPlaces = ["驿站", "丰巢", "快递柜", "快递超市"]
    private static let pickupCode = try! NSRegularExpression(pattern: "\\d{1,3}-\\d{1,3}-\\d{3,5}")
    private static let ticketStrong = ["登机牌", "电子客票", "检票口", "12306", "价税合计", "电子发票", "行程单"]
    private static let ticketWeak = ["车次", "座位号", "航班", "门票", "入场", "演出", "酒店", "发票", "小票", "收据", "合计金额", "出发", "到达"]
    /// 相机拍的只认明确的票据词：试卷上也有“座位号”“考场”，不能算车票
    private static let cameraTicketWords = ["发票", "价税合计", "小票", "收据", "合计金额", "登机牌", "电子客票", "检票口", "门票"]
    private static let orderStrong = ["订单号", "订单编号", "运单号", "快递单号"]
    private static let orderWeak = ["实付", "退款", "物流", "已签收", "已发货", "待收货", "确认收货", "商品总价"]
    /// 只在聊天记录里出现的字
    private static let chatWords = ["撤回了一条消息", "[语音]", "[图片]", "[表情]", "[动画表情]", "拍了拍", "以下为新消息"]
    /// 语音消息的时长：微信、QQ 的语音气泡只有一个“18″”（前面的喇叭图标常被认成零碎符号）。整行只有秒数加引号，文章、界面里几乎不会出现
    private static let voiceLine = try! NSRegularExpression(pattern: "^[^\\p{L}\\d]{0,4}\\d{1,2}\\s*[\"“”″＂'’]{1,2}$")
    /// 聊天记录中间的时间分隔：“昨天 21:30”“星期三 10:12”“下午 3:05”
    private static let chatTime = try! NSRegularExpression(pattern: "^(昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|上午|下午|晚上|凌晨|中午|早上)\\s*\\d{1,2}[:：]\\d{2}$")
    /// 居中的时间戳（去掉空格后整行匹配）：“10:12”“昨天21:30”“3月1日下午3:05”“2025/3/1 10:12”
    static let chatStamp = try! NSRegularExpression(pattern:
        "^((\\d{4}年)?\\d{1,2}月\\d{1,2}日|\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天])?(上午|下午|晚上|凌晨|中午|早上)?\\d{1,2}[:：]\\d{2}$")
    private static let digits4to8 = try! NSRegularExpression(pattern: "\\d{4,8}")

    static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    private static func count(_ words: [String], _ t: String) -> Int { words.filter { t.contains($0) }.count }

    static func isCJK(_ c: Character) -> Bool { c.unicodeScalars.contains { $0.value >= 0x4E00 && $0.value <= 0x9FFF } }

    /// 从缩小后的像素（RGBA，72 像素宽）算：微信绿气泡颜色占的千分比、最多的那种颜色占的百分比
    static func pixelStats(_ rgba: [UInt8]) -> (green: Int, background: Int) {
        let n = rgba.count / 4
        if n == 0 { return (-1, -1) }
        var green = 0
        var buckets: [Int: Int] = [:]
        for i in 0..<n {
            let r = Int(rgba[i * 4]), g = Int(rgba[i * 4 + 1]), b = Int(rgba[i * 4 + 2])
            if g >= 215 && (125...185).contains(r) && (85...130).contains(b) && g - r >= 45 { green += 1 }
            buckets[(r >> 4) << 8 | (g >> 4) << 4 | (b >> 4), default: 0] += 1
        }
        return (green * 1000 / n, (buckets.values.max() ?? 0) * 100 / n)
    }

    /// 画面里“白纸”的比例 0..1：4×4 颜色网格里浅色、不鲜艳的格子占多少（文件、书页大部分是纸）
    static func paperFraction(_ color: [UInt8]) -> Float {
        if color.count < 48 { return -1 }
        var n = 0
        for i in 0..<16 {
            let r = Int(color[i * 3]), g = Int(color[i * 3 + 1]), b = Int(color[i * 3 + 2])
            if min(r, g, b) >= 140 && max(r, g, b) - min(r, g, b) <= 45 { n += 1 }
        }
        return Float(n) / 16
    }

    /// codes 是图里识别出的二维码/条码内容；camera = 相机拍的照片；paper 是 paperFraction（相机照片用）
    static func classify(_ text: String, codes: [String], layout: TextLayout?, camera: Bool, paper: Float = -1) -> ShotKind? {
        let t = text.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "\n", with: "")
        let coverage = layout?.coverage ?? -1
        let lines = cleanLines(text)
        let chars = lines.reduce(0) { $0 + $1.count }
        // 成段的文字：10 个字以上的长行占了大部分（界面截图都是零散的短标签）
        let longChars = lines.filter { $0.count >= 10 }.reduce(0) { $0 + $1.count }
        let paragraph = chars >= 150 && Float(longChars) >= Float(chars) * 0.6
        let isId = idStrong.contains { t.contains($0) } || matches(idNumber, t) || (idWeak.contains { t.contains($0) } && chars < 400 && !paragraph)

        if camera {
            // 相机拍的：只认证件、票据、文件。路边招牌、菜单、包装上的零星字不算
            if isId { return .id }
            if cameraTicketWords.contains(where: { t.contains($0) }) && chars >= 30 && !paragraph && paper >= 0.3 { return .ticket }
            if chars >= 100 && (paragraph || lines.count >= 8) && paper >= 0.45 && coverage >= 0.12 { return .doc }
            return nil
        }
        if isId { return .id }
        if payWords.contains(where: { t.contains($0) }) { return .pay }
        if codeWords.contains(where: { t.contains($0) }) && matches(digits4to8, t) && chars < 300 { return .code }
        if pickupStrong.contains(where: { t.contains($0) }) || (pickupPlaces.contains(where: { t.contains($0) }) && matches(pickupCode, t)) { return .pickup }
        if ticketStrong.contains(where: { t.contains($0) }) || (count(ticketWeak, t) >= 2 && !paragraph) { return .ticket }
        if orderStrong.contains(where: { t.contains($0) }) || count(orderWeak, t) >= 2 { return .order }
        if chatWords.contains(where: { t.contains($0) }) { return .chat }
        if let layout, layout.wechatChat { return .chat }
        // 对方发来一大段字、再加几条语音：没有绿气泡、没有居中的时间，以前会被当成文章（和安卓 v2.8.5 一样）
        if text.components(separatedBy: "\n").filter({ matches(voiceLine, $0.trimmingCharacters(in: .whitespaces)) }).count >= 2 { return .chat }
        // 聊天中间的时间，再加上对齐的气泡、左右不并排（文件列表、日程表也有时间，但会并排）
        if let layout, layout.times > 0 || lines.contains(where: { matches(chatTime, $0) }), layout.bubbles { return .chat }
        if !codes.isEmpty && chars < 150 { return .qr }
        // 文章：成段的长句子，字还得占画面相当一块（海报上的一句标语不算）
        if paragraph && chars >= 200 && (coverage < 0 || coverage >= 0.2) { return .longText }
        return nil
    }

    /// 证件里的长串数字（身份证号、卡号）不原样存：只留前两位和后两位
    static func maskNumbers(_ text: String) -> String {
        let re = try! NSRegularExpression(pattern: "[0-9Xx]{6,}")
        var out = text
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)).reversed() {
            guard let r = Range(m.range, in: out) else { continue }
            let s = String(out[r])
            out.replaceSubrange(r, with: String(s.prefix(2)) + String(repeating: "*", count: s.count - 4) + String(s.suffix(2)))
        }
        return out
    }

    // ---------- 截图笔记 ----------

    /// 状态栏、导航栏里的零碎：时间、电量、信号
    private static let noise = try! NSRegularExpression(pattern: "^(\\d{1,2}[:：]\\d{2}|\\d{1,3}%|[45]G|HD|WiFi|Wi-Fi|\\W{1,3})$", options: .caseInsensitive)
    private static let statusBar = try! NSRegularExpression(pattern: "^\\d{1,2}[:：]\\d{2}")

    static func cleanLines(_ text: String) -> [String] {
        text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { line in
            line.count >= 2 && !matches(noise, line) && !(matches(statusBar, line) && line.count <= 14) && readable(line)
        }
    }

    /// 像话的行：汉字、字母、数字占多数（不是图标被识别成的乱码）
    private static func readable(_ line: String) -> Bool {
        Float(line.filter { $0.isLetter || $0.isNumber }.count) >= Float(line.count) * 0.5
    }

    /// 把一组截图的文字整理成一篇笔记：按顺序，去掉状态栏零碎，前面出现过的行（标题、固定按钮、滚动重叠的部分）不再重复
    static func buildNote(title: String, pages: [String]) -> String {
        var seen = Set<String>()
        var out = title + "\n"
        for (i, text) in pages.enumerated() {
            let lines = cleanLines(text).filter { seen.insert($0).inserted }
            if lines.isEmpty { continue }
            out += "\n— \(i + 1) —\n" + lines.joined(separator: "\n") + "\n"
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines) + "\n"
    }

    /// 一组截图的标题：第一张里第一行像样的文字
    static func headline(_ text: String) -> String? { cleanLines(text).first { (4...30).contains($0.count) } }
}

/// 连续截的一串图（看直播、上网课截的），可以拼成长图、整理成笔记
struct ShotSession: Identifiable {
    let ids: [String]
    let startMs: Int64
    let endMs: Int64
    var id: String { ids.first ?? "" }
}

enum ShotSessions {
    /// 按时间切：相邻两张超过 10 分钟没截就算下一串；尺寸要一样（同一个 App、同一屏），至少 3 张
    static func split(_ shots: [(id: String, timeMs: Int64, width: Int, height: Int)], gapMs: Int64 = 10 * 60_000, minCount: Int = 3) -> [ShotSession] {
        let sorted = shots.sorted { $0.timeMs < $1.timeMs }
        var out: [ShotSession] = []
        var cur: [(id: String, timeMs: Int64, width: Int, height: Int)] = []
        func flush() {
            if cur.count >= minCount { out.append(ShotSession(ids: cur.map { $0.id }, startMs: cur.first!.timeMs, endMs: cur.last!.timeMs)) }
            cur = []
        }
        for s in sorted {
            if let p = cur.last, s.timeMs - p.timeMs > gapMs || s.width != p.width || s.height != p.height { flush() }
            cur.append(s)
        }
        flush()
        return out.sorted { $0.startMs > $1.startMs }
    }
}
