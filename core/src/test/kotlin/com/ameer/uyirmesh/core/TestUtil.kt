package com.ameer.uyirmesh.core

import org.junit.Assert.fail

inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        fail("Expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}: ${e.message}")
    }
    fail("Expected ${T::class.java.simpleName} but nothing was thrown")
    throw IllegalStateException()
}

fun resourceText(name: String): String {
    val stream = TestUtil::class.java.classLoader.getResourceAsStream(name)
        ?: java.io.File("core/src/test/resources/$name").takeIf { it.exists() }?.inputStream()
        ?: java.io.File("src/test/resources/$name").inputStream()
    return stream.use { String(it.readBytes(), Charsets.UTF_8) }
}

object TestUtil {
    /** Keys that stand in for the real base keys during tests. */
    val enc = Crypto.newEcKeyPair()
    val sig = Crypto.newEcKeyPair()
    val resc = Crypto.newEcKeyPair()
    val pinned = PinnedBaseKeys(enc.public, sig.public, resc.public)
    val basePrivate = BasePrivateKeys(enc.private, sig.private, resc.private)

    fun sampleSos(caseId: String = Ids.newId()) = SosPayload(
        caseId = caseId, kind = EmergencyKind.FLOOD, people = 4, children = 1, elderly = 1, injured = true,
        needs = listOf(Needs.WATER, Needs.BOAT), note = "வீட்டின் மேல் மாடியில் இருக்கிறோம் - on the roof",
        battery = 23, lat = 11.6643, lon = 78.146, accuracy = 12.0, locTime = 1_700_000_000_000,
        name = "Ameer", phone = "9876543210", blood = "O+", medical = "Asthma", trigger = Triggers.VOICE,
        cancelHash = Crypto.sha256Hex("secret"), sentAt = 1_700_000_000_000,
    )
}
