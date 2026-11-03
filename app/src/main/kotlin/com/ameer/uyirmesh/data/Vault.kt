package com.ameer.uyirmesh.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted file storage. Every file is encrypted with AES-256-GCM using a key kept in the
 * Android Keystore (the key never leaves the phone's secure hardware/keystore).
 * If a phone's keystore is broken, a key in the app's private storage is used instead and
 * [usingKeystore] is false (shown in the self-test).
 */
class Vault(context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val sp = context.getSharedPreferences("uyirmesh_vault", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile var usingKeystore: Boolean = true
        private set

    private val key: SecretKey by lazy { loadKey() }

    private fun loadKey(): SecretKey {
        if (!sp.getBoolean("software", false)) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                val k = gen.generateKey()
                // Prove the key works before relying on it.
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, k)
                val ct = c.doFinal(byteArrayOf(1, 2, 3))
                val d = Cipher.getInstance(TRANSFORM)
                d.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, c.iv))
                check(d.doFinal(ct).contentEquals(byteArrayOf(1, 2, 3)))
                return k
            } catch (e: Exception) {
                sp.edit().putBoolean("software", true).apply()
            }
        }
        usingKeystore = false
        val stored = sp.getString("swkey", null)
        val raw = if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            ByteArray(32).also {
                SecureRandom().nextBytes(it)
                sp.edit().putString("swkey", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
            }
        }
        return SecretKeySpec(raw, "AES")
    }

    private fun file(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun write(name: String, data: ByteArray) {
        synchronized(lock) {
            val c = Cipher.getInstance(TRANSFORM)
            if (usingKeystoreKey()) {
                c.init(Cipher.ENCRYPT_MODE, key)
            } else {
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            val iv = c.iv
            val ct = c.doFinal(data)
            val out = ByteArray(2 + iv.size + ct.size)
            out[0] = 'V'.code.toByte()
            out[1] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 2, iv.size)
            System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
            val target = file(name)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun read(name: String): ByteArray? {
        synchronized(lock) {
            val f = file(name)
            if (!f.exists()) return null
            return try {
                val b = f.readBytes()
                if (b.size < 3 || b[0] != 'V'.code.toByte()) return null
                val ivLen = b[1].toInt()
                val iv = b.copyOfRange(2, 2 + ivLen)
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                c.doFinal(b, 2 + ivLen, b.size - 2 - ivLen)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun writeText(name: String, text: String) = write(name, text.toByteArray(Charsets.UTF_8))

    fun readText(name: String): String? = read(name)?.let { String(it, Charsets.UTF_8) }

    fun exists(name: String): Boolean = file(name).exists()

    fun delete(name: String) {
        synchronized(lock) { file(name).delete() }
    }

    fun list(prefix: String): List<String> = dir.list()?.filter { it.startsWith(prefix) && !it.endsWith(".tmp") }.orEmpty()

    /** Quick health check for the self-test screen. */
    fun selfTest(): Boolean = try {
        write("selftest", byteArrayOf(9, 8, 7))
        val ok = read("selftest")?.contentEquals(byteArrayOf(9, 8, 7)) == true
        delete("selftest")
        ok
    } catch (e: Exception) {
        false
    }

    private fun usingKeystoreKey(): Boolean {
        key // make sure the key is loaded
        return usingKeystore
    }

    companion object {
        private const val ALIAS = "uyirmesh_vault_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
