package app.clairix.analysis

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.ProxyInfo
import android.provider.Settings
import android.util.Base64
import app.clairix.Alert
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.comms.CommsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * What one canary run saw.
 *
 * [intercepted] (CRITICAL) is true when the relay's key differs from the pinned
 * one on the direct path, or when a configured proxy hands back a different key
 * than the relay presents — the proxy substitutes the certificate. [method] says
 * which.
 *
 * [untrustedUnpinned] (HIGH) is a first contact whose chain no trust store on the
 * phone accepts. That is not pinned automatically; the owner can pin it by hand
 * for a self-hosted relay with a private CA, after which only a later mismatch
 * counts.
 *
 * A configured proxy whose path was checked and found clean is reported at MEDIUM
 * ([proxyOnly]). [proxyVerified] says whether the relay's key was actually seen
 * unchanged through it; [proxyNote] says what was found or why it could not be
 * checked.
 */
data class TlsCanaryResult(
    val intercepted: Boolean,
    val method: String?,
    val proxyHost: String?,
    /** How the proxy is configured, when one is. */
    val proxyMethod: String? = null,
    val relayHost: String? = null,
    /** "sha256/<base64>" of the leaf certificate's SubjectPublicKeyInfo, as pinned. */
    val pinnedSpki: String? = null,
    /** The SPKI hash seen on the direct connection, or null when it failed. */
    val observedSpki: String? = null,
    /** CN of the leaf's issuer as presented, for the alert text. */
    val observedIssuer: String? = null,
    /** Whether the presented chain validates against the phone's default trust store. */
    val chainTrusted: Boolean? = null,
    /** True when this run pinned the key (first successful connection). */
    val pinnedNow: Boolean = false,
    val error: String? = null,
    /** First contact with a chain no trust store accepts; not pinned, can be pinned by hand. */
    val untrustedUnpinned: Boolean = false,
    /** Null with no proxy; true when the relay's key was seen unchanged through it; false when not verified. */
    val proxyVerified: Boolean? = null,
    /** What the check through the proxy found, or why it could not be made. */
    val proxyNote: String? = null,
    /** The SPKI hash the relay presented through the proxy, when that probe completed. */
    val viaProxySpki: String? = null,
    /** Issuer CN of the certificate presented through the proxy. */
    val viaProxyIssuer: String? = null,
    val ts: Long = System.currentTimeMillis()
) {
    /** A proxy is configured and nothing substituted the relay's key: worth knowing, not interception. */
    val proxyOnly: Boolean get() = !intercepted && proxyHost != null

    /** CRITICAL for interception, HIGH for an unpinned untrusted relay, MEDIUM for a proxy, null when clear. */
    val severity: Severity?
        get() = when {
            intercepted -> Severity.CRITICAL
            untrustedUnpinned -> Severity.HIGH
            proxyHost != null -> Severity.MEDIUM
            else -> null
        }
}

/**
 * A TLS interception canary.
 *
 * Every four hours, and whenever the default network changes, it opens a raw
 * TLS connection to the relay the comms module is registered with and looks at
 * the leaf certificate the server presents, without validating it: an
 * intercepting proxy presents its own certificate, signed by a root it had
 * installed on the phone, and the platform's trust manager would accept it
 * without a word. The SubjectPublicKeyInfo hash of the first publicly trusted
 * leaf seen is stored as the pin; any later connection whose leaf key differs is
 * reported as CRITICAL. (A relay that legitimately rotates its key trips it once;
 * the owner re-pins from the Certificates page.) A first contact whose chain no
 * trust store accepts is HIGH and is not pinned automatically — on a relay served
 * with a public certificate it is someone else's key — but the owner of a
 * self-hosted relay with a private CA can pin it by hand.
 *
 * The same run also checks for proxies, which are how interception is wired in
 * on a phone without root: the default proxy, every network's own HTTP proxy,
 * and the global `http_proxy` setting. The direct probe does not go through a
 * proxy, so when one is configured the relay is also probed *through* it with an
 * HTTP CONNECT tunnel, and that key is compared too. A proxy that substitutes the
 * certificate is CRITICAL. One whose path shows the relay's own key is MEDIUM:
 * corporate and carrier networks set proxies routinely, and calling each one
 * interception taught the owner to ignore the CRITICAL that matters. A proxy that
 * cannot be checked (a PAC script, no host, a refused tunnel) is MEDIUM and says
 * so rather than claiming the key held.
 */
