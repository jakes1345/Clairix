package app.clairix.comms.mesh

import app.clairix.Store
import app.clairix.TestStoreCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.random.Random

/** Bundles hop between simulated phones, over chunked frames, and reach the phone they are for. */
class MeshTest {

    private var now = 1_700_000_000_000L
    private val rnd = Random(3)

    private fun key(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
    private fun envelope(size: Int = 600) = ByteArray(size).also { rnd.nextBytes(it) }
    private fun store() = MeshStore(Store(File(Files.createTempDirectory("mesh").toFile().apply { deleteOnExit() }, "m.json"), cipher = TestStoreCipher))

    private class Phone(val name: String, val sealing: String, val store: MeshStore) {
        val got = ArrayList<ByteArray>()
    }

    /**
     * Connects two phones through chunked frames of [mtu] bytes, the way GATT carries
     * them, and runs the meeting to the end.
     */
    private fun meet(a: Phone, b: Phone, mtu: Int = 185) {
        val toB = ArrayDeque<ByteArray>()
        val toA = ArrayDeque<ByteArray>()
        val sa = MeshSession(a.store, { MeshProtocol.tagsFor(a.sealing, now) }, { a.got += it.data }, { f -> toB.addAll(MeshProtocol.chunk(f, mtu - 3)) }, { now }, initiator = true)
        val sb = MeshSession(b.store, { MeshProtocol.tagsFor(b.sealing, now) }, { b.got += it.data }, { f -> toA.addAll(MeshProtocol.chunk(f, mtu - 3)) }, { now }, initiator = false)
        val ra = MeshProtocol.Reassembler()
        val rb = MeshProtocol.Reassembler()
        sa.start()
        var guard = 0
        while ((toA.isNotEmpty() || toB.isNotEmpty()) && guard++ < 100_000) {
            toB.removeFirstOrNull()?.let { c -> rb.add(c)?.let { assertTrue(sb.onFrame(it)) } }
            toA.removeFirstOrNull()?.let { c -> ra.add(c)?.let { assertTrue(sa.onFrame(it)) } }
        }
        assertTrue("a did not finish", sa.finished)
        assertTrue("b did not finish", sb.finished)
    }

    @Test
    fun framesSurviveChunkingAndMalformedOnesAreRefused() {
        val b = MeshProtocol.newBundle(envelope(2_000), key(), now)!!
        val frame = MeshProtocol.encode(MeshProtocol.Frame.Carry(b))
        val r = MeshProtocol.Reassembler()
        var whole: ByteArray? = null
        for (c in MeshProtocol.chunk(frame, 20)) r.add(c)?.let { whole = it }
        val back = MeshProtocol.decode(whole!!) as MeshProtocol.Frame.Carry
        assertArrayEquals(b.data, back.bundle.data)
        assertArrayEquals(b.tag, back.bundle.tag)
        assertNull(MeshProtocol.decode(byteArrayOf(9, 9, 9)))
        assertNull(MeshProtocol.decode(frame.copyOf(frame.size - 1)))
        assertNull(MeshProtocol.newBundle(envelope(MeshProtocol.MAX_ENVELOPE + 1), key(), now))
    }

    @Test
    fun aMessageHopsThroughAStrangerToItsRecipient() {
        val alice = Phone("alice", key(), store())
        val stranger = Phone("stranger", key(), store())
        val bob = Phone("bob", key(), store())
        val env = envelope()
        assertTrue(alice.store.addOwn("bob", "m1", MeshProtocol.newBundle(env, bob.sealing, now)!!, now))
        assertFalse("queued twice", alice.store.addOwn("bob", "m1", MeshProtocol.newBundle(envelope(), bob.sealing, now)!!, now))

        meet(alice, stranger)
        assertTrue(stranger.got.isEmpty())
        assertEquals(1, stranger.store.size)

        now += 3_600_000L
        meet(bob, stranger)
        assertEquals(1, bob.got.size)
        assertArrayEquals(env, bob.got[0])
        // Delivered, so bob does not carry it on, and a second copy is not handed over again.
        assertEquals(0, bob.store.size)
        meet(alice, bob)
        assertEquals(1, bob.got.size)
    }

    @Test
    fun bothSidesTradeInOneMeetingAndNothingTravelsTwice() {
        val a = Phone("a", key(), store())
        val b = Phone("b", key(), store())
        val c = key()
        a.store.addOwn("b", "1", MeshProtocol.newBundle(envelope(), b.sealing, now)!!, now)
        a.store.addOwn("c", "2", MeshProtocol.newBundle(envelope(), c, now)!!, now)
        b.store.addOwn("a", "3", MeshProtocol.newBundle(envelope(), a.sealing, now)!!, now)
        meet(a, b)
        assertEquals(1, a.got.size)
        assertEquals(1, b.got.size)
        // b carries c's now, and still its own for a: nothing tells it a has it.
        assertEquals(2, b.store.size)
        meet(a, b)
        assertEquals(1, a.got.size)
        assertEquals(1, b.got.size)
    }

    @Test
    fun oldAndFarTravelledBundlesAreDropped() {
        val s = store()
        val b = MeshProtocol.newBundle(envelope(), key(), now)!!
        assertFalse(s.add(b.copy(hops = MeshProtocol.MAX_HOPS + 1), now))
        assertTrue(s.add(b, now))
        assertEquals(1, s.ids(now).size)
        assertEquals(0, s.ids(now + MeshProtocol.TTL_MS + 1).size)
    }

    @Test
    fun theStoreSurvivesARestart() {
        val dir = Files.createTempDirectory("mesh").toFile().apply { deleteOnExit() }
        val f = File(dir, "m.json")
        val k = key()
        MeshStore(Store(f, cipher = TestStoreCipher)).addOwn("x", "1", MeshProtocol.newBundle(envelope(), k, now)!!, now)
        val again = MeshStore(Store(f, cipher = TestStoreCipher))
        assertEquals(1, again.size)
        assertTrue(again.isQueued("x", "1"))
    }

    @Test
    fun tagsChangeDailyAndAreAnsweredAroundMidnight() {
        val k = key()
        val today = MeshProtocol.tag(k, MeshProtocol.day(now))
        val tomorrow = MeshProtocol.tag(k, MeshProtocol.day(now) + 1)
        assertFalse(today.contentEquals(tomorrow))
        assertTrue(MeshProtocol.hex(tomorrow) in MeshProtocol.tagsFor(k, now))
        assertFalse(MeshProtocol.hex(today) in MeshProtocol.tagsFor(key(), now))
    }
}
