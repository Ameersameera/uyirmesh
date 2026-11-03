package com.ameer.uyirmesh.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.ameer.uyirmesh.core.LatLon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS position. Satellite GPS works without internet or mobile network; the first fix outdoors
 * can take a minute when there is no network help, so the last good position is always kept.
 */
class Loc(private val ctx: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    private val _last = MutableStateFlow<Location?>(null)
    val lastFlow: StateFlow<Location?> = _last
    val last: Location? get() = _last.value
    val lastLatLon: LatLon? get() = last?.let { LatLon(it.latitude, it.longitude) }

    private var trackers = 0

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { offer(it) }
        }
    }
    private val gpsListener = LocationListener { offer(it) }

    fun offer(l: Location) {
        if (l.latitude == 0.0 && l.longitude == 0.0) return
        val cur = _last.value
        if (cur == null || isBetter(l, cur)) _last.value = l
    }

    private fun isBetter(n: Location, o: Location): Boolean {
        val newer = n.time - o.time
        if (newer > 2 * 60_000) return true
        if (newer < -2 * 60_000) return false
        return n.accuracy <= o.accuracy || (newer > 0 && n.accuracy < o.accuracy * 1.5f)
    }

    /** Age of the last position in ms, or Long.MAX_VALUE. */
    fun ageMs(): Long = last?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE

    @SuppressLint("MissingPermission")
    fun seed() {
        if (!Perms.hasLocation(ctx)) return
        try {
            fused.lastLocation.addOnSuccessListener { it?.let(::offer) }
        } catch (_: Exception) {
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                lm.getLastKnownLocation(p)?.let(::offer)
            } catch (_: Exception) {
            }
        }
    }

    /** Asks for a fresh position; [cb] runs on the main thread with the best position known by then. */
    @SuppressLint("MissingPermission")
    fun requestOnce(timeoutMs: Long, cb: (Location?) -> Unit) {
        if (!Perms.hasLocation(ctx)) {
            cb(last)
            return
        }
        seed()
        var done = false
        fun finish(l: Location?) {
            if (done) return
            done = true
            l?.let(::offer)
            cb(last)
        }
        try {
            val cts = CancellationTokenSource()
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { finish(it) }
                .addOnFailureListener { finish(null) }
            main.postDelayed({
                cts.cancel()
                finish(null)
            }, timeoutMs)
        } catch (e: Exception) {
            finish(null)
        }
    }

    /** Keeps the GPS on while someone needs fresh positions (SOS active, rescuer on duty). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startTracking(intervalMs: Long) {
        if (!Perms.hasLocation(ctx)) return
        trackers++
        if (trackers > 1) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 5f, gpsListener, Looper.getMainLooper())
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stopTracking() {
        if (trackers == 0) return
        trackers--
        if (trackers > 0) return
        try {
            fused.removeLocationUpdates(fusedCallback)
        } catch (_: Exception) {
        }
        try {
            lm.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stopAll() {
        trackers = 1
        stopTracking()
    }
}
