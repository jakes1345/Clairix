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

    // ── Made from the SOS's rows in the group outbox ────────────────────

    private val t0 = 1_700_000_000_000L
    private val family = listOf("a" to "Family", "b" to "Shelter team")

    private fun row(gid: String, number: String, status: GroupOutbox.Status, mesh: Boolean = false, error: String? = null) =
        GroupOutbox.Row(
            "sos-$gid", number, gid, "batch", GroupOutbox.Kind.SOS, "ed-$number", "{}", t0, t0 + GroupOutbox.SOS_LIFETIME_MS,
            status, mesh = mesh, error = error
        )

    @Test
    fun aRetryThatLandsSaysSo() {
        val q = GroupOutbox.Status.QUEUED
        val s = GroupOutbox.Status.SENT
        val first = PanicResult.fromRows(1, t0, 51.5, -0.1, family, listOf(row("a", "1", s), row("a", "2", s), row("a", "3", s), row("b", "4", q, error = offline)))
        assertTrue("a queued row is still being tried", first.retrying)
        assertEquals("SOS reached 3 of 4 people; trying 1 more again", first.headline())
        val after = PanicResult.fromRows(1, t0, 51.5, -0.1, family, listOf(row("a", "1", s), row("a", "2", s), row("a", "3", s), row("b", "4", s)), pass = 2)
        assertFalse(after.retrying)
        assertEquals(2, after.pass)
        assertEquals("SOS reached all 4 people", after.headline())
        assertEquals("Family: 3 of 3 · Shelter team: 1 of 1 · with your position", after.detail())
    }

    @Test
    fun reachedQueuedAndFailedForGoodAreToldApart() {
        val rows = listOf(
            row("a", "1", GroupOutbox.Status.SENT),
            row("a", "2", GroupOutbox.Status.QUEUED, error = offline),
            row("a", "3", GroupOutbox.Status.HELD),
            row("a", "4", GroupOutbox.Status.QUEUED, mesh = true, error = offline),
            row("a", "5", GroupOutbox.Status.FAILED, error = "No such Clairix number on this relay any more")
        )
        val r = PanicResult.fromRows(1, t0, null, null, family.take(1), rows)
        assertEquals(1, r.reached)
        assertEquals("queued and held rows are both still waiting", 2, r.pending)
        assertEquals(1, r.meshQueued)
        assertEquals(1, r.failed)
        assertTrue(r.retrying)
        assertEquals("SOS reached 1 of 5 people; 1 in the offline mesh; trying 2 more again; 1 could not be reached", r.headline())
        // Thirty minutes on, whoever is still queued is not reached.
        val end = PanicResult.fromRows(1, t0, null, null, family.take(1), rows.map { GroupOutbox.expire(it, t0 + GroupOutbox.SOS_LIFETIME_MS) })
        assertFalse(end.retrying)
        assertEquals("SOS reached 1 of 5 people; 1 in the offline mesh; 3 could not be reached", end.headline())
        assertEquals("Family: 1 of 5 (1 in the offline mesh) · no position attached", end.detail())
    }

    @Test
    fun theFirstPassWithTheRelayDownSaysNotSentYet() {
        val r = PanicResult.fromRows(1, t0, 51.5, -0.1, family.take(1), listOf(row("a", "1", GroupOutbox.Status.QUEUED, error = offline)))
        assertEquals("SOS not sent yet — retrying: the relay is not reachable right now", r.headline())
        val line = r.groups.single().logLine(position = true, retrying = r.retrying)
        assertEquals("SOS to \"Family\": not sent yet, 0 of 1 member reached; 1 waiting for the relay", line)
        assertFalse("the log never calls an SOS sent that nobody got", line.contains("sent to"))
    }

    @Test
    fun theGroupLineSaysWhatHappenedToEachMember() {
        val g = PanicGroupResult(
            "a", "Family",
            listOf(m("1", SENT), m("2", SENT), m("3", MESH_QUEUED, offline), m("4", FAILED, "refused")),
            skipped = 1, removed = 1
        )
        assertEquals(
            "SOS to \"Family\": reached 2 of 4 members; 1 in the offline mesh; 1 could not be reached; 1 you blocked left out; 1 no longer in the group (no position)",
            g.logLine(position = false, retrying = false)
        )
        assertEquals(
            "SOS to \"Family\": not sent, 0 of 1 member reached; 1 could not be reached",
            PanicGroupResult("a", "Family", listOf(m("1", FAILED, "refused"))).logLine(position = true, retrying = false)
        )
        assertEquals(
            "SOS to \"Gone\" could not be tried: that group is no longer on this phone",
            PanicGroupResult("x", "Gone", emptyList(), error = "That group is no longer on this phone").logLine(true, false)
        )
    }

    @Test
    fun blockedAndRemovedMembersAreLeftOutNotUnreached() {
        val r = PanicResult.fromRows(
            1, t0, 51.5, -0.1, family.take(1), listOf(row("a", "1", GroupOutbox.Status.SENT)),
            skipped = mapOf("a" to 1), removed = mapOf("a" to 1)
        )
        assertEquals(1, r.people)
        assertEquals("SOS reached 1 person", r.headline())
        assertEquals("Family: 1 of 1 (1 you blocked left out, 1 no longer in the group) · with your position", r.detail())
        // Everyone else blocked: nobody to send to, said as such.
        val none = PanicResult.fromRows(1, t0, null, null, family.take(1), emptyList(), skipped = mapOf("a" to 2))
        assertEquals(PanicBlocked.NO_RECIPIENTS, none.blocked)
        assertEquals("SOS not sent: nobody else in your groups can receive it", none.headline())
        // No other member at all.
        assertEquals(PanicBlocked.NO_OTHER_MEMBERS, PanicResult.fromRows(1, t0, null, null, family.take(1), emptyList()).blocked)
        // A group that could not be tried is said, not taken for an empty one.
        val gone = PanicResult.fromRows(1, t0, null, null, family.take(1), emptyList(), errors = mapOf("a" to "That group is no longer on this phone"))
        assertEquals(null, gone.blocked)
        assertEquals("SOS could not be sent: that group is no longer on this phone", gone.headline())
    }
}
