// 核心规则的自动测试：不用模拟器，直接把 QingleAlbum/Core/*.swift 和这个文件一起编译运行。
// 跑法：sh Tests/run_core_tests.sh
import Foundation

var failures = 0
func check(_ ok: Bool, _ what: String, file: String = #file, line: Int = #line) {
    if ok { print("  ✓ \(what)") } else { failures += 1; print("  ✗ \(what)  (第 \(line) 行)") }
}

// ---------- 造数据 ----------
var rng = SeededRNG(seed: 42)
struct SeededRNG: RandomNumberGenerator {
    var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 { s &+= 0x9E3779B97F4A7C15; var z = s; z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9; z = (z ^ (z >> 27)) &* 0x94D049BB133111EB; return z ^ (z >> 31) }
}
func feat(sharp: Float = 5000) -> ImageFeatures {
    ImageFeatures(dHash: UInt64.random(in: 0...UInt64.max, using: &rng), vHash: UInt64.random(in: 0...UInt64.max, using: &rng),
                  color: (0..<48).map { _ in UInt8.random(in: 0...255, using: &rng) },
                  sharpness: sharp, globalSharpness: sharp, mean: 120, std: 50, darkFrac: 0, brightFrac: 0, p95: 230,
                  peakSharpness: sharp, focusRatio: 1, anisotropy: 1)
}
/// 同一个画面：翻几位指纹、颜色差一点点
func near(_ f: ImageFeatures, bits: Int) -> ImageFeatures {
    var g = f
    for i in 0..<bits { g.dHash ^= (1 << UInt64(i * 5 % 64)) }
    g.color = f.color.map { UInt8(min(255, Int($0) + 2)) }
    return g
}
func photo(_ id: String, sec: Int, _ f: ImageFeatures, aes: Float? = 6.5, fav: Bool = false, w: Int = 4032, h: Int = 3024) -> PhotoInfo {
    PhotoInfo(id: id, timeMs: Int64(sec) * 1000, width: w, height: h, size: Int64(w * h / 4), favorite: fav,
              features: f, faces: nil, aesthetic: aes, lat: 31.23, lng: 121.47)
}
/// 和 Library 一样：先分相似组，组里建议删的交给场景
func similarGroups(_ photos: [PhotoInfo], reviewed: Set<String> = []) -> [RankedGroup] {
    Grouper.group(photos, GroupParams()).map { Ranker.rank($0, keepCount: 1) }
        .filter { g in g.members.contains { !reviewed.contains($0.photo.id) } && !g.suggestedDelete.isEmpty }
}
func similarDrop(_ photos: [PhotoInfo], reviewed: Set<String> = []) -> [String: Bool] {
    var out: [String: Bool] = [:]
    for g in similarGroups(photos, reviewed: reviewed) { for d in g.suggestedDelete { out[d.photo.id] = g.nearIdentical } }
    return out
}

