package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The blocked list across a move to another relay, in the real SQLCipher
 * database (one of its own): a number blocked on the old relay stays blocked
 * there, and the same digits on the new relay are not.
 */
@RunWith(AndroidJUnit4::class)
class BlockedListMoveTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "comms_blocked_move_test.db"
    private lateinit var store: CommsStore
    private val oldRelay = "https://old.example.org"
    private val newRelay = "https://new.example.org"

    @Before
    fun open() {
        context.deleteDatabase(name)
        store = CommsStore(context, name)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test
    fun aNumberBlockedOnTheOldRelayStaysBlockedThereAfterAMove() {
        // Blocked while this phone was on the old relay: stored as "this phone's relay".
        store.addBlocked("ed-abuser", "curve-abuser", "482913605", null, "", 1L)
        // Someone on the relay this phone moves to, blocked from here before the move.
        store.addBlocked("ed-other", "curve-other", "111222333", newRelay, "", 2L)
        store.moveSelf("900000001", "900000002", oldRelay, newRelay)
        val list = store.blockedKeys()
        assertEquals(oldRelay, list.single { it.number == "482913605" }.relay)
        assertEquals(null, list.single { it.number == "111222333" }.relay)
        // By number alone: blocked on the old relay, not on the new one.
        assertTrue(ContactGate.isBlocked(list, null, null, "482913605", oldRelay))
        assertFalse(ContactGate.isBlocked(list, null, null, "482913605", null))
        assertTrue(ContactGate.isBlocked(list, null, null, "111222333", null))
    }
}
