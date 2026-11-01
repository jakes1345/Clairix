package app.clairix.analysis

import app.clairix.analysis.ScannerHealth.StopReason
import app.clairix.analysis.ScannerHealth.StopRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a running scan can hear, and how a stopped scanner is accounted for, apart from the service. */
class ScannerHealthTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun aScanThatIsNotRegisteredIsNotDeaf() {
        assertNull(ScannerHealth.deafCause(scanActive = false, locationOn = false, unfiltered = true, lastPacketAt = 0L, scanStartedAt = 0L, now = t0))
    }

    @Test
    fun locationOffHidesEveryResultWhateverTheFilters() {
        assertEquals(ScannerHealth.LOCATION_OFF, ScannerHealth.deafCause(true, locationOn = false, unfiltered = true, lastPacketAt = t0, scanStartedAt = t0, now = t0 + 1_000L))
        assertEquals(ScannerHealth.LOCATION_OFF, ScannerHealth.deafCause(true, locationOn = false, unfiltered = false, lastPacketAt = t0, scanStartedAt = t0, now = t0 + 1_000L))
    }

    @Test
    fun fiveQuietMinutesOnAnUnfilteredScanIsDeaf() {
        assertNull(ScannerHealth.deafCause(true, true, unfiltered = true, lastPacketAt = 0L, scanStartedAt = t0, now = t0 + ScannerHealth.QUIET_MS))
        assertEquals(ScannerHealth.QUIET, ScannerHealth.deafCause(true, true, unfiltered = true, lastPacketAt = 0L, scanStartedAt = t0, now = t0 + ScannerHealth.QUIET_MS + 1))
        // A packet resets the clock.
        assertNull(ScannerHealth.deafCause(true, true, unfiltered = true, lastPacketAt = t0 + 4 * 60_000L, scanStartedAt = t0, now = t0 + 8 * 60_000L))
    }

    @Test
    fun aFilteredScreenOffScanIsAllowedToBeSilent() {
        assertNull(ScannerHealth.deafCause(true, true, unfiltered = false, lastPacketAt = 0L, scanStartedAt = t0, now = t0 + 3_600_000L))
    }

    @Test
    fun aRestartGetsItsFiveMinutesAgain() {
        // The last packet is an hour old, but the scan was registered afresh a minute ago.
        assertNull(ScannerHealth.deafCause(true, true, unfiltered = true, lastPacketAt = t0, scanStartedAt = t0 + 3_600_000L, now = t0 + 3_600_000L + 60_000L))
    }

    @Test
    fun healthMessagesReplaceOnlyEachOther() {
        assertEquals(ScannerHealth.LOCATION_OFF, ScannerHealth.reconcileError(null, ScannerHealth.LOCATION_OFF))
        assertEquals(ScannerHealth.LOCATION_OFF, ScannerHealth.reconcileError(ScannerHealth.QUIET, ScannerHealth.LOCATION_OFF))
        // A scan failure is more specific than silence and stays up.
        assertEquals("Bluetooth scan failed (2)", ScannerHealth.reconcileError("Bluetooth scan failed (2)", ScannerHealth.QUIET))
        // Recovery clears a health message and nothing else.
        assertNull(ScannerHealth.reconcileError(ScannerHealth.QUIET, null))
        assertEquals("Bluetooth is off", ScannerHealth.reconcileError("Bluetooth is off", null))
        assertNull(ScannerHealth.reconcileError(null, null))
    }

    @Test
    fun theLastPacketTimeIsPublishedCoarsely() {
        // The first packet is published at once.
        assertEquals(t0, ScannerHealth.publishedHeard(published = 0L, heard = t0))
        // Packets every few milliseconds leave the published time alone for half a minute.
        assertEquals(t0, ScannerHealth.publishedHeard(published = t0, heard = t0 + 29_999L))
        assertEquals(t0 + ScannerHealth.HEARD_PUBLISH_STEP_MS, ScannerHealth.publishedHeard(t0, t0 + ScannerHealth.HEARD_PUBLISH_STEP_MS))
        // Silence leaves it where it was, which is what lets the screen show the gap.
        assertEquals(t0, ScannerHealth.publishedHeard(published = t0, heard = t0))
        // A clock set back is followed rather than stuck in the future.
        assertEquals(t0 - 60_000L, ScannerHealth.publishedHeard(published = t0, heard = t0 - 60_000L))
    }

    @Test
    fun aSystemStopGetsOneTimelineRow() {
        // The service wrote it on the way out, or an earlier return to the app did.
        assertTrue(ScannerHealth.alreadyRecorded(StopRecord(StopReason.SYSTEM, t0), t0))
        // Killed outright: dated from the heartbeat, not yet on record.
        assertFalse(ScannerHealth.alreadyRecorded(null, t0))
        assertFalse(ScannerHealth.alreadyRecorded(StopRecord(StopReason.USER, t0 - 3_600_000L), t0))
        assertFalse(ScannerHealth.alreadyRecorded(StopRecord(StopReason.SYSTEM, t0 - 3_600_000L), t0))
    }

    @Test
    fun theTwoStopRowsSayWhoStoppedIt() {
        val system = ScannerHealth.systemStopEvent(t0)
        assertEquals(app.clairix.EventKind.SCAN_STOP, system.kind)
        assertEquals(t0, system.ts)
        assertTrue(system.id, system.id.startsWith(ScannerHealth.SYSTEM_STOP_ID_PREFIX))
        assertEquals("Scanner was stopped by the system at ${ScannerHealth.clock(t0)}", system.title)
        assertEquals(app.clairix.Severity.MEDIUM, system.severity)
        val user = ScannerHealth.userStopEvent(t0)
        assertEquals(app.clairix.EventKind.SCAN_STOP, user.kind)
        assertFalse(user.id, user.id.startsWith(ScannerHealth.SYSTEM_STOP_ID_PREFIX))
        assertEquals("Scanning stopped", user.title)
        assertEquals(app.clairix.Severity.LOW, user.severity)
    }

    @Test
    fun theHeartbeatIsWrittenAtOnceThenEveryMinute() {
        assertTrue(ScannerHealth.heartbeatDue(0L, t0))
        assertFalse(ScannerHealth.heartbeatDue(t0, t0 + 30_000L))
        assertTrue(ScannerHealth.heartbeatDue(t0, t0 + ScannerHealth.HEARTBEAT_EVERY_MS))
        // A clock set back must not silence it for the difference.
        assertTrue(ScannerHealth.heartbeatDue(t0 + 10_000L, t0))
    }

    @Test
    fun aScannerTheOwnerTurnedOffOrThatIsRunningWasNotStoppedByTheSystem() {
        assertNull(ScannerHealth.systemStopAt(scanEnabled = false, scanning = false, lastHeartbeat = t0, lastStop = StopRecord(StopReason.USER, t0 + 1)))
        assertNull(ScannerHealth.systemStopAt(scanEnabled = true, scanning = true, lastHeartbeat = t0, lastStop = null))
    }

    @Test
    fun aRecordedSystemStopGivesItsOwnTime() {
        val stop = StopRecord(StopReason.SYSTEM, t0 + 50_000L)
        assertEquals(t0 + 50_000L, ScannerHealth.systemStopAt(scanEnabled = true, scanning = false, lastHeartbeat = t0, lastStop = stop))
    }

    @Test
    fun aProcessKilledOutrightIsDatedByItsLastHeartbeat() {
        // The owner's stop is older than the heartbeat: she started it again since, and something else ended it.
        assertEquals(t0 + 90_000L, ScannerHealth.systemStopAt(true, false, lastHeartbeat = t0 + 90_000L, lastStop = StopRecord(StopReason.USER, t0)))
        assertEquals(t0, ScannerHealth.systemStopAt(true, false, lastHeartbeat = t0, lastStop = null))
        // Never ran at all: nothing to date, nothing to claim.
        assertNull(ScannerHealth.systemStopAt(true, false, lastHeartbeat = 0L, lastStop = null))
    }

    @Test
    fun anOwnerStopNewerThanTheHeartbeatIsNotBlamedOnTheSystem() {
        assertNull(ScannerHealth.systemStopAt(true, false, lastHeartbeat = t0, lastStop = StopRecord(StopReason.USER, t0 + 1_000L)))
    }

    @Test
    fun theStoppedLineCarriesTheClockTime() {
        val line = ScannerHealth.stoppedBySystemLine(t0)
        assertTrue(line, line.startsWith("Scanner was stopped by the system at ") && line.endsWith(" — tap to resume."))
        assertEquals(ScannerHealth.clock(t0), line.removePrefix("Scanner was stopped by the system at ").removeSuffix(" — tap to resume."))
        assertTrue(ScannerHealth.clock(t0), ScannerHealth.clock(t0).matches(Regex("\\d\\d:\\d\\d")))
    }
}
