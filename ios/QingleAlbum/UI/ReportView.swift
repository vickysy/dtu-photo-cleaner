import SwiftUI
import UIKit

/**
 使用报告：给参与测试的朋友用。App 只在手机里记次数和数字（见 Usage），这一页把它们整理成一份报告，
 全文摆在这里给测试者看；点“发送报告”由测试者自己选发给谁（微信、QQ、邮件），不点就不会离开手机。
 */
struct ReportView: View {
    @EnvironmentObject var model: AppModel
    @State private var comment = ""
    @State private var share: ShareItems?

    var body: some View {
        let text = Report.build(model, comment: comment)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Footnote(text: "谢谢你帮忙测试！App 只在手机里记了一些使用次数，下面就是要发出去的全部内容：没有照片、文件名、图里的字、拍摄时间和地点。点「发送报告」后由你自己选发给谁（微信、QQ、邮件都行），不点就不会离开手机。")
                    .padding(.top, 8)
                VStack(alignment: .leading, spacing: 8) {
                    Text("想说的话（可以不写）").font(.subheadline.weight(.semibold))
                    TextField("哪里好用、哪里不好用、哪张分错了……", text: $comment, axis: .vertical)
                        .lineLimit(3...8)
                }
                .padding(14).card(14).padding(.horizontal, 16)
                Text(text).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14).card(14).padding(.horizontal, 16)
            }
            .padding(.bottom, 20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.page)
        .navigationTitle("使用报告")
        .navigationBarTitleDisplayMode(.inline)
        .usagePage("使用报告")
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 12) {
                Spacer()
                CapsuleButton(title: "复制", filled: false) {
                    UIPasteboard.general.string = text
                    model.show("已复制，去微信里粘贴发给作者就行")
                }
                CapsuleButton(title: "发送报告") { share = ShareItems(items: [Report.file(text) ?? text]) }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(.bar)
            .overlay(alignment: .top) { Divider() }
        }
        .sheet(item: $share) { s in ShareSheet(items: s.items) }
    }
}

