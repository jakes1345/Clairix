package app.clairix.detect

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.Threat
import app.clairix.TrackerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a tracker is asked who it is: not once at the ten-minute mark and never again,
 * but on qualifying, on separating, every fifteen minutes up to six times, and on request.
 */
class DultScheduleTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun DultSchedule.askAndRefuse(key: String, qualifies: Boolean, separatedAt: Long, now: Long): DultSchedule.Claim? {
        val claim = claim(key, qualifies, separatedAt, now) ?: return null
        finished(key, claim, now, answered = false)
        return claim
    }

    @Test
    fun aDeviceIsAskedOnceItQualifiesAndNotBefore() {
        val s = DultSchedule()
        assertNull(s.claim("tag", qualifies = false, separatedAt = 0L, now = t0))
        val first = s.claim("tag", qualifies = true, separatedAt = 0L, now = t0)
        assertEquals(DultSchedule.Reason.FIRST, first?.reason)
        assertEquals(1, first?.attempt)
    }

    @Test
    fun anExchangeInFlightBlocksAnotherAndIsRecordedOnlyWhenItEnds() {
        val s = DultSchedule()
        val claim = s.claim("tag", qualifies = true, separatedAt = 0L, now = t0)!!
        // Packets keep arriving during the exchange; none starts a second one.
        assertNull(s.claim("tag", qualifies = true, separatedAt = 0L, now = t0 + 1_000L))
        assertNull(s.claimManual("tag"))
        // It began at t0 and ended refused: the next automatic ask is fifteen minutes on.
        assertEquals(t0 + DultSchedule.RETRY_MS, s.finished("tag", claim, t0, answered = false))
        assertNull(s.claim("tag", qualifies = true, separatedAt = 0L, now = t0 + 14 * minute))
        assertEquals(DultSchedule.Reason.RETRY, s.claim("tag", qualifies = true, separatedAt = 0L, now = t0 + 15 * minute)?.reason)
    }

    @Test
    fun aRefusingTrackerIsAskedEveryFifteenMinutesSixTimesInAll() {
        val s = DultSchedule()
        var now = t0
        var asked = 0
        while (now < t0 + 6 * 3600_000L) {
            if (s.askAndRefuse("tag", qualifies = true, separatedAt = 0L, now = now) != null) asked++
            now += minute
        }
        assertEquals(DultSchedule.MAX_AUTOMATIC, asked)
        // Out of automatic attempts: the last refusal says so.
        val claim = s.claimManual("tag")!!
        assertEquals(0L, s.finished("tag", claim, now, answered = false))
    }

    @Test
    fun separatingBringsAnAskAtOnceEvenAfterTheAttemptsAreUsedUp() {
        val s = DultSchedule()
        var now = t0
        repeat(DultSchedule.MAX_AUTOMATIC) {
            assertNotNull(s.askAndRefuse("tag", qualifies = true, separatedAt = 0L, now = now))
            now += DultSchedule.RETRY_MS
        }
        assertNull(s.claim("tag", qualifies = true, separatedAt = 0L, now = now))
        // The tag that was asked while its owner was near has now separated.
        val separatedAt = now - 30_000L
        val claim = s.claim("tag", qualifies = true, separatedAt = separatedAt, now = now)
        assertEquals(DultSchedule.Reason.SEPARATED, claim?.reason)
        s.finished("tag", claim!!, now, answered = false)
        // One flip, one extra ask.
        assertNull(s.claim("tag", qualifies = true, separatedAt = separatedAt, now = now + DultSchedule.RETRY_MS))
    }

    @Test
    fun aSeparationDatedBeforeTheLastAskButLearnedAfterItStillBringsOne() {
        // The separated address was heard first and stitched onto the old entry later,
        // so its separation is dated before the last ask began.
        val s = DultSchedule()
        s.askAndRefuse("tag", qualifies = true, separatedAt = 0L, now = t0)
        val claim = s.claim("tag", qualifies = true, separatedAt = t0 - 5 * minute, now = t0 + 2 * minute)
        assertEquals(DultSchedule.Reason.SEPARATED, claim?.reason)
    }

    @Test
    fun anAnsweredTrackerIsNotAskedAgainOnItsOwn() {
        val s = DultSchedule()
        val claim = s.claim("tag", qualifies = true, separatedAt = 0L, now = t0)!!
        assertEquals(0L, s.finished("tag", claim, t0, answered = true))
        assertNull(s.claim("tag", qualifies = true, separatedAt = t0 + minute, now = t0 + 3600_000L))
        // The owner can still ask.
        assertEquals(DultSchedule.Reason.MANUAL, s.claimManual("tag")?.reason)
    }

    @Test
    fun aManualAskIsAlwaysAllowedAndUsesNoAutomaticAttempt() {
        val s = DultSchedule()
        val manual = s.claimManual("tag")!!
        assertEquals(1, manual.attempt)
        s.finished("tag", manual, t0, answered = false)
        val again = s.claimManual("tag")!!
        assertEquals(2, again.attempt)
        assertEquals(t0 + minute + DultSchedule.RETRY_MS, s.finished("tag", again, t0 + minute, answered = false))
    }

    @Test
    fun anAbandonedClaimCountsForNothing() {
        val s = DultSchedule()
        s.claim("tag", qualifies = true, separatedAt = 0L, now = t0)
        s.abandoned("tag")
        assertEquals(DultSchedule.Reason.FIRST, s.claim("tag", qualifies = true, separatedAt = 0L, now = t0 + 1_000L)?.reason)
    }

    @Test
    fun whatQualifies() {
        val airTag = TrackerType("airtag", "Apple AirTag", "Apple", Threat.CRITICAL, "")
        val near = TrackerType("airtag_near", "Apple AirTag (owner nearby)", "Apple", Threat.MEDIUM, "")
        assertTrue(DultSchedule.qualifies(detection(airTag, persistent = true, following = false)))
        assertFalse(DultSchedule.qualifies(detection(airTag, persistent = false, following = false)))
        // With its owner near it offers no non-owner service; it is asked once following.
        assertFalse(DultSchedule.qualifies(detection(near, persistent = true, following = false)))
        assertTrue(DultSchedule.qualifies(detection(near, persistent = true, following = true)))
        assertTrue(DultSchedule.qualifies(detection(null, persistent = true, following = true)))
    }

    @Test
    fun theLocatorLine() {
        val clock: (Long) -> String = { "12:04" }
        assertNull(DultSchedule.statusLine(null, qualifies = true, clock = clock))
        assertNull(DultSchedule.statusLine(DultResult("A"), qualifies = true, clock = clock))
        assertEquals(
            "Asked 12:04 — asking now",
            DultSchedule.statusLine(DultResult("A", askedAt = t0), qualifies = true, clock = clock)
        )
        val refused = DultResult("A", complete = true, refused = true, askedAt = t0, attempt = 1, retryAt = t0 + DultSchedule.RETRY_MS,
            error = "Tracker refused the connection (status 8) — it is probably still with its owner")
        assertEquals(
            "Asked 12:04 — refused (owner may be near); will try again",
            DultSchedule.statusLine(refused, qualifies = true, clock = clock)
        )
        assertEquals(
            "Asked 12:04 — refused (owner may be near); no more automatic attempts",
            DultSchedule.statusLine(refused.copy(retryAt = 0L), qualifies = true, clock = clock)
        )
        assertEquals(
            "Asked 12:04 — refused (owner may be near); will try again if it stays around",
            DultSchedule.statusLine(refused, qualifies = false, clock = clock)
        )
        // A device whose maker ships no DULT: a refusal says nothing about an owner.
        assertEquals(
            "Asked 12:04 — no answer (it may not support DULT); will try again",
            DultSchedule.statusLine(refused, qualifies = true, capable = false, clock = clock)
        )
        val timedOut = DultResult("A", complete = true, askedAt = t0, retryAt = t0 + 1, error = "No answer within 10 s")
        assertEquals(
            "Asked 12:04 — no answer within 10 s; will try again",
            DultSchedule.statusLine(timedOut, qualifies = true, clock = clock)
        )
        val answered = DultResult("A", manufacturer = "Apple", model = "AirTag", complete = true, askedAt = t0)
        assertEquals("Asked 12:04 — answered: Apple AirTag", DultSchedule.statusLine(answered, qualifies = true, clock = clock))
    }

    @Test
    fun whoseRefusalMeansTheOwnerIsNear() {
        assertTrue(DultSchedule.dultCapable(Signatures.AIRTAG))
        assertTrue(DultSchedule.dultCapable(Signatures.AIRTAG_NEAR_OWNER))
        assertTrue(DultSchedule.dultCapable(Signatures.GOOGLE_FIND_HUB))
        assertFalse(DultSchedule.dultCapable(Signatures.GPS_BLE))
        assertFalse(DultSchedule.dultCapable(Signatures.IBEACON))
        assertFalse(DultSchedule.dultCapable(null))
    }

    private fun detection(type: TrackerType?, persistent: Boolean, following: Boolean) = Detection(
        key = "k", address = "AA:BB:CC:DD:EE:FF", name = "n", rssi = -60, tracker = type,
        threat = Threat.HIGH, score = 50, firstSeen = t0, lastSeen = t0, sightings = 10,
        persistent = persistent, following = following,
        confidence = if (following) FollowConfidence.CONFIRMED else FollowConfidence.NOT_MOVED_ENOUGH,
        displacementM = 0.0, places = 0, rotations = 0, addresses = 1, approxMetres = null
    )
}
