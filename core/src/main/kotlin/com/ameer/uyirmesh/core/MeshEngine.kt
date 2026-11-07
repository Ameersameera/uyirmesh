package com.ameer.uyirmesh.core

/** Tunables of the relay engine. Times are in milliseconds. */
data class MeshConfig(
    val maxSeen: Int = 20_000,
    val seenTtlMs: Long = 48L * 3600_000,
    val maxBuffer: Int = 800,
    /** How long a packet is kept for store-and-forward, by QoS class 0..3. */
    val keepMs: LongArray = longArrayOf(6L * 3600_000, 6L * 3600_000, 3600_000, 30L * 60_000),
    /** Packets older than this are treated as replays and dropped (must not exceed [seenTtlMs]). */
    val maxAgeMs: Long = 48L * 3600_000,
    /** Tolerance for phones whose clock runs ahead. */
    val maxFutureMs: Long = 24L * 3600_000,
    val ratePerMinute: Int = 30,
    val maxPacketsPerTick: Int = 6,
)

class MeshMetrics {
    @Volatile var received = 0L
    @Volatile var consumed = 0L
    @Volatile var originated = 0L
    @Volatile var relayed = 0L
    @Volatile var transmissions = 0L
    @Volatile var duplicates = 0L
    @Volatile var crcDrops = 0L
    @Volatile var formatDrops = 0L
    @Volatile var replayDrops = 0L
    @Volatile var rateDrops = 0L
    @Volatile var forgedDrops = 0L
    @Volatile var ttlStops = 0L
    @Volatile var sendFailures = 0L

    fun snapshot(): Map<String, Long> = linkedMapOf(
        "received" to received, "consumed" to consumed, "originated" to originated, "relayed" to relayed,
        "transmissions" to transmissions, "duplicates" to duplicates, "crcDrops" to crcDrops,
        "formatDrops" to formatDrops, "replayDrops" to replayDrops, "rateDrops" to rateDrops,
        "forgedDrops" to forgedDrops, "ttlStops" to ttlStops, "sendFailures" to sendFailures,
    )
}

/** Why an incoming packet was not accepted. */
enum class Drop { CRC, FORMAT, DUPLICATE, REPLAY, RATE, FORGED }

/**
 * Multi-hop relay engine (controlled flooding with store-and-forward), independent of Android.
 *
 *  - Every packet has a random 16-byte id. Each phone remembers ids it has seen and drops repeats (duplicate filter).
 *  - TTL is the number of hops a packet may still travel. Every transmission uses one hop; a copy that arrives
 *    with TTL 0 is used but not forwarded again.
 *  - Packets are kept in a buffer and sent to every neighbour that has not had them yet, also neighbours that
 *    appear later (store-and-forward), until they expire.
 *  - The buffer is served in QoS order: SOS first.
 *  - Packets with impossible timestamps (replays), forged base messages ([validator]) and floods from one
 *    source (rate limit) are dropped.
 *  - Every phone keeps forwarding packets for the rescue side, also the base and rescuers that read them, so a
 *    rescuer in the middle or at the end of the chain gets them.
 *
 * Thread-safe: all public methods are synchronized.
 */
