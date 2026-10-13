import Photos
import SwiftUI

enum Route: String, Hashable {
    case similar, scenes, bad, screenshots, bigVideos, settings, recent, aesthetic, videos, videoScenes, compress, docs, notes, report
}

/// 首页上的一类建议
struct Category: Identifiable {
    let id: Route
    let title: String
    let summary: String
    let count: Int
    /// 预选删除能腾出的空间；大视频不预选，算 0
    let reclaim: Int64
    let sizeText: String
    let thumbs: [MediaItem]
}

func categories(_ lib: Library) -> [Category] {
    let sceneThumbs = lib.scenes.prefix(4).compactMap { sc in (sc.highlights + sc.favorites).first.flatMap { lib.byId[$0] } }
    let groupThumbs = lib.groups.prefix(4).compactMap { g in g.members.first { $0.best }.flatMap { lib.byId[$0.photo.id] } }
    let old = lib.oldScreenshots()
    return [
        Category(id: .scenes, title: "场景精选", summary: "\(lib.scenes.count) 个场景，各留几张", count: lib.scenes.count,
                 reclaim: lib.sceneDeleteBytes, sizeText: Fmt.bytes(lib.sceneDeleteBytes), thumbs: Array(sceneThumbs)),
        Category(id: .similar, title: "相似照片", summary: "\(lib.groups.count) 组，各留最好的", count: lib.groups.count,
                 reclaim: lib.groupDeleteBytes, sizeText: Fmt.bytes(lib.groupDeleteBytes), thumbs: Array(groupThumbs)),
        Category(id: .bad, title: "废片", summary: "\(lib.bad.count) 张拍糊或误拍", count: lib.bad.count,
                 reclaim: lib.bad.filter { $0.confident }.reduce(0) { $0 + $1.item.size },
                 sizeText: Fmt.bytes(lib.bad.reduce(0) { $0 + $1.item.size }), thumbs: lib.bad.prefix(4).map { $0.item }),
        Category(id: .screenshots, title: "截图", summary: old.isEmpty ? "\(lib.screenshots.count) 张，都是最近的" : "\(old.count) 张超过 \(lib.settings.screenshotDays) 天",
                 count: lib.screenshots.count, reclaim: old.reduce(0) { $0 + $1.size },
                 sizeText: Fmt.bytes(lib.screenshots.reduce(0) { $0 + $1.size }), thumbs: Array(lib.screenshots.prefix(4))),
        Category(id: .videoScenes, title: "视频场景精选", summary: "\(lib.videoScenes.count) 个场景拍了好几段", count: lib.videoScenes.count,
                 reclaim: lib.videoSceneDeleteBytes, sizeText: Fmt.bytes(lib.videoSceneDeleteBytes),
                 thumbs: lib.videoScenes.prefix(4).compactMap { sc in sc.keep.first.flatMap { lib.byId[$0] } }),
        Category(id: .videos, title: "可删的视频", summary: videoSummary(lib), count: lib.videoSuggest.count,
                 reclaim: lib.videoSuggest.filter { $0.confident }.reduce(0) { $0 + $1.item.size },
                 sizeText: Fmt.bytes(lib.videoSuggest.reduce(0) { $0 + $1.item.size }), thumbs: lib.videoSuggest.prefix(4).map { $0.item }),
        Category(id: .bigVideos, title: "大视频", summary: "\(lib.bigVideos.count) 个，自己挑", count: lib.bigVideos.count,
                 reclaim: 0, sizeText: Fmt.bytes(lib.bigVideos.reduce(0) { $0 + $1.size }), thumbs: Array(lib.bigVideos.prefix(4))),
    ]
}

/// “3 个黑屏 · 2 个重复”这种：挑最多的两类说
func videoSummary(_ lib: Library) -> String {
    let counts = Dictionary(grouping: lib.videoSuggest.compactMap { $0.reasons.first }, by: { $0 }).mapValues { $0.count }
        .sorted { $0.value > $1.value }.prefix(2)
    if counts.isEmpty { return "黑屏、误录、重复的" }
    return counts.map { "\($0.value) 个\($0.key.rawValue)" }.joined(separator: "，")
}

