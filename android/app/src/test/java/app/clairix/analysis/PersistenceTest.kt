package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging two keys: only a certain stitch may hand one address the other's history. */
class PersistenceTest {

    private val t0 = 1_700_000_000_000L

    private fun Tracker.see(key: String, at: Long, metresNorth: Double) = observe(
        key = key, address = key, rssi = -60, tracker = null, approxMetres = null,
        rotations = 0, addresses = 1,
        fix = Fix(lat = 40.0 + metresNorth / 111_195.0, lon = -75.0, speed = 12f, time = at),
        hasPosition = true, now = at
    ).detection

    /**
     * The old key rides along for twelve minutes across two kilometres and is
     * following; the new key has been heard twice in the last minute. Returns the
     * tracker and the time of the new key's last sighting.
     */
    private fun oldFollowerAndNewAddress(): Pair<Tracker, Long> {
        val tracker = Tracker()
        for (i in 0..12) tracker.see("old", t0 + i * 60_000L, i * 170.0)
        assertTrue(tracker.see("old", t0 + 12 * 60_000L + 1_000, 2_040.0).following)
        val t1 = t0 + 13 * 60_000L
        tracker.see("new", t1, 2_100.0)
        tracker.see("new", t1 + 30_000L, 2_150.0)
        return tracker to t1 + 30_000L
    }

    @Test
    fun anUncertainMergeRestartsThePresenceClock() {
        val (tracker, last) = oldFollowerAndNewAddress()
        assertTrue(tracker.merge("new", "old", certain = false))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertFalse(after.following)
        assertFalse(after.persistent)
        assertEquals(t0 + 13 * 60_000L, after.firstSeen)
        // The identity bookkeeping still records the rotation.
        assertTrue(after.rotations >= 1)
    }

    @Test
    fun aCertainMergeKeepsTheEarliestFirstSeen() {
        val (tracker, last) = oldFollowerAndNewAddress()
        assertTrue(tracker.merge("new", "old", certain = true))
        val after = tracker.see("old", last + 30_000L, 2_200.0)
        assertEquals(t0, after.firstSeen)
        assertTrue(after.following)
    }
}
