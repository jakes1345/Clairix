package app.clairix.detect

import app.clairix.Severity
import app.clairix.Store
import app.clairix.TrackerType
import app.clairix.analysis.Fix
import app.clairix.analysis.haversine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which Bluetooth devices rode along on each car trip, and notices one
 * that keeps coming back.
 *
 * The persistence test — "with you for ten minutes across three hundred metres" —
 * catches a tracker on the first drive. What it cannot catch is one that only wakes
 * up to advertise now and then, or a day when the drive was short. Trips are a
 * natural unit for that: a thing in your own car is there every time, a thing in
 * the car next to you at the lights is there once. A device that qualifies on three
 * separate trips is in the car.
 *
 * Two keys are kept per device. The address, which is conclusive when it matches —
 * a GPS box, a Tile, most cheap trackers keep one — and, for identified tracker
 * types only, the advertisement fingerprint, which survives the address rotation of
 * an AirTag or SmartTag but cannot tell two AirTags apart. The second is therefore
 * reported a notch lower and phrased as "an AirTag", not "the same AirTag".
 *
 * A trip is any stretch at road speed, so a bus or a train is one too, and a device
 * on three of them may be the bus's own electronics or a fellow commuter's watch. A
 * finding is therefore phrased by what shows the journey was in the owner's car: a
 * car kit connected during it ([noteAccessory]) or Drive mode on ([sight]). With that,
 * it is "in your vehicle", with the places to search; without it, it "has been on
 * N of your journeys", a notch lower, with the co-traveller explanation
 * ([Finding.coTraveller], [JOURNEYS_PREFIX]).
 *
 * Trips are persisted through [Store] so the count survives the service being
 * restarted between drives, which it always is.
 */
class VehicleTrips(private val store: Store) {

    /**
     * One past trip: when, where it set off from (when a fix was in hand), and what
     * qualified on it. The start point is what separates three drives from three
     * places from three drives out of the same car park.
     */
    data class Trip(
        val start: Long, val end: Long, val addresses: Set<String>, val signatures: Set<String>,
        val startLat: Double? = null, val startLon: Double? = null,
        /**
         * The owner's own Bluetooth accessories connected to the phone at some point
         * during the trip, address to name: the car's hands-free, a watch, earbuds.
         * Not detections; what rode along *by the owner's choice*, so the Bluetooth
         * page can say which fixed address announces the car on every drive.
         */
        val accessories: Map<String, String> = emptyMap()
    )

    /** One accessory and how many remembered trips it was connected on. */
    data class AccessoryUse(val address: String, val name: String, val trips: Int)

