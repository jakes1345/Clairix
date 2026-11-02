package app.clairix.analysis

import android.content.Context
import android.util.Log
import app.clairix.comms.TorTransport
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** One app on the stalkerware list: its main name first, then the other names it is sold under. */
data class StalkerwareFamily(val names: List<String>) {
    val name: String get() = names.first()
    val aliases: List<String> get() = names.drop(1)
}

/** How an installed app matched the list: by one of its signing certificates, its package name, or both. */
data class IocMatch(val family: StalkerwareFamily, val byCertificate: Boolean, val byPackage: Boolean)

/**
 * The stalkerware list: the package names and signing-certificate fingerprints of known
 * stalkerware, converted by tools/ioc/convert.py from the stalkerware indicators Julien Voisin and
 * Tek maintain for the Echap non-profit (github.com/AssoEchap/stalkerware-indicators, CC BY 4.0).
 * [parse] refuses what the converter's --check refuses; keep the two in step.
 */
class IocList(
    /** When the list was converted, in Unix seconds; a phone only ever moves to a later one. */
    val issued: Long,
    val families: List<StalkerwareFamily>,
    private val packages: Map<String, Int>,
    private val certs: Map<String, Int>,
) {
    val packageCount: Int get() = packages.size
    val certificateCount: Int get() = certs.size

    /**
     * The app [packageName] belongs to, by its name or by one of [certDigests] (uppercase hex
     * SHA-1 or SHA-256 fingerprints of the certificates it is signed with); null when neither is
     * listed. A certificate is the stronger evidence: anyone can pick a package name, nobody can
     * sign with another developer's key.
     */
    fun match(packageName: String, certDigests: Collection<String>): IocMatch? {
        val byCertificate = certDigests.firstNotNullOfOrNull { certs[it.uppercase(Locale.ROOT)] }
        val byName = packages[packageName]
        val index = byCertificate ?: byName ?: return null
        return IocMatch(families[index], byCertificate = byCertificate != null, byPackage = byName == index)
    }

    companion object {
        const val TYPE = "clairix-stalkerware-ioc"
        const val VERSION = 1
        const val MAX_FAMILIES = 2000
        const val MAX_NAMES = 50
        const val MAX_TEXT = 100
        const val MAX_ENTRIES = 50_000
        const val MAX_PACKAGE = 255
        const val MAX_JSON_BYTES = 8L * 1024 * 1024
        private val FIELDS = setOf("type", "version", "issued", "source", "licence", "names", "packages", "certs")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val DIGEST = Regex("^(?:[0-9A-F]{40}|[0-9A-F]{64})$")

        /**
         * The list in [gz], gzip of the converter's JSON, or an [IllegalArgumentException] saying
         * what is wrong with it. A list issued more than an hour after [nowSec] is refused. With
         * [nowSec] null the issue time is not compared with the phone's clock: that is how the copy
         * built into the app and a download already verified are read, because a clock set back
         * (by hand, or a phone that booted before network time) must not drop the list it has.
         */
        fun parse(gz: ByteArray, nowSec: Long?): IocList =
            parseJson(String(gunzip(gz, MAX_JSON_BYTES), Charsets.UTF_8), nowSec)

        fun parseJson(text: String, nowSec: Long?): IocList {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON") }
            val unknown = doc.keys().asSequence().filter { it !in FIELDS }.toList()
            require(unknown.isEmpty()) { "unknown field(s) $unknown" }
            require(doc.opt("type") == TYPE) { "not a Clairix stalkerware list" }
            require(doc.opt("version") == VERSION) { "stalkerware list version ${doc.opt("version")} is not one this app reads" }
            val issued = (doc.opt("issued") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
            require(issued != null && issued > 0) { "issued must be a positive whole number" }
            require(nowSec == null || issued <= nowSec + 3600) { "issued is in the future" }
            for (field in listOf("source", "licence")) {
                val v = doc.opt(field) as? String
                require(v != null && v.length <= 500) { "$field must be text" }
            }
            val names = doc.opt("names") as? JSONArray
            require(names != null && names.length() in 1..MAX_FAMILIES) { "names must list 1-$MAX_FAMILIES apps" }
            val families = ArrayList<StalkerwareFamily>(names.length())
            for (i in 0 until names.length()) {
                val arr = names.opt(i) as? JSONArray
                require(arr != null && arr.length() in 1..MAX_NAMES) { "names[$i] must list 1-$MAX_NAMES names" }
                val list = (0 until arr.length()).map { j ->
                    val v = arr.opt(j) as? String
                    require(v != null && oneLine(v)) { "names[$i] holds something that is not one line of 1-$MAX_TEXT characters" }
                    v
                }
                families += StalkerwareFamily(list)
            }
            val packages = table(doc, "packages", PACKAGE, families.size)
            val certs = table(doc, "certs", DIGEST, families.size)
            require(packages.isNotEmpty()) { "packages is empty" }
            return IocList(issued, families, packages, certs)
        }

        private fun oneLine(v: String): Boolean =
            v.isNotEmpty() && v.length <= MAX_TEXT && v == v.trim() && v.none { it == '\n' || it == '\r' || it == '\t' }

        private fun table(doc: JSONObject, field: String, pattern: Regex, families: Int): Map<String, Int> {
            val o = doc.opt(field) as? JSONObject
            require(o != null && o.length() <= MAX_ENTRIES) { "$field must map at most $MAX_ENTRIES keys to apps" }
            val out = HashMap<String, Int>(o.length() * 2)
            for (key in o.keys()) {
                require(key.length <= MAX_PACKAGE && pattern.matches(key)) { "$field: $key is not a valid key" }
                val index = o.opt(key)
                require(index is Int && index in 0 until families) { "$field: $key points at no app" }
                out[key] = index
            }
            return out
        }

        /**
         * Whether a downloaded list replaces [current]: its signature [sig] must check against
         * the asset key ([verify]), it must parse, and it must be issued later, so nobody can hand
         * a phone an older list back (one an app has since been added to). Null when it does not.
         */
        fun accept(current: IocList?, raw: ByteArray, sig: String, nowSec: Long, verify: (ByteArray, String) -> Boolean): IocList? {
            if (!verify(raw, sig)) throw IllegalArgumentException("the stalkerware list is not signed by the Clairix release key")
            val list = parse(raw, nowSec)
            return if (current == null || list.issued > current.issued) list else null
        }

        /** The bytes inside gzip [data], refusing more than [max] of them. */
        internal fun gunzip(data: ByteArray, max: Long): ByteArray = try {
            GZIPInputStream(ByteArrayInputStream(data)).use { readCapped(it, max) }
        } catch (e: IOException) {
            throw IllegalArgumentException("not a gzip file of at most $max bytes")
        }

        internal fun readCapped(input: InputStream, max: Long): ByteArray {
            val out = ByteArrayOutputStream()
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
    }
}

/**
 * The stalkerware list on this phone: the copy built into the app (src/suite/assets, so only the
 * editions with the stalkerware scan carry it) or a newer one downloaded since and kept only once
 * its signature checked, exactly as [app.clairix.comms.RelayDirectory] keeps the relay list. The
 * tools/ioc workflow publishes the list, signed with the asset key, as the `ioc` release.
 */
object StalkwareIoc {

    private const val TAG = "StalkwareIoc"
    private const val RELEASE = "https://github.com/jakes1345/Clairix/releases/download/ioc/ioc.json.gz"
    private const val ASSET = "ioc.json.gz"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val SIGNATURE_MAX_BYTES = 1024L
    private const val PREFS = "stalkerware_list"
    private const val KEY_NEXT_TRY = "next_try"
    /** How often the release is asked for a newer list. */
    const val REFRESH_INTERVAL_MS = 24 * 3600_000L
    /** How soon a refresh that failed (offline, the release down) is tried again. */
    private const val RETRY_INTERVAL_MS = 3600_000L

    private val _list = MutableStateFlow<IocList?>(null)
    val list: StateFlow<IocList?> = _list.asStateFlow()

    private val lock = Mutex()

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

    private fun cacheFile(context: Context) = File(File(context.applicationContext.filesDir, "ioc"), ASSET)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nowSec() = System.currentTimeMillis() / 1000

    /** The list this phone has, loading it on first use: the built-in copy or a newer verified download; null in an edition without one. */
    fun current(context: Context): IocList? {
        _list.value?.let { return it }
        synchronized(this) {
            _list.value?.let { return it }
            val app = context.applicationContext
            // Neither copy is compared with the phone's clock: the built-in one shipped in the APK,
            // and the cached one was kept only after its signature and issue time checked (see
            // [refresh]). A clock set back must not leave the phone matching against nothing.
            val bundled = runCatching {
                app.assets.open(ASSET).use { IocList.parse(IocList.readCapped(it, MAX_BYTES), nowSec = null) }
            }.onFailure { Log.i(TAG, "No built-in stalkerware list: ${it.message}") }.getOrNull()
            val cached = runCatching {
                cacheFile(app).takeIf { it.isFile }?.inputStream()?.use { IocList.parse(IocList.readCapped(it, MAX_BYTES), nowSec = null) }
            }.getOrNull()
            val best = listOfNotNull(bundled, cached).maxByOrNull { it.issued }
            _list.value = best
            return best
        }
    }

    /** Asks the release for a newer list unless the last try was recent: a day after a success, an hour after a failure. */
    suspend fun refreshIfStale(context: Context): Boolean {
        if (System.currentTimeMillis() < prefs(context).getLong(KEY_NEXT_TRY, 0L)) return true
        return refresh(context)
    }

    /** Downloads the list and its signature and keeps it when it is newer. False when that failed. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val ok = try {
                val current = current(app)
                val raw = fetch(RELEASE, MAX_BYTES)
                val sig = String(fetch("$RELEASE.sig", SIGNATURE_MAX_BYTES), Charsets.US_ASCII).trim()
                val newer = IocList.accept(current, raw, sig, nowSec(), AssetSignature::verify)
                if (newer != null) {
                    val file = cacheFile(app)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(raw)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    _list.value = newer
                    Log.i(TAG, "Stalkerware list updated: ${newer.families.size} apps, issued ${newer.issued}")
                }
                true
            } catch (e: IOException) {
                Log.i(TAG, "Stalkerware list unavailable: ${e.message}")
                false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Stalkerware list refused: ${e.message}")
                false
            }
            val next = System.currentTimeMillis() + if (ok) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS
            prefs(app).edit().putLong(KEY_NEXT_TRY, next).apply()
            ok
        }
    }

    private fun fetch(url: String, max: Long): ByteArray =
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > max) throw IOException("$url is larger than $max bytes")
            body.byteStream().use { IocList.readCapped(it, max) }
        }
}
