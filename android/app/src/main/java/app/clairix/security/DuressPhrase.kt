package app.clairix.security

import android.content.Context
import android.util.Base64
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.TimelineEvent
import app.clairix.TimelineLog
import app.clairix.comms.CommsRepository
import app.clairix.comms.KeystoreBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * A duress phrase: an ordinary-looking sentence the owner can type into any
 * conversation. The message goes out as written, so anyone watching the screen
 * sees a normal chat; underneath, the SOS fires with the last known position.
 *
 * The phrase is kept encrypted under the comms Keystore key ([KeystoreBox]) and
 * compared in memory only. It is never written to a log, a toast, a
 * notification or the timeline — the timeline entry says only that the SOS was
 * triggered this way.
 */
object DuressPhrase {

    private const val PREFS = "duress"
    private const val KEY_PHRASE = "phrase_enc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The decrypted, normalised phrase, held after the first check so the Keystore is not hit per message. */
    @Volatile
    private var cached: String? = null

    @Volatile
    private var installed = false

    /** Whitespace-collapsed, lower-cased: "case-insensitive exact" tolerant of double spaces. */
    private fun normalise(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    /** A phrase must have at least this many words… */
    const val MIN_WORDS = 3
    /** …or at least this many characters (after collapsing spaces). */
    const val MIN_CHARS = 12

    /** What the owner is told when a phrase is too short; shown by the Settings screen. */
    const val TOO_SHORT_MESSAGE =
        "Too short. Use at least $MIN_WORDS words or $MIN_CHARS characters — a short phrase like " +
            "\"ok\" is typed by accident and would send an SOS."

    /**
     * Why [phrase] cannot be the duress phrase, or null when it can. A blank phrase is
     * valid: it clears the setting. Anything else needs [MIN_WORDS] words or
     * [MIN_CHARS] characters, so an everyday reply ("ok", "yes", "on my way") cannot
     * trigger an SOS by accident.
     */
    fun validate(phrase: String): String? {
        val norm = normalise(phrase)
        if (norm.isEmpty()) return null
        val words = norm.split(' ').size
        return if (words >= MIN_WORDS || norm.length >= MIN_CHARS) null else TOO_SHORT_MESSAGE
    }

    /**
     * Saves [phrase] encrypted. A blank phrase clears it.
     *
     * @throws IllegalArgumentException when [validate] rejects the phrase; nothing is
     *   saved and an existing phrase is kept.
     */
    fun set(context: Context, phrase: String) {
        validate(phrase)?.let { throw IllegalArgumentException(it) }
        val norm = normalise(phrase)
        if (norm.isEmpty()) { clear(context); return }
        val blob = Base64.encodeToString(KeystoreBox.encryptString(norm), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_PHRASE, blob).apply()
        cached = norm
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PHRASE).apply()
        cached = null
    }

    fun isConfigured(context: Context): Boolean = prefs(context).contains(KEY_PHRASE)

    /**
     * True when [body] is the duress phrase. Decrypts on first use; a Keystore
     * that refuses (key gone after an unpair, say) reads as "not configured".
     */
    fun check(body: String, context: Context): Boolean {
        val phrase = cached ?: load(context) ?: return false
        return constantTimeEquals(normalise(body), phrase)
    }

    /**
     * Checks [body] and, on a match, raises the SOS: [Registry.setPanicActive]
     * with the last known location from [Registry.status], and a PANIC entry on
     * the timeline. Returns whether it fired.
     */
    fun checkAndTrigger(body: String, context: Context): Boolean {
        if (!check(body, context)) return false
        trigger(context.applicationContext)
        return true
    }

    /**
     * Hooks the comms send path once per process: every outgoing text is
     * checked before it is queued. Call from the activity and the service after
     * [CommsRepository.init]. The message itself still goes out unchanged.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        CommsRepository.addSendHook { body -> checkAndTrigger(body, app) }
        installed = true
    }

    private fun trigger(app: Context) {
        val s = Registry.status.value
        val lat = s.lat
        val lon = s.lon
        Registry.setPanicActive(true)
        scope.launch {
            val now = System.currentTimeMillis()
            TimelineLog.record(
                app,
                TimelineEvent(
                    id = "panic_duress@$now",
                    kind = EventKind.PANIC,
                    ts = now,
                    title = "SOS triggered by duress phrase",
                    detail = if (lat != null && lon != null) "Last known position attached" else "No GPS fix to attach",
                    severity = Severity.CRITICAL,
                    lat = lat, lon = lon
                ),
                dedupeKey = "panic_duress"
            )
        }
    }

    private fun load(context: Context): String? {
        val blob = prefs(context).getString(KEY_PHRASE, null) ?: return null
        val phrase = runCatching { KeystoreBox.decryptString(Base64.decode(blob, Base64.NO_WRAP)) }.getOrNull() ?: return null
        cached = phrase
        return phrase
    }

    /** Compares digests so timing does not leak how much of the phrase matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val x = md.digest(a.toByteArray(Charsets.UTF_8))
        val y = md.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(x, y)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
