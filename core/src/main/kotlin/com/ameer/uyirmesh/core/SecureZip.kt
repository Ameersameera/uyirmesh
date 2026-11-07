package com.ameer.uyirmesh.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Calendar
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a password-protected ZIP file using the WinZip AES-256 format (AE-2), which 7-Zip, WinRAR and most
 * Android file managers can open. Used to export case data from the base so a lost or shared file does not
 * expose victims' names and phone numbers.
 *
 * Format: each file is deflated, then encrypted with AES-256 in counter mode (little-endian counter starting at 1);
 * the key comes from the password with PBKDF2-HMAC-SHA1 (1000 rounds, 16-byte salt); a 10-byte HMAC-SHA1 code
 * protects the encrypted data and a 2-byte check value lets the opening program spot a wrong password.
 */
object SecureZip {
    class Entry(val name: String, val data: ByteArray, val time: Long = System.currentTimeMillis())

    const val MIN_PASSWORD = 6
    private const val SALT = 16
    private const val KEY = 32
    private const val ROUNDS = 1000
    private const val AES_METHOD = 99
    private const val DEFLATE = 8
    private const val VERSION = 51

    fun write(entries: List<Entry>, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.length >= MIN_PASSWORD) { "Password too short" }
        require(entries.isNotEmpty()) { "Nothing to put in the file" }
        val pw = password.toByteArray(Charsets.UTF_8)
        val out = Le()
        val central = Le()
        for (e in entries) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            val packed = deflate(e.data)
            val salt = ByteArray(SALT).also { random.nextBytes(it) }
            val keys = pbkdf2(pw, salt, ROUNDS, 2 * KEY + 2)
            val encKey = keys.copyOfRange(0, KEY)
            val macKey = keys.copyOfRange(KEY, 2 * KEY)
            val check = keys.copyOfRange(2 * KEY, 2 * KEY + 2)
            val enc = ctr(encKey, packed)
            val auth = Mac.getInstance("HmacSHA1").run {
                init(SecretKeySpec(macKey, "HmacSHA1"))
                doFinal(enc)
            }.copyOf(10)
            val size = SALT + 2 + enc.size + 10
            val (time, date) = dos(e.time)
            val flags = 0x0001 or 0x0800 // encrypted, UTF-8 names
            val extra = aesExtra()
            val offset = out.size()

            out.int(0x04034b50); out.short(VERSION); out.short(flags); out.short(AES_METHOD)
            out.short(time); out.short(date)
            out.int(0) // CRC is left out in AE-2
            out.int(size); out.int(e.data.size)
            out.short(name.size); out.short(extra.size)
            out.bytes(name); out.bytes(extra)
            out.bytes(salt); out.bytes(check); out.bytes(enc); out.bytes(auth)

            central.int(0x02014b50); central.short(VERSION); central.short(VERSION); central.short(flags)
            central.short(AES_METHOD); central.short(time); central.short(date)
            central.int(0); central.int(size); central.int(e.data.size)
            central.short(name.size); central.short(extra.size); central.short(0)
            central.short(0); central.short(0); central.int(0)
            central.int(offset)
            central.bytes(name); central.bytes(extra)
        }
        val cdOffset = out.size()
        val cd = central.toByteArray()
        out.bytes(cd)
        out.int(0x06054b50); out.short(0); out.short(0)
        out.short(entries.size); out.short(entries.size)
        out.int(cd.size); out.int(cdOffset); out.short(0)
        return out.toByteArray()
    }

    /** The AES extra field: vendor version 2 (AE-2), "AE", 256-bit key, real method deflate. */
    private fun aesExtra(): ByteArray = Le().apply {
        short(0x9901); short(7); short(2); bytes(byteArrayOf('A'.code.toByte(), 'E'.code.toByte())); byte(3); short(DEFLATE)
    }.toByteArray()

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    /** AES-CTR with the little-endian counter WinZip uses (the JDK's CTR mode counts big-endian). */
    private fun ctr(key: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val blocks = (data.size + 15) / 16
        val counters = ByteArray(blocks * 16)
        for (b in 0 until blocks) {
            var v = (b + 1).toLong()
            for (i in 0 until 8) {
                counters[b * 16 + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val stream = c.doFinal(counters)
        return ByteArray(data.size) { i -> (data[i].toInt() xor stream[i].toInt()).toByte() }
    }

    internal fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (r in 1 until rounds) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    private fun dos(t: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((c.get(Calendar.YEAR) - 1980).coerceIn(0, 127) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    /** Little-endian writer. */
    private class Le : ByteArrayOutputStream() {
        fun byte(v: Int) = write(v and 0xFF)
        fun short(v: Int) {
            write(v and 0xFF)
            write((v ushr 8) and 0xFF)
        }
        fun int(v: Int) {
            short(v and 0xFFFF)
            short((v ushr 16) and 0xFFFF)
        }
        fun bytes(b: ByteArray) = write(b, 0, b.size)
    }
}
