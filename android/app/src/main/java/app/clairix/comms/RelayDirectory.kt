package app.clairix.comms

import android.content.Context
import android.util.Log
import app.clairix.nav.AssetKeyList
import app.clairix.nav.AssetKeys
import app.clairix.BuildConfig
import app.clairix.nav.AssetSignature
import app.clairix.nav.MapTiles
import app.clairix.nav.TileConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A relay on the Clairix relay list. */
data class ListedRelay(
    val url: String,
    val name: String,
    /** Where it is, as the list says: an ISO country code, or "Global". */
    val country: String,
    /** Who hosts the server, and where that company answers to. */
    val host: String,
    val operator: String,
    /** The relay's Ed25519 key as its `GET /` publishes it (`relayKey`). */
    val key: String,
    /** How to get on it (an invite, a code from somewhere). */
    val join: String?,
    /** Its onion address, `http://….onion`, when it has one. */
    val onion: String?,
)

/**
 * The relay list: relays/relays.json in the repository, signed with the
 * asset key and published as the `relays` release by the Relay list
 * workflow. Parsed by the same rules as tools/relays/check.py, which keep
 * the two in step.
 */
class RelayList(val issued: Long, val relays: List<ListedRelay>) {

    /** The listed relay at [url] (an origin, as registered), or null. */
    fun find(url: String): ListedRelay? {
        val origin = url.trim().trimEnd('/').lowercase()
        return relays.firstOrNull { it.url == origin }
    }

    companion object {
        const val TYPE = "clairix-relay-list"
        const val VERSION = 1
        const val MAX_RELAYS = 64
        const val MAX_TEXT = 200
        private val FIELDS = setOf("url", "name", "country", "host", "operator", "key", "join", "onion")
        private val ONION_LABEL = Regex("^[a-z2-7]{56}$")

        /** The list in [text], or an [IllegalArgumentException] saying what is wrong with it. */
        fun parse(text: String, nowSec: Long): RelayList {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON") }
            require(doc.optString("type") == TYPE) { "not a Clairix relay list" }
            require(doc.opt("version") == VERSION) { "relay list version ${doc.opt("version")} is not one this app reads" }
            val issued = (doc.opt("issued") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
            require(issued != null && issued > 0) { "issued must be a positive whole number" }
            require(issued <= nowSec + 3600) { "issued is in the future" }
            val arr = doc.optJSONArray("relays")
            require(arr != null && arr.length() in 1..MAX_RELAYS) { "relays must list 1-$MAX_RELAYS relays" }
            val relays = ArrayList<ListedRelay>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("relay ${i + 1}: not an object")
                val relay = try { entry(o) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("relay ${i + 1}: ${e.message}") }
                require(relays.none { it.url == relay.url }) { "relay ${i + 1}: ${relay.url} is listed twice" }
                relays += relay
            }
            return RelayList(issued, relays)
        }

        private fun entry(o: JSONObject): ListedRelay {
            val unknown = o.keys().asSequence().filter { it !in FIELDS }.toList()
            require(unknown.isEmpty()) { "unknown field(s) $unknown" }
            fun text(field: String, required: Boolean = true): String? {
                if (!o.has(field) && !required) return null
                val v = o.opt(field) as? String ?: throw IllegalArgumentException("$field is missing")
                require(v.isNotBlank() && v.length <= MAX_TEXT && v.none { it == '\n' || it == '\r' || it == '\t' }) {
                    "$field must be one line of text, 1-$MAX_TEXT characters"
                }
                return v
            }
            val url = text("url")!!
            origin(url, "https")
            val onion = text("onion", required = false)
            if (onion != null) {
                val host = origin(onion, "http")
                require(host.endsWith(".onion") && ONION_LABEL.matches(host.removeSuffix(".onion"))) { "$onion is not a v3 onion address" }
            }
            val key = text("key")!!
            val raw = try { java.util.Base64.getDecoder().decode(key) } catch (e: IllegalArgumentException) { null }
            require(raw != null && raw.size == 32 && key.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) { "key must be a 32-byte Ed25519 key in base64" }
            return ListedRelay(
                url = url, name = text("name")!!, country = text("country")!!, host = text("host")!!,
                operator = text("operator")!!, key = key, join = text("join", required = false), onion = onion,
            )
        }

        /** The host of [url], which must be a bare lowercase `scheme://host` origin. */
        private fun origin(url: String, scheme: String): String {
            val prefix = "$scheme://"
            require(url.startsWith(prefix)) { "$url must start with $prefix" }
            val host = url.removePrefix(prefix)
            require(host.isNotEmpty() && host.none { it in "/?#@" } && host == host.lowercase()) { "$url must be a bare lowercase origin" }
            return host
        }

        /**
         * Whether a downloaded list replaces [current]: its signature [sig] must
         * check against the asset key ([verify]), it must parse, and it must be
         * issued later, so nobody can hand a phone an old list back (one that
         * still names a relay since taken off it). Null when it does not.
         */
        fun accept(current: RelayList?, raw: ByteArray, sig: String, nowSec: Long, verify: (ByteArray, String) -> Boolean): RelayList? {
            if (!verify(raw, sig)) throw IllegalArgumentException("the relay list is not signed by the Clairix release key")
            val list = parse(String(raw, Charsets.UTF_8), nowSec)
            return if (current == null || list.issued > current.issued) list else null
        }
    }
}

