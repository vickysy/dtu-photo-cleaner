import SwiftUI

/**
 场景精选：同一个地方拍了一堆，同一个东西拍了好几张的只留最好的，明显拍糊的也勾上；拍的不一样又清楚的都留着。
 不标“精华”（和绿勾、绿色标签分不清，木妈定的）：最好的几张排在前面，点“只留最好的 N 张”就只留它们。
 勾上 = 要删，红勾。每张标美感分（和安卓一样）。
 每个场景单独删、单独“全部保留”；底部“全部删除…”要先确认。
 */
struct SceneListView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected = Set<String>()
    /// 每个场景上次按哪一版建议勾的（场景 id → 建议）
    @State private var initialized: [String: String] = [:]
    @State private var viewer: ViewerTarget?
    @State private var deleting: DeleteRequest?
    @State private var drag = DragSelector()

    var body: some View {
        let scenes = model.library?.scenes ?? []
        let chosenIds = scenes.flatMap { $0.members.map { $0.id }.filter { selected.contains($0) } }
        let chosenBytes = chosenIds.reduce(Int64(0)) { $0 + (model.library?.byId[$1]?.size ?? 0) }
        ScrollView {
            VStack(spacing: 18) {
                Footnote(text: "同一个地方连续拍了很多张时：同一个东西拍了好几张的，只留最好的那张，其余打上红勾（要删）；明显拍糊的也打上。拍的东西不一样、又清楚的都留着。最好的几张排在最前面，想只留它们就点“只留最好的几张”。每张左下角是美感分（1–10）。按住慢慢横着划可以连续切换。只点这个场景自己的“删除”，不会动别的场景。")
                    .padding(.top, 8)
                PreviewHint()
                if scenes.isEmpty { Text("场景都处理完了").foregroundStyle(.secondary).padding(.top, 60) }
                ForEach(scenes) { sc in
                    SceneCard(scene: sc, selected: $selected, drag: drag,
                              open: { items, i in viewer = ViewerTarget(items: items, start: i) },
                              confirm: { deleting = $0 })
                }
            }
            .padding(.bottom, 20)
            .dragSelect(drag, ids: { scenes.flatMap(displayIds) }, selection: $selected)
        }
        .background(Color.page)
        .navigationTitle("场景精选")
        .usagePage("场景精选")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !scenes.isEmpty {
                BottomBar(title: "还有 \(scenes.count) 个场景", subtitle: chosenIds.isEmpty ? "都设成了全部保留" : "全部按勾选处理：删 \(chosenIds.count) 张 · \(Fmt.bytes(chosenBytes))") {
                    CapsuleButton(title: "全部删除…", color: .danger, filled: false, enabled: !chosenIds.isEmpty) {
                        deleting = DeleteRequest(items: chosenIds.compactMap { model.library?.byId[$0] },
                                                 note: "包括 \(scenes.count) 个场景里你还没看过的。每个场景按现在的勾选处理。") { items in
                            let ids = Set(items.map { $0.id })
                            let kept = scenes.flatMap { $0.members.map { $0.id } }.filter { !ids.contains($0) }
                            Task { await model.delete(items, kept: kept) }
                        }
                    }
                }
            }
        }
        .onAppear { initSelection(scenes) }
        .onChange(of: scenes.map(Self.key)) { initSelection(scenes) }
        .deleteConfirm($deleting, isMarked: { selected.contains($0) }, onToggle: { id in
            if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        })
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: { id in
                if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
            })
        }
    }

    /// 场景和它的建议：分析中途相册刷新，场景会并进新照片、建议会变
    private static func key(_ sc: PhotoScene) -> String { sc.id + "|" + sc.suggested.joined(separator: ",") }

    /// 新出现的场景、建议变了的场景，按推荐重新勾（先清掉这个场景原来的勾，免得精华、收藏的还勾着）；
    /// 用户改过勾选、建议没变的场景不动
    private func initSelection(_ scenes: [PhotoScene]) {
        // 只跟上一次比：建议变了又变回来，也要重新勾（不然勾的还是中间那一版，精华可能还勾着）
        for sc in scenes where initialized[sc.id] != Self.key(sc) {
            sc.members.forEach { selected.remove($0.id) }
            sc.suggested.forEach { selected.insert($0) }
            initialized[sc.id] = Self.key(sc)
        }
    }
}

