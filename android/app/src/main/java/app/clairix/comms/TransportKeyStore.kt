package app.clairix.comms

import android.content.Context
import android.util.Base64
import uniffi.clairix_comms_crypto.TransportKey

/**
 * The phone's transport key (see TransportKey in the crypto library): it signs
 * only the relay requests that collect envelopes, so a locked phone, whose
 * identity is out of reach, still takes in what arrives. Its seed is wrapped
 * under [KeystoreBox], the Keystore key that works while the phone is locked;
 * the key reads nothing and sends nothing, and the relay refuses it anything
 * else.
 */
class TransportKeyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var key: TransportKey? = null

    /** The transport key, made and saved on first use. */
    @Synchronized
    fun get(): TransportKey {
        key?.let { return it }
        val wrapped = prefs.getString(KEY_SEED, null)
        val loaded = if (wrapped != null) {
            val seed = KeystoreBox.decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
            try { TransportKey.fromSeed(seed) } finally { seed.fill(0) }
        } else {
            val fresh = TransportKey.generate()
            val seed = fresh.seed()
            try {
                prefs.edit().putString(KEY_SEED, Base64.encodeToString(KeystoreBox.encrypt(seed), Base64.NO_WRAP)).commit()
            } finally {
                seed.fill(0)
            }
            fresh
        }
        key = loaded
        return loaded
    }

    /** Whether the relay has this key: until it does, requests are signed with the identity. */
    val published: Boolean get() = prefs.getBoolean(KEY_PUBLISHED, false)

    fun setPublished(published: Boolean) { prefs.edit().putBoolean(KEY_PUBLISHED, published).apply() }

    /** Forgets the key (unpair); a new one is made for the next registration. */
    @Synchronized
    fun destroy() {
        key?.destroy()
        key = null
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS = "comms_transport"
        const val KEY_SEED = "seed_wrapped"
        const val KEY_PUBLISHED = "published"
    }
}
