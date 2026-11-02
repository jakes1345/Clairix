package app.clairix.comms

// What became of an SOS, as the button, the timeline, the comms log and the
// outcome notification report it. Pure: CommsRepository fills these in from
// the SOS's rows in the group outbox ([GroupOutbox]), and the counting and the
// wording live here so they can be tested without a relay.

/** What one member's envelope stands at. */
enum class MemberDelivery {
    /** The relay took it. */
    SENT,
    /** The relay has not taken it; the offline mesh carries a copy phone to phone. The relay is still tried until the SOS's time is up. */
    MESH_QUEUED,
    /** Nothing carries it yet: no network, a busy relay, no delivery token on this phone. Queued and tried again. */
    OFFLINE,
    /** Final: not a contact on this phone, refused by the relay, too large, or not reached in the time an SOS is tried. */
    FAILED;

    /** When one person was tried in several groups, the best outcome stands for them. */
    internal val rank: Int
        get() = when (this) {
            SENT -> 3
            MESH_QUEUED -> 2
            OFFLINE -> 1
            FAILED -> 0
        }
}

/** One member's envelope: what came of it, and why when it did not go. */
data class MemberResult(val number: String, val delivery: MemberDelivery, val reason: String? = null)

/** One group's SOS: every other member it went to and what their envelope came to. */
data class PanicGroupResult(
    val groupId: String,
    val groupName: String,
    val members: List<MemberResult>,
    /** Set when the group could not be tried at all (gone from this phone, say); [members] is then empty. */
    val error: String? = null,
    /** Members the owner blocked: sent nothing, and not counted among the people the SOS was for. */
    val skipped: Int = 0,
    /** Members the group's roster turned out no longer to list (an admin removed them): their envelope was dropped. */
    val removed: Int = 0
) {
    val reached: Int get() = members.count { it.delivery == MemberDelivery.SENT }
    val meshQueued: Int get() = members.count { it.delivery == MemberDelivery.MESH_QUEUED }

    /**
     * This group's line in the comms log: how many members the SOS reached,
     * and of the rest how many the offline mesh carries, how many are still
     * waiting for the relay ([retrying]) and how many could not be reached.
     * It says "not sent" when nothing went, never "sent".
     */
    fun logLine(position: Boolean, retrying: Boolean): String {
        error?.let { return "SOS to \"$groupName\" could not be tried: ${it.replaceFirstChar { c -> c.lowercase() }}" }
        val n = members.size
        val r = reached
        val waiting = members.count { it.delivery == MemberDelivery.OFFLINE }
        val failed = members.count { it.delivery == MemberDelivery.FAILED }
        val of = "$r of $n member" + if (n == 1) "" else "s"
        val head = when {
            n == 0 -> "SOS to \"$groupName\": nobody to send it to"
            r > 0 -> "SOS to \"$groupName\": reached $of"
            retrying -> "SOS to \"$groupName\": not sent yet, $of reached"
            else -> "SOS to \"$groupName\": not sent, $of reached"
        }
        val tails = ArrayList<String>(5)
        if (meshQueued > 0) tails += "$meshQueued in the offline mesh"
        if (waiting > 0) tails += "$waiting waiting for the relay"
        if (failed > 0) tails += "$failed could not be reached"
        if (skipped > 0) tails += "$skipped you blocked left out"
        if (removed > 0) tails += "$removed no longer in the group"
        return head + tails.joinToString("") { "; $it" } + if (position) "" else " (no position)"
    }
}

/** Why an SOS had nobody to go to. */
enum class PanicBlocked {
    NOT_REGISTERED,
    NO_GROUPS,
    NO_OTHER_MEMBERS,
    /** The groups have other members, but none this phone sends to: everyone else is blocked, or no longer in the group. */
    NO_RECIPIENTS
}

/**
 * One SOS as it stands: after the first pass over every member of every
 * group, then after each pass of the group outbox until it is final.
 *
 * Counts are of people, not envelopes: a member of two groups gets one
 * envelope per group and is one person, reached if either went. Someone the
 * offline mesh carries is counted apart from someone nobody could reach.
 */
