package app.clairix

import app.clairix.analysis.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner loop's decisions on each pass: who is listed and in what order, what
 * the map draws, when the health and Wi-Fi checks run, which Wi-Fi findings are
 * bait, when a drive is over, and when three signals together are correlated
 * surveillance.
 */
class ScanLoopPolicyTest {

    private val now = 1_793_450_117_000L
    private val airTag = TrackerType("airtag", "AirTag", "Apple", Threat.CRITICAL, "Away from its owner")
    private val here = LatLon(40.0, -83.0)

    private fun device(
        key: String,
        sightings: Int = 1,
        tracker: TrackerType? = null,
        persistent: Boolean = false,
        following: Boolean = false,
        points: List<LatLon> = emptyList(),
        resolvedAt: Long = 0L,
        address: String = "AA:BB:CC:00:00:${key.takeLast(2).padStart(2, '0')}",
    ) = Detection(
        key = key, address = address, name = key, rssi = -70, tracker = tracker,
        threat = if (following) Threat.CRITICAL else Threat.NONE, score = 0,
        firstSeen = now - 60_000L, lastSeen = now, sightings = sightings,
        persistent = persistent, following = following,
        confidence = if (following) FollowConfidence.CONFIRMED else FollowConfidence.NONE,
        displacementM = 0.0, places = points.size, rotations = 0, addresses = 1, approxMetres = null,
        points = points, lastHeardAt = points.lastOrNull(), resolvedAt = resolvedAt,
    )

    // ── The list ────────────────────────────────────────────────────────────

    @Test
    fun aPassingPhoneIsTrackedButNotListed() {
        assertFalse(ScanLoopPolicy.worthListing(device("p1", sightings = 4)))
        assertTrue(ScanLoopPolicy.worthListing(device("p2", sightings = 5)))
        assertFalse(ScanLoopPolicy.worthListing(device("t1", sightings = 2, tracker = airTag)))
        assertTrue("a tracker needs fewer sightings", ScanLoopPolicy.worthListing(device("t2", sightings = 3, tracker = airTag)))
        assertTrue(ScanLoopPolicy.worthListing(device("f1", following = true)))
        assertTrue(ScanLoopPolicy.worthListing(device("s1", persistent = true)))
    }

    @Test
    fun followingFirstThenPersistentThenIdentifiedThenMostSeen() {
        val all = listOf(
            device("seen9", sightings = 9),
            device("tag3", sightings = 3, tracker = airTag),
            device("passer", sightings = 1),
            device("persist", sightings = 6, persistent = true),
            device("seen20", sightings = 20),
            device("follow", sightings = 2, following = true),
        )
        assertEquals(listOf("follow", "persist", "tag3", "seen20", "seen9"), ScanLoopPolicy.listed(all).map { it.key })
    }

    // ── The map ─────────────────────────────────────────────────────────────

    @Test
    fun theMapDrawsDevicesWithAPlaceInKeyOrderAndNotTheOwnersAsWithHer() {
        val list = listOf(
            device("zeta", sightings = 30, following = true, points = listOf(here)),
            device("alpha", sightings = 9, following = true, points = listOf(here, LatLon(40.01, -83.0))),
            device("nowhere", sightings = 50, following = true),
            device("mine", sightings = 12, following = true, points = listOf(here), address = "11:22:33:44:55:66"),
            device("found", sightings = 12, following = true, points = listOf(here), resolvedAt = now - 1_000L),
        )
        val trails = ScanLoopPolicy.deviceTrails(list, trusted = setOf("11:22:33:44:55:66"))
        assertEquals(listOf("alpha", "found", "mine", "zeta"), trails.map { it.key })
        assertEquals(listOf(true, false, false, true), trails.map { it.following })
        assertEquals(LatLon(40.01, -83.0), trails.first().lastHeardAt)
        // The same devices in another sighting order draw the same map.
        assertEquals(trails, ScanLoopPolicy.deviceTrails(list.reversed(), trusted = setOf("11:22:33:44:55:66")))
    }

    @Test
    fun theCellIsOnTheMapOnlyWhenItIsMediumOrWorseAndThePhoneKnowsWhereItIs() {
        val serving = ServingCell(mcc = "310", mnc = "260", tac = "100", cellId = "4242", rat = Rat.LTE, signalDbm = -90, neighbors = 0, ts = now)
        val fix = Fix(40.0, -83.0, 0f, now)
        assertEquals(
            listOf(CellMarker("4G LTE 4242", LatLon(40.0, -83.0), Threat.MEDIUM)),
            ScanLoopPolicy.cellMarkers(CellStatus(available = true, cell = serving, level = Threat.MEDIUM), fix)
        )
        assertTrue(ScanLoopPolicy.cellMarkers(CellStatus(available = true, cell = serving, level = Threat.LOW), fix).isEmpty())
        assertTrue(ScanLoopPolicy.cellMarkers(CellStatus(available = true, cell = serving, level = Threat.HIGH), null).isEmpty())
        assertTrue(ScanLoopPolicy.cellMarkers(CellStatus(available = true, cell = null, level = Threat.CRITICAL), fix).isEmpty())
    }

