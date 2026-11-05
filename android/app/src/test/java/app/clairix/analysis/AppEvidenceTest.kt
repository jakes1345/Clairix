package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * What "Save evidence now" writes: the app, who installed it and when, its
 * access, why it was flagged, its signing certificate's SHA-256 and the time,
 * on one line for the timeline (which is how the evidence report prints it)
 * and one field to a line for the clipboard.
 */
class AppEvidenceTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    // 2026-03-03 10:14:00 UTC and 2026-11-01 14:02:11 UTC.
    private val installed = 1_772_532_840_000L
    private val saved = 1_793_541_731_000L

    private val evidence = AppEvidence(
        packageName = "com.example.tracker",
        label = "System Service",
        severity = Severity.HIGH,
        score = 9,
        installer = null,
        namedInstaller = "com.android.vending",
        installedAt = installed,
        updatedAt = installed,
        access = listOf("accessibility service on (reads the screen)", "device admin active (can block its own uninstall)"),
        permissions = listOf("location all the time", "read text messages"),
        reasons = listOf("installed from a computer (adb) or an unknown source", "has no icon in the app drawer"),
        signers = listOf("AB:CD:EF"),
        savedAt = saved
    )

    @Test
    fun theCertificateFingerprintIsSha256InColonHex() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            AppEvidence.sha256("abc".toByteArray())
        )
    }

    @Test
    fun onlyGrantedSensitivePermissionsAreNamedInTheTablesOrder() {
        val requested = arrayOf(
            "android.permission.READ_SMS",
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.ACCESS_FINE_LOCATION"
        )
        // 2 is REQUESTED_PERMISSION_GRANTED; 3 adds REQUESTED_PERMISSION_REQUIRED.
        val flags = intArrayOf(2, 0, 2, 3)
        assertEquals(listOf("precise location", "read text messages"), AppEvidence.grantedPermissions(requested, flags))
        assertTrue(AppEvidence.grantedPermissions(null, null).isEmpty())
        // Flags shorter than the list: what has no flag is not taken as granted.
        assertEquals(listOf("read text messages"), AppEvidence.grantedPermissions(requested, intArrayOf(2)))
    }

    @Test
    fun timesAreUtcFirstWithThePhonesOwnTimeBeside() {
        assertEquals("2026-11-01 14:02:11 UTC (2026-11-01 14:02:11 UTC on this phone)", AppEvidence.stamp(saved, utc))
        assertEquals(
            "2026-11-01 14:02:11 UTC (2026-11-01 15:02:11 CET on this phone)",
            AppEvidence.stamp(saved, TimeZone.getTimeZone("Europe/Berlin"))
        )
    }

    @Test
    fun theTimelineEntryIsOneLineWithEveryField() {
        val e = evidence.timelineEvent(utc)
        assertEquals(EventKind.EVIDENCE, e.kind)
        assertEquals(Severity.HIGH, e.severity)
        assertEquals(saved, e.ts)
        assertTrue(e.id.startsWith(AppEvidence.ID_PREFIX + "com.example.tracker@"))
        assertEquals("Evidence saved: System Service", e.title)
        assertFalse("the report prints the detail on one line", e.detail.contains('\n'))
        for (part in listOf(
            "App: System Service (com.example.tracker)",
            "Flagged: HIGH, score 9",
            "Installed: 2026-03-03 10:14:00 UTC",
            "Installed by: none recorded (adb, root or an unknown source)",
            "Installer it names: com.android.vending",
            "Access: accessibility service on (reads the screen), device admin active (can block its own uninstall)",
            "Permissions granted: location all the time, read text messages",
            "Reasons: installed from a computer (adb) or an unknown source; has no icon in the app drawer",
            "Signing certificate SHA-256: AB:CD:EF",
            "Saved: 2026-11-01 14:02:11 UTC"
        )) assertTrue("missing \"$part\" in ${e.detail}", e.detail.contains(part))
        assertFalse("an update at install time is not repeated", e.detail.contains("Last updated"))
    }

    @Test
    fun theClipboardCopyIsOneFieldToALine() {
        val lines = evidence.text(utc).lines()
        assertEquals("Clairix evidence: an app on this phone", lines.first())
        assertTrue(lines.contains("App: System Service (com.example.tracker)"))
        assertTrue(lines.contains("Signing certificate SHA-256: AB:CD:EF"))
        assertTrue(lines.any { it.startsWith("Saved: 2026-11-01 14:02:11 UTC") })
    }

    @Test
    fun anUnflaggedAppSavesWhatIsKnownAndSaysWhatIsNot() {
        val plain = evidence.copy(
            severity = null, score = null, installer = "com.android.vending", namedInstaller = null,
            installedAt = null, updatedAt = null, access = emptyList(), permissions = emptyList(),
            reasons = emptyList(), signers = emptyList()
        )
        val e = plain.timelineEvent(utc)
        assertEquals(Severity.LOW, e.severity)
        assertFalse(e.detail.contains("Flagged"))
        assertFalse(e.detail.contains("Reasons"))
        assertTrue(e.detail.contains("Installed: unknown"))
        assertTrue(e.detail.contains("Installed by: com.android.vending"))
        assertTrue(e.detail.contains("Access: none of the special access Clairix checks"))
        assertTrue(e.detail.contains("Signing certificate SHA-256: not readable"))
    }

    @Test
    fun theBatteryExemptionIsRecordedOnItsOwnLine() {
        assertTrue(evidence.copy(batteryExempt = true).timelineEvent(utc).detail
            .contains("Battery optimisation: exempt (Android never pauses it in the background)"))
        assertTrue(evidence.copy(batteryExempt = false).text(utc).lines().contains("Battery optimisation: not exempt"))
        assertTrue(evidence.copy(batteryExempt = null).text(utc).lines().contains("Battery optimisation: not readable"))
    }

    @Test
    fun theWeeksTrafficIsRecordedWithWhereTheCountsComeFrom() {
        val usage = DataUsage.Summary(List(DataUsage.DAYS) { DataUsage.Day(100_000L, 2_000_000L) })
        val line = evidence.copy(dataUsage = usage).text(utc).lines().first { it.startsWith("Data, last 7 days: ") }
        assertTrue(line.contains("sent 14.0 MB and received 700.0 kB in 7 days, sending on 7 of them"))
        assertTrue(line.contains("blocks of an hour or two"))
        assertTrue(evidence.text(utc).lines().any { it.startsWith("Data, last 7 days: not readable") })
    }

    @Test
    fun whatIsOnlyAskedForIsKeptApartFromWhatIsGranted() {
        val requested = arrayOf(
            "android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.READ_SMS", "android.permission.INTERNET",
        )
        val flags = intArrayOf(2, 0, 3, 2)
        assertEquals(listOf("microphone", "read text messages"), AppEvidence.grantedPermissions(requested, flags))
        assertEquals(listOf("camera"), AppEvidence.requestedOnlyPermissions(requested, flags))
        assertTrue(AppEvidence.requestedOnlyPermissions(requested, null).isEmpty())
        val e = evidence.copy(requestedOnly = listOf("camera", "read the call log")).timelineEvent(utc)
        assertTrue(e.detail, e.detail.contains("Asked for, not granted: camera, read the call log"))
        assertFalse(evidence.timelineEvent(utc).detail.contains("Asked for, not granted"))
    }
}
