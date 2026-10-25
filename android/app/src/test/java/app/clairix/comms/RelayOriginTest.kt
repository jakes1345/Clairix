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
}
