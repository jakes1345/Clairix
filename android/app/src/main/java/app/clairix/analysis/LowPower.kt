package app.clairix.analysis

import android.bluetooth.le.ScanSettings
import app.clairix.comms.mesh.MeshPacing

/**
 * Low power: what Clairix gives up on a day the phone has to last, as decisions apart
 * from the receivers and the loops that act on them.
 *
 * On when the owner turns on "Low power" in Settings > Battery, when Android's own
 * battery saver is on, and below [AUTO_BELOW_PCT] with the battery not charging. Once
 * the battery has put it on, it stays on until the phone charges or climbs back to
 * [AUTO_RESUME_PCT], so a level that wobbles around the line does not switch it on and
 * off over and over.
 *
 * What it changes ([pacing]): the phone counts as settled after half the usual quiet
 * spell, the Bluetooth scan runs at low power with results handed over once a minute
 * while the screen is off, the serving cell is read every two minutes and the Wi-Fi
 * around is looked at every fifteen, cover traffic stops, and the offline mesh listens
 * and advertises at low power unless it has something to carry.
 *
 * What it never touches: an SOS (the relay, the mesh with an SOS to carry, the SMS and
 * call fallbacks), the duress passcode and phrase, incoming calls and the relay
 * connection that rings them, and alerts, which are raised as a packet or a reading is
 * handled, never on these cadences. Nothing here is read on those paths.
 */
object LowPower {

    /** Below this, with the battery not charging, low power comes on by itself. */
    const val AUTO_BELOW_PCT = 20

    /** Once on because of the battery, it stays on until the phone charges or reaches this. */
    const val AUTO_RESUME_PCT = 25

    /** Why low power is on. */
    enum class Reason {
        /** The owner turned on "Low power". */
        SWITCH,

        /** Android's battery saver is on. */
        POWER_SAVE,

        /** The battery is below [AUTO_BELOW_PCT] and not charging. */
        BATTERY
    }

    /**
     * Whether the battery alone keeps low power on, given whether it did last time
     * ([wasLow]). A reading without a level never counts: nothing is slowed on a guess.
     */
    fun batteryLow(levelPct: Int?, charging: Boolean, wasLow: Boolean): Boolean {
        val pct = levelPct ?: return false
        if (charging) return false
        return if (wasLow) pct < AUTO_RESUME_PCT else pct < AUTO_BELOW_PCT
    }

    /**
     * Why low power is on, or null when it is off: the owner's [switchOn] first, then
     * Android's battery saver ([powerSave]), then a low battery ([batteryLow]).
     */
    fun reason(switchOn: Boolean, powerSave: Boolean, batteryLow: Boolean): Reason? = when {
        switchOn -> Reason.SWITCH
        powerSave -> Reason.POWER_SAVE
        batteryLow -> Reason.BATTERY
        else -> null
    }

    // ── The pace ────────────────────────────────────────────────────────────

    /** The serving cell is read at most this often in low power. */
    const val CELL_POLL_MS = 2 * 60_000L

    /** The Wi-Fi around is looked at at most this often in low power. */
    const val WIFI_SCAN_MS = 15 * 60_000L

    /** How long the Bluetooth controller may hold results in low power while the screen is off. */
    const val BLE_BATCH_MS = 60_000L

    /**
     * How the scanner and comms run: [stillAfterMs] and [meshStillAfterMs] before the
     * scanner and the mesh count the phone as settled; [cellPollFloorMs] and
     * [wifiScanFloorMs] the shortest pause between cell readings and Wi-Fi looks (0
     * leaves the usual pace alone); [bleMode] the Bluetooth scan mode to use instead of
     * the configured one (null keeps it) and [bleBatchMs] the batch it may hand over with
     * the screen off; [coverTraffic] whether cover traffic may run; [meshIdle] whether the
     * mesh stays at its low-power pace unless it has something to carry.
     */
    data class Pacing(
        val stillAfterMs: Long,
        val meshStillAfterMs: Long,
        val cellPollFloorMs: Long,
        val wifiScanFloorMs: Long,
        val bleMode: Int?,
        val bleBatchMs: Long,
        val coverTraffic: Boolean,
        val meshIdle: Boolean
    )

    /** The usual pace. */
    val NORMAL = Pacing(
        stillAfterMs = MotionPolicy.STILL_AFTER_MS,
        meshStillAfterMs = MeshPacing.STILL_AFTER_MS,
        cellPollFloorMs = 0L,
        wifiScanFloorMs = 0L,
        bleMode = null,
        bleBatchMs = 0L,
        coverTraffic = true,
        meshIdle = false
    )

    /** Low power: settled in half the time, the radios at their slowest useful pace. */
    val LOW = Pacing(
        stillAfterMs = MotionPolicy.STILL_AFTER_MS / 2,
        meshStillAfterMs = MeshPacing.STILL_AFTER_MS / 2,
        cellPollFloorMs = CELL_POLL_MS,
        wifiScanFloorMs = WIFI_SCAN_MS,
        bleMode = ScanSettings.SCAN_MODE_LOW_POWER,
        bleBatchMs = BLE_BATCH_MS,
        coverTraffic = false,
        meshIdle = true
    )

    /** The pace for low power [on] or off. */
    fun pacing(on: Boolean): Pacing = if (on) LOW else NORMAL

    /** The pace from the inputs themselves; see [reason]. */
    fun pacing(switchOn: Boolean, powerSave: Boolean, batteryLow: Boolean): Pacing =
        pacing(reason(switchOn, powerSave, batteryLow) != null)
}
