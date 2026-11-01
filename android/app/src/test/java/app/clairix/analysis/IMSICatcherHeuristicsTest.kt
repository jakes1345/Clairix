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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The cell heuristics IMSICatcherTest does not reach, one reading that should
 * raise each and one ordinary reading that must not: a signal far above what the
 * area ever gave, a cell never seen in an area visited twenty times, four cells in
 * five minutes standing still, a lone cell on a modem known to report neighbours,
 * and a jump of 20 dB on one cell; then the score and the level the findings add
 * up to, at each threshold.
 */
class IMSICatcherHeuristicsTest {

    private var now = 1_700_000_000_000L

    private fun catcher(): IMSICatcher {
        val dir = Files.createTempDirectory("imsi").toFile().apply { deleteOnExit() }
        return IMSICatcher(Store(File(dir, "imsi_baseline.json"), cipher = TestStoreCipher), clock = { now })
    }

    private fun cell(id: String, tac: String = "100", dbm: Int? = -95, neighbors: Int? = 3, rat: Rat = Rat.LTE) =
        ServingCell(mcc = "310", mnc = "260", tac = tac, cellId = id, rat = rat, signalDbm = dbm, neighbors = neighbors, ts = now)

    private fun still() = Fix(lat = 40.0, lon = -75.0, speed = 0f, time = now)
    private fun driving() = Fix(lat = 40.0, lon = -75.0, speed = 14f, time = now)

    private fun ids(findings: List<CatcherFinding>) = findings.map { it.id }

    /**
     * [visits] separate visits to area 100 (cell H), each a return after more than
     * half an hour in area 200 (cell W). Ends in area 200, half an hour before the
     * next reading, so the next area-100 reading is judged with [visits] visits.
     */
    private fun learnArea(c: IMSICatcher, visits: Int, neighbors: Int? = 3) {
        repeat(visits) {
            c.recordAndAnalyze(cell("H", neighbors = neighbors), null)
            now += 31 * 60_000L
            c.recordAndAnalyze(cell("W", tac = "200", neighbors = neighbors), null)
            now += 31 * 60_000L
        }
    }

    // ── 4: signal outlier ───────────────────────────────────────────────────

    @Test
    fun aSignalFarAboveTheAreasBestIsAnOutlier() {
        val c = catcher()
        learnArea(c, 5)
        val f = c.recordAndAnalyze(cell("H", dbm = -70), null)
        assertTrue(ids(f).toString(), "signal_outlier" in ids(f))
    }

    @Test
    fun aGoodDayOrANewAreaIsNotAnOutlier() {
        val c = catcher()
        learnArea(c, 5)
        // Fifteen dB above the best is still weather, a window, a different pocket.
        val f = c.recordAndAnalyze(cell("H", dbm = -80), null)
        assertFalse(ids(f).toString(), "signal_outlier" in ids(f))
        // An area seen four times has no best worth comparing with.
        val fresh = catcher()
        learnArea(fresh, 4)
        val g = fresh.recordAndAnalyze(cell("H", dbm = -50), null)
        assertFalse(ids(g).toString(), "signal_outlier" in ids(g))
    }

    // ── 5: unknown cell at a familiar area ──────────────────────────────────

    @Test
    fun aCellNeverSeenInAnAreaVisitedTwentyTimesIsReported() {
        val c = catcher()
        learnArea(c, 20)
        val f = c.recordAndAnalyze(cell("X"), null)
        assertTrue(ids(f).toString(), "unknown_cell" in ids(f))
    }

    @Test
    fun theUsualCellOrANewOneInALessKnownAreaIsNot() {
        val c = catcher()
        learnArea(c, 20)
        assertFalse("unknown_cell" in ids(c.recordAndAnalyze(cell("H"), null)))
        val young = catcher()
        learnArea(young, 19)
        val f = young.recordAndAnalyze(cell("X"), null)
        assertFalse(ids(f).toString(), "unknown_cell" in ids(f))
    }

    // ── 7: cell flapping ────────────────────────────────────────────────────

    @Test
    fun fourCellsInFiveMinutesStandingStillIsFlapping() {
        val c = catcher()
        var last = emptyList<CatcherFinding>()
        for (id in listOf("A", "B", "C", "D")) { last = c.recordAndAnalyze(cell(id), still()); now += 15_000L }
        assertTrue(ids(last).toString(), "cell_flapping" in ids(last))
    }

    @Test
    fun fourCellsOnTheMoveOrThreeStandingStillAreNot() {
        val moving = catcher()
        val seen = ArrayList<CatcherFinding>()
        for (id in listOf("A", "B", "C", "D", "E")) { seen += moving.recordAndAnalyze(cell(id), driving()); now += 15_000L }
        assertFalse(ids(seen).toString(), "cell_flapping" in ids(seen))

        val three = catcher()
        seen.clear()
        repeat(3) { for (id in listOf("A", "B", "C")) { seen += three.recordAndAnalyze(cell(id), still()); now += 15_000L } }
        assertFalse(ids(seen).toString(), "cell_flapping" in ids(seen))

        // Four cells spread over more than five minutes: the first has left the window.
        val slow = catcher()
        seen.clear()
        for (id in listOf("A", "B", "C", "D")) { seen += slow.recordAndAnalyze(cell(id), still()); now += 2 * 60_000L }
        assertFalse(ids(seen).toString(), "cell_flapping" in ids(seen))
    }

    // ── 8: no neighbours ────────────────────────────────────────────────────

