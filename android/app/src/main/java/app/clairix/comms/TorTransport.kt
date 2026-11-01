package app.clairix.comms

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.clairix_comms_crypto.TorProxy
import uniffi.clairix_comms_crypto.TorStatus
import java.io.File
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Connect through Tor": everything the app says to relays (and the relay
 * list) goes through Tor, so no relay, ours or anyone's, learns this phone's
 * network address, and nobody watching the phone's network learns which relay
 * it uses.
 *
 * Tor itself is Arti, the Tor Project's Rust client, running in the app's
 * native library behind a SOCKS5 proxy on 127.0.0.1 (comms-crypto/src/tor.rs).
 * The HTTP clients find it through [proxySelector], asked afresh for every
 * new connection, and it fails closed: while the setting is on and Tor is not
 * up yet, connections go to a port nothing listens on and fail, never out
 * directly. Names are resolved by the Tor exit: OkHttp hands a SOCKS proxy
 * the host name unresolved.
 *
 * Arti does not run for nothing ([TorPacing]). Its state is read every second
 * while it bootstraps, then before each new connection and once a minute. With
 * the background connection off and nothing holding the link, it sleeps once
 * nothing has gone through it for five minutes; the next connection starts it
 * again on the spot and waits for it, so the first request after a sleep is a
 * few seconds slower and none ever goes out directly.
 *
 * What does not go through Tor: call audio and video (WebRTC sends UDP, which
 * Tor cannot carry), so the relay's TURN server sees this phone's address
 * during a call, and a contact does too unless "Relay-only calls" is on; and
 * push wake-ups, which the UnifiedPush distributor app fetches itself.
 */
object TorTransport {

    private const val TAG = "TorTransport"
    private const val PREFS = "comms_tor"
    private const val KEY_ENABLED = "enabled"
    /** Where connections go while Tor is on but its proxy is not up: nothing listens on port 1. */
    private const val CLOSED_PORT = 1

