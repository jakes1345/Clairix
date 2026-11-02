package app.clairix

import app.clairix.ScannerRecordings.Entry
import app.clairix.ScannerRecordings.Header
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * The scanner's recordings: sealed a second at a time and bound to their place, played
 * back piece by piece, held to ten minutes each, to a cap in all and to the retention,
 * and plain recordings from before only ever moved or deleted when asked.
 */
class ScannerRecordingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Reversible and visibly not plaintext, and, like AES-GCM's associated data, refuses a
     * piece opened for another place. That refusal is what the format relies on.
     */
    private object PlaceCipher : StoreCipher {
        override fun seal(text: String): ByteArray = flip(text.toByteArray(Charsets.UTF_8))
        override fun open(blob: ByteArray): String = String(flip(blob), Charsets.UTF_8)
        override fun sealBytes(plain: ByteArray, aad: ByteArray): ByteArray =
            ByteBuffer.allocate(4 + aad.size + plain.size).putInt(aad.size).put(aad).put(flip(plain)).array()
        override fun openBytes(blob: ByteArray, aad: ByteArray): ByteArray {
            val b = ByteBuffer.wrap(blob)
            val stored = ByteArray(b.int).also { b.get(it) }
            require(stored.contentEquals(aad)) { "sealed for another place" }
            return flip(ByteArray(b.remaining()).also { b.get(it) })
        }

        private fun flip(b: ByteArray) = ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }
    }

    private fun store(dir: File = tmp.root) = ScannerRecordings(dir, PlaceCipher)

    /** [n] samples counting up from [from]. */
    private fun samples(n: Int, from: Int = 0) = ShortArray(n) { (from + it).toShort() }

    private fun le(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        s.forEachIndexed { i, v ->
            out[2 * i] = v.toInt().toByte()
            out[2 * i + 1] = (v.toInt() shr 8).toByte()
        }
        return out
    }

    private fun readAll(source: PcmSource): Pair<ByteArray, List<Int>> {
        val out = ByteArrayOutputStream()
        val sizes = ArrayList<Int>()
        source.use {
            while (true) {
                val piece = it.next() ?: break
                sizes += piece.size
                out.write(piece)
            }
        }
        return out.toByteArray() to sizes
    }

    @Test
    fun aRecordingPlaysBackAsWrittenASecondAtATime() {
        val s = store()
        // 1 kHz, so a second is 2000 bytes.
        val w = s.begin(Header(1_793_450_117_000L, 155_475_000L, "Fire dispatch", 1_000))
        val audio = samples(2_500)
        w.write(audio)
        assertTrue(w.closeAndKeep())

        val info = s.list().single()
        assertEquals(1_793_450_117_000L, info.startedAt)
        assertEquals(155_475_000L, info.freqHz)
        assertEquals("Fire dispatch", info.channel)
        assertEquals(2_500L, info.durationMs)
        assertTrue(info.complete)
        assertTrue(info.readable)

        val (played, pieces) = readAll(s.open(info.id))
        assertArrayEquals(le(audio), played)
        assertEquals(listOf(2_000, 2_000, 1_000), pieces)
    }

    @Test
    fun samplesAreLittleEndian() {
        val s = store()
        val w = s.begin(Header(1L, 1L, "", 1_000))
        w.write(shortArrayOf(0x0102, -2))
        w.close()
        val (played, _) = readAll(s.open(s.list().single().id))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), played)
    }

    @Test
    fun theFileNamesNothingAndHoldsNoPlainAudio() {
        val s = store()
        val w = s.begin(Header(1_793_450_117_000L, 155_475_000L, "Fire dispatch", 1_000))
        val audio = samples(1_000, from = 1_000)
        w.write(audio)
        w.close()
        val file = tmp.root.listFiles()!!.single()
        assertTrue(file.name.matches(Regex("^[0-9a-f]{32}\\.rec$")))
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Fire dispatch"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(le(audio), Charsets.ISO_8859_1)))
    }

    @Test
    fun aRecordingStopsAtTenMinutes() {
        val s = store()
        // 100 Hz: ten minutes is 60,000 samples.
        val w = s.begin(Header(1L, 1L, "", 100))
        w.write(samples(59_999))
        assertFalse(w.full)
        w.write(samples(10_000))
        assertTrue(w.full)
        assertEquals(ScannerRecordings.MAX_RECORDING_MS, w.durationMs)
        w.close()
        assertEquals(ScannerRecordings.MAX_RECORDING_MS, s.list().single().durationMs)
    }

    @Test
    fun aTransmissionWithNoAudioLeavesNothing() {
        val s = store()
        val w = s.begin(Header(1L, 1L, "", 1_000))
        assertFalse(w.closeAndKeep())
        assertTrue(s.list().isEmpty())
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test
    fun theRecordingUnderWayIsNeitherListedNorDeleted() {
        val s = store()
        val w = s.begin(Header(1L, 1L, "", 1_000))
        w.write(samples(3_000))
        assertTrue(s.list().isEmpty())
        assertFalse(s.delete(w.id))
        assertEquals(0, s.deleteAll())
        assertEquals(0, s.pruneOlderThan(Long.MAX_VALUE))
        w.close()
        assertEquals(1, s.list().size)
    }

    @Test
    fun aRecordingCutShortPlaysAsFarAsItGot() {
        val writing = store()
        val w = writing.begin(Header(1L, 1L, "", 1_000))
        w.write(samples(3_500))
        // The app stopped here: three seconds were sealed, the half second in hand and the trailer never were.
        val info = store().list().single()
        assertFalse(info.complete)
        assertEquals(3_000L, info.durationMs)
        val (played, pieces) = readAll(store().open(info.id))
        assertEquals(listOf(2_000, 2_000, 2_000), pieces)
        assertArrayEquals(le(samples(3_000)), played)
    }

    @Test
    fun piecesCannotBeReorderedOrMovedToAnotherFile() {
        val s = store()
        val w = s.begin(Header(1L, 1L, "", 1_000))
        w.write(samples(2_000))
        w.close()
        val id = s.list().single().id
        val file = File(tmp.root, "$id.rec")
        val bytes = file.readBytes()
        // Magic, then the header record, then two chunk records of the same size.
        var at = 4
        at += 5 + ByteBuffer.wrap(bytes, at + 1, 4).int
        val chunkLen = 5 + ByteBuffer.wrap(bytes, at + 1, 4).int
        val swapped = bytes.copyOf()
        System.arraycopy(bytes, at, swapped, at + chunkLen, chunkLen)
        System.arraycopy(bytes, at + chunkLen, swapped, at, chunkLen)
        file.writeBytes(swapped)
        assertThrows(IllegalArgumentException::class.java) { readAll(s.open(id)) }

        // A whole file under another name does not open either.
        file.writeBytes(bytes)
        val other = "0".repeat(32)
        file.copyTo(File(tmp.root, "$other.rec"))
        val listed = s.list().associateBy { it.id }
        assertTrue(listed.getValue(id).readable)
        assertFalse(listed.getValue(other).readable)
        assertTrue(s.delete(other))
    }

    @Test
    fun theCapDeletesTheOldestFirstAndNeverTheOneBeingWritten() {
        val files = listOf(Entry("a", 100, 1_000), Entry("b", 100, 2_000), Entry("c", 100, 3_000))
        assertEquals(listOf("a"), ScannerRecordings.overCap(files, maxBytes = 250, keep = null))
        assertEquals(listOf("a", "b"), ScannerRecordings.overCap(files, maxBytes = 100, keep = null))
        assertEquals(listOf("b"), ScannerRecordings.overCap(files, maxBytes = 250, keep = "a"))
        assertTrue(ScannerRecordings.overCap(files, maxBytes = 300, keep = null).isEmpty())
    }

    @Test
    fun theRetentionDeletesWhatWasLastWrittenBeforeTheCutoff() {
        val files = listOf(Entry("a", 100, 1_000), Entry("b", 100, 2_000), Entry("c", 100, 3_000))
        assertEquals(listOf("a", "b"), ScannerRecordings.olderThan(files, cutoff = 2_500, keep = null))
        assertEquals(listOf("b"), ScannerRecordings.olderThan(files, cutoff = 2_500, keep = "a"))
        assertTrue(ScannerRecordings.olderThan(files, cutoff = 1_000, keep = null).isEmpty())
    }

    @Test
    fun capAndRetentionActOnTheFilesOnDisk() {
        val s = store()
        val ids = (0 until 3).map { i ->
            val w = s.begin(Header(i.toLong(), 1L, "", 1_000))
            w.write(samples(1_000))
            w.close()
            File(tmp.root, "${w.id}.rec").setLastModified(1_000_000_000_000L + i * 60_000L)
            w.id
        }
        val one = File(tmp.root, "${ids[0]}.rec").length()
        assertEquals(1, s.enforceCap(maxBytes = 2 * one))
        assertFalse(File(tmp.root, "${ids[0]}.rec").exists())
        assertEquals(1, s.pruneOlderThan(1_000_000_000_000L + 90_000L))
        assertEquals(listOf(ids[2]), s.list().map { it.id })
    }

    @Test
    fun plainRecordingsFromBeforeAreFoundAndOnlyMovedWhenAsked() {
        val legacyDir = tmp.newFolder("external-scanner")
        val audio = ByteArray(96_000 * 2 + 3) { (it % 199).toByte() }
        val plain = File(legacyDir, "1793450117000_155475000.raw").apply { writeBytes(audio) }
        File(legacyDir, "notes.txt").writeText("not a recording")
        val sealedDir = tmp.newFolder("sealed")
        val s = store(sealedDir)

        val found = ScannerRecordings.findLegacy(listOf(legacyDir, tmp.newFolder("empty")))
        assertEquals(1, found.size)
        assertEquals(1_793_450_117_000L, found[0].startedAt)
        assertEquals(155_475_000L, found[0].freqHz)
        // Played as it is, before any choice.
        assertArrayEquals(audio.copyOf(audio.size - 1), readAll(ScannerRecordings.PlainReader(plain)).first)

        val now = 1_800_000_000_000L
        assertEquals(1, s.importLegacy(found, now = now))
        assertFalse(plain.exists())
        val info = s.list().single()
        assertEquals(1_793_450_117_000L, info.startedAt)
        assertEquals(155_475_000L, info.freqHz)
        assertEquals(2_000L, info.durationMs)
        assertTrue(info.complete)
        // Kept from now, not deleted at once for having been made long ago.
        assertEquals(now, File(sealedDir, "${info.id}.rec").lastModified())
        // The odd last byte was half a sample.
        assertArrayEquals(audio.copyOf(audio.size - 1), readAll(s.open(info.id)).first)
    }

    @Test
    fun movingPlainRecordingsNeverDeletesAnythingToMakeRoom() {
        val legacyDir = tmp.newFolder("external-scanner")
        File(legacyDir, "1793450117000_155475000.raw").writeBytes(ByteArray(96_000))
        val sealedDir = tmp.newFolder("sealed")
        val s = store(sealedDir)
        val w = s.begin(Header(1L, 1L, "", 1_000))
        w.write(samples(1_000))
        w.close()

        val found = ScannerRecordings.findLegacy(listOf(legacyDir))
        assertEquals(0, s.importLegacy(found, now = 1L, maxBytes = s.totalBytes() + 1_000))
        assertTrue(found.single().file.exists())
        assertEquals(1, s.list().size)
        assertNull(s.list().firstOrNull { it.freqHz == 155_475_000L })
    }

    @Test
    fun clearAllDataDeletesEveryRecordingAndNothingElse() {
        val sealedDir = tmp.newFolder("sealed-all")
        val s = store(sealedDir)
        s.begin(Header(1L, 1L, "", 1_000)).apply { write(samples(2_000)); close() }
        val underWay = s.begin(Header(2L, 2L, "", 1_000)).apply { write(samples(2_000)) }
        val legacyDir = tmp.newFolder("legacy-all")
        val plain = File(legacyDir, "1793450117000_155475000.raw").apply { writeBytes(ByteArray(4_000)) }
        val other = File(legacyDir, "notes.txt").apply { writeText("not a recording") }
        val kept = File(sealedDir, "something.json").apply { writeText("{}") }

        assertEquals(3, ScannerRecordings.deleteEverything(sealedDir, listOf(legacyDir)))
        assertFalse(plain.exists())
        assertTrue(other.exists())
        assertTrue(kept.exists())
        // The recording under way finishes into a file that is already gone.
        underWay.close()
        assertTrue(s.list().isEmpty())
        assertTrue(ScannerRecordings.findLegacy(listOf(legacyDir)).isEmpty())
    }
}
