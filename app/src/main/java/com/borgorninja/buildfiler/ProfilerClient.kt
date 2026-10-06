package com.borgorninja.buildfiler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProfilerClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun queryBuilding(
        baseUrl: String,
        lat: Double,
        lon: Double,
        heading: Float,
        radius: Float = 80f,
        fov: Float = 60f
    ): Result<ProfileResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trimEnd('/')
            val url = "$cleanBase/profile?lat=$lat&lon=$lon&heading=$heading&radius=$radius&fov=$fov"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BuildFiler-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)

            var primaryTarget: BuildingTarget? = null
            if (!json.isNull("primary_target")) {
                val tObj = json.getJSONObject("primary_target")
                val coords = tObj.getJSONObject("coordinates")
                primaryTarget = BuildingTarget(
                    osmId = tObj.optLong("osm_id", 0L),
                    name = tObj.optString("name", "Unnamed Building"),
                    category = tObj.optString("category", "building"),
                    type = tObj.optString("type", "building"),
                    address = if (tObj.isNull("address")) null else tObj.optString("address"),
                    distanceMeters = tObj.optDouble("distance_meters", 0.0),
                    bearingDegrees = tObj.optDouble("bearing_degrees", 0.0),
                    angleOffset = tObj.optDouble("angle_offset", 0.0),
                    lat = coords.optDouble("lat", 0.0),
                    lon = coords.optDouble("lon", 0.0)
                )
            }

            val facingCount = json.optInt("facing_count", 0)
            val totalNearby = json.optInt("total_nearby", 0)

            Result.success(ProfileResult(primaryTarget, facingCount, totalNearby))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
