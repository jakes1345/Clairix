package app.clairix.comms

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A short, on-device record of what the comms module did with the relay and
 * with each envelope: connections made and lost, envelopes that could not be
 * read, calls that arrived too late to ring, sessions that were reset.
 *
 * Failures in this path used to be dropped silently, so a call that never rang
 * left nothing to go on. The owner can read this in COMMS settings and copy it.
 * It never holds message text, only events, numbers of the owner's own contacts
 * and error descriptions, and it stays on the phone.
 *
 * On disk it is sealed under [KeystoreBox]: the entries name who was called and
 * when, who was messaged and when an SOS went out, which is exactly the record
 * a copy of the app's files must not give up. A log an earlier version kept in
 * the clear is read once and resealed, and the plaintext removed.
 */
object CommsLog {

    private const val PREFS = "comms_log"
    /** The plaintext key earlier versions wrote; read once, then removed. */
    private const val KEY_LEGACY = "entries"
    /** The entries as a JSON array, sealed by [KeystoreBox], base64. */
    private const val KEY_SEALED = "sealed"
    private const val MAX_ENTRIES = 150

    data class Entry(val ts: Long, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            val sealed = p.getString(KEY_SEALED, null)
            val loaded = runCatching {
                if (sealed != null) parse(KeystoreBox.decryptString(Base64.decode(sealed, Base64.NO_WRAP)))
                else parse(p.getString(KEY_LEGACY, null) ?: "[]")
            }.getOrDefault(emptyList())
            _entries.value = loaded
            // A log found in the clear goes back sealed before anything else is added to it.
            if (p.contains(KEY_LEGACY)) persistLocked(loaded)
        }
    }

    fun add(text: String) {
        // Never to logcat: entries name contacts, calls and SOS events, and logcat
        // is in every bug report and readable over ADB.
        synchronized(this) {
            val next = (_entries.value + Entry(System.currentTimeMillis(), text)).takeLast(MAX_ENTRIES)
            _entries.value = next
            persistLocked(next)
        }
    }

    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
            persistLocked(emptyList())
        }
    }

    private fun parse(text: String): List<Entry> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.getLong("t"), it.getString("m")) } }
    }

    private fun persistLocked(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("t", e.ts).put("m", e.text))
        // With the Keystore refusing just now, the entries stay in memory and off
        // the disk rather than on it in the clear.
        val sealed = runCatching { Base64.encodeToString(KeystoreBox.encryptString(arr.toString()), Base64.NO_WRAP) }.getOrNull()
        val editor = prefs?.edit() ?: return
        editor.remove(KEY_LEGACY)
        if (sealed != null) editor.putString(KEY_SEALED, sealed) else editor.remove(KEY_SEALED)
        editor.apply()
    }
}

/** The last characters of a relay envelope id, enough to tell envelopes apart in the log. */
internal fun shortId(id: String): String = id.takeLast(6)
