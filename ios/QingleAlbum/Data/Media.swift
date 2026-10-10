import Foundation
import Photos

/// 相册里的一张照片或一段视频
struct MediaItem: Identifiable, Hashable {
    let id: String
    let asset: PHAsset
    let timeMs: Int64
    /// 修改时间（编辑过就要重新分析）
    let modified: Double
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
            if let c = cached[id], c.mod == mod { size = c.size } else {
                size = fileSize(a)
                newSizes.append((id, mod, size))
            }
            let loc = a.location?.coordinate
            let item = MediaItem(
                id: id, asset: a,
                timeMs: Int64(((a.creationDate ?? .distantPast).timeIntervalSince1970 * 1000).rounded()),
                modified: mod, width: a.pixelWidth, height: a.pixelHeight,
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

    /// 这张照片/视频一共占多少空间：所有资源（原图、编辑后的、实况照片的视频）加起来
    static func fileSize(_ a: PHAsset) -> Int64 {
        var total: Int64 = 0
        for r in PHAssetResource.assetResources(for: a) {
            if let n = r.value(forKey: "fileSize") as? CLong { total += Int64(n) }
        }
        return total
    }
}
