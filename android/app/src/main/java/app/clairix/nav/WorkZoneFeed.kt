package app.clairix.nav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * A planned road work or closure from the state DOT feeds (WZDx / CWZ), as the workzones
 * aggregator publishes it. [geom] is in travel order; times are epoch seconds.
 */
data class WorkZone(
    val id: String,
    val type: String,
    val impact: String,
    val speedKph: Double?,
    val road: String?,
    val direction: String?,
    val description: String?,
    val start: Long?,
    val end: Long?,
    val geom: List<LatLon>,
    val source: String,
) {
    fun isActive(nowSec: Long): Boolean = (start == null || start <= nowSec) && (end == null || end > nowSec)

    companion object {
        const val CLOSURE = "closure"
        const val LANE_CLOSURE = "lane_closure"
        const val WORK_ZONE = "work_zone"
        const val DETOUR = "detour"
        const val RESTRICTION = "restriction"
        val TYPES = setOf(CLOSURE, LANE_CLOSURE, WORK_ZONE, DETOUR, RESTRICTION)

        const val IMPACT_ALL_CLOSED = "all_closed"
        const val IMPACT_SOME_CLOSED = "some_closed"
        const val IMPACT_SHOULDER = "shoulder"
        const val IMPACT_OPEN = "open"
        const val IMPACT_UNKNOWN = "unknown"
        val IMPACTS = setOf(IMPACT_ALL_CLOSED, IMPACT_SOME_CLOSED, IMPACT_SHOULDER, IMPACT_OPEN, IMPACT_UNKNOWN)

        /** Parses one aggregator item; null when it lacks an id or a usable geometry. */
        fun fromJson(o: JSONObject): WorkZone? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val arr = o.optJSONArray("geom") ?: return null
            val geom = ArrayList<LatLon>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                val lat = p.optDouble(0, Double.NaN)
                val lon = p.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                geom.add(LatLon(lat, lon))
            }
            if (geom.isEmpty()) return null
            val type = o.optString("type").takeIf { it in TYPES } ?: WORK_ZONE
            val impact = o.optString("impact").takeIf { it in IMPACTS } ?: IMPACT_UNKNOWN
            return WorkZone(
                id = id,
                type = type,
                impact = impact,
                speedKph = optPositiveDouble(o, "speed_kph"),
                road = optText(o, "road"),
                direction = optText(o, "direction"),
                description = optText(o, "description"),
                start = optEpoch(o, "start"),
                end = optEpoch(o, "end"),
                geom = geom,
                source = optText(o, "source") ?: "State DOT",
            )
        }

        private fun optText(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }

        private fun optEpoch(o: JSONObject, key: String): Long? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v <= 0) null else v.toLong()
        }

        private fun optPositiveDouble(o: JSONObject, key: String): Double? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v <= 0) null else v
        }
    }
}

/**
 * Downloads the work zones for one map region from the workzones release, with conditional
 * requests so an unchanged file costs one round trip, and the release's signed index for the
 * time of the aggregator's last run. GitHub Actions runs that workflow on a best-effort
 * schedule, in practice several times a day rather than the quarter-hour its cron asks for, so
 * [status] carries how old the zones are, for the UI to show beside the routes that use them.
 * The last good files are kept in filesDir/nav/ so zones are known offline.
 */
class WorkZoneFeed(context: Context, val slug: String) {

