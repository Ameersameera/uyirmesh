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

    /** Who needs the GPS on (an open SOS, a rescuer on duty) and how often; the shortest interval is used. */
    private val owners = HashMap<String, Long>()
    private var activeInterval = 0L

    /** Keeps the GPS on while [owner] needs fresh positions. Calling it again for the same owner does not stack. */
    @Synchronized
    fun startTracking(owner: String, intervalMs: Long) {
        if (!Perms.hasLocation(ctx)) return
        owners[owner] = intervalMs
        applyTracking()
    }

    @Synchronized
    fun stopTracking(owner: String) {
        if (owners.remove(owner) == null) return
        applyTracking()
    }

    @Synchronized
    fun stopAll() {
        owners.clear()
        applyTracking()
    }

    @SuppressLint("MissingPermission")
    private fun applyTracking() {
        val want = owners.values.minOrNull() ?: 0L
        if (want == activeInterval) return
        if (activeInterval != 0L) {
            try {
                fused.removeLocationUpdates(fusedCallback)
            } catch (_: Exception) {
            }
            try {
                lm.removeUpdates(gpsListener)
            } catch (_: Exception) {
            }
        }
        activeInterval = want
        if (want == 0L) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, want)
                .setMinUpdateIntervalMillis(want / 2)
                .build()
            fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, want, 5f, gpsListener, Looper.getMainLooper())
            }
        } catch (_: Exception) {
        }
    }
}
