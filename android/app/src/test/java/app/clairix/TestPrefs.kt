package app.clairix

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * A context whose SharedPreferences live in memory, for code that keeps its
 * state in preferences files: the JVM has no data directory. ContextWrapper(null)
 * alone would hand getApplicationContext() to its missing base, so this one
 * answers for itself.
 */
internal class PrefsContext : ContextWrapper(null) {
    private val files = HashMap<String, InMemoryPrefs>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs(name)

    override fun getApplicationContext(): Context = this

    /** The preferences file [name], as the code under test sees it. */
    fun prefs(name: String): InMemoryPrefs = files.getOrPut(name) { InMemoryPrefs() }
}

/**
 * SharedPreferences in a map. As on Android, an editor's changes land together
 * on apply or commit, with clear() taking effect before the puts.
 */
internal class InMemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = also { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = also { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = also { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = also { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = also { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = also { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = also { pending[key] = null }
        override fun clear(): SharedPreferences.Editor = also { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            clear = false
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            pending.clear()
        }
    }
}
