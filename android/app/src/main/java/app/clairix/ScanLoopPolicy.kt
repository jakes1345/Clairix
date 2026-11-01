package app.clairix

import app.clairix.analysis.Fix

/**
 * The decisions [ScanService]'s publish loop makes on each pass, apart from the
 * radios and the flows they feed: which devices are listed and in what order, what
 * the map draws, when the phone-health and Wi-Fi checks are due, which Wi-Fi
 * findings count as bait, when a drive has ended, and when a persistent device, a
 * cell anomaly and Wi-Fi bait together make correlated surveillance. How often the
 * loop runs and when it may rest is [app.clairix.analysis.ScanPacing]'s business.
 */
internal object ScanLoopPolicy {

    /** Phone health is read at once, then this often while the screen is on… */
    const val HEALTH_EVERY_SCREEN_ON_MS = 30_000L
    /** …and this often while it is off; the microphone and camera are watched through callbacks in between. */
    const val HEALTH_EVERY_SCREEN_OFF_MS = 5 * 60_000L
    /** A drive has ended once no fix has shown driving speed for this long: the car is parked. */
    const val TRIP_END_MS = 5 * 60_000L

    /**
     * Worth the owner's attention: following, persistent, an identified tracker seen
     * three times, or anything seen five times. A phone carried past once is tracked
     * but not listed.
     */
    fun worthListing(d: Detection): Boolean =
        d.following || d.persistent || (d.tracker != null && d.sightings >= 3) || d.sightings >= 5

    /** What the list shows: [worthListing], following first, then persistent, then identified, then most seen. */
    fun listed(all: List<Detection>): List<Detection> =
        all.filter(::worthListing).sortedWith(
            compareByDescending<Detection> { it.following }
                .thenByDescending { it.persistent }
                .thenByDescending { it.identified }
                .thenByDescending { it.sightings }
        )

    /**
     * The devices the map draws, ordered by key rather than by the list's sighting
     * order: sightings change on nearly every advert, and an order that changed with
     * them made every pass's map data compare unequal, redraw, and re-request expired
     * tiles. A device the owner marked as theirs ([trusted]) or as found is not drawn
     * as moving with her.
     */
    fun deviceTrails(list: List<Detection>, trusted: Set<String>): List<DeviceTrail> =
        list.filter { it.points.isNotEmpty() }.map {
            val withYou = it.following && !it.resolved && it.address !in trusted
            DeviceTrail(it.key, it.name, it.threat, withYou, it.points, it.lastHeardAt)
        }.sortedBy { it.key }

    /** The serving cell on the map, where the phone is, once the cell reading is MEDIUM or worse; none without a fix. */
    fun cellMarkers(cell: CellStatus, fix: Fix?): List<CellMarker> {
        val serving = cell.cell ?: return emptyList()
        if (cell.level.ordinal < Threat.MEDIUM.ordinal || fix == null) return emptyList()
        return listOf(CellMarker("${serving.rat.label} ${serving.cellId}", LatLon(fix.lat, fix.lon), cell.level))
    }

    /** Phone health on the loop's first pass ([cycle] 1), then every [HEALTH_EVERY_SCREEN_ON_MS] or [HEALTH_EVERY_SCREEN_OFF_MS]. */
    fun healthDue(cycle: Int, lastAt: Long, now: Long, screenOn: Boolean): Boolean =
        cycle == 1 || now - lastAt >= if (screenOn) HEALTH_EVERY_SCREEN_ON_MS else HEALTH_EVERY_SCREEN_OFF_MS

    /** The Wi-Fi scan on the first pass, then every [everyMs] ([app.clairix.analysis.ScanPacing.wifiScanEveryMs]). */
    fun wifiDue(cycle: Int, lastAt: Long, now: Long, everyMs: Long): Boolean =
        cycle == 1 || now - lastAt >= everyMs

    /**
     * The Wi-Fi findings that are bait: above LOW. An open carrier hotspot (LOW) is
     * information for the Wi-Fi tab, with no timeline event and no part in the
     * correlation.
     */
    fun bait(anomalies: List<WifiAnomaly>): List<WifiAnomaly> = anomalies.filter { it.threat.ordinal > Threat.LOW.ordinal }

    /** The timeline severity of the worst bait. */
    fun baitSeverity(threat: Threat): Severity = when (threat) {
        Threat.CRITICAL -> Severity.CRITICAL
        Threat.HIGH -> Severity.HIGH
        else -> Severity.MEDIUM
    }

    /** A drive is over: in one, and no driving speed for [TRIP_END_MS]. Fused location goes quiet once the car is parked. */
    fun tripEnded(inTrip: Boolean, lastDrivingAt: Long, now: Long): Boolean = inTrip && now - lastDrivingAt > TRIP_END_MS

    /**
     * Correlated surveillance: the persistent devices, not following and not the
     * owner's own, that ride along while the cell reading is HIGH or worse and Wi-Fi
     * bait or an answered canary is in range. Empty when the three do not coincide.
     */
    fun correlated(cellLevel: Threat, list: List<Detection>, trusted: Set<String>, bait: Boolean, canaryAnswered: Boolean): List<Detection> {
        if (cellLevel.ordinal < Threat.HIGH.ordinal || !(bait || canaryAnswered)) return emptyList()
        return list.filter { it.persistent && !it.following && it.address !in trusted }
    }
}
