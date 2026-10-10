package com.dtu.cleaner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dtu.cleaner.core.FaceInfo
import com.dtu.cleaner.core.ImageFeatures
import com.dtu.cleaner.core.TextLayout
import com.dtu.cleaner.core.VideoFeatures
import java.nio.ByteBuffer

/**
 * 一张照片存下来的分析结果。[features] 为 null 表示这张图解码失败；
 * [aesthetic] 美感分 1..10，null = 还没评，-1 = 评分失败。
 */
class StoredFeat(
    val features: ImageFeatures?,
    val faces: FaceInfo?,
    val aesthetic: Float?,
    /** 拍摄地点；没有 GPS 或还没读是 NaN */
    val lat: Double = Double.NaN,
    val lng: Double = Double.NaN,
    /** 地点读过了（有没有都算读过） */
    val geoRead: Boolean = false,
)

/** 视频分析结果：[features] 为 null 表示抽帧失败 */
class StoredVideo(val modified: Long, val size: Long, val version: Int, val features: VideoFeatures?)

/** 识别出的文字和二维码；[layout] 字块排布（认聊天记录用） */
class StoredText(val modified: Long, val version: Int, val text: String, val codes: List<String>, val layout: TextLayout? = null)

/** 要存的一行识字结果 */
class TextRow(val item: MediaItem, val text: String, val codes: List<String>, val layout: TextLayout?)

class Stamp(val modified: Long, val size: Long, val version: Int)

/**
 * 本地缓存：每张照片的分析结果、用户已经确认“保留”的照片、累计清理统计。
 * 分析一次几万张照片要十几分钟，缓存下来以后只分析新照片。
 */
