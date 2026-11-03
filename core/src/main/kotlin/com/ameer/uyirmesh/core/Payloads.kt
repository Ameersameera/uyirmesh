package com.ameer.uyirmesh.core

/**
 * Message bodies. Each one becomes a small JSON document; encryption (if any) is applied on top of these bytes.
 * Field names are short to keep packets small.
 */

object EmergencyKind {
    const val TRAPPED = "TRAPPED"
    const val MEDICAL = "MEDICAL"
    const val FIRE = "FIRE"
    const val FLOOD = "FLOOD"
    const val NEED = "NEED"
    val ALL = listOf(TRAPPED, MEDICAL, FIRE, FLOOD, NEED)
}

object Needs {
    const val WATER = "WATER"
    const val FOOD = "FOOD"
    const val MEDICINE = "MEDICINE"
    const val BABY_FOOD = "BABY_FOOD"
    const val BOAT = "BOAT"
    val ALL = listOf(WATER, FOOD, MEDICINE, BABY_FOOD, BOAT)
}

object Triggers {
    const val BUTTON = "BUTTON"
    const val VOICE = "VOICE"
    const val SHAKE = "SHAKE"
    const val WIDGET = "WIDGET"
    const val TILE = "TILE"
    const val SHORTCUT = "SHORTCUT"
    const val NOTIFICATION = "NOTIFICATION"
}

private fun Double?.r6(): Double? = this?.let { Math.round(it * 1e6) / 1e6 }

data class SosPayload(
    val caseId: String,
    val kind: String,
    val people: Int,
    val children: Int = 0,
    val elderly: Int = 0,
    val disabled: Int = 0,
    val pregnant: Int = 0,
    val injured: Boolean = false,
    val needs: List<String> = emptyList(),
    val note: String = "",
    val battery: Int = -1,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val locTime: Long? = null,
    val name: String = "",
    val phone: String = "",
    val blood: String = "",
    val medical: String = "",
    val trigger: String = Triggers.BUTTON,
    val cancelHash: String = "",
    val sentAt: Long = 0,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "c" to caseId, "k" to kind, "p" to people, "ch" to children, "el" to elderly, "di" to disabled,
        "pr" to pregnant, "in" to injured, "nd" to needs, "no" to note, "b" to battery,
        "la" to lat.r6(), "lo" to lon.r6(), "ac" to accuracy, "lt" to locTime,
        "n" to name, "ph" to phone, "bg" to blood, "md" to medical, "tr" to trigger, "cx" to cancelHash, "t" to sentAt,
    )

    fun toBytes(): ByteArray = Json.toBytes(toJson())

    companion object {
        fun fromJson(m: Map<String, Any?>) = SosPayload(
            caseId = m.str("c"), kind = m.str("k", EmergencyKind.TRAPPED), people = m.int("p", 1).coerceIn(1, 999),
            children = m.int("ch").coerceIn(0, 999), elderly = m.int("el").coerceIn(0, 999),
            disabled = m.int("di").coerceIn(0, 999), pregnant = m.int("pr").coerceIn(0, 999),
            injured = m.bool("in"), needs = m.strList("nd"), note = m.str("no").take(300), battery = m.int("b", -1),
            lat = m.doubleOrNull("la"), lon = m.doubleOrNull("lo"), accuracy = m.doubleOrNull("ac"),
            locTime = (m["lt"] as? Number)?.toLong(), name = m.str("n").take(60), phone = m.str("ph").take(30),
            blood = m.str("bg").take(8), medical = m.str("md").take(300), trigger = m.str("tr", Triggers.BUTTON),
            cancelHash = m.str("cx"), sentAt = m.long("t"),
        )

        fun fromBytes(b: ByteArray) = fromJson(Json.objectFromBytes(b))
    }
}

data class SosUpdate(
    val caseId: String,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val locTime: Long?,
    val battery: Int,
    val lowBattery: Boolean = false,
    val note: String = "",
    val sentAt: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "c" to caseId, "la" to lat.r6(), "lo" to lon.r6(), "ac" to accuracy, "lt" to locTime,
            "b" to battery, "lb" to lowBattery, "no" to note, "t" to sentAt,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): SosUpdate {
            val m = Json.objectFromBytes(b)
            return SosUpdate(
                m.str("c"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.doubleOrNull("ac"),
                (m["lt"] as? Number)?.toLong(), m.int("b", -1), m.bool("lb"), m.str("no").take(300), m.long("t"),
            )
        }
    }
}

