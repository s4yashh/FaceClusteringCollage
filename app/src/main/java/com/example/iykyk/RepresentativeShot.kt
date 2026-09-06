package com.example.iykyk

import android.graphics.Bitmap

data class RepresentativeShot(
    val personId: Int,
    val videoId: String,
    val timestamp: Long,
    val image: Bitmap,
    val score: Float
)
