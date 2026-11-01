package app.clairix.comms

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's half of a recovery file: what Comms keeps besides the identity
 * itself, which the crypto library adds (`Identity.recoveryFile`) and
 * encrypts together with this under the recovery code.
 *
 * In it: the relay and the Clairix number registered there, the name shown
 * to contacts and whether the number is listed, every contact row (blocked contacts and message requests too)
 * with the keys pinned for them, whether they were verified and whether their
 * keys changed unreviewed, the pass each contact gave, every group with its
 * roster and roster numbers, the blocked list, and each conversation's
 * disappearing-messages timer.
 *
 * Not in it, on purpose: messages, media, Olm sessions, one-time keys,
 * this phone's own pass key (a restored phone makes a new one and gives it
 * to every contact, since the old phone may have replaced it after saving),
 * contacts' sealing prekeys (fetched again), delivery tokens and claim
 * tickets (the relay's, spent or expiring), the outbox, receipts, payloads
 * held for review, and the envelope ids already seen. A recovery file brings
 * back who this phone is and who it knows, not what was said.
 */
internal data class RecoveryRecord(
    val savedAt: Long,
    val relayUrl: String,
    val number: String,
    val name: String,
    val listed: Boolean,
    val contacts: List<Contact>,
    /** Contact number → the pass key they gave (base64) and when they made it. */
    val contactPasses: Map<String, Pair<String, Long>>,
    val groups: List<Group>,
    val members: List<GroupMember>,
    val blocked: List<ContactGate.Blocked>,
    /** Conversation (a number or a group id) → timer in seconds and when it was set. */
    val timers: Map<String, Pair<Int, Long>>
) {

    fun encode(): ByteArray = JSONObject()
        .put(F_VERSION, VERSION)
        .put(F_SAVED_AT, savedAt)
        .put(F_RELAY, relayUrl)
        .put(F_NUMBER, number)
        .put(F_NAME, name)
        .put(F_LISTED, listed)
        .put(F_CONTACTS, JSONArray(contacts.map { contactJson(it) }))
        .put(F_GROUPS, JSONArray(groups.map { g -> groupJson(g, members.filter { it.gid == g.id }) }))
        .put(F_BLOCKED, JSONArray(blocked.map { blockedJson(it) }))
        .put(F_TIMERS, JSONArray(timers.map { (peer, t) -> JSONObject().put(F_PEER, peer).put(F_TTL, t.first).put(F_TS, t.second) }))
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun contactJson(c: Contact): JSONObject = JSONObject()
        .put(F_NUMBER, c.number)
        .put(F_NAME, c.name)
        .put(F_ED25519, c.ed25519)
        .put(F_CURVE25519, c.curve25519)
        .put(F_SEALING, c.sealing)
        .put(F_SIGNATURE, c.signature)
        .put(F_VERIFIED, c.verified)
        .put(F_KEY_CHANGED, c.keyChanged)
        .put(F_ADDED, c.addedTs)
        .put(F_BLOCKED_FLAG, c.blocked)
        .put(F_REQUEST, c.request)
        .apply { c.relay?.let { put(F_RELAY, it) } }
        .apply { contactPasses[c.number]?.let { (key, created) -> put(F_PASS, JSONObject().put(F_KEY, key).put(F_CREATED, created)) } }

    private fun groupJson(g: Group, roster: List<GroupMember>): JSONObject = JSONObject()
        .put(F_ID, g.id)
        .put(F_NAME, g.name)
        .put(F_CREATED_BY, g.createdBy)
        .put(F_CREATED, g.createdTs)
        .put(F_ROLE, g.myRole.name)
        .put(F_ROSTER_VERSION, g.rosterVersion)
        .put(F_ROSTER_SEEN, g.rosterSeen)
        .put(
            F_MEMBERS,
            JSONArray(
                roster.map { m ->
                    JSONObject().put(F_NUMBER, m.number).put(F_NAME, m.name).put(F_ROLE, m.role.name).put(F_ED25519, m.ed25519)
                        .put(F_ADDED_BY, m.addedBy).put(F_ADDED, m.addedTs).put(F_RV, m.rv)
                }
            )
        )

    private fun blockedJson(b: ContactGate.Blocked): JSONObject = JSONObject()
        .put(F_ED25519, b.ed25519)
        .put(F_CURVE25519, b.curve25519)
        .put(F_NUMBER, b.number)
        .put(F_NAME, b.name)
        .put(F_TS, b.ts)
        .apply { b.relay?.let { put(F_RELAY, it) } }

    companion object {
        /** Version of this record; a file from a newer app with a higher one is refused rather than half read. */
        const val VERSION = 1

        private const val F_VERSION = "v"
        private const val F_SAVED_AT = "savedAt"
        private const val F_RELAY = "relay"
        private const val F_NUMBER = "number"
        private const val F_NAME = "name"
        private const val F_LISTED = "listed"
        private const val F_PASS = "pass"
        private const val F_KEY = "key"
        private const val F_CREATED = "created"
        private const val F_CONTACTS = "contacts"
        private const val F_GROUPS = "groups"
        private const val F_BLOCKED = "blocked"
        private const val F_TIMERS = "timers"
        private const val F_PEER = "peer"
        private const val F_TTL = "ttl"
        private const val F_TS = "ts"
        private const val F_ED25519 = "ed25519"
        private const val F_CURVE25519 = "curve25519"
        private const val F_SEALING = "sealing"
        private const val F_SIGNATURE = "signature"
        private const val F_VERIFIED = "verified"
        private const val F_KEY_CHANGED = "keyChanged"
        private const val F_ADDED = "added"
        private const val F_BLOCKED_FLAG = "isBlocked"
        private const val F_REQUEST = "request"
        private const val F_ID = "id"
        private const val F_CREATED_BY = "createdBy"
        private const val F_ROLE = "role"
        private const val F_ROSTER_VERSION = "rosterVersion"
        private const val F_ROSTER_SEEN = "rosterSeen"
        private const val F_MEMBERS = "members"
        private const val F_ADDED_BY = "addedBy"
        private const val F_RV = "rv"

        /**
         * Reads a record. Throws [IllegalArgumentException] when it is from a
         * newer app or is missing what a restore needs; never returns half of one.
         */
        fun decode(bytes: ByteArray): RecoveryRecord {
            val o = requireNotNull(runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()) { "not a recovery record" }
            val version = o.optInt(F_VERSION, 0)
            require(version >= 1) { "not a recovery record" }
            require(version <= VERSION) { "made by a newer Clairix" }
            val relay = requireNotNull(o.optString(F_RELAY).takeIf { it.startsWith("https://") }) { "no relay" }
            val number = requireNotNull(parseClairixNumber(o.optString(F_NUMBER))) { "no Clairix number" }
            val contactRows = objects(o, F_CONTACTS)
            val contacts = contactRows.map { readContact(it) }
            val passes = HashMap<String, Pair<String, Long>>()
            contactRows.zip(contacts).forEach { (row, c) -> row.optJSONObject(F_PASS)?.let(::readPass)?.let { passes[c.number] = it } }
            val groupRows = objects(o, F_GROUPS)
            val groups = groupRows.map { readGroup(it) }
            val members = groupRows.zip(groups).flatMap { (row, g) -> objects(row, F_MEMBERS).map { readMember(g.id, it) } }
            val timers = objects(o, F_TIMERS)
                .filter { it.optString(F_PEER).isNotBlank() }
                .associate { it.optString(F_PEER) to (it.optInt(F_TTL, 0) to it.optLong(F_TS, 0L)) }
            return RecoveryRecord(
                savedAt = o.optLong(F_SAVED_AT, 0L),
                relayUrl = relay,
                number = number,
                name = o.optString(F_NAME).take(MAX_NAME),
                listed = o.optBoolean(F_LISTED, true),
                contacts = contacts,
                contactPasses = passes,
                groups = groups,
                members = members,
                blocked = objects(o, F_BLOCKED).map { readBlocked(it) },
                timers = timers
            )
        }

        /** The objects in [o]'s array [field]; none when it has no such array. */
        private fun objects(o: JSONObject, field: String): List<JSONObject> {
            val a = o.optJSONArray(field) ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }
        }

        private const val MAX_NAME = 40
        private const val PASS_KEY_BYTES = 32

        private fun required(o: JSONObject, field: String): String =
            requireNotNull(o.optString(field).takeIf { it.isNotBlank() }) { "missing $field" }

        private fun readContact(c: JSONObject) = Contact(
            number = requireNotNull(parseClairixNumber(c.optString(F_NUMBER))) { "a contact without a Clairix number" },
            name = c.optString(F_NAME),
            ed25519 = required(c, F_ED25519),
            curve25519 = required(c, F_CURVE25519),
            sealing = required(c, F_SEALING),
            signature = required(c, F_SIGNATURE),
            verified = c.optBoolean(F_VERIFIED, false),
            addedTs = c.optLong(F_ADDED, 0L),
            keyChanged = c.optBoolean(F_KEY_CHANGED, false),
            relay = c.optString(F_RELAY).takeIf { it.isNotBlank() },
            blocked = c.optBoolean(F_BLOCKED_FLAG, false),
            request = c.optBoolean(F_REQUEST, false)
        )

        private fun readPass(p: JSONObject): Pair<String, Long>? {
            val key = p.optString(F_KEY).takeIf { it.isNotBlank() } ?: return null
            val bytes = runCatching { java.util.Base64.getDecoder().decode(key) }.getOrNull() ?: return null
            return if (bytes.size == PASS_KEY_BYTES) key to p.optLong(F_CREATED, 0L) else null
        }

        private fun readGroup(g: JSONObject) = Group(
            id = required(g, F_ID),
            name = g.optString(F_NAME),
            createdBy = g.optString(F_CREATED_BY),
            createdTs = g.optLong(F_CREATED, 0L),
            myRole = role(g.optString(F_ROLE)),
            rosterVersion = g.optLong(F_ROSTER_VERSION, 0L),
            rosterSeen = g.optLong(F_ROSTER_SEEN, 0L)
        )

        private fun readMember(gid: String, m: JSONObject) = GroupMember(
            gid = gid,
            number = required(m, F_NUMBER),
            name = m.optString(F_NAME),
            role = role(m.optString(F_ROLE)),
            ed25519 = m.optString(F_ED25519),
            addedBy = m.optString(F_ADDED_BY),
            addedTs = m.optLong(F_ADDED, 0L),
            rv = m.optLong(F_RV, 0L)
        )

        private fun readBlocked(b: JSONObject) = ContactGate.Blocked(
            id = 0L,
            ed25519 = b.optString(F_ED25519),
            curve25519 = b.optString(F_CURVE25519),
            number = b.optString(F_NUMBER),
            relay = b.optString(F_RELAY).takeIf { it.isNotBlank() },
            name = b.optString(F_NAME),
            ts = b.optLong(F_TS, 0L)
        )

        private fun role(name: String): OrgRole = OrgRole.entries.firstOrNull { it.name == name } ?: OrgRole.MEMBER
    }
}

