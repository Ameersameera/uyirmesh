package com.ameer.uyirmesh.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.PacketCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GatewayStatus(
    val online: Boolean = false,
    val lastSync: Long = 0,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val error: String = "",
)

/**
 * Internet relay: the moment any phone in the mesh has a working internet connection, it uploads the packets in its
 * store-and-forward buffer to a small relay server and downloads the packets other phones uploaded. Two mesh islands
 * with one connected phone each are joined this way, and a control room far away can receive SOS messages.
 *
 * Packets stay exactly as they are on the mesh: SOS messages remain encrypted for the base and replies remain signed,
 * so the server (and anyone watching the connection) sees only packet ids, types and phone ids.
 * The relay server is server/gateway_server.py in this project.
 */
class Gateway(private val ctx: Context) {
    private val uploaded = LinkedHashSet<String>()
    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    fun hasInternet(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    /** One upload + download round. Call it from a background thread every 30 seconds or so. */
    fun cycle() {
        val s = Graph.prefs.now
        val base = s.gatewayUrl.trim().trimEnd('/')
        if (!s.gateway || base.isEmpty()) return
        if (!hasInternet()) {
            _status.value = _status.value.copy(online = false, error = "")
            return
        }
        try {
            val pending = Graph.mesh.engine.buffered { it.idHex !in uploaded }.take(MAX_UP)
            var up = 0
            if (pending.isNotEmpty()) {
                post("$base/up", pack(pending.map { PacketCodec.encode(it) }))
                pending.forEach { remember(it.idHex) }
                up = pending.size
            }
            val since = Graph.prefs.getLong(KEY_SINCE)
            val (next, packets) = get("$base/down?since=$since&max=$MAX_DOWN")
            for (b in packets) Graph.mesh.onBytes(LINK_ID, b)
            Graph.prefs.putLong(KEY_SINCE, next)
            val st = _status.value
            _status.value = GatewayStatus(true, System.currentTimeMillis(), st.uploaded + up, st.downloaded + packets.size, "")
            if (up > 0 || packets.isNotEmpty()) Graph.log("NET", "Internet relay: sent $up, received ${packets.size} packet(s)")
        } catch (e: Exception) {
            _status.value = _status.value.copy(online = true, error = e.javaClass.simpleName + " " + (e.message ?: "").take(80))
        }
    }

    private fun remember(id: String) {
        uploaded.add(id)
        while (uploaded.size > 5_000) uploaded.remove(uploaded.first())
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "UyirMesh-relay")
    }

    private fun post(url: String, body: ByteArray) {
        val c = open(url)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(body) }
            if (c.responseCode !in 200..299) throw IllegalStateException("upload HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): Pair<Long, List<ByteArray>> {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("download HTTP ${c.responseCode}")
            val next = c.getHeaderField("X-Next")?.toLongOrNull() ?: 0L
            val body = c.inputStream.use { it.readBytes() }
            return next to unpack(body)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** Neighbour name used for packets that came over the internet. */
        const val LINK_ID = "INTERNET"
        private const val KEY_SINCE = "gw_since"
        private const val MAX_UP = 100
        private const val MAX_DOWN = 60

        /** Batch format used by the relay server: for each packet a 4-byte big-endian length, then the packet. */
        fun pack(packets: List<ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                for (p in packets) {
                    out.writeInt(p.size)
                    out.write(p)
                }
            }
            return bos.toByteArray()
        }

        fun unpack(body: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            DataInputStream(body.inputStream()).use { inp ->
                while (inp.available() >= 4) {
                    val n = inp.readInt()
                    if (n <= 0 || n > PacketCodec.MAX_PACKET) break
                    val b = ByteArray(n)
                    inp.readFully(b)
                    out.add(b)
                }
            }
            return out
        }
    }
}
