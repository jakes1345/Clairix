package app.clairix.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Feeds [GnssIntegrity] from the platform: the satellites the receiver hears, its raw
 * measurements (clock and gain control), satellite positions, and Wi-Fi/cell positions to
 * hold them against. Publishes the assessment on [Registry.gnss] and raises an alert when
 * the verdict turns to spoofed, jammed or suspect.
 *
 * Battery: the status callback and the passive listener cost nothing of their own; they
 * report whenever something else has the receiver running, which the scanner's own
 * position fixes do while the phone is moving, and navigation does. The raw-measurement
 * callback is not like them: registered without a request it asks for the fastest rate
 * the chipset can report and keeps the receiver running for as long as it is registered,
 * which held a once-a-second satellite session for the whole scan. It is registered as
 * [GnssRequestPolicy.measurements] decides: passive on Android 14 and later, the longest
 * interval on Android 13, and on Android 12, which has neither, only while the phone is
 * moving ([setSettled]). Passive, it hears measurements only when another app asks for
 * them, so in the background the clock and gain checks often have none. Only a
 * foreground holder (the GPS screen) switches the receiver on itself, once a second,
 * with measurements at the same rate.
 *
 * The Wi-Fi/cell position the satellites are held against is this monitor's own
 * network-provider request, not the scanner's fused fixes, which may come from the
 * satellites themselves. It is asked for once a minute while the GPS screen is open.
 * Otherwise it is asked for only while satellite fixes are arriving, once a minute while
 * the phone is moving and every 15 minutes once the scanner has seen it settle: with no
 * satellite fix there is nothing to hold it against, and each Wi-Fi/cell fix wakes the
 * radios ([GnssRequestPolicy.networkIntervalMs]). It is not asked for at all on a phone
 * with no Wi-Fi/cell provider, with "Cross-check GPS against Wi-Fi/cell position" off, or
 * with Satellite-only positioning on ([Positioning.networkCheck]): working it out sends the
 * networks around the phone to its location service, Google Play services on most phones.
 * The network check then says which of the three it is ([GnssIntegrity.networkUnavailable]).
 *
 * Held by reference count: the scanner service holds it while it runs, the GPS screen
 * while it is open. Everything runs on one thread, which owns the analyzer.
 */
object GnssMonitor {

    private const val TAG = "GnssMonitor"
    private const val ASSESS_EVERY_MS = 1_000L
    private const val ALERT_WINDOW_MS = 30 * 60_000L
    private const val GPS_L1_HZ = 1_575.42e6

    class Handle internal constructor(val foreground: Boolean)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gnss-monitor").apply { isDaemon = true } }
    private val holders = LinkedHashSet<Handle>()
    private var app: Context? = null
    private var integrity: GnssIntegrity? = null
    private var lastAssessAt = 0L
    private val alertedAt = HashMap<GnssVerdict, Long>()
    private var gpsRequested = false
    private var registered = false
    /** The scanner has seen the phone settle; see [setSettled]. */
    private var settled = false
    /** The interval the Wi-Fi/cell request is registered at, or 0 when there is none. */
    private var networkIntervalMs = 0L
    /** How the raw-measurement callback is registered now; see [GnssRequestPolicy.measurements]. */
    private var measurements: GnssRequestPolicy.Measurements = GnssRequestPolicy.Measurements.Off
    /** [SystemClock.elapsedRealtime] of the latest satellite fix heard, or 0; see [GnssRequestPolicy.networkIntervalMs]. */
    private var lastGnssFixAt = 0L
    /**
     * Whether a Wi-Fi/cell position may be asked for: the phone has a provider for it and
     * the owner allows it. Looked up once per registration and whenever the owner changes
     * either setting ([settingsChanged]), not on every fix.
     */
    private var networkCheck = NetworkCheck.NO_PROVIDER

    /** Starts (or joins) monitoring. A [foreground] holder also switches the receiver on. Release the handle when done. */
    fun acquire(context: Context, foreground: Boolean): Handle {
        val h = Handle(foreground)
        val ctx = context.applicationContext
        executor.execute {
            app = ctx
            holders += h
            if (!registered) register(ctx)
            updateGpsRequest(ctx)
            if (registered) updateRequests(ctx)
        }
        return h
    }

    fun release(handle: Handle) {
        executor.execute {
            holders -= handle
            val ctx = app ?: return@execute
            if (holders.isEmpty()) unregister(ctx) else { updateGpsRequest(ctx); if (registered) updateRequests(ctx) }
        }
    }

    /**
     * The scanner's view of whether the phone is going anywhere. Settled, the Wi-Fi/cell
     * fixes slow down, and on Android 12 the measurement callback, which keeps the
     * receiver on, is dropped until the phone moves again.
     */
    fun setSettled(value: Boolean) {
        executor.execute {
            settled = value
            app?.let { if (registered) updateRequests(it) }
        }
    }

