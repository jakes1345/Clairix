package app.clairix.screenshots

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import app.clairix.AppSettings
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.ClairixThemeName

/**
 * Hosts a screen for a screenshot: the chosen theme, selected in AppSettings the
 * way the Appearance picker does it, plus what an Activity would otherwise
 * provide: a result registry for the permission launchers, and preferences that
 * live in memory.
 */
@Composable
internal fun ScreenshotHost(theme: ClairixThemeName, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val context = remember(base) { InMemoryPrefsContext(base) }
    AppSettings.setTheme(context, theme)
    CompositionLocalProvider(
        LocalContext provides context,
        LocalActivityResultRegistryOwner provides NoResultsOwner
    ) {
        ClairixTheme(theme = theme) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

private object NoResultsOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) = Unit
    }
}

/**
 * A context whose SharedPreferences live in memory, since layoutlib has no data
 * directory, and which reports a system service layoutlib does not implement
 * (biometrics) as absent, the way a phone without the hardware does.
 */
private class InMemoryPrefsContext(base: Context) : ContextWrapper(base) {
    private val files = HashMap<String, InMemoryPrefs>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        files.getOrPut(name) { InMemoryPrefs() }
    override fun getSystemService(name: String): Any? = runCatching { super.getSystemService(name) }.getOrNull()
    override fun getApplicationContext(): Context = this
}

private class InMemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = this.also { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this.also { pending[key] = values }
        override fun putInt(key: String, value: Int) = this.also { pending[key] = value }
        override fun putLong(key: String, value: Long) = this.also { pending[key] = value }
        override fun putFloat(key: String, value: Float) = this.also { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = this.also { pending[key] = value }
        override fun remove(key: String) = this.also { pending[key] = null }
        override fun clear() = this.also { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            pending.clear()
        }
    }
}
