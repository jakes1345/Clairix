package app.clairix.nav

import android.content.Context
import android.util.Log
import app.clairix.comms.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * Turns map-matched positions into anonymous per-segment speed samples for the relay.
 *
 * Only a segment key, the segment's own map position, a median speed and a time leave the
 * phone, one sample per segment traversed (or per minute spent on one); GPS positions never do.
 * Nothing is collected or sent unless Drive navigation is active, and stopping navigation
 * discards whatever had not been sent. The first stretch of a trip and the last before the
 * destination are never reported, so the relay cannot learn where trips begin or end. Each
 * stretch is between [ZONE_MIN_M] and [ZONE_MAX_M] long, drawn afresh for every trip in
 * [start], so the first and last reported points of a repeated trip are not a fixed distance
 * from home either. A road the car was already on inside the start zone is never reported,
 * however far it runs beyond the zone or whether the car comes back to it later in the trip
 * (see [finish]); entering the end zone drops the road being driven (see [nearDestination]).
 *
 * Each batch is posted anonymously (a traffic token, no identity) under [trip], the pseudonym
 * this drive is known by on the relay, which changes every few minutes: the relay can tell
 * this vehicle's samples from another's for long enough to detect a stoppage, and no longer.
 */
class ProbeReporter(
    context: Context,
    val region: String,
    private val trip: TripPseudonym,
    private val random: SecureRandom = SecureRandom(),
) {

    private companion object {
        const val TAG = "ProbeReporter"
        const val MIN_FIXES = 3
        const val MIN_COVERED_M = 40.0
        const val BATCH_MAX = 60
        const val FLUSH_INTERVAL_MS = 60_000L
        /** The relay refuses samples older than 15 min; anything near that is dropped rather than retried. */
        const val MAX_AGE_MS = 14 * 60_000L
        const val MAX_KPH = 250.0
        /** A gap this long between fixes means the traversal was not observed continuously. */
        const val MAX_GAP_MS = 15_000L
        /**
         * A long stay on one segment is reported a minute at a time; otherwise a car stopped in
         * a jam or behind a crash would say nothing until it moved on, too late for detection.
         */
        const val SLICE_MS = 60_000L
        /**
         * The start and end zones, the stretches of a trip that are never reported, are each
         * drawn from this range per trip (see [drawZoneM]); one fixed length would put the first
         * and last reported points of a daily trip at the same distance from home every day.
         */
        const val ZONE_MIN_M = 500.0
        const val ZONE_MAX_M = 1_500.0
    }

    private class Sample(val seg: String, val kph: Double, val timeMs: Long, val lat: Double, val lon: Double)

    /** The segment currently being driven and what was observed on it so far. */
    private class Traversal(
        val seg: String,
        firstAlongM: Double,
        val startTimeMs: Long,
        var lastTimeMs: Long,
        val segLat: Double,
        val segLon: Double,
        /** [drivenM] when the car entered this segment; a segment entered inside the start zone is never reported. */
        val startDrivenM: Double,
    ) {
        val speeds = ArrayList<Double>()
        var minAlongM = firstAlongM
        var maxAlongM = firstAlongM
        var speedUnknown = false
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val sendLock = Mutex()
    private var current: Traversal? = null
    private val pending = ArrayList<Sample>()
    @Volatile private var active = false
    private var flushJob: Job? = null
    private var scope: CoroutineScope? = null
    /** Distance covered by the traversals finished since [start], reported or not. */
    private var drivenM = 0.0
    /**
     * The segment last matched and [drivenM] when the car entered it. A later minute on the same
     * segment (see [SLICE_MS]), or a return to it after fixes that matched no road, inherits that
     * distance, so the road a trip began on stays unreported beyond the zone boundary too.
     */
    private var lastSeg: String? = null
    private var lastSegEnteredM = 0.0
    /** Roads entered inside this trip's start zone; a return to one of them later in the trip is not reported either. */
    private val startZoneSegs = HashSet<String>()
    /** This trip's start zone: a segment entered before this far was driven is never reported. */
    internal var startZoneM = ZONE_MAX_M
        private set
    /** This trip's end zone: once the destination is this near, nothing more is reported. */
    internal var endZoneM = ZONE_MAX_M
        private set
    @Volatile private var suppressed = false

    /**
     * Set by navigation once the destination is near: from then on nothing more is reported, and
     * the segment being driven is dropped rather than finished.
     */
    fun nearDestination(near: Boolean) {
        synchronized(lock) {
            if (near && !suppressed) current = null
            suppressed = near
        }
    }

    /** Navigation's distance left to the destination; inside this trip's end zone reporting stops (see [nearDestination]). */
    fun remainingToDestination(remainingM: Double) {
        synchronized(lock) { nearDestination(remainingM <= endZoneM) }
    }

    /** Begins collecting; samples are posted every minute, or sooner once 60 are waiting. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (active) return
            active = true
            this.scope = scope
            current = null
            pending.clear()
            drivenM = 0.0
            lastSeg = null
            lastSegEnteredM = 0.0
            startZoneSegs.clear()
            // Fresh zones for each trip: a daily commute must not report from the same two spots every day.
            startZoneM = drawZoneM()
            endZoneM = drawZoneM()
            suppressed = false
            // A new drive is a new vehicle as far as the relay can tell.
            trip.reset()
            flushJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    /** A whole number of metres in [ZONE_MIN_M]..[ZONE_MAX_M], from the same kind of randomness as the pseudonym. */
    private fun drawZoneM(): Double = ZONE_MIN_M + random.nextInt((ZONE_MAX_M - ZONE_MIN_M).toInt() + 1)

    /** Stops collecting and drops everything not yet sent. */
    fun stop() {
        synchronized(lock) {
            active = false
            flushJob?.cancel()
            flushJob = null
            scope = null
            current = null
            pending.clear()
        }
    }

    /**
     * One matched fix. [segmentKey] is null when the fix matched no road, which ends the current
     * traversal. [alongM] is the distance from the start of the matched edge, used to tell how
     * much of the segment was actually observed. [speedMps] null means the GPS gave no speed.
     * [segLat]/[segLon] locate the segment on the map (not the car), so the relay can place
     * incidents it detects there.
     */
    fun onMatched(segmentKey: String?, alongM: Double, speedMps: Float?, timeMs: Long, segLat: Double = Double.NaN, segLon: Double = Double.NaN) {
        var batchFull = false
        synchronized(lock) {
            if (!active || suppressed) return
            val cur = current
            if (cur != null && (cur.seg != segmentKey || timeMs - cur.lastTimeMs > MAX_GAP_MS || timeMs - cur.startTimeMs >= SLICE_MS)) {
                finish(cur)
                current = null
            }
            if (segmentKey == null) return
            if (segmentKey != lastSeg) {
                lastSeg = segmentKey
                lastSegEnteredM = drivenM
                if (drivenM < startZoneM) startZoneSegs.add(segmentKey)
            }
            val t = current ?: Traversal(segmentKey, alongM, timeMs, timeMs, segLat, segLon, lastSegEnteredM).also { current = it }
            t.lastTimeMs = timeMs
            t.minAlongM = minOf(t.minAlongM, alongM)
            t.maxAlongM = maxOf(t.maxAlongM, alongM)
            if (speedMps == null || speedMps.isNaN() || speedMps < 0f) t.speedUnknown = true
            else t.speeds.add(speedMps * 3.6)
            batchFull = pending.size >= BATCH_MAX
        }
        if (batchFull) scope?.launch(Dispatchers.IO) { flush() }
    }

    private fun finish(t: Traversal) {
        val coveredM = t.maxAlongM - t.minAlongM
        drivenM += coveredM
        // Judged by where the trip stood when the car entered the segment, never by how far it
        // has come since: the traversal that carries the trip out of the start zone is dropped
        // unreported, as nearDestination() drops the one in progress at the other end, and so
        // is any later minute on that same road.
        if (t.startDrivenM < startZoneM || t.seg in startZoneSegs) return
        // A segment where the GPS lost its speed even once is not reported: its median would
        // describe only part of the drive.
        if (t.speedUnknown || t.speeds.isEmpty()) return
        if (t.speeds.size < MIN_FIXES && coveredM < MIN_COVERED_M) return
        val kph = median(t.speeds)
        if (kph < 0 || kph > MAX_KPH) return
        pending.add(Sample(t.seg, kph, t.lastTimeMs, t.segLat, t.segLon))
    }

    /** Segment keys of the samples waiting to be posted, oldest first; what tests check instead of a relay. */
    internal fun queuedSegments(): List<String> = synchronized(lock) { pending.map { it.seg } }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Posts waiting samples, up to 60 per request. Failed batches are kept until they are too old. */
    suspend fun flush() {
        sendLock.withLock {
            while (true) {
                val batch: List<Sample>
                synchronized(lock) {
                    if (!active) return
                    val now = System.currentTimeMillis()
                    pending.removeAll { now - it.timeMs > MAX_AGE_MS }
                    if (pending.isEmpty()) return
                    batch = ArrayList(pending.subList(0, minOf(BATCH_MAX, pending.size)))
                }
                val relay = navRelay(appContext) ?: run {
                    // Without COMMS there is nowhere to send to; keeping samples would only grow memory.
                    synchronized(lock) { pending.removeAll(batch.toSet()) }
                    return
                }
                val clockOffsetMs = relay.relayNow() - System.currentTimeMillis()
                val arr = JSONArray()
                for (s in batch) {
                    val o = JSONObject()
                        .put("seg", s.seg)
                        .put("kph", Math.round(s.kph * 10) / 10.0)
                        // The relay checks freshness against its own clock, not this phone's.
                        .put("t", (s.timeMs + clockOffsetMs) / 1000)
                    if (!s.lat.isNaN() && !s.lon.isNaN()) {
                        o.put("lat", Math.round(s.lat * 1e5) / 1e5).put("lon", Math.round(s.lon * 1e5) / 1e5)
                    }
                    arr.put(o)
                }
                try {
                    relay.trafficProbe(region, trip.current(), arr)
                } catch (e: RelayException) {
                    // No token left (NO_TOKENS is negative) is a deferral: the live loop tops up each minute.
                    val refused = e.code in 400..499 && e.code != 408 && e.code != 429
                    if (!refused) {
                        Log.i(TAG, "Probe upload deferred: ${e.message}")
                        return
                    }
                    // The relay will never take this batch; retrying it would block every later one.
                    Log.w(TAG, "Probe batch refused (${e.code}): ${e.message}")
                } catch (e: IOException) {
                    Log.i(TAG, "Probe upload deferred: ${e.message}")
                    return
                }
                synchronized(lock) { pending.removeAll(batch.toSet()) }
            }
        }
    }
}
