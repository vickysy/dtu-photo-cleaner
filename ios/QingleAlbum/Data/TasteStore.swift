import Foundation

/**
 越用越懂你（和安卓版一样）：用户在相似照片里改了推荐（留下的不是推荐的那张）再删除时记一票，
 看他更看重构图好看还是拍得清楚，以后挑照片跟着调。票数只存在手机里。
 */
enum TasteStore {
    private static let aesKey = "taste.aes"
    private static let techKey = "taste.tech"

    static var votes: (aes: Int, tech: Int) {
        (UserDefaults.standard.integer(forKey: aesKey), UserDefaults.standard.integer(forKey: techKey))
    }

    static var taste: Taste { Taste.from(aesVotes: votes.aes, techVotes: votes.tech) }

    static func record(_ v: (aes: Int, tech: Int)) {
        guard v.aes + v.tech > 0 else { return }
        let now = votes
        UserDefaults.standard.set(now.aes + v.aes, forKey: aesKey)
        UserDefaults.standard.set(now.tech + v.tech, forKey: techKey)
    }

    static func reset() {
        UserDefaults.standard.removeObject(forKey: aesKey)
        UserDefaults.standard.removeObject(forKey: techKey)
    }

    /**
     改过推荐的组里，留下的和删掉的两两比较：美感高但没那么清楚的被留下 → 更看重好看；反过来 → 更看重清楚。
     两样都更好的说明不了口味，不算。[deleted] 是这次真正删掉的 id
     */
    static func votes(_ groups: [RankedGroup], deleted: Set<String>) -> (aes: Int, tech: Int) {
        var aes = 0, tech = 0
        for g in groups {
            // 没改过推荐的组不算
            if !g.members.contains(where: { deleted.contains($0.photo.id) == $0.keep }) { continue }
            let kept = g.members.filter { !deleted.contains($0.photo.id) }
            let dropped = g.members.filter { deleted.contains($0.photo.id) }
            for k in kept {
                for d in dropped {
                    guard let ka = k.photo.aesthetic, let da = d.photo.aesthetic else { continue }
                    let aesDiff = ka - da, techDiff = k.tech - d.tech
                    if abs(aesDiff) < 0.15 || abs(techDiff) < 0.05 { continue }
                    if (aesDiff > 0) == (techDiff > 0) { continue }
                    if aesDiff > 0 { aes += 1 } else { tech += 1 }
                }
            }
        }
        return (aes, tech)
    }
}
