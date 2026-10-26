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
 * A phone with no secure lock screen has no locked state, and Android 14
 * refuses to make such a key there at all ("User ECDH key missing": the key
 * hangs off the lock screen's credential, and there is none). Those phones
 * get [OPEN_ALIAS], a key with no unlock condition, which is all the lock
 * screen they have allows. Once the owner sets a PIN, pattern or password the
 * next write makes the unlock-bound key, and whatever is read under the open
 * one is reported [Opened.legacy] so its owner writes it again under the
 * unlock-bound key; removing the lock screen moves things back the same way.
 */
object VaultBox {

    /** Works only while the phone is unlocked. */
    private const val LOCKED_ALIAS = "clairix_vault_v1"
    /** For a phone with no secure lock screen, where the key above cannot be made. */
    private const val OPEN_ALIAS = "clairix_vault_open_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, writeKey().key)
        return cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts [blob], written under either vault key or, before they existed,
     * under [KeystoreBox]. Anything not under the key [encrypt] uses now is
     * reported ([Opened.legacy]) so the caller writes it again; a blob no key
     * opens throws. The unlock-bound key is tried first, and while the phone is
     * locked it refuses before the blob is looked at, so a locked phone never
     * falls back to a key that would open.
     */
    fun open(blob: ByteArray): Opened {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val ks = keyStore()
        val current = preferred()
        for (alias in listOf(LOCKED_ALIAS, OPEN_ALIAS)) {
            try {
                val key = existing(ks, alias) ?: continue
                return Opened(decrypt(key, blob), legacy = alias != current)
            } catch (e: AEADBadTagException) {
                // Not this key's; the next one may be.
            } catch (e: Exception) {
                // The unlock-bound key refusing on a phone with a lock screen means
                // the phone is locked: stop here. On one without, it will never work
                // again (Android disables it when the lock screen is removed), and
                // what was written under it is gone; the open key may still hold
                // this blob.
                if (alias != LOCKED_ALIAS || Vault.deviceSecure() != false) throw e
            }
        }
        return Opened(KeystoreBox.decrypt(blob), legacy = true)
    }

    class Opened(val plain: ByteArray, val legacy: Boolean)

    private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    private class Chosen(val alias: String, val key: SecretKey)

    /**
     * The Keystore would not make the unlock-bound key, with the lock screen
     * as it was then ([Refusal.secure]). Asked again once that changes: a PIN
     * set since is the moment the key can be made.
     */
    @Volatile
    private var refusal: Refusal? = null

    private class Refusal(val secure: Boolean?)

    /** Whether the unlock-bound key is the one to write under, if it is not there yet. */
    private fun wantLocked(): Boolean {
        val secure = Vault.deviceSecure()
        return secure != false && refusal.let { it == null || it.secure != secure }
    }

    /** The alias [writeKey] will choose, found without making a key. */
    private fun preferred(): String = if (wantLocked()) LOCKED_ALIAS else OPEN_ALIAS

    /**
     * The key new writes go under: the unlock-bound one while the phone has a
     * secure lock screen, the open one otherwise (and when the Keystore will
     * not make the unlock-bound key even with a lock screen set, which some
     * phones do). Without a lock screen there is no locked state for the
     * unlock-bound key to wait out, and once the owner removes their lock
     * screen Android may disable the keys bound to it, so nothing new is
     * written under it then.
     */
    @Synchronized
    private fun writeKey(): Chosen {
        val ks = keyStore()
        if (wantLocked()) {
            existing(ks, LOCKED_ALIAS)?.let { return Chosen(LOCKED_ALIAS, it) }
            runCatching { generate(LOCKED_ALIAS, unlockedDeviceRequired = true) }
                .onSuccess { return Chosen(LOCKED_ALIAS, it) }
                .onFailure { if (it is WipingException) throw it else refusal = Refusal(Vault.deviceSecure()) }
        }
        existing(ks, OPEN_ALIAS)?.let { return Chosen(OPEN_ALIAS, it) }
        return Chosen(OPEN_ALIAS, generate(OPEN_ALIAS, unlockedDeviceRequired = false))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun existing(ks: KeyStore, alias: String): SecretKey? =
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun generate(alias: String, unlockedDeviceRequired: Boolean): SecretKey {
        // During a wipe the keys are gone on purpose (see KeystoreBox).
        if (app.clairix.security.Wiper.isActive) throw WipingException()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(unlockedDeviceRequired)
                .build()
        )
        return generator.generateKey()
    }

    private class WipingException : IllegalStateException("Wiping")

    /** Deletes both keys; everything encrypted under them becomes unreadable. Used on unpair. */
    @Synchronized
    fun destroy() {
        runCatching {
            val ks = keyStore()
            for (alias in listOf(LOCKED_ALIAS, OPEN_ALIAS)) if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    /** Which key new writes go under, for tests: true for the unlock-bound one. */
    @androidx.annotation.VisibleForTesting
    fun writesUnlockBound(): Boolean = writeKey().alias == LOCKED_ALIAS
}
