import SwiftUI

/**
 相似照片：一组连拍/重复里只留最好的一张。
 “几乎一样”的（连拍、重复保存）识别很可靠，已经按推荐勾好；
 “角度不同”的最容易判断错（两页不同的文件、不同的人），只标出推荐留哪张，不帮用户勾（和安卓 v2.8.5 一样）。
 每组单独删；底部“全部删除…”要先确认（和安卓版 v2.0 修过的问题一样：页面上的删除不能悄悄删掉没看过的组）。
 */
struct SimilarView: View {
    @EnvironmentObject var model: AppModel
    @State private var near = true
    @State private var selected = Set<String>()
    @State private var initialized: Set<String> = []
    @State private var viewer: ViewerTarget?
    @State private var deleting: DeleteRequest?
    @State private var drag = DragSelector()

    var body: some View {
        let all = model.library?.groups ?? []
        let groups = all.filter { $0.nearIdentical == near }
        let chosen = groups.flatMap { g in g.members.filter { selected.contains($0.photo.id) } }
        let chosenBytes = chosen.reduce(Int64(0)) { $0 + $1.photo.size }
        ScrollView {
            VStack(spacing: 18) {
                Picker("", selection: $near) {
                    Text("几乎一样 \(all.filter { $0.nearIdentical }.count)").tag(true)
                    Text("角度不同 \(all.filter { !$0.nearIdentical }.count)").tag(false)
                }
                .pickerStyle(.segmented).padding(.horizontal, 16).padding(.top, 8)
                Footnote(text: near ? "连拍、重复保存的，每组留最清楚、最好看的一张（绿色标签），其余已经勾上。按住横着一划可以连续选。"
                    : "同一个地方换了角度拍的。这类最容易判断错（比如不同的人、不同页的文件），所以没帮你勾，只标出推荐留哪张；分不出高下的标“差不多”。想删就自己点，或点“按推荐勾选”。")
                PreviewHint()
                if groups.isEmpty {
                    Text("这一类没有了").foregroundStyle(.secondary).padding(.top, 60)
                }
                ForEach(groups) { g in
                    GroupCard(group: g, selected: $selected, drag: drag, open: { items, i in viewer = ViewerTarget(items: items, start: i) },
                              confirm: { deleting = $0 })
                }
            }
            .padding(.bottom, 20)
            // 跨组也能一路划下去（和安卓版一样），每组的“删除”只删自己组里勾上的
            .dragSelect(drag, ids: { groups.flatMap { $0.members.map { $0.photo.id } } }, selection: $selected)
        }
        .background(Color.page)
        .navigationTitle("相似照片")
        .usagePage("相似照片")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !groups.isEmpty {
                BottomBar(title: "还有 \(groups.count) 组", subtitle: chosen.isEmpty ? (near ? "都设成了保留" : "还没勾要删的") : "全部按勾选处理：删 \(chosen.count) 张 · \(Fmt.bytes(chosenBytes))") {
                    CapsuleButton(title: "全部删除…", color: .danger, filled: false, enabled: !chosen.isEmpty) {
                        let items = chosen.compactMap { model.library?.byId[$0.photo.id] }
                        deleting = DeleteRequest(items: items, note: "包括 \(groups.count) 组里你还没看过的。每组按现在的勾选处理。") { items in
                            let ids = Set(items.map { $0.id })
                            // 几乎一样的组是推荐勾好的，没删的算看过、要留；角度不同的只有动过的组才算
                            let kept = groups.filter { g in g.nearIdentical || g.members.contains { ids.contains($0.photo.id) } }
                                .flatMap { $0.members.map { $0.photo.id } }.filter { !ids.contains($0) }
                            let votes = TasteStore.votes(groups, deleted: ids)
                            Task { if await model.delete(items, kept: kept) { TasteStore.record(votes) } }
                        }
                    }
                }
            }
        }
        .onAppear { initSelection(all) }
        .onChange(of: all.map { $0.id }) { initSelection(all) }
        .deleteConfirm($deleting, isMarked: { selected.contains($0) }, onToggle: { id in
            if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        })
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: { id in
                if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
            })
        }
    }
}

extension SimilarView {
    /// 新出现的“几乎一样”的组按推荐勾上（已经看过、改过勾选的不动）；“角度不同”的不勾
    fileprivate func initSelection(_ groups: [RankedGroup]) {
        for g in groups where !initialized.contains(g.id) {
            if g.nearIdentical { g.suggestedDelete.forEach { selected.insert($0.photo.id) } }
            initialized.insert(g.id)
        }
    }
}

private struct GroupCard: View {
    @EnvironmentObject var model: AppModel
    let group: RankedGroup
    @Binding var selected: Set<String>
    let drag: DragSelector
    let open: ([MediaItem], Int) -> Void
    let confirm: (DeleteRequest) -> Void

    var body: some View {
        let items = group.members.compactMap { model.library?.byId[$0.photo.id] }
        let dropped = group.members.filter { selected.contains($0.photo.id) }
        let bytes = dropped.reduce(Int64(0)) { $0 + $1.photo.size }
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("\(Fmt.day(group.startTime)) \(Fmt.hm(group.startTime))").font(.headline)
                Text("· \(group.members.count) 张").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                ForEach(Array(group.members.enumerated()), id: \.element.photo.id) { i, rp in
                    if let m = model.library?.byId[rp.photo.id] {
                        PhotoCell(item: m, selected: selected.contains(m.id), tag: rp.tags.first,
                                  onToggle: { toggle(m.id) }, onOpen: { open(items, i) })
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .dragSelectCell(m.id, drag)
                    }
                }
            }
            HStack {
                if !dropped.isEmpty {
                    Button("全部保留") { model.markReviewed(group.members.map { $0.photo.id }) }
                        .font(.subheadline).foregroundStyle(Color.accentText)
                } else if !group.nearIdentical && !group.suggestedDelete.isEmpty {
                    Button("按推荐勾选") { group.suggestedDelete.forEach { selected.insert($0.photo.id) } }
                        .font(.subheadline).foregroundStyle(Color.accentText)
                }
                Spacer()
                if dropped.isEmpty {
                    CapsuleButton(title: "完成") { model.markReviewed(group.members.map { $0.photo.id }) }
                } else {
                    CapsuleButton(title: "删除 \(dropped.count) 张 · \(Fmt.bytes(bytes))", color: .danger) {
                        confirm(DeleteRequest(items: dropped.compactMap { model.library?.byId[$0.photo.id] }) { items in
                            let ids = Set(items.map { $0.id })
                            let kept = group.members.map { $0.photo.id }.filter { !ids.contains($0) }
                            let votes = TasteStore.votes([group], deleted: ids)
                            Task { if await model.delete(items, kept: kept) { TasteStore.record(votes) } }
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
