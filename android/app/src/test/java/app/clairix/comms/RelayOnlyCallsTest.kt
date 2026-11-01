package app.clairix.comms

import app.clairix.comms.RelayOnlyCalls.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun route(
        choice: Boolean? = null,
        offeredNow: Boolean,
        known: Boolean? = null,
        tor: Boolean = false,
        answering: Boolean = false,
        confirmed: Boolean = false,
    ) = RelayOnlyCalls.route(choice, offeredNow, known, tor, answering, confirmed)

    @Test
    fun aRelayThatOffersTurnCarriesTheCallUnlessTheOwnerChoseDirect() {
        assertEquals(Route.RELAY_ONLY, route(offeredNow = true))
        assertEquals(Route.RELAY_ONLY, route(choice = true, offeredNow = true))
        assertEquals(Route.DIRECT, route(choice = false, offeredNow = true))
    }

    @Test
    fun aPassingTurnFailureNeverQuietlyBecomesADirectCall() {
        // The relay offered TURN before; this time its call server failed (a timeout, a mint error, the hourly cap).
        assertEquals(Route.ASK, route(offeredNow = false, known = true))
        assertEquals(Route.REFUSE_TURN_UNAVAILABLE, route(offeredNow = false, known = true, answering = true))
        // Only the owner's yes, for this call, sends it direct.
        assertEquals(Route.DIRECT, route(offeredNow = false, known = true, confirmed = true))
    }

    @Test
    fun withNoTurnAndNoChoiceACallThisPhonePlacesAsksFirst() {
        assertEquals(Route.ASK, route(offeredNow = false, known = false))
        assertEquals(Route.ASK, route(offeredNow = false, known = null))
        assertEquals(Route.DIRECT, route(offeredNow = false, known = false, confirmed = true))
        // A call being answered on a relay that has never offered TURN connects as it always has.
        assertEquals(Route.DIRECT, route(offeredNow = false, known = false, answering = true))
        // An owner who chose either way is never asked.
        assertEquals(Route.REFUSE_NO_TURN, route(choice = true, offeredNow = false, known = false))
        assertEquals(Route.DIRECT, route(choice = false, offeredNow = false, known = false))
    }

    @Test
    fun withTorOnACallNeverGoesDirect() {
        // TURN on offer: it carries the call even for an owner who chose direct calls.
        assertEquals(Route.RELAY_ONLY, route(choice = false, offeredNow = true, tor = true))
        // No TURN: refused, whatever the choice, the confirmation or the direction.
        for (choice in listOf(null, true, false)) {
            assertEquals(Route.REFUSE_TOR, route(choice = choice, offeredNow = false, tor = true))
            assertEquals(Route.REFUSE_TOR, route(choice = choice, offeredNow = false, tor = true, confirmed = true))
            assertEquals(Route.REFUSE_TOR, route(choice = choice, offeredNow = false, tor = true, answering = true))
        }
    }

    @Test
    fun everyRefusalSaysWhyAndARouteThatConnectsSaysNothing() {
        for (r in Route.values()) {
            if (r == Route.RELAY_ONLY || r == Route.DIRECT) assertNull(RelayOnlyCalls.refusal(r)) else assertNotNull(RelayOnlyCalls.refusal(r))
        }
        assertTrue(RelayOnlyCalls.refusal(Route.REFUSE_TOR)!!.contains("Tor"))
    }
}
