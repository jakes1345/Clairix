package app.clairix.nav

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * "Share traffic data" off: the box around the route is never even worked out for
 * HERE/TomTom, let alone sent, and incidents from them already shown are dropped
 * (gap audit item 8). On, the fetch goes as far as asking where the route is.
 */
@RunWith(RobolectricTestRunner::class)
class ShareTrafficTest {

    private fun client() = TrafficClient(RuntimeEnvironment.getApplication(), "north-america-us-ohio", TripPseudonym())

    @Test
    fun `off, the provider fetch never asks where the route is`() = runBlocking {
        val traffic = client()
        var asked = 0
        traffic.focus = { asked++; listOf(LatLon(40.0, -83.0), LatLon(40.1, -83.1)) }
        traffic.providersAllowed = false
        assertFalse(traffic.refreshProviders(0))
        assertFalse(traffic.refreshProviders(2 * 60_000L))
        assertEquals(0, asked)
        assertTrue(traffic.providerIncidents.value.isEmpty())
    }

    @Test
    fun `on, the provider fetch asks for the route`() = runBlocking {
        val traffic = client()
        var asked = 0
        // No route yet: the fetch stops there, before any request.
        traffic.focus = { asked++; null }
        assertTrue(traffic.providersAllowed)
        assertFalse(traffic.refreshProviders(0))
        assertEquals(1, asked)
    }
}
