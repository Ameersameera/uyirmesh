// Measurements and mesh simulations for the UyirMesh project report.
// Uses the real core code (MeshEngine, PacketCodec, Outbox, Crypto, Authenticity, Triage) on the JVM.
// Radio links are simulated: a phone links to others within RANGE metres, at most MAX_PEERS links each
// (NearbyLink.MAX_PEERS = 6), like Nearby Connections P2P_CLUSTER. Each round every phone calls engine.tick()
// once and every send is delivered (or lost and retried, when a loss rate is set).

import com.ameer.uyirmesh.core.*
import java.io.File
import java.util.Random
import kotlin.math.*

var simNow = 1_790_000_000_000L
val clock: () -> Long = { simNow }

object K {
    val enc = Crypto.newEcKeyPair()
    val sig = Crypto.newEcKeyPair()
    val resc = Crypto.newEcKeyPair()
    val pinned = PinnedBaseKeys(enc.public, sig.public, resc.public)
    val keys = BasePrivateKeys(enc.private, sig.private, resc.private)
}

val out = StringBuilder()
fun emit(s: String) { println(s); out.append(s).append('\n') }

/* ============================ 1. packet sizes ============================ */

fun sizes() {
    emit("## packet_sizes")
    emit("type|plain_payload_bytes|payload_on_wire_bytes|packet_bytes_at_source|note")
    val now = simNow
    val victim = Ids.newDeviceId()
    val vOut = Outbox(victim, K.pinned, clock)
    val caseId = Ids.newId()
    val sos = SosPayload(
        caseId = caseId, kind = EmergencyKind.FLOOD, people = 4, children = 1, elderly = 1, injured = true,
        needs = listOf(Needs.BOAT, Needs.WATER), note = "water rising, on the roof", battery = 30,
        lat = 11.6643, lon = 78.1460, accuracy = 12.0, locTime = now, name = "Ravi", phone = "9876543210",
        trigger = Triggers.VOICE, cancelHash = Crypto.sha256Hex(Hex.encode(Crypto.random(16))), sentAt = now,
    )
    fun row(name: String, plain: ByteArray, p: Packet, note: String) {
        emit("$name|${plain.size}|${p.payload.size}|${PacketCodec.encode(p).size}|$note")
    }
    val sosPlain = sos.toBytes()
    row("SOS", sosPlain, vOut.sealedRescue(PacketType.SOS, sosPlain), "sealed for base + rescuers (2 key slots)")
    val upd = SosUpdate(caseId, 11.6650, 78.1470, 8.0, now, 25, sentAt = now).toBytes()
    row("SOS_UPDATE", upd, vOut.sealedRescue(PacketType.SOS_UPDATE, upd), "sealed for base + rescuers")
    val baseId = Ids.newDeviceId()
    val bOut = Outbox(baseId, K.pinned, clock)
    val ack = AckPayload(caseId, AckStatus.RECEIVED, "", -1, "", now, "Salem Base", "", 3, listOf("1a2b3c4d", "5e6f7a8b")).toBytes()
    row("ACK (base)", ack, bOut.signedByBase(K.keys, PacketType.ACK, ack, victim), "ECDSA P-256 signature by the base")
    val teamKey = Crypto.random(32)
    val bundle = Credentials.issue(K.keys, "Karthik", "ALPHA", teamKey, now, 5)
    val rOut = Outbox(Ids.newDeviceId(), K.pinned, clock)
    val prog = AckPayload(caseId, AckStatus.ON_THE_WAY, "ALPHA", 7, "", now, "Karthik", bundle.cert.toBase64(), distanceM = 620).toBytes()
    row("PROGRESS (rescuer)", prog, rOut.signedByRescuer(bundle, PacketType.PROGRESS, prog, victim), "rescuer signature + base-signed certificate")
    val hb = HeartbeatPayload(Roles.RESCUER, 70, listOf(Ids.newDeviceId(), Ids.newDeviceId(), Ids.newDeviceId()), 11.6600, 78.1460, "ALPHA", bundle.cert.rescuerId, "Karthik", "1.0", now, now)
    val hbSigned = PositionAuth.sign(teamKey, rOut.myId, hb).toBytes()
    row("HEARTBEAT (rescuer)", hbSigned, rOut.sealedBase(PacketType.HEARTBEAT, hbSigned), "position MAC, sealed for base")
    val task = TaskPayload(caseId, victim, 11.6643, 78.1460, "Critical", 92, "Flood · 4 people · injured", "", 4, "ALPHA", now, 12.0).toBytes()
    row("TASK", task, bOut.teamSignedByBase(K.keys, PacketType.TASK, task, "ALPHA", teamKey), "team key AES-GCM + base signature")
    val alert = AlertPayload(Ids.newId(), AlertKind.INFO, 1, "Water at the school", "Drinking water at the Govt school from 4 pm", validUntil = now + 3_600_000, at = now).toBytes()
    row("ALERT", alert, bOut.signedByBase(K.keys, PacketType.ALERT, alert, Destinations.ALL), "base signature, to all phones")
    val chat = ChatPayload("Meena", "Is anyone near the bridge?", now).toBytes()
    row("CHAT", chat, vOut.plain(PacketType.CHAT, chat, Destinations.ALL), "public, not encrypted")
    val qr = Credentials.toQr(bundle, "4321".toCharArray())
    emit("## rescuer_qr")
    emit("qr_chars|${qr.length}")
    emit("cert_bytes|${bundle.cert.encode().size}")
    emit("bundle_bytes|${bundle.toBytes().size}")
    // Path trace: every relay adds 4 bytes.
    val p0 = vOut.sealedRescue(PacketType.SOS, sosPlain)
    var p = p0.relayCopy(null)
    val sizesByHop = ArrayList<Int>()
    sizesByHop.add(PacketCodec.encode(p).size)
    for (h in 2..8) {
        p = p.relayCopy(h)
        sizesByHop.add(PacketCodec.encode(p).size)
    }
    emit("## sos_size_by_hop")
    emit("hop|bytes")
    sizesByHop.forEachIndexed { i, b -> emit("${i + 1}|$b") }
}

