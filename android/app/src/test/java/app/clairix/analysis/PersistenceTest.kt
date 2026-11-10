package app.clairix.analysis

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.Threat
import app.clairix.TrackerType
import app.clairix.detect.Signatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow verdict: movement the phone itself made, not a position that jumped;
 * merging two keys, where only a certain stitch may hand one address the other's
 * history and only a distinguishable one its verdict; and one threat mapping for
 * the observation and the list.
 */
class PersistenceTest {

    private val t0 = 1_700_000_000_000L
    private val t1 = t0 + 13 * 60_000L

    /**
     * One packet from [key] with the phone [metresNorth] north of the start and its
     * odometer at [travelledM] — a straight walk, unless said otherwise.
     */
    private fun Tracker.see(
        key: String, at: Long, metresNorth: Double, type: TrackerType? = null, travelledM: Double = metresNorth
    ): Detection = observe(
        key = key, address = key, rssi = -60, tracker = type, approxMetres = null,
        rotations = 0, addresses = 1,
        fix = Fix(lat = 40.0 + metresNorth / 111_195.0, lon = -75.0, speed = 12f, time = at),
        hasPosition = true, now = at, travelledM = travelledM
    ).detection

    /**
     * [key] heard twice a minute for [minutes] minutes from [from]: [metresPerMinute]
     * further on each minute, the second packet of the minute 85 m on from the first,
     * so that every place is heard under two fixes; or in one spot when not moving.
     * Returns the last detection.
     */
    private fun Tracker.ride(key: String, from: Long, minutes: Int, metresPerMinute: Double, type: TrackerType? = null): Detection {
        var last: Detection? = null
        for (i in 0..minutes) {
            val m = i * metresPerMinute
            see(key, from + i * 60_000L, m, type)
            last = see(key, from + i * 60_000L + 30_000L, if (metresPerMinute > 0) m + 85.0 else m, type)
        }
        return last!!
    }

    /** One packet from [key] under the phone's real track, whose newest fix is [fix]. */
    private fun Tracker.hear(key: String, type: TrackerType?, fix: Fix, track: LocationTrack, now: Long): Detection = observe(
        key = key, address = key, rssi = -70, tracker = type, approxMetres = null, rotations = 0, addresses = 1,
        fix = fix, hasPosition = true, now = now, travelledM = track.travelledM
    ).detection

    /**
     * The old key is heard for twelve minutes — riding along across two kilometres
     * when [moving], or sitting in one place otherwise — and the new key twice in the
     * minute after. Returns the tracker and the time of the new key's last sighting.
     */
    private fun oldEntryAndNewAddress(moving: Boolean): Pair<Tracker, Long> {
        val tracker = Tracker()
        tracker.ride("old", t0, 11, if (moving) 170.0 else 0.0)
        val old = tracker.see("old", t0 + 12 * 60_000L + 1_000, if (moving) 2_040.0 else 0.0)
        assertEquals(moving, old.following)
        assertTrue(old.persistent)
        tracker.see("new", t1, 2_100.0)
        tracker.see("new", t1 + 30_000L, 2_150.0)
        return tracker to t1 + 30_000L
    }

    @Test
    fun aStationaryPhoneWithOneOutlierFixIsNotFollowing() {
        val tracker = Tracker()
        val track = LocationTrack()
        var t = t0
        var last: Detection? = null
        // Twelve minutes at home: a fix every fifteen seconds, the neighbour's Tile heard under each.
        for (i in 0 until 48) {
            val f = Fix(40.0, -75.0, null, t, 10f)
            track.update(f)
            last = tracker.hear("tile", Signatures.TILE, f, track, t)
            if (i == 20) {
                // One optimistic network fix 400 m away, current for the rest of the interval.
                val bad = Fix(40.0 + 400.0 / 111_195.0, -75.0, null, t + 7_000, 30f)
                track.update(bad)
                repeat(5) { k -> tracker.hear("tile", Signatures.TILE, bad, track, t + 7_000 + k * 1_500L) }
            }
            t += 15_000
        }
        val d = last!!
        assertTrue(d.persistent)
        assertFalse(d.following)
        assertEquals(FollowConfidence.NOT_MOVED_ENOUGH, d.confidence)
        assertEquals(0.0, d.displacementM, 0.0)
        // The outlier is still remembered as a place, as evidence; it just proves nothing.
        assertEquals(2, d.places)
        assertNotEquals(Threat.CRITICAL, d.threat)
        assertFalse(tracker.snapshot(t).single { it.key == "tile" }.following)
    }

