package app.clairix.comms.lora

import app.clairix.comms.mesh.MeshProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The LoRa bridge's wire format (Meshtastic's protobufs, written by hand), the
 * fragments a bundle is cut into, and the airtime rules.
 */
class LoraTest {

    private val hour = 3_600_000L
    private val now = 1_793_450_117_000L

    private fun bundle(size: Int, hops: Int = 1, seed: Int = 1, createdMs: Long = MeshProtocol.hourOf(now)): MeshProtocol.Bundle {
        val data = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }
        val tag = ByteArray(MeshProtocol.TAG_LEN) { (it * 7 + seed).toByte() }
        return MeshProtocol.Bundle(MeshProtocol.idOf(data), tag, hops, createdMs, data)
    }

    // ── Meshtastic protobufs ──────────────────────────────────────────────

    @Test
    fun wantConfigIsToRadioFieldThreeAsAVarint() {
        // field 3, wire type 0 = 0x18; 1234 = 0xD2 0x09
        assertArrayEquals(byteArrayOf(0x18, 0xD2.toByte(), 0x09), MeshtasticProto.wantConfig(1234))
        // A nonce above 2^31 is still written unsigned.
        assertEquals(6, MeshtasticProto.wantConfig(-1).size)
    }

    @Test
    fun aBroadcastMatchesTheProtobufLayoutByteForByte() {
        val got = MeshtasticProto.broadcast(byteArrayOf(0x41, 0x42), port = 400, packetId = 0x01020304, hopLimit = 3)
        val data = byteArrayOf(0x08, 0x90.toByte(), 0x03, 0x12, 0x02, 0x41, 0x42) // portnum=400, payload="AB"
        val packet = byteArrayOf(0x15, -1, -1, -1, -1) + // to = 0xFFFFFFFF (fixed32, field 2)
            byteArrayOf(0x22, data.size.toByte()) + data + // decoded (field 4)
            byteArrayOf(0x35, 0x04, 0x03, 0x02, 0x01) + // id (fixed32, field 6), little endian
            byteArrayOf(0x48, 0x03) // hop_limit (field 9)
        val toRadio = byteArrayOf(0x0A, packet.size.toByte()) + packet
        assertArrayEquals(toRadio, got)
    }

    @Test
    fun aPacketFromTheRadioIsReadBack() {
        // Build what the radio would send: FromRadio{id=7, packet=MeshPacket{from, to, channel, decoded, id}}
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val data = byteArrayOf(0x08, 0x90.toByte(), 0x03, 0x12, payload.size.toByte()) + payload
        val packet = byteArrayOf(0x0D, 0x78, 0x56, 0x34, 0x12) + // from = 0x12345678
            byteArrayOf(0x15, -1, -1, -1, -1) +
            byteArrayOf(0x18, 0x02) + // channel 2
            byteArrayOf(0x22, data.size.toByte()) + data +
            byteArrayOf(0x35, 0x2A, 0, 0, 0) +
            byteArrayOf(0x45, 0, 0, 0x20, 0x41) + // rx_snr = 10.0f (field 8, fixed32): skipped
            byteArrayOf(0x60, 0xB0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01) // rx_rssi = -80
        val fromRadio = byteArrayOf(0x08, 0x07, 0x12, packet.size.toByte()) + packet
        val p = MeshtasticProto.parseFromRadio(fromRadio) as MeshtasticProto.FromRadio.Packet
        assertEquals(0x12345678, p.from)
        assertEquals(MeshtasticProto.BROADCAST, p.to)
        assertEquals(2, p.channel)
        assertEquals(42, p.id)
        assertEquals(400, p.port)
        assertArrayEquals(payload, p.payload)
    }

    @Test
    fun ourOwnBroadcastParsesAsAPacketWithTheSamePayload() {
        val toRadio = MeshtasticProto.broadcast(byteArrayOf(9, 8, 7), port = 400, packetId = 99)
        // ToRadio.packet (1) and FromRadio.packet (2) hold the same MeshPacket.
        val asFromRadio = toRadio.copyOf().also { it[0] = 0x12 }
        val p = MeshtasticProto.parseFromRadio(asFromRadio) as MeshtasticProto.FromRadio.Packet
        assertEquals(99, p.id)
        assertArrayEquals(byteArrayOf(9, 8, 7), p.payload)
    }

    @Test
    fun myInfoConfigCompleteQueueAndRebootAreRecognised() {
        assertEquals(MeshtasticProto.FromRadio.MyInfo(0x0BADCAFE),
            MeshtasticProto.parseFromRadio(byteArrayOf(0x1A, 0x05, 0x08, 0xFE.toByte(), 0x95.toByte(), 0xB7.toByte(), 0x5D)))
        assertEquals(MeshtasticProto.FromRadio.ConfigComplete(1234),
            MeshtasticProto.parseFromRadio(byteArrayOf(0x38, 0xD2.toByte(), 0x09)))
        assertEquals(MeshtasticProto.FromRadio.Queue(0, 3, 16, 0),
            MeshtasticProto.parseFromRadio(byteArrayOf(0x5A, 0x04, 0x10, 0x03, 0x18, 0x10)))
        assertEquals(MeshtasticProto.FromRadio.Rebooted, MeshtasticProto.parseFromRadio(byteArrayOf(0x40, 0x01)))
    }

    @Test
    fun anEncryptedPacketOrUnknownFieldIsOtherAndGarbageIsNull() {
        // MeshPacket with only `encrypted` (field 5): the radio couldn't decode it.
        val packet = byteArrayOf(0x2A, 0x02, 0x55, 0x66)
        assertEquals(MeshtasticProto.FromRadio.Other, MeshtasticProto.parseFromRadio(byteArrayOf(0x12, packet.size.toByte()) + packet))
        // node_info (field 4) is not used.
        assertEquals(MeshtasticProto.FromRadio.Other, MeshtasticProto.parseFromRadio(byteArrayOf(0x22, 0x00)))
        assertNull(MeshtasticProto.parseFromRadio(byteArrayOf(0x12, 0x40, 0x01)))  // length past the end
        assertNull(MeshtasticProto.parseFromRadio(byteArrayOf(0x80.toByte())))     // truncated varint
        assertNull(MeshtasticProto.parseFromRadio(byteArrayOf(0x07)))               // field 0
    }

    // ── Fragments ─────────────────────────────────────────────────────────

    @Test
    fun aBundleSurvivesSplittingAndReassemblyInAnyOrderWithRepeats() {
        val b = bundle(700, hops = 2)
        val parts = LoraFrames.split(b)
        assertEquals(4, parts.size)
        parts.forEach { assertTrue(it.size <= MeshtasticProto.DATA_PAYLOAD_LEN) }
        val r = LoraFrames.Reassembler()
        val order = listOf(3, 1, 1, 0, 3)
        order.forEach { assertNull(r.add(parts[it], now)) }
        val got = r.add(parts[2], now)
        assertNotNull(got)
        assertArrayEquals(b.id, got!!.id)
        assertArrayEquals(b.tag, got.tag)
        assertArrayEquals(b.data, got.data)
        assertEquals(b.createdMs, got.createdMs)
        assertEquals("crossing LoRa is one hop", 3, got.hops)
        // A late repeat of a finished bundle is ignored, not started again.
        assertNull(r.add(parts[0], now))
        assertEquals(0, r.inProgress)
    }

    @Test
    fun theLargestEnvelopeFitsInEightPacketsAndOneByteMoreDoesNot() {
        assertEquals(LoraFrames.MAX_FRAGMENTS, LoraFrames.split(bundle(LoraFrames.MAX_ENVELOPE)).size)
        assertTrue(LoraFrames.split(bundle(LoraFrames.MAX_ENVELOPE + 1)).isEmpty())
        assertEquals(0, LoraFrames.fragmentCount(bundle(4_000)))
        assertEquals(1, LoraFrames.fragmentCount(bundle(10)))
    }

    @Test
    fun aTamperedFragmentDropsTheBundle() {
        val parts = LoraFrames.split(bundle(500)).map { it.copyOf() }
        parts[1][LoraFrames.HEADER + 3] = (parts[1][LoraFrames.HEADER + 3] + 1).toByte()
        val r = LoraFrames.Reassembler()
        assertNull(parts.firstNotNullOfOrNull { r.add(it, now) })
    }

    @Test
    fun foreignAndMalformedPayloadsAreIgnored() {
        val r = LoraFrames.Reassembler()
        val good = LoraFrames.split(bundle(300))[0]
        assertNull(r.add(good.copyOf().also { it[0] = 0x00 }, now))      // not ours
        assertNull(r.add(good.copyOf().also { it[1] = 9 }, now))         // other version
        assertNull(r.add(good.copyOf().also { it[11] = 0 }, now))        // count 0
        assertNull(r.add(good.copyOf().also { it[11] = 9 }, now))        // too many
        assertNull(r.add(good.copyOf().also { it[10] = 5; it[11] = 2 }, now)) // index past count
        assertNull(r.add(good.copyOf(LoraFrames.HEADER), now))           // header only
        assertEquals(0, r.inProgress)
    }

    @Test
    fun unfinishedBundlesExpireAndThePendingListIsBounded() {
        val r = LoraFrames.Reassembler(maxPending = 3, timeoutMs = 10 * 60_000L)
        val a = LoraFrames.split(bundle(500, seed = 1))
        r.add(a[0], now)
        assertEquals(1, r.inProgress)
        // Another bundle's fragment much later expires the first.
        r.add(LoraFrames.split(bundle(500, seed = 2))[0], now + 11 * 60_000L)
        assertEquals(1, r.inProgress)
        assertNull("the rest of the expired one doesn't complete it", a.drop(1).firstNotNullOfOrNull { r.add(it, now + 11 * 60_000L) })
        (3..10).forEach { r.add(LoraFrames.split(bundle(500, seed = it))[0], now + 11 * 60_000L) }
        assertEquals(3, r.inProgress)
    }

    // ── Airtime ───────────────────────────────────────────────────────────

    @Test
    fun thePlannerStaysWithinItsBudgetAndPrefersFewerHops() {
        val p = LoraPlanner(budget = 10, windowMs = 10 * 60_000L)
        val near = bundle(400, hops = 1, seed = 1)      // 2 packets
        val far = bundle(400, hops = 5, seed = 2)       // 2 packets
        val big = bundle(1_500, hops = 1, seed = 3, createdMs = MeshProtocol.hourOf(now) - hour) // 7 packets, an hour older
        val picked = p.pick(listOf(far, big, near), now)
        assertEquals(listOf(near, big), picked) // 2 + 7 = 9; far (2 more) would pass 10
        picked.forEach { p.sent(it, LoraFrames.fragmentCount(it), now) }
        assertEquals(1, p.remaining(now))
        assertTrue(p.pick(listOf(far), now).isEmpty())
        // The window rolls on.
        assertEquals(listOf(far), p.pick(listOf(far), now + 10 * 60_000L))
    }

    @Test
    fun aBundleHeardOnLoraIsNeverSentBackOntoIt() {
        val p = LoraPlanner()
        val b = bundle(200)
        p.heard(b)
        assertTrue(p.pick(listOf(b), now).isEmpty())
    }

    @Test
    fun eachBundleGoesOutAtMostTwiceHalfAnHourApart() {
        val p = LoraPlanner(budget = 100, maxSends = 2, resendAfterMs = 30 * 60_000L)
        val b = bundle(200)
        assertEquals(listOf(b), p.pick(listOf(b), now)); p.sent(b, 1, now)
        assertTrue(p.pick(listOf(b), now + 29 * 60_000L).isEmpty())
        assertEquals(listOf(b), p.pick(listOf(b), now + 30 * 60_000L)); p.sent(b, 1, now + 30 * 60_000L)
        assertTrue(p.pick(listOf(b), now + 5 * hour).isEmpty())
    }

    @Test
    fun nothingGoesOutWhileTheRadioQueueIsFullOrForTooBigOrTooFarBundles() {
        val p = LoraPlanner()
        p.radioQueueFull = true
        assertTrue(p.pick(listOf(bundle(100)), now).isEmpty())
        p.radioQueueFull = false
        assertTrue(p.pick(listOf(bundle(4_000)), now).isEmpty())
        assertTrue(p.pick(listOf(bundle(100, hops = MeshProtocol.MAX_HOPS)), now).isEmpty())
    }
}
