package com.ameer.uyirmesh.rescuer

import com.ameer.uyirmesh.core.HazardPayload
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Destinations

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AckPayload
import com.ameer.uyirmesh.core.AckStatus
import com.ameer.uyirmesh.core.B64
import com.ameer.uyirmesh.core.Credentials
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.RescuerBundle
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.SosCancel
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.SosUpdate
import com.ameer.uyirmesh.core.TaskPayload
import com.ameer.uyirmesh.core.TaskState
import com.ameer.uyirmesh.core.TaskStatusPayload
import com.ameer.uyirmesh.core.TeamChatPayload
import com.ameer.uyirmesh.core.Triage
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.data.Crumb
import com.ameer.uyirmesh.data.SeenSos
import com.ameer.uyirmesh.data.TaskItem
import com.ameer.uyirmesh.data.TaskSource
import com.ameer.uyirmesh.data.TeamMsg
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Rescuer side: log in with the base-signed QR ID and PIN, receive tasks from the base, see every SOS in
 * radio range directly (rescuer group key), update task status (also tells the victim directly with a
 * signed reply), best visit order, breadcrumb trail, encrypted team chat, auto-lock after 5 minutes.
 */
class Rescuer(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)
    private val store get() = Graph.store
    private val vault get() = Graph.vault

    @Volatile private var cached: RescuerBundle? = null
    @Volatile private var loaded = false

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    @Volatile private var lastActive = 0L
    private val progressSent = HashMap<String, Pair<Long, Int>>()

    fun start() {
        scope.launch {
            delay(5_000)
            if (bundle() != null && Graph.prefs.now.role == Roles.RESCUER) Graph.loc.startTracking("rescuer", 30_000)
            while (true) {
                try {
                    tick()
                } catch (e: Exception) {
                    Graph.note("rescuer tick", e)
                }
                delay(30_000)
            }
        }
    }

    /** The rescuer's credentials, or null when this phone is not a logged-in rescuer. */
    fun bundle(): RescuerBundle? {
        if (loaded) return cached
        synchronized(this) {
            if (!loaded) {
                cached = try {
                    vault.readText(BUNDLE_FILE)?.let { RescuerBundle.decode(it) }
                } catch (e: Exception) {
                    null
                }
                loaded = true
            }
        }
        return cached
    }

    val isExpired: Boolean get() = bundle()?.cert?.isExpired(System.currentTimeMillis()) == true

    /** Checks the QR and PIN, then stores the ID encrypted. Throws with a readable message on failure. */
    fun login(qr: String, pin: CharArray) {
        val b = Credentials.fromQr(qr, pin)
        if (!Credentials.verify(Graph.pinned, b, System.currentTimeMillis())) {
            throw IllegalStateException(L.t("rescuer_id_invalid"))
        }
        vault.writeText(BUNDLE_FILE, b.encode())
        val salt = Crypto.random(16)
        vault.writeText(PIN_FILE, Json.stringify(linkedMapOf("s" to B64.encode(salt), "h" to B64.encode(Crypto.pbkdf2(pin, salt, PIN_ITERATIONS)))))
        cached = b
        loaded = true
        _locked.value = false
        lastActive = System.currentTimeMillis()
        Graph.prefs.update { it.copy(role = Roles.RESCUER, name = it.name.ifBlank { b.cert.name }) }
        Graph.mesh.onRoleChanged()
        Graph.loc.startTracking("rescuer", 30_000)
        Graph.log("RESCUER", "Logged in as ${b.cert.name}, team ${b.cert.team} (ID signed by base ${Graph.pinned.sigFingerprint})")
    }

    fun unlock(pin: CharArray): Boolean {
        val text = vault.readText(PIN_FILE) ?: return false
        val ok = try {
            val m = Json.parseObject(text)
            java.security.MessageDigest.isEqual(B64.decode(m.str("h")), Crypto.pbkdf2(pin, B64.decode(m.str("s")), PIN_ITERATIONS))
        } catch (e: Exception) {
            false
        }
        if (ok) {
            _locked.value = false
            lastActive = System.currentTimeMillis()
        }
        return ok
    }

    fun touch() {
        lastActive = System.currentTimeMillis()
    }

    fun lock() {
        _locked.value = true
    }

    fun checkAutoLock() {
        if (!_locked.value && System.currentTimeMillis() - lastActive > AUTO_LOCK_MS) {
            _locked.value = true
            Graph.log("RESCUER", "Rescuer screens locked after 5 minutes")
        }
    }

    fun logout() {
        vault.delete(BUNDLE_FILE)
        vault.delete(PIN_FILE)
        cached = null
        loaded = true
        _locked.value = true
        store.clearRole(Roles.RESCUER)
        Graph.prefs.update { it.copy(role = Roles.PUBLIC) }
        Graph.mesh.onRoleChanged()
        Graph.loc.stopTracking("rescuer")
        Graph.log("RESCUER", "Logged out")
    }

    private fun tick() {
        val b = bundle()
        if (b == null || Graph.prefs.now.role != Roles.RESCUER) {
            // Not on duty (logged out or switched to public mode): no breadcrumb GPS.
            Graph.loc.stopTracking("rescuer")
            return
        }
        Graph.loc.startTracking("rescuer", 30_000)
        Graph.loc.ensureGps()
        checkAutoLock()
        // Only precise positions (GPS or good Wi-Fi) are used: a rough network one can be kilometres off.
        val l = Graph.loc.usable() ?: return
        val last = store.crumbs.value.lastOrNull()
        val now = System.currentTimeMillis()
        if (last == null || Geo.distanceM(last.lat, last.lon, l.latitude, l.longitude) > 15 || now - last.at > 5 * 60_000) {
            if (Graph.loc.ageMs() < 2 * 60_000) store.crumbs.add(Crumb(l.latitude, l.longitude, now))
        }
        sendProgress(now)
        if (b.cert.isExpired(now)) Graph.log("RESCUER", "Rescuer ID expired; ask the base for a new one")
    }

    /**
     * While on the way, tells each victim how far away this rescuer is, signed with the rescuer ID so it cannot
     * be faked: every 2 minutes (5 in battery saver), and at once when the rescuer comes within [NEAR_M].
     */
    @Synchronized
    fun sendProgress(now: Long = System.currentTimeMillis()) {
        val b = bundle() ?: return
        if (Graph.prefs.now.role != Roles.RESCUER) return
        val l = Graph.loc.usable() ?: return
        if (Graph.loc.ageMs() > 2 * 60_000) return
        val interval = if (Graph.prefs.now.batterySaver) 5 * 60_000L else 2 * 60_000L
        val myId = Graph.mesh.engine.myId
        for (t in store.tasks.value) {
            if (t.state != TaskState.ON_THE_WAY || t.victim.isBlank() || !Geo.isValid(t.lat, t.lon)) continue
            val d = Geo.distanceM(l.latitude, l.longitude, t.lat!!, t.lon!!).roundToInt()
            val last = progressSent[t.caseId]
            val justCameNear = d <= NEAR_M && (last == null || last.second > NEAR_M)
            if (last != null && now - last.first < interval && !justCameNear) continue
            val eta = Geo.etaMinutes(d.toDouble(), Graph.prefs.now.speedKmh.toDouble())
            val update = AckPayload(t.caseId, AckStatus.ON_THE_WAY, b.cert.team, eta, "", now, b.cert.name, b.cert.toBase64(), distanceM = d)
            // Older distance updates for this victim are no longer worth spreading.
            Graph.mesh.engine.forgetWhere { p -> p.type == PacketType.PROGRESS && p.src == myId && p.dst == t.victim }
            Graph.mesh.send(Graph.mesh.outbox.signedByRescuer(b, PacketType.PROGRESS, update.toBytes(), t.victim, maxOf(Graph.mesh.outbox.ttl, 6)))
            progressSent[t.caseId] = now to d
        }
    }

    /* ---------------- incoming ---------------- */

    fun onSos(p: Packet, s: SosPayload) {
        val now = System.currentTimeMillis()
        val existing = store.seen.value.firstOrNull { it.caseId == s.caseId }
        if (existing != null && existing.victim != p.src) return
        val hasLoc = Geo.isValid(s.lat, s.lon)
        val lat = if (hasLoc) s.lat else existing?.lat
        val lon = if (hasLoc) s.lon else existing?.lon
        val accuracy = if (hasLoc) s.accuracy else existing?.accuracy
        store.seen.upsert(
            { it.caseId },
            SeenSos(s.caseId, p.src, s, lat, lon, s.battery, existing?.firstAt ?: now, now, p.hops, false, accuracy),
        )
        if (existing == null) {
            val dist = distanceText(lat, lon)
            Notifier.rescue(ctx, s.caseId, "${L.t("sos_nearby")}: ${Triage.kindLabel(s.kind)}", "${s.people} ${L.t("people")}${if (s.injured) " · " + L.t("injured") else ""}$dist", "rescuer", task = true)
            Graph.log("RESCUER", "SOS ${s.caseId.take(6)} opened directly (${p.hops} hops)")
        }
    }

    fun onUpdate(p: Packet, u: SosUpdate) {
        val hasLoc = Geo.isValid(u.lat, u.lon)
        store.seen.modify({ it.caseId }, u.caseId) {
            if (it.victim != p.src) it
            else it.copy(
                lat = if (hasLoc) u.lat else it.lat, lon = if (hasLoc) u.lon else it.lon,
                accuracy = if (hasLoc) u.accuracy else it.accuracy,
                battery = if (u.battery >= 0) u.battery else it.battery, lastAt = System.currentTimeMillis(),
            )
        }
        store.tasks.modify({ it.caseId }, u.caseId) {
            if (it.victim != p.src || !hasLoc) it
            else it.copy(lat = u.lat, lon = u.lon, accuracy = u.accuracy, updatedAt = System.currentTimeMillis())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onMedia(p: Packet, m: SosMedia) {
        // Media is reviewed at the base; the rescuer only needs the location and the summary.
    }

    fun onCancel(p: Packet, c: SosCancel) {
        val seen = store.seen.value.firstOrNull { it.caseId == c.caseId && it.victim == p.src } ?: return
        if (Crypto.sha256Hex(c.secret) != seen.payload.cancelHash) return
        store.seen.modify({ it.caseId }, c.caseId) { it.copy(cancelled = true) }
        store.tasks.modify({ it.caseId }, c.caseId) { it.copy(state = TaskState.UNABLE, summary = "CANCELLED by the victim · " + it.summary, updatedAt = System.currentTimeMillis()) }
    }

    fun onTask(p: Packet, t: TaskPayload) {
        val now = System.currentTimeMillis()
        val existing = store.tasks.value.firstOrNull { it.caseId == t.caseId }
        if (t.label == "CANCELLED") {
            if (existing != null) store.tasks.upsert({ it.caseId }, existing.copy(state = TaskState.UNABLE, summary = "CANCELLED · " + existing.summary, updatedAt = now))
            return
        }
        val item = TaskItem(
            t.caseId, t.victimDevice, t.lat, t.lon, t.label, t.score, t.summary, t.medical, t.people, t.team,
            existing?.state ?: TaskItem.NEW, TaskSource.BASE, existing?.at ?: now, now, t.accuracy,
        )
        store.tasks.upsert({ it.caseId }, item)
        if (existing == null) {
            Notifier.rescue(ctx, t.caseId, "${L.t("new_task")}: ${t.label} (${t.score})", t.summary + distanceText(t.lat, t.lon), "rescuer", task = true)
            Graph.alarm.vibrateReply()
            Graph.log("RESCUER", "Task ${t.caseId.take(6)} from base: ${t.label}")
        }
    }

    fun onTeammateStatus(p: Packet, st: TaskStatusPayload) {
        val b = bundle() ?: return
        if (st.rescuerId == b.cert.rescuerId) return
        store.tasks.modify({ it.caseId }, st.caseId) {
            it.copy(summary = it.summary.substringBefore(" ⟶ ") + " ⟶ ${st.rescuerName}: ${st.status.lowercase().replace('_', ' ')}", updatedAt = System.currentTimeMillis())
        }
    }

    fun onTeamChat(p: Packet, m: TeamChatPayload) {
        if (store.teamChat.value.any { it.id == p.idHex }) return
        store.teamChat.add(TeamMsg(p.idHex, p.src, m.name, m.text, m.at, false))
        Notifier.message(ctx, Notifier.ID_TEAM, "${L.t("team_chat")}: ${m.name}", m.text, "rescuer")
    }

    /* ---------------- actions ---------------- */

    /** Takes an SOS seen directly as a task (when the base is out of range). */
    fun acceptSeen(s: SeenSos) {
        val b = bundle() ?: return
        val now = System.currentTimeMillis()
        val summary = "${Triage.kindLabel(s.payload.kind)} · ${s.payload.people} people" +
            (if (s.payload.injured) " · injured" else "") + (if (s.payload.note.isNotBlank()) " · \"${s.payload.note.take(60)}\"" else "")
        val existing = store.tasks.value.firstOrNull { it.caseId == s.caseId }
        if (existing == null) {
            store.tasks.add(
                TaskItem(s.caseId, s.victim, s.lat, s.lon, "SOS", 0, summary, s.payload.medical, s.payload.people, b.cert.team, TaskItem.NEW, TaskSource.DIRECT, now, now, s.accuracy),
            )
        }
        setTaskState(s.caseId, TaskState.ACCEPTED)
    }

    fun setTaskState(caseId: String, state: String, outcome: String = "") {
        val b = bundle() ?: return
        val now = System.currentTimeMillis()
        val t = store.tasks.modify({ it.caseId }, caseId) { it.copy(state = state, updatedAt = now) } ?: return
        val loc = Graph.loc.usable()
        val st = TaskStatusPayload(caseId, b.cert.rescuerId, b.cert.name, b.cert.team, state, loc?.latitude, loc?.longitude, now, loc?.time ?: 0L, Graph.mesh.batteryPercent(), outcome.take(200))
        Graph.mesh.send(Graph.mesh.outbox.team(PacketType.TASK_STATUS, st.toBytes(), b.cert.team, b.teamKey))
        // Tell the victim directly too (works even when the base is out of range).
        val ackStatus = when (state) {
            TaskState.ACCEPTED -> AckStatus.ASSIGNED
            TaskState.ON_THE_WAY -> AckStatus.ON_THE_WAY
            TaskState.RESCUED -> AckStatus.RESCUED
            else -> null
        }
        if (ackStatus != null && t.victim.isNotBlank()) {
            val fresh = loc != null && Graph.loc.ageMs() < 5 * 60_000
            val d = if (fresh && t.lat != null && t.lon != null) Geo.distanceM(loc!!.latitude, loc.longitude, t.lat, t.lon) else -1.0
            val eta = if (d >= 0) Geo.etaMinutes(d, Graph.prefs.now.speedKmh.toDouble()) else -1
            val dist = if (d >= 0 && state == TaskState.ON_THE_WAY) d.roundToInt() else -1
            val ack = AckPayload(caseId, ackStatus, b.cert.team, eta, "", now, b.cert.name, b.cert.toBase64(), distanceM = dist)
            Graph.mesh.send(Graph.mesh.outbox.signedByRescuer(b, PacketType.ACK, ack.toBytes(), t.victim, maxOf(Graph.mesh.outbox.ttl, 6)))
            if (dist >= 0) synchronized(this) { progressSent[caseId] = now to dist }
        }
        Graph.log("RESCUER", "Task ${caseId.take(6)}: ${state.lowercase().replace('_', ' ')}")
    }

    /**
     * Marks a danger here ("bridge broken", "live wire in water") for everyone: signed with this rescuer's key, so
     * every phone can check it really comes from a rescuer issued by the base.
     */
    fun sendHazard(kind: String, note: String, radiusM: Int, cleared: Boolean = false, hazardId: String = Ids.newId()): Boolean {
        val b = bundle() ?: return false
        val loc = Graph.loc.usable() ?: Graph.loc.last ?: return false
        val now = System.currentTimeMillis()
        val h = HazardPayload(hazardId, kind, note.take(200), loc.latitude, loc.longitude, radiusM.coerceIn(10, 5_000), b.cert.name, b.cert.toBase64(), cleared, now)
        val p = Graph.mesh.outbox.signedByRescuer(b, PacketType.HAZARD, h.toBytes(), Destinations.ALL, maxOf(Graph.mesh.outbox.ttl, 8))
        Graph.mesh.send(p)
        Graph.safety.onHazard(p, h, b.cert.name)
        Graph.log("RESCUER", "Hazard marked: $kind")
        return true
    }

    fun sendTeamChat(text: String) {
        val b = bundle() ?: return
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        val p = Graph.mesh.outbox.team(PacketType.TEAM_CHAT, TeamChatPayload(b.cert.name, t, now).toBytes(), b.cert.team, b.teamKey)
        Graph.mesh.send(p)
        store.teamChat.add(TeamMsg(p.idHex, Graph.prefs.now.deviceId, b.cert.name, t, now, true))
    }

    /** Best order to visit open tasks from the current position (nearest neighbour + 2-opt). */
    fun routeOrder(): List<TaskItem> {
        val open = store.tasks.value.filter { (it.state == TaskItem.NEW || it.state == TaskState.ACCEPTED || it.state == TaskState.ON_THE_WAY) && it.lat != null && it.lon != null }
        val me = Graph.loc.lastLatLon ?: return open
        val order = Geo.routeOrder(me, open.map { LatLon(it.lat!!, it.lon!!) })
        return order.map { open[it] }
    }

    private fun distanceText(lat: Double?, lon: Double?): String {
        val me = Graph.loc.usable() ?: return ""
        if (!Geo.isValid(lat, lon)) return ""
        val d = Geo.distanceM(me.latitude, me.longitude, lat!!, lon!!)
        val b = Geo.compassName(Geo.bearingDeg(LatLon(me.latitude, me.longitude), LatLon(lat, lon)))
        return " · ${Geo.formatDistance(d)} $b"
    }

    companion object {
        private const val BUNDLE_FILE = "rescuer_bundle.json"
        private const val PIN_FILE = "rescuer_pin.json"
        private const val PIN_ITERATIONS = 30_000
        private const val AUTO_LOCK_MS = 5 * 60_000L

        /** Within this distance the victim is told at once (close enough to shout, or to hear a siren). */
        const val NEAR_M = 150
    }
}
