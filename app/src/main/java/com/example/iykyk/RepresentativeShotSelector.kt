package com.example.iykyk

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.min

class RepresentativeShotSelector {

    /** Returns at most one generous-crop representative for each person in one video. */
    fun selectForVideo(
        clusters: List<PersonCluster>,
        videoId: String
    ): List<RepresentativeShot> {
        return clusters.mapNotNull { cluster ->
            val candidates = cluster.observations
                .asSequence()
                .filter { it.videoId == videoId }
                .filter { it.face.sharpness >= MIN_SHARPNESS }
                .map { observation ->
                    val score = scoreObservation(observation)
                    Log.d(
                        TAG,
                        "CANDIDATE personId=${cluster.personId} videoId=$videoId " +
                            "timestamp=${observation.timestampMs} score=$score"
                    )
                    observation to score
                }
                .toList()

            val best = candidates.maxWithOrNull(
                compareBy<Pair<FaceObservation, Float>> { it.second }
                    .thenByDescending { it.first.timestampMs }
            ) ?: return@mapNotNull null

            val (observation, score) = best
            Log.d(
                TAG,
                "SELECTED personId=${cluster.personId} videoId=$videoId " +
                    "timestamp=${observation.timestampMs} score=$score"
            )
            RepresentativeShot(
                personId = cluster.personId,
                videoId = videoId,
                timestamp = observation.timestampMs,
                image = observation.face.generousCrop,
                score = score
            )
        }
    }

    fun scoreObservation(observation: FaceObservation): Float {
        val face = observation.face
        val yawScore = 1f - min(abs(face.headYaw) / MAX_YAW_DEGREES, 1f)
        val rollScore = 1f - min(abs(face.headRoll) / MAX_ROLL_DEGREES, 1f)
        val eyesOpenAverage = (
            face.leftEyeOpenProbability + face.rightEyeOpenProbability
            ) / 2f
        val faceAreaRatio = if (observation.frameWidth > 0 && observation.frameHeight > 0) {
            face.boundingBox.width().toFloat() * face.boundingBox.height().toFloat() /
                (observation.frameWidth.toFloat() * observation.frameHeight.toFloat())
        } else {
            0f
        }
        val faceSizeRatio = min(faceAreaRatio / MAX_FACE_AREA_RATIO, 1f)
        val faceNotClipped = if (isInsideFrame(
                face.boundingBox,
                observation.frameWidth,
                observation.frameHeight
            )
        ) {
            1f
        } else {
            0f
        }

        return (
            YAW_WEIGHT * yawScore +
                ROLL_WEIGHT * rollScore +
                SHARPNESS_WEIGHT * face.sharpness +
                EYES_WEIGHT * eyesOpenAverage +
                SMILE_WEIGHT * face.smileProbability +
                CLIPPED_WEIGHT * faceNotClipped +
                FACE_SIZE_WEIGHT * faceSizeRatio
            ).coerceIn(0f, 1f)
    }

    private fun isInsideFrame(box: Rect, frameWidth: Int, frameHeight: Int): Boolean {
        return frameWidth > 0 && frameHeight > 0 &&
            box.left >= 0 && box.top >= 0 &&
            box.right <= frameWidth && box.bottom <= frameHeight
    }

    companion object {
        const val MIN_SHARPNESS = 0.20f
        private const val MAX_YAW_DEGREES = 45f
        private const val MAX_ROLL_DEGREES = 30f
        private const val MAX_FACE_AREA_RATIO = 0.25f
        private const val YAW_WEIGHT = 0.22f
        private const val ROLL_WEIGHT = 0.10f
        private const val SHARPNESS_WEIGHT = 0.25f
        private const val EYES_WEIGHT = 0.15f
        private const val SMILE_WEIGHT = 0.08f
        private const val CLIPPED_WEIGHT = 0.12f
        private const val FACE_SIZE_WEIGHT = 0.08f
        private const val TAG = "IYKYK_REP"
    }
}
