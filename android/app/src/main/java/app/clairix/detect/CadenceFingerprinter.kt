package app.clairix.detect

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Follows a device across Bluetooth address rotations by the rhythm of its radio.
 *
 * The identity resolver already links rotations when the advertisement *content*
 * matches and exactly one candidate fits. A tracker that rotates its payload along
 * with its address — an AirTag's whole key changes every fifteen minutes — leaves
 * only the type and length to match on, and in a car park with three AirTags that is
 * no match at all. What does not change is the firmware: how often it advertises,
 * how tightly it holds that interval, what transmit power and PHY it uses, how its
 * manufacturer data is laid out. Those together are a fingerprint of the *device*,
 * not of the packet.
 *
 * Per address this keeps the last twenty inter-packet intervals and the fixed radio
 * features. When a new address appears, every address that has gone silent in the
 * last thirty minutes is scored against it; a single clear winner at or above
 * [threshold] means the new address is the same device, and the two share one
 * logical id. Ambiguity — two silent candidates scoring nearly alike — stitches
 * nothing, on the same principle as the identity resolver: a wrong merge is worse
 * than a missed one.
 *
 * Two rules keep a family's shared traits from being mistaken for one device's.
 * The new address must have *measured* its own interval: a family's nominal
 * cadence describes every AirTag, so on its own it identifies none (two AirTags of
 * one firmware used to score about 0.9 on the prior alone and be stitched, the
 * second inheriting the first's history and its "following" verdict). And when
 * both sides belong to the same family and every confirming feature is equal —
 * which for two units of one product is the normal case — those features carry no
 * information, so the decision rests on the measured cadence of both sides and on
 * the hand-over itself: the old address must have gone quiet shortly before the
 * new one appeared, as it does in a rotation.
 *
 * Timing comes from [ScanResult.getTimestampNanos], which is the controller's
 * receive time, so a busy main thread does not smear the intervals.
 */
class CadenceFingerprinter(
    /** A candidate must have been quiet at least this long: still advertising means a different device. */
    private val silentMinMs: Long = 4_000L,
    /** And no longer than this — the window within which a rotation is looked for. */
    private val silentMaxMs: Long = 30 * 60_000L,
    /** Minimum match score to stitch a new address onto an existing logical device. */
    private val threshold: Double = 0.75,
    /** Two candidates closer than this are indistinguishable, and nothing is stitched. */
    private val ambiguityMargin: Double = 0.05
) {

    /**
     * A tracker family with a known advertising cadence. Used two ways: as a prior
     * for the interval of an address heard too few times to measure its own, and as a
     * hard gate — two addresses that resolve to different families are never one
     * device, whatever else agrees.
     */
    data class Family(
        val id: String,
        val label: String,
        val intervalMs: Int,
        val toleranceMs: Int,
        val company: Int? = null,
        /** First byte of the manufacturer payload (the Apple type byte), when fixed. */
        val mfrType: Int? = null,
        /** Second byte of the manufacturer payload (Apple's length byte), when fixed. */
        val mfrLen: Int? = null,
        val serviceShorts: Set<Int> = emptySet()
    )

    /** Snapshot of what is known about one address, for display and diagnostics. */
    data class Profile(
        val address: String,
        val logicalId: String,
        val stitched: Boolean,
        val family: String?,
        val intervalMeanMs: Double?,
        val intervalStdMs: Double?,
        val samples: Int,
        val txPower: Int?,
        val primaryPhy: Int,
        val advertisingSid: Int,
        val company: Int?,
        val payloadLength: Int
    )

    /**
     * The outcome of a stitch: the logical id the address belongs to, and whether the
     * evidence was strong enough to treat the two histories as one without reserve
     * (see [Match.certain]).
     */
    data class Match(
        val logicalId: String,
        /**
         * Both sides measured their own cadence, the quiet address over at least
         * [CERTAIN_SAMPLES] intervals; the pair was not two units of one tracker
         * family that only the cadence tells apart; the score cleared
         * [CERTAIN_SCORE]; and no other candidate reached the threshold. Callers may
         * then merge histories wholesale; otherwise they should not let the merge
         * extend how long the device appears to have been present.
         */
        val certain: Boolean
    )

    /**
     * What one packet tells the matcher, independent of [ScanResult] so the matching
     * can run, and be tested, without the Bluetooth stack.
     */
    internal class Packet(
        val address: String,
        /** Controller receive time, nanoseconds (see [ScanResult.getTimestampNanos]). */
        val timestampNanos: Long,
        /** Wall-clock time the packet was handled. */
        val nowMs: Long,
        val rssi: Int,
        val primaryPhy: Int,
        val advertisingSid: Int,
        /** False when the scan carried no advertisement record; the fields below are then ignored. */
        val hasRecord: Boolean,
        val txPower: Int?,
        val company: Int?,
        val mfrPayload: ByteArray?,
        val services: Set<Int>,
        val payloadLength: Int
    )

    private class Track(val address: String, val firstSeenMs: Long) {
        val deltas = LongArray(RING)
        var count = 0
        var head = 0
        var lastTsNanos = 0L
        var lastSeenMs = 0L
        var lastRssi = 0
        var txPower: Int? = null
        var primaryPhy = 0
        var advertisingSid = 0xFF
        var company: Int? = null
        var mfrType: Int? = null
        var mfrLen: Int? = null
        var mfrPayload = 0
        var payloadLength = 0
        var services: Set<Int> = emptySet()

        /** The id the rest of the app knows this device by. Starts as the address until assigned. */
        var logicalId: String = address
        var stitched = false
        /** Set once a later address has been stitched onto this one; it then stops being a candidate. */
        var consumed = false
        /** The id this address carried before a late stitch moved it; handed over once. */
        var supersededId: String? = null
        var attempts = 0
        /** Whether the stitch that gave this address its id was [Match.certain]. */
        var stitchCertain = false

        fun push(deltaMs: Long) {
            deltas[head] = deltaMs
            head = (head + 1) % RING
            if (count < RING) count++
        }

        /** Number of usable intervals behind [interval]. */
        fun samples(): Int = (0 until count).count { deltas[it] > 0 }

        /**
         * Mean and standard deviation of the advertising interval. Missed packets
         * show up as deltas of two or three times the base interval, so each delta is
         * first folded back by the whole number of intervals it spans, using the
         * median as the base. A device heard twice has one delta and no spread; two
         * deltas are the minimum for a measurement anyone should trust.
         */
        fun interval(): Pair<Double, Double>? {
            if (count < 2) return null
            val raw = LongArray(count) { deltas[it] }.filter { it > 0 }.sorted()
            if (raw.size < 2) return null
            val median = raw[raw.size / 2].toDouble()
            if (median <= 0) return null
            val folded = raw.map { d ->
                val n = max(1, (d / median).roundToInt())
                d.toDouble() / n
            }
            val mean = folded.sum() / folded.size
            val variance = folded.sumOf { (it - mean) * (it - mean) } / folded.size
            return mean to sqrt(variance)
        }
    }

    private val tracks = HashMap<String, Track>()
    private var lastPruneMs = 0L

    /**
     * Records [scan] and answers which logical device its address belongs to.
     *
     * Returns the logical id when the address has been stitched onto a device seen
     * under another address, on this call or an earlier one; null when the address
     * stands on its own. A null answer is the caller's cue to give the address an id
     * of its choosing through [assign], so a later stitch can hand that same id on.
     */
    @Synchronized
    fun stitch(scan: ScanResult): Match? {
        val address = scan.device?.address ?: return null
        return stitch(packetOf(address, scan, System.currentTimeMillis()))
    }

    /** [stitch] for an already-decoded packet. */
    @Synchronized
    internal fun stitch(packet: Packet): Match? {
        val address = BleNames.formatMac(packet.address)
        val nowMs = packet.nowMs
        if (nowMs - lastPruneMs > PRUNE_EVERY_MS || tracks.size > MAX_TRACKS) prune(nowMs)

        val existing = tracks[address]
        val track = existing ?: Track(address, nowMs).also { tracks[address] = it }
        record(track, packet)

        if (track.stitched) return Match(track.logicalId, track.stitchCertain)

        // A fresh address is scored only once it has measured its own cadence; until
        // then it has nothing that is its own to compare. Packets before that point
        // are not attempts. After it, it is re-tried on the next few packets, until
        // it matches or is clearly its own device.
        if (nowMs - track.firstSeenMs > ATTEMPT_WINDOW_MS) return null
        if (track.interval() == null) return null
        if (track.attempts >= MAX_ATTEMPTS) return null
        track.attempts++

        val best = bestCandidate(track, nowMs) ?: return null
        best.candidate.consumed = true
        if (track.logicalId != track.address) track.supersededId = track.logicalId
        track.logicalId = best.candidate.logicalId
        track.stitched = true
        track.stitchCertain = best.certain
        return Match(track.logicalId, track.stitchCertain)
    }

    /**
     * Names an unstitched address. Called by the owner of the id space after
     * [stitch] returned null, so that when this address later turns out to be the
     * predecessor of a rotated one, the rotated address inherits the right id.
     */
    @Synchronized
    fun assign(address: String, logicalId: String) {
        val track = tracks[BleNames.formatMac(address)] ?: return
        if (!track.stitched) track.logicalId = logicalId
    }

    /**
     * If [address] was first given one id through [assign] and then stitched onto a
     * different device by a later packet, the id it gave up — once. The caller merges
     * whatever it recorded under the old id into the new one.
     */
    @Synchronized
    fun supersededKey(address: String): String? {
        val track = tracks[BleNames.formatMac(address)] ?: return null
        val old = track.supersededId ?: return null
        track.supersededId = null
        return if (old == track.logicalId) null else old
    }

    /** The tracker family the address's radio behaviour matches, for display. */
    @Synchronized
    fun familyLabel(address: String): String? =
        tracks[BleNames.formatMac(address)]?.let { familyOf(it)?.label }

    @Synchronized
    fun profile(address: String): Profile? {
        val t = tracks[BleNames.formatMac(address)] ?: return null
        val interval = t.interval()
        return Profile(
            address = t.address, logicalId = t.logicalId, stitched = t.stitched,
            family = familyOf(t)?.label,
            intervalMeanMs = interval?.first, intervalStdMs = interval?.second,
            samples = t.count, txPower = t.txPower, primaryPhy = t.primaryPhy,
            advertisingSid = t.advertisingSid, company = t.company, payloadLength = t.payloadLength
        )
    }

    @Synchronized
    fun clear() {
        tracks.clear()
    }

    // ── Recording ────────────────────────────────────────────────────────────

    private fun packetOf(address: String, scan: ScanResult, nowMs: Long): Packet {
        val record = scan.scanRecord
        val mfr = record?.manufacturerSpecificData?.takeIf { it.size() > 0 }
        val shorts = HashSet<Int>()
        record?.serviceUuids?.forEach { shortUuid(it.uuid.toString())?.let(shorts::add) }
        record?.serviceData?.keys?.forEach { shortUuid(it.uuid.toString())?.let(shorts::add) }
        return Packet(
            address = address,
            timestampNanos = scan.timestampNanos,
            nowMs = nowMs,
            rssi = scan.rssi,
            primaryPhy = scan.primaryPhy,
            advertisingSid = scan.advertisingSid,
            hasRecord = record != null,
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            company = mfr?.keyAt(0),
            mfrPayload = mfr?.valueAt(0),
            services = shorts,
            payloadLength = record?.let(::advertisedLength) ?: 0
        )
    }

    private fun record(track: Track, packet: Packet) {
        val ts = packet.timestampNanos
        if (track.lastTsNanos != 0L && ts > track.lastTsNanos) {
            val deltaMs = (ts - track.lastTsNanos) / 1_000_000L
            // Below 20 ms it is the same advertising event heard on another channel,
            // or a scan response; above the window it is a device coming back, not a cadence.
            if (deltaMs in 20..silentMaxMs) track.push(deltaMs)
        }
        track.lastTsNanos = ts
        track.lastSeenMs = packet.nowMs
        track.lastRssi = packet.rssi
        track.primaryPhy = packet.primaryPhy
        track.advertisingSid = packet.advertisingSid

        if (!packet.hasRecord) return
        packet.txPower?.let { track.txPower = it }
        val company = packet.company
        if (company != null) {
            val payload = packet.mfrPayload
            track.company = company
            track.mfrPayload = payload?.size ?: 0
            track.mfrType = payload?.getOrNull(0)?.toInt()?.and(0xFF)
            track.mfrLen = payload?.getOrNull(1)?.toInt()?.and(0xFF)
        }
        if (packet.services.isNotEmpty()) track.services = packet.services
        track.payloadLength = packet.payloadLength
    }

    /**
     * The bytes the device actually sent. [ScanRecord.getBytes] pads a legacy
     * advertisement out to 62 bytes, so the AD structures are walked instead and the
     * zero-length terminator ends the count.
     */
    private fun advertisedLength(record: ScanRecord): Int {
        val bytes = record.bytes ?: return 0
        var i = 0
        var total = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0) break
            total += len + 1
            i += len + 1
        }
        return total
    }

    private fun shortUuid(uuid: String): Int? {
        val lower = uuid.lowercase()
        if (lower.length != 36 || !lower.startsWith("0000") || !lower.endsWith(BASE_UUID_TAIL)) return null
        return lower.substring(4, 8).toIntOrNull(16)
    }

    // ── Matching ─────────────────────────────────────────────────────────────

    private class Best(val candidate: Track, val certain: Boolean)

    /** A score and the facts about the comparison that decide whether a stitch on it is certain. */
    private class Score(val value: Double, val bothMeasured: Boolean, val indistinguishable: Boolean) {
        companion object {
            val NONE = Score(0.0, bothMeasured = false, indistinguishable = false)
        }
    }

    private fun bestCandidate(fresh: Track, nowMs: Long): Best? {
        var best: Track? = null
        var bestScore = Score.NONE
        var second = 0.0
        for (candidate in tracks.values) {
            if (candidate === fresh || candidate.consumed) continue
            if (candidate.logicalId == fresh.logicalId) continue
            val quiet = nowMs - candidate.lastSeenMs
            if (quiet < silentMinMs || quiet > silentMaxMs) continue
            val s = score(fresh, candidate)
            if (s.value > bestScore.value) {
                second = bestScore.value
                bestScore = s
                best = candidate
            } else if (s.value > second) {
                second = s.value
            }
        }
        if (best == null || bestScore.value < threshold) return null
        if (bestScore.value - second < ambiguityMargin) return null
        val certain = bestScore.bothMeasured && !bestScore.indistinguishable &&
            best.samples() >= CERTAIN_SAMPLES &&
            bestScore.value >= CERTAIN_SCORE && second < threshold
        return Best(best, certain)
    }

    /**
     * How alike two addresses' radios are, 0..1. [fresh] is the address just
     * heard, [candidate] one that has gone quiet.
     *
     * Interval is the heaviest term, because it is the one thing a rotating tracker
     * cannot hide. The fresh side must have measured its own; the candidate may fall
     * back to its family's prior when it was heard too briefly to measure. Without
     * either the score is zero. The others are cheap confirmations: transmit power
     * as advertised, PHY and advertising set, the shape of the manufacturer data,
     * and the total advertised length.
     *
     * When both sides are of the same family and those confirmations are all equal,
     * they are what every unit of the product shares and confirm nothing. Then both
     * intervals must be measured, the hand-over must look like a rotation, and the
     * score is the interval agreement alone.
     */
    private fun score(fresh: Track, candidate: Track): Score {
        val fa = familyOf(fresh)
        val fb = familyOf(candidate)
        if (fa != null && fb != null && fa !== fb) return Score.NONE
        if (fresh.company != null && candidate.company != null && fresh.company != candidate.company) return Score.NONE

        // Heard at the same time means two radios; a rotation never overlaps.
        val gapMs = fresh.firstSeenMs - candidate.lastSeenMs
        if (gapMs < -OVERLAP_SLACK_MS) return Score.NONE

        val measuredFresh = fresh.interval() ?: return Score.NONE
        val measuredCandidate = candidate.interval()
        val (meanA, stdA) = measuredFresh
        val (meanB, stdB) = measuredCandidate
            ?: fb?.let { it.intervalMs.toDouble() to it.toleranceMs.toDouble() }
            ?: return Score.NONE
        val bothMeasured = measuredCandidate != null
        val spread = max(max(stdA, stdB), MIN_SPREAD_MS)
        val meanScore = (1.0 - abs(meanA - meanB) / (2 * spread + MIN_SPREAD_MS)).coerceIn(0.0, 1.0)
        val stdScore = (1.0 - abs(stdA - stdB) / spread).coerceIn(0.0, 1.0)
        val interval = 0.75 * meanScore + 0.25 * stdScore
        // A rotation does not teleport the device: a 20 dB jump is a different place.
        val rssiFactor = if (abs(fresh.lastRssi - candidate.lastRssi) > 20) 0.85 else 1.0

        val indistinguishable = fa != null && fa === fb &&
            fresh.txPower == candidate.txPower &&
            fresh.mfrType == candidate.mfrType && fresh.mfrLen == candidate.mfrLen &&
            fresh.mfrPayload == candidate.mfrPayload && fresh.services == candidate.services &&
            fresh.payloadLength == candidate.payloadLength
        if (indistinguishable) {
            if (!bothMeasured) return Score.NONE
            if (gapMs > HANDOVER_MAX_MS) return Score.NONE
            return Score(interval * rssiFactor, bothMeasured = true, indistinguishable = true)
        }

        val txA = fresh.txPower
        val txB = candidate.txPower
        val tx = when {
            txA != null && txB != null -> when (abs(txA - txB)) { 0 -> 1.0; 1, 2 -> 0.5; else -> 0.0 }
            txA == null && txB == null -> 0.6
            else -> 0.2
        }

        val phy = (if (fresh.primaryPhy == candidate.primaryPhy) 0.6 else 0.0) +
            (if (fresh.advertisingSid == candidate.advertisingSid) 0.4 else 0.0)

        val mfr = when {
            fresh.company != null && candidate.company != null -> when {
                fresh.mfrType == candidate.mfrType && fresh.mfrLen == candidate.mfrLen &&
                    fresh.mfrPayload == candidate.mfrPayload -> 1.0
                fresh.mfrType == candidate.mfrType -> 0.5
                else -> 0.2
            }
            fresh.company == null && candidate.company == null -> if (fresh.services == candidate.services) 1.0 else 0.0
            else -> 0.0
        }

        val length = when (abs(fresh.payloadLength - candidate.payloadLength)) { 0 -> 1.0; 1, 2 -> 0.5; else -> 0.0 }

        var s = 0.40 * interval + 0.15 * tx + 0.10 * phy + 0.25 * mfr + 0.10 * length
        if (fa != null && fa === fb) s = min(1.0, s + 0.05)
        return Score(s * rssiFactor, bothMeasured, indistinguishable = false)
    }

    private fun familyOf(t: Track): Family? = FAMILIES.firstOrNull { f ->
        when {
            f.company != null -> t.company == f.company &&
                (f.mfrType == null || t.mfrType == f.mfrType) &&
                (f.mfrLen == null || t.mfrLen == f.mfrLen)
            f.serviceShorts.isNotEmpty() -> t.services.any { it in f.serviceShorts }
            else -> false
        }
    }

    private fun prune(nowMs: Long) {
        lastPruneMs = nowMs
        val cutoff = nowMs - silentMaxMs
        tracks.values.removeAll { it.lastSeenMs < cutoff }
        if (tracks.size > MAX_TRACKS) {
            tracks.values.sortedBy { it.lastSeenMs }.take(tracks.size - MAX_TRACKS).forEach { tracks.remove(it.address) }
        }
    }

    companion object {
        private const val RING = 20
        private const val MAX_TRACKS = 4000
        private const val PRUNE_EVERY_MS = 60_000L
        /** How many packets a new address is tested against the silent set before being left alone. */
        private const val MAX_ATTEMPTS = 8
        private const val ATTEMPT_WINDOW_MS = 90_000L
        /** Nothing is steadier than this; keeps the interval term from dividing by a near-zero spread. */
        private const val MIN_SPREAD_MS = 40.0
        /** Wall-clock slack when deciding two addresses were heard at the same time. */
        private const val OVERLAP_SLACK_MS = 2_000L
        /**
         * Between two addresses nothing but cadence tells apart, the longest quiet
         * gap that still reads as one device rotating. A rotation is immediate; this
         * allows for duty-cycled scanning and missed packets, not for another unit of
         * the same product wandering into range a quarter of an hour later.
         */
        private const val HANDOVER_MAX_MS = 120_000L
        /** Intervals the quiet address must have measured before a stitch on it can be [Match.certain]. */
        private const val CERTAIN_SAMPLES = 6
        /** Score a stitch must reach to be [Match.certain]. */
        private const val CERTAIN_SCORE = 0.90
        private const val BASE_UUID_TAIL = "-0000-1000-8000-00805f9b34fb"

        /**
         * Measured advertising cadences. AirTag: Find My payload, type 0x12, length
         * byte 0x19, every two seconds. Tile: service 0xFEED at 1.5 s with a wide
         * spread between generations. SmartTag: Samsung manufacturer data at 1 s.
         * Chipolo: 400 ms, distinctive on its own.
         */
        val FAMILIES: List<Family> = listOf(
            Family("airtag", "Apple AirTag", 2000, 200, company = 0x004C, mfrType = 0x12, mfrLen = 0x19),
            Family("tile", "Tile", 1500, 500, serviceShorts = setOf(0xFEED, 0xFD84)),
            Family("smarttag", "Samsung SmartTag", 1000, 150, company = 0x0075),
            Family("chipolo", "Chipolo", 400, 100, serviceShorts = setOf(0xFEBE, 0xFE2B, 0xFE33))
        )
    }
}