    /**
     * The owner changed Satellite-only positioning or the Wi-Fi/cell cross-check: the
     * request follows at once, a standing one goes, and the network check says why.
     */
    fun settingsChanged() {
        executor.execute {
            val ctx = app ?: return@execute
            if (!registered) return@execute
            refreshNetworkCheck(ctx)
            updateNetworkRequest(ctx)
            maybeAssess(force = true)
        }
    }

    /** Forgets everything learned, as the scanner's Clear data does. */
    fun clear() {
        executor.execute {
            integrity?.clear()
            alertedAt.clear()
            Registry.publishGnss(GnssAssessment())
        }
    }

    private fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun manager(ctx: Context) = ctx.getSystemService(LocationManager::class.java)

    private fun register(ctx: Context) {
        if (!granted(ctx)) {
            Log.i(TAG, "No fine location permission; satellite checks are off")
            return
        }
        val lm = manager(ctx) ?: return
        integrity = GnssIntegrity(wallClockTrusted = {
            runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) == 1 }.getOrDefault(true)
        })
        try {
            lm.registerGnssStatusCallback(executor, statusCallback)
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, executor, passiveListener)
            registered = true
            refreshNetworkCheck(ctx)
            updateRequests(ctx)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Provider missing: ${e.message}")
        }
    }

    private fun unregister(ctx: Context) {
        val lm = manager(ctx) ?: return
        runCatching { lm.unregisterGnssStatusCallback(statusCallback) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
        runCatching { lm.removeUpdates(passiveListener) }
        runCatching { lm.removeUpdates(networkListener) }
        runCatching { lm.removeUpdates(gpsListener) }
        gpsRequested = false
        registered = false
        networkIntervalMs = 0L
        measurements = GnssRequestPolicy.Measurements.Off
        lastGnssFixAt = 0L
    }

    /** The holders, the pace or the satellite fixes changed: brings both requests that depend on them up to date. */
    private fun updateRequests(ctx: Context) {
        updateMeasurements(ctx)
        updateNetworkRequest(ctx)
    }

    private fun updateMeasurements(ctx: Context) {
        val want = GnssRequestPolicy.measurements(Build.VERSION.SDK_INT, foreground = holders.any { it.foreground }, moving = !settled)
        if (want == measurements) return
        val lm = manager(ctx) ?: return
        try {
            // Registering the same callback again replaces its earlier request.
            if (want == GnssRequestPolicy.Measurements.Off) lm.unregisterGnssMeasurementsCallback(measurementsCallback)
            else lm.registerGnssMeasurementsCallback(measurementRequest(want), executor, measurementsCallback)
            measurements = want
        } catch (e: SecurityException) {
            Log.w(TAG, "Satellite measurements refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Satellite measurements unavailable: ${e.message}")
        }
    }

    /**
     * Android 12 has no interval to set, and only ever asks for the platform default;
     * from Android 13 the request carries the policy's interval, which on Android 14
     * and later is [GnssMeasurementRequest.PASSIVE_INTERVAL] for the passive request.
     */
    private fun measurementRequest(m: GnssRequestPolicy.Measurements): GnssMeasurementRequest {
        val builder = GnssMeasurementRequest.Builder()
        val interval = GnssRequestPolicy.intervalMillis(m)
        if (interval != null && Build.VERSION.SDK_INT >= 33) builder.setIntervalMillis(interval)
        return builder.build()
    }

    /** Whether the satellites can be held against a Wi-Fi/cell position here, for the request, the network check and the screens. */
    private fun refreshNetworkCheck(ctx: Context) {
        val check = Positioning.networkCheck(ctx)
        networkCheck = check
        integrity?.networkUnavailable = PositionText.network(check)
        Registry.updatePositioning { it.copy(networkCheck = check) }
    }

    /** Runs on every satellite and Wi-Fi/cell fix, so it returns before any platform call when nothing changes. */
    private fun updateNetworkRequest(ctx: Context) {
        // No provider, the cross-check off or Satellite-only positioning on: nothing is
        // asked for, and a request standing from before goes.
        val want = if (networkCheck != NetworkCheck.AVAILABLE) 0L else GnssRequestPolicy.networkIntervalMs(
            foreground = holders.any { it.foreground }, moving = !settled,
            lastGnssFixAt = lastGnssFixAt, now = SystemClock.elapsedRealtime()
        ) ?: 0L
        if (want == networkIntervalMs) return
        val lm = manager(ctx) ?: return
        if (want == 0L) {
            runCatching { lm.removeUpdates(networkListener) }
            networkIntervalMs = 0L
            return
        }
        try {
            // Registering the same listener again replaces its earlier request.
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, want, 0f, executor, networkListener)
            networkIntervalMs = want
        } catch (e: SecurityException) {
            Log.w(TAG, "Network location refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No network provider: ${e.message}")
        }
    }

    private fun updateGpsRequest(ctx: Context) {
        val want = holders.any { it.foreground } && registered
        if (want == gpsRequested) return
        val lm = manager(ctx) ?: return
        try {
            if (want) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, executor, gpsListener)
            else lm.removeUpdates(gpsListener)
            gpsRequested = want
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No GPS provider: ${e.message}")
        }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val g = integrity ?: return
            val sats = ArrayList<GnssSat>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                sats += GnssSat(
                    constellation = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i),
                    used = status.usedInFix(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null
                )
            }
            g.onSky(SkyEpoch(System.currentTimeMillis(), sats))
            maybeAssess()
        }

        override fun onStopped() {
            // The receiver went off; what is published stays until it is stale, and the
            // assessment says NO_DATA once the last epoch is older than half a minute.
            maybeAssess(force = true)
        }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val g = integrity ?: return
            val clock = event.clock
            val agc: Double? = if (Build.VERSION.SDK_INT >= 33) {
                event.gnssAutomaticGainControls
                    .filter { it.constellationType == GnssStatus.CONSTELLATION_GPS && abs(it.carrierFrequencyHz - GPS_L1_HZ) < 2e6 }
                    .map { it.levelDb }
                    .takeIf { it.isNotEmpty() }?.average()
            } else {
                @Suppress("DEPRECATION")
                event.measurements
                    .filter { it.constellationType == GnssStatus.CONSTELLATION_GPS && it.hasAutomaticGainControlLevelDb() &&
                        (!it.hasCarrierFrequencyHz() || abs(it.carrierFrequencyHz - GPS_L1_HZ) < 2e6) }
                    .map { it.automaticGainControlLevelDb }
                    .takeIf { it.isNotEmpty() }?.average()
            }
            g.onClock(
                GnssClockSample(
                    timeMs = System.currentTimeMillis(),
                    discontinuity = clock.hardwareClockDiscontinuityCount,
                    driftNsPerS = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
                    agcDb = agc
                )
            )
        }
    }

    /** The phone's wall clock at the moment [loc] was computed, from the elapsed-time stamps. */
    private fun wallAt(loc: Location): Long {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        return System.currentTimeMillis() - ageMs.coerceAtLeast(0L)
    }

    private fun onLocation(loc: Location) {
        val g = integrity ?: return
        val at = wallAt(loc)
        if (loc.isMock) {
            g.onMockFix(at)
        } else when (loc.provider) {
            LocationManager.GPS_PROVIDER -> {
                g.onGnssFix(GnssFix(at, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.time))
                lastGnssFixAt = SystemClock.elapsedRealtime()
            }
            LocationManager.NETWORK_PROVIDER -> g.onNetworkFix(
                NetworkFix(at, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
            )
        }
        // The first satellite fix after a quiet spell asks for Wi-Fi/cell positions
        // again; the first fix of any kind once the satellites have gone quiet stops it.
        app?.let { if (registered) updateNetworkRequest(it) }
        maybeAssess(force = true)
    }

    private val passiveListener = LocationListener { onLocation(it) }
    private val gpsListener = LocationListener { onLocation(it) }
    private val networkListener = LocationListener { onLocation(it) }

    private fun maybeAssess(force: Boolean = false) {
        val g = integrity ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastAssessAt < ASSESS_EVERY_MS) return
        lastAssessAt = now
        val a = g.assess(now)
        Registry.publishGnss(a)
        raise(a, now)
    }

    private fun raise(a: GnssAssessment, now: Long) {
        val severity = when (a.verdict) {
            GnssVerdict.SPOOFED, GnssVerdict.JAMMED -> Severity.HIGH
            GnssVerdict.SUSPECT -> Severity.MEDIUM
            else -> return
        }
        val last = alertedAt[a.verdict] ?: 0L
        if (now - last < ALERT_WINDOW_MS) return
        alertedAt[a.verdict] = now
        val reasons = (a.failing + a.warning).joinToString(" ") { "${it.label}: ${it.detail}" }
        Registry.publishAlert(
            Alert(
                id = "gnss_${a.verdict.name.lowercase()}@$now", ts = now, severity = severity,
                title = a.verdict.label, detail = reasons + a.advice(),
                kind = EventKind.GNSS, dedupeKey = "gnss_${a.verdict.name.lowercase()}",
                // Where the phone is, from Wi-Fi and cell towers, only when a recent
                // network position agrees with the satellites. When the two disagree
                // nothing here knows which is wrong, and an old one says nothing about
                // now, so the alert then carries no position at all.
                lat = a.vouchedNetwork?.first,
                lon = a.vouchedNetwork?.second
            ),
            dedupeWindowMs = ALERT_WINDOW_MS
        )
    }
}