    @Test
    fun aFourHundredMetreWalkWithFixesEveryTwentyFiveMetresIsFollowing() {
        // A device in the bag: heard for ten minutes at home while the phone sits on a
        // satellite fix, then twice under each fix of a 400 m walk with a fix every 25 m.
        // The odometer is the real one, which runs a fix behind the phone.
        val tracker = Tracker()
        val track = LocationTrack()
        var t = t0
        var last: Detection? = null
        repeat(40) {
            val f = Fix(40.0, -75.0, 0.1f, t, 8f)
            track.update(f)
            last = tracker.hear("box", null, f, track, t)
            t += 15_000
        }
        assertFalse(last!!.following)
        for (i in 1..16) {
            val f = Fix(40.0 + i * 25.0 / 111_195.0, -75.0, 1.4f, t, 8f)
            track.update(f)
            tracker.hear("box", null, f, track, t)
            last = tracker.hear("box", null, f, track, t + 9_000)
            t += 18_000
        }
        val d = last!!
        assertTrue(d.following)
        assertEquals(FollowConfidence.CONFIRMED, d.confidence)
        assertTrue("displacement ${d.displacementM}", d.displacementM >= 300.0)
        // Nothing identifies it, so it is travelling with you, not a tracker on you.
        assertEquals(Threat.HIGH, d.threat)
    }

