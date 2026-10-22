package app.clairix.nav

import java.security.SecureRandom
import java.util.Base64

/**
 * The name a drive goes by on the relay.
 *
 * Probes, incident reports and votes are posted anonymously (see
 * [app.clairix.comms.RelayClient]); what lets the relay count vehicles and
 * take one vote per vehicle is this: 16 random bytes made up when the drive
 * begins and replaced every [rotateAfterMs]. Within one pseudonym's life the
 * relay can tell this vehicle's samples from another's, which stoppage
 * detection needs; across pseudonyms, and ever back to a phone, it cannot.
 * The value never touches the disk.
 */
class TripPseudonym(
    private val rotateAfterMs: Long = ROTATE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private var id: String? = null
    private var madeAt = 0L

    /** The pseudonym to post under now; a fresh one once the current one is old enough. */
    @Synchronized
    fun current(): String {
        val now = clock()
        val cur = id
        if (cur != null && now - madeAt < rotateAfterMs) return cur
        val fresh = ByteArray(16).also { random.nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        id = fresh
        madeAt = now
        return fresh
    }

    /** A new drive: the next [current] is unrelated to anything posted before. */
    @Synchronized
    fun reset() {
        id = null
        madeAt = 0L
    }

    companion object {
        /** Long enough for the relay's five-minute stoppage window, and no longer. */
        const val ROTATE_MS = 10 * 60_000L
        /** 16 bytes, base64url without padding, as the relay requires. */
        val FORMAT = Regex("^[A-Za-z0-9_-]{22}$")
    }
}
