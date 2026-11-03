package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.TimelineEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The timeline stays newest first by when things happened, whatever order they are written in. */
class EventLogTest {

    private val file = File(Files.createTempDirectory("eventlog").toFile().apply { deleteOnExit() }, "timeline.json")

    private fun event(id: String, ts: Long, kind: EventKind = EventKind.PLATFORM, attachment: String? = null) =
        TimelineEvent(id = id, kind = kind, ts = ts, title = id, detail = "", severity = Severity.LOW, attachment = attachment)

    private fun store() = Store(file, Dispatchers.Unconfined, TestStoreCipher)

    @Test
    fun aLateWrittenOlderEventGoesBelowNewerOnes() {
        val log = EventLog(store())
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
        val log = EventLog(store())
        log.record(event("new", 2_000L))
        log.record(event("old", 1_000L))
        log.flush()
        val reopened = EventLog(store())
        assertEquals(listOf("new", "old"), reopened.snapshot().map { it.id })
    }

    @Test
    fun aPhotoAttachmentAndTheCameraKindSurviveAReopen() {
        val log = EventLog(store())
        log.record(event("camera_photo|x@5", 5_000L, EventKind.CAMERA, "sweep/0123456789abcdef0123456789abcdef.clxp"))
        log.record(event("plain", 4_000L))
        log.flush()
        val back = EventLog(store()).snapshot()
        assertEquals(EventKind.CAMERA, back[0].kind)
        assertEquals("sweep/0123456789abcdef0123456789abcdef.clxp", back[0].attachment)
        assertNull(back[1].attachment)
    }

    @Test
    fun eventsStoredBeforeAttachmentsAndTheCameraKindStillLoad() {
        // A timeline written by an earlier build: old kinds, no attachment field.
        store().replace(
            org.json.JSONObject().put(
                "events", org.json.JSONArray()
                    .put(org.json.JSONObject().put("id", "camera|optical|glint@2").put("kind", "PLATFORM").put("ts", 2_000L)
                        .put("title", "Possible hidden camera").put("detail", "d").put("severity", "HIGH").put("lat", 1.5).put("lon", 2.5))
                    .put(org.json.JSONObject().put("id", "following@1").put("kind", "FOLLOWING").put("ts", 1_000L)
                        .put("title", "Tracker").put("detail", "d").put("severity", "CRITICAL"))
            )
        )
        val loaded = EventLog(store()).snapshot()
        assertEquals(listOf("camera|optical|glint@2", "following@1"), loaded.map { it.id })
        assertEquals(EventKind.PLATFORM, loaded[0].kind)
        assertEquals(Severity.HIGH, loaded[0].severity)
        assertEquals(1.5, loaded[0].lat!!, 0.0)
        assertEquals(EventKind.FOLLOWING, loaded[1].kind)
        assertTrue(loaded.all { it.attachment == null })
    }

    @Test
    fun aKindThisBuildDoesNotKnowReadsAsAPlatformAlertNotAFollowingTracker() {
        store().replace(
            org.json.JSONObject().put(
                "events", org.json.JSONArray().put(
                    org.json.JSONObject().put("id", "future@1").put("kind", "SOMETHING_NEWER").put("ts", 1_000L)
                        .put("title", "t").put("detail", "d").put("severity", "LOW")
                )
            )
        )
        assertEquals(EventKind.PLATFORM, EventLog(store()).snapshot().single().kind)
    }

    @Test
    fun everyEventThatLeavesTheLogIsHandedOverSoItsPhotoGoesWithIt() {
        val dropped = ArrayList<String>()
        val log = EventLog(store(), cap = 3) { gone -> gone.forEach { dropped += it.id } }
        log.record(event("p1", 1_000L, EventKind.CAMERA, "sweep/a.clxp"))
        log.record(event("e2", 2_000L))
        log.record(event("e3", 3_000L))
        log.record(event("e4", 4_000L))
        // Over the cap: the oldest ordinary event goes; the kept photo stays.
        assertEquals(listOf("e2"), dropped)
        // The retention: everything ordinary before the cutoff.
        assertEquals(1, log.pruneOlderThan(3_500L))
        assertEquals(listOf("e2", "e3"), dropped)
        // "Clear all data": the rest, the photo with it, newest first as the log holds it.
        log.clear()
        assertEquals(listOf("e2", "e3", "e4", "p1"), dropped)
        // Nothing to prune: nothing handed over.
        assertEquals(0, log.pruneOlderThan(10_000L))
        assertEquals(4, dropped.size)
    }

