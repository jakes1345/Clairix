package app.clairix.comms

import android.content.Context
import android.util.Base64

/**
 * Preference data for the comms module; secrets live in [IdentityStore]. Kept
 * in [SealedPrefs]: the relay, the Clairix number, the display name and the
 * push endpoint name this phone's account, and a copy of the app's files must
 * not give that up either.
 */
class CommsConfig(context: Context) {

    private val prefs = SealedPrefs(context, PREFS)

    /** Base URL of the relay Worker, no trailing slash, or null until registered. */
    val relayUrl: String? get() = prefs.getString(KEY_RELAY, null)

    /** This identity's Clairix number, once the relay has allocated one. */
    val number: String? get() = prefs.getString(KEY_NUMBER, null)

    val displayName: String get() = prefs.getString(KEY_NAME, "") ?: ""

    val listed: Boolean get() = prefs.getBoolean(KEY_LISTED, true)

    /** The owner's choice to keep the relay connection open in the background. */
    val online: Boolean get() = prefs.getBoolean(KEY_ONLINE, false)

    /** The UnifiedPush endpoint the relay last acknowledged. */
    val pushEndpoint: String? get() = prefs.getString(KEY_PUSH, null)

    /**
     * Whether notifications say only that something arrived, naming no contact
     * and quoting nothing: for a phone that is sometimes in other hands while
     * unlocked. Until the owner chooses, it follows the passcode: set, hide.
     */
    fun hidePreviews(passcodeSet: Boolean): Boolean = prefs.getBoolean(KEY_HIDE_PREVIEWS, passcodeSet)

    fun setHidePreviews(hide: Boolean) { prefs.edit().putBoolean(KEY_HIDE_PREVIEWS, hide).apply() }

    /**
     * Whether call audio may only travel through the relay's TURN servers, so
     * no contact, and nobody holding a contact's phone, learns this phone's
     * network address from a call. Off by default: it costs some delay and
     * needs the relay to have call servers at all.
     */
    val relayOnlyCalls: Boolean get() = prefs.getBoolean(KEY_RELAY_ONLY_CALLS, false)

    fun setRelayOnlyCalls(on: Boolean) { prefs.edit().putBoolean(KEY_RELAY_ONLY_CALLS, on).apply() }

    /** An endpoint the relay is checking: in use once the code it pushed through it is echoed. */
    val pendingPush: String? get() = prefs.getString(KEY_PUSH_PENDING, null)

    fun setPendingPush(endpoint: String?) { prefs.edit().putString(KEY_PUSH_PENDING, endpoint).apply() }

    val isRegistered: Boolean get() = relayUrl != null && number != null

    /** Set once the background connection has been switched on by default for this install. */
    val onlineDefaultApplied: Boolean get() = prefs.getBoolean(KEY_ONLINE_DEFAULT, false)

    fun markOnlineDefaultApplied() { prefs.edit().putBoolean(KEY_ONLINE_DEFAULT, true).apply() }

    /** When this identity last published a new fallback key; 0 before the first rotation. */
    val fallbackRotatedAt: Long get() = prefs.getLong(KEY_FALLBACK_ROTATED, 0L)

    fun setFallbackRotatedAt(ts: Long) { prefs.edit().putLong(KEY_FALLBACK_ROTATED, ts).apply() }

    fun saveRegistration(relayUrl: String, number: String, name: String, listed: Boolean) {
        prefs.edit()
            .putString(KEY_RELAY, relayUrl.trimEnd('/'))
            .putString(KEY_NUMBER, number)
            .putString(KEY_NAME, name)
            .putBoolean(KEY_LISTED, listed)
            .remove(KEY_PUSH)
            .apply()
    }

    fun setDisplayName(name: String) { prefs.edit().putString(KEY_NAME, name).apply() }
    fun setListed(listed: Boolean) { prefs.edit().putBoolean(KEY_LISTED, listed).apply() }
    fun setOnline(online: Boolean) { prefs.edit().putBoolean(KEY_ONLINE, online).apply() }
    fun setPushEndpoint(endpoint: String?) { prefs.edit().putString(KEY_PUSH, endpoint).apply() }

    fun clear() { prefs.edit().clear().apply() }

    /**
     * Removes what the Twilio-era module kept in this same preferences file:
     * its worker URL, device token, sync cursors and voice token, and the phone
     * number that went with them. Runs once; a file without the old keys is
     * left alone.
     */
    fun purgeLegacy() {
        if (LEGACY_KEYS.none { prefs.contains(it) }) return
        prefs.edit().apply { LEGACY_KEYS.forEach { remove(it) }; remove(KEY_NUMBER) }.apply()
    }

    private companion object {
        const val PREFS = "comms"
        const val KEY_RELAY = "relay_url"
        const val KEY_NUMBER = "number"
        const val KEY_NAME = "name"
        const val KEY_LISTED = "listed"
        const val KEY_ONLINE = "online"
        const val KEY_PUSH = "push_endpoint"
        const val KEY_PUSH_PENDING = "push_endpoint_pending"
        const val KEY_ONLINE_DEFAULT = "online_default_applied"
        const val KEY_FALLBACK_ROTATED = "fallback_rotated_at"
        const val KEY_HIDE_PREVIEWS = "hide_previews"
        const val KEY_RELAY_ONLY_CALLS = "relay_only_calls"
        val LEGACY_KEYS = listOf(
            "worker_url", "device_id", "token_enc", "sync_seq", "sync_updated",
            "push_token", "sync_calls", "voice_token", "voice_at"
        )
    }
}

/**
 * The delivery tokens this phone holds ([MemoryTokenPool]), kept across
 * restarts in private preferences, encrypted under the Keystore key like
 * message bodies. A token in someone else's hands would let them send one
 * envelope at this phone's expense, nothing more.
 */
class StoredTokenPool(context: Context, private val key: String = "tokens") : MemoryTokenPool() {

    private val prefs = context.applicationContext.getSharedPreferences("comms_tokens", Context.MODE_PRIVATE)

    init {
        prefs.getString(key, null)?.let { stored ->
            runCatching { KeystoreBox.decryptString(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()?.let { load(it) }
        }
    }

    override fun changed() {
        if (entries.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val blob = runCatching { Base64.encodeToString(KeystoreBox.encryptString(encode()), Base64.NO_WRAP) }.getOrNull() ?: return
        prefs.edit().putString(key, blob).apply()
    }
}

/**
 * [PendingClaims] kept across restarts, encrypted under the Keystore key like
 * the delivery tokens: a process that dies between starting a session and
 * sending its first envelope still presents the claim ticket afterwards.
 */
class StoredPendingClaims(context: Context) : PendingClaims() {

    private val prefs = context.applicationContext.getSharedPreferences("comms_claims", Context.MODE_PRIVATE)

    init {
        prefs.getString(KEY, null)?.let { stored ->
            runCatching { KeystoreBox.decryptString(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()?.let { load(it) }
        }
    }

    override fun changed() {
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY).apply()
            return
        }
        val blob = runCatching { Base64.encodeToString(KeystoreBox.encryptString(encode()), Base64.NO_WRAP) }.getOrNull() ?: return
        prefs.edit().putString(KEY, blob).apply()
    }

    private companion object {
        const val KEY = "claims"
    }
}
