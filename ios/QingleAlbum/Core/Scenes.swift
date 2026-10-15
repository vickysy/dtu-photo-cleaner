import Foundation

/**
 场景精选：一个地方连续拍了几十张、构图各不相同，只想留几张精华。
 1. 按拍摄时间切场景（隔 20 分钟没拍，或者换了 1.5 公里以外的地方，就算新场景），够多张的才处理；
 2. 每张打分：美感为主，曝光、有没有人闭眼为辅，拍糊的垫底；
 3. 建议删的只有两种：“相似照片”里建议删的（同一个东西拍了好几张、没那张好的，和那一页留的是同一张）、
    明显拍糊的；收藏的、用户点过保留的不删。拍的东西不一样、又清楚的都留着；
 4. 精华兼顾“好”和“不重复”（MMR），挑出来的几张覆盖不同画面；不从“几乎一样”里建议删的和拍糊的里挑
    （“角度不同”的照样能当精华：同一个地方换着角度拍的，还是留几张）。
 1、2、4 的挑法和安卓版 core/Scenes.kt 一样；3 是 iPhone 1.0.1 先改的：原来除了精华全建议删，
 朋友反馈在博物馆拍的每件展品都不一样，也只留了 5 张。
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
    /// 建议删的（按时间）：拍重了没那张好的、明显拍糊的
    let suggested: [String]
    /// 重新挑精华（改“精华几张”）时要用同样的条件
    let taste: Taste
    let similarDrop: [String: Bool]
    let reviewed: Set<String>

    /// “精华几张”最多能调到几：能当精华的照片有几张（收藏的不算）
    var maxHighlights: Int {
        members.filter { !$0.favorite && SceneDetector.canHighlight($0, similarDrop, reviewed) }.count
    }

    /// 改了精华张数，按同样的条件重算
    func rebuilt(keep: Int) -> PhotoScene {
        SceneDetector.build(members, keep: keep, taste: taste, similarDrop: similarDrop, reviewed: reviewed)
    }

    /// 留下的：除了建议删的都留
    var keepIds: Set<String> { Set(members.map { $0.id }).subtracting(suggested) }

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

    /// 精华：十几张标 3 张，二三十张标 4 张，更多标 5 张
    static func defaultKeep(_ n: Int) -> Int { n < 20 ? 3 : n < 40 ? 4 : 5 }

    /// [similarDrop]：“相似照片”里建议删的 → 是不是“几乎一样”的组；[reviewed]：用户看过、选了保留的
    static func build(_ members: [PhotoInfo], keep: Int? = nil, taste: Taste = .normal,
                      similarDrop: [String: Bool] = [:], reviewed: Set<String> = []) -> PhotoScene {
        let sorted = members.sorted { $0.timeMs < $1.timeMs }
        let ids = Set(sorted.map { $0.id })
        let drop = Set(sorted.filter { isDrop($0, similarDrop, reviewed) }.map { $0.id })
        // 默认留几张按不算收藏的张数定
        let k = keep ?? defaultKeep(sorted.filter { !$0.favorite }.count)
        let q = qualities(sorted, taste)
        // 能当精华的都不够（比如全糊）才从建议删的里挑
        let pool = sorted.filter { canHighlight($0, similarDrop, reviewed) }
        let picked = pick(pool.contains { !$0.favorite } ? pool : sorted, k, q).map { $0.id }
        let highlights = Set(picked)
        return PhotoScene(id: sorted[0].id, members: sorted, highlights: picked, keepCount: k,
                          favorites: sorted.filter { $0.favorite }.map { $0.id },
                          suggested: sorted.map { $0.id }.filter { drop.contains($0) && !highlights.contains($0) },
                          taste: taste, similarDrop: similarDrop.filter { ids.contains($0.key) }, reviewed: reviewed.intersection(ids))
    }

    private static func blurry(_ p: PhotoInfo) -> Bool { BadShotRules.blurLevel(p.features, 1) == 2 }

    /// 建议删：“相似照片”里建议删的、明显拍糊的；收藏的、用户点过保留的不删
    static func isDrop(_ p: PhotoInfo, _ similarDrop: [String: Bool], _ reviewed: Set<String>) -> Bool {
        !p.favorite && !reviewed.contains(p.id) && (similarDrop[p.id] != nil || blurry(p))
    }

    /// 能当精华：不建议删的，或者只是“角度不同”里没被推荐的（同一个地方换着角度拍的，还是留几张）。
    /// “几乎一样”里建议删的、拍糊的不当精华，和“相似照片”留同一张
    static func canHighlight(_ p: PhotoInfo, _ similarDrop: [String: Bool], _ reviewed: Set<String>) -> Bool {
        !isDrop(p, similarDrop, reviewed) || (similarDrop[p.id] == false && !blurry(p))
    }

    /// 每张的质量分（场景内比较用）
    static func qualities(_ members: [PhotoInfo], _ taste: Taste) -> [String: Float] {
        let known = members.compactMap { $0.aestheticKnown ? $0.aesthetic : nil }
        let mean = known.isEmpty ? 0 : known.reduce(0, +) / Float(known.count)
        let std: Float = known.count < 2 ? 0 : sqrt(known.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Float(known.count))
        return Dictionary(uniqueKeysWithValues: members.map { ($0.id, quality($0, mean, std, taste)) })
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
    static func pick(_ members: [PhotoInfo], _ k: Int, _ q: [String: Float]) -> [PhotoInfo] {
        if members.isEmpty { return [] }
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
