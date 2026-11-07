@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ameer.uyirmesh.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AckStatus
import com.ameer.uyirmesh.core.EmergencyKind
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Needs
import com.ameer.uyirmesh.core.ProfileCard
import com.ameer.uyirmesh.core.ReportKind
import com.ameer.uyirmesh.core.Skills
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.data.MySos
import com.ameer.uyirmesh.mesh.PublicHub
import com.ameer.uyirmesh.rescuer.Rescuer
import com.ameer.uyirmesh.sos.SosForm
import com.ameer.uyirmesh.sos.VoiceNote
import com.ameer.uyirmesh.util.MapsLink
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.delay

/* ---------------- home ---------------- */

@Composable
fun PublicHome(nav: Nav) {
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val mySos by Graph.store.mySos.flow.collectAsStateWithLifecycle()
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val countdown by Graph.trigger.state.collectAsStateWithLifecycle()
    val active = mySos.lastOrNull { it.active }
    val now = System.currentTimeMillis()
    val liveAlerts = alerts.filter { it.isValid(now) && it.kind != "SHELTER" && now - it.receivedAt < 24 * 3600_000L }
    val settings by Graph.prefs.settings.collectAsStateWithLifecycle()
    val checkIn by com.ameer.uyirmesh.sos.CheckIn.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        ScreenBar("UyirMesh", null) {
            IconButton(onClick = { nav.go("settings") }) { Icon(Ic.Settings, tr("settings")) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            MeshStatusCard(stats.peers, stats.waitingOwn, stats.running) { nav.go("network") }
            LocationQualityCard()
            if (countdown.active) {
                InfoCard(color = Palette.Red.copy(alpha = 0.15f)) {
                    Text(tr("countdown_title").replace("{n}", countdown.seconds.toString()), fontWeight = FontWeight.Bold, color = Palette.Red)
                    ButtonRow {
                        TonalButton(tr("cancel"), modifier = Modifier.weight(1f)) { Graph.trigger.cancel() }
                        TonalButton(tr("send_now"), modifier = Modifier.weight(1f)) { Graph.trigger.sendNow() }
                    }
                }
            }
            val polls by Graph.store.polls.flow.collectAsStateWithLifecycle()
            for (p in polls.filter { !it.mine && it.isValid(now) && it.answered < 0 }.takeLast(2)) PollCard(nav, p)
            for (a in liveAlerts.takeLast(2).reversed()) {
                InfoCard(color = (if (a.severity >= 3) Palette.Red else Palette.Orange).copy(alpha = 0.15f), onClick = { nav.go("alerts") }) {
                    Text("${PublicHub.alertTitle(a.kind)}: ${a.title}", fontWeight = FontWeight.Bold)
                    Text(a.message, maxLines = 2)
                }
            }
            if (checkIn != null) {
                InfoCard(color = Palette.Teal.copy(alpha = 0.13f), onClick = { nav.go("checkin") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.Timer, null, tint = Palette.Teal)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("checkin_due").replace("{t}", com.ameer.uyirmesh.sos.CheckIn.timeOf(checkIn!!.deadline)), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TonalButton(tr("checkin_ok")) { com.ameer.uyirmesh.sos.CheckIn.ok(Graph.app) }
                    }
                }
            }
            if (active != null) {
                ActiveSosCard(active) { nav.go("sos_status") }
            } else if (settings.simpleMode) {
                Spacer(Modifier.height(8.dp))
                Text(tr("picture_sos_hint"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                PictureSosGrid { }
            } else {
                Spacer(Modifier.height(12.dp))
                BigSosButton { nav.go("sos_form") }
                Text(
                    tr("sos_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            ButtonRow {
                PrimaryButton(tr("im_safe"), Ic.CheckCircle, Modifier.weight(1f), color = Palette.Green) { nav.go("safe") }
                PrimaryButton(tr("find_me"), Ic.Siren, Modifier.weight(1f), color = Palette.Orange) { nav.go("findme") }
            }
            if (settings.simpleMode) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(tr("full_app"), Ic.Home) { Graph.prefs.update { it.copy(simpleMode = false) } }
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            NearestShelterCard(nav)
            SectionTitle(tr("safety_tools"))
            MenuTile(Ic.Touch, tr("picture_sos"), tr("picture_sos_sub"), Palette.Red) { nav.go("picture_sos") }
            MenuTile(Ic.Timer, tr("checkin"), tr("checkin_sub"), Palette.Teal) { nav.go("checkin") }
            SectionTitle(tr("help_and_info"))
            MenuTile(Ic.Alert, tr("alerts"), tr("alerts_sub"), Palette.Orange, badge = liveAlerts.size) { nav.go("alerts") }
            MenuTile(Ic.Chat, tr("group_chat"), tr("group_chat_sub"), Palette.Blue) { nav.go("chat") }
            MenuTile(Ic.Search, tr("missing_people"), tr("missing_people_sub"), Palette.Purple) { nav.go("missing") }
            MenuTile(Ic.Shelter, tr("shelters"), tr("shelters_sub"), Palette.Teal) { nav.go("shelters") }
            MenuTile(Ic.Map, tr("map"), tr("map_sub"), Palette.Blue) { nav.go("map") }
            MenuTile(Ic.Healing, tr("first_aid"), tr("first_aid_sub"), Palette.Red) { nav.go("firstaid") }
            MenuTile(Ic.Report, tr("report_problem"), tr("report_problem_sub"), Palette.Amber) { nav.go("report") }
            MenuTile(Ic.Heart, tr("i_can_help"), tr("i_can_help_sub"), Palette.Green) { nav.go("volunteer") }
            MenuTile(Ic.Medical, tr("medical_card"), tr("medical_card_sub"), Palette.Red) { nav.go("profile") }
            SectionTitle(tr("phone"))
            MenuTile(Ic.Network, tr("network_status"), tr("network_status_sub"), Palette.Teal) { nav.go("network") }
            MenuTile(Ic.Security, tr("self_test"), tr("self_test_sub"), Palette.Green) { nav.go("selftest") }
            MenuTile(Ic.Group, tr("rescue_modes"), tr("rescue_modes_sub"), Palette.Grey) { nav.go("modes") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MeshStatusCard(peers: Int, waiting: Int, running: Boolean, onClick: () -> Unit) {
    val ok = running && peers > 0
    InfoCard(color = (if (ok) Palette.Green else Palette.Amber).copy(alpha = 0.14f), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok, warn = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (!running) tr("mesh_off") else if (peers == 0) tr("mesh_searching") else tr("mesh_connected").replace("{n}", peers.toString()),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (waiting > 0) tr("mesh_waiting").replace("{n}", waiting.toString()) else tr("mesh_works_offline"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(baseContactText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                powerNote()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            }
            Icon(Ic.Arrow, null)
        }
    }
}

@Composable
private fun BigSosButton(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(210.dp).scale(pulse).clip(CircleShape).background(Palette.Red).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black)
                Text(tr("tap_for_help"), color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ActiveSosCard(s: MySos, onClick: () -> Unit) {
    InfoCard(color = Palette.Red.copy(alpha = 0.16f), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Sos, null, tint = Palette.Red, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("sos_active"), fontWeight = FontWeight.Bold, color = Palette.Red)
                Text(sosStage(s), style = MaterialTheme.typography.bodyMedium)
                if (s.drill) Text(tr("drill_mode_on"), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            }
            Icon(Ic.Arrow, null)
        }
    }
}

@Composable
fun sosStage(s: MySos): String = when {
    s.rescuedAt > 0 -> tr("stage_rescued")
    s.onWayAt > 0 -> tr("stage_on_way").replace("{e}", if (s.etaMin > 0) "${s.etaMin} min" else "-")
    s.assignedAt > 0 -> tr("stage_assigned").replace("{t}", s.team.ifBlank { s.rescuer })
    s.baseAt > 0 -> tr("stage_base")
    s.handedAt > 0 -> tr("stage_handed").replace("{n}", s.handedTo.toString())
    else -> tr("stage_waiting")
}

/* ---------------- SOS form ---------------- */

@Composable
fun SosFormScreen(nav: Nav) {
    val ctx = LocalContext.current
    val start = remember { Graph.victim.lastForm() }
    var kind by remember { mutableStateOf(start.kind) }
    var people by remember { mutableIntStateOf(start.people) }
    var children by remember { mutableIntStateOf(start.children) }
    var elderly by remember { mutableIntStateOf(start.elderly) }
    var disabled by remember { mutableIntStateOf(start.disabled) }
    var pregnant by remember { mutableIntStateOf(start.pregnant) }
    var injured by remember { mutableStateOf(start.injured) }
    var needs by remember { mutableStateOf(start.needs.toSet()) }
    var note by remember { mutableStateOf("") }
    var animals by remember { mutableIntStateOf(start.animals) }
    var silent by remember { mutableStateOf(false) }
    var forOther by remember { mutableStateOf(false) }
    var otherName by remember { mutableStateOf("") }
    var otherPhone by remember { mutableStateOf("") }
    var voiceAfter by remember { mutableStateOf(Perms.hasMic(ctx)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voiceAfter = it }
    Scaffold(topBar = { ScreenBar(tr("send_sos"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
            VoiceFillCard { text, r ->
                r.kind?.let { kind = it }
                r.people?.let { people = it }
                r.children?.let { children = it }
                r.elderly?.let { elderly = it }
                r.disabled?.let { disabled = it }
                r.pregnant?.let { pregnant = it }
                r.injured?.let { injured = it }
                r.animals?.let { animals = it }
                if (r.needs.isNotEmpty()) needs = needs + r.needs
                if (people < children + elderly) people = (children + elderly).coerceAtMost(200)
                if (note.isBlank()) note = text.take(300)
            }
            SectionTitle(tr("what_happened"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in EmergencyKind.ALL) {
                    KindButton(k, kind == k) { kind = k }
                }
            }
            SectionTitle(tr("who_is_here"))
            Stepper(tr("people_total"), people, 1, 200, Ic.Group) { people = it }
            Stepper(tr("children"), children, 0, 99, Ic.Child) { children = it }
            Stepper(tr("elderly"), elderly, 0, 99, Ic.Person) { elderly = it }
            Stepper(tr("disabled"), disabled, 0, 99, Ic.Accessible) { disabled = it }
            Stepper(tr("pregnant"), pregnant, 0, 99, Ic.Pregnant) { pregnant = it }
            SwitchRow(tr("someone_injured"), tr("someone_injured_sub"), injured) { injured = it }
            Stepper(tr("animals"), animals, 0, 99, Ic.Pets) { animals = it }
            SectionTitle(tr("we_need"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in Needs.ALL) {
                    FilterChip(
                        selected = n in needs,
                        onClick = { needs = if (n in needs) needs - n else needs + n },
                        label = { Text(needLabel(n)) },
                        leadingIcon = { Icon(needIcon(n), null, Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(300) }, label = { Text(tr("note_optional")) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), minLines = 2,
            )
            SwitchRow(tr("silent_sos"), tr("silent_sos_sub"), silent) { silent = it }
            SwitchRow(tr("for_someone_else"), tr("for_someone_else_sub"), forOther) { forOther = it }
            if (forOther) {
                Field(tr("their_name"), otherName, { otherName = it.take(60) })
                Field(tr("their_phone"), otherPhone, { otherPhone = it.take(30) }, number = true)
            }
            SwitchRow(tr("voice_after"), tr("voice_after_sub"), voiceAfter) { on ->
                if (on && !Perms.hasMic(ctx)) micLauncher.launch(Perms.MIC) else voiceAfter = on
            }
            Text(tr("sos_privacy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("send_sos_now"), Ic.Send, modifier = Modifier.height(64.dp)) {
                val form = SosForm(
                    kind, people, children, elderly, disabled, pregnant, injured, needs.toList(), note.trim(),
                    silent = silent, forOther = forOther, otherName = otherName.trim(), otherPhone = otherPhone.trim(), animals = animals,
                )
                Graph.victim.sendSos(form, Triggers.BUTTON)
                if (voiceAfter) VoiceNote.recordAndSend(ctx)
                nav.replace("home")
                nav.go("sos_status")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KindButton(kind: String, selected: Boolean, onClick: () -> Unit) {
    val color = when (kind) {
        EmergencyKind.FIRE -> Palette.Orange
        EmergencyKind.MEDICAL -> Palette.Red
        EmergencyKind.FLOOD -> Palette.Blue
        EmergencyKind.NEED -> Palette.Teal
        else -> Palette.Purple
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) color else color.copy(alpha = 0.12f)),
        modifier = Modifier.width(104.dp).height(86.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(kindIcon(kind), null, tint = if (selected) Color.White else color)
            Text(kindLabel(kind), color = if (selected) Color.White else color, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

fun kindIcon(kind: String): ImageVector = when (kind) {
    EmergencyKind.FIRE -> Ic.Fire
    EmergencyKind.MEDICAL -> Ic.Hospital
    EmergencyKind.FLOOD -> Ic.Boat
    EmergencyKind.NEED -> Ic.Water
    else -> Ic.Sos
}

@Composable
fun kindLabel(kind: String): String = when (kind) {
    EmergencyKind.FIRE -> tr("kind_fire")
    EmergencyKind.MEDICAL -> tr("kind_medical")
    EmergencyKind.FLOOD -> tr("kind_flood")
    EmergencyKind.NEED -> tr("kind_need")
    else -> tr("kind_trapped")
}

@Composable
fun needLabel(n: String): String = when (n) {
    Needs.WATER -> tr("need_water")
    Needs.FOOD -> tr("need_food")
    Needs.MEDICINE -> tr("need_medicine")
    Needs.BABY_FOOD -> tr("need_baby_food")
    else -> tr("need_boat")
}

fun needIcon(n: String): ImageVector = when (n) {
    Needs.WATER -> Ic.Water
    Needs.FOOD -> Ic.Food
    Needs.MEDICINE -> Ic.Medicine
    Needs.BABY_FOOD -> Ic.Child
    else -> Ic.Boat
}

/* ---------------- SOS status ---------------- */

@Composable
fun SosStatusScreen(nav: Nav) {
    val ctx = LocalContext.current
    val mySos by Graph.store.mySos.flow.collectAsStateWithLifecycle()
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val recording by VoiceNote.seconds.collectAsStateWithLifecycle()
    val s = mySos.lastOrNull { it.active } ?: mySos.lastOrNull()
    var confirmCancel by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) VoiceNote.recordAndSend(ctx) }
    Scaffold(topBar = { ScreenBar(tr("sos_status"), { nav.back() }) }) { pad ->
        if (s == null) {
            Box(Modifier.padding(pad)) { EmptyState(Ic.Sos, tr("no_sos_yet")) }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (s.drill) Chip(tr("drill_mode_on"), Palette.Orange, Modifier.padding(top = 8.dp))
            InfoCard(color = (if (s.active) Palette.Red else Palette.Green).copy(alpha = 0.14f)) {
                Text(if (s.active) tr("sos_active") else if (s.rescuedAt > 0) tr("stage_rescued") else tr("sos_cancelled"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("${kindLabel(s.payload.kind)} · ${s.payload.people} ${tr("people")} · ${tr("sent")} ${timeText(s.sentAt)}")
                if (stats.waitingOwn > 0 && s.active) Text(tr("mesh_waiting").replace("{n}", stats.waitingOwn.toString()), color = Palette.Orange, style = MaterialTheme.typography.bodySmall)
            }
            if (s.active && s.helpDistanceM >= 0) HelpComingCard(nav, s)
            if (s.active) LocationQualityCard(showWhenGood = true)
            SectionTitle(tr("timeline"))
            TimelineStep(tr("tl_sent"), timeText(s.sentAt), true)
            TimelineStep(tr("tl_handed").replace("{n}", s.handedTo.toString()), timeText(s.handedAt), s.handedAt > 0)
            TimelineStep(
                tr("tl_base") + if (s.baseHops > 0) " (${s.baseHops} ${tr("hops")})" else "",
                timeText(s.baseAt), s.baseAt > 0,
            )
            TimelineStep(tr("tl_assigned") + if (s.team.isNotBlank()) ": ${s.team}" else "", timeText(s.assignedAt), s.assignedAt > 0)
            TimelineStep(
                tr("tl_on_way") + (if (s.rescuer.isNotBlank()) " - ${s.rescuer}" else "") +
                    (if (s.active && s.helpDistanceM >= 0) " · ${Geo.formatDistance(s.helpDistanceM.toDouble())}" else "") +
                    if (s.etaMin > 0) " · ETA ${s.etaMin} min" else "",
                timeText(s.onWayAt), s.onWayAt > 0,
            )
            TimelineStep(tr("tl_rescued"), timeText(s.rescuedAt), s.rescuedAt > 0, last = true)
            if (s.replies.isNotEmpty()) {
                SectionTitle(tr("replies"))
                for (r in s.replies.reversed()) {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.Security, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                (if (r.verifiedBy == "BASE") tr("rescue_base") else r.verifiedBy) + " · " + tr("verified"),
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(timeText(r.at), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(statusLabel(r.status) + if (r.etaMin > 0) " · ETA ${r.etaMin} min" else "")
                        if (r.message.isNotBlank()) Text(r.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (s.active) {
                SectionTitle(tr("more_help"))
                if (recording >= 0) {
                    InfoCard(color = Palette.Red.copy(alpha = 0.12f)) {
                        Text(tr("recording").replace("{n}", recording.toString()), fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { (recording / VoiceNote.limitSeconds.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        ButtonRow {
                            TonalButton(tr("stop_and_send"), Ic.Send, Modifier.weight(1f)) { VoiceNote.stopNow() }
                            TonalButton(tr("discard"), Ic.Delete, Modifier.weight(1f)) { VoiceNote.discard() }
                        }
                    }
                } else {
                    ButtonRow {
                        TonalButton(tr("voice_note"), Ic.Mic, Modifier.weight(1f)) {
                            if (Perms.hasMic(ctx)) VoiceNote.recordAndSend(ctx) else micLauncher.launch(Perms.MIC)
                        }
                        TonalButton(tr("photo"), Ic.Camera, Modifier.weight(1f)) { nav.go("photo") }
                    }
                    WeakLinkNote()
                }
                ButtonRow {
                    TonalButton(tr("update_location"), Ic.MyLocation, Modifier.weight(1f)) {
                        Graph.loc.requestOnce(20_000) { Graph.victim.active()?.let { a -> Graph.victim.sendUpdate(a, false, "") } }
                    }
                    TonalButton(tr("find_me"), Ic.Siren, Modifier.weight(1f)) { nav.go("findme") }
                }
                Text(tr("voice_photo_sent").replace("{v}", s.voiceSent.toString()).replace("{p}", s.photoSent.toString()), style = MaterialTheme.typography.bodySmall)
                SectionTitle(tr("backup_phone"))
                Text(tr("backup_phone_sub"), style = MaterialTheme.typography.bodySmall)
                ButtonRow {
                    TonalButton(tr("call_112"), Ic.Call, Modifier.weight(1f)) { dial(ctx, "112") }
                    TonalButton(tr("sms_family"), Ic.Sms, Modifier.weight(1f)) { smsFamily(ctx, sosSmsText(s)) }
                }
                Spacer(Modifier.height(16.dp))
                SecondaryButton(tr("cancel_sos"), Ic.Cancel) { confirmCancel = true }
            } else {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(tr("new_sos"), Ic.Sos) { nav.go("sos_form") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(tr("cancel_sos_q")) },
            text = { Text(tr("cancel_sos_body")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    Graph.victim.cancel()
                }) { Text(tr("yes_cancel")) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(tr("keep_sos")) } },
        )
    }
}

/** On the victim's SOS screen: how far away the rescuer on the way is (from the rescuer's signed updates). */
@Composable
private fun HelpComingCard(nav: Nav, s: MySos) {
    val ageMin = ((System.currentTimeMillis() - s.helpAt) / 60_000).coerceAtLeast(0)
    InfoCard(color = Palette.Green.copy(alpha = 0.14f)) {
        Text(tr("help_coming"), fontWeight = FontWeight.Bold, color = Palette.Green)
        Text(
            tr("help_distance").replace("{w}", s.helpBy).replace("{d}", Geo.formatDistance(s.helpDistanceM.toDouble())),
            fontSize = 24.sp, fontWeight = FontWeight.Black,
        )
        Text(
            (if (s.helpEtaMin > 0) tr("help_eta").replace("{e}", s.helpEtaMin.toString()) + " · " else "") +
                tr("help_updated").replace("{a}", agoText(s.helpAt)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (ageMin >= 10) Text(tr("help_old").replace("{n}", ageMin.toString()), color = Palette.Orange, style = MaterialTheme.typography.bodySmall)
        if (s.helpDistanceM <= Rescuer.NEAR_M) {
            Text(tr("help_near"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            TonalButton(tr("find_me"), Ic.Siren, Modifier.fillMaxWidth().padding(top = 4.dp)) { nav.go("findme") }
        }
        Text(tr("help_signed"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun statusLabel(status: String): String = when (status) {
    AckStatus.RECEIVED -> tr("st_received")
    AckStatus.ASSIGNED -> tr("st_assigned")
    AckStatus.ON_THE_WAY -> tr("st_on_way")
    AckStatus.RESCUED -> tr("st_rescued")
    AckStatus.CANCELLED -> tr("st_cancelled")
    else -> status
}

@Composable
private fun TimelineStep(text: String, time: String, done: Boolean, last: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (done) Palette.Green else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { if (done) Icon(Ic.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            if (!last) Box(Modifier.width(2.dp).height(26.dp).background(if (done) Palette.Green else MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal, color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (done) time else "", style = MaterialTheme.typography.bodySmall)
    }
}

fun dial(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

fun smsFamily(ctx: Context, text: String) {
    val numbers = Graph.prefs.now.familyPhones.split(',', ';', ' ').map { it.trim() }.filter { it.length >= 5 }
    val p = Graph.store.profile.value
    val all = (numbers + listOfNotNull(p.contactPhone.takeIf { it.length >= 5 })).distinct()
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + all.joinToString(";")))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

/** SMS for family when there is mobile signal: the newest position as a Google Maps link (opens on any phone). */
private fun sosSmsText(s: MySos): String {
    val p = s.payload
    val fresh = Graph.loc.last?.takeIf { Graph.loc.ageMs() < 15 * 60_000 }
    val lat = fresh?.latitude ?: p.lat
    val lon = fresh?.longitude ?: p.lon
    val loc = if (Geo.isValid(lat, lon)) " Location: ${MapsLink.webUrl(lat!!, lon!!)}" else ""
    return "SOS from ${p.name.ifBlank { "me" }}: ${p.kind.lowercase()}, ${p.people} people.$loc (sent with UyirMesh)"
}

/* ---------------- find me ---------------- */

@Composable
fun FindMeScreen(nav: Nav) {
    val siren by Graph.alarm.siren.collectAsStateWithLifecycle()
    val torch by Graph.alarm.torch.collectAsStateWithLifecycle()
    var strobe by remember { mutableStateOf(false) }
    if (strobe) {
        StrobeScreen { strobe = false }
        return
    }
    Scaffold(topBar = { ScreenBar(tr("find_me"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("find_me_body"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (siren) tr("stop_siren") else tr("start_siren"), Ic.Siren, color = if (siren) Palette.Grey else Palette.Red) {
                if (siren) Graph.alarm.stopSiren() else Graph.alarm.startSiren()
            }
            Text(tr("siren_sub"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            PrimaryButton(if (torch) tr("stop_flash") else tr("start_flash"), Ic.Torch, color = if (torch) Palette.Grey else Palette.Orange) {
                if (torch) Graph.alarm.stopTorch() else Graph.alarm.startTorchSos()
            }
            Text(if (Graph.alarm.hasTorch()) tr("flash_sub") else tr("no_flash"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            PrimaryButton(tr("screen_strobe"), Ic.Theme, color = Palette.Purple) { strobe = true }
            Text(tr("strobe_sub"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            PrimaryButton(tr("vibrate_sos"), Ic.Vibrate, color = Palette.Teal) { Graph.alarm.vibrateSos() }
            Text(tr("vibrate_sub"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StrobeScreen(onStop: () -> Unit) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            on = !on
        }
    }
    Box(
        Modifier.fillMaxSize().background(if (on) Color.White else Color.Black).clickable(onClick = onStop),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(tr("tap_to_stop"), color = if (on) Color.Black else Color.White, modifier = Modifier.padding(32.dp))
    }
}

/* ---------------- photo ---------------- */

@Composable
fun PhotoScreen(nav: Nav) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Perms.hasCamera(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Perms.CAMERA) }
    val holder = remember { PhotoCapture() }
    Scaffold(topBar = { ScreenBar(tr("send_photo"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (!granted) {
                EmptyState(Ic.Camera, tr("camera_needed"))
                SecondaryButton(tr("allow_camera")) { launcher.launch(Perms.CAMERA) }
                return@Column
            }
            CameraPreview(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)), holder)
            Spacer(Modifier.height(12.dp))
            Text(tr("photo_sub"), style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (busy) tr("sending") else tr("take_and_send"), Ic.Camera, enabled = !busy) {
                busy = true
                PhotoUtil.take(holder, ctx.cacheDir) { bytes ->
                    val ok = bytes != null && Graph.victim.sendMedia("image/jpeg", 0, bytes)
                    busy = false
                    message = if (ok) L.t("photo_sent") else L.t("photo_failed")
                }
            }
        }
    }
}

/* ---------------- chat ---------------- */

@Composable
fun ChatScreen(nav: Nav) {
    val chat by Graph.store.chat.flow.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) state.animateScrollToItem(chat.size - 1) }
    Scaffold(topBar = { ScreenBar(tr("group_chat"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Text(tr("chat_public_note"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp)) {
                if (chat.isEmpty()) item { EmptyState(Ic.Chat, tr("no_messages")) }
                items(chat, key = { it.id }) { m -> Bubble(m.name, m.text, m.at, m.mine, m.drill) }
            }
            MessageInput(text, { text = it }) {
                Graph.public.sendChat(text)
                text = ""
            }
        }
    }
}

@Composable
fun Bubble(name: String, text: String, at: Long, mine: Boolean, drill: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Card(colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth(0.85f)) {
            Column(Modifier.padding(10.dp)) {
                if (!mine) Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(text)
                Text((if (drill) "${tr("drill")} · " else "") + timeText(at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MessageInput(text: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { onChange(it.take(500)) }, modifier = Modifier.weight(1f), placeholder = { Text(tr("type_message")) }, maxLines = 4)
        IconButton(onClick = { if (text.isNotBlank()) onSend() }) { Icon(Ic.Send, tr("send"), tint = MaterialTheme.colorScheme.primary) }
    }
}

/* ---------------- alerts ---------------- */

@Composable
fun AlertsScreen(nav: Nav) {
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val me = Graph.loc.lastLatLon
    val now = System.currentTimeMillis()
    Scaffold(topBar = { ScreenBar(tr("alerts"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item { Text(tr("alerts_verified_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val list = alerts.filter { it.kind != "SHELTER" }.sortedByDescending { it.at }
            if (list.isEmpty()) item { EmptyState(Ic.Alert, tr("no_alerts")) }
            items(list, key = { it.id }) { a ->
                val color = if (a.severity >= 3 || a.kind == "EVACUATE") Palette.Red else if (a.kind == "ALL_CLEAR") Palette.Green else Palette.Orange
                InfoCard(color = color.copy(alpha = if (a.isValid(now)) 0.15f else 0.05f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip(PublicHub.alertTitle(a.kind), color)
                        Spacer(Modifier.width(6.dp))
                        if (a.drill) Chip(tr("drill"), Palette.Grey)
                        Spacer(Modifier.weight(1f))
                        Text(timeText(a.at), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(a.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(a.message)
                    if (a.lat != null && a.lon != null) {
                        val where = if (me != null) {
                            val d = Geo.distanceM(me, LatLon(a.lat, a.lon))
                            "${Geo.formatDistance(d)} ${Geo.compassName(Geo.bearingDeg(me, LatLon(a.lat, a.lon)))}" + if (a.radiusM > 0 && d <= a.radiusM) " · ${tr("you_are_inside")}" else ""
                        } else "%.4f, %.4f".format(java.util.Locale.US, a.lat, a.lon)
                        Text("📍 $where" + if (a.radiusM > 0) " · ${tr("radius")} ${Geo.formatDistance(a.radiusM.toDouble())}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("✔ ${if (a.mine) tr("sent_by_you") else tr("signed_by_base")}" + if (!a.isValid(now)) " · ${tr("expired")}" else "", style = MaterialTheme.typography.labelSmall, color = Palette.Green)
                }
            }
        }
    }
}

/* ---------------- I'm safe ---------------- */

@Composable
fun SafeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val safe by Graph.store.safe.flow.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf(L.t("safe_default_message")) }
    var place by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    Scaffold(topBar = { ScreenBar(tr("im_safe"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("safe_body"), style = MaterialTheme.typography.bodyMedium)
                Field(tr("message"), message, { message = it.take(200) })
                Field(tr("where_are_you"), place, { place = it.take(80) })
                PrimaryButton(if (sent) tr("sent_again") else tr("tell_everyone_safe"), Ic.CheckCircle, color = Palette.Green) {
                    Graph.public.sendSafe(message.trim(), place.trim())
                    sent = true
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("sms_family_safe"), Ic.Sms) { smsFamily(ctx, "$message ${if (place.isNotBlank()) "($place)" else ""} - ${Graph.store.profile.value.name}") }
                Text(tr("sms_needs_signal"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Graph.victim.active() != null) {
                    Spacer(Modifier.height(8.dp))
                    InfoCard(color = Palette.Orange.copy(alpha = 0.12f)) {
                        Text(tr("safe_cancel_sos_hint"))
                        TonalButton(tr("cancel_sos"), Ic.Cancel) { Graph.victim.cancel() }
                    }
                }
                SectionTitle(tr("people_safe") + " (${safe.size})")
                Field(tr("search_name"), query, { query = it })
            }
            val list = safe.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.sortedByDescending { it.at }
            if (list.isEmpty()) item { EmptyState(Ic.Group, tr("nobody_yet")) }
            items(list, key = { it.id }) { s ->
                InfoCard {
                    Row {
                        Text(s.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(timeText(s.at), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(s.message)
                    val place2 = if (s.place.startsWith("SHELTER:")) "🏠 " + s.place.substringAfterLast(':') else s.place
                    if (place2.isNotBlank()) Text(place2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/* ---------------- missing persons ---------------- */

@Composable
fun MissingScreen(nav: Nav) {
    val missing by Graph.store.missing.flow.collectAsStateWithLifecycle()
    val finds by Graph.store.finds.flow.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var lastSeen by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf(Graph.store.profile.value.phone) }
    var query by remember { mutableStateOf("") }
    Scaffold(topBar = { ScreenBar(tr("missing_people"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                SectionTitle(tr("ask_base"))
                Text(tr("ask_base_sub"), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(tr("name"), query, { query = it.take(60) }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TonalButton(tr("ask"), Ic.Search) { Graph.public.sendFind(query) }
                }
                for (f in finds.sortedByDescending { it.at }.take(5)) {
                    InfoCard(color = Palette.Blue.copy(alpha = 0.1f)) {
                        Text("${tr("base_answer_for")} \"${f.query}\"", fontWeight = FontWeight.Bold)
                        if (f.matches.isEmpty()) Text(tr("find_none").replace("{q}", f.query))
                        for (m in f.matches) Text("• ${m.name}: ${m.status}${if (m.place.isNotBlank()) " - ${m.place}" else ""} (${timeText(m.at)})")
                    }
                }
                SectionTitle(tr("board"))
                if (!showForm) {
                    SecondaryButton(tr("report_missing"), Ic.PersonAdd) { showForm = true }
                } else {
                    InfoCard {
                        Field(tr("missing_name"), person, { person = it.take(60) })
                        Field(tr("age"), age, { age = it.filter { c -> c.isDigit() }.take(3) }, number = true)
                        Field(tr("description"), desc, { desc = it.take(300) }, singleLine = false)
                        Field(tr("last_seen"), lastSeen, { lastSeen = it.take(120) })
                        Field(tr("contact_phone"), contact, { contact = it.take(30) }, number = true)
                        ButtonRow {
                            TonalButton(tr("cancel"), modifier = Modifier.weight(1f)) { showForm = false }
                            TonalButton(tr("post"), Ic.Send, Modifier.weight(1f), enabled = person.isNotBlank()) {
                                Graph.public.sendMissing(person.trim(), age.toIntOrNull() ?: -1, desc.trim(), lastSeen.trim(), contact.trim())
                                showForm = false
                                person = ""; age = ""; desc = ""; lastSeen = ""
                            }
                        }
                    }
                }
            }
            val list = missing.sortedWith(compareBy({ it.found }, { -it.at }))
            if (list.isEmpty()) item { EmptyState(Ic.Search, tr("no_missing")) }
            items(list, key = { it.id }) { m ->
                InfoCard(color = if (m.found) Palette.Green.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.person + if (m.age > 0) ", ${m.age}" else "", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        if (m.found) Chip(tr("found"), Palette.Green)
                    }
                    if (m.description.isNotBlank()) Text(m.description)
                    if (m.lastSeen.isNotBlank()) Text("${tr("last_seen")}: ${m.lastSeen}", style = MaterialTheme.typography.bodySmall)
                    Text("${tr("contact")}: ${m.reporter} ${m.contact} · ${timeText(m.at)}", style = MaterialTheme.typography.bodySmall)
                    if (!m.found) TonalButton(tr("mark_found"), Ic.Check) { Graph.public.markFound(m) }
                }
            }
        }
    }
}

/* ---------------- reports ---------------- */

@Composable
fun ReportScreen(nav: Nav) {
    val reports by Graph.store.reports.flow.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(ReportKind.ROAD_BLOCK) }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    Scaffold(topBar = { ScreenBar(tr("report_problem"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("report_body"), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in ReportKind.ALL) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(reportLabel(k)) })
                }
                Field(tr("details"), note, { note = it.take(300) }, singleLine = false)
                Text(tr("report_location_note"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(if (sent) tr("sent_send_another") else tr("send_report"), Ic.Send) {
                    Graph.public.sendReport(kind, note.trim())
                    note = ""
                    sent = true
                }
                SectionTitle(tr("recent_reports"))
            }
            val list = reports.sortedByDescending { it.at }
            if (list.isEmpty()) item { EmptyState(Ic.Report, tr("no_reports")) }
            items(list, key = { it.id }) { r ->
                InfoCard {
                    Row {
                        Text(reportLabel(r.kind), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(timeText(r.at), style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.note.isNotBlank()) Text(r.note)
                    Text(r.name + distanceFromMe(r.lat, r.lon), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun reportLabel(k: String): String = when (k) {
    ReportKind.ROAD_BLOCK -> tr("rk_road")
    ReportKind.WATER_LEVEL -> tr("rk_water")
    ReportKind.TREE_FALL -> tr("rk_tree")
    ReportKind.FIRE -> tr("rk_fire")
    ReportKind.BUILDING -> tr("rk_building")
    else -> tr("rk_other")
}

fun distanceFromMe(lat: Double?, lon: Double?): String {
    val me = Graph.loc.lastLatLon ?: return ""
    if (!Geo.isValid(lat, lon)) return ""
    val p = LatLon(lat!!, lon!!)
    return " · ${Geo.formatDistance(Geo.distanceM(me, p))} ${Geo.compassName(Geo.bearingDeg(me, p))}"
}

/* ---------------- volunteer ---------------- */

@Composable
fun VolunteerScreen(nav: Nav) {
    var skills by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(Graph.prefs.getLong("volunteeredAt") > 0) }
    Scaffold(topBar = { ScreenBar(tr("i_can_help"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            Text(tr("volunteer_body"), style = MaterialTheme.typography.bodyMedium)
            SectionTitle(tr("skills"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Skills.ALL) FilterChip(selected = s in skills, onClick = { skills = if (s in skills) skills - s else skills + s }, label = { Text(skillLabel(s)) })
            }
            Field(tr("what_can_you_offer"), note, { note = it.take(300) }, singleLine = false)
            Text(tr("volunteer_privacy"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (sent) tr("sent_update") else tr("offer_help"), Ic.Heart, color = Palette.Green) {
                Graph.public.sendVolunteer(skills.toList(), note.trim())
                sent = true
            }
        }
    }
}

@Composable
fun skillLabel(s: String): String = when (s) {
    Skills.DOCTOR -> tr("sk_doctor")
    Skills.NURSE -> tr("sk_nurse")
    Skills.SWIMMER -> tr("sk_swimmer")
    Skills.BOAT -> tr("sk_boat")
    Skills.DRIVER -> tr("sk_driver")
    Skills.ELECTRICIAN -> tr("sk_electrician")
    else -> tr("sk_cook")
}

/* ---------------- medical card ---------------- */

@Composable
fun ProfileScreen(nav: Nav) {
    val p by Graph.store.profile.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(p.name) }
    var phone by remember { mutableStateOf(p.phone) }
    var blood by remember { mutableStateOf(p.blood) }
    var allergies by remember { mutableStateOf(p.allergies) }
    var conditions by remember { mutableStateOf(p.conditions) }
    var medicines by remember { mutableStateOf(p.medicines) }
    var contactName by remember { mutableStateOf(p.contactName) }
    var contactPhone by remember { mutableStateOf(p.contactPhone) }
    var family by remember { mutableStateOf(Graph.prefs.now.familyPhones) }
    var showQr by remember { mutableStateOf(false) }
    Scaffold(topBar = { ScreenBar(tr("medical_card"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()) {
            Text(tr("medical_body"), style = MaterialTheme.typography.bodyMedium)
            Field(tr("your_name"), name, { name = it.take(40) })
            Field(tr("phone"), phone, { phone = it.take(15) }, number = true)
            Field(tr("blood_group"), blood, { blood = it.uppercase().take(4) })
            Field(tr("allergies"), allergies, { allergies = it.take(100) })
            Field(tr("conditions"), conditions, { conditions = it.take(100) })
            Field(tr("medicines"), medicines, { medicines = it.take(100) })
            Field(tr("emergency_contact"), contactName, { contactName = it.take(40) })
            Field(tr("emergency_contact_phone"), contactPhone, { contactPhone = it.take(15) }, number = true)
            Field(tr("family_phones"), family, { family = it.take(200) })
            Text(tr("family_phones_sub"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("save")) {
                val np = p.copy(name = name.trim(), phone = phone.trim(), blood = blood.trim(), allergies = allergies.trim(), conditions = conditions.trim(), medicines = medicines.trim(), contactName = contactName.trim(), contactPhone = contactPhone.trim())
                Graph.store.setProfile(np)
                Graph.prefs.update { it.copy(name = name.trim(), phone = phone.trim(), familyPhones = family.trim()) }
                showQr = true
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("show_qr"), Ic.Qr) { showQr = !showQr }
            if (showQr) {
                val card = ProfileCard(Graph.prefs.now.deviceId, p.name, p.phone, p.blood, p.allergies, p.conditions, p.medicines, p.contactName, p.contactPhone)
                Spacer(Modifier.height(12.dp))
                QrImage(card.toQr(), Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp))
                Text(tr("qr_sub"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