/**
 * The relay list on this phone: the copy built into the app, or a newer one
 * downloaded and verified since. Offered when setting up Comms, and checked
 * at registration: a relay on the list must publish the key the list gives
 * it, so a relay address someone has taken over is refused (see
 * [CommsRepository.register]).
 *
 * The same daily fetch brings the two other files of the `relays` release:
 * the asset key list ([AssetKeys]), which says which keys the list and every
 * other published file may be signed with, and the version note
 * ([AppUpdate]), the newest version and the oldest still safe. All three are
 * the same anonymous request for everyone, from GitHub; nothing about the
 * phone goes with them, and the comparison with this build happens here.
 */
object RelayDirectory {

    private const val TAG = "RelayDirectory"
    private const val RELEASE_BASE = "https://github.com/jakes1345/Clairix/releases/download/relays"
    private const val RELEASE = "$RELEASE_BASE/relays.json"
    private const val KEYS_RELEASE = "$RELEASE_BASE/${AssetKeys.ASSET}"
    private const val LATEST_RELEASE = "$RELEASE_BASE/${AppUpdate.ASSET}"
    private const val ASSET = "relays.json"
    private const val MAX_BYTES = 256L * 1024
    private const val SIGNATURE_MAX_BYTES = 1024L
    private const val PREFS = "relay_directory"
    private const val KEY_NEXT_TRY = "next_try"
    /** How often the release is asked for newer files. */
    const val REFRESH_INTERVAL_MS = 24 * 3600_000L
    /** How soon a refresh that failed (offline, the release down) is tried again. */
    private const val RETRY_INTERVAL_MS = 3600_000L

    /**
     * The maps' tile server ([TileConfig]): published on the same release,
     * signed with the same key, checked the same way, and asked for with the
     * relay list or, while the map is in use, on its own ([refreshTilesIfStale]).
     */
    private const val TILES_RELEASE = "https://github.com/jakes1345/Clairix/releases/download/relays/tiles.json"
    @Volatile private var tilesAttemptMs = 0L

    private val _list = MutableStateFlow<RelayList?>(null)
    val list: StateFlow<RelayList?> = _list.asStateFlow()

