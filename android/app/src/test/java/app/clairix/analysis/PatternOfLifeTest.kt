package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Patterns page's suggestions give times to the quarter hour, so a glance
 * over the owner's shoulder does not take away "Mon 07:52"; the share of
 * weekdays they quote is counted for the window they show.
 */
class PatternOfLifeTest {

    @Test
    fun aDepartureWindowWidensOutToWholeQuarterHours() {
        assertEquals(465 to 495, quarterWindow(472, 484))   // 07:52–08:04 shows as 07:45–08:15
        assertEquals(450 to 465, quarterWindow(450, 465))   // already on the quarters: unchanged
        assertEquals(480 to 495, quarterWindow(480, 480))   // a single minute shows as its quarter
        assertEquals(465 to 480, quarterWindow(470, 474))
    }

    @Test
    fun aTimeOfDayGoesToTheNearestQuarter() {
        assertEquals(1050, nearestQuarter(1057))   // 17:37 -> 17:30
        assertEquals(1065, nearestQuarter(1058))   // 17:38 -> 17:45
        assertEquals(480, nearestQuarter(480))
        assertEquals("00:00", clock(nearestQuarter(1439)))  // 23:59 rounds to midnight
    }

    /**
     * Two working weeks: home until 07:50–07:54, a 15-minute drive, work until
     * 17:35–17:39, the drive back, home again. The precise times are in the
     * fixes; none of them may come out in the suggestions.
     */
    @Test
    fun theSuggestionsForAFortnightNameOnlyQuarterHours() {
        val zone = ZoneId.systemDefault()
        val home = 40.0000 to -83.0000
        val work = 40.0200 to -83.0000
        val fixes = ArrayList<Fix>()
        fun at(day: LocalDate, minute: Int, seconds: Int = 0): Long =
            day.atTime(LocalTime.of(minute / 60, minute % 60, seconds)).atZone(zone).toInstant().toEpochMilli()
        fun stay(place: Pair<Double, Double>, from: Long, until: Long) {
            var t = from
            while (t < until) { fixes += Fix(place.first, place.second, 0f, t, 10f); t += 5 * 60_000L }
            fixes += Fix(place.first, place.second, 0f, until, 10f)
        }
        fun drive(a: Pair<Double, Double>, b: Pair<Double, Double>, from: Long): Long {
            val steps = 30
            for (k in 1 until steps) {
                val f = k.toDouble() / steps
                fixes += Fix(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 2.5f, from + k * 30_000L, 10f)
            }
            return from + steps * 30_000L
        }
        // Monday 1 June 2026 to Friday 12 June: ten weekdays, no clock change anywhere.
        val weekdays = (0L until 12L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
            .filter { it.dayOfWeek.value <= 5 }
        weekdays.forEachIndexed { i, day ->
            val leaveHome = at(day, 470 + i % 5)          // 07:50 .. 07:54
            val leaveWork = at(day, 1055 + (i * 2) % 5)   // 17:35 .. 17:39
            stay(home, at(day, 0), leaveHome)
            val atWork = drive(home, work, leaveHome)
            stay(work, atWork, leaveWork)
            val backHome = drive(work, home, leaveWork)
            stay(home, backHome, at(day, 23 * 60 + 55))
        }

        val report = PatternOfLife.analyze(fixes)

        assertEquals(listOf("Home", "Work"), report.clusters.take(2).map { it.label })
        assertTrue(report.suggestions.toString(), "You leave home 07:45–08:00 on 100% of weekdays." in report.suggestions)
        assertTrue(report.suggestions.toString(), report.suggestions.any { it.startsWith("You leave work around 17:") })
        val times = Regex("\\b(\\d{2}):(\\d{2})\\b").findAll(report.suggestions.joinToString(" ")).toList()
        assertTrue("no times in ${report.suggestions}", times.isNotEmpty())
        for (t in times) {
            assertTrue("${t.value} is not on a quarter hour in ${report.suggestions}", t.groupValues[2].toInt() % 15 == 0)
        }
    }
}
