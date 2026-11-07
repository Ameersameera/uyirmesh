@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ameer.uyirmesh.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.ameer.uyirmesh.core.AlertKind
import com.ameer.uyirmesh.core.EmergencyKind
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import com.ameer.uyirmesh.core.Needs
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.core.VoiceForm
import com.ameer.uyirmesh.core.VoiceFormResult
import com.ameer.uyirmesh.data.AlertItem
import com.ameer.uyirmesh.sos.CheckIn
import com.ameer.uyirmesh.sos.SosForm
import com.ameer.uyirmesh.sos.VoiceDictation
import com.ameer.uyirmesh.util.MapsLink
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.delay

/* ---------------- picture-only SOS ---------------- */

data class PictureChoice(val key: String, val icon: ImageVector, val color: Color, val form: SosForm)

val PICTURE_CHOICES = listOf(
    PictureChoice("pic_trapped", Ic.Sos, Palette.Purple, SosForm(kind = EmergencyKind.TRAPPED)),
    PictureChoice("pic_injured", Ic.Injury, Palette.Red, SosForm(kind = EmergencyKind.MEDICAL, injured = true)),
    PictureChoice("pic_fire", Ic.Fire, Palette.Orange, SosForm(kind = EmergencyKind.FIRE)),
    PictureChoice("pic_flood", Ic.Boat, Palette.Blue, SosForm(kind = EmergencyKind.FLOOD, needs = listOf(Needs.BOAT))),
    PictureChoice("pic_water_food", Ic.Water, Palette.Teal, SosForm(kind = EmergencyKind.NEED, needs = listOf(Needs.WATER, Needs.FOOD))),
    PictureChoice("pic_sick", Ic.Hospital, Palette.RedDark, SosForm(kind = EmergencyKind.MEDICAL)),
)

/** Starts a short (3 s) countdown with the picture's form; the group size of the last SOS is kept. */
fun sendPicture(choice: PictureChoice): Boolean {
    val last = Graph.victim.lastForm()
    val form = choice.form.copy(
        people = last.people, children = last.children, elderly = last.elderly, disabled = last.disabled,
        pregnant = last.pregnant, animals = last.animals,
    )
    return Graph.trigger.start(Triggers.PICTURE, seconds = 3, form = form)
}

