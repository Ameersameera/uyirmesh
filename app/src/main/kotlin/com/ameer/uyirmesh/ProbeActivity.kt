package com.ameer.uyirmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.ameer.uyirmesh.core.Ids
import com.google.android.gms.location.LocationServices
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Strategy
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.vosk.LibVosk
import org.vosk.LogLevel

/** Temporary: checks that every library resolves and compiles in CI. */
class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = Nearby.getConnectionsClient(this)
        val strategy = Strategy.P2P_CLUSTER
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val qr = QRCodeWriter().encode("UyirMesh", BarcodeFormat.QR_CODE, 64, 64)
        val status = NanoHTTPD.Response.Status.OK
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val info = "${client.hashCode()} $strategy ${fused.hashCode()} ${qr.width} $status ${Ids.newDeviceId()}"
        setContent {
            MaterialTheme {
                Icon(Icons.Filled.Radar, null)
                Icon(Icons.Filled.Campaign, null)
                Icon(Icons.Filled.FlashlightOn, null)
                Icon(Icons.Filled.QrCodeScanner, null)
                Icon(Icons.Filled.MedicalServices, null)
                Text(info)
            }
        }
    }
}
