package app.clairix

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom

/** Audio to play, a piece at a time: 16-bit little-endian mono at [sampleRate]. */
interface PcmSource : Closeable {
    val sampleRate: Int

    /** The next piece, or null at the end. */
    fun next(): ByteArray?
}

/**
 * The radio scanner's recordings: one file per transmission, made only while the owner
 * has recording on (off by default, and off again when drive mode ends), sealed under the scanner's
 * store key ([KeystoreStoreCipher]) in the app's private no-backup storage, and kept to
 * [MAX_RECORDING_MS] each, [MAX_TOTAL_BYTES] in all (the oldest go first) and the history
 * retention ([app.clairix.security.HistoryRetention]).
 *
 * A file is "CXR1", then records of a type byte, a 4-byte length and a sealed blob: a
 * header (when, which frequency and channel, the sample rate), the audio a second at a
 * time, then a trailer that counts the seconds. Each blob is sealed to its file, its type
 * and its place, so pieces cannot be reordered, swapped between files or cut from the
 * middle without the file failing to open; a file with no trailer was cut short (the app
 * stopped mid-transmission) and plays as far as it got. Names are random: when a
 * transmission was heard, and on what frequency, is inside the sealed header. What the
 * file system itself shows is each file's size and when it was last written.
 *
 * Before recordings were sealed they were written as plain PCM to the app's external
 * storage; [findLegacy] finds what is left of those, and [importLegacy] seals them into
 * this store when the owner asks. Nothing deletes them unasked.
 */
