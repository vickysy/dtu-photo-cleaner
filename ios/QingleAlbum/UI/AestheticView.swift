import SwiftUI

/// 美感排行：相机照片按美感分（1–10）排好，看看拍得最好和最差的。不预选，自己挑着删
struct AestheticView: View {
    @EnvironmentObject var model: AppModel
    @State private var bestFirst = true
    @State private var selected = Set<String>()
    @State private var viewer: ViewerTarget?
    @State private var confirm = false

    var body: some View {
        let lib = model.library
        let scored = (lib?.aesthetic ?? [:]).compactMap { id, s in lib?.byId[id].map { ($0, s) } }
            .sorted { bestFirst ? $0.1 > $1.1 : $0.1 < $1.1 }
        let items = scored.map { $0.0 }
        let chosen = items.filter { selected.contains($0.id) }
        let low = scored.filter { aesRounded($0.1) <= aesLow }.count
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Picker("", selection: $bestFirst) {
                    Text("最好看的在前").tag(true)
                    Text("最差的在前").tag(false)
                }
                .pickerStyle(.segmented).padding(.horizontal, 16).padding(.top, 8)
                Footnote(text: "\(Fmt.count(items.count)) 张相机照片按构图、色彩、清晰度打了分（iOS 自带的美感评估，换算成 1–10）。\(low) 张 4.3 分及以下。没有预选，长按看大图，自己挑着删。")
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                    ForEach(Array(scored.enumerated()), id: \.element.0.id) { i, e in
                        let r = aesRounded(e.1)
                        PhotoCell(item: e.0, selected: selected.contains(e.0.id), tag: nil,
                                  onToggle: { toggle(e.0.id) }, onOpen: { viewer = ViewerTarget(items: items, start: i) })
                            .overlay(alignment: .topLeading) {
                                PhotoBadge(text: String(format: "%.1f", r),
                                           bg: r <= aesLow ? Color.danger.opacity(0.9) : r >= aesHigh ? Color.brand.opacity(0.95) : .black.opacity(0.5))
                                    .padding(4)
                            }
                    }
                }
            }
            .padding(.bottom, 20)
        }
        .background(Color.page)
        .navigationTitle("美感排行")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !chosen.isEmpty {
                BottomBar(title: "已选 \(chosen.count) 张", subtitle: Fmt.bytes(chosen.reduce(0) { $0 + $1.size })) {
                    CapsuleButton(title: "删除", color: .danger) { confirm = true }
                }
            }
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

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}
