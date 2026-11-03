package com.ameer.uyirmesh.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec

/**
 * Public keys of the rescue side, built into the app (key pinning):
 *  - [enc]: rescue base encryption key (SOS data is sealed to it)
 *  - [sig]: rescue base signing key (verifies replies, alerts, tasks and rescuer ID cards)
 *  - [resc]: rescuer group encryption key (SOS data is also sealed to it, so any verified rescuer can read it)
 */
class PinnedBaseKeys(val enc: PublicKey, val sig: PublicKey, val resc: PublicKey) {
    val encFingerprint: String get() = Crypto.fingerprint(enc)
    val sigFingerprint: String get() = Crypto.fingerprint(sig)
    val rescFingerprint: String get() = Crypto.fingerprint(resc)

    fun toJson(): String = Json.stringify(
        linkedMapOf(
            "v" to 2, "enc" to B64.encode(enc.encoded), "sig" to B64.encode(sig.encoded), "resc" to B64.encode(resc.encoded),
        )
    )

    companion object {
        fun fromJson(text: String): PinnedBaseKeys {
            val m = Json.parseObject(text)
            return PinnedBaseKeys(
                Crypto.publicKeyFromX509(B64.decode(m.str("enc"))),
                Crypto.publicKeyFromX509(B64.decode(m.str("sig"))),
                Crypto.publicKeyFromX509(B64.decode(m.str("resc"))),
            )
        }
    }
}

/** Private keys of the rescue side, only present on the base phone after importing the base key file. */
class BasePrivateKeys(val enc: PrivateKey, val sig: PrivateKey, val resc: PrivateKey)

/**
 * The base key file: the private keys protected with a password (PBKDF2-HMAC-SHA256 + AES-256-GCM).
 * The public keys are stored in clear so that the app can check that the file belongs to this build.
 */
object BaseKeyFile {
    private const val KIND = "uyirmesh-base-key"

    fun create(password: CharArray, enc: KeyPair, sig: KeyPair, resc: KeyPair, iterations: Int = 200_000): String {
        val secret = Json.toBytes(
            linkedMapOf(
                "enc" to B64.encode(enc.private.encoded),
                "sig" to B64.encode(sig.private.encoded),
                "resc" to B64.encode(resc.private.encoded),
            )
        )
        return Json.stringify(
            linkedMapOf(
                "v" to 2, "kind" to KIND,
                "encPub" to B64.encode(enc.public.encoded),
                "sigPub" to B64.encode(sig.public.encoded),
                "rescPub" to B64.encode(resc.public.encoded),
                "box" to B64.encode(Crypto.passwordSeal(password, secret, iterations)),
            )
        )
    }

    /** Opens the file. Throws [CryptoException] on a wrong password or when the keys do not match [pinned]. */
    fun open(text: String, password: CharArray, pinned: PinnedBaseKeys): BasePrivateKeys {
        val m = try {
            Json.parseObject(text.trim())
        } catch (e: Exception) {
            throw CryptoException("This is not a UyirMesh base key file")
        }
        if (m.str("kind") != KIND) throw CryptoException("This is not a UyirMesh base key file")
        val samePublic = try {
            B64.decode(m.str("encPub")).contentEquals(pinned.enc.encoded) &&
                B64.decode(m.str("sigPub")).contentEquals(pinned.sig.encoded) &&
                B64.decode(m.str("rescPub")).contentEquals(pinned.resc.encoded)
        } catch (e: Exception) {
            false
        }
        if (!samePublic) throw CryptoException("This base key file belongs to a different build of the app")
        val box = try {
            B64.decode(m.str("box"))
        } catch (e: Exception) {
            throw CryptoException("The base key file is damaged")
        }
        val secret = Json.objectFromBytes(Crypto.passwordOpen(password, box))
        val keys = decodePrivateMap(secret)
        if (!keysMatch(keys, pinned)) throw CryptoException("The keys in this file are damaged")
        return keys
    }

    /** Proves that the private keys belong to the pinned public keys. */
    fun keysMatch(keys: BasePrivateKeys, pinned: PinnedBaseKeys): Boolean = try {
        val probe = Crypto.random(32)
        val sigOk = Crypto.verify(pinned.sig, probe, Crypto.sign(keys.sig, probe))
        val encOk = Crypto.openMulti(keys.enc, pinned.enc, Crypto.sealMulti(listOf(pinned.enc), probe, probe), probe)
            .contentEquals(probe)
        val rescOk = Crypto.openMulti(keys.resc, pinned.resc, Crypto.sealMulti(listOf(pinned.resc), probe, probe), probe)
            .contentEquals(probe)
        sigOk && encOk && rescOk
    } catch (e: Exception) {
        false
    }

