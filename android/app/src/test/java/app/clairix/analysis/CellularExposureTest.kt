package app.clairix.analysis

import app.clairix.Severity
import app.clairix.analysis.CellularExposure.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2G row over time. It used to say "ALLOWED" for thirty days after any 2G tower,
 * whatever the owner did: someone who turned 2G off was told for a month it was on.
 */
class CellularExposureTest {

    private val day = 24 * 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun verdict(last2gAt: Long?, saidOffAt: Long?, now: Long, hasSwitch: Boolean = true, policy: Boolean = false, ap: Boolean? = null) =
        CellularExposure.verdict(hasSwitch, ap, policy, last2gAt, saidOffAt, now)

    @Test
    fun turningTwoGOffIsBelievedAndHistoryStaysHistory() {
        // Day 0: the phone uses a 2G tower. The row says so, red, and asks.
        val seen = t0
        assertEquals(Verdict.ALLOWED, verdict(seen, null, now = t0 + day))
        val red = CellularExposure.row(Verdict.ALLOWED, true, seen to "2G GSM", null, platformWarnings = true)
        assertEquals(Severity.HIGH, red.severity)
        assertEquals(CellularExposure.ANSWER_SAID_OFF, red.answer?.id)
        assertNotNull(red.fixAction)

        // Day 1: the owner turns the switch off and says so. The row believes them at once.
        val said = t0 + day
        assertEquals(Verdict.SAID_OFF, verdict(seen, said, now = said))
        val off = CellularExposure.row(Verdict.SAID_OFF, true, seen to "2G GSM", said, platformWarnings = true)
        assertNull("not a problem any more", off.severity)
        assertNull("nothing left to ask", off.answer)
        assertTrue(off.value.startsWith("Off, you said on"))
        assertTrue("the sighting is kept as history", off.detail!!.contains("last used a 2G GSM tower on ${CellularExposure.day(seen)}"))

        // The radio moving over in the minute after is not the switch coming back on.
        assertEquals(Verdict.SAID_OFF, verdict(said + 60_000L, said, now = said + 90_000L))

        // Weeks later, nothing has changed: still off, no thirty-day red.
        assertEquals(Verdict.SAID_OFF, verdict(seen, said, now = t0 + 29 * day))
    }

    @Test
    fun twoGAfterTheOwnerSaidOffTurnsTheRowRedAgain() {
        val said = t0
        val seenAgain = t0 + 2 * day
        assertEquals(Verdict.ALLOWED, verdict(seenAgain, said, now = seenAgain + 1_000L))
        val row = CellularExposure.row(Verdict.ALLOWED, true, seenAgain to "2G GSM", said, platformWarnings = false)
        assertEquals(Severity.HIGH, row.severity)
        assertTrue(row.detail!!.startsWith("You said on ${CellularExposure.day(said)} that 2G was off"))
        assertEquals("they can say so again", CellularExposure.ANSWER_SAID_OFF, row.answer?.id)
        // A week of quiet after that: unknown again, not "off" on the strength of a statement 2G contradicted.
        assertEquals(Verdict.NOT_READABLE, verdict(seenAgain, said, now = seenAgain + 8 * day))
        // Said again after the sighting: believed again.
        assertEquals(Verdict.SAID_OFF, verdict(seenAgain, seenAgain + 8 * day, now = seenAgain + 8 * day))
    }

    @Test
    fun aSightingCountsForAWeekNotAMonth() {
        assertEquals(7 * day, CellularExposure.RECENT_MS)
        assertEquals(Verdict.ALLOWED, verdict(t0, null, now = t0 + 6 * day))
        assertEquals(Verdict.NOT_READABLE, verdict(t0, null, now = t0 + 8 * day))
        val unknown = CellularExposure.row(Verdict.NOT_READABLE, true, t0 to "2G CDMA", null, platformWarnings = false)
        assertEquals(Severity.LOW, unknown.severity)
        assertTrue(unknown.detail!!.contains("last used a 2G CDMA tower"))
        assertEquals("the owner can answer here too", CellularExposure.ANSWER_SAID_OFF, unknown.answer?.id)
    }

    @Test
    fun whatTheSystemEnforcesOutranksEverything() {
        assertEquals(Verdict.BLOCKED_ADVANCED_PROTECTION, verdict(t0, null, now = t0 + 1_000L, ap = true))
        assertEquals(Verdict.BLOCKED_POLICY, verdict(t0, null, now = t0 + 1_000L, policy = true))
        assertNull(CellularExposure.row(Verdict.BLOCKED_POLICY, true, null, null, platformWarnings = true).severity)
    }

    @Test
    fun aPhoneWithoutTheSwitchIsNotToldToFlipIt() {
        assertEquals(Verdict.NO_SWITCH, verdict(null, null, now = t0, hasSwitch = false))
        assertEquals(Verdict.ALLOWED, verdict(t0, null, now = t0 + day, hasSwitch = false))
        val row = CellularExposure.row(Verdict.ALLOWED, false, t0 to "2G GSM", null, platformWarnings = false)
        assertEquals(Severity.HIGH, row.severity)
        assertNull(row.answer)
        assertNull(row.fixAction)
        // And an inconsistent call never throws: no sighting, no ALLOWED.
        assertEquals("No switch on this phone", CellularExposure.row(Verdict.ALLOWED, false, null, null, false).value)
    }

    @Test
    fun androidsOwnWarningsAreAPointerNotAVerdict() {
        val row = CellularExposure.notificationsRow()
        assertEquals(CellularExposure.NOTIFICATIONS_FACT_ID, row.id)
        assertNull("apps cannot read it, so it is not an issue", row.severity)
        assertTrue(row.detail!!.contains("Android 15"))
        assertEquals(android.provider.Settings.ACTION_SECURITY_SETTINGS, row.fixAction)
        val red = CellularExposure.row(Verdict.ALLOWED, true, t0 to "2G GSM", null, platformWarnings = true)
        assertTrue(red.detail!!.contains("cell network warnings"))
    }
}
