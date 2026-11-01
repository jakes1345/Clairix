package app.clairix.comms

import app.clairix.comms.GroupOutbox.Plan
import app.clairix.comms.GroupOutbox.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roster numbers: a member who missed a roster change finds out from the
 * number on the next payload, holds what it would have sent to members it
 * cannot confirm, and once an admin's roster comes, sends to whoever it lists
 * and never to the person removed.
 */
class RosterSyncTest {

    @Test
    fun anAdminNumbersEachChangePastEverythingItHasHeardOf() {
        assertEquals(1L, RosterSync.next(0L, 0L))
        assertEquals(4L, RosterSync.next(3L, 3L))
        // Another admin's change it has heard of but not yet received: past that too.
        assertEquals(8L, RosterSync.next(3L, 7L))
    }

    @Test
    fun theNextChangeInLineMovesTheRosterOnAndAGapMarksItOutOfDate() {
        assertEquals(4L to 4L, RosterSync.afterDelta(3L, 3L, 4L))
        assertFalse(RosterSync.stale(4L, 4L))
        // Change 5 arrives, 4 never did: applied, but the roster is out of date.
        val (v, s) = RosterSync.afterDelta(3L, 3L, 5L)
        assertEquals(3L to 5L, v to s)
        assertTrue(RosterSync.stale(v, s))
        // A late copy of a change already held changes nothing.
        assertEquals(5L to 5L, RosterSync.afterDelta(5L, 5L, 4L))
    }

    @Test
    fun aWholeRosterReplacesOnlyAnOlderOneAndEndsTheStaleness() {
        assertTrue(RosterSync.replaces(3L, 4L))
        assertFalse(RosterSync.replaces(4L, 4L))
        assertFalse(RosterSync.replaces(5L, 4L))
        assertEquals(4L to 4L, RosterSync.afterRoster(3L, 4L, 4L))
        // A member claimed a number no admin has: the admin's roster is what the group is.
        assertEquals(4L to 4L, RosterSync.afterRoster(4L, 99L, 4L))
        // A late copy of an older roster changes nothing, staleness included.
        assertEquals(5L to 6L, RosterSync.afterRoster(5L, 6L, 4L))
    }

    @Test
    fun whileOutOfDateOnlyMembersConfirmedInTheNewerRosterAreSentTo() {
        assertTrue("not out of date: everyone", RosterSync.confirmed(0L, 3L, 3L))
        assertTrue(RosterSync.confirmed(4L, 3L, 4L))
        assertFalse(RosterSync.confirmed(3L, 3L, 4L))
        assertEquals(3L to 4L, RosterSync.heard(3L, 3L, 4L))
        assertEquals("an older number heard changes nothing", 3L to 4L, RosterSync.heard(3L, 4L, 2L))
    }

    @Test
    fun aGroupNoAdminHasNumberedIsNeverOutOfDate() {
        val (v, s) = RosterSync.heard(0L, 0L, 0L)
        assertFalse(RosterSync.stale(v, s))
        assertTrue(RosterSync.confirmed(0L, v, s))
    }