/** Voice note or photo attached to a case or report. Plain format: jsonLen(u16) | json | media bytes. */
data class SosMedia(val caseId: String, val mime: String, val durationMs: Long, val data: ByteArray, val sentAt: Long = 0) {
    fun toBytes(): ByteArray {
        val head = Json.toBytes(linkedMapOf("c" to caseId, "m" to mime, "d" to durationMs, "t" to sentAt))
        return ByteWriter(head.size + data.size + 4).bytes16(head).raw(data).toByteArray()
    }

    companion object {
        /** Largest voice note or photo that fits in one sealed packet (with room for headers). */
        const val MAX_MEDIA_BYTES = 28_500

        fun fromBytes(b: ByteArray): SosMedia {
            val r = ByteReader(b)
            val m = Json.objectFromBytes(r.bytes16())
            val data = r.raw(r.remaining)
            return SosMedia(m.str("c"), m.str("m"), m.long("d"), data, m.long("t"))
        }
    }
}

data class SosCancel(val caseId: String, val secret: String, val sentAt: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(linkedMapOf("c" to caseId, "s" to secret, "t" to sentAt))

    companion object {
        fun fromBytes(b: ByteArray): SosCancel {
            val m = Json.objectFromBytes(b)
            return SosCancel(m.str("c"), m.str("s"), m.long("t"))
        }
    }
}

object AckStatus {
    const val RECEIVED = "RECEIVED"
    const val ASSIGNED = "ASSIGNED"
    const val ON_THE_WAY = "ON_THE_WAY"
    const val RESCUED = "RESCUED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Reply to a victim. Sent by the base (signed with the base key) or directly by a rescuer
 * (signed with the rescuer's key; [cert] carries the rescuer's base-signed certificate, base64).
 * [hops] and [path] tell the victim how the SOS travelled to the base.
 */
data class AckPayload(
    val caseId: String,
    val status: String,
    val team: String = "",
    val etaMin: Int = -1,
    val message: String = "",
    val at: Long = 0,
    val by: String = "",
    val cert: String = "",
    val hops: Int = -1,
    val path: List<String> = emptyList(),
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "c" to caseId, "s" to status, "tm" to team, "e" to etaMin, "m" to message, "t" to at,
            "by" to by, "rc" to cert, "h" to hops, "pa" to path,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): AckPayload {
            val m = Json.objectFromBytes(b)
            return AckPayload(
                m.str("c"), m.str("s"), m.str("tm"), m.int("e", -1), m.str("m").take(300), m.long("t"),
                m.str("by").take(60), m.str("rc"), m.int("h", -1), m.strList("pa").take(16),
            )
        }
    }
}

object AlertKind {
    const val EVACUATE = "EVACUATE"
    const val DANGER = "DANGER"
    const val SHELTER = "SHELTER"
    const val INFO = "INFO"
    const val MISSING = "MISSING"
    const val ALL_CLEAR = "ALL_CLEAR"
    val ALL = listOf(EVACUATE, DANGER, SHELTER, INFO, MISSING, ALL_CLEAR)
}

data class AlertPayload(
    val alertId: String,
    val kind: String,
    val severity: Int,
    val title: String,
    val message: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: Int = 0,
    val capacity: Int = 0,
    val occupancy: Int = 0,
    val validUntil: Long = 0,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "a" to alertId, "k" to kind, "sv" to severity, "ti" to title, "m" to message, "la" to lat.r6(),
            "lo" to lon.r6(), "r" to radiusM, "cp" to capacity, "oc" to occupancy, "vu" to validUntil, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): AlertPayload {
            val m = Json.objectFromBytes(b)
            return AlertPayload(
                m.str("a"), m.str("k", AlertKind.INFO), m.int("sv", 1).coerceIn(1, 3), m.str("ti").take(80),
                m.str("m").take(500), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.int("r"), m.int("cp"),
                m.int("oc"), m.long("vu"), m.long("t"),
            )
        }
    }
}

data class ChatPayload(val name: String, val text: String, val at: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(linkedMapOf("n" to name, "x" to text, "t" to at))

    companion object {
        fun fromBytes(b: ByteArray): ChatPayload {
            val m = Json.objectFromBytes(b)
            return ChatPayload(m.str("n").take(40), m.str("x").take(500), m.long("t"))
        }
    }
}

data class SafePayload(
    val name: String,
    val message: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val place: String = "",
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("n" to name, "m" to message, "la" to lat.r6(), "lo" to lon.r6(), "pl" to place, "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): SafePayload {
            val m = Json.objectFromBytes(b)
            return SafePayload(
                m.str("n").take(60), m.str("m").take(200), m.doubleOrNull("la"), m.doubleOrNull("lo"),
                m.str("pl").take(80), m.long("t"),
            )
        }
    }
}

