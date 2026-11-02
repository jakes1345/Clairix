package app.clairix.comms.lora

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * Lite messages on LoRa: short texts and SOS between this phone and the people it
 * paired with over the radio, most of all someone carrying a LilyGO T-Deck, which
 * has no Clairix app and so no relay session, only its own key. It works exactly as
 * the handheld's own messenger does (lora/core/src/handheld.cpp), so the two
 * understand each other:
 *
 * A message is sent at once and again after 20 s, 1, 3, 10 and 30 minutes until the
 * contact's radio acknowledges it with a Lite Ack, sealed like the message. Each
 * repeat is sealed afresh: the same bytes again would be dropped unheard by every
 * radio that heard the first copy, the contact's included. Every copy that arrives
 * here is acknowledged, and copies are told apart by the message's id. With no
 * acknowledgement after the last try the message is marked failed, and can be sent
 * again as a new one.
 *
 * Only messages from contacts are opened at all ([LiteCodec.open] checks the sender
 * before the second key agreement), so a stranger on the network cannot put text on
 * this phone's screen. Lite has no forward secrecy: its key is this phone's sealing
 * key, and anyone who later takes that key and kept the radio traffic can read what
 * was sent. Full Clairix messages over LoRa (bundles) keep their ratchet; Lite is for
 * those who have only a handheld.
 *
 * Not thread safe on its own: [Lora] calls it under one lock.
 */