/// 从最占地方的那类开始；预选的都清完了，还有大视频就去看大视频
func nextCategory(_ cats: [Category]) -> Category? {
    cats.filter { $0.count > 0 && $0.reclaim > 0 }.max { $0.reclaim < $1.reclaim } ?? cats.first { $0.count > 0 }
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @Binding var path: [Route]

    var body: some View {
        let mark = Diag.begin("首页 body")
        let lib = model.library
        let cats = lib.map(categories) ?? []
        let next = nextCategory(cats)
        let _ = Diag.end("首页 body", mark)
        ScrollView {
            VStack(spacing: 22) {
                BrandTitle()
                if model.auth == .limited { LimitedCard() }
                StorageCard()
                Hero(next: next) { path.append($0) }
                // 视频压缩单独一张卡：省的空间往往比删照片多得多
                if let lib, lib.compressSaving > 50_000_000 || !lib.compressedPending.isEmpty {
                    CompressCard(lib: lib) { path.append(.compress) }
                }
                if let lib, case let n = lib.images.filter({ $0.isCameraShot && $0.timeMs >= Int64((Date().timeIntervalSince1970 - 86_400) * 1000) }).count, n > 0 {
                    RecentRow(count: n) { path.append(.recent) }
                }
                if let lib, !lib.docKinds.isEmpty || !model.praise.isEmpty {
                    AlbumShortcuts(lib: lib, praise: model.praise) { path.append(.docs) }
                }
                if let lib {
                    let todo = cats.filter { $0.count > 0 }
                    let done = lib.analyzed ? cats.filter { $0.count == 0 && ![.bigVideos, .screenshots, .videos, .videoScenes].contains($0.id) } : []
                    if !todo.isEmpty || !done.isEmpty {
                        Text("建议清理").font(.title3.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                            ForEach(todo) { c in
                                CategoryTile(c: c, recommended: c.id == next?.id && c.reclaim > 0) { path.append(c.id) }
                            }
                        }
                        .padding(.horizontal, 16)
                        if !done.isEmpty { DoneRow(titles: done.map { $0.title }) }
                    }
                }
                if let lib, !lib.images.isEmpty {
                    Text("浏览").font(.title3.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
                    if !lib.aesthetic.isEmpty { AestheticCard(lib: lib) { path.append(.aesthetic) } }
                    if !lib.sessions.isEmpty {
                        BrowseRow(icon: "doc.text", title: "截图笔记", subtitle: "\(lib.sessions.count) 串连续截图，拼长图、整理文字") { path.append(.notes) }
                            .card(14).padding(.horizontal, 16)
                    }
                }
                RecentlyDeletedCard()
                if model.cleaned.count > 0 {
                    Text("相册已经轻了 \(Fmt.bytes(model.cleaned.bytes))，清掉 \(Fmt.count(model.cleaned.count)) 项")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(.bottom, 40)
        }
        .background(Color.page)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { path.append(.settings) } label: { Image(systemName: "gearshape").foregroundStyle(Color.accentText) }
            }
        }
        .refreshable { model.refresh() }
        .usagePage("首页")
    }
}

private struct BrandTitle: View {
    var body: some View {
        HStack(spacing: 10) {
            BrandMark(size: 36)
            Text("轻了相册").font(.largeTitle.weight(.bold))
            Spacer()
        }
        .padding(.horizontal, 20)
    }
}

/// 分类相册的快捷入口：一排图标（不放照片缩略图，证件不会出现在首页）。好评在最前，最后是“全部分类”
private struct AlbumShortcuts: View {
    let lib: Library
    let praise: Set<String>
    let openAll: () -> Void

    var body: some View {
        let _ = Diag.log("分类相册那一排 重算")
        let groups = docGroups.map { g in (g, lib.docKinds.values.filter { g.kinds.contains($0) }.count) }.filter { $0.1 > 0 }
        let praised = praise.filter { lib.byId[$0] != nil }.count
        VStack(alignment: .leading, spacing: 10) {
            Text("分类相册").font(.title3.weight(.semibold)).padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    if praised > 0 {
                        NavigationLink { AlbumView(title: "好评", note: praiseNote, kinds: [], praiseable: true, praiseOnly: true) } label: {
                            shortcut("star.fill", "好评", praised, .brandYellow)
                        }
                    }
                    ForEach(groups, id: \.0.id) { g, n in
                        NavigationLink { AlbumView(title: g.title, note: g.note, kinds: g.kinds, praiseable: g.praiseable, cleanup: g.cleanup) } label: {
                            shortcut(g.icon, g.title, n, .brand)
                        }
                    }
                    Button(action: openAll) { shortcut("square.grid.2x2", "全部分类", nil, .gray) }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                // 图标之间的空隙也算这一排：手指落在空隙里时，横着滑也是滑这一排
                .contentShape(Rectangle())
            }
            // 一屏放得下就不用左右滑，也不弹来弹去
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        }
    }

    private func shortcut(_ icon: String, _ title: String, _ count: Int?, _ color: Color) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 24)).foregroundStyle(color)
                .frame(width: 64, height: 64)
                .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            Text(title).font(.caption).foregroundStyle(.primary).lineLimit(1)
            Text(count.map { "\($0) 张" } ?? " ").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(width: 76)
        .contentShape(Rectangle())
    }
}

