package app.clairix

import kotlin.math.roundToInt

/**
 * What the Trackers screen, the hub and the map count as on you now, and how the
 * follow check's thresholds are put in words.
 *
 * A confirmed follower is remembered for a week after it was last heard, so the
 * screens ask more than "was it confirmed": a device the owner marked found no
 * longer counts, and one not heard for [ON_YOU_MS] was on you earlier rather than now.
 */
internal object FollowerStatus {

    /** A confirmed follower heard within this long counts as on you now. */
    const val ON_YOU_MS = 30 * 60_000L

    /** Confirmed as moving with you and not marked found. */
    fun withYou(d: Detection): Boolean = d.following && !d.resolved

    /** [withYou], and heard within [ON_YOU_MS] of [now]. */
    fun onYouNow(d: Detection, now: Long): Boolean = withYou(d) && now - d.lastSeen < ON_YOU_MS

    /** [withYou], but not heard for [ON_YOU_MS] or more: it followed you earlier. */
    fun followedEarlier(d: Detection, now: Long): Boolean = withYou(d) && now - d.lastSeen >= ON_YOU_MS

    /**
     * What proving a follower takes with the owner's settings: "10 minutes of presence
     * and a 300 m move". Both thresholds are adjustable in Settings, so the screens
     * read them rather than repeating the defaults.
     */
    fun proof(persistMin: Float, followM: Float): String {
        val minutes = persistMin.roundToInt()
        return "${if (minutes == 1) "1 minute" else "$minutes minutes"} of presence and a ${followM.roundToInt()} m move"
    }
}
