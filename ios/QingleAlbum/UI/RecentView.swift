import SwiftUI

/// 刚拍的照片：最近 24 小时（没有就 3 天、7 天）
func recentPhotos(_ lib: Library, now: Date = Date()) -> (items: [MediaItem], label: String) {
    let camera = lib.images.filter { $0.isCameraShot }
    for (days, label) in [(1, "最近 24 小时"), (3, "最近 3 天"), (7, "最近 7 天")] {
        let cutoff = Int64((now.timeIntervalSince1970 - Double(days) * 86_400) * 1000)
        let items = camera.filter { $0.timeMs >= cutoff }
        if !items.isEmpty { return (items, label) }
    }
    return ([], "")
}

/// 美感分低于这个（含）算拍得不好：和安卓版一样，显示为 4.3 分及以下
let aesLow: Float = 4.3
let aesHigh: Float = 5.5

func aesRounded(_ s: Float) -> Float { (s * 10).rounded() / 10 }

/**
 刚拍的：先现场打分（几秒钟），好了就是一张照片格子，每张标着美感分。
 4.3 分及以下、明显拍糊、连拍里不是最好的已经勾上；有人闭眼的只标出来。
 */
struct RecentView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected = Set<String>()
    @State private var initialized = false
    @State private var scoring = true
    @State private var viewer: ViewerTarget?
    @State private var confirm = false

    var body: some View {
        let lib = model.library
        let recent = lib.map { recentPhotos($0) } ?? (items: [], label: "")
        let items = recent.items
        let chosen = items.filter { selected.contains($0.id) }
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if scoring {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("正在给\(recent.label)的 \(items.count) 张照片打分").font(.headline)
                        ProgressView(value: model.progress.total > 0 ? model.progress.fraction : 0).tint(.brand)
                        Text("看清不清楚、构图好不好看、有没有人闭眼，全在手机里算。").font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(16).card(14).padding(.horizontal, 16).padding(.top, 8)
                } else {
                    Footnote(text: "\(recent.label)拍的 \(items.count) 张，每张标了美感分（1–10）。4.3 分及以下、拍糊的、连拍里不是最好的已经勾上；有人闭眼的只标出来。长按看大图。")
                        .padding(.top, 8)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                            PhotoCell(item: m, selected: selected.contains(m.id), tag: nil,
                                      onToggle: { toggle(m.id) }, onOpen: { viewer = ViewerTarget(items: items, start: i) })
                                .overlay(alignment: .topLeading) { scoreBadge(m, lib).padding(4) }
                        }
                    }
                }
            }
            .padding(.bottom, 20)
        }
        .background(Color.page)
        .navigationTitle("刚拍的")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !scoring {
                BottomBar(title: "已选 \(chosen.count) 张", subtitle: Fmt.bytes(chosen.reduce(0) { $0 + $1.size })) {
                    CapsuleButton(title: "删除", color: .danger, enabled: !chosen.isEmpty) { confirm = true }
                }
            }
        }
        .task {
            // 只分析这几张，几秒钟就好，不用等整个相册
            await model.analyzeNow(items)
            if let lib = model.library, !initialized {
                selected = suggest(recentPhotos(lib).items, lib)
                initialized = true
            }
            scoring = false
        }
        .alert("删除 \(chosen.count) 张？", isPresented: $confirm) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                Task { if await model.delete(chosen) { selected.subtract(chosen.map { $0.id }) } }
            }
        } message: {
            Text("会移到「照片」的「最近删除」，30 天内能恢复。")
        }
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: toggle)
        }
    }

    @ViewBuilder private func scoreBadge(_ m: MediaItem, _ lib: Library?) -> some View {
        if let s = lib?.aesthetic[m.id] {
            let r = aesRounded(s)
            let bg: Color = r <= aesLow ? Color.danger.opacity(0.9) : r >= aesHigh ? Color.brand.opacity(0.95) : .black.opacity(0.5)
            HStack(spacing: 3) {
                PhotoBadge(text: String(format: "%.1f", r), bg: bg)
                if let fi = lib?.faces[m.id], fi.count > 0, fi.minEyesOpen >= 0, fi.minEyesOpen <= 0.35 {
                    PhotoBadge(text: "有人闭眼", bg: Color.danger.opacity(0.9))
                }
            }
        }
    }

    /// 默认勾：美感分 4.3 及以下、明显拍糊/误拍、几乎一样的连拍里不是最好的
    private func suggest(_ items: [MediaItem], _ lib: Library) -> Set<String> {
        var out = Set<String>()
        for m in items where !m.favorite {
            if let s = lib.aesthetic[m.id], aesRounded(s) <= aesLow { out.insert(m.id) }
            if let f = lib.features[m.id], BadShotRules.reasons(f, 1).contains(where: { $0 != .maybeBlurry }) { out.insert(m.id) }
        }
        let infos = items.compactMap { m -> PhotoInfo? in
            guard let f = lib.features[m.id] else { return nil }
            return PhotoInfo(id: m.id, timeMs: m.timeMs, width: m.width, height: m.height, size: m.size, favorite: m.favorite,
                             features: f, faces: lib.faces[m.id], aesthetic: lib.aesthetic[m.id], lat: m.lat, lng: m.lng)
        }
        for g in Grouper.group(infos, GroupParams()).map({ Ranker.rank($0, keepCount: 1) }) where g.nearIdentical {
            g.suggestedDelete.forEach { out.insert($0.photo.id) }
        }
        return out
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}
