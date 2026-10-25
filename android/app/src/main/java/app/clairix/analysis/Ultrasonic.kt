package app.clairix.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Finds ultrasonic beacons in microphone audio: the near-inaudible tones, 17.5 to 21.5
 * kHz, that ad-tracking kits (SilverPush, LISNR, Shopkick and their kind) play from TV
 * ads, shop speakers and web pages so an app on the phone can hear them and report
 * "this device was here, in front of that". Pure functions over sample buffers; the
 * recording lives in the tool page, and nothing but these spectrum numbers is kept.
 */
object Ultrasonic {

    const val BAND_LOW_HZ = 17_500.0
    const val BAND_HIGH_HZ = 21_500.0
    const val FRAME = 4096
    /** A peak must stand this far above the band's median to count. */
    const val PEAK_SNR_DB = 15.0
    /** …and this far above its own neighbourhood, so a slope is not a tone. */
    const val PEAK_PROMINENCE_DB = 6.0
    /** A frame where this share of the band is lit is a broadband sound (keys, a clap), not a beacon. */
    const val BROADBAND_FRACTION = 0.2
    const val BUCKET_HZ = 50.0

    data class Peak(val freqHz: Double, val snrDb: Double)

    data class FrameResult(val peaks: List<Peak>, val broadband: Boolean, val floorDb: Double)

