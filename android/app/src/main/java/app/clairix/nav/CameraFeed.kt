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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A fixed automatic licence plate reader (ALPR) mapped in OpenStreetMap, as
 * `tools/graph-builder/extract_cameras.py` publishes it beside the region's road
 * graph. Flock Safety's Falcons, Motorola/Vigilant units and the like: cameras whose
 * job is to record which plates passed, when, and in which direction, kept by
 * their operators and searchable by them and by whoever they share with.
 */
data class KnownCamera(
    val id: String,
    val lat: Double,
    val lon: Double,
    /** What the camera watches: traffic, parking or an entrance, when mapped. */
    val zone: String?,
    /** Degrees clockwise from north the camera faces, when mapped. */
    val directionDeg: Int?,
    val mount: String?,
    val manufacturer: String?,
    val model: String?,
    val operator: String?,
) {
    /** One line for a map label: who made it and who runs it, as far as the map knows. */
    val summary: String
        get() {
            val maker = listOfNotNull(manufacturer, model).joinToString(" ")
            return buildString {
                append(if (maker.isNotEmpty()) maker else "Plate reader")
                if (operator != null) append(" · ").append(operator)
            }
        }

    companion object {
        const val KIND_ALPR = "alpr"
        private const val MAX_TEXT = 120

        /** One record from the extractor; null when it has no id or no usable position. */
        fun fromJson(o: JSONObject): KnownCamera? {
            val id = o.optString("id").takeIf { it.isNotEmpty() && it.length <= 24 } ?: return null
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (o.optString("kind", KIND_ALPR) != KIND_ALPR) return null
            val dir = if (o.has("direction") && !o.isNull("direction")) o.optInt("direction", -1).takeIf { it in 0..359 } else null
            return KnownCamera(
                id = id, lat = lat, lon = lon,
                zone = text(o, "zone"), directionDeg = dir, mount = text(o, "mount"),
                manufacturer = text(o, "manufacturer"), model = text(o, "model"), operator = text(o, "operator"),
            )
        }

        private fun text(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
    }
}

/**
 * The known plate readers for one map region, downloaded from the region's maps release
 * (rebuilt daily from OpenStreetMap by GitHub Actions) and kept in filesDir/nav/ so they
 * are known offline. Nothing is used before its signature by the Clairix asset key checks,
 * and a copy saved by a version that did not check is not trusted now.
 */
class CameraFeed(context: Context, val slug: String) {

    companion object {
        private const val TAG = "CameraFeed"
        private const val RELEASE_BASE = "https://github.com/jakes1345/Clairix/releases/download"
        /** The maps release is rebuilt daily; asking more often only costs round trips. */
        const val REFRESH_INTERVAL_MS = 24 * 3_600_000L
        const val MAX_BYTES = 5L * 1024 * 1024
        private const val FORMAT_VERSION = 1

        fun url(slug: String): String = "$RELEASE_BASE/maps-$slug/cameras-$slug.json.gz"

        /**
         * Every plate reader in every region's list on this phone, from the signed
         * caches alone, so the scanner can say whether hardware it hears is a mapped
         * camera. File I/O; call off the main thread. Empty until a region has loaded.
         */
        fun loadCached(context: Context): List<KnownCamera> {
            val dir = File(context.applicationContext.filesDir, "nav")
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("cameras-") && f.name.endsWith(".json.gz") }
                ?: return emptyList()
            val out = ArrayList<KnownCamera>()
            for (f in files) {
                val meta = File(dir, f.name.removeSuffix(".json.gz") + ".meta.json")
                val signed = runCatching { JSONObject(meta.readText()).optBoolean("signed") }.getOrDefault(false)
                if (!signed) continue
                runCatching {
                    val raw = f.inputStream().use { WorkZoneFeed.readCapped(it, MAX_BYTES) }
                    out += parse(String(WorkZoneFeed.gunzipCapped(raw, MAX_BYTES), Charsets.UTF_8))
                }.onFailure { Log.w(TAG, "Camera cache ${f.name} unreadable: ${it.message}") }
            }
            return out.distinctBy { it.id }
        }

        /** Parses an extractor file; throws [JSONException] when it is not one, or is for another region. */
        fun parse(text: String, expectSlug: String? = null): List<KnownCamera> {
            val root = JSONObject(text)
            val version = root.optInt("version", -1)
            if (version != FORMAT_VERSION) throw JSONException("Unsupported camera file version $version")
            if (expectSlug != null) {
                val s = root.optString("slug")
                if (s.isNotEmpty() && s != expectSlug) throw JSONException("Camera file is for $s, not $expectSlug")
            }
            val items = root.optJSONArray("cameras") ?: JSONArray()
            val out = ArrayList<KnownCamera>(items.length())
            val seen = HashSet<String>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val c = KnownCamera.fromJson(o) ?: continue
                if (seen.add(c.id)) out.add(c)
            }
            return out
        }
    }

    private val dir = File(context.applicationContext.filesDir, "nav")
    private val cacheFile = File(dir, "cameras-$slug.json.gz")
    private val metaFile = File(dir, "cameras-$slug.meta.json")

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

    private val _cameras = MutableStateFlow<List<KnownCamera>>(emptyList())
    /** The plate readers in the newest file this phone has. */
    val cameras: StateFlow<List<KnownCamera>> = _cameras.asStateFlow()

    private val _state = MutableStateFlow<Status>(Status.Unknown)
    /** Whether a list exists for this region, so the UI can say "none known" and "none published" apart. */
    val status: StateFlow<Status> = _state.asStateFlow()

    sealed interface Status {
        data object Unknown : Status
        data class Loaded(val count: Int, val builtEpochSec: Long?) : Status
        /** No camera file has been published for this region (yet). */
        data object NotPublished : Status
    }

    /** Refreshes when the last attempt was more than [maxAgeMs] ago. */
    suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS): Boolean {
        if (System.currentTimeMillis() - lastAttemptMs < maxAgeMs && loaded) return true
        return refresh()
    }

    /**
     * Loads the cached file on first use, then asks the release for a newer one. Returns false
     * when the download failed; the cached cameras stay in place then.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                loadCache()
                loaded = true
            }
            lastAttemptMs = System.currentTimeMillis()
            try {
                download()
                true
            } catch (e: IOException) {
                Log.i(TAG, "Plate readers for $slug unavailable: ${e.message}")
                false
            } catch (e: JSONException) {
                Log.w(TAG, "Malformed camera file for $slug: ${e.message}")
                false
            }
        }
    }

    private fun download() {
        val meta = readMeta()
        val builder = Request.Builder().url(url(slug)).get()
        if (cacheFile.isFile && meta?.optBoolean("signed") == true) {
            meta.optString("etag").takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
            meta.optString("lastModified").takeIf { it.isNotEmpty() }?.let { builder.header("If-Modified-Since", it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 304) return
            if (resp.code == 404) {
                // A region whose maps release predates the camera list, or a region with no list.
                if (_cameras.value.isEmpty()) _state.value = Status.NotPublished
                throw IOException("No camera list is published for this region")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_BYTES) throw IOException("Camera file is larger than ${MAX_BYTES / (1024 * 1024)} MiB")
            val raw = body.byteStream().use { WorkZoneFeed.readCapped(it, MAX_BYTES) }
            // Verified before it is parsed or kept: published by the maps workflow, signed by the same key as the graph.
            if (!AssetSignature.verify(raw, fetchSignature(url(slug) + ".sig"))) {
                throw IOException("The camera file is not signed by the Clairix release key")
            }
            val text = String(WorkZoneFeed.gunzipCapped(raw, MAX_BYTES), Charsets.UTF_8)
            val cameras = parse(text, slug)
            writeAtomically(cacheFile, raw)
            val m = JSONObject().put("signed", true)
            resp.header("ETag")?.let { m.put("etag", it) }
            resp.header("Last-Modified")?.let { m.put("lastModified", it) }
            writeAtomically(metaFile, m.toString().toByteArray(Charsets.UTF_8))
            publish(cameras, text)
        }
    }

    private fun publish(cameras: List<KnownCamera>, text: String) {
        _cameras.value = cameras
        val built = runCatching { JSONObject(text).optLong("built", 0L) }.getOrDefault(0L).takeIf { it > 0 }
        _state.value = Status.Loaded(cameras.size, built)
    }

    private fun fetchSignature(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.code == 404) throw IOException("No signature has been published for this camera file")
            if (!resp.isSuccessful) throw IOException("Signature download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty signature")
            return String(body.byteStream().use { WorkZoneFeed.readCapped(it, WorkZoneFeed.SIGNATURE_MAX_BYTES) }, Charsets.US_ASCII).trim()
        }
    }

    private fun readMeta(): JSONObject? = try {
        if (metaFile.isFile) JSONObject(metaFile.readText()) else null
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        metaFile.delete()
        null
    }

    private fun loadCache() {
        if (!cacheFile.isFile) return
        if (readMeta()?.optBoolean("signed") != true) {
            cacheFile.delete()
            metaFile.delete()
            return
        }
        try {
            val raw = cacheFile.inputStream().use { WorkZoneFeed.readCapped(it, MAX_BYTES) }
            val text = String(WorkZoneFeed.gunzipCapped(raw, MAX_BYTES), Charsets.UTF_8)
            publish(parse(text, slug), text)
        } catch (e: IOException) {
            Log.w(TAG, "Camera cache unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "Camera cache corrupt, discarding: ${e.message}")
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
