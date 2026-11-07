@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ameer.uyirmesh.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.HazardKind
import com.ameer.uyirmesh.core.PollKind
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.data.PollItem
import com.ameer.uyirmesh.mesh.SafetyHub
import com.ameer.uyirmesh.rescuer.TeamVoice
import com.ameer.uyirmesh.util.Perms

/* ---------------- polls (public side) ---------------- */

@Composable
fun PollCard(nav: Nav, p: PollItem) {
    val roll = p.kind == PollKind.ROLLCALL
    InfoCard(color = (if (roll) Palette.Purple else Palette.Blue).copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (roll) Ic.HowToReg else Ic.Poll, null, tint = if (roll) Palette.Purple else Palette.Blue)
            Spacer(Modifier.width(8.dp))
            Text(if (roll) tr("rollcall") else tr("poll_from_base"), fontWeight = FontWeight.Bold)
            if (p.drill) {
                Spacer(Modifier.width(6.dp))
                Chip(tr("drill"), Palette.Orange)
            }
        }
        Text(p.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
        if (p.answered >= 0) {
            Text(tr("you_answered").replace("{a}", p.options.getOrNull(p.answered) ?: "?"), color = Palette.Green, fontWeight = FontWeight.SemiBold)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEachIndexed { i, o ->
                    PrimaryButton(o, modifier = Modifier.width(150.dp), color = if (roll && i == 1) Palette.Red else if (roll) Palette.Green else Palette.Blue) {
                        Graph.safety.answerPoll(p.pollId, i)
                        // "I need help" in a roll call: offer the SOS form right away.
                        if (roll && i == 1) nav.go("sos_form")
                    }
                }
            }
        }
        Text(tr("poll_private"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PollScreen(nav: Nav, id: String) {
    val polls by Graph.store.polls.flow.collectAsStateWithLifecycle()
    val p = polls.firstOrNull { it.pollId == id }
    Scaffold(topBar = { ScreenBar(tr("poll_from_base"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (p == null) EmptyState(Ic.Poll, tr("nothing_yet")) else PollCard(nav, p)
        }
    }
}

/* ---------------- hazards (rescuer or base) ---------------- */

@Composable
fun hazardChipLabel(k: String): String = remember(k, LocalLang.current) { SafetyHub.hazardLabel(k) }

@Composable
fun HazardScreen(nav: Nav) {
    var kind by remember { mutableStateOf(HazardKind.BRIDGE_DOWN) }
    var note by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(2) }
    var sent by remember { mutableStateOf<Boolean?>(null) }
    val isBase = Graph.prefs.now.role == Roles.BASE
    Scaffold(topBar = { ScreenBar(tr("mark_hazard"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("mark_hazard_body"))
            SectionTitle(tr("what_danger"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in HazardKind.ALL) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(hazardChipLabel(k)) })
            }
            Field(tr("message"), note, { note = it.take(200) })
            Stepper(tr("radius_m"), radius, 1, 20, Ic.Radar) { radius = it }
            Text(tr("hazard_where"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("send_hazard"), Ic.Hazard, color = Palette.Red) {
                val loc = Graph.loc.usable() ?: Graph.loc.last
                sent = if (isBase) {
                    Graph.baseOps.sendHazard(kind, note, loc?.latitude, loc?.longitude, radius * 100)
                } else {
                    Graph.rescuer.sendHazard(kind, note, radius * 100)
                }
            }
            when (sent) {
                true -> Text(tr("hazard_sent"), color = Palette.Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                false -> Text(tr("need_location_first"), color = Palette.Orange, modifier = Modifier.padding(top = 8.dp))
                null -> {}
            }
        }
    }
}

/* ---------------- closing a task with its outcome ---------------- */

@Composable
fun OutcomeDialog(people: Int, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var rescued by remember { mutableIntStateOf(people.coerceAtLeast(1)) }
    var injured by remember { mutableIntStateOf(0) }
    var takenTo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("rescue_outcome")) },
        text = {
            Column {
                Stepper(tr("people_rescued"), rescued, 0, 200, Ic.Group) { rescued = it }
                Stepper(tr("injured_count"), injured, 0, 200, Ic.Injury) { injured = it }
                Field(tr("taken_to"), takenTo, { takenTo = it.take(60) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = buildList {
                    add("$rescued rescued")
                    if (injured > 0) add("$injured injured")
                    if (takenTo.isNotBlank()) add("taken to ${takenTo.trim()}")
                }
                onDone(parts.joinToString(", "))
            }) { Text(tr("mark_rescued")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

/* ---------------- push to talk ---------------- */

@Composable
fun PushToTalkButton(team: String) {
    val ctx = LocalContext.current
    val secs by TeamVoice.seconds.collectAsStateWithLifecycle()
    val recording = secs >= 0
    val hasMic = Perms.hasMic(ctx)
    Surface(
        color = if (recording) Palette.Red else Palette.Blue,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(56.dp).pointerInput(team) {
            detectTapGestures(onPress = {
                if (team.isNotBlank() && TeamVoice.start(ctx, team)) {
                    val released = tryAwaitRelease()
                    TeamVoice.stop(send = released)
                }
            })
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Walkie, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !hasMic -> tr("voice_no_permission")
                        recording -> tr("ptt_recording").replace("{n}", secs.toString()).replace("{m}", TeamVoice.MAX_SECONDS.toString())
                        else -> tr("ptt_hold")
                    },
                    color = Color.White, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/* ---------------- base: polls and roll call ---------------- */

@Composable
fun BasePollsScreen(nav: Nav) {
    val polls by Graph.store.polls.flow.collectAsStateWithLifecycle()
    val answers by Graph.store.pollAnswers.flow.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf(L.t("yes"), L.t("no"))) }
    var minutes by remember { mutableIntStateOf(60) }
    var open by remember { mutableStateOf("") }
    Scaffold(topBar = { ScreenBar(tr("polls_rollcall"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            InfoCard(color = Palette.Purple.copy(alpha = 0.12f)) {
                Text(tr("rollcall"), fontWeight = FontWeight.Bold)
                Text(tr("rollcall_body"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                PrimaryButton(tr("start_rollcall"), Ic.HowToReg, color = Palette.Purple) {
                    Graph.baseOps.sendPoll(PollKind.ROLLCALL, L.t("rollcall_question"), listOf(L.t("rollcall_safe"), L.t("rollcall_help")), 180)
                }
            }
            SectionTitle(tr("new_poll"))
            Field(tr("poll_question"), question, { question = it.take(200) })
            options.forEachIndexed { i, o ->
                Field(tr("poll_option").replace("{n}", (i + 1).toString()), o, { v -> options = options.toMutableList().also { it[i] = v.take(40) } })
            }
            ButtonRow {
                TonalButton(tr("add_option"), Ic.Add, Modifier.weight(1f), enabled = options.size < 4) { options = options + "" }
                TonalButton(tr("remove_option"), Ic.Delete, Modifier.weight(1f), enabled = options.size > 2) { options = options.dropLast(1) }
            }
            Stepper(tr("valid_minutes"), minutes, 10, 1440, Ic.Timer) { minutes = it }
            PrimaryButton(tr("send_poll"), Ic.Send, enabled = question.isNotBlank() && options.count { it.isNotBlank() } >= 2) {
                if (Graph.baseOps.sendPoll(PollKind.POLL, question, options, minutes)) question = ""
            }
            SectionTitle(tr("results"))
            val mine = polls.filter { it.mine }.sortedByDescending { it.at }
            if (mine.isEmpty()) Text(tr("nothing_yet"), style = MaterialTheme.typography.bodySmall)
            for (p in mine) {
                val rs = answers.filter { it.pollId == p.pollId }
                val counts = p.options.indices.map { i -> rs.count { it.choice == i } }
                val total = rs.size.coerceAtLeast(1)
                InfoCard(onClick = { open = if (open == p.pollId) "" else p.pollId }) {
                    Text((if (p.kind == PollKind.ROLLCALL) tr("rollcall") + ": " else "") + p.question, fontWeight = FontWeight.Bold)
                    Text(tr("answers_count").replace("{n}", rs.size.toString()) + " · " + timeText(p.at), style = MaterialTheme.typography.bodySmall)
                    p.options.forEachIndexed { i, o ->
                        Text("$o: ${counts[i]}", modifier = Modifier.padding(top = 4.dp))
                        LinearProgressIndicator(
                            progress = { counts[i].toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (p.kind == PollKind.ROLLCALL && i == 1) Palette.Red else Palette.Green,
                        )
                    }
                    if (open == p.pollId) {
                        Spacer(Modifier.height(6.dp))
                        for (r in rs.sortedByDescending { it.choice }) {
                            Text(
                                "${r.name.ifBlank { r.src.take(6) }}: ${p.options.getOrNull(r.choice) ?: "?"}${if (r.phone.isNotBlank()) " · ${r.phone}" else ""} · ${timeText(r.at)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.kind == PollKind.ROLLCALL && r.choice == 1) Palette.Red else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- base: rescuer IDs and revoking ---------------- */

@Composable
fun BaseRescuerIdsScreen(nav: Nav) {
    val rescuers by Graph.store.rescuers.flow.collectAsStateWithLifecycle()
    val revoked by Graph.store.revoked.flow.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    val revokedIds = revoked.map { it.rescuerId }.toSet()
    Scaffold(topBar = { ScreenBar(tr("rescuer_ids"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("revoke_body"), style = MaterialTheme.typography.bodySmall)
            if (rescuers.isEmpty()) EmptyState(Ic.Group, tr("no_teams_yet"))
            for (r in rescuers.sortedBy { it.team + it.name }) {
                val gone = r.rescuerId in revokedIds
                InfoCard(color = if (gone) Palette.Red.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.name} · ${r.team}", fontWeight = FontWeight.Bold)
                            Text("${tr("rescuer_id")} ${r.rescuerId.take(8)} · ${tr("valid_until")} ${timeText(r.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (gone) Chip(tr("revoked"), Palette.Red) else TonalButton(tr("revoke"), Ic.Block) { confirm = r.rescuerId to r.name }
                    }
                }
            }
            if (revoked.isNotEmpty()) {
                SectionTitle(tr("revoked"))
                for (r in revoked) Text("${r.name.ifBlank { "-" }} · ${r.rescuerId.take(8)} · ${timeText(r.at)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("send_revoke_again"), Ic.Refresh) { Graph.baseOps.broadcastRevocations() }
            }
        }
    }
    confirm?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("revoke_q").replace("{n}", name)) },
            text = { Text(tr("revoke_note")) },
            confirmButton = {
                TextButton(onClick = {
                    Graph.baseOps.revoke(id, name)
                    confirm = null
                }) { Text(tr("revoke")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("cancel")) } },
        )
    }
}

/* ---------------- base: hand a case over to another base ---------------- */

@Composable
fun HandOverDialog(caseId: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("hand_over")) },
        text = {
            Column {
                Text(tr("hand_over_body"), style = MaterialTheme.typography.bodySmall)
                Field(tr("other_base_name"), name, { name = it.take(40) })
                Field(tr("message"), note, { note = it.take(200) })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                Graph.baseOps.handOver(caseId, name, note)
                onDismiss()
            }) { Text(tr("hand_over")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

/* ---------------- settings: internet relay ---------------- */

@Composable
fun RelaySettings() {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val st by Graph.gateway.status.collectAsStateWithLifecycle()
    var url by remember(s.gatewayUrl) { mutableStateOf(s.gatewayUrl) }
    SectionTitle(tr("internet_relay"))
    SwitchRow(tr("internet_relay"), tr("internet_relay_sub"), s.gateway) { v -> Graph.prefs.update { it.copy(gateway = v) } }
    Field(tr("relay_address"), url, { url = it.trim().take(120) })
    ButtonRow {
        TonalButton(tr("save"), Ic.Check, Modifier.weight(1f), enabled = url != s.gatewayUrl) { Graph.prefs.update { it.copy(gatewayUrl = url) } }
        if (s.role == Roles.BASE && Graph.base.hasKeys()) {
            TonalButton(tr("announce_relay"), Ic.Alert, Modifier.weight(1f), enabled = url.startsWith("http")) {
                Graph.prefs.update { it.copy(gatewayUrl = url) }
                Graph.baseOps.announceGateway(url)
            }
        }
    }
    val line = when {
        !s.gateway -> tr("relay_off")
        s.gatewayUrl.isBlank() -> tr("relay_no_address")
        !st.online -> tr("relay_waiting")
        st.error.isNotBlank() -> tr("relay_error").replace("{e}", st.error)
        else -> tr("relay_ok").replace("{u}", st.uploaded.toString()).replace("{d}", st.downloaded.toString()).replace("{t}", timeText(st.lastSync))
    }
    Text(line, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
    Text(tr("relay_privacy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Readable text for a warning flag the base put on a case (see BaseBrain checks). */
@Composable
fun flagText(f: String): String = when (f.substringBefore(':')) {
    "BURST" -> tr("flag_burst")
    "NO_GPS" -> tr("flag_no_gps")
    "JUMP" -> tr("flag_jump").replace("{d}", f.substringAfter(':', ""))
    "OLD_CLOCK" -> tr("flag_clock")
    "REPEAT_CANCEL" -> tr("flag_cancel")
    else -> f
}
