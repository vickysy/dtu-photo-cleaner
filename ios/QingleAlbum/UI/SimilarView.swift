import SwiftUI

/**
 相似照片：一组连拍/重复里只留最好的一张。
 “几乎一样”的（连拍、重复保存）识别很可靠；“角度不同”的最好看一眼。
 每组单独删；底部“全部删除…”要先确认（和安卓版 v2.0 修过的问题一样：页面上的删除不能悄悄删掉没看过的组）。
 */
struct SimilarView: View {
    @EnvironmentObject var model: AppModel
    @State private var near = true
    @State private var selected = Set<String>()
    @State private var initialized: Set<String> = []
    @State private var viewer: ViewerTarget?
    @State private var confirmAll = false

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
                Footnote(text: near ? "连拍、重复保存的，每组留最清楚、最好看的一张（绿色标签），其余已经勾上。"
                    : "同一个地方换了角度拍的。这类最容易判断错（比如不同的人、不同页的文件），删之前看一眼。")
                if groups.isEmpty {
                    Text("这一类没有了").foregroundStyle(.secondary).padding(.top, 60)
                }
                ForEach(groups) { g in
                    GroupCard(group: g, selected: $selected, open: { items, i in viewer = ViewerTarget(items: items, start: i) })
                }
            }
            .padding(.bottom, 20)
        }
        .background(Color.page)
        .navigationTitle("相似照片")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if !groups.isEmpty {
                BottomBar(title: "还有 \(groups.count) 组", subtitle: chosen.isEmpty ? "都设成了保留" : "全部按勾选处理：删 \(chosen.count) 张 · \(Fmt.bytes(chosenBytes))") {
                    CapsuleButton(title: "全部删除…", color: .danger, filled: false, enabled: !chosen.isEmpty) { confirmAll = true }
                }
            }
        }
        .onAppear { initSelection(all) }
        .onChange(of: all.map { $0.id }) { initSelection(all) }
        .alert("删除 \(groups.count) 组里的 \(chosen.count) 张？", isPresented: $confirmAll) {
            Button("取消", role: .cancel) {}
            Button("全部删除", role: .destructive) {
                let kept = groups.flatMap { g in g.members.filter { !selected.contains($0.photo.id) }.map { $0.photo.id } }
                Task { await model.delete(chosen.compactMap { model.library?.byId[$0.photo.id] }, kept: kept) }
            }
        } message: {
            Text("包括你还没看过的组。每组按现在的勾选处理，其余 \(Fmt.bytes(chosenBytes)) 移到「最近删除」，30 天内能恢复。只想删一组，点那组的“删除”。")
        }
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: { id in
                if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
            })
        }
    }
}

extension SimilarView {
    /// 新出现的组按推荐勾上（已经看过、改过勾选的不动）
    fileprivate func initSelection(_ groups: [RankedGroup]) {
        for g in groups where !initialized.contains(g.id) {
            g.suggestedDelete.forEach { selected.insert($0.photo.id) }
            initialized.insert(g.id)
        }
    }
}

private struct GroupCard: View {
    @EnvironmentObject var model: AppModel
    let group: RankedGroup
    @Binding var selected: Set<String>
    let open: ([MediaItem], Int) -> Void

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
                    }
                }
            }
            HStack {
                if !dropped.isEmpty {
                    Button("全部保留") { model.markReviewed(group.members.map { $0.photo.id }) }
                        .font(.subheadline).foregroundStyle(Color.accentText)
                }
                Spacer()
                if dropped.isEmpty {
                    CapsuleButton(title: "完成") { model.markReviewed(group.members.map { $0.photo.id }) }
                } else {
                    CapsuleButton(title: "删除 \(dropped.count) 张 · \(Fmt.bytes(bytes))", color: .danger) {
                        let kept = group.members.filter { !selected.contains($0.photo.id) }.map { $0.photo.id }
                        Task { await model.delete(dropped.compactMap { model.library?.byId[$0.photo.id] }, kept: kept) }
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
