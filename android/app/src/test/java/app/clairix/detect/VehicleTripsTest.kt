package app.clairix.detect

import android.bluetooth.BluetoothClass
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.TrackerType
import app.clairix.security.BluetoothExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        return VehicleTrips(Store(File(dir, "vehicle_trips.json"), cipher = TestStoreCipher))
    }

    /** The car's own hands-free unit. */
    private val carKit = "00:1A:2B:3C:4D:5E"

    /**
     * One drive from [from], with [devices] each heard four times over three minutes;
     * with [ownCar], the car's hands-free connects as it sets off. Returns every
     * finding raised, mid-trip and at the end.
     */
    private fun drive(
        vt: VehicleTrips, from: Pair<Double, Double>, devices: List<String>,
        ownCar: Boolean = false, tracker: TrackerType? = null, fingerprint: String? = null
    ): List<VehicleTrips.Finding> {
        val out = ArrayList<VehicleTrips.Finding>()
        vt.beginTrip(now, from.first, from.second)
        if (ownCar) vt.noteAccessory(carKit, "Civic", car = true)
        repeat(4) {
            for (d in devices) vt.sight(d, d, "Tag $d", tracker = tracker, fingerprint = fingerprint, now = now)?.let(out::add)
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
        assertTrue(drive(vt, home, listOf(tracker), ownCar = true).isEmpty())
        assertTrue(drive(vt, work, listOf(tracker), ownCar = true).isEmpty())
        val third = drive(vt, home, listOf(tracker), ownCar = true)
        assertEquals(third.toString(), 1, third.size)
        val finding = third.single()
        assertEquals(Severity.CRITICAL, finding.severity)
        assertEquals("vehicle@$tracker", finding.dedupeKey)
        assertTrue(finding.title, finding.title.contains("in your vehicle on 3 trips"))
        assertTrue(finding.detail.contains("OBD-II"))
        assertFalse(finding.coTraveller)
    }

    @Test
    fun withNothingTyingTheJourneysToACarItIsReportedAsJourneysNotTheVehicle() {
        // A bus commuter: no car kit, no Drive mode. The bus's own unit is on every ride.
        val vt = trips()
        drive(vt, home, listOf(tracker))
        drive(vt, work, listOf(tracker))
        val finding = drive(vt, home, listOf(tracker)).single()
        assertEquals(Severity.HIGH, finding.severity)
        assertEquals("${VehicleTrips.JOURNEYS_PREFIX}$tracker", finding.dedupeKey)
        assertTrue(finding.id.startsWith(VehicleTrips.JOURNEYS_PREFIX))
        assertTrue(VehicleTrips.coTravellerEvent("${finding.dedupeKey}@$now"))
        assertTrue(finding.coTraveller)
        assertTrue(finding.title, finding.title.contains("has been on 3 of your journeys"))
        assertFalse(finding.title.contains("vehicle"))
        assertTrue(finding.detail, finding.detail.contains("bus or train"))
    }

    @Test
    fun driveModeIsAnOwnCarSignal() {
        val vt = trips()
        drive(vt, home, listOf(tracker))
        drive(vt, work, listOf(tracker))
        vt.beginTrip(now, home.first, home.second)
        var finding: VehicleTrips.Finding? = null
        repeat(4) {
            finding = finding ?: vt.sight(tracker, tracker, "Tag", null, null, now, driveMode = true)
            now += 60_000L
        }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertEquals("vehicle@$tracker", finding!!.dedupeKey)
    }

    @Test
    fun aConnectedAccessoryThatIsNotACarKitIsNoOwnCarSignal() {
        val vt = trips()
        drive(vt, home, listOf(tracker))
        drive(vt, work, listOf(tracker))
        vt.beginTrip(now, home.first, home.second)
        vt.noteAccessory("F0:E1:D2:C3:B4:A5", "Pixel Buds")
        var finding: VehicleTrips.Finding? = null
        repeat(4) {
            finding = finding ?: vt.sight(tracker, tracker, "Tag", null, null, now)
            now += 60_000L
        }
        assertTrue(finding!!.coTraveller)
    }

    @Test
    fun aSharedSignatureIsReportedAtMostDailyAndANotchLowerWithoutACar() {
        // A separated AirTag rotates, so each trip hears a different address with the
        // same type and advertisement shape — every AirTag's.
        val vt = trips()
        val airTag = Signatures.AIRTAG
        drive(vt, home, listOf("AA:00:00:00:00:01"), tracker = airTag, fingerprint = "fp")
        drive(vt, work, listOf("AA:00:00:00:00:02"), tracker = airTag, fingerprint = "fp")
        val onBus = drive(vt, home, listOf("AA:00:00:00:00:03"), tracker = airTag, fingerprint = "fp").single()
        assertEquals("An Apple AirTag has been on 3 of your journeys", onBus.title)
        assertEquals(Severity.MEDIUM, onBus.severity)
        assertEquals(VehicleTrips.SIGNATURE_DEDUPE_MS, onBus.dedupeWindowMs)
        assertTrue(onBus.coTraveller)
        val inCar = drive(vt, work, listOf("AA:00:00:00:00:04"), ownCar = true, tracker = airTag, fingerprint = "fp").single()
        assertEquals(Severity.HIGH, inCar.severity)
        assertEquals(VehicleTrips.SIGNATURE_DEDUPE_MS, inCar.dedupeWindowMs)
        assertEquals("vehicle@airtag|fp", inCar.dedupeKey)
        assertFalse(inCar.coTraveller)
    }

    @Test
    fun aCarKitIsKnownByItsBluetoothClass() {
        assertTrue(BluetoothExposure.isCarKit(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertTrue(BluetoothExposure.isCarKit(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
        assertFalse(BluetoothExposure.isCarKit(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertFalse(BluetoothExposure.isCarKit(BluetoothClass.Device.WEARABLE_WRIST_WATCH))
        assertFalse(BluetoothExposure.isCarKit(null))
    }

    @Test
    fun anAddressFindingKeepsTheShortDedupeWindow() {
        val vt = trips()
        drive(vt, home, listOf(tracker), ownCar = true)
        drive(vt, work, listOf(tracker), ownCar = true)
        assertEquals(VehicleTrips.ADDRESS_DEDUPE_MS, drive(vt, home, listOf(tracker), ownCar = true).single().dedupeWindowMs)
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
        drive(VehicleTrips(Store(file, cipher = TestStoreCipher)), home, listOf(tracker))
        drive(VehicleTrips(Store(file, cipher = TestStoreCipher)), work, listOf(tracker))
        val vt = VehicleTrips(Store(file, cipher = TestStoreCipher))
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
        val first = VehicleTrips(Store(file, cipher = TestStoreCipher))
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
        val reopened = VehicleTrips(Store(file, cipher = TestStoreCipher))
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

    private val tireSensor = "80:EA:CA:10:8A:78"

    @Test
    fun theOwnersOwnTireSensorsAreNotReportedAsPlantedInTheCar() {
        // A car with Bluetooth tire sensors takes them on every drive.
        val vt = trips()
        assertTrue(drive(vt, home, listOf(tireSensor), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "fp").isEmpty())
        assertTrue(drive(vt, work, listOf(tireSensor), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "fp").isEmpty())
        val finding = drive(vt, home, listOf(tireSensor), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "fp").single()
        assertEquals(Severity.MEDIUM, finding.severity)
        assertEquals("${VehicleTrips.TIRE_PREFIX}$tireSensor", finding.dedupeKey)
        assertTrue(finding.title, finding.title.contains("A tire pressure sensor has been on 3 of your drives"))
        assertTrue(finding.detail, finding.detail.contains("mark your car's tires as yours"))
        assertFalse(finding.detail.contains("OBD-II"))
        assertFalse(finding.coTraveller)
    }

    @Test
    fun onceTheOwnersTiresAreMarkedAnotherTireSensorOnThreeDrivesIsAnotherVehicle() {
        val vt = trips()
        val others = "80:EA:CA:77:77:77"
        fun drive(from: Pair<Double, Double>): VehicleTrips.Finding? {
            var found: VehicleTrips.Finding? = null
            vt.beginTrip(now, from.first, from.second)
            vt.noteAccessory(carKit, "Civic", car = true)
            repeat(4) {
                found = found ?: vt.sight(others, others, "TPMS1", Signatures.TIRE_SENSOR, "fp", now, ownTiresMarked = true)
                now += 60_000L
            }
            now += 20 * 60_000L
            vt.endTrip(now)
            now += 8 * 3600_000L
            return found
        }
        assertNull(drive(home))
        assertNull(drive(work))
        val finding = drive(home)!!
        assertEquals(Severity.HIGH, finding.severity)
        assertTrue(finding.title, finding.title.startsWith("Another vehicle's tire sensor has been near you on 3 drives"))
    }

    @Test
    fun tireSensorsOfOneKitDoNotAddUpByTheirSharedAdvertisementShape() {
        // Three different cars with the same kit on three drives: three addresses, one shape.
        val vt = trips()
        drive(vt, home, listOf("80:EA:CA:00:00:01"), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "kit")
        drive(vt, work, listOf("80:EA:CA:00:00:02"), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "kit")
        assertTrue(drive(vt, home, listOf("80:EA:CA:00:00:03"), ownCar = true, tracker = Signatures.TIRE_SENSOR, fingerprint = "kit").isEmpty())
    }
}
