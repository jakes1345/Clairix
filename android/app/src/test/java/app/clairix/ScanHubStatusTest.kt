package app.clairix

import app.clairix.analysis.CameraFindMethod
import app.clairix.analysis.CameraFinding
import app.clairix.analysis.ScannerHealth
import app.clairix.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Scan hub's status lines and the tab layout per build. */
class ScanHubStatusTest {

    private fun detection(address: String, following: Boolean, threat: Threat = Threat.CRITICAL) = Detection(
        key = address, address = address, name = "Tracker", rssi = -60, tracker = null,
        threat = if (following) threat else Threat.LOW, score = 0,
        firstSeen = 0L, lastSeen = 0L, sightings = 3, persistent = following, following = following,
        confidence = if (following) FollowConfidence.CONFIRMED else FollowConfidence.NOT_MOVED_ENOUGH,
        displacementM = 0.0, places = 1, rotations = 0, addresses = 1, approxMetres = null
    )

    @Test
    fun trackersCountNearbyDevicesWhenNothingFollows() {
        val s = trackerStatus(ScanStatus(scanning = true, nearbyCount = 3), listOf(detection("A", false)), emptySet())
        assertEquals("3 devices nearby, none following", s.text)
        assertEquals(StatusTone.CLEAR, s.tone)
    }

    @Test
    fun aFollowingTrackerAlertsUnlessTrusted() {
        val following = listOf(detection("A", true))
        val s = trackerStatus(ScanStatus(scanning = true, nearbyCount = 5), following, emptySet())
        assertEquals("1 tracker following you", s.text)
        assertTrue(s.alert)
        val trusted = trackerStatus(ScanStatus(scanning = true, nearbyCount = 1), following, setOf("A"))
        assertEquals("1 device nearby, none following", trusted.text)
        assertFalse(trusted.alert)
    }

    @Test
    fun anUnidentifiedDeviceMovingWithYouIsACautionNotATracker() {
        val s = trackerStatus(ScanStatus(scanning = true, nearbyCount = 5), listOf(detection("B", true, Threat.HIGH)), emptySet())
        assertEquals("1 device travelling with you", s.text)
        assertEquals(StatusTone.CAUTION, s.tone)
    }

    @Test
    fun trackersReportTheScannerOffAndBluetoothOff() {
        assertEquals("Scanner off", trackerStatus(ScanStatus(scanning = false), emptyList(), emptySet()).text)
        assertEquals(
            "Bluetooth is off",
            trackerStatus(ScanStatus(scanning = true, bluetoothOn = false), emptyList(), emptySet()).text
        )
    }

    @Test
    fun aScannerThatCannotHearIsACautionNotAQuietAllClear() {
        val deaf = ScanStatus(scanning = true, nearbyCount = 0, error = ScannerHealth.LOCATION_OFF)
        val tile = trackerStatus(deaf, emptyList(), emptySet())
        assertEquals("Scanner cannot hear", tile.text)
        assertEquals(StatusTone.CAUTION, tile.tone)
        val hero = hubVerdict(scanning = true, statuses = listOf(tile), error = deaf.error)
        assertEquals("Scanner cannot hear", hero.headline)
        assertEquals(ScannerHealth.LOCATION_OFF, hero.body)
        assertEquals(Tone.Caution, hero.tone)
        // Bluetooth off is named on the tile, where it used to read as a neutral "Scanner off".
        val btOff = trackerStatus(ScanStatus(scanning = false, bluetoothOn = false, error = "Bluetooth is off"), emptyList(), emptySet())
        assertEquals("Bluetooth is off", btOff.text)
        assertEquals(StatusTone.CAUTION, btOff.tone)
        // A start Android refused is a scanner that cannot hear, not one the owner turned off.
        val refused = ScanStatus(scanning = false, error = "Android blocked starting the scanner in the background — open Clairix and tap Start")
        assertEquals("Scanner cannot hear", trackerStatus(refused, emptyList(), emptySet()).text)
        // Bluetooth off after the owner stopped the scanner is simply off.
        assertEquals("Scanner off", trackerStatus(ScanStatus(scanning = false, bluetoothOn = false), emptyList(), emptySet()).text)
    }

    @Test
    fun aConfirmedFollowerOutranksADeafScannerAndTheHeroStillSaysSo() {
        val alert = CategoryStatus("1 tracker following you", StatusTone.ALERT)
        val hero = hubVerdict(scanning = true, statuses = listOf(alert), error = ScannerHealth.QUIET)
        assertEquals(Tone.Danger, hero.tone)
        assertEquals("1 thing needs a look", hero.headline)
        assertTrue(hero.body, hero.body.contains(ScannerHealth.QUIET))
    }

    @Test
    fun aScannerStoppedByTheSystemSaysWhenAndLeadsTheHub() {
        val at = 1_700_000_000_000L
        val status = ScanStatus(scanning = false, stoppedBySystem = true, stoppedAt = at)
        val tile = trackerStatus(status, emptyList(), emptySet())
        assertEquals("Stopped by the system at ${ScannerHealth.clock(at)}", tile.text)
        assertEquals(StatusTone.CAUTION, tile.tone)
        // The tile is itself a caution; the hero names the stop rather than counting it.
        val hero = hubVerdict(scanning = false, statuses = listOf(tile), stoppedBySystemAt = at)
        assertEquals("Scanner stopped", hero.headline)
        assertEquals(ScannerHealth.stoppedBySystemLine(at), hero.body)
        assertEquals(Tone.Caution, hero.tone)
        // Once it is running again the flag means nothing.
        assertEquals("All clear", hubVerdict(scanning = true, statuses = emptyList(), stoppedBySystemAt = at).headline)
    }

