package app.clairix.comms

/**
 * How often [TorTransport] looks at Arti, and when it lets it sleep, as
 * decisions apart from the transport so they can be tested.
 *
 * While Tor bootstraps its progress is read every second, for the Comms
 * settings line. Once it is ready nothing much changes: its state is read
 * before each new connection and otherwise once a minute. And Tor need not run
 * at all while Clairix has no reason to talk to a relay: with the background
 * connection off, no screen or call holding the link, and nothing having gone
 * through Tor for [IDLE_SLEEP_MS], Arti is stopped, and the next connection
 * starts it again (it waits for Tor, never goes out directly).
 */
internal object TorPacing {

    /** While Tor bootstraps, its progress is read this often. */
    const val BOOTSTRAP_POLL_MS = 1_000L

    /** Once it is ready, its state is read this often at most, besides before each new connection. */
    const val READY_POLL_MS = 60_000L

    /**
     * Unneeded and unused for this long, Tor sleeps. Longer than any request
     * through it may take (the longest call timeout through Tor is under two
     * minutes), so none is cut off.
     */
    const val IDLE_SLEEP_MS = 5 * 60_000L

    /** How long until Arti's state is read again. */
    fun pollMs(ready: Boolean): Long = if (ready) READY_POLL_MS else BOOTSTRAP_POLL_MS

    /**
     * Whether Tor may sleep now: nothing keeps it running ([needed]: the
     * background connection on, or the link held by the screen or a call) and
     * nothing has gone through it since [lastUseAt] for [IDLE_SLEEP_MS].
     */
    fun shouldSleep(needed: Boolean, lastUseAt: Long, now: Long): Boolean = !needed && now - lastUseAt >= IDLE_SLEEP_MS
}
