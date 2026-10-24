package app.clairix.detect

import app.clairix.Severity
import app.clairix.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Trip counting: a commuter's tracker is found; a device parked at home is not. */
class VehicleTripsTest {

    private val home = 40.0000 to -75.0000
    /** About 11 km north of home. */
    private val work = 40.1000 to -75.0000

    private var now = 1_700_000_000_000L
    private val tracker = "C0:FF:EE:00:00:01"

    private fun trips(): VehicleTrips {
        val dir = Files.createTempDirectory("trips").toFile().apply { deleteOnExit() }
        return VehicleTrips(Store(File(dir, "vehicle_trips.json")))
    }

    /**
     * One drive from [from], with [devices] each heard four times over three minutes.
     * Returns every finding raised, mid-trip and at the end.
     */
    private fun drive(vt: VehicleTrips, from: Pair<Double, Double>, devices: List<String>): List<VehicleTrips.Finding> {
        val out = ArrayList<VehicleTrips.Finding>()
        vt.beginTrip(now, from.first, from.second)
        repeat(4) {
            for (d in devices) vt.sight(d, d, "Tag $d", tracker = null, fingerprint = null, now = now)?.let(out::add)
            now += 60_000L
        }
        now += 20 * 60_000L
        out += vt.endTrip(now)
        now += 8 * 3600_000L
        return out
    }

    @Test
    fun aCommutersTrackerIsReportedOnTheThirdTrip() {
        val vt = trips()
        assertTrue(drive(vt, home, listOf(tracker)).isEmpty())
        assertTrue(drive(vt, work, listOf(tracker)).isEmpty())
        val third = drive(vt, home, listOf(tracker))
        assertEquals(third.toString(), 1, third.size)
        assertEquals(Severity.CRITICAL, third.single().severity)
        assertEquals("vehicle@$tracker", third.single().dedupeKey)
    }

    @Test
    fun aDeviceThatOnlyQualifiesOnDrivesFromHomeIsNeverReported() {
        // Something sitting in the garage is heard for the first minutes of every
        // drive that starts at home, and on none that start anywhere else.
        val garage = "6A:4A:00:00:00:02"
        val vt = trips()
        val findings = ArrayList<VehicleTrips.Finding>()
        repeat(4) {
            findings += drive(vt, home, listOf(garage))
            findings += drive(vt, work, emptyList())
        }
        assertTrue(findings.toString(), findings.isEmpty())
    }

    @Test
    fun theCountSurvivesARestartBetweenDrives() {
        val dir = Files.createTempDirectory("trips").toFile().apply { deleteOnExit() }
        val file = File(dir, "vehicle_trips.json")
        drive(VehicleTrips(Store(file)), home, listOf(tracker))
        drive(VehicleTrips(Store(file)), work, listOf(tracker))
        val vt = VehicleTrips(Store(file))
        assertEquals(2, vt.trips().size)
        vt.beginTrip(now, home.first, home.second)
        var finding: VehicleTrips.Finding? = null
        repeat(4) {
            finding = finding ?: vt.sight(tracker, tracker, "Tag", null, null, now)
            now += 60_000L
        }
        assertNotNull(finding)
        assertNull(vt.sight(tracker, tracker, "Tag", null, null, now))
    }

    @Test
    fun accessoriesAreRememberedPerTripAndCountedAcrossThem() {
        val dir = Files.createTempDirectory("trips").toFile().apply { deleteOnExit() }
        val file = File(dir, "vehicle_trips.json")
        val car = "00:1A:2B:3C:4D:5E"
        val buds = "F0:E1:D2:C3:B4:A5"

        // Nothing is recorded outside a trip.
        val first = VehicleTrips(Store(file))
        first.noteAccessory(car, "Civic")
        first.beginTrip(now, home.first, home.second)
        first.noteAccessory(car, "Civic")
        first.noteAccessory(buds, "Buds")
        // A later name for the same address wins.
        first.noteAccessory(buds, "Pixel Buds")
        now += 20 * 60_000L
        first.endTrip(now)
        now += 3600_000L
        first.beginTrip(now, work.first, work.second)
        first.noteAccessory(car, "Civic")
        now += 20 * 60_000L
        first.endTrip(now)
        now += 3600_000L

        // A false start is discarded with its accessories.
        first.beginTrip(now, home.first, home.second)
        first.noteAccessory(buds, "Pixel Buds")
        now += 30_000L
        first.endTrip(now)

        // Survives a restart, since the trips file holds it.
        val reopened = VehicleTrips(Store(file))
        val use = reopened.accessoryUse()
        assertEquals(use.toString(), 2, use.size)
        assertEquals(car, use[0].address)
        assertEquals("Civic", use[0].name)
        assertEquals(2, use[0].trips)
        assertEquals(buds, use[1].address)
        assertEquals("Pixel Buds", use[1].name)
        assertEquals(1, use[1].trips)
        assertEquals(mapOf(car to "Civic"), reopened.trips().last().accessories)

        reopened.clear()
        assertTrue(reopened.accessoryUse().isEmpty())
    }
}
