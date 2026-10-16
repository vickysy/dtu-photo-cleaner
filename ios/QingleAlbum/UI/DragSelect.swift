import SwiftUI
import UIKit

/**
 一划多选（和“照片”App、安卓版一样）：
 - 按在照片上横着划：划过的照片一起勾上（从已勾的照片开始划，就是一起取消）；
 - 划开以后再上下拖：起点到手指之间的整片都跟着变；拖到上下边缘会自动滚动；
 - 一开始就竖着滑：照常滚动；点一下、长按不受影响。
 用法：ScrollView 里最外层的内容加 .dragSelect(...)，每个格子加 .dragSelectCell(id, drag)。
 */
@MainActor
final class DragSelector: NSObject, UIGestureRecognizerDelegate {
    static let space = "dragSelect"

    /// 每个格子在内容里的位置（内容坐标，滚动时不变；只记屏幕附近的格子）
    fileprivate var frames: [String: CGRect] = [:]
    fileprivate var ids: () -> [String] = { [] }
    fileprivate var selection: Binding<Set<String>>?
    fileprivate var enabled = true
    /// 垫在内容底下的视图：手指位置换算成内容坐标用
    private weak var host: UIView?
    private weak var scrollView: UIScrollView?
    private var pan: UIPanGestureRecognizer?

    private var downInWindow = CGPoint.zero
    private var downPoint = CGPoint.zero
    private var order: [String] = []
    private var index: [String: Int] = [:]
    private var start = -1
    /// true：划过的勾上；false：划过的取消
    private var marking = true
    /// 开始划之前的勾选：划出范围的照片恢复成这样
    private var base = Set<String>()
    private var lastRange: ClosedRange<Int>?
    private var speed: CGFloat = 0
    private var link: CADisplayLink?
    private let haptic = UISelectionFeedbackGenerator()

    fileprivate func attach(host: UIView) {
        self.host = host
        var v = host.superview
        while let s = v, !(s is UIScrollView) { v = s.superview }
        guard let sv = v as? UIScrollView, sv !== scrollView else { return }
        detach()
        let p = UIPanGestureRecognizer(target: self, action: #selector(handle(_:)))
        p.maximumNumberOfTouches = 1
        p.delegate = self
        p.isEnabled = enabled
        sv.addGestureRecognizer(p)
        // 列表先等一下：确定不是横着划，再交给它滚
        sv.panGestureRecognizer.require(toFail: p)
        // iOS 26 起页面中间往右划也是“返回”，慢慢划会被它抢走、划选用不了。让返回也先等一下：
        // 快速一划、不在照片上起手、竖着动的，划选马上放弃，返回照常
        if #available(iOS 26.0, *), let nav = Self.navigationController(of: sv) {
            nav.interactiveContentPopGestureRecognizer?.require(toFail: p)
        }
        pan = p
        scrollView = sv
    }

    private static func navigationController(of view: UIView) -> UINavigationController? {
        var r: UIResponder? = view
        while let x = r {
            if let vc = x as? UIViewController { return vc.navigationController }
            r = x.next
        }
        return nil
    }

    fileprivate func detach() {
        finish()
        if let pan { pan.view?.removeGestureRecognizer(pan) }
        pan = nil
        scrollView = nil
    }

    /// 不能划选的时候（比如分类相册没点“选择”）整个手势关掉，列表滚动完全不用等它
    fileprivate func setEnabled(_ on: Bool) {
        enabled = on
        pan?.isEnabled = on
    }

    private func cell(at p: CGPoint) -> String? {
        frames.first { index[$0.key] != nil && $0.value.contains(p) }?.key
    }

