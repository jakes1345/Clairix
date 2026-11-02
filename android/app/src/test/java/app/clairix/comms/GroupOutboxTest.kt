package app.clairix.comms

import app.clairix.comms.GroupOutbox.Kind
import app.clairix.comms.GroupOutbox.Outcome
import app.clairix.comms.GroupOutbox.Plan
import app.clairix.comms.GroupOutbox.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group outbox: one row per member, tried again with the outbox's
 * back-off and the relay's Retry-After, an SOS given up after thirty minutes,
 * roster changes kept in order per member, and no row at all for this phone
 * or for anyone the owner blocked.
 */
class GroupOutboxTest {

    private val t0 = 1_700_000_000_000L
    private val me = "100000001"
    private val offline = "The relay is not reachable right now"

    private fun row(
        recipient: String,
        kind: Kind = Kind.SOS,
        gid: String = "g1",
        id: String = "p-${kind.wire}-$gid",
        created: Long = t0,
        deadline: Long = created + GroupOutbox.SOS_LIFETIME_MS,
        status: Status = Status.QUEUED,
        triedAt: Long = 0L,
        seq: Long = 0L
    ) = GroupOutbox.Row(
        id, recipient, gid, batch = "b-$id", kind = kind, recipientEd = "ed-$recipient", payload = "{}",
        created = created, deadline = deadline, status = status, triedAt = triedAt, seq = seq
    )

    private fun member(number: String, ed: String = "ed-$number", rv: Long = 0L) =
        GroupMember("g1", number, "Member $number", OrgRole.MEMBER, ed, me, t0, rv)

    private fun contact(number: String) =
        Contact(number, "Contact $number", "ed-$number", "curve-$number", "seal-$number", "sig-$number", verified = false, addedTs = t0)

    private fun blocked(number: String, ed: String) = ContactGate.Blocked(7L, ed, "curve-$number", number, null, "Blocked", t0)

    // ── Who gets a row ──────────────────────────────────────────────────

    @Test
    fun everyOtherMemberGetsARowOfTheirOwn() {
        val roster = listOf(member(me), member("200000002"), member("300000003"), member("400000004"))
        val plans = roster.map { GroupOutbox.plan(it, me, contact(it.number), knownAs = true, blocked = emptyList(), confirmed = true) }
        assertEquals(Plan.Skip, plans[0])
        assertEquals(List(3) { Plan.Queue(held = false) }, plans.drop(1))
    }

    @Test
    fun aMemberTheOwnerBlockedGetsNoRowAndIsCountedNowhere() {
        val list = listOf(blocked("200000002", "ed-abuser"))
        // By the key the roster records for them.
        assertEquals(Plan.Skip, GroupOutbox.plan(member("200000002", "ed-abuser"), me, null, false, list, true))
        // By the same key under another number (they moved).
        assertEquals(Plan.Skip, GroupOutbox.plan(member("900000009", "ed-abuser"), me, contact("900000009"), true, list, true))
        // By number, when the roster records no key for them.
        assertEquals(Plan.Skip, GroupOutbox.plan(member("200000002", ""), me, null, false, list, true))
        // Someone else with that number, whom the roster knows by another key, is not them.
        assertEquals(Plan.Queue(held = false), GroupOutbox.plan(member("200000002", "ed-someone-else"), me, contact("200000002"), true, list, true))
        assertTrue(GroupOutbox.blockedRecipient("200000002", "ed-abuser", list))
        assertFalse(GroupOutbox.blockedRecipient("200000002", "ed-someone-else", list))
    }

    @Test
    fun aMemberThisPhoneCannotReachGetsARowThatHasAlreadyFailedAndSaysWhy() {
        assertEquals(Plan.Fail(GroupOutbox.NOT_A_CONTACT), GroupOutbox.plan(member("200000002"), me, null, false, emptyList(), true))
        assertEquals(Plan.Fail(GroupOutbox.NOT_THE_MEMBER), GroupOutbox.plan(member("200000002"), me, contact("200000002"), false, emptyList(), true))
    }

    @Test
    fun aMemberNotConfirmedInANewerRosterIsHeld() {
        assertEquals(Plan.Queue(held = true), GroupOutbox.plan(member("200000002"), me, contact("200000002"), true, emptyList(), confirmed = false))
    }

