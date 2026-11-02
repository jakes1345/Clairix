package app.clairix.comms.lora

import app.clairix.comms.lora.LoraLinkProtocol.Frame
import app.clairix.comms.lora.LoraLinkProtocol.LinkBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's side of the radio link writes and reads exactly the bytes the radio's
 * firmware does: every frame is checked against the known answers the radio's own
 * tests check (lora/core/tests/known_answers.h), read from that file, so a change on
 * either side that the other lacks fails here.
 */
class LoraLinkProtocolTest {

    private val ka: Map<String, String> = File("../../lora/core/tests/known_answers.h").readLines()
        .filter { it.startsWith("#define KA_") }
        .associate { line -> line.split(" ", limit = 3).let { it[1] to it[2].trim().trim('"') } }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun seq(n: Int, start: Int) = ByteArray(n) { (start + it).toByte() }

    private val bundle = LinkBundle(seq(16, 0x10), 2, 1_793_574_000_000L, unhex("deadbeef01"))

    @Test
    fun framesThePhoneSendsMatchTheRadio() {
        assertEquals(ka.getValue("KA_LINK_HELLO"), hex(LoraLinkProtocol.hello()))
        val config = LoraLinkProtocol.Config(0, 14, 3, seq(32, 0), 1_793_577_600_123L)
        assertEquals(ka.getValue("KA_LINK_CONFIG"), hex(LoraLinkProtocol.config(config)))
        assertEquals(ka.getValue("KA_LINK_BUNDLE_OUT"), hex(LoraLinkProtocol.bundleOut(bundle, LoraLinkProtocol.BUNDLE_OWN)!!))
        assertEquals(ka.getValue("KA_LINK_GOT"), hex(LoraLinkProtocol.got(0x0102030405060708L)))
        assertEquals(ka.getValue("KA_LINK_LITE_OUT"), hex(LoraLinkProtocol.liteOut(byteArrayOf(1, 2, 3), 5)))
        assertEquals(ka.getValue("KA_LINK_PAIR_OUT"), hex(LoraLinkProtocol.pairOut(byteArrayOf(4, 5))))
    }

    @Test
    fun framesTheRadioSendsAreRead() {
        val info = (LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_INFO"))) as Frame.InfoFrame).info
        assertEquals(LoraLinkProtocol.Board.T_DECK, info.board)
        assertEquals("1.0.0", info.firmware)
        assertEquals(2, info.preset)
        assertEquals(20, info.txPower)
        assertEquals(4, info.hopLimit)
        assertTrue(info.scanning)
        assertArrayEquals(seq(8, 0xA0), info.networkId)
        assertEquals(false, info.publicNetwork)
        assertEquals(true, info.standalone)
        assertEquals(3987, info.batteryMv)
        assertEquals(7, info.stored)
        assertEquals(123_456L, info.heard)
        assertEquals(42L, info.sent)
        assertEquals(99L, info.relayed)
        assertEquals(13, info.airtimePermille)
        assertEquals(ka.getValue("KA_LINK_INFO"), hex(LoraLinkProtocol.encodeInfo(info)))

        val inFrame = LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_BUNDLE_IN"))) as Frame.BundleIn
        assertEquals(0x0102030405060708L, inFrame.bid)
        assertEquals(bundle, inFrame.bundle)
        assertEquals(ka.getValue("KA_LINK_BUNDLE_IN"), hex(LoraLinkProtocol.bundleIn(inFrame.bid, inFrame.bundle)!!))

        val lite = LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_LITE_IN"))) as Frame.LiteIn
        assertArrayEquals(byteArrayOf(9, 8, 7), lite.body)
        assertEquals(-117, lite.rssi)
        assertEquals(-7.25f, lite.snr, 0f)
        assertEquals(ka.getValue("KA_LINK_LITE_IN"), hex(LoraLinkProtocol.heard(LoraLinkProtocol.T_LITE_IN, lite.body, lite.rssi, lite.snr)))

        val pair = LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_PAIR_IN"))) as Frame.PairIn
        assertArrayEquals(byteArrayOf(6), pair.body)
        assertEquals(-40, pair.rssi)
        assertEquals(9.5f, pair.snr, 0f)
    }

    @Test
    fun malformedOrOutOfPlaceFramesAreRefused() {
        assertNull(LoraLinkProtocol.decode(ByteArray(0)))
        // What the phone sends, coming back, is not something the radio says.
        assertNull(LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_BUNDLE_OUT"))))
        assertNull(LoraLinkProtocol.decode(unhex(ka.getValue("KA_LINK_HELLO"))))
        // A bundle whose length field disagrees with what follows.
        val inFrame = unhex(ka.getValue("KA_LINK_BUNDLE_IN"))
        assertNull(LoraLinkProtocol.decode(inFrame.copyOf(inFrame.size - 1)))
        assertNull(LoraLinkProtocol.decode(inFrame + 0))
        // Info cut short.
        val info = unhex(ka.getValue("KA_LINK_INFO"))
        assertNull(LoraLinkProtocol.decode(info.copyOf(info.size - 2)))
        // A heard body longer than one LoRa frame could carry.
        assertNull(LoraLinkProtocol.decode(LoraLinkProtocol.heard(LoraLinkProtocol.T_LITE_IN, ByteArray(237), -90, 1f)))
    }

    @Test
    fun envelopesTooBigForLoRaAreNotOffered() {
        val big = bundle.copy(envelope = ByteArray(LoraLinkProtocol.MAX_ENVELOPE + 1))
        assertNull(LoraLinkProtocol.bundleOut(big, 0))
        val fits = bundle.copy(envelope = ByteArray(LoraLinkProtocol.MAX_ENVELOPE))
        val frame = LoraLinkProtocol.bundleOut(fits, 0)!!
        assertEquals(2 + 16 + 1 + 8 + 2 + LoraLinkProtocol.MAX_ENVELOPE, frame.size)
        assertNull(LoraLinkProtocol.bundleOut(bundle.copy(tag = ByteArray(15)), 0))
    }
}
