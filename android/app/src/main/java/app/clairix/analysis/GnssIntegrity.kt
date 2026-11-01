package app.clairix.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** Constellation ids, the same numbers as [android.location.GnssStatus]'s CONSTELLATION_ constants. */
object Gnss {
    const val GPS = 1
    const val SBAS = 2
    const val GLONASS = 3
    const val QZSS = 4
    const val BEIDOU = 5
    const val GALILEO = 6
    const val IRNSS = 7

    fun name(constellation: Int): String = when (constellation) {
        GPS -> "GPS"
        SBAS -> "SBAS"
        GLONASS -> "GLONASS"
        QZSS -> "QZSS"
        BEIDOU -> "BeiDou"
        GALILEO -> "Galileo"
        IRNSS -> "NavIC"
        else -> "Other"
    }
}

/** One satellite as the receiver reported it at one moment. [cn0] is the carrier-to-noise density in dB-Hz. */
data class GnssSat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val used: Boolean,
    val carrierHz: Float? = null
)

/** Everything the receiver could hear at [timeMs] (wall clock). */
data class SkyEpoch(val timeMs: Long, val sats: List<GnssSat>)

/**
 * A position from the satellite receiver alone. [timeMs] is the phone's wall clock when the
 * fix was computed; [satelliteTimeMs] is the time the satellites said it was, which a
 * spoofer replaying a recording, or one that never set its clock, gets wrong.
 */
data class GnssFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val satelliteTimeMs: Long?,
    val mock: Boolean = false
)

/** A position from Wi-Fi and cell towers, which a satellite spoofer does not touch. */
data class NetworkFix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * The receiver's clock and front end at one moment, from the raw measurements API: how
 * fast its oscillator is drifting, a counter that moves on every clock reset, and the
 * automatic gain control level of the GPS L1 front end, which falls when something loud
 * is on the band.
 */
data class GnssClockSample(
    val timeMs: Long,
    val discontinuity: Int,
    val driftNsPerS: Double?,
    val agcDb: Double?
)

enum class GnssVerdict(val label: String) {
    NO_DATA("No satellite data yet"),
    NO_FIX("No satellites in the fix"),
    GENUINE("Signals look genuine"),
    SUSPECT("Signals look unusual"),
    SPOOFED("GPS looks spoofed"),
    JAMMED("GPS looks jammed")
}

enum class CheckState { NO_DATA, PASS, WARN, FAIL }

data class GnssCheck(val id: String, val label: String, val state: CheckState, val detail: String)

