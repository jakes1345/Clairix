package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disappearing messages on the device, in the real SQLCipher database (one of
 * its own, so the app's is never touched): when each message's clock starts,
 * that an expired message is never shown even before the sweep, that the sweep
 * deletes it, and how two timer changes settle.
 */
@RunWith(AndroidJUnit4::class)
class DisappearingMessagesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "comms_disappearing_test.db"
    private lateinit var store: CommsStore
    private val peer = "482913605"

    @Before
    fun open() {
        context.deleteDatabase(name)
        store = CommsStore(context, name)
        store.upsertContact(
            Contact(peer, "Ana", "ed-ana", "curve-ana", "seal-ana", "sig-ana", verified = true, addedTs = 1L)
        )
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun msg(id: String, dir: Direction, ttl: Int, read: Boolean = dir == Direction.OUT, ts: Long = System.currentTimeMillis()) =
        ChatMessage(id, peer, dir, "body $id", ts, if (dir == Direction.OUT) "sent" else "received", read = read, ttl = ttl)

    @Test
    fun aSentMessageCountsDownAtOnceAndAReceivedOneOnceRead() {
        val before = System.currentTimeMillis()
        store.insertMessage(msg("out", Direction.OUT, 3600))
        store.insertMessage(msg("in", Direction.IN, 3600))
        store.insertMessage(msg("stays", Direction.IN, 0))
        val out = store.message("out")!!
        assertNotNull(out.expiresAt)
        assertTrue(out.expiresAt!! in before + 3_600_000..System.currentTimeMillis() + 3_600_000)
        assertNull("an unread message's clock has not started", store.message("in")!!.expiresAt)
        store.markRead(peer)
        val read = store.message("in")!!
        assertNotNull(read.expiresAt)
        assertTrue(read.expiresAt!! >= before + 3_600_000)
        assertNull("a message with no timer never goes", store.message("stays")!!.expiresAt)
        assertEquals(store.message("out")!!.expiresAt!!.coerceAtMost(read.expiresAt!!), store.nextExpiry())
    }

    @Test
    fun anExpiredMessageIsNeverShownAndTheSweepDeletesIt() {
        store.insertMessage(msg("old", Direction.OUT, 60).copy(expiresAt = System.currentTimeMillis() - 1))
        store.insertMessage(msg("new", Direction.OUT, 3600))
        // Not yet swept, already gone from every read.
        assertEquals(listOf("new"), store.messages(peer).map { it.id })
        assertEquals("new", store.threads().single().lastMessage?.id)
        val gone = store.expireMessages(System.currentTimeMillis())
        assertEquals(listOf("old"), gone.map { it.first })
        assertNull(store.message("old"))
        assertTrue(store.message("new") != null)
        assertTrue(store.expireMessages(System.currentTimeMillis()).isEmpty())
        // Gone from the file too, not just hidden.
        val rows = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE id = 'old'", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, rows)
    }

    @Test
    fun aGroupMessageCountsDownOnceTheGroupIsRead() {
        val gid = "group-1"
        store.insertGroupMessage(gid, ChatMessage("g1", gid, Direction.IN, "hi", System.currentTimeMillis(), "received", read = false, sender = peer, ttl = 300))
        assertNull(store.message("g1")!!.expiresAt)
        store.markGroupRead(gid)
        assertNotNull(store.message("g1")!!.expiresAt)
    }

    @Test
    fun theLaterTimerChangeWins() {
        assertEquals(0, store.timer(peer))
        assertFalse("turning off what was never on changes nothing", store.setTimer(peer, 0, 100))
        assertTrue(store.setTimer(peer, 3600, 200))
        assertEquals(3600, store.timer(peer))
        assertFalse("an older change arriving late is ignored", store.setTimer(peer, 300, 150))
        assertEquals(3600, store.timer(peer))
        assertFalse("the same change again", store.setTimer(peer, 3600, 200))
        assertTrue(store.setTimer(peer, 0, 300))
        assertEquals(0, store.timer(peer))
    }

    @Test
    fun deletedRowsAreOverwrittenInTheFile() {
        val on = store.readableDatabase.rawQuery("PRAGMA secure_delete", null).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, on)
    }
}
