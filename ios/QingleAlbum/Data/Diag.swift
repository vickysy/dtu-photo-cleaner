import Foundation
import UIKit

/**
 诊断（只在开发版里开着；TestFlight、App Store 版什么都不做）：
 - 界面卡住超过 0.15 秒时，打出当时主线程在做哪一步（begin/end 标的那些）；
 - 每次手指按下、抬起，打出是哪个手势接住了（滚动、返回、划选……）；
 - 读相册、分析、打开页面的时间线。
 用 `xcrun devicectl device process launch --console` 启动就能在电脑上实时看到。
 */
enum Diag {
    #if DEBUG
    static let on = true
    #else
    static let on = false
    #endif

    private static let t0 = Date()
    private static let lock = NSLock()
    private static var stack: [String] = []

    static func log(_ text: @autoclosure () -> String) {
        guard on else { return }
        let line = String(format: "[%8.2f]%@ ", Date().timeIntervalSince(t0), Thread.isMainThread ? "主" : "  ") + text()
        print(line)
    }

    /// 主线程上开始做某件事（卡住时就知道卡在这一步）
    static func begin(_ name: String) -> Date? {
        guard on else { return nil }
        lock.lock(); stack.append(name); lock.unlock()
        return Date()
    }

    /// 做完了；超过 30 毫秒就打出来
    static func end(_ name: String, _ start: Date?) {
        guard on, let start else { return }
        lock.lock(); if let i = stack.lastIndex(of: name) { stack.remove(at: i) }; lock.unlock()
        let ms = Int(Date().timeIntervalSince(start) * 1000)
        if ms > 30 { log("\(name) 用了 \(ms) 毫秒") }
    }

    private static func doing() -> String {
        lock.lock(); defer { lock.unlock() }
        return stack.isEmpty ? "（没标记的事：多半是在画界面/排版）" : stack.joined(separator: " → ")
    }

    static func start() {
        guard on else { return }
        log("诊断开始：iOS \(UIDevice.current.systemVersion)")
        watchMain()
        DispatchQueue.main.async { installTouchSpy() }
    }

    /// 每 0.1 秒往主线程丢个空任务；0.15 秒还没轮到它，就是界面卡住了，打出主线程那时在做什么
    private static func watchMain() {
        let q = DispatchQueue(label: "diag.watch", qos: .userInteractive)
        func ping() {
            let sent = Date()
            var reported = false
            let check = DispatchWorkItem {
                reported = true
                log("⚠️ 界面卡住中，主线程正在：\(doing())")
            }
            q.asyncAfter(deadline: .now() + 0.15, execute: check)
            DispatchQueue.main.async {
                check.cancel()
                let ms = Int(Date().timeIntervalSince(sent) * 1000)
                if reported || ms > 150 { log("⚠️ 界面卡了 \(ms) 毫秒") }
                q.asyncAfter(deadline: .now() + 0.1) { ping() }
            }
        }
        q.async { ping() }
    }

    private static let spy = TouchSpy()

    private static func installTouchSpy() {
        guard let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { installTouchSpy() }
            return
        }
        if spy.view !== w { w.addGestureRecognizer(spy) }
        log("手势记录已装好")
    }
}

/// 只看不抢：手指按下时把一路上的手势都登记一下，谁开始识别了就打出来
private final class TouchSpy: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private var watched: [UIGestureRecognizer] = []
    private var downAt = CGPoint.zero

    init() {
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith o: UIGestureRecognizer) -> Bool { true }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = touches.first, let v = t.view else { return }
        downAt = t.location(in: nil)
        unwatch()
        var chain: [UIView] = []
        var x: UIView? = v
        while let y = x { chain.append(y); x = y.superview }
        for view in chain {
            for g in view.gestureRecognizers ?? [] where g !== self {
                g.addTarget(self, action: #selector(changed(_:)))
                watched.append(g)
            }
        }
        Diag.log("👆 按下 (\(Int(downAt.x)), \(Int(downAt.y))) 在 \(type(of: v))；这一路上有 \(watched.count) 个手势")
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) { finish(touches, "抬起") }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) { finish(touches, "被取消") }

    private func finish(_ touches: Set<UITouch>, _ how: String) {
        if let t = touches.first {
            let p = t.location(in: nil)
            Diag.log("👆 \(how)，手指移动了 (\(Int(p.x - downAt.x)), \(Int(p.y - downAt.y)))")
        }
        unwatch()
        state = .failed
    }

    @objc private func changed(_ g: UIGestureRecognizer) {
        let s: String
        switch g.state {
        case .began: s = "开始"
        case .ended: s = "结束"
        case .cancelled: s = "取消"
        default: return
        }
        Diag.log("   手势\(s)：\(type(of: g)) 在 \(g.view.map { "\(type(of: $0))" } ?? "?")")
    }

    private func unwatch() {
        watched.forEach { $0.removeTarget(self, action: #selector(changed(_:))) }
        watched = []
    }
}