/* ============================ 2. priority examples ============================ */

fun priorityExamples() {
    val model = ForestModel.fromJson(File(System.getProperty("model")).readText())
    emit("## priority_examples")
    emit("case|kind|people|children|elderly|injured|needs|battery|label|score|proba_low_med_high_crit|top_reasons")
    data class Ex(val name: String, val i: TriageInput)
    val ex = listOf(
        Ex("A", TriageInput(EmergencyKind.FLOOD, 4, 1, 1, 0, 0, true, listOf(Needs.BOAT, Needs.WATER), 30)),
        Ex("B", TriageInput(EmergencyKind.MEDICAL, 1, 0, 1, 0, 0, true, listOf(Needs.MEDICINE), 15)),
        Ex("C", TriageInput(EmergencyKind.TRAPPED, 3, 2, 0, 0, 0, false, listOf(Needs.WATER, Needs.FOOD), 60)),
        Ex("D", TriageInput(EmergencyKind.FIRE, 2, 0, 0, 0, 0, false, emptyList(), 80)),
        Ex("E", TriageInput(EmergencyKind.NEED, 5, 0, 0, 0, 0, false, listOf(Needs.FOOD, Needs.WATER), 70)),
        Ex("F", TriageInput(EmergencyKind.NEED, 1, 0, 0, 0, 0, false, listOf(Needs.WATER), 90)),
    )
    for (e in ex) {
        val r = Triage.assess(model, e.i, 0.0, 0)
        val pr = r.proba.joinToString(",") { "%.2f".format(it) }
        val reasons = r.reasons.joinToString("; ") { "${it.label} +${it.points}" }
        emit("${e.name}|${e.i.kind}|${e.i.people}|${e.i.children}|${e.i.elderly}|${e.i.injured}|${e.i.needs.joinToString("+")}|${e.i.battery}|${r.label}|${r.score}|$pr|$reasons")
    }
    // Aging and repeats on example F.
    emit("## priority_aging")
    emit("waiting_min|repeats|score|label")
    for ((w, rep) in listOf(0.0 to 0, 10.0 to 0, 30.0 to 0, 30.0 to 2, 45.0 to 4)) {
        val r = Triage.assess(model, ex[5].i, w, rep)
        emit("${w.toInt()}|$rep|${r.score}|${r.label}")
    }
    // Inference time on the JVM (for scale only; phones are slower).
    val x = Triage.features(ex[0].i)
    repeat(2000) { model.explain(x) }
    val t0 = System.nanoTime()
    val n = 20000
    repeat(n) { model.explain(x) }
    emit("## priority_timing")
    emit("explain_us_per_call_jvm|${"%.1f".format((System.nanoTime() - t0) / 1000.0 / n)}")
}

