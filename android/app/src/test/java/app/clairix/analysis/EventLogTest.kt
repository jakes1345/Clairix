package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.TimelineEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The timeline stays newest first by when things happened, whatever order they are written in. */
class EventLogTest {

    private val file = File(Files.createTempDirectory("eventlog").toFile().apply { deleteOnExit() }, "timeline.json")

    private fun event(id: String, ts: Long) =
        TimelineEvent(id = id, kind = EventKind.PLATFORM, ts = ts, title = id, detail = "", severity = Severity.LOW)

    @Test
    fun aLateWrittenOlderEventGoesBelowNewerOnes() {
        val log = EventLog(Store(file, Dispatchers.Unconfined, TestStoreCipher))
        log.record(event("a", 1_000L))
        log.record(event("c", 3_000L))
        // A scanner stop dated from its last heartbeat, found only after "c" was written.
        log.record(event("b", 2_000L))
        log.record(event("d", 4_000L))
        log.record(event("first", 500L))
        assertEquals(listOf("d", "c", "b", "a", "first"), log.snapshot().map { it.id })
    }

    @Test
    fun theOrderSurvivesAReopen() {
        val log = EventLog(Store(file, Dispatchers.Unconfined, TestStoreCipher))
        log.record(event("new", 2_000L))
        log.record(event("old", 1_000L))
        log.flush()
        val reopened = EventLog(Store(file, Dispatchers.Unconfined, TestStoreCipher))
        assertEquals(listOf("new", "old"), reopened.snapshot().map { it.id })
    }
}
