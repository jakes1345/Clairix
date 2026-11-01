package app.clairix.analysis

import app.clairix.HealthFact
import app.clairix.PhoneHealth
import app.clairix.PhoneHealthFinding
import app.clairix.Severity
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the stalkerware check and the grant verdicts put in Phone check, and so on the hub. */
class SpywareHealthTest {

    private fun app(pkg: String, label: String, score: Int, knownAs: String? = null, reasons: List<String> = listOf("has no icon in the app drawer")) =
        StalkwareScanner.SuspiciousApp(
            pkg, label, score, reasons, 0L, 0L, sideloaded = true, hasLauncherIcon = false, vpnCapable = false,
            notificationListener = false, accessibilityEnabled = true, imeEnabled = false, deviceAdminActive = false,
            installerPackage = null, knownAs = knownAs,
        )

    private fun result(vararg apps: StalkwareScanner.SuspiciousApp) = StalkwareScanner.Result(
        apps = apps.toList(), notificationListeners = emptyList(), accessibilityServices = emptyList(), vpnApps = emptyList(),
        suspiciousImes = emptyList(), activeDeviceAdmins = emptyList(), vouchedAdmins = emptySet(),
        usageAccessGranted = false, scannedAt = 42L,
    )

    @Test
    fun anOwnerIsOneGrantPerPackageWhateverAdminReceiversItHolds() {
        // Family Link on a Pixel: Play services holds Find My Device's admin and the supervision receiver.
        val gms = "com.google.android.gms"
        val admins = listOf(
            gms to "com.google.android.gms.mdm.receivers.MdmDeviceAdminReceiver",
            gms to "com.google.android.gms.kids.account.receiver.ProfileOwnerReceiver",
            "com.example.admin" to "com.example.admin.A",
            "com.example.admin" to "com.example.admin.B",
        )
        var asked = 0
        val grants = SpywareHealth.adminGrants(admins) { pkg -> asked++; if (pkg == gms) false else null }
        assertEquals(
            listOf(
                SpywareHealth.AdminGrant(GrantKind.PROFILE_OWNER, gms, null),
                SpywareHealth.AdminGrant(GrantKind.DEVICE_ADMIN, "com.example.admin", "com.example.admin.A"),
                SpywareHealth.AdminGrant(GrantKind.DEVICE_ADMIN, "com.example.admin", "com.example.admin.B"),
            ),
            grants,
        )
        assertEquals("asked once per package", 2, asked)
        assertEquals(
            listOf(SpywareHealth.AdminGrant(GrantKind.DEVICE_OWNER, "com.airwatch.androidagent", null)),
            SpywareHealth.adminGrants(listOf("com.airwatch.androidagent" to "X", "com.airwatch.androidagent" to "Y")) { true },
        )
    }

    @Test
    fun grantedRowsAndTheStockKeyboardAreInformationNotAPassedCheck() {
        val mine = SpywareHealth.grantedFact(GrantKind.ACCESSIBILITY, "Button Mapper", "com.example.mapper", "Svc", GrantStatus.YOURS)
        assertNull(mine.severity)
        assertTrue(SpywareHealth.informational(mine.id))
        val owner = SpywareHealth.grantedFact(GrantKind.DEVICE_OWNER, "Intune", "com.microsoft.windowsintune.companyportal", null, GrantStatus.YOURS)
        assertTrue(SpywareHealth.informational(owner.id))
        assertTrue(SpywareHealth.informational(KeyboardExposure.STOCK_INTERNET_FACT_ID))
        // A keyboard with no internet access, or one flagged, is not information.
        assertFalse(SpywareHealth.informational("keyboard"))
        assertFalse(SpywareHealth.informational(SpywareHealth.grantId(SpywareHealth.GRANT_PREFIX, GrantKind.ACCESSIBILITY, "com.spy")))
    }