class AnalysisDb(ctx: Context) : SQLiteOpenHelper(ctx, "analysis.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE feat(
                id INTEGER PRIMARY KEY, mod INTEGER NOT NULL, size INTEGER NOT NULL, ver INTEGER NOT NULL,
                ok INTEGER NOT NULL, dh INTEGER, vh INTEGER, color BLOB, sharp REAL, gsharp REAL,
                mean REAL, std REAL, dark REAL, bright REAL, p95 REAL,
                faces INTEGER NOT NULL DEFAULT -1, eyes REAL NOT NULL DEFAULT -1, smile REAL NOT NULL DEFAULT -1,
                peak REAL, ratio REAL, aniso REAL, aes REAL, lat REAL, lng REAL, geo INTEGER NOT NULL DEFAULT 0)""",
        )
        createV4Tables(db)
        db.execSQL("CREATE TABLE reviewed(id INTEGER PRIMARY KEY, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cleaned(at INTEGER NOT NULL, count INTEGER NOT NULL, bytes INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：新的模糊判定需要三个新特征；旧行的分析版本号不对，会自动重新分析
            db.execSQL("ALTER TABLE feat ADD COLUMN peak REAL")
            db.execSQL("ALTER TABLE feat ADD COLUMN ratio REAL")
            db.execSQL("ALTER TABLE feat ADD COLUMN aniso REAL")
        }
        if (oldVersion < 3) {
            // v3：美感分。只对相似组里的照片评，空着的下次分析时补上，不用重新分析整个相册
            db.execSQL("ALTER TABLE feat ADD COLUMN aes REAL")
        }
        if (oldVersion < 4) {
            // v4：拍摄地点、视频抽帧、截图识字。都是补充分析，不用重新分析照片
            db.execSQL("ALTER TABLE feat ADD COLUMN lat REAL")
            db.execSQL("ALTER TABLE feat ADD COLUMN lng REAL")
            db.execSQL("ALTER TABLE feat ADD COLUMN geo INTEGER NOT NULL DEFAULT 0")
            createV4Tables(db)
        } else {
            if (oldVersion < 5) {
                // v5：视频中间那帧的美感分（视频场景精选用）
                db.execSQL("ALTER TABLE vfeat ADD COLUMN aes REAL")
            }
            if (oldVersion < 6) {
                // v6：字块排布（认聊天记录）
                db.execSQL("ALTER TABLE ocr ADD COLUMN meta TEXT")
            }
        }
    }

    private fun createV4Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE vfeat(
                id INTEGER PRIMARY KEY, mod INTEGER NOT NULL, size INTEGER NOT NULL, ver INTEGER NOT NULL, ok INTEGER NOT NULL,
                frames INTEGER, dark INTEGER, plain INTEGER, blurry INTEGER, hashes BLOB, color BLOB, aes REAL)""",
        )
        db.execSQL("CREATE TABLE ocr(id INTEGER PRIMARY KEY, mod INTEGER NOT NULL, ver INTEGER NOT NULL, text TEXT NOT NULL, codes TEXT NOT NULL, meta TEXT)")
        // 压缩过的视频：原视频 → 压缩版，等用户确认后删原视频
        db.execSQL("CREATE TABLE compressed(src INTEGER PRIMARY KEY, out INTEGER NOT NULL, at INTEGER NOT NULL)")
    }

    fun putCompressed(src: Long, out: Long) {
        writableDatabase.insertWithOnConflict(
            "compressed", null,
            ContentValues().apply { put("src", src); put("out", out); put("at", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 原视频 id → 压缩版 id */
    fun loadCompressed(): Map<Long, Long> = readAll { db ->
        val out = HashMap<Long, Long>()
        db.rawQuery("SELECT src, out FROM compressed", null).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) }
        out
    }

    fun removeCompressed(srcs: Collection<Long>) {
        if (srcs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            srcs.forEach { db.delete("compressed", "src=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 整张表一次读完。行数多时游标是分段读的（每段约 2MB，大概两千行），
     * 段与段之间如果分析线程正好删了行（相册里删掉的照片），后一段会对不上，直接崩溃。
     * 所以放在事务里读：读的过程中别的线程写不进来。
     */
    private inline fun <T> readAll(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val r = block(db)
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun stamps(): Map<Long, Stamp> = readAll { db ->
        val out = HashMap<Long, Stamp>()
        db.rawQuery("SELECT id, mod, size, ver FROM feat", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Stamp(c.getLong(1), c.getLong(2), c.getInt(3))
        }
        out
    }

    fun loadAll(): Map<Long, StoredFeat> = readAll { db ->
        val out = HashMap<Long, StoredFeat>()
        db.rawQuery(
            "SELECT id, ok, dh, vh, color, sharp, gsharp, mean, std, dark, bright, p95, faces, eyes, smile, peak, ratio, aniso, aes, lat, lng, geo FROM feat",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val ok = c.getInt(1) == 1
                val f = if (!ok) null else ImageFeatures(
                    dHash = c.getLong(2),
                    vHash = c.getLong(3),
                    color = c.getBlob(4),
                    sharpness = c.getFloat(5),
                    globalSharpness = c.getFloat(6),
                    mean = c.getFloat(7),
                    std = c.getFloat(8),
                    darkFrac = c.getFloat(9),
                    brightFrac = c.getFloat(10),
                    p95 = c.getFloat(11),
                    peakSharpness = c.getFloat(15),
                    focusRatio = c.getFloat(16),
                    anisotropy = c.getFloat(17),
                )
                val faces = c.getInt(12)
                out[c.getLong(0)] = StoredFeat(
                    f,
                    if (faces < 0) null else FaceInfo(faces, c.getFloat(13), c.getFloat(14)),
                    if (c.isNull(18)) null else c.getFloat(18),
                    if (c.isNull(19)) Double.NaN else c.getDouble(19),
                    if (c.isNull(20)) Double.NaN else c.getDouble(20),
                    c.getInt(21) == 1,
                )
            }
        }
        out
    }

    fun putFeatures(rows: List<Pair<MediaItem, ImageFeatures?>>, version: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((item, f) in rows) {
                val v = ContentValues().apply {
                    put("id", item.id)
                    put("mod", item.modified)
                    put("size", item.size)
                    put("ver", version)
                    put("ok", if (f != null) 1 else 0)
                    if (f != null) {
                        put("dh", f.dHash)
                        put("vh", f.vHash)
                        put("color", f.color)
                        put("sharp", f.sharpness)
                        put("gsharp", f.globalSharpness)
                        put("mean", f.mean)
                        put("std", f.std)
                        put("dark", f.darkFrac)
                        put("bright", f.brightFrac)
                        put("p95", f.p95)
                        put("peak", f.peakSharpness)
                        put("ratio", f.focusRatio)
                        put("aniso", f.anisotropy)
                    }
                    put("faces", -1)
                }
                db.insertWithOnConflict("feat", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun putFaces(rows: List<Pair<Long, FaceInfo>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, fi) in rows) {
                val v = ContentValues().apply {
                    put("faces", fi.count)
                    put("eyes", fi.minEyesOpen)
                    put("smile", fi.avgSmile)
                }
                db.update("feat", v, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun putAesthetic(rows: List<Pair<Long, Float>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, aes) in rows) db.update("feat", ContentValues().apply { put("aes", aes) }, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 拍摄地点：读过的都记下，没有 GPS 的存 NULL */
    fun putGeo(rows: List<Pair<Long, Pair<Double, Double>?>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, ll) in rows) {
                val v = ContentValues().apply {
                    put("geo", 1)
                    if (ll != null) { put("lat", ll.first); put("lng", ll.second) } else { putNull("lat"); putNull("lng") }
                }
                db.update("feat", v, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 视频 ----------

    fun putVideos(rows: List<Triple<MediaItem, VideoFeatures?, Int>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((item, f, ver) in rows) {
                val v = ContentValues().apply {
                    put("id", item.id); put("mod", item.modified); put("size", item.size); put("ver", ver)
                    put("ok", if (f != null) 1 else 0)
                    if (f != null) {
                        put("frames", f.frames); put("dark", f.darkFrames); put("plain", f.plainFrames); put("blurry", f.blurryFrames)
                        put("hashes", ByteBuffer.allocate(8 * f.hashes.size).also { b -> f.hashes.forEach { b.putLong(it) } }.array())
                        put("color", f.color)
                        if (f.aesthetic != null) put("aes", f.aesthetic) else putNull("aes")
                    }
                }
                db.insertWithOnConflict("vfeat", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadVideos(): Map<Long, StoredVideo> = readAll { db ->
        val out = HashMap<Long, StoredVideo>()
        db.rawQuery("SELECT id, mod, size, ver, ok, frames, dark, plain, blurry, hashes, color, aes FROM vfeat", null).use { c ->
            while (c.moveToNext()) {
                val f = if (c.getInt(4) != 1) null else {
                    val hb = ByteBuffer.wrap(c.getBlob(9) ?: ByteArray(0))
                    VideoFeatures(
                        frames = c.getInt(5), darkFrames = c.getInt(6), plainFrames = c.getInt(7), blurryFrames = c.getInt(8),
                        hashes = LongArray(hb.remaining() / 8) { hb.long },
                        color = c.getBlob(10) ?: ByteArray(48),
                        aesthetic = if (c.isNull(11)) null else c.getFloat(11),
                    )
                }
                out[c.getLong(0)] = StoredVideo(c.getLong(1), c.getLong(2), c.getInt(3), f)
            }
        }
        out
    }

    // ---------- 文字 ----------

    fun putTexts(rows: List<TextRow>, ver: (MediaItem) -> Int) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                db.insertWithOnConflict(
                    "ocr", null,
                    ContentValues().apply {
                        put("id", r.item.id); put("mod", r.item.modified); put("ver", ver(r.item)); put("text", r.text)
                        put("codes", r.codes.joinToString("\n")); put("meta", r.layout?.encode())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadTexts(): Map<Long, StoredText> = readAll { db ->
        val out = HashMap<Long, StoredText>()
        db.rawQuery("SELECT id, mod, ver, text, codes, meta FROM ocr", null).use { c ->
            while (c.moveToNext()) {
                val codes = c.getString(4).let { if (it.isEmpty()) emptyList() else it.split('\n') }
                out[c.getLong(0)] = StoredText(c.getLong(1), c.getInt(2), c.getString(3), codes, TextLayout.decode(c.getString(5)))
            }
        }
        out
    }

    /** 删掉已经不在相册里的视频、截图的缓存 */
    fun pruneMissingOther(existingVideos: Set<Long>, existingImages: Set<Long>) {
        val staleV = readAll { db ->
            val l = ArrayList<Long>()
            db.rawQuery("SELECT id FROM vfeat", null).use { c -> while (c.moveToNext()) c.getLong(0).let { if (it !in existingVideos) l.add(it) } }
            l
        }
        val staleT = readAll { db ->
            val l = ArrayList<Long>()
            db.rawQuery("SELECT id FROM ocr", null).use { c -> while (c.moveToNext()) c.getLong(0).let { if (it !in existingImages) l.add(it) } }
            l
        }
        if (staleV.isEmpty() && staleT.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            staleV.forEach { db.delete("vfeat", "id=?", arrayOf(it.toString())) }
            staleT.forEach { db.delete("ocr", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删掉相册里已经不存在的照片的缓存。 */
    fun pruneMissing(existing: Set<Long>) {
        val stale = stamps().keys.filter { it !in existing }
        if (stale.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.forEach { db.delete("feat", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearFeatures() { writableDatabase.delete("feat", null, null) }

    fun reviewedIds(): Set<Long> = readAll { db ->
        val out = HashSet<Long>()
        db.rawQuery("SELECT id FROM reviewed", null).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        out
    }

    fun addReviewed(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (id in ids) {
                db.insertWithOnConflict("reviewed", null, ContentValues().apply { put("id", id); put("at", now) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearReviewed() { writableDatabase.delete("reviewed", null, null) }

    fun addCleaned(count: Int, bytes: Long) {
        writableDatabase.insert("cleaned", null, ContentValues().apply {
            put("at", System.currentTimeMillis()); put("count", count); put("bytes", bytes)
        })
    }

    fun cleanedTotal(): Pair<Int, Long> =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(count),0), COALESCE(SUM(bytes),0) FROM cleaned", null).use { c ->
            c.moveToFirst(); c.getInt(0) to c.getLong(1)
        }
}
