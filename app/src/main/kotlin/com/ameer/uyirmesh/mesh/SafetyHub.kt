package com.ameer.uyirmesh.mesh

import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.ChildFound
import com.ameer.uyirmesh.core.ChildTag
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.HazardKind
import com.ameer.uyirmesh.core.HazardPayload
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketFlags
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.PollAnswer
import com.ameer.uyirmesh.core.PollKind
import com.ameer.uyirmesh.core.PollPayload
import com.ameer.uyirmesh.core.Revocations
import com.ameer.uyirmesh.core.RevokePayload
import com.ameer.uyirmesh.core.SensorKind
import com.ameer.uyirmesh.core.SensorPayload
import com.ameer.uyirmesh.data.AlertItem
import com.ameer.uyirmesh.data.ChildItem
import com.ameer.uyirmesh.data.PollItem
import com.ameer.uyirmesh.data.RevokedItem
import com.ameer.uyirmesh.data.SensorItem
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier

/**
 * Version 2 messages that every phone handles: verified hazard markers, polls and roll calls from the base,
 * revoked rescuer IDs, sensor readings and lost-child wristbands.
 */
class SafetyHub(private val ctx: Context) {
    private val store get() = Graph.store
    private val myName: String
        get() = Graph.store.profile.value.name.ifBlank { Graph.prefs.now.name }.ifBlank { "Phone " + Graph.prefs.now.deviceId.take(4) }

    /* ---------------- hazards ---------------- */

    /**
     * A hazard marked by a rescuer (or the base) becomes a danger zone on every phone, so all the existing danger
     * screens, the map and "you are inside this zone" warnings show it. [signer] is the verified rescuer name or "BASE".
     */
    fun onHazard(p: Packet, h: HazardPayload, signer: String) {
        val now = System.currentTimeMillis()
        val existing = store.alerts.value.firstOrNull { it.id == h.hazardId }
        if (existing != null && existing.at >= h.at) return
        val who = if (signer == "BASE") L.t("rescue_base") else L.t("verified_rescuer").replace("{n}", signer)
        val item = AlertItem(
            h.hazardId, AlertKind.DANGER, 3, hazardLabel(h.kind), listOf(h.note, who).filter { it.isNotBlank() }.joinToString(" · "),
            h.lat, h.lon, h.radiusM, 0, 0, if (h.cleared) now - 1 else now + 24 * 3600_000L, h.at, now, p.isDrill, p.src == Graph.prefs.now.deviceId,
        )
        store.alerts.upsert({ it.id }, item)
        Graph.log("ALERT", "Hazard ${h.kind}${if (h.cleared) " cleared" else ""} from $signer (signature verified)")
        if (!h.cleared && existing == null) {
            Notifier.alert(ctx, h.hazardId, L.t("alert_danger") + ": " + hazardLabel(h.kind), item.message, critical = false)
        }
    }

    /* ---------------- polls and roll calls ---------------- */

    fun onPoll(p: Packet, poll: PollPayload) {
        val now = System.currentTimeMillis()
        val existing = store.polls.value.firstOrNull { it.pollId == poll.pollId }
        if (existing != null) return
        store.polls.add(PollItem(poll.pollId, poll.kind, poll.question, poll.options, poll.validUntil, poll.at, now, drill = p.isDrill))
        val title = if (poll.kind == PollKind.ROLLCALL) L.t("rollcall") else L.t("poll_from_base")
        Notifier.message(ctx, Notifier.ID_MSG + 2, title, poll.question, "poll/${poll.pollId}")
        Graph.alarm.vibrateReply()
        Graph.log("POLL", "${poll.kind} from the base: ${poll.question}")
    }

    /** Sends this phone's answer, sealed so only the base can read it. */
    fun answerPoll(pollId: String, choice: Int) {
        val item = store.polls.value.firstOrNull { it.pollId == pollId } ?: return
        val now = System.currentTimeMillis()
        val loc = Graph.loc.last
        val a = PollAnswer(pollId, choice, myName, store.profile.value.phone.ifBlank { Graph.prefs.now.phone }, loc?.latitude, loc?.longitude, now)
        if (Graph.prefs.now.role == com.ameer.uyirmesh.core.Roles.BASE && Graph.base.hasKeys()) {
            Graph.baseOps.onPollAnswer(Graph.prefs.now.deviceId, a)
        } else {
            Graph.mesh.send(Graph.mesh.outbox.sealedBase(PacketType.POLL_ANSWER, a.toBytes()))
        }
        store.polls.modify({ it.pollId }, pollId) { it.copy(answered = choice, answeredAt = now) }
        Graph.log("POLL", "Answered \"${item.options.getOrNull(choice) ?: choice}\"")
    }

    /* ---------------- revoked rescuer IDs ---------------- */

    fun onRevoke(r: RevokePayload) {
        val now = System.currentTimeMillis()
        val known = store.revoked.value.map { it.rescuerId }.toSet()
        val new = r.rescuerIds.filter { it !in known }
        if (new.isEmpty()) return
        Revocations.addAll(new)
        for ((i, id) in r.rescuerIds.withIndex()) {
            if (id in new) store.revoked.add(RevokedItem(id, r.names.getOrNull(i) ?: "", now))
        }
        Graph.log("SECURITY", "Base revoked ${new.size} rescuer ID(s); messages signed with them are now rejected")
        // A phone whose own rescuer ID was revoked logs out at once.
        val mine = Graph.rescuer.bundle()?.cert?.rescuerId
        if (mine != null && mine in new) {
            Graph.rescuer.logout()
            Notifier.message(ctx, Notifier.ID_TEAM, L.t("rescuer_id"), L.t("id_revoked_here"), "home")
        }
    }

