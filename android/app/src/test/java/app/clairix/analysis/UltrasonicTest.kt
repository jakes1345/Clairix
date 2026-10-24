package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Beacon detection against synthetic audio: room noise, a speech-band tone, a click, a beacon, a keyed beacon. */
class UltrasonicTest {

    private val rate = 48_000
    private val rnd = Random(7)

    /** One frame of quiet room noise, plus whatever tones are asked for. */
    private fun frame(vararg tones: Pair<Double, Double>, click: Boolean = false): ShortArray {
        val out = ShortArray(Ultrasonic.FRAME)
        for (i in out.indices) {
            var v = rnd.nextDouble(-1.0, 1.0) * 300.0
            for ((f, amp) in tones) v += sin(2 * PI * f * i / rate) * amp
            if (click && i in 2000..2003) v += 25_000.0
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun track(frames: List<ShortArray>): List<Ultrasonic.Beacon> {
        val t = Ultrasonic.Tracker()
        frames.forEachIndexed { i, f -> t.add(i * 85L, Ultrasonic.analyze(f, rate)) }
        return t.beacons()
    }

    @Test
    fun fftFindsASineInItsBin() {
        val db = Ultrasonic.spectrumDb(frame(19_000.0 to 4_000.0))
        val bin = (19_000.0 / (rate.toDouble() / Ultrasonic.FRAME)).toInt()
        val peak = (bin - 3..bin + 3).maxBy { db[it] }
        assertTrue("peak at bin $peak, expected about $bin", abs(peak - bin) <= 1)
    }

    @Test
    fun aSteadyToneIsABeacon() {
        val beacons = track(List(40) { frame(18_750.0 to 1_500.0) })
        assertEquals(beacons.toString(), 1, beacons.size)
        assertEquals(18_750.0, beacons[0].freqHz, 60.0)
        assertTrue(!beacons[0].modulated)
    }

    @Test
    fun aKeyedBeaconHoppingBetweenTonesIsModulated() {
        val tones = listOf(18_200.0, 18_600.0, 19_000.0, 19_400.0)
        val beacons = track(List(48) { i -> frame(tones[(i / 2) % tones.size] to 1_500.0) })
        assertTrue(beacons.toString(), beacons.any { it.modulated && it.lowHz <= 18_250.0 && it.highHz >= 19_350.0 })
    }

    @Test
    fun roomNoiseSpeechAndClicksAreNotBeacons() {
        assertTrue(track(List(40) { frame() }).isEmpty())
        assertTrue(track(List(40) { frame(1_000.0 to 8_000.0, 3_000.0 to 5_000.0) }).isEmpty())
        assertTrue(track(List(40) { i -> frame(click = i % 3 == 0) }).isEmpty())
    }

    @Test
    fun aFewFramesAreNeverEnough() {
        assertTrue(track(List(10) { frame(19_500.0 to 2_000.0) }).isEmpty())
    }

    @Test
    fun theTestToneIsHeardAsATone() {
        // Through a speaker and a microphone there is always a noise floor; without one the
        // window's own side lobes would light the whole band.
        val pcm = Ultrasonic.tone(19_000.0, rate, 1.0).map { (it + rnd.nextInt(-300, 300)).coerceIn(-32768, 32767).toShort() }.toShortArray()
        val r = Ultrasonic.analyze(pcm, rate, offset = rate / 2)
        assertTrue(r.peaks.toString(), r.peaks.any { abs(it.freqHz - 19_000.0) < 30 })
    }
}
