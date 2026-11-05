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
 * exemption, which only strengthens an app already listed, and permissions granted rather than
 * only asked for. (StalkwareScannerTest has the rest.)
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
    fun aBatteryExemptionStrengthensAListedAppThatHides() {
        val hiddenSideload = AppFacts(rogue, system = false, installer = null, hasLauncher = false)
        assertEquals(Severity.MEDIUM, severity(hiddenSideload))
        val exempt = hiddenSideload.copy(batteryExempt = true)
        assertEquals(score(hiddenSideload) + StalkwareScanner.BATTERY_EXEMPT_SCORE, score(exempt))
        assertEquals(Severity.HIGH, severity(exempt))
        assertTrue(exemptReason in StalkwareScanner.assess(exempt).map { it.reason })
    }

    @Test
    fun aMessengerFromItsMakersSiteStaysMediumWhenExempt() {
        // A messenger from its maker's site: an APK install, the microphone and the camera granted,
        // and the exemption asked for to stay connected without Google's push. Medium, never high.
        val perms = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val messenger = AppFacts(
            "com.example.messenger", system = false, installer = "com.google.android.packageinstaller", hasLauncher = true,
            requested = perms, granted = perms, batteryExempt = true,
        )
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + 2, score(messenger))
        assertEquals(Severity.MEDIUM, severity(messenger))
        assertFalse(exemptReason in StalkwareScanner.assess(messenger).map { it.reason })
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
        assertNull(StalkwareScanner.batterySignal(f, listOf(StalkwareScanner.Signal(2, "x")), hidden = true))
        assertEquals(
            StalkwareScanner.BATTERY_EXEMPT_SCORE,
            StalkwareScanner.batterySignal(f, listOf(StalkwareScanner.Signal(3, "x")), hidden = true)?.points,
        )
        // Listed, but only for coming from outside a store: not of the monitoring kind on its own.
        assertNull(StalkwareScanner.batterySignal(f, listOf(StalkwareScanner.Signal(3, "x")), hidden = false))
        assertNull(StalkwareScanner.batterySignal(f.copy(batteryExempt = false), listOf(StalkwareScanner.Signal(5, "x")), hidden = true))
    }

    // ── Steady uploads ────────────────────────────────────────────────────────

    private val uploads = DataUsage.Summary(List(DataUsage.DAYS) { DataUsage.Day(100_000L, 2_000_000L) })
    private val uploadReason = "keeps sending more than it receives"

    private fun uploadSignal(f: AppFacts) = StalkwareScanner.assess(f).firstOrNull { it.reason.startsWith(uploadReason) }

    @Test
    fun steadyUploadsAloneAreNeverAVerdict() {
        // A hidden, sideloaded backup tool that does nothing else: uploads do not list it.
        val sideloaded = AppFacts("com.example.backup", system = false, installer = "com.android.packageinstaller", hasLauncher = true, dataUsage = uploads)
        assertEquals(StalkwareScanner.SIDELOAD_SCORE, score(sideloaded))
        assertNull(uploadSignal(sideloaded))
        // A store app that uploads (a photo library) never gets the signal, listed or not.
        val store = AppFacts(
            "com.example.photos", system = false, installer = play, hasLauncher = true, dataUsage = uploads,
            requested = setOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG),
        )
        assertTrue(score(store) >= 3)
        assertNull(uploadSignal(store))
    }

    @Test
    fun steadyUploadsStrengthenAListedAppThatHides() {
        val hiddenSideload = AppFacts(rogue, system = false, installer = null, hasLauncher = false)
        assertEquals(Severity.MEDIUM, severity(hiddenSideload))
        val uploading = hiddenSideload.copy(dataUsage = uploads)
        assertEquals(score(hiddenSideload) + StalkwareScanner.STEADY_UPLOAD_SCORE, score(uploading))
        assertEquals(Severity.HIGH, severity(uploading))
        assertTrue(uploadSignal(uploading)!!.reason.contains("sending on 7 of them"))
        // Downloading more than it uploads: nothing.
        val viewer = hiddenSideload.copy(dataUsage = DataUsage.Summary(List(DataUsage.DAYS) { DataUsage.Day(9_000_000L, 2_000_000L) }))
        assertNull(uploadSignal(viewer))
    }

    @Test
    fun aMessengerFromItsMakersSiteThatSendsALotStaysMedium() {
        // Its owner sends videos all week: the reason is shown, but it does not make the app HIGH.
        val perms = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val messenger = AppFacts(
            "com.example.messenger", system = false, installer = "com.google.android.packageinstaller", hasLauncher = true,
            requested = perms, granted = perms, batteryExempt = true, dataUsage = uploads,
        )
        assertEquals(Severity.MEDIUM, severity(messenger))
        assertEquals(0, uploadSignal(messenger)?.points)
        // Listed at 3, it adds its point and stays MEDIUM.
        val lesser = messenger.copy(requested = setOf(Manifest.permission.RECORD_AUDIO), granted = setOf(Manifest.permission.RECORD_AUDIO))
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + 1, score(lesser.copy(dataUsage = null)))
        assertEquals(1, uploadSignal(lesser)?.points)
        assertEquals(Severity.MEDIUM, severity(lesser))
    }

    @Test
    fun theUploadSignalIsJudgedOnTheOtherSignalsAlone() {
        val f = AppFacts(rogue, system = false, installer = null, hasLauncher = true, dataUsage = uploads)
        assertNull(StalkwareScanner.dataSignal(f, listOf(StalkwareScanner.Signal(2, "x")), hidden = true))
        assertEquals(1, StalkwareScanner.dataSignal(f, listOf(StalkwareScanner.Signal(3, "x")), hidden = true)?.points)
        assertNull(StalkwareScanner.dataSignal(f.copy(dataUsage = null), listOf(StalkwareScanner.Signal(5, "x")), hidden = true))
    }

    // ── Granted or only asked for ─────────────────────────────────────────────

    private val sms = setOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO)

    @Test
    fun aGrantedPermissionWeighsMoreThanOneOnlyAskedFor() {
        val asks = AppFacts(rogue, system = false, installer = play, hasLauncher = true, requested = sms, granted = emptySet())
        val holds = asks.copy(granted = sms)
        assertTrue(score(holds) > score(asks))
        // READ_SMS 2, READ_CALL_LOG 2, microphone 1 granted; 1, 1, 0 only asked for.
        assertEquals(5, score(holds))
        assertEquals(2, score(asks))
        val reasons = StalkwareScanner.assess(asks).map { it.reason }
        assertTrue(reasons.toString(), "asks to read SMS, not granted" in reasons)
        assertTrue(reasons.toString(), "asks for the microphone, not granted" in reasons)
        assertTrue("can read SMS (including one-time codes) (granted)" in StalkwareScanner.assess(holds).map { it.reason })
    }

    @Test
    fun withoutTheGrantFlagsEveryRequestedPermissionIsWeighedAsGranted() {
        val unknown = AppFacts(rogue, system = false, installer = play, hasLauncher = true, requested = sms)
        assertEquals(5, score(unknown))
        assertTrue("can read SMS (including one-time codes)" in StalkwareScanner.assess(unknown).map { it.reason })
    }

    @Test
    fun drawingOverOtherAppsIsNotARuntimeGrantSoItsFlagIsIgnored() {
        val overlay = AppFacts(
            rogue, system = false, installer = play, hasLauncher = true,
            requested = setOf(Manifest.permission.SYSTEM_ALERT_WINDOW), granted = emptySet(),
        )
        assertEquals(listOf("can draw over other apps"), StalkwareScanner.assess(overlay).map { it.reason })
        assertEquals(1, score(overlay))
    }

    @Test
    fun theCameraCountsOnlyForAnAppFromOutsideAStoreOrWithoutAnIcon() {
        val camera = setOf(Manifest.permission.CAMERA)
        val store = AppFacts("com.example.chat", system = false, installer = play, hasLauncher = true, requested = camera, granted = camera)
        assertEquals(0, score(store))
        assertTrue("can use the camera (granted)" in StalkwareScanner.assess(store).map { it.reason })
        val sideloaded = store.copy(installer = "com.android.packageinstaller")
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + 1, score(sideloaded))
        assertEquals(StalkwareScanner.NO_LAUNCHER_SCORE + 1, score(store.copy(hasLauncher = false)))
        assertEquals(StalkwareScanner.SIDELOAD_SCORE, score(sideloaded.copy(granted = emptySet())))
    }

    @Test
    fun grantFlagsAreReadPositionByPosition() {
        val requested = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        assertEquals(setOf(Manifest.permission.READ_SMS, Manifest.permission.RECORD_AUDIO), StalkwareScanner.grantedOf(requested, intArrayOf(3, 1, 2)))
        // A flags array shorter than the list: what has no flag is not granted.
        assertEquals(setOf(Manifest.permission.READ_SMS), StalkwareScanner.grantedOf(requested, intArrayOf(2)))
        assertNull(StalkwareScanner.grantedOf(requested, null))
        assertEquals(emptySet<String>(), StalkwareScanner.grantedOf(null, null))
    }

    @Test
    fun aStalkerwareSetupWithEveryGrantInPlaceIsHigh() {
        val all = setOf(
            Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.CAMERA,
        )
        val spy = AppFacts(rogue, system = false, installer = null, hasLauncher = false, requested = all, granted = all)
        assertEquals(Severity.HIGH, severity(spy))
    }

    // ── Apps published outside the stores by their own makers ────────────────

    private val signal = "org.thoughtcrime.securesms"

    @Test
    fun signalFromSignalOrgIsNeitherAnImpostorNorSideloaded() {
        // Signal's APK from signal.org: installed by the package installer, microphone and camera
        // granted, exempt from battery optimisation, and signed with Signal's key.
        val perms = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val official = AppFacts(
            signal, system = false, installer = "com.google.android.packageinstaller", hasLauncher = true,
            requested = perms, granted = perms, batteryExempt = true, officialBuild = true,
        )
        assertFalse(official.sideloaded)
        assertTrue(official.nameTrusted)
        val reasons = StalkwareScanner.assess(official).map { it.reason }
        assertFalse(reasons.any { "name of a known app" in it })
        // It scores exactly what the same app from Play would: only what it holds, nothing for where it came from.
        val fromPlay = official.copy(installer = play, officialBuild = false)
        assertEquals(score(fromPlay), score(official))
        assertEquals(severity(fromPlay), severity(official))
    }

    @Test
    fun anotherSignerUnderSignalsNameIsStillAnImpostor() {
        val perms = setOf(Manifest.permission.RECORD_AUDIO)
        val impostor = AppFacts(
            signal, system = false, installer = "com.google.android.packageinstaller", hasLauncher = true,
            requested = perms, granted = perms,
        )
        assertTrue(impostor.sideloaded)
        assertTrue(StalkwareScanner.assess(impostor).any { it.points == StalkwareScanner.IMPOSTOR_SCORE })
        assertEquals(Severity.HIGH, severity(impostor))
    }

    @Test
    fun anOfficialBuildIsRecognisedByItsPublishersCertificateOnly() {
        val cert = byteArrayOf(1, 2, 3, 4)
        val other = byteArrayOf(5, 6, 7, 8)
        val digest = StalkwareScanner.hex(java.security.MessageDigest.getInstance("SHA-256").digest(cert))
        val signers = mapOf(signal to setOf(digest))
        assertTrue(StalkwareScanner.officialBuild(signal, listOf(cert), signers))
        // A rotated key: the publisher's certificate anywhere in the signing history counts.
        assertTrue(StalkwareScanner.officialBuild(signal, listOf(other, cert), signers))
        assertFalse(StalkwareScanner.officialBuild(signal, listOf(other), signers))
        assertFalse(StalkwareScanner.officialBuild(signal, emptyList(), signers))
        // The certificate vouches only for the name it is listed under.
        assertFalse(StalkwareScanner.officialBuild("com.example.other", listOf(cert), signers))
    }

    @Test
    fun theOfficialSignerTableHoldsSha256FingerprintsInTheListForm() {
        assertTrue(StalkwareScanner.OFFICIAL_SIGNERS.isNotEmpty())
        for ((name, digests) in StalkwareScanner.OFFICIAL_SIGNERS) {
            assertTrue(name, digests.isNotEmpty())
            for (d in digests) assertTrue("$name: $d", Regex("[0-9A-F]{64}").matches(d))
        }
    }
}