/* ============================ 3. mesh simulation ============================ */

class SimNode(val id: String, var x: Double, var y: Double, val engine: MeshEngine, val mobile: Boolean) {
    var tx = x; var ty = y; var speed = 0.0; var pauseUntil = 0L
    val links = LinkedHashSet<String>()
}

class World(
    val rng: Random,
    val range: Double,
    val maxPeers: Int = 6,
    val loss: Double = 0.0,
    val setupMs: Long = 0,
) {
    val nodes = ArrayList<SimNode>()
    val byId = HashMap<String, SimNode>()
    private val inRangeSince = HashMap<Long, Long>()

    fun add(x: Double, y: Double, mobile: Boolean = false, config: MeshConfig = MeshConfig()): SimNode {
        val id = Ids.newDeviceId()
        val e = MeshEngine(id, clock, config)
        e.validator = { p -> Authenticity.check(K.pinned, p, simNow) }
        val n = SimNode(id, x, y, e, mobile)
        nodes.add(n); byId[id] = n
        return n
    }

    private fun d(a: SimNode, b: SimNode) = hypot(a.x - b.x, a.y - b.y)
    private fun key(i: Int, j: Int) = i.toLong() * 100_000 + j

    /** Static topology: closest pairs first, at most [maxPeers] links per phone. */
    fun linkStatic() {
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val dd = d(nodes[i], nodes[j])
            if (dd <= range) pairs.add(Triple(i, j, dd))
        }
        pairs.sortBy { it.third }
        for ((i, j, _) in pairs) {
            val a = nodes[i]; val b = nodes[j]
            if (a.links.size < maxPeers && b.links.size < maxPeers) { a.links.add(b.id); b.links.add(a.id) }
        }
        nodes.forEach { it.engine.setNeighbours(it.links) }
    }

    /** Moving phones: links break when out of range; a new link needs [setupMs] in range first. */
    fun linkDynamic() {
        val now = simNow
        for (a in nodes) {
            val it = a.links.iterator()
            while (it.hasNext()) {
                val b = byId[it.next()]!!
                if (d(a, b) > range) { it.remove(); b.links.remove(a.id) }
            }
        }
        val cands = ArrayList<Triple<Int, Int, Double>>()
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            val k = key(i, j)
            val dd = d(a, b)
            if (dd <= range) {
                val since = inRangeSince.getOrPut(k) { now }
                if (b.id !in a.links && now - since >= setupMs) cands.add(Triple(i, j, dd))
            } else inRangeSince.remove(k)
        }
        cands.sortBy { it.third }
        for ((i, j, _) in cands) {
            val a = nodes[i]; val b = nodes[j]
            if (a.links.size < maxPeers && b.links.size < maxPeers) { a.links.add(b.id); b.links.add(a.id) }
        }
        nodes.forEach { it.engine.setNeighbours(it.links) }
    }

    fun move(dtS: Double, w: Double, h: Double) {
        for (n in nodes) {
            if (!n.mobile) continue
            if (simNow < n.pauseUntil) continue
            val dx = n.tx - n.x; val dy = n.ty - n.y
            val dist = hypot(dx, dy)
            val step = n.speed * dtS
            if (dist <= step || n.speed == 0.0) {
                n.x = n.tx; n.y = n.ty
                n.pauseUntil = simNow + (rng.nextDouble() * 60_000).toLong()
                n.tx = rng.nextDouble() * w; n.ty = rng.nextDouble() * h
                n.speed = 0.5 + rng.nextDouble() * 1.0
            } else {
                n.x += dx / dist * step; n.y += dy / dist * step
            }
        }
    }

    /** One round: every phone ticks once, then all sends are delivered. Returns the number of sends. */
    fun round(onConsumed: (SimNode, Packet) -> Unit): Int {
        val sends = ArrayList<Pair<SimNode, MeshEngine.Send>>()
        for (n in nodes) for (s in n.engine.tick()) sends.add(n to s)
        for ((from, s) in sends) {
            val to = byId[s.neighbour] ?: continue
            if (s.neighbour !in from.links) { from.engine.sendFailed(s.neighbour, s.packet.idHex); continue }
            if (loss > 0 && rng.nextDouble() < loss) { from.engine.sendFailed(s.neighbour, s.packet.idHex); continue }
            val inc = to.engine.receive(from.id, s.bytes)
            if (inc.consumed && inc.packet != null) onConsumed(to, inc.packet)
        }
        return sends.size
    }

    fun hopDistance(src: SimNode, dst: SimNode): Int {
        val dist = HashMap<String, Int>()
        val q = ArrayDeque<String>()
        dist[src.id] = 0; q.add(src.id)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == dst.id) return dist[u]!!
            for (v in byId[u]!!.links) if (v !in dist) { dist[v] = dist[u]!! + 1; q.add(v) }
        }
        return -1
    }

    fun sum(f: (MeshMetrics) -> Long): Long = nodes.sumOf { f(it.engine.metrics) }
}

