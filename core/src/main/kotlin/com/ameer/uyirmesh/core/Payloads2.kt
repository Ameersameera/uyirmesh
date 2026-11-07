package com.ameer.uyirmesh.core

import java.util.Collections

/* Message bodies added in version 2. Same style as Payloads.kt: small JSON documents with short field names. */

private fun Double?.r6(): Double? = this?.let { Math.round(it * 1e6) / 1e6 }

object HazardKind {
    const val BRIDGE_DOWN = "BRIDGE_DOWN"
    const val LIVE_WIRE = "LIVE_WIRE"
    const val DEEP_WATER = "DEEP_WATER"
    const val LANDSLIDE = "LANDSLIDE"
    const val FIRE = "FIRE"
    const val GAS = "GAS"
    const val COLLAPSE = "COLLAPSE"
    const val OTHER = "OTHER"
    val ALL = listOf(BRIDGE_DOWN, LIVE_WIRE, DEEP_WATER, LANDSLIDE, FIRE, GAS, COLLAPSE, OTHER)
}

/**
 * A danger marked by a rescuer in the field ("bridge broken", "live wire in water"). Signed with the rescuer's key;
 * [cert] carries the rescuer's base-signed certificate (base64) so every phone can check it. [cleared] = the danger
 * is gone. The base can send the same message signed with the base key (then [cert] is empty).
 */
data class HazardPayload(
    val hazardId: String,
    val kind: String,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Int,
    val by: String = "",
    val cert: String = "",
    val cleared: Boolean = false,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "h" to hazardId, "k" to kind, "no" to note, "la" to lat.r6(), "lo" to lon.r6(), "r" to radiusM,
            "by" to by, "rc" to cert, "cl" to cleared, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): HazardPayload {
            val m = Json.objectFromBytes(b)
            return HazardPayload(
                m.str("h"), m.str("k", HazardKind.OTHER), m.str("no").take(200), m.doubleOrNull("la"), m.doubleOrNull("lo"),
                m.int("r", 100).coerceIn(10, 5_000), m.str("by").take(60), m.str("rc"), m.bool("cl"), m.long("t"),
            )
        }
    }
}

object PollKind {
    const val POLL = "POLL"

    /** "Are you safe?" after an evacuation: answers become a headcount at the base. */
    const val ROLLCALL = "ROLLCALL"
}

/** A question from the base to every phone (signed by the base). [options] are the possible answers. */
data class PollPayload(
    val pollId: String,
    val kind: String,
    val question: String,
    val options: List<String>,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: Int = 0,
    val validUntil: Long = 0,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "p" to pollId, "k" to kind, "q" to question, "o" to options, "la" to lat.r6(), "lo" to lon.r6(),
            "r" to radiusM, "vu" to validUntil, "t" to at,
        )
    )

    fun isValid(now: Long) = validUntil == 0L || validUntil > now

    companion object {
        fun fromBytes(b: ByteArray): PollPayload {
            val m = Json.objectFromBytes(b)
            return PollPayload(
                m.str("p"), m.str("k", PollKind.POLL), m.str("q").take(200), m.strList("o").take(6).map { it.take(40) },
                m.doubleOrNull("la"), m.doubleOrNull("lo"), m.int("r"), m.long("vu"), m.long("t"),
            )
        }
    }
}

/** One phone's answer to a poll, sealed so only the base can read it. [choice] is an index into the options. */
data class PollAnswer(
    val pollId: String,
    val choice: Int,
    val name: String,
    val phone: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("p" to pollId, "c" to choice, "n" to name, "ph" to phone, "la" to lat.r6(), "lo" to lon.r6(), "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): PollAnswer {
            val m = Json.objectFromBytes(b)
            return PollAnswer(
                m.str("p"), m.int("c", -1), m.str("n").take(60), m.str("ph").take(30), m.doubleOrNull("la"),
                m.doubleOrNull("lo"), m.long("t"),
            )
        }
    }
}

/**
 * When several rescue bases work in one area (all load the same base key file), each tells the others what it did
 * with a case, so two bases never send two teams to the same victim. [handoverTo] = the base that should take the
 * case over (its base name), empty for a plain status update. Signed with the base key.
 */
data class CaseSync(
    val caseId: String,
    val status: String,
    val team: String,
    val baseName: String,
    val etaMin: Int = -1,
    val handoverTo: String = "",
    val note: String = "",
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "c" to caseId, "s" to status, "tm" to team, "bn" to baseName, "e" to etaMin, "ho" to handoverTo,
            "no" to note, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): CaseSync {
            val m = Json.objectFromBytes(b)
            return CaseSync(
                m.str("c"), m.str("s"), m.str("tm").take(40), m.str("bn").take(40), m.int("e", -1), m.str("ho").take(40),
                m.str("no").take(200), m.long("t"),
            )
        }
    }
}

/** Rescuer IDs that must no longer be trusted (lost or stolen phone), signed by the base. */
data class RevokePayload(val rescuerIds: List<String>, val names: List<String> = emptyList(), val at: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(linkedMapOf("ri" to rescuerIds, "n" to names, "t" to at))

    companion object {
        fun fromBytes(b: ByteArray): RevokePayload {
            val m = Json.objectFromBytes(b)
            return RevokePayload(m.strList("ri").take(200), m.strList("n").take(200).map { it.take(60) }, m.long("t"))
        }
    }
}

/**
 * Revoked rescuer IDs known to this phone. Replies, distance updates and hazard markers signed by a revoked
 * rescuer are rejected everywhere [Authenticity] is used (relays drop them as forged).
 */
object Revocations {
    private val ids: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun isRevoked(rescuerId: String): Boolean = rescuerId in ids

