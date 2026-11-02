package app.clairix.comms.lora

import app.clairix.comms.lora.LoraMessenger.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The phone's Lite messenger, two of them over a radio that can lose frames: a text
 * is acknowledged and marked delivered, a lost one is sent again sealed afresh, a
 * copy is answered but shown once, strangers are never opened, and what was still
 * being sent when the app stopped comes back failed, to be sent again. The sealing
 * here is a stand-in that only checks who a body is from and for; the real one is
 * Rust's (comms-crypto/src/lora.rs), checked byte for byte against the radio there.
 */
class LoraMessengerTest {

    private val now0 = 1_793_577_600_000L
    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }
    private val keyC = ByteArray(32) { 3 }

    /** Seals as "from(32) | to(32) | counter(4) | inner", so each seal of one inner part differs. */
    private class FakeCodec(private val me: ByteArray) : LiteCodec {
        private var counter = 0
        override fun encode(kind: Int, id: Long, sentMinutes: Long, payload: ByteArray): ByteArray? {
            if (payload.size > LoraMessenger.TEXT_MAX) return null
            return ByteBuffer.allocate(9 + payload.size).put(kind.toByte()).putInt(id.toInt()).putInt(sentMinutes.toInt()).put(payload).array()
        }
        override fun decode(inner: ByteArray): LiteCodec.Inner? {
            if (inner.size < 9) return null
            val b = ByteBuffer.wrap(inner)
            val kind = b.get().toInt()
            val id = b.int.toLong() and 0xFFFFFFFFL
            val minutes = b.int.toLong() and 0xFFFFFFFFL
            return LiteCodec.Inner(kind, id, minutes, ByteArray(b.remaining()).also { b.get(it) })
        }
        override fun seal(peer: ByteArray, inner: ByteArray): ByteArray =
            ByteBuffer.allocate(68 + inner.size).put(me).put(peer).putInt(counter++).put(inner).array()
        override fun open(body: ByteArray, contacts: List<ByteArray>): Pair<ByteArray, ByteArray>? {
            if (body.size < 68 || !body.copyOfRange(32, 64).contentEquals(me)) return null
            val from = body.copyOfRange(0, 32)
            if (contacts.none { it.contentEquals(from) }) return null
            return from to body.copyOfRange(68, body.size)
        }
        override fun sosPayload(position: Pair<Int, Int>?, text: String): ByteArray {
            val t = text.toByteArray()
            return if (position == null) byteArrayOf(0) + t
            else ByteBuffer.allocate(9 + t.size).put(1).putInt(position.first).putInt(position.second).put(t).array()
        }
        override fun parseSos(payload: ByteArray): LiteCodec.Sos? {
            if (payload.isEmpty()) return null
            if (payload[0].toInt() == 0) return LiteCodec.Sos(null, String(payload, 1, payload.size - 1))
            if (payload.size < 9) return null
            val b = ByteBuffer.wrap(payload, 1, payload.size - 1)
            val pos = b.int to b.int
            return LiteCodec.Sos(pos, String(payload, 9, payload.size - 9))
        }
    }

    /** Two messengers and the air between them; [lose] drops what is sent while it is true. */
    private inner class Pair2 {
        var lose = false
        var radioUp = true
        val toA = ArrayList<ByteArray>()
        val toB = ArrayList<ByteArray>()
        val a = LoraMessenger(FakeCodec(keyA), { body -> if (!radioUp) false else { if (!lose) toB += body; true } }, java.util.Random(1))
        val b = LoraMessenger(FakeCodec(keyB), { body -> if (!lose) toA += body; true }, java.util.Random(2))
        init {
            a.addContact(keyB, "Bea", now0, keyA)
            b.addContact(keyA, "Al", now0, keyB)
        }
        fun deliver(now: Long) {
            while (toA.isNotEmpty() || toB.isNotEmpty()) {
                toB.toList().also { toB.clear() }.forEach { b.onLite(it, -90, now) }
                toA.toList().also { toA.clear() }.forEach { a.onLite(it, -95, now) }
            }
        }
    }

    private val hexB = hex(keyB)
    private val hexA = hex(keyA)

    @Test
    fun aTextIsDeliveredAndAcknowledged() {
        val p = Pair2()
        var heard: LoraMessenger.Message? = null
        p.b.onIncoming = { _, m -> heard = m }
        val sent = p.a.sendText(hexB, "Meet at the north gate", now0)!!
        assertEquals(State.SENT, sent.state)
        p.deliver(now0)
        assertEquals("Meet at the north gate", heard!!.text)
        assertEquals(-90, heard!!.rssi)
        assertEquals(1, p.b.contact(hexA)!!.unread)
        assertEquals(State.DELIVERED, p.a.messages(hexB).single().state)
        // Delivered: no more tries go out.
        p.a.tick(now0 + 3_600_000L)
        assertTrue(p.toB.isEmpty())
    }

    @Test
    fun aLostTextIsSentAgainSealedAfreshAndShownOnce() {
        val p = Pair2()
        p.lose = true
        p.a.sendText(hexB, "Are you there?", now0)
        p.a.tick(now0 + 5_000L)
        assertTrue("nothing is due before 20 s", p.toB.isEmpty())
        p.lose = false
        p.a.tick(now0 + 20_000L)
        assertEquals(1, p.toB.size)
        p.deliver(now0 + 20_000L)
        assertEquals(1, p.b.messages(hexA).size)
        assertEquals(State.DELIVERED, p.a.messages(hexB).single().state)

        // A copy (the Ack was lost, so the sender tried once more) is answered again but not shown twice.
        val q = Pair2()
        q.a.sendText(hexB, "One", now0)
        val first = q.toB.single()
        q.toB.clear()
        q.b.onLite(first, null, now0)
        q.toA.clear() // that Ack is lost
        q.a.tick(now0 + 20_000L)
        val second = q.toB.single()
        assertFalse(first.contentEquals(second))
        q.deliver(now0 + 20_000L)
        assertEquals(1, q.b.messages(hexA).size)
        assertEquals(State.DELIVERED, q.a.messages(hexB).single().state)
    }

    @Test
    fun withNoAnswerAfterTheLastTryItFailsAndCanBeSentAgain() {
        val p = Pair2()
        p.lose = true
        p.a.sendText(hexB, "Hello?", now0)
        var t = now0
        for (wait in LoraMessenger.RETRY_MS) { t += wait; p.a.tick(t) }
        t += LoraMessenger.RETRY_MS.last()
        p.a.tick(t)
        val failed = p.a.messages(hexB).single()
        assertEquals(State.FAILED, failed.state)
        p.lose = false
        val again = p.a.resend(failed, t)!!
        p.deliver(t)
        assertEquals(State.DELIVERED, p.a.messages(hexB).first { it.id == again.id }.state)
    }

    @Test
    fun withNoRadioItWaitsAndGoesOutOnTheNextTry() {
        val p = Pair2()
        p.radioUp = false
        assertEquals(State.SENDING, p.a.sendText(hexB, "Queued", now0)!!.state)
        p.radioUp = true
        p.a.tick(now0 + 20_000L)
        assertEquals(State.SENT, p.a.messages(hexB).single().state)
    }

    @Test
    fun anSosCarriesItsPosition() {
        val p = Pair2()
        var heard: LoraMessenger.Message? = null
        p.b.onIncoming = { _, m -> heard = m }
        p.a.sendSos(hexB, 406_353_908 to -739_846_000, "Hurt, need help", now0)
        p.deliver(now0)
        assertEquals(LoraMessenger.KIND_SOS, heard!!.kind)
        assertTrue(heard!!.hasPosition)
        assertEquals(406_353_908, heard!!.lat7)
        assertEquals(-739_846_000, heard!!.lon7)
        assertEquals("Hurt, need help", heard!!.text)
    }

    @Test
    fun strangersAreNotOpenedAndLimitsHold() {
        val p = Pair2()
        val stranger = LoraMessenger(FakeCodec(keyC), { body -> p.toB += body; true })
        stranger.addContact(keyB, "B", now0, keyC)
        stranger.sendText(hexB, "Let me in", now0)
        p.deliver(now0)
        assertTrue(p.b.messages(hex(keyC)).isEmpty())
        assertTrue(p.b.messages(hexA).isEmpty())

        assertNull(p.a.sendText(hexB, "x".repeat(LoraMessenger.TEXT_MAX + 1), now0))
        assertNull(p.a.sendText(hexB, "   ", now0))
        assertNull(p.a.sendText(hex(keyC), "not a contact", now0))
        assertFalse(p.a.addContact(keyA, "me", now0, keyA))
        assertNotNull(p.a.sendText(hexB, "é".repeat(LoraMessenger.TEXT_MAX / 2), now0))
        assertEquals("ab", cutUtf8("abé", 3))
    }

    @Test
    fun whatWasBeingSentWhenTheAppStoppedComesBackFailed() {
        val p = Pair2()
        p.lose = true
        p.a.sendText(hexB, "In flight", now0)
        p.lose = false
        assertFalse(p.b.onLite(ByteArray(0), null, now0))
        val saved = p.a.toJson()
        val restored = LoraMessenger(FakeCodec(keyA), { true })
        restored.load(saved)
        assertTrue(restored.dirty)
        assertEquals(State.FAILED, restored.messages(hexB).single().state)
        assertEquals("Bea", restored.contact(hexB)!!.name)

        // Removing a contact takes their messages and stops their bodies being opened.
        p.deliver(now0)
        p.a.removeContact(hexB)
        assertTrue(p.a.messages(hexB).isEmpty())
        assertTrue(p.a.contacts().isEmpty())
    }
}
