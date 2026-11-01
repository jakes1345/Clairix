package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Patterns page's suggestions and its Places cards' usual departures give
 * times to the quarter hour, so a glance
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

    private val zone = ZoneId.systemDefault()
    private val home = 40.0000 to -83.0000
    private val work = 40.0200 to -83.0000

    private class Fixes(val zone: ZoneId) {
        val list = ArrayList<Fix>()

        fun at(day: LocalDate, minute: Int, seconds: Int = 0): Long =
            day.atTime(LocalTime.of(minute / 60, minute % 60, seconds)).atZone(zone).toInstant().toEpochMilli()

        fun stay(place: Pair<Double, Double>, from: Long, until: Long) {
            var t = from
            while (t < until) { list += Fix(place.first, place.second, 0f, t, 10f); t += 5 * 60_000L }
            list += Fix(place.first, place.second, 0f, until, 10f)
        }

        fun drive(a: Pair<Double, Double>, b: Pair<Double, Double>, from: Long): Long {
            val steps = 30
            for (k in 1 until steps) {
                val f = k.toDouble() / steps
                list += Fix(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 2.5f, from + k * 30_000L, 10f)
            }
            return from + steps * 30_000L
        }
    }

    /**
     * Two working weeks: home until 07:50–07:54, a 15-minute drive, work until
     * 17:35–17:39 (the same minute on the same weekday both weeks), the drive
     * back, home again. Monday 1 June 2026 to Friday 12 June: ten weekdays, no
     * clock change anywhere.
     */
    private fun fortnight(): List<Fix> {
        val f = Fixes(zone)
        val weekdays = (0L until 12L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
            .filter { it.dayOfWeek.value <= 5 }
        weekdays.forEachIndexed { i, day ->
            val leaveHome = f.at(day, 470 + i % 5)          // 07:50 .. 07:54
            val leaveWork = f.at(day, 1055 + (i * 2) % 5)   // 17:35 .. 17:39
            f.stay(home, f.at(day, 0), leaveHome)
            val atWork = f.drive(home, work, leaveHome)
            f.stay(work, atWork, leaveWork)
            val backHome = f.drive(work, home, leaveWork)
            f.stay(home, backHome, f.at(day, 23 * 60 + 55))
        }
        return f.list
    }

    /** The precise times are in the fixes; none of them may come out in the suggestions. */
    @Test
    fun theSuggestionsForAFortnightNameOnlyQuarterHours() {
        val report = PatternOfLife.analyze(fortnight())

        assertEquals(listOf("Home", "Work"), report.clusters.take(2).map { it.label })
        assertTrue(report.suggestions.toString(), "You leave home 07:45–08:00 on 100% of weekdays." in report.suggestions)
        assertTrue(report.suggestions.toString(), report.suggestions.any { it.startsWith("You leave work around 17:") })
        val times = Regex("\\b(\\d{2}):(\\d{2})\\b").findAll(report.suggestions.joinToString(" ")).toList()
        assertTrue("no times in ${report.suggestions}", times.isNotEmpty())
        for (t in times) {
            assertTrue("${t.value} is not on a quarter hour in ${report.suggestions}", t.groupValues[2].toInt() % 15 == 0)
        }

        // The Places cards on the same page list the usual departures: quarter hours there too.
        val labels = report.clusters.flatMap { c -> c.departures.map { it.label } }
        assertTrue("no departures listed", labels.isNotEmpty())
        for (label in labels) {
            val t = Regex("\\b(\\d{2}):(\\d{2})\\b").find(label)
            assertTrue("no time in $label", t != null)
            assertTrue("$label is not on a quarter hour", t!!.groupValues[2].toInt() % 15 == 0)
        }
    }

    @Test
    fun aDepartureLabelNamesTheQuarterHour() {
        assertEquals("Mon 07:45 ± 6 min", DepartureStats(java.time.DayOfWeek.MONDAY, 472, 6.4f, 10).label)
    }

    @Test
    fun aFortnightFindsHomeAndWorkWhereTheyAreAndTheDepartureWindow() {
        val report = PatternOfLife.analyze(fortnight())
        val (h, w) = report.clusters
        assertEquals("Home", h.label)
        assertEquals(home.first, h.lat, 0.0005)
        assertEquals(home.second, h.lon, 0.0005)
        assertEquals("Work", w.label)
        assertEquals(work.first, w.lat, 0.0005)
        assertEquals(10, w.visits)
        assertEquals(10, w.days)
        assertEquals(2, report.clusters.size)

        // 07:50 to 07:54: the 10th to the 90th percentile is four minutes wide, and that is tight.
        assertEquals(4, report.weekdayDepartureWindowMin)
        assertTrue(report.suggestions.toString(), "Consider varying your departure time by ±15 minutes." in report.suggestions)

        // Leaving work: the same minute on each weekday both weeks.
        assertEquals(
            listOf(DayOfWeek.MONDAY to 1055, DayOfWeek.TUESDAY to 1057, DayOfWeek.WEDNESDAY to 1059, DayOfWeek.THURSDAY to 1056, DayOfWeek.FRIDAY to 1058),
            w.departures.map { it.weekday to it.meanMinuteOfDay }
        )
        assertTrue(w.departures.all { it.samples == 2 && it.stdMinutes == 0f })
        assertTrue("a clockwork fortnight scored ${report.predictabilityScore}", report.predictabilityScore >= 0.9f)
        assertEquals(12, report.daysCovered)
    }

    @Test
    fun aPlaceVisitedTwiceIsNotWork() {
        val f = Fixes(zone)
        val cafe = 40.0100 to -83.0100
        val days = (0L until 7L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        for ((i, day) in days.withIndex()) {
            if (i == 1 || i == 4) {
                f.stay(home, f.at(day, 0), f.at(day, 10 * 60))
                val there = f.drive(home, cafe, f.at(day, 10 * 60))
                f.stay(cafe, there, f.at(day, 11 * 60 + 30))
                val back = f.drive(cafe, home, f.at(day, 11 * 60 + 30))
                f.stay(home, back, f.at(day, 23 * 60 + 55))
            } else {
                f.stay(home, f.at(day, 0), f.at(day, 23 * 60 + 55))
            }
        }
        val report = PatternOfLife.analyze(f.list)
        assertEquals(listOf("Home", "Place 2"), report.clusters.map { it.label })
        assertEquals(2, report.clusters[1].visits)
        assertTrue(report.suggestions.none { it.startsWith("You leave work") })
    }

    @Test
    fun tooLittleHistorySaysSoAndNamesNoPlace() {
        val report = PatternOfLife.analyze(listOf(Fix(home.first, home.second, 0f, 1_780_000_000_000L, 10f)))
        assertTrue(report.clusters.isEmpty())
        assertEquals(0f, report.predictabilityScore)
        assertEquals(listOf("Not enough location history yet. Keep the scanner running for a week or two."), report.suggestions)
    }
}
