package com.ameer.uyirmesh.core

import java.util.zip.CRC32

/**
 * Message types carried over the mesh.
 *
 * [qos]: 0 is sent first. Order follows the design: SOS > rescue replies and alerts > chat and reports > voice/photo.
 */
enum class PacketType(val code: Int, val qos: Int) {
    SOS(1, 0),
    SOS_UPDATE(2, 0),
    SOS_MEDIA(3, 3),
    SOS_CANCEL(4, 1),
    ACK(5, 1),
    ALERT(6, 1),
    CHAT(7, 2),
    SAFE(8, 2),
    REPORT(9, 2),
    VOLUNTEER(10, 2),
    HEARTBEAT(11, 2),
    TASK(12, 1),
    TASK_STATUS(13, 1),
    TEAM_CHAT(14, 2),
    FIND_REQUEST(15, 2),
    FIND_RESULT(16, 1),
    MISSING(17, 2),

    /** A rescuer on the way tells the victim how far away they are (rescuer-signed, kept only an hour). */
    PROGRESS(18, 2);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PacketType = byCode[code] ?: throw FormatException("Unknown packet type $code")
    }
}

object PacketFlags {
    const val DRILL = 1
    const val ENC_BASE = 2
    const val ENC_TEAM = 4
    const val SIGNED = 8
}

object Destinations {
    const val BASE = "BASE"
    const val ALL = "*"
    fun team(name: String) = "TEAM:$name"
    fun isTeam(dst: String) = dst.startsWith("TEAM:")
    fun teamName(dst: String) = dst.removePrefix("TEAM:")
}

/** One mesh packet. Immutable; relays create copies with [relayCopy]. */
class Packet(
    val type: PacketType,
    val flags: Int,
    val id: ByteArray,
    val ttl: Int,
    val ttl0: Int,
    val hops: Int,
    val createdAt: Long,
    val src: String,
    val dst: String,
    val path: List<Int>,
    val payload: ByteArray,
    val sig: ByteArray = ByteArray(0),
) {
    init {
        require(id.size == 16) { "Packet id must be 16 bytes" }
    }

    val idHex: String by lazy { Hex.encode(id) }
    val shortId: String get() = idHex.take(8)
    val qos: Int get() = type.qos
    val isDrill: Boolean get() = flags and PacketFlags.DRILL != 0

    /** Copy sent by a relay: one hop used. [relayShort] is added to the path trace when the sender is not the source. */
    fun relayCopy(relayShort: Int?): Packet {
        val newPath = if (relayShort != null && path.size < PacketCodec.MAX_PATH) path + relayShort else path
        return Packet(type, flags, id, ttl - 1, ttl0, hops + 1, createdAt, src, dst, newPath, payload, sig)
    }

    fun withSig(signature: ByteArray): Packet =
        Packet(type, flags or PacketFlags.SIGNED, id, ttl, ttl0, hops, createdAt, src, dst, path, payload, signature)

    /** Additional authenticated data that binds an encrypted payload to this packet. */
    fun aad(): ByteArray = aadFor(type, id, src)

    companion object {
        fun newId(): ByteArray = Crypto.random(16)

        fun aadFor(type: PacketType, id: ByteArray, src: String): ByteArray =
            ByteWriter(64).u8(type.code).raw(id).str8(src).toByteArray()

        fun create(
            type: PacketType,
            src: String,
            dst: String,
            payload: ByteArray,
            ttl: Int,
            now: Long,
            flags: Int = 0,
            id: ByteArray = newId(),
        ): Packet {
            val t = ttl.coerceIn(1, PacketCodec.MAX_TTL)
            return Packet(type, flags, id, t, t, 0, now, src, dst, emptyList(), payload)
        }
    }
}

/**
 * Wire format v1 (big-endian):
 *  'U' 'M' version | type | flags | id[16] | ttl | ttl0 | hops | createdAt(i64)
 *  src(str8) | dst(str8) | pathCount(u8) + pathCount * i32 | payload(u16 len) | sig(u8 len) | CRC-32(i32)
 * The CRC-32 covers every byte before it and is recomputed by each relay (TTL, hops and path change per hop).
 */
