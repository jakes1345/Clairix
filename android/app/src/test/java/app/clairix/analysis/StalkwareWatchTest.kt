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
import org.junit.Assert.assertNotNull
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

    @Test
    fun onResumeAnInstallOrRemovalSinceTheLastCheckMakesItDue() {
        val day = StalkwareWatch.CHECK_EVERY_MS
        // Checked an hour ago: not due by time.
        assertFalse(StalkwareWatch.dueOnResume(1_000L, 1_000L + 3_600_000L, null, "app.clairix"))
        // An app installed (or removed) since: due, scanner on or off.
        assertTrue(StalkwareWatch.dueOnResume(1_000L, 1_000L + 3_600_000L, listOf("com.mspy.lite"), "app.clairix"))
        // Clairix's own update is no reason.
        assertFalse(StalkwareWatch.dueOnResume(1_000L, 1_000L + 3_600_000L, listOf("app.clairix"), "app.clairix"))
        assertTrue(StalkwareWatch.dueOnResume(1_000L, 1_000L + day, null, "app.clairix"))
    }

    @Test
    fun aFindingIsRaisedOnceWhileItsAppStaysInstalled() {
        val installed = mutableSetOf("com.example.hidden", "com.example.spy")
        // The first check is the baseline: everything is returned (silently) and recorded.
        val (first, afterFirst) = StalkwareWatch.newFindings(setOf("app:com.example.spy"), null) { it in installed }
        assertEquals(setOf("app:com.example.spy"), first)
        // A new HIGH app is raised.
        val (second, afterSecond) = StalkwareWatch.newFindings(setOf("app:com.example.spy", "app:com.example.hidden"), afterFirst) { it in installed }
        assertEquals(setOf("app:com.example.hidden"), second)
        // Its hidden-service signal leaves the week's window: it drops to MEDIUM and out of the check.
        val (third, afterThird) = StalkwareWatch.newFindings(setOf("app:com.example.spy"), afterSecond) { it in installed }
        assertTrue(third.isEmpty())
        assertTrue("app:com.example.hidden" in afterThird)
        // After the next reboot it scores HIGH again: not raised a second time.
        val (fourth, afterFourth) = StalkwareWatch.newFindings(setOf("app:com.example.spy", "app:com.example.hidden"), afterThird) { it in installed }
        assertTrue(fourth.isEmpty())
        // Uninstalled, it is forgotten; installed again later, it is raised again.
        installed -= "com.example.hidden"
        val (_, afterRemoval) = StalkwareWatch.newFindings(setOf("app:com.example.spy"), afterFourth) { it in installed }
        assertFalse("app:com.example.hidden" in afterRemoval)
        installed += "com.example.hidden"
        val (again, _) = StalkwareWatch.newFindings(setOf("app:com.example.spy", "app:com.example.hidden"), afterRemoval) { it in installed }
        assertEquals(setOf("app:com.example.hidden"), again)
        assertEquals("com.example.hidden", StalkwareWatch.packageOf("profile_owner:com.example.hidden"))
    }

    @Test
    fun aCheckPostsOneNotificationAtMost() {
        val app = StalkwareScanner.grantKey(GrantKind.ACCESSIBILITY, "com.example.spy")
        val r = result(apps = listOf(app("com.example.spy", "Spy", 9)), grants = mapOf(app to GrantStatus.UNKNOWN))
        val alerts = StalkwareWatch.alerts(setOf("app:com.example.spy", app), r, label, 5L, silent = true)
        assertEquals(2, alerts.size)
        val n = StalkwareWatch.notice(alerts, 5L)!!
        assertFalse(n.silent)
        assertEquals(Severity.HIGH, n.severity)
        assertTrue(n.title.contains("Spy looks like stalkerware") && n.title.contains("New accessibility service: Spy"))
        assertTrue(n.detail.contains("reads every screen"))
        // One alert is its own notification; none, none.
        assertEquals(alerts[0].copy(silent = false), StalkwareWatch.notice(alerts.take(1), 5L))
        assertNull(StalkwareWatch.notice(emptyList(), 5L))
    }

    @Test
    fun aNotificationAfterAnInstallWaitsUntilThePhoneIsPutDownAndUnlockedAgain() {
        val a = StalkwareWatch.alerts(setOf("app:a.b"), result(apps = listOf(app("a.b", "A", 6))), label, 5L, silent = true).single()
        val held = HeldNotice()
        // Raised while whoever installed it holds the unlocked phone.
        held.hold(a, screenOn = true)
        assertNull("not while they still hold it", held.userPresent())
        held.screenOff()
        val posted = held.userPresent()!!
        assertEquals("A looks like stalkerware", posted.title)
        assertFalse(posted.silent)
        assertNull("posted once", held.userPresent())

        // With the screen already off, the next unlock posts it.
        held.hold(a, screenOn = false)
        assertNotNull(held.userPresent())

        // Another install while one waits: one notification carries both, after the screen goes off again.
        val b = StalkwareWatch.alerts(setOf("app:c.d"), result(apps = listOf(app("c.d", "C", 6))), label, 6L, silent = true).single()
        held.hold(a, screenOn = true)
        held.screenOff()
        held.hold(b, screenOn = true)
        assertNull(held.userPresent())
        held.screenOff()
        val both = held.userPresent()!!
        assertTrue(both.title.contains("A looks like stalkerware") && both.title.contains("C looks like stalkerware"))

        // "Clear all data" drops what is held.
        held.hold(a, screenOn = false)
        held.drop()
        assertNull(held.userPresent())
    }

    private fun store(file: File) = Store(file, Dispatchers.Unconfined, TestStoreCipher)

    @Test
    fun theRecordKeepsWhatWasRaisedAndAMarkTakesItOut() {
        val file = File(Files.createTempDirectory("stalkerware").toFile().apply { deleteOnExit() }, "stalkerware.json")
        val record = StalkwareRecord(store(file))
        assertNull(record.alerted())
        // A record from before this was kept reads its last findings as raised.
        record.saveFindings(setOf("app:a.b"), 1_000L)
        assertEquals(setOf("app:a.b"), record.alerted())
        record.saveAlerted(setOf("app:a.b", "a11y:a.b"))
        assertEquals(setOf("app:a.b", "a11y:a.b"), StalkwareRecord(store(file)).alerted())
        record.unalert("a11y:a.b")
        assertEquals(setOf("app:a.b"), StalkwareRecord(store(file)).alerted())
        record.clearFindings()
        assertNull(StalkwareRecord(store(file)).alerted())
    }

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
