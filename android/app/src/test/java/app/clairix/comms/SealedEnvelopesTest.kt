package app.clairix.comms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A send whose answer was lost is tried again with the envelope already
 * sealed for it, so the recipient drops the repeat unopened and no second
 * ratchet step is taken.
 */
class SealedEnvelopesTest {

    private var now = 1_000_000L
    private val sealed = SealedEnvelopes(ttlMs = 15 * 60_000L) { now }
    private val envelope = byteArrayOf(1, 2, 3)

    @Test
    fun aKeptEnvelopeIsHandedBackForItsRetry() {
        sealed.keep("m1", "100", envelope)
        now += 5 * 60_000L
        assertArrayEquals(envelope, sealed.take("m1", "100"))
        // Still there for a further retry until the send settles.
        assertArrayEquals(envelope, sealed.take("m1", "100"))
    }

    @Test
    fun aSettledSendForgetsItsEnvelope() {
        sealed.keep("m1", "100", envelope)
        sealed.forget("m1")
        assertNull(sealed.take("m1", "100"))
    }

    @Test
    fun anEnvelopePastItsTimeIsSealedAfresh() {
        sealed.keep("m1", "100", envelope)
        now += 15 * 60_000L + 1
        assertNull("the recipient's sealing prekey may have moved on", sealed.take("m1", "100"))
    }

    @Test
    fun anEnvelopeForAnotherNumberIsNotSent() {
        // The contact moved to a new number since: sealed again for them there.
        sealed.keep("m1", "100", envelope)
        assertNull(sealed.take("m1", "200"))
        assertNull("and it is gone", sealed.take("m1", "100"))
    }

    @Test
    fun stalePiecesAreDroppedWhenAnotherIsKept() {
        sealed.keep("old", "100", envelope)
        now += 16 * 60_000L
        sealed.keep("new", "100", envelope)
        now -= 16 * 60_000L
        // Swept by the keep above, even though the clock is read back here.
        assertNull(sealed.take("old", "100"))
    }

    @Test
    fun clearDropsEverything() {
        sealed.keep("a", "100", envelope)
        sealed.keep("b", "100", envelope)
        sealed.clear()
        assertNull(sealed.take("a", "100"))
        assertNull(sealed.take("b", "100"))
    }
}