fun sosPayloadFor(caseId: String) = SosPayload(
    caseId = caseId, kind = EmergencyKind.FLOOD, people = 3, injured = true, needs = listOf(Needs.BOAT),
    note = "on the roof", battery = 40, lat = 11.66, lon = 78.14, accuracy = 10.0, locTime = simNow, sentAt = simNow,
).toBytes()

class Trial(
    var pathHops: Int = -1, var delivered: Boolean = false, var hops: Int = -1, var rounds: Int = -1,
    var tx: Long = 0, var dup: Long = 0, var ttlStops: Long = 0, var ack: Boolean = false, var ackRounds: Int = -1,
)

/** One SOS from [victim] to [base] in a static world; the base answers with a signed ACK like BaseBrain.replyTo. */
fun runOne(w: World, victim: SimNode, base: SimNode, ttl: Int, maxRounds: Int = 400): Trial {
    val t = Trial()
    t.pathHops = w.hopDistance(victim, base)
    val tx0 = w.sum { it.transmissions }; val dup0 = w.sum { it.duplicates }; val ttl0 = w.sum { it.ttlStops }
    val caseId = Ids.newId()
    val sos = Outbox(victim.id, K.pinned, clock).sealedRescue(PacketType.SOS, sosPayloadFor(caseId), ttl)
    victim.engine.originate(sos)
    var r = 0
    var quiet = 0
    while (r < maxRounds) {
        r++
        val sent = w.round { node, p ->
            if (node === base && p.type == PacketType.SOS && p.idHex == sos.idHex && !t.delivered) {
                t.delivered = true; t.hops = p.hops; t.rounds = r
                val ack = AckPayload(caseId, AckStatus.RECEIVED, "", -1, "", simNow, "Base", "", p.hops, emptyList()).toBytes()
                val ackTtl = maxOf(ttl, p.hops + 2, 6).coerceAtMost(16)
                base.engine.originate(Outbox(base.id, K.pinned, clock).signedByBase(K.keys, PacketType.ACK, ack, victim.id, ackTtl))
            }
            if (node === victim && p.type == PacketType.ACK && !t.ack) { t.ack = true; t.ackRounds = r }
        }
        simNow += 250
        if (sent == 0) quiet++ else quiet = 0
        if (quiet >= 3) break
    }
    t.tx = w.sum { it.transmissions } - tx0
    t.dup = w.sum { it.duplicates } - dup0
    t.ttlStops = w.sum { it.ttlStops } - ttl0
    return t
}

fun lineScenario() {
    emit("## line")
    emit("ttl|hops_to_base|delivered|hops_seen|transmissions|ack_back")
    for (ttl in listOf(5, 8)) for (h in 1..9) {
        val w = World(Random(h.toLong()), range = 60.0)
        val nodes = (0..h).map { w.add(it * 50.0, 0.0) }
        val base = nodes.last(); base.engine.isBase = true
        w.linkStatic()
        val t = runOne(w, nodes.first(), base, ttl)
        emit("$ttl|$h|${t.delivered}|${t.hops}|${t.tx}|${t.ack}")
    }
}

