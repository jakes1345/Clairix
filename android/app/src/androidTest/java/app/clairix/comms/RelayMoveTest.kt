package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.UUID

/**
 * Moving between relays, on the device, against the same two real relays as
 * [EndToEndTest] (`-e relayA … -e relayB … -e secret …`).
 *
 * The app registers on relay A with two contacts it has already talked to:
 * Near, on relay A, and Far, on relay B. Then, in order: the app moves to
 * relay B and both contacts are told, over the sessions they share, and reach
 * it there, while what Near still sends to the old number is collected from
 * relay A; Far moves to relay A and the app follows it, conversation and
 * verification kept; and the app moves back to relay A, taking up its old
 * number again without a secret. The last test unpairs, which deletes the
 * app's accounts on both relays.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RelayMoveTest {

    private val repo = CommsRepository

    @Test
    fun a_theAppMovesAndItsContactsFollow() = runBlocking {
        setUp()
        // Sessions both ways first, as between contacts who have talked.
        repo.send(near.number, "before the move").getOrThrow()
        near.receive { isMessage(it, "before the move") }
        repo.send(far.number, "before the move").getOrThrow()
        far.receive { isMessage(it, "before the move") }
        near.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "hello from Near"))
        far.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "hello from Far"), appRelay = originA)
        eventually("both contacts' messages arrive") { received(near.number, "hello from Near") && received(far.number, "hello from Far") }

        val old = app
        val number = repo.moveTo(relayB, secret).getOrThrow()
        val moved = repo.pairingCode()!!
        app = moved
        assertEquals(number, moved.number)
        assertEquals(originB, RelayClient.originOf(moved.relayUrl))
        assertEquals("the identity stays", old.ed25519, moved.ed25519)
        assertEquals(old.curve25519, moved.curve25519)

        // Each contact is told where the app is now, in the notice's sender fields.
        for (peer in listOf(near, far)) {
            val notice = peer.receive { CommsWire.type(it) == CommsWire.T_MOVED }
            assertEquals(number, notice.getString(CommsWire.F_FROM))
            assertEquals(originB, notice.getString(CommsWire.F_RELAY))
            assertTrue("the notice is dated", notice.getLong(CommsWire.F_TS) > 0)
        }
        eventually("every notice has gone") { repo.moveNoticesOwed() == 0 }

        // Near is now on another relay than the app's; Far on the same one.
        assertEquals(originA, repo.contact(near.number)!!.relay)
        assertNull(repo.contact(far.number)!!.relay)
        assertTrue("a verified contact stays verified", repo.contact(near.number)!!.verified)

        // Both reach the app at its new number, and it reaches them.
        near.send(moved, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "to the new number, from relay A"), appRelay = originB)
        far.send(moved, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "to the new number, from relay B"))
        eventually("both reach the new number") {
            received(near.number, "to the new number, from relay A") && received(far.number, "to the new number, from relay B")
        }
        repo.send(near.number, "from the new relay").getOrThrow()
        assertEquals(number, near.receive { isMessage(it, "from the new relay") }.getString(CommsWire.F_FROM))
        repo.send(far.number, "from the new relay").getOrThrow()
        assertEquals(number, far.receive { isMessage(it, "from the new relay") }.getString(CommsWire.F_FROM))

        // A contact whose phone had not followed yet still writes to the old
        // number: the app collects it from relay A.
        assertEquals(RelayClient.originOf(relayA), RelayClient.originOf(repo.previousRelay()!!.first))
        assertEquals(old.number, repo.previousRelay()!!.second)
        near.send(old, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "to the old number"))
        eventually("what went to the old number is collected from the old relay") { received(near.number, "to the old number") }
    }

    @Test
    fun b_aContactMovesAndTheAppFollows() = runBlocking {
        setUp()
        val oldNumber = far.number
        val history = repo.messages(oldNumber).size
        assertTrue("there is a conversation to keep", history > 0)
        val newNumber = far.moveTo(relayA, appRelayUrl = relayB)
        far.send(app, CommsWire.moved(System.currentTimeMillis()), appRelay = originB)
        eventually("the app follows Far to relay A") {
            repo.contact(newNumber)?.relay == originA && repo.contact(oldNumber).let { it == null || newNumber == oldNumber }
        }
        val contact = repo.contact(newNumber)!!
        assertTrue("Far stays verified: the keys did not change", contact.verified)
        val conversation = repo.messages(newNumber)
        assertTrue("the conversation came along", conversation.count { it.body == "before the move" } == 1)
        assertTrue("the move is noted in it", conversation.any { it.status == STATUS_SYSTEM && it.body.contains("moved to another relay") })

        repo.send(newNumber, "to where you are now").getOrThrow()
        far.receive { isMessage(it, "to where you are now") }
        far.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "from relay A now"), appRelay = originB)
        eventually("Far's message from its new relay arrives") { received(newNumber, "from relay A now") }
    }

    @Test
    fun c_movingBackTakesUpTheOldNumber() = runBlocking {
        setUp()
        val (previousUrl, previousNumber) = repo.previousRelay()!!
        assertEquals(originA, RelayClient.originOf(previousUrl))
        val leaving = app
        val number = repo.moveTo(relayA, "").getOrThrow()
        assertEquals("the number on relay A is the one this phone had there", previousNumber, number)
        app = repo.pairingCode()!!
        assertEquals(originA, RelayClient.originOf(app.relayUrl))
        assertEquals("relay B is now the one left", leaving.number, repo.previousRelay()!!.second)

        val notice = near.receive { CommsWire.type(it) == CommsWire.T_MOVED }
        assertEquals(number, notice.getString(CommsWire.F_FROM))
        assertEquals(originA, notice.getString(CommsWire.F_RELAY))
        // Both contacts are on relay A now, the app's own.
        assertNull(repo.contact(near.number)!!.relay)
        assertNull(repo.contact(far.number)!!.relay)

        near.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "welcome back"))
        eventually("Near reaches the old number again") { received(near.number, "welcome back") }
        repo.send(far.number, "back on relay A").getOrThrow()
        assertEquals(number, far.receive { isMessage(it, "back on relay A") }.getString(CommsWire.F_FROM))
    }

    @Test
    fun z_unpairing() = runBlocking {
        setUp()
        repo.unpair()
        assertTrue(!repo.isRegistered())
        assertNull(repo.previousRelay())
    }

    // ── Shared set-up ─────────────────────────────────────────────────────

    private fun isMessage(json: JSONObject, body: String) =
        CommsWire.type(json) == CommsWire.T_MSG && json.optString(CommsWire.F_BODY) == body

    private fun received(peer: String, body: String) =
        repo.messages(peer).any { it.direction == Direction.IN && it.body == body }

    private fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass relayA, relayB and secret to run the relay move tests", args.getString("relayA") != null && args.getString("relayB") != null && args.getString("secret") != null)
        if (started) return
        synchronized(RelayMoveTest::class.java) {
            if (started) return
            relayA = args.getString("relayA")!!
            relayB = args.getString("relayB")!!
            secret = args.getString("secret")!!
            originA = RelayClient.originOf(relayA)!!
            originB = RelayClient.originOf(relayB)!!
            runBlocking {
                repo.init(InstrumentationRegistry.getInstrumentation().targetContext)
                if (repo.isRegistered()) repo.unpair()
                repo.register(relayA, secret, "Mover", listed = true).getOrThrow()
                app = repo.pairingCode()!!
                near = TestPeer(relayA, secret, "Near", app)
                far = TestPeer(relayB, secret, "Far", app)
                repo.addContactFromCode(near.code("Near"), "Near", CommsRepository.CodeSource.SCANNED).getOrThrow()
                repo.addContactFromCode(far.code("Far"), "Far", CommsRepository.CodeSource.SCANNED).getOrThrow()
            }
            started = true
        }
    }

    /** Syncs the app until [check] holds, or fails after [timeoutMs]. */
    private suspend fun eventually(what: String, timeoutMs: Long = 60_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            repo.sync()
            if (check()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what: not within ${timeoutMs / 1000} s")
            kotlinx.coroutines.delay(500)
        }
    }

    companion object {
        @Volatile private var started = false
        private lateinit var relayA: String
        private lateinit var relayB: String
        private lateinit var secret: String
        private lateinit var originA: String
        private lateinit var originB: String
        private lateinit var app: PairingCode
        private lateinit var near: TestPeer
        private lateinit var far: TestPeer

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (!started) return
            runCatching { near.close() }
            runCatching { far.close() }
        }
    }
}
