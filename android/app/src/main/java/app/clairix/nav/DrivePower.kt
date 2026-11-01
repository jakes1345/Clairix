package app.clairix.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.runningFold

/**
 * What the Drive tab's heavy features do about the battery, as decisions apart from
 * the receiver and the loops that act on them.
 *
 * Drive mode streams two million samples a second from a radio stick the phone powers
 * through its own USB port, keeps an audio track playing and polls the OBD-II adapter
 * over Bluetooth; navigation keeps a fix every second and a handful of network timers.
 * Below [CUTOFF_PCT] with the battery not charging, drive mode stops the stick and the
 * adapter until the phone charges, and a drive carries on with its timers stretched to
 * [LOW_BATTERY_TIMER_MS]: whoever relies on the phone for an SOS should arrive with some
 * battery left. Once low, the phone counts as low until it charges or climbs back to
 * [RESUME_PCT], so a reading that wobbles around the line does not start and stop the
 * stick over and over.
 */
object DrivePower {

    /** Below this, with the battery not charging, the stick and the adapter stop. */
    const val CUTOFF_PCT = 15

    /** Back at this (or charging), they may run again. */
    const val RESUME_PCT = 20

    /** Navigation's traffic, report and faster-route timers while the battery is low. */
    const val LOW_BATTERY_TIMER_MS = 5 * 60_000L

    /** The OBD-II gauges while the Drive screen shows them: twice a second is smooth enough for a needle. */
    const val OBD_POLL_ON_SCREEN_MS = 500L

    /** Off screen nothing draws the gauges; once a second keeps the link and the readings alive. */
    const val OBD_POLL_OFF_SCREEN_MS = 1_000L

    /**
     * The scanner's pause before the next channel when the tuner could not lock on one.
     * While a channel talks the scanner waits for its squelch to close, without polling.
     */
    const val RETUNE_PAUSE_MS = 100L

    /** The battery as Android reports it: [levelPct] null when it did not say, [charging] when it is filling or full. */
    data class Reading(val levelPct: Int?, val charging: Boolean)

    /** A [Reading] from ACTION_BATTERY_CHANGED's level, scale and status extras. */
    fun reading(level: Int, scale: Int, status: Int): Reading {
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        // Plugged in is not enough: a weak car socket, or a phone too hot to charge, still drains.
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Reading(pct, charging)
    }

    /**
     * Whether the battery counts as low, given whether it did last time ([wasLow]). A
     * reading without a level never counts: nothing is stopped on a guess.
     */
    fun lowBattery(reading: Reading, wasLow: Boolean): Boolean {
        val pct = reading.levelPct ?: return false
        if (reading.charging) return false
        return if (wasLow) pct < RESUME_PCT else pct < CUTOFF_PCT
    }

    /** [readings] as low or not, starting from [initial] and emitting only changes. */
    fun lowBatteryStates(readings: Flow<Reading>, initial: Boolean = false): Flow<Boolean> =
        readings.runningFold(initial) { wasLow, r -> lowBattery(r, wasLow) }.distinctUntilChanged()

    /** A navigation timer's period: [normalMs], or at least [LOW_BATTERY_TIMER_MS] while the battery is low. */
    fun navTimerMs(normalMs: Long, lowBattery: Boolean): Long =
        if (lowBattery) maxOf(normalMs, LOW_BATTERY_TIMER_MS) else normalMs

    /** The pause between rounds of OBD-II readings. */
    fun obdPollMs(onScreen: Boolean): Long = if (onScreen) OBD_POLL_ON_SCREEN_MS else OBD_POLL_OFF_SCREEN_MS
}

/** The battery as Android reports it, from the sticky ACTION_BATTERY_CHANGED broadcast; see [DrivePower]. */
object BatteryWatch {

    /** The battery now, or null when Android would not say. */
    fun now(context: Context): DrivePower.Reading? {
        val sticky = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        return read(sticky)
    }

    /**
     * The battery from now on, while collected. The broadcast is sticky, so the current
     * reading comes at once; after that, a reading whenever the level or charging changes
     * (Android also sends one for every change of voltage or temperature, which this drops).
     */
    fun readings(context: Context): Flow<DrivePower.Reading> = callbackFlow {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                trySend(read(intent))
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun read(intent: Intent): DrivePower.Reading = DrivePower.reading(
        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    )
}
