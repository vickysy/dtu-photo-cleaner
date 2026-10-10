import Foundation

struct BadItem: Identifiable {
    let item: MediaItem
    let reasons: [BadReason]
    var id: String { item.id }
    /// 只有“可能模糊”这种拿不准的，不默认勾选
    var confident: Bool { reasons.contains { $0 != .maybeBlurry } }
}

/// 建议删的视频；keepId = 重复保存时留下的那一个
struct VideoSuggestion: Identifiable {
    let item: MediaItem
    let reasons: [VideoReason]
    var keepId: String?
    var id: String { item.id }
    var confident: Bool { reasons.contains { $0.confident } }
}

/// 值得压缩的视频：estimated 是压成 1080p 后大概多大
struct CompressCandidate: Identifiable {
    let item: MediaItem
    let estimated: Int64
    var id: String { item.id }
    var saving: Int64 { item.size - estimated }
}

/// 压好了、等用户确认删原视频的一对
struct CompressedPair: Identifiable {
    let source: MediaItem
    let output: MediaItem
    var id: String { source.id }
}

struct Settings {
    /// 相似照片：相邻两张间隔多少秒内才算一组
    var gapSec = 120
    var keepPerGroup = 1
    /// 模糊判定：0 宽松 / 1 标准 / 2 严格
    var blurSensitivity = 1
    /// 截图多少天前的预选
    var screenshotDays = 30
    /// 多大算大视频
    var bigVideoMB = 300
}

/// 某一时刻相册的整理结果，所有界面都从这里取数据（和安卓版 data/Library.kt 对应）
struct Library {
    let images: [MediaItem]
    let videos: [MediaItem]
    let byId: [String: MediaItem]
    let features: [String: ImageFeatures]
    /// 相机照片的美感分（1..10）
    let aesthetic: [String: Float]
    /// 相机照片的人脸
    let faces: [String: FaceInfo]
    let unanalyzed: Int
    let groups: [RankedGroup]
    let scenes: [PhotoScene]
    let bad: [BadItem]
    let screenshots: [MediaItem]
    let bigVideos: [MediaItem]
    /// 黑屏、误录、重复保存、不到 3 秒……
    let videoSuggest: [VideoSuggestion]
    /// 视频场景精选：同一个场景拍了好几段
    let videoScenes: [VideoScene]
    let compressible: [CompressCandidate]
    let compressedPending: [CompressedPair]
    let videosUnanalyzed: Int
    /// 识过字的图（截图、存下来的图、拍的文件）的分类：证件、聊天记录、文章、文件照片……
    let docKinds: [String: ShotKind]
    /// 截图里识别出的字（整理笔记用）
    let shotTexts: [String: String]
    /// 连续截的一串图（直播、网课），可以整理成笔记
    let sessions: [ShotSession]
    /// 还没识字的图
    let textsPending: Int
    let settings: Settings

    var imageBytes: Int64 { images.reduce(0) { $0 + $1.size } }
    var videoBytes: Int64 { videos.reduce(0) { $0 + $1.size } }
    var groupDeleteBytes: Int64 { groups.reduce(0) { a, g in a + g.suggestedDelete.reduce(0) { $0 + $1.photo.size } } }
    var sceneDeleteBytes: Int64 {
        scenes.reduce(0) { a, sc in let keep = sc.keepIds; return a + sc.members.filter { !keep.contains($0.id) }.reduce(0) { $0 + $1.size } }
    }
    var analyzed: Bool { !features.isEmpty }
    var videoSceneDeleteBytes: Int64 {
        videoScenes.reduce(0) { a, sc in let keep = sc.keepIds; return a + sc.members.filter { !keep.contains($0.id) }.reduce(0) { $0 + $1.size } }
    }
    var compressSaving: Int64 { compressible.reduce(0) { $0 + $1.saving } }

    func oldScreenshots(now: Date = Date()) -> [MediaItem] {
        screenshots.filter { screenshotSuggested($0, now: now) }
    }

    /// 截图要不要预先勾上：证件永远不勾；取件码、验证码、付款码、二维码这类用完就没用的，3 天前的就勾；其余按设置里的天数
    func screenshotSuggested(_ m: MediaItem, now: Date = Date()) -> Bool {
        let kind = docKinds[m.id]
        if kind?.important == true { return false }
        let age = now.timeIntervalSince1970 * 1000 - Double(m.timeMs)
        return age > Double(settings.screenshotDays) * 86_400_000 || (kind?.disposable == true && age > 3 * 86_400_000)
    }

