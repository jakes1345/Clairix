package app.clairix.detect

import app.clairix.Detection
import app.clairix.TrackerType

/**
 * When the scanner asks a tracker who it is over DULT ([DultInterrogator]).
 *
 * A tracker offers the non-owner service only once it has been away from its owner
 * for a while, and a tag is usually planted with its owner's phone right beside it.
 * So the first moment a tag qualifies, around for the persistence threshold or
 * confirmed following, is often one at which it can only refuse; a single attempt
 * per device spent that window and left the maker, the serial and the sound out of
 * reach for good. A device is therefore asked:
 *
 * - when it first qualifies ([qualifies]);
 * - again as soon as it is seen to separate from its owner (a [Detection.separatedAt]
 *   no ask has covered yet), whatever the count so far;
 * - again every [retryMs] while it still qualifies and has not answered, up to
 *   [maxAutomatic] automatic asks in all;
 * - and whenever the owner taps "Ask it who it is" in the locator ([claimManual]).
 *
 * An exchange is recorded when it has ended, not when it starts, and while one is in
 * flight no other is started for the same device. A device that has answered is not
 * asked again on its own. Thread-safe: packets arrive on the packet thread and
 * exchanges end on an IO thread.
 */
class DultSchedule(
    private val retryMs: Long = RETRY_MS,
    private val maxAutomatic: Int = MAX_AUTOMATIC
) {

    enum class Reason { FIRST, SEPARATED, RETRY, MANUAL }

    /** An exchange the caller may start now: why, and which exchange with the device it is, counting from 1. */
    data class Claim(val reason: Reason, val attempt: Int)

    private class State {
        var inFlight = false
        /** When the last exchange to end began; 0 before any has ended. */
        var lastAskedAt = 0L
        var automatic = 0
        var total = 0
        var answered = false
        /** The separation an ended exchange has already covered, so one flip brings one extra ask. */
        var separationCovered = 0L
        /** The separation the exchange in flight covers, recorded when it ends. */
        var pendingSeparation = 0L
    }

    private val states = HashMap<String, State>()

    /**
     * Whether to ask [key] on the scanner's own initiative now, and why; null when not.
     * [qualifies] is [qualifies] of the device; [separatedAt] its separation time, or 0.
     * A non-null answer claims the device: the caller reports the end of the exchange
     * through [finished], or [abandoned] when it never got started.
     */
    @Synchronized
    fun claim(key: String, qualifies: Boolean, separatedAt: Long, now: Long): Claim? {
        val s = states[key]
        if (s != null && (s.inFlight || s.answered)) return null
        val reason = when {
            s == null || s.total == 0 -> if (qualifies) Reason.FIRST else null
            separatedAt != 0L && separatedAt != s.separationCovered -> Reason.SEPARATED
            qualifies && s.automatic < maxAutomatic && now - s.lastAskedAt >= retryMs -> Reason.RETRY
            else -> null
        } ?: return null
        val state = s ?: State().also { states[key] = it }
        state.inFlight = true
        state.pendingSeparation = separatedAt
        return Claim(reason, state.total + 1)
    }

    /**
     * The owner asked from the locator: allowed whenever no exchange with [key] is in
     * flight, answered or not and whatever the count. It does not use up an automatic attempt.
     */
    @Synchronized
    fun claimManual(key: String, separatedAt: Long = 0L): Claim? {
        val s = states.getOrPut(key) { State() }
        if (s.inFlight) return null
        s.inFlight = true
        s.pendingSeparation = separatedAt
        return Claim(Reason.MANUAL, s.total + 1)
    }

    /**
     * The exchange [claim] stood for has ended: it began at [startedAt], and the
     * tracker named itself when [answered]. Returns when the scanner will ask again on
     * its own if the device still qualifies, or 0 when it will not.
     */
    @Synchronized
    fun finished(key: String, claim: Claim, startedAt: Long, answered: Boolean): Long {
        val s = states.getOrPut(key) { State() }
        s.inFlight = false
        s.lastAskedAt = startedAt
        s.total++
        if (claim.reason != Reason.MANUAL) s.automatic++
        if (answered) s.answered = true
        s.separationCovered = s.pendingSeparation
        return if (s.answered || s.automatic >= maxAutomatic) 0L else startedAt + retryMs
    }

    /** The claim never became an exchange (Bluetooth off, no permission): nothing is counted. */
    @Synchronized
    fun abandoned(key: String) {
        val s = states[key] ?: return
        s.inFlight = false
        if (s.total == 0) states.remove(key)
    }

    /** Forgets every device. Used by "clear all data". */
    @Synchronized
    fun clear() = states.clear()

    companion object {
        /** How long after an unanswered exchange the next automatic one may begin. */
        const val RETRY_MS = 15 * 60_000L
        /** Automatic exchanges per device; the owner can always ask once more from the locator. */
        const val MAX_AUTOMATIC = 6

        /**
         * Whether a device is worth a Bluetooth connection: a type whose maker ships
         * DULT that has stayed around, or anything confirmed following. Not every tag
         * passing in the street: a busy pavement is dozens an hour.
         */
        fun qualifies(d: Detection): Boolean =
            (d.persistent && d.tracker?.id?.let { it in DultInterrogator.SUPPORTED } == true) || d.following

        /**
         * Whether [type] is made by a maker that ships DULT, in either state: a
         * separated reading ([DultInterrogator.SUPPORTED]) or one with its owner nearby.
         * For such a tracker a refusal means its owner is probably near; for anything
         * else it more likely means the device does not speak DULT at all.
         */
        fun dultCapable(type: TrackerType?): Boolean =
            type != null && (type.id in DultInterrogator.SUPPORTED || Signatures.withOwner(type))

        /**
         * The locator's one-line account of the last exchange with a tracker, such as
         * "Asked 12:04 — refused (owner may be near); will try again". Null before any
         * exchange has begun. [qualifies] is [qualifies] of the device now, [capable]
         * its [dultCapable]; [clock] formats a time of day.
         */
        fun statusLine(result: DultResult?, qualifies: Boolean, capable: Boolean = true, clock: (Long) -> String): String? {
            val r = result ?: return null
            if (r.askedAt == 0L) return null
            val asked = "Asked ${clock(r.askedAt)}"
            if (!r.complete) return "$asked — asking now"
            val outcome = when {
                r.answered -> "answered" + (r.label?.let { ": $it" } ?: "")
                r.refused && capable -> "refused (owner may be near)"
                r.refused -> "no answer (it may not support DULT)"
                else -> r.error?.trimEnd('.')?.replaceFirstChar { it.lowercase() } ?: "no answer"
            }
            val next = when {
                r.answered -> ""
                r.retryAt != 0L && qualifies -> "; will try again"
                r.retryAt != 0L -> "; will try again if it stays around"
                else -> "; no more automatic attempts"
            }
            return "$asked — $outcome$next"
        }
    }
}