data class GnssAssessment(
    val verdict: GnssVerdict = GnssVerdict.NO_DATA,
    val checks: List<GnssCheck> = emptyList(),
    val usedCount: Int = 0,
    val visibleCount: Int = 0,
    /** Constellations with a satellite in the fix, by name. */
    val constellationsUsed: List<String> = emptyList(),
    val meanCn0: Float? = null,
    /** Standard deviation of C/N0 across the satellites in the fix. */
    val spreadCn0: Float? = null,
    val agcDeviationDb: Double? = null,
    /** Satellite time minus the phone's clock, at the latest fix. */
    val timeOffsetMs: Long? = null,
    val lastEpochMs: Long = 0L,
    /** The latest epoch's satellites, strongest first, for the sky list. */
    val sats: List<GnssSat> = emptyList(),
    /**
     * The latest Wi-Fi/cell position, whatever its age. Shown or attached as "where the
     * phone really is" only when [networkAgrees]; see [vouchedNetwork].
     */
    val networkLat: Double? = null,
    val networkLon: Double? = null,
    /** A Wi-Fi/cell position from the last five minutes agrees with the satellites (the network check passes). */
    val networkAgrees: Boolean = false,
    /** The Wi-Fi/cell position disagrees with the satellites (the network check is WARN or FAIL). */
    val networkDisputed: Boolean = false,
    /** An app is supplying positions through Android's mock-location setting. */
    val mockDetected: Boolean = false
) {
    val failing: List<GnssCheck> get() = checks.filter { it.state == CheckState.FAIL }
    val warning: List<GnssCheck> get() = checks.filter { it.state == CheckState.WARN }

    /**
     * The Wi-Fi/cell position as one Clairix can stand behind, or null. Only a recent
     * one that agrees with the satellites qualifies: when the two disagree nothing here
     * knows which is wrong (a moved router, or train Wi-Fi mapped to the depot, fails
     * the comparison against perfectly real satellites), and a position from an hour
     * ago says nothing about now.
     */
    val vouchedNetwork: Pair<Double, Double>?
        get() = if (networkAgrees && networkLat != null && networkLon != null) networkLat to networkLon else null

    /**
     * The sentence of advice that goes with the verdict, with a leading space, or ""
     * when there is nothing to advise. It never points at the Wi-Fi/cell position when
     * that position is in dispute: a confidently wrong Wi-Fi fix looks the same from
     * the phone's side as a spoofed satellite position, and sending someone to the
     * network's idea of where they are would send them to the wrong place.
     */
    fun advice(): String = when (verdict) {
        GnssVerdict.SPOOFED -> when {
            mockDetected -> " An app on this phone is supplying positions. Find it under Developer options, " +
                "Select mock location app, and remove it; until then the map and every app's location are its invention."
            networkDisputed -> " Do not trust the position on the map or in any app until this clears. The Wi-Fi and " +
                "cell position disagrees with the satellites and Clairix cannot tell which of the two is wrong, so do " +
                "not navigate by either; go by road signs, or ask someone."
            vouchedNetwork != null -> " Do not trust the position on the map or in any app until this clears; Wi-Fi and cell towers are the better guide."
            else -> " Do not trust the position on the map or in any app until this clears."
        }
        GnssVerdict.JAMMED -> " Navigation and anything that tracks a vehicle by GPS will lose it here."
        GnssVerdict.SUSPECT -> when {
            networkDisputed -> " The satellite position and the Wi-Fi and cell position disagree. One of them is " +
                "wrong, and the other checks do not say which: a mis-mapped access point does this as readily as a spoofer."
            else -> ""
        }
        else -> ""
    }
}

/**
 * Judges whether the satellite signals the phone receives come from satellites.
 *
 * Every test here is one a cheap spoofer (a software-defined radio running gps-sdr-sim,
 * the kit that costs a few hundred dollars) fails, and the ones a careful one passes are
 * said to be weak where they are weak:
 *
 * - **Signal spread.** Real satellites sit at different heights in the sky, so their
 *   signals arrive 15 to 50 dB-Hz strong and differ by five or more. One transmitter on
 *   the ground sends them all at the same power: the spread collapses.
 * - **Signal strength.** From 20,000 km a satellite rarely exceeds 50 dB-Hz. Several
 *   above 55 at once is a transmitter nearby.
 * - **Time.** The satellites tell the receiver the time. A replayed recording, or a
 *   simulator started without the right date, is minutes to days off the phone's
 *   network clock.
 * - **Network agreement.** Wi-Fi and cell positions come from other radios entirely; a
 *   satellite position kilometres from them, again and again, is not where the phone is.
 * - **Jumps.** A position that moves faster than an airliner between two good fixes.
 * - **Mock locations.** An app supplying positions through Android's developer setting.
 * - **Clock and gain.** A receiver captured by a spoofer jumps its clock drift, and a loud
 *   transmitter on the band moves the front end's gain control. Corroboration only.
 * - **Constellations.** A phone that normally fixes on GPS and Galileo and BeiDou, now
 *   on GPS alone: the simple simulators generate GPS L1 and nothing else.
 * - **Jamming.** A good fix that vanishes at once, with the gain control showing energy
 *   on the band. Without the gain reading it is only "lost suddenly": a tunnel does that.
 *
 * Not deterministic proof, and not every phone reports raw measurements or gain. A
 * spoofer that simulates a full multi-constellation sky at realistic powers and the right
 * time, and moves its fake position slowly, passes the signal tests; the network
 * agreement test is the one it cannot pass while Wi-Fi or a cell tower is in range.
 *
 * **Corroboration.** Any one check can be wrong once: a Wi-Fi fix from a router that
 * moved house, or train Wi-Fi mapped to the depot, disagrees with perfectly real
 * satellites; a cold-start artefact can read as a jump. So the verdict turns SPOOFED
 * only when the evidence agrees with itself — two checks failing, a failing check with a
 * receiver-side check warning beside it (strength, constellations, clock, gain), a flat
 * spread that persists across [SPREAD_PERSIST_EPOCHS] consecutive epochs, a second
 * impossible jump, or the platform itself reporting mock locations. One failing check on
 * its own is SUSPECT. And the network position is only ever offered as the real one when
 * a recent one agrees with the satellites: when the two disagree, nothing here knows
 * which of them is wrong.
 *
 * Not thread safe; feed it from one thread. Times are in milliseconds, wall clock.
 */
