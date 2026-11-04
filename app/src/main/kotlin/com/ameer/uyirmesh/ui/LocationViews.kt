package com.ameer.uyirmesh.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.FixChoice
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.util.Loc
import com.ameer.uyirmesh.util.Perms
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Why this phone's position may be wrong, and a one-tap fix: no location permission, only "approximate"
 * location allowed (Android moves it 1-3 km), location switched off, no GPS fix yet, or only a rough
 * network position. With [showWhenGood] it also says how exact a good position is.
 */
@Composable
fun LocationQualityCard(showWhenGood: Boolean = false) {
    val ctx = LocalContext.current
    val last by Graph.loc.lastFlow.collectAsStateWithLifecycle()
    var recheck by remember { mutableIntStateOf(0) }
    // Re-reads the permission and the location switch every 10 s, and at once after a fix was tried.
    val now = rememberNow(10_000) + recheck * 0L
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck++
        if (!Perms.hasLocation(ctx) || Perms.approximateOnly(ctx)) {
            // Android shows the question only a limited number of times; then it can be changed only in Settings.
            Perms.open(ctx, Perms.appSettings(ctx))
        } else {
            Graph.loc.restart()
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        recheck++
        Graph.loc.restart()
    }
    val acc = last?.let { Loc.accuracyOf(it) }
    val ageMs = last?.let { now - it.time } ?: Long.MAX_VALUE
    when {
        !Perms.hasLocation(ctx) ->
            Problem(tr("loc_no_permission"), tr("loc_allow")) { permLauncher.launch(Perms.location().toTypedArray()) }
        Perms.approximateOnly(ctx) ->
            Problem(tr("loc_approx_permission"), tr("loc_turn_on_precise")) { permLauncher.launch(Perms.location().toTypedArray()) }
        !Perms.locationOn(ctx) ->
            Problem(tr("loc_off"), tr("loc_turn_on")) { askToTurnOnLocation(ctx, settingsLauncher) }
        last == null || ageMs > 10 * 60_000 ->
            Problem(tr("loc_waiting"), null, Palette.Amber) {}
        !FixChoice.isPrecise(acc) ->
            Problem(tr("loc_rough").replace("{a}", accuracyText(acc)), tr("loc_improve"), Palette.Amber) { askToTurnOnLocation(ctx, settingsLauncher) }
        showWhenGood ->
            InfoCard(color = Palette.Green.copy(alpha = 0.12f)) {
                Text(tr("loc_good").replace("{a}", accuracyText(acc)).replace("{t}", agoText(last!!.time)), color = Palette.Green, fontWeight = FontWeight.SemiBold)
            }
    }
}

@Composable
private fun Problem(text: String, button: String?, color: Color = Palette.Red, onFix: () -> Unit) {
    InfoCard(color = color.copy(alpha = 0.14f)) {
        Text(text, fontWeight = FontWeight.SemiBold)
        if (button != null) {
            Spacer(Modifier.height(6.dp))
            TonalButton(button, Ic.MyLocation, Modifier.fillMaxWidth()) { onFix() }
        }
    }
}

/** "±8 m" or "±1.5 km"; "?" when unknown. */
fun accuracyText(accM: Double?): String = if (accM == null) "?" else "±" + Geo.formatDistance(accM)

/**
 * Asks Android to switch on location with high accuracy (GPS, plus Wi-Fi and mobile network help).
 * Shows the system's one-tap "Turn on" dialog when possible, otherwise opens the location settings.
 */
fun askToTurnOnLocation(ctx: Context, launcher: ActivityResultLauncher<IntentSenderRequest>) {
    val request = LocationSettingsRequest.Builder()
        .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
        .setAlwaysShow(true)
        .build()
    try {
        LocationServices.getSettingsClient(ctx).checkLocationSettings(request)
            .addOnSuccessListener { Graph.loc.restart() }
            .addOnFailureListener { e ->
                val resolvable = e as? ResolvableApiException
                if (resolvable != null) {
                    try {
                        launcher.launch(IntentSenderRequest.Builder(resolvable.resolution).build())
                        return@addOnFailureListener
                    } catch (_: Exception) {
                    }
                }
                Perms.open(ctx, Perms.locationSettings())
            }
    } catch (e: Exception) {
        Perms.open(ctx, Perms.locationSettings())
    }
}
