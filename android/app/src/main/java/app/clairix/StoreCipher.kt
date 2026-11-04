package app.clairix

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a [Store] document before it touches the disk and opens it again on
 * load. The scanner's stores are where the owner has been: the location log,
 * the timeline of detections with their positions, the cell-tower baseline,
 * the vehicle trips. In the clear, a copy of the app's files is a map of the
 * owner's life. The radio scanner's recordings (ScannerRecordings, src/suite) are
 * sealed under the same key, a piece at a time ([sealBytes]).
 */
interface StoreCipher {
    fun seal(text: String): ByteArray
    fun open(blob: ByteArray): String

    /**
     * Seals binary [plain] bound to [aad]: [openBytes] refuses it with any other [aad].
     * For records written in pieces (the scanner's recordings), so a piece cannot be
     * moved to another place or file. This default goes through [seal], with [aad]
     * inside the sealed text; [KeystoreStoreCipher] seals the bytes directly, with [aad]
     * as AES-GCM's associated data.
     */
    fun sealBytes(plain: ByteArray, aad: ByteArray): ByteArray {
        val b64 = Base64.getEncoder()
        return seal(b64.encodeToString(aad) + "." + b64.encodeToString(plain))
    }

    /** Opens what [sealBytes] sealed with the same [aad]; throws on anything else. */
    fun openBytes(blob: ByteArray, aad: ByteArray): ByteArray {
        val text = open(blob)
        val dot = text.indexOf('.')
        require(dot >= 0) { "not a sealed record" }
        val b64 = Base64.getDecoder()
        require(b64.decode(text.substring(0, dot)).contentEquals(aad)) { "sealed for another place" }
        return b64.decode(text.substring(dot + 1))
    }

    /**
     * Seals raw bytes that stand alone: a photo the owner chose to keep from the
     * camera sweep ([app.clairix.analysis.SweepPhotos]). The default goes through
     * [seal] as Base64 text, so any cipher can do it; [KeystoreStoreCipher] seals
     * the bytes directly.
     */
    fun sealBytes(bytes: ByteArray): ByteArray = seal(Base64.getEncoder().encodeToString(bytes))

    /** Opens what the one-argument [sealBytes] sealed. */
    fun openBytes(blob: ByteArray): ByteArray = Base64.getDecoder().decode(open(blob))
}

/**
 * AES-256-GCM under an Android Keystore key that never leaves secure hardware.
 *
 * The key is not bound to the device being unlocked. The scanner writes the
 * cell baseline on every poll with the phone in a pocket and the keyguard up,
 * and a key that refuses to work while locked would stop it recording exactly
 * when it matters. What this key buys is that nothing copied off the phone (a
 * device transfer, an image of the data partition, the files pulled over ADB)
 * is readable, and that the files are unreadable before the first unlock after
 * a boot, when the Keystore itself is still locked. It does not protect against
 * code running as this app on a phone that is already unlocked; the passcode
 * and the wipe are for that.
 *
 * The [Wiper] deletes every Keystore alias, so after a wipe whatever survives
 * on disk by accident is ciphertext nobody holds a key to.
 */
object KeystoreStoreCipher : StoreCipher {

    private const val KEY_ALIAS = "clairix_store_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    override fun seal(text: String): ByteArray = sealBytes(text.toByteArray(Charsets.UTF_8))

    override fun open(blob: ByteArray): String = String(openBytes(blob), Charsets.UTF_8)

    override fun sealBytes(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(bytes)
    }

    override fun openBytes(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    override fun sealBytes(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun openBytes(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
