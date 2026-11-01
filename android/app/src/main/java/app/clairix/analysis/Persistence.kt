package app.clairix.analysis

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.LatLon
import app.clairix.Threat
import app.clairix.TrackerType
import app.clairix.detect.BleNames
import app.clairix.detect.DultResult
import kotlin.math.max
import kotlin.math.min

const val PERSIST_THRESHOLD_MS = 10 * 60 * 1000L
const val PERSIST_MIN_SIGHTINGS = 5

/**
 * Longest persistence threshold the user may set. An uncertain cadence stitch
 * restarts a device's presence clock, so a tracker whose address rotates more
 * often than the threshold would never be held long enough under one address to
 * be confirmed. The shortest rotation among supported trackers is the Samsung
 * SmartTag's, about 15 minutes; the threshold stays below it with room for the
 * minutes a new address takes to be heard and stitched.
 */
const val MAX_PERSIST_THRESHOLD_MS = 12 * 60 * 1000L

/** Ceiling on simultaneously tracked devices — see [Tracker.prune]. */
private const val MAX_ENTRIES = 4000

/** How long a confirmed follower is remembered after its last sighting — see [Tracker.prune]. */
const val FOLLOWING_RETENTION_MS = 7 * 24 * 3600_000L

/** Ceiling on distinct service names remembered per device; a row cannot show more anyway. */
private const val MAX_SERVICES = 8

/**
 * Suffix of the ids of the "owner nearby" readings of a Find My tracker
 * ([app.clairix.detect.Signatures.AIRTAG_NEAR_OWNER] and its Find My sibling).
 */
private const val NEAR_OWNER_SUFFIX = "_near"

/** Whether this reading is of a tracker still within range of its owner's phone. */
private val TrackerType.nearOwner: Boolean get() = id.endsWith(NEAR_OWNER_SUFFIX)

/**
 * The classification an entry should hold given what it held and what a packet
 * says. A device becomes identifiable once it advertises something we match, and a
 * higher-severity reading replaces a lower one: a Find My tag heard "owner nearby"
 * that then advertises as separated is a tracker away from its owner under the same
 * address, and the first reading must not hide the second. Nothing is ever
 * downgraded, so a later near-owner frame does not undo a separation.
 */
private fun classify(held: TrackerType?, seen: TrackerType?): TrackerType? = when {
    seen == null -> held
    held == null -> seen
    seen.threat.ordinal > held.threat.ordinal -> seen
    else -> held
}

/** The earlier of two timestamps, where 0 means none. */
private fun earliest(a: Long, b: Long): Long = when {
    a == 0L -> b
    b == 0L -> a
    else -> min(a, b)
}

/**
 * Tracks how long a device stays with you and — where there is a position fix —
 * whether it actually travelled with you.
 *
 * The distinction is the whole point of this app. "Seen five times over ten
 * minutes" describes a tracker in your bumper and it equally describes a
 * neighbour's Tile through a wall. Only displacement separates them, so with no
 * position source the stronger claim is simply never made. Nor is it made on
 * displacement the phone did not itself travel: see [ObservationArea].
 */
