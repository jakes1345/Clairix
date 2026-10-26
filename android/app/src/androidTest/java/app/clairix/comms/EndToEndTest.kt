package app.clairix.comms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import uniffi.clairix_comms_crypto.Identity
import uniffi.clairix_comms_crypto.PeerKeys
import uniffi.clairix_comms_crypto.fingerprint
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * The app on the device, end to end, against two real relays (wrangler dev)
 * on the computer the emulator runs on, reached through adb reverse:
 *
 *   am instrument -e relayA http://127.0.0.1:8787 -e relayB http://127.0.0.1:8788 -e secret <ENROLL_SECRET>
 *
 * The app registers on relay A through [CommsRepository], exactly as its setup
 * screen does, and talks to scripted peers ([TestPeer]: the app's own
 * [RelayClient] and crypto, driven by the test): one on relay A, and one on
 * relay B that is reached through A. Every message crosses a real relay,
 * spends a real blind token, is mixed, sealed and ratcheted, and is opened by
 * the app's own code on the way in. The tests run in order and share the one
 * registration; the last one unpairs.
 *
 * Skipped when the arguments are absent, so the smoke run needs no relays.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {

    private val repo = CommsRepository

    @Test
    fun a_theAppRegistersAndHoldsBlindTokens() {
        val app = setUp()
        assertTrue("registered", repo.isRegistered())
        val client = repo.relayClient()
        assertNotNull("relay A publishes blind-token keys", client.info().tokens)
        client.topUpTokens()
        assertTrue("tokens held", client.tokenCount() > 0)
        assertEquals("every token is blind", client.tokenCount(), client.blindTokenCount())
        assertTrue(app.number.isNotEmpty())
    }

    @Test
    fun b_textsGoBothWaysWithReceipts() = runBlocking {
        setUp()
        val peer = local
        repo.send(peer.number, "hello from the emulator").getOrThrow()
        val got = peer.receive { CommsWire.type(it) == CommsWire.T_MSG }
        assertEquals("hello from the emulator", got.getString(CommsWire.F_BODY))
        assertTrue("no timer yet", !got.has(CommsWire.F_TTL))
        val id = got.getString(CommsWire.F_ID)
        peer.send(app, CommsWire.receipt(listOf(id), CommsWire.STATUS_READ))
        eventually("the app's message shows read") { repo.messages(peer.number).any { it.id == id && it.status == "read" } }

        peer.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "hello from the peer"))
        eventually("the peer's message arrives in the app") {
            repo.messages(peer.number).any { it.direction == Direction.IN && it.body == "hello from the peer" }
        }
    }

    @Test
    fun c_aVoiceNoteAndAPhotoArriveWhole() = runBlocking {
        setUp()
        val peer = local
        val contact = repo.contact(peer.number)!!
        val audio = Base64.getEncoder().encodeToString(Random(7).nextBytes(3_000))
        repo.sendVoicemail(contact, audio, 1_500).getOrThrow()
        val vm = peer.receive { CommsWire.type(it) == CommsWire.T_VOICEMAIL }
        assertEquals(audio, vm.getString(CommsWire.F_AUDIO))
        assertEquals(1_500L, vm.getLong(CommsWire.F_DURATION))

        // Three chunks: header first in the app's queue, chunks after, in any order on arrival.
        val photo = Random(11).nextBytes(CommsWire.MEDIA_CHUNK_BYTES * 2 + 5_000)
        repo.sendMedia(peer.number, CommsMedia.Prepared(photo, "image/jpeg", 640, 480, 0, ""), "a test photo").getOrThrow()
        val header = peer.receive { CommsWire.type(it) == CommsWire.T_MEDIA }
        assertEquals(3, header.getInt(CommsWire.F_CHUNKS))
        assertEquals("a test photo", header.getString(CommsWire.F_CAPTION))
        val chunks = HashMap<Int, ByteArray>()
        while (chunks.size < 3) {
            val c = peer.receive { CommsWire.type(it) == CommsWire.T_MEDIA_CHUNK }
            chunks[c.getInt(CommsWire.F_INDEX)] = Base64.getDecoder().decode(c.getString(CommsWire.F_DATA))
        }
        assertArrayEquals(photo, (0 until 3).map { chunks[it]!! }.reduce { a, b -> a + b })
    }

    @Test
    fun d_aGroupCarriesMessagesBothWays() = runBlocking {
        setUp()
        val peer = local
        val gid = repo.createGroup("End to end")
        repo.addGroupMember(gid, peer.number).getOrThrow()
        val add = peer.receive { CommsWire.type(it) == CommsWire.T_GROUP_CTRL }
        assertEquals(gid, add.getString(CommsWire.F_GID))
        repo.sendGroupMessage(gid, "hello group").getOrThrow()
        val msg = peer.receive { CommsWire.type(it) == CommsWire.T_GROUP_MSG }
        assertEquals("hello group", msg.getString(CommsWire.F_BODY))
        peer.send(app, CommsWire.groupMsg(gid, "group reply", UUID.randomUUID().toString(), System.currentTimeMillis()))
        eventually("the peer's group message arrives") {
            repo.groupMessages(gid).any { it.body == "group reply" && it.sender == peer.number }
        }
    }

    @Test
    fun e_disappearingMessagesAreDeletedOnBothSidesTime() = runBlocking {
        setUp()
        val peer = local
        repo.setTimer(peer.number, 4).getOrThrow()
        val change = peer.receive { CommsWire.type(it) == CommsWire.T_TIMER }
        assertEquals(4, CommsWire.ttlOf(change))
        repo.send(peer.number, "this one vanishes").getOrThrow()
        val sent = peer.receive { CommsWire.type(it) == CommsWire.T_MSG }
        assertEquals("the recipient is told how long it lasts", 4, CommsWire.ttlOf(sent))
        eventually("the sent copy is deleted after four seconds", timeoutMs = 20_000) {
            repo.messages(peer.number).none { it.body == "this one vanishes" }
        }

        peer.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "so does this", ttl = 4))
        eventually("it arrives") { repo.messages(peer.number).any { it.body == "so does this" } }
        val arrived = repo.messages(peer.number).first { it.body == "so does this" }
        assertEquals(4, arrived.ttl)
        repo.markRead(peer.number)
        eventually("deleted four seconds after it was read", timeoutMs = 20_000) {
            repo.messages(peer.number).none { it.body == "so does this" }
        }

        peer.send(app, CommsWire.timer(UUID.randomUUID().toString(), System.currentTimeMillis(), 0))
        eventually("they turned it off") { repo.timer(peer.number) == 0 }
        assertTrue(repo.messages(peer.number).any { it.status == STATUS_SYSTEM && it.body.endsWith("turned disappearing messages off") })
    }

    @Test
    fun f_aCallRingsAndStopsWhenTheCallerHangsUp() = runBlocking {
        setUp()
        val peer = local
        val cid = UUID.randomUUID().toString()
        peer.send(app, CommsWire.callOffer(cid, repo.relayNow(), MINIMAL_OFFER))
        eventually("the app rings") {
            CallManager.call.value?.let { it.id == cid && it.phase == CallPhase.INCOMING && it.peer.number == peer.number } == true
        }
        val ringing = peer.receive { CommsWire.type(it) == CommsWire.T_CALL && it.optString(CommsWire.F_CALL_KIND) == CommsWire.CALL_RINGING }
        assertEquals(cid, ringing.getString(CommsWire.F_CALL_ID))
        peer.send(app, CommsWire.callEnd(cid, CommsWire.END_CANCEL))
        eventually("the ringing stops") { CallManager.call.value?.let { it.id != cid || it.phase != CallPhase.INCOMING } ?: true }
    }

    @Test
    fun g_aContactOnAnotherRelayIsReachedThroughThisOne() = runBlocking {
        setUp()
        val remote = remote
        val contact = repo.addContactFromCode(remote.code("Remote"), "Remote", CommsRepository.CodeSource.SCANNED).getOrThrow()
        assertEquals("http://127.0.0.1:${relayBPort()}", contact.relay)
        repo.send(remote.number, "across two relays").getOrThrow()
        val got = remote.receive { CommsWire.type(it) == CommsWire.T_MSG }
        assertEquals("across two relays", got.getString(CommsWire.F_BODY))
        remote.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "and back"), appRelay = relayAOrigin())
        eventually("the reply comes back through relay B and relay A") {
            repo.messages(remote.number).any { it.direction == Direction.IN && it.body == "and back" }
        }
    }

    @Test
    fun h_aLockedPhoneKeepsWhatArrivesAndReadsItOnUnlock() = runBlocking {
        setUp()
        val peer = local
        try {
            Vault.sealForTest()
            eventually("the vault seals") { repo.contacts().isEmpty() }
            peer.send(app, CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), "sent while locked"))
            // Collected with the transport key, and kept as it came.
            lockedEventually("it waits for the unlock") { repo.lockedWaiting() > 0 }
            assertTrue("nothing can be read while locked", repo.messages(peer.number).isEmpty())
        } finally {
            Vault.open()
        }
        eventually("it is read once unlocked") {
            repo.messages(peer.number).any { it.direction == Direction.IN && it.body == "sent while locked" }
        }
        assertEquals(0, repo.lockedWaiting())
    }

    @Test
    fun i_aCallToALockedPhoneRingsAndIsReadOnUnlock() = runBlocking {
        setUp()
        val peer = local
        val cid = UUID.randomUUID().toString()
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.app.NotificationManager::class.java)
        try {
            Vault.sealForTest()
            eventually("the vault seals") { repo.contacts().isEmpty() }
            peer.send(app, CommsWire.callOffer(cid, repo.relayNow(), MINIMAL_OFFER))
            lockedEventually("the locked phone rings, naming no one") {
                manager.activeNotifications.any { it.id == CommsNotifications.LOCKED_CALL_NOTIFICATION_ID }
            }
            val ring = manager.activeNotifications.first { it.id == CommsNotifications.LOCKED_CALL_NOTIFICATION_ID }.notification
            // CallStyle titles it with the caller, here a stand-in that names no one.
            assertEquals("Clairix call", ring.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            assertEquals("Unlock to answer", ring.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            assertTrue("no call is known yet", CallManager.call.value == null)
        } finally {
            Vault.open()
        }
        eventually("unlocked, the offer is read and the call rings") {
            CallManager.call.value?.let { it.id == cid && it.phase == CallPhase.INCOMING } == true
        }
        assertTrue("the stand-in is gone", manager.activeNotifications.none { it.id == CommsNotifications.LOCKED_CALL_NOTIFICATION_ID })
        peer.send(app, CommsWire.callEnd(cid, CommsWire.END_CANCEL))
        eventually("the ringing stops") { CallManager.call.value?.let { it.id != cid || it.phase != CallPhase.INCOMING } ?: true }
    }

    @Test
    fun z_unpairing() = runBlocking {
        setUp()
        repo.unpair()
        assertTrue(!repo.isRegistered())
    }

    // ── Shared set-up ─────────────────────────────────────────────────────

    private fun setUp(): PairingCode {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass relayA, relayB and secret to run the end-to-end tests", args.getString("relayA") != null && args.getString("secret") != null)
        return shared ?: synchronized(EndToEndTest::class.java) {
            shared ?: start(args.getString("relayA")!!, args.getString("relayB") ?: args.getString("relayA")!!, args.getString("secret")!!).also { shared = it }
        }
    }

    private fun start(relayA: String, relayB: String, secret: String): PairingCode = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repo.init(context)
        if (repo.isRegistered()) repo.unpair()
        repo.register(relayA, secret, "Emulator", listed = true).getOrThrow()
        val me = repo.pairingCode()!!
        app = me
        local = TestPeer(relayA, secret, "Peer", me)
        remote = TestPeer(relayB, secret, "Remote", me)
        repo.addContactFromCode(local.code("Peer"), "Peer", CommsRepository.CodeSource.SCANNED).getOrThrow()
        me
    }

    private fun relayAOrigin(): String = RelayClient.originOf(InstrumentationRegistry.getArguments().getString("relayA"))!!
    private fun relayBPort(): Int = java.net.URI(InstrumentationRegistry.getArguments().getString("relayB")!!).port

    /** As [eventually], for a locked phone: its sync only collects. */
    private suspend fun lockedEventually(what: String, timeoutMs: Long = 45_000, check: () -> Boolean) = eventually(what, timeoutMs, check)

    /** Syncs the app until [check] holds, or fails after [timeoutMs]. */
    private suspend fun eventually(what: String, timeoutMs: Long = 45_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            repo.sync()
            if (check()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what: not within ${timeoutMs / 1000} s")
            kotlinx.coroutines.delay(500)
        }
    }

    companion object {
        @Volatile private var shared: PairingCode? = null
        private lateinit var app: PairingCode
        private lateinit var local: TestPeer
        private lateinit var remote: TestPeer

        /** Enough of an SDP offer for the app to ring; it is never answered. */
        private val MINIMAL_OFFER = listOf(
            "v=0", "o=- 1 1 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=mid:0", "a=sendrecv",
            "a=ice-ufrag:abcd", "a=ice-pwd:abcdefghijklmnopqrstuv",
            "a=fingerprint:sha-256 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF",
            "a=setup:actpass", "a=rtpmap:111 opus/48000/2", ""
        ).joinToString("\r\n")

        @JvmStatic
        @AfterClass
        fun tidy() {
            if (shared == null) return
            runCatching { local.close() }
            runCatching { remote.close() }
            runCatching { runBlocking { if (CommsRepository.isRegistered()) CommsRepository.unpair() } }
            shared = null
        }
    }
}

