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
import kotlin.random.Random
import uniffi.clairix_comms_crypto.Identity

/** Bundles hop between simulated phones, over chunked frames, and reach the phone they are for. */
class MeshTest {

    private var now = 1_700_000_000_000L
    private val rnd = Random(3)

    private fun tag(): ByteArray = ByteArray(MeshProtocol.TAG_LEN).also { rnd.nextBytes(it) }
    private fun envelope(size: Int = 600) = ByteArray(size).also { rnd.nextBytes(it) }
    private fun store() = MeshStore(Store(File(Files.createTempDirectory("mesh").toFile().apply { deleteOnExit() }, "m.json"), cipher = TestStoreCipher))

    /** A phone with a real identity, answering to the tags it shares with [contacts]. */
    private class Phone(val name: String, val store: MeshStore) {
        val identity: Identity = Identity.create()
        val sealing: String get() = identity.publicBundle().sealing
        val contacts = ArrayList<Phone>()
        val got = ArrayList<ByteArray>()
        fun tags(now: Long): Set<String> = contacts.flatMap { c -> MeshProtocol.around(now) { identity.meshTag(c.sealing, it) } }.toSet()
        fun bundleFor(to: Phone, envelope: ByteArray, now: Long) =
            MeshProtocol.newBundle(envelope, identity.meshTag(to.sealing, MeshProtocol.day(now)), now)!!
    }

    private fun friends(vararg phones: Phone) {
        for (p in phones) for (q in phones) if (p !== q) p.contacts += q
    }

    /**
     * Connects two phones through chunked frames of [mtu] bytes, the way GATT carries
     * them, and runs the meeting to the end.
     */
    private fun meet(a: Phone, b: Phone, mtu: Int = 185) {
        val toB = ArrayDeque<ByteArray>()
        val toA = ArrayDeque<ByteArray>()
        val sa = MeshSession(a.store, { a.tags(now) }, { a.got += it.data }, { f -> toB.addAll(MeshProtocol.chunk(f, mtu - 3)) }, { now }, initiator = true)
        val sb = MeshSession(b.store, { b.tags(now) }, { b.got += it.data }, { f -> toA.addAll(MeshProtocol.chunk(f, mtu - 3)) }, { now }, initiator = false)
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
        val b = MeshProtocol.newBundle(envelope(2_000), tag(), now)!!
        val frame = MeshProtocol.encode(MeshProtocol.Frame.Carry(b))
        val r = MeshProtocol.Reassembler()
        var whole: ByteArray? = null
        for (c in MeshProtocol.chunk(frame, 20)) r.add(c)?.let { whole = it }
        val back = MeshProtocol.decode(whole!!) as MeshProtocol.Frame.Carry
        assertArrayEquals(b.data, back.bundle.data)
        assertArrayEquals(b.tag, back.bundle.tag)
        assertNull(MeshProtocol.decode(byteArrayOf(9, 9, 9)))
        assertNull(MeshProtocol.decode(frame.copyOf(frame.size - 1)))
        assertNull(MeshProtocol.newBundle(envelope(MeshProtocol.MAX_ENVELOPE + 1), tag(), now))
        assertNull(MeshProtocol.newBundle(envelope(), ByteArray(8), now))
    }

    @Test
    fun aMessageHopsThroughAStrangerToItsRecipient() {
        val alice = Phone("alice", store())
        val stranger = Phone("stranger", store())
        val bob = Phone("bob", store())
        friends(alice, bob)
        val env = envelope()
        assertTrue(alice.store.addOwn("bob", "m1", alice.bundleFor(bob, env, now), now))
        assertFalse("queued twice", alice.store.addOwn("bob", "m1", alice.bundleFor(bob, envelope(), now), now))

        meet(alice, stranger)
        assertTrue(stranger.got.isEmpty())
        assertEquals(1, stranger.store.size)

        now += 3_600_000L
        meet(bob, stranger)
        assertEquals(1, bob.got.size)
        assertArrayEquals(env, bob.got[0])
        // Delivered, and carried on all the same; a second copy is not handed over again.
        assertEquals(1, bob.store.size)
        meet(alice, bob)
        assertEquals(1, bob.got.size)
    }

