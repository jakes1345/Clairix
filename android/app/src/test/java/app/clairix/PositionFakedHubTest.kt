package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * While an app supplies the phone's positions the hub does not read as an all-clear:
 * nothing can be confirmed as following, and nothing ruled out, until real fixes return.
 */
class PositionFakedHubTest {

    @Test
    fun fakedPositionsAreNotAnAllClear() {
        val scanning = ScanStatus(scanning = true, nearbyCount = 4)
        val faked = trackerStatus(scanning, emptyList(), emptySet(), faking = true)
        assertEquals("Position faked, following unproven", faked.text)
        assertEquals(StatusTone.CAUTION, faked.tone)
        // Real positions: the usual reading.
        val real = trackerStatus(scanning, emptyList(), emptySet(), faking = false)
        assertEquals("4 devices nearby, none following", real.text)
        assertEquals(StatusTone.CLEAR, real.tone)
        // With the scanner off there is nothing to qualify.
        assertEquals("Scanner off", trackerStatus(ScanStatus(scanning = false), emptyList(), emptySet(), faking = true).text)
    }
}
