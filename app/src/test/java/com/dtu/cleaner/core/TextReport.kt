package com.dtu.cleaner.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 校准用：读 -DtextTsv=...（每行：id、目录、文件名、是否相机、排布、二维码、识出的字，换行写成 \n），
 * 用现在的分类规则把每张图分一遍，结果写到同目录的 kinds.tsv。没给文件时自动跳过。
 */
class TextReport {
    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")

    @Test
    fun report() {
        val path = System.getProperty("textTsv").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).isFile)
        val out = StringBuilder("id\tkind\n")
        File(path).readLines().drop(1).forEach { line ->
            val c = line.split('\t')
            if (c.size < 7) return@forEach
            val codes = unescape(c[5]).split('\n').filter { it.isNotEmpty() }
            val kind = ScreenText.classify(unescape(c[6]), codes, TextLayout.decode(c[4]), c[3] == "1")
            out.append(c[0]).append('\t').append(kind?.name ?: "").append('\n')
        }
        File(File(path).parentFile, "kinds.tsv").writeText(out.toString())
    }
}
