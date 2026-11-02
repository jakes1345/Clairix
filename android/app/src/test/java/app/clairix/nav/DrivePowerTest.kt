package app.clairix.nav

import android.os.BatteryManager
import app.clairix.nav.DrivePower.Reading
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Drive tab and the battery: what stops below 15 % without a charger, and how often things are polled. */
class DrivePowerTest {

    private fun draining(pct: Int) = Reading(pct, charging = false)
    private fun charging(pct: Int) = Reading(pct, charging = true)

    @Test
    fun readingsComeFromTheBroadcastExtras() {
        assertEquals(Reading(14, false), DrivePower.reading(14, 100, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(Reading(50, false), DrivePower.reading(128, 256, BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertEquals(Reading(80, true), DrivePower.reading(80, 100, BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(Reading(100, true), DrivePower.reading(100, 100, BatteryManager.BATTERY_STATUS_FULL))
        assertNull(DrivePower.reading(-1, 100, BatteryManager.BATTERY_STATUS_CHARGING).levelPct)
        assertNull(DrivePower.reading(50, 0, BatteryManager.BATTERY_STATUS_CHARGING).levelPct)
    }

    @Test
    fun belowFifteenWithoutAChargerIsLow() {
        assertTrue(DrivePower.lowBattery(draining(14), wasLow = false))
        assertTrue(DrivePower.lowBattery(draining(0), wasLow = false))
        assertFalse(DrivePower.lowBattery(draining(15), wasLow = false))
        assertFalse(DrivePower.lowBattery(draining(80), wasLow = false))
    }

    @Test
    fun aChargerLiftsTheCutOffWhateverTheLevel() {
        assertFalse(DrivePower.lowBattery(charging(3), wasLow = false))
        assertFalse(DrivePower.lowBattery(charging(3), wasLow = true))
    }

    @Test
    fun pluggedInButStillDrainingIsNotCharging() {
        // A weak car socket, or a phone too hot to charge, reports "not charging" while plugged in.
        assertTrue(DrivePower.lowBattery(DrivePower.reading(10, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING), wasLow = false))
    }

    @Test
    fun onceLowItStaysLowUntilTwentyOrCharging() {
        assertTrue(DrivePower.lowBattery(draining(15), wasLow = true))
        assertTrue(DrivePower.lowBattery(draining(19), wasLow = true))
        assertFalse(DrivePower.lowBattery(draining(20), wasLow = true))
    }

    @Test
    fun anUnknownLevelNeverStopsAnything() {
        assertFalse(DrivePower.lowBattery(Reading(null, charging = false), wasLow = false))
        assertFalse(DrivePower.lowBattery(Reading(null, charging = false), wasLow = true))
    }

    @Test
    fun aReadingThatWobblesAroundTheLineDoesNotStartAndStopTheStick() = runBlocking {
        val readings = flowOf(
            draining(16), draining(15), draining(14), draining(15), draining(16), draining(14),
            charging(14), draining(14)
        )
        // Low once at 14, still low back at 15 and 16, released only by the charger.
        assertEquals(listOf(false, true, false, true), DrivePower.lowBatteryStates(readings).toList())
    }

    @Test
    fun aWatchStartedLowStaysLowUntilRelieved() = runBlocking {
        assertEquals(listOf(true, false), DrivePower.lowBatteryStates(flowOf(draining(17), draining(21)), initial = true).toList())
    }

    @Test
    fun navigationTimersStretchToFiveMinutesOnALowBattery() {
        assertEquals(60_000L, DrivePower.navTimerMs(60_000L, lowBattery = false))
        assertEquals(300_000L, DrivePower.navTimerMs(60_000L, lowBattery = true))
        assertEquals(300_000L, DrivePower.navTimerMs(120_000L, lowBattery = true))
        // A timer already slower than five minutes is never made faster.
        assertEquals(900_000L, DrivePower.navTimerMs(900_000L, lowBattery = true))
    }

    @Test
    fun theGaugesArePolledTwiceASecondOnScreenAndOnceOffIt() {
        assertEquals(500L, DrivePower.obdPollMs(onScreen = true))
        assertEquals(1_000L, DrivePower.obdPollMs(onScreen = false))
        // The old pause was 150 ms: six rounds of five Bluetooth requests a second.
        assertTrue(DrivePower.obdPollMs(onScreen = true) >= 500L)
        assertTrue(DrivePower.RETUNE_PAUSE_MS >= 100L)
    }
}