object PacketCodec {
    const val VERSION = 1
    const val MAX_TTL = 16
    const val MAX_PATH = 16
    const val MAX_PAYLOAD = 30_000
    const val MAX_PACKET = 32_000

    fun encode(p: Packet): ByteArray {
        require(p.payload.size <= MAX_PAYLOAD) { "Payload too large: ${p.payload.size}" }
        val w = ByteWriter(96 + p.payload.size)
        w.u8(0x55).u8(0x4D).u8(VERSION)
        w.u8(p.type.code).u8(p.flags and 0xFF)
        w.raw(p.id)
        w.u8(p.ttl.coerceIn(0, MAX_TTL)).u8(p.ttl0.coerceIn(0, MAX_TTL)).u8(p.hops.coerceIn(0, 255))
        w.i64(p.createdAt)
        w.str8(p.src).str8(p.dst)
        val path = p.path.take(MAX_PATH)
        w.u8(path.size)
        for (s in path) w.i32(s)
        w.bytes16(p.payload)
        w.bytes8(p.sig)
        val body = w.toByteArray()
        val crc = crc32(body, body.size)
        val out = body.copyOf(body.size + 4)
        out[body.size] = (crc ushr 24).toByte()
        out[body.size + 1] = (crc ushr 16).toByte()
        out[body.size + 2] = (crc ushr 8).toByte()
        out[body.size + 3] = crc.toByte()
        return out
    }

    /** Decodes and validates a packet. Throws [CrcException] on a checksum mismatch and [FormatException] otherwise. */
    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < 40 || bytes.size > MAX_PACKET) throw FormatException("Bad packet size ${bytes.size}")
        val n = bytes.size - 4
        val stored = ((bytes[n].toInt() and 0xFF) shl 24) or ((bytes[n + 1].toInt() and 0xFF) shl 16) or
            ((bytes[n + 2].toInt() and 0xFF) shl 8) or (bytes[n + 3].toInt() and 0xFF)
        if (crc32(bytes, n) != stored) throw CrcException()
        val r = ByteReader(bytes, 0, n)
        if (r.u8() != 0x55 || r.u8() != 0x4D) throw FormatException("Bad magic")
        val version = r.u8()
        if (version != VERSION) throw FormatException("Unsupported version $version")
        val type = PacketType.fromCode(r.u8())
        val flags = r.u8()
        val id = r.raw(16)
        val ttl = r.u8()
        val ttl0 = r.u8()
        val hops = r.u8()
        if (ttl0 > MAX_TTL || ttl > ttl0) throw FormatException("Bad TTL $ttl/$ttl0")
        val createdAt = r.i64()
        val src = r.str8()
        val dst = r.str8()
        if (src.isEmpty() || dst.isEmpty()) throw FormatException("Empty address")
        val pathCount = r.u8()
        if (pathCount > MAX_PATH) throw FormatException("Path too long")
        val path = ArrayList<Int>(pathCount)
        repeat(pathCount) { path.add(r.i32()) }
        val payload = r.bytes16()
        if (payload.size > MAX_PAYLOAD) throw FormatException("Payload too large")
        val sig = r.bytes8()
        if (r.remaining != 0) throw FormatException("Trailing bytes")
        return Packet(type, flags, id, ttl, ttl0, hops, createdAt, src, dst, path, payload, sig)
    }

    /** Bytes covered by a signature: every field that relays must not change. */
    fun signingBytes(p: Packet): ByteArray {
        val w = ByteWriter(64 + p.payload.size)
        w.raw("UM-SIG-1".toByteArray(Charsets.US_ASCII))
        w.u8(p.type.code).u8(p.flags and PacketFlags.SIGNED.inv() and 0xFF).raw(p.id).u8(p.ttl0)
        w.i64(p.createdAt).str8(p.src).str8(p.dst).bytes16(p.payload)
        return w.toByteArray()
    }

    fun crc32(b: ByteArray, len: Int): Int {
        val c = CRC32()
        c.update(b, 0, len)
        return c.value.toInt()
    }

    fun crcHex(bytes: ByteArray): String {
        val n = bytes.size - 4
        if (n < 0) return ""
        return Hex.encode(bytes.copyOfRange(n, bytes.size)).uppercase()
    }
}

class CrcException : RuntimeException("CRC-32 mismatch")
