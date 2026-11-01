package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the network sweep covers, and when its "nothing found" proves nothing: an
 * isolated guest network (only the router answers), a network wider than the /24
 * it probes, no IPv4 address, a sweep stopped early.
 */
class NetworkSweepCoverageTest {

    private fun ip(s: String): Int = NetworkSweepCoverage.ipToInt(s.split('.').map { it.toInt().toByte() }.toByteArray())

    @Test
    fun `a slash 24 is swept whole except the phone itself`() {
        val r = NetworkSweepCoverage.range(ip("192.168.1.37"), 24)
        assertEquals("192.168.1.0/24", r.subnet)
        assertEquals(253, r.hosts.size)
        assertFalse(ip("192.168.1.37") in r.hosts)
        assertEquals("192.168.1.1", NetworkSweepCoverage.ipString(r.hosts.first()))
        assertEquals("192.168.1.254", NetworkSweepCoverage.ipString(r.hosts.last()))
        assertFalse(r.narrowed)
    }

    @Test
    fun `a slash 16 is narrowed to the slash 24 around the phone and says how much that is`() {
        val r = NetworkSweepCoverage.range(ip("10.20.4.17"), 16)
        assertEquals("10.20.4.0/24", r.subnet)
        assertTrue(r.narrowed)
        assertEquals(65_536L, r.networkSize)
        assertEquals(253, r.hosts.size)
        // A camera answered, so the result means something, but not for the rest of the network.
        val why = NetworkSweepCoverage.inconclusiveReason(r, setOf("10.20.4.1", "10.20.4.50"), "10.20.4.1")
        assertNotNull(why)
        assertTrue(why!!, why.contains("/16 (65,536 addresses)"))
        assertTrue(why, why.contains("only the 253 addresses in the /24 around your phone (10.20.4.0/24) were checked"))
        assertFalse(why, why.contains("Only the router answered"))
    }

    @Test
    fun `a slash 22 counts its own size`() {
        val r = NetworkSweepCoverage.range(ip("172.16.2.9"), 22)
        assertEquals(1_024L, r.networkSize)
        assertEquals("172.16.2.0/24", r.subnet)
        assertTrue(NetworkSweepCoverage.inconclusiveReason(r, setOf("172.16.2.1", "172.16.2.80"), "172.16.0.1")!!.contains("/22 (1,024 addresses)"))
    }

    @Test
    fun `a network narrower than a slash 24 is swept whole`() {
        val r = NetworkSweepCoverage.range(ip("192.168.1.37"), 28)
        assertEquals("192.168.1.32/28", r.subnet)
        assertEquals(13, r.hosts.size) // 16 addresses, minus network, broadcast and the phone
        assertFalse(r.narrowed)
    }

    @Test
    fun `an address with no neighbours has nothing to sweep and says so`() {
        val r = NetworkSweepCoverage.range(ip("100.64.3.3"), 32)
        assertTrue(r.hosts.isEmpty())
        val why = NetworkSweepCoverage.inconclusiveReason(r, emptySet(), null)
        assertTrue(why!!, why.contains("no neighbours"))
    }

    @Test
    fun `only the router answering is inconclusive`() {
        val r = NetworkSweepCoverage.range(ip("192.168.1.37"), 24)
        val why = NetworkSweepCoverage.inconclusiveReason(r, setOf("192.168.1.1"), "192.168.1.1")
        assertNotNull(why)
        assertTrue(why!!, why.startsWith("Only the router answered."))
        assertTrue(why, why.contains("client isolation"))
        assertTrue(why, why.contains("Optical and Wireless tabs"))
    }

    @Test
    fun `nobody answering is inconclusive, naming the router only when it was probed`() {
        val r = NetworkSweepCoverage.range(ip("192.168.1.37"), 24)
        assertTrue(NetworkSweepCoverage.inconclusiveReason(r, emptySet(), "192.168.1.1")!!.contains("not even the router"))
        // On a /16 whose router sits outside the block swept, it was never asked.
        val wide = NetworkSweepCoverage.range(ip("10.20.4.17"), 16)
        val why = NetworkSweepCoverage.inconclusiveReason(wide, emptySet(), "10.20.0.1")!!
        assertFalse(why, why.contains("not even the router"))
        assertTrue(why, why.contains("Nothing on this network answered"))
    }

    @Test
    fun `another device answering makes an empty result mean something`() {
        val r = NetworkSweepCoverage.range(ip("192.168.1.37"), 24)
        assertNull(NetworkSweepCoverage.inconclusiveReason(r, setOf("192.168.1.1", "192.168.1.20"), "192.168.1.1"))
        // One device that is not the router: conclusive.
        assertNull(NetworkSweepCoverage.inconclusiveReason(r, setOf("192.168.1.20"), "192.168.1.1"))
        // No default route known: one answer is not the router by definition.
        assertNull(NetworkSweepCoverage.inconclusiveReason(r, setOf("192.168.1.20"), null))
    }

    @Test
    fun `the empty findings text never says nothing was found when the sweep could not look`() {
        assertEquals("Not swept yet", NetworkSweepCoverage.emptyText(NetworkScanProgress()))
        assertEquals("Sweeping…", NetworkSweepCoverage.emptyText(NetworkScanProgress(running = true)))
        assertEquals(
            "Sweep inconclusive",
            NetworkSweepCoverage.emptyText(NetworkScanProgress(lastScanTs = 1L, inconclusiveReason = NetworkSweepCoverage.NO_IPV4))
        )
        assertEquals(
            "Sweep inconclusive",
            NetworkSweepCoverage.emptyText(NetworkScanProgress(lastScanTs = 1L, inconclusiveReason = NetworkSweepCoverage.STOPPED))
        )
        assertEquals("No camera-like hosts found on the last sweep", NetworkSweepCoverage.emptyText(NetworkScanProgress(lastScanTs = 1L, liveHosts = 9)))
    }

    @Test
    fun `addresses survive the round trip through ints`() {
        for (s in listOf("0.0.0.0", "10.0.0.1", "192.168.255.254", "255.255.255.255", "172.16.0.128")) {
            assertEquals(s, NetworkSweepCoverage.ipString(ip(s)))
        }
    }
}
