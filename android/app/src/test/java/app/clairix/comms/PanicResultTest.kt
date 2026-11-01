package app.clairix.comms

import app.clairix.comms.MemberDelivery.FAILED
import app.clairix.comms.MemberDelivery.MESH_QUEUED
import app.clairix.comms.MemberDelivery.OFFLINE
import app.clairix.comms.MemberDelivery.SENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS summary counts people, tells the offline mesh from a failure, and
 * says only what happened: the sentence the button toasts after the sends.
 */
class PanicResultTest {

    private fun m(number: String, d: MemberDelivery, why: String? = null) = MemberResult(number, d, why)
    private fun g(id: String, vararg members: MemberResult) = PanicGroupResult(id, "Group $id", members.toList())
    private fun result(vararg groups: PanicGroupResult, retrying: Boolean = false, lat: Double? = 51.5, lon: Double? = -0.1) =
        PanicResult(attempt = 1, ts = 0L, lat = lat, lon = lon, groups = groups.toList(), retrying = retrying)

    private val offline = "The relay is not reachable right now"

    @Test
    fun countsPeopleNotEnvelopes() {
        // Bea is in both groups: one of her envelopes went, so she is one person reached.
        val r = result(
            g("a", m("bea", SENT), m("cal", OFFLINE, offline)),
            g("b", m("bea", OFFLINE, offline), m("dee", SENT))
        )
        assertEquals(3, r.people)
        assertEquals(2, r.reached)
        assertEquals(1, r.pending)
        assertEquals(0, r.failed)
        assertEquals(0, r.meshQueued)
        assertEquals(2, r.groupsReached)
        // Her second envelope is not worth another pass; Cal's is.
        assertEquals(setOf("cal"), r.pendingIn("a"))
        assertEquals(emptySet<String>(), r.pendingIn("b"))
        assertEquals(emptySet<String>(), r.pendingIn("no such group"))
        assertTrue(r.anyPending)
    }

    @Test
    fun theOfflineMeshIsNotAFailure() {
        val r = result(
            g("a", m("bea", SENT), m("cal", MESH_QUEUED, offline), m("dee", FAILED, "No such Clairix number on this relay any more"))
        )
        assertEquals(1, r.reached)
        assertEquals(1, r.meshQueued)
        assertEquals(1, r.failed)
        assertEquals(0, r.pending)
        assertFalse("a mesh-queued member is not tried again by the relay path", r.anyPending)
        assertEquals("SOS reached 1 of 3 people; 1 in the offline mesh; 1 could not be reached", r.headline())
        assertEquals("Group a: 1 of 3 (1 in the offline mesh) · with your position", r.detail())
    }

    @Test
    fun aGroupThatCouldNotBeTriedDoesNotHideTheOthers() {
        val r = result(
            PanicGroupResult("x", "Gone", emptyList(), error = "That group is no longer on this phone"),
            g("a", m("bea", SENT))
        )
        assertEquals(1, r.people)
        assertEquals(1, r.reached)
        assertEquals("SOS reached 1 person", r.headline())
        assertEquals("Gone: not tried (that group is no longer on this phone) · Group a: 1 of 1 · with your position", r.detail())
    }

    @Test
    fun headlinesSayWhatHappened() {
        assertEquals("SOS reached all 4 people", result(g("a", m("1", SENT), m("2", SENT), m("3", SENT), m("4", SENT))).headline())
        assertEquals(
            "SOS reached 3 of 4 people; trying 1 more again",
            result(g("a", m("1", SENT), m("2", SENT), m("3", SENT), m("4", OFFLINE, offline)), retrying = true).headline()
        )
        assertEquals(
            "SOS reached 3 of 4 people; 1 could not be reached",
            result(g("a", m("1", SENT), m("2", SENT), m("3", SENT), m("4", OFFLINE, offline))).headline()
        )
        assertEquals(
            "SOS not sent yet — retrying: the relay is not reachable right now",
            result(g("a", m("1", OFFLINE, offline)), retrying = true).headline()
        )
        assertEquals(
            "SOS could not be sent: the relay is not reachable right now",
            result(g("a", m("1", OFFLINE, offline))).headline()
        )
        assertEquals(
            "SOS could not reach the relay; the offline mesh carries it for all 2 people",
            result(g("a", m("1", MESH_QUEUED, offline), m("2", MESH_QUEUED, offline))).headline()
        )
        assertEquals(
            "SOS could not reach the relay; the offline mesh carries it for 1 of 2 people; trying 1 more again",
            result(g("a", m("1", MESH_QUEUED, offline), m("2", OFFLINE, offline)), retrying = true).headline()
        )
        assertEquals(
            "Your location reached 1 of 2 people; 1 could not be reached",
            result(g("a", m("1", SENT), m("2", FAILED, "they are not a contact on this phone"))).headline("Your location")
        )
    }

    @Test
    fun nobodyToSendToIsSaidAsNotSent() {
        assertEquals("SOS not sent: this phone is not set up for Comms", PanicResult(1, 0L, blocked = PanicBlocked.NOT_REGISTERED).headline())
        assertEquals("SOS not sent: this phone is in no group", PanicResult(1, 0L, blocked = PanicBlocked.NO_GROUPS).headline())
        assertEquals("SOS not sent: your groups have no other members", PanicResult(1, 0L, blocked = PanicBlocked.NO_OTHER_MEMBERS).headline())
        assertEquals("SOS could not be sent: the vault is sealed", PanicResult(1, 0L, error = "The vault is sealed").headline())
        // No group could be tried: the first group's own words.
        val r = result(PanicGroupResult("x", "Gone", emptyList(), error = "That group is no longer on this phone"))
        assertEquals(0, r.people)
        assertEquals("SOS could not be sent: that group is no longer on this phone", r.headline())
        assertFalse(PanicResult(1, 0L, blocked = PanicBlocked.NO_GROUPS).hasPosition)
    }

    @Test
    fun aRetryPassReplacesOnlyTheMembersItTried() {
        val first = result(
            g("a", m("bea", SENT), m("cal", OFFLINE, offline), m("dee", OFFLINE, offline)),
            retrying = true
        )
        val second = first.merged(listOf(g("a", m("cal", SENT))), pass = 2, retrying = true)
        assertEquals(2, second.pass)
        assertEquals(2, second.reached)
        assertEquals(setOf("dee"), second.pendingIn("a"))
        assertEquals("SOS reached 2 of 3 people; trying 1 more again", second.headline())
        // A pass naming a group this result does not have changes nothing.
        assertEquals(second.groups, second.merged(listOf(g("zz", m("bea", FAILED))), pass = 3, retrying = true).groups)
        // Nothing left to try: final.
        val third = second.merged(listOf(g("a", m("dee", SENT))), pass = 3, retrying = false)
        assertFalse(third.anyPending)
        assertEquals("SOS reached all 3 people", third.headline())
        // A missing position is said in the detail, never left out.
        assertEquals("Group a: 3 of 3 · no position attached", third.copy(lat = null, lon = null).detail())
    }

    @Test
    fun retriesGrowAndStopWithinTenMinutes() {
        val d = PanicResult.RETRY_DELAYS_MS
        assertTrue("the first retry should come within seconds", d.first() <= 5_000L)
        for (i in 1 until d.size) assertTrue("pauses should grow", d[i] > d[i - 1])
        val total = d.sum()
        assertTrue("retries should last several minutes, not seconds: $total", total >= 5 * 60_000L)
        assertTrue("retries should not go on past ten minutes: $total", total <= 10 * 60_000L)
    }
}
