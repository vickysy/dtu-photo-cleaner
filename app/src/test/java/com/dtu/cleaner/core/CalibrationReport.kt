package com.dtu.cleaner.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * 校准用：读取 -DcalibDir=... 下面已经缩到 512px 的原始像素文件（文件名 “原图__变体.rgb”，
 * 前 8 字节是宽高，后面是 RGB 字节），
 * 打印各项指标，帮助确定模糊阈值和相似度阈值。没有给目录时自动跳过。
 */
class CalibrationReport {
    private fun load(f: File): ImageFeatures {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val w = inp.readInt()
            val h = inp.readInt()
            val bytes = ByteArray(w * h * 3)
            inp.readFully(bytes)
            val px = IntArray(w * h) { i ->
                (0xFF shl 24) or ((bytes[i * 3].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() and 0xFF)
            }
            return ImageMetrics.compute(px, w, h)
        }
    }

    @Test
    fun report() {
        val dir = System.getProperty("calibDir").orEmpty()
        assumeTrue(dir.isNotEmpty() && File(dir).isDirectory)
        val files = File(dir).listFiles { f -> f.name.endsWith(".rgb") }!!.sortedBy { it.name }
        val feats = files.associate { it.nameWithoutExtension to load(it) }
        val bases = feats.keys.map { it.substringBefore("__") }.distinct()

        println("== 每张图的指标 ==")
        println("%-34s %8s %8s %6s %6s %5s %5s %5s %s".format("name", "sharp", "gsharp", "mean", "std", "dark", "brt", "p95", "bad(mid)"))
        for ((k, f) in feats) {
            println(
                "%-34s %8.1f %8.1f %6.1f %6.1f %5.2f %5.2f %5.0f %s".format(
                    k, f.sharpness, f.globalSharpness, f.mean, f.std, f.darkFrac, f.brightFrac, f.p95,
                    BadShotRules.reasons(f, 1).joinToString { it.label },
                ),
            )
        }

        println("\n== 同一原图的变体：指纹距离 / 颜色距离 ==")
        val variantDist = HashMap<String, MutableList<Pair<Int, Float>>>()
        for (b in bases) {
            val o = feats["${b}__orig"] ?: continue
            for ((k, f) in feats) {
                if (!k.startsWith("${b}__") || k.endsWith("__orig")) continue
                val v = k.substringAfter("__")
                variantDist.getOrPut(v) { ArrayList() }
                    .add(Similarity.hashDistance(o, f) to Similarity.colorDistance(o.color, f.color))
            }
        }
        for ((v, list) in variantDist.toSortedMap()) {
            val hs = list.map { it.first }.sorted()
            val cs = list.map { it.second }.sorted()
            println("%-9s hash min/med/max %3d %3d %3d   color med/max %.3f %.3f".format(v, hs.first(), hs[hs.size / 2], hs.last(), cs[cs.size / 2], cs.last()))
        }

        println("\n== 挑最佳：连拍里应该留 orig/shift2，副本里应该留 orig ==")
        var burstOk = 0
        var copyOk = 0
        var n = 0
        for (b in bases) {
            val o = feats["${b}__orig"] ?: continue
            if (BadShotRules.excludedFromGrouping(o) || b.startsWith("small") || b.startsWith("synthetic")) continue
            if (!listOf("g1.5", "m20", "shift2", "g3", "wechat").all { feats.containsKey("${b}__$it") }) continue
            n++
            fun ph(id: Long, v: String, w: Int = 4000, h: Int = 3000, size: Long = 4_000_000) =
                PhotoInfo(id, id * 1000, w, h, size, false, feats["${b}__$v"]!!, null)
            val burst = Ranker.rank(listOf(ph(1, "g1.5"), ph(2, "m20"), ph(3, "orig"), ph(4, "shift2"), ph(5, "g3")), 1)
            val keep = burst.members.single { it.keep }
            val keptVariant = listOf("g1.5", "m20", "orig", "shift2", "g3")[(keep.photo.id - 1).toInt()]
            if (keptVariant == "orig" || keptVariant == "shift2") burstOk++
            val copies = Ranker.rank(listOf(ph(1, "wechat", 1280, 960, 300_000), ph(2, "orig")), 1)
            val keptCopy = if (copies.members.single { it.keep }.photo.id == 2L) "orig" else "wechat"
            if (keptCopy == "orig") copyOk++
            println("%-8s 连拍留下 %-6s  分数 %s   副本组留下 %s 标签 %s".format(
                b, keptVariant, burst.members.joinToString(" ") { it.score.toString() + it.tags }, keptCopy,
                copies.members.joinToString { it.tags.toString() }))
        }
        println("连拍挑对 $burstOk/$n，副本挑对 $copyOk/$n")

        println("\n== 模糊判定：每类图被判为“模糊”的张数（低 / 中 / 高）==")
        val byVariant = feats.entries.filter { !it.key.startsWith("small") && !it.key.startsWith("synthetic") }
            .groupBy { it.key.substringAfter("__") }
        for ((v, list) in byVariant.toSortedMap()) {
            val counts = (0..2).map { lv -> list.count { BadShotRules.isBlurry(it.value, lv) } }
            println("%-9s %2d / %2d / %2d  共 %d 张".format(v, counts[0], counts[1], counts[2], list.size))
        }

        println("\n== 不同原图之间（应当判为不相似）==")
        val origs = feats.filterKeys { it.endsWith("__orig") }.entries.toList()
        val cross = ArrayList<Triple<Int, Float, String>>()
        for (i in origs.indices) for (j in i + 1 until origs.size) {
            val a = origs[i].value
            val b = origs[j].value
            cross.add(Triple(Similarity.hashDistance(a, b), Similarity.colorDistance(a.color, b.color), "${origs[i].key} vs ${origs[j].key}"))
        }
        cross.sortBy { it.first }
        cross.take(12).forEach { println("hash %3d color %.3f  %s".format(it.first, it.second, it.third)) }
        for (level in 0..2) {
            val p = GroupParams.forStrictness(level, 120, 1)
            val fp = origs.indices.sumOf { i -> (i + 1 until origs.size).count { j -> Similarity.isSimilar(origs[i].value, origs[j].value, p) } }
            val burstVariants = listOf("shift2", "shift5", "zoom6", "zoom15", "rot2", "bright12", "noise", "wechat", "g1.5", "g3", "m20")
            var tp = 0
            var tot = 0
            for (b in bases) {
                val o = feats["${b}__orig"] ?: continue
                for (v in burstVariants) {
                    val f = feats["${b}__$v"] ?: continue
                    tot++
                    if (Similarity.isSimilar(o, f, p)) tp++
                }
            }
            println("level $level: 连拍变体识别 $tp/$tot，不同照片误判 $fp/${cross.size}")
        }
    }
}
