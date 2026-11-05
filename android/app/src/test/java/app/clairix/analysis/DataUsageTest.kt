package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adding Android's traffic counters up per app, and what counts as an app that keeps uploading. */
class DataUsageTest {

    private val mb = 1_000_000L

    private fun summary(vararg days: Pair<Long, Long>) = DataUsage.Summary(days.map { (rx, tx) -> DataUsage.Day(rx, tx) })

    @Test
    fun theWindowsAreSevenDaysOldestFirstEndingNow() {
        val now = 10L * 24 * 3600_000L
        val w = DataUsage.windows(now)
        assertEquals(DataUsage.DAYS, w.size)
        assertEquals(now, w.last().second)
        assertEquals(now - 24 * 3600_000L, w.last().first)
        assertEquals(now - 7 * 24 * 3600_000L, w.first().first)
        w.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
    }

    @Test
    fun samplesAreAddedUpPerUidAndWindow() {
        val got = DataUsage.accumulate(
            sequenceOf(
                // Mobile and Wi-Fi for the same app in the same window add up.
                DataUsage.Sample(window = 0, uid = 10123, rx = 100, tx = 1_000),
                DataUsage.Sample(window = 0, uid = 10123, rx = 50, tx = 500),
                DataUsage.Sample(window = 6, uid = 10123, rx = 7, tx = 70),
                DataUsage.Sample(window = 2, uid = 10200, rx = 9, tx = 0),
            )
        )
        val app = got.getValue(10123)
        assertEquals(DataUsage.DAYS, app.days.size)
        // Window 0 is the last 24 hours, the newest day, so the last in the list.
        assertEquals(DataUsage.Day(150, 1_500), app.days.last())
        assertEquals(DataUsage.Day(7, 70), app.days.first())
        assertEquals(1_570L, app.tx)
        assertEquals(157L, app.rx)
        assertEquals(9L, got.getValue(10200).rx)
    }

    @Test
    fun aResetCounterOrAWindowOutsideTheWeekIsSkipped() {
        val got = DataUsage.accumulate(
            sequenceOf(
                DataUsage.Sample(window = 7, uid = 1, rx = 1, tx = 1),
                DataUsage.Sample(window = -1, uid = 1, rx = 1, tx = 1),
                DataUsage.Sample(window = 0, uid = 1, rx = -5, tx = 3),
                DataUsage.Sample(window = 0, uid = 2, rx = 4, tx = 4),
            )
        )
        assertFalse(1 in got)
        assertEquals(4L, got.getValue(2).tx)
    }

    @Test
    fun aSteadyUploaderSendsMoreThanItReceivesOnMostDays() {
        // 2 MB out and 0.1 MB in every day: a monitoring app's shape.
        val steady = summary(*Array(7) { 100_000L to 2 * mb })
        assertTrue(steady.steadyUploader)
        assertEquals(7, steady.uploadDays)
        // The same upload on only three days is not steady.
        val bursts = summary(0L to 0L, 0L to 0L, 0L to 0L, 0L to 0L, 0L to 5 * mb, 0L to 5 * mb, 0L to 5 * mb)
        assertFalse(bursts.steadyUploader)
        // Receives more than it sends (video, a browser): never, however steady.
        assertFalse(summary(*Array(7) { 30 * mb to 2 * mb }).steadyUploader)
        // Steady but small: a few hundred kilobytes a day stays under the total.
        assertFalse(summary(*Array(7) { 0L to 300_000L }).steadyUploader)
    }

    @Test
    fun bytesReadLikeAndroidsOwnSettings() {
        assertEquals("812 B", DataUsage.bytes(812))
        assertEquals("4.2 kB", DataUsage.bytes(4_200))
        assertEquals("42.0 MB", DataUsage.bytes(42 * mb))
        assertEquals("1.3 GB", DataUsage.bytes(1_300 * mb))
    }

    @Test
    fun theTextHasTheCountsAndNoSemicolon() {
        val text = DataUsage.text(summary(*Array(7) { 100_000L to 2 * mb }))
        assertEquals("sent 14.0 MB and received 700.0 kB in 7 days, sending on 7 of them", text)
        assertFalse(';' in text)
    }

    @Test
    fun theCaveatSaysTheCountsAreCoarseAndNeverWhatOrWhere() {
        assertTrue("hour" in DataUsage.CAVEAT)
        assertTrue("approximate" in DataUsage.CAVEAT)
        assertTrue("never to where or what" in DataUsage.CAVEAT)
    }
}
