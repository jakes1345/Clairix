package app.clairix.comms

/**
 * How often this phone starts a new session and asks a contact to resync after
 * an envelope it could not read.
 *
 * Each resync fetches a bundle (spending one of the contact's one-time keys)
 * and sends an envelope, so a stream of unreadable envelopes must not turn
 * into a stream of resyncs. The sender of such an envelope is now authenticated
 * (its claim is signed, see comms-crypto), but a relay can still replay old
 * ones or a contact's phone can misbehave. Three caps apply: at most one resync
 * per contact a minute, at most [maxPerContactPerHour] per contact in any hour,
 * and at most [maxPerHour] in all in any hour, whoever they are for. The
 * per-contact hourly cap is well under the total, so one contact sending
 * unreadable envelopes cannot use up the recoveries everyone else may need. A hint signed longer ago than the relay keeps envelopes, or
 * from the future, is not acted on at all.
 *
 * Pure Kotlin with an injected clock, so the JVM tests drive it directly.
 * Not thread-safe; the repository calls it under its lock.
 */
class ResyncLimiter(
    private val perContactIntervalMs: Long = PER_CONTACT_INTERVAL_MS,
    private val maxPerHour: Int = MAX_PER_HOUR,
    private val maxPerContactPerHour: Int = MAX_PER_CONTACT_PER_HOUR,
) {
    private val lastByContact = HashMap<String, Long>()
    private val recent = ArrayDeque<Long>()
    private val recentByContact = HashMap<String, ArrayDeque<Long>>()

    sealed class Decision {
        object Allowed : Decision()
        /** A resync to this contact went out less than a minute ago. */
        object TooSoonForContact : Decision()
        /** The hourly cap, for this contact or for all, is used up; [retryAtMs] is when a slot frees. */
        class HourlyCapReached(val retryAtMs: Long) : Decision()
        /** The hint is older than any envelope the relay could still hold, or dated in the future. */
        object StaleHint : Decision()
    }

    /** Decides whether a resync for [contact] may start now, and if so counts it. */
    fun tryAcquire(contact: String, hintSentAtMs: Long, nowMs: Long): Decision {
        if (hintSentAtMs < nowMs - MAX_HINT_AGE_MS || hintSentAtMs > nowMs + MAX_CLOCK_SKEW_MS) return Decision.StaleHint
        lastByContact[contact]?.let { if (nowMs - it < perContactIntervalMs) return Decision.TooSoonForContact }
        val theirs = recentByContact.getOrPut(contact) { ArrayDeque() }
        while (theirs.isNotEmpty() && nowMs - theirs.first() >= HOUR_MS) theirs.removeFirst()
        if (theirs.size >= maxPerContactPerHour) return Decision.HourlyCapReached(theirs.first() + HOUR_MS)
        while (recent.isNotEmpty() && nowMs - recent.first() >= HOUR_MS) recent.removeFirst()
        if (recent.size >= maxPerHour) return Decision.HourlyCapReached(recent.first() + HOUR_MS)
        recent.addLast(nowMs)
        theirs.addLast(nowMs)
        lastByContact[contact] = nowMs
        if (lastByContact.size > 1000) lastByContact.entries.removeAll { nowMs - it.value >= perContactIntervalMs }
        if (recentByContact.size > 1000) recentByContact.entries.removeAll { (_, q) -> q.isEmpty() || nowMs - q.last() >= HOUR_MS }
        return Decision.Allowed
    }

    /**
     * The resync for [contact] could not start (the relay was unreachable): it
     * may be tried again for that contact straight away. Its slot in the hourly
     * cap stays used, so failures cannot be turned into unbounded retries.
     */
    fun forgetContact(contact: String) {
        lastByContact.remove(contact)
    }

    /** How many resyncs the last hour has seen. */
    fun usedThisHour(nowMs: Long): Int = recent.count { nowMs - it < HOUR_MS }

    companion object {
        const val PER_CONTACT_INTERVAL_MS = 60_000L
        const val MAX_PER_HOUR = 30
        const val MAX_PER_CONTACT_PER_HOUR = 3
        const val HOUR_MS = 3_600_000L
        /** The relay keeps envelopes 30 days; a day more covers clocks. */
        const val MAX_HINT_AGE_MS = 31L * 24 * HOUR_MS
        const val MAX_CLOCK_SKEW_MS = 24 * HOUR_MS
    }
}
