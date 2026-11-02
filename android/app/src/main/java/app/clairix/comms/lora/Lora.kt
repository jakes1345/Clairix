package app.clairix.comms.lora

import android.content.Context
import android.util.Base64
import app.clairix.Store
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import app.clairix.comms.SealedPrefs
import app.clairix.comms.Vault
import app.clairix.comms.mesh.Mesh
import app.clairix.comms.mesh.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.clairix_comms_crypto.LoraPairState
import uniffi.clairix_comms_crypto.LoraPairing
import uniffi.clairix_comms_crypto.loraNetworkCode
import uniffi.clairix_comms_crypto.loraNewNetworkKey
import uniffi.clairix_comms_crypto.loraParseNetworkCode
import uniffi.clairix_comms_crypto.loraPublicNetworkKey
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The LoRa radio as the rest of the app sees it: which radio this phone is paired
 * with, the radio's settings and network, and the two things it carries.
 *
 * Bundles: the same sealed envelopes the Bluetooth mesh carries ([MeshProtocol]),
 * the full Clairix message with its ratchet and sealed sender. The owner's own
 * bundles (texts and SOS, at most [LoraLinkProtocol.MAX_ENVELOPE] bytes) are handed
 * to the radio to put on the air; bundles the radio hears come back and go into the
 * mesh store, and those for this phone into the inbox. A bundle the radio hands over
 * while the vault is sealed is kept but not acknowledged, so the radio offers it again
 * after the unlock, when this phone can tell whether it is its own.
 *
 * Lite: short texts and SOS with people paired over the radio, most of all someone
 * carrying a LilyGO T-Deck ([LoraMessenger]). Opened and kept only while the vault is
 * open; bodies heard while it is sealed wait in memory and are opened at the unlock.
 *
 * It runs inside the mesh's foreground service: [attach] when the mesh starts, if a
 * radio is paired, and [detach] when it stops. One thread owns the state here.
 */
object Lora {

    private const val PREFS = "lora"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_PRESET = "preset"
    private const val KEY_POWER = "power"
    private const val KEY_HOPS = "hops"
    private const val KEY_NETWORK = "network"
    private const val KEY_PAIR_NAME = "pair_name"
    private const val LITE_FILE = "lora_lite.json"
    private const val HELD_MAX = 64
    const val DEFAULT_POWER = 22
    const val DEFAULT_HOPS = 5

    data class Status(
        val paired: Boolean = false,
        val radioName: String = "",
        val connected: Boolean = false,
        val info: LoraLinkProtocol.Info? = null,
        val infoAt: Long = 0L,
        val error: String? = null,
        val bundlesOut: Int = 0,
        val bundlesIn: Int = 0,
        val forYou: Int = 0,
        /** Lite bodies heard while the vault was sealed, waiting for the unlock. */
        val held: Int = 0
    )

    /** A pairing in progress with a handheld or another phone, over the air. */
    data class Pairing(val state: LoraPairState, val digits: Int, val peerName: String)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Goes up whenever a Lite contact or message changes; the screens read [contacts] and [messages] again. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _found = MutableStateFlow<List<LoraRadioLink.Found>>(emptyList())
    val found: StateFlow<List<LoraRadioLink.Found>> = _found.asStateFlow()

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lora").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()

    @Volatile private var app: Context? = null
    private var prefs: SealedPrefs? = null
    private var link: LoraRadioLink? = null
    private var attached = false
    private var ticker: ScheduledFuture<*>? = null
    private var vaultWatch: Job? = null

    private var messenger: LoraMessenger? = null
    private var liteStore: Store? = null
    private var ownKey: ByteArray? = null
    private val held = ArrayDeque<Pair<ByteArray, Int>>()
    private var pairingSession: LoraPairing? = null
    /** The owner's bundles handed to the radio on this connection. */
    private val handed = HashSet<String>()
    private val onStoreChange: () -> Unit = { exec.execute { handOwnBundles() } }

    // ── Settings ──────────────────────────────────────────────────────────

    private fun prefs(context: Context): SealedPrefs =
        prefs ?: synchronized(this) { prefs ?: SealedPrefs(context.applicationContext, PREFS).also { prefs = it } }

