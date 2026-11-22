package app.clairix.screenshots

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What a screen keeps while its screenshot is drawn goes to a temporary folder, not to the folder the tests run in. */
class ScreenshotHarnessTest {

    @Test
    fun aScreensFilesGoToATemporaryFolder() {
        // The base has no data directory to hand on, as layoutlib's has none: every folder is the harness's own.
        val context = ScreenshotContext(ContextWrapper(null))
        val tmp = File(checkNotNull(System.getProperty("java.io.tmpdir"))).absoluteFile
        val dirs = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir)
        for (dir in dirs) {
            assertTrue("$dir is a folder", dir.isDirectory)
            assertTrue("$dir is under $tmp", dir.isAbsolute && dir.startsWith(tmp))
        }
        assertEquals("each has its own folder", dirs.size, dirs.toSet().size)
        // Where the app keeps the lists it downloads: a relative path here would be android/app/relays.
        assertTrue(File(context.filesDir, "relays").isAbsolute)
        // One data directory for the process, as for the app on a phone.
        assertEquals(context.filesDir, ScreenshotContext(ContextWrapper(null)).filesDir)
    }
}
