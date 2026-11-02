package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Before a map downloads: its size and date read from two small pieces of the published
 * file, whether the phone has room, whether the connection is metered, and whether a map
 * on the phone has a newer build.
 */
class MapDownloadsTest {

    private val mb = 1_048_576L

    @Test
    fun aDownloadNeedsRoomForTheUnpackedMapAndSomeToSpare() {
        val p = MapDownloads.plan(193 * mb, 475 * mb, freeBytes = 2_000 * mb, metered = false, replacing = false)
        assertEquals(193 * mb, p.downloadBytes)
        assertEquals(475 * mb, p.installedBytes)
        assertEquals(475 * mb + MapDownloads.SPARE_BYTES, p.requiredFreeBytes)
        assertTrue(p.enoughSpace)
        assertEquals(0L, p.missingBytes)
        assertFalse(p.estimated)
    }

    @Test
    fun notEnoughSpaceSaysHowMuchIsMissing() {
        val p = MapDownloads.plan(193 * mb, 475 * mb, freeBytes = 400 * mb, metered = false, replacing = false)
        assertFalse(p.enoughSpace)
        assertEquals(475 * mb + MapDownloads.SPARE_BYTES - 400 * mb, p.missingBytes)
    }

    @Test
    fun anUpdateNeedsTheSameFreeSpaceAsAFirstDownload() {
        // The old map's space is already taken and it stays until the new one is in place;
        // the new one is unpacked as it arrives, so no download sits beside it.
        val first = MapDownloads.plan(86 * mb, 206 * mb, 1_000 * mb, metered = false, replacing = false)
        val update = MapDownloads.plan(86 * mb, 206 * mb, 1_000 * mb, metered = false, replacing = true)
        assertEquals(first.requiredFreeBytes, update.requiredFreeBytes)
        assertTrue(update.replacing)
        assertFalse(first.replacing)
    }

    @Test
    fun withOnlyTheDownloadSizeTheSpaceIsEstimatedOnTheSafeSide() {
        val p = MapDownloads.plan(86 * mb, unpackedBytes = null, freeBytes = 1_000 * mb, metered = false, replacing = false)
        assertTrue(p.estimated)
        assertEquals(86 * mb * MapDownloads.UNPACK_ESTIMATE, p.installedBytes)
        // Real maps unpack to about two and a half times the download (Ohio: 86 MB to 206 MB).
        assertTrue(p.installedBytes!! > 206 * mb)
    }

    @Test
    fun withNoSizeAtAllNothingIsRefusedOnAGuess() {
        val p = MapDownloads.plan(null, null, freeBytes = 10 * mb, metered = true, replacing = false)
        assertNull(p.requiredFreeBytes)
        assertTrue(p.enoughSpace)
        assertTrue(p.metered)
    }

    @Test
    fun theResponseToARangeGivesTheWholeFileSize() {
        assertEquals(90_335_665L, MapDownloads.totalFromContentRange("bytes 0-4095/90335665"))
        assertEquals(90_335_665L, MapDownloads.totalFromContentRange(" bytes 90335661-90335664/90335665 "))
        assertNull(MapDownloads.totalFromContentRange("bytes 0-4095/*"))
        assertNull(MapDownloads.totalFromContentRange("bytes */90335665"))
        assertNull(MapDownloads.totalFromContentRange(null))
    }

    @Test
    fun theGzipTrailerGivesTheUnpackedSize() {
        // Ohio's published map on 2026-10-31: 90,335,665 bytes, whose last four say this.
        val ohio = 216_303_336L
        val tail = ByteArray(4) { ((ohio shr (8 * it)) and 0xFF).toByte() }
        assertEquals(ohio, MapDownloads.gzipSize(tail))
        assertNull(MapDownloads.gzipSize(ByteArray(3)))
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val gz = gzip(data)
        assertEquals(300_000L, MapDownloads.gzipSize(gz.copyOfRange(gz.size - 4, gz.size)))
    }

