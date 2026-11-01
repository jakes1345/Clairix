package app.clairix.analysis

import android.bluetooth.le.ScanSettings
import app.clairix.ScanStatus

/**
 * How hard the scanner and the Scan tabs work, as decisions apart from the service
 * and the screens that act on them.
 *
 * A phone spends most of a day with its screen off, and much of that going nowhere:
 * on a desk, a bedside table, in a bag on the floor. Following is judged from places
 * a device is heard at, and a phone that is not moving makes no new places;
 * persistence is satisfied by sightings minutes apart. So while the screen is off,
 * the phone has settled ([MotionPolicy]) and no drive is under way ([idle]), the
 * scanner backs off: the Bluetooth controller scans at low power and hands results
 * over once a minute, and the device list is rebuilt only when something was heard or
 * aged out. Bluetooth alerts stay packet-driven: they are raised as a packet is
 * handled, never on these cadences. The cell and Wi-Fi checks are polls, so their
 * cadences here bound how soon those alerts can come, and they run on coroutine
 * timers that do not wake a sleeping phone: at rest with the screen off they run as
 * often as stated only while something keeps the CPU awake (a Bluetooth batch, a
 * position fix, Comms). The screen coming on, the phone moving or a drive starting
 * brings the usual pace back at once.
 */
object ScanPacing {

    /** Nobody is looking, the phone has settled and no drive is under way. */
    fun idle(screenOn: Boolean, still: Boolean, inTrip: Boolean): Boolean = !screenOn && still && !inTrip

    // ── Bluetooth ───────────────────────────────────────────────────────────

    /** The scan mode and batch delay to register; a [reportDelayMs] of 0 hands over each result as it is heard. */
    data class BleScan(val mode: Int, val reportDelayMs: Long)

    /** How long the controller may hold results while the screen is off and the phone is moving. */
    const val SCREEN_OFF_BATCH_MS = 5_000L

    /** How long it may hold them once the phone has settled with the screen off: one hand-over a minute. */
    const val IDLE_BATCH_MS = 60_000L

    /**
     * The scan [ScanService][app.clairix.ScanService] registers. The first minutes of
     * a drive ([intensive]) scan at low latency. With the screen off and no drive the
     * controller may batch results when it can ([batchingSupported]), so the CPU
     * sleeps between hand-overs; never during a drive, where a few seconds' delay puts
     * a roadside camera hundreds of metres from where it was heard. Settled with the
     * screen off ([idle]), the balanced default drops to low power with a minute's
     * batch. An owner who switched on Aggressive scan asked for faster detection at
     * the cost of battery, and keeps it, until low power ([lowPower], see [LowPower])
     * comes on: then the scan is low power with a minute's batch whatever the screen
     * or the pace, Aggressive scan or not. The first minutes of a drive still scan at
     * low latency: a camera passed at speed is in range for seconds.
     */
    fun bleScan(
        configuredMode: Int,
        intensive: Boolean,
        screenOn: Boolean,
        still: Boolean,
        inTrip: Boolean,
        batchingSupported: Boolean,
        lowPower: Boolean = false
    ): BleScan {
        val batch = !screenOn && !inTrip && batchingSupported
        val backOff = !intensive && idle(screenOn, still, inTrip) && configuredMode == ScanSettings.SCAN_MODE_BALANCED
        return when {
            intensive -> BleScan(ScanSettings.SCAN_MODE_LOW_LATENCY, if (batch) SCREEN_OFF_BATCH_MS else 0L)
            lowPower -> BleScan(LowPower.LOW.bleMode ?: ScanSettings.SCAN_MODE_LOW_POWER, if (batch) LowPower.BLE_BATCH_MS else 0L)
            backOff -> BleScan(ScanSettings.SCAN_MODE_LOW_POWER, if (batch) IDLE_BATCH_MS else 0L)
            else -> BleScan(configuredMode, if (batch) SCREEN_OFF_BATCH_MS else 0L)
        }
    }

    // ── The publish loop ────────────────────────────────────────────────────

    /** The list and the map refresh this often while someone may be looking at them. */
    const val SCREEN_ON_PUBLISH_MS = 1_500L

    /** With the screen off, while the phone moves or a drive is under way. */
    const val SCREEN_OFF_PUBLISH_MS = 10_000L

    /**
     * Settled with the screen off: the loop wakes once a minute at most, for the
     * health check, the heartbeat and the settle clock, and publishes only when
     * [publishDue] says so.
     */
    const val IDLE_PUBLISH_MS = 60_000L

    /** How long the publish loop waits for a kick (the screen coming on, Location toggled) before its next cycle. */
    fun publishWaitMs(screenOn: Boolean, idle: Boolean): Long = when {
        screenOn -> SCREEN_ON_PUBLISH_MS
        idle -> IDLE_PUBLISH_MS
        else -> SCREEN_OFF_PUBLISH_MS
    }

    /**
     * Whether a cycle rebuilds and republishes the device list and the map. Every
     * cycle does, except while [idle]: then only when a packet arrived since the last
     * publish ([heardAt], the time of the latest packet, differs from
     * [publishedHeardAt], the [publishedMark] of that publish), when that mark is null
     * (before the first publish, or when the last one may have missed a packet), or
     * when a device aged out of the list ([pruned]). Between packets the list changes
     * only as devices age out, and a tracker's answer over DULT follows a packet by
     * seconds and shows at the next one. The screen coming on ends the idle spell, so
     * what the owner opens is always fresh.
     */
    fun publishDue(idle: Boolean, heardAt: Long, publishedHeardAt: Long?, pruned: Int): Boolean =
        !idle || publishedHeardAt == null || heardAt != publishedHeardAt || pruned > 0