/** What the relay said when a restored phone asked for its old mailbox. */
internal sealed interface RelayProbe {
    /** The mailbox is there and took this identity's signature: the number is still this phone's. */
    data object Found : RelayProbe
    /** The relay has no mailbox at that number for these keys (deleted, wiped, or the number went to someone else). */
    data object Gone : RelayProbe
    /** The relay could not be asked: no network, or it did not answer. */
    data class Unreachable(val reason: String) : RelayProbe
    /**
     * The mailbox is there and took this identity's signature, but the relay's
     * operator suspended the number ([reason], the relay's own words, with how
     * to appeal). The keys are still that account's, so they cannot be
     * registered again on this relay.
     */
    data class Suspended(val reason: String) : RelayProbe

    companion object {
        /**
         * The probe's outcome from the relay's answer: [code] is the HTTP
         * status of a refusal, or 0 when no answer came at all. 401 is what
         * the relay says when no mailbox there takes these keys at that
         * number; 404 is a relay with no such route or mailbox. 403, to a
         * request signed with the identity key, is a suspended account
         * (comms-worker src/index.ts, after authenticate), not a missing one.
         */
        fun fromRefusal(code: Int, reason: String): RelayProbe = when (code) {
            HTTP_UNAUTHORIZED, HTTP_NOT_FOUND -> Gone
            HTTP_FORBIDDEN -> Suspended(reason)
            else -> Unreachable(reason)
        }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
    }
}

