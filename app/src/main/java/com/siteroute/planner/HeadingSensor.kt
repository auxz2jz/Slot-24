package com.siteroute.planner

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.*
import kotlin.math.abs

/**
 * Device orientation relative to true north. GPS course is preferred while driving;
 * the compass is used when stopped or when no reliable GPS course is available.
 */
@Composable
fun rememberCompassHeading(enabled: Boolean, position: GeoPoint?): Float? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val gps by rememberUpdatedState(position)

    DisposableEffect(context, enabled) {
        if (!enabled) {
            heading = null
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val listener = object : SensorEventListener {
                private val matrix = FloatArray(9)
                private val remapped = FloatArray(9)
                private val orientation = FloatArray(3)
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val rotationIndex = @Suppress("DEPRECATION") run {
                        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .defaultDisplay.rotation
                    }
                    val axes = when (rotationIndex) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    if (!SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped))
                        return
                    SensorManager.getOrientation(remapped, orientation)
                    var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    gps?.let { point ->
                        degrees += GeomagneticField(point.latitude.toFloat(),
                            point.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
                    }
                    val normalized = ((degrees % 360f) + 360f) % 360f
                    val previous = heading
                    val difference = previous?.let {
                        abs((normalized - it + 540f) % 360f - 180f)
                    } ?: 360f
                    if (difference >= 3f) heading = normalized
                }
            }
            if (rotation != null) manager.registerListener(
                listener, rotation, SensorManager.SENSOR_DELAY_UI
            )
            onDispose { manager.unregisterListener(listener) }
        }
    }
    return heading
}
