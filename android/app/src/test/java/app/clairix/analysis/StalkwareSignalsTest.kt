package app.clairix.analysis

import android.Manifest
import app.clairix.Severity
import app.clairix.analysis.StalkwareScanner.AppFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stalkerware score's weaker signals and what qualifies them: the battery-optimisation
 * exemption, which only strengthens an app already listed. (StalkwareScannerTest has the rest.)
 */
class StalkwareSignalsTest {
    private val rogue = "com.example.keylogger"
    private val play = "com.android.vending"

    private fun score(f: AppFacts): Int = StalkwareScanner.assess(f).sumOf { it.points }

    private fun severity(f: AppFacts): Severity = StalkwareScanner.SuspiciousApp(
        f.name, f.name, score(f), emptyList(), 0, 0, f.sideloaded, f.hasLauncher, false, false, false,
        f.imeEnabled, f.deviceAdminActive, f.installer,
    ).severity

    // ── Battery exemption ─────────────────────────────────────────────────────

    private val exemptReason = "is exempt from battery optimisation, so Android never pauses it in the background"

    @Test
    fun aBatteryExemptionAloneIsNeverAVerdict() {
        // A store messenger or alarm clock: exempt and nothing else.
        val plain = AppFacts("com.example.alarm", system = false, installer = play, hasLauncher = true, batteryExempt = true)
        assertTrue(StalkwareScanner.assess(plain).isEmpty())
        // Sideloaded and exempt: still below MEDIUM, and the exemption adds nothing.
        val sideloaded = plain.copy(installer = "com.android.packageinstaller")
        assertEquals(StalkwareScanner.SIDELOAD_SCORE, score(sideloaded))
        assertFalse(exemptReason in StalkwareScanner.assess(sideloaded).map { it.reason })
        // Hidden and exempt: the icon counts, the exemption does not.
        assertEquals(StalkwareScanner.NO_LAUNCHER_SCORE, score(plain.copy(hasLauncher = false)))
    }

    @Test
    fun aBatteryExemptionStrengthensAListedAppThatHidesOrCameFromOutsideAStore() {
        val hiddenSideload = AppFacts(rogue, system = false, installer = null, hasLauncher = false)
        assertEquals(Severity.MEDIUM, severity(hiddenSideload))
        val exempt = hiddenSideload.copy(batteryExempt = true)
        assertEquals(score(hiddenSideload) + StalkwareScanner.BATTERY_EXEMPT_SCORE, score(exempt))
        assertEquals(Severity.HIGH, severity(exempt))
        assertTrue(exemptReason in StalkwareScanner.assess(exempt).map { it.reason })
    }

    @Test
    fun aBatteryExemptionNeedsASignalOfTheMonitoringKind() {
        // A store app listed for its permissions alone: SMS and call log, nothing hidden or granted.
        val store = AppFacts(
            "com.example.sms", system = false, installer = play, hasLauncher = true,
            requested = setOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG), batteryExempt = true,
        )
        assertTrue(score(store) >= 3)
        assertFalse(exemptReason in StalkwareScanner.assess(store).map { it.reason })
        // The same app with an accessibility service nobody vouches for: now the exemption counts.
        val withGrant = store.copy(accessibilityEnabled = true)
        assertTrue(exemptReason in StalkwareScanner.assess(withGrant).map { it.reason })
        // A known accessibility app from a store is vouched for, so it does not.
        val talkback = AppFacts(
            "com.google.android.marvin.talkback", system = false, installer = play, hasLauncher = true,
            requested = setOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG),
            accessibilityEnabled = true, batteryExempt = true,
        )
        assertFalse(exemptReason in StalkwareScanner.assess(talkback).map { it.reason })
    }

    @Test
    fun theBatterySignalIsJudgedOnTheOtherSignalsAlone() {
        val f = AppFacts(rogue, system = false, installer = null, hasLauncher = true, batteryExempt = true)
        assertNull(StalkwareScanner.batterySignal(f, listOf(StalkwareScanner.Signal(2, "x")), hidden = false))
        assertEquals(
            StalkwareScanner.BATTERY_EXEMPT_SCORE,
            StalkwareScanner.batterySignal(f, listOf(StalkwareScanner.Signal(3, "x")), hidden = false)?.points,
        )
        assertNull(StalkwareScanner.batterySignal(f.copy(batteryExempt = false), listOf(StalkwareScanner.Signal(5, "x")), hidden = true))
    }
}