    /**
     * A packet's time is noted as it arrives and its handling finishes a moment later,
     * so a list taken within [HANDLE_MARGIN_MS] of the latest packet may not hold it yet.
     */
    const val HANDLE_MARGIN_MS = 2_000L

    /**
     * What to remember as published after a publish at [publishedAt] that read
     * [heardAt]: [heardAt] itself once that packet is certainly in the list, or null
     * when it arrived too shortly before, so the next cycle publishes again.
     */
    fun publishedMark(heardAt: Long, publishedAt: Long): Long? =
        if (publishedAt - heardAt >= HANDLE_MARGIN_MS) heardAt else null

    // ── Cell ────────────────────────────────────────────────────────────────

    /** In use or on the move, cells change quickly. */
    const val CELL_ACTIVE_MS = 15_000L

    /**
     * Screen off, moving or not. Not slower once the phone has settled: that is when
     * the brief-cell rules run ([IMSICatcher] judges them only on a still phone), and
     * they measure a stint in poll intervals. At thirty seconds a catcher that holds the
     * phone for 30 to 90 s always reads as 30 to 90 s; at a minute an 80 s stint read
     * as 120 s one time in three and a 40 s one fell between readings one time in
     * three. Asking the modem is not what drains a resting phone.
     */
    const val CELL_SCREEN_OFF_MS = 30_000L

    /**
     * How long the cell loop waits before asking the modem again, unless the screen
     * coming on or the phone moving cuts it short. In low power ([lowPower]) never
     * less than [LowPower.CELL_POLL_MS].
     */
    fun cellPollMs(screenOn: Boolean, moving: Boolean, lowPower: Boolean = false): Long =
        maxOf(if (screenOn || moving) CELL_ACTIVE_MS else CELL_SCREEN_OFF_MS, LowPower.pacing(lowPower).cellPollFloorMs)

    // ── Wi-Fi ───────────────────────────────────────────────────────────────

    /** The scanner's Wi-Fi look (a scan request, then the bait rules and the canary) while the screen is on or the phone moves. */
    const val WIFI_ACTIVE_MS = 2 * 60_000L

    /** Settled with the screen off, where the networks around do not change. */
    const val WIFI_SETTLED_MS = 10 * 60_000L

    /**
     * How often the scanner asks Android for a Wi-Fi scan and judges what is in range;
     * in low power ([lowPower]) never more often than every [LowPower.WIFI_SCAN_MS].
     */
    fun wifiScanEveryMs(screenOn: Boolean, moving: Boolean, lowPower: Boolean = false): Long =
        maxOf(if (screenOn || moving) WIFI_ACTIVE_MS else WIFI_SETTLED_MS, LowPower.pacing(lowPower).wifiScanFloorMs)

    // ── Scan tabs ───────────────────────────────────────────────────────────

    /**
     * A tab under Scan that takes its own reading while it is on screen and the
     * scanner is off, and how often. Android allows a foreground app four Wi-Fi scan
     * requests in two minutes and refuses the rest; the Wi-Fi tab's one a minute stays
     * well inside that.
     */
    enum class Tab(val ownMs: Long) {
        CELL(15_000L),
        DEVICE(30_000L),
        WIFI(60_000L)
    }

    /**
     * Whether the scanner service is publishing what the Scan tabs show: the judged
     * cell readings, the phone-health check, the Wi-Fi it scans with the Karma canary's
     * answers. That is so whenever the service runs, Bluetooth scan or not
     * ([ScanStatus.serviceRunning], not [ScanStatus.scanning]); a tab reading for itself
     * then would overwrite the judged results with unjudged ones.
     */
    fun scannerPublishes(status: ScanStatus): Boolean = status.serviceRunning

    /**
     * How often [tab] refreshes itself while it is on screen, or null when it leaves
     * that to the scanner ([scannerPublishes]): while the scanner runs it publishes what
     * each tab shows (the judged cell readings, the phone-health check every 30 s with
     * the screen on, the Wi-Fi it scans every two minutes), and a tab asking again only
     * repeats the work.
     */
    fun tabRefreshMs(tab: Tab, status: ScanStatus): Long? = if (scannerPublishes(status)) null else tab.ownMs

    // ── Housekeeping ────────────────────────────────────────────────────────

    /** History older than the owner's retention is deleted this often while the scanner runs. */
    const val RETENTION_EVERY_MS = 60 * 60_000L

    /** Whether the retention pass is due: at once, then every [RETENTION_EVERY_MS], and again if the clock went back. */
    fun retentionDue(lastAt: Long, now: Long): Boolean =
        lastAt == 0L || now - lastAt >= RETENTION_EVERY_MS || now < lastAt

    /**
     * Whether a log kept oldest first holds anything older than [cutoff], judged from
     * its oldest row alone ([oldest], null when it is empty): when even that one is
     * inside the retention there is nothing to delete, and the walk over every row
     * that finds so is skipped.
     */
    fun holdsOlderThan(oldest: Long?, cutoff: Long): Boolean = oldest != null && oldest < cutoff
}
