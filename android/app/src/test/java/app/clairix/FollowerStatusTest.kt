package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Tracker on you" means heard lately and not found; and the screens state the follow
 * check's thresholds as the owner set them.
 */
class FollowerStatusTest {

    private val now = 1_700_000_000_000L

    private fun follower(lastSeen: Long, resolvedAt: Long = 0L) = Detection(
        key = "k", address = "AA:BB:CC:DD:EE:FF", name = "Apple AirTag", rssi = -60, tracker = null,
        threat = Threat.CRITICAL, score = 90, firstSeen = lastSeen - 3_600_000L, lastSeen = lastSeen,
        sightings = 40, persistent = true, following = true, confidence = FollowConfidence.CONFIRMED,
        displacementM = 900.0, places = 4, rotations = 0, addresses = 1, approxMetres = null,
        resolvedAt = resolvedAt
    )

    @Test
    fun aFollowerHeardInTheLastHalfHourIsOnYouNow() {
        val recent = follower(now - 5 * 60_000L)
        assertTrue(FollowerStatus.onYouNow(recent, now))
        assertFalse(FollowerStatus.followedEarlier(recent, now))
    }

    @Test
    fun oneLastHeardDaysAgoFollowedYouEarlier() {
        val old = follower(now - 3 * 24 * 3_600_000L)
        assertFalse(FollowerStatus.onYouNow(old, now))
        assertTrue(FollowerStatus.followedEarlier(old, now))
    }

    @Test
    fun oneMarkedFoundCountsForNothing() {
        val found = follower(now - 60_000L, resolvedAt = now - 30_000L)
        assertFalse(FollowerStatus.withYou(found))
        assertFalse(FollowerStatus.onYouNow(found, now))
        assertFalse(FollowerStatus.followedEarlier(found, now))
    }

    @Test
    fun theThresholdsAreTheOwnersNotTheDefaults() {
        assertEquals("10 minutes of presence and a 300 m move", FollowerStatus.proof(10f, 300f))
        assertEquals("5 minutes of presence and a 750 m move", FollowerStatus.proof(5f, 750f))
        assertEquals("12 minutes of presence and a 150 m move", FollowerStatus.proof(12f, 150f))
    }
}
