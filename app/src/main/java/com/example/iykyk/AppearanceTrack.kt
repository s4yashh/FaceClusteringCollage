package com.example.iykyk

import android.graphics.Rect
import kotlin.math.sqrt

class AppearanceTrack(
    val id: Int,
    firstObservation: FaceObservation
) {
    val observations = mutableListOf(firstObservation)
    var lastTimestampMs: Long = firstObservation.timestampMs
        private set
    var lastBoundingBox: Rect = Rect(firstObservation.face.boundingBox)
        private set
    var lastEmbedding: FloatArray = firstObservation.embedding.copyOf()
        private set
    var closed: Boolean = false
        private set

    val durationMs: Long
        get() = lastTimestampMs - observations.first().timestampMs

    val frameCount: Int
        get() = observations.size

    val averageEmbedding: FloatArray
        get() {
            if (observations.isEmpty()) return FloatArray(0)
            val average = FloatArray(observations.first().embedding.size)
            observations.forEach { observation ->
                observation.embedding.forEachIndexed { index, value -> average[index] += value }
            }
            val count = observations.size.toFloat()
            var squaredSum = 0f
            average.indices.forEach { index ->
                average[index] /= count
                squaredSum += average[index] * average[index]
            }
            val norm = sqrt(squaredSum)
            if (norm > 0f) average.indices.forEach { index -> average[index] /= norm }
            return average
        }

    val isValidAppearance: Boolean
        get() = observations.size >= MIN_OBSERVATIONS &&
            observations.any { it.face.sharpness >= MIN_SHARPNESS }

    fun append(observation: FaceObservation) {
        check(!closed) { "Cannot append to a closed appearance track" }
        observations += observation
        lastTimestampMs = observation.timestampMs
        lastBoundingBox = Rect(observation.face.boundingBox)
        lastEmbedding = observation.embedding.copyOf()
    }

    fun close() {
        closed = true
    }

    companion object {
        const val MIN_OBSERVATIONS = 2
        const val MIN_SHARPNESS = 0.20f
    }
}
