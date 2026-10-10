import Foundation

/// 人脸：[minEyesOpen] 最小睁眼程度 0..1（-1 = 没法判断），[quality] 系统给的人脸拍摄质量 0..1（清楚、正脸、光线）
struct FaceInfo: Codable, Equatable {
    var count: Int
    var minEyesOpen: Float
    var quality: Float
}

struct PhotoInfo {
    let id: String
    let timeMs: Int64
    let width: Int
    let height: Int
    let size: Int64
    let favorite: Bool
    let features: ImageFeatures
    /// nil = 还没做人脸分析
    let faces: FaceInfo?
    /// 美感分 1..10；nil = 不知道
    let aesthetic: Float?
    /// 拍摄地点；nil = 不知道
    let lat: Double?
    let lng: Double?

    var aestheticKnown: Bool { (aesthetic ?? 0) > 0 }
    var hasLocation: Bool { lat != nil && lng != nil }
}

struct GroupParams {
    /// 相邻两张间隔超过这个时间就不算“同一时期”
    var maxGapMs: Int64 = 120_000
    /// 指纹差异 ≤ 这个值：几乎肯定是同一画面
    var strictBits = 14
    /// 指纹差异 ≤ 这个值且颜色也接近：同一场景换了点角度
    var looseBits = 30
    /// 颜色差异上限（0..1）
    var maxColorDist: Float = 0.06
    /// 每组建议保留几张
    var keepCount = 1
}

enum Similarity {
    static func hashDistance(_ a: ImageFeatures, _ b: ImageFeatures) -> Int {
        (a.dHash ^ b.dHash).nonzeroBitCount + (a.vHash ^ b.vHash).nonzeroBitCount
    }

    static func colorDistance(_ a: [UInt8], _ b: [UInt8]) -> Float {
        var s = 0
        for i in 0..<min(a.count, b.count) { s += abs(Int(a[i]) - Int(b[i])) }
        return Float(s) / (Float(a.count) * 255)
    }

    static func isSimilar(_ a: ImageFeatures, _ b: ImageFeatures, _ p: GroupParams) -> Bool {
        let h = hashDistance(a, b)
        if h > p.looseBits { return false }
        let c = colorDistance(a.color, b.color)
        return h <= p.strictBits ? c <= p.maxColorDist * 2 : c <= p.maxColorDist
    }

    static func sameAspect(_ a: PhotoInfo, _ b: PhotoInfo) -> Bool {
        if a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0 { return true }
        let ra = Float(max(a.width, a.height)) / Float(min(a.width, a.height))
        let rb = Float(max(b.width, b.height)) / Float(min(b.width, b.height))
        return abs(ra - rb) < 0.02
    }

    /// 内容完全一样（重复保存、微信转存的同一张）
    static func isDuplicate(_ a: PhotoInfo, _ b: PhotoInfo) -> Bool {
        hashDistance(a.features, b.features) <= 3 && colorDistance(a.features.color, b.features.color) <= 0.02 && sameAspect(a, b)
    }
}

