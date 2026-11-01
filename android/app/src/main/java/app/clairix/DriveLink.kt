package app.clairix

import app.clairix.security.NotificationText

/**
 * Drive mode's radio and vehicle halves as decisions, apart from the stick's driver,
 * the Bluetooth link and the receivers that report on them; [DriveViewModel] feeds it
 * events and starts or stops what it says should run.
 *
 * The SDR stick runs while drive mode is on, the battery is not low and the stick is
 * not known to be unplugged or waiting for permission. Pulled out (the one thing every
 * drive with a cable does sooner or later), it is stopped at once and its aircraft are
 * cleared instead of standing frozen on the list. Plugged back in with Clairix already
 * allowed to use it, it starts again by itself. Plugged back in without that permission
 * it waits for the owner to tap Reconnect: asking puts a system dialog over whatever is
 * on the screen, and that may be the road. The OBD-II link runs while it is wanted (drive
 * mode, or Connect on the Vehicle card) and the battery is not low. The battery is
 * watched only while one of them is wanted.
 */
class DriveLink {

    /** What is known of the stick since drive mode started. */
    enum class Stick { UNKNOWN, PLUGGED, UNPLUGGED, NEEDS_PERMISSION }

    /** What the radio half is doing, as the Drive screen and the notification say it. */
    enum class Radio { OFF, ON, UNPLUGGED, NEEDS_PERMISSION, LOW_BATTERY }

    var driveOn = false
        private set
    var obdWanted = false
        private set
    var stick = Stick.UNKNOWN
        private set
    var lowBattery = false
        private set

    /** Whether the stick should be streaming. */
    val sdrShouldRun: Boolean
        get() = driveOn && !lowBattery && stick != Stick.UNPLUGGED && stick != Stick.NEEDS_PERMISSION

    /** Whether the OBD-II link should be polling. */
    val obdShouldRun: Boolean get() = obdWanted && !lowBattery

    /** Whether the battery needs watching: only while something here may run. */
    val watchBattery: Boolean get() = driveOn || obdWanted

    val radio: Radio
        get() = when {
            !driveOn -> Radio.OFF
            lowBattery -> Radio.LOW_BATTERY
            stick == Stick.UNPLUGGED -> Radio.UNPLUGGED
            stick == Stick.NEEDS_PERMISSION -> Radio.NEEDS_PERMISSION
            else -> Radio.ON
        }

    /** Drive mode turned on: the stick is tried afresh, and the adapter is wanted. */
    fun driveModeOn() {
        driveOn = true
        obdWanted = true
        stick = Stick.UNKNOWN
    }

    /** Drive mode turned off: nothing runs, and nothing is known of the stick any more. */
    fun driveModeOff() {
        driveOn = false
        obdWanted = false
        stick = Stick.UNKNOWN
        forgetBatteryIfUnwatched()
    }

    fun obdConnect() {
        obdWanted = true
    }

    fun obdDisconnect() {
        obdWanted = false
        forgetBatteryIfUnwatched()
    }

    /** A stick this driver knows was pulled out. */
    fun detached() {
        stick = Stick.UNPLUGGED
    }

    /** A stick this driver knows was plugged in; [permitted] when Clairix may already use it. */
    fun attached(permitted: Boolean) {
        stick = if (permitted) Stick.PLUGGED else Stick.NEEDS_PERMISSION
    }

    /**
     * The owner tapped Reconnect: try the stick again, asking for permission if need be.
     * A stick known to be out stays out until it is plugged in.
     */
    fun retry() {
        if (stick != Stick.UNPLUGGED) stick = Stick.UNKNOWN
    }

    fun battery(low: Boolean) {
        lowBattery = low && watchBattery
    }

    /** With nothing left to run, a low reading from before means nothing; the next start reads afresh. */
    private fun forgetBatteryIfUnwatched() {
        if (!watchBattery) lowBattery = false
    }

    companion object {
        private const val TITLE = "Drive mode on"

        /**
         * What drive mode's ongoing notification says: that it runs and what of it is
         * stopped, never what the radio is hearing ([app.clairix.security.NotificationPresentation]
         * gives the lock-screen version, which says only that something runs).
         */
        fun ongoing(radio: Radio, sdr: SdrState, obdConnected: Boolean): NotificationText {
            val obd = if (obdConnected) "OBD-II connected" else "OBD-II not connected"
            return when (radio) {
                Radio.LOW_BATTERY -> NotificationText(
                    "Drive mode paused",
                    "Battery low: the radio and OBD-II stay off until the phone charges"
                )
                Radio.UNPLUGGED -> NotificationText(TITLE, "Radio stick unplugged · $obd")
                Radio.NEEDS_PERMISSION -> NotificationText(TITLE, "Radio stick plugged in: open Clairix to reconnect it · $obd")
                Radio.ON, Radio.OFF -> NotificationText(
                    TITLE,
                    when (sdr) {
                        is SdrState.Ready -> "Radio on · $obd"
                        is SdrState.Opening -> "Radio starting · $obd"
                        is SdrState.Error -> "Radio stopped: ${sdr.msg} · $obd"
                        SdrState.Closed -> "Radio off · $obd"
                    }
                )
            }
        }
    }
}
