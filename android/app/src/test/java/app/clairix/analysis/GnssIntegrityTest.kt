package app.clairix.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spoofing and jamming tests against skies built to pass or fail each one. */
class GnssIntegrityTest {

    private var now = 1_700_000_000_000L
    private val home = 40.0 to -75.0

    /** A real sky: three constellations, strengths spread the way elevation spreads them. */
    private fun realSky(t: Long) = SkyEpoch(t, listOf(
        GnssSat(Gnss.GPS, 3, 44f, true), GnssSat(Gnss.GPS, 7, 31f, true), GnssSat(Gnss.GPS, 12, 38f, true),
        GnssSat(Gnss.GPS, 19, 26f, true), GnssSat(Gnss.GALILEO, 4, 41f, true), GnssSat(Gnss.GALILEO, 11, 29f, true),
        GnssSat(Gnss.BEIDOU, 21, 35f, true), GnssSat(Gnss.GLONASS, 9, 22f, false), GnssSat(Gnss.SBAS, 131, 37f, true)
    ))

    /** gps-sdr-sim from one antenna: GPS only, every satellite at the same strength. */
    private fun fakeSky(t: Long, cn0: Float = 47f) = SkyEpoch(t, (1..8).map { GnssSat(Gnss.GPS, it, cn0 + (it % 2) * 0.4f, true) })

    private fun feed(g: GnssIntegrity, seconds: Int, sky: (Long) -> SkyEpoch) {
        repeat(seconds) { g.onSky(sky(now)); now += 1_000L }
    }

    private fun fix(t: Long, lat: Double = home.first, lon: Double = home.second, satTime: Long? = t, mock: Boolean = false) =
        GnssFix(t, lat, lon, 8f, satTime, mock)

    private fun state(a: GnssAssessment, id: String) = a.checks.first { it.id == id }.state

    @Test
    fun aRealSkyIsGenuine() {
        val g = GnssIntegrity()
        feed(g, 10, ::realSky)
        g.onGnssFix(fix(now))
        g.onNetworkFix(NetworkFix(now, home.first + 0.001, home.second, 40f))
        val a = g.assess(now)
        assertEquals(a.checks.toString(), GnssVerdict.GENUINE, a.verdict)
        assertEquals(CheckState.PASS, state(a, "spread"))
        assertEquals(CheckState.PASS, state(a, "network"))
        assertEquals(CheckState.PASS, state(a, "time"))
        assertEquals(7, a.usedCount - 1) // the SBAS satellite is in the fix but not counted as a constellation
        assertEquals(listOf("BeiDou", "GPS", "Galileo"), a.constellationsUsed)
    }

    @Test
    fun oneTransmitterCollapsesTheSpreadAndIsSpoofed() {
        val g = GnssIntegrity()
        feed(g, 30, ::realSky)
        feed(g, 6) { fakeSky(it) }
        val a = g.assess(now)
        assertEquals(CheckState.FAIL, state(a, "spread"))
        assertEquals(CheckState.WARN, state(a, "constellations"))
        assertEquals(GnssVerdict.SPOOFED, a.verdict)
    }

    @Test
    fun loudEvenSignalsWarnTwiceAndAreSuspectWithoutAFail() {
        val g = GnssIntegrity()
        // Even but not flat (about 2 dB-Hz spread), and loud.
        feed(g, 6) { t -> SkyEpoch(t, (1..8).map { GnssSat(Gnss.GPS, it, 56f + (it % 3) * 2f, true) }) }
        val a = g.assess(now)
        assertEquals(CheckState.WARN, state(a, "spread"))
        assertEquals(CheckState.WARN, state(a, "strength"))
        assertEquals(GnssVerdict.SUSPECT, a.verdict)
    }