    // ── A row's life ────────────────────────────────────────────────────

    @Test
    fun aRowTheRelayTookIsFinal() {
        val sent = GroupOutbox.after(row("200000002"), Outcome.Sent, t0 + 1_000)
        assertEquals(Status.SENT, sent.status)
        assertTrue(sent.final)
        assertEquals(1, sent.attempts)
        assertEquals(t0 + 1_000, sent.triedAt)
        assertEquals(MemberDelivery.SENT, GroupOutbox.delivery(sent))
    }

    @Test
    fun aRowTheRelayCouldNotTakeStaysQueuedAndCountsItsTries() {
        val first = GroupOutbox.after(row("200000002"), Outcome.Offline(offline), t0 + 1_000)
        assertEquals(Status.QUEUED, first.status)
        assertFalse(first.final)
        assertEquals(1, first.attempts)
        assertEquals(offline, first.error)
        assertEquals(MemberDelivery.OFFLINE, GroupOutbox.delivery(first))
        val second = GroupOutbox.after(first, Outcome.Offline("The relay is busy; trying again shortly", retryAfterMs = 2_000L), t0 + 6_000)
        assertEquals(2, second.attempts)
        assertEquals(t0 + 6_000, second.triedAt)
        // A copy in the offline mesh: still tried through the relay, counted apart.
        val meshed = GroupOutbox.after(second, Outcome.Offline(offline, meshQueued = true), t0 + 20_000)
        assertTrue(meshed.mesh)
        assertEquals(Status.QUEUED, meshed.status)
        assertEquals(MemberDelivery.MESH_QUEUED, GroupOutbox.delivery(meshed))
        // And when the relay takes it after all, it is sent.
        assertEquals(Status.SENT, GroupOutbox.after(meshed, Outcome.Sent, t0 + 40_000).status)
    }

    @Test
    fun aRefusalIsFinal() {
        val failed = GroupOutbox.after(row("200000002"), Outcome.Failed("No such Clairix number on this relay any more"), t0 + 1_000)
        assertEquals(Status.FAILED, failed.status)
        assertTrue(failed.final)
        assertEquals("No such Clairix number on this relay any more", failed.error)
        assertEquals(MemberDelivery.FAILED, GroupOutbox.delivery(failed))
    }

    @Test
    fun anSosIsTriedForThirtyMinutesAndThenGivenUp() {
        val queued = GroupOutbox.after(row("200000002"), Outcome.Offline(offline), t0 + 1_000)
        assertEquals(t0 + 30 * 60_000L, queued.deadline)
        assertSame(queued, GroupOutbox.expire(queued, t0 + 29 * 60_000L))
        val expired = GroupOutbox.expire(queued, t0 + 30 * 60_000L)
        assertEquals(Status.FAILED, expired.status)
        assertEquals("$offline; given up after 30 minutes", expired.error)
        // A try that fails at the deadline settles it the same way.
        assertEquals(Status.FAILED, GroupOutbox.after(queued, Outcome.Offline(offline), t0 + 31 * 60_000L).status)
        // A copy in the offline mesh is what is left of it then.
        val meshed = GroupOutbox.expire(queued.copy(mesh = true), t0 + 30 * 60_000L)
        assertEquals(Status.MESH, meshed.status)
        assertEquals(MemberDelivery.MESH_QUEUED, GroupOutbox.delivery(meshed))
        assertTrue(meshed.final)
        // One held for a roster that never came: not confirmed, so not reached.
        val held = GroupOutbox.expire(row("200000002", status = Status.HELD), t0 + 30 * 60_000L)
        assertEquals(Status.FAILED, held.status)
        assertEquals("${GroupOutbox.NOT_CONFIRMED}; given up after 30 minutes", held.error)
        // A final row stays as it was.
        val sent = row("200000002", status = Status.SENT)
        assertSame(sent, GroupOutbox.expire(sent, t0 + 60 * 60_000L))
    }

