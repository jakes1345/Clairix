package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResyncLimiterTest {

    private val t0 = 1_760_000_000_000L

    @Test
    fun oneResyncPerContactAMinute() {
        val l = ResyncLimiter()
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("111111111", t0, t0))
        assertSame(ResyncLimiter.Decision.TooSoonForContact, l.tryAcquire("111111111", t0, t0 + 59_999))
        // Another contact is not held up by the first.
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("222222222", t0, t0 + 1))
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("111111111", t0, t0 + 60_000))
    }

    @Test
    fun atMostTenAnHourInAll() {
        val l = ResyncLimiter()
        for (i in 0 until ResyncLimiter.MAX_PER_HOUR) {
            assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("10000000$i", t0, t0 + i * 1000L))
        }
        val refused = l.tryAcquire("999999999", t0, t0 + 20_000)
        assertTrue(refused is ResyncLimiter.Decision.HourlyCapReached)
        assertEquals(t0 + ResyncLimiter.HOUR_MS, (refused as ResyncLimiter.Decision.HourlyCapReached).retryAtMs)
        assertEquals(10, l.usedThisHour(t0 + 20_000))
        // The window slides: once the first is an hour old, one slot frees.
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("999999999", t0, t0 + ResyncLimiter.HOUR_MS))
        assertTrue(l.tryAcquire("888888888", t0, t0 + ResyncLimiter.HOUR_MS) is ResyncLimiter.Decision.HourlyCapReached)
    }

    @Test
    fun aFailedResyncMayBeRetriedForThatContactButStillCounts() {
        val l = ResyncLimiter(maxPerHour = 2)
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("111111111", t0, t0))
        l.forgetContact("111111111")
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("111111111", t0, t0 + 1))
        l.forgetContact("111111111")
        assertTrue(l.tryAcquire("111111111", t0, t0 + 2) is ResyncLimiter.Decision.HourlyCapReached)
    }

    @Test
    fun hintsOlderThanTheRelayKeepsEnvelopesOrFromTheFutureAreIgnored() {
        val l = ResyncLimiter()
        assertSame(ResyncLimiter.Decision.StaleHint, l.tryAcquire("111111111", t0 - 32L * 24 * 3600_000L, t0))
        assertSame(ResyncLimiter.Decision.StaleHint, l.tryAcquire("111111111", t0 + 2L * 24 * 3600_000L, t0))
        // A stale hint used up nothing.
        assertEquals(0, l.usedThisHour(t0))
        assertSame(ResyncLimiter.Decision.Allowed, l.tryAcquire("111111111", t0 - 29L * 24 * 3600_000L, t0))
    }
}
