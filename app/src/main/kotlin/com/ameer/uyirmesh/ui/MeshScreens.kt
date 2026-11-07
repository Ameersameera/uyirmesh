@file:OptIn(ExperimentalMaterial3Api::class)

package com.ameer.uyirmesh.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.base.Reports
import com.ameer.uyirmesh.core.MediaLevel
import com.ameer.uyirmesh.core.SecureZip
import com.ameer.uyirmesh.core.SosChecks
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.mesh.MeshService
import kotlinx.coroutines.delay

/* ---------------- base contact and power ---------------- */

/** "Base Salem Control reached through 2 phones · heard 3 min ago · 1.2 km away", or that it was not heard yet. */
@Composable
fun baseContactText(): String {
    val contact by Graph.health.base.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val c = contact ?: return tr("base_not_heard")
    val name = c.name.ifBlank { tr("rescue_base") }
    val ago = remember(c.at, tick) { agoText(c.at) }
    val main = if (c.hops <= 0) tr("base_direct") else tr("base_via").replace("{h}", c.hops.toString())
    val dist = Graph.health.distanceToBase()?.let { " · " + tr("base_distance").replace("{d}", SosChecks.distanceText(it)) } ?: ""
    return main.replace("{b}", name).replace("{a}", ago) + dist
}

/** One line about battery-aware relaying, or nothing when the phone relays everything. */
@Composable
fun powerNote(): String? {
    val p by Graph.health.power.collectAsStateWithLifecycle()
    return when (p.relayMaxQos) {
        2 -> tr("power_relay_2")
        1 -> tr("power_relay_1")
        else -> null
    }
}

/** Shown where voice notes and photos are sent, when they will be made smaller for a weak link. */
@Composable
fun WeakLinkNote() {
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val level = remember(stats.peers, stats.waitingOwn) { Graph.health.mediaLevel() }
    if (level != MediaLevel.NORMAL) {
        Text(tr("media_weak"), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
    }
}

/* ---------------- relay mode ---------------- */

/**
 * An old phone left on a charger as a fixed relay: dark screen that stays on, large numbers, and relaying of
 * everything for longer on battery. Leaving the screen ends relay mode.
 */
@Composable
fun RelayModeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val stats by Graph.mesh.stats.collectAsStateWithLifecycle()
    val power by Graph.health.power.collectAsStateWithLifecycle()
    var heard by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        Graph.prefs.update { it.copy(relayMode = true) }
        Graph.health.refreshPower()
        Graph.mesh.applyRole()
        if (!Graph.mesh.isRunning) MeshService.start(ctx, fromForeground = true)
        view.keepScreenOn = true
        // Dim the screen to save power (it stays on so the phone is not put to sleep by the system).
        val window = (ctx as? Activity)?.window
        val oldBrightness = window?.attributes?.screenBrightness ?: -1f
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.05f } }
        Graph.log("MESH", "Relay mode on")
        onDispose {
            view.keepScreenOn = false
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = oldBrightness } }
            Graph.prefs.update { it.copy(relayMode = false) }
            Graph.health.refreshPower()
            Graph.mesh.applyRole()
            Graph.log("MESH", "Relay mode off")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            heard = Graph.mesh.phonesHeard()
            Graph.health.refreshPower()
            delay(5_000)
        }
    }
    val dim = Color(0xFF9AA4AE)
    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Ic.Tower, null, tint = if (stats.peers > 0) Palette.Green else Palette.Amber)
        Text(tr("relay_running"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        RelayNumber(stats.peers.toString(), tr("nearby_phones"), dim)
        RelayNumber((stats.metrics["relayed"] ?: 0L).toString(), tr("c_relayed"), dim)
        RelayNumber(stats.buffer.toString(), tr("packets_stored"), dim)
        RelayNumber(heard.toString(), tr("phones_heard"), dim)
        RelayNumber(
            if (power.battery >= 0) "${power.battery}%" else "-",
            tr("battery_label") + if (power.charging) " · " + tr("charging") else "",
            if (power.charging || power.battery > 30) dim else Palette.Orange,
        )
        Spacer(Modifier.height(12.dp))
        Text(baseContactText(), color = dim, style = MaterialTheme.typography.bodyMedium)
        powerNote()?.let { Text(it, color = Palette.Orange, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(20.dp))
        Text(tr("relay_hint"), color = dim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        TonalButton(tr("relay_stop"), Ic.Stop) { nav.back() }
    }
}

@Composable
private fun RelayNumber(value: String, label: String, labelColor: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text(label, color = labelColor, style = MaterialTheme.typography.bodySmall)
    }
}

/* ---------------- settings ---------------- */

/** Automatic power saving and deleting old data (Settings screen). */
@Composable
fun PowerAndDataSettings(nav: Nav) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    SwitchRow(tr("auto_power"), tr("auto_power_sub"), s.autoPowerSave) { v ->
        Graph.prefs.update { it.copy(autoPowerSave = v) }
        Graph.health.refreshPower()
        Graph.mesh.applyRole()
    }
    MenuTile(Ic.Tower, tr("relay_mode"), tr("relay_mode_sub"), Palette.Teal) { nav.go("relay") }
    SectionTitle(tr("auto_delete"))
    Text(tr("auto_delete_sub"), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in listOf(0, 7, 30, 90)) {
            FilterChip(
                selected = s.autoDeleteDays == d,
                onClick = { Graph.prefs.update { it.copy(autoDeleteDays = d) } },
                label = { Text(if (d == 0) tr("keep") else tr("days_n").replace("{n}", d.toString())) },
            )
        }
    }
    if (s.autoDeleteDays > 0) {
        val ctx = LocalContext.current
        TonalButton(tr("delete_now"), Ic.Delete) {
            val n = com.ameer.uyirmesh.data.Cleanup.run()
            Toast.makeText(ctx, L.get(Graph.prefs.now.lang, "deleted_n").replace("{n}", n.toString()), Toast.LENGTH_SHORT).show()
        }
    }
}