    companion object {
        private const val TAG = "WorkZoneFeed"
        private const val RELEASE_BASE = "https://github.com/jakes1345/Clairix/releases/download/workzones"
        /** How often to ask the release again; most answers are 304, and the age shown does not depend on it. */
        const val REFRESH_INTERVAL_MS = 15 * 60_000L
        const val MAX_BYTES = 10L * 1024 * 1024
        /** The index lists every feed's status: a few kilobytes. */
        const val INDEX_MAX_BYTES = 1024L * 1024
        /** A signature file is one line. */
        const val SIGNATURE_MAX_BYTES = 1024L
        private const val FORMAT_VERSION = 1
        /** Rewritten by every run of the aggregator, whether or not any region's file changed. */
        const val INDEX_URL = "$RELEASE_BASE/workzones-index.json"
        /** The cached index is shared by every region's feed: one reader or writer at a time. */
        private val indexLock = Mutex()

        fun url(slug: String): String = "$RELEASE_BASE/workzones-$slug.json.gz"

        /** Decompresses [bytes] when they are gzip (else returns them as they are), refusing more than [cap] bytes of output. */
        fun gunzipCapped(bytes: ByteArray, cap: Long = MAX_BYTES): ByteArray {
            val gz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            if (!gz) {
                if (bytes.size > cap) throw IOException("Work zone file is larger than ${cap / (1024 * 1024)} MiB")
                return bytes
            }
            return GZIPInputStream(ByteArrayInputStream(bytes), 64 * 1024).use { readCapped(it, cap) }
        }

        fun readCapped(input: InputStream, cap: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) throw IOException("Work zone file is larger than ${cap / (1024 * 1024)} MiB")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * Parses an aggregator file and keeps the zones that have not ended by [nowSec]. Throws
         * [JSONException] when the file is not one; a file for another region is rejected too.
         */
        fun parse(text: String, nowSec: Long, expectSlug: String? = null): List<WorkZone> =
            parseFile(text, nowSec, expectSlug).zones

        /** [parse], with the file's own date: the aggregator run that last changed this region's zones. */
        fun parseFile(text: String, nowSec: Long, expectSlug: String? = null): ParsedFile {
            val root = JSONObject(text)
            val version = root.optInt("version", -1)
            if (version != FORMAT_VERSION) throw JSONException("Unsupported work zone file version $version")
            if (expectSlug != null) {
                val s = root.optString("slug")
                if (s.isNotEmpty() && s != expectSlug) throw JSONException("Work zone file is for $s, not $expectSlug")
            }
            val items = root.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<WorkZone>(items.length())
            val seen = HashSet<String>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val z = WorkZone.fromJson(o) ?: continue
                if (z.end != null && z.end <= nowSec) continue
                if (seen.add(z.id)) out.add(z)
            }
            return ParsedFile(out, epochOrNull(root, "generated"))
        }

        /**
         * The time of the aggregator's last run, from workzones-index.json; null when the index
         * carries none. Throws [JSONException] when the text is not a JSON object.
         */
        fun parseIndexGenerated(text: String): Long? = epochOrNull(JSONObject(text), "generated")

