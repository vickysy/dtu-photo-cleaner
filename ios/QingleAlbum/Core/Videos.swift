import Foundation

/**
 一个视频抽几帧（10%、30%、50%、70%、90% 处）算出来的特征。
 每一帧都用和照片一样的方法（ImageMetrics）算，所以“暗、纯色、模糊”的标准和照片一致。和安卓版 core/Videos.kt 一样。
 */
struct VideoFeatures: Codable {
    /// 抽到的帧数（解码失败的不算）；0 = 读不到（只在 iCloud 里）
    var frames: Int
    var darkFrames: Int
    var plainFrames: Int
    var blurryFrames: Int
    /// 每帧的画面指纹
    var hashes: [UInt64]
    /// 中间那帧的颜色分布
    var color: [UInt8]
    /// 中间那帧的美感分 1..10
    var aesthetic: Float?

    static let unreadable = VideoFeatures(frames: 0, darkFrames: 0, plainFrames: 0, blurryFrames: 0, hashes: [], color: [])
}

enum VideoReason: String {
    case dark = "黑屏或挡住了"
    case plain = "误录：没拍到东西"
    case duplicate = "重复保存"
    case short = "不到 3 秒"
    case blurry = "可能全程模糊"

    /// 拿得准的才默认勾选
    var confident: Bool { self != .blurry }
}

struct VideoInfo {
    let id: String
    let timeMs: Int64
    let durationMs: Int64
    let size: Int64
    let width: Int
    let height: Int
    /// 相机拍的（不是录屏、不是存下来的）
    let camera: Bool
    let favorite: Bool
    let f: VideoFeatures
}

enum VideoRules {
    /// 至少这么多帧都是黑的/纯色的，才算整段没拍到东西
    private static func mostly(_ n: Int, _ total: Int) -> Bool { total >= 3 && n >= Int(ceil(Float(total) * 0.8)) }

    static func reasons(_ v: VideoInfo) -> [VideoReason] {
        let f = v.f
        if mostly(f.darkFrames, f.frames) { return [.dark] }
        if mostly(f.plainFrames, f.frames) { return [.plain] }
        // 每一帧都糊才提示，而且不默认勾选：手机视频为了流畅本来就比照片软
        if f.frames >= 3 && f.blurryFrames == f.frames { return [.blurry] }
        return []
    }

    /// 两段视频是不是同一个视频（重复保存、微信转存的压缩版）
    static func isDuplicate(_ a: VideoInfo, _ b: VideoInfo) -> Bool {
        if abs(a.durationMs - b.durationMs) > 700 { return false }
        // 颜色也要接近：两段不同的空白视频（一段黑、一段灰）画面指纹都是 0，不能算重复
        if Similarity.colorDistance(a.f.color, b.f.color) > 0.08 { return false }
        let n = min(a.f.hashes.count, b.f.hashes.count)
        if n < 3 { return false }
        let same = (0..<n).filter { (a.f.hashes[$0] ^ b.f.hashes[$0]).nonzeroBitCount <= 10 }.count
        return same >= n - 1
    }

    /// 重复保存的视频：每组留一个（收藏 > 相机原片 > 不糊 > 分辨率高 > 文件大），返回 待删 id → 留下的 id
    static func duplicates(_ videos: [VideoInfo]) -> [String: String] {
        let sorted = videos.sorted { $0.durationMs < $1.durationMs }
        var parent: [String: String] = [:]
        func find(_ x: String) -> String {
            var r = x
            while let p = parent[r], p != r { r = p }
            return r
        }
        for i in sorted.indices {
            var j = i + 1
            while j < sorted.count && sorted[j].durationMs - sorted[i].durationMs <= 700 {
                if isDuplicate(sorted[i], sorted[j]) { parent[find(sorted[j].id)] = find(sorted[i].id) }
                j += 1
            }
        }
        var out: [String: String] = [:]
        for g in Dictionary(grouping: sorted, by: { find($0.id) }).values where g.count >= 2 {
            let keep = g.max { a, b in
                (a.favorite ? 1 : 0, a.camera ? 1 : 0, -a.f.blurryFrames, Int64(a.width) * Int64(a.height), a.size) <
                    (b.favorite ? 1 : 0, b.camera ? 1 : 0, -b.f.blurryFrames, Int64(b.width) * Int64(b.height), b.size)
            }!
            g.filter { $0.id != keep.id && !$0.favorite }.forEach { out[$0.id] = keep.id }
        }
        return out
    }

    // ---------- 压缩 ----------

    /// 压成 1080p HEVC 后的码率，大约 8 Mbps（60 帧的多 50%）
    static let targetBitrate: Int64 = 8_000_000

    /// 估计压缩后的大小：视频按目标码率（原片更低就按原片），加 128 kbps 音频
    static func estimateSize(durationMs: Int64, sourceBitrate: Int64) -> Int64 {
        let v = sourceBitrate > 0 ? min(sourceBitrate, targetBitrate) : targetBitrate
        return (v + 128_000) * durationMs / 8000
    }

    /// 值得压：能省一半以上，而且至少省 20 MB
    static func worthCompressing(size: Int64, estimated: Int64) -> Bool { estimated < size / 2 && size - estimated > 20_000_000 }
}