/// 刚拍了照片：拍完马上看评分
private struct RecentRow: View {
    let count: Int
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "camera.fill").foregroundStyle(.white).frame(width: 30, height: 30)
                    .background(Color.brand, in: RoundedRectangle(cornerRadius: 7))
                VStack(alignment: .leading, spacing: 2) {
                    Text("刚拍的").foregroundStyle(.primary)
                    Text("最近 24 小时拍了 \(count) 张，看评分，当场删掉拍得不好的").font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(14).card(14)
        }
        .buttonStyle(.plain).padding(.horizontal, 16)
    }
}

/// 只允许了访问部分照片
private struct LimitedCard: View {
    var body: some View {
        Button {
            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "photo.on.rectangle").foregroundStyle(.white).frame(width: 30, height: 30)
                    .background(Color.brand, in: RoundedRectangle(cornerRadius: 7))
                VStack(alignment: .leading, spacing: 2) {
                    Text("只允许了访问部分照片").foregroundStyle(.primary)
                    Text("点这里改成“完全访问”，才能找出重复和拍糊的").font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(14).card(14)
        }
        .buttonStyle(.plain).padding(.horizontal, 16)
    }
}

// ---------- 手机存储 ----------

/// 第一张卡：手机存储，照片、视频、其他、可用各占多少（iPhone“储存空间”那样的分段条）
private struct StorageCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let st = model.storage {
            let lib = model.library
            let total = Double(max(1, st.total))
            let photos = Double(lib?.imageBytes ?? 0) / total
            let videos = Double(lib?.videoBytes ?? 0) / total
            let rest = max(0, Double(st.total - st.free) / total - photos - videos)
            let track = Color.gray.opacity(0.16), other = Color.gray.opacity(0.5)
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text("手机存储").font(.headline)
                    Spacer()
                    Text("已用 \(Fmt.bytes(st.total - st.free)) / \(Fmt.bytes(st.total))").font(.footnote).foregroundStyle(.secondary)
                }
                GeometryReader { g in
                    HStack(spacing: 1.5) {
                        segment(photos, .brand, g.size.width)
                        segment(videos, .brandYellow, g.size.width)
                        segment(rest, other, g.size.width)
                        Spacer(minLength: 0)
                    }
                }
                .frame(height: 20).background(track).clipShape(RoundedRectangle(cornerRadius: 6)).padding(.top, 12)
                HStack(spacing: 14) {
                    legend(.brand, "照片"); legend(.brandYellow, "视频"); legend(other, "其他"); legend(track, "可用")
                }
                .padding(.top, 10)
                if let lib {
                    Divider().padding(.vertical, 12)
                    HStack(alignment: .top) {
                        stat("照片", Fmt.bytes(lib.imageBytes), "\(Fmt.count(lib.images.count)) 张")
                        stat("视频", Fmt.bytes(lib.videoBytes), "\(Fmt.count(lib.videos.count)) 个")
                        stat("可用", Fmt.bytes(st.free), st.free < st.total / 10 ? "快满了" : "", warn: true)
                    }
                }
            }
            .padding(18).card().padding(.horizontal, 16)
        }
    }

    @ViewBuilder private func segment(_ f: Double, _ c: Color, _ w: CGFloat) -> some View {
        if f > 0.002 { c.frame(width: max(1, w * f)) }
    }

    private func legend(_ c: Color, _ t: String) -> some View {
        HStack(spacing: 3) { Circle().fill(c).frame(width: 9, height: 9); Text(t).font(.caption).foregroundStyle(.secondary) }
    }

    private func stat(_ label: String, _ value: String, _ sub: String, warn: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.footnote).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.bold))
            if !sub.isEmpty { Text(sub).font(.caption).foregroundStyle(warn ? Color.danger : .secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// ---------- 相册还能轻多少 ----------

/// 分析进度的数字和进度条：只有这一块跟着进度刷新
private struct AnalyzingNumbers: View {
    @ObservedObject var live: LiveProgress

    var body: some View {
        let p = live.progress
        VStack(alignment: .leading, spacing: 0) {
            Text(p.label).font(.subheadline).foregroundStyle(.secondary)
            Text(p.total > 0 ? "\(Int(p.fraction * 100))%" : "…").font(.system(size: 56, weight: .bold)).tracking(-1)
            if p.total > 0 { Text("\(Fmt.count(p.done)) / \(Fmt.count(p.total))").font(.subheadline).foregroundStyle(.secondary) }
            ProgressView(value: p.fraction).tint(.brand).padding(.top, 14)
        }
    }
}

/// 第二张卡：相册还能轻多少 + 开始整理；分析中显示进度
private struct Hero: View {
    @EnvironmentObject var model: AppModel
    let next: Category?
    let open: (Route) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.progress.running {
                analyzing
            } else if let lib = model.library {
                if !lib.analyzed { firstAnalysis(lib) } else { reclaim(lib) }
            } else {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("正在读取相册…第一次打开要算每张照片多大，照片多的话要等一会儿").font(.footnote).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, minHeight: 160)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card().padding(.horizontal, 16)
    }

    private var analyzing: some View {
        VStack(alignment: .leading, spacing: 0) {
            AnalyzingNumbers(live: model.live)
            HStack {
                Text("全在手机里算，照片不上传。别锁屏；切到别的 App 会暂停，回来自动接着分析，做完的不会重来。").font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button("暂停") { model.pauseAnalysis() }.font(.subheadline).foregroundStyle(Color.accentText)
            }
            .padding(.top, 8)
        }
    }

    private func firstAnalysis(_ lib: Library) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("先把相册过一遍").font(.title2.weight(.bold))
            Text("\(Fmt.count(lib.images.count)) 张照片，全部在手机里分析。几千张几分钟，建议边充电边等，别锁屏。")
                .font(.subheadline).foregroundStyle(.secondary)
            if let m = model.progress.message { Text(m).font(.footnote).foregroundStyle(.secondary) }
            PrimaryButton(title: "开始分析") { model.startAnalysis() }.padding(.top, 12)
        }
    }

    @ViewBuilder private func reclaim(_ lib: Library) -> some View {
        let r = lib.reclaimable()
        if r.bytes > 0 {
            Text("相册还能轻").font(.subheadline).foregroundStyle(.secondary)
            BigNumber(bytes: r.bytes)
            Text("约 \(Fmt.count(r.count)) 张，已经替你挑好了" + (next.map { "，先清\($0.title)" } ?? ""))
                .font(.subheadline).foregroundStyle(.secondary)
        } else {
            HStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 34)).foregroundStyle(Color.brand)
                VStack(alignment: .leading) {
                    Text("相册轻了").font(.title2.weight(.bold))
                    Text(next.map { "建议的都清完了，可以看看\($0.title)" } ?? "没有需要清理的").font(.subheadline).foregroundStyle(.secondary)
                }
            }
        }
        if let next {
            PrimaryButton(title: r.bytes > 0 ? "开始整理" : "看看\(next.title)") { open(next.id) }.padding(.top, 16)
        }
        if lib.unanalyzed > 0 || lib.videosUnanalyzed > 0 {
            HStack {
                Text("还有 " + [lib.unanalyzed > 0 ? "\(Fmt.count(lib.unanalyzed)) 张新照片" : nil,
                               lib.videosUnanalyzed > 0 ? "\(Fmt.count(lib.videosUnanalyzed)) 个视频" : nil].compactMap { $0 }.joined(separator: "、") + "没分析")
                    .font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button("分析") { model.startAnalysis() }.font(.subheadline).foregroundStyle(Color.accentText)
            }
            .padding(.top, 10)
        }
    }
}