    @Test
    fun onlyHighAppsBecomeFindingsAndKnownStalkerwareIsCritical() {
        val findings = SpywareHealth.appFindings(result(
            app("com.mxspy", "Copy9", 7, knownAs = "TheTruthSpy"),
            app("com.example.hidden", "Hidden", 6),
            app("com.example.medium", "Medium", 3),
        ))
        assertEquals(listOf("spy:com.mxspy", "spy:com.example.hidden"), findings.map { it.id })
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals("Copy9 is known stalkerware: TheTruthSpy", findings[0].title)
        assertEquals(Severity.HIGH, findings[1].severity)
        assertEquals("Hidden looks like stalkerware", findings[1].title)
        assertTrue(findings[1].detail.contains("has no icon in the app drawer"))
        assertEquals(emptyList<PhoneHealthFinding>(), SpywareHealth.appFindings(null))
    }

    @Test
    fun mergingReplacesTheAppFindingsAndCountsAnAppOnce() {
        val a11y = SpywareHealth.grantFinding(GrantKind.ACCESSIBILITY, "Hidden", "com.example.hidden", "com.example.hidden.Svc", GrantStatus.UNKNOWN, Severity.HIGH)
        val other = SpywareHealth.grantFinding(GrantKind.NOTIFICATION_LISTENER, "Other", "com.example.other", null, GrantStatus.UNKNOWN, Severity.HIGH)
        val stale = PhoneHealthFinding("spy:com.gone", Severity.HIGH, "Stalkerware", "Gone looks like stalkerware", "")
        val root = PhoneHealthFinding("pkg_com.topjohnwu.magisk", Severity.MEDIUM, "Root", "Magisk is installed", "")
        val health = PhoneHealth(findings = listOf(root, a11y, other, stale), findingsScannedTs = 1L)

        val merged = SpywareHealth.merge(health, SpywareHealth.appFindings(result(app("com.example.hidden", "Hidden", 8))), 42L)
        val ids = merged.findings.map { it.id }
        // The app's own finding stands for its accessibility grant; the stale app is gone; the rest stay.
        assertTrue("spy:com.example.hidden" in ids)
        assertFalse(a11y.id in ids)
        assertFalse("spy:com.gone" in ids)
        assertTrue(other.id in ids && root.id in ids)
        // Worst first, and the check's time is recorded for the hub.
        assertEquals(Severity.MEDIUM, merged.findings.last().severity)
        assertEquals(42L, merged.spywareScannedTs)
        assertEquals(1L, merged.findingsScannedTs)
    }

    @Test
    fun grantIdsCarryTheirPackage() {
        assertEquals("grant:a11y:com.foo/com.foo.Svc", SpywareHealth.grantId(SpywareHealth.GRANT_PREFIX, GrantKind.ACCESSIBILITY, "com.foo", "com.foo.Svc"))
        assertEquals("com.foo", SpywareHealth.packageOf("grant:a11y:com.foo/com.foo.Svc"))
        assertEquals("com.foo", SpywareHealth.packageOf("granted:owner:com.foo"))
        assertEquals("com.foo", SpywareHealth.packageOf("spy:com.foo"))
        assertNull(SpywareHealth.packageOf("pkg_com.foo"))
    }

    @Test
    fun anOwnerFindingSaysWhatAnOwnerCanDoAndAsksWhoEnrolledIt() {
        val f = SpywareHealth.grantFinding(GrantKind.PROFILE_OWNER, "Google Play services", "com.google.android.gms", null, GrantStatus.CONFIRM, Severity.HIGH)
        assertEquals("This phone is managed by Google Play services", f.title)
        assertEquals(Severity.HIGH, f.severity)
        assertTrue(f.detail.contains("Family Link"))
        assertTrue(f.detail.contains("install certificates"))
        assertTrue(f.detail.contains("Did you enrol this phone yourself"))
        val mdm = SpywareHealth.grantFinding(GrantKind.DEVICE_ADMIN, "Intune", "com.microsoft.windowsintune.companyportal", "x.Admin", GrantStatus.CONFIRM, Severity.MEDIUM)
        assertEquals("Intune — work or school management", mdm.title)
        assertEquals(Severity.MEDIUM, mdm.severity)
    }

