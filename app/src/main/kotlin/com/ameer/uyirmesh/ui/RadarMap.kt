package com.ameer.uyirmesh.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.LatLon
import kotlin.math.hypot
import kotlin.math.max

data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val color: Color,
    val label: String,
    /** 0 = dot, 1 = square (rescuer), 2 = triangle (report), 3 = base, 4 = me */
    val shape: Int = 0,
)

data class MapZone(val lat: Double, val lon: Double, val radiusM: Double, val color: Color, val label: String)

/** A path drawn on the map (e.g. a rescuer's recent positions). */
data class MapTrail(val points: List<LatLon>, val color: Color, val dashed: Boolean = false)

/**
 * Offline radar map: no map tiles or internet. Everything is drawn around the centre by distance and
 * compass direction (equirectangular projection), with range rings, heat and zones.
 */
@Composable
fun RadarMap(
    center: LatLon?,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    zones: List<MapZone> = emptyList(),
    trail: List<LatLon> = emptyList(),
    trails: List<MapTrail> = emptyList(),
    heat: List<LatLon> = emptyList(),
    selected: String? = null,
    onTap: (String) -> Unit = {},
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val origin = center ?: markers.firstOrNull()?.let { LatLon(it.lat, it.lon) } ?: LatLon(0.0, 0.0)
    val far = (markers.map { LatLon(it.lat, it.lon) } + trail + trails.flatMap { it.points } + heat + zones.map { LatLon(it.lat, it.lon) })
        .maxOfOrNull { Geo.distanceM(origin, it) } ?: 0.0
    val range = niceRange(max(far * 1.15, 150.0)) / zoom
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(markers, range) {
                detectTapGestures { tap ->
                    val r = size.width.coerceAtMost(size.height) / 2f * 0.92f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    var best: String? = null
                    var bestD = 36.dp.toPx()
                    for (m in markers) {
                        val (x, y) = Geo.toLocalMetres(origin, LatLon(m.lat, m.lon))
                        val px = cx + (x / range * r).toFloat()
                        val py = cy - (y / range * r).toFloat()
                        val d = hypot(px - tap.x, py - tap.y)
                        if (d < bestD) {
                            bestD = d
                            best = m.id
                        }
                    }
                    best?.let(onTap)
                }
            },
        ) {
            val r = size.minDimension / 2f * 0.92f
            if (r <= 1f) return@Canvas // not laid out yet (a zero-size gradient would throw)
            val c = Offset(size.width / 2f, size.height / 2f)
            fun project(lat: Double, lon: Double): Offset {
                val (x, y) = Geo.toLocalMetres(origin, LatLon(lat, lon))
                return Offset(c.x + (x / range * r).toFloat(), c.y - (y / range * r).toFloat())
            }
            drawCircle(bg, radius = r * 1.04f, center = c)
            // Heat: soft red glow where many SOS are.
            for (h in heat) {
                val p = project(h.lat, h.lon)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x66FF1744), Color(0x00FF1744)), center = p, radius = r * 0.16f),
                    radius = r * 0.16f, center = p,
                )
            }
            // Range rings.
            val text = Paint().apply {
                color = ink.toArgb()
                textSize = 11.dp.toPx()
                isAntiAlias = true
            }
            for (k in 1..4) {
                val rr = r * k / 4f
                drawCircle(ink.copy(alpha = 0.25f), radius = rr, center = c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                drawContext.canvas.nativeCanvas.drawText(Geo.formatDistance(range * k / 4), c.x + 4.dp.toPx(), c.y - rr + 12.dp.toPx(), text)
            }
            drawLine(ink.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
            drawLine(ink.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
            val north = Paint(text).apply {
                textSize = 14.dp.toPx()
                isFakeBoldText = true
                color = primary.toArgb()
            }
            drawContext.canvas.nativeCanvas.drawText("N", c.x - 5.dp.toPx(), c.y - r - 2.dp.toPx(), north)
            // Zones (danger areas, shelters).
            for (z in zones) {
                val p = project(z.lat, z.lon)
                val zr = (z.radiusM / range * r).toFloat().coerceAtLeast(6.dp.toPx())
                drawCircle(z.color.copy(alpha = 0.18f), radius = zr, center = p)
                drawCircle(z.color, radius = zr, center = p, style = Stroke(2.dp.toPx()))
            }
            // Breadcrumb trail.
            if (trail.size >= 2) {
                val path = Path()
                trail.forEachIndexed { i, t ->
                    val p = project(t.lat, t.lon)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color(0xFF1565C0), style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
            for (t in trails) {
                if (t.points.size < 2) continue
                val path = Path()
                t.points.forEachIndexed { i, pt ->
                    val p = project(pt.lat, pt.lon)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                val effect = if (t.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null
                drawPath(path, t.color, style = Stroke(3.dp.toPx(), pathEffect = effect))
            }
            // Markers.
            val label = Paint(text).apply { textSize = 11.dp.toPx() }
            for (m in markers) {
                val p = project(m.lat, m.lon)
                val s = if (m.id == selected) 11.dp.toPx() else 8.dp.toPx()
                drawMarker(m, p, s)
                if (m.label.isNotBlank()) {
                    label.color = m.color.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(m.label.take(14), p.x + s + 2.dp.toPx(), p.y + 4.dp.toPx(), label)
                }
            }
            if (center != null) drawCircle(primary, radius = 3.dp.toPx(), center = c)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
            SmallFloatingActionButton(onClick = { zoom = (zoom * 2f).coerceAtMost(64f) }) { Text("+") }
            SmallFloatingActionButton(onClick = { zoom = (zoom / 2f).coerceAtLeast(0.25f) }) { Text("−") }
        }
        Text(
            "${tr("range")}: ${Geo.formatDistance(range)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

private fun DrawScope.drawMarker(m: MapMarker, p: Offset, s: Float) {
    when (m.shape) {
        1 -> {
            drawRect(m.color, topLeft = Offset(p.x - s, p.y - s), size = androidx.compose.ui.geometry.Size(s * 2, s * 2))
            drawRect(Color.White, topLeft = Offset(p.x - s, p.y - s), size = androidx.compose.ui.geometry.Size(s * 2, s * 2), style = Stroke(2f))
        }
        2 -> {
            val path = Path().apply {
                moveTo(p.x, p.y - s * 1.2f)
                lineTo(p.x + s, p.y + s * 0.8f)
                lineTo(p.x - s, p.y + s * 0.8f)
                close()
            }
            drawPath(path, m.color)
        }
        3 -> {
            drawCircle(m.color, radius = s * 1.4f, center = p)
            drawCircle(Color.White, radius = s * 0.7f, center = p)
            drawCircle(m.color, radius = s * 0.35f, center = p)
        }
        4 -> {
            drawCircle(Color(0x551565C0), radius = s * 2f, center = p)
            drawCircle(Color(0xFF1565C0), radius = s * 0.9f, center = p)
            drawCircle(Color.White, radius = s * 0.9f, center = p, style = Stroke(2f))
        }
        else -> {
            drawCircle(m.color, radius = s, center = p)
            drawCircle(Color.White, radius = s, center = p, style = Stroke(2f))
        }
    }
}

private fun niceRange(m: Double): Double {
    val steps = doubleArrayOf(100.0, 200.0, 300.0, 500.0, 1_000.0, 2_000.0, 3_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0)
    return steps.firstOrNull { it >= m } ?: m
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MapLegend(items: List<Pair<Color, String>>) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for ((color, text) in items) {
            androidx.compose.foundation.layout.Row(Modifier.padding(end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).padding(0.dp)) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                }
                Text(" $text", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
