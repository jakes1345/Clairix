package app.clairix.comms

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks on the background relay connection every ten minutes or so while
 * comms are online.
 *
 * Timers inside the app stop while the phone sleeps, so a socket that died
 * quietly (a carrier dropping the connection, a relay restart the phone never
 * heard about) could stay dead for hours, and calls in that time never rang.
 * An alarm is the one clock Android keeps for a sleeping app. When it fires,
 * a socket that heard from the relay within its heartbeat window is left
 * alone and the phone goes back to sleep at once; a quiet one is probed, and
 * a dead one replaced, with the phone kept awake until the relay answers or
 * the new socket is open, and never longer than [CHECK_BUDGET_MS].
 *
 * If Android stopped the connection service, it is asked for again. Android
 * refuses that from the background unless Clairix may run unrestricted; the
 * second refusal in a row tells the owner ([LinkHealth]).
 */
class LinkWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        CommsRepository.init(app)
        if (!CommsRepository.isRegistered() || !CommsRepository.state.value.online) {
            cancel(app)
            return
        }
        val lock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clairix:relay-watchdog")
            ?.apply { acquire(CHECK_BUDGET_MS + 2_000L) }
        val pending = goAsync()
        scope.launch {
            try {
                val since = SystemClock.elapsedRealtime()
                // The service keeps the link held; if Android stopped it, ask for it again.
                var restarted = false
                if (!LiveLink.held()) {
                    val refused = CommsService.tryStart(app) is ForegroundServiceStartNotAllowedException
                    LinkHealth.onWatchdogStart(app, refused)
                    // Refused, nothing will connect: no reason to keep the phone awake.
                    if (refused) return@launch
                    restarted = true
                }
                val verdict = LiveLink.checkAlive()
                // Heard from recently: done. Otherwise wait for the relay's answer
                // to the probe, or for the new socket, within the budget.
                if (restarted || (verdict != null && verdict != LinkTiming.Check.FRESH)) {
                    withTimeoutOrNull(CHECK_BUDGET_MS) { LiveLink.awaitFrame(since) }
                }
            } finally {
                schedule(app)
                runCatching { if (lock?.isHeld == true) lock.release() }
                pending.finish()
            }
        }
    }

    companion object {
        /** Android lets an idle app's alarm fire about every nine minutes at most. */
        private const val INTERVAL_MS = 10 * 60_000L
        /** The longest the phone is kept awake for one check: a probe's answer, or a reconnect through Tor. */
        private const val CHECK_BUDGET_MS = 25_000L
        private const val ACTION = "app.clairix.comms.LINK_WATCHDOG"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0x5C00_0010,
            Intent(context, LinkWatchdog::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Arms (or re-arms) the next check. Allowed in Doze; needs no exact-alarm permission. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    intent(context)
                )
            }.onFailure { CommsLog.add("Could not schedule the connection check: ${it.message}") }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
        }
    }
}