enum Grouper {
    /// 把照片分成“同一时期同一场景”的组，再把跨时间的完全重复并进来。只返回 ≥2 张的组，组内按时间排序
    static func group(_ photos: [PhotoInfo], _ p: GroupParams) -> [[PhotoInfo]] {
        if photos.count < 2 { return [] }
        let sorted = photos.sorted { $0.timeMs < $1.timeMs }
        var parent = Array(0..<sorted.count)
        func find(_ x: Int) -> Int {
            var r = x
            while parent[r] != r { r = parent[r] }
            var c = x
            while parent[c] != r { let n = parent[c]; parent[c] = r; c = n }
            return r
        }
        func union(_ a: Int, _ b: Int) { parent[find(a)] = find(b) }

        // 1) 时间窗口内的相似照片。每个“活跃组”记住最近几张成员
        final class Active { let anchor: Int; var recent: [Int]; var lastTime: Int64
            init(_ a: Int, _ t: Int64) { anchor = a; recent = [a]; lastTime = t } }
        var active: [Active] = []
        for i in sorted.indices {
            let ph = sorted[i]
            active.removeAll { ph.timeMs - $0.lastTime > p.maxGapMs }
            var best: Active?
            var bestD = Int.max
            for g in active {
                // 防止“一路平移”串成一大组：和组里第一张也不能差太远
                if Similarity.hashDistance(sorted[g.anchor].features, ph.features) > p.looseBits + 10 { continue }
                for m in g.recent where Similarity.isSimilar(sorted[m].features, ph.features, p) {
                    let d = Similarity.hashDistance(sorted[m].features, ph.features)
                    if d < bestD { bestD = d; best = g }
                }
            }
            if let b = best {
                union(i, b.anchor)
                b.recent.append(i)
                if b.recent.count > 6 { b.recent.removeFirst() }
                b.lastTime = ph.timeMs
            } else {
                active.append(Active(i, ph.timeMs))
            }
        }

        // 2) 跨时间的完全重复：dHash 分 4 段 16 位分桶找候选（距离 ≤3 必有一段完全相同）
        var buckets: [UInt64: [Int]] = [:]
        for i in sorted.indices {
            let h = sorted[i].features.dHash
            for k in 0..<4 {
                let chunk = (h >> (UInt64(k) * 16)) & 0xFFFF
                buckets[(UInt64(k) << 16) | chunk, default: []].append(i)
            }
        }
        for list in buckets.values where list.count >= 2 && list.count <= 300 {
            for x in 0..<list.count { for y in (x + 1)..<list.count {
                let a = list[x], b = list[y]
                if find(a) != find(b) && Similarity.isDuplicate(sorted[a], sorted[b]) { union(a, b) }
            } }
        }

        var comps: [Int: [PhotoInfo]] = [:]
        var order: [Int] = []
        for i in sorted.indices {
            let r = find(i)
            if comps[r] == nil { order.append(r) }
            comps[r, default: []].append(sorted[i])
        }
        return order.compactMap { comps[$0] }.filter { $0.count >= 2 }.map { $0.sorted { $0.timeMs < $1.timeMs } }
    }
}

/// 用户口味：挑照片时美感占多大比重（默认 0.35），从用户改过的推荐里学
struct Taste {
    static let defaultWeight: Float = 0.35
    static let normal = Taste(aestheticWeight: defaultWeight)
    var aestheticWeight: Float

    static func from(aesVotes: Int, techVotes: Int) -> Taste {
        let total = aesVotes + techVotes
        if total == 0 { return normal }
        let lean = Float(aesVotes - techVotes) / Float(total + 6)
        return Taste(aestheticWeight: min(0.6, max(0.15, defaultWeight + 0.3 * lean)))
    }
}

struct RankedPhoto {
    let photo: PhotoInfo
    /// 0..100
    let score: Int
    let keep: Bool
    let best: Bool
    let tags: [String]
    /// 技术分 0..1（清晰、曝光、人脸）
    let tech: Float
}

struct RankedGroup: Identifiable {
    /// 组里最早一张的 id
    let id: String
    let members: [RankedPhoto]
    let duplicate: Bool
    /// 组里每张都和推荐那张“几乎一样”（连拍、重复保存）：可以放心一键清理
    let nearIdentical: Bool

    var startTime: Int64 { members.map { $0.photo.timeMs }.min() ?? 0 }
    var suggestedDelete: [RankedPhoto] { members.filter { !$0.keep } }
    static let closeTag = "差不多"
}

enum Ranker {
    static func exposureScore(_ f: ImageFeatures) -> Float {
        var e: Float = 1
        e -= min(1, f.brightFrac * 3) * 0.6
        e -= min(1, f.darkFrac * 2) * 0.4
        if f.mean < 50 { e -= (50 - f.mean) / 50 * 0.4 }
        if f.mean > 215 { e -= (f.mean - 215) / 40 * 0.4 }
        return min(1, max(0, e))
    }

    private static func faceScore(_ fi: FaceInfo?, _ maxFaces: Int) -> Float {
        guard let fi else { return 0.5 }
        let countRatio: Float = maxFaces == 0 ? 1 : Float(fi.count) / Float(maxFaces)
        let eyes: Float = fi.minEyesOpen < 0 ? 0.5 : fi.minEyesOpen
        let q: Float = fi.quality < 0 ? 0.3 : fi.quality
        return 0.4 * countRatio + 0.45 * eyes + 0.15 * q
    }

    private static let sharpScale: Float = 1.5
    /// 指纹差异 ≤ 这个值算“几乎同一个取景”，只有这时比细节多少才等于比谁更清楚
    static let sameFramingBits = 10
    /// 美感分差不到这么多就算“差不多”（仍推荐分高的那张，只提醒）
    static let aestheticMargin: Float = 0.15

