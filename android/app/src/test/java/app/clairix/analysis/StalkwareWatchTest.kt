package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.Store
import app.clairix.TestStoreCipher
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What a stalkerware check reports as new, how it is alerted, and the record it keeps. */
class StalkwareWatchTest {

    private fun app(pkg: String, label: String, score: Int, knownAs: String? = null) = StalkwareScanner.SuspiciousApp(
        pkg, label, score, listOf("accessibility service is switched ON"), 0L, 0L, sideloaded = true, hasLauncherIcon = false,
        vpnCapable = false, notificationListener = false, accessibilityEnabled = true, imeEnabled = false,
        deviceAdminActive = false, installerPackage = null, knownAs = knownAs,
    )

    private fun result(apps: List<StalkwareScanner.SuspiciousApp> = emptyList(), grants: Map<String, GrantStatus> = emptyMap()) =
        StalkwareScanner.Result(
            apps = apps, notificationListeners = emptyList(), accessibilityServices = emptyList(), vpnApps = emptyList(),
            suspiciousImes = emptyList(), activeDeviceAdmins = emptyList(), vouchedAdmins = emptySet(),
            usageAccessGranted = true, scannedAt = 1_000L, grants = grants,
        )

    private val label: (String) -> String = { it.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() } }

    @Test
    fun aCheckReportsHighAppsAndGrantsNobodyVouchesFor() {
        val r = result(
            apps = listOf(app("com.example.spy", "Spy", 9), app("com.example.meh", "Meh", 3)),
            grants = mapOf(
                StalkwareScanner.grantKey(GrantKind.ACCESSIBILITY, "com.example.spy") to GrantStatus.UNKNOWN,
                StalkwareScanner.grantKey(GrantKind.ACCESSIBILITY, "com.google.android.marvin.talkback") to GrantStatus.KNOWN,
                StalkwareScanner.grantKey(GrantKind.DEVICE_ADMIN, "com.airwatch.androidagent") to GrantStatus.CONFIRM,
                StalkwareScanner.grantKey(GrantKind.KEYBOARD, "com.vendor.ime") to GrantStatus.PRELOADED,
                StalkwareScanner.grantKey(GrantKind.NOTIFICATION_LISTENER, "com.example.mine") to GrantStatus.YOURS,
            ),
        )
        assertEquals(
            setOf("app:com.example.spy", "a11y:com.example.spy", "admin:com.airwatch.androidagent"),
            StalkwareWatch.findings(r),
        )
    }

    @Test
    fun aNewHighAppIsOneHighPlatformAlertThatNamesIt() {
        val r = result(apps = listOf(app("com.mxspy", "Copy9", 7, knownAs = "TheTruthSpy")))
        val alerts = StalkwareWatch.alerts(setOf("app:com.mxspy"), r, label, now = 5L, silent = false)
        val a = alerts.single()
        assertEquals(Severity.HIGH, a.severity)
        assertEquals(EventKind.PLATFORM, a.kind)
        assertEquals("Known stalkerware on this phone: Copy9", a.title)
        assertTrue(a.detail.contains("com.mxspy") && a.detail.contains("accessibility service is switched ON"))
        assertFalse(a.silent)
        assertEquals("stalkerware_apps:com.mxspy", a.dedupeKey)

        val two = result(apps = listOf(app("a.b", "A", 6), app("c.d", "C", 5)))
        assertEquals("2 apps look like stalkerware", StalkwareWatch.alerts(setOf("app:a.b", "app:c.d"), two, label, 5L, false).single().title)
        assertEquals("A looks like stalkerware", StalkwareWatch.alerts(setOf("app:a.b"), two, label, 5L, false).single().title)
    }

    @Test
    fun newGrantsAreOneAlertAndAnOwnerSaysThePhoneIsManaged() {
        val owner = StalkwareScanner.grantKey(GrantKind.DEVICE_OWNER, "com.microsoft.windowsintune.companyportal")
        val r = result(grants = mapOf(owner to GrantStatus.CONFIRM))
        val a = StalkwareWatch.alerts(setOf(owner), r, label, 5L, silent = false).single()
        assertEquals("This phone is now managed by Companyportal", a.title)
        assertEquals(Severity.HIGH, a.severity)
        assertTrue(a.detail.contains("install and remove apps silently"))

        val a11y = StalkwareScanner.grantKey(GrantKind.ACCESSIBILITY, "com.example.spy")
        val listener = StalkwareScanner.grantKey(GrantKind.NOTIFICATION_LISTENER, "com.example.spy")
        val both = result(grants = mapOf(a11y to GrantStatus.UNKNOWN, listener to GrantStatus.UNKNOWN))
        val combined = StalkwareWatch.alerts(setOf(a11y, listener), both, label, 5L, silent = false).single()
        assertEquals("2 new kinds of special access to check", combined.title)
        assertTrue(combined.detail.contains("reads every screen") && combined.detail.contains("reads every notification"))
        assertEquals(
            "New accessibility service: Spy",
            StalkwareWatch.alerts(setOf(a11y), both, label, 5L, silent = false).single().title,
        )
    }

    @Test
    fun aCheckOnScreenOrTheFirstOneRaisesNoNotification() {
        val r = result(apps = listOf(app("a.b", "A", 6)))
        assertTrue(StalkwareWatch.alerts(setOf("app:a.b"), r, label, 5L, silent = true).all { it.silent })
        assertTrue(StalkwareWatch.alerts(emptySet(), r, label, 5L, silent = false).isEmpty())
    }

    @Test
    fun aCheckIsDueOnceADayOrWhenThereHasBeenNone() {
        val day = StalkwareWatch.CHECK_EVERY_MS
        assertTrue(StalkwareWatch.due(null, 10L))
        assertFalse(StalkwareWatch.due(1_000L, 1_000L + day - 1))
        assertTrue(StalkwareWatch.due(1_000L, 1_000L + day))
        // A clock set back is no reason to wait a day.
        assertTrue(StalkwareWatch.due(1_000L, 999L))
    }

    private fun store(file: File) = Store(file, Dispatchers.Unconfined, TestStoreCipher)

    @Test
    fun theRecordKeepsTheLastFindingsAndTheOwnersMarksAcrossARestart() {
        val file = File(Files.createTempDirectory("stalkerware").toFile().apply { deleteOnExit() }, "stalkerware.json")
        val record = StalkwareRecord(store(file))
        assertNull(record.lastFindings())
        record.saveFindings(setOf("app:a.b", "a11y:a.b"), 1_000L)
        val mine = StalkwareScanner.ackKey(GrantKind.ACCESSIBILITY, "com.example.buttonmapper", "AB".repeat(32))
        record.acknowledge(mine, 2_000L)
        record.acknowledge(StalkwareScanner.ackKey(GrantKind.KEYBOARD, "com.example.keys", "CD".repeat(32)), 2_000L)

        val reopened = StalkwareRecord(store(file))
        assertEquals(setOf("app:a.b", "a11y:a.b"), reopened.lastFindings())
        assertTrue(mine in reopened.acknowledged())
        assertEquals(2, reopened.acknowledged().size)

        // Undo takes back every mark on that grant of that app, whatever certificate it was made for.
        reopened.unacknowledge("${GrantKind.ACCESSIBILITY.key}|com.example.buttonmapper|")
        assertFalse(mine in reopened.acknowledged())
        assertEquals(1, reopened.acknowledged().size)

        // "Clear all data" forgets the findings, so the next check starts a new baseline; the marks stay.
        reopened.clearFindings()
        assertNull(StalkwareRecord(store(file)).lastFindings())
        assertEquals(1, StalkwareRecord(store(file)).acknowledged().size)
    }
}