fun randomScenario(trials: Int) {
    emit("## random_static")
    emit("n_phones|ttl|trials|path_exists|path_within_ttl|delivered|mean_hops|mean_tx_per_sos|mean_dup_per_sos|ack_given_delivered|mean_degree")
    val side = 400.0
    for (n in listOf(20, 30, 40, 60, 80, 100)) for (ttl in listOf(3, 5, 8)) {
        var exists = 0; var within = 0; var deliv = 0; var hopsSum = 0L; var txSum = 0L; var dupSum = 0L; var ackOk = 0; var count = 0
        var degSum = 0.0
        val rng = Random(1000L * n)
        repeat(trials) {
            val w = World(rng, range = 60.0)
            val base = w.add(side / 2, side / 2); base.engine.isBase = true
            val phones = (0 until n).map { w.add(rng.nextDouble() * side, rng.nextDouble() * side) }
            w.linkStatic()
            degSum += phones.sumOf { it.links.size }.toDouble() / n
            val victim = phones[rng.nextInt(n)]
            val t = runOne(w, victim, base, ttl)
            count++
            if (t.pathHops >= 0) exists++
            if (t.pathHops in 0..ttl) within++
            if (t.delivered) { deliv++; hopsSum += t.hops; if (t.ack) ackOk++ }
            txSum += t.tx; dupSum += t.dup
        }
        emit("$n|$ttl|$count|${"%.3f".format(exists / count.toDouble())}|${"%.3f".format(within / count.toDouble())}|${"%.3f".format(deliv / count.toDouble())}|" +
            "${if (deliv > 0) "%.2f".format(hopsSum / deliv.toDouble()) else "-"}|${"%.1f".format(txSum / count.toDouble())}|${"%.1f".format(dupSum / count.toDouble())}|" +
            "${if (deliv > 0) "%.3f".format(ackOk / deliv.toDouble()) else "-"}|${"%.2f".format(degSum / count)}")
    }
}

fun lossScenario(trials: Int) {
    emit("## link_loss")
    emit("loss|trials_reachable|delivered_of_reachable|mean_rounds|mean_tx_per_sos|ack_given_delivered")
    val side = 400.0; val n = 60; val ttl = 5
    for (loss in listOf(0.0, 0.1, 0.3, 0.5)) {
        var reach = 0; var deliv = 0; var roundsSum = 0L; var txSum = 0L; var ackOk = 0
        val rng = Random(77L + (loss * 100).toLong())
        var attempts = 0
        while (reach < trials && attempts < trials * 20) {
            attempts++
            val w = World(rng, range = 60.0, loss = loss)
            val base = w.add(side / 2, side / 2); base.engine.isBase = true
            val phones = (0 until n).map { w.add(rng.nextDouble() * side, rng.nextDouble() * side) }
            w.linkStatic()
            val victim = phones[rng.nextInt(n)]
            val hd = w.hopDistance(victim, base)
            if (hd !in 1..ttl) continue
            reach++
            val t = runOne(w, victim, base, ttl)
            if (t.delivered) { deliv++; roundsSum += t.rounds; if (t.ack) ackOk++ }
            txSum += t.tx
        }
        emit("$loss|$reach|${"%.3f".format(deliv / reach.toDouble())}|${"%.2f".format(roundsSum / max(1, deliv).toDouble())}|${"%.1f".format(txSum / reach.toDouble())}|${"%.3f".format(ackOk / max(1, deliv).toDouble())}")
    }
}

