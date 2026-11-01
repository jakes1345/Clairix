package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a device was heard, and whether the phone itself travelled between those
 * places: a bad or wandering fix while the phone sits still must not read as a journey.
 */
class GeoTest {

    private val t0 = 1_700_000_000_000L

    /** About 1.1 m of latitude per 1e-5 degree. */
    private fun fix(northM: Double, at: Long, speed: Float? = null, accuracy: Float? = 10f) =
        Fix(51.5 + northM / 111_195.0, -0.12, speed, at, accuracy)

    @Test
    fun aStaleOrCoarseFixPlacesNothing() {
        val area = ObservationArea()
        area.add(fix(0.0, t0), now = t0 + MAX_FIX_AGE_MS + 1, travelledM = 0.0)
        area.add(fix(0.0, t0, accuracy = 80f), now = t0, travelledM = 0.0)
        assertEquals(0, area.size)
        area.add(fix(0.0, t0), now = t0 + MAX_FIX_AGE_MS, travelledM = 0.0)
        assertEquals(1, area.size)
    }

    @Test
    fun oneOutlierFixWhileStillDoesNotWidenTheSpan() {
        // Twelve minutes at home, a fix every fifteen seconds and the device heard under
        // each; then one optimistic network fix 400 m away, current for one interval,
        // with the device heard ten times under it; then home again.
        val area = ObservationArea()
        val track = LocationTrack()
        var t = t0
        fun at(northM: Double, packets: Int) {
            val f = fix(northM, t)
            track.update(f)
            repeat(packets) { area.add(f, t + it * 1_000L, track.travelledM) }
            t += 15_000
        }
        repeat(48) { at(0.0, 5) }
        at(400.0, 10)
        repeat(8) { at(0.0, 5) }
        // The outlier is remembered as a place, as evidence; it proves nothing.
        assertEquals(2, area.size)
        assertEquals(0.0, area.span(), 0.0)
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun aFlappingFixHeardUnderMoreThanOneFixIsStillNotTravel() {
        // The phone never moves; its position source alternates between the true spot
        // and one 400 m away, so the far place is heard under several fixes.
        val area = ObservationArea()
        val track = LocationTrack()
        var t = t0
        repeat(30) { i ->
            val f = fix(if (i % 5 == 4) 400.0 else 0.0, t)
            track.update(f)
            repeat(3) { area.add(f, t + it * 1_000L, track.travelledM) }
            t += 15_000
        }
        assertEquals(2, area.size)
        assertEquals(0.0, area.span(), 0.0)
    }

    @Test
    fun aWalkWithFixesEveryTwentyFiveMetresIsTravel() {
        // A fix every 25 m at walking pace, the device heard twice under each.
        val area = ObservationArea()
        val track = LocationTrack()
        var t = t0
        for (m in 0..400 step 25) {
            val f = fix(m.toDouble(), t, speed = 1.4f)
            track.update(f)
            area.add(f, t, track.travelledM)
            area.add(f, t + 9_000, track.travelledM)
            t += 18_000
        }
        assertTrue("span ${area.span()}", area.span() >= 300.0)
        // The odometer runs one fix behind: the last 25 m wait for the next fix.
        assertEquals(375.0, track.travelledM, 1.0)
    }

    @Test
    fun aDriveIsCreditedOneFixLate() {
        val track = LocationTrack()
        for (i in 0..3) track.update(fix(i * 300.0, t0 + i * 15_000L, speed = 20f))
        assertEquals(600.0, track.travelledM, 1.0)
    }

    @Test
    fun aSpikeIsNotTravel() {
        val track = LocationTrack()
        track.update(fix(0.0, t0))
        track.update(fix(400.0, t0 + 15_000))
        track.update(fix(0.0, t0 + 30_000))
        track.update(fix(0.0, t0 + 45_000))
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun aLongJumpWithoutSpeedIsNotTravel() {
        // Indoors, a Wi-Fi position moves 400 m between two fixes that report no speed
        // and stays there: the position source changed its mind, the phone did not move.
        val track = LocationTrack()
        track.update(fix(0.0, t0))
        track.update(fix(400.0, t0 + 180_000))
        track.update(fix(410.0, t0 + 360_000))
        track.update(fix(405.0, t0 + 540_000))
        assertTrue("travelled ${track.travelledM}", track.travelledM < 50.0)
    }

    @Test
    fun aWanderingWifiPositionOnAStillPhoneIsNotTravel() {
        // A settled phone gets a Wi-Fi fix every three minutes, and each lands 30 m on
        // from the last, never coming back: an hour of that is 570 m of wandering.
        val track = LocationTrack()
        for (i in 0 until 20) track.update(fix(i * 30.0, t0 + i * 180_000L, speed = null, accuracy = 30f))
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun satelliteJitterOnAPhoneAtRestIsNotTravel() {
        // Satellite fixes every ten seconds scatter by up to 15 m, but their speed,
        // measured from the signal rather than the position, says the phone is at rest.
        val track = LocationTrack()
        JITTER_M.forEachIndexed { i, m -> track.update(fix(m, t0 + i * 10_000L, speed = 0.2f, accuracy = 8f)) }
        assertEquals(0.0, track.travelledM, 0.0)
    }

    @Test
    fun aBadNetworkFixInTheMiddleOfAWalkAddsNothing() {
        // A walk at 1.3 m/s, a fix every 20 m; in the middle one network fix lands 400 m
        // off and the next is back on the path.
        val track = LocationTrack()
        var t = t0
        for (i in 0..4) { track.update(fix(i * 20.0, t, speed = 1.3f, accuracy = 8f)); t += 15_000 }
        track.update(fix(480.0, t, speed = null, accuracy = 40f)); t += 15_000
        for (i in 5..9) { track.update(fix(i * 20.0, t, speed = 1.3f, accuracy = 8f)); t += 15_000 }
        // The 180 m walked, less the last 20 m, which wait for the next fix.
        assertEquals(160.0, track.travelledM, 1.0)
    }

    @Test
    fun mergingTwoHistoriesJoinsTheirPlaces() {
        // One device under two addresses: the first heard over the first half of a walk,
        // the second over the rest. Neither half spans 300 m; together they do.
        val track = LocationTrack()
        val first = ObservationArea()
        val second = ObservationArea()
        var t = t0
        for (m in 0..400 step 25) {
            val f = fix(m.toDouble(), t, speed = 1.4f)
            track.update(f)
            val area = if (m <= 200) first else second
            area.add(f, t, track.travelledM)
            area.add(f, t + 9_000, track.travelledM)
            t += 18_000
        }
        assertTrue(first.span() < 300.0)
        assertTrue(second.span() < 300.0)
        first.merge(second)
        assertTrue("span ${first.span()}", first.span() >= 300.0)
    }

    private companion object {
        val JITTER_M = doubleArrayOf(0.0, 12.0, -9.0, 14.0, -15.0, 7.0, -3.0, 11.0, -12.0, 5.0, 15.0, -6.0, 9.0, -14.0, 2.0)
    }
}