    /** In-place iterative radix-2 FFT. [re] and [im] must be the same power-of-two length. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Power spectrum in dB of one Hann-windowed frame of 16-bit PCM. Length [FRAME] / 2. */
    fun spectrumDb(samples: ShortArray, offset: Int = 0): DoubleArray {
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        for (i in 0 until FRAME) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (FRAME - 1))
            re[i] = (samples.getOrElse(offset + i) { 0 } / 32768.0) * w
        }
        fft(re, im)
        return DoubleArray(FRAME / 2) { k -> 10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-20) }
    }

    /** The narrow peaks in the beacon band of one frame. */
    fun analyze(samples: ShortArray, sampleRate: Int, offset: Int = 0): FrameResult {
        val db = spectrumDb(samples, offset)
        val binHz = sampleRate.toDouble() / FRAME
        val lo = (BAND_LOW_HZ / binHz).roundToInt()
        val hi = min((min(BAND_HIGH_HZ, sampleRate / 2.0 - 500) / binHz).roundToInt(), db.size - 4)
        if (hi - lo < 16) return FrameResult(emptyList(), false, 0.0)
        val band = (lo..hi).map { db[it] }.sorted()
        val floor = band[band.size / 2]
        val lit = (lo..hi).count { db[it] > floor + 10 }
        val broadband = lit > BROADBAND_FRACTION * (hi - lo + 1)
        if (broadband) return FrameResult(emptyList(), true, floor)
        val peaks = ArrayList<Peak>()
        for (k in lo..hi) {
            val p = db[k]
            if (p < floor + PEAK_SNR_DB) continue
            if (p < db[k - 1] || p < db[k + 1]) continue
            val shoulders = max(db[k - 3], db[k + 3])
            if (p - shoulders < PEAK_PROMINENCE_DB) continue
            // Parabolic interpolation puts the peak between bins.
            val a = db[k - 1]; val b = p; val c = db[k + 1]
            val d = (a - c) / (2 * (a - 2 * b + c)).let { if (it == 0.0) 1e-9 else it }
            peaks += Peak((k + d.coerceIn(-0.5, 0.5)) * binHz, p - floor)
        }
        return FrameResult(peaks.sortedByDescending { it.snrDb }.take(6), false, floor)
    }

    /** One beacon the tracker has seen enough of to name. */
    data class Beacon(
        val freqHz: Double,
        /** For a modulated beacon, the lowest and highest tone it hopped between. */
        val lowHz: Double,
        val highHz: Double,
        val snrDb: Double,
        /** Share of the window's frames it was present in. */
        val presence: Double,
        val modulated: Boolean
    )

    /**
     * Holds the last few seconds of frames and names what persists: a steady tone in a
     * third or more of them, or several tones within a kilohertz and a half that between
     * them fill two fifths, which is what a beacon carrying data (frequency-shift keyed)
     * looks like. A single loud frame is never a beacon.
     */
    class Tracker(private val windowMs: Long = 4_000L, private val minFrames: Int = 20) {
        private val frames = ArrayDeque<Pair<Long, FrameResult>>()

        fun add(timeMs: Long, frame: FrameResult) {
            frames.addLast(timeMs to frame)
            while (frames.isNotEmpty() && timeMs - frames.first().first > windowMs) frames.removeFirst()
        }

        val frameCount: Int get() = frames.size
        val broadbandShare: Double get() = if (frames.isEmpty()) 0.0 else frames.count { it.second.broadband }.toDouble() / frames.size

        fun beacons(): List<Beacon> {
            if (frames.size < minFrames) return emptyList()
            val n = frames.size.toDouble()
            // Frames in which each 50 Hz bucket (± one bucket) appears, and the SNRs seen there.
            val presence = HashMap<Int, MutableSet<Int>>()
            val snr = HashMap<Int, MutableList<Double>>()
            frames.forEachIndexed { i, (_, f) ->
                for (p in f.peaks) {
                    val b = (p.freqHz / BUCKET_HZ).roundToInt()
                    presence.getOrPut(b) { HashSet() }.add(i)
                    snr.getOrPut(b) { ArrayList() }.add(p.snrDb)
                }
            }
            fun near(b: Int): Set<Int> = (b - 1..b + 1).flatMap { presence[it] ?: emptySet() }.toSet()
            fun snrNear(b: Int): Double = (b - 1..b + 1).flatMap { snr[it] ?: emptyList() }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

            val out = ArrayList<Beacon>()
            val claimed = HashSet<Int>()
            // Steady tones.
            for (b in presence.keys.sortedByDescending { near(it).size }) {
                if (b in claimed) continue
                val share = near(b).size / n
                if (share >= 0.3 && snrNear(b) >= PEAK_SNR_DB) {
                    val f = b * BUCKET_HZ
                    out += Beacon(f, f, f, snrNear(b), share, modulated = false)
                    for (x in b - 2..b + 2) claimed += x
                }
            }
            // Modulated: three or more tones within 1.5 kHz, each in a tenth of the frames, together in two fifths.
            val candidates = presence.keys.filter { it !in claimed && (presence[it]?.size ?: 0) / n >= 0.1 }.sorted()
            var i = 0
            while (i < candidates.size) {
                val group = candidates.filter { it >= candidates[i] && (it - candidates[i]) * BUCKET_HZ <= 1_500.0 }
                val distinct = group.fold(ArrayList<Int>()) { acc, b -> if (acc.isEmpty() || b - acc.last() >= 2) acc.add(b); acc }
                val covered = group.flatMap { presence[it] ?: emptySet() }.toSet().size / n
                if (distinct.size >= 3 && covered >= 0.4) {
                    val lowHz = group.first() * BUCKET_HZ
                    val highHz = group.last() * BUCKET_HZ
                    val s = group.flatMap { snr[it] ?: emptyList() }.sorted()
                    out += Beacon((lowHz + highHz) / 2, lowHz, highHz, s[s.size / 2], covered, modulated = true)
                    i += group.size
                } else i++
            }
            return out
        }

        fun clear() = frames.clear()
    }

    /** A test tone: [seconds] of [freqHz] at [amplitude] (0..1) as 16-bit PCM, with 20 ms fades so it does not click. */
    fun tone(freqHz: Double, sampleRate: Int, seconds: Double, amplitude: Double = 0.5): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val fade = (sampleRate * 0.02).toInt()
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * freqHz * i / sampleRate) * amplitude * env * 32767).toInt().toShort()
        }
    }
}
