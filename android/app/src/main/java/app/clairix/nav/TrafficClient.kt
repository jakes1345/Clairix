package app.clairix.nav

import android.content.Context
import android.util.Log
import app.clairix.comms.CommsRepository
import app.clairix.comms.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * A traffic incident: reported by drivers through the relay, proxied from HERE / TomTom, or a
 * work zone from the state DOT feeds ([origin]). Times are epoch seconds; 0 means unknown.
 * [geom] is the affected stretch in travel order, when the source gives one.
 */
data class Incident(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val seg: String?,
    val source: String,
    val confirmations: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val description: String?,
    val geom: List<LatLon>? = null,
    val road: String? = null,
    val delaySec: Int? = null,
    val origin: Origin = Origin.RELAY,
) {
    enum class Origin { RELAY, PROVIDER, WORK_ZONE }

    /** Only driver reports live on the relay, so only they can be confirmed or cleared. */
    val votable: Boolean get() = origin == Origin.RELAY

    companion object {
        const val POLICE = "police"
        const val CRASH = "crash"
        const val HAZARD = "hazard"
        const val CLOSURE = "closure"
        const val JAM = "jam"
        const val LANE_CLOSURE = "lane_closure"
        const val WORK_ZONE = "work_zone"
        const val DETOUR = "detour"
        const val RESTRICTION = "restriction"
        const val OTHER = "other"
        /** Types a driver can report. */
        val TYPES = listOf(POLICE, CRASH, HAZARD, CLOSURE, JAM)
        private val PROVIDER_TYPES = setOf(CRASH, CLOSURE, LANE_CLOSURE, WORK_ZONE, JAM, HAZARD, POLICE, OTHER)

        /** One incident from the relay's HERE / TomTom proxy; null when it lacks an id or a position. */
        fun fromProviderJson(o: JSONObject): Incident? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val geom = o.optJSONArray("geom")?.let { parseGeom(it) }?.takeIf { it.isNotEmpty() }
            var lat = o.optDouble("lat", Double.NaN)
            var lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) {
                val first = geom?.firstOrNull() ?: return null
                lat = first.lat; lon = first.lon
            }
            fun text(k: String) = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() }
            fun epoch(k: String): Long = if (o.isNull(k)) 0L else o.optDouble(k, 0.0).takeIf { it > 0 }?.toLong() ?: 0L
            return Incident(
                id = id,
                type = o.optString("type").takeIf { it in PROVIDER_TYPES } ?: OTHER,
                lat = lat,
                lon = lon,
                seg = null,
                source = text("source") ?: "provider",
                confirmations = 0,
                createdAt = epoch("start"),
                expiresAt = epoch("end"),
                description = text("description"),
                geom = geom,
                road = text("road"),
                delaySec = if (o.isNull("delay_s")) null else o.optDouble("delay_s", Double.NaN).takeIf { !it.isNaN() && it >= 0 }?.toInt(),
                origin = Origin.PROVIDER,
            )
        }

        /** A work zone shown alongside incidents; it sits at the start of its stretch, where a driver meets it. */
        fun fromWorkZone(z: WorkZone): Incident {
            val type = when {
                z.impact == WorkZone.IMPACT_ALL_CLOSED -> CLOSURE
                else -> z.type
            }
            return Incident(
                id = z.id,
                type = type,
                lat = z.geom.first().lat,
                lon = z.geom.first().lon,
                seg = null,
                source = z.source,
                confirmations = 0,
                createdAt = z.start ?: 0L,
                expiresAt = z.end ?: 0L,
                description = z.description,
                geom = z.geom.takeIf { it.size >= 2 },
                road = z.road,
                delaySec = null,
                origin = Origin.WORK_ZONE,
            )
        }

        private fun parseGeom(arr: JSONArray): List<LatLon> {
            val out = ArrayList<LatLon>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                val lat = p.optDouble(0, Double.NaN)
                val lon = p.optDouble(1, Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) out.add(LatLon(lat, lon))
            }
            return out
        }

        fun fromJson(o: JSONObject): Incident = Incident(
            id = o.getString("id"),
            type = o.getString("type"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            seg = if (o.isNull("seg")) null else o.optString("seg").takeIf { it.isNotEmpty() },
            source = o.optString("source", "user"),
            confirmations = o.optInt("confirmations", 1),
            createdAt = o.optLong("createdAt", 0L),
            expiresAt = o.optLong("expiresAt", 0L),
            description = if (o.isNull("description")) null else o.optString("description").takeIf { it.isNotEmpty() },
        )
    }
}