    @Test
    fun bothSidesTradeInOneMeetingAndNothingTravelsTwice() {
        val a = Phone("a", store())
        val b = Phone("b", store())
        val c = Phone("c", store())
        friends(a, b, c)
        a.store.addOwn("b", "1", a.bundleFor(b, envelope(), now), now)
        a.store.addOwn("c", "2", a.bundleFor(c, envelope(), now), now)
        b.store.addOwn("a", "3", b.bundleFor(a, envelope(), now), now)
        meet(a, b)
        assertEquals(1, a.got.size)
        assertEquals(1, b.got.size)
        // b carries c's now, the one it was handed, and still its own for a: nothing tells it a has it.
        assertEquals(3, b.store.size)
        meet(a, b)
        assertEquals(1, a.got.size)
        assertEquals(1, b.got.size)
    }

    @Test
    fun oldAndFarTravelledBundlesAreDropped() {
        val s = store()
        val b = MeshProtocol.newBundle(envelope(), tag(), now)!!
        assertFalse(s.add(b.copy(hops = MeshProtocol.MAX_HOPS + 1), now))
        assertTrue(s.add(b, now))
        assertEquals(1, s.ids(now).size)
        assertEquals(0, s.ids(now + MeshProtocol.TTL_MS + 1).size)
    }

    @Test
    fun theStoreSurvivesARestart() {
        val dir = Files.createTempDirectory("mesh").toFile().apply { deleteOnExit() }
        val f = File(dir, "m.json")
        MeshStore(Store(f, cipher = TestStoreCipher)).addOwn("x", "1", MeshProtocol.newBundle(envelope(), tag(), now)!!, now)
        val again = MeshStore(Store(f, cipher = TestStoreCipher))
        assertEquals(1, again.size)
        assertTrue(again.isQueued("x", "1"))
    }

    @Test
    fun tagsChangeDailyAndAreAnsweredAroundMidnight() {
        val alice = Phone("alice", store())
        val bob = Phone("bob", store())
        friends(alice, bob)
        val today = alice.identity.meshTag(bob.sealing, MeshProtocol.day(now))
        val tomorrow = alice.identity.meshTag(bob.sealing, MeshProtocol.day(now) + 1)
        assertFalse(today.contentEquals(tomorrow))
        assertTrue(MeshProtocol.hex(tomorrow) in bob.tags(now))
        assertTrue(MeshProtocol.hex(today) in bob.tags(now))
        assertFalse(MeshProtocol.hex(today) in bob.tags(now + 3 * 86_400_000L))
    }

    /**
     * The hole Beta 5 had: another contact of Bob's, holding Bob's public keys,
     * could tell a bundle being carried was for him. The pairwise tag is one only
     * Alice and Bob can compute.
     */
    @Test
    fun anotherContactOfTheRecipientCannotTellABundleIsForThem() {
        val alice = Phone("alice", store())
        val bob = Phone("bob", store())
        val carol = Phone("carol", store())
        friends(alice, bob)
        friends(carol, bob)
        val bundle = alice.bundleFor(bob, envelope(), now)
        val seen = MeshProtocol.hex(bundle.tag)
        assertTrue(seen in bob.tags(now))
        // Everything Carol can work out from Bob's public sealing key.
        assertFalse(seen in carol.tags(now))
        assertFalse(seen in MeshProtocol.around(now) { legacyTag(bob.sealing, it) })
        assertFalse(seen in MeshProtocol.around(now) { carol.identity.meshTag(bob.sealing, it) })
        // And Carol carries it on without recognising it.
        alice.store.addOwn("bob", "m1", bundle, now)
        meet(alice, carol)
        assertTrue(carol.got.isEmpty())
        assertEquals(1, carol.store.size)
        meet(carol, bob)
        assertEquals(1, bob.got.size)
    }

