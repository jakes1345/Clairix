package app.clairix.comms

import org.json.JSONObject

/**
 * Who reaches this phone: blocked identities, and message requests.
 *
 * Blocking puts an identity's keys and its number (with the relay it is on)
 * on a list ([Blocked]) that outlives its contact, so whatever it sends next
 * is dropped before anything else happens: nothing is stored, receipted,
 * notified or logged by name, a call never rings, and its group messages are
 * hidden. The blocked contact can be kept, its conversation a record, or
 * deleted with everything else about it; the list remembers it either way.
 *
 * A first message from someone who is not a contact makes them a request,
 * not a contact: what they send is kept, but no receipt goes back, a call
 * from them never rings and the notification says only that someone new
 * wrote, until the owner accepts. Declining blocks them. Two kinds of
 * newcomer still come in as contacts, as they always did: someone who joined
 * with an invite this phone made and says so, and a member of a group this
 * phone is in writing to the group, a conversation the owner joined.
 *
 * Pure Kotlin and org.json, so the JVM tests use exactly this code.
 */
object ContactGate {

    /** An identity on the blocked list: its keys, its number and relay (null for this phone's own), the name it had, and when. */
    data class Blocked(
        val id: Long,
        val ed25519: String,
        val curve25519: String,
        val number: String,
        val relay: String?,
        val name: String,
        val ts: Long
    )

    /**
     * Whether [blocked] holds a sender with these keys, or the number it
     * claims on the relay it claims ([relay] null for this phone's own). Either
     * key is enough, so a blocked identity that moved to another number is
     * still blocked; so is the number, on its relay, whatever keys come with it.
     * Any argument may be null when it is not known yet.
     */
    fun isBlocked(blocked: List<Blocked>, ed25519: String?, curve25519: String?, number: String?, relay: String?): Boolean =
        blocked.any { matches(it, ed25519, curve25519, number, relay) }

    fun matches(b: Blocked, ed25519: String?, curve25519: String?, number: String?, relay: String?): Boolean =
        (!ed25519.isNullOrEmpty() && b.ed25519.isNotEmpty() && b.ed25519 == ed25519) ||
            (!curve25519.isNullOrEmpty() && b.curve25519.isNotEmpty() && b.curve25519 == curve25519) ||
            (number != null && b.number == number && b.relay == relay)

    // ── Message requests ────────────────────────────────────────────────

    /** How an unknown sender, whose keys the relay has confirmed, comes in. */
    enum class Admission {
        /** An ordinary, unverified contact: someone this phone invited, or a member of one of its groups writing to the group. */
        CONTACT,
        /** A message request, held until the owner accepts it. */
        REQUEST
    }

    /**
     * Whether [payload] belongs to a group conversation, and so can only come
     * from someone the group's roster names. An invitation to a group is not:
     * it introduces a group this phone is not in yet.
     */
    fun groupPayload(payload: JSONObject): Boolean = when (CommsWire.type(payload)) {
        CommsWire.T_GROUP_MSG, CommsWire.T_THREAT, CommsWire.T_PANIC -> true
        CommsWire.T_GROUP_CTRL -> payload.optString(CommsWire.F_OP) != CommsWire.OP_ADD
        CommsWire.T_TIMER -> payload.has(CommsWire.F_GID)
        else -> false
    }

    /**
     * How a sender who is not a contact comes in with [payload]. As a contact
     * only when [invited] (their payload presents an invite this phone made:
     * they joined at the owner's invitation) or when they are a member of a
     * group this phone is in, writing to that group: [rosterEd25519] is the
     * key the group's roster records for their number (blank when it records
     * none; null when the group is not here or does not list them). A blank
     * key vouches for a sender on this phone's own relay only ([ownRelay]),
     * whose number the relay stands behind. Everyone else is a request: a
     * stranger's first message, a call, a payment notice, an invitation to a
     * group.
     */
    fun admit(payload: JSONObject, senderEd25519: String, rosterEd25519: String?, ownRelay: Boolean, invited: Boolean = false): Admission = when {
        invited -> Admission.CONTACT
        !groupPayload(payload) || rosterEd25519 == null -> Admission.REQUEST
        rosterEd25519.isNotBlank() -> if (rosterEd25519 == senderEd25519) Admission.CONTACT else Admission.REQUEST
        ownRelay -> Admission.CONTACT
        else -> Admission.REQUEST
    }

