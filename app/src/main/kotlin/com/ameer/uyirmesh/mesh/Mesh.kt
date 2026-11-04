package com.ameer.uyirmesh.mesh

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
import com.ameer.uyirmesh.BuildConfig
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Authenticity
import com.ameer.uyirmesh.core.Drop
import com.ameer.uyirmesh.core.HeartbeatPayload
import com.ameer.uyirmesh.core.PositionAuth
import com.ameer.uyirmesh.core.MeshEngine
import com.ameer.uyirmesh.core.Outbox
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.PinnedBaseKeys
import com.ameer.uyirmesh.core.Roles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class MeshStats(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val peers: Int = 0,
    val buffer: Int = 0,
    val waiting: Int = 0,
    val waitingOwn: Int = 0,
    val metrics: Map<String, Long> = emptyMap(),
    val lastError: String = "",
)

/**
 * The running mesh: Nearby radio links + relay engine + packet handling.
 * Packets are handled one at a time on a dedicated thread so the order of events is kept.
 */
class Mesh(private val ctx: Context, myId: String) : NearbyLink.Listener {
    val engine = MeshEngine(myId)
    /** Builds this phone's packets (encryption and signatures). Replaced only by [usePinnedForTest]. */
    @Volatile var outbox = Outbox(myId, Graph.pinned)
        private set
    val link = NearbyLink(ctx, myId, { roleChar() }, this)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-worker") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker + CrashGuard)
    private var loops: Job? = null

    private val _peers = MutableStateFlow<List<NearbyLink.Peer>>(emptyList())
    val peers: StateFlow<List<NearbyLink.Peer>> = _peers
    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats

    @Volatile private var kick = false
    private var lastDropLog = 0L

    init {
        engine.validator = { p -> Authenticity.check(Graph.pinned, p, System.currentTimeMillis()) }
        engine.onFirstHandOff = { p, n -> Graph.victim.onHandedOff(p, n) }
        engine.onStored = { p ->
            if (p.src == engine.myId) Graph.log("MESH", "No phone nearby: ${p.type} ${p.shortId} stored, will send when one comes in range")
        }
        engine.onStoredForwarded = { p, waited ->
            Graph.log("MESH", "Stored ${p.type} ${p.shortId} forwarded after ${waited / 1000}s")
        }
        applyRole()
    }

    private fun roleChar(): Char = when (Graph.prefs.now.role) {
        Roles.BASE -> 'B'
        Roles.RESCUER -> 'R'
        else -> 'P'
    }

    /** Updates what this phone reads (base / rescuer team) and the packet options. */
    fun applyRole() {
        val s = Graph.prefs.now
        engine.isBase = s.role == Roles.BASE && Graph.base.hasKeys()
        val bundle = if (s.role == Roles.RESCUER) Graph.rescuer.bundle() else null
        engine.isRescuer = bundle != null
        engine.teams = if (bundle != null) setOf(bundle.cert.team) else emptySet()
        outbox.ttl = s.ttl
        outbox.drill = s.drill
        link.lowPower = s.batterySaver
        val blocked = blockedPeers()
        if (blocked != link.blocked) link.blocked = blocked
    }

    fun blockedPeers(): Set<String> =
        (Graph.prefs.getString("blockedPeers") ?: "").split(',').filter { it.length == 16 }.toSet()

    /** Demo helper: refuse (or allow again) the direct radio link to one phone. */
    fun setBlocked(deviceId: String, block: Boolean) {
        val set = blockedPeers().toMutableSet()
        if (block) set.add(deviceId) else set.remove(deviceId)
        Graph.prefs.putString("blockedPeers", set.joinToString(","))
        link.blocked = set
        Graph.log("LINK", (if (block) "Blocked" else "Allowed") + " direct link to ${deviceId.take(6)}")
    }

    fun onRoleChanged() {
        applyRole()
        link.restartAdvertising()
    }

    @Synchronized
    fun start() {
        if (loops != null) return
        link.start()
        loops = scope.launch {
            launch { tickLoop() }
            launch { heartbeatLoop() }
        }
        Graph.log("MESH", "Mesh started (device ${engine.myId.take(6)})")
    }

    @Synchronized
    fun stop() {
        loops?.cancel()
        loops = null
        link.stop()
        publishStats()
        Graph.log("MESH", "Mesh stopped")
    }

    val isRunning: Boolean get() = loops != null

    /** Queue a packet made on this phone. */
    fun send(p: Packet) {
        engine.originate(p)
        kick = true
    }

    private suspend fun tickLoop() {
        var n = 0
        while (true) {
            try {
                val out = engine.tick()
                for (s in out) link.send(s.neighbour, s.bytes, s.packet.idHex)
                if (n++ % 4 == 0) publishStats()
            } catch (e: Exception) {
                Graph.note("mesh tick", e)
            }
            val wait = if (kick) 50L else 250L
            kick = false
            delay(wait)
        }
    }