    /**
     * 127.0.0.1, where the proxy listens (comms-crypto/src/tor.rs binds IPv4
     * loopback only). Not InetAddress.getLoopbackAddress(), which on Android
     * is ::1: every connection was refused there (TorDeviceTest, Android 14).
     */
    private val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    sealed interface State {
        data object Off : State
        data class Starting(val percent: Int, val summary: String) : State
        data object Ready : State
        /** Stopped because nothing needed it for a while; the next connection starts it again. */
        data object Asleep : State
        data class Blocked(val percent: Int, val reason: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private lateinit var appDir: File
    private lateinit var stateDir: File
    private lateinit var cacheDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Guards starting, stopping and putting Arti to sleep. Held across a start, which blocks briefly. */
    private val life = Any()

    @Volatile private var proxy: TorProxy? = null
    @Volatile private var port: Int = 0
    private var watcher: Job? = null

    /** Stopped for want of use ([TorPacing.shouldSleep]); the next connection starts it again. */
    @Volatile private var asleep = false

    /** When something last went through Tor: a new connection, or a request starting or ending. */
    @Volatile private var lastUseAt = 0L

    /** Whatever keeps Tor running whether or not anything uses it; see [keepRunningWhile]. */
    @Volatile private var keepRunning: () -> Boolean = { true }

    // The proxy's login, made for each process: other apps on the phone can
    // reach 127.0.0.1 too, and must not use (or be seen to use) Clairix's Tor.
    private val username = randomToken()
    private val password = randomToken()

    /** Whether the owner has turned it on. Read on every connection. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Reads the setting and, when it is on, starts Tor. Called as the process starts, before any relay request. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appDir = File(app.applicationInfo.dataDir)
        stateDir = File(app.filesDir, "tor/state")
        cacheDir = File(app.cacheDir, "tor")
        enabled = prefs?.getBoolean(KEY_ENABLED, false) == true
        // The JDK's SOCKS client asks the default Authenticator for the proxy's
        // login; this one answers for Clairix's own proxy and nothing else.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val p = port
                val socks = requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true
                return if (socks && p > 0 && requestingPort == p) PasswordAuthentication(username, password.toCharArray()) else null
            }
        })
        if (enabled) start()
    }

    /**
     * What keeps Tor running whether or not anything has used it lately: for
     * comms, the background connection being on or the link being held (the
     * app on screen, a call). Until this is set, Tor never sleeps.
     */
    fun keepRunningWhile(rule: () -> Boolean) {
        keepRunning = rule
    }

    /** Turns it on or off. Connections already open are dropped, so none keeps going around (or through) Tor. */
    fun setEnabled(on: Boolean) {
        synchronized(this) {
            if (on == enabled) return
            enabled = on
            prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.commit()
            if (on) start() else stop()
        }
        CommsLog.add(if (on) "Connecting through Tor" else "Tor off: connecting directly")
        changed()
    }

    /** Called with no arguments when the way out changes: drop pooled connections, reconnect sockets. */
    fun onChange(listener: () -> Unit) { listeners += listener }

    private fun changed() {
        for (l in listeners) runCatching { l() }
    }

    /**
     * Something is going through Tor: a request starting or ending. It keeps
     * Tor from sleeping under a request that rides a connection already open,
     * where [proxySelector] is not asked.
     */
    fun noteUse() {
        lastUseAt = SystemClock.elapsedRealtime()
    }

    /** Reads Arti's state now, for the Comms settings line, rather than at the next minute. */
    fun refreshStatus() {
        val p = proxy ?: return
        runCatching { p.status() }.getOrNull()?.let { publish(it) }
    }

    private fun publish(s: TorStatus) {
        val next = when {
            s.ready -> State.Ready
            s.blocked != null -> State.Blocked(s.percent.toInt(), s.blocked!!)
            else -> State.Starting(s.percent.toInt(), s.summary)
        }
        if (_state.value != next) _state.value = next
    }

    /** Starts Tor in the background, off the caller's thread. */
    private fun start() {
        if (proxy != null) return
        asleep = false
        _state.value = State.Starting(0, "Starting")
        scope.launch {
            // Connections refused while the proxy came up can go now.
            if (startNow()) changed()
        }
    }

    /**
     * Starts Arti and its proxy on this thread, which must not be the main
     * one, and follows its state. False when it did not start (or the setting
     * went off meanwhile).
     */
    private fun startNow(): Boolean = synchronized(life) {
        if (proxy != null) return true
        if (!enabled) return false
        _state.value = State.Starting(0, "Starting")
        val started = try {
            stateDir.mkdirs()
            cacheDir.mkdirs()
            TorProxy.start(appDir.absolutePath, stateDir.absolutePath, cacheDir.absolutePath, username, password)
        } catch (e: Exception) {
            Log.w(TAG, "Tor did not start", e)
            _state.value = State.Failed(e.message ?: "Tor did not start")
            return false
        }
        if (!enabled) {
            started.stop()
            return false
        }
        proxy = started
        port = started.socksPort().toInt()
        asleep = false
        lastUseAt = SystemClock.elapsedRealtime()
        watch(started)
        true
    }

    /**
     * Follows [started]'s state: every second while it bootstraps, then once
     * a minute, putting it to sleep when nothing needs it ([TorPacing]).
     * Caller holds [life].
     */
    private fun watch(started: TorProxy) {
        watcher?.cancel()
        watcher = scope.launch {
            var wasReady = false
            while (isActive) {
                val s = runCatching { started.status() }.getOrNull() ?: break
                publish(s)
                if (s.ready && !wasReady) CommsLog.add("Tor connected")
                wasReady = s.ready
                if (s.ready && sleepIfIdle(started)) break
                delay(TorPacing.pollMs(s.ready))
            }
        }
    }

    /**
     * Stops [started] when nothing keeps Tor running and nothing has gone
     * through it for a while; [proxySelector] starts it again for the next
     * connection. True when it is no longer running (put to sleep now, or
     * stopped or replaced already), so its watcher ends.
     */
    private fun sleepIfIdle(started: TorProxy): Boolean {
        // Asked outside [life]: the rule reads comms and the link, which never wait on Tor.
        val needed = runCatching { keepRunning() }.getOrDefault(true)
        synchronized(life) {
            if (proxy !== started) return true
            if (!TorPacing.shouldSleep(needed, lastUseAt, SystemClock.elapsedRealtime())) return false
            proxy = null
            port = 0
            asleep = true
            runCatching { started.stop() }
            _state.value = State.Asleep
        }
        CommsLog.add("Tor asleep: nothing has needed it for ${TorPacing.IDLE_SLEEP_MS / 60_000} min; it starts again with the next request")
        // Pooled connections went through the proxy just stopped.
        changed()
        return true
    }

    /** A new connection is about to go through Tor: it counts as use, wakes Tor if it sleeps, and gets the port (0 when none). */
    private fun portForConnection(): Int {
        noteUse()
        if (asleep) synchronized(life) {
            if (asleep && enabled) {
                asleep = false
                CommsLog.add("Tor waking for a request")
                startNow()
            }
        }
        refreshStatus()
        return port
    }

    private fun stop() {
        synchronized(life) {
            watcher?.cancel()
            watcher = null
            val p = proxy
            proxy = null
            port = 0
            asleep = false
            runCatching { p?.stop() }
            _state.value = State.Off
        }
    }

    /**
     * The proxy for every relay connection: Tor's while the setting is on (or
     * a closed port while Tor is not up yet), none while it is off. OkHttp
     * asks for each new connection and never falls back to a direct one when
     * the proxy fails. A Tor put to sleep is started again here, on the
     * connection's own thread, and the connection waits for it.
     */
    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            if (enabled) listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, portForConnection().takeIf { it > 0 } ?: CLOSED_PORT)))
            else listOf(Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Nothing to fall back to, by design.
        }
    }

    /** For tests: stops the proxy as a crash would, leaving the setting on. */
    @androidx.annotation.VisibleForTesting
    fun killProxyForTest() {
        synchronized(life) {
            watcher?.cancel()
            val p = proxy
            proxy = null
            runCatching { p?.stop() }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
