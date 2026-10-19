package app.clairix.comms

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The immediate retry after a send found the delivery-token pool empty.
 *
 * Single-flight: while one refill is running, further empty-pool sends do not
 * start another. The waiting envelopes are retried at once only when the
 * fetch actually left tokens in the pool; when it failed (offline, relay
 * down) nothing is retried here, and the outbox's own backoff
 * ([CommsRepository]'s scheduleRetry, which tops up first) takes over. So a
 * failing token fetch can never turn into a loop of fetch, flush, empty pool,
 * fetch.
 *
 * [topUp] fetches tokens if few are left and returns how many the pool holds
 * afterwards (it may throw); [retry] flushes what was waiting; [launch] runs
 * the refill off the caller's thread.
 */
class TokenRefill(
    private val topUp: () -> Int,
    private val retry: () -> Unit,
    private val launch: (() -> Unit) -> Unit,
) {
    private val inFlight = AtomicBoolean(false)

    /** A send found no token. Returns true when this call started a refill. */
    fun onNoTokens(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        launch {
            val refilled = try {
                runCatching { topUp() }.getOrDefault(0) > 0
            } finally {
                inFlight.set(false)
            }
            if (refilled) retry()
        }
        return true
    }
}
