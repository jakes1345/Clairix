package app.clairix.nav

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** What a published road map weighs and when it was built, read without downloading it ([GraphStore.published]). */
data class PublishedGraph(
    val slug: String,
    /** The download: the .agr.gz file's size. */
    val compressedBytes: Long,
    /** The graph once unpacked, from the gzip trailer; null when it could not be read. */
    val unpackedBytes: Long?,
    /** From the graph's own header; null when it could not be read. */
    val builtAtEpochSec: Long?,
)

/**
 * The checks before a road map is downloaded, and how a published map is weighed and
 * dated from two small pieces of it, as decisions apart from the network and the screen.
 *
 * A state's map is a download of up to about 200 MB that unpacks to about two and a half
 * times that. Before anything is fetched the owner sees both figures, whether the phone
 * has the room, and whether the connection is metered. The graph is unpacked as it
 * arrives ([GraphStore]), so installing needs room for the unpacked graph alone; on an
 * update the old map stays in place and usable until the new one is ready, and its space
 * is already taken, so an update needs the same free space as a first download.
 *
 * The figures come from the published file itself: its first few kilobytes unpack to the
 * graph's header (when it was built, which region), the response says how big the file
 * is, and a gzip file's last four bytes are its unpacked size. A map on the phone is
 * compared with the published one by that build time, at most once a week, so "Update"
 * appears only when there is something newer.
 */
object MapDownloads {

    /** Room kept free beyond the map itself, so a download never leaves the phone with none. */
    const val SPARE_BYTES = 100L * 1024 * 1024

    /** When only the download size is known: graphs unpack to about two and a half times it, so this errs on the side of room. */
    const val UNPACK_ESTIMATE = 3L

    /** How often a map on the phone is compared with the published one. */
    const val UPDATE_CHECK_EVERY_MS = 7L * 24 * 3600 * 1000

    /** The first bytes of a published map that are read for its header; a few hundred are enough, this leaves room. */
    const val HEAD_PROBE_BYTES = 4096

    /** A graph's header, in bytes ([RoadGraph]). */
    const val GRAPH_HEADER_BYTES = 128

    /** What a download would cost, as the owner sees it before it starts. */
    data class Plan(
        /** Data the download uses; null when unknown. */
        val downloadBytes: Long?,
        /** Space the map takes once installed; null when unknown. */
        val installedBytes: Long?,
        /** Free space needed while it installs; null when it could not be worked out. */
        val requiredFreeBytes: Long?,
        val freeBytes: Long,
        /** True when [installedBytes] is worked out from the download size rather than read. */
        val estimated: Boolean,
        val metered: Boolean,
        /** True when a map of this region is already on the phone and stays usable until the new one is in place. */
        val replacing: Boolean,
    ) {
        val enoughSpace: Boolean get() = requiredFreeBytes == null || freeBytes >= requiredFreeBytes
        val missingBytes: Long get() = requiredFreeBytes?.let { maxOf(0L, it - freeBytes) } ?: 0L
    }

    fun plan(compressedBytes: Long?, unpackedBytes: Long?, freeBytes: Long, metered: Boolean, replacing: Boolean): Plan {
        val installed = unpackedBytes ?: compressedBytes?.let { it * UNPACK_ESTIMATE }
        return Plan(
            downloadBytes = compressedBytes,
            installedBytes = installed,
            requiredFreeBytes = installed?.let { it + SPARE_BYTES },
            freeBytes = freeBytes,
            estimated = unpackedBytes == null && compressedBytes != null,
            metered = metered,
            replacing = replacing,
        )
    }

    /** Whether the map built at [publishedBuiltAt] is newer than the one on the phone; null when the published one's date is unknown. */
    fun isNewer(installedBuiltAt: Long, publishedBuiltAt: Long?): Boolean? = publishedBuiltAt?.let { it > installedBuiltAt }

    /** Whether a week has passed since the last comparison (or the clock went back past it). */
    fun updateCheckDue(lastCheckedMs: Long, nowMs: Long): Boolean =
        lastCheckedMs <= 0L || nowMs < lastCheckedMs || nowMs - lastCheckedMs >= UPDATE_CHECK_EVERY_MS

    /** The whole file's size from a Content-Range header such as "bytes 0-4095/90335665"; null otherwise. */
    fun totalFromContentRange(header: String?): Long? {
        val m = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
        return m.groupValues[3].toLongOrNull()
    }

    /** A gzip file's unpacked size from its last four bytes (little-endian, exact below 4 GB); null unless given exactly four. */
    fun gzipSize(tail: ByteArray): Long? {
        if (tail.size != 4) return null
        return (tail[0].toLong() and 0xFF) or
            ((tail[1].toLong() and 0xFF) shl 8) or
            ((tail[2].toLong() and 0xFF) shl 16) or
            ((tail[3].toLong() and 0xFF) shl 24)
    }

    /** The first [n] bytes the start of a gzip file unpacks to, or null when [head] is not gzip or does not reach that far. */
    fun gunzipHead(head: ByteArray, n: Int): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(head)).use { input ->
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(out, got, n - got)
                if (r < 0) break
                got += r
            }
            if (got == n) out else null
        }
    } catch (e: IOException) {
        null
    }

    /** When a graph header says it was built, if it is a graph [GraphStore] can open, for [slug]; null otherwise. */
    fun builtAt(header: ByteArray, slug: String): Long? {
        if (header.size < GRAPH_HEADER_BYTES) return null
        if (header[0] != 'A'.code.toByte() || header[1] != 'G'.code.toByte() ||
            header[2] != 'R'.code.toByte() || header[3] != '1'.code.toByte()
        ) return null
        if (leInt(header, 4) != GraphStore.GRAPH_VERSION) return null
        val regionEnd = (48 until 112).firstOrNull { header[it].toInt() == 0 } ?: 112
        if (String(header, 48, regionEnd - 48, Charsets.US_ASCII) != slug) return null
        return leLong(header, 40)
    }

    private fun leInt(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        return v
    }

    private fun leLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$")
}
