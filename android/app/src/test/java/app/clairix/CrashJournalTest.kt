package app.clairix

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The crash record keeps classes and frames and never an exception's message,
 * survives the sealed store, and the problem report carries the device line only.
 */
class CrashJournalTest {

    @Test
    fun theTraceKeepsClassesAndFramesButNoMessage() {
        val cause = IllegalStateException("contact +15551234567 at 51.5,-0.12")
        val error = RuntimeException("wrapping: home is 12 Acacia Road", cause)
        val trace = CrashJournal.trace(error)
        assertTrue(trace.startsWith("java.lang.RuntimeException\n"))
        assertTrue(trace.contains("\nCaused by: java.lang.IllegalStateException\n"))
        assertTrue(trace.contains("app.clairix.CrashJournalTest.theTraceKeepsClassesAndFramesButNoMessage("))
        assertFalse(trace.contains("5551234567"))
        assertFalse(trace.contains("51.5"))
        assertFalse(trace.contains("Acacia"))
        assertFalse(trace.contains("wrapping"))
    }

    @Test
    fun aCauseThatPointsBackEndsTheChain() {
        val a = RuntimeException("a")
        val b = IllegalArgumentException("b", a)
        a.initCause(b)
        val trace = CrashJournal.trace(a)
        assertEquals(1, Regex("Caused by: ").findAll(trace).count())
    }

    @Test
    fun longTracesAreCut() {
        val error = RuntimeException()
        error.stackTrace = Array(200) { StackTraceElement("app.clairix.Deep", "level$it", "Deep.kt", it) }
        val trace = CrashJournal.trace(error)
        assertEquals(64, trace.lines().count { it.startsWith("\tat ") })
        assertTrue(trace.endsWith("\t... 136 more"))
    }

    @Test
    fun theRecordSurvivesTheSealedStore() {
        val file = File(Files.createTempDirectory("crash").toFile().apply { deleteOnExit() }, CrashJournal.FILE)
        val record = CrashRecord(ts = 1_761_955_200_000L, version = "1.4.0", model = "Pixel 8", sdk = 35, trace = "java.lang.RuntimeException\n\tat x.y(Z.kt:1)")
        Store(file, cipher = TestStoreCipher).replace(CrashJournal.toJson(record))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Pixel"))
        val back = CrashJournal.fromJson(Store(file, cipher = TestStoreCipher).load { JSONObject() })
        assertEquals(record, back)
        assertNull(CrashJournal.fromJson(JSONObject()))
    }

    @Test
    fun theProblemReportNamesTheBuildAndDeviceOnly() {
        assertEquals("Clairix 1.4.0 on Pixel 8/35", CrashJournal.reportSubject("1.4.0", "Pixel 8", 35))
        val body = CrashJournal.reportBody("1.4.0", "Pixel 8", 35)
        assertTrue(body.contains("Clairix 1.4.0, Pixel 8, Android SDK 35"))
        assertTrue(body.contains("What happened:"))
        val record = CrashRecord(0, "1.4.0", "Pixel 8", 35, "java.lang.RuntimeException")
        assertEquals("Clairix 1.4.0 on Pixel 8, Android SDK 35\n\njava.lang.RuntimeException", record.asText())
    }
}
