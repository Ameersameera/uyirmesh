@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ameer.uyirmesh.ui

import android.graphics.Bitmap
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.BloodGroups
import com.ameer.uyirmesh.core.ChildTag
import com.ameer.uyirmesh.core.Names
import com.ameer.uyirmesh.core.Preparedness
import com.ameer.uyirmesh.data.AlertItem
import com.ameer.uyirmesh.data.ChecklistItem
import com.ameer.uyirmesh.data.MissingItem
import com.ameer.uyirmesh.data.SafeItem
import com.ameer.uyirmesh.mesh.SafetyHub
import java.io.File
import java.io.FileOutputStream

/* ---------------- blood donor finder ---------------- */

/** Under a BLOOD alert: the group asked for and "I can donate" for donors whose group fits. */
@Composable
fun BloodAlertActions(a: AlertItem, nav: Nav) {
    val profile by Graph.store.profile.collectAsStateWithLifecycle()
    var offered by remember(a.id) { mutableStateOf(Graph.public.offeredBlood(a.id)) }
    Text(tr("blood_needed").replace("{g}", a.blood.ifBlank { "?" }), fontWeight = FontWeight.Bold, color = Palette.Red)
    when {
        a.mine -> {}
        offered -> Text(tr("blood_offered"), color = Palette.Green, fontWeight = FontWeight.SemiBold)
        profile.blood.isBlank() -> TonalButton(tr("blood_add_group"), Ic.Medical) { nav.go("profile") }
        BloodGroups.canDonate(profile.blood, a.blood) -> {
            Text(tr("blood_match").replace("{g}", profile.blood), style = MaterialTheme.typography.bodySmall)
            TonalButton(tr("blood_i_can_donate"), Ic.Blood) { offered = Graph.public.offerBlood(a) }
            Text(tr("blood_offer_privacy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Text(tr("blood_no_match").replace("{g}", profile.blood), style = MaterialTheme.typography.bodySmall)
    }
}

/** Blood group choice on the base's alert screen. */
@Composable
fun BloodGroupChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in BloodGroups.ALL + BloodGroups.ANY) {
            FilterChip(selected = selected == g, onClick = { onSelect(g) }, label = { Text(if (g == BloodGroups.ANY) tr("blood_any") else g) })
        }
    }
}

/* ---------------- missing people: possible matches ---------------- */

