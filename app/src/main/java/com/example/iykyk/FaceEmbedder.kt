package com.example.iykyk

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceEmbedder(context: Context) : AutoCloseable {
    private val interpreter: Interpreter

    init {
        val asset = context.assets.openFd("mobilefacenet.tflite")
        val mappedBuffer = asset.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY,
            asset.startOffset,
            asset.declaredLength
        )
        interpreter = Interpreter(mappedBuffer)
        // This supplied model requires two input images. Use the same crop for both
        // rows and retain the first output embedding.
    }

    @Synchronized
    fun embed(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, 112, 112, true)
        val inputBuffer = ByteBuffer.allocateDirect(2 * 112 * 112 * 3 * 4)
            .order(ByteOrder.nativeOrder())

        repeat(2) {
            for (y in 0 until 112) {
                for (x in 0 until 112) {
                    val pixel = resized.getPixel(x, y)
                    inputBuffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 128f)
                    inputBuffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 128f)
                    inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 128f)
                }
            }
        }
        inputBuffer.rewind()

        val output = Array(2) { FloatArray(192) }
        interpreter.run(inputBuffer, output)
        if (resized !== faceBitmap && !resized.isRecycled) resized.recycle()
        return l2Normalize(output[0])
    }

    private fun l2Normalize(values: FloatArray): FloatArray {
        var norm = 0f
        for (value in values) norm += value * value
        norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)
        return FloatArray(values.size) { values[it] / norm }
    }

    override fun close() {
        interpreter.close()
    }
}

fun cosineSimilarity(first: FloatArray, second: FloatArray): Float =
    cosineSim(first, second)