    @Test
    fun aKeptPhotoOutlivesTheRetentionAndIsTheLastToGoAtTheCap() {
        val dropped = ArrayList<String>()
        val log = EventLog(store(), cap = 2) { gone -> gone.forEach { dropped += it.id } }
        log.record(event("photo", 1_000L, EventKind.CAMERA, "sweep/a.clxp"))
        log.record(event("finding", 1_500L, EventKind.CAMERA))
        // A camera finding without a photo ages out; the photo does not.
        assertEquals(1, log.pruneOlderThan(5_000L))
        assertEquals(listOf("finding"), dropped)
        assertTrue(EventLog.isKept(log.snapshot().single()))
        // Ordinary events come and go past the cap around it.
        log.record(event("a", 2_000L))
        log.record(event("b", 3_000L))
        assertEquals(listOf("finding", "a"), dropped)
        assertEquals(listOf("b", "photo"), log.snapshot().map { it.id })
        // Once only kept events are left, the oldest of them goes at the cap.
        log.record(event("photo2", 4_000L, EventKind.CAMERA, "sweep/b.clxp"))
        log.record(event("photo3", 5_000L, EventKind.CAMERA, "sweep/c.clxp"))
        assertEquals(listOf("photo3", "photo2"), log.snapshot().map { it.id })
        assertEquals(listOf("finding", "a", "b", "photo"), dropped)
    }

    @Test
    fun theCapHoldsAcrossAReopenAndKeepsTheNewest() {
        val log = EventLog(store(), cap = 500)
        for (i in 1..520) log.record(event("e$i", i * 1_000L))
        assertEquals(500, log.snapshot().size)
        assertEquals("e520", log.snapshot().first().id)
        assertEquals("e21", log.snapshot().last().id)
        val reopened = EventLog(store(), cap = 500).snapshot()
        assertEquals(500, reopened.size)
        assertEquals("e21", reopened.last().id)
    }

    @Test
    fun aLateOldEventOverTheCapIsTheOneThatGoes() {
        val log = EventLog(store(), cap = 3)
        log.record(event("b", 2_000L))
        log.record(event("c", 3_000L))
        log.record(event("d", 4_000L))
        log.record(event("a", 1_000L)) // older than all three: it is the oldest, so it goes at once
        assertEquals(listOf("d", "c", "b"), log.snapshot().map { it.id })
    }

    @Test
    fun aRepeatWithinTheWindowIsOneEntryAndAfterItAnother() {
        val log = EventLog(store())
        val window = 30 * 60_000L
        assertTrue(log.record(event("follow:AA@1000", 1_000L), dedupeKey = "follow:AA"))
        assertFalse("a repeat a minute later", log.record(event("follow:AA@61000", 61_000L), dedupeKey = "follow:AA"))
        assertFalse(log.record(event("follow:AA@x", 1_000L + window - 1), dedupeKey = "follow:AA"))
        // Another device is its own entry inside the same window.
        assertTrue(log.record(event("follow:BB@2000", 2_000L), dedupeKey = "follow:BB"))
        // A key that is a prefix of another does not swallow it.
        assertTrue(log.record(event("follow:A@3000", 3_000L), dedupeKey = "follow:A"))
        // The window measured from the entry kept, not from the repeats dropped.
        assertTrue(log.record(event("follow:AA@late", 1_000L + window), dedupeKey = "follow:AA"))
        assertEquals(4, log.snapshot().size)
        // Without a key nothing is suppressed.
        assertTrue(log.record(event("follow:AA@late", 1_000L + window)))
        assertEquals(5, log.snapshot().size)
    }

    @Test
    fun aShorterWindowLetsTheRepeatThrough() {
        val log = EventLog(store())
        assertTrue(log.record(event("k@1", 1_000L), dedupeKey = "k", dedupeWindowMs = 10_000L))
        assertFalse(log.record(event("k@2", 10_999L), dedupeKey = "k", dedupeWindowMs = 10_000L))
        assertTrue(log.record(event("k@3", 11_000L), dedupeKey = "k", dedupeWindowMs = 10_000L))
    }

    @Test
    fun savedEvidenceOutlivesTheRetentionAndIsTheLastToGoAtTheCap() {
        val dropped = ArrayList<String>()
        val log = EventLog(store(), cap = 3) { gone -> gone.forEach { dropped += it.id } }
        log.record(event("evidence", 1_000L, EventKind.EVIDENCE))
        log.record(event("e2", 2_000L))
        log.record(event("e3", 3_000L))
        log.record(event("e4", 4_000L))
        // Over the cap: the oldest ordinary event goes, not the older evidence.
        assertEquals(listOf("e2"), dropped)
        // The retention leaves the evidence however old it is.
        assertEquals(1, log.pruneOlderThan(3_500L))
        assertEquals(listOf("e4", "evidence"), log.snapshot().map { it.id })
        // "Clear all data" takes it.
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertTrue("evidence" in dropped)
    }

    @Test
    fun aLogOfNothingButEvidenceStillKeepsToTheCap() {
        val log = EventLog(store(), cap = 2)
        log.record(event("a", 1_000L, EventKind.EVIDENCE))
        log.record(event("b", 2_000L, EventKind.EVIDENCE))
        log.record(event("c", 3_000L, EventKind.EVIDENCE))
        assertEquals(listOf("c", "b"), log.snapshot().map { it.id })
    }
}
