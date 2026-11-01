package app.clairix.analysis

import android.bluetooth.le.ScanSettings
import app.clairix.analysis.ScanPacing.BleScan
import app.clairix.analysis.ScanPacing.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How hard the scanner and the Scan tabs work: settled with the screen off, moving, in use, on a drive. */
class ScanPacingTest {

    private val balanced = ScanSettings.SCAN_MODE_BALANCED
    private val lowLatency = ScanSettings.SCAN_MODE_LOW_LATENCY
    private val lowPower = ScanSettings.SCAN_MODE_LOW_POWER
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun ble(
        configured: Int = balanced,
        intensive: Boolean = false,
        screenOn: Boolean = false,
        still: Boolean = false,
        inTrip: Boolean = false,
        batching: Boolean = true
    ) = ScanPacing.bleScan(configured, intensive, screenOn, still, inTrip, batching)

    @Test
    fun idleIsScreenOffSettledAndNoDrive() {
        assertTrue(ScanPacing.idle(screenOn = false, still = true, inTrip = false))
        assertFalse(ScanPacing.idle(screenOn = true, still = true, inTrip = false))
        assertFalse(ScanPacing.idle(screenOn = false, still = false, inTrip = false))
        assertFalse(ScanPacing.idle(screenOn = false, still = true, inTrip = true))
    }

    // ── Bluetooth ──

    @Test
    fun aSettledPhoneWithItsScreenOffScansAtLowPowerInMinuteBatches() {
        assertEquals(BleScan(lowPower, ScanPacing.IDLE_BATCH_MS), ble(still = true))
        assertTrue(ScanPacing.IDLE_BATCH_MS in 30_000L..60_000L)
    }

    @Test
    fun movingTheScreenOrADriveBringsTheUsualScanBack() {
        // Moving, screen off: the configured mode, in short batches.
        assertEquals(BleScan(balanced, ScanPacing.SCREEN_OFF_BATCH_MS), ble(still = false))
        // Screen on, settled or not: unbatched, every advert as it is heard.
        assertEquals(BleScan(balanced, 0L), ble(screenOn = true, still = true))
        assertEquals(BleScan(balanced, 0L), ble(screenOn = true, still = false))
        // A drive under way, even parked at a light long enough to read as settled: never batched.
        assertEquals(BleScan(balanced, 0L), ble(still = true, inTrip = true))
    }

    @Test
    fun theFirstMinutesOfADriveScanAtLowLatency() {
        assertEquals(BleScan(lowLatency, 0L), ble(intensive = true, still = true, inTrip = true))
        assertEquals(BleScan(lowLatency, 0L), ble(intensive = true, screenOn = true, inTrip = true))
    }

    @Test
    fun aggressiveScanIsNotTurnedDownWhenSettled() {
        // The owner chose faster detection at the cost of battery: settled or not, it stays.
        assertEquals(BleScan(lowLatency, ScanPacing.SCREEN_OFF_BATCH_MS), ble(configured = lowLatency, still = true))
        assertEquals(BleScan(lowLatency, 0L), ble(configured = lowLatency, screenOn = true))
    }

    @Test
    fun aControllerWithoutBatchingIsNeverAskedToHoldResults() {
        // A report delay on such a controller fails the scan outright.
        assertEquals(BleScan(lowPower, 0L), ble(still = true, batching = false))
        assertEquals(BleScan(balanced, 0L), ble(still = false, batching = false))
    }

    // ── The publish loop ──

    @Test
    fun theLoopWakesOnceAMinuteWhenIdle() {
        assertEquals(1_500L, ScanPacing.publishWaitMs(screenOn = true, idle = false))
        assertEquals(10_000L, ScanPacing.publishWaitMs(screenOn = false, idle = false))
        assertEquals(60_000L, ScanPacing.publishWaitMs(screenOn = false, idle = true))
    }

    @Test
    fun outsideIdleEveryCyclePublishes() {
        assertTrue(ScanPacing.publishDue(idle = false, heardAt = t0, publishedHeardAt = t0, pruned = 0))
    }

