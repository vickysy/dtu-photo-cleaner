package com.dtu.cleaner.analysis

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 照片美感评分：NIMA（Neural Image Assessment，idealo 开源，Apache-2.0）的 MobileNet 版本，
 * 用 AVA 摄影比赛照片的人工评分训练。输出 1..10 分，普通照片大多在 4～6.5 分之间。
 * 模型在手机本地运行，不联网。
 */
class AestheticScorer private constructor(private val interpreter: Interpreter) : Closeable {
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val outCount = interpreter.getOutputTensor(0).numElements()
    private val output = ByteBuffer.allocateDirect(outCount * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)

    /** 线程安全（内部串行），返回 1..10 */
    @Synchronized
    fun score(src: Bitmap): Float {
        // 分两步缩到 224，避免一次缩太多产生锯齿
        var bmp = src
        while (bmp.width >= SIZE * 4 || bmp.height >= SIZE * 4) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== src) bmp.recycle()
            bmp = next
        }
        // 模型训练时是直接拉伸到 224x224（不裁剪），这里保持一致
        val small = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        if (bmp !== src && bmp !== small) bmp.recycle()
        small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== src) small.recycle()

        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 127.5f - 1f)
            input.putFloat(((p shr 8) and 0xFF) / 127.5f - 1f)
            input.putFloat((p and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        var score = 0f
        for (i in 0 until outCount) score += (i + 1) * output.getFloat()
        return score
    }

    override fun close() = interpreter.close()

    companion object {
        private const val SIZE = 224
        private const val MODEL = "nima_aesthetic_fp16.tflite"

        /** 模型加载失败（极少见）时返回 null，App 照常工作，只是不用美感分 */
        fun create(ctx: Context): AestheticScorer? = try {
            val fd = ctx.assets.openFd(MODEL)
            val buf = FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            AestheticScorer(Interpreter(buf, Interpreter.Options().setNumThreads(4)))
        } catch (e: Throwable) {
            android.util.Log.w("DTu", "aesthetic model unavailable", e)
            null
        }
    }
}
