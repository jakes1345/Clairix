package app.clairix.ui

import app.clairix.Detection
import app.clairix.EventKind
import app.clairix.FollowConfidence
import app.clairix.Threat
import app.clairix.TrackerType
import app.clairix.analysis.StalkwareScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the safety card shows, and when opening a flagged app's settings asks
 * first: wherever acting on a finding means removing something someone placed.
 */
class SafetyFirstTest {

    private val airTag = TrackerType("airtag", "AirTag", "Apple", Threat.CRITICAL, "Away from its owner")

    private fun detection(following: Boolean, persistent: Boolean, tracker: TrackerType?, threat: Threat) = Detection(
        key = "k", address = "AA:BB", name = "Device", rssi = -60, tracker = tracker, threat = threat, score = 80,
        firstSeen = 0L, lastSeen = 0L, sightings = 12, persistent = persistent, following = following,
        confidence = if (following) FollowConfidence.CONFIRMED else FollowConfidence.NOT_MOVED_ENOUGH,
        displacementM = if (following) 640.0 else 0.0, places = 3, rotations = 0, addresses = 1, approxMetres = null
    )

    private fun app(pkg: String, score: Int) = StalkwareScanner.SuspiciousApp(
        packageName = pkg, appLabel = pkg, score = score, reasons = listOf("has no icon in the app drawer"),
        installTs = 0L, lastUpdateTs = 0L, sideloaded = true, hasLauncherIcon = false, vpnCapable = false,
        notificationListener = false, accessibilityEnabled = false, imeEnabled = false, deviceAdminActive = false,
        installerPackage = null
    )

    private fun result(
        apps: List<StalkwareScanner.SuspiciousApp> = emptyList(),
        imes: List<String> = emptyList(),
        admins: List<String> = emptyList(),
        vouchedAdmins: Set<String> = emptySet()
    ) = StalkwareScanner.Result(
        apps = apps, notificationListeners = emptyList(), accessibilityServices = emptyList(), vpnApps = emptyList(),
        suspiciousImes = imes, activeDeviceAdmins = admins, vouchedAdmins = vouchedAdmins,
        usageAccessGranted = true, scannedAt = 0L
    )

    @Test
    fun theSentencesAreTheFixedOnes() {
        assertEquals(
            "The person who placed this may notice when it stops reporting or when the phone changes. Removing it can tell them you found it.",
            SafetyFirst.NOTICE
        )
        assertEquals(
            "If this is someone you live with or know, consider talking to an advocate or the police before you remove it, and do not do it where they can see.",
            SafetyFirst.ADVOCATE
        )
        assertEquals("Export the evidence first", SafetyFirst.EXPORT)
    }

    @Test
    fun aTrackerOnYouARiderAndAKnownTrackerThatStaysCarryTheCard() {
        assertTrue("a recognised tracker following", SafetyFirst.forDetection(detection(true, true, airTag, Threat.CRITICAL)))
        assertTrue("an unidentified device travelling with you", SafetyFirst.forDetection(detection(true, true, null, Threat.HIGH)))
        assertTrue("a known tracker that keeps showing up", SafetyFirst.forDetection(detection(false, true, airTag, Threat.HIGH)))
    }

    @Test
    fun anUnknownDeviceThatOnlyStaysNearbyOrABriefOneDoesNot() {
        assertFalse("part of the surroundings", SafetyFirst.forDetection(detection(false, true, null, Threat.HIGH)))
        assertFalse("not enough time yet", SafetyFirst.forDetection(detection(false, false, airTag, Threat.HIGH)))
    }

    @Test
    fun stalkerwareFindingsLeadWithTheCardWheneverSomethingCouldBeRemoved() {
        assertFalse("a clean scan", SafetyFirst.forStalkerware(result()))
        assertTrue("a high app", SafetyFirst.forStalkerware(result(apps = listOf(app("com.spy", 7)))))
        assertTrue("a medium app", SafetyFirst.forStalkerware(result(apps = listOf(app("com.maybe", 3)))))
        assertTrue("an unknown keyboard", SafetyFirst.forStalkerware(result(imes = listOf("com.keys"))))
        assertTrue("an unknown device admin", SafetyFirst.forStalkerware(result(admins = listOf("com.admin"))))
        assertFalse(
            "only a device admin the scan vouches for",
            SafetyFirst.forStalkerware(result(admins = listOf("com.google.android.gms"), vouchedAdmins = setOf("com.google.android.gms")))
        )
    }

    @Test
    fun unlockAndEnrolmentAlertsCarryTheCardAndOtherEntriesDoNot() {
        assertTrue(SafetyFirst.forEvent(EventKind.UNLOCK_ANOMALY))
        assertTrue(SafetyFirst.forEvent(EventKind.BIOMETRIC_ENROLLED))
        for (kind in EventKind.entries - setOf(EventKind.UNLOCK_ANOMALY, EventKind.BIOMETRIC_ENROLLED)) {
            assertFalse("$kind", SafetyFirst.forEvent(kind))
        }
    }

