package app.clairix.analysis

import app.clairix.Store
import app.clairix.TestStoreCipher
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The camera sweep survives the process: findings, ticks, when it last ran, the
 * last network result and the IR self-test go to the sealed store and come back,
 * and what Clear removes is gone from disk too.
 */
class CameraSweepRecordTest {

    private val file = File(Files.createTempDirectory("camerasweep").toFile().apply { deleteOnExit() }, "camera_sweep.json")

    private fun store() = Store(file, Dispatchers.Unconfined, TestStoreCipher)

    private fun finding(id: String, ts: Long, method: CameraFindMethod = CameraFindMethod.LENS_GLINT, confidence: Float = 0.9f) = CameraFinding(
        id = id, ts = ts, method = method, confidence = confidence,
        location = "In the viewfinder, 40 % from the left and 50 % from the top", detail = "A small round pinpoint",
        lat = 51.50734, lon = -0.12776
    )

    private val full = CameraSweepRecord(
        findings = listOf(
            finding("optical|glint|1|4|5", 3_000L).copy(photo = "sweep/0123456789abcdef0123456789abcdef.clxp"),
            finding("optical|ir|1|2|2", 2_000L, CameraFindMethod.IR_ILLUMINATOR, 0.65f).copy(needsConfirmation = true, lat = null, lon = null),
            finding("mag|9", 1_000L, CameraFindMethod.MAGNETIC, 0.3f)
        ),
        done = setOf("sightlines", "glint", "above"),
        lastRunTs = 3_500L,
        network = NetworkScanProgress(
            lastScanTs = 2_500L, subnet = "10.20.4.0/24", ssid = "Hotel Guest", liveHosts = 1, found = 0,
            inconclusiveReason = "Only the router answered."
        ),
        ir = IrCheck(passedTs = 1_234L, failedTs = 999L)
    )

    @Test
    fun `a record comes back from JSON as it went in`() {
        assertEquals(full, CameraSweepRecord.fromJson(JSONObject(full.toJson().toString())))
    }

    @Test
    fun `a record survives a reopen of the sealed store, and is not on disk in the clear`() {
        store().replace(full.toJson())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("Hotel Guest"))
        assertFalse(raw.contains("optical|glint"))
        val back = CameraSweepRecord.fromJson(store().load { JSONObject() })
        assertEquals(full, back)
    }

    @Test
    fun `clearing writes the emptied record over the old one`() {
        store().replace(full.toJson())
        // Clear on the Optical tab: the optical findings go, the rest stays.
        val cleared = full.copy(findings = full.findings.filter { it.method != CameraFindMethod.LENS_GLINT && it.method != CameraFindMethod.IR_ILLUMINATOR })
        store().replace(cleared.toJson())
        assertEquals(listOf("mag|9"), CameraSweepRecord.fromJson(store().load { JSONObject() }).findings.map { it.id })
        // "Clear all data": nothing at all.
        store().replace(CameraSweepRecord().toJson())
        val empty = CameraSweepRecord.fromJson(store().load { JSONObject() })
        assertTrue(empty.findings.isEmpty())
        assertTrue(empty.done.isEmpty())
        assertEquals(0L, empty.lastRunTs)
        assertEquals(0L, empty.network.lastScanTs)
        assertFalse(empty.ir.seen)
    }

    @Test
    fun `an empty or damaged document reads as nothing swept`() {
        val none = CameraSweepRecord.fromJson(JSONObject())
        assertEquals(CameraSweepRecord(), none)
        val odd = JSONObject()
            .put("findings", org.json.JSONArray().put(JSONObject().put("id", "x").put("method", "NOT_A_METHOD")).put("junk"))
            .put("done", org.json.JSONArray().put("").put("glint"))
        val read = CameraSweepRecord.fromJson(odd)
        assertTrue(read.findings.isEmpty())
        assertEquals(setOf("glint"), read.done)
        assertNull(read.network.inconclusiveReason)
    }

    @Test
    fun `restoring keeps what this process found and drops what the retention cut`() {
        val current = listOf(finding("optical|glint|1|4|5", 9_000L, confidence = 0.95f))
        val saved = listOf(
            finding("optical|glint|1|4|5", 3_000L, confidence = 0.8f),
            finding("net|10.0.0.7", 5_000L, CameraFindMethod.NETWORK),
            finding("mag|old", 100L, CameraFindMethod.MAGNETIC, 0.3f)
        )
        val merged = CameraSweepRecord.merge(current, saved, cutoff = 1_000L, cap = 200)
        assertEquals(listOf("optical|glint|1|4|5", "net|10.0.0.7"), merged.map { it.id })
        // The one in memory is newer and wins.
        assertEquals(0.95f, merged.first().confidence)
        assertEquals(1, CameraSweepRecord.merge(current, saved, cutoff = null, cap = 1).size)
        assertEquals(3, CameraSweepRecord.merge(emptyList(), saved, cutoff = null, cap = 200).size)
    }
}
