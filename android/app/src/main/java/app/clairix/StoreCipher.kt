package app.clairix

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a [Store] document before it touches the disk and opens it again on
 * load. The scanner's stores are where the owner has been: the location log,
 * the timeline of detections with their positions, the cell-tower baseline,
 * the vehicle trips. In the clear, a copy of the app's files is a map of the
 * owner's life.
 */
interface StoreCipher {
    fun seal(text: String): ByteArray
    fun open(blob: ByteArray): String
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

    override fun seal(text: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    override fun open(blob: ByteArray): String {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
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
