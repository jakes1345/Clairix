package app.clairix.analysis

import app.clairix.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A request to settle the running scanner now, for the emulator's power checks
 * (.github/workflows/emulator.yml, .github/scripts/power_budget.py): with the pace
 * forced to settled, `dumpsys location` must show no satellite measurement request of
 * Clairix's that switches the receiver on. Debug builds only: the broadcast that asks
 * for it is declared in src/debug (DebugPaceReceiver), and [forceStill] does nothing in
 * any other build.
 */
object DebugPace {

    /** The broadcast's action; `adb shell am broadcast -a app.clairix.debug.FORCE_STILL -p app.clairix`. */
    const val ACTION_FORCE_STILL = "app.clairix.debug.FORCE_STILL"

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** What [app.clairix.ScanService] listens to in debug builds. */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** Asks the running scanner to settle; nothing outside debug builds. */
    fun forceStill() {
        if (BuildConfig.DEBUG) _requests.tryEmit(Unit)
    }
}