    @Test
    fun idleCyclesPublishOnlyWhenSomethingWasHeardOrAgedOut() {
        // The first cycle publishes whatever there is.
        assertTrue(ScanPacing.publishDue(idle = true, heardAt = 0L, publishedHeardAt = null, pruned = 0))
        // Nothing heard since the last publish and nothing aged out: the list stands.
        assertFalse(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = t0, pruned = 0))
        // A packet since.
        assertTrue(ScanPacing.publishDue(idle = true, heardAt = t0 + 30_000L, publishedHeardAt = t0, pruned = 0))
        // A device aged out.
        assertTrue(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = t0, pruned = 1))
    }

    @Test
    fun aListTakenRightAfterAPacketIsTakenAgainNextCycle() {
        // The packet's time is noted before its handling finishes, so a list taken a
        // moment later may not hold it: the mark stays open and the next cycle publishes.
        assertNull(ScanPacing.publishedMark(heardAt = t0, publishedAt = t0 + 500L))
        assertTrue(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = null, pruned = 0))
        // Taken well after it, the packet is in the list and the mark closes on it.
        val mark = ScanPacing.publishedMark(heardAt = t0, publishedAt = t0 + 5_000L)
        assertEquals(t0, mark)
        assertFalse(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = mark, pruned = 0))
        // Nothing ever heard: the mark is closed at once.
        assertEquals(0L, ScanPacing.publishedMark(heardAt = 0L, publishedAt = t0))
    }

    @Test
    fun aDeviceAgingOutOfTheTrackerTriggersAnIdlePublish() {
        val tracker = Tracker()
        tracker.observe(
            key = "tag", address = "tag", rssi = -60, tracker = null, approxMetres = null,
            rotations = 0, addresses = 1, fix = null, hasPosition = false, now = t0, travelledM = 0.0
        )
        // Within the half hour nothing ages out and an idle cycle with nothing heard publishes nothing.
        val early = tracker.prune(t0 + 10 * min)
        assertEquals(0, early)
        assertFalse(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = t0, pruned = early))
        // Past it the device is dropped, and the list must be published without it.
        val late = tracker.prune(t0 + 31 * min)
        assertEquals(1, late)
        assertTrue(ScanPacing.publishDue(idle = true, heardAt = t0, publishedHeardAt = t0, pruned = late))
        assertEquals(0, tracker.prune(t0 + 32 * min))
    }

    // ── Cell ──

    @Test
    fun theCellIsAskedOnceAMinuteOnlyWhenTheRestIsConfirmed() {
        assertEquals(15_000L, ScanPacing.cellPollMs(screenOn = true, moving = false, confirmedStill = true))
        assertEquals(15_000L, ScanPacing.cellPollMs(screenOn = false, moving = true, confirmedStill = false))
        assertEquals(60_000L, ScanPacing.cellPollMs(screenOn = false, moving = false, confirmedStill = true))
        // Settled with no recent fix to show it (a train underground): no slower than before.
        assertEquals(30_000L, ScanPacing.cellPollMs(screenOn = false, moving = false, confirmedStill = false))
    }

    @Test
    fun aSettledCellPollStillCatchesAStintUnderNinetySeconds() {
        // The strict ephemeral-cell rule looks for stints of 30 to 90 s; one reading
        // apart, a stint measures one poll interval.
        assertTrue(ScanPacing.CELL_SETTLED_MS in 30_000L..90_000L)
        // And the five-minute checks still get several readings.
        assertTrue(5 * min / ScanPacing.CELL_SETTLED_MS >= 4)
    }

    // ── Wi-Fi ──

    @Test
    fun theScannerLooksAtWiFiEveryTwoMinutesInUseOrMovingAndEveryTenSettled() {
        assertEquals(2 * min, ScanPacing.wifiScanEveryMs(screenOn = true, moving = false))
        assertEquals(2 * min, ScanPacing.wifiScanEveryMs(screenOn = true, moving = true))
        assertEquals(2 * min, ScanPacing.wifiScanEveryMs(screenOn = false, moving = true))
        assertEquals(10 * min, ScanPacing.wifiScanEveryMs(screenOn = false, moving = false))
    }

    // ── Tabs ──

    @Test
    fun tabsLeaveTheirReadingsToARunningScanner() {
        for (tab in Tab.entries) assertNull(ScanPacing.tabRefreshMs(tab, scanning = true))
        assertEquals(15_000L, ScanPacing.tabRefreshMs(Tab.CELL, scanning = false))
        assertEquals(30_000L, ScanPacing.tabRefreshMs(Tab.DEVICE, scanning = false))
        assertEquals(60_000L, ScanPacing.tabRefreshMs(Tab.WIFI, scanning = false))
    }

    @Test
    fun theWiFiTabStaysInsideAndroidsScanAllowance() {
        // Four requests in any two minutes for an app in front; the tab leaves room.
        val period = ScanPacing.tabRefreshMs(Tab.WIFI, scanning = false)!!
        assertTrue(2 * min / period < 4)
        // And the scanner's own look is never more often than the tab's was allowed.
        assertTrue(ScanPacing.wifiScanEveryMs(screenOn = true, moving = true) >= 2 * min / 4)
    }

    // ── Housekeeping ──

    @Test
    fun retentionRunsHourly() {
        assertTrue(ScanPacing.retentionDue(lastAt = 0L, now = t0))
        assertFalse(ScanPacing.retentionDue(lastAt = t0, now = t0 + 59 * min))
        assertTrue(ScanPacing.retentionDue(lastAt = t0, now = t0 + 60 * min))
        // A clock that went back does not postpone it for as long as it went back.
        assertTrue(ScanPacing.retentionDue(lastAt = t0, now = t0 - 5 * min))
    }

    @Test
    fun aLogWhoseOldestRowIsInsideTheRetentionHoldsNothingToDelete() {
        assertFalse(ScanPacing.holdsOlderThan(oldest = null, cutoff = t0))
        assertFalse(ScanPacing.holdsOlderThan(oldest = t0, cutoff = t0))
        assertFalse(ScanPacing.holdsOlderThan(oldest = t0 + 1, cutoff = t0))
        assertTrue(ScanPacing.holdsOlderThan(oldest = t0 - 1, cutoff = t0))
    }
}