    @Test
    fun aLoneCellOnAPhoneThatReportsNeighboursIsListedLow() {
        val c = catcher()
        learnArea(c, 5)
        val f = c.recordAndAnalyze(cell("H", neighbors = 0), null)
        val lone = f.firstOrNull { it.id == "no_neighbors" }
        assertTrue(ids(f).toString(), lone != null)
        assertEquals(Severity.LOW, lone!!.severity)
    }

    @Test
    fun aModemThatNeverReportsNeighboursIsNotListed() {
        val c = catcher()
        learnArea(c, 5, neighbors = 0)
        assertFalse("no_neighbors" in ids(c.recordAndAnalyze(cell("H", neighbors = 0), null)))
        val unknown = catcher()
        learnArea(unknown, 5)
        assertFalse("not reported is not zero", "no_neighbors" in ids(unknown.recordAndAnalyze(cell("H", neighbors = null), null)))
    }

    // ── 10: signal spike ────────────────────────────────────────────────────

    @Test
    fun twentyDbInOneReadingOnOneCellStandingStillIsASpike() {
        val c = catcher()
        c.recordAndAnalyze(cell("A", dbm = -100), still()); now += 15_000L
        val f = c.recordAndAnalyze(cell("A", dbm = -80), still())
        assertTrue(ids(f).toString(), "signal_spike" in ids(f))
    }

    @Test
    fun aSmallerJumpAJumpOnTheMoveOrAcrossCellsIsNotASpike() {
        val c = catcher()
        c.recordAndAnalyze(cell("A", dbm = -100), still()); now += 15_000L
        assertFalse("signal_spike" in ids(c.recordAndAnalyze(cell("A", dbm = -81), still())))

        val walking = catcher()
        walking.recordAndAnalyze(cell("A", dbm = -110), driving()); now += 15_000L
        assertFalse("out of a building", "signal_spike" in ids(walking.recordAndAnalyze(cell("A", dbm = -80), driving())))

        val handover = catcher()
        handover.recordAndAnalyze(cell("A", dbm = -110), still()); now += 15_000L
        assertFalse("signal_spike" in ids(handover.recordAndAnalyze(cell("B", dbm = -70), still())))

        val falling = catcher()
        falling.recordAndAnalyze(cell("A", dbm = -70), still()); now += 15_000L
        assertFalse("a drop is not a spike", "signal_spike" in ids(falling.recordAndAnalyze(cell("A", dbm = -100), still())))
    }

    // ── An ordinary day ─────────────────────────────────────────────────────

    @Test
    fun aDayOnTheUsualCellsRaisesNothing() {
        val c = catcher()
        learnArea(c, 20)
        // Home again: the arrival reading, from a phone that has not had a fix yet.
        c.recordAndAnalyze(cell("H"), null)
        now += 15_000L
        val seen = ArrayList<CatcherFinding>()
        // An hour at home on the usual cell, signal wandering a few dB, standing still.
        repeat(240) { i -> seen += c.recordAndAnalyze(cell("H", dbm = -95 + (i % 7) - 3), still()); now += 15_000L }
        assertTrue(ids(seen).toString(), seen.isEmpty())
    }

    // ── The score ──────────────────────────────────────────────────────────

    private fun f(id: String) = CatcherFinding(id, Severity.LOW, id, "")

    @Test
    fun theScoreAndLevelAtEachThreshold() {
        val c = catcher()
        assertEquals(0 to Threat.NONE, c.scoreAndLevel(emptyList()))
        assertEquals(15 to Threat.LOW, c.scoreAndLevel(listOf(f("no_neighbors"))))
        assertEquals(28 to Threat.LOW, c.scoreAndLevel(listOf(f("timing_advance_zero"))))
        assertEquals(30 to Threat.MEDIUM, c.scoreAndLevel(listOf(f("rat_oscillation"))))
        assertEquals(47 to Threat.MEDIUM, c.scoreAndLevel(listOf(f("tac_change_stationary"), f("signal_outlier"))))
        assertEquals(50 to Threat.HIGH, c.scoreAndLevel(listOf(f("cellid_tac_mismatch"), f("unknown_cell"))))
        assertEquals(79 to Threat.HIGH, c.scoreAndLevel(listOf(f("tac_change_stationary"), f("signal_outlier"), f("ephemeral_cell_strict:7"))))
        assertEquals(80 to Threat.CRITICAL, c.scoreAndLevel(listOf(f("rat_downgrade"), f("cellid_tac_mismatch"), f("no_neighbors"))))
        assertEquals(100 to Threat.CRITICAL, c.scoreAndLevel(listOf(f("rat_downgrade"), f("cellid_tac_mismatch"), f("rat_oscillation"), f("ephemeral_cell_strict:9"))))
    }

    @Test
    fun eachHeuristicWeighsWhatItSays() {
        val c = catcher()
        val weights = mapOf(
            "rat_downgrade" to 35, "cellid_tac_mismatch" to 30, "tac_change_stationary" to 25, "signal_outlier" to 22,
            "unknown_cell" to 20, "ephemeral_cell:12" to 20, "cell_flapping" to 18, "no_neighbors" to 15,
            "timing_advance_zero" to 28, "signal_spike" to 18, "rat_oscillation" to 30, "ephemeral_cell_strict:12" to 32,
            "something_new" to 10,
        )
        for ((id, w) in weights) assertEquals(id, w, c.scoreAndLevel(listOf(f(id))).first)
    }
}