        private fun epochOrNull(o: JSONObject, key: String): Long? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v <= 0) null else v.toLong()
        }

        /**
         * How current a region's zones are once a refresh has confirmed its file: the newer of the
         * file's own date (the run that last changed its zones) and the run time the index gave
         * ([indexGenerated], fetched before the file, so never newer than the file is). An
         * unchanged file also keeps the date it was confirmed to before; a changed one starts
         * again from its own date.
         */
        fun confirmedAsOf(fileChanged: Boolean, fileGenerated: Long?, previousAsOf: Long?, indexGenerated: Long?): Long? =
            listOfNotNull(fileGenerated, indexGenerated, if (fileChanged) null else previousAsOf).maxOrNull()

        /** "just now", "12 min ago", "3 h ago", "2 d ago": how old the work-zone data is. */
        fun asOfText(nowSec: Long, asOfSec: Long): String {
            val s = (nowSec - asOfSec).coerceAtLeast(0L)
            return when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60} min ago"
                s < 86_400 -> "${s / 3600} h ago"
                else -> "${s / 86_400} d ago"
            }
        }

        /** The line under route options that says how many work zones the routes know of, and how old that list is. */
        fun statusText(status: Status, nowSec: Long): String = when (status) {
            is Status.Loaded -> {
                val listed = if (status.count == 0) "none listed for this map" else "${status.count} listed for this map"
                val asOf = status.asOfEpochSec
                if (asOf != null) "Work zones as of ${asOfText(nowSec, asOf)}: $listed, from the state DOT feeds."
                else "Work zones: $listed, from the state DOT feeds; the list carries no date."
            }
            Status.NotPublished -> "No work-zone list has been published for this map yet."
            Status.Unknown -> "The work-zone list for this map has not been fetched yet."
        }
    }

    /** A parsed region file: the zones not yet ended, and the aggregator run that produced this set of zones. */
    data class ParsedFile(val zones: List<WorkZone>, val generatedEpochSec: Long?)

    /** What the UI can say about the feed: nothing yet, the list with its age, or none published for this region. */
    sealed interface Status {
        data object Unknown : Status
        /** [count] zones not yet ended; [asOfEpochSec] the aggregator run they are known current at, null when nothing says. */
        data class Loaded(val count: Int, val asOfEpochSec: Long?) : Status
        /** No work-zone file has been published for this region (yet). */
        data object NotPublished : Status
    }

    private val dir = File(context.applicationContext.filesDir, "nav")
    private val cacheFile = File(dir, "workzones-$slug.json.gz")
    /** The region file's validators, the mark that its signature was checked, and "asOf", the run it was last confirmed current at. */
    private val metaFile = File(dir, "workzones-$slug.meta.json")
    /**
     * The release's index, the same for every region: one copy and one set of validators. Its run
     * time dates a region only in the refresh that confirmed that region's file; what each region
     * was confirmed to is kept in that region's own meta file.
     */
    private val indexFile = File(dir, "workzones-index.json")
    private val indexMetaFile = File(dir, "workzones-index.meta.json")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true) // release assets redirect to GitHub's object storage
        .followSslRedirects(false)
        .build()

    private val lock = Mutex()
    @Volatile private var loaded = false
    @Volatile private var lastAttemptMs = 0L
    /** A signed region file has been parsed, from the cache or the release, and its zones are in [zones]. */
    @Volatile private var haveFile = false
    /** The region file's "generated": the aggregator run that last changed its zones. */
    @Volatile private var fileGenerated: Long? = null
    /** The aggregator run this region's file was last confirmed current at. */
    @Volatile private var asOf: Long? = null

    private val _zones = MutableStateFlow<List<WorkZone>>(emptyList())
    /** Work zones not yet ended, from the newest file this phone has. */
    val zones: StateFlow<List<WorkZone>> = _zones.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    /** Whether a list exists for this region and how old it is, for the route options sheet. */
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Refreshes when the last attempt was more than [maxAgeMs] ago. */
    suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS): Boolean {
        if (System.currentTimeMillis() - lastAttemptMs < maxAgeMs && loaded) return true
        return refresh()
    }

    /**
     * Loads the cached file on first use, then asks the release for its index and for a newer
     * region file. Returns false when the region download failed; the cached zones and their
     * date stay in place then.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                loadCache()
                loaded = true
            }
            lastAttemptMs = System.currentTimeMillis()
            // The index before the region file: tools/workzones/publish.py uploads it after every
            // region file of a run, so a region file fetched after it is at least as new as the
            // run it names, and the age shown can be too old but never too new.
            val runAt = fetchRunTime()
            val changed = try {
                download()
            } catch (e: IOException) {
                Log.i(TAG, "Work zones for $slug unavailable: ${e.message}")
                pruneEnded()
                null
            } catch (e: JSONException) {
                Log.w(TAG, "Malformed work zone file for $slug: ${e.message}")
                pruneEnded()
                null
            }
            if (changed != null) {
                val confirmed = confirmedAsOf(changed, fileGenerated, asOf, runAt)
                // A new file was stored with fresh validators and no date; an unchanged one only when its date moves.
                if (changed || confirmed != asOf) saveAsOf(confirmed)
                asOf = confirmed
            }
            publishStatus()
            changed != null
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun pruneEnded() {
        val now = nowSec()
        val cur = _zones.value
        val kept = cur.filter { it.end == null || it.end > now }
        if (kept.size != cur.size) _zones.value = kept
    }

    /** Loaded once a signed file has been read, dated by the newer of its own date and the run it was confirmed at. */
    private fun publishStatus() {
        if (!haveFile) return
        _status.value = Status.Loaded(_zones.value.size, listOfNotNull(fileGenerated, asOf).maxOrNull())
    }

    /** Asks the release for a newer region file: true when one was stored, false when the one held is current (304). */
    private fun download(): Boolean {
        val meta = readMeta(metaFile)
        val builder = Request.Builder().url(url(slug)).get()
        // Validators only make sense while the zones they describe are loaded from a file still on
        // disk, and only for a copy whose signature was checked when it was stored: a 304 must
        // mean the zones in hand are the current ones.
        if (haveFile && cacheFile.isFile && meta != null && meta.optBoolean("signed")) {
            meta.optString("etag").takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
            meta.optString("lastModified").takeIf { it.isNotEmpty() }?.let { builder.header("If-Modified-Since", it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 304) {
                pruneEnded()
                return false
            }
            if (resp.code == 404) {
                // A region the release has no file for; with zones in hand, a moment between a replace's delete and upload.
                if (!haveFile) _status.value = Status.NotPublished
                throw IOException("No work zone file is published for this region")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw IOException("Work zone file is larger than ${MAX_BYTES / (1024 * 1024)} MiB")
            val raw = body.byteStream().use { readCapped(it, MAX_BYTES) }
            // Verified before it is parsed or kept: the file is published by the
            // same workflow as the maps, and signed by the same key.
            if (!AssetSignature.verify(raw, fetchSignature(url(slug) + ".sig"))) {
                throw IOException("The work zone file is not signed by the Clairix release key")
            }
            val text = String(gunzipCapped(raw), Charsets.UTF_8)
            val parsed = parseFile(text, nowSec(), slug)
            writeAtomically(cacheFile, raw)
            writeAtomically(metaFile, validators(resp))
            fileGenerated = parsed.generatedEpochSec
            haveFile = true
            _zones.value = parsed.zones
            return true
        }
    }

    /** The time of the aggregator's last run, from the release's signed index; null when it cannot be had. */
    private suspend fun fetchRunTime(): Long? = indexLock.withLock {
        try {
            downloadIndex()
        } catch (e: IOException) {
            Log.i(TAG, "Work zone index unavailable: ${e.message}")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed work zone index, discarding the cached copy: ${e.message}")
            indexFile.delete()
            indexMetaFile.delete()
            null
        }
    }

    private fun downloadIndex(): Long? {
        val meta = readMeta(indexMetaFile)
        val builder = Request.Builder().url(INDEX_URL).get()
        if (indexFile.isFile && meta != null && meta.optBoolean("signed")) {
            meta.optString("etag").takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
            meta.optString("lastModified").takeIf { it.isNotEmpty() }?.let { builder.header("If-Modified-Since", it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 304) {
                // Unchanged: the copy on disk, checked when it was stored, is the current index.
                val cached = indexFile.inputStream().use { readCapped(it, INDEX_MAX_BYTES) }
                return parseIndexGenerated(String(cached, Charsets.UTF_8))
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > INDEX_MAX_BYTES) throw IOException("Work zone index is larger than ${INDEX_MAX_BYTES / 1024} KiB")
            val raw = body.byteStream().use { readCapped(it, INDEX_MAX_BYTES) }
            // Signed by the same key as the region files, and checked the same way before it is read.
            if (!AssetSignature.verify(raw, fetchSignature("$INDEX_URL.sig"))) {
                throw IOException("The work zone index is not signed by the Clairix release key")
            }
            val generated = parseIndexGenerated(String(raw, Charsets.UTF_8))
            writeAtomically(indexFile, raw)
            writeAtomically(indexMetaFile, validators(resp))
            return generated
        }
    }

    /** The one-line signature published beside a work zone file. */
    private fun fetchSignature(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.code == 404) throw IOException("No signature has been published for this work zone file")
            if (!resp.isSuccessful) throw IOException("Signature download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty signature")
            return String(body.byteStream().use { readCapped(it, SIGNATURE_MAX_BYTES) }, Charsets.US_ASCII).trim()
        }
    }

    /** The validators to send next time, and the mark that this copy's signature was checked. */
    private fun validators(resp: Response): ByteArray {
        val m = JSONObject().put("signed", true)
        resp.header("ETag")?.let { m.put("etag", it) }
        resp.header("Last-Modified")?.let { m.put("lastModified", it) }
        return m.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readMeta(file: File): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        file.delete()
        null
    }

    /** Keeps the run this region's file was confirmed current at beside its validators, so a restart offline still knows it. */
    private fun saveAsOf(value: Long?) {
        val m = readMeta(metaFile) ?: return
        if (value == null) m.remove("asOf") else m.put("asOf", value)
        writeAtomically(metaFile, m.toString().toByteArray(Charsets.UTF_8))
    }

    private fun loadCache() {
        if (!cacheFile.isFile) return
        val meta = readMeta(metaFile)
        // A copy kept by a version that did not check signatures is not trusted now.
        if (meta == null || !meta.optBoolean("signed")) {
            cacheFile.delete()
            metaFile.delete()
            return
        }
        try {
            val raw = cacheFile.inputStream().use { readCapped(it, MAX_BYTES) }
            val parsed = parseFile(String(gunzipCapped(raw), Charsets.UTF_8), nowSec(), slug)
            fileGenerated = parsed.generatedEpochSec
            // Recorded by an earlier refresh; a copy stored before that was recorded has only its own date.
            asOf = meta.optLong("asOf", 0L).takeIf { it > 0 }
            haveFile = true
            _zones.value = parsed.zones
            publishStatus()
        } catch (e: IOException) {
            Log.w(TAG, "Work zone cache unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "Work zone cache corrupt, discarding: ${e.message}")
            cacheFile.delete()
            metaFile.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save ${target.name}: ${e.message}")
        }
    }
}
