package app.clairix.bugreport

import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Finds the main text inside a bug-report zip and hands it, line by line, to [BugReportParser].
 * Read as a stream from whatever app shared it: nothing is copied to storage, uploaded or kept.
 *
 * A zip shared to Clairix could be anything, including one built to exhaust the phone (a "zip
 * bomb": a few kilobytes that inflate to gigabytes). So everything is capped: how many entries are
 * looked at ([MAX_ENTRIES]), how many bytes are inflated in all, skipped entries included
 * ([MAX_TOTAL_BYTES]), how much of the main text is read ([MAX_TEXT_BYTES]) and how long one line
 * may be ([MAX_LINE]); past a cap the reading stops and the result says so. The text is never held
 * whole, only one line at a time.
 *
 * The main text is the top-level `bugreport-*.txt` entry that `dumpstate` writes (its name is also
 * in `main_entry.txt`, which may come after it in the zip, so the name is matched instead). A plain
 * `.txt` bug report, not zipped, is read too.
 */
internal object BugReportZip {

    const val MAX_ENTRIES = 5_000
    const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
    const val MAX_TEXT_BYTES = 256L * 1024 * 1024
    const val MAX_LINE = 4_096

    /** The caps in force; the tests use smaller ones. */
    data class Limits(
        val entries: Int = MAX_ENTRIES,
        val totalBytes: Long = MAX_TOTAL_BYTES,
        val textBytes: Long = MAX_TEXT_BYTES,
        val line: Int = MAX_LINE,
    )

    /** What reading the zip came to. */
    sealed interface Outcome {
        /** The text was found and read; [stopped] says why reading ended early, if it did. */
        data class Read(val entry: String, val findings: BugReportParser.Findings, val stopped: String?) : Outcome
        /** No bug report text could be read, and why. */
        data class NotRead(val reason: String) : Outcome
    }

    /** "PK\u0003\u0004", how every zip file starts. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Whether [name], an entry in the zip, is dumpstate's main text: a top-level bugreport-*.txt. */
    fun isMainEntry(name: String): Boolean =
        '/' !in name && '\\' !in name && name.startsWith("bugreport") && name.endsWith(".txt")

    /** Reads [input], a bug-report zip or a plain bug-report text, from its start. Closes nothing; the caller owns [input]. */
    fun read(input: InputStream, limits: Limits = Limits()): Outcome {
        val head = input.buffered()
        head.mark(ZIP_MAGIC.size)
        val magic = ByteArray(ZIP_MAGIC.size)
        val got = readUpTo(head, magic)
        head.reset()
        if (got < magic.size || !magic.contentEquals(ZIP_MAGIC)) {
            val counter = CappedStream(head, limits.textBytes)
            return readText(counter, "the shared text", limits, counter)
        }
        return try {
            readZip(ZipInputStream(head), limits)
        } catch (e: ZipException) {
            Outcome.NotRead("The file is not a zip Clairix can read (${e.message ?: "damaged"}).")
        } catch (e: IOException) {
            Outcome.NotRead("The file could not be read (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /** Fills [into] from [input] as far as it goes (InputStream.readNBytes needs API 33); how many bytes came. */
    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun readZip(zip: ZipInputStream, limits: Limits): Outcome {
        val total = CappedStream(zip, limits.totalBytes)
        var entries = 0
        while (true) {
            val entry = zip.nextEntry ?: return Outcome.NotRead(
                "No bug report text (bugreport-….txt) was found in the zip. Was it made with Developer options → Take bug report?"
            )
            if (++entries > limits.entries) return Outcome.NotRead("The zip has more than ${limits.entries} entries; Clairix stopped there.")
            if (!entry.isDirectory && isMainEntry(entry.name)) {
                val text = CappedStream(total, limits.textBytes)
                return readText(text, entry.name, limits, text, total)
            }
            // Skipping an entry still inflates it, so it counts against the total.
            val sink = ByteArray(64 * 1024)
            do {
                val n = total.read(sink)
            } while (n >= 0)
            if (total.capped) return Outcome.NotRead("The zip inflates to more than ${mb(limits.totalBytes)} before its bug report text; Clairix stopped there.")
        }
    }

    private fun readText(input: InputStream, name: String, limits: Limits, text: CappedStream, total: CappedStream? = null): Outcome {
        val lines = CappedLines(input.bufferedReader(Charsets.UTF_8), limits.line)
        val parsed = BugReportParser.parse(generateSequence { lines.next() })
        val findings = parsed.copy(longLines = lines.longLines, unread = withLongLines(parsed, lines.longLines))
        val stopped = when {
            total?.capped == true -> "The zip inflated to more than ${mb(limits.totalBytes)}; Clairix read only that much."
            text.capped -> "The bug report text is longer than ${mb(limits.textBytes)}; Clairix read only the first ${mb(limits.textBytes)}."
            lines.failed != null -> "Reading stopped early: ${lines.failed}."
            else -> null
        }
        return Outcome.Read(name, findings, stopped)
    }

    /** "768 MB", or "512 bytes" for the tests' small caps. */
    private fun mb(bytes: Long): String = if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "$bytes bytes"

    /** The parser's [BugReportParser.Findings.unread], with the count of lines cut short, which only the reader knows. */
    private fun withLongLines(f: BugReportParser.Findings, longLines: Long): List<String> =
        if (longLines == 0L) f.unread else f.unread + "$longLines very long lines were cut short before reading."

    /**
     * A stream that gives at most [cap] bytes and then reports the end, with [capped] set when there
     * was more. Over a [ZipInputStream] it counts every entry's bytes together.
     */
    private class CappedStream(private val inner: InputStream, private val cap: Long) : InputStream() {
        private var count = 0L
        var capped = false
            private set

        override fun read(): Int {
            if (count >= cap) return over()
            val b = inner.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= cap) return over()
            val n = inner.read(b, off, minOf(len.toLong(), cap - count).toInt())
            if (n > 0) count += n
            return n
        }

        /** At the cap: it is only "capped" when the stream really had more to give. */
        private fun over(): Int {
            if (!capped && inner.read() >= 0) capped = true
            return -1
        }
    }

    /** Lines of at most [max] characters; a longer one is cut there and the rest of it skipped. */
    private class CappedLines(private val reader: Reader, private val max: Int) {
        var longLines = 0L
            private set
        var failed: String? = null
            private set
        private val sb = StringBuilder()

        fun next(): String? {
            sb.setLength(0)
            var long = false
            while (true) {
                val c = try {
                    reader.read()
                } catch (e: IOException) {
                    failed = e.message ?: e.javaClass.simpleName
                    -1
                }
                if (c < 0) return if (sb.isEmpty() && !long) null else done(long)
                if (c == '\n'.code) return done(long)
                if (sb.length < max) sb.append(c.toChar()) else long = true
            }
        }

        private fun done(long: Boolean): String {
            if (long) longLines++
            return sb.toString().removeSuffix("\r")
        }
    }
}
