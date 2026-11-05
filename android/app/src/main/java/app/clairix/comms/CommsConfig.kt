package app.clairix.comms

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

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
     * unlocked. Hidden until the owner chooses to show them.
     */
    fun hidePreviews(): Boolean = prefs.getBoolean(KEY_HIDE_PREVIEWS, true)

    fun setHidePreviews(hide: Boolean) { prefs.edit().putBoolean(KEY_HIDE_PREVIEWS, hide).apply() }

    /**
     * The owner's choice about relay-only calls: whether call audio may only
     * travel through the relay's TURN servers, so no contact, and nobody
     * holding a contact's phone, learns this phone's network address from a
     * call. Null while they have not chosen; then it is on whenever the relay
     * offers a TURN server ([RelayOnlyCalls]). A choice stored before that
     * default existed was made by the owner too, and is kept.
     */
    val relayOnlyCallsChoice: Boolean?
        get() = if (prefs.contains(KEY_RELAY_ONLY_CALLS)) prefs.getBoolean(KEY_RELAY_ONLY_CALLS, false) else null

    fun setRelayOnlyCalls(on: Boolean) { prefs.edit().putBoolean(KEY_RELAY_ONLY_CALLS, on).apply() }

    /**
     * The invites this phone made that may still be answered, as the SHA-256
     * (hex) of each code with when to forget it: someone who registers with
     * one and says so ([CommsWire.T_JOINED]) comes in as a contact rather than
     * as a message request. The codes themselves are not kept.
     */
    private fun issuedInvites(): MutableMap<String, Long> =
        prefs.getString(KEY_ISSUED_INVITES, null)
            ?.let { text -> runCatching { JSONObject(text).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrNull() }
            ?.toMutableMap()
            ?: mutableMapOf()

    private fun saveIssuedInvites(invites: Map<String, Long>) {
        val now = System.currentTimeMillis()
        prefs.edit().putString(KEY_ISSUED_INVITES, JSONObject(invites.filterValues { it > now }).toString()).apply()
    }

    /** Remembers an invite this phone made ([codeHash]) until [keepUntil]. */
    fun addIssuedInvite(codeHash: String, keepUntil: Long) {
        saveIssuedInvites(issuedInvites().apply { put(codeHash, keepUntil) })
    }

    /** Takes the invite whose code hashes to [codeHash]: true, once, when this phone made it and still remembers it. */
    fun takeIssuedInvite(codeHash: String): Boolean {
        val held = issuedInvites()
        val good = held.remove(codeHash)?.let { it > System.currentTimeMillis() } == true
        saveIssuedInvites(held)
        return good
    }

    /** Whether the relay offered a TURN server for the last call this phone set up (GET /v1/turn); null before the first. */
    val relayTurn: Boolean?
        get() = if (prefs.contains(KEY_RELAY_TURN)) prefs.getBoolean(KEY_RELAY_TURN, false) else null

    fun setRelayTurn(offered: Boolean) {
        if (relayTurn != offered) prefs.edit().putBoolean(KEY_RELAY_TURN, offered).apply()
    }

    /**
     * Whether the relay offers ClairixCoin, as its `GET /` last said
     * ([RelayClient.Info.coin]); true until it says otherwise, since every
     * relay offered it before the flag existed. The balance and the Pay button
     * show only while it does ([CoinRules.shown]).
     */
    val relayCoin: Boolean get() = prefs.getBoolean(KEY_RELAY_COIN, true)

    fun setRelayCoin(offered: Boolean) {
        if (relayCoin != offered) prefs.edit().putBoolean(KEY_RELAY_COIN, offered).apply()
    }

    /**
     * Whether this phone sends cover traffic: sealed envelopes nobody reads, to
     * random contacts at random intervals, so the real ones are not the only
     * traffic there is. Off by default: it costs some data and battery.
     */
    val coverTraffic: Boolean get() = prefs.getBoolean(KEY_COVER_TRAFFIC, false)

    fun setCoverTraffic(on: Boolean) { prefs.edit().putBoolean(KEY_COVER_TRAFFIC, on).apply() }

    /** The other addresses the relay answers at, as it last told this phone; see [RelayClient.info]. */
    val relayAlternates: List<String>
        get() = prefs.getString(KEY_ALTERNATES, null)
            ?.let { text -> runCatching { JSONArray(text).let { a -> (0 until a.length()).map { i -> a.getString(i) } } }.getOrNull() }
            ?: emptyList()

    fun setRelayAlternates(urls: List<String>) { prefs.edit().putString(KEY_ALTERNATES, JSONArray(urls).toString()).apply() }

    /** The relay's onion address as it last told this phone (`http://….onion`), or null; see [RelayClient.info]. */
    val relayOnion: String? get() = prefs.getString(KEY_ONION, null)

    fun setRelayOnion(url: String?) { prefs.edit().putString(KEY_ONION, url).apply() }

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

    /** When the sealing prekey last accepted by the relay was made (0 when none was): a newer one is still to be published. */
    val prekeyPublished: Long get() = prefs.getLong(KEY_PREKEY_PUBLISHED, 0L)

    fun setPrekeyPublished(createdMs: Long) { prefs.edit().putLong(KEY_PREKEY_PUBLISHED, createdMs).apply() }

    /**
     * The key contacts' passes are made with (32 bytes), given to contacts
     * inside end-to-end encrypted messages; null until first made.
     */
    val passKey: ByteArray?
        get() = prefs.getString(KEY_PASS, null)?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 }

    /** Whether the relay holds the current [passKey]. */
    val passPublished: Boolean get() = prefs.getBoolean(KEY_PASS_PUBLISHED, false)

    /** When [passKey] was made; contacts keep the newest key they are given. */
    val passCreated: Long get() = prefs.getLong(KEY_PASS_CREATED, 0L)

    /** Makes [key], made at [created], the pass key, not yet given to the relay. */
    fun setPassKey(key: ByteArray, created: Long) {
        prefs.edit()
            .putString(KEY_PASS, java.util.Base64.getEncoder().encodeToString(key))
            .putLong(KEY_PASS_CREATED, created)
            .putBoolean(KEY_PASS_PUBLISHED, false)
            .apply()
    }

    fun setPassPublished() { prefs.edit().putBoolean(KEY_PASS_PUBLISHED, true).apply() }

    /**
     * The inviter (their Clairix number) still to be told which of their
     * invites this phone registered with, and the invite: kept until the
     * word is sent or refused for good, so a send that found no delivery
     * token yet is tried again with the outbox.
     */
    val pendingJoined: Pair<String, String>?
        get() {
            val number = prefs.getString(KEY_JOINED_NUMBER, null) ?: return null
            val invite = prefs.getString(KEY_JOINED_INVITE, null) ?: return null
            return number to invite
        }

    fun setPendingJoined(number: String, invite: String) {
        prefs.edit().putString(KEY_JOINED_NUMBER, number).putString(KEY_JOINED_INVITE, invite).apply()
    }

    fun clearPendingJoined() {
        prefs.edit().remove(KEY_JOINED_NUMBER).remove(KEY_JOINED_INVITE).apply()
    }

    /**
     * The secret a report's envelope fingerprint is made under
     * ([ContactGate.envelopeFingerprint]), 32 random bytes made on first use.
     * It never leaves the phone.
     */
    @Synchronized
    fun reportSecret(): ByteArray {
        prefs.getString(KEY_REPORT_SECRET, null)
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == 32 }
            ?.let { return it }
        val made = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_REPORT_SECRET, java.util.Base64.getEncoder().encodeToString(made)).apply()
        return made
    }

    fun saveRegistration(relayUrl: String, number: String, name: String, listed: Boolean) {
        prefs.edit()
            .putString(KEY_RELAY, relayUrl.trimEnd('/'))
            .putString(KEY_NUMBER, number)
            .putString(KEY_NAME, name)
            .putBoolean(KEY_LISTED, listed)
            .remove(KEY_PUSH)
            .remove(KEY_PASS_PUBLISHED)
            // Another relay's addresses are no use for this one, and what it
            // offered for calls or for ClairixCoin says nothing about this one.
            .remove(KEY_ALTERNATES)
            .remove(KEY_ONION)
            .remove(KEY_RELAY_TURN)
            .remove(KEY_RELAY_COIN)
            .apply()
    }

    /**
     * This phone moved to the relay at [relayUrl], where it is [number]: the
     * registration there replaces the old one, which is kept as the previous
     * relay ([previousRelay]) while its mailbox is still emptied.
     */
    fun saveMove(relayUrl: String, number: String, previousRelay: String, previousNumber: String, movedAt: Long) {
        prefs.edit()
            .putString(KEY_RELAY, relayUrl.trimEnd('/'))
            .putString(KEY_NUMBER, number)
            .remove(KEY_PUSH)
            .remove(KEY_PUSH_PENDING)
            .remove(KEY_ALTERNATES)
            .remove(KEY_ONION)
            .remove(KEY_RELAY_TURN)
            .remove(KEY_RELAY_COIN)
            .putLong(KEY_PREKEY_PUBLISHED, 0L)
            .remove(KEY_PASS_PUBLISHED)
            .putString(KEY_PREVIOUS_RELAY, previousRelay.trimEnd('/'))
            .putString(KEY_PREVIOUS_NUMBER, previousNumber)
            .putLong(KEY_MOVED_AT, movedAt)
            .remove(KEY_PREVIOUS_EMPTIED)
            .apply()
    }

    /**
     * The relay this phone moved away from, and its number there, for as long
     * as envelopes sent there before contacts heard of the move are still
     * collected; null otherwise.
     */
    val previousRelay: String? get() = prefs.getString(KEY_PREVIOUS_RELAY, null)
    val previousNumber: String? get() = prefs.getString(KEY_PREVIOUS_NUMBER, null)

    /** When this phone last moved to another relay (the date its move notices carry); 0 if it never has. */
    val movedAt: Long get() = prefs.getLong(KEY_MOVED_AT, 0L)

    /** When the previous relay's mailbox was last found empty, since the move; 0 until it has been. */
    val previousEmptied: Long get() = prefs.getLong(KEY_PREVIOUS_EMPTIED, 0L)

    fun setPreviousEmptied(ts: Long) { prefs.edit().putLong(KEY_PREVIOUS_EMPTIED, ts).apply() }

    /**
     * This phone came back from a recovery file under a new number (its old
     * mailbox was gone): the date the move notices to its contacts carry.
     * There is no previous mailbox to empty, so none is recorded.
     */
    fun setMovedAt(movedAt: Long) { prefs.edit().putLong(KEY_MOVED_AT, movedAt).apply() }

    /** When the owner last saved a recovery file from this phone; 0 if never. The file itself is theirs, wherever they put it. */
    val recoverySavedAt: Long get() = prefs.getLong(KEY_RECOVERY_SAVED, 0L)

    fun setRecoverySavedAt(ts: Long) { prefs.edit().putLong(KEY_RECOVERY_SAVED, ts).apply() }

    fun forgetPreviousRelay() {
        prefs.edit().remove(KEY_PREVIOUS_RELAY).remove(KEY_PREVIOUS_NUMBER).remove(KEY_PREVIOUS_EMPTIED).apply()
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
        const val KEY_PREKEY_PUBLISHED = "prekey_published"
        const val KEY_HIDE_PREVIEWS = "hide_previews"
        const val KEY_RELAY_ONLY_CALLS = "relay_only_calls"
        const val KEY_RELAY_TURN = "relay_turn"
        const val KEY_RELAY_COIN = "relay_coin"
        const val KEY_ISSUED_INVITES = "issued_invites"
        const val KEY_COVER_TRAFFIC = "cover_traffic"
        const val KEY_ALTERNATES = "relay_alternates"
        const val KEY_ONION = "relay_onion"
        const val KEY_PREVIOUS_RELAY = "previous_relay"
        const val KEY_PREVIOUS_NUMBER = "previous_number"
        const val KEY_PREVIOUS_EMPTIED = "previous_emptied"
        const val KEY_MOVED_AT = "moved_at"
        const val KEY_PASS = "pass_key"
        const val KEY_PASS_PUBLISHED = "pass_published"
        const val KEY_PASS_CREATED = "pass_created"
        const val KEY_RECOVERY_SAVED = "recovery_saved_at"
        const val KEY_REPORT_SECRET = "report_secret"
        const val KEY_JOINED_NUMBER = "joined_number"
        const val KEY_JOINED_INVITE = "joined_invite"
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
 * The blind-token keys seen per relay and epoch ([MemoryTokenKeyPins]), kept
 * across restarts so a key first seen days ago is still the one checked. Not
 * secret: they are public keys every caller of the relay sees.
 */
class StoredTokenKeyPins(context: Context, private val key: String = KEY) : MemoryTokenKeyPins() {

    private val prefs = context.applicationContext.getSharedPreferences("comms_tokens", Context.MODE_PRIVATE)

    init {
        prefs.getString(key, null)?.let { stored ->
            // Sealed under the Keystore key: the pins name the relay and show
            // which epochs this phone was active in. One kept in the clear by
            // an earlier version is read once and sealed on the next change.
            val json = runCatching { KeystoreBox.decryptString(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull() ?: stored
            load(json)
        }
    }

    override fun changed() {
        prefs.edit().putString(key, Base64.encodeToString(KeystoreBox.encryptString(encode()), Base64.NO_WRAP)).apply()
    }

    companion object {
        private const val KEY = "token_keys"
        /** Where the relay's Supporter keys (docs/SUPPORTER.md) are pinned. */
        const val SUPPORTER_KEY = "supporter_keys"
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
