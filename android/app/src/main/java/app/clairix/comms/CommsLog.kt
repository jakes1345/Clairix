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
 * On disk it is sealed under [VaultBox], a key that works only while the phone
 * is unlocked: the entries name who was called and
 * when, who was messaged and when an SOS went out, which is exactly the record
 * a copy of the app's files must not give up. A log an earlier version kept in
 * the clear is read once and resealed, and the plaintext removed.
 */
object CommsLog {

    private const val PREFS = "comms_log"
    /** The plaintext key earlier versions wrote; read once, then removed. */
    private const val KEY_LEGACY = "entries"
    /** The entries as a JSON array, sealed by [VaultBox] (by [KeystoreBox] before it), base64. */
    private const val KEY_SEALED = "sealed"
    private const val MAX_ENTRIES = 150

    data class Entry(val ts: Long, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Whether the log on disk is in memory; not while the phone is locked, or before it could be read. */
    private var loaded = false

    /** What was logged while the phone was locked: kept in memory only, and added to the log after the unlock. */
    private val whileLocked = ArrayList<Entry>()

    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!Vault.sealed.value) loadLocked()
        }
    }

    fun add(text: String) {
        // Never to logcat: entries name contacts, calls and SOS events, and logcat
        // is in every bug report and readable over ADB.
        synchronized(this) {
            val entry = Entry(System.currentTimeMillis(), text)
            if (!loaded) {
                whileLocked += entry
                while (whileLocked.size > MAX_ENTRIES) whileLocked.removeAt(0)
                _entries.value = whileLocked.toList()
                return
            }
            val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = next
            persistLocked(next)
        }
    }

    /** The phone locked: the log leaves memory, and what is logged meanwhile waits for the unlock. */
    fun seal() {
        synchronized(this) {
            loaded = false
            _entries.value = whileLocked.toList()
        }
    }

    /** The phone was unlocked: the log is read again, with what was logged meanwhile added to it. */
    fun open() {
        synchronized(this) { if (!loaded) loadLocked() }
    }

    fun clear() {
        synchronized(this) {
            whileLocked.clear()
            _entries.value = emptyList()
            loaded = true
            persistLocked(emptyList())
        }
    }

    /**
     * Reads the log from disk. One sealed under [KeystoreBox] (before the vault)
     * or kept in the clear (before that) is sealed again under [VaultBox]. A log
     * that cannot be read now (the Keystore refused) is left as it is, never
     * written over: the next unlock tries again.
     */
    private fun loadLocked() {
        val p = prefs ?: return
        val sealed = p.getString(KEY_SEALED, null)
        val read = runCatching {
            if (sealed != null) {
                val opened = VaultBox.open(Base64.decode(sealed, Base64.NO_WRAP))
                parse(String(opened.plain, Charsets.UTF_8)) to opened.legacy
            } else {
                parse(p.getString(KEY_LEGACY, null) ?: "[]") to p.contains(KEY_LEGACY)
            }
        }.getOrNull() ?: return
        val (onDisk, rewrite) = read
        loaded = true
        val merged = (onDisk + whileLocked).takeLast(MAX_ENTRIES)
        val grew = whileLocked.isNotEmpty()
        whileLocked.clear()
        _entries.value = merged
        if (rewrite || grew) persistLocked(merged)
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
        val sealed = runCatching { Base64.encodeToString(VaultBox.encrypt(arr.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) }.getOrNull() ?: return
        val editor = prefs?.edit() ?: return
        editor.remove(KEY_LEGACY)
        editor.putString(KEY_SEALED, sealed)
        editor.apply()
    }
}

/** The last characters of a relay envelope id, enough to tell envelopes apart in the log. */
internal fun shortId(id: String): String = id.takeLast(6)
