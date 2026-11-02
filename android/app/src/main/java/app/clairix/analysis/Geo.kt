package app.clairix.analysis

import android.location.Location
import android.os.SystemClock
import app.clairix.LatLon
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6371008.8

/** Two observations this far apart prove the emitter physically moved with you. */
const val FOLLOW_DISPLACEMENT_M = 300.0

/** Fixes closer together than this are treated as the same place. */
const val PLACE_RADIUS_M = 100.0

/** Below this speed we are not travelling, so co-presence proves nothing. */
const val MOVING_SPEED_MS = 1.5

/** A fix reporting worse accuracy than this (cell or coarse Wi-Fi positioning) places nothing. */
const val MAX_FIX_ACCURACY_M = 50f

/**
 * A fix older than this says nothing about where the phone is now. Once the phone
 * has settled it asks for a position only every few minutes, and a sighting long
 * after the last fix would otherwise be pinned to wherever that fix was.
 */
const val MAX_FIX_AGE_MS = 60_000L

/** A place counts towards the span only once the device was heard there this many times. */
const val MIN_PLACE_SIGHTINGS = 2

/**
 * Between hearing a device at one place and hearing it at another, the phone's own
 * odometer must have advanced by at least this share of the two places' separation.
 * Not the whole of it: the odometer ([LocationTrack.travelledM]) runs one fix
 * behind, and the last sighting at a place may be a place radius from its centre.
 */
const val TRAVEL_CONSISTENCY = 0.5

data class Fix(val lat: Double, val lon: Double, val speed: Float?, val time: Long, val accuracy: Float? = null)

/**
 * When the fix was taken, not when it was read, on the phone's own clock: how old a
 * fix is decides whether a sighting can be placed by it (see [MAX_FIX_AGE_MS]).
 *
 * The age comes from the monotonic clock the fix carries
 * ([Location.getElapsedRealtimeNanos]). [Location.getTime] is satellite or network
 * time, and every fix time is compared with [System.currentTimeMillis]: on a phone
 * whose clock is set wrong, by hand or by someone who wants its fixes to look stale,
 * every fix would read as minutes old, or a drive would end the moment it began.
 * [Location.getTime] is used only when the fix carries no monotonic time.
 */
fun Location.toFix(): Fix {
    val realtimeNanos = elapsedRealtimeNanos
    val at = if (realtimeNanos > 0L) {
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - realtimeNanos).coerceAtLeast(0L) / 1_000_000L
    } else {
        time.takeIf { it > 0L } ?: System.currentTimeMillis()
    }
    return Fix(latitude, longitude, if (hasSpeed()) speed else null, at, if (hasAccuracy()) accuracy else null)
}

fun haversine(a: Fix, b: Fix): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * The set of places one device was heard from, and whether the phone travelled
 * between them. Kept small by collapsing fixes that land in the same spot, so the
 * pairwise span stays cheap to compute.
 *
 * A place on its own proves nothing: a position source that is wrong for one fix
 * puts a device that never moved four hundred metres away. So a sighting is placed
 * only by a fresh, reasonably accurate fix; a place counts only once the device was
 * heard there more than once; and two places count as a journey only when the
 * phone's own odometer advanced by (most of) their separation between hearing the
 * device at the one and at the other. A fix that jumps while the phone sits still
 * fails the last test, because [LocationTrack] does not count a jump as travel.
 */
class ObservationArea(private val maxPlaces: Int = 60) {

    private class Place(lat: Double, lon: Double, travelledM: Double) {
        val fix = Fix(lat, lon, null, 0)
        /** Packets heard here. One packet under one fix is not somewhere the device was. */
        var sightings = 0
        /** The phone's odometer when the device was last heard here. */
        var travelledLastM = travelledM
        /** Places the phone travelled between hearing the device at this one and at the other. */
        val reached = HashSet<Place>()
    }

    private val places = ArrayList<Place>()
    private var spanCache: Double? = 0.0

    val size: Int get() = places.size

