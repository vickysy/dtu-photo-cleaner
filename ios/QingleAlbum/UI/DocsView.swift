import SwiftUI

/// 分类相册里的一类
struct DocGroup: Identifiable {
    let title: String
    let kinds: Set<ShotKind>
    let note: String
    let icon: String
    /// 能设为好评（聊天记录）
    var praiseable = false
    /// 清理用的类：一打开就在选择状态，3 天前的已勾上
    var cleanup = false
    var id: String { title }
}

let docGroups: [DocGroup] = [
    DocGroup(title: "证件", kinds: [.id], note: "身份证、银行卡、护照、驾驶证、社保卡、营业执照……永远不会建议删除。证件上的长串号码在手机里也是打了码存的。", icon: "person.text.rectangle"),
    DocGroup(title: "聊天记录", kinds: [.chat], note: "微信聊天截图（认绿色气泡），和中间带时间的聊天记录。学员、客户夸你的，可以设为好评单独收着。", icon: "bubble.left.and.bubble.right", praiseable: true),
    DocGroup(title: "文章与文件", kinds: [.longText, .doc], note: "文章、笔记、PPT 截图，和拍的文件、书页、课件。", icon: "doc.text"),
    DocGroup(title: "票据行程", kinds: [.ticket], note: "车票、机票、门票、发票、小票。", icon: "ticket"),
    DocGroup(title: "订单物流", kinds: [.order], note: "订单、退款、物流截图。", icon: "shippingbox"),
    DocGroup(title: "用完就没用的", kinds: [.pickup, .code, .pay, .qr], note: "取件码、验证码、付款码、二维码。3 天前的已经勾选。", icon: "qrcode", cleanup: true),
]

let praiseNote = "设为好评的聊天截图都在这里，不会被建议删除。多选可以拼成一张长图，发朋友圈、做宣传都方便。"

/// 首页一行说明：“证件 3 · 聊天记录 40 · 文章与文件 12”
func docsSummary(_ lib: Library) -> String {
    let counts = docGroups.map { g in (g.title, lib.docKinds.values.filter { g.kinds.contains($0) }.count) }.filter { $0.1 > 0 }
    if counts.isEmpty { return lib.textsPending > 0 ? "还在认图里的字……" : "按图里的字分类：证件、聊天记录、文章与文件……" }
    return counts.prefix(3).map { "\($0.0) \($0.1)" }.joined(separator: " · ")
}

