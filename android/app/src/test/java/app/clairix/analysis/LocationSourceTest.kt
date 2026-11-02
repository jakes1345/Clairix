package app.clairix.analysis

import android.location.LocationManager
import android.location.LocationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where position fixes come from: Google Play services only where it is installed and
 * answering, Android's own location service otherwise, the satellites alone when the
 * owner asks; the scanner's pace the same from every source; and the screens told why
 * a fix or the GPS comparison is missing instead of a bare "No GPS fix".
 */
class LocationSourceTest {

    private val everything = LocationPolicy.Platform(playServices = true, fusedProvider = true, gpsProvider = true, networkProvider = true)
    private val deGoogled = everything.copy(playServices = false)

    @Test
    fun googlePlayServicesServesWhereItIsInstalledAndAnswering() {
        assertEquals(LocationSource.GOOGLE, LocationPolicy.choose(everything, satelliteOnly = false, googleFailed = false))
        // Even on a phone whose own providers are thin: Google's client is not one of them.
        val thin = LocationPolicy.Platform(playServices = true, fusedProvider = false, gpsProvider = false, networkProvider = false)
        assertEquals(LocationSource.GOOGLE, LocationPolicy.choose(thin, satelliteOnly = false, googleFailed = false))
    }

    @Test
    fun aPhoneWithoutPlayServicesUsesItsOwnLocationService() {
        assertEquals(LocationSource.PHONE, LocationPolicy.choose(deGoogled, satelliteOnly = false, googleFailed = false))
        // Its fused provider, asked by name, for precise and coarse fixes alike.
        for (high in listOf(true, false)) {
            assertEquals(listOf(LocationManager.FUSED_PROVIDER), LocationPolicy.providers(LocationSource.PHONE, deGoogled, high))
        }
        assertEquals(emptyList<String>(), LocationPolicy.providers(LocationSource.GOOGLE, everything, highAccuracy = true))
    }

    @Test
    fun playServicesThatFailARequestAreLeftForThePhonesOwnService() {
        assertEquals(LocationSource.PHONE_FALLBACK, LocationPolicy.choose(everything, satelliteOnly = false, googleFailed = true))
        assertEquals(
            listOf(LocationManager.FUSED_PROVIDER),
            LocationPolicy.providers(LocationSource.PHONE_FALLBACK, everything, highAccuracy = true)
        )
        // Failed, and nothing of the phone's own to fall back to.
        val bare = LocationPolicy.Platform(playServices = true, fusedProvider = false, gpsProvider = false, networkProvider = false)
        assertEquals(LocationSource.NONE, LocationPolicy.choose(bare, satelliteOnly = false, googleFailed = true))
    }

    @Test
    fun satelliteOnlyAsksTheReceiverAloneWhateverElseThePhoneHas() {
        for (play in listOf(true, false)) {
            for (failed in listOf(true, false)) {
                val p = everything.copy(playServices = play)
                assertEquals(LocationSource.SATELLITE, LocationPolicy.choose(p, satelliteOnly = true, googleFailed = failed))
            }
        }
        for (high in listOf(true, false)) {
            assertEquals(listOf(LocationManager.GPS_PROVIDER), LocationPolicy.providers(LocationSource.SATELLITE, everything, high))
        }
        assertEquals(listOf(LocationManager.GPS_PROVIDER), LocationPolicy.lastKnownProviders(LocationSource.SATELLITE, everything))
        // A phone with no satellite receiver has nothing to give.
        val noGps = everything.copy(gpsProvider = false)
        assertEquals(LocationSource.SATELLITE_UNAVAILABLE, LocationPolicy.choose(noGps, satelliteOnly = true, googleFailed = false))
        assertEquals(emptyList<String>(), LocationPolicy.providers(LocationSource.SATELLITE_UNAVAILABLE, noGps, highAccuracy = true))
    }