    /**
     * The device was heard while the phone's position was [fix] and its odometer read
     * [travelledM]. Coarse fixes (cell or Wi-Fi positioning) and fixes older than
     * [MAX_FIX_AGE_MS] at [now] place nothing.
     */
    fun add(fix: Fix, now: Long, travelledM: Double) {
        if (fix.accuracy != null && fix.accuracy > MAX_FIX_ACCURACY_M) return
        if (now - fix.time > MAX_FIX_AGE_MS) return
        var changed = false
        val place = places.firstOrNull { haversine(it.fix, fix) <= PLACE_RADIUS_M }
            ?: Place(fix.lat, fix.lon, travelledM).also {
                places.add(it)
                changed = true
            }
        place.sightings++
        if (place.sightings == MIN_PLACE_SIGHTINGS) changed = true
        // Since the device was last heard at each other place, has the phone travelled
        // (most of) the way from there to here?
        for (other in places) {
            if (other === place || other in place.reached) continue
            val since = travelledM - other.travelledLastM
            if (since <= 0.0) continue
            if (since >= TRAVEL_CONSISTENCY * haversine(other.fix, place.fix)) {
                place.reached.add(other)
                other.reached.add(place)
                changed = true
            }
        }
        place.travelledLastM = travelledM

        // Drop the least-visited place rather than the oldest: somewhere passed
        // through once matters less than somewhere you keep returning to.
        if (places.size > maxPlaces) {
            evictLeastVisited()
            changed = true
        }
        if (changed) spanCache = null
    }

    /**
     * Widest separation between any two places this device was heard from, counting
     * only places heard at more than once and pairs the phone travelled between.
     * This single number is what separates a tracker on your car from a neighbour's
     * tag on the other side of a wall.
     */
    fun span(): Double {
        spanCache?.let { return it }
        var widest = 0.0
        for (i in places.indices) {
            val a = places[i]
            if (a.sightings < MIN_PLACE_SIGHTINGS) continue
            for (j in i + 1 until places.size) {
                val b = places[j]
                if (b.sightings < MIN_PLACE_SIGHTINGS || b !in a.reached) continue
                val d = haversine(a.fix, b.fix)
                if (d > widest) widest = d
            }
        }
        spanCache = widest
        return widest
    }

    fun movedWithUs(thresholdM: Double = FOLLOW_DISPLACEMENT_M): Boolean = span() >= thresholdM

    /**
     * Folds [other]'s places into this area, for two keys that turn out to be one
     * device. A place both heard at adds up its sightings. Whether the phone travelled
     * between a place of the one history and a place of the other is judged by how far
     * the odometer moved between the device's last sightings at each, the same test
     * [add] applies as sightings arrive.
     */
    fun merge(other: ObservationArea) {
        if (other === this) return
        val mapped = HashMap<Place, Place>()
        for (q in other.places) {
            val own = places.firstOrNull { haversine(it.fix, q.fix) <= PLACE_RADIUS_M }
            val p = if (own != null) {
                own.sightings += q.sightings
                own.travelledLastM = max(own.travelledLastM, q.travelledLastM)
                own
            } else {
                Place(q.fix.lat, q.fix.lon, q.travelledLastM).also { it.sightings = q.sightings }
            }
            for (mine in places) {
                if (mine === p || mine in p.reached) continue
                if (abs(q.travelledLastM - mine.travelledLastM) >= TRAVEL_CONSISTENCY * haversine(mine.fix, p.fix)) {
                    p.reached.add(mine)
                    mine.reached.add(p)
                }
            }
            if (own == null) places.add(p)
            mapped[q] = p
        }
        // What the other history already knew the phone travelled between.
        for (q in other.places) {
            val p = mapped[q] ?: continue
            for (r in q.reached) {
                val pr = mapped[r] ?: continue
                if (pr === p) continue
                p.reached.add(pr)
                pr.reached.add(p)
            }
        }
        while (places.size > maxPlaces) evictLeastVisited()
        spanCache = null
    }

    /** Forgets this area's places and takes [other]'s instead. */
    fun replaceWith(other: ObservationArea) {
        clear()
        merge(other)
    }

    /** Forgets every place. */
    fun clear() {
        places.clear()
        spanCache = 0.0
    }

    /** Every place the device was heard from, counted towards the span or not: where to look. */
    fun points(): List<LatLon> = places.map { LatLon(it.fix.lat, it.fix.lon) }

