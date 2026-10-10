import AVFoundation
import Foundation
import Photos

/**
 视频压缩：4K 视频转成 1080p H.265，全在手机上转码。
 压好的存进相册，拍摄时间、地点、收藏都和原视频一样；原视频先不动，等用户对比确认后才删（进“最近删除”）。
 */
enum Compressor {
    enum Failure: LocalizedError {
        case unreadable, exportFailed(String), verifyFailed
        var errorDescription: String? {
            switch self {
            case .unreadable: return "读不到原视频"
            case .exportFailed(let m): return "转码失败：\(m)"
            case .verifyFailed: return "压出来的视频不对，已作废，原视频没动"
            }
        }
    }

    /// 压缩一个视频，返回压好的新视频 id。[onProgress] 0..1
    static func compress(_ m: MediaItem, onProgress: @escaping (Double) -> Void) async throws -> String {
        // 原视频可能在 iCloud 里，压缩需要原片，允许下载
        let asset: AVAsset? = await withCheckedContinuation { c in
            DispatchQueue.global(qos: .userInitiated).async { c.resume(returning: Analyzer.loadAVAsset(m.asset, network: true)) }
        }
        guard let asset, let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHEVC1920x1080) else {
            throw Failure.unreadable
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("qingle-\(UUID().uuidString).mov")
        defer { try? FileManager.default.removeItem(at: url) }
        session.shouldOptimizeForNetworkUse = false
        let watcher = Task {
            for await state in session.states(updateInterval: 0.5) {
                if case .exporting(let p) = state { onProgress(p.fractionCompleted) }
            }
        }
        do {
            try await session.export(to: url, as: .mov)
        } catch {
            watcher.cancel()
            throw Failure.exportFailed(error.localizedDescription)
        }
        watcher.cancel()

        // 核对：时长差不多、确实变小了
        let out = AVURLAsset(url: url)
        let outDur = (try? await out.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
        guard abs(outDur * 1000 - Double(m.durationMs)) < 1500, size > 0, size < m.size else { throw Failure.verifyFailed }

        var newId: String?
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let o = PHAssetResourceCreationOptions()
            o.shouldMoveFile = true
            req.addResource(with: .video, fileURL: url, options: o)
            req.creationDate = m.asset.creationDate
            req.location = m.asset.location
            req.isFavorite = m.asset.isFavorite
            newId = req.placeholderForCreatedAsset?.localIdentifier
        }
        guard let newId else { throw Failure.verifyFailed }
        Store.shared.putCompressed(src: m.id, out: newId)
        return newId
    }
}
