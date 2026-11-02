package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often Arti's state is read, and when Tor may sleep until the next request. */
class TorPacingTest {

    private val t0 = 1_000_000L
    private val min = 60_000L

    @Test
    fun bootstrapIsReadEverySecondAndAReadyTorOnceAMinute() {
        assertEquals(1_000L, TorPacing.pollMs(ready = false))
        assertEquals(60_000L, TorPacing.pollMs(ready = true))
    }

    @Test
    fun torSleepsOnlyWhenNothingKeepsItAndNothingHasUsedItForFiveMinutes() {
        assertEquals(5 * min, TorPacing.IDLE_SLEEP_MS)
        assertTrue(TorPacing.shouldSleep(needed = false, lastUseAt = t0, now = t0 + 5 * min))
        assertTrue(TorPacing.shouldSleep(needed = false, lastUseAt = t0, now = t0 + 3 * 60 * min))
        // A request a moment ago, perhaps still reading its answer.
        assertFalse(TorPacing.shouldSleep(needed = false, lastUseAt = t0, now = t0 + 5 * min - 1))
        assertFalse(TorPacing.shouldSleep(needed = false, lastUseAt = t0, now = t0 + 30_000L))
    }

    @Test
    fun theBackgroundConnectionOrAHeldLinkKeepsTorRunningHoweverLongItIsQuiet() {
        assertFalse(TorPacing.shouldSleep(needed = true, lastUseAt = t0, now = t0 + 10 * 60 * min))
    }

    @Test
    fun noRequestThroughTorOutlastsTheIdleWindow() {
        // The longest call timeout through Tor: 25 s, times four for Tor's circuits.
        assertTrue(TorPacing.IDLE_SLEEP_MS > 4 * 25_000L)
    }
}
