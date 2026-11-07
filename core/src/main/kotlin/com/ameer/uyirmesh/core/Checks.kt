package com.ameer.uyirmesh.core

import java.util.Locale

/**
 * Checks that tell the dispatcher an SOS may need a second look (a prank, a phone with a wrong clock, a position
 * that jumps). They are flags only: they never lower the urgency score, because a real emergency must not be
 * pushed down by mistake. The dispatcher decides (for example by calling the phone number in the SOS).
 */
object SosChecks {
    const val BURST = "BURST"
    const val NO_GPS = "NO_GPS"
    const val JUMP = "JUMP"
    const val OLD_CLOCK = "OLD_CLOCK"
    const val REPEAT_CANCEL = "REPEAT_CANCEL"

    /** Several separate SOS from one phone within [BURST_WINDOW_MS]. */
    const val BURST_COUNT = 3
    const val BURST_WINDOW_MS = 10 * 60_000L

    /** Messages received without any position before [NO_GPS] is raised. */
    const val NO_GPS_MESSAGES = 3

    /** Faster than this between two positions of one SOS is not possible on foot or by boat. */
    const val JUMP_KMH = 150.0
    const val JUMP_MIN_M = 2_000.0

    const val CLOCK_AHEAD_MS = 10 * 60_000L
    const val CLOCK_OLD_MS = 12 * 3600_000L

    /** SOS sent and cancelled by one phone within a day before [REPEAT_CANCEL] is raised. */
    const val CANCELS = 2

    data class Input(
        val now: Long,
        /** When the phone says it made the message. */
        val createdAt: Long,
        val hasPosition: Boolean,
        /** How many times this SOS has arrived (first message = 1). */
        val messages: Int,
        /** Other cases opened by the same phone in the last [BURST_WINDOW_MS]. */
        val otherRecentCases: Int,
        /** Cases of the same phone cancelled in the last 24 hours. */
        val recentCancels: Int,
    )

    fun flags(i: Input): List<String> {
        val out = ArrayList<String>()
        if (i.otherRecentCases + 1 >= BURST_COUNT) out.add(BURST)
        if (!i.hasPosition && i.messages >= NO_GPS_MESSAGES) out.add(NO_GPS)
        if (i.createdAt > i.now + CLOCK_AHEAD_MS || i.createdAt < i.now - CLOCK_OLD_MS) out.add(OLD_CLOCK)
        if (i.recentCancels >= CANCELS) out.add(REPEAT_CANCEL)
        return out
    }

    /** "JUMP:12.4 km" when the position moved impossibly fast between two fixes, else null. */
    fun jump(lat1: Double, lon1: Double, at1: Long, lat2: Double, lon2: Double, at2: Long): String? {
        val d = Geo.distanceM(lat1, lon1, lat2, lon2)
        if (d < JUMP_MIN_M) return null
        val hours = Math.abs(at2 - at1) / 3600_000.0
        if (hours > 0.5) return null
        val kmh = if (hours <= 0.0) Double.POSITIVE_INFINITY else d / 1000.0 / hours
        return if (kmh > JUMP_KMH) "$JUMP:${distanceText(d)}" else null
    }

    fun distanceText(m: Double): String =
        if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "${m.toInt()} m"

    /** Keeps a position jump seen earlier, replaces the other flags with [fresh]. */
    fun merge(old: List<String>, fresh: List<String>): List<String> =
        (old.filter { it.startsWith("$JUMP:") }.take(1) + fresh).distinct()
}

/** What the base knows about one case, for spotting the same people reported twice. */
data class CaseFacts(
    val caseId: String,
    val device: String,
    val kind: String,
    val at: Long,
    val lat: Double?,
    val lon: Double?,
    /** Name of the person in danger (the other person's name when reported for someone else). */
    val name: String,
    val phone: String,
)

/**
 * Finds SOS that are probably about the same people (two family members both pressing SOS, or a neighbour
 * reporting someone who also sent their own). The base links them so only one team goes.
 */
object Duplicates {
    const val SAME_SPOT = "same spot"
    const val SAME_PHONE = "same phone number"
    const val SAME_NAME = "same name nearby"

    /** Why [a] and [b] look like the same emergency, or null. */
    fun reason(a: CaseFacts, b: CaseFacts): String? {
        if (a.caseId == b.caseId || a.device == b.device) return null
        val dt = Math.abs(a.at - b.at)
        val near = if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else null
        if (near != null && near < 30 && a.kind == b.kind && dt < 30 * 60_000L) return SAME_SPOT
        val pa = digits(a.phone)
        if (pa.length >= 8 && pa == digits(b.phone) && dt < 6 * 3600_000L) return SAME_PHONE
        if (near != null && near < 500 && dt < 2 * 3600_000L && Names.similar(a.name, b.name)) return SAME_NAME
        return null
    }

    /** Last 10 digits, so "+91 98400 12345" and "09840012345" match. */
    fun digits(phone: String): String = phone.filter { it.isDigit() }.takeLast(10)
}

/** How big voice notes and photos may be, chosen from how good the mesh link is right now. */
enum class MediaLevel(val photoBytes: Int, val voiceBitrate: Int, val voiceSeconds: Int) {
    NORMAL(26_000, 12_200, 15),
    LOW(15_000, 7_950, 12),
    VERY_LOW(9_000, 5_900, 8),
}

object MediaQuality {
    /**
     * [peers] = phones in direct range, [baseHops] = hops to the base last time it was heard (-1 = not heard),
     * [battery] in percent (-1 = not known), [waiting] = own packets still waiting for a phone in range.
     * Smaller media when the message has to wait or travel far: it reaches the base sooner and costs relays less battery.
     */
    fun pick(peers: Int, baseHops: Int, battery: Int, waiting: Int): MediaLevel = when {
        peers == 0 || battery in 0..10 || waiting >= 20 -> MediaLevel.VERY_LOW
        baseHops >= 4 || battery in 0..20 || waiting >= 5 -> MediaLevel.LOW
        else -> MediaLevel.NORMAL
    }
}

/** Battery-aware relaying and radio duty cycle. */
object PowerPolicy {
    /**
     * Highest QoS class this phone still relays for others (see [MeshEngine.relayMaxQos]).
     * A fixed relay phone ([relayMode]) keeps relaying everything for longer.
     */
    fun relayMaxQos(battery: Int, charging: Boolean, relayMode: Boolean, auto: Boolean): Int {
        if (!auto || charging || battery < 0) return 3
        val (critical, low) = if (relayMode) 5 to 10 else 10 to 20
        return when {
            battery <= critical -> 1
            battery <= low -> 2
            else -> 3
        }
    }

    /** True when the radio should search less often to save battery. */
    fun lowPowerRadio(battery: Int, charging: Boolean, relayMode: Boolean, auto: Boolean): Boolean {
        if (!auto || charging || battery < 0) return false
        return battery <= if (relayMode) 10 else 20
    }
}
