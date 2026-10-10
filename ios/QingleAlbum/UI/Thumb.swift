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

/// 照片格子：点一下勾选/取消，长按看大图。勾选 = 要删
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
                    HStack(spacing: 2) {
                        Image(systemName: "play.fill").font(.system(size: 9))
                        Text(Fmt.duration(item.durationMs)).font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(.white).shadow(radius: 2).padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) { SelectCircle(selected: selected, size: 22).padding(5) }
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggle)
            .onLongPressGesture(perform: onOpen)
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

    var body: some View {
        VStack(spacing: 0) {
            // 顶栏单独一条黑底，照片不会盖住关闭按钮（白底截图上也看得见）
            HStack(spacing: 8) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(Color.white.opacity(0.2), in: Circle())
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("关闭")
                Spacer()
                if items.indices.contains(index) {
                    let m = items[index]
                    Text("\(index + 1) / \(items.count) · \(Fmt.day(m.timeMs)) \(Fmt.hm(m.timeMs))").font(.footnote).foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    if let isMarked, let onToggle {
                        let marked = isMarked(m.id)
                        Button { onToggle(m.id) } label: {
                            HStack(spacing: 6) {
                                SelectCircle(selected: marked, size: 20)
                                Text(marked ? "要删" : "留下").font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                            }
                            .padding(.horizontal, 12).frame(height: 36)
                            .background(marked ? Color.danger : Color.white.opacity(0.2), in: Capsule())
                        }
                    } else {
                        Color.clear.frame(width: 44, height: 44)
                    }
                }
            }
            .padding(.horizontal, 8).frame(height: 52)

            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                    BigImage(item: m).tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .offset(y: dragY)
            .simultaneousGesture(
                DragGesture(minimumDistance: 25)
                    .onChanged { v in
                        if v.translation.height > 0 && abs(v.translation.height) > abs(v.translation.width) { dragY = v.translation.height }
                    }
                    .onEnded { _ in
                        if dragY > 120 { dismiss() } else { withAnimation(.easeOut(duration: 0.2)) { dragY = 0 } }
                    }
            )

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
                        }
                    }
                }
                .padding(.vertical, 10)
            }
        }
        .background(Color.black.opacity(1 - min(0.6, Double(dragY) / 400)).ignoresSafeArea())
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
