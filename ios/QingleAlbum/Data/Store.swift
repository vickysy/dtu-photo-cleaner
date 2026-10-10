import Foundation
import SQLite3

/// 每张照片的分析结果（缓存在本地数据库里，下次打开不用重新算）
struct StoredFeat: Codable {
    /// nil = 读不到图（比如只在 iCloud 里、本机没有缩略图）
    var features: ImageFeatures?
    var faces: FaceInfo?
    /// 美感分 1..10
    var aesthetic: Float?
    /// 系统认为是“实用类”图片：截图、收据、文件、白板……
    var utility: Bool?
}

/**
 本地缓存（SQLite）：分析结果、文件大小、用户确认“保留”过的照片。
 只存数字，不存照片本身。所有方法都可以在后台线程调用（串行队列保护）。
 */
final class Store {
    static let shared = Store()
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "store")
    private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("analysis.sqlite").path
        if sqlite3_open(path, &db) != SQLITE_OK { db = nil }
        exec("PRAGMA journal_mode=WAL")
        exec("CREATE TABLE IF NOT EXISTS feat(id TEXT PRIMARY KEY, mod REAL NOT NULL, ver INTEGER NOT NULL, data BLOB NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS sizes(id TEXT PRIMARY KEY, mod REAL NOT NULL, size INTEGER NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS reviewed(id TEXT PRIMARY KEY)")
        exec("CREATE TABLE IF NOT EXISTS vfeat(id TEXT PRIMARY KEY, mod REAL NOT NULL, ver INTEGER NOT NULL, data BLOB NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS ocr(id TEXT PRIMARY KEY, mod REAL NOT NULL, ver INTEGER NOT NULL, data BLOB NOT NULL)")
        // 压缩：原视频 → 压好的视频，等用户对比确认后删原视频
        exec("CREATE TABLE IF NOT EXISTS compressed(src TEXT PRIMARY KEY, out TEXT NOT NULL, at REAL NOT NULL)")
    }

    private func exec(_ sql: String) {
        queue.sync { _ = sqlite3_exec(db, sql, nil, nil, nil) }
    }

    // ---------- 分析结果 ----------

    struct FeatRow { let mod: Double; let ver: Int; let feat: StoredFeat }

    func loadFeatures() -> [String: FeatRow] {
        queue.sync {
            var out: [String: FeatRow] = [:]
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id, mod, ver, data FROM feat", -1, &st, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(st) }
            let dec = JSONDecoder()
            while sqlite3_step(st) == SQLITE_ROW {
                let id = String(cString: sqlite3_column_text(st, 0))
                let n = Int(sqlite3_column_bytes(st, 3))
                guard let p = sqlite3_column_blob(st, 3) else { continue }
                let data = Data(bytes: p, count: n)
                if let f = try? dec.decode(StoredFeat.self, from: data) {
                    out[id] = FeatRow(mod: sqlite3_column_double(st, 1), ver: Int(sqlite3_column_int(st, 2)), feat: f)
                }
            }
            return out
        }
    }

    func putFeatures(_ rows: [(id: String, mod: Double, feat: StoredFeat)], ver: Int) {
        queue.sync {
            let enc = JSONEncoder()
            sqlite3_exec(db, "BEGIN", nil, nil, nil)
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO feat(id, mod, ver, data) VALUES(?,?,?,?)", -1, &st, nil)
            for r in rows {
                guard let data = try? enc.encode(r.feat) else { continue }
                sqlite3_bind_text(st, 1, r.id, -1, SQLITE_TRANSIENT)
                sqlite3_bind_double(st, 2, r.mod)
                sqlite3_bind_int(st, 3, Int32(ver))
                data.withUnsafeBytes { b in _ = sqlite3_bind_blob(st, 4, b.baseAddress, Int32(data.count), SQLITE_TRANSIENT) }
                sqlite3_step(st)
                sqlite3_reset(st)
            }
            sqlite3_finalize(st)
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
        }
    }

    // ---------- 文件大小 ----------

    func loadSizes() -> [String: (mod: Double, size: Int64)] {
        queue.sync {
            var out: [String: (Double, Int64)] = [:]
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id, mod, size FROM sizes", -1, &st, nil) == SQLITE_OK else { return [:] }
            defer { sqlite3_finalize(st) }
            while sqlite3_step(st) == SQLITE_ROW {
                out[String(cString: sqlite3_column_text(st, 0))] = (sqlite3_column_double(st, 1), sqlite3_column_int64(st, 2))
            }
            return out
        }
    }

    func putSizes(_ rows: [(id: String, mod: Double, size: Int64)]) {
        if rows.isEmpty { return }
        queue.sync {
            sqlite3_exec(db, "BEGIN", nil, nil, nil)
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO sizes(id, mod, size) VALUES(?,?,?)", -1, &st, nil)
            for r in rows {
                sqlite3_bind_text(st, 1, r.id, -1, SQLITE_TRANSIENT)
                sqlite3_bind_double(st, 2, r.mod)
                sqlite3_bind_int64(st, 3, r.size)
                sqlite3_step(st)
                sqlite3_reset(st)
            }
            sqlite3_finalize(st)
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
        }
    }

    // ---------- 用户确认保留的 ----------

    func reviewedIds() -> Set<String> {
        queue.sync {
            var out = Set<String>()
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id FROM reviewed", -1, &st, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(st) }
            while sqlite3_step(st) == SQLITE_ROW { out.insert(String(cString: sqlite3_column_text(st, 0))) }
            return out
        }
    }

    func markReviewed(_ ids: [String]) {
        queue.sync {
            sqlite3_exec(db, "BEGIN", nil, nil, nil)
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO reviewed(id) VALUES(?)", -1, &st, nil)
            for id in ids {
                sqlite3_bind_text(st, 1, id, -1, SQLITE_TRANSIENT)
                sqlite3_step(st)
                sqlite3_reset(st)
            }
            sqlite3_finalize(st)
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
        }
    }

    func clearReviewed() { exec("DELETE FROM reviewed") }

    /// 重新分析全部
    func clearFeatures() { exec("DELETE FROM feat"); exec("DELETE FROM vfeat"); exec("DELETE FROM ocr") }

    // ---------- 视频 ----------

    struct VideoRow { let mod: Double; let ver: Int; let feat: VideoFeatures }

    func loadVideos() -> [String: VideoRow] {
        queue.sync {
            var out: [String: VideoRow] = [:]
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id, mod, ver, data FROM vfeat", -1, &st, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(st) }
            let dec = JSONDecoder()
            while sqlite3_step(st) == SQLITE_ROW {
                guard let p = sqlite3_column_blob(st, 3) else { continue }
                let data = Data(bytes: p, count: Int(sqlite3_column_bytes(st, 3)))
                if let f = try? dec.decode(VideoFeatures.self, from: data) {
                    out[String(cString: sqlite3_column_text(st, 0))] = VideoRow(mod: sqlite3_column_double(st, 1), ver: Int(sqlite3_column_int(st, 2)), feat: f)
                }
            }
            return out
        }
    }

    func putVideos(_ rows: [(id: String, mod: Double, feat: VideoFeatures)], ver: Int) {
        queue.sync {
            let enc = JSONEncoder()
            sqlite3_exec(db, "BEGIN", nil, nil, nil)
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO vfeat(id, mod, ver, data) VALUES(?,?,?,?)", -1, &st, nil)
            for r in rows {
                guard let data = try? enc.encode(r.feat) else { continue }
                sqlite3_bind_text(st, 1, r.id, -1, SQLITE_TRANSIENT)
                sqlite3_bind_double(st, 2, r.mod)
                sqlite3_bind_int(st, 3, Int32(ver))
                data.withUnsafeBytes { b in _ = sqlite3_bind_blob(st, 4, b.baseAddress, Int32(data.count), SQLITE_TRANSIENT) }
                sqlite3_step(st)
                sqlite3_reset(st)
            }
            sqlite3_finalize(st)
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
        }
    }

    // ---------- 识字 ----------

    /// 一张图里识别出的字（证件号已打码）、二维码、字的排布
    struct TextRow: Codable {
        var text: String
        var codes: [String]
        var layout: TextLayout?
    }

    func loadTexts() -> [String: (mod: Double, ver: Int, row: TextRow)] {
        queue.sync {
            var out: [String: (Double, Int, TextRow)] = [:]
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id, mod, ver, data FROM ocr", -1, &st, nil) == SQLITE_OK else { return [:] }
            defer { sqlite3_finalize(st) }
            let dec = JSONDecoder()
            while sqlite3_step(st) == SQLITE_ROW {
                guard let p = sqlite3_column_blob(st, 3) else { continue }
                let data = Data(bytes: p, count: Int(sqlite3_column_bytes(st, 3)))
                if let r = try? dec.decode(TextRow.self, from: data) {
                    out[String(cString: sqlite3_column_text(st, 0))] = (sqlite3_column_double(st, 1), Int(sqlite3_column_int(st, 2)), r)
                }
            }
            return out
        }
    }

    func putTexts(_ rows: [(id: String, mod: Double, row: TextRow)], ver: Int) {
        queue.sync {
            let enc = JSONEncoder()
            sqlite3_exec(db, "BEGIN", nil, nil, nil)
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO ocr(id, mod, ver, data) VALUES(?,?,?,?)", -1, &st, nil)
            for r in rows {
                guard let data = try? enc.encode(r.row) else { continue }
                sqlite3_bind_text(st, 1, r.id, -1, SQLITE_TRANSIENT)
                sqlite3_bind_double(st, 2, r.mod)
                sqlite3_bind_int(st, 3, Int32(ver))
                data.withUnsafeBytes { b in _ = sqlite3_bind_blob(st, 4, b.baseAddress, Int32(data.count), SQLITE_TRANSIENT) }
                sqlite3_step(st)
                sqlite3_reset(st)
            }
            sqlite3_finalize(st)
            sqlite3_exec(db, "COMMIT", nil, nil, nil)
        }
    }

    // ---------- 压缩 ----------

    /// 原视频 id → 压好的视频 id
    func loadCompressed() -> [String: String] {
        queue.sync {
            var out: [String: String] = [:]
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT src, out FROM compressed", -1, &st, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(st) }
            while sqlite3_step(st) == SQLITE_ROW {
                out[String(cString: sqlite3_column_text(st, 0))] = String(cString: sqlite3_column_text(st, 1))
            }
            return out
        }
    }

    func putCompressed(src: String, out: String) {
        queue.sync {
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO compressed(src, out, at) VALUES(?,?,?)", -1, &st, nil)
            sqlite3_bind_text(st, 1, src, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(st, 2, out, -1, SQLITE_TRANSIENT)
            sqlite3_bind_double(st, 3, Date().timeIntervalSince1970)
            sqlite3_step(st)
            sqlite3_finalize(st)
        }
    }

    func removeCompressed(src: String) {
        queue.sync {
            var st: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM compressed WHERE src = ?", -1, &st, nil)
            sqlite3_bind_text(st, 1, src, -1, SQLITE_TRANSIENT)
            sqlite3_step(st)
            sqlite3_finalize(st)
        }
    }
}
