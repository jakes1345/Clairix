package app.clairix.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The tile setting's format (tools/relays/check_tiles.py holds the same rules),
 * when a download replaces the one held, and the fallback to OpenStreetMap's
 * server while none is held.
 */
class TileConfigTest {

    private val now = 1_800_000_000L

    private fun setting(issued: Long = 1_700_000_000L) = JSONObject()
        .put("type", "clairix-tile-source").put("version", 1).put("issued", issued)
        .put("name", "OpenStreetMap").put("url", "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        .put("minZoom", 0).put("maxZoom", 19).put("tileSize", 256)
        .put("attribution", "© OpenStreetMap contributors").put("attributionUrl", "https://www.openstreetmap.org/copyright")

    private fun refused(doc: JSONObject) {
        try {
            TileConfig.parse(doc.toString(), now)
            fail("accepted: $doc")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aGoodSettingParses() {
        val c = TileConfig.parse(setting().toString(), now)
        assertEquals(1_700_000_000L, c.issued)
        assertEquals("OpenStreetMap", c.name)
        assertEquals(19, c.maxZoom)
        assertEquals("https://tile.openstreetmap.org/12/2047/1361.png", c.tileUrl(12, 2047, 1361))
    }

    @Test
    fun aCommercialServerWithAKeyInTheQueryParses() {
        val c = TileConfig.parse(
            setting().put("name", "MapTiler")
                .put("url", "https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=abc123")
                .put("maxZoom", 22).put("attribution", "© MapTiler © OpenStreetMap contributors").toString(),
            now,
        )
        assertEquals("https://api.maptiler.com/maps/streets-v2/256/3/4/5.png?key=abc123", c.tileUrl(3, 4, 5))
    }

    @Test
    fun theSettingInTheRepositoryParses() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "relays/tiles.json") }.first { it.isFile }
        val c = TileConfig.parse(file.readText(), System.currentTimeMillis() / 1000)
        assertTrue(TileConfig.REQUIRED_CREDIT in c.attribution)
    }

    @Test
    fun aBadSettingIsRefused() {
        refused(setting().put("type", "clairix-relay-list"))
        refused(setting().put("version", 2))
        refused(setting(issued = now + 7200))
        refused(setting(issued = 0))
        refused(setting().put("issued", true))
        refused(setting().put("extra", "field"))
        refused(setting().apply { remove("attributionUrl") })
        refused(setting().put("name", "two\nlines"))
        refused(setting().put("name", "x".repeat(201)))
        refused(setting().put("url", "http://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        refused(setting().put("url", "https://tile.openstreetmap.org/{z}/{x}.png"))
        refused(setting().put("url", "https://tile.openstreetmap.org/{z}/{x}/{y}/{y}.png"))
        refused(setting().put("url", "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"))
        refused(setting().put("url", "https://tile.openstreetmap.org/{z}/{x}/{y}.png?style={style}"))
        refused(setting().put("url", "https://Tile.openstreetmap.org/{z}/{x}/{y}.png"))
        refused(setting().put("url", "https://user:pw@tile.openstreetmap.org/{z}/{x}/{y}.png"))
        refused(setting().put("url", "https://tile.openstreetmap.org/{z}/{x}/{y}.png#frag"))
        refused(setting().put("url", "https://tile.openstreetmap.org/ {z}/{x}/{y}.png"))
        refused(setting().put("url", "https://tile.example.org/" + "a".repeat(512) + "/{z}/{x}/{y}.png"))
        refused(setting().put("maxZoom", 23))
        refused(setting().put("maxZoom", 0))
        refused(setting().put("minZoom", 20))
        refused(setting().put("minZoom", -1))
        refused(setting().put("minZoom", 1.5))
        refused(setting().put("tileSize", 300))
        refused(setting().put("tileSize", 256.5))
        refused(setting().put("attribution", "© Some tile company"))
        refused(setting().put("attributionUrl", "http://www.openstreetmap.org/copyright"))
        refused(JSONObject().put("type", "clairix-tile-source"))
    }

    @Test
    fun aDownloadReplacesTheSettingOnlyWhenSignedAndNewer() {
        val held = TileConfig.parse(setting(issued = 1_700_000_000L).toString(), now)
        val good: (ByteArray, String) -> Boolean = { _, sig -> sig == "good" }
        val newer = setting(issued = 1_700_000_100L).toString().toByteArray()
        val older = setting(issued = 1_699_999_000L).toString().toByteArray()
        val same = setting(issued = 1_700_000_000L).toString().toByteArray()

        assertEquals(1_700_000_100L, TileConfig.accept(held, newer, "good", now, good)?.issued)
        // An old setting handed back (one naming a server since moved away from) is not taken.
        assertNull(TileConfig.accept(held, older, "good", now, good))
        assertNull(TileConfig.accept(held, same, "good", now, good))
        // With none held, any signed one is taken: it replaces the built-in default.
        assertEquals(1_699_999_000L, TileConfig.accept(null, older, "good", now, good)?.issued)
        try {
            TileConfig.accept(held, newer, "forged", now, good)
            fail("an unsigned setting was accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            TileConfig.accept(null, "{\"type\":\"clairix-tile-source\"}".toByteArray(), "good", now, good)
            fail("a signed but malformed setting was accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun withoutAVerifiedSettingTheMapsUseOpenStreetMapsServer() {
        val d = TileConfig.DEFAULT
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", d.url)
        assertEquals(0L, d.issued) // so any signed setting replaces it
        assertTrue(TileConfig.REQUIRED_CREDIT in d.attribution)
        assertEquals("https://www.openstreetmap.org/copyright", d.attributionUrl)
        // The default passes the same rules a downloaded setting must.
        val asJson = setting().put("issued", 1).put("url", d.url).put("name", d.name).put("attribution", d.attribution)
        TileConfig.parse(asJson.toString(), now)
    }

    @Test
    fun openStreetMapKeepsItsCacheAndAnotherServerGetsItsOwn() {
        val osm = TileConfig.parse(setting().toString(), now)
        assertEquals("Mapnik", osm.cacheName)
        assertEquals("Mapnik", TileConfig.DEFAULT.cacheName)
        val other = TileConfig.parse(setting().put("url", "https://tiles.example.org/{z}/{x}/{y}.png").toString(), now)
        val third = TileConfig.parse(setting().put("url", "https://tiles.example.net/{z}/{x}/{y}.png").toString(), now)
        assertTrue(other.cacheName.startsWith("tiles-"))
        assertNotEquals(other.cacheName, third.cacheName)
        assertEquals(other.cacheName, TileConfig.parse(setting().put("url", other.url).toString(), now).cacheName)
    }

    @Test
    fun theUserAgentNamesTheAppAndHowToReachTheProject() {
        assertEquals(
            "Clairix/3.0.0-beta.8 (+https://clairix.app; support@clairix.app)",
            MapTiles.userAgent("3.0.0-beta.8"),
        )
    }
}