    /** Plain form, only used inside the app's encrypted storage. */
    fun encodePrivate(keys: BasePrivateKeys): String = Json.stringify(
        linkedMapOf(
            "enc" to B64.encode(keys.enc.encoded), "sig" to B64.encode(keys.sig.encoded), "resc" to B64.encode(keys.resc.encoded),
        )
    )

    fun decodePrivate(text: String): BasePrivateKeys = decodePrivateMap(Json.parseObject(text))

    private fun decodePrivateMap(m: Map<String, Any?>) = BasePrivateKeys(
        Crypto.privateKeyFromPkcs8(B64.decode(m.str("enc"))),
        Crypto.privateKeyFromPkcs8(B64.decode(m.str("sig"))),
        Crypto.privateKeyFromPkcs8(B64.decode(m.str("resc"))),
    )
}

/**
 * Public part of a rescuer's ID card, signed by the base: who the rescuer is and the rescuer's own signing key.
 * It travels inside rescuer messages so that any phone can check it with the pinned base key
 * (a two-level certificate chain: base -> rescuer -> message).
 */
class RescuerCert(
    val rescuerId: String,
    val name: String,
    val team: String,
    val pub: PublicKey,
    val issuedAt: Long,
    val expiresAt: Long,
    val sig: ByteArray,
) {
    fun signedBytes(): ByteArray = signedBytes(rescuerId, name, team, pub, issuedAt, expiresAt)

    fun isExpired(now: Long): Boolean = now > expiresAt

    fun verify(baseSig: PublicKey): Boolean = Crypto.verify(baseSig, signedBytes(), sig)

    fun isValid(baseSig: PublicKey, now: Long): Boolean = !isExpired(now) && verify(baseSig)

    fun encode(): ByteArray = ByteWriter(256).raw(MAGIC).str8(rescuerId).str8(name).str8(team).bytes8(pub.encoded)
        .i64(issuedAt).i64(expiresAt).bytes8(sig).toByteArray()

    fun toBase64(): String = B64.encode(encode())

    companion object {
        private val MAGIC = "RC1".toByteArray(Charsets.US_ASCII)

        fun signedBytes(id: String, name: String, team: String, pub: PublicKey, issued: Long, expires: Long): ByteArray =
            ByteWriter(256).raw("UM-RC-2".toByteArray(Charsets.US_ASCII)).str8(id).str8(name).str8(team)
                .bytes8(pub.encoded).i64(issued).i64(expires).toByteArray()

        fun decode(b: ByteArray): RescuerCert {
            val r = ByteReader(b)
            if (!r.raw(3).contentEquals(MAGIC)) throw FormatException("Not a rescuer certificate")
            val c = RescuerCert(
                r.str8(), r.str8(), r.str8(), Crypto.publicKeyFromX509(r.bytes8()), r.i64(), r.i64(), r.bytes8(),
            )
            if (r.remaining != 0) throw FormatException("Trailing bytes in certificate")
            return c
        }

        fun fromBase64(s: String): RescuerCert = decode(B64.decode(s))
    }
}

/**
 * Everything a rescuer phone receives from the base in the PIN-protected QR code:
 * the signed certificate, the rescuer's private signing key, the team key (team chat and tasks)
 * and the rescuer group key (to open SOS messages directly).
 */