    @Test
    fun satelliteTimeHoursOffIsSpoofedButTheLeapSecondQuirkIsNot() {
        val g = GnssIntegrity()
        feed(g, 6, ::realSky)
        g.onGnssFix(fix(now, satTime = now - 3 * 3_600_000L))
        assertEquals(CheckState.FAIL, state(g.assess(now), "time"))

        val quirk = GnssIntegrity()
        feed(quirk, 6, ::realSky)
        quirk.onGnssFix(fix(now, satTime = now - 18_000L))
        assertEquals(CheckState.PASS, state(quirk.assess(now), "time"))

        val untrusted = GnssIntegrity(wallClockTrusted = { false })
        feed(untrusted, 6, ::realSky)
        untrusted.onGnssFix(fix(now, satTime = now - 3 * 3_600_000L))
        assertEquals(CheckState.NO_DATA, state(untrusted.assess(now), "time"))
    }

    @Test
    fun aPositionFarFromWifiAndCellTwiceRunningIsUnusualNotSpoofed() {
        val g = GnssIntegrity()
        feed(g, 6, ::realSky)
        // Satellites say 20 km north; Wi-Fi and cell say home. On a sky that passes every
        // other check that is as likely a mis-mapped access point as a spoofer (it used
        // to read as spoofed, with "trust the Wi-Fi/cell position"), so it is unusual and
        // neither position is vouched for.
        g.onGnssFix(fix(now, lat = home.first + 0.18))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        assertEquals(CheckState.WARN, state(g.assess(now), "network"))
        now += 30_000L
        g.onGnssFix(fix(now, lat = home.first + 0.18))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        val a = g.assess(now)
        assertEquals(CheckState.FAIL, state(a, "network"))
        assertEquals(GnssVerdict.SUSPECT, a.verdict)
        assertEquals(home.first, a.networkLat!!, 1e-9)
        assertTrue(a.networkDisputed)
        assertNull(a.vouchedNetwork)
        assertFalse(a.advice(), a.advice().contains("better guide"))
    }

    @Test
    fun aDisagreementBackedByASecondFailingCheckIsSpoofedButVouchesForNeither() {
        val g = GnssIntegrity()
        feed(g, 6, ::realSky)
        // Twenty km from the Wi-Fi/cell position twice, and the satellites' clock three
        // hours out: two independent checks agree. Which position is right is still not
        // known, so the advice points at neither.
        val off = 3 * 3_600_000L
        g.onGnssFix(fix(now, lat = home.first + 0.18, satTime = now - off))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        now += 30_000L
        g.onGnssFix(fix(now, lat = home.first + 0.18, satTime = now - off))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        val a = g.assess(now)
        assertEquals(CheckState.FAIL, state(a, "time"))
        assertEquals(CheckState.FAIL, state(a, "network"))
        assertEquals(GnssVerdict.SPOOFED, a.verdict)
        assertNull(a.vouchedNetwork)
        assertTrue(a.advice(), a.advice().contains("do not navigate by either"))
    }

    @Test
    fun aSpoofWithTheNetworkInAgreementVouchesForTheNetworkPosition() {
        val g = GnssIntegrity()
        feed(g, 30, ::realSky)
        feed(g, 6) { fakeSky(it) }
        g.onGnssFix(fix(now))
        g.onNetworkFix(NetworkFix(now, home.first + 0.001, home.second, 40f))
        val a = g.assess(now)
        assertEquals(GnssVerdict.SPOOFED, a.verdict)
        assertEquals(home.first + 0.001, a.vouchedNetwork!!.first, 1e-9)
        assertTrue(a.advice(), a.advice().contains("better guide"))
    }

    /** Indoors by a window: every satellite arrives by one reflection and, for a moment, all alike. */
    private fun flatSky(t: Long) = SkyEpoch(t, listOf(
        GnssSat(Gnss.GPS, 3, 33.4f, true), GnssSat(Gnss.GPS, 7, 32.9f, true), GnssSat(Gnss.GPS, 12, 33.8f, true),
        GnssSat(Gnss.GALILEO, 4, 33.1f, true), GnssSat(Gnss.GALILEO, 11, 32.6f, true), GnssSat(Gnss.BEIDOU, 21, 33.5f, true)
    ))

