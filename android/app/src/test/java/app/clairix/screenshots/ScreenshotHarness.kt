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
import java.io.File
import java.nio.file.Files

/**
 * Hosts a screen for a screenshot: the chosen theme, selected in AppSettings the
 * way the Appearance picker does it, plus what an Activity would otherwise
 * provide: a result registry for the permission launchers, preferences that
 * live in memory, and a data directory in a temporary folder.
 */
@Composable
internal fun ScreenshotHost(theme: ClairixThemeName, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val context = remember(base) { ScreenshotContext(base) }
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
 * The data directory of the screens drawn in this test process: a temporary
 * folder, deleted when the process ends. It is one for all of them, as the app
 * has one on a phone, because what reads it is one per process too: AssetKeys
 * and AppUpdate keep the first context they are handed. (Not named dataDir:
 * inside a Context that name is the context's own getDataDir(), null here.)
 */
private val screenshotData: File by lazy {
    Files.createTempDirectory("screenshots").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }
}

/**
 * A context for a screen drawn by layoutlib, which has no data directory. Its
 * SharedPreferences live in memory and its files in [screenshotData], and it
 * reports a system service layoutlib does not implement (biometrics) as absent,
 * the way a phone without the hardware does.
 *
 * layoutlib answers getFilesDir(), getNoBackupFilesDir() and getCacheDir() with
 * null, and File(null, "relays") is the path "relays" in the working directory.
 * Without the three below, what a screen keeps would be written into
 * android/app: the Comms setup screen asks RelayDirectory for newer lists, and
 * the ones it downloads are kept as relays/latest.json and the like.
 */
internal class ScreenshotContext(base: Context) : ContextWrapper(base) {
    private val prefs = HashMap<String, InMemoryPrefs>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { InMemoryPrefs() }
    override fun getSystemService(name: String): Any? = runCatching { super.getSystemService(name) }.getOrNull()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir("files")
    override fun getNoBackupFilesDir(): File = dir("no_backup")
    override fun getCacheDir(): File = dir("cache")
    private fun dir(name: String) = File(screenshotData, name).apply { mkdirs() }
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
