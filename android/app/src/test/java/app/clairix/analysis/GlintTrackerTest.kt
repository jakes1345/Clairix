package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The optical sweep's detector against synthetic camera frames. */
class GlintTrackerTest {

    private val w = 640
    private val h = 480
    /** 30 fps, in nanoseconds. */
    private val frameNs = 33_333_333L

    /** The room at [background] luma with the given bright rectangles (x, y, width, height in pixels). */
    private fun frame(vararg spots: IntArray, background: Int = 40, width: Int = w, height: Int = h): ByteArray {
        val y = ByteArray(width * height) { background.toByte() }
        for (s in spots) for (yy in s[1] until s[1] + s[3]) for (xx in s[0] until s[0] + s[2]) {
            if (xx in 0 until width && yy in 0 until height) y[yy * width + xx] = 250.toByte()
        }
        return y
    }

    private fun spot(x: Int, y: Int, size: Int) = intArrayOf(x, y, size, size)

    private class Run {
        val results = ArrayList<GlintTracker.Result>()
        val reported get() = results.flatMap { it.report }
        val torchCalls get() = results.mapNotNull { it.torch }
    }

    /**
     * Feeds [frames] frames to [t]. [scene] gives the frame's spots for frame index i;
     * [lit] says whether the torch is lighting the scene right now. The torch goes dark
     * [latencyFrames] frames after it is asked off (the phone's own lag) and lights the
     * room's background to [torchBackground] while it is on. [confirm] passes the
     * camera's confirmation that the torch is off, once it physically is.
     */
    private fun run(
        t: GlintTracker,
        frames: Int,
        latencyFrames: Int = 0,
        torchBackground: Int = 80,
        darkBackground: Int = 40,
        confirm: Boolean = false,
        scene: (i: Int, lit: Boolean) -> Array<IntArray>
    ): Run {
        val run = Run()
        var asked = true
        var askedOffAt = -1
        for (i in 0 until frames) {
            val lit = asked || (askedOffAt >= 0 && i - askedOffAt <= latencyFrames)
            val bg = if (lit) torchBackground else darkBackground
            val r = t.process(frame(*scene(i, lit), background = bg), w, h, w, 1, i * frameNs, torchOffConfirmed = confirm && !lit)
            run.results.add(r)
            r.torch?.let { on ->
                asked = on
                askedOffAt = if (on) -1 else i
            }
        }
        return run
    }

