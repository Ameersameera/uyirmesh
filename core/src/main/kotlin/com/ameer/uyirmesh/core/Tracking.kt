package com.ameer.uyirmesh.core

import kotlin.math.ceil

/** One reported position of a rescuer; [at] is when the GPS fix was taken. */
data class Fix(val lat: Double, val lon: Double, val at: Long)

/**
 * Is a rescuer really going to the victim? Uses the positions the rescuer phone reports over the mesh
 * (about once a minute): the distance now, the distance a few minutes earlier, and whether the rescuer
 * has stayed in one place.
 */
object Tracking {
    enum class Trend {
        /** Only one position so far (or too close together in time) to tell. */
        UNKNOWN,
        CLOSER,

        /** Moving, but the distance to the victim stays about the same (e.g. going around an obstacle). */
        SAME,
        AWAY,

        /** Has not moved for a few minutes. */
        STILL,

        /** Within [ARRIVED_M] of the victim. */
        ARRIVED,
    }

    data class Approach(
        val distanceM: Double,
        val trend: Trend,
        /** Distance at the comparison position a few minutes earlier, or null. */
        val earlierM: Double?,
        /** Minutes between the comparison position and the latest one. */
        val overMin: Int,
        /** Minutes the rescuer has stayed within [STILL_RADIUS_M] of the latest position. */
        val stillMin: Int,
        /** When the latest position was taken. */
        val at: Long,
        /** Estimated minutes to reach the victim (0 = there), -1 if unknown. */
        val etaMin: Int,
    )

    /** Closer than this counts as "reached the victim" (GPS is good to a few tens of metres). */
    const val ARRIVED_M = 60.0

    /** Staying within this radius counts as "not moving" (normal GPS jitter is smaller). */
    const val STILL_RADIUS_M = 40.0

    /** The distance must change by at least this much to count as closer or farther. */
    const val CHANGE_M = 45.0

    const val LOOKBACK_MS = 5 * 60_000L
    private const val MIN_SPAN_MS = 90_000L
    private const val STILL_AFTER_MS = 4 * 60_000L

    /**
     * How the rescuer with positions [fixes] is doing on the way to [target]. Null when there is no valid
     * position. [speedKmh] is the team speed used for the ETA when there is no measured approach speed.
     */
    fun approach(target: LatLon, fixes: List<Fix>, speedKmh: Double): Approach? {
        val pts = fixes.filter { Geo.isValid(it.lat, it.lon) }.sortedBy { it.at }
        val cur = pts.lastOrNull() ?: return null
        val d = Geo.distanceM(target.lat, target.lon, cur.lat, cur.lon)
        // How long has the rescuer stayed near the latest position?
        var stillFrom = cur.at
        for (i in pts.indices.reversed()) {
            if (Geo.distanceM(pts[i].lat, pts[i].lon, cur.lat, cur.lon) > STILL_RADIUS_M) break
            stillFrom = pts[i].at
        }
        val stillMs = cur.at - stillFrom
        // Compare with the newest position at least LOOKBACK_MS older (or the oldest one, if old enough).
        val ref = pts.lastOrNull { it.at <= cur.at - LOOKBACK_MS } ?: pts.first().takeIf { cur.at - it.at >= MIN_SPAN_MS }
        val earlier = ref?.let { Geo.distanceM(target.lat, target.lon, it.lat, it.lon) }
        val spanMs = if (ref != null) cur.at - ref.at else 0L
        val trend = when {
            d <= ARRIVED_M -> Trend.ARRIVED
            earlier == null -> Trend.UNKNOWN
            stillMs >= STILL_AFTER_MS -> Trend.STILL
            earlier - d >= CHANGE_M -> Trend.CLOSER
            d - earlier >= CHANGE_M -> Trend.AWAY
            else -> Trend.SAME
        }
        val eta = when (trend) {
            Trend.ARRIVED -> 0
            Trend.CLOSER -> {
                val metresPerMin = (earlier!! - d) / (spanMs / 60_000.0)
                if (metresPerMin >= 5) ceil(d / metresPerMin).toInt().coerceIn(1, 24 * 60) else Geo.etaMinutes(d, speedKmh)
            }
            else -> Geo.etaMinutes(d, speedKmh)
        }
        return Approach(d, trend, earlier, (spanMs / 60_000).toInt(), (stillMs / 60_000).toInt(), cur.at, eta)
    }
}

/**
 * Which of two positions from this phone to keep. A precise GPS fix should not be replaced by a rough
 * network position just because the rough one is newer: an old fix only gets less certain as time passes
 * (people move up to about [DRIFT_M_PER_S] on foot), so the rough one wins only once it is really better.
 */
object FixChoice {
    /** Within this the position counts as precise (GPS, or good Wi-Fi positioning). */
    const val PRECISE_M = 100.0

    /** Positions rougher than this are not used for distances, rescuer tracking or "how far away" updates. */
    const val USABLE_M = 200.0

    const val DRIFT_M_PER_S = 1.5

    /** Accuracy used when a position does not say how exact it is. */
    private const val UNKNOWN_M = 5_000.0

    /** True if the new position (accuracy in metres, time) should replace the current one. */
    fun isBetter(newAccM: Double?, newAt: Long, curAccM: Double?, curAt: Long): Boolean {
        val dt = newAt - curAt
        if (dt <= -2 * 60_000) return false
        if (dt >= 30 * 60_000) return true
        val n = newAccM ?: UNKNOWN_M
        val c = curAccM ?: UNKNOWN_M
        return n <= c + DRIFT_M_PER_S * (maxOf(dt, 0L) / 1000.0)
    }

    fun isPrecise(accM: Double?): Boolean = accM != null && accM <= PRECISE_M
    fun isUsable(accM: Double?): Boolean = accM != null && accM <= USABLE_M
}
