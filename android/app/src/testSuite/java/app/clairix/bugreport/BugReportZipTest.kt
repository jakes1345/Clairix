package app.clairix.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finding the text in a bug-report zip, and the caps that keep a hostile zip from exhausting the phone. */
class BugReportZipTest {

    private val text = """
        == dumpstate: 2026-11-01 22:14:03
        DUMP OF SERVICE appops:
          Uid 10123:
            Package com.example.spy:
              RECORD_AUDIO (allow):
                Access: [bg-s] 2026-11-01 02:40:01.004 (-19h34m2s) duration=+41m0s
        --------- 0.051s was the duration of dumpsys appops, ending at: 2026-11-01 22:14:20
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = zipOf(entries.toList())

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, limits: BugReportZip.Limits = BugReportZip.Limits()) = BugReportZip.read(bytes.inputStream(), limits)

    @Test
    fun theMainTextIsFoundAfterTheOtherEntries() {
        val o = read(
            zip(
                "version.txt" to "2.0".toByteArray(),
                "FS/data/anr/traces.txt" to ByteArray(10_000) { 'x'.code.toByte() },
                "proto/activity.proto" to ByteArray(500),
                "bugreport-husky-AP4A.250105.002-2026-11-01-22-14-03.txt" to text.toByteArray(),
                "main_entry.txt" to "bugreport-husky-AP4A.250105.002-2026-11-01-22-14-03.txt".toByteArray(),
            )
        ) as BugReportZip.Outcome.Read
        assertEquals("bugreport-husky-AP4A.250105.002-2026-11-01-22-14-03.txt", o.entry)
        assertEquals("com.example.spy", o.findings.uses.single().packageName)
        assertNull(o.stopped)
    }

    @Test
    fun onlyATopLevelBugreportTextIsTheMainOne() {
        assertTrue(BugReportZip.isMainEntry("bugreport-husky-2026-11-01.txt"))
        assertFalse(BugReportZip.isMainEntry("FS/data/bugreport-old.txt"))
        assertFalse(BugReportZip.isMainEntry("dumpstate_log.txt"))
        assertFalse(BugReportZip.isMainEntry("bugreport-husky.zip"))
    }

    @Test
    fun aZipWithoutTheTextSaysSo() {
        val o = read(zip("photo.png" to ByteArray(10))) as BugReportZip.Outcome.NotRead
        assertTrue(o.reason.contains("No bug report text"))
    }

    @Test
    fun aPlainTextBugReportIsReadToo() {
        val o = read(text.toByteArray()) as BugReportZip.Outcome.Read
        assertEquals(1, o.findings.uses.size)
    }

    @Test
    fun somethingThatIsNotAZipOrABugReportReadsAsNothingFound() {
        val o = read(byteArrayOf(1, 2, 3)) as BugReportZip.Outcome.Read
        assertTrue(o.findings.uses.isEmpty())
        assertTrue(o.findings.unread.any { it.startsWith("No app ops section") })
    }

    @Test
    fun tooManyEntriesStopTheReading() {
        val many = (1..20).map { "f$it.txt" to ByteArray(1) }
        val o = read(zipOf(many + ("bugreport-x.txt" to text.toByteArray())), BugReportZip.Limits(entries = 10)) as BugReportZip.Outcome.NotRead
        assertTrue(o.reason.contains("more than 10 entries"))
    }

    @Test
    fun aZipThatInflatesPastTheCapStops() {
        // 64 kB of zeros compress to almost nothing: a small zip bomb.
        val bomb = zip("padding.bin" to ByteArray(64 * 1024), "bugreport-x.txt" to text.toByteArray())
        assertTrue(bomb.size < 2_000)
        val o = read(bomb, BugReportZip.Limits(totalBytes = 16 * 1024)) as BugReportZip.Outcome.NotRead
        assertTrue(o.reason.contains("inflates to more than 16384 bytes"))
    }

    @Test
    fun aLongTextIsReadOnlyUpToTheCap() {
        val long = text + "\n" + "filler line\n".repeat(1_000)
        val o = read(zip("bugreport-x.txt" to long.toByteArray()), BugReportZip.Limits(textBytes = 1_000)) as BugReportZip.Outcome.Read
        assertEquals(1, o.findings.uses.size)
        assertTrue(o.stopped!!.contains("longer than 1000 bytes"))
    }

    @Test
    fun aVeryLongLineIsCutAndCounted() {
        val withLong = "x".repeat(10_000) + "\n" + text
        val o = read(zip("bugreport-x.txt" to withLong.toByteArray()), BugReportZip.Limits(line = 100)) as BugReportZip.Outcome.Read
        assertEquals(1L, o.findings.longLines)
        assertEquals(1, o.findings.uses.size)
        assertTrue(o.findings.unread.any { it.startsWith("1 very long lines") })
    }

    @Test
    fun aDamagedZipSaysItCouldNotBeRead() {
        val good = zip("bugreport-x.txt" to text.toByteArray())
        val damaged = good.copyOf(40)
        val o = read(damaged)
        assertTrue(o is BugReportZip.Outcome.NotRead || (o as BugReportZip.Outcome.Read).findings.uses.isEmpty())
    }
}