    private suspend fun heartbeatLoop() {
        delay(8_000)
        while (true) {
            try {
                sendHeartbeat()
            } catch (e: Exception) {
                Graph.note("heartbeat", e)
            }
            delay(if (Graph.prefs.now.batterySaver) 180_000L else 60_000L)
        }
    }

    fun sendHeartbeat() {
        val s = Graph.prefs.now
        val bundle = if (s.role == Roles.RESCUER) Graph.rescuer.bundle() else null
        val shareLoc = s.role != Roles.PUBLIC
        // Only precise positions: a rough network one (hundreds of metres off) would move a rescuer on the base map.
        val loc = if (shareLoc) Graph.loc.usable() else null
        val plain = HeartbeatPayload(
            role = s.role,
            battery = batteryPercent(),
            neighbours = _peers.value.map { it.deviceId },
            lat = loc?.latitude,
            lon = loc?.longitude,
            team = bundle?.cert?.team ?: "",
            rescuerId = bundle?.cert?.rescuerId ?: "",
            name = if (shareLoc) (bundle?.cert?.name ?: s.name) else "",
            version = BuildConfig.VERSION_NAME,
            at = System.currentTimeMillis(),
            locAt = loc?.time ?: 0L,
        )
        // A rescuer signs its position with the team key, so the base knows it is genuine.
        val hb = if (bundle != null) PositionAuth.sign(bundle.teamKey, engine.myId, plain) else plain
        if (s.role == Roles.BASE && Graph.base.hasKeys()) {
            Graph.base.onHeartbeat(engine.myId, hb, 0)
        } else {
            send(outbox.sealedBase(PacketType.HEARTBEAT, hb.toBytes()))
        }
    }

    fun batteryPercent(): Int = Companion.batteryPercent(ctx)

    /** Only for the on-device smoke test: seal this phone's packets for the generated test base. */
    @VisibleForTesting
    fun usePinnedForTest(keys: PinnedBaseKeys) {
        val old = outbox
        outbox = Outbox(engine.myId, keys).also {
            it.ttl = old.ttl
            it.drill = old.drill
        }
    }

    companion object {
        fun batteryPercent(ctx: Context): Int {
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (v in 0..100) return v
            } catch (_: Exception) {
            }
            return try {
                val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level < 0 || scale <= 0) -1 else level * 100 / scale
            } catch (e: Exception) {
                -1
            }
        }
    }

    private fun publishStats() {
        _stats.value = MeshStats(
            running = loops != null,
            advertising = link.advertising,
            discovering = link.discovering,
            peers = _peers.value.size,
            buffer = engine.bufferSize(),
            waiting = engine.waitingCount(),
            waitingOwn = engine.waitingOwnCount(),
            metrics = engine.metrics.snapshot(),
            lastError = link.lastError,
        )
    }

    /* ---------------- NearbyLink.Listener ---------------- */

    override fun onBytes(fromDevice: String, bytes: ByteArray) {
        scope.launch {
            val r = try {
                engine.receive(fromDevice, bytes)
            } catch (e: Exception) {
                Graph.note("mesh receive", e)
                return@launch
            }
            if (r.forwarded) kick = true
            val p = r.packet
            if (r.drop != null) {
                logDrop(r.drop!!, p, fromDevice)
            } else if (p != null && r.consumed) {
                try {
                    Graph.router.handle(p)
                } catch (e: Exception) {
                    Graph.note("router ${p.type}", e)
                }
            }
        }
    }

    private fun logDrop(drop: Drop, p: Packet?, from: String) {
        if (drop == Drop.DUPLICATE) return
        val t = System.currentTimeMillis()
        if (t - lastDropLog < 2_000) return
        lastDropLog = t
        val what = p?.let { "${it.type} ${it.shortId}" } ?: "packet"
        val why = when (drop) {
            Drop.CRC -> "CRC-32 check failed (damaged)"
            Drop.FORMAT -> "bad format"
            Drop.REPLAY -> "too old or from the future (replay)"
            Drop.RATE -> "too many packets from one phone (spam limit)"
            Drop.FORGED -> "signature check failed (forged)"
            Drop.DUPLICATE -> "duplicate"
        }
        Graph.log("SECURITY", "Dropped $what from ${from.take(6)}: $why")
    }

    override fun onPeersChanged(peers: List<NearbyLink.Peer>) {
        _peers.value = peers
        engine.setNeighbours(peers.map { it.deviceId })
        kick = true
        publishStats()
        Graph.onPeersChanged(peers.size)
    }

    override fun onSendFailed(toDevice: String, tag: String) {
        engine.sendFailed(toDevice, tag)
    }

    override fun onLog(text: String) {
        Graph.log("LINK", text)
    }
}