    /**
     * The audit's case: B holds roster 3 (admin A, B, C and X). The admin
     * removes X as roster 4; B misses both the removal and the roster. C's
     * next message carries number 4. B must not send its SOS to X.
     */
    @Test
    fun aMemberWhoMissedARemovalDoesNotSendTheSosToTheRemovedMember() {
        val b = "200000002"
        var version = 3L
        var seen = 3L
        val members = linkedMapOf(
            "100000001" to GroupMember("g", "100000001", "A", OrgRole.ADMIN, "ed-a", "100000001", 0L, rv = 3L),
            b to GroupMember("g", b, "B", OrgRole.MEMBER, "ed-b", "100000001", 0L, rv = 3L),
            "300000003" to GroupMember("g", "300000003", "C", OrgRole.MEMBER, "ed-c", "100000001", 0L, rv = 3L),
            "400000004" to GroupMember("g", "400000004", "X", OrgRole.MEMBER, "ed-x", "100000001", 0L, rv = 3L)
        )
        fun contact(m: GroupMember) = Contact(m.number, m.name, m.ed25519, "curve-${m.number}", "seal", "sig", verified = true, addedTs = 0L)

        // C's group message, numbered 4.
        RosterSync.heard(version, seen, 4L).let { (v, s) -> version = v; seen = s }
        members["300000003"] = members.getValue("300000003").copy(rv = 4L)
        assertTrue("B now knows its roster is out of date", RosterSync.stale(version, seen))

        // B raises an SOS: one row per member as the outbox plans them.
        val rows = members.values.mapNotNull { m ->
            when (val p = GroupOutbox.plan(m, b, contact(m), knownAs = true, blocked = emptyList(), confirmed = RosterSync.confirmed(m.rv, version, seen))) {
                Plan.Skip -> null
                is Plan.Fail -> error("nobody here is unreachable: ${p.reason}")
                is Plan.Queue -> GroupOutbox.Row(
                    "sos", m.number, "g", "batch", GroupOutbox.Kind.SOS, m.ed25519, "{}", 0L, GroupOutbox.SOS_LIFETIME_MS,
                    if (p.held) Status.HELD else Status.QUEUED
                )
            }
        }
        assertEquals(mapOf("100000001" to Status.HELD, "300000003" to Status.QUEUED, "400000004" to Status.HELD), rows.associate { it.recipient to it.status })
        // Nothing for X goes out while the roster is out of date.
        assertEquals(listOf("300000003"), GroupOutbox.due(rows, 1_000L).map { it.recipient })

        // The admin's roster 4 arrives: A, B and C.
        assertTrue(RosterSync.replaces(version, 4L))
        RosterSync.afterRoster(version, seen, 4L).let { (v, s) -> version = v; seen = s }
        members.remove("400000004")
        for (k in members.keys.toList()) members[k] = members.getValue(k).copy(rv = 4L)
        assertFalse(RosterSync.stale(version, seen))

        // The held rows: A's goes, X's is dropped.
        val released = rows.mapNotNull { r ->
            val m = members[r.recipient]
            GroupOutbox.release(r, m, m != null && RosterSync.confirmed(m.rv, version, seen))
        }
        assertNull(released.find { it.recipient == "400000004" })
        assertEquals(setOf("100000001", "300000003"), GroupOutbox.due(released, 2_000L).map { it.recipient }.toSet())
        // And the next SOS is planned for the roster as it now is: X is not on it.
        val next = members.values.map { GroupOutbox.plan(it, b, contact(it), true, emptyList(), RosterSync.confirmed(it.rv, version, seen)) }
        assertEquals(listOf(Plan.Queue(false), Plan.Skip, Plan.Queue(false)), next)
    }

    /**
     * The owner made the group and is its only admin. Member M sends a group
     * message numbered far past any roster. With no other admin, no newer roster
     * can exist and nobody can answer the request for one: the owner's SOS must
     * still go to everyone, not to M alone.
     */
    @Test
    fun aMembersNumberNoAdminCanSettleHoldsNobodyBack() {
        val me = "100000001"
        var version = 2L
        var seen = 2L
        val members = linkedMapOf(
            me to GroupMember("g", me, "Me", OrgRole.ADMIN, "ed-me", me, 0L, rv = 2L),
            "200000002" to GroupMember("g", "200000002", "Helper", OrgRole.MEMBER, "ed-h", me, 0L, rv = 2L),
            "300000003" to GroupMember("g", "300000003", "M", OrgRole.MEMBER, "ed-m", me, 0L, rv = 2L)
        )
        RosterSync.heard(version, seen, 1_000_000L).let { (v, s) -> version = v; seen = s }
        members["300000003"] = members.getValue("300000003").copy(rv = 1_000_000L)
        assertTrue(RosterSync.stale(version, seen))

        val askable = RosterSync.askable(members.values.toList(), me)
        assertFalse("this phone is the only admin", askable)
        val helper = members.getValue("200000002")
        assertFalse("the plain rule would hold the helper", RosterSync.confirmed(helper.rv, version, seen))
        assertTrue(RosterSync.confirmed(helper.rv, version, seen, askable))
        val plan = GroupOutbox.plan(helper, me, null, knownAs = false, blocked = emptyList(), confirmed = RosterSync.confirmed(helper.rv, version, seen, askable))
        assertEquals("planned on reachability alone, not held", Plan.Fail(GroupOutbox.NOT_A_CONTACT), plan)

        // A group whose only admin left: nobody to ask either.
        val noAdmin = members.values.map { it.copy(role = OrgRole.MEMBER) }
        assertFalse(RosterSync.askable(noAdmin, "200000002"))
    }

    @Test
    fun withAnotherAdminToAskTheRosterRuleStands() {
        val members = listOf(
            GroupMember("g", "100000001", "A", OrgRole.ADMIN, "ed-a", "100000001", 0L),
            GroupMember("g", "200000002", "B", OrgRole.MEMBER, "ed-b", "100000001", 0L)
        )
        assertTrue(RosterSync.askable(members, "200000002"))
        assertFalse(RosterSync.confirmed(3L, 3L, 4L, askable = true))
        assertTrue(RosterSync.confirmed(4L, 3L, 4L, askable = true))
        // An admin with no other admin is nobody's answer but its own.
        assertFalse(RosterSync.askable(members, "100000001"))
    }
}
