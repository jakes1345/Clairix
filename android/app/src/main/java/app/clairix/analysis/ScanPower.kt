package app.clairix.analysis

import android.bluetooth.le.ScanSettings

/**
 * One reading of the battery: when it was taken ([at]), the level in percent (null when
 * Android did not say), the charge counter in microampere-hours where the phone reports
 * one (BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER; null where it does not), and
 * whether the battery was charging or full.
 */
data class BatterySample(val at: Long, val levelPct: Int?, val chargeUah: Long?, val charging: Boolean)

/**
 * "Used since the scan started" in Settings > Battery, as arithmetic apart from the
 * battery service.
 *
 * Android tells an app how full the battery is, and on most phones how much charge is
 * in it, but not what any one app has drawn: that share is on Android's own battery
 * page. So the readout is the whole phone's drain since the scanner started, said as
 * such. A charge in between makes the start meaningless; the count then starts again
 * from the moment the phone is unplugged.
 */
object BatteryUse {

    /** A rate per hour needs at least this long behind it; a few minutes' drain says little. */
    const val MIN_RATE_SPAN_MS = 30 * 60_000L

    /**
     * The charge counter as BatteryManager returns it: Integer.MIN_VALUE where the phone
     * does not report one, and 0 or less on phones that report it wrongly; both read as
     * absent.
     */
    fun chargeUah(raw: Int): Long? = if (raw <= 0) null else raw.toLong()

    /**
     * The reading to count from after [sample], given the one counted from so far
     * ([baseline], null when there is none): none while the phone charges, the first
     * reading after it stops, otherwise the same as before.
     */
    fun rebase(baseline: BatterySample?, sample: BatterySample): BatterySample? = when {
        sample.charging -> null
        baseline == null -> sample
        else -> baseline
    }

    /**
     * What the phone used between [baseline] and [now]: the time between them, the
     * level points lost (never below 0; null when either level is unknown), the charge
     * in milliampere-hours (null without a counter at both ends, or when the counter
     * went up, as it does on some phones after a recalibration), and the points per
     * hour once [MIN_RATE_SPAN_MS] has passed.
     */
    data class Use(val sinceAt: Long, val elapsedMs: Long, val pct: Int?, val mAh: Long?, val pctPerHour: Double?)

    /** The [Use] from [baseline] to [now], or null when there is nothing to count from or [now] is before it. */
    fun use(baseline: BatterySample?, now: BatterySample): Use? {
        if (baseline == null || now.charging) return null
        val elapsed = now.at - baseline.at
        if (elapsed < 0) return null
        val pct = if (baseline.levelPct != null && now.levelPct != null) (baseline.levelPct - now.levelPct).coerceAtLeast(0) else null
        val mAh = if (baseline.chargeUah != null && now.chargeUah != null && now.chargeUah <= baseline.chargeUah) {
            (baseline.chargeUah - now.chargeUah) / 1_000L
        } else null
        val rate = if (pct != null && elapsed >= MIN_RATE_SPAN_MS) pct * 3_600_000.0 / elapsed else null
        return Use(baseline.at, elapsed, pct, mAh, rate)
    }
}

/**
 * What the scanner itself has done since it started, from its own counters: how long
 * the Bluetooth scan ran in each mode and how much of that the controller could batch,
 * how long the phone was moving (precise position fixes every fifteen seconds) or
 * settled (a coarse fix every few minutes), how many times the modem was asked for the
 * serving cell and how many Wi-Fi scans were asked of Android. These are the app's
 * duty cycles, not a measured current: Android gives an app no figure for its own draw.
 *
 * Every method takes the time, so the arithmetic can be tested; thread-safe.
 */
class ScanEffort(val startedAt: Long) {

