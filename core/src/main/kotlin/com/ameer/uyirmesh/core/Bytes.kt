package com.ameer.uyirmesh.core

import java.io.ByteArrayOutputStream
import java.util.Base64

class FormatException(message: String) : RuntimeException(message)

/** Big-endian binary writer with length-prefixed fields. */
class ByteWriter(initial: Int = 256) {
    private val out = ByteArrayOutputStream(initial)

    fun u8(v: Int): ByteWriter {
        require(v in 0..0xFF) { "u8 out of range: $v" }
        out.write(v)
        return this
    }

    fun u16(v: Int): ByteWriter {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    fun i32(v: Int): ByteWriter {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    fun i64(v: Long): ByteWriter {
        for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
        return this
    }

    fun raw(b: ByteArray): ByteWriter {
        out.write(b, 0, b.size)
        return this
    }

    /** UTF-8 string with a one-byte length prefix (max 255 bytes). */
    fun str8(s: String): ByteWriter {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 0xFF) { "String too long: ${b.size} bytes" }
        u8(b.size)
        return raw(b)
    }

    fun bytes8(b: ByteArray): ByteWriter {
        require(b.size <= 0xFF) { "Field too long: ${b.size}" }
        u8(b.size)
        return raw(b)
    }

    fun bytes16(b: ByteArray): ByteWriter {
        require(b.size <= 0xFFFF) { "Field too long: ${b.size}" }
        u16(b.size)
        return raw(b)
    }

    fun size(): Int = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Bounds-checked big-endian reader. Throws [FormatException] on malformed input. */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    val remaining: Int get() = end - pos
    val position: Int get() = pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw FormatException("Truncated data (need $n at $pos, end $end)")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun i32(): Int {
        need(4)
        var v = 0
        for (k in 0 until 4) v = (v shl 8) or (buf[pos + k].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (buf[pos + k].toLong() and 0xFF)
        pos += 8
        return v
    }

    fun raw(n: Int): ByteArray {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun str8(): String = String(raw(u8()), Charsets.UTF_8)

    fun bytes8(): ByteArray = raw(u8())

    fun bytes16(): ByteArray = raw(u16())
}

object Hex {
    private val digits = "0123456789abcdef".toCharArray()

    fun encode(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (k in b.indices) {
            val v = b[k].toInt() and 0xFF
            out[2 * k] = digits[v ushr 4]
            out[2 * k + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    fun decode(s: String): ByteArray {
        if (s.length % 2 != 0) throw FormatException("Odd hex length")
        val out = ByteArray(s.length / 2)
        for (k in out.indices) {
            val hi = Character.digit(s[2 * k], 16)
            val lo = Character.digit(s[2 * k + 1], 16)
            if (hi < 0 || lo < 0) throw FormatException("Bad hex")
            out[k] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

object B64 {
    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = try {
        Base64.getDecoder().decode(s.trim())
    } catch (e: IllegalArgumentException) {
        throw FormatException("Bad base64")
    }

    fun encodeUrl(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun decodeUrl(s: String): ByteArray = try {
        Base64.getUrlDecoder().decode(s.trim())
    } catch (e: IllegalArgumentException) {
        throw FormatException("Bad base64")
    }
}

object Ids {
    /** New random device id: 16 lowercase hex characters (8 random bytes). */
    fun newDeviceId(): String = Hex.encode(Crypto.random(8))

    /** New random id for a case, report, alert or request. */
    fun newId(): String = Hex.encode(Crypto.random(8))

    /** 4-byte short id used in packet path traces: the first 8 hex characters of a device id. */
    fun shortOf(deviceId: String): Int {
        val h = deviceId.filter { Character.digit(it, 16) >= 0 }.padEnd(8, '0').take(8)
        return h.toLong(16).toInt()
    }

    fun shortHex(short: Int): String = String.format("%08x", short)

    fun isValidDeviceId(id: String): Boolean = id.length == 16 && id.all { Character.digit(it, 16) >= 0 }
}
