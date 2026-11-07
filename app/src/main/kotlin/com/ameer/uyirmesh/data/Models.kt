package com.ameer.uyirmesh.data

import com.ameer.uyirmesh.core.FindMatch
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.Reason
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.bool
import com.ameer.uyirmesh.core.doubleOrNull
import com.ameer.uyirmesh.core.int
import com.ameer.uyirmesh.core.list
import com.ameer.uyirmesh.core.long
import com.ameer.uyirmesh.core.obj
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.core.strList

/* JSON helpers for app models. */

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.objs(key: String): List<Map<String, Any?>> = list(key).mapNotNull { it as? Map<String, Any?> }

private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

/* ---------------- event log ---------------- */

data class LogEntry(val at: Long, val tag: String, val text: String) {
    fun toJson() = linkedMapOf("a" to at, "g" to tag, "x" to text)

    companion object {
        fun from(m: Map<String, Any?>) = LogEntry(m.long("a"), m.str("g"), m.str("x"))
    }
}

/* ---------------- victim side ---------------- */

/** A reply shown on the victim's SOS timeline. [verifiedBy] = "BASE" or the rescuer's name. */
data class SosReply(
    val at: Long,
    val status: String,
    val team: String,
    val etaMin: Int,
    val message: String,
    val verifiedBy: String,
) {
    fun toJson() = linkedMapOf("a" to at, "s" to status, "tm" to team, "e" to etaMin, "m" to message, "v" to verifiedBy)

    companion object {
        fun from(m: Map<String, Any?>) =
            SosReply(m.long("a"), m.str("s"), m.str("tm"), m.int("e", -1), m.str("m"), m.str("v"))
    }
}

data class MySos(
    val caseId: String,
    val payload: SosPayload,
    val cancelSecret: String,
    val packetId: String,
    val sentAt: Long,
    val drill: Boolean,
    val handedAt: Long = 0,
    val handedTo: Int = 0,
    val baseAt: Long = 0,
    val baseHops: Int = -1,
    val assignedAt: Long = 0,
    val team: String = "",
    val etaMin: Int = -1,
    val onWayAt: Long = 0,
    val rescuer: String = "",
    val rescuedAt: Long = 0,
    val cancelledAt: Long = 0,
    val lastUpdateAt: Long = 0,
    val updatesSent: Int = 0,
    val lowBatterySent: Boolean = false,
    val replies: List<SosReply> = emptyList(),
    val voiceSent: Int = 0,
    val photoSent: Int = 0,
    /** Latest distance a rescuer on the way reported (metres), -1 if none yet. */
    val helpDistanceM: Int = -1,
    val helpEtaMin: Int = -1,
    /** When that rescuer's phone sent it (its clock), and who it was. */
    val helpAt: Long = 0,
    val helpBy: String = "",
) {
    val active: Boolean get() = rescuedAt == 0L && cancelledAt == 0L

    fun toJson() = linkedMapOf(
        "c" to caseId, "p" to payload.toJson(), "cs" to cancelSecret, "pk" to packetId, "sa" to sentAt, "dr" to drill,
        "ha" to handedAt, "ht" to handedTo, "ba" to baseAt, "bh" to baseHops, "aa" to assignedAt, "tm" to team,
        "e" to etaMin, "ow" to onWayAt, "rs" to rescuer, "ra" to rescuedAt, "ca" to cancelledAt, "lu" to lastUpdateAt,
        "us" to updatesSent, "lb" to lowBatterySent, "rp" to replies.map { it.toJson() }, "vs" to voiceSent,
        "ps" to photoSent, "hd" to helpDistanceM, "he" to helpEtaMin, "hs" to helpAt, "hb" to helpBy,
    )

    companion object {
        fun from(m: Map<String, Any?>) = MySos(
            m.str("c"), SosPayload.fromJson(m.obj("p")), m.str("cs"), m.str("pk"), m.long("sa"), m.bool("dr"),
            m.long("ha"), m.int("ht"), m.long("ba"), m.int("bh", -1), m.long("aa"), m.str("tm"), m.int("e", -1),
            m.long("ow"), m.str("rs"), m.long("ra"), m.long("ca"), m.long("lu"), m.int("us"), m.bool("lb"),
            m.objs("rp").map { SosReply.from(it) }, m.int("vs"), m.int("ps"), m.int("hd", -1), m.int("he", -1),
            m.long("hs"), m.str("hb"),
        )
    }
}

