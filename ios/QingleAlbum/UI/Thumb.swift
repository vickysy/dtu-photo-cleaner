import AVKit
import Photos
import SwiftUI

/// 缩略图共用一个缓存管理器
private let thumbManager = PHCachingImageManager()

/// 缩略图：从系统相册取（本机缓存的版本，不联网）
struct AssetThumb: View {
    let item: MediaItem
    var side: CGFloat = 200
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.fillSoft
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height).clipped()
                }
            }
        }
        .clipped()
        .task(id: item.id) { await load() }
    }

    private func load() async {
        let o = PHImageRequestOptions()
        o.deliveryMode = .opportunistic
        o.resizeMode = .fast
        o.isNetworkAccessAllowed = false
        let px = side * UIScreen.main.scale
        let stream = AsyncStream<UIImage> { cont in
            let id = thumbManager.requestImage(for: item.asset, targetSize: CGSize(width: px, height: px), contentMode: .aspectFill, options: o) { img, info in
                if let img { cont.yield(img) }
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if !degraded { cont.finish() }
            }
            cont.onTermination = { _ in thumbManager.cancelImageRequest(id) }
        }
        for await img in stream { image = img }
    }
}

/// 照片格子：点一下勾选/取消；右上角放大镜（或长按）看大图。勾选 = 要删
struct PhotoCell: View {
    let item: MediaItem
    let selected: Bool
    var tag: String?
    let onToggle: () -> Void
    let onOpen: () -> Void

    var body: some View {
        AssetThumb(item: item)
            .aspectRatio(1, contentMode: .fit)
            .overlay(selected ? Color.white.opacity(0.22) : .clear)
            .overlay(alignment: .topLeading) {
                if let tag { SmartTag(text: tag).padding(4) }
            }
            .overlay(alignment: .bottomLeading) {
                if item.isVideo {
                    // 清晰度单独一行：一行放不下时长 + 1080p，会压到右下角的勾选圈
                    VStack(alignment: .leading, spacing: 1) {
                        if let r = item.resolution { Text(r).font(.system(size: 10, weight: .bold)) }
                        HStack(spacing: 2) {
                            Image(systemName: "play.fill").font(.system(size: 9))
                            Text(Fmt.duration(item.durationMs)).font(.system(size: 11, weight: .semibold))
                        }
                    }
                    .foregroundStyle(.white).shadow(radius: 2).padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) { SelectCircle(selected: selected, size: 22).padding(5) }
            // 放大镜：用户反馈过“找不到大图”（只能长按），和安卓 v2.8.5 一样放一个看得见的入口
            .overlay(alignment: .topTrailing) { ZoomButton(action: { Usage.add("看大图"); onOpen() }) }
            .contentShape(Rectangle())
            .onTapGesture {
                Usage.add((selected ? "取消勾选." : "勾选.") + Usage.page)
                onToggle()
            }
            .onLongPressGesture {
                Usage.add("看大图")
                onOpen()
            }
    }
}

/// 看大图时底部的一个按钮（文字、颜色可以随当前这张变）
struct ViewerAction: Identifiable {
    let id = UUID()
    let icon: String
    let label: (String) -> String
    var tint: (String) -> Color = { _ in .white }
    let run: (String) -> Void
}

/// 看大图：左右滑；整理时可以切换删/留，分类相册里底部有分享、好评、删除；视频能播放
struct Viewer: View {
    let items: [MediaItem]
    @State var index: Int
    var isMarked: ((String) -> Bool)? = nil
    var onToggle: ((String) -> Void)? = nil
    var actions: [ViewerAction] = []
    @Environment(\.dismiss) private var dismiss
    /// 往下拖的距离：拖够了就关掉（和“照片”App 一样）
    @State private var dragY: CGFloat = 0
    /// 正在看的那张（左右翻页用）
    @State private var pageId: String?

    init(items: [MediaItem], index: Int, isMarked: ((String) -> Bool)? = nil, onToggle: ((String) -> Void)? = nil, actions: [ViewerAction] = []) {
        self.items = items
        _index = State(initialValue: index)
        self.isMarked = isMarked
        self.onToggle = onToggle
        self.actions = actions
        _pageId = State(initialValue: items.indices.contains(index) ? items[index].id : nil)
    }

