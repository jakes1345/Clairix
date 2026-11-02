package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The scanner gunzips and parses the plate-reader lists only when the stamp of what is
 * on disk changes: a download, a replacement or a deletion moves it; nothing else does.
 */
class CameraCacheStampTest {

    private fun dir(): File = Files.createTempDirectory("nav").toFile().apply { deleteOnExit() }

    private fun File.put(name: String, text: String, modified: Long): File =
        File(this, name).apply { writeText(text); setLastModified(modified); deleteOnExit() }

    private val t0 = 1_700_000_000_000L

    @Test
    fun aDirectoryNobodyTouchedKeepsItsStamp() {
        val d = dir()
        d.put("cameras-ohio.json.gz", "list", t0)
        d.put("cameras-ohio.meta.json", "{\"signed\":true}", t0)
        assertEquals(CameraFeed.cacheStamp(d), CameraFeed.cacheStamp(d))
    }

    @Test
    fun aReplacedListMovesTheStamp() {
        val d = dir()
        val list = d.put("cameras-ohio.json.gz", "list", t0)
        d.put("cameras-ohio.meta.json", "{\"signed\":true}", t0)
        val before = CameraFeed.cacheStamp(d)
        list.setLastModified(t0 + 86_400_000L)
        assertNotEquals(before, CameraFeed.cacheStamp(d))
    }

    @Test
    fun aNewRegionOrADeletedOneMovesTheStamp() {
        val d = dir()
        d.put("cameras-ohio.json.gz", "list", t0)
        val one = CameraFeed.cacheStamp(d)
        val texas = d.put("cameras-texas.json.gz", "list", t0)
        val two = CameraFeed.cacheStamp(d)
        assertNotEquals(one, two)
        texas.delete()
        assertEquals(one, CameraFeed.cacheStamp(d))
    }

    @Test
    fun aRewrittenSignatureRecordMovesTheStamp() {
        // Whether a list is trusted is in its record; loadCached reads it, so the stamp covers it.
        val d = dir()
        d.put("cameras-ohio.json.gz", "list", t0)
        val meta = d.put("cameras-ohio.meta.json", "{}", t0)
        val before = CameraFeed.cacheStamp(d)
        meta.writeText("{\"signed\":true}")
        meta.setLastModified(t0 + 1_000L)
        assertNotEquals(before, CameraFeed.cacheStamp(d))
    }

    @Test
    fun filesTheListsDoNotDependOnLeaveTheStampAlone() {
        val d = dir()
        d.put("cameras-ohio.json.gz", "list", t0)
        val before = CameraFeed.cacheStamp(d)
        // A download in progress, and the region's graph and work zones beside it.
        d.put("cameras-ohio.json.gz.tmp", "partial", t0 + 5_000L)
        d.put("graph-ohio.bin", "graph", t0 + 5_000L)
        d.put("workzones-ohio.json.gz", "zones", t0 + 5_000L)
        assertEquals(before, CameraFeed.cacheStamp(d))
    }

    @Test
    fun noDirectoryIsAnEmptyStamp() {
        assertEquals("", CameraFeed.cacheStamp(File(dir(), "missing")))
    }
}
