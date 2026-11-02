package app.clairix.comms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A format-2 envelope refused by the relay past its cutoff (426) is sealed
 * again in format 3 once the contact's prekey is looked up, so two up-to-date
 * phones whose session predates Beta 7 are not cut off from each other.
 */
class LegacyEnvelopeTest {

    @Test
    fun aRefusedFormat2EnvelopeLooksUpThePrekeyOnce() {
        assertTrue(LegacyEnvelope.lookUpPrekeyAndRetry(426, prekeyHeld = false, alreadyLookedUp = false))
        assertFalse("once per send", LegacyEnvelope.lookUpPrekeyAndRetry(426, prekeyHeld = false, alreadyLookedUp = true))
    }

    @Test
    fun withAPrekeyHeldThe426WasAboutSomethingElse() {
        // The envelope was format 3 already; the relay refused the old kind of token.
        assertFalse(LegacyEnvelope.lookUpPrekeyAndRetry(426, prekeyHeld = true, alreadyLookedUp = false))
    }

    @Test
    fun otherRefusalsAreNotRetriedThisWay() {
        for (status in listOf(0, 400, 401, 403, 404, 413, 429, 500)) {
            assertFalse("$status", LegacyEnvelope.lookUpPrekeyAndRetry(status, prekeyHeld = false, alreadyLookedUp = false))
        }
    }
}
