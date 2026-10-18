package app.clairix.analysis

import app.clairix.analysis.TlsCanary.Probe
import app.clairix.analysis.TlsCanary.ProxyProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** The canary's verdicts, separated from the sockets that feed them. */
class TlsCanaryTest {

    private val relayKey = "sha256/relay"
    private val proxyKey = "sha256/proxy"
    private val trusted = Probe.Seen(relayKey, trusted = true, issuer = "Public CA")

    @Test
    fun aMatchingKeyWithNoProxyIsClear() {
        val a = TlsCanary.assess(relayKey, trusted, ProxyProbe.NoProxy)
        assertFalse(a.intercepted)
        assertFalse(a.untrustedUnpinned)
        assertNull(a.pinNow)
        assertNull(a.proxyVerified)
    }

    @Test
    fun aTrustedFirstContactIsPinned() {
        val a = TlsCanary.assess(null, trusted, ProxyProbe.NoProxy)
        assertEquals(relayKey, a.pinNow)
        assertFalse(a.intercepted)
    }

    @Test
    fun aDirectPinMismatchIsInterception() {
        val a = TlsCanary.assess(relayKey, Probe.Seen("sha256/other", true, "Corp CA"), ProxyProbe.NoProxy)
        assertTrue(a.intercepted)
        assertTrue(a.method!!.contains("pin mismatch"))
    }

    @Test
    fun aProxyThatSubstitutesTheCertificateIsInterception() {
        val via = ProxyProbe.Done(Probe.Seen(proxyKey, trusted = true, issuer = "Proxy CA"))
        val a = TlsCanary.assess(relayKey, trusted, via)
        assertTrue(a.intercepted)
        assertEquals("Proxy substitutes the relay certificate", a.method)
        assertEquals(false, a.proxyVerified)
    }

    @Test
    fun aSubstitutingProxyIsCaughtOnFirstContactToo() {
        // Nothing pinned yet: the through-proxy key is compared with the direct one.
        val via = ProxyProbe.Done(Probe.Seen(proxyKey, trusted = true, issuer = "Proxy CA"))
        val a = TlsCanary.assess(null, trusted, via)
        assertTrue(a.intercepted)
        assertEquals(relayKey, a.pinNow)
    }

    @Test
    fun onlyAKeyThatHeldThroughTheProxyIsVerified() {
        val a = TlsCanary.assess(relayKey, trusted, ProxyProbe.Done(Probe.Seen(relayKey, true, "Public CA")))
        assertFalse(a.intercepted)
        assertEquals(true, a.proxyVerified)
        assertTrue(a.proxyNote!!.contains("unchanged"))
    }

    @Test
    fun aProxyThatCannotBeTunnelledIsReportedAsUnverified() {
        val failed = TlsCanary.assess(relayKey, trusted, ProxyProbe.Done(Probe.Failed("HTTP 407")))
        assertFalse(failed.intercepted)
        assertEquals(false, failed.proxyVerified)
        assertTrue(failed.proxyNote!!.startsWith("Could not verify through the proxy"))

        val pac = TlsCanary.assess(relayKey, trusted, ProxyProbe.Unverifiable("a PAC script chooses the proxy"))
        assertEquals(false, pac.proxyVerified)
        assertTrue(pac.proxyNote!!.contains("PAC"))
    }

    @Test
    fun aProxyKeyWithNothingToCompareIsNotVerified() {
        val a = TlsCanary.assess(null, Probe.Failed("timeout"), ProxyProbe.Done(Probe.Seen(proxyKey, true, "X")))
        assertFalse(a.intercepted)
        assertEquals(false, a.proxyVerified)
        assertNull(a.pinNow)
    }

    @Test
    fun anUntrustedFirstContactIsHighNotCriticalAndIsNotPinned() {
        val selfHosted = Probe.Seen("sha256/private", trusted = false, issuer = "Home CA")
        val a = TlsCanary.assess(null, selfHosted, ProxyProbe.NoProxy)
        assertFalse(a.intercepted)
        assertTrue(a.untrustedUnpinned)
        assertNull(a.pinNow)
        assertEquals(
            app.clairix.Severity.HIGH,
            TlsCanaryResult(intercepted = a.intercepted, method = a.method, proxyHost = null, untrustedUnpinned = true).severity
        )
    }

    @Test
    fun onceAPrivateCertificateIsPinnedOnlyAMismatchCounts() {
        val selfHosted = Probe.Seen("sha256/private", trusted = false, issuer = "Home CA")
        val same = TlsCanary.assess("sha256/private", selfHosted, ProxyProbe.NoProxy)
        assertFalse(same.intercepted)
        assertFalse(same.untrustedUnpinned)
        val swapped = TlsCanary.assess("sha256/private", Probe.Seen("sha256/evil", false, "Evil"), ProxyProbe.NoProxy)
        assertTrue(swapped.intercepted)
    }

    @Test
    fun connectStatusIsReadUpToTheBlankLine() {
        val ok = ByteArrayInputStream("HTTP/1.1 200 Connection established\r\nProxy-Agent: x\r\n\r\n\u0016\u0003".toByteArray(Charsets.ISO_8859_1))
        assertEquals(200, TlsCanary.readConnectStatus(ok))
        // The tunnelled bytes after the headers are left unread.
        assertEquals(0x16, ok.read())

        val denied = ByteArrayInputStream("HTTP/1.0 407 Proxy Authentication Required\r\n\r\n".toByteArray())
        assertEquals(407, TlsCanary.readConnectStatus(denied))
    }

    @Test(expected = IOException::class)
    fun aTruncatedConnectAnswerIsAnError() {
        TlsCanary.readConnectStatus(ByteArrayInputStream("HTTP/1.1 200 OK\r\n".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun aNonHttpConnectAnswerIsAnError() {
        TlsCanary.readConnectStatus(ByteArrayInputStream("SSH-2.0-OpenSSH\r\n\r\n".toByteArray()))
    }

    @Test
    fun aProxyTricklingItsAnswerIsCutOffAtTheDeadline() {
        // One byte every nine seconds stays inside a 10 s read timeout forever; the
        // overall deadline has to end it.
        var clock = 0L
        val answer = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()
        var read = 0
        val trickle = object : java.io.InputStream() {
            override fun read(): Int {
                clock += 9_000L
                return if (read < answer.size) answer[read++].toInt() else -1
            }
        }
        val error = runCatching { TlsCanary.readConnectStatus(trickle, deadlineAt = 30_000L, now = { clock }) }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException)
        assertEquals("proxy response too slow", error!!.message)
        // Stopped after a handful of bytes, not after the whole answer.
        assertTrue("read $read bytes", read <= 4)
    }

    @Test
    fun aPromptAnswerWithinTheDeadlineIsRead() {
        var clock = 0L
        val answer = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()
        var read = 0
        val quick = object : java.io.InputStream() {
            override fun read(): Int {
                clock += 10L
                return if (read < answer.size) answer[read++].toInt() else -1
            }
        }
        assertEquals(200, TlsCanary.readConnectStatus(quick, deadlineAt = 30_000L, now = { clock }))
    }

    @Test
    fun theGlobalProxySettingIsParsed() {
        val p = TlsCanary.parseGlobalProxy(" 10.0.0.2:8888 ")!!
        assertEquals("10.0.0.2", p.host)
        assertEquals(8888, p.port)
        assertNull(TlsCanary.parseGlobalProxy(":0"))
        assertNull(TlsCanary.parseGlobalProxy(""))
        assertEquals(0, TlsCanary.parseGlobalProxy("proxy.example")!!.port)
    }
}