    @Test
    fun `a still reflection is tested with the torch off, confirmed when it goes dark, and reported`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // A reflection: lit only while the torch is.
        val r = run(t, 90) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertEquals(listOf(false, true), r.torchCalls)
        val first = r.reported.first()
        assertEquals(GlintTracker.Kind.REFLECTION, first.kind)
        assertTrue(first.candidate)
        assertTrue(first.heldMs >= GlintTracker.HOLD_MS)
        // Confidence climbs as it keeps holding.
        assertTrue(r.reported.last().confidence > first.confidence)
    }

    @Test
    fun `a light that stays lit with the torch off is a light source and never reported`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = run(t, 120) { _, _ -> arrayOf(spot(300, 200, 6)) }
        assertTrue(r.reported.isEmpty())
        val last = r.results.last().spots.single()
        assertEquals(GlintTracker.Kind.LIGHT_SOURCE, last.kind)
        assertFalse(last.candidate)
        assertEquals(0f, last.confidence)
    }

    @Test
    fun `a torch slow to go dark (300 ms) still reads a lens as a reflection`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // The spot and the room stay lit for the first 9 frames after the torch is asked off.
        val r = run(t, 120, latencyFrames = 9) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertTrue(r.reported.isNotEmpty())
        assertEquals(GlintTracker.Kind.REFLECTION, r.reported.first().kind)
    }

    @Test
    fun `a torch slower than a fixed window allowed (450 ms) still reads a lens as a reflection`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // A window fixed at 200-450 ms after asking would see it lit in nearly every frame.
        val r = run(t, 120, latencyFrames = 14) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertTrue(r.reported.isNotEmpty())
        assertEquals(GlintTracker.Kind.REFLECTION, r.reported.first().kind)
        assertTrue(r.results.none { res -> res.spots.any { it.kind == GlintTracker.Kind.LIGHT_SOURCE } })
    }

    @Test
    fun `a torch never seen to go off decides nothing and is tried again later`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // Bright room, the torch's light lost in it, and no word from the camera: nothing changes.
        val r = run(t, 240, latencyFrames = 1_000, torchBackground = 80, darkBackground = 80) { _, _ -> arrayOf(spot(300, 200, 6)) }
        assertTrue(r.reported.isEmpty())
        assertTrue(r.results.none { res -> res.spots.any { it.kind == GlintTracker.Kind.LIGHT_SOURCE || it.kind == GlintTracker.Kind.REFLECTION } })
        // Asked off, given back after the timeout, then asked again a retest period later.
        val calls = r.results.withIndex().filter { it.value.torch != null }
        assertEquals(listOf(false, true, false, true), calls.take(4).map { it.value.torch })
        val firstOff = calls[0].index
        val putBack = calls[1].index
        val again = calls[2].index
        assertTrue((putBack - firstOff) * frameNs / 1_000_000L > GlintTracker.VERIFY_TIMEOUT_MS)
        assertTrue((again - putBack) * frameNs / 1_000_000L >= GlintTracker.RETEST_MS)
        assertTrue(r.results[putBack].torchUnseen)
    }

    @Test
    fun `the camera's confirmation opens the window when the room does not darken`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // A lit room: the torch makes no visible difference to the mean, but the camera confirms it.
        val r = run(t, 90, torchBackground = 80, darkBackground = 80, confirm = true) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertEquals(listOf(false, true), r.torchCalls)
        assertEquals(GlintTracker.Kind.REFLECTION, r.reported.first().kind)
    }

    @Test
    fun `a light source is tested again after holding on it`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = run(t, 240) { _, _ -> arrayOf(spot(300, 200, 6)) }
        assertTrue(r.reported.isEmpty())
        // First test at about 0.7 s, decided as a light; tried again about four seconds later.
        assertEquals(2, r.torchCalls.count { !it })
    }

    @Test
    fun `without a torch the glint mode tracks nothing and never asks for it`() {
        val t = GlintTracker(ir = false, torchAvailable = false)
        val r = run(t, 90) { _, _ -> arrayOf(spot(300, 200, 6)) }
        assertTrue(r.torchCalls.isEmpty())
        assertTrue(r.reported.isEmpty())
        assertTrue(r.results.all { it.spots.isEmpty() && !it.checking })
    }

    @Test
    fun `a torch that turns up after the first frames is used from then on`() {
        val t = GlintTracker(ir = false, torchAvailable = false)
        val before = run(t, 10) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertTrue(before.results.all { it.spots.isEmpty() })
        t.torchAvailable = true
        val after = run(t, 90) { _, lit -> if (lit) arrayOf(spot(300, 200, 6)) else emptyArray() }
        assertEquals(listOf(false, true), after.torchCalls)
        assertTrue(after.reported.isNotEmpty())
    }

    @Test
    fun `a highlight sliding slowly across the frame never counts as still`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // 2 px a frame: well under a cell per frame, so the old per-frame test let it through.
        val r = run(t, 150) { i, _ -> arrayOf(spot(100 + i * 2, 200, 6)) }
        assertTrue(r.torchCalls.isEmpty())
        assertTrue(r.reported.isEmpty())
        assertTrue(r.results.last().spots.all { it.heldMs < GlintTracker.HOLD_MS })
    }

    @Test
    fun `a long thin streak is not a lens`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = t.process(frame(intArrayOf(100, 200, 120, 4), intArrayOf(300, 100, 6, 100)), w, h, w, 1, 0L)
        assertTrue(r.spots.isEmpty())
    }

    @Test
    fun `a pinhole glint a few pixels across is found wherever it falls on the grid`() {
        for (offset in 0 until GlintTracker.SCALE) {
            val t = GlintTracker(ir = false, torchAvailable = true)
            val r = t.process(frame(spot(301 + offset, 201 + offset, 2)), w, h, w, 1, 0L)
            assertEquals("offset $offset", 1, r.spots.size)
        }
    }

    @Test
    fun `at 1280x960 the grid keeps its size and a two-pixel glint is still found`() {
        assertEquals(8, GlintTracker.cellSize(1280))
        assertEquals(GlintTracker.SCALE, GlintTracker.cellSize(640))
        for (offset in 0 until 8) {
            val t = GlintTracker(ir = false, torchAvailable = true)
            val r = t.process(frame(spot(601 + offset, 401 + offset, 2), width = 1280, height = 960), 1280, 960, 1280, 1, 0L)
            assertEquals("offset $offset", 1, r.spots.size)
            assertEquals(GlintTracker.GRID_COLUMNS, r.gridWidth)
        }
    }

    @Test
    fun `a spot that drops out for a frame now and then keeps its place`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = run(t, 90) { i, lit -> if (!lit || i % 6 == 5) emptyArray() else arrayOf(spot(300, 200, 6)) }
        assertTrue(r.reported.isNotEmpty())
    }

    @Test
    fun `IR mode reports a still point of light without touching the torch`() {
        val t = GlintTracker(ir = true, torchAvailable = false)
        val r = run(t, 40, torchBackground = 40) { _, _ -> arrayOf(spot(300, 200, 4)) }
        assertTrue(r.torchCalls.isEmpty())
        assertTrue(r.reported.isNotEmpty())
        assertEquals(GlintTracker.Kind.REFLECTION, r.reported.first().kind)
    }

    @Test
    fun `a spot that stays put on the sensor while the phone turns is a hot pixel, never reported`() {
        val t = GlintTracker(ir = true, torchAvailable = false)
        val reported = ArrayList<GlintTracker.Spot>()
        var last: GlintTracker.Result? = null
        for (i in 0 until 60) {
            // The phone turns 0.6 degrees a frame; a point in the room would slide across the view.
            val r = t.process(frame(spot(300, 200, 2)), w, h, w, 1, i * frameNs, turnXDeg = 0.6f)
            reported += r.report
            last = r
        }
        assertTrue(reported.isEmpty())
        assertEquals(GlintTracker.Kind.FIXED, last!!.spots.single().kind)
        assertEquals(0f, last.spots.single().confidence)
    }

    @Test
    fun `hand tremor that cancels out does not make a real spot a hot pixel`() {
        val t = GlintTracker(ir = true, torchAvailable = false)
        val reported = ArrayList<GlintTracker.Spot>()
        for (i in 0 until 60) {
            // Back and forth: a lot of turning in total, almost none net.
            val turn = if (i % 2 == 0) 0.6f else -0.6f
            reported += t.process(frame(spot(300, 200, 4)), w, h, w, 1, i * frameNs, turnXDeg = turn, turnYDeg = -turn).report
        }
        assertTrue(reported.isNotEmpty())
    }

    @Test
    fun `holding time is camera time, so a slow camera waits just as long`() {
        val t = GlintTracker(ir = true, torchAvailable = false)
        // 10 fps: 0.7 s is 7 frames, not 20.
        var reportedAt = -1
        for (i in 0 until 20) {
            val r = t.process(frame(spot(300, 200, 4)), w, h, w, 1, i * 100_000_000L)
            if (r.report.isNotEmpty() && reportedAt < 0) reportedAt = i
        }
        assertEquals(7, reportedAt)
    }

    @Test
    fun `a washed-out frame finds nothing`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = t.process(frame(intArrayOf(0, 0, w, h / 3)), w, h, w, 1, 0L)
        assertTrue(r.washedOut)
        assertTrue(r.spots.isEmpty())
        assertNull(r.torch)
    }
}
