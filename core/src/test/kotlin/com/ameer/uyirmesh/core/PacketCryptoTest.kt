package com.ameer.uyirmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCryptoTest {
    private val now = 1_750_000_000_000L

    private fun sample(payload: ByteArray = "hello".toByteArray()) =
        Packet.create(PacketType.SOS, "0123456789abcdef", Destinations.BASE, payload, 5, now, PacketFlags.DRILL)

    @Test
    fun packetEncodeDecodeRoundTrip() {
        val p = sample().relayCopy(Ids.shortOf("aaaaaaaa00000000")).relayCopy(Ids.shortOf("bbbbbbbb00000000"))
        val back = PacketCodec.decode(PacketCodec.encode(p))
        assertEquals(PacketType.SOS, back.type)
        assertTrue(back.isDrill)
        assertEquals(p.idHex, back.idHex)
        assertEquals(3, back.ttl)
        assertEquals(5, back.ttl0)
        assertEquals(2, back.hops)
        assertEquals(now, back.createdAt)
        assertEquals("0123456789abcdef", back.src)
        assertEquals(Destinations.BASE, back.dst)
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), back.path.map { Ids.shortHex(it) })
        assertArrayEquals("hello".toByteArray(), back.payload)
    }

    @Test
    fun relayCopyUsesOneHop() {
        val p = sample()
        val c = p.relayCopy(null)
        assertEquals(4, c.ttl)
        assertEquals(1, c.hops)
        assertEquals(emptyList<Int>(), c.path)
        assertEquals(p.idHex, c.idHex)
    }

    @Test
    fun everySingleByteCorruptionIsDetected() {
        val bytes = PacketCodec.encode(sample(ByteArray(200) { it.toByte() }))
        for (i in bytes.indices) {
            val bad = bytes.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            val e = expectThrows<RuntimeException> { PacketCodec.decode(bad) }
            assertTrue("byte $i: ${e::class.java.simpleName}", e is CrcException)
        }
        assertEquals(8, PacketCodec.crcHex(bytes).length)
    }

    @Test
    fun malformedPacketsAreRejected() {
        expectThrows<FormatException> { PacketCodec.decode(ByteArray(10)) }
        expectThrows<FormatException> { PacketCodec.decode(ByteArray(40_000)) }
        // Valid CRC but impossible TTL (ttl > ttl0).
        val p = Packet(PacketType.CHAT, 0, ByteArray(16), 9, 5, 0, now, "a", "*", emptyList(), ByteArray(0))
        expectThrows<FormatException> { PacketCodec.decode(PacketCodec.encode(p)) }
        expectThrows<IllegalArgumentException> { PacketCodec.encode(sample(ByteArray(PacketCodec.MAX_PAYLOAD + 1))) }
        expectThrows<FormatException> { PacketType.fromCode(99) }
    }

    @Test
    fun largestMediaPacketFitsTheTransportLimit() {
        val media = SosMedia("case", "audio/amr", 15_000, ByteArray(SosMedia.MAX_MEDIA_BYTES), now).toBytes()
        val out = Outbox("0123456789abcdef", TestUtil.pinned) { now }
        val p = out.sealedRescue(PacketType.SOS_MEDIA, media)
        var relayed = p
        repeat(16) { relayed = relayed.relayCopy(it) }
        val bytes = PacketCodec.encode(relayed)
        assertTrue("size ${bytes.size}", bytes.size <= PacketCodec.MAX_PACKET)
        assertTrue(bytes.size < 32_768)
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = Crypto.hkdfSha256(
            Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            Hex.decode("000102030405060708090a0b0c"),
            Hex.decode("f0f1f2f3f4f5f6f7f8f9"), 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(okm),
        )
    }

    @Test
    fun sealMultiOpensForEveryRecipientOnly() {
        val a = Crypto.newEcKeyPair()
        val b = Crypto.newEcKeyPair()
        val other = Crypto.newEcKeyPair()
        val aad = "aad".toByteArray()
        val plain = "SOS details தமிழ்".toByteArray()
        val box = Crypto.sealMulti(listOf(a.public, b.public), plain, aad)
        assertArrayEquals(plain, Crypto.openMulti(a.private, a.public, box, aad))
        assertArrayEquals(plain, Crypto.openMulti(b.private, b.public, box, aad))
        expectThrows<CryptoException> { Crypto.openMulti(other.private, other.public, box, aad) }
        expectThrows<CryptoException> { Crypto.openMulti(a.private, a.public, box, "other".toByteArray()) }
        // A wrong private key presented with a recipient's public key must fail too.
        expectThrows<CryptoException> { Crypto.openMulti(other.private, a.public, box, aad) }
        for (i in listOf(5, box.size / 2, box.size - 1)) {
            val bad = box.copyOf()
            bad[i] = (bad[i].toInt() xor 0x40).toByte()
            expectThrows<CryptoException> { Crypto.openMulti(a.private, a.public, bad, aad) }
        }
        expectThrows<CryptoException> { Crypto.openMulti(a.private, a.public, ByteArray(3), aad) }
        // Same plaintext encrypts differently every time.
        assertFalse(box.contentEquals(Crypto.sealMulti(listOf(a.public, b.public), plain, aad)))
    }

    @Test
    fun sealToSingleRecipientStillWorks() {
        val a = Crypto.newEcKeyPair()
        val box = Crypto.sealTo(a.public, byteArrayOf(1, 2, 3), byteArrayOf(9))
        assertArrayEquals(byteArrayOf(1, 2, 3), Crypto.openSealed(a.private, box, byteArrayOf(9)))
        expectThrows<CryptoException> { Crypto.openSealed(Crypto.newEcKeyPair().private, box, byteArrayOf(9)) }
    }

    @Test
    fun teamKeyEncryption() {
        val key = Crypto.random(32)
        val box = Crypto.keyEncrypt(key, "team".toByteArray(), byteArrayOf(1))
        assertArrayEquals("team".toByteArray(), Crypto.keyDecrypt(key, box, byteArrayOf(1)))
        expectThrows<CryptoException> { Crypto.keyDecrypt(Crypto.random(32), box, byteArrayOf(1)) }
        expectThrows<CryptoException> { Crypto.keyDecrypt(key, box, byteArrayOf(2)) }
        expectThrows<CryptoException> { Crypto.keyDecrypt(key, ByteArray(5), byteArrayOf(1)) }
    }

    @Test
    fun signaturesSurviveRelaysButNotTampering() {
        val k = Crypto.newEcKeyPair()
        val signed = Crypto.signPacket(k.private, sample())
        assertTrue(Crypto.verifyPacket(k.public, signed))
        val relayed = PacketCodec.decode(PacketCodec.encode(signed.relayCopy(7).relayCopy(8)))
        assertTrue(Crypto.verifyPacket(k.public, relayed))
        assertFalse(Crypto.verifyPacket(Crypto.newEcKeyPair().public, relayed))
        val tampered = Packet(
            relayed.type, relayed.flags, relayed.id, relayed.ttl, relayed.ttl0, relayed.hops, relayed.createdAt,
            relayed.src, "*", relayed.path, relayed.payload, relayed.sig,
        )
        assertFalse(Crypto.verifyPacket(k.public, tampered))
        val tamperedPayload = Packet(
            relayed.type, relayed.flags, relayed.id, relayed.ttl, relayed.ttl0, relayed.hops, relayed.createdAt,
            relayed.src, relayed.dst, relayed.path, "hellO".toByteArray(), relayed.sig,
        )
        assertFalse(Crypto.verifyPacket(k.public, tamperedPayload))
        assertFalse(Crypto.verify(k.public, byteArrayOf(1), ByteArray(0)))
        assertFalse(Crypto.verify(k.public, byteArrayOf(1), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun passwordProtection() {
        val box = Crypto.passwordSeal("correct horse".toCharArray(), "secret".toByteArray(), 2_000)
        assertArrayEquals("secret".toByteArray(), Crypto.passwordOpen("correct horse".toCharArray(), box))
        val e = expectThrows<CryptoException> { Crypto.passwordOpen("wrong".toCharArray(), box) }
        assertEquals("Wrong password or damaged file", e.message)
        expectThrows<CryptoException> { Crypto.passwordOpen("x".toCharArray(), "XYZ123".toByteArray() + ByteArray(40)) }
    }

    @Test
    fun keysEncodeAndFingerprint() {
        val k = Crypto.newEcKeyPair()
        assertArrayEquals(k.public.encoded, Crypto.publicKeyFromX509(k.public.encoded).encoded)
        assertArrayEquals(k.private.encoded, Crypto.privateKeyFromPkcs8(k.private.encoded).encoded)
        assertTrue(Crypto.fingerprint(k.public).matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")))
        expectThrows<CryptoException> { Crypto.publicKeyFromX509(byteArrayOf(1, 2, 3)) }
        assertEquals(4, Crypto.keyId(k.public).size)
    }
}
