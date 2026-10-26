package app.clairix.comms

import android.app.Activity
import app.clairix.comms.mesh.Mesh
import app.clairix.comms.mesh.MeshProtocol
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.clairix.BuildConfig
import app.clairix.EventKind
import app.clairix.MainActivity
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.TimelineEvent
import app.clairix.TimelineLog
import app.clairix.analysis.TlsCanary
import app.clairix.security.AppLock
import app.clairix.security.DuressPhrase
import app.clairix.security.RelayClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.clairix_comms_crypto.CryptoException
import uniffi.clairix_comms_crypto.PeerKeys
import uniffi.clairix_comms_crypto.SealingPrekey
import uniffi.clairix_comms_crypto.fingerprint
import uniffi.clairix_comms_crypto.verify
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.ln

/**
 * The comms module's single entry point: registration with the relay, the
 * contact list, sending and receiving end-to-end encrypted messages, key
 * replenishment and push wiring.
 *
 * Everything that touches the network or the identity runs on the IO
 * dispatcher under one mutex, so a ratchet step is never taken twice and the
 * pickle on disk always matches what the relay has seen.
 *
 * Wire format inside an envelope (after Olm decryption), JSON:
 *   {"v":1,"t":"msg","id":uuid,"ts":ms,"body":text,"from":number,"name":text,
 *    "k":ed25519,"c":curve25519,"s":sealing,"g":signature}
 *   {"v":1,"t":"receipt","ids":[...],"status":"delivered"|"read","from":number}
 *   {"v":1,"t":"resync","from":number}   — sent when a message could not be read;
 *                                         it starts a fresh session by itself.
 *   {"v":1,"t":"payment","id":uuid,"txid":relay id,"ts":ms,"amt":coins,"note":text,...}
 *   {"v":1,"t":"voicemail","id":uuid,"ts":ms,"audio":base64 AMR-NB,"dur":ms,...}
 *   {"v":1,"t":"media","id":uuid,"ts":ms,"mime":..,"w":..,"h":..,"dur":ms,"size":bytes,"n":chunks,"cap":..,"thumb":b64,...}
 *   {"v":1,"t":"mchunk","id":media uuid,"i":index,"data":base64,...}   — one of "n" pieces of the file
 *   {"v":1,"t":"gmsg","gid":group,"id":uuid,"ts":ms,"body":text,...}   — a group message, one envelope per member
 *   {"v":1,"t":"gctl","gid":group,"op":roster|add|remove|join,...}      — a group's roster, kept by its admins
 *   {"v":1,"t":"threat","gid":group,"id":uuid,"ts":ms,"kind":..,"sev":..,"lat":..,"lon":..,"title":..,"detail":..,"exp":ms,...}
 *   {"v":1,"t":"panic","gid":group,"id":uuid,"ts":ms,"lat":..,"lon":..,"note":text,...}
 * The sender's keys ride along so a first message from someone who has our
 * number pins their identity without trusting the relay for it; the relay's
 * record for that number must still match before the message is accepted.
 */
object CommsRepository {

    private const val TAG = "CommsRepository"
    /** How long an unpair waits for the relay to delete the account before moving on. */
    private const val RELAY_WIPE_BUDGET_MS = 4_000L
    /** What the offline mesh carries when the relay is out of reach: texts, group texts and SOS. */
    private val MESH_TYPES = setOf(CommsWire.T_MSG, CommsWire.T_GROUP_MSG, CommsWire.T_PANIC)
    /**
     * Payloads the relay must not hold for mixing: a call rings now or not at
     * all, an SOS cannot wait, and group membership changes must arrive in the
     * order they were made (an add then a remove, reordered, would leave the
     * person in). Mixing reorders everything else, which the rest of the app
     * takes in its stride: messages sort by their own time, receipts only
     * advance, and media chunks may overtake their header.
     */
    private val URGENT_TYPES = setOf(CommsWire.T_CALL, CommsWire.T_PANIC, CommsWire.T_GROUP_CTRL)
    /** Cover traffic: the mean, floor and ceiling of the random interval between envelopes. */
    private const val COVER_MEAN_MS = 20 * 60_000L
    private const val COVER_MIN_MS = 60_000L
    private const val COVER_MAX_MS = 90 * 60_000L
    private const val COVER_ALPHABET = "abcdefghijklmnopqrstuvwxyz      "
    /**
     * One-time keys on the relay: topped up by a batch whenever fewer than
     * [ONE_TIME_KEYS_LOW] are left, so a few hundred stay published and
     * lookups (limited per caller and per number) cannot empty the pool.
     */
    private const val ONE_TIME_KEYS_LOW = 150
    private const val ONE_TIME_KEYS_BATCH = 100
    private const val MAX_BODY = 4000
    /**
     * A voicemail's base64 audio: 30 s of AMR-NB at 4.75 kbit/s is under 25 KB.
     * The cap keeps the padded payload inside the relay's largest envelope.
     */
    private const val MAX_VOICEMAIL_B64 = 60_000
    private const val MAX_COIN_NOTE = 140
    private const val MAX_CAPTION = 500
    /** Chunks of a file whose rest never came are forgotten after this long. */
    private const val CHUNK_TTL_MS = 7L * 24 * 3600_000L
    /** An inbound photo or video still incomplete after this long is marked failed rather than left "receiving". */
    private const val MEDIA_RECEIVE_TIMEOUT_MS = 10L * 60_000L
    private const val HOLD_FOREGROUND = "foreground"
    private const val HOLD_CALL = "call"

    // Organisation groups. Every group message is one envelope per member, so groups stay modest.
    private const val MAX_GROUP_MEMBERS = 200
    private const val MAX_GROUP_NAME = 60
    private const val MAX_THREAT_TEXT = 500
    private val THREAT_KINDS = setOf("FOLLOWING", "CATCHER", "WIFI_ANOMALY", "CORRELATED")
    private val THREAT_SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
    /** How long a shared threat stays on members' maps; a catcher sits still, a follower moves on. */
    private const val THREAT_TTL_MS = 3600_000L
    private const val CATCHER_TTL_MS = 24 * 3600_000L
    private const val THREAT_EXPIRE_INTERVAL_MS = 3600_000L
    private const val MAX_PANIC_NOTE = 280
    /** Intent extra naming the group conversation MainActivity should open (with [CommsNotifications.EXTRA_TAB]). */
    const val EXTRA_GROUP = "app.clairix.GROUP"
    const val ACTION_OPEN_GROUP = "app.clairix.OPEN_GROUP"

    private lateinit var appContext: Context
    private lateinit var config: CommsConfig
    private lateinit var identities: IdentityStore
    private lateinit var transportKeys: TransportKeyStore
    /** The message database while the vault is open; null while the phone is locked. */
    @Volatile
    private var openStore: CommsStore? = null

    /**
     * The message database. Throws [VaultSealedException] while the phone is
     * locked: the database is closed then, and its key out of reach.
     */
    private val store: CommsStore
        get() = openStore ?: throw VaultSealedException()

    /** Envelopes that arrived while the phone was locked; read once it is unlocked. */
    private lateinit var lockedInbox: LockedInbox
    private lateinit var relay: RelayClient

