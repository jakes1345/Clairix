package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/** The pinning trust manager refuses any key but the pinned one, after the platform has had its say. */
class RelayTlsTest {

    private fun cert(name: String): X509Certificate {
        val stream = javaClass.getResourceAsStream("/tls/$name.pem") ?: error("missing test certificate $name")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }

    private val a = cert("relay-a")
    private val b = cert("relay-b")

    /** Stands in for the platform: accepts everything, and records whether it was asked. */
    private class Accepting : X509TrustManager {
        var asked = false
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { asked = true }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class Refusing : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("untrusted")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun withoutAPinThePlatformDecidesAlone() {
        val platform = Accepting()
        PinningTrustManager(platform) { null }.checkServerTrusted(arrayOf(a), "ECDHE_ECDSA")
        assertTrue(platform.asked)
    }

    @Test
    fun thePinnedKeyPasses() {
        PinningTrustManager(Accepting()) { RelayTls.spkiHash(a) }.checkServerTrusted(arrayOf(a), "ECDHE_ECDSA")
    }

    @Test
    fun anotherKeyIsRefusedEvenWhenThePlatformTrustsIt() {
        val manager = PinningTrustManager(Accepting()) { RelayTls.spkiHash(a) }
        val e = assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(b), "ECDHE_ECDSA") }
        assertTrue(e.message!!, e.message!!.contains("not the pinned one"))
    }

    @Test
    fun aChainThePlatformRefusesNeverReachesThePin() {
        var pinAsked = false
        val manager = PinningTrustManager(Refusing()) { pinAsked = true; RelayTls.spkiHash(a) }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(a), "ECDHE_ECDSA") }
        assertFalse(pinAsked)
    }

    @Test
    fun thePinIsReadOnEveryHandshakeSoForgettingItTakesEffectAtOnce() {
        var pin: String? = RelayTls.spkiHash(a)
        val manager = PinningTrustManager(Accepting()) { pin }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(b), "ECDHE_ECDSA") }
        pin = null
        manager.checkServerTrusted(arrayOf(b), "ECDHE_ECDSA")
    }

    @Test
    fun theHashIsTheCanarysForm() {
        val expected = "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(a.publicKey.encoded))
        assertEquals(expected, RelayTls.spkiHash(a))
        assertTrue(RelayTls.spkiHash(a) != RelayTls.spkiHash(b))
    }
}
