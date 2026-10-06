package com.borgorninja.buildfiler

data class BuildingTarget(
    val osmId: Long,
    val name: String,
    val category: String,
    val type: String,
    val address: String?,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val angleOffset: Double,
    val lat: Double,
    val lon: Double
)

data class ProfileResult(
    val primaryTarget: BuildingTarget?,
    val facingCount: Int,
    val totalNearby: Int
)
