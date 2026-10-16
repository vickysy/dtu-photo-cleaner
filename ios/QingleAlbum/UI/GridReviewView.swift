import SwiftUI

/// 打开大图用
struct ViewerTarget: Identifiable {
    let id = UUID()
    let items: [MediaItem]
    let start: Int
}

/// 废片、截图、大视频：一屏照片格子，勾上的删
struct GridReviewView: View {
    enum Kind { case bad, screenshots, bigVideos, videos }
    let kind: Kind
    @EnvironmentObject var model: AppModel
    @State private var selected = Set<String>()
    /// 已经按推荐勾过的（之后用户改过的勾选不再动）
    @State private var seen = Set<String>()
    @State private var viewer: ViewerTarget?
    @State private var deleting: DeleteRequest?
    @State private var drag = DragSelector()

    private var title: String {
        switch kind {
        case .bad: return "废片"
        case .screenshots: return "截图"
        case .bigVideos: return "大视频"
        case .videos: return "可删的视频"
        }
    }

    private var note: String {
        switch kind {
        case .bad: return "拍糊的、太暗的、误拍的纯色画面。背景虚化（人像模式）的照片只要主体清楚就不算糊。“可能模糊”的没有勾，点放大镜看大图再决定。按住照片慢慢横着划可以连续选。"
        case .screenshots: return "超过 \(model.settings.screenshotDays) 天的截图已经勾选；取件码、验证码、付款码这类用完就没用的，3 天前的就勾上；证件永远不勾。按住慢慢横着划可以连续选。"
        case .bigVideos: return "超过 \(model.settings.bigVideoMB) MB 的视频，从大到小排。都没勾，点放大镜播放看一眼再决定；按住慢慢横着划可以连续选。"
        case .videos: return "每个视频抽了 5 帧来看：黑屏、没拍到东西、重复保存的、不到 3 秒的已经勾选；“可能全程模糊”的没勾，点放大镜播放看一眼再决定。同一个场景拍了好几段的，在首页“视频场景精选”里。"
        }
    }

    /// (照片, 标签, 默认勾不勾)
    private var entries: [(MediaItem, String?, Bool)] {
        guard let lib = model.library else { return [] }
        switch kind {
        case .bad:
            return lib.bad.map { ($0.item, $0.reasons.first?.rawValue, $0.confident) }
        case .screenshots:
            let old = Set(lib.oldScreenshots().map { $0.id })
            return lib.screenshots.map { ($0, lib.docKinds[$0.id]?.rawValue, old.contains($0.id)) }
        case .bigVideos:
            return lib.bigVideos.map { ($0, Fmt.bytes($0.size), false) }
        case .videos:
            return lib.videoSuggest.map { ($0.item, $0.reasons.first?.rawValue, $0.confident) }
        }
    }

    var body: some View {
        let list = entries
        let items = list.map { $0.0 }
        let chosen = items.filter { selected.contains($0.id) }
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Footnote(text: note).padding(.top, 8)
                PreviewHint()
                if list.isEmpty {
                    Text("没有需要处理的").foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, 80)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 4), spacing: 2) {
                    ForEach(Array(list.enumerated()), id: \.element.0.id) { i, e in
                        PhotoCell(item: e.0, selected: selected.contains(e.0.id), tag: e.1,
                                  onToggle: { toggle(e.0.id) }, onOpen: { viewer = ViewerTarget(items: items, start: i) })
                            .dragSelectCell(e.0.id, drag)
                    }
                }
            }
            .padding(.bottom, 20)
            .dragSelect(drag, ids: { entries.map { $0.0.id } }, selection: $selected)
        }
        .background(Color.page)
        .navigationTitle(title)
        .usagePage(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(chosen.count == items.count && !items.isEmpty ? "全不选" : "全选") {
                    if chosen.count == items.count { selected.removeAll() } else { selected = Set(items.map { $0.id }) }
                }
                .foregroundStyle(Color.accentText).disabled(items.isEmpty)
            }
        }
        .safeAreaInset(edge: .bottom) {
            BottomBar(title: "已选 \(chosen.count) 项", subtitle: "\(Fmt.bytes(chosen.reduce(0) { $0 + $1.size })) · 共 \(items.count) 项") {
                CapsuleButton(title: "删除", color: .danger, enabled: !chosen.isEmpty) {
                deleting = DeleteRequest(items: chosen) { items in
                    Task { if await model.delete(items) { selected.subtract(items.map { $0.id }) } }
                }
            }
            }
        }
        .onAppear { preselect(list) }
        .onChange(of: items.map { $0.id }) { preselect(entries) }
        .deleteConfirm($deleting, isMarked: { selected.contains($0) }, onToggle: toggle)
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: toggle, actions: viewerActions)
        }
    }

    /// 截图看大图时能设为好评（和安卓 v2.8.5 一样：没认成聊天记录的好评截图，比如朋友圈、小红书评论，也能收进“好评”）
    private var viewerActions: [ViewerAction] {
        guard kind == .screenshots else { return [] }
        return [ViewerAction(icon: "star.fill", label: { model.praise.contains($0) ? "取消好评" : "设为好评" },
                             tint: { model.praise.contains($0) ? .brandYellow : .white }) { id in
            let on = !model.praise.contains(id)
            if on { selected.remove(id) }
            model.setPraise([id], on: on)
        }]
    }

    /// 新出现的照片按推荐勾上（相册还没读完时进来，读完后补勾）
    private func preselect(_ list: [(MediaItem, String?, Bool)]) {
        for e in list where !seen.contains(e.0.id) {
            if e.2 { selected.insert(e.0.id) }
            seen.insert(e.0.id)
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}

/// 底部工具栏：左边两行说明，右边按钮
struct BottomBar<Trailing: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let trailing: () -> Trailing
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            trailing()
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}
