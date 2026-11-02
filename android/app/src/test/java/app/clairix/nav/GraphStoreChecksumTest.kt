package app.clairix.nav

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The checksum an installed map came from is read back for the weekly comparison only
 * while that map is on the phone, only when it is a checksum, and goes with the map.
 */
class GraphStoreChecksumTest {

    private val files: File = Files.createTempDirectory("graphs").toFile().apply { deleteOnExit() }

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
    }

    private val nav = File(files, "nav").apply { mkdirs() }
    private val sum = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun theRecordedChecksumIsReadForAnInstalledMapAndDeletedWithIt() {
        val store = GraphStore(context)
        assertNull(store.recordedChecksum("texas"))
        File(nav, "texas.agr").writeBytes(ByteArray(256))
        // A map from before checksums were recorded.
        assertNull(store.recordedChecksum("texas"))
        File(nav, "texas.agr.sha256").writeText("$sum\n")
        assertEquals(sum, store.recordedChecksum("texas"))
        // Not a map region, or not a checksum: nothing.
        assertNull(store.recordedChecksum("../texas"))
        File(nav, "ohio.agr").writeBytes(ByteArray(256))
        File(nav, "ohio.agr.sha256").writeText("not a checksum")
        assertNull(store.recordedChecksum("ohio"))

        store.delete("texas")
        assertFalse(File(nav, "texas.agr.sha256").exists())
        assertNull(store.recordedChecksum("texas"))
    }

    @Test
    fun aChecksumWithoutItsMapCountsForNothing() {
        File(nav, "utah.agr.sha256").writeText(sum)
        assertNull(GraphStore(context).recordedChecksum("utah"))
        // And it is not listed as a map.
        assertEquals(emptyList<InstalledRegion>(), GraphStore(context).installed())
    }
}