/// 大数字：数字大、单位小，数字下面垫一道黄色荧光笔
private struct BigNumber: View {
    let bytes: Int64
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let text = Fmt.bytes(bytes)
        let num = String(text.split(separator: " ").first ?? "")
        let unit = String(text.split(separator: " ").last ?? "")
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(num).font(.system(size: 56, weight: .bold)).tracking(-1)
                .background(alignment: .bottom) {
                    GeometryReader { g in
                        // 深色模式下黄色垫在白字下面太糊，改成一道下划线
                        let dark = scheme == .dark
                        Color.brandYellow.opacity(dark ? 1 : 0.85)
                            .frame(width: g.size.width + 4, height: g.size.height * (dark ? 0.08 : 0.32))
                            .offset(x: -2, y: g.size.height * (dark ? 0.80 : 0.58))
                    }
                }
            Text(unit).font(.system(size: 24, weight: .semibold))
        }
    }
}

// ---------- 分类卡片 ----------

private struct CategoryTile: View {
    let c: Category
    let recommended: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                Mosaic(items: c.thumbs)
                VStack(alignment: .leading, spacing: 1) {
                    HStack {
                        Text(c.title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    HStack(spacing: 8) {
                        Text(c.sizeText).font(.title3.weight(.semibold)).foregroundStyle(c.reclaim > 0 ? Color.accentText : .primary)
                        if recommended { YellowChip(text: "先清这个") }
                    }
                    Text(c.summary).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .padding(.horizontal, 6).padding(.top, 10).padding(.bottom, 4)
            }
            .padding(8).card(18)
        }
        .buttonStyle(.plain)
    }
}

