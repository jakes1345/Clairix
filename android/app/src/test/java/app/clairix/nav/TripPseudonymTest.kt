package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A drive's pseudonym is well formed, stable for a while, then replaced; a new drive starts afresh. */
class TripPseudonymTest {

    @Test
    fun aPseudonymIsWellFormedAndStableUntilItRotates() {
        var now = 1_760_000_000_000L
        val trip = TripPseudonym(rotateAfterMs = 10 * 60_000L, clock = { now })
        val first = trip.current()
        assertTrue(first, TripPseudonym.FORMAT.matches(first))
        now += 9 * 60_000L
        assertEquals(first, trip.current())
        now += 2 * 60_000L
        val second = trip.current()
        assertNotEquals(first, second)
        assertTrue(second, TripPseudonym.FORMAT.matches(second))
    }

    @Test
    fun aNewDriveIsUnrelatedToTheLast() {
        val trip = TripPseudonym()
        val before = trip.current()
        trip.reset()
        assertNotEquals(before, trip.current())
    }

    @Test
    fun pseudonymsDoNotRepeat() {
        val seen = HashSet<String>()
        val trip = TripPseudonym()
        repeat(200) {
            trip.reset()
            assertTrue(seen.add(trip.current()))
        }
    }
}
