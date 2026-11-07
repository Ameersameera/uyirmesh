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
import com.ameer.uyirmesh.core.CaseFacts
import com.ameer.uyirmesh.core.Credentials
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.Duplicates
import com.ameer.uyirmesh.core.EtaModel
import com.ameer.uyirmesh.core.EtaSample
import com.ameer.uyirmesh.core.Planner
import com.ameer.uyirmesh.core.Revocations
import com.ameer.uyirmesh.core.SosChecks
import com.ameer.uyirmesh.core.TeamSlot
import com.ameer.uyirmesh.core.WaitingCase
import com.ameer.uyirmesh.core.Destinations
import com.ameer.uyirmesh.core.FindMatch
import com.ameer.uyirmesh.core.FindRequest
import com.ameer.uyirmesh.core.FindResult
import com.ameer.uyirmesh.core.Fix
import com.ameer.uyirmesh.core.ForestModel
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.HeartbeatPayload
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.PositionAuth
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
import com.ameer.uyirmesh.core.Tracking
import com.ameer.uyirmesh.core.Triage
import com.ameer.uyirmesh.core.TriageInput
import com.ameer.uyirmesh.core.VolunteerPayload
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.data.CaseEvent
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.data.CheckInItem
import com.ameer.uyirmesh.data.Crumb
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

