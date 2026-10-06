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
    val lon: Double,
    val description: String? = null,
    val details: List<String> = emptyList()
)

data class ProfileResult(
    val primaryTarget: BuildingTarget?,
    val facingCandidates: List<BuildingTarget> = emptyList(),
    val facingCount: Int,
    val totalNearby: Int
)
