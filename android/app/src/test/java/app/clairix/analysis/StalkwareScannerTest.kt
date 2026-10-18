package app.clairix.analysis

import android.Manifest
import app.clairix.Severity
import app.clairix.analysis.StalkwareScanner.AppFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkwareScannerTest {
    private val gboard = "com.google.android.inputmethod.latin"
    private val rogue = "com.example.keylogger"
    private val play = "com.android.vending"

    /** A keyboard's ordinary manifest: its IME service, voice typing and a suggestion overlay. */
    private val keyboardManifest = AppFacts(
        name = rogue, system = false, installer = play, hasLauncher = true,
        requested = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SYSTEM_ALERT_WINDOW),
        declared = setOf(Manifest.permission.BIND_INPUT_METHOD),
        imeEnabled = true,
    )

    private fun score(f: AppFacts): Int = StalkwareScanner.assess(f).sumOf { it.points }

    /** The severity the scanner reports for [f], with the same thresholds. */
    private fun severity(f: AppFacts): Severity = StalkwareScanner.SuspiciousApp(
        f.name, f.name, score(f), emptyList(), 0, 0, f.sideloaded, f.hasLauncher, false, false, false,
        f.imeEnabled, f.deviceAdminActive, f.installer,
    ).severity

    // ── Keyboards ─────────────────────────────────────────────────────────────

    @Test
    fun anUnknownStoreKeyboardAloneIsMediumNotHigh() {
        // IME service, microphone and overlay are how a keyboard works: counted once, as the keyboard.
        assertEquals(StalkwareScanner.IME_ENABLED_SCORE, score(keyboardManifest))
        assertEquals(Severity.MEDIUM, severity(keyboardManifest))
    }

    @Test
    fun anUnknownKeyboardReachesHighOnlyWithAnIndependentSignal() {
        assertEquals(Severity.HIGH, severity(keyboardManifest.copy(installer = "com.android.packageinstaller")))
        assertEquals(Severity.HIGH, severity(keyboardManifest.copy(accessibilityEnabled = true)))
        assertEquals(Severity.HIGH, severity(keyboardManifest.copy(deviceAdminActive = true)))
        assertEquals(Severity.HIGH, severity(keyboardManifest.copy(hasLauncher = false)))
        assertEquals(
            Severity.HIGH,
            severity(keyboardManifest.copy(requested = keyboardManifest.requested + Manifest.permission.READ_SMS + Manifest.permission.READ_CALL_LOG)),
        )
    }

    @Test
    fun microphoneAndOverlayStillCountForAnAppThatIsNotAnEnabledKeyboard() {
        val notIme = keyboardManifest.copy(imeEnabled = false, declared = emptySet())
        assertEquals(2, score(notIme))
    }

    @Test
    fun aKnownKeyboardIsListedButNotScored() {
        val signals = StalkwareScanner.assess(keyboardManifest.copy(name = gboard))
        assertEquals(0, signals.sumOf { it.points })
        assertTrue("is an enabled keyboard (a known app)" in signals.map { it.reason })
    }

    @Test
    fun aDeclaredButDisabledKeyboardIsAWeakSignalUnlessKnown() {
        val declared = AppFacts(rogue, false, play, true, declared = setOf(Manifest.permission.BIND_INPUT_METHOD))
        assertEquals(1, score(declared))
        assertEquals(0, score(declared.copy(name = gboard)))
    }

    @Test
    fun oemKeyboardsAreKnown() {
        for (oem in listOf(
            "com.baidu.input_huawei", "com.huawei.ohos.inputmethod", "com.sohu.inputmethod.sogou.xiaomi",
            "com.iflytek.inputmethod.miui", "com.baidu.input_vivo", "com.oplus.securitykeyboard",
            "com.touchtype.swiftkey", "org.futo.inputmethod.latin", "helium314.keyboard",
        )) {
            assertEquals(oem, 0, score(keyboardManifest.copy(name = oem, system = true, installer = null, hasLauncher = false)))
        }
    }

    @Test
    fun anUnknownPreloadedKeyboardIsListedButNotScoredOnItsOwn() {
        val preloaded = keyboardManifest.copy(name = "com.vendor.ime", system = true, installer = null, hasLauncher = false)
        val signals = StalkwareScanner.assess(preloaded)
        assertEquals(0, signals.sumOf { it.points })
        assertEquals(listOf("is an enabled keyboard (preloaded by the phone maker)"), signals.map { it.reason })
        // A preloaded app without a drawer icon is normal: no signal for it.
        assertFalse(signals.any { "app drawer" in it.reason })
    }

    @Test
    fun anUnknownPreloadedKeyboardIsScoredWithAnotherSignal() {
        val preloaded = keyboardManifest.copy(name = "com.vendor.ime", system = true, installer = null, hasLauncher = false)
        val withSms = preloaded.copy(requested = preloaded.requested + Manifest.permission.READ_SMS)
        assertEquals(2 + StalkwareScanner.IME_ENABLED_SCORE, score(withSms))
        assertEquals(Severity.HIGH, severity(withSms))
    }

    @Test
    fun noLauncherIconCountsOnlyForUserApps() {
        assertEquals(StalkwareScanner.NO_LAUNCHER_SCORE, score(AppFacts(rogue, false, play, hasLauncher = false)))
        assertEquals(0, score(AppFacts(rogue, true, null, hasLauncher = false)))
    }

    // ── Device admins ─────────────────────────────────────────────────────────

    @Test
    fun anActiveAdminScoresTheGrantNotAlsoTheReceiver() {
        val declaredOnly = AppFacts(rogue, false, play, true, declared = setOf(Manifest.permission.BIND_DEVICE_ADMIN))
        assertEquals(1, score(declaredOnly))
        assertEquals(StalkwareScanner.DEVICE_ADMIN_ACTIVE_SCORE, score(declaredOnly.copy(deviceAdminActive = true)))
    }

    @Test
    fun knownAdminsAreListedButNotScored() {
        for (known in listOf(
            "com.google.android.gms", "com.microsoft.windowsintune.companyportal", "com.airwatch.androidagent",
            "com.samsung.android.fmm", "com.google.android.apps.work.clouddpc", "com.microsoft.office.outlook",
        )) {
            val f = AppFacts(known, false, play, true, declared = setOf(Manifest.permission.BIND_DEVICE_ADMIN), deviceAdminActive = true)
            val signals = StalkwareScanner.assess(f)
            assertEquals(known, 0, signals.sumOf { it.points })
            assertTrue(known, "device admin is active (a known app)" in signals.map { it.reason })
            assertTrue(StalkwareScanner.isKnownAdmin(known))
        }
        assertFalse(StalkwareScanner.isKnownAdmin(rogue))
    }

    // ── Existing behaviour kept ───────────────────────────────────────────────

    @Test
    fun vouchedBindingsStillExemptKnownApps() {
        fun declared(name: String, perm: String) = score(AppFacts(name, false, play, true, declared = setOf(perm)))
        assertEquals(0, declared("com.google.android.marvin.talkback", Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
        assertEquals(2, declared(rogue, Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
        assertEquals(0, declared("com.whatsapp", Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE))
        assertEquals(1, declared(rogue, Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE))
        assertEquals(0, declared(rogue, Manifest.permission.BIND_VPN_SERVICE))
    }

    @Test
    fun sideloadedHiddenSpywareIsStillHigh() {
        val spy = AppFacts(
            rogue, false, installer = null, hasLauncher = false,
            requested = setOf(Manifest.permission.READ_SMS),
            accessibilityEnabled = true, hiddenService = true,
        )
        // 3 sideload + 2 no icon + 2 SMS + 3 accessibility + 2 hidden service.
        assertEquals(12, score(spy))
        assertEquals(Severity.HIGH, severity(spy))
    }

    @Test
    fun systemAppsAreScoredOnlyForGrantsOutsideTheStockImage() {
        assertFalse(StalkwareScanner.systemAppWorthScoring(gboard, listener = false, a11y = false, imeEnabled = true))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.preloaded.ime", listener = false, a11y = false, imeEnabled = true))
        assertFalse(StalkwareScanner.systemAppWorthScoring("com.android.systemui", listener = true, a11y = false, imeEnabled = false))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.spy", listener = true, a11y = false, imeEnabled = false))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.spy", listener = false, a11y = true, imeEnabled = false))
        assertFalse(StalkwareScanner.systemAppWorthScoring("com.vendor.anything", listener = false, a11y = false, imeEnabled = false))
    }

    @Test
    fun suspiciousImesDropKnownKeyboardsAndSort() {
        assertEquals(
            listOf("com.a.keys", rogue),
            StalkwareScanner.suspiciousImes(listOf(rogue, gboard, "com.a.keys", "com.samsung.android.honeyboard", rogue)),
        )
        assertEquals(emptyList<String>(), StalkwareScanner.suspiciousImes(emptySet()))
    }
}
