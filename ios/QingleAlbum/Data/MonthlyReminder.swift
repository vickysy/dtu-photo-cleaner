import Foundation
import UserNotifications

/**
 每月提醒：“这个月，相册清了没？”——和安卓一样，最多 30 天一次，能腾出 500 MB 以上才提醒。

 安卓是每天在后台查一遍；iOS 不让 App 在后台随便跑，所以换个做法：每次整理完相册，
 按现在能腾出多少，预约一条本地通知（离上次提醒满 30 天的那天晚上 8 点），到点系统自己弹，
 不用 App 在后台运行。每次整理完都会重新预约：清理过了、不到 500 MB 了，就取消。
 */
enum MonthlyReminder {
    /// 设置里开关存的键（默认开）
    static let key = "monthlyReminder"
    private static let requestId = "monthly"
    private static let interval: TimeInterval = 30 * 86_400
    /// 少于这么多就不打扰
    private static let minBytes: Int64 = 500 * 1_000_000
    private static let hour = 20

    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }

    /**
     整理完相册调用：按现在能腾出多少，重新预约下一条；时间和内容都没变就不动。
     第一次要预约时会请求通知权限，请求前先调 [willAsk]（让 App 说一句为什么要通知）。
     */
    static func update(count: Int, bytes: Int64, willAsk: @escaping @Sendable @MainActor () -> Void = {}) {
        let d = UserDefaults.standard
        let now = Date()
        // 上次预约的那条已经到点了：算提醒过，从那天起再算 30 天
        if let due = d.object(forKey: "reminderScheduledFor") as? Date, due <= now {
            d.set(due, forKey: "lastReminder")
            forgetScheduled()
        }
        var threshold = minBytes
        #if DEBUG
        let test = CommandLine.arguments.contains("-reminderTest")
        if test { threshold = 0 }
        #endif
        guard enabled, bytes >= threshold, count > 0 else { cancel(); return }
        // 刚装上不提醒，从现在开始算 30 天
        let last = d.object(forKey: "lastReminder") as? Date ?? {
            d.set(now, forKey: "lastReminder")
            return now
        }()
        var fire = evening(atOrAfter: max(last.addingTimeInterval(interval), now.addingTimeInterval(600)))
        #if DEBUG
        // 模拟器测试用：-reminderTest 时 15 秒后就弹
        if test { fire = now.addingTimeInterval(15) }
        #endif
        let body = "又能轻 \(Fmt.bytes(bytes))：\(Fmt.count(count)) 张重复、拍糊或多拍的，点开看看"
        if d.object(forKey: "reminderScheduledFor") as? Date == fire && d.string(forKey: "reminderBody") == body { return }

        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { s in
            switch s.authorizationStatus {
            case .notDetermined:
                Task { @MainActor in
                    willAsk()
                    center.requestAuthorization(options: [.alert, .sound]) { ok, _ in
                        if ok { schedule(fire, body) }
                    }
                }
            case .authorized, .provisional, .ephemeral:
                schedule(fire, body)
            default:
                break // 用户没允许通知：不预约，设置里会提示去打开
            }
        }
    }

    /// 关掉开关、或者不到 500 MB 了：取消已经预约的
    static func cancel() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [requestId])
        forgetScheduled()
    }

    /// 用户在系统设置里关掉了本 App 的通知
    static func denied() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }

    private static func schedule(_ fire: Date, _ body: String) {
        // 权限弹窗放着没点、点的时候已经过了预约时间：这次不约，下次整理完再约
        guard fire > Date() else { return }
        let content = UNMutableNotificationContent()
        content.title = "这个月，相册清了没？"
        content.body = body
        content.sound = .default
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
        let req = UNNotificationRequest(identifier: requestId, content: content,
                                        trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
        // 同一个 id 再加一次就是替换，不会叠成好几条
        UNUserNotificationCenter.current().add(req) { error in
            guard error == nil else { return }
            UserDefaults.standard.set(fire, forKey: "reminderScheduledFor")
            UserDefaults.standard.set(body, forKey: "reminderBody")
        }
    }

    private static func forgetScheduled() {
        UserDefaults.standard.removeObject(forKey: "reminderScheduledFor")
        UserDefaults.standard.removeObject(forKey: "reminderBody")
    }

    /// [date] 当天或之后的第一个晚上 8 点（不在半夜吵人）
    private static func evening(atOrAfter date: Date) -> Date {
        let cal = Calendar.current
        var c = cal.dateComponents([.year, .month, .day], from: date)
        c.hour = hour
        let t = cal.date(from: c) ?? date
        return t >= date ? t : cal.date(byAdding: .day, value: 1, to: t) ?? date
    }
}

extension AppModel {
    /**
     相册整理好以后（不在分析中）按现在能腾出多少，重新预约每月提醒。
     [lib] 用刚整理出来的那份（@Published 发出新值时，属性本身还没换过来）。
     */
    func updateReminder(_ lib: Library?) {
        if !MonthlyReminder.enabled { MonthlyReminder.cancel(); return }
        guard let lib, lib.analyzed, !progress.running else { return }
        let r = lib.reclaimable()
        MonthlyReminder.update(count: r.count, bytes: r.bytes) { [weak self] in
            self?.show("每月提醒你一次能腾出多少空间，请允许通知（设置里随时能关）")
        }
    }
}