class Tracker(
    /**
     * Read through lambdas rather than captured once, so moving a slider in Settings
     * takes effect on the next observation instead of at the next app start. The
     * defaults keep the class usable on its own.
     */
    private val persistMs: () -> Long = { PERSIST_THRESHOLD_MS },
    private val followM: () -> Double = { FOLLOW_DISPLACEMENT_M }
) {

    private class Entry(val key: String) {
        var address = ""
        var name = ""
        // Identification is sticky: an advertisement that omits the name or the
        // manufacturer (scan responses and rotating payloads do) must not blank out
        // what an earlier packet already told us.
        var advertisedName: String? = null
        var manufacturer: String? = null
        val services = LinkedHashSet<String>()
        var radio = "BLE"
        var addressKind: String? = null
        var rssi = 0
        var tracker: TrackerType? = null
        /**
         * When a near-owner reading of this device gave way to a separated one, or 0.
         * The moment its owner's phone dropped out of range — which, for a tag that was
         * riding along, is the moment it stopped being somebody's keys.
         */
        var separatedAt = 0L
        var approxMetres: Double? = null
        var firstSeen = 0L
        var lastSeen = 0L
        var sightings = 0
        var rotations = 0
        var addresses = 1
        var following = false
        /**
         * When this device was first confirmed as following, or 0. Once set it holds
         * the verdict: a rotation stitched without certainty restarts the presence
         * clock, and without this latch a confirmed tracker would drop back to
         * nothing for a full threshold after every rotation — and be pruned on the
         * short cutoff meanwhile. It only ever carries a verdict this entry already
         * earned, so nothing new can reach "following" through it; and it does not
         * carry across a stitch that could as well have swapped in another unit of
         * the same product (see [absorb]).
         */
        var confirmedAt = 0L
        var persistent = false
        /**
         * Whether an observation has reported this device as a recognised tracker
         * following you. A merge can lift a follower to CRITICAL between two
         * observations, when a separated reading arrives under a new address, and the
         * next observation must still report that rise, once.
         */
        var criticalReported = false
        var score = 0
        var confidence = FollowConfidence.NONE
        val area = ObservationArea()
        /** Our position at the most recent sighting — where the map should mark it. */
        var lastHeardAt: LatLon? = null
        /** The tracker's own account of itself over DULT, once it has been asked. */
        var dult: DultResult? = null

        /**
         * Folds [other] into this entry when two keys turn out to be one device. The
         * cadence fingerprinter can only prove a rotation after a few packets, by which
         * time the new address has a few sightings under a key of its own.
         *
         * [certain] is the fingerprinter's own confidence in the stitch.
         * [indistinguishable] says the two addresses belong to one tracker family and
         * share every confirming feature, so that only their cadence and the timing of
         * the hand-over say they are one device — which two units of one product also
         * manage.
         *
         * A certain merge joins the two histories: the earliest firstSeen, every
         * sighting and every place. An uncertain one may be two different devices,
         * so the evidence of presence — firstSeen, sightings, places, and the
         * persistent/following verdicts drawn from them — restarts from the newer of
         * the two, and the device has to earn "following" again on its own. Here
         * `this` is usually the older entry (the stitch target) and [other] the new
         * address; keeping this entry's early firstSeen, as the first version of
         * this did, still handed the new address the old one's ten minutes. The
         * identity bookkeeping (rotations, addresses, names, DULT) is kept either way.
         *
         * A confirmation already earned survives a merge, certain or not, with one
         * exception: when the stitch is uncertain and indistinguishable, the address
         * now on the row is not necessarily the device that earned it, and carrying
         * the verdict over would send the locator after the wrong physical tracker.
         * The row then keeps only what the newer address earned for itself.
         */
        fun absorb(other: Entry, certain: Boolean, indistinguishable: Boolean) {
            if (address.isEmpty()) address = other.address
            if (advertisedName == null) advertisedName = other.advertisedName
            if (manufacturer == null) manufacturer = other.manufacturer
            if (services.size < MAX_SERVICES) services.addAll(other.services)
            if (radio == "BLE" && other.radio != "BLE") radio = other.radio
            if (addressKind == null) addressKind = other.addressKind
            // The higher-severity classification wins (see [classify]); a near-owner
            // reading giving way to a separated one is the separation itself, dated
            // to when the separated address was first heard unless either side knows better.
            val held = tracker
            val kept = classify(held, other.tracker)
            if (kept !== held) {
                if (held != null && held.nearOwner && kept != null && !kept.nearOwner &&
                    separatedAt == 0L && other.separatedAt == 0L
                ) separatedAt = other.firstSeen
                tracker = kept
            }
            separatedAt = earliest(separatedAt, other.separatedAt)
            criticalReported = criticalReported || other.criticalReported
            if (dult == null || (other.dult?.answered == true && dult?.answered != true)) dult = other.dult
            if (other.lastSeen >= lastSeen) {
                rssi = other.rssi
                approxMetres = other.approxMetres
                lastHeardAt = other.lastHeardAt ?: lastHeardAt
            }
            // The merge itself is one more address the device has used.
            rotations = rotations + other.rotations + 1
            addresses = addresses + other.addresses
            var inherited = earliest(confirmedAt, other.confirmedAt)
            if (certain) {
                firstSeen = if (firstSeen == 0L) other.firstSeen else min(firstSeen, other.firstSeen)
                sightings += other.sightings
                following = following || other.following
                persistent = persistent || other.persistent
                score = max(score, other.score)
                area.merge(other.area)
            } else {
                // Restart the presence clock at the later of the two starts.
                val newer = if (other.firstSeen != 0L && (firstSeen == 0L || other.firstSeen >= firstSeen)) other else this
                if (newer === other) {
                    firstSeen = other.firstSeen
                    sightings = other.sightings
                    following = other.following
                    persistent = other.persistent
                    score = other.score
                    area.replaceWith(other.area)
                }
                if (indistinguishable) inherited = newer.confirmedAt
            }
            confirmedAt = inherited
            if (confirmedAt != 0L) following = true
            lastSeen = max(lastSeen, other.lastSeen)
            name = BleNames.displayName(advertisedName, tracker, manufacturer, address)
        }
    }

    data class Observation(
        val detection: Detection,
        /** This packet confirmed the device as following. */
        val becameFollowing: Boolean,
        /**
         * Already following, and now a recognised tracker away from its owner for the
         * first time, so the verdict rose to CRITICAL: a tag that rode along as "owner
         * nearby" has separated, under its own address or a new one.
         */
        val escalated: Boolean = false
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun observe(
        key: String,
        address: String,
        rssi: Int,
        tracker: TrackerType?,
        approxMetres: Double?,
        rotations: Int,
        addresses: Int,
        fix: Fix?,
        hasPosition: Boolean,
        now: Long,
        advertisedName: String? = null,
        manufacturer: String? = null,
        services: List<String> = emptyList(),
        radio: String = "BLE",
        addressKind: String? = null,
        /** The phone's own odometer ([LocationTrack.travelledM]) at this sighting. */
        travelledM: Double = 0.0
    ): Observation {
        val entry = entries.getOrPut(key) {
            Entry(key).apply { firstSeen = now }
        }

        val wasFollowing = entry.following

        entry.address = address
        entry.rssi = rssi
        entry.approxMetres = approxMetres
        // Never backwards: a cadence stitch can route a fresh identity (zero rotations)
        // onto an entry that has already been seen through several.
        entry.rotations = max(entry.rotations, rotations)
        entry.addresses = max(entry.addresses, addresses)
        entry.lastSeen = now
        entry.sightings++
        val held = entry.tracker
        val kept = classify(held, tracker)
        if (kept !== held) {
            if (held != null && held.nearOwner && kept != null && !kept.nearOwner && entry.separatedAt == 0L) {
                entry.separatedAt = now
            }
            entry.tracker = kept
        }

        advertisedName?.let { entry.advertisedName = it }
        manufacturer?.let { entry.manufacturer = it }
        if (services.isNotEmpty() && entry.services.size < MAX_SERVICES) entry.services.addAll(services)
        if (radio != "Unknown") entry.radio = radio
        addressKind?.let { entry.addressKind = it }
        entry.name = BleNames.displayName(entry.advertisedName, entry.tracker, entry.manufacturer, address)

        fix?.let {
            entry.area.add(it, now, travelledM)
            entry.lastHeardAt = LatLon(it.lat, it.lon)
        }

        val duration = now - entry.firstSeen
        entry.persistent =
            duration >= persistMs().coerceAtMost(MAX_PERSIST_THRESHOLD_MS) && entry.sightings >= PERSIST_MIN_SIGHTINGS

        val movedWithUs = entry.area.movedWithUs(followM())
        entry.following = (entry.persistent && movedWithUs) || entry.confirmedAt != 0L
        if (entry.following && entry.confirmedAt == 0L) entry.confirmedAt = now

        val confidence = when {
            entry.following -> FollowConfidence.CONFIRMED
            !entry.persistent -> FollowConfidence.NONE
            !hasPosition -> FollowConfidence.NO_POSITION
            else -> FollowConfidence.NOT_MOVED_ENOUGH
        }

        var score = when (entry.tracker?.threat) {
            Threat.CRITICAL -> 88
            Threat.HIGH -> 68
            Threat.MEDIUM -> 44
            Threat.LOW -> 20
            else -> 10
        }
        if (rssi > -55) score += 10 else if (rssi > -70) score += 5
        if (entry.following) {
            score += 30
            if (entry.tracker != null) score += 10
        } else if (entry.persistent) {
            score += 10
        }
        // Rotating its address while staying with you is what a tracker built to
        // defeat exactly this kind of detection does.
        if (entry.rotations > 0) score += 8
        score = score.coerceIn(0, 100)
        entry.score = score
        entry.confidence = confidence

        val threat = threatOf(entry)
        val criticalFollower = entry.following && threat == Threat.CRITICAL
        val escalated = wasFollowing && criticalFollower && !entry.criticalReported
        if (criticalFollower) entry.criticalReported = true
        return Observation(
            detection = detectionOf(entry, threat),
            becameFollowing = entry.following && !wasFollowing,
            escalated = escalated
        )
    }

    /**
     * How worrying an entry is, for the live observation and the list alike: one
     * mapping, so the pill, the hero, the report and the alert cannot disagree.
     *
     * Moving with you is CRITICAL for a recognised tracker away from its owner. For a
     * device nothing identifies, or one still within range of its owner, it is HIGH:
     * that is how a hardwired GPS box looks, and also how a companion's phone, a
     * passenger's earbuds or the bus's own electronics look, and the alert says so
     * rather than declaring every one of them a tracker.
     */
    private fun threatOf(entry: Entry): Threat {
        val type = entry.tracker
        return when {
            entry.following -> if (isRecognisedTracker(type)) Threat.CRITICAL else Threat.HIGH
            type != null -> type.threat
            entry.persistent -> Threat.MEDIUM
            else -> Threat.LOW
        }
    }

    /**
     * A tag made for finding things (AirTag, Tile, SmartTag…) away from its owner —
     * not a beacon, not a reading with the owner's phone in range, not a device we
     * only half recognise.
     */
    private fun isRecognisedTracker(type: TrackerType?): Boolean =
        type != null && !type.nearOwner && (type.threat == Threat.CRITICAL || type.threat == Threat.HIGH)

    private fun detectionOf(entry: Entry, threat: Threat) = Detection(
        key = entry.key,
        address = entry.address,
        name = entry.name,
        rssi = entry.rssi,
        tracker = entry.tracker,
        threat = threat,
        score = entry.score,
        firstSeen = entry.firstSeen,
        lastSeen = entry.lastSeen,
        sightings = entry.sightings,
        persistent = entry.persistent,
        following = entry.following,
        confidence = entry.confidence,
        displacementM = entry.area.span(),
        places = entry.area.size,
        rotations = entry.rotations,
        addresses = entry.addresses,
        approxMetres = entry.approxMetres,
        points = entry.area.points(),
        lastHeardAt = entry.lastHeardAt,
        advertisedName = entry.advertisedName,
        manufacturer = entry.manufacturer,
        services = entry.services.toList(),
        radio = entry.radio,
        addressKind = entry.addressKind,
        dult = entry.dult,
        separatedAt = entry.separatedAt
    )

    /**
     * Attaches what a tracker said over DULT to its row. Partial results arrive as
     * the exchange proceeds; a later one always replaces an earlier one, except that
     * a final "refused" must not erase answers already received.
     */
    @Synchronized
    fun attachDult(key: String, result: DultResult) {
        val entry = entries[key] ?: return
        val current = entry.dult
        if (current != null && current.answered && !result.answered) {
            entry.dult = current.copy(complete = result.complete, error = result.error)
            return
        }
        entry.dult = result
    }

    /**
     * Moves everything recorded under [fromKey] into [intoKey], creating the target
     * when it does not exist yet. Returns false when there was nothing to move.
     * [certain] says whether the two keys are proven to be one device and
     * [indistinguishable] whether only their cadence told them apart; see
     * [Entry.absorb] for what each holds back.
     */
    @Synchronized
    fun merge(fromKey: String, intoKey: String, certain: Boolean, indistinguishable: Boolean = false): Boolean {
        if (fromKey == intoKey) return false
        val from = entries.remove(fromKey) ?: return false
        val into = entries.getOrPut(intoKey) { Entry(intoKey) }
        into.absorb(from, certain, indistinguishable)
        return true
    }

    @Synchronized
    fun snapshot(now: Long): List<Detection> = entries.values.map { detectionOf(it, threatOf(it)) }

    /**
     * Drops devices that have gone quiet. Anything that proved it travels with you is
     * kept far longer — trackers sleep between reports and forgetting one would throw
     * away the only evidence that matters — but not forever: a follower silent for a
     * week is gone, and keeping its row would only leave a permanent CRITICAL ghost.
     * Returns how many devices were dropped, so the scanner knows when its list changed.
     */
    @Synchronized
    fun prune(now: Long, maxAgeMs: Long = 30 * 60 * 1000L): Int {
        val before = entries.size
        val cutoff = now - maxAgeMs
        val followingCutoff = now - FOLLOWING_RETENTION_MS
        entries.entries.removeAll { (_, entry) ->
            if (entry.following || entry.confirmedAt != 0L) entry.lastSeen < followingCutoff
            else entry.lastSeen < cutoff
        }

        // Somewhere crowded can produce thousands of one-sighting devices inside the
        // retention window. Drop the least recently heard, but never anything that has
        // proved it travels with you or is holding a persistence pattern — those are
        // the entire point of keeping any of this.
        if (entries.size > MAX_ENTRIES) {
            entries.values
                .filterNot { it.following || it.confirmedAt != 0L || it.persistent }
                .sortedBy { it.lastSeen }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
        return before - entries.size
    }

    /**
     * Keys (identity ids) of every device currently confirmed as following. The
     * identity resolver must not forget these while the tracker still remembers them.
     */
    @Synchronized
    fun followingKeys(): Set<String> =
        entries.values.filter { it.following }.mapTo(HashSet()) { it.key }

    /** Forgets every device. Used by "clear all data". */
    @Synchronized
    fun clear() = entries.clear()
}