/// 美感排行：一排拍得最好的 4 张
private struct AestheticCard: View {
    let lib: Library
    let action: () -> Void
    var body: some View {
        // 美感分最高的 4 张，画面几乎一样的只取一张
        let top: [MediaItem] = {
            var picked: [MediaItem] = []
            var feats: [ImageFeatures] = []
            for (id, _) in lib.aesthetic.sorted(by: { $0.value > $1.value }) {
                guard let m = lib.byId[id] else { continue }
                if let f = lib.features[id], feats.contains(where: { o in
                    let h = Similarity.hashDistance(f, o)
                    return h <= 20 || (h <= 50 && Similarity.colorDistance(f.color, o.color) <= 0.06)
                }) { continue }
                picked.append(m)
                if let f = lib.features[id] { feats.append(f) }
                if picked.count == 4 { break }
            }
            return picked
        }()
        let low = lib.aesthetic.values.filter { aesRounded($0) <= aesLow }.count
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 2) {
                    ForEach(0..<4, id: \.self) { i in
                        Group {
                            if i < top.count { AssetThumb(item: top[i]) } else { Color.fillSoft }
                        }
                        .aspectRatio(1, contentMode: .fit)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("美感排行").font(.headline).foregroundStyle(.primary)
                        Text("\(Fmt.count(lib.aesthetic.count)) 张按 1–10 分排好" + (low > 0 ? " · \(Fmt.count(low)) 张 4.3 分及以下" : ""))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .padding(.horizontal, 6).padding(.bottom, 4)
            }
            .padding(8).card(18)
        }
        .buttonStyle(.plain).padding(.horizontal, 16)
    }
}

/// 视频压缩：能省多少、有几个压好了等确认
private struct CompressCard: View {
    let lib: Library
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack(alignment: .bottomLeading) {
                    if let m = lib.compressible.first?.item ?? lib.compressedPending.first?.source { AssetThumb(item: m) } else { Color.fillSoft }
                    Image(systemName: "play.fill").font(.system(size: 12)).foregroundStyle(.white).padding(5)
                }
                .frame(width: 84, height: 84).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("压缩视频").font(.headline).foregroundStyle(.primary)
                    if !lib.compressedPending.isEmpty {
                        Text("\(lib.compressedPending.count) 个压好了，等你确认").font(.footnote).foregroundStyle(Color.accentText)
                    }
                    if lib.compressSaving > 0 {
                        Text("\(Fmt.count(lib.compressible.count)) 个视频能再省 \(Fmt.bytes(lib.compressSaving))")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentText)
                        Text("转成 1080p H.265，手机上看几乎没区别").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(8).card(18)
        }
        .buttonStyle(.plain).padding(.horizontal, 16)
    }
}

/// “浏览”里的一行
private struct BrowseRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).foregroundStyle(.white).frame(width: 30, height: 30)
                    .background(Color.brand, in: RoundedRectangle(cornerRadius: 7))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(14).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 清完的类：一行绿色对勾
private struct DoneRow: View {
    let titles: [String]
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.brand).font(.title3)
            Text("已清完").font(.subheadline.weight(.semibold))
            Text(titles.joined(separator: "、")).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 12).card(14).padding(.horizontal, 16)
    }
}

/// 删掉的在系统“最近删除”里；App 打不开那个相簿，只能跳到“照片”App
private struct RecentlyDeletedCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Button {
                if let u = URL(string: "photos-redirect://") { UIApplication.shared.open(u) }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "trash").foregroundStyle(.white).frame(width: 30, height: 30)
                        .background(Color.gray, in: RoundedRectangle(cornerRadius: 7))
                    Text("去「照片」清空最近删除").foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .padding(14).card(14)
            }
            .buttonStyle(.plain)
            Text("删掉的先放在「照片」App 的「最近删除」里，30 天后自动彻底删除。放在那里时仍然占空间。")
                .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 16)
        }
        .padding(.horizontal, 16)
    }
}
