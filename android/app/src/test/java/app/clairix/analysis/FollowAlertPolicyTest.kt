package app.clairix.analysis

import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.Threat
import app.clairix.analysis.FollowAlertPolicy.Decision
import app.clairix.detect.Signatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recognised tracker is announced at once; unidentified co-travellers once per journey, together. */
class FollowAlertPolicyTest {

    private val t0 = 1_700_000_000_000L

    private fun follower(key: String, name: String, threat: Threat, displacementM: Double = 420.0, sightings: Int = 40) = Detection(
        key = key, address = "addr-$key", name = name, rssi = -60,
        tracker = if (threat == Threat.CRITICAL) Signatures.AIRTAG else null,
        threat = threat, score = 80, firstSeen = t0, lastSeen = t0 + 10 * 60_000L, sightings = sightings,
        persistent = true, following = true, confidence = FollowConfidence.CONFIRMED,
        displacementM = displacementM, places = 3, rotations = 0, addresses = 1, approxMetres = null
    )

    @Test
    fun aRecognisedTrackerIsAnnouncedAtOnceAndOnlyOnce() {
        val policy = FollowAlertPolicy()
        val tag = follower("tag", "Apple AirTag", Threat.CRITICAL)
        assertEquals(Decision.ANNOUNCE, policy.onVerdict(tag))
        assertEquals(Decision.NOTHING, policy.onVerdict(tag))
        assertNull(policy.onJourneyEnd(t0 + 20 * 60_000L))
    }

    @Test
    fun unidentifiedCoTravellersAreHeldAndSummedUpWhenTheJourneyEnds() {
        val policy = FollowAlertPolicy()
        assertEquals(Decision.HOLD, policy.onVerdict(follower("a", "Pixel 8", Threat.HIGH)))
        assertEquals(Decision.HOLD, policy.onVerdict(follower("b", "Galaxy Buds", Threat.HIGH, displacementM = 650.0)))
        assertEquals(Decision.NOTHING, policy.onVerdict(follower("a", "Pixel 8", Threat.HIGH)))
        val end = t0 + 25 * 60_000L
        val summary = policy.onJourneyEnd(end)!!
        assertEquals(2, summary.count)
        assertEquals("2 devices travelled with you", summary.title)
        assertTrue(summary.detail, "Pixel 8" in summary.detail && "Galaxy Buds" in summary.detail)
        assertTrue(summary.detail, "650 m" in summary.detail)
        assertTrue(summary.detail, "None is recognised as a tracker away from its owner" in summary.detail)
        // Summed up once: neither the next journey end nor a repeat verdict raises them again.
        assertNull(policy.onJourneyEnd(end + 60_000L))
        assertEquals(Decision.NOTHING, policy.onVerdict(follower("a", "Pixel 8", Threat.HIGH)))
    }

    @Test
    fun aSingleCoTravellerGetsItsOwnWordingAndTheLatestFigures() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("a", "Unknown device", Threat.HIGH, displacementM = 380.0))
        val summary = policy.onJourneyEnd(t0 + 30 * 60_000L) {
            listOf(follower("a", "Unknown device", Threat.HIGH, displacementM = 910.0, sightings = 75))
        }!!
        assertEquals("A device travelled with you", summary.title)
        assertTrue(summary.detail, summary.detail.startsWith("Unknown device was heard at places 910 m apart while you travelled, 75 times."))
        assertTrue(summary.detail, "It is not recognised as a tracker away from its owner" in summary.detail)
    }

    @Test
    fun aHeldDeviceThatSeparatesIsAnnouncedAtOnceAndLeavesTheSummary() {
        val policy = FollowAlertPolicy()
        assertEquals(Decision.HOLD, policy.onVerdict(follower("tag", "Apple AirTag (owner nearby)", Threat.HIGH)))
        assertEquals(Decision.HOLD, policy.onVerdict(follower("buds", "Galaxy Buds", Threat.HIGH)))
        // The owner's phone drops away: the same key is now a recognised tracker.
        assertEquals(Decision.ANNOUNCE, policy.onVerdict(follower("tag", "Apple AirTag", Threat.CRITICAL)))
        val summary = policy.onJourneyEnd(t0 + 20 * 60_000L)!!
        assertEquals(listOf("buds"), summary.keys)
    }

    @Test
    fun aRiderSummedUpEarlierIsStillAnnouncedWhenItSeparates() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("tag", "Apple AirTag (owner nearby)", Threat.HIGH))
        assertEquals(listOf("tag"), policy.onJourneyEnd(t0 + 20 * 60_000L)!!.keys)
        assertEquals(Decision.ANNOUNCE, policy.onVerdict(follower("tag", "Apple AirTag", Threat.CRITICAL)))
    }

    @Test
    fun aRiderTheOwnerHasMarkedAsTheirsIsDropped() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("watch", "Galaxy Watch", Threat.HIGH))
        policy.onVerdict(follower("box", "Unknown device", Threat.HIGH))
        val summary = policy.onJourneyEnd(t0 + 20 * 60_000L, trusted = setOf("addr-watch"))!!
        assertEquals(listOf("box"), summary.keys)
        assertNull(policy.onJourneyEnd(t0 + 40 * 60_000L, trusted = setOf("addr-watch")))
    }

    @Test
    fun theTrackerIsNotAskedWhenNothingIsHeld() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("tag", "Apple AirTag", Threat.CRITICAL))
        var asked = false
        assertNull(policy.onJourneyEnd(t0) { asked = true; emptyList() })
        assertFalse(asked)
    }

    @Test
    fun clearForgetsEverything() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("tag", "Apple AirTag", Threat.CRITICAL))
        policy.onVerdict(follower("a", "Pixel 8", Threat.HIGH))
        policy.clear()
        assertNull(policy.onJourneyEnd(t0))
        assertEquals(Decision.ANNOUNCE, policy.onVerdict(follower("tag", "Apple AirTag", Threat.CRITICAL)))
    }

    private fun tire(key: String, wheel: Int) = follower(key, "TPMS$wheel", Threat.HIGH).copy(
        tracker = Signatures.TIRE_SENSOR,
        tire = app.clairix.detect.TirePressure.Reading(app.clairix.detect.TirePressure.Format.VALVE_CAP, "8$wheel:EA:CA:00:00:0$wheel", wheel = wheel)
    )

    @Test
    fun tireSensorsAreHeldAndSummedUpAsPossiblyTheOwnersCar() {
        val policy = FollowAlertPolicy()
        for (w in 1..4) assertEquals(Decision.HOLD, policy.onVerdict(tire("t$w", w)))
        val summary = policy.onJourneyEnd(t0 + 25 * 60_000L)!!
        assertEquals(4, summary.count)
        assertEquals("4 tire pressure sensors travelled with you", summary.title)
        assertTrue(summary.detail, "mark your car's tires as yours" in summary.detail)
        // A tire sensor is on a wheel: no search of the car is suggested.
        assertFalse(summary.detail, "OBD-II" in summary.detail)
    }

    @Test
    fun aTireSensorAmongOtherRidersIsNamedAsOne() {
        val policy = FollowAlertPolicy()
        policy.onVerdict(follower("a", "Pixel 8", Threat.HIGH))
        policy.onVerdict(tire("t1", 1))
        val summary = policy.onJourneyEnd(t0 + 25 * 60_000L)!!
        assertEquals("2 devices travelled with you", summary.title)
        assertTrue(summary.detail, "OBD-II" in summary.detail)
        assertTrue(summary.detail, "One of them is a tire pressure sensor" in summary.detail)
    }
}
