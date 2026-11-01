package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disappearing-messages sweep: no timer while nothing counts down, a sleep
 * exactly until the next message is due, and every message still gone on time.
 */
class ExpiryScheduleTest {

    private val now = 1_700_000_000_000L

    @Test
    fun nothingCountingDownMeansNoWakeAtAll() {
        assertNull(ExpirySchedule.waitMs(nextExpiry = null, failed = false, now = now))
        assertFalse(ExpirySchedule.due(null, now))
    }

    @Test
    fun theSweepSleepsExactlyUntilTheNextMessageIsDueHoweverFarAway() {
        assertEquals(30_000L, ExpirySchedule.waitMs(now + 30_000L, failed = false, now = now))
        // An hour, a week: no cap, no polling on the way.
        assertEquals(3_600_000L, ExpirySchedule.waitMs(now + 3_600_000L, failed = false, now = now))
        val fourWeeks = 28L * 24 * 3_600_000L
        assertEquals(fourWeeks, ExpirySchedule.waitMs(now + fourWeeks, failed = false, now = now))
    }

    @Test
    fun aMessageDueNowOrOverdueGoesAtOnceAndMessagesDueTogetherInOnePass() {
        assertTrue(ExpirySchedule.due(now, now))
        assertTrue(ExpirySchedule.due(now - 5_000L, now))
        assertFalse(ExpirySchedule.due(now + 1L, now))
        assertEquals(ExpirySchedule.MIN_WAIT_MS, ExpirySchedule.waitMs(now - 5_000L, failed = false, now = now))
        assertEquals(ExpirySchedule.MIN_WAIT_MS, ExpirySchedule.waitMs(now + 10L, failed = false, now = now))
    }

    @Test
    fun aFailedPassIsTriedAgainInAMinuteRatherThanDropped() {
        assertEquals(ExpirySchedule.RETRY_MS, ExpirySchedule.waitMs(null, failed = true, now = now))
        assertEquals(60_000L, ExpirySchedule.RETRY_MS)
    }

    @Test
    fun aMessageGoesOnTimeAcrossASequenceOfPasses() {
        // Two messages counting down, ten and twenty seconds out: the sweep wakes at
        // each due time and stops once the store has nothing left counting down.
        val due = sortedSetOf(now + 10_000L, now + 20_000L)
        var clock = now
        val deletedAt = ArrayList<Long>()
        while (true) {
            val gone = due.filter { it <= clock }
            due.removeAll(gone.toSet())
            gone.forEach { deletedAt += clock }
            val next = due.firstOrNull()
            val wait = ExpirySchedule.waitMs(next, failed = false, now = clock) ?: break
            clock += wait
        }
        assertEquals(listOf(now + 10_000L, now + 20_000L), deletedAt)
        assertEquals(now + 20_000L, clock)
    }
}
