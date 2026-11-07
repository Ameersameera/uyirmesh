package com.ameer.uyirmesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.SensorPayload
import com.ameer.uyirmesh.util.Perms

/**
 * Listens for UyirMesh sensor nodes (for example an ESP32 with a water-level sensor, see hardware/) over Bluetooth
 * LE and passes their readings into the mesh. The node only advertises: no pairing, no connection. A scan filter
 * on the manufacturer data keeps the radio work small and lets Android deliver results with the screen off.
 *
 * Advert: manufacturer data with company id 0xFFFF (the id Bluetooth reserves for tests and prototypes), then
 * "UM", version 1, kind, value (int16), alarm, battery, 4-byte sensor id ([SensorPayload.fromAdvert]).
 */
class SensorScanner(private val ctx: Context) {
    private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handle(r)
        }

        override fun onScanFailed(errorCode: Int) {
            running = false
            Graph.log("SENSOR", "Bluetooth sensor scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 31 && !Perms.has(ctx, android.Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
            val filter = ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID, byteArrayOf('U'.code.toByte(), 'M'.code.toByte()), byteArrayOf(-1, -1))
                .build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            scanner.startScan(listOf(filter), settings, callback)
            running = true
            Graph.log("SENSOR", "Listening for Bluetooth sensor nodes")
        } catch (e: Exception) {
            Graph.note("sensor scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Graph.note("sensor scan stop", e)
        }
    }

    private fun handle(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        val reading = SensorPayload.fromAdvert(data, System.currentTimeMillis()) ?: return
        try {
            Graph.safety.relaySensor(reading)
        } catch (e: Exception) {
            Graph.note("sensor reading", e)
        }
    }

    companion object {
        /** Bluetooth SIG company id reserved for tests and prototypes. */
        const val COMPANY_ID = 0xFFFF
    }
}