@Composable
fun PictureSosGrid(onSent: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in PICTURE_CHOICES.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in row) {
                    Card(
                        onClick = { if (sendPicture(c)) onSent() },
                        colors = CardDefaults.cardColors(containerColor = c.color),
                        modifier = Modifier.weight(1f).height(150.dp),
                    ) {
                        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(c.icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(tr(c.key), color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PictureSosScreen(nav: Nav) {
    Scaffold(topBar = { ScreenBar(tr("picture_sos"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(tr("picture_sos_hint"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            PictureSosGrid {
                nav.replace("home")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- check-in timer ---------------- */

fun minutesLabel(min: Int): String = if (min >= 60 && min % 60 == 0) L.t("hours_short").replace("{n}", (min / 60).toString()) else L.t("minutes_short").replace("{n}", min.toString())

@Composable
fun CheckInScreen(nav: Nav) {
    val ctx = LocalContext.current
    val st by CheckIn.state.collectAsStateWithLifecycle()
    var choice by remember { mutableIntStateOf(60) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st) {
        while (st != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Scaffold(topBar = { ScreenBar(tr("checkin"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val cur = st
            if (cur == null) {
                Text(tr("checkin_body"), modifier = Modifier.padding(vertical = 8.dp))
                SectionTitle(tr("checkin_every"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in CheckIn.CHOICES) {
                        FilterChip(selected = choice == m, onClick = { choice = m }, label = { Text(minutesLabel(m)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("checkin_start"), Ic.Timer, color = Palette.Teal) { CheckIn.start(ctx, choice) }
            } else {
                val left = (cur.deadline - now).coerceAtLeast(0)
                val urgent = left < 5 * 60_000
                InfoCard(color = (if (urgent) Palette.Orange else Palette.Teal).copy(alpha = 0.15f)) {
                    Text(tr("checkin_left"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%d:%02d:%02d".format(left / 3_600_000, (left / 60_000) % 60, (left / 1000) % 60),
                        fontSize = 48.sp, fontWeight = FontWeight.Black, color = if (urgent) Palette.Orange else Palette.Teal,
                    )
                    Text(tr("checkin_due").replace("{t}", CheckIn.timeOf(cur.deadline)), fontWeight = FontWeight.SemiBold)
                    Text(tr("checkin_every") + " " + minutesLabel(cur.intervalMin), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(tr("checkin_ok"), Ic.CheckCircle, Modifier.height(72.dp), color = Palette.Green) { CheckIn.ok(ctx) }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(tr("checkin_stop"), Ic.Stop) { CheckIn.stop(ctx) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------------- CPR helper ---------------- */

@Composable
fun CprScreen(nav: Nav) {
    var running by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var beat by remember { mutableStateOf(false) }
    DisposableEffect(running) {
        val tone = if (running) {
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        toneHolder = tone
        onDispose {
            try {
                tone?.release()
            } catch (_: Exception) {
            }
            toneHolder = null
        }
    }
    LaunchedEffect(running) {
        while (running) {
            count++
            beat = true
            try {
                toneHolder?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            } catch (_: Exception) {
            }
            delay(150)
            beat = false
            delay(CPR_BEAT_MS - 150)
        }
    }
    Scaffold(topBar = { ScreenBar(tr("cpr_helper"), { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            for (i in 1..6) Text("$i. ${tr("cpr_$i")}", modifier = Modifier.padding(vertical = 3.dp))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(200.dp).scale(if (beat) 1.12f else 1f).clip(CircleShape).background(if (running) Palette.Red else Palette.Grey),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr("cpr_count").replace("{n}", count.toString()), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        if (running && count > 0 && count % 30 == 0) Text(tr("cpr_breaths"), color = Color.White, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (running) {
                PrimaryButton(tr("cpr_stop"), Ic.Stop, color = Palette.Grey) { running = false }
            } else {
                PrimaryButton(tr("cpr_start"), Ic.Play, color = Palette.Red) {
                    count = 0
                    running = true
                }
            }
            Text(tr("cpr_note"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            ButtonRow {
                val ctx = LocalContext.current
                TonalButton(tr("call_108"), Ic.Call, Modifier.weight(1f)) { dial(ctx, "108") }
                TonalButton(tr("call_112"), Ic.Call, Modifier.weight(1f)) { dial(ctx, "112") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 110 compressions a minute (guidelines: 100-120). */
private const val CPR_BEAT_MS = 545L
private var toneHolder: ToneGenerator? = null

/* ---------------- nearest shelter and compass ---------------- */

/** The nearest announced shelter with a position, and its distance from this phone (metres). */
fun nearestShelter(alerts: List<AlertItem>, me: LatLon?): Pair<AlertItem, Double>? {
    if (me == null) return null
    return alerts.filter { it.kind == AlertKind.SHELTER && Geo.isValid(it.lat, it.lon) }
        .map { it to Geo.distanceM(me, LatLon(it.lat!!, it.lon!!)) }
        .minByOrNull { it.second }
}

@Composable
fun NearestShelterCard(nav: Nav) {
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val fix by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    val me = fix?.let { LatLon(it.latitude, it.longitude) }
    val (s, d) = nearestShelter(alerts, me) ?: return
    val free = if (s.capacity > 0) (s.capacity - s.occupancy).coerceAtLeast(0) else -1
    InfoCard(color = Palette.Teal.copy(alpha = 0.13f), onClick = { nav.go("go_shelter/${s.id}") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Shelter, null, tint = Palette.Teal, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("nearest_shelter") + ": " + s.title, fontWeight = FontWeight.Bold)
                Text(
                    Geo.formatDistance(d) + " " + Geo.compassName(Geo.bearingDeg(me!!, LatLon(s.lat!!, s.lon!!))) +
                        if (free >= 0) " · " + tr("free_space").replace("{n}", free.toString()) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Ic.Explore, null, tint = Palette.Teal)
        }
    }
}

@Composable
fun GoShelterScreen(nav: Nav, id: String) {
    val ctx = LocalContext.current
    val alerts by Graph.store.alerts.flow.collectAsStateWithLifecycle()
    val fix by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    val me = fix?.let { LatLon(it.latitude, it.longitude) }
    val shelter = alerts.firstOrNull { it.id == id && Geo.isValid(it.lat, it.lon) } ?: nearestShelter(alerts, me)?.first
    val heading = rememberHeading()
    Scaffold(topBar = { ScreenBar(tr("go_shelter"), { nav.back() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (shelter == null) {
                EmptyState(Ic.Shelter, tr("shelter_not_found"))
                return@Column
            }
            Text(shelter.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            if (shelter.message.isNotBlank()) Text(shelter.message, textAlign = TextAlign.Center)
            if (me == null) {
                Text(tr("need_location_first"), color = Palette.Orange, modifier = Modifier.padding(16.dp))
                return@Column
            }
            val target = LatLon(shelter.lat!!, shelter.lon!!)
            val bearing = Geo.bearingDeg(me, target)
            val dist = Geo.distanceM(me, target)
            Spacer(Modifier.height(16.dp))
            if (heading != null) {
                Icon(Ic.Navigate, null, tint = Palette.Teal, modifier = Modifier.size(200.dp).rotate((bearing - heading).toFloat()))
                Text(tr("compass_hint"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            } else {
                Text(tr("no_compass").replace("{d}", Geo.compassName(bearing)), textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
            Text(Geo.formatDistance(dist), fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.Teal)
            Text(Geo.compassName(bearing) + " · " + "%.0f°".format(bearing), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            TonalButton(tr("nav_google_maps"), Ic.Navigate, Modifier.fillMaxWidth()) { MapsLink.navigate(ctx, target.lat, target.lon, shelter.title) }
            Text(tr("gmaps_offline_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

/** Compass heading of the top of the phone in degrees (0 = north), or null when the phone has no compass. */
@Composable
fun rememberHeading(): Double? {
    val ctx = LocalContext.current
    var heading by remember { mutableFloatStateOf(Float.NaN) }
    var available by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            available = false
            onDispose { }
        } else {
            val rot = FloatArray(9)
            val orient = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    SensorManager.getOrientation(rot, orient)
                    val deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                    val norm = (deg + 360f) % 360f
                    // Smooth a little so the arrow does not shake.
                    heading = if (heading.isNaN()) norm else smoothAngle(heading, norm, 0.2f)
                }

                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }
    return if (!available || heading.isNaN()) null else heading.toDouble()
}

private fun smoothAngle(old: Float, new: Float, k: Float): Float {
    var diff = new - old
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return (old + k * diff + 360f) % 360f
}

/* ---------------- speak to fill (SOS form) ---------------- */

@Composable
fun VoiceFillCard(onResult: (String, VoiceFormResult) -> Unit) {
    val ctx = LocalContext.current
    val st by VoiceDictation.state.collectAsStateWithLifecycle()
    var mine by remember { mutableStateOf(false) }
    var filled by remember { mutableIntStateOf(-1) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            mine = true
            filled = -1
            VoiceDictation.start(ctx)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            VoiceDictation.stop()
            VoiceDictation.clear()
        }
    }
    LaunchedEffect(st) {
        val s = st
        if (mine && s is VoiceDictation.State.Done) {
            mine = false
            val r = VoiceForm.parse(s.text)
            filled = r.filled
            if (s.text.isNotBlank()) onResult(s.text, r)
        }
    }
    InfoCard(color = Palette.Blue.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Speak, null, tint = Palette.Blue, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("speak_to_fill"), fontWeight = FontWeight.Bold)
                Text(tr("speak_to_fill_sub"), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        when (val s = st) {
            is VoiceDictation.State.Loading -> Text(tr("voice_loading"))
            is VoiceDictation.State.Listening -> {
                Text(tr("listening_left").replace("{n}", s.secondsLeft.toString()), color = Palette.Red, fontWeight = FontWeight.Bold)
                if (s.text.isNotBlank()) Text("\"${s.text}\"")
                Spacer(Modifier.height(6.dp))
                TonalButton(tr("stop_and_fill"), Ic.Stop, Modifier.fillMaxWidth()) { VoiceDictation.stop() }
            }
            else -> {
                if (s is VoiceDictation.State.Done && filled >= 0) {
                    if (s.text.isNotBlank()) Text(tr("heard") + ": \"${s.text}\"", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (filled > 0) tr("filled_fields").replace("{n}", filled.toString()) else tr("voice_not_understood"),
                        color = if (filled > 0) Palette.Green else Palette.Orange, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (s is VoiceDictation.State.Failed) {
                    Text(
                        when (s.reason) {
                            VoiceDictation.FAIL_MODEL -> tr("voice_no_model")
                            VoiceDictation.FAIL_PERMISSION -> tr("voice_no_permission")
                            else -> tr("voice_mic_busy")
                        },
                        color = Palette.Orange,
                    )
                }
                PrimaryButton(tr("speak_now"), Ic.Mic, color = Palette.Blue) {
                    if (!Perms.hasMic(ctx)) {
                        micLauncher.launch(Perms.MIC)
                    } else {
                        mine = true
                        filled = -1
                        VoiceDictation.start(ctx)
                    }
                }
            }
        }
    }
}