    fun addAll(list: Collection<String>): Boolean = ids.addAll(list.filter { it.isNotBlank() })

    fun all(): Set<String> = synchronized(ids) { ids.toSet() }

    fun clear() = ids.clear()
}

object SensorKind {
    const val WATER_LEVEL = "WATER_LEVEL"
    const val RAIN = "RAIN"
    const val GAS = "GAS"
}

/**
 * Reading from a small sensor node (for example an ESP32 with an ultrasonic water-level sensor) that a nearby phone
 * picked up over Bluetooth LE and passed into the mesh. Sensors cannot sign, so readings are shown as "unverified".
 */
data class SensorPayload(
    val sensorId: String,
    val kind: String,
    val value: Double,
    val unit: String,
    val alarm: Boolean,
    val battery: Int = -1,
    val lat: Double? = null,
    val lon: Double? = null,
    val relayedBy: String = "",
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "s" to sensorId, "k" to kind, "v" to value, "u" to unit, "al" to alarm, "b" to battery,
            "la" to lat.r6(), "lo" to lon.r6(), "rb" to relayedBy, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): SensorPayload {
            val m = Json.objectFromBytes(b)
            return SensorPayload(
                m.str("s").take(32), m.str("k", SensorKind.WATER_LEVEL), m.doubleOrNull("v") ?: 0.0, m.str("u").take(8),
                m.bool("al"), m.int("b", -1), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("rb").take(60), m.long("t"),
            )
        }

        /**
         * Reads the BLE advertisement of a UyirMesh sensor: the manufacturer data after the company id (0xFFFF),
         * "UM" | version(1) | kind(1: 1 water, 2 rain, 3 gas) | value(int16, big-endian, cm or %) | alarm(1) |
         * battery(1) | id(4) = 12 bytes.
         */
        fun fromAdvert(data: ByteArray, now: Long): SensorPayload? {
            if (data.size < 12 || data[0] != 'U'.code.toByte() || data[1] != 'M'.code.toByte() || data[2].toInt() != 1) return null
            val kind = when (data[3].toInt()) {
                1 -> SensorKind.WATER_LEVEL
                2 -> SensorKind.RAIN
                3 -> SensorKind.GAS
                else -> return null
            }
            val value = ((data[4].toInt() and 0xFF) shl 8 or (data[5].toInt() and 0xFF)).toShort().toInt()
            val alarm = data[6].toInt() != 0
            val battery = data[7].toInt() and 0xFF
            val id = Hex.encode(data.copyOfRange(8, 12)).uppercase()
            val unit = if (kind == SensorKind.WATER_LEVEL) "cm" else "%"
            return SensorPayload(id, kind, value.toDouble(), unit, alarm, if (battery <= 100) battery else -1, at = now)
        }
    }
}

/**
 * Lost-child wristband: the parent's phone makes a QR with a random tag id, the parent's mesh id and a secret key.
 * Whoever finds the child scans it, and a message encrypted with that key goes to the parent's phone.
 */
data class ChildTag(val tagId: String, val childName: String, val parentDevice: String, val key: ByteArray) {
    fun toQr(): String = "UMC1:" + B64.encodeUrl(
        Json.toBytes(linkedMapOf("i" to tagId, "n" to childName, "d" to parentDevice, "k" to B64.encode(key)))
    )

    companion object {
        fun create(childName: String, parentDevice: String): ChildTag = ChildTag(Ids.newId(), childName.take(40), parentDevice, Crypto.random(32))

        fun fromQr(text: String): ChildTag {
            if (!text.startsWith("UMC1:")) throw FormatException("Not a UyirMesh child tag")
            val m = Json.objectFromBytes(B64.decodeUrl(text.removePrefix("UMC1:")))
            val key = B64.decode(m.str("k"))
            if (key.size != 32 || m.str("i").isBlank() || !Ids.isValidDeviceId(m.str("d"))) throw FormatException("Bad child tag")
            return ChildTag(m.str("i"), m.str("n").take(40), m.str("d"), key)
        }
    }
}

/** "I found your child": sent to the parent's phone, encrypted with the wristband's key. */
data class ChildFound(
    val tagId: String,
    val finderName: String,
    val finderPhone: String,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("i" to tagId, "fn" to finderName, "fp" to finderPhone, "no" to note, "la" to lat.r6(), "lo" to lon.r6(), "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): ChildFound {
            val m = Json.objectFromBytes(b)
            return ChildFound(
                m.str("i"), m.str("fn").take(60), m.str("fp").take(30), m.str("no").take(200), m.doubleOrNull("la"),
                m.doubleOrNull("lo"), m.long("t"),
            )
        }
    }
}

/** Matching names typed by different people ("Ravi Kumar" / "ravikumar" / "Ravi Kumaar"). */
object Names {
    fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** True when the names are probably the same person. */
    fun similar(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.length < 3 || y.length < 3) return false
        if (x == y) return true
        if (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))) return true
        val d = levenshtein(x, y)
        val longest = maxOf(x.length, y.length)
        return d <= 1 || (longest >= 8 && d <= 2)
    }

    fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}

/**
 * "The rescue base is here": sent by the base every few minutes, signed with the base key. Phones use it to show
 * how many hops away the base is and when they last heard from it, and the distance to the base.
 */
data class BeaconPayload(
    val baseName: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val openCases: Int = 0,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("bn" to baseName, "la" to lat.r6(), "lo" to lon.r6(), "oc" to openCases, "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): BeaconPayload {
            val m = Json.objectFromBytes(b)
            return BeaconPayload(m.str("bn").take(40), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.int("oc").coerceAtLeast(0), m.long("t"))
        }
    }
}
