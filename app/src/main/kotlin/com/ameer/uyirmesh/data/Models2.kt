package com.ameer.uyirmesh.data

import com.ameer.uyirmesh.core.bool
import com.ameer.uyirmesh.core.doubleOrNull
import com.ameer.uyirmesh.core.int
import com.ameer.uyirmesh.core.long
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.core.strList

/* Stored records added in version 2. */

/** A poll or roll call from the base, as this phone saw it (and how it answered). */
data class PollItem(
    val pollId: String,
    val kind: String,
    val question: String,
    val options: List<String>,
    val validUntil: Long,
    val at: Long,
    val receivedAt: Long,
    val answered: Int = -1,
    val answeredAt: Long = 0,
    val mine: Boolean = false,
    val drill: Boolean = false,
) {
    fun isValid(now: Long) = validUntil == 0L || validUntil > now

    fun toJson() = linkedMapOf(
        "p" to pollId, "k" to kind, "q" to question, "o" to options, "vu" to validUntil, "a" to at, "ra" to receivedAt,
        "an" to answered, "aa" to answeredAt, "mi" to mine, "d" to drill,
    )

    companion object {
        fun from(m: Map<String, Any?>) = PollItem(
            m.str("p"), m.str("k"), m.str("q"), m.strList("o"), m.long("vu"), m.long("a"), m.long("ra"), m.int("an", -1),
            m.long("aa"), m.bool("mi"), m.bool("d"),
        )
    }
}

/** One answer the base received (latest answer per phone counts). */
data class PollResponse(
    val pollId: String,
    val src: String,
    val choice: Int,
    val name: String,
    val phone: String,
    val lat: Double?,
    val lon: Double?,
    val at: Long,
) {
    fun toJson() = linkedMapOf("p" to pollId, "s" to src, "c" to choice, "n" to name, "ph" to phone, "la" to lat, "lo" to lon, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = PollResponse(
            m.str("p"), m.str("s"), m.int("c", -1), m.str("n"), m.str("ph"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.long("a"),
        )
    }
}

/** A rescuer ID the base revoked (lost or stolen phone). */
data class RevokedItem(val rescuerId: String, val name: String, val at: Long) {
    fun toJson() = linkedMapOf("i" to rescuerId, "n" to name, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = RevokedItem(m.str("i"), m.str("n"), m.long("a"))
    }
}

/** Latest reading of a Bluetooth sensor node (water level, rain, gas). */
data class SensorItem(
    val sensorId: String,
    val kind: String,
    val value: Double,
    val unit: String,
    val alarm: Boolean,
    val battery: Int,
    val lat: Double?,
    val lon: Double?,
    val relayedBy: String,
    val at: Long,
    val receivedAt: Long,
) {
    fun toJson() = linkedMapOf(
        "s" to sensorId, "k" to kind, "v" to value, "u" to unit, "al" to alarm, "b" to battery, "la" to lat, "lo" to lon,
        "rb" to relayedBy, "a" to at, "ra" to receivedAt,
    )

    companion object {
        fun from(m: Map<String, Any?>) = SensorItem(
            m.str("s"), m.str("k"), m.doubleOrNull("v") ?: 0.0, m.str("u"), m.bool("al"), m.int("b", -1),
            m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("rb"), m.long("a"), m.long("ra"),
        )
    }
}

/** A lost-child wristband made on this phone, and the last "found" message for it. */
data class ChildItem(
    val tagId: String,
    val name: String,
    val qr: String,
    val createdAt: Long,
    val foundAt: Long = 0,
    val finder: String = "",
    val finderPhone: String = "",
    val note: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun toJson() = linkedMapOf(
        "i" to tagId, "n" to name, "q" to qr, "c" to createdAt, "fa" to foundAt, "f" to finder, "fp" to finderPhone,
        "no" to note, "la" to lat, "lo" to lon,
    )

    companion object {
        fun from(m: Map<String, Any?>) = ChildItem(
            m.str("i"), m.str("n"), m.str("q"), m.long("c"), m.long("fa"), m.str("f"), m.str("fp"), m.str("no"),
            m.doubleOrNull("la"), m.doubleOrNull("lo"),
        )
    }
}

/** Preparedness checklist item ticked by the user. */
data class ChecklistItem(val key: String, val done: Boolean, val at: Long) {
    fun toJson() = linkedMapOf("k" to key, "d" to done, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = ChecklistItem(m.str("k"), m.bool("d"), m.long("a"))
    }
}
