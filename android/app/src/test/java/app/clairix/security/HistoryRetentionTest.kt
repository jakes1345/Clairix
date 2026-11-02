package app.clairix.security

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.TimelineEvent
import app.clairix.analysis.EventLog
import app.clairix.detect.VehicleTrips
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The history retention's sweep: every store it promises to reach is reached
 * with the same cutoff (the hidden-camera sweep's findings among them), the
 * exported reports and the cell baseline are seen to whatever the choice, "keep
 * everything" deletes no history, one failing store does not stop the rest, and
 * the timeline and the vehicle trips, seeded across the cutoff, keep exactly what
 * is inside it, on disk as well as in memory.
 */
class HistoryRetentionTest {

    private val dir: File = Files.createTempDirectory("retention").toFile()
    private val now = 1_793_450_117_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Records what each store was asked to do; a named store can be made to fail. */
    private open class RecordingStores(private val failing: Set<String> = emptySet()) : HistoryRetention.Stores {
        val calls = LinkedHashMap<String, Long?>()

        protected fun call(name: String, value: Long?) {
            calls[name] = value
            if (name in failing) error("$name is unreadable")
        }

        override fun exports(now: Long) = call("exports", now)
        override fun cellBaseline(cutoff: Long?, now: Long) = call("cellBaseline", cutoff)
        override fun locations(cutoff: Long) = call("locations", cutoff)
        override fun trips(cutoff: Long) = call("trips", cutoff)
        override fun unlocks(cutoff: Long) = call("unlocks", cutoff)
        override fun tiles(cutoff: Long) = call("tiles", cutoff)
        override fun typicalSpeeds(cutoff: Long, now: Long) = call("typicalSpeeds", cutoff)
        override fun recordings(cutoff: Long) = call("recordings", cutoff)
        override fun cameraSweep(cutoff: Long) = call("cameraSweep", cutoff)
        override fun timeline(cutoff: Long) = call("timeline", cutoff)
    }

    private val everyStore = listOf(
        "exports", "cellBaseline", "locations", "trips", "unlocks", "tiles", "typicalSpeeds", "recordings", "cameraSweep", "timeline",
    )

    @Test
    fun everyStoreIsReachedWithTheSameCutoff() {
        val stores = RecordingStores()
        HistoryRetention.sweep(stores, days = 30, now = now)
        assertEquals(everyStore, stores.calls.keys.toList())
        val cutoff = now - 30 * day
        assertEquals(now, stores.calls["exports"])
        for (name in everyStore - "exports") assertEquals(name, cutoff, stores.calls[name])
    }

    @Test
    fun eachChoiceCutsWhereItSays() {
        for (days in HistoryRetention.CHOICES - HistoryRetention.FOREVER) {
            val stores = RecordingStores()
            HistoryRetention.sweep(stores, days, now)
            assertEquals(now - days * day, stores.calls["cameraSweep"])
        }
    }

    @Test
    fun keepingEverythingDeletesNoHistoryButStillTidiesReportsAndTheBaseline() {
        val stores = RecordingStores()
        HistoryRetention.sweep(stores, HistoryRetention.FOREVER, now)
        assertEquals(listOf("exports", "cellBaseline"), stores.calls.keys.toList())
        // Every tower kept; only the exact times thinned (BaselineRetention).
        assertTrue(stores.calls.containsKey("cellBaseline"))
        assertNull(stores.calls["cellBaseline"])
    }

    @Test
    fun aStoreThatFailsDoesNotStopTheOthers() {
        val stores = RecordingStores(failing = setOf("exports", "locations", "tiles", "cameraSweep"))
        HistoryRetention.sweep(stores, days = 7, now = now)
        assertEquals(everyStore, stores.calls.keys.toList())
    }

    private fun event(id: String, ts: Long) =
        TimelineEvent(id = id, kind = EventKind.CAMERA, ts = ts, title = id, detail = "", severity = Severity.MEDIUM)

    @Test
    fun theTimelineAndTheTripsSeededAcrossTheCutoffKeepOnlyWhatIsInsideIt() {
        val timelineFile = File(dir, "timeline.json")
        val tripsFile = File(dir, "vehicle_trips.json")
        fun timelineStore() = Store(timelineFile, Dispatchers.Unconfined, TestStoreCipher)
        fun tripsStore() = Store(tripsFile, Dispatchers.Unconfined, TestStoreCipher)

        val dropped = ArrayList<String>()
        val log = EventLog(timelineStore()) { gone -> gone.forEach { dropped += it.id } }
        for ((id, age) in listOf("a" to 45, "b" to 31, "c" to 29, "d" to 2)) log.record(event(id, now - age * day))
        // Exactly at the cutoff is inside it.
        log.record(event("edge", now - 30 * day))

        val drives = VehicleTrips(tripsStore())
        for (age in listOf(40, 31, 10, 1)) {
            val end = now - age * day
            drives.beginTrip(end - 20 * 60_000L)
            drives.endTrip(end)
        }

        val stores = object : RecordingStores() {
            override fun trips(cutoff: Long) {
                call("trips", cutoff)
                drives.pruneOlderThan(cutoff)
            }

            override fun timeline(cutoff: Long) {
                call("timeline", cutoff)
                log.pruneOlderThan(cutoff)
            }
        }
        HistoryRetention.sweep(stores, days = 30, now = now)

        assertEquals(listOf("d", "c", "edge"), log.snapshot().map { it.id })
        // A photo kept on an event goes with it: the log hands every dropped event over.
        assertEquals(setOf("a", "b"), dropped.toSet())
        assertEquals(listOf(now - 10 * day, now - day), drives.trips().map { it.end })

        // Reopened from disk, as the next process sees them.
        assertEquals(listOf("d", "c", "edge"), EventLog(timelineStore()).snapshot().map { it.id })
        assertEquals(listOf(now - 10 * day, now - day), VehicleTrips(tripsStore()).trips().map { it.end })
    }
}