    // MARK: 手势

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // 总是接住手指（不是划选就在下面判失败）：不接的话，列表的滚动可能一直等它、滑不动
        if let host {
            downInWindow = touch.location(in: nil)
            downPoint = touch.location(in: host)
        }
        return true
    }

    /// 往右划超过这个速度（点/秒）算“返回”
    static let backSwipeSpeed: CGFloat = 600

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard enabled, let p = g as? UIPanGestureRecognizer, let host, selection != nil else { return false }
        // 屏幕左边缘留给“滑动返回”
        if downInWindow.x < 28 { return false }
        // 先横着动才算划选，先竖着动就是滚动。
        // iOS 26 起这里的移动距离常常还是 0（以前靠它判断，划选根本开始不了），没有距离就看速度的方向
        let t = p.translation(in: host)
        let v = p.velocity(in: host)
        let d = t == .zero ? v : t
        guard abs(d.x) > abs(d.y) * 1.2 else { return false }
        // 快速往右一划是“返回”（iOS 26 起页面中间也能右滑返回）：不算划选，交给返回。慢慢划才算划选
        if d.x > 0 && v.x > Self.backSwipeSpeed { return false }
        order = ids()
        index = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return cell(at: downPoint) != nil
    }

    @objc private func handle(_ p: UIPanGestureRecognizer) {
        switch p.state {
        case .began:
            begin()
            move(p)
        case .changed:
            move(p)
        default:
            finish()
        }
    }

    private func begin() {
        guard let id = cell(at: downPoint), let i = index[id], let selection else { return }
        start = i
        base = selection.wrappedValue
        marking = !base.contains(id)
        lastRange = nil
        haptic.prepare()
        Usage.add("一划多选." + Usage.page)
        apply(i)
        let l = CADisplayLink(target: self, selector: #selector(tick))
        l.add(to: .main, forMode: .common)
        link = l
    }

    private func move(_ p: UIPanGestureRecognizer) {
        guard start >= 0, let host, let sv = scrollView else { return }
        if let id = cell(at: p.location(in: host)), let i = index[id] { apply(i) }
        // 手指在列表可见区域里的上下位置：靠近上下边缘就自动滚
        let y = p.location(in: sv).y - sv.contentOffset.y
        let top = sv.adjustedContentInset.top
        let bottom = sv.bounds.height - sv.adjustedContentInset.bottom
        let edge: CGFloat = 70
        if y > bottom - edge {
            speed = min(1, (y - (bottom - edge)) / edge) * 18
        } else if y < top + edge {
            speed = -min(1, (top + edge - y) / edge) * 18
        } else {
            speed = 0
        }
    }

    @objc private func tick() {
        guard speed != 0, let sv = scrollView, let pan else { return }
        let minY = -sv.adjustedContentInset.top
        let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
        let y = min(maxY, max(minY, sv.contentOffset.y + speed))
        guard y != sv.contentOffset.y else { return }
        sv.contentOffset.y = y
        move(pan)
    }

    private func apply(_ i: Int) {
        let range = min(start, i)...max(start, i)
        guard range != lastRange, let selection else { return }
        var s = base
        for k in range where order.indices.contains(k) {
            if marking { s.insert(order[k]) } else { s.remove(order[k]) }
        }
        selection.wrappedValue = s
        lastRange = range
        haptic.selectionChanged()
    }

    private func finish() {
        start = -1
        lastRange = nil
        speed = 0
        link?.invalidate()
        link = nil
    }
}

extension View {
    /// 放在 ScrollView 里最外层的内容上。ids：所有格子按显示的先后顺序；enabled 为 false 时不能划选
    func dragSelect(_ drag: DragSelector, ids: @escaping () -> [String], selection: Binding<Set<String>>, enabled: Bool = true) -> some View {
        background(DragSelectHost(drag: drag, ids: ids, selection: selection, enabled: enabled))
            .coordinateSpace(.named(DragSelector.space))
    }

    /// 能被一划多选的格子
    func dragSelectCell(_ id: String, _ drag: DragSelector) -> some View {
        modifier(DragSelectCell(id: id, drag: drag))
    }
}

/// 格子报告自己在内容里的位置；划出屏幕的格子不记，回到屏幕再记上
private struct DragSelectCell: ViewModifier {
    let id: String
    let drag: DragSelector
    @State private var frame: CGRect?

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(DragSelector.space)) } action: { f in
                frame = f
                drag.frames[id] = f
            }
            .onAppear { if let frame { drag.frames[id] = frame } }
            .onDisappear { drag.frames[id] = nil }
    }
}

/// 垫在内容底下、和内容一样大的空视图：找到外面的列表（UIScrollView），把划选手势装上去
private struct DragSelectHost: UIViewRepresentable {
    let drag: DragSelector
    let ids: () -> [String]
    let selection: Binding<Set<String>>
    let enabled: Bool

    func makeUIView(context: Context) -> HostView {
        let v = HostView()
        v.isUserInteractionEnabled = false
        v.drag = drag
        return v
    }

    func updateUIView(_ v: HostView, context: Context) {
        drag.ids = ids
        drag.selection = selection
        drag.setEnabled(enabled)
        v.drag = drag
        if v.window != nil { drag.attach(host: v) }
    }

    static func dismantleUIView(_ v: HostView, coordinator: ()) {
        v.drag?.detach()
    }

    final class HostView: UIView {
        weak var drag: DragSelector?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { drag?.attach(host: self) }
        }
    }
}
