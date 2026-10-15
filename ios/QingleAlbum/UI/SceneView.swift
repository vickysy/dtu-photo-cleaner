import SwiftUI

/**
 场景精选：同一个地方拍了一堆，推荐留几张精华（兼顾好看和不重复），其余默认勾选。每张标美感分（和安卓一样）。
 每个场景单独删、单独“全部保留”；底部“全部删除…”要先确认。
 */
struct SceneListView: View {
    @EnvironmentObject var model: AppModel
    /// 用户改过“留几张”的场景：场景 id → 张数
    @State private var keepOverride: [String: Int] = [:]
    @State private var selected = Set<String>()
    @State private var initialized: Set<String> = []
    @State private var viewer: ViewerTarget?
    @State private var deleting: DeleteRequest?
    @State private var drag = DragSelector()

    private func scene(_ sc: PhotoScene) -> PhotoScene {
        guard let k = keepOverride[sc.id], k != sc.keepCount else { return sc }
        return SceneDetector.build(sc.members, keep: k)
    }

    var body: some View {
        let scenes = (model.library?.scenes ?? []).map(scene)
        let chosenIds = scenes.flatMap { $0.members.map { $0.id }.filter { selected.contains($0) } }
        let chosenBytes = chosenIds.reduce(Int64(0)) { $0 + (model.library?.byId[$1]?.size ?? 0) }
        ScrollView {
            VStack(spacing: 18) {
                Footnote(text: "同一个地方连续拍了很多张时，帮你挑几张精华：构图好看、拍得清楚、没人闭眼，而且尽量是不同的画面。每张左下角是美感分（1–10）。其余已经勾选，按住横着一划可以连续切换。只点这个场景自己的“删除”，不会动别的场景。")
                    .padding(.top, 8)
                PreviewHint()
                if scenes.isEmpty { Text("场景都处理完了").foregroundStyle(.secondary).padding(.top, 60) }
                ForEach(scenes) { sc in
                    SceneCard(scene: sc, selected: $selected, drag: drag,
                              setKeep: { k in
                                  keepOverride[sc.id] = k
                                  let rebuilt = SceneDetector.build(sc.members, keep: k)
                                  sc.members.forEach { selected.remove($0.id) }
                                  let keep = rebuilt.keepIds
                                  rebuilt.members.filter { !keep.contains($0.id) }.forEach { selected.insert($0.id) }
                              },
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
        .onChange(of: scenes.map { $0.id }) { initSelection(scenes) }
        .deleteConfirm($deleting, isMarked: { selected.contains($0) }, onToggle: { id in
            if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        })
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: { id in
                if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
            })
        }
    }

    /// 新出现的场景按推荐勾上（已经看过、改过勾选的不动）
    private func initSelection(_ scenes: [PhotoScene]) {
        for sc in scenes where !initialized.contains(sc.id) {
            let keep = sc.keepIds
            sc.members.filter { !keep.contains($0.id) }.forEach { selected.insert($0.id) }
            initialized.insert(sc.id)
        }
    }
}

/// 卡片里的先后顺序：精华在前（按推荐顺序），然后是收藏的，其余按时间
private func displayIds(_ scene: PhotoScene) -> [String] {
    let keep = scene.keepIds
    let members = Set(scene.members.map { $0.id })
    return (scene.highlights + scene.favorites).filter { members.contains($0) } + scene.members.map { $0.id }.filter { !keep.contains($0) }
}

private struct SceneCard: View {
    @EnvironmentObject var model: AppModel
    let scene: PhotoScene
    @Binding var selected: Set<String>
    let drag: DragSelector
    let setKeep: (Int) -> Void
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
                Text("留 \(scene.keepCount) 张精华").font(.subheadline)
                Stepper("", value: Binding(get: { scene.keepCount }, set: { setKeep($0) }), in: 1...max(1, scene.members.count - scene.favorites.count))
                    .labelsHidden()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 4), spacing: 3) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                    PhotoCell(item: m, selected: selected.contains(m.id), tag: scene.highlights.contains(m.id) ? "精华" : scene.favorites.contains(m.id) ? "已收藏" : nil,
                              onToggle: { toggle(m.id) }, onOpen: { open(items, i) })
                        .overlay(alignment: .bottomLeading) {
                            if let a = model.library?.aesthetic[m.id] { AesBadge(score: a).padding(4) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .dragSelectCell(m.id, drag)
                }
            }
            HStack {
                if !dropped.isEmpty {
                    Button("全部保留") { model.markReviewed(scene.members.map { $0.id }) }
                        .font(.subheadline).foregroundStyle(Color.accentText)
                }
                Spacer()
                if dropped.isEmpty {
                    CapsuleButton(title: "完成") { model.markReviewed(scene.members.map { $0.id }) }
                } else {
                    CapsuleButton(title: "删除 \(dropped.count) 张 · \(Fmt.bytes(bytes))", color: .danger) {
                        confirm(DeleteRequest(items: dropped.compactMap { model.library?.byId[$0.id] }) { items in
                            let ids = Set(items.map { $0.id })
                            let kept = scene.members.map { $0.id }.filter { !ids.contains($0) }
                            Task { await model.delete(items, kept: kept) }
                        })
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
