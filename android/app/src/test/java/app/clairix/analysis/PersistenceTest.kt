package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.clairix.Threat
import app.clairix.detect.Signatures
import org.junit.Test

/**
 * Merging two keys: only a certain stitch may hand one address the other's
 * history, but a verdict the old address already earned survives any merge.
 */
class PersistenceTest {

    private val t0 = 1_700_000_000_000L
    private val t1 = t0 + 13 * 60_000L

    private fun Tracker.see(key: String, at: Long, metresNorth: Double, type: app.clairix.TrackerType? = null) = observe(
        key = key, address = key, rssi = -60, tracker = type, approxMetres = null,
        rotations = 0, addresses = 1,
        fix = Fix(lat = 40.0 + metresNorth / 111_195.0, lon = -75.0, speed = 12f, time = at),
        hasPosition = true, now = at
    ).detection

    /**
     * The old key is heard for twelve minutes — riding along across two kilometres
     * when [moving], or sitting in one place otherwise — and the new key twice in the
     * minute after. Returns the tracker and the time of the new key's last sighting.
     */
    private fun oldEntryAndNewAddress(moving: Boolean): Pair<Tracker, Long> {
        val tracker = Tracker()
        for (i in 0..12) tracker.see("old", t0 + i * 60_000L, if (moving) i * 170.0 else 0.0)
        val old = tracker.see("old", t0 + 12 * 60_000L + 1_000, if (moving) 2_040.0 else 0.0)
        assertEquals(moving, old.following)
        assertTrue(old.persistent)
        tracker.see("new", t1, 2_100.0)
        tracker.see("new", t1 + 30_000L, 2_150.0)
        return tracker to t1 + 30_000L
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
        // A SmartTag that was already confirmed rotates; the stitch is uncertain.
        val (tracker, last) = oldEntryAndNewAddress(moving = true)
        assertTrue(tracker.merge("new", "old", certain = false))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertTrue(after.following)
        // The presence clock itself still restarted.
        assertEquals(t1, after.firstSeen)
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
        var last = tracker.see("tag", t0, 0.0)
        for (i in 1..12) last = tracker.see("tag", t0 + i * 60_000L, i * 170.0)
        assertTrue(last.following)
        assertTrue(MAX_PERSIST_THRESHOLD_MS < 15 * 60_000L)
    }

    @Test
    fun aRecognisedTrackerMovingWithYouIsCriticalAnythingElseIsHigh() {
        // Twelve minutes, two kilometres: both have moved with you.
        val tracker = Tracker()
        var tag = tracker.see("tag", t0, 0.0, Signatures.AIRTAG)
        var phone = tracker.see("phone", t0, 0.0)
        for (i in 1..13) {
            tag = tracker.see("tag", t0 + i * 60_000L, i * 170.0, Signatures.AIRTAG)
            phone = tracker.see("phone", t0 + i * 60_000L, i * 170.0)
        }
        assertTrue(tag.following)
        assertTrue(phone.following)
        assertEquals(Threat.CRITICAL, tag.threat)
        // A companion's phone looks like this too; it is not called a tracker.
        assertEquals(Threat.HIGH, phone.threat)
    }
}
