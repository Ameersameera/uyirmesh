package com.ameer.uyirmesh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.util.Perms

@Composable
fun OnboardingScreen(nav: Nav) {
    var step by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        when (step) {
            0 -> WelcomeStep { step = 1 }
            1 -> NameStep { step = 2 }
            else -> PermissionStep {
                Graph.prefs.update { it.copy(onboarded = true) }
                Graph.log("APP", "Setup finished")
                nav.replace("home")
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(next: () -> Unit) {
    val s = Graph.prefs.now
    Spacer(Modifier.height(24.dp))
    Icon(Ic.Network, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(12.dp))
    Text("UyirMesh", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(tr("tagline"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    Spacer(Modifier.height(24.dp))
    InfoCard { Text(tr("welcome_body")) }
    SectionTitle(tr("language"))
    ButtonRow {
        TonalButton("English", modifier = Modifier.weight(1f), enabled = s.lang != "en") { Graph.prefs.update { it.copy(lang = "en") } }
        TonalButton("தமிழ்", modifier = Modifier.weight(1f), enabled = s.lang != "ta") { Graph.prefs.update { it.copy(lang = "ta") } }
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(tr("next"), onClick = next)
}

@Composable
private fun ColumnScope.NameStep(next: () -> Unit) {
    val p = Graph.store.profile.value
    var name by remember { mutableStateOf(p.name.ifBlank { Graph.prefs.now.name }) }
    var phone by remember { mutableStateOf(p.phone.ifBlank { Graph.prefs.now.phone }) }
    var blood by remember { mutableStateOf(p.blood) }
    Spacer(Modifier.height(24.dp))
    Text(tr("about_you"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(tr("about_you_body"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    Field(tr("your_name"), name, { name = it.take(40) })
    Field(tr("phone_optional"), phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) }, number = true)
    Field(tr("blood_group_optional"), blood, { blood = it.uppercase().take(4) })
    Spacer(Modifier.height(20.dp))
    PrimaryButton(tr("next")) {
        Graph.store.setProfile(Graph.store.profile.value.copy(name = name.trim(), phone = phone.trim(), blood = blood.trim()))
        Graph.prefs.update { it.copy(name = name.trim(), phone = phone.trim()) }
        next()
    }
}

@Composable
private fun ColumnScope.PermissionStep(finish: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    // Re-check when coming back from a settings screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        val missing = Perms.missing(ctx, Perms.setup())
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    @Suppress("UNUSED_VARIABLE") val r = refresh
    val meshOk = Perms.meshReady(ctx)
    val micOk = Perms.hasMic(ctx)
    val notifOk = Perms.hasNotifications(ctx)
    val btOn = Perms.bluetoothOn(ctx)
    val locOn = Perms.locationOn(ctx)
    val wifiOn = Perms.wifiOn(ctx)
    val battery = Perms.ignoringBatteryOptimizations(ctx)

    Spacer(Modifier.height(24.dp))
    Text(tr("setup_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(tr("setup_body"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    CheckRow(tr("perm_nearby"), tr("perm_nearby_why"), meshOk) { launcher.launch(Perms.missing(ctx, Perms.mesh()).toTypedArray()) }
    CheckRow(tr("perm_mic"), tr("perm_mic_why"), micOk) { launcher.launch(arrayOf(Perms.MIC)) }
    if (Perms.notifications().isNotEmpty()) CheckRow(tr("perm_notif"), tr("perm_notif_why"), notifOk) { launcher.launch(Perms.notifications().toTypedArray()) }
    CheckRow(tr("bluetooth_on"), tr("bluetooth_on_why"), btOn) { Perms.open(ctx, Perms.bluetoothSettings()) }
    CheckRow(tr("location_on"), tr("location_on_why"), locOn) { Perms.open(ctx, Perms.locationSettings()) }
    CheckRow(tr("wifi_on"), tr("wifi_on_why"), wifiOn, optional = true) { Perms.open(ctx, Perms.wifiSettings()) }
    CheckRow(tr("battery_opt"), tr("battery_opt_why"), battery, optional = true) {
        try {
            ctx.startActivity(Perms.batteryOptimizationRequest(ctx))
        } catch (e: Exception) {
            Perms.open(ctx, Perms.batteryOptimizationList())
        }
    }
    if (!meshOk) {
        Text(tr("perm_denied_hint"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        SecondaryButton(tr("open_app_settings")) { Perms.open(ctx, Perms.appSettings(ctx)) }
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(if (meshOk) tr("start") else tr("start_anyway")) {
        MeshService.start(ctx, fromForeground = true)
        finish()
    }
}

@Composable
fun CheckRow(title: String, why: String, ok: Boolean, optional: Boolean = false, fix: () -> Unit) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok, warn = optional)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!ok) {
                Spacer(Modifier.width(8.dp))
                TonalButton(tr("fix"), onClick = fix)
            }
        }
    }
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