class GnssIntegrity(private val wallClockTrusted: () -> Boolean = { true }) {

    companion object {
        const val SPREAD_FAIL_DB = 1.5
        const val SPREAD_WARN_DB = 2.5
        const val MIN_SATS_FOR_SPREAD = 6
        const val STRONG_CN0 = 55f
        const val MIN_STRONG = 4
        const val TIME_FAIL_MS = 60_000L
        const val TIME_WARN_MS = 3_000L
        /** Some chipsets report GPS time without leap seconds for a while after a cold start. */
        const val LEAP_SECONDS_MS = 18_000L
        const val NETWORK_MIN_DISAGREE_M = 1_000.0
        const val MAX_SPEED_MS = 400.0
        const val DRIFT_JUMP_NS_PER_S = 1_500.0
        const val AGC_DEVIATION_DB = 8.0
        const val WINDOW_EPOCHS = 5
        const val RECENT_MS = 10_000L
        /** Consecutive epochs of a flat spread that corroborate the spread check on their own. */
        const val SPREAD_PERSIST_EPOCHS = 20
        private const val EPOCH_KEEP_MS = 120_000L
        private const val MEMORY_MS = 10 * 60_000L
        private const val MULTI_MEMORY_MS = 30 * 60_000L
        private const val AGC_ALPHA = 0.02
        /** Gain samples that make up the warm-up; the alarm is not judged before then. */
        const val AGC_LEARNED = 30
        /**
         * Consecutive out-of-band gain samples after which the new level is taken as
         * this phone's normal. The jamming verdict needs the gain alarm within about a
         * minute of losing the fix, so ten minutes of steady "abnormal" is a changed
         * environment, not an attack worth a run-long warning.
         */
        const val AGC_RESEED_SAMPLES = 600
    }

    private val epochs = ArrayDeque<SkyEpoch>()
    private val fixes = ArrayDeque<GnssFix>()
    private var lastNetwork: NetworkFix? = null
    private var lastClock: GnssClockSample? = null

    private var networkStreak = 0
    private var lastComparison: Pair<Long, Pair<Double, Double>>? = null // time to (distance, limit)
    private var jump: Triple<Long, Double, Double>? = null // time, metres, seconds
    /** When each impossible jump inside [MEMORY_MS] happened; a second one corroborates the first. */
    private val jumpTimes = ArrayDeque<Long>()
    /** Consecutive epochs with enough satellites whose spread was below [SPREAD_FAIL_DB]. */
    private var spreadFlatEpochs = 0
    private var mockAt = 0L
    private var driftJumpAt = 0L
    private var clockSamples = 0
    private var agcBaseline: Double? = null
    private var agcSamples = 0
    private var agcDeviation: Double? = null
    private var agcStreak = 0
    /** The warm-up samples; the baseline is their median until there are [AGC_LEARNED]. */
    private val agcWarmup = ArrayList<Double>(AGC_LEARNED)
    private var multiConstellationAt = 0L
    private var healthyAt = 0L