object ReportKind {
    const val ROAD_BLOCK = "ROAD_BLOCK"
    const val WATER_LEVEL = "WATER_LEVEL"
    const val TREE_FALL = "TREE_FALL"
    const val FIRE = "FIRE"
    const val BUILDING = "BUILDING"
    const val OTHER = "OTHER"
    val ALL = listOf(ROAD_BLOCK, WATER_LEVEL, TREE_FALL, FIRE, BUILDING, OTHER)
}

data class ReportPayload(
    val reportId: String,
    val kind: String,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val name: String = "",
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("r" to reportId, "k" to kind, "no" to note, "la" to lat.r6(), "lo" to lon.r6(), "n" to name, "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): ReportPayload {
            val m = Json.objectFromBytes(b)
            return ReportPayload(
                m.str("r"), m.str("k", ReportKind.OTHER), m.str("no").take(300), m.doubleOrNull("la"),
                m.doubleOrNull("lo"), m.str("n").take(60), m.long("t"),
            )
        }
    }
}

object Skills {
    const val DOCTOR = "DOCTOR"
    const val NURSE = "NURSE"
    const val SWIMMER = "SWIMMER"
    const val BOAT = "BOAT"
    const val DRIVER = "DRIVER"
    const val ELECTRICIAN = "ELECTRICIAN"
    const val COOK = "COOK"
    val ALL = listOf(DOCTOR, NURSE, SWIMMER, BOAT, DRIVER, ELECTRICIAN, COOK)
}

data class VolunteerPayload(
    val name: String,
    val phone: String,
    val skills: List<String>,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf("n" to name, "ph" to phone, "sk" to skills, "no" to note, "la" to lat.r6(), "lo" to lon.r6(), "t" to at)
    )

    companion object {
        fun fromBytes(b: ByteArray): VolunteerPayload {
            val m = Json.objectFromBytes(b)
            return VolunteerPayload(
                m.str("n").take(60), m.str("ph").take(30), m.strList("sk"), m.str("no").take(300),
                m.doubleOrNull("la"), m.doubleOrNull("lo"), m.long("t"),
            )
        }
    }
}

object Roles {
    const val PUBLIC = "PUBLIC"
    const val RESCUER = "RESCUER"
    const val BASE = "BASE"
}

data class HeartbeatPayload(
    val role: String,
    val battery: Int,
    val neighbours: List<String>,
    val lat: Double? = null,
    val lon: Double? = null,
    val team: String = "",
    val rescuerId: String = "",
    val name: String = "",
    val version: String = "",
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "ro" to role, "b" to battery, "nb" to neighbours, "la" to lat.r6(), "lo" to lon.r6(), "tm" to team,
            "ri" to rescuerId, "n" to name, "v" to version, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): HeartbeatPayload {
            val m = Json.objectFromBytes(b)
            return HeartbeatPayload(
                m.str("ro", Roles.PUBLIC), m.int("b", -1), m.strList("nb").take(32), m.doubleOrNull("la"),
                m.doubleOrNull("lo"), m.str("tm"), m.str("ri"), m.str("n").take(60), m.str("v"), m.long("t"),
            )
        }
    }
}

data class TaskPayload(
    val caseId: String,
    val victimDevice: String,
    val lat: Double?,
    val lon: Double?,
    val label: String,
    val score: Int,
    val summary: String,
    val medical: String,
    val people: Int,
    val team: String,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "c" to caseId, "vd" to victimDevice, "la" to lat.r6(), "lo" to lon.r6(), "lb" to label, "sc" to score,
            "su" to summary, "md" to medical, "p" to people, "tm" to team, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): TaskPayload {
            val m = Json.objectFromBytes(b)
            return TaskPayload(
                m.str("c"), m.str("vd"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("lb"), m.int("sc"),
                m.str("su").take(400), m.str("md").take(300), m.int("p", 1), m.str("tm"), m.long("t"),
            )
        }
    }
}

object TaskState {
    const val ACCEPTED = "ACCEPTED"
    const val ON_THE_WAY = "ON_THE_WAY"
    const val RESCUED = "RESCUED"
    const val UNABLE = "UNABLE"
}