/**
 * A scripted phone: its own identity and the app's [RelayClient], registered on
 * [relayUrl]. It addresses the app by its pairing code, as a contact who
 * scanned it would, and reads its own inbox by polling.
 */
class TestPeer(private val relayUrl: String, secret: String, private val label: String, app: PairingCode) {
    private val identity: Identity = Identity.create().also { it.generateOneTimeKeys(20u) }
    @Volatile var number: String = ""
        private set
    private val relay = RelayClient({ identity }, { relayUrl }, { number.ifEmpty { null } })
    private val foreign = RelayClient.originOf(relayUrl) != RelayClient.originOf(app.relayUrl)
    private var claim: String? = null
    private val waiting = ArrayList<JSONObject>()

    init {
        number = relay.register(relayUrl, identity, secret, listed = true).number
        identity.markKeysPublished()
        relay.topUpTokens()
    }

    fun code(name: String): PairingCode = identity.publicBundle().let { PairingCode(relayUrl, number, it.ed25519, it.curve25519, it.sealing, it.signature, name) }

    private val sender: CommsWire.Sender
        get() = identity.publicBundle().let { CommsWire.Sender(number, label, it.ed25519, it.curve25519, it.sealing, it.signature) }

    /** Encrypts and sends [payload] to [to], as the app's deliver does; [appRelay] when [to] is on another relay. */
    fun send(to: PairingCode, payload: JSONObject, appRelay: String? = null) {
        // The app's current sealing prekey, as a contact's phone fetches it: envelopes are format 3.
        val prekey = runCatching { relay.identity(to.number, fingerprint(to.ed25519), appRelay).sealingPrekey }.getOrNull()
        val keys = PeerKeys(to.ed25519, to.curve25519, to.sealing, to.signature, prekey)
        if (!identity.hasSession(keys.curve25519)) {
            val bundle = relay.bundle(to.number, fingerprint(to.ed25519), appRelay)
            identity.startSession(keys, bundle.sessionKey)
            claim = bundle.claim
        }
        val full = CommsWire.withSender(payload, sender)
        if (foreign) full.put(CommsWire.F_RELAY, RelayClient.originOf(relayUrl))
        relay.topUpTokens()
        val urgent = CommsWire.type(payload) in setOf(CommsWire.T_CALL, CommsWire.T_PANIC, CommsWire.T_GROUP_CTRL)
        val alert = when (CommsWire.type(payload)) {
            CommsWire.T_CALL -> if (payload.optString(CommsWire.F_CALL_KIND) == CommsWire.CALL_OFFER) RelayClient.ALERT_CALL else null
            CommsWire.T_PANIC -> RelayClient.ALERT_SOS
            else -> null
        }
        relay.send(to.number, identity.encrypt(keys, full.toString().toByteArray(Charsets.UTF_8)), claim, relay = appRelay, urgent = urgent, alert = alert)
        claim = null
    }

