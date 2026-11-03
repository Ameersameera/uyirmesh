package com.ameer.uyirmesh.mesh

import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.AlertPayload
import com.ameer.uyirmesh.core.ChatPayload
import com.ameer.uyirmesh.core.Destinations
import com.ameer.uyirmesh.core.FindRequest
import com.ameer.uyirmesh.core.FindResult
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.MissingPayload
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.ReportPayload
import com.ameer.uyirmesh.core.SafePayload
import com.ameer.uyirmesh.core.VolunteerPayload
import com.ameer.uyirmesh.data.AlertItem
import com.ameer.uyirmesh.data.ChatItem
import com.ameer.uyirmesh.data.FindAnswer
import com.ameer.uyirmesh.data.MissingItem
import com.ameer.uyirmesh.data.ReportItem
import com.ameer.uyirmesh.data.SafeItem
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier

/** Public messages everyone can read: group chat, alerts, "I'm safe", citizen reports, missing persons. */
class PublicHub(private val ctx: Context) {
    private val store get() = Graph.store
    private val myName: String get() = Graph.store.profile.value.name.ifBlank { Graph.prefs.now.name }.ifBlank { "Phone " + Graph.prefs.now.deviceId.take(4) }

    private fun lat() = Graph.loc.last?.latitude
    private fun lon() = Graph.loc.last?.longitude

    /* ---------------- sending ---------------- */

    fun sendChat(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        val p = Graph.mesh.outbox.plain(PacketType.CHAT, ChatPayload(myName, t, now).toBytes(), Destinations.ALL)
        Graph.mesh.send(p)
        store.chat.add(ChatItem(p.idHex, Graph.prefs.now.deviceId, myName, t, now, true, p.isDrill))
    }

    fun sendSafe(message: String, place: String) {
        val now = System.currentTimeMillis()
        val safe = SafePayload(myName, message.take(200), lat(), lon(), place.take(80), now)
        val p = Graph.mesh.outbox.plain(PacketType.SAFE, safe.toBytes(), Destinations.ALL)
        Graph.mesh.send(p)
        store.safe.add(SafeItem(p.idHex, Graph.prefs.now.deviceId, safe.name, safe.message, safe.lat, safe.lon, safe.place, now, true))
        Graph.log("SAFE", "I'm safe sent${if (place.isNotBlank()) " ($place)" else ""}")
        if (Graph.base.hasKeys()) Graph.base.onSafe(p, safe)
    }

    fun sendReport(kind: String, note: String) {
        val now = System.currentTimeMillis()
        val r = ReportPayload(Ids.newId(), kind, note.take(300), lat(), lon(), myName, now)
        val p = Graph.mesh.outbox.plain(PacketType.REPORT, r.toBytes(), Destinations.ALL)
        Graph.mesh.send(p)
        store.reports.add(ReportItem(r.reportId, Graph.prefs.now.deviceId, kind, r.note, r.lat, r.lon, r.name, now, true))
        Graph.log("REPORT", "Report sent: $kind")
    }

    fun sendMissing(person: String, age: Int, description: String, lastSeen: String, contact: String) {
        val now = System.currentTimeMillis()
        val m = MissingPayload(Ids.newId(), person.take(60), age, description.take(300), lastSeen.take(120), contact.take(30), myName, lat(), lon(), false, now)
        Graph.mesh.send(Graph.mesh.outbox.plain(PacketType.MISSING, m.toBytes(), Destinations.ALL))
        store.missing.add(MissingItem(m.noticeId, Graph.prefs.now.deviceId, m.person, m.age, m.description, m.lastSeen, m.contact, m.reporter, m.lat, m.lon, false, now, true))
        Graph.log("MISSING", "Missing person notice sent for ${m.person}")
    }

    fun markFound(item: MissingItem) {
        val now = System.currentTimeMillis()
        val m = MissingPayload(item.id, item.person, item.age, item.description, item.lastSeen, item.contact, myName, item.lat, item.lon, true, now)
        Graph.mesh.send(Graph.mesh.outbox.plain(PacketType.MISSING, m.toBytes(), Destinations.ALL))
        store.missing.modify({ it.id }, item.id) { it.copy(found = true, at = now) }
    }

    fun sendVolunteer(skills: List<String>, note: String) {
        val now = System.currentTimeMillis()
        val profile = store.profile.value
        val v = VolunteerPayload(myName, profile.phone.ifBlank { Graph.prefs.now.phone }, skills, note.take(300), lat(), lon(), now)
        Graph.mesh.send(Graph.mesh.outbox.sealedBase(PacketType.VOLUNTEER, v.toBytes()))
        Graph.prefs.putLong("volunteeredAt", now)
        Graph.log("VOLUNTEER", "Offered help: ${skills.joinToString()}")
    }

