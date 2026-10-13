import Foundation

/// 数字、大小、时间的显示格式（和安卓版一致：1.2 GB、3,456 张、10月8日）
enum Fmt {
    static func bytes(_ b: Int64) -> String {
        let v = Double(max(0, b))
        if v >= 1e9 { return String(format: v >= 1e11 ? "%.0f GB" : "%.1f GB", v / 1e9) }
        if v >= 1e6 { return String(format: "%.0f MB", v / 1e6) }
        if v >= 1e3 { return String(format: "%.0f KB", v / 1e3) }
        return "\(Int(v)) B"
    }

    static func count(_ n: Int) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    private static let dayFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日"
        return f
    }()
    private static let yearDayFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy年M月d日"
        return f
    }()
    private static let hmFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// 今年的只写月日，往年的带年份
    static func day(_ ms: Int64) -> String {
        let d = date(ms)
        let sameYear = Calendar.current.component(.year, from: d) == Calendar.current.component(.year, from: Date())
        return (sameYear ? dayFmt : yearDayFmt).string(from: d)
    }

    static func hm(_ ms: Int64) -> String { hmFmt.string(from: date(ms)) }

    private static let stampFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    /// 报告里用的时间：2026-10-12 19:40
    static func stamp(_ d: Date) -> String { stampFmt.string(from: d) }

    static func duration(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}
