import SwiftUI

/**
 压缩视频：4K、高码率的视频转成 1080p H.265，同一段视频能小好几倍。
 压好的放进相册（拍摄时间、地点不变），原视频先不动；你对比看过、点“删原视频”，原视频才进「最近删除」。
 */
struct CompressView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected = Set<String>()
    @State private var initialized = false
    @State private var viewer: ViewerTarget?
    @State private var confirmKeepAll = false
    @State private var drag = DragSelector()

    var body: some View {
        let lib = model.library
        let candidates = lib?.compressible ?? []
        let pending = lib?.compressedPending ?? []
        let chosen = candidates.filter { selected.contains($0.id) }
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let c = model.compressing {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("正在压第 \(c.index + 1) / \(c.total) 个").font(.headline)
                        ProgressView(value: c.fraction).tint(.brand)
                        HStack {
                            Text("压的时候别锁屏、别切走，iOS 不让 App 在后台长时间转码。").font(.footnote).foregroundStyle(.secondary)
                            Spacer()
                            Button("停止") { model.cancelCompress() }.font(.subheadline).foregroundStyle(Color.danger)
                        }
                    }
                    .padding(16).card(14).padding(.horizontal, 16)
                }

                if !pending.isEmpty {
                    Text("压好了，等你确认").font(.title3.weight(.semibold)).padding(.horizontal, 20)
                    Footnote(text: "左边原视频，右边压缩版，长按播放对比。满意就删原视频（进「最近删除」，30 天内能恢复）；不满意就删压缩版。")
                    ForEach(pending) { p in PairRow(pair: p, open: { items, i in viewer = ViewerTarget(items: items, start: i) }) }
                    if pending.count > 1 {
                        CapsuleButton(title: "都满意，删掉 \(pending.count) 个原视频 · 省 \(Fmt.bytes(pending.reduce(0) { $0 + $1.source.size - $1.output.size }))", color: .danger) {
                            confirmKeepAll = true
                        }
                        .padding(.horizontal, 16)
                    }
                }

                Text("可以压缩的").font(.title3.weight(.semibold)).padding(.horizontal, 20).padding(.top, 4)
                Footnote(text: candidates.isEmpty ? "没有值得压的视频了：1080p 拍的视频本来就不大。"
                    : "\(candidates.count) 个视频转成 1080p H.265 能省 \(Fmt.bytes(lib?.compressSaving ?? 0))，手机上看几乎没区别。按能省的从多到少排，默认勾了最省的 10 个，按住慢慢横着划可以连续选。收藏的视频不压。")
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                    ForEach(Array(candidates.enumerated()), id: \.element.id) { i, c in
                        PhotoCell(item: c.item, selected: selected.contains(c.id), tag: "省 \(Fmt.bytes(c.saving))", selectTint: .brand,
                                  onToggle: { toggle(c.id) },
                                  onOpen: { viewer = ViewerTarget(items: candidates.map { $0.item }, start: i) })
                            .dragSelectCell(c.id, drag)
                    }
                }
            }
            .padding(.vertical, 8)
            .dragSelect(drag, ids: { candidates.map { $0.id } }, selection: $selected)
        }
        .background(Color.page)
        .navigationTitle("压缩视频")
        .usagePage("压缩视频")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !candidates.isEmpty && model.compressing == nil {
                BottomBar(title: "已选 \(chosen.count) 个", subtitle: "能省 \(Fmt.bytes(chosen.reduce(0) { $0 + $1.saving }))") {
                    CapsuleButton(title: "压缩", enabled: !chosen.isEmpty) { model.compress(chosen.map { $0.item }) }
                }
            }
        }
        .onAppear {
            if !initialized && !candidates.isEmpty {
                selected = Set(candidates.prefix(10).map { $0.id })
                initialized = true
            }
        }
        .alert("删掉 \(pending.count) 个原视频？", isPresented: $confirmKeepAll) {
            Button("取消", role: .cancel) {}
            Button("删原视频", role: .destructive) { Task { await model.keepCompressed(pending) } }
        } message: {
            Text("压缩版留下。原视频移到「最近删除」，30 天内能恢复。")
        }
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: toggle)
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}

/// 一对：原视频 → 压缩版
private struct PairRow: View {
    @EnvironmentObject var model: AppModel
    let pair: CompressedPair
    let open: ([MediaItem], Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                tile(pair.source, label("原视频", pair.source), 0)
                Image(systemName: "arrow.right").foregroundStyle(.secondary)
                tile(pair.output, label("压缩版", pair.output), 1)
            }
            HStack {
                Button("不要压缩版") { Task { await model.discardCompressed(pair) } }
                    .font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                CapsuleButton(title: "删原视频 · 省 \(Fmt.bytes(pair.source.size - pair.output.size))", color: .danger) {
                    Task { await model.keepCompressed([pair]) }
                }
            }
        }
        .padding(14).card(16).padding(.horizontal, 16)
    }

    private func label(_ name: String, _ m: MediaItem) -> String {
        ([name] + [m.resolution, Fmt.bytes(m.size)].compactMap { $0 }).joined(separator: " ")
    }

    private func tile(_ m: MediaItem, _ label: String, _ i: Int) -> some View {
        AssetThumb(item: m).aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(alignment: .bottomLeading) { PhotoBadge(text: label).padding(5) }
            .onLongPressGesture { open([pair.source, pair.output], i) }
    }
}
