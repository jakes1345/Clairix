package app.clairix.security

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock screen's keypad shrinks on a short window so the Unlock button that
 * every code under 12 digits needs stays on screen, and never below a touch
 * target. (Scrolling covers what is left, at large text; that needs a device.)
 */
class LockScreenLayoutTest {

    @Test
    fun aTallWindowKeepsTheFullSizeKeys() {
        assertEquals(72.dp, lockKeySize(800.dp))
        assertEquals(72.dp, lockKeySize(668.dp))
    }

    @Test
    fun aShortWindowGetsSmallerKeysSoTheWholeScreenFits() {
        // About 380 dp of the screen is not keys. With the smallest keys the whole
        // screen fits a window of 572 dp; a 640 dp phone (about 568 dp between its
        // bars) is 4 dp short, which the screen's scroll covers.
        val chrome = 380.dp
        val key = lockKeySize(572.dp)
        assertEquals(48.dp, key)
        assertTrue("taller than the window: ${chrome + key * 4}", chrome + key * 4 <= 572.dp)
        assertEquals(48.dp, lockKeySize(568.dp))
        assertEquals(55.dp, lockKeySize(600.dp))
    }

    @Test
    fun keysNeverShrinkBelowATouchTarget() {
        assertEquals(48.dp, lockKeySize(400.dp))
        assertEquals(48.dp, lockKeySize(0.dp))
    }
}
