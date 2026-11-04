package app.clairix

import app.clairix.analysis.BatteryUse
import app.clairix.analysis.LowPower
import app.clairix.analysis.ScanEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The words of Settings > Battery, read from the app's string resources: honest about what is counted and what is not. */
@RunWith(RobolectricTestRunner::class)
class BatteryTextTest {

    private val battery = BatteryText(RuntimeEnvironment.getApplication().resources)

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun durations() {
        assertEquals("under a minute", battery.duration(59_000L))
        assertEquals("45 min", battery.duration(45 * min))
        assertEquals("2 h", battery.duration(120 * min))
        assertEquals("3 h 5 min", battery.duration(185 * min))
    }

    @Test
    fun theReadoutSaysItIsTheWholePhoneAndGivesARateOnlyWhenThereIsOne() {
        val use = BatteryUse.Use(sinceAt = t0, elapsedMs = 120 * min, pct = 6, mAh = 180L, pctPerHour = 3.0)
        val text = battery.used(use, scanning = true, charging = false, scanStartedAt = t0)
        assertEquals("6 % (180 mAh) in 2 h, about 3.0 % an hour. The whole phone's use, not Clairix's alone.", text)
        val early = BatteryUse.Use(sinceAt = t0, elapsedMs = 10 * min, pct = 1, mAh = null, pctPerHour = null)
        assertEquals(
            "1 % in 10 min. The whole phone's use, not Clairix's alone.",
            battery.used(early, scanning = true, charging = false, scanStartedAt = t0)
        )
    }

    @Test
    fun theReadoutSaysWhenItCountsFromAnUnplug() {
        val use = BatteryUse.Use(sinceAt = t0 + 60 * min, elapsedMs = 30 * min, pct = 2, mAh = null, pctPerHour = 4.0)
        val text = battery.used(use, scanning = true, charging = false, scanStartedAt = t0)
        assertTrue(text, text.contains("since the phone was unplugged at "))
    }

    @Test
    fun theReadoutWithoutAScanOrWhileCharging() {
        assertEquals(battery.scannerOff, battery.used(null, scanning = false, charging = false, scanStartedAt = null))
        assertTrue(battery.used(null, scanning = true, charging = true, scanStartedAt = t0).startsWith("Charging now"))
    }

    @Test
    fun theSwitchLineSaysWhyAndThatSosAndCallsAreNeverSlowed() {
        for (reason in listOf(null) + LowPower.Reason.entries) {
            val text = battery.lowPower(reason)
            assertTrue(text, text.contains("SOS, the duress passcode and incoming calls are never slowed"))
        }
        assertTrue(battery.lowPower(LowPower.Reason.BATTERY).contains("below 20 %"))
        assertTrue(battery.lowPower(LowPower.Reason.POWER_SAVE).contains("battery saver"))
        assertTrue(battery.lowPower(null).startsWith("Off."))
    }

    @Test
    fun featureLinesComeFromTheCountersAndNameNoCurrent() {
        val s = ScanEffort.Snapshot(
            elapsedMs = 180 * min, bleLowPowerMs = 120 * min, bleBalancedMs = 60 * min, bleLowLatencyMs = 0L,
            bleBatchedMs = 120 * min, movingMs = 30 * min, settledMs = 150 * min, cellReadings = 1, wifiScans = 12
        )
        val ble = battery.bluetooth(s)
        assertTrue(ble, ble.startsWith("Running 3 h: 2 h at low power"))
        assertFalse(ble, ble.contains("low latency"))
        assertTrue(battery.position(s).startsWith("Moving 30 min"))
        assertEquals("1 cell reading from the modem, 12 Wi-Fi scans asked of Android.", battery.checks(s))
        for (line in listOf(ble, battery.position(s), battery.drive, battery.navigation, battery.footnote)) {
            assertFalse(line, Regex("""\d+\s*mA\b""").containsMatchIn(line))
        }
    }

    @Test
    fun commsAndMeshLines() {
        assertEquals("Offline: no connection is kept open.", battery.comms(online = false, coverTraffic = true, lowPower = false))
        assertTrue(battery.comms(online = true, coverTraffic = true, lowPower = true).contains("paused while low power is on"))
        assertEquals("Off.", battery.mesh(running = false, fullPace = false))
        assertTrue(battery.drive.contains("powered from this phone's battery"))
    }
}
