package com.ameer.uyirmesh.base

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AckPayload
import com.ameer.uyirmesh.core.AckStatus
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.AlertPayload
import com.ameer.uyirmesh.core.B64
import com.ameer.uyirmesh.core.BaseKeyFile
import com.ameer.uyirmesh.core.BasePrivateKeys
import com.ameer.uyirmesh.core.Credentials
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.Destinations
import com.ameer.uyirmesh.core.FindMatch
import com.ameer.uyirmesh.core.FindRequest
import com.ameer.uyirmesh.core.FindResult
import com.ameer.uyirmesh.core.ForestModel
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.HeartbeatPayload
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.SafePayload
import com.ameer.uyirmesh.core.SosCancel
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.SosUpdate
import com.ameer.uyirmesh.core.TaskPayload
import com.ameer.uyirmesh.core.TaskState
import com.ameer.uyirmesh.core.TaskStatusPayload
import com.ameer.uyirmesh.core.TeamChatPayload
import com.ameer.uyirmesh.core.Triage
import com.ameer.uyirmesh.core.TriageInput
import com.ameer.uyirmesh.core.VolunteerPayload
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.data.CaseEvent
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.data.CheckInItem
import com.ameer.uyirmesh.data.MediaRef
import com.ameer.uyirmesh.data.NodeRecord
import com.ameer.uyirmesh.data.RescuerRecord
import com.ameer.uyirmesh.data.ShelterRecord
import com.ameer.uyirmesh.data.TeamMsg
import com.ameer.uyirmesh.data.TrackPoint
import com.ameer.uyirmesh.data.VolunteerItem
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import com.ameer.uyirmesh.web.DashboardServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Hotspot(val center: LatLon, val count: Int, val radiusM: Double, val caseIds: List<String>)

/**
 * Rescue base: receives every SOS, ranks it with the on-phone Random Forest, merges duplicates,
 * finds hotspots, assigns teams with an ETA, replies to victims with signed messages, sends
 * signed alerts, runs shelters, answers "is this person safe?" searches and serves the laptop dashboard.
 */
