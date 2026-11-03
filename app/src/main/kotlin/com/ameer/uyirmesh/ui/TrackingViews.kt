package com.ameer.uyirmesh.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ameer.uyirmesh.base.TeamApproach
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.Tracking
import com.ameer.uyirmesh.util.MapsLink
import kotlinx.coroutines.delay

/** The current time, refreshed every [periodMs] so "2 min ago" texts stay right while a screen is open. */
@Composable
fun rememberNow(periodMs: Long = 15_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** "Navigate in Google Maps" and "Open in map app" for one position. */
@Composable
fun MapButtons(lat: Double, lon: Double, label: String, note: Boolean = true) {
    val ctx = LocalContext.current
    Spacer(Modifier.height(6.dp))
    TonalButton(tr("nav_google_maps"), Ic.Navigate, Modifier.fillMaxWidth()) { MapsLink.navigate(ctx, lat, lon, label) }
    Spacer(Modifier.height(6.dp))
    TonalButton(tr("open_map_app"), Ic.Map, Modifier.fillMaxWidth()) { MapsLink.openInMapApp(ctx, lat, lon, label) }
    if (note) Text(tr("gmaps_offline_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun trendLabel(a: Tracking.Approach): Pair<String, Color> = when (a.trend) {
    Tracking.Trend.CLOSER -> tr("trend_closer") to Palette.Green
    Tracking.Trend.ARRIVED -> tr("trend_arrived") to Palette.Green
    Tracking.Trend.SAME -> tr("trend_same") to Palette.Amber
    Tracking.Trend.STILL -> tr("trend_still").replace("{n}", a.stillMin.toString()) to Palette.Orange
    Tracking.Trend.AWAY -> tr("trend_away") to Palette.Red
    Tracking.Trend.UNKNOWN -> tr("trend_unknown") to Palette.Grey
}

/** Base case screen: is each rescuer of the assigned team really on the way to the victim? */
@Composable
fun TrackingSection(team: String, hasVictimGps: Boolean, approaches: List<TeamApproach>, now: Long) {
    SectionTitle("${tr("tracking_title")} · ${tr("team")} $team")
    when {
        !hasVictimGps -> Text(tr("tracking_no_victim_gps"), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
        approaches.isEmpty() -> Text(tr("tracking_none").replace("{t}", team), style = MaterialTheme.typography.bodySmall)
    }
    for (a in approaches) TrackingCard(a, now)
    Text(tr("positions_signed"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TrackingCard(a: TeamApproach, now: Long) {
    val ap = a.approach
    val (label, color) = trendLabel(ap)
    val ageMin = ((now - ap.at) / 60_000).coerceAtLeast(0)
    InfoCard(color = color.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.rescuer.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Chip(label, color)
        }
        Text(tr("from_victim").replace("{d}", Geo.formatDistance(ap.distanceM)), fontSize = 20.sp, fontWeight = FontWeight.Black)
        val earlier = ap.earlierM
        if (earlier != null && ap.trend != Tracking.Trend.UNKNOWN) {
            Text(
                tr("was_earlier").replace("{d}", Geo.formatDistance(earlier)).replace("{n}", ap.overMin.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            listOfNotNull(
                if (ap.etaMin > 0) "ETA ${ap.etaMin} min" else null,
                tr("position_age").replace("{a}", agoText(ap.at)),
                if (a.rescuer.battery >= 0) "${tr("battery")} ${a.rescuer.battery}%" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        if (ageMin >= 5) {
            Text(tr("position_old").replace("{n}", ageMin.toString()), color = Palette.Orange, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
