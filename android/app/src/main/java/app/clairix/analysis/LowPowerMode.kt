package app.clairix.analysis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.clairix.AppSettings
import app.clairix.nav.DrivePower
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether low power is on ([LowPower]), for the whole process: the owner's switch,
 * Android's battery saver and the battery level, read from Android's broadcasts.
 *
 * Started once per process ([start], from [app.clairix.ClairixApp]); the scanner, the
 * mesh, cover traffic and the scanner's notification read [state]. The battery
 * broadcast is sticky, so the level is known as soon as the receiver is registered.
 * Every battery reading also goes to [ScanPower], which counts the scanner's drain.
 */
object LowPowerMode {

    /** The owner's "Low power" switch, in the app's settings file ([app.clairix.AppSettings.PREFS_NAME]). */
    const val KEY_SWITCH = "low_power"

    /**
     * Low power now: [reason] null when it is off; [battery] the latest reading (null
     * before the first) and [powerSave] Android's battery saver, for the settings page.
     */
    data class State(
        val reason: LowPower.Reason? = null,
        val switchOn: Boolean = false,
        val powerSave: Boolean = false,
        val battery: DrivePower.Reading? = null,
        val batteryLow: Boolean = false
    ) {
        val active: Boolean get() = reason != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether low power is on now; read by the loops on every cycle. */
    val active: Boolean get() = _state.value.active

    /** The pace the loops use now; see [LowPower.pacing]. */
    val pacing: LowPower.Pacing get() = LowPower.pacing(active)

    @Volatile
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(context, intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> update(powerSave = powerSave(context))
            }
        }
    }

    /** Registers for the battery and battery-saver broadcasts; once per process. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val switchOn = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SWITCH, false)
        _state.update { it.copy(switchOn = switchOn) }
        update(powerSave = powerSave(app))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // Both are protected broadcasts only Android sends; the battery one is sticky,
        // so the current reading arrives at once.
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** The owner turned "Low power" on or off. */
    fun setSwitch(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SWITCH, on).apply()
        _state.update { recompute(it.copy(switchOn = on)) }
    }

    /** The battery now, read on demand for the settings page: level, charge counter and charging. */
    fun sample(context: Context, now: Long): BatterySample? {
        val sticky = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        return sampleOf(context, sticky, now)
    }

    private fun onBattery(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val sample = sampleOf(context, intent, now)
        ScanPower.onBattery(sample)
        _state.update { s ->
            val reading = DrivePower.Reading(sample.levelPct, sample.charging)
            recompute(s.copy(battery = reading, batteryLow = LowPower.batteryLow(reading.levelPct, reading.charging, s.batteryLow)))
        }
    }

    private fun update(powerSave: Boolean) {
        _state.update { recompute(it.copy(powerSave = powerSave)) }
    }

    private fun recompute(s: State): State = s.copy(reason = LowPower.reason(s.switchOn, s.powerSave, s.batteryLow))

    private fun powerSave(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull() == true

    private fun sampleOf(context: Context, intent: Intent, now: Long): BatterySample {
        val reading = DrivePower.reading(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        )
        val counter = runCatching {
            context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull()
        return BatterySample(now, reading.levelPct, counter?.let { BatteryUse.chargeUah(it) }, reading.charging)
    }
}
