package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The magnetic gauge's input from the uncalibrated stream: turning the phone does
 * not move it (the hard-iron offset turns with the phone, the room's field does
 * not), and the platform re-estimating the offset does not step it.
 */
class HardIronHoldTest {

    /** The room's field in the world frame, µT: a mid-latitude Earth field. */
    private val earth = floatArrayOf(20f, 0f, -40f)

    /** A hard-iron offset of the size phones carry, fixed in the phone's frame. */
    private val offset = floatArrayOf(80f, -60f, 120f)

    private fun length(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** [v] turned by [yawDeg] about z, then [pitchDeg] about x: the room's field as a turned phone sees it. */
    private fun turned(v: FloatArray, yawDeg: Double, pitchDeg: Double): FloatArray {
        val a = Math.toRadians(yawDeg)
        val b = Math.toRadians(pitchDeg)
        val x1 = (v[0] * cos(a) - v[1] * sin(a)).toFloat()
        val y1 = (v[0] * sin(a) + v[1] * cos(a)).toFloat()
        val z1 = v[2]
        return floatArrayOf(x1, (y1 * cos(b) - z1 * sin(b)).toFloat(), (y1 * sin(b) + z1 * cos(b)).toFloat())
    }

    /** An uncalibrated event's values: raw field (room + offset), then the platform's offset estimate. */
    private fun event(field: FloatArray, estimate: FloatArray = offset) = floatArrayOf(
        field[0] + offset[0], field[1] + offset[1], field[2] + offset[2], estimate[0], estimate[1], estimate[2]
    )

    private val poses = (0 until 360 step 15).flatMap { yaw -> listOf(-60, 0, 45, 90).map { pitch -> yaw.toDouble() to pitch.toDouble() } }

    @Test
    fun `turning the phone does not move the reading`() {
        val hold = HardIronHold()
        for ((yaw, pitch) in poses) {
            assertEquals(length(earth), hold.magnitude(event(turned(earth, yaw, pitch))), 0.01f)
        }
    }

    @Test
    fun `the raw magnitude alone would swing far past the trigger as the phone turns`() {
        val raw = poses.map { (yaw, pitch) -> event(turned(earth, yaw, pitch)).let { length(it) } }
        assertTrue(raw.max() - raw.min() > MagneticBaseline.MIN_DELTA_UT * 3)
    }

    @Test
    fun `a sweep that turns the phone raises no episode`() {
        val hold = HardIronHold()
        val gauge = MagneticBaseline()
        gauge.accuracyChanged(3)
        var t = 0L
        var episodes = 0
        // Settle, then turn slowly through every pose and back, as a hand does along a wall.
        repeat(200) { gauge.sample(hold.magnitude(event(earth)), t); t += 20L }
        for ((yaw, pitch) in poses + poses.reversed()) {
            repeat(5) {
                if (gauge.sample(hold.magnitude(event(turned(earth, yaw, pitch))), t).started) episodes++
                t += 20L
            }
        }
        assertEquals(0, episodes)
    }

    @Test
    fun `a re-estimate of the offset does not step the reading`() {
        val hold = HardIronHold()
        val before = hold.magnitude(event(earth))
        val moved = floatArrayOf(offset[0] + 30f, offset[1] - 25f, offset[2] + 20f)
        assertEquals(before, hold.magnitude(event(earth, estimate = moved)), 0.001f)
    }

    @Test
    fun `after a release the new estimate is held`() {
        val hold = HardIronHold()
        hold.magnitude(event(earth))
        val wrong = floatArrayOf(offset[0] + 30f, offset[1], offset[2])
        hold.release()
        // Held from here: raw minus the new estimate, which is 30 µT off along x.
        val expected = length(floatArrayOf(earth[0] - 30f, earth[1], earth[2]))
        assertEquals(expected, hold.magnitude(event(earth, estimate = wrong)), 0.01f)
        assertEquals(expected, hold.magnitude(event(earth, estimate = offset)), 0.01f)
    }

    @Test
    fun `the calibrated stream is used as it is`() {
        assertEquals(length(earth), HardIronHold().magnitude(earth.copyOf()), 0.001f)
    }

    @Test
    fun `only a real change of accuracy asks for a fresh estimate`() {
        val gauge = MagneticBaseline()
        assertTrue(gauge.accuracyChanged(3))
        assertEquals(false, gauge.accuracyChanged(3))
        assertTrue(gauge.accuracyChanged(2))
    }
}
