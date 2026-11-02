package app.clairix.comms.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The mesh's second phone-to-phone radio: Wi-Fi Aware (Neighbor Awareness Networking),
 * on Android 13 and later on phones that have it. It reaches farther than ordinary
 * Bluetooth and moves bundles far faster, so two phones that pass each other trade
 * everything they carry in the moment they are in reach.
 *
 * Every phone publishes [MeshAwareWire.SERVICE_NAME] with a random instance id, made
 * afresh each time the mesh starts. A phone at full pace also listens for it. When one
 * finds another, the lower id starts the meeting: it sends the publisher an offer with a
 * fresh passphrase, both ask Android for a Wi-Fi Aware data path secured with it
 * (WPA3-style SAE on the link), the publisher listens on a socket and the starter
 * connects. Over that socket the two run one [MeshSession], the same exchange as over
 * Bluetooth, framed by length ([MeshAwareWire]). One meeting at a time, at most one per
 * peer per [MeshNode.PEER_COOLDOWN_MS], ended after [MEETING_MS] whatever happens.
 *
 * What it shows: that a phone nearby offers the Clairix mesh service, as the Bluetooth
 * advertisement does, with an id that names it only until the mesh restarts. What it
 * carries is sealed end to end before it gets here; the link's own encryption only
 * keeps strangers from reading the sealed bundles go by.
 *
 * All callbacks run on one thread ([handler]); sockets are read and written on their own.
 * Needs NEARBY_WIFI_DEVICES (flagged never-for-location); without it, or without Wi-Fi
 * Aware, it does nothing and the Bluetooth mesh carries on alone.
 */