    @Test
    fun aHeldRowIsNotTriedButCanStillRunOutOfTime() {
        val held = GroupOutbox.after(row("200000002"), Outcome.Hold, t0 + 1_000)
        assertEquals(Status.HELD, held.status)
        assertEquals("a held row has not been tried", 0, held.attempts)
        assertEquals(GroupOutbox.WAITING_FOR_ROSTER, held.error)
        assertEquals(MemberDelivery.OFFLINE, GroupOutbox.delivery(held))
        // An SOS whose every member waits on the roster says why it has not gone.
        val r = PanicResult.fromRows(1, t0, null, null, listOf("g1" to "Family"), listOf(held))
        assertEquals("SOS not sent yet — retrying: ${GroupOutbox.WAITING_FOR_ROSTER}", r.headline())
        // Released, it is queued again and the wait is no longer its reason.
        assertNull(GroupOutbox.release(held, member("200000002"), confirmed = true)!!.error)
    }

    @Test
    fun lifetimesAreSaidInWords() {
        assertEquals("30 minutes", GroupOutbox.lifetimeWords(GroupOutbox.SOS_LIFETIME_MS))
        assertEquals("7 days", GroupOutbox.lifetimeWords(GroupOutbox.TEXT_LIFETIME_MS))
        assertEquals("30 days", GroupOutbox.lifetimeWords(GroupOutbox.ROSTER_LIFETIME_MS))
        assertEquals("24 hours", GroupOutbox.lifetimeWords(24 * 3600_000L))
        assertEquals("1 minute", GroupOutbox.lifetimeWords(10_000L))
    }

    // ── Which rows a pass tries, and in what order ──────────────────────

    @Test
    fun sosRowsGoFirstAndTheLeastRecentlyTriedLeads() {
        val text = row("200000002", kind = Kind.TEXT, deadline = t0 + GroupOutbox.TEXT_LIFETIME_MS)
        val sosTriedLately = row("300000003", triedAt = t0 + 5_000)
        val sosNeverTried = row("400000004")
        val due = GroupOutbox.due(listOf(text, sosTriedLately, sosNeverTried), t0 + 10_000)
        assertEquals(listOf("400000004", "300000003", "200000002"), due.map { it.recipient })
    }

    @Test
    fun aRosterChangeNeverOvertakesAnOlderOneToTheSameMember() {
        val life = t0 + GroupOutbox.ROSTER_LIFETIME_MS
        val remove = row("200000002", kind = Kind.ROSTER, id = "remove", deadline = life, seq = 1)
        val roster = row("200000002", kind = Kind.ROSTER, id = "roster", deadline = life, seq = 2)
        val otherGroup = row("200000002", kind = Kind.ROSTER, gid = "g2", id = "join-g2", deadline = life, seq = 3)
        // Tried in the order they were made, even after the first was tried and the second not.
        val due = GroupOutbox.due(listOf(roster, remove.copy(triedAt = t0 + 1_000), otherGroup), t0 + 2_000)
        assertEquals(listOf("join-g2", "remove"), due.map { it.id })
        // Once the first is final, the next goes.
        val after = GroupOutbox.due(listOf(roster, remove.copy(status = Status.SENT), otherGroup), t0 + 2_000)
        assertEquals(setOf("join-g2", "roster"), after.map { it.id }.toSet())
    }

    @Test
    fun heldFinalAndExpiredRowsAreNotTried() {
        val rows = listOf(
            row("200000002", status = Status.HELD),
            row("300000003", status = Status.SENT),
            row("400000004", status = Status.FAILED),
            row("500000005", status = Status.MESH),
            row("600000006", deadline = t0 + 1_000)
        )
        assertTrue(GroupOutbox.due(rows, t0 + 2_000).isEmpty())
    }

    // ── When the outbox is tried again ──────────────────────────────────

    private val max = 5 * 60_000L

