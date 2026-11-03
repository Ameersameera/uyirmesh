import com.ameer.uyirmesh.core.BaseKeyFile
import com.ameer.uyirmesh.core.Crypto
import com.ameer.uyirmesh.core.PinnedBaseKeys
import java.io.File

/**
 * Creates a new set of rescue-side keys.
 *  args[0] = output folder, args[1] = password for the base key file
 * Writes base_keys.json (public, goes into app/src/main/assets) and uyirmesh-base.key (private, password protected).
 * After running this the app must be rebuilt, because the public keys are pinned inside the APK.
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val password = args[1].toCharArray()
    val enc = Crypto.newEcKeyPair()
    val sig = Crypto.newEcKeyPair()
    val resc = Crypto.newEcKeyPair()
    val pinned = PinnedBaseKeys(enc.public, sig.public, resc.public)
    val keyFile = BaseKeyFile.create(password, enc, sig, resc)
    // Check before writing.
    val opened = BaseKeyFile.open(keyFile, password, pinned)
    check(BaseKeyFile.keysMatch(opened, pinned))
    File(out, "base_keys.json").writeText(pinned.toJson())
    File(out, "uyirmesh-base.key").writeText(keyFile)
    println("enc ${pinned.encFingerprint}  sig ${pinned.sigFingerprint}  resc ${pinned.rescFingerprint}")
}
