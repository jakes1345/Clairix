package app.clairix.analysis

import app.clairix.CatcherFinding
import app.clairix.Rat
import app.clairix.ServingCell
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.Threat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Motion gating, call context and per-cell memory: what a handoff on the move, a
 * voice call without VoLTE, a rooftop cell and a carrier re-plan look like must not
 * read as a catcher, and what a catcher looks like still must.
 */
class IMSICatcherTest {

    private var now = 1_700_000_000_000L

    private fun catcher(): IMSICatcher {
        val dir = Files.createTempDirectory("imsi").toFile().apply { deleteOnExit() }
        return IMSICatcher(Store(File(dir, "imsi_baseline.json")), clock = { now })
    }

    private fun cell(id: String, tac: String = "100", rat: Rat = Rat.LTE, ta: Int? = null, inCall: Boolean = false) =
        ServingCell(
            mcc = "310", mnc = "260", tac = tac, cellId = id, rat = rat, signalDbm = -95, neighbors = 3, ts = now,
            timingAdvance = ta, inCall = inCall
        )

    /** About [metres] north of the origin. */
    private fun fixAt(metres: Double, speed: Float?) =
        Fix(lat = 40.0 + metres / 111_195.0, lon = -75.0, speed = speed, time = now)

    /**
     * Cell A serves for a minute, then B for six: A has then been "gone" long enough
     * for both ephemeral rules to look at it, and its 60 s lifetime is inside both
     * windows. Returns every finding raised along the way.
     */
    private fun briefCellThenHandoff(speed: Float): List<CatcherFinding> {
        val c = catcher()
        val out = ArrayList<CatcherFinding>()
        var metres = 0.0
        val step = 15_000L
        repeat(4) {
            out += c.recordAndAnalyze(cell("A"), fixAt(metres, speed))
            now += step; metres += speed * 15
        }
        repeat(26) {
            out += c.recordAndAnalyze(cell("B"), fixAt(metres, speed))
            now += step; metres += speed * 15
        }
        return out
    }

    @Test
    fun aBriefCellWhileDrivingIsAHandoffNotAnEphemeralTower() {
        val findings = briefCellThenHandoff(speed = 15f)
        assertFalse(findings.map { it.id }.toString(), findings.any { it.id.startsWith("ephemeral_cell") })
    }

    @Test
    fun theSameBriefCellWhileStandingStillIsStillReported() {
        val ids = briefCellThenHandoff(speed = 0f).map { it.id }
        assertTrue(ids.toString(), "ephemeral_cell_strict:A" in ids)
        assertTrue(ids.toString(), "ephemeral_cell:A" in ids)
    }

