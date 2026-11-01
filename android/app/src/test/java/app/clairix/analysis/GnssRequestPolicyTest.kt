package app.clairix.analysis

import android.location.GnssMeasurementRequest
import app.clairix.analysis.GnssRequestPolicy.Measurements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the satellite checks ask the platform for: never a receiver left running for
 * the scanner alone, and a Wi-Fi/cell position only while there is a satellite fix to
 * hold it against.
 */
class GnssRequestPolicyTest {

    private val min = 60_000L
    private val t0 = 5_000_000L

    @Test
    fun aSettledScannerNeverKeepsTheReceiverOnFromAndroid13() {
        assertEquals(Measurements.Passive, GnssRequestPolicy.measurements(sdk = 34, foreground = false, moving = false))
        assertEquals(Measurements.Passive, GnssRequestPolicy.measurements(sdk = 35, foreground = false, moving = false))
        assertEquals(Measurements.Rare, GnssRequestPolicy.measurements(sdk = 33, foreground = false, moving = false))
    }

    @Test
    fun onTheMoveTheScannerAsksForMeasurementsSoAJammerIsNotATunnel() {
        // A passive request hears measurements only for another app's request, and
        // nothing else in Clairix makes one: on the move the gain check would have no
        // readings and a jammed fix would read as a tunnel.
        for (sdk in listOf(33, 34, 35)) {
            assertEquals(Measurements.Every(GnssRequestPolicy.MOVING_INTERVAL_MS), GnssRequestPolicy.measurements(sdk, foreground = false, moving = true))
        }
        // Fast enough that the gain alarm's run of readings fits in its freshness window.
        assertTrue(GnssRequestPolicy.MOVING_INTERVAL_MS.toLong() * GnssIntegrity.WINDOW_EPOCHS <= GnssIntegrity.MEASUREMENT_FRESH_MS)
    }

    @Test
    fun onAndroid12TheScannerRegistersOnlyWhileThePhoneMoves() {
        for (sdk in listOf(31, 32)) {
            assertEquals(Measurements.Running, GnssRequestPolicy.measurements(sdk, foreground = false, moving = true))
            assertEquals(Measurements.Off, GnssRequestPolicy.measurements(sdk, foreground = false, moving = false))
        }
    }

    @Test
    fun theGpsScreenSwitchesTheReceiverOnOnceASecond() {
        for (sdk in listOf(33, 34, 35)) {
            for (moving in listOf(true, false)) {
                assertEquals(Measurements.Every(1_000), GnssRequestPolicy.measurements(sdk, foreground = true, moving = moving))
            }
        }
        // Android 12 has no interval to give: the platform default, settled or not.
        assertEquals(Measurements.Running, GnssRequestPolicy.measurements(sdk = 31, foreground = true, moving = false))
        assertEquals(Measurements.Running, GnssRequestPolicy.measurements(sdk = 32, foreground = true, moving = true))
    }

    @Test
    fun theRequestIntervalsAreThePlatformsOwn() {
        // Android 14's passive request is the longest interval there is; Android 13 is
        // given the same value, which it reads as one measurement and then the next after it.
        assertEquals(GnssMeasurementRequest.PASSIVE_INTERVAL, GnssRequestPolicy.intervalMillis(Measurements.Passive))
        assertEquals(Int.MAX_VALUE, GnssRequestPolicy.intervalMillis(Measurements.Rare))
        assertEquals(1_000, GnssRequestPolicy.intervalMillis(Measurements.Every(1_000)))
        assertNull(GnssRequestPolicy.intervalMillis(Measurements.Running))
        assertNull(GnssRequestPolicy.intervalMillis(Measurements.Off))
    }

    @Test
    fun noWiFiOrCellPositionIsAskedForWithoutASatelliteFixToHoldItAgainst() {
        // No satellite fix yet: nothing to compare with.
        assertNull(GnssRequestPolicy.networkIntervalMs(foreground = false, moving = true, lastGnssFixAt = 0L, now = t0))
        // The satellites went quiet two minutes ago: they are off, moving or not.
        assertNull(GnssRequestPolicy.networkIntervalMs(foreground = false, moving = true, lastGnssFixAt = t0, now = t0 + 2 * min + 1))
        assertNull(GnssRequestPolicy.networkIntervalMs(foreground = false, moving = false, lastGnssFixAt = t0, now = t0 + 30 * min))
    }

    @Test
    fun theWiFiOrCellPositionIsPacedByMotionWhileSatelliteFixesArrive() {
        assertEquals(60_000L, GnssRequestPolicy.networkIntervalMs(foreground = false, moving = true, lastGnssFixAt = t0, now = t0 + 15_000L))
        assertEquals(15 * min, GnssRequestPolicy.networkIntervalMs(foreground = false, moving = false, lastGnssFixAt = t0, now = t0 + 15_000L))
        // Up to the quiet limit itself the satellites still count as on.
        assertEquals(60_000L, GnssRequestPolicy.networkIntervalMs(foreground = false, moving = true, lastGnssFixAt = t0, now = t0 + 2 * min))
    }

    @Test
    fun theGpsScreenKeepsItsOnceAMinuteComparison() {
        assertEquals(60_000L, GnssRequestPolicy.networkIntervalMs(foreground = true, moving = false, lastGnssFixAt = 0L, now = t0))
        assertEquals(60_000L, GnssRequestPolicy.networkIntervalMs(foreground = true, moving = false, lastGnssFixAt = t0, now = t0 + 30 * min))
    }

    @Test
    fun aSettledComparisonGoesStaleBetweenPositionsAndThenVouchesForNothing() {
        // Settled, a Wi-Fi/cell position comes every fifteen minutes at most. The
        // integrity check vouches for one only while its comparison is under five
        // minutes old; in between, the alert carries no position and the advice names
        // neither source, as with no comparison at all.
        val g = GnssIntegrity()
        val t = 1_700_000_000_000L
        g.onGnssFix(GnssFix(t, 40.0, -75.0, 8f, t))
        g.onNetworkFix(NetworkFix(t + 1_000L, 40.0005, -75.0, 40f))
        val fresh = g.assess(t + min)
        assertTrue(fresh.networkAgrees)
        assertFalse(fresh.networkDisputed)
        assertNotNull(fresh.vouchedNetwork)
        val stale = g.assess(t + GnssRequestPolicy.NETWORK_SETTLED_MS - 1_000L)
        assertFalse(stale.networkAgrees)
        assertFalse(stale.networkDisputed)
        assertNull(stale.vouchedNetwork)
    }

    @Test
    fun aComparisonPairsFixesWithinAMinuteSoTheQuietLimitCoversTheScannersFixRate() {
        // The scanner's position request has the satellites on about every 15 s while
        // moving; the quiet limit must not drop the request between two of them.
        assertTrue(GnssRequestPolicy.GNSS_QUIET_MS >= 4 * 15_000L)
        assertTrue(GnssRequestPolicy.NETWORK_MOVING_MS <= GnssRequestPolicy.GNSS_QUIET_MS)
    }
}
