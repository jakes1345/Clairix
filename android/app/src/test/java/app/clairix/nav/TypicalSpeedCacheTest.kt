package app.clairix.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The typical-speed cache keeps a week of profiles, or less when the owner's history retention is shorter. */
class TypicalSpeedCacheTest {

    private val nowSec = 1_700_000_000L
    private val day = 24 * 3600L

    @Test
    fun aWeekUnlessTheRetentionIsShorter() {
        // Kept for ever, or thirty days: a week is the shorter.
        assertEquals(nowSec - 7 * day, TypicalSpeedCache.oldestKeptSec(cutoffMs = null, nowSec = nowSec))
        assertEquals(nowSec - 7 * day, TypicalSpeedCache.oldestKeptSec(cutoffMs = (nowSec - 30 * day) * 1000, nowSec = nowSec))
        // A cutoff later than a week back wins.
        assertEquals(nowSec - 3 * day, TypicalSpeedCache.oldestKeptSec(cutoffMs = (nowSec - 3 * day) * 1000, nowSec = nowSec))
    }

    @Test
    fun profilesFetchedBeforeTheCutoffAreDropped() {
        val root = JSONObject().put(
            "segments",
            JSONObject()
                .put("111:0", JSONObject().put("t", nowSec - 8 * day))
                .put("222:1", JSONObject().put("t", nowSec - day))
                .put("333:0", JSONObject().put("t", nowSec - 7 * day - 1))
                .put("444:1", JSONObject())
        )
        assertEquals(3, TypicalSpeedCache.dropOlderThan(root, nowSec - 7 * day))
        assertEquals(listOf("222:1"), root.getJSONObject("segments").keys().asSequence().toList())
        assertEquals(0, TypicalSpeedCache.dropOlderThan(JSONObject(), nowSec))
    }
}
