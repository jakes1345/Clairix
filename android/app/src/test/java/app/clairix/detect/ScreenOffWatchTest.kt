package app.clairix.detect

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.Threat
import app.clairix.TrackerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-off scan keeps a filter for each device already under watch that the
 * tracker-type filters would miss, so a GPS box found with the screen on is still
 * heard with it off.
 */
class ScreenOffWatchTest {

    private val t0 = 1_700_000_000_000L

    private fun d(
        address: String, tracker: TrackerType? = null, persistent: Boolean = false, following: Boolean = false,
        lastSeen: Long = t0, sightings: Int = 3, addressKind: String? = null
    ) = Detection(
        key = address, address = address, name = address, rssi = -70, tracker = tracker, threat = Threat.MEDIUM,
        score = 40, firstSeen = t0, lastSeen = lastSeen, sightings = sightings, persistent = persistent,
        following = following, confidence = FollowConfidence.NONE, displacementM = 0.0, places = 0,
        rotations = 0, addresses = 1, approxMetres = null, addressKind = addressKind
    )

    @Test
    fun anUnbrandedBoxUnderWatchGetsAFilterACataloguedTrackerDoesNot() {
        val box = d("C0:00:00:00:00:01", persistent = true)
        val gps = d("C0:00:00:00:00:02", tracker = Signatures.GPS_BLE)
        val airTag = d("C0:00:00:00:00:03", tracker = Signatures.AIRTAG, persistent = true)
        val passerBy = d("C0:00:00:00:00:04", sightings = 40)
        val watched = ScreenOffWatch.watched(listOf(box, gps, airTag, passerBy), trusted = emptySet()).map { it.address }
        // The AirTag is heard through the Find My filter anyway; the passing crowd is left out.
        assertEquals(listOf(box.address, gps.address), watched)
    }

    @Test
    fun followersComeFirstTrustedDevicesNeverAndTheListIsCapped() {
        val follower = d("C0:00:00:00:00:10", persistent = true, following = true, lastSeen = t0 - 60_000L)
        val mine = d("C0:00:00:00:00:11", persistent = true, following = true)
        val boxes = (0 until 30).map { d("C0:00:00:00:01:%02X".format(it), persistent = true, lastSeen = t0 + it) }
        val watched = ScreenOffWatch.watched(boxes + mine + follower, trusted = setOf(mine.address))
        assertEquals(ScreenOffWatch.MAX_ADDRESS_FILTERS, watched.size)
        assertEquals(follower.address, watched.first().address)
        assertFalse(watched.any { it.address == mine.address })
        // Then the most recently heard.
        assertEquals(boxes.last().address, watched[1].address)
    }

    @Test
    fun theAddressTypeIsPassedWhenThePlatformToldUs() {
        val watched = ScreenOffWatch.watched(
            listOf(
                d("C0:00:00:00:00:21", persistent = true, addressKind = "public"),
                d("C0:00:00:00:00:22", persistent = true, addressKind = "random static", lastSeen = t0 - 1),
                d("C0:00:00:00:00:23", persistent = true, lastSeen = t0 - 2)
            ),
            trusted = emptySet()
        )
        assertEquals(listOf(false, true, null), watched.map { it.random })
    }

    @Test
    fun theScanIsRegisteredAgainOnlyForAChangeAndAtMostOnceAMinute() {
        val now = t0 + 10 * 60_000L
        assertFalse(ScreenOffWatch.refreshDue(setOf("A"), setOf("A"), registeredAt = t0, now = now))
        assertTrue(ScreenOffWatch.refreshDue(setOf("A", "B"), setOf("A"), registeredAt = t0, now = now))
        assertFalse(ScreenOffWatch.refreshDue(setOf("A", "B"), setOf("A"), registeredAt = now - 30_000L, now = now))
    }

    @Test
    fun onlyAGpsModuleIsLeftToItsAddressAmongTheSignatures() {
        assertFalse(ScreenOffWatch.coveredByTypeFilter(Signatures.GPS_BLE))
        assertFalse(ScreenOffWatch.coveredByTypeFilter(null))
        for (t in listOf(Signatures.AIRTAG, Signatures.TILE, Signatures.IBEACON, Signatures.EDDYSTONE, Signatures.GOOGLE_FIND_HUB)) {
            assertTrue(t.id, ScreenOffWatch.coveredByTypeFilter(t))
        }
    }
}
