package app.clairix

import app.clairix.DriveLink.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drive mode's stick and adapter: pulled out it stops at once, plugged back in it starts
 * again (or waits for Reconnect without permission), and a low battery stops both.
 */
class DriveLinkTest {

    private fun driving() = DriveLink().apply { driveModeOn() }

    @Test
    fun driveModeRunsTheStickAndTheAdapter() {
        val l = DriveLink()
        assertFalse(l.sdrShouldRun)
        assertFalse(l.obdShouldRun)
        assertFalse(l.watchBattery)
        assertEquals(Radio.OFF, l.radio)
        l.driveModeOn()
        assertTrue(l.sdrShouldRun)
        assertTrue(l.obdShouldRun)
        assertTrue(l.watchBattery)
        assertEquals(Radio.ON, l.radio)
        l.driveModeOff()
        assertFalse(l.sdrShouldRun)
        assertFalse(l.obdShouldRun)
        assertFalse(l.watchBattery)
        assertEquals(Radio.OFF, l.radio)
    }

    @Test
    fun pullingTheStickOutStopsItAtOnceAndLeavesTheAdapter() {
        val l = driving()
        l.detached()
        assertFalse(l.sdrShouldRun)
        assertTrue(l.obdShouldRun)
        assertEquals(Radio.UNPLUGGED, l.radio)
    }

    @Test
    fun pluggedBackInWithPermissionItRunsAgainByItself() {
        val l = driving().apply { detached() }
        l.attached(permitted = true)
        assertTrue(l.sdrShouldRun)
        assertEquals(Radio.ON, l.radio)
    }

    @Test
    fun pluggedBackInWithoutPermissionItWaitsForTheOwnersTap() {
        val l = driving().apply { detached() }
        l.attached(permitted = false)
        assertFalse(l.sdrShouldRun)
        assertEquals(Radio.NEEDS_PERMISSION, l.radio)
        // Reconnect: the stick is opened again, and the open asks Android for permission.
        l.retry()
        assertTrue(l.sdrShouldRun)
        assertEquals(Radio.ON, l.radio)
    }

    @Test
    fun reconnectDoesNotPretendAnUnpluggedStickIsThere() {
        val l = driving().apply { detached() }
        l.retry()
        assertFalse(l.sdrShouldRun)
        assertEquals(Radio.UNPLUGGED, l.radio)
    }

    @Test
    fun aLowBatteryStopsBothAndChargingBringsThemBack() {
        val l = driving()
        l.battery(low = true)
        assertFalse(l.sdrShouldRun)
        assertFalse(l.obdShouldRun)
        assertEquals(Radio.LOW_BATTERY, l.radio)
        // Still watched, so the charger is noticed.
        assertTrue(l.watchBattery)
        l.battery(low = false)
        assertTrue(l.sdrShouldRun)
        assertTrue(l.obdShouldRun)
        assertEquals(Radio.ON, l.radio)
    }

    @Test
    fun aStickPulledOutDuringALowBatteryStaysOutAfterIt() {
        val l = driving().apply { battery(low = true) }
        l.detached()
        assertEquals(Radio.LOW_BATTERY, l.radio)
        l.battery(low = false)
        assertEquals(Radio.UNPLUGGED, l.radio)
        assertFalse(l.sdrShouldRun)
        assertTrue(l.obdShouldRun)
    }

    @Test
    fun theVehicleCardAloneRunsTheAdapterUnderTheSameCutOff() {
        val l = DriveLink()
        l.obdConnect()
        assertTrue(l.obdShouldRun)
        assertFalse(l.sdrShouldRun)
        assertTrue(l.watchBattery)
        l.battery(low = true)
        assertFalse(l.obdShouldRun)
        l.obdDisconnect()
        assertFalse(l.watchBattery)
        // Forgotten: the next start reads the battery afresh.
        assertFalse(l.lowBattery)
    }

    @Test
    fun aReadingWithNothingToRunIsIgnored() {
        val l = DriveLink()
        l.battery(low = true)
        assertFalse(l.lowBattery)
        l.driveModeOn()
        assertTrue(l.sdrShouldRun)
    }

    @Test
    fun aDriveStartedOnALowBatteryOpensNothing() {
        // The view model turns drive mode on, then gives it the reading taken at that moment.
        val l = DriveLink()
        l.driveModeOn()
        l.battery(low = true)
        assertFalse(l.sdrShouldRun)
        assertFalse(l.obdShouldRun)
        assertEquals(Radio.LOW_BATTERY, l.radio)
    }

    @Test
    fun turningDriveModeOffEndsTheLowBatteryState() {
        val l = driving().apply { battery(low = true) }
        l.driveModeOff()
        assertFalse(l.lowBattery)
        assertEquals(Radio.OFF, l.radio)
    }

    @Test
    fun aNewDriveForgetsTheOldStick() {
        val l = driving().apply { detached() }
        l.driveModeOff()
        l.driveModeOn()
        assertEquals(DriveLink.Stick.UNKNOWN, l.stick)
        assertTrue(l.sdrShouldRun)
    }

    @Test
    fun theNotificationSaysWhatRunsAndNeverWhatIsHeard() {
        val on = DriveLink.ongoing(Radio.ON, SdrState.Ready(155_475_000L, 2_016_000), obdConnected = true)
        assertEquals("Drive mode on", on.title)
        assertEquals("Radio on · OBD-II connected", on.text)
        assertFalse("no frequency in the shade", on.text.contains("155"))
        assertEquals(
            "Radio stopped: USB sample stream stalled · OBD-II not connected",
            DriveLink.ongoing(Radio.ON, SdrState.Error("USB sample stream stalled"), obdConnected = false).text
        )
        assertTrue(DriveLink.ongoing(Radio.UNPLUGGED, SdrState.Closed, obdConnected = true).text.startsWith("Radio stick unplugged"))
        assertTrue(DriveLink.ongoing(Radio.NEEDS_PERMISSION, SdrState.Closed, obdConnected = false).text.contains("reconnect"))
        val low = DriveLink.ongoing(Radio.LOW_BATTERY, SdrState.Closed, obdConnected = false)
        assertEquals("Drive mode paused", low.title)
        assertTrue(low.text.startsWith("Battery low"))
        // Never detailed: the lock screen gets the neutral public version on top of this.
        assertFalse(on.detailed)
    }
}