    @Test
    fun theBackOffDoublesUpToFiveMinutes() {
        var backoff = 5_000L
        val waits = ArrayList<Long>()
        repeat(8) {
            val next = OutboxRetry.next(t0, backoff, null, null, max)!!
            waits += next.waitMs
            assertEquals(t0 + next.waitMs, next.atMs)
            backoff = next.backoffMs
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), waits)
    }

    @Test
    fun aRetryAfterIsWaitedForExactlyAndDoesNotGrowTheBackOff() {
        val next = OutboxRetry.next(t0, 40_000L, 2_000L, null, max)!!
        assertEquals(2_000L, next.waitMs)
        assertEquals(40_000L, next.backoffMs)
        // At least a second, at most the cap.
        assertEquals(1_000L, OutboxRetry.next(t0, 5_000L, 0L, null, max)!!.waitMs)
        assertEquals(max, OutboxRetry.next(t0, 5_000L, 3_600_000L, null, max)!!.waitMs)
    }

    @Test
    fun aRetryDueSoonerStandsAndOneDueLaterGivesWay() {
        // Pending in three seconds: a five-second back-off does not replace it.
        assertNull(OutboxRetry.next(t0, 5_000L, null, t0 + 3_000L, max))
        // Pending in a minute: the relay's "again in 2 s" takes its place.
        val next = OutboxRetry.next(t0, 160_000L, 2_000L, t0 + 60_000L, max)!!
        assertEquals(t0 + 2_000L, next.atMs)
    }

    // ── Held rows once the roster changed ───────────────────────────────

    @Test
    fun aHeldRowGoesOnceConfirmedAndIsDroppedForSomeoneTheRosterNoLongerLists() {
        val held = row("200000002", status = Status.HELD)
        assertEquals(Status.QUEUED, GroupOutbox.release(held, member("200000002"), confirmed = true)!!.status)
        assertSame(held, GroupOutbox.release(held, member("200000002"), confirmed = false))
        assertNull("no longer in the group", GroupOutbox.release(held, null, confirmed = false))
        assertNull("the number is someone else's now", GroupOutbox.release(held, member("200000002", "ed-another"), confirmed = true))
        val queued = row("200000002")
        assertSame(queued, GroupOutbox.release(queued, null, confirmed = false))
    }

    // ── A group text's bubble ───────────────────────────────────────────

    @Test
    fun aGroupTextSaysHowFarItGot() {
        val d = t0 + GroupOutbox.TEXT_LIFETIME_MS
        fun r(n: String, s: Status, why: String? = null) = row(n, kind = Kind.TEXT, deadline = d, status = s).copy(error = why)
        assertEquals(GroupOutbox.TextStatus("queued", null), GroupOutbox.textStatus(listOf(r("2", Status.QUEUED), r("3", Status.QUEUED))))
        assertEquals(
            GroupOutbox.TextStatus("queued", "Reached 1 of 2 members; trying 1 more"),
            GroupOutbox.textStatus(listOf(r("2", Status.SENT), r("3", Status.QUEUED)))
        )
        assertEquals(GroupOutbox.TextStatus("sent", null), GroupOutbox.textStatus(listOf(r("2", Status.SENT), r("3", Status.SENT))))
        assertEquals(
            GroupOutbox.TextStatus("sent", "Reached 3 of 4 members; 1 could not be reached"),
            GroupOutbox.textStatus(listOf(r("2", Status.SENT), r("3", Status.SENT), r("4", Status.SENT), r("5", Status.FAILED)))
        )
        assertEquals(
            GroupOutbox.TextStatus("failed", "No member could be reached"),
            GroupOutbox.textStatus(listOf(r("2", Status.FAILED), r("3", Status.FAILED)))
        )
        assertEquals(
            GroupOutbox.TextStatus("failed", "Not sent: ${GroupOutbox.NOT_A_CONTACT}"),
            GroupOutbox.textStatus(listOf(r("2", Status.FAILED, GroupOutbox.NOT_A_CONTACT)))
        )
        assertEquals(GroupOutbox.TextStatus("sent", null), GroupOutbox.textStatus(emptyList()))
    }

    // ── A covert SOS's rows ─────────────────────────────────────────────

    @Test
    fun memoryRowsKeepTheirOrderAndForgetWhatIsRemoved() {
        val rows = GroupOutbox.MemoryRows()
        val a = row("200000002")
        val b = row("300000003")
        rows.add(listOf(a, b))
        rows.add(listOf(a.copy(status = Status.SENT)))
        assertEquals("a second copy of a row is not added", listOf("200000002", "300000003"), rows.all().map { it.recipient })
        assertEquals(listOf(1L, 2L), rows.all().map { it.seq })
        assertTrue(rows.has(a))
        rows.update(rows.all().first().copy(status = Status.SENT))
        assertEquals(Status.SENT, rows.all().first().status)
        rows.remove(b)
        assertFalse(rows.has(b))
        rows.update(b.copy(status = Status.SENT))
        assertFalse("a row that is gone stays gone", rows.has(b))
        rows.removeIf { it.gid == "g1" }
        assertTrue(rows.all().isEmpty())
    }
}