/**
 * How a recovery file is brought back, decided before anything on the phone
 * changes. The relay is asked first; only once it has taken this identity
 * again (or given it a new number) is anything written here.
 */
internal sealed interface RestorePlan {
    /**
     * The old mailbox is still there: the same number on the same relay.
     * The relay is given new one-time keys that replace the old phone's
     * (whose secrets went with it), a new fallback key and sealing prekey,
     * and a new transport key, which shuts the old phone's out of the mailbox.
     * Contacts notice nothing.
     */
    data class Resume(val relayUrl: String, val number: String) : RestorePlan

    /**
     * The mailbox is gone: the same keys are registered again at [relayUrl]
     * (with an invite, the enrollment secret, or none where registration is
     * open), the relay gives a new number, and every contact is sent the move
     * notice, as after moving relays, so their phones follow.
     */
    data class Reregister(val relayUrl: String, val oldNumber: String) : RestorePlan

    /** Nothing can be decided: the relay did not answer. Nothing was changed. */
    data class Retry(val reason: String) : RestorePlan

    /**
     * The number is suspended on its relay ([reason], as the relay put it).
     * Nothing was changed: the same keys cannot register there again while
     * that account holds them; an invite to another relay can take them.
     */
    data class Suspended(val relayUrl: String, val reason: String) : RestorePlan

