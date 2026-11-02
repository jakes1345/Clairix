package app.clairix.analysis

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind the cell reading: which SIM's cell, and whether a call is up. */
class CellMonitorTest {

    @Test
    fun theRegisteredCellOnTheDataSimIsTheOneRead() {
        // Dual SIM: T-Mobile US on the first slot, Verizon on the second, data on Verizon.
        assertEquals(1, CellMonitor.pickForPlmn(listOf("310260", "311480"), "311480"))
        assertEquals(0, CellMonitor.pickForPlmn(listOf("310260", "311480"), "310260"))
    }

    @Test
    fun withoutAMatchOrAChoiceTheFirstCellIsRead() {
        assertEquals(0, CellMonitor.pickForPlmn(listOf("310260", "311480"), null))
        assertEquals(0, CellMonitor.pickForPlmn(listOf("310260", "311480"), ""))
        assertEquals(0, CellMonitor.pickForPlmn(listOf("310260", "311480"), "26201"))
        assertEquals(0, CellMonitor.pickForPlmn(listOf("311480"), "310260"))
        // A cell whose identity withheld the network code cannot be the match.
        assertEquals(1, CellMonitor.pickForPlmn(listOf(null, "311480"), "311480"))
    }

    @Test
    fun aCallIsInCallInCommunicationRingingOrScreening() {
        assertTrue(CellMonitor.isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(CellMonitor.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(CellMonitor.isCallMode(AudioManager.MODE_RINGTONE))
        assertTrue(CellMonitor.isCallMode(AudioManager.MODE_CALL_SCREENING))
        assertFalse(CellMonitor.isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(CellMonitor.isCallMode(AudioManager.MODE_INVALID))
    }

    @Test
    fun aReadingIsInACallWhenTheModeSaysSoOrACallJustEnded() {
        val now = 5_000_000L
        // Up now, by the mode read at the reading or by the watcher.
        assertTrue(CellMonitor.callContext(AudioManager.MODE_IN_CALL, active = false, lastCallAt = 0L, now = now))
        assertTrue(CellMonitor.callContext(AudioManager.MODE_NORMAL, active = true, lastCallAt = now - 1_000L, now = now))
        // A twelve-second call that ended twenty seconds ago, between two polls: the mode
        // is normal again at this reading, but the phone may still be on 3G.
        assertTrue(CellMonitor.callContext(AudioManager.MODE_NORMAL, active = false, lastCallAt = now - 20_000L, now = now))
        // No call, or one long enough ago that the catcher's own grace covers the rest.
        assertFalse(CellMonitor.callContext(AudioManager.MODE_NORMAL, active = false, lastCallAt = 0L, now = now))
        assertFalse(CellMonitor.callContext(null, active = false, lastCallAt = now - CellMonitor.RECENT_CALL_MS - 1, now = now))
    }
}