    /** What happens to a payload from a message request until the owner accepts it. */
    enum class RequestAction {
        /** Kept in the request's conversation, with no receipt and only the notification that names nobody. */
        KEEP,
        /** A call offer: it never rings; a missed call is written into the request instead. */
        MISSED_CALL,
        /** Held unseen until the owner accepts: an invitation to a group, which would add this phone to it. */
        HOLD,
        /**
         * Acted on as for anyone: group traffic (its roster decides), their
         * pass, a move, a timer, and word that they joined with an invite this
         * phone made, which accepts them.
         */
        PROCESS,
        /**
         * Nothing to act on: receipts and resyncs concern what this phone sent
         * them, which is nothing (and a resync would be answered), and every
         * call signal but the offer belongs to a call that never rang.
         */
        IGNORE
    }

    fun forRequest(payload: JSONObject): RequestAction = when (CommsWire.type(payload)) {
        CommsWire.T_MSG, CommsWire.T_MEDIA, CommsWire.T_MEDIA_CHUNK, CommsWire.T_VOICEMAIL, CommsWire.T_PAYMENT -> RequestAction.KEEP
        CommsWire.T_CALL -> if (CommsWire.callKind(payload) == CommsWire.CALL_OFFER) RequestAction.MISSED_CALL else RequestAction.IGNORE
        CommsWire.T_GROUP_CTRL -> if (payload.optString(CommsWire.F_OP) == CommsWire.OP_ADD) RequestAction.HOLD else RequestAction.PROCESS
        CommsWire.T_RECEIPT, CommsWire.T_RESYNC -> RequestAction.IGNORE
        else -> RequestAction.PROCESS
    }

    /** Statuses that are never receipted: a call line, this phone's own notes, a file still arriving ([CommsStore.markRead]). */
    private val NO_RECEIPT = setOf(STATUS_CALL, STATUS_SYSTEM, STATUS_RECEIVING)

    /**
     * The receipts a request's conversation is owed once the owner accepts:
     * by status, a read receipt for each message they have read and a
     * delivery receipt for each one they have not. Calls, this phone's own
     * notes and a file still arriving get none, as in any conversation; a file
     * that completes later is receipted then.
     */
    fun receiptsOnAccept(messages: List<ChatMessage>): Map<String, List<String>> =
        messages
            .filter { it.direction == Direction.IN && it.status !in NO_RECEIPT && it.kind != KIND_NOTE }
            .groupBy({ if (it.read) CommsWire.STATUS_READ else CommsWire.STATUS_DELIVERED }, { it.id })

    // ── Reports ──────────────────────────────────────────────────────────

    /** Why someone is reported; [wire] is the word the relay is given. */
    enum class ReportReason(val wire: String, val label: String) {
        HARASSMENT("harassment", "Harassment"),
        THREATS("threats", "Threats of violence"),
        SPAM("spam", "Spam or scams"),
        IMPERSONATION("impersonation", "Pretending to be someone else"),
        OTHER("other", "Something else")
    }

    /**
     * Whether something from a contact is worth naming in a report: what they
     * wrote, sent or set going, not the bookkeeping their phone does on its
     * own (receipts, passes, cover traffic, the chunks after a file's header).
     */
    fun reportable(payload: JSONObject): Boolean = when (CommsWire.type(payload)) {
        CommsWire.T_MSG, CommsWire.T_MEDIA, CommsWire.T_VOICEMAIL, CommsWire.T_PAYMENT,
        CommsWire.T_GROUP_MSG, CommsWire.T_GROUP_CTRL, CommsWire.T_THREAT, CommsWire.T_PANIC, CommsWire.T_TIMER -> true
        CommsWire.T_CALL -> CommsWire.callKind(payload) == CommsWire.CALL_OFFER
        else -> false
    }

    /** An envelope's hash as this phone keeps it: SHA-256 of its bytes, in lowercase hex. */
    private val ENVELOPE_HASH = Regex("^[0-9a-f]{64}$")

    /**
     * What a report tells the relay: the number reported and the relay it is
     * on (left out for the relay the report goes to), the hash of an envelope
     * they sent when one is known, and the reason. Nothing about the phone
     * that reports, and nothing of what they wrote, which the relay never
     * sees. The request that carries it spends a token and names nobody.
     */
    fun reportBody(number: String, relay: String?, envelopeHash: String?, reason: ReportReason): JSONObject =
        JSONObject().apply {
            put("number", number)
            if (relay != null) put("relay", relay)
            envelopeHash?.takeIf { ENVELOPE_HASH.matches(it) }?.let { put("envelope", it) }
            put("reason", reason.wire)
        }
}
