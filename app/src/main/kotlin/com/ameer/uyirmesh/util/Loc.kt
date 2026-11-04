package com.ameer.uyirmesh.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.ameer.uyirmesh.core.FixChoice
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
 * can take a minute when there is no network help, so the last good position is always kept, and a
 * rough network position (hundreds of metres to kilometres off) never replaces a recent GPS fix.
 */
class Loc(private val ctx: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    private val _last = MutableStateFlow<Location?>(null)
    val lastFlow: StateFlow<Location?> = _last
    val last: Location? get() = _last.value
    val lastLatLon: LatLon? get() = last?.let { LatLon(it.latitude, it.longitude) }

    private var gpsOn = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { offer(it) }
        }
    }
    private val gpsListener = LocationListener { offer(it) }

    fun offer(l: Location) {
        if (l.latitude == 0.0 && l.longitude == 0.0) return
        val cur = _last.value
        if (cur == null || FixChoice.isBetter(accuracyOf(l), l.time, accuracyOf(cur), cur.time)) _last.value = l
    }

    /** Age of the last position in ms, or Long.MAX_VALUE. */
    fun ageMs(): Long = last?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE

    /** How exact the last position is, in metres (null if unknown or no position). */
    fun accuracyM(): Double? = last?.let { accuracyOf(it) }

    /** The last position if it is exact enough to work out distances (GPS or good Wi-Fi), else null. */
    fun usable(): Location? = last?.takeIf { FixChoice.isUsable(accuracyOf(it)) }

    @VisibleForTesting
    fun resetForTest() {
        _last.value = null
    }

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

    /** Asks again for positions, e.g. after "precise location" or the phone's location was turned on. */
    @Synchronized
    fun restart() {
        applyTracking(force = true)
        seed()
    }

    /** Starts the satellite GPS listener if the GPS was turned on after tracking started. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun ensureGps() {
        if (activeInterval == 0L || gpsOn || !Perms.hasLocation(ctx)) return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, activeInterval, 5f, gpsListener, Looper.getMainLooper())
                gpsOn = true
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyTracking(force: Boolean = false) {
        val want = owners.values.minOrNull() ?: 0L
        if (want == activeInterval && !force) return
        if (activeInterval != 0L || force) {
            try {
                fused.removeLocationUpdates(fusedCallback)
            } catch (_: Exception) {
            }
            try {
                lm.removeUpdates(gpsListener)
            } catch (_: Exception) {
            }
            gpsOn = false
        }
        activeInterval = want
        if (want == 0L || !Perms.hasLocation(ctx)) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, want)
                .setMinUpdateIntervalMillis(want / 2)
                .setWaitForAccurateLocation(true)
                .build()
            fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        ensureGps()
    }

    companion object {
        fun accuracyOf(l: Location): Double? = if (l.hasAccuracy()) l.accuracy.toDouble() else null
    }
}