    fun onSky(epoch: SkyEpoch) {
        epochs.addLast(epoch)
        while (epochs.isNotEmpty() && epoch.timeMs - epochs.first().timeMs > EPOCH_KEEP_MS) epochs.removeFirst()
        val used = epoch.sats.filter { it.used && it.constellation != Gnss.SBAS }
        if (used.map { it.constellation }.distinct().size >= 2) multiConstellationAt = epoch.timeMs
        if (used.size >= 5 && used.map { it.cn0 }.average() >= 30.0) healthyAt = epoch.timeMs
        // How long the spread has stayed flat, epoch after epoch. An epoch without
        // enough satellites to judge breaks the run: only an unbroken one corroborates.
        val judged = used.filter { it.cn0 > 0f }
        spreadFlatEpochs = if (judged.size >= MIN_SATS_FOR_SPREAD && std(judged.map { it.cn0.toDouble() }) < SPREAD_FAIL_DB)
            spreadFlatEpochs + 1 else 0
    }

    fun onGnssFix(fix: GnssFix) {
        if (fix.mock) mockAt = fix.timeMs
        val prev = fixes.lastOrNull()
        // The same fix delivered twice (directly and through the passive listener) is one fix.
        if (prev != null && prev.satelliteTimeMs == fix.satelliteTimeMs && prev.lat == fix.lat && prev.lon == fix.lon) return
        if (prev != null && !fix.mock && !prev.mock) {
            val dt = (fix.timeMs - prev.timeMs) / 1000.0
            val good = (fix.accuracyM ?: 999f) < 100f && (prev.accuracyM ?: 999f) < 100f
            if (dt >= 1.0 && good) {
                val d = metres(prev.lat, prev.lon, fix.lat, fix.lon)
                if (d / dt > MAX_SPEED_MS) {
                    jump = Triple(fix.timeMs, d, dt)
                    jumpTimes.addLast(fix.timeMs)
                }
            }
        }
        fixes.addLast(fix)
        while (fixes.size > 20) fixes.removeFirst()
        lastNetwork?.let { compare(fix, it) }
    }

    fun onNetworkFix(fix: NetworkFix) {
        val previous = lastNetwork
        lastNetwork = fix
        // Each network fix is compared once, with the satellite fix nearest in time.
        if (previous != null && previous.timeMs == fix.timeMs) return
        val gnss = fixes.lastOrNull { !it.mock && abs(it.timeMs - fix.timeMs) <= 60_000L } ?: return
        compare(gnss, fix)
    }

    fun onMockFix(timeMs: Long) { mockAt = timeMs }

    fun onClock(sample: GnssClockSample) {
        val prev = lastClock
        if (prev != null && prev.discontinuity == sample.discontinuity && sample.timeMs - prev.timeMs in 1..5_000) {
            val a = prev.driftNsPerS
            val b = sample.driftNsPerS
            if (a != null && b != null && abs(b - a) > DRIFT_JUMP_NS_PER_S) driftJumpAt = sample.timeMs
        }
        lastClock = sample
        clockSamples++
        val agc = sample.agcDb ?: return
        // Warm-up: the baseline is the median of the first AGC_LEARNED samples, so a
        // receiver still settling, or a first sample taken indoors, cannot set a level
        // every later sample then "deviates" from. The old code took the first sample
        // as the baseline and learned only from samples within 8 dB of it, so one off
        // first sample poisoned the whole run. The alarm is not judged until the
        // warm-up is over (see assess()).
        if (agcSamples < AGC_LEARNED) {
            agcWarmup += agc
            agcSamples = agcWarmup.size
            val base = median(agcWarmup)
            agcBaseline = base
            agcDeviation = agc - base
            agcStreak = 0
            return
        }
        // Always set by the warm-up above; clear() resets the two together.
        val base = agcBaseline ?: return
        val dev = agc - base
        agcDeviation = dev
        if (abs(dev) > AGC_DEVIATION_DB) {
            agcStreak++
            // Ten minutes of steady "abnormal" is this phone's new normal — a changed
            // environment, a different receiver mode — not an attack worth a warning
            // for the rest of the run. The jamming verdict only ever needed the alarm
            // in the first minute after a fix was lost.
            if (agcStreak >= AGC_RESEED_SAMPLES) {
                agcBaseline = agc
                agcDeviation = 0.0
                agcStreak = 0
            }
        } else {
            agcStreak = 0
            // Learn only from readings that look normal, so a long attack does not become the baseline.
            agcBaseline = base + AGC_ALPHA * (agc - base)
        }
    }