    @Test
    fun theFirstKilobytesUnpackToTheGraphHeader() {
        val graph = graphHeader("north-america-us-ohio", builtAt = 1_793_450_117L) + ByteArray(500_000) { (it * 31).toByte() }
        val gz = gzip(graph)
        val head = MapDownloads.gunzipHead(gz.copyOf(MapDownloads.HEAD_PROBE_BYTES), MapDownloads.GRAPH_HEADER_BYTES)
        assertNotNull(head)
        assertEquals(1_793_450_117L, MapDownloads.builtAt(head!!, "north-america-us-ohio"))
        // Another region's map, an older format, or something that is not gzip at all gives no date.
        assertNull(MapDownloads.builtAt(head, "north-america-us-texas"))
        assertNull(MapDownloads.builtAt(graphHeader("north-america-us-ohio", 1L, version = 1), "north-america-us-ohio"))
        assertNull(MapDownloads.gunzipHead(ByteArray(4096) { 7 }, MapDownloads.GRAPH_HEADER_BYTES))
        assertNull(MapDownloads.gunzipHead(gz.copyOf(8), MapDownloads.GRAPH_HEADER_BYTES))
    }

    @Test
    fun updateOnlyWhenThePublishedMapIsNewer() {
        assertEquals(true, MapDownloads.isNewer(installedBuiltAt = 1_000L, publishedBuiltAt = 2_000L))
        assertEquals(false, MapDownloads.isNewer(2_000L, 2_000L))
        assertEquals(false, MapDownloads.isNewer(3_000L, 2_000L))
        assertNull(MapDownloads.isNewer(1_000L, null))
    }

    @Test
    fun mapsAreComparedAtMostOnceAWeek() {
        val week = MapDownloads.UPDATE_CHECK_EVERY_MS
        assertTrue(MapDownloads.updateCheckDue(0L, 1_000L))
        assertFalse(MapDownloads.updateCheckDue(10_000L, 10_000L + week - 1))
        assertTrue(MapDownloads.updateCheckDue(10_000L, 10_000L + week))
        // The clock went back past the last check.
        assertTrue(MapDownloads.updateCheckDue(10_000L, 5_000L))
    }

    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    /** A [RoadGraph] header: magic, version, four counts, the box, the build time at 40, the region at 48. */
    private fun graphHeader(region: String, builtAt: Long, version: Int = 2): ByteArray {
        val h = ByteBuffer.allocate(MapDownloads.GRAPH_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("AGR1".toByteArray(Charsets.US_ASCII))
        h.putInt(version)
        repeat(4) { h.putInt(1) }
        repeat(4) { h.putInt(0) }
        h.putLong(builtAt)
        h.put(region.toByteArray(Charsets.US_ASCII))
        return h.array()
    }

    @Test
    fun theWeeklyCheckWaitsForAnUnmeteredConnection() {
        val week = MapDownloads.UPDATE_CHECK_EVERY_MS
        assertTrue(MapDownloads.updateCheckDue(0L, 1_000L, metered = false))
        assertFalse(MapDownloads.updateCheckDue(0L, 1_000L, metered = true))
        assertFalse(MapDownloads.updateCheckDue(10_000L, 10_000L + week - 1, metered = false))
        assertTrue(MapDownloads.updateCheckDue(10_000L, 10_000L + week, metered = false))
        assertFalse(MapDownloads.updateCheckDue(10_000L, 10_000L + week, metered = true))
    }

    @Test
    fun theSignedChecksumSettlesItOnlyWhenItMatchesTheRecordedOne() {
        val sum = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertTrue(MapDownloads.checksumCurrent(sum, sum))
        assertTrue(MapDownloads.checksumCurrent(sum, sum.uppercase()))
        // A newer build: the dates decide.
        assertFalse(MapDownloads.checksumCurrent(sum, sum.replace('9', '8')))
        // A map installed before checksums were recorded: the dates decide.
        assertFalse(MapDownloads.checksumCurrent(null, sum))
    }

    @Test
    fun theUpdateLineNamesTheRegionTheBuildAndTheSize() {
        assertEquals("Newer Texas map available (built 31 Oct 2026, 202 MB)", MapDownloads.updateLine("Texas", "31 Oct 2026", "202 MB"))
        assertEquals("Newer Ohio map available (90 MB)", MapDownloads.updateLine("Ohio", null, "90 MB"))
    }
}
