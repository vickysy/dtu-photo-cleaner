import SwiftUI

/// 要确认删除的一批：[items] 是点删除那一刻勾着的；[run] 收到最后还勾着的那些，去真正删除
struct DeleteRequest: Identifiable {
    let id = UUID()
    let items: [MediaItem]
    /// 补充说明删的范围（比如“包括还没看过的组”）
    var note: String? = nil
    let run: ([MediaItem]) -> Void
}

/**
 删之前最后看一眼（和安卓 v2.8.5 一样，用户反馈过“识别不准，误删了”）：
 一排小图列出要删的是哪几张、删到哪里、误删了去哪找回。点小图或“一张张看大图”逐张过一遍，
 看的时候能改成保留；回到这里，张数跟着变。确认后系统还会再问一次。
 */
struct DeleteConfirm: View {
    let request: DeleteRequest
    let isMarked: (String) -> Bool
    let onToggle: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var review: ViewerTarget?

    var body: some View {
        let items = request.items.filter { isMarked($0.id) }
        let unit = !items.isEmpty && items.allSatisfy { $0.isVideo } ? "段视频" : "张"
        VStack(spacing: 0) {
            VStack(spacing: 4) {
                Text(items.isEmpty ? "都改成保留了" : "删除这 \(items.count) \(unit)？").font(.headline)
                if !items.isEmpty {
                    Text(Fmt.bytes(items.reduce(0) { $0 + $1.size })).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(.top, 22)

            if !request.items.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(Array(request.items.enumerated()), id: \.element.id) { i, m in
                            let marked = isMarked(m.id)
                            AssetThumb(item: m, side: 140)
                                .frame(width: 64, height: 64)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                .overlay {
                                    if !marked {
                                        RoundedRectangle(cornerRadius: 6).fill(Color.black.opacity(0.45))
                                        Text("留下").font(.caption2.weight(.semibold)).foregroundStyle(.white)
                                    } else if m.isVideo {
                                        Image(systemName: "play.fill").font(.system(size: 14)).foregroundStyle(.white).shadow(radius: 2)
                                    }
                                }
                                .contentShape(Rectangle())
                                .onTapGesture { review = ViewerTarget(items: request.items, start: i) }
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .frame(height: 64)
                .padding(.top, 14)
            }

            Text((request.note.map { $0 + "\n" } ?? "") + (items.isEmpty ? "这次不删了。"
                : "删掉的先进「照片」App 的「最近删除」，30 天内能恢复。点“删除”后系统还会再问你一次。"))
                .font(.footnote).multilineTextAlignment(.center)
                .padding(.horizontal, 24).padding(.top, 14)

            Spacer(minLength: 16)

            if items.isEmpty {
                Button("好") { dismiss() }
                    .font(.body.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
            } else {
                Divider()
                Button { review = ViewerTarget(items: request.items, start: 0) } label: {
                    Label("一张张看大图", systemImage: "plus.magnifyingglass").frame(maxWidth: .infinity, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .foregroundStyle(Color.accentText)
                Divider()
                HStack(spacing: 0) {
                    Button { dismiss() } label: {
                        Text("取消").frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                    }
                    Divider().frame(height: 48)
                    Button {
                        dismiss()
                        // 等确认框收起来再删：系统的“允许删除”弹窗紧接着出来
                        let run = request.run
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { run(items) }
                    } label: {
                        Text("删除").fontWeight(.semibold).foregroundStyle(Color.danger)
                            .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                    }
                }
            }
        }
        .padding(.bottom, 8)
        .presentationDetents([.height(request.items.isEmpty ? 220 : 330)])
        .presentationDragIndicator(.visible)
        // 过一遍的时候列表不跟着变：改成保留的那张还在，可以再改回来
        .fullScreenCover(item: $review) { v in
            Viewer(items: v.items, index: v.start, isMarked: isMarked, onToggle: onToggle)
        }
    }
}

extension View {
    /// 挂一个删除确认框：[request] 不为空就弹出来
    func deleteConfirm(_ request: Binding<DeleteRequest?>, isMarked: @escaping (String) -> Bool, onToggle: @escaping (String) -> Void) -> some View {
        sheet(item: request) { r in DeleteConfirm(request: r, isMarked: isMarked, onToggle: onToggle) }
    }
}