/** A rescuer of the case's team, their recent positions (oldest first) and how they are doing on the way to the victim. */
data class TeamApproach(val rescuer: RescuerRecord, val approach: Tracking.Approach, val fixes: List<Fix>)

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
    private val unverifiedWarned = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    fun teams(): List<String> = store.rescuers.value
        .filter { it.expiresAt > System.currentTimeMillis() && !Revocations.isRevoked(it.rescuerId) }
        .map { it.team }.distinct().sorted()

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
        if (existing == null) {
            findDuplicate(c)?.let { (of, why) ->
                c = c.copy(duplicateOf = of, events = c.events + CaseEvent(now, "DUPLICATE", "Probably the same people as case ${of.take(6)} ($why)"))
            }
        } else if (hasLoc && existing.lat != null && existing.lon != null) {
            SosChecks.jump(existing.lat, existing.lon, existing.locAt ?: existing.lastAt, s.lat!!, s.lon!!, s.locTime ?: now)?.let { j -> c = withFlags(c, c.flags + j, now) }
        }
        c = withFlags(c, SosChecks.merge(c.flags, sosFlags(c, p.createdAt, now)), now)
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

    private fun facts(c: CaseItem): CaseFacts {
        val p = c.payload
        return CaseFacts(
            c.caseId, c.victim, p.kind, c.firstAt, c.lat, c.lon,
            if (p.forOther) p.otherName else p.name, if (p.forOther) p.otherPhone else p.phone,
        )
    }

    /** An open case about the same people (same spot, same phone number, or the same name nearby) and why. */
    private fun findDuplicate(c: CaseItem): Pair<String, String>? {
        val me = facts(c)
        for (o in store.cases.value) {
            if (!o.isOpen || o.caseId == c.caseId) continue
            val why = Duplicates.reason(me, facts(o)) ?: continue
            return o.duplicateOf.ifBlank { o.caseId } to why
        }
        return null
    }

    /** Warning signs for the dispatcher (never used to lower the score). */
    private fun sosFlags(c: CaseItem, createdAt: Long, now: Long): List<String> {
        val day = 24 * 3600_000L
        val same = store.cases.value.filter { it.victim == c.victim && it.caseId != c.caseId }
        val cancels = same.count { it.cancelledAt > 0 && now - it.cancelledAt < day } + if (c.cancelledAt > 0 && now - c.cancelledAt < day) 1 else 0
        return SosChecks.flags(
            SosChecks.Input(
                now = now, createdAt = createdAt, hasPosition = c.lat != null, messages = c.repeats + 1,
                otherRecentCases = same.count { now - it.firstAt < SosChecks.BURST_WINDOW_MS }, recentCancels = cancels,
            ),
        )
    }

    /** Sets the flags and notes newly found ones in the case history. */
    private fun withFlags(c: CaseItem, flags: List<String>, now: Long): CaseItem {
        val fresh = flags.distinct()
        val added = fresh.filter { f -> c.flags.none { it.substringBefore(':') == f.substringBefore(':') } }
        if (added.isEmpty() && fresh == c.flags) return c
        if (added.isNotEmpty()) Graph.log("BASE", "Case ${c.caseId.take(6)} flagged: ${added.joinToString()}")
        return c.copy(flags = fresh, events = c.events + added.map { CaseEvent(now, "CHECK", "Check this SOS: $it") })
    }

    @Synchronized
    fun onUpdate(p: Packet, u: SosUpdate) {
        val now = System.currentTimeMillis()
        val c = store.cases.value.firstOrNull { it.caseId == u.caseId && it.victim == p.src } ?: return
        val hasLoc = Geo.isValid(u.lat, u.lon)
        val moved = if (hasLoc && c.lat != null && c.lon != null) Geo.distanceM(c.lat, c.lon, u.lat!!, u.lon!!) else 0.0
        val jump = if (hasLoc && c.lat != null && c.lon != null) SosChecks.jump(c.lat, c.lon, c.locAt ?: c.lastAt, u.lat!!, u.lon!!, u.locTime ?: now) else null
        val flagged = if (jump != null) withFlags(c, c.flags + jump, now) else c
        val updated = score(
            flagged.copy(
                lastAt = now, lat = if (hasLoc) u.lat else c.lat, lon = if (hasLoc) u.lon else c.lon,
                accuracy = if (hasLoc) u.accuracy else c.accuracy, locAt = if (hasLoc) u.locTime else c.locAt,
                battery = if (u.battery >= 0) u.battery else c.battery, lowBattery = c.lowBattery || u.lowBattery,
                track = if (hasLoc) (c.track + TrackPoint(u.lat!!, u.lon!!, u.locTime ?: now, u.battery)).takeLast(200) else c.track,
                events = if (u.lowBattery) flagged.events + CaseEvent(now, "BATTERY", "Victim battery low (${u.battery}%), last position sent") else flagged.events,
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
        val d = distanceFor(team, c) ?: return -1
        return etaModel(team).minutes(d)
    }

    private fun distanceFor(team: String, c: CaseItem): Double? {
        val from = teamPosition(team) ?: return null
        val lat = c.lat ?: return null
        val lon = c.lon ?: return null
        return Geo.distanceM(from.lat, from.lon, lat, lon)
    }

    /** Finished rescues the ETA model learns from: distance when the team set off and minutes until "rescued". */
    fun etaSamples(team: String = ""): List<EtaSample> = store.cases.value.filter { c ->
        val start = if (c.onWayAt > 0) c.onWayAt else c.assignedAt
        c.status == CaseStatus.RESCUED && c.departDistM > 0 && start > 0 && c.rescuedAt > start && (team.isBlank() || c.team == team)
    }.map { c -> EtaSample(c.departDistM.toDouble(), (c.rescuedAt - (if (c.onWayAt > 0) c.onWayAt else c.assignedAt)) / 60_000.0) }

    /** ETA model for a team: its own finished rescues once it has two, otherwise those of all teams. */
    fun etaModel(team: String = ""): EtaModel {
        val speed = Graph.prefs.now.speedKmh.toDouble()
        if (team.isNotBlank()) {
            val own = etaSamples(team)
            if (own.size >= 2) return EtaModel(speed, own)
        }
        return EtaModel(speed, etaSamples())
    }

    @Synchronized
    fun assign(caseId: String, team: String, by: String = "dispatcher") {
        val c = store.cases.value.firstOrNull { it.caseId == caseId } ?: return
        val clean = Credentials.cleanTeamName(team)
        val now = System.currentTimeMillis()
        val eta = etaFor(clean, c)
        val dist = distanceFor(clean, c)
        val updated = c.copy(
            status = CaseStatus.ASSIGNED, team = clean, etaMin = eta, assignedAt = now,
            departDistM = dist?.toInt() ?: c.departDistM,
            events = c.events + CaseEvent(now, "ASSIGN", "Assigned to team $clean by $by${if (eta > 0) ", ETA $eta min" else ""}"),
        )
        store.cases.upsert({ it.caseId }, updated)
        sendTask(updated)
        replyTo(updated, AckStatus.ASSIGNED, clean, eta, "", -1, emptyList())
        Graph.baseOps.syncCase(updated)
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
            departDistM = if (status == CaseStatus.ON_THE_WAY) distanceFor(c.team, c)?.toInt() ?: c.departDistM else c.departDistM,
            events = c.events + CaseEvent(now, "STATUS", "Marked ${status.lowercase()} at the base"),
        )
        store.cases.upsert({ it.caseId }, updated)
        Graph.baseOps.syncCase(updated)
        when (status) {
            CaseStatus.RESCUED -> replyTo(updated, AckStatus.RESCUED, c.team, -1, "", -1, emptyList())
            CaseStatus.ON_THE_WAY -> replyTo(updated, AckStatus.ON_THE_WAY, c.team, c.etaMin, "", -1, emptyList())
        }
    }

    /**
     * Gives waiting cases to teams with free places using the optimal plan ([Planner], Hungarian method): the most
     * urgent cases first and, among similar ones, the nearest team, best for all cases together. A duplicate goes
     * to the team of its original case. Returns how many cases were assigned.
     */
    @Synchronized
    fun autoAssign(by: String = "auto-assign"): Int {
        val teams = teams()
        if (teams.isEmpty()) return 0
        // Cases another base is already handling (multi-base sync) are left to that base.
        fun waiting() = store.cases.value.filter { it.status == CaseStatus.NEW && it.handledBy.isBlank() }
        fun originalOf(c: CaseItem) = if (c.duplicateOf.isBlank()) null else store.cases.value.firstOrNull { it.caseId == c.duplicateOf }
        var count = 0
        fun followOriginals() {
            for (c in waiting()) {
                val team = originalOf(c)?.takeIf { it.isOpen && it.team.isNotBlank() }?.team ?: continue
                assign(c.caseId, team, by)
                count++
            }
        }
        followOriginals()
        // Plan the rest; a duplicate whose original is still waiting follows it afterwards.
        val rest = waiting().filter { c -> originalOf(c)?.isOpen != true }
        if (rest.isNotEmpty()) {
            val models = teams.associateWith { etaModel(it) }
            val slots = teams.map { t ->
                val pos = teamPosition(t)
                TeamSlot(t, pos?.lat, pos?.lon, MAX_ACTIVE_PER_TEAM - activeTasks(t))
            }.filter { it.free > 0 }
            val plan = Planner.plan(slots, rest.map { WaitingCase(it.caseId, it.lat, it.lon, it.score) }) { t, w ->
                models.getValue(t.team).minutes(Geo.distanceM(t.lat!!, t.lon!!, w.lat!!, w.lon!!))
            }
            for (pick in plan) {
                assign(pick.caseId, pick.team, by)
                count++
            }
            followOriginals()
        }
        return count
    }

    private fun sendTask(c: CaseItem, cancelled: Boolean = false) {
        val keys = keys() ?: return
        val key = teamKey(c.team, create = false) ?: return
        val t = TaskPayload(
            c.caseId, c.victim, c.lat, c.lon, if (cancelled) "CANCELLED" else c.label, if (cancelled) -1 else c.score,
            summary(c), c.payload.medical, c.payload.people, c.team, System.currentTimeMillis(), c.accuracy,
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
        val now = System.currentTimeMillis()
        // It opened with the team key, so it really comes from that team: trust its position for that team's rescuer.
        val packetTeam = Destinations.teamName(p.dst)
        store.rescuers.modify({ it.rescuerId }, st.rescuerId) { r ->
            if (r.team != packetTeam) r
            else r.copy(lastSeen = now, deviceId = p.src, battery = if (st.battery >= 0) st.battery else r.battery)
                .movedTo(st.lat, st.lon, if (st.locAt > 0) st.locAt else st.at, now)
        }
        val c = store.cases.value.firstOrNull { it.caseId == st.caseId } ?: return
        val dist = if (Geo.isValid(st.lat, st.lon) && c.lat != null && c.lon != null) Geo.distanceM(st.lat!!, st.lon!!, c.lat, c.lon) else null
        val eta = if (dist != null) etaModel(st.team.ifBlank { c.team }).minutes(dist) else c.etaMin
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
            outcome = st.outcome.ifBlank { c.outcome },
            departDistM = if (status == CaseStatus.ON_THE_WAY && c.onWayAt == 0L && dist != null) dist.toInt() else c.departDistM,
            events = c.events + CaseEvent(now, "TEAM", "${st.rescuerName} (${st.team}): ${st.status.lowercase().replace('_', ' ')}${if (st.outcome.isNotBlank()) " · ${st.outcome}" else ""}"),
        )
        store.cases.upsert({ it.caseId }, updated)
        Graph.baseOps.syncCase(updated)
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
            val r = store.rescuers.value.firstOrNull { it.rescuerId == hb.rescuerId } ?: return
            val key = teamKey(r.team, create = false) ?: return
            if (!PositionAuth.verify(key, src, hb)) {
                // Anyone can seal a heartbeat for the base; only the rescuer's team key can sign its position.
                val last = unverifiedWarned[r.rescuerId] ?: 0L
                if (now - last > 10 * 60_000) {
                    unverifiedWarned[r.rescuerId] = now
                    Graph.log("BASE", "Ignored a position for rescuer ${r.name} that is not signed with team ${r.team}'s key")
                }
                return
            }
            store.rescuers.modify({ it.rescuerId }, hb.rescuerId) {
                it.copy(deviceId = src, lastSeen = now, battery = hb.battery).movedTo(hb.lat, hb.lon, hb.locAt, now)
            }
        }
    }

    /**
     * The rescuer at a newly reported position. Keeps a short trail of verified positions so the base can see
     * whether the rescuer is really getting closer to the victim. A position older than the one we already have
     * (store-and-forward can deliver out of order) does not move the rescuer back.
     */
    private fun RescuerRecord.movedTo(lat: Double?, lon: Double?, fixAt: Long, now: Long): RescuerRecord {
        if (!Geo.isValid(lat, lon)) return this
        val at = if (fixAt <= 0) now else minOf(fixAt, now)
        if (at < now - POSITION_MAX_AGE_MS || at < posAt) return this
        val last = trail.lastOrNull()
        val add = last == null || Geo.distanceM(last.lat, last.lon, lat!!, lon!!) >= 10 || at - last.at >= 2 * 60_000
        val newTrail = if (add) (trail.filter { at - it.at <= TRAIL_MAX_AGE_MS } + Crumb(lat!!, lon!!, at)).takeLast(TRAIL_MAX) else trail
        return copy(lat = lat, lon = lon, posAt = at, trail = newTrail)
    }

    /** Positions of a rescuer as tracking fixes, oldest first (the trail plus the latest position). */
    fun fixesOf(r: RescuerRecord): List<Fix> {
        val list = r.trail.map { Fix(it.lat, it.lon, it.at) }.toMutableList()
        if (r.lat != null && r.lon != null && list.none { it.at == r.positionAt }) list.add(Fix(r.lat, r.lon, r.positionAt))
        return list.sortedBy { it.at }
    }

    /** For a case with a team and a location: every rescuer of that team with a position, nearest first. */
    fun approaches(c: CaseItem): List<TeamApproach> {
        if (c.team.isBlank() || c.lat == null || c.lon == null) return emptyList()
        val target = LatLon(c.lat, c.lon)
        val speed = Graph.prefs.now.speedKmh.toDouble()
        return store.rescuers.value.filter { it.team == c.team }
            .mapNotNull { r ->
                val fixes = fixesOf(r).filter { Geo.isValid(it.lat, it.lon) }
                Tracking.approach(target, fixes, speed)?.let { TeamApproach(r, it, fixes) }
            }
            .sortedBy { it.approach.distanceM }
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
        // Re-rank open cases (waiting time raises urgency); save only when something changed.
        val changed = store.cases.value.filter { it.isOpen }.map { score(it, now) }
            .filter { n -> store.cases.value.firstOrNull { it.caseId == n.caseId }?.let { it.score != n.score || it.label != n.label } == true }
            .associateBy { it.caseId }
        if (changed.isNotEmpty()) {
            store.cases.edit { list -> list.map { c -> changed[c.caseId]?.let { n -> c.copy(score = n.score, label = n.label, modelScore = n.modelScore, reasons = n.reasons, proba = n.proba) } ?: c } }
        }
        if (Graph.prefs.now.autoAssign) autoAssign()
        Graph.baseOps.periodic(now)
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
        if (p.animals > 0) parts.add("${p.animals} animals")
        if (p.needs.isNotEmpty()) parts.add("needs " + p.needs.joinToString { it.lowercase().replace('_', ' ') })
        if (p.silent) parts.add("SILENT: approach quietly")
        if (p.forOther) parts.add("reported for " + p.otherName.ifBlank { "someone else" } + if (p.otherPhone.isNotBlank()) " (${p.otherPhone})" else "")
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

        /** Positions older than this are not used at all. */
        private const val POSITION_MAX_AGE_MS = 6 * 3600_000L

        /** The trail keeps the last few hours of positions (about one a minute while moving). */
        private const val TRAIL_MAX_AGE_MS = 3 * 3600_000L
        private const val TRAIL_MAX = 180
    }
}
