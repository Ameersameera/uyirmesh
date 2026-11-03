package com.ameer.uyirmesh.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * All cryptography used by UyirMesh, built only on standard JCA algorithms that exist on both the JVM and Android:
 *  - ECDH P-256 + HKDF-SHA256 + AES-256-GCM to seal SOS data so that only the rescue base can open it
 *  - ECDSA P-256 (SHA-256) signatures on replies and alerts from the base
 *  - AES-256-GCM with a shared team key for rescue-team traffic
 *  - PBKDF2-HMAC-SHA256 + AES-256-GCM for password-protected files (base key file, rescuer QR)
 */
object Crypto {
    private val rng = SecureRandom()

    const val GCM_IV = 12
    const val GCM_TAG_BITS = 128

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun sha256Hex(s: String): String = Hex.encode(sha256(s.toByteArray(Charsets.UTF_8)))

    /* ---------------- keys ---------------- */

    fun newEcKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rng)
        return g.generateKeyPair()
    }

    fun publicKeyFromX509(b: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))
    } catch (e: Exception) {
        throw CryptoException("Bad public key", e)
    }

    fun privateKeyFromPkcs8(b: ByteArray): PrivateKey = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b))
    } catch (e: Exception) {
        throw CryptoException("Bad private key", e)
    }

    /** Short human-readable fingerprint of a public key, e.g. "3F2A-91C0-7B11". */
    fun fingerprint(pub: PublicKey): String {
        val h = Hex.encode(sha256(pub.encoded)).uppercase().take(12)
        return h.chunked(4).joinToString("-")
    }

    /* ---------------- primitives ---------------- */

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            prev = hmacSha256(prk, prev + info + byteArrayOf(counter.toByte()))
            val n = minOf(prev.size, length - pos)
            System.arraycopy(prev, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null && aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherText: ByteArray, aad: ByteArray? = null): ByteArray = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null && aad.isNotEmpty()) c.updateAAD(aad)
        c.doFinal(cipherText)
    } catch (e: AEADBadTagException) {
        throw CryptoException("Authentication failed", e)
    } catch (e: Exception) {
        throw CryptoException("Decryption failed", e)
    }

    /* ---------------- seal to a public key (ECIES style) ---------------- */

    private val SEAL_INFO = "UM1-seal".toByteArray(Charsets.US_ASCII)

    /**
     * Encrypts [plain] so that only the holder of the private key matching [recipient] can read it.
     * Format: epkLen(u8) | ephemeral public key (X.509) | iv(12) | AES-256-GCM ciphertext + tag
     */
    fun sealTo(recipient: PublicKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val eph = newEcKeyPair()
        val shared = ecdh(eph.private, recipient)
        val epk = eph.public.encoded
        val key = hkdfSha256(shared, epk, SEAL_INFO, 32)
        val iv = random(GCM_IV)
        val ct = aesGcmEncrypt(key, iv, plain, aad)
        return ByteWriter(epk.size + ct.size + 16).bytes8(epk).raw(iv).raw(ct).toByteArray()
    }

    fun openSealed(recipientPrivate: PrivateKey, box: ByteArray, aad: ByteArray): ByteArray {
        try {
            val r = ByteReader(box)
            val epk = r.bytes8()
            val iv = r.raw(GCM_IV)
            val ct = r.raw(r.remaining)
            val shared = ecdh(recipientPrivate, publicKeyFromX509(epk))
            val key = hkdfSha256(shared, epk, SEAL_INFO, 32)
            return aesGcmDecrypt(key, iv, ct, aad)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Cannot open sealed data", e)
        }
    }

    /* ---------------- seal to several public keys ---------------- */

    private val WRAP_INFO = "UM1-wrap".toByteArray(Charsets.US_ASCII)
    private val MAGIC_MULTI = byteArrayOf(0x4D, 0x31) // "M1"

    /** 4-byte key id used to find the right slot in a multi-recipient box. */
    fun keyId(pub: PublicKey): ByteArray = sha256(pub.encoded).copyOfRange(0, 4)

    /**
     * Encrypts [plain] once with a random content key and wraps that key for every recipient
     * (ECDH P-256 with one ephemeral key + HKDF-SHA256 + AES-256-GCM).
     * Format: "M1" | epkLen(u8) | epk | count(u8) | count * [kid(4) | iv(12) | wrappedKey(48)] | iv(12) | ciphertext + tag
     */
    fun sealMulti(recipients: List<PublicKey>, plain: ByteArray, aad: ByteArray): ByteArray {
        require(recipients.isNotEmpty() && recipients.size <= 8) { "1..8 recipients" }
        val eph = newEcKeyPair()
        val epk = eph.public.encoded
        val contentKey = random(32)
        val w = ByteWriter(plain.size + 160 + recipients.size * 64)
        w.raw(MAGIC_MULTI).bytes8(epk).u8(recipients.size)
        for (r in recipients) {
            val kid = keyId(r)
            val wrapKey = hkdfSha256(ecdh(eph.private, r), epk, WRAP_INFO + kid, 32)
            val iv = random(GCM_IV)
            w.raw(kid).raw(iv).raw(aesGcmEncrypt(wrapKey, iv, contentKey, kid))
        }
        val iv = random(GCM_IV)
        w.raw(iv).raw(aesGcmEncrypt(contentKey, iv, plain, aad))
        return w.toByteArray()
    }

    /** Opens a box made by [sealMulti] with the private key that belongs to [myPublic]. */
    fun openMulti(myPrivate: PrivateKey, myPublic: PublicKey, box: ByteArray, aad: ByteArray): ByteArray {
        try {
            val r = ByteReader(box)
            if (!r.raw(2).contentEquals(MAGIC_MULTI)) throw CryptoException("Not a sealed message")
            val epk = r.bytes8()
            val count = r.u8()
            if (count < 1 || count > 8) throw CryptoException("Bad recipient count")
            val myKid = keyId(myPublic)
            var wrapped: ByteArray? = null
            var wrapIv: ByteArray? = null
            repeat(count) {
                val kid = r.raw(4)
                val iv = r.raw(GCM_IV)
                val wk = r.raw(48)
                if (wrapped == null && kid.contentEquals(myKid)) {
                    wrapped = wk
                    wrapIv = iv
                }
            }
            val iv = r.raw(GCM_IV)
            val ct = r.raw(r.remaining)
            val wk = wrapped ?: throw CryptoException("This message is not addressed to this key")
            val wrapKey = hkdfSha256(ecdh(myPrivate, publicKeyFromX509(epk)), epk, WRAP_INFO + myKid, 32)
            val contentKey = aesGcmDecrypt(wrapKey, wrapIv!!, wk, myKid)
            return aesGcmDecrypt(contentKey, iv, ct, aad)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Cannot open sealed data", e)
        }
    }

    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /* ---------------- shared-key (team) encryption ---------------- */

    /** Format: iv(12) | AES-256-GCM ciphertext + tag */
    fun keyEncrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Team key must be 32 bytes" }
        val iv = random(GCM_IV)
        return iv + aesGcmEncrypt(key, iv, plain, aad)
    }

    fun keyDecrypt(key: ByteArray, box: ByteArray, aad: ByteArray): ByteArray {
        if (box.size < GCM_IV + 16) throw CryptoException("Ciphertext too short")
        return aesGcmDecrypt(key, box.copyOfRange(0, GCM_IV), box.copyOfRange(GCM_IV, box.size), aad)
    }

    /* ---------------- signatures ---------------- */

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv, rng)
        s.update(data)
        return s.sign()
    }

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        if (sig.isEmpty()) {
            false
        } else {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(data)
            s.verify(sig)
        }
    } catch (e: Exception) {
        false
    }

    fun signPacket(priv: PrivateKey, p: Packet): Packet = p.withSig(sign(priv, PacketCodec.signingBytes(p)))

    fun verifyPacket(pub: PublicKey, p: Packet): Boolean = verify(pub, PacketCodec.signingBytes(p), p.sig)

    /* ---------------- password-based encryption ---------------- */

    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, length * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Format: "PB1" | iterations(i32) | salt(16) | iv(12) | ciphertext + tag */
    fun passwordSeal(password: CharArray, plain: ByteArray, iterations: Int = 120_000): ByteArray {
        val salt = random(16)
        val key = pbkdf2(password, salt, iterations)
        val iv = random(GCM_IV)
        val ct = aesGcmEncrypt(key, iv, plain, MAGIC_PB)
        return ByteWriter(plain.size + 64).raw(MAGIC_PB).i32(iterations).raw(salt).raw(iv).raw(ct).toByteArray()
    }

    fun passwordOpen(password: CharArray, box: ByteArray): ByteArray {
        val r = ByteReader(box)
        val magic = r.raw(3)
        if (!magic.contentEquals(MAGIC_PB)) throw CryptoException("Not a password-protected file")
        val iterations = r.i32()
        if (iterations < 1_000 || iterations > 5_000_000) throw CryptoException("Bad iteration count")
        val salt = r.raw(16)
        val iv = r.raw(GCM_IV)
        val ct = r.raw(r.remaining)
        val key = pbkdf2(password, salt, iterations)
        try {
            return aesGcmDecrypt(key, iv, ct, MAGIC_PB)
        } catch (e: CryptoException) {
            throw CryptoException("Wrong password or damaged file", e)
        }
    }

    private val MAGIC_PB = "PB1".toByteArray(Charsets.US_ASCII)
}