/** Provider incidents in a relay `/v1/traffic/incidents` reply that have not ended by [nowSec]. */
internal fun parseProviderIncidents(json: JSONObject, nowSec: Long): List<Incident> {
    val arr = json.optJSONArray("incidents") ?: return emptyList()
    val out = ArrayList<Incident>(arr.length())
    for (i in 0 until arr.length()) {
        val inc = arr.optJSONObject(i)?.let { Incident.fromProviderJson(it) } ?: continue
        if (inc.expiresAt == 0L || inc.expiresAt > nowSec) out.add(inc)
    }
    return out
}

/** The relay's `source` for work zones its own WZDX_FEEDS cron turned into incidents. */
internal const val RELAY_WZDX_SOURCE = "wzdx"

/**
 * How close a relay WZDx incident (a point at its zone's midpoint) must lie to an active work
 * zone's stretch to be the same zone. The aggregator simplifies stretches to 10 m; 30 m stays
 * inside one carriageway, so the other direction of a divided road or a parallel ramp is kept.
 */
internal const val WZDX_SAME_ZONE_M = 30.0

/**
 * What the relay's WZDx description says about its zone: the road names and travel direction,
 * from its "Work zone on <roads>[ (<direction>)]: <text>" form. Empty when the text is in
 * another form; then only the distance decides.
 */
internal data class RelayWzdxHint(val roads: Set<String>, val direction: String?)

private val RELAY_WZDX_DESCRIPTION = Regex("""^Work zone on (.+?)(?: \(([a-z-]+)\))?(?:: .*)?$""", RegexOption.DOT_MATCHES_ALL)

internal fun relayWzdxHint(description: String?): RelayWzdxHint {
    val m = description?.let { RELAY_WZDX_DESCRIPTION.matchEntire(it.trim()) } ?: return RelayWzdxHint(emptySet(), null)
    return RelayWzdxHint(roadKeys(m.groupValues[1]), normalizeDirection(m.groupValues[2]))
}

/**
 * Road names as comparable keys: "I-71", "Interstate 71" and "i 71" are all "I71"; "US-33" and
 * "U.S. 33" are "US33"; "SR-315" and "State Route 315" are "SR315". Several names may be joined
 * with "/" (the work-zone files) or "," (the relay).
 */
internal fun roadKeys(text: String?): Set<String> {
    if (text.isNullOrBlank()) return emptySet()
    return text.split('/', ',', ';').mapNotNull { raw ->
        var r = " " + raw.uppercase(Locale.ROOT).replace('.', ' ') + " "
        for ((from, to) in ROAD_WORDS) r = r.replace(from, to)
        r.filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }
    }.toSet()
}

private val ROAD_WORDS = listOf(
    " INTERSTATE " to " I ",
    " U S " to " US ",
    " STATE ROUTE " to " SR ",
    " STATE HIGHWAY " to " SR ",
    " STATE ROAD " to " SR ",
    " HIGHWAY " to " HWY ",
)

/** A travel direction to compare, or null when it says none ("both", "unknown", blank). */
internal fun normalizeDirection(text: String?): String? {
    val d = text?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return d.takeUnless { it in setOf("both", "unknown", "undefined", "none", "n/a", "all") }
}

/** An active work zone with its bounding box padded by [WZDX_SAME_ZONE_M], for a cheap first test. */
private class ZoneBox(val zone: WorkZone) {
    val minLat: Double; val maxLat: Double; val minLon: Double; val maxLon: Double
    val roads = roadKeys(zone.road)
    val direction = normalizeDirection(zone.direction)

    init {
        var a = Double.POSITIVE_INFINITY; var b = Double.NEGATIVE_INFINITY
        var c = Double.POSITIVE_INFINITY; var d = Double.NEGATIVE_INFINITY
        for (p in zone.geom) {
            a = minOf(a, p.lat); b = maxOf(b, p.lat); c = minOf(c, p.lon); d = maxOf(d, p.lon)
        }
        val padLat = WZDX_SAME_ZONE_M / RoadGeo.M_PER_DEG_LAT
        val padLon = WZDX_SAME_ZONE_M / (RoadGeo.M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(maxOf(abs(a), abs(b)))).coerceAtLeast(1e-6))
        minLat = a - padLat; maxLat = b + padLat; minLon = c - padLon; maxLon = d + padLon
    }

    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