/* ---------------- public messages ---------------- */

data class ChatItem(
    val id: String,
    val src: String,
    val name: String,
    val text: String,
    val at: Long,
    val mine: Boolean,
    val drill: Boolean,
) {
    fun toJson() = linkedMapOf("i" to id, "s" to src, "n" to name, "x" to text, "a" to at, "m" to mine, "d" to drill)

    companion object {
        fun from(m: Map<String, Any?>) =
            ChatItem(m.str("i"), m.str("s"), m.str("n"), m.str("x"), m.long("a"), m.bool("m"), m.bool("d"))
    }
}

data class AlertItem(
    val id: String,
    val kind: String,
    val severity: Int,
    val title: String,
    val message: String,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Int,
    val capacity: Int,
    val occupancy: Int,
    val validUntil: Long,
    val at: Long,
    val receivedAt: Long,
    val drill: Boolean,
    val mine: Boolean,
) {
    fun isValid(now: Long) = validUntil == 0L || validUntil > now

    fun toJson() = linkedMapOf(
        "i" to id, "k" to kind, "sv" to severity, "ti" to title, "m" to message, "la" to lat, "lo" to lon,
        "r" to radiusM, "cp" to capacity, "oc" to occupancy, "vu" to validUntil, "a" to at, "ra" to receivedAt,
        "d" to drill, "mi" to mine,
    )

    companion object {
        fun from(m: Map<String, Any?>) = AlertItem(
            m.str("i"), m.str("k"), m.int("sv", 1), m.str("ti"), m.str("m"), m.doubleOrNull("la"), m.doubleOrNull("lo"),
            m.int("r"), m.int("cp"), m.int("oc"), m.long("vu"), m.long("a"), m.long("ra"), m.bool("d"), m.bool("mi"),
        )
    }
}