    /* ---------------- sensors ---------------- */

    fun onSensor(p: Packet, s: SensorPayload) {
        val now = System.currentTimeMillis()
        val old = store.sensors.value.firstOrNull { it.sensorId == s.sensorId }
        if (old != null && old.at >= s.at) return
        store.sensors.upsert(
            { it.sensorId },
            SensorItem(s.sensorId, s.kind, s.value, s.unit, s.alarm, s.battery, s.lat, s.lon, s.relayedBy, s.at, now),
        )
        if (s.alarm && (old == null || !old.alarm)) {
            Notifier.alert(ctx, "sensor-" + s.sensorId, L.t("sensor_alarm"), sensorText(s.kind, s.value, s.unit) + " · " + L.t("unverified_sensor"), critical = false)
            Graph.log("SENSOR", "Sensor ${s.sensorId} alarm: ${s.kind} ${s.value} ${s.unit}")
        }
    }

    /** Passes a reading heard over Bluetooth into the mesh (and keeps it here too). */
    fun relaySensor(s: SensorPayload) {
        val loc = Graph.loc.usable()
        val withPlace = s.copy(lat = s.lat ?: loc?.latitude, lon = s.lon ?: loc?.longitude, relayedBy = myName)
        val last = store.sensors.value.firstOrNull { it.sensorId == s.sensorId }
        // One reading a minute per sensor is plenty (alarm changes go at once).
        if (last != null && withPlace.at - last.at < 60_000 && last.alarm == withPlace.alarm) return
        val p = Graph.mesh.outbox.plain(PacketType.SENSOR, withPlace.toBytes(), com.ameer.uyirmesh.core.Destinations.ALL)
        Graph.mesh.send(p)
        onSensor(p, withPlace)
    }

    /* ---------------- lost-child wristband ---------------- */

    fun registerChild(name: String): ChildItem {
        val tag = ChildTag.create(name, Graph.prefs.now.deviceId)
        val item = ChildItem(tag.tagId, tag.childName, tag.toQr(), System.currentTimeMillis())
        store.children.add(item)
        Graph.log("CHILD", "Wristband QR made for ${tag.childName}")
        return item
    }

    /** The finder's side: tells the parent's phone where the child is. */
    fun sendChildFound(tag: ChildTag, note: String): Boolean {
        val loc = Graph.loc.last
        val now = System.currentTimeMillis()
        val found = ChildFound(tag.tagId, myName, store.profile.value.phone.ifBlank { Graph.prefs.now.phone }, note.take(200), loc?.latitude, loc?.longitude, now)
        val id = Packet.newId()
        val box = Crypto.keyEncrypt(tag.key, found.toBytes(), Packet.aadFor(PacketType.CHILD_FOUND, id, Graph.prefs.now.deviceId))
        val ttl = maxOf(Graph.mesh.outbox.ttl, 8)
        val p = Packet.create(
            PacketType.CHILD_FOUND, Graph.prefs.now.deviceId, tag.parentDevice, box, ttl, now,
            PacketFlags.ENC_TEAM or (if (Graph.prefs.now.drill) PacketFlags.DRILL else 0), id,
        )
        Graph.mesh.send(p)
        Graph.log("CHILD", "Told the parent of ${tag.childName} where the child is")
        return true
    }

    /** The parent's side: try the keys of this phone's wristbands. */
    fun onChildFound(p: Packet) {
        for (c in store.children.value) {
            val tag = try {
                ChildTag.fromQr(c.qr)
            } catch (e: Exception) {
                continue
            }
            val plain = try {
                Crypto.keyDecrypt(tag.key, p.payload, p.aad())
            } catch (e: Exception) {
                continue
            }
            val f = ChildFound.fromBytes(plain)
            if (f.tagId != c.tagId) continue
            store.children.modify({ it.tagId }, c.tagId) {
                it.copy(foundAt = f.at, finder = f.finderName, finderPhone = f.finderPhone, note = f.note, lat = f.lat, lon = f.lon)
            }
            Notifier.alert(ctx, "child-" + c.tagId, L.t("child_found_title").replace("{n}", c.name), listOf(f.finderName, f.finderPhone, f.note).filter { it.isNotBlank() }.joinToString(" · "), critical = true)
            Graph.alarm.vibrateSos()
            Graph.log("CHILD", "Someone found ${c.name}")
            return
        }
    }

    companion object {
        fun hazardLabel(kind: String): String = when (kind) {
            HazardKind.BRIDGE_DOWN -> L.t("hz_bridge")
            HazardKind.LIVE_WIRE -> L.t("hz_wire")
            HazardKind.DEEP_WATER -> L.t("hz_water")
            HazardKind.LANDSLIDE -> L.t("hz_landslide")
            HazardKind.FIRE -> L.t("hz_fire")
            HazardKind.GAS -> L.t("hz_gas")
            HazardKind.COLLAPSE -> L.t("hz_collapse")
            else -> L.t("hz_other")
        }

        fun sensorText(kind: String, value: Double, unit: String): String = when (kind) {
            SensorKind.WATER_LEVEL -> L.t("sensor_water").replace("{v}", "%.0f %s".format(value, unit))
            SensorKind.RAIN -> L.t("sensor_rain").replace("{v}", "%.0f %s".format(value, unit))
            else -> L.t("sensor_gas").replace("{v}", "%.0f %s".format(value, unit))
        }
    }
}
