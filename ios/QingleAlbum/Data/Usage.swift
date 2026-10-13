import Foundation

/**
 使用记录：只在手机里记一些次数和数字（打开了哪些功能、删了多少、分析用了多久、哪里出了错），用来生成“使用报告”。
 不记照片、文件名、图里的字、拍摄时间和地点；不联网。
 只有用户在“设置 → 发送使用报告”里自己点发送、自己选发给谁，才会离开手机。
 */
enum Usage {
    private static let lock = NSLock()
    private static let countsKey = "usage.counts"
    private static let eventsKey = "usage.events"
    private static let firstKey = "usage.firstOpen"
    private static let foregroundKey = "usage.inForeground"
    private static var counts: [String: Int] = UserDefaults.standard.dictionary(forKey: countsKey) as? [String: Int] ?? [:]

    /// 当前在哪个页面：删除、勾选记在这个页面名下（只在主线程改）
    static var page = "首页"

    static func add(_ name: String, _ n: Int = 1) {
        guard n != 0 else { return }
        lock.lock()
        counts[name, default: 0] += n
        let snapshot = counts
        lock.unlock()
        UserDefaults.standard.set(snapshot, forKey: countsKey)
    }

    /// 只留最大的那个值（比如最长卡了多久）
    static func keepMax(_ name: String, _ v: Int) {
        lock.lock()
        let bigger = v > (counts[name] ?? 0)
        if bigger { counts[name] = v }
        let snapshot = counts
        lock.unlock()
        if bigger { UserDefaults.standard.set(snapshot, forKey: countsKey) }
    }

    static func all() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return counts
    }

    static func value(_ name: String) -> Int { all()[name] ?? 0 }

    /// 记一条出错（只写发生了什么，不写照片名），最多留 30 条
    static func event(_ text: String) {
        let line = Fmt.stamp(Date()) + "  " + String(text.prefix(120))
        lock.lock()
        var list = UserDefaults.standard.stringArray(forKey: eventsKey) ?? []
        list.append(line)
        if list.count > 30 { list.removeFirst(list.count - 30) }
        UserDefaults.standard.set(list, forKey: eventsKey)
        lock.unlock()
    }

    static func events() -> [String] { UserDefaults.standard.stringArray(forKey: eventsKey) ?? [] }

    static var firstOpen: Date? { UserDefaults.standard.object(forKey: firstKey) as? Date }

    /// App 启动时调一次：记打开次数；上次还在前台就没了，说明是闪退或内存不够被系统关掉
    static func launched() {
        let d = UserDefaults.standard
        if d.object(forKey: firstKey) == nil { d.set(Date(), forKey: firstKey) }
        if d.bool(forKey: foregroundKey) {
            add("意外退出")
            event("上次用着用着退出了（闪退，或内存不够被系统关掉）")
        }
        add("打开 App")
    }

    /// 切到前台 / 后台
    static func foreground(_ on: Bool) {
        active = on
        UserDefaults.standard.set(on, forKey: foregroundKey)
    }

    private static var active = false

    /**
     看界面有没有卡住：每秒往主线程丢一个空任务，过了 0.4 秒才轮到它，就是那一下界面点不动、滑不动。
     切到后台时 App 被冻住，回来那一下不算。
     */
    static func watchMainThread() {
        let q = DispatchQueue(label: "usage.watch", qos: .utility)
        func ping() {
            let sent = Date()
            let wasActive = active
            DispatchQueue.main.async {
                let ms = Int(Date().timeIntervalSince(sent) * 1000)
                if wasActive && active && ms > 400 && ms < 15_000 {
                    add("界面卡住")
                    keepMax("界面最长卡住毫秒", ms)
                }
                q.asyncAfter(deadline: .now() + 1) { ping() }
            }
        }
        q.async { ping() }
    }
}
