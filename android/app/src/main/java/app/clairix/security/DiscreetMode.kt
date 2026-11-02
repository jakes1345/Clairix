package app.clairix.security

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import app.clairix.AppSettings
import app.clairix.BuildConfig
import app.clairix.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discreet mode, as the phone sees it: the owner's choice (kept with the app's
 * other settings, and kept by a duress wipe's decoy so the decoy looks the same),
 * the launcher entry that shows, and the notification channel names. What each
 * part says is decided in [LauncherPlan], [ChannelNames], [DiscreetText] and
 * [NotificationPresentation].
 *
 * The launcher disguise exists only where [BuildConfig.LAUNCHER_DISGUISE] is
 * true, the full edition: Google Play's policy does not allow an app to disguise
 * its launcher icon, so the Play edition keeps the Clairix entry and has the rest
 * of discreet mode (notifications, channel names, the lock screen, quick exit).
 */
object DiscreetMode {

    private const val TAG = "DiscreetMode"

    /** In [AppSettings.PREFS_NAME], with the other settings a duress decoy keeps ([AppSettings.DECOY_KEYS]). */
    const val KEY_ON = "discreet_mode"
    const val KEY_DISGUISE = "discreet_disguise"

    /** Whether this edition may show a neutral launcher entry instead of Clairix's. */
    val disguiseAllowed: Boolean get() = BuildConfig.LAUNCHER_DISGUISE

    data class State(val on: Boolean, val disguise: LauncherEntry)

    private val _state = MutableStateFlow(State(on = false, disguise = LauncherPlan.DEFAULT_DISGUISE))
    @Volatile private var loaded = false

    /** The owner's choice, for screens to follow. Read from disk by [load]; false until then. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Reads the stored choice once per process. Cheap afterwards. */
    fun load(context: Context): State {
        if (loaded) return _state.value
        synchronized(this) {
            if (!loaded) {
                val p = context.applicationContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
                _state.value = State(
                    on = p.getBoolean(KEY_ON, false),
                    disguise = LauncherPlan.disguiseNamed(p.getString(KEY_DISGUISE, null))
                )
                loaded = true
            }
        }
        return _state.value
    }

    /** Whether discreet mode is on: what every notification and the lock screen ask. */
    fun on(context: Context): Boolean = load(context).on

    /**
     * Turns discreet mode on or off and picks the disguise, then applies both at
     * once: the launcher entry and every existing channel's name. Notifications
     * already in the shade take the new wording when they are next updated.
     */
    fun set(context: Context, on: Boolean, disguise: LauncherEntry) {
        val app = context.applicationContext
        val chosen = if (disguise.disguise) disguise else LauncherPlan.DEFAULT_DISGUISE
        app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on)
            .putString(KEY_DISGUISE, chosen.name)
            .apply()
        synchronized(this) {
            _state.value = State(on, chosen)
            loaded = true
        }
        applyLauncher(app)
        applyChannelNames(app)
    }

    /**
     * At each start: the launcher entry and the channel names follow the stored
     * choice. After "Wipe Clairix" the choice is gone, so the Clairix entry comes
     * back; after the duress passcode's wipe the decoy keeps it, so the disguise stays.
     */
    fun reconcile(context: Context) {
        val app = context.applicationContext
        load(app)
        applyLauncher(app)
        applyChannelNames(app)
    }

    /** The status-bar icon of a notification outside Comms: the Clairix mark, or a plain dot in discreet mode. */
    fun smallIcon(context: Context): Int = if (on(context)) R.drawable.ic_stat_neutral else R.drawable.ic_stat_clairix

    private fun component(context: Context, entry: LauncherEntry) = ComponentName(context.packageName, entry.component)

    /**
     * Enables exactly the entry [LauncherPlan] picks and disables the others, changing
     * only what differs (a launcher redraws, and may drop a home-screen icon, on every
     * change). The process is left running.
     */
    private fun applyLauncher(context: Context) {
        val s = _state.value
        val pm = context.packageManager
        // The one to show first, so there is never a moment with no launcher entry at all.
        val wanted = LauncherPlan.states(s.on, s.disguise, disguiseAllowed).entries.sortedByDescending { it.value }
        for ((entry, enabled) in wanted) {
            val name = component(context, entry)
            runCatching {
                val now = pm.getComponentEnabledSetting(name)
                // As declared: only the Clairix entry is enabled in the manifest.
                val isEnabled = now == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !entry.disguise)
                if (isEnabled != enabled) {
                    pm.setComponentEnabledSetting(
                        name,
                        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }.onFailure { Log.w(TAG, "could not switch ${entry.name}", it) }
        }
    }

    /**
     * Renames every channel the app has already created to [ChannelNames]' text for
     * the current mode. A channel keeps its id, importance, sound and lock-screen
     * setting; Android updates only the name and description of an existing channel.
     */
    fun applyChannelNames(context: Context) {
        val discreet = _state.value.on
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = runCatching { manager.notificationChannels }.getOrNull() ?: return
        for (channel in channels) {
            if (!ChannelNames.known(channel.id)) continue
            val text = ChannelNames.of(channel.id, discreet)
            if (channel.name?.toString() == text.name && channel.description == text.description) continue
            channel.name = text.name
            channel.description = text.description
            runCatching { manager.createNotificationChannel(channel) }
        }
    }
}

/**
 * Quick exit: Clairix leaves the screen at once and, when a passcode is set,
 * locks, so whoever picks the phone up next finds the lock screen, not the page
 * that was open. A long press on the mark at the top left, and Back on the first
 * screen, do this.
 */
object QuickExit {

    fun leave(context: Context) {
        AppLock.lock()
        context.findActivity()?.moveTaskToBack(true)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
