package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An empty token pool never turns into a fetch-flush loop. */
class TokenRefillTest {

    /**
     * A fake relay and outbox: [send] fails with "no tokens" while the pool is
     * empty, as RelayClient.send does, and reports it to the refill exactly as
     * CommsRepository.transmit does. Launched work is queued and drained by
     * [runFor], standing in for the coroutine scope.
     */
    private class World(var fetchWorks: Boolean, val tokensPerFetch: Int = 5) {
        var pool = 0
        var fetches = 0
        var flushes = 0
        var queuedEnvelopes = 3
        val queue = ArrayDeque<() -> Unit>()
        val refill = TokenRefill(
            topUp = {
                fetches++
                if (!fetchWorks) throw RelayException("relay unreachable", 0)
                pool += tokensPerFetch
                pool
            },
            retry = { queue.addLast { flush() } },
            launch = { block -> queue.addLast(block) },
        )

        fun send(): Boolean {
            if (pool == 0) {
                refill.onNoTokens()
                return false
            }
            pool--
            return true
        }

        /** Flushes the outbox (the receipts and call signalling do the same). */
        fun flush() {
            flushes++
            while (queuedEnvelopes > 0 && send()) queuedEnvelopes--
        }

        /** Runs launched work for at most [steps] tasks; returns how many ran. */
        fun runFor(steps: Int): Int {
            var n = 0
            while (n < steps) {
                val next = queue.removeFirstOrNull() ?: break
                next()
                n++
            }
            return n
        }
    }

    @Test
    fun aFailingTokenFetchIsTriedOnceAndNothingIsRetriedHere() {
        val w = World(fetchWorks = false)
        // Outbox, receipts and a call signal all hit the empty pool at once.
        w.flush()
        w.flush()
        w.flush()
        val ran = w.runFor(10_000)
        assertTrue("work kept relaunching itself ($ran tasks)", ran <= 3)
        assertEquals(1, w.fetches)
        assertEquals("only the three original flushes", 3, w.flushes)
        assertTrue(w.queue.isEmpty())
        assertEquals(3, w.queuedEnvelopes)
    }

    @Test
    fun repeatedFailuresStayBoundedAcrossManyBackoffRounds() {
        val w = World(fetchWorks = false)
        // Ten backoff rounds, each one flush; the queue is drained after each.
        repeat(10) {
            w.flush()
            w.runFor(10_000)
        }
        assertEquals(10, w.fetches)
        assertEquals(10, w.flushes)
        assertTrue(w.queue.isEmpty())
    }

    @Test
    fun onlyOneRefillRunsAtATime() {
        val w = World(fetchWorks = false)
        assertTrue(w.refill.onNoTokens())
        repeat(50) { assertFalse(w.refill.onNoTokens()) }
        w.runFor(10_000)
        assertEquals(1, w.fetches)
        // Once it finished, the next empty-pool send may start another.
        assertTrue(w.refill.onNoTokens())
    }

    @Test
    fun aSuccessfulFetchRetriesOnceAndTheEnvelopesGo() {
        val w = World(fetchWorks = true)
        w.flush()
        w.runFor(10_000)
        assertEquals(0, w.queuedEnvelopes)
        assertEquals(1, w.fetches)
        assertEquals(2, w.flushes)
        assertTrue(w.queue.isEmpty())
    }

    @Test
    fun aFetchThatLeavesThePoolEmptyRetriesNothing() {
        val w = World(fetchWorks = true, tokensPerFetch = 0)
        w.flush()
        val ran = w.runFor(10_000)
        assertEquals(1, ran)
        assertEquals(1, w.fetches)
        assertEquals(1, w.flushes)
    }
}
