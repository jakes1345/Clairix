package app.clairix.detect

import app.clairix.Detection
import app.clairix.TrackerType

/**
 * The devices the screen-off scan keeps a filter of their own for.
 *
 * With the screen off Android keeps a Bluetooth scan running only when it is filtered,
 * and the scanner's filters name the catalogued tracker types: Find My and iBeacon
 * frames, the trackers' service UUIDs, surveillance hardware. A device found while the
 * screen was on that matches none of them, an unbranded GPS box above all, would go
 * unheard the moment the screen went off, its evidence frozen and the row pruned half
 * an hour later. So the scan also carries an address filter for each device already
 * under watch that the type filters do not cover: confirmed following, holding a
 * persistence pattern, or a GPS module recognised by its name. Following first, then
 * persistent, then named, then the most recently heard, up to [MAX_ADDRESS_FILTERS]
 * of them: a controller has only so many filter slots (past them Android filters in
 * its own process instead, which keeps the scan alive but wakes the CPU more). The
 * passing crowd is left out on purpose: its devices come and go by the minute, and
 * every change would mean registering the scan again. A device that changes its
 * address loses its filter at the next rotation, which is why the type filters stay.
 */
internal object ScreenOffWatch {

    /** Address filters per screen-off scan. */
    const val MAX_ADDRESS_FILTERS = 16

    /** The scan is re-registered for a changed watch list at most this often: Android throttles scan starts. */
    const val REFRESH_MIN_MS = 60_000L

    /** One device to filter for: its address, and whether that address is random (null when the platform did not say). */
    data class Watched(val address: String, val random: Boolean?)

    /**
     * Whether a type filter already hears [type] with the screen off. Everything the
     * signatures recognise comes with a manufacturer or service filter except a GPS
     * module, which is recognised only by its name.
     */
    fun coveredByTypeFilter(type: TrackerType?): Boolean = type != null && type.id != Signatures.GPS_BLE.id

    /** The devices among [detections] that get an address filter; none the owner trusts. */
    fun watched(detections: List<Detection>, trusted: Set<String>, max: Int = MAX_ADDRESS_FILTERS): List<Watched> =
        detections.asSequence()
            .filter { it.address !in trusted && !coveredByTypeFilter(it.tracker) }
            .filter { it.following || it.persistent || it.identified }
            .sortedWith(
                compareByDescending<Detection> { it.following }
                    .thenByDescending { it.persistent }
                    .thenByDescending { it.identified }
                    .thenByDescending { it.lastSeen }
            )
            .distinctBy { it.address }
            .take(max)
            .map { Watched(it.address, randomAddress(it.addressKind)) }
            .toList()

    /**
     * Whether to re-register the screen-off scan: the devices to watch ([watched]) are
     * not the ones the running scan has filters for ([registered]), and the scan was
     * registered at least [REFRESH_MIN_MS] ago ([registeredAt]).
     */
    fun refreshDue(watched: Set<String>, registered: Set<String>, registeredAt: Long, now: Long): Boolean =
        watched != registered && now - registeredAt >= REFRESH_MIN_MS

    /** What [app.clairix.detect.BleNames.addressKind] says about the address type: random, public, or unknown. */
    private fun randomAddress(kind: String?): Boolean? = when (kind) {
        null -> null
        "public" -> false
        else -> true
    }
}