    /** The next payload that [matches], polling this peer's inbox; anything else that arrives is kept for later. */
    fun receive(timeoutMs: Long = 45_000, matches: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            waiting.firstOrNull(matches)?.let { waiting.remove(it); return it }
            val envelopes = relay.inbox()
            for (env in envelopes) {
                val d = identity.decrypt(env.data)
                CommsWire.parse(String(d.plaintext, Charsets.UTF_8))?.let { waiting += it }
            }
            if (envelopes.isNotEmpty()) relay.ack(envelopes.map { it.id })
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("$label: nothing matching arrived within ${timeoutMs / 1000} s; got ${waiting.map { CommsWire.type(it) }}")
            }
            if (envelopes.isEmpty()) Thread.sleep(400)
        }
    }

    /** Everything that arrives within [ms], plus anything already received and not yet matched. */
    fun drain(ms: Long): List<JSONObject> {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val envelopes = relay.inbox()
            for (env in envelopes) {
                val d = identity.decrypt(env.data)
                CommsWire.parse(String(d.plaintext, Charsets.UTF_8))?.let { waiting += it }
            }
            if (envelopes.isNotEmpty()) relay.ack(envelopes.map { it.id })
            Thread.sleep(400)
        }
        return waiting.toList().also { waiting.clear() }
    }

    fun close() {
        runCatching { relay.wipe() }
    }
}
