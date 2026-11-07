package com.ameer.uyirmesh.base

import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.AlertPayload
import com.ameer.uyirmesh.core.BeaconPayload
import com.ameer.uyirmesh.core.CaseSync
import com.ameer.uyirmesh.core.Destinations
import com.ameer.uyirmesh.core.HazardPayload
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.PollAnswer
import com.ameer.uyirmesh.core.PollKind
import com.ameer.uyirmesh.core.PollPayload
import com.ameer.uyirmesh.core.Revocations
import com.ameer.uyirmesh.core.RevokePayload
import com.ameer.uyirmesh.data.CaseEvent
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.data.PollItem
import com.ameer.uyirmesh.data.PollResponse
import com.ameer.uyirmesh.data.RevokedItem
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier

/**
 * Version 2 work of the rescue base: polls and roll calls, revoking lost rescuer IDs, hazard markers, the internet
 * relay address, and keeping several rescue bases in step (case sync and hand-over).
 */
class BaseOps(private val ctx: Context) {
    private val store get() = Graph.store
    private var lastRevokeBroadcast = 0L
    private var lastBeacon = 0L

    private fun ttl() = maxOf(Graph.mesh.outbox.ttl, 8)

    /* ---------------- polls and roll calls ---------------- */

    fun sendPoll(kind: String, question: String, options: List<String>, validMinutes: Int): Boolean {
        val keys = Graph.base.keys() ?: return false
        val now = System.currentTimeMillis()
        val clean = options.map { it.trim().take(40) }.filter { it.isNotEmpty() }.take(6)
        if (question.isBlank() || clean.size < 2) return false
        val poll = PollPayload(Ids.newId(), kind, question.trim().take(200), clean, validUntil = if (validMinutes > 0) now + validMinutes * 60_000L else 0, at = now)
        val p = Graph.mesh.outbox.signedByBase(keys, PacketType.POLL, poll.toBytes(), Destinations.ALL, ttl())
        Graph.mesh.send(p)
        store.polls.add(PollItem(poll.pollId, kind, poll.question, poll.options, poll.validUntil, now, now, mine = true, drill = p.isDrill))
        Graph.log("BASE", "${if (kind == PollKind.ROLLCALL) "Roll call" else "Poll"} sent: ${poll.question}")
        return true
    }

    /** One phone's answer; the latest answer from each phone counts. */
    fun onPollAnswer(src: String, a: PollAnswer) {
        if (store.polls.value.none { it.pollId == a.pollId }) return
        val r = PollResponse(a.pollId, src, a.choice, a.name, a.phone, a.lat, a.lon, a.at)
        store.pollAnswers.edit { list -> list.filterNot { it.pollId == a.pollId && it.src == src } + r }
        val poll = store.polls.value.first { it.pollId == a.pollId }
        if (poll.kind == PollKind.ROLLCALL && a.choice == 1) {
            Notifier.rescue(ctx, "roll-" + src, L.t("rollcall_needs_help"), a.name.ifBlank { src.take(6) }, "base", task = false)
        }
    }

    fun responses(pollId: String): List<PollResponse> = store.pollAnswers.value.filter { it.pollId == pollId }

    fun counts(poll: PollItem): List<Int> {
        val rs = responses(poll.pollId)
        return poll.options.indices.map { i -> rs.count { it.choice == i } }
    }

    /* ---------------- revoked rescuer IDs ---------------- */

    fun revoke(rescuerId: String, name: String) {
        if (store.revoked.value.none { it.rescuerId == rescuerId }) store.revoked.add(RevokedItem(rescuerId, name, System.currentTimeMillis()))
        Revocations.addAll(listOf(rescuerId))
        broadcastRevocations()
        Graph.log("BASE", "Rescuer ID of $name revoked")
    }

