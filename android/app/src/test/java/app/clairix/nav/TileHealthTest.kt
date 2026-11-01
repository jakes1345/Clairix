package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the maps say "Map tiles unavailable": six 4xx/5xx answers within two
 * minutes with no tile served in between, cleared by the first tile served.
 */
class TileHealthTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun aServerRefusingEveryTileIsAnOutageAfterSixAnswers() {
        val h = TileHealth()
        for (i in 0 until TileHealth.FAILURES - 1) assertNull(h.record(403, t0 + i * 100L))
        val outage = h.record(403, t0 + 600L)
        assertNotNull(outage)
        assertEquals(403, outage!!.status)
        assertEquals(t0, outage.since)
        assertTrue(outage.refused)
    }

    @Test
    fun aFailingServerIsAnOutageToo() {
        val h = TileHealth()
        repeat(TileHealth.FAILURES) { h.record(503, t0 + it) }
        val outage = h.current()!!
        assertFalse(outage.refused)
        assertTrue(outage.describe("OpenStreetMap").startsWith("OpenStreetMap's tile server is failing (error 503)"))
    }

    @Test
    fun aTileServedInBetweenMeansTheServerIsAnswering() {
        val h = TileHealth()
        repeat(TileHealth.FAILURES - 1) { h.record(404, t0 + it) }
        assertNull(h.record(200, t0 + 10))
        repeat(TileHealth.FAILURES - 1) { assertNull(h.record(404, t0 + 20 + it)) }
    }

    @Test
    fun theFirstTileServedClearsAnOutage() {
        val h = TileHealth()
        repeat(TileHealth.FAILURES) { h.record(429, t0 + it) }
        assertNotNull(h.current())
        // Still out: another refusal updates the status, keeps the start.
        assertEquals(t0, h.record(403, t0 + 50)?.since)
        assertEquals(403, h.current()?.status)
        assertNull(h.record(200, t0 + 60))
        assertNull(h.current())
    }

    @Test
    fun errorsFurtherApartThanTheWindowAreNotOneRun() {
        val h = TileHealth()
        val step = TileHealth.WINDOW_MS / (TileHealth.FAILURES - 1) + 1
        for (i in 0 until TileHealth.FAILURES * 3) assertNull(h.record(500, t0 + i * step))
    }

    @Test
    fun whatIsNotAnHttpErrorSaysNothingAboutTheServer() {
        val h = TileHealth()
        repeat(TileHealth.FAILURES - 1) { h.record(403, t0 + it) }
        // Redirects and informational answers neither count nor clear.
        assertNull(h.record(302, t0 + 10))
        assertNull(h.record(304, t0 + 11))
        assertNotNull(h.record(403, t0 + 12))
    }

    @Test
    fun aNewServerStartsClean() {
        val h = TileHealth()
        repeat(TileHealth.FAILURES) { h.record(403, t0 + it) }
        h.reset()
        assertNull(h.current())
        assertNull(h.record(403, t0 + 100))
    }

    @Test
    fun theNoticeSaysWhetherTheServerRefusesOrFails() {
        assertEquals(
            "MapTiler's tile server is refusing the map's requests (error 403), so the map background stays blank.",
            TileHealth.Outage(403, t0).describe("MapTiler"),
        )
        assertEquals(
            "OpenStreetMap's tile server is failing (error 502), so the map background stays blank.",
            TileHealth.Outage(502, t0).describe("OpenStreetMap"),
        )
    }
}
