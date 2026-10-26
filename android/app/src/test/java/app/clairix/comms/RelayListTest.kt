package app.clairix.comms

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The relay list's format (tools/relays/check.py holds the same rules) and when a download replaces it. */
class RelayListTest {

    private val now = 1_800_000_000L
    private val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun relay(url: String = "https://relay.example.org") = JSONObject()
        .put("url", url).put("name", "Example").put("country", "IS").put("host", "1984 Hosting")
        .put("operator", "Someone").put("key", key).put("join", "Ask for an invite.")
        .put("onion", "http://" + "a".repeat(56) + ".onion")

    private fun list(issued: Long = 1_700_000_000L, vararg relays: JSONObject = arrayOf(relay())) = JSONObject()
        .put("type", "clairix-relay-list").put("version", 1).put("issued", issued).put("relays", JSONArray(relays.toList()))

    private fun refused(doc: JSONObject) {
        try {
            RelayList.parse(doc.toString(), now)
            fail("accepted: $doc")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aGoodListParses() {
        val parsed = RelayList.parse(list().toString(), now)
        assertEquals(1_700_000_000L, parsed.issued)
        val r = parsed.relays.single()
        assertEquals("https://relay.example.org", r.url)
        assertEquals(key, r.key)
        assertEquals("http://" + "a".repeat(56) + ".onion", r.onion)
        assertSame(r, parsed.find("https://relay.example.org/"))
        assertNull(parsed.find("https://other.example.org"))
    }

    @Test
    fun theListInTheRepositoryParses() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "relays/relays.json") }.first { it.isFile }
        assertTrue(RelayList.parse(file.readText(), System.currentTimeMillis() / 1000).relays.isNotEmpty())
    }

    @Test
    fun aBadListIsRefused() {
        refused(list().put("type", "something-else"))
        refused(list().put("version", 2))
        refused(list(issued = now + 7200))
        refused(list().put("relays", JSONArray()))
        refused(list(relays = arrayOf(relay("http://relay.example.org"))))
        refused(list(relays = arrayOf(relay("https://relay.example.org/path"))))
        refused(list(relays = arrayOf(relay("https://Relay.example.org"))))
        refused(list(relays = arrayOf(relay().put("key", java.util.Base64.getEncoder().encodeToString(ByteArray(31))))))
        refused(list(relays = arrayOf(relay().put("key", "not base64!"))))
        refused(list(relays = arrayOf(relay().put("name", "two\nlines"))))
        refused(list(relays = arrayOf(relay().put("onion", "http://short.onion"))))
        refused(list(relays = arrayOf(relay().put("extra", "field"))))
        refused(list(relays = arrayOf(relay().apply { remove("operator") })))
        refused(list(relays = arrayOf(relay(), relay())))
    }

    @Test
    fun aDownloadReplacesTheListOnlyWhenSignedAndNewer() {
        val current = RelayList.parse(list(issued = 1_700_000_000L).toString(), now)
        val good: (ByteArray, String) -> Boolean = { _, sig -> sig == "good" }
        val newer = list(issued = 1_700_000_100L).toString().toByteArray()
        val older = list(issued = 1_699_999_000L).toString().toByteArray()
        val same = list(issued = 1_700_000_000L).toString().toByteArray()

        assertEquals(1_700_000_100L, RelayList.accept(current, newer, "good", now, good)?.issued)
        // An old list handed back (one that still names a relay since taken off) is not taken.
        assertNull(RelayList.accept(current, older, "good", now, good))
        assertNull(RelayList.accept(current, same, "good", now, good))
        assertEquals(1_699_999_000L, RelayList.accept(null, older, "good", now, good)?.issued)
        try {
            RelayList.accept(current, newer, "forged", now, good)
            fail("an unsigned list was accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