    private val lock = Mutex()
    @Volatile private var lastAttemptMs = 0L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true) // release assets redirect to GitHub's object storage
            .followSslRedirects(false)
            .proxySelector(TorTransport.proxySelector)
            .build()
    }

    private fun cacheFile(context: Context) = File(File(context.applicationContext.filesDir, "relays"), "relays.json")

    private fun nowSec() = System.currentTimeMillis() / 1000

    /** The list this phone has, loading it on first use: the built-in copy or a newer verified download. */
    fun current(context: Context): RelayList? {
        _list.value?.let { return it }
        synchronized(this) {
            _list.value?.let { return it }
            val bundled = runCatching {
                context.applicationContext.assets.open(ASSET).use { RelayList.parse(String(readCapped(it, MAX_BYTES), Charsets.UTF_8), nowSec()) }
            }.onFailure { Log.w(TAG, "The built-in relay list is unreadable: ${it.message}") }.getOrNull()
            // Kept only after its signature checked (see [refresh]).
            val cached = runCatching {
                cacheFile(context).takeIf { it.isFile }?.inputStream()?.use { RelayList.parse(String(readCapped(it, MAX_BYTES), Charsets.UTF_8), nowSec()) }
            }.getOrNull()
            val best = listOfNotNull(bundled, cached).maxByOrNull { it.issued }
            _list.value = best
            return best
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Asks the release for newer files unless it was asked lately: in this
     * process within [maxAgeMs], or by any process within a day of a refresh
     * that worked or an hour of one that failed. Called whenever the app comes
     * to the screen (MainActivity) and from the Comms setup screens.
     */
    suspend fun refreshIfStale(context: Context, maxAgeMs: Long = REFRESH_INTERVAL_MS): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < maxAgeMs) return true
        val nextTry = withContext(Dispatchers.IO) { runCatching { prefs(context).getLong(KEY_NEXT_TRY, 0L) }.getOrDefault(0L) }
        if (!refreshDue(nextTry, now)) return true
        return refresh(context)
    }

    /**
     * Whether a refresh is due at [now] when the last one set [nextTry]: once
     * that has passed, or when it lies further ahead than any refresh sets it
     * (the clock was wrong when it was written).
     */
    internal fun refreshDue(nextTry: Long, now: Long): Boolean = now >= nextTry || nextTry - now > REFRESH_INTERVAL_MS

    /**
     * Downloads, in this order, the asset key list, the relay list and the
     * version note, each with its signature, and keeps each one that checks and
     * is newer than the one held. The keys come first, so a list or a note
     * signed with a key published since is checked against it. False when any
     * of them could not be fetched or was refused; a file the release does not
     * have yet is not a failure.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            lastAttemptMs = System.currentTimeMillis()
            val app = context.applicationContext
            AssetKeys.init(app)
            AppUpdate.init(app)
            val keys = attempt("Key list") { refreshKeys(app) }
            val list = attempt("Relay list") { refreshList(app) }
            val latest = attempt("Version note") { refreshLatest(app) }
            // The comms-only build has no map to set a tile server for; a tile setting
            // that fails to come is retried while a map is open (refreshTilesIfStale).
            if (BuildConfig.FULL_ACCESS) refreshTiles(app)
            val ok = keys && list && latest
            val next = System.currentTimeMillis() + if (ok) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS
            runCatching { prefs(app).edit().putLong(KEY_NEXT_TRY, next).apply() }
            ok
        }
    }

    /** The key list, checked against the built-in key alone: a list cannot vouch for itself. */
    private fun refreshKeys(context: Context) {
        val raw = fetchIfPublished(KEYS_RELEASE, AssetKeys.MAX_BYTES) ?: return
        val sig = String(fetch("$KEYS_RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
        val newer = AssetKeyList.accept(AssetKeys.current(), raw, sig, nowSec(), AssetSignature::verifyRoot) ?: return
        AssetKeys.store(context, newer, raw, sig)
        CommsLog.add("Signing keys updated: ${newer.accepted.size} key(s) accepted")
    }

    private fun refreshList(context: Context) {
        val current = current(context)
        val raw = fetch(RELEASE, MAX_BYTES)
        val sig = String(fetch("$RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
        val newer = RelayList.accept(current, raw, sig, nowSec(), AssetSignature::verify) ?: return
        val file = cacheFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(raw)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _list.value = newer
        CommsLog.add("Relay list updated: ${newer.relays.size} relay(s)")
    }

    private fun refreshLatest(context: Context) {
        val raw = fetchIfPublished(LATEST_RELEASE, AppUpdate.MAX_BYTES) ?: return
        val sig = String(fetch("$LATEST_RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
        val newer = LatestRelease.accept(AppUpdate.current(), raw, sig, nowSec(), AssetSignature::verify) ?: return
        AppUpdate.store(context, newer, raw)
        CommsLog.add("Newest Clairix version: ${newer.versionName}")
    }

    /** Runs one download; false, logged, when it failed or what came was refused. */
    private inline fun attempt(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IOException) {
        Log.i(TAG, "$what unavailable: ${e.message}")
        false
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "$what refused: ${e.message}")
        false
    }

    /** Asks the release for a newer tile setting when the last try was over [maxAgeMs] ago; the maps call this while shown. */
    suspend fun refreshTilesIfStale(context: Context, maxAgeMs: Long = REFRESH_INTERVAL_MS) {
        if (System.currentTimeMillis() - tilesAttemptMs < maxAgeMs) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                // A relay-list refresh may have fetched it while this waited for the lock.
                if (System.currentTimeMillis() - tilesAttemptMs >= maxAgeMs) refreshTiles(context)
            }
        }
    }

    /**
     * Downloads tiles.json and its signature and hands it to [MapTiles] when
     * it is signed by the asset key and issued later than the one held.
     * Under [lock]; false when that failed.
     */
    private fun refreshTiles(context: Context): Boolean {
        tilesAttemptMs = System.currentTimeMillis()
        return try {
            val raw = fetch(TILES_RELEASE, MapTiles.MAX_SETTING_BYTES)
            val sig = String(fetch("$TILES_RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
            val newer = TileConfig.accept(MapTiles.held(context), raw, sig, nowSec(), AssetSignature::verify) ?: return true
            MapTiles.adopt(context, raw, newer)
            Log.i(TAG, "Map tiles now from ${newer.name}")
            true
        } catch (e: IOException) {
            Log.i(TAG, "Tile setting unavailable: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Tile setting refused: ${e.message}")
            false
        }
    }

    /**
     * The key the relay at [url] publishes now (`relayKey` on its `GET /`),
     * asked of it directly; null when it did not answer with one.
     */
    fun liveKey(url: String): String? {
        val origin = url.trim().trimEnd('/')
        return runCatching {
            http.newCall(Request.Builder().url("$origin/").header("Accept", "application/json").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                JSONObject(String(body.byteStream().use { readCapped(it, 64L * 1024) }, Charsets.UTF_8)).optString("relayKey").takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun fetch(url: String, max: Long): ByteArray =
        fetchIfPublished(url, max) ?: throw IOException("HTTP 404 for $url")

    /** The bytes at [url], or null when the release has no such file (yet): a 404. */
    private fun fetchIfPublished(url: String, max: Long): ByteArray? {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > max) throw IOException("$url is larger than $max bytes")
            return body.byteStream().use { readCapped(it, max) }
        }
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("larger than $max bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** For tests: what [current] returns when nothing has been loaded. */
    @androidx.annotation.VisibleForTesting
    internal fun reset() { _list.value = null; lastAttemptMs = 0L; tilesAttemptMs = 0L }
}