data class PanicResult(
    /** Which raise of the SOS flag this is, counted from app start, so a caller waiting for its result knows its own. */
    val attempt: Long,
    val ts: Long,
    /** The position that went with it, when there was one. */
    val lat: Double? = null,
    val lon: Double? = null,
    val blocked: PanicBlocked? = null,
    val groups: List<PanicGroupResult> = emptyList(),
    /** The broadcast itself failed before any group was tried. */
    val error: String? = null,
    /** 1 for the first pass over every member; one more for each pass of the outbox after it. */
    val pass: Int = 1,
    /**
     * Some members' envelopes are still queued: the group outbox tries them
     * again, for about thirty minutes from the SOS, and the next pass
     * replaces this result.
     */
    val retrying: Boolean = false
) {
    val hasPosition: Boolean get() = lat != null && lon != null

    /** The best outcome each person had across the groups they share with this phone. */
    private val best: Map<String, MemberResult> = HashMap<String, MemberResult>().also { m ->
        for (g in groups) for (r in g.members) {
            val cur = m[r.number]
            if (cur == null || r.delivery.rank > cur.delivery.rank) m[r.number] = r
        }
    }

    /** Everyone the SOS was for: the other members of every group, each counted once, the blocked ones aside. */
    val people: Int get() = best.size
    val reached: Int get() = count(MemberDelivery.SENT)
    val meshQueued: Int get() = count(MemberDelivery.MESH_QUEUED)
    /** People nothing carries the SOS to yet: queued, and tried again while [retrying]. */
    val pending: Int get() = count(MemberDelivery.OFFLINE)
    val failed: Int get() = count(MemberDelivery.FAILED)
    val groupsReached: Int get() = groups.count { it.reached > 0 }

    private fun count(d: MemberDelivery) = best.values.count { it.delivery == d }

    /**
     * One sentence for the owner: "SOS reached 3 of 4 people". [subject] names
     * what went, for the alert that carries a position found after the first
     * ("Your location reached 3 of 4 people").
     */
    fun headline(subject: String = "SOS"): String {
        blocked?.let {
            return when (it) {
                PanicBlocked.NOT_REGISTERED -> "$subject not sent: this phone is not set up for Comms"
                PanicBlocked.NO_GROUPS -> "$subject not sent: this phone is in no group"
                PanicBlocked.NO_OTHER_MEMBERS -> "$subject not sent: your groups have no other members"
                PanicBlocked.NO_RECIPIENTS -> "$subject not sent: nobody else in your groups can receive it"
            }
        }
        error?.let { return "$subject could not be sent: ${lower(it)}" }
        val n = people
        if (n == 0) return "$subject could not be sent: ${lower(firstReason() ?: "no group could be tried")}"
        val r = reached
        val m = meshQueued
        val p = pending
        val f = failed
        val head = when {
            r == n -> return if (n == 1) "$subject reached 1 person" else "$subject reached all $n people"
            r > 0 -> "$subject reached $r of $n people"
            m > 0 -> "$subject could not reach the relay; the offline mesh carries it for " +
                when {
                    m < n -> "$m of $n people"
                    n == 1 -> "the 1 person in your groups"
                    else -> "all $n people"
                }
            retrying -> return "$subject not sent yet — retrying" + (firstReason()?.let { ": ${lower(it)}" } ?: "")
            else -> return "$subject could not be sent: ${lower(firstReason() ?: "nobody could be reached")}"
        }
        val tails = ArrayList<String>(3)
        if (r > 0 && m > 0) tails += "$m in the offline mesh"
        if (retrying) {
            if (p > 0) tails += "trying $p more again"
            if (f > 0) tails += "$f could not be reached"
        } else if (p + f > 0) {
            tails += "${p + f} could not be reached"
        }
        return if (tails.isEmpty()) head else head + "; " + tails.joinToString("; ")
    }

    /** Group by group, for the timeline and the comms log: "Family: 2 of 3 · Shelter team: 1 of 1 · with your position". */
    fun detail(): String {
        val parts = ArrayList<String>(groups.size + 1)
        for (g in groups) {
            if (g.error != null) {
                parts += "${g.groupName}: not tried (${lower(g.error)})"
                continue
            }
            val notes = listOfNotNull(
                if (g.meshQueued > 0) "${g.meshQueued} in the offline mesh" else null,
                if (g.skipped > 0) "${g.skipped} you blocked left out" else null,
                if (g.removed > 0) "${g.removed} no longer in the group" else null
            )
            parts += "${g.groupName}: ${g.reached} of ${g.members.size}" + if (notes.isEmpty()) "" else notes.joinToString(", ", " (", ")")
        }
        parts += if (hasPosition) "with your position" else "no position attached"
        return parts.joinToString(" · ")
    }

    /** Why the first person nobody reached could not be: the relay's words, else a group's. */
    private fun firstReason(): String? =
        best.values.firstOrNull { it.delivery == MemberDelivery.OFFLINE }?.reason
            ?: best.values.firstOrNull { it.delivery == MemberDelivery.FAILED }?.reason
            ?: groups.firstNotNullOfOrNull { it.error }

    private fun lower(s: String): String = s.replaceFirstChar { it.lowercase() }

    companion object {
        /**
         * An SOS as its outbox rows stand: one group result per entry of
         * [groups] (id to name), each member counted as [GroupOutbox.delivery]
         * says; a group in [errors] could not be tried at all. [skipped] and
         * [removed] count, per group, the members left out because the owner
         * blocked them or the roster no longer lists them. Retrying while any
         * row is not final.
         */
        fun fromRows(
            attempt: Long,
            ts: Long,
            lat: Double?,
            lon: Double?,
            groups: List<Pair<String, String>>,
            rows: List<GroupOutbox.Row>,
            errors: Map<String, String> = emptyMap(),
            skipped: Map<String, Int> = emptyMap(),
            removed: Map<String, Int> = emptyMap(),
            pass: Int = 1
        ): PanicResult {
            val byGroup = rows.groupBy { it.gid }
            val results = groups.map { (gid, name) ->
                errors[gid]?.let { PanicGroupResult(gid, name, emptyList(), error = it) }
                    ?: PanicGroupResult(
                        gid, name,
                        byGroup[gid].orEmpty().map { MemberResult(it.recipient, GroupOutbox.delivery(it), it.error) },
                        skipped = skipped[gid] ?: 0,
                        removed = removed[gid] ?: 0
                    )
            }
            val result = PanicResult(attempt, ts, lat, lon, groups = results, pass = pass, retrying = rows.any { !it.final })
            if (result.people > 0 || errors.isNotEmpty()) return result
            val leftOut = results.any { it.skipped + it.removed > 0 }
            return result.copy(blocked = if (leftOut) PanicBlocked.NO_RECIPIENTS else PanicBlocked.NO_OTHER_MEMBERS, retrying = false)
        }
    }
}

/**
 * What holding the SOS button leads to, decided before the confirm dialog so
 * nobody confirms an SOS that has no one to go to.
 */
enum class SosGate {
    /** Somebody would receive it: confirm, then send. */
    SEND,
    /** This phone has no Clairix number: nothing can be sent until Comms is set up. */
    NOT_REGISTERED,
    /** Registered, but in no group with anyone else in it it sends to. */
    NO_RECIPIENTS;

    companion object {
        /** [otherMembers]: members of this phone's groups other than itself and anyone it blocked, over all its groups. */
        fun decide(registered: Boolean, groups: Int, otherMembers: Int): SosGate = when {
            !registered -> NOT_REGISTERED
            groups <= 0 || otherMembers <= 0 -> NO_RECIPIENTS
            else -> SEND
        }
    }
}
