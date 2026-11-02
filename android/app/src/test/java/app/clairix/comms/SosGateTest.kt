package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Test

/** The SOS button asks who would receive an SOS before anyone confirms one. */
class SosGateTest {

    @Test
    fun noClairixNumberComesFirst() {
        assertEquals(SosGate.NOT_REGISTERED, SosGate.decide(registered = false, groups = 3, otherMembers = 10))
        assertEquals(SosGate.NOT_REGISTERED, SosGate.decide(registered = false, groups = 0, otherMembers = 0))
    }

    @Test
    fun registeredWithoutAGroupHasNobodyToSendTo() {
        assertEquals(SosGate.NO_RECIPIENTS, SosGate.decide(registered = true, groups = 0, otherMembers = 0))
    }

    @Test
    fun aGroupWithOnlyThisPhoneInItHasNobodyToSendTo() {
        assertEquals(SosGate.NO_RECIPIENTS, SosGate.decide(registered = true, groups = 2, otherMembers = 0))
    }

    @Test
    fun oneOtherPersonIsEnough() {
        assertEquals(SosGate.SEND, SosGate.decide(registered = true, groups = 1, otherMembers = 1))
        assertEquals(SosGate.SEND, SosGate.decide(registered = true, groups = 4, otherMembers = 37))
    }
}
