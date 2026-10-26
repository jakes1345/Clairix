package app.clairix.comms

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for what reads conversations: the key that encrypts the
 * identity pickle, the message database's key, stored media, the duress
 * phrase and the comms log. Out of reach while the phone is locked.
 *
 * Everything is encrypted with one random data key, and only that key is
 * wrapped by the Android Keystore: by a key that works only while the phone
 * is unlocked (setUnlockedDeviceRequired) when it has a secure lock screen,
 * and by a key with no condition when it has none (there is no locked state
 * to wait out, and Android 14 refuses to make an unlock-bound key without a
 * lock screen: "User ECDH key missing"). [KeystoreBox]'s key keeps working
 * while the phone is locked, which the relay connection needs to take in
 * envelopes; they wait in the [LockedInbox] until the owner unlocks.
 *
 * Why the data key: Android 14 deletes every unlock-bound key the moment the
 * owner removes their lock screen (VaultLockScreenTest saw the alias gone),
 * so anything encrypted under one directly would be lost with it. The data
 * key is held in memory while the vault is open, and [reconcile] wraps it
 * again under whichever Keystore key now fits as soon as the lock screen
 * changes (Vault calls it on screen and unlock events). Setting a lock screen
 * moves the vault under the unlock-bound key the same way. What stays at risk:
 * the lock screen removed while Clairix's process is not running or the vault
 * was never opened since it started, which loses the vault, and the data key
 * left in the process's memory after [forget] until the garbage collector
 * reuses it, as the database password already is.
 */
object VaultBox {

    /** Wraps the data key; works only while the phone is unlocked. */
    private const val LOCKED_ALIAS = "clairix_vault_v1"
    /** Wraps the data key on a phone with no secure lock screen. */
    private const val OPEN_ALIAS = "clairix_vault_open_v1"
    private const val PREFS = "comms_vault"
    private const val WRAPPED_LOCKED = "data_key_locked"
    private const val WRAPPED_OPEN = "data_key_open"
    /** First byte of what [encrypt] writes. Blobs from before the data key had none. */
    private const val FORMAT: Byte = 0x01
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val DATA_KEY_LEN = 32

