package app.clairix.analysis

import app.clairix.Rat
import app.clairix.ServingCell
import app.clairix.Store
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The cell-tower baseline under the history retention: it keeps which towers
 * and areas are usual and the last day each was seen, forgets what has not
 * been seen for three retention periods, and the rules it feeds answer the
 * same afterwards.
 */
class BaselineRetentionTest {

    private val day = BaselineRetention.DAY_MS
    private val now = 1_700_000_000_000L + 13 * 60 * 60_000L + 17_123L
    private val thirtyDays = now - 30 * day

    private fun baseline(): JSONObject {
        val cells = JSONObject()
            .put("310-260-100-A", JSONObject().put("firstSeen", now - 200 * day).put("lastSeen", now - 5 * 60_000L).put("count", 400).put("taZeroPolls", 30).put("taZeroFirst", now - 3 * day))
            .put("310-260-100-B", JSONObject().put("lastSeen", now - 100 * day).put("count", 3).put("taZeroFirst", now - 20 * 60 * 60_000L))
            .put("310-260-200-C", JSONObject().put("lastSeen", now - 80 * day).put("count", 9))
        val tacs = JSONObject()
            .put("310-260-100", JSONObject().put("visits", 40).put("lastSeenTs", now - 10 * 60_000L).put("cells", JSONArray().put("A").put("B")))
            .put("310-260-200", JSONObject().put("visits", 2).put("lastSeenTs", now - 80 * day).put("cells", JSONArray().put("C")))
            .put("310-260-300", JSONObject().put("visits", 1).put("lastSeenTs", now - 120 * day).put("cells", JSONArray().put("D")))
        val cellTacs = JSONObject()
            .put("310-260-LTE-A", JSONObject().put("tac", "100").put("since", now - 200 * day).put("pendingTac", "101").put("pendingPolls", 3).put("pendingSince", now - 4 * day))
            .put("310-260-LTE-B", JSONObject().put("tac", "100").put("since", now - 100 * day))
            .put("310-260-LTE-C", JSONObject().put("tac", "200").put("pendingTac", "201").put("pendingPolls", 1).put("pendingSince", now - 30 * 60_000L))
        return JSONObject().put("cells", cells).put("tacs", tacs).put("cellTacs", cellTacs).put("lastTac", "310-260-100")
    }

    @Test
    fun exactTimesAreKeptOnlyWhileARuleCanNeedThemThenRoundedToTheDay() {
        val data = baseline()
        assertTrue(BaselineRetention.apply(data, cutoff = null, now = now) > 0)
        val cells = data.getJSONObject("cells")
        val a = cells.getJSONObject("310-260-100-A")
        // Never needed by a rule: dropped, or kept to the day at once.
        assertFalse(a.has("firstSeen"))
        assertEquals(dayFloor(now - 5 * 60_000L), a.getLong("lastSeen"))
        // Three days old: no rule measures a gap that long to more than the day.
        assertEquals(dayFloor(now - 3 * day), a.getLong("taZeroFirst"))
        // Twenty hours old: rule 9 still counts the day it takes, so it stays exact.
        assertEquals(now - 20 * 60 * 60_000L, cells.getJSONObject("310-260-100-B").getLong("taZeroFirst"))
        val tacs = data.getJSONObject("tacs")
        assertEquals(now - 10 * 60_000L, tacs.getJSONObject("310-260-100").getLong("lastSeenTs"))
        assertEquals(dayFloor(now - 80 * day), tacs.getJSONObject("310-260-200").getLong("lastSeenTs"))
        val cellTacs = data.getJSONObject("cellTacs")
        assertFalse(cellTacs.getJSONObject("310-260-LTE-A").has("since"))
        assertEquals(dayFloor(now - 4 * day), cellTacs.getJSONObject("310-260-LTE-A").getLong("pendingSince"))
        assertEquals(now - 30 * 60_000L, cellTacs.getJSONObject("310-260-LTE-C").getLong("pendingSince"))
        // Kept for ever, history and baseline alike: no tower or area is forgotten.
        assertEquals(3, cells.length())
        assertEquals(3, tacs.length())
        assertEquals(3, cellTacs.length())
        // Applied again, nothing is left to change.
        assertEquals(0, BaselineRetention.apply(data, cutoff = null, now = now))
    }

    @Test
    fun aTowerOrAreaNotSeenForThreeRetentionPeriodsIsForgottenWithEveryTraceOfIt() {
        val data = baseline()
        BaselineRetention.apply(data, cutoff = thirtyDays, now = now)
        val cells = data.getJSONObject("cells")
        val tacs = data.getJSONObject("tacs")
        val cellTacs = data.getJSONObject("cellTacs")
        // B: a hundred days unseen against a thirty-day retention.
        assertFalse(cells.has("310-260-100-B"))
        assertFalse(cellTacs.has("310-260-LTE-B"))
        assertEquals(listOf("A"), tacs.getJSONObject("310-260-100").getJSONArray("cells").let { arr -> (0 until arr.length()).map { arr.getString(it) } })
        // Area 300: a hundred and twenty days unseen.
        assertFalse(tacs.has("310-260-300"))
        // C and its area, eighty days: still inside ninety.
        assertTrue(cells.has("310-260-200-C"))
        assertTrue(tacs.has("310-260-200"))
        assertTrue(cellTacs.has("310-260-LTE-C"))
        assertTrue(cells.has("310-260-100-A"))
        // A seven-day retention forgets in twenty-one.
        val week = baseline()
        BaselineRetention.apply(week, cutoff = now - 7 * day, now = now)
        assertEquals(listOf("310-260-100-A"), week.getJSONObject("cells").keys().asSequence().toList())
        assertEquals(listOf("310-260-100"), week.getJSONObject("tacs").keys().asSequence().toList())
    }

    @Test
    fun theVisitRuleCountsTheSameAfterTheTimesAreCoarsened() {
        var clock = now - 3 * day
        val dir = Files.createTempDirectory("baseline").toFile().apply { deleteOnExit() }
        val catcher = IMSICatcher(Store(File(dir, "imsi_baseline.json")), clock = { clock })
        fun at(tac: String) = ServingCell(
            mcc = "310", mnc = "260", tac = tac, cellId = "C$tac", rat = Rat.LTE, signalDbm = -95, neighbors = 3, ts = clock
        )
        catcher.recordAndAnalyze(at("100"), null)
        clock += 60 * 60_000L
        catcher.recordAndAnalyze(at("200"), null)
        val before = catcher.stats().second
        clock = now
        assertTrue(catcher.applyRetention(cutoff = thirtyDays, now = now) > 0)
        // Back in area 100 three days on: a new visit, as it was before the times were rounded.
        catcher.recordAndAnalyze(at("100"), null)
        assertEquals(before + 1, catcher.stats().second)
        // And a return within the visit gap is still not one.
        clock += 60_000L
        catcher.recordAndAnalyze(at("200"), null)
        val afterTrip = catcher.stats().second
        clock += 60_000L
        catcher.recordAndAnalyze(at("100"), null)
        assertEquals(afterTrip, catcher.stats().second)
    }
}