/** Sparse area: trapped victims stay put, walkers carry SOS messages (store-and-forward) to the base. */
fun mobilityScenario(trials: Int) {
    emit("## store_and_forward")
    emit("mode|ttl|walkers|trials|minute|sos_delivered_fraction|ack_back_fraction")
    val side = 1000.0
    val minutes = 120
    val checkpoints = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    val noStore = MeshConfig(keepMs = longArrayOf(1_000, 1_000, 1_000, 1_000))
    data class Mode(val name: String, val config: MeshConfig, val ttl: Int, val walkers: Int)
    val modes = listOf(
        Mode("store_and_forward", MeshConfig(), 5, 20),
        Mode("no_store", noStore, 5, 20),
        Mode("store_and_forward", MeshConfig(), 8, 20),
        Mode("store_and_forward", MeshConfig(), 5, 40),
        Mode("no_store", noStore, 5, 40),
    )
    for (m in modes) {
        val delivAt = Array(checkpoints.size) { 0 }
        val ackAt = Array(checkpoints.size) { 0 }
        var total = 0
        repeat(trials) { trial ->
            val rng = Random(5000L + trial)
            simNow = 1_790_000_000_000L + trial * 86_400_000L
            val w = World(rng, range = 60.0, setupMs = 10_000)
            val base = w.add(side / 2, side / 2, config = m.config); base.engine.isBase = true
            val victims = (0 until 10).map { w.add(rng.nextDouble() * side, rng.nextDouble() * side, config = m.config) }
            repeat(m.walkers) {
                val n = w.add(rng.nextDouble() * side, rng.nextDouble() * side, mobile = true, config = m.config)
                n.tx = rng.nextDouble() * side; n.ty = rng.nextDouble() * side; n.speed = 0.5 + rng.nextDouble()
            }
            val ids = HashMap<String, Int>()
            val gotSos = HashMap<Int, Long>()
            val gotAck = HashMap<Int, Long>()
            val start = simNow
            victims.forEachIndexed { i, v ->
                val caseId = Ids.newId()
                val p = Outbox(v.id, K.pinned, clock).sealedRescue(PacketType.SOS, sosPayloadFor(caseId), m.ttl)
                ids[p.idHex] = i
                v.engine.originate(p)
            }
            val victimIndex = victims.withIndex().associate { it.value.id to it.index }
            for (s in 0 until minutes * 60) {
                w.move(1.0, side, side)
                w.linkDynamic()
                w.round { node, p ->
                    if (node === base && p.type == PacketType.SOS) {
                        val i = ids[p.idHex]
                        if (i != null && i !in gotSos) {
                            gotSos[i] = simNow - start
                            val ack = AckPayload("c$i", AckStatus.RECEIVED, "", -1, "", simNow, "Base", "", p.hops, emptyList()).toBytes()
                            val ackTtl = maxOf(m.ttl, p.hops + 2, 6).coerceAtMost(16)
                            base.engine.originate(Outbox(base.id, K.pinned, clock).signedByBase(K.keys, PacketType.ACK, ack, victims[i].id, ackTtl))
                        }
                    }
                    if (p.type == PacketType.ACK) {
                        val i = victimIndex[node.id]
                        if (i != null && i !in gotAck) gotAck[i] = simNow - start
                    }
                }
                simNow += 1_000
            }
            total += victims.size
            checkpoints.forEachIndexed { ci, minute ->
                delivAt[ci] += gotSos.values.count { it <= minute * 60_000L }
                ackAt[ci] += gotAck.values.count { it <= minute * 60_000L }
            }
        }
        checkpoints.forEachIndexed { ci, minute ->
            emit("${m.name}|${m.ttl}|${m.walkers}|$trials|$minute|${"%.3f".format(delivAt[ci] / total.toDouble())}|${"%.3f".format(ackAt[ci] / total.toDouble())}")
        }
    }
}

fun main(args: Array<String>) {
    val what = args.firstOrNull() ?: "all"
    val trials = args.getOrNull(1)?.toInt() ?: 200
    val t0 = System.currentTimeMillis()
    if (what == "all" || what == "sizes") sizes()
    if (what == "all" || what == "priority") priorityExamples()
    if (what == "all" || what == "line") lineScenario()
    if (what == "all" || what == "random") randomScenario(trials)
    if (what == "all" || what == "loss") lossScenario(trials)
    if (what == "all" || what == "mobility") mobilityScenario(args.getOrNull(2)?.toInt() ?: 30)
    emit("## done_in_s|${(System.currentTimeMillis() - t0) / 1000}")
    File(System.getProperty("outfile", "sim_out_$what.txt")).writeText(out.toString())
}
