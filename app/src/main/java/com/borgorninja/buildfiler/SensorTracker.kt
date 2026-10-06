package com.borgorninja.buildfiler

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class SensorTracker(
    context: Context,
    private val onHeadingChanged: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var gravityValues: FloatArray? = null
    private var geomagneticValues: FloatArray? = null

    private var lastHeading: Float = 0f

    fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            dispatchHeading(azimuth)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gravityValues = event.values.clone()
            computeFallbackOrientation()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagneticValues = event.values.clone()
            computeFallbackOrientation()
        }
    }

    private fun computeFallbackOrientation() {
        val grav = gravityValues ?: return
        val mag = geomagneticValues ?: return
        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, grav, mag)) {
            val actualOrientation = FloatArray(3)
            SensorManager.getOrientation(r, actualOrientation)
            var azimuth = Math.toDegrees(actualOrientation[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            dispatchHeading(azimuth)
        }
    }

    private fun dispatchHeading(heading: Float) {
        // Low pass filter smoothing
        val diff = Math.abs(heading - lastHeading)
        if (diff > 1.5f || (diff > 355f)) {
            lastHeading = heading
            onHeadingChanged(heading)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