/* ---------------- base: plan, ETA, secure export ---------------- */

/** "Assign all (best plan)" above the case list when cases wait and teams exist. */
@Composable
fun AssignAllButton() {
    val ctx = LocalContext.current
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val waiting = cases.count { it.status == CaseStatus.NEW && it.handledBy.isBlank() }
    if (waiting == 0 || Graph.base.teams().isEmpty()) return
    TonalButton(tr("assign_all").replace("{n}", waiting.toString()), Ic.Task, Modifier.fillMaxWidth()) {
        val n = Graph.base.autoAssign(by = "dispatcher (best plan)")
        val lang = Graph.prefs.now.lang
        Toast.makeText(ctx, if (n > 0) L.get(lang, "assign_all_done").replace("{n}", n.toString()) else L.get(lang, "assign_none"), Toast.LENGTH_SHORT).show()
    }
    Text(tr("assign_all_sub"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the ETA model learned from finished rescues (teams tab). */
@Composable
fun LearnedEtaNote() {
    val cases by Graph.store.cases.flow.collectAsStateWithLifecycle()
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val model = remember(cases, s.speedKmh) { Graph.base.etaModel() }
    Text(
        if (model.sampleCount == 0) tr("learned_eta_none")
        else tr("learned_eta").replace("{n}", model.sampleCount.toString()).replace("{k}", String.format(java.util.Locale.US, "%.1f", model.kmh)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Asks for a password and shares the cases (CSV) and report (PDF) in an AES-256 encrypted ZIP. */
@Composable
fun SecureExportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val mismatch = tr("export_mismatch")
    val short = tr("export_short")
    val failed = tr("export_failed")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("secure_export")) },
        text = {
            Column {
                Field(tr("export_password"), pw, { pw = it.take(64); error = "" }, password = true)
                Field(tr("export_password2"), pw2, { pw2 = it.take(64); error = "" }, password = true)
                if (error.isNotBlank()) Text(error, color = Palette.Red, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(tr("export_hint"), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pw.length < SecureZip.MIN_PASSWORD -> error = short
                    pw != pw2 -> error = mismatch
                    else -> {
                        val f = Reports.writeSecureZip(ctx, pw)
                        if (f == null) {
                            error = failed
                        } else {
                            shareFile(ctx, f, "application/zip", "UyirMesh cases (encrypted)")
                            onDismiss()
                        }
                    }
                }
            }) { Text(tr("export_make")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

/** Crowd count and power lines for the network screen. */
@Composable
fun NetworkHealthCard() {
    val power by Graph.health.power.collectAsStateWithLifecycle()
    var heard by remember { mutableIntStateOf(Graph.mesh.phonesHeard()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            heard = Graph.mesh.phonesHeard()
        }
    }
    InfoCard {
        Text(baseContactText(), fontWeight = FontWeight.SemiBold)
        KeyValue(tr("phones_heard"), heard.toString())
        KeyValue(tr("battery_label"), (if (power.battery >= 0) "${power.battery}%" else "-") + if (power.charging) " · " + tr("charging") else "")
        powerNote()?.let { Text(it, color = Palette.Orange, style = MaterialTheme.typography.bodySmall) }
    }
}

