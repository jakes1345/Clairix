package app.clairix.comms

// The group outbox: what a group payload still owes each member. A group
// text, an SOS, a shared threat or a roster change is one envelope per member;
// each is a row here, one per member, until the relay takes it, it fails for
// good, or its time is up. CommsRepository tries the rows (flushOutbox and
// scheduleRetry, the same pass and back-off as one-to-one messages) and
// CommsStore keeps them; the decisions live here, in pure Kotlin, so the JVM
// tests use exactly this code.

/**
 * One row per member a group payload is still owed to.
 *
 * Before, a group payload was posted once per member and a relay that was
 * busy or out of reach at that second (no network, 429, 5xx, no delivery
 * token) was a line in the comms log: an SOS, a group text or a roster change
 * that missed a member stayed missed. Now each member's envelope is a [Row]
 * that the outbox tries again, with the same back-off and the relay's
 * Retry-After ([OutboxRetry]), until it goes or its time is up: an SOS for
 * about thirty minutes, a roster change for as long as the relay keeps an
 * envelope, a group text for a week, a shared threat until it expires on the
 * map.
 *
 * Who gets a row at all is [plan]: never this phone, never a member the owner
 * blocked (the rule that hides their group messages, so they are counted
 * nowhere), and a member this phone cannot reach gets a row that has already
 * failed, so the sender is told who and why.
 */
object GroupOutbox {

    /** What a row carries, most urgent first: a flush tries SOS rows before anything else. */
    enum class Kind(val wire: String) {
        SOS("sos"),
        /** A roster change, a whole roster, a request for one, or a leaving notice. */
        ROSTER("roster"),
        TEXT("text"),
        THREAT("threat");

