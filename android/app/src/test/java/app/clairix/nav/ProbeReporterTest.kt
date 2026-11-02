package app.clairix.nav

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The road a trip begins on never reaches the relay, however long it is or however long the car
 * spends on it; reporting starts with the first road entered beyond a start zone drawn per trip,
 * and stops for good once the destination is inside the end zone.
 */
class ProbeReporterTest {

    /** A SecureRandom whose every bounded draw is [draw]: 0 gives the shortest zone (500 m), 1000 the longest (1500 m). */
    private class FixedRandom(private val draw: Int) : SecureRandom() {
        override fun nextInt(bound: Int): Int = draw
    }

    private val scope = CoroutineScope(Job())
    private val t0 = 1_760_000_000_000L
    /** 10 m/s with a fix every 5 s: 50 m per fix, 600 m per minute, so a minute's slice ends past a 500 m zone. */
    private val mps = 10.0
    private val stepMs = 5_000L

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * The reporter takes the application context at construction and uses it only at flush time, and
     * no test flushes; ContextWrapper(null) alone would delegate getApplicationContext() to its missing base.
     */
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    /** A reporter mid-drive with a known zone. */
    private fun started(draw: Int = 0): ProbeReporter =
        ProbeReporter(context, "test", TripPseudonym(), FixedRandom(draw)).also { it.start(scope) }

    /**
     * Drives [lengthM] along [seg] from its start, one fix every [stepMs] from [fromMs]; returns
     * the time of the fix after the last. As with the Navigator, the traversal ends only when the
     * next fix is on another road or on none.
     */
    private fun drive(r: ProbeReporter, seg: String, lengthM: Double, fromMs: Long): Long {
        var t = fromMs
        var along = 0.0
        while (along <= lengthM) {
            r.onMatched(seg, along, mps.toFloat(), t, 40.0, -83.0)
            along += mps * stepMs / 1000
            t += stepMs
        }
        return t
    }

    private fun offRoad(r: ProbeReporter, t: Long) = r.onMatched(null, 0.0, mps.toFloat(), t)

    @Test
    fun aTwoKilometreFirstRoadIsNeverReported() {
        val r = started()
        // Over three minutes on the first road: the slice rule ends a traversal every minute, each
        // well past the 500 m zone, and none of them may be queued.
        var t = drive(r, "way:first", 2_000.0, t0)
        t = drive(r, "way:second", 300.0, t)
        assertEquals(emptyList<String>(), r.queuedSegments())
        offRoad(r, t)
        assertEquals(listOf("way:second"), r.queuedSegments())
        r.stop()
    }

    @Test
    fun aMinuteOnTheFirstRoadIsNotReportedEvenWhenItEndsPastTheZone() {
        val r = started()
        // 70 s on the first road: the slice rule cuts the first minute off after 550 m, beyond the
        // 500 m zone, which is what used to send the road a trip starts on.
        val t = drive(r, "way:first", 700.0, t0)
        assertEquals(emptyList<String>(), r.queuedSegments())
        offRoad(r, t)
        assertEquals(emptyList<String>(), r.queuedSegments())
        r.stop()
    }

    @Test
    fun reportingBeginsWithTheFirstRoadEnteredBeyondTheZone() {
        val r = started()
        var t = drive(r, "way:a", 300.0, t0) // entered at 0 m
        t = drive(r, "way:b", 300.0, t) // entered at 300 m; carries the trip past 500 m and is dropped, not finished
        t = drive(r, "way:c", 300.0, t) // entered at 600 m: the first road reported
        offRoad(r, t)
        assertEquals(listOf("way:c"), r.queuedSegments())
        r.stop()
    }

    @Test
    fun aLongerZoneHoldsBackMoreOfTheTripAndItsEdgeCounts() {
        val r = started(draw = 1000)
        assertEquals(1_500.0, r.startZoneM, 0.0)
        var t = t0
        for (n in 1..5) t = drive(r, "way:$n", 300.0, t) // entered at 0, 300, 600, 900 and 1200 m
        t = drive(r, "way:6", 300.0, t) // entered at exactly 1500 m: the zone is behind the car
        offRoad(r, t)
        assertEquals(listOf("way:6"), r.queuedSegments())
        r.stop()
    }

    @Test
    fun theZonesAreDrawnPerTripBetween500And1500Metres() {
        val r = ProbeReporter(context, "test", TripPseudonym())
        val starts = HashSet<Double>()
        val ends = HashSet<Double>()
        repeat(300) {
            r.start(scope)
            assertTrue(r.startZoneM.toString(), r.startZoneM in 500.0..1_500.0)
            assertTrue(r.endZoneM.toString(), r.endZoneM in 500.0..1_500.0)
            starts.add(r.startZoneM)
            ends.add(r.endZoneM)
            r.stop()
        }
        // 300 draws from 1001 values: the same one every time would be the old fixed radius back.
        assertTrue(starts.size > 1)
        assertTrue(ends.size > 1)
        // Both ends of the range are reachable, and the end zone is drawn the same way.
        val shortest = started(draw = 0)
        val longest = started(draw = 1000)
        assertEquals(500.0, shortest.startZoneM, 0.0)
        assertEquals(500.0, shortest.endZoneM, 0.0)
        assertEquals(1_500.0, longest.startZoneM, 0.0)
        assertEquals(1_500.0, longest.endZoneM, 0.0)
        shortest.stop()
        longest.stop()
    }

    @Test
    fun theEndZoneDropsTheRoadBeingDrivenAndEverythingAfterIt() {
        val r = started() // both zones 500 m
        var t = drive(r, "way:a", 600.0, t0) // the start zone
        r.remainingToDestination(2_000.0) // far from the destination: no effect
        t = drive(r, "way:b", 300.0, t)
        t = drive(r, "way:c", 150.0, t) // finishes way:b; way:c is under way when the end zone begins
        assertEquals(listOf("way:b"), r.queuedSegments())
        r.remainingToDestination(500.0)
        t = drive(r, "way:d", 300.0, t)
        t = drive(r, "way:e", 300.0, t)
        offRoad(r, t)
        assertEquals(listOf("way:b"), r.queuedSegments())
        r.stop()
    }

    @Test
    fun aNewTripStartsItsZoneAfresh() {
        val r = started()
        var t = drive(r, "way:a", 600.0, t0)
        t = drive(r, "way:b", 300.0, t)
        offRoad(r, t)
        assertEquals(listOf("way:b"), r.queuedSegments())
        r.stop()
        r.start(scope)
        // The next drive begins on the road the last one reported from: it is the first road again.
        t = drive(r, "way:b", 600.0, t)
        t = drive(r, "way:c", 300.0, t)
        offRoad(r, t)
        assertEquals(listOf("way:c"), r.queuedSegments())
        r.stop()
    }

    @Test
    fun aReturnToTheStartRoadLaterInTheTripIsNotReported() {
        val r = started() // 500 m zone
        var t = drive(r, "home-street", 300.0, t0)
        t = drive(r, "main-road", 1_000.0, t) // entered at 300 m: inside the zone, never reported
        t = drive(r, "home-street", 300.0, t) // back on the start road at 1.3 km: still never reported
        t = drive(r, "away-road", 200.0, t) // entered at 1.6 km: the first reported road
        offRoad(r, t)
        assertEquals(listOf("away-road"), r.queuedSegments())
    }
}
