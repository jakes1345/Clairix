package app.clairix.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The magnetic gauge's verdict, kept free of Android types so it can be tested
 * (the sweep's sensor listener feeds it field magnitudes).
 *
 * The baseline is the room's own field, learned from quiet samples. A deviation
 * above max([MIN_DELTA_UT], 4 sigma of the quiet samples), held for [SUSTAIN_MS],
 * starts an episode: something magnetic is near the phone.
 *
 * Three things are not a magnet behind the wall, and are not reported as one:
 *  - **A calibration change.** When the platform re-estimates the sensor (an
 *    accuracy change), everything learned so far is dropped and the baseline is
 *    learned again from where the phone is now ([accuracyChanged]).
 *  - **A step that stays.** Sliding from drywall onto a steel stud or a door frame
 *    moves the field to a new level and leaves it there. Once an episode has
 *    lasted [LEARN_AFTER_MS] the baseline follows the field, slowly, so the
 *    reading decays back to quiet instead of saying "Anomaly" until the gauge is
 *    restarted. A magnet passed over in the slide is gone well before then, and
 *    leaves the baseline where it was.
 *  - **Coming back off that step.** The level from before a step the baseline
 *    has followed is remembered for [PRIOR_KEEP_MS]; heading back to it is the
 *    phone leaving the stud, not a new find.
 *
 * While the platform calls its own readings unreliable there is no verdict at
 * all: the gauge asks for a figure-eight recalibration instead.
 */
internal class MagneticBaseline {

    companion object {
        /** Samples averaged into the first baseline. */
        const val WARMUP = 40
        /** Quiet samples the spread (sigma) is measured over. */
        const val WINDOW = 96
        const val MIN_DELTA_UT = 18f
        const val SUSTAIN_MS = 250L
        /** After an episode ends, a new one may not start for this long. */
        const val COOLDOWN_MS = 1_500L
        /** Per-sample learning rate while the field is quiet. */
        const val ALPHA = 0.03f
        /** An episode this old is a step to a new level: the baseline starts following it. */
        const val LEARN_AFTER_MS = 3_000L
        /** Time constant of that following. */
        const val FOLLOW_TAU_MS = 4_000L
        /** How long the level from before a followed step is remembered. */
        const val PRIOR_KEEP_MS = 60_000L
        /** [android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE], spelled out so the tests need no Android stub. */
        const val ACCURACY_UNRELIABLE = 0
        /** No accuracy reported yet. */
        const val ACCURACY_UNKNOWN = Int.MIN_VALUE
        /** Samples further apart than this (a stalled sensor) count as this far apart. */
        private const val MAX_STEP_MS = 250L
        private const val NEVER = Long.MIN_VALUE / 4
    }

    /** One sample's verdict. */
    data class Reading(
        val fieldUt: Float,
        val baselineUt: Float,
        val deltaUt: Float,
        val thresholdUt: Float,
        /** Largest deviation in the current or last episode. */
        val peakUt: Float,
        val triggered: Boolean,
        /** The platform says its readings cannot be trusted: no verdict until it recovers. */
        val unreliable: Boolean,
        val warmingUp: Boolean,
        /** An episode began on this sample. */
        val started: Boolean,
        /** The running episode's peak grew on this sample. */
        val peakGrew: Boolean
    )

    private val window = FloatArray(WINDOW)
    private var wi = 0
    private var wn = 0
    private var baseline = 0f
    private var warm = 0
    private var onsetMs = -1L
    private var triggered = false
    private var triggeredAtMs = -1L
    /** The baseline when the running episode began. */
    private var episodeBaseline = 0f
    private var peak = 0f
    private var episodeEndedMs = NEVER
    private var lastMs = -1L
    /** The level from before a step the baseline followed, or NaN. */
    private var prior = Float.NaN
    private var priorUntilMs = NEVER
    private var accuracy = ACCURACY_UNKNOWN

    /** The platform's last word on the sensor was "unreliable". */
    var unreliable = false
        private set

    /**
     * The sensor's accuracy changed. Every transition re-seeds: the platform has
     * re-estimated the field, so the baseline and the spread learned before no
     * longer describe it, and a jump in the readings at this moment is the
     * estimate moving, not a magnet. Returns whether it re-seeded (false for the
     * accuracy already known), so the listener takes the platform's new offset
     * estimate at the same moment ([HardIronHold.release]).
     */
    fun accuracyChanged(newAccuracy: Int): Boolean {
        if (newAccuracy == accuracy) return false
        accuracy = newAccuracy
        unreliable = newAccuracy == ACCURACY_UNRELIABLE
        reseed()
        return true
    }

    private fun reseed() {
        warm = 0
        resetWindow()
        onsetMs = -1L
        triggered = false
        prior = Float.NaN
        priorUntilMs = NEVER
        lastMs = -1L
    }

    private fun resetWindow() {
        wi = 0
        wn = 0
    }

    private fun push(v: Float) {
        window[wi] = v
        wi = (wi + 1) % WINDOW
        if (wn < WINDOW) wn++
    }

    private fun sigma(): Float {
        if (wn < 8) return 0f
        var s = 0.0
        for (i in 0 until wn) s += window[i]
        val m = s / wn
        var v = 0.0
        for (i in 0 until wn) { val d = window[i] - m; v += d * d }
        return sqrt(v / wn).toFloat()
    }

    /** One field magnitude, µT, at [nowMs] on a monotonic clock. */
    fun sample(fieldUt: Float, nowMs: Long): Reading {
        val stepMs = if (lastMs < 0L) 0L else (nowMs - lastMs).coerceIn(0L, MAX_STEP_MS)
        lastMs = nowMs

        if (unreliable) {
            // Paused: whatever the needle does now, it is not a reading to act on.
            triggered = false
            onsetMs = -1L
            return Reading(fieldUt, baseline, 0f, 0f, peak, triggered = false, unreliable = true, warmingUp = false, started = false, peakGrew = false)
        }

        if (warm < WARMUP) {
            warm++
            baseline = if (warm == 1) fieldUt else baseline + (fieldUt - baseline) / warm
            push(fieldUt)
            return Reading(fieldUt, baseline, 0f, 0f, peak, triggered = false, unreliable = false, warmingUp = true, started = false, peakGrew = false)
        }

        val threshold = max(MIN_DELTA_UT, 4f * sigma())
        var delta = abs(fieldUt - baseline)
        var started = false
        var peakGrew = false

        if (!triggered) {
            if (!prior.isNaN() && nowMs > priorUntilMs) prior = Float.NaN
            val back = prior
            if (delta > threshold && !back.isNaN() && abs(fieldUt - back) < delta) {
                // Heading back to the level from before a step the baseline followed:
                // the phone leaving the stud, or the magnet it was held over. Once it
                // is there, that level is the baseline again.
                onsetMs = -1L
                if (abs(fieldUt - back) <= threshold / 2f) {
                    baseline = back
                    prior = Float.NaN
                    resetWindow()
                    push(fieldUt)
                    delta = abs(fieldUt - baseline)
                }
            } else if (delta > threshold) {
                if (onsetMs < 0L) {
                    onsetMs = nowMs
                } else if (nowMs - onsetMs >= SUSTAIN_MS && nowMs - episodeEndedMs >= COOLDOWN_MS) {
                    triggered = true
                    triggeredAtMs = nowMs
                    episodeBaseline = baseline
                    peak = delta
                    started = true
                }
            } else {
                onsetMs = -1L
                // Quiet samples teach the baseline; the thing hunted for does not.
                baseline += ALPHA * (fieldUt - baseline)
                push(fieldUt)
            }
        } else {
            if (delta > peak) { peak = delta; peakGrew = true }
            if (nowMs - triggeredAtMs >= LEARN_AFTER_MS) {
                // Still there after this long: a new level, not a magnet slid past. Follow it.
                baseline += (stepMs.toFloat() / FOLLOW_TAU_MS).coerceAtMost(1f) * (fieldUt - baseline)
                delta = abs(fieldUt - baseline)
            }
            if (delta < threshold / 2f) {
                triggered = false
                onsetMs = -1L
                episodeEndedMs = nowMs
                if (abs(baseline - episodeBaseline) > threshold / 2f) {
                    // The baseline followed the step: remember where it came from, and
                    // measure the spread afresh at the new level.
                    prior = episodeBaseline
                    priorUntilMs = nowMs + PRIOR_KEEP_MS
                    resetWindow()
                }
            }
        }
        return Reading(fieldUt, baseline, delta, threshold, peak, triggered, unreliable = false, warmingUp = false, started = started, peakGrew = peakGrew)
    }
}

/**
 * The field magnitude the gauge judges, from a magnetometer event's values.
 *
 * From TYPE_MAGNETIC_FIELD_UNCALIBRATED, values[0..2] are the raw field with the
 * phone's own hard-iron offset still in it, and values[3..5] the platform's
 * estimate of that offset. The offset turns with the phone and the room's field
 * does not, so the length of their sum changes as the phone tilts, by up to twice
 * the smaller of the two: often far more than the gauge's trigger, which would
 * read every tilt as a magnet. The calibrated stream subtracts the offset but
 * steps whenever the platform re-estimates it.
 *
 * So the estimate is taken at the first sample and held: the length of (raw -
 * held offset) stays put as the phone turns, and does not step when the
 * platform's estimate moves. [release] takes a fresh estimate at the next sample;
 * the listener calls it whenever the baseline re-seeds on an accuracy change.
 * Values with no offset estimate (the calibrated stream) are used as they are.
 */
internal class HardIronHold {

    private var held: FloatArray? = null

    /** The next sample's offset estimate is held from then on. */
    fun release() {
        held = null
    }

    fun magnitude(values: FloatArray): Float {
        if (values.size < 6) return length(values[0], values[1], values[2])
        val b = held ?: floatArrayOf(values[3], values[4], values[5]).also { held = it }
        return length(values[0] - b[0], values[1] - b[1], values[2] - b[2])
    }

    private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