    /** An unexpected failure in background work is recorded instead of taking the app down. */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "comms background work failed", e)
        CommsLog.add("Error: ${e.javaClass.simpleName}: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val lock = Mutex()

    /**
     * Observers of every outgoing text, called with the body before it is queued.
     * The message goes out whatever they do; a hook that throws is logged and the
     * send continues.
     */
    private val sendHooks = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addSendHook(hook: (String) -> Unit) { sendHooks.addIfAbsent(hook) }

    private fun runSendHooks(body: String) {
        for (hook in sendHooks) runCatching { hook(body) }.onFailure { CommsLog.add("Send hook failed: ${it.message}") }
    }

    /**
     * How often a new session may be started after an unreadable envelope: once
     * a minute per contact and ten times an hour in all, so a stream of
     * unreadable envelopes (replayed by a relay, say) cannot turn into a stream
     * of bundle fetches and resyncs.
     */
    private val resyncs = ResyncLimiter()

    /** Claim tickets waiting to be presented, one per peer; see [PendingClaims]. */
    private lateinit var claims: PendingClaims

    /**
     * The one immediate retry after a send found no delivery token; anything
     * beyond it is left to [scheduleRetry]'s backoff. See [TokenRefill].
     */
    private val tokenRefill = TokenRefill(
        topUp = { topUpTokens() },
        retry = { scope.launch { flushOutbox(); flushReceipts() } },
        launch = { block -> scope.launch { block() } },
    )

    /** One outbox flush at a time, so a queued message is never encrypted and sent twice. */
    private val outboxLock = Mutex()
    private val receiptsLock = Mutex()
    private val keysLock = Mutex()
    /** When keys were last topped up from the live connection; see [replenishKeysInBackground]. */
    @Volatile
    private var lastReplenishAt = 0L
    private var retryJob: Job? = null
    @Volatile private var retryBackoffMs = RETRY_MIN_MS
    private const val RETRY_MIN_MS = 5_000L
    /** The disappearing-messages sweep looks at least this often, and never sooner than [EXPIRY_MIN_WAIT_MS] apart. */
    private const val EXPIRY_POLL_MS = 60_000L
    private const val EXPIRY_MIN_WAIT_MS = 250L
    private const val RETRY_MAX_MS = 5 * 60_000L
    /** A key refresh after tokens came for keys not yet pinned: half a minute to five minutes later, at random. */
    private const val KEY_REFRESH_MIN_MS = 30_000L
    private const val KEY_REFRESH_SPREAD_MS = 270_000
    private const val RECEIPT_BATCH = 100
    private const val MAX_INBOX_PAGES = 25
    private const val ONE_TIME_KEYS_MAX_WAITING = 100
    /** The fallback key is replaced daily; the previous one keeps working until the next rotation. */
    private const val FALLBACK_ROTATE_MS = 24 * 3600_000L
    /** A call's offer that waited longer than this for the phone to be unlocked no longer rings: the caller has given up. */
    private const val LOCKED_RING_MS = 45_000L
    /** A contact's keys are fetched for their sealing prekey at most this often when their envelopes show they have one. */
    private const val PREKEY_REFRESH_MS = 3600_000L
    /** How long the relay may hold an envelope for mixing before it shows in the inbox, with room to spare. */
    private const val MIX_HOLD_MARGIN_MS = 5 * 60_000L
    private const val REPLENISH_INTERVAL_MS = 3600_000L
    /** How far back unconfirmed messages are sent again when a contact asks for a new session. */
    private const val RESEND_WINDOW_MS = 7L * 24 * 3600_000L

    @Volatile
    private var initialised = false

    private val _state = MutableStateFlow(CommsState())
    val state: StateFlow<CommsState> = _state.asStateFlow()

    /** Bumped whenever contacts or messages change, so screens re-query. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /**
     * Chunks sent so far of each outgoing photo or video, by message id, while it
     * is going: (done, total). The bubble shows it as "Sending 4/17".
     */
    private val _mediaProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val mediaProgress: StateFlow<Map<String, Pair<Int, Int>>> = _mediaProgress.asStateFlow()

    /** Where each outgoing media transfer got to, so a retry after a network failure carries on rather than starting over. */
    private val mediaSent = HashMap<String, Int>()

    /** The conversation currently on screen; its messages do not raise notifications. */
    @Volatile
    var openPeer: String? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        // First: whether the phone is locked decides what may be read below.
        Vault.init(appContext)
        CommsLog.init(appContext)
        config = CommsConfig(appContext)
        // The Twilio-era module left plaintext phone numbers and Keystore-readable
        // SMS bodies in its own database, with no screen left to delete them from.
        appContext.deleteDatabase("comms.db")
        config.purgeLegacy()
        identities = IdentityStore(appContext)
        transportKeys = TransportKeyStore(appContext)
        lockedInbox = LockedInbox(appContext)
        // Started while the phone is locked (a push, a restart): the database
        // opens once it is unlocked.
        if (!Vault.sealed.value) openStore = CommsStore(appContext)
        CommsMedia.init(appContext)
        relay = RelayClient(
            // Locked, there is no identity at hand: what the relay is asked then
            // is signed with the transport key, or waits.
            { if (Vault.sealed.value) null else identities.get() }, { config.relayUrl }, { config.number },
            tokens = StoredTokenPool(appContext),
            trafficTokens = StoredTokenPool(appContext, "traffic"),
            reportTokens = StoredTokenPool(appContext, "report"),
            tokenKeys = StoredTokenKeyPins(appContext),
            // The canary's pin, enforced on every handshake when the owner asked for it.
            alternates = { config.relayAlternates },
            pin = { TlsCanary.enforcedPin(appContext, config.relayUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }) },
            // Collecting envelopes is signed with the transport key once the
            // relay has it, so it works while the identity is locked away.
            transport = { if (transportKeys.published) runCatching { transportKeys.get() }.getOrNull() else null },
            transportRefused = { transportKeys.setPublished(false) },
            // Through Tor, the relay is reached at its onion address when it has one.
            onion = { if (TorTransport.enabled) relayOnion() else null },
        )
        claims = StoredPendingClaims(appContext)
        // The relay's clock, as the Device tab's clock-integrity row compares it.
        RelayClock.bind(appContext)
        relay.clockListener = { serverMs, localMs -> RelayClock.record(serverMs, localMs) }
        LiveLink.init(appContext)
        CallManager.init(appContext)
        // Identities registered before the background connection became the default
        // kept it off, so calls to them could only ring while Clairix was on screen.
        // Switch it on once; the owner can still turn it off in settings.
        if (config.isRegistered && !config.onlineDefaultApplied) {
            config.setOnline(true)
            config.markOnlineDefaultApplied()
            CommsLog.add("Background connection switched on (calls ring while Clairix is closed)")
        }
        initialised = true
        watchVault()
        startExpirySweep()
        syncCoverTraffic()
        refreshAlternates()
        // Every entry point that initialises comms also arms the duress phrase.
        DuressPhrase.install(appContext)
        publishConfig()
        scope.launch { refreshUnread() }
        if (config.isRegistered && config.online) CommsService.start(appContext)
        watchNetwork()
        if (config.isRegistered) scope.launch { pruneSessions() }
        scope.launch { runCatching { store.purgeChunks(System.currentTimeMillis() - CHUNK_TTL_MS) } }
        // Shared threats on the map are forgotten as their time runs out: now, and every hour.
        scope.launch {
            while (true) {
                runCatching { expireSharedThreats() }.onFailure { CommsLog.add("Could not tidy shared threats: ${it.message}") }
                delay(THREAT_EXPIRE_INTERVAL_MS)
            }
        }
        // An SOS (the button, or the duress phrase in an outgoing text) goes to
        // every group this phone is in, with the last known position, and the
        // flag is lowered once that has been tried.
        scope.launch {
            Registry.panicActive.collect { active ->
                if (!active) return@collect
                val covert = Registry.panicCovert
                runCatching { broadcastPanic(covert) }.onFailure { if (!covert) CommsLog.add("SOS could not be sent: ${it.message}") }
                Registry.setPanicActive(false)
            }
        }
    }

    // ── The vault: what the phone being locked puts out of reach ──────────

    /**
     * Follows the [Vault]: sealed, the identity is forgotten and the database
     * closed, under the repository's lock so nothing is half done; opened, the
     * database comes back and what arrived meanwhile is read. A call in progress
     * holds the vault open.
     */
    private fun watchVault() {
        scope.launch {
            CallManager.call
                .map { it != null && it.phase != CallPhase.ENDED }
                .distinctUntilChanged()
                .collect { active -> if (active) Vault.hold() else Vault.release() }
        }
        scope.launch {
            Vault.sealed.collect { sealed -> if (sealed) sealVault() else openVault() }
        }
    }

    private suspend fun sealVault() {
        lock.withLock {
            val db = openStore
            openStore = null
            runCatching { db?.close() }
            identities.seal()
            DuressPhrase.forget()
            meshTagCache = null
        }
        CommsLog.seal()
        // Last: the log above is put away under it.
        VaultBox.forget()
    }

    private suspend fun openVault() {
        val opened = lock.withLock {
            if (openStore != null) return@withLock true
            try {
                openStore = CommsStore(appContext)
                true
            } catch (e: Exception) {
                // The Keystore can lag the unlock by a moment; the next unlock or
                // screen-on tries again, and nothing waiting is lost meanwhile.
                CommsLog.add("Could not open the messages after unlock: ${e.message}")
                false
            }
        }
        if (!opened) return
        CommsLog.open()
        CommsNotifications.cancelLocked(appContext)
        readLockedInbox()
        // A locked ring whose offer turned out to be over (or was not one) stops
        // here; a live offer has taken the ring over by now.
        CallManager.stopLockedRing()
        bump()
        wakeExpiry()
        syncInBackground()
    }

    /** How many envelopes are waiting for the phone to be unlocked. */
    fun lockedWaiting(): Int = if (initialised) lockedInbox.count() else 0

    /** Reads what arrived while the phone was locked, oldest first; one that cannot be handled yet stays for next time. */
    private suspend fun readLockedInbox() {
        val waiting = lockedInbox.all()
        if (waiting.isEmpty()) return
        CommsLog.add("${waiting.size} envelope(s) that arrived while locked are being read")
        for (entry in waiting) {
            if (Vault.sealed.value) return
            if (handleEnvelope(entry.envelope)) lockedInbox.remove(entry) else return
        }
    }

    /**
     * Keeps an envelope that arrived while the phone is locked for after the
     * unlock, and says so: a call rings, an SOS sounds, anything else is "a new
     * message", with no sender, since nothing can be opened yet.
     */
    private fun keepForUnlock(envelope: RelayClient.Envelope): Boolean {
        try {
            lockedInbox.put(envelope)
        } catch (e: Exception) {
            CommsLog.add("Envelope ${shortId(envelope.id)} left on the relay: it could not be kept until unlock (${e.message})")
            return false
        }
        when (envelope.alert) {
            RelayClient.ALERT_CALL -> if (System.currentTimeMillis() - envelope.ts < LOCKED_RING_MS) CommsNotifications.lockedCall(appContext)
            RelayClient.ALERT_SOS -> CommsNotifications.lockedSos(appContext)
            else -> CommsNotifications.lockedMessages(appContext, lockedInbox.count())
        }
        return true
    }

    /**
     * Drops Olm sessions with anyone who is not a contact. A pre-key message
     * creates a session before the app knows who sent it, and sessions from
     * senders that were rejected used to stay in the saved state for good,
     * making every save slower. Payloads still waiting for their sender to be
     * confirmed keep theirs.
     */
    private suspend fun pruneSessions() {
        runCatching {
            lock.withLock {
                if (!identities.exists()) return@withLock
                val keep = store.contacts().map { it.curve25519 } + store.pending().map { it.senderCurve25519 }
                val dropped = identities.update { it.pruneSessions(keep) }
                if (dropped > 0u) CommsLog.add("Removed sessions with $dropped sender(s) who are not contacts")
            }
        }.onFailure { CommsLog.add("Could not tidy sessions: ${it.message}") }
    }

    /**
     * A new network (Wi-Fi to mobile, back in coverage) sends what was waiting
     * at once and drops a relay socket that belonged to the old network, rather
     * than waiting for a timer or for the socket's pings to notice.
     */
    private fun watchNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LiveLink.onNetwork(network.toString())
                    onNetworkAvailable()
                }
            })
        }.onFailure { CommsLog.add("Cannot watch network changes: ${it.message}") }
    }

    /** The relay's current time; see [RelayClient.relayNow]. */
    fun relayNow(): Long = if (initialised) relay.relayNow() else System.currentTimeMillis()

    private fun publishConfig() {
        _state.update {
            it.copy(
                registered = config.isRegistered,
                relayUrl = config.relayUrl,
                number = config.number,
                displayName = config.displayName,
                listed = config.listed,
                online = config.online,
                pushRegistered = config.pushEndpoint != null
            )
        }
    }

    private fun bump() {
        _version.update { it + 1 }
        scope.launch { refreshUnread() }
    }

    private fun refreshUnread() {
        if (Vault.sealed.value) return
        _unread.value = runCatching { store.unreadCount() }.getOrDefault(0)
    }

    private fun setBusy(busy: Boolean, error: String? = null) {
        _state.update { it.copy(busy = busy, error = error) }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    /** Called by [LiveLink] as the live socket comes and goes. */
    fun setConnected(connected: Boolean) { _state.update { it.copy(connected = connected) } }

    /**
     * The app's screen came up or went away. While it is visible the relay
     * socket is held open, so messages and call offers land in the open
     * conversation instead of waiting for the next sync; a registered identity
     * is not needed to take the hold, the link checks that itself.
     */
    fun onAppVisible(visible: Boolean) {
        if (!initialised) return
        if (visible) {
            LiveLink.hold(HOLD_FOREGROUND)
            // The background service may have been refused at process start (a
            // push wake, say) or stopped by Android since. The app on screen may
            // always start it, and starting it again is harmless.
            if (config.isRegistered && config.online) CommsService.start(appContext)
        } else {
            LiveLink.release(HOLD_FOREGROUND)
        }
    }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Creates this device's identity and registers it with the relay, which
     * allocates the Clairix number. The enrollment secret is used once and never
     * stored; from then on every request is signed by the identity key.
     */
    suspend fun register(relayUrl: String, secret: String, name: String, listed: Boolean, invite: PairingCode? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val result = lock.withLock {
                val url = (invite?.relayUrl ?: relayUrl).trim().trimEnd('/')
                if (!RelayClient.acceptableRelayUrl(url)) return@withLock Result.failure(RelayException("The relay URL must start with https://"))
                if (invite == null && secret.isBlank()) return@withLock Result.failure(RelayException("Enter the enrollment secret"))
                if (invite != null && invite.invite == null) return@withLock Result.failure(RelayException("That code is not an invite"))
                setBusy(true)
                // A relay on the relay list must answer with the key the list gives it.
                // One that answers at its address with another key is a different relay
                // (the address was taken over, or the name lapsed and was bought), and
                // this phone would hand it its account.
                RelayDirectory.current(appContext)?.find(url)?.let { listed ->
                    val live = RelayDirectory.liveKey(url)
                    if (live != listed.key) {
                        val reason = if (live == null) {
                            "Could not reach ${listed.name} to check it is the relay on the Clairix relay list. Try again."
                        } else {
                            "The relay at $url answers with a different key from the one the Clairix relay list gives ${listed.name}. " +
                                "Someone else may have taken over its address: do not register there."
                        }
                        setBusy(false, reason)
                        return@withLock Result.failure(RelayException(reason))
                    }
                }
                try {
                    val identity = identities.create()
                    identity.generateOneTimeKeys(ONE_TIME_KEYS_BATCH.toUInt())
                    identities.persist()
                    transportKeys.destroy()
                    val transportKey = runCatching { transportKeys.get().publicKey() }.getOrNull()
                    val registered = relay.register(url, identity, secret.takeIf { invite == null }, listed, invite?.invite, transportKey)
                    identity.markKeysPublished()
                    identities.persist()
                    if (transportKey != null) transportKeys.setPublished(true)
                    config.saveRegistration(url, registered.number, name.trim().take(40), listed)
                    refreshAlternates()
                    // Reachable from the start: the background connection is on until
                    // the owner switches it off, so calls ring with the app closed.
                    config.setOnline(true)
                    config.markOnlineDefaultApplied()
                    publishConfig()
                    setBusy(false)
                    CommsLog.add("Registered as ${formatClairixNumber(registered.number)} on $url" + if (invite != null) " with ${formatClairixNumber(invite.number)}'s invite" else "")
                    CommsService.start(appContext)
                    LiveLink.refresh()
                    // Delivery tokens from the start, so the first send has one ready.
                    scope.launch { topUpTokens() }
                    Result.success(registered.number)
                } catch (e: Exception) {
                    // Registration failed: keep no half-made identity around.
                    identities.destroy()
                    val reason = e.message ?: "Registration failed"
                    setBusy(false, reason)
                    Result.failure(e)
                }
            }
            // The inviter's keys came in the invite link, so they are pinned, but
            // not verified: the link passed through other hands (and the relay's
            // invite page) on the way. A first message tells them who joined;
            // their phone adds this number when it arrives.
            if (invite != null && result.isSuccess) {
                addContactFromCode(invite.copy(invite = null), source = CodeSource.LINK)
                    .onSuccess { contact -> send(contact.number, "Joined Clairix with your invite.") }
                    .onFailure { CommsLog.add("Could not add ${formatClairixNumber(invite.number)} from the invite: ${it.message}") }
            }
            result
        }

    /** A shareable invite from this phone: one registration on this relay, valid for a week. */
    suspend fun createInvite(): Result<Pair<PairingCode, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val code = pairingCode() ?: throw RelayException("Not registered")
            val invite = relay.createInvite()
            CommsLog.add("Invite made; it works once until ${java.text.DateFormat.getDateInstance().format(java.util.Date(invite.expiresAt))}")
            code.copy(invite = invite.code) to invite.expiresAt
        }
    }

    /** Deletes the mailbox on the relay (best effort) and everything local. */
    /** The relay's account deletion, signed now for a wipe to send once the phone is erased (see [RelayClient.preparedWipe]). */
    fun preparedRelayWipe(): okhttp3.Request? = if (initialised && config.isRegistered) relay.preparedWipe() else null

    fun sendRelayWipe(request: okhttp3.Request, timeoutSec: Long) = relay.sendPrepared(request, timeoutSec)

    suspend fun unpair() = withContext(Dispatchers.IO) {
        lock.withLock {
            setBusy(true)
            // The relay gets a few seconds to delete the account; an unreachable relay
            // must not hold up what follows (a wipe gives this whole call six). The
            // call itself blocks on its own thread until the client's timeout.
            val remote = scope.launch(Dispatchers.IO) {
                runCatching { relay.wipe() }.onFailure { Log.w(TAG, "relay wipe failed: ${it.message}") }
            }
            withTimeoutOrNull(RELAY_WIPE_BUDGET_MS) { remote.join() }
            runCatching { UnifiedPush.unregister(appContext) }
            CommsService.stop(appContext)
            LiveLink.disconnect()
            relay.clearTokens()
            claims.clear()
            store.clearAll()
            CommsMedia.deleteAll()
            deleteVoicemailFiles()
            identities.destroy()
            transportKeys.destroy()
            config.clear()
            syncCoverTraffic()
            // What else this phone kept about its comms: the duress phrase (it is
            // typed into comms), the comms log (it names contacts' numbers) and
            // every comms notification still on screen.
            DuressPhrase.clear(appContext)
            CommsNotifications.cancelAll(appContext)
            KeystoreBox.destroy()
            // The database key was wrapped by the key just destroyed; the database
            // goes with it, and the next one starts clean under a new key.
            runCatching { openStore?.close() }
            CommsDb.reset(appContext)
            VaultBox.destroy()
            lockedInbox.clear()
            openStore = if (Vault.sealed.value) null else CommsStore(appContext)
            CommsLog.clear()
            publishConfig()
            _state.update { it.copy(connected = false, lastSync = 0L, relayOneTimeKeys = -1, balance = -1L) }
            setBusy(false)
            bump()
        }
    }

    /** Voicemail recordings and playback copies left in the cache (vm_*.amr). */
    private fun deleteVoicemailFiles() {
        val files = appContext.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("vm_") && f.name.endsWith(".amr") } ?: return
        for (f in files) if (!f.delete()) Log.w(TAG, "could not delete ${f.name}")
    }

    // ── Settings ──────────────────────────────────────────────────────────

    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        config.setDisplayName(name.trim().take(40))
        publishConfig()
    }

    suspend fun setListed(listed: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { relay.setListed(listed) }
            .onSuccess { config.setListed(listed); publishConfig() }
            .onFailure { setBusy(false, it.message) }
    }

    /** Keeps (or stops keeping) the relay socket open in the background. */
    fun setOnline(online: Boolean) {
        config.setOnline(online)
        publishConfig()
        if (online) CommsService.start(appContext) else CommsService.stop(appContext)
        syncCoverTraffic()
    }

    // ── Cover traffic ─────────────────────────────────────────────────────

    private var coverJob: Job? = null

    fun coverTraffic(): Boolean = initialised && config.coverTraffic

    fun setCoverTraffic(on: Boolean) {
        config.setCoverTraffic(on)
        syncCoverTraffic()
    }

    /**
     * Starts or stops the cover-traffic sender to match the owner's choice and
     * the background connection. While it runs, a sealed envelope of random
     * size goes to a random contact at random intervals (exponential, mean
     * [COVER_MEAN_MS]); the contact's phone drops it unread and sends no
     * receipt. What it buys: to the relay, and to anyone at the network's edge,
     * this phone messages its contacts at a steady rate whether or not it has
     * anything to say, so the real messages are not the only traffic there is.
     * What it does not buy: each envelope's recipient is still visible to the
     * relay, one among many, and a contact with no session yet gets no cover
     * (starting one would be a lookup the relay could see).
     */
    @Synchronized
    private fun syncCoverTraffic() {
        val wanted = initialised && config.isRegistered && config.online && config.coverTraffic
        if (!wanted) {
            coverJob?.cancel()
            coverJob = null
            return
        }
        if (coverJob?.isActive == true) return
        coverJob = scope.launch {
            while (isActive) {
                delay(nextCoverDelay())
                if (!(config.online && config.coverTraffic && config.isRegistered)) break
                runCatching { sendCover() }.onFailure { CommsLog.add("Cover envelope not sent: ${it.message}") }
            }
        }
    }

    private fun nextCoverDelay(): Long {
        val u = SecureRandom().nextDouble().coerceIn(1e-9, 1.0)
        return (-ln(u) * COVER_MEAN_MS).toLong().coerceIn(COVER_MIN_MS, COVER_MAX_MS)
    }

    private suspend fun sendCover() {
        val contact = lock.withLock {
            val identity = identities.get() ?: return
            val eligible = store.contacts().filter { !it.keyChanged && identity.hasSession(it.curve25519) }
            if (eligible.isEmpty()) null else eligible[SecureRandom().nextInt(eligible.size)]
        } ?: return
        // Sized like a message, not like a receipt: a random length of filler,
        // padded by the crate to the same buckets a text would be.
        val rnd = SecureRandom()
        val fill = CharArray(40 + rnd.nextInt(1400)) { COVER_ALPHABET[rnd.nextInt(COVER_ALPHABET.length)] }.concatToString()
        deliver(contact, CommsWire.noise(fill))
    }

    // ── Alternate addresses ───────────────────────────────────────────────

    /** Asks the relay which other addresses it answers at, and keeps them for when this one stops. */
    private fun refreshAlternates() {
        if (!initialised || !config.isRegistered) return
        scope.launch {
            runCatching { relay.info() }.onSuccess { keepAddresses(it) }
        }
    }

    /** Keeps the other addresses the relay said it answers at, onion included. */
    private fun keepAddresses(info: RelayClient.Info) {
        if (info.alternates != config.relayAlternates) config.setRelayAlternates(info.alternates)
        if (info.onion != config.relayOnion) config.setRelayOnion(info.onion)
    }

    /**
     * The relay's onion address: the relay list's, for a relay on it (signed,
     * so it outranks anything else), else what the relay itself says at
     * `GET /`, read over its https address or the onion address itself.
     */
    fun relayOnion(): String? {
        val url = config.relayUrl ?: return null
        val listed = runCatching { RelayDirectory.current(appContext)?.find(url) }.getOrNull()
        return RelayClient.onionOrigin(listed?.onion) ?: RelayClient.onionOrigin(config.relayOnion)
    }

    // ── Push ──────────────────────────────────────────────────────────────

    /**
     * Asks the UnifiedPush distributor on this phone (ntfy, for example) for an
     * endpoint. Needs an Activity in case the user has to pick a distributor.
     */
    fun registerPush(activity: Activity) {
        if (!initialised || !config.isRegistered) return
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(appContext)
            else _state.update { it.copy(error = "No UnifiedPush distributor found. Install ntfy (or another distributor) for instant delivery while offline; messages still arrive when Clairix is open or online.") }
        }
    }

    /** Re-requests the endpoint from an already chosen distributor, without UI. */
    fun registerPushIfPossible() {
        if (!initialised || !config.isRegistered) return
        if (UnifiedPush.getAckDistributor(appContext) == null) return
        if (config.pushEndpoint == null) UnifiedPush.register(appContext)
    }

    fun onPushEndpoint(endpoint: String?) {
        if (!initialised || !config.isRegistered) return
        scope.launch {
            lock.withLock {
                runCatching { relay.setPush(endpoint) }
                    .onSuccess { verified ->
                        if (endpoint == null || verified) {
                            config.setPushEndpoint(endpoint)
                            config.setPendingPush(null)
                        } else {
                            // Not a push host the relay knows: it has pushed a check code
                            // through the endpoint, and wakes this phone through it only
                            // once the code is echoed (see [onPushChallenge]).
                            config.setPendingPush(endpoint)
                            CommsLog.add("Push endpoint on a host the relay does not know; waiting for its check to arrive")
                        }
                        publishConfig()
                    }
                    .onFailure { Log.w(TAG, "push endpoint not accepted: ${it.message}") }
            }
        }
    }

    /** The relay's check code arrived through the pending push endpoint: echo it so the endpoint goes live. */
    fun onPushChallenge(code: String) {
        if (!initialised || !config.isRegistered) return
        scope.launch {
            lock.withLock {
                val pending = config.pendingPush ?: return@withLock
                runCatching { relay.verifyPush(code) }
                    .onSuccess {
                        config.setPushEndpoint(pending)
                        config.setPendingPush(null)
                        publishConfig()
                        CommsLog.add("Push endpoint checked; the relay can wake this phone")
                    }
                    .onFailure { CommsLog.add("Push endpoint check refused: ${it.message}") }
            }
        }
    }

    fun onPushFailed(reason: String) {
        _state.update { it.copy(error = "Push registration failed: $reason") }
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    /**
     * A read for a screen: while the phone is locked (the screen just went
     * off under it) there is nothing to show rather than an error to crash on.
     */
    private inline fun <T> unlockedOr(empty: T, read: () -> T): T =
        if (Vault.sealed.value) empty else try { read() } catch (e: VaultSealedException) { empty }

    fun contacts(): List<Contact> = unlockedOr(emptyList()) { store.contacts() }
    fun contact(number: String): Contact? = unlockedOr(null) { store.contact(number) }
    fun threads(): List<ChatThread> = unlockedOr(emptyList()) { store.threads() }
    fun messages(peer: String): List<ChatMessage> = unlockedOr(emptyList()) { store.messages(peer) }
    fun transactions(peer: String): List<CoinTx> = unlockedOr(emptyList()) { store.transactions(peer) }
    fun allTransactions(): List<CoinTx> = unlockedOr(emptyList()) { store.allTransactions() }

    /** This identity's pairing payload, for the QR code others scan. */
    fun pairingCode(): PairingCode? {
        val relayUrl = config.relayUrl ?: return null
        val number = config.number ?: return null
        val identity = runCatching { identities.get() }.getOrNull() ?: return null
        val bundle = identity.publicBundle()
        return PairingCode(relayUrl, number, bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature, config.displayName)
    }

    fun myEd25519(): String? = runCatching { identities.get()?.ed25519() }.getOrNull()

    /** Where a pairing code came from, which decides how far it is trusted. */
    enum class CodeSource {
        /** Scanned from the other person's screen, adding someone new: a verified contact. */
        SCANNED,
        /** Scanned from the other person's screen on their own contact page: may replace the keys held for them. */
        SCANNED_FOR_CONTACT,
        /**
         * A link, an invite or pasted text: anyone could have made it, or changed it
         * on the way (a relay serving the invite page among them). The contact is
         * not verified, and a contact already held keeps the keys it has.
         */
        LINK
    }

    /**
     * Adds (or re-verifies) a contact from a pairing code. A code is self-signed,
     * which proves only that its keys belong together, not whose they are; how
     * much it is trusted follows [source].
     */
    suspend fun addContactFromCode(code: PairingCode, name: String? = null, source: CodeSource): Result<Contact> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (config.relayUrl == null) return@withLock Result.failure(RelayException("Not registered"))
            // A contact on another relay is reached through this phone's own,
            // which forwards to theirs; both relays must be set up for it
            // (the relay's RELAY_KEY), and the first send says so if not.
            val theirRelay = if (isOwnRelay(code.relayUrl)) null else RelayClient.originOf(code.relayUrl)
                ?: return@withLock Result.failure(RelayException("That code names a relay address Clairix cannot use (${code.relayUrl})"))
            if (theirRelay == null && code.number == config.number) return@withLock Result.failure(RelayException("That is your own Clairix number"))
            if (!verify(code.ed25519, "${code.curve25519}|${code.sealing}".toByteArray(Charsets.UTF_8), code.signature)) {
                return@withLock Result.failure(RelayException("The code's keys are not signed by its identity key"))
            }
            val existing = store.contact(code.number)
            if (existing != null && existing.relay != theirRelay) {
                // Numbers are random per relay, so two relays can hand out the same
                // one. Contacts are kept by number; the second is refused rather
                // than allowed to take over the first one's conversation.
                return@withLock Result.failure(RelayException(
                    "${contactLabel(existing)} already has the Clairix number ${formatClairixNumber(code.number)}, on ${relayLabel(existing.relay)}. " +
                        "This code is for the same number on ${relayLabel(theirRelay)}; Clairix cannot keep both. Remove the existing contact first if it is no longer in use."
                ))
            }
            val existingByCurve = store.contactByCurve(code.curve25519)
            if (existingByCurve != null && existingByCurve.number != code.number) {
                return@withLock Result.failure(RelayException("Those keys already belong to ${existingByCurve.name.ifBlank { formatClairixNumber(existingByCurve.number) }}"))
            }
            val sameKeys = existing != null && existing.curve25519 == code.curve25519 && existing.ed25519 == code.ed25519 && existing.sealing == code.sealing
            if (existing != null && !sameKeys) {
                // Someone already in contacts, with other keys. Only their code,
                // scanned on their own page, replaces them: a link could come
                // from anyone, and replacing a contact's keys from one would let
                // whoever made it take over the conversation.
                when (source) {
                    CodeSource.LINK -> return@withLock Result.failure(RelayException(
                        "${contactLabel(existing)} is already a contact, with other keys than this link has. It was not used. " +
                            "If their keys really changed, scan their code in person on their contact page."
                    ))
                    CodeSource.SCANNED -> return@withLock Result.failure(RelayException(
                        "${contactLabel(existing)} is already a contact, with other keys than this code has. " +
                            "To replace them, open their contact page and scan the code there."
                    ))
                    CodeSource.SCANNED_FOR_CONTACT -> identities.update { it.dropSessions(existing.curve25519) }
                }
            }
            if (existing != null && sameKeys && source == CodeSource.LINK) {
                // The keys already held: nothing to change, and a link adds no trust.
                return@withLock Result.success(existing)
            }
            val contact = Contact(
                number = code.number,
                name = (name?.takeIf { it.isNotBlank() } ?: existing?.name?.takeIf { it.isNotBlank() } ?: code.name).trim().take(40),
                ed25519 = code.ed25519,
                curve25519 = code.curve25519,
                sealing = code.sealing,
                signature = code.signature,
                verified = source != CodeSource.LINK,
                addedTs = existing?.addedTs ?: System.currentTimeMillis(),
                keyChanged = false,
                relay = theirRelay
            )
            store.upsertContact(contact)
            if (existing != null) {
                // Scanned in person: these keys are theirs. Groups learn them, and
                // anything held from exactly these keys is shown; the rest is not.
                store.setMemberKey(contact.number, contact.ed25519)
                releaseHeldLocked(contact)
            }
            bump()
            Result.success(contact)
        }
    }

    /**
     * Adds a listed contact by Clairix number. The keys come from the relay, so
     * the contact stays unverified until their QR code is scanned or the safety
     * number is compared in person.
     */
    suspend fun addContactByNumber(rawNumber: String, name: String): Result<Contact> = withContext(Dispatchers.IO) {
        lock.withLock {
            val number = parseClairixNumber(rawNumber) ?: return@withLock Result.failure(RelayException("A Clairix number is nine digits"))
            if (number == config.number) return@withLock Result.failure(RelayException("That is your own Clairix number"))
            store.contact(number)?.let { return@withLock Result.success(it) }
            setBusy(true)
            try {
                // Only the identity: no one-time key is claimed until there is something
                // to send, so the first message presents the key's claim ticket.
                val keys = relay.identity(number, null)
                if (!verify(keys.ed25519, "${keys.curve25519}|${keys.sealing}".toByteArray(Charsets.UTF_8), keys.signature)) {
                    throw RelayException("The relay returned keys that are not signed by that number's identity key")
                }
                store.contactByCurve(keys.curve25519)?.let { other ->
                    // A relay answering with another contact's real keys would otherwise
                    // let messages from that identity land under this number.
                    throw RelayException("The relay returned keys that already belong to ${other.name.ifBlank { formatClairixNumber(other.number) }}")
                }
                val contact = Contact(
                    number = number, name = name.trim().take(40),
                    ed25519 = keys.ed25519, curve25519 = keys.curve25519, sealing = keys.sealing, signature = keys.signature,
                    verified = false, addedTs = System.currentTimeMillis()
                )
                store.upsertContact(contact)
                notePrekey(contact, keys.sealingPrekey)
                setBusy(false)
                bump()
                Result.success(contact)
            } catch (e: Exception) {
                val reason = when {
                    e is RelayException && e.code == 404 -> "No listed Clairix number ${formatClairixNumber(number)} on this relay. Unlisted numbers are added by scanning their QR code."
                    else -> e.message ?: "Lookup failed"
                }
                setBusy(false, reason)
                Result.failure(RelayException(reason))
            }
        }
    }

    suspend fun renameContact(number: String, name: String) = withContext(Dispatchers.IO) {
        store.contact(number)?.let { store.upsertContact(it.copy(name = name.trim().take(40))) }
        bump()
    }

    /**
     * The owner compared safety numbers in person, or scanned the contact's code.
     * Groups now know these keys for them, and whatever was held from exactly
     * these keys is shown.
     */
    suspend fun markVerified(number: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = store.contact(number) ?: return@withLock
            val trusted = c.copy(verified = true, keyChanged = false)
            store.upsertContact(trusted)
            store.setMemberKey(number, trusted.ed25519)
            releaseHeldLocked(trusted)
        }
        bump()
    }

    /**
     * The owner accepts a contact's new keys without verifying them: messages
     * flow again and what was held from these keys is shown, but the contact
     * stays unverified, and groups still take no roster changes from them until
     * they are verified.
     */
    suspend fun acceptNewKeys(number: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = store.contact(number) ?: return@withLock
            if (!c.keyChanged) return@withLock
            val accepted = c.copy(verified = false, keyChanged = false)
            store.upsertContact(accepted)
            CommsLog.add("New keys of ${contactLabel(accepted)} accepted without verification")
            releaseHeldLocked(accepted)
        }
        bump()
    }

    /** Deletes what was held from [number]'s new keys, unseen. */
    suspend fun discardHeld(number: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val n = store.quarantineCount(number)
            store.clearQuarantine(number)
            if (n > 0) CommsLog.add("$n held item(s) from ${formatClairixNumber(number)} discarded unseen")
        }
        bump()
    }

    /** How many payloads from [number]'s new keys are held for review. */
    fun heldCount(number: String): Int = runCatching { store.quarantineCount(number) }.getOrDefault(0)

    /**
     * Processes what was held from [contact] now that the owner verified or
     * accepted their keys: only what came from exactly those keys, oldest
     * first; the rest is deleted. Caller holds [lock].
     */
    private fun releaseHeldLocked(contact: Contact) {
        val held = store.quarantined(contact.number)
        var shown = 0
        for (h in held) {
            val json = CommsWire.parse(h.payload)
            if (json != null && KeyChangeGuard.releasable(contact, h.curve, json)) {
                runCatching { processPayload(contact, h.ts, json) }
                    .onSuccess { shown++ }
                    .onFailure { CommsLog.add("A held item from ${contactLabel(contact)} could not be shown: ${it.message}") }
            }
            store.deleteQuarantined(h.id)
        }
        if (held.isNotEmpty()) CommsLog.add("$shown of ${held.size} held item(s) from ${contactLabel(contact)} shown")
    }

    private fun contactLabel(c: Contact) = c.name.ifBlank { formatClairixNumber(c.number) }

    /**
     * Marks in [contact]'s conversation that their keys changed, so the change
     * is visible where the messages are and not only in a badge.
     */
    private fun noteKeyChange(contact: Contact) {
        store.insertMessage(KeyChangeGuard.systemMessage(UUID.randomUUID().toString(), contact, contactLabel(contact), System.currentTimeMillis()))
    }

    suspend fun deleteContact(number: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            store.contact(number)?.let { c -> identities.update { it.dropSessions(c.curve25519) } }
            store.deleteContact(number).forEach { CommsMedia.delete(it) }
            CommsNotifications.cancel(appContext, number)
            bump()
        }
    }

    /** The 60-digit safety number shared with [contact]; identical on both phones. */
    fun safetyNumber(contact: Contact): String? =
        runCatching { identities.get()?.safetyNumber(contact.ed25519) }.getOrNull()

    // ── Sending ───────────────────────────────────────────────────────────

    /** Queues a message and tries to send it now. Queued messages are retried until they go. */
    suspend fun send(peer: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val text = body.trim().take(MAX_BODY)
        if (text.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty message"))
        store.contact(peer) ?: return@withContext Result.failure(RelayException("Unknown contact"))
        runSendHooks(text)
        val id = UUID.randomUUID().toString()
        store.insertMessage(ChatMessage(id, peer, Direction.OUT, text, System.currentTimeMillis(), "queued", read = true, ttl = store.timer(peer)))
        bump()
        wakeExpiry()
        // On the repository's scope: the caller's scope is the screen's, and leaving
        // the conversation used to cancel the flush half-way, leaving the message
        // "queued" with no retry timer. The bubble shows a failure and its RETRY
        // button through the store, not through this result.
        scope.launch { runCatching { flushOutbox() }.onFailure { CommsLog.add("Sending failed: ${it.message}") } }
        Result.success(Unit)
    }

    /**
     * Queues a photo or video for [peer] and starts sending it. Like a text it
     * stays queued through network failures and goes when the relay is back; the
     * file itself is kept encrypted on this phone under the message id, and the
     * bubble shows it from there.
     */
    suspend fun sendMedia(peer: String, media: CommsMedia.Prepared, caption: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            store.contact(peer) ?: throw RelayException("Unknown contact")
            if (media.bytes.isEmpty()) throw IllegalArgumentException("The file is empty")
            if (media.bytes.size > CommsMedia.MAX_BYTES) throw IllegalArgumentException("The file is too large to send")
            val id = UUID.randomUUID().toString()
            val chunks = (media.bytes.size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES
            val body = MediaBody(
                media.mime, media.width, media.height, media.durationMs, media.bytes.size.toLong(), chunks,
                caption.trim().take(MAX_CAPTION), media.thumbB64
            )
            CommsMedia.save(id, media.bytes)
            store.insertMessage(ChatMessage(id, peer, Direction.OUT, body.encode(), System.currentTimeMillis(), "queued", read = true, kind = KIND_MEDIA, ttl = store.timer(peer)))
            bump()
            wakeExpiry()
            CommsLog.add("${if (media.isVideo) "Video" else "Photo"} (${media.bytes.size / 1024} KB, $chunks envelope(s)) queued for ${formatClairixNumber(peer)}")
            // On the repository's scope: a photo takes many envelopes, and leaving
            // the conversation must not cancel it half-way.
            scope.launch { runCatching { flushOutbox() }.onFailure { CommsLog.add("Sending failed: ${it.message}") } }
            Unit
        }
    }

    /** The bytes of a media message on this phone, or null when they are gone (or still arriving). */
    fun mediaBytes(id: String): ByteArray? = CommsMedia.load(id)

    /** Retries a message that failed. */
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        store.setStatus(id, "queued")
        bump()
        // On the repository's scope, for the same reason as [send].
        scope.launch { runCatching { flushOutbox() }.onFailure { CommsLog.add("Sending failed: ${it.message}") } }
    }

    /**
     * Sends everything queued, oldest first, stopping at the first network
     * failure and trying again later. The repository lock is held only to
     * encrypt each message, never across the network round trip, so a slow
     * network does not hold up envelopes (and call signals) coming in.
     */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        if (!config.isRegistered || Vault.sealed.value) return@withContext
        outboxLock.withLock {
            for (m in store.queued()) {
                val contact = store.contact(m.peer)
                if (contact == null) {
                    store.setStatus(m.id, "failed", "Contact removed")
                    continue
                }
                val outcome = if (m.isMedia) deliverMedia(contact, m) else deliver(contact, CommsWire.message(m.id, m.ts, m.body, m.ttl))
                when (outcome) {
                    Deliver.Sent -> { store.markSentIfQueued(m.id); retryBackoffMs = RETRY_MIN_MS }
                    is Deliver.Failed -> {
                        CommsLog.add("Message to ${formatClairixNumber(contact.number)} failed: ${outcome.reason}")
                        store.setStatus(m.id, "failed", outcome.reason)
                        if (m.isMedia) {
                            // A retry starts from the header again.
                            synchronized(mediaSent) { mediaSent.remove(m.id) }
                            _mediaProgress.update { it - m.id }
                        }
                    }
                    is Deliver.TooLarge -> {
                        // The relay will never take this envelope, and it must not hold
                        // up everything queued behind it: this message alone fails.
                        CommsLog.add("Message to ${formatClairixNumber(contact.number)} failed: ${outcome.reason}")
                        store.markFailed(contact.number, m.id, outcome.reason)
                        if (m.isMedia) {
                            synchronized(mediaSent) { mediaSent.remove(m.id) }
                            _mediaProgress.update { it - m.id }
                        }
                        continue
                    }
                    is Deliver.Offline -> {
                        _state.update { it.copy(error = outcome.reason) }
                        scheduleRetry(outcome.retryAfterMs)
                        // With the offline mesh on, everything still queued also travels
                        // phone to phone; the relay retry carries on regardless, and the
                        // recipient drops whichever copy arrives second.
                        meshQueueOutbox()
                        break
                    }
                }
            }
        }
        bump()
    }

    /**
     * Sends a queued photo or video: its header, then every chunk in order. A
     * network failure part-way leaves the message queued and remembers how far
     * it got, so the retry sends only the rest; the recipient drops any chunk
     * it already has. A fresh process starts from the header again, which is
     * harmless for the same reason.
     */
    private suspend fun deliverMedia(contact: Contact, m: ChatMessage): Deliver {
        val body = MediaBody.decode(m.body) ?: return Deliver.Failed("This attachment's record is unreadable")
        val bytes = CommsMedia.load(m.id) ?: return Deliver.Failed("The ${if (body.isVideo) "video" else "photo"} is no longer on this phone")
        val total = (bytes.size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES
        var done = synchronized(mediaSent) { mediaSent[m.id] ?: -1 }
        fun progress(n: Int) { _mediaProgress.update { it + (m.id to (n.coerceAtLeast(0) to total)) } }
        try {
            if (done < 0) {
                progress(0)
                val header = CommsWire.mediaHeader(m.id, m.ts, body.mime, body.width, body.height, body.durationMs, bytes.size.toLong(), total, body.caption, body.thumbB64, m.ttl)
                when (val d = deliver(contact, header)) {
                    Deliver.Sent -> { done = 0; synchronized(mediaSent) { mediaSent[m.id] = 0 } }
                    else -> return d
                }
            }
            while (done < total) {
                val from = done * CommsWire.MEDIA_CHUNK_BYTES
                val to = minOf(from + CommsWire.MEDIA_CHUNK_BYTES, bytes.size)
                val data = java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(from, to))
                when (val d = deliver(contact, CommsWire.mediaChunk(m.id, done, data))) {
                    Deliver.Sent -> {
                        done++
                        synchronized(mediaSent) { mediaSent[m.id] = done }
                        progress(done)
                    }
                    else -> return d
                }
            }
            synchronized(mediaSent) { mediaSent.remove(m.id) }
            CommsLog.add("${if (body.isVideo) "Video" else "Photo"} sent to ${formatClairixNumber(contact.number)} in $total envelope(s)")
            return Deliver.Sent
        } finally {
            if (done >= total) _mediaProgress.update { it - m.id }
        }
    }

    private sealed class Deliver {
        object Sent : Deliver()
        class Failed(val reason: String) : Deliver()
        /** Not now; try again later, or after [retryAfterMs] when the relay said how long. */
        class Offline(val reason: String, val retryAfterMs: Long? = null) : Deliver()
        /** The relay refused the envelope as too large (413): final for this message, but the queue behind it goes on. */
        class TooLarge(val reason: String) : Deliver()
    }

    private sealed class Prepared {
        class Ready(val envelope: ByteArray) : Prepared()
        class Failed(val reason: String) : Prepared()
        class Offline(val reason: String) : Prepared()
        // No Olm session exists yet; caller must fetch a bundle outside the lock, then retry.
        object NeedBundle : Prepared()
        // The contact's sealing prekey is missing or out of date; caller fetches their keys, then retries.
        object NeedPrekey : Prepared()
    }

    /**
     * Encrypts under the lock, then hands the envelope to the relay without holding it.
     * If no Olm session exists for this contact the bundle fetch is done outside the lock
     * to avoid blocking unrelated envelope processing (including ICE candidates) for up
     * to 25 seconds while a network call completes.
     */
    private suspend fun deliver(contact: Contact, payload: JSONObject): Deliver {
        // A call being set up and an SOS skip the relay's mixing delay.
        val urgent = CommsWire.type(payload) in URGENT_TYPES
        val alert = alertFor(payload)
        // Encryption runs under the lock; anything it needs from the network (a
        // bundle to start a session, the contact's current sealing prekey) is
        // fetched without it, once each, and encryption tried again.
        var bundle: RelayClient.Bundle? = null
        var prekeyFetched = false
        while (true) {
            when (val p = lock.withLock { prepareLocked(contact, payload, bundle) }) {
                is Prepared.Ready -> return transmit(contact, p.envelope, urgent, alert)
                is Prepared.Failed -> return Deliver.Failed(p.reason)
                is Prepared.Offline -> return Deliver.Offline(p.reason)
                Prepared.NeedBundle -> {
                    if (bundle != null) return Deliver.Failed("Unexpected state")
                    bundle = try {
                        relay.bundle(contact.number, fingerprint(contact.ed25519), contact.relay)
                    } catch (e: RelayException) {
                        return relayOutcome(e)
                    }
                    notePrekey(contact, bundle.sealingPrekey)
                }
                Prepared.NeedPrekey -> {
                    if (prekeyFetched) {
                        return Deliver.Failed("${contactLabel(contact)}'s relay offers no current sealing key for them; their phone may be off or its clock wrong")
                    }
                    prekeyFetched = true
                    val keys = try {
                        relay.identity(contact.number, fingerprint(contact.ed25519), contact.relay)
                    } catch (e: RelayException) {
                        return relayOutcome(e)
                    }
                    if (keys.ed25519 == contact.ed25519 && keys.curve25519 == contact.curve25519 && keys.sealing == contact.sealing) {
                        notePrekey(contact, keys.sealingPrekey)
                    }
                }
            }
        }
    }

    /**
     * What a locked recipient's phone should do with [payload] before it can
     * open it: ring for a call's offer, sound the alarm for an SOS. Nothing for
     * the rest of a call's signals, which only matter once it is answered.
     */
    private fun alertFor(payload: JSONObject): String? = when (CommsWire.type(payload)) {
        CommsWire.T_CALL -> if (payload.optString(CommsWire.F_CALL_KIND) == CommsWire.CALL_OFFER) RelayClient.ALERT_CALL else null
        CommsWire.T_PANIC -> RelayClient.ALERT_SOS
        else -> null
    }

    /** The keys envelopes to [contact] are made for, with their sealing prekey when one is known. */
    private fun peerKeys(contact: Contact): PeerKeys =
        PeerKeys(contact.ed25519, contact.curve25519, contact.sealing, contact.signature, store.prekey(contact.number))

    /**
     * Keeps [prekey] as [contact]'s sealing prekey when their identity key
     * signed it and it is newer than the one held. A relay can withhold a
     * prekey but not forge one; a forged one is logged and dropped.
     */
    private fun notePrekey(contact: Contact, prekey: SealingPrekey?) {
        if (prekey == null) return
        val statement = "clairix-sealing-prekey-v1|${prekey.key}|${prekey.createdMs}".toByteArray(Charsets.UTF_8)
        if (!verify(contact.ed25519, statement, prekey.signature)) {
            CommsLog.add("The relay offered a sealing key for ${contactLabel(contact)} that they did not sign; ignored")
            return
        }
        store.setPrekey(contact.number, prekey)
    }

    /** When each contact's keys were last fetched for their sealing prekey, so a burst of envelopes asks once. */
    private val prekeyRefreshAt = HashMap<String, Long>()

    /**
     * [contact] sealed to this phone's prekey, so their app has them, but this
     * phone holds none of theirs: fetches their keys in the background, at most
     * once an hour, so replies are sealed the same way.
     */
    private fun refreshPrekeyLater(contact: Contact) {
        val now = System.currentTimeMillis()
        synchronized(prekeyRefreshAt) {
            if (now - (prekeyRefreshAt[contact.number] ?: 0L) < PREKEY_REFRESH_MS) return
            prekeyRefreshAt[contact.number] = now
        }
        scope.launch {
            runCatching { relay.identity(contact.number, fingerprint(contact.ed25519), contact.relay) }
                .onSuccess { keys ->
                    if (keys.ed25519 == contact.ed25519 && keys.curve25519 == contact.curve25519 && keys.sealing == contact.sealing) {
                        notePrekey(contact, keys.sealingPrekey)
                    }
                }
        }
    }

    /**
     * Adds this identity to [payload] and encrypts it for [contact], starting a
     * session first if there is none. The ratchet step is saved before this
     * returns, so the envelope can go out after the lock is released and in any
     * order relative to others. Caller holds [lock].
     *
     * Pass [bundle] when a new session is needed (returned [Prepared.NeedBundle] on a
     * previous call); if [bundle] is null and no session exists, returns [Prepared.NeedBundle]
     * immediately so the caller can fetch it without the lock.
     */
    private fun prepareLocked(contact: Contact, payload: JSONObject, bundle: RelayClient.Bundle?): Prepared {
        val label = contact.name.ifBlank { formatClairixNumber(contact.number) }
        // Nothing goes to keys the owner has not looked at since they changed.
        if (contact.keyChanged) return Prepared.Failed("$label's keys have changed. Scan their code or compare safety numbers before messaging them.")
        val peer = peerKeys(contact)
        return try {
            putSelf(payload)
            val identity = identities.get() ?: return Prepared.Failed("No identity on this device")
            if (!identity.hasSession(contact.curve25519)) {
                if (bundle == null) return Prepared.NeedBundle
                if (bundle.ed25519 != contact.ed25519 || bundle.curve25519 != contact.curve25519 || bundle.sealing != contact.sealing) {
                    store.upsertContact(contact.copy(keyChanged = true, verified = false))
                    noteKeyChange(contact)
                    return Prepared.Failed("$label's keys have changed. Scan their code again before messaging them.")
                }
                identities.update { it.startSession(peer, bundle.sessionKey) }
                // Kept until an envelope on this session is accepted, whichever that is.
                claims.put(contact.number, bundle.claim, System.currentTimeMillis())
            }
            Prepared.Ready(identities.update { it.encrypt(peer, payload.toString().toByteArray(Charsets.UTF_8)) })
        } catch (e: RelayException) {
            when (val d = relayOutcome(e)) {
                is Deliver.Offline -> Prepared.Offline(d.reason)
                is Deliver.Failed -> Prepared.Failed(d.reason)
                is Deliver.TooLarge -> Prepared.Failed(d.reason)
                Deliver.Sent -> Prepared.Failed(e.message ?: "Relay error")
            }
        } catch (e: CryptoException.SessionLimit) {
            Prepared.Failed("$label has many sessions with this phone in use already; no new one was started")
        } catch (e: CryptoException.StalePrekey) {
            Prepared.NeedPrekey
        } catch (e: CryptoException) {
            Prepared.Failed("Encryption failed: ${e.message}")
        } catch (e: IllegalStateException) {
            // The identity could not be read or saved; nothing was sent, so nothing is lost.
            Prepared.Failed(e.message ?: "The identity could not be saved")
        }
    }

    /**
     * Posts an encrypted envelope, anonymously (a delivery token, no identity;
     * see [RelayClient.send]). Until the relay has accepted one envelope that
     * presents it, every envelope to [contact] carries the claim ticket of the
     * one-time key their newest session was built on ([claims]): a failed
     * first envelope, or a resync that could not go, does not lose it. With no
     * delivery token left this is Offline: the envelope stays queued, the
     * outbox's backoff retries it (fetching tokens first), and [tokenRefill]
     * retries once at once if a fetch does bring tokens. No lock is held.
     */
    private fun transmit(contact: Contact, envelope: ByteArray, urgent: Boolean = false, alert: String? = null): Deliver {
        val claim = claims.get(contact.number, System.currentTimeMillis())
        return try {
            relay.send(contact.number, envelope, claim, contact.relay, urgent, alert)
            if (claim != null) claims.accepted(contact.number, claim)
            Deliver.Sent
        } catch (e: RelayException) {
            if (e.code == RelayClient.NO_TOKENS) tokenRefill.onNoTokens()
            relayOutcome(e)
        }
    }

    /**
     * Fetches delivery tokens when few are left and returns how many the pool
     * then holds (0 when the fetch failed; failures are logged and retried by
     * the next sync or outbox retry).
     */
    private fun topUpTokens(): Int {
        if (!config.isRegistered) return 0
        return runCatching { relay.topUpTokens() }
            .onFailure {
                if ((it as? RelayException)?.code == RelayClient.KEYS_PENDING) scheduleKeyRefresh()
                else CommsLog.add("Could not fetch delivery tokens: ${it.message}")
            }
            .getOrDefault(0)
    }

    private var keyRefreshJob: Job? = null

    /**
     * Reads the relay's blind-token keys (and so its alternates) at a random
     * moment a little later, apart from any signed request: what the relay
     * answers a request it cannot tie to this phone is what everyone gets.
     */
    private fun scheduleKeyRefresh() {
        if (keyRefreshJob?.isActive == true) return
        keyRefreshJob = scope.launch {
            delay(KEY_REFRESH_MIN_MS + SecureRandom().nextInt(KEY_REFRESH_SPREAD_MS))
            runCatching { relay.info() }
                .onSuccess { info ->
                    keepAddresses(info)
                    tokenRefill.onNoTokens()
                }
                .onFailure { CommsLog.add("Could not read the relay's token keys: ${it.message}") }
        }
    }

    /** Which relay errors are worth retrying later (Offline) and which are final (Failed). */
    private fun relayOutcome(e: RelayException): Deliver = when {
        e.code == 404 -> Deliver.Failed("No such Clairix number on this relay any more")
        e.code == 401 -> Deliver.Failed("The relay does not accept this phone's signature (is its clock right?)")
        e.code == 403 -> Deliver.Offline(e.message ?: "The relay refused this phone's delivery tokens; trying again later")
        e.code == 413 -> Deliver.TooLarge(e.message ?: "The relay refused the message as too large")
        e.code == RelayClient.NO_TOKENS -> Deliver.Offline("Waiting for delivery tokens from the relay")
        // Busy, not down: the relay said how long to wait, and nothing is wrong
        // with the network, so the backoff does not grow.
        e.code == 429 -> Deliver.Offline(e.message ?: "The relay is busy; trying again shortly", (e.retryAfterSec ?: 30L) * 1000)
        e.code == 0 || e.code == 408 || e.code >= 500 || e.code == RelayClient.MALFORMED ->
            Deliver.Offline(e.message ?: "The relay is not reachable right now")
        else -> Deliver.Failed(e.message ?: "Relay refused the message")
    }

    /**
     * Tries the outbox and owed receipts again after a growing pause, and on
     * every network change; after exactly [afterMs] instead when the relay said
     * how long to wait (a busy relay is not an outage, so the pause does not grow).
     */
    private fun scheduleRetry(afterMs: Long? = null) {
        if (retryJob?.isActive == true) return
        val wait = if (afterMs != null) {
            afterMs.coerceIn(1_000L, RETRY_MAX_MS)
        } else {
            retryBackoffMs.also { retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(RETRY_MAX_MS) }
        }
        retryJob = scope.launch {
            delay(wait)
            topUpTokens()
            flushOutbox()
            flushReceipts()
        }
    }

    /** Called when the phone gets a (new) network: send what was waiting straight away. */
    private fun onNetworkAvailable() {
        if (!initialised || !config.isRegistered) return
        retryBackoffMs = RETRY_MIN_MS
        retryJob?.cancel()
        scope.launch {
            flushOutbox()
            flushReceipts()
        }
    }

    /**
     * Adds this identity's number, name and keys to an outgoing payload; see
     * [CommsWire.withSender]. Once only: [deliver] runs [prepareLocked] a second
     * time on the same payload when a session bundle had to be fetched first,
     * and [CommsWire.withSender] refuses to overwrite the fields it added the
     * first time. That refusal used to fail every first envelope to a contact
     * with no session yet (one added from a QR code, say): the message, the
     * call offer and the voicemail after it all came back as "payload field(s)
     * from, name, k, c, s, g are reserved for the sender's identity".
     */
    private fun putSelf(o: JSONObject) {
        if (CommsWire.SENDER_FIELDS.all { o.has(it) }) return
        val bundle = identities.get()?.publicBundle() ?: return
        val number = config.number ?: return
        CommsWire.withSender(
            o, CommsWire.Sender(number, config.displayName, bundle.ed25519, bundle.curve25519, bundle.sealing, bundle.signature)
        )
        // Where this number lives, for a contact on another relay to reply to.
        config.relayUrl?.let { RelayClient.originOf(it) }?.let { o.put(CommsWire.F_RELAY, it) }
    }

    /**
     * Whether [contact] is the group member whose roster entry carries
     * [rosterEd25519]. A number names one person on one relay only: with
     * relays reaching each other, anyone running a relay can present any
     * number there. So the roster's key decides when the group knows it; when
     * it does not, only a contact on this phone's own relay (whose number the
     * relay vouches for) or one verified in person counts.
     */
    private fun isGroupMember(contact: Contact, rosterEd25519: String?): Boolean = when {
        !rosterEd25519.isNullOrBlank() -> rosterEd25519 == contact.ed25519
        else -> contact.relay == null || contact.verified
    }

    /** Whether [url] is this phone's relay, under any of the addresses it answers at. */
    private fun isOwnRelay(url: String?): Boolean {
        val mine = config.relayUrl ?: return false
        if (url == null || url.trim().trimEnd('/') == mine.trim().trimEnd('/')) return true
        val origin = RelayClient.originOf(url) ?: return false
        return (listOf(mine) + config.relayAlternates).any { RelayClient.originOf(it) == origin }
    }

    /** How a relay is named to the owner: its host, or "this relay" for their own. */
    private fun relayLabel(relay: String?): String =
        if (relay == null) "your relay" else runCatching { java.net.URI(relay).host }.getOrNull() ?: relay

    /**
     * Sends a call signal (offer, answer, ICE, end) to [contact] through the
     * encrypted session. Returns null once the relay has it, or the reason it
     * could not be sent, which the call screen shows as it is.
     */
    suspend fun sendCallSignal(contact: Contact, payload: JSONObject): String? = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext "this phone is not registered with a relay"
        val kind = CommsWire.callKind(payload)
        when (val r = deliver(contact, payload)) {
            Deliver.Sent -> { if (kind != CommsWire.CALL_ICE) CommsLog.add("Call $kind sent to ${formatClairixNumber(contact.number)}"); null }
            is Deliver.Failed -> { CommsLog.add("Call $kind to ${formatClairixNumber(contact.number)} failed: ${r.reason}"); r.reason }
            is Deliver.TooLarge -> { CommsLog.add("Call $kind to ${formatClairixNumber(contact.number)} failed: ${r.reason}"); r.reason }
            is Deliver.Offline -> { CommsLog.add("Call $kind to ${formatClairixNumber(contact.number)} not sent: ${r.reason}"); r.reason }
        }
    }

    /** Records a call in the conversation with [contact]; a missed call shows as unread. */
    suspend fun logCall(contact: Contact, direction: Direction, body: String, unread: Boolean) = withContext(Dispatchers.IO) {
        store.insertMessage(
            ChatMessage(UUID.randomUUID().toString(), contact.number, direction, body, System.currentTimeMillis(), STATUS_CALL, read = !unread)
        )
        bump()
    }

    // ── Voicemail ─────────────────────────────────────────────────────────

    /**
     * Sends a recording to [contact] as an encrypted envelope, the way a
     * message goes, and keeps a copy in the conversation. Unlike a message it
     * is not queued: a voicemail is left right after a call that did not
     * connect, and the owner is told there and then whether it went.
     */
    suspend fun sendVoicemail(contact: Contact, audioB64: String, durationMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            if (audioB64.isBlank()) throw IllegalArgumentException("The recording is empty")
            if (audioB64.length > MAX_VOICEMAIL_B64) throw IllegalArgumentException("The recording is too long to send")
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val ttl = store.timer(contact.number)
            when (val result = deliver(contact, CommsWire.voicemail(id, ts, audioB64, durationMs, ttl))) {
                Deliver.Sent -> Unit
                is Deliver.Failed -> throw RelayException(result.reason)
                is Deliver.TooLarge -> throw RelayException(result.reason)
                is Deliver.Offline -> throw RelayException(result.reason)
            }
            val body = VoicemailBody(audioB64, durationMs).encode()
            lock.withLock {
                store.insertMessage(ChatMessage(id, contact.number, Direction.OUT, body, ts, STATUS_VOICEMAIL, read = true, ttl = ttl))
            }
            CommsLog.add("Voicemail (${durationLabel(durationMs)}) sent to ${formatClairixNumber(contact.number)}")
            bump()
            wakeExpiry()
        }.onFailure { CommsLog.add("Voicemail to ${formatClairixNumber(contact.number)} not sent: ${it.message}") }
    }

    // ── ClairixCoin ─────────────────────────────────────────────────────────
    //
    // Full edition only. The Play edition is built with CLAIRIX_COIN off: it
    // never asks the relay for a balance, never pays, and does not record
    // payment notices sent to it.

    /** This identity's coins on the relay, or -1 when the relay could not say (or the edition has no coin). */
    suspend fun getBalance(): Long = withContext(Dispatchers.IO) {
        if (!BuildConfig.CLAIRIX_COIN || !config.isRegistered) return@withContext -1L
        runCatching { relay.balance().balance }
            .onFailure { CommsLog.add("Could not read the coin balance: ${it.message}") }
            .getOrDefault(-1L)
    }

    /** Asks the relay for the balance and publishes it; leaves the last known one when the relay is unreachable. */
    suspend fun refreshBalance() {
        val b = getBalance()
        if (b >= 0) _state.update { it.copy(balance = b) }
    }

    /**
     * Pays [contact] [amount] ClairixCoin. The relay moves the coins (it holds
     * both balances) on this identity's signed request; the recipient then
     * hears about it, with the note, through an encrypted envelope the relay
     * cannot read. Returns the balance left afterwards.
     */
    suspend fun sendPayment(contact: Contact, amount: Long, note: String?): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            if (!BuildConfig.CLAIRIX_COIN) throw UnsupportedOperationException("ClairixCoin is not part of this edition")
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            if (amount <= 0L) throw IllegalArgumentException("The amount must be at least 1 $COIN_SYMBOL")
            if (contact.number == config.number) throw IllegalArgumentException("That is your own Clairix number")
            // Coins live in the relay's mailboxes; each relay is its own coin
            // community, and the same number on this relay is someone else.
            if (contact.relay != null) throw IllegalArgumentException("${contactLabel(contact)} is on ${relayLabel(contact.relay)}; ClairixCoin moves only between people on the same relay")
            val text = note?.trim()?.take(MAX_COIN_NOTE)?.takeIf { it.isNotEmpty() }
            val result = relay.pay(contact.number, amount, null)
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            // The coins have moved: recorded before anything else can fail.
            lock.withLock {
                store.insertTransaction(CoinTx(id, contact.number, Direction.OUT, amount, ts, text ?: "", result.txid))
                store.insertMessage(ChatMessage(id, contact.number, Direction.OUT, paymentLine(amount, text, sent = true), ts, STATUS_PAYMENT, read = true))
            }
            _state.update { it.copy(balance = result.balance) }
            bump()
            CommsLog.add("Paid $amount $COIN_SYMBOL to ${formatClairixNumber(contact.number)}")
            // The notice is what carries the note; only the amount reached the relay.
            when (val d = deliver(contact, CommsWire.payment(id, result.txid, ts, amount, text))) {
                Deliver.Sent -> Unit
                is Deliver.Failed -> CommsLog.add("Payment notice to ${formatClairixNumber(contact.number)} failed: ${d.reason}; the coins have been transferred")
                is Deliver.TooLarge -> CommsLog.add("Payment notice to ${formatClairixNumber(contact.number)} failed: ${d.reason}; the coins have been transferred")
                is Deliver.Offline -> CommsLog.add("Payment notice to ${formatClairixNumber(contact.number)} not sent: ${d.reason}; the coins have been transferred")
            }
            result.balance
        }.onFailure { CommsLog.add("Payment to ${formatClairixNumber(contact.number)} failed: ${it.message}") }
    }

    /** The line a payment shows as in the conversation. */
    private fun paymentClaimLine(amount: Long, note: String?): String =
        "$amount $COIN_SYMBOL payment, not yet confirmed by the relay" + (if (!note.isNullOrBlank()) " · $note" else "")

    /**
     * Checks a payment notice against the relay's record of this account: a
     * credit under [txid] from [from] for [amount]. Found, it is recorded and
     * shown as received; not found, the conversation says so.
     */
    private suspend fun confirmPayment(from: String, id: String, txid: String, amount: Long, ts: Long, note: String) {
        val history = runCatching { relay.coinHistory() }.getOrElse {
            CommsLog.add("Could not check a payment notice from ${formatClairixNumber(from)} with the relay: ${it.message}")
            return
        }
        val found = history.any { it.id == txid && it.direction == "in" && it.from == from && it.amount == amount }
        lock.withLock {
            if (found) {
                if (!store.hasTransaction(id)) store.insertTransaction(CoinTx(id, from, Direction.IN, amount, ts, note, txid))
                store.setMessageBody(id, paymentLine(amount, note, sent = false))
                CommsLog.add("Received $amount $COIN_SYMBOL from ${formatClairixNumber(from)}")
            } else {
                store.setMessageBody(id, "Says they sent $amount $COIN_SYMBOL, but the relay has no such payment")
                CommsLog.add("A payment notice from ${formatClairixNumber(from)} for $amount $COIN_SYMBOL is not in the relay's record; not counted")
            }
            bump()
        }
        refreshBalance()
    }

    private fun paymentLine(amount: Long, note: String?, sent: Boolean): String =
        "$amount $COIN_SYMBOL ${if (sent) "sent" else "received"}" + (if (!note.isNullOrBlank()) " · $note" else "")

    // ── Organisation groups ───────────────────────────────────────────────
    //
    // A group lives on its members' phones only: the relay never sees a group
    // id, let alone a roster. Every group payload is encrypted separately for
    // each member and sent along the 1:1 path, so a group message costs one
    // envelope per member. Admins keep the roster; a change is told to every
    // member, and each phone applies only what it hears from someone it knows
    // to be in that group (and, for roster changes, to be an admin of it).

    fun groups(): List<Group> = unlockedOr(emptyList()) { store.groups().map { it.copy(memberCount = store.groupMembers(it.id).size) } }

    /** The owner's choice about what notifications say; see [CommsConfig.hidePreviews]. */
    fun setHidePreviews(hide: Boolean) = config.setHidePreviews(hide)

    /** See [CommsConfig.relayOnlyCalls]. */
    fun relayOnlyCalls(): Boolean = initialised && config.relayOnlyCalls

    fun setRelayOnlyCalls(on: Boolean) = config.setRelayOnlyCalls(on)

    fun group(gid: String): Group? = unlockedOr(null) { store.group(gid)?.let { it.copy(memberCount = store.groupMembers(gid).size) } }

    fun groupMembers(gid: String): List<GroupMember> = unlockedOr(emptyList()) { store.groupMembers(gid) }

    /** The newest [limit] messages in [gid], oldest first, as the conversation shows them. */
    fun groupMessages(gid: String, limit: Int = 100): List<ChatMessage> = unlockedOr(emptyList()) { store.groupMessages(gid, limit).asReversed() }

    fun sharedThreats(gid: String? = null): List<SharedThreat> = unlockedOr(emptyList()) { store.sharedThreats(gid) }

    /** Every group with its last message, unread count and size, most recently active first. */
    fun groupThreads(): List<GroupThread> = unlockedOr(emptyList()) {
        store.groups().map { g ->
            val members = store.groupMembers(g.id).size
            GroupThread(g.copy(memberCount = members), store.groupMessages(g.id, 1).firstOrNull(), store.groupUnread(g.id), members)
        }.sortedByDescending { it.lastMessage?.ts ?: it.group.createdTs }
    }

    /** Marks a group conversation read; group messages carry no receipts. */
    suspend fun markGroupRead(gid: String) = withContext(Dispatchers.IO) {
        if (AppLock.locked.value) return@withContext
        if (store.groupUnread(gid) == 0) return@withContext
        store.markGroupRead(gid)
        wakeExpiry()
        bump()
    }

    /** Makes a new group with this phone as its admin and first member; returns its id. */
    suspend fun createGroup(name: String): String = withContext(Dispatchers.IO) {
        val me = config.number ?: throw RelayException("Not registered with a relay")
        val title = name.trim().take(MAX_GROUP_NAME)
        if (title.isEmpty()) throw IllegalArgumentException("Give the group a name")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        lock.withLock {
            store.insertGroup(Group(id, title, me, now, OrgRole.ADMIN))
            store.insertGroupMember(GroupMember(id, me, config.displayName, OrgRole.ADMIN, myEd25519() ?: "", me, now))
        }
        CommsLog.add("Group \"$title\" created")
        bump()
        id
    }

    /** The group, its roster and this phone's number, or the reason there is no such group to act on. */
    private class GroupContext(val me: String, val group: Group, val members: List<GroupMember>) {
        val myRole: OrgRole get() = members.find { it.number == me }?.role ?: group.myRole
        fun others(): List<GroupMember> = members.filter { it.number != me }
        fun has(number: String) = members.any { it.number == number }
        fun isAdmin(number: String) = members.find { it.number == number }?.role == OrgRole.ADMIN
    }

    private fun groupContext(gid: String): GroupContext {
        val me = config.number ?: throw RelayException("Not registered with a relay")
        val group = store.group(gid) ?: throw RelayException("That group is no longer on this phone")
        return GroupContext(me, group, store.groupMembers(gid))
    }

    private fun memberLabel(m: GroupMember) = m.name.ifBlank { formatClairixNumber(m.number) }

    private fun rosterJson(members: List<GroupMember>): JSONArray {
        val arr = JSONArray()
        for (m in members) arr.put(CommsWire.rosterEntry(m.number, m.name, m.role.name, m.ed25519))
        return arr
    }

    /**
     * Sends [payload] to every one of [members] who is a contact on this phone,
     * one envelope each; returns how many the relay took. Failures are logged
     * per member. Nothing is queued: a group payload is sent once, and the
     * caller decides what a complete failure means.
     */
    private suspend fun fanOut(what: String, members: List<GroupMember>, payload: JSONObject, quiet: Boolean = false): Int {
        // A covert SOS leaves no line in the comms log, failures included.
        val log: (String) -> Unit = if (quiet) { _ -> } else CommsLog::add
        var sent = 0
        for (m in members) {
            val contact = store.contact(m.number)
            if (contact == null) {
                log("$what to ${memberLabel(m)} not sent: they are not a contact on this phone")
                continue
            }
            if (!isGroupMember(contact, m.ed25519)) {
                // Someone else holds this number on another relay: the group's
                // messages go to the member the group knows, never to a number.
                log("$what to ${memberLabel(m)} not sent: the contact with that number is not the person this group knows")
                continue
            }
            when (val d = deliver(contact, payload)) {
                Deliver.Sent -> sent++
                is Deliver.Failed -> log("$what to ${memberLabel(m)} failed: ${d.reason}")
                is Deliver.TooLarge -> log("$what to ${memberLabel(m)} failed: ${d.reason}")
                is Deliver.Offline -> {
                    val id = payload.optString(CommsWire.F_ID)
                    val type = payload.optString(CommsWire.F_TYPE)
                    if (type in MESH_TYPES && id.isNotEmpty() && meshQueue(contact, payload, id)) {
                        log("$what to ${memberLabel(m)} is in the offline mesh: the relay is not reachable (${d.reason})")
                    } else {
                        log("$what to ${memberLabel(m)} not sent: ${d.reason}")
                    }
                }
            }
        }
        return sent
    }

    /**
     * Adds a contact to a group this phone administers: the roster here, then
     * the whole roster to the newcomer (so their phone learns the group) and
     * the new member to everyone already in it.
     */
    suspend fun addGroupMember(gid: String, number: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            val ctx = groupContext(gid)
            if (ctx.myRole != OrgRole.ADMIN) throw RelayException("Only an admin can add members")
            if (number == ctx.me) throw IllegalArgumentException("You are already in the group")
            val contact = store.contact(number) ?: throw RelayException("Add ${formatClairixNumber(number)} as a contact first")
            if (contact.keyChanged) throw RelayException("${contact.name.ifBlank { formatClairixNumber(number) }}'s keys have changed; verify them before adding them")
            if (ctx.has(number)) throw IllegalArgumentException("${contact.name.ifBlank { formatClairixNumber(number) }} is already in the group")
            if (ctx.members.size >= MAX_GROUP_MEMBERS) throw IllegalArgumentException("A group holds at most $MAX_GROUP_MEMBERS members")
            val now = System.currentTimeMillis()
            val newcomer = GroupMember(gid, number, contact.name, OrgRole.MEMBER, contact.ed25519, ctx.me, now)
            lock.withLock { store.insertGroupMember(newcomer) }
            bump()
            val roster = ctx.members + newcomer
            val welcome = CommsWire.groupCtrl(
                gid, CommsWire.OP_ADD,
                mapOf(
                    CommsWire.F_GROUP_NAME to ctx.group.name,
                    CommsWire.F_CREATED_BY to ctx.group.createdBy,
                    CommsWire.F_CREATED_TS to ctx.group.createdTs,
                    CommsWire.F_MEMBERS to rosterJson(roster)
                )
            )
            val welcomed = fanOut("Group invitation", listOf(newcomer), welcome)
            if (welcomed == 0) {
                lock.withLock { store.removeGroupMember(gid, number) }
                bump()
                throw RelayException("${contact.name.ifBlank { formatClairixNumber(number) }} could not be reached; try again later")
            }
            val joined = CommsWire.groupCtrl(
                gid, CommsWire.OP_JOIN,
                mapOf(
                    CommsWire.F_MEMBER to number,
                    CommsWire.F_MEMBER_NAME to contact.name,
                    CommsWire.F_ROLE to OrgRole.MEMBER.name,
                    CommsWire.F_MEMBER_ED25519 to contact.ed25519
                )
            )
            fanOut("Roster update", ctx.others(), joined)
            CommsLog.add("${contact.name.ifBlank { formatClairixNumber(number) }} added to \"${ctx.group.name}\"")
            Unit
        }
    }

    /** Takes a member out of a group this phone administers; they and everyone else are told. */
    suspend fun removeGroupMember(gid: String, number: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            val ctx = groupContext(gid)
            if (ctx.myRole != OrgRole.ADMIN) throw RelayException("Only an admin can remove members")
            if (number == ctx.me) throw IllegalArgumentException("Leave the group instead")
            val target = ctx.members.find { it.number == number } ?: throw IllegalArgumentException("They are not in the group")
            lock.withLock { store.removeGroupMember(gid, number) }
            bump()
            val notice = CommsWire.groupCtrl(gid, CommsWire.OP_REMOVE, mapOf(CommsWire.F_MEMBER to number))
            // The removed member hears it too, so their phone drops the group.
            fanOut("Roster update", ctx.others(), notice)
            CommsLog.add("${memberLabel(target)} removed from \"${ctx.group.name}\"")
            Unit
        }
    }

    /** Leaves a group: the other members are told, then everything about it goes from this phone. */
    suspend fun leaveGroup(gid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = groupContext(gid)
            if (config.isRegistered) fanOut("Leaving notice", ctx.others(), CommsWire.groupCtrl(gid, CommsWire.OP_LEAVE))
            dropGroupLocally(gid)
            Registry.publishSharedThreats(store.sharedThreats(null))
            CommsLog.add("Left \"${ctx.group.name}\"")
            bump()
        }
    }

    private suspend fun dropGroupLocally(gid: String) {
        lock.withLock { store.deleteGroup(gid).forEach { CommsMedia.delete(it) } }
        CommsNotifications.cancel(appContext, gid)
    }

    /**
     * Sends a text to every member of [gid]. The conversation gets one row at
     * once; its status is "sent" when at least one member's envelope reached
     * the relay and "failed" when none did. The fan-out runs on the
     * repository's scope, so leaving the screen does not cut it short.
     */
    suspend fun sendGroupMessage(gid: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            val text = body.trim().take(MAX_BODY)
            if (text.isEmpty()) throw IllegalArgumentException("Empty message")
            val ctx = groupContext(gid)
            if (!ctx.has(ctx.me)) throw RelayException("You are no longer in this group")
            runSendHooks(text)
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val ttl = store.timer(gid)
            lock.withLock {
                store.insertGroupMessage(gid, ChatMessage(id, gid, Direction.OUT, text, ts, "queued", read = true, sender = ctx.me, ttl = ttl))
            }
            bump()
            wakeExpiry()
            scope.launch {
                val recipients = ctx.others()
                val sent = if (recipients.isEmpty()) 0 else fanOut("Group message", recipients, CommsWire.groupMsg(gid, text, id, ts, ttl))
                if (sent > 0 || recipients.isEmpty()) {
                    store.markSentIfQueued(id)
                    if (recipients.isNotEmpty() && sent < recipients.size) {
                        CommsLog.add("Group message to \"${ctx.group.name}\" reached $sent of ${recipients.size} member(s)")
                    }
                } else {
                    store.setStatus(id, "failed", "No member could be reached")
                }
                bump()
            }
            Unit
        }
    }

    /** How long a shared threat of [kind] stays on members' maps. */
    private fun threatTtl(kind: String): Long = if (kind == "CATCHER") CATCHER_TTL_MS else THREAT_TTL_MS

    private fun severityOf(name: String): Severity = runCatching { Severity.valueOf(name) }.getOrDefault(Severity.MEDIUM)

    /**
     * Shares a detection with group [gid]: on this phone's map and timeline at
     * once, and to every member in one envelope each. [bleJson] and [cellJson]
     * are optional JSON objects with the raw evidence.
     */
    suspend fun shareThreats(
        gid: String, kind: String, severity: String, lat: Double?, lon: Double?, title: String, detail: String,
        bleJson: String? = null, cellJson: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            if (kind !in THREAT_KINDS) throw IllegalArgumentException("Unknown threat kind \"$kind\"")
            if (severity !in THREAT_SEVERITIES) throw IllegalArgumentException("Unknown severity \"$severity\"")
            if (lat == null || lon == null) throw IllegalArgumentException("No GPS fix to attach; a shared threat needs a position")
            val ctx = groupContext(gid)
            if (!ctx.has(ctx.me)) throw RelayException("You are no longer in this group")
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val heading = title.trim().take(MAX_THREAT_TEXT)
            val text = detail.trim().take(MAX_THREAT_TEXT)
            val threat = SharedThreat(id, gid, ctx.me, config.displayName, ts, kind, severity, lat, lon, heading, text, ts + threatTtl(kind))
            lock.withLock { store.insertSharedThreat(threat) }
            Registry.publishSharedThreats(store.sharedThreats(null))
            bump()
            val sent = fanOut("Shared threat", ctx.others(), CommsWire.threat(gid, id, ts, kind, severity, lat, lon, heading, text, threat.expiresAt, bleJson, cellJson))
            CommsLog.add("Threat \"$heading\" shared with \"${ctx.group.name}\": $sent of ${ctx.others().size} member(s) reached")
            if (sent == 0 && ctx.others().isNotEmpty()) throw RelayException("No member could be reached")
            Unit
        }
    }

    /** Sends an SOS to every member of [gid], with this phone's position when it has one. */
    suspend fun sendPanic(gid: String, lat: Double?, lon: Double?, note: String? = null, covert: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            val ctx = groupContext(gid)
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val text = note?.trim()?.take(MAX_PANIC_NOTE)
            val sent = fanOut("SOS", ctx.others(), CommsWire.panic(gid, id, ts, lat, lon, text), quiet = covert)
            if (!covert) CommsLog.add("SOS sent to \"${ctx.group.name}\": $sent of ${ctx.others().size} member(s) reached" + if (lat == null || lon == null) " (no position)" else "")
            if (sent == 0 && ctx.others().isNotEmpty()) throw RelayException("No member of \"${ctx.group.name}\" could be reached")
            Unit
        }
    }

    /** The SOS was raised: to every group, with the last known position. */
    private suspend fun broadcastPanic(covert: Boolean) {
        if (!config.isRegistered) return
        val groups = store.groups()
        if (groups.isEmpty()) {
            if (!covert) CommsLog.add("SOS raised, but this phone is in no group to send it to")
            return
        }
        val s = Registry.status.value
        for (g in groups) sendPanic(g.id, s.lat, s.lon, null, covert)
    }

    private fun expireSharedThreats() {
        val gone = store.expireSharedThreats(System.currentTimeMillis())
        if (gone > 0) CommsLog.add("$gone expired shared threat(s) forgotten")
        Registry.publishSharedThreats(store.sharedThreats(null))
    }

    /** A number's name as this phone knows it: the roster's, then the contact's, then the number. */
    private fun memberName(gid: String, number: String): String =
        store.groupMember(gid, number)?.name?.takeIf { it.isNotBlank() }
            ?: store.contact(number)?.name?.takeIf { it.isNotBlank() }
            ?: formatClairixNumber(number)

    /**
     * Acts on a group payload from [contact]. Caller holds [lock]. Anything
     * about a group this phone is not in is dropped, as is anything from
     * someone not on that group's roster; only an invitation ([CommsWire.OP_ADD])
     * from a contact may introduce a group.
     */
    private fun processGroupPayload(contact: Contact, envelopeTs: Long, json: JSONObject) {
        val type = CommsWire.type(json)
        val gid = json.optString(CommsWire.F_GID).takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        val from = contact.number
        val label = contact.name.ifBlank { formatClairixNumber(from) }
        val group = store.group(gid)
        if (type == CommsWire.T_GROUP_CTRL && !KeyChangeGuard.mayApplyGroupControl(contact, store.groupMember(gid, from)?.ed25519)) {
            // Roster changes are never taken from keys the owner has not verified
            // since they changed, nor from keys other than those the group knows.
            CommsLog.add("Group change from $label dropped: it is not signed by the keys this group knows for them")
            return
        }
        if (type == CommsWire.T_GROUP_CTRL && json.optString(CommsWire.F_OP) == CommsWire.OP_ADD) {
            applyGroupAdd(contact, gid, group, json)
            return
        }
        if (group == null) {
            CommsLog.add("$type from $label about a group this phone is not in; dropped")
            return
        }
        val sender = store.groupMember(gid, from)
        if (sender == null) {
            CommsLog.add("$type from $label, who is not in \"${group.name}\"; dropped")
            return
        }
        if (!isGroupMember(contact, sender.ed25519)) {
            CommsLog.add("$type from $label dropped: they have the number of someone in \"${group.name}\", not their keys")
            return
        }
        when (type) {
            CommsWire.T_GROUP_MSG -> {
                val id = CommsWire.messageId(json) ?: return
                if (store.hasMessage(id)) return
                val body = json.optString(CommsWire.F_BODY).take(MAX_BODY)
                if (body.isEmpty()) return
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                val onScreen = openPeer == gid && !AppLock.locked.value
                store.insertGroupMessage(gid, ChatMessage(id, gid, Direction.IN, body, ts, "received", read = onScreen, sender = from, ttl = disappearAfter(gid, json)))
                if (onScreen) wakeExpiry()
                if (!onScreen) {
                    CommsNotifications.notifyGroupInbound(appContext, group, store.unreadGroupInbound(gid, 5)) { memberName(gid, it) }
                }
            }
            CommsWire.T_GROUP_CTRL -> applyGroupCtrl(contact, group, sender, json)
            CommsWire.T_TIMER -> applyTimer(gid, envelopeTs, json, memberName(gid, from), group = true)
            CommsWire.T_THREAT -> {
                val id = CommsWire.messageId(json) ?: return
                if (store.hasSharedThreat(id)) return
                val kind = json.optString(CommsWire.F_KIND).takeIf { it in THREAT_KINDS } ?: run {
                    CommsLog.add("Shared threat from $label dropped: kind \"${json.optString(CommsWire.F_KIND)}\" not understood")
                    return
                }
                val severity = json.optString(CommsWire.F_SEVERITY).takeIf { it in THREAT_SEVERITIES } ?: "MEDIUM"
                val lat = json.optDouble(CommsWire.F_LAT, Double.NaN)
                val lon = json.optDouble(CommsWire.F_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    CommsLog.add("Shared threat from $label dropped: no usable position")
                    return
                }
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                // The sender says how long it lasts, within what this phone would give it.
                val expiresAt = json.optLong(CommsWire.F_EXPIRES, ts + threatTtl(kind)).coerceIn(ts, ts + threatTtl(kind))
                val title = json.optString(CommsWire.F_TITLE).trim().take(MAX_THREAT_TEXT).ifBlank { kind }
                val detail = json.optString(CommsWire.F_DETAIL).trim().take(MAX_THREAT_TEXT)
                val fromName = memberName(gid, from)
                store.insertSharedThreat(SharedThreat(id, gid, from, fromName, ts, kind, severity, lat, lon, title, detail, expiresAt))
                Registry.publishSharedThreats(store.sharedThreats(null))
                CommsLog.add("Threat \"$title\" shared by $fromName in \"${group.name}\"")
                val event = TimelineEvent(
                    id = "shared_threat@$id", kind = EventKind.SHARED_THREAT, ts = ts,
                    title = "$fromName shared: $title",
                    detail = "${group.name} · $kind" + (if (detail.isNotBlank()) " · $detail" else ""),
                    severity = severityOf(severity), lat = lat, lon = lon
                )
                scope.launch { runCatching { TimelineLog.record(appContext, event) } }
            }
            CommsWire.T_PANIC -> {
                val id = CommsWire.messageId(json) ?: return
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                val lat = json.optDouble(CommsWire.F_LAT, Double.NaN).takeIf { !it.isNaN() && it in -90.0..90.0 }
                val lon = json.optDouble(CommsWire.F_LON, Double.NaN).takeIf { !it.isNaN() && it in -180.0..180.0 }
                val pos = if (lat != null && lon != null) lat to lon else null
                val note = json.optString(CommsWire.F_NOTE).trim().take(MAX_PANIC_NOTE)
                val fromName = memberName(gid, from)
                CommsLog.add("SOS from $fromName in \"${group.name}\"" + (if (pos == null) " (no position)" else ""))
                val event = TimelineEvent(
                    id = "panic@$id", kind = EventKind.PANIC, ts = ts,
                    title = "SOS from $fromName",
                    detail = group.name + (if (pos != null) String.format(java.util.Locale.US, " · at %.5f, %.5f", pos.first, pos.second) else " · no position attached") +
                        (if (note.isNotBlank()) " · $note" else ""),
                    severity = Severity.CRITICAL, lat = pos?.first, lon = pos?.second
                )
                scope.launch { runCatching { TimelineLog.record(appContext, event, dedupeKey = "panic@$id") } }
                CommsNotifications.notifyPanic(appContext, group, id, fromName, pos?.first, pos?.second, note, ts)
            }
        }
    }

    /**
     * An invitation: [contact] added this phone to a group. The roster must
     * name them as an admin, or the message is somebody making up a group in
     * other people's names. For a group already here, only an admin's roster
     * replaces what this phone has.
     */
    private fun applyGroupAdd(contact: Contact, gid: String, existing: Group?, json: JSONObject) {
        val me = config.number ?: return
        val from = contact.number
        val label = contact.name.ifBlank { formatClairixNumber(from) }
        val roster = CommsWire.rosterEntries(json).filter { parseClairixNumber(it.number) != null }.distinctBy { it.number }
        if (roster.none { it.number == from && it.role == OrgRole.ADMIN.name }) {
            CommsLog.add("Group invitation from $label dropped: its roster does not make them an admin")
            return
        }
        if (!KeyChangeGuard.mayApplyGroupControl(contact, roster.find { it.number == from }?.ed25519)) {
            CommsLog.add("Group invitation from $label dropped: its roster names other keys for them")
            return
        }
        if (roster.none { it.number == me }) {
            CommsLog.add("Group invitation from $label dropped: this phone is not on its roster")
            return
        }
        if (roster.size > MAX_GROUP_MEMBERS) {
            CommsLog.add("Group invitation from $label dropped: ${roster.size} members is more than a group holds")
            return
        }
        if (existing != null && store.groupMember(gid, from)?.role != OrgRole.ADMIN) {
            CommsLog.add("Roster for \"${existing.name}\" from $label, who is not one of its admins; dropped")
            return
        }
        val name = json.optString(CommsWire.F_GROUP_NAME).trim().take(MAX_GROUP_NAME).ifBlank { existing?.name ?: "Group" }
        val createdBy = json.optString(CommsWire.F_CREATED_BY).let { parseClairixNumber(it) } ?: existing?.createdBy ?: from
        val createdTs = existing?.createdTs ?: json.optLong(CommsWire.F_CREATED_TS, System.currentTimeMillis())
        val myRole = roster.find { it.number == me }?.let { runCatching { OrgRole.valueOf(it.role) }.getOrNull() } ?: OrgRole.MEMBER
        val now = System.currentTimeMillis()
        store.insertGroup(Group(gid, name, createdBy, createdTs, myRole))
        val known = store.groupMembers(gid).associateBy { it.number }
        for (e in roster) {
            val role = runCatching { OrgRole.valueOf(e.role) }.getOrDefault(OrgRole.MEMBER)
            val ed = e.ed25519.ifBlank { store.contact(e.number)?.ed25519 ?: known[e.number]?.ed25519 ?: "" }
            val memberName = e.name.trim().take(40).ifBlank { store.contact(e.number)?.name ?: known[e.number]?.name ?: "" }
            store.insertGroupMember(GroupMember(gid, e.number, memberName, role, ed, known[e.number]?.addedBy ?: from, known[e.number]?.addedTs ?: now))
        }
        for (gone in known.keys - roster.map { it.number }.toSet()) store.removeGroupMember(gid, gone)
        CommsLog.add(if (existing == null) "Added to group \"$name\" by $label (${roster.size} members)" else "Roster of \"$name\" updated by $label (${roster.size} members)")
    }

    /** A roster change from [sender], already known to be in [group]. Caller holds [lock]. */
    private fun applyGroupCtrl(contact: Contact, group: Group, sender: GroupMember, json: JSONObject) {
        val me = config.number ?: return
        val gid = group.id
        val label = memberLabel(sender)
        when (val op = json.optString(CommsWire.F_OP)) {
            CommsWire.OP_JOIN -> {
                if (sender.role != OrgRole.ADMIN) { CommsLog.add("Roster change in \"${group.name}\" from $label, who is not an admin; dropped"); return }
                val number = parseClairixNumber(json.optString(CommsWire.F_MEMBER)) ?: return
                if (store.groupMember(gid, number) != null) return
                if (store.groupMembers(gid).size >= MAX_GROUP_MEMBERS) return
                val role = runCatching { OrgRole.valueOf(json.optString(CommsWire.F_ROLE)) }.getOrDefault(OrgRole.MEMBER)
                val name = json.optString(CommsWire.F_MEMBER_NAME).trim().take(40).ifBlank { store.contact(number)?.name ?: "" }
                val ed = json.optString(CommsWire.F_MEMBER_ED25519).ifBlank { store.contact(number)?.ed25519 ?: "" }
                store.insertGroupMember(GroupMember(gid, number, name, role, ed, sender.number, System.currentTimeMillis()))
                CommsLog.add("${name.ifBlank { formatClairixNumber(number) }} joined \"${group.name}\" (added by $label)")
            }
            CommsWire.OP_REMOVE -> {
                if (sender.role != OrgRole.ADMIN) { CommsLog.add("Roster change in \"${group.name}\" from $label, who is not an admin; dropped"); return }
                val number = parseClairixNumber(json.optString(CommsWire.F_MEMBER)) ?: return
                if (number == me) {
                    // After the caller releases the lock: the group's rows go with its files.
                    scope.launch { dropGroupLocally(gid); Registry.publishSharedThreats(store.sharedThreats(null)); bump() }
                    CommsLog.add("Removed from \"${group.name}\" by $label")
                    return
                }
                val target = store.groupMember(gid, number) ?: return
                store.removeGroupMember(gid, number)
                CommsLog.add("${memberLabel(target)} removed from \"${group.name}\" by $label")
            }
            CommsWire.OP_LEAVE -> {
                store.removeGroupMember(gid, sender.number)
                CommsLog.add("$label left \"${group.name}\"")
            }
            else -> CommsLog.add("Group operation \"$op\" from $label not understood; ignored")
        }
    }

    /**
     * Keeps the relay socket open for a call's signalling even when the app
     * leaves the screen and the owner has comms offline; released when the
     * call ends. The call's own microphone service keeps the process alive.
     */
    fun holdLiveLink() { if (initialised) LiveLink.hold(HOLD_CALL) }
    fun releaseLiveLink() { if (initialised) LiveLink.release(HOLD_CALL) }

    /**
     * Encrypts a control message now and sends it once the lock is released.
     * Caller holds [lock]. Used for the resync, which is sent on a session the
     * caller has just started, so no bundle is fetched here; receipts go
     * through the receipts table so a failed one is retried.
     */
    private fun sendControlLocked(contact: Contact, payload: JSONObject) {
        val type = CommsWire.type(payload)
        when (val p = prepareLocked(contact, payload, null)) {
            Prepared.NeedBundle -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} not sent: no session with them")
            Prepared.NeedPrekey -> {
                CommsLog.add("$type to ${formatClairixNumber(contact.number)} not sent: their current sealing key is not known yet; fetching it")
                refreshPrekeyLater(contact)
            }
            is Prepared.Ready -> scope.launch {
                when (val d = transmit(contact, p.envelope)) {
                    Deliver.Sent -> Unit
                    is Deliver.Failed -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} failed: ${d.reason}")
                    is Deliver.TooLarge -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} failed: ${d.reason}")
                    is Deliver.Offline -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} not sent: ${d.reason}")
                }
            }
            is Prepared.Failed -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} failed: ${p.reason}")
            is Prepared.Offline -> CommsLog.add("$type to ${formatClairixNumber(contact.number)} not sent: ${p.reason}")
        }
    }

    /**
     * Sends the receipts owed to contacts, one batch per contact and status.
     * They are stored first (see processPayload and markRead), so a receipt
     * that cannot go now is tried again rather than lost.
     */
    suspend fun flushReceipts() = withContext(Dispatchers.IO) {
        if (!config.isRegistered || Vault.sealed.value || !store.hasPendingReceipts()) return@withContext
        receiptsLock.withLock {
            for ((peer, byStatus) in store.pendingReceipts()) {
                val contact = store.contact(peer)
                if (contact == null) { store.clearReceiptsFor(peer); continue }
                for ((status, ids) in byStatus) for (chunk in ids.chunked(RECEIPT_BATCH)) {
                    when (val d = deliver(contact, CommsWire.receipt(chunk, status))) {
                        Deliver.Sent -> store.clearReceipts(peer, chunk, status)
                        is Deliver.Failed -> {
                            // Will never go (their keys changed, the number is gone): drop it.
                            CommsLog.add("Receipt to ${formatClairixNumber(peer)} dropped: ${d.reason}")
                            store.clearReceipts(peer, chunk, status)
                        }
                        is Deliver.TooLarge -> {
                            // A receipt batch the relay will not take is split no further; drop it like any final failure.
                            CommsLog.add("Receipt to ${formatClairixNumber(peer)} dropped: ${d.reason}")
                            store.clearReceipts(peer, chunk, status)
                        }
                        is Deliver.Offline -> { scheduleRetry(d.retryAfterMs); return@withLock }
                    }
                }
            }
        }
    }

    // ── Offline mesh ──────────────────────────────────────────────────────

    /** The tags worked out for one day and one set of contacts; a carried bundle is checked against them. */
    private data class MeshTagCache(val day: Long, val contacts: Set<String>, val tags: Set<String>)
    @Volatile private var meshTagCache: MeshTagCache? = null

    /**
     * The routing tags this phone answers to on the mesh, yesterday, today and
     * tomorrow: the one it shares with each contact. Worked out once a day, or
     * when the contacts change. Beta 5's tag, from this phone's public key alone,
     * is no longer answered: anyone holding that key could make a bundle only
     * this phone takes, and so pick this phone out of a crowd.
     */
    fun meshTags(): Set<String> {
        // Locked, nothing here can tell which bundles are this phone's: the mesh
        // carries them all, and they are recognised after the unlock.
        if (Vault.sealed.value) return emptySet()
        val now = System.currentTimeMillis()
        val day = MeshProtocol.day(now)
        val contacts = runCatching { store.contacts() }.getOrDefault(emptyList()).map { it.sealing }.filter { it.isNotBlank() }.toSet()
        meshTagCache?.takeIf { it.day == day && it.contacts == contacts }?.let { return it.tags }
        val identity = runCatching { identities.get() }.getOrNull() ?: return emptySet()
        val tags = HashSet<String>()
        for (peer in contacts) {
            runCatching { tags += MeshProtocol.around(now) { d -> identity.meshTag(peer, d) } }
        }
        return tags.also { meshTagCache = MeshTagCache(day, contacts, it) }
    }

    /**
     * An envelope the mesh carried here: into the same path the relay's take, with the
     * time it arrived (the relay's timestamp is not there to use). Duplicates of one the
     * relay also delivered are dropped there, by content and by message id.
     */
    fun receiveFromMesh(data: ByteArray) {
        scope.launch {
            runCatching {
                handleEnvelope(RelayClient.Envelope("mesh-" + MeshProtocol.hex(MeshProtocol.idOf(data)), System.currentTimeMillis(), data))
            }.onFailure { CommsLog.add("A message from the offline mesh could not be opened: ${it.message}") }
        }
    }

    /** Every queued text, sealed once into the mesh. Media is too big for Bluetooth hops and stays for the relay. */
    private suspend fun meshQueueOutbox() {
        if (!::appContext.isInitialized || !Mesh.enabled(appContext)) return
        for (m in store.queued()) {
            if (m.isMedia) continue
            val contact = store.contact(m.peer) ?: continue
            meshQueue(contact, CommsWire.message(m.id, m.ts, m.body, m.ttl), m.id)
        }
    }

    /**
     * Seals [payload] for [contact] into a mesh bundle, once per message [id]. Needs a
     * session with them already: starting one takes a key from the relay, which is
     * exactly what is not reachable. Returns true when the message is in the mesh.
     */
    private suspend fun meshQueue(contact: Contact, payload: JSONObject, id: String): Boolean {
        if (!::appContext.isInitialized || !Mesh.enabled(appContext)) return false
        val mesh = Mesh.store(appContext)
        if (mesh.isQueued(contact.number, id)) return true
        val now = System.currentTimeMillis()
        val label = contact.name.ifBlank { formatClairixNumber(contact.number) }
        return when (val p = lock.withLock { prepareLocked(contact, JSONObject(payload.toString()), null) }) {
            is Prepared.Ready -> {
                val tag = runCatching { identities.get()?.meshTag(contact.sealing, MeshProtocol.day(now)) }.getOrNull()
                val b = tag?.let { MeshProtocol.newBundle(p.envelope, it, now) }
                if (tag == null) {
                    CommsLog.add("A message to $label could not be addressed for the offline mesh; it waits for the relay")
                    false
                } else if (b == null) {
                    CommsLog.add("A message to $label is too long for the offline mesh; it waits for the relay")
                    false
                } else {
                    mesh.addOwn(contact.number, id, b, now)
                    CommsLog.add("A message to $label is in the offline mesh: it travels phone to phone until it reaches them")
                    true
                }
            }
            Prepared.NeedBundle -> {
                CommsLog.add("No session with $label yet, so the offline mesh cannot reach them until one message has gone through the relay")
                false
            }
            Prepared.NeedPrekey -> {
                // Their sealing key was last fetched over two weeks ago (or never);
                // sealing to an older one, or to their long-term key, would undo
                // what the rotating keys are for.
                CommsLog.add("$label's sealing key is out of date, so the offline mesh cannot reach them until the relay is reachable again")
                false
            }
            else -> false
        }
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /**
     * Opens one envelope from the relay and acts on it. Returns true when the
     * relay may delete it: its payload is safely staged here (and processed, or
     * held for a retry), it was a duplicate, or it can never be read. It
     * returns false, leaving the envelope on the relay, only when the ratchet
     * step could not be saved or the identity could not be read.
     */
    suspend fun handleEnvelope(envelope: RelayClient.Envelope): Boolean = withContext(Dispatchers.IO) {
        // Locked: kept as it came, to be read once the phone is unlocked.
        if (Vault.sealed.value) return@withContext keepForUnlock(envelope)
        val handled = try {
            lock.withLock {
                if (store.hasPending()) retryPendingLocked()
                handleLocked(envelope)
            }
        } catch (e: IllegalStateException) {
            CommsLog.add("Envelope ${shortId(envelope.id)} left on the relay: ${e.message}")
            false
        }
        bump()
        if (store.hasPendingReceipts()) scope.launch { flushReceipts() }
        handled
    }

    /** A digest of the envelope's bytes: duplicates are recognised by content, which the relay cannot change. */
    private fun envelopeKey(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun handleLocked(envelope: RelayClient.Envelope): Boolean {
        // The live socket and an inbox fetch can both hand over the same envelope,
        // and a relay could re-send an old one under a new id. A second copy must
        // not reach the ratchet.
        val key = envelopeKey(envelope.data)
        if (store.isEnvelopeSeen(key)) return true
        val decrypted = try {
            identities.update { it.decrypt(envelope.data) }
        } catch (e: CryptoException.Duplicate) {
            store.markEnvelopeSeen(key, envelope.ts)
            return true
        } catch (e: CryptoException.NoSessionFor) {
            store.markEnvelopeSeen(key, envelope.ts)
            recoverSession(e.senderCurve25519, e.senderEd25519, e.sentAtMs, "Envelope ${shortId(envelope.id)} came on a session this phone does not have")
            return true
        } catch (e: CryptoException.DecryptFrom) {
            store.markEnvelopeSeen(key, envelope.ts)
            recoverSession(e.senderCurve25519, e.senderEd25519, e.sentAtMs, "Envelope ${shortId(envelope.id)} could not be decrypted (${e.reason})")
            return true
        } catch (e: CryptoException.BadSignature) {
            // Its claim to come from someone is unsigned, signed for another
            // envelope, or pairs a contact's key with someone else's: nobody is
            // named, so nothing is done on anybody's behalf.
            store.markEnvelopeSeen(key, envelope.ts)
            CommsLog.add("Dropped envelope ${shortId(envelope.id)}: its sender claim is unsigned or forged")
            return true
        } catch (e: CryptoException) {
            store.markEnvelopeSeen(key, envelope.ts)
            CommsLog.add("Dropped envelope ${shortId(envelope.id)}: ${e.message}")
            return true
        }
        if (decrypted.evictedRecentSession) {
            val who = store.contactByCurve(decrypted.senderCurve25519)?.let { contactLabel(it) } ?: "A sender"
            CommsLog.add("$who started yet another session; the least recently used of the sessions kept with them was dropped")
        }
        if (decrypted.sealedToPrekey) {
            store.contactByCurve(decrypted.senderCurve25519)
                ?.takeIf { it.ed25519 == decrypted.senderEd25519 && store.prekey(it.number) == null }
                ?.let { refreshPrekeyLater(it) }
        }
        // Durable before anything that can fail: seen and staged in one transaction.
        val text = String(decrypted.plaintext, Charsets.UTF_8)
        if (!store.markSeenAndStage(key, envelope.ts, decrypted.senderCurve25519, decrypted.senderEd25519, text)) return true
        processStagedLocked(CommsStore.PendingInbound(key, envelope.ts, decrypted.senderCurve25519, decrypted.senderEd25519, text))
        return true
    }

    /**
     * Acts on a staged payload and removes it once done. Returns false when the
     * relay was needed and could not be reached, so later ones wait too. Caller
     * holds [lock].
     */
    private fun processStagedLocked(p: CommsStore.PendingInbound): Boolean {
        // A photo whose rest never came must not spin as "receiving" for good.
        if (store.expireStaleMedia(System.currentTimeMillis() - MEDIA_RECEIVE_TIMEOUT_MS) > 0) {
            CommsLog.add("Attachment(s) still incomplete after ${MEDIA_RECEIVE_TIMEOUT_MS / 60_000} minutes marked failed")
        }
        val json = CommsWire.parse(p.payload)
        if (json == null) {
            CommsLog.add("Envelope ${shortId(p.id)} decrypted but is not a Clairix payload; dropped")
            store.deletePending(p.id)
            return true
        }
        return try {
            when (val resolved = resolveSender(p.senderCurve25519, p.senderEd25519, json)) {
                is Resolution.Found -> { admitLocked(resolved.contact, p, json); store.deletePending(p.id); true }
                is Resolution.Rejected -> {
                    CommsLog.add("Envelope ${shortId(p.id)} rejected: ${resolved.reason}")
                    // Its pre-key message created a session for a sender who is not
                    // a contact; nothing will ever be sent on it.
                    if (store.contactByCurve(p.senderCurve25519) == null) identities.update { it.dropSessions(p.senderCurve25519) }
                    store.deletePending(p.id)
                    true
                }
                Resolution.Unreachable -> { CommsLog.add("Relay unreachable while confirming who sent ${shortId(p.id)}; holding it"); false }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // Kept staged and tried again on the next envelope or sync.
            CommsLog.add("Envelope ${shortId(p.id)} held for a retry: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * A payload from [contact] that has been staged: processed, held for review
     * when the contact's keys changed, or dropped (see [KeyChangeGuard]).
     * Caller holds [lock].
     */
    private fun admitLocked(contact: Contact, p: CommsStore.PendingInbound, json: JSONObject) {
        val type = CommsWire.type(json)
        when (KeyChangeGuard.inbound(contact, json)) {
            KeyChangeGuard.Action.PROCESS -> processPayload(contact, p.ts, json)
            KeyChangeGuard.Action.QUARANTINE -> {
                val before = store.quarantineCount(contact.number)
                store.quarantine(p.id, contact.number, p.senderCurve25519, p.ts, p.payload, KeyChangeGuard.MAX_HELD_PER_CONTACT)
                CommsLog.add("$type from ${contactLabel(contact)} held: their keys changed; review it in the conversation")
                CommsNotifications.notifyHeld(appContext, contact, store.quarantineCount(contact.number), alert = KeyChangeGuard.alertsOnHold(before))
            }
            KeyChangeGuard.Action.DROP -> CommsLog.add("$type from ${contactLabel(contact)} dropped: their keys changed and have not been checked")
        }
    }

    /**
     * Something from [senderCurve] could not be decrypted. The envelope's sender
     * claim was signed by [senderEd] (the crypto layer checked that), which must
     * be the contact's own identity key: a claim anyone else signed asks for
     * nothing. Every session with them is kept, since envelopes already on
     * their way may still need one; instead a fresh session is started (it goes
     * first, so the resync and everything after it use it) and they are asked
     * to switch to it. At most once a minute per contact and ten times an hour
     * in all ([ResyncLimiter]); not for a claim signed more than a month ago.
     *
     * Caller holds [lock]. The bundle fetch is launched in a new coroutine so
     * the network call does NOT block the lock — holding it for up to 25 s
     * would starve ICE candidate processing during an active call.
     */
    private fun recoverSession(senderCurve: String, senderEd: String, sentAtMs: Long, what: String) {
        val contact = store.contactByCurve(senderCurve)
        if (contact == null) {
            CommsLog.add("$what, from someone who is not a contact; dropped")
            return
        }
        val label = contact.name.ifBlank { formatClairixNumber(contact.number) }
        if (contact.ed25519 != senderEd) {
            CommsLog.add("$what, claiming to be from $label but signed by another key; ignored")
            return
        }
        if (contact.keyChanged) {
            CommsLog.add("$what from $label, whose keys changed; verify them before anything is sent")
            return
        }
        val now = System.currentTimeMillis()
        // Counted now (inside the lock) so concurrent envelopes from the same
        // sender don't each spawn a bundle fetch.
        when (val d = synchronized(resyncs) { resyncs.tryAcquire(contact.number, sentAtMs, now) }) {
            ResyncLimiter.Decision.Allowed -> Unit
            ResyncLimiter.Decision.TooSoonForContact -> {
                CommsLog.add("$what from $label; a new session was asked for moments ago")
                return
            }
            is ResyncLimiter.Decision.HourlyCapReached -> {
                CommsLog.add("$what from $label; as many new sessions as are allowed were already started this hour, so not another until ${java.text.DateFormat.getTimeInstance().format(java.util.Date(d.retryAtMs))}")
                return
            }
            ResyncLimiter.Decision.StaleHint -> {
                CommsLog.add("$what from $label, sent too long ago (or dated in the future) to act on; dropped")
                return
            }
        }
        CommsLog.add("$what from $label; fetching a new session bundle")
        // Network fetch happens outside the lock in a new coroutine; this coroutine
        // suspends at lock.withLock below until the current holder releases it.
        scope.launch {
            try {
                val bundle = relay.bundle(contact.number, fingerprint(contact.ed25519), contact.relay)
                lock.withLock {
                    val fresh = store.contactByCurve(senderCurve) ?: return@withLock
                    if (bundle.ed25519 == fresh.ed25519) notePrekey(fresh, bundle.sealingPrekey)
                    if (bundle.ed25519 != fresh.ed25519 || bundle.curve25519 != fresh.curve25519 || bundle.sealing != fresh.sealing) {
                        CommsLog.add("$what from $label, but the relay now holds different keys for them; nothing sent")
                        return@withLock
                    }
                    try {
                        identities.update {
                            it.startSession(peerKeys(fresh), bundle.sessionKey)
                        }
                    } catch (e: CryptoException.SessionLimit) {
                        // Every session with them decrypted something in the last two
                        // weeks and there are as many as are ever kept: none is given up.
                        CommsLog.add("$what from $label; no new session started: every session this phone keeps with them is in use")
                        return@withLock
                    } catch (e: CryptoException) {
                        CommsLog.add("$what from $label; no new session started (${e.message})")
                        return@withLock
                    }
                    // Presented with the resync, or with whatever goes to them first if it fails.
                    claims.put(fresh.number, bundle.claim, System.currentTimeMillis())
                    CommsLog.add("$what from $label; started a new session and asked them to use it")
                    sendControlLocked(fresh, CommsWire.resync())
                }
            } catch (e: RelayException) {
                synchronized(resyncs) { resyncs.forgetContact(contact.number) }
                CommsLog.add("$what from $label; could not start a new session yet (${e.message})")
            }
        }
    }

    /** Processes staged payloads that were held for a retry. Caller holds [lock]. */
    private fun retryPendingLocked() {
        for (p in store.pending()) if (!processStagedLocked(p)) return
    }

    /** Acts on a decrypted payload from a known contact. Caller holds [lock]. */
    private fun processPayload(contact: Contact, envelopeTs: Long, json: JSONObject) {
        when (CommsWire.type(json)) {
            CommsWire.T_MSG -> {
                val id = CommsWire.messageId(json) ?: return
                // An id already used in another conversation, or for this phone's
                // own message: dropped, and never receipted, so nobody can learn
                // what this phone has read elsewhere by reusing an id.
                if (store.belongsElsewhere(id, contact.number)) return
                val alreadyRead = store.isRead(id)
                if (alreadyRead != null) {
                    // A copy of a message already here (they re-sent it after a network
                    // error): receipt it again, since their first receipt may be lost.
                    store.queueReceipt(contact.number, listOf(id), if (alreadyRead) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                    return
                }
                val body = json.optString(CommsWire.F_BODY).take(MAX_BODY)
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                val onScreen = openPeer == contact.number && !AppLock.locked.value
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, body, ts, "received", read = onScreen, ttl = disappearAfter(contact.number, json)))
                if (onScreen) wakeExpiry()
                store.queueReceipt(contact.number, listOf(id), if (onScreen) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                if (!onScreen) {
                    CommsNotifications.notifyInbound(appContext, contact, store.unreadInbound(contact.number, 5))
                }
            }
            CommsWire.T_RECEIPT -> {
                val status = json.optString(CommsWire.F_STATUS)
                if (status == CommsWire.STATUS_DELIVERED || status == CommsWire.STATUS_READ) {
                    // Only our own messages to this contact can be receipted by them.
                    store.advanceStatus(contact.number, CommsWire.receiptIds(json), status)
                }
            }
            CommsWire.T_RESYNC -> {
                // Their pre-key message has already created a fresh session on our
                // side, first in the list; everything to them from now on uses it.
                // Whatever they could not read is gone from the relay, so messages
                // they never confirmed go again (they drop ids they already have),
                // and so does the setup of a call with them.
                val resent = store.requeueUndelivered(contact.number, System.currentTimeMillis() - RESEND_WINDOW_MS)
                CommsLog.add(
                    "${contact.name.ifBlank { formatClairixNumber(contact.number) }} could not read something from this phone and started a new session" +
                        if (resent > 0) "; sending $resent message(s) again" else ""
                )
                if (resent > 0) bump()
                // After the caller releases the lock.
                scope.launch { flushOutbox() }
                CallManager.onPeerResync(contact)
            }
            CommsWire.T_CALL -> CallManager.onSignal(contact, json, envelopeTs)
            CommsWire.T_PAYMENT -> {
                // The coins were moved by the relay when the payer paid; this is
                // the payer telling us, with the note only we can read.
                val id = CommsWire.messageId(json) ?: return
                val txid = json.optString(CommsWire.F_TXID).takeIf { it.isNotBlank() } ?: return
                val amount = json.optLong(CommsWire.F_AMOUNT, 0L).takeIf { it > 0 } ?: return
                if (!BuildConfig.CLAIRIX_COIN) {
                    // This edition has no ClairixCoin. Receipt the notice so the
                    // payer's phone stops re-sending it, and record nothing.
                    store.queueReceipt(contact.number, listOf(id), CommsWire.STATUS_DELIVERED)
                    CommsLog.add("A ClairixCoin payment notice from ${formatClairixNumber(contact.number)} was not shown: this edition has no ClairixCoin")
                    return
                }
                // An id already used in another conversation, or for this phone's
                // own message: dropped, and never receipted, so nobody can learn
                // what this phone has read elsewhere by reusing an id.
                if (store.belongsElsewhere(id, contact.number)) return
                val alreadyRead = store.isRead(id)
                if (alreadyRead != null || store.hasTransaction(id)) {
                    // A re-sent notice: receipt it again, count nothing twice.
                    store.queueReceipt(contact.number, listOf(id), if (alreadyRead == true) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                    return
                }
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                val note = json.optString(CommsWire.F_NOTE, "").take(MAX_COIN_NOTE)
                val onScreen = openPeer == contact.number && !AppLock.locked.value
                // The notice is only the payer's word: anyone can send one. It is
                // shown as unconfirmed, and becomes a payment received only once
                // the relay, which moved the coins, shows it in this account's history.
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, paymentClaimLine(amount, note), ts, STATUS_PAYMENT, read = onScreen))
                store.queueReceipt(contact.number, listOf(id), if (onScreen) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                if (!onScreen) {
                    CommsNotifications.notifyInbound(appContext, contact, store.unreadInbound(contact.number, 5))
                }
                scope.launch { confirmPayment(contact.number, id, txid, amount, ts, note) }
            }
            CommsWire.T_VOICEMAIL -> {
                val id = CommsWire.messageId(json) ?: return
                val audioB64 = json.optString(CommsWire.F_AUDIO).takeIf { it.isNotBlank() } ?: return
                if (audioB64.length > MAX_VOICEMAIL_B64) {
                    CommsLog.add("Voicemail from ${formatClairixNumber(contact.number)} dropped: too large")
                    return
                }
                // An id already used in another conversation, or for this phone's
                // own message: dropped, and never receipted, so nobody can learn
                // what this phone has read elsewhere by reusing an id.
                if (store.belongsElsewhere(id, contact.number)) return
                val alreadyRead = store.isRead(id)
                if (alreadyRead != null) {
                    store.queueReceipt(contact.number, listOf(id), if (alreadyRead) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                    return
                }
                val durationMs = json.optLong(CommsWire.F_DURATION, 0L).coerceAtLeast(0L)
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                // Arrives unread even to an open conversation: it is audio, and the
                // owner has not heard it; the conversation marks it read as usual.
                val onScreen = openPeer == contact.number && !AppLock.locked.value
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, VoicemailBody(audioB64, durationMs).encode(), ts, STATUS_VOICEMAIL, read = false, ttl = disappearAfter(contact.number, json)))
                store.queueReceipt(contact.number, listOf(id), CommsWire.STATUS_DELIVERED)
                if (!onScreen) {
                    CommsNotifications.notifyInbound(appContext, contact, store.unreadInbound(contact.number, 5))
                }
                CommsLog.add("Voicemail (${durationLabel(durationMs)}) from ${formatClairixNumber(contact.number)}")
            }
            CommsWire.T_MEDIA -> {
                val id = CommsWire.messageId(json) ?: return
                // An id already used in another conversation, or for this phone's
                // own message: dropped, and never receipted, so nobody can learn
                // what this phone has read elsewhere by reusing an id.
                if (store.belongsElsewhere(id, contact.number)) return
                val alreadyRead = store.isRead(id)
                if (alreadyRead != null) {
                    // The header again (they resent after a network error). A file
                    // already whole is receipted again; one still arriving is not yet.
                    if (store.message(id)?.status != STATUS_RECEIVING) {
                        store.queueReceipt(contact.number, listOf(id), if (alreadyRead) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED)
                    }
                    return
                }
                val mime = json.optString(CommsWire.F_MIME).takeIf { it.startsWith("image/") || it.startsWith("video/") } ?: run {
                    CommsLog.add("Attachment from ${formatClairixNumber(contact.number)} dropped: type \"${json.optString(CommsWire.F_MIME)}\" not shown")
                    return
                }
                val size = json.optLong(CommsWire.F_SIZE, 0L)
                val chunks = json.optInt(CommsWire.F_CHUNKS, 0)
                val expectedChunks = ((size + CommsWire.MEDIA_CHUNK_BYTES - 1) / CommsWire.MEDIA_CHUNK_BYTES).toInt()
                if (size <= 0L || size > CommsMedia.MAX_BYTES || chunks <= 0 || chunks > CommsMedia.MAX_CHUNKS || chunks != expectedChunks) {
                    CommsLog.add("Attachment from ${formatClairixNumber(contact.number)} dropped: $size bytes in $chunks chunk(s) is not acceptable")
                    return
                }
                val thumb = json.optString(CommsWire.F_THUMB).takeIf { it.length <= 24_000 } ?: ""
                val body = MediaBody(
                    mime, json.optInt(CommsWire.F_WIDTH, 0), json.optInt(CommsWire.F_HEIGHT, 0), json.optLong(CommsWire.F_DURATION, 0L).coerceAtLeast(0L),
                    size, chunks, json.optString(CommsWire.F_CAPTION, "").take(MAX_CAPTION), thumb
                )
                val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
                // Shown at once as a thumbnail that fills in; read, receipted and
                // notified only when the whole file is here.
                store.insertMessage(ChatMessage(id, contact.number, Direction.IN, body.encode(), ts, STATUS_RECEIVING, read = false, kind = KIND_MEDIA, ttl = disappearAfter(contact.number, json)))
                CommsLog.add("${if (body.isVideo) "Video" else "Photo"} (${size / 1024} KB, $chunks envelope(s)) announced by ${formatClairixNumber(contact.number)}")
                // Its chunks may have overtaken the header.
                completeMediaIfWhole(contact, id, body)
            }
            CommsWire.T_MEDIA_CHUNK -> {
                val id = CommsWire.messageId(json) ?: return
                val index = json.optInt(CommsWire.F_INDEX, -1)
                val data = json.optString(CommsWire.F_DATA).takeIf { it.isNotBlank() } ?: return
                if (index < 0 || index >= CommsMedia.MAX_CHUNKS || data.length > CommsWire.MEDIA_CHUNK_BYTES * 4 / 3 + 4) return
                val existing = store.message(id)
                if (existing != null && existing.status != STATUS_RECEIVING) return // whole already, or not media
                if (existing != null && existing.peer != contact.number) return
                val announced = existing?.let { MediaBody.decode(it.body) }
                if (announced != null && index >= announced.chunks) {
                    // Past the end the header declared: kept, it would make the count
                    // reach the total while a real chunk is missing, and the file
                    // could then never be assembled.
                    CommsLog.add("Chunk $index of an attachment from ${formatClairixNumber(contact.number)} dropped: it announced only ${announced.chunks}")
                    return
                }
                if (existing == null && store.orphanChunkIds().size >= 8 && store.chunkCount(id) == 0) {
                    // Chunks for files whose header never came must not pile up without bound.
                    CommsLog.add("Chunk of an unannounced attachment from ${formatClairixNumber(contact.number)} dropped")
                    return
                }
                val bytes = runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull() ?: return
                store.saveChunk(id, index, bytes, envelopeTs)
                val body = announced ?: return
                completeMediaIfWhole(contact, id, body)
            }
            CommsWire.T_GROUP_MSG, CommsWire.T_GROUP_CTRL, CommsWire.T_THREAT, CommsWire.T_PANIC ->
                processGroupPayload(contact, envelopeTs, json)
            CommsWire.T_TIMER ->
                if (json.has(CommsWire.F_GID)) processGroupPayload(contact, envelopeTs, json)
                else applyTimer(contact.number, envelopeTs, json, contact.name.ifBlank { formatClairixNumber(contact.number) }, group = false)
            // Cover traffic: nothing is kept, shown or acknowledged.
            CommsWire.T_NOISE -> Unit
            else -> CommsLog.add("Payload of type \"${CommsWire.type(json)}\" from ${formatClairixNumber(contact.number)} not understood; ignored")
        }
    }

    /**
     * Once every chunk of media [id] is here, assembles the file, stores it under
     * the message id and turns the message from "receiving" into a received one:
     * receipted, and notified unless the conversation is on screen. Caller holds [lock].
     */
    private fun completeMediaIfWhole(contact: Contact, id: String, body: MediaBody) {
        if (store.chunkCount(id) < body.chunks) return
        val bytes = store.assembleChunks(id, body.chunks)
        if (bytes == null) {
            CommsLog.add("Attachment from ${formatClairixNumber(contact.number)} could not be assembled; waiting for it to be sent again")
            return
        }
        if (bytes.size.toLong() != body.size) {
            CommsLog.add("Attachment from ${formatClairixNumber(contact.number)} arrived with ${bytes.size} bytes, not ${body.size}; dropped")
            store.deleteChunks(id)
            store.setStatus(id, "failed", "The file arrived damaged")
            return
        }
        CommsMedia.save(id, bytes)
        store.deleteChunks(id)
        val onScreen = openPeer == contact.number && !AppLock.locked.value
        store.setStatus(id, "received")
        if (onScreen) {
            store.queueReceipt(contact.number, store.markRead(contact.number), CommsWire.STATUS_READ)
        } else {
            store.queueReceipt(contact.number, listOf(id), CommsWire.STATUS_DELIVERED)
        }
        if (!onScreen) {
            CommsNotifications.notifyInbound(appContext, contact, store.unreadInbound(contact.number, 5))
        }
        CommsLog.add("${if (body.isVideo) "Video" else "Photo"} from ${formatClairixNumber(contact.number)} complete (${bytes.size / 1024} KB)")
    }

    /**
     * The timestamp a payload claims, kept within a week before the relay
     * took it and a minute past now: a phone whose clock is wrong should not
     * put its message at the top or the bottom of the conversation.
     */
    private fun claimedTs(envelopeTs: Long, claimed: Long): Long {
        // The envelope's own time comes from the relay, so it is bounded too: a
        // relay that dated one far ahead would otherwise pass the claim through
        // unchecked, and a timer change dated in the future outlasts every
        // change made after it.
        val now = System.currentTimeMillis()
        val earliest = minOf(envelopeTs, now) - 7L * 24 * 3600_000L
        val latest = now + 60_000L
        return claimed.coerceIn(earliest, latest)
    }

    private sealed class Resolution {
        class Found(val contact: Contact) : Resolution()
        /** The payload claims a number its keys do not hold, or is malformed; drop it. */
        class Rejected(val reason: String) : Resolution()
        /** The relay was needed to confirm the sender and could not be reached; retry later. */
        object Unreachable : Resolution()
    }

    /**
     * Maps the Olm sender key to a contact. A first message from an unknown
     * identity carries its keys and Clairix number; those are pinned as an
     * unverified contact once the relay confirms that number publishes exactly
     * those keys, so nobody can send as a number they do not hold.
     */
    private fun resolveSender(senderCurve: String, senderEd: String, json: JSONObject): Resolution {
        // A payload staged before schema 6 recorded no signing key (sender_ed is
        // ''): Olm still vouched for its Curve25519 key, so it is resolved on
        // that alone rather than rejected.
        val legacy = senderEd.isEmpty()
        store.contactByCurve(senderCurve)?.let { c ->
            // Olm vouches for the Curve25519 key; the envelope's signature for the
            // Ed25519 key. Both must be the contact's.
            return if (legacy || c.ed25519 == senderEd) Resolution.Found(c)
            else Resolution.Rejected("signed by a key that is not ${contactLabel(c)}'s")
        }
        val from = parseClairixNumber(json.optString(CommsWire.F_FROM))
            ?: return Resolution.Rejected("sender is not a contact and gave no Clairix number")
        val ed = json.optString(CommsWire.F_ED25519); val curve = json.optString(CommsWire.F_CURVE25519)
        val sealing = json.optString(CommsWire.F_SEALING); val sig = json.optString(CommsWire.F_SIGNATURE)
        if (curve != senderCurve || (!legacy && ed != senderEd) || ed.isBlank() || sealing.isBlank() || sig.isBlank()) {
            return Resolution.Rejected("keys claimed by ${formatClairixNumber(from)} do not match the sender")
        }
        if (!verify(ed, "$curve|$sealing".toByteArray(Charsets.UTF_8), sig)) {
            return Resolution.Rejected("keys claimed by ${formatClairixNumber(from)} are not signed")
        }
        // The sender's relay, when it is not this phone's: their identity is
        // confirmed there, through this phone's relay, and replies go there.
        val claimedRelay = json.optString(CommsWire.F_RELAY).takeIf { it.isNotBlank() }
        val theirRelay = if (claimedRelay == null || isOwnRelay(claimedRelay)) null
            else RelayClient.originOf(claimedRelay) ?: return Resolution.Rejected("${formatClairixNumber(from)} gave a relay address Clairix cannot use")
        store.contact(from)?.let { known ->
            if (known.relay != theirRelay) {
                return Resolution.Rejected("${formatClairixNumber(from)} on ${relayLabel(theirRelay)} is not ${contactLabel(known)}, who has that number on ${relayLabel(known.relay)}")
            }
        }
        val relayView = try {
            // The identity lookup claims none of their one-time keys.
            relay.identity(from, fingerprint(ed), theirRelay)
        } catch (e: RelayException) {
            // 404 means the relay has no such number, or the pin (their identity key)
            // does not match it: the claim is false. Anything else is the relay's problem.
            return if (e.code == 404) Resolution.Rejected("the relay does not know ${formatClairixNumber(from)} with those keys")
            else Resolution.Unreachable
        }
        if (relayView.ed25519 != ed || relayView.curve25519 != curve || relayView.sealing != sealing) {
            return Resolution.Rejected("the relay holds different keys for ${formatClairixNumber(from)}")
        }
        val existing = store.contact(from)
        val contact = if (existing != null) {
            // Same number, different keys: keep the pinned name, flag it, trust
            // nothing. What comes from the new keys is held until the owner looks.
            identities.update { it.dropSessions(existing.curve25519) }
            existing.copy(ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig, verified = false, keyChanged = true)
        } else {
            Contact(
                number = from, name = json.optString(CommsWire.F_NAME).trim().take(40),
                ed25519 = ed, curve25519 = curve, sealing = sealing, signature = sig,
                verified = false, addedTs = System.currentTimeMillis(), relay = theirRelay
            )
        }
        store.upsertContact(contact)
        notePrekey(contact, relayView.sealingPrekey)
        if (existing != null) {
            noteKeyChange(contact)
            CommsLog.add("Keys of ${contactLabel(contact)} changed; what they send is held until you verify or accept them")
        }
        return Resolution.Found(contact)
    }

    // ── Sync ──────────────────────────────────────────────────────────────

    /** Pulls the whole inbox, sends what is queued (messages and receipts) and tops up keys. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!config.isRegistered) return@withContext Result.success(Unit)
        if (Vault.sealed.value) return@withContext collectWhileLocked()
        setBusy(true)
        try {
            // Every sync tops up delivery tokens, ahead of the sends below.
            topUpTokens()
            lock.withLock { retryPendingLocked() }
            // The relay hands out its queue a page at a time; acked envelopes are
            // deleted, so each fetch returns the next page.
            var drainedAt = 0L
            for (page in 0 until MAX_INBOX_PAGES) {
                // An envelope still held for mixing when this fetch starts is not
                // in it, so "emptied" is dated a few minutes earlier than the fetch.
                val fetchedFrom = System.currentTimeMillis() - MIX_HOLD_MARGIN_MS
                val envelopes = relay.inbox()
                if (envelopes.isEmpty()) { drainedAt = fetchedFrom; break }
                val done = ArrayList<String>()
                for (env in envelopes) {
                    val handled = try {
                        lock.withLock { handleLocked(env) }
                    } catch (e: IllegalStateException) {
                        CommsLog.add("Envelope ${shortId(env.id)} left on the relay: ${e.message}")
                        false
                    }
                    if (handled) done += env.id
                }
                if (done.isNotEmpty()) relay.ack(done)
                if (done.size < envelopes.size) break
                if (envelopes.size < RelayClient.INBOX_PAGE) { drainedAt = fetchedFrom; break }
            }
            if (drainedAt > 0L) pruneSealingPrekeys(drainedAt)
            flushOutbox()
            flushReceipts()
            replenishKeys()
            refreshBalance()
            _state.update { it.copy(busy = false, error = null, lastSync = System.currentTimeMillis()) }
            bump()
            Result.success(Unit)
        } catch (e: Exception) {
            setBusy(false, e.message ?: "Sync failed")
            bump()
            Result.failure(e)
        }
    }

    /**
     * Deletes the replaced sealing prekeys nothing can still arrive for, now
     * that the mailbox was emptied at [drainedAt] (see Identity.pruneSealingPrekeys).
     * From then on, envelopes sealed to them are unreadable to anyone.
     */
    private suspend fun pruneSealingPrekeys(drainedAt: Long) {
        val gone = runCatching { lock.withLock { identities.update { it.pruneSealingPrekeys(drainedAt) } } }.getOrDefault(0u)
        if (gone > 0u) CommsLog.add("Deleted $gone old sealing key(s); envelopes sealed to them can no longer be opened")
    }

    /**
     * The phone is locked: takes the inbox in (signed with the transport key)
     * and keeps every envelope for after the unlock. Nothing is sent: that
     * needs the identity.
     */
    private suspend fun collectWhileLocked(): Result<Unit> = runCatching {
        for (page in 0 until MAX_INBOX_PAGES) {
            val envelopes = relay.inbox()
            if (envelopes.isEmpty()) break
            val kept = envelopes.filter { keepForUnlock(it) }.map { it.id }
            if (kept.isNotEmpty()) relay.ack(kept)
            if (kept.size < envelopes.size || envelopes.size < RelayClient.INBOX_PAGE) break
        }
    }

    fun syncInBackground() {
        if (!initialised || !config.isRegistered) return
        scope.launch { sync() }
    }

    /**
     * Tops up one-time keys from the live connection, at most hourly. A phone
     * kept online by the background service rarely runs a full sync, and its
     * keys on the relay drained until every new session used the fallback key.
     */
    fun replenishKeysInBackground() {
        if (!initialised || !config.isRegistered || Vault.sealed.value) return
        val now = System.currentTimeMillis()
        if (now - lastReplenishAt < REPLENISH_INTERVAL_MS) return
        lastReplenishAt = now
        scope.launch {
            runCatching { replenishKeys() }.onFailure {
                lastReplenishAt = 0L
                CommsLog.add("Could not top up keys on the relay: ${it.message}")
            }
        }
    }

    /**
     * Publishes more one-time keys when the relay is running low, a new
     * fallback key once a day, and fetches delivery tokens when few are left. The network calls are made without the lock.
     * Keys generated for an upload that failed are uploaded next time rather
     * than piling up with new ones.
     */
    private suspend fun replenishKeys() = keysLock.withLock {
        val me = relay.me()
        var count = me.oneTimeKeys
        val now = System.currentTimeMillis()
        val rotate = now - config.fallbackRotatedAt > FALLBACK_ROTATE_MS
        // A week-old sealing prekey (or none, on an identity from before them)
        // is replaced and saved first, and published below; one whose upload
        // failed goes up on the next try.
        val prekey = lock.withLock {
            identities.update { id ->
                if (id.sealingPrekeyDue()) id.rotateSealingPrekey()
                id.publicBundle().sealingPrekey
            }
        }
        val prekeyUnpublished = prekey != null && prekey.createdMs != config.prekeyPublished
        if (count < ONE_TIME_KEYS_LOW || rotate || prekeyUnpublished) {
            val bundle = lock.withLock {
                identities.update { id ->
                    var waiting = id.publicBundle().oneTimeKeys.size
                    if (waiting > ONE_TIME_KEYS_MAX_WAITING) {
                        // Never uploaded, so never handed out: they can simply be retired.
                        id.markKeysPublished()
                        waiting = 0
                    }
                    if (count < ONE_TIME_KEYS_LOW && waiting < ONE_TIME_KEYS_BATCH) {
                        id.generateOneTimeKeys((ONE_TIME_KEYS_BATCH - waiting).toUInt())
                    }
                    if (rotate) id.rotateFallbackKey()
                    id.publicBundle()
                }
            }
            count = relay.putKeys(bundle)
            lock.withLock { identities.update { it.markKeysPublished() } }
            if (rotate) config.setFallbackRotatedAt(now)
            bundle.sealingPrekey?.let { config.setPrekeyPublished(it.createdMs) }
        }
        if (me.listed != config.listed) config.setListed(me.listed)
        // Registered before transport keys, or the relay lost it: give it one now.
        if (!transportKeys.published) {
            runCatching { relay.putTransport(transportKeys.get().publicKey()) }
                .onSuccess { transportKeys.setPublished(true) }
                .onFailure { CommsLog.add("Could not give the relay this phone's transport key: ${it.message}") }
        }
        _state.update { it.copy(relayOneTimeKeys = count, listed = me.listed) }
        // Delivery tokens are fetched here, ahead of need and apart from any send.
        runCatching { relay.topUpTokens() }.onFailure { CommsLog.add("Could not fetch delivery tokens: ${it.message}") }
    }

    /** Marks a conversation read and tells the sender (retried until it goes). */
    suspend fun markRead(peer: String) = withContext(Dispatchers.IO) {
        // Behind the lock nothing is being read, whatever is on screen underneath it.
        if (AppLock.locked.value) return@withContext
        val ids = store.markRead(peer)
        wakeExpiry()
        if (ids.isEmpty()) return@withContext
        bump()
        store.queueReceipt(peer, ids, CommsWire.STATUS_READ)
        // On the repository's scope: leaving the screen must not cancel the receipt.
        scope.launch { flushReceipts() }
    }

    // ── Disappearing messages ─────────────────────────────────────────────

    /** The disappearing-messages timer of a conversation (contact number or group id), in seconds; 0 when off. */
    fun timer(peer: String): Int = if (initialised) unlockedOr(0) { store.timer(peer) } else 0

    /**
     * Sets a conversation's disappearing-messages timer and tells the other side
     * (every member, for a group). From then on everything sent in it carries the
     * timer, so a phone that missed the change still deletes what it is sent, and
     * this phone deletes its own copies of everything in it, sent or received.
     */
    suspend fun setTimer(peer: String, ttl: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isRegistered) throw RelayException("Not registered with a relay")
            val seconds = ttl.coerceIn(0, CommsWire.MAX_TTL)
            val now = System.currentTimeMillis()
            val group = store.group(peer)
            val contact = if (group == null) store.contact(peer) ?: throw RelayException("Unknown contact") else null
            val changed = lock.withLock {
                store.setTimer(peer, seconds, now).also { changed ->
                    if (changed) noteTimer(peer, group != null, null, seconds, now)
                }
            }
            if (!changed) return@runCatching
            bump()
            val payload = CommsWire.timer(UUID.randomUUID().toString(), now, seconds, group?.id)
            if (group != null) {
                val recipients = groupContext(peer).others()
                if (recipients.isNotEmpty()) fanOut("Disappearing-messages change", recipients, payload)
            } else if (contact != null) {
                when (val d = deliver(contact, payload)) {
                    Deliver.Sent -> Unit
                    is Deliver.Failed -> CommsLog.add("Disappearing-messages change not sent: ${d.reason}")
                    is Deliver.TooLarge -> CommsLog.add("Disappearing-messages change not sent: ${d.reason}")
                    // The next message carries the timer anyway.
                    is Deliver.Offline -> CommsLog.add("Disappearing-messages change waits for the next message: ${d.reason}")
                }
            }
        }
    }

    /**
     * How long an inbound message in [peer]'s conversation lasts once read: the
     * shorter of what the sender asked for and this phone's own timer, so the
     * owner's setting governs every copy on this phone, whatever the sender's app.
     */
    private fun disappearAfter(peer: String, json: JSONObject): Int {
        val asked = CommsWire.ttlOf(json)
        val mine = store.timer(peer)
        return listOf(asked, mine).filter { it > 0 }.minOrNull() ?: 0
    }

    /** A timer change from [who] in [peer]'s conversation. Caller holds [lock]. */
    private fun applyTimer(peer: String, envelopeTs: Long, json: JSONObject, who: String, group: Boolean) {
        val ttl = CommsWire.ttlOf(json)
        val ts = claimedTs(envelopeTs, json.optLong(CommsWire.F_TS, envelopeTs))
        if (!store.setTimer(peer, ttl, ts)) return
        noteTimer(peer, group, who, ttl, ts)
        CommsLog.add("$who ${if (ttl > 0) "set disappearing messages to ${ttlLabel(ttl)}" else "turned disappearing messages off"}")
        bump()
    }

    /** Writes the line that says the timer changed into the conversation. [who] null is this phone's owner. */
    private fun noteTimer(peer: String, group: Boolean, who: String?, ttl: Int, ts: Long) {
        val subject = who ?: "You"
        val line = if (ttl > 0) "$subject set disappearing messages to ${ttlLabel(ttl)}" else "$subject turned disappearing messages off"
        val note = ChatMessage(UUID.randomUUID().toString(), peer, Direction.IN, line, ts, STATUS_SYSTEM, read = who == null, kind = KIND_NOTE)
        if (group) store.insertGroupMessage(peer, note) else store.insertMessage(note)
    }

    private val expiryWake = Channel<Unit>(Channel.CONFLATED)

    /** Has the sweep look again now: a message's clock started or a new one is counting down. */
    private fun wakeExpiry() {
        expiryWake.trySend(Unit)
    }

    /**
     * Deletes disappearing messages as their time comes, for as long as the
     * process lives: at the moment the next one is due, and at least once a
     * minute. What is past its time is never shown even before this runs (the
     * store's reads leave it out), and a process that was not running sweeps
     * the moment it starts.
     */
    private fun startExpirySweep() {
        scope.launch {
            while (true) {
                // Locked, the database is closed: what is due goes the moment it opens.
                val next = if (Vault.sealed.value) null else runCatching { sweepExpired() }
                    .onFailure { CommsLog.add("Deleting disappearing messages failed: ${it.message}") }
                    .getOrNull()
                val wait = next?.let { (it - System.currentTimeMillis()).coerceIn(EXPIRY_MIN_WAIT_MS, EXPIRY_POLL_MS) } ?: EXPIRY_POLL_MS
                withTimeoutOrNull(wait) { expiryWake.receive() }
            }
        }
    }

    /** Deletes what is due now, with its files and notifications; returns when the next is due. */
    private suspend fun sweepExpired(): Long? = lock.withLock {
        val gone = store.expireMessages(System.currentTimeMillis())
        if (gone.isNotEmpty()) {
            for ((id, _, kind) in gone) if (kind == KIND_MEDIA) CommsMedia.delete(id)
            for (peer in gone.map { it.second }.toSet()) CommsNotifications.cancel(appContext, peer)
            CommsLog.add("${gone.size} disappearing message(s) deleted")
            bump()
        }
        store.nextExpiry()
    }

    // ── Service helpers ───────────────────────────────────────────────────

    internal fun relayClient(): RelayClient = relay
    internal fun isRegistered(): Boolean = initialised && config.isRegistered
    internal fun number(): String? = config.number
}

/** Starts a foreground service in the way Android accepts from any process state. */
internal fun Context.startForegroundCompat(intent: Intent) {
    ContextCompat.startForegroundService(this, intent)
}
