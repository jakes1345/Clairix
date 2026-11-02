package app.clairix.nav

/**
 * Whether the tile server has stopped giving the maps their background, from
 * the HTTP answer to each tile request. [failures] answers in the 400-599
 * range within [windowMs], with no tile served since the first of them, are a
 * server refusing Clairix (a block, a rate limit) or failing (an outage), not
 * a tile missing here and there, and the maps say so instead of going grey.
 * The first tile served clears it. What is not an HTTP answer (no network, a
 * timeout, a redirect) says nothing about the server and is not counted.
 * Thread-safe: osmdroid downloads on several threads.
 */
class TileHealth(private val failures: Int = FAILURES, private val windowMs: Long = WINDOW_MS) {

    /** The server is refusing or failing: [status] is its latest answer, [since] when the run of them began. */
    data class Outage(val status: Int, val since: Long) {
        /** A 4xx answer: the server is turning Clairix away, rather than broken. */
        val refused: Boolean get() = status in 400..499

        /** What the map says about it, naming the server as the tile setting does ([TileConfig.name]). */
        fun describe(serverName: String): String =
            if (refused) "$serverName's tile server is refusing the map's requests (error $status), so the map background stays blank."
            else "$serverName's tile server is failing (error $status), so the map background stays blank."
    }

    private val recent = ArrayDeque<Long>()
    private var outage: Outage? = null

    /** Counts the [status] one tile request was answered with at [nowMs]; returns the outage after it, null while tiles are coming. */
    @Synchronized
    fun record(status: Int, nowMs: Long): Outage? {
        when (status) {
            in 200..299 -> {
                recent.clear()
                outage = null
            }
            in 400..599 -> {
                recent.addLast(nowMs)
                while (nowMs - recent.first() > windowMs) recent.removeFirst()
                val ongoing = outage
                outage = when {
                    ongoing != null -> ongoing.copy(status = status)
                    recent.size >= failures -> Outage(status, recent.first())
                    else -> null
                }
            }
        }
        return outage
    }

    /** The outage now, or null. */
    @Synchronized
    fun current(): Outage? = outage

    /** Forgets every answer: the maps moved to another server. */
    @Synchronized
    fun reset() {
        recent.clear()
        outage = null
    }

    companion object {
        /**
         * A screen of map is a dozen tiles or more, all asked for at once, so
         * a server refusing them all crosses this within a second; a stray
         * error between tiles that load never reaches it.
         */
        const val FAILURES = 6

        /** Errors further apart than this are not one run. */
        const val WINDOW_MS = 2 * 60_000L
    }
}
