package app.clairix.analysis

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TimelineEvent
import app.clairix.TimelineLog
import app.clairix.WifiSecurity
import app.clairix.security.HistoryRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ── Data model ───────────────────────────────────────────────────────────────

enum class CameraFindMethod { LENS_GLINT, IR_ILLUMINATOR, NETWORK, BLE_SETUP_AP, WIFI_SETUP_AP, MAGNETIC }

data class CameraFinding(
    val id: String,
    val ts: Long,
    val method: CameraFindMethod,
    /** 0..1 — how strongly this evidence alone points at a camera. */
    val confidence: Float,
    /** Where to look: a position in the viewfinder, an IP address, a distance estimate. */
    val location: String,
    val detail: String,
    val lat: Double?,
    val lon: Double?,
    /**
     * An IR point of light waiting for the owner's answer: one they can see with
     * their own eyes is an ordinary LED, one they cannot is a possible illuminator.
     * Held below alert level until they say.
     */
    val needsConfirmation: Boolean = false,
    /** The photo the owner kept of it ([SweepPhotos]), as the attachment its timeline event carries. */
    val photo: String? = null
)

/** The two optical passes. Glint wants the torch on the back camera; IR wants a dark room and the front camera. */
enum class OpticalMode(val label: String, val torch: Boolean, val frontCamera: Boolean) {
    GLINT("Lens glint", torch = true, frontCamera = false),
    IR("IR illuminator", torch = false, frontCamera = true)
}

/**
 * A tracked bright spot in the viewfinder. [x], [y] and [radius] are normalised 0..1
 * in *display* orientation (rotation and front-camera mirroring already applied),
 * so the overlay just multiplies by its own size.
 */
data class GlintBlob(
    val id: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    /** How long, in camera time, this spot has held still while the scene held still. */
    val heldMs: Long,
    /**
     * Not a lens: it stayed lit with the torch off (a light), or stayed put on the
     * sensor while the phone turned (a hot pixel). Never reported.
     */
    val lightSource: Boolean,
    /** Held still and, where the torch could test it, confirmed a reflection. */
    val candidate: Boolean,
    val confidence: Float
)

/** What the OPTICAL tab shows beside the viewfinder. */
data class OpticalState(
    val mode: OpticalMode = OpticalMode.GLINT,
    val running: Boolean = false,
    val torchOn: Boolean = false,
    /** False when this camera has no torch, so the glint sweep cannot work. */
    val hasTorch: Boolean = true,
    /** The torch is briefly off while a steady spot is tested. */
    val checking: Boolean = false,
    val error: String? = null,
    /** Mean luma of the last frame, 0..255. */
    val meanLuma: Float = 0f,
    /** Frame-to-frame change in mean luma, as a fraction. Above 6 % the tracker holds. */
    val lumaChange: Float = 0f,
    /** Pixels over the threshold in the last frame, at 1/4 scale. */
    val brightPixels: Int = 0,
    /** True when so much of the frame is saturated that nothing can be told apart. */
    val washedOut: Boolean = false,
    /** IR mode only: the room is too bright for an IR dot to stand out. */
    val tooBright: Boolean = false,
    /** The last torch-off test never saw the torch go off, so it decided nothing; it is tried again. */
    val torchUnseen: Boolean = false,
    val tracks: List<GlintBlob> = emptyList(),
    val frames: Long = 0L,
    val fps: Float = 0f
)

data class NetworkScanProgress(
    val running: Boolean = false,
    val phase: String = "",
    val hostsDone: Int = 0,
    val hostsTotal: Int = 0,
    /** "192.168.1.0/24" — what was swept. */
    val subnet: String? = null,
    val ssid: String? = null,
    val liveHosts: Int = 0,
    val error: String? = null,
    val lastScanTs: Long = 0L,
    val found: Int = 0,
    /**
     * Why the last sweep proves nothing, or null when its result means something:
     * only the router answered (client isolation), the network is wider than the
     * block that was checked, the Wi-Fi gave the phone no IPv4 address, or the
     * sweep was stopped. See [NetworkSweepCoverage].
     */
    val inconclusiveReason: String? = null
)

data class WirelessState(
    val wifiScanning: Boolean = false,
    val wifiScannedTs: Long = 0L,
    val wifiApsSeen: Int = 0,
    val wifiError: String? = null,
    val bleListening: Boolean = false,
    /** Wall-clock time the current BLE listening window ends, or 0. */
    val bleUntilTs: Long = 0L,
    val bleDevicesSeen: Int = 0,
    val bleError: String? = null
)

data class MagneticState(
    val active: Boolean = false,
    val error: String? = null,
    /** Field magnitude right now, µT. */
    val fieldUt: Float = 0f,
    /** Slow baseline the room settles at, µT (Earth's field is 25–65 µT). */
    val baselineUt: Float = 0f,
    val deltaUt: Float = 0f,
    val thresholdUt: Float = 0f,
    /** Largest deviation seen in the current or last episode. */
    val peakUt: Float = 0f,
    val triggered: Boolean = false,
    /** The sensor says its own readings cannot be trusted — wave the phone in a figure 8. */
    val unreliable: Boolean = false,
    val warmingUp: Boolean = false
)

/** One line of the guided room sweep. */
data class SweepStep(val id: String, val title: String, val detail: String, val tool: String)

// ── Optical: lens glint / IR illuminator ─────────────────────────────────────

/**
 * Finds the one thing a camera lens cannot help doing: looking back at you.
 *
 * With the torch on, a lens, even a pinhole lens, is a retroreflector: the light
 * goes down the barrel, bounces off the sensor stack and comes straight back up
 * the same axis as a steady, round pinpoint. Shiny edges, screws and glass also
 * flash, but their highlights slide as the phone moves, and a light that is on by
 * itself (a standby LED, a screen) stays lit when the torch goes off. The work is
 * done by [GlintTracker]; this class copies the luma plane out of each camera frame,
 * switches the torch when the tracker asks, and turns grid cells into display
 * coordinates.
 *
 * IR mode is the same machinery with the torch off on the front camera, in a dark
 * room: a night-vision camera lights the room with 850 nm LEDs the eye cannot see,
 * and on phones whose front camera lets some infrared through, they show as a dot.
 * The same frames feed the IR self-test ([IrSelfTest]) while one is running.
 *
 * Only the Y plane is read; frames never leave this class. (A photo the owner
 * chooses to keep comes from the preview, not from here: [HiddenCameraScanner.keepPhoto].)
 */
