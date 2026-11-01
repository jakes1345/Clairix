package app.clairix.comms

import android.content.Context
import android.util.Log
import app.clairix.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The version note: relays/latest.json in the repository, the newest Clairix
 * version and the oldest still safe to use, signed with the asset key and
 * published with the relay list by the Relay list workflow. Fetched with it
 * once a day ([RelayDirectory.refresh]) by the same anonymous request
 * everyone makes. Parsed by the same rules as tools/relays/check_latest.py,
 * which keep the two in step.
 */
class LatestRelease(
    /** When the note was written, in Unix seconds; a phone only ever moves to a later one. */
    val issued: Long,
    val versionCode: Int,
    val versionName: String,
    /** The oldest version still safe to use; below it the app stops sending ([sendRefusalFor]). */
    val minSafeVersionCode: Int,
    /** One line on what the newest version fixes, shown with the offer to update. */
    val notes: String,
) {
    companion object {
        const val TYPE = "clairix-latest"
        const val VERSION = 1
        const val MAX_TEXT = 200
        const val MAX_VERSION_NAME = 64
        /** The largest versionCode Android accepts. */
        const val MAX_VERSION_CODE = 2_100_000_000
        private val FIELDS = setOf("type", "version", "issued", "versionCode", "versionName", "minSafeVersionCode", "notes")
        private val VERSION_NAME = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z]+(\\.[0-9A-Za-z]+)*)?$")

        /** The note in [text], or an [IllegalArgumentException] saying what is wrong with it. */
        fun parse(text: String, nowSec: Long): LatestRelease {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON", e) }
            require(doc.optString("type") == TYPE) { "not a Clairix version note" }
            require(doc.opt("version") == VERSION) { "version note format ${doc.opt("version")} is not one this app reads" }
            val unknown = doc.keys().asSequence().filter { it !in FIELDS }.toList()
            require(unknown.isEmpty()) { "unknown field(s) $unknown" }
            val issued = whole(doc, "issued")
            require(issued != null && issued > 0) { "issued must be a positive whole number" }
            require(issued <= nowSec + 3600) { "issued is in the future" }
            val code = whole(doc, "versionCode")
            require(code != null && code in 1..MAX_VERSION_CODE.toLong()) { "versionCode must be a whole number from 1 to $MAX_VERSION_CODE" }
            val minSafe = whole(doc, "minSafeVersionCode")
            require(minSafe != null && minSafe in 1..code) { "minSafeVersionCode must be a whole number from 1 to versionCode" }
            val name = doc.opt("versionName") as? String
            require(name != null && name.length <= MAX_VERSION_NAME && VERSION_NAME.matches(name)) { "versionName must be a version like 3.0.0 or 3.0.0-beta.8" }
            val notes = doc.opt("notes") as? String
            require(notes != null && notes.isNotBlank() && notes.length <= MAX_TEXT && notes.none { it == '\n' || it == '\r' || it == '\t' }) {
                "notes must be one line of text, 1-$MAX_TEXT characters"
            }
            return LatestRelease(issued, code.toInt(), name, minSafe.toInt(), notes)
        }

        private fun whole(doc: JSONObject, field: String): Long? =
            (doc.opt(field) as? Number)?.takeIf { it is Int || it is Long }?.toLong()

        /**
         * Whether a downloaded note replaces [current]: its signature [sig] must
         * check ([verify], the asset keys [app.clairix.nav.AssetSignature] accepts),
         * it must parse, and it must be issued later, so nobody can hand a phone an
         * old note back (one from before a version was found unsafe). Null when it
         * does not.
         */
        fun accept(current: LatestRelease?, raw: ByteArray, sig: String, nowSec: Long, verify: (ByteArray, String) -> Boolean): LatestRelease? {
            require(verify(raw, sig)) { "the version note is not signed by a Clairix asset key" }
            val note = parse(String(raw, Charsets.UTF_8), nowSec)
            return if (current == null || note.issued > current.issued) note else null
        }
    }
}

/** What the app says about updates, from the note it holds and the version it is ([updateNotice]). */
data class UpdateNotice(
    /** The newest release, when it is newer than this build; null otherwise. */
    val newer: LatestRelease?,
    /** Settings > About offers the update. */
    val offer: Boolean,
    /** This build is below the oldest safe version: the banner on the Scan and Comms screens, and no sending. */
    val unsafe: Boolean,
) {
    companion object {
        val NONE = UpdateNotice(null, offer = false, unsafe = false)
    }
}

/**
 * The notice for a build of [versionCode] holding [latest], the newest release
 * the owner has dismissed being [dismissedVersionCode] (0 when none). Below the
 * newest version Settings > About offers the update until the owner dismisses
 * that version; a newer one is offered again. Below the oldest safe version the
 * offer cannot be dismissed and the app is unsafe.
 */