    private val random = SecureRandom()
    @Volatile private var prefs: SharedPreferences? = null
    /** The data key while the vault is open; null after [forget]. */
    private var dataKey: ByteArray? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun store(): SharedPreferences = prefs ?: throw IllegalStateException("VaultBox.init has not run")

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"))
        return byteArrayOf(FORMAT) + cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts [blob], written by [encrypt] or, before the data key, directly
     * under either vault key or, before the vault, under [KeystoreBox]. The
     * older ones are reported ([Opened.legacy]) so the caller writes them again;
     * a blob nothing opens throws. While the phone is locked the data key
     * cannot be unwrapped, so this throws before any blob is looked at.
     */
    fun open(blob: ByteArray): Opened {
        require(blob.size > IV_LEN + 1) { "ciphertext truncated" }
        val key = key()
        if (blob[0] == FORMAT) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
                return Opened(cipher.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN), legacy = false)
            } catch (e: AEADBadTagException) {
                // A blob from before the data key whose IV happened to start with FORMAT.
            }
        }
        val ks = keyStore()
        for (alias in listOf(LOCKED_ALIAS, OPEN_ALIAS)) {
            val k = runCatching { existing(ks, alias) }.getOrNull() ?: continue
            try {
                return Opened(keystoreDecrypt(k, blob), legacy = true)
            } catch (e: Exception) {
                // Not this key's (or the key is gone with the lock screen); the next may be.
            }
        }
        return Opened(KeystoreBox.decrypt(blob), legacy = true)
    }

    class Opened(val plain: ByteArray, val legacy: Boolean)

    /** The data key, unwrapped (or made) on first use since the vault opened. */
    @Synchronized
    private fun key(): ByteArray {
        dataKey?.let { return it }
        val p = store()
        val ks = keyStore()
        val key = when {
            p.contains(WRAPPED_LOCKED) -> unwrap(ks, LOCKED_ALIAS, p.getString(WRAPPED_LOCKED, null)!!)
            p.contains(WRAPPED_OPEN) -> unwrap(ks, OPEN_ALIAS, p.getString(WRAPPED_OPEN, null)!!)
            else -> {
                check(!app.clairix.security.Wiper.isActive) { "Wiping" }
                ByteArray(DATA_KEY_LEN).also { random.nextBytes(it) }
            }
        }
        dataKey = key
        reconcile()
        return key
    }

    private fun unwrap(ks: KeyStore, alias: String, wrapped: String): ByteArray {
        val k = existing(ks, alias)
            ?: throw IllegalStateException("The vault's Keystore key is gone (a lock screen removed while Clairix was not running)")
        return keystoreDecrypt(k, Base64.decode(wrapped, Base64.NO_WRAP))
    }

    /**
     * Wraps the data key under the Keystore key that fits the lock screen as
     * it is now, if it is not already, and drops the other wrapping. Does
     * nothing while the vault is closed: then there is nothing in memory to
     * wrap, and the stored wrapping is all there is.
     */
    @Synchronized
    fun reconcile() {
        val key = dataKey ?: return
        val p = store()
        val ks = keyStore()
        // A wrapping counts only while its Keystore key is there: Android deletes
        // the unlock-bound one with the lock screen, even if a new lock screen is
        // set before this runs.
        fun held(field: String, alias: String) = p.contains(field) && runCatching { ks.containsAlias(alias) }.getOrDefault(false)
        if (wantLocked()) {
            if (held(WRAPPED_LOCKED, LOCKED_ALIAS) && !p.contains(WRAPPED_OPEN)) return
            val wrapped = runCatching { wrap(LOCKED_ALIAS, unlockedDeviceRequired = true, key) }
                .onFailure { if (it is WipingException) throw it else refusal = Refusal(Vault.deviceSecure()) }
                .getOrNull()
            if (wrapped != null) {
                p.edit().putString(WRAPPED_LOCKED, wrapped).remove(WRAPPED_OPEN).commit()
                return
            }
            // Refused even with a lock screen: kept under the open key below.
        }
        if (held(WRAPPED_OPEN, OPEN_ALIAS) && !p.contains(WRAPPED_LOCKED)) return
        p.edit().putString(WRAPPED_OPEN, wrap(OPEN_ALIAS, unlockedDeviceRequired = false, key)).remove(WRAPPED_LOCKED).commit()
    }

    /** Drops the data key from memory: the vault is sealed. */
    @Synchronized
    fun forget() {
        dataKey?.fill(0)
        dataKey = null
    }

    /**
     * The Keystore would not make the unlock-bound key, with the lock screen
     * as it was then ([Refusal.secure]). Asked again once that changes: a PIN
     * set since is the moment the key can be made.
     */
    @Volatile
    private var refusal: Refusal? = null

    private class Refusal(val secure: Boolean?)

    /** Whether the data key belongs under the unlock-bound key now. */
    private fun wantLocked(): Boolean {
        val secure = Vault.deviceSecure()
        return secure != false && refusal.let { it == null || it.secure != secure }
    }

    private fun wrap(alias: String, unlockedDeviceRequired: Boolean, key: ByteArray): String {
        val ks = keyStore()
        val k = existing(ks, alias) ?: generate(alias, unlockedDeviceRequired)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(key), Base64.NO_WRAP)
    }

    private fun keystoreDecrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
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

    /** Deletes the data key and both Keystore keys; everything encrypted under them becomes unreadable. Used on unpair. */
    @Synchronized
    fun destroy() {
        forget()
        prefs?.edit()?.remove(WRAPPED_LOCKED)?.remove(WRAPPED_OPEN)?.commit()
        runCatching {
            val ks = keyStore()
            for (alias in listOf(LOCKED_ALIAS, OPEN_ALIAS)) if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    /** For tests: whether the data key is wrapped under the unlock-bound key now (opening the vault if needed). */
    @androidx.annotation.VisibleForTesting
    fun writesUnlockBound(): Boolean {
        key()
        return store().contains(WRAPPED_LOCKED)
    }
}