    @Test
    fun theLedgerShowsTheCardOnATripOrAnUnlockWhileAsleep() {
        assertFalse(SafetyFirst.forLedger(tripwireTriggered = false, sleepUnlocks = 0))
        assertTrue(SafetyFirst.forLedger(tripwireTriggered = true, sleepUnlocks = 0))
        assertTrue(SafetyFirst.forLedger(tripwireTriggered = false, sleepUnlocks = 2))
    }

    @Test
    fun openingSettingsAsksFirstUnlessTheAppIsVouchedForAndUnflagged() {
        assertTrue("a flagged app", SafetyFirst.gatesSettings(flagged = true, vouched = false))
        assertTrue("a flagged app wearing a known name", SafetyFirst.gatesSettings(flagged = true, vouched = true))
        assertTrue("an unknown keyboard or admin", SafetyFirst.gatesSettings(flagged = false, vouched = false))
        assertFalse("a known admin the scan vouches for", SafetyFirst.gatesSettings(flagged = false, vouched = true))
    }

    @Test
    fun evidenceIsSavedForEveryFlaggedAppThenEveryUnvouchedKeyboardAndAdminOnce() {
        val r = result(
            apps = listOf(app("com.spy", 9), app("com.maybe", 3)),
            imes = listOf("com.keys", "com.spy"),
            admins = listOf("com.admin", "com.google.android.gms"),
            vouchedAdmins = setOf("com.google.android.gms")
        )
        assertEquals(listOf("com.spy", "com.maybe", "com.keys", "com.admin"), SafetyFirst.evidencePackages(r))
        assertTrue(SafetyFirst.evidencePackages(result()).isEmpty())
    }

    private fun ownerResult(status: StalkwareScanner.GrantStatus) = StalkwareScanner.Result(
        apps = emptyList(), notificationListeners = emptyList(), accessibilityServices = emptyList(), vpnApps = emptyList(),
        suspiciousImes = emptyList(), activeDeviceAdmins = listOf("com.google.android.gms"),
        vouchedAdmins = setOf("com.google.android.gms"), usageAccessGranted = true, scannedAt = 0L,
        grants = mapOf(StalkwareScanner.grantKey(StalkwareScanner.GrantKind.PROFILE_OWNER, "com.google.android.gms") to status),
        owners = listOf(StalkwareScanner.Owner("com.google.android.gms", deviceOwner = false))
    )

    @Test
    fun anOwnerNobodyConfirmedLeadsWithTheCardAndIsInTheEvidence() {
        // Family Link runs as Google Play services: a vouched admin, never scored.
        val r = ownerResult(StalkwareScanner.GrantStatus.CONFIRM)
        assertTrue(SafetyFirst.forStalkerware(r))
        assertEquals(listOf("com.google.android.gms"), SafetyFirst.evidencePackages(r))
    }

    @Test
    fun anOwnerTheOwnerConfirmedDoesNot() {
        val r = ownerResult(StalkwareScanner.GrantStatus.YOURS)
        assertFalse(SafetyFirst.forStalkerware(r))
        assertTrue(SafetyFirst.evidencePackages(r).isEmpty())
    }

    private fun listResult(apps: List<StalkwareScanner.SuspiciousApp>, statuses: Map<String, StalkwareScanner.GrantStatus>) =
        StalkwareScanner.Result(
            apps = apps, notificationListeners = emptyList(), accessibilityServices = statuses.keys.toList(),
            vpnApps = emptyList(), suspiciousImes = emptyList(), activeDeviceAdmins = emptyList(), vouchedAdmins = emptySet(),
            usageAccessGranted = true, scannedAt = 0L,
            grants = statuses.mapKeys { StalkwareScanner.grantKey(StalkwareScanner.GrantKind.ACCESSIBILITY, it.key) }
        )

    @Test
    fun aSharedSettingsListAsksAboutTheAppOnItThatNeedsALook() {
        val a11y = StalkwareScanner.GrantKind.ACCESSIBILITY
        val known = StalkwareScanner.GrantStatus.KNOWN
        val unknown = StalkwareScanner.GrantStatus.UNKNOWN
        val clean = listResult(
            emptyList(),
            mapOf(
                "com.passwords" to known,
                "com.mine" to StalkwareScanner.GrantStatus.YOURS,
                "com.oem" to StalkwareScanner.GrantStatus.PRELOADED
            )
        )
        assertNull("every app on the page is known, marked as hers or preloaded", SafetyFirst.sharedPageSubject(clean, a11y, clean.accessibilityServices))

        val unvouched = listResult(emptyList(), mapOf("com.passwords" to known, "com.helper" to unknown))
        assertEquals(
            "the password manager's button opens the page the unknown service is switched off on",
            "com.helper",
            SafetyFirst.sharedPageSubject(unvouched, a11y, unvouched.accessibilityServices)
        )

        val flagged = listResult(listOf(app("com.spy", 9)), mapOf("com.passwords" to known, "com.helper" to unknown, "com.spy" to unknown))
        assertEquals(
            "a flagged app comes before one that is merely unknown",
            "com.spy",
            SafetyFirst.sharedPageSubject(flagged, a11y, flagged.accessibilityServices)
        )
        assertNull(
            "a flagged app on another list does not count",
            SafetyFirst.sharedPageSubject(flagged, a11y, listOf("com.passwords"))
        )
    }
}