    // ── When the checks run ─────────────────────────────────────────────────

    @Test
    fun phoneHealthAtOnceThenEveryHalfMinuteOnOrFiveMinutesOff() {
        assertTrue("the first pass", ScanLoopPolicy.healthDue(cycle = 1, lastAt = now, now = now, screenOn = false))
        assertFalse(ScanLoopPolicy.healthDue(2, lastAt = now - 29_999L, now = now, screenOn = true))
        assertTrue(ScanLoopPolicy.healthDue(2, lastAt = now - 30_000L, now = now, screenOn = true))
        assertFalse(ScanLoopPolicy.healthDue(2, lastAt = now - 4 * 60_000L, now = now, screenOn = false))
        assertTrue(ScanLoopPolicy.healthDue(2, lastAt = now - 5 * 60_000L, now = now, screenOn = false))
    }

    @Test
    fun wifiAtOnceThenOnItsOwnInterval() {
        assertTrue(ScanLoopPolicy.wifiDue(1, lastAt = now, now = now, everyMs = 600_000L))
        assertFalse(ScanLoopPolicy.wifiDue(7, lastAt = now - 119_999L, now = now, everyMs = 120_000L))
        assertTrue(ScanLoopPolicy.wifiDue(7, lastAt = now - 120_000L, now = now, everyMs = 120_000L))
    }

    @Test
    fun aDriveEndsFiveMinutesAfterTheLastFixAtRoadSpeed() {
        assertFalse(ScanLoopPolicy.tripEnded(inTrip = true, lastDrivingAt = now - 5 * 60_000L, now = now))
        assertTrue(ScanLoopPolicy.tripEnded(inTrip = true, lastDrivingAt = now - 5 * 60_000L - 1, now = now))
        assertFalse("no drive to end", ScanLoopPolicy.tripEnded(inTrip = false, lastDrivingAt = 0L, now = now))
    }

    // ── Wi-Fi bait and correlation ──────────────────────────────────────────

    private fun wifi(bssid: String, threat: Threat, reason: String) = WifiAnomaly("net", bssid, -60, reason, threat, now)

    @Test
    fun anOpenCarrierHotspotIsNotBait() {
        val hotspot = wifi("01", Threat.LOW, "carrier_open_network")
        val twin = wifi("02", Threat.HIGH, "open_twin_of_secured")
        val canary = wifi("03", Threat.CRITICAL, "karma_canary")
        assertEquals(listOf(twin, canary), ScanLoopPolicy.bait(listOf(hotspot, twin, canary)))
        assertTrue(ScanLoopPolicy.bait(listOf(hotspot)).isEmpty())
        assertEquals(Severity.CRITICAL, ScanLoopPolicy.baitSeverity(Threat.CRITICAL))
        assertEquals(Severity.HIGH, ScanLoopPolicy.baitSeverity(Threat.HIGH))
        assertEquals(Severity.MEDIUM, ScanLoopPolicy.baitSeverity(Threat.MEDIUM))
    }

    @Test
    fun correlationNeedsAPersistentStrangerAHighCellAndBaitTogether() {
        val stranger = device("stranger", sightings = 8, persistent = true)
        val mine = device("mine", sightings = 8, persistent = true, address = "11:22:33:44:55:66")
        val follower = device("follower", sightings = 8, persistent = true, following = true)
        val list = listOf(stranger, mine, follower, device("passer", sightings = 6))
        val trusted = setOf("11:22:33:44:55:66")

        assertEquals(listOf(stranger), ScanLoopPolicy.correlated(Threat.HIGH, list, trusted, bait = true, canaryAnswered = false))
        assertEquals(listOf(stranger), ScanLoopPolicy.correlated(Threat.CRITICAL, list, trusted, bait = false, canaryAnswered = true))
        assertTrue("the cell only MEDIUM", ScanLoopPolicy.correlated(Threat.MEDIUM, list, trusted, bait = true, canaryAnswered = true).isEmpty())
        assertTrue("no bait in range", ScanLoopPolicy.correlated(Threat.CRITICAL, list, trusted, bait = false, canaryAnswered = false).isEmpty())
        assertTrue("only the owner's own", ScanLoopPolicy.correlated(Threat.HIGH, listOf(mine, follower), trusted, bait = true, canaryAnswered = false).isEmpty())
    }
}
