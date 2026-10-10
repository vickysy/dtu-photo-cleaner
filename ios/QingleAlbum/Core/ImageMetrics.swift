import Foundation

/**
 从一张缩小后的图（最长边 `ImageMetrics.analysisSize`）算出的特征。
 和安卓版 core/ImageMetrics.kt 是同一套算法、同一套阈值（在真实手机照片上校准过）。
 */
struct ImageFeatures: Codable, Equatable {
    /// 横向差值哈希（9×8 灰度），判断“是不是同一个画面”
    var dHash: UInt64
    /// 纵向差值哈希（8×9 灰度）
    var vHash: UInt64
    /// 4×4 网格 × RGB 平均色，共 48 字节
    var color: [UInt8]
    /// 清晰度：4 个方向里最弱方向的能量（只看最清晰的区块）
    var sharpness: Float
    var globalSharpness: Float
    /// 平均亮度 0..255
    var mean: Float
    /// 亮度标准差，接近 0 说明几乎是纯色（误拍、口袋里的黑屏）
    var std: Float
    /// 亮度 < 16 的像素占比
    var darkFrac: Float
    /// 亮度 > 250 的像素占比
    var brightFrac: Float
    var p95: Float
    /// 主体清晰度：最清楚的 3 个区块的细节能量（背景虚化的照片只要主体清楚就高）；-1 = 图太小，不判断模糊
    var peakSharpness: Float
    /// 最清楚的区块比全图中位数高多少倍，≥10 基本是“主体清楚 + 背景虚化”
    var focusRatio: Float
    /// 最清楚那些区块里最弱方向 / 最强方向，手抖时接近 0
    var anisotropy: Float

    func withoutSharpness() -> ImageFeatures {
        var f = self
        f.peakSharpness = -1
        f.focusRatio = 0
        f.anisotropy = 1
        return f
    }
}

enum ImageMetrics {
    static let analysisSize = 512
    private static let tiles = 8
    private static let topTiles = 6
    private static let peakTiles = 3
    /// 相邻格子亮度差不到这个值就当作一样亮，避免天空、白墙因噪点乱翻位
    private static let hashEps: Float = 1.5

    /// `rgba` 为 w*h*4 字节（R,G,B,A）
    static func compute(rgba: [UInt8], w: Int, h: Int) -> ImageFeatures {
        precondition(w >= 9 && h >= 9 && rgba.count >= w * h * 4)
        let n = w * h
        var gray = [Int32](repeating: 0, count: n)
        var hist = [Int](repeating: 0, count: 256)
        rgba.withUnsafeBufferPointer { px in
            for i in 0..<n {
                let r = Int(px[i * 4]), g = Int(px[i * 4 + 1]), b = Int(px[i * 4 + 2])
                let y = (r * 299 + g * 587 + b * 114) / 1000
                gray[i] = Int32(y)
                hist[y] += 1
            }
        }
        var sum = 0.0, sumSq = 0.0
        for v in 0..<256 {
            sum += Double(v) * Double(hist[v])
            sumSq += Double(v) * Double(v) * Double(hist[v])
        }
        let mean = sum / Double(n)
        let std = sqrt(max(0, sumSq / Double(n) - mean * mean))
        let dark = hist[0..<16].reduce(0, +)
        let bright = hist[251..<256].reduce(0, +)
        let p5 = percentile(hist, n, 0.05)
        let p95 = percentile(hist, n, 0.95)

        let s98 = areaResize(gray, w, h, 9, 8)
        var dHash: UInt64 = 0
        for y in 0..<8 { for x in 0..<8 {
            dHash <<= 1
            if s98[y * 9 + x] < s98[y * 9 + x + 1] - hashEps { dHash |= 1 }
        } }
        let s89 = areaResize(gray, w, h, 8, 9)
        var vHash: UInt64 = 0
        for y in 0..<8 { for x in 0..<8 {
            vHash <<= 1
            if s89[y * 8 + x] < s89[(y + 1) * 8 + x] - hashEps { vHash |= 1 }
        } }

        let color = colorGrid(rgba, w, h)
        let sh = laplacian(gray, w, h)
        // 偏暗/发灰的照片细节能量天然低，按明暗范围补偿（最多 12 倍，从不往下压）
        let range = Float(max(p95 - p5, 40))
        let boost = min(12, max(1, (180 / range) * (180 / range)))
        return ImageFeatures(
            dHash: dHash, vHash: vHash, color: color,
            sharpness: sh.directional, globalSharpness: sh.iso,
            mean: Float(mean), std: Float(std),
            darkFrac: Float(dark) / Float(n), brightFrac: Float(bright) / Float(n), p95: Float(p95),
            peakSharpness: sh.peak * boost, focusRatio: sh.peak / (sh.median + 1), anisotropy: sh.anisotropy
        )
    }

    private static func percentile(_ hist: [Int], _ n: Int, _ q: Double) -> Int {
        let target = Int(Double(n) * q)
        var acc = 0
        for v in 0..<256 {
            acc += hist[v]
            if acc >= target { return v }
        }
        return 255
    }

