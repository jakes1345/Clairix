package app.clairix.supporter

/**
 * Whether this install is a Supporter (docs/SUPPORTER.md), and what that
 * lifts. The one place the app asks.
 *
 * A Supporter is an install that holds at least one unexpired Supporter
 * credential: a blind token the relay issued for a code or a purchase, on a
 * request that named nobody. There is no flag here that billing, a setting or
 * anything else can switch on: the answer is the credential pool's, read
 * through [bind] (CommsRepository binds the relay client's pool at start).
 *
 * Supporter lifts only relay-cost limits. No safety feature may ask this
 * object anything: the scan, Phone check, alerts, removal safety, evidence,
 * helplines, Clean link, bug-report import, SOS and ordinary messaging work
 * the same either way, and SafetyStaysFreeTest fails the build if code on
 * those paths names it.
 */
object Entitlement {

    /** The largest photo or video a free install sends: the limit every version before Supporter had. */
    const val FREE_MEDIA_MAX_BYTES = 5L * 1024 * 1024

    /**
     * The largest a Supporter sends. Every install receives up to this much
     * (CommsMedia.RECEIVE_MAX_BYTES), so a Supporter's video reaches anyone;
     * it is 342 envelopes, well inside a mailbox's queue of 2000.
     */
    const val SUPPORTER_MEDIA_MAX_BYTES = 10L * 1024 * 1024

    /** Counts the unexpired credentials held at a moment. */
    fun interface Credentials {
        fun usable(nowMs: Long): Int
    }

    @Volatile
    private var credentials: Credentials = Credentials { 0 }

    /** Points the answer at the credential pool. Until then, and in a build with no comms, nobody is a Supporter. */
    fun bind(source: Credentials) {
        credentials = source
    }

    /** Credentials left: how many more batches of delivery tokens this install can draw beyond its allowance. */
    fun credentialsLeft(nowMs: Long = System.currentTimeMillis()): Int =
        runCatching { credentials.usable(nowMs) }.getOrDefault(0).coerceAtLeast(0)

    fun isSupporter(nowMs: Long = System.currentTimeMillis()): Boolean = credentialsLeft(nowMs) > 0

    /** The largest photo or video this install may send. */
    fun mediaSendLimitBytes(nowMs: Long = System.currentTimeMillis()): Long =
        if (isSupporter(nowMs)) SUPPORTER_MEDIA_MAX_BYTES else FREE_MEDIA_MAX_BYTES
}
