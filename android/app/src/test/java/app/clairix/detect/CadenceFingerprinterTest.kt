package app.clairix.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stitching rotated addresses by cadence: a measured match joins, two units of one
 * product that only share their family's traits do not.
 */
class CadenceFingerprinterTest {

    private val t0 = 1_700_000_000_000L

    /** A Find My advertisement: Apple company id, type 0x12, length byte 0x19, 27 bytes. */
    private val airTagPayload = ByteArray(27).also { it[0] = 0x12; it[1] = 0x19 }

    private fun airTag(address: String, atMs: Long, rssi: Int = -70) = CadenceFingerprinter.Packet(
        address = address, timestampNanos = atMs * 1_000_000L, nowMs = atMs, rssi = rssi,
        primaryPhy = 1, advertisingSid = 0xFF, hasRecord = true, txPower = null,
        company = 0x004C, mfrPayload = airTagPayload, services = emptySet(), payloadLength = 31
    )

    private fun generic(address: String, atMs: Long) = CadenceFingerprinter.Packet(
        address = address, timestampNanos = atMs * 1_000_000L, nowMs = atMs, rssi = -65,
        primaryPhy = 1, advertisingSid = 3, hasRecord = true, txPower = -8,
        company = 0x0999, mfrPayload = byteArrayOf(0x07, 0x11, 0x22, 0x33), services = emptySet(), payloadLength = 22
    )

    /** Feeds [count] packets every [intervalMs] from [startMs], with a few ms of jitter; returns every answer. */
    private fun feed(
        fp: CadenceFingerprinter, count: Int, startMs: Long, intervalMs: Long,
        packet: (Long) -> CadenceFingerprinter.Packet
    ): List<CadenceFingerprinter.Match?> =
        (0 until count).map { i -> fp.stitch(packet(startMs + i * intervalMs + JITTER[i % JITTER.size])) }

    @Test
    fun twoAirTagsWithoutMeasuredCadenceAreNotStitched() {
        // The reported bug: neither address had measured an interval, both fell back
        // to the AirTag prior, scored about 0.9 and were joined into one device.
        val fp = CadenceFingerprinter()
        feed(fp, count = 2, startMs = t0, intervalMs = 2_000) { airTag("AA:AA:AA:AA:AA:01", it) }
        fp.assign("AA:AA:AA:AA:AA:01", "tag-one")
        // The first goes quiet; a different AirTag appears seven seconds later.
        val answers = feed(fp, count = 8, startMs = t0 + 9_000, intervalMs = 2_000) { airTag("BB:BB:BB:BB:BB:02", it) }
        assertTrue(answers.toString(), answers.all { it == null })
    }

    @Test
    fun aFreshAddressIsNeverScoredOnTheFamilyPriorAlone() {
        // The old address measured its cadence; the new one has been heard once.
        val fp = CadenceFingerprinter()
        feed(fp, count = 12, startMs = t0, intervalMs = 2_000) { airTag("AA:AA:AA:AA:AA:01", it) }
        val lastA = t0 + 11 * 2_000
        assertNull(fp.stitch(airTag("BB:BB:BB:BB:BB:02", lastA + 6_000)))
    }

    @Test
    fun aMeasuredRotationOfOneAirTagIsStillStitched() {
        val fp = CadenceFingerprinter()
        feed(fp, count = 15, startMs = t0, intervalMs = 2_000) { airTag("AA:AA:AA:AA:AA:01", it) }
        fp.assign("AA:AA:AA:AA:AA:01", "tag-one")
        val lastA = t0 + 14 * 2_000
        // Same cadence, picked up on the very next advertising slot under a new address.
        val answers = feed(fp, count = 6, startMs = lastA + 2_000, intervalMs = 2_000) { airTag("BB:BB:BB:BB:BB:02", it) }
        val match = answers.firstOrNull { it != null }
        assertNotNull(answers.toString(), match)
        assertEquals("tag-one", match!!.logicalId)
        // Nothing but cadence separates two AirTags, so this is never "certain".
        assertFalse(match.certain)
    }

    @Test
    fun anIdenticalAirTagLongAfterTheFirstWentQuietIsNotStitched() {
        // Same cadence and the same family traits, but ten minutes after the first
        // went quiet: that is another unit wandering into range, not a rotation.
        val fp = CadenceFingerprinter()
        feed(fp, count = 15, startMs = t0, intervalMs = 2_000) { airTag("AA:AA:AA:AA:AA:01", it) }
        fp.assign("AA:AA:AA:AA:AA:01", "tag-one")
        val lastA = t0 + 14 * 2_000
        val answers = feed(fp, count = 8, startMs = lastA + 10 * 60_000, intervalMs = 2_000) { airTag("BB:BB:BB:BB:BB:02", it) }
        assertTrue(answers.toString(), answers.all { it == null })
    }

    @Test
    fun addressesHeardAtTheSameTimeAreTwoDevices() {
        val fp = CadenceFingerprinter()
        feed(fp, count = 15, startMs = t0, intervalMs = 2_000) { airTag("AA:AA:AA:AA:AA:01", it) }
        fp.assign("AA:AA:AA:AA:AA:01", "tag-one")
        // B starts while A is still advertising, and keeps going after A stops.
        val answers = feed(fp, count = 12, startMs = t0 + 20_000, intervalMs = 2_000) { airTag("BB:BB:BB:BB:BB:02", it) }
        assertTrue(answers.toString(), answers.all { it == null })
    }

    @Test
    fun aDistinctiveDeviceWithALongHistoryIsACertainMatch() {
        val fp = CadenceFingerprinter()
        feed(fp, count = 15, startMs = t0, intervalMs = 1_285) { generic("CC:CC:CC:CC:CC:01", it) }
        fp.assign("CC:CC:CC:CC:CC:01", "gadget")
        val last = t0 + 14 * 1_285
        val answers = feed(fp, count = 6, startMs = last + 5_000, intervalMs = 1_285) { generic("DD:DD:DD:DD:DD:02", it) }
        val match = answers.firstOrNull { it != null }
        assertNotNull(answers.toString(), match)
        assertEquals("gadget", match!!.logicalId)
        assertTrue(match.certain)
    }

    private companion object {
        val JITTER = longArrayOf(0, 3, 7, 1, 9, 4, 2, 8, 5, 6)
    }
}
