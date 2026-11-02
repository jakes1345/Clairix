package app.clairix.analysis

/**
 * What [GnssMonitor] asks the platform for, as decisions apart from the monitor.
 *
 * The status callback and the passive location listener cost nothing of their own:
 * they report whenever something else has the receiver running. The raw-measurement
 * callback does not. Registered without a request it asks for the fastest rate the
 * chipset can report, and the receiver then runs for as long as it stays registered:
 * held by the scanner, that was a once-a-second satellite session for the whole scan,
 * on a desk or not. So once the phone has settled it is registered only as the
 * platform allows it to cost nothing; it asks for measurements itself only while the
 * phone is going somewhere, when the scanner's own position fixes have the receiver
 * on anyway ([measurements]).
 *
 * The Wi-Fi/cell position is what the satellites are held against: a satellite
 * spoofer does not touch it. The scanner's own fixes cannot stand in for it, because
 * a fused fix may come from the satellites themselves. Each one wakes the radios, so
 * it is asked for only while there is a satellite fix to hold it against, and less
 * often once the phone has settled ([networkIntervalMs]).
 */
object GnssRequestPolicy {

    /** How the raw-measurement callback is registered. */
    sealed interface Measurements {
        /** Not registered. */
        data object Off : Measurements

        /** Registered with the platform's default request, which on Android 12 has no interval to give: the receiver runs while it is. */
        data object Running : Measurements

        /** One report every [intervalMs], with the receiver on for it; the GPS screen asks for once a second. */
        data class Every(val intervalMs: Int) : Measurements

        /**
         * Android 14 and later: a passive request. It switches nothing on and costs
         * nothing; measurements arrive whenever another app's measurement request has
         * them generated.
         */
        data object Passive : Measurements

        /**
         * Android 13, which has the interval but not the passive request: the longest
         * interval there is. Android 13 takes one measurement when it is registered and
         * the next after that interval, about 25 days later; as near to passive as it allows.
         */
        data object Rare : Measurements
    }

    /** The GPS screen's measurement rate, the rate of the satellite fixes it asks for. */
    const val FOREGROUND_INTERVAL_MS = 1_000

    /**
     * The scanner's measurement rate while the phone moves. The jamming verdict needs
     * the gain control to have been off for [GnssIntegrity.WINDOW_EPOCHS] readings in a
     * row, each under [GnssIntegrity.MEASUREMENT_FRESH_MS] old, when the fix collapses:
     * once a second has that within seconds, as Android 12's default request does.
     */
    const val MOVING_INTERVAL_MS = 1_000

    /**
     * The measurement registration for API level [sdk]. A [foreground] holder (the GPS
     * screen, open and in front) switches the receiver on itself and gets measurements
     * at its rate. Otherwise only the scanner holds the monitor. While the phone is
     * [moving], the scanner's own position fixes (and navigation) have the receiver on
     * anyway, so it asks for measurements itself: once every [MOVING_INTERVAL_MS] on
     * Android 13 and later, the platform default on Android 12, which has no interval
     * to give. A passive request would leave the clock and gain checks without data
     * on the move, and a jammer would read as a tunnel. Once the phone has settled, on
     * Android 13 and later the registration is passive or as good as, and the clock and
     * gain checks get measurements only when another app has asked for them (the
     * satellite list and positions, which the other checks read, still arrive whenever
     * anything has the receiver on); on Android 12, which cannot register without
     * switching the receiver on, it is dropped.
     */
    fun measurements(sdk: Int, foreground: Boolean, moving: Boolean): Measurements = when {
        foreground && sdk >= 33 -> Measurements.Every(FOREGROUND_INTERVAL_MS)
        foreground -> Measurements.Running
        moving && sdk >= 33 -> Measurements.Every(MOVING_INTERVAL_MS)
        sdk >= 34 -> Measurements.Passive
        sdk == 33 -> Measurements.Rare
        moving -> Measurements.Running
        else -> Measurements.Off
    }

    /**
     * The interval to set on the request for [m] (Android 13 and later), or null to
     * leave the platform's default. The passive request is the longest interval, the
     * value Android 14 names `GnssMeasurementRequest.PASSIVE_INTERVAL` and treats as
     * passive; Android 13 reads the same value as one measurement, then the next after it.
     */
    fun intervalMillis(m: Measurements): Int? = when (m) {
        is Measurements.Every -> m.intervalMs
        Measurements.Passive, Measurements.Rare -> Int.MAX_VALUE
        Measurements.Running, Measurements.Off -> null
    }

    /** A Wi-Fi/cell position this often while the phone is moving or the GPS screen is open. */
    const val NETWORK_MOVING_MS = 60_000L

    /** And this often once the scanner has seen the phone settle, while satellite fixes still arrive. */
    const val NETWORK_SETTLED_MS = 15 * 60_000L

    /**
     * No satellite fix for this long and there is nothing to hold a Wi-Fi/cell position
     * against: the comparison pairs fixes taken within a minute of each other. The
     * scanner's position request has the satellites on about every fifteen seconds
     * while the phone moves, so two minutes without one means they are off.
     */
    const val GNSS_QUIET_MS = 2 * 60_000L

    /**
     * The interval to register the Wi-Fi/cell request at, or null for none. The GPS
     * screen ([foreground]) keeps it once a minute, as it always has. Otherwise it is
     * asked for only while satellite fixes are arriving ([lastGnssFixAt], on the same
     * clock as [now]; 0 before the first): once a minute while [moving], every
     * [NETWORK_SETTLED_MS] once settled.
     */
    fun networkIntervalMs(foreground: Boolean, moving: Boolean, lastGnssFixAt: Long, now: Long): Long? = when {
        foreground -> NETWORK_MOVING_MS
        lastGnssFixAt == 0L || now - lastGnssFixAt > GNSS_QUIET_MS -> null
        moving -> NETWORK_MOVING_MS
        else -> NETWORK_SETTLED_MS
    }
}
