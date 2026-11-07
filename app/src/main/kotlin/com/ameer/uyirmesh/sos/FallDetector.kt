package com.ameer.uyirmesh.sos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hard fall or crash: a short free fall (the phone feels almost no gravity), then a strong impact, then the
 * phone lies still (the person may be hurt). Only then [onFall] is called; the caller starts a long countdown
 * so a dropped phone is easy to cancel. Uses the same accelerometer as shake SOS; no permission is needed.
 */
class FallDetector(ctx: Context, private val onFall: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var freeFallStart = 0L
    private var fallEnd = 0L
    private var impactAt = 0L
    private var stillSamples = 0
    private var totalSamples = 0
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
        reset()
    }

    private fun reset() {
        freeFallStart = 0L
        fallEnd = 0L
        impactAt = 0L
        stillSamples = 0
        totalSamples = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        onSample(System.currentTimeMillis(), g)
    }

    /** One accelerometer reading in g (1.0 = resting). Separate from Android so the logic is easy to follow. */
    fun onSample(now: Long, g: Float) {
        when {
            impactAt != 0L -> {
                // After the impact: is the phone (and maybe the person) lying still?
                val since = now - impactAt
                if (since < SETTLE_MS) return
                totalSamples++
                if (abs(g - 1f) < STILL_TOLERANCE_G) stillSamples++
                if (abs(g - 1f) > MOVING_G) {
                    // Picked up again or walking: not a fall that needs help.
                    reset()
                    return
                }
                if (since >= SETTLE_MS + STILL_WINDOW_MS) {
                    val still = totalSamples > 0 && stillSamples >= totalSamples * STILL_SHARE
                    reset()
                    if (still && now - lastFire > COOLDOWN_MS) {
                        lastFire = now
                        onFall()
                    }
                }
            }
            fallEnd != 0L -> {
                if (g > IMPACT_G) {
                    impactAt = now
                    fallEnd = 0L
                } else if (now - fallEnd > IMPACT_WINDOW_MS) {
                    reset()
                }
            }
            g < FREE_FALL_G -> if (freeFallStart == 0L) freeFallStart = now
            freeFallStart != 0L -> {
                if (now - freeFallStart >= MIN_FREE_FALL_MS) fallEnd = now
                freeFallStart = 0L
                if (fallEnd != 0L && g > IMPACT_G) {
                    impactAt = now
                    fallEnd = 0L
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val FREE_FALL_G = 0.45f
        private const val MIN_FREE_FALL_MS = 60L
        private const val IMPACT_G = 2.5f
        private const val IMPACT_WINDOW_MS = 1_200L
        private const val SETTLE_MS = 1_000L
        private const val STILL_WINDOW_MS = 2_500L
        private const val STILL_TOLERANCE_G = 0.3f
        private const val STILL_SHARE = 0.85
        private const val MOVING_G = 0.9f
        private const val COOLDOWN_MS = 60_000L
    }
}
