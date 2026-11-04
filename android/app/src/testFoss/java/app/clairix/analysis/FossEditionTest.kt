package app.clairix.analysis

import app.clairix.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foss edition (the F-Droid build): no Google fused client, so every position comes
 * from Android's own location service, and no Google Play services class on its classpath.
 * CI checks the release classpath and the APK's classes as well (.github/workflows/android.yml).
 */
class FossEditionTest {

    @Test
    fun thereIsNoGoogleFusedClient() {
        assertNull(EditionLocation.fused)
        assertFalse(BuildConfig.PLAY_SERVICES)
    }

    @Test
    fun withNoFusedClientEveryRequestGoesToAndroidsOwnService() {
        // What Positioning.platform reports in this edition: playServices is false
        // whatever the phone has installed, because nothing here can talk to it.
        val platform = LocationPolicy.Platform(
            playServices = EditionLocation.fused != null,
            fusedProvider = true, gpsProvider = true, networkProvider = true
        )
        assertTrue(LocationPolicy.choose(platform, satelliteOnly = false, googleFailed = false) == LocationSource.PHONE)
        assertTrue(LocationPolicy.choose(platform, satelliteOnly = true, googleFailed = false) == LocationSource.SATELLITE)
    }

    @Test
    fun noGooglePlayServicesClassIsOnTheClasspath() {
        for (name in listOf(
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.android.gms.location.LocationServices",
            "com.google.android.gms.tasks.Tasks"
        )) {
            val found = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            assertFalse("$name should not be in the foss edition", found)
        }
    }
}