    var body: some View {
        VStack(spacing: 0) {
            // 顶栏单独一条黑底，照片不会盖住关闭按钮（白底截图上也看得见）
            HStack(spacing: 8) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(Color.white.opacity(0.2), in: Circle())
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("关闭")
                Spacer()
                if items.indices.contains(index) {
                    let m = items[index]
                    Text("\(index + 1) / \(items.count) · \(Fmt.day(m.timeMs)) \(Fmt.hm(m.timeMs))").font(.footnote).foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    if let isMarked, let onToggle {
                        let marked = isMarked(m.id)
                        Button { Usage.add("大图里切换删留"); onToggle(m.id) } label: {
                            HStack(spacing: 6) {
                                SelectCircle(selected: marked, size: 20)
                                Text(marked ? "要删" : "留下").font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                            }
                            .padding(.horizontal, 12).frame(height: 36)
                            .background(marked ? Color.danger : Color.white.opacity(0.2), in: Capsule())
                            .contentShape(Capsule())
                        }
                    } else {
                        Color.clear.frame(width: 44, height: 44)
                    }
                }
            }
            .padding(.horizontal, 8).frame(height: 52)

            // 左右翻页：用按需加载的横向列表（以前用 TabView，几百张时每张都要先建好，新系统上翻起来卡）
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 0) {
                        ForEach(items, id: \.id) { m in
                            BigImage(item: m).containerRelativeFrame([.horizontal, .vertical])
                        }
                    }
                    .scrollTargetLayout()
                    .background(PullDown(onChange: { dragY = $0 }, onEnd: { y, v in
                        if y > 120 || v > 900 { dismiss() } else { withAnimation(.easeOut(duration: 0.2)) { dragY = 0 } }
                    }))
                }
                .scrollIndicators(.hidden)
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $pageId)
                .onAppear { if let pageId { proxy.scrollTo(pageId) } }
            }
            .offset(y: dragY)
            .onChange(of: pageId) { _, id in
                if let id, let i = items.firstIndex(where: { $0.id == id }) { index = i }
            }

            if !actions.isEmpty && items.indices.contains(index) {
                let id = items[index].id
                HStack {
                    ForEach(actions) { a in
                        Button { a.run(id) } label: {
                            VStack(spacing: 4) {
                                Image(systemName: a.icon).font(.system(size: 22)).foregroundStyle(a.tint(id))
                                Text(a.label(id)).font(.caption).foregroundStyle(.white)
                            }
                            .frame(maxWidth: .infinity)
                            .contentShape(Rectangle())
                        }
                    }
                }
                .padding(.vertical, 10)
            }
        }
        .background(Color.black.opacity(1 - min(0.6, Double(dragY) / 400)).ignoresSafeArea())
        // 关掉大图才把声音还给别的 App（音乐接着放）；每翻一页都还的话，两段视频之间音乐会冒出来
        .onDisappear {
            DispatchQueue.global(qos: .utility).async {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
        }
    }
}

/**
 往下拖关大图：手势装在左右翻页的列表（UIScrollView）上。先竖着往下拖才算，横着动就是翻页；
 翻页先等它判断完，所以两个不会抢（以前用 SwiftUI 的拖动手势叠在翻页上，新系统上左右滑会卡住）。
 */
private struct PullDown: UIViewRepresentable {
    let onChange: (CGFloat) -> Void
    let onEnd: (_ distance: CGFloat, _ speed: CGFloat) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> Host {
        let v = Host()
        v.isUserInteractionEnabled = false
        v.coordinator = context.coordinator
        return v
    }

    func updateUIView(_ v: Host, context: Context) {
        context.coordinator.onChange = onChange
        context.coordinator.onEnd = onEnd
        if v.window != nil { context.coordinator.attach(v) }
    }

    final class Host: UIView {
        weak var coordinator: Coordinator?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { coordinator?.attach(self) }
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onChange: (CGFloat) -> Void = { _ in }
        var onEnd: (CGFloat, CGFloat) -> Void = { _, _ in }
        private weak var scrollView: UIScrollView?
        private var pan: UIPanGestureRecognizer?

        func attach(_ host: UIView) {
            var v = host.superview
            while let s = v, !(s is UIScrollView) { v = s.superview }
            guard let sv = v as? UIScrollView, sv !== scrollView else { return }
            if let pan { pan.view?.removeGestureRecognizer(pan) }
            let p = UIPanGestureRecognizer(target: self, action: #selector(handle(_:)))
            p.maximumNumberOfTouches = 1
            p.delegate = self
            sv.addGestureRecognizer(p)
            sv.panGestureRecognizer.require(toFail: p)
            pan = p
            scrollView = sv
        }

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let p = g as? UIPanGestureRecognizer else { return false }
            let t = p.translation(in: nil)
            return t.y > 0 && t.y > abs(t.x) * 1.2
        }

