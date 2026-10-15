import Foundation
import Photos

/// 相册里的一张照片或一段视频
struct MediaItem: Identifiable, Hashable {
    let id: String
    let asset: PHAsset
    let timeMs: Int64
    /// 修改时间（编辑过就要重新分析）
    let modified: Double
    /// 编辑过（裁剪、滤镜、剪视频）
    let edited: Bool
    let width: Int
    let height: Int
    let isVideo: Bool
    let isScreenshot: Bool
    let isScreenRecording: Bool
    let favorite: Bool
    let durationMs: Int64
    /// 占用空间（含实况照片的视频、编辑前的原图）
    var size: Int64
    let lat: Double?
    let lng: Double?

    var maxSide: Int { max(width, height) }
    /// 视频清晰度：4K、2.7K 看长边，其余按短边（竖着拍的也对），裁过一点的往常见档位上靠
    var resolution: String? {
        guard isVideo else { return nil }
        let long = max(width, height), short = min(width, height)
        if long >= 3800 { return "4K" }
        if long >= 2600 { return "2.7K" }
        return [1440, 1080, 720, 540, 480, 360].first { Double(short) >= Double($0) * 0.9 }.map { "\($0)p" }
    }

    /**
     以前在 [mod] 那个修改时间分析过，现在的内容还是不是那时的样子。
     没编辑过的照片内容不会变——系统自己也会改修改时间（升级系统后会把整个相册重新整理一遍），
     只看修改时间的话，分析过的会被当成新照片一遍遍重新分析，App 一直忙、点不动。编辑过的才看修改时间。
     */
    func sameContent(asOf mod: Double) -> Bool { mod == modified || !edited }
    /// 相机拍的：iPhone 相机照片最长边都在 3000 以上；存下来的微信图、表情包、网图一般小得多
    /// 照片还要是相机的比例（4:3、16:9、1:1）：存下来的别人的截图、长图也很大，但比例细长
    /// （视频：iPhone 拍的至少 1080p，微信存下来的多半更小）
    var isCameraShot: Bool {
        if isScreenshot { return false }
        if isVideo { return !isScreenRecording && maxSide >= 1920 }
        return maxSide >= 2500 && Double(maxSide) <= Double(max(1, min(width, height))) * 2.05
    }
    /// 码率（比特/秒），估算压缩能省多少用
    var bitrate: Int64 { durationMs > 0 ? size * 8 * 1000 / durationMs : 0 }

    static func == (a: MediaItem, b: MediaItem) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

enum PhotoSource {
    /// 读整个相册（照片 + 视频），按时间从新到旧；文件大小从缓存取，没有的再算
    static func load() -> (images: [MediaItem], videos: [MediaItem]) {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.includeAssetSourceTypes = [.typeUserLibrary, .typeiTunesSynced]
        let result = PHAsset.fetchAssets(with: opts)
        latest.set(result)
        let cached = Store.shared.loadSizes()
        var newSizes: [(id: String, mod: Double, size: Int64)] = []
        var images: [MediaItem] = []
        var videos: [MediaItem] = []
        images.reserveCapacity(result.count)
        result.enumerateObjects { a, _, _ in
            guard a.mediaType == .image || a.mediaType == .video else { return }
            let id = a.localIdentifier
            let mod = (a.modificationDate ?? a.creationDate ?? .distantPast).timeIntervalSince1970
            var size: Int64
            let edited = a.hasAdjustments
            if let c = cached[id], c.mod == mod || !edited { size = c.size } else {
                size = fileSize(a)
                newSizes.append((id, mod, size))
            }
            let loc = a.location?.coordinate
            let item = MediaItem(
                id: id, asset: a,
                timeMs: Int64(((a.creationDate ?? .distantPast).timeIntervalSince1970 * 1000).rounded()),
                modified: mod, edited: edited, width: a.pixelWidth, height: a.pixelHeight,
                isVideo: a.mediaType == .video,
                isScreenshot: a.mediaSubtypes.contains(.photoScreenshot),
                isScreenRecording: a.mediaType == .video && a.mediaSubtypes.contains(.videoScreenRecording),
                favorite: a.isFavorite,
                durationMs: Int64(a.duration * 1000),
                size: size,
                lat: loc.map { $0.latitude }, lng: loc.map { $0.longitude }
            )
            if item.isVideo { videos.append(item) } else { images.append(item) }
        }
        Store.shared.putSizes(newSizes)
        return (images, videos)
    }

    /// 上次读相册用的查询结果：相册变了的时候拿它算出到底变了什么
    static let latest = FetchHolder()

    /**
     相册变了，要不要重新读：有新照片、删了照片、改了收藏、编辑了才要。
     系统自己整理相册（升级系统后会持续好几天）只改修改时间，不用管——以前每次都重读整个相册，App 一直卡。
     在系统通知的那个线程上调。
     */
    static func needsReload(_ change: PHChange) -> Bool {
        guard let before = latest.get(), let d = change.changeDetails(for: before) else { return true }
        latest.set(d.fetchResultAfterChanges)
        guard d.hasIncrementalChanges, d.insertedObjects.isEmpty, d.removedObjects.isEmpty, !d.hasMoves else { return true }
        let after = d.fetchResultAfterChanges
        var relevant = false
        d.changedIndexes?.forEach { i in
            guard !relevant, i < after.count, i < before.count else { return }
            let a = after.object(at: i), b = before.object(at: i)
            if a.localIdentifier != b.localIdentifier || a.isFavorite != b.isFavorite || a.hasAdjustments != b.hasAdjustments
                || a.pixelWidth != b.pixelWidth || a.pixelHeight != b.pixelHeight
                || (a.hasAdjustments && a.modificationDate != b.modificationDate) {
                relevant = true
            }
        }
        return relevant
    }

    /// 这张照片/视频一共占多少空间：所有资源（原图、编辑后的、实况照片的视频）加起来
    static func fileSize(_ a: PHAsset) -> Int64 {
        var total: Int64 = 0
        for r in PHAssetResource.assetResources(for: a) {
            if let n = r.value(forKey: "fileSize") as? CLong { total += Int64(n) }
        }
        return total
    }
}

/// 多个线程都要读写的“上次的查询结果”
final class FetchHolder: @unchecked Sendable {
    private let lock = NSLock()
    private var value: PHFetchResult<PHAsset>?
    func get() -> PHFetchResult<PHAsset>? { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ v: PHFetchResult<PHAsset>) { lock.lock(); value = v; lock.unlock() }
}