    /** A device that has now qualified on [TRIPS_TO_ALERT] trips. */
    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String,
        val dedupeKey: String,
        /**
         * How long a repeat under [dedupeKey] stays off the timeline and out of the
         * shade. A day for a shared signature, which every separated AirTag has.
         */
        val dedupeWindowMs: Long = ADDRESS_DEDUPE_MS,
        /**
         * Nothing showed the journey was in the owner's car: the finding says "your
         * journeys", not "your vehicle", and its timeline row opens the co-traveller
         * explanation ([JOURNEYS_PREFIX]).
         */
        val coTraveller: Boolean = false
    )

    private class Seen(val address: String) {
        var first = 0L
        var last = 0L
        var count = 0
        var name = ""
        var tracker: TrackerType? = null
        var fingerprint: String? = null
        var qualified = false
        var reported = false
    }

    private val lock = Any()
    private var tripStart = 0L
    private var tripStartLat: Double? = null
    private var tripStartLon: Double? = null
    private val seen = HashMap<String, Seen>()
    private val tripAccessories = HashMap<String, String>()
    /** Something on this trip showed it was in the owner's car: a car kit connected, or Drive mode on. */
    private var tripOwnCar = false
    private val history = ArrayList<Trip>()

    init {
        synchronized(lock) { history.addAll(loadHistory()) }
    }

    val inTrip: Boolean get() = synchronized(lock) { tripStart != 0L }

    /** Trips remembered so far, oldest first. */
    fun trips(): List<Trip> = synchronized(lock) { ArrayList(history) }

    fun beginTrip(now: Long, startLat: Double? = null, startLon: Double? = null) {
        synchronized(lock) {
            if (tripStart != 0L) return
            tripStart = now
            tripStartLat = startLat
            tripStartLon = startLon
            seen.clear()
            tripAccessories.clear()
            tripOwnCar = false
        }
    }

    /**
     * Records one of the owner's connected accessories as riding along on the current
     * trip. Nothing is recorded outside a trip. A later name for the same address wins.
     * A [car] kit (a hands-free or car audio unit) connected during the trip shows the
     * trip was in the owner's car, which decides how a finding on it is phrased.
     */
    fun noteAccessory(address: String, name: String, car: Boolean = false) {
        synchronized(lock) {
            if (tripStart == 0L) return
            if (car) tripOwnCar = true
            if (tripAccessories.size >= MAX_ACCESSORIES && address !in tripAccessories) return
            tripAccessories[address] = name
        }
    }

    /** Each accessory remembered on any trip, most-used first, with the latest name it had. */
    fun accessoryUse(): List<AccessoryUse> = synchronized(lock) {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in history) for ((address, name) in t.accessories) {
            val prev = counts[address]
            counts[address] = name to ((prev?.second ?: 0) + 1)
        }
        counts.map { (address, v) -> AccessoryUse(address, v.first, v.second) }
            .sortedWith(compareByDescending<AccessoryUse> { it.trips }.thenBy { it.name })
    }

    /**
     * Earlier trips matching [has], counted so that earlier trips setting off within
     * [DISTINCT_START_M] of each other count once. A trip with no recorded start
     * cannot be told apart from any other and counts on its own.
     *
     * The point of the dedupe is a device that sits *at* a start point — in the
     * garage at home — and qualifies on every drive from there: such a device can only
     * ever show one distinct start among the earlier trips, so with the current trip
     * it reaches two, never [TRIPS_TO_ALERT]. The current trip is not part of the
     * dedupe. It used to seed it, and then a commuter's tracker — home to work, work
     * to home, home to work — lost the earlier home start to the current one and
     * stalled at two forever, however many days it rode along.
     */
    private fun earlierTrips(has: (Trip) -> Boolean): Int {
        val starts = ArrayList<Pair<Double, Double>>()
        var n = 0
        for (t in history) {
            if (!has(t)) continue
            val lat = t.startLat
            val lon = t.startLon
            if (lat == null || lon == null) { n++; continue }
            if (starts.any { haversine(Fix(it.first, it.second, null, 0L), Fix(lat, lon, null, 0L)) < DISTINCT_START_M }) continue
            starts.add(lat to lon)
            n++
        }
        return n
    }

    /**
     * Records a sighting during the current trip. Returns a [Finding] the first time
     * this device qualifies for the trip and has already qualified on enough earlier
     * trips to make the total [TRIPS_TO_ALERT]; null in every other case, including
     * when no trip is under way. [driveMode] says Drive mode is on, which shows the trip
     * is in the owner's car.
     */
    fun sight(
        key: String,
        address: String,
        name: String,
        tracker: TrackerType?,
        fingerprint: String?,
        now: Long,
        driveMode: Boolean = false
    ): Finding? {
        synchronized(lock) {
            if (tripStart == 0L) return null
            if (driveMode) tripOwnCar = true
            val entry = seen.getOrPut(key) {
                if (seen.size >= MAX_SEEN) return null
                Seen(address).apply { first = now }
            }
            entry.last = now
            entry.count++
            entry.name = name
            if (tracker != null) entry.tracker = tracker
            if (fingerprint != null) entry.fingerprint = fingerprint

            if (entry.qualified) return null
            if (entry.count < MIN_SIGHTINGS || entry.last - entry.first < MIN_SPAN_MS) return null
            entry.qualified = true

            val byAddress = earlierTrips { it.addresses.contains(entry.address) }
            if (byAddress + 1 >= TRIPS_TO_ALERT) {
                entry.reported = true
                return addressFinding(entry, byAddress + 1, now, tripOwnCar, search = true)
            }
            val signature = signatureOf(entry) ?: return null
            val bySignature = earlierTrips { it.signatures.contains(signature) }
            if (bySignature + 1 >= TRIPS_TO_ALERT) {
                entry.reported = true
                return signatureFinding(entry, signature, bySignature + 1, now, tripOwnCar)
            }
            return null
        }
    }

    /**
     * The same address on [trips] trips. In the owner's car ([ownCar]) it is in the
     * vehicle; otherwise it has been on that many journeys, which a bus, a train or a
     * fellow commuter explains as well, so it is a notch lower and says so. [search]
     * adds the places to look in a car (the mid-trip wording; the end-of-trip one is shorter).
     */
    private fun addressFinding(entry: Seen, trips: Int, now: Long, ownCar: Boolean, search: Boolean): Finding {
        val places = "the OBD-II port, under the seats, the bumper covers and the wheel wells"
        return if (ownCar) Finding(
            id = "vehicle@${entry.address}@$now",
            severity = Severity.CRITICAL,
            title = "${entry.name} has been in your vehicle on $trips trips",
            detail = "Same Bluetooth address (${entry.address}) present for the length of $trips separate drives." +
                if (search) " Check ${places}." else "",
            dedupeKey = "vehicle@${entry.address}"
        ) else Finding(
            id = "$JOURNEYS_PREFIX${entry.address}@$now",
            severity = Severity.HIGH,
            title = "${entry.name} has been on $trips of your journeys",
            detail = "Same Bluetooth address (${entry.address}) heard for the length of $trips separate journeys. " +
                "Nothing showed these were in your own car (no car kit connected, Drive mode off), so it may be part " +
                "of the bus or train you take, or carried by someone who makes the same journeys. If you made them in " +
                "your own car, check $places.",
            dedupeKey = "$JOURNEYS_PREFIX${entry.address}",
            coTraveller = true
        )
    }

    /**
     * A tracker of one type and advertisement shape on [trips] trips: possibly different
     * units each time, since a rotating tracker's signature is shared by every unit of
     * its model. Reported at most once a day under one signature.
     */
    private fun signatureFinding(entry: Seen, signature: String, trips: Int, now: Long, ownCar: Boolean): Finding {
        val label = entry.tracker?.label ?: entry.name
        val first = label.firstOrNull()?.lowercaseChar()
        val a = if (first != null && first in VOWELS) "An" else "A"
        return if (ownCar) Finding(
            id = "vehicle@$signature@$now",
            severity = Severity.HIGH,
            title = "$a $label has ridden along on $trips trips",
            detail = "A tracker of this type and advertisement shape was present for the length of " +
                "$trips separate drives. It rotates its address, so this could be two " +
                "different ones — or the same one each time. Search the vehicle.",
            dedupeKey = "vehicle@$signature",
            dedupeWindowMs = SIGNATURE_DEDUPE_MS
        ) else Finding(
            id = "$JOURNEYS_PREFIX$signature@$now",
            severity = Severity.MEDIUM,
            title = "$a $label has been on $trips of your journeys",
            detail = "A tracker of this type and advertisement shape was heard for the length of $trips separate " +
                "journeys. It rotates its address, so these may be different ones each time, which on a bus or a " +
                "train is the likely case, or the same one. Nothing showed these journeys were in your own car; if " +
                "they were, search it.",
            dedupeKey = "$JOURNEYS_PREFIX$signature",
            dedupeWindowMs = SIGNATURE_DEDUPE_MS,
            coTraveller = true
        )
    }

    /**
     * Closes the trip, stores what qualified, and returns any device that has now
     * reached [TRIPS_TO_ALERT] and was not already reported mid-trip. A trip shorter
     * than [MIN_TRIP_MS] is a false transition — reversing out of a parking space —
     * and is discarded.
     */
    fun endTrip(now: Long): List<Finding> {
        synchronized(lock) {
            val start = tripStart
            if (start == 0L) return emptyList()
            tripStart = 0L
            val startLat = tripStartLat
            val startLon = tripStartLon
            val ownCar = tripOwnCar
            tripOwnCar = false
            if (now - start < MIN_TRIP_MS) {
                seen.clear(); tripAccessories.clear(); tripStartLat = null; tripStartLon = null
                return emptyList()
            }

            val addresses = HashSet<String>()
            val signatures = HashSet<String>()
            val findings = ArrayList<Finding>()
            for (entry in seen.values) {
                if (!entry.qualified) continue
                addresses.add(entry.address)
                signatureOf(entry)?.let(signatures::add)
                if (entry.reported) continue
                val byAddress = earlierTrips { it.addresses.contains(entry.address) } + 1
                if (byAddress >= TRIPS_TO_ALERT) findings.add(addressFinding(entry, byAddress, now, ownCar, search = false))
            }
            history.add(Trip(start, now, addresses, signatures, startLat, startLon, HashMap(tripAccessories)))
            while (history.size > MAX_TRIPS) history.removeAt(0)
            seen.clear()
            tripAccessories.clear()
            tripStartLat = null
            tripStartLon = null
            saveHistory()
            return findings
        }
    }

    /** Forgets trips that ended before [cutoff]; returns how many. */
    fun pruneOlderThan(cutoff: Long): Int = synchronized(lock) {
        val before = history.size
        history.removeAll { it.end < cutoff }
        val removed = before - history.size
        if (removed > 0) saveHistory()
        removed
    }

    fun clear() {
        synchronized(lock) {
            tripStart = 0L
            tripStartLat = null
            tripStartLon = null
            seen.clear()
            tripAccessories.clear()
            tripOwnCar = false
            history.clear()
            store.replace(JSONObject())
        }
    }

    /** "airtag|3f9a…" for an identified tracker; generic devices have no rotation-proof signature. */
    private fun signatureOf(entry: Seen): String? {
        val tracker = entry.tracker ?: return null
        val fingerprint = entry.fingerprint ?: return null
        return "${tracker.id}|$fingerprint"
    }

    private fun loadHistory(): List<Trip> {
        val root = store.load { JSONObject() }
        val trips = root.optJSONArray("trips") ?: return emptyList()
        val out = ArrayList<Trip>(trips.length())
        for (i in 0 until trips.length()) {
            val t = trips.optJSONObject(i) ?: continue
            out.add(Trip(
                start = t.optLong("start"), end = t.optLong("end"),
                addresses = strings(t.optJSONArray("addresses")),
                signatures = strings(t.optJSONArray("signatures")),
                startLat = if (t.has("startLat")) t.optDouble("startLat") else null,
                startLon = if (t.has("startLon")) t.optDouble("startLon") else null,
                accessories = accessories(t.optJSONObject("accessories"))
            ))
        }
        return out
    }

    private fun saveHistory() {
        val trips = JSONArray()
        for (t in history) {
            trips.put(JSONObject().apply {
                put("start", t.start)
                put("end", t.end)
                put("addresses", JSONArray(t.addresses.toList()))
                put("signatures", JSONArray(t.signatures.toList()))
                t.startLat?.let { put("startLat", it) }
                t.startLon?.let { put("startLon", it) }
                if (t.accessories.isNotEmpty()) {
                    put("accessories", JSONObject().also { o -> for ((a, n) in t.accessories) o.put(a, n) })
                }
            })
        }
        store.replace(JSONObject().put("trips", trips))
    }

    private fun accessories(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = HashMap<String, String>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optString(k, "")
        }
        return out
    }

    private fun strings(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = HashSet<String>(array.length())
        for (i in 0 until array.length()) array.optString(i, null)?.let(out::add)
        return out
    }

    companion object {
        /** How many trips a device must ride along on before it is reported. */
        const val TRIPS_TO_ALERT = 3
        /** Trips remembered; older ones fall off. */
        const val MAX_TRIPS = 10
        /** A trip shorter than this is a false start. */
        const val MIN_TRIP_MS = 2 * 60_000L
        /** To count for a trip a device must be heard this often… */
        const val MIN_SIGHTINGS = 3
        /** …over at least this long, so a car alongside for one light does not qualify. */
        const val MIN_SPAN_MS = 90_000L
        /** Trips setting off closer together than this count as the same start point. */
        const val DISTINCT_START_M = 500.0
        private const val MAX_SEEN = 2000
        /** Labels starting with one of these take "An": "An Apple AirTag", "An iBeacon". */
        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
        /** Accessories remembered per trip; nobody has more paired devices than this connected at once. */
        private const val MAX_ACCESSORIES = 16
        /** A repeat of an address finding stays off the timeline this long: once per trip in practice. */
        const val ADDRESS_DEDUPE_MS = 30 * 60_000L
        /** A shared-signature finding, which every separated AirTag shares, at most once a day. */
        const val SIGNATURE_DEDUPE_MS = 24 * 3_600_000L

        /**
         * Start of the dedupe key, and so of the timeline row's id, of a finding with no
         * own-car signal ([Finding.coTraveller]): the timeline opens the co-traveller
         * explanation below for those rows ([coTravellerEvent]).
         */
        const val JOURNEYS_PREFIX = "journeys@"

        /** Whether a timeline row with [eventId] is a co-traveller finding from trip memory. */
        fun coTravellerEvent(eventId: String): Boolean = eventId.startsWith(JOURNEYS_PREFIX)

        /** The co-traveller explanation of such a finding: what to do. */
        const val CO_TRAVELLER_WHAT_TO_DO =
            "If you made these journeys by bus, train or in someone else's car, the device is most likely part of " +
                "that vehicle or carried by someone who makes the same journeys, and there is nothing to do. If you " +
                "made them in your own car, or the device keeps turning up when you travel alone, search the car: the " +
                "OBD-II port under the dashboard, under the seats, the bumper covers and the wheel wells. If you find " +
                "something, photograph it in place and keep it as evidence."

        /** The co-traveller explanation: what it is. */
        const val CO_TRAVELLER_WHAT_IS_THIS =
            "Clairix remembers which Bluetooth devices were present for the length of each journey you make at road " +
                "speed, and this one has been on several of them. A bus or a train is a journey too, and nothing " +
                "showed these were in your own car: no car hands-free connected while you travelled, and Drive mode " +
                "was off. So it may be the vehicle's own electronics, or the phone, watch or earbuds of someone who " +
                "travels the same way as you."

        /** The co-traveller explanation: how certain. */
        const val CO_TRAVELLER_HOW_CERTAIN =
            "That it was present on each of these journeys is certain: it was heard at least three times over at " +
                "least a minute and a half on each. Where it was, in your car, on the bus or in someone's pocket, " +
                "cannot be told from the radio."
    }
}
