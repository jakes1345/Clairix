package app.clairix.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * The lens-glint analyzer on the phone, fed synthetic camera frames in place of
 * the camera (the emulator has none): a YUV_420_888 luma plane with row padding,
 * as a real camera hands it over, through the analyzer's own frame path, its torch
 * switching and its mapping from the sensor to the screen. A pinhole that shines
 * back only while the torch is on is reported where it is on screen; a standby LED
 * that stays lit with the torch off is drawn grey and never reported; in the dark
 * room of the infrared pass, a still point of light on the mirrored front camera
 * is reported where the owner sees it.
 */
@RunWith(AndroidJUnit4::class)
class LensGlintAnalyzerDeviceTest {

    private val width = 640
    private val height = 480
    /** Rows padded to a 64-byte boundary, as camera HALs commonly hand them over. */
    private val rowStride = 704
    /** 30 fps, in nanoseconds. */
    private val frameNs = 33_333_333L

    /** A Y plane at [background] with a [size]-pixel bright square at ([x], [y]) when [spot]; the last row unpadded, as in a real plane. */
    private fun plane(background: Int, spot: Boolean, x: Int = 300, y: Int = 200, size: Int = 6): ByteBuffer {
        val bytes = ByteArray(rowStride * (height - 1) + width) { i -> if (i % rowStride < width) background.toByte() else 7 }
        if (spot) for (yy in y until y + size) for (xx in x until x + size) bytes[yy * rowStride + xx] = 250.toByte()
        return ByteBuffer.wrap(bytes)
    }

    /** What the analyzer reported and showed, and how it switched the torch. */
    private class Seen {
        val candidates = ArrayList<GlintBlob>()
        val states = ArrayList<OpticalState>()
        val torch = ArrayList<Boolean>()
    }

    /**
     * Runs [frames] frames of the glint pass on the back camera (sensor rotated 90°),
     * the torch lighting the room to 80 while it is on and leaving it at 40 when off.
     * [spotLit] says whether the bright point shows, given the torch.
     */
    private fun glintPass(frames: Int, spotLit: (torchOn: Boolean) -> Boolean): Seen {
        val seen = Seen()
        var torchOn = true
        val analyzer = LensGlintAnalyzer(
            mode = OpticalMode.GLINT, mirrored = false,
            torch = { on -> seen.torch += on; torchOn = on },
            onState = { seen.states += it },
            onCandidate = { seen.candidates += it }
        )
        analyzer.torchAvailable = true
        for (i in 0 until frames) {
            analyzer.torchReportedOn = torchOn
            val lit = torchOn
            analyzer.processLuma(plane(if (lit) 80 else 40, spotLit(lit)), width, height, rowStride, 1, i * frameNs, rotation = 90)
        }
        analyzer.close()
        return seen
    }

    @Test
    fun aPinholeThatShinesOnlyInTheTorchlightIsReportedWhereItIsOnScreen() {
        val seen = glintPass(120) { torchOn -> torchOn }
        // Off to test it, then on again.
        assertEquals(listOf(false, true), seen.torch.take(2))
        val lens = seen.candidates.firstOrNull { !it.lightSource }
        assertTrue("nothing reported: ${seen.states.lastOrNull()?.tracks}", lens != null)
        assertTrue(lens!!.candidate)
        assertTrue(lens.heldMs >= GlintTracker.HOLD_MS)
        // Sensor (303, 203) of 640 x 480, turned 90° onto a portrait screen.
        assertEquals(1f - 203f / height, lens.x, 0.06f)
        assertEquals(303f / width, lens.y, 0.06f)
        assertTrue(seen.states.last().running)
        assertTrue(seen.states.last().hasTorch)
    }

    @Test
    fun aStandbyLedThatStaysLitWithTheTorchOffIsGreyAndNeverReported() {
        val seen = glintPass(120) { true }
        assertTrue("a light source was reported: ${seen.candidates}", seen.candidates.isEmpty())
        assertTrue("the torch was never switched off to test it", false in seen.torch)
        // Once tested, it is drawn as a light: grey, not a candidate.
        assertTrue(
            "the LED was never drawn as a light: ${seen.states.last().tracks}",
            seen.states.any { s -> s.tracks.isNotEmpty() && s.tracks.all { it.lightSource && !it.candidate } }
        )
    }

    @Test
    fun anEmptyRoomReportsNothing() {
        val seen = glintPass(120) { false }
        assertTrue(seen.candidates.isEmpty())
        assertTrue(seen.states.last().tracks.isEmpty())
    }

    @Test
    fun theInfraredPassReportsAStillPointOfLightWhereTheOwnerSeesIt() {
        val candidates = ArrayList<GlintBlob>()
        val states = ArrayList<OpticalState>()
        val analyzer = LensGlintAnalyzer(
            mode = OpticalMode.IR, mirrored = true, torch = null,
            onState = { states += it }, onCandidate = { candidates += it }
        )
        for (i in 0 until 40) {
            analyzer.processLuma(plane(40, spot = true, size = 4), width, height, rowStride, 1, i * frameNs, rotation = 270)
        }
        analyzer.close()
        val dot = candidates.firstOrNull()
        assertTrue("nothing reported: ${states.lastOrNull()?.tracks}", dot != null)
        assertFalse(dot!!.lightSource)
        // Sensor (302, 202), turned 270° and mirrored as the front preview is.
        assertEquals(1f - 202f / height, dot.x, 0.06f)
        assertEquals(1f - 302f / width, dot.y, 0.06f)
        assertFalse("the infrared pass has no torch to switch", states.last().torchOn)
    }

    @Test
    fun aClosedAnalyzerReportsNothingMore() {
        val candidates = ArrayList<GlintBlob>()
        val analyzer = LensGlintAnalyzer(
            mode = OpticalMode.IR, mirrored = true, torch = null,
            onState = {}, onCandidate = { candidates += it }
        )
        analyzer.close()
        for (i in 0 until 40) analyzer.processLuma(plane(40, spot = true, size = 4), width, height, rowStride, 1, i * frameNs, rotation = 270)
        assertTrue(candidates.isEmpty())
    }
}
