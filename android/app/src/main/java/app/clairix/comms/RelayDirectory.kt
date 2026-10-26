package app.clairix.comms

import android.content.Context
import android.util.Log
import app.clairix.nav.AssetSignature
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
 */
object RelayDirectory {

    private const val TAG = "RelayDirectory"
    private const val RELEASE = "https://github.com/jakes1345/Clairix/releases/download/relays/relays.json"
    private const val ASSET = "relays.json"
    private const val MAX_BYTES = 256L * 1024
    private const val SIGNATURE_MAX_BYTES = 1024L
    /** How often the release is asked for a newer list. */
    const val REFRESH_INTERVAL_MS = 24 * 3600_000L

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

    /** Asks the release for a newer list when the last try was over [maxAgeMs] ago. */
    suspend fun refreshIfStale(context: Context, maxAgeMs: Long = REFRESH_INTERVAL_MS): Boolean {
        if (System.currentTimeMillis() - lastAttemptMs < maxAgeMs) return true
        return refresh(context)
    }

    /** Downloads the list and its signature and keeps it when it is newer. False when that failed. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            lastAttemptMs = System.currentTimeMillis()
            val current = current(context)
            try {
                val raw = fetch(RELEASE, MAX_BYTES)
                val sig = String(fetch("$RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
                val newer = RelayList.accept(current, raw, sig, nowSec(), AssetSignature::verify) ?: return@withLock true
                val file = cacheFile(context)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(raw)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                _list.value = newer
                CommsLog.add("Relay list updated: ${newer.relays.size} relay(s)")
                true
            } catch (e: IOException) {
                Log.i(TAG, "Relay list unavailable: ${e.message}")
                false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Relay list refused: ${e.message}")
                false
            }
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
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > max) throw IOException("$url is larger than $max bytes")
            body.byteStream().use { readCapped(it, max) }
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
    internal fun reset() { _list.value = null; lastAttemptMs = 0L }
}
