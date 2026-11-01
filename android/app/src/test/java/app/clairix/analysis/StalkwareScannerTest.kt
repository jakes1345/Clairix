package app.clairix.analysis

import android.Manifest
import app.clairix.Severity
import app.clairix.analysis.StalkwareScanner.AppFacts
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
            "com.google.android.gms", "com.google.android.apps.adm", "com.samsung.android.fmm",
            "com.microsoft.office.outlook", "com.samsung.android.knox.containercore",
        )) {
            val f = AppFacts(known, false, play, true, declared = setOf(Manifest.permission.BIND_DEVICE_ADMIN), deviceAdminActive = true)
            val signals = StalkwareScanner.assess(f)
            assertEquals(known, 0, signals.sumOf { it.points })
            assertTrue(known, "device admin is active (a known app)" in signals.map { it.reason })
            assertTrue(known, StalkwareScanner.isVouchedAdmin(f))
            assertEquals(known, GrantStatus.KNOWN, StalkwareScanner.grantStatus(GrantKind.DEVICE_ADMIN, f))
        }
        assertFalse(StalkwareScanner.isVouchedAdmin(AppFacts(rogue, false, play, true, deviceAdminActive = true)))
    }

    @Test
    fun mdmAgentsFromAStoreAreRealButMediumUntilTheOwnerConfirms() {
        // Anyone can install one of these from Play and enrol a partner's phone in minutes.
        for (mdm in listOf(
            "com.google.android.apps.work.clouddpc", "com.microsoft.windowsintune.companyportal", "com.airwatch.androidagent",
            "com.azure.authenticator", "com.mobileiron", "com.mobileiron.anyware.android",
            "net.soti.mobicontrol.androidwork", "com.meraki.sm", "com.manageengine.mdm.android",
        )) {
            val f = AppFacts(mdm, false, play, true, declared = setOf(Manifest.permission.BIND_DEVICE_ADMIN), deviceAdminActive = true)
            // The real app, so no impostor signal; but listed, not vouched for silently.
            assertTrue(mdm, StalkwareScanner.isVouchedAdmin(f))
            assertEquals(mdm, StalkwareScanner.MDM_ADMIN_SCORE, score(f))
            assertEquals(mdm, Severity.MEDIUM, severity(f))
            assertEquals(mdm, GrantStatus.CONFIRM, StalkwareScanner.grantStatus(GrantKind.DEVICE_ADMIN, f))
            assertEquals(mdm, Severity.MEDIUM, StalkwareScanner.grantSeverity(GrantKind.DEVICE_ADMIN, GrantStatus.CONFIRM))
            // Confirmed by the owner: listed as theirs, scoring nothing.
            val confirmed = f.copy(acknowledged = setOf(GrantKind.DEVICE_ADMIN))
            assertEquals(mdm, 0, score(confirmed))
            assertEquals(mdm, GrantStatus.YOURS, StalkwareScanner.grantStatus(GrantKind.DEVICE_ADMIN, confirmed))
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
    fun anInstallRecordFromBeforeTheSystemWasBuiltIsNotCalledAnAdbInstall() {
        // A years-old Play app on a phone upgraded since: Android carried over only the installer.
        val old = AppFacts("com.example.notes", false, installer = null, hasLauncher = true, declaredInstaller = play, installedBeforeSystemBuild = true)
        val reason = StalkwareScanner.assess(old).map { it.reason }.single { "installingPackageName says $play" in it }
        assertTrue(reason, "predate an OS upgrade" in reason && "uninstalled" in reason)
        assertFalse(reason, "adb" in reason || "root" in reason || "forged" in reason)
        // The score is that of any install from outside a store.
        assertEquals(StalkwareScanner.SIDELOAD_SCORE, score(old))
        // Installed after this Android was built, it cannot be a carried-over record.
        val recent = old.copy(installedBeforeSystemBuild = false)
        val recentReason = StalkwareScanner.assess(recent).map { it.reason }.single { "installingPackageName says $play" in it }
        assertTrue(recentReason, "unlikely to be left over from an OS upgrade" in recentReason && "adb" in recentReason)
    }

    @Test
    fun installedBeforeComparesTheInstallWithTheSystemBuildAndNeedsBoth() {
        assertTrue(StalkwareScanner.installedBefore(firstInstallTs = 1_000L, systemBuiltTs = 2_000L))
        assertFalse(StalkwareScanner.installedBefore(firstInstallTs = 3_000L, systemBuiltTs = 2_000L))
        assertFalse(StalkwareScanner.installedBefore(firstInstallTs = 0L, systemBuiltTs = 2_000L))
        assertFalse(StalkwareScanner.installedBefore(firstInstallTs = 1_000L, systemBuiltTs = 0L))
    }

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
        // 2 sideload + 2 no icon + 2 SMS + 3 accessibility + 2 hidden service.
        assertEquals(11, score(spy))
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

    // ── Where an app came from is not, on its own, a reason to list it ────────

    @Test
    fun installOriginAloneIsBelowMedium() {
        // F-Droid installed from its website is not a genuine store, so what it installs reads as
        // a sideload; so do APK files, adb and other installers. None of it is listed on its own.
        for (f in listOf(
            AppFacts(rogue, false, installer = fdroid, hasLauncher = true, genuineStores = setOf(play)),
            AppFacts(rogue, false, installer = "com.google.android.packageinstaller", hasLauncher = true),
            AppFacts(rogue, false, installer = null, hasLauncher = true),
            AppFacts(rogue, false, installer = "dev.imranr.obtainium", hasLauncher = true),
            AppFacts(rogue, false, installer = null, hasLauncher = true, declaredInstaller = play),
        )) {
            assertTrue(f.toString(), f.sideloaded)
            assertEquals(f.toString(), StalkwareScanner.SIDELOAD_SCORE, score(f))
            assertEquals(f.toString(), Severity.LOW, severity(f))
        }
    }

    @Test
    fun aSideloadWithOneMoreSignalIsListed() {
        val hidden = AppFacts(rogue, false, installer = fdroid, hasLauncher = false, genuineStores = setOf(play))
        assertEquals(StalkwareScanner.SIDELOAD_SCORE + StalkwareScanner.NO_LAUNCHER_SCORE, score(hidden))
        assertEquals(Severity.MEDIUM, severity(hidden))
    }

    @Test
    fun anFDroidInstallIsDescribedAsAnOpenStoreNotAsAFakeOne() {
        val f = AppFacts(rogue, false, installer = fdroid, hasLauncher = true, genuineStores = setOf(play))
        val reason = StalkwareScanner.assess(f).single().reason
        assertTrue(reason, "open store" in reason)
        assertFalse(reason, "not the genuine store" in reason)
    }

    @Test
    fun aMissingInitiatingAppIsNotCalledAForgery() {
        // Android also forgets the initiating app once that app is uninstalled.
        val f = AppFacts(rogue, false, installer = null, hasLauncher = true, declaredInstaller = play)
        val reason = StalkwareScanner.assess(f).single().reason
        assertFalse(reason, "forged" in reason)
        assertTrue(reason, "adb" in reason && "uninstalled" in reason)
        // An initiating app that is there and disagrees is still a forgery.
        val forged = f.copy(installer = "com.evil.dropper")
        assertTrue(StalkwareScanner.assess(forged).single().reason.endsWith("the installer is forged"))
    }

    // ── The stalkerware list: by package name and by signing certificate ──────

    private val truthSpy = StalkerwareFamily(listOf("TheTruthSpy", "Copy9", "ExactSpy"))

    @Test
    fun anAppOnTheStalkerwareListIsHighOnItsOwn() {
        val byName = AppFacts("com.mxspy", false, play, true, knownStalkerware = IocMatch(truthSpy, byCertificate = false, byPackage = true))
        val signals = StalkwareScanner.assess(byName)
        assertEquals(StalkwareScanner.KNOWN_STALKERWARE_SCORE, signals.sumOf { it.points })
        assertEquals(Severity.HIGH, severity(byName))
        val reason = signals.single().reason
        assertTrue(reason, reason.startsWith("is TheTruthSpy (also sold as Copy9, ExactSpy)") && "package name is on" in reason)
        val byCert = byName.copy(name = "com.innocent.app", knownStalkerware = IocMatch(truthSpy, byCertificate = true, byPackage = false))
        assertTrue(StalkwareScanner.assess(byCert).single().reason.contains("signing certificate is on"))
        assertEquals(Severity.HIGH, severity(byCert))
    }

    @Test
    fun certificatesAreMatchedByTheFingerprintsTheListKeeps() {
        val der = "abc".toByteArray()
        val digests = StalkwareScanner.digests(listOf(der))
        // SHA-1 and SHA-256 of "abc", uppercase hex.
        assertEquals(
            setOf("A9993E364706816ABA3E25717850C26C9CD0D89D", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),
            digests,
        )
        val list = IocList.parseJson(
            """{"type":"clairix-stalkerware-ioc","version":1,"issued":1700000000,"source":"t","licence":"CC BY 4.0",""" +
                """"names":[["TheTruthSpy","Copy9"]],"packages":{"com.mxspy":0},"certs":{"A9993E364706816ABA3E25717850C26C9CD0D89D":0}}""",
            1_800_000_000L,
        )
        // Renamed, signed with the listed certificate: still found.
        val m = list.match("com.totally.different", digests)!!
        assertEquals("TheTruthSpy", m.family.name)
        assertTrue(m.byCertificate)
        assertNull(list.match("com.totally.different", StalkwareScanner.digests(listOf("abd".toByteArray()))))
    }

    @Test
    fun noMarkClearsAnAppOnTheStalkerwareList() {
        val spy = AppFacts(
            "com.mxspy", false, null, true, accessibilityEnabled = true,
            acknowledged = setOf(GrantKind.ACCESSIBILITY), knownStalkerware = IocMatch(truthSpy, false, true),
        )
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.ACCESSIBILITY, spy))
        assertTrue(StalkwareScanner.assess(spy).any { it.reason == "accessibility service is switched ON" })
    }

    // ── Grants: one verdict for the page, Phone check and the alerts ──────────

    @Test
    fun accessibilityAndListenerVouchingFollowTheOriginRules() {
        val talkback = AppFacts("com.google.android.marvin.talkback", false, play, true, accessibilityEnabled = true)
        assertTrue(StalkwareScanner.isVouchedAccessibility(talkback))
        assertFalse(StalkwareScanner.isVouchedAccessibility(talkback.copy(installer = null)))
        assertFalse(StalkwareScanner.isVouchedAccessibility(AppFacts(rogue, false, play, true)))
        val whatsapp = AppFacts("com.whatsapp", false, play, true, notificationListener = true)
        assertTrue(StalkwareScanner.isVouchedListener(whatsapp))
        assertFalse(StalkwareScanner.isVouchedListener(whatsapp.copy(installer = "com.android.packageinstaller")))
        assertEquals(GrantStatus.KNOWN, StalkwareScanner.grantStatus(GrantKind.ACCESSIBILITY, talkback))
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.ACCESSIBILITY, talkback.copy(installer = null)))
    }

    @Test
    fun aUserChosenAccessibilityAppIsYoursOnceMarked() {
        val macro = AppFacts(
            "com.example.buttonmapper", false, play, true, accessibilityEnabled = true,
            declared = setOf(Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
        )
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.ACCESSIBILITY, macro))
        assertEquals(Severity.HIGH, StalkwareScanner.grantSeverity(GrantKind.ACCESSIBILITY, GrantStatus.UNKNOWN))
        // 2 for declaring the service, 3 for having it on.
        assertEquals(5, score(macro))
        val mine = macro.copy(acknowledged = setOf(GrantKind.ACCESSIBILITY))
        assertEquals(GrantStatus.YOURS, StalkwareScanner.grantStatus(GrantKind.ACCESSIBILITY, mine))
        assertNull(StalkwareScanner.grantSeverity(GrantKind.ACCESSIBILITY, GrantStatus.YOURS))
        assertEquals(0, score(mine))
        assertTrue("accessibility service is on (you marked it as yours)" in StalkwareScanner.assess(mine).map { it.reason })
        // A mark on one grant says nothing about another.
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.NOTIFICATION_LISTENER, mine))
    }

    @Test
    fun keyboardAndListenerGrantsReadAsTheScoreDoes() {
        assertEquals(GrantStatus.KNOWN, StalkwareScanner.grantStatus(GrantKind.KEYBOARD, keyboardManifest.copy(name = gboard)))
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.KEYBOARD, keyboardManifest))
        assertEquals(Severity.MEDIUM, StalkwareScanner.grantSeverity(GrantKind.KEYBOARD, GrantStatus.UNKNOWN))
        val oem = keyboardManifest.copy(name = "com.vendor.ime", system = true, installer = null)
        assertEquals(GrantStatus.PRELOADED, StalkwareScanner.grantStatus(GrantKind.KEYBOARD, oem))
        assertNull(StalkwareScanner.grantSeverity(GrantKind.KEYBOARD, GrantStatus.PRELOADED))
        val oemListener = AppFacts("com.vendor.notifications", system = true, installer = null, hasLauncher = false, notificationListener = true)
        assertEquals(GrantStatus.PRELOADED, StalkwareScanner.grantStatus(GrantKind.NOTIFICATION_LISTENER, oemListener))
        assertEquals(GrantStatus.UNKNOWN, StalkwareScanner.grantStatus(GrantKind.NOTIFICATION_LISTENER, oemListener.copy(system = false, installer = play)))
    }

    @Test
    fun anOwnerIsHighWhateverItsNameUntilTheOwnerConfirms() {
        // Family Link supervision runs as Play services: a known admin, from the system image.
        val gms = AppFacts("com.google.android.gms", system = true, installer = null, hasLauncher = true, deviceAdminActive = true)
        assertTrue(StalkwareScanner.isVouchedAdmin(gms))
        assertEquals(GrantStatus.KNOWN, StalkwareScanner.grantStatus(GrantKind.DEVICE_ADMIN, gms))
        for (kind in listOf(GrantKind.DEVICE_OWNER, GrantKind.PROFILE_OWNER)) {
            assertEquals(kind.name, GrantStatus.CONFIRM, StalkwareScanner.grantStatus(kind, gms))
            assertEquals(kind.name, Severity.HIGH, StalkwareScanner.grantSeverity(kind, GrantStatus.CONFIRM))
            val confirmed = gms.copy(acknowledged = setOf(kind))
            assertEquals(kind.name, GrantStatus.YOURS, StalkwareScanner.grantStatus(kind, confirmed))
            assertNull(kind.name, StalkwareScanner.grantSeverity(kind, GrantStatus.YOURS))
        }
        // An MDM agent from a store that owns the phone is the same.
        val intune = AppFacts("com.microsoft.windowsintune.companyportal", false, play, true, deviceAdminActive = true)
        assertEquals(GrantStatus.CONFIRM, StalkwareScanner.grantStatus(GrantKind.DEVICE_OWNER, intune))
    }

    private fun result(
        apps: List<StalkwareScanner.SuspiciousApp> = emptyList(),
        grants: Map<String, GrantStatus> = emptyMap(),
        owners: List<StalkwareScanner.Owner> = emptyList(),
    ) = StalkwareScanner.Result(
        apps = apps, notificationListeners = emptyList(), accessibilityServices = emptyList(), vpnApps = emptyList(),
        suspiciousImes = emptyList(), activeDeviceAdmins = owners.map { it.packageName }, vouchedAdmins = emptySet(),
        usageAccessGranted = true, scannedAt = 0L, grants = grants, owners = owners,
    )

    @Test
    fun anOwnerOrAnUnconfirmedManagementAppIsNeverAVouchedAdminAndNeverClean() {
        // Only a known admin or one the owner marked counts as vouched for.
        assertTrue(StalkwareScanner.adminVouched(GrantStatus.KNOWN))
        assertTrue(StalkwareScanner.adminVouched(GrantStatus.YOURS))
        assertFalse(StalkwareScanner.adminVouched(GrantStatus.CONFIRM))
        assertFalse(StalkwareScanner.adminVouched(GrantStatus.UNKNOWN))
        // Family Link: Play services owns the profile. Its admin reads as its owner grant, not as a known admin.
        val gms = "com.google.android.gms"
        val supervised = result(
            grants = mapOf(StalkwareScanner.grantKey(GrantKind.PROFILE_OWNER, gms) to GrantStatus.CONFIRM),
            owners = listOf(StalkwareScanner.Owner(gms, deviceOwner = false)),
        )
        assertEquals(GrantStatus.CONFIRM, supervised.adminStatus(gms))
        assertFalse("no clean card beside an unconfirmed owner", supervised.clean)
        // Confirmed, it is clean.
        val confirmed = supervised.copy(grants = mapOf(StalkwareScanner.grantKey(GrantKind.PROFILE_OWNER, gms) to GrantStatus.YOURS))
        assertEquals(GrantStatus.YOURS, confirmed.adminStatus(gms))
        assertTrue(confirmed.clean)
        // A plain admin reads as itself; an unknown grant of any kind is not clean either.
        val plain = result(grants = mapOf(StalkwareScanner.grantKey(GrantKind.DEVICE_ADMIN, gms) to GrantStatus.KNOWN))
        assertEquals(GrantStatus.KNOWN, plain.adminStatus(gms))
        assertTrue(plain.clean)
        assertFalse(result(grants = mapOf(StalkwareScanner.grantKey(GrantKind.ACCESSIBILITY, "com.spy") to GrantStatus.UNKNOWN)).clean)
        assertTrue("preloaded keyboards need no look", result(grants = mapOf(StalkwareScanner.grantKey(GrantKind.KEYBOARD, "com.oem.ime") to GrantStatus.PRELOADED)).clean)
    }

    @Test
    fun confirmingAnOwnerCoversItsDeviceAdminAndMarksAreTiedToTheSigner() {
        val pkg = "com.airwatch.androidagent"
        val signer = "AB".repeat(32)
        val acks = setOf(StalkwareScanner.ackKey(GrantKind.DEVICE_OWNER, pkg, signer))
        assertEquals(setOf(GrantKind.DEVICE_OWNER, GrantKind.DEVICE_ADMIN), StalkwareScanner.acknowledgedKinds(pkg, signer, acks))
        // Another app installed later under the same name, signed by someone else, is asked about afresh.
        assertEquals(emptySet<GrantKind>(), StalkwareScanner.acknowledgedKinds(pkg, "CD".repeat(32), acks))
        assertEquals(pkg, StalkwareScanner.ackPackage(acks.single()))
        assertEquals(emptySet<GrantKind>(), StalkwareScanner.acknowledgedKinds(pkg, signer, emptySet()))
    }

    // ── What this model makes of Clairix itself ──────────────────────────────
    // The README ("What Clairix looks like to another scanner, and what Settings shows") quotes
    // these numbers. The facts are read from Clairix's own manifests, so a permission change that
    // moves them fails here first. Clairix's own scan skips its own package.

    /**
     * Every `<uses-permission>` in [manifests] (paths from the module directory, where Gradle runs
     * unit tests), and every `BIND_*` permission a component there declares itself protected by.
     */
    private fun manifestPermissions(vararg manifests: String): Pair<Set<String>, Set<String>> {
        val ns = "http://schemas.android.com/apk/res/android"
        val requested = HashSet<String>()
        val declared = HashSet<String>()
        for (path in manifests) {
            val file = File(path)
            assertTrue("$path is missing", file.isFile)
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(file)
            val uses = doc.getElementsByTagName("uses-permission")
            for (i in 0 until uses.length) {
                (uses.item(i) as Element).getAttributeNS(ns, "name").takeIf { it.isNotEmpty() }?.let { requested += it }
            }
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val permission = (all.item(i) as Element).getAttributeNS(ns, "permission")
                if (permission.startsWith("android.permission.BIND_")) declared += permission
            }
        }
        return requested to declared
    }

    /**
     * Clairix as the model reads it, from its manifests: src/main, plus src/suite for [suite] (Play
     * and Full), plus src/fullSuite for the Full edition ([full]).
     */
    private fun clairix(installer: String?, adminActive: Boolean, hiddenService: Boolean = false, suite: Boolean = true, full: Boolean = suite): AppFacts {
        val manifests = listOfNotNull(
            "src/main/AndroidManifest.xml",
            "src/suite/AndroidManifest.xml".takeIf { suite },
            "src/fullSuite/AndroidManifest.xml".takeIf { suite && full },
        )
        val (requested, declared) = manifestPermissions(*manifests.toTypedArray())
        return AppFacts(
            name = "app.clairix", system = false, installer = installer, hasLauncher = true,
            requested = requested, declared = declared,
            deviceAdminActive = adminActive,
            hiddenService = hiddenService,
        )
    }

    @Test
    fun theManifestsAreReadAsTheyStand() {
        val full = clairix(installer = null, adminActive = false)
        // Read, not copied: the location, microphone and package permissions come from the files.
        assertTrue(Manifest.permission.RECORD_AUDIO in full.requested)
        assertTrue(Manifest.permission.ACCESS_BACKGROUND_LOCATION in full.requested)
        assertTrue(Manifest.permission.QUERY_ALL_PACKAGES in full.requested)
        // The unlock ledger's admin receiver (suite), and Full's card emulation service.
        assertEquals(setOf(Manifest.permission.BIND_DEVICE_ADMIN, Manifest.permission.BIND_NFC_SERVICE), full.declared)
        assertEquals(setOf(Manifest.permission.BIND_DEVICE_ADMIN), clairix(installer = play, adminActive = false, full = false).declared)
        val comms = clairix(installer = null, adminActive = false, suite = false)
        assertTrue(comms.declared.isEmpty())
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in comms.requested)
    }

    @Test
    fun clairixFullFromGitHubScoresFive() {
        val full = clairix(installer = "com.google.android.packageinstaller", adminActive = false)
        assertEquals(
            listOf(
                "installed from an APK file, not a store",
                "can record audio",
                "can read location in the background",
                "declares a device admin (can resist uninstall)",
            ),
            StalkwareScanner.assess(full).map { it.reason },
        )
        // Its own name is no impostor signal: the genuine app is published outside stores.
        assertEquals(5, score(full))
        assertEquals(Severity.HIGH, severity(full))
        // With the unlock ledger's admin switched on, 7; after a week of the scanner without the app being opened, 9.
        assertEquals(7, score(full.copy(deviceAdminActive = true)))
        assertEquals(9, score(full.copy(deviceAdminActive = true, hiddenService = true)))
    }

    @Test
    fun clairixFromPlayScoresTwoAndItsAdminIsKnown() {
        // The Play edition has no card emulation (src/fullSuite).
        val fromPlay = clairix(installer = play, adminActive = true, full = false)
        assertEquals(
            listOf("can record audio", "can read location in the background", "device admin is active (a known app)"),
            StalkwareScanner.assess(fromPlay).map { it.reason },
        )
        assertEquals(2, score(fromPlay))
        assertEquals(Severity.LOW, severity(fromPlay))
        assertTrue(StalkwareScanner.isVouchedAdmin(fromPlay))
        assertEquals(4, score(fromPlay.copy(hiddenService = true)))
        // It holds no listener, accessibility service or keyboard, and no list vouches for one.
        assertFalse(StalkwareScanner.isVouchedListener(fromPlay))
        assertFalse(StalkwareScanner.isVouchedAccessibility(fromPlay))
        assertFalse(StalkwareScanner.isVouchedIme(fromPlay))
    }

    @Test
    fun clairixCommsFromGitHubScoresThree() {
        val comms = clairix(installer = "com.google.android.packageinstaller", adminActive = false, suite = false)
        assertEquals(listOf("installed from an APK file, not a store", "can record audio"), StalkwareScanner.assess(comms).map { it.reason })
        assertEquals(3, score(comms))
        assertEquals(Severity.MEDIUM, severity(comms))
    }
}
