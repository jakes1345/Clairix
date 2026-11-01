package app.clairix.comms

/**
 * Roster versions: how a member who missed a roster change finds out, and
 * stops sending the group's messages and its SOS to someone who was removed.
 *
 * Roster changes used to be told once, as deltas (someone joined, someone was
 * removed), and a member whose copy was lost kept the old roster for good: a
 * removed member, often the very person a support circle removed, went on
 * getting that phone's SOS positions. Now an admin numbers every change it
 * makes ([next]); the change goes through the group outbox, as the delta for
 * apps from before versions and as the whole numbered roster
 * ([CommsWire.OP_ROSTER]); and every group payload carries the sender's
 * number ("rv", [CommsWire.F_ROSTER_VERSION]).
 *
 * A phone that hears a newer number than its own knows its roster is out of
 * date ([stale]). Until an admin's roster reaches it (it asks the group's
 * admins for one, [CommsWire.OP_ROSTER_REQUEST]), it sends the group's
 * payloads only to members confirmed in the newer roster ([confirmed]: they
 * sent something numbered with it, or a change or roster that new named
 * them), and holds the rest; once the roster comes, the held rows go to
 * whoever it still lists and are dropped for anyone it does not.
 *
 * Deltas are applied as they always were; the numbers only decide when a
 * whole roster replaces the one held and when to ask for one. A group whose
 * admins run apps from before versions never has a number, and is never
 * stale.
 */
object RosterSync {

    /** Whether the roster held at [version] is out of date: something from the group named [seen], a newer one. */
    fun stale(version: Long, seen: Long): Boolean = seen > version

    /**
     * Whether a member known to hold roster version [memberRv] may be sent
     * the group's payloads: always while the roster is not out of date;
     * otherwise only when they hold the newest version heard of, since a
     * member removed in it would not.
     */
    fun confirmed(memberRv: Long, version: Long, seen: Long): Boolean = !stale(version, seen) || memberRv >= seen

    /**
     * [confirmed], for a phone that may have no admin to ask ([askable] false: this
     * phone is the group's only admin, or the group has none left). Only an admin
     * numbers a roster, so with nobody else who could have made a newer one, a newer
     * number is a member's claim that no roster can ever settle: holding rows for it
     * would hold an SOS for everyone except the member who made the claim, for the
     * whole of its thirty minutes. Everyone the roster lists is then confirmed.
     */
    fun confirmed(memberRv: Long, version: Long, seen: Long, askable: Boolean): Boolean =
        !askable || confirmed(memberRv, version, seen)

    /** Whether [members] has an admin other than [me] to ask for a newer roster. */
    fun askable(members: List<GroupMember>, me: String?): Boolean =
        members.any { it.role == OrgRole.ADMIN && it.number != me }

    /** The number an admin gives the change it makes now: past everything it has heard of, so it is newer for everyone. */
    fun next(version: Long, seen: Long): Long = maxOf(version, seen) + 1

    /** After a payload from a member that names [rv]: the newest version heard of moves up. Returns (version, seen). */
    fun heard(version: Long, seen: Long, rv: Long): Pair<Long, Long> = version to maxOf(seen, rv)

    /**
     * After an admin's change numbered [rv], applied as deltas always were:
     * the next number in line moves the roster's own on; a gap (a change in
     * between was missed) leaves it where it was, and the newer number marks
     * the roster out of date. Returns (version, seen).
     */
    fun afterDelta(version: Long, seen: Long, rv: Long): Pair<Long, Long> =
        if (rv == version + 1) rv to maxOf(seen, rv) else version to maxOf(seen, rv)

    /** Whether an admin's whole roster numbered [rv] replaces the one held at [version]. */
    fun replaces(version: Long, rv: Long): Boolean = rv > version

    /**
     * After an admin's whole roster numbered [rv]. One at least as new as the
     * one held is what the group is: the roster is up to date with it, and a
     * newer number someone else named is forgotten (a member claiming a
     * number no admin has must not keep this phone holding SOS rows for
     * good; a newer change by another admin names its number again when it
     * arrives). An older one changes nothing. Returns (version, seen).
     */
    fun afterRoster(version: Long, seen: Long, rv: Long): Pair<Long, Long> = if (rv >= version) rv to rv else version to seen
}
