package app.clairix.analysis

import org.junit.Assert.assertEquals
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
    fun aPositionFarFromWifiAndCellTwiceRunningIsSpoofed() {
        val g = GnssIntegrity()
        feed(g, 6, ::realSky)
        // Satellites say 20 km north; Wi-Fi and cell say home.
        g.onGnssFix(fix(now, lat = home.first + 0.18))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        assertEquals(CheckState.WARN, state(g.assess(now), "network"))
        now += 30_000L
        g.onGnssFix(fix(now, lat = home.first + 0.18))
        g.onNetworkFix(NetworkFix(now, home.first, home.second, 30f))
        val a = g.assess(now)
        assertEquals(CheckState.FAIL, state(a, "network"))
        assertEquals(GnssVerdict.SPOOFED, a.verdict)
        assertEquals(home.first, a.networkLat!!, 1e-9)
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
