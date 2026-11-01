package app.clairix.comms

import app.clairix.comms.LinkTiming.Check
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the live relay socket is judged now that the app sends no pings on a
 * timer: by the relay's heartbeat and the interval it announces, a probe that
 * waits only for the answer, and a watchdog that leaves a socket heard from
 * recently alone.
 */
class LinkTimingTest {

    private val min = 60_000L

    @Test
    fun theAppAsksForFiveMinutesAndBelievesWhatTheRelayAnnouncesWithinBounds() {
        assertEquals(5 * min, LinkTiming.HEARTBEAT_MS)
        assertEquals(2 * min, LinkTiming.announcedGap(2 * min))
        assertEquals(5 * min, LinkTiming.announcedGap(5 * min))
        // A relay that announces nothing is one from before: a heartbeat every two minutes.
        assertEquals(LinkTiming.LEGACY_HEARTBEAT_MS, LinkTiming.announcedGap(null))
        assertEquals(2 * min, LinkTiming.LEGACY_HEARTBEAT_MS)
        // Nonsense is held to what a relay may send.
        assertEquals(LinkTiming.MAX_GAP_MS, LinkTiming.announcedGap(24 * 60 * min))
        assertEquals(LinkTiming.MIN_GAP_MS, LinkTiming.announcedGap(1L))
    }

    @Test
    fun aSocketHeardFromWithinItsHeartbeatWindowIsLeftAloneAndTheWatchdogSleepsAtOnce() {
        val quiet = 5 * min
        assertEquals(Check.FRESH, LinkTiming.check(0L, quiet))
        assertEquals(Check.FRESH, LinkTiming.check(5 * min, quiet))
        // The window allows a minute for a late relay alarm and the network.
        assertEquals(Check.FRESH, LinkTiming.check(6 * min, quiet))
        assertEquals(6 * min, LinkTiming.heardWithinMs(quiet))
    }

    @Test
    fun aHeartbeatOverdueIsProbedAndTwoMissedReplaceTheSocket() {
        val quiet = 5 * min
        assertEquals(Check.PROBE, LinkTiming.check(6 * min + 1, quiet))
        assertEquals(Check.PROBE, LinkTiming.check(11 * min, quiet))
        assertEquals(Check.REPLACE, LinkTiming.check(11 * min + 1, quiet))
        assertEquals(11 * min, LinkTiming.silentLimitMs(quiet))
        // While envelopes move the relay says two minutes, and the windows narrow with it.
        assertEquals(Check.FRESH, LinkTiming.check(3 * min, 2 * min))
        assertEquals(Check.PROBE, LinkTiming.check(3 * min + 1, 2 * min))
        assertEquals(Check.REPLACE, LinkTiming.check(5 * min + 1, 2 * min))
    }

    @Test
    fun withARelayFromBeforeTheRulesComeToWhatTheyWere() {
        val legacy = LinkTiming.announcedGap(null)
        // The old rule: a socket silent for more than five minutes is replaced.
        assertEquals(5 * min, LinkTiming.silentLimitMs(legacy))
        assertEquals(Check.REPLACE, LinkTiming.check(5 * min + 1, legacy))
        assertEquals(Check.PROBE, LinkTiming.check(4 * min, legacy))
        assertEquals(Check.FRESH, LinkTiming.check(2 * min, legacy))
    }

    @Test
    fun theAppPingsAfterAMinutesSilenceInUseAndOtherwiseOnlyWhenAHeartbeatIsOverdue() {
        assertEquals(min, LinkTiming.quietLimitMs(inUse = true, gapMs = 5 * min))
        assertEquals(6 * min, LinkTiming.quietLimitMs(inUse = false, gapMs = 5 * min))
        assertEquals(3 * min, LinkTiming.quietLimitMs(inUse = false, gapMs = 2 * min))
        assertTrue(LinkTiming.quietLimitMs(inUse = false, gapMs = LinkTiming.HEARTBEAT_MS) >= 4 * min)
    }

    // ── awaitFrame ──

    @Test
    fun aFrameAtOrAfterTheProbeAnswersAtOnce() = runBlocking {
        val frames = FrameMark()
        frames.mark(1_000L)
        assertNotNull(withTimeoutOrNull(200) { frames.await(1_000L) })
        assertNotNull(withTimeoutOrNull(200) { frames.await(400L) })
    }

    @Test
    fun aFrameOlderThanTheProbeIsNoAnswer() = runBlocking {
        val frames = FrameMark()
        frames.mark(1_000L)
        assertNull(withTimeoutOrNull(200) { frames.await(1_001L) })
        assertNull(withTimeoutOrNull(200) { FrameMark().await(1L) })
    }

    @Test
    fun theWaitEndsTheMomentANewEnoughFrameArrivesAndNotBefore() = runBlocking {
        val frames = FrameMark()
        frames.mark(1_000L)
        val waiter = async { withTimeoutOrNull(5_000) { frames.await(2_000L) } }
        delay(50)
        assertFalse(waiter.isCompleted)
        // A frame from before the probe was sent (in flight at the time) is not its answer.
        frames.mark(1_500L)
        delay(50)
        assertFalse(waiter.isCompleted)
        // The answer, or a new socket opening, ends it.
        frames.mark(2_000L)
        assertNotNull(waiter.await())
    }

    @Test
    fun framesOnlyMoveForward() {
        val frames = FrameMark()
        assertEquals(0L, frames.at)
        frames.mark(5_000L)
        frames.mark(3_000L)
        assertEquals(5_000L, frames.at)
    }
}
