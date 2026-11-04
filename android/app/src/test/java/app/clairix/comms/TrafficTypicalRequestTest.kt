package app.clairix.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A typical-speed lookup names the ordered segments of every candidate route,
 * which together say where the driver is going. They go in the POST body, and
 * the path the relay (and any request log in front of it) sees carries none of
 * them (gap audit item 8).
 */
class TrafficTypicalRequestTest {

    private val segs = listOf("123456:0", "987654:1", "555:0")

    @Test
    fun `the path carries no query and no segment`() {
        val path = RelayClient.TRAFFIC_TYPICAL_PATH
        assertEquals("/v1/traffic/typical", path)
        assertFalse(path.contains('?'))
        for (seg in segs) assertFalse(path.contains(seg.substringBefore(':')))
    }

    @Test
    fun `the body holds the region and the segments as a JSON array, in order`() {
        val body = RelayClient.typicalBody("north-america-us-ohio", segs)
        assertEquals("north-america-us-ohio", body.getString("region"))
        val arr = body.getJSONArray("segs")
        assertEquals(segs, (0 until arr.length()).map { arr.getString(it) })
        assertEquals(setOf("region", "segs"), body.keys().asSequence().toSet())
    }

    @Test
    fun `more than the relay's limit is refused before anything is sent`() {
        val tooMany = (1..RelayClient.TRAFFIC_TYPICAL_MAX + 1).map { "$it:0" }
        assertThrows(IllegalArgumentException::class.java) { RelayClient.typicalBody("north-america-us-ohio", tooMany) }
    }
}