    private var comparedNetworkAt = 0L

    private fun compare(gnss: GnssFix, net: NetworkFix) {
        if (gnss.mock) return
        if (abs(gnss.timeMs - net.timeMs) > 60_000L) return
        if (net.timeMs == comparedNetworkAt) return
        comparedNetworkAt = net.timeMs
        val d = metres(gnss.lat, gnss.lon, net.lat, net.lon)
        val limit = maxOf(NETWORK_MIN_DISAGREE_M, 3.0 * ((gnss.accuracyM ?: 50f) + (net.accuracyM ?: 500f)))
        networkStreak = if (d > limit) networkStreak + 1 else 0
        lastComparison = maxOf(gnss.timeMs, net.timeMs) to (d to limit)
    }

    fun assess(now: Long): GnssAssessment {
        val recent = epochs.filter { now - it.timeMs <= RECENT_MS }
        val latest = epochs.lastOrNull()?.takeIf { now - it.timeMs <= 30_000L }
        val window = recent.takeLast(WINDOW_EPOCHS)
        val usedOf = { e: SkyEpoch -> e.sats.filter { it.used && it.constellation != Gnss.SBAS && it.cn0 > 0f } }

        val checks = ArrayList<GnssCheck>(10)

        // 1. Spread of signal strength across the satellites in the fix.
        val spreadEpochs = window.filter { usedOf(it).size >= MIN_SATS_FOR_SPREAD }
        val spreads = spreadEpochs.map { std(usedOf(it).map { s -> s.cn0.toDouble() }) }
        checks += when {
            spreadEpochs.size < WINDOW_EPOCHS -> GnssCheck("spread", "Signal spread", CheckState.NO_DATA,
                "Needs $MIN_SATS_FOR_SPREAD or more satellites in the fix for a few seconds.")
            spreads.max() < SPREAD_FAIL_DB -> GnssCheck("spread", "Signal spread", CheckState.FAIL,
                "Every satellite in the fix arrives within %.1f dB-Hz of the others. Satellites at different heights in the sky differ by five or more; one transmitter on the ground makes them all equal.".format(spreads.max()))
            spreads.max() < SPREAD_WARN_DB -> GnssCheck("spread", "Signal spread", CheckState.WARN,
                "The satellites in the fix are unusually even in strength (%.1f dB-Hz apart at most).".format(spreads.max()))
            else -> GnssCheck("spread", "Signal spread", CheckState.PASS,
                "Satellites differ in strength by %.1f dB-Hz, as real ones at different heights do.".format(spreads.average()))
        }

        // 2. Absolute strength.
        checks += when {
            window.isEmpty() -> GnssCheck("strength", "Signal strength", CheckState.NO_DATA, "No satellites heard in the last few seconds.")
            window.size >= WINDOW_EPOCHS && window.all { e -> usedOf(e).count { it.cn0 >= STRONG_CN0 } >= MIN_STRONG } ->
                GnssCheck("strength", "Signal strength", CheckState.WARN,
                    "${usedOf(window.last()).count { it.cn0 >= STRONG_CN0 }} satellites above ${STRONG_CN0.toInt()} dB-Hz. From orbit they rarely pass 50; this loud means a transmitter nearby.")
            else -> GnssCheck("strength", "Signal strength", CheckState.PASS, "Within what satellites in orbit deliver.")
        }

        // 3. Satellite time against the network clock.
        val lastFix = fixes.lastOrNull { !it.mock && now - it.timeMs <= 60_000L }
        val offset = lastFix?.satelliteTimeMs?.let { it - lastFix.timeMs }
        checks += when {
            !wallClockTrusted() -> GnssCheck("time", "Satellite time", CheckState.NO_DATA,
                "Automatic date and time is off, so the phone's clock cannot vouch for the satellites'.")
            offset == null -> GnssCheck("time", "Satellite time", CheckState.NO_DATA, "No satellite fix in the last minute.")
            abs(offset) > TIME_FAIL_MS -> GnssCheck("time", "Satellite time", CheckState.FAIL,
                "The satellites say it is ${describeOffset(offset)} the network's time. Real satellites carry atomic clocks; a replayed recording or a simulator started at the wrong date does not.")
            abs(offset) > TIME_WARN_MS && abs(abs(offset) - LEAP_SECONDS_MS) > 1_500L -> GnssCheck("time", "Satellite time", CheckState.WARN,
                "Satellite time is ${describeOffset(offset)} the network's.")
            else -> GnssCheck("time", "Satellite time", CheckState.PASS, "Agrees with the network clock to within a few seconds.")
        }

        // 4. Satellite position against the Wi-Fi/cell position.
        val cmp = lastComparison?.takeIf { now - it.first <= 5 * 60_000L }
        checks += when {
            cmp == null -> GnssCheck("network", "Wi-Fi and cell position", CheckState.NO_DATA,
                "No Wi-Fi or cell position to compare with in the last five minutes.")
            networkStreak >= 2 -> GnssCheck("network", "Wi-Fi and cell position", CheckState.FAIL,
                "The satellite position is ${km(cmp.second.first)} from where Wi-Fi and cell towers place the phone, again and again. A spoofer moves the first; a mis-mapped access point or a stale cell fix moves the second, so on its own this does not say which is wrong.")
            networkStreak == 1 -> GnssCheck("network", "Wi-Fi and cell position", CheckState.WARN,
                "The satellite position is ${km(cmp.second.first)} from the Wi-Fi and cell position. Checking again.")
            else -> GnssCheck("network", "Wi-Fi and cell position", CheckState.PASS,
                "Within ${cmp.second.first.toInt()} m of where Wi-Fi and cell towers place the phone.")
        }

        // 5. Impossible jumps.
        val j = jump?.takeIf { now - it.first <= MEMORY_MS }
        checks += when {
            j != null -> GnssCheck("jumps", "Position jumps", CheckState.FAIL,
                "The position moved ${km(j.second)} in ${j.third.toInt()} s (${(j.second / j.third * 3.6).toInt()} km/h) between two good fixes.")
            fixes.size >= 2 -> GnssCheck("jumps", "Position jumps", CheckState.PASS, "Every move between fixes is one a vehicle could make.")
            else -> GnssCheck("jumps", "Position jumps", CheckState.NO_DATA, "Needs two satellite fixes.")
        }

        // 6. Mock locations.
        checks += when {
            mockAt != 0L && now - mockAt <= MEMORY_MS -> GnssCheck("mock", "Mock locations", CheckState.FAIL,
                "An app on this phone is supplying fake positions through Android's mock-location setting (Developer options → Select mock location app).")
            fixes.isNotEmpty() -> GnssCheck("mock", "Mock locations", CheckState.PASS, "No app is supplying positions.")
            else -> GnssCheck("mock", "Mock locations", CheckState.NO_DATA, "No fix yet.")
        }

        // 7. Receiver clock.
        checks += when {
            clockSamples == 0 -> GnssCheck("clock", "Receiver clock", CheckState.NO_DATA,
                "This phone has not reported raw satellite measurements; many do only while a fix is running.")
            driftJumpAt != 0L && now - driftJumpAt <= MEMORY_MS -> GnssCheck("clock", "Receiver clock", CheckState.WARN,
                "The receiver's clock drift jumped without a reset, as it does when a stronger signal captures the receiver.")
            else -> GnssCheck("clock", "Receiver clock", CheckState.PASS, "The receiver's clock is drifting steadily.")
        }

        // 8. Front-end gain. Judged only once the warm-up is over: until then the
        // baseline is still being learned and a streak against it means nothing. The
        // alarm arm used to come before the "still learning" arm.
        val agcAlarm = agcSamples >= AGC_LEARNED && agcStreak >= WINDOW_EPOCHS
        checks += when {
            agcBaseline == null -> GnssCheck("agc", "Band energy", CheckState.NO_DATA, "This phone does not report its GPS gain control.")
            agcSamples < AGC_LEARNED -> GnssCheck("agc", "Band energy", CheckState.NO_DATA, "Learning this phone's normal gain level.")
            agcAlarm -> GnssCheck("agc", "Band energy", CheckState.WARN,
                "The GPS band's gain control has moved %.0f dB from normal: something loud is transmitting on it.".format(agcDeviation ?: 0.0))
            else -> GnssCheck("agc", "Band energy", CheckState.PASS, "No unusual energy on the GPS band.")
        }

        // 9. Constellations.
        val gpsOnly = window.size >= WINDOW_EPOCHS && window.all { e ->
            val u = usedOf(e)
            u.size >= MIN_SATS_FOR_SPREAD && u.all { it.constellation == Gnss.GPS }
        }
        checks += when {
            window.isEmpty() -> GnssCheck("constellations", "Constellations", CheckState.NO_DATA, "No satellites heard.")
            gpsOnly && multiConstellationAt != 0L && now - multiConstellationAt <= MULTI_MEMORY_MS ->
                GnssCheck("constellations", "Constellations", CheckState.WARN,
                    "The fix is on GPS alone, where minutes ago it used several systems. Simple simulators generate GPS and nothing else.")
            else -> GnssCheck("constellations", "Constellations", CheckState.PASS,
                usedOf(window.last()).map { Gnss.name(it.constellation) }.distinct().sorted().joinToString(", ").ifEmpty { "None in the fix" })
        }

        // 10. Jamming.
        val outage = recent.size >= 8 && recent.all { e -> e.sats.none { it.used } && (e.sats.maxOfOrNull { it.cn0 } ?: 0f) < 20f }
        val outageStart = recent.firstOrNull()?.timeMs ?: now
        val wasHealthy = healthyAt != 0L && outageStart - healthyAt in 0..70_000L
        checks += when {
            outage && wasHealthy && agcAlarm -> GnssCheck("jamming", "Jamming", CheckState.FAIL,
                "A good fix vanished at once and the gain control shows energy on the band: something is jamming GPS here.")
            outage && wasHealthy -> GnssCheck("jamming", "Jamming", CheckState.WARN,
                "A good fix vanished at once. A tunnel or a building does that; so does a jammer.")
            epochs.isEmpty() -> GnssCheck("jamming", "Jamming", CheckState.NO_DATA, "No satellites heard yet.")
            else -> GnssCheck("jamming", "Jamming", CheckState.PASS, "Satellites are being received.")
        }

        val byId = checks.associateBy { it.id }
        // Corroboration before SPOOFED (see the class comment). One failing check is
        // SUSPECT: a moved router or train Wi-Fi fails the network check against real
        // satellites, a wrong network clock fails the time check, and a cold-start
        // artefact can read as one jump. The evidence has to agree with itself: two
        // failing checks; a failing check plus a warning from one of the receiver-side
        // checks the class comment calls corroboration (too loud, GPS alone, clock
        // captured, gain moved), none of which a wrong Wi-Fi database entry or a wrong
        // network clock can produce; a flat spread that persists; a second impossible
        // jump; or Android's own word that an app is mocking the location. Persistence
        // only counts for the spread and jump checks, whose innocent failures are
        // momentary: a mis-mapped access point or a wrong clock fails for as long as it
        // lasts. An evenness warning on the spread is not corroboration either; a few
        // satellites through a window are often that even.
        val spoofFailing = listOf("spread", "time", "network", "jumps").filter { byId[it]?.state == CheckState.FAIL }
        val mockFail = byId["mock"]?.state == CheckState.FAIL
        val receiverWarn = listOf("strength", "constellations", "clock", "agc").any { byId[it]?.state == CheckState.WARN }
        while (jumpTimes.isNotEmpty() && now - jumpTimes.first() > MEMORY_MS) jumpTimes.removeFirst()
        val corroborated = mockFail || spoofFailing.size >= 2 ||
            (spoofFailing.isNotEmpty() && receiverWarn) ||
            ("spread" in spoofFailing && spreadFlatEpochs >= SPREAD_PERSIST_EPOCHS) ||
            ("jumps" in spoofFailing && jumpTimes.size >= 2)
        val spoofWarns = listOf("spread", "strength", "time", "network", "clock", "agc", "constellations")
            .count { byId[it]?.state == CheckState.WARN }
        val usedNow = latest?.let { usedOf(it) } ?: emptyList()
        val verdict = when {
            corroborated -> GnssVerdict.SPOOFED
            byId["jamming"]?.state == CheckState.FAIL -> GnssVerdict.JAMMED
            // A lone failing check stays on screen as unusual even once the sky has gone
            // quiet; it used to read as spoofed, and must not now read as "no data".
            spoofFailing.isNotEmpty() -> GnssVerdict.SUSPECT
            latest == null && lastFix == null -> GnssVerdict.NO_DATA
            spoofWarns >= 2 -> GnssVerdict.SUSPECT
            latest != null && latest.sats.none { it.used } -> GnssVerdict.NO_FIX
            latest == null -> GnssVerdict.NO_DATA
            else -> GnssVerdict.GENUINE
        }
        val networkState = byId["network"]?.state
        return GnssAssessment(
            verdict = verdict,
            checks = checks,
            usedCount = latest?.sats?.count { it.used } ?: 0,
            visibleCount = latest?.sats?.size ?: 0,
            constellationsUsed = usedNow.map { Gnss.name(it.constellation) }.distinct().sorted(),
            meanCn0 = usedNow.takeIf { it.isNotEmpty() }?.map { it.cn0 }?.average()?.toFloat(),
            spreadCn0 = usedNow.takeIf { it.size >= 2 }?.let { std(it.map { s -> s.cn0.toDouble() }).toFloat() },
            agcDeviationDb = agcDeviation,
            timeOffsetMs = offset,
            lastEpochMs = latest?.timeMs ?: 0L,
            sats = latest?.sats?.sortedByDescending { it.cn0 } ?: emptyList(),
            networkLat = lastNetwork?.lat,
            networkLon = lastNetwork?.lon,
            networkAgrees = networkState == CheckState.PASS,
            networkDisputed = networkState == CheckState.FAIL || networkState == CheckState.WARN,
            mockDetected = mockFail
        )
    }