    @Test
    fun aMomentOfEvenSignalsIndoorsIsUnusualAndOnlyAPersistentOneIsSpoofed() {
        val g = GnssIntegrity()
        feed(g, 30, ::realSky)
        feed(g, 6, ::flatSky)
        val brief = g.assess(now)
        assertEquals(CheckState.FAIL, state(brief, "spread"))
        assertEquals(CheckState.PASS, state(brief, "constellations"))
        assertEquals(GnssVerdict.SUSPECT, brief.verdict)
        // The same flat spread epoch after epoch for half a minute is a transmitter.
        feed(g, 24, ::flatSky)
        assertEquals(GnssVerdict.SPOOFED, g.assess(now).verdict)
    }

    /** A GPS repeater indoors: the rooftop antenna's sky, amplified, with its spread intact. */
    private fun repeaterSky(t: Long) = SkyEpoch(t, listOf(
        GnssSat(Gnss.GPS, 3, 58f, true), GnssSat(Gnss.GPS, 7, 49f, true), GnssSat(Gnss.GPS, 12, 56f, true),
        GnssSat(Gnss.GPS, 19, 45f, true), GnssSat(Gnss.GALILEO, 4, 57f, true), GnssSat(Gnss.GALILEO, 11, 47f, true),
        GnssSat(Gnss.BEIDOU, 21, 55f, true)
    ))

    @Test
    fun aRepeaterIndoorsIsLoudButNotSpoofed() {
        val g = GnssIntegrity()
        feed(g, 10, ::repeaterSky)
        g.onGnssFix(fix(now))
        g.onNetworkFix(NetworkFix(now, home.first + 0.001, home.second, 40f))
        val a = g.assess(now)
        assertEquals(CheckState.WARN, state(a, "strength"))
        assertEquals(CheckState.PASS, state(a, "spread"))
        assertEquals(GnssVerdict.GENUINE, a.verdict)
    }

    @Test
    fun oneImpossibleJumpIsUnusualAndASecondIsSpoofed() {
        // A cold start or a multipath fix can put one position kilometres out; a spoofer
        // dragging the position does it again.
        val g = GnssIntegrity()
        feed(g, 6, ::realSky)
        g.onGnssFix(fix(now)); now += 2_000L
        g.onGnssFix(fix(now, lat = home.first + 0.02)) // 2.2 km in 2 s
        val once = g.assess(now)
        assertEquals(CheckState.FAIL, state(once, "jumps"))
        assertEquals(GnssVerdict.SUSPECT, once.verdict)
        now += 60_000L
        g.onGnssFix(fix(now, lat = home.first + 0.02)); now += 2_000L
        g.onGnssFix(fix(now, lat = home.first + 0.05)) // 3.3 km in 2 s
        assertEquals(GnssVerdict.SPOOFED, g.assess(now).verdict)
    }

    @Test
    fun aMockLocationAppGetsItsOwnAdvice() {
        val g = GnssIntegrity()
        g.onGnssFix(fix(now, mock = true))
        val a = g.assess(now)
        assertTrue(a.mockDetected)
        assertTrue(a.advice(), a.advice().contains("Select mock location app"))
    }

    @Test
    fun aBadFirstGainSampleDoesNotPoisonTheRun() {
        // The receiver's first gain reading, taken while it settles, is 12 dB off the
        // level it then holds. It used to become the baseline every later reading
        // "deviated" from, so Band energy warned for the rest of the run.
        val g = GnssIntegrity()
        g.onClock(GnssClockSample(now, 1, 100.0, 30.0)); g.onSky(realSky(now)); now += 1_000L
        repeat(10) { g.onClock(GnssClockSample(now, 1, 100.0, 42.0)); g.onSky(realSky(now)); now += 1_000L }
        // Still learning: not judged, however far off the first sample was.
        assertEquals(CheckState.NO_DATA, state(g.assess(now), "agc"))
        repeat(60) { g.onClock(GnssClockSample(now, 1, 100.0, 42.0)); g.onSky(realSky(now)); now += 1_000L }
        val a = g.assess(now)
        assertEquals(CheckState.PASS, state(a, "agc"))
        assertEquals(GnssVerdict.GENUINE, a.verdict)
        // Real energy on the band after the warm-up still shows.
        repeat(6) { g.onClock(GnssClockSample(now, 1, 100.0, 30.0)); g.onSky(realSky(now)); now += 1_000L }
        assertEquals(CheckState.WARN, state(g.assess(now), "agc"))
    }