    fun sendFind(query: String) {
        val q = query.trim().take(60)
        if (q.isEmpty()) return
        val f = FindRequest(Ids.newId(), q, System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.plain(PacketType.FIND_REQUEST, f.toBytes(), Destinations.BASE))
        Graph.prefs.putString("lastFind", f.requestId)
        Graph.log("FIND", "Asked the base about \"$q\"")
    }

    /* ---------------- receiving ---------------- */

    fun onChat(p: Packet, c: ChatPayload) {
        if (store.chat.value.any { it.id == p.idHex }) return
        store.chat.add(ChatItem(p.idHex, p.src, c.name, c.text, c.at, false, p.isDrill))
        Notifier.message(ctx, Notifier.ID_MSG, "${c.name}: ", c.text, "chat")
    }

    fun onAlert(p: Packet, a: AlertPayload) {
        val now = System.currentTimeMillis()
        val existing = store.alerts.value.firstOrNull { it.id == a.alertId }
        val item = AlertItem(
            a.alertId, a.kind, a.severity, a.title, a.message, a.lat, a.lon, a.radiusM, a.capacity, a.occupancy,
            a.validUntil, a.at, now, p.isDrill, false,
        )
        store.alerts.upsert({ it.id }, item)
        // Shelter occupancy updates arrive often; only new alerts make noise.
        if (existing != null && a.kind == AlertKind.SHELTER) return
        if (existing != null && existing.at == a.at) return
        val drill = if (p.isDrill) "[${L.t("drill")}] " else ""
        val critical = a.severity >= 3 || a.kind == AlertKind.EVACUATE
        Notifier.alert(ctx, a.alertId, drill + alertTitle(a.kind) + ": " + a.title, a.message, critical)
        Graph.log("ALERT", "Signed alert from base: ${a.kind} ${a.title}")
        if (critical && !p.isDrill && a.kind != AlertKind.ALL_CLEAR) {
            Graph.alarm.vibrateSos()
            Graph.alarm.startSiren(maxMinutes = 1)
        }
    }

    fun onSafe(p: Packet, s: SafePayload) {
        if (store.safe.value.any { it.id == p.idHex }) return
        store.safe.add(SafeItem(p.idHex, p.src, s.name, s.message, s.lat, s.lon, s.place, s.at, false))
    }

    fun onReport(p: Packet, r: ReportPayload) {
        if (store.reports.value.any { it.id == r.reportId }) return
        store.reports.add(ReportItem(r.reportId, p.src, r.kind, r.note, r.lat, r.lon, r.name, r.at, false))
    }

    fun onMissing(p: Packet, m: MissingPayload) {
        val item = MissingItem(m.noticeId, p.src, m.person, m.age, m.description, m.lastSeen, m.contact, m.reporter, m.lat, m.lon, m.found, m.at, p.src == Graph.prefs.now.deviceId)
        val existing = store.missing.value.firstOrNull { it.id == m.noticeId }
        if (existing != null && existing.at >= m.at) return
        store.missing.upsert({ it.id }, item)
        if (existing == null && !m.found) {
            Notifier.message(ctx, Notifier.ID_MSG + 1, L.t("missing_title"), "${m.person}${if (m.age > 0) ", ${m.age}" else ""} - ${m.lastSeen}", "missing")
        }
    }

    fun onFindResult(r: FindResult) {
        store.finds.upsert({ it.requestId }, FindAnswer(r.requestId, r.query, r.matches, r.at))
        val text = if (r.matches.isEmpty()) L.t("find_none").replace("{q}", r.query)
        else r.matches.joinToString("\n") { "${it.name}: ${it.status} ${it.place}" }
        Notifier.message(ctx, Notifier.ID_FIND, L.t("find_title").replace("{q}", r.query), text, "missing")
    }

    companion object {
        fun alertTitle(kind: String): String = when (kind) {
            AlertKind.EVACUATE -> L.t("alert_evacuate")
            AlertKind.DANGER -> L.t("alert_danger")
            AlertKind.SHELTER -> L.t("alert_shelter")
            AlertKind.MISSING -> L.t("alert_missing")
            AlertKind.ALL_CLEAR -> L.t("alert_all_clear")
            else -> L.t("alert_info")
        }
    }
}
