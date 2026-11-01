package app.clairix.comms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relay-only calls: on by default wherever the relay offers TURN; the owner's own choice always wins. */
class RelayOnlyCallsTest {

    @Test
    fun withNoChoiceMadeItFollowsWhatTheRelayOffers() {
        // A TURN server: no contact learns this phone's address from a call.
        assertTrue(RelayOnlyCalls.on(choice = null, relayOffersTurn = true))
        // None: a relay-only call could not connect at all, so calls go direct.
        assertFalse(RelayOnlyCalls.on(choice = null, relayOffersTurn = false))
    }

    @Test
    fun anOwnerWhoSwitchedItOnKeepsItOnEvenWithoutTurn() {
        assertTrue(RelayOnlyCalls.on(choice = true, relayOffersTurn = true))
        assertTrue(RelayOnlyCalls.on(choice = true, relayOffersTurn = false))
    }

    @Test
    fun anOwnerWhoSwitchedItOffGetsDirectCallsEvenWithTurn() {
        assertFalse(RelayOnlyCalls.on(choice = false, relayOffersTurn = true))
        assertFalse(RelayOnlyCalls.on(choice = false, relayOffersTurn = false))
    }
}
