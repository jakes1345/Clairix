package app.clairix.comms.mesh

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import app.clairix.Store
import app.clairix.TestStoreCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * How hard the offline mesh works: low power with nothing to do, full pace with
 * something to carry, a conversation open or a meeting just now, and no
 * long-range advertisement while the phone is still.
 */
class MeshPacingTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val rnd = Random(7)

    @Test
    fun nothingToCarryNoConversationAndNoRecentMeetingIsIdle() {
        assertFalse(MeshPacing.busy(carrying = 0, conversationOpen = false, lastMeetingAt = 0L, now = now))
        assertFalse(MeshPacing.busy(carrying = 0, conversationOpen = false, lastMeetingAt = now - 10 * min, now = now))
    }

    @Test
    fun somethingToCarryAConversationOpenOrAMeetingInTheLastTenMinutesIsBusy() {
        assertTrue(MeshPacing.busy(carrying = 1, conversationOpen = false, lastMeetingAt = 0L, now = now))
        assertTrue(MeshPacing.busy(carrying = 0, conversationOpen = true, lastMeetingAt = 0L, now = now))
        assertTrue(MeshPacing.busy(carrying = 0, conversationOpen = false, lastMeetingAt = now - 10 * min + 1, now = now))
        assertEquals(10 * min, MeshPacing.AFTER_MEETING_MS)
    }

    @Test
    fun idleListensAndAdvertisesAtLowPowerAndBusyRunsWhatTheMeshAlwaysRan() {
        assertEquals(
            MeshPacing.Plan(ScanSettings.SCAN_MODE_LOW_POWER, AdvertiseSettings.ADVERTISE_MODE_LOW_POWER, longRange = true),
            MeshPacing.plan(busy = false, still = false, longRangeSupported = true)
        )
        assertEquals(
            MeshPacing.Plan(ScanSettings.SCAN_MODE_BALANCED, AdvertiseSettings.ADVERTISE_MODE_BALANCED, longRange = true),
            MeshPacing.plan(busy = true, still = false, longRangeSupported = true)
        )
    }

    @Test
    fun theLongRangeSetStopsWhileThePhoneIsStillBusyOrNotAndNeverRunsWithoutTheMode() {
        assertFalse(MeshPacing.plan(busy = true, still = true, longRangeSupported = true).longRange)
        assertFalse(MeshPacing.plan(busy = false, still = true, longRangeSupported = true).longRange)
        assertFalse(MeshPacing.plan(busy = true, still = false, longRangeSupported = false).longRange)
        // Still or not, the ordinary radio follows the work, not the motion.
        assertEquals(ScanSettings.SCAN_MODE_BALANCED, MeshPacing.plan(busy = true, still = true, longRangeSupported = true).scanMode)
        assertTrue(MeshPacing.STILL_AFTER_MS >= 4 * min)
    }

    // ── The store's part: what it carries, and telling the radio when that grows ──

    private fun store() = MeshStore(Store(File(Files.createTempDirectory("mesh").toFile().apply { deleteOnExit() }, "m.json"), cipher = TestStoreCipher))
    private fun tag(): ByteArray = ByteArray(MeshProtocol.TAG_LEN).also { rnd.nextBytes(it) }
    private fun bundle(createdMs: Long = now) = MeshProtocol.newBundle(ByteArray(300).also { rnd.nextBytes(it) }, tag(), createdMs)!!

    @Test
    fun addingABundleTellsTheRadioOnceAndADuplicateDoesNot() {
        val s = store()
        var told = 0
        s.onChange = { told++ }
        val b = bundle()
        assertTrue(s.add(b, now))
        assertEquals(1, told)
        assertFalse(s.add(b, now))
        assertEquals(1, told)
        assertTrue(s.addOwn("482913605", "m1", bundle(), now))
        assertEquals(2, told)
        assertFalse(s.addOwn("482913605", "m1", bundle(), now))
        assertEquals(2, told)
    }

    @Test
    fun whatIsCarriedPastItsTimeNoLongerKeepsTheMeshBusy() {
        val s = store()
        assertEquals(0, s.carrying(now))
        assertTrue(s.add(bundle(createdMs = now), now))
        assertEquals(1, s.carrying(now))
        assertEquals(0, s.carrying(now + MeshProtocol.TTL_MS + 1))
        assertFalse(MeshPacing.busy(s.carrying(now + MeshProtocol.TTL_MS + 1), conversationOpen = false, lastMeetingAt = 0L, now = now))
    }
}