    @Test
    fun withoutAFusedProviderSatellitesAndWifiCellStandInForEachOther() {
        val both = deGoogled.copy(fusedProvider = false)
        assertEquals(listOf(LocationManager.GPS_PROVIDER), LocationPolicy.providers(LocationSource.PHONE, both, highAccuracy = true))
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), LocationPolicy.providers(LocationSource.PHONE, both, highAccuracy = false))
        // The network provider missing: the satellites serve the coarse request too.
        val noNetwork = both.copy(networkProvider = false)
        assertEquals(listOf(LocationManager.GPS_PROVIDER), LocationPolicy.providers(LocationSource.PHONE, noNetwork, highAccuracy = false))
        assertEquals(listOf(LocationManager.GPS_PROVIDER), LocationPolicy.lastKnownProviders(LocationSource.PHONE, noNetwork))
        // No satellite receiver: Wi-Fi/cell serves the precise one.
        val noGps = both.copy(gpsProvider = false)
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), LocationPolicy.providers(LocationSource.PHONE, noGps, highAccuracy = true))
        // Nothing at all.
        val nothing = noGps.copy(networkProvider = false)
        assertEquals(LocationSource.NONE, LocationPolicy.choose(nothing, satelliteOnly = false, googleFailed = false))
        assertEquals(emptyList<String>(), LocationPolicy.providers(LocationSource.NONE, nothing, highAccuracy = true))
        assertEquals(emptyList<String>(), LocationPolicy.lastKnownProviders(LocationSource.NONE, nothing))
    }

    @Test
    fun theScannersPaceIsTheSameFromEverySource() {
        val moving = LocationPolicy.scannerFixes(MotionPolicy.Pace.MOVING, settledFixMs = 180_000L)
        assertEquals(FixRequest(highAccuracy = true, intervalMs = 15_000L, minIntervalMs = 10_000L, minDistanceM = 25f), moving)
        val still = LocationPolicy.scannerFixes(MotionPolicy.Pace.STILL, settledFixMs = 180_000L)
        assertEquals(FixRequest(highAccuracy = false, intervalMs = 180_000L, minIntervalMs = 60_000L, minDistanceM = 0f), still)
        // Android's own fused provider: precise is precise; coarse is Wi-Fi/cell only, as
        // Google's balanced request is, wherever a Wi-Fi/cell provider can answer it.
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, LocationPolicy.quality(moving.highAccuracy, networkProvider = true))
        assertEquals(LocationRequest.QUALITY_LOW_POWER, LocationPolicy.quality(still.highAccuracy, networkProvider = true))
        // With none, balanced, so the satellites still give an occasional fix.
        assertEquals(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, LocationPolicy.quality(still.highAccuracy, networkProvider = false))
        assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, LocationPolicy.quality(moving.highAccuracy, networkProvider = false))
    }

    @Test
    fun theWifiCellComparisonRunsOnlyWhereItCanAndIsAllowed() {
        assertEquals(NetworkCheck.AVAILABLE, LocationPolicy.networkCheck(networkProvider = true, crossCheck = true, satelliteOnly = false))
        assertEquals(NetworkCheck.NO_PROVIDER, LocationPolicy.networkCheck(networkProvider = false, crossCheck = true, satelliteOnly = false))
        assertEquals(NetworkCheck.OFF, LocationPolicy.networkCheck(networkProvider = true, crossCheck = false, satelliteOnly = false))
        // Satellite-only rules it out whatever the cross-check says: asking for it would
        // send the networks around the phone to a location service.
        for (provider in listOf(true, false)) {
            for (cross in listOf(true, false)) {
                assertEquals(
                    NetworkCheck.SATELLITE_ONLY,
                    LocationPolicy.networkCheck(networkProvider = provider, crossCheck = cross, satelliteOnly = true)
                )
            }
        }
    }

    @Test
    fun theScreensSayWhereFixesComeFromAndWhyTheComparisonCannotRun() {
        assertEquals("Using the phone's own location (no Google Play services).", PositionText.source(LocationSource.PHONE))
        assertNull(PositionText.source(LocationSource.GOOGLE))
        assertNull(PositionText.source(null))
        for (s in LocationSource.entries) {
            if (s != LocationSource.GOOGLE) assertTrue("$s says nothing", !PositionText.source(s).isNullOrBlank())
        }
        assertTrue(PositionText.sourceIsProblem(LocationSource.NONE))
        assertTrue(PositionText.sourceIsProblem(LocationSource.SATELLITE_UNAVAILABLE))
        assertFalse(PositionText.sourceIsProblem(LocationSource.PHONE))
        assertFalse(PositionText.sourceIsProblem(LocationSource.SATELLITE))

        assertEquals(
            "This phone has no Wi-Fi/cell position source; the comparison check cannot run here.",
            PositionText.network(NetworkCheck.NO_PROVIDER)
        )
        assertNull(PositionText.network(NetworkCheck.AVAILABLE))
        assertTrue(PositionText.network(NetworkCheck.OFF)!!.contains("off in Settings"))
        assertTrue(PositionText.network(NetworkCheck.SATELLITE_ONLY)!!.contains("Satellite-only"))
        assertTrue(PositionText.networkIsProblem(NetworkCheck.NO_PROVIDER))
        assertFalse(PositionText.networkIsProblem(NetworkCheck.OFF))

        // No fix: positions being faked is said first, whatever the source; never "No GPS fix".
        assertEquals(PositionText.FAKED, PositionText.noFix(LocationSource.GOOGLE, faking = true))
        assertEquals(PositionText.FAKED, PositionText.noFix(LocationSource.NONE, faking = true))
        assertTrue(PositionText.noFix(LocationSource.SATELLITE, faking = false).contains("indoors"))
        for (s in listOf(null) + LocationSource.entries) {
            assertFalse(PositionText.noFix(s, faking = false).contains("GPS"))
        }
    }

    @Test
    fun theGpsChecksComparisonSaysWhyItCannotRunInsteadOfNoData() {
        val g = GnssIntegrity()
        val t0 = 1_700_000_000_000L
        val before = g.assess(t0).checks.single { it.id == "network" }
        assertEquals(CheckState.NO_DATA, before.state)
        assertEquals("No Wi-Fi or cell position to compare with in the last five minutes.", before.detail)

        g.networkUnavailable = PositionText.network(NetworkCheck.NO_PROVIDER)
        val after = g.assess(t0).checks.single { it.id == "network" }
        assertEquals(CheckState.NO_DATA, after.state)
        assertEquals("This phone has no Wi-Fi/cell position source; the comparison check cannot run here.", after.detail)
        // It is not something learned: clearing what was learned keeps it.
        g.clear()
        assertEquals(after.detail, g.assess(t0).checks.single { it.id == "network" }.detail)
    }
}
