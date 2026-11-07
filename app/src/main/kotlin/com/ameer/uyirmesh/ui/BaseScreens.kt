@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ameer.uyirmesh.ui

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.base.Reports
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.CryptoException
import com.ameer.uyirmesh.core.FixChoice
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.data.RescuerRecord
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.mesh.PublicHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ---------------- setup and login ---------------- */

@Composable
fun BaseSetupScreen(nav: Nav) {
    val ctx = LocalContext.current
    var fileText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                if (text == null || text.length > 100_000) {
                    error = L.t("not_key_file")
                } else {
                    fileText = text
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    error = ""
                }
            } catch (e: Exception) {
                error = e.message ?: L.t("not_key_file")
            }
        }
    }
    Scaffold(topBar = { ScreenBar(tr("role_base"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            Text(tr("base_setup_body"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SecondaryButton(if (fileText == null) tr("choose_key_file") else "✓ $fileName", Ic.Doc) { picker.launch(arrayOf("*/*")) }
            Field(tr("key_password"), password, { password = it.take(64) }, password = true)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (busy) tr("checking") else tr("import_key"), Ic.Lock, enabled = !busy && fileText != null && password.isNotEmpty()) {
                busy = true
                error = ""
                val text = fileText ?: return@PrimaryButton
                scope.launch {
                    val msg = withContext(Dispatchers.Default) {
                        try {
                            Graph.base.importKeyFile(text, password.toCharArray())
                            null
                        } catch (e: CryptoException) {
                            e.message
                        } catch (e: Exception) {
                            L.t("not_key_file")
                        }
                    }
                    busy = false
                    if (msg == null) {
                        Graph.prefs.update { it.copy(role = Roles.BASE) }
                        Graph.mesh.onRoleChanged()
                        MeshService.start(ctx, fromForeground = true)
                        nav.replace("home")
                    } else {
                        error = msg
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoCard { Text(tr("base_key_note").replace("{f}", Graph.pinned.sigFingerprint), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun BaseGate(nav: Nav) {
    val unlocked by Graph.base.unlocked.collectAsStateWithLifecycle()
    if (!Graph.base.hasKeys()) {
        BaseSetupScreen(nav)
        return
    }
    if (!unlocked) BaseLogin(nav) else BaseHome(nav)
}

@Composable
private fun BaseLogin(nav: Nav) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { ScreenBar(tr("role_base"), null) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).imePadding()) {
            Text(tr("base_login_body"), style = MaterialTheme.typography.bodyMedium)
            Field(tr("base_password"), password, { password = it; error = false }, password = true)
            if (error) Text(tr("wrong_password"), color = MaterialTheme.colorScheme.error)
            PrimaryButton(if (busy) tr("checking") else tr("log_in"), Ic.Lock, enabled = !busy) {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { Graph.base.login(password.toCharArray()) }
                    busy = false
                    error = !ok
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(tr("base_runs_locked"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            SecondaryButton(tr("public_screens"), Ic.Person) { nav.go("public") }
        }
    }
}

/* ---------------- base home ---------------- */

@Composable
private fun BaseHome(nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val peers by Graph.peerCount.collectAsStateWithLifecycle()
    val open = cases.count { it.isOpen }
    Scaffold(
        topBar = {
            ScreenBar(Graph.prefs.now.baseName, null) {
                IconButton(onClick = { nav.go("web") }) { Icon(Ic.Computer, tr("web_dashboard")) }
                IconButton(onClick = { Graph.base.lock() }) { Icon(Ic.Lock, tr("lock")) }
                IconButton(onClick = { nav.go("settings") }) { Icon(Ic.Settings, tr("settings")) }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Ic.Sos, null) }, label = { Text(tr("cases") + if (open > 0) " ($open)" else "") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Ic.Radar, null) }, label = { Text(tr("map")) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Ic.Group, null) }, label = { Text(tr("teams")) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Ic.Network, null) }, label = { Text(tr("network")) })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Ic.Dashboard, null) }, label = { Text(tr("more")) })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                tr("base_status").replace("{p}", peers.toString()).replace("{o}", open.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = if (peers > 0) Palette.Green else Palette.Orange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when (tab) {
                0 -> CaseList(nav)
                1 -> BaseMap(nav)
                2 -> TeamsTab(nav)
                3 -> TopologyTab(nav)
                else -> MoreTab(nav)
            }
        }
    }
}

@Composable
private fun CaseList(nav: Nav) {
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) }
    val list = when (filter) {
        0 -> cases.filter { it.isOpen }.sortedByDescending { it.score }
        1 -> cases.filter { it.status == CaseStatus.RESCUED }.sortedByDescending { it.rescuedAt }
        else -> cases.sortedByDescending { it.lastAt }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text(tr("open_cases")) })
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text(tr("rescued_cases")) })
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text(tr("all_cases")) })
            }
            CaseSummaryBar(cases)
            AssignAllButton()
        }
        if (list.isEmpty()) item { EmptyState(Ic.Sos, tr("no_cases")) }
        items(list, key = { it.caseId }) { c -> CaseCard(c) { nav.go("case/${c.caseId}") } }
    }
}

