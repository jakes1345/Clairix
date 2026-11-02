package app.clairix.nav

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The server both maps draw their background tiles from: relays/tiles.json in
 * the repository, signed with the asset key and published on the `relays`
 * release next to the relay list, so it can change without an app update, as
 * the OpenStreetMap tile usage policy asks. [DEFAULT], OpenStreetMap's own
 * server, is used while no verified copy is held. Parsed by the same rules as
 * tools/relays/check_tiles.py, which keep the two in step.
 */
data class TileConfig(
    /** When it was issued, seconds since 1970: a phone never goes back to an earlier one. */
    val issued: Long,
    /** Who runs the server, as the app names it when its tiles stop coming. */
    val name: String,
    /** The tile address: `https://`, with `{z}`, `{x}` and `{y}` each once. */
    val url: String,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int,
    /** The credit drawn on the map. */
    val attribution: String,
    /** Where a tap on the credit goes. */
    val attributionUrl: String,
) {

    /** The address of tile ([x], [y]) at zoom [z]. */
    fun tileUrl(z: Int, x: Int, y: Int): String =
        url.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /**
     * The name osmdroid files this server's tiles under. OpenStreetMap's keeps
     * "Mapnik", the name its tiles have had in the cache since the first
     * release; any other server gets a name of its own, so a switch never
     * draws one server's tiles as another's.
     */
    val cacheName: String
        get() = if (url == OSM_URL) "Mapnik" else "tiles-" + sha256Hex(url).take(16)

    companion object {
        const val TYPE = "clairix-tile-source"
        const val VERSION = 1
        const val MAX_TEXT = 200
        const val MAX_URL = 512
        const val MAX_ZOOM = 22
        val TILE_SIZES = setOf(256, 512)
        const val OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

        /**
         * Every server the maps may draw from renders OpenStreetMap data, which
         * the ODbL lets anyone use only with this credit on the map.
         */
        const val REQUIRED_CREDIT = "OpenStreetMap"

        /** OpenStreetMap's own server: what the maps use until a signed setting says otherwise. */
        val DEFAULT = TileConfig(
            issued = 0L,
            name = "OpenStreetMap",
            url = OSM_URL,
            minZoom = 0,
            maxZoom = 19,
            tileSize = 256,
            attribution = "© OpenStreetMap contributors",
            attributionUrl = OSM_COPYRIGHT_URL,
        )

        private val FIELDS = setOf("type", "version", "issued", "name", "url", "minZoom", "maxZoom", "tileSize", "attribution", "attributionUrl")
        private val PLACEHOLDERS = listOf("{z}", "{x}", "{y}")

        /** The setting in [text], or an [IllegalArgumentException] saying what is wrong with it. */
        fun parse(text: String, nowSec: Long): TileConfig {
            val doc = try { JSONObject(text) } catch (e: JSONException) { throw IllegalArgumentException("not JSON", e) }
            require(doc.opt("type") == TYPE) { "not a Clairix tile setting" }
            require(doc.opt("version") == VERSION) { "tile setting version ${doc.opt("version")} is not one this app reads" }
            val keys = doc.keys().asSequence().toSet()
            val unknown = keys - FIELDS
            require(unknown.isEmpty()) { "unknown field(s) ${unknown.sorted()}" }
            val missing = FIELDS - keys
            require(missing.isEmpty()) { "missing ${missing.sorted()}" }
            val issued = whole(doc, "issued")
            require(issued != null && issued > 0) { "issued must be a positive whole number" }
            require(issued <= nowSec + 3600) { "issued is in the future" }
            val name = text(doc, "name")
            val url = https(doc, "url")
            for (p in PLACEHOLDERS) {
                require(url.split(p).size == 2) { "url must contain $p exactly once" }
            }
            val stripped = PLACEHOLDERS.fold(url) { acc, p -> acc.replace(p, "") }
            require('{' !in stripped && '}' !in stripped) { "url may hold no placeholder other than {z}, {x} and {y}" }
            val maxZoom = whole(doc, "maxZoom")
            require(maxZoom != null && maxZoom in 1..MAX_ZOOM) { "maxZoom must be a whole number from 1 to $MAX_ZOOM" }
            val minZoom = whole(doc, "minZoom")
            require(minZoom != null && minZoom in 0..maxZoom) { "minZoom must be a whole number from 0 to maxZoom" }
            val tileSize = whole(doc, "tileSize")
            require(tileSize != null && tileSize.toInt() in TILE_SIZES) { "tileSize must be one of $TILE_SIZES" }
            val attribution = text(doc, "attribution")
            require(REQUIRED_CREDIT in attribution) { "attribution must credit $REQUIRED_CREDIT" }
            val attributionUrl = https(doc, "attributionUrl")
            return TileConfig(issued, name, url, minZoom.toInt(), maxZoom.toInt(), tileSize.toInt(), attribution, attributionUrl)
        }

        /**
         * Whether a downloaded setting replaces [current]: its signature [sig]
         * must check against the asset key ([verify]), it must parse, and it
         * must be issued later, so nobody can hand a phone an old one back.
         * Null when it does not replace it.
         */
        fun accept(current: TileConfig?, raw: ByteArray, sig: String, nowSec: Long, verify: (ByteArray, String) -> Boolean): TileConfig? {
            require(verify(raw, sig)) { "the tile setting is not signed by the Clairix release key" }
            val config = parse(String(raw, Charsets.UTF_8), nowSec)
            return if (current == null || config.issued > current.issued) config else null
        }

        /** A JSON whole number (not a fraction, not a boolean), or null. */
        private fun whole(doc: JSONObject, field: String): Long? =
            (doc.opt(field) as? Number)?.takeIf { it is Int || it is Long }?.toLong()

        private fun text(doc: JSONObject, field: String): String {
            val v = doc.opt(field) as? String
            require(v != null && v.isNotBlank() && v.length <= MAX_TEXT && v.none { it == '\n' || it == '\r' || it == '\t' }) {
                "$field must be one line of text, 1-$MAX_TEXT characters"
            }
            return v
        }

        /** An https URL with a bare lowercase host, no credentials, no spaces, no fragment, and a path. */
        private fun https(doc: JSONObject, field: String): String {
            val v = doc.opt(field) as? String
            require(v != null && v.isNotEmpty() && v.length <= MAX_URL) { "$field must be a URL of at most $MAX_URL characters" }
            require(v.none { it.isWhitespace() } && '#' !in v) { "$field must have no spaces and no #fragment" }
            require(v.startsWith("https://")) { "$field must start with https://" }
            val rest = v.removePrefix("https://")
            val host = rest.substringBefore('/').substringBefore('?')
            require(host.isNotEmpty() && '@' !in host && host == host.lowercase() && '{' !in host && '}' !in host) {
                "$field must name a lowercase host with no credentials or placeholders in it"
            }
            require('/' in rest) { "$field must have a path after the host" }
            return v
        }

        private fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
