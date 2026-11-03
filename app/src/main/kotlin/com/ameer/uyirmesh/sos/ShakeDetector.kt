package com.ameer.uyirmesh.sos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Three strong shakes within 2.5 seconds start the SOS countdown. */
class ShakeDetector(ctx: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val peaks = ArrayDeque<Long>()
    private var lastPeak = 0L
    private var lastFire = 0L
    @Volatile var running = false
        private set

    val available: Boolean get() = accel != null

    fun start() {
        if (running || accel == null) return
        running = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        peaks.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = System.currentTimeMillis()
        if (now - lastPeak < MIN_GAP_MS) return
        lastPeak = now
        peaks.addLast(now)
        while (peaks.isNotEmpty() && now - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size >= SHAKES && now - lastFire > COOLDOWN_MS) {
            lastFire = now
            peaks.clear()
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val THRESHOLD_G = 2.9f
        private const val MIN_GAP_MS = 300L
        private const val WINDOW_MS = 2_500L
        private const val SHAKES = 3
        private const val COOLDOWN_MS = 30_000L
    }
}
