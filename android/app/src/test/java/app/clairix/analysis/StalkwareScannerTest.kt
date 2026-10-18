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
            assertTrue(known, StalkwareScanner.isVouchedAdmin(f))
        }
        assertFalse(StalkwareScanner.isVouchedAdmin(AppFacts(rogue, false, play, true, deviceAdminActive = true)))
    }

    @Test
    fun mdmAgentsFromAStoreAreKnownAdmins() {
        for (mdm in listOf(
            "com.azure.authenticator", "com.mobileiron", "com.mobileiron.anyware.android",
            "net.soti.mobicontrol.androidwork", "com.meraki.sm", "com.manageengine.mdm.android",
        )) {
            assertEquals(mdm, 0, score(AppFacts(mdm, false, play, true, deviceAdminActive = true)))
        }
    }

    // ── Impostors: a known name only vouches from a store or the system image ──

    private val impostorAdmin = AppFacts(
        "com.google.android.gms", system = false, installer = "com.android.packageinstaller", hasLauncher = true,
        declared = setOf(Manifest.permission.BIND_DEVICE_ADMIN), deviceAdminActive = true,
    )

    @Test
    fun aSideloadedAdminUsingAKnownNameIsHighAndNotVouched() {
        val signals = StalkwareScanner.assess(impostorAdmin)
        assertTrue(signals.any { it.points == StalkwareScanner.IMPOSTOR_SCORE && "name of a known app" in it.reason })
        assertFalse("device admin is active (a known app)" in signals.map { it.reason })
        assertEquals(Severity.HIGH, severity(impostorAdmin))
        // The Phone check badge reads the same verdict.
        assertFalse(StalkwareScanner.isVouchedAdmin(impostorAdmin))
    }

    @Test
    fun theSameAdminFromAStoreOrTheSystemImageIsStillVouched() {
        for (real in listOf(impostorAdmin.copy(installer = play), impostorAdmin.copy(system = true, installer = null))) {
            assertEquals(0, score(real))
            assertTrue(StalkwareScanner.isVouchedAdmin(real))
        }
    }

    @Test
    fun anImpostorPlusAnyActiveCapabilityReachesHighWithoutTheSideloadSignal() {
        // Even before the sideload signal: the impostor signal plus the weakest capability is HIGH (5).
        for (capability in listOf(
            StalkwareScanner.DEVICE_ADMIN_ACTIVE_SCORE, 3 /* accessibility */, 3 /* listener */, StalkwareScanner.IME_ENABLED_SCORE,
        )) {
            assertTrue(StalkwareScanner.IMPOSTOR_SCORE + capability >= 5)
        }
    }

    @Test
    fun aSideloadedKeyboardUsingAKnownNameGetsNoWaiverAndIsHigh() {
        val fake = keyboardManifest.copy(name = "com.touchtype.swiftkey", installer = null)
        val reasons = StalkwareScanner.assess(fake).map { it.reason }
        assertTrue(reasons.any { "name of a known app" in it })
        assertTrue("can record audio" in reasons && "can draw over other apps" in reasons)
        assertTrue(reasons.any { "can log every keystroke" in it })
        assertFalse(StalkwareScanner.isVouchedIme(fake))
        assertEquals(Severity.HIGH, severity(fake))
        // The store copy is the real one.
        assertEquals(0, score(fake.copy(installer = play)))
        assertTrue(StalkwareScanner.isVouchedIme(fake.copy(installer = play)))
    }

    @Test
    fun sideloadedAccessibilityAndListenerImpostorsAreNotVouched() {
        val a11y = AppFacts("com.google.android.marvin.talkback", false, null, true, accessibilityEnabled = true)
        assertTrue(StalkwareScanner.assess(a11y).any { it.reason == "accessibility service is switched ON" })
        assertEquals(Severity.HIGH, severity(a11y))
        val listener = AppFacts("com.whatsapp", false, null, true, notificationListener = true)
        assertTrue(StalkwareScanner.assess(listener).any { it.reason.startsWith("notification access is switched ON") })
        assertEquals(Severity.HIGH, severity(listener))
        // From the store they are what they say they are.
        assertEquals(0, score(a11y.copy(installer = play)))
        assertEquals(0, score(listener.copy(installer = play)))
    }

    // ── Install source: who really installed it, and whether that store is genuine ──

    private val fdroid = "org.fdroid.fdroid"

    @Test
    fun anAdbInstallNamingPlayAsItsInstallerIsASideload() {
        // `adb install -i com.android.vending`: Android records no initiating app for shell or root.
        val spoofed = AppFacts(rogue, false, installer = null, hasLauncher = true, declaredInstaller = play)
        assertTrue(spoofed.spoofedInstaller && spoofed.sideloaded)
        val reasons = StalkwareScanner.assess(spoofed).map { it.reason }
        assertTrue(reasons.any { "installingPackageName says $play" in it && "initiatingPackageName is empty" in it })
        assertEquals(StalkwareScanner.SIDELOAD_SCORE, score(spoofed))
        // Wearing a known admin's name it is HIGH and unvouched, badge included.
        val admin = spoofed.copy(name = "com.google.android.gms", deviceAdminActive = true)
        assertFalse(StalkwareScanner.isVouchedAdmin(admin))
        assertEquals(Severity.HIGH, severity(admin))
    }

    @Test
    fun anInitiatingAppThatDisagreesWithTheNamedInstallerIsASideload() {
        val forged = AppFacts(rogue, false, installer = "com.evil.dropper", hasLauncher = true, declaredInstaller = play)
        assertTrue(forged.sideloaded)
        assertTrue(StalkwareScanner.assess(forged).any {
            "installingPackageName says $play" in it.reason && "initiatingPackageName is com.evil.dropper" in it.reason
        })
        // A preloaded transfer app restoring apps under Play is not a forgery.
        val restored = forged.copy(installer = "com.sec.android.easyMover", installerPreloaded = true)
        assertFalse(restored.sideloaded)
        assertEquals(0, score(restored))
    }

    @Test
    fun onlyPreloadedStoresOrStoresInstalledByOneAreGenuine() {
        val preloaded = setOf(play)
        val genuine = StalkwareScanner.genuineStores(
            systemPackages = preloaded,
            sources = mapOf(
                play to StalkwareScanner.InstallSource(null, null),
                "com.sec.android.app.samsungapps" to StalkwareScanner.InstallSource(play, play),
                fdroid to StalkwareScanner.InstallSource("com.android.chrome", "com.android.chrome"),
                "com.amazon.venezia" to StalkwareScanner.InstallSource(null, play), // adb install -i
            ),
        )
        assertEquals(setOf(play, "com.sec.android.app.samsungapps"), genuine)
    }

    @Test
    fun aFakePlayStoreDoesNotVouchForWhatItInstalls() {
        // A sideloaded app calling itself com.android.vending: not preloaded, installed from an APK.
        val noGenuineStore = StalkwareScanner.genuineStores(
            systemPackages = emptySet(),
            sources = mapOf(play to StalkwareScanner.InstallSource("com.google.android.packageinstaller", "com.google.android.packageinstaller")),
        )
        assertTrue(noGenuineStore.isEmpty())
        val installed = AppFacts("com.google.android.gms", false, installer = play, hasLauncher = true,
            deviceAdminActive = true, genuineStores = noGenuineStore)
        assertTrue(installed.sideloaded)
        assertTrue(StalkwareScanner.assess(installed).any { "not the genuine store" in it.reason })
        assertFalse(StalkwareScanner.isVouchedAdmin(installed))
        assertEquals(Severity.HIGH, severity(installed))
    }

    @Test
    fun anFDroidInstalledGoogleNameIsNotVouched() {
        val gms = AppFacts("com.google.android.gms", false, installer = fdroid, hasLauncher = true, deviceAdminActive = true)
        assertFalse(gms.sideloaded)
        assertFalse(StalkwareScanner.isVouchedAdmin(gms))
        val signals = StalkwareScanner.assess(gms)
        assertTrue(signals.any { it.points == StalkwareScanner.IMPOSTOR_SCORE && "cannot vouch" in it.reason })
        // Impostor (2) + active admin (3): HIGH without any sideload signal.
        assertEquals(Severity.HIGH, severity(gms))
        // Same for a Microsoft keyboard name via Aurora, with no keyboard-permission waiver.
        val swiftkey = keyboardManifest.copy(name = "com.touchtype.swiftkey", installer = "com.aurora.store")
        assertFalse(StalkwareScanner.isVouchedIme(swiftkey))
        assertEquals(Severity.HIGH, severity(swiftkey))
        // An open-source keyboard F-Droid publishes is vouched for by it.
        val floris = keyboardManifest.copy(name = "dev.patrickgold.florisboard", installer = fdroid)
        assertTrue(StalkwareScanner.isVouchedIme(floris))
        assertEquals(0, score(floris))
    }

    @Test
    fun restoredKeyboardNamesAreKnown() {
        for (name in listOf("com.htc.sense.ime", "com.nuance.swype.dtc", "com.minuum.keyboard", "com.transsion.sk", "com.tct.smartkeyboard")) {
            assertEquals(name, 0, score(keyboardManifest.copy(name = name)))
        }
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
    fun suspiciousImesDropVouchedKeyboardsAndSort() {
        assertEquals(
            listOf("com.a.keys", rogue),
            StalkwareScanner.suspiciousImes(
                listOf(rogue, gboard, "com.a.keys", "com.samsung.android.honeyboard", rogue),
                vouchedImes = setOf(gboard, "com.samsung.android.honeyboard"),
            ),
        )
        // A known name that was not vouched for (sideloaded) stays listed.
        assertEquals(listOf(gboard), StalkwareScanner.suspiciousImes(listOf(gboard), vouchedImes = emptySet()))
        assertEquals(emptyList<String>(), StalkwareScanner.suspiciousImes(emptySet(), emptySet()))
    }
}
