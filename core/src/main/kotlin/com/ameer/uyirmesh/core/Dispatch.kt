package com.ameer.uyirmesh.core

import kotlin.math.ceil

/**
 * Minimum-cost assignment (the Hungarian method, O(n²·m)). Used by the base to give waiting cases to rescue teams
 * so the plan is best for all cases together, not just for the first one picked.
 */
object Hungarian {
    /**
     * [cost] has one row per worker and one column per job (all rows the same length, finite numbers).
     * Returns for each row the column it gets, or -1 when there are more rows than columns and the row gets nothing.
     */
    fun solve(cost: Array<DoubleArray>): IntArray {
        val rows = cost.size
        if (rows == 0) return IntArray(0)
        val cols = cost[0].size
        if (cols == 0) return IntArray(rows) { -1 }
        require(cost.all { it.size == cols }) { "All rows need the same number of columns" }
        // The method below needs rows <= columns; otherwise solve the flipped problem.
        val flip = rows > cols
        val a = if (flip) Array(cols) { j -> DoubleArray(rows) { i -> cost[i][j] } } else cost
        val n = a.size
        val m = a[0].size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1) // p[j] = row (1-based) holding column j
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val rowToCol = IntArray(n) { -1 }
        for (j in 1..m) if (p[j] != 0) rowToCol[p[j] - 1] = j - 1
        if (!flip) return rowToCol
        val out = IntArray(rows) { -1 }
        for (r in 0 until n) if (rowToCol[r] >= 0) out[rowToCol[r]] = r
        return out
    }

    /** Total cost of an answer from [solve]. */
    fun total(cost: Array<DoubleArray>, answer: IntArray): Double =
        answer.indices.sumOf { i -> if (answer[i] >= 0) cost[i][answer[i]] else 0.0 }
}

/** A rescue team that can take more work: [free] = how many more tasks it may get now. */
data class TeamSlot(val team: String, val lat: Double?, val lon: Double?, val free: Int)

/** A waiting case: [score] is its urgency. */
data class WaitingCase(val caseId: String, val lat: Double?, val lon: Double?, val score: Int)

data class PlanPick(val caseId: String, val team: String, val etaMin: Int)

/**
 * Plans which team goes to which waiting case.
 *
 * Cost of sending a team to a case = travel minutes − [URGENCY_WEIGHT] × urgency score (+ [BUSY_MINUTES] for a
 * team's second and later task, which it can only start after the first). The Hungarian method finds the plan with
 * the lowest total cost, so the most urgent cases get teams first and, among cases of similar urgency, the
 * nearest team goes. When there are more cases than free team places, the least urgent ones wait.
 */
object Planner {
    const val URGENCY_WEIGHT = 2.0
    const val BUSY_MINUTES = 20.0

    /** Used when a team's or a case's position is not known. */
    const val UNKNOWN_MINUTES = 30

    fun plan(teams: List<TeamSlot>, cases: List<WaitingCase>, travelMinutes: (TeamSlot, WaitingCase) -> Int): List<PlanPick> {
        if (teams.isEmpty() || cases.isEmpty()) return emptyList()
        // One row per free place in a team.
        val slots = teams.flatMap { t -> (0 until t.free.coerceIn(0, 10)).map { k -> t to k } }
        if (slots.isEmpty()) return emptyList()
        val minutes = Array(teams.size) { ti -> IntArray(cases.size) { ci -> eta(travelMinutes, teams[ti], cases[ci]) } }
        val teamIndex = teams.withIndex().associate { it.value to it.index }
        val cost = Array(slots.size) { r ->
            val (t, k) = slots[r]
            val ti = teamIndex.getValue(t)
            DoubleArray(cases.size) { ci -> minutes[ti][ci] + k * BUSY_MINUTES - URGENCY_WEIGHT * cases[ci].score }
        }
        val answer = Hungarian.solve(cost)
        val picks = ArrayList<PlanPick>()
        for (r in answer.indices) {
            val ci = answer[r]
            if (ci < 0) continue
            val t = slots[r].first
            picks.add(PlanPick(cases[ci].caseId, t.team, minutes[teamIndex.getValue(t)][ci]))
        }
        // Most urgent first, so tasks go out in that order.
        val order = cases.withIndex().associate { it.value.caseId to it.value.score }
        return picks.sortedByDescending { order[it.caseId] ?: 0 }
    }

    private fun eta(f: (TeamSlot, WaitingCase) -> Int, t: TeamSlot, c: WaitingCase): Int {
        if (t.lat == null || t.lon == null || c.lat == null || c.lon == null) return UNKNOWN_MINUTES
        val m = f(t, c)
        return if (m < 0) UNKNOWN_MINUTES else m
    }
}

/** One finished rescue: the team was [distanceM] away when it set off and the rescue was done [minutes] later. */
data class EtaSample(val distanceM: Double, val minutes: Double)

/**
 * Learns how long rescues really take. Starts from the speed set at the base and moves towards the pace seen in
 * finished rescues (median, so one odd rescue does not spoil it): with n rescues the learned pace has weight n/(n+3).
 * The time includes everything from setting off until "rescued" (finding the house, getting people out).
 */
class EtaModel(defaultKmh: Double, samples: List<EtaSample>) {
    private val defaultPace = 60.0 / defaultKmh.coerceIn(1.0, 120.0) // minutes per km
    val used: List<EtaSample> = samples.filter { ok(it) }
    val sampleCount: Int get() = used.size

    /** Minutes per kilometre. */
    val pace: Double = run {
        if (used.isEmpty()) return@run defaultPace
        val paces = used.map { it.minutes / (it.distanceM / 1000.0).coerceAtLeast(MIN_KM) }.sorted()
        val median = if (paces.size % 2 == 1) paces[paces.size / 2] else (paces[paces.size / 2 - 1] + paces[paces.size / 2]) / 2
        val w = used.size / (used.size + PRIOR_WEIGHT)
        defaultPace * (1 - w) + median * w
    }

    /** The speed the estimates use (km/h), learned or set. */
    val kmh: Double get() = 60.0 / pace

    fun minutes(distanceM: Double): Int {
        if (distanceM < 0) return -1
        return maxOf(1, ceil(distanceM / 1000.0 * pace).toInt())
    }

    companion object {
        const val PRIOR_WEIGHT = 3.0

        /** Short trips still take a few minutes; count them as at least this far. */
        const val MIN_KM = 0.2

        private fun ok(s: EtaSample): Boolean {
            if (s.distanceM < 50 || s.distanceM > 200_000) return false
            if (s.minutes < 1 || s.minutes > 600) return false
            val kmh = s.distanceM / 1000.0 / (s.minutes / 60.0)
            return kmh in 0.5..120.0
        }
    }
}
