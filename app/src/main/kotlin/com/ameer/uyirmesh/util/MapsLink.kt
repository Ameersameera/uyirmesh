package com.ameer.uyirmesh.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.ui.L
import java.util.Locale

/**
 * Opens a position in Google Maps (or any other map app) with a normal Android link. This app needs no
 * Google API key and no internet for it; Google Maps itself shows the map without internet only for areas
 * that were downloaded beforehand (Google Maps > Offline maps), and gives only driving routes offline.
 */
object MapsLink {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    private fun f(v: Double) = String.format(Locale.US, "%.6f", v)

    /** A link that opens the pin in Google Maps on any phone or computer (used in SMS and on the laptop). */
    fun webUrl(lat: Double, lon: Double): String = "https://maps.google.com/?q=${f(lat)},${f(lon)}"

    /** Turn-by-turn driving route in Google Maps. */
    fun navigationUri(lat: Double, lon: Double): Uri = Uri.parse("google.navigation:q=${f(lat)},${f(lon)}&mode=d")

    /** A pin with a label, understood by Google Maps and by other map apps (Organic Maps, OsmAnd, ...). */
    fun pinUri(lat: Double, lon: Double, label: String): Uri {
        val clean = label.replace(Regex("[()\\r\\n]"), " ").trim().take(40)
        val name = if (clean.isEmpty()) "" else "(${Uri.encode(clean)})"
        return Uri.parse("geo:${f(lat)},${f(lon)}?q=${f(lat)},${f(lon)}$name")
    }

    /** Route in Google Maps; without Google Maps, a pin in any map app; without any, the coordinates are copied. */
    fun navigate(ctx: Context, lat: Double, lon: Double, label: String) {
        if (start(ctx, Intent(Intent.ACTION_VIEW, navigationUri(lat, lon)).setPackage(GOOGLE_MAPS))) return
        openInMapApp(ctx, lat, lon, label)
    }

    /** The position as a pin in the phone's map app (the user's default, or a chooser). */
    fun openInMapApp(ctx: Context, lat: Double, lon: Double, label: String) {
        if (start(ctx, Intent(Intent.ACTION_VIEW, pinUri(lat, lon, label)))) return
        // No map app at all: copy the coordinates so they can be read out over a phone or radio call.
        val text = "${f(lat)}, ${f(lon)}"
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("location", text))
        } catch (e: Exception) {
            Graph.note("copy location", e)
        }
        Toast.makeText(ctx, L.t("no_map_app_copied").replace("{p}", text), Toast.LENGTH_LONG).show()
    }

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
