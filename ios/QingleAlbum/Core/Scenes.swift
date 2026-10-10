import Foundation

/**
 场景精选：一个地方连续拍了几十张、构图各不相同，只想留几张精华。
 1. 按拍摄时间切场景（隔 20 分钟没拍，或者换了 1.5 公里以外的地方，就算新场景），够多张的才处理；
 2. 每张打分：美感为主，曝光、有没有人闭眼为辅，拍糊的垫底；
 3. 挑精华兼顾“好”和“不重复”（MMR），挑出来的几张覆盖不同画面。
 和安卓版 core/Scenes.kt 一样。
 */
struct PhotoScene: Identifiable {
    let id: String
    /// 按时间排序
    let members: [PhotoInfo]
    /// 推荐保留的“精华”id（按推荐顺序，正好 keepCount 张，不含收藏的）
    let highlights: [String]
    let keepCount: Int
    /// 收藏的照片：一律保留，但不算在“留几张”里（单独标“已收藏”）
    let favorites: [String]

    /// 留下的：精华 + 收藏
    var keepIds: Set<String> { Set(highlights).union(favorites) }

    var startTime: Int64 { members.first?.timeMs ?? 0 }
    var endTime: Int64 { members.last?.timeMs ?? 0 }
}

enum SceneDetector {
    static let gapMs: Int64 = 20 * 60_000
    static let minPhotos = 10
    static let moveMeters = 1500.0

    static func distanceMeters(_ a: PhotoInfo, _ b: PhotoInfo) -> Double {
        guard let la = a.lat, let ln = a.lng, let lb = b.lat, let lnb = b.lng else { return 0 }
        let r = 6_371_000.0
        let dLat = (lb - la) * .pi / 180, dLng = (lnb - ln) * .pi / 180
        let x = pow(sin(dLat / 2), 2) + cos(la * .pi / 180) * cos(lb * .pi / 180) * pow(sin(dLng / 2), 2)
        return 2 * r * asin(sqrt(min(1, max(0, x))))
    }

    static func segment(_ photos: [PhotoInfo], gapMs: Int64 = gapMs, minPhotos: Int = minPhotos) -> [[PhotoInfo]] {
        let sorted = photos.sorted { $0.timeMs < $1.timeMs }
        var out: [[PhotoInfo]] = []
        var cur: [PhotoInfo] = []
        for p in sorted {
            if let last = cur.last, p.timeMs - last.timeMs > gapMs || distanceMeters(last, p) > moveMeters {
                if cur.count >= minPhotos { out.append(cur) }
                cur = []
            }
            cur.append(p)
        }
        if cur.count >= minPhotos { out.append(cur) }
        return out
    }

    /// 十几张留 3 张，二三十张留 4 张，更多留 5 张
    static func defaultKeep(_ n: Int) -> Int { n < 20 ? 3 : n < 40 ? 4 : 5 }

    static func build(_ members: [PhotoInfo], keep: Int? = nil, taste: Taste = .normal) -> PhotoScene {
        let sorted = members.sorted { $0.timeMs < $1.timeMs }
        // 默认留几张按不算收藏的张数定
        let k = keep ?? defaultKeep(sorted.filter { !$0.favorite }.count)
        return PhotoScene(id: sorted[0].id, members: sorted, highlights: pick(sorted, k, taste).map { $0.id }, keepCount: k,
                          favorites: sorted.filter { $0.favorite }.map { $0.id })
    }

    static func quality(_ p: PhotoInfo, _ aesMean: Float, _ aesStd: Float, _ taste: Taste) -> Float {
        let aesN: Float = p.aestheticKnown && aesStd > 0 ? min(1, max(0, 0.5 + 0.22 * (p.aesthetic! - aesMean) / aesStd)) : 0.5
        let expo = Ranker.exposureScore(p.features)
        let face: Float
        if let fi = p.faces, fi.count > 0 {
            face = fi.minEyesOpen >= 0 && fi.minEyesOpen <= 0.35 ? 0.1 : 0.7 + 0.3 * max(0, fi.quality)
        } else {
            face = 0.6
        }
        let aesW = min(0.8, max(0.35, 0.62 * taste.aestheticWeight / Taste.defaultWeight))
        let rest = 1 - aesW
        var q = aesW * aesN + rest * 0.6 * expo + rest * 0.4 * face
        switch BadShotRules.blurLevel(p.features, 1) {
        case 2: q *= 0.15
        case 1: q *= 0.5
        default: break
        }
        return q
    }

    /// 两张有多像（0..1）：画面结构、颜色、拍摄时间都算
    static func similarity(_ a: PhotoInfo, _ b: PhotoInfo, _ spanMs: Int64) -> Float {
        let h = Similarity.hashDistance(a.features, b.features)
        let simHash = min(1, max(0, 1 - Float(h) / 64))
        let simColor = min(1, max(0, 1 - Similarity.colorDistance(a.features.color, b.features.color) / 0.2))
        let tau = max(180_000, Float(spanMs) * 0.1)
        let simTime = exp(-Float(abs(a.timeMs - b.timeMs)) / tau)
        return 0.35 * simHash + 0.35 * simColor + 0.3 * simTime
    }

    private static let lambda: Float = 0.8

    /// 挑 k 张精华：每次选“质量高、又和已留下的不像”的那张。
    /// 收藏的照片本来就留着，不算在 k 张里，但挑精华时会避开和收藏的重复
    static func pick(_ members: [PhotoInfo], _ k: Int, _ taste: Taste) -> [PhotoInfo] {
        if members.isEmpty { return [] }
        let known = members.compactMap { $0.aestheticKnown ? $0.aesthetic : nil }
        let mean = known.isEmpty ? 0 : known.reduce(0, +) / Float(known.count)
        let std: Float = known.count < 2 ? 0 : sqrt(known.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Float(known.count))
        let q = Dictionary(uniqueKeysWithValues: members.map { ($0.id, quality($0, mean, std, taste)) })
        let span = (members.map { $0.timeMs }.max() ?? 0) - (members.map { $0.timeMs }.min() ?? 0)
        let favorites = members.filter { $0.favorite }
        let pool = members.filter { !$0.favorite }
        var picked: [PhotoInfo] = []
        while picked.count < min(k, pool.count) {
            var best: PhotoInfo?
            var bestScore = -Float.infinity
            for m in pool where !picked.contains(where: { $0.id == m.id }) {
                let redundancy = (picked + favorites).map { similarity(m, $0, span) }.max() ?? 0
                let s = q[m.id]! - lambda * redundancy
                if s > bestScore { bestScore = s; best = m }
            }
            guard let b = best else { break }
            picked.append(b)
        }
        return picked
    }
}
