package app.clairix.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The locator for someone who cannot, or should not, watch the screen. */
class LocatorCuesTest {

    private fun state(
        distance: Float = 2.2f, rssi: Float = -60f, trend: SignalTrend = SignalTrend.WARMER,
        lastSeenAgoMs: Long? = 300L, bestAzimuth: Float = Float.NaN, heading: Float = 0f
    ) = LocatorState(
        address = "AA:BB:CC:DD:EE:FF", headingDeg = heading, bestAzimuthDeg = bestAzimuth,
        rssiByBucket = FloatArray(RssiLocator.BUCKETS) { Float.NaN }, estimatedDistanceM = distance,
        confidence = 0.62f, lastRssi = rssi.toInt(), trend = trend, samples = 40, lastSeenAgoMs = lastSeenAgoMs,
        smoothedRssi = rssi
    )

    @Test
    fun theLiveLineChangesOnlyWithTheTrendAndTheDistanceBand() {
        // Fluctuations inside one band say nothing new, so nothing is read out again.
        assertEquals(LocatorCues.announcement(state(distance = 2.1f)), LocatorCues.announcement(state(distance = 2.8f)))
        assertEquals("Warmer, about 2 metres", LocatorCues.announcement(state(distance = 2.1f)))
        assertEquals("Colder, about 8 metres", LocatorCues.announcement(state(distance = 9f, trend = SignalTrend.COLDER)))
        assertEquals("Steady, within about a metre", LocatorCues.announcement(state(distance = 0.6f, trend = SignalTrend.STEADY)))
        assertEquals("Signal lost", LocatorCues.announcement(state(lastSeenAgoMs = 6_000L)))
        assertEquals("Listening for the tracker", LocatorCues.announcement(state(lastSeenAgoMs = null)))
    }

    @Test
    fun theDialIsDescribedInWords() {
        val spoken = LocatorCues.description(state(distance = 4.5f, rssi = -66f, bestAzimuth = 45f, heading = 0f))
        assertTrue(spoken, spoken.contains("about 4 metres"))
        assertTrue(spoken, spoken.contains("-66 dBm"))
        assertTrue(spoken, spoken.contains("strongest 45 degrees to your right"))
        assertTrue(spoken, spoken.contains("confidence 62 percent"))
        val noBearing = LocatorCues.description(state())
        assertTrue(noBearing, noBearing.contains("turn a full circle"))
        assertEquals("straight ahead", LocatorCues.bearing(state(bestAzimuth = 90f, heading = 85f)))
        assertEquals("30 degrees to your left", LocatorCues.bearing(state(bestAzimuth = 330f, heading = 0f)))
    }

    @Test
    fun thePulseQuickensAsTheSignalStrengthens() {
        assertEquals(LocatorCues.SLOWEST_PULSE_MS, LocatorCues.pulsePeriodMs(state(rssi = -95f)))
        assertEquals(LocatorCues.FASTEST_PULSE_MS, LocatorCues.pulsePeriodMs(state(rssi = -40f)))
        val far = LocatorCues.pulsePeriodMs(state(rssi = -80f))!!
        val near = LocatorCues.pulsePeriodMs(state(rssi = -55f))!!
        assertTrue("$far > $near", far > near)
        // Nothing heard lately, or nothing heard in the last moment: no pulse to follow.
        assertNull(LocatorCues.pulsePeriodMs(state(lastSeenAgoMs = 5_000L)))
        assertNull(LocatorCues.pulsePeriodMs(state(rssi = Float.NaN)))
    }
}