/**
 * Whether the relay's WZDx incident [inc] is its copy of [box]'s zone: on the zone's stretch, and,
 * where both name them, on the same road and in the same direction. Two zones 30 m apart on
 * different roads (a ramp) or carriageways stay apart.
 */
private fun isSameZone(inc: Incident, hint: RelayWzdxHint, box: ZoneBox): Boolean {
    if (!box.contains(inc.lat, inc.lon)) return false
    if (hint.roads.isNotEmpty() && box.roads.isNotEmpty() && hint.roads.none { it in box.roads }) return false
    if (hint.direction != null && box.direction != null && hint.direction != box.direction) return false
    return nearStretch(inc.lat, inc.lon, box.zone.geom, WZDX_SAME_ZONE_M)
}

/**
 * Driver reports, provider incidents and active work zones as one list, deduplicated by id
 * (earlier sources win) and without anything that has ended. A work zone the relay's WZDX_FEEDS
 * cron also reported is kept once, as the work zone: it carries the stretch, road and end time,
 * while the relay's copy has a random id and only a point.
 */
internal fun mergeIncidents(
    relay: List<Incident>, provider: List<Incident>, zones: List<WorkZone>, nowSec: Long,
): List<Incident> {
    val out = LinkedHashMap<String, Incident>()
    val activeZones = zones.filter { it.isActive(nowSec) && it.geom.isNotEmpty() }
    val boxes by lazy(LazyThreadSafetyMode.NONE) { activeZones.map(::ZoneBox) }
    for (inc in relay) {
        if (inc.expiresAt != 0L && inc.expiresAt <= nowSec) continue
        if (inc.source == RELAY_WZDX_SOURCE && activeZones.isNotEmpty()) {
            val hint = relayWzdxHint(inc.description)
            if (boxes.any { isSameZone(inc, hint, it) }) continue
        }
        out.putIfAbsent(inc.id, inc)
    }
    for (inc in provider) if (inc.expiresAt == 0L || inc.expiresAt > nowSec) out.putIfAbsent(inc.id, inc)
    for (z in activeZones) out.putIfAbsent(z.id, Incident.fromWorkZone(z))
    return out.values.toList()
}

/**
 * True when ([lat], [lon]) lies within [radiusM] of the polyline [geom] (or of its only point).
 * Uses a local equirectangular projection, exact enough at the tens of metres this is asked about.
 */
internal fun nearStretch(lat: Double, lon: Double, geom: List<LatLon>, radiusM: Double): Boolean {
    if (geom.isEmpty()) return false
    val mPerDegLon = RoadGeo.M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(lat))
    fun x(p: LatLon) = (p.lon - lon) * mPerDegLon
    fun y(p: LatLon) = (p.lat - lat) * RoadGeo.M_PER_DEG_LAT
    val r2 = radiusM * radiusM
    if (geom.size == 1) return x(geom[0]).let { it * it } + y(geom[0]).let { it * it } <= r2
    for (i in 1 until geom.size) {
        val ax = x(geom[i - 1]); val ay = y(geom[i - 1])
        val dx = x(geom[i]) - ax; val dy = y(geom[i]) - ay
        val len2 = dx * dx + dy * dy
        // The point is the origin, so the closest point on the segment is a + t·d with t from -a·d.
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        if (cx * cx + cy * cy <= r2) return true
    }
    return false
}

/**
 * Half the relay's 1° limit: at most 3 × 3 of its 0.25° tiles instead of 5 × 5, so a car's
 * box stays fresh within the relay's per-request upstream budget and a small group stays
 * inside the HERE / TomTom daily caps; ~55 km is still half an hour of motorway ahead.
 */
internal const val INCIDENT_BOX_SPAN_DEG = 0.5

/**
 * The box to ask the incident proxy about: the leading stretch of [points] (the route ahead, or
 * just the car's position) that fits in [maxSpanDeg] once padded by [marginDeg], widened to at
 * least [minSpanDeg] so a lone position still covers its surroundings. Returns
 * [minLat, minLon, maxLat, maxLon], or null for no points.
 */
