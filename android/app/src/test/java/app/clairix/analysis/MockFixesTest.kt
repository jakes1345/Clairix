package app.clairix.analysis

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.security.SosLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fix an app supplies through Android's mock-location setting never stands for where the
 * phone is: it cannot pin the phone so that a tracker riding along is never confirmed, nor
 * walk it so that a neighbour's tag looks like a follower, and an SOS never carries one.
 * The scanner's side is driven here as ScanService drives it: each fix through
 * [MockFixes.take], then each packet heard under the track's current fix.
 */
class MockFixesTest {

    private val t0 = 1_700_000_000_000L

    /** One fix through [MockFixes], and [key] heard twice under whatever the track then holds. */
    private fun Tracker.fixAndHear(key: String, track: LocationTrack, fix: Fix, mock: Boolean): Detection {
        MockFixes.take(track, fix, mock)
        hear(key, track, fix.time)
        return hear(key, track, fix.time + 9_000)
    }

    private fun Tracker.hear(key: String, track: LocationTrack, now: Long): Detection = observe(
        key = key, address = key, rssi = -60, tracker = null, approxMetres = null, rotations = 0, addresses = 1,
        fix = track.current, hasPosition = track.hasFix(), now = now, travelledM = track.travelledM
    ).detection

    /** Ten minutes at home on real satellite fixes every fifteen seconds, [key] heard under each. Returns the time after. */
    private fun Tracker.tenMinutesAtHome(key: String, track: LocationTrack): Long {
        var t = t0
        repeat(40) {
            MockFixes.take(track, Fix(40.0, -75.0, 0.1f, t, 8f), mock = false)
            hear(key, track, t)
            t += 15_000
        }
        return t
    }

    /** The phone [metresNorth] of home, walking. */
    private fun walking(metresNorth: Double, at: Long) = Fix(40.0 + metresNorth / 111_195.0, -75.0, 1.4f, at, 8f)

    @Test
    fun aRealWalkWithTheDeviceConfirmsFollowing() {
        // The scenario the others change one thing in: without a mock fix, it follows.
        val tracker = Tracker()
        val track = LocationTrack()
        var t = tracker.tenMinutesAtHome("box", track)
        var last: Detection? = null
        for (i in 1..16) {
            last = tracker.fixAndHear("box", track, walking(i * 25.0, t), mock = false)
            t += 18_000
        }
        assertTrue(last!!.following)
        assertEquals(FollowConfidence.CONFIRMED, last.confidence)
    }

    @Test
    fun aPositionPinnedByAMockAppNeitherConfirmsNorClearsTheDevice() {
        // The same walk, with an app pinning the phone at home: before, every fix said the
        // phone had not moved, and the device read "Nearby, not following".
        val tracker = Tracker()
        val track = LocationTrack()
        var t = tracker.tenMinutesAtHome("box", track)
        var last: Detection? = null
        for (i in 1..16) {
            last = tracker.fixAndHear("box", track, Fix(40.0, -75.0, 0f, t, 3f), mock = true)
            t += 18_000
        }
        val d = last!!
        assertFalse(d.following)
        assertTrue(d.persistent)
        assertEquals(FollowConfidence.NO_POSITION, d.confidence)
        assertFalse(track.hasFix())
        assertNull(track.current)
    }

    @Test
    fun aWalkInventedByAMockAppDoesNotMakeANeighboursTagFollow() {
        // The phone sits at home beside a neighbour's tag while an app walks its position
        // 400 m up the road. Taken as real, the tag would be heard 400 m apart.
        val tracker = Tracker()
        val track = LocationTrack()
        var t = tracker.tenMinutesAtHome("neighbour", track)
        var last: Detection? = null
        for (i in 1..16) {
            last = tracker.fixAndHear("neighbour", track, walking(i * 25.0, t), mock = true)
            t += 18_000
        }
        val d = last!!
        assertFalse(d.following)
        assertEquals(FollowConfidence.NO_POSITION, d.confidence)
        assertEquals(0.0, d.displacementM, 0.0)
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun aRealFixAfterAMockSpellBringsThePositionBack() {
        val track = LocationTrack()
        val home = Fix(40.0, -75.0, 0.1f, t0, 8f)
        assertTrue(MockFixes.take(track, home, mock = false))
        assertTrue(track.hasFix())
        assertFalse(MockFixes.take(track, Fix(41.0, -75.0, 0f, t0 + 15_000, 3f), mock = true))
        assertFalse(track.hasFix())
        val back = Fix(40.0, -75.0, 0.1f, t0 + 30_000, 8f)
        assertTrue(MockFixes.take(track, back, mock = false))
        assertEquals(back, track.current)
    }

    @Test
    fun theScreensSayPositionsAreFakedOnlyWhileTheyAre() {
        assertFalse(PositionState().faking(t0))
        val faked = PositionState(mockFixAt = t0)
        assertTrue(faked.faking(t0))
        assertTrue(faked.faking(t0 + MockFixes.MEMORY_MS))
        // Nothing heard from the app for a while: no longer said.
        assertFalse(faked.faking(t0 + MockFixes.MEMORY_MS + 1))
        // A clock set back does not end it early.
        assertTrue(faked.faking(t0 - 60_000L))
    }

    @Test
    fun anSosNeverCarriesAFakedOrStalePosition() {
        assertTrue(SosLocation.usable(mock = false, ageMs = 30_000L, maxAgeMs = SosLocation.LAST_KNOWN_MAX_AGE_MS))
        assertFalse(SosLocation.usable(mock = true, ageMs = 0L, maxAgeMs = SosLocation.LAST_KNOWN_MAX_AGE_MS))
        assertFalse(SosLocation.usable(mock = true, ageMs = 0L, maxAgeMs = Long.MAX_VALUE))
        assertFalse(SosLocation.usable(mock = false, ageMs = SosLocation.LAST_KNOWN_MAX_AGE_MS + 1, maxAgeMs = SosLocation.LAST_KNOWN_MAX_AGE_MS))
        // A fresh fix of unknown age is taken; a last known one of unknown age is not.
        assertTrue(SosLocation.usable(mock = false, ageMs = Long.MAX_VALUE, maxAgeMs = Long.MAX_VALUE))
        assertFalse(SosLocation.usable(mock = false, ageMs = Long.MAX_VALUE, maxAgeMs = SosLocation.LAST_KNOWN_MAX_AGE_MS))
    }
}
