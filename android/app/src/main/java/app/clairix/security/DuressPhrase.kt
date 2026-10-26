package app.clairix.security

import android.content.Context
import android.util.Base64
import app.clairix.Registry
import app.clairix.comms.CommsRepository
import app.clairix.comms.KeystoreBox
import java.security.MessageDigest

/**
 * A duress phrase: an ordinary-looking sentence the owner can type into any
 * conversation. The message goes out as written, so anyone watching the screen
 * sees a normal chat; underneath, the SOS fires with the last known position.
 *
 * The phrase is kept encrypted under the comms Keystore key ([KeystoreBox]) and
 * compared in memory only. Neither it nor the SOS it raises is written to a
 * log, a toast, a notification or the timeline: a captor who later makes the
 * owner unlock the phone finds no sign that help was called.
 */
object DuressPhrase {

    private const val PREFS = "duress"
    private const val KEY_PHRASE = "phrase_enc"


    /** The decrypted, normalised phrase, held after the first check so the Keystore is not hit per message. */
    @Volatile
    private var cached: String? = null

    @Volatile
    private var installed = false

    /** Whitespace-collapsed, lower-cased: "case-insensitive exact" tolerant of double spaces. */
    private fun normalise(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    /** A phrase must have at least this many words… */
    const val MIN_WORDS = 3
    /** …and at least this many characters, not counting spaces. */
    const val MIN_CHARS = 12

    /** What the owner is told when a phrase is too short; shown by the Duress screen. */
    const val TOO_SHORT_MESSAGE =
        "Too short. Use at least $MIN_WORDS words and $MIN_CHARS letters (spaces don't count) — " +
            "a short phrase like \"on my way\" is typed by accident and would send an SOS."

    /**
     * Why [phrase] cannot be the duress phrase, or null when it can. A blank phrase is
     * valid: it clears the setting. Anything else needs [MIN_WORDS] words *and*
     * [MIN_CHARS] characters other than spaces. Either rule alone lets everyday
     * replies through — "on my way", "see you soon" and "i love you" all have three
     * words — and those must never be able to send an SOS by accident.
     */
    fun validate(phrase: String): String? {
        val norm = normalise(phrase)
        if (norm.isEmpty()) return null
        val words = norm.split(' ').size
        val letters = norm.count { it != ' ' }
        return if (words >= MIN_WORDS && letters >= MIN_CHARS) null else TOO_SHORT_MESSAGE
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
     * True when a phrase is stored that fails [validate] — one saved before the
     * length rule — and so never fires until it is replaced. Decrypts; call off the
     * main thread.
     */
    fun needsReplacing(context: Context): Boolean {
        val phrase = cached ?: load(context) ?: return false
        return validate(phrase) != null
    }

    /**
     * True when [body] is the duress phrase. Decrypts on first use; a Keystore
     * that refuses (key gone after an unpair, say) reads as "not configured".
     *
     * A stored phrase that fails [validate] never matches: one saved before the
     * length rule existed ("ok") would otherwise still fire on an everyday reply.
     * The owner sees the setting as configured and is asked to replace it there.
     */
    fun check(body: String, context: Context): Boolean {
        val phrase = cached ?: load(context) ?: return false
        return matches(body, phrase)
    }

    /** [check] against an already-decrypted, normalised [stored] phrase. */
    internal fun matches(body: String, stored: String): Boolean {
        if (validate(stored) != null) return false
        return constantTimeEquals(normalise(body), stored)
    }

    /**
     * Checks [body] and, on a match, raises a covert SOS ([Registry.setPanicActive]):
     * sent with the last known location, recorded nowhere. Returns whether it fired.
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
        // Sent to every group like the SOS button's, and recorded nowhere on this
        // phone: no timeline entry, no line in the comms log. Whoever makes the
        // owner unlock the phone afterwards must not find that help was called.
        Registry.setPanicActive(true, covert = true)
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