/// 把使用记录整理成一份能读的报告（只有次数和数字）
@MainActor
enum Report {
    static func build(_ model: AppModel, comment: String) -> String {
        let c = Usage.all()
        func n(_ k: String) -> Int { c[k] ?? 0 }
        /// “前缀.页面名” → [(页面名, 次数)]，多的在前
        func byPage(_ prefix: String) -> [(String, Int)] {
            c.filter { $0.key.hasPrefix(prefix + ".") && $0.value > 0 }
                .map { (String($0.key.dropFirst(prefix.count + 1)), $0.value) }
                .sorted { $0.1 > $1.1 }
        }
        let lib = model.library
        var out: [String] = []

        out.append("轻了相册 使用报告")
        out.append("生成时间：\(Fmt.stamp(Date()))")
        let info = Bundle.main.infoDictionary
        out.append("版本：\(info?["CFBundleShortVersionString"] as? String ?? "?")（\(info?["CFBundleVersion"] as? String ?? "?")）")
        out.append("手机：\(deviceName) · iOS \(UIDevice.current.systemVersion) · 内存 \(ProcessInfo.processInfo.physicalMemory / 1_000_000_000) GB")
        out.append("相册权限：" + (model.auth == .limited ? "只允许了部分照片" : model.auth == .authorized ? "全部照片" : "没有权限"))
        if let first = Usage.firstOpen { out.append("第一次打开：\(Fmt.stamp(first))，一共打开 \(n("打开 App")) 次") }
        if n("意外退出") > 0 { out.append("意外退出：\(n("意外退出")) 次（闪退，或内存不够被系统关掉）") }
        let stuck = n("界面卡住")
        out.append(stuck == 0 ? "界面卡住：没有" : "界面卡住（超过 0.4 秒点不动）：\(stuck) 次，最长 \(String(format: "%.1f", Double(n("界面最长卡住毫秒")) / 1000)) 秒")
        if n("读相册.次数") > 0 {
            out.append("重新读相册 \(n("读相册.次数")) 次，平均每次 \(String(format: "%.1f", Double(n("读相册.毫秒")) / 1000 / Double(n("读相册.次数")))) 秒；相册变化通知：重读 \(n("相册变化.重读")) 次，忽略 \(n("相册变化.忽略")) 次")
        }
        let words = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        if !words.isEmpty {
            out.append("")
            out.append("【测试者想说的话】")
            out.append(words)
        }

        out.append("")
        out.append("【相册】")
        if let lib {
            let shots = lib.images.filter { $0.isScreenshot }.count
            let camera = lib.images.filter { $0.isCameraShot }.count
            let favorite = (lib.images + lib.videos).filter { $0.favorite }.count
            out.append("照片 \(Fmt.count(lib.images.count)) 张（\(Fmt.bytes(lib.imageBytes))），视频 \(Fmt.count(lib.videos.count)) 个（\(Fmt.bytes(lib.videoBytes))）")
            out.append("其中截图 \(Fmt.count(shots)) 张，相机拍的 \(Fmt.count(camera)) 张，收藏 \(Fmt.count(favorite)) 个")
        } else {
            out.append("相册还没读出来")
        }
        if let s = model.storage { out.append("手机存储：共 \(Fmt.bytes(s.total))，还剩 \(Fmt.bytes(s.free))") }

        out.append("")
        out.append("【分析】")
        if let lib {
            out.append("照片已分析 \(Fmt.count(lib.features.count)) 张，还没分析 \(Fmt.count(lib.unanalyzed)) 张；视频还没看 \(lib.videosUnanalyzed) 个；截图还没认字 \(lib.textsPending) 张")
        }
        if model.progress.running { out.append("正在分析：\(model.progress.label) \(model.progress.done) / \(model.progress.total)") }
        for step in ["照片", "视频", "认字"] {
            let ms = n("分析用时.\(step)"), count = n("分析张数.\(step)")
            if count > 0 {
                out.append("\(step)：一口气做完的 \(Fmt.count(count)) 张，用时 \(minutes(ms))，平均每张 \(String(format: "%.2f", Double(ms) / 1000 / Double(count))) 秒")
            }
            if n("卡住跳过.\(step)") > 0 { out.append("\(step)：卡住跳过 \(n("卡住跳过.\(step)")) 张") }
        }
        if n("分析中途暂停") > 0 { out.append("中途暂停 \(n("分析中途暂停")) 次（切到别的 App 或锁屏）") }
        if n("重新分析全部") > 0 { out.append("点过“重新分析全部照片” \(n("重新分析全部")) 次") }

        if let lib {
            out.append("")
            out.append("【App 现在的建议】")
            let near = lib.groups.filter { $0.nearIdentical }.count
            out.append("相似照片 \(lib.groups.count) 组（几乎一样 \(near)，角度不同 \(lib.groups.count - near)），推荐删 \(lib.groups.reduce(0) { $0 + $1.suggestedDelete.count }) 张")
            out.append("场景精选 \(lib.scenes.count) 个，推荐删 \(lib.scenes.reduce(0) { a, sc in let k = sc.keepIds; return a + sc.members.filter { !k.contains($0.id) }.count }) 张")
            out.append("废片 \(lib.bad.count) 张（有把握的 \(lib.bad.filter { $0.confident }.count) 张）")
            out.append("截图 \(lib.screenshots.count) 张，推荐删 \(lib.oldScreenshots().count) 张")
            out.append("可删的视频 \(lib.videoSuggest.count) 个，视频场景 \(lib.videoScenes.count) 个，大视频 \(lib.bigVideos.count) 个")
            out.append("可压缩的视频 \(lib.compressible.count) 个，能省 \(Fmt.bytes(lib.compressSaving))")
            let kinds = ShotKind.allCases.map { k in (k, lib.docKinds.values.filter { $0 == k }.count) }.filter { $0.1 > 0 }
            if !kinds.isEmpty { out.append("认出的图片：" + kinds.map { "\($0.0.rawValue) \($0.1)" }.joined(separator: "，")) }
            out.append("截图笔记 \(lib.sessions.count) 串，设为好评 \(model.praise.count) 张")
            // 美感分是 iOS 自带的评估换算成 1–10 的：看分布，才知道“4.3 分及以下”“5.5 分及以上”这两条线在 iPhone 上准不准
            let aes = lib.aesthetic.values.sorted()
            if aes.count >= 20 {
                func at(_ q: Double) -> String { String(format: "%.1f", aes[min(aes.count - 1, Int(Double(aes.count) * q))]) }
                let low = aes.filter { aesRounded($0) <= aesLow }.count, high = aes.filter { aesRounded($0) >= aesHigh }.count
                out.append("美感分分布（\(aes.count) 张相机照片）：最低 5% \(at(0.05))，四分之一 \(at(0.25))，中间 \(at(0.5))，四分之三 \(at(0.75))，最高 5% \(at(0.95))；4.3 分及以下 \(low) 张（\(low * 100 / aes.count)%），5.5 分及以上 \(high) 张（\(high * 100 / aes.count)%）")
            }
            let r = lib.reclaimable()
            out.append("按推荐全删，一共能腾出 \(Fmt.count(r.count)) 项 · \(Fmt.bytes(r.bytes))")
        }

        out.append("")
        out.append("【打开过的页面】（次数）")
        let opens = byPage("打开")
        out.append(opens.isEmpty ? "还没打开过" : opens.map { "\($0.0) \($0.1)" }.joined(separator: "，"))

        out.append("")
        out.append("【删除】")
        out.append("一共删了 \(Fmt.count(model.cleaned.count)) 项 · \(Fmt.bytes(model.cleaned.bytes))")
        for (page, times) in byPage("删除次数") {
            out.append("  \(page)：\(times) 次，\(n("删除项数.\(page)")) 项，\(Fmt.bytes(Int64(n("删除字节.\(page)"))))")
        }
        let fromApp = n("删掉的.推荐的"), own = n("删掉的.自己加的")
        if fromApp + own > 0 { out.append("删掉的里面：App 推荐的 \(fromApp) 项，自己另外勾的 \(own) 项") }
        if n("推荐删但留下") > 0 { out.append("App 推荐删、看过后留下的：\(n("推荐删但留下")) 项") }
        if n("删除没成功") > 0 { out.append("系统确认框里点了取消（或没删成）：\(n("删除没成功")) 次") }

        out.append("")
        out.append("【勾选】")
        let pages = Set(byPage("勾选").map { $0.0 } + byPage("取消勾选").map { $0.0 } + byPage("一划多选").map { $0.0 })
        if pages.isEmpty { out.append("还没改过勾选") }
        for page in pages.sorted() {
            out.append("  \(page)：取消勾 \(n("取消勾选.\(page)")) 次，加勾 \(n("勾选.\(page)")) 次，一划多选 \(n("一划多选.\(page)")) 次")
        }
        out.append("看大图 \(n("看大图")) 次，在大图里切换删/留 \(n("大图里切换删留")) 次")

        out.append("")
        out.append("【其他功能】")
        out.append("拼长图 \(n("拼长图")) 次（共 \(n("拼长图.张数")) 张），整理成文字 \(n("整理成文字")) 次，分享原图 \(n("分享原图")) 张")
        out.append("设为好评 \(n("设为好评")) 张，取消好评 \(n("取消好评")) 张")
        out.append("压缩视频：成功 \(n("压缩.成功")) 个，失败 \(n("压缩.失败")) 个；满意删原视频 \(n("压缩.满意删原视频")) 个，不满意删压缩版 \(n("压缩.不满意删压缩版")) 个")
        let monthly = UserDefaults.standard.object(forKey: MonthlyReminder.key) as? Bool ?? true
        out.append("每月提醒：\(monthly ? "开着" : "关了")")
        let tv = TasteStore.votes
        out.append("学到的口味：好看 \(tv.aes) 票，清楚 \(tv.tech) 票，美感占 \(Int(TasteStore.taste.aestheticWeight * 100))%")
        if n("让保留过的重新参与建议") > 0 { out.append("点过“让保留过的照片重新参与建议” \(n("让保留过的重新参与建议")) 次") }

        out.append("")
        out.append("【出错记录】")
        let events = Usage.events()
        out.append(contentsOf: events.isEmpty ? ["没有"] : events.suffix(20))

        out.append("")
        out.append("—— 这份报告只有次数和数字：没有照片、文件名、图里的字、拍摄时间和地点。")
        return out.joined(separator: "\n")
    }

