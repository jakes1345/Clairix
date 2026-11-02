package app.clairix.comms

/**
 * When the owner is told that Android stopped the background connection, as a
 * rule apart from Android so it can be tested.
 *
 * The connection watchdog ([LinkWatchdog]) finds nothing holding the link (the
 * service was stopped, or the process killed and started again by the alarm)
 * and asks for the connection service again. Android refuses that start from
 * the background unless Clairix may run unrestricted
 * (ForegroundServiceStartNotAllowedException). One refusal can be a moment
 * Android was busy; [NOTIFY_AFTER] in a row, ten minutes or so apart, means it
 * will keep refusing, and calls will not ring until the owner lets Clairix run.
 * They are told once; the count starts again when the service runs, so a later
 * stop is told again.
 */
internal object RestartRefusals {

    /** Refusals in a row before the owner is told. */
    const val NOTIFY_AFTER = 2

    /** [refusals] in a row so far, and whether the owner has been told since the service last ran. */
    data class State(val refusals: Int = 0, val notified: Boolean = false)

    /** The new state, and whether to post the notification now. */
    data class Outcome(val state: State, val notify: Boolean)

    /** The watchdog asked for the service, and Android [refused] the start or took it. */
    fun onAttempt(state: State, refused: Boolean): Outcome {
        if (!refused) return Outcome(state.copy(refusals = 0), notify = false)
        val refusals = state.refusals + 1
        val notify = refusals >= NOTIFY_AFTER && !state.notified
        return Outcome(State(refusals, notified = state.notified || notify), notify)
    }

    /** The service went foreground: the connection runs again. */
    fun onRunning(): State = State()

    /** Whether the Comms screen shows the steps to let Clairix run: the owner was told, and the service has not run since. */
    fun stopped(state: State): Boolean = state.notified
}
