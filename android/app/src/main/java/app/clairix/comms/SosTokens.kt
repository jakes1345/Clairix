package app.clairix.comms

/**
 * Delivery tokens for an SOS, fetched before its sends.
 *
 * A send never fetches tokens itself (RelayClient.send: a fetch right before
 * a send would let the relay pair the two), so an empty pool makes every
 * envelope "not now" until a top-up. A phone back after weeks away has an
 * empty pool, since tokens last two to four weeks, and often has not seen the
 * relay's blind-token keys for the current fortnight either, which an
 * ordinary top-up leaves to a moment chosen at random within five minutes. An
 * SOS cannot wait for either: before its first pass, and before each retry,
 * the pool is filled to what the SOS needs ([target]), reading the relay's
 * keys first when this phone does not hold the current ones ([step]).
 */
object SosTokens {

    /** Kept on hand beyond the envelopes an SOS needs: the location that follows it, the receipts after. */
    const val MARGIN = 10
    /**
     * The most fetches before one SOS pass: a fetch brings at most 50 blind
     * tokens, so up to 250, enough from an empty pool for the largest group
     * (200 members) with the margin.
     */
    const val MAX_FETCHES = 5

    enum class Step {
        /** The pool holds enough. */
        ENOUGH,
        /** Fetch a batch. */
        FETCH,
        /** Read the relay's keys for the current epoch (GET /, a request that names nobody), then fetch. */
        READ_KEYS_FIRST
    }

    /** How many tokens to hold before an SOS to [envelopes] members: never fewer than the pool's own low mark. */
    fun target(envelopes: Int): Int = maxOf(TokenKind.DELIVERY.low, envelopes + MARGIN)

    /**
     * What to do next with [held] tokens in the pool, for an SOS to
     * [envelopes] members. [keysCurrent]: this phone holds the relay's keys
     * for the current epoch, or the relay issues no blind tokens (see
     * RelayClient.tokenKeysCurrent).
     */
    fun step(held: Int, envelopes: Int, keysCurrent: Boolean): Step = when {
        held >= target(envelopes) -> Step.ENOUGH
        keysCurrent -> Step.FETCH
        else -> Step.READ_KEYS_FIRST
    }
}
