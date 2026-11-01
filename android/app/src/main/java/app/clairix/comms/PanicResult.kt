package app.clairix.comms

// What became of an SOS, as the button, the timeline and the comms log report
// it. Pure: CommsRepository fills these in from what each member's envelope
// came to, and the counting and the wording live here so they can be tested
// without a relay.

/** What one member's envelope came to on one pass of a group fan-out. */
enum class MemberDelivery {
    /** The relay took it. */
    SENT,
    /** The relay could not be reached and the offline mesh carries it phone to phone instead. */
    MESH_QUEUED,
    /** Nothing carries it yet: no network, a busy relay, no delivery token on this phone. Tried again. */
    OFFLINE,
    /** Final: not a contact on this phone, refused by the relay, too large. */
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

/** One group's SOS: every other member of it and what their envelope came to. */
data class PanicGroupResult(
    val groupId: String,
    val groupName: String,
    val members: List<MemberResult>,
    /** Set when the group could not be tried at all (gone from this phone, say); [members] is then empty. */
    val error: String? = null
) {
    val reached: Int get() = members.count { it.delivery == MemberDelivery.SENT }
    val meshQueued: Int get() = members.count { it.delivery == MemberDelivery.MESH_QUEUED }
}

/** Why an SOS had nobody to go to. */
enum class PanicBlocked { NOT_REGISTERED, NO_GROUPS, NO_OTHER_MEMBERS }

/**
 * One SOS as it stands: after the first pass over every member of every
 * group, then after each retry pass until it is final.
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
    /** 1 for the first pass over every member; one more for each retry pass. */
    val pass: Int = 1,
    /** Members still [MemberDelivery.OFFLINE] are tried again; the next pass replaces this result. */
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

    /** Everyone the SOS was for: the other members of every group, each counted once. */
    val people: Int get() = best.size
    val reached: Int get() = count(MemberDelivery.SENT)
    val meshQueued: Int get() = count(MemberDelivery.MESH_QUEUED)
    /** People nothing carries the SOS to yet; tried again while [retrying]. */
    val pending: Int get() = count(MemberDelivery.OFFLINE)
    val failed: Int get() = count(MemberDelivery.FAILED)
    val groupsReached: Int get() = groups.count { it.reached > 0 }

    private fun count(d: MemberDelivery) = best.values.count { it.delivery == d }

    /**
     * Numbers in [groupId] whose envelope there is still waiting on the relay
     * and whom no other group reached: the next pass's members.
     */
    fun pendingIn(groupId: String): Set<String> {
        val g = groups.firstOrNull { it.groupId == groupId } ?: return emptySet()
        return g.members
            .filter { it.delivery == MemberDelivery.OFFLINE && best[it.number]?.delivery != MemberDelivery.SENT }
            .map { it.number }
            .toSet()
    }

    /** Whether any envelope is worth another pass. */
    val anyPending: Boolean get() = groups.any { pendingIn(it.groupId).isNotEmpty() }

    /**
     * This result after another pass over some members: their entries are
     * replaced by what the pass found, everyone else's stand.
     */
    fun merged(passResults: List<PanicGroupResult>, pass: Int, retrying: Boolean): PanicResult {
        val byGroup = passResults.associateBy { it.groupId }
        val updated = groups.map { g ->
            val p = byGroup[g.groupId] ?: return@map g
            val fresh = p.members.associateBy { it.number }
            g.copy(members = g.members.map { fresh[it.number] ?: it })
        }
        return copy(groups = updated, pass = pass, retrying = retrying)
    }

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
            parts += if (g.error != null) "${g.groupName}: not tried (${lower(g.error)})"
            else "${g.groupName}: ${g.reached} of ${g.members.size}" + (if (g.meshQueued > 0) " (${g.meshQueued} in the offline mesh)" else "")
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
         * Pauses before each pass over the members the relay could not take:
         * about eight minutes in all, in this process's memory, a fresh network
         * ending a pause early. After the last the result is final.
         */
        val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 240_000L)
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
    /** Registered, but in no group with anyone else in it. */
    NO_RECIPIENTS;

    companion object {
        /** [otherMembers]: members of this phone's groups other than itself, over all its groups. */
        fun decide(registered: Boolean, groups: Int, otherMembers: Int): SosGate = when {
            !registered -> NOT_REGISTERED
            groups <= 0 || otherMembers <= 0 -> NO_RECIPIENTS
            else -> SEND
        }
    }
}