class LensGlintAnalyzer(
    private val mode: OpticalMode,
    /** True when the preview is mirrored (front camera), so overlay x is flipped to match. */
    private val mirrored: Boolean,
    /** Switches the torch, or null in a mode that never uses it. */
    private val torch: ((Boolean) -> Unit)?,
    private val onState: (OpticalState) -> Unit,
    private val onCandidate: (GlintBlob) -> Unit
) : ImageAnalysis.Analyzer {

    private val tracker = GlintTracker(ir = mode == OpticalMode.IR, torchAvailable = false)
    private var luma = ByteArray(0)
    private var frames = 0L
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0
    private var fps = 0f
    @Volatile private var closed = false

    /**
     * Whether the bound camera has a torch ([androidx.camera.core.CameraInfo.hasFlashUnit]),
     * set once it is bound. False until then, so a camera without one never runs the
     * torch-off test and the glint mode says it cannot work.
     */
    @Volatile var torchAvailable: Boolean = false

    /** The torch as the camera reports it ([androidx.camera.core.CameraInfo.getTorchState]). */
    @Volatile var torchReportedOn: Boolean = false

    /** The camera confirmed (a capture result) that the torch went off as the test asked. */
    @Volatile private var torchConfirmedOff = false

    /** The torch switch the tracker asked for has taken effect, by the camera's own account. */
    fun torchConfirmed(on: Boolean) { torchConfirmedOff = !on }

    private val turnLock = Any()
    private var turnX = 0f
    private var turnY = 0f

    /** The gyroscope: the phone turned [dxDeg] and [dyDeg] about its x and y axes. */
    fun addTurn(dxDeg: Float, dyDeg: Float) {
        synchronized(turnLock) { turnX += dxDeg; turnY += dyDeg }
    }

    private var irTest = IrSelfTest()
    private var irTestRun = 0L

    /** Stops emitting; the executor may still be mid-frame when the use case unbinds. */
    fun close() { closed = true }

    override fun analyze(image: ImageProxy) {
        try {
            if (!closed) process(image)
        } catch (_: Throwable) {
            // A malformed frame is not worth the camera session.
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val plane = image.planes[0]
        processLuma(
            plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
            image.imageInfo.timestamp, image.imageInfo.rotationDegrees
        )
    }

    /**
     * One frame, from its Y plane on: everything [analyze] does once the camera has
     * handed over the frame. Apart so a device test can feed it frames of its own,
     * with no camera.
     */
    internal fun processLuma(buf: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long, rotation: Int) {
        val size = buf.remaining()
        if (luma.size != size) luma = ByteArray(size)
        buf.get(luma, 0, size)
        buf.rewind()
        tracker.torchAvailable = torchAvailable
        val (tx, ty) = synchronized(turnLock) { (turnX to turnY).also { turnX = 0f; turnY = 0f } }
        val r = tracker.process(
            luma, width, height, rowStride, pixelStride, timestampNs,
            torchOffConfirmed = torchConfirmedOff, turnXDeg = tx, turnYDeg = ty
        )
        r.torch?.let { on ->
            // A new switch: the camera's word on the last one no longer applies.
            torchConfirmedOff = false
            if (!closed) torch?.invoke(on)
        }
        if (mode == OpticalMode.IR) {
            val run = HiddenCameraScanner.irCheckRun()
            if (run != 0L) {
                if (run != irTestRun) { irTestRun = run; irTest = IrSelfTest() }
                if (irTest.process(luma, width, height, rowStride, pixelStride, timestampNs)) {
                    HiddenCameraScanner.irCheckPassed(run)
                }
            }
        }

        frames++
        val now = SystemClock.elapsedRealtime()
        fpsWindowFrames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        else if (now - fpsWindowStart >= 1_000L) {
            fps = fpsWindowFrames * 1000f / (now - fpsWindowStart)
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
        if (r.gridWidth == 0) return
        val blobs = r.spots.map { blob(it, r, rotation) }
        if (!closed) for (s in r.report) onCandidate(blob(s, r, rotation))
        if (!closed) onState(
            OpticalState(
                mode = mode, running = true,
                torchOn = mode.torch && torchAvailable && torchReportedOn,
                hasTorch = !mode.torch || torchAvailable,
                checking = r.checking, error = null,
                meanLuma = r.meanLuma, lumaChange = r.lumaChange, brightPixels = r.brightCells,
                washedOut = r.washedOut, tooBright = r.tooBright, torchUnseen = r.torchUnseen,
                tracks = blobs, frames = frames, fps = fps
            )
        )
    }

    private fun blob(s: GlintTracker.Spot, r: GlintTracker.Result, rotation: Int): GlintBlob {
        val (vx, vy) = toView((s.gx + 0.5f) / r.gridWidth, (s.gy + 0.5f) / r.gridHeight, rotation)
        return GlintBlob(
            id = s.id, x = vx, y = vy, radius = s.radius / max(r.gridWidth, r.gridHeight),
            heldMs = s.heldMs,
            // A light, or a spot stuck to the sensor: shown grey either way, never reported.
            lightSource = s.kind == GlintTracker.Kind.LIGHT_SOURCE || s.kind == GlintTracker.Kind.FIXED,
            candidate = s.candidate, confidence = s.confidence
        )
    }

    /**
     * Buffer coordinates to display coordinates: the sensor is landscape, the screen
     * is portrait, and the front preview is mirrored.
     */
    private fun toView(nx: Float, ny: Float, rotation: Int): Pair<Float, Float> {
        var x: Float
        var y: Float
        when (rotation) {
            90 -> { x = 1f - ny; y = nx }
            180 -> { x = 1f - nx; y = 1f - ny }
            270 -> { x = ny; y = 1f - nx }
            else -> { x = nx; y = ny }
        }
        if (mirrored) x = 1f - x
        return x to y
    }
}

// ── The scanner ──────────────────────────────────────────────────────────────

/**
 * Every way a phone with no root and no special hardware can find a hidden camera,
 * and the one honest note about the way it cannot ([ANALOG_RF_NOTE]).
 *
 * Findings from all six methods land on [findings], newest first, and anything at
 * or above [ALERT_CONFIDENCE] also goes through [Registry.publishAlert] so it reaches
 * the timeline, and the notification shade when the owner is not on the sweep
 * screen to see it ([Alert.silent], [sweepScreenResumed]). A magnetic anomaly,
 * which never reaches that confidence, goes on the timeline when its episode ends.
 * Each method keeps its own live state flow for the tab that drives it.
 *
 * The findings, the checklist, when a method last ran, the last network sweep's
 * result and the infrared self-test are kept in an encrypted [Store]
 * ([CameraSweepRecord]), put back by [restore] and emptied by Clear and by
 * "Clear all data" ([forget]).
 */
object HiddenCameraScanner {

    private const val FINDINGS_CAP = 200
    private const val FINDINGS_PER_METHOD = 50
    private const val ALERT_CONFIDENCE = 0.7f
    /** An IR point of light stays below alert level until the owner says they cannot see it. */
    private const val IR_UNCONFIRMED_CAP = 0.65f
    /** A magnetic peak this far from the baseline is as strong as a magnetic hint gets. */
    private const val FULL_CONFIDENCE_UT = 200f
    private const val STORE_FILE = "camera_sweep.json"
    /** Writes of the sweep record are batched to at most one in this long. */
    private const val PERSIST_DELAY_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _findings = MutableStateFlow<List<CameraFinding>>(emptyList())
    val findings: StateFlow<List<CameraFinding>> = _findings.asStateFlow()

    private val _lastRunTs = MutableStateFlow(0L)
    /** When any sweep method last ran, or 0 if none ever has: the hub says "Not swept yet" until one does. */
    val lastRunTs: StateFlow<Long> = _lastRunTs.asStateFlow()

    private fun markRun() {
        _lastRunTs.value = System.currentTimeMillis()
        persistSoon()
    }

    // ── Kept on disk ─────────────────────────────────────────────────────────

    private val persistLock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var store: Store? = null
    /** The record on disk has been read (or cleared) in this process; writes may go ahead. */
    @Volatile private var restored = false
    private val restoreStarted = AtomicBoolean(false)
    private val persistQueued = AtomicBoolean(false)
    /** Bumped by [forget], so a restore already reading the old record puts nothing back. */
    @Volatile private var generation = 0
    /** The last network sweep as it finished: what is kept, rather than a sweep half run. */
    @Volatile private var networkResult = NetworkScanProgress()

    private fun storeFor(app: Context): Store = synchronized(persistLock) {
        store ?: Store(app, STORE_FILE).also { store = it }
    }

    /**
     * Opens the sweep's encrypted store and puts back what was found before this
     * process started, once, in the background. Anything found meanwhile stays (it
     * is newer). Safe from any thread and as often as wanted: the hub and the sweep
     * screen call it as they appear, and every method calls it as it starts.
     */
    fun restore(context: Context) {
        val app = context.applicationContext ?: return
        if (appContext == null) appContext = app
        if (!restoreStarted.compareAndSet(false, true)) return
        val gen = generation
        scope.launch { runCatching { restoreNow(app, gen) }.onFailure { restored = true } }
    }

    private fun restoreNow(app: Context, gen: Int) {
        val s = storeFor(app)
        val saved = runCatching { CameraSweepRecord.fromJson(s.load { JSONObject() }) }.getOrDefault(CameraSweepRecord())
        val cutoff = runCatching { HistoryRetention.cutoff(app) }.getOrNull()
        synchronized(persistLock) {
            if (gen == generation) {
                _findings.update { CameraSweepRecord.merge(it, saved.findings, cutoff, FINDINGS_CAP) }
                _sweepDone.update { it + saved.done }
                _lastRunTs.update { max(it, saved.lastRunTs) }
                if (networkResult.lastScanTs == 0L) networkResult = saved.network
                _network.update { if (it.lastScanTs == 0L && !it.running) saved.network else it }
                _irCheck.update { IrCheck(max(it.passedTs, saved.ir.passedTs), max(it.failedTs, saved.ir.failedTs)) }
            }
            restored = true
        }
        // Writes back the merge, without anything the history retention has since cut.
        persistSoon()
    }

    /** Writes the record soon, batching a burst of findings into one write. */
    private fun persistSoon() {
        if (!restored || store == null) return
        if (!persistQueued.compareAndSet(false, true)) return
        scope.launch {
            delay(PERSIST_DELAY_MS)
            persistQueued.set(false)
            writeNow()
        }
    }

    /** Writes the record now, in the background: after Clear, so what was cleared is gone from disk too. */
    private fun persistNow() {
        if (!restored || store == null) return
        scope.launch { writeNow() }
    }

    private fun writeNow() {
        val s = store ?: return
        synchronized(persistLock) {
            if (!restored) return
            val cutoff = appContext?.let { runCatching { HistoryRetention.cutoff(it) }.getOrNull() }
            val record = CameraSweepRecord(
                findings = _findings.value.filter { cutoff == null || it.ts >= cutoff },
                done = _sweepDone.value,
                lastRunTs = _lastRunTs.value,
                network = networkResult,
                ir = _irCheck.value
            )
            runCatching { s.replace(record.toJson()) }
        }
    }

    /**
     * The history retention ([HistoryRetention.apply]): findings from before [cutoff]
     * go, in memory and on disk, also in a process that never opened the sweep (the
     * daily alarm's), so a finding ages out on a phone whose owner has not opened
     * Clairix since. Disk I/O; call off the main thread.
     */
    fun pruneOlderThan(context: Context, cutoff: Long) {
        val app = context.applicationContext ?: return
        if (appContext == null) appContext = app
        synchronized(persistLock) {
            _findings.update { list -> if (list.any { it.ts < cutoff }) list.filter { it.ts >= cutoff } else list }
            if (!restored) {
                // Not read in this process yet: cut the record on disk itself. A restore
                // that is reading it now applies the same cutoff when it merges.
                val s = storeFor(app)
                val saved = runCatching { CameraSweepRecord.fromJson(s.load { JSONObject() }) }.getOrNull() ?: return
                val kept = saved.findings.filter { it.ts >= cutoff }
                if (kept.size != saved.findings.size) runCatching { s.replace(saved.copy(findings = kept).toJson()) }
                return
            }
        }
        // Read in this process: write the record back, which drops what is past the cutoff.
        writeNow()
    }

    /**
     * The sweep's record as the evidence report needs it: what this process holds
     * and, when the sweep has not been opened in it, what is on disk too. Reads the
     * store without restoring it, so nothing is written. Disk I/O; call off the main thread.
     */
    fun reportRecord(context: Context): CameraSweepRecord {
        val app = context.applicationContext ?: return CameraSweepRecord()
        if (appContext == null) appContext = app
        val saved = if (restored) null else runCatching { CameraSweepRecord.fromJson(storeFor(app).load { JSONObject() }) }.getOrNull()
        synchronized(persistLock) {
            return CameraSweepRecord(
                findings = CameraSweepRecord.merge(_findings.value, saved?.findings.orEmpty(), null, FINDINGS_CAP),
                done = _sweepDone.value + saved?.done.orEmpty(),
                lastRunTs = max(_lastRunTs.value, saved?.lastRunTs ?: 0L),
                network = networkResult,
                ir = _irCheck.value
            )
        }
    }

    /**
     * "Clear all data": every finding, tick, time and self-test result, in memory and
     * on disk, now. Photos kept on the timeline go with their timeline events, which
     * the same action clears. Disk I/O; it runs where the scanner's own clearing does.
     */
    fun forget(context: Context) {
        val app = context.applicationContext ?: return
        if (appContext == null) appContext = app
        synchronized(persistLock) {
            generation++
            restoreStarted.set(true)
            restored = true
            _findings.value = emptyList()
            _sweepDone.value = emptySet()
            _lastRunTs.value = 0L
            networkResult = NetworkScanProgress()
            _network.update { if (it.running) it else NetworkScanProgress() }
            _irCheck.value = IrCheck()
            irConfirmed.clear()
            _irConfirmedIds.value = emptySet()
            irDismissed.clear()
            _irDismissedIds.value = emptySet()
            irSpots.clear()
            alerted.clear()
            // The owner's clear: the file goes even if this run could not read it.
            runCatching { storeFor(app).also { it.discardUnread() }.replace(CameraSweepRecord().toJson()) }
        }
    }

    private val _optical = MutableStateFlow(OpticalState())
    val optical: StateFlow<OpticalState> = _optical.asStateFlow()

    private val _network = MutableStateFlow(NetworkScanProgress())
    val network: StateFlow<NetworkScanProgress> = _network.asStateFlow()

    private val _wireless = MutableStateFlow(WirelessState())
    val wireless: StateFlow<WirelessState> = _wireless.asStateFlow()

    private val _magnetic = MutableStateFlow(MagneticState())
    val magnetic: StateFlow<MagneticState> = _magnetic.asStateFlow()

    private val _sweepDone = MutableStateFlow<Set<String>>(emptySet())
    val sweepDone: StateFlow<Set<String>> = _sweepDone.asStateFlow()

    /** Finding ids already raised as alerts, so a climbing confidence re-alerts nobody. */
    private val alerted = Collections.synchronizedSet(HashSet<String>())

    /**
     * The methods whose findings can only be made with the sweep screen up: the
     * camera is bound to the screen's lifecycle and the magnetometer stops when
     * the guided tab goes. Their alerts are always [Alert.silent].
     */
    private val ON_SCREEN_METHODS = setOf(CameraFindMethod.LENS_GLINT, CameraFindMethod.IR_ILLUMINATOR, CameraFindMethod.MAGNETIC)

    /** Whether the sweep screen is resumed; see [sweepScreenResumed]. */
    @Volatile private var screenResumed = false

    /**
     * The sweep screen says when it is resumed and when it is paused or gone. A
     * finding made while it is up (a network sweep or a wireless listen that
     * finishes while the owner is looking at it) is shown there and raises no
     * notification; one that lands after they have left the screen does.
     */
    fun sweepScreenResumed(resumed: Boolean) { screenResumed = resumed }

    /**
     * Adds or replaces (by id) a finding; alerts once when it is strong enough and
     * [mayAlert]. Each method keeps at most [FINDINGS_PER_METHOD], so a stream of one
     * kind cannot push the others off the list. A photo kept of a finding stays with
     * it as later frames refresh it.
     */
    fun report(f: CameraFinding, mayAlert: Boolean = true) {
        _findings.update { current ->
            val previous = current.firstOrNull { it.id == f.id }
            val next = if (f.photo == null && previous?.photo != null) f.copy(photo = previous.photo) else f
            val merged = listOf(next) + current.filter { it.id != f.id }
            val perMethod = HashMap<CameraFindMethod, Int>()
            merged.filter { (perMethod.merge(it.method, 1, Int::plus) ?: 0) <= FINDINGS_PER_METHOD }.take(FINDINGS_CAP)
        }
        if (mayAlert && f.confidence >= ALERT_CONFIDENCE && !f.needsConfirmation && alerted.add(f.id)) {
            Registry.publishAlert(
                Alert(
                    id = "camera|${f.id}", ts = f.ts,
                    severity = if (f.confidence >= 0.85f) Severity.HIGH else Severity.MEDIUM,
                    title = "Possible hidden camera — ${methodLabel(f.method)}",
                    detail = "${f.location}. ${f.detail}",
                    kind = EventKind.CAMERA, dedupeKey = "camera|${f.id}",
                    lat = f.lat, lon = f.lon,
                    silent = f.method in ON_SCREEN_METHODS || screenResumed
                ),
                dedupeWindowMs = 6 * 60 * 60_000L
            )
        }
        persistSoon()
    }

    /** Clears the findings of [method], or all of them, from the list and from disk. Photos kept on the timeline stay there. */
    fun clear(method: CameraFindMethod? = null) {
        if (method == null) _findings.value = emptyList()
        else _findings.update { current -> current.filter { it.method != method } }
        persistNow()
    }

    fun methodLabel(m: CameraFindMethod): String = when (m) {
        CameraFindMethod.LENS_GLINT -> "lens glint"
        CameraFindMethod.IR_ILLUMINATOR -> "IR illuminator"
        CameraFindMethod.NETWORK -> "camera on the Wi-Fi"
        CameraFindMethod.BLE_SETUP_AP -> "Bluetooth setup beacon"
        CameraFindMethod.WIFI_SETUP_AP -> "Wi-Fi setup hotspot"
        CameraFindMethod.MAGNETIC -> "magnetic anomaly"
    }

    // ── Optical ──────────────────────────────────────────────────────────────

    /** A fresh session id per camera bind, so track ids from different binds never collide. */
    @Volatile private var opticalSession = 0L

    /**
     * The optical sweep's camera session is starting. Claims the camera with
     * [PhoneHealthMonitor] first, so the sweep's own camera is not reported as another
     * app using it; [opticalError] and [opticalStopped] release the claim.
     */
    fun opticalStarting(mode: OpticalMode) {
        PhoneHealthMonitor.ownCameraStarted(CAMERA_CLAIM)
        opticalSession = System.currentTimeMillis()
        opticalRunMarked = false
        // The answers given about IR spots belong to the last session's spots.
        irDismissed.clear()
        _irDismissedIds.value = emptySet()
        irConfirmed.clear()
        _irConfirmedIds.value = emptySet()
        irSpots.clear()
        _optical.value = OpticalState(mode = mode, running = true, torchOn = mode.torch)
    }

    /** The optical sweep counts as run once its first frame has been looked at. */
    @Volatile private var opticalRunMarked = false

    fun publishOptical(state: OpticalState) {
        _optical.value = state
        if (state.running && state.frames > 0L && !opticalRunMarked) {
            opticalRunMarked = true
            markRun()
        }
    }

    fun opticalError(mode: OpticalMode, message: String?) {
        PhoneHealthMonitor.ownCameraStopped(CAMERA_CLAIM)
        cancelIrCheck()
        _optical.value = OpticalState(mode = mode, running = false, error = message ?: "Camera could not be opened")
    }

    fun opticalStopped(mode: OpticalMode) {
        PhoneHealthMonitor.ownCameraStopped(CAMERA_CLAIM)
        cancelIrCheck()
        _optical.value = OpticalState(mode = mode, running = false)
    }

    /** The name the sweep's camera claim is held under; see [PhoneHealthMonitor.ownCameraStarted]. */
    private const val CAMERA_CLAIM = "sweep"

    /** When the optical sweep last raised an alert; it raises at most one a minute. */
    @Volatile private var lastOpticalAlertAt = 0L

    /**
     * The finding a spot makes: one per place in the frame (a 10 % grid) per camera
     * session, so a spot that blinks out and back is not a new finding.
     */
    fun opticalFindingId(mode: OpticalMode, blob: GlintBlob): String {
        val px = (blob.x * 100).roundToInt().coerceIn(0, 100)
        val py = (blob.y * 100).roundToInt().coerceIn(0, 100)
        return "optical|${mode.name.lowercase(Locale.US)}|$opticalSession|${px / 10}|${py / 10}"
    }

    /** An IR spot's own confidence and holding time, before the cap that waits for the owner's answer. */
    private class IrSpot(val confidence: Float, val heldMs: Long)

    /** IR spots the owner can see with their own eyes: ordinary LEDs, not raised again this session. */
    private val irDismissed = Collections.synchronizedSet(HashSet<String>())
    /** IR spots the owner cannot see: possible illuminators, raised at their own confidence. */
    private val irConfirmed = Collections.synchronizedSet(HashSet<String>())
    private val irSpots = ConcurrentHashMap<String, IrSpot>()

    private val _irConfirmedIds = MutableStateFlow<Set<String>>(emptySet())
    /** The finding ids of IR spots the owner said they cannot see; the viewfinder draws those red. */
    val irConfirmedIds: StateFlow<Set<String>> = _irConfirmedIds.asStateFlow()

    private val _irDismissedIds = MutableStateFlow<Set<String>>(emptySet())
    /** The finding ids of IR spots the owner can see (ordinary LEDs); the viewfinder draws those grey and asks no more. */
    val irDismissedIds: StateFlow<Set<String>> = _irDismissedIds.asStateFlow()

    /** Called by the analyzer when a spot has held still long enough. */
    fun opticalCandidate(context: Context, mode: OpticalMode, blob: GlintBlob) {
        val id = opticalFindingId(mode, blob)
        if (mode == OpticalMode.IR && id in irDismissed) return
        val (lat, lon) = fix(context)
        val f = opticalFinding(mode, blob, id, lat, lon)
        val now = System.currentTimeMillis()
        val mayAlert = f.confidence < ALERT_CONFIDENCE || now - lastOpticalAlertAt >= 60_000L
        if (mayAlert && f.confidence >= ALERT_CONFIDENCE) lastOpticalAlertAt = now
        report(f, mayAlert = mayAlert)
    }

    private fun opticalFinding(mode: OpticalMode, blob: GlintBlob, id: String, lat: Double?, lon: Double?): CameraFinding {
        val px = (blob.x * 100).roundToInt().coerceIn(0, 100)
        val py = (blob.y * 100).roundToInt().coerceIn(0, 100)
        val location = "In the viewfinder, $px % from the left and $py % from the top"
        val now = System.currentTimeMillis()
        if (mode == OpticalMode.GLINT) {
            val seconds = String.format(Locale.US, "%.1f", blob.heldMs / 1000f)
            return CameraFinding(
                id = id, ts = now, method = CameraFindMethod.LENS_GLINT, confidence = blob.confidence, location = location,
                detail = "A small round pinpoint held still for $seconds s while the phone held still, and went dark when the torch switched off, so it is a reflection of the torch, not a light. A lens returns torchlight straight back along its own axis. Step closer: look for a dark pinhole behind the spot.",
                lat = lat, lon = lon
            )
        }
        irSpots[id] = IrSpot(blob.confidence, blob.heldMs)
        val confirmed = id in irConfirmed
        return CameraFinding(
            id = id, ts = now, method = CameraFindMethod.IR_ILLUMINATOR,
            confidence = if (confirmed) blob.confidence else min(blob.confidence, IR_UNCONFIRMED_CAP),
            location = location, detail = irDetail(blob.heldMs, confirmed), lat = lat, lon = lon,
            needsConfirmation = !confirmed
        )
    }

    /** An IR spot's words; [heldMs] is null for one put back from disk, whose holding time is gone. */
    private fun irDetail(heldMs: Long?, confirmed: Boolean): String {
        val held = if (heldMs == null) "held still" else "held still for ${String.format(Locale.US, "%.1f", heldMs / 1000f)} s"
        return if (confirmed)
            "A point of light $held in a dark scene, and you could not see it with your own eyes: a possible night-vision illuminator. Night-vision cameras light the room with infrared LEDs the eye cannot see, and this phone's front camera lets some of that light through. Look for a lens beside it."
        else
            "A point of light $held in a dark scene. If you can see this light with your own eyes it is an ordinary LED, not infrared. If you cannot, treat it as a possible night-vision illuminator."
    }

    /**
     * The owner cannot see the IR spot [id] with their own eyes: it is raised as a
     * possible illuminator at its own confidence, alerting if that is high enough.
     */
    fun confirmIr(id: String) {
        irConfirmed.add(id)
        _irConfirmedIds.value = irConfirmed.toSet()
        val f = _findings.value.firstOrNull { it.id == id } ?: return
        val spot = irSpots[id]
        val confidence = max(f.confidence, spot?.confidence ?: 0f)
        report(f.copy(confidence = confidence, detail = irDetail(spot?.heldMs, confirmed = true), needsConfirmation = false), mayAlert = true)
    }

    /** The owner can see the IR spot [id]: an ordinary LED. Removed, and not raised again this session. */
    fun dismissIr(id: String) {
        irDismissed.add(id)
        _irDismissedIds.value = irDismissed.toSet()
        irSpots.remove(id)
        _findings.update { current -> current.filter { it.id != id } }
        persistSoon()
    }

    // ── Optical: can this phone see infrared? ────────────────────────────────

    /** How long the self-test watches for a remote's flashing. */
    const val IR_CHECK_MS = 10_000L

    private val _irCheck = MutableStateFlow(IrCheck())
    /** What this phone's infrared self-test has shown, kept with the sweep. */
    val irCheck: StateFlow<IrCheck> = _irCheck.asStateFlow()

    private val _irCheckUntil = MutableStateFlow(0L)
    /** Wall-clock end of the self-test in progress, or 0 when none is. */
    val irCheckUntil: StateFlow<Long> = _irCheckUntil.asStateFlow()

    private val irLock = Any()
    @Volatile private var irRun = 0L
    private var irCheckJob: Job? = null

    /**
     * Starts the infrared self-test: for [IR_CHECK_MS] the IR view's frames are
     * watched for a TV remote's flashing ([IrSelfTest]). Passing records the date;
     * running out of time records a check that saw nothing.
     */
    fun startIrCheck() {
        val run = synchronized(irLock) {
            if (irRun != 0L) return
            System.currentTimeMillis().also { irRun = it }
        }
        _irCheckUntil.value = run + IR_CHECK_MS
        irCheckJob = scope.launch {
            delay(IR_CHECK_MS)
            finishIrCheck(run, passed = false)
        }
    }

    /** The self-test the analyzer should feed frames to, or 0 when none is running. */
    internal fun irCheckRun(): Long = irRun

    /** The analyzer saw a remote flashing during self-test [run]. */
    internal fun irCheckPassed(run: Long) {
        if (finishIrCheck(run, passed = true)) irCheckJob?.cancel()
    }

    private fun finishIrCheck(run: Long, passed: Boolean): Boolean {
        synchronized(irLock) {
            if (run == 0L || irRun != run) return false
            irRun = 0L
        }
        _irCheckUntil.value = 0L
        val now = System.currentTimeMillis()
        _irCheck.update { if (passed) it.copy(passedTs = now) else it.copy(failedTs = now) }
        persistNow()
        return true
    }

    /** The IR view closed mid-test: stop without recording a result either way. */
    private fun cancelIrCheck() {
        synchronized(irLock) {
            if (irRun == 0L) return
            irRun = 0L
        }
        irCheckJob?.cancel()
        _irCheckUntil.value = 0L
    }

    // ── Optical: keeping a photo ─────────────────────────────────────────────

    /**
     * "Keep a photo of this": [shot] is the viewfinder as the owner sees it, taken on
     * the main thread, and [marks] the red candidates in it. Circles them, burns in
     * the time, the place and where in the frame, seals the JPEG under files/sweep/
     * and records a camera event on the timeline with the photo attached. Returns
     * false when it could not be kept. Encryption and file I/O: call off the main thread.
     */
    fun keepPhoto(context: Context, mode: OpticalMode, marks: List<GlintBlob>, shot: Bitmap, density: Float): Boolean {
        val lead = marks.maxByOrNull { it.confidence } ?: return false
        val app = context.applicationContext ?: return false
        restore(app)
        val (fixLat, fixLon) = fix(app)
        val id = opticalFindingId(mode, lead)
        val finding = _findings.value.firstOrNull { it.id == id } ?: opticalFinding(mode, lead, id, fixLat, fixLon)
        val lat = fixLat ?: finding.lat
        val lon = fixLon ?: finding.lon
        val now = System.currentTimeMillis()
        val caption = SweepPhotos.caption(now, TimeZone.getDefault(), lat, lon, methodLabel(finding.method), finding.location)
        val jpeg = runCatching { SweepPhotos.render(shot, marks, caption, density) }.getOrNull() ?: return false
        val attachment = runCatching { SweepPhotos.save(app.filesDir, jpeg) }.getOrNull() ?: return false
        val recorded = runCatching {
            TimelineLog.record(
                app,
                TimelineEvent(
                    id = "camera_photo|$id@$now", kind = EventKind.CAMERA, ts = now,
                    title = "Photo kept: possible hidden camera, ${methodLabel(finding.method)}",
                    detail = "${finding.location}. ${finding.detail}",
                    severity = if (finding.confidence >= 0.85f) Severity.HIGH else Severity.MEDIUM,
                    lat = lat, lon = lon, attachment = attachment
                )
            )
        }.getOrDefault(false)
        if (!recorded) {
            SweepPhotos.delete(app.filesDir, listOf(attachment))
            return false
        }
        report(finding.copy(photo = attachment), mayAlert = false)
        return true
    }

    // ── Network: cameras on the local Wi-Fi ──────────────────────────────────

    private var networkJob: Job? = null

    fun startNetworkScan(context: Context) {
        if (_network.value.running) return
        val app = context.applicationContext
        restore(app)
        networkJob = scope.launch { runCatching { scanNetwork(app) } }
    }

    fun cancelNetworkScan() { networkJob?.cancel() }

    private enum class PortState { OPEN, CLOSED, FILTERED }

    /** The ports a first pass hits on every address: the camera protocols plus the two every web UI uses. */
    private val STAGE1_PORTS = intArrayOf(554, 80, 8080, 37777, 34567)
    /** Hosts that answered the first pass are then asked about the rest. */
    private val STAGE2_PORTS = intArrayOf(8554, 10554, 81, 88, 8000, 8081, 8899, 9000, 1935)
    private val HTTP_PORTS = setOf(80, 81, 88, 8000, 8080, 8081, 8899, 9000)

    internal class HttpSignature(val regex: Regex, val label: String, val confidence: Float)

    /** Camera and recorder fingerprints in an HTTP response: titles, Server headers, auth realms, login pages. */
    internal val HTTP_SIGNATURES = listOf(
        HttpSignature(Regex("hikvision|hik-connect|/doc/page/login\\.asp|ds-2cd|ds-7"), "Hikvision camera or recorder", 0.92f),
        HttpSignature(Regex("dahua|dh_web|webplugin\\.exe|ipc-hdw|ipc-hfw|imou"), "Dahua / Imou camera", 0.92f),
        HttpSignature(Regex("reolink"), "Reolink camera", 0.92f),
        HttpSignature(Regex("amcrest"), "Amcrest camera", 0.92f),
        HttpSignature(Regex("foscam|ipcam client"), "Foscam camera", 0.92f),
        HttpSignature(Regex("tapo|tp-link ipc|tplink.*camera"), "TP-Link Tapo camera", 0.9f),
        HttpSignature(Regex("ezviz"), "EZVIZ camera", 0.92f),
        HttpSignature(Regex("axis communications|axis.*network camera|/axis-cgi/"), "Axis network camera", 0.92f),
        HttpSignature(Regex("vivotek|mobotix|arecont|geovision|hanwha|wisenet|uniview|tiandy|avigilon|bosch.*camera|panasonic.*network camera|sony.*network camera"), "Professional IP camera", 0.9f),
        HttpSignature(Regex("ubnt|unifi protect|aircam|unifi video"), "Ubiquiti camera", 0.85f),
        // Whole words only: "blink" alone is also a CSS animation name on half the routers' login pages.
        HttpSignature(Regex("\\b(wyze|yi home|yi_home|kami|wansview|sricam|tenvis|zmodo|lorex|swann|annke|night owl|eufy|arlo)\\b|\\bblink (sync|camera|mini|outdoor|indoor)\\b"), "Consumer security camera", 0.88f),
        HttpSignature(Regex("netwave|uc-httpd|xmeye|sofia|v380|yoosee|icsee|camhi|hichip|cloudcam|p2p.*camera"), "Budget IP camera firmware (Netwave / XMeye class)", 0.85f),
        // GoAhead and Boa run on many routers, modems and printers as well as cameras: a hint, not a finding.
        HttpSignature(Regex("goahead-webs|boa/0\\.9"), "Embedded web server common on budget cameras (also on routers and printers)", 0.4f),
        HttpSignature(Regex("mjpg-streamer|mjpeg-streamer|motion-jpeg|videostream\\.cgi|/snapshot\\.cgi|/video\\.cgi|/cgi-bin/hi3510|hi3516|hi3518"), "Camera video stream endpoint", 0.85f),
        HttpSignature(Regex("ip ?camera|ipcam|network camera|web ?camera|webcam|surveillance|cctv"), "Describes itself as a camera", 0.8f),
        HttpSignature(Regex("onvif"), "ONVIF camera interface", 0.8f),
        HttpSignature(Regex("blue iris|zoneminder|shinobi|frigate|motioneye|synology surveillance|agent dvr|ispy"), "Video surveillance server", 0.75f),
        HttpSignature(Regex("\\bnvr\\b|\\bdvr\\b|\\bxvr\\b|video recorder"), "Video recorder (NVR/DVR)", 0.7f)
    )

    private val MDNS_TYPES = listOf("_rtsp._tcp.", "_axis-video._tcp.", "_psia._tcp.", "_onvif._tcp.", "_hap._tcp.", "_http._tcp.")

    private class HostHit(val ip: String) {
        val openPorts: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        @Volatile var rtsp: String? = null
        @Volatile var rtspPort: Int = 0
        @Volatile var http: HttpSignature? = null
        @Volatile var httpServer: String? = null
        @Volatile var httpPort: Int = 0
        @Volatile var onvif: String? = null
        val mdns = Collections.synchronizedList(ArrayList<CameraEvidence.MdnsHit>())

        /** What it answered, as it stands now, for [CameraEvidence.judgeHost]. */
        fun evidence() = CameraEvidence.HostEvidence(
            ip = ip, openPorts = LinkedHashSet(openPorts), rtsp = rtsp, rtspPort = rtspPort,
            http = http, httpServer = httpServer, httpPort = httpPort, onvif = onvif, mdns = mdns.toList()
        )
    }

    /**
     * Sweeps the Wi-Fi subnet the phone is on for anything that speaks camera.
     *
     * Three independent probes run together: a TCP connect sweep of the camera ports
     * (RTSP, the Dahua and XMeye recorder ports, the web ports) with banners fetched
     * from whatever answers; an ONVIF WS-Discovery multicast probe, which most ONVIF
     * cameras answer (a setting some brands ship switched off); and mDNS browsing for
     * `_rtsp`, `_axis-video`, `_psia`, `_onvif`, HomeKit cameras (`_hap` with category
     * 17/18) and web servers whose name says camera. A host with only a web UI and no
     * camera fingerprint is not reported — that is every printer and router.
     * Everything is bound to the Wi-Fi [Network] so mobile data cannot swallow the
     * probes. A camera that talks only to its maker's cloud answers none of them
     * ([CLOUD_ONLY_NOTE]).
     *
     * When the sweep could not look ([NetworkSweepCoverage]: only the router
     * answered, a network wider than the block checked, no IPv4 address, a sweep
     * stopped early) it says so in [NetworkScanProgress.inconclusiveReason].
     */
    suspend fun scanNetwork(context: Context): List<CameraFinding> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifiNet: Network? = cm?.let { m ->
            @Suppress("DEPRECATION")
            val networks = runCatching { m.allNetworks.toList() }.getOrDefault(emptyList())
                .mapNotNull { n -> m.getNetworkCapabilities(n)?.let { n to it } }
            // The Wi-Fi itself, not a VPN running over it: a VPN reports its underlying
            // Wi-Fi transport too, and sweeping the tunnel says nothing about the room.
            NetworkSweepCoverage.wifiNetwork(
                networks,
                isWifi = { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                isVpn = { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            )?.first
        }
        val link: LinkProperties? = wifiNet?.let { cm?.getLinkProperties(it) }
        if (wifiNet == null || link == null) {
            _network.update { it.copy(running = false, error = "Not on Wi-Fi — join the venue's network first, then scan", phase = "") }
            return@withContext emptyList()
        }
        val ssid = Registry.wifiStatus.value.connection?.ssid
            ?: runCatching {
                @Suppress("DEPRECATION")
                app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
            }.getOrNull()?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address }
        if (v4 == null) {
            // On the Wi-Fi, but with no IPv4 address (an IPv6-only network, or no lease
            // yet): there is nothing to sweep, and that is the result, not "Not on Wi-Fi".
            networkResult = NetworkScanProgress(lastScanTs = System.currentTimeMillis(), ssid = ssid, inconclusiveReason = NetworkSweepCoverage.NO_IPV4)
            _network.value = networkResult
            markRun()
            return@withContext emptyList()
        }
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress

        // The /24 round our own address, even on a wider network (and the sweep says how much it checked).
        val range = NetworkSweepCoverage.range(NetworkSweepCoverage.ipToInt(v4.address.address), v4.prefixLength)
        val hosts = range.hosts.map { NetworkSweepCoverage.ipString(it) }

        _network.value = NetworkScanProgress(
            running = true, phase = "Probing ${hosts.size} addresses", hostsTotal = hosts.size,
            subnet = range.subnet, ssid = ssid, lastScanTs = _network.value.lastScanTs
        )

        val hits = ConcurrentHashMap<String, HostHit>()
        fun hit(ip: String): HostHit = hits.computeIfAbsent(ip) { HostHit(ip) }
        val (lat, lon) = fix(app)
        val out = ArrayList<CameraFinding>()
        var inconclusive: String? = null
        var completed = false
        try {
            coroutineScope {
                val onvif = async { runCatching { onvifProbe(wifiNet, 3_000L) }.getOrDefault(emptyMap()) }
                val mdns = async { runCatching { discoverMdns(app, 4_500L) }.getOrDefault(emptyList()) }

                // Stage 1: every address, the five ports that matter. A refused
                // connection means the host is up; a timeout means nobody's home.
                val alive = ConcurrentHashMap.newKeySet<String>()
                val sem = Semaphore(64)
                val done = AtomicInteger()
                hosts.map { ip ->
                    launch {
                        var up = false
                        for (port in STAGE1_PORTS) {
                            val st = sem.withPermit { probe(wifiNet, ip, port, 350) }
                            if (st != PortState.FILTERED) up = true
                            if (st == PortState.OPEN) hit(ip).openPorts.add(port)
                        }
                        if (up) alive.add(ip)
                        val d = done.incrementAndGet()
                        _network.update { it.copy(hostsDone = d, liveHosts = alive.size) }
                    }
                }.joinAll()

                // Did anything but the router answer? If not, an empty result proves nothing.
                inconclusive = NetworkSweepCoverage.inconclusiveReason(range, alive, gateway)

                // Stage 2: the live hosts get the long port list and banners.
                _network.update { it.copy(phase = "Fingerprinting ${alive.size} live hosts", hostsDone = 0, hostsTotal = alive.size, liveHosts = alive.size) }
                val done2 = AtomicInteger()
                alive.map { ip ->
                    launch {
                        for (port in STAGE2_PORTS) {
                            if (sem.withPermit { probe(wifiNet, ip, port, 350) } == PortState.OPEN) hit(ip).openPorts.add(port)
                        }
                        val h = hits[ip]
                        if (h != null) {
                            for (port in h.openPorts.filter { it in CameraEvidence.RTSP_PORTS }.sorted()) {
                                val banner = sem.withPermit { rtspBanner(wifiNet, ip, port) }
                                if (banner != null) { h.rtsp = banner; h.rtspPort = port; break }
                            }
                            for (port in h.openPorts.filter { it in HTTP_PORTS }.sorted()) {
                                val body = sem.withPermit { httpBanner(wifiNet, ip, port) } ?: continue
                                val lower = body.lowercase(Locale.US)
                                val sig = HTTP_SIGNATURES.firstOrNull { it.regex.containsMatchIn(lower) }
                                if (h.httpServer == null) h.httpServer = headerValue(body, "Server")
                                if (sig != null && (h.http == null || sig.confidence > h.http!!.confidence)) { h.http = sig; h.httpPort = port }
                            }
                        }
                        _network.update { it.copy(hostsDone = done2.incrementAndGet()) }
                    }
                }.joinAll()

                _network.update { it.copy(phase = "Waiting for ONVIF and mDNS answers") }
                for ((ip, scopes) in onvif.await()) hit(ip).onvif = scopes
                for (m in mdns.await()) hit(m.ip).mdns.add(m)
            }

            // Judge each host.
            val now = System.currentTimeMillis()
            for (h in hits.values) CameraEvidence.judgeHost(h.evidence(), gateway, ssid, now, lat, lon)?.let { out += it }
            out.sortByDescending { it.confidence }
            // Replace the previous network sweep rather than stacking stale hosts.
            _findings.update { current -> (out + current.filter { it.method != CameraFindMethod.NETWORK }).take(FINDINGS_CAP) }
            out.forEach { f -> if (f.confidence >= ALERT_CONFIDENCE) report(f) }
            _network.update { it.copy(found = out.size, error = null, inconclusiveReason = inconclusive) }
            completed = true
            out
        } finally {
            _network.update {
                it.copy(
                    running = false, phase = "", lastScanTs = System.currentTimeMillis(), hostsDone = it.hostsTotal,
                    // Cancelled or cut short: what it did not reach, it did not check.
                    inconclusiveReason = if (completed) it.inconclusiveReason else NetworkSweepCoverage.STOPPED
                )
            }
            networkResult = _network.value
            markRun()
        }
    }

    private fun probe(network: Network, ip: String, port: Int, timeoutMs: Int): PortState {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return PortState.FILTERED }
        return try {
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            PortState.OPEN
        } catch (e: ConnectException) {
            // Refused is a live host with the port closed; unreachable is an empty address.
            if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", ignoreCase = true) == true) PortState.CLOSED else PortState.FILTERED
        } catch (_: SocketTimeoutException) {
            PortState.FILTERED
        } catch (_: Exception) {
            PortState.FILTERED
        } finally {
            runCatching { s.close() }
        }
    }

    /** `OPTIONS` is the one RTSP request every server answers, logged in or not. */
    private fun rtspBanner(network: Network, ip: String, port: Int): String? {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(ip, port), 700)
            s.soTimeout = 1_500
            s.getOutputStream().apply {
                write("OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Clairix\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val buf = ByteArray(1024)
            val n = s.getInputStream().read(buf)
            if (n <= 0) return null
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            if (!text.startsWith("RTSP/")) return null
            val status = text.lineSequence().firstOrNull()?.trim() ?: return null
            val server = headerValue(text, "Server")
            if (server != null) "$status, server \"$server\"" else status
        } catch (_: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    /** The first 4 KB of `GET /` — enough for the Server header, the realm and the title. */
    private fun httpBanner(network: Network, ip: String, port: Int): String? {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(ip, port), 700)
            s.soTimeout = 1_500
            s.getOutputStream().apply {
                write("GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: Clairix\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            var total = 0
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (total < buf.size && SystemClock.elapsedRealtime() < deadline) {
                val n = try { input.read(buf, total, buf.size - total) } catch (_: SocketTimeoutException) { break }
                if (n < 0) break
                total += n
            }
            if (total <= 0) null else String(buf, 0, total, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    private fun headerValue(response: String, name: String): String? {
        val head = response.substringBefore("\r\n\r\n")
        return head.lineSequence().drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
    }

    /**
     * WS-Discovery: one multicast `Probe` for `NetworkVideoTransmitter` to
     * 239.255.255.250:3702. An ONVIF device with discovery switched on (most ship
     * that way; some brands ship it off) answers with a unicast `ProbeMatch`
     * carrying its scopes (name, hardware) and service address. A network that
     * isolates clients delivers neither the probe nor the answer.
     */
    private fun onvifProbe(network: Network, listenMs: Long): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val sock = DatagramSocket()
        try {
            runCatching { network.bindSocket(sock) }
            sock.soTimeout = 400
            val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
                .toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            sock.send(DatagramPacket(probe, probe.size, group, 3702))
            // A second probe a moment later catches devices that missed the first.
            val deadline = SystemClock.elapsedRealtime() + listenMs
            var resent = false
            val buf = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!resent && SystemClock.elapsedRealtime() > deadline - listenMs / 2) {
                    resent = true
                    runCatching { sock.send(DatagramPacket(probe, probe.size, group, 3702)) }
                }
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                val ip = pkt.address?.hostAddress ?: continue
                val xml = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                // Only a device that says it is a video transmitter counts; printers and NAS boxes answer ONVIF probes too.
                val desc = CameraEvidence.parseProbeMatch(xml) ?: continue
                found.putIfAbsent(ip, desc)
            }
        } finally {
            runCatching { sock.close() }
        }
        return found
    }

    /** Browses the camera-shaped mDNS service types for [listenMs]; resolves one hit at a time, as NsdManager insists. */
    private suspend fun discoverMdns(app: Context, listenMs: Long): List<CameraEvidence.MdnsHit> {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return emptyList()
        val hits = Collections.synchronizedList(ArrayList<CameraEvidence.MdnsHit>())
        val resolveLock = Mutex()
        coroutineScope {
            for (type in MDNS_TYPES) {
                launch {
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            // Cheap pre-filter: a web server is only interesting if its name says camera.
                            if (type == "_http._tcp." && !CameraEvidence.MDNS_CAMERA_NAME.containsMatchIn(serviceInfo.serviceName ?: "")) return
                            launch {
                                val resolved = resolveLock.withLock { resolveMdns(nsd, serviceInfo) } ?: return@launch
                                classifyMdns(type, resolved)?.let { hits.add(it) }
                            }
                        }
                    }
                    val started = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
                    if (!started) return@launch
                    try { delay(listenMs) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
                }
            }
        }
        return hits.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveMdns(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(2_500L) {
        suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            val ok = runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
                })
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    /** [CameraEvidence.classifyMdns] on what NsdManager resolved. */
    @Suppress("DEPRECATION")
    private fun classifyMdns(type: String, info: NsdServiceInfo): CameraEvidence.MdnsHit? {
        val host = info.host
        return CameraEvidence.classifyMdns(
            type = type,
            ip = host?.hostAddress?.substringBefore('%'),
            ipv4 = host is Inet4Address,
            name = info.serviceName ?: "",
            homeKitCategory = if (type == "_hap._tcp.") info.attributes?.get("ci")?.let { String(it, Charsets.UTF_8) } else null
        )
    }

    // ── Wireless: unprovisioned cameras announcing themselves ────────────────

    internal class ApRule(pattern: String, val label: String, val confidence: Float) {
        val regex = Regex(pattern)
    }

    /**
     * SSIDs a camera broadcasts before it has been given the venue's Wi-Fi. Matched
     * against the lower-cased whole name, anchored at the start: an ordinary network
     * that happens to contain "cam" (Camden, campus) must not fire.
     */
    internal val AP_RULES = listOf(
        ApRule("^(ipc|ipcam|ip_cam|ip-cam|ipcamera|ip_camera|netcam|hdcam|hd_cam|hd-cam|minicam|mini_cam|spycam|spy_cam|smartcam|smart_cam|wificam|wifi_cam|wifi-cam|camhi|cam_hi|camera|cam)[-_ ]?[0-9a-f]{4,}$", "Generic IP camera setup hotspot", 0.85f),
        // The short prefixes need the device's hex serial after them: "AC1750" and "xdhome" are routers and homes.
        ApRule("^(a9|q7|q8|q15|sq\\d{1,2}|x5|xd|hdq|ccq|cmdc|bpr|lookcam|hdwificam(pro)?|pccam|p2p|eye4|ac\\d{2})[-_ ]?[0-9a-f]{4,}$", "Covert mini camera (A9 / SQ / Q7 class) in setup mode", 0.95f),
        ApRule("^(a9|q7|sq\\d{1,2}|lookcam|hdwificam(pro)?)$", "Covert mini camera (A9 / SQ / Q7 class) in setup mode", 0.9f),
        ApRule("^(wyze|wyzecam|wyze_cam)([-_ ].*)?$", "Wyze camera in setup mode", 0.9f),
        ApRule("^(yi|kami)[-_ ]?(home|cam|dome|outdoor|smart|pro)[-_ ]?.*$", "YI / Kami camera", 0.9f),
        ApRule("^reolink([-_ ].*)?$", "Reolink camera", 0.9f),
        ApRule("^amcrest([-_ ].*)?$", "Amcrest camera", 0.9f),
        ApRule("^foscam([-_ ].*)?$", "Foscam camera", 0.9f),
        ApRule("^(tapo_c\\d|tapo_d\\d|tapo_cam|tp-link_ipc|tplink_ipc|tp-link_camera).*$", "TP-Link Tapo camera", 0.9f),
        ApRule("^(ezviz|hik-|hikvision|ds-2cd|ds-2de|hik_).*$", "Hikvision / EZVIZ camera", 0.9f),
        ApRule("^(dh-|dahua|imou|ipc-hdw|ipc-hfw|dh_ipc).*$", "Dahua / Imou camera", 0.9f),
        ApRule("^(arlo|blink|eufy_cam|eufycam|eufy[-_ ]?security|ring-|ring_|ringsetup|lorex|swann|zmodo|annke|night ?owl|wansview|sricam|tenvis|vimtag|dlink.*cam|dcs-\\d{3,4})([-_ ].*)?$", "Consumer security camera in setup mode", 0.85f),
        ApRule("^(v380|yoosee|icsee|xm-|xmeye|gw_|carecam|camhipro|hichip|ipc365|cloudedge|cloudcam|littlelf|tuya_ipc|jooan|besder|anran|hiseeu|zosi|sv3c|xvim|iegeek)([-_ ].*)?$", "Budget Wi-Fi camera (V380 / Yoosee / iCSee / XMeye / Tuya) in setup mode", 0.85f),
        // Every Tuya / Smart Life plug and bulb broadcasts this in setup mode too.
        ApRule("^(tuyasmart|smartlife|smart_life)([-_ ].*)?$", "Tuya / Smart Life device in setup mode (plugs and bulbs use it as well as cameras)", 0.3f),
        ApRule("^(mi camera|mi_camera|xiaomi_cam|xiaomi-cam|mijia_cam|chuangmi|isa-camera|isa_camera|imilab|aqara[-_ ]?cam|aqara[-_ ]?g\\d)([-_ ].*)?$", "Xiaomi / Aqara camera", 0.85f),
        ApRule("^(nvr|dvr|hdnvr|xvr)[-_ ]?[0-9a-z]*$", "Video recorder (NVR/DVR) hotspot", 0.7f),
        ApRule("^direct-[0-9a-z]{2}-.*(cam|ipc|camera).*$", "Wi-Fi Direct camera", 0.7f),
        ApRule("^(dashcam|dash_cam|dash-cam|viofo|70mai|nextbase|blackvue|ddpai|thinkware|azdome|vantrue|rove|garmin[-_ ]?dash).*$", "Dash camera hotspot (usually a parked car)", 0.5f),
        ApRule("^(gopro|insta360|dji[-_ ]|osmo|akaso|hero\\d).*$", "Action camera hotspot", 0.45f),
        ApRule("^(canon|nikon|sony|fujifilm|panasonic|olympus|lumix|om-d|eos)[-_ ].*$", "Handheld camera's hotspot", 0.3f)
    )

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val CAMERA_TOKENS = setOf("cam", "ipc", "ipcam", "camera", "webcam", "spycam", "minicam", "hdcam", "netcam", "nvr", "dvr")

    private var wifiJob: Job? = null

    fun startWifiSetupScan(context: Context) {
        if (_wireless.value.wifiScanning) return
        val app = context.applicationContext
        restore(app)
        wifiJob = scope.launch { runCatching { scanWifiSetupAps(app) } }
    }

    /**
     * Refreshes the platform's scan cache and reads it for camera setup hotspots. An
     * unprovisioned camera is usually an *open* access point with the maker's name
     * and the last bytes of its MAC; a venue's own Wi-Fi never looks like that.
     */
    suspend fun scanWifiSetupAps(context: Context): List<CameraFinding> {
        val app = context.applicationContext
        _wireless.update { it.copy(wifiScanning = true, wifiError = null) }
        val out = ArrayList<CameraFinding>()
        try {
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi == null) { _wireless.update { it.copy(wifiError = "No Wi-Fi service on this device") }; return out }
            if (!wifi.isWifiEnabled) { _wireless.update { it.copy(wifiError = "Wi-Fi is turned off") }; return out }
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                _wireless.update { it.copy(wifiError = "Location permission needed to read network names") }; return out
            }
            if (runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() != true) {
                _wireless.update { it.copy(wifiError = "Location is turned off — Android hides network names without it") }; return out
            }
            WifiScanner.requestScan(app)
            @Suppress("DEPRECATION")
            val results = try { wifi.scanResults } catch (_: SecurityException) { null } ?: emptyList()
            val (lat, lon) = fix(app)
            val now = System.currentTimeMillis()
            // When the readings were actually taken: Android refuses all but four scans in two
            // minutes, and then the cache is older than "just now".
            val newestUs = results.maxOfOrNull { it.timestamp } ?: 0L
            val measuredAt = if (newestUs > 0L) now - (SystemClock.elapsedRealtime() - newestUs / 1000L).coerceAtLeast(0L) else now
            for (r in results) {
                @Suppress("DEPRECATION")
                val raw = r.SSID?.trim()?.trim('"').orEmpty()
                val bssid = r.BSSID?.lowercase(Locale.US) ?: continue
                if (raw.isEmpty()) continue
                val ssid = raw.lowercase(Locale.US)
                val open = WifiScanner.securityOf(r.capabilities) == WifiSecurity.OPEN
                val rule = AP_RULES.firstOrNull { it.regex.matches(ssid) }
                var conf = rule?.confidence ?: 0f
                var label = rule?.label
                if (rule == null && open) {
                    // An open network whose first word is a camera word: not a maker we
                    // know, but nothing else broadcasts an open "CAM-3F9A".
                    val tokens = ssid.split(NON_ALNUM).filter { it.isNotEmpty() }
                    if (tokens.isNotEmpty() && tokens.first() in CAMERA_TOKENS) { conf = 0.6f; label = "Open hotspot named like a camera" }
                }
                if (label == null) continue
                if (open) conf = min(1f, conf + 0.05f)
                val metres = approxMetresWifi(r.level)
                out += CameraFinding(
                    id = "wifiap|$bssid", ts = now, method = CameraFindMethod.WIFI_SETUP_AP, confidence = conf,
                    location = "\"$raw\" at ${r.level} dBm — roughly ${fmtMetres(metres)} away" + if (r.level >= -40) ", probably in this room" else "",
                    detail = "$label. ${if (open) "Open network" else "Encrypted network"}, ${WifiScanner.bandFor(r.frequency)}, BSSID $bssid. " +
                        "A camera broadcasts its own hotspot until someone pairs it with the venue's Wi-Fi; one that is still broadcasting was planted recently or never finished setup. Walk toward the strongest signal.",
                    lat = lat, lon = lon
                )
            }
            out.sortByDescending { it.confidence }
            _findings.update { current -> (out + current.filter { it.method != CameraFindMethod.WIFI_SETUP_AP }).take(FINDINGS_CAP) }
            out.forEach { f -> if (f.confidence >= ALERT_CONFIDENCE) report(f) }
            _wireless.update { it.copy(wifiScannedTs = measuredAt, wifiApsSeen = results.size, wifiError = null) }
            markRun()
            return out
        } finally {
            _wireless.update { it.copy(wifiScanning = false) }
        }
    }

    private var bleJob: Job? = null

    fun startBleListening(context: Context, durationMs: Long = 20_000L) {
        if (_wireless.value.bleListening) return
        val app = context.applicationContext
        restore(app)
        bleJob = scope.launch { runCatching { listenBle(app, durationMs) } }
    }

    fun stopBleListening() { bleJob?.cancel() }

    /**
     * Listens for [durationMs] to every BLE advertisement in range — its own
     * low-latency scan plus whatever the tracker service is already publishing on
     * [Registry.scans] — and reports the ones that name a camera or carry a
     * provisioning service. Names come from the advertisement, not from
     * `BluetoothDevice.getName`, which would need the CONNECT permission.
     */
    suspend fun listenBle(context: Context, durationMs: Long = 20_000L) {
        val app = context.applicationContext
        val until = System.currentTimeMillis() + durationMs
        _wireless.update { it.copy(bleListening = true, bleUntilTs = until, bleDevicesSeen = 0, bleError = null) }
        val seen = Collections.synchronizedSet(HashSet<String>())
        // When each device's finding was last refreshed: its signal strength is updated about
        // once a second, so walking toward it shows the number climbing.
        val refreshedAt = ConcurrentHashMap<String, Long>()
        val (lat, lon) = fix(app)
        val handle: (ScanResult) -> Unit = { r ->
            val address = r.device?.address
            if (address != null) {
                if (seen.add(address)) _wireless.update { it.copy(bleDevicesSeen = seen.size) }
                val record = r.scanRecord
                val name = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                val match = CameraEvidence.matchBle(name, record?.serviceUuids?.map { it.uuid.toString() }.orEmpty())
                val nowMs = System.currentTimeMillis()
                if (match != null && nowMs - (refreshedAt[address] ?: 0L) >= 1_000L) {
                    refreshedAt[address] = nowMs
                    val metres = approxMetres(r.rssi)
                    report(
                        CameraFinding(
                            id = "ble|$address", ts = System.currentTimeMillis(), method = CameraFindMethod.BLE_SETUP_AP, confidence = match.confidence,
                            location = "${name ?: "Unnamed device"} ($address) at ${r.rssi} dBm — roughly ${fmtMetres(metres)} away" + if (r.rssi >= -50) ", probably in this room" else "",
                            detail = "${match.label}. Cameras advertise over Bluetooth while they wait for an app to hand them Wi-Fi credentials; a working camera has usually stopped. Follow the signal strength to find it.",
                            lat = lat, lon = lon
                        )
                    )
                }
            }
        }
        try {
            coroutineScope {
                launch {
                    withTimeoutOrNull(durationMs) {
                        bleScanFlow(app)
                            .catch { e -> _wireless.update { it.copy(bleError = e.message ?: "Bluetooth scan failed") } }
                            .collect { handle(it) }
                    }
                }
                launch { withTimeoutOrNull(durationMs) { Registry.scans.collect { handle(it) } } }
            }
        } finally {
            _wireless.update { it.copy(bleListening = false, bleUntilTs = 0L) }
            // A window that heard the air counts as a sweep; one Bluetooth refused does not.
            if (_wireless.value.bleError == null) markRun()
        }
    }

    private fun bleScanFlow(app: Context): Flow<ScanResult> = callbackFlow {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { close(IllegalStateException("Bluetooth is off")); return@callbackFlow }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            close(SecurityException("Nearby devices permission not granted")); return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { close(IllegalStateException("Bluetooth LE scanner unavailable")); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { trySend(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { trySend(it) } }
            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("Bluetooth scan failed (code $errorCode)")) }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false).setReportDelay(0).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) { close(e); return@callbackFlow }
        catch (e: IllegalStateException) { close(e); return@callbackFlow }
        awaitClose {
            try { scanner.stopScan(callback) } catch (_: SecurityException) {} catch (_: IllegalStateException) {}
        }
    }

    // ── Magnetic: a sweep with the phone's compass ───────────────────────────

    /**
     * Feeds the gauge's verdict ([MagneticBaseline]) and turns its episodes into
     * findings: one per episode, refreshed as the peak grows, and put on the
     * timeline with its final peak when the episode ends or the gauge stops.
     */
    private class MagneticListener(context: Context) : SensorEventListener {
        private val verdict = MagneticBaseline()
        private val offset = HardIronHold()
        private val lat: Double?
        private val lon: Double?
        /** The open episode's finding, or null. */
        private var episode: CameraFinding? = null
        /** The baseline when the open episode began. */
        private var episodeBaselineUt = 0f

        init {
            val (la, lo) = fix(context)
            lat = la; lon = lo
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // A re-seed takes the platform's offset estimate again, with the new baseline.
            if (verdict.accuracyChanged(accuracy)) offset.release()
            if (verdict.unreliable) closeEpisode()
            // Re-seeded: settling again, or paused until the compass recovers.
            _magnetic.update { it.copy(triggered = false, unreliable = verdict.unreliable, warmingUp = !verdict.unreliable) }
        }

        override fun onSensorChanged(e: SensorEvent) {
            // From TYPE_MAGNETIC_FIELD_UNCALIBRATED: the raw field less the phone's own
            // hard-iron offset as the platform estimated it when the gauge (re)started,
            // held so a re-estimate does not step the reading, and subtracted so turning
            // the phone does not move it ([HardIronHold]).
            val r = verdict.sample(offset.magnitude(e.values), SystemClock.elapsedRealtime())
            if (r.started) {
                episodeBaselineUt = r.baselineUt
                episode = finding(System.currentTimeMillis(), r).also { report(it) }
            } else if (r.peakGrew) {
                episode?.let { open -> episode = finding(open.ts, r).also { report(it) } }
            }
            if (!r.triggered) closeEpisode()
            _magnetic.value = MagneticState(
                active = true, fieldUt = r.fieldUt, baselineUt = r.baselineUt, deltaUt = r.deltaUt, thresholdUt = r.thresholdUt,
                peakUt = r.peakUt, triggered = r.triggered, unreliable = r.unreliable, warmingUp = r.warmingUp
            )
        }

        private fun finding(ts: Long, r: MagneticBaseline.Reading): CameraFinding {
            // A magnet is not a camera: speakers, mounts and door catches all move the
            // needle. This is a hint about where to look, so it caps well below alert level.
            val conf = (r.peakUt / FULL_CONFIDENCE_UT).coerceIn(0.15f, 0.6f)
            return CameraFinding(
                id = "mag|$ts", ts = ts, method = CameraFindMethod.MAGNETIC, confidence = conf,
                location = "Where the phone was when the needle jumped",
                detail = "The field moved ${r.peakUt.roundToInt()} µT from the ${episodeBaselineUt.roundToInt()} µT baseline (trigger ${r.thresholdUt.roundToInt()} µT). " +
                    "Covert cameras carry a speaker magnet or a magnetic mount; so do real speakers, door catches and steel studs. Look for a lens at this spot.",
                lat = lat, lon = lon
            )
        }

        /** The open episode is over (or the gauge stops): it goes on the timeline with its peak. */
        fun closeEpisode() {
            val f = episode ?: return
            episode = null
            recordMagnetic(f)
        }
    }

    /**
     * A magnetic anomaly on the timeline. It never reaches alert confidence (a magnet
     * is a hint, not a camera), so it is recorded as LOW and raises no notification.
     */
    private fun recordMagnetic(f: CameraFinding) {
        Registry.publishAlert(
            Alert(
                id = "camera|${f.id}", ts = f.ts, severity = Severity.LOW,
                title = "Magnetic anomaly: look for a lens here",
                detail = "${f.location}. ${f.detail}",
                kind = EventKind.CAMERA, dedupeKey = "camera|${f.id}",
                lat = f.lat, lon = f.lon, silent = true
            ),
            dedupeWindowMs = 6 * 60 * 60_000L
        )
    }

    private var magListener: MagneticListener? = null
    private var magManager: SensorManager? = null

    /**
     * Starts the magnetometer sweep; call from the main thread (sensor events land on
     * it). Reads the uncalibrated stream where the phone has one, which the
     * platform's calibration updates do not step; the calibrated stream otherwise.
     */
    fun startMagnetic(context: Context) {
        stopMagnetic()
        val app = context.applicationContext
        restore(app)
        val sm = app.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || sensor == null) {
            _magnetic.value = MagneticState(active = false, error = "This phone has no magnetometer")
            return
        }
        val l = MagneticListener(app)
        val ok = runCatching { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME) }.getOrDefault(false)
        if (!ok) {
            _magnetic.value = MagneticState(active = false, error = "Magnetometer refused to start")
            return
        }
        magListener = l
        magManager = sm
        _magnetic.value = MagneticState(active = true, warmingUp = true)
        markRun()
    }

    fun stopMagnetic() {
        magListener?.let { l ->
            runCatching { magManager?.unregisterListener(l) }
            l.closeEpisode()
        }
        magListener = null
        magManager = null
        _magnetic.update { it.copy(active = false, triggered = false, warmingUp = false) }
    }

    // ── Guided sweep ─────────────────────────────────────────────────────────

    val SWEEP_STEPS: List<SweepStep> = listOf(
        SweepStep("sightlines", "Stand where you are exposed",
            "Bed, shower, toilet, desk, the spot where you change. A camera has to see its target — stand there and look back at everything that can see you. That is the search area.", "Eyes"),
        SweepStep("lights", "Lights off, blinds closed",
            "Let your eyes adjust for a minute, then look slowly around at eye height and up. Most covert cameras have a tiny status LED or an IR glow that is lost under room lighting.", "Eyes"),
        SweepStep("glint", "Glint sweep with the torch",
            "OPTICAL tab, Lens glint mode. Hold the phone at arm's length and pan slowly: a lens throws the torch straight back as a steady pinpoint; a screw head or glass edge flashes and slides. Circles that turn red have held still. It works best within about a metre of the surface: for ceiling fixtures, stand on a chair or use the 2× zoom.", "OPTICAL"),
        SweepStep("ir", "Infrared sweep in the dark",
            "OPTICAL tab, IR illuminator mode. First check this phone can see infrared at all, with a TV remote and the check on that tab. Then, room dark, point the front camera at smoke detectors, clocks, chargers, outlets and vents. A night-vision camera lights the room with LEDs you cannot see; a phone that passed the check can. A light you can see with your own eyes is an ordinary LED.", "OPTICAL"),
        SweepStep("objects", "Check the usual disguises",
            "Smoke detectors, USB chargers and power strips, alarm clocks, air fresheners, picture frames, tissue boxes, books, plant pots, screws with a hole in the head. Light bulbs and lamp shades: bulb cameras screw into any socket and point down at the bed. Coat and towel hooks on the back of doors, routers and TV boxes, TVs, soundbars and smart speakers, thermostats, door peepholes, shower heads. Anything with a dark pinhole facing the room, especially facing the bed or shower.", "Hands · torch"),
        SweepStep("outlets", "Outlets, vents and ceiling fixtures",
            "Pull chargers out and look at their faces. Shine the torch into vents and around the edges of ceiling lights and detectors; a lens behind a grille still glints.", "Torch"),
        SweepStep("above", "Above and behind you",
            "Lie on the bed and stand in the shower; look up and at the door. Anything with a dark dot facing you from above (a bulb, a hook, a detector) gets the glint and IR check from where you are.", "Eyes · OPTICAL"),
        SweepStep("mirrors", "Mirror test",
            "Fingernail on the glass: a gap between the nail and its reflection is a normal mirror. No gap, or a dark room showing through when you cup the torch against the glass, is a two-way mirror.", "Hands · torch"),
        SweepStep("network", "Scan the venue's Wi-Fi",
            "Join the venue's network and run the NETWORK tab. Cameras that serve video on the network answer RTSP, many answer ONVIF, and some have a web page with the maker's name on it. Most consumer cameras talk only to their maker's cloud and answer none of that, so a clean result here is not the last word.", "NETWORK"),
        SweepStep("wireless", "Listen for setup beacons",
            "WIRELESS tab: a camera that has not been paired broadcasts its own open hotspot or a Bluetooth name. A planted one that was never finished is loud.", "WIRELESS"),
        SweepStep("magnet", "Magnetic pass along surfaces",
            "Gauge on, phone's back flat to the surface, slide slowly along walls behind the bed, under tables, along shelves. A speaker magnet or a magnetic mount jumps the needle; drywall and wood do not.", "GUIDED gauge")
    )

    fun toggleSweepStep(id: String) {
        val done = _sweepDone.updateAndGet { done -> if (id in done) done - id else done + id }
        // A step ticked off is part of the room swept.
        if (id in done) markRun() else persistSoon()
    }

    fun resetSweep() {
        _sweepDone.value = emptySet()
        persistNow()
    }

    /**
     * What the network sweep cannot see even on the same Wi-Fi. Shown on the
     * Network tab whatever the result, and in the website's limits.
     */
    const val CLOUD_ONLY_NOTE: String =
        "Most consumer cameras (Wyze, Blink, Ring, Arlo, Nest, many cheap \"IP camera\" app cameras) talk only to their maker's cloud " +
        "and run no local service. They are invisible to this sweep even on the same Wi-Fi; the optical, wireless and hands-on steps are what find them."

    /** Shown on the IR tab until this phone has shown it can see infrared. */
    const val IR_UNSEEN_TITLE: String = "This phone has not shown it can see infrared"

    /** What the IR tab's notice says about [check], or null once the phone has shown infrared. */
    fun irNotice(check: IrCheck): String? = when {
        check.seen -> null
        check.failedTs > 0L ->
            "The last check saw no remote flashing. Some front cameras filter infrared out completely, and on those the IR view cannot show an illuminator. " +
                "Try again with the remote close to the screen, and rely on the other tabs until it passes."
        else ->
            "Point a TV remote at the front camera, press and hold a button, and run the check. Until this phone has shown the remote flashing, an empty IR result says nothing about the room."
    }

    /** The IR tab's empty findings text: prefixed with [IR_UNSEEN_TITLE] until the phone has shown infrared. */
    fun irEmptyText(check: IrCheck): String =
        if (check.seen) "No steady points of light recorded yet" else "$IR_UNSEEN_TITLE. No steady points of light recorded yet."

    const val ANALOG_RF_NOTE: String =
        "What this phone cannot see: analogue wireless cameras. Cheap video senders on 900 MHz, 1.2 GHz, 2.4 GHz and 5.8 GHz " +
        "broadcast FM video with no Wi-Fi, no Bluetooth and no network, and a camera recording to a memory card transmits nothing at all. " +
        "A phone's radios decode only Wi-Fi, Bluetooth, NFC and cellular — there is no spectrum analyser in it. " +
        "For those, the physical sweep here (glint, IR, magnet, hands) is the method; a dedicated RF detector adds the radio side."

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Our position: the scan service's fix if it has one, else the platform's last known. */
    private fun fix(context: Context): Pair<Double?, Double?> {
        val s = Registry.status.value
        if (s.lat != null && s.lon != null) return s.lat to s.lon
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null to null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null to null
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return loc?.latitude to loc?.longitude
    }

    /** Log-distance path loss with a −59 dBm one-metre reference, which is what BLE and Wi-Fi beacons roughly show. */
    fun approxMetres(rssi: Int): Double = 10.0.pow((-59.0 - rssi) / 20.0)

    /**
     * A Wi-Fi access point is far louder than a Bluetooth beacon: about -40 dBm at a
     * metre, and walls bend the falloff steeper than free space (exponent 3).
     */
    fun approxMetresWifi(rssi: Int): Double = 10.0.pow((-40.0 - rssi) / 30.0)

    fun fmtMetres(m: Double): String = when {
        m < 1.0 -> "under a metre"
        m < 10.0 -> "${m.roundToInt()} m"
        else -> "${(m / 5).roundToInt() * 5} m"
    }
}
