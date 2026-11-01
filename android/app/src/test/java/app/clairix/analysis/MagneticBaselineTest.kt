package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The magnetic gauge's verdict: a magnet slid past is one episode and leaves the
 * baseline alone; a step that stays (a steel stud, a calibration jump) decays
 * instead of latching; coming back off it is not a new find; a calibration change
 * re-seeds; and while the compass calls itself unreliable there is no verdict.
 */
class MagneticBaselineTest {

    /** 50 Hz, as SENSOR_DELAY_GAME roughly delivers. */
    private val stepMs = 20L

    private class Run(val readings: List<MagneticBaseline.Reading>) {
        val episodes get() = readings.count { it.started }
        val last get() = readings.last()
    }

    /** Feeds [samples] samples of [field] µT from [startMs]; returns them and the next time. */
    private fun feed(m: MagneticBaseline, field: Float, samples: Int, startMs: Long): Pair<Run, Long> {
        val out = ArrayList<MagneticBaseline.Reading>(samples)
        var t = startMs
        repeat(samples) { out.add(m.sample(field, t)); t += stepMs }
        return Run(out) to t
    }

    /** A settled gauge at [field] µT, and the time it is at. */
    private fun settled(field: Float = 50f): Pair<MagneticBaseline, Long> {
        val m = MagneticBaseline()
        m.accuracyChanged(3)
        val (run, t) = feed(m, field, 200, 0L)
        assertFalse(run.last.warmingUp)
        assertEquals(0, run.episodes)
        return m to t
    }

    @Test
    fun `a magnet slid past is one episode and leaves the baseline where it was`() {
        val (m, t0) = settled(50f)
        val (over, t1) = feed(m, 120f, 50, t0)          // one second over the magnet
        assertEquals(1, over.episodes)
        assertTrue(over.last.triggered)
        val (after, _) = feed(m, 50f, 100, t1)
        assertFalse(after.last.triggered)
        assertEquals(0, after.episodes)
        assertEquals(50f, after.last.baselineUt, 0.01f)
    }

    @Test
    fun `a step that stays decays back to quiet instead of latching`() {
        val (m, t0) = settled(50f)
        // Slid onto a steel stud: the field moves to a new level and stays there.
        val (onStud, _) = feed(m, 100f, 15 * 50, t0)     // fifteen seconds
        assertEquals(1, onStud.episodes)
        assertTrue(onStud.readings.any { it.triggered })
        assertFalse("still saying Anomaly after 15 s on a stud", onStud.last.triggered)
        assertEquals(100f, onStud.last.baselineUt, 9f)
        // It does not keep re-triggering at the new level.
        assertEquals(1, onStud.readings.count { it.started })
    }

    @Test
    fun `coming back off a learned step is not a new anomaly`() {
        val (m, t0) = settled(50f)
        val (_, t1) = feed(m, 100f, 15 * 50, t0)
        val (back, _) = feed(m, 50f, 200, t1)
        assertEquals(0, back.episodes)
        assertFalse(back.last.triggered)
        assertEquals(50f, back.last.baselineUt, 2f)
    }

    @Test
    fun `a jump at an accuracy change is learned, not reported`() {
        val (m, t0) = settled(50f)
        // The platform re-estimates the sensor and the readings move 30 µT at the same moment.
        m.accuracyChanged(2)
        val (after, _) = feed(m, 80f, 300, t0)
        assertTrue(after.readings.first().warmingUp)
        assertEquals(0, after.episodes)
        assertFalse(after.last.triggered)
        assertEquals(80f, after.last.baselineUt, 0.5f)
    }

    @Test
    fun `without the re-seed the same jump would have been an anomaly`() {
        val (m, t0) = settled(50f)
        val (after, _) = feed(m, 80f, 50, t0)
        assertEquals(1, after.episodes)
    }

    @Test
    fun `an accuracy report that does not change anything does not re-seed`() {
        val (m, t0) = settled(50f)
        m.accuracyChanged(3)
        val (next, _) = feed(m, 50f, 1, t0)
        assertFalse(next.last.warmingUp)
    }

    @Test
    fun `while the compass is unreliable there is no verdict, and it settles afresh after`() {
        val (m, t0) = settled(50f)
        m.accuracyChanged(MagneticBaseline.ACCURACY_UNRELIABLE)
        assertTrue(m.unreliable)
        val (paused, t1) = feed(m, 200f, 200, t0)
        assertEquals(0, paused.episodes)
        assertTrue(paused.readings.all { it.unreliable && !it.triggered && !it.warmingUp })
        m.accuracyChanged(3)
        assertFalse(m.unreliable)
        val (resumed, _) = feed(m, 200f, 300, t1)
        assertTrue(resumed.readings.first().warmingUp)
        assertEquals(0, resumed.episodes)
        assertEquals(200f, resumed.last.baselineUt, 0.5f)
    }

    @Test
    fun `an episode in progress ends when the compass turns unreliable`() {
        val (m, t0) = settled(50f)
        val (over, t1) = feed(m, 120f, 30, t0)
        assertTrue(over.last.triggered)
        m.accuracyChanged(MagneticBaseline.ACCURACY_UNRELIABLE)
        val (paused, _) = feed(m, 120f, 1, t1)
        assertFalse(paused.last.triggered)
    }

    @Test
    fun `a peak that grows is flagged so the finding can be refreshed`() {
        val (m, t0) = settled(50f)
        val (first, t1) = feed(m, 90f, 30, t0)
        assertEquals(1, first.episodes)
        val (closer, _) = feed(m, 150f, 5, t1)
        assertTrue(closer.readings.any { it.peakGrew })
        assertEquals(100f, closer.last.peakUt, 0.5f)
    }

    @Test
    fun `the unreliable constant is the platform's`() {
        assertEquals(android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE, MagneticBaseline.ACCURACY_UNRELIABLE)
    }
}
