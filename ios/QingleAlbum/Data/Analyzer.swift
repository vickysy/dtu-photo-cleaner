import AVFoundation
import CoreGraphics
import Foundation
import Photos
import UIKit
import Vision

/// 分析进度
struct Progress: Equatable {
    var running = false
    /// 正在做什么：“正在分析照片”“正在看视频”
    var label = "正在分析照片"
    var done = 0
    var total = 0
    var message: String?
    var fraction: Double { total > 0 ? Double(done) / Double(total) : 0 }
}

/**
 分析照片：全在手机上算，不联网（只读本机有的版本，iCloud 里没下载的用本机缩略图）。
 - 清晰度、曝光、画面指纹：和安卓版同一套算法（512px）；
 - 美感分：系统自带的美感评分（iOS 18 的 Vision），换算到 1..10；
 - 人脸：几张脸、有没有人闭眼、系统给的人脸拍摄质量。
 */
enum Analyzer {
    /// 分析算法有变化时 +1，旧缓存会自动重新分析
    static let version = 1
    /// 太小的图（表情包、缩略图）不参与分析
    static let minSide = 400

    static func eligible(_ m: MediaItem) -> Bool { !m.isVideo && !m.isScreenshot && m.maxSide >= minSide }

    static func needsWork(_ m: MediaItem, _ stored: [String: Store.FeatRow]) -> Bool {
        guard let r = stored[m.id] else { return true }
        return r.mod != m.modified || r.ver != version
    }

    /// 读图、算特征、跑 Vision 都是阻塞的，放在自己的线程上做（不能占着 Swift 并发的共享线程，Vision 内部还要用线程）
    private static let workQueue = DispatchQueue(label: "analyze", qos: .utility)

    private static let parallelQueue = DispatchQueue(label: "analyze.parallel", qos: .utility, attributes: .concurrent)

    /// 同时最多做 [width] 张（每张都要加载 Vision 模型，同时太多 iPhone 内存吃不消），做完才返回
    private static func parallel(_ count: Int, width: Int, _ body: @escaping (Int) -> Void) {
        let group = DispatchGroup()
        let slots = DispatchSemaphore(value: width)
        for i in 0..<count {
            slots.wait()
            group.enter()
            parallelQueue.async {
                body(i)
                slots.signal()
                group.leave()
            }
        }
        group.wait()
    }

    /// 分析 [items]；每做完一批回调一次进度。可以取消
    static func run(_ items: [MediaItem], onProgress: @escaping (Int, Int) -> Void) async {
        let total = items.count
        var done = 0
        onProgress(0, total)
        let chunks = stride(from: 0, to: items.count, by: 12).map { Array(items[$0..<min($0 + 12, items.count)]) }
        for chunk in chunks {
            if Task.isCancelled { return }
            let rows: [(id: String, mod: Double, feat: StoredFeat)] = await withCheckedContinuation { c in
                workQueue.async {
                    var out = [StoredFeat?](repeating: nil, count: chunk.count)
                    let lock = NSLock()
                    parallel(chunk.count, width: 3) { i in
                        let r = autoreleasepool { analyze(chunk[i]) }
                        lock.lock(); out[i] = r; lock.unlock()
                    }
                    c.resume(returning: chunk.indices.map { (chunk[$0].id, chunk[$0].modified, out[$0] ?? StoredFeat()) })
                }
            }
            Store.shared.putFeatures(rows, ver: version)
            done += chunk.count
            onProgress(done, total)
        }
    }

    static func analyze(_ m: MediaItem) -> StoredFeat {
        guard let big = loadImage(m.asset, side: 1024) else { return StoredFeat() }
        var feat = StoredFeat()
        if let (rgba, w, h) = rgbaPixels(big, maxSide: ImageMetrics.analysisSize), w >= 9, h >= 9 {
            var f = ImageMetrics.compute(rgba: rgba, w: w, h: h)
            // 本机只有很小的缩略图（照片在 iCloud 里）：清晰度不可信，不判断模糊
            if max(big.width, big.height) < 400 { f = f.withoutSharpness() }
            feat.features = f
        }
        vision(big, into: &feat)
        return feat
    }

    // ---------- 读图 ----------

    private static let manager = PHImageManager.default()

    /// 同步读一张图（在后台线程调），最长边约 [side]；不联网
    static func loadImage(_ asset: PHAsset, side: CGFloat) -> CGImage? {
        let o = PHImageRequestOptions()
        o.isSynchronous = true
        o.deliveryMode = .highQualityFormat
        o.resizeMode = .exact
        o.isNetworkAccessAllowed = false
        var out: CGImage?
        manager.requestImage(for: asset, targetSize: CGSize(width: side, height: side), contentMode: .aspectFit, options: o) { img, _ in
            out = img?.cgImage
        }
        return out
    }

