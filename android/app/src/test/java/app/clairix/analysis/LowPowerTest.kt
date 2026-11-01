package app.clairix.analysis

import android.bluetooth.le.ScanSettings
import app.clairix.analysis.ScanPacing.BleScan
import app.clairix.comms.mesh.MeshPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Low power: when it comes on, what it slows, and what it leaves alone. */
class LowPowerTest {

    private val min = 60_000L

    // ── When it is on ──

    @Test
    fun theBatteryTurnsItOnBelowTwentyPercentOnlyWhenNotCharging() {
        assertTrue(LowPower.batteryLow(levelPct = 19, charging = false, wasLow = false))
        assertFalse(LowPower.batteryLow(levelPct = 20, charging = false, wasLow = false))
        assertFalse(LowPower.batteryLow(levelPct = 5, charging = true, wasLow = false))
        assertFalse(LowPower.batteryLow(levelPct = 5, charging = true, wasLow = true))
    }

    @Test
    fun onceOnItStaysOnUntilTwentyFivePercentSoAWobbleDoesNotFlap() {
        assertTrue(LowPower.batteryLow(levelPct = 20, charging = false, wasLow = true))
        assertTrue(LowPower.batteryLow(levelPct = 24, charging = false, wasLow = true))
        assertFalse(LowPower.batteryLow(levelPct = 25, charging = false, wasLow = true))
    }

    @Test
    fun anUnknownLevelNeverTurnsItOn() {
        assertFalse(LowPower.batteryLow(levelPct = null, charging = false, wasLow = false))
        assertFalse(LowPower.batteryLow(levelPct = null, charging = false, wasLow = true))
    }

    @Test
    fun theReasonPutsTheOwnersSwitchFirstThenBatterySaverThenTheBattery() {
        assertEquals(LowPower.Reason.SWITCH, LowPower.reason(switchOn = true, powerSave = true, batteryLow = true))
        assertEquals(LowPower.Reason.POWER_SAVE, LowPower.reason(switchOn = false, powerSave = true, batteryLow = true))
        assertEquals(LowPower.Reason.BATTERY, LowPower.reason(switchOn = false, powerSave = false, batteryLow = true))
        assertNull(LowPower.reason(switchOn = false, powerSave = false, batteryLow = false))
    }

    // ── What it changes ──

    @Test
    fun lowPowerHalvesTheSettleThresholdsAndSetsTheSlowCadences() {
        val low = LowPower.pacing(switchOn = false, powerSave = true, batteryLow = false)
        assertEquals(LowPower.LOW, low)
        assertEquals(MotionPolicy.STILL_AFTER_MS / 2, low.stillAfterMs)
        assertEquals(MeshPacing.STILL_AFTER_MS / 2, low.meshStillAfterMs)
        assertEquals(2 * min, low.cellPollFloorMs)
        assertEquals(15 * min, low.wifiScanFloorMs)
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, low.bleMode)
        assertEquals(60_000L, low.bleBatchMs)
        assertFalse(low.coverTraffic)
        assertTrue(low.meshIdle)
    }

    @Test
    fun offLeavesTheUsualPace() {
        val normal = LowPower.pacing(switchOn = false, powerSave = false, batteryLow = false)
        assertEquals(LowPower.NORMAL, normal)
        assertEquals(MotionPolicy.STILL_AFTER_MS, normal.stillAfterMs)
        assertEquals(MeshPacing.STILL_AFTER_MS, normal.meshStillAfterMs)
        assertNull(normal.bleMode)
        assertTrue(normal.coverTraffic)
        assertFalse(normal.meshIdle)
    }

    @Test
    fun theBluetoothScanDropsToLowPowerWithAMinutesBatchEvenWithAggressiveScan() {
        fun ble(configured: Int, screenOn: Boolean, intensive: Boolean = false, inTrip: Boolean = false) =
            ScanPacing.bleScan(configured, intensive, screenOn, still = false, inTrip = inTrip, batchingSupported = true, lowPower = true)
        assertEquals(BleScan(ScanSettings.SCAN_MODE_LOW_POWER, 60_000L), ble(ScanSettings.SCAN_MODE_LOW_LATENCY, screenOn = false))
        assertEquals(BleScan(ScanSettings.SCAN_MODE_LOW_POWER, 60_000L), ble(ScanSettings.SCAN_MODE_BALANCED, screenOn = false))
        // With the screen on the list stays live: low power, no batch.
        assertEquals(BleScan(ScanSettings.SCAN_MODE_LOW_POWER, 0L), ble(ScanSettings.SCAN_MODE_BALANCED, screenOn = true))
        // A drive is never batched; its first minutes still scan at low latency.
        assertEquals(BleScan(ScanSettings.SCAN_MODE_LOW_POWER, 0L), ble(ScanSettings.SCAN_MODE_BALANCED, screenOn = false, inTrip = true))
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, ble(ScanSettings.SCAN_MODE_BALANCED, screenOn = false, intensive = true).mode)
    }

    @Test
    fun theCellIsReadEveryTwoMinutesAndTheWifiEveryFifteenAtMost() {
        assertEquals(2 * min, ScanPacing.cellPollMs(screenOn = true, moving = true, lowPower = true))
        assertEquals(2 * min, ScanPacing.cellPollMs(screenOn = false, moving = false, lowPower = true))
        assertEquals(15_000L, ScanPacing.cellPollMs(screenOn = true, moving = true, lowPower = false))
        assertEquals(15 * min, ScanPacing.wifiScanEveryMs(screenOn = true, moving = true, lowPower = true))
        assertEquals(15 * min, ScanPacing.wifiScanEveryMs(screenOn = false, moving = false, lowPower = true))
        assertEquals(2 * min, ScanPacing.wifiScanEveryMs(screenOn = true, moving = true, lowPower = false))
    }

    @Test
    fun theScannerSettlesAfterHalfTheQuietSpellOnceTheThresholdIsHalved() {
        val p = MotionPolicy()
        p.start(0L)
        assertNull(p.tick(2 * min))
        p.stillAfterMs = LowPower.LOW.stillAfterMs
        assertEquals(MotionPolicy.Pace.STILL, p.tick(2 * min))
    }

    @Test
    fun forcingStillSettlesAtOnceAndMovementStillEndsIt() {
        val p = MotionPolicy()
        p.start(0L)
        assertEquals(MotionPolicy.Pace.STILL, p.forceStill())
        assertNull(p.forceStill())
        assertEquals(MotionPolicy.Pace.MOVING, p.onMotion(1_000L))
    }

    // ── What it leaves alone ──

    @Test
    fun theMeshStillRunsAtFullPaceWithSomethingToCarryLikeAnSos() {
        val now = 10 * min
        assertTrue(MeshPacing.busy(carrying = 1, conversationOpen = false, lastMeetingAt = 0L, now = now, lowPower = true))
        // An open conversation or a recent meeting no longer hurries it.
        assertFalse(MeshPacing.busy(carrying = 0, conversationOpen = true, lastMeetingAt = 0L, now = now, lowPower = true))
        assertFalse(MeshPacing.busy(carrying = 0, conversationOpen = false, lastMeetingAt = now - min, now = now, lowPower = true))
        assertTrue(MeshPacing.busy(carrying = 0, conversationOpen = true, lastMeetingAt = 0L, now = now, lowPower = false))
    }
}
