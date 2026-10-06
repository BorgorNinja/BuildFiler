package com.borgorninja.buildfiler

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.borgorninja.buildfiler.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var sensorTracker: SensorTracker
    private lateinit var rfScanner: RfScanner
    private val profilerClient = ProfilerClient()

    private var locationOverlay: MyLocationNewOverlay? = null
    private var targetMarker: Marker? = null
    private var conePolygon: Polygon? = null

    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private var currentHeading: Float = 0f

    private var isProfilerMode: Boolean = false

    private var lastQueryHeading: Float = -999f
    private var lastQueryLat: Double = 0.0
    private var lastQueryLon: Double = 0.0
    private var lastQueryTime: Long = 0

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREF_SERVER_URL = "pref_server_url"
        private const val PREF_RADIUS = "pref_radius"
        private const val PREF_FOV = "pref_fov"
        private const val DEFAULT_SERVER = "http://borgorninja.duckdns.org:8090"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OSMDroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("buildfiler_prefs", Context.MODE_PRIVATE)
        rfScanner = RfScanner(this)

        setupMap()
        setupSensors()
        setupUI()
        checkLocationPermissions()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(18.5)

        // Default initial point (Imus / Cavite)
        val startPoint = GeoPoint(14.4172, 120.9416)
        binding.mapView.controller.setCenter(startPoint)

        val provider = GpsMyLocationProvider(this)
        locationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
            enableMyLocation()
            enableFollowLocation()
            setDrawAccuracyEnabled(true)
        }
        binding.mapView.overlays.add(locationOverlay)
    }

    private fun setupSensors() {
        sensorTracker = SensorTracker(this) { heading ->
            currentHeading = heading
            val cardinals = getCardinalDirection(heading)
            binding.tvHeading.text = "🧭 Heading: ${heading.toInt()}° $cardinals"
            drawFacingCone()
            checkTriggerQuery()
        }
    }

    private fun setupUI() {
        binding.fabRecenter.setOnClickListener {
            val lat = currentLat
            val lon = currentLon
            if (lat != null && lon != null) {
                binding.mapView.controller.animateTo(GeoPoint(lat, lon))
            } else {
                Toast.makeText(this, "Acquiring GPS fix…", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnModeToggle.setOnClickListener {
            isProfilerMode = !isProfilerMode
            updateModeUI()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun updateModeUI() {
        if (isProfilerMode) {
            binding.btnModeToggle.text = "🗺️ Map"
            binding.mapView.visibility = View.GONE
            binding.fabRecenter.visibility = View.GONE
            binding.cardProfile.visibility = View.GONE
            binding.profilerModeView.visibility = View.VISIBLE
        } else {
            binding.btnModeToggle.text = "📡 Profiler"
            binding.mapView.visibility = View.VISIBLE
            binding.fabRecenter.visibility = View.VISIBLE
            binding.cardProfile.visibility = View.VISIBLE
            binding.profilerModeView.visibility = View.GONE
        }
    }

    private fun checkLocationPermissions() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED || coarse != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, this)

        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
    }

    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        binding.tvGpsStatus.text = "📍 GPS: %.5f, %.5f (±%.1fm)".format(location.latitude, location.longitude, location.accuracy)

        drawFacingCone()
        checkTriggerQuery()
    }

    private fun checkTriggerQuery() {
        val lat = currentLat ?: return
        val lon = currentLon ?: return
        val now = System.currentTimeMillis()

        // Debounce: minimum 800ms between calls, or significant heading change (> 8 deg), or moved > 5m
        val headingDelta = Math.abs(currentHeading - lastQueryHeading)
        val distMoved = FloatArray(1)
        Location.distanceBetween(lat, lon, lastQueryLat, lastQueryLon, distMoved)

        if (now - lastQueryTime > 800 && (headingDelta > 8f || distMoved[0] > 4f)) {
            lastQueryHeading = currentHeading
            lastQueryLat = lat
            lastQueryLon = lon
            lastQueryTime = now
            triggerProfilerQuery(lat, lon, currentHeading)
        }
    }

    private fun triggerProfilerQuery(lat: Double, lon: Double, heading: Float) {
        val serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        val radius = prefs.getFloat(PREF_RADIUS, 80f)
        val fov = prefs.getFloat(PREF_FOV, 60f)

        binding.pbScanning.visibility = View.VISIBLE
        binding.pbProfilerScanning.visibility = View.VISIBLE

        activityScope.launch {
            val result = profilerClient.queryBuilding(serverUrl, lat, lon, heading, radius, fov)
            binding.pbScanning.visibility = View.GONE
            binding.pbProfilerScanning.visibility = View.GONE

            result.onSuccess { data ->
                val target = data.primaryTarget
                if (target != null) {
                    // Map Mode HUD (Compact)
                    binding.tvTargetName.text = target.name
                    binding.tvTargetCategory.text = "${target.category.uppercase()} • ${target.type.replace('_', ' ').uppercase()}"
                    binding.tvTargetCategory.visibility = View.VISIBLE
                    binding.tvTargetDistance.text = "🎯 %.1fm away • Bearing: %.0f° (Offset: %.1f°)".format(
                        target.distanceMeters,
                        target.bearingDegrees,
                        target.angleOffset
                    )
                    if (!target.address.isNullOrBlank()) {
                        binding.tvTargetAddress.visibility = View.VISIBLE
                        binding.tvTargetAddress.text = "📍 ${target.address}"
                    } else {
                        binding.tvTargetAddress.visibility = View.GONE
                    }

                    // Small description for Map Mode
                    if (!target.description.isNullOrBlank()) {
                        binding.tvTargetDescription.visibility = View.VISIBLE
                        binding.tvTargetDescription.text = target.description
                    } else {
                        binding.tvTargetDescription.visibility = View.GONE
                    }

                    // Profiler Mode (Detailed View)
                    binding.tvProfilerTargetName.text = target.name
                    binding.tvProfilerTargetCategory.text = "${target.category.uppercase()} • ${target.type.replace('_', ' ').uppercase()}"
                    binding.tvProfilerTargetCategory.visibility = View.VISIBLE
                    binding.tvProfilerTargetDistance.text = "🎯 %.1fm away • Bearing: %.0f° (Offset: %.1f°)".format(
                        target.distanceMeters,
                        target.bearingDegrees,
                        target.angleOffset
                    )
                    if (!target.address.isNullOrBlank()) {
                        binding.tvProfilerTargetAddress.visibility = View.VISIBLE
                        binding.tvProfilerTargetAddress.text = "📍 ${target.address}"
                    } else {
                        binding.tvProfilerTargetAddress.visibility = View.GONE
                    }

                    binding.tvProfilerFullDesc.text = if (!target.description.isNullOrBlank()) {
                        target.description
                    } else {
                        "No detailed profile available for this structure."
                    }

                    if (target.details.isNotEmpty()) {
                        binding.tvProfilerDetails.visibility = View.VISIBLE
                        binding.tvProfilerDetails.text = target.details.joinToString(" • ")
                    } else {
                        binding.tvProfilerDetails.visibility = View.GONE
                    }

                    // Populate other candidates in cone
                    val otherCandidates = data.facingCandidates.filter { it.osmId != target.osmId }
                    binding.tvCandidatesTitle.text = "DETECTED IN CONE (${data.facingCandidates.size})"
                    if (otherCandidates.isNotEmpty()) {
                        val listStr = otherCandidates.take(6).joinToString("\n\n") { c ->
                            val descLine = if (!c.description.isNullOrBlank()) "\n  ${c.description}" else ""
                            "• ${c.name} (${c.type.replace('_', ' ')})\n  %.1fm away • Bearing: %.0f°$descLine".format(
                                c.distanceMeters,
                                c.bearingDegrees
                            )
                        }
                        binding.tvCandidatesList.text = listStr
                    } else {
                        binding.tvCandidatesList.text = "No other structures detected in forward cone."
                    }

                    updateTargetMarker(target)
                } else {
                    // Map Mode reset
                    binding.tvTargetName.text = "No building directly in front"
                    binding.tvTargetCategory.text = "SCANNING"
                    binding.tvTargetDistance.text = "${data.totalNearby} buildings nearby • Turn towards one"
                    binding.tvTargetAddress.visibility = View.GONE
                    binding.tvTargetDescription.visibility = View.GONE

                    // Profiler Mode reset
                    binding.tvProfilerTargetName.text = "No building directly in front"
                    binding.tvProfilerTargetCategory.text = "SCANNING"
                    binding.tvProfilerTargetDistance.text = "${data.totalNearby} buildings nearby • Turn towards one"
                    binding.tvProfilerTargetAddress.visibility = View.GONE
                    binding.tvProfilerFullDesc.text = "Point your device towards a structure or establishment to inspect."
                    binding.tvProfilerDetails.visibility = View.GONE
                    binding.tvCandidatesTitle.text = "DETECTED IN CONE (0)"
                    binding.tvCandidatesList.text = "No structures currently within forward cone."

                    removeTargetMarker()
                }
            }.onFailure { err ->
                binding.tvTargetDistance.text = "Profiler connection error: ${err.message}"
                binding.tvTargetDescription.visibility = View.GONE
                binding.tvProfilerTargetDistance.text = "Profiler connection error: ${err.message}"
                binding.tvProfilerFullDesc.text = "Unable to connect to profiler API. Verify server URL in settings."
                binding.tvProfilerDetails.visibility = View.GONE
            }

            // Trigger background ctOS RF scan
            activityScope.launch(Dispatchers.IO) {
                val rfList = rfScanner.scanNearby()
                withContext(Dispatchers.Main) {
                    updateRfUI(rfList)
                }
            }
        }
    }

    private fun updateRfUI(devices: List<RfDevice>) {
        binding.tvRfTitle.text = "📡 CTOS WIRELESS RF SNIFFER (${devices.size} NODES)"
        if (devices.isNotEmpty()) {
            val str = devices.joinToString("\n\n") { d ->
                "⚡ [${d.type}] ${d.name}\n   ADDR: ${d.address} • Signal: ${d.rssi} dBm"
            }
            binding.tvRfList.text = str
        } else {
            binding.tvRfList.text = "Scanning local 2.4GHz / 5GHz and BLE radio spectrum…"
        }
    }

    private fun drawFacingCone() {
        val lat = currentLat ?: return
        val lon = currentLon ?: return
        val radius = prefs.getFloat(PREF_RADIUS, 80f).toDouble()
        val fov = prefs.getFloat(PREF_FOV, 60f).toDouble()

        if (conePolygon == null) {
            conePolygon = Polygon(binding.mapView).apply {
                fillPaint.color = Color.parseColor("#330284C7")
                outlinePaint.color = Color.parseColor("#8038BDF8")
                outlinePaint.strokeWidth = 3f
            }
            binding.mapView.overlays.add(conePolygon)
        }

        val points = mutableListOf<GeoPoint>()
        points.add(GeoPoint(lat, lon))

        val halfFov = fov / 2.0
        val startAngle = currentHeading.toDouble() - halfFov
        val endAngle = currentHeading.toDouble() + halfFov

        val steps = 8
        val stepSize = (endAngle - startAngle) / steps

        val rEarth = 6371000.0
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        for (i in 0..steps) {
            val angle = Math.toRadians(startAngle + i * stepSize)
            val dLat = (radius / rEarth) * cos(angle)
            val dLon = (radius / (rEarth * cos(latRad))) * sin(angle)

            val pLat = Math.toDegrees(latRad + dLat)
            val pLon = Math.toDegrees(lonRad + dLon)
            points.add(GeoPoint(pLat, pLon))
        }

        conePolygon?.points = points
        binding.mapView.invalidate()
    }

    private fun updateTargetMarker(target: BuildingTarget) {
        val geo = GeoPoint(target.lat, target.lon)
        if (targetMarker == null) {
            targetMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(targetMarker)
        }
        targetMarker?.apply {
            position = geo
            title = target.name
            snippet = "${target.type} (%.1fm)".format(target.distanceMeters)
        }
        binding.mapView.invalidate()
    }

    private fun removeTargetMarker() {
        targetMarker?.let {
            binding.mapView.overlays.remove(it)
            targetMarker = null
            binding.mapView.invalidate()
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etRadius = dialogView.findViewById<EditText>(R.id.etRadius)
        val etFov = dialogView.findViewById<EditText>(R.id.etFov)

        etUrl.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER))
        etRadius.setText(prefs.getFloat(PREF_RADIUS, 80f).toInt().toString())
        etFov.setText(prefs.getFloat(PREF_FOV, 60f).toInt().toString())

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("BuildFiler Configuration")
            .setPositiveButton("Save") { _, _ ->
                val newUrl = etUrl.text.toString().trim()
                val newRadius = etRadius.text.toString().toFloatOrNull() ?: 80f
                val newFov = etFov.text.toString().toFloatOrNull() ?: 60f

                prefs.edit()
                    .putString(PREF_SERVER_URL, if (newUrl.isNotBlank()) newUrl else DEFAULT_SERVER)
                    .putFloat(PREF_RADIUS, newRadius)
                    .putFloat(PREF_FOV, newFov)
                    .apply()

                Toast.makeText(this, "Settings updated", Toast.LENGTH_SHORT).show()
                checkTriggerQuery()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCardinalDirection(heading: Float): String {
        return when (((heading + 22.5f) % 360) / 45) {
            0f -> "N"
            1f -> "NE"
            2f -> "E"
            3f -> "SE"
            4f -> "S"
            5f -> "SW"
            6f -> "W"
            7f -> "NW"
            else -> "N"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        sensorTracker.start()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        sensorTracker.stop()
        locationOverlay?.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