@SuppressLint("MissingPermission")
class MeshAware(
    context: Context,
    private val store: MeshStore,
    private val ownTags: () -> Set<String>,
    private val onDeliver: (ByteArray) -> Unit,
    private val status: MutableStateFlow<MeshStatus>
) {
    companion object {
        private const val TAG = "MeshAware"
        const val MEETING_MS = 30_000L
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val ACCEPT_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** Whether this phone can run it at all: the hardware, Android 13 and the permission. */
        fun usable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private val app = context.applicationContext
    private val aware: WifiAwareManager? = app.getSystemService(WifiAwareManager::class.java)
    private val connectivity: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("mesh-aware").apply { start() }
    private val handler = Handler(thread.looper)

    private var running = false
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var wantSubscribe = false
    private var ownId = MeshAwareWire.newId()
    private var messageId = 0
    private val lastMet = HashMap<String, Long>()
    private var meeting: Meeting? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { handler.post { if (aware?.isAvailable == true) attach() else detach() } }
    }

    // ── Start and stop ────────────────────────────────────────────────────

    fun start() = handler.post {
        if (running || aware == null || !usable(app)) return@post
        running = true
        ContextCompat.registerReceiver(
            app, stateReceiver, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        attach()
    }

    fun stop() {
        handler.post {
            if (!running) return@post
            running = false
            runCatching { app.unregisterReceiver(stateReceiver) }
            detach()
        }
        handler.post { thread.quitSafely() }
    }

    /** Listens for other phones only while the mesh runs at full pace; publishing goes on regardless, so busy phones still find this one. */
    fun setFullPace(full: Boolean) = handler.post {
        if (full == wantSubscribe) return@post
        wantSubscribe = full
        val s = session ?: return@post
        if (full) startSubscribe(s) else { subscribe?.close(); subscribe = null }
    }

    private fun attach() {
        if (!running || session != null || aware?.isAvailable != true) return
        ownId = MeshAwareWire.newId()
        aware.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                if (!running) { s.close(); return }
                session = s
                startPublish(s)
                if (wantSubscribe) startSubscribe(s)
                status.update { it.copy(aware = true) }
            }

            override fun onAttachFailed() { Log.i(TAG, "attach failed") }

            override fun onAwareSessionTerminated() { detach() }
        }, handler)
    }

    private fun detach() {
        meeting?.end()
        publish?.close(); publish = null
        subscribe?.close(); subscribe = null
        session?.close(); session = null
        status.update { it.copy(aware = false) }
    }

    private fun startPublish(s: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(MeshAwareWire.SERVICE_NAME)
            .setServiceSpecificInfo(ownId)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
        s.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(p: PublishDiscoverySession) { publish = p }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) { onOffer(peer, message) }
            override fun onSessionTerminated() { publish = null }
        }, handler)
    }

    private fun startSubscribe(s: WifiAwareSession) {
        if (subscribe != null) return
        val config = SubscribeConfig.Builder()
            .setServiceName(MeshAwareWire.SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        s.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                if (wantSubscribe) subscribe = sub else sub.close()
            }
            override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, filter: List<ByteArray>?) { onFound(peer, info) }
            override fun onSessionTerminated() { subscribe = null }
        }, handler)
    }

    // ── Starting a meeting ────────────────────────────────────────────────

    private fun coolingDown(peerId: ByteArray, now: Long): Boolean =
        now - (lastMet[MeshProtocol.hex(peerId)] ?: 0L) < MeshNode.PEER_COOLDOWN_MS

    /** Found a publisher: if this phone's id is the lower, offer it a meeting and ask for the data path. */
    private fun onFound(peer: PeerHandle, info: ByteArray?) {
        val sub = subscribe ?: return
        val peerId = info?.takeIf { it.size == MeshAwareWire.ID_LEN } ?: return
        val now = System.currentTimeMillis()
        if (meeting != null || coolingDown(peerId, now) || !MeshAwareWire.initiates(ownId, peerId)) return
        lastMet[MeshProtocol.hex(peerId)] = now
        val pass = MeshAwareWire.newPassphrase()
        sub.sendMessage(peer, ++messageId, MeshAwareWire.offer(ownId, pass))
        val spec = WifiAwareNetworkSpecifier.Builder(sub, peer).setPskPassphrase(pass).build()
        Meeting(peerId, initiator = true).also { meeting = it }.request(spec, server = null)
    }

    /** An offer reached this phone's publication: listen on a socket and answer the data path. */
    private fun onOffer(peer: PeerHandle, message: ByteArray) {
        val pub = publish ?: return
        val offer = MeshAwareWire.parseOffer(message) ?: return
        // An offer only ever comes from the lower id; anything else is not a Clairix phone following the rules.
        if (!MeshAwareWire.initiates(offer.id, ownId)) return
        val now = System.currentTimeMillis()
        if (meeting != null) return
        lastMet[MeshProtocol.hex(offer.id)] = now
        val server = try { ServerSocket(0) } catch (e: IOException) { Log.i(TAG, "no socket: ${e.message}"); return }
        val spec = WifiAwareNetworkSpecifier.Builder(pub, peer)
            .setPskPassphrase(offer.passphrase)
            .setPort(server.localPort)
            .build()
        Meeting(offer.id, initiator = false).also { meeting = it }.request(spec, server)
    }

    // ── One meeting ───────────────────────────────────────────────────────

    /** One data path, its socket and its [MeshSession]. Its fields are touched on [handler] only, but for the socket. */
    private inner class Meeting(val peerId: ByteArray, val initiator: Boolean) {
        private var callback: ConnectivityManager.NetworkCallback? = null
        private var server: ServerSocket? = null
        @Volatile private var socket: Socket? = null
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-aware-w").apply { isDaemon = true } }
        private var out: BufferedOutputStream? = null
        private var started = false
        private var ended = false
        private val deadline = Runnable { end() }

        val session = MeshSession(
            store, ownTags,
            deliver = { b -> status.update { it.copy(receivedForYou = it.receivedForYou + 1) }; onDeliver(b.data) },
            send = { frame -> val o = out; runCatching { writer.execute { write(o, frame) } } },
            now = { System.currentTimeMillis() },
            initiator = initiator
        )

        fun request(spec: WifiAwareNetworkSpecifier, server: ServerSocket?) {
            this.server = server
            handler.postDelayed(deadline, MEETING_MS)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (server != null) handler.post { accept(server) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (server != null) return
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val addr = info.peerIpv6Addr ?: return
                    handler.post { connect(network, InetSocketAddress(addr, info.port)) }
                }

                override fun onUnavailable() { handler.post { end() } }
                override fun onLost(network: Network) { handler.post { end() } }
            }
            callback = cb
            val ok = runCatching { connectivity?.requestNetwork(request, cb, handler, NETWORK_TIMEOUT_MS) }.isSuccess
            if (!ok || connectivity == null) end()
        }

        private fun accept(server: ServerSocket) {
            if (started || ended) return
            started = true
            Thread({
                val s = try {
                    server.soTimeout = ACCEPT_TIMEOUT_MS
                    server.accept()
                } catch (e: IOException) { Log.i(TAG, "no peer connected: ${e.message}"); null }
                handler.post { if (s == null) end() else run(s) }
            }, "mesh-aware-a").apply { isDaemon = true }.start()
        }

        private fun connect(network: Network, at: InetSocketAddress) {
            if (started || ended) return
            started = true
            Thread({
                val s = try {
                    network.socketFactory.createSocket().apply { connect(at, CONNECT_TIMEOUT_MS) }
                } catch (e: IOException) { Log.i(TAG, "could not reach the peer: ${e.message}"); null }
                handler.post { if (s == null) end() else run(s) }
            }, "mesh-aware-c").apply { isDaemon = true }.start()
        }

        /** The socket is up: read frames on a thread of their own, hand each to the session here. */
        private fun run(s: Socket) {
            if (ended) { runCatching { s.close() }; return }
            socket = s
            try {
                s.soTimeout = READ_TIMEOUT_MS
                out = BufferedOutputStream(s.getOutputStream())
            } catch (e: IOException) { Log.i(TAG, "socket setup failed: ${e.message}"); end(); return }
            session.start()
            Thread({ readAll(s) }, "mesh-aware-r").apply { isDaemon = true }.start()
        }

        /** Reads frames until the peer closes, the socket fails or the meeting ends; on its own thread. */
        private fun readAll(s: Socket) {
            val input = try {
                DataInputStream(s.getInputStream().buffered())
            } catch (e: IOException) { Log.i(TAG, "no input stream: ${e.message}"); null }
            while (input != null) {
                // The peer closing, a timeout or end() closing the socket all land here; each ends the reading.
                val frame = try { MeshAwareWire.readFrame(input) } catch (ignored: IOException) { null } ?: break
                handler.post { onFrame(frame) }
            }
            handler.post { finishIfDone(); end() }
        }

        private fun onFrame(frame: ByteArray) {
            if (ended) return
            if (session.onFrame(frame)) finishIfDone() else end()
        }

        private fun write(o: BufferedOutputStream?, frame: ByteArray) {
            if (o == null) return
            try {
                MeshAwareWire.writeFrame(o, frame)
            } catch (e: IOException) {
                Log.i(TAG, "write failed: ${e.message}")
                handler.post { end() }
            }
        }

        private var counted = false

        private fun finishIfDone() {
            if (ended || counted || !session.finished) return
            counted = true
            status.update {
                it.copy(meetings = it.meetings + 1, lastMeetingAt = System.currentTimeMillis(),
                    handedOn = it.handedOn + session.forwarded, carrying = store.size)
            }
            // The starter hangs up once its last frame is written; the other side reads the end.
            if (initiator) runCatching { writer.execute { runCatching { socket?.shutdownOutput() } } }
        }

        fun end() {
            if (ended) return
            ended = true
            handler.removeCallbacks(deadline)
            if (meeting === this) meeting = null
            val s = socket
            val srv = server
            runCatching { writer.execute { runCatching { s?.close() }; runCatching { srv?.close() } } }
            writer.shutdown()
            callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
            callback = null
        }
    }
}
