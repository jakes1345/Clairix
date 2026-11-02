package app.clairix.comms

import app.clairix.comms.SosTokens.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery tokens before an SOS: a send never fetches its own, so a phone
 * back after weeks away (empty pool, the relay's keys for this fortnight not
 * yet seen) would have its first SOS refused for want of a token. The pool is
 * filled to what the SOS needs first, the keys read first when they are not
 * held.
 */
class SosTokensTest {

    @Test
    fun anSosHoldsATokenForEveryMemberAndAFewToSpare() {
        assertEquals("never below the pool's own low mark", TokenKind.DELIVERY.low, SosTokens.target(3))
        assertEquals(110, SosTokens.target(100))
    }

    @Test
    fun aPoolThatHoldsEnoughIsLeftAlone() {
        assertEquals(Step.ENOUGH, SosTokens.step(held = 40, envelopes = 3, keysCurrent = true))
        assertEquals(Step.ENOUGH, SosTokens.step(held = 300, envelopes = 199, keysCurrent = false))
    }

    @Test
    fun anEmptyPoolIsFilledBeforeTheFirstSend() {
        assertEquals(Step.FETCH, SosTokens.step(held = 0, envelopes = 3, keysCurrent = true))
        // A pool above the ordinary low mark is still short for a large group.
        assertEquals(Step.FETCH, SosTokens.step(held = 45, envelopes = 60, keysCurrent = true))
    }

    @Test
    fun afterWeeksAwayTheRelaysKeysAreReadFirst() {
        assertEquals(Step.READ_KEYS_FIRST, SosTokens.step(held = 0, envelopes = 3, keysCurrent = false))
    }

    @Test
    fun theFetchesBeforeOnePassCoverTheLargestGroupFromAnEmptyPool() {
        // A blind fetch brings a delivery batch at most (RelayClient.BLIND_TOKENS_BATCH is the same 50).
        assertTrue(SosTokens.MAX_FETCHES * TokenKind.DELIVERY.batch >= SosTokens.target(199))
    }
}
