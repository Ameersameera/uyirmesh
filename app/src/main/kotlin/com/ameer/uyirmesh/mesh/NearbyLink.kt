package com.ameer.uyirmesh.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-to-phone radio links using Google Nearby Connections (P2P_CLUSTER strategy:
 * many-to-many over Bluetooth, BLE and Wi-Fi, no internet, no mobile network, no router).
 * Every phone advertises and discovers at the same time and connects to up to [MAX_PEERS] neighbours.
 * Links are encrypted by Nearby; UyirMesh adds its own end-to-end encryption on top.
 */
class NearbyLink(
    context: Context,
    private val myId: String,
    private val roleChar: () -> Char,
    private val listener: Listener,
) {
    interface Listener {
        fun onBytes(fromDevice: String, bytes: ByteArray)
        fun onPeersChanged(peers: List<Peer>)
        fun onSendFailed(toDevice: String, tag: String)
        fun onLog(text: String)
    }

    data class Peer(val endpointId: String, val deviceId: String, val role: Char, val since: Long)

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    private val peers = ConcurrentHashMap<String, Peer>()
    private val names = ConcurrentHashMap<String, String>()
    private val found = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val nextTry = ConcurrentHashMap<String, Long>()
    private val sent = ConcurrentHashMap<Long, Pair<String, String>>()

    @Volatile var running = false
        private set
    @Volatile var advertising = false
        private set
    @Volatile var discovering = false
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var lowPower = false

    private var lastAdvertiseTry = 0L
    private var lastDiscoveryTry = 0L
    private var discoveryStartedAt = 0L
    private var discoveryPausedUntil = 0L

    private fun now() = System.currentTimeMillis()

    fun localName() = "UM1|$myId|${roleChar()}"

    fun peerList(): List<Peer> = peers.values.sortedBy { it.since }

    fun start() {
        if (running) return
        running = true
        lastError = ""
        startAdvertising()
        startDiscovery()
        main.removeCallbacks(maintenance)
        main.postDelayed(maintenance, 5_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(maintenance)
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (_: Exception) {
        }
        advertising = false
        discovering = false
        peers.clear()
        pending.clear()
        found.clear()
        sent.clear()
        listener.onPeersChanged(emptyList())
    }

    /** Re-advertise with a new name (after a role change). */
    fun restartAdvertising() {
        if (!running) return
        try {
            client.stopAdvertising()
        } catch (_: Exception) {
        }
        advertising = false
        startAdvertising()
    }

    private fun startAdvertising() {
        lastAdvertiseTry = now()
        try {
            val opts = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            client.startAdvertising(localName(), SERVICE_ID, lifecycle, opts)
                .addOnSuccessListener {
                    advertising = true
                    listener.onLog("Advertising as ${myId.take(6)}")
                }
                .addOnFailureListener { e ->
                    if (codeOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                        advertising = true
                    } else {
                        advertising = false
                        lastError = describe(e)
                        listener.onLog("Advertising failed: $lastError")
                    }
                }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
        }
    }

    private fun startDiscovery() {
        lastDiscoveryTry = now()
        try {
            val opts = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            client.startDiscovery(SERVICE_ID, discovery, opts)
                .addOnSuccessListener {
                    discovering = true
                    discoveryStartedAt = now()
                }
                .addOnFailureListener { e ->
                    if (codeOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                        discovering = true
                    } else {
                        discovering = false
                        lastError = describe(e)
                        listener.onLog("Discovery failed: $lastError")
                    }
                }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val dev = parseDevice(info.endpointName) ?: return
            if (dev == myId) return
            names[endpointId] = info.endpointName
            found[endpointId] = now()
            maybeConnect(endpointId, dev, fresh = true)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
        }
    }

    private fun maybeConnect(endpointId: String, dev: String, fresh: Boolean) {
        if (!running) return
        if (peers.containsKey(endpointId) || pending.containsKey(endpointId)) return
        if (peers.values.any { it.deviceId == dev }) return
        if (peers.size >= MAX_PEERS) return
        if (now() < (nextTry[dev] ?: 0L)) return
        if (fresh && myId > dev) {
            // The phone with the smaller id asks first, so both phones do not ask at the same moment.
            main.postDelayed({ if (found.containsKey(endpointId)) maybeConnect(endpointId, dev, fresh = false) }, 3_500)
            return
        }
        pending[endpointId] = now()
        try {
            client.requestConnection(localName(), endpointId, lifecycle)
                .addOnFailureListener { e ->
                    pending.remove(endpointId)
                    val code = codeOf(e)
                    if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
                    val n = (failures[dev] ?: 0) + 1
                    failures[dev] = n
                    nextTry[dev] = now() + minOf(60_000L, 2_000L * n * n)
                    listener.onLog("Link to ${dev.take(6)} failed (${describe(e)}), retrying")
                }
        } catch (e: Exception) {
            pending.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val dev = parseDevice(info.endpointName)
            if (dev == null || dev == myId || peers.values.any { it.deviceId == dev }) {
                try {
                    client.rejectConnection(endpointId)
                } catch (_: Exception) {
                }
                return
            }
            names[endpointId] = info.endpointName
            pending[endpointId] = now()
            try {
                client.acceptConnection(endpointId, payloads).addOnFailureListener { pending.remove(endpointId) }
            } catch (e: Exception) {
                pending.remove(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            val name = names[endpointId] ?: return
            val dev = parseDevice(name) ?: return
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                if (peers.values.any { it.deviceId == dev && it.endpointId != endpointId }) {
                    try {
                        client.disconnectFromEndpoint(endpointId)
                    } catch (_: Exception) {
                    }
                    return
                }
                peers[endpointId] = Peer(endpointId, dev, parseRole(name), now())
                found.remove(endpointId)
                failures.remove(dev)
                nextTry.remove(dev)
                listener.onLog("Connected to ${dev.take(6)} (${peers.size} nearby)")
                listener.onPeersChanged(peerList())
            } else {
                nextTry[dev] = now() + 4_000
            }
        }

        override fun onDisconnected(endpointId: String) {
            val p = peers.remove(endpointId) ?: return
            listener.onLog("Disconnected from ${p.deviceId.take(6)}")
            listener.onPeersChanged(peerList())
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val dev = peers[endpointId]?.deviceId ?: names[endpointId]?.let { parseDevice(it) } ?: return
            listener.onBytes(dev, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> sent.remove(update.payloadId)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                    sent.remove(update.payloadId)?.let { listener.onSendFailed(it.first, it.second) }
                else -> Unit
            }
        }
    }

    /** Sends one packet to a neighbour. [tag] (the packet id) comes back in [Listener.onSendFailed]. */
    fun send(deviceId: String, bytes: ByteArray, tag: String) {
        val p = peers.values.firstOrNull { it.deviceId == deviceId }
        if (p == null) {
            listener.onSendFailed(deviceId, tag)
            return
        }
        try {
            val payload = Payload.fromBytes(bytes)
            if (sent.size > 4_000) sent.clear()
            sent[payload.id] = deviceId to tag
            client.sendPayload(p.endpointId, payload).addOnFailureListener {
                sent.remove(payload.id)
                listener.onSendFailed(deviceId, tag)
            }
        } catch (e: Exception) {
            listener.onSendFailed(deviceId, tag)
        }
    }

    private val maintenance = object : Runnable {
        override fun run() {
            if (!running) return
            val t = now()
            if (!advertising && t - lastAdvertiseTry > 15_000) startAdvertising()
            if (lowPower && peers.isNotEmpty() && discovering && t - discoveryStartedAt > 20_000) {
                // Battery saver: listen for new phones 20 s out of every 80 s once connected.
                try {
                    client.stopDiscovery()
                } catch (_: Exception) {
                }
                discovering = false
                discoveryPausedUntil = t + 60_000
            }
            if (!discovering && t > discoveryPausedUntil && t - lastDiscoveryTry > 10_000) startDiscovery()
            for (ep in found.keys.toList()) {
                val dev = names[ep]?.let { parseDevice(it) } ?: continue
                maybeConnect(ep, dev, fresh = false)
            }
            pending.entries.removeIf { t - it.value > 30_000 }
            found.entries.removeIf { t - it.value > 10 * 60_000 }
            main.postDelayed(this, 5_000)
        }
    }

    private fun codeOf(e: Exception): Int? = (e as? ApiException)?.statusCode

    private fun describe(e: Exception): String {
        val code = codeOf(e) ?: return e.message ?: e.javaClass.simpleName
        val text = try {
            ConnectionsStatusCodes.getStatusCodeString(code)
        } catch (_: Exception) {
            "code $code"
        }
        return "$text ($code)"
    }

    companion object {
        const val SERVICE_ID = "com.ameer.uyirmesh.mesh"
        const val MAX_PEERS = 6
        private val STRATEGY = Strategy.P2P_CLUSTER

        fun parseDevice(name: String): String? {
            val parts = name.split('|')
            if (parts.size < 2 || parts[0] != "UM1") return null
            val id = parts[1]
            return if (id.length == 16 && id.all { Character.digit(it, 16) >= 0 }) id else null
        }

        fun parseRole(name: String): Char = name.split('|').getOrNull(2)?.firstOrNull() ?: 'P'
    }
}
