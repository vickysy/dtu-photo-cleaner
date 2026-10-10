import Foundation
import Photos
import SwiftUI
import UIKit

/// 手机存储：总容量和可用空间
struct StorageInfo {
    let total: Int64
    let free: Int64
}

/// 整个 App 的状态：相册、分析进度、删除
@MainActor
final class AppModel: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published var auth: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published var library: Library?
    @Published var loading = false
    @Published var progress = Progress()
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
        Task { @MainActor in
            if Date() < self.ignoreChangesUntil { return }
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
        let lib = await Task.detached(priority: .userInitiated) { () -> Library in
            let (images, videos) = PhotoSource.load()
            return Library.build(images: images, videos: videos, settings: settings, taste: .normal)
        }.value
        library = lib
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

    func startAnalysis() {
        guard let lib = library, analysisTask == nil else { return }
        let stored = Store.shared.loadFeatures()
        let vstored = Store.shared.loadVideos()
        // 新的先分析：刚拍的照片马上就能参与建议；照片分析完再看视频
        let todo = lib.images.filter { Analyzer.eligible($0) && Analyzer.needsWork($0, stored) }
        let vtodo = lib.videos.filter { Analyzer.videoEligible($0) && Analyzer.videoNeedsWork($0, vstored) }
        let texts = Store.shared.loadTexts()
        // 识字放最后：要先知道哪些相机照片是“实用类”（拍的文件、收据），截图先识
        let images = lib.images
        let ttodoNow = images.filter { Analyzer.textEligible($0, utility: stored[$0.id]?.feat.utility) && Analyzer.textNeedsWork($0, texts) }
        if todo.isEmpty && vtodo.isEmpty && ttodoNow.isEmpty { return }
        progress = Progress(running: true, label: !todo.isEmpty ? "正在分析照片" : !vtodo.isEmpty ? "正在看视频" : "正在认图里的字",
                            done: 0, total: !todo.isEmpty ? todo.count : !vtodo.isEmpty ? vtodo.count : ttodoNow.count)
        UIApplication.shared.isIdleTimerDisabled = true
        let bg = UIApplication.shared.beginBackgroundTask(withName: "analysis")
        lastMidRefresh = Date()
        analysisTask = Task.detached(priority: .utility) {
            func report(_ label: String) -> (Int, Int) -> Void {
                { done, total in
                    Task { @MainActor in
                        self.progress = Progress(running: true, label: label, done: done, total: total)
                        // 分析很久时，中途也刷新一下，先出来的建议先给用户看
                        if Date().timeIntervalSince(self.lastMidRefresh) > 60 && done < total { self.lastMidRefresh = Date(); self.refresh() }
                    }
                }
            }
            if !todo.isEmpty { await Analyzer.run(todo, onProgress: report("正在分析照片")) }
            if !vtodo.isEmpty && !Task.isCancelled {
                await MainActor.run { self.refresh() }
                await Analyzer.runVideos(vtodo, onProgress: report("正在看视频"))
            }
            if !Task.isCancelled {
                // 照片分析完才知道哪些相机照片是“实用类”，重新算一遍要识字的
                let fresh = Store.shared.loadFeatures()
                let freshTexts = Store.shared.loadTexts()
                let ttodo = images.filter { Analyzer.textEligible($0, utility: fresh[$0.id]?.feat.utility) && Analyzer.textNeedsWork($0, freshTexts) }
                    .sorted { ($0.isScreenshot ? 0 : 1, -$0.timeMs) < ($1.isScreenshot ? 0 : 1, -$1.timeMs) }
                if !ttodo.isEmpty {
                    await MainActor.run { self.refresh() }
                    await Analyzer.runTexts(ttodo, onProgress: report("正在认图里的字"))
                }
            }
            let cancelled = Task.isCancelled
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
            var lastError: String?
            for (i, m) in items.enumerated() {
                if Task.isCancelled { break }
                compressing = CompressState(index: i, total: items.count, fraction: 0)
                do {
                    _ = try await Compressor.compress(m) { f in
                        Task { @MainActor in self.compressing?.fraction = f }
                    }
                    ok += 1
                } catch {
                    lastError = error.localizedDescription
                }
            }
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
            Store.shared.removeCompressed(src: pair.source.id)
            await reload()
        }
    }

    /// 压缩版留下，原视频进“最近删除”
    func keepCompressed(_ pairs: [CompressedPair]) async {
        if await delete(pairs.map { $0.source }) {
            pairs.forEach { Store.shared.removeCompressed(src: $0.source.id) }
            await reload()
        }
    }

    func pauseAnalysis() {
        analysisTask?.cancel()
    }

    /// 马上分析这几张（“刚拍的”用）：全相册分析正在跑就先停下，分析完再接着跑
    func analyzeNow(_ items: [MediaItem]) async {
        let stored = Store.shared.loadFeatures()
        let todo = items.filter { Analyzer.eligible($0) && Analyzer.needsWork($0, stored) }
        if !todo.isEmpty {
            let wasRunning = analysisTask != nil
            if let t = analysisTask { t.cancel(); await t.value }
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
        ignoreChangesUntil = Date().addingTimeInterval(5)
        let ok: Bool = await withCheckedContinuation { c in
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            }) { success, _ in c.resume(returning: success) }
        }
        guard ok else { return false }
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
        stitching = true
        defer { stitching = false }
        var images: [UIImage] = []
        for m in pics { if let img = await NoteMaker.loadFull(m.asset) { images.append(img) } }
        let pages = NoteMaker.stitch(images)
        do {
            try await NoteMaker.save(pages)
            show("已拼成 \(pages.count) 张长图，存在相册“\(NoteMaker.albumName)”里")
        } catch {
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
        return urls
    }

    func markReviewed(_ ids: [String]) {
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
        pauseAnalysis()
        Store.shared.clearFeatures()
        refresh()
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.startAnalysis()
        }
    }
}
