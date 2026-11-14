package app.clairix.analysis

import app.clairix.Detection
import app.clairix.Threat
import app.clairix.detect.Signatures
import kotlin.math.max

/**
 * When a device confirmed as moving with you wakes the notification shade.
 *
 * A recognised tracker away from its owner — CRITICAL from [Tracker] — is announced
 * the moment it is confirmed: it is on you or your vehicle, and the sooner it is
 * found the better. Anything else that rides along is, far more often than not, a
 * companion's phone or watch, a passenger's earbuds or the bus's own electronics.
 * One alarm per such device in the middle of a journey would teach the owner to
 * ignore alarms, so those are held and summed up in one notice when the journey
 * ends: when the phone comes to rest ([MotionPolicy] going still) or a drive is over
 * ([app.clairix.detect.VehicleTrips.endTrip]). A device held or summed up that later
 * turns out to be a recognised tracker — a tag that rode along near its owner's phone
 * and then separated — is announced at once after all. The list, the timeline and
 * the ongoing notification show every follower as soon as it is confirmed; this
 * decides only when the shade is woken.
 *
 * Thread-safe: verdicts arrive on the packet thread, journeys end on the main thread
 * and on the service's loop.
 */
class FollowAlertPolicy {

    enum class Decision {
        /** Raise the device's own alert now. */
        ANNOUNCE,
        /** Kept for the summary at the end of the journey. */
        HOLD,
        /** Already announced, held or summed up; nothing to raise. */
        NOTHING
    }

    /** One notice for the unidentified devices that rode along on a journey. */
    data class Summary(val keys: List<String>, val title: String, val detail: String) {
        val count: Int get() = keys.size
    }

    private class Rider(
        val key: String,
        var address: String,
        var name: String,
        var displacementM: Double,
        var sightings: Int,
        /** A Bluetooth tire pressure sensor, which gets its own wording ([summaryOf]). */
        val tire: Boolean = false
    ) {
        /** The device as the tracker sees it now, which has usually been heard more since it was held. */
        fun refresh(d: Detection) {
            address = d.address
            name = d.name
            displacementM = max(displacementM, d.displacementM)
            sightings = max(sightings, d.sightings)
        }
    }

    private val held = LinkedHashMap<String, Rider>()
    /** Keys announced as recognised trackers. */
    private val trackers = HashSet<String>()
    /** Keys already summed up as riders on an earlier journey. */
    private val summarised = HashSet<String>()

    /** A device has just been confirmed as following, or its verdict has just risen to CRITICAL. */
    @Synchronized
    fun onVerdict(d: Detection): Decision {
        if (d.threat == Threat.CRITICAL) {
            held.remove(d.key)
            return if (trackers.add(d.key)) Decision.ANNOUNCE else Decision.NOTHING
        }
        if (d.key in trackers || d.key in summarised || d.key in held) return Decision.NOTHING
        held[d.key] = Rider(d.key, d.address, d.name, d.displacementM, d.sightings, tire = d.tracker?.id == Signatures.TIRE_SENSOR.id)
        return Decision.HOLD
    }

    /**
     * The journey has ended. Returns the notice for whatever was held, its figures
     * brought up to date from [latest] (asked for only when something was held), or
     * null when nothing was. A rider the owner has since marked as theirs — its
     * address is in [trusted] — is dropped. The rest count as summed up from here on.
     */
    fun onJourneyEnd(now: Long, trusted: Set<String> = emptySet(), latest: () -> List<Detection> = { emptyList() }): Summary? {
        if (synchronized(this) { held.isEmpty() }) return null
        val current = latest().associateBy { it.key }
        synchronized(this) {
            val riders = ArrayList<Rider>(held.size)
            for (r in held.values) {
                current[r.key]?.let(r::refresh)
                if (r.address !in trusted) riders.add(r)
            }
            held.clear()
            if (riders.isEmpty()) return null
            riders.forEach { summarised.add(it.key) }
            return summaryOf(riders)
        }
    }