internal fun incidentBox(
    points: List<LatLon>, maxSpanDeg: Double = INCIDENT_BOX_SPAN_DEG, marginDeg: Double = 0.02, minSpanDeg: Double = 0.25,
): DoubleArray? {
    val first = points.firstOrNull() ?: return null
    var minLat = first.lat; var maxLat = first.lat
    var minLon = first.lon; var maxLon = first.lon
    val inner = maxSpanDeg - 2 * marginDeg
    for (p in points) {
        val nMinLat = minOf(minLat, p.lat); val nMaxLat = maxOf(maxLat, p.lat)
        val nMinLon = minOf(minLon, p.lon); val nMaxLon = maxOf(maxLon, p.lon)
        if (nMaxLat - nMinLat > inner || nMaxLon - nMinLon > inner) break
        minLat = nMinLat; maxLat = nMaxLat; minLon = nMinLon; maxLon = nMaxLon
    }
    minLat -= marginDeg; maxLat += marginDeg; minLon -= marginDeg; maxLon += marginDeg
    val minSpan = minOf(minSpanDeg, maxSpanDeg)
    if (maxLat - minLat < minSpan) {
        val c = (minLat + maxLat) / 2; minLat = c - minSpan / 2; maxLat = c + minSpan / 2
    }
    if (maxLon - minLon < minSpan) {
        val c = (minLon + maxLon) / 2; minLon = c - minSpan / 2; maxLon = c + minSpan / 2
    }
    minLat = minLat.coerceIn(-90.0, 90.0); maxLat = maxLat.coerceIn(-90.0, 90.0)
    minLon = minLon.coerceIn(-180.0, 180.0); maxLon = maxLon.coerceIn(-180.0, 180.0)
    return doubleArrayOf(minLat, minLon, maxLat, maxLon)
}

/**
 * The relay client COMMS already uses, or null when this phone is not registered with a relay;
 * traffic then is simply unavailable. Same path as CommsService: init is idempotent.
 */
internal fun navRelay(context: Context): RelayClient? = try {
    CommsRepository.init(context.applicationContext)
    if (CommsRepository.isRegistered()) CommsRepository.relayClient() else null
} catch (e: RuntimeException) {
    Log.w("Nav", "COMMS unavailable for traffic: ${e.message}")
    null
}

/**
 * Crowd-sourced traffic for one map region: live segment speeds (refreshed every minute while
 * navigating), hour-of-week typical speeds (cached on disk for a week) and incident reports.
 *
 * [liveKph] and [typicalKph] are called from the router's inner loop, so they only read
 * in-memory maps and never touch the network or disk.
 */
class TrafficClient(context: Context, val region: String, private val trip: TripPseudonym) : SpeedSource {

    private companion object {
        const val TAG = "TrafficClient"
        const val LIVE_INTERVAL_MS = 60_000L
        /** The relay's live window is 10 min; past half that without a refresh our copy is too old to trust. */
        const val LIVE_STALE_MS = 5 * 60_000L
        const val TYPICAL_FRESH_SEC = 7L * 24 * 3600
        const val HOURS_PER_WEEK = 168
        const val PROVIDER_NAV_INTERVAL_MS = 2 * 60_000L
        /** Provider incidents not refreshed for this long are dropped rather than shown as current. */
        const val PROVIDER_STALE_MS = 15 * 60_000L
    }

    private val appContext = context.applicationContext
    private val cacheFile = File(File(appContext.filesDir, "nav"), "typical-$region.json")

    private val live = ConcurrentHashMap<String, Float>()
    @Volatile private var liveFetchedAtMs = 0L

    /** Hour-of-week km/h per segment; NaN where the relay has no data for that hour. */
    private val typical = ConcurrentHashMap<String, FloatArray>()
    /** When each segment's profile was fetched (epoch seconds), including segments the relay knew nothing about. */
    private val typicalFetchedAt = ConcurrentHashMap<String, Long>()
    private val typicalLock = Mutex()
    @Volatile private var typicalLoaded = false