class LoraMessenger(
    private val codec: LiteCodec,
    /** Puts a sealed Lite body on the air; false when no radio took it. */
    private val send: (ByteArray) -> Boolean,
    private val random: java.util.Random = SecureRandom()
) {
    companion object {
        const val KIND_TEXT = 1
        const val KIND_ACK = 2
        const val KIND_SOS = 3

        /** As the handheld: the inner part holds at most 155 bytes, less 9 of header and 1 of padding mark. */
        const val TEXT_MAX = 145
        val RETRY_MS = longArrayOf(20_000, 60_000, 180_000, 600_000, 1_800_000)
        const val MAX_CONTACTS = 256
        const val MAX_MESSAGES = 3_000
        private const val SEEN_MAX = 1_024
        const val NAME_MAX = 24
        private const val MINUTE_MS = 60_000L
    }

    enum class State { SENDING, SENT, DELIVERED, RECEIVED, FAILED }

    /** One person paired over the radio: their Lite key and the name they gave. */
    data class Contact(val key: ByteArray, val name: String, val addedMs: Long, val unread: Int = 0) {
        val keyHex: String get() = hex(key)
        override fun equals(other: Any?) = other is Contact && key.contentEquals(other.key) && name == other.name && unread == other.unread
        override fun hashCode() = key.contentHashCode()
    }

    data class Message(
        val contact: String,
        val outgoing: Boolean,
        val id: Long,
        /** When it was written, by its writer's clock, in ms since 1970; when it arrived if that clock was unset. */
        val atMs: Long,
        val state: State,
        val kind: Int,
        val text: String,
        val hasPosition: Boolean = false,
        val lat7: Int = 0,
        val lon7: Int = 0,
        /** How strongly the radio heard it, for a received message; null when not known. */
        val rssi: Int? = null
    )

    private class Pending(val id: Long, val contact: String, val inner: ByteArray, var tries: Int, var next: Long)

    private val contacts = ArrayList<Contact>()
    private val messages = ArrayList<Message>()
    private val pending = ArrayList<Pending>()
    /** Received (contact, id) pairs, so a copy is acknowledged again but shown once. */
    private val seen = LinkedHashSet<String>()

    /** Set when something worth saving or showing changed; the caller saves and clears it. */
    var dirty = false

    /** Told of a message for the owner as it arrives. */
    var onIncoming: ((Contact, Message) -> Unit)? = null

    fun contacts(): List<Contact> = contacts.toList()
    fun messages(contact: String): List<Message> = messages.filter { it.contact == contact }
    fun contact(keyHex: String): Contact? = contacts.firstOrNull { it.keyHex == keyHex }
    fun contactKeys(): List<ByteArray> = contacts.map { it.key }

    // ── Contacts ──────────────────────────────────────────────────────────

    /** Adds a paired contact, or renames one paired again. False for this phone's own key or a full list. */
    fun addContact(key: ByteArray, name: String, now: Long, ownKey: ByteArray): Boolean {
        if (key.size != LoraLinkProtocol.KEY_LEN || key.contentEquals(ownKey)) return false
        val clean = cleanName(name)
        val at = contacts.indexOfFirst { it.key.contentEquals(key) }
        if (at >= 0) {
            contacts[at] = contacts[at].copy(name = clean)
        } else {
            if (contacts.size >= MAX_CONTACTS) return false
            contacts += Contact(key.copyOf(), clean, now)
        }
        dirty = true
        return true
    }

    fun rename(keyHex: String, name: String) {
        val at = contacts.indexOfFirst { it.keyHex == keyHex }
        if (at < 0) return
        contacts[at] = contacts[at].copy(name = cleanName(name))
        dirty = true
    }

    /** Removes a contact and everything said with them; their messages are no longer opened. */
    fun removeContact(keyHex: String) {
        if (contacts.removeAll { it.keyHex == keyHex }) {
            messages.removeAll { it.contact == keyHex }
            pending.removeAll { it.contact == keyHex }
            dirty = true
        }
    }

    fun markRead(keyHex: String) {
        val at = contacts.indexOfFirst { it.keyHex == keyHex }
        if (at >= 0 && contacts[at].unread != 0) {
            contacts[at] = contacts[at].copy(unread = 0)
            dirty = true
        }
    }

    // ── Sending ───────────────────────────────────────────────────────────

    /** Sends [text] to [keyHex]; null when there is no such contact, or the text is empty or too long. */
    fun sendText(keyHex: String, text: String, now: Long): Message? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (text.isBlank() || bytes.size > TEXT_MAX) return null
        return queue(keyHex, KIND_TEXT, bytes, Message(keyHex, true, 0, now, State.SENDING, KIND_TEXT, text), now)
    }

    /** An SOS with where this phone is, if known; the text is cut to what fits beside the position. */
    fun sendSos(keyHex: String, position: Pair<Int, Int>?, text: String, now: Long): Message? {
        val room = TEXT_MAX - if (position != null) 9 else 1
        val cut = cutUtf8(text, room)
        val m = Message(
            keyHex, true, 0, now, State.SENDING, KIND_SOS, cut,
            hasPosition = position != null, lat7 = position?.first ?: 0, lon7 = position?.second ?: 0
        )
        return queue(keyHex, KIND_SOS, codec.sosPayload(position, cut), m, now)
    }

    /** Sends a failed message again, as a new one. */
    fun resend(failed: Message, now: Long): Message? {
        if (!failed.outgoing || failed.state != State.FAILED) return null
        return if (failed.kind == KIND_SOS) {
            sendSos(failed.contact, if (failed.hasPosition) failed.lat7 to failed.lon7 else null, failed.text, now)
        } else {
            sendText(failed.contact, failed.text, now)
        }
    }

    private fun queue(keyHex: String, kind: Int, payload: ByteArray, draft: Message, now: Long): Message? {
        val contact = contact(keyHex) ?: return null
        val id = random.nextInt().toLong() and 0xFFFFFFFFL
        val inner = codec.encode(kind, id, minutes(now), payload) ?: return null
        val body = codec.seal(contact.key, inner) ?: return null
        val m = draft.copy(id = id, state = if (send(body)) State.SENT else State.SENDING)
        pending += Pending(id, keyHex, inner, 0, now + RETRY_MS[0])
        push(m)
        return m
    }

    private fun ack(contact: Contact, id: Long, now: Long) {
        val idBytes = byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
        val ackId = random.nextInt().toLong() and 0xFFFFFFFFL
        val inner = codec.encode(KIND_ACK, ackId, minutes(now), idBytes) ?: return
        codec.seal(contact.key, inner)?.let { send(it) }
    }

    /** Resends what is due, and marks failed what has had its last try. Call about once a second. */
    fun tick(now: Long) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (now < p.next) continue
            if (p.tries >= RETRY_MS.size) {
                setState(p.contact, p.id) { s -> if (s != State.DELIVERED) State.FAILED else s }
                it.remove()
                continue
            }
            val contact = contact(p.contact)
            if (contact == null) { it.remove(); continue }
            // Sealed afresh: the same bytes again would be dropped by every radio that heard the first copy.
            val sent = codec.seal(contact.key, p.inner)?.let { body -> send(body) } ?: false
            if (sent) setState(p.contact, p.id) { s -> if (s == State.SENDING) State.SENT else s }
            p.tries++
            p.next = now + RETRY_MS[minOf(p.tries, RETRY_MS.size - 1)]
        }
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /** A Lite body off the air. True when it was for this phone, from a contact, and changed something. */
    fun onLite(body: ByteArray, rssi: Int?, now: Long): Boolean {
        val opened = codec.open(body, contactKeys()) ?: return false
        val contact = contacts.firstOrNull { it.key.contentEquals(opened.first) } ?: return false
        val lm = codec.decode(opened.second) ?: return false
        if (lm.kind == KIND_ACK) {
            if (lm.payload.size != 4) return false
            val acked = ((lm.payload[0].toLong() and 0xFF) shl 24) or ((lm.payload[1].toLong() and 0xFF) shl 16) or
                ((lm.payload[2].toLong() and 0xFF) shl 8) or (lm.payload[3].toLong() and 0xFF)
            pending.removeAll { it.id == acked && it.contact == contact.keyHex }
            return setState(contact.keyHex, acked) { State.DELIVERED }
        }
        // Every copy is answered: the sender keeps trying until an Ack gets through.
        ack(contact, lm.id, now)
        val key = "${contact.keyHex}/${lm.id}"
        if (!seen.add(key)) return false
        while (seen.size > SEEN_MAX) seen.remove(seen.first())
        if (messages.any { !it.outgoing && it.contact == contact.keyHex && it.id == lm.id }) return false
        val at = if (lm.sentMinutes > 0) lm.sentMinutes * MINUTE_MS else now
        val m = if (lm.kind == KIND_SOS) {
            val sos = codec.parseSos(lm.payload) ?: return false
            Message(contact.keyHex, false, lm.id, at, State.RECEIVED, KIND_SOS, sos.text, sos.position != null,
                sos.position?.first ?: 0, sos.position?.second ?: 0, rssi)
        } else {
            Message(contact.keyHex, false, lm.id, at, State.RECEIVED, KIND_TEXT, String(lm.payload, Charsets.UTF_8), rssi = rssi)
        }
        val ci = contacts.indexOf(contact)
        contacts[ci] = contact.copy(unread = contact.unread + 1)
        push(m)
        onIncoming?.invoke(contacts[ci], m)
        return true
    }

    // ── Keeping ───────────────────────────────────────────────────────────

    private fun push(m: Message) {
        messages += m
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        dirty = true
    }

    private fun setState(contact: String, id: Long, f: (State) -> State): Boolean {
        var changed = false
        for (i in messages.indices) {
            val m = messages[i]
            if (m.outgoing && m.contact == contact && m.id == id) {
                val s = f(m.state)
                if (s != m.state) { messages[i] = m.copy(state = s); changed = true }
            }
        }
        if (changed) dirty = true
        return changed
    }

    fun toJson(): JSONObject {
        val b64 = Base64.getEncoder()
        val cs = JSONArray()
        for (c in contacts) cs.put(JSONObject().put("k", b64.encodeToString(c.key)).put("n", c.name).put("a", c.addedMs).put("u", c.unread))
        val ms = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("c", m.contact).put("o", m.outgoing).put("i", m.id).put("t", m.atMs)
                .put("s", m.state.name).put("k", m.kind).put("x", m.text)
            if (m.hasPosition) o.put("la", m.lat7).put("lo", m.lon7)
            m.rssi?.let { o.put("r", it) }
            ms.put(o)
        }
        return JSONObject().put("v", 1).put("contacts", cs).put("messages", ms)
    }

    /**
     * Restores what [toJson] wrote. What was still being sent when the app stopped
     * is marked failed: its retries were not kept, and the owner can send it again.
     */
    fun load(root: JSONObject) {
        contacts.clear(); messages.clear(); pending.clear(); seen.clear()
        val b64 = Base64.getDecoder()
        root.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) contactFrom(arr.optJSONObject(i), b64)?.let { contacts += it }
        }
        var failed = false
        root.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val state = runCatching { State.valueOf(o.getString("s")) }.getOrNull() ?: continue
                val outgoing = o.optBoolean("o")
                val cutOff = outgoing && (state == State.SENDING || state == State.SENT)
                failed = failed || cutOff
                val m = messageFrom(o, outgoing, if (cutOff) State.FAILED else state)
                messages += m
                if (!outgoing) seen += "${m.contact}/${m.id}"
            }
        }
        dirty = failed
    }

    /** One saved contact, or null when its key is missing or the wrong length. */
    private fun contactFrom(o: JSONObject?, b64: Base64.Decoder): Contact? {
        if (o == null) return null
        val k = runCatching { b64.decode(o.getString("k")) }.getOrNull() ?: return null
        if (k.size != LoraLinkProtocol.KEY_LEN) return null
        return Contact(k, o.optString("n"), o.optLong("a"), o.optInt("u"))
    }

    private fun messageFrom(o: JSONObject, outgoing: Boolean, state: State): Message = Message(
        o.optString("c"), outgoing, o.optLong("i"), o.optLong("t"), state,
        o.optInt("k", KIND_TEXT), o.optString("x"),
        o.has("la"), o.optInt("la"), o.optInt("lo"), if (o.has("r")) o.optInt("r") else null
    )

    private fun minutes(now: Long): Long = now / MINUTE_MS

    private fun cleanName(name: String): String =
        cutUtf8(name.filter { !it.isISOControl() }.trim(), NAME_MAX).ifBlank { "Radio contact" }
}

/** What Lite needs from the key and the codec; [UniffiLiteCodec] in the app, a stand-in in tests. */
interface LiteCodec {
    class Inner(val kind: Int, val id: Long, val sentMinutes: Long, val payload: ByteArray)
    class Sos(val position: Pair<Int, Int>?, val text: String)

    fun encode(kind: Int, id: Long, sentMinutes: Long, payload: ByteArray): ByteArray?
    fun decode(inner: ByteArray): Inner?
    fun seal(peer: ByteArray, inner: ByteArray): ByteArray?
    /** The sender's key and the inner part, when [body] is for this phone and from one of [contacts]. */
    fun open(body: ByteArray, contacts: List<ByteArray>): Pair<ByteArray, ByteArray>?
    fun sosPayload(position: Pair<Int, Int>?, text: String): ByteArray
    fun parseSos(payload: ByteArray): Sos?
}

internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/** [s] cut to at most [max] bytes of UTF-8, never inside a character. */
internal fun cutUtf8(s: String, max: Int): String {
    if (s.toByteArray(Charsets.UTF_8).size <= max) return s
    var end = s.length
    while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > max) end--
    if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
    return s.substring(0, end)
}
