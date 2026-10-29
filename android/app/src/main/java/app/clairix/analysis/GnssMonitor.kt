package app.clairix.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * Battery: the status and measurement callbacks and the passive listener cost nothing of
 * their own; they report whenever something else has the receiver running, which the
 * scanner's own location request and navigation do. Only a foreground holder (the GPS
 * screen) switches the receiver on itself, once a second. The Wi-Fi/cell position is asked
 * for once a minute while the phone is moving or the GPS screen is open, and every
 * [NETWORK_SETTLED_MS] once the scanner has seen it settle ([setSettled]): a phone on a
 * desk has nothing new to cross-check, and each Wi-Fi/cell fix wakes the radios.
 *
 * Held by reference count: the scanner service holds it while it runs, the GPS screen
 * while it is open. Everything runs on one thread, which owns the analyzer.
 */
object GnssMonitor {

    private const val TAG = "GnssMonitor"
    private const val ASSESS_EVERY_MS = 1_000L
    private const val ALERT_WINDOW_MS = 30 * 60_000L
    private const val NETWORK_INTERVAL_MS = 60_000L
    private const val NETWORK_SETTLED_MS = 10 * 60_000L
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

    /** Starts (or joins) monitoring. A [foreground] holder also switches the receiver on. Release the handle when done. */
    fun acquire(context: Context, foreground: Boolean): Handle {
        val h = Handle(foreground)
        val ctx = context.applicationContext
        executor.execute {
            app = ctx
            holders += h
            if (!registered) register(ctx)
            updateGpsRequest(ctx)
            if (registered) updateNetworkRequest(ctx)
        }
        return h
    }

    fun release(handle: Handle) {
        executor.execute {
            holders -= handle
            val ctx = app ?: return@execute
            if (holders.isEmpty()) unregister(ctx) else { updateGpsRequest(ctx); if (registered) updateNetworkRequest(ctx) }
        }
    }

    /** The scanner's view of whether the phone is going anywhere; slows the Wi-Fi/cell fixes while it is not. */
    fun setSettled(value: Boolean) {
        executor.execute {
            settled = value
            app?.let { if (registered) updateNetworkRequest(it) }
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
            lm.registerGnssMeasurementsCallback(executor, measurementsCallback)
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, executor, passiveListener)
            registered = true
            updateNetworkRequest(ctx)
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
    }

    private fun updateNetworkRequest(ctx: Context) {
        val lm = manager(ctx) ?: return
        if (!lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) return
        val want = if (settled && holders.none { it.foreground }) NETWORK_SETTLED_MS else NETWORK_INTERVAL_MS
        if (want == networkIntervalMs) return
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
            LocationManager.GPS_PROVIDER -> g.onGnssFix(
                GnssFix(at, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.time)
            )
            LocationManager.NETWORK_PROVIDER -> g.onNetworkFix(
                NetworkFix(at, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
            )
        }
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
        val advice = when (a.verdict) {
            GnssVerdict.SPOOFED -> " Do not trust the position on the map or in any app until this clears; Wi-Fi and cell towers are the better guide."
            GnssVerdict.JAMMED -> " Navigation and anything that tracks a vehicle by GPS will lose it here."
            else -> ""
        }
        Registry.publishAlert(
            Alert(
                id = "gnss_${a.verdict.name.lowercase()}@$now", ts = now, severity = severity,
                title = a.verdict.label, detail = reasons + advice,
                kind = EventKind.GNSS, dedupeKey = "gnss_${a.verdict.name.lowercase()}",
                // Where the phone really is: the Wi-Fi/cell position when the satellites are lying.
                lat = a.networkLat, lon = a.networkLon
            ),
            dedupeWindowMs = ALERT_WINDOW_MS
        )
    }
}
