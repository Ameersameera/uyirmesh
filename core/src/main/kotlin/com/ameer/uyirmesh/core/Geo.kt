package com.ameer.uyirmesh.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine formula). */
    fun distanceM(a: LatLon, b: LatLon): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from a to b in degrees, 0 = north, clockwise. */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compassName(deg: Double): String = compass[(((deg % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    fun formatDistance(m: Double): String = when {
        m.isNaN() -> "-"
        m < 1000 -> "${m.roundToInt()} m"
        m < 10_000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** Projects a point to metres east/north of [origin] (equirectangular, fine for a few km). */
    fun toLocalMetres(origin: LatLon, p: LatLon): Pair<Double, Double> {
        val x = Math.toRadians(p.lon - origin.lon) * EARTH_RADIUS_M * cos(Math.toRadians((p.lat + origin.lat) / 2))
        val y = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M
        return x to y
    }

    fun isValid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(lat == 0.0 && lon == 0.0)

    /** Minutes needed to cover [distanceM] at [speedKmh] (at least 1). */
    fun etaMinutes(distanceM: Double, speedKmh: Double): Int {
        if (speedKmh <= 0) return -1
        return maxOf(1, Math.ceil(distanceM / (speedKmh * 1000.0 / 60.0)).toInt())
    }

    /**
     * DBSCAN clustering with haversine distance. Returns clusters as lists of point indices;
     * points that belong to no cluster (noise) are left out.
     */
    fun dbscan(points: List<LatLon>, epsM: Double, minPts: Int): List<List<Int>> {
        val n = points.size
        val label = IntArray(n) { UNVISITED }
        var cluster = -1
        fun region(i: Int): List<Int> = (0 until n).filter { distanceM(points[i], points[it]) <= epsM }
        for (i in 0 until n) {
            if (label[i] != UNVISITED) continue
            val nb = region(i)
            if (nb.size < minPts) {
                label[i] = NOISE
                continue
            }
            cluster++
            label[i] = cluster
            val seeds = ArrayDeque(nb.filter { it != i })
            val queued = HashSet(seeds)
            while (seeds.isNotEmpty()) {
                val j = seeds.removeFirst()
                if (label[j] == NOISE) label[j] = cluster
                if (label[j] != UNVISITED) continue
                label[j] = cluster
                val nb2 = region(j)
                if (nb2.size >= minPts) {
                    for (q in nb2) if (queued.add(q)) seeds.addLast(q)
                }
            }
        }
        return (0..cluster).map { c -> (0 until n).filter { label[it] == c } }
    }

    private const val UNVISITED = -2
    private const val NOISE = -1

    /**
     * Visiting order for several stops starting from [start]: nearest-neighbour tour improved with 2-opt.
     * Returns indices into [stops].
     */
    fun routeOrder(start: LatLon, stops: List<LatLon>): List<Int> {
        if (stops.isEmpty()) return emptyList()
        val remaining = stops.indices.toMutableList()
        val order = ArrayList<Int>()
        var cur = start
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { distanceM(cur, stops[it]) }!!
            order.add(next)
            remaining.remove(next)
            cur = stops[next]
        }
        // 2-opt on an open path that begins at start.
        fun pt(k: Int): LatLon = if (k < 0) start else stops[order[k]]
        var improved = true
        var guard = 0
        while (improved && guard < 200) {
            improved = false
            guard++
            for (i in 0 until order.size - 1) {
                for (j in i + 1 until order.size) {
                    val a = pt(i - 1)
                    val b = pt(i)
                    val c = pt(j)
                    val before = distanceM(a, b) + if (j + 1 < order.size) distanceM(c, pt(j + 1)) else 0.0
                    val after = distanceM(a, c) + if (j + 1 < order.size) distanceM(b, pt(j + 1)) else 0.0
                    if (after + 1e-6 < before) {
                        order.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }
        return order
    }

    fun routeLengthM(start: LatLon, stops: List<LatLon>, order: List<Int>): Double {
        var d = 0.0
        var cur = start
        for (k in order) {
            d += distanceM(cur, stops[k])
            cur = stops[k]
        }
        return d
    }
}
