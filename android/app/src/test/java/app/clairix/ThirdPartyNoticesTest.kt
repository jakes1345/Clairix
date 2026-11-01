package app.clairix

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The third-party notices asset the About page reads (written at build time
 * by thirdPartyNotices in app/build.gradle.kts), and the committed inputs it
 * is built from in app/notices.
 */
class ThirdPartyNoticesTest {

    private fun licence(id: String, text: String? = "Licence text of $id") = JSONObject().put("id", id).put("name", "$id licence")
        .put("url", "https://example.org/$id").apply { if (text != null) put("text", text) }

    private fun doc() = JSONObject()
        .put("format", "clairix-third-party-licences").put("version", 1)
        .put("components", JSONArray()
            .put(JSONObject().put("origin", "android").put("name", "osmdroid-android").put("id", "org.osmdroid:osmdroid-android")
                .put("version", "6.1.20").put("by", "org.osmdroid").put("licences", JSONArray().put("Apache-2.0-aaa")))
            .put(JSONObject().put("origin", "android").put("name", "Java Native Access").put("id", "net.java.dev.jna:jna")
                .put("licences", JSONArray().put("Apache-2.0-aaa").put("LGPL-2.1-or-later-bbb")))
            .put(JSONObject().put("origin", "android").put("name", "play-services-location").put("id", "com.google.android.gms:play-services-location")
                .put("licences", JSONArray().put("ASDKL")))
            .put(JSONObject().put("origin", "native").put("name", "WebRTC").put("in", "io.getstream:stream-webrtc-android")
                .put("licences", JSONArray().put("WebRTC-ccc")))
            .put(JSONObject().put("origin", "rust").put("name", "vodozemac").put("version", "0.11.0")
                .put("licences", JSONArray().put("Apache-2.0-aaa"))))
        .put("licences", JSONObject()
            .put("Apache-2.0-aaa", licence("Apache-2.0"))
            .put("LGPL-2.1-or-later-bbb", licence("LGPL-2.1-or-later"))
            .put("WebRTC-ccc", licence("WebRTC"))
            .put("ASDKL", licence("ASDKL", text = null)))

    private fun refused(doc: JSONObject) {
        try {
            ThirdPartyNotices.parse(doc.toString())
            fail("accepted: $doc")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theAssetParsesIntoComponentsAndTheirLicences() {
        val n = ThirdPartyNotices.parse(doc().toString())
        assertEquals(5, n.components.size)
        assertEquals(3, n.from(ThirdPartyNotices.Origin.ANDROID).size)
        val jna = n.components.single { it.id == "net.java.dev.jna:jna" }
        assertEquals(listOf("Apache-2.0", "LGPL-2.1-or-later"), n.licencesOf(jna).map { it.id })
        assertEquals("Licence text of LGPL-2.1-or-later", n.licencesOf(jna)[1].text)
        val webrtc = n.from(ThirdPartyNotices.Origin.NATIVE).single()
        assertEquals("io.getstream:stream-webrtc-android", webrtc.inside)
        assertEquals("WebRTC", webrtc.id) // no id: the name stands in
        // Terms shown by name and link alone carry no text.
        val play = n.components.single { it.name == "play-services-location" }
        assertNull(n.licencesOf(play).single().text)
        assertEquals("https://example.org/ASDKL", n.licencesOf(play).single().url)
        assertEquals("0.11.0", n.from(ThirdPartyNotices.Origin.RUST).single().version)
    }

    @Test
    fun aBrokenAssetIsRefused() {
        refused(JSONObject().put("format", "something-else"))
        refused(doc().put("format", "clairix-relay-list"))
        refused(doc().put("version", 2))
        refused(doc().apply { remove("licences") })
        refused(doc().apply { remove("components") })
        refused(doc().apply { getJSONArray("components").getJSONObject(0).put("origin", "ios") })
        refused(doc().apply { getJSONArray("components").getJSONObject(0).put("licences", JSONArray()) })
        refused(doc().apply { getJSONArray("components").getJSONObject(0).put("licences", JSONArray().put("MIT-zzz")) })
        refused(doc().apply { getJSONArray("components").getJSONObject(0).remove("name") })
        refused(doc().apply { getJSONObject("licences").getJSONObject("ASDKL").remove("name") })
        try {
            ThirdPartyNotices.parse("not json")
            fail("accepted text that is not JSON")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    /** android/app/notices, found from wherever the tests run. */
    private fun noticesDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "android/app/notices"), File(it, "app/notices"), File(it, "notices")) }
            .first { File(it, "notices.json").isFile }

    @Test
    fun everyLicenceTheNoticesNameHasItsTextOrItsLink() {
        val dir = noticesDir()
        val config = JSONObject(File(dir, "notices.json").readText())
        val texts = config.getJSONObject("licenceTexts")
        val linked = config.getJSONObject("linkedOnly")
        for (id in texts.keys().asSequence()) {
            val t = texts.getJSONObject(id)
            assertTrue("$id has a name", t.optString("name").isNotBlank())
            val files = t.optJSONArray("files")
            if (files == null) {
                assertTrue("$id has a text or files", t.optString("text").isNotBlank())
            } else {
                for (i in 0 until files.length()) {
                    val f = File(dir, "licences/" + files.getString(i))
                    assertTrue("$f exists", f.isFile && f.length() > 100)
                }
            }
        }
        for (id in linked.keys().asSequence()) {
            assertTrue("$id has a link", linked.getJSONObject(id).optString("url").startsWith("https://"))
        }
        fun knows(id: String) = texts.has(id) || linked.has(id)
        val urls = config.getJSONObject("licenceUrls")
        for (u in urls.keys().asSequence()) assertTrue("$u maps to a known licence", knows(urls.getString(u)))
        val perLibrary = config.getJSONObject("libraryTexts")
        for (lib in perLibrary.keys().asSequence()) {
            val m = perLibrary.getJSONObject(lib)
            for (k in m.keys().asSequence()) assertTrue("$lib: ${m.getString(k)} is known", knows(m.getString(k)))
        }
        val native = config.getJSONArray("native")
        for (i in 0 until native.length()) {
            val n = native.getJSONObject(i)
            val ls = n.getJSONArray("licences")
            assertTrue("${n.getString("name")} has a licence", ls.length() > 0)
            for (j in 0 until ls.length()) assertTrue("${n.getString("name")}: ${ls.getString(j)} is known", knows(ls.getString(j)))
        }
    }

    @Test
    fun theRustCrateListHasATextForEveryCrate() {
        val rust = JSONObject(File(noticesDir(), "rust-crates.json").readText())
        assertEquals("clairix-rust-crates", rust.getString("format"))
        val licences = rust.getJSONArray("licences")
        val crates = HashSet<String>()
        for (i in 0 until licences.length()) {
            val l = licences.getJSONObject(i)
            assertTrue(l.getString("text").isNotBlank())
            val cs = l.getJSONArray("crates")
            assertTrue(cs.length() > 0)
            for (j in 0 until cs.length()) crates += cs.getJSONObject(j).getString("name")
        }
        // The crates the audit named, and the encryption itself.
        for (name in listOf("vodozemac", "arti-client", "ring", "rustls", "x25519-dalek", "chacha20poly1305", "voprf", "uniffi")) {
            assertTrue("$name is listed", name in crates)
        }
    }
}
