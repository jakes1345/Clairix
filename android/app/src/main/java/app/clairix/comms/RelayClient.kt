package app.clairix.comms

import app.clairix.BuildConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.clairix_comms_crypto.Identity
import uniffi.clairix_comms_crypto.PublicBundle
import uniffi.clairix_comms_crypto.SealingPrekey
import uniffi.clairix_comms_crypto.SignedKey
import uniffi.clairix_comms_crypto.TransportKey
import uniffi.clairix_comms_crypto.tokenBlind
import uniffi.clairix_comms_crypto.tokenFinalize
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A relay call that did not succeed, with the relay's message when it gave one. */
class RelayException(message: String, val code: Int = 0) : IOException(message)

/**
 * HTTP and WebSocket client for the relay Worker. Every request about this
 * phone's own mailbox is signed with the identity's Ed25519 key (see
 * comms-worker/src/auth.ts). Sending an envelope and everything about traffic
 * are not: each spends one single-use token of the right kind ([tokens],
 * [trafficTokens], [reportTokens]) and carries no identity at all, on a
 * connection of its own, so the relay does not learn who sent or asked.
 * Calls are blocking and belong on an IO dispatcher.
 *
 * It depends on nothing Android-specific, so the relay round-trip tests run it
 * on the JVM against a real relay; the app hands it its identity, relay URL and
 * Clairix number as lookups, since all three change on registration.
 */
