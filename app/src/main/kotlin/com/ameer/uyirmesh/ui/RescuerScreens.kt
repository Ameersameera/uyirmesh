@file:OptIn(ExperimentalMaterial3Api::class)

package com.ameer.uyirmesh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.CryptoException
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.TaskState
import com.ameer.uyirmesh.core.Triage
import com.ameer.uyirmesh.data.TaskItem
import com.ameer.uyirmesh.data.TaskSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------------- login ---------------- */

@Composable
fun RescuerLoginScreen(nav: Nav) {
    Scaffold(topBar = { ScreenBar(tr("role_rescuer"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("rescuer_login_body"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(tr("scan_rescuer_qr"), Ic.QrScan) { nav.go("scan/rescuer") }
            Spacer(Modifier.height(16.dp))
            InfoCard { Text(tr("rescuer_security_note"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun RescuerPinEntry(nav: Nav, qr: String) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoCard {
        Text(tr("rescuer_qr_found"), fontWeight = FontWeight.Bold)
        Text(tr("enter_pin_body"), style = MaterialTheme.typography.bodySmall)
        Field(tr("pin"), pin, { pin = it.filter { c -> c.isDigit() }.take(8) }, number = true, password = true)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton(if (busy) tr("checking") else tr("log_in"), Ic.Lock, enabled = !busy && pin.length >= 4) {
            busy = true
            error = ""
            scope.launch {
                val msg = withContext(Dispatchers.Default) {
                    try {
                        Graph.rescuer.login(qr, pin.toCharArray())
                        null
                    } catch (e: CryptoException) {
                        L.t("wrong_pin")
                    } catch (e: Exception) {
                        e.message ?: L.t("rescuer_id_invalid")
                    }
                }
                busy = false
                if (msg == null) nav.replace("home") else error = msg
            }
        }
    }
}

/* ---------------- rescuer home ---------------- */

@Composable
fun RescuerGate(nav: Nav) {
    val locked by Graph.rescuer.locked.collectAsStateWithLifecycle()
    val bundle = Graph.rescuer.bundle()
    if (bundle == null) {
        LaunchedEffect(Unit) {
            Graph.prefs.update { it.copy(role = Roles.PUBLIC) }
            nav.replace("home")
        }
        return
    }
    if (locked) RescuerLockScreen(nav) else RescuerHome(nav)
}

@Composable
private fun RescuerLockScreen(nav: Nav) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val b = Graph.rescuer.bundle()
    Scaffold(topBar = { ScreenBar(tr("role_rescuer"), null) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp).imePadding()) {
            Icon(Ic.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 16.dp).align(Alignment.CenterHorizontally))
            Text(tr("rescuer_locked").replace("{n}", b?.cert?.name ?: ""), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            Field(tr("pin"), pin, { pin = it.filter { c -> c.isDigit() }.take(8); error = false }, number = true, password = true)
            if (error) Text(tr("wrong_pin"), color = MaterialTheme.colorScheme.error)
            PrimaryButton(tr("unlock"), Ic.Lock) { if (!Graph.rescuer.unlock(pin.toCharArray())) error = true }
            Spacer(Modifier.height(16.dp))
            Text(tr("rescuer_locked_note"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            SecondaryButton(tr("public_screens"), Ic.Person) { nav.go("public") }
        }
    }
}

@Composable
private fun RescuerHome(nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    val b = Graph.rescuer.bundle() ?: return
    val tasks by Graph.store.tasks.flow.collectAsStateWithLifecycle()
    val seen by Graph.store.seen.flow.collectAsStateWithLifecycle()
    val peers by Graph.peerCount.collectAsStateWithLifecycle()
    val open = tasks.count { it.state != TaskState.RESCUED && it.state != TaskState.UNABLE }
    Scaffold(
        topBar = {
            ScreenBar("${b.cert.name} · ${b.cert.team}", null) {
                IconButton(onClick = { nav.go("scan/victim") }) { Icon(Ic.QrScan, tr("scan_victim_qr")) }
                IconButton(onClick = { Graph.rescuer.lock() }) { Icon(Ic.Lock, tr("lock")) }
                IconButton(onClick = { nav.go("settings") }) { Icon(Ic.Settings, tr("settings")) }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Ic.Task, null) }, label = { Text(tr("tasks") + if (open > 0) " ($open)" else "") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Ic.Sos, null) }, label = { Text(tr("nearby_sos") + if (seen.any { !it.cancelled }) " (${seen.count { !it.cancelled }})" else "") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Ic.Radar, null) }, label = { Text(tr("map")) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Ic.Group, null) }, label = { Text(tr("team")) })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                tr("rescuer_status").replace("{p}", peers.toString()),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = if (peers > 0) Palette.Green else Palette.Orange,
            )
            when (tab) {
                0 -> TaskList(nav)
                1 -> SeenList(nav)
                2 -> RescuerMap(nav)
                else -> TeamTab(nav)
            }
        }
    }
}

@Composable
private fun TaskList(nav: Nav) {
    val tasks by Graph.store.tasks.flow.collectAsStateWithLifecycle()
    val loc by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    val route = remember(tasks, loc) { Graph.rescuer.routeOrder() }
    val done = tasks.filter { it.state == TaskState.RESCUED || it.state == TaskState.UNABLE }
    val noLoc = tasks.filter { it !in route && it !in done }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (tasks.isEmpty()) item { EmptyState(Ic.Task, tr("no_tasks")) }
        if (route.isNotEmpty()) item { SectionTitle(tr("best_order")) }
        items(route.size) { i -> TaskCard(nav, route[i], i + 1) }
        if (noLoc.isNotEmpty()) item { SectionTitle(tr("no_location_yet")) }
        items(noLoc, key = { "n" + it.caseId }) { TaskCard(nav, it, 0) }
        if (done.isNotEmpty()) item { SectionTitle(tr("finished")) }
        items(done, key = { "d" + it.caseId }) { TaskCard(nav, it, 0) }
    }
}

@Composable
private fun TaskCard(nav: Nav, t: TaskItem, order: Int) {
    val color = if (t.source == TaskSource.DIRECT) Palette.Purple else Palette.priority(t.label)
    InfoCard(onClick = { nav.go("task/${t.caseId}") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (order > 0) {
                Badge(order.toString(), Palette.Blue)
                Spacer(Modifier.width(8.dp))
            }
            Chip(if (t.source == TaskSource.DIRECT) tr("direct_sos") else "${t.label} ${t.score}", color)
            Spacer(Modifier.width(8.dp))
            Chip(taskStateLabel(t.state), Palette.Grey)
            Spacer(Modifier.weight(1f))
            Text(agoText(t.at), style = MaterialTheme.typography.bodySmall)
        }
        Text(t.summary, modifier = Modifier.padding(top = 6.dp), maxLines = 3)
        Text(distanceFromMe(t.lat, t.lon).removePrefix(" · ").ifBlank { tr("no_location_yet") }, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun taskStateLabel(s: String): String = when (s) {
    TaskState.ACCEPTED -> tr("ts_accepted")
    TaskState.ON_THE_WAY -> tr("ts_on_way")
    TaskState.RESCUED -> tr("ts_rescued")
    TaskState.UNABLE -> tr("ts_unable")
    else -> tr("ts_new")
}

@Composable
private fun SeenList(nav: Nav) {
    val seen by Graph.store.seen.flow.collectAsStateWithLifecycle()
    val tasks by Graph.store.tasks.flow.collectAsStateWithLifecycle()
    val me = Graph.loc.lastLatLon
    val list = seen.sortedBy { s -> if (me != null && s.lat != null && s.lon != null) Geo.distanceM(me, LatLon(s.lat, s.lon)) else Double.MAX_VALUE }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text(tr("nearby_sos_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (list.isEmpty()) item { EmptyState(Ic.Sos, tr("no_nearby_sos")) }
        items(list, key = { it.caseId }) { s ->
            val taken = tasks.any { it.caseId == s.caseId }
            InfoCard(color = if (s.cancelled) MaterialTheme.colorScheme.surfaceVariant else Palette.Red.copy(alpha = 0.08f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Triage.kindLabel(s.payload.kind), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (s.cancelled) Chip(tr("st_cancelled"), Palette.Grey)
                    Text(agoText(s.lastAt), style = MaterialTheme.typography.bodySmall)
                }
                Text("${s.payload.people} ${tr("people")}${if (s.payload.injured) " · " + tr("injured") else ""}${if (s.payload.children > 0) " · ${s.payload.children} ${tr("children")}" else ""}${if (s.payload.elderly > 0) " · ${s.payload.elderly} ${tr("elderly")}" else ""}")
                if (s.payload.note.isNotBlank()) Text("\"${s.payload.note}\"", style = MaterialTheme.typography.bodySmall)
                if (s.payload.medical.isNotBlank()) Text("⚕ ${s.payload.medical}", style = MaterialTheme.typography.bodySmall, color = Palette.Red)
                Text(distanceFromMe(s.lat, s.lon).removePrefix(" · ").ifBlank { tr("no_location_yet") } + " · ${s.hops} ${tr("hops")} · ${tr("battery")} ${if (s.battery >= 0) "${s.battery}%" else "?"}", style = MaterialTheme.typography.bodySmall)
                if (!s.cancelled) {
                    if (taken) Text(tr("already_task"), color = Palette.Green, style = MaterialTheme.typography.bodySmall)
                    else TonalButton(tr("take_this"), Ic.Run) { Graph.rescuer.acceptSeen(s) }
                }
            }
        }
    }
}

@Composable
private fun RescuerMap(nav: Nav) {
    val tasks by Graph.store.tasks.flow.collectAsStateWithLifecycle()
    val seen by Graph.store.seen.flow.collectAsStateWithLifecycle()
    val crumbs by Graph.store.crumbs.flow.collectAsStateWithLifecycle()
    val loc by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    val me = loc?.let { LatLon(it.latitude, it.longitude) }
    val route = remember(tasks, loc) { Graph.rescuer.routeOrder() }
    val markers = buildList {
        if (me != null) add(MapMarker("me", me.lat, me.lon, Palette.Blue, L.t("you"), 4))
        route.forEachIndexed { i, t -> add(MapMarker("t" + t.caseId, t.lat!!, t.lon!!, Palette.priority(t.label), "${i + 1}. ${t.label}", 0)) }
        for (s in seen) if (!s.cancelled && s.lat != null && s.lon != null && tasks.none { it.caseId == s.caseId }) add(MapMarker("s" + s.caseId, s.lat, s.lon, Palette.Purple, "SOS", 0))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        RadarMap(me, markers, trail = crumbs.takeLast(300).map { LatLon(it.lat, it.lon) }, selected = selected, onTap = { selected = it })
        MapLegend(listOf(Palette.Blue to L.t("you"), Palette.Red to L.t("tasks"), Palette.Purple to L.t("nearby_sos"), Palette.Blue to L.t("breadcrumb")))
        if (me == null) Text(tr("waiting_gps"), color = Palette.Orange)
        selected?.let { id ->
            if (id.startsWith("t")) tasks.firstOrNull { "t" + it.caseId == id }?.let { TaskCard(nav, it, 0) }
            if (id.startsWith("s")) seen.firstOrNull { "s" + it.caseId == id }?.let { s ->
                InfoCard {
                    Text("${Triage.kindLabel(s.payload.kind)} · ${s.payload.people} ${tr("people")}")
                    TonalButton(tr("take_this"), Ic.Run) { Graph.rescuer.acceptSeen(s) }
                }
            }
        }
        SectionTitle(tr("breadcrumb"))
        Text(tr("breadcrumb_sub").replace("{n}", crumbs.size.toString()), style = MaterialTheme.typography.bodySmall)
        TonalButton(tr("clear_trail"), Ic.Delete) { Graph.store.crumbs.clear() }
    }
}

@Composable
private fun TeamTab(nav: Nav) {
    val b = Graph.rescuer.bundle() ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        InfoCard {
            KeyValue(tr("name"), b.cert.name)
            KeyValue(tr("team"), b.cert.team)
            KeyValue(tr("rescuer_id"), b.cert.rescuerId.take(8))
            KeyValue(tr("valid_until"), timeText(b.cert.expiresAt), if (Graph.rescuer.isExpired) Palette.Red else MaterialTheme.colorScheme.onSurface)
            KeyValue(tr("signed_by"), Graph.pinned.sigFingerprint)
        }
        MenuTile(Ic.Chat, tr("team_chat"), tr("team_chat_sub"), Palette.Blue) { nav.go("team_chat") }
        MenuTile(Ic.QrScan, tr("scan_victim_qr"), tr("scan_victim_qr_sub"), Palette.Red) { nav.go("scan/victim") }
        MenuTile(Ic.Network, tr("network_status"), tr("network_status_sub"), Palette.Teal) { nav.go("network") }
        MenuTile(Ic.Healing, tr("first_aid"), tr("first_aid_sub"), Palette.Red) { nav.go("firstaid") }
        MenuTile(Ic.Person, tr("public_screens"), tr("public_screens_sub"), Palette.Green) { nav.go("public") }
        Spacer(Modifier.height(12.dp))
        SecondaryButton(tr("log_out"), Ic.Logout) {
            Graph.rescuer.logout()
            nav.replace("home")
        }
    }
}

/* ---------------- task detail ---------------- */

@Composable
fun TaskScreen(nav: Nav, caseId: String) {
    val tasks by Graph.store.tasks.flow.collectAsStateWithLifecycle()
    val loc by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    val t = tasks.firstOrNull { it.caseId == caseId }
    Scaffold(topBar = { ScreenBar(tr("task"), { nav.back() }) }) { pad ->
        if (t == null) {
            Column(Modifier.padding(pad)) { EmptyState(Ic.Task, tr("task_missing")) }
            return@Scaffold
        }
        val me = loc?.let { LatLon(it.latitude, it.longitude) }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (t.source == TaskSource.DIRECT) tr("direct_sos") else "${t.label} ${t.score}", Palette.priority(t.label))
                Spacer(Modifier.width(8.dp))
                Chip(taskStateLabel(t.state), Palette.Blue)
            }
            Text(t.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            if (t.medical.isNotBlank()) InfoCard(color = Palette.Red.copy(alpha = 0.1f)) { Text("⚕ ${t.medical}") }
            if (t.lat != null && t.lon != null) {
                val target = LatLon(t.lat, t.lon)
                if (me != null) {
                    val d = Geo.distanceM(me, target)
                    val bearing = Geo.bearingDeg(me, target)
                    InfoCard(color = Palette.Blue.copy(alpha = 0.1f)) {
                        Text("${Geo.formatDistance(d)} ${Geo.compassName(bearing)} (${bearing.toInt()}°)", fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("ETA ${Geo.etaMinutes(d, Graph.prefs.now.speedKmh.toDouble())} min @ ${Graph.prefs.now.speedKmh} km/h", style = MaterialTheme.typography.bodySmall)
                    }
                }
                RadarMap(me, listOfNotNull(
                    me?.let { MapMarker("me", it.lat, it.lon, Palette.Blue, L.t("you"), 4) },
                    MapMarker("t", target.lat, target.lon, Palette.priority(t.label), t.label, 0),
                ))
                Text("GPS %.5f, %.5f".format(java.util.Locale.US, t.lat, t.lon), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(tr("no_location_yet"), color = Palette.Orange)
            }
            SectionTitle(tr("update_status"))
            when (t.state) {
                TaskItem.NEW -> PrimaryButton(tr("accept"), Ic.Check) { Graph.rescuer.setTaskState(t.caseId, TaskState.ACCEPTED) }
                TaskState.ACCEPTED -> PrimaryButton(tr("on_my_way"), Ic.Run, color = Palette.Orange) { Graph.rescuer.setTaskState(t.caseId, TaskState.ON_THE_WAY) }
                TaskState.ON_THE_WAY -> PrimaryButton(tr("rescued"), Ic.CheckCircle, color = Palette.Green) { Graph.rescuer.setTaskState(t.caseId, TaskState.RESCUED) }
                else -> Text(taskStateLabel(t.state), fontWeight = FontWeight.Bold)
            }
            if (t.state != TaskState.RESCUED && t.state != TaskState.UNABLE) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("unable"), Ic.Cancel) { Graph.rescuer.setTaskState(t.caseId, TaskState.UNABLE) }
            }
            Text(tr("status_goes_to"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/* ---------------- team chat ---------------- */

@Composable
fun TeamChatScreen(nav: Nav) {
    val msgs by Graph.store.teamChat.flow.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val isBase = Graph.prefs.now.role == Roles.BASE
    val teams = if (isBase) Graph.base.teams() else emptyList()
    var team by remember { mutableStateOf(teams.firstOrNull() ?: "") }
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1) }
    Scaffold(topBar = { ScreenBar(tr("team_chat"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Text(tr("team_chat_note"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isBase) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    for (tm in teams) TonalButton(tm, modifier = Modifier.padding(end = 6.dp), enabled = tm != team) { team = tm }
                }
                if (teams.isEmpty()) Text(tr("no_teams_yet"), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp)) {
                if (msgs.isEmpty()) item { EmptyState(Ic.Chat, tr("no_messages")) }
                items(msgs, key = { it.id }) { m -> Bubble(m.name, m.text, m.at, m.mine) }
            }
            MessageInput(text, { text = it }) {
                if (isBase) {
                    if (team.isNotBlank()) Graph.base.sendTeamChat(team, text)
                } else {
                    Graph.rescuer.sendTeamChat(text)
                }
                text = ""
            }
        }
    }
}