object TlsCanary {

    private const val PREFS = "tls_canary"
    private const val KEY_HOST = "host"
    private const val KEY_PIN = "spki"
    private const val KEY_PINNED_AT = "pinned_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TS = "last_ts"

    private const val INTERVAL_MS = 4 * 3600_000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    /**
     * Ceiling on one probe from TCP connect to finished handshake, CONNECT headers
     * included. The per-read timeout alone does not bound the total: a proxy or
     * server trickling a byte every few seconds could hold a probe for hours, and
     * runs are sequential, so every later run would wait behind it.
     */
    private const val PROBE_DEADLINE_MS = 30_000L

    /** Closes a probe's socket when its deadline passes, wherever the probe is blocked. */
    private val deadlineWatchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tls-canary-deadline").apply { isDaemon = true }
    }

    /** Longest CONNECT response header block read before giving up on the proxy. */
    private const val MAX_CONNECT_RESPONSE = 16 * 1024
    /** A network change fires several callbacks in a row; one run per burst. */
    private const val NETWORK_DEBOUNCE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodic: Job? = null
    private var networkRun: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * One check, as a cold flow that emits a single [TlsCanaryResult] and
     * completes. Runs on the IO dispatcher. No alert is raised here; see [start]
     * for the scheduled run that does.
     */
    fun check(context: Context): Flow<TlsCanaryResult> = flow {
        emit(runOnce(context.applicationContext))
    }.flowOn(Dispatchers.IO)

    /**
     * Starts the four-hourly run plus a run on every default-network change.
     * Idempotent. Each run that finds interception goes through
     * [Registry.publishAlert] as CRITICAL (deduplicated for an hour).
     */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (periodic?.isActive != true) {
            periodic = scope.launch {
                while (isActive) {
                    runAndAlert(app)
                    delay(INTERVAL_MS)
                }
            }
        }
        if (callback == null) {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(this@TlsCanary) {
                        networkRun?.cancel()
                        networkRun = scope.launch {
                            delay(NETWORK_DEBOUNCE_MS)
                            runAndAlert(app)
                        }
                    }
                }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb); callback = cb }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        periodic?.cancel(); periodic = null
        networkRun?.cancel(); networkRun = null
        callback?.let { cb ->
            runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        callback = null
    }

    /** Forgets the pinned key; the next connection pins afresh. For a relay that rotated its certificate. */
    fun resetPin(context: Context) {
        prefs(context).edit().remove(KEY_HOST).remove(KEY_PIN).remove(KEY_PINNED_AT).apply()
    }

    /** The pinned SPKI hash and the host it belongs to, or null when nothing is pinned yet. */
    fun pinned(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return null
        val pin = p.getString(KEY_PIN, null) ?: return null
        return host to pin
    }

    /** The outcome of the last run this install made, or null. */
    fun lastResult(context: Context): Pair<Long, String>? {
        val p = prefs(context)
        val ts = p.getLong(KEY_LAST_TS, 0L)
        val text = p.getString(KEY_LAST_RESULT, null) ?: return null
        return ts to text
    }

    private suspend fun runAndAlert(app: Context) {
        check(app).collect { r ->
            if (r.intercepted) {
                publishInterception(r)
                return@collect
            }
            if (r.untrustedUnpinned) publishUntrusted(r)
            if (r.proxyHost != null) publishProxyNotice(r)
        }
    }

    private fun publishInterception(r: TlsCanaryResult) {
        val detail = buildString {
            append(r.method)
            r.proxyHost?.let { append(". Proxy: ").append(it) }
            r.relayHost?.let { append(". Relay: ").append(it) }
            val issuer = if (r.viaProxySpki != null && r.viaProxySpki != (r.pinnedSpki ?: r.observedSpki)) r.viaProxyIssuer else r.observedIssuer
            issuer?.let { append(". Certificate issued by: ").append(it) }
            if (r.chainTrusted == true && r.observedSpki != r.pinnedSpki && r.pinnedSpki != null) {
                append(". The substitute certificate is trusted by this phone, which means a root was installed to make it so.")
            }
            append(" Treat every connection from this phone as read by a third party until the cause is found.")
        }
        Registry.publishAlert(
            Alert(
                id = "tls_canary@${r.ts}",
                ts = r.ts,
                severity = Severity.CRITICAL,
                title = "TLS interception indicator",
                detail = detail,
                kind = EventKind.PLATFORM,
                dedupeKey = "tls_canary"
            ),
            dedupeWindowMs = 3600_000L
        )
    }

    /** First contact with a relay whose chain nothing on the phone trusts: HIGH, at most once a day. */
    private fun publishUntrusted(r: TlsCanaryResult) {
        val detail = buildString {
            append("The relay ")
            r.relayHost?.let { append(it).append(' ') }
            append("presented a certificate that no trust store on this phone accepts")
            r.observedIssuer?.let { append(" (issued by ").append(it).append(')') }
            append(". It has not been pinned. A relay with a public certificate never looks like this, so someone ")
            append("may be answering in its place. If this is your own relay with a private certificate authority, ")
            append("pin it on the Certificates page; after that only a change of key is reported.")
        }
        Registry.publishAlert(
            Alert(
                id = "tls_canary_untrusted@${r.ts}",
                ts = r.ts,
                severity = Severity.HIGH,
                title = "Relay certificate is not publicly trusted",
                detail = detail,
                kind = EventKind.PLATFORM,
                dedupeKey = "tls_canary_untrusted@${r.relayHost}"
            ),
            dedupeWindowMs = 24 * 3600_000L
        )
    }

    /** A proxy that did not substitute the relay's key, or could not be checked: MEDIUM, at most once a day. */
    private fun publishProxyNotice(r: TlsCanaryResult) {
        val detail = buildString {
            append(r.proxyMethod ?: "A proxy is configured")
            r.proxyHost?.let { append(": ").append(it) }
            append(". Web traffic is routed through it. ")
            if (r.proxyVerified == true) {
                append("Through the proxy the relay presented the same certificate key as directly, so encrypted ")
                append("connections to it are not being read there. ")
            } else {
                append(r.proxyNote ?: "Could not verify through the proxy.")
                append(" Whether it reads encrypted traffic is unknown. ")
            }
            append("A proxy can only read HTTPS with a root certificate installed on this phone; check the trust store audit. ")
            append("If you did not set this up, or a work profile did not, remove it in the network's settings.")
        }
        Registry.publishAlert(
            Alert(
                id = "tls_canary_proxy@${r.ts}",
                ts = r.ts,
                severity = Severity.MEDIUM,
                title = if (r.proxyVerified == true) "Proxy configured" else "Proxy configured — could not verify through it",
                detail = detail,
                kind = EventKind.PLATFORM,
                dedupeKey = "tls_canary_proxy@${r.proxyHost}"
            ),
            dedupeWindowMs = 24 * 3600_000L
        )
    }

    // ── One run ────────────────────────────────────────────────────────────

    /** What one probe of the relay saw, reduced to what the verdict needs. */
    internal sealed class Probe {
        data class Seen(val spki: String, val trusted: Boolean, val issuer: String?) : Probe()
        data class Failed(val error: String) : Probe()
    }

    /** The probe through the configured proxy, if there is one. */
    internal sealed class ProxyProbe {
        object NoProxy : ProxyProbe()
        /** A proxy is configured but cannot be tunnelled through (PAC, no host or port). */
        data class Unverifiable(val reason: String) : ProxyProbe()
        data class Done(val probe: Probe) : ProxyProbe()
    }

    /** The verdict of one run, independent of sockets and storage. */
    internal data class Assessment(
        val intercepted: Boolean,
        val untrustedUnpinned: Boolean,
        /** The key to pin now, on a trusted first contact. */
        val pinNow: String?,
        val method: String?,
        val proxyVerified: Boolean?,
        val proxyNote: String?
    )

    /**
     * Judges a run. [pinnedSpki] is the pin for this relay host, or null; [direct]
     * the probe straight to the relay; [proxy] the probe through the configured
     * proxy.
     *
     * The through-proxy key is compared with the pin, or on first contact with what
     * the relay presents directly; a difference is interception whatever the trust
     * store says. Only a through-proxy key that matches counts as verified.
     */
    internal fun assess(pinnedSpki: String?, direct: Probe, proxy: ProxyProbe): Assessment {
        val seen = direct as? Probe.Seen
        val pinNow = if (pinnedSpki == null && seen != null && seen.trusted) seen.spki else null
        val untrustedUnpinned = pinnedSpki == null && seen != null && !seen.trusted
        val directMismatch = pinnedSpki != null && seen != null && seen.spki != pinnedSpki

        val reference = pinnedSpki ?: seen?.spki
        val via = ((proxy as? ProxyProbe.Done)?.probe) as? Probe.Seen
        val viaMismatch = via != null && reference != null && via.spki != reference

        val proxyNote = when (proxy) {
            ProxyProbe.NoProxy -> null
            is ProxyProbe.Unverifiable -> "Could not verify through the proxy: ${proxy.reason}."
            is ProxyProbe.Done -> when (val p = proxy.probe) {
                is Probe.Failed -> "Could not verify through the proxy: ${p.error}."
                is Probe.Seen -> when {
                    reference == null -> "Could not verify through the proxy: the relay could not be reached directly " +
                        "and no key is pinned to compare with."
                    viaMismatch -> "Proxy substitutes the relay certificate."
                    else -> "The relay's certificate key is unchanged through the proxy."
                }
            }
        }
        val proxyVerified = when (proxy) {
            ProxyProbe.NoProxy -> null
            else -> via != null && reference != null && !viaMismatch
        }

        val method = when {
            viaMismatch && directMismatch ->
                "Relay certificate key changed (pin mismatch), both directly and through the proxy"
            viaMismatch -> "Proxy substitutes the relay certificate"
            directMismatch && seen!!.trusted ->
                "Relay certificate key changed (pin mismatch); the new chain is trusted by this phone"
            directMismatch -> "Relay certificate key changed (pin mismatch); the new chain is not trusted by the system store"
            untrustedUnpinned ->
                "Relay certificate is not publicly trusted; not pinned. If this is your own relay with a private " +
                    "certificate authority, pin it below."
            else -> null
        }
        return Assessment(
            intercepted = directMismatch || viaMismatch,
            untrustedUnpinned = untrustedUnpinned,
            pinNow = pinNow,
            method = method,
            proxyVerified = proxyVerified,
            proxyNote = proxyNote
        )
    }

    private fun runOnce(app: Context): TlsCanaryResult {
        val proxies = proxyFindings(app)
        val proxy = proxies.firstOrNull()

        val relayUrl = CommsConfig(app).relayUrl
        val uri = relayUrl?.let { runCatching { URI(it) }.getOrNull() }
        val host = uri?.host
        if (host.isNullOrBlank()) {
            // No relay to probe: the proxy is still worth reporting, unverified.
            return remember(app, TlsCanaryResult(
                intercepted = false,
                method = null,
                proxyHost = proxy?.display,
                proxyMethod = proxy?.method,
                proxyVerified = proxy?.let { false },
                proxyNote = proxy?.let { "Could not verify through the proxy: no relay is configured to test against." },
                error = "No relay configured; certificate pin not checked"
            ))
        }
        val port = uri.port.takeIf { it > 0 } ?: 443

        val direct = runCatching { probe(host, port) }
        val directProbe: Probe = direct.fold(
            onSuccess = { Probe.Seen(it.spki, it.trusted, it.issuer) },
            onFailure = { Probe.Failed("${it.javaClass.simpleName}: ${it.message}") }
        )
        val proxyProbe: ProxyProbe = when {
            proxy == null -> ProxyProbe.NoProxy
            proxy.pac -> ProxyProbe.Unverifiable("a PAC script chooses the proxy for each address and was not evaluated")
            proxy.host == null || proxy.port <= 0 -> ProxyProbe.Unverifiable("it has no host and port to connect to")
            else -> ProxyProbe.Done(
                runCatching { probeViaProxy(proxy.host, proxy.port, host, port) }.fold(
                    onSuccess = { Probe.Seen(it.spki, it.trusted, it.issuer) },
                    onFailure = { Probe.Failed("${it.javaClass.simpleName}: ${it.message}") }
                )
            )
        }

        val p = prefs(app)
        val pinnedSpki = p.getString(KEY_PIN, null)?.takeIf { p.getString(KEY_HOST, null) == host }
        val verdict = assess(pinnedSpki, directProbe, proxyProbe)
        verdict.pinNow?.let { pin(app, host, it) }

        val observed = direct.getOrNull()
        val via = ((proxyProbe as? ProxyProbe.Done)?.probe) as? Probe.Seen
        return remember(app, TlsCanaryResult(
            intercepted = verdict.intercepted,
            method = verdict.method,
            proxyHost = proxy?.display,
            proxyMethod = proxy?.method,
            relayHost = host,
            pinnedSpki = verdict.pinNow ?: pinnedSpki,
            observedSpki = observed?.spki,
            observedIssuer = observed?.issuer,
            chainTrusted = observed?.trusted,
            pinnedNow = verdict.pinNow != null,
            error = (directProbe as? Probe.Failed)?.let { "Connection failed: ${it.error}" },
            untrustedUnpinned = verdict.untrustedUnpinned,
            proxyVerified = verdict.proxyVerified,
            proxyNote = verdict.proxyNote,
            viaProxySpki = via?.spki,
            viaProxyIssuer = via?.issuer
        ))
    }

    private fun pin(app: Context, host: String, spki: String) {
        prefs(app).edit().putString(KEY_HOST, host).putString(KEY_PIN, spki)
            .putLong(KEY_PINNED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Pins [spki] for [host] at the owner's request: a self-hosted relay whose
     * certificate comes from a private CA, reported by a run as [TlsCanaryResult.untrustedUnpinned].
     * From then on only a different key is reported.
     */
    fun pinManually(context: Context, host: String, spki: String) {
        pin(context.applicationContext, host, spki)
    }

    private fun remember(app: Context, r: TlsCanaryResult): TlsCanaryResult {
        val text = when {
            r.intercepted -> "INTERCEPTED: ${r.method}"
            r.untrustedUnpinned -> "NOT PUBLICLY TRUSTED: ${r.relayHost} (not pinned)"
            r.error != null && r.observedSpki == null && r.proxyHost != null ->
                "${r.error}; proxy configured (${r.proxyHost}), ${r.proxyNote ?: "not verified"}"
            r.error != null && r.observedSpki == null -> r.error
            r.proxyOnly && r.proxyVerified == true -> "Proxy configured (${r.proxyHost}); relay key unchanged through it"
            r.proxyOnly -> "Proxy configured (${r.proxyHost}); ${r.proxyNote ?: "not verified"}"
            r.pinnedNow -> "Pinned ${r.relayHost}"
            else -> "Clear (${r.relayHost})"
        }
        prefs(app).edit().putString(KEY_LAST_RESULT, text).putLong(KEY_LAST_TS, r.ts).apply()
        return r
    }

    private class Observed(val spki: String, val trusted: Boolean, val issuer: String?)

    /** A TLS context whose trust manager records the server's chain and accepts anything. */
    private class Recording {
        var seen: Array<X509Certificate>? = null
        val context: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<X509TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { seen = chain }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }
    }

    /**
     * Opens TLS to [host]:[port] directly with a trust manager that records the
     * server's chain and accepts anything, then asks the platform's own trust
     * manager whether it would have accepted that chain. Nothing is sent after the
     * handshake; the socket is closed at once.
     */
    private fun probe(host: String, port: Int): Observed {
        val recording = Recording()
        val socket = recording.context.socketFactory.createSocket() as SSLSocket
        val deadlineAt = System.currentTimeMillis() + PROBE_DEADLINE_MS
        return withDeadline(socket, deadlineAt) {
            socket.connect(InetSocketAddress(host, port), remaining(deadlineAt))
            socket.soTimeout = remaining(deadlineAt)
            handshake(socket, host, recording)
        }
    }

    /**
     * Runs [block] against [socket] under the overall [deadlineAt]: a watchdog closes
     * the socket when it passes, which unblocks a connect, read or handshake that the
     * per-step timeouts would let run on, and the resulting failure is reported as
     * the deadline. The socket is closed on every path.
     */
    private fun <T> withDeadline(socket: Socket, deadlineAt: Long, block: () -> T): T {
        val delay = (deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val watchdog = deadlineWatchdog.schedule({ runCatching { socket.close() } }, delay, TimeUnit.MILLISECONDS)
        try {
            return block()
        } catch (e: IOException) {
            if (System.currentTimeMillis() >= deadlineAt) {
                throw IOException("probe exceeded its ${PROBE_DEADLINE_MS / 1000} s deadline", e)
            }
            throw e
        } finally {
            watchdog.cancel(false)
            runCatching { socket.close() }
        }
    }

    /** Milliseconds left before [deadlineAt], as a socket timeout; throws once none are left. */
    private fun remaining(deadlineAt: Long): Int {
        val left = deadlineAt - System.currentTimeMillis()
        if (left <= 0) throw IOException("probe exceeded its ${PROBE_DEADLINE_MS / 1000} s deadline")
        return minOf(left, CONNECT_TIMEOUT_MS.toLong()).toInt()
    }

    /**
     * The same probe through an HTTP proxy: a plain connection to the proxy, a
     * CONNECT tunnel to the relay, then TLS inside the tunnel. A proxy that reads
     * HTTPS terminates TLS itself and presents its own certificate here.
     */
    private fun probeViaProxy(proxyHost: String, proxyPort: Int, host: String, port: Int): Observed {
        val plain = Socket()
        val deadlineAt = System.currentTimeMillis() + PROBE_DEADLINE_MS
        // One deadline for the connect, the CONNECT exchange and the handshake.
        return withDeadline(plain, deadlineAt) {
            plain.connect(InetSocketAddress(proxyHost, proxyPort), remaining(deadlineAt))
            plain.soTimeout = remaining(deadlineAt)
            val out = plain.getOutputStream()
            out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val status = readConnectStatus(plain.getInputStream(), deadlineAt)
            if (status != 200) throw IOException("proxy answered CONNECT with HTTP $status")
            plain.soTimeout = remaining(deadlineAt)
            val recording = Recording()
            val tls = recording.context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            try {
                handshake(tls, host, recording)
            } finally {
                runCatching { tls.close() }
            }
        }
    }

    /**
     * Reads a proxy's answer to CONNECT up to the blank line that ends its headers
     * and returns the status code. Reads byte by byte so nothing past the headers —
     * the start of the tunnelled stream — is consumed. Throws on a malformed or
     * oversized answer, one cut off before the blank line, or one still incomplete
     * at [deadlineAt] by [now]: each read is bounded by the socket timeout, but a
     * proxy sending a byte just inside it would otherwise never be cut off.
     */
    internal fun readConnectStatus(
        input: InputStream,
        deadlineAt: Long = Long.MAX_VALUE,
        now: () -> Long = System::currentTimeMillis
    ): Int {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n") && !head.endsWith("\n\n")) {
            if (head.length >= MAX_CONNECT_RESPONSE) throw IOException("proxy response headers too long")
            if (now() >= deadlineAt) throw IOException("proxy response too slow")
            val b = input.read()
            if (b < 0) throw IOException("proxy closed the connection during CONNECT")
            head.append(b.toChar())
        }
        val statusLine = head.lineSequence().first().trim()
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("not an HTTP response: $statusLine")
        return parts[1].toIntOrNull() ?: throw IOException("no status code: $statusLine")
    }

    private fun handshake(socket: SSLSocket, host: String, recording: Recording): Observed {
        val params = socket.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        socket.sslParameters = params
        socket.startHandshake()
        val chain = recording.seen?.toList()?.takeIf { it.isNotEmpty() }
            ?: socket.session.peerCertificates.filterIsInstance<X509Certificate>().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("server presented no certificate")
        val leaf = chain.first()
        val trusted = runCatching {
            val tmf = TrustManagerFactory.getInstance("X509").apply { init(null as KeyStore?) }
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            // The auth type must fit the leaf's key: validators that check key
            // usage reject an ECDSA certificate presented as "RSA".
            val authType = when (leaf.publicKey.algorithm) {
                "EC" -> "ECDHE_ECDSA"
                "RSA" -> "ECDHE_RSA"
                else -> "GENERIC"
            }
            tm.checkServerTrusted(chain.toTypedArray(), authType)
            true
        }.getOrDefault(false)
        val issuer = leaf.issuerX500Principal.getName(X500Principal.RFC2253).let { CaAuditScanner.cn(it) ?: it }
        return Observed(spkiHash(leaf), trusted, issuer)
    }

    /** "sha256/<base64>" of the certificate's SubjectPublicKeyInfo, the form HPKP used. */
    fun spkiHash(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /** One configured proxy: how it is set, how it reads, and where to tunnel through it. */
    internal data class ProxyFinding(
        val method: String,
        /** "host:port", "host" or "PAC <url>", for display. */
        val display: String,
        val host: String?,
        val port: Int,
        /** Chosen per address by a PAC script; there is no single endpoint to test. */
        val pac: Boolean
    )

    /**
     * Every proxy configured on the phone, the default one first. A phone that is
     * not being intercepted has none: Android does not set one on its own.
     */
    internal fun proxyFindings(context: Context): List<ProxyFinding> {
        val out = ArrayList<ProxyFinding>(3)
        val cm = context.getSystemService(ConnectivityManager::class.java)

        fun describe(method: String, p: ProxyInfo?): ProxyFinding? {
            if (p == null) return null
            val pac = p.pacFileUrl?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            if (pac != null) return ProxyFinding(method, "PAC $pac", null, 0, pac = true)
            val host = p.host?.takeIf { it.isNotBlank() } ?: return null
            val display = if (p.port > 0) "$host:${p.port}" else host
            return ProxyFinding(method, display, host, p.port, pac = false)
        }

        runCatching { describe("Default HTTP proxy set on the active network", cm?.defaultProxy) }.getOrNull()?.let { out += it }

        runCatching {
            @Suppress("DEPRECATION")
            val networks = cm?.allNetworks ?: emptyArray()
            for (n in networks) {
                val caps = cm?.getNetworkCapabilities(n)
                val kind = when {
                    caps == null -> "network"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> "network"
                }
                val proxy = describe("HTTP proxy on the $kind network's link", cm?.getLinkProperties(n)?.httpProxy) ?: continue
                if (out.none { it.display == proxy.display }) out += proxy
            }
        }

        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY) }.getOrNull()
            ?.let { parseGlobalProxy(it) }
            ?.let { g -> if (out.none { it.display == g.display }) out += g }

        return out
    }

    /** The global `http_proxy` setting ("host:port"), or null when unset. */
    internal fun parseGlobalProxy(raw: String): ProxyFinding? {
        val value = raw.trim().takeIf { it.isNotEmpty() && it != ":0" && it != "null" } ?: return null
        val method = "Global http_proxy setting (set by ADB or a device owner)"
        val colon = value.lastIndexOf(':')
        val host = (if (colon > 0) value.substring(0, colon) else value).trim().takeIf { it.isNotEmpty() }
        val port = if (colon > 0) value.substring(colon + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0 else 0
        return ProxyFinding(method, value, host, port, pac = false)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