    @Test
    fun aVouchedOrMarkedGrantIsListedWithNoSeverity() {
        val known = SpywareHealth.grantedFact(GrantKind.ACCESSIBILITY, "TalkBack", "com.google.android.marvin.talkback", "x.Svc", GrantStatus.KNOWN)
        assertNull(known.severity)
        assertEquals("Access granted · known app", known.value)
        assertEquals("TalkBack (accessibility service)", known.label)
        assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, known.fixAction)
        val mine = SpywareHealth.grantedFact(GrantKind.ACCESSIBILITY, "Button Mapper", "com.example.buttonmapper", "x.Svc", GrantStatus.YOURS)
        assertNull(mine.severity)
        assertEquals("Access granted · marked as yours", mine.value)
        assertTrue(mine.detail!!.contains("undo it"))
        val owner = SpywareHealth.grantedFact(GrantKind.DEVICE_OWNER, "Intune", "com.microsoft.windowsintune.companyportal", null, GrantStatus.YOURS)
        assertEquals("Manages this phone · you enrolled it", owner.value)
        assertTrue(PhoneHealth(facts = listOf(known, mine, owner), findingsScannedTs = 1L).problems.isEmpty())
    }

    @Test
    fun theProfilesRowSaysWhatIsNotScanned() {
        val none = SpywareHealth.profilesFact(others = 0, secureFolder = false, sdk = 34)
        assertNull(none.severity)
        assertEquals("None Clairix can see", none.value)
        assertTrue(none.detail!!.contains("work profile"))
        assertFalse(none.detail!!.contains("Private space →"))

        val galaxy = SpywareHealth.profilesFact(others = 1, secureFolder = true, sdk = 35)
        assertEquals("1 Clairix can see, not scanned", galaxy.value)
        assertTrue(galaxy.detail!!.contains("1 other profile "))
        assertTrue(galaxy.detail!!.contains("Secure Folder is installed"))
        assertTrue(galaxy.detail!!.contains("Settings → Security & privacy → Private space"))
        assertNull(galaxy.severity)

        // Clairix installed in a work profile checks none of the main apps: worth a look, not information.
        val inWork = SpywareHealth.profilesFact(others = 1, secureFolder = false, sdk = 34, ownProfile = "a work profile")
        assertEquals(Severity.MEDIUM, inWork.severity)
        assertEquals("Clairix runs in a work profile; the main profile is not checked", inWork.value)
        assertTrue(inWork.detail!!.startsWith("Clairix is installed in a work profile"))
    }

    @Test
    fun theCertificatesRowCountsRootsAddedByHand() {
        fun ca(cn: String, source: String, severity: Severity) =
            SuspiciousCa("CN=Issuer", "CN=$cn", "AA:BB", 0L, 1L, severity, source, "reason")
        /** An audit that read both sources. */
        fun read(roots: List<SuspiciousCa>) = CaAudit(roots, userStoreRead = true, trustManagerRead = true)
        val clean = CaAuditScanner.fact(read(emptyList()))
        assertNull(clean.severity)
        assertEquals("None added by hand", clean.value)
        // A system root missing from the representative bundle list stays on the Certificates page.
        assertNull(CaAuditScanner.fact(read(listOf(ca("Vendor Root", "system", Severity.MEDIUM)))).severity)

        val user = CaAuditScanner.fact(read(listOf(ca("mitmproxy", "user", Severity.HIGH), ca("Vendor Root", "system", Severity.MEDIUM))))
        assertEquals(Severity.HIGH, user.severity)
        assertEquals("1 added by hand", user.value)
        assertTrue(user.detail!!.contains("mitmproxy") && !user.detail!!.contains("Vendor Root"))
        assertEquals(android.provider.Settings.ACTION_SECURITY_SETTINGS, user.fixAction)

        val both = CaAuditScanner.fact(read(listOf(ca("A", "user", Severity.HIGH), ca("B", "unknown", Severity.HIGH))))
        assertEquals("1 added by hand, 1 from outside the stores", both.value)
        assertTrue(both.detail!!.contains("modified trust manager"))
        val health = PhoneHealth(facts = listOf(both, HealthFact("lock", "Access", "Screen lock", "PIN")), findingsScannedTs = 1L)
        assertEquals(listOf("user_ca"), health.problems.map { it.id })
    }
}