@Composable
private fun CaseSummaryBar(cases: List<CaseItem>) {
    val open = cases.filter { it.isOpen }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (label in listOf("Critical", "High", "Medium", "Low")) {
            val n = open.count { it.label == label }
            Column(
                Modifier.weight(1f).background(Palette.priority(label).copy(alpha = 0.14f)).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(n.toString(), fontWeight = FontWeight.Black, fontSize = 20.sp, color = Palette.priority(label))
                Text(priorityName(label), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun priorityName(label: String): String = when (label) {
    "Critical" -> tr("p_critical")
    "High" -> tr("p_high")
    "Medium" -> tr("p_medium")
    else -> tr("p_low")
}

@Composable
private fun CaseCard(c: CaseItem, onClick: () -> Unit) {
    InfoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("${priorityName(c.label)} ${c.score}", Palette.priority(c.label))
            Spacer(Modifier.width(6.dp))
            Chip(caseStatusLabel(c.status), Palette.Blue)
            if (c.drill) {
                Spacer(Modifier.width(6.dp))
                Chip(tr("drill"), Palette.Grey)
            }
            if (c.duplicateOf.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Chip(tr("duplicate"), Palette.Purple)
            }
            Spacer(Modifier.weight(1f))
            Text(agoText(c.firstAt), style = MaterialTheme.typography.bodySmall)
        }
        Text(Graph.base.summary(c), modifier = Modifier.padding(top = 6.dp), maxLines = 3)
        Text(
            listOfNotNull(
                c.payload.name.takeIf { it.isNotBlank() },
                if (c.team.isNotBlank()) "${tr("team")} ${c.team}" + if (c.etaMin > 0) " · ETA ${c.etaMin} min" else "" else null,
                "${c.hops} ${tr("hops")}",
                if (c.media.isNotEmpty()) "🎙/📷 ${c.media.size}" else null,
                if (c.lat == null) tr("no_gps") else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun caseStatusLabel(s: String): String = when (s) {
    CaseStatus.NEW -> tr("cs_new")
    CaseStatus.ASSIGNED -> tr("cs_assigned")
    CaseStatus.ON_THE_WAY -> tr("cs_on_way")
    CaseStatus.RESCUED -> tr("cs_rescued")
    CaseStatus.CANCELLED -> tr("cs_cancelled")
    CaseStatus.UNABLE -> tr("cs_unable")
    else -> s
}

@Composable
private fun BaseMap(nav: Nav) {
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val rescuers by Graph.store.rescuers.flow.collectAsStateWithLifecycle()
    val shelters by Graph.store.shelters.flow.collectAsStateWithLifecycle()
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val loc by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var showHeat by remember { mutableStateOf(true) }
    val baseLoc = loc?.let { LatLon(it.latitude, it.longitude) }
    val open = cases.filter { it.isOpen && it.lat != null && it.lon != null }
    val hotspots = remember(cases) { Graph.base.hotspots() }
    val now = System.currentTimeMillis()
    val markers = buildList {
        if (baseLoc != null) add(MapMarker("base", baseLoc.lat, baseLoc.lon, Palette.Red, L.t("base_short"), 3))
        for (c in open) add(MapMarker("c" + c.caseId, c.lat!!, c.lon!!, Palette.priority(c.label), "${c.score}", 0))
        for (r in rescuers) if (r.lat != null && r.lon != null && now - r.lastSeen < 30 * 60_000) add(MapMarker("r" + r.rescuerId, r.lat, r.lon, Palette.Blue, r.team, 1))
        for (s in shelters) if (s.lat != null && s.lon != null) add(MapMarker("s" + s.id, s.lat, s.lon, Palette.Teal, s.name, 3))
    }
    val zones = alerts.filter { (it.kind == AlertKind.DANGER || it.kind == AlertKind.EVACUATE) && it.isValid(now) && it.lat != null && it.lon != null }
        .map { MapZone(it.lat!!, it.lon!!, it.radiusM.coerceAtLeast(50).toDouble(), Palette.Red, it.title) } +
        hotspots.map { MapZone(it.center.lat, it.center.lon, it.radiusM + 40, Palette.Orange, "") }
    // Where each team member went in the last 30 minutes.
    val rescuerTrails = rescuers.filter { it.lat != null && now - it.lastSeen < 30 * 60_000 }.map { r ->
        MapTrail(Graph.base.fixesOf(r).filter { now - it.at < 30 * 60_000 && Geo.isValid(it.lat, it.lon) }.map { LatLon(it.lat, it.lon) }, Palette.Blue.copy(alpha = 0.55f))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        RadarMap(baseLoc, markers, zones = zones, trails = rescuerTrails, heat = if (showHeat) open.map { LatLon(it.lat!!, it.lon!!) } else emptyList(), selected = selected, onTap = { selected = it })
        MapLegend(listOf(Palette.Red to L.t("p_critical"), Palette.Orange to L.t("p_high"), Palette.Amber to L.t("p_medium"), Palette.Green to L.t("p_low"), Palette.Blue to L.t("teams"), Palette.Teal to L.t("shelters")))
        SwitchRow(tr("heatmap"), tr("heatmap_sub"), showHeat) { showHeat = it }
        if (baseLoc == null) Text(tr("waiting_gps"), color = Palette.Orange)
        if (hotspots.isNotEmpty()) {
            SectionTitle(tr("hotspots"))
            for (h in hotspots) InfoCard { Text(tr("hotspot_text").replace("{n}", h.count.toString()).replace("{r}", Geo.formatDistance(h.radiusM))) }
        }
        selected?.let { id ->
            if (id.startsWith("c")) cases.firstOrNull { "c" + it.caseId == id }?.let { c -> CaseCard(c) { nav.go("case/${c.caseId}") } }
            if (id.startsWith("r")) rescuers.firstOrNull { "r" + it.rescuerId == id }?.let { r -> RescuerMapCard(nav, r, cases) }
            if (id.startsWith("s")) shelters.firstOrNull { "s" + it.id == id }?.let { s -> InfoCard { Text("${s.name}: ${s.occupancy}/${s.capacity}") } }
        }
    }
}

/** A rescuer tapped on the base map: when last heard from, and how they are doing on their team's open cases. */
@Composable
private fun RescuerMapCard(nav: Nav, r: RescuerRecord, cases: List<CaseItem>) {
    InfoCard {
        Text("${r.name} · ${tr("team")} ${r.team}", fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(
                "${tr("seen")} ${agoText(r.lastSeen)}",
                if (r.lat != null) tr("position_age").replace("{a}", agoText(r.positionAt)) else null,
                if (r.battery >= 0) "${tr("battery")} ${r.battery}%" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        val onTheWay = cases.filter { it.team == r.team && (it.status == CaseStatus.ASSIGNED || it.status == CaseStatus.ON_THE_WAY) }
        for (c in onTheWay) {
            val a = Graph.base.approaches(c).firstOrNull { it.rescuer.rescuerId == r.rescuerId }
            if (a != null) {
                val (label, color) = trendLabel(a.approach)
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go("case/${c.caseId}") }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${tr("case")} ${c.caseId.take(6)} · ${Geo.formatDistance(a.approach.distanceM)}", Modifier.weight(1f))
                    Chip(label, color)
                }
            }
        }
        if (r.lat != null && r.lon != null) MapButtons(r.lat, r.lon, r.name, note = false)
    }
}

@Composable
private fun TeamsTab(nav: Nav) {
    val rescuers by Graph.store.rescuers.flow.collectAsStateWithLifecycle()
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            PrimaryButton(tr("issue_rescuer"), Ic.PersonAdd) { nav.go("issue") }
            SwitchRow(tr("auto_assign"), tr("auto_assign_sub"), s.autoAssign) { v ->
                Graph.prefs.update { it.copy(autoAssign = v) }
                if (v) Graph.base.autoAssign()
            }
            Stepper(tr("team_speed"), s.speedKmh, 2, 80, Ic.Run) { v -> Graph.prefs.update { it.copy(speedKmh = v) } }
            LearnedEtaNote()
            MenuTile(Ic.Chat, tr("team_chat"), tr("team_chat_base_sub"), Palette.Blue) { nav.go("team_chat") }
        }
        val teams = rescuers.groupBy { it.team }
        if (teams.isEmpty()) item { EmptyState(Ic.Group, tr("no_teams_yet")) }
        for ((team, members) in teams) {
            item(key = "team$team") {
                val active = cases.count { it.team == team && (it.status == CaseStatus.ASSIGNED || it.status == CaseStatus.ON_THE_WAY) }
                SectionTitle("${tr("team")} $team · ${tr("active_tasks")}: $active")
            }
            items(members, key = { it.rescuerId }) { r ->
                val seenRecently = now - r.lastSeen < 10 * 60_000
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(seenRecently, warn = r.lastSeen > 0)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text(
                                (if (r.lastSeen > 0) "${tr("seen")} ${agoText(r.lastSeen)}" else tr("not_seen_yet")) +
                                    (if (r.battery >= 0) " · ${r.battery}%" else "") +
                                    (if (r.lat != null) " · " + tr("position_age").replace("{a}", agoText(r.positionAt)) else "") +
                                    " · ${tr("valid_until")} ${timeText(r.expiresAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyTab(nav: Nav) {
    val nodes by Graph.store.nodes.flow.collectAsStateWithLifecycle()
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val live = nodes.filter { now - it.lastSeen < 15 * 60_000 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(tr("topology_body"), style = MaterialTheme.typography.bodySmall)
        TopologyGraph(live.map { it.deviceId to it.role }, live.flatMap { n -> n.neighbours.map { n.deviceId to it } }, Graph.prefs.now.deviceId)
        Text(tr("phones_reporting").replace("{n}", live.size.toString()), style = MaterialTheme.typography.bodySmall)
        SectionTitle(tr("packet_paths"))
        for (c in cases.sortedByDescending { it.firstAt }.take(8)) {
            InfoCard {
                Text("${c.caseId.take(6)} · ${Graph.base.summary(c).take(40)}", fontWeight = FontWeight.SemiBold)
                Text(
                    (listOf("📱 " + c.victim.take(6)) + c.path.map { "↪ " + it.take(6) } + listOf("🏠 ${tr("base_short")}")).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionTitle(tr("packet_counters"))
        InfoCard { for ((k, v) in stats.metrics) KeyValue(k, v.toString()) }
        MenuTile(Ic.History, tr("event_log"), tr("event_log_sub")) { nav.go("log") }
    }
}

/** Simple circular layout of the phones that reported their neighbours in heartbeats. */
@Composable
fun TopologyGraph(nodes: List<Pair<String, String>>, edges: List<Pair<String, String>>, me: String) {
    val all = (nodes.map { it.first } + edges.flatMap { listOf(it.first, it.second) } + me).distinct()
    val roles = nodes.toMap()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.2f).padding(8.dp)) {
        if (all.isEmpty()) return@Canvas
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.8f
        val pos = HashMap<String, Offset>()
        all.forEachIndexed { i, id ->
            val angle = 2 * Math.PI * i / all.size - Math.PI / 2
            pos[id] = if (id == me && all.size > 1) c else Offset(c.x + (r * Math.cos(angle)).toFloat(), c.y + (r * Math.sin(angle)).toFloat())
        }
        for ((a, b) in edges) {
            val pa = pos[a] ?: continue
            val pb = pos[b] ?: continue
            drawLine(ink.copy(alpha = 0.5f), pa, pb, strokeWidth = 3f)
        }
        for (id in all) {
            val p = pos[id] ?: continue
            val color = when (if (id == me) Roles.BASE else roles[id]) {
                Roles.BASE -> Palette.Red
                Roles.RESCUER -> Palette.Blue
                else -> Palette.Green
            }
            drawCircle(color, radius = 16f, center = p)
            drawCircle(Color.White, radius = 16f, center = p, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
    }
}

@Composable
private fun MoreTab(nav: Nav) {
    val ctx = LocalContext.current
    val volunteers by Graph.store.volunteers.flow.collectAsStateWithLifecycle()
    val reports by Graph.store.reports.flow.collectAsStateWithLifecycle()
    val missing by Graph.store.missing.flow.collectAsStateWithLifecycle()
    val safe by Graph.store.safe.flow.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var secureExport by remember { mutableStateOf(false) }
    if (secureExport) SecureExportDialog { secureExport = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        MenuTile(Ic.Alert, tr("send_alert"), tr("send_alert_sub"), Palette.Orange) { nav.go("base_alert") }
        MenuTile(Ic.HowToReg, tr("polls_rollcall"), tr("polls_rollcall_sub"), Palette.Purple) { nav.go("base_polls") }
        MenuTile(Ic.Hazard, tr("mark_hazard"), tr("mark_hazard_sub"), Palette.Red) { nav.go("hazard") }
        MenuTile(Ic.Block, tr("rescuer_ids"), tr("rescuer_ids_sub"), Palette.Grey) { nav.go("base_ids") }
        MenuTile(Ic.Shelter, tr("shelters_supplies"), tr("shelters_supplies_sub"), Palette.Teal) { nav.go("base_shelters") }
        MenuTile(Ic.Computer, tr("web_dashboard"), tr("web_dashboard_sub"), Palette.Blue) { nav.go("web") }
        MenuTile(Ic.Doc, tr("export_csv"), tr("export_csv_sub"), Palette.Green) {
            val f = Reports.writeCsv(ctx)
            if (f != null) shareFile(ctx, f, "text/csv", "UyirMesh cases")
        }
        MenuTile(Ic.Pdf, tr("export_pdf"), tr("export_pdf_sub"), Palette.Red) {
            val f = Reports.writePdf(ctx)
            if (f != null) shareFile(ctx, f, "application/pdf", "UyirMesh report")
        }
        MenuTile(Ic.Lock, tr("secure_export"), tr("secure_export_sub"), Palette.Teal) { secureExport = true }
        MenuTile(Ic.Chart, tr("statistics"), tr("statistics_sub"), Palette.Purple) { nav.go("web") }
        SectionTitle(tr("people_search"))
        Field(tr("search_name"), query, { query = it })
        if (query.length >= 2) {
            val q = query.lowercase()
            val hits = safe.filter { it.name.lowercase().contains(q) }.map { "${it.name}: ${tr("st_safe")} ${it.place.substringAfterLast(':')} (${timeText(it.at)})" } +
                Graph.store.checkins.value.filter { it.name.lowercase().contains(q) }.map { ci -> "${ci.name}: ${tr("in_shelter")} ${Graph.store.shelters.value.firstOrNull { it.id == ci.shelterId }?.name ?: ""}" } +
                Graph.store.cases.value.filter { it.payload.name.lowercase().contains(q) }.map { "${it.payload.name}: SOS ${caseStatusLabel(it.status)}" } +
                missing.filter { it.person.lowercase().contains(q) }.map { "${it.person}: ${if (it.found) tr("found") else tr("missing")}" }
            if (hits.isEmpty()) Text(tr("find_none").replace("{q}", query))
            for (h in hits.take(20)) Text("• $h")
        }
        SectionTitle(tr("volunteers") + " (${volunteers.size})")
        for (v in volunteers.sortedByDescending { it.at }) {
            InfoCard {
                Text("${v.name} · ${v.phone}", fontWeight = FontWeight.Bold)
                Text(v.skills.joinToString { it.lowercase() } + if (v.note.isNotBlank()) " · ${v.note}" else "", style = MaterialTheme.typography.bodySmall)
                Text(distanceFromMe(v.lat, v.lon).removePrefix(" · ") + " · ${timeText(v.at)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle(tr("citizen_reports") + " (${reports.size})")
        for (r in reports.sortedByDescending { it.at }.take(30)) {
            InfoCard {
                Text("${reportLabel(r.kind)} · ${r.name}", fontWeight = FontWeight.Bold)
                if (r.note.isNotBlank()) Text(r.note)
                Text(distanceFromMe(r.lat, r.lon).removePrefix(" · ") + " · ${timeText(r.at)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle(tr("base_settings"))
        val s by Graph.prefs.settings.collectAsStateWithLifecycle()
        var baseName by remember { mutableStateOf(s.baseName) }
        Field(tr("base_name"), baseName, { baseName = it.take(40) })
        TonalButton(tr("save"), Ic.Check) { Graph.prefs.update { it.copy(baseName = baseName.trim().ifBlank { "Rescue Base" }) } }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(tr("public_screens"), Ic.Person) { nav.go("public") }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(tr("leave_base_mode"), Ic.Logout) {
            Graph.prefs.update { it.copy(role = Roles.PUBLIC) }
            Graph.base.lock()
            Graph.mesh.onRoleChanged()
            nav.replace("home")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- case detail ---------------- */

@Composable
fun CaseScreen(nav: Nav, caseId: String) {
    val ctx = LocalContext.current
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val rescuers by Graph.store.rescuers.flow.collectAsStateWithLifecycle()
    val now = rememberNow()
    val c = cases.firstOrNull { it.caseId == caseId }
    var message by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var handOver by remember { mutableStateOf(false) }
    if (handOver) HandOverDialog(caseId) { handOver = false }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Scaffold(topBar = { ScreenBar(tr("case") + " " + caseId.take(6), { nav.back() }) }) { pad ->
        if (c == null) {
            Column(Modifier.padding(pad)) { EmptyState(Ic.Sos, tr("case_missing")) }
            return@Scaffold
        }
        val p = c.payload
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip("${priorityName(c.label)} ${c.score}", Palette.priority(c.label))
                Spacer(Modifier.width(6.dp))
                Chip(caseStatusLabel(c.status), Palette.Blue)
                if (c.drill) {
                    Spacer(Modifier.width(6.dp))
                    Chip(tr("drill"), Palette.Grey)
                }
            }
            Text(Graph.base.summary(c), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            if (c.handledBy.isNotBlank()) {
                InfoCard(color = Palette.Purple.copy(alpha = 0.12f)) { Text(tr("handled_by_base").replace("{b}", c.handledBy), fontWeight = FontWeight.SemiBold) }
            }
            if (c.outcome.isNotBlank()) {
                InfoCard(color = Palette.Green.copy(alpha = 0.12f)) { Text(tr("rescue_outcome") + ": " + c.outcome, fontWeight = FontWeight.SemiBold) }
            }
            if (c.flags.isNotEmpty()) {
                InfoCard(color = Palette.Orange.copy(alpha = 0.14f)) {
                    Text(tr("check_this_sos"), fontWeight = FontWeight.Bold, color = Palette.Orange)
                    for (f in c.flags) Text("• " + flagText(f), style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionTitle(tr("why_priority"))
            InfoCard {
                Text(tr("ai_model_note").replace("{m}", c.modelScore.toString()), style = MaterialTheme.typography.bodySmall)
                for (r in c.reasons) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(r.label, Modifier.weight(1f))
                        Text("+${r.points}", fontWeight = FontWeight.Bold, color = Palette.Red)
                    }
                }
                if (c.proba.size == 4) {
                    Text(
                        "P(Low/Med/High/Crit) = " + c.proba.joinToString(" / ") { "%.2f".format(java.util.Locale.US, it) },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            SectionTitle(tr("victim"))
            InfoCard {
                if (p.silent) Text(tr("approach_quietly"), color = Palette.Red, fontWeight = FontWeight.Bold)
                if (p.forOther) {
                    KeyValue(tr("for_whom"), p.otherName.ifBlank { "-" } + if (p.otherPhone.isNotBlank()) " · ${p.otherPhone}" else "", Palette.Purple)
                }
                if (p.animals > 0) KeyValue(tr("animals"), p.animals.toString())
                KeyValue(tr("name"), p.name.ifBlank { "-" })
                KeyValue(tr("phone"), p.phone.ifBlank { "-" })
                KeyValue(tr("blood_group"), p.blood.ifBlank { "-" })
                KeyValue(tr("medical"), p.medical.ifBlank { "-" }, if (p.medical.isNotBlank()) Palette.Red else MaterialTheme.colorScheme.onSurface)
                KeyValue(tr("battery"), if (c.battery >= 0) "${c.battery}%${if (c.lowBattery) " (${tr("last_position")})" else ""}" else "?")
                KeyValue(tr("trigger"), p.trigger.lowercase())
                KeyValue(tr("location"), if (c.lat != null && c.lon != null) "%.5f, %.5f ±%dm".format(java.util.Locale.US, c.lat, c.lon, (c.accuracy ?: 0.0).toInt()) else tr("no_gps"))
                val acc = c.accuracy
                if (c.lat != null && acc != null && acc > FixChoice.PRECISE_M) {
                    Text(tr("victim_loc_rough").replace("{a}", accuracyText(acc)), color = Palette.Orange, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                KeyValue(tr("location_age"), c.locAt?.let { agoText(it) } ?: "-")
                KeyValue(tr("route"), (listOf(tr("victim")) + c.path.map { it.take(6) } + listOf(tr("base_short"))).joinToString(" → ") + " (${c.hops} ${tr("hops")})")
                KeyValue(tr("times_sent"), (c.repeats + 1).toString())
                if (c.duplicateOf.isNotBlank()) KeyValue(tr("duplicate_of"), c.duplicateOf.take(6), Palette.Purple)
            }
            if (c.media.isNotEmpty()) {
                SectionTitle(tr("voice_and_photos"))
                for (m in c.media) {
                    if (m.isAudio) {
                        TonalButton("${tr("play_voice")} (${m.durationMs / 1000}s) · ${timeText(m.at)}", Ic.Play) {
                            val bytes = Graph.base.mediaBytes(m.file) ?: return@TonalButton
                            try {
                                player?.release()
                                val f = File(ctx.cacheDir, "play.amr")
                                f.writeBytes(bytes)
                                player = MediaPlayer().apply {
                                    setDataSource(f.absolutePath)
                                    prepare()
                                    start()
                                }
                            } catch (e: Exception) {
                                Graph.note("play voice", e)
                            }
                        }
                    } else {
                        val bmp = remember(m.file) {
                            Graph.base.mediaBytes(m.file)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                        }
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
                    }
                }
            }
            val tracking = c.team.isNotBlank() && (c.status == CaseStatus.ASSIGNED || c.status == CaseStatus.ON_THE_WAY)
            val approaches = remember(c, rescuers) { if (tracking) Graph.base.approaches(c) else emptyList() }
            if (c.lat != null && c.lon != null) {
                SectionTitle(tr("map"))
                val baseLoc = Graph.loc.lastLatLon
                RadarMap(
                    baseLoc ?: LatLon(c.lat, c.lon),
                    listOfNotNull(
                        baseLoc?.let { MapMarker("base", it.lat, it.lon, Palette.Red, L.t("base_short"), 3) },
                        MapMarker("c", c.lat, c.lon, Palette.priority(c.label), c.label, 0),
                    ) + approaches.map { a ->
                        val f = a.fixes.last()
                        MapMarker("r" + a.rescuer.rescuerId, f.lat, f.lon, Palette.Blue, a.rescuer.name, 1)
                    },
                    trails = listOf(MapTrail(c.track.map { LatLon(it.lat, it.lon) }, Palette.priority(c.label), dashed = true)) +
                        approaches.map { a -> MapTrail(a.fixes.map { LatLon(it.lat, it.lon) }, Palette.Blue) },
                )
                MapLegend(
                    listOfNotNull(
                        Palette.Red to L.t("base_short"),
                        Palette.priority(c.label) to L.t("victim"),
                        if (approaches.isNotEmpty()) Palette.Blue to L.t("rescuer") else null,
                    ),
                )
                MapButtons(c.lat, c.lon, "SOS ${c.caseId.take(6)}", accuracyM = c.accuracy)
            }
            if (tracking) TrackingSection(c.team, c.lat != null && c.lon != null, approaches, now, c.accuracy)
            SectionTitle(tr("assign_team"))
            val teams = Graph.base.teams()
            if (teams.isEmpty()) Text(tr("no_teams_yet"), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in teams) {
                    val eta = Graph.base.etaFor(t, c)
                    FilterChip(selected = c.team == t, onClick = { Graph.base.assign(c.caseId, t) }, label = { Text(t + if (eta > 0) " · $eta min" else "") })
                }
            }
            SectionTitle(tr("set_status"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (st in listOf(CaseStatus.ON_THE_WAY, CaseStatus.RESCUED, CaseStatus.NEW)) {
                    TonalButton(caseStatusLabel(st), enabled = c.status != st) { Graph.base.setStatus(c.caseId, st) }
                }
                TonalButton(tr("hand_over"), Ic.Swap) { handOver = true }
            }
            SectionTitle(tr("message_victim"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(tr("message"), message, { message = it.take(300) }, Modifier.weight(1f))
                IconButton(onClick = {
                    if (message.isNotBlank()) {
                        Graph.base.messageVictim(c.caseId, message.trim())
                        message = ""
                    }
                }) { Icon(Ic.Send, tr("send")) }
            }
            Text(tr("message_victim_sub"), style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("case_timeline"))
            for (e in c.events.reversed()) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(timeText(e.at), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                    Text(e.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- issue rescuer ID ---------------- */

@Composable
fun IssueRescuerScreen(nav: Nav) {
    var name by remember { mutableStateOf("") }
    var team by remember { mutableStateOf(Graph.base.teams().firstOrNull() ?: "ALPHA") }
    var days by remember { mutableIntStateOf(30) }
    var pin by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { ScreenBar(tr("issue_rescuer"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            val code = qr
            if (code == null) {
                Text(tr("issue_body"), style = MaterialTheme.typography.bodyMedium)
                Field(tr("rescuer_name"), name, { name = it.take(40) })
                Field(tr("team_name"), team, { team = it.uppercase().take(20) })
                Stepper(tr("valid_days"), days, 1, 365, Ic.Time) { days = it }
                Field(tr("pin_4_8"), pin, { pin = it.filter { c -> c.isDigit() }.take(8) }, number = true, password = true)
                Text(tr("pin_note"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) tr("creating") else tr("create_id"), Ic.Qr, enabled = !busy && name.isNotBlank() && team.isNotBlank() && pin.length >= 4) {
                    busy = true
                    scope.launch {
                        val q = withContext(Dispatchers.Default) {
                            try {
                                Graph.base.issueRescuer(name.trim(), team.trim(), days, pin.toCharArray())
                            } catch (e: Exception) {
                                Graph.note("issue rescuer", e)
                                null
                            }
                        }
                        busy = false
                        qr = q
                    }
                }
            } else {
                Text(tr("issued_for").replace("{n}", name).replace("{t}", team.uppercase()), fontWeight = FontWeight.Bold)
                Text(tr("scan_with_rescuer_phone"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                QrImage(code, Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(6.dp))
                Text(tr("tell_pin_privately"), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp), color = Palette.Red)
                SecondaryButton(tr("issue_another"), Ic.PersonAdd) {
                    qr = null
                    name = ""
                    pin = ""
                }
            }
        }
    }
}

/* ---------------- alerts from the base ---------------- */

@Composable
fun BaseAlertScreen(nav: Nav) {
    var kind by remember { mutableStateOf(AlertKind.EVACUATE) }
    var severity by remember { mutableIntStateOf(3) }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(500) }
    var validHours by remember { mutableIntStateOf(6) }
    var useLocation by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf(false) }
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    Scaffold(topBar = { ScreenBar(tr("send_alert"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            Text(tr("alert_body"), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in listOf(AlertKind.EVACUATE, AlertKind.DANGER, AlertKind.OFFICIAL, AlertKind.INFO, AlertKind.MISSING, AlertKind.ALL_CLEAR)) {
                    FilterChip(selected = kind == k, onClick = { kind = k; severity = if (k == AlertKind.EVACUATE || k == AlertKind.DANGER) 3 else 1 }, label = { Text(PublicHub.alertTitle(k)) })
                }
            }
            Field(tr("alert_title"), title, { title = it.take(80) })
            Field(tr("message"), message, { message = it.take(500) }, singleLine = false, minLines = 3)
            Stepper(tr("severity"), severity, 1, 3, Ic.Sos) { severity = it }
            SwitchRow(tr("use_base_location"), tr("use_base_location_sub"), useLocation) { useLocation = it }
            if (useLocation) Stepper(tr("radius_m"), radius / 100, 1, 100, Ic.Location) { radius = it * 100 }
            Stepper(tr("valid_hours"), validHours, 0, 72, Ic.Time) { validHours = it }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (sent) tr("sent_send_another") else tr("sign_and_send"), Ic.Alert, color = Palette.Orange, enabled = title.isNotBlank()) {
                val loc = if (useLocation) Graph.loc.last else null
                sent = Graph.base.sendAlert(kind, severity, title.trim(), message.trim(), loc?.latitude, loc?.longitude, if (loc != null) radius else 0, validHours * 60)
            }
            SectionTitle(tr("sent_alerts"))
            for (a in alerts.filter { it.mine && it.kind != AlertKind.SHELTER }.sortedByDescending { it.at }) {
                InfoCard { Text("${PublicHub.alertTitle(a.kind)}: ${a.title} · ${timeText(a.at)}") }
            }
        }
    }
}

/* ---------------- shelters ---------------- */

@Composable
fun BaseSheltersScreen(nav: Nav) {
    val shelters by Graph.store.shelters.flow.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var capacity by remember { mutableIntStateOf(100) }
    var showQr by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { ScreenBar(tr("shelters_supplies"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("shelter_body"), style = MaterialTheme.typography.bodySmall)
                Field(tr("shelter_name"), name, { name = it.take(40) })
                Stepper(tr("capacity_x10"), capacity / 10, 1, 500, Ic.Group) { capacity = it * 10 }
                TonalButton(tr("add_shelter_here"), Ic.Add, enabled = name.isNotBlank()) {
                    val l = Graph.loc.last
                    Graph.base.addShelter(name.trim(), l?.latitude, l?.longitude, capacity)
                    name = ""
                }
            }
            items(shelters, key = { it.id }) { s ->
                var water by remember(s.id) { mutableIntStateOf(s.water) }
                var food by remember(s.id) { mutableIntStateOf(s.food) }
                var medicine by remember(s.id) { mutableIntStateOf(s.medicine) }
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Chip("${s.occupancy}/${s.capacity}", if (s.occupancy >= s.capacity) Palette.Red else Palette.Green)
                    }
                    Stepper(tr("water_l_x10"), water, 0, 9999, Ic.Water) { water = it }
                    Stepper(tr("food_packets_x10"), food, 0, 9999, Ic.Food) { food = it }
                    Stepper(tr("medicine_kits"), medicine, 0, 9999, Ic.Medicine) { medicine = it }
                    ButtonRow {
                        TonalButton(tr("save_announce"), Ic.Alert, Modifier.weight(1f)) { Graph.base.updateSupplies(s.id, water, food, medicine, s.capacity) }
                        TonalButton(tr("check_in_qr"), Ic.Qr, Modifier.weight(1f)) { showQr = if (showQr == s.id) null else s.id }
                    }
                    if (showQr == s.id) {
                        QrImage(Graph.base.shelterQr(s), Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp))
                        Text(tr("shelter_qr_sub"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/* ---------------- web dashboard ---------------- */

@Composable
fun WebDashboardScreen(nav: Nav) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val urls by Graph.base.serverUrls.collectAsStateWithLifecycle()
    Scaffold(topBar = { ScreenBar(tr("web_dashboard"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("web_body"), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(tr("web_on"), tr("web_on_sub"), s.webServer) { v ->
                Graph.prefs.update { it.copy(webServer = v) }
                Graph.base.webServerCheck()
            }
            if (s.webServer) {
                SectionTitle(tr("open_on_laptop"))
                if (urls.isEmpty()) Text(tr("no_network_yet"), color = Palette.Orange)
                for (u in urls) {
                    InfoCard(color = Palette.Blue.copy(alpha = 0.1f)) {
                        Text(u, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Blue)
                        Text("$u/app  ← ${tr("apk_download_page")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(tr("web_login_note"), style = MaterialTheme.typography.bodySmall)
            }
            SectionTitle(tr("how_to_connect"))
            Text(tr("web_steps"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
