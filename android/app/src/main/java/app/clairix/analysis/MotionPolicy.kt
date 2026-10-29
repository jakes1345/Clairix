package app.clairix.analysis

import kotlin.math.max

/**
 * Whether the phone is going anywhere, which decides how hard the scanner works for its
 * position. Following is judged from places a device is heard at, and a phone that sits
 * on a desk all afternoon makes no new places: running satellite fixes every fifteen
 * seconds there was most of the scanner's battery cost and bought nothing.
 *
 * [Pace.MOVING] until no fix has shown movement for [stillAfterMs], then [Pace.STILL].
 * Movement is a fix at walking speed or one far enough from where the phone last
 * settled, judged against both fixes' accuracy so that a coarse fix wandering about
 * is not a walk. The phone's significant-motion sensor ([onMotion]) ends a still spell
 * at once; without one, the next coarse fix that has moved ends it.
 *
 * Thread-safe: fixes arrive on the main thread, the clock ticks on the service's loop.
 */
class MotionPolicy(private val stillAfterMs: Long = STILL_AFTER_MS) {

    enum class Pace { MOVING, STILL }

    @Volatile
    var pace: Pace = Pace.MOVING
        private set

    /** Where the phone last settled: the first fix, then each fix that counted as movement. */
    private var anchor: Fix? = null
    private var lastMovedAt = 0L
    /** When the newest fix arrived, moving or not. */
    private var lastFixAt: Long? = null

    /** Starts out moving, so a fresh start gets precise fixes while it learns where it is. */
    @Synchronized
    fun start(now: Long) {
        pace = Pace.MOVING
        lastMovedAt = now
        lastFixAt = null
        anchor = null
    }

    /**
     * Settled, with a fix in the last [CONFIRMED_FIX_MS] that showed it: evidence that
     * the phone is standing still, which the cell checks can use. A settled spell with
     * no fixes (a train underground, where neither GPS nor the motion sensor may
     * notice the ride) proves nothing and does not count.
     */
    @Synchronized
    fun confirmedStill(now: Long): Boolean =
        pace == Pace.STILL && lastFixAt.let { it != null && now - it <= CONFIRMED_FIX_MS }

    /** A new fix; returns the new pace if it changed. */
    @Synchronized
    fun onFix(fix: Fix): Pace? {
        lastFixAt = max(lastFixAt ?: fix.time, fix.time)
        val a = anchor
        if (a == null) {
            anchor = fix
            return null
        }
        val fast = (fix.speed ?: 0f) >= MOVING_SPEED_MS
        val slack = max(ANCHOR_M, (fix.accuracy ?: UNKNOWN_ACCURACY_M).toDouble() + (a.accuracy ?: UNKNOWN_ACCURACY_M).toDouble())
        if (!fast && haversine(a, fix) <= slack) return null
        anchor = fix
        lastMovedAt = max(lastMovedAt, fix.time)
        return switchTo(Pace.MOVING)
    }

    /** The significant-motion sensor fired: the phone is being carried. */
    @Synchronized
    fun onMotion(now: Long): Pace? {
        lastMovedAt = max(lastMovedAt, now)
        return switchTo(Pace.MOVING)
    }

    /** Called on the service's loop; returns [Pace.STILL] when the phone has settled. */
    @Synchronized
    fun tick(now: Long): Pace? =
        if (pace == Pace.MOVING && now - lastMovedAt >= stillAfterMs) switchTo(Pace.STILL) else null

    private fun switchTo(p: Pace): Pace? {
        if (pace == p) return null
        pace = p
        return p
    }

    companion object {
        /** No movement for this long and the phone has settled; a red light or a queue is shorter. */
        const val STILL_AFTER_MS = 4 * 60_000L
        /** A slow walk; GPS speed on a phone at rest stays well under it. */
        const val MOVING_SPEED_MS = 1.0f
        /** At least this far from where it settled before a fix counts as moving. */
        const val ANCHOR_M = 75.0
        /** How recent a fix must be for a settled phone to count as standing still. */
        const val CONFIRMED_FIX_MS = 6 * 60_000L
        /** Assumed for a fix that does not say how accurate it is. */
        private const val UNKNOWN_ACCURACY_M = 50f
    }
}
