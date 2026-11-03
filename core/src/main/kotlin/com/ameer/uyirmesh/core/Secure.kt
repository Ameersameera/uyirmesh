package com.ameer.uyirmesh.core

/**
 * Builds packets for this phone, applying encryption and signatures.
 *
 *  - [sealedRescue]: readable by the rescue base and by verified rescuers only (SOS, location updates, voice/photo, cancel)
 *  - [sealedBase]: readable by the rescue base only (heartbeats with rescuer positions, volunteer contact details)
 *  - [team]: encrypted with a rescue team key (tasks, task status, team chat)
 *  - [plain]: public messages (chat, I'm safe, citizen reports, find requests)
 * Relays never need to read a packet to forward it.
 */
class Outbox(
    val myId: String,
    val pinned: PinnedBaseKeys,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var ttl: Int = DEFAULT_TTL
    @Volatile var drill: Boolean = false

    private fun flags(extra: Int) = extra or (if (drill) PacketFlags.DRILL else 0)

    fun sealedRescue(type: PacketType, plain: ByteArray, ttl: Int = this.ttl): Packet {
        val id = Packet.newId()
        val box = Crypto.sealMulti(listOf(pinned.enc, pinned.resc), plain, Packet.aadFor(type, id, myId))
        return Packet.create(type, myId, Destinations.BASE, box, ttl, clock(), flags(PacketFlags.ENC_BASE), id)
    }

    fun sealedBase(type: PacketType, plain: ByteArray, ttl: Int = this.ttl): Packet {
        val id = Packet.newId()
        val box = Crypto.sealMulti(listOf(pinned.enc), plain, Packet.aadFor(type, id, myId))
        return Packet.create(type, myId, Destinations.BASE, box, ttl, clock(), flags(PacketFlags.ENC_BASE), id)
    }

    fun plain(type: PacketType, plain: ByteArray, dst: String, ttl: Int = this.ttl): Packet =
        Packet.create(type, myId, dst, plain, ttl, clock(), flags(0))

    fun team(type: PacketType, plain: ByteArray, team: String, teamKey: ByteArray, ttl: Int = this.ttl): Packet {
        val id = Packet.newId()
        val box = Crypto.keyEncrypt(teamKey, plain, Packet.aadFor(type, id, myId))
        return Packet.create(type, myId, Destinations.team(team), box, ttl, clock(), flags(PacketFlags.ENC_TEAM), id)
    }

    /** Base reply or announcement, signed with the base key. */
    fun signedByBase(base: BasePrivateKeys, type: PacketType, plain: ByteArray, dst: String, ttl: Int = this.ttl): Packet =
        Crypto.signPacket(base.sig, plain(type, plain, dst, ttl))

    /** Team message signed by the base (tasks). */
    fun teamSignedByBase(base: BasePrivateKeys, type: PacketType, plain: ByteArray, team: String, teamKey: ByteArray, ttl: Int = this.ttl): Packet =
        Crypto.signPacket(base.sig, team(type, plain, team, teamKey, ttl))

    /** Message from a rescuer that any phone can verify through the rescuer's base-signed certificate. */
    fun signedByRescuer(bundle: RescuerBundle, type: PacketType, plain: ByteArray, dst: String, ttl: Int = this.ttl): Packet =
        Crypto.signPacket(bundle.signKey, plain(type, plain, dst, ttl))

    companion object {
        const val DEFAULT_TTL = 5
    }
}

/** Opens encrypted payloads. All functions throw [CryptoException] when the key does not fit. */
object Opener {
    fun asBase(keys: BasePrivateKeys, pinned: PinnedBaseKeys, p: Packet): ByteArray =
        Crypto.openMulti(keys.enc, pinned.enc, p.payload, p.aad())

    fun asRescuer(bundle: RescuerBundle, pinned: PinnedBaseKeys, p: Packet): ByteArray =
        Crypto.openMulti(bundle.groupKey, pinned.resc, p.payload, p.aad())

    fun team(teamKey: ByteArray, p: Packet): ByteArray = Crypto.keyDecrypt(teamKey, p.payload, p.aad())

    /** Types that are sealed for rescuers as well as the base. */
    val RESCUE_READABLE = setOf(PacketType.SOS, PacketType.SOS_UPDATE, PacketType.SOS_MEDIA, PacketType.SOS_CANCEL)
}

/** Checks that messages which only the base (or a verified rescuer) may send are genuine. */
object Authenticity {
    fun check(pinned: PinnedBaseKeys, p: Packet, now: Long): Boolean = when (p.type) {
        PacketType.ALERT, PacketType.TASK, PacketType.FIND_RESULT -> isBaseSigned(pinned, p)
        PacketType.ACK -> isBaseSigned(pinned, p) || rescuerSigner(pinned, p, now) != null
        else -> true
    }

    fun isBaseSigned(pinned: PinnedBaseKeys, p: Packet): Boolean =
        (p.flags and PacketFlags.SIGNED) != 0 && Crypto.verifyPacket(pinned.sig, p)

    /** For a reply signed by a rescuer: the rescuer's verified certificate, or null. */
    fun rescuerSigner(pinned: PinnedBaseKeys, p: Packet, now: Long): RescuerCert? {
        if (p.type != PacketType.ACK || (p.flags and PacketFlags.SIGNED) == 0) return null
        val ack = try {
            AckPayload.fromBytes(p.payload)
        } catch (e: Exception) {
            return null
        }
        if (ack.cert.isEmpty()) return null
        val cert = try {
            RescuerCert.fromBase64(ack.cert)
        } catch (e: Exception) {
            return null
        }
        if (!cert.isValid(pinned.sig, now)) return null
        return if (Crypto.verifyPacket(cert.pub, p)) cert else null
    }
}
