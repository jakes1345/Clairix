package app.clairix

import app.clairix.analysis.BatteryUse
import app.clairix.analysis.LowPower
import app.clairix.analysis.ScanEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of Settings > Battery: honest about what is counted and what is not. */
class BatteryTextTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun durations() {
        assertEquals("under a minute", BatteryText.duration(59_000L))
        assertEquals("45 min", BatteryText.duration(45 * min))
        assertEquals("2 h", BatteryText.duration(120 * min))
        assertEquals("3 h 5 min", BatteryText.duration(185 * min))
    }

    @Test
    fun theReadoutSaysItIsTheWholePhoneAndGivesARateOnlyWhenThereIsOne() {
        val use = BatteryUse.Use(sinceAt = t0, elapsedMs = 120 * min, pct = 6, mAh = 180L, pctPerHour = 3.0)
        val text = BatteryText.used(use, scanning = true, charging = false, scanStartedAt = t0)
        assertEquals("6 % (180 mAh) in 2 h, about 3.0 % an hour. The whole phone's use, not Clairix's alone.", text)
        val early = BatteryUse.Use(sinceAt = t0, elapsedMs = 10 * min, pct = 1, mAh = null, pctPerHour = null)
        assertEquals(
            "1 % in 10 min. The whole phone's use, not Clairix's alone.",
            BatteryText.used(early, scanning = true, charging = false, scanStartedAt = t0)
        )
    }

    @Test
    fun theReadoutSaysWhenItCountsFromAnUnplug() {
        val use = BatteryUse.Use(sinceAt = t0 + 60 * min, elapsedMs = 30 * min, pct = 2, mAh = null, pctPerHour = 4.0)
        val text = BatteryText.used(use, scanning = true, charging = false, scanStartedAt = t0)
        assertTrue(text, text.contains("since the phone was unplugged at "))
    }

    @Test
    fun theReadoutWithoutAScanOrWhileCharging() {
        assertEquals(BatteryText.SCANNER_OFF, BatteryText.used(null, scanning = false, charging = false, scanStartedAt = null))
        assertTrue(BatteryText.used(null, scanning = true, charging = true, scanStartedAt = t0).startsWith("Charging now"))
    }

    @Test
    fun theSwitchLineSaysWhyAndThatSosAndCallsAreNeverSlowed() {
        for (reason in listOf(null) + LowPower.Reason.entries) {
            val text = BatteryText.lowPower(reason)
            assertTrue(text, text.contains("SOS, the duress passcode and incoming calls are never slowed"))
        }
        assertTrue(BatteryText.lowPower(LowPower.Reason.BATTERY).contains("below 20 %"))
        assertTrue(BatteryText.lowPower(LowPower.Reason.POWER_SAVE).contains("battery saver"))
        assertTrue(BatteryText.lowPower(null).startsWith("Off."))
    }

    @Test
    fun featureLinesComeFromTheCountersAndNameNoCurrent() {
        val s = ScanEffort.Snapshot(
            elapsedMs = 180 * min, bleLowPowerMs = 120 * min, bleBalancedMs = 60 * min, bleLowLatencyMs = 0L,
            bleBatchedMs = 120 * min, movingMs = 30 * min, settledMs = 150 * min, cellReadings = 1, wifiScans = 12
        )
        val ble = BatteryText.bluetooth(s)
        assertTrue(ble, ble.startsWith("Running 3 h: 2 h at low power"))
        assertFalse(ble, ble.contains("low latency"))
        assertTrue(BatteryText.position(s).startsWith("Moving 30 min"))
        assertEquals("1 cell reading from the modem, 12 Wi-Fi scans asked of Android.", BatteryText.checks(s))
        for (line in listOf(ble, BatteryText.position(s), BatteryText.DRIVE, BatteryText.NAVIGATION, BatteryText.FOOTNOTE)) {
            assertFalse(line, Regex("""\d+\s*mA\b""").containsMatchIn(line))
        }
    }

    @Test
    fun commsAndMeshLines() {
        assertEquals("Offline: no connection is kept open.", BatteryText.comms(online = false, coverTraffic = true, lowPower = false))
        assertTrue(BatteryText.comms(online = true, coverTraffic = true, lowPower = true).contains("paused while low power is on"))
        assertEquals("Off.", BatteryText.mesh(running = false, fullPace = false))
        assertTrue(BatteryText.DRIVE.contains("powered from this phone's battery"))
    }
}