/// 一个场景里拍的好几段视频；keep 是推荐留下的（按推荐顺序）
struct VideoScene: Identifiable {
    let id: String
    let members: [VideoInfo]
    let keep: [String]
    let keepCount: Int
    /// 收藏的视频：一律保留，不算在“留几段”里
    let favorites: [String]
    var keepIds: Set<String> { Set(keep).union(favorites) }
    var startTime: Int64 { members.first?.timeMs ?? 0 }
    var endTime: Int64 { members.last.map { $0.timeMs + $0.durationMs } ?? 0 }
    var totalDurationMs: Int64 { members.reduce(0) { $0 + $1.durationMs } }
}

/**
 视频场景精选：同一个地方前后挨着拍了好几段（孩子表演录了好几遍、演唱会录了十几段）。
 推荐留下的：时长够、画面清楚、不黑、构图好看，而且尽量彼此不重复。
 */
enum VideoScenes {
    static let gapMs: Int64 = 20 * 60_000
    static let minVideos = 4

    static func segment(_ videos: [VideoInfo]) -> [[VideoInfo]] {
        let sorted = videos.filter { $0.camera && $0.f.frames > 0 }.sorted { $0.timeMs < $1.timeMs }
        var out: [[VideoInfo]] = []
        var cur: [VideoInfo] = []
        for v in sorted {
            if let prev = cur.last, v.timeMs - (prev.timeMs + prev.durationMs) > gapMs {
                if cur.count >= minVideos { out.append(cur) }
                cur = []
            }
            cur.append(v)
        }
        if cur.count >= minVideos { out.append(cur) }
        return out
    }

    /// 6 段以内留 2 段，12 段以内留 3 段，更多留 4 段
    static func defaultKeep(_ n: Int) -> Int { n <= 6 ? 2 : n <= 12 ? 3 : 4 }

    static func quality(_ v: VideoInfo, _ aesMean: Float, _ aesStd: Float) -> Float {
        let f = v.f
        let frames = Float(max(1, f.frames))
        let dur = min(1, max(0, (Float(v.durationMs) - 3_000) / 27_000))
        let clear = 1 - Float(f.blurryFrames) / frames
        let lit = 1 - Float(f.darkFrames + f.plainFrames) / frames
        var aesN: Float = 0.5
        if let a = f.aesthetic, a > 0, aesStd > 0 { aesN = min(1, max(0, 0.5 + 0.22 * (a - aesMean) / aesStd)) }
        return (0.3 * dur + 0.2 * clear + 0.4 * aesN + 0.1) * (0.3 + 0.7 * lit)
    }

    static func similarity(_ a: VideoInfo, _ b: VideoInfo, _ span: Int64) -> Float {
        var best = 64
        for x in a.f.hashes { for y in b.f.hashes { best = min(best, (x ^ y).nonzeroBitCount) } }
        let hashSim = min(1, max(0, 1 - Float(best) / 32))
        let colorSim = min(1, max(0, 1 - Similarity.colorDistance(a.f.color, b.f.color) / 0.2))
        let tau = max(180_000, Float(span) * 0.1)
        let timeSim = exp(-Float(abs(a.timeMs - b.timeMs)) / tau)
        return 0.5 * hashSim + 0.2 * colorSim + 0.3 * timeSim
    }

    static func pick(_ members: [VideoInfo], _ k: Int) -> [VideoInfo] {
        if members.isEmpty { return [] }
        let known = members.compactMap { v -> Float? in (v.f.aesthetic ?? 0) > 0 ? v.f.aesthetic : nil }
        let mean = known.isEmpty ? 0 : known.reduce(0, +) / Float(known.count)
        let std: Float = known.count < 2 ? 0 : sqrt(known.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Float(known.count))
        let q = Dictionary(uniqueKeysWithValues: members.map { ($0.id, quality($0, mean, std)) })
        let span = (members.map { $0.timeMs }.max() ?? 0) - (members.map { $0.timeMs }.min() ?? 0)
        let favorites = members.filter { $0.favorite }
        let pool = members.filter { !$0.favorite }
        var picked: [VideoInfo] = []
        while picked.count < min(k, pool.count) {
            var best: VideoInfo?
            var bestScore = -Float.infinity
            for m in pool where !picked.contains(where: { $0.id == m.id }) {
                let redundancy = (picked + favorites).map { similarity(m, $0, span) }.max() ?? 0
                let s = q[m.id]! - 0.6 * redundancy
                if s > bestScore { bestScore = s; best = m }
            }
            guard let b = best else { break }
            picked.append(b)
        }
        return picked
    }

    static func build(_ members: [VideoInfo], keep: Int? = nil) -> VideoScene {
        let sorted = members.sorted { $0.timeMs < $1.timeMs }
        let k = keep ?? defaultKeep(sorted.filter { !$0.favorite }.count)
        return VideoScene(id: sorted[0].id, members: sorted, keep: pick(sorted, k).map { $0.id }, keepCount: k,
                          favorites: sorted.filter { $0.favorite }.map { $0.id })
    }
}
