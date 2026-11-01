package app.clairix.analysis

import app.clairix.CellStatus
import app.clairix.Detection
import app.clairix.FollowConfidence
import app.clairix.ScanStatus
import app.clairix.Threat
import app.clairix.detect.Signatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evidence report says what the threat says: only a recognised tracker is "confirmed following". */
class ReportTest {

    private val t0 = 1_700_000_000_000L

    private fun follower(key: String, name: String, threat: Threat, separatedAt: Long = 0L) = Detection(
        key = key, address = key, name = name, rssi = -60,
        tracker = if (threat == Threat.CRITICAL) Signatures.AIRTAG else null,
        threat = threat, score = 80, firstSeen = t0, lastSeen = t0 + 600_000L, sightings = 40,
        persistent = true, following = true, confidence = FollowConfidence.CONFIRMED,
        displacementM = 420.0, places = 3, rotations = 0, addresses = 1, approxMetres = null,
        separatedAt = separatedAt
    )

    @Test
    fun theStatusLineFollowsTheThreat() {
        val text = Report.toText(
            ScanStatus(scanning = true),
            listOf(
                follower("tag", "Apple AirTag", Threat.CRITICAL, separatedAt = t0 + 300_000L),
                follower("buds", "Galaxy Buds", Threat.HIGH)
            ),
            emptyList(), CellStatus(), emptyList()
        )
        assertEquals(1, Regex("CONFIRMED FOLLOWING").findAll(text).count())
        assertTrue(text, "Travelling with you (not a recognised tracker away from its owner)" in text)
        assertTrue(text, "Separated from owner since" in text)
    }
}