/** "Possible match: Lakshmi said she is safe at 10:30 (school shelter)" under a missing-person notice. */
@Composable
fun MissingMatches(m: MissingItem, safe: List<SafeItem>) {
    if (m.found) return
    val hits = remember(m.id, m.person, safe.size) { safe.filter { Names.similar(m.person, it.name) }.sortedByDescending { it.at }.take(3) }
    if (hits.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth().background(Palette.Green.copy(alpha = 0.12f)).padding(8.dp)) {
        Text(tr("possible_match"), fontWeight = FontWeight.Bold, color = Palette.Green)
        for (s in hits) {
            val place = s.place.substringAfterLast(':').ifBlank { s.message }
            Text("• ${s.name}: ${tr("st_safe")} · ${timeText(s.at)}" + if (place.isNotBlank()) " · $place" else "", style = MaterialTheme.typography.bodySmall)
        }
        Text(tr("possible_match_hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- preparedness checklist ---------------- */

@Composable
fun ChecklistScreen(nav: Nav) {
    val items by Graph.store.checklist.flow.collectAsStateWithLifecycle()
    val done = items.filter { it.done }.map { it.key }.toSet()
    val total = Preparedness.ITEMS.size
    val ready = Preparedness.ITEMS.count { it in done }
    Scaffold(topBar = { ScreenBar(tr("checklist_title"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("checklist_body"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(tr("checklist_progress").replace("{n}", ready.toString()).replace("{t}", total.toString()), fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { ready / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = if (ready == total) Palette.Green else Palette.Orange)
            }
            items(Preparedness.ITEMS, key = { it }) { key ->
                val checked = key in done
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(key, !checked) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle(key, it) })
                    Text(tr("prep_$key"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                ButtonRow {
                    TonalButton(tr("medical_card"), Ic.Medical, Modifier.weight(1f)) { nav.go("profile") }
                    TonalButton(tr("practice_title"), Ic.Game, Modifier.weight(1f)) { nav.go("practice") }
                }
                if (done.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(tr("checklist_reset"), Ic.Refresh) { Graph.store.checklist.clear() }
                }
            }
        }
    }
}

private fun toggle(key: String, on: Boolean) {
    Graph.store.checklist.upsert({ it.key }, ChecklistItem(key, on, System.currentTimeMillis()))
}

/* ---------------- practice game ---------------- */

/** One question of the practice game: three answers, the right one and why. */
private class PracticeQ(val n: Int, val right: Int)

private val PRACTICE = listOf(PracticeQ(1, 1), PracticeQ(2, 0), PracticeQ(3, 0), PracticeQ(4, 0), PracticeQ(5, 0), PracticeQ(6, 0), PracticeQ(7, 0), PracticeQ(8, 0))

/** Short quiz that teaches what to do and how the app helps (no network needed, silent SOS, signed alerts...). */
@Composable
fun PracticeScreen(nav: Nav) {
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Graph.prefs.getLong("practice_best").toInt()) }
    // Answer order is shuffled per question, so the right answer is not always in the same place.
    val order = remember(index) { (0..2).shuffled() }
    Scaffold(topBar = { ScreenBar(tr("practice_title"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (index >= PRACTICE.size) {
                Text(tr("practice_score").replace("{s}", score.toString()).replace("{t}", PRACTICE.size.toString()), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(tr("practice_best").replace("{s}", best.toString()), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(if (score == PRACTICE.size) tr("practice_perfect") else tr("practice_again_hint"))
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("practice_again"), Ic.Refresh) {
                    index = 0
                    picked = -1
                    score = 0
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("practice_drill"), Ic.Sos) {
                    Graph.prefs.update { it.copy(drill = true) }
                    nav.go("sos_form")
                }
                Text(tr("practice_drill_sub"), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val q = PRACTICE[index]
            Text("${index + 1} / ${PRACTICE.size}", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { index / PRACTICE.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text(tr("pg_q${q.n}"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            for (o in order) {
                val label = tr("pg_q${q.n}_" + "abc"[o])
                val color = when {
                    picked < 0 -> MaterialTheme.colorScheme.surfaceVariant
                    o == q.right -> Palette.Green.copy(alpha = 0.25f)
                    o == picked -> Palette.Red.copy(alpha = 0.2f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                InfoCard(color = color, onClick = if (picked < 0) ({
                    picked = o
                    if (o == q.right) score++
                }) else null) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (picked >= 0) {
                Spacer(Modifier.height(8.dp))
                Text(if (picked == q.right) tr("practice_right") else tr("practice_wrong"), fontWeight = FontWeight.Bold, color = if (picked == q.right) Palette.Green else Palette.Red)
                Text(tr("pg_q${q.n}_why"))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(tr("next"), Ic.Arrow) {
                    index++
                    picked = -1
                    if (index >= PRACTICE.size && score > best) {
                        best = score
                        Graph.prefs.putLong("practice_best", score.toLong())
                    }
                }
            }
        }
    }
}

/* ---------------- lost-child wristband ---------------- */

/** Parents make a QR for each child (print it or keep it in the child's pocket); whoever finds the child scans it. */
@Composable
fun ChildrenScreen(nav: Nav) {
    val ctx = LocalContext.current
    val children by Graph.store.children.flow.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { ScreenBar(tr("child_tag_title"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("child_tag_body"), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(tr("child_name"), name, { name = it.take(40) }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TonalButton(tr("make_qr"), Ic.Qr, enabled = name.isNotBlank()) {
                        shown = Graph.safety.registerChild(name.trim()).tagId
                        name = ""
                    }
                }
                Text(tr("child_tag_privacy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (children.isEmpty()) item { EmptyState(Ic.Child, tr("child_none")) }
            items(children.sortedByDescending { it.createdAt }, key = { it.tagId }) { c ->
                InfoCard(color = if (c.foundAt > 0) Palette.Green.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.Child, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        TonalButton(if (shown == c.tagId) tr("hide_qr") else tr("show_qr"), Ic.Qr) { shown = if (shown == c.tagId) null else c.tagId }
                    }
                    if (c.foundAt > 0) {
                        Text(tr("child_found_by").replace("{f}", c.finder.ifBlank { "?" }).replace("{t}", timeText(c.foundAt)), fontWeight = FontWeight.SemiBold, color = Palette.Green)
                        if (c.note.isNotBlank()) Text("\"${c.note}\"")
                        val where = distanceFromMe(c.lat, c.lon).removePrefix(" · ")
                        if (where.isNotBlank()) Text("📍 $where", style = MaterialTheme.typography.bodySmall)
                        if (c.finderPhone.isNotBlank()) TonalButton(tr("call") + " " + c.finderPhone, Ic.Call) { dial(ctx, c.finderPhone) }
                    }
                    if (shown == c.tagId) {
                        Spacer(Modifier.height(8.dp))
                        QrImage(c.qr, Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp))
                        Text(tr("child_qr_hint").replace("{n}", c.name), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        TonalButton(tr("share_qr"), Ic.Share, Modifier.fillMaxWidth()) { shareQr(ctx, c.qr, c.name) }
                    }
                }
            }
        }
    }
}

private fun shareQr(ctx: android.content.Context, qr: String, name: String) {
    try {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "uyirmesh_child_${name.filter { it.isLetterOrDigit() }.take(20)}.png")
        FileOutputStream(f).use { Qr.bitmap(qr).compress(Bitmap.CompressFormat.PNG, 100, it) }
        shareFile(ctx, f, "image/png", "UyirMesh child QR: $name")
    } catch (e: Exception) {
        Graph.note("share child qr", e)
    }
}

/** After scanning a child's QR: tell the parent's phone, with an optional note; the position is added. */
@Composable
fun ChildFoundForm(nav: Nav, qr: String) {
    val tag = remember(qr) {
        try {
            ChildTag.fromQr(qr)
        } catch (e: Exception) {
            null
        }
    }
    if (tag == null) {
        Text(tr("bad_qr"))
        return
    }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    InfoCard(color = Palette.Green.copy(alpha = 0.12f)) {
        Text(tr("child_found_q"), style = MaterialTheme.typography.labelMedium)
        Text(tag.childName, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(tr("child_found_body"), style = MaterialTheme.typography.bodySmall)
        if (!sent) {
            Field(tr("child_found_note"), note, { note = it.take(200) }, singleLine = false)
            PrimaryButton(tr("child_tell_parent"), Ic.Send, color = Palette.Green) { sent = Graph.safety.sendChildFound(tag, note.trim()) }
        } else {
            Text(tr("child_parent_told"), color = Palette.Green, fontWeight = FontWeight.Bold)
            Text(tr("child_stay_hint"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("back_home"), Ic.Home) { nav.replace("home") }
        }
    }
}

/* ---------------- Bluetooth sensors ---------------- */

@Composable
fun SensorsScreen(nav: Nav) {
    val sensors by Graph.store.sensors.flow.collectAsStateWithLifecycle()
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    Scaffold(topBar = { ScreenBar(tr("sensors_title"), { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(tr("sensors_body"), style = MaterialTheme.typography.bodyMedium)
                SwitchRow(tr("sensor_scan"), tr("sensor_scan_sub"), s.sensorScan) { v -> Graph.prefs.update { it.copy(sensorScan = v) } }
            }
            val list = sensors.sortedWith(compareBy({ !it.alarm }, { -it.receivedAt }))
            if (list.isEmpty()) item { EmptyState(Ic.Sensor, tr("sensors_none")) }
            items(list, key = { it.sensorId }) { r ->
                val stale = now - r.receivedAt > 30 * 60_000
                InfoCard(color = (if (r.alarm) Palette.Red else Palette.Teal).copy(alpha = if (stale) 0.05f else 0.14f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(SafetyHub.sensorText(r.kind, r.value, r.unit), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        if (r.alarm) Chip(tr("sensor_alarm"), Palette.Red)
                    }
                    Text(
                        "${tr("sensor_id")} ${r.sensorId}" + (if (r.battery >= 0) " · ${tr("battery_label")} ${r.battery}%" else "") +
                            " · ${agoText(r.receivedAt)} " + tr("ago") + (if (r.relayedBy.isNotBlank()) " · ${tr("via")} ${r.relayedBy}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val where = distanceFromMe(r.lat, r.lon).removePrefix(" · ")
                    if (where.isNotBlank()) Text("📍 $where", style = MaterialTheme.typography.bodySmall)
                    Text(tr("unverified_sensor"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(tr("sensors_hardware_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