        companion object {
            fun of(wire: String): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** An SOS is tried for about half an hour; whoever it has not reached by then is counted as not reached. */
    const val SOS_LIFETIME_MS = 30 * 60_000L
    /**
     * A roster change for as long as the relay keeps an envelope: a member who
     * misses one keeps sending the group's messages, and its SOS, to someone
     * who was removed.
     */
    const val ROSTER_LIFETIME_MS = 30L * 24 * 3600_000L
    /** A group text for a week. A shared threat is tried until it expires. */
    const val TEXT_LIFETIME_MS = 7L * 24 * 3600_000L

    enum class Status(val wire: String) {
        /** Waiting for the relay. */
        QUEUED("queued"),
        /**
         * Not sent while this phone's roster of the group is out of date and
         * the member is not confirmed in the newer one ([RosterSync]): they may
         * have been removed. Queued once the roster confirms them, dropped if
         * it does not list them.
         */
        HELD("held"),
        /** The relay took it. */
        SENT("sent"),
        /** The relay never took it before its time was up; the offline mesh carries a copy. */
        MESH("mesh"),
        /** Final: refused, not a contact, too large, or its time ran out. */
        FAILED("failed");

        val final: Boolean get() = this == SENT || this == MESH || this == FAILED

        companion object {
            fun of(wire: String): Status = entries.firstOrNull { it.wire == wire } ?: QUEUED
        }
    }

    data class Row(
        /** The payload's id: the message's, this group's SOS's, or a roster change's. */
        val id: String,
        /** The member's Clairix number. */
        val recipient: String,
        val gid: String,
        /** The send this row belongs to: one SOS across all its groups, one text, one roster change. */
        val batch: String,
        val kind: Kind,
        /** The member's identity key as the roster had it when the row was made; blank when it had none. */
        val recipientEd: String,
        /** The payload as JSON, without the sender's fields, which are added as it is sent. */
        val payload: String,
        val created: Long,
        /** When it is given up: [created] plus its kind's lifetime. */
        val deadline: Long,
        val status: Status = Status.QUEUED,
        /** The offline mesh carries a copy. The relay is still tried until the row is final. */
        val mesh: Boolean = false,
        val attempts: Int = 0,
        /** When it was last tried; 0 before the first try. */
        val triedAt: Long = 0L,
        /** Why it has not gone yet, or why it failed. */
        val error: String? = null,
        /** Order of insertion, so two rows made in the same millisecond keep their order. */
        val seq: Long = 0L,
    ) {
        val final: Boolean get() = status.final
    }

    /** What one try at a row came to. */
    sealed class Outcome {
        /** The relay took it. */
        object Sent : Outcome()
        /** Not now: tried again, after [retryAfterMs] when the relay said how long. [meshQueued]: the mesh took a copy. */
        data class Offline(val reason: String, val retryAfterMs: Long? = null, val meshQueued: Boolean = false) : Outcome()
        /** Never: refused for good. */
        data class Failed(val reason: String) : Outcome()
        /** Not tried: the member is not confirmed in the newer roster this phone has heard of. */
        object Hold : Outcome()
    }

    const val NOT_A_CONTACT = "they are not a contact on this phone"
    const val NOT_THE_MEMBER = "the contact with that number is not the person this group knows"
    const val NOT_CONFIRMED = "this phone could not confirm they are still in the group"
    /** Why a held row waits. */
    const val WAITING_FOR_ROSTER = "waiting for the group's current member list"

    /** [row] after one try at [now]. A row past its deadline that still did not go is settled by [expire]. */
    fun after(row: Row, outcome: Outcome, now: Long): Row {
        val tried = row.copy(attempts = row.attempts + 1, triedAt = now)
        return when (outcome) {
            Outcome.Sent -> tried.copy(status = Status.SENT, error = null)
            is Outcome.Failed -> tried.copy(status = Status.FAILED, error = outcome.reason)
            is Outcome.Offline -> expire(
                tried.copy(status = Status.QUEUED, error = outcome.reason, mesh = row.mesh || outcome.meshQueued),
                now
            )
            Outcome.Hold -> expire(row.copy(status = Status.HELD, error = WAITING_FOR_ROSTER), now)
        }
    }

    /**
     * [row] once its time is up: carried by the offline mesh when it holds a
     * copy, failed otherwise, with why and after how long. Unchanged while
     * there is time left, or once it is final.
     */
    fun expire(row: Row, now: Long): Row = when {
        row.final || now < row.deadline -> row
        row.mesh -> row.copy(status = Status.MESH)
        else -> row.copy(
            status = Status.FAILED,
            error = (if (row.status == Status.HELD) NOT_CONFIRMED else row.error ?: "the relay did not take it") +
                "; given up after " + lifetimeWords(row.deadline - row.created)
        )
    }

    /** "30 minutes", "7 days": how long a row was tried, in words. */
    fun lifetimeWords(ms: Long): String {
        val minutes = (ms + 30_000L) / 60_000L
        fun n(count: Long, unit: String) = "$count $unit" + if (count == 1L) "" else "s"
        return when {
            minutes < 120 -> n(minutes.coerceAtLeast(1L), "minute")
            minutes < 48 * 60 -> n((minutes + 30) / 60, "hour")
            else -> n((minutes + 720) / 1440, "day")
        }
    }

    /**
     * The rows a flush tries now, in order: SOS first, then roster changes,
     * texts and threats; within each, the least recently tried first, so one
     * member the relay keeps refusing does not hold up the others pass after
     * pass. A roster row never overtakes an older one of the same group to
     * the same member: an add then a remove, reordered, would leave the
     * person in. Held, final and expired rows are not tried.
     */
    fun due(rows: List<Row>, now: Long): List<Row> {
        val waiting = rows.filter { it.status == Status.QUEUED && now < it.deadline }
        val oldestRoster = HashMap<Pair<String, String>, Row>()
        for (r in waiting) {
            if (r.kind != Kind.ROSTER) continue
            val key = r.gid to r.recipient
            val cur = oldestRoster[key]
            if (cur == null || order.compare(r, cur) < 0) oldestRoster[key] = r
        }
        return waiting
            .filter { it.kind != Kind.ROSTER || oldestRoster[it.gid to it.recipient] === it }
            .sortedWith(compareBy<Row>({ it.kind.ordinal }, { it.triedAt }).then(order))
    }

    private val order: Comparator<Row> = compareBy<Row>({ it.created }, { it.seq })

    /** What a member's row stands at, as an SOS result counts it. */
    fun delivery(row: Row): MemberDelivery = when (row.status) {
        Status.SENT -> MemberDelivery.SENT
        Status.MESH -> MemberDelivery.MESH_QUEUED
        Status.FAILED -> MemberDelivery.FAILED
        Status.QUEUED, Status.HELD -> if (row.mesh) MemberDelivery.MESH_QUEUED else MemberDelivery.OFFLINE
    }

    // ── Who gets a row ──────────────────────────────────────────────────

    /** What one member of a group gets from a payload this phone sends to it. */
    sealed class Plan {
        /** A row, queued for the relay, or [held] until a newer roster confirms they are still in the group. */
        data class Queue(val held: Boolean) : Plan()
        /** A row that has already failed: nothing can reach them, and the sender is told why. */
        data class Fail(val reason: String) : Plan()
        /** No row: this phone itself, or someone the owner blocked. Counted nowhere. */
        object Skip : Plan()
    }

    /**
     * What [member] gets. [contact] is the contact on this phone with their
     * number (null when there is none, or only a blocked contact or a message
     * request); [knownAs] is whether that contact is the person the roster
     * means (a contact with the roster's key, or, when it records none, one on
     * this relay or verified); [blocked] is the blocked list; [confirmed] is
     * whether they are confirmed in the newest roster this phone has heard of
     * ([RosterSync.confirmed]).
     */
    fun plan(
        member: GroupMember,
        me: String,
        contact: Contact?,
        knownAs: Boolean,
        blocked: List<ContactGate.Blocked>,
        confirmed: Boolean
    ): Plan = when {
        member.number == me -> Plan.Skip
        blockedRecipient(member.number, member.ed25519, blocked) -> Plan.Skip
        contact == null -> Plan.Fail(NOT_A_CONTACT)
        !knownAs -> Plan.Fail(NOT_THE_MEMBER)
        else -> Plan.Queue(held = !confirmed)
    }

    /**
     * Whether the owner blocked the member numbered [number] whom the roster
     * knows by [ed25519] (blank when it records no key): by that key, or by
     * the number when the roster records no key or that very key. The rule
     * that hides a blocked member's group messages (CommsStore's
     * notFromBlockedMember): someone whose messages this phone hides is
     * someone it sends nothing to. Someone else with the same number, whom the
     * roster knows by another key, is not them.
     */
    fun blockedRecipient(number: String, ed25519: String, blocked: List<ContactGate.Blocked>): Boolean =
        blocked.any { b ->
            (b.number == number && (ed25519.isBlank() || ed25519 == b.ed25519)) ||
                (ed25519.isNotBlank() && b.ed25519.isNotEmpty() && b.ed25519 == ed25519)
        }

    /**
     * A held row once the group's roster may have changed: dropped (null)
     * when [member] (the roster's entry for the row's recipient now) is gone,
     * or is someone else, by another key; queued when [confirmed]; still held
     * otherwise. Rows that are not held are returned as they are.
     */
    fun release(row: Row, member: GroupMember?, confirmed: Boolean): Row? = when {
        row.status != Status.HELD -> row
        member == null -> null
        row.recipientEd.isNotBlank() && member.ed25519.isNotBlank() && member.ed25519 != row.recipientEd -> null
        confirmed -> row.copy(status = Status.QUEUED, error = null)
        else -> row
    }

    // ── What a group text's bubble says ─────────────────────────────────

    /** A group text's status ("queued", "sent" or "failed") and the line under the bubble, if any. */
    data class TextStatus(val status: String, val note: String?)

    /**
     * What a group text's bubble says while its rows stand as [rows] do: queued
     * while any member is still being tried (with how far it got, once some
     * have answered), sent once every member has it, sent with a note when some
     * could not be reached, failed when nobody could.
     */
    fun textStatus(rows: List<Row>): TextStatus {
        val n = rows.size
        if (n == 0) return TextStatus("sent", null)
        val sent = rows.count { it.status == Status.SENT }
        val mesh = rows.count { it.status == Status.MESH }
        val waiting = rows.count { !it.final }
        val failed = rows.count { it.status == Status.FAILED }
        val of = "of $n member" + if (n == 1) "" else "s"
        return when {
            waiting > 0 -> TextStatus(
                "queued",
                if (sent + mesh + failed == 0) null
                else "Reached $sent $of; trying $waiting more" + (if (failed > 0) "; $failed could not be reached" else "")
            )
            failed == 0 && mesh == 0 -> TextStatus("sent", null)
            failed == 0 -> TextStatus("sent", "Reached $sent $of; the offline mesh carries it to $mesh")
            sent + mesh == 0 -> TextStatus("failed", if (n == 1) "Not sent: ${rows.first().error ?: "they could not be reached"}" else "No member could be reached")
            else -> TextStatus(
                "sent",
                "Reached $sent $of" + (if (mesh > 0) "; the offline mesh carries it to $mesh" else "") + "; $failed could not be reached"
            )
        }
    }

    /**
     * Rows kept in memory only: a covert SOS's (the duress phrase writes
     * nothing on the phone), and anything sent because of one. Lost with the
     * process, which is the point.
     */
    class MemoryRows {
        private val rows = LinkedHashMap<Pair<String, String>, Row>()
        private var seq = 0L

        @Synchronized
        fun add(new: List<Row>) {
            for (r in new) {
                val key = r.id to r.recipient
                if (key !in rows) rows[key] = r.copy(seq = ++seq)
            }
        }

        @Synchronized
        fun all(): List<Row> = rows.values.toList()

        @Synchronized
        fun has(row: Row): Boolean = (row.id to row.recipient) in rows

        /** Replaces the row with [row]'s id and recipient; one that is gone stays gone. */
        @Synchronized
        fun update(row: Row) {
            val key = row.id to row.recipient
            if (key in rows) rows[key] = row
        }

        @Synchronized
        fun remove(row: Row) {
            rows.remove(row.id to row.recipient)
        }

        @Synchronized
        fun removeIf(predicate: (Row) -> Boolean) {
            rows.values.removeAll(predicate)
        }
    }
}

/**
 * When the outbox is tried again after a send the relay could not take: the
 * one decision behind CommsRepository's scheduleRetry, which one-to-one
 * messages, receipts and every group outbox row share.
 */
object OutboxRetry {

    data class Next(val waitMs: Long, val atMs: Long, val backoffMs: Long)

    /**
     * The retry after a failure at [now]. After exactly [retryAfterMs] when
     * the relay said how long (busy, not down: the pause does not grow; at
     * least a second, at most [maxMs]), else after [backoffMs], which doubles
     * for next time up to [maxMs]. Null when a retry already pending at
     * [pendingAtMs] comes sooner: it stands. One due later gives way, so the
     * relay's "again in 2 s" is not lost behind a retry minutes off.
     */
    fun next(now: Long, backoffMs: Long, retryAfterMs: Long?, pendingAtMs: Long?, maxMs: Long): Next? {
        val wait = retryAfterMs?.coerceIn(1_000L, maxMs) ?: backoffMs
        val at = now + wait
        if (pendingAtMs != null && pendingAtMs <= at) return null
        val nextBackoff = if (retryAfterMs == null) (backoffMs * 2).coerceAtMost(maxMs) else backoffMs
        return Next(wait, at, nextBackoff)
    }
}