    @Test
    fun theHubWithoutAScannerProblemReadsAsBefore() {
        val clear = CategoryStatus("No issues found", StatusTone.CLEAR)
        assertEquals("All clear", hubVerdict(scanning = true, statuses = listOf(clear)).headline)
        assertEquals("Standing by", hubVerdict(scanning = false, statuses = listOf(clear)).headline)
        val caution = CategoryStatus("1 item to review", StatusTone.CAUTION)
        val look = hubVerdict(scanning = true, statuses = listOf(caution))
        assertEquals("1 thing needs a look", look.headline)
        assertEquals(Tone.Caution, look.tone)
        val alert = CategoryStatus("1 tracker following you", StatusTone.ALERT)
        assertEquals("2 things need a look", hubVerdict(scanning = true, statuses = listOf(caution, alert)).headline)
    }

    @Test
    fun cellStatusOnlyClaimsCleanWhenAnalysingAMatureBaseline() {
        assertEquals("No tower reading", cellStatus(CellStatus(available = false)).text)
        assertEquals("Scanner off, towers not checked", cellStatus(CellStatus(available = true, analysing = false)).text)
        assertEquals("Learning your usual towers", cellStatus(CellStatus(available = true, analysing = true)).text)
        val clean = cellStatus(CellStatus(available = true, analysing = true, mature = true))
        assertEquals("No suspicious towers", clean.text)
        assertEquals(StatusTone.CLEAR, clean.tone)
        assertTrue(cellStatus(CellStatus(available = true, analysing = true, level = Threat.HIGH)).alert)
        assertEquals(StatusTone.CAUTION, cellStatus(CellStatus(available = true, analysing = true, level = Threat.MEDIUM)).tone)
    }

    @Test
    fun wifiSeparatesRiskyNetworksFromOnesToReview() {
        val risky = WifiAnomaly("Free", "aa:bb", -50, "known_catcher_ssid", Threat.HIGH, 0L)
        val minor = WifiAnomaly("Cafe", "cc:dd", -70, "carrier_open_network", Threat.LOW, 0L)
        assertEquals("1 risky network nearby", wifiStatus(listOf(risky, minor), WifiStatus(), scanning = true).text)
        assertEquals("1 network to review", wifiStatus(listOf(minor), WifiStatus(), scanning = true).text)
        assertEquals("No risky networks found", wifiStatus(emptyList(), WifiStatus(scannedTs = 1L), scanning = false).text)
        assertEquals("Not checked yet", wifiStatus(emptyList(), WifiStatus(), scanning = false).text)
    }

    @Test
    fun nfcCountsSuspiciousTags() {
        fun tag(uid: String, suspicious: Boolean) = NfcTag(uid, emptyList(), "NfcA", null, suspicious, "", 0L)
        assertEquals("No tags read yet", nfcStatus(emptyList()).text)
        assertEquals("2 tags read, none suspicious", nfcStatus(listOf(tag("1", false), tag("2", false))).text)
        val bad = nfcStatus(listOf(tag("1", true), tag("2", false)))
        assertEquals("1 suspicious tag", bad.text)
        assertTrue(bad.alert)
    }

    @Test
    fun cameraFindingsSplitStrongFromPossible() {
        fun finding(c: Float) = CameraFinding("id$c", 0L, CameraFindMethod.LENS_GLINT, c, "centre", "", null, null)
        assertEquals("No findings", cameraStatus(emptyList()).text)
        assertEquals("2 possible findings to check", cameraStatus(listOf(finding(0.3f), finding(0.5f))).text)
        assertEquals("1 strong finding", cameraStatus(listOf(finding(0.9f), finding(0.3f))).text)
    }

    @Test
    fun phoneCheckNeverClaimsCleanBeforeAScan() {
        assertEquals("Not checked yet", phoneStatus(PhoneHealth()).text)
        val clean = PhoneHealth(findingsScannedTs = 1L, facts = listOf(HealthFact("lock", "Lock", "Screen lock", "PIN")))
        assertEquals("No issues found", phoneStatus(clean).text)
        val bad = PhoneHealth(
            findingsScannedTs = 1L,
            findings = listOf(PhoneHealthFinding("a11y", Severity.HIGH, "Apps", "Accessibility service", "Reads the screen"))
        )
        assertEquals("1 issue to fix", phoneStatus(bad).text)
        assertTrue(phoneStatus(bad).alert)
        val mic = PhoneHealth(findingsScannedTs = 1L, activeRecordings = 1)
        assertEquals("Camera or microphone in use", phoneStatus(mic).text)
    }

    @Test
    fun commsBuildHasOnlyTheCommsTab() {
        assertEquals(listOf(HomeTab.COMMS), homeTabsFor(fullAccess = false))
        assertEquals(listOf(HomeTab.SCAN, HomeTab.COMMS, HomeTab.DRIVE, HomeTab.ACTIVITY), homeTabsFor(fullAccess = true))
    }

    @Test
    fun savedNamesThatNoLongerExistReadAsNone() {
        assertEquals(ScanCategory.NFC, enumByName<ScanCategory>("NFC"))
        assertNull(enumByName<ScanCategory>("TOOLS"))
        assertNull(enumByName<ScanCategory>(null))
    }
}
