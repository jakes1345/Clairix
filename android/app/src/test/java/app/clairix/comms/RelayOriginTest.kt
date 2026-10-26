package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Relay addresses are compared the way relays compare them (comms-worker federation.ts relayOrigin). */
class RelayOriginTest {

    @Test
    fun anHttpsHostIsItsOwnOriginInLowerCase() {
        assertEquals("https://relay.example.org", RelayClient.originOf("https://relay.example.org"))
        assertEquals("https://relay.example.org", RelayClient.originOf("https://Relay.Example.ORG/"))
        assertEquals("https://relay.example.org", RelayClient.originOf("  https://relay.example.org:443  "))
        assertEquals("https://relay.example.org:8443", RelayClient.originOf("https://relay.example.org:8443"))
        assertEquals("https://clairix-relay.someone.workers.dev", RelayClient.originOf("https://clairix-relay.someone.workers.dev"))
    }

    @Test
    fun anythingElseIsNotARelayAddress() {
        for (bad in listOf(
            null, "", "relay.example.org", "http://relay.example.org", "ftp://relay.example.org",
            "https://relay.example.org/v1", "https://relay.example.org/?x=1", "https://relay.example.org/#f",
            "https://user:pw@relay.example.org", "https://10.0.0.1", "https://[::1]", "https://localhost",
            "https://box.local", "https://db.internal", "https://intranet", "not a url at all"
        )) {
            assertNull(bad, RelayClient.originOf(bad))
        }
    }

    /** A relay's onion address, as relays publish it and the relay list carries it: a v3 onion origin, plain http. */
    @Test
    fun anOnionAddressIsAV3OnionOrigin() {
        val host = "abcdefghijklmnopqrstuvwxyz234567".repeat(2).take(56) + ".onion"
        assertEquals("http://$host", RelayClient.onionOrigin("http://$host"))
        assertEquals("http://$host", RelayClient.onionOrigin("  HTTP://${host.uppercase()}/ "))
        for (bad in listOf(
            null, "", host, "https://$host", "http://$host:80", "http://$host/v1", "http://$host/?x=1",
            "http://user@$host", "http://short.onion", "http://" + "a".repeat(55) + ".onion",
            "http://" + "a".repeat(56) + "1.onion", "http://relay.example.org", "http://127.0.0.1:8788"
        )) {
            assertNull(bad, RelayClient.onionOrigin(bad))
        }
    }

    /** A relay on the attached computer, as the end-to-end tests use: debug builds only, and only loopback. */
    @Test
    fun aLoopbackRelayIsAnAddressInDebugBuildsOnly() {
        val expected = if (app.clairix.BuildConfig.DEBUG) "http://127.0.0.1:8788" else null
        assertEquals(expected, RelayClient.originOf("http://127.0.0.1:8788"))
        assertEquals(app.clairix.BuildConfig.DEBUG, RelayClient.acceptableRelayUrl("http://localhost:8787"))
        for (bad in listOf("http://127.0.0.1", "http://10.0.2.2:8788", "http://relay.example.org:8788", "http://127.0.0.1:8788/v1")) {
            assertNull(bad, RelayClient.originOf(bad))
            assertEquals(bad, false, RelayClient.acceptableRelayUrl(bad))
        }
        assertEquals(true, RelayClient.acceptableRelayUrl("https://relay.example.org"))
    }
}
