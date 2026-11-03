package com.ameer.uyirmesh.sos

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AckPayload
import com.ameer.uyirmesh.core.AckStatus
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.EmergencyKind
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.Hex
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.SosCancel
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.SosUpdate
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.data.MySos
import com.ameer.uyirmesh.data.SosReply
import com.ameer.uyirmesh.mesh.Mesh
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the person fills in on the SOS screen (all optional except the kind). */
data class SosForm(
    val kind: String = EmergencyKind.TRAPPED,
    val people: Int = 1,
    val children: Int = 0,
    val elderly: Int = 0,
    val disabled: Int = 0,
    val pregnant: Int = 0,
    val injured: Boolean = false,
    val needs: List<String> = emptyList(),
    val note: String = "",
)

/**
 * Victim side: creating and sending the SOS, follow-up location updates (every 2 minutes while
 * the SOS is open, and a last one at 5% battery), voice notes and photos, cancelling, and the
 * replies from the base and rescuers that make up the status timeline.
 */
class Victim(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)
    private val store get() = Graph.store
    private var lastSosAt = 0L

    fun start() {
        scope.launch {
            delay(5_000)
            if (active() != null) Graph.loc.startTracking(60_000)
            while (true) {
                try {
                    followUp()
                } catch (e: Exception) {
                    Graph.note("sos follow-up", e)
                }
                delay(30_000)
            }
        }
    }

    fun active(): MySos? = store.mySos.value.lastOrNull { it.active }

    /** Default form for hands-free SOS: the last kind and group size the person used. */
    fun lastForm(): SosForm {
        val last = store.mySos.value.lastOrNull()?.payload ?: return SosForm()
        return SosForm(last.kind, last.people, last.children, last.elderly, last.disabled, last.pregnant, last.injured, last.needs)
    }

    /** Sends an SOS. If one is already open, it is sent again with the new details (same case). */
    @Synchronized
    fun sendSos(form: SosForm, trigger: String): MySos {
        MeshService.ensureRunning(ctx)
        val now = System.currentTimeMillis()
        lastSosAt = now
        val profile = store.profile.value
        val s = Graph.prefs.now
        val existing = active()
        val caseId = existing?.caseId ?: Ids.newId()
        val secret = existing?.cancelSecret ?: Hex.encode(Crypto.random(16))
        val loc = Graph.loc.last?.takeIf { Graph.loc.ageMs() < 15 * 60_000 }
        val payload = SosPayload(
            caseId = caseId,
            kind = form.kind,
            people = form.people.coerceIn(1, 999),
            children = form.children.coerceIn(0, 999),
            elderly = form.elderly.coerceIn(0, 999),
            disabled = form.disabled.coerceIn(0, 999),
            pregnant = form.pregnant.coerceIn(0, 999),
            injured = form.injured,
            needs = form.needs,
            note = form.note.take(300),
            battery = Mesh.batteryPercent(ctx),
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracy = loc?.accuracy?.toDouble(),
            locTime = loc?.time,
            name = profile.name.ifBlank { s.name }.take(60),
            phone = profile.phone.ifBlank { s.phone }.take(30),
            blood = profile.blood.take(8),
            medical = profile.medicalSummary().take(300),
            trigger = trigger,
            cancelHash = Crypto.sha256Hex(secret),
            sentAt = now,
        )
        val packet = Graph.mesh.outbox.sealedRescue(PacketType.SOS, payload.toBytes())
        Graph.mesh.send(packet)
        val sos = if (existing != null) {
            existing.copy(payload = payload, drill = packet.isDrill)
        } else {
            MySos(caseId, payload, secret, packet.idHex, now, packet.isDrill)
        }
        store.mySos.upsert({ it.caseId }, sos)
        store.flush()
        Graph.log("SOS", "SOS ${if (existing != null) "re-sent" else "sent"} (${payload.kind}, ${payload.people} people, trigger $trigger, packet ${packet.shortId}, ${if (loc != null) "with GPS" else "no GPS yet"})")
        Graph.alarm.vibrateSent()
        Notifier.sosStatus(ctx, L.t("sos_sent_title"), L.t("sos_sent_text"), loud = false)
        Graph.loc.startTracking(60_000)
        // Ask for a fresh GPS fix and send it as soon as it arrives.
        scope.launch(Dispatchers.Main) {
            Graph.loc.requestOnce(45_000) { fix ->
                val cur = active() ?: return@requestOnce
                if (cur.caseId != caseId || fix == null) return@requestOnce
                val p = cur.payload
                val moved = if (p.lat == null || p.lon == null) Double.MAX_VALUE else Geo.distanceM(p.lat!!, p.lon!!, fix.latitude, fix.longitude)
                val better = p.accuracy == null || fix.accuracy < (p.accuracy!! / 2)
                if (moved > 15 || better) scope.launch { sendUpdate(cur, low = false, note = "") }
            }
        }
        return sos
    }

    /** Hands-free SOS (voice, shake, widget, tile, notification): uses the last form. */
    fun quickSos(trigger: String): MySos = sendSos(lastForm(), trigger)

    fun recentlySent(): Boolean = System.currentTimeMillis() - lastSosAt < 20_000

    @Synchronized
    fun sendUpdate(sos: MySos, low: Boolean, note: String) {
        val loc = Graph.loc.last
        val now = System.currentTimeMillis()
        val upd = SosUpdate(
            sos.caseId, loc?.latitude, loc?.longitude, loc?.accuracy?.toDouble(), loc?.time,
            Mesh.batteryPercent(ctx), low, note.take(300), now,
        )
        Graph.mesh.send(Graph.mesh.outbox.sealedRescue(PacketType.SOS_UPDATE, upd.toBytes()))
        store.mySos.modify({ it.caseId }, sos.caseId) {
            it.copy(
                lastUpdateAt = now, updatesSent = it.updatesSent + 1, lowBatterySent = it.lowBatterySent || low,
                payload = if (loc != null) it.payload.copy(lat = loc.latitude, lon = loc.longitude, accuracy = loc.accuracy.toDouble(), locTime = loc.time) else it.payload,
            )
        }
        Graph.log("SOS", "Location update sent${if (low) " (battery low, last position)" else ""}")
    }

    private fun followUp() {
        val sos = active() ?: return
        val now = System.currentTimeMillis()
        val battery = Mesh.batteryPercent(ctx)
        if (battery in 0..5 && !sos.lowBatterySent) {
            sendUpdate(sos, low = true, note = "")
            return
        }
        val interval = if (Graph.prefs.now.batterySaver) 5 * 60_000L else 2 * 60_000L
        val last = maxOf(sos.lastUpdateAt, sos.sentAt)
        if (now - last >= interval) sendUpdate(sos, low = false, note = "")
    }

    /** Sends a voice note (AMR) or photo (JPEG) for the open SOS. */
    fun sendMedia(mime: String, durationMs: Long, data: ByteArray): Boolean {
        val sos = active() ?: return false
        if (data.isEmpty() || data.size > SosMedia.MAX_MEDIA_BYTES) {
            Graph.log("SOS", "Media too large (${data.size} bytes), not sent")
            return false
        }
        val media = SosMedia(sos.caseId, mime, durationMs, data, System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.sealedRescue(PacketType.SOS_MEDIA, media.toBytes()))
        store.mySos.modify({ it.caseId }, sos.caseId) {
            if (mime.startsWith("audio")) it.copy(voiceSent = it.voiceSent + 1) else it.copy(photoSent = it.photoSent + 1)
        }
        Graph.log("SOS", "${if (mime.startsWith("audio")) "Voice note" else "Photo"} sent (${data.size / 1024} KB)")
        return true
    }

    fun cancel() {
        val sos = active() ?: return
        val now = System.currentTimeMillis()
        val c = SosCancel(sos.caseId, sos.cancelSecret, now)
        val myId = Graph.prefs.now.deviceId
        Graph.mesh.engine.forgetWhere { it.src == myId && (it.type == PacketType.SOS || it.type == PacketType.SOS_UPDATE || it.type == PacketType.SOS_MEDIA) }
        Graph.mesh.send(Graph.mesh.outbox.sealedRescue(PacketType.SOS_CANCEL, c.toBytes()))
        store.mySos.modify({ it.caseId }, sos.caseId) { it.copy(cancelledAt = now) }
        Graph.loc.stopTracking()
        Graph.alarm.stopAll()
        Notifier.cancel(ctx, Notifier.ID_SOS_STATUS)
        Graph.log("SOS", "SOS cancelled by the user")
    }

    fun onHandedOff(p: Packet, neighbours: Int) {
        if (p.type != PacketType.SOS) return
        val sos = active() ?: return
        if (sos.handedAt != 0L) return
        store.mySos.modify({ it.caseId }, sos.caseId) { it.copy(handedAt = System.currentTimeMillis(), handedTo = neighbours) }
        Graph.log("SOS", "SOS handed to $neighbours nearby phone(s)")
    }

    fun onReply(p: Packet, ack: AckPayload, verifiedBy: String) {
        val sos = store.mySos.value.lastOrNull { it.caseId == ack.caseId } ?: return
        val now = System.currentTimeMillis()
        val reply = SosReply(ack.at.takeIf { it > 0 } ?: now, ack.status, ack.team, ack.etaMin, ack.message, verifiedBy)
        val duplicate = sos.replies.any { it.status == reply.status && it.message == reply.message && it.verifiedBy == reply.verifiedBy && it.etaMin == reply.etaMin }
        if (duplicate) return
        val updated = store.mySos.modify({ it.caseId }, sos.caseId) { s ->
            var n = s.copy(replies = s.replies + reply)
            when (ack.status) {
                AckStatus.RECEIVED -> {
                    if (n.baseAt == 0L) n = n.copy(baseAt = now, baseHops = ack.hops)
                }
                AckStatus.ASSIGNED -> {
                    n = n.copy(
                        assignedAt = if (n.assignedAt == 0L) now else n.assignedAt,
                        team = ack.team.ifBlank { n.team }, etaMin = if (ack.etaMin > 0) ack.etaMin else n.etaMin,
                        rescuer = if (verifiedBy != "BASE") verifiedBy else n.rescuer,
                    )
                }
                AckStatus.ON_THE_WAY -> {
                    n = n.copy(
                        onWayAt = if (n.onWayAt == 0L) now else n.onWayAt, team = ack.team.ifBlank { n.team },
                        etaMin = if (ack.etaMin > 0) ack.etaMin else n.etaMin,
                        rescuer = if (verifiedBy != "BASE") verifiedBy else ack.by.ifBlank { n.rescuer },
                    )
                }
                AckStatus.RESCUED -> {
                    n = n.copy(rescuedAt = now)
                }
                AckStatus.CANCELLED -> {
                    if (n.cancelledAt == 0L) n = n.copy(cancelledAt = now)
                }
            }
            // A base reply also proves the SOS reached the base.
            if (verifiedBy == "BASE" && n.baseAt == 0L) n = n.copy(baseAt = now, baseHops = ack.hops)
            n
        } ?: return
        val who = if (verifiedBy == "BASE") L.t("rescue_base") else verifiedBy
        val text = when (ack.status) {
            AckStatus.RECEIVED -> L.t("reply_received").replace("{h}", if (ack.hops > 0) ack.hops.toString() else "?")
            AckStatus.ASSIGNED -> L.t("reply_assigned").replace("{t}", ack.team).replace("{e}", etaText(ack.etaMin))
            AckStatus.ON_THE_WAY -> L.t("reply_on_way").replace("{w}", who).replace("{e}", etaText(ack.etaMin))
            AckStatus.RESCUED -> L.t("reply_rescued")
            AckStatus.CANCELLED -> L.t("reply_cancelled")
            else -> ack.message
        } + if (ack.message.isNotBlank() && ack.status != AckStatus.RECEIVED) "\n${ack.message}" else ""
        Notifier.sosStatus(ctx, L.t("reply_from").replace("{w}", who), text, loud = true)
        Graph.alarm.vibrateReply()
        Graph.log("SOS", "Reply ${ack.status} from $who (verified signature)")
        if (!updated.active) {
            Graph.loc.stopTracking()
            val myId = Graph.prefs.now.deviceId
            Graph.mesh.engine.forgetWhere { it.src == myId && (it.type == PacketType.SOS || it.type == PacketType.SOS_UPDATE) }
        }
    }

    private fun etaText(min: Int) = if (min > 0) "$min min" else "-"

    companion object {
        fun triggerName(t: String): String = when (t) {
            Triggers.VOICE -> L.t("trigger_voice")
            Triggers.SHAKE -> L.t("trigger_shake")
            Triggers.WIDGET -> L.t("trigger_widget")
            Triggers.TILE -> L.t("trigger_tile")
            Triggers.SHORTCUT -> L.t("trigger_shortcut")
            Triggers.NOTIFICATION -> L.t("trigger_notification")
            else -> L.t("trigger_button")
        }
    }
}