        // 用窗口坐标：大图跟着手指往下移，用它自己的坐标会越算越少
        @objc private func handle(_ p: UIPanGestureRecognizer) {
            let y = max(0, p.translation(in: nil).y)
            switch p.state {
            case .changed: onChange(y)
            case .ended, .cancelled, .failed: onEnd(y, p.velocity(in: nil).y)
            default: break
            }
        }
    }
}

/// 一张大图（看大图时可以从 iCloud 下载原图）；视频显示播放器
private struct BigImage: View {
    let item: MediaItem
    @State private var image: UIImage?
    @State private var player: AVPlayer?

    var body: some View {
        ZStack {
            if let player {
                VideoPlayer(player: player)
            } else if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView().tint(.white)
            }
        }
        .task(id: item.id) {
            if item.isVideo {
                // 不设成“播放视频”，手机开着静音模式时播放没声音（用户反馈）。
                // 只设类型、不马上激活：点播放才会打断别的 App 的音乐
                try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
                let o = PHVideoRequestOptions()
                o.isNetworkAccessAllowed = true
                PHImageManager.default().requestPlayerItem(forVideo: item.asset, options: o) { pi, _ in
                    if let pi { Task { @MainActor in player = AVPlayer(playerItem: pi) } }
                }
            } else {
                let o = PHImageRequestOptions()
                o.deliveryMode = .opportunistic
                o.isNetworkAccessAllowed = true
                let side = max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * UIScreen.main.scale
                PHImageManager.default().requestImage(for: item.asset, targetSize: CGSize(width: side, height: side), contentMode: .aspectFit, options: o) { img, _ in
                    if let img { Task { @MainActor in image = img } }
                }
            }
        }
        .onDisappear { player?.pause() }
    }
}

/// 照片拼图：4 张 2×2，3 张左大右二，2 张左右各半，1 张铺满
struct Mosaic: View {
    let items: [MediaItem]
    var body: some View {
        GeometryReader { g in
            let gap: CGFloat = 2
            let w = g.size.width, h = g.size.height
            ZStack(alignment: .topLeading) {
                Color.fillSoft
                switch items.count {
                case 0: EmptyView()
                case 1: cell(items[0], w, h)
                case 2:
                    HStack(spacing: gap) { cell(items[0], (w - gap) / 2, h); cell(items[1], (w - gap) / 2, h) }
                case 3:
                    HStack(spacing: gap) {
                        cell(items[0], (w - gap) / 2, h)
                        VStack(spacing: gap) { cell(items[1], (w - gap) / 2, (h - gap) / 2); cell(items[2], (w - gap) / 2, (h - gap) / 2) }
                    }
                default:
                    VStack(spacing: gap) {
                        HStack(spacing: gap) { cell(items[0], (w - gap) / 2, (h - gap) / 2); cell(items[1], (w - gap) / 2, (h - gap) / 2) }
                        HStack(spacing: gap) { cell(items[2], (w - gap) / 2, (h - gap) / 2); cell(items[3], (w - gap) / 2, (h - gap) / 2) }
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func cell(_ m: MediaItem, _ w: CGFloat, _ h: CGFloat) -> some View {
        AssetThumb(item: m, side: max(w, h)).frame(width: w, height: h)
            .overlay(alignment: .bottomLeading) {
                if m.isVideo { Image(systemName: "play.fill").font(.system(size: 12)).foregroundStyle(.white).padding(4) }
            }
    }
}

/// 小图右上角的放大镜：点它看大图
struct ZoomButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "plus.magnifyingglass")
                .font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Color.black.opacity(0.35), in: Circle())
                .padding(4)
                .frame(width: 34, height: 34, alignment: .topTrailing)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("看大图")
    }
}

/// 第一次进清理页面提示一次怎么看大图；点“知道了”以后不再出现
struct PreviewHint: View {
    @AppStorage("previewHintShown") private var shown = false
    var body: some View {
        if !shown {
            HStack(spacing: 10) {
                Image(systemName: "plus.magnifyingglass").font(.system(size: 20)).foregroundStyle(Color.brand)
                Text("点照片右上角的放大镜（或按住照片）看大图。大图里也能改成保留。")
                    .font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                Button("知道了") { shown = true }.font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentText)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(Color.brand.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
        }
    }
}