    @Test
    fun withoutReportedSpeedMotionIsJudgedBySpeedNotDistance() {
        // 150 m in 15 s is 36 km/h. The old fallback called anything under 200 m
        // between fixes "stationary" and raised an area change while driving.
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), fixAt(0.0, speed = null))
        now += 15_000L
        val driving = c.recordAndAnalyze(cell("B", tac = "200"), fixAt(150.0, speed = null))
        assertFalse(driving.map { it.id }.toString(), driving.any { it.id == "tac_change_stationary" })
    }

    @Test
    fun withoutReportedSpeedStandingStillStillCounts() {
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), fixAt(0.0, speed = null))
        now += 15_000L
        val still = c.recordAndAnalyze(cell("B", tac = "200"), fixAt(5.0, speed = null))
        assertTrue(still.map { it.id }.toString(), still.any { it.id == "tac_change_stationary" })
    }

    @Test
    fun aFirstFixWithoutSpeedIsNotStationary() {
        // Same tracking-area change as above, but the first fix of the session has no
        // speed and nothing to compare with: motion is unknown, so the rule stays quiet.
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), null)
        now += 15_000L
        val first = c.recordAndAnalyze(cell("B", tac = "200"), fixAt(0.0, speed = null))
        assertFalse(first.map { it.id }.toString(), first.any { it.id == "tac_change_stationary" })
    }

    @Test
    fun aSettledPhoneCountsAsStationaryWithoutAFreshFix() {
        // Settled phones get a fix every few minutes, so the newest one is usually
        // past FIX_STALE_MS. The scanner's confirmed stillness stands in for it.
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), fixAt(0.0, speed = null), settled = true)
        now += 4 * 60_000L
        val still = c.recordAndAnalyze(cell("B", tac = "200"), fixAt(0.0, speed = null).copy(time = now - 4 * 60_000L), settled = true)
        assertTrue(still.map { it.id }.toString(), still.any { it.id == "tac_change_stationary" })
    }

    @Test
    fun aStalePreviousFixDoesNotMakeTheCurrentOneStationary() {
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), fixAt(0.0, speed = null))
        // Ten minutes later, a few metres away: the average speed is near zero, but a
        // fix that old says nothing about whether the phone is moving now.
        now += 10 * 60_000L
        val later = c.recordAndAnalyze(cell("B", tac = "200"), fixAt(5.0, speed = null))
        assertFalse(later.map { it.id }.toString(), later.any { it.id == "tac_change_stationary" })
    }

    @Test
    fun aRepeatedFixIsComparedWithTheOneBeforeIt() {
        // A poll that sees the same fix twice has no time delta of its own; the
        // movement is the one between that fix and the distinct fix before it.
        val c = catcher()
        c.recordAndAnalyze(cell("A", tac = "100"), fixAt(0.0, speed = null))
        now += 15_000L
        val moved = fixAt(150.0, speed = null)
        c.recordAndAnalyze(cell("A", tac = "100"), moved)
        now += 5_000L
        val repeat = c.recordAndAnalyze(cell("B", tac = "200"), moved)
        assertFalse(repeat.map { it.id }.toString(), repeat.any { it.id == "tac_change_stationary" })
    }

    // ── Call context: circuit-switched fallback ─────────────────────────────

    private fun still() = fixAt(0.0, speed = 0f)
    private fun ids(findings: List<CatcherFinding>) = findings.map { it.id }

    @Test
    fun aDowngradeWhileStandingStillOutsideACallIsReported() {
        val c = catcher()
        repeat(2) { c.recordAndAnalyze(cell("A"), still()); now += 15_000L }
        val down = c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS), still())
        assertTrue(ids(down).toString(), "rat_downgrade" in ids(down))
    }

    @Test
    fun aVoiceCallFallingBackTo3GIsNotADowngradeOrABriefTower() {
        val c = catcher()
        val seen = ArrayList<CatcherFinding>()
        repeat(4) { seen += c.recordAndAnalyze(cell("A"), still()); now += 15_000L }
        // A 45-second call on a SIM without VoLTE: three readings on a 3G cell, one more
        // there after hanging up, then back on LTE for seven minutes, long enough for the
        // 3G cell to count as gone and be judged by both ephemeral-cell rules.
        repeat(3) { seen += c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS, inCall = true), still()); now += 15_000L }
        seen += c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS), still()); now += 15_000L
        repeat(28) { seen += c.recordAndAnalyze(cell("A"), still()); now += 15_000L }
        val tripped = ids(seen).filter { it == "rat_downgrade" || it == "rat_oscillation" || it.startsWith("ephemeral_cell") }
        assertTrue(tripped.toString(), tripped.isEmpty())
    }

    @Test
    fun stillOn3GFiveMinutesAfterACallIsReported() {
        // The call explains the fallback and the minutes it takes to come back, not a
        // phone that stays down long after hanging up.
        val c = catcher()
        repeat(2) { c.recordAndAnalyze(cell("A"), still()); now += 15_000L }
        c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS, inCall = true), still())
        now += 6 * 60_000L
        val stuck = c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS), still())
        assertTrue(ids(stuck).toString(), "rat_downgrade" in ids(stuck))
    }

    @Test
    fun threeTechnologiesStandingStillOutsideACallIsHigh() {
        val c = catcher()
        c.recordAndAnalyze(cell("N", rat = Rat.NR5G), still()); now += 15_000L
        c.recordAndAnalyze(cell("L", rat = Rat.LTE), still()); now += 15_000L
        val f = c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS), still())
        assertTrue(ids(f).toString(), "rat_oscillation" in ids(f))
        assertEquals(Threat.HIGH, c.scoreAndLevel(f).second)
    }

    @Test
    fun aFiveGPhonesCallIsNotOscillation() {
        // NR before the call, 3G for it, LTE straight after, NR again: three
        // technologies inside a minute, which is what one call looks like.
        val c = catcher()
        val seen = ArrayList<CatcherFinding>()
        seen += c.recordAndAnalyze(cell("N", rat = Rat.NR5G), still()); now += 15_000L
        repeat(2) { seen += c.recordAndAnalyze(cell("U", tac = "900", rat = Rat.UMTS, inCall = true), still()); now += 15_000L }
        seen += c.recordAndAnalyze(cell("L", rat = Rat.LTE), still()); now += 15_000L
        val last = c.recordAndAnalyze(cell("N", rat = Rat.NR5G), still())
        seen += last
        assertTrue(ids(seen).toString(), seen.none { it.id == "rat_oscillation" || it.id == "rat_downgrade" })
        assertEquals(Threat.NONE, c.scoreAndLevel(last).second)
    }

    @Test
    fun aCallMovingA5GPhoneToLteInAnotherAreaIsNotAnAreaChange() {
        // 5G without voice over NR: the call moves the phone to LTE, whose tracking area
        // can carry a different number. Outside a call the same change still counts
        // (withoutReportedSpeedStandingStillStillCounts).
        val c = catcher()
        c.recordAndAnalyze(cell("N", tac = "100", rat = Rat.NR5G), still()); now += 15_000L
        val call = c.recordAndAnalyze(cell("L", tac = "200", inCall = true), still())
        assertFalse(ids(call).toString(), "tac_change_stationary" in ids(call))
    }

    // ── Timing advance: per-cell memory ─────────────────────────────────────

    /**
     * Five separate visits to area 100, each a return after more than half an hour in
     * area 200, which arms the visit-gated rules; the area-200 cell reports a real
     * timing advance, which arms the timing-advance rule.
     */
    private fun learnArea(c: IMSICatcher) {
        repeat(5) {
            c.recordAndAnalyze(cell("H", tac = "100"), null)
            now += 31 * 60_000L
            c.recordAndAnalyze(cell("W", tac = "200", ta = 6), null)
            now += 31 * 60_000L
        }
    }

    @Test
    fun timingAdvanceZeroIsListedAsLowAndWeighsLowAlone() {
        val c = catcher()
        learnArea(c)
        val f = c.recordAndAnalyze(cell("H", tac = "100", ta = 0), null)
        val ta = f.firstOrNull { it.id == "timing_advance_zero" }
        assertNotNull(ids(f).toString(), ta)
        assertEquals(Severity.LOW, ta!!.severity)
        assertEquals(28 to Threat.LOW, c.scoreAndLevel(listOf(ta)))
    }

    @Test
    fun aCellThatReadsZeroDayAfterDayStopsBeingNewsButTenMinutesIsNotEnough() {
        val c = catcher()
        learnArea(c)
        // A small cell on the roof reads zero on every reading at home. Ten minutes of
        // it is still listed: a transmitter parked outside looks the same.
        var last = emptyList<CatcherFinding>()
        repeat(40) { last = c.recordAndAnalyze(cell("H", tac = "100", ta = 0), null); now += 15_000L }
        assertTrue(ids(last).toString(), "timing_advance_zero" in ids(last))
        // Still zero there the next day: normal for this cell, no longer listed.
        now += 24 * 60 * 60_000L
        val nextDay = c.recordAndAnalyze(cell("H", tac = "100", ta = 0), null)
        assertFalse(ids(nextDay).toString(), "timing_advance_zero" in ids(nextDay))
        // Another cell in the same area reading zero for the first time is still news.
        val other = c.recordAndAnalyze(cell("K", tac = "100", ta = 0), null)
        assertTrue(ids(other).toString(), "timing_advance_zero" in ids(other))
    }

    // ── Cell ID in the wrong area: technology in the key, re-plans relearned ──

    @Test
    fun aKnownCellClaimingAnotherAreaIsReported() {
        val c = catcher()
        c.recordAndAnalyze(cell("C", tac = "100"), null); now += 15_000L
        val moved = c.recordAndAnalyze(cell("C", tac = "101"), null)
        assertTrue(ids(moved).toString(), "cellid_tac_mismatch" in ids(moved))
    }

    @Test
    fun a3GCellAndAnLteCellSharingANumberAreDifferentCells() {
        // The modem reports the 3G location area code in the same field as the LTE
        // tracking area code, and cell numbers repeat across technologies.
        val c = catcher()
        c.recordAndAnalyze(cell("12345", tac = "7", rat = Rat.UMTS), null); now += 15_000L
        val lte = c.recordAndAnalyze(cell("12345", tac = "300"), null)
        assertFalse(ids(lte).toString(), "cellid_tac_mismatch" in ids(lte))
    }

    @Test
    fun aCarrierRePlanIsRelearned() {
        val c = catcher()
        c.recordAndAnalyze(cell("C", tac = "100"), null)
        // The carrier moves the cell to area 101 for good. Reported on every reading for
        // over an hour, the new area becomes the baseline and the finding clears: at one
        // reading every 90 s, the 41st is both the 40th repeat and an hour after the first.
        var last = emptyList<CatcherFinding>()
        repeat(41) { now += 90_000L; last = c.recordAndAnalyze(cell("C", tac = "101"), null) }
        assertTrue(ids(last).toString(), "cellid_tac_mismatch" in ids(last))
        now += 90_000L
        val relearned = c.recordAndAnalyze(cell("C", tac = "101"), null)
        assertFalse(ids(relearned).toString(), "cellid_tac_mismatch" in ids(relearned))
        // The old area is now the odd one out.
        now += 90_000L
        val old = c.recordAndAnalyze(cell("C", tac = "100"), null)
        assertTrue(ids(old).toString(), "cellid_tac_mismatch" in ids(old))
    }

    @Test
    fun aCellNumberBorrowedOnAndOffIsNeverRelearned() {
        // A transmitter using a real cell's number in another area: the real cell is
        // heard in between, so the new area is never reported consistently.
        val c = catcher()
        c.recordAndAnalyze(cell("C", tac = "100"), null)
        repeat(60) { i -> now += 90_000L; c.recordAndAnalyze(cell("C", tac = if (i % 3 == 2) "100" else "101"), null) }
        now += 90_000L
        val later = c.recordAndAnalyze(cell("C", tac = "101"), null)
        assertTrue(ids(later).toString(), "cellid_tac_mismatch" in ids(later))
    }

    @Test
    fun oldFormatAreaEntriesAreDroppedOnLoad() {
        // Earlier versions stored "mcc-mnc-cell": "area" with no technology; those
        // entries can never be read under the new key and must not linger.
        val dir = Files.createTempDirectory("imsi").toFile().apply { deleteOnExit() }
        val file = File(dir, "imsi_baseline.json")
        file.writeText("""{"cells":{},"tacs":{},"cellTacs":{"310-260-C":"7"}}""")
        val store = Store(file, cipher = TestStoreCipher)
        IMSICatcher(store, clock = { now })
        assertFalse(store.json.optJSONObject("cellTacs")!!.has("310-260-C"))
    }
}
