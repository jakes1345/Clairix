package app.clairix.analysis

import app.clairix.nav.MapTiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hourly retention pass looks at the oldest entry first and stops there when it is
 * inside the retention: no walk over thirty thousand location rows, no tile delete.
 */
class HistoryPruneShortCircuitTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L
    private val cutoff = now - 30 * day

    private fun row(ts: Long) = JSONArray().put(ts).put(40.0).put(-75.0).put(10.0)

    @Test
    fun aLocationLogWhoseFirstRowIsRecentIsLeftAlone() {
        val log = JSONArray().put(row(cutoff + 1_000L)).put(row(now - 60_000L))
        assertEquals(cutoff + 1_000L, LocationHistory.oldestTime(log))
        assertFalse(ScanPacing.holdsOlderThan(LocationHistory.oldestTime(log), cutoff))
    }

    @Test
    fun aLocationLogWithAnExpiredFirstRowIsWalked() {
        val log = JSONArray().put(row(cutoff - 1_000L)).put(row(now))
        assertTrue(ScanPacing.holdsOlderThan(LocationHistory.oldestTime(log), cutoff))
    }

    @Test
    fun anEmptyLogHasNothingToWalk() {
        assertNull(LocationHistory.oldestTime(JSONArray()))
        assertFalse(ScanPacing.holdsOlderThan(LocationHistory.oldestTime(JSONArray()), cutoff))
    }

    @Test
    fun aMalformedFirstRowIsWalkedAndDropped() {
        // The walk drops every row it cannot read; a bad first row must not stop pruning for good.
        val notARow = JSONArray().put("garbage").put(row(now))
        assertTrue(ScanPacing.holdsOlderThan(LocationHistory.oldestTime(notARow), cutoff))
        val noTime = JSONArray().put(JSONArray()).put(row(now))
        assertTrue(ScanPacing.holdsOlderThan(LocationHistory.oldestTime(noTime), cutoff))
    }

    @Test
    fun theTileCacheIsDeletedFromOnlyWhenItsOldestTileIsPastTheCutoff() {
        // A tile's row keeps when it expires: fetched, plus the server's lifetime, plus a week.
        val limit = cutoff + MapTiles.EXTRA_LIFETIME_MS
        assertFalse(MapTiles.pruneDue(oldestExpires = null, cutoff = cutoff))
        assertFalse(MapTiles.pruneDue(oldestExpires = limit, cutoff = cutoff))
        assertFalse(MapTiles.pruneDue(oldestExpires = now + day, cutoff = cutoff))
        assertTrue(MapTiles.pruneDue(oldestExpires = limit - 1, cutoff = cutoff))
    }
}
