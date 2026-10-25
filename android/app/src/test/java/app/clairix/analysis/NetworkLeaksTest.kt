package app.clairix.analysis

import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VPN coverage checks against the cases that leak and the ones that do not. */
class NetworkLeaksTest {

    private fun fact(p: NetworkLeaks.Picture, id: String) = NetworkLeaks.facts(p).firstOrNull { it.id == id }

    private val wifi = NetworkLeaks.Picture(
        vpnUp = true, appOnVpn = true,
        vpnDns = listOf("10.64.0.1"), underlyingDns = listOf("192.168.1.1"), underlyingGateways = listOf("192.168.1.1"),
        vpnRoutesV4 = true, vpnRoutesV6 = true, underlyingHasGlobalV6 = true, lockdown = true,
        underlyingName = "the Wi-Fi network"
    )

    @Test
    fun aWellSetUpVpnRaisesNothing() {
        assertTrue(NetworkLeaks.facts(wifi).toString(), NetworkLeaks.facts(wifi).none { it.severity != null })
    }

    @Test
    fun dnsGoingToTheRouterIsALeak() {
        val f = fact(wifi.copy(vpnDns = listOf("192.168.1.1")), "vpn_dns")!!
        assertEquals(Severity.HIGH, f.severity)
        assertEquals(Severity.HIGH, fact(wifi.copy(vpnDns = emptyList()), "vpn_dns")!!.severity)
        // Private DNS encrypts the lookups whoever answers them.
        assertNull(fact(wifi.copy(vpnDns = listOf("192.168.1.1"), privateDnsActive = true), "vpn_dns")!!.severity)
    }

    @Test
    fun ipv4OnlyVpnOnAnIpv6NetworkIsFlaggedLow() {
        assertEquals(Severity.LOW, fact(wifi.copy(vpnRoutesV6 = false), "vpn_v6")!!.severity)
        assertNull(fact(wifi.copy(vpnRoutesV6 = false, underlyingHasGlobalV6 = false), "vpn_v6")!!.severity)
    }

    @Test
    fun anExcludedAppAndNoLockdownAreSaid() {
        assertEquals(Severity.MEDIUM, fact(wifi.copy(appOnVpn = false), "vpn_excluded")!!.severity)
        assertEquals(Severity.MEDIUM, fact(wifi.copy(lockdown = false), "vpn_lockdown")!!.severity)
        assertNull(fact(wifi.copy(lockdown = null), "vpn_lockdown")!!.severity)
    }

    @Test
    fun aProxyIsFlaggedWithOrWithoutAVpn() {
        val p = NetworkLeaks.Picture(vpnUp = false, appOnVpn = false, proxy = "10.0.0.5:8080")
        assertEquals(Severity.MEDIUM, fact(p, "net_proxy")!!.severity)
        assertEquals(1, NetworkLeaks.facts(p).size)
        assertTrue(NetworkLeaks.facts(NetworkLeaks.Picture(vpnUp = false, appOnVpn = false)).isEmpty())
    }
}
