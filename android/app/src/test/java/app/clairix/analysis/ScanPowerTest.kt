package app.clairix.analysis

import android.bluetooth.le.ScanSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Used since the scan started" and the scanner's own counters in Settings > Battery. */
class ScanPowerTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private fun sample(at: Long, pct: Int?, uah: Long? = null, charging: Boolean = false) = BatterySample(at, pct, uah, charging)

    @After
    fun tearDown() = ScanPower.stop()

    // ── The battery arithmetic ──

    @Test
    fun theChargeCounterReadsAsAbsentWhereThePhoneDoesNotReportIt() {
        assertNull(BatteryUse.chargeUah(Int.MIN_VALUE))
        assertNull(BatteryUse.chargeUah(0))
        assertNull(BatteryUse.chargeUah(-5))
        assertEquals(3_000_000L, BatteryUse.chargeUah(3_000_000))
    }

    @Test
    fun levelPointsChargeAndARatePerHour() {
        val use = BatteryUse.use(sample(t0, 90, 3_600_000L), sample(t0 + 2 * hour, 84, 3_420_000L))!!
        assertEquals(2 * hour, use.elapsedMs)
        assertEquals(6, use.pct)
        assertEquals(180L, use.mAh)
        assertEquals(3.0, use.pctPerHour!!, 1e-9)
        assertEquals(t0, use.sinceAt)
    }

    @Test
    fun noRateBeforeHalfAnHourAndNoNegativeDrain() {
        val early = BatteryUse.use(sample(t0, 90), sample(t0 + 29 * min, 89))!!
        assertEquals(1, early.pct)
        assertNull(early.pctPerHour)
        // A level that reads higher (a recalibration) is not counted as a gain.
        assertEquals(0, BatteryUse.use(sample(t0, 50), sample(t0 + hour, 52))!!.pct)
    }

    @Test
    fun aCounterThatWentUpOrIsMissingAtOneEndGivesNoMilliampHours() {
        assertNull(BatteryUse.use(sample(t0, 90, 1_000_000L), sample(t0 + hour, 89, 1_200_000L))!!.mAh)
        assertNull(BatteryUse.use(sample(t0, 90, null), sample(t0 + hour, 89, 1_200_000L))!!.mAh)
        assertNull(BatteryUse.use(sample(t0, null), sample(t0 + hour, 89))!!.pct)
    }

    @Test
    fun nothingIsCountedWhileChargingOrWithoutAStartOrBackwardsInTime() {
        assertNull(BatteryUse.use(null, sample(t0, 80)))
        assertNull(BatteryUse.use(sample(t0, 90), sample(t0 + hour, 95, charging = true)))
        assertNull(BatteryUse.use(sample(t0, 90), sample(t0 - 1, 90)))
    }

    @Test
    fun aChargeStartsTheCountAgainFromTheUnplug() {
        var base = BatteryUse.rebase(null, sample(t0, 60))
        assertEquals(t0, base!!.at)
        base = BatteryUse.rebase(base, sample(t0 + hour, 55))
        assertEquals(t0, base!!.at)
        base = BatteryUse.rebase(base, sample(t0 + 2 * hour, 70, charging = true))
        assertNull(base)
        base = BatteryUse.rebase(base, sample(t0 + 3 * hour, 100))
        assertEquals(t0 + 3 * hour, base!!.at)
    }

    @Test
    fun theScanRecordCountsFromTheStartAndRebasesOnACharge() {
        ScanPower.start(t0, sample(t0, 80))
        assertEquals(5, ScanPower.use(sample(t0 + hour, 75))!!.pct)
        ScanPower.onBattery(sample(t0 + hour, 76, charging = true))
        assertNull(ScanPower.use(sample(t0 + hour, 80, charging = true)))
        ScanPower.onBattery(sample(t0 + 2 * hour, 90))
        val after = ScanPower.use(sample(t0 + 3 * hour, 87))!!
        assertEquals(3, after.pct)
        assertEquals(t0 + 2 * hour, after.sinceAt)
        ScanPower.stop()
        assertNull(ScanPower.use(sample(t0 + 3 * hour, 87)))
    }

    @Test
    fun aScanStartedOnTheChargerCountsFromTheUnplug() {
        ScanPower.start(t0, sample(t0, 100, charging = true))
        assertNull(ScanPower.use(sample(t0 + min, 100)))
        ScanPower.onBattery(sample(t0 + hour, 100))
        assertEquals(t0 + hour, ScanPower.use(sample(t0 + 2 * hour, 97))!!.sinceAt)
    }

    // ── The scanner's own counters ──

    @Test
    fun bluetoothTimeIsSplitByModeAndBatching() {
        val e = ScanEffort(t0)
        e.ble(ScanSettings.SCAN_MODE_BALANCED, batched = false, t0)
        e.ble(ScanSettings.SCAN_MODE_LOW_POWER, batched = true, t0 + 10 * min)
        e.ble(null, batched = false, t0 + 40 * min)
        e.ble(ScanSettings.SCAN_MODE_LOW_LATENCY, batched = false, t0 + 50 * min)
        val s = e.snapshot(t0 + 55 * min)
        assertEquals(10 * min, s.bleBalancedMs)
        assertEquals(30 * min, s.bleLowPowerMs)
        assertEquals(5 * min, s.bleLowLatencyMs)
        assertEquals(30 * min, s.bleBatchedMs)
        assertEquals(45 * min, s.bleMs)
        assertEquals(55 * min, s.elapsedMs)
    }

    @Test
    fun movingAndSettledTimeAndTheCounts() {
        val e = ScanEffort(t0)
        e.pace(true, t0)
        e.pace(false, t0 + 20 * min)
        repeat(3) { e.cellReading() }
        e.wifiScan()
        // A snapshot in between does not count the running spell twice.
        assertEquals(10 * min, e.snapshot(t0 + 30 * min).settledMs)
        val s = e.snapshot(t0 + hour)
        assertEquals(20 * min, s.movingMs)
        assertEquals(40 * min, s.settledMs)
        assertEquals(3, s.cellReadings)
        assertEquals(1, s.wifiScans)
        // Positions stopped: nothing more is counted.
        e.pace(null, t0 + hour)
        assertEquals(40 * min, e.snapshot(t0 + 2 * hour).settledMs)
    }
}
