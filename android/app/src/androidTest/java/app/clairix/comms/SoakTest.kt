package app.clairix.comms

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.random.Random

/**
 * A long conversation against the two relays, as a phone has over days:
 * texts both ways with a contact on the same relay and one on the other (the
 * self-hosted relay in CI), photos now and then, the vault sealing and
 * opening as the screen goes off and on, and the socket dropped as a phone
 * changing networks drops it. Nothing may be lost or arrive twice, and the
 * app's memory may not keep growing.
 *
 *   am instrument -e class app.clairix.comms.SoakTest -e soakMinutes 8 \
 *     -e relayA http://127.0.0.1:8787 -e relayB http://127.0.0.1:8788 -e secret <ENROLL_SECRET>
 */
@RunWith(AndroidJUnit4::class)
class SoakTest {

    private val repo = CommsRepository
    private val args = InstrumentationRegistry.getArguments()
    private val peers = ArrayList<TestPeer>()

    @After
    fun tearDown() {
        peers.forEach { runCatching { it.close() } }
        runCatching { runBlocking { if (repo.isRegistered()) repo.unpair() } }
    }

    @Test
    fun aLongConversationLosesNothing() = runBlocking {
        val minutes = args.getString("soakMinutes")?.toLongOrNull()
        assumeTrue("pass soakMinutes, relayA, relayB and secret to run the soak test", minutes != null && args.getString("relayA") != null && args.getString("secret") != null)
        val relayA = args.getString("relayA")!!
        val relayB = args.getString("relayB") ?: relayA
        val secret = args.getString("secret")!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repo.init(context)
        if (repo.isRegistered()) repo.unpair()
        repo.register(relayA, secret, "Soak", listed = true).getOrThrow()
        val app = repo.pairingCode()!!
        val near = TestPeer(relayA, secret, "Near", app).also { peers += it }
        val far = TestPeer(relayB, secret, "Far", app).also { peers += it }
        repo.addContactFromCode(near.code("Near"), "Near", CommsRepository.CodeSource.SCANNED).getOrThrow()
        repo.addContactFromCode(far.code("Far"), "Far", CommsRepository.CodeSource.SCANNED).getOrThrow()
        val farRelay = RelayClient.originOf(relayA)

        val sentByApp = HashMap<String, Int>()   // body -> times a peer received it
        val sentToApp = HashSet<String>()
        val random = Random(20261026)
        val end = System.currentTimeMillis() + minutes!! * 60_000
        var round = 0
        var baseline = 0L
        while (System.currentTimeMillis() < end) {
            round++
            val peer = if (round % 2 == 0) far else near
            val appRelay = if (peer === far) farRelay else null

            // The app writes, the peer reads it.
            val out = "soak $round from the app " + "x".repeat(random.nextInt(0, 400))
            repo.send(peer.number, out).getOrThrow()
            val got = peer.receive(90_000) { CommsWire.type(it) == CommsWire.T_MSG && it.optString(CommsWire.F_BODY) == out }
            sentByApp[got.getString(CommsWire.F_BODY)] = (sentByApp[out] ?: 0) + 1

            // Every tenth round the screen goes off: what the peers send waits
            // sealed in the locked inbox until it comes back on.
            if (round % 10 == 0) {
                Vault.sealForTest()
                eventually("the vault seals") { repo.contacts().isEmpty() }
                repeat(3) { i ->
                    val body = "soak $round while locked $i"
                    peer.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), body), appRelay)
                    sentToApp += body
                }
                Vault.open()
            }

            // Every seventh, the network changes under the live socket.
            if (round % 7 == 0) LiveLink.onNetwork("soak-network-$round")

            // The peer writes back.
            val back = "soak $round from ${if (peer === far) "far" else "near"}"
            peer.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), back), appRelay)
            sentToApp += back

            // Every fifteenth, a photo in three chunks.
            if (round % 15 == 0) {
                val photo = random.nextBytes(CommsWire.MEDIA_CHUNK_BYTES * 2 + random.nextInt(1, 20_000))
                repo.sendMedia(peer.number, CommsMedia.Prepared(photo, "image/jpeg", 640, 480, 0, ""), "soak photo $round").getOrThrow()
                val header = peer.receive(90_000) { CommsWire.type(it) == CommsWire.T_MEDIA }
                assertEquals("soak photo $round", header.getString(CommsWire.F_CAPTION))
                repeat(header.getInt(CommsWire.F_CHUNKS)) { peer.receive(90_000) { CommsWire.type(it) == CommsWire.T_MEDIA_CHUNK } }
            }

            if (round == WARMUP_ROUNDS) baseline = usedMemory()
            if (round % 10 == 0) Log.i(TAG, "round $round, memory ${usedMemory() / 1024} KiB")
        }

        // Everything the peers sent is in the app, once.
        eventually("every message to the app arrives", 180_000) {
            val bodies = (repo.messages(near.number) + repo.messages(far.number)).filter { it.direction == Direction.IN }.map { it.body }
            sentToApp.all { it in bodies }
        }
        val inbound = (repo.messages(near.number) + repo.messages(far.number)).filter { it.direction == Direction.IN && it.body.startsWith("soak ") }
        assertEquals("a message to the app arrived twice", inbound.size, inbound.map { it.body }.toSet().size)
        assertEquals(sentToApp, inbound.map { it.body }.toSet())
        assertTrue("every message from the app arrived once", sentByApp.values.all { it == 1 })
        // A second copy of anything the app sent would still be waiting at a peer.
        val leftovers = (near.drain(10_000) + far.drain(10_000))
            .filter { CommsWire.type(it) == CommsWire.T_MSG && it.optString(CommsWire.F_BODY).startsWith("soak ") }
        assertTrue("messages from the app arrived twice: ${leftovers.map { it.optString(CommsWire.F_BODY) }}", leftovers.isEmpty())

        assertTrue("only $round rounds ran", round > WARMUP_ROUNDS)
        val growth = usedMemory() - baseline
        Log.i(TAG, "$round rounds; memory grew ${growth / 1024} KiB after warm-up")
        assertTrue("memory grew ${growth / (1024 * 1024)} MiB over $round rounds", growth < MAX_GROWTH_BYTES)
    }

    /** The app's heap in use, after asking for a collection, plus native allocations. */
    private fun usedMemory(): Long {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc(); System.runFinalization() }
        return (rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 60_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            repo.sync()
            if (check()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what: not within ${timeoutMs / 1000} s")
            kotlinx.coroutines.delay(500)
        }
    }

    private companion object {
        const val TAG = "SoakTest"
        const val WARMUP_ROUNDS = 5
        /** Room for caches filling and the heap settling; a leak per round outgrows it. */
        const val MAX_GROWTH_BYTES = 48L * 1024 * 1024
    }
}
