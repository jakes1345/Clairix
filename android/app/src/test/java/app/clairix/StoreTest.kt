package app.clairix

import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Background writes never roll the file back, flush() always leaves the newest
 * state on disk, and nothing reaches the disk in the clear.
 */
class StoreTest {

    /** Reversible and obviously not plaintext: every byte is flipped. The Keystore is not on the JVM. */
    private object FlipCipher : StoreCipher {
        override fun seal(text: String): ByteArray = text.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0xFF).toByte() }.toByteArray()
        override fun open(blob: ByteArray): String = String(blob.map { (it.toInt() xor 0xFF).toByte() }.toByteArray(), Charsets.UTF_8)
    }

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
    private val store = Store(file, held.asCoroutineDispatcher(), FlipCipher)
    private val owner = Any()

    private fun onDisk(): JSONObject {
        val bytes = file.readBytes()
        assertTrue("file is not sealed", bytes.size > Store.MAGIC.size && bytes.copyOfRange(0, Store.MAGIC.size).contentEquals(Store.MAGIC))
        return JSONObject(FlipCipher.open(bytes.copyOfRange(Store.MAGIC.size, bytes.size)))
    }

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
        assertEquals(9, Store(file, cipher = FlipCipher).load { JSONObject() }.getInt("v"))
    }

    @Test
    fun nothingOnDiskIsPlaintext() {
        synchronized(owner) {
            store.load { JSONObject() }.put("secret", "home is at 51.5,-0.12")
            store.markDirty()
            store.flush()
        }
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("51.5"))
        assertEquals("home is at 51.5,-0.12", onDisk().getString("secret"))
    }

    @Test
    fun aPlaintextFileFromAnOlderVersionIsReadOnceAndResealed() {
        file.writeText("""{"v":4,"where":"plain"}""")
        val s = Store(file, held.asCoroutineDispatcher(), FlipCipher)
        assertEquals(4, s.load { JSONObject() }.getInt("v"))
        held.runAll()
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("plain"))
        assertEquals("plain", onDisk().getString("where"))
        assertEquals(4, Store(file, cipher = FlipCipher).load { JSONObject() }.getInt("v"))
    }
}