    companion object {
        /**
         * The plan for [record], given what its relay said ([probe]) and, when
         * the person brought one, the relay of an invite ([inviteRelay]): with
         * the old mailbox gone, an invite to another relay is where the keys
         * are registered again.
         */
        fun of(record: RecoveryRecord, probe: RelayProbe, inviteRelay: String? = null): RestorePlan = when (probe) {
            RelayProbe.Found -> Resume(record.relayUrl, record.number)
            RelayProbe.Gone -> Reregister(inviteRelay ?: record.relayUrl, record.number)
            is RelayProbe.Unreachable -> elsewhere(record, inviteRelay)?.let { Reregister(it, record.number) }
                ?: Retry(record.relayUrl.removePrefix("https://") + " did not answer: " + probe.reason)
            is RelayProbe.Suspended -> elsewhere(record, inviteRelay)?.let { Reregister(it, record.number) }
                ?: Suspended(record.relayUrl, probe.reason)
        }

        /** [inviteRelay] when it is a relay other than [record]'s own; null otherwise. */
        private fun elsewhere(record: RecoveryRecord, inviteRelay: String?): String? =
            inviteRelay?.takeIf { RelayClient.originOf(it) != RelayClient.originOf(record.relayUrl) }
    }
}

/** What came of restoring from a recovery file, for the setup screen to say. */
sealed interface RestoreOutcome {
    /** Restored as [number]; [sameNumber] false when the relay gave a new one and contacts are being told. */
    data class Restored(val number: String, val sameNumber: Boolean, val contacts: Int, val groups: Int) : RestoreOutcome

    /**
     * The old mailbox is gone and [relayUrl] did not take the keys back
     * without an invite or its enrollment secret ([reason], as the relay put
     * it). Nothing on the phone changed; ask for one and try again.
     */
    data class NeedsAdmission(val relayUrl: String, val reason: String) : RestoreOutcome

    /**
     * The file's relay did not answer ([reason]). Nothing on the phone
     * changed: try again later, or bring an invite to another relay, where
     * the same keys are registered under a new number.
     */
    data class Unreachable(val relayUrl: String, val reason: String) : RestoreOutcome

    /**
     * The number is suspended on [relayUrl] by its operator ([reason], the
     * relay's words, with how to appeal). Nothing on the phone changed: appeal
     * to the operator, or bring an invite to another relay, where the same
     * keys are registered under a new number.
     */
    data class Suspended(val relayUrl: String, val reason: String) : RestoreOutcome

    /** It did not work ([reason]); nothing on the phone changed. */
    data class Failed(val reason: String) : RestoreOutcome
}