/// 分类相册：按图里的字分好类，找证件、聊天记录、文章都方便；点开就是一个相册
struct DocsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let lib = model.library
        let groups: [(DocGroup, [MediaItem])] = docGroups.compactMap { g in
            guard let lib else { return nil }
            let items = lib.docKinds.filter { g.kinds.contains($0.value) }.compactMap { lib.byId[$0.key] }.sorted { $0.timeMs > $1.timeMs }
            return items.isEmpty ? nil : (g, items)
        }
        let praised = model.praise.compactMap { lib?.byId[$0] }.sorted { $0.timeMs > $1.timeMs }
        ScrollView {
            VStack(spacing: 12) {
                Footnote(text: "认了每张截图、存下来的图和拍的文件里的字，按内容分成几个相册，找东西方便。全在手机里识别，不上传。" +
                    ((lib?.textsPending ?? 0) > 0 ? "\n还有 \(Fmt.count(lib!.textsPending)) 张没识字，分析时会接着识。" : ""))
                    .padding(.top, 8)
                if groups.isEmpty && praised.isEmpty {
                    Text((lib?.textsPending ?? 0) > 0 ? "还在识字，晚点再来看" : "没有找到带字的截图和照片").foregroundStyle(.secondary).padding(.top, 60)
                }
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    if !praised.isEmpty {
                        tile("好评", "star.fill", praised, tint: .brandYellow) { AlbumView(title: "好评", note: praiseNote, kinds: [], praiseable: true, praiseOnly: true) }
                    }
                    ForEach(groups, id: \.0.id) { g, items in
                        tile(g.title, g.icon, items, tint: .brand) {
                            AlbumView(title: g.title, note: g.note, kinds: g.kinds, praiseable: g.praiseable, cleanup: g.cleanup)
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 24)
        }
        .background(Color.page)
        .navigationTitle("文字与证件")
        .usagePage("文字与证件")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func tile<D: View>(_ title: String, _ icon: String, _ items: [MediaItem], tint: Color, @ViewBuilder dest: @escaping () -> D) -> some View {
        NavigationLink(destination: dest) {
            VStack(alignment: .leading, spacing: 0) {
                Mosaic(items: Array(items.prefix(4)))
                HStack(spacing: 6) {
                    Image(systemName: icon).font(.subheadline).foregroundStyle(tint)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(title).font(.headline).foregroundStyle(.primary)
                        Text("\(Fmt.count(items.count)) 张 · \(Fmt.bytes(items.reduce(0) { $0 + $1.size }))").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 6).padding(.top, 10).padding(.bottom, 4)
            }
            .padding(8).card(18)
        }
        .buttonStyle(.plain)
    }
}

/**
 分类相册（证件、聊天记录、文章与文件、好评……）：
 - 平时像相册一样：点一下看大图，大图下面能分享、设为好评、删除；
 - 点右上角“选择”（或长按一张）进入选择：底部能拼成长图分享（按拍摄时间从早到晚）、分享原图、设为好评、删除。
 “用完就没用的”一打开就在选择状态，3 天前的已经勾上。和安卓版一样。
 */
struct AlbumView: View {
    let title: String
    let note: String
    let kinds: Set<ShotKind>
    var praiseable = false
    var cleanup = false
    /// 好评相册：只看设为好评的
    var praiseOnly = false
    /// 截图笔记：固定的一串截图（按时间从早到晚排），底部多一个“整理成文字”
    var fixedIds: [String]? = nil

    @EnvironmentObject var model: AppModel
    @State private var selecting = false
    @State private var selected = Set<String>()
    @State private var seen = Set<String>()
    @State private var onlyPraise = false
    @State private var viewer: ViewerTarget?
    @State private var deleting: DeleteRequest?
    @State private var share: ShareItems?
    @State private var drag = DragSelector()

    private var all: [MediaItem] {
        guard let lib = model.library else { return [] }
        if let fixedIds { return fixedIds.compactMap { lib.byId[$0] }.sorted { $0.timeMs < $1.timeMs } }
        let ids: [String] = praiseOnly ? Array(model.praise) : lib.docKinds.filter { kinds.contains($0.value) }.map { $0.key }
        return ids.compactMap { lib.byId[$0] }.sorted { $0.timeMs > $1.timeMs }
    }

    var body: some View {
        let items = all
        let visible = onlyPraise ? items.filter { model.praise.contains($0.id) } : items
        let chosen = items.filter { selected.contains($0.id) }
        let praisedCount = items.filter { model.praise.contains($0.id) }.count
        let allSelected = !visible.isEmpty && visible.allSatisfy { selected.contains($0.id) }
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Footnote(text: note + "\n" + (selecting ? "点照片勾选，按住慢慢横着划可以连续选，点放大镜看大图。" :
                    fixedIds != nil ? "点开看大图，左右滑一张张看；点右上角“选择”挑出要的几张，再拼成长图或整理成文字。"
                    : "点开看大图；点右上角“选择”可以多选，拼成长图发给朋友或得到。"))
                    .padding(.top, 8)
                if praiseable && !praiseOnly && praisedCount > 0 {
                    HStack(spacing: 8) {
                        pill("全部", !onlyPraise) { onlyPraise = false }
                        pill("好评 \(praisedCount)", onlyPraise) { onlyPraise.toggle() }
                    }
                    .padding(.horizontal, 16)
                }
                if visible.isEmpty {
                    Text(onlyPraise || praiseOnly ? "还没有设为好评的" : "这里没有图片了").foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, 60)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { i, m in
                        cell(m, i, visible).dragSelectCell(m.id, drag)
                    }
                }
            }
            .padding(.bottom, 20)
            .dragSelect(drag, ids: { visible.map { $0.id } }, selection: $selected, enabled: selecting)
        }
        .background(Color.page)
        .navigationTitle(title)
        .usagePage(fixedIds != nil ? "截图笔记·一串截图" : "文字与证件·\(title)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !visible.isEmpty {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if selecting {
                        Button(allSelected ? "全不选" : "全选") {
                            if allSelected { selected.subtract(visible.map { $0.id }) } else { selected.formUnion(visible.map { $0.id }) }
                        }
                        if !cleanup { Button("取消") { selecting = false; selected.removeAll() }.fontWeight(.semibold) }
                    } else {
                        Button("选择") { selecting = true }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting { toolbar(chosen) }
        }
        .onAppear { setup(items) }
        .deleteConfirm($deleting, isMarked: { selected.contains($0) }, onToggle: toggle)
        .fullScreenCover(item: $viewer) { v in
            if selecting {
                Viewer(items: v.items, index: v.start, isMarked: { selected.contains($0) }, onToggle: toggle)
            } else {
                Viewer(items: v.items, index: v.start, actions: viewerActions(v.items))
            }
        }
        .sheet(item: $share) { s in ShareSheet(items: s.items) }
    }

    // ---------- 格子 ----------

    private func cell(_ m: MediaItem, _ i: Int, _ visible: [MediaItem]) -> some View {
        // 截图笔记里的截图很长，格子也高一点，多看到一些内容
        AssetThumb(item: m, side: 240)
            .aspectRatio(fixedIds != nil ? 0.5 : 0.75, contentMode: .fit)
            .overlay(selecting && selected.contains(m.id) ? Color.white.opacity(0.22) : .clear)
            .overlay(alignment: .topLeading) {
                if model.praise.contains(m.id) { PhotoBadge(text: "好评", icon: "star.fill", iconTint: .brandYellow).padding(4) }
            }
            .overlay(alignment: .bottomTrailing) {
                if selecting { SelectCircle(selected: selected.contains(m.id), size: 22, tint: .brand).padding(5) }
            }
            .overlay(alignment: .topTrailing) {
                if selecting { ZoomButton { Usage.add("看大图"); viewer = ViewerTarget(items: visible, start: i) } }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if selecting {
                    Usage.add((selected.contains(m.id) ? "取消勾选." : "勾选.") + Usage.page)
                    toggle(m.id)
                } else {
                    Usage.add("看大图")
                    viewer = ViewerTarget(items: visible, start: i)
                }
            }
            // 长按：看图时进入选择并选中这张；选择时看大图
            .onLongPressGesture {
                if selecting { viewer = ViewerTarget(items: visible, start: i) } else { selecting = true; selected.insert(m.id) }
            }
    }

    private func pill(_ text: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.subheadline.weight(on ? .semibold : .regular)).foregroundStyle(on ? .white : .primary)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(on ? Color.brand : Color.fillSoft, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // ---------- 选择时的底部工具栏 ----------

    private func toolbar(_ chosen: [MediaItem]) -> some View {
        let any = !chosen.isEmpty
        let allPraised = any && chosen.allSatisfy { model.praise.contains($0.id) }
        return HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 1) {
                Text("已选 \(chosen.count) 张").font(.subheadline.weight(.semibold))
                Text(Fmt.bytes(chosen.reduce(0) { $0 + $1.size })).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            tool("rectangle.split.1x2", model.stitching ? "拼图中…" : "拼长图", enabled: any && !model.stitching) {
                Task {
                    // 截图笔记：整理好的文字同时复制到剪贴板（发到得到、备忘录时粘贴，字可以直接搜）
                    let note = fixedIds == nil ? nil : ScreenText.buildNote(title: title, pages: chosen.sorted { $0.timeMs < $1.timeMs }.map { model.library?.shotTexts[$0.id] ?? "" })
                    let urls = await model.stitchForSharing(chosen, title: title, note: note)
                    if !urls.isEmpty {
                        share = ShareItems(items: urls)
                        if !cleanup { selecting = false; selected.removeAll() }
                    }
                }
            }
            if fixedIds != nil {
                tool("text.alignleft", "整理成文字", enabled: any) {
                    let pages = chosen.sorted { $0.timeMs < $1.timeMs }.map { model.library?.shotTexts[$0.id] ?? "" }
                    Usage.add("整理成文字")
                    share = ShareItems(items: [ScreenText.buildNote(title: title, pages: pages)])
                }
            }
            tool("square.and.arrow.up", "分享", enabled: any) {
                Task { let urls = await model.originals(chosen); if !urls.isEmpty { share = ShareItems(items: urls) } }
            }
            if praiseable {
                tool("star.fill", allPraised ? "取消好评" : "设为好评", enabled: any, tint: .brandYellow) {
                    model.setPraise(chosen.map { $0.id }, on: !allPraised)
                    if !cleanup { selecting = false; selected.removeAll() }
                }
            }
            tool("trash", "删除", enabled: any, tint: .danger) {
                deleting = DeleteRequest(items: chosen) { items in Task { await delete(items) } }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }

    private func tool(_ icon: String, _ label: String, enabled: Bool, tint: Color = .accentText, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon).font(.system(size: 20)).foregroundStyle(enabled ? tint : Color.secondary.opacity(0.5))
                Text(label).font(.caption2).foregroundStyle(enabled ? .primary : .secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!enabled)
    }

    // ---------- 看大图时的按钮 ----------

    private func viewerActions(_ items: [MediaItem]) -> [ViewerAction] {
        var out = [ViewerAction(icon: "square.and.arrow.up", label: { _ in "分享" }) { id in
            guard let m = items.first(where: { $0.id == id }) else { return }
            Task { let urls = await model.originals([m]); viewer = nil; if !urls.isEmpty { share = ShareItems(items: urls) } }
        }]
        if praiseable {
            out.append(ViewerAction(icon: "star.fill", label: { model.praise.contains($0) ? "取消好评" : "设为好评" },
                                    tint: { model.praise.contains($0) ? .brandYellow : .white }) { id in
                model.setPraise([id], on: !model.praise.contains(id))
            })
        }
        out.append(ViewerAction(icon: "trash", label: { _ in "删除" }) { id in
            guard let m = items.first(where: { $0.id == id }) else { return }
            Task { await delete([m]); viewer = nil }
        })
        return out
    }

    // ---------- 其他 ----------

    /// 清理用的类一打开就在选择状态：用完就没用的（取件码、验证码……）3 天前的勾上；证件永远不勾
    private func setup(_ items: [MediaItem]) {
        guard cleanup else { return }
        selecting = true
        let cutoff = Int64((Date().timeIntervalSince1970 - 3 * 86_400) * 1000)
        for m in items where !seen.contains(m.id) {
            if let k = model.library?.docKinds[m.id], k.disposable, m.timeMs < cutoff, !m.favorite { selected.insert(m.id) }
            seen.insert(m.id)
        }
    }

    private func delete(_ items: [MediaItem]) async {
        if await model.delete(items) { selected.subtract(items.map { $0.id }) }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }
}
