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

    /** A dim room with the given bright rectangles (x, y, width, height in pixels). */
    private fun frame(vararg spots: IntArray): ByteArray {
        val y = ByteArray(w * h) { 40 }
        for (s in spots) for (yy in s[1] until s[1] + s[3]) for (xx in s[0] until s[0] + s[2]) {
            if (xx in 0 until w && yy in 0 until h) y[yy * w + xx] = 250.toByte()
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
     * [torchOff] says whether the torch is currently off (the test switches it as asked).
     */
    private fun run(t: GlintTracker, frames: Int, scene: (i: Int, torchOff: Boolean) -> Array<IntArray>): Run {
        val run = Run()
        var torchOff = false
        for (i in 0 until frames) {
            val r = t.process(frame(*scene(i, torchOff)), w, h, w, 1, i * frameNs)
            run.results.add(r)
            r.torch?.let { torchOff = !it }
        }
        return run
    }

    @Test
    fun `a still reflection is tested with the torch off, confirmed when it goes dark, and reported`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        // A reflection: lit only while the torch is on.
        val r = run(t, 90) { _, off -> if (off) emptyArray() else arrayOf(spot(300, 200, 6)) }
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
    fun `a spot that drops out for a frame now and then keeps its place`() {
        val t = GlintTracker(ir = false, torchAvailable = true)
        val r = run(t, 90) { i, off -> if (off || i % 6 == 5) emptyArray() else arrayOf(spot(300, 200, 6)) }
        assertTrue(r.reported.isNotEmpty())
    }

    @Test
    fun `IR mode reports a still point of light without touching the torch`() {
        val t = GlintTracker(ir = true, torchAvailable = false)
        val r = run(t, 40) { _, _ -> arrayOf(spot(300, 200, 4)) }
        assertTrue(r.torchCalls.isEmpty())
        assertTrue(r.reported.isNotEmpty())
        assertEquals(GlintTracker.Kind.REFLECTION, r.reported.first().kind)
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
