package com.ameer.uyirmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysSecureTest {
    private val now = 1_750_000_000_000L
    private val pinned = TestUtil.pinned
    private val base = TestUtil.basePrivate

    @Test
    fun pinnedKeysJsonRoundTrip() {
        val back = PinnedBaseKeys.fromJson(pinned.toJson())
        assertArrayEquals(pinned.enc.encoded, back.enc.encoded)
        assertArrayEquals(pinned.sig.encoded, back.sig.encoded)
        assertArrayEquals(pinned.resc.encoded, back.resc.encoded)
        assertEquals(pinned.encFingerprint, back.encFingerprint)
    }

    @Test
    fun baseKeyFileOpensOnlyWithThePasswordAndTheRightBuild() {
        val file = BaseKeyFile.create("Base@2026".toCharArray(), TestUtil.enc, TestUtil.sig, TestUtil.resc, iterations = 5_000)
        val keys = BaseKeyFile.open(file, "Base@2026".toCharArray(), pinned)
        assertTrue(BaseKeyFile.keysMatch(keys, pinned))
        assertEquals("Wrong password or damaged file", expectThrows<CryptoException> { BaseKeyFile.open(file, "base@2026".toCharArray(), pinned) }.message)
        val otherBuild = PinnedBaseKeys(Crypto.newEcKeyPair().public, pinned.sig, pinned.resc)
        assertEquals(
            "This base key file belongs to a different build of the app",
            expectThrows<CryptoException> { BaseKeyFile.open(file, "Base@2026".toCharArray(), otherBuild) }.message,
        )
        expectThrows<CryptoException> { BaseKeyFile.open("not json", "x".toCharArray(), pinned) }
        expectThrows<CryptoException> { BaseKeyFile.open("{\"kind\":\"other\"}", "x".toCharArray(), pinned) }
        val stored = BaseKeyFile.decodePrivate(BaseKeyFile.encodePrivate(keys))
        assertTrue(BaseKeyFile.keysMatch(stored, pinned))
        assertFalse(BaseKeyFile.keysMatch(BasePrivateKeys(Crypto.newEcKeyPair().private, keys.sig, keys.resc), pinned))
    }

    @Test
    fun rescuerCredentialIssueQrAndVerify() {
        val teamKey = Crypto.random(32)
        val bundle = Credentials.issue(base, "  Karthik  ", "alpha team!", teamKey, now, 30)
        assertEquals("Karthik", bundle.cert.name)
        assertEquals("ALPHATEAM", bundle.cert.team)
        assertTrue(Credentials.verify(pinned, bundle, now))
        val qr = Credentials.toQr(bundle, "4321".toCharArray())
        assertTrue(Credentials.isCredentialQr(qr))
        assertTrue("QR length ${qr.length}", qr.length < 600)
        val back = Credentials.fromQr(qr, "4321".toCharArray())
        assertEquals(bundle.cert.rescuerId, back.cert.rescuerId)
        assertArrayEquals(teamKey, back.teamKey)
        assertTrue(Credentials.verify(pinned, back, now))
        assertArrayEquals(bundle.signKey.encoded, back.signKey.encoded)
        assertArrayEquals(base.resc.encoded, back.groupKey.encoded)
        // The JSON form kept in the phone's encrypted storage also round-trips.
        assertTrue(Credentials.verify(pinned, RescuerBundle.decode(back.encode()), now))
        expectThrows<CryptoException> { Credentials.fromQr(qr, "1234".toCharArray()) }
        expectThrows<FormatException> { Credentials.fromQr("UMP1:abc", "1234".toCharArray()) }
        expectThrows<IllegalArgumentException> { Credentials.toQr(bundle, "12".toCharArray()) }
        // Expired after 30 days.
        assertFalse(Credentials.verify(pinned, back, now + 31L * 86_400_000))
        // A certificate signed by someone else is rejected.
        val fakeBase = BasePrivateKeys(Crypto.newEcKeyPair().private, Crypto.newEcKeyPair().private, base.resc)
        val fake = Credentials.issue(fakeBase, "Fake", "ALPHA", teamKey, now, 30)
        assertFalse(Credentials.verify(pinned, fake, now))
    }

    @Test
    fun certificateEncodingDetectsTampering() {
        val bundle = Credentials.issue(base, "Priya", "BRAVO", Crypto.random(32), now, 10)
        val enc = bundle.cert.encode()
        val back = RescuerCert.decode(enc)
        assertTrue(back.isValid(pinned.sig, now))
        val renamed = RescuerCert(back.rescuerId, "Mallory", back.team, back.pub, back.issuedAt, back.expiresAt, back.sig)
        assertFalse(renamed.verify(pinned.sig))
        expectThrows<FormatException> { RescuerCert.decode(enc + byteArrayOf(0)) }
        expectThrows<RuntimeException> { RescuerCert.decode(enc.copyOf(enc.size - 3)) }
    }

    @Test
    fun sosIsReadableByBaseAndRescuersButNotByOthers() {
        val victim = Outbox(Ids.newDeviceId(), pinned) { now }
        val sos = TestUtil.sampleSos()
        val p = PacketCodec.decode(PacketCodec.encode(victim.sealedRescue(PacketType.SOS, sos.toBytes()).relayCopy(1)))
        assertTrue(p.flags and PacketFlags.ENC_BASE != 0)
        assertFalse(String(p.payload, Charsets.ISO_8859_1).contains("Ameer"))
        assertEquals(sos, SosPayload.fromBytes(Opener.asBase(base, pinned, p)))
        val rescuer = Credentials.issue(base, "R", "ALPHA", Crypto.random(32), now, 5)
        assertEquals(sos, SosPayload.fromBytes(Opener.asRescuer(rescuer, pinned, p)))
        // Payload moved into another packet (different id) cannot be opened.
        val moved = Packet(p.type, p.flags, Packet.newId(), p.ttl, p.ttl0, p.hops, p.createdAt, p.src, p.dst, p.path, p.payload)
        expectThrows<CryptoException> { Opener.asBase(base, pinned, moved) }
        // Base-only data (heartbeats) cannot be opened by rescuers.
        val hb = victim.sealedBase(PacketType.HEARTBEAT, HeartbeatPayload(Roles.RESCUER, 50, emptyList()).toBytes())
        assertEquals(Roles.RESCUER, HeartbeatPayload.fromBytes(Opener.asBase(base, pinned, hb)).role)
        expectThrows<CryptoException> { Opener.asRescuer(rescuer, pinned, hb) }
    }

    @Test
    fun teamPacketsNeedTheTeamKey() {
        val key = Crypto.random(32)
        val out = Outbox(Ids.newDeviceId(), pinned) { now }
        val p = out.teamSignedByBase(base, PacketType.TASK, "task".toByteArray(), "ALPHA", key)
        assertEquals("TEAM:ALPHA", p.dst)
        assertArrayEquals("task".toByteArray(), Opener.team(key, p))
        expectThrows<CryptoException> { Opener.team(Crypto.random(32), p) }
        assertTrue(Authenticity.check(pinned, p, now))
        val unsignedTask = out.team(PacketType.TASK, "task".toByteArray(), "ALPHA", key)
        assertFalse(Authenticity.check(pinned, unsignedTask, now))
    }

    @Test
    fun repliesFromBaseAndRescuersAreVerified() {
        val out = Outbox(Ids.newDeviceId(), pinned) { now }
        val victimId = Ids.newDeviceId()
        val fromBase = out.signedByBase(base, PacketType.ACK, AckPayload("c1", AckStatus.RECEIVED, at = now).toBytes(), victimId)
        assertTrue(Authenticity.check(pinned, fromBase, now))
        assertNull(Authenticity.rescuerSigner(pinned, fromBase, now))

        val bundle = Credentials.issue(base, "Karthik", "ALPHA", Crypto.random(32), now, 30)
        val ack = AckPayload("c1", AckStatus.ON_THE_WAY, "ALPHA", 12, "Coming", now, "Karthik", bundle.cert.toBase64())
        val fromRescuer = out.signedByRescuer(bundle, PacketType.ACK, ack.toBytes(), victimId)
        val relayed = PacketCodec.decode(PacketCodec.encode(fromRescuer.relayCopy(3)))
        assertTrue(Authenticity.check(pinned, relayed, now))
        assertEquals("Karthik", Authenticity.rescuerSigner(pinned, relayed, now)?.name)
        // Expired certificate.
        assertFalse(Authenticity.check(pinned, relayed, now + 40L * 86_400_000))
        // Someone copies the certificate but signs with their own key.
        val forged = Crypto.signPacket(Crypto.newEcKeyPair().private, out.plain(PacketType.ACK, ack.toBytes(), victimId))
        assertFalse(Authenticity.check(pinned, forged, now))
        // Unsigned reply.
        assertFalse(Authenticity.check(pinned, out.plain(PacketType.ACK, ack.toBytes(), victimId), now))
        // Ordinary messages need no signature.
        assertTrue(Authenticity.check(pinned, out.plain(PacketType.CHAT, ChatPayload("a", "b").toBytes(), Destinations.ALL), now))
        assertNotNull(out)
    }

    @Test
    fun drillFlagAndTtlAreApplied() {
        val out = Outbox(Ids.newDeviceId(), pinned) { now }
        out.drill = true
        out.ttl = 7
        val p = out.plain(PacketType.CHAT, byteArrayOf(1), Destinations.ALL)
        assertTrue(p.isDrill)
        assertEquals(7, p.ttl)
        out.drill = false
        assertFalse(out.sealedRescue(PacketType.SOS, byteArrayOf(1)).isDrill)
    }
}
