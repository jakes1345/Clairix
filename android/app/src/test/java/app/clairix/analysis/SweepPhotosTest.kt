package app.clairix.analysis

import app.clairix.TestStoreCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/**
 * A kept photo is sealed on disk, opens again with the store's cipher, lives only
 * under files/sweep/, and carries its own time, place and position in the frame.
 */
class SweepPhotosTest {

    private val filesDir: File = Files.createTempDirectory("files").toFile().apply { deleteOnExit() }

    /** The first bytes of every JPEG, followed by something photo-like. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5_000) { (it % 251).toByte() }

    @Test
    fun `a kept photo is sealed on disk and opens back to the same bytes`() {
        val name = SweepPhotos.save(filesDir, jpeg, TestStoreCipher)
        assertTrue(name, name.startsWith("sweep/") && name.endsWith(".clxp"))
        val onDisk = File(filesDir, name).readBytes()
        // Not a JPEG on disk: anything that reads the file without the key sees ciphertext.
        assertFalse(onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        assertArrayEquals(jpeg, SweepPhotos.open(filesDir, name, TestStoreCipher))
        // No temporary file is left behind.
        assertEquals(1, File(filesDir, SweepPhotos.DIR).listFiles()!!.size)
    }

    @Test
    fun `two photos never share a file`() {
        assertFalse(SweepPhotos.save(filesDir, jpeg, TestStoreCipher) == SweepPhotos.save(filesDir, jpeg, TestStoreCipher))
    }

    @Test
    fun `deleting a photo removes its file and leaves everything else alone`() {
        val name = SweepPhotos.save(filesDir, jpeg, TestStoreCipher)
        val other = File(filesDir, "timeline.json").apply { writeText("{}") }
        SweepPhotos.delete(filesDir, listOf(name, "timeline.json", "sweep/../timeline.json"))
        assertNull(SweepPhotos.open(filesDir, name, TestStoreCipher))
        assertTrue(other.exists())
    }

    @Test
    fun `clear all data deletes every photo, named or not, and nothing else`() {
        val a = SweepPhotos.save(filesDir, jpeg, TestStoreCipher)
        val b = SweepPhotos.save(filesDir, jpeg, TestStoreCipher)
        val dir = File(filesDir, SweepPhotos.DIR)
        val halfWritten = File(dir, "0123456789abcdef0123456789abcdef.clxp.tmp").apply { writeBytes(jpeg) }
        val notOurs = File(dir, "notes.txt").apply { writeText("x") }
        val timeline = File(filesDir, "timeline.json").apply { writeText("{}") }
        assertEquals(3, SweepPhotos.deleteAll(filesDir))
        assertNull(SweepPhotos.open(filesDir, a, TestStoreCipher))
        assertNull(SweepPhotos.open(filesDir, b, TestStoreCipher))
        assertFalse(halfWritten.exists())
        assertTrue(notOurs.exists())
        assertTrue(timeline.exists())
        assertEquals("no folder, nothing to do", 0, SweepPhotos.deleteAll(Files.createTempDirectory("empty").toFile()))
    }

    @Test
    fun `only names it made itself are ever read or deleted`() {
        assertNull(SweepPhotos.fileOf(filesDir, "sweep/../timeline.json"))
        assertNull(SweepPhotos.fileOf(filesDir, "../sweep/0123456789abcdef0123456789abcdef.clxp"))
        assertNull(SweepPhotos.fileOf(filesDir, "sweep/notes.txt"))
        assertNull(SweepPhotos.fileOf(filesDir, "camera_sweep.json"))
        assertEquals(
            File(File(filesDir, "sweep"), "0123456789abcdef0123456789abcdef.clxp"),
            SweepPhotos.fileOf(filesDir, "sweep/0123456789abcdef0123456789abcdef.clxp")
        )
        assertFalse(SweepPhotos.isPhoto(null))
        assertFalse(SweepPhotos.isPhoto("report.txt"))
    }

    @Test
    fun `a file that is not a sealed photo does not open`() {
        val dir = File(filesDir, SweepPhotos.DIR).apply { mkdirs() }
        File(dir, "0123456789abcdef0123456789abcdef.clxp").writeBytes(jpeg)
        assertNull(SweepPhotos.open(filesDir, "sweep/0123456789abcdef0123456789abcdef.clxp", TestStoreCipher))
    }

    @Test
    fun `the caption says what, when in UTC and on the phone, where the phone was and where in the frame`() {
        val lines = SweepPhotos.caption(
            ts = 1_793_538_202_000L, zone = TimeZone.getTimeZone("Europe/London"),
            lat = 51.507341, lon = -0.127758, method = "lens glint",
            where = "In the viewfinder, 37 % from the left and 42 % from the top"
        )
        assertEquals("Clairix hidden-camera sweep: possible camera, lens glint", lines[0])
        assertTrue(lines[1], lines[1].startsWith("2026-11-01 13:03:22 UTC ("))
        assertTrue(lines[1], lines[1].endsWith("on this phone)"))
        assertEquals("Phone at 51.50734, -0.12776", lines[2])
        assertEquals("In the viewfinder, 37 % from the left and 42 % from the top", lines[3])
        assertEquals("No position fix when this was taken", SweepPhotos.caption(0L, TimeZone.getTimeZone("UTC"), null, null, "IR illuminator", "x")[2])
    }
}
