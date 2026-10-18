package app.clairix

import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** Background writes never roll the file back, and flush() always leaves the newest state on disk. */
class StoreTest {

    /** Runs nothing until told to, so a test decides when the background write lands. */
    private class HeldExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(queue) { queue.addLast(command) } }
        fun runAll() {
            while (true) {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    private val held = HeldExecutor()
    private val file = File(Files.createTempDirectory("store").toFile().apply { deleteOnExit() }, "s.json")
    private val store = Store(file, held.asCoroutineDispatcher())
    private val owner = Any()

    private fun onDisk(): JSONObject = JSONObject(file.readText())

    @Test
    fun anOlderBackgroundWriteCannotOverwriteANewerOne() {
        synchronized(owner) {
            store.load { JSONObject() }.put("v", 1)
            store.touch(minIntervalMs = 0)          // snapshot of v=1, write held back
            store.json.put("v", 2)
            store.touch(minIntervalMs = 60_000)     // marks dirty; too soon for another write
            store.flush()                           // v=2 written now
        }
        assertEquals(2, onDisk().getInt("v"))
        held.runAll()                               // the v=1 write finally runs
        assertEquals(2, onDisk().getInt("v"))
    }

    @Test
    fun flushWritesASnapshotWhoseBackgroundWriteHasNotRun() {
        synchronized(owner) {
            store.load { JSONObject() }.put("v", 7)
            store.touch(minIntervalMs = 0)
            assertFalse(file.exists())
            // Nothing changed since the snapshot; flush must still not return before it is on disk.
            store.flush()
        }
        assertEquals(7, onDisk().getInt("v"))
    }

    @Test
    fun aGuardedTouchSerialisesInTheBackgroundUnderTheOwnersLock() {
        synchronized(owner) {
            store.load { JSONObject() }.put("v", 1)
            store.touch(minIntervalMs = 0, guard = owner)
            store.json.put("v", 3)
        }
        assertFalse(file.exists())
        held.runAll()
        assertEquals(3, onDisk().getInt("v"))
    }

    @Test
    fun replaceIsWrittenAtOnceAndSurvivesALaterStaleWrite() {
        synchronized(owner) {
            store.load { JSONObject() }.put("v", 1)
            store.touch(minIntervalMs = 0)
            store.replace(JSONObject().put("v", 9))
        }
        held.runAll()
        assertEquals(9, onDisk().getInt("v"))
        assertEquals(9, Store(file).load { JSONObject() }.getInt("v"))
    }
}