    fun pairedAddress(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun preset(context: Context): LoraLinkProtocol.Preset = LoraLinkProtocol.Preset.of(prefs(context).getInt(KEY_PRESET, 1))
    fun power(context: Context): Int = prefs(context).getInt(KEY_POWER, DEFAULT_POWER)
    fun hops(context: Context): Int = prefs(context).getInt(KEY_HOPS, DEFAULT_HOPS)

    /** The network key: the public one unless the owner made or joined a private one. */
    fun networkKey(context: Context): ByteArray =
        prefs(context).getString(KEY_NETWORK, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == LoraLinkProtocol.KEY_LEN } ?: loraPublicNetworkKey()

    fun isPublicNetwork(context: Context): Boolean = networkKey(context).contentEquals(loraPublicNetworkKey())

    /** The code that lets another radio join this network: 55 characters to read out or type. */
    fun networkCode(context: Context): String = runCatching { loraNetworkCode(networkKey(context)) }.getOrDefault("")

    fun pairName(context: Context): String = prefs(context).getString(KEY_PAIR_NAME, null) ?: "Phone"

    fun setRadio(context: Context, preset: LoraLinkProtocol.Preset, power: Int, hops: Int) {
        prefs(context).edit()
            .putInt(KEY_PRESET, preset.code)
            .putInt(KEY_POWER, power.coerceIn(LoraLinkProtocol.MIN_TX_DBM, LoraLinkProtocol.MAX_TX_DBM))
            .putInt(KEY_HOPS, hops.coerceIn(1, LoraLinkProtocol.MAX_HOP_LIMIT)).apply()
        exec.execute { sendConfig() }
    }

    /** Joins the network a code names; false when the code does not read. */
    fun joinNetwork(context: Context, code: String): Boolean {
        val key = runCatching { loraParseNetworkCode(code) }.getOrNull() ?: return false
        prefs(context).edit().putString(KEY_NETWORK, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        exec.execute { sendConfig() }
        return true
    }

    /** A new private network: only radios given its code hear anything on it. */
    fun newPrivateNetwork(context: Context) {
        prefs(context).edit().putString(KEY_NETWORK, Base64.encodeToString(loraNewNetworkKey(), Base64.NO_WRAP)).apply()
        exec.execute { sendConfig() }
    }

    fun usePublicNetwork(context: Context) {
        prefs(context).edit().remove(KEY_NETWORK).apply()
        exec.execute { sendConfig() }
    }

    // ── Pairing the radio itself ──────────────────────────────────────────

    fun scanForRadios(context: Context) {
        val l = link(context)
        _found.value = emptyList()
        l.scan { f -> _found.update { list -> (list.filter { it.address != f.address } + f).sortedByDescending { it.rssi } } }
    }

    fun stopScan() { link?.stopScan() }

    /** Bonds with [radio] and makes it this phone's radio. [done] is called with the outcome. */
    fun pairRadio(context: Context, radio: LoraRadioLink.Found, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        link(app).bond(radio.address) { ok ->
            if (ok) {
                prefs(app).edit().putString(KEY_ADDRESS, radio.address).putString(KEY_NAME, radio.name).apply()
                _status.update { it.copy(paired = true, radioName = radio.name, error = null) }
                exec.execute { if (attached) link?.connect(radio.address) else if (Mesh.enabled(app)) attach(app) }
            }
            done(ok)
        }
    }

    /** Forgets the radio here; it forgets this phone when its button is held for ten seconds. */
    fun forgetRadio(context: Context) {
        val app = context.applicationContext
        val address = pairedAddress(app)
        prefs(app).edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        exec.execute {
            link?.disconnect()
            address?.let { link?.unbond(it) }
            _status.value = Status()
        }
    }

    private fun link(context: Context): LoraRadioLink =
        link ?: LoraRadioLink(context.applicationContext, listener).also { link = it }

    // ── Running ───────────────────────────────────────────────────────────

    /** Starts the radio side, from the mesh service; does nothing until a radio is paired. */
    fun attach(context: Context) {
        val a = context.applicationContext
        app = a
        exec.execute {
            if (attached) return@execute
            val address = pairedAddress(a)
            _status.update { it.copy(paired = address != null, radioName = prefs(a).getString(KEY_NAME, null) ?: "") }
            if (address == null) return@execute
            attached = true
            Mesh.store(a).addListener(onStoreChange)
            link(a).connect(address)
            ticker = exec.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
            vaultWatch = scope.launch { Vault.sealed.collect { sealed -> exec.execute { if (sealed) closeLite() else openLite() } } }
        }
    }

    fun detach() = exec.execute {
        if (!attached) return@execute
        attached = false
        app?.let { Mesh.store(it).removeListener(onStoreChange) }
        ticker?.cancel(false)
        vaultWatch?.cancel()
        link?.disconnect()
        closeLite()
        cancelPairingLocked()
        _status.update { it.copy(connected = false) }
    }

    private val listener = object : LoraRadioLink.Listener {
        override fun onReady() = exec.execute {
            handed.clear()
            _status.update { it.copy(connected = true, error = null) }
            link?.send(LoraLinkProtocol.hello())
            sendConfig()
            handOwnBundles()
        }

        override fun onFrame(frame: LoraLinkProtocol.Frame) = exec.execute { handle(frame) }

        override fun onDown(reason: String?) = exec.execute {
            _status.update { it.copy(connected = false, error = reason) }
        }
    }

    private fun sendConfig() {
        val a = app ?: return
        val l = link ?: return
        if (!l.isReady) return
        l.send(LoraLinkProtocol.config(LoraLinkProtocol.Config(preset(a).code, power(a), hops(a), networkKey(a), System.currentTimeMillis())))
    }

    private fun handle(frame: LoraLinkProtocol.Frame) {
        when (frame) {
            is LoraLinkProtocol.Frame.InfoFrame -> _status.update { it.copy(info = frame.info, infoAt = System.currentTimeMillis()) }
            is LoraLinkProtocol.Frame.BundleIn -> onBundle(frame)
            is LoraLinkProtocol.Frame.LiteIn -> onLite(frame.body, frame.rssi)
            is LoraLinkProtocol.Frame.PairIn -> {
                val p = pairingSession ?: return
                p.onBody(frame.body, System.currentTimeMillis().toULong()).forEach { sendPair(it) }
                publishPairing()
            }
        }
    }

    // ── Bundles ───────────────────────────────────────────────────────────

    private fun handOwnBundles() {
        val a = app ?: return
        val l = link ?: return
        if (!l.isReady) return
        for (b in Mesh.store(a).ownBundles(System.currentTimeMillis())) {
            if (b.idHex in handed || b.data.size > LoraLinkProtocol.MAX_ENVELOPE) continue
            val frame = LoraLinkProtocol.bundleOut(LoraLinkProtocol.LinkBundle(b.tag, b.hops, b.createdMs, b.data), LoraLinkProtocol.BUNDLE_OWN)
                ?: continue
            if (l.send(frame)) {
                handed += b.idHex
                _status.update { it.copy(bundlesOut = it.bundlesOut + 1) }
            }
        }
    }

    private fun onBundle(f: LoraLinkProtocol.Frame.BundleIn) {
        val a = app ?: return
        val now = System.currentTimeMillis()
        val lb = f.bundle
        val b = MeshProtocol.Bundle(MeshProtocol.idOf(lb.envelope), lb.tag, lb.hops, lb.createdMs, lb.envelope)
        val store = Mesh.store(a)
        _status.update { it.copy(bundlesIn = it.bundlesIn + 1) }
        // Sealed: this phone cannot tell its own bundles yet. Keep it, and let the radio offer it again after the unlock.
        if (Vault.sealed.value) {
            store.add(b, now)
            return
        }
        if (MeshProtocol.hex(b.tag) in CommsRepository.meshTags()) {
            if (store.markDelivered(b, now)) {
                _status.update { it.copy(forYou = it.forYou + 1) }
                CommsRepository.receiveFromMesh(b.data)
            }
        } else {
            store.add(b, now)
        }
        link?.send(LoraLinkProtocol.got(f.bid))
    }

    // ── Lite ──────────────────────────────────────────────────────────────

    private fun openLite() {
        val a = app ?: return
        if (messenger != null) return
        val identity = CommsRepository.loraIdentity() ?: return
        val store = Store(File(a.filesDir, LITE_FILE), cipher = VaultStoreCipher)
        val m = LoraMessenger(UniffiLiteCodec(identity), { body -> sendLite(body) })
        m.load(store.load { org.json.JSONObject() })
        m.onIncoming = { c, msg ->
            CommsNotifications.notifyRadio(a, c.name, if (msg.kind == LoraMessenger.KIND_SOS) sosLine(msg) else msg.text, msg.kind == LoraMessenger.KIND_SOS)
        }
        messenger = m
        liteStore = store
        ownKey = identity.loraPublic()
        val now = System.currentTimeMillis()
        while (held.isNotEmpty()) held.removeFirst().let { (body, rssi) -> m.onLite(body, rssi, now) }
        _status.update { it.copy(held = 0) }
        saveLite()
        // What the radio held back while this phone was sealed.
        link?.send(LoraLinkProtocol.hello())
    }

    private fun closeLite() {
        saveLite()
        liteStore?.flush()
        messenger = null
        liteStore = null
        ownKey = null
        cancelPairingLocked()
        bump()
    }

    private fun saveLite() {
        val m = messenger ?: return
        if (!m.dirty) return
        liteStore?.replace(m.toJson())
        m.dirty = false
        bump()
    }

    private fun bump() { _revision.update { it + 1 } }

    private fun sendLite(body: ByteArray): Boolean {
        val a = app ?: return false
        return link?.send(LoraLinkProtocol.liteOut(body, hops(a))) ?: false
    }

    private fun onLite(body: ByteArray, rssi: Int) {
        val m = messenger
        if (m == null) {
            held.addLast(body to rssi)
            while (held.size > HELD_MAX) held.removeFirst()
            _status.update { it.copy(held = held.size) }
            return
        }
        m.onLite(body, rssi, System.currentTimeMillis())
        saveLite()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        messenger?.let { it.tick(now); saveLite() }
        pairingSession?.let { p ->
            p.tick(now.toULong()).forEach { sendPair(it) }
            publishPairing()
        }
    }

    /** Lite needs the vault open; the screens say so when this is false. */
    fun liteOpen(): Boolean = messenger != null

    fun contacts(): List<LoraMessenger.Contact> = onExec { messenger?.contacts() } ?: emptyList()

    fun messages(contactHex: String): List<LoraMessenger.Message> = onExec { messenger?.messages(contactHex) } ?: emptyList()

    fun sendText(contactHex: String, text: String): Boolean =
        onExec { messenger?.sendText(contactHex, text.trim(), System.currentTimeMillis()).also { saveLite() } } != null

    fun sendSos(contactHex: String, position: Pair<Int, Int>?, text: String): Boolean =
        onExec { messenger?.sendSos(contactHex, position, text, System.currentTimeMillis()).also { saveLite() } } != null

    fun resend(message: LoraMessenger.Message) = exec.execute { messenger?.resend(message, System.currentTimeMillis()); saveLite() }

    fun markRead(contactHex: String) = exec.execute { messenger?.markRead(contactHex); saveLite() }

    fun rename(contactHex: String, name: String) = exec.execute { messenger?.rename(contactHex, name); saveLite() }

    fun removeContact(contactHex: String) = exec.execute { messenger?.removeContact(contactHex); saveLite() }

    /** Runs [f] on the Lora thread and waits for it; the screens call this, so it is brief. */
    private fun <T> onExec(f: () -> T): T? = runCatching { exec.submit<T> { f() }.get(5, TimeUnit.SECONDS) }.getOrNull()

    private fun sosLine(m: LoraMessenger.Message): String {
        val where = if (m.hasPosition) " at %.5f, %.5f".format(Locale.US, m.lat7 / 1e7, m.lon7 / 1e7) else ""
        return (m.text.ifBlank { "Needs help" }) + where
    }

    // ── Pairing a person over the air ─────────────────────────────────────

    /**
     * Starts pairing with someone who starts at the same moment on their handheld or
     * phone, both on the same network: each side commits to its key, then reveals it,
     * and both screens show six digits that match only if nobody in between swapped a
     * key. [name] is what the other side will call this phone.
     */
    fun startPairing(context: Context, name: String) {
        val clean = cutUtf8(name.trim(), LoraMessenger.NAME_MAX).ifBlank { "Phone" }
        prefs(context).edit().putString(KEY_PAIR_NAME, clean).apply()
        exec.execute {
            val key = ownKey ?: return@execute
            cancelPairingLocked()
            val p = runCatching { LoraPairing(key, clean) }.getOrNull() ?: return@execute
            p.start(System.currentTimeMillis().toULong(), random.nextInt(3_000).toUInt())
            pairingSession = p
            publishPairing()
        }
    }

    /** The owner saw the same six digits on both screens: [name] becomes a contact. */
    fun confirmPairing(name: String?) = exec.execute {
        val p = pairingSession ?: return@execute
        val peer = p.peer() ?: return@execute
        val key = ownKey ?: return@execute
        messenger?.addContact(peer, name?.takeIf { it.isNotBlank() } ?: p.peerName(), System.currentTimeMillis(), key)
        saveLite()
        cancelPairingLocked()
    }

    fun cancelPairing() = exec.execute { cancelPairingLocked() }

    private fun cancelPairingLocked() {
        pairingSession?.let { runCatching { it.cancel() }; it.destroy() }
        pairingSession = null
        _pairing.value = null
    }

    private fun publishPairing() {
        val p = pairingSession ?: return
        _pairing.value = Pairing(p.state(), p.digits().toInt(), p.peerName())
    }

    private fun sendPair(body: ByteArray) {
        if (body.isEmpty() || body.size > LoraLinkProtocol.BODY_MAX) return
        link?.send(LoraLinkProtocol.pairOut(body))
    }

    /** This phone's Lite key fingerprint, to read out when checking a contact: four and four characters. */
    fun fingerprint(): String? = onExec { ownKey?.let { k -> runCatching { uniffi.clairix_comms_crypto.loraFingerprint(k) }.getOrNull() } }

    fun contactFingerprint(contact: LoraMessenger.Contact): String =
        runCatching { uniffi.clairix_comms_crypto.loraFingerprint(contact.key) }.getOrDefault("")
}