    static func sameFraming(_ a: PhotoInfo, _ b: PhotoInfo) -> Bool {
        Similarity.hashDistance(a.features, b.features) <= sameFramingBits
    }

    static func rank(_ group: [PhotoInfo], keepCount: Int, taste: Taste = .normal) -> RankedGroup {
        let members = group.sorted { $0.timeMs < $1.timeMs }
        let n = members.count
        let pixels = members.map { max(1, Int64($0.width) * Int64($0.height)) }
        let maxPixels = pixels.max()!
        let resSpread = Float(maxPixels) / Float(pixels.min()!)
        let maxSize = max(1, members.map { $0.size }.max()!)
        let maxFaces = members.map { $0.faces?.count ?? 0 }.max()!

        let sharpLog = members.map { log(1 + max(0, $0.features.sharpness)) }
        let relSharp: [Float] = (0..<n).map { i in
            var top = sharpLog[i]
            for j in 0..<n where j != i && sameFraming(members[i], members[j]) { top = max(top, sharpLog[j]) }
            return min(1, max(0, (sharpLog[i] - top) / sharpScale + 1))
        }
        let absSharp: [Float] = members.map {
            switch BadShotRules.blurLevel($0.features, 1) {
            case 2: return 0.15
            case 1: return 0.6
            default: return 1
            }
        }
        let tech: [Float] = (0..<n).map { i in
            let m = members[i]
            let sharpN = relSharp[i] * absSharp[i]
            let expo = exposureScore(m.features)
            return maxFaces > 0 ? 0.55 * sharpN + 0.15 * expo + 0.30 * faceScore(m.faces, maxFaces) : 0.80 * sharpN + 0.20 * expo
        }
        let known = members.compactMap { $0.aestheticKnown ? $0.aesthetic : nil }
        let aesKnown = known.count >= 2
        let avg = known.isEmpty ? 0 : known.reduce(0, +) / Float(known.count)
        let aes: [Float] = members.map { $0.aestheticKnown ? $0.aesthetic! : avg }
        let aesMax = aesKnown ? aes.max()! : 0

        let scores: [Float] = (0..<n).map { i in
            let m = members[i]
            let w = taste.aestheticWeight
            let quality = aesKnown ? (1 - w) * tech[i] + w * min(1, max(0, aes[i] - aesMax + 1)) : tech[i]
            let res = Float(pixels[i]) / Float(maxPixels)
            let sizeR = Float(m.size) / Float(maxSize)
            let s = resSpread > 1.3 ? 0.6 * quality + 0.4 * res : 0.92 * quality + 0.05 * res + 0.03 * sizeR
            return s - Float(i) * 0.0001
        }

        let order = (0..<n).sorted { scores[$0] > scores[$1] }
        var keepIdx = Set(order.prefix(max(1, keepCount)))
        for (i, m) in members.enumerated() where m.favorite { keepIdx.insert(i) }
        let bestIdx = order[0]
        let best = members[bestIdx]
        let dupOfBest = (0..<n).map { i in i != bestIdx && Similarity.isDuplicate(members[i], best) && sameQuality(members[i], best) }
        let duplicate = (0..<n).allSatisfy { $0 == bestIdx || dupOfBest[$0] }
        let close = (0..<n).map { i in
            !keepIdx.contains(i) && !dupOfBest[i] && !sameFraming(members[i], best) &&
                absSharp[i] == 1 && absSharp[bestIdx] == 1 && tech[i] >= tech[bestIdx] - 0.12 &&
                (!aesKnown || abs(aes[i] - aes[bestIdx]) < aestheticMargin)
        }

        let ranked: [RankedPhoto] = members.enumerated().map { i, m in
            var tags: [String] = []
            let f = m.features
            if i == bestIdx { tags.append(duplicate ? "保留原图" : "最佳") }
            if m.favorite { tags.append("已收藏") }
            if i != bestIdx {
                if dupOfBest[i] {
                    tags.append(Float(pixels[i]) < Float(pixels[bestIdx]) * 0.77 ? "压缩副本" : "重复")
                } else if close[i] {
                    tags.append(RankedGroup.closeTag)
                } else {
                    if absSharp[i] <= 0.15 { tags.append("模糊") }
                    else if absSharp[i] < 1 { tags.append("可能模糊") }
                    else if relSharp[i] < 0.73 { tags.append("更模糊") }
                    if let fi = m.faces, fi.count > 0, fi.minEyesOpen >= 0, fi.minEyesOpen <= 0.35 { tags.append("有人闭眼") }
                    if let fi = m.faces, maxFaces > 0, fi.count < maxFaces { tags.append("少了人") }
                    if f.brightFrac > 0.15 { tags.append("过曝") }
                    if f.mean < 45 { tags.append("偏暗") }
                    if Float(pixels[i]) < Float(pixels[bestIdx]) * 0.77 { tags.append("分辨率低") }
                    if aesKnown && !sameFraming(m, best) && aes[bestIdx] - aes[i] >= aestheticMargin { tags.append("没那张好看") }
                    if tags.isEmpty { tags.append("相似") }
                }
            }
            return RankedPhoto(photo: m, score: Int((min(1, max(0, scores[i])) * 100).rounded()),
                               keep: keepIdx.contains(i), best: i == bestIdx, tags: tags, tech: tech[i])
        }
        let nearIdentical = (0..<n).allSatisfy { $0 == bestIdx || dupOfBest[$0] || sameFraming(members[$0], best) }
        return RankedGroup(id: members[0].id, members: ranked, duplicate: duplicate, nearIdentical: nearIdentical)
    }