    private fun evictLeastVisited() {
        var worst = 0
        for (i in 1 until places.size) if (places[i].sightings < places[worst].sightings) worst = i
        val gone = places.removeAt(worst)
        for (p in places) p.reached.remove(gone)
    }
}

/**
 * Where we have been, and whether we are actually going anywhere. Updated on the main
 * thread and read from the packet thread and the service's loop, hence volatile.
 *
 * [travelledM] is an odometer of travel, not of fixes. A position that jumps away and
 * straight back is a bad fix, not a journey, so each hop is credited one fix late,
 * once the fix after it shows the phone did not come straight back; the number lags
 * the phone by one fix for it. And a hop counts only with evidence of motion behind
 * it (see [travelled]): a Wi-Fi position wandering about a still phone, or jumping to
 * the wrong street and staying there, is the position source changing its mind.
 */
class LocationTrack {

    @Volatile
    var current: Fix? = null
        private set

    @Volatile
    var travelledM: Double = 0.0
        private set

    @Volatile
    private var previous: Fix? = null

    /** The last fix the odometer has counted up to. */
    private var anchor: Fix? = null

    /** The newest fix after [anchor]; its hop is credited once the fix after it shows it was travel. */
    private var pending: Fix? = null

    fun update(fix: Fix) {
        previous = current
        current = fix
        val from = anchor
        if (from == null) {
            anchor = fix
            return
        }
        val hop = pending
        if (hop == null) {
            pending = fix
            return
        }
        val out = haversine(from, hop)
        val back = haversine(from, fix)
        val onward = haversine(hop, fix)
        if (back < out * SPIKE_RATIO && back < onward * SPIKE_RATIO) {
            // Out and straight back: a bad fix. Forgotten; the anchor stands.
            pending = fix
            return
        }
        if (travelled(from, hop, out)) travelledM += out
        anchor = hop
        pending = fix
    }

    /**
     * Whether the [out] metres from [from] to [to] were the phone going somewhere. A fix
     * that reports a speed settles it: satellite speed is measured from the signal's
     * Doppler shift, not from position, so a position that jumps on a phone at rest
     * still reports it at rest; the hop must also be one that speed could cover. A fix
     * without speed (Wi-Fi or cell positioning) vouches only for a short hop made at
     * walking pace or better: anything longer, or slower, is the position wandering.
     */
    private fun travelled(from: Fix, to: Fix, out: Double): Boolean {
        val seconds = (to.time - from.time) / 1000.0
        if (out <= MIN_HOP_M || seconds <= 0.0) return false
        val implied = out / seconds
        val reported = listOfNotNull(from.speed, to.speed).maxOrNull()
            ?: return out <= NO_SPEED_HOP_M && implied >= WALKING_SPEED_MS
        return reported >= WALKING_SPEED_MS && implied <= SPEED_SLACK * reported + WALKING_SPEED_MS
    }

    fun hasFix(): Boolean = current != null

    /**
     * Where the phone is, is no longer known (an app is supplying fake positions; see
     * [MockFixes]): [current] goes, so nothing is placed by a fix from before, and
     * [hasFix] reads false until the next [update]. The odometer and the fixes it counts
     * from are kept, and the next real fix goes on from them.
     */
    fun forget() {
        previous = null
        current = null
    }

    fun isMoving(): Boolean {
        val c = current ?: return false
        c.speed?.let { return it >= MOVING_SPEED_MS }
        val p = previous ?: return false
        val dt = (c.time - p.time) / 1000.0
        if (dt <= 0) return false
        return haversine(p, c) / dt >= MOVING_SPEED_MS
    }

    private companion object {
        /** Hops shorter than this are fix jitter, not travel. */
        const val MIN_HOP_M = 5.0
        /** A hop longer than this is credited only when one of its fixes reports a speed. */
        const val NO_SPEED_HOP_M = 100.0
        /** A return to within this share of both legs of an excursion makes the excursion a spike. */
        const val SPIKE_RATIO = 0.5
        /** A slow walk, as [MotionPolicy] judges it: satellite speed on a phone at rest stays well under it. */
        const val WALKING_SPEED_MS = MotionPolicy.MOVING_SPEED_MS
        /** How far a hop may outrun the reported speed (twice it, plus a walk) and still be that speed's doing. */
        const val SPEED_SLACK = 2.0
    }
}
