import Foundation
import Photos
import SwiftUI
import UIKit

/// 手机存储：总容量和可用空间
struct StorageInfo {
    let total: Int64
    let free: Int64
}

/// 分析进度单独放一个对象：进度一秒要变好几次，只让显示进度的那几处跟着刷新。
/// 放在 AppModel 里的话，每变一次所有页面（相册格子、看大图）都要重算一遍，滑起来就卡
@MainActor
final class LiveProgress: ObservableObject {
    @Published var progress = Progress()
}

/// 整个 App 的状态：相册、分析进度、删除
@MainActor
final class AppModel: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published var auth: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published var library: Library?
    @Published var loading = false
    let live = LiveProgress()
    /// 只有开始、结束、换步骤时才通知所有页面；数字涨只通知 [live]
    var progress: Progress {
        get { live.progress }
        set {
            let old = live.progress
            if newValue.running != old.running || newValue.label != old.label || newValue.message != old.message { objectWillChange.send() }
            live.progress = newValue
        }
    }
    @Published var storage: StorageInfo?
    @Published var toast: String?
    @Published var cleaned: (count: Int, bytes: Int64) = (UserDefaults.standard.integer(forKey: "cleanedCount"), Int64(UserDefaults.standard.integer(forKey: "cleanedBytes")))

    var settings = Settings()
    private var analysisTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var observing = false
    private var lastMidRefresh = Date()
    /// 自己删完会刷新一次，系统的“相册变了”通知就不用再刷
    private var ignoreChangesUntil = Date.distantPast

    var authorized: Bool { auth == .authorized || auth == .limited }

    func start() {
        auth = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        #if DEBUG
        // 模拟器测试用：带 -autoGrant 启动时直接要权限（权限已用 simctl 预先给好，不会弹窗）
        if auth == .notDetermined && CommandLine.arguments.contains("-autoGrant") { requestAccess(); return }
        #endif
        refreshStorage()
        if authorized {
            if !observing { PHPhotoLibrary.shared().register(self); observing = true }
            refresh()
        }
    }

    func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { s in
            Task { @MainActor in
                self.auth = s
                self.start()
            }
        }
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        // 系统自己整理相册（升级系统后会持续好几天）只改修改时间，不用重读；有新照片、删了、改了收藏、编辑了才重读
        guard PhotoSource.needsReload(changeInstance) else {
            Usage.add("相册变化.忽略")
            Diag.log("相册变化通知：只是系统改了修改时间，忽略")
            return
        }
        Usage.add("相册变化.重读")
        Diag.log("相册变化通知：要重读")
        Task { @MainActor in
            if Date() < self.ignoreChangesUntil { return }
            // 正在分析时不用急着重读：分析中途每分钟会刷新一次，分析完也会刷新
            if self.progress.running { return }
            self.refreshTask?.cancel()
            self.refreshTask = Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if !Task.isCancelled { self.refresh() }
            }
        }
    }

    func refreshStorage() {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        if let v = try? url.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey]),
           let total = v.volumeTotalCapacity, let free = v.volumeAvailableCapacityForImportantUsage {
            storage = StorageInfo(total: Int64(total), free: free)
        }
    }

    /// 重新读相册、重新整理；分析过一次以后，有新照片就自动接着分析
    func refresh() {
        Task { await reload() }
    }

    func reload() async {
        guard authorized else { return }
        loading = true
        let settings = self.settings
        let taste = TasteStore.taste
        let t0 = Date()
        let lib = await Task.detached(priority: .userInitiated) { () -> Library in
            let (images, videos) = PhotoSource.load()
            return Library.build(images: images, videos: videos, settings: settings, taste: taste)
        }.value
        Usage.add("读相册.次数")
        Usage.add("读相册.毫秒", Int(Date().timeIntervalSince(t0) * 1000))
        Diag.log("读完相册：照片 \(lib.images.count)，视频 \(lib.videos.count)，用了 \(Int(Date().timeIntervalSince(t0) * 1000)) 毫秒；未分析 照片\(lib.unanalyzed) 视频\(lib.videosUnanalyzed) 认字\(lib.textsPending)")
        let mark = Diag.begin("发布新相册")
        library = lib
        Diag.end("发布新相册", mark)
        loading = false
        refreshStorage()
        if lib.analyzed && (lib.unanalyzed > 0 || lib.videosUnanalyzed > 0 || lib.textsPending > 0) && !progress.running && compressing == nil { startAnalysis() }
        #if DEBUG
        if !lib.analyzed && !progress.running && CommandLine.arguments.contains("-autoAnalyze") { startAnalysis() }
        if CommandLine.arguments.contains("-autoCompress") && compressing == nil && lib.compressedPending.isEmpty, let c = lib.compressible.first {
            compress([c.item])
        }
        #endif
    }

    // ---------- 分析 ----------

    /// 这次要分析哪些（照片多的时候算一次要一两秒，在后台算）
    private struct AnalysisPlan {
        let images: [MediaItem]
        let todo: [MediaItem]
        let vtodo: [MediaItem]
        let ttodo: [MediaItem]
        /// 最近 7 天还没认字的截图：照片、视频还要分析很久时先认这几张，刚截的好评马上能进“聊天记录”
        let freshShots: [MediaItem]
        let photoTotal: Int
        let videoTotal: Int
        let textTotal: Int
    }

    private var preparingAnalysis = false

    func startAnalysis() {
        guard let lib = library, analysisTask == nil, !preparingAnalysis else { return }
        preparingAnalysis = true
        Task {
            // 读分析记录、挑出要做的放到后台：以前在主线程上读，每次重读相册界面都要卡一下、点不动
            let plan = await Task.detached(priority: .userInitiated) { AppModel.plan(lib) }.value
            Diag.log(plan.map { "要分析：照片 \($0.todo.count)，视频 \($0.vtodo.count)，认字 \($0.ttodo.count)" } ?? "没有要分析的")
            preparingAnalysis = false
            if let plan, analysisTask == nil { launchAnalysis(plan) }
        }
    }

    /// 没有要做的返回 nil
    private nonisolated static func plan(_ lib: Library) -> AnalysisPlan? {
        let stored = Store.shared.loadFeatures()
        let vstored = Store.shared.loadVideos()
        // 新的先分析：刚拍的照片马上就能参与建议；照片分析完再看视频
        let todo = lib.images.filter { Analyzer.eligible($0) && Analyzer.needsWork($0, stored) }
        let vtodo = lib.videos.filter { Analyzer.videoEligible($0) && Analyzer.videoNeedsWork($0, vstored) }
        let texts = Store.shared.loadTexts()
        // 识字放最后：要先知道哪些相机照片是“实用类”（拍的文件、收据），截图先识
        let images = lib.images
        let textAll = images.filter { Analyzer.textEligible($0, utility: stored[$0.id]?.feat.utility) }
        let ttodo = textAll.filter { Analyzer.textNeedsWork($0, texts) }
        if todo.isEmpty && vtodo.isEmpty && ttodo.isEmpty { return nil }
        // 进度按“这一步一共多少、已经做完多少”显示（包括以前做完的），切走再回来不会像是从 0 重新开始
        let weekAgo = Int64((Date().timeIntervalSince1970 - 7 * 86_400) * 1000)
        let freshShots = todo.isEmpty && vtodo.isEmpty ? [] :
            Array(ttodo.filter { $0.isScreenshot && $0.timeMs >= weekAgo }.sorted { $0.timeMs > $1.timeMs }.prefix(40))
        return AnalysisPlan(images: images, todo: todo, vtodo: vtodo, ttodo: ttodo, freshShots: freshShots,
                            photoTotal: images.filter { Analyzer.eligible($0) }.count,
                            videoTotal: lib.videos.filter { Analyzer.videoEligible($0) }.count,
                            textTotal: textAll.count)
    }

    private func launchAnalysis(_ plan: AnalysisPlan) {
        let images = plan.images, todo = plan.todo, vtodo = plan.vtodo
        let photoTotal = plan.photoTotal, videoTotal = plan.videoTotal
        let photoLabel = "第 1 步，共 3 步：分析照片", videoLabel = "第 2 步，共 3 步：看视频", textLabel = "第 3 步，共 3 步：认图里的字"
        if !todo.isEmpty {
            progress = Progress(running: true, label: photoLabel, done: photoTotal - todo.count, total: photoTotal)
        } else if !vtodo.isEmpty {
            progress = Progress(running: true, label: videoLabel, done: videoTotal - vtodo.count, total: videoTotal)
        } else {
            progress = Progress(running: true, label: textLabel, done: plan.textTotal - plan.ttodo.count, total: plan.textTotal)
        }
        UIApplication.shared.isIdleTimerDisabled = true
        let bg = UIApplication.shared.beginBackgroundTask(withName: "analysis")
        lastMidRefresh = Date()
        analysisTask = Task.detached(priority: .utility) {
            /// [before] 是这一步以前就做完的，[all] 是这一步一共多少
            func report(_ label: String, before: Int, all: Int) -> (Int, Int) -> Void {
                { done, total in
                    Task { @MainActor in
                        self.progress = Progress(running: true, label: label, done: before + done, total: all)
                        // 分析很久时，中途也刷新一下，先出来的建议先给用户看
                        if Date().timeIntervalSince(self.lastMidRefresh) > 60 && done < total { self.lastMidRefresh = Date(); self.refresh() }
                    }
                }
            }
            /// 这一步做完（没被打断）才记用时，算出来的“平均每张几秒”才准
            func timed(_ name: String, _ n: Int, _ work: () async -> Void) async {
                let t0 = Date()
                await work()
                if !Task.isCancelled {
                    Usage.add("分析用时.\(name)", Int(Date().timeIntervalSince(t0) * 1000))
                    Usage.add("分析张数.\(name)", n)
                }
            }
            if !plan.freshShots.isEmpty {
                await Analyzer.runTexts(plan.freshShots, onProgress: report("先认刚截的图", before: 0, all: plan.freshShots.count))
                if !Task.isCancelled { await MainActor.run { self.refresh() } }
            }
            if !todo.isEmpty && !Task.isCancelled {
                await timed("照片", todo.count) { await Analyzer.run(todo, onProgress: report(photoLabel, before: photoTotal - todo.count, all: photoTotal)) }
            }
            if !vtodo.isEmpty && !Task.isCancelled {
                await MainActor.run { self.refresh() }
                await timed("视频", vtodo.count) { await Analyzer.runVideos(vtodo, onProgress: report(videoLabel, before: videoTotal - vtodo.count, all: videoTotal)) }
            }
            if !Task.isCancelled {
                // 照片分析完才知道哪些相机照片是“实用类”，重新算一遍要识字的
                let fresh = Store.shared.loadFeatures()
                let freshTexts = Store.shared.loadTexts()
                let textAll = images.filter { Analyzer.textEligible($0, utility: fresh[$0.id]?.feat.utility) }
                let ttodo = textAll.filter { Analyzer.textNeedsWork($0, freshTexts) }
                    .sorted { ($0.isScreenshot ? 0 : 1, -$0.timeMs) < ($1.isScreenshot ? 0 : 1, -$1.timeMs) }
                if !ttodo.isEmpty {
                    await MainActor.run { self.refresh() }
                    await timed("认字", ttodo.count) { await Analyzer.runTexts(ttodo, onProgress: report(textLabel, before: textAll.count - ttodo.count, all: textAll.count)) }
                }
            }
            let cancelled = Task.isCancelled
            if cancelled { Usage.add("分析中途暂停") }
            await MainActor.run {
                self.progress = Progress(running: false, done: 0, total: 0, message: cancelled ? "已暂停，下次打开会接着分析" : nil)
                self.analysisTask = nil
                UIApplication.shared.isIdleTimerDisabled = false
                UIApplication.shared.endBackgroundTask(bg)
                self.refresh()
            }
        }
    }

    // ---------- 压缩 ----------

    /// 正在压缩：第几个 / 一共几个、当前这个的进度
    struct CompressState: Equatable {
        var index: Int
        var total: Int
        var fraction: Double
    }

    @Published var compressing: CompressState?
    private var compressTask: Task<Void, Never>?

    /// 一个一个压；压的时候别锁屏（iOS 不让 App 在后台长时间转码）
    func compress(_ items: [MediaItem]) {
        guard compressTask == nil, !items.isEmpty else { return }
        if let t = analysisTask { t.cancel() }
        compressing = CompressState(index: 0, total: items.count, fraction: 0)
        UIApplication.shared.isIdleTimerDisabled = true
        compressTask = Task {
            var ok = 0
            var failed = 0
            var lastError: String?
            for (i, m) in items.enumerated() {
                if Task.isCancelled { break }
                compressing = CompressState(index: i, total: items.count, fraction: 0)
                do {
                    _ = try await Compressor.compress(m) { f in
                        Task { @MainActor in
                            if let c = self.compressing, f - c.fraction >= 0.01 || f >= 1 { self.compressing?.fraction = f }
                        }
                    }
                    ok += 1
                } catch {
                    if Task.isCancelled { break }
                    failed += 1
                    lastError = error.localizedDescription
                    if let f = error as? Compressor.Failure, f.permanent { Compressor.markFailed(m.id) }
                    Usage.event("视频没压成：\(error.localizedDescription)")
                }
            }
            Usage.add("压缩.成功", ok)
            Usage.add("压缩.失败", failed)
            compressing = nil
            compressTask = nil
            UIApplication.shared.isIdleTimerDisabled = false
            show(ok > 0 ? "压好了 \(ok) 个，对比看过再删原视频" + (lastError.map { "（有 \(items.count - ok) 个没压成：\($0)）" } ?? "") : (lastError ?? "没压成"))
            await reload()
        }
    }

    func cancelCompress() { compressTask?.cancel() }

    /// 不要压缩版：删掉压出来的那个（系统会问），原视频留着
    func discardCompressed(_ pair: CompressedPair) async {
        if await delete([pair.output]) {
            Usage.add("压缩.不满意删压缩版")
            Store.shared.removeCompressed(src: pair.source.id)
            await reload()
        }
    }

    /// 压缩版留下，原视频进“最近删除”
    func keepCompressed(_ pairs: [CompressedPair]) async {
        if await delete(pairs.map { $0.source }) {
            Usage.add("压缩.满意删原视频", pairs.count)
            pairs.forEach { Store.shared.removeCompressed(src: $0.source.id) }
            await reload()
        }
    }

    func pauseAnalysis() {
        analysisTask?.cancel()
    }

    /// 马上分析这几张（“刚拍的”用）：全相册分析正在跑就先停下，分析完再接着跑
    func analyzeNow(_ items: [MediaItem]) async {
        Diag.log("刚拍的：开始，\(items.count) 张")
        defer { Diag.log("刚拍的：结束") }
        let todo = await Task.detached(priority: .userInitiated) { () -> [MediaItem] in
            let stored = Store.shared.loadFeatures()
            return items.filter { Analyzer.eligible($0) && Analyzer.needsWork($0, stored) }
        }.value
        if !todo.isEmpty {
            let wasRunning = analysisTask != nil
            if let t = analysisTask {
                t.cancel()
                // 等正在做的那一批收尾，最多等 3 秒：看视频的一批可能要一两分钟，不能让“刚拍的”一直转圈、像点不进去
                for _ in 0..<30 where analysisTask != nil { try? await Task.sleep(nanoseconds: 100_000_000) }
            }
            progress = Progress(running: true, done: 0, total: todo.count)
            await Analyzer.run(todo) { done, total in
                Task { @MainActor in self.progress = Progress(running: true, done: done, total: total) }
            }
            progress = Progress()
            await reload()
            if wasRunning { startAnalysis() }
        } else if library == nil {
            await reload()
        }
    }

    // ---------- 删除 ----------

    /**
     删除：系统会弹窗确认，删掉的进“最近删除”，30 天内能恢复。
     [kept] 是用户看过、决定留下的（以后不再建议）。返回是否删成功
     */
    @discardableResult
    func delete(_ items: [MediaItem], kept: [String] = []) async -> Bool {
        if items.isEmpty {
            if !kept.isEmpty { markReviewed(kept) }
            return true
        }
        let assets = items.map { $0.asset }
        let bytes = items.reduce(Int64(0)) { $0 + $1.size }
        let suggested = library?.suggestedIds() ?? []
        ignoreChangesUntil = Date().addingTimeInterval(5)
        let ok: Bool = await withCheckedContinuation { c in
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            }) { success, _ in c.resume(returning: success) }
        }
        guard ok else {
            Usage.add("删除没成功")
            return false
        }
        // 使用报告：在哪一页删的、删了多少；删掉的里面有多少是 App 推荐的，推荐删的又留下了多少
        let page = Usage.page
        Usage.add("删除次数.\(page)")
        Usage.add("删除项数.\(page)", items.count)
        Usage.add("删除字节.\(page)", Int(bytes))
        let fromSuggestion = items.filter { suggested.contains($0.id) }.count
        Usage.add("删掉的.推荐的", fromSuggestion)
        Usage.add("删掉的.自己加的", items.count - fromSuggestion)
        Usage.add("推荐删但留下", kept.filter { suggested.contains($0) }.count)
        if !kept.isEmpty { Store.shared.markReviewed(kept) }
        cleaned = (cleaned.count + items.count, cleaned.bytes + bytes)
        UserDefaults.standard.set(cleaned.count, forKey: "cleanedCount")
        UserDefaults.standard.set(Int(cleaned.bytes), forKey: "cleanedBytes")
        show("相册轻了 \(Fmt.bytes(bytes))（\(items.count) 项）。去「照片」App 的「最近删除」清空后，手机才真正腾出空间")
        refresh()
        return true
    }

    // ---------- 好评 ----------

    /// 设为好评的聊天截图（存在本机）
    @Published var praise: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "praise") ?? [])

    /// 设为好评 / 取消。设为好评的同时记成“保留”，以后不会被建议删除
    func setPraise(_ ids: [String], on: Bool) {
        if ids.isEmpty { return }
        if on { praise.formUnion(ids) } else { praise.subtract(ids) }
        Usage.add(on ? "设为好评" : "取消好评", ids.count)
        UserDefaults.standard.set(Array(praise), forKey: "praise")
        if on { markReviewed(ids) }
        show(on ? "已设为好评 \(ids.count) 张" : "已取消好评 \(ids.count) 张")
    }

    // ---------- 拼长图、分享 ----------

    @Published var stitching = false

    /// 按拍摄时间从早到晚拼成长图，存进相册“轻了笔记”，返回拼好的图（拿去分享：微信、得到、备忘录都能收）
    func stitch(_ items: [MediaItem]) async -> [UIImage] {
        let pics = items.filter { !$0.isVideo }.sorted { $0.timeMs < $1.timeMs }
        if pics.isEmpty || stitching { return [] }
        Usage.add("拼长图")
        Usage.add("拼长图.张数", pics.count)
        stitching = true
        defer { stitching = false }
        var images: [UIImage] = []
        for m in pics { if let img = await NoteMaker.loadFull(m.asset) { images.append(img) } }
        let pages = NoteMaker.stitch(images)
        do {
            try await NoteMaker.save(pages)
            show("已拼成 \(pages.count) 张长图，存在相册“\(NoteMaker.albumName)”里")
        } catch {
            Usage.event("长图没存进相册：\(error.localizedDescription)")
            show("长图没存进相册：\(error.localizedDescription)")
        }
        return pages
    }

    /// 拼长图并准备分享：长图写成 JPEG 文件；有整理好的文字就放进剪贴板（图和字混在一起分享，得到、微信常常收不到）
    func stitchForSharing(_ items: [MediaItem], title: String, note: String?) async -> [URL] {
        let pages = await stitch(items)
        if pages.isEmpty { return [] }
        let urls = NoteMaker.jpegFiles(pages, name: title.isEmpty ? "轻了笔记" : title)
        if let note, !note.isEmpty {
            UIPasteboard.general.string = note
            show("长图存进了相册“\(NoteMaker.albumName)”；整理好的文字已复制，发过去后可以长按粘贴")
        }
        return urls
    }

    /// 分享原图：导出成文件（保留原图画质）
    func originals(_ items: [MediaItem]) async -> [URL] {
        var urls: [URL] = []
        for m in items { if let u = await NoteMaker.exportOriginal(m.asset) { urls.append(u) } }
        Usage.add("分享原图", urls.count)
        if urls.count < items.count { Usage.event("分享原图：\(items.count - urls.count) 张没导出来") }
        return urls
    }

    func markReviewed(_ ids: [String]) {
        let suggested = library?.suggestedIds() ?? []
        Usage.add("推荐删但留下", ids.filter { suggested.contains($0) }.count)
        Store.shared.markReviewed(ids)
        refresh()
    }

    func show(_ text: String) {
        toast = text
        let t = text
        Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if self.toast == t { self.toast = nil }
        }
    }

    func reanalyzeAll() {
        Usage.add("重新分析全部")
        pauseAnalysis()
        Store.shared.clearFeatures()
        refresh()
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.startAnalysis()
        }
    }
}
