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
    /** The latest Wi-Fi/cell position, which is the one to trust when the verdict is SPOOFED. */
    val networkLat: Double? = null,
    val networkLon: Double? = null
) {
    val failing: List<GnssCheck> get() = checks.filter { it.state == CheckState.FAIL }
    val warning: List<GnssCheck> get() = checks.filter { it.state == CheckState.WARN }
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
        private const val EPOCH_KEEP_MS = 120_000L
        private const val MEMORY_MS = 10 * 60_000L
        private const val MULTI_MEMORY_MS = 30 * 60_000L
        private const val AGC_ALPHA = 0.02
        private const val AGC_LEARNED = 30
    }

    private val epochs = ArrayDeque<SkyEpoch>()
    private val fixes = ArrayDeque<GnssFix>()
    private var lastNetwork: NetworkFix? = null
    private var lastClock: GnssClockSample? = null

    private var networkStreak = 0
    private var lastComparison: Pair<Long, Pair<Double, Double>>? = null // time to (distance, limit)
    private var jump: Triple<Long, Double, Double>? = null // time, metres, seconds
    private var mockAt = 0L
    private var driftJumpAt = 0L
    private var clockSamples = 0
    private var agcBaseline: Double? = null
    private var agcSamples = 0
    private var agcDeviation: Double? = null
    private var agcStreak = 0
    private var multiConstellationAt = 0L
    private var healthyAt = 0L

    fun onSky(epoch: SkyEpoch) {
        epochs.addLast(epoch)
        while (epochs.isNotEmpty() && epoch.timeMs - epochs.first().timeMs > EPOCH_KEEP_MS) epochs.removeFirst()
        val used = epoch.sats.filter { it.used && it.constellation != Gnss.SBAS }
        if (used.map { it.constellation }.distinct().size >= 2) multiConstellationAt = epoch.timeMs
        if (used.size >= 5 && used.map { it.cn0 }.average() >= 30.0) healthyAt = epoch.timeMs
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
                if (d / dt > MAX_SPEED_MS) jump = Triple(fix.timeMs, d, dt)
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
        val base = agcBaseline
        if (base == null) {
            agcBaseline = agc
            agcSamples = 1
            agcDeviation = 0.0
            return
        }
        val dev = agc - base
        agcDeviation = dev
        if (abs(dev) > AGC_DEVIATION_DB) {
            agcStreak++
        } else {
            agcStreak = 0
            // Learn only from readings that look normal, so a long attack does not become the baseline.
            agcBaseline = base + AGC_ALPHA * (agc - base)
            agcSamples++
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
                "The satellite position is ${km(cmp.second.first)} from where Wi-Fi and cell towers place the phone, again and again. A spoofer moves the first and cannot move the second.")
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

        // 8. Front-end gain.
        val agcAlarm = agcStreak >= WINDOW_EPOCHS
        checks += when {
            agcBaseline == null -> GnssCheck("agc", "Band energy", CheckState.NO_DATA, "This phone does not report its GPS gain control.")
            agcAlarm -> GnssCheck("agc", "Band energy", CheckState.WARN,
                "The GPS band's gain control has moved %.0f dB from normal: something loud is transmitting on it.".format(agcDeviation ?: 0.0))
            agcSamples < AGC_LEARNED -> GnssCheck("agc", "Band energy", CheckState.NO_DATA, "Learning this phone's normal gain level.")
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
        val spoofFail = listOf("spread", "time", "network", "jumps", "mock").any { byId[it]?.state == CheckState.FAIL }
        val spoofWarns = listOf("spread", "strength", "time", "network", "clock", "agc", "constellations")
            .count { byId[it]?.state == CheckState.WARN }
        val usedNow = latest?.let { usedOf(it) } ?: emptyList()
        val verdict = when {
            spoofFail -> GnssVerdict.SPOOFED
            byId["jamming"]?.state == CheckState.FAIL -> GnssVerdict.JAMMED
            latest == null && lastFix == null -> GnssVerdict.NO_DATA
            spoofWarns >= 2 -> GnssVerdict.SUSPECT
            latest != null && latest.sats.none { it.used } -> GnssVerdict.NO_FIX
            latest == null -> GnssVerdict.NO_DATA
            else -> GnssVerdict.GENUINE
        }
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
            networkLon = lastNetwork?.lon
        )
    }

    fun clear() {
        epochs.clear(); fixes.clear(); lastNetwork = null; lastClock = null
        networkStreak = 0; lastComparison = null; jump = null; mockAt = 0L; driftJumpAt = 0L
        clockSamples = 0; agcBaseline = null; agcSamples = 0; agcDeviation = null; agcStreak = 0
        multiConstellationAt = 0L; healthyAt = 0L; comparedNetworkAt = 0L
    }

    private fun std(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
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