class MeshEngine(
    val myId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val config: MeshConfig = MeshConfig(),
) {
    data class Send(val neighbour: String, val packet: Packet, val bytes: ByteArray)

    data class Incoming(val packet: Packet?, val consumed: Boolean, val forwarded: Boolean, val drop: Drop?)

    private class Entry(val packet: Packet, val storedAt: Long, val from: String?) {
        val sentTo = HashSet<String>()
        var waitingLogged = false
    }

    val metrics = MeshMetrics()
    private val myShort = Ids.shortOf(myId)

    @Volatile var isBase: Boolean = false
    @Volatile var isRescuer: Boolean = false
    @Volatile var teams: Set<String> = emptySet()

    /** Returns false for packets that claim to come from the base but are not correctly signed. */
    @Volatile var validator: ((Packet) -> Boolean)? = null

    private val seen = LinkedHashMap<String, Long>(1024)
    private val buffer = LinkedHashMap<String, Entry>(256)
    private val neighbours = LinkedHashSet<String>()
    private val rate = HashMap<String, ArrayDeque<Long>>()

    /** Called for locally originated packets when they are first handed to at least one neighbour. */
    var onFirstHandOff: ((Packet, Int) -> Unit)? = null

    /** Called when a stored packet is finally forwarded after waiting with no neighbour (waited ms). */
    var onStoredForwarded: ((Packet, Long) -> Unit)? = null

    /** Called when a packet starts waiting because no neighbour is in range. */
    var onStored: ((Packet) -> Unit)? = null

    @Synchronized
    fun setNeighbours(ids: Collection<String>) {
        neighbours.clear()
        neighbours.addAll(ids.filter { it != myId })
    }

    @Synchronized
    fun neighbourList(): List<String> = neighbours.toList()

    /** Queue a packet created on this phone. */
    @Synchronized
    fun originate(p: Packet) {
        require(p.src == myId) { "Originated packets must use this phone's id" }
        remember(p.idHex)
        metrics.originated++
        putInBuffer(Entry(p, clock(), null))
    }

    /** Handle bytes from a neighbour. [Incoming.consumed] tells whether this phone should read the packet. */
    @Synchronized
    fun receive(fromNeighbour: String, bytes: ByteArray): Incoming {
        metrics.received++
        val p = try {
            PacketCodec.decode(bytes)
        } catch (e: CrcException) {
            metrics.crcDrops++
            return Incoming(null, false, false, Drop.CRC)
        } catch (e: Exception) {
            metrics.formatDrops++
            return Incoming(null, false, false, Drop.FORMAT)
        }
        val now = clock()
        if (p.createdAt < now - config.maxAgeMs || p.createdAt > now + config.maxFutureMs) {
            metrics.replayDrops++
            return Incoming(p, false, false, Drop.REPLAY)
        }
        if (isSeen(p.idHex, now)) {
            metrics.duplicates++
            return Incoming(p, false, false, Drop.DUPLICATE)
        }
        if (p.src == myId) {
            // Our own packet came back around the mesh.
            remember(p.idHex)
            metrics.duplicates++
            return Incoming(p, false, false, Drop.DUPLICATE)
        }
        val v = validator
        if (v != null && !safeValidate(v, p)) {
            metrics.forgedDrops++
            return Incoming(p, false, false, Drop.FORGED)
        }
        if (isRateLimited(p, now)) {
            metrics.rateDrops++
            return Incoming(p, false, false, Drop.RATE)
        }
        remember(p.idHex)
        val consume = isForMe(p)
        if (consume) metrics.consumed++
        var forwarded = false
        if (shouldForward(p)) {
            if (p.ttl > 0) {
                putInBuffer(Entry(p, now, fromNeighbour))
                forwarded = true
                metrics.relayed++
            } else {
                metrics.ttlStops++
            }
        }
        return Incoming(p, consume, forwarded, null)
    }

    /** Decide what to send now. Call this regularly (every few hundred milliseconds). */
    @Synchronized
    fun tick(): List<Send> {
        val now = clock()
        val out = ArrayList<Send>()
        if (buffer.isEmpty()) return out
        val entries = buffer.values.sortedWith(compareBy<Entry>({ it.packet.qos }, { it.storedAt }))
        var served = 0
        for (e in entries) {
            if (served >= config.maxPacketsPerTick) break
            val keep = config.keepMs[e.packet.qos.coerceIn(0, config.keepMs.size - 1)]
            if (now - e.storedAt > keep || e.packet.ttl <= 0) {
                buffer.remove(e.packet.idHex)
                continue
            }
            val usable = neighbours.filter { it != e.from && it != e.packet.src }
            val targets = usable.filter { it !in e.sentTo }
            if (targets.isEmpty()) {
                if (usable.isEmpty() && e.sentTo.isEmpty() && !e.waitingLogged) {
                    e.waitingLogged = true
                    onStored?.invoke(e.packet)
                }
                continue
            }
            val firstSend = e.sentTo.isEmpty()
            val copy = e.packet.relayCopy(if (e.from == null) null else myShort)
            val bytes = PacketCodec.encode(copy)
            for (t in targets) {
                e.sentTo.add(t)
                out.add(Send(t, copy, bytes))
                metrics.transmissions++
            }
            if (firstSend) {
                if (e.waitingLogged) onStoredForwarded?.invoke(e.packet, now - e.storedAt)
                if (e.from == null) onFirstHandOff?.invoke(e.packet, targets.size)
            }
            served++
        }
        return out
    }

    /** The transport could not deliver [idHex] to [neighbour]: try again on a later tick. */
    @Synchronized
    fun sendFailed(neighbour: String, idHex: String) {
        metrics.sendFailures++
        buffer[idHex]?.sentTo?.remove(neighbour)
    }

    /** Stop spreading a packet (for example a cancelled SOS). */
    @Synchronized
    fun forget(idHex: String) {
        buffer.remove(idHex)
    }

    /** Stop spreading every buffered packet that matches [predicate]. Returns how many were removed. */
    @Synchronized
    fun forgetWhere(predicate: (Packet) -> Boolean): Int {
        val ids = buffer.values.filter { predicate(it.packet) }.map { it.packet.idHex }
        ids.forEach { buffer.remove(it) }
        return ids.size
    }

    /** Packets that are waiting because no neighbour is available. */
    @Synchronized
    fun waitingCount(): Int = buffer.values.count { e ->
        e.sentTo.isEmpty() && e.packet.ttl > 0 && neighbours.none { it != e.from && it != e.packet.src }
    }

    /** Locally created packets that have not reached any phone yet. */
    @Synchronized
    fun waitingOwnCount(): Int = buffer.values.count { e -> e.from == null && e.sentTo.isEmpty() }

    @Synchronized
    fun bufferSize(): Int = buffer.size

    /** Packets in the store-and-forward buffer that match [predicate] (for the internet gateway). */
    @Synchronized
    fun buffered(predicate: (Packet) -> Boolean): List<Packet> = buffer.values.map { it.packet }.filter(predicate)

    @Synchronized
    fun hasPending(): Boolean = buffer.values.any { e ->
        e.packet.ttl > 0 && neighbours.any { it != e.from && it != e.packet.src && it !in e.sentTo }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        seen.clear()
        rate.clear()
    }

    /* ---------------- rules ---------------- */

    fun isForMe(p: Packet): Boolean = when {
        p.dst == myId -> true
        p.dst == Destinations.ALL -> true
        p.dst == Destinations.BASE -> isBase || isRescuer
        Destinations.isTeam(p.dst) -> isBase || Destinations.teamName(p.dst) in teams
        else -> false
    }

    fun shouldForward(p: Packet): Boolean = p.dst != myId

    private fun safeValidate(v: (Packet) -> Boolean, p: Packet): Boolean = try {
        v(p)
    } catch (e: Exception) {
        false
    }

    private fun isSeen(idHex: String, now: Long): Boolean {
        val t = seen[idHex] ?: return false
        if (now - t > config.seenTtlMs) {
            seen.remove(idHex)
            return false
        }
        return true
    }

    private fun remember(idHex: String) {
        seen.remove(idHex)
        seen[idHex] = clock()
        if (seen.size > config.maxSeen) {
            val it = seen.entries.iterator()
            while (seen.size > config.maxSeen && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    private fun isRateLimited(p: Packet, now: Long): Boolean {
        // Replies, alerts and tasks are signed by the base (checked by the validator before this point).
        if (p.type == PacketType.ACK || p.type == PacketType.ALERT || p.type == PacketType.TASK ||
            p.type == PacketType.FIND_RESULT || p.type == PacketType.POLL || p.type == PacketType.BASE_SYNC ||
            p.type == PacketType.REVOKE
        ) return false
        val q = rate.getOrPut(p.src) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
        if (q.size >= config.ratePerMinute) return true
        q.addLast(now)
        if (rate.size > 2_000) {
            rate.entries.removeIf { it.value.isEmpty() || now - it.value.last() > 60_000 }
        }
        return false
    }

    private fun putInBuffer(e: Entry) {
        buffer[e.packet.idHex] = e
        if (buffer.size > config.maxBuffer) {
            // Evict the oldest packet of the lowest priority class first.
            val victim = buffer.values.sortedWith(compareBy<Entry>({ -it.packet.qos }, { it.storedAt })).firstOrNull()
            if (victim != null) buffer.remove(victim.packet.idHex)
        }
    }
}
