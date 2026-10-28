package com.airgesture.transfer.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs

/**
 * Linear-acceleration reader on its own looper thread.
 *
 * The gravity-removed z axis is inverted so that pushing the phone *away* from
 * the user reports a positive value, which is the same sign convention the
 * vision pipeline uses for depth. A short low-pass removes the hand tremor that
 * would otherwise dominate the signal.
 */
class ThrustSensor(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val thread = HandlerThread("airgesture-accel").apply { start() }
    private val handler = Handler(thread.looper)

    private var filtered = 0f

    /** Positive = device accelerated forward (away from the user), m/s². */
    @Volatile
    var forwardAccel: Float = 0f
        private set

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        val manager = sensorManager ?: return
        val target = sensor ?: return
        manager.registerListener(this, target, SENSOR_PERIOD_US, handler)
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
        forwardAccel = 0f
        filtered = 0f
    }

    fun release() {
        stop()
        runCatching { thread.quitSafely() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        val z = -event.values[2] // convert to "forward" convention
        filtered += LOW_PASS * (z - filtered)
        if (abs(filtered) < NOISE_FLOOR) filtered = 0f
        forwardAccel = filtered
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SENSOR_PERIOD_US = 20_000 // 50 Hz, negligible battery cost
        private const val LOW_PASS = 0.35f
        private const val NOISE_FLOOR = 0.25f
    }
}
