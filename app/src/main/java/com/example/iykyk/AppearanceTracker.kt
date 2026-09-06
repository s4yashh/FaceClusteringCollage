package com.example.iykyk

import android.graphics.Rect
import android.util.Log

class AppearanceTracker {
    private val activeTracks = mutableListOf<AppearanceTrack>()
    private val finishedTracks = mutableListOf<AppearanceTrack>()
    private var nextTrackId = 1

    val completedTracks: List<AppearanceTrack>
        get() = finishedTracks

    fun processFrame(timestampMs: Long, observations: List<FaceObservation>) {
        activeTracks.toList().forEach { track ->
            if (timestampMs - track.lastTimestampMs > TRACK_TIMEOUT_MS) closeTrack(track)
        }

        data class Candidate(
            val track: AppearanceTrack,
            val observation: FaceObservation,
            val observationIndex: Int,
            val iou: Float,
            val similarity: Float,
            val score: Float
        )

        val candidates = activeTracks.flatMap { track ->
            observations.mapIndexedNotNull { index, observation ->
                val iou = intersectionOverUnion(observation.face.boundingBox, track.lastBoundingBox)
                val similarity = cosineSimilarity(observation.embedding, track.lastEmbedding)
                if (iou >= MIN_IOU && similarity >= MIN_COSINE_SIMILARITY) {
                    Candidate(track, observation, index, iou, similarity, iou * similarity)
                } else {
                    null
                }
            }
        }.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.track.id }
                .thenBy { it.observationIndex }
        )

        val matchedTracks = mutableSetOf<AppearanceTrack>()
        val matchedObservations = mutableSetOf<Int>()
        candidates.forEach { candidate ->
            if (matchedTracks.add(candidate.track) && matchedObservations.add(candidate.observationIndex)) {
                candidate.track.append(candidate.observation)
                Log.d(
                    TAG,
                    "TRACK_MATCH trackId=${candidate.track.id} timestamp=$timestampMs " +
                        "iou=${candidate.iou} cosine=${candidate.similarity} score=${candidate.score}"
                )
            }
        }

        observations.forEachIndexed { index, observation ->
            if (index !in matchedObservations) {
                val track = AppearanceTrack(nextTrackId++, observation)
                activeTracks += track
                Log.d(TAG, "TRACK_START trackId=${track.id} timestamp=$timestampMs faceIndex=$index")
            }
        }
    }

    fun finish() {
        activeTracks.toList().forEach(::closeTrack)
    }

    private fun closeTrack(track: AppearanceTrack) {
        if (track.closed) return
        track.close()
        activeTracks.remove(track)
        finishedTracks += track
        Log.d(
            TAG,
            "TRACK_END trackId=${track.id} start=${track.observations.first().timestampMs} " +
                "end=${track.lastTimestampMs} observations=${track.frameCount} " +
                "valid=${track.isValidAppearance}"
        )
    }

    companion object {
        private const val MIN_IOU = 0.30f
        private const val MIN_COSINE_SIMILARITY = 0.50f
        private const val TRACK_TIMEOUT_MS = 500L
        private const val TAG = "IYKYK_TRACK"

        fun intersectionOverUnion(first: Rect, second: Rect): Float {
            val left = maxOf(first.left, second.left)
            val top = maxOf(first.top, second.top)
            val right = minOf(first.right, second.right)
            val bottom = minOf(first.bottom, second.bottom)
            val intersectionWidth = (right - left).coerceAtLeast(0)
            val intersectionHeight = (bottom - top).coerceAtLeast(0)
            val intersection = intersectionWidth.toLong() * intersectionHeight.toLong()
            val union = first.width().toLong() * first.height().toLong() +
                second.width().toLong() * second.height().toLong() - intersection
            return if (union <= 0L) 0f else intersection.toFloat() / union.toFloat()
        }
    }
}
