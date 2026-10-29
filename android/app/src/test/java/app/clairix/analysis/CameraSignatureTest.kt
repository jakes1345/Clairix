package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hidden-camera sweep's name and web-page rules, against the things they used to mistake for cameras. */
class CameraSignatureTest {

    private fun ap(ssid: String) = HiddenCameraScanner.AP_RULES.firstOrNull { it.regex.matches(ssid.lowercase()) }

    private fun http(page: String) = HiddenCameraScanner.HTTP_SIGNATURES
        .filter { it.regex.containsMatchIn(page.lowercase()) }
        .maxByOrNull { it.confidence }

    @Test
    fun `a smart plug in setup mode is a weak hint, not a camera`() {
        assertEquals(0.3f, ap("SmartLife-1A2B")!!.confidence)
        assertEquals(0.3f, ap("TuyaSmart-77F0")!!.confidence)
    }

    @Test
    fun `routers and homes named like covert-camera prefixes are not cameras`() {
        assertNull(ap("AC1750"))
        assertNull(ap("xdhome"))
        assertNull(ap("Camden"))
    }

    @Test
    fun `a covert mini camera with its hex serial still matches`() {
        assertEquals(0.95f, ap("A9-3F9A1C")!!.confidence)
        assertEquals(0.95f, ap("SQ11_ab12cd")!!.confidence)
        assertEquals(0.85f, ap("IPC-3F9A")!!.confidence)
    }

    @Test
    fun `a router page that animates something called blink is not a Blink camera`() {
        assertNull(http("<style>@keyframes blink { 50% { opacity: 0 } }</style><title>Router login</title>"))
    }

    @Test
    fun `GoAhead and Boa are only hints, since routers run them too`() {
        assertEquals(0.4f, http("HTTP/1.0 200 OK\r\nServer: GoAhead-Webs\r\n\r\n<title>Login</title>")!!.confidence)
        assertEquals(0.4f, http("Server: Boa/0.94.14rc21")!!.confidence)
    }

    @Test
    fun `real camera pages are still recognised`() {
        assertEquals(0.92f, http("<title>Hikvision Web</title>")!!.confidence)
        assertEquals(0.88f, http("<title>Blink Sync Module</title>")!!.confidence)
        assertEquals(0.88f, http("<title>Wyze</title>")!!.confidence)
    }

    @Test
    fun `Wi-Fi distances use an access point's loudness, not a beacon's`() {
        // -48 dBm from an access point is a few metres, not under one.
        val m = HiddenCameraScanner.approxMetresWifi(-48)
        assert(m in 1.5..3.5) { "got $m" }
        assertEquals(1.0, HiddenCameraScanner.approxMetresWifi(-40), 0.01)
    }
}