/// 卡片里的先后顺序：最好的几张在前（按推荐顺序），然后是收藏的，其余按时间
private func displayIds(_ scene: PhotoScene) -> [String] {
    let top = scene.highlights + scene.favorites
    let topSet = Set(top)
    let members = Set(scene.members.map { $0.id })
    return top.filter { members.contains($0) } + scene.members.map { $0.id }.filter { !topSet.contains($0) }
}

private struct SceneCard: View {
    @EnvironmentObject var model: AppModel
    let scene: PhotoScene
    @Binding var selected: Set<String>
    let drag: DragSelector
    let open: ([MediaItem], Int) -> Void
    let confirm: (DeleteRequest) -> Void

    var body: some View {
        let items = displayIds(scene).compactMap { model.library?.byId[$0] }
        let dropped = scene.members.filter { selected.contains($0.id) }
        let bytes = dropped.reduce(Int64(0)) { $0 + $1.size }
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(Fmt.day(scene.startTime)).font(.headline)
                    Text("\(Fmt.hm(scene.startTime))–\(Fmt.hm(scene.endTime)) · \(scene.members.count) 张").font(.footnote).foregroundStyle(.secondary)
                    if !scene.favorites.isEmpty {
                        Text("另有 \(scene.favorites.count) 张收藏的也留着").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 4), spacing: 3) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                    PhotoCell(item: m, selected: selected.contains(m.id), tag: scene.favorites.contains(m.id) ? "已收藏" : nil,
                              onToggle: { toggle(m.id) }, onOpen: { open(items, i) })
                        .overlay(alignment: .bottomLeading) {
                            if let a = model.library?.aesthetic[m.id] { AesBadge(score: a).padding(4) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .dragSelectCell(m.id, drag)
                }
            }
            HStack(spacing: 18) {
                if !dropped.isEmpty {
                    Button("全部保留") { model.markReviewed(scene.members.map { $0.id }) }
                        .font(.subheadline).foregroundStyle(Color.accentText).fixedSize()
                }
                // 回到原来的做法：除了最好的几张和收藏的都勾上
                let others = scene.members.filter { !scene.highlights.contains($0.id) && !scene.favorites.contains($0.id) }
                if others.contains(where: { !selected.contains($0.id) }) {
                    Button("只留最好的 \(scene.highlights.count) 张") {
                        Usage.add("场景.只留精华")
                        others.forEach { selected.insert($0.id) }
                    }
                    // 不折行：放不下时右边的删除按钮去掉大小
                    .font(.subheadline).foregroundStyle(Color.accentText).fixedSize()
                }
                Spacer()
                if dropped.isEmpty {
                    CapsuleButton(title: "完成") { model.markReviewed(scene.members.map { $0.id }) }
                } else {
                    let run = {
                        confirm(DeleteRequest(items: dropped.compactMap { model.library?.byId[$0.id] }) { items in
                            let ids = Set(items.map { $0.id })
                            let kept = scene.members.map { $0.id }.filter { !ids.contains($0) }
                            Task { await model.delete(items, kept: kept) }
                        })
                    }
                    // 左边有“全部保留”“只留最好的几张”两个时，窄屏放不下大小就只写张数（不然大小被截成“15…”）
                    ViewThatFits(in: .horizontal) {
                        CapsuleButton(title: "删除 \(dropped.count) 张 · \(Fmt.bytes(bytes))", color: .danger, action: run)
                        CapsuleButton(title: "删除 \(dropped.count) 张", color: .danger, action: run)
                    }
                }
            }
        }
        .padding(14).card(16).padding(.horizontal, 16)
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}