    /// 写成 .txt 文件好发（微信收文件比收一大段字方便）
    static func file(_ text: String) -> URL? {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmm"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("轻了相册使用报告-\(f.string(from: Date())).txt")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private static func minutes(_ ms: Int) -> String {
        let s = ms / 1000
        return s >= 60 ? "\(s / 60) 分 \(s % 60) 秒" : "\(s) 秒"
    }

    /// 手机型号：常见的换成名字，其他的给型号代码
    private static var deviceName: String {
        var s = utsname()
        uname(&s)
        let code = withUnsafeBytes(of: &s.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let names = [
            "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max", "iPhone12,8": "iPhone SE 2",
            "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
            "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max", "iPhone14,6": "iPhone SE 3",
            "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
            "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
            "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,5": "iPhone 16e",
        ]
        return names[code].map { "\($0)（\(code)）" } ?? code
    }
}

extension View {
    /// 这一页叫什么：第一次打开记一次；回到这一页时，删除、勾选又记在它名下
    func usagePage(_ name: String) -> some View { modifier(UsagePage(name: name)) }
}

private struct UsagePage: ViewModifier {
    let name: String
    @State private var counted = false

    func body(content: Content) -> some View {
        content.onAppear {
            Diag.log("打开页面：\(name)")
            Usage.page = name
            if !counted {
                counted = true
                Usage.add("打开." + name)
            }
        }
    }
}