class BaseBrain(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)
    private val store get() = Graph.store
    private val vault get() = Graph.vault

    @Volatile private var cachedKeys: BasePrivateKeys? = null
    @Volatile private var model: ForestModel? = null
    private val teamKeys = HashMap<String, ByteArray>()
    private var server: DashboardServer? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    private val _serverUrls = MutableStateFlow<List<String>>(emptyList())
    val serverUrls: StateFlow<List<String>> = _serverUrls

    fun start() {
        loadTeamKeys()
        scope.launch {
            delay(10_000)
            while (true) {
                try {
                    if (hasKeys() && Graph.prefs.now.role == Roles.BASE) periodic()
                } catch (e: Exception) {
                    Graph.note("base periodic", e)
                }
                delay(30_000)
            }
        }
    }

    /* ---------------- keys and login ---------------- */

    fun hasKeys(): Boolean = keys() != null

    fun keys(): BasePrivateKeys? {
        cachedKeys?.let { return it }
        val text = vault.readText(KEYS_FILE) ?: return null
        return try {
            BaseKeyFile.decodePrivate(text).also { cachedKeys = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Imports the base key file. The same password is then used to log in to the base screens and the web dashboard. */
    fun importKeyFile(text: String, password: CharArray) {
        val keys = BaseKeyFile.open(text, password, Graph.pinned)
        vault.writeText(KEYS_FILE, BaseKeyFile.encodePrivate(keys))
        setPassword(password)
        cachedKeys = keys
        _unlocked.value = true
        Graph.log("BASE", "Base key file imported (keys match the app: ${Graph.pinned.sigFingerprint})")
    }

    private fun setPassword(password: CharArray) {
        val salt = Crypto.random(16)
        val hash = Crypto.pbkdf2(password, salt, PW_ITERATIONS)
        vault.writeText(PW_FILE, Json.stringify(linkedMapOf("s" to B64.encode(salt), "h" to B64.encode(hash))))
    }

    fun checkPassword(password: CharArray): Boolean {
        val text = vault.readText(PW_FILE) ?: return false
        return try {
            val m = Json.parseObject(text)
            val salt = B64.decode(m.str("s"))
            val expected = B64.decode(m.str("h"))
            java.security.MessageDigest.isEqual(expected, Crypto.pbkdf2(password, salt, PW_ITERATIONS))
        } catch (e: Exception) {
            false
        }
    }

    fun login(password: CharArray): Boolean {
        val ok = checkPassword(password)
        if (ok) _unlocked.value = true
        Graph.log("BASE", if (ok) "Admin logged in" else "Wrong admin password")
        return ok
    }

    fun lock() {
        _unlocked.value = false
    }

    fun removeKeys() {
        vault.delete(KEYS_FILE)
        vault.delete(PW_FILE)
        cachedKeys = null
        _unlocked.value = false
        stopWebServer()
    }

    /* ---------------- team keys and rescuer IDs ---------------- */

    private fun loadTeamKeys() {
        val text = vault.readText(TEAM_FILE) ?: return
        try {
            val m = Json.parseObject(text)
            synchronized(teamKeys) {
                for ((k, v) in m) if (v is String) teamKeys[k] = B64.decode(v)
            }
        } catch (_: Exception) {
        }
    }

    fun teamKey(team: String, create: Boolean): ByteArray? = synchronized(teamKeys) {
        teamKeys[team]?.let { return it }
        if (!create) return null
        val k = Crypto.random(32)
        teamKeys[team] = k
        vault.writeText(TEAM_FILE, Json.stringify(teamKeys.mapValues { B64.encode(it.value) }))
        k
    }

    fun teams(): List<String> = store.rescuers.value.filter { it.expiresAt > System.currentTimeMillis() }.map { it.team }.distinct().sorted()

    /** Creates a rescuer ID and returns the PIN-protected QR text. */
    fun issueRescuer(name: String, team: String, days: Int, pin: CharArray): String {
        val keys = keys() ?: error("Base keys missing")
        val clean = Credentials.cleanTeamName(team)
        val bundle = Credentials.issue(keys, name, clean, teamKey(clean, create = true)!!, System.currentTimeMillis(), days)
        store.rescuers.upsert({ it.rescuerId }, RescuerRecord(bundle.cert.rescuerId, bundle.cert.name, bundle.cert.team, bundle.cert.issuedAt, bundle.cert.expiresAt))
        Graph.log("BASE", "Rescuer ID issued: ${bundle.cert.name} (team ${bundle.cert.team}, $days days)")
        return Credentials.toQr(bundle, pin)
    }

    /* ---------------- SOS handling ---------------- */

    private fun model(): ForestModel? {
        model?.let { return it }
        return try {
            ForestModel.fromJson(ctx.assets.open("priority_model.json").use { String(it.readBytes(), Charsets.UTF_8) }).also { model = it }
        } catch (e: Exception) {
            Graph.note("load model", e)
            null
        }
    }

    private fun score(c: CaseItem, now: Long): CaseItem {
        val m = model() ?: return c
        val waiting = if (c.status == CaseStatus.NEW || c.status == CaseStatus.UNABLE) (now - c.firstAt) / 60_000.0 else 0.0
        val input = TriageInput.from(c.payload).copy(battery = if (c.battery >= 0) c.battery else c.payload.battery)
        val r = Triage.assess(m, input, waiting, c.repeats)
        return c.copy(score = r.score, label = r.label, modelScore = r.modelScore, reasons = r.reasons, proba = r.proba.toList())
    }

    private fun pathNames(p: Packet): List<String> = p.path.map { Ids.shortHex(it) }

    @Synchronized
    fun onSos(p: Packet, s: SosPayload) {
        val now = System.currentTimeMillis()
        val existing = store.cases.value.firstOrNull { it.caseId == s.caseId }
        if (existing != null && existing.victim != p.src) return // someone reusing another phone's case id
        val hasLoc = Geo.isValid(s.lat, s.lon)
        var c = if (existing == null) {
            CaseItem(
                caseId = s.caseId, victim = p.src, payload = s, packetId = p.idHex, firstAt = now, lastAt = now, repeats = 0,
                hops = p.hops, path = pathNames(p), lat = if (hasLoc) s.lat else null, lon = if (hasLoc) s.lon else null,
                accuracy = s.accuracy, locAt = s.locTime, battery = s.battery, lowBattery = false,
                track = if (hasLoc) listOf(TrackPoint(s.lat!!, s.lon!!, s.locTime ?: now, s.battery)) else emptyList(),
                media = emptyList(), score = 0, label = "Low", modelScore = 0, reasons = emptyList(), proba = emptyList(),
                status = CaseStatus.NEW, team = "", rescuerName = "", etaMin = -1, assignedAt = 0, onWayAt = 0, rescuedAt = 0,
                cancelledAt = 0, duplicateOf = "", drill = p.isDrill,
                events = listOf(CaseEvent(now, "SOS", "SOS received via ${p.hops} hop(s), trigger ${s.trigger}")),
            )
        } else {
            existing.copy(
                payload = s, lastAt = now, repeats = existing.repeats + 1,
                lat = if (hasLoc) s.lat else existing.lat, lon = if (hasLoc) s.lon else existing.lon,
                accuracy = if (hasLoc) s.accuracy else existing.accuracy, locAt = if (hasLoc) s.locTime else existing.locAt,
                battery = if (s.battery >= 0) s.battery else existing.battery,
                status = if (existing.status == CaseStatus.CANCELLED || existing.status == CaseStatus.RESCUED) CaseStatus.NEW else existing.status,
                events = existing.events + CaseEvent(now, "SOS", "SOS sent again (${existing.repeats + 2} times)"),
            )
        }
        if (existing == null) c = c.copy(duplicateOf = findDuplicate(c, now))
        c = score(c, now)
        store.cases.upsert({ it.caseId }, c)
        replyTo(c, AckStatus.RECEIVED, "", -1, "", p.hops, pathNames(p))
        if (existing == null) {
            val drill = if (c.drill) "[${L.t("drill")}] " else ""
            Notifier.rescue(ctx, c.caseId, "$drill${L.t("new_sos")}: ${c.label} (${c.score})", summary(c), "base", task = false)
            Graph.log("BASE", "New SOS ${c.caseId.take(6)}: ${c.label} ${c.score} (${p.hops} hops)")
            if (Graph.prefs.now.autoAssign) autoAssign()
        }
    }

    private fun findDuplicate(c: CaseItem, now: Long): String {
        val lat = c.lat ?: return ""
        val lon = c.lon ?: return ""
        return store.cases.value.firstOrNull { o ->
            o.caseId != c.caseId && o.victim != c.victim && o.isOpen && o.payload.kind == c.payload.kind &&
                now - o.firstAt < 30 * 60_000 && o.lat != null && o.lon != null &&
                Geo.distanceM(lat, lon, o.lat, o.lon) < 30
        }?.let { it.duplicateOf.ifBlank { it.caseId } } ?: ""
    }

    @Synchronized
    fun onUpdate(p: Packet, u: SosUpdate) {
        val now = System.currentTimeMillis()
        val c = store.cases.value.firstOrNull { it.caseId == u.caseId && it.victim == p.src } ?: return
        val hasLoc = Geo.isValid(u.lat, u.lon)
        val moved = if (hasLoc && c.lat != null && c.lon != null) Geo.distanceM(c.lat, c.lon, u.lat!!, u.lon!!) else 0.0
        val updated = score(
            c.copy(
                lastAt = now, lat = if (hasLoc) u.lat else c.lat, lon = if (hasLoc) u.lon else c.lon,
                accuracy = if (hasLoc) u.accuracy else c.accuracy, locAt = if (hasLoc) u.locTime else c.locAt,
                battery = if (u.battery >= 0) u.battery else c.battery, lowBattery = c.lowBattery || u.lowBattery,
                track = if (hasLoc) (c.track + TrackPoint(u.lat!!, u.lon!!, u.locTime ?: now, u.battery)).takeLast(200) else c.track,
                events = if (u.lowBattery) c.events + CaseEvent(now, "BATTERY", "Victim battery low (${u.battery}%), last position sent") else c.events,
            ),
            now,
        )
        store.cases.upsert({ it.caseId }, updated)
        if (u.lowBattery) Notifier.rescue(ctx, c.caseId + "b", L.t("victim_battery_low"), summary(updated), "base", task = false)
        if ((moved > 50 || (c.lat == null && hasLoc)) && updated.team.isNotBlank() && updated.isOpen) sendTask(updated)
    }

    @Synchronized
    fun onMedia(p: Packet, m: SosMedia) {
        val c = store.cases.value.firstOrNull { it.caseId == m.caseId && it.victim == p.src } ?: return
        val ext = if (m.mime.startsWith("audio")) "amr" else "jpg"
        val name = "media_${m.caseId}_${p.idHex.take(8)}.$ext"
        if (c.media.any { it.file == name }) return
        vault.write(name, m.data)
        val now = System.currentTimeMillis()
        store.cases.upsert(
            { it.caseId },
            c.copy(
                media = c.media + MediaRef(name, m.mime, m.durationMs, now), lastAt = now,
                events = c.events + CaseEvent(now, "MEDIA", if (ext == "amr") "Voice note received (${m.durationMs / 1000}s)" else "Photo received"),
            ),
        )
        Notifier.rescue(ctx, c.caseId + "m", if (ext == "amr") L.t("voice_note_received") else L.t("photo_received"), summary(c), "base", task = false)
    }

    fun mediaBytes(file: String): ByteArray? = if (file.startsWith("media_")) vault.read(file) else null

    @Synchronized
    fun onCancel(p: Packet, x: SosCancel) {
        val c = store.cases.value.firstOrNull { it.caseId == x.caseId && it.victim == p.src } ?: return
        if (Crypto.sha256Hex(x.secret) != c.payload.cancelHash) {
            Graph.log("SECURITY", "Cancel for ${c.caseId.take(6)} rejected: wrong cancel code")
            return
        }
        val now = System.currentTimeMillis()
        val updated = c.copy(status = CaseStatus.CANCELLED, cancelledAt = now, events = c.events + CaseEvent(now, "CANCEL", "Victim cancelled the SOS"))
        store.cases.upsert({ it.caseId }, updated)
        if (c.team.isNotBlank()) sendTask(updated, cancelled = true)
        replyTo(updated, AckStatus.CANCELLED, c.team, -1, "", -1, emptyList())
        Graph.log("BASE", "SOS ${c.caseId.take(6)} cancelled by the victim")
    }

    /* ---------------- assignment ---------------- */

    private fun teamPosition(team: String): LatLon? {
        val r = store.rescuers.value.filter { it.team == team && it.lat != null && it.lon != null }.maxByOrNull { it.lastSeen }
        return if (r != null) LatLon(r.lat!!, r.lon!!) else Graph.loc.lastLatLon
    }

    private fun activeTasks(team: String) = store.cases.value.count { it.team == team && (it.status == CaseStatus.ASSIGNED || it.status == CaseStatus.ON_THE_WAY) }

    fun etaFor(team: String, c: CaseItem): Int {
        val from = teamPosition(team) ?: return -1
        val lat = c.lat ?: return -1
        val lon = c.lon ?: return -1
        return Geo.etaMinutes(Geo.distanceM(from.lat, from.lon, lat, lon), Graph.prefs.now.speedKmh.toDouble())
    }

    @Synchronized
    fun assign(caseId: String, team: String, by: String = "dispatcher") {
        val c = store.cases.value.firstOrNull { it.caseId == caseId } ?: return
        val clean = Credentials.cleanTeamName(team)
        val now = System.currentTimeMillis()
        val eta = etaFor(clean, c)
        val updated = c.copy(
            status = CaseStatus.ASSIGNED, team = clean, etaMin = eta, assignedAt = now,
            events = c.events + CaseEvent(now, "ASSIGN", "Assigned to team $clean by $by${if (eta > 0) ", ETA $eta min" else ""}"),
        )
        store.cases.upsert({ it.caseId }, updated)
        sendTask(updated)
        replyTo(updated, AckStatus.ASSIGNED, clean, eta, "", -1, emptyList())
        Graph.log("BASE", "Case ${caseId.take(6)} assigned to $clean")
    }

    @Synchronized
    fun setStatus(caseId: String, status: String) {
        val c = store.cases.value.firstOrNull { it.caseId == caseId } ?: return
        val now = System.currentTimeMillis()
        val updated = c.copy(
            status = status,
            rescuedAt = if (status == CaseStatus.RESCUED) now else c.rescuedAt,
            onWayAt = if (status == CaseStatus.ON_THE_WAY) now else c.onWayAt,
            events = c.events + CaseEvent(now, "STATUS", "Marked ${status.lowercase()} at the base"),
        )
        store.cases.upsert({ it.caseId }, updated)
        when (status) {
            CaseStatus.RESCUED -> replyTo(updated, AckStatus.RESCUED, c.team, -1, "", -1, emptyList())
            CaseStatus.ON_THE_WAY -> replyTo(updated, AckStatus.ON_THE_WAY, c.team, c.etaMin, "", -1, emptyList())
        }
    }

    /** Picks a team for every waiting case, most urgent first: nearest team with the fewest open tasks. */
    @Synchronized
    fun autoAssign() {
        val teams = teams()
        if (teams.isEmpty()) return
        val waiting = store.cases.value.filter { it.status == CaseStatus.NEW }.sortedByDescending { it.score }
        for (c in waiting) {
            val dupTeam = store.cases.value.firstOrNull { it.caseId == c.duplicateOf && it.team.isNotBlank() }?.team
            val choice = dupTeam ?: teams.filter { activeTasks(it) < MAX_ACTIVE_PER_TEAM }.minByOrNull { t ->
                val pos = teamPosition(t)
                val km = if (pos != null && c.lat != null && c.lon != null) Geo.distanceM(pos.lat, pos.lon, c.lat, c.lon) / 1000 else 5.0
                km + 2.0 * activeTasks(t)
            } ?: continue
            assign(c.caseId, choice, by = "auto-assign")
        }
    }

    private fun sendTask(c: CaseItem, cancelled: Boolean = false) {
        val keys = keys() ?: return
        val key = teamKey(c.team, create = false) ?: return
        val t = TaskPayload(
            c.caseId, c.victim, c.lat, c.lon, if (cancelled) "CANCELLED" else c.label, if (cancelled) -1 else c.score,
            summary(c), c.payload.medical, c.payload.people, c.team, System.currentTimeMillis(),
        )
        val ttl = maxOf(Graph.mesh.outbox.ttl, 8)
        Graph.mesh.send(Graph.mesh.outbox.teamSignedByBase(keys, PacketType.TASK, t.toBytes(), c.team, key, ttl))
    }

    private fun replyTo(c: CaseItem, status: String, team: String, eta: Int, message: String, hops: Int, path: List<String>) {
        val keys = keys() ?: return
        val ack = AckPayload(c.caseId, status, team, eta, message, System.currentTimeMillis(), Graph.prefs.now.baseName, "", hops, path)
        val ttl = maxOf(Graph.mesh.outbox.ttl, c.hops + 2, 6).coerceAtMost(16)
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.ACK, ack.toBytes(), c.victim, ttl))
    }

    /** Sends a free-text message to a victim (signed by the base). */
    fun messageVictim(caseId: String, text: String) {
        val c = store.cases.value.firstOrNull { it.caseId == caseId } ?: return
        val status = when (c.status) {
            CaseStatus.ON_THE_WAY -> AckStatus.ON_THE_WAY
            CaseStatus.ASSIGNED -> AckStatus.ASSIGNED
            CaseStatus.RESCUED -> AckStatus.RESCUED
            else -> AckStatus.RECEIVED
        }
        replyTo(c, status, c.team, c.etaMin, text.take(300), -1, emptyList())
        val now = System.currentTimeMillis()
        store.cases.upsert({ it.caseId }, c.copy(events = c.events + CaseEvent(now, "MESSAGE", "Message to victim: ${text.take(80)}")))
    }

    @Synchronized
    fun onTaskStatus(p: Packet, st: TaskStatusPayload) {
        val c = store.cases.value.firstOrNull { it.caseId == st.caseId } ?: return
        val now = System.currentTimeMillis()
        if (Geo.isValid(st.lat, st.lon)) {
            store.rescuers.modify({ it.rescuerId }, st.rescuerId) { it.copy(lat = st.lat, lon = st.lon, lastSeen = now, deviceId = p.src) }
        }
        val eta = if (Geo.isValid(st.lat, st.lon) && c.lat != null && c.lon != null) {
            Geo.etaMinutes(Geo.distanceM(st.lat!!, st.lon!!, c.lat, c.lon), Graph.prefs.now.speedKmh.toDouble())
        } else c.etaMin
        val (status, ack) = when (st.status) {
            TaskState.ACCEPTED -> CaseStatus.ASSIGNED to AckStatus.ASSIGNED
            TaskState.ON_THE_WAY -> CaseStatus.ON_THE_WAY to AckStatus.ON_THE_WAY
            TaskState.RESCUED -> CaseStatus.RESCUED to AckStatus.RESCUED
            TaskState.UNABLE -> CaseStatus.UNABLE to ""
            else -> return
        }
        val updated = c.copy(
            status = status, team = st.team.ifBlank { c.team }, rescuerName = st.rescuerName, etaMin = eta,
            assignedAt = if (c.assignedAt == 0L) now else c.assignedAt,
            onWayAt = if (status == CaseStatus.ON_THE_WAY && c.onWayAt == 0L) now else c.onWayAt,
            rescuedAt = if (status == CaseStatus.RESCUED) now else c.rescuedAt,
            events = c.events + CaseEvent(now, "TEAM", "${st.rescuerName} (${st.team}): ${st.status.lowercase().replace('_', ' ')}"),
        )
        store.cases.upsert({ it.caseId }, updated)
        if (ack.isNotEmpty()) replyTo(updated, ack, updated.team, eta, if (st.rescuerName.isNotBlank()) "Rescuer: ${st.rescuerName}" else "", -1, emptyList())
        if (status == CaseStatus.UNABLE) {
            Notifier.rescue(ctx, c.caseId + "u", L.t("team_unable"), summary(updated), "base", task = false)
            if (Graph.prefs.now.autoAssign) {
                store.cases.upsert({ it.caseId }, updated.copy(status = CaseStatus.NEW, team = ""))
                autoAssign()
            }
        }
    }

    fun onTeamChat(p: Packet, team: String, m: TeamChatPayload) {
        if (store.teamChat.value.any { it.id == p.idHex }) return
        store.teamChat.add(TeamMsg(p.idHex, p.src, "${m.name} ($team)", m.text, m.at, false))
    }

    fun sendTeamChat(team: String, text: String) {
        val key = teamKey(team, create = false) ?: return
        val now = System.currentTimeMillis()
        val p = Graph.mesh.outbox.team(PacketType.TEAM_CHAT, TeamChatPayload(L.t("rescue_base"), text.take(500), now).toBytes(), team, key)
        Graph.mesh.send(p)
        store.teamChat.add(TeamMsg(p.idHex, Graph.prefs.now.deviceId, "${L.t("rescue_base")} → $team", text, now, true))
    }

    /* ---------------- other reports ---------------- */

    fun onVolunteer(p: Packet, v: VolunteerPayload) {
        store.volunteers.upsert({ it.src }, VolunteerItem(p.src, v.name, v.phone, v.skills, v.note, v.lat, v.lon, v.at))
        Graph.log("BASE", "Volunteer: ${v.name} (${v.skills.joinToString()})")
    }

    fun onHeartbeat(src: String, hb: HeartbeatPayload, hops: Int) {
        val now = System.currentTimeMillis()
        store.nodes.upsert(
            { it.deviceId },
            NodeRecord(src, hb.role, hb.battery, hb.neighbours, now, hb.name, hb.team, hb.lat, hb.lon, hb.version),
        )
        if (hb.role == Roles.RESCUER && hb.rescuerId.isNotBlank()) {
            store.rescuers.modify({ it.rescuerId }, hb.rescuerId) {
                it.copy(deviceId = src, lastSeen = now, lat = hb.lat ?: it.lat, lon = hb.lon ?: it.lon, battery = hb.battery)
            }
        }
    }

    fun onSafe(p: Packet, s: SafePayload) {
        if (!s.place.startsWith("SHELTER:")) return
        val parts = s.place.split(':')
        val shelterId = parts.getOrNull(1) ?: return
        if (store.shelters.value.none { it.id == shelterId }) return
        if (store.checkins.value.any { it.shelterId == shelterId && it.deviceId == p.src }) return
        store.checkins.add(CheckInItem(shelterId, s.name, p.src, s.at))
        recountShelter(shelterId)
        Graph.log("BASE", "${s.name} checked in at shelter ${parts.getOrNull(2) ?: shelterId}")
    }

    fun onFindRequest(p: Packet, f: FindRequest) {
        val keys = keys() ?: return
        val q = f.query.trim().lowercase()
        if (q.length < 2) return
        val matches = ArrayList<FindMatch>()
        for (s in store.safe.value) if (s.name.lowercase().contains(q)) matches.add(FindMatch(s.name, "SAFE", s.place.substringAfterLast(':'), s.at))
        for (ci in store.checkins.value) if (ci.name.lowercase().contains(q)) {
            val shelter = store.shelters.value.firstOrNull { it.id == ci.shelterId }?.name ?: ""
            matches.add(FindMatch(ci.name, "IN SHELTER", shelter, ci.at))
        }
        for (c in store.cases.value) if (c.payload.name.isNotBlank() && c.payload.name.lowercase().contains(q)) {
            val st = when (c.status) {
                CaseStatus.RESCUED -> "RESCUED"
                CaseStatus.CANCELLED -> "SOS CANCELLED"
                else -> "SOS - HELP ON THE WAY"
            }
            matches.add(FindMatch(c.payload.name, st, "", c.lastAt))
        }
        val result = FindResult(f.requestId, f.query, matches.sortedByDescending { it.at }.distinctBy { it.name + it.status }.take(10), System.currentTimeMillis())
        Graph.mesh.send(Graph.mesh.outbox.signedByBase(keys, PacketType.FIND_RESULT, result.toBytes(), p.src, maxOf(Graph.mesh.outbox.ttl, p.hops + 2)))
        Graph.log("BASE", "Answered search \"${f.query}\": ${result.matches.size} match(es)")
    }

    /* ---------------- alerts and shelters ---------------- */

    fun sendAlert(kind: String, severity: Int, title: String, message: String, lat: Double?, lon: Double?, radiusM: Int, validMinutes: Int, capacity: Int = 0, occupancy: Int = 0, alertId: String = Ids.newId()): Boolean {
        val keys = keys() ?: return false
        val now = System.currentTimeMillis()
        val a = AlertPayload(alertId, kind, severity.coerceIn(1, 3), title.take(80), message.take(500), lat, lon, radiusM, capacity, occupancy, if (validMinutes > 0) now + validMinutes * 60_000L else 0, now)
        val p = Graph.mesh.outbox.signedByBase(keys, PacketType.ALERT, a.toBytes(), Destinations.ALL, maxOf(Graph.mesh.outbox.ttl, 8))
        Graph.mesh.send(p)
        store.alerts.upsert({ it.id }, com.ameer.uyirmesh.data.AlertItem(a.alertId, kind, a.severity, a.title, a.message, lat, lon, radiusM, capacity, occupancy, a.validUntil, now, now, p.isDrill, true))
        Graph.log("BASE", "Signed alert sent: $kind $title")
        return true
    }

    fun addShelter(name: String, lat: Double?, lon: Double?, capacity: Int): ShelterRecord {
        val now = System.currentTimeMillis()
        val s = ShelterRecord(Ids.newId(), name.take(40), lat, lon, capacity, 0, 0, 0, 0, now)
        store.shelters.add(s)
        announceShelter(s.id)
        return s
    }

    fun updateSupplies(id: String, water: Int, food: Int, medicine: Int, capacity: Int) {
        store.shelters.modify({ it.id }, id) { it.copy(water = water, food = food, medicine = medicine, capacity = capacity, updatedAt = System.currentTimeMillis()) }
        announceShelter(id)
    }

    fun manualCheckIn(shelterId: String, name: String, deviceId: String) {
        if (store.checkins.value.any { it.shelterId == shelterId && it.deviceId == deviceId && deviceId.isNotBlank() }) return
        store.checkins.add(CheckInItem(shelterId, name, deviceId.ifBlank { Ids.newId() }, System.currentTimeMillis()))
        recountShelter(shelterId)
    }

    private fun recountShelter(id: String) {
        val n = store.checkins.value.filter { it.shelterId == id }.map { it.deviceId }.distinct().size
        val s = store.shelters.modify({ it.id }, id) { it.copy(occupancy = n, updatedAt = System.currentTimeMillis()) } ?: return
        if (System.currentTimeMillis() - s.announcedAt > 2 * 60_000) announceShelter(id)
    }

    fun announceShelter(id: String) {
        val s = store.shelters.value.firstOrNull { it.id == id } ?: return
        val msg = "${s.occupancy}/${s.capacity} people · water ${s.water} · food ${s.food} · medicine ${s.medicine}"
        if (sendAlert(AlertKind.SHELTER, 1, s.name, msg, s.lat, s.lon, 0, 0, s.capacity, s.occupancy, alertId = "shelter-${s.id}")) {
            store.shelters.modify({ it.id }, id) { it.copy(announcedAt = System.currentTimeMillis()) }
        }
    }

    /** QR code that people scan at the shelter entrance to check in. */
    fun shelterQr(s: ShelterRecord): String = "UMS1:${s.id}:${s.name}"

    /* ---------------- analysis ---------------- */

    fun hotspots(): List<Hotspot> {
        val open = store.cases.value.filter { it.isOpen && it.lat != null && it.lon != null }
        val pts = open.map { LatLon(it.lat!!, it.lon!!) }
        return Geo.dbscan(pts, epsM = 250.0, minPts = 3).map { idx ->
            val lat = idx.map { pts[it].lat }.average()
            val lon = idx.map { pts[it].lon }.average()
            val center = LatLon(lat, lon)
            Hotspot(center, idx.size, idx.maxOf { Geo.distanceM(center, pts[it]) }.coerceAtLeast(60.0), idx.map { open[it].caseId })
        }
    }

    private fun periodic() {
        val now = System.currentTimeMillis()
        // Re-rank open cases (waiting time raises urgency).
        store.cases.edit { list -> list.map { if (it.isOpen) score(it, now) else it } }
        if (Graph.prefs.now.autoAssign) autoAssign()
        // Keep the base's own GPS position fresh for ETAs.
        if (Graph.loc.ageMs() > 5 * 60_000) Graph.loc.requestOnce(30_000) { }
    }

    fun summary(c: CaseItem): String {
        val p = c.payload
        val parts = ArrayList<String>()
        parts.add(Triage.kindLabel(p.kind))
        val people = buildList {
            if (p.children > 0) add("${p.children} child")
            if (p.elderly > 0) add("${p.elderly} elderly")
            if (p.disabled > 0) add("${p.disabled} disabled")
            if (p.pregnant > 0) add("${p.pregnant} pregnant")
        }
        parts.add("${p.people} people" + if (people.isNotEmpty()) " (${people.joinToString()})" else "")
        if (p.injured) parts.add("injured")
        if (p.needs.isNotEmpty()) parts.add("needs " + p.needs.joinToString { it.lowercase().replace('_', ' ') })
        if (c.battery in 0..100) parts.add("battery ${c.battery}%")
        if (p.note.isNotBlank()) parts.add("\"${p.note.take(80)}\"")
        return parts.joinToString(" · ")
    }

    /* ---------------- web dashboard ---------------- */

    fun webServerCheck() {
        val s = Graph.prefs.now
        val want = s.webServer && s.role == Roles.BASE && hasKeys() && com.ameer.uyirmesh.mesh.MeshService.running
        if (want && server == null) startWebServer() else if (!want && server != null) stopWebServer()
        if (server != null) _serverUrls.value = DashboardServer.localUrls(DashboardServer.PORT)
    }

    @Synchronized
    private fun startWebServer() {
        if (server != null) return
        try {
            server = DashboardServer(ctx).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            _serverUrls.value = DashboardServer.localUrls(DashboardServer.PORT)
            Graph.log("BASE", "Web dashboard on ${_serverUrls.value.joinToString()}")
        } catch (e: Exception) {
            server = null
            Graph.note("web server", e)
        }
    }

    @Synchronized
    fun stopWebServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        if (server != null) Graph.log("BASE", "Web dashboard stopped")
        server = null
        _serverUrls.value = emptyList()
    }

    companion object {
        private const val KEYS_FILE = "base_keys.json"
        private const val PW_FILE = "base_pw.json"
        private const val TEAM_FILE = "team_keys.json"
        private const val PW_ITERATIONS = 60_000
        private const val MAX_ACTIVE_PER_TEAM = 3
    }
}
