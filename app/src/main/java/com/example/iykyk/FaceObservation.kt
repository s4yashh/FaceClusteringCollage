package com.example.iykyk

data class FaceObservation(
    val timestampMs: Long,
    val face: DetectedFace,
    val embedding: FloatArray,
    val frameIndex: Int
)