    /** Beta 5's tag, from the recipient's public key alone: whoever holds that key could make one. */
    private fun legacyTag(sealingB64: String, day: Long): ByteArray {
        val key = java.util.Base64.getDecoder().decode(sealingB64)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("clairix-mesh-tag-v1".toByteArray()); md.update(key); md.update(java.nio.ByteBuffer.allocate(8).putLong(day).array())
        return md.digest().copyOf(MeshProtocol.TAG_LEN)
    }

    /**
     * Anyone holding Bob's public key (his relay, any contact) could make a bundle
     * under Beta 5's tag and see which phone in a crowd took it. It is no longer
     * answered: such a bundle is carried by Bob's phone like anyone's.
     */
    @Test
    fun aBundleUnderTheOldPublicKeyTagSinglesNobodyOut() {
        val bob = Phone("bob", store())
        val alice = Phone("alice", store())
        val prober = Phone("prober", store())
        friends(alice, bob)
        val probe = MeshProtocol.newBundle(envelope(), legacyTag(bob.sealing, MeshProtocol.day(now)), now)!!
        assertFalse(MeshProtocol.hex(probe.tag) in bob.tags(now))
        prober.store.addOwn("bob", "1", probe, now)
        meet(prober, bob)
        assertTrue(bob.got.isEmpty())
        assertEquals(1, bob.store.size)
    }

    /**
     * A peer that connects with an empty hello is sent everything listed: a
     * bundle handed to this phone's owner is listed and sent like the rest, so
     * listing without sending cannot mark the phone it was for.
     */
    @Test
    fun aBundleForThisPhoneIsPassedOnLikeAnyOther() {
        val alice = Phone("alice", store())
        val bob = Phone("bob", store())
        val watcher = Phone("watcher", store())
        friends(alice, bob)
        alice.store.addOwn("bob", "m1", alice.bundleFor(bob, envelope(), now), now)
        meet(alice, bob)
        assertEquals(1, bob.got.size)
        val listed = bob.store.knownIds(now).map { MeshProtocol.hex(it) }.toSet()
        meet(watcher, bob)
        val sent = watcher.store.ids(now).map { MeshProtocol.hex(it) }.toSet()
        assertEquals(listed, sent)
    }

    /** A bundle's writer is not given away by its hop count or the minute it was written. */
    @Test
    fun aNewBundleDoesNotSayWhoWroteItOrWhen() {
        val hops = (1..200).map { MeshProtocol.newBundle(envelope(), tag(), now + it)!!.hops }.toSet()
        assertEquals(setOf(1, 2, 3), hops)
        val b = MeshProtocol.newBundle(envelope(), tag(), now)!!
        assertEquals(0L, b.createdMs % 3_600_000L)
        assertTrue(b.createdMs <= now && now - b.createdMs < 3_600_000L)
    }

    /** Strangers flooding a phone push out each other's bundles, never the owner's own. */
    @Test
    fun aFloodDoesNotPushOutTheOwnersOwnBundles() {
        val s = store()
        val mine = MeshProtocol.newBundle(envelope(), tag(), now)!!
        assertTrue(s.addOwn("bob", "sos", mine, now))
        repeat(MeshProtocol.MAX_STORE + 50) { s.add(MeshProtocol.newBundle(envelope(64), tag(), now)!!, now) }
        assertEquals(MeshProtocol.MAX_STORE, s.size)
        assertTrue(s.ids(now).any { it.contentEquals(mine.id) })
        // Nor can one tag take the store over.
        val t = tag()
        val s2 = store()
        repeat(MeshStore.MAX_PER_TAG + 10) { s2.add(MeshProtocol.newBundle(envelope(64), t, now)!!, now) }
        assertEquals(MeshStore.MAX_PER_TAG, s2.size)
    }

    /** One meeting takes at most so many bundles from a peer. */
    @Test
    fun oneMeetingCannotFloodAPhone() {
        val s = store()
        val session = MeshSession(s, { emptySet() }, {}, {}, { now }, initiator = false)
        var accepted = true
        for (i in 0..MeshSession.MAX_ADDS) {
            accepted = session.onFrame(MeshProtocol.encode(MeshProtocol.Frame.Carry(MeshProtocol.newBundle(envelope(64), tag(), now)!!)))
        }
        assertFalse("the meeting ends past the cap", accepted)
    }
}