// ---------- 1. 场景精选 ----------
print("场景精选")
do {
    // 博物馆：25 件不同展品（每 2 分钟一张），第 5 件 20 秒内拍了 3 张，2 张拍糊，第 7 件是收藏
    var museum: [PhotoInfo] = []
    var t = 0
    for i in 0..<25 {
        let f = feat()
        museum.append(photo("m\(i)", sec: t, f, aes: 5 + Float(i % 7) * 0.5, fav: i == 7)); t += 120
        if i == 5 {
            museum.append(photo("m5b", sec: t - 110, near(f, bits: 4), aes: 5.0))
            museum.append(photo("m5c", sec: t - 100, near(f, bits: 8), aes: 4.8))
        }
    }
    museum.append(photo("blur1", sec: t, feat(sharp: 1), aes: 4)); t += 60
    museum.append(photo("blur2", sec: t, feat(sharp: 1), aes: 4))
    let drop = similarDrop(museum)
    let sc = SceneDetector.build(museum, similarDrop: drop)
    check(Set(sc.suggested) == Set(drop.keys).union(["blur1", "blur2"]), "建议删的 = 相似照片里建议删的 + 拍糊的：\(sc.suggested.sorted())")
    check(sc.suggested.count == 4, "29 张里只建议删 4 张（同一件拍的 2 张 + 2 张糊的）")
    check(!sc.suggested.contains("m7"), "收藏的不建议删")
    check(Set(sc.highlights).isDisjoint(with: sc.suggested), "精华不会被建议删")
    check(Set(sc.highlights).isDisjoint(with: drop.keys), "精华不挑相似照片里建议删的那几张（两页留的是同一张）")
    check(sc.keepIds.count == museum.count - 4, "不一样又清楚的都留着")

    // 用户在相似照片 / 废片里点过保留的，场景里也不再建议删
    let reviewed: Set<String> = ["m5b", "blur1"]
    let sc2 = SceneDetector.build(museum, similarDrop: similarDrop(museum, reviewed: reviewed), reviewed: reviewed)
    check(!sc2.suggested.contains("m5b") && !sc2.suggested.contains("blur1"), "点过保留的不再建议删：\(sc2.suggested.sorted())")

    // 改精华张数：条件不变（口味、相似、保留过的都带着）
    let more = sc.rebuilt(keep: 8)
    check(more.highlights.count == 8 && Set(more.suggested) == Set(sc.suggested), "改成 8 张精华，建议删的不变")
    let taste = Taste(aestheticWeight: 0.7)
    let tasted = SceneDetector.build(museum, taste: taste, similarDrop: drop)
    check(tasted.rebuilt(keep: tasted.keepCount).highlights == tasted.highlights, "重算精华用的是同一个口味")

    // 海边：同一个画面 15 张（几乎一样）→ 留最好的，其余建议删
    let base = feat()
    let beach = (0..<15).map { photo("b\($0)", sec: 10_000 + $0 * 30, near(base, bits: $0 % 4), aes: 6 + Float($0 % 3) * 0.3) }
    let bd = similarDrop(beach)
    let bs = SceneDetector.build(beach, similarDrop: bd)
    check(bs.suggested.count >= 10, "同一个画面拍了 15 张，大部分建议删（\(bs.suggested.count) 张）")
    check(Set(bs.highlights).isDisjoint(with: bs.suggested), "海边：精华不会被建议删")

    // 同一个地方换着角度拍了 15 张（“角度不同”）：照样留几张精华，相似照片推荐留的那张也留
    let spot = feat()
    let angles = (0..<15).map { k -> PhotoInfo in
        var f = near(spot, bits: 0)
        for i in 0..<(16 + k % 8) { f.dHash ^= (1 << UInt64((i * 7 + k) % 64)) }
        return photo("s\(k)", sec: 30_000 + k * 40, f, aes: 6 + Float(k % 5) * 0.4)
    }
    let ag = similarGroups(angles)
    let ad = similarDrop(angles)
    let asc = SceneDetector.build(angles, similarDrop: ad)
    let keeper = Set(ag.flatMap { $0.members.filter { $0.keep }.map { $0.photo.id } })
    check(!ag.isEmpty && ag.allSatisfy { !$0.nearIdentical }, "换角度拍的分成“角度不同”组")
    check(asc.highlights.count == asc.keepCount, "角度不同：照样挑满 \(asc.keepCount) 张精华")
    check(keeper.isDisjoint(with: asc.suggested), "相似照片推荐留的那张，场景里也不建议删")
    check(Set(asc.highlights).isDisjoint(with: asc.suggested), "角度不同：精华不勾")

    // 全都拍糊了：也要有精华，精华不勾
    let allBlur = (0..<10).map { photo("x\($0)", sec: 20_000 + $0 * 60, feat(sharp: 1), aes: 4) }
    let ab = SceneDetector.build(allBlur)
    check(!ab.highlights.isEmpty && Set(ab.highlights).isDisjoint(with: ab.suggested), "全糊的场景：还是挑精华，精华不勾")
}

// ---------- 2. 相似照片 ----------
print("相似照片")
do {
    let f = feat()
    let orig = photo("orig", sec: 0, f)
    let small = photo("small", sec: 86_400, f, w: 1440, h: 1080)  // 一天后存回来的缩小副本
    let groups = Grouper.group([orig, small], GroupParams()).map { Ranker.rank($0, keepCount: 1) }
    check(groups.count == 1, "原图和缩小副本隔了一天也分到一组")
    if let g = groups.first {
        check(g.suggestedDelete.map { $0.photo.id } == ["small"], "留原图、删缩小的那张")
        check(g.members.first { $0.photo.id == "small" }?.tags.contains("压缩副本") == true, "缩小的那张标“压缩副本”")
    }
    let a = photo("a", sec: 0, feat()), b = photo("b", sec: 5, feat())
    check(Grouper.group([a, b], GroupParams()).isEmpty, "两张完全不同的不分组")
    let fav = photo("fav", sec: 1, near(f, bits: 2), aes: 3, fav: true)
    let g2 = Grouper.group([orig, fav], GroupParams()).map { Ranker.rank($0, keepCount: 1) }
    check(g2.allSatisfy { !$0.suggestedDelete.contains { $0.photo.id == "fav" } }, "收藏的再差也不建议删")
}

// ---------- 3. 视频压缩门槛 ----------
print("视频压缩")
do {
    func worth(mbPerMin: Double, minutes: Double) -> Bool {
        let ms = Int64(minutes * 60_000)
        let size = Int64(mbPerMin * minutes * 1_000_000)
        let est = VideoRules.estimateSize(durationMs: ms, sourceBitrate: size * 8 * 1000 / ms)
        return VideoRules.worthCompressing(size: size, estimated: est)
    }
    check(worth(mbPerMin: 170, minutes: 3), "4K 30 帧（每分钟约 170 MB）值得压")
    check(worth(mbPerMin: 400, minutes: 1), "4K 60 帧（每分钟约 400 MB）值得压")
    check(!worth(mbPerMin: 40, minutes: 8), "1080p 30 帧（每分钟约 40 MB）再长也不压")
    check(!worth(mbPerMin: 90, minutes: 5), "1080p 60 帧（每分钟约 90 MB）不压")
    check(!worth(mbPerMin: 170, minutes: 0.1), "4K 但只有 6 秒（省不到 20 MB）不压")
}

print(failures == 0 ? "\n全部通过" : "\n有 \(failures) 项没通过")
exit(failures == 0 ? 0 : 1)