    /** Guards the incident sources below; held only to read or replace them, never for a merge. */
    private val incidentLock = Any()
    /** Serialises [publishIncidents], so merges finish in the order their snapshots were taken. */
    private val publishLock = Any()
    private var relayIncidents: List<Incident> = emptyList()
    private var workZones: List<WorkZone> = emptyList()
    @Volatile private var providerFetchedAtMs = 0L
    @Volatile private var providerAttemptMs = 0L
    private val providerMutex = Mutex()

    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    /** Driver reports, HERE / TomTom incidents and active work zones, deduplicated by id. */
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    private val _providerIncidents = MutableStateFlow<List<Incident>>(emptyList())
    /** The latest HERE / TomTom incidents around the focus; RoadClosures maps their closures onto the graph. */
    val providerIncidents: StateFlow<List<Incident>> = _providerIncidents.asStateFlow()

    /**
     * Where provider incidents matter: the route ahead while driving or previewing, else the
     * car's position. Set by the owner of this client; null or empty skips provider refreshes.
     */
    @Volatile var focus: (() -> List<LatLon>?)? = null

    private val _available = MutableStateFlow(false)
    /** True once the relay has answered a traffic request; false without COMMS or while offline. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var liveJob: Job? = null

    // ── SpeedSource ─────────────────────────────────────────────────────

    override fun liveKph(segmentKey: String): Double? {
        if (System.currentTimeMillis() - liveFetchedAtMs > LIVE_STALE_MS) return null
        return live[segmentKey]?.toDouble()
    }

    override fun typicalKph(segmentKey: String, epochSec: Long): Double? {
        val h = typical[segmentKey] ?: return null
        val v = h[hourOfWeek(epochSec)]
        return if (v.isNaN()) null else v.toDouble()
    }

    /** Index into the relay's 168-hour profile: UTC, Monday 00:00 = 0. */
    private fun hourOfWeek(epochSec: Long): Int {
        val days = Math.floorDiv(epochSec, 86_400L)
        // 1970-01-01 was a Thursday, which is day 3 counting Monday as 0.
        val dow = Math.floorMod(days + 3, 7L).toInt()
        val hour = (Math.floorMod(epochSec, 86_400L) / 3600).toInt()
        return dow * 24 + hour
    }

    // ── Live speeds and incidents ───────────────────────────────────────

