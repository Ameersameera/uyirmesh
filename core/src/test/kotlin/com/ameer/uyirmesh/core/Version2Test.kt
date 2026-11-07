package com.ameer.uyirmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Version2Test {
    private val t0 = 1_760_000_000_000L

    @Test
    fun hazardFromARescuerIsVerifiedByEveryPhone() {
        Revocations.clear()
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val karthik = Credentials.issue(TestUtil.basePrivate, "Karthik", "ALPHA", Crypto.random(32), t0, 5)
        val h = HazardPayload("h1", HazardKind.LIVE_WIRE, "wire in the water", 11.66, 78.14, 150, "Karthik", karthik.cert.toBase64(), at = t0)
        val p = PacketCodec.decode(PacketCodec.encode(out.signedByRescuer(karthik, PacketType.HAZARD, h.toBytes(), Destinations.ALL).relayCopy(3)))
        assertTrue(Authenticity.check(pinned, p, t0))
        assertEquals("Karthik", Authenticity.rescuerSigner(pinned, p, t0)?.name)
        val back = HazardPayload.fromBytes(p.payload)
        assertEquals(HazardKind.LIVE_WIRE, back.kind)
        assertEquals(150, back.radiusM)
        // Unsigned or signed by an unknown key: dropped. Signed by the base: fine.
        assertFalse(Authenticity.check(pinned, out.plain(PacketType.HAZARD, h.toBytes(), Destinations.ALL), t0))
        assertFalse(Authenticity.check(pinned, Crypto.signPacket(Crypto.newEcKeyPair().private, out.plain(PacketType.HAZARD, h.toBytes(), Destinations.ALL)), t0))
        assertTrue(Authenticity.check(pinned, out.signedByBase(TestUtil.basePrivate, PacketType.HAZARD, h.copy(cert = "").toBytes(), Destinations.ALL), t0))
    }

    @Test
    fun revokedRescuerCanNoLongerSign() {
        Revocations.clear()
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val lost = Credentials.issue(TestUtil.basePrivate, "Lost phone", "BRAVO", Crypto.random(32), t0, 5)
        val ack = AckPayload("c1", AckStatus.ON_THE_WAY, "BRAVO", 5, "", t0, "Lost phone", lost.cert.toBase64(), distanceM = 300)
        val p = out.signedByRescuer(lost, PacketType.PROGRESS, ack.toBytes(), Ids.newDeviceId())
        assertTrue(Authenticity.check(pinned, p, t0))
        val revoke = RevokePayload(listOf(lost.cert.rescuerId), listOf("Lost phone"), t0)
        val rp = out.signedByBase(TestUtil.basePrivate, PacketType.REVOKE, revoke.toBytes(), Destinations.ALL)
        assertTrue(Authenticity.check(pinned, rp, t0))
        assertFalse("a revoke list must be signed by the base", Authenticity.check(pinned, out.plain(PacketType.REVOKE, revoke.toBytes(), Destinations.ALL), t0))
        Revocations.addAll(RevokePayload.fromBytes(rp.payload).rescuerIds)
        assertFalse(Authenticity.check(pinned, p, t0))
        assertNull(Authenticity.rescuerSigner(pinned, p, t0))
        Revocations.clear()
    }

    @Test
    fun pollsAndAnswers() {
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val poll = PollPayload("p1", PollKind.ROLLCALL, "Are you safe?", listOf("I'm safe", "I need help"), validUntil = t0 + 3_600_000, at = t0)
        val pp = out.signedByBase(TestUtil.basePrivate, PacketType.POLL, poll.toBytes(), Destinations.ALL)
        assertTrue(Authenticity.check(pinned, pp, t0))
        assertFalse(Authenticity.check(pinned, out.plain(PacketType.POLL, poll.toBytes(), Destinations.ALL), t0))
        val back = PollPayload.fromBytes(pp.payload)
        assertEquals(PollKind.ROLLCALL, back.kind)
        assertEquals(2, back.options.size)
        assertTrue(back.isValid(t0))
        assertFalse(back.isValid(t0 + 4_000_000))
        val answer = PollAnswer("p1", 1, "Ravi", "98", 11.6, 78.1, t0)
        val sealed = out.sealedBase(PacketType.POLL_ANSWER, answer.toBytes())
        val opened = PollAnswer.fromBytes(Opener.asBase(TestUtil.basePrivate, pinned, sealed))
        assertEquals(1, opened.choice)
        assertEquals("Ravi", opened.name)
    }

    @Test
    fun caseSyncBetweenBases() {
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val sync = CaseSync("c9", "ASSIGNED", "ALPHA", "Camp North", 12, handoverTo = "Camp South", at = t0)
        val p = out.signedByBase(TestUtil.basePrivate, PacketType.BASE_SYNC, sync.toBytes(), Destinations.BASE)
        assertTrue(Authenticity.check(pinned, p, t0))
        val back = CaseSync.fromBytes(p.payload)
        assertEquals("Camp South", back.handoverTo)
        assertEquals(12, back.etaMin)
    }

    @Test
    fun sensorAdvertAndChildTag() {
        // "UM", version 1, water level, 132 cm, alarm on, battery 77, id DEADBEEF
        val adv = byteArrayOf('U'.code.toByte(), 'M'.code.toByte(), 1, 1, 0, 132.toByte(), 1, 77, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0)
        val s = SensorPayload.fromAdvert(adv, t0)
        assertNotNull(s)
        assertEquals(SensorKind.WATER_LEVEL, s!!.kind)
        assertEquals(132.0, s.value, 0.0)
        assertTrue(s.alarm)
        assertEquals("DEADBEEF", s.sensorId)
        assertNull(SensorPayload.fromAdvert(byteArrayOf(1, 2, 3), t0))
        assertEquals(132.0, SensorPayload.fromBytes(s.toBytes()).value, 0.0)

        val tag = ChildTag.create("Meena", Ids.newDeviceId())
        val back = ChildTag.fromQr(tag.toQr())
        assertEquals(tag.tagId, back.tagId)
        assertArrayEquals(tag.key, back.key)
        val found = ChildFound(tag.tagId, "Shop owner", "97", "near the temple gate", 11.6, 78.1, t0)
        val id = Packet.newId()
        val box = Crypto.keyEncrypt(back.key, found.toBytes(), Packet.aadFor(PacketType.CHILD_FOUND, id, "src"))
        val plain = Crypto.keyDecrypt(tag.key, box, Packet.aadFor(PacketType.CHILD_FOUND, id, "src"))
        assertEquals("near the temple gate", ChildFound.fromBytes(plain).note)
    }

    @Test
    fun similarNames() {
        assertTrue(Names.similar("Ravi Kumar", "ravikumar"))
        assertTrue(Names.similar("Ravi Kumar", "Ravi Kumaar"))
        assertTrue(Names.similar("Meena", "Meena S"))
        assertFalse(Names.similar("Ravi", "Raju"))
        assertFalse(Names.similar("Al", "Al"))
        assertEquals(1, Names.levenshtein("kitten", "kittin"))
    }
}
