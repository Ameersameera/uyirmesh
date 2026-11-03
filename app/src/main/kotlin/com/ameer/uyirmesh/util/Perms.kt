package com.ameer.uyirmesh.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Which runtime permissions each feature needs on this Android version. */
object Perms {

    /**
     * Needed by Google Nearby Connections to advertise, discover and connect (per Android version, as in
     * Google's Nearby Connections setup guide): location up to Android 12L, Bluetooth permissions from
     * Android 12, nearby Wi-Fi devices from Android 13.
     */
    fun mesh(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= 29) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun location(): List<String> = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun notifications(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    const val MIC = Manifest.permission.RECORD_AUDIO
    const val CAMERA = Manifest.permission.CAMERA

    /** Everything asked for during setup (the camera is asked for only when it is used). */
    fun setup(): List<String> = (mesh() + location() + notifications() + MIC).distinct()

    fun has(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun missing(ctx: Context, list: List<String>): List<String> = list.filterNot { has(ctx, it) }

    fun meshReady(ctx: Context) = missing(ctx, mesh()).isEmpty()
    fun hasLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasMic(ctx: Context) = has(ctx, MIC)
    fun hasCamera(ctx: Context) = has(ctx, CAMERA)
    fun hasNotifications(ctx: Context) = notifications().all { has(ctx, it) }

    @SuppressLint("MissingPermission")
    fun bluetoothOn(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (e: Exception) {
        false
    }

    fun wifiOn(ctx: Context): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    } catch (e: Exception) {
        false
    }

    fun locationOn(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        false
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    /* Intents that open the right settings page. */

    fun bluetoothSettings() = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    fun wifiSettings() = Intent(Settings.ACTION_WIFI_SETTINGS)
    fun locationSettings() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun appSettings(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    @SuppressLint("BatteryLife")
    fun batteryOptimizationRequest(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun batteryOptimizationList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Opens a settings screen, falling back to the app's own page when a phone lacks it. */
    fun open(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                ctx.startActivity(appSettings(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }
}