    /** Refreshes live speeds and incidents every minute on [scope] until [stopLive]. */
    @Synchronized
    fun startLive(scope: CoroutineScope) {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshLive()
                refreshProviders(PROVIDER_NAV_INTERVAL_MS)
                // For the next minute's requests, after this minute's: a fetch right
                // before a request would let the relay pair the two.
                prefetchTokens()
                delay(LIVE_INTERVAL_MS)
            }
        }
    }

    /**
     * Keeps the anonymous traffic and report tokens stocked, apart from the
     * requests that spend them. Called when a map session opens and once a
     * minute while live; a failure (offline) is harmless, the next minute tries again.
     */
    suspend fun prefetchTokens() = withContext(Dispatchers.IO) {
        val relay = navRelay(appContext) ?: return@withContext
        try {
            relay.topUpTrafficTokens()
        } catch (e: IOException) {
            Log.i(TAG, "Traffic tokens not fetched: ${e.message}")
        }
    }

    @Synchronized
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    /** One fetch of live speeds and incidents. Returns false when the relay could not be reached. */
    suspend fun refreshLive(): Boolean = withContext(Dispatchers.IO) {
        val relay = navRelay(appContext) ?: run { _available.value = false; return@withContext false }
        try {
            val json = relay.trafficLive(region)
            val segs = json.optJSONObject("segments") ?: JSONObject()
            val fresh = HashMap<String, Float>(segs.length() * 2)
            val keys = segs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val kph = segs.optJSONObject(k)?.optDouble("kph", Double.NaN) ?: Double.NaN
                if (!kph.isNaN() && kph >= 0) fresh[k] = kph.toFloat()
            }
            live.keys.retainAll(fresh.keys)
            live.putAll(fresh)
            liveFetchedAtMs = System.currentTimeMillis()
            json.optJSONArray("incidents")?.let { arr ->
                val parsed = parseIncidents(arr)
                synchronized(incidentLock) { relayIncidents = parsed }
            }
            publishIncidents()
            _available.value = true
            true
        } catch (e: IOException) {
            Log.i(TAG, "Live traffic unavailable: ${e.message}")
            _available.value = false
            pruneExpired()
            false
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed live traffic: ${e.message}")
            false
        }
    }

    private fun parseIncidents(arr: JSONArray): List<Incident> {
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Incident>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val inc = try { Incident.fromJson(o) } catch (e: JSONException) { continue }
            if (inc.expiresAt == 0L || inc.expiresAt > now) out.add(inc)
        }
        return out
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis() / 1000
        synchronized(incidentLock) { relayIncidents = relayIncidents.filter { it.expiresAt == 0L || it.expiresAt > now } }
        publishIncidents()
    }

    private fun upsertIncident(inc: Incident) {
        synchronized(incidentLock) { relayIncidents = relayIncidents.filter { it.id != inc.id } + inc }
        publishIncidents()
    }

    private fun removeIncident(id: String) {
        synchronized(incidentLock) { relayIncidents = relayIncidents.filter { it.id != id } }
        publishIncidents()
    }

    /**
     * Rebuilds [incidents] from its three sources; also called on a timer so work zones start and
     * end on time. The merge runs on the caller's thread outside [incidentLock], so a large work-zone
     * list never holds up the threads that update the sources; call it off the main thread.
     * Merges run one at a time and each takes the sources as they are when it starts, so the last
     * one to finish always reflects the latest sources.
     */
    fun publishIncidents() {
        synchronized(publishLock) {
            val now = System.currentTimeMillis() / 1000
            val relay: List<Incident>
            val provider: List<Incident>
            val zones: List<WorkZone>
            synchronized(incidentLock) {
                if (providerFetchedAtMs > 0 && System.currentTimeMillis() - providerFetchedAtMs > PROVIDER_STALE_MS) {
                    _providerIncidents.value = emptyList()
                    providerFetchedAtMs = 0L
                }
                relay = relayIncidents
                provider = _providerIncidents.value
                zones = workZones
            }
            val merged = mergeIncidents(relay, provider, zones, now)
            if (merged != _incidents.value) _incidents.value = merged
        }
    }

    /** Replaces the work zones shown with the incidents. Merges them in; call it off the main thread. */
    fun setWorkZones(zones: List<WorkZone>) {
        synchronized(incidentLock) { workZones = zones }
        publishIncidents()
    }

    /**
     * Fetches HERE / TomTom incidents for the box around [focus] when the last attempt is older
     * than [maxAgeMs]. Returns false when there is no focus, no relay, or the relay failed.
     */
    suspend fun refreshProviders(maxAgeMs: Long): Boolean = withContext(Dispatchers.IO) {
        providerMutex.withLock {
            if (System.currentTimeMillis() - providerAttemptMs < maxAgeMs) return@withLock true
            val points = try { focus?.invoke() } catch (e: RuntimeException) { null }
            val box = points?.let { incidentBox(it) } ?: return@withLock false
            val relay = navRelay(appContext) ?: return@withLock false
            providerAttemptMs = System.currentTimeMillis()
            try {
                val json = relay.trafficIncidents(box[0], box[1], box[2], box[3])
                val list = parseProviderIncidents(json, System.currentTimeMillis() / 1000)
                synchronized(incidentLock) {
                    _providerIncidents.value = list
                    providerFetchedAtMs = System.currentTimeMillis()
                }
                publishIncidents()
                true
            } catch (e: IOException) {
                Log.i(TAG, "Provider incidents unavailable: ${e.message}")
                publishIncidents()
                false
            } catch (e: JSONException) {
                Log.w(TAG, "Malformed provider incidents: ${e.message}")
                false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider incident box rejected: ${e.message}")
                false
            }
        }
    }

    /** Reports an incident at a position, optionally tied to the segment being driven. */
    suspend fun report(type: String, lat: Double, lon: Double, seg: String?): Result<Incident> = withContext(Dispatchers.IO) {
        require(type in Incident.TYPES) { "Unknown incident type $type" }
        val relay = navRelay(appContext) ?: return@withContext Result.failure(IOException("COMMS is not set up, so reports cannot be sent"))
        runCatching {
            val json = relay.trafficReport(region, trip.current(), type, lat, lon, seg)
            val inc = try {
                Incident.fromJson(json.getJSONObject("incident"))
            } catch (e: JSONException) {
                throw IOException("The relay returned an unexpected report: ${e.message}")
            }
            upsertIncident(inc)
            inc
        }
    }

    /** Says whether an incident is [still] there; the result is null once the relay has removed it. */
    suspend fun vote(id: String, still: Boolean): Result<Incident?> = withContext(Dispatchers.IO) {
        val relay = navRelay(appContext) ?: return@withContext Result.failure(IOException("COMMS is not set up, so votes cannot be sent"))
        runCatching {
            val json = relay.trafficVote(region, trip.current(), id, still)
            val inc = json.optJSONObject("incident")?.let {
                try { Incident.fromJson(it) } catch (e: JSONException) { throw IOException("The relay returned an unexpected incident: ${e.message}") }
            }
            if (inc == null) removeIncident(id) else upsertIncident(inc)
            inc
        }
    }

    // ── Typical speeds ──────────────────────────────────────────────────

    /**
     * Makes sure typical profiles for [segs] are in memory: loads the disk cache once, then fetches
     * segments missing or older than a week from the relay in batches of 500 and saves the cache.
     */
    suspend fun ensureTypical(segs: Collection<String>) = withContext(Dispatchers.IO) {
        typicalLock.withLock {
            if (!typicalLoaded) {
                loadTypicalCache()
                typicalLoaded = true
            }
            val now = System.currentTimeMillis() / 1000
            val wanted = LinkedHashSet<String>()
            for (s in segs) {
                val at = typicalFetchedAt[s]
                if (at == null || now - at > TYPICAL_FRESH_SEC) wanted.add(s)
            }
            if (wanted.isEmpty()) return@withLock
            val relay = navRelay(appContext) ?: return@withLock
            var changed = false
            for (batch in wanted.chunked(RelayClient.TRAFFIC_TYPICAL_MAX)) {
                val json = try {
                    relay.trafficTypical(region, batch)
                } catch (e: IOException) {
                    Log.i(TAG, "Typical traffic unavailable: ${e.message}")
                    break
                }
                val got = json.optJSONObject("segments") ?: JSONObject()
                for (s in batch) {
                    val h = got.optJSONObject(s)?.optJSONArray("h")
                    val profile = h?.let { parseProfile(it) }
                    if (profile != null) typical[s] = profile else typical.remove(s)
                    typicalFetchedAt[s] = now
                }
                changed = true
            }
            if (changed) saveTypicalCache(now)
        }
    }

    private fun parseProfile(h: JSONArray): FloatArray? {
        if (h.length() != HOURS_PER_WEEK) return null
        var any = false
        val out = FloatArray(HOURS_PER_WEEK) { i ->
            val v = if (h.isNull(i)) Double.NaN else h.optDouble(i, Double.NaN)
            if (!v.isNaN() && v > 0) { any = true; v.toFloat() } else Float.NaN
        }
        return if (any) out else null
    }

    private fun loadTypicalCache() {
        if (!cacheFile.isFile) return
        try {
            val root = JSONObject(cacheFile.readText())
            val now = System.currentTimeMillis() / 1000
            val segs = root.optJSONObject("segments") ?: return
            val keys = segs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = segs.optJSONObject(k) ?: continue
                val t = o.optLong("t", 0L)
                if (now - t > TYPICAL_FRESH_SEC) continue
                typicalFetchedAt[k] = t
                o.optJSONArray("h")?.let { parseProfile(it) }?.let { typical[k] = it }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Typical cache unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "Typical cache corrupt, discarding: ${e.message}")
            cacheFile.delete()
        }
    }

    private fun saveTypicalCache(now: Long) {
        val segs = JSONObject()
        for ((k, t) in typicalFetchedAt) {
            if (now - t > TYPICAL_FRESH_SEC) continue
            val o = JSONObject().put("t", t)
            val h = typical[k]
            if (h != null) {
                val arr = JSONArray()
                for (v in h) arr.put(if (v.isNaN()) JSONObject.NULL else v.toDouble())
                o.put("h", arr)
            } else {
                o.put("h", JSONObject.NULL)
            }
            segs.put(k, o)
        }
        val text = JSONObject().put("region", region).put("savedAt", now).put("segments", segs).toString()
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                if (!tmp.renameTo(cacheFile)) throw IOException("Could not replace ${cacheFile.name}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save typical cache: ${e.message}")
        }
    }
}
