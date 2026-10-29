package app.clairix.analysis

import app.clairix.analysis.MotionPolicy.Pace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPolicyTest {

    private val lat0 = 51.5
    private val lon0 = -0.12
    /** About 1.1 m of latitude per 1e-5 degree. */
    private fun fix(northM: Double, t: Long, speed: Float? = null, accuracy: Float? = 10f) =
        Fix(lat0 + northM / 111_195.0, lon0, speed, t, accuracy)

    private val min = 60_000L

    @Test
    fun `a phone that stops settles after four minutes and not before`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0))
        assertNull(p.tick(3 * min))
        assertEquals(Pace.MOVING, p.pace)
        assertEquals(Pace.STILL, p.tick(4 * min))
        assertNull(p.tick(5 * min))
    }

    @Test
    fun `a fix at walking speed ends a still spell`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0))
        p.tick(5 * min)
        assertEquals(Pace.MOVING, p.onFix(fix(5.0, 6 * min, speed = 1.4f)))
        // And the clock restarts from that fix.
        assertNull(p.tick(9 * min))
        assertEquals(Pace.STILL, p.tick(10 * min))
    }

    @Test
    fun `GPS jitter at rest is not movement`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0))
        for (i in 1..20) assertNull(p.onFix(fix(if (i % 2 == 0) 30.0 else -30.0, i * 15_000L, speed = 0.3f)))
        assertEquals(Pace.STILL, p.tick(5 * min))
    }

    @Test
    fun `a coarse fix must move further than its own error`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0, accuracy = 20f))
        p.tick(5 * min)
        // A cell fix 300 m off, good to 400 m: still where it was.
        assertNull(p.onFix(fix(300.0, 6 * min, accuracy = 400f)))
        assertEquals(Pace.STILL, p.pace)
        // A Wi-Fi fix 150 m away, good to 25 m: it has gone somewhere.
        assertEquals(Pace.MOVING, p.onFix(fix(150.0, 7 * min, accuracy = 25f)))
    }

    @Test
    fun `the motion sensor wakes it at once`() {
        val p = MotionPolicy()
        p.start(0)
        p.tick(5 * min)
        assertEquals(Pace.MOVING, p.onMotion(6 * min))
        // Already moving: nothing to switch, but the clock restarts from the latest trigger.
        assertNull(p.onMotion(6 * min + 1))
        assertNull(p.tick(10 * min))
        assertEquals(Pace.STILL, p.tick(10 * min + 1))
    }

    @Test
    fun `settled counts as standing still only with a recent fix to show it`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0))
        p.tick(5 * min)
        assertEquals(Pace.STILL, p.pace)
        // The fix that showed it is five minutes old: still evidence.
        assertEquals(true, p.confirmedStill(5 * min))
        // Ten minutes on with no fix at all (underground): no longer evidence of anything.
        assertEquals(false, p.confirmedStill(10 * min))
        // A settled fix in the same place restores it.
        p.onFix(fix(10.0, 11 * min, accuracy = 30f))
        assertEquals(true, p.confirmedStill(11 * min))
    }

    @Test
    fun `moving is never standing still`() {
        val p = MotionPolicy()
        p.start(0)
        p.onFix(fix(0.0, 0))
        assertEquals(false, p.confirmedStill(1 * min))
    }
}