    static func areaResize(_ gray: [Int32], _ w: Int, _ h: Int, _ tw: Int, _ th: Int) -> [Float] {
        var out = [Float](repeating: 0, count: tw * th)
        for ty in 0..<th {
            let y0 = ty * h / th
            let y1 = max(y0 + 1, (ty + 1) * h / th)
            for tx in 0..<tw {
                let x0 = tx * w / tw
                let x1 = max(x0 + 1, (tx + 1) * w / tw)
                var s = 0
                for y in y0..<y1 {
                    let row = y * w
                    for x in x0..<x1 { s += Int(gray[row + x]) }
                }
                out[ty * tw + tx] = Float(s) / Float((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    private static func colorGrid(_ rgba: [UInt8], _ w: Int, _ h: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 48)
        for ty in 0..<4 {
            let y0 = ty * h / 4
            let y1 = max(y0 + 1, (ty + 1) * h / 4)
            for tx in 0..<4 {
                let x0 = tx * w / 4
                let x1 = max(x0 + 1, (tx + 1) * w / 4)
                var r = 0, g = 0, b = 0, cnt = 0
                var y = y0
                while y < y1 {
                    var x = x0
                    while x < x1 {
                        let i = (y * w + x) * 4
                        r += Int(rgba[i]); g += Int(rgba[i + 1]); b += Int(rgba[i + 2])
                        cnt += 1
                        x += 2
                    }
                    y += 2
                }
                let k = (ty * 4 + tx) * 3
                out[k] = UInt8(r / cnt); out[k + 1] = UInt8(g / cnt); out[k + 2] = UInt8(b / cnt)
            }
        }
        return out
    }

    private struct Sharpness {
        let directional: Float, iso: Float, peak: Float, median: Float, anisotropy: Float
    }

    /// 4 个方向（横、竖、两条对角线）的二阶差分能量，按 8×8 区块统计（说明见安卓版）
    private static func laplacian(_ gray: [Int32], _ w: Int, _ h: Int) -> Sharpness {
        let nt = tiles * tiles
        var sum = [[Double]](repeating: [Double](repeating: 0, count: nt), count: 5)
        var sq = [[Double]](repeating: [Double](repeating: 0, count: nt), count: 5)
        var cnt = [Int](repeating: 0, count: nt)
        gray.withUnsafeBufferPointer { g in
            for y in 1..<(h - 1) {
                let row = y * w
                let ty = min(tiles - 1, y * tiles / h)
                for x in 1..<(w - 1) {
                    let i = row + x
                    let c2 = 2 * g[i]
                    let dxx = Double(c2 - g[i - 1] - g[i + 1])
                    let dyy = Double(c2 - g[i - w] - g[i + w])
                    let dd1 = Double(c2 - g[i - w - 1] - g[i + w + 1])
                    let dd2 = Double(c2 - g[i - w + 1] - g[i + w - 1])
                    let lap = dxx + dyy
                    let t = ty * tiles + min(tiles - 1, x * tiles / w)
                    sum[0][t] += dxx; sq[0][t] += dxx * dxx
                    sum[1][t] += dyy; sq[1][t] += dyy * dyy
                    sum[2][t] += dd1; sq[2][t] += dd1 * dd1
                    sum[3][t] += dd2; sq[3][t] += dd2 * dd2
                    sum[4][t] += lap; sq[4][t] += lap * lap
                    cnt[t] += 1
                }
            }
        }
        let vars: [[Float]] = (0..<5).map { k in
            (0..<nt).map { t in
                let c = cnt[t]
                if c == 0 { return 0 }
                let m = sum[k][t] / Double(c)
                return Float(sq[k][t] / Double(c) - m * m)
            }
        }
        func topMean(_ a: [Float], _ k: Int) -> Float {
            let s = a.sorted(by: >)
            return s.prefix(k).reduce(0, +) / Float(k)
        }
        let directional: Float = (0..<4).map { topMean(vars[$0], topTiles) }.min()!
        var tileIso = [Float](repeating: 0, count: nt)
        for t in 0..<nt {
            let a: Float = vars[0][t] + vars[1][t]
            let b: Float = vars[2][t] + vars[3][t]
            tileIso[t] = (a + b) / 4
        }
        let order = (0..<nt).sorted { tileIso[$0] > tileIso[$1] }
        let peak = order.prefix(peakTiles).map { tileIso[$0] }.reduce(0, +) / Float(peakTiles)
        let sortedIso = tileIso.sorted()
        let median = (sortedIso[nt / 2 - 1] + sortedIso[nt / 2]) / 2
        var dirSum = [Float](repeating: 0, count: 4)
        for i in 0..<topTiles { for k in 0..<4 { dirSum[k] += vars[k][order[i]] } }
        let anisotropy = dirSum.min()! / max(1e-6, dirSum.max()!)
        return Sharpness(directional: directional, iso: topMean(vars[4], topTiles), peak: peak, median: median, anisotropy: anisotropy)
    }
}
