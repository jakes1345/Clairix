package app.clairix.nav

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One key on the asset key list. */
data class AssetKey(
    /** An Ed25519 public key, 32 bytes in base64, as android/asset-signing.pub holds the built-in one. */
    val key: String,
    /** "current", the key files are signed with now, or "next", the one they will be signed with. */
    val role: String,
    /** Revoked: nothing it signed is accepted any more. */
    val revoked: Boolean,
)

/**
 * The asset key list: relays/keys.json in the repository, published with the
 * relay list as the `relays` release. It names the keys published files (the
 * relay list, the version note, maps, work zones, the stalkerware list) may be
 * signed with, and it is signed itself with one key only: the one built into
 * the app ([AssetSignature.publicKey]), the root. A key can so be replaced, or a
 * leaked one revoked, without a new app. Parsed by the same rules as
 * tools/relays/check_keys.py, which keep the two in step.
 */
class AssetKeyList(
    /** When the list was written, in Unix seconds; a phone only ever moves to a later one. */
    val issued: Long,
    val keys: List<AssetKey>,
) {
    /** The keys a published file may be signed with: every listed key that is not revoked. */
    val accepted: List<String> get() = keys.filter { !it.revoked }.map { it.key }

    companion object {
        const val TYPE = "clairix-asset-keys"
        const val VERSION = 1
        const val MAX_KEYS = 8
        val ROLES = setOf("current", "next")
        private val FIELDS = setOf("type", "version", "issued", "keys")
        private val KEY_FIELDS = setOf("key", "role", "revoked")
        private val KEY = Regex("^[A-Za-z0-9+/]{43}=?$")

        /** The list in [text], or an [IllegalArgumentException] saying what is wrong with it. */
        fun parse(text: String, nowSec: Long): AssetKeyList {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON", e) }
            require(doc.optString("type") == TYPE) { "not a Clairix key list" }
            require(doc.opt("version") == VERSION) { "key list version ${doc.opt("version")} is not one this app reads" }
            val unknown = doc.keys().asSequence().filter { it !in FIELDS }.toList()
            require(unknown.isEmpty()) { "unknown field(s) $unknown" }
            val issued = (doc.opt("issued") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
            require(issued != null && issued > 0) { "issued must be a positive whole number" }
            require(issued <= nowSec + 3600) { "issued is in the future" }
            val arr = doc.optJSONArray("keys")
            require(arr != null && arr.length() in 1..MAX_KEYS) { "keys must list 1-$MAX_KEYS keys" }
            val keys = ArrayList<AssetKey>(arr.length())
            val seen = HashSet<String>()
            val live = HashMap<String, Int>()
            for (i in 0 until arr.length()) {
                val where = "key ${i + 1}"
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("$where: not an object")
                require(o.keys().asSequence().toSet() == KEY_FIELDS) { "$where: must have exactly the fields $KEY_FIELDS" }
                val key = o.opt("key") as? String
                require(key != null && canonicalKey(key)) { "$where: not a 32-byte Ed25519 key in its one base64 spelling" }
                val role = o.opt("role") as? String
                require(role != null && role in ROLES) { "$where: role must be one of $ROLES" }
                val revoked = o.opt("revoked") as? Boolean ?: throw IllegalArgumentException("$where: revoked must be true or false")
                // Padding is the one thing two spellings of a key may differ in, once each is canonical.
                require(seen.add(key.trimEnd('='))) { "$where: the same key is listed twice" }
                if (!revoked) live[role] = (live[role] ?: 0) + 1
                keys += AssetKey(key, role, revoked)
            }
            require(live.values.all { it <= 1 }) { "more than one key that is not revoked in one role" }
            require(live.values.sum() > 0) { "every key is revoked" }
            return AssetKeyList(issued, keys)
        }

        /** Whether [key] is 32 bytes of base64 in its one spelling (padding aside): base64 forgives spare bits. */
        private fun canonicalKey(key: String): Boolean {
            if (!KEY.matches(key)) return false
            val bare = key.trimEnd('=')
            val raw = try { java.util.Base64.getDecoder().decode("$bare=") } catch (_: IllegalArgumentException) { return false }
            return raw.size == 32 && java.util.Base64.getEncoder().withoutPadding().encodeToString(raw) == bare
        }

        /**
         * Whether a downloaded list replaces [current]: [sig] must be the root
         * key's signature over [raw] ([verifyRoot]; never a listed key's, since a
         * list cannot vouch for itself), it must parse, and it must be issued
         * later, so nobody can hand a phone an old list back (one in which a key
         * since revoked was not). Null when it does not.
         */
        fun accept(current: AssetKeyList?, raw: ByteArray, sig: String, nowSec: Long, verifyRoot: (ByteArray, String) -> Boolean): AssetKeyList? {
            require(verifyRoot(raw, sig)) { "the key list is not signed by the key built into Clairix" }
            val list = parse(String(raw, Charsets.UTF_8), nowSec)
            return if (current == null || list.issued > current.issued) list else null
        }

        /** The keys a published file may be signed with while [list] is held: its unrevoked ones; with none, [root] alone. */
        fun acceptedKeys(root: String, list: AssetKeyList?): List<String> = list?.accepted ?: listOf(root)

        /** Whether one of [keys] signed [message] ([signature]); [verify] takes a key, the message and the signature. */
        fun verifiedByAny(keys: List<String>, message: ByteArray, signature: String, verify: (String, ByteArray, String) -> Boolean): Boolean =
            keys.any { key -> runCatching { verify(key.trimEnd('='), message, signature) }.getOrDefault(false) }
    }
}

/**
 * The key list this phone holds: the copy built into the app (assets/keys.json,
 * relays/keys.json as it was when the app was built) or a newer one downloaded
 * with the relay list and checked against the built-in key
 * ([app.clairix.comms.RelayDirectory.refresh]), whichever was issued later.
 * [AssetSignature] checks every published file against the keys it accepts.
 */
object AssetKeys {

    private const val TAG = "AssetKeys"
    const val ASSET = "keys.json"
    const val MAX_BYTES = 16L * 1024

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var held: AssetKeyList? = null

    /** Called at process start (ClairixApp); nothing is read until a key is first needed. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun cacheFile(context: Context) = File(File(context.filesDir, "relays"), ASSET)
    private fun sigFile(context: Context) = File(File(context.filesDir, "relays"), "$ASSET.sig")

    /** The list held, loading it on first use; null when there is none (or before [init]: then the built-in key alone). */
    fun current(): AssetKeyList? {
        if (loaded) return held
        val context = appContext ?: return null
        synchronized(this) {
            if (loaded) return held
            val now = System.currentTimeMillis() / 1000
            val bundled = runCatching {
                context.assets.open(ASSET).use { AssetKeyList.parse(String(readCapped(it), Charsets.UTF_8), now) }
            }.onFailure { Log.w(TAG, "The built-in key list is unreadable: ${it.message}") }.getOrNull()
            // Checked against the built-in key again on every start, so a list
            // signed by the root of another build is not carried into this one.
            val cached = runCatching {
                val file = cacheFile(context)
                val sig = sigFile(context)
                if (!file.isFile || !sig.isFile) return@runCatching null
                val raw = file.inputStream().use { readCapped(it) }
                AssetKeyList.accept(null, raw, sig.readText(Charsets.US_ASCII).trim(), now, AssetSignature::verifyRoot)
            }.onFailure { Log.w(TAG, "The kept key list is refused: ${it.message}") }.getOrNull()
            held = listOfNotNull(bundled, cached).maxByOrNull { it.issued }
            loaded = true
            return held
        }
    }

    /** The keys a published file may be signed with now; see [AssetKeyList.acceptedKeys]. */
    fun accepted(root: String): List<String> = AssetKeyList.acceptedKeys(root, current())

    /** Keeps [list], downloaded as [raw] with the root's signature [sig], already checked and found newer. */
    fun store(context: Context, list: AssetKeyList, raw: ByteArray, sig: String) {
        val app = context.applicationContext
        val file = cacheFile(app)
        file.parentFile?.mkdirs()
        replace(sigFile(app), (sig + "\n").toByteArray(Charsets.US_ASCII))
        replace(file, raw)
        synchronized(this) {
            held = list
            loaded = true
        }
    }

    private fun replace(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("larger than $MAX_BYTES bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
