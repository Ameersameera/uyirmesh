@file:OptIn(ExperimentalMaterial3Api::class)

package com.ameer.uyirmesh.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.BuildConfig
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.Credentials
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Outbox
import com.ameer.uyirmesh.core.PacketCodec
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.ProfileCard
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.TaskState
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.sos.VoiceTrigger
import com.ameer.uyirmesh.util.CrashLog
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.delay
import java.io.File

/* ---------------- first aid ---------------- */

@Composable
fun FirstAidScreen(nav: Nav) {
    var open by remember { mutableStateOf(-1) }
    val lang = LocalLang.current
    val topics = FirstAid.topics(lang)
    Scaffold(topBar = { ScreenBar(tr("first_aid"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("first_aid_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ButtonRow {
                    val ctx = LocalContext.current
                    TonalButton(tr("call_108"), Ic.Call, Modifier.weight(1f)) { dial(ctx, "108") }
                    TonalButton(tr("call_112"), Ic.Call, Modifier.weight(1f)) { dial(ctx, "112") }
                }
            }
            items(topics.size) { i ->
                val t = topics[i]
                InfoCard(onClick = { open = if (open == i) -1 else i }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.first, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(Ic.Arrow, null)
                    }
                    if (open == i) {
                        Spacer(Modifier.height(6.dp))
                        t.second.forEachIndexed { n, step -> Text("${n + 1}. $step", modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            }
        }
    }
}

/* ---------------- shelters and danger zones ---------------- */

@Composable
fun SheltersScreen(nav: Nav) {
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val me = Graph.loc.lastLatLon
    val now = System.currentTimeMillis()
    val shelters = alerts.filter { it.kind == AlertKind.SHELTER }
    val zones = alerts.filter { (it.kind == AlertKind.DANGER || it.kind == AlertKind.EVACUATE) && it.isValid(now) && it.lat != null }
    Scaffold(topBar = { ScreenBar(tr("shelters"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                SecondaryButton(tr("scan_shelter_qr"), Ic.QrScan) { nav.go("scan/shelter") }
                SectionTitle(tr("danger_zones"))
                if (zones.isEmpty()) Text(tr("no_danger_zones"), style = MaterialTheme.typography.bodySmall)
            }
            items(zones, key = { "z" + it.id }) { z ->
                val inside = me != null && z.radiusM > 0 && Geo.distanceM(me, LatLon(z.lat!!, z.lon!!)) <= z.radiusM
                InfoCard(color = Palette.Red.copy(alpha = if (inside) 0.25f else 0.1f)) {
                    Text(z.title, fontWeight = FontWeight.Bold)
                    Text(z.message)
                    Text(distanceFromMe(z.lat, z.lon).removePrefix(" · ") + " · ${tr("radius")} ${Geo.formatDistance(z.radiusM.toDouble())}", style = MaterialTheme.typography.bodySmall)
                    if (inside) Text(tr("you_are_inside_leave"), color = Palette.Red, fontWeight = FontWeight.Bold)
                }
            }
            item { SectionTitle(tr("shelters")) }
            if (shelters.isEmpty()) item { EmptyState(Ic.Shelter, tr("no_shelters")) }
            items(shelters.sortedBy { s -> if (me != null && s.lat != null && s.lon != null) Geo.distanceM(me, LatLon(s.lat, s.lon)) else Double.MAX_VALUE }, key = { it.id }) { s ->
                val full = s.capacity > 0 && s.occupancy >= s.capacity
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Chip(if (full) tr("full") else "${s.occupancy}/${s.capacity}", if (full) Palette.Red else Palette.Green)
                    }
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    Text(distanceFromMe(s.lat, s.lon).removePrefix(" · ") + " · ${tr("updated")} ${timeText(s.at)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/* ---------------- public map ---------------- */

@Composable
fun PublicMapScreen(nav: Nav) {
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val reports by Graph.store.reports.flow.collectAsStateWithLifecycle()
    val safe by Graph.store.safe.flow.collectAsStateWithLifecycle()
    val loc by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Graph.loc.requestOnce(20_000) {} }
    val now = System.currentTimeMillis()
    val me = loc?.let { LatLon(it.latitude, it.longitude) }
    val markers = buildList {
        if (me != null) add(MapMarker("me", me.lat, me.lon, Palette.Blue, L.t("you"), 4))
        for (a in alerts) if (a.kind == AlertKind.SHELTER && a.lat != null && a.lon != null) add(MapMarker("a" + a.id, a.lat, a.lon, Palette.Teal, a.title, 3))
        for (r in reports) if (r.lat != null && r.lon != null && now - r.at < 24 * 3600_000L) add(MapMarker("r" + r.id, r.lat, r.lon, Palette.Amber, "", 2))
        for (s in safe) if (s.lat != null && s.lon != null) add(MapMarker("s" + s.id, s.lat, s.lon, Palette.Green, "", 0))
    }
    val zones = alerts.filter { (it.kind == AlertKind.DANGER || it.kind == AlertKind.EVACUATE) && it.isValid(now) && it.lat != null && it.lon != null }
        .map { MapZone(it.lat!!, it.lon!!, it.radiusM.coerceAtLeast(50).toDouble(), Palette.Red, it.title) }
    Scaffold(topBar = { ScreenBar(tr("map"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("map_offline_note"), style = MaterialTheme.typography.bodySmall)
            RadarMap(me, markers, zones = zones, selected = selected, onTap = { selected = it })
            MapLegend(listOf(Palette.Blue to L.t("you"), Palette.Teal to L.t("shelters"), Palette.Red to L.t("danger_zones"), Palette.Amber to L.t("reports"), Palette.Green to L.t("people_safe")))
            if (me == null) Text(tr("waiting_gps"), color = Palette.Orange)
            selected?.let { id ->
                val text = when {
                    id.startsWith("a") -> alerts.firstOrNull { "a" + it.id == id }?.let { "${it.title}: ${it.message}" }
                    id.startsWith("r") -> reports.firstOrNull { "r" + it.id == id }?.let { "${reportLabel(it.kind)}: ${it.note}" }
                    id.startsWith("s") -> safe.firstOrNull { "s" + it.id == id }?.let { "${it.name}: ${it.message}" }
                    else -> L.t("you")
                }
                if (text != null) InfoCard { Text(text) }
            }
        }
    }
}

/* ---------------- network status ---------------- */

@Composable
fun NetworkScreen(nav: Nav) {
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val peers by Graph.mesh.peers.collectAsStateWithLifecycle()
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val m = stats.metrics
    Scaffold(topBar = { ScreenBar(tr("network_status"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            InfoCard {
                KeyValue(tr("this_phone"), "${s.deviceId.take(8)} (${roleName(s.role)})")
                KeyValue(tr("mesh_service"), if (stats.running) tr("running") else tr("stopped"), if (stats.running) Palette.Green else Palette.Red)
                KeyValue(tr("advertising"), if (stats.advertising) tr("yes") else tr("no"))
                KeyValue(tr("discovering"), if (stats.discovering) tr("yes") else tr("no"))
                KeyValue(tr("nearby_phones"), peers.size.toString())
                KeyValue(tr("packets_stored"), "${stats.buffer} (${tr("waiting")}: ${stats.waiting})")
                KeyValue("TTL", "${s.ttl} ${tr("hops")}")
                if (stats.lastError.isNotBlank()) KeyValue(tr("last_error"), stats.lastError, Palette.Red)
            }
            ButtonRow {
                val ctx = LocalContext.current
                TonalButton(if (stats.running) tr("restart_mesh") else tr("start_mesh"), Ic.Refresh, Modifier.weight(1f)) {
                    Graph.mesh.stop()
                    MeshService.start(ctx, fromForeground = true)
                    Graph.mesh.start()
                }
                TonalButton(tr("send_heartbeat"), Ic.NetworkCheck, Modifier.weight(1f)) { Graph.mesh.sendHeartbeat() }
            }
            SectionTitle(tr("nearby_phones"))
            if (peers.isEmpty()) Text(tr("no_peers_hint"), style = MaterialTheme.typography.bodySmall)
            var blockTick by remember { mutableIntStateOf(0) }
            for (p in peers) {
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.role == 'B') Ic.Dashboard else if (p.role == 'R') Ic.Run else Ic.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${p.deviceId.take(8)} · ${roleName(roleOf(p.role))}", fontWeight = FontWeight.SemiBold)
                            Text("${tr("connected_for")} ${agoText(p.since)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TonalButton(tr("block_demo")) {
                            Graph.mesh.setBlocked(p.deviceId, true)
                            blockTick++
                        }
                    }
                }
            }
            val blocked = remember(peers, blockTick) { Graph.mesh.blockedPeers() }
            if (blocked.isNotEmpty()) {
                SectionTitle(tr("blocked_links"))
                Text(tr("blocked_links_sub"), style = MaterialTheme.typography.bodySmall)
                for (b in blocked) {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.take(8), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TonalButton(tr("unblock")) {
                                Graph.mesh.setBlocked(b, false)
                                blockTick++
                            }
                        }
                    }
                }
            }
            Text(tr("block_demo_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle(tr("packet_counters"))
            InfoCard {
                for ((k, v) in m) KeyValue(counterName(k), v.toString())
            }
            SectionTitle(tr("how_it_works"))
            Text(tr("how_it_works_body"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun roleOf(c: Char): String = when (c) {
    'B' -> Roles.BASE
    'R' -> Roles.RESCUER
    else -> Roles.PUBLIC
}

@Composable
fun roleName(role: String): String = when (role) {
    Roles.BASE -> tr("role_base")
    Roles.RESCUER -> tr("role_rescuer")
    else -> tr("role_public")
}

@Composable
private fun counterName(k: String): String = when (k) {
    "received" -> tr("c_received")
    "consumed" -> tr("c_consumed")
    "originated" -> tr("c_originated")
    "relayed" -> tr("c_relayed")
    "transmissions" -> tr("c_transmissions")
    "duplicates" -> tr("c_duplicates")
    "crcDrops" -> tr("c_crc")
    "formatDrops" -> tr("c_format")
    "replayDrops" -> tr("c_replay")
    "rateDrops" -> tr("c_rate")
    "forgedDrops" -> tr("c_forged")
    "ttlStops" -> tr("c_ttl")
    "sendFailures" -> tr("c_failures")
    else -> k
}

/* ---------------- event log ---------------- */

@Composable
fun LogScreen(nav: Nav) {
    val log by Graph.store.log.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Scaffold(topBar = {
        ScreenBar(tr("event_log"), { nav.back() }) {
            IconButton(onClick = { shareText(ctx, "UyirMesh log", logText()) }) { Icon(Ic.Share, tr("share")) }
            IconButton(onClick = { Graph.store.log.clear() }) { Icon(Ic.Delete, tr("clear")) }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            if (log.isEmpty()) item { EmptyState(Ic.History, tr("log_empty")) }
            items(log.reversed()) { e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(timeText(e.at), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                    Chip(e.tag, tagColor(e.tag))
                    Spacer(Modifier.width(6.dp))
                    Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun tagColor(tag: String) = when (tag) {
    "SOS" -> Palette.Red
    "SECURITY", "ERROR" -> Palette.Purple
    "ALERT" -> Palette.Orange
    "BASE" -> Palette.Blue
    "RESCUER" -> Palette.Teal
    else -> Palette.Grey
}

fun logText(): String = Graph.store.log.value.joinToString("\n") { "${timeText(it.at)} [${it.tag}] ${it.text}" }

fun shareText(ctx: Context, subject: String, text: String) {
    try {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "uyirmesh_${System.currentTimeMillis()}.txt")
        f.writeText(text)
        val uri = FileProvider.getUriForFile(ctx, "com.ameer.uyirmesh.files", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text.take(50_000))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Graph.note("share", e)
    }
}

fun shareFile(ctx: Context, f: File, mime: String, subject: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "com.ameer.uyirmesh.files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Graph.note("share file", e)
    }
}

/* ---------------- self-test ---------------- */

private data class Check(val name: String, val ok: Boolean, val detail: String, val warn: Boolean = false, val fix: (() -> Unit)? = null)

@Composable
fun SelfTestScreen(nav: Nav) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var cryptoResult by remember { mutableStateOf("") }
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val voice by VoiceTrigger.status.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(Unit) {
        Graph.loc.requestOnce(30_000) { refresh++ }
        while (true) {
            delay(3_000)
            refresh++
        }
    }
    @Suppress("UNUSED_VARIABLE") val r = refresh
    val loc = Graph.loc.last
    val checks = listOf(
        Check(tr("perm_nearby"), Perms.meshReady(ctx), if (Perms.meshReady(ctx)) tr("granted") else tr("missing"), fix = { launcher.launch(Perms.missing(ctx, Perms.mesh()).toTypedArray()) }),
        Check(tr("bluetooth_on"), Perms.bluetoothOn(ctx), "", fix = { Perms.open(ctx, Perms.bluetoothSettings()) }),
        Check(tr("location_on"), Perms.locationOn(ctx), "", fix = { Perms.open(ctx, Perms.locationSettings()) }),
        Check(tr("wifi_on"), Perms.wifiOn(ctx), tr("wifi_optional"), warn = true, fix = { Perms.open(ctx, Perms.wifiSettings()) }),
        Check(tr("mesh_service"), stats.running, if (stats.running) tr("running") else tr("stopped"), fix = { MeshService.start(ctx, true) }),
        Check(tr("advertising"), stats.advertising, stats.lastError),
        Check(tr("discovering"), stats.discovering, ""),
        Check(tr("nearby_phones"), stats.peers > 0, stats.peers.toString(), warn = true),
        Check(tr("perm_mic"), Perms.hasMic(ctx), "", warn = true, fix = { launcher.launch(arrayOf(Perms.MIC)) }),
        Check(tr("voice_sos"), voice == VoiceTrigger.STATUS_LISTENING, voiceStatus(voice), warn = true),
        Check(tr("gps_fix"), loc != null && Graph.loc.ageMs() < 10 * 60_000, if (loc != null) "±${loc.accuracy.toInt()} m, ${agoText(loc.time)}" else tr("waiting_gps"), warn = true),
        Check(tr("flashlight"), Graph.alarm.hasTorch(), "", warn = true),
        Check(tr("perm_notif"), Perms.hasNotifications(ctx), "", warn = true, fix = { launcher.launch(Perms.notifications().toTypedArray()) }),
        Check(tr("battery_opt"), Perms.ignoringBatteryOptimizations(ctx), tr("battery_opt_why"), warn = true, fix = {
            try {
                ctx.startActivity(Perms.batteryOptimizationRequest(ctx))
            } catch (e: Exception) {
                Perms.open(ctx, Perms.batteryOptimizationList())
            }
        }),
        Check(tr("secure_storage"), Graph.vault.selfTest(), if (Graph.vault.usingKeystore) "Android Keystore (AES-256-GCM)" else tr("software_key")),
        Check(tr("base_key_pinned"), true, Graph.pinned.sigFingerprint),
    )
    Scaffold(topBar = { ScreenBar(tr("self_test"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                val bad = checks.count { !it.ok && !it.warn }
                InfoCard(color = (if (bad == 0) Palette.Green else Palette.Red).copy(alpha = 0.14f)) {
                    Text(if (bad == 0) tr("all_good") else tr("problems_found").replace("{n}", bad.toString()), fontWeight = FontWeight.Bold)
                    Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · UyirMesh ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(checks) { c ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(c.ok, c.warn)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!c.ok && c.fix != null) TonalButton(tr("fix"), onClick = c.fix)
                    }
                }
            }
            item {
                SectionTitle(tr("crypto_test"))
                SecondaryButton(tr("run_crypto_test"), Ic.Security) { cryptoResult = runCryptoTest() }
                if (cryptoResult.isNotBlank()) Text(cryptoResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("test_alarm"), Ic.Siren) {
                    Graph.alarm.vibrateSos()
                    Graph.alarm.beep()
                }
            }
        }
    }
}

@Composable
fun voiceStatus(s: String): String = when (s) {
    VoiceTrigger.STATUS_LISTENING -> tr("voice_listening")
    VoiceTrigger.STATUS_LOADING -> tr("voice_loading")
    VoiceTrigger.STATUS_PAUSED -> tr("voice_paused")
    VoiceTrigger.STATUS_NO_PERMISSION -> tr("voice_no_permission")
    VoiceTrigger.STATUS_NO_MODEL -> tr("voice_no_model")
    VoiceTrigger.STATUS_MIC_BUSY -> tr("voice_mic_busy")
    VoiceTrigger.STATUS_ERROR -> tr("voice_error")
    else -> tr("voice_off")
}

/** Seals, opens, signs and verifies a sample SOS with this phone's crypto engine and reports the timings. */
fun runCryptoTest(): String = try {
    val sb = StringBuilder()
    val t0 = System.nanoTime()
    val sample = SosPayload("selftest", "TRAPPED", 2, note = "self test")
    val out = Outbox(Graph.prefs.now.deviceId, Graph.pinned)
    val p = out.sealedRescue(PacketType.SOS, sample.toBytes())
    val t1 = System.nanoTime()
    val bytes = PacketCodec.encode(p.relayCopy(1))
    val back = PacketCodec.decode(bytes)
    val t2 = System.nanoTime()
    sb.append("ECDH+AES-256-GCM seal: ${(t1 - t0) / 1_000_000} ms\n")
    sb.append("Packet ${bytes.size} bytes, CRC-32 ${PacketCodec.crcHex(bytes)} OK (${(t2 - t1) / 1_000_000} ms)\n")
    val k = Crypto.newEcKeyPair()
    val sig = Crypto.sign(k.private, bytes)
    val ok = Crypto.verify(k.public, bytes, sig)
    val t3 = System.nanoTime()
    sb.append("ECDSA P-256 sign+verify: ${if (ok) "OK" else "FAIL"} (${(t3 - t2) / 1_000_000} ms)\n")
    bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
    val crcCaught = try {
        PacketCodec.decode(bytes)
        false
    } catch (e: Exception) {
        true
    }
    sb.append("Damaged packet rejected by CRC: ${if (crcCaught) "OK" else "FAIL"}\n")
    sb.append("Base key ${Graph.pinned.sigFingerprint} · packet id ${back.shortId}")
    sb.toString()
} catch (e: Exception) {
    "FAILED: ${e.message}"
}

/* ---------------- settings ---------------- */

@Composable
fun SettingsScreen(nav: Nav) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(topBar = { ScreenBar(tr("settings"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle(tr("language"))
            ButtonRow {
                TonalButton("English", modifier = Modifier.weight(1f), enabled = s.lang != "en") { Graph.prefs.update { it.copy(lang = "en") } }
                TonalButton("தமிழ்", modifier = Modifier.weight(1f), enabled = s.lang != "ta") { Graph.prefs.update { it.copy(lang = "ta") } }
            }
            SectionTitle(tr("display"))
            SwitchRow(tr("large_text"), "", s.largeText) { v -> Graph.prefs.update { it.copy(largeText = v) } }
            ButtonRow {
                TonalButton(tr("theme_auto"), modifier = Modifier.weight(1f), enabled = s.theme != 0) { Graph.prefs.update { it.copy(theme = 0) } }
                TonalButton(tr("theme_light"), modifier = Modifier.weight(1f), enabled = s.theme != 1) { Graph.prefs.update { it.copy(theme = 1) } }
                TonalButton(tr("theme_dark"), modifier = Modifier.weight(1f), enabled = s.theme != 2) { Graph.prefs.update { it.copy(theme = 2) } }
            }
            SectionTitle(tr("hands_free_sos"))
            SwitchRow(tr("voice_sos"), tr("voice_sos_sub"), s.voice) { v -> Graph.prefs.update { it.copy(voice = v) } }
            SwitchRow(tr("shake_sos"), tr("shake_sos_sub"), s.shake) { v -> Graph.prefs.update { it.copy(shake = v) } }
            Text(tr("other_triggers"), style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("battery"))
            SwitchRow(tr("battery_saver"), tr("battery_saver_sub"), s.batterySaver) { v -> Graph.prefs.update { it.copy(batterySaver = v) } }
            SectionTitle(tr("mesh"))
            Stepper(tr("ttl_hops"), s.ttl, 1, 10, Ic.Network) { v -> Graph.prefs.update { it.copy(ttl = v) } }
            Text(tr("ttl_sub"), style = MaterialTheme.typography.bodySmall)
            SwitchRow(tr("drill_mode"), tr("drill_mode_sub"), s.drill) { v -> Graph.prefs.update { it.copy(drill = v) } }
            SectionTitle(tr("diagnostics"))
            MenuTile(Ic.History, tr("event_log"), tr("event_log_sub")) { nav.go("log") }
            MenuTile(Ic.Security, tr("self_test"), tr("self_test_sub")) { nav.go("selftest") }
            MenuTile(Ic.Bug, tr("share_diagnostics"), tr("share_diagnostics_sub")) { shareText(ctx, "UyirMesh diagnostics", diagnostics(ctx)) }
            MenuTile(Ic.Info, tr("about"), "UyirMesh ${BuildConfig.VERSION_NAME}") { nav.go("about") }
            SectionTitle(tr("danger_area"))
            SecondaryButton(tr("stop_mesh"), Ic.Stop) { MeshService.stop(ctx) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("reset_app"), Ic.Delete) { confirmReset = true }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("reset_app_q")) },
            text = { Text(tr("reset_app_body")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    listOf(Graph.store.mySos, Graph.store.chat, Graph.store.alerts, Graph.store.safe, Graph.store.reports, Graph.store.missing, Graph.store.finds, Graph.store.log).forEach { it.clear() }
                    Graph.store.flush()
                }) { Text(tr("yes_reset")) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("cancel")) } },
        )
    }
}

fun diagnostics(ctx: Context): String = buildString {
    val s = Graph.prefs.now
    val st = Graph.mesh.stats.value
    append("UyirMesh ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
    append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
    append("Device ${s.deviceId.take(8)} role ${s.role} ttl ${s.ttl} saver ${s.batterySaver} voice ${s.voice} shake ${s.shake}\n")
    append("Perms mesh=${Perms.meshReady(ctx)} mic=${Perms.hasMic(ctx)} loc=${Perms.hasLocation(ctx)} notif=${Perms.hasNotifications(ctx)}\n")
    append("Radios bt=${Perms.bluetoothOn(ctx)} wifi=${Perms.wifiOn(ctx)} location=${Perms.locationOn(ctx)} batteryExempt=${Perms.ignoringBatteryOptimizations(ctx)}\n")
    append("Mesh running=${st.running} adv=${st.advertising} disc=${st.discovering} peers=${st.peers} buffer=${st.buffer} err=${st.lastError}\n")
    append("Counters ${st.metrics}\n")
    append("Voice ${VoiceTrigger.status.value} keystore=${Graph.vault.usingKeystore}\n\n")
    CrashLog.read()?.let { append("=== Last crash ===\n").append(it).append("\n\n") }
    val errors = CrashLog.readErrors(ctx)
    if (errors.isNotBlank()) append("=== Errors ===\n").append(errors.takeLast(20_000)).append("\n\n")
    append("=== Event log ===\n").append(logText().takeLast(30_000))
}

/* ---------------- about ---------------- */

@Composable
fun AboutScreen(nav: Nav) {
    Scaffold(topBar = { ScreenBar(tr("about"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("UyirMesh", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(tr("tagline"))
            Text("${tr("version")} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("project"))
            Text(tr("project_body"))
            SectionTitle(tr("security"))
            InfoCard {
                KeyValue(tr("base_enc_key"), Graph.pinned.encFingerprint)
                KeyValue(tr("base_sig_key"), Graph.pinned.sigFingerprint)
                KeyValue(tr("rescuer_group_key"), Graph.pinned.rescFingerprint)
            }
            Text(tr("security_body"), style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("how_it_works"))
            Text(tr("how_it_works_body"), style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("libraries"))
            Text("Google Nearby Connections · Vosk speech recognition (Apache 2.0) · ZXing (Apache 2.0) · NanoHTTPD (BSD) · JNA (Apache 2.0) · Jetpack Compose · CameraX · scikit-learn (model training)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- modes ---------------- */

@Composable
fun ModesScreen(nav: Nav) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { ScreenBar(tr("rescue_modes"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("modes_body"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            MenuTile(Ic.Person, tr("role_public"), tr("role_public_sub") + if (s.role == Roles.PUBLIC) " ✓" else "", Palette.Green) {
                if (s.role != Roles.PUBLIC) {
                    Graph.prefs.update { it.copy(role = Roles.PUBLIC) }
                    Graph.mesh.onRoleChanged()
                }
                nav.replace("home")
            }
            MenuTile(Ic.Run, tr("role_rescuer"), tr("role_rescuer_sub") + if (s.role == Roles.RESCUER) " ✓" else "", Palette.Blue) {
                if (Graph.rescuer.bundle() != null) {
                    Graph.prefs.update { it.copy(role = Roles.RESCUER) }
                    Graph.mesh.onRoleChanged()
                    nav.replace("home")
                } else {
                    nav.go("rescuer_login")
                }
            }
            MenuTile(Ic.Dashboard, tr("role_base"), tr("role_base_sub") + if (s.role == Roles.BASE) " ✓" else "", Palette.Red) {
                if (Graph.base.hasKeys()) {
                    Graph.prefs.update { it.copy(role = Roles.BASE) }
                    Graph.mesh.onRoleChanged()
                    nav.replace("home")
                } else {
                    nav.go("base_setup")
                }
            }
        }
    }
}

/* ---------------- QR scanner ---------------- */

@Composable
fun ScanScreen(nav: Nav, purpose: String) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Perms.hasCamera(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Perms.CAMERA) }
    var result by remember { mutableStateOf<String?>(null) }
    val title = when (purpose) {
        "rescuer" -> tr("scan_rescuer_qr")
        "victim" -> tr("scan_victim_qr")
        "shelter" -> tr("scan_shelter_qr")
        else -> tr("scan_qr")
    }
    Scaffold(topBar = { ScreenBar(title, { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val r = result
            if (r == null) {
                if (!granted) {
                    EmptyState(Ic.Camera, tr("camera_needed"))
                    SecondaryButton(tr("allow_camera")) { launcher.launch(Perms.CAMERA) }
                } else {
                    Text(tr("point_camera"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    QrScanner(Modifier.fillMaxWidth().aspectRatio(1f)) { result = it }
                }
            } else {
                ScanResult(nav, purpose, r) { result = null }
            }
        }
    }
}

@Composable
private fun ScanResult(nav: Nav, purpose: String, text: String, again: () -> Unit) {
    when {
        Credentials.isCredentialQr(text) -> {
            RescuerPinEntry(nav, text)
        }
        text.startsWith("UMP1:") -> {
            val card = try {
                ProfileCard.fromQr(text)
            } catch (e: Exception) {
                null
            }
            if (card == null) {
                Text(tr("bad_qr"))
            } else {
                InfoCard(color = Palette.Red.copy(alpha = 0.08f)) {
                    Text(card.name.ifBlank { tr("unknown_name") }, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    if (card.phone.isNotBlank()) KeyValue(tr("phone"), card.phone)
                    KeyValue(tr("blood_group"), card.blood.ifBlank { "-" })
                    KeyValue(tr("allergies"), card.allergies.ifBlank { "-" }, if (card.allergies.isNotBlank()) Palette.Red else MaterialTheme.colorScheme.onSurface)
                    KeyValue(tr("conditions"), card.conditions.ifBlank { "-" })
                    KeyValue(tr("medicines"), card.medicines.ifBlank { "-" })
                    KeyValue(tr("emergency_contact"), "${card.contactName} ${card.contactPhone}".trim().ifBlank { "-" })
                }
                val task = Graph.store.tasks.value.firstOrNull { it.victim == card.deviceId && it.state != TaskState.RESCUED }
                val case = Graph.store.cases.value.firstOrNull { it.victim == card.deviceId && it.isOpen }
                if (task != null && Graph.rescuer.bundle() != null) {
                    InfoCard(color = Palette.Green.copy(alpha = 0.12f)) {
                        Text(tr("matches_task"), fontWeight = FontWeight.Bold)
                        Text(task.summary, style = MaterialTheme.typography.bodySmall)
                        TonalButton(tr("mark_rescued"), Ic.CheckCircle) {
                            Graph.rescuer.setTaskState(task.caseId, TaskState.RESCUED)
                            nav.back()
                        }
                    }
                } else if (case != null && Graph.base.hasKeys()) {
                    InfoCard(color = Palette.Green.copy(alpha = 0.12f)) {
                        Text(tr("matches_case"), fontWeight = FontWeight.Bold)
                        TonalButton(tr("open_case"), Ic.Arrow) { nav.go("case/${case.caseId}") }
                    }
                }
            }
        }
        text.startsWith("UMS1:") -> {
            val parts = text.split(':')
            val id = parts.getOrNull(1).orEmpty()
            val name = parts.drop(2).joinToString(":")
            var done by remember { mutableStateOf(false) }
            InfoCard(color = Palette.Teal.copy(alpha = 0.12f)) {
                Text(tr("shelter"), style = MaterialTheme.typography.labelMedium)
                Text(name, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                if (!done) {
                    PrimaryButton(tr("check_in_here"), Ic.CheckCircle, color = Palette.Green) {
                        Graph.public.sendSafe(L.t("checked_in_at").replace("{s}", name), "SHELTER:$id:$name")
                        done = true
                    }
                } else {
                    Text(tr("checked_in_done"), color = Palette.Green, fontWeight = FontWeight.Bold)
                }
            }
        }
        else -> {
            Text(tr("bad_qr"))
            Text(text.take(200), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    Spacer(Modifier.height(12.dp))
    SecondaryButton(tr("scan_again"), Ic.QrScan, onClick = again)
    @Suppress("UNUSED_VARIABLE") val p = purpose
}