    fun clear() {
        epochs.clear(); fixes.clear(); lastNetwork = null; lastClock = null
        networkStreak = 0; lastComparison = null; jump = null; jumpTimes.clear(); spreadFlatEpochs = 0
        mockAt = 0L; driftJumpAt = 0L
        clockSamples = 0; agcBaseline = null; agcSamples = 0; agcDeviation = null; agcStreak = 0; agcWarmup.clear()
        multiConstellationAt = 0L; healthyAt = 0L; comparedNetworkAt = 0L
    }

    private fun std(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    /** Of a non-empty list. */
    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        haversine(Fix(lat1, lon1, null, 0L), Fix(lat2, lon2, null, 0L))

    private fun km(m: Double): String = if (m >= 1_000) "%.1f km".format(m / 1_000) else "${m.toInt()} m"

    private fun describeOffset(ms: Long): String {
        val a = abs(ms)
        val amount = when {
            a >= 86_400_000L -> "%.1f days".format(a / 86_400_000.0)
            a >= 3_600_000L -> "%.1f hours".format(a / 3_600_000.0)
            a >= 60_000L -> "${a / 60_000L} min"
            else -> "%.1f s".format(a / 1000.0)
        }
        return if (ms > 0) "$amount ahead of" else "$amount behind"
    }
}
