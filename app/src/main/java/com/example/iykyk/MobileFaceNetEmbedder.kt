package com.example.iykyk

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MobileFaceNetEmbedder(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannelsLast: Boolean
    private val outputDimension: Int
    private val loggedFirstInference = AtomicBoolean(false)

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }
        val options = Interpreter.Options().apply {
            setNumThreads(CPU_THREADS)
        }
        interpreter = Interpreter(model, options)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
            "MobileFaceNet input must be FLOAT32, got ${inputTensor.dataType()}"
        }
        require(inputShape.size == 4) {
            "Expected a 4D MobileFaceNet input, got ${inputShape.contentToString()}"
        }

        inputChannelsLast = when {
            inputShape[3] == 3 -> true
            inputShape[1] == 3 -> false
            else -> error("Could not identify RGB channel dimension in ${inputShape.contentToString()}")
        }
        inputHeight = if (inputChannelsLast) inputShape[1] else inputShape[2]
        inputWidth = if (inputChannelsLast) inputShape[2] else inputShape[3]

        val outputTensor = interpreter.getOutputTensor(0)
        require(outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
            "MobileFaceNet output must be FLOAT32, got ${outputTensor.dataType()}"
        }
        outputDimension = outputTensor.numElements()

        Log.d(TAG, "model input shape=${inputShape.contentToString()}")
        Log.d(TAG, "model output shape=${outputTensor.shape().contentToString()}")
        Log.d(TAG, "embedding dimension=$outputDimension")
    }

    suspend fun embed(tightCrop: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val resized = Bitmap.createScaledBitmap(tightCrop, inputWidth, inputHeight, true)
        try {
            val pixels = IntArray(inputWidth * inputHeight)
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            val input = FloatArray(interpreter.getInputTensor(0).numElements())

            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = pixels[y * inputWidth + x]
                    val red = ((pixel shr 16) and 0xFF) / 128.0f - 1.0f
                    val green = ((pixel shr 8) and 0xFF) / 128.0f - 1.0f
                    val blue = (pixel and 0xFF) / 128.0f - 1.0f

                    if (inputChannelsLast) {
                        val offset = (y * inputWidth + x) * 3
                        input[offset] = red
                        input[offset + 1] = green
                        input[offset + 2] = blue
                    } else {
                        val planeSize = inputWidth * inputHeight
                        val offset = y * inputWidth + x
                        input[offset] = red
                        input[planeSize + offset] = green
                        input[2 * planeSize + offset] = blue
                    }
                }
            }

            val output = FloatArray(outputDimension)
            interpreter.run(input, output)
            l2Normalize(output).also {
                if (loggedFirstInference.compareAndSet(false, true)) {
                    Log.d(TAG, "first embedding inference completed")
                }
            }
        } finally {
            if (resized !== tightCrop && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val MODEL_ASSET = "mobilefacenet.tflite"
        private const val CPU_THREADS = 4
        private const val TAG = "IYKYK_EMBEDDING"

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Embedding dimensions do not match" }
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (index in a.indices) {
                dot += a[index] * b[index]
                normA += a[index] * a[index]
                normB += b[index] * b[index]
            }
            if (normA == 0f || normB == 0f) return 0f
            return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(-1f, 1f)
        }

        private fun l2Normalize(values: FloatArray): FloatArray {
            var squaredSum = 0f
            for (value in values) squaredSum += value * value
            val norm = sqrt(squaredSum)
            if (norm > 0f) {
                for (index in values.indices) values[index] /= norm
            }
            return values
        }
    }
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float =
    MobileFaceNetEmbedder.cosineSimilarity(a, b)
