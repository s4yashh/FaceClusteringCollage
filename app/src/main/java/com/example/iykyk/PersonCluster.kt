package com.example.iykyk

/** Input produced by a later identity-clustering step. This class does not cluster tracks. */
data class PersonCluster(
    val personId: Int,
    val observations: List<FaceObservation>,
    val appearanceCount: Int = 0
)
