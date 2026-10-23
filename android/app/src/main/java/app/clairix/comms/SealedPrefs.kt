package app.clairix.comms

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Key/value preferences sealed under [KeystoreBox]: the values are one JSON
 * object, encrypted as a whole and kept as a single string in a private
 * preferences file of its own, so nothing in it is readable from a copy of the
 * app's files. The surface is the part of [android.content.SharedPreferences]
 * the comms module uses.
 *
 * A plain preferences file of the same [name] written by an earlier version is
 * read once into the sealed one and emptied.
 */
class SealedPrefs(context: Context, name: String) {

    private val app = context.applicationContext
    private val file = app.getSharedPreferences("${name}_sealed", Context.MODE_PRIVATE)
    private val values: JSONObject

    init {
        val sealed = file.getString(KEY_BLOB, null)
        values = sealed?.let { runCatching { JSONObject(KeystoreBox.decryptString(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull() }
            ?: JSONObject()
        val legacy = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val old = legacy.all
        if (old.isNotEmpty()) {
            for ((key, value) in old) {
                if (value == null || values.has(key)) continue
                values.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            }
            if (persist()) legacy.edit().clear().commit()
        }
    }

    @Synchronized
    fun getString(key: String, default: String?): String? =
        if (values.has(key) && !values.isNull(key)) values.optString(key) else default

    @Synchronized
    fun getBoolean(key: String, default: Boolean): Boolean = values.optBoolean(key, default)

    @Synchronized
    fun getLong(key: String, default: Long): Long = values.optLong(key, default)

    @Synchronized
    fun getInt(key: String, default: Int): Int = values.optInt(key, default)

    @Synchronized
    fun contains(key: String): Boolean = values.has(key)

    fun edit(): Editor = Editor()

    /** Changes gathered and written in one go, as [android.content.SharedPreferences.Editor] does. */
    inner class Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clearFirst = false

        /** A null value removes the key, as the platform editor does. */
        fun putString(key: String, value: String?): Editor = also { changes[key] = value }
        fun putBoolean(key: String, value: Boolean): Editor = also { changes[key] = value }
        fun putLong(key: String, value: Long): Editor = also { changes[key] = value }
        fun putInt(key: String, value: Int): Editor = also { changes[key] = value }
        fun remove(key: String): Editor = also { changes[key] = null }
        fun clear(): Editor = also { clearFirst = true }

        fun apply() { commit() }

        /** Writes the changes; false when the sealed file could not be written (the values stay in memory). */
        fun commit(): Boolean = synchronized(this@SealedPrefs) {
            if (clearFirst) for (key in values.keys().asSequence().toList()) values.remove(key)
            for ((key, value) in changes) if (value == null) values.remove(key) else values.put(key, value)
            persist()
        }
    }

    /** Caller holds the monitor. */
    private fun persist(): Boolean {
        val blob = runCatching { Base64.encodeToString(KeystoreBox.encryptString(values.toString()), Base64.NO_WRAP) }.getOrNull()
            ?: return false
        return file.edit().putString(KEY_BLOB, blob).commit()
    }

    private companion object {
        const val KEY_BLOB = "sealed"
    }
}
