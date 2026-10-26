package app.clairix.comms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under an Android Keystore key that works only while the phone
 * is unlocked (setUnlockedDeviceRequired), for what reads conversations: the
 * key that encrypts the identity pickle, the message database's key, stored
 * media and the duress phrase.
 *
 * [KeystoreBox]'s key keeps working while the phone is locked, which the
 * relay connection needs to take in envelopes. This one does not: whoever
 * copies the app's files off a locked phone, or runs code as the app while
 * it is locked, gets nothing the Keystore will open for them. Envelopes that
 * arrive meanwhile wait in the [LockedInbox] until the owner unlocks.
 *
 * On a phone with no secure lock screen there is no locked state, and the key
 * works always, as [KeystoreBox]'s does.
 */
object VaultBox {

    private const val KEY_ALIAS = "clairix_vault_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    /**
     * Decrypts [blob], written under this key or, before it existed, under
     * [KeystoreBox]. The second is reported ([Opened.legacy]) so the caller
     * writes it again under this key; a blob neither key opens throws. While
     * the phone is locked this key refuses before the blob is looked at, so a
     * locked phone never falls back to the other key.
     */
    fun open(blob: ByteArray): Opened = try {
        Opened(decrypt(blob), legacy = false)
    } catch (e: AEADBadTagException) {
        Opened(KeystoreBox.decrypt(blob), legacy = true)
    }

    class Opened(val plain: ByteArray, val legacy: Boolean)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        // During a wipe the key is gone on purpose (see KeystoreBox).
        check(!app.clairix.security.Wiper.isActive) { "Wiping" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Deletes the key; everything encrypted under it becomes unreadable. Used on unpair. */
    @Synchronized
    fun destroy() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}