    /** The counts at one moment; see [ScanEffort]. Times in milliseconds. */
    data class Snapshot(
        val elapsedMs: Long,
        val bleLowPowerMs: Long,
        val bleBalancedMs: Long,
        val bleLowLatencyMs: Long,
        val bleBatchedMs: Long,
        val movingMs: Long,
        val settledMs: Long,
        val cellReadings: Int,
        val wifiScans: Int
    ) {
        val bleMs: Long get() = bleLowPowerMs + bleBalancedMs + bleLowLatencyMs
    }

    private var bleMode: Int? = null
    private var bleBatched = false
    private var bleSince = 0L
    private var bleLowPower = 0L
    private var bleBalanced = 0L
    private var bleLowLatency = 0L
    private var bleBatchedTotal = 0L

    private var moving: Boolean? = null
    private var paceSince = 0L
    private var movingTotal = 0L
    private var settledTotal = 0L

    private var cells = 0
    private var wifis = 0

    /** The Bluetooth scan now runs in [mode] (null: stopped), batched or not. */
    @Synchronized
    fun ble(mode: Int?, batched: Boolean, now: Long) {
        closeBle(now)
        bleMode = mode
        bleBatched = batched
        bleSince = now
    }

    /** The phone is now [moving] or settled; null when positions stopped (no permission, the scan ended). */
    @Synchronized
    fun pace(moving: Boolean?, now: Long) {
        closePace(now)
        this.moving = moving
        paceSince = now
    }

    @Synchronized
    fun cellReading() { cells++ }

    @Synchronized
    fun wifiScan() { wifis++ }

    /** The counts up to [now], the running spells included. */
    @Synchronized
    fun snapshot(now: Long): Snapshot {
        closeBle(now)
        bleSince = now
        closePace(now)
        paceSince = now
        return Snapshot(
            elapsedMs = (now - startedAt).coerceAtLeast(0L),
            bleLowPowerMs = bleLowPower, bleBalancedMs = bleBalanced, bleLowLatencyMs = bleLowLatency,
            bleBatchedMs = bleBatchedTotal,
            movingMs = movingTotal, settledMs = settledTotal,
            cellReadings = cells, wifiScans = wifis
        )
    }

    private fun closeBle(now: Long) {
        val span = (now - bleSince).coerceAtLeast(0L)
        when (bleMode) {
            null -> return
            ScanSettings.SCAN_MODE_LOW_POWER -> bleLowPower += span
            ScanSettings.SCAN_MODE_LOW_LATENCY -> bleLowLatency += span
            else -> bleBalanced += span
        }
        if (bleBatched) bleBatchedTotal += span
    }

    private fun closePace(now: Long) {
        val span = (now - paceSince).coerceAtLeast(0L)
        when (moving) {
            true -> movingTotal += span
            false -> settledTotal += span
            null -> Unit
        }
    }
}

/**
 * The scanner's battery record for Settings > Battery: the battery reading counted from
 * ([BatteryUse]) and the scanner's own counters ([ScanEffort]), from the moment the scan
 * started until it stops. [app.clairix.ScanService] starts and stops it and feeds it;
 * [LowPowerMode] passes on every battery reading, so a charge in between starts the
 * count again from the unplug.
 */
object ScanPower {

    @Volatile
    var effort: ScanEffort? = null
        private set

    @Volatile
    private var baseline: BatterySample? = null

    /** The scan started at [now] with the battery at [battery] (null when Android would not say). */
    fun start(now: Long, battery: BatterySample?) {
        effort = ScanEffort(now)
        baseline = battery?.let { BatteryUse.rebase(null, it) }
    }

    /** The scan stopped: nothing is counted until it starts again. */
    fun stop() {
        effort = null
        baseline = null
    }

    /** A battery reading while the scan runs. */
    fun onBattery(sample: BatterySample) {
        if (effort == null) return
        baseline = BatteryUse.rebase(baseline, sample)
    }

    /** What the phone used since the scan started (or since it was last unplugged), or null; see [BatteryUse.use]. */
    fun use(now: BatterySample): BatteryUse.Use? = if (effort == null) null else BatteryUse.use(baseline, now)
}
