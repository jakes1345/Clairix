package app.clairix

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import app.clairix.security.Wiper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

/**
 * A small JSON-backed key/value store written atomically to the app's private
 * files directory.
 *
 * IMSI-catcher detection needs to know what "normal" looks like at the places
 * you actually go, and the timeline has to survive the process being killed and
 * the phone rebooting. Public tower databases need a key and a connection; a
 * baseline you build yourself needs neither and is accurate for your own routes.
 * So this state persists across runs, on disk, and never leaves the device.
 *
 * Writes are batched — the cell baseline updates every poll and the flash does
 * not need to hear about each one — but always flushed on stop and on a
 * confirmed detection, the two moments where losing data would matter.
 *
 * Threading contract: the owner of a store mutates [json] in place under a lock
 * of its own, and calls [touch], [flush] and [replace] while holding that same
 * lock. Those calls serialise the JSON on the calling thread, unless [touch] is
 * given that lock as its guard: then the serialisation runs on the IO dispatcher
 * inside the guard, which keeps a large document off the main thread. Either way
 * the file writer only ever sees a finished string.
 *
 * On disk the document is sealed by [cipher] (see [KeystoreStoreCipher]): the
 * file starts with [MAGIC], then the ciphertext. A file that starts with `{`
 * instead was written by a version that kept these stores in the clear; it is
 * read once, and the sealed form is written over it straight away.
 */
