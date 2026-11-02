package app.clairix.comms.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class MeshAwareWireTest {

    @Test fun exactly_one_of_two_phones_starts_the_meeting() {
        repeat(200) {
            val a = MeshAwareWire.newId()
            val b = MeshAwareWire.newId()
            assertNotEquals(MeshAwareWire.initiates(a, b), MeshAwareWire.initiates(b, a))
        }
        val low = byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0)
        val high = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertTrue("bytes compare unsigned", MeshAwareWire.initiates(low, high))
        assertFalse(MeshAwareWire.initiates(high, low))
        assertFalse("never with itself", MeshAwareWire.initiates(low, low.copyOf()))
    }

    @Test fun an_offer_reads_back_and_rejects_anything_else() {
        val id = MeshAwareWire.newId()
        val pass = MeshAwareWire.newPassphrase()
        assertEquals(MeshAwareWire.PASS_LEN, pass.length)
        val o = MeshAwareWire.parseOffer(MeshAwareWire.offer(id, pass))!!
        assertArrayEquals(id, o.id)
        assertEquals(pass, o.passphrase)
        val bytes = MeshAwareWire.offer(id, pass)
        assertNull(MeshAwareWire.parseOffer(bytes.copyOf(bytes.size - 1)))
        assertNull(MeshAwareWire.parseOffer(bytes.copyOf().also { it[0] = 0 }))
        assertNull(MeshAwareWire.parseOffer(bytes.copyOf().also { it[it.size - 1] = ' '.code.toByte() }))
    }

    @Test fun frames_round_trip_and_bad_lengths_fail() {
        val out = ByteArrayOutputStream()
        val frames = listOf(byteArrayOf(1), ByteArray(4096) { it.toByte() }, ByteArray(MeshAwareWire.MAX_FRAME) { 7 })
        frames.forEach { MeshAwareWire.writeFrame(out, it) }
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        frames.forEach { assertArrayEquals(it, MeshAwareWire.readFrame(input)) }
        assertNull("a clean end between frames", MeshAwareWire.readFrame(input))

        val cut = out.toByteArray().copyOf(10)
        val r = DataInputStream(ByteArrayInputStream(cut))
        MeshAwareWire.readFrame(r)
        assertThrows { MeshAwareWire.readFrame(r) }
        assertThrows { MeshAwareWire.readFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))) }
        assertThrows { MeshAwareWire.readFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(0x7F, 0, 0, 0, 1)))) }
    }

    @Test fun a_meeting_carried_over_frames_moves_the_bundles() {
        // Two sessions talking through the length-prefixed stream, as a Wi-Fi Aware meeting does.
        val now = 1_793_577_600_000L
        val tagA = ByteArray(16) { 1 }
        val tagB = ByteArray(16) { 2 }
        val dir = java.nio.file.Files.createTempDirectory("aware").toFile().apply { deleteOnExit() }
        val storeA = MeshStore(app.clairix.Store(java.io.File(dir, "a.json"), cipher = app.clairix.TestStoreCipher))
        val storeB = MeshStore(app.clairix.Store(java.io.File(dir, "b.json"), cipher = app.clairix.TestStoreCipher))
        val forB = MeshProtocol.newBundle(byteArrayOf(9, 9, 9), tagB, now)!!
        storeA.add(forB, now)
        val gotB = ArrayList<MeshProtocol.Bundle>()
        val aToB = ArrayDeque<ByteArray>()
        val bToA = ArrayDeque<ByteArray>()
        fun wire(q: ArrayDeque<ByteArray>): (ByteArray) -> Unit = { f ->
            val o = ByteArrayOutputStream(); MeshAwareWire.writeFrame(o, f)
            q.add(MeshAwareWire.readFrame(DataInputStream(ByteArrayInputStream(o.toByteArray())))!!)
        }
        val a = MeshSession(storeA, { setOf(MeshProtocol.hex(tagA)) }, {}, wire(aToB), { now }, initiator = true)
        val b = MeshSession(storeB, { setOf(MeshProtocol.hex(tagB)) }, { gotB.add(it) }, wire(bToA), { now }, initiator = false)
        a.start()
        var guard = 0
        while ((aToB.isNotEmpty() || bToA.isNotEmpty()) && guard++ < 100) {
            aToB.removeFirstOrNull()?.let { assertTrue(b.onFrame(it)) }
            bToA.removeFirstOrNull()?.let { assertTrue(a.onFrame(it)) }
        }
        assertTrue(a.finished && b.finished)
        assertEquals(1, gotB.size)
        assertArrayEquals(byteArrayOf(9, 9, 9), gotB[0].data)
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (e: IOException) { return }
        throw AssertionError("expected an IOException")
    }
}
