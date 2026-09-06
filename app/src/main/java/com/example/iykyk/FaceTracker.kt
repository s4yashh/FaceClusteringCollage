package com.example.iykyk

import android.graphics.Rect
import android.util.Log
import kotlin.math.sqrt

fun faceQuality(f: DetectedFace): Float {
    val frontality = 1f -
        ((kotlin.math.abs(f.headYaw) + kotlin.math.abs(f.headRoll)) / 90f).coerceIn(0f, 1f)
    val eyesOpen = (f.leftEyeOpenProbability + f.rightEyeOpenProbability) / 2f
    return frontality * 0.4f + f.sharpness * 0.35f + eyesOpen * 0.15f + f.smileProbability * 0.1f
}

data class Track(
    val id: Int,
    val faces: MutableList<DetectedFace> = mutableListOf()
) {
    val startMs get() = faces.first().timestampMs
    val endMs get() = faces.last().timestampMs

    // Average the three highest-quality frames for a stable but selective identity signal.
    val bestEmbedding: FloatArray by lazy {
        val topFrames = faces.sortedByDescending { faceQuality(it) }.take(3)
        val dim = topFrames.first().embedding.size
        val sum = FloatArray(dim)
        topFrames.forEach { face ->
            face.embedding.forEachIndexed { index, value -> sum[index] += value }
        }
        l2Normalize(FloatArray(dim) { sum[it] / topFrames.size })
    }
}

fun l2Normalize(v: FloatArray): FloatArray {
    var norm = 0f
    for (x in v) norm += x * x
    norm = sqrt(norm).coerceAtLeast(1e-6f)
    return FloatArray(v.size) { v[it] / norm }
}

fun cosineSim(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

fun iou(a: Rect, b: Rect): Float {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val intersection = ((right - left) * (bottom - top)).toFloat()
    val union = (a.width() * a.height() + b.width() * b.height()).toFloat() - intersection
    return intersection / union
}

class FaceTracker(
    private val iouThreshold: Float = 0.2f,
    private val simThreshold: Float = 0.45f,
    private val maxGapMs: Long = 500L,
    private val minSharpness: Float = 0.15f
) {
    fun track(allFaces: List<DetectedFace>): List<Track> {
        val byFrame = allFaces.sortedBy { it.timestampMs }.groupBy { it.timestampMs }
        val active = mutableListOf<Track>()
        val finished = mutableListOf<Track>()
        var nextId = 0

        for ((timestamp, facesInFrame) in byFrame) {
            val used = mutableSetOf<Int>()

            for (face in facesInFrame) {
                var best: Track? = null
                var bestScore = 0f

                for (t in active) {
                    if (t.id in used) continue
                    if (timestamp - t.endMs > maxGapMs) continue
                    val last = t.faces.last()
                    val i = iou(face.boundingBox, last.boundingBox)
                    val s = cosineSim(face.embedding, last.embedding)
                    if (i >= iouThreshold && s >= simThreshold) {
                        val score = i * s
                        if (score > bestScore) { bestScore = score; best = t }
                    }
                }

                if (best != null) {
                    best.faces.add(face)
                    used.add(best.id)
                } else {
                    val newTrack = Track(nextId++).apply { faces.add(face) }
                    active.add(newTrack)
                    used.add(newTrack.id)
                    Log.d(
                        TAG,
                        "TRACK_START trackId=${newTrack.id} timestamp=${face.timestampMs}"
                    )
                }
            }

            val expired = active.filter { timestamp - it.endMs > maxGapMs }
            expired.forEach { track ->
                Log.d(
                    TAG,
                    "TRACK_END trackId=${track.id} start=${track.startMs} " +
                        "end=${track.endMs} frames=${track.faces.size}"
                )
            }
            finished.addAll(expired)
            active.removeAll(expired)
        }
        active.forEach { track ->
            Log.d(
                TAG,
                "TRACK_END trackId=${track.id} start=${track.startMs} " +
                    "end=${track.endMs} frames=${track.faces.size}"
            )
        }
        finished.addAll(active)

        return finished.filter { t ->
            t.faces.size >= 2 && t.faces.any { it.sharpness >= minSharpness }
        }
    }

    companion object {
        private const val TAG = "IYKYK_TRACK"
    }
}