data class TaskStatusPayload(
    val caseId: String,
    val rescuerId: String,
    val rescuerName: String,
    val team: String,
    val status: String,
    val lat: Double?,
    val lon: Double?,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "c" to caseId, "ri" to rescuerId, "rn" to rescuerName, "tm" to team, "s" to status,
            "la" to lat.r6(), "lo" to lon.r6(), "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): TaskStatusPayload {
            val m = Json.objectFromBytes(b)
            return TaskStatusPayload(
                m.str("c"), m.str("ri"), m.str("rn").take(60), m.str("tm"), m.str("s"), m.doubleOrNull("la"),
                m.doubleOrNull("lo"), m.long("t"),
            )
        }
    }
}

data class TeamChatPayload(val name: String, val text: String, val at: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(linkedMapOf("n" to name, "x" to text, "t" to at))

    companion object {
        fun fromBytes(b: ByteArray): TeamChatPayload {
            val m = Json.objectFromBytes(b)
            return TeamChatPayload(m.str("n").take(60), m.str("x").take(500), m.long("t"))
        }
    }
}

data class FindRequest(val requestId: String, val query: String, val at: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(linkedMapOf("r" to requestId, "q" to query, "t" to at))

    companion object {
        fun fromBytes(b: ByteArray): FindRequest {
            val m = Json.objectFromBytes(b)
            return FindRequest(m.str("r"), m.str("q").take(60), m.long("t"))
        }
    }
}

data class FindMatch(val name: String, val status: String, val place: String, val at: Long)

data class FindResult(val requestId: String, val query: String, val matches: List<FindMatch>, val at: Long = 0) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "r" to requestId, "q" to query, "t" to at,
            "m" to matches.take(10).map { linkedMapOf("n" to it.name, "s" to it.status, "p" to it.place, "t" to it.at) },
        )
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromBytes(b: ByteArray): FindResult {
            val m = Json.objectFromBytes(b)
            val matches = m.list("m").mapNotNull { it as? Map<String, Any?> }.map {
                FindMatch(it.str("n"), it.str("s"), it.str("p"), it.long("t"))
            }
            return FindResult(m.str("r"), m.str("q"), matches, m.long("t"))
        }
    }
}

/** Missing-person notice shown on every phone's board; [found] = true closes it. */
data class MissingPayload(
    val noticeId: String,
    val person: String,
    val age: Int,
    val description: String,
    val lastSeen: String,
    val contact: String,
    val reporter: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val found: Boolean = false,
    val at: Long = 0,
) {
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            "i" to noticeId, "p" to person, "ag" to age, "de" to description, "ls" to lastSeen, "ct" to contact,
            "rp" to reporter, "la" to lat.r6(), "lo" to lon.r6(), "f" to found, "t" to at,
        )
    )

    companion object {
        fun fromBytes(b: ByteArray): MissingPayload {
            val m = Json.objectFromBytes(b)
            return MissingPayload(
                m.str("i"), m.str("p").take(60), m.int("ag", -1), m.str("de").take(300), m.str("ls").take(120),
                m.str("ct").take(30), m.str("rp").take(60), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.bool("f"),
                m.long("t"),
            )
        }
    }
}

/** Personal medical card, shown as a QR code that a rescuer or shelter can scan (never sent over the mesh by itself). */
data class ProfileCard(
    val deviceId: String,
    val name: String,
    val phone: String,
    val blood: String,
    val allergies: String,
    val conditions: String,
    val medicines: String,
    val contactName: String,
    val contactPhone: String,
) {
    fun toQr(): String = "UMP1:" + B64.encodeUrl(
        Json.toBytes(
            linkedMapOf(
                "d" to deviceId, "n" to name, "ph" to phone, "bg" to blood, "al" to allergies, "co" to conditions,
                "me" to medicines, "cn" to contactName, "cp" to contactPhone,
            )
        )
    )

    fun medicalSummary(): String = buildList {
        if (blood.isNotBlank()) add("Blood $blood")
        if (allergies.isNotBlank()) add("Allergies: $allergies")
        if (conditions.isNotBlank()) add("Conditions: $conditions")
        if (medicines.isNotBlank()) add("Medicines: $medicines")
    }.joinToString("; ")

    companion object {
        fun fromQr(text: String): ProfileCard {
            if (!text.startsWith("UMP1:")) throw FormatException("Not a UyirMesh profile QR")
            val m = Json.objectFromBytes(B64.decodeUrl(text.removePrefix("UMP1:")))
            return ProfileCard(
                m.str("d"), m.str("n"), m.str("ph"), m.str("bg"), m.str("al"), m.str("co"), m.str("me"),
                m.str("cn"), m.str("cp"),
            )
        }
    }
}