data class SafeItem(
    val id: String,
    val src: String,
    val name: String,
    val message: String,
    val lat: Double?,
    val lon: Double?,
    val place: String,
    val at: Long,
    val mine: Boolean,
) {
    fun toJson() = linkedMapOf(
        "i" to id, "s" to src, "n" to name, "m" to message, "la" to lat, "lo" to lon, "pl" to place, "a" to at, "mi" to mine,
    )

    companion object {
        fun from(m: Map<String, Any?>) = SafeItem(
            m.str("i"), m.str("s"), m.str("n"), m.str("m"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("pl"),
            m.long("a"), m.bool("mi"),
        )
    }
}

data class ReportItem(
    val id: String,
    val src: String,
    val kind: String,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val name: String,
    val at: Long,
    val mine: Boolean,
) {
    fun toJson() = linkedMapOf(
        "i" to id, "s" to src, "k" to kind, "no" to note, "la" to lat, "lo" to lon, "n" to name, "a" to at, "mi" to mine,
    )

    companion object {
        fun from(m: Map<String, Any?>) = ReportItem(
            m.str("i"), m.str("s"), m.str("k"), m.str("no"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("n"),
            m.long("a"), m.bool("mi"),
        )
    }
}

data class MissingItem(
    val id: String,
    val src: String,
    val person: String,
    val age: Int,
    val description: String,
    val lastSeen: String,
    val contact: String,
    val reporter: String,
    val lat: Double?,
    val lon: Double?,
    val found: Boolean,
    val at: Long,
    val mine: Boolean,
) {
    fun toJson() = linkedMapOf(
        "i" to id, "s" to src, "p" to person, "ag" to age, "de" to description, "ls" to lastSeen, "ct" to contact,
        "rp" to reporter, "la" to lat, "lo" to lon, "f" to found, "a" to at, "mi" to mine,
    )

    companion object {
        fun from(m: Map<String, Any?>) = MissingItem(
            m.str("i"), m.str("s"), m.str("p"), m.int("ag", -1), m.str("de"), m.str("ls"), m.str("ct"), m.str("rp"),
            m.doubleOrNull("la"), m.doubleOrNull("lo"), m.bool("f"), m.long("a"), m.bool("mi"),
        )
    }
}

data class FindAnswer(val requestId: String, val query: String, val matches: List<FindMatch>, val at: Long) {
    fun toJson() = linkedMapOf(
        "r" to requestId, "q" to query, "a" to at,
        "m" to matches.map { linkedMapOf("n" to it.name, "s" to it.status, "p" to it.place, "t" to it.at) },
    )

    companion object {
        fun from(m: Map<String, Any?>) = FindAnswer(
            m.str("r"), m.str("q"), m.objs("m").map { FindMatch(it.str("n"), it.str("s"), it.str("p"), it.long("t")) },
            m.long("a"),
        )
    }
}

/* ---------------- base side ---------------- */

data class TrackPoint(val lat: Double, val lon: Double, val at: Long, val battery: Int) {
    fun toJson() = linkedMapOf("la" to lat, "lo" to lon, "a" to at, "b" to battery)

    companion object {
        fun from(m: Map<String, Any?>) =
            TrackPoint(m.doubleOrNull("la") ?: 0.0, m.doubleOrNull("lo") ?: 0.0, m.long("a"), m.int("b", -1))
    }
}

data class MediaRef(val file: String, val mime: String, val durationMs: Long, val at: Long) {
    val isAudio: Boolean get() = mime.startsWith("audio")

    fun toJson() = linkedMapOf("f" to file, "m" to mime, "d" to durationMs, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = MediaRef(m.str("f"), m.str("m"), m.long("d"), m.long("a"))
    }
}

data class CaseEvent(val at: Long, val kind: String, val text: String) {
    fun toJson() = linkedMapOf("a" to at, "k" to kind, "x" to text)

    companion object {
        fun from(m: Map<String, Any?>) = CaseEvent(m.long("a"), m.str("k"), m.str("x"))
    }
}

object CaseStatus {
    const val NEW = "NEW"
    const val ASSIGNED = "ASSIGNED"
    const val ON_THE_WAY = "ON_THE_WAY"
    const val RESCUED = "RESCUED"
    const val CANCELLED = "CANCELLED"
    const val UNABLE = "UNABLE"
    val OPEN = setOf(NEW, ASSIGNED, ON_THE_WAY, UNABLE)
}

data class CaseItem(
    val caseId: String,
    val victim: String,
    val payload: SosPayload,
    val packetId: String,
    val firstAt: Long,
    val lastAt: Long,
    val repeats: Int,
    val hops: Int,
    val path: List<String>,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val locAt: Long?,
    val battery: Int,
    val lowBattery: Boolean,
    val track: List<TrackPoint>,
    val media: List<MediaRef>,
    val score: Int,
    val label: String,
    val modelScore: Int,
    val reasons: List<Reason>,
    val proba: List<Double>,
    val status: String,
    val team: String,
    val rescuerName: String,
    val etaMin: Int,
    val assignedAt: Long,
    val onWayAt: Long,
    val rescuedAt: Long,
    val cancelledAt: Long,
    val duplicateOf: String,
    val drill: Boolean,
    val events: List<CaseEvent>,
    /** Another rescue base that is already handling this case (multi-base sync), empty if none. */
    val handledBy: String = "",
    /** Warning signs found by the base (possible fake or test SOS), shown to the dispatcher. */
    val flags: List<String> = emptyList(),
    /** What the rescue team reported when it closed the case ("3 rescued, 1 injured, taken to ..."). */
    val outcome: String = "",
    /** How far the team was (metres) when it set off; with the time to "rescued" it teaches the ETA model. */
    val departDistM: Int = -1,
) {
    val isOpen: Boolean get() = status in CaseStatus.OPEN

    fun toJson() = linkedMapOf(
        "c" to caseId, "v" to victim, "p" to payload.toJson(), "pk" to packetId, "fa" to firstAt, "la_" to lastAt,
        "rp" to repeats, "h" to hops, "pa" to path, "la" to lat, "lo" to lon, "ac" to accuracy, "lt" to locAt,
        "b" to battery, "lb" to lowBattery, "tr" to track.map { it.toJson() }, "me" to media.map { it.toJson() },
        "sc" to score, "lbl" to label, "ms" to modelScore,
        "rs" to reasons.map { linkedMapOf("l" to it.label, "p" to it.points) }, "pr" to proba,
        "st" to status, "tm" to team, "rn" to rescuerName, "e" to etaMin, "aa" to assignedAt, "ow" to onWayAt,
        "ra" to rescuedAt, "ca" to cancelledAt, "du" to duplicateOf, "dr" to drill, "ev" to events.map { it.toJson() },
        "hb" to handledBy, "fl" to flags, "oc" to outcome, "dd" to departDistM,
    )

    companion object {
        fun from(m: Map<String, Any?>) = CaseItem(
            m.str("c"), m.str("v"), SosPayload.fromJson(m.obj("p")), m.str("pk"), m.long("fa"), m.long("la_"),
            m.int("rp"), m.int("h", -1), m.strList("pa"), m.doubleOrNull("la"), m.doubleOrNull("lo"),
            m.doubleOrNull("ac"), m.longOrNull("lt"), m.int("b", -1), m.bool("lb"),
            m.objs("tr").map { TrackPoint.from(it) }, m.objs("me").map { MediaRef.from(it) }, m.int("sc"),
            m.str("lbl", "Low"), m.int("ms"), m.objs("rs").map { Reason(it.str("l"), it.int("p")) },
            m.list("pr").mapNotNull { (it as? Number)?.toDouble() }, m.str("st", CaseStatus.NEW), m.str("tm"),
            m.str("rn"), m.int("e", -1), m.long("aa"), m.long("ow"), m.long("ra"), m.long("ca"), m.str("du"),
            m.bool("dr"), m.objs("ev").map { CaseEvent.from(it) },
            handledBy = m.str("hb"), flags = m.strList("fl"), outcome = m.str("oc"), departDistM = m.int("dd", -1),
        )
    }
}

data class RescuerRecord(
    val rescuerId: String,
    val name: String,
    val team: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val deviceId: String = "",
    val lastSeen: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val battery: Int = -1,
    /** When the GPS fix in [lat]/[lon] was taken; 0 if unknown (then [lastSeen] is the best guess). */
    val posAt: Long = 0,
    /** Recent verified positions, oldest first (to see whether the rescuer is getting closer to a victim). */
    val trail: List<Crumb> = emptyList(),
) {
    /** Time of the latest position. */
    val positionAt: Long get() = if (posAt > 0) posAt else lastSeen

    fun toJson() = linkedMapOf(
        "i" to rescuerId, "n" to name, "tm" to team, "ia" to issuedAt, "ea" to expiresAt, "d" to deviceId,
        "ls" to lastSeen, "la" to lat, "lo" to lon, "b" to battery, "pa" to posAt, "tr" to trail.map { it.toJson() },
    )

    companion object {
        fun from(m: Map<String, Any?>) = RescuerRecord(
            m.str("i"), m.str("n"), m.str("tm"), m.long("ia"), m.long("ea"), m.str("d"), m.long("ls"),
            m.doubleOrNull("la"), m.doubleOrNull("lo"), m.int("b", -1), m.long("pa"), m.objs("tr").map { Crumb.from(it) },
        )
    }
}

data class NodeRecord(
    val deviceId: String,
    val role: String,
    val battery: Int,
    val neighbours: List<String>,
    val lastSeen: Long,
    val name: String,
    val team: String,
    val lat: Double?,
    val lon: Double?,
    val version: String,
) {
    fun toJson() = linkedMapOf(
        "d" to deviceId, "r" to role, "b" to battery, "nb" to neighbours, "ls" to lastSeen, "n" to name, "tm" to team,
        "la" to lat, "lo" to lon, "v" to version,
    )

    companion object {
        fun from(m: Map<String, Any?>) = NodeRecord(
            m.str("d"), m.str("r"), m.int("b", -1), m.strList("nb"), m.long("ls"), m.str("n"), m.str("tm"),
            m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("v"),
        )
    }
}

data class ShelterRecord(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val capacity: Int,
    val occupancy: Int,
    val water: Int,
    val food: Int,
    val medicine: Int,
    val updatedAt: Long,
    val announcedAt: Long = 0,
) {
    fun toJson() = linkedMapOf(
        "i" to id, "n" to name, "la" to lat, "lo" to lon, "cp" to capacity, "oc" to occupancy, "w" to water,
        "f" to food, "m" to medicine, "u" to updatedAt, "an" to announcedAt,
    )

    companion object {
        fun from(m: Map<String, Any?>) = ShelterRecord(
            m.str("i"), m.str("n"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.int("cp"), m.int("oc"), m.int("w"),
            m.int("f"), m.int("m"), m.long("u"), m.long("an"),
        )
    }
}

data class CheckInItem(val shelterId: String, val name: String, val deviceId: String, val at: Long) {
    fun toJson() = linkedMapOf("s" to shelterId, "n" to name, "d" to deviceId, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = CheckInItem(m.str("s"), m.str("n"), m.str("d"), m.long("a"))
    }
}

data class VolunteerItem(
    val src: String,
    val name: String,
    val phone: String,
    val skills: List<String>,
    val note: String,
    val lat: Double?,
    val lon: Double?,
    val at: Long,
) {
    fun toJson() = linkedMapOf(
        "s" to src, "n" to name, "ph" to phone, "sk" to skills, "no" to note, "la" to lat, "lo" to lon, "a" to at,
    )

    companion object {
        fun from(m: Map<String, Any?>) = VolunteerItem(
            m.str("s"), m.str("n"), m.str("ph"), m.strList("sk"), m.str("no"), m.doubleOrNull("la"),
            m.doubleOrNull("lo"), m.long("a"),
        )
    }
}

/* ---------------- rescuer side ---------------- */

object TaskSource {
    const val BASE = "BASE"
    const val DIRECT = "DIRECT"
}

data class TaskItem(
    val caseId: String,
    val victim: String,
    val lat: Double?,
    val lon: Double?,
    val label: String,
    val score: Int,
    val summary: String,
    val medical: String,
    val people: Int,
    val team: String,
    val state: String,
    val source: String,
    val at: Long,
    val updatedAt: Long,
    /** How exact the victim's position is (metres), null if not known. */
    val accuracy: Double? = null,
) {
    fun toJson() = linkedMapOf(
        "c" to caseId, "v" to victim, "la" to lat, "lo" to lon, "lb" to label, "sc" to score, "su" to summary,
        "md" to medical, "p" to people, "tm" to team, "st" to state, "so" to source, "a" to at, "u" to updatedAt,
        "ac" to accuracy,
    )

    companion object {
        const val NEW = "NEW"

        fun from(m: Map<String, Any?>) = TaskItem(
            m.str("c"), m.str("v"), m.doubleOrNull("la"), m.doubleOrNull("lo"), m.str("lb"), m.int("sc"), m.str("su"),
            m.str("md"), m.int("p", 1), m.str("tm"), m.str("st", NEW), m.str("so", TaskSource.BASE), m.long("a"),
            m.long("u"), m.doubleOrNull("ac"),
        )
    }
}

/** An SOS that a rescuer phone opened directly (rescuer group key). */
data class SeenSos(
    val caseId: String,
    val victim: String,
    val payload: SosPayload,
    val lat: Double?,
    val lon: Double?,
    val battery: Int,
    val firstAt: Long,
    val lastAt: Long,
    val hops: Int,
    val cancelled: Boolean,
    /** How exact [lat]/[lon] is (metres), null if not known. */
    val accuracy: Double? = null,
) {
    fun toJson() = linkedMapOf(
        "c" to caseId, "v" to victim, "p" to payload.toJson(), "la" to lat, "lo" to lon, "b" to battery,
        "fa" to firstAt, "la_" to lastAt, "h" to hops, "x" to cancelled, "ac" to accuracy,
    )

    companion object {
        fun from(m: Map<String, Any?>) = SeenSos(
            m.str("c"), m.str("v"), SosPayload.fromJson(m.obj("p")), m.doubleOrNull("la"), m.doubleOrNull("lo"),
            m.int("b", -1), m.long("fa"), m.long("la_"), m.int("h", -1), m.bool("x"), m.doubleOrNull("ac"),
        )
    }
}

data class TeamMsg(
    val id: String,
    val src: String,
    val name: String,
    val text: String,
    val at: Long,
    val mine: Boolean,
    /** Push-to-talk clip saved in the encrypted store ("ptt_<id>.amr"), empty for text messages. */
    val audio: String = "",
    val durationMs: Long = 0,
) {
    fun toJson() = linkedMapOf("i" to id, "s" to src, "n" to name, "x" to text, "a" to at, "m" to mine, "au" to audio, "du" to durationMs)

    companion object {
        fun from(m: Map<String, Any?>) = TeamMsg(m.str("i"), m.str("s"), m.str("n"), m.str("x"), m.long("a"), m.bool("m"), m.str("au"), m.long("du"))
    }
}

data class Crumb(val lat: Double, val lon: Double, val at: Long) {
    fun toJson() = linkedMapOf("la" to lat, "lo" to lon, "a" to at)

    companion object {
        fun from(m: Map<String, Any?>) = Crumb(m.doubleOrNull("la") ?: 0.0, m.doubleOrNull("lo") ?: 0.0, m.long("a"))
    }
}

/** Medical card filled in by the user. */
data class Profile(
    val name: String = "",
    val phone: String = "",
    val blood: String = "",
    val allergies: String = "",
    val conditions: String = "",
    val medicines: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
) {
    fun medicalSummary(): String = buildList {
        if (blood.isNotBlank()) add("Blood $blood")
        if (allergies.isNotBlank()) add("Allergies: $allergies")
        if (conditions.isNotBlank()) add("Conditions: $conditions")
        if (medicines.isNotBlank()) add("Medicines: $medicines")
    }.joinToString("; ")

    fun toJson() = linkedMapOf(
        "n" to name, "ph" to phone, "bg" to blood, "al" to allergies, "co" to conditions, "me" to medicines,
        "cn" to contactName, "cp" to contactPhone,
    )

    companion object {
        fun from(m: Map<String, Any?>) = Profile(
            m.str("n"), m.str("ph"), m.str("bg"), m.str("al"), m.str("co"), m.str("me"), m.str("cn"), m.str("cp"),
        )
    }
}

fun encodeList(items: List<Map<String, Any?>>): String = Json.stringify(items)

@Suppress("UNCHECKED_CAST")
fun decodeList(text: String?): List<Map<String, Any?>> = try {
    (Json.parse(text ?: "[]") as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
} catch (e: Exception) {
    emptyList()
}