    /**
     * Forgets [key]: the owner marked it found. If it is heard again after an hour away
     * and travels with you again ([Tracker.observe]), it is announced, held or summed up
     * afresh instead of counting as already done.
     */
    @Synchronized
    fun forget(key: String) {
        held.remove(key)
        trackers.remove(key)
        summarised.remove(key)
    }

    /** Forgets everything. Used by "clear all data". */
    @Synchronized
    fun clear() {
        held.clear()
        trackers.clear()
        summarised.clear()
    }

    private fun summaryOf(riders: List<Rider>): Summary {
        if (riders.all { it.tire }) return tireSummaryOf(riders)
        val widest = riders.maxOf { it.displacementM }.toInt()
        val title: String
        val opening: String
        if (riders.size == 1) {
            val r = riders[0]
            title = "A device travelled with you"
            opening = "${r.name} was heard at places $widest m apart while you travelled, ${r.sightings} times. " +
                "It is not recognised as a tracker away from its owner: it may be the phone, earbuds, watch or keys " +
                "of someone who travelled with you, or your vehicle's own electronics."
        } else {
            title = "${riders.size} devices travelled with you"
            val shown = riders.take(3).joinToString(", ") { it.name }
            val more = riders.size - 3
            opening = "$shown${if (more > 0) " and $more more" else ""} were heard at places up to $widest m apart " +
                "while you travelled. None is recognised as a tracker away from its owner: they may be the phones, " +
                "earbuds, watches or keys of people who travelled with you, or your vehicle's own electronics."
        }
        val closing = " A hardwired GPS tracker looks the same, so if you were alone, check the car: OBD-II port, " +
            "under the dash, wheel wells, bumpers. " +
            if (riders.size == 1) "It is listed under Trackers; mark it as yours there if you recognise it."
            else "They are listed under Trackers; mark any you recognise as yours there."
        val tires = riders.count { it.tire }
        val tireNote = when (tires) {
            0 -> ""
            1 -> " One of them is a tire pressure sensor: if your car has Bluetooth tire sensors, it is most likely " +
                "one of its wheels, and you can mark your car's tires as yours under Trackers."
            else -> " $tires of them are tire pressure sensors: if your car has Bluetooth tire sensors, they are most " +
                "likely its wheels, and you can mark your car's tires as yours under Trackers."
        }
        return Summary(riders.map { it.key }, title, opening + closing + tireNote)
    }

    /**
     * The notice when every rider is a tire pressure sensor. A car with Bluetooth tire
     * sensors takes its own set on every drive, so that is said first; the other case is
     * a vehicle that stayed near the owner's for the whole distance. No search of the
     * car is suggested: a tire sensor is on a wheel, and does not report a position.
     */
    private fun tireSummaryOf(riders: List<Rider>): Summary {
        val widest = riders.maxOf { it.displacementM }.toInt()
        val one = riders.size == 1
        val title = if (one) "A tire pressure sensor travelled with you" else "${riders.size} tire pressure sensors travelled with you"
        val shown = riders.take(4).joinToString(", ") { it.name }
        val more = riders.size - 4
        val heard = if (one) "${riders[0].name} was heard at places $widest m apart while you travelled, ${riders[0].sightings} times."
        else "$shown${if (more > 0) " and $more more" else ""} were heard at places up to $widest m apart while you travelled."
        val detail = "$heard If your car has Bluetooth tire sensors, ${if (one) "this is most likely one of its wheels" else "these are most likely its wheels"}: " +
            "open ${if (one) "it" else "one"} under Trackers and mark your car's tires as yours, so they stop being counted. " +
            "If not, ${if (one) "it is" else "they are"} on a vehicle that stayed near yours for that distance; Clairix " +
            "remembers and says so if the same one turns up on later drives."
        return Summary(riders.map { it.key }, title, detail)
    }
}
