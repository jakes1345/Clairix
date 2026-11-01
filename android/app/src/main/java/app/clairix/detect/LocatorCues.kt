package app.clairix.detect

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the tracker locator says and does for someone who cannot, or should not, keep
 * watching the screen: a screen reader user, or someone feeling under a car at night.
 *
 * The compass is drawn on a canvas, which a screen reader cannot see, so it gets a
 * spoken description ([description]); and the line under it is a live region whose
 * text ([announcement]) changes only when the distance moves into another band or the
 * signal turns warmer or colder, so TalkBack speaks as she gets closer without
 * reading out every fluctuation. The phone can also pulse ([pulsePeriodMs]), faster
 * the stronger the signal, like a Geiger counter.
 */
object LocatorCues {

    /** Pulse period at the weak end of the range ([WEAK_DBM] and below). */
    const val SLOWEST_PULSE_MS = 1_200L

    /** Pulse period at the strong end of the range ([STRONG_DBM] and above): within arm's reach. */
    const val FASTEST_PULSE_MS = 120L

    private const val WEAK_DBM = -90f
    private const val STRONG_DBM = -45f

    /** Nothing heard for this long and the signal counts as lost: no pulse, "Signal lost". */
    const val LOST_MS = 4_000L

    /**
     * The distance in words, in bands as wide as the estimate is honest (it can be out
     * by a factor of two indoors): the band changing means she really moved.
     */
    fun distanceBand(metres: Float): String = when {
        metres.isNaN() -> "distance unknown"
        metres < 1.5f -> "within about a metre"
        metres < 3f -> "about 2 metres"
        metres < 6f -> "about 4 metres"
        metres < 12f -> "about 8 metres"
        metres < 25f -> "about 15 metres"
        else -> "more than 25 metres"
    }

    /** Whether the target has been heard recently enough to guide by. */
    fun heard(state: LocatorState): Boolean = state.lastSeenAgoMs?.let { it <= LOST_MS } == true

    /**
     * The live-region line: "Warmer, about 2 metres". Changes only with the trend and
     * the distance band, so a screen reader speaks when there is news.
     */
    fun announcement(state: LocatorState): String = when {
        state.lastSeenAgoMs == null -> "Listening for the tracker"
        !heard(state) -> "Signal lost"
        else -> {
            val trend = when (state.trend) {
                SignalTrend.WARMER -> "Warmer"
                SignalTrend.COLDER -> "Colder"
                SignalTrend.STEADY, SignalTrend.SEARCHING -> "Steady"
            }
            "$trend, ${distanceBand(state.estimatedDistanceM)}"
        }
    }

    /**
     * Where the strongest signal was, relative to where the phone points, in words;
     * null until enough directions have been sampled.
     */
    fun bearing(state: LocatorState): String? {
        if (state.bestAzimuthDeg.isNaN()) return null
        var rel = (state.bestAzimuthDeg - state.headingDeg + 360f) % 360f
        if (rel > 180f) rel -= 360f
        return when {
            abs(rel) <= RssiLocator.BUCKET_DEG -> "straight ahead"
            rel > 0 -> "${rel.roundToInt()} degrees to your right"
            else -> "${(-rel).roundToInt()} degrees to your left"
        }
    }

    /** The compass, spoken: distance band, signal, the strongest direction, and confidence. */
    fun description(state: LocatorState): String {
        val parts = ArrayList<String>(4)
        parts.add("Signal compass")
        parts.add(if (heard(state)) distanceBand(state.estimatedDistanceM) else announcement(state).lowercase())
        state.lastRssi?.let { parts.add("$it dBm") }
        parts.add(bearing(state)?.let { "strongest $it" } ?: "turn a full circle to find the strongest direction")
        parts.add("confidence ${(state.confidence * 100).roundToInt()} percent")
        return parts.joinToString(", ")
    }

    /**
     * How often to pulse: [SLOWEST_PULSE_MS] at -90 dBm or weaker down to
     * [FASTEST_PULSE_MS] at -45 dBm or stronger, from the signal of the last second
     * and a half; null when the target is not being heard.
     */
    fun pulsePeriodMs(state: LocatorState): Long? {
        if (!heard(state)) return null
        val rssi = state.smoothedRssi
        if (rssi.isNaN()) return null
        val t = ((rssi - WEAK_DBM) / (STRONG_DBM - WEAK_DBM)).coerceIn(0f, 1f)
        return (SLOWEST_PULSE_MS - t * (SLOWEST_PULSE_MS - FASTEST_PULSE_MS)).roundToInt().toLong()
    }
}
