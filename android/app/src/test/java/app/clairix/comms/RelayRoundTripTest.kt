package app.clairix.comms

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import uniffi.clairix_comms_crypto.CryptoException
import uniffi.clairix_comms_crypto.Identity
import uniffi.clairix_comms_crypto.PeerKeys
import uniffi.clairix_comms_crypto.fingerprint
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Two simulated phones talking through a real relay, using the app's own
 * RelayClient, the real Rust crypto library and the app's wire format.
 *
 * Each phone registers a fresh unlisted identity, keeps the live WebSocket
 * open the way LiveLink does, and acks what it receives. Envelopes go out the
 * way the app sends them: anonymously, each spending one delivery token, with
 * the claim ticket of the one-time key a new session was built on. Both
 * mailboxes are wiped at the end. Runs only when CLAIRIX_RELAY_URL and CLAIRIX_ENROLL_SECRET
 * are set in the environment; skipped otherwise (in CI, for example).
 */
class RelayRoundTripTest {

    private val relayUrl: String? = System.getenv("CLAIRIX_RELAY_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    private val secret: String? = System.getenv("CLAIRIX_ENROLL_SECRET")?.trim()?.takeIf { it.isNotEmpty() }

    private lateinit var alice: Phone
    private lateinit var bob: Phone

    /** A phone as the relay and the other phone see it. */
    private class Phone(private val relayUrl: String, secret: String, val label: String) {
        val identity: Identity = Identity.create().also { it.generateOneTimeKeys(20u) }
        @Volatile var number: String? = null
        val relay = RelayClient({ identity }, { relayUrl }, { number })
        private val inbox = LinkedBlockingQueue<RelayClient.Envelope>()
        /** Types of the other frames the relay sent (pong, heartbeat, acked), in order. */
        val control = LinkedBlockingQueue<String>()
        private var socket: WebSocket? = null
        /** The claim ticket of the one-time key the newest session was built on, until its first envelope goes. */
        private var pendingClaim: String? = null

        init {
            number = registerPatiently(relayUrl, secret)
            identity.markKeysPublished()
            // As the app does at registration: tokens before the first send.
            relay.topUpTokens()
        }

        private fun registerPatiently(relayUrl: String, secret: String): String =
            patiently { relay.register(relayUrl, identity, secret, listed = false) }.number

        val keys: PeerKeys
            get() = identity.publicBundle().let { PeerKeys(it.ed25519, it.curve25519, it.sealing, it.signature, it.sealingPrekey) }

        private val sender: CommsWire.Sender
            get() = identity.publicBundle().let { CommsWire.Sender(number!!, label, it.ed25519, it.curve25519, it.sealing, it.signature) }

        /** Opens the live socket and waits for the relay's "ready" after any backlog. */
        fun connect() {
            val ready = CountDownLatch(1)
            socket = relay.openSocket(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "envelope" -> {
                            val env = relay.parseEnvelope(json.getJSONObject("envelope"))
                            inbox.put(env)
                            webSocket.send(JSONObject().put("type", "ack").put("ids", JSONArray(listOf(env.id))).toString())
                        }
                        "ready" -> ready.countDown()
                        else -> control.put(json.optString("type"))
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    System.err.println("$label socket failed: ${t.message}")
                }
            })
            assertTrue("$label: relay socket never became ready", ready.await(20, TimeUnit.SECONDS))
        }

        /** Encrypts [payload] for [peer] exactly as CommsRepository.deliver does, starting a session if needed. */
        fun send(peer: Phone, payload: JSONObject) {
            val peerKeys = peer.keys
            if (!identity.hasSession(peerKeys.curve25519)) {
                val bundle = relay.bundle(peer.number!!, fingerprint(peerKeys.ed25519))
                assertEquals(peerKeys.curve25519, bundle.curve25519)
                identity.startSession(peerKeys, bundle.sessionKey)
                pendingClaim = bundle.claim
            }
            val plaintext = CommsWire.withSender(payload, sender).toString().toByteArray(Charsets.UTF_8)
            // The app tops up on sync and never inside a send; here, whenever few are left.
            relay.topUpTokens()
            // As CommsRepository.deliver: what must not wait or be reordered skips
            // the relay's mixing; everything else may arrive in any order.
            val urgent = CommsWire.type(payload) in setOf(CommsWire.T_CALL, CommsWire.T_PANIC, CommsWire.T_GROUP_CTRL)
            relay.send(peer.number!!, identity.encrypt(peerKeys, plaintext), pendingClaim, urgent = urgent)
            pendingClaim = null
        }