    @Test
    fun aLastingChangeInGainLevelBecomesTheNewNormal() {
        val g = GnssIntegrity()
        repeat(40) { g.onClock(GnssClockSample(now, 1, 100.0, 42.0)); now += 1_000L }
        repeat(GnssIntegrity.AGC_RESEED_SAMPLES) { g.onClock(GnssClockSample(now, 1, 100.0, 31.0)); now += 1_000L }
        assertEquals(CheckState.PASS, state(g.assess(now), "agc"))
    }

    @Test
    fun aJumpFasterThanAnAirlinerIsCaught() {
        val g = GnssIntegrity()
        g.onGnssFix(fix(now))
        now += 5_000L
        g.onGnssFix(fix(now, lat = home.first + 0.1)) // 11 km in 5 s
        assertEquals(CheckState.FAIL, state(g.assess(now), "jumps"))
        val driving = GnssIntegrity()
        driving.onGnssFix(fix(now))
        now += 5_000L
        driving.onGnssFix(fix(now, lat = home.first + 0.0013)) // 145 m in 5 s, 104 km/h
        assertEquals(CheckState.PASS, state(driving.assess(now), "jumps"))
    }

    @Test
    fun aMockLocationAppIsSpoofing() {
        val g = GnssIntegrity()
        g.onGnssFix(fix(now, mock = true))
        val a = g.assess(now)
        assertEquals(CheckState.FAIL, state(a, "mock"))
        assertEquals(GnssVerdict.SPOOFED, a.verdict)
    }

    @Test
    fun aDriftJumpWithoutAResetWarnsAndAResetDoesNot() {
        val g = GnssIntegrity()
        g.onClock(GnssClockSample(now, 3, 120.0, null))
        g.onClock(GnssClockSample(now + 1_000, 3, 140.0, null))
        assertEquals(CheckState.PASS, state(g.assess(now + 1_000), "clock"))
        g.onClock(GnssClockSample(now + 2_000, 3, 4_000.0, null))
        assertEquals(CheckState.WARN, state(g.assess(now + 2_000), "clock"))

        val reset = GnssIntegrity()
        reset.onClock(GnssClockSample(now, 3, 120.0, null))
        reset.onClock(GnssClockSample(now + 1_000, 4, 4_000.0, null))
        assertEquals(CheckState.PASS, state(reset.assess(now + 1_000), "clock"))
    }

    @Test
    fun aVanishingFixWithEnergyOnTheBandIsJammingAndWithoutItIsOnlyLost() {
        val jammed = GnssIntegrity()
        repeat(40) { jammed.onClock(GnssClockSample(now, 1, 100.0, 40.0)); jammed.onSky(realSky(now)); now += 1_000L }
        repeat(10) {
            jammed.onClock(GnssClockSample(now, 1, 100.0, 22.0)) // gain control pulled down 18 dB
            jammed.onSky(SkyEpoch(now, listOf(GnssSat(Gnss.GPS, 3, 12f, false), GnssSat(Gnss.GPS, 7, 9f, false))))
            now += 1_000L
        }
        val a = jammed.assess(now)
        assertEquals(CheckState.FAIL, state(a, "jamming"))
        assertEquals(GnssVerdict.JAMMED, a.verdict)

        val tunnel = GnssIntegrity()
        feed(tunnel, 20, ::realSky)
        feed(tunnel, 10) { t -> SkyEpoch(t, listOf(GnssSat(Gnss.GPS, 3, 11f, false))) }
        val b = tunnel.assess(now)
        assertEquals(CheckState.WARN, state(b, "jamming"))
        assertEquals(GnssVerdict.NO_FIX, b.verdict)
    }

    @Test
    fun noDataSaysSoInsteadOfClaimingClean() {
        val a = GnssIntegrity().assess(now)
        assertEquals(GnssVerdict.NO_DATA, a.verdict)
        assertTrue(a.checks.all { it.state == CheckState.NO_DATA })
    }
}
