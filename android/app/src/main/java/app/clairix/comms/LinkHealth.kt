package app.clairix.comms

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Android has been stopping the background connection, as the
 * connection watchdog sees it ([RestartRefusals]). Kept in [SealedPrefs]
 * rather than in memory: Android killing the process is part of what is being
 * counted, and the watchdog's alarm often fires into a fresh one.
 *
 * The second refusal in a row posts one notification
 * ([CommsNotifications.linkStopped]); the Comms screen shows the steps for the
 * phone's maker while [stopped] holds. Both end when the service runs again.
 */
object LinkHealth {

    private const val PREFS = "comms_link_health"
    private const val KEY_REFUSALS = "refusals"
    private const val KEY_NOTIFIED = "notified"

    @Volatile
    private var prefs: SealedPrefs? = null

    private val _stopped = MutableStateFlow(false)

    /** True once the owner has been told that Android stopped the connection, until the service runs again. */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    private fun prefs(context: Context): SealedPrefs =
        prefs ?: synchronized(this) { prefs ?: SealedPrefs(context.applicationContext, PREFS).also { prefs = it } }

    private fun read(context: Context): RestartRefusals.State {
        val p = prefs(context)
        return RestartRefusals.State(p.getInt(KEY_REFUSALS, 0), p.getBoolean(KEY_NOTIFIED, false))
    }

    private fun write(context: Context, state: RestartRefusals.State) {
        prefs(context).edit().putInt(KEY_REFUSALS, state.refusals).putBoolean(KEY_NOTIFIED, state.notified).apply()
        _stopped.value = RestartRefusals.stopped(state)
    }

    /** Reads what was kept into [stopped]; called as comms start. */
    @Synchronized
    fun init(context: Context) {
        _stopped.value = RestartRefusals.stopped(read(context))
    }

    /**
     * The watchdog found nothing holding the link and asked for the service
     * again; Android [refused] the start, or took it. The second refusal in a
     * row tells the owner, once.
     */
    @Synchronized
    fun onWatchdogStart(context: Context, refused: Boolean) {
        val outcome = RestartRefusals.onAttempt(read(context), refused)
        write(context, outcome.state)
        if (refused) {
            CommsLog.add("Android refused to restart the background connection (${outcome.state.refusals} time(s) in a row)")
        }
        if (outcome.notify) CommsNotifications.linkStopped(context)
    }

    /** The service went foreground: the count starts again, and the notification goes. */
    @Synchronized
    fun onRunning(context: Context) {
        val current = read(context)
        if (current == RestartRefusals.State()) return
        write(context, RestartRefusals.onRunning())
        if (current.notified) CommsNotifications.cancelLinkStopped(context)
    }
}
