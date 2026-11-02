package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IR self-test passes on a TV remote's flashing and on nothing else, and the
 * IR tab's words follow what it has recorded.
 */
class IrSelfTestTest {

    private val w = 640
    private val h = 480
    private val frameNs = 33_333_333L

    private fun frame(vararg rects: IntArray): ByteArray {
        val y = ByteArray(w * h) { 20 }
        for (s in rects) for (yy in s[1] until s[1] + s[3]) for (xx in s[0] until s[0] + s[2]) {
            if (xx in 0 until w && yy in 0 until h) y[yy * w + xx] = 240.toByte()
        }
        return y
    }

    /** Runs [frames] frames; [lit] says what is lit in frame i. Returns the frame it passed on, or -1. */
    private fun run(frames: Int, lit: (Int) -> Array<IntArray>): Int {
        val test = IrSelfTest()
        for (i in 0 until frames) {
            if (test.process(frame(*lit(i)), w, h, w, 1, i * frameNs)) return i
        }
        return -1
    }

    private val remote = intArrayOf(320, 240, 6, 6)

    @Test
    fun `a remote flashing at the front camera passes within a second`() {
        // A repeat code: one frame in three catches a burst.
        val passedAt = run(60) { i -> if (i % 3 == 0) arrayOf(remote) else emptyArray() }
        assertTrue("never passed", passedAt >= 0)
        assertTrue("took $passedAt frames", passedAt < 30)
    }

    @Test
    fun `a remote waved about while it flashes still passes`() {
        val passedAt = run(90) { i -> if (i % 2 == 0) arrayOf(intArrayOf(300 + (i % 10) * 3, 240, 6, 6)) else emptyArray() }
        assertTrue(passedAt >= 0)
    }

    @Test
    fun `a steady light never passes`() {
        assertEquals(-1, run(150) { arrayOf(remote) })
    }

    @Test
    fun `a light that blinks too slowly to be a remote does not pass`() {
        // On for a second, off for a second: four toggles in any two seconds, not five.
        assertEquals(-1, run(180) { i -> if ((i / 30) % 2 == 0) arrayOf(remote) else emptyArray() })
    }

    @Test
    fun `a single noisy pixel is not a remote`() {
        assertEquals(-1, run(90) { i -> if (i % 2 == 0) arrayOf(intArrayOf(100, 100, 1, 1)) else emptyArray() })
    }

    @Test
    fun `a large area flickering is the scene changing, not a remote`() {
        assertEquals(-1, run(90) { i -> if (i % 2 == 0) arrayOf(intArrayOf(0, 0, 320, 240)) else emptyArray() })
    }

    @Test
    fun `once passed it stays passed`() {
        val test = IrSelfTest()
        var passed = false
        for (i in 0 until 30) {
            val lit: Array<IntArray> = if (i % 2 == 0) arrayOf(remote) else emptyArray()
            passed = test.process(frame(*lit), w, h, w, 1, i * frameNs) || passed
        }
        assertTrue(passed)
        assertTrue(test.process(frame(), w, h, w, 1, 31 * frameNs))
        assertTrue(test.passed)
    }

    @Test
    fun `the IR tab says an empty result means nothing until the phone has shown infrared`() {
        assertEquals(
            "This phone has not shown it can see infrared. No steady points of light recorded yet.",
            HiddenCameraScanner.irEmptyText(IrCheck())
        )
        assertEquals(
            "This phone has not shown it can see infrared. No steady points of light recorded yet.",
            HiddenCameraScanner.irEmptyText(IrCheck(failedTs = 5L))
        )
        assertEquals("No steady points of light recorded yet", HiddenCameraScanner.irEmptyText(IrCheck(passedTs = 5L)))
        assertTrue(HiddenCameraScanner.irNotice(IrCheck())!!.contains("TV remote"))
        assertTrue(HiddenCameraScanner.irNotice(IrCheck(failedTs = 5L))!!.contains("saw no remote flashing"))
        // A pass is a pass, whatever failed before it.
        assertEquals(null, HiddenCameraScanner.irNotice(IrCheck(passedTs = 9L, failedTs = 5L)))
        assertFalse(IrCheck().seen)
        assertTrue(IrCheck(passedTs = 1L).seen)
    }
}