    /** Sends the full revoke list (signed); repeated every 30 minutes so phones that join later learn it too. */
    fun broadcastRevocations() {
        val keys = Graph.base.keys() ?: return
        val list = store.revoked.value
        if (list.isEmpty()) return
        val r = RevokePayload(list.map { it.rescuerId }, list.map { it.name }, System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.REVOKE, r.toBytes(), Destinations.ALL, ttl()))
        lastRevokeBroadcast = System.currentTimeMillis()
    }

    /* ---------------- hazards and the internet relay ---------------- */

    fun sendHazard(kind: String, note: String, lat: Double?, lon: Double?, radiusM: Int, cleared: Boolean = false, hazardId: String = Ids.newId()): Boolean {
        val keys = Graph.base.keys() ?: return false
        val now = System.currentTimeMillis()
        val h = HazardPayload(hazardId, kind, note.take(200), lat, lon, radiusM.coerceIn(10, 5_000), Graph.prefs.now.baseName, "", cleared, now)
        val p = Graph.mesh.outbox.signedByBase(keys, PacketType.HAZARD, h.toBytes(), Destinations.ALL, ttl())
        Graph.mesh.send(p)
        Graph.safety.onHazard(p, h, "BASE")
        return true
    }

    /** Tells every phone the internet relay address (a signed alert of kind GATEWAY, not shown in the alert list). */
    fun announceGateway(url: String): Boolean {
        val keys = Graph.base.keys() ?: return false
        val a = AlertPayload("gateway", AlertKind.GATEWAY, 1, url.trim().take(80), "", at = System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.ALERT, a.toBytes(), Destinations.ALL, ttl()))
        Graph.log("BASE", "Internet relay address announced: ${url.trim()}")
        return true
    }

    /* ---------------- several bases ---------------- */

    /** Tells the other bases what this base did with a case ([handoverTo] = ask that base to take it over). */
    fun syncCase(c: CaseItem, handoverTo: String = "", note: String = "") {
        val keys = Graph.base.keys() ?: return
        val s = CaseSync(c.caseId, c.status, c.team, Graph.prefs.now.baseName, c.etaMin, handoverTo, note, System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.BASE_SYNC, s.toBytes(), Destinations.BASE, ttl()))
    }

    fun handOver(caseId: String, toBase: String, note: String) {
        val c = store.cases.value.firstOrNull { it.caseId == caseId } ?: return
        val now = System.currentTimeMillis()
        val updated = c.copy(handledBy = toBase.trim(), events = c.events + CaseEvent(now, "HANDOVER", "Handed over to base ${toBase.trim()}${if (note.isNotBlank()) ": $note" else ""}"))
        store.cases.upsert({ it.caseId }, updated)
        syncCase(updated, handoverTo = toBase.trim(), note = note)
        Graph.log("BASE", "Case ${caseId.take(6)} handed over to $toBase")
    }

    fun onCaseSync(p: Packet, s: CaseSync) {
        val me = Graph.prefs.now.baseName
        val c = store.cases.value.firstOrNull { it.caseId == s.caseId } ?: return
        val now = System.currentTimeMillis()
        if (s.handoverTo.isNotBlank() && s.handoverTo.equals(me, ignoreCase = true)) {
            val updated = c.copy(
                handledBy = "",
                events = c.events + CaseEvent(now, "HANDOVER", "Base ${s.baseName} handed this case to you${if (s.note.isNotBlank()) ": ${s.note}" else ""}"),
            )
            store.cases.upsert({ it.caseId }, updated)
            Notifier.rescue(ctx, c.caseId + "h", L.t("handover_to_you").replace("{b}", s.baseName), Graph.base.summary(updated), "base", task = false)
            if (Graph.prefs.now.autoAssign) Graph.base.autoAssign()
            return
        }
        if (s.handoverTo.isNotBlank()) return // handed to a third base: nothing to do here
        val mineActive = c.handledBy.isBlank() && (c.status == CaseStatus.ASSIGNED || c.status == CaseStatus.ON_THE_WAY) && c.team.isNotBlank()
        val text = "Base ${s.baseName}: ${s.status.lowercase().replace('_', ' ')}${if (s.team.isNotBlank()) ", team ${s.team}" else ""}"
        val updated = if (mineActive && s.status != CaseStatus.RESCUED) {
            // Both bases sent a team: keep ours but show the dispatcher.
            c.copy(events = c.events + CaseEvent(now, "SYNC", "$text (this base also has team ${c.team} on it)"))
        } else {
            c.copy(
                handledBy = s.baseName,
                status = if (s.status in listOf(CaseStatus.ASSIGNED, CaseStatus.ON_THE_WAY, CaseStatus.RESCUED, CaseStatus.CANCELLED)) s.status else c.status,
                etaMin = if (s.etaMin > 0) s.etaMin else c.etaMin,
                rescuedAt = if (s.status == CaseStatus.RESCUED && c.rescuedAt == 0L) now else c.rescuedAt,
                events = c.events + CaseEvent(now, "SYNC", text),
            )
        }
        store.cases.upsert({ it.caseId }, updated)
    }

    fun periodic(now: Long = System.currentTimeMillis()) {
        if (now - lastRevokeBroadcast > 30 * 60_000L) broadcastRevocations()
        if (now - lastBeacon > BEACON_EVERY_MS) sendBeacon()
    }

    /** "The base is here" for every phone: name, position and open cases, signed with the base key. */
    fun sendBeacon(): Boolean {
        val keys = Graph.base.keys() ?: return false
        val pos = Graph.loc.lastLatLon
        val b = BeaconPayload(Graph.prefs.now.baseName, pos?.lat, pos?.lon, store.cases.value.count { it.isOpen }, System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.BASE_BEACON, b.toBytes(), Destinations.ALL, ttl()))
        lastBeacon = System.currentTimeMillis()
        return true
    }

    companion object {
        const val BEACON_EVERY_MS = 10 * 60_000L
    }
}