internal fun updateNotice(latest: LatestRelease?, versionCode: Int, dismissedVersionCode: Int): UpdateNotice {
    if (latest == null || versionCode >= latest.versionCode) return UpdateNotice.NONE
    val unsafe = versionCode < latest.minSafeVersionCode
    return UpdateNotice(latest, offer = unsafe || dismissedVersionCode < latest.versionCode, unsafe = unsafe)
}

/**
 * Why an envelope must not be sent, or null when it may. Below the oldest safe
 * version nothing is sent: an emergency brake for a version with a security
 * flaw. An SOS ([alert] [RelayClient.ALERT_SOS]) still goes: someone in danger
 * with an old version is better served by an alert than by none. So does what
 * an SOS cannot reach a member without ([forSos], see [carriesSos]).
 */
internal fun sendRefusalFor(notice: UpdateNotice, alert: String?, forSos: Boolean = false): String? {
    val newer = notice.newer ?: return null
    val sosGoes = alert == RelayClient.ALERT_SOS || forSos
    if (!notice.unsafe || sosGoes) return null
    return "Not sent: this version of Clairix has a security flaw that ${newer.versionName} fixes, and it sends nothing " +
        "but SOS alerts until it is updated. Settings > About has the update."
}

/**
 * Whether a payload of [type] (with group control [op] for group [gid]) is
 * part of getting an SOS out, so the brake below the oldest safe version
 * ([sendRefusalFor]) lets it go: the SOS itself, and the request for a group's
 * member list while an SOS to that group is still being tried
 * ([heldSosGroups], the groups with such a row). Without the second, an SOS to
 * a group whose list on this phone is out of date would wait for an answer to
 * a request the brake never let leave.
 */
internal fun carriesSos(type: String, op: String?, gid: String?, heldSosGroups: Set<String>): Boolean = when (type) {
    CommsWire.T_PANIC -> true
    CommsWire.T_GROUP_CTRL -> op == CommsWire.OP_ROSTER_REQUEST && gid != null && gid in heldSosGroups
    else -> false
}

/**
 * The version note this phone holds, the last one downloaded and verified with
 * the relay list ([RelayDirectory.refresh]), and what it means for this build:
 * the offer in Settings > About, the banner on the Scan and Comms screens, and
 * the brake on sending ([sendRefusalFor], applied by [RelayClient.send]).
 */
object AppUpdate {

    private const val TAG = "AppUpdate"
    const val ASSET = "latest.json"
    const val MAX_BYTES = 16L * 1024
    private const val PREFS = "app_update"
    private const val KEY_DISMISSED = "dismissed_version_code"

    @Volatile private var appContext: Context? = null
    @Volatile private var latest: LatestRelease? = null
    @Volatile private var dismissed = 0

    private val _notice = MutableStateFlow(UpdateNotice.NONE)
    /** What the screens show; [UpdateNotice.NONE] until a note says this build is behind. */
    val notice: StateFlow<UpdateNotice> = _notice.asStateFlow()

    private fun cacheFile(context: Context) = File(File(context.applicationContext.filesDir, "relays"), ASSET)

    /**
     * Loads the note kept from the last download, and the version the owner
     * dismissed, once per process (ClairixApp): a few hundred bytes and one
     * preference, so the banner and the brake hold from the first moment.
     */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        synchronized(this) {
            if (appContext != null) return
            dismissed = runCatching { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DISMISSED, 0) }.getOrDefault(0)
            // Kept only after its signature checked (see [store]).
            latest = runCatching {
                cacheFile(app).takeIf { it.isFile && it.length() <= MAX_BYTES }?.readBytes()?.let {
                    LatestRelease.parse(String(it, Charsets.UTF_8), System.currentTimeMillis() / 1000)
                }
            }.onFailure { Log.w(TAG, "The kept version note is unreadable: ${it.message}") }.getOrNull()
            appContext = app
            publish()
        }
    }

    /** The note held, or null. */
    fun current(): LatestRelease? = latest

    /** Keeps [note], downloaded as [raw] and already verified and found newer, and shows what it means. */
    fun store(context: Context, note: LatestRelease, raw: ByteArray) {
        val file = cacheFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(raw)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        latest = note
        publish()
    }

    /** The owner put off the update to [versionCode]: it is not offered again until a newer version is published. */
    fun dismiss(context: Context, versionCode: Int) {
        dismissed = maxOf(dismissed, versionCode)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_DISMISSED, dismissed).apply()
        publish()
    }

    /** Why an envelope with [alert] must not be sent now ([sendRefusalFor]); null when it may. */
    fun sendRefusal(alert: String?, forSos: Boolean = false): String? = sendRefusalFor(_notice.value, alert, forSos)

    private fun publish() {
        _notice.value = updateNotice(latest, BuildConfig.VERSION_CODE, dismissed)
    }
}
