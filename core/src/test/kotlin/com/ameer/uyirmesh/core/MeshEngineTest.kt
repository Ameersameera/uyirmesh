package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates phones running the relay engine with an in-memory radio.
 * Links are undirected; a packet sent on a link arrives at the next step.
 */
class MeshEngineTest {

    private class Net(names: List<String>, var now: Long = 1_750_000_000_000L, config: MeshConfig = MeshConfig()) {
        val ids = names.associateWith { n -> (n.padEnd(8, '0').map { c -> "%x".format(c.code % 16) }.joinToString("") + "00000000").take(16) }
        val engines = names.associateWith { MeshEngine(ids.getValue(it), { now }, config) }
        val links = HashSet<Pair<String, String>>()
        val delivered = HashMap<String, MutableList<Packet>>()
        val corrupt = HashSet<String>()
        var transmissions = 0

        fun id(n: String) = ids.getValue(n)
        fun e(n: String) = engines.getValue(n)
        private fun nameOf(id: String) = ids.entries.first { it.value == id }.key

        fun link(a: String, b: String) {
            links.add(a to b)
            links.add(b to a)
            refresh()
        }

        fun unlink(a: String, b: String) {
            links.remove(a to b)
            links.remove(b to a)
            refresh()
        }

        fun refresh() {
            for ((n, e) in engines) e.setNeighbours(links.filter { it.first == n }.map { id(it.second) })
        }

        fun step() {
            val inFlight = ArrayList<Triple<String, String, ByteArray>>()
            for ((n, e) in engines) for (s in e.tick()) {
                val to = nameOf(s.neighbour)
                if ((n to to) in links) {
                    val bytes = if (to in corrupt) s.bytes.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() } else s.bytes
                    inFlight.add(Triple(n, to, bytes))
                }
            }
            transmissions += inFlight.size
            for ((from, to, bytes) in inFlight) {
                val r = e(to).receive(id(from), bytes)
                if (r.consumed && r.packet != null) delivered.getOrPut(to) { ArrayList() }.add(r.packet!!)
            }
            now += 300
        }