    /// 同一分辨率下清晰度也几乎一样 → 真的是同一个文件的拷贝，而不是连拍里的另一张
    private static func sameQuality(_ a: PhotoInfo, _ b: PhotoInfo) -> Bool {
        let pa = Int64(a.width) * Int64(a.height), pb = Int64(b.width) * Int64(b.height)
        if pa > 0 && pb > 0 && Float(max(pa, pb)) / Float(min(pa, pb)) > 1.3 { return true }
        let d = abs(log(1 + max(0, a.features.sharpness)) - log(1 + max(0, b.features.sharpness)))
        if let fa = a.faces, let fb = b.faces, fa.count != fb.count || abs(fa.minEyesOpen - fb.minEyesOpen) > 0.3 { return false }
        return d < 0.12
    }
}

enum BadReason: String {
    case blurry = "模糊"
    /// 有点像糊了但拿不准：列出来，但不默认勾选
    case maybeBlurry = "可能模糊"
    case dark = "太暗"
    case overexposed = "过曝"
    case plain = "误拍"
}

enum BadShotRules {
    /// 模糊阈值（主体清晰度，缩到 512px 后算），低/中/高三档。按真实手机照片校准：真正拍废的都在 35 以下
    static let blurWithSubject: [Float] = [20, 30, 45]
    static let blurUniform: [Float] = [30, 45, 70]
    static let subjectRatio: Float = 10
    static let motionAnisotropy: Float = 0.05

    /// 纯色/太暗的照片不参与相似分组，直接归到废片里
    static func excludedFromGrouping(_ f: ImageFeatures) -> Bool { f.std < 9 || (f.mean < 30 && f.p95 < 70) }

    static func hasBokeh(_ f: ImageFeatures) -> Bool { f.focusRatio >= subjectRatio }

    /// 0 = 不模糊，1 = 可能模糊（不默认勾选），2 = 明显模糊（默认勾选）
    static func blurLevel(_ f: ImageFeatures, _ sensitivity: Int) -> Int {
        if f.peakSharpness < 0 { return 0 }
        let lv = min(2, max(0, sensitivity))
        let threshold = hasBokeh(f) ? blurWithSubject[lv] : blurUniform[lv]
        if f.peakSharpness < threshold * 0.6 { return 2 }
        if f.peakSharpness < threshold { return 1 }
        // 手抖只做提示：百叶窗、大桥、公路这种线条多的照片也会“某个方向没细节”
        if f.anisotropy < motionAnisotropy && f.peakSharpness < blurUniform[lv] * 3 { return 1 }
        return 0
    }

    static func reasons(_ f: ImageFeatures, _ sensitivity: Int) -> [BadReason] {
        var out: [BadReason] = []
        let plain = f.std < 9
        if plain { out.append(.plain) }
        if f.mean < 30 && f.p95 < 70 { out.append(.dark) }
        if f.mean > 232 && f.brightFrac > 0.55 { out.append(.overexposed) }
        if !plain && out.isEmpty {
            switch blurLevel(f, sensitivity) {
            case 2: out.append(.blurry)
            case 1: out.append(.maybeBlurry)
            default: break
            }
        }
        return out
    }
}
