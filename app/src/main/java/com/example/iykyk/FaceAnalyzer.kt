package com.example.iykyk

import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DetectedFace(
    val timestampMs: Long,
    val boundingBox: Rect,
    val headYaw: Float,
    val headRoll: Float,
    val leftEyeOpenProbability: Float,
    val rightEyeOpenProbability: Float,
    val smileProbability: Float,
    val tightCrop: Bitmap,
    val generousCrop: Bitmap,
    val sharpness: Float,
    val embedding: FloatArray
)

class FaceAnalyzer(context: Context) : AutoCloseable {

    private val embedder = FaceEmbedder(context)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    )

    suspend fun detectFaces(timestampMs: Long, bitmap: Bitmap): List<DetectedFace> =
        withContext(Dispatchers.Default) {

            val image = InputImage.fromBitmap(bitmap, 0)

            val faces = Tasks.await(
                detector.process(image)
            )

            faces.mapNotNull { face ->

                val box = clampRect(
                    face.boundingBox,
                    bitmap.width,
                    bitmap.height
                )

                if (box.width() <= 0 || box.height() <= 0) {
                    return@mapNotNull null
                }

                val tightCrop = createCrop(
                    bitmap = bitmap,
                    box = box,
                    widthMultiplier = 1.3f,
                    heightMultiplier = 1.3f
                )

                val generousCrop = createCrop(
                    bitmap = bitmap,
                    box = box,
                    // Keep context for later composition; this is intentionally not a face-only crop.
                    widthMultiplier = 3.0f,
                    heightMultiplier = 3.5f
                )

                DetectedFace(
                    timestampMs = timestampMs,
                    boundingBox = box,
                    headYaw = face.headEulerAngleY,
                    headRoll = face.headEulerAngleZ,
                    leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                    rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                    smileProbability = face.smilingProbability ?: 0f,
                    tightCrop = tightCrop,
                    generousCrop = generousCrop,
                    sharpness = calculateSharpness(tightCrop),
                    embedding = embedder.embed(tightCrop)
                )
            }
        }

    private fun createCrop(
        bitmap: Bitmap,
        box: Rect,
        widthMultiplier: Float,
        heightMultiplier: Float
    ): Bitmap {

        val centerX = box.centerX()
        val centerY = box.centerY()

        val cropWidth = (box.width() * widthMultiplier).toInt()
        val cropHeight = (box.height() * heightMultiplier).toInt()

        val left = (centerX - cropWidth / 2)
            .coerceAtLeast(0)

        val top = (centerY - cropHeight / 2)
            .coerceAtLeast(0)

        val right = (centerX + cropWidth / 2)
            .coerceAtMost(bitmap.width)

        val bottom = (centerY + cropHeight / 2)
            .coerceAtMost(bitmap.height)

        val finalWidth = right - left
        val finalHeight = bottom - top

        if (finalWidth <= 0 || finalHeight <= 0) {
            return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            finalWidth,
            finalHeight
        )
    }

    private fun clampRect(
        rect: Rect,
        width: Int,
        height: Int
    ): Rect {

        return Rect(
            rect.left.coerceIn(0, width),
            rect.top.coerceIn(0, height),
            rect.right.coerceIn(0, width),
            rect.bottom.coerceIn(0, height)
        )
    }

    private fun calculateSharpness(bitmap: Bitmap): Float {

        // Laplacian variance is a lightweight focus measure; the bounded ratio makes
        // values comparable across frames with different image contrast.

        val small = Bitmap.createScaledBitmap(
            bitmap,
            96,
            96,
            true
        )

        val pixels = IntArray(96 * 96)
        small.getPixels(
            pixels,
            0,
            96,
            0,
            0,
            96,
            96
        )

        val gray = IntArray(96 * 96)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        val laplacianValues = ArrayList<Double>()

        for (y in 1 until 95) {
            for (x in 1 until 95) {

                val center = gray[y * 96 + x]

                val left = gray[y * 96 + (x - 1)]
                val right = gray[y * 96 + (x + 1)]
                val top = gray[(y - 1) * 96 + x]
                val bottom = gray[(y + 1) * 96 + x]

                val laplacian =
                    (4 * center - left - right - top - bottom).toDouble()

                laplacianValues.add(laplacian)
            }
        }

        val mean = laplacianValues.average()

        val variance = laplacianValues
            .map { value ->
                val difference = value - mean
                difference * difference
            }
            .average()

        val normalized = variance
            .let { value ->
                value / (value + 500.0)
            }
            .toFloat()
            .coerceIn(0f, 1f)

        if (small !== bitmap && !small.isRecycled) {
            small.recycle()
        }
        return normalized
    }

    override fun close() {
        detector.close()
        embedder.close()
    }
}