class Store(
    private val file: File,
    /** Where background writes run. A parameter so tests can hold them back. */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cipher: StoreCipher = KeystoreStoreCipher,
    /** Told the file's name when it could not be read and was set aside. A parameter so tests can listen. */
    private val onSetAside: (String) -> Unit = { name -> reportSetAside(name) }
) {

    constructor(context: Context, filename: String) : this(File(context.filesDir, filename))

    companion object {
        /** Marks a sealed file. Plain JSON never starts with these bytes. */
        val MAGIC: ByteArray = "CLXS1\n".toByteArray(Charsets.US_ASCII)

        /** Appended to the name of a file that could not be read and was moved out of the way. */
        const val SET_ASIDE_SUFFIX = ".unreadable"

        /** How long a set-aside file is kept before [pruneSetAside] deletes it. */
        val SET_ASIDE_KEEP_MS: Long = TimeUnit.DAYS.toMillis(30)

        private const val OPEN_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 250L
        /** filesDir, its own folders (the speed cache's) and one below. */
        private const val PRUNE_DEPTH = 3

        /**
         * Whether [e], thrown while opening a store, means the file is damaged for
         * good: an AES-GCM tag that does not match (corrupted, or sealed under a key
         * that no longer exists), a sealed blob too short to hold anything, a
         * document that does not parse, a key the Keystore has invalidated. Anything else (the Keystore refusing or not
         * answering, an I/O error) may pass, and is not taken for damage.
         */
        internal fun isDamage(e: Throwable): Boolean = when (e) {
            // A key the Keystore says is gone for good opens nothing, now or after a restart.
            is JSONException, is AEADBadTagException, is KeyPermanentlyInvalidatedException -> true
            is KeyStoreException, is ProviderException, is UnrecoverableEntryException, is IllegalStateException -> false
            // KeystoreStoreCipher's own check that a blob is long enough to hold an IV and a tag.
            is IllegalArgumentException -> true
            else -> false
        }

        /**
         * What a store that set a file aside does about it: a plain alert through
         * [Registry.publishAlert], at most once a day per file.
         */
        internal fun reportSetAside(name: String) {
            val now = System.currentTimeMillis()
            Registry.publishAlert(
                Alert(
                    id = "store_set_aside:$name@$now",
                    ts = now,
                    severity = Severity.MEDIUM,
                    title = "Saved scanner data could not be read",
                    detail = "Saved scanner data could not be read and was set aside. Clairix has started that part " +
                        "afresh and keeps what it learns from now on as usual. The unreadable copy is deleted after 30 days.",
                    kind = EventKind.PLATFORM,
                    dedupeKey = "store_set_aside:$name"
                ),
                dedupeWindowMs = TimeUnit.DAYS.toMillis(1)
            )
        }

        /**
         * Deletes the files stores set aside ([SET_ASIDE_SUFFIX]) under [dir] that
         * are older than [SET_ASIDE_KEEP_MS] at [now], and returns how many went.
         * Nothing can read them, and one from a version that kept stores in the
         * clear is plaintext.
         */
        fun pruneSetAside(dir: File, now: Long = System.currentTimeMillis()): Int {
            val old = dir.walkTopDown().maxDepth(PRUNE_DEPTH)
                .filter { it.isFile && it.name.endsWith(SET_ASIDE_SUFFIX) && now - it.lastModified() > SET_ASIDE_KEEP_MS }
                .toList()
            return old.count { it.delete() }
        }
    }

    /**
     * Guards [data], [dirty], [lastFlush] and the snapshot sequence. It does not
     * guard the *contents* of [data]: owners mutate [json] in place under a lock of
     * their own (IMSICatcher's monitor, LocationHistory's lock, …), which this class
     * cannot see.
     */
    private val lock = Any()
    /** Serialises the file writes themselves, so two snapshots never interleave on disk. */
    private val writeLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var data: JSONObject? = null
    private var dirty = false
    private var lastFlush = 0L
    /** Sequence number of the newest snapshot taken; a write of an older one is dropped. */
    private var snapshotSeq = 0L
    /** Sequence number of the newest snapshot on disk. Guarded by [writeLock]. */
    private var writtenSeq = 0L
    /**
     * The newest snapshot taken and not yet known to be on disk. [flush] writes it
     * itself rather than trusting a background write that may never run.
     */
    private var pending: Pair<Long, String>? = null
    /**
     * False once a load found the file but the Keystore would not open it, however
     * often it was asked: nothing is written for the rest of the process, so no
     * write buries a file the next start will probably read. Guarded by [lock].
     */
    private var writable = true

    fun load(fallback: () -> JSONObject): JSONObject {
        var setAside = false
        val reseal = synchronized(lock) {
            data?.let { return it }
            var legacy = false
            val loaded = when (val read = readPatiently()) {
                is Read.Absent -> fallback()
                is Read.Opened -> {
                    legacy = read.legacy
                    read.json
                }
                is Read.Damaged -> {
                    // Damaged for good. Set it aside, once, rather than let the next
                    // write bury the owner's baseline and location history under an
                    // empty document; and say so.
                    runCatching { file.renameTo(setAsideTarget()) }
                    setAside = true
                    fallback()
                }
                is Read.Unavailable -> {
                    // The Keystore would not answer. The file stays where it is, and
                    // this store writes nothing over it until the next start reads it.
                    writable = false
                    fallback()
                }
            }
            data = loaded
            // A document found in the clear goes back to disk sealed before anything
            // else happens to it, so the plaintext does not sit there until the next
            // routine write.
            if (legacy) {
                dirty = true
                snapshotLocked()
            } else {
                null
            }
        }
        if (setAside) runCatching { onSetAside(file.name) }
        if (reseal != null) ioScope.launch { write(reseal.first, reseal.second) }
        return data!!
    }

    /** Where a damaged file goes: `<name>.unreadable`, or a dated name beside an earlier one, which it must not replace. */
    private fun setAsideTarget(): File {
        val first = File(file.parentFile, "${file.name}$SET_ASIDE_SUFFIX")
        return if (!first.exists()) first else File(file.parentFile, "${file.name}.${System.currentTimeMillis()}$SET_ASIDE_SUFFIX")
    }

    /** What reading the file found. */
    private sealed interface Read {
        data object Absent : Read
        class Opened(val json: JSONObject, val legacy: Boolean) : Read
        /** Damaged: no retry will ever open it ([isDamage]). */
        data object Damaged : Read
        /** The Keystore did not answer; the file may well be fine. */
        data object Unavailable : Read
    }

    /**
     * Reads and opens the file. A Keystore that fails to answer is asked again a
     * few times over a second and a half, as [app.clairix.comms.CommsDb] asks for
     * its key, and is never taken for damage: setting the file aside then would
     * reset the cell baseline or the timeline over one bad Keystore call.
     */
    private fun readPatiently(): Read {
        repeat(OPEN_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_BACKOFF_MS * attempt)
            val read = runCatching {
                if (!file.exists()) return Read.Absent
                val bytes = file.readBytes()
                if (isSealed(bytes)) {
                    Read.Opened(JSONObject(cipher.open(bytes.copyOfRange(MAGIC.size, bytes.size))), legacy = false)
                } else {
                    Read.Opened(JSONObject(String(bytes, Charsets.UTF_8)), legacy = true)
                }
            }
            read.getOrNull()?.let { return it }
            if (read.exceptionOrNull()?.let { isDamage(it) } == true) return Read.Damaged
        }
        return Read.Unavailable
    }

    private fun isSealed(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    val json: JSONObject
        get() = data ?: load { JSONObject() }

    /**
     * Marks the store as having unwritten changes without scheduling anything.
     * A caller that mutates [json] directly and then wants it on disk needs this —
     * [flush] deliberately does nothing when there is nothing to write.
     */
    fun markDirty() {
        synchronized(lock) { dirty = true }
    }

    /**
     * Marks the store dirty and, if enough time has passed since the last write,
     * writes it on the IO dispatcher.
     *
     * The JSON is serialised *here*, on the calling thread, and only the finished
     * string is handed to the IO writer. The owner mutates [json] under its own lock
     * and calls this from inside that lock, so the snapshot cannot race a mutation.
     * Serialising on the IO thread instead — as this used to — walked the owner's
     * HashMaps while the owner was changing them; the resulting
     * ConcurrentModificationException was swallowed by the write's catch and the
     * write silently lost.
     *
     * With a [guard] — the lock the owner mutates [json] under — the serialisation
     * moves to the IO dispatcher and runs inside that lock instead. For an owner on
     * the main thread with a large document, so the main thread only marks the store
     * dirty.
     */
    fun touch(minIntervalMs: Long = 5_000L, guard: Any? = null) {
        val snapshot = synchronized(lock) {
            dirty = true
            val now = System.currentTimeMillis()
            if (now - lastFlush < minIntervalMs) return
            // Counted from the snapshot, not the write, so a burst of touches does not
            // launch a write each while the first is still on its way to disk.
            if (guard != null) {
                lastFlush = now
                null
            } else {
                val taken = snapshotLocked() ?: return
                lastFlush = now
                taken
            }
        }
        if (snapshot != null) {
            ioScope.launch { write(snapshot.first, snapshot.second) }
        } else {
            ioScope.launch {
                // Lock order guard → lock, as when the owner calls in holding its guard.
                val taken = synchronized(guard!!) { synchronized(lock) { snapshotLocked() } } ?: return@launch
                write(taken.first, taken.second)
            }
        }
    }

    /**
     * Writes the newest state now, on the calling thread, and returns once it is on
     * disk. Changes made since the last snapshot are serialised here — call this
     * while holding the lock the owner mutates [json] under. With none, a snapshot
     * that [touch] took but whose background write has not landed yet is written
     * here instead of being left to a write that may never run.
     */
    fun flush() {
        val snapshot = synchronized(lock) { snapshotLocked() ?: pending } ?: return
        write(snapshot.first, snapshot.second)
    }

    /**
     * Replaces the contents with [fresh] and writes immediately.
     *
     * "Clear all data" used to just delete the files, which did nothing while the
     * scanner was running: this object still held the parsed JSON and wrote it
     * straight back on the next flush.
     */
    fun replace(fresh: JSONObject) {
        val snapshot = synchronized(lock) {
            data = fresh
            dirty = true
            lastFlush = 0L
            snapshotLocked()
        } ?: return
        write(snapshot.first, snapshot.second)
    }

    /** Serialises [data] if there is anything to write, clearing [dirty]. Caller holds [lock]. */
    private fun snapshotLocked(): Pair<Long, String>? {
        val d = data ?: return null
        if (!dirty) return null
        // A wipe is deleting this file; writing it back would undo that.
        if (Wiper.isActive) return null
        // The file on disk could not be opened just now; it is not this store's to overwrite.
        if (!writable) return null
        val text = try {
            d.toString()
        } catch (_: RuntimeException) {
            // Only reachable when a caller broke the contract above and mutated the
            // JSON without holding its own lock. Stay dirty; the next touch retries.
            return null
        }
        dirty = false
        snapshotSeq++
        val taken = snapshotSeq to text
        pending = taken
        return taken
    }

    private fun write(seq: Long, text: String) {
        synchronized(writeLock) {
            // A later snapshot already reached the disk; this one would roll it back.
            if (seq <= writtenSeq) return
            if (Wiper.isActive) return
            try {
                file.parentFile?.mkdirs()
                val sealed = MAGIC + cipher.seal(text)
                // Write to a temp file and rename so a crash mid-write cannot leave a
                // half-written, unparseable baseline behind.
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(sealed)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(sealed)
                    tmp.delete()
                }
                writtenSeq = seq
                synchronized(lock) {
                    if ((pending?.first ?: Long.MAX_VALUE) <= seq) pending = null
                }
            } catch (_: Exception) {
                // A failed write is not worth crashing the scanner over. Mark the store
                // dirty again so the next touch() or flush() takes a fresh snapshot.
                synchronized(lock) {
                    dirty = true
                    lastFlush = 0L
                }
            }
        }
    }
}
