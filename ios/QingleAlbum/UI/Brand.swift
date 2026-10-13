import SwiftUI
import UIKit

/// 配色：白底 + 品牌绿（书封面那个绿）+ 黄色点缀，和安卓版一样
extension Color {
    /// 按钮、进度条、勾选圈这些大块的绿
    static let brand = Color(hex: 0x2FC4A0)
    /// 文字、图标用的绿（深一点，白底上看得清）
    static let accentText = Color(light: 0x119C7E, dark: 0x3DD6B0)
    static let brandYellow = Color(hex: 0xF6CC45)
    static let yellowSoft = Color(light: 0xFFF5D6, dark: 0x3A3218)
    static let onYellow = Color(light: 0x8A6A00, dark: 0xF6CC45)
    static let page = Color(light: 0xFFFFFF, dark: 0x000000)
    static let card = Color(light: 0xFFFFFF, dark: 0x1C1C1E)
    static let cardOutline = Color(light: 0xECEEF0, dark: 0x1C1C1E)
    static let fillSoft = Color(uiColor: .tertiarySystemFill)
    static let danger = Color(hex: 0xFF3B30)

    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { t in
            let h = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((h >> 16) & 0xFF) / 255, green: CGFloat((h >> 8) & 0xFF) / 255, blue: CGFloat(h & 0xFF) / 255, alpha: 1)
        })
    }
}

/// 卡片：白底页面上是白卡片加一圈细边；深色模式是深灰卡片
struct CardBackground: ViewModifier {
    var radius: CGFloat = 20
    func body(content: Content) -> some View {
        content
            .background(Color.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Color.cardOutline, lineWidth: 1))
            // 整张卡都算能点：画上去的底色不算，卡片做成按钮时只有字和图标能点，点空白处没反应（iOS 27 上用手机右边的手指最明显）
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    func card(_ radius: CGFloat = 20) -> some View { modifier(CardBackground(radius: radius)) }
}

/// “轻了相册”的 logo：白底圆角方块 + 3×3 照片格，右上角那格被挑出来（实心绿 + 黄星）
struct BrandMark: View {
    var size: CGFloat = 36
    private static let tiles: [(CGFloat, CGFloat)] = [(22, 22), (45, 22), (22, 45), (45, 45), (68, 45), (22, 68), (45, 68), (68, 68)]
    private static let star: [CGFloat] = [77, 25.5, 78.9, 29.4, 83.2, 30, 80.1, 33, 80.8, 37.3, 77, 35.3, 73.2, 37.3, 73.9, 33, 70.8, 30, 75.1, 29.4]

    var body: some View {
        Canvas { ctx, sz in
            let k = sz.width / 108
            let bg = Path(roundedRect: CGRect(origin: .zero, size: sz), cornerRadius: 24 * k, style: .continuous)
            ctx.fill(bg, with: .color(.white))
            ctx.stroke(bg, with: .color(Color(hex: 0xECEEF0)), lineWidth: 1)
            let s: CGFloat = 0.84
            let ox = 54 - 55 * s, oy = 54 - 53 * s
            func x(_ v: CGFloat) -> CGFloat { (ox + v * s) * k }
            func y(_ v: CGFloat) -> CGFloat { (oy + v * s) * k }
            for (tx, ty) in Self.tiles {
                ctx.fill(Path(roundedRect: CGRect(x: x(tx), y: y(ty), width: 18 * s * k, height: 18 * s * k), cornerRadius: 4 * s * k),
                         with: .color(Color.brand.opacity(0.3)))
            }
            ctx.fill(Path(roundedRect: CGRect(x: x(66), y: y(20), width: 22 * s * k, height: 22 * s * k), cornerRadius: 5 * s * k),
                     with: .color(.brand))
            var star = Path()
            star.move(to: CGPoint(x: x(Self.star[0]), y: y(Self.star[1])))
            for i in stride(from: 2, to: Self.star.count, by: 2) { star.addLine(to: CGPoint(x: x(Self.star[i]), y: y(Self.star[i + 1]))) }
            star.closeSubpath()
            ctx.fill(star, with: .color(.brandYellow))
        }
        .frame(width: size, height: size)
    }
}

/// 浅黄底小标签，比如“先清这个”
struct YellowChip: View {
    let text: String
    var body: some View {
        Text(text).font(.caption.weight(.medium)).foregroundStyle(Color.onYellow)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Color.yellowSoft, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// 绿底白字的大按钮
struct PrimaryButton: View {
    let title: String
    var color: Color = .brand
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.headline).foregroundStyle(enabled ? .white : .secondary)
                .frame(maxWidth: .infinity).frame(height: 50)
                .background(enabled ? color : Color.fillSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain).disabled(!enabled)
    }
}

/// 胶囊小按钮
struct CapsuleButton: View {
    let title: String
    var color: Color = .brand
    var filled = true
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                .foregroundStyle(!enabled ? Color.secondary : filled ? Color.white : color)
                .padding(.horizontal, 16).frame(height: 36)
                .background(!enabled ? Color.fillSoft : filled ? color : color.opacity(0.12), in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain).disabled(!enabled)
    }
}

/// 勾选圈：选中 = 要删，绿底白勾
struct SelectCircle: View {
    let selected: Bool
    var size: CGFloat = 23
    var body: some View {
        ZStack {
            Circle().fill(selected ? Color.brand : Color.black.opacity(0.18))
            Circle().stroke(Color.white, lineWidth: 1.5)
            if selected { Image(systemName: "checkmark").font(.system(size: size * 0.5, weight: .bold)).foregroundStyle(.white) }
        }
        .frame(width: size, height: size)
    }
}

/// 照片上的半透明小胶囊标签
struct PhotoBadge: View {
    let text: String
    var bg: Color = .black.opacity(0.45)
    var icon: String?
    var iconTint: Color = .white
    var body: some View {
        HStack(spacing: 2) {
            if let icon { Image(systemName: icon).font(.system(size: 9, weight: .bold)).foregroundStyle(iconTint) }
            Text(text).font(.system(size: 11, weight: .medium)).foregroundStyle(.white).lineLimit(1)
        }
        .padding(.horizontal, 6).padding(.vertical, 2)
        .background(bg, in: Capsule())
    }
}

/// 照片上的原因标签：坏原因红底，好的绿底，其余灰底
struct SmartTag: View {
    let text: String
    private static let good: Set<String> = ["最佳", "推荐", "保留原图", "已收藏", "精华"]
    private static let bad: Set<String> = ["模糊", "更模糊", "有人闭眼", "过曝", "偏暗", "太暗", "误拍", "重复", "压缩副本", "分辨率低", "没那张好看"]
    var body: some View {
        PhotoBadge(text: text, bg: Self.good.contains(text) ? Color.brand.opacity(0.95) : Self.bad.contains(text) ? Color.danger.opacity(0.9) : .black.opacity(0.45))
    }
}

/// 灰色说明文字
struct Footnote: View {
    let text: String
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
    }
}
