import ImageIO
import Photos
import UIKit
import UniformTypeIdentifiers

/**
 截图笔记：把连续截的一串图（看直播、上网课）
 - 拼成长图：每张缩到同样宽度上下接起来，太长就分成几张，存进相册“轻了笔记”；
 - 整理成文字：按顺序把每张的字接起来，去掉状态栏零碎和重复的行。
 原来的截图不动，删不删用户自己决定。
 */
enum NoteMaker {
    static let albumName = "轻了笔记"
    /// 长图宽度
    private static let width: CGFloat = 1080
    /// 一张长图最多这么高，再长就分成下一张（太长的图微信、得到打开会糊）
    private static let maxHeight: CGFloat = 12000

    /// 读原图（可以从 iCloud 下载）
    static func loadFull(_ asset: PHAsset) async -> UIImage? {
        await withCheckedContinuation { c in
            let o = PHImageRequestOptions()
            o.deliveryMode = .highQualityFormat
            o.isNetworkAccessAllowed = true
            o.resizeMode = .exact
            let w = CGFloat(asset.pixelWidth), h = CGFloat(asset.pixelHeight)
            let s = min(1, width / max(1, w))
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: w * s, height: h * s), contentMode: .aspectFit, options: o) { img, info in
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                c.resume(returning: img)
            }
        }
    }

    /// 把原图导出成临时文件（分享用，保留原来的格式和画质）
    static func exportOriginal(_ asset: PHAsset) async -> URL? {
        // 编辑过的照片分享编辑后的样子
        let all = PHAssetResource.assetResources(for: asset)
        guard let res = all.first(where: { $0.type == .fullSizePhoto }) ?? all.first(where: { $0.type == .photo }) ?? all.first else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(res.originalFilename)
        try? FileManager.default.removeItem(at: url)
        let o = PHAssetResourceRequestOptions()
        o.isNetworkAccessAllowed = true
        do {
            try await PHAssetResourceManager.default().writeData(for: res, toFile: url, options: o)
        } catch {
            return nil
        }
        // HEIC 很多 App 打不开（得到、一些安卓手机），转成 JPEG（拍摄时间、地点这些信息跟着带过去）
        let ext = url.pathExtension.lowercased()
        guard ext == "heic" || ext == "heif" else { return url }
        let jpg = url.deletingPathExtension().appendingPathExtension("jpg")
        try? FileManager.default.removeItem(at: jpg)
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let dest = CGImageDestinationCreateWithURL(jpg as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return url }
        CGImageDestinationAddImageFromSource(dest, src, 0, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return url }
        try? FileManager.default.removeItem(at: url)
        return jpg
    }

    /// 拼成长图（可能分成几张）
    static func stitch(_ images: [UIImage]) -> [UIImage] {
        var pages: [[UIImage]] = [[]]
        var h: CGFloat = 0
        for img in images {
            let ih = img.size.height * width / max(1, img.size.width)
            if h + ih > maxHeight && !pages[pages.count - 1].isEmpty { pages.append([]); h = 0 }
            pages[pages.count - 1].append(img)
            h += ih
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return pages.filter { !$0.isEmpty }.map { page in
            let total = page.reduce(CGFloat(0)) { $0 + $1.size.height * width / max(1, $1.size.width) }
            return UIGraphicsImageRenderer(size: CGSize(width: width, height: total), format: format).image { _ in
                var y: CGFloat = 0
                for img in page {
                    let ih = img.size.height * width / max(1, img.size.width)
                    img.draw(in: CGRect(x: 0, y: y, width: width, height: ih))
                    y += ih
                }
            }
        }
    }

    /// 把长图写成 JPEG 文件拿去分享：得到、微信这些 App 的分享入口认文件最稳（直接给图片对象，又大又长时常常收不到）
    static func jpegFiles(_ images: [UIImage], name: String) -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = String(name.filter { !"/\\:*?\"<>|".contains($0) }.prefix(30))
        return images.enumerated().compactMap { i, img in
            let url = dir.appendingPathComponent(images.count > 1 ? "\(safe)-\(i + 1).jpg" : "\(safe).jpg")
            guard let data = img.jpegData(compressionQuality: 0.9) else { return nil }
            try? FileManager.default.removeItem(at: url)
            return (try? data.write(to: url)) != nil ? url : nil
        }
    }

    /// 存进相册“轻了笔记”（没有就建一个）
    static func save(_ images: [UIImage]) async throws {
        let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil)
        var album: PHAssetCollection?
        existing.enumerateObjects { c, _, stop in if c.localizedTitle == albumName { album = c; stop.pointee = true } }
        try await PHPhotoLibrary.shared().performChanges {
            let albumReq = album.map { PHAssetCollectionChangeRequest(for: $0) } ?? PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: albumName)
            let placeholders = images.compactMap { PHAssetChangeRequest.creationRequestForAsset(from: $0).placeholderForCreatedAsset }
            albumReq?.addAssets(placeholders as NSArray)
        }
    }
}
