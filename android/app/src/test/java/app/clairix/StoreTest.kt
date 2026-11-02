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

    /** Opens nothing until it has failed [failures] times the way a Keystore that does not answer fails. */
    private class FlakyCipher(private var failures: Int) : StoreCipher {
        var opens = 0
        override fun seal(text: String): ByteArray = FlipCipher.seal(text)
        override fun open(blob: ByteArray): String {
            opens++
            if (failures > 0) {
                failures--
                throw java.security.ProviderException("Keystore operation failed")
            }
            return FlipCipher.open(blob)
        }
    }

    private fun sealedFile(json: JSONObject) {
        file.writeBytes(Store.MAGIC + FlipCipher.seal(json.toString()))
    }

    private val setAside get() = File(file.parentFile, "${file.name}${Store.SET_ASIDE_SUFFIX}")

    @Test
    fun aCorruptSealedFileIsSetAsideOnceAndReported() {
        file.writeBytes(Store.MAGIC + "not a sealed document".toByteArray())
        val reported = mutableListOf<String>()
        val s = Store(file, held.asCoroutineDispatcher(), FlipCipher) { reported += it }
        assertTrue(s.load { JSONObject().put("fresh", true) }.getBoolean("fresh"))
        assertFalse(file.exists())
        assertTrue(setAside.exists())
        assertEquals(listOf("s.json"), reported)
        // A second load of the same store, and of a new one, find nothing to set aside.
        s.load { JSONObject() }
        Store(file, held.asCoroutineDispatcher(), FlipCipher) { reported += it }.load { JSONObject() }
        assertEquals(1, reported.size)
    }

    @Test
    fun aTransientCipherFailureLoadsOnRetry() {
        sealedFile(JSONObject().put("baseline", 42))
        val cipher = FlakyCipher(failures = 2)
        val reported = mutableListOf<String>()
        val s = Store(file, held.asCoroutineDispatcher(), cipher) { reported += it }
        assertEquals(42, s.load { JSONObject() }.getInt("baseline"))
        assertEquals(3, cipher.opens)
        assertTrue(file.exists())
        assertFalse(setAside.exists())
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aCipherThatNeverAnswersLeavesTheFileInPlaceAndUnwritten() {
        sealedFile(JSONObject().put("baseline", 42))
        val before = file.readBytes()
        val s = Store(file, held.asCoroutineDispatcher(), FlakyCipher(failures = Int.MAX_VALUE)) { error("not damaged") }
        val json = s.load { JSONObject() }
        assertEquals(0, json.length())
        assertFalse(setAside.exists())
        // The fallback document is used, and nothing it holds reaches the disk.
        synchronized(owner) {
            json.put("v", 1)
            s.touch(minIntervalMs = 0)
            s.markDirty()
            s.flush()
            s.replace(JSONObject().put("v", 2))
        }
        held.runAll()
        assertTrue(before.contentEquals(file.readBytes()))
        // The next start, with a Keystore that answers, reads it.
        assertEquals(42, Store(file, cipher = FlipCipher).load { JSONObject() }.getInt("baseline"))
    }

    /** Opens like [FlipCipher] and seals nothing, as a Keystore that will not seal just now. */
    private object NoSealCipher : StoreCipher {
        override fun seal(text: String): ByteArray = throw java.security.ProviderException("Keystore operation failed")
        override fun open(blob: ByteArray): String = FlipCipher.open(blob)
    }

    @Test
    fun theOwnersClearReachesTheDiskEvenWhenTheFileCouldNotBeRead() {
        sealedFile(JSONObject().put("history", "where she went"))
        val s = Store(file, held.asCoroutineDispatcher(), FlakyCipher(failures = Int.MAX_VALUE)) { error("not damaged") }
        s.load { JSONObject() }
        // "Clear all data": without discardUnread the cleared document stayed in memory
        // and the history came back at the next start.
        assertTrue(s.discardUnread())
        s.replace(JSONObject().put("cleared", true))
        held.runAll()
        val next = Store(file, cipher = FlipCipher).load { JSONObject() }
        assertFalse(next.has("history"))
        assertTrue(next.getBoolean("cleared"))
        assertFalse("said once", s.discardUnread())
    }

    @Test
    fun isWrittenOnlyOnceTheNewestStateIsOnDisk() {
        val s = Store(file, held.asCoroutineDispatcher(), FlipCipher)
        synchronized(owner) {
            s.load { JSONObject() }.put("v", 1)
            s.markDirty()
            assertFalse(s.isWritten())
            s.flush()
        }
        assertTrue(s.isWritten())

        val failing = Store(File(file.parentFile, "other.json"), held.asCoroutineDispatcher(), NoSealCipher)
        synchronized(owner) {
            failing.load { JSONObject() }.put("v", 1)
            failing.markDirty()
            failing.flush()
        }
        assertFalse("flush() said nothing, and the write failed", failing.isWritten())

        sealedFile(JSONObject().put("v", 3))
        val unread = Store(file, held.asCoroutineDispatcher(), FlakyCipher(failures = Int.MAX_VALUE)) { }
        unread.load { JSONObject() }
        assertFalse("a file this run could not read is not this store's state", unread.isWritten())
    }

    @Test
    fun aSetAsideFileIsNotOverwrittenByTheNextWrite() {
        file.writeText("{ this is not json")
        val s = Store(file, held.asCoroutineDispatcher(), FlipCipher) { }
        synchronized(owner) {
            s.load { JSONObject() }.put("v", 5)
            s.markDirty()
            s.flush()
        }
        held.runAll()
        assertEquals("{ this is not json", setAside.readText())
        assertEquals(5, onDisk().getInt("v"))
        // A second damaged file later replaces nothing that was set aside before it.
        file.writeBytes(Store.MAGIC + "damaged again".toByteArray())
        Store(file, held.asCoroutineDispatcher(), FlipCipher) { }.load { JSONObject() }
        assertEquals("{ this is not json", setAside.readText())
        val asideNow = file.parentFile!!.listFiles()!!.filter { it.name.endsWith(Store.SET_ASIDE_SUFFIX) }
        assertEquals(2, asideNow.size)
    }

    @Test
    fun damageAndAKeystoreThatDoesNotAnswerAreToldApart() {
        assertTrue(Store.isDamage(javax.crypto.AEADBadTagException("tag mismatch")))
        assertTrue(Store.isDamage(org.json.JSONException("bad")))
        assertTrue(Store.isDamage(IllegalArgumentException("ciphertext truncated")))
        assertFalse(Store.isDamage(java.security.KeyStoreException("busy")))
        assertFalse(Store.isDamage(java.security.ProviderException("Keystore operation failed")))
        assertFalse(Store.isDamage(java.security.UnrecoverableKeyException("locked")))
        assertFalse(Store.isDamage(IllegalStateException("not initialised")))
        assertFalse(Store.isDamage(java.io.IOException("read failed")))
    }

    @Test
    fun setAsideFilesGoAfterThirtyDays() {
        val dir = file.parentFile!!
        val now = System.currentTimeMillis()
        val old = File(dir, "timeline.json${Store.SET_ASIDE_SUFFIX}").apply { writeText("x"); setLastModified(now - Store.SET_ASIDE_KEEP_MS - 60_000) }
        val recent = File(dir, "cells.json${Store.SET_ASIDE_SUFFIX}").apply { writeText("x"); setLastModified(now - 60_000) }
        val nested = File(File(dir, "speeds").apply { mkdirs() }, "s.json${Store.SET_ASIDE_SUFFIX}")
            .apply { writeText("x"); setLastModified(now - Store.SET_ASIDE_KEEP_MS - 60_000) }
        val ordinary = File(dir, "kept.json").apply { writeText("x"); setLastModified(0) }
        assertEquals(2, Store.pruneSetAside(dir, now))
        assertFalse(old.exists())
        assertFalse(nested.exists())
        assertTrue(recent.exists())
        assertTrue(ordinary.exists())
    }
}
