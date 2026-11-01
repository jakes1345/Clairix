package app.clairix.comms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The version note (relays/latest.json; tools/relays/check_latest.py holds the
 * same rules), which note a phone keeps, what it then offers or warns of, and
 * the brake on sending below the oldest safe version.
 */
class AppUpdateTest {

    private val now = 1_800_000_000L

    private fun note(issued: Long = 1_700_000_000L, code: Int = 29, minSafe: Int = 27, name: String = "3.0.0-beta.9") = JSONObject()
        .put("type", "clairix-latest").put("version", 1).put("issued", issued)
        .put("versionCode", code).put("versionName", name).put("minSafeVersionCode", minSafe)
        .put("notes", "Fixes a flaw that let a crafted link take over a verified contact.")

    private fun release(issued: Long = 1_700_000_000L, code: Int = 29, minSafe: Int = 27) =
        LatestRelease.parse(note(issued, code, minSafe).toString(), now)

    private fun refused(doc: JSONObject) {
        try {
            LatestRelease.parse(doc.toString(), now)
            fail("accepted: $doc")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aGoodNoteParses() {
        val r = release()
        assertEquals(1_700_000_000L, r.issued)
        assertEquals(29, r.versionCode)
        assertEquals("3.0.0-beta.9", r.versionName)
        assertEquals(27, r.minSafeVersionCode)
        assertTrue(r.notes.startsWith("Fixes"))
    }

    @Test
    fun theNoteInTheRepositoryParses() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "relays/latest.json") }.first { it.isFile }
        val r = LatestRelease.parse(file.readText(), System.currentTimeMillis() / 1000)
        assertTrue(r.minSafeVersionCode <= r.versionCode)
    }

    @Test
    fun aBadNoteIsRefused() {
        refused(note().put("type", "clairix-relay-list"))
        refused(note().put("version", 2))
        refused(note().put("version", true))
        refused(note(issued = now + 7200))
        refused(note(issued = 0))
        refused(note().put("issued", 1.5))
        refused(note(code = 0))
        refused(note().put("versionCode", "29"))
        refused(note().put("versionCode", 2_100_000_001L))
        refused(note(minSafe = 30))
        refused(note(minSafe = 0))
        refused(note(name = "3.0"))
        refused(note(name = "v3.0.0"))
        refused(note(name = "3.0.0 Beta 9"))
        refused(note(name = "3.0.0-" + "b".repeat(64)))
        refused(note().put("notes", ""))
        refused(note().put("notes", "two\nlines"))
        refused(note().put("notes", "x".repeat(201)))
        refused(note().put("extra", "field"))
        refused(note().apply { remove("minSafeVersionCode") })
        refused(note().apply { remove("notes") })
        try {
            LatestRelease.parse("not json", now)
            fail("accepted something that is not JSON")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aDownloadReplacesTheNoteOnlyWhenSignedAndIssuedLater() {
        val held = release(issued = 1_700_000_000L)
        val good: (ByteArray, String) -> Boolean = { _, sig -> sig == "good" }
        val newer = note(issued = 1_700_000_100L, code = 30).toString().toByteArray()
        val same = note(issued = 1_700_000_000L).toString().toByteArray()
        // An older note handed back, even one that names a newer version, is not taken: it
        // could be from before a version was found unsafe.
        val older = note(issued = 1_699_999_000L, code = 31, minSafe = 20).toString().toByteArray()

        assertEquals(30, LatestRelease.accept(held, newer, "good", now, good)?.versionCode)
        assertNull(LatestRelease.accept(held, same, "good", now, good))
        assertNull(LatestRelease.accept(held, older, "good", now, good))
        assertEquals(31, LatestRelease.accept(null, older, "good", now, good)?.versionCode)
        try {
            LatestRelease.accept(held, newer, "forged", now, good)
            fail("an unsigned note was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun noNoteOrAnUpToDateBuildShowsNothing() {
        assertSame(UpdateNotice.NONE, updateNotice(null, 28, 0))
        val r = release(code = 29, minSafe = 27)
        assertSame(UpdateNotice.NONE, updateNotice(r, 29, 0))
        // A build newer than the note (one made from main) is neither offered nor warned about.
        assertSame(UpdateNotice.NONE, updateNotice(r, 30, 0))
    }

    @Test
    fun belowTheNewestVersionTheUpdateIsOfferedUntilPutOff() {
        val r = release(code = 29, minSafe = 27)
        val offered = updateNotice(r, 28, 0)
        assertSame(r, offered.newer)
        assertTrue(offered.offer)
        assertFalse(offered.unsafe)
        // Put off: not offered again for that version ...
        assertFalse(updateNotice(r, 28, 29).offer)
        assertNotNull(updateNotice(r, 28, 29).newer)
        // ... and offered again once a newer one is published.
        assertTrue(updateNotice(release(issued = 1_700_000_100L, code = 30, minSafe = 27), 28, 29).offer)
        // Still safe at exactly the oldest safe version.
        assertFalse(updateNotice(r, 27, 0).unsafe)
    }

    @Test
    fun belowTheOldestSafeVersionTheWarningCannotBePutOff() {
        val r = release(code = 29, minSafe = 27)
        val unsafe = updateNotice(r, 26, 29)
        assertTrue(unsafe.unsafe)
        assertTrue("the offer cannot be put off below the floor", unsafe.offer)
        // The floor can be the newest version itself.
        assertTrue(updateNotice(release(code = 29, minSafe = 29), 28, 0).unsafe)
    }

    @Test
    fun belowTheFloorNothingButAnSosIsSent() {
        val unsafe = updateNotice(release(code = 29, minSafe = 27), 26, 0)
        val refusal = sendRefusalFor(unsafe, alert = null)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("3.0.0-beta.9"))
        assertTrue(refusal.contains("SOS"))
        assertNotNull("a call is not an SOS", sendRefusalFor(unsafe, RelayClient.ALERT_CALL))
        assertNull("an SOS still goes", sendRefusalFor(unsafe, RelayClient.ALERT_SOS))
        // Behind but safe, or up to date: everything goes.
        assertNull(sendRefusalFor(updateNotice(release(code = 29, minSafe = 27), 28, 0), null))
        assertNull(sendRefusalFor(UpdateNotice.NONE, null))
    }

    @Test
    fun theDailyRefreshWaitsForItsTimeUnlessTheClockWasWrong() {
        val day = RelayDirectory.REFRESH_INTERVAL_MS
        assertTrue(RelayDirectory.refreshDue(nextTry = 0L, now = now * 1000))
        assertFalse(RelayDirectory.refreshDue(nextTry = now * 1000 + 3_600_000L, now = now * 1000))
        assertTrue(RelayDirectory.refreshDue(nextTry = now * 1000, now = now * 1000))
        // Further ahead than any refresh sets it: written by a clock that was wrong.
        assertTrue(RelayDirectory.refreshDue(nextTry = now * 1000 + 2 * day, now = now * 1000))
    }
}