        fun run(steps: Int) = repeat(steps) { step() }
        fun got(n: String) = delivered[n].orEmpty()
    }

    private fun sos(net: Net, from: String, ttl: Int = 5): Packet {
        val p = Packet.create(PacketType.SOS, net.id(from), Destinations.BASE, "help".toByteArray(), ttl, net.now)
        net.e(from).originate(p)
        return p
    }

    @Test
    fun sosTravelsFourHopsToTheBaseWithPathTrace() {
        val net = Net(listOf("victim", "r1", "r2", "r3", "base"))
        net.e("base").isBase = true
        net.link("victim", "r1"); net.link("r1", "r2"); net.link("r2", "r3"); net.link("r3", "base")
        val p = sos(net, "victim")
        net.run(6)
        val got = net.got("base")
        assertEquals(1, got.size)
        assertEquals(p.idHex, got[0].idHex)
        assertEquals(4, got[0].hops)
        assertEquals(1, got[0].ttl)
        assertEquals(listOf("r1", "r2", "r3").map { net.id(it).take(8) }, got[0].path.map { Ids.shortHex(it) })
        assertTrue(net.got("r1").isEmpty())
    }

    @Test
    fun ttlLimitsHowFarAPacketTravels() {
        val net = Net(listOf("a", "b", "c", "d", "base"))
        net.e("base").isBase = true
        net.link("a", "b"); net.link("b", "c"); net.link("c", "d"); net.link("d", "base")
        sos(net, "a", ttl = 3) // a->b (2) b->c (1) c->d (0): d uses it but does not forward
        net.run(10)
        assertTrue(net.got("base").isEmpty())
        assertTrue(net.e("d").metrics.ttlStops >= 1)
    }

    @Test
    fun duplicateFilterDeliversOnceInAMeshWithLoops() {
        val names = listOf("a", "b", "c", "d", "e", "base")
        val net = Net(names)
        net.e("base").isBase = true
        // Fully connected: many loops.
        for (x in names) for (y in names) if (x < y) net.link(x, y)
        sos(net, "a")
        net.run(10)
        assertEquals(1, net.got("base").size)
        val dupes = names.sumOf { net.e(it).metrics.duplicates }
        assertTrue("duplicates were dropped: $dupes", dupes > 0)
        // Each phone forwards a given packet at most once per neighbour.
        assertTrue(net.transmissions <= names.size * (names.size - 1))
    }

    @Test
    fun storeAndForwardWhenNoPhoneIsInRange() {
        val net = Net(listOf("victim", "walker", "base"))
        net.e("base").isBase = true
        var storedEvents = 0
        var forwardedAfterWait = 0L
        net.e("victim").onStored = { storedEvents++ }
        net.e("victim").onStoredForwarded = { _, waited -> forwardedAfterWait = waited }
        sos(net, "victim")
        net.run(20)
        assertEquals(1, storedEvents)
        assertEquals(1, net.e("victim").waitingCount())
        // A person walks past the victim, then later reaches the base.
        net.link("victim", "walker")
        net.run(2)
        net.unlink("victim", "walker")
        assertTrue(forwardedAfterWait > 0)
        net.run(50)
        assertTrue(net.got("base").isEmpty())
        net.link("walker", "base")
        net.run(2)
        assertEquals(1, net.got("base").size)
    }

    @Test
    fun corruptedPacketsAreDroppedByCrc() {
        val net = Net(listOf("a", "base"))
        net.e("base").isBase = true
        net.corrupt.add("base")
        net.link("a", "base")
        sos(net, "a")
        net.run(3)
        assertTrue(net.got("base").isEmpty())
        assertEquals(1L, net.e("base").metrics.crcDrops)
    }

    @Test
    fun oldPacketsAreDroppedAsReplays() {
        val net = Net(listOf("a", "base"))
        net.e("base").isBase = true
        net.link("a", "base")
        val old = Packet.create(PacketType.SOS, net.id("a"), Destinations.BASE, byteArrayOf(1), 5, net.now - 49L * 3600_000)
        val r = net.e("base").receive(net.id("a"), PacketCodec.encode(old.relayCopy(null)))
        assertEquals(Drop.REPLAY, r.drop)
        val future = Packet.create(PacketType.SOS, net.id("a"), Destinations.BASE, byteArrayOf(1), 5, net.now + 25L * 3600_000)
        assertEquals(Drop.REPLAY, net.e("base").receive(net.id("a"), PacketCodec.encode(future.relayCopy(null))).drop)
        // A copy of an accepted packet sent again later is a duplicate.
        val ok = Packet.create(PacketType.SOS, net.id("a"), Destinations.BASE, byteArrayOf(1), 5, net.now)
        assertEquals(null, net.e("base").receive(net.id("a"), PacketCodec.encode(ok.relayCopy(null))).drop)
        assertEquals(Drop.DUPLICATE, net.e("base").receive(net.id("a"), PacketCodec.encode(ok.relayCopy(null))).drop)
    }

    @Test
    fun floodsFromOneSourceAreRateLimited() {
        val net = Net(listOf("spammer", "relay"), config = MeshConfig(ratePerMinute = 30))
        var accepted = 0
        var limited = 0
        repeat(40) {
            val p = Packet.create(PacketType.CHAT, net.id("spammer"), Destinations.ALL, byteArrayOf(1), 5, net.now)
            val r = net.e("relay").receive(net.id("spammer"), PacketCodec.encode(p.relayCopy(null)))
            if (r.drop == null) accepted++ else if (r.drop == Drop.RATE) limited++
        }
        assertEquals(30, accepted)
        assertEquals(10, limited)
        net.now += 61_000
        val p = Packet.create(PacketType.CHAT, net.id("spammer"), Destinations.ALL, byteArrayOf(1), 5, net.now)
        assertEquals(null, net.e("relay").receive(net.id("spammer"), PacketCodec.encode(p.relayCopy(null))).drop)
    }

    @Test
    fun forgedBaseMessagesAreDroppedByRelays() {
        val net = Net(listOf("fake", "relay", "victim"))
        net.e("relay").validator = { Authenticity.check(TestUtil.pinned, it, net.now) }
        net.e("victim").validator = { Authenticity.check(TestUtil.pinned, it, net.now) }
        net.link("fake", "relay"); net.link("relay", "victim")
        val alert = AlertPayload("a1", AlertKind.EVACUATE, 3, "Leave now", "fake").toBytes()
        val unsigned = Packet.create(PacketType.ALERT, net.id("fake"), Destinations.ALL, alert, 5, net.now)
        val wrongKey = Crypto.signPacket(Crypto.newEcKeyPair().private, Packet.create(PacketType.ALERT, net.id("fake"), Destinations.ALL, alert, 5, net.now))
        net.e("fake").originate(unsigned)
        net.e("fake").originate(wrongKey)
        net.run(5)
        assertTrue(net.got("relay").isEmpty())
        assertTrue(net.got("victim").isEmpty())
        assertEquals(2L, net.e("relay").metrics.forgedDrops)
        // A genuine alert from the base goes through.
        val genuine = Outbox(net.id("fake"), TestUtil.pinned) { net.now }
            .signedByBase(TestUtil.basePrivate, PacketType.ALERT, alert, Destinations.ALL)
        net.e("fake").originate(genuine)
        net.run(5)
        assertEquals(1, net.got("victim").size)
    }

    @Test
    fun sosIsSentBeforeChatAndMedia() {
        val net = Net(listOf("a", "b"), config = MeshConfig(maxPacketsPerTick = 1))
        net.e("b").isBase = true
        val media = Packet.create(PacketType.SOS_MEDIA, net.id("a"), Destinations.BASE, byteArrayOf(1), 5, net.now)
        val chat = Packet.create(PacketType.CHAT, net.id("a"), Destinations.ALL, byteArrayOf(2), 5, net.now)
        net.e("a").originate(media)
        net.e("a").originate(chat)
        val sos = sos(net, "a")
        net.link("a", "b")
        net.run(1)
        assertEquals(listOf(sos.idHex), net.got("b").map { it.idHex })
        net.run(1)
        assertEquals(PacketType.CHAT, net.got("b")[1].type)
        net.run(1)
        assertEquals(PacketType.SOS_MEDIA, net.got("b")[2].type)
    }

    @Test
    fun rescuerInTheMiddleReadsAndStillForwards() {
        val net = Net(listOf("victim", "rescuer", "base"))
        net.e("rescuer").isRescuer = true
        net.e("base").isBase = true
        net.link("victim", "rescuer"); net.link("rescuer", "base")
        sos(net, "victim")
        net.run(4)
        assertEquals(1, net.got("rescuer").size)
        assertEquals(1, net.got("base").size)
    }

    @Test
    fun rescuerBehindTheBaseStillGetsTheSos() {
        val net = Net(listOf("victim", "base", "rescuer"))
        net.e("rescuer").isRescuer = true
        net.e("base").isBase = true
        net.link("victim", "base"); net.link("base", "rescuer")
        sos(net, "victim")
        net.run(4)
        assertEquals(1, net.got("base").size)
        assertEquals(1, net.got("rescuer").size)
        assertTrue(net.got("victim").isEmpty())
    }

    @Test
    fun replyReachesVictimAndIsNotForwardedFurther() {
        val net = Net(listOf("base", "relay", "victim", "beyond"))
        net.e("base").isBase = true
        net.link("base", "relay"); net.link("relay", "victim"); net.link("victim", "beyond")
        val ack = Packet.create(PacketType.ACK, net.id("base"), net.id("victim"), byteArrayOf(1), 5, net.now)
        net.e("base").originate(ack)
        net.run(5)
        assertEquals(1, net.got("victim").size)
        assertTrue(net.got("relay").isEmpty())
        assertTrue(net.got("beyond").isEmpty())
        assertEquals(0L, net.e("beyond").metrics.received)
    }

    @Test
    fun teamMessagesAreReadOnlyByThatTeamAndTheBase() {
        val net = Net(listOf("base", "alpha", "bravo", "public"))
        net.e("base").isBase = true
        net.e("alpha").isRescuer = true; net.e("alpha").teams = setOf("ALPHA")
        net.e("bravo").isRescuer = true; net.e("bravo").teams = setOf("BRAVO")
        net.link("base", "public"); net.link("public", "alpha"); net.link("alpha", "bravo")
        val task = Packet.create(PacketType.TASK, net.id("base"), Destinations.team("ALPHA"), byteArrayOf(1), 5, net.now)
        net.e("base").originate(task)
        net.run(5)
        assertEquals(1, net.got("alpha").size)
        assertTrue(net.got("bravo").isEmpty())
        assertTrue(net.got("public").isEmpty())
        val status = Packet.create(PacketType.TASK_STATUS, net.id("alpha"), Destinations.team("ALPHA"), byteArrayOf(2), 5, net.now)
        net.e("alpha").originate(status)
        net.run(5)
        assertEquals(1, net.got("base").size)
    }

    @Test
    fun failedSendIsRetriedAndForgetStopsSpreading() {
        val net = Net(listOf("a", "b", "c"))
        net.link("a", "b")
        val p = sos(net, "a")
        val first = net.e("a").tick()
        assertEquals(1, first.size)
        assertTrue(net.e("a").tick().isEmpty())
        net.e("a").sendFailed(net.id("b"), p.idHex)
        assertEquals(1, net.e("a").tick().size)
        assertEquals(1L, net.e("a").metrics.sendFailures)
        net.e("a").forget(p.idHex)
        net.link("a", "c")
        assertTrue(net.e("a").tick().isEmpty())
        assertEquals(0, net.e("a").bufferSize())
    }

    @Test
    fun handOffCallbackAndExpiry() {
        val net = Net(listOf("a", "b"))
        var handedTo = -1
        net.e("a").onFirstHandOff = { _, n -> handedTo = n }
        val chat = Packet.create(PacketType.CHAT, net.id("a"), Destinations.ALL, byteArrayOf(1), 5, net.now)
        net.e("a").originate(chat)
        assertEquals(1, net.e("a").waitingOwnCount())
        net.now += 2 * 3600_000L // chat is kept for 1 hour only
        net.link("a", "b")
        net.run(2)
        assertEquals(-1, handedTo)
        assertEquals(0, net.e("a").bufferSize())
        sos(net, "a")
        net.run(1)
        assertEquals(1, handedTo)
        assertFalse(net.e("a").hasPending())
    }

    @Test
    fun bufferIsBoundedAndDropsLowPriorityFirst() {
        val net = Net(listOf("a"), config = MeshConfig(maxBuffer = 10))
        val sosPacket = sos(net, "a")
        repeat(20) {
            net.e("a").originate(Packet.create(PacketType.CHAT, net.id("a"), Destinations.ALL, byteArrayOf(it.toByte()), 5, net.now))
            net.now += 10
        }
        assertEquals(10, net.e("a").bufferSize())
        assertEquals(1, net.e("a").forgetWhere { it.idHex == sosPacket.idHex })
    }
}