class RelayClient(
    private val identity: () -> Identity?,
    private val relayUrl: () -> String?,
    private val number: () -> String?,
    private val tokens: TokenPool = MemoryTokenPool(),
    private val trafficTokens: TokenPool = MemoryTokenPool(),
    private val reportTokens: TokenPool = MemoryTokenPool(),
    /**
     * The relay's pinned certificate key ("sha256/…" of its SubjectPublicKeyInfo),
     * or null to trust the platform's verdict alone. Read on every handshake,
     * so a pin set or forgotten on the Certificates page applies at once. See
     * [RelayTls].
     */
    private val pin: () -> String? = { null },
    /**
     * The other addresses the relay answers at, as it last said (see [info]).
     * When the address in use stops answering, a request is tried at each of
     * these, and the one that answers is kept for the requests after it.
     */
    private val alternates: () -> List<String> = { emptyList() },
    /** The blind-token keys seen per relay and epoch; see [TokenKeyPins]. */
    private val tokenKeys: TokenKeyPins = MemoryTokenKeyPins(),
    /**
     * The transport key, once the relay has it: fetching, acknowledging and
     * streaming envelopes are signed with it, so they work while the identity
     * is out of reach (the phone is locked). Null signs them with the identity.
     */
    private val transport: () -> TransportKey? = { null },
    /** Called when the relay refused the transport key: it is then republished, and the identity signs meanwhile. */
    private val transportRefused: () -> Unit = {},
) {

    /** The alternate address requests go to since the registered one stopped answering, if any. */
    @Volatile private var reachable: String? = null

    /**
     * The address requests go to: the alternate that last answered, else the
     * one registered at. With the relay's key pinned, always the registered
     * one: the pin is that host's key, and another host has another.
     */
    private fun base(): String? {
        val primary = relayUrl()?.trimEnd('/') ?: return null
        val current = reachable
        return if (current != null && pin() == null && current in alternates().map { it.trimEnd('/') }) current else primary
    }

    /** The addresses to try after [current] failed to answer, in order. */
    private fun otherBases(current: String): List<String> {
        if (pin() != null) return emptyList()
        val primary = relayUrl()?.trimEnd('/') ?: return emptyList()
        return (listOf(primary) + alternates().map { it.trimEnd('/') }).distinct().filter { it != current }
    }

    /** The keys a relay issues blind tokens under in [epoch], kind (wire name) to base64url. */
    data class TokenKeys(val epoch: Long, val keys: Map<String, String>)

    data class Info(val version: Int, val alternates: List<String>, val tokens: TokenKeys? = null)

    /**
     * The relay's description of itself: its API version, the other addresses it
     * answers at, and its blind-token keys (null from a relay before Beta 6).
     * Unauthenticated, on a connection nothing else uses.
     */
    fun info(): Info {
        val relay = base() ?: throw RelayException("Not registered with a relay")
        val json = execute(Request.Builder().url("$relay/").header("Accept", "application/json").get(), anonymousClient(4))
        return parsed {
            val arr = json.optJSONArray("alternates")
            val urls = arr?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.startsWith("https://") } } } ?: emptyList()
            val t = json.optJSONObject("tokens")?.takeIf { it.optInt("version") == 2 }
            fun keysOf(o: JSONObject?) = o?.optJSONObject("keys")?.let { k -> k.keys().asSequence().associateWith { k.getString(it) } }
            val keys = keysOf(t)
            val tokens = if (t != null && keys != null) TokenKeys(t.getLong("epoch"), keys) else null
            val origin = relay.trimEnd('/')
            if (tokens != null && plausibleEpoch(tokens.epoch)) {
                // Seen on a request that names nobody: the first keys seen for an
                // epoch are the ones tokens are checked against. The next epoch's
                // are pinned now too, long before any tokens are issued under them.
                if (tokenKeys.get(origin, tokens.epoch) == null) tokenKeys.put(origin, tokens.epoch, tokens.keys)
                val next = t!!.optJSONObject("next")
                val nextKeys = keysOf(next)
                val nextEpoch = next?.optLong("epoch", -1L) ?: -1L
                if (nextKeys != null && nextEpoch == tokens.epoch + 1 && tokenKeys.get(origin, nextEpoch) == null) tokenKeys.put(origin, nextEpoch, nextKeys)
            }
            Info(json.optInt("version", 0), urls, tokens)
        }
    }

    /**
     * Whether a token epoch the relay names is one this phone's own clock agrees
     * with: this one or a neighbour. The relay names the epoch, and one it made
     * up for a single phone would mark every token that phone spends.
     */
    private fun plausibleEpoch(epoch: Long): Boolean {
        val mine = System.currentTimeMillis() / TOKEN_EPOCH_MS
        return epoch in (mine - 1)..(mine + 1)
    }

    /**
     * A client for one request that names nobody: its own connection and its own
     * TLS session cache, both thrown away after the request. Requests sharing a
     * connection, or resuming one TLS session, are one phone's requests as far as
     * the relay can see, however anonymous each is on its own.
     */
    private fun anonymousClient(callTimeoutSec: Long): OkHttpClient =
        RelayTls.pinned(sender.newBuilder(), pin)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .build()

    private fun pool(kind: TokenKind): TokenPool = when (kind) {
        TokenKind.DELIVERY -> tokens
        TokenKind.TRAFFIC -> trafficTokens
        TokenKind.REPORT -> reportTokens
    }

    /** What a signed request signs. The signature headers themselves are added per attempt by [signer]. */
    private class Signing(val method: String, val pathAndQuery: String, val bodyText: String, val transport: TransportKey? = null)

    /**
     * Signs every network attempt afresh. OkHttp retries a request on a new
     * connection when the first one broke (a stale pooled connection, a
     * network switch mid-request). The relay accepts each nonce once, so a
     * retry that reused the first attempt's signature was refused with 401,
     * and a call offer that had in fact been delivered was reported as failed.
     * With a fresh nonce the retry goes through; a duplicate envelope is
     * dropped by the receiving phone, and duplicate keys by the relay.
     */
    private val signer = Interceptor { chain ->
        val request = chain.request()
        val signing = request.tag(Signing::class.java) ?: return@Interceptor chain.proceed(request)
        chain.proceed(sign(request.newBuilder(), signing).build())
    }

    /** The live socket's client: no overall call timeout, which would cut the socket itself. */
    val http: OkHttpClient = RelayTls.pinned(OkHttpClient.Builder(), pin)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        // Signed requests carry identity headers and the registration body carries
        // the enrollment secret; neither may be re-sent to wherever a redirect points.
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(signer)
        .build()

    /**
     * Requests: bounded as a whole, so one stalled request (Wi-Fi that lost
     * its internet, say) cannot hold up everything queued behind it.
     */
    private val rest: OkHttpClient by lazy { http.newBuilder().pingInterval(0, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build() }

    /**
     * Sending an envelope: a call's signals must fail fast enough to be retried
     * within the call. Its own connection pool and no signing interceptor, so an
     * anonymous send never rides a connection a signed request used.
     */
    private val sender: OkHttpClient by lazy {
        RelayTls.pinned(OkHttpClient.Builder(), pin)
            .connectionPool(okhttp3.ConnectionPool())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class Registered(val number: String)

    data class Bundle(
        val number: String,
        val ed25519: String,
        val curve25519: String,
        val sealing: String,
        val signature: String,
        /** Their current sealing prekey, signature not yet checked; null from an app or relay that has none. */
        val sealingPrekey: SealingPrekey?,
        /** A one-time key, or the fallback when none is left. */
        val sessionKey: SignedKey,
        /**
         * With a one-time key: the ticket the first envelope on the session built
         * from it presents ([send]'s claim), which spends the key on the relay.
         * Not presented within 15 minutes, the relay puts the key back in the pool.
         */
        val claim: String? = null
    )

    /**
     * One envelope from the relay. [alert] is what its sender asked a locked
     * phone to do with it before it can be opened: "call" (ring) or "sos".
     */
    data class Envelope(val id: String, val ts: Long, val data: ByteArray, val alert: String? = null)

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Registers a fresh identity and returns the Clairix number the relay
     * allocated. Admission is either the relay's enrollment [secret] or a
     * one-time [invite] code from someone already registered.
     */
    fun register(
        relayUrl: String,
        identity: Identity,
        secret: String?,
        listed: Boolean,
        invite: String? = null,
        transportKey: String? = null
    ): Registered {
        val bundle = identity.publicBundle()
        val body = JSONObject()
            // Dated, so a captured registration (it carries the secret) cannot be
            // replayed later; the relay accepts it within five minutes of its clock.
            .put("ts", relayNow())
            .apply { if (invite != null) put("invite", invite) else put("secret", secret ?: "") }
            .put("ed25519", bundle.ed25519)
            .put("curve25519", bundle.curve25519)
            .put("sealing", bundle.sealing)
            .put("signature", bundle.signature)
            .put("fallback", bundle.fallback?.let { signedKeyJson(it) } ?: JSONObject.NULL)
            .put("oneTimeKeys", JSONArray(bundle.oneTimeKeys.map { signedKeyJson(it) }))
            .apply { bundle.sealingPrekey?.let { put("sealingPrekey", prekeyJson(it)) } }
            .apply { if (transportKey != null) put("transportKey", transportKey) }
            .put("listed", listed)
            .toString()
        val request = Request.Builder()
            .url("${relayUrl.trimEnd('/')}/v1/register")
            .header("X-Clairix-Sig", identity.sign(body.toByteArray(Charsets.UTF_8)))
            .post(body.toRequestBody(JSON))
        val json = execute(request)
        return parsed { Registered(json.getString("number")) }
    }

    // ── Own mailbox ───────────────────────────────────────────────────────

    data class Me(val listed: Boolean, val oneTimeKeys: Int)

    fun me(): Me {
        val json = signed("GET", "/v1/me")
        return parsed { Me(json.getJSONObject("profile").optBoolean("listed", true), json.optInt("oneTimeKeys", 0)) }
    }

    /**
     * Uploads one-time keys and the fallback key, and the current sealing
     * prekey, which the relay takes only when it is newer than the one it holds.
     */
    fun putKeys(bundle: PublicBundle): Int {
        val body = JSONObject()
            .put("oneTimeKeys", JSONArray(bundle.oneTimeKeys.map { signedKeyJson(it) }))
            .put("fallback", bundle.fallback?.let { signedKeyJson(it) } ?: JSONObject.NULL)
            .apply { bundle.sealingPrekey?.let { put("sealingPrekey", prekeyJson(it)) } }
        return signed("PUT", "/v1/keys", body).optInt("oneTimeKeys", 0)
    }

    private fun prekeyJson(p: SealingPrekey): JSONObject =
        JSONObject().put("key", p.key).put("created", p.createdMs).put("signature", p.signature)

    /**
     * A sealing prekey in a relay's answer, in its expected shape; null when
     * absent or malformed. Its signature is checked before it is kept
     * ([CommsRepository]), as for every key a relay hands over.
     */
    private fun prekeyFrom(o: JSONObject?): SealingPrekey? {
        if (o == null) return null
        val key = o.optString("key").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val signature = o.optString("signature").takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val created = o.opt("created") as? Number ?: return null
        return SealingPrekey(key, created.toLong(), signature)
    }

    /** Gives the relay this phone's transport key, the one that may only collect envelopes. */
    fun putTransport(publicKey: String) {
        signed("PUT", "/v1/transport", JSONObject().put("key", publicKey))
    }

    data class Invite(val code: String, val expiresAt: Long)

    /** A one-time invite: lets one person register on this relay without the enrollment secret, for a week. */
    fun createInvite(): Invite {
        val json = signed("POST", "/v1/invites")
        return parsed { Invite(json.getString("code"), json.getLong("expiresAt")) }
    }

    fun setListed(listed: Boolean) { signed("PUT", "/v1/listed", JSONObject().put("listed", listed)) }

    /**
     * Gives the relay the push endpoint to wake this phone through (null: none).
     * True when it is in use at once; false when the relay first pushes a check
     * code through it, which [verifyPush] must echo before it is used.
     */
    fun setPush(endpoint: String?): Boolean =
        signed("PUT", "/v1/push", JSONObject().put("endpoint", endpoint ?: JSONObject.NULL)).optBoolean("verified", false)

    /** Echoes the check code that arrived by push; the pending endpoint goes live. */
    fun verifyPush(code: String) { signed("PUT", "/v1/push/verify", JSONObject().put("code", code)) }

    fun wipe() { signed("DELETE", "/v1/me") }

    /**
     * The account deletion, signed now, to be sent with [sendPrepared] after the
     * keys that sign it are gone: a wipe destroys them first and asks the relay
     * last, so the network never holds up what is erased on the phone. The
     * relay accepts it for its five-minute window.
     */
    fun preparedWipe(): Request? = runCatching {
        val builder = signedRequest("DELETE", "/v1/me", null)
        val signing = builder.build().tag(Signing::class.java) ?: return@runCatching null
        sign(builder, signing).tag(Signing::class.java, null).build()
    }.getOrNull()

    /** Sends a request [preparedWipe] made, on a connection of its own, waiting at most [timeoutSec]. */
    fun sendPrepared(request: Request, timeoutSec: Long) {
        runCatching { anonymousClient(timeoutSec).newCall(request).execute().close() }
    }

    // ── Contacts and envelopes ────────────────────────────────────────────

    /**
     * A contact's keys plus one key to start a session; [pin] unlocks an
     * unlisted number. The number asked about travels in the signed body,
     * never in the URL, so no request log anywhere names it.
     */
    fun bundle(number: String, pin: String?, relay: String? = null): Bundle {
        val json = signed("POST", "/v1/lookup", lookupBody(number, pin, claim = true, relay))
        return parsed {
            val b = json.getJSONObject("bundle")
            val key = b.optJSONObject("oneTimeKey") ?: b.optJSONObject("fallback")
                ?: throw RelayException("The relay has no session key for that number", MALFORMED)
            Bundle(
                number = b.getString("number"),
                ed25519 = b.getString("ed25519"),
                curve25519 = b.getString("curve25519"),
                sealing = b.getString("sealing"),
                signature = b.getString("signature"),
                sealingPrekey = prekeyFrom(b.optJSONObject("sealingPrekey")),
                sessionKey = SignedKey(key.getString("id"), key.getString("key"), key.getString("signature")),
                claim = if (b.optJSONObject("oneTimeKey") != null) b.optString("claim").takeIf { it.isNotEmpty() } else null
            )
        }
    }

    /** A contact's public identity keys, without the one-time key a bundle claims. */
    data class IdentityKeys(
        val number: String,
        val ed25519: String,
        val curve25519: String,
        val sealing: String,
        val signature: String,
        val sealingPrekey: SealingPrekey? = null
    )

    /**
     * Who holds [number] (unlisted numbers need their key's [pin]); claims none
     * of their keys. [relay]: the other relay the number is on, asked through
     * this phone's own (see the relay's federation.ts); null for this one.
     */
    fun identity(number: String, pin: String?, relay: String? = null): IdentityKeys {
        val json = signed("POST", "/v1/lookup", lookupBody(number, pin, claim = false, relay))
        return parsed {
            val o = json.getJSONObject("identity")
            IdentityKeys(
                o.getString("number"), o.getString("ed25519"), o.getString("curve25519"), o.getString("sealing"), o.getString("signature"),
                prekeyFrom(o.optJSONObject("sealingPrekey"))
            )
        }
    }

    private fun lookupBody(number: String, pin: String?, claim: Boolean, relay: String?): JSONObject {
        val body = JSONObject().put("number", number).put("claim", claim)
        if (pin != null) body.put("pin", pin)
        if (relay != null) body.put("relay", relay)
        return body
    }

    /**
     * Queues [envelope] for [to], anonymously: the request carries one delivery
     * token and no identity. [claim] is the ticket of the one-time key the
     * envelope's session was built on, for the first envelope of a session.
     * A token the relay refuses (expired, say) is replaced once from the pool.
     *
     * Tokens are never fetched here: a fetch right before a send would let the
     * relay pair the two. With the pool empty this throws a [RelayException]
     * with [NO_TOKENS]; the caller keeps the envelope queued and calls
     * [topUpTokens] before trying again.
     */
    fun send(to: String, envelope: ByteArray, claim: String? = null, relay: String? = null, urgent: Boolean = false, alert: String? = null): String {
        val body = JSONObject().put("to", to).put("envelope", Base64.getEncoder().encodeToString(envelope))
        if (claim != null) body.put("claim", claim)
        // Everything but a call being set up, an SOS or a group change asks
        // the relay to hold it a random few seconds and release it shuffled
        // with whatever else is due (mixing). The relay holds only what asks,
        // so apps from before mixing are never delayed.
        if (!urgent) body.put("mix", true)
        // A contact on another relay: this phone's relay forwards it there.
        if (relay != null) body.put("relay", relay)
        // A call's offer or an SOS: a locked recipient rings or sounds the alarm
        // before it can open the envelope.
        if (alert != null) body.put("alert", alert)
        val json = anonymous(TokenKind.DELIVERY, "POST", "/v1/send", body)
        return parsed { json.getString("id") }
    }

    /**
     * One request that spends a token of [kind] and names nobody, on the
     * anonymous connection. A token the relay refuses (expired, say) is
     * replaced once from the pool. Never fetches tokens: with the pool empty
     * this throws [NO_TOKENS], and the caller tops up apart from the request.
     */
    private fun anonymous(kind: TokenKind, method: String, pathAndQuery: String, body: JSONObject?): JSONObject {
        val relay = base() ?: throw RelayException("Not registered with a relay")
        val text = body?.toString()
        var refused: RelayException? = null
        repeat(2) {
            val token = nextToken(kind)
            val request = Request.Builder()
                .url(relay + pathAndQuery)
                .header("X-Clairix-Token", token)
                .header("Accept", "application/json")
            when (method.uppercase()) {
                "GET" -> request.get()
                else -> request.post((text ?: "{}").toRequestBody(JSON))
            }
            try {
                return execute(request, anonymousClient(12))
            } catch (e: RelayException) {
                if (e.code != 401 && e.code != 403) throw e
                refused = e
            }
        }
        throw refused ?: RelayException("The relay refused the ${kind.wire} token", 403)
    }

    /** Delivery tokens this phone holds, unexpired. */
    fun tokenCount(): Int = tokens.size(System.currentTimeMillis())

    /** How many of the delivery tokens held are blind ones (the rest came from a relay before Beta 6). */
    fun blindTokenCount(): Int = (tokens as? MemoryTokenPool)?.let { pool ->
        runCatching {
            val arr = JSONArray(pool.encode())
            (0 until arr.length()).count { arr.getJSONObject(it).optString("t").startsWith("2.") }
        }.getOrDefault(0)
    } ?: 0

    /**
     * Fetches a batch of delivery tokens when fewer than [low] are left. Called
     * at registration, on every sync and by the hourly key top-up, and after a
     * send found the pool empty; never by [send] itself, so tokens are fetched
     * apart from the sends that spend them.
     */
    fun topUpTokens(low: Int = TOKENS_LOW, batch: Int = TOKENS_BATCH): Int = topUpTokens(TokenKind.DELIVERY, low, batch)

    /** Fetches a batch of [kind] tokens when fewer than [low] are left; returns how many the pool then holds. */
    fun topUpTokens(kind: TokenKind, low: Int = kind.low, batch: Int = kind.batch): Int {
        val p = pool(kind)
        if (p.size(System.currentTimeMillis()) < low) fetchTokens(kind, batch)
        return p.size(System.currentTimeMillis())
    }

    /**
     * Keeps the traffic and report pools stocked. Called when a drive begins
     * and after each minute's polls, for the next minute's: never right before
     * the request that would spend one.
     */
    fun topUpTrafficTokens() {
        topUpTokens(TokenKind.TRAFFIC)
        topUpTokens(TokenKind.REPORT)
    }

    /** One token from the pool; none left is a [NO_TOKENS] failure for the caller to retry after a top-up. */
    private fun nextToken(kind: TokenKind): String =
        pool(kind).take(System.currentTimeMillis()) ?: throw RelayException("No ${kind.wire} tokens on this phone yet", NO_TOKENS)

    @Synchronized
    private fun fetchTokens(kind: TokenKind, count: Int) {
        val relay = base() ?: throw RelayException("Not registered with a relay")
        // A relay that ever published blind-token keys gets no plain tokens from
        // this phone again: plain ones it could match to this account, and a
        // relay that stopped publishing would be asking for exactly that.
        if (!tokenKeys.any(relay.trimEnd('/'))) {
            // Never seen any: a relay from before Beta 6, or this phone's first
            // fetch. Asked once here; a relay that publishes keys is pinned by it.
            if (info().tokens == null && !tokenKeys.any(relay.trimEnd('/'))) {
                fetchPlainTokens(kind, count)
                return
            }
        }
        fetchBlindTokens(relay, kind, count)
    }

    /** A relay before Beta 6: tokens it makes, and so could match to this account. */
    private fun fetchPlainTokens(kind: TokenKind, count: Int) {
        val body = JSONObject().put("count", count.coerceIn(1, TOKENS_BATCH)).put("kind", kind.wire)
        val json = signedOnceMore("/v1/tokens", body)
        parsed {
            val arr = json.getJSONArray("tokens")
            pool(kind).add((0 until arr.length()).map { arr.getString(it) }, json.getLong("expiresAt"))
        }
    }

    /**
     * Blind tokens (comms-worker/src/tokens.ts): this phone makes [count] random
     * inputs and sends the relay only their blinded forms. The relay's answer
     * comes with a proof that it used its key for [kind]; the key must be the one
     * it publishes at GET / to every caller, and the same one this phone saw
     * first in that epoch, or the batch is refused. Unblinded, each is a token
     * the relay can check but cannot trace to this account.
     */
    private fun fetchBlindTokens(relay: String, kind: TokenKind, count: Int) {
        val blinds = List(count.coerceIn(1, BLIND_TOKENS_BATCH)) { tokenBlind() }
        val body = JSONObject().put("kind", kind.wire).put("blinded", JSONArray(blinds.map { b64url(it.blinded) }))
        val json = signedOnceMore("/v1/tokens", body)
        val (epoch, key, evaluated, proof) = parsed {
            val arr = json.getJSONArray("evaluated")
            Quad(json.getLong("epoch"), json.getString("key"), (0 until arr.length()).map { fromB64url(arr.getString(it)) }, fromB64url(json.getString("proof")))
        }
        val origin = relay.trimEnd('/')
        if (!plausibleEpoch(epoch)) {
            throw RelayException("The relay issued tokens for epoch $epoch, which this phone's clock does not agree with; refused", TOKEN_KEY_MISMATCH)
        }
        // Not fetched now if missing: a fetch right after this signed request
        // would be one the relay could tell was this phone's, and answer with
        // keys of its own. The keys are read later, at a time of the phone's
        // choosing ([KEYS_PENDING] tells the caller to arrange it).
        val pinned = tokenKeys.get(origin, epoch)
            ?: throw RelayException("This phone has not yet seen the relay's keys for epoch $epoch; it will look them up and try again", KEYS_PENDING)
        if (pinned[kind.wire] != key) {
            throw RelayException("The relay issued ${kind.wire} tokens under a key it does not give everyone; refused", TOKEN_KEY_MISMATCH)
        }
        val outputs = try {
            tokenFinalize(blinds, evaluated, proof, fromB64url(key))
        } catch (e: Exception) {
            throw RelayException("The relay's ${kind.wire} tokens did not prove out against its published key; refused", TOKEN_KEY_MISMATCH)
        }
        val made = outputs.mapIndexed { i, out -> "2.$epoch.${b64url(blinds[i].input)}.${b64url(out)}" }
        parsed { pool(kind).add(made, json.getLong("expiresAt")) }
    }

    private data class Quad(val epoch: Long, val key: String, val evaluated: List<ByteArray>, val proof: ByteArray)

    /** A signed POST, sent once more after a 401: the first from a phone whose clock is off is refused, and taught this client the relay's time. */
    private fun signedOnceMore(path: String, body: JSONObject): JSONObject = try {
        signed("POST", path, body)
    } catch (e: RelayException) {
        if (e.code != 401) throw e
        signed("POST", path, body)
    }

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun fromB64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    /** Forgets every token of every kind (the phone is unpairing). */
    fun clearTokens() {
        tokens.clear()
        trafficTokens.clear()
        reportTokens.clear()
    }

    fun inbox(): List<Envelope> {
        val json = signed("GET", "/v1/inbox")
        return parsed {
            val arr = json.getJSONArray("envelopes")
            (0 until arr.length()).map { parseEnvelope(arr.getJSONObject(it)) }
        }
    }

    fun ack(ids: List<String>) {
        if (ids.isEmpty()) return
        signed("POST", "/v1/ack", JSONObject().put("ids", JSONArray(ids)))
    }

    /** One STUN or TURN server for a call, as the relay hands it out. */
    data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

    /** The last ICE server list the relay gave, reused when a fetch is slow or fails. */
    @Volatile
    private var lastIceServers: Pair<Long, List<IceServer>>? = null

    /** A client that gives up quickly: a call should not wait 15 s on a slow relay for its server list. */
    private val quick: OkHttpClient by lazy { rest.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    /**
     * ICE servers for a call: STUN always, TURN with credentials the relay mints
     * for this request when it has a key. A list fetched in the last few minutes
     * is reused only if the relay does not answer within four seconds.
     */
    fun turn(): List<IceServer> {
        val cached = lastIceServers?.takeIf { System.currentTimeMillis() - it.first < ICE_CACHE_MS }?.second
        val json = try {
            execute(signedRequest("GET", "/v1/turn", null), quick)
        } catch (e: RelayException) {
            if (cached != null) return cached
            throw e
        }
        val servers = parsed {
            val arr = json.getJSONArray("iceServers")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val urls = o.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.getString(it) } } ?: return@mapNotNull null
                IceServer(urls, o.optString("username").takeIf { it.isNotEmpty() }, o.optString("credential").takeIf { it.isNotEmpty() })
            }
        }
        if (servers.isNotEmpty()) lastIceServers = System.currentTimeMillis() to servers
        return servers
    }

    // ── ClairixCoin ─────────────────────────────────────────────────────────

    /** This identity's coins on this relay. Every relay is its own coin community. */
    data class CoinBalance(val balance: Long, val symbol: String)

    /** A payment the relay took: its id, and what is left afterwards. */
    data class PayResult(val txid: String, val balance: Long)

    /** One transfer as the relay recorded it; [direction] is "in" or "out" from this identity's side. */
    data class CoinTx(val id: String, val from: String, val to: String, val amount: Long, val ts: Long, val direction: String, val note: String)

    /** Refuses before anything is sent when the edition is built without ClairixCoin (the Play edition). */
    private fun requireCoin() {
        if (!BuildConfig.CLAIRIX_COIN) throw UnsupportedOperationException("ClairixCoin is not part of this edition")
    }

    fun balance(): CoinBalance {
        requireCoin()
        val json = signed("GET", "/v1/coin/balance")
        return parsed { CoinBalance(json.getLong("balance"), json.optString("symbol", "AC")) }
    }

    /**
     * Pays [amount] coins to [to]. Only the amount reaches the relay; the note
     * the payer wrote travels to the recipient inside an encrypted envelope
     * (see CommsWire.payment), never here.
     */
    fun pay(to: String, amount: Long, note: String?, txid: String = UUID.randomUUID().toString()): PayResult {
        requireCoin()
        val body = JSONObject().put("to", to).put("amount", amount).put("txid", txid)
        if (!note.isNullOrBlank()) body.put("note", note)
        // The payment is named here, and the relay makes it once per name: a
        // request whose answer was lost on the network is repeated under the
        // same name, and cannot move the coins twice. An answer the relay gave
        // (any HTTP status) is final either way.
        val json = try {
            signed("POST", "/v1/coin/pay", body)
        } catch (e: RelayException) {
            if (e.code != 0) throw e
            signed("POST", "/v1/coin/pay", body)
        }
        return parsed { PayResult(json.getString("txid"), json.getLong("balance")) }
    }

    fun coinHistory(): List<CoinTx> {
        requireCoin()
        val json = signed("GET", "/v1/coin/history")
        return parsed {
            val arr = json.getJSONArray("txs")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoinTx(
                    o.getString("id"), o.getString("from"), o.getString("to"), o.getLong("amount"), o.getLong("ts"),
                    o.optString("direction", "out"), o.optString("note", "")
                )
            }
        }
    }

    // ── Traffic ───────────────────────────────────────────────────────────
    //
    // The relay keeps one traffic store per map region (comms-worker Traffic
    // object). Every traffic request is anonymous: it spends a traffic token
    // (a new report, a report token) and carries no identity. Probes, reports
    // and votes carry a trip pseudonym (app.clairix.nav.TripPseudonym) the
    // phone made up for the drive, so the relay can count vehicles and votes
    // without anything that reaches back to this phone.

    /** Posts up to 60 per-segment speed samples ({"seg","kph","t"}) under [trip]; returns the relay's reply ({"ok","accepted"}). */
    fun trafficProbe(region: String, trip: String, samples: JSONArray): JSONObject =
        anonymous(TokenKind.TRAFFIC, "POST", "/v1/traffic/probe", JSONObject().put("region", region).put("trip", trip).put("samples", samples))

    /** Live segment speeds and current incidents for [region]: {"now","segments","incidents"}. */
    fun trafficLive(region: String): JSONObject =
        anonymous(TokenKind.TRAFFIC, "GET", "/v1/traffic/live?region=${query(region)}", null)

    /** Hour-of-week speed profiles for at most 500 segments: {"segments":{seg:{"h":[168]}}}. */
    fun trafficTypical(region: String, segs: List<String>): JSONObject {
        require(segs.size <= TRAFFIC_TYPICAL_MAX) { "At most $TRAFFIC_TYPICAL_MAX segments per request" }
        return anonymous(TokenKind.TRAFFIC, "GET", "/v1/traffic/typical?region=${query(region)}&segs=${query(segs.joinToString(","))}", null)
    }

    /** Reports an incident ("police", "crash", "hazard", "closure" or "jam") under [trip]; returns {"incident"}. */
    fun trafficReport(region: String, trip: String, type: String, lat: Double, lon: Double, seg: String?): JSONObject {
        val body = JSONObject()
            .put("region", region)
            .put("trip", trip)
            .put("type", type)
            .put("lat", lat)
            .put("lon", lon)
            .put("seg", seg ?: JSONObject.NULL)
        return anonymous(TokenKind.REPORT, "POST", "/v1/traffic/report", body)
    }

    /** Confirms ([still] = true) or clears an incident, one vote per [trip]; returns {"incident"}, null once it is removed. */
    fun trafficVote(region: String, trip: String, id: String, still: Boolean): JSONObject =
        anonymous(TokenKind.TRAFFIC, "POST", "/v1/traffic/report/vote", JSONObject().put("region", region).put("trip", trip).put("id", id).put("still", still))

    /**
     * HERE / TomTom incidents the relay proxies for a box of at most 1° × 1°,
     * [bbox] = [minLat, minLon, maxLat, maxLon]:
     * {"now","incidents":[{"id","type","lat","lon","geom","road","description","start","end","delay_s","source"}],
     *  "providers":{"here","tomtom"}} with each provider "ok", "off", "quota" or "error".
     */
    fun trafficIncidents(bbox: DoubleArray): JSONObject {
        require(bbox.size == 4) { "bbox must be [minLat, minLon, maxLat, maxLon]" }
        val (minLat, minLon, maxLat, maxLon) = bbox.toList()
        require(minLat < maxLat && minLon < maxLon) { "bbox minimums must be below its maximums" }
        require(maxLat - minLat <= TRAFFIC_INCIDENTS_MAX_SPAN_DEG && maxLon - minLon <= TRAFFIC_INCIDENTS_MAX_SPAN_DEG) {
            "bbox may span at most $TRAFFIC_INCIDENTS_MAX_SPAN_DEG° in each direction"
        }
        // Rounded inward to 5 decimals so rounding can never push a 1° box over the relay's limit;
        // fixed-point and Locale.ROOT because Double.toString can give "1.0E-4", and some locales a decimal comma.
        val inward = listOf(
            kotlin.math.ceil(minLat * 1e5) / 1e5,
            kotlin.math.ceil(minLon * 1e5) / 1e5,
            kotlin.math.floor(maxLat * 1e5) / 1e5,
            kotlin.math.floor(maxLon * 1e5) / 1e5,
        )
        val box = inward.joinToString(",") { String.format(java.util.Locale.ROOT, "%.5f", it) }
        return anonymous(TokenKind.TRAFFIC, "GET", "/v1/traffic/incidents?bbox=${query(box)}", null)
    }

    fun trafficIncidents(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): JSONObject =
        trafficIncidents(doubleArrayOf(minLat, minLon, maxLat, maxLon))

    private fun query(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Opens the live-delivery socket. The caller owns the returned socket. */
    fun openSocket(listener: WebSocketListener): WebSocket {
        // OkHttp runs no network interceptors for a WebSocket upgrade, so it is
        // signed here, once; a refused upgrade is retried by the caller.
        val builder = signedRequest("GET", "/v1/ws", null)
        val signing = builder.build().tag(Signing::class.java) ?: throw RelayException("Not registered with a relay")
        if (signing.transport == null) return http.newWebSocket(sign(builder, signing).build(), listener)
        // A refused upgrade signed with the transport key means the relay does not
        // have it: the next attempt is signed with the identity, and it is republished.
        return http.newWebSocket(sign(builder, signing).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket, response)
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(webSocket, code, reason)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(webSocket, code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) transportRefused()
                listener.onFailure(webSocket, t, response)
            }
        })
    }

    fun parseEnvelope(o: JSONObject): Envelope = parsed {
        Envelope(
            o.getString("id"), o.getLong("ts"), Base64.getDecoder().decode(o.getString("data")),
            o.optString("alert").takeIf { it == ALERT_CALL || it == ALERT_SOS }
        )
    }

    /**
     * Runs response parsing, turning a missing or malformed field into a
     * [RelayException] with [MALFORMED] so a version mismatch or a hostile relay
     * fails the one call instead of crashing the process.
     */
    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw RelayException("The relay returned an unexpected response: ${e.message}", MALFORMED)
    } catch (e: IllegalArgumentException) {
        throw RelayException("The relay returned an unexpected response: ${e.message}", MALFORMED)
    }

    // ── Signing ───────────────────────────────────────────────────────────

    private fun signed(method: String, pathAndQuery: String, body: JSONObject? = null): JSONObject {
        val builder = signedRequest(method, pathAndQuery, body)
        return try {
            execute(builder)
        } catch (e: RelayException) {
            // The relay no longer has the transport key (or never took it): sign
            // with the identity this once, if it is at hand, and republish.
            val viaTransport = builder.build().tag(Signing::class.java)?.transport != null
            if (!viaTransport || (e.code != 401 && e.code != 403)) throw e
            transportRefused()
            if (identity() == null) throw e
            execute(signedRequest(method, pathAndQuery, body, allowTransport = false))
        }
    }

    private fun signedRequest(method: String, pathAndQuery: String, body: JSONObject?, allowTransport: Boolean = true): Request.Builder {
        val relay = base() ?: throw RelayException("Not registered with a relay")
        val number = number() ?: throw RelayException("Not registered with a relay")
        val route = "${method.uppercase()} ${pathAndQuery.substringBefore('?')}"
        val viaTransport = if (allowTransport && route in TRANSPORT_ROUTES) transport() else null
        if (viaTransport == null && identity() == null) throw RelayException("No identity on this device")
        val bodyText = body?.toString() ?: ""
        val builder = Request.Builder()
            .url(relay + pathAndQuery)
            .header("X-Clairix-Number", number)
            .header("Accept", "application/json")
            .tag(Signing::class.java, Signing(method.uppercase(), pathAndQuery, bodyText, viaTransport))
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PUT" -> builder.put(bodyText.toRequestBody(JSON))
            else -> builder.post(bodyText.toRequestBody(JSON))
        }
        return builder
    }

    /** Adds the signature headers for one attempt: a fresh nonce and the relay's current time. */
    private fun sign(builder: Request.Builder, s: Signing): Request.Builder {
        val number = number() ?: throw RelayException("Not registered with a relay")
        val signer: (ByteArray) -> String = s.transport?.let { t -> { bytes: ByteArray -> t.sign(bytes) } }
            ?: (identity() ?: throw RelayException("No identity on this device")).let { id -> { bytes: ByteArray -> id.sign(bytes) } }
        // The relay rejects a timestamp more than five minutes from its own clock,
        // so a phone whose clock is off signs with the relay's time once it knows it.
        val ts = relayNow()
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val payload = "$number\n$ts\n$nonce\n${s.method}\n${s.pathAndQuery}\n${sha256Hex(s.bodyText)}"
        return builder
            .header("X-Clairix-Number", number)
            .header("X-Clairix-Ts", ts.toString())
            .header("X-Clairix-Nonce", nonce)
            .header("X-Clairix-Sig", signer(payload.toByteArray(Charsets.UTF_8)))
            .apply { if (s.transport != null) header("X-Clairix-Key", "transport") else removeHeader("X-Clairix-Key") }
    }

    /**
     * Runs the request at the address it was built for; if that address does
     * not answer at all (a network failure, never a refusal), tries it at each
     * other address the relay answers at and keeps the one that does.
     */
    private fun execute(builder: Request.Builder, client: OkHttpClient = rest): JSONObject {
        val url = builder.build().url.toString()
        val current = base()
        try {
            return executeOnce(builder, client)
        } catch (e: RelayException) {
            if (e.code != 0 || current == null || !url.startsWith(current)) throw e
            var last = e
            for (other in otherBases(current)) {
                try {
                    val json = executeOnce(builder.url(other + url.removePrefix(current)), client)
                    reachable = other
                    return json
                } catch (again: RelayException) {
                    // An answer from the relay, whatever it says, stands; only silence moves on.
                    if (again.code != 0) throw again
                    last = again
                }
            }
            throw last
        }
    }

    private fun executeOnce(builder: Request.Builder, client: OkHttpClient): JSONObject {
        // Every network failure, including one while reading the response body
        // (a network switch mid-request), surfaces as a RelayException with code
        // 0, which callers treat as "offline, retry later".
        try {
            client.newCall(builder.build()).execute().use { res ->
                noteServerTime(res)
                val text = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!res.isSuccessful) {
                    val reason = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${res.code}"
                    throw RelayException(reason, res.code)
                }
                return json ?: throw RelayException("The relay returned something that is not JSON", MALFORMED)
            }
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException("Could not reach the relay: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── Relay clock ───────────────────────────────────────────────────────

    /** Relay time minus this phone's time, learned from the `Date` header of every response. */
    @Volatile
    private var clockOffsetMs: Long = 0L

    /**
     * Told (relay time, phone time) for every response that carries a `Date`;
     * the app points it at the clock-integrity store, which this Android-free
     * class cannot reach itself. Called on OkHttp's threads.
     */
    @Volatile
    var clockListener: ((serverMs: Long, localMs: Long) -> Unit)? = null

    /**
     * The current time by the relay's clock. Envelope timestamps are the relay's,
     * so anything that measures an envelope's age (a call offer that waited too
     * long to ring) compares against this rather than the phone's own clock,
     * which may be minutes off.
     */
    fun relayNow(): Long = System.currentTimeMillis() + clockOffsetMs

    /**
     * Records the relay's clock from [response]; called for every HTTP and
     * socket response. Returns true when that moved this phone's idea of the
     * relay's time by more than half a minute.
     */
    fun noteServerTime(response: Response): Boolean {
        val server = response.headers.getDate("Date")?.time ?: return false
        val local = System.currentTimeMillis()
        runCatching { clockListener?.invoke(server + 500L, local) }
        // The header has one-second resolution; half a second centres the error.
        val offset = server + 500L - local
        val previous = clockOffsetMs
        clockOffsetMs = offset
        val moved = kotlin.math.abs(offset - previous) > CLOCK_WARN_MS
        if (kotlin.math.abs(offset) > CLOCK_WARN_MS && moved) {
            CommsLog.add("This phone's clock is ${offset / 1000}s off the relay's; using the relay's time")
        }
        return moved
    }

    private fun signedKeyJson(k: SignedKey) = JSONObject().put("id", k.id).put("key", k.key).put("signature", k.signature)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        /** [RelayException.code] for a response the client could not parse; not a network failure. */
        const val MALFORMED = -1
        /** [RelayException.code] for a send with no delivery token left: retry after [topUpTokens]. */
        const val NO_TOKENS = -2
        /** The relay's tokens were not made with the key it publishes to everyone, or the first one seen this epoch. */
        const val TOKEN_KEY_MISMATCH = -5
        /** Tokens were issued for an epoch whose keys this phone has not pinned yet: look them up later, anonymously, and retry. */
        const val KEYS_PENDING = -6
        /** A token epoch: 14 days, as the relay's (comms-worker/src/tokens.ts TOKEN_EPOCH_MS). */
        const val TOKEN_EPOCH_MS = 14L * 24 * 60 * 60_000
        /** Envelopes the relay returns per inbox fetch (Mailbox.inbox's limit). */
        const val INBOX_PAGE = 200
        /** An envelope's sender asks a locked phone to ring for it (comms-worker Alert). */
        const val ALERT_CALL = "call"
        /** An envelope's sender asks a locked phone to sound an SOS for it. */
        const val ALERT_SOS = "sos"
        /** What the relay lets the transport key sign (comms-worker/src/index.ts TRANSPORT_ROUTES). */
        private val TRANSPORT_ROUTES = setOf("GET /v1/inbox", "POST /v1/ack", "GET /v1/ws")
        /** Segments per /v1/traffic/typical request (the relay's limit). */
        const val TRAFFIC_TYPICAL_MAX = 500
        /** The relay refuses incident boxes larger than this in either direction. */
        const val TRAFFIC_INCIDENTS_MAX_SPAN_DEG = 1.0
        private const val CLOCK_WARN_MS = 30_000L
        /** The relay mints TURN credentials per request, valid two hours; a fallback copy is kept far less. */
        private const val ICE_CACHE_MS = 10 * 60_000L
        /** Delivery tokens fetched at a time (the relay's maximum), and the level that triggers a fetch. */
        const val TOKENS_BATCH = 100
        /** Blind tokens asked for at a time: each costs the relay about a quarter of a millisecond to sign. */
        const val BLIND_TOKENS_BATCH = 50
        const val TOKENS_LOW = 40
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The canonical origin of a relay address, `https://host[:port]` in
         * lower case, as relays compare them (federation.ts relayOrigin): no
         * path, query or credentials, and a host name rather than an IP
         * literal. Null when [url] is not one.
         */
        fun originOf(url: String?): String? {
            val u = runCatching { java.net.URI(url?.trim()?.trimEnd('/') ?: return null) }.getOrNull() ?: return null
            if (BuildConfig.DEBUG) loopbackOrigin(u)?.let { return it }
            if (!u.scheme.equals("https", ignoreCase = true) || u.rawUserInfo != null) return null
            if (!u.rawPath.isNullOrEmpty() || u.rawQuery != null || u.rawFragment != null) return null
            val host = u.host?.lowercase() ?: return null
            if (host.startsWith("[") || host.all { it.isDigit() || it == '.' } || '.' !in host) return null
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null
            return if (u.port == -1 || u.port == 443) "https://$host" else "https://$host:${u.port}"
        }

        /**
         * `http://127.0.0.1:port` or `http://localhost:port`: a relay on the
         * computer this phone (or emulator, through adb reverse) is attached
         * to, as the end-to-end tests run them. Debug builds only, as relays
         * admit such addresses only when they themselves run on loopback
         * (federation.ts isLoopbackDev); a release build never talks in the clear.
         */
        fun loopbackOrigin(u: java.net.URI): String? {
            if (!u.scheme.equals("http", ignoreCase = true) || u.rawUserInfo != null || u.port <= 0) return null
            if (!u.rawPath.isNullOrEmpty() || u.rawQuery != null || u.rawFragment != null) return null
            val host = u.host?.lowercase() ?: return null
            return if (host == "127.0.0.1" || host == "localhost") "http://$host:${u.port}" else null
        }

        /** Whether [url] is a relay address registration accepts: https, or loopback in a debug build. */
        fun acceptableRelayUrl(url: String): Boolean =
            url.startsWith("https://") || (BuildConfig.DEBUG && runCatching { loopbackOrigin(java.net.URI(url)) }.getOrNull() != null)
    }
}
