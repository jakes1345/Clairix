package app.clairix.comms

/**
 * When the disappearing-messages sweep runs, as decisions apart from the
 * repository so they can be tested.
 *
 * The sweep runs only while something counts down: an identity is registered
 * and the store holds a message whose clock has started (`nextExpiry` not
 * null). It sleeps exactly until that message is due, deletes what is due and
 * looks again; with nothing counting down it stops, and whatever starts a
 * clock (a timed message sent or read, the vault opening, the app coming to
 * the front) starts it again. What is past its time is never shown even
 * before the sweep deletes it: the store's reads leave it out.
 */
internal object ExpirySchedule {

    /** Never sooner than this after a pass, so messages due together go in one. */
    const val MIN_WAIT_MS = 250L

    /** After a pass that failed (the database busy for a moment, say), the next try. */
    const val RETRY_MS = 60_000L

    /** Whether the next message to go is due already, so a pass deletes rather than only looks. */
    fun due(nextExpiry: Long?, now: Long): Boolean = nextExpiry != null && nextExpiry <= now

    /**
     * How long the sweep sleeps before it looks again, or null when it stops:
     * nothing counts down (or the vault is sealed, which closes the database;
     * it is woken when the vault opens). [failed] is a pass that threw.
     */
    fun waitMs(nextExpiry: Long?, failed: Boolean, now: Long): Long? = when {
        failed -> RETRY_MS
        nextExpiry == null -> null
        else -> (nextExpiry - now).coerceAtLeast(MIN_WAIT_MS)
    }
}