    /// 缩到最长边 [maxSide] 的 RGBA 像素
    static func rgbaPixels(_ img: CGImage, maxSide: Int) -> ([UInt8], Int, Int)? {
        let s = min(1, Double(maxSide) / Double(max(img.width, img.height)))
        let w = max(1, Int((Double(img.width) * s).rounded()))
        let h = max(1, Int((Double(img.height) * s).rounded()))
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ok = buf.withUnsafeMutableBytes { p -> Bool in
            guard let ctx = CGContext(
                data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? (buf, w, h) : nil
    }

    // ---------- 系统视觉能力 ----------

    private static func vision(_ img: CGImage, into feat: inout StoredFeat) {
        let handler = VNImageRequestHandler(cgImage: img, options: [:])
        let aes = VNCalculateImageAestheticsScoresRequest()
        let faces = VNDetectFaceLandmarksRequest()
        try? handler.perform([aes, faces])
        if let r = aes.results?.first {
            // overallScore 是 -1..1，换算到和安卓版一样的 1..10
            feat.aesthetic = 5.5 + 4.5 * r.overallScore
            feat.utility = r.isUtility
        }
        let found = (faces.results ?? []).filter { $0.boundingBox.height >= 0.06 }
        var quality: [Float] = []
        if !found.isEmpty {
            let q = VNDetectFaceCaptureQualityRequest()
            q.inputFaceObservations = found
            try? handler.perform([q])
            quality = (q.results ?? []).compactMap { $0.faceCaptureQuality }
        }
        let eyes = found.compactMap { eyesOpen($0) }
        feat.faces = FaceInfo(
            count: found.count,
            minEyesOpen: eyes.min() ?? -1,
            quality: quality.isEmpty ? -1 : quality.reduce(0, +) / Float(quality.count)
        )
    }

    /// 睁眼程度 0..1：两只眼睛轮廓的高宽比，取睁得小的那只。脸太小、看不清就返回 nil
    private static func eyesOpen(_ face: VNFaceObservation) -> Float? {
        guard face.boundingBox.height >= 0.1, let l = face.landmarks?.leftEye, let r = face.landmarks?.rightEye else { return nil }
        func ratio(_ e: VNFaceLandmarkRegion2D) -> Float? {
            let pts = e.normalizedPoints
            guard pts.count >= 4 else { return nil }
            let xs = pts.map { $0.x }, ys = pts.map { $0.y }
            let w = (xs.max()! - xs.min()!)
            guard w > 0 else { return nil }
            return Float((ys.max()! - ys.min()!) / w)
        }
        guard let a = ratio(l), let b = ratio(r) else { return nil }
        // 睁着的眼睛高宽比大约 0.3–0.45，闭着的在 0.15 以下
        return min(1, max(0, (min(a, b) - 0.12) / 0.16))
    }

    // ---------- 视频 ----------

    static let videoVersion = 1

    /// 录屏不看
    static func videoEligible(_ m: MediaItem) -> Bool { m.isVideo && !m.isScreenRecording && m.durationMs > 0 }

    static func videoNeedsWork(_ m: MediaItem, _ stored: [String: Store.VideoRow]) -> Bool {
        guard let r = stored[m.id] else { return true }
        return r.mod != m.modified || r.ver != videoVersion
    }

    /// 视频抽帧：每个视频抽 5 帧（不到 3 秒的抽 1 帧），用和照片一样的方法判断黑、纯色、糊
    static func runVideos(_ items: [MediaItem], onProgress: @escaping (Int, Int) -> Void) async {
        let total = items.count
        var done = 0
        onProgress(0, total)
        for chunk in stride(from: 0, to: items.count, by: 4).map({ Array(items[$0..<min($0 + 4, items.count)]) }) {
            if Task.isCancelled { return }
            let rows: [(id: String, mod: Double, feat: VideoFeatures)] = await withCheckedContinuation { c in
                workQueue.async {
                    var out = [VideoFeatures?](repeating: nil, count: chunk.count)
                    let lock = NSLock()
                    parallel(chunk.count, width: 2) { i in
                        let r = autoreleasepool { analyzeVideo(chunk[i]) }
                        lock.lock(); out[i] = r; lock.unlock()
                    }
                    c.resume(returning: chunk.indices.map { (chunk[$0].id, chunk[$0].modified, out[$0] ?? .unreadable) })
                }
            }
            Store.shared.putVideos(rows, ver: videoVersion)
            done += chunk.count
            onProgress(done, total)
        }
    }

    static func analyzeVideo(_ m: MediaItem) -> VideoFeatures {
        guard let asset = loadAVAsset(m.asset, network: false) else { return .unreadable }
        let points: [Double] = m.durationMs < 3000 ? [0.5] : [0.1, 0.3, 0.5, 0.7, 0.9]
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 640, height: 640)
        gen.requestedTimeToleranceBefore = CMTime(seconds: 1, preferredTimescale: 600)
        gen.requestedTimeToleranceAfter = CMTime(seconds: 1, preferredTimescale: 600)
        var feats: [ImageFeatures] = []
        var aes: Float?
        for (i, p) in points.enumerated() {
            guard let img = frame(gen, at: Double(m.durationMs) / 1000 * p) else { continue }
            if let (rgba, w, h) = rgbaPixels(img, maxSide: ImageMetrics.analysisSize), w >= 9, h >= 9 {
                feats.append(ImageMetrics.compute(rgba: rgba, w: w, h: h))
            }
            if i == points.count / 2 { aes = aesthetics(img)?.score }
        }
        if feats.isEmpty { return .unreadable }
        let reasons = feats.map { BadShotRules.reasons($0, 1) }
        return VideoFeatures(
            frames: feats.count,
            darkFrames: reasons.filter { $0.contains(.dark) }.count,
            plainFrames: reasons.filter { $0.contains(.plain) }.count,
            blurryFrames: reasons.filter { $0.contains(.blurry) || $0.contains(.maybeBlurry) }.count,
            hashes: feats.map { $0.dHash },
            color: feats[feats.count / 2].color,
            aesthetic: aes
        )
    }

    private static func frame(_ gen: AVAssetImageGenerator, at seconds: Double) -> CGImage? {
        let sem = DispatchSemaphore(value: 0)
        var out: CGImage?
        gen.generateCGImageAsynchronously(for: CMTime(seconds: seconds, preferredTimescale: 600)) { img, _, _ in
            out = img
            sem.signal()
        }
        // 解不出来的帧不能一直等着，把整个分析卡住
        if sem.wait(timeout: .now() + 15) == .timedOut { gen.cancelAllCGImageGeneration(); return nil }
        return out
    }

    /// 从相册拿视频文件（在后台线程调）。network = false 时只用本机有的
    static func loadAVAsset(_ a: PHAsset, network: Bool) -> AVAsset? {
        let o = PHVideoRequestOptions()
        o.isNetworkAccessAllowed = network
        o.version = .current
        o.deliveryMode = .highQualityFormat
        let sem = DispatchSemaphore(value: 0)
        var out: AVAsset?
        let id = PHImageManager.default().requestAVAsset(forVideo: a, options: o) { asset, _, _ in
            out = asset
            sem.signal()
        }
        // 本机读：几秒内就该好；从 iCloud 下载（压缩时）给足时间
        if sem.wait(timeout: .now() + (network ? 600 : 20)) == .timedOut { PHImageManager.default().cancelImageRequest(id); return nil }
        return out
    }

    /// 美感分（1..10）和是不是“实用类”图片
    static func aesthetics(_ img: CGImage) -> (score: Float, utility: Bool)? {
        let req = VNCalculateImageAestheticsScoresRequest()
        try? VNImageRequestHandler(cgImage: img, options: [:]).perform([req])
        guard let r = req.results?.first else { return nil }
        // overallScore 是 -1..1，换算到和安卓版一样的 1..10
        return (5.5 + 4.5 * r.overallScore, r.isUtility)
    }

    // ---------- 识字 ----------

    static let textVersion = 1

    /// 要识字的：截图、存下来的图全识；相机照片只识系统认为是“实用类”的（文件、收据、白板），风景人像不用识
    static func textEligible(_ m: MediaItem, utility: Bool?) -> Bool {
        guard !m.isVideo, m.maxSide >= minSide else { return false }
        return !m.isCameraShot || utility == true
    }

    static func textNeedsWork(_ m: MediaItem, _ texts: [String: (mod: Double, ver: Int, row: Store.TextRow)]) -> Bool {
        guard let t = texts[m.id] else { return true }
        return t.mod != m.modified || t.ver != textVersion
    }

    static func runTexts(_ items: [MediaItem], onProgress: @escaping (Int, Int) -> Void) async {
        let total = items.count
        var done = 0
        onProgress(0, total)
        for chunk in stride(from: 0, to: items.count, by: 8).map({ Array(items[$0..<min($0 + 8, items.count)]) }) {
            if Task.isCancelled { return }
            let rows: [(id: String, mod: Double, row: Store.TextRow)] = await withCheckedContinuation { c in
                workQueue.async {
                    var out = [Store.TextRow?](repeating: nil, count: chunk.count)
                    let lock = NSLock()
                    parallel(chunk.count, width: 2) { i in
                        let r = autoreleasepool { recognize(chunk[i]) }
                        lock.lock(); out[i] = r; lock.unlock()
                    }
                    c.resume(returning: chunk.indices.map { (chunk[$0].id, chunk[$0].modified, out[$0] ?? Store.TextRow(text: "", codes: [], layout: nil)) })
                }
            }
            Store.shared.putTexts(rows, ver: textVersion)
            done += chunk.count
            onProgress(done, total)
        }
    }

    /// 识一张图里的字和二维码，记下字的排布；证件上的长串号码打码后再存
    static func recognize(_ m: MediaItem) -> Store.TextRow {
        // 宽度读到 1290 左右（手机截图原大）；长图按宽度读，不然字太小认不出
        let w = Double(max(1, m.width)), h = Double(max(1, m.height))
        let scale = min(1, 1290 / w, 8000 / h)
        guard let img = loadImage(m.asset, target: CGSize(width: w * scale, height: h * scale)) else {
            return Store.TextRow(text: "", codes: [], layout: nil)
        }
        let handler = VNImageRequestHandler(cgImage: img, options: [:])
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.recognitionLanguages = ["zh-Hans", "en-US"]
        req.usesLanguageCorrection = true
        let bar = VNDetectBarcodesRequest()
        try? handler.perform(m.isCameraShot ? [req] : [req, bar])
        // 从上往下排（Vision 的坐标原点在左下角）
        let lines: [(String, CGRect)] = (req.results ?? []).sorted { $0.boundingBox.maxY > $1.boundingBox.maxY }
            .compactMap { o in o.topCandidates(1).first.map { ($0.string, o.boundingBox) } }
        var text = lines.map { $0.0 }.joined(separator: "\n")
        let codes = (bar.results ?? []).compactMap { $0.payloadStringValue }
        let layout = layoutOf(lines, img)
        if ScreenText.classify(text, codes: codes, layout: layout, camera: m.isCameraShot) == .id { text = ScreenText.maskNumbers(text) }
        return Store.TextRow(text: text, codes: codes, layout: layout)
    }

    /// 字的排布：哪些行靠左、哪些靠右、有没有居中的时间、微信绿气泡（和安卓版一样，坐标换成千分比、从上往下）
    private static func layoutOf(_ lines: [(String, CGRect)], _ img: CGImage) -> TextLayout {
        func pm(_ v: CGFloat) -> Int { min(1000, max(0, Int((v * 1000).rounded()))) }
        let area = lines.reduce(0.0) { $0 + Double($1.1.width * $1.1.height) }
        let cjk = lines.filter { $0.0.filter(ScreenText.isCJK).count >= 2 }
        let boxes = cjk.map { s, r in
            TextBox(l: pm(r.minX), t: pm(1 - r.maxY), r: pm(r.maxX), b: pm(1 - r.minY), n: s.filter(ScreenText.isCJK).count)
        }.sorted { $0.t < $1.t }
        // 聊天气泡：左边的从头像右边开始、不到右边；右边的贴着右边、从中间往后开始
        let sides = boxes.map { b in b.l < 300 && b.r < 780 ? -1 : (b.l > 220 && b.r > 700 ? 1 : 0) }
        var switches = 0
        var last = 0
        for sd in sides where sd != 0 { if last != 0 && sd != last { switches += 1 }; last = sd }
        // 居中的时间（最上面状态栏的不算）
        let times = lines.filter { s, r in
            1 - r.maxY > 0.06 && abs(r.midX - 0.5) < 0.08 && r.width < 0.6 &&
                ScreenText.matches(ScreenText.chatStamp, s.replacingOccurrences(of: " ", with: ""))
        }.count
        // 颜色：缩到 72 像素宽看微信绿气泡、底色平不平
        var green = -1, bg = -1
        let side = Int((72.0 * Double(max(img.width, img.height)) / Double(max(1, img.width))).rounded())
        if let (px, _, _) = rgbaPixels(img, maxSide: side) { (green, bg) = ScreenText.pixelStats(px) }
        return TextLayout(blocks: boxes.count, left: sides.filter { $0 == -1 }.count, right: sides.filter { $0 == 1 }.count,
                          switches: switches, coverage: Float(min(1, area)), times: times, boxes: boxes, wechatGreen: green, background: bg)
    }

    /// 按指定大小读图（不联网）
    static func loadImage(_ asset: PHAsset, target: CGSize) -> CGImage? {
        let o = PHImageRequestOptions()
        o.isSynchronous = true
        o.deliveryMode = .highQualityFormat
        o.resizeMode = .exact
        o.isNetworkAccessAllowed = false
        var out: CGImage?
        manager.requestImage(for: asset, targetSize: target, contentMode: .aspectFit, options: o) { img, _ in out = img?.cgImage }
        return out
    }
}