        /** What CommsRepository.recoverSession does: a new outbound session, first in the list, old ones kept. */
        fun startFreshSession(peer: Phone) {
            val peerKeys = peer.keys
            val bundle = relay.bundle(peer.number!!, fingerprint(peerKeys.ed25519))
            identity.startSession(peerKeys, bundle.sessionKey)
            pendingClaim = bundle.claim
        }

        /** Sends a raw frame on the live socket, as LiveLink's liveness probe does. */
        fun sendFrame(text: String) = assertTrue("$label: socket refused the frame", socket?.send(text) == true)

        /** Waits for a control frame of [type], skipping others. */
        fun awaitControl(type: String, seconds: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                if (control.poll(left, TimeUnit.NANOSECONDS) == type) return true
            }
        }

        /** Posts already-encrypted bytes, as a relay redelivery or a re-send would. */
        fun sendRaw(peer: Phone, envelope: ByteArray): String {
            relay.topUpTokens()
            return relay.send(peer.number!!, envelope)
        }

        fun encryptFor(peer: Phone, payload: JSONObject): ByteArray {
            val peerKeys = peer.keys
            if (!identity.hasSession(peerKeys.curve25519)) startFreshSession(peer)
            return identity.encrypt(peerKeys, CommsWire.withSender(payload, sender).toString().toByteArray(Charsets.UTF_8))
        }

        class Received(val senderCurve: String, val senderEd: String, val payload: JSONObject, val newSession: Boolean)

        /** The next envelope off the live socket, decrypted and parsed. */
        fun receive(): Received {
            val env = inbox.poll(RECEIVE_S, TimeUnit.SECONDS) ?: throw AssertionError("$label: nothing arrived within $RECEIVE_S s")
            val d = identity.decrypt(env.data)
            val payload = CommsWire.parse(String(d.plaintext, Charsets.UTF_8)) ?: throw AssertionError("$label: payload did not parse")
            return Received(d.senderCurve25519, d.senderEd25519, payload, d.newSession)
        }

        /** The next envelope, expected to fail decryption. */
        fun receiveUndecryptable(): CryptoException {
            val env = inbox.poll(RECEIVE_S, TimeUnit.SECONDS) ?: throw AssertionError("$label: nothing arrived within $RECEIVE_S s")
            try {
                identity.decrypt(env.data)
            } catch (e: CryptoException) {
                return e
            }
            throw AssertionError("$label: the envelope decrypted")
        }

        /** Polls the inbox (no socket) for the next envelope, decrypts and acks it. */
        fun inboxOnce(): JSONObject {
            val deadline = System.currentTimeMillis() + RECEIVE_S * 1000
            while (System.currentTimeMillis() < deadline) {
                val envs = relay.inbox()
                if (envs.isNotEmpty()) {
                    relay.ack(envs.map { it.id })
                    val d = identity.decrypt(envs.first().data)
                    return CommsWire.parse(String(d.plaintext, Charsets.UTF_8)) ?: throw AssertionError("$label: payload did not parse")
                }
                Thread.sleep(500)
            }
            throw AssertionError("$label: nothing arrived within $RECEIVE_S s")
        }

        fun close() {
            socket?.close(1000, "test over")
            runCatching { relay.wipe() }
        }
    }

    @Before
    fun setUp() {
        assumeTrue("set CLAIRIX_RELAY_URL and CLAIRIX_ENROLL_SECRET to run the relay round-trip tests", relayUrl != null && secret != null)
        alice = Phone(relayUrl!!, secret!!, "Alice")
        bob = Phone(relayUrl, secret, "Bob")
        alice.connect()
        bob.connect()
    }

    @After
    fun tearDown() {
        if (::alice.isInitialized) alice.close()
        if (::bob.isInitialized) bob.close()
    }

    private companion object {
        /** Longer than the relay's longest mixing hold (20 s), with room for its alarm. */
        const val RECEIVE_S = 45L

        /**
         * Runs a registration, waiting out the relay's limit of ten a minute from
         * one address: a full run registers two phones per test, and more.
         */
        fun <T> patiently(register: () -> T): T {
            repeat(30) {
                try {
                    return register()
                } catch (e: RelayException) {
                    if (e.code != 429) throw e
                    Thread.sleep(7_000)
                }
            }
            throw AssertionError("the relay kept refusing registration")
        }
    }

    private fun msg(body: String) = CommsWire.message(UUID.randomUUID().toString(), System.currentTimeMillis(), body)

    @Test
    fun severalMessagesBeforeAnyReplyAllArriveAndReceiptsComeBack() {
        val ids = (1..5).map { i -> msg("hello $i").also { alice.send(bob, it) }.getString(CommsWire.F_ID) }
        // The relay's mixing may hand them over in any order; each arrives once, intact.
        val bodies = HashMap<String, String>()
        repeat(ids.size) {
            val got = bob.receive()
            assertEquals(alice.keys.curve25519, got.senderCurve)
            assertEquals(alice.keys.ed25519, got.senderEd)
            assertEquals(CommsWire.T_MSG, CommsWire.type(got.payload))
            assertEquals(alice.number, CommsWire.senderOf(got.payload)?.number)
            assertEquals(null, bodies.put(got.payload.getString(CommsWire.F_ID), got.payload.getString(CommsWire.F_BODY)))
        }
        assertEquals(ids.mapIndexed { i, id -> id to "hello ${i + 1}" }.toMap(), bodies)
        bob.send(alice, CommsWire.receipt(ids, CommsWire.STATUS_DELIVERED))
        val receipt = alice.receive()
        assertEquals(CommsWire.T_RECEIPT, CommsWire.type(receipt.payload))
        assertEquals(ids, CommsWire.receiptIds(receipt.payload))
        // And the conversation keeps going in both directions afterwards.
        repeat(4) { round ->
            alice.send(bob, msg("a$round")); assertEquals("a$round", bob.receive().payload.getString(CommsWire.F_BODY))
            bob.send(alice, msg("b$round")); assertEquals("b$round", alice.receive().payload.getString(CommsWire.F_BODY))
        }
    }

    @Test
    fun callSignalsArriveIntactInBothDirections() {
        val cid = UUID.randomUUID().toString()
        val offerSdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n" + "a=fingerprint:sha-256 AB:CD\r\n".repeat(150)
        alice.send(bob, CommsWire.callOffer(cid, System.currentTimeMillis(), offerSdp))
        alice.send(bob, CommsWire.callIce(cid, listOf(CommsWire.Candidate("0", 0, "candidate:1 1 udp 2122260223 10.0.0.2 50000 typ host"))))

        val offer = bob.receive().payload
        assertEquals(CommsWire.T_CALL, CommsWire.type(offer))
        assertEquals(CommsWire.CALL_OFFER, CommsWire.callKind(offer))
        assertEquals(cid, offer.getString(CommsWire.F_CALL_ID))
        assertEquals(offerSdp, offer.getString(CommsWire.F_SDP))
        val ice = bob.receive().payload
        assertEquals(CommsWire.CALL_ICE, CommsWire.callKind(ice))
        assertEquals(1, CommsWire.candidates(ice).size)

        bob.send(alice, CommsWire.callAnswer(cid, "v=0 answer"))
        bob.send(alice, CommsWire.callIce(cid, listOf(CommsWire.Candidate("0", 0, "candidate:2 1 udp 1686052607 198.51.100.4 40000 typ srflx"))))
        val answer = alice.receive().payload
        assertEquals(CommsWire.CALL_ANSWER, CommsWire.callKind(answer))
        assertEquals("v=0 answer", answer.getString(CommsWire.F_SDP))
        assertEquals(CommsWire.CALL_ICE, CommsWire.callKind(alice.receive().payload))

        alice.send(bob, CommsWire.callEnd(cid, "hangup"))
        val end = bob.receive().payload
        assertEquals(CommsWire.CALL_END, CommsWire.callKind(end))
        assertEquals("hangup", end.getString(CommsWire.F_REASON))
    }

    @Test
    fun bothPhonesStartingSessionsAtOnceStillConverge() {
        // Each adds the other and sends first, before anything has arrived.
        alice.send(bob, msg("from alice first"))
        bob.send(alice, msg("from bob first"))
        assertEquals("from alice first", bob.receive().payload.getString(CommsWire.F_BODY))
        assertEquals("from bob first", alice.receive().payload.getString(CommsWire.F_BODY))
        repeat(6) { round ->
            alice.send(bob, msg("a$round")); assertEquals("a$round", bob.receive().payload.getString(CommsWire.F_BODY))
            bob.send(alice, msg("b$round")); assertEquals("b$round", alice.receive().payload.getString(CommsWire.F_BODY))
        }
    }

    @Test
    fun aLostSessionRecoversThroughResync() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()

        // Bob loses his session (as recoverSession does after a failure).
        bob.identity.dropSessions(alice.keys.curve25519)
        alice.send(bob, msg("lost"))
        val failure = bob.receiveUndecryptable()
        assertTrue("expected NoSessionFor, got $failure", failure is CryptoException.NoSessionFor)
        assertEquals(alice.keys.curve25519, (failure as CryptoException.NoSessionFor).senderCurve25519)
        // The hint is signed, so it names Alice's identity key, which Bob checks
        // against his contact before he asks her for anything.
        assertEquals(alice.keys.ed25519, failure.senderEd25519)

        // Bob asks for a new session; the resync itself starts one.
        bob.send(alice, CommsWire.resync())
        val resync = alice.receive()
        assertEquals(CommsWire.T_RESYNC, CommsWire.type(resync.payload))
        assertTrue("the resync should arrive on a new session", resync.newSession)

        // From here on both directions work again.
        alice.send(bob, msg("after")); assertEquals("after", bob.receive().payload.getString(CommsWire.F_BODY))
        bob.send(alice, msg("reply")); assertEquals("reply", alice.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun messagesInFlightDuringARecoveryStillArrive() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()

        // Alice sends two more on the current session...
        alice.send(bob, msg("in flight 1"))
        alice.send(bob, msg("in flight 2"))
        // ...while Bob, reacting to some unreadable envelope, starts a new session
        // and sends a resync on it, keeping his old sessions (the v2.3 recovery).
        bob.startFreshSession(alice)
        bob.send(alice, CommsWire.resync())

        // Bob still reads what Alice sent on the old session, in whichever order it comes.
        assertEquals(setOf("in flight 1", "in flight 2"), List(2) { bob.receive().payload.getString(CommsWire.F_BODY) }.toSet())
        // Alice takes the resync on a new session and from then on uses it.
        val resync = alice.receive()
        assertEquals(CommsWire.T_RESYNC, CommsWire.type(resync.payload))
        assertTrue(resync.newSession)
        alice.send(bob, msg("after")); assertEquals("after", bob.receive().payload.getString(CommsWire.F_BODY))
        bob.send(alice, msg("reply")); assertEquals("reply", alice.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun aRedeliveredEnvelopeIsADuplicateAndHarmsNothing() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()
        val envelope = alice.encryptFor(bob, msg("twice"))
        alice.sendRaw(bob, envelope)
        alice.sendRaw(bob, envelope)
        assertEquals("twice", bob.receive().payload.getString(CommsWire.F_BODY))
        val second = bob.receiveUndecryptable()
        assertTrue("expected Duplicate, got $second", second is CryptoException.Duplicate)
        alice.send(bob, msg("still fine")); assertEquals("still fine", bob.receive().payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun identityLookupConfirmsKeysWithoutUsingAOneTimeKey() {
        val before = bob.relay.me().oneTimeKeys
        val id = alice.relay.identity(bob.number!!, fingerprint(bob.keys.ed25519))
        assertEquals(bob.keys.curve25519, id.curve25519)
        assertEquals(bob.keys.ed25519, id.ed25519)
        assertEquals(before, bob.relay.me().oneTimeKeys)
        // Unlisted, so a lookup without the pin is refused.
        try {
            alice.relay.identity(bob.number!!, null)
            fail("an unlisted identity was handed out without its pin")
        } catch (e: RelayException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun theRelayServesTheSealingPrekeyAndEnvelopesAreSealedToIt() {
        val published = bob.identity.publicBundle().sealingPrekey
        assertNotNull("a new identity has a sealing prekey", published)
        val id = alice.relay.identity(bob.number!!, fingerprint(bob.keys.ed25519))
        assertEquals(published, id.sealingPrekey)
        val envelope = alice.encryptFor(bob, msg("sealed to the prekey"))
        assertEquals("envelope format", 3, envelope[0].toInt())
        alice.sendRaw(bob, envelope)
        val got = bob.receive()
        assertEquals("sealed to the prekey", got.payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun aSendNeedsADeliveryTokenAndCarriesNoIdentity() {
        val http = OkHttpClient()
        val json = "application/json".toMediaType()
        val body = JSONObject().put("to", bob.number).put("envelope", "AAAA").toString()
        fun post(vararg headers: Pair<String, String>): Int {
            val request = Request.Builder().url("$relayUrl/v1/send").post(body.toRequestBody(json))
            for ((k, v) in headers) request.header(k, v)
            return http.newCall(request.build()).execute().use { it.code }
        }
        assertEquals(401, post())
        assertEquals(403, post("X-Clairix-Token" to "A".repeat(43)))
        assertEquals(400, post("X-Clairix-Token" to "A".repeat(43), "X-Clairix-Number" to alice.number!!))
        // A real token works once, through the app's own client.
        alice.send(bob, msg("with a token"))
        assertEquals("with a token", bob.receive().payload.getString(CommsWire.F_BODY))
        assertTrue("the client should hold tokens for later sends", alice.relay.tokenCount() > 0)
    }

    @Test
    fun aSendWithAnEmptyTokenPoolFailsWithoutFetchingTokens() {
        val empty = RelayClient({ alice.identity }, { relayUrl }, { alice.number })
        try {
            empty.send(bob.number!!, ByteArray(64))
            fail("a send with no tokens went out")
        } catch (e: RelayException) {
            assertEquals(RelayClient.NO_TOKENS, e.code)
        }
        assertEquals(0, empty.tokenCount())
        empty.topUpTokens()
        assertTrue(empty.tokenCount() > 0)
    }

    /**
     * The tokens the app fetches are blind: made on this phone, signed by the
     * relay without seeing them, and checked against the key GET / publishes.
     */
    @Test
    fun tokensAreBlindAndProvedAgainstThePublishedKey() {
        val pool = MemoryTokenPool()
        val pins = MemoryTokenKeyPins()
        val client = RelayClient({ alice.identity }, { relayUrl }, { alice.number }, tokens = pool, tokenKeys = pins)
        val info = client.info()
        assertNotNull("the relay publishes blind-token keys", info.tokens)
        val keys = info.tokens!!
        assertEquals(setOf("delivery", "traffic", "report"), keys.keys.keys)
        assertEquals(keys.keys, pins.get(relayUrl!!, keys.epoch))
        patiently { client.topUpTokens() }
        val held = pool.encode()
        val tokens = org.json.JSONArray(held).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("t") } }
        assertEquals(RelayClient.BLIND_TOKENS_BATCH, tokens.size)
        assertTrue("blind tokens: ${tokens.first()}", tokens.all { it.startsWith("2.${keys.epoch}.") })
        // They buy sends, once each.
        patiently { client.send(bob.number!!, alice.encryptFor(bob, msg("blind"))) }
        assertEquals("blind", bob.receive().payload.getString(CommsWire.F_BODY))
        val http = OkHttpClient()
        val spent = tokens.first { t -> pool.encode().let { !it.contains(t) } }
        val again = http.newCall(
            Request.Builder().url("$relayUrl/v1/send").header("X-Clairix-Token", spent)
                .post(JSONObject().put("to", bob.number).put("envelope", "AAAA").toString().toRequestBody("application/json".toMediaType())).build()
        ).execute().use { it.code }
        assertEquals("a spent blind token is refused", 403, again)
    }

    /**
     * An onion address that does not answer (one nothing serves, which does not
     * even resolve without Tor) costs a try and no more: the request goes to the
     * registered address, and the keys seen are kept under that address, where
     * tokens fetched through either address are checked.
     */
    @Test
    fun anOnionAddressThatDoesNotAnswerFallsBackToTheRegisteredOne() {
        val pins = MemoryTokenKeyPins()
        val dead = "http://" + "a".repeat(56) + ".onion"
        val client = RelayClient({ alice.identity }, { relayUrl }, { alice.number }, tokenKeys = pins, onion = { dead })
        val info = client.info()
        val keys = info.tokens!!
        assertEquals(keys.keys, pins.get(relayUrl!!, keys.epoch))
        assertEquals(null, pins.get(dead, keys.epoch))
        // Signed requests too, now straight to the registered address.
        assertTrue(client.me().oneTimeKeys >= 0)
        assertTrue(client.createInvite().code.isNotEmpty())
    }

    /** A relay that issues under a key other than the one it publishes is refused, and nothing is added to the pool. */
    @Test
    fun tokensUnderAnotherKeyAreRefused() {
        val pool = MemoryTokenPool()
        val pins = MemoryTokenKeyPins()
        val client = RelayClient({ alice.identity }, { relayUrl }, { alice.number }, tokens = pool, tokenKeys = pins)
        val epoch = client.info().tokens!!.epoch
        // As if the key first seen this epoch had been another one.
        pins.put(relayUrl!!, epoch, mapOf("delivery" to "A".repeat(43), "traffic" to "B".repeat(43), "report" to "C".repeat(43)))
        try {
            client.topUpTokens()
            fail("tokens under an unpinned key were accepted")
        } catch (e: RelayException) {
            assertEquals(RelayClient.TOKEN_KEY_MISMATCH, e.code)
        }
        assertEquals(0, client.tokenCount())
    }

    /**
     * A contact on another relay, end to end over two real relays: set
     * CLAIRIX_RELAY_B_URL to a second relay (two `wrangler dev` side by side on
     * loopback admit each other; deployed relays need https addresses).
     */
    @Test
    fun aContactOnAnotherRelayIsReachedThroughThisOne() {
        val other = System.getenv("CLAIRIX_RELAY_B_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        assumeTrue("set CLAIRIX_RELAY_B_URL to a second relay", other != null)
        val carol = Phone(other!!, secret!!, "Carol")
        try {
            val carolOrigin = RelayClient.originOf(other)!!
            val aliceOrigin = RelayClient.originOf(relayUrl)!!
            // Alice reaches Carol through her own relay, which forwards to Carol's.
            val keys = carol.keys
            val bundle = alice.relay.bundle(carol.number!!, fingerprint(keys.ed25519), carolOrigin)
            assertEquals(keys.curve25519, bundle.curve25519)
            alice.identity.startSession(keys, bundle.sessionKey)
            val payload = CommsWire.withSender(msg("across relays"), CommsWire.Sender(alice.number!!, "Alice", alice.keys.ed25519, alice.keys.curve25519, alice.keys.sealing, alice.keys.signature))
            alice.relay.topUpTokens()
            alice.relay.send(carol.number!!, alice.identity.encrypt(keys, payload.toString().toByteArray()), bundle.claim, relay = carolOrigin)
            val got = carol.inboxOnce()
            assertEquals("across relays", got.getString(CommsWire.F_BODY))
            // And back, through Carol's relay to Alice's.
            carol.relay.topUpTokens()
            val back = CommsWire.withSender(msg("and back"), CommsWire.Sender(carol.number!!, "Carol", keys.ed25519, keys.curve25519, keys.sealing, keys.signature)).put(CommsWire.F_RELAY, carolOrigin)
            carol.relay.send(alice.number!!, carol.identity.encrypt(alice.keys, back.toString().toByteArray()), relay = aliceOrigin)
            assertEquals("and back", alice.receive().payload.getString(CommsWire.F_BODY))
        } finally {
            carol.close()
        }
    }

    /**
     * Moving to another relay, as CommsRepository.moveTo does it: Alice
     * registers the identity she has on a second relay and is given a number
     * there. Bob, who already has a session with her, finds the same keys
     * under the new number and writes to it over that session; what is sent
     * to her old number still waits on the first relay; and the account there
     * is still hers to collect from, or to take up again.
     */
    @Test
    fun anIdentityMovesToAnotherRelayAndKeepsItsSessions() {
        val other = System.getenv("CLAIRIX_RELAY_B_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        assumeTrue("set CLAIRIX_RELAY_B_URL to a second relay", other != null)
        alice.send(bob, msg("before the move")); bob.receive()
        bob.send(alice, msg("a reply")); alice.receive()

        alice.identity.generateOneTimeKeys(20u)
        val newNumber = alice.relay.register(other!!, alice.identity, secret!!, listed = false).number
        alice.identity.markKeysPublished()
        val there = RelayClient({ alice.identity }, { other }, { newNumber })
        try {
            val origin = RelayClient.originOf(other)!!
            val keys = bob.relay.identity(newNumber, fingerprint(alice.keys.ed25519), origin)
            assertEquals("the same keys, under the new number", alice.keys.ed25519, keys.ed25519)
            assertEquals(alice.keys.curve25519, keys.curve25519)
            assertEquals(alice.keys.sealing, keys.sealing)

            // Over the session Bob already has: no new one is needed.
            assertTrue(bob.identity.hasSession(alice.keys.curve25519))
            val sender = bob.identity.publicBundle().let { CommsWire.Sender(bob.number!!, "Bob", it.ed25519, it.curve25519, it.sealing, it.signature) }
            val plain = CommsWire.withSender(msg("to your new number"), sender).toString().toByteArray(Charsets.UTF_8)
            bob.relay.topUpTokens()
            bob.relay.send(newNumber, bob.identity.encrypt(alice.keys, plain), relay = origin)
            val deadline = System.currentTimeMillis() + RECEIVE_S * 1000
            var envelopes = there.inbox()
            while (envelopes.isEmpty() && System.currentTimeMillis() < deadline) { Thread.sleep(500); envelopes = there.inbox() }
            assertEquals(1, envelopes.size)
            there.ack(envelopes.map { it.id })
            val opened = alice.identity.decrypt(envelopes.single().data)
            assertTrue("read on the session the two already had", !opened.newSession)
            assertEquals("to your new number", CommsWire.parse(String(opened.plaintext, Charsets.UTF_8))!!.getString(CommsWire.F_BODY))

            // A contact who has not heard yet writes to the old number: it waits on the old relay.
            bob.send(alice, msg("to the old number"))
            assertEquals("to the old number", alice.receive().payload.getString(CommsWire.F_BODY))
            // And the old account is still Alice's.
            alice.relay.me()
        } finally {
            runCatching { there.wipe() }
        }
    }

    @Test
    fun shortPayloadsMakeEnvelopesOfOneSize() {
        alice.send(bob, msg("one")); bob.receive()
        bob.send(alice, msg("two")); alice.receive()
        val receipt = alice.encryptFor(bob, CommsWire.receipt(listOf(UUID.randomUUID().toString()), CommsWire.STATUS_READ))
        val text = alice.encryptFor(bob, msg("ok"))
        assertEquals(receipt.size, text.size)
        for (e in listOf(receipt, text)) alice.sendRaw(bob, e)
        // Mixing may swap them; both arrive and read as what they are.
        val got = List(2) { bob.receive().payload }
        assertEquals(setOf(CommsWire.T_RECEIPT, CommsWire.T_MSG), got.map { CommsWire.type(it) }.toSet())
        assertEquals("ok", got.single { CommsWire.type(it) == CommsWire.T_MSG }.getString(CommsWire.F_BODY))
    }

    @Test
    fun aLookupHandsOutAClaimTicketWithItsOneTimeKey() {
        val bundle = alice.relay.bundle(bob.number!!, fingerprint(bob.keys.ed25519))
        assertNotNull("a one-time key comes with its claim ticket", bundle.claim)
        assertEquals(43, bundle.claim!!.length)
    }

    @Test
    fun aClaimedOneTimeKeyIsNeverOfferedAgain() {
        // Take one key, then try to publish it again: the relay must refuse to re-add it.
        val bundle = alice.relay.bundle(bob.number!!, fingerprint(bob.keys.ed25519))
        val after = bob.relay.me().oneTimeKeys
        val again = bob.identity.publicBundle().let {
            uniffi.clairix_comms_crypto.PublicBundle(it.ed25519, it.curve25519, it.sealing, it.signature, null, null, listOf(bundle.sessionKey))
        }
        assertEquals(after, bob.relay.putKeys(again))
    }

    @Test
    fun relayClockIsLearnedFromResponses() {
        alice.relay.me()
        val skew = alice.relay.relayNow() - System.currentTimeMillis()
        assertTrue("relay clock offset $skew ms is implausible for a synced clock", kotlin.math.abs(skew) < 5_000)
        assertNotNull(alice.number)
    }

    @Test
    fun aSecondSocketForTheSameNumberStillGetsEnvelopes() {
        // LiveLink can briefly hold two sockets across a reconnect; neither may swallow envelopes.
        bob.connect()
        alice.send(bob, msg("to both sockets"))
        val first = bob.receive()
        assertEquals("to both sockets", first.payload.getString(CommsWire.F_BODY))
    }

    @Test
    fun unknownVersionPayloadIsNotMistakenForAMessage() {
        val raw = JSONObject().put(CommsWire.F_VERSION, 99).put(CommsWire.F_TYPE, CommsWire.T_MSG)
        val plaintext = raw.toString().toByteArray()
        if (!alice.identity.hasSession(bob.keys.curve25519)) {
            val bundle = alice.relay.bundle(bob.number!!, fingerprint(bob.keys.ed25519))
            alice.identity.startSession(bob.keys, bundle.sessionKey)
        }
        alice.sendRaw(bob, alice.identity.encrypt(bob.keys, plaintext))
        try {
            bob.receive()
            fail("a version-99 payload parsed")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains("did not parse"))
        }
    }

    @Test
    fun aPingOnTheSocketIsAnswered() {
        // LiveLink's liveness probe: an open socket that gets no pong is replaced.
        alice.sendFrame("{\"type\":\"ping\"}")
        assertTrue("no pong within 5 s", alice.awaitControl("pong", 5))
    }

    @Test
    fun theRelaySendsHeartbeatsToAConnectedPhone() {
        // Takes up to two and a half minutes, so it runs only when asked for.
        assumeTrue("set CLAIRIX_SLOW_TESTS to wait for a relay heartbeat", System.getenv("CLAIRIX_SLOW_TESTS") != null)
        assertTrue("no heartbeat within 150 s", alice.awaitControl("hb", 150))
    }

    @Test
    fun anInviteRegistersExactlyOnePhone() {
        val invite = alice.relay.createInvite()
        assertEquals(22, invite.code.length)
        assertTrue(invite.expiresAt > System.currentTimeMillis() + 6L * 24 * 3600_000L)

        // A new phone registers with the invite and no enrollment secret.
        val carol = Identity.create().also { it.generateOneTimeKeys(5u) }
        var carolNumber: String? = null
        val carolRelay = RelayClient({ carol }, { relayUrl }, { carolNumber })
        carolNumber = patiently { carolRelay.register(relayUrl!!, carol, null, listed = false, invite = invite.code) }.number
        try {
            assertNotNull(carolNumber)
            // The invite is spent: a second phone cannot use it.
            val dave = Identity.create()
            val refused = runCatching { patiently { RelayClient({ dave }, { relayUrl }, { null }).register(relayUrl!!, dave, null, false, invite.code) } }
            assertEquals(403, (refused.exceptionOrNull() as? RelayException)?.code)
            // Nor can a made-up code.
            val made = runCatching { patiently { RelayClient({ dave }, { relayUrl }, { null }).register(relayUrl!!, dave, null, false, "AAAAAAAAAAAAAAAAAAAAAA") } }
            assertEquals(403, (made.exceptionOrNull() as? RelayException)?.code)
        } finally {
            runCatching { carolRelay.wipe() }
        }
    }
}