    /// 所有建议删除的加起来（同一张只算一次）：张数和字节数。大视频不预选，不算
    func reclaimable() -> (count: Int, bytes: Int64) {
        var ids = Set<String>()
        groups.forEach { g in g.suggestedDelete.forEach { ids.insert($0.photo.id) } }
        scenes.forEach { sc in let keep = sc.keepIds; sc.members.forEach { if !keep.contains($0.id) { ids.insert($0.id) } } }
        bad.forEach { if $0.confident { ids.insert($0.item.id) } }
        oldScreenshots().forEach { ids.insert($0.id) }
        videoSuggest.forEach { if $0.confident { ids.insert($0.item.id) } }
        videoScenes.forEach { sc in let keep = sc.keepIds; sc.members.forEach { if !keep.contains($0.id) { ids.insert($0.id) } } }
        return (ids.count, ids.reduce(0) { $0 + (byId[$1]?.size ?? 0) })
    }

    static func build(images: [MediaItem], videos: [MediaItem], settings: Settings, taste: Taste) -> Library {
        let stored = Store.shared.loadFeatures()
        let reviewed = Store.shared.reviewedIds()
        var features: [String: ImageFeatures] = [:]
        var unanalyzed = 0
        var infos: [PhotoInfo] = []
        var cameraInfos: [PhotoInfo] = []
        for m in images where Analyzer.eligible(m) {
            guard let row = stored[m.id], row.mod == m.modified, row.ver == Analyzer.version else { unanalyzed += 1; continue }
            guard let f = row.feat.features else { continue }
            features[m.id] = f
            let info = PhotoInfo(id: m.id, timeMs: m.timeMs, width: m.width, height: m.height, size: m.size, favorite: m.favorite,
                                 features: f, faces: row.feat.faces, aesthetic: row.feat.aesthetic, lat: m.lat, lng: m.lng)
            if m.isCameraShot { cameraInfos.append(info) }
            if BadShotRules.excludedFromGrouping(f) { continue }
            infos.append(info)
        }

        var params = GroupParams()
        params.maxGapMs = Int64(settings.gapSec) * 1000
        params.keepCount = settings.keepPerGroup
        // 场景：只看相机拍的；大部分照片都标记过保留的场景不再出现
        let scenes = SceneDetector.segment(cameraInfos)
            .filter { ms in Double(ms.filter { reviewed.contains($0.id) }.count) < Double(ms.count) * 0.8 }
            .map { SceneDetector.build($0, taste: taste) }
            // 收藏的加精华已经是全部了：没有可删的，不用列出来
            .filter { $0.keepIds.count < $0.members.count }
            .sorted { $0.startTime > $1.startTime }
        let groups = Grouper.group(infos, params)
            .map { Ranker.rank($0, keepCount: settings.keepPerGroup, taste: taste) }
            .filter { g in g.members.contains { !reviewed.contains($0.photo.id) } && !g.suggestedDelete.isEmpty }
            .sorted { $0.startTime > $1.startTime }
        var proposed = Set<String>()
        groups.forEach { g in g.suggestedDelete.forEach { proposed.insert($0.photo.id) } }

        let bad: [BadItem] = images.compactMap { m in
            guard Analyzer.eligible(m), m.isCameraShot, !m.favorite, !proposed.contains(m.id), !reviewed.contains(m.id),
                  let f = features[m.id] else { return nil }
            let r = BadShotRules.reasons(f, settings.blurSensitivity)
            return r.isEmpty ? nil : BadItem(item: m, reasons: r)
        }
        let screenshots = images.filter { $0.isScreenshot && !reviewed.contains($0.id) && !$0.favorite }
        let bigBytes = Int64(settings.bigVideoMB) * 1_000_000
        let bigVideos = videos.filter { $0.size >= bigBytes && !reviewed.contains($0.id) }.sorted { $0.size > $1.size }

        // 视频：抽帧结果 → 黑屏、误录、重复保存、同场景多段；再加上不到 3 秒的
        let vstored = Store.shared.loadVideos()
        var videosUnanalyzed = 0
        var vinfos: [VideoInfo] = []
        for m in videos where Analyzer.videoEligible(m) {
            guard let r = vstored[m.id], r.mod == m.modified, r.ver == Analyzer.videoVersion else { videosUnanalyzed += 1; continue }
            vinfos.append(VideoInfo(id: m.id, timeMs: m.timeMs, durationMs: m.durationMs, size: m.size, width: m.width, height: m.height,
                                    camera: m.isCameraShot, favorite: m.favorite, f: r.feat))
        }
        let dups = VideoRules.duplicates(vinfos)
        let videoScenes = VideoScenes.segment(vinfos.filter { dups[$0.id] == nil })
            .filter { ms in Double(ms.filter { reviewed.contains($0.id) }.count) < Double(ms.count) * 0.8 }
            .map { VideoScenes.build($0) }
            .filter { $0.keepIds.count < $0.members.count }
            .sorted { $0.startTime > $1.startTime }
        let vById = Dictionary(uniqueKeysWithValues: vinfos.map { ($0.id, $0) })
        let videoSuggest: [VideoSuggestion] = videos.compactMap { m in
            guard !m.isScreenRecording, !m.favorite, !reviewed.contains(m.id) else { return nil }
            var r: [VideoReason] = []
            if dups[m.id] != nil { r.append(.duplicate) }
            if m.durationMs > 0 && m.durationMs < 3000 { r.append(.short) }
            if let v = vById[m.id] { VideoRules.reasons(v).forEach { if !r.contains($0) { r.append($0) } } }
            return r.isEmpty ? nil : VideoSuggestion(item: m, reasons: r, keepId: dups[m.id])
        }

        // 识字：分类、连续截图整理成笔记
        let texts = Store.shared.loadTexts()
        var docKinds: [String: ShotKind] = [:]
        var shotTexts: [String: String] = [:]
        var textsPending = 0
        for m in images where Analyzer.textEligible(m, utility: stored[m.id]?.feat.utility) {
            guard let t = texts[m.id], t.mod == m.modified, t.ver == Analyzer.textVersion else { textsPending += 1; continue }
            let paper = stored[m.id]?.feat.features.map { ScreenText.paperFraction($0.color) } ?? -1
            if let k = ScreenText.classify(t.row.text, codes: t.row.codes, layout: t.row.layout, camera: m.isCameraShot, paper: paper) {
                docKinds[m.id] = k
            }
            if m.isScreenshot { shotTexts[m.id] = t.row.text }
        }
        let sessions = ShotSessions.split(images.filter { $0.isScreenshot }.map { ($0.id, $0.timeMs, $0.width, $0.height) })

        // 压缩：相机拍的、码率高的视频；已经压过的、压出来的不再压；收藏的不动
        let compressedMap = Store.shared.loadCompressed()
        let outputs = Set(compressedMap.values)
        let videoById = Dictionary(uniqueKeysWithValues: videos.map { ($0.id, $0) })
        let compressedPending: [CompressedPair] = compressedMap.compactMap { src, out in
            guard let s = videoById[src], let o = videoById[out] else { return nil }
            return CompressedPair(source: s, output: o)
        }
        let compressible: [CompressCandidate] = videos.compactMap { m in
            guard m.isCameraShot, !m.favorite, m.durationMs > 0, compressedMap[m.id] == nil, !outputs.contains(m.id) else { return nil }
            let est = VideoRules.estimateSize(durationMs: m.durationMs, sourceBitrate: m.bitrate)
            return VideoRules.worthCompressing(size: m.size, estimated: est) ? CompressCandidate(item: m, estimated: est) : nil
        }.sorted { $0.saving > $1.saving }

        var byId: [String: MediaItem] = [:]
        images.forEach { byId[$0.id] = $0 }
        videos.forEach { byId[$0.id] = $0 }
        var aesthetic: [String: Float] = [:]
        var faces: [String: FaceInfo] = [:]
        cameraInfos.forEach {
            if let a = $0.aesthetic { aesthetic[$0.id] = a }
            if let f = $0.faces { faces[$0.id] = f }
        }

        return Library(images: images, videos: videos, byId: byId, features: features, aesthetic: aesthetic, faces: faces,
                       unanalyzed: unanalyzed, groups: groups, scenes: scenes, bad: bad, screenshots: screenshots,
                       bigVideos: bigVideos, videoSuggest: videoSuggest, videoScenes: videoScenes, compressible: compressible,
                       compressedPending: compressedPending, videosUnanalyzed: videosUnanalyzed,
                       docKinds: docKinds, shotTexts: shotTexts, sessions: sessions, textsPending: textsPending, settings: settings)
    }
}