class RescuerBundle(
    val cert: RescuerCert,
    val signKey: PrivateKey,
    val teamKey: ByteArray,
    val groupKey: PrivateKey,
) {
    fun encode(): String = Json.stringify(
        linkedMapOf(
            "c" to B64.encode(cert.encode()), "sk" to B64.encode(signKey.encoded),
            "tk" to B64.encode(teamKey), "gk" to B64.encode(groupKey.encoded),
        )
    )

    /** Compact binary form used inside the QR code (keeps the QR small and easy to scan). */
    fun toBytes(): ByteArray = ByteWriter(400).raw(MAGIC).bytes16(cert.encode())
        .raw(EcKeys.scalar(signKey)).raw(teamKey).raw(EcKeys.scalar(groupKey)).toByteArray()

    companion object {
        private val MAGIC = "RB3".toByteArray(Charsets.US_ASCII)

        fun fromBytes(b: ByteArray): RescuerBundle {
            val r = ByteReader(b)
            if (!r.raw(3).contentEquals(MAGIC)) throw FormatException("Not a rescuer ID")
            val cert = RescuerCert.decode(r.bytes16())
            val params = (cert.pub as ECPublicKey).params
            val signKey = EcKeys.privateFromScalar(r.raw(32), params)
            val teamKey = r.raw(32)
            val groupKey = EcKeys.privateFromScalar(r.raw(32), params)
            if (r.remaining != 0) throw FormatException("Trailing bytes in rescuer ID")
            return RescuerBundle(cert, signKey, teamKey, groupKey)
        }

        fun decode(text: String): RescuerBundle {
            val m = Json.parseObject(text)
            val teamKey = B64.decode(m.str("tk"))
            if (teamKey.size != 32) throw FormatException("Bad team key")
            return RescuerBundle(
                RescuerCert.decode(B64.decode(m.str("c"))),
                Crypto.privateKeyFromPkcs8(B64.decode(m.str("sk"))),
                teamKey,
                Crypto.privateKeyFromPkcs8(B64.decode(m.str("gk"))),
            )
        }
    }
}

/** Raw P-256 private key scalars (32 bytes) for compact storage in QR codes. */
object EcKeys {
    fun scalar(priv: PrivateKey): ByteArray {
        val s = (priv as? ECPrivateKey)?.s ?: throw CryptoException("Not an EC private key")
        val raw = s.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw CryptoException("Bad EC private key")
        }
    }

    fun privateFromScalar(s: ByteArray, params: java.security.spec.ECParameterSpec): PrivateKey = try {
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, s), params))
    } catch (e: Exception) {
        throw CryptoException("Bad private key", e)
    }
}

object Credentials {
    private const val PREFIX = "UMR3:"
    private const val QR_ITERATIONS = 60_000

    /** Creates a rescuer ID on the base phone. [teamKey] must be the 32-byte key of [team]. */
    fun issue(
        base: BasePrivateKeys,
        name: String,
        team: String,
        teamKey: ByteArray,
        now: Long,
        validDays: Int,
    ): RescuerBundle {
        require(teamKey.size == 32) { "Team key must be 32 bytes" }
        require(name.isNotBlank() && team.isNotBlank()) { "Name and team are required" }
        val kp = Crypto.newEcKeyPair()
        val id = Ids.newId()
        val cleanName = name.trim().take(40)
        val cleanTeam = cleanTeamName(team)
        val expires = now + validDays.coerceIn(1, 365) * 86_400_000L
        val sig = Crypto.sign(base.sig, RescuerCert.signedBytes(id, cleanName, cleanTeam, kp.public, now, expires))
        return RescuerBundle(RescuerCert(id, cleanName, cleanTeam, kp.public, now, expires, sig), kp.private, teamKey, base.resc)
    }

    fun cleanTeamName(team: String): String =
        team.trim().uppercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(20).ifEmpty { "TEAM" }

    /** Checks the certificate signature and that the bundle's keys belong together. */
    fun verify(pinned: PinnedBaseKeys, b: RescuerBundle, now: Long): Boolean {
        if (!b.cert.isValid(pinned.sig, now)) return false
        val probe = Crypto.random(16)
        return try {
            Crypto.verify(b.cert.pub, probe, Crypto.sign(b.signKey, probe)) &&
                Crypto.openMulti(b.groupKey, pinned.resc, Crypto.sealMulti(listOf(pinned.resc), probe, probe), probe)
                    .contentEquals(probe)
        } catch (e: Exception) {
            false
        }
    }

    fun toQr(b: RescuerBundle, pin: CharArray): String {
        require(pin.size >= 4) { "PIN must have at least 4 digits" }
        return PREFIX + B64.encodeUrl(Crypto.passwordSeal(pin, b.toBytes(), QR_ITERATIONS))
    }

    fun isCredentialQr(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** Throws [CryptoException] on a wrong PIN and [FormatException] for other QR codes. */
    fun fromQr(text: String, pin: CharArray): RescuerBundle {
        val t = text.trim()
        if (!isCredentialQr(t)) throw FormatException("Not a UyirMesh rescuer QR code")
        val plain = Crypto.passwordOpen(pin, B64.decodeUrl(t.removePrefix(PREFIX)))
        return RescuerBundle.fromBytes(plain)
    }
}