class ScannerRecordings(
    private val dir: File,
    private val cipher: StoreCipher = KeystoreStoreCipher,
    private val random: SecureRandom = SecureRandom(),
) {

    /** One recording, as the list shows it. [readable] false: it would not open (a damaged file); it can still be deleted. */
    data class Info(
        val id: String,
        val startedAt: Long,
        val freqHz: Long,
        val channel: String,
        val durationMs: Long,
        val bytes: Long,
        /** False when the recording was cut short; it plays as far as it got. */
        val complete: Boolean,
        val readable: Boolean = true,
    )

    /** What the sealed header says. */
    data class Header(val startedAt: Long, val freqHz: Long, val channel: String, val sampleRate: Int)

    /** A recording from before they were sealed: plain PCM at [LEGACY_SAMPLE_RATE], named after when and on what frequency. */
    data class LegacyRecording(val file: File, val startedAt: Long, val freqHz: Long, val bytes: Long)

    /** A file as the cap and the retention see it. */
    internal data class Entry(val id: String, val bytes: Long, val modifiedAt: Long)

    /** The recording being written now; never listed, deleted, capped or pruned while it is. */
    @Volatile
    var activeId: String? = null
        private set

    /** Starts a recording; the caller writes it a block at a time and closes it. */
    fun begin(header: Header): Writer {
        dir.mkdirs()
        val id = newId()
        // Marked before its file exists, so the list never shows it half-made.
        activeId = id
        return try {
            Writer(id, File(dir, id + SUFFIX), header, MAX_RECORDING_MS)
        } catch (e: Exception) {
            if (activeId == id) activeId = null
            throw e
        }
    }

    /** Every finished recording, newest first. */
    fun list(): List<Info> {
        val active = activeId
        return files().filter { idOf(it) != active }.map { info(it) }.sortedByDescending { it.startedAt }
    }

    /** Opens recording [id] for playing, a second at a time. */
    fun open(id: String): Reader {
        require(ID_RE.matches(id)) { "not a recording: $id" }
        return Reader(id, File(dir, id + SUFFIX))
    }

    fun delete(id: String): Boolean {
        if (!ID_RE.matches(id) || id == activeId) return false
        val f = File(dir, id + SUFFIX)
        return !f.exists() || f.delete()
    }

    /** Deletes every finished recording; returns how many. */
    fun deleteAll(): Int {
        val active = activeId
        return files().count { idOf(it) != active && it.delete() }
    }

    /** Bytes the recordings take, the one being written included. */
    fun totalBytes(): Long = files().sumOf { it.length() }

    /** Deletes recordings last written before [cutoff], and import leftovers as old; returns how many recordings. */
    fun pruneOlderThan(cutoff: Long): Int {
        dir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) && f.lastModified() < cutoff }?.forEach { it.delete() }
        val doomed = olderThan(entries(), cutoff, activeId)
        return doomed.count { File(dir, it + SUFFIX).delete() }
    }

    /** Deletes the oldest recordings until the rest fit in [MAX_TOTAL_BYTES]; returns how many. */
    fun enforceCap(maxBytes: Long = MAX_TOTAL_BYTES): Int {
        val doomed = overCap(entries(), maxBytes, activeId)
        return doomed.count { File(dir, it + SUFFIX).delete() }
    }

    /**
     * Seals [legacy] recordings into this store, newest first, as many as fit within
     * [maxBytes] with what is already here; nothing is deleted to make room. Each plain
     * file is deleted once its sealed copy is complete; one that does not fit, or fails,
     * stays where it was. A moved recording keeps when it was made, and counts as written
     * [now] for the retention, so it is not deleted the moment it arrives. Returns how
     * many moved.
     */
    fun importLegacy(legacy: List<LegacyRecording>, now: Long = System.currentTimeMillis(), maxBytes: Long = MAX_TOTAL_BYTES): Int {
        dir.mkdirs()
        var total = totalBytes()
        var moved = 0
        for (r in legacy.sortedByDescending { it.startedAt }) {
            if (total + sealedEstimate(r.bytes, LEGACY_SAMPLE_RATE) > maxBytes) continue
            val id = newId()
            val tmp = File(dir, id + TMP_SUFFIX)
            val dest = File(dir, id + SUFFIX)
            try {
                val w = Writer(id, tmp, Header(r.startedAt, r.freqHz, "", LEGACY_SAMPLE_RATE), Long.MAX_VALUE)
                try {
                    FileInputStream(r.file).use { input ->
                        val buf = ByteArray(64 * 1024)
                        var carry = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            var off = 0
                            // Keep samples whole across reads: an odd read leaves a byte for the next.
                            if (carry >= 0 && n > 0) {
                                w.writeLe(byteArrayOf(carry.toByte(), buf[0]), 0, 2)
                                carry = -1
                                off = 1
                            }
                            val even = (n - off) and 1.inv()
                            w.writeLe(buf, off, even)
                            if (off + even < n) carry = buf[n - 1].toInt() and 0xFF
                        }
                    }
                } finally {
                    w.close()
                }
                if (!tmp.renameTo(dest)) throw IOException("the sealed copy could not be put in place")
                dest.setLastModified(now)
                total += dest.length()
                r.file.delete()
                moved++
            } catch (e: Exception) {
                tmp.delete()
            }
        }
        return moved
    }

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * One recording being written. Audio is gathered a second at a time and each second is
     * sealed and written as it fills, so a recording cut short loses at most its last second.
     */
    inner class Writer internal constructor(
        val id: String,
        private val file: File,
        val header: Header,
        maxMs: Long,
    ) : Closeable {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 128 * 1024))
        private val chunkBytes = pcmBytes(header.sampleRate, CHUNK_MS).toInt().coerceAtLeast(2) and 1.inv()
        private val maxBytes = if (maxMs == Long.MAX_VALUE) Long.MAX_VALUE else pcmBytes(header.sampleRate, maxMs)
        private val buf = ByteArray(chunkBytes)
        private var fill = 0
        private var chunks = 0
        private var written = 0L
        private var closed = false

        init {
            try {
                out.write(MAGIC_BYTES)
                writeRecord(out, TYPE_HEADER, cipher.sealBytes(encodeHeader(header), aad(id, TYPE_HEADER, 0)))
            } catch (e: Exception) {
                runCatching { out.close() }
                file.delete()
                throw e
            }
        }

        /** Whether the recording holds as much as it may ([MAX_RECORDING_MS]); the caller closes it. */
        val full: Boolean get() = written + fill >= maxBytes

        /** How much audio it holds. */
        val durationMs: Long get() = (written + fill) * 1000 / (header.sampleRate * 2L)

        /** Adds [pcm]; whatever would go past the limit is dropped. */
        fun write(pcm: ShortArray) {
            if (closed) return
            var i = 0
            while (i < pcm.size && !full) {
                val s = pcm[i].toInt()
                buf[fill] = s.toByte()
                buf[fill + 1] = (s shr 8).toByte()
                fill += 2
                i++
                if (fill == chunkBytes) flushChunk()
            }
        }

        /** Adds 16-bit little-endian audio; [len] is rounded down to whole samples. */
        internal fun writeLe(bytes: ByteArray, off: Int, len: Int) {
            if (closed) return
            var i = off
            val end = off + (len and 1.inv())
            while (i < end && !full) {
                buf[fill] = bytes[i]
                buf[fill + 1] = bytes[i + 1]
                fill += 2
                i += 2
                if (fill == chunkBytes) flushChunk()
            }
        }

        private fun flushChunk() {
            if (fill == 0) return
            writeRecord(out, TYPE_CHUNK, cipher.sealBytes(buf.copyOf(fill), aad(id, TYPE_CHUNK, chunks)))
            written += fill
            chunks++
            fill = 0
        }

        /** Seals what is left and the trailer, and closes the file; see [closeAndKeep]. */
        override fun close() {
            closeAndKeep()
        }

        /**
         * [close], saying whether anything was kept: a recording that held no audio at all
         * is deleted, and this returns false.
         */
        fun closeAndKeep(): Boolean {
            if (closed) return written > 0
            closed = true
            try {
                flushChunk()
                writeRecord(out, TYPE_TRAILER, cipher.sealBytes(encodeTrailer(chunks, written), aad(id, TYPE_TRAILER, 0)))
            } finally {
                runCatching { out.close() }
                if (activeId == id) activeId = null
            }
            if (written == 0L) {
                file.delete()
                return false
            }
            return true
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** One recording being played: its header at once, then a second of audio per [next]. */
    inner class Reader internal constructor(val id: String, file: File) : PcmSource {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file), 128 * 1024))
        val header: Header = try {
            readHeader()
        } catch (e: Exception) {
            runCatching { input.close() }
            throw e
        }
        private var index = 0
        private var done = false

        private fun readHeader(): Header {
            val magic = ByteArray(MAGIC_BYTES.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC_BYTES)) throw IOException("not a recording")
            val (type, blob) = readRecord(input, SMALL_RECORD_MAX) ?: throw IOException("the recording has no header")
            if (type != TYPE_HEADER) throw IOException("the recording has no header")
            return decodeHeader(cipher.openBytes(blob, aad(id, TYPE_HEADER, 0)))
        }

        override val sampleRate: Int get() = header.sampleRate

        /** The next second of audio; null at the trailer, or where a cut-short file stops. Throws if a piece will not open. */
        override fun next(): ByteArray? {
            if (done) return null
            val record = readRecord(input, maxChunkRecord(header.sampleRate))
            if (record == null) {
                done = true
                return null
            }
            val (type, blob) = record
            return when (type) {
                TYPE_CHUNK -> cipher.openBytes(blob, aad(id, TYPE_CHUNK, index)).also { index++ }
                TYPE_TRAILER -> {
                    val (chunks, _) = decodeTrailer(cipher.openBytes(blob, aad(id, TYPE_TRAILER, 0)))
                    if (chunks != index) throw IOException("the recording is missing pieces")
                    done = true
                    null
                }
                else -> throw IOException("the recording is damaged")
            }
        }

        override fun close() {
            runCatching { input.close() }
        }
    }

    /** Plays a recording from before they were sealed, straight from its plain file. */
    class PlainReader(file: File) : PcmSource {
        private val input = BufferedInputStream(FileInputStream(file), 128 * 1024)
        private val piece = pcmBytes(LEGACY_SAMPLE_RATE, CHUNK_MS).toInt()
        override val sampleRate: Int get() = LEGACY_SAMPLE_RATE

        override fun next(): ByteArray? {
            val buf = ByteArray(piece)
            var n = 0
            while (n < piece) {
                val r = input.read(buf, n, piece - n)
                if (r < 0) break
                n += r
            }
            val whole = n and 1.inv()
            return if (whole == 0) null else buf.copyOf(whole)
        }

        override fun close() {
            runCatching { input.close() }
        }
    }

    // ── The list ───────────────────────────────────────────────────────────

    private fun info(file: File): Info {
        val id = idOf(file)
        val bytes = file.length()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC_BYTES.size)
                raf.readFully(magic)
                if (!magic.contentEquals(MAGIC_BYTES)) throw IOException("not a recording")
                if (raf.readByte().toInt() != TYPE_HEADER) throw IOException("no header")
                val headerLen = raf.readInt()
                if (headerLen !in 1..SMALL_RECORD_MAX) throw IOException("bad header")
                val headerBlob = ByteArray(headerLen).also { raf.readFully(it) }
                val header = decodeHeader(cipher.openBytes(headerBlob, aad(id, TYPE_HEADER, 0)))
                var chunks = 0
                var trailer: Pair<Int, Long>? = null
                val maxChunk = maxChunkRecord(header.sampleRate)
                while (true) {
                    val left = raf.length() - raf.filePointer
                    if (left < 5) break
                    val type = raf.readByte().toInt()
                    val len = raf.readInt()
                    if (len < 0 || len > maxChunk || raf.length() - raf.filePointer < len) break
                    if (type == TYPE_CHUNK) {
                        raf.seek(raf.filePointer + len)
                        chunks++
                    } else if (type == TYPE_TRAILER && len <= SMALL_RECORD_MAX) {
                        val blob = ByteArray(len).also { raf.readFully(it) }
                        trailer = decodeTrailer(cipher.openBytes(blob, aad(id, TYPE_TRAILER, 0)))
                        break
                    } else {
                        break
                    }
                }
                val t = trailer
                val complete = t != null && t.first == chunks
                val durationMs = if (t != null && complete) t.second * 1000 / (header.sampleRate * 2L) else chunks * CHUNK_MS
                Info(id, header.startedAt, header.freqHz, header.channel, durationMs, bytes, complete)
            }
        } catch (e: Exception) {
            Info(id, file.lastModified(), 0L, "", 0L, bytes, complete = false, readable = false)
        }
    }

    // ── Files ──────────────────────────────────────────────────────────────

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) && ID_RE.matches(f.name.removeSuffix(SUFFIX)) }
            ?.toList().orEmpty()

    private fun entries(): List<Entry> = files().map { Entry(idOf(it), it.length(), it.lastModified()) }

    private fun idOf(f: File): String = f.name.removeSuffix(SUFFIX)

    private fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        val out = StringBuilder(32)
        for (x in b) {
            out.append(HEX[(x.toInt() shr 4) and 0xF])
            out.append(HEX[x.toInt() and 0xF])
        }
        return out.toString()
    }

    companion object {
        /** The most one recording holds: a channel left talking (a stuck carrier, a broadcast) must not fill the phone. */
        const val MAX_RECORDING_MS = 10 * 60_000L

        /** The most all recordings together take; past it the oldest are deleted. */
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

        /** Audio is sealed a second at a time. */
        const val CHUNK_MS = 1_000L

        /** What the recorder wrote before recordings were sealed: [FmDemodulator.OUTPUT_RATE], 16-bit mono. */
        const val LEGACY_SAMPLE_RATE = FmDemodulator.OUTPUT_RATE

        private val MAGIC_BYTES = "CXR1".toByteArray(Charsets.US_ASCII)
        private val HEX = "0123456789abcdef".toCharArray()
        private const val SUFFIX = ".rec"
        private const val TMP_SUFFIX = ".rec.tmp"
        private val ID_RE = Regex("^[0-9a-f]{32}$")
        private val LEGACY_NAME = Regex("^(\\d{10,})_(\\d+)\\.raw$")
        private const val TYPE_HEADER = 'H'.code
        private const val TYPE_CHUNK = 'C'.code
        private const val TYPE_TRAILER = 'T'.code
        /** A header or trailer record is never bigger than this. */
        private const val SMALL_RECORD_MAX = 4 * 1024
        /** What sealing adds to a second of audio, with room to spare (AES-GCM adds 28 bytes). */
        private const val SEAL_OVERHEAD_MAX = 1024

        fun dir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "scanner")

        fun of(context: Context): ScannerRecordings = ScannerRecordings(dir(context))

        /** For [app.clairix.security.HistoryRetention]: deletes recordings last written before [cutoff]. */
        fun pruneOlderThan(context: Context, cutoff: Long): Int = of(context).pruneOlderThan(cutoff)

        /** Where the recorder used to write: the app's external files ("scanner"), or its files directory when there was none. */
        fun legacyDirs(context: Context): List<File> {
            val app = context.applicationContext
            return listOfNotNull(app.getExternalFilesDir(null)?.let { File(it, "scanner") }, app.filesDir)
        }

        /** The plain recordings left in [dirs], newest first. */
        fun findLegacy(dirs: List<File>): List<LegacyRecording> = dirs.flatMap { d ->
            d.listFiles { f -> f.isFile && LEGACY_NAME.matches(f.name) }?.toList().orEmpty().mapNotNull { f ->
                val m = LEGACY_NAME.matchEntire(f.name) ?: return@mapNotNull null
                val at = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                LegacyRecording(f, at, m.groupValues[2].toLongOrNull() ?: 0L, f.length())
            }
        }.distinctBy { it.file.absolutePath }.sortedByDescending { it.startedAt }

        /** Bytes of 16-bit mono audio at [sampleRate] over [ms]. */
        fun pcmBytes(sampleRate: Int, ms: Long): Long = sampleRate.toLong() * 2 * ms / 1000

        /** What [pcm] bytes of audio take once sealed, at most. */
        internal fun sealedEstimate(pcm: Long, sampleRate: Int): Long {
            val chunk = pcmBytes(sampleRate, CHUNK_MS).coerceAtLeast(2)
            val chunks = (pcm + chunk - 1) / chunk
            return MAGIC_BYTES.size + 2L * (5 + SMALL_RECORD_MAX) + pcm + chunks * (5 + SEAL_OVERHEAD_MAX)
        }

        /**
         * Which recordings to delete, oldest first, so the rest fit in [maxBytes]. [keep], the
         * one being written, is never chosen, and counts toward the total.
         */
        internal fun overCap(files: List<Entry>, maxBytes: Long, keep: String?): List<String> {
            var total = files.sumOf { it.bytes }
            val out = ArrayList<String>()
            for (f in files.sortedWith(compareBy<Entry>({ it.modifiedAt }, { it.id }))) {
                if (total <= maxBytes) break
                if (f.id == keep) continue
                out += f.id
                total -= f.bytes
            }
            return out
        }

        /** Which recordings were last written before [cutoff]; [keep] never is. */
        internal fun olderThan(files: List<Entry>, cutoff: Long, keep: String?): List<String> =
            files.filter { it.modifiedAt < cutoff && it.id != keep }.map { it.id }

        private fun maxChunkRecord(sampleRate: Int): Int =
            (pcmBytes(sampleRate, CHUNK_MS) + SEAL_OVERHEAD_MAX).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        private fun aad(id: String, type: Int, index: Int): ByteArray =
            "CXR1/$id/${type.toChar()}/$index".toByteArray(Charsets.US_ASCII)

        private fun writeRecord(out: DataOutputStream, type: Int, blob: ByteArray) {
            out.writeByte(type)
            out.writeInt(blob.size)
            out.write(blob)
            out.flush()
        }

        /** The next record, or null at the end of the file or where it was cut off mid-record. */
        private fun readRecord(input: DataInputStream, maxLen: Int): Pair<Int, ByteArray>? {
            val type = input.read()
            if (type < 0) return null
            return try {
                val len = input.readInt()
                if (len < 0 || len > maxLen) throw IOException("the recording is damaged")
                val blob = ByteArray(len)
                input.readFully(blob)
                type to blob
            } catch (e: EOFException) {
                null
            }
        }

        private fun encodeHeader(h: Header): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use {
                it.writeInt(1)
                it.writeLong(h.startedAt)
                it.writeLong(h.freqHz)
                it.writeInt(h.sampleRate)
                it.writeUTF(h.channel.take(200))
            }
            return bytes.toByteArray()
        }

        private fun decodeHeader(b: ByteArray): Header = DataInputStream(ByteArrayInputStream(b)).use {
            if (it.readInt() != 1) throw IOException("unknown recording version")
            val startedAt = it.readLong()
            val freqHz = it.readLong()
            val sampleRate = it.readInt()
            if (sampleRate !in 1..1_000_000) throw IOException("bad sample rate")
            Header(startedAt, freqHz, it.readUTF(), sampleRate)
        }

        private fun encodeTrailer(chunks: Int, pcmBytes: Long): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use {
                it.writeInt(chunks)
                it.writeLong(pcmBytes)
            }
            return bytes.toByteArray()
        }

        private fun decodeTrailer(b: ByteArray): Pair<Int, Long> = DataInputStream(ByteArrayInputStream(b)).use {
            it.readInt() to it.readLong()
        }
    }
}
