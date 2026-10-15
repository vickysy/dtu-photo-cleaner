import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 系统分享面板（微信、得到、备忘录……）。图片文件换成 [ShareImage] 交出去
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items.map { item in
            if let url = item as? URL, let image = ShareImage(url: url) { return image }
            return item
        }, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/**
 分享出去的一张图：以“图片”的身份交给分享面板，不是“文件”。
 很多 App 的分享规则是“图片能收好几张、文件只收一个”：以前分享的是图片文件，
 选一张时得到大脑、Get笔记还在列表里，选两张就不见了。
 列表里按“图片”出现；真正交出去的是原文件（保留文件名），存回相册给图片。
 */
final class ShareImage: NSObject, UIActivityItemSource {
    let url: URL
    private let type: UTType
    private let thumb: UIImage

    init?(url: URL) {
        guard url.isFileURL, let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image),
              let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: 240,
              ] as CFDictionary) else { return nil }
        self.url = url
        self.type = type
        self.thumb = UIImage(cgImage: cg)
    }

    func activityViewControllerPlaceholderItem(_ vc: UIActivityViewController) -> Any { thumb }

    func activityViewController(_ vc: UIActivityViewController, itemForActivityType t: UIActivity.ActivityType?) -> Any? {
        // 真正交出去的还是原文件（带 .jpg/.png 文件名）：得到大脑这类 App 看文件名认格式，
        // 只给图片数据会提示“不支持的内容类型”。列表里出不出现，看的是上面那张小图（图片）
        if t == .saveToCameraRoll { return UIImage(contentsOfFile: url.path) }
        return url
    }

    func activityViewController(_ vc: UIActivityViewController, dataTypeIdentifierForActivityType t: UIActivity.ActivityType?) -> String {
        type.identifier
    }

    func activityViewController(_ vc: UIActivityViewController, thumbnailImageForActivityType t: UIActivity.ActivityType?, suggestedSize size: CGSize) -> UIImage? {
        thumb
    }
}

struct ShareItems: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// 一串截图的标题：第一张里第一行像样的文字
func sessionTitle(_ s: ShotSession, _ lib: Library?) -> String {
    s.ids.lazy.compactMap { lib?.shotTexts[$0] }.compactMap { ScreenText.headline($0) }.first ?? "截图笔记"
}

/**
 截图笔记：看直播、上网课时连续截的一串图，拼成长图、整理成文字，发给微信、得到、备忘录。
 点缩略图看大图（左右滑一张张看）；点“挑几张”进去勾出要的，只拼这几张。
 长图存在相册“轻了笔记”里，原来的截图要你自己决定删不删。
 */
struct NotesView: View {
    @EnvironmentObject var model: AppModel
    @State private var busy: String?
    @State private var share: ShareItems?
    @State private var viewer: ViewerTarget?

    var body: some View {
        let lib = model.library
        let sessions = lib?.sessions ?? []
        ScrollView {
            VStack(spacing: 16) {
                Footnote(text: "连续截的一串图（相隔不到 10 分钟、同一个屏幕）。点缩略图看大图；只想要其中几张，点“挑几张”勾出来再拼。长图存在相册的“轻了笔记”里，原来的截图要你自己决定删不删。")
                    .padding(.top, 8)
                if sessions.isEmpty { Text("没有连续截的截图").foregroundStyle(.secondary).padding(.top, 60) }
                ForEach(sessions) { s in
                    let items = s.ids.compactMap { lib?.byId[$0] }.sorted { $0.timeMs < $1.timeMs }
                    let title = sessionTitle(s, lib)
                    VStack(alignment: .leading, spacing: 10) {
                        NavigationLink {
                            AlbumView(title: title, note: "这一串截图按截的先后排。", kinds: [], fixedIds: s.ids)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                                    Text("\(Fmt.day(s.startMs)) \(Fmt.hm(s.startMs))–\(Fmt.hm(s.endMs)) · \(items.count) 张").font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("挑几张").font(.subheadline).foregroundStyle(Color.accentText)
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 4) {
                                ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                                    AssetThumb(item: m).frame(width: 72, height: 140).clipShape(RoundedRectangle(cornerRadius: 6))
                                        .contentShape(Rectangle())
                                        .onTapGesture { viewer = ViewerTarget(items: items, start: i) }
                                }
                            }
                        }
                        HStack {
                            Button("全部整理成文字") {
                                let note = ScreenText.buildNote(title: title, pages: items.map { lib?.shotTexts[$0.id] ?? "" })
                                share = ShareItems(items: [note])
                            }
                            .font(.subheadline).foregroundStyle(Color.accentText)
                            Spacer()
                            if busy == s.id {
                                ProgressView()
                            } else {
                                CapsuleButton(title: "全部拼成长图") { Task { await makeLongImage(items, s.id, title) } }
                            }
                        }
                    }
                    .padding(14).card(16).padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 24)
        }
        .background(Color.page)
        .navigationTitle("截图笔记")
        .usagePage("截图笔记")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $share) { s in ShareSheet(items: s.items) }
        .fullScreenCover(item: $viewer) { v in
            Viewer(items: v.items, index: v.start, actions: [
                ViewerAction(icon: "square.and.arrow.up", label: { _ in "分享" }) { id in
                    guard let m = v.items.first(where: { $0.id == id }) else { return }
                    Task { let urls = await model.originals([m]); viewer = nil; if !urls.isEmpty { share = ShareItems(items: urls) } }
                },
                ViewerAction(icon: "trash", label: { _ in "删除" }) { id in
                    guard let m = v.items.first(where: { $0.id == id }) else { return }
                    Task { if await model.delete([m]) { viewer = nil } }
                },
            ])
        }
    }

    /// 拼长图分享；整理好的文字同时复制到剪贴板
    private func makeLongImage(_ items: [MediaItem], _ key: String, _ title: String) async {
        busy = key
        defer { busy = nil }
        let note = ScreenText.buildNote(title: title, pages: items.map { model.library?.shotTexts[$0.id] ?? "" })
        let urls = await model.stitchForSharing(items, title: title, note: note)
        if !urls.isEmpty { share = ShareItems(items: urls) }
    }
}
