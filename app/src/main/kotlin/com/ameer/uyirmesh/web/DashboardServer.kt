package com.ameer.uyirmesh.web

import android.content.Context
import com.ameer.uyirmesh.BuildConfig
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.base.Reports
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.Hex
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.Triage
import com.ameer.uyirmesh.core.int
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.core.bool
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.util.AudioConvert
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Small web server on the base phone. A laptop joins the base phone's hotspot (no internet needed)
 * and opens http://<phone-ip>:8080 to see the same dashboard in a browser. Login uses the base password.
 * /app offers the UyirMesh APK so more phones can install it offline.
 */
class DashboardServer(private val ctx: Context) : NanoHTTPD(PORT) {
    private val sessions = ConcurrentHashMap<String, Long>()
    private var failures = 0
    private var lockedUntil = 0L
    private val wavCache = ConcurrentHashMap<String, ByteArray>()

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Graph.note("web ${session.uri}", e)
        json(Response.Status.INTERNAL_ERROR, mapOf("error" to (e.message ?: "error")))
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri ?: "/"
        return when {
            uri == "/" || uri == "/index.html" -> asset("web/index.html", "text/html; charset=utf-8")
            uri == "/app.js" -> asset("web/app.js", "application/javascript; charset=utf-8")
            uri == "/style.css" -> asset("web/style.css", "text/css; charset=utf-8")
            uri == "/app" || uri == "/app/" -> asset("web/download.html", "text/html; charset=utf-8")
            uri == "/uyirmesh.apk" -> apk()
            uri == "/api/login" && s.method == Method.POST -> login(s)
            uri.startsWith("/api/") -> if (authed(s)) api(s, uri) else json(Response.Status.UNAUTHORIZED, mapOf("error" to "login"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun asset(path: String, mime: String): Response {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()).also {
            it.addHeader("Cache-Control", "no-cache")
        }
    }

    private fun apk(): Response {
        val f = File(ctx.applicationInfo.sourceDir)
        return newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(f), f.length()).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"UyirMesh.apk\"")
        }
    }

    private fun body(s: IHTTPSession): Map<String, Any?> {
        val files = HashMap<String, String>()
        s.parseBody(files)
        val raw = files["postData"] ?: return emptyMap()
        return try {
            Json.parseObject(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun login(s: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return json(Response.Status.FORBIDDEN, mapOf("error" to "Too many attempts. Wait a minute."))
        val pw = body(s).str("password")
        if (pw.isEmpty() || !Graph.base.checkPassword(pw.toCharArray())) {
            failures++
            if (failures >= 5) {
                lockedUntil = now + 60_000
                failures = 0
            }
            Graph.log("SECURITY", "Web dashboard: wrong password from ${s.remoteIpAddress}")
            return json(Response.Status.UNAUTHORIZED, mapOf("error" to "Wrong password"))
        }
        failures = 0
        val token = Hex.encode(Crypto.random(24))
        sessions[token] = now + 12 * 3600_000L
        Graph.log("BASE", "Web dashboard login from ${s.remoteIpAddress}")
        return json(Response.Status.OK, mapOf("ok" to true)).also {
            it.addHeader("Set-Cookie", "um_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200")
        }
    }

    private fun authed(s: IHTTPSession): Boolean {
        val token = s.cookies?.read("um_session") ?: s.headers["x-session"] ?: return false
        val exp = sessions[token] ?: return false
        if (exp < System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun api(s: IHTTPSession, uri: String): Response {
        val base = Graph.base
        return when (uri) {
            "/api/state" -> json(Response.Status.OK, state())
            "/api/media" -> media(s.parameters["f"]?.firstOrNull() ?: "", wav = false)
            "/api/media.wav" -> media(s.parameters["f"]?.firstOrNull() ?: "", wav = true)
            "/api/export.csv" -> newFixedLengthResponse(Response.Status.OK, "text/csv; charset=utf-8", Reports.csv()).also {
                it.addHeader("Content-Disposition", "attachment; filename=\"uyirmesh_cases.csv\"")
            }
            "/api/logout" -> {
                s.cookies?.read("um_session")?.let { sessions.remove(it) }
                json(Response.Status.OK, mapOf("ok" to true))
            }
            else -> {
                if (s.method != Method.POST) return json(Response.Status.BAD_REQUEST, mapOf("error" to "POST required"))
                val b = body(s)
                when (uri) {
                    "/api/assign" -> base.assign(b.str("caseId"), b.str("team"), by = "web dashboard")
                    "/api/status" -> base.setStatus(b.str("caseId"), b.str("status"))
                    "/api/message" -> base.messageVictim(b.str("caseId"), b.str("text"))
                    "/api/alert" -> {
                        val loc = if (b.bool("useLocation")) Graph.loc.last else null
                        base.sendAlert(
                            b.str("kind", "INFO"), b.int("severity", 1), b.str("title"), b.str("message"),
                            loc?.latitude, loc?.longitude, if (loc != null) b.int("radius", 500) else 0, b.int("validMinutes", 360),
                        )
                    }
                    "/api/autoassign" -> {
                        Graph.prefs.update { it.copy(autoAssign = b.bool("on")) }
                        if (b.bool("on")) base.autoAssign()
                    }
                    "/api/shelter" -> {
                        val loc = Graph.loc.last
                        base.addShelter(b.str("name").ifBlank { "Shelter" }, loc?.latitude, loc?.longitude, b.int("capacity", 100))
                    }
                    "/api/supplies" -> base.updateSupplies(b.str("id"), b.int("water"), b.int("food"), b.int("medicine"), b.int("capacity", 100))
                    "/api/teamchat" -> base.sendTeamChat(b.str("team"), b.str("text"))
                    else -> return json(Response.Status.NOT_FOUND, mapOf("error" to "unknown"))
                }
                json(Response.Status.OK, mapOf("ok" to true))
            }
        }
    }

    private fun media(file: String, wav: Boolean): Response {
        val bytes = Graph.base.mediaBytes(file) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
        if (file.endsWith(".jpg")) return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        if (wav) {
            val w = wavCache[file] ?: AudioConvert.amrToWav(bytes, ctx.cacheDir)?.also { wavCache[file] = it }
            if (w != null) return newFixedLengthResponse(Response.Status.OK, "audio/wav", ByteArrayInputStream(w), w.size.toLong())
        }
        return newFixedLengthResponse(Response.Status.OK, "audio/amr", ByteArrayInputStream(bytes), bytes.size.toLong()).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"$file\"")
        }
    }

    private fun json(status: Response.Status, value: Any?): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", Json.stringify(value)).also {
            it.addHeader("Cache-Control", "no-store")
        }

    /* ---------------- snapshot for the browser ---------------- */

    private fun caseJson(c: CaseItem): Map<String, Any?> {
        val p = c.payload
        return linkedMapOf(
            "id" to c.caseId, "victim" to c.victim.take(8), "kind" to p.kind, "kindLabel" to Triage.kindLabel(p.kind),
            "people" to p.people, "children" to p.children, "elderly" to p.elderly, "disabled" to p.disabled, "pregnant" to p.pregnant,
            "injured" to p.injured, "needs" to p.needs, "note" to p.note, "name" to p.name, "phone" to p.phone, "blood" to p.blood,
            "medical" to p.medical, "trigger" to p.trigger, "battery" to c.battery, "lowBattery" to c.lowBattery,
            "lat" to c.lat, "lon" to c.lon, "accuracy" to c.accuracy, "locAt" to c.locAt, "hops" to c.hops, "path" to c.path,
            "firstAt" to c.firstAt, "lastAt" to c.lastAt, "repeats" to c.repeats, "score" to c.score, "label" to c.label,
            "modelScore" to c.modelScore, "reasons" to c.reasons.map { mapOf("label" to it.label, "points" to it.points) },
            "proba" to c.proba, "status" to c.status, "team" to c.team, "rescuer" to c.rescuerName, "eta" to c.etaMin,
            "assignedAt" to c.assignedAt, "onWayAt" to c.onWayAt, "rescuedAt" to c.rescuedAt, "cancelledAt" to c.cancelledAt,
            "duplicateOf" to c.duplicateOf, "drill" to c.drill, "summary" to Graph.base.summary(c),
            "media" to c.media.map { mapOf("file" to it.file, "mime" to it.mime, "durationMs" to it.durationMs, "at" to it.at) },
            "track" to c.track.map { listOf(it.lat, it.lon, it.at) },
            "events" to c.events.map { mapOf("at" to it.at, "kind" to it.kind, "text" to it.text) },
        )
    }

    private fun state(): Map<String, Any?> {
        val store = Graph.store
        val s = Graph.prefs.now
        val loc = Graph.loc.last
        val st = Graph.mesh.stats.value
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "now" to now,
            "base" to linkedMapOf(
                "name" to s.baseName, "device" to s.deviceId.take(8), "lat" to loc?.latitude, "lon" to loc?.longitude,
                "fingerprint" to Graph.pinned.sigFingerprint, "autoAssign" to s.autoAssign, "speedKmh" to s.speedKmh,
                "drill" to s.drill, "version" to BuildConfig.VERSION_NAME,
            ),
            "mesh" to linkedMapOf(
                "running" to st.running, "peers" to Graph.mesh.peers.value.map { mapOf("device" to it.deviceId.take(8), "role" to it.role.toString(), "since" to it.since) },
                "buffer" to st.buffer, "waiting" to st.waiting, "metrics" to st.metrics,
            ),
            "cases" to store.cases.value.map { caseJson(it) },
            "teams" to Graph.base.teams(),
            "rescuers" to store.rescuers.value.map {
                mapOf("id" to it.rescuerId.take(8), "name" to it.name, "team" to it.team, "lastSeen" to it.lastSeen, "lat" to it.lat, "lon" to it.lon, "battery" to it.battery, "expiresAt" to it.expiresAt)
            },
            "nodes" to store.nodes.value.filter { now - it.lastSeen < 30 * 60_000 }.map {
                mapOf("id" to it.deviceId.take(8), "role" to it.role, "battery" to it.battery, "neighbours" to it.neighbours.map { n -> n.take(8) }, "lastSeen" to it.lastSeen, "name" to it.name, "team" to it.team)
            } + listOf(mapOf("id" to s.deviceId.take(8), "role" to "BASE", "battery" to Graph.mesh.batteryPercent(), "neighbours" to Graph.mesh.peers.value.map { it.deviceId.take(8) }, "lastSeen" to now, "name" to s.baseName, "team" to "")),
            "alerts" to store.alerts.value.filter { it.kind != "SHELTER" }.map {
                mapOf("id" to it.id, "kind" to it.kind, "severity" to it.severity, "title" to it.title, "message" to it.message, "lat" to it.lat, "lon" to it.lon, "radius" to it.radiusM, "at" to it.at, "validUntil" to it.validUntil, "mine" to it.mine)
            },
            "shelters" to store.shelters.value.map {
                mapOf("id" to it.id, "name" to it.name, "lat" to it.lat, "lon" to it.lon, "capacity" to it.capacity, "occupancy" to it.occupancy, "water" to it.water, "food" to it.food, "medicine" to it.medicine, "updatedAt" to it.updatedAt)
            },
            "volunteers" to store.volunteers.value.map {
                mapOf("name" to it.name, "phone" to it.phone, "skills" to it.skills, "note" to it.note, "lat" to it.lat, "lon" to it.lon, "at" to it.at)
            },
            "reports" to store.reports.value.takeLast(200).map {
                mapOf("kind" to it.kind, "note" to it.note, "lat" to it.lat, "lon" to it.lon, "name" to it.name, "at" to it.at)
            },
            "missing" to store.missing.value.map {
                mapOf("person" to it.person, "age" to it.age, "description" to it.description, "lastSeen" to it.lastSeen, "contact" to it.contact, "reporter" to it.reporter, "found" to it.found, "at" to it.at)
            },
            "safe" to store.safe.value.takeLast(500).map { mapOf("name" to it.name, "message" to it.message, "place" to it.place.substringAfterLast(':'), "at" to it.at) },
            "checkins" to store.checkins.value.map { mapOf("shelter" to it.shelterId, "name" to it.name, "at" to it.at) },
            "hotspots" to Graph.base.hotspots().map { mapOf("lat" to it.center.lat, "lon" to it.center.lon, "count" to it.count, "radius" to it.radiusM, "cases" to it.caseIds) },
            "teamChat" to store.teamChat.value.takeLast(100).map { mapOf("name" to it.name, "text" to it.text, "at" to it.at, "mine" to it.mine) },
            "log" to store.log.value.takeLast(150).map { mapOf("at" to it.at, "tag" to it.tag, "text" to it.text) },
        )
    }

    companion object {
        const val PORT = 8080

        /** http://address:port for every IPv4 address of this phone (hotspot, Wi-Fi, USB tethering). */
        fun localUrls(port: Int): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress } }
                .sortedBy { (name, _) -> if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1")) 0 else 1 }
                .mapNotNull { (_, ip) -> ip?.let { "http://$it:$port" } }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
