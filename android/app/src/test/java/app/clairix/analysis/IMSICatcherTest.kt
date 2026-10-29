package app.clairix.analysis

import app.clairix.CatcherFinding
import app.clairix.Rat
import app.clairix.ServingCell
import app.clairix.Store
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Motion gating: what a handoff on the move looks like must not read as a catcher. */
class IMSICatcherTest {

    private var now = 1_700_000_000_000L

    private fun catcher(): IMSICatcher {
        val dir = Files.createTempDirectory("imsi").toFile().apply { deleteOnExit() }
        return IMSICatcher(Store(File(dir, "imsi_baseline.json")), clock = { now })
    }

    private fun cell(id: String, tac: String = "100", rat: Rat = Rat.LTE) =
        ServingCell(mcc = "310", mnc = "260", tac = tac, cellId = id, rat = rat, signalDbm = -95, neighbors = 3, ts = now)

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
}