    @Test
    fun aWifiPositionMislocatedFromHomeIsNotFollowing() {
        // Four minutes on satellite fixes at home with the neighbour's Tile heard every
        // five seconds; then the phone settles and an hour of Wi-Fi fixes, every three
        // minutes, put it 400 m away give or take 30 m. The Tile is heard all along.
        val tracker = Tracker()
        val track = LocationTrack()
        var t = t0
        var last: Detection? = null
        repeat(16) {
            val f = Fix(40.0, -75.0, 0.1f, t, 8f)
            track.update(f)
            repeat(3) { k -> last = tracker.hear("tile", Signatures.TILE, f, track, t + k * 5_000L) }
            t += 15_000
        }
        for (j in WIFI_JITTER_M) {
            val f = Fix(40.0 + (400.0 + j) / 111_195.0, -75.0, null, t, 30f)
            track.update(f)
            repeat(18) { k -> last = tracker.hear("tile", Signatures.TILE, f, track, t + k * 10_000L) }
            t += 180_000
        }
        val d = last!!
        assertTrue(d.persistent)
        assertFalse(d.following)
        assertEquals(0.0, d.displacementM, 0.0)
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun anUncertainMergeRestartsThePresenceClock() {
        // The old key never confirmed. Joined with its twelve minutes in one place,
        // the new key's two kilometres would read as a follower; uncertain, they must not.
        val (tracker, last) = oldEntryAndNewAddress(moving = false)
        assertTrue(tracker.merge("new", "old", certain = false))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertFalse(after.following)
        assertFalse(after.persistent)
        assertEquals(t1, after.firstSeen)
        // The identity bookkeeping still records the rotation.
        assertTrue(after.rotations >= 1)
    }

    @Test
    fun aCertainMergeKeepsTheEarliestFirstSeen() {
        val (tracker, last) = oldEntryAndNewAddress(moving = false)
        assertTrue(tracker.merge("new", "old", certain = true))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertEquals(t0, after.firstSeen)
        assertTrue(after.following)
    }

    @Test
    fun aConfirmedTrackerStaysFollowingAcrossAnUncertainMerge() {
        // A SmartTag that was already confirmed rotates; the stitch is uncertain but
        // something besides the cadence told the two addresses apart.
        val (tracker, last) = oldEntryAndNewAddress(moving = true)
        assertTrue(tracker.merge("new", "old", certain = false))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertTrue(after.following)
        // The presence clock itself still restarted.
        assertEquals(t1, after.firstSeen)
    }

    @Test
    fun anIndistinguishableStitchDoesNotInheritTheLatch() {
        // A confirmed tracker's address goes quiet and a same-product unit with the same
        // cadence appears inside the hand-over window. The stitch is uncertain and
        // indistinguishable: the row must not call the new address confirmed.
        val (tracker, last) = oldEntryAndNewAddress(moving = true)
        assertTrue(tracker.merge("new", "old", certain = false, indistinguishable = true))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertFalse(after.following)
        assertNotEquals(FollowConfidence.CONFIRMED, after.confidence)
        assertEquals(t1, after.firstSeen)
        assertFalse(tracker.snapshot(last + 30_000L).single { it.key == "old" }.following)
    }

    @Test
    fun aConfirmedTrackerIsKeptForTheFollowingRetention() {
        val (tracker, last) = oldEntryAndNewAddress(moving = true)
        tracker.merge("new", "old", certain = false)
        tracker.see("old", last + 30_000L, 2_200.0)
        // Two hours of silence is far past the 30-minute cutoff for ordinary devices.
        val later = last + 2 * 3600_000L
        tracker.prune(later)
        assertTrue(tracker.snapshot(later).any { it.key == "old" && it.following })
    }

    @Test
    fun anUnconfirmedDeviceIsPrunedOnTheShortCutoff() {
        val (tracker, last) = oldEntryAndNewAddress(moving = false)
        val later = last + 2 * 3600_000L
        tracker.prune(later)
        assertTrue(tracker.snapshot(later).none { it.key == "old" })
    }

    @Test
    fun theThresholdIsCappedBelowTheFastestRotation() {
        // A threshold set above the cap must not make a tracker that rotates every
        // fifteen minutes unreachable: twelve minutes under one address is enough.
        val tracker = Tracker(persistMs = { 30 * 60_000L })
        val last = tracker.ride("tag", t0, 12, 170.0)
        assertTrue(last.following)
        assertTrue(MAX_PERSIST_THRESHOLD_MS < 15 * 60_000L)
    }

    @Test
    fun aRecognisedTrackerMovingWithYouIsCriticalAnythingElseIsHigh() {
        // Thirteen minutes, two kilometres: both have moved with you.
        val tracker = Tracker()
        val tag = tracker.ride("tag", t0, 13, 170.0, Signatures.AIRTAG)
        val phone = tracker.ride("phone", t0, 13, 170.0)
        assertTrue(tag.following)
        assertTrue(phone.following)
        assertEquals(Threat.CRITICAL, tag.threat)
        // A companion's phone looks like this too; it is not called a tracker.
        assertEquals(Threat.HIGH, phone.threat)
    }

    @Test
    fun aNearOwnerTagThatSeparatesIsUpgradedAndCriticalOnceFollowing() {
        val tracker = Tracker()
        // Twelve minutes riding along as "owner nearby": it moved with you, but a tag
        // next to its owner's phone is somebody's keys, so it is HIGH, not a tracker verdict.
        val near = tracker.ride("tag", t0, 12, 170.0, Signatures.AIRTAG_NEAR_OWNER)
        assertTrue(near.following)
        assertEquals(Signatures.AIRTAG_NEAR_OWNER, near.tracker)
        assertEquals(Threat.HIGH, near.threat)
        assertEquals(0L, near.separatedAt)
        // The owner's phone drops out of range: the same address now advertises as separated.
        val tSep = t0 + 13 * 60_000L
        val separated = tracker.observe(
            key = "tag", address = "tag", rssi = -60, tracker = Signatures.AIRTAG, approxMetres = null,
            rotations = 0, addresses = 1,
            fix = Fix(lat = 40.0 + 2_300.0 / 111_195.0, lon = -75.0, speed = 12f, time = tSep),
            hasPosition = true, now = tSep, travelledM = 2_300.0
        )
        assertEquals(Signatures.AIRTAG, separated.detection.tracker)
        assertEquals(tSep, separated.detection.separatedAt)
        assertEquals(Threat.CRITICAL, separated.detection.threat)
        assertTrue(separated.escalated)
        assertFalse(separated.becameFollowing)
        // A later near-owner frame does not take it back.
        val again = tracker.see("tag", tSep + 10_000L, 2_310.0, Signatures.AIRTAG_NEAR_OWNER)
        assertEquals(Signatures.AIRTAG, again.tracker)
        assertEquals(Threat.CRITICAL, again.threat)
        assertEquals(tSep, again.separatedAt)
        assertEquals(Threat.CRITICAL, tracker.snapshot(tSep + 10_000L).single { it.key == "tag" }.threat)
    }

    @Test
    fun aSeparationUnderANewAddressIsReportedOnce() {
        // The tag rode along as "owner nearby" and was confirmed travelling with you; it
        // separates and comes back under a new address, which a distinguishable stitch
        // joins to the old one. The verdict it already earned carries over, and the rise
        // to CRITICAL is reported by the next observation, and only by that one.
        val tracker = Tracker()
        val near = tracker.ride("old", t0, 12, 170.0, Signatures.AIRTAG_NEAR_OWNER)
        assertEquals(Threat.HIGH, near.threat)
        tracker.see("new", t1, 2_300.0, Signatures.AIRTAG)
        tracker.see("new", t1 + 30_000L, 2_350.0, Signatures.AIRTAG)
        assertTrue(tracker.merge("new", "old", certain = false, indistinguishable = false))
        val first = tracker.observe(
            key = "old", address = "old", rssi = -60, tracker = null, approxMetres = null, rotations = 0, addresses = 1,
            fix = Fix(lat = 40.0 + 2_400.0 / 111_195.0, lon = -75.0, speed = 12f, time = t1 + 60_000L),
            hasPosition = true, now = t1 + 60_000L, travelledM = 2_400.0
        )
        assertTrue(first.detection.following)
        assertEquals(Signatures.AIRTAG, first.detection.tracker)
        assertEquals(Threat.CRITICAL, first.detection.threat)
        assertEquals(t1, first.detection.separatedAt)
        assertTrue(first.escalated)
        assertFalse(first.becameFollowing)
        val second = tracker.observe(
            key = "old", address = "old", rssi = -60, tracker = null, approxMetres = null, rotations = 0, addresses = 1,
            fix = Fix(lat = 40.0 + 2_450.0 / 111_195.0, lon = -75.0, speed = 12f, time = t1 + 90_000L),
            hasPosition = true, now = t1 + 90_000L, travelledM = 2_450.0
        )
        assertFalse(second.escalated)
    }

    @Test
    fun aMergeKeepsTheHigherClassificationAndDatesTheSeparation() {
        val tracker = Tracker()
        tracker.ride("old", t0, 11, 0.0, Signatures.AIRTAG_NEAR_OWNER)
        tracker.see("new", t1, 0.0, Signatures.AIRTAG)
        tracker.see("new", t1 + 30_000L, 0.0, Signatures.AIRTAG)
        assertTrue(tracker.merge("new", "old", certain = true))
        val after = tracker.see("old", t1 + 60_000L, 0.0)
        assertEquals(Signatures.AIRTAG, after.tracker)
        assertEquals(t1, after.separatedAt)
    }

    @Test
    fun theObservationAndTheListAgreeOnTheThreat() {
        val tracker = Tracker()
        val tag = tracker.ride("tag", t0, 13, 170.0, Signatures.AIRTAG)
        val phone = tracker.ride("phone", t0, 13, 170.0)
        val keys = tracker.ride("keys", t0, 13, 170.0, Signatures.FINDMY_NEAR_OWNER)
        val listed = tracker.snapshot(t0 + 14 * 60_000L).associateBy { it.key }
        assertTrue(tag.following && phone.following && keys.following)
        assertEquals(Threat.CRITICAL, tag.threat)
        assertEquals(Threat.HIGH, phone.threat)
        // Still within range of its owner's phone: moving with you, but not a tracker verdict.
        assertEquals(Threat.HIGH, keys.threat)
        for (d in listOf(tag, phone, keys)) assertEquals(d.key, d.threat, listed.getValue(d.key).threat)
    }

    @Test
    fun aFindHubTagInItsOrdinaryFrameIsTravellingWithYouUntilItSeparates() {
        val tracker = Tracker()
        // Frame 0x40 for twelve minutes and two kilometres: it moved with you, but a Find
        // Hub tag sends that frame while its owner is near, so it is not a tracker verdict.
        val ordinary = tracker.ride("hub", t0, 12, 170.0, Signatures.GOOGLE_FIND_HUB)
        assertTrue(ordinary.following)
        assertEquals(Threat.HIGH, ordinary.threat)
        assertEquals(0L, ordinary.separatedAt)
        // Frame 0x41: unwanted-tracking protection mode, away from its owner.
        val tSep = t0 + 13 * 60_000L
        val separated = tracker.observe(
            key = "hub", address = "hub", rssi = -60, tracker = Signatures.GOOGLE_FIND_HUB_SEPARATED, approxMetres = null,
            rotations = 0, addresses = 1,
            fix = Fix(lat = 40.0 + 2_300.0 / 111_195.0, lon = -75.0, speed = 12f, time = tSep),
            hasPosition = true, now = tSep, travelledM = 2_300.0
        )
        assertEquals(Signatures.GOOGLE_FIND_HUB_SEPARATED, separated.detection.tracker)
        assertEquals(Threat.CRITICAL, separated.detection.threat)
        assertEquals(tSep, separated.detection.separatedAt)
        assertTrue(separated.escalated)
        // An ordinary frame afterwards does not take it back.
        val again = tracker.see("hub", tSep + 10_000L, 2_310.0, Signatures.GOOGLE_FIND_HUB)
        assertEquals(Signatures.GOOGLE_FIND_HUB_SEPARATED, again.tracker)
        assertEquals(Threat.CRITICAL, again.threat)
    }

    @Test
    fun anIBeaconThatMovesWithYouIsTravellingWithYouNotATracker() {
        val tracker = Tracker()
        val beacon = tracker.ride("beacon", t0, 13, 170.0, Signatures.IBEACON)
        assertTrue(beacon.following)
        assertEquals(Threat.HIGH, beacon.threat)
    }

    @Test
    fun aFoundTrackerStopsCountingAndIsWatchedAfreshAfterAnHourAway() {
        val tracker = Tracker()
        val tag = tracker.ride("tag", t0, 13, 170.0, Signatures.AIRTAG)
        assertTrue(tag.following)
        val tFound = t0 + 14 * 60_000L
        val found = tracker.resolve("tag", found = true, now = tFound)!!
        assertEquals(tFound, found.resolvedAt)
        assertTrue(found.resolved)
        // Still heard (in her hand, in the car on the way to the police): it stays found,
        // and nothing about it is raised.
        val held = tracker.observe(
            key = "tag", address = "tag", rssi = -60, tracker = Signatures.AIRTAG, approxMetres = null,
            rotations = 0, addresses = 1,
            fix = Fix(lat = 40.0 + 2_500.0 / 111_195.0, lon = -75.0, speed = 12f, time = tFound + 60_000L),
            hasPosition = true, now = tFound + 60_000L, travelledM = 2_500.0
        )
        assertTrue(held.detection.resolved)
        assertFalse(held.becameFollowing)
        assertFalse(held.escalated)
        assertTrue(tracker.snapshot(tFound + 60_000L).single().resolved)

        // Quiet for over an hour, then heard again: it starts over, found no more, and
        // has to travel with you again before it is raised again.
        val tBack = tFound + 60_000L + REARM_AFTER_MS + 60_000L
        val back = tracker.see("tag", tBack, 5_000.0, Signatures.AIRTAG)
        assertFalse(back.resolved)
        assertFalse(back.following)
        assertEquals(tBack, back.firstSeen)
        val again = tracker.ride("tag", tBack + 30_000L, 13, 170.0, Signatures.AIRTAG)
        assertTrue(again.following)
        assertEquals(Threat.CRITICAL, again.threat)
    }

    @Test
    fun foundCanBeTakenBack() {
        val tracker = Tracker()
        tracker.ride("tag", t0, 13, 170.0, Signatures.AIRTAG)
        tracker.resolve("tag", found = true, now = t1)
        val back = tracker.resolve("tag", found = false, now = t1 + 1_000L)!!
        assertFalse(back.resolved)
        assertTrue(back.following)
        assertEquals(null, tracker.resolve("nothing", found = true, now = t1))
    }

    @Test
    fun foundCarriesAcrossARotationButNotOntoAnotherUnitOfTheProduct() {
        fun foundThenRotated(certain: Boolean, indistinguishable: Boolean): Detection {
            val tracker = Tracker()
            tracker.ride("old", t0, 11, 0.0, Signatures.AIRTAG)
            tracker.resolve("old", found = true, now = t0 + 12 * 60_000L)
            tracker.see("new", t1, 0.0, Signatures.AIRTAG)
            tracker.see("new", t1 + 30_000L, 0.0, Signatures.AIRTAG)
            assertTrue(tracker.merge("new", "old", certain, indistinguishable))
            return tracker.snapshot(t1 + 30_000L).single()
        }
        assertTrue(foundThenRotated(certain = true, indistinguishable = false).resolved)
        assertTrue(foundThenRotated(certain = false, indistinguishable = false).resolved)
        // Two units of one product only their cadence told apart: the new address may be the other one.
        assertFalse(foundThenRotated(certain = false, indistinguishable = true).resolved)
    }

    private companion object {
        /** How far each Wi-Fi fix lands from the mislocated spot, metres north. */
        val WIFI_JITTER_M = doubleArrayOf(
            0.0, 25.0, -20.0, 30.0, -10.0, 15.0, -25.0, 20.0, 5.0, -30.0,
            10.0, -15.0, 25.0, -5.0, 0.0, 20.0, -20.0, 10.0, -10.0, 30.0
        )
    }

    @Test
    fun aTireSensorThatRidesAlongIsATravellerNotARecognisedTrackerAndKeepsItsLatestReading() {
        val tracker = Tracker()
        val front = app.clairix.detect.TirePressure.Reading(
            app.clairix.detect.TirePressure.Format.VALVE_CAP, "80:EA:CA:10:8A:78", wheel = 1, pressureKpa = 230.0
        )
        // Twelve minutes along a road, then a packet carrying a reading.
        tracker.ride("tire", t0, 12, 170.0, Signatures.TIRE_SENSOR)
        val at = t0 + 12 * 60_000L + 45_000L
        val d = tracker.observe(
            key = "tire", address = "tire", rssi = -70, tracker = Signatures.TIRE_SENSOR,
            approxMetres = null, rotations = 0, addresses = 1, fix = null, hasPosition = true, now = at,
            tire = front.copy(pressureKpa = 242.0)
        ).detection
        assertTrue(d.following)
        // Moving with you, it is a vehicle near yours or your own: HIGH, held for the
        // journey's notice, never the CRITICAL of a tag away from its owner.
        assertEquals(Threat.HIGH, d.threat)
        assertEquals(242.0, d.tire!!.pressureKpa!!, 1e-9)
        assertEquals("80:EA:CA:10:8A:78", d.tire!!.sensorId)

        // A packet with no reading (a scan response) does not blank out the last one,
        // and a merge carries it onto the surviving key.
        tracker.observe(
            key = "tire", address = "tire", rssi = -70, tracker = Signatures.TIRE_SENSOR,
            approxMetres = null, rotations = 0, addresses = 1, fix = null, hasPosition = true, now = at + 30_000L
        )
        tracker.merge("tire", "other", certain = true)
        val merged = tracker.snapshot(at + 60_000L).single { it.key == "other" }
        assertEquals(242.0, merged.tire!!.pressureKpa!!, 1e-9)
    }
}
