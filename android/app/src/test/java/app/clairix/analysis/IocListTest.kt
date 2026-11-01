package app.clairix.analysis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * The stalkerware list's format (tools/ioc/convert.py --check holds the same rules), how an app is
 * matched against it, and when a download replaces the list a phone has.
 */
class IocListTest {

    private val now = 1_800_000_000L
    private val sha1 = "31A6ECECD97CF39BC4126B8745CD94A7C30BF81C"
    private val sha256 = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"

    private fun doc(issued: Long = 1_700_000_000L) = JSONObject()
        .put("type", "clairix-stalkerware-ioc").put("version", 1).put("issued", issued)
        .put("source", "test").put("licence", "CC BY 4.0")
        .put("names", JSONArray().put(JSONArray().put("TheTruthSpy").put("Copy9")).put(JSONArray().put("Cocospy")))
        .put("packages", JSONObject().put("com.mxspy", 0).put("com.duiyun.spyic", 1))
        .put("certs", JSONObject().put(sha1, 0).put(sha256, 1))

    private fun gz(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun refused(d: JSONObject) {
        try {
            IocList.parse(gz(d.toString()), now)
            fail("accepted: $d")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aGoodListParsesAndMatchesByPackageAndByCertificate() {
        val list = IocList.parse(gz(doc().toString()), now)
        assertEquals(1_700_000_000L, list.issued)
        assertEquals(2, list.packageCount)
        assertEquals(2, list.certificateCount)

        val byName = list.match("com.mxspy", emptySet())!!
        assertEquals("TheTruthSpy", byName.family.name)
        assertEquals(listOf("Copy9"), byName.family.aliases)
        assertTrue(byName.byPackage && !byName.byCertificate)

        // Any name, the listed certificate: lower case is matched too.
        val byCert = list.match("com.innocent.looking.app", setOf(sha1.lowercase()))!!
        assertEquals("TheTruthSpy", byCert.family.name)
        assertTrue(byCert.byCertificate && !byCert.byPackage)

        // A SHA-256 fingerprint is matched as well as the SHA-1 ones the source gives.
        assertEquals("Cocospy", list.match("x.y", setOf(sha256))!!.family.name)

        // Name and certificate of the same app: both said.
        val both = list.match("com.duiyun.spyic", setOf(sha256))!!
        assertTrue(both.byPackage && both.byCertificate)

        assertNull(list.match("com.whatsapp", setOf("00".repeat(20))))
    }

    @Test
    fun theCertificateDecidesWhenTheNameSaysSomethingElse() {
        val list = IocList.parse(gz(doc().toString()), now)
        val m = list.match("com.mxspy", setOf(sha256))!!
        assertEquals("Cocospy", m.family.name)
        assertTrue(m.byCertificate)
        assertFalse("the name belongs to another app on the list", m.byPackage)
    }

    @Test
    fun aBadListIsRefused() {
        refused(doc().put("type", "clairix-relay-list"))
        refused(doc().put("version", 2))
        refused(doc().put("version", true))
        refused(doc(issued = now + 7200))
        refused(doc().put("issued", 0))
        refused(doc().put("extra", 1))
        refused(doc().put("source", JSONObject.NULL))
        refused(doc().put("names", JSONArray()))
        refused(doc().put("names", JSONArray().put(JSONArray())))
        refused(doc().put("names", JSONArray().put(JSONArray().put("two\nlines"))))
        refused(doc().put("names", JSONArray().put(JSONArray().put(" padded"))))
        refused(doc().put("packages", JSONObject()))
        refused(doc().put("packages", JSONObject().put("nodot", 0)))
        refused(doc().put("packages", JSONObject().put("1bad.start", 0)))
        refused(doc().put("packages", JSONObject().put("com.ok.app", 2)))
        refused(doc().put("packages", JSONObject().put("com.ok.app", true)))
        refused(doc().put("certs", JSONObject().put(sha1.lowercase(), 0)))
        refused(doc().put("certs", JSONObject().put("ABCD", 0)))
        try {
            IocList.parse(doc().toString().toByteArray(), now)
            fail("accepted a list that is not gzip")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aListLargerThanTheCapIsRefusedWhileItIsBeingRead() {
        val big = ByteArray((IocList.MAX_JSON_BYTES + 1).toInt()) { ' '.code.toByte() }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(big) }
        try {
            IocList.gunzip(out.toByteArray(), IocList.MAX_JSON_BYTES)
            fail("read past the cap")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aDownloadReplacesTheListOnlyWhenSignedAndNewer() {
        val current = IocList.parse(gz(doc(issued = 1_700_000_000L).toString()), now)
        val newer = gz(doc(issued = 1_700_000_100L).toString())
        val older = gz(doc(issued = 1_600_000_000L).toString())
        val good: (ByteArray, String) -> Boolean = { _, sig -> sig == "good" }

        assertEquals(1_700_000_100L, IocList.accept(current, newer, "good", now, good)!!.issued)
        // The same issue or an older one is no replacement, so an old list cannot be handed back.
        assertNull(IocList.accept(current, gz(doc(issued = 1_700_000_000L).toString()), "good", now, good))
        assertNull(IocList.accept(current, older, "good", now, good))
        // With nothing on the phone, any signed list will do.
        assertNotNull(IocList.accept(null, older, "good", now, good))
        try {
            IocList.accept(current, newer, "forged", now, good)
            fail("accepted an unsigned list")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theListBuiltIntoTheAppParsesAndHasTheRealNames() {
        val file = File("src/suite/assets/ioc.json.gz")
        val list = IocList.parse(file.readBytes(), System.currentTimeMillis() / 1000)
        assertTrue(list.families.size >= 80 && list.packageCount >= 350 && list.certificateCount >= 250)
        // Names stalkerware really ships under, as the source lists them.
        assertEquals("FlexiSpy", list.match("com.vvt.android.syncmanager", emptySet())!!.family.name)
        assertEquals("Hoverwatch", list.match("com.android.core.mnt", emptySet())!!.family.name)
        assertEquals("XNSpy", list.match("com.system.task", emptySet())!!.family.name)
        // And none of the names the old hard-coded list made up.
        for (invented in listOf("com.flexispy.android", "com.hoverwatch.android", "com.spyic.android", "com.xnspy.android", "com.cerberusapp")) {
            assertNull(invented, list.match(invented, emptySet()))
        }
    }
}
