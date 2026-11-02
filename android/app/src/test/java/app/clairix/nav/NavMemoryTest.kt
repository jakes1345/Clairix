package app.clairix.nav

import android.content.ComponentCallbacks2
import app.clairix.nav.NavMemory.Trim
import org.junit.Assert.assertEquals
import org.junit.Test

/** What navigation gives back when Android says memory is short. */
@Suppress("DEPRECATION") // the running levels are still delivered on 12 and 13
class NavMemoryTest {

    @Test
    fun aHiddenScreenDropsTheDecodedTilesOnly() {
        assertEquals(Trim(tiles = true, routing = false), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, navigating = false))
    }

    @Test
    fun inTheBackgroundTheRoutingMemoryGoesTooUnlessADriveIsOn() {
        assertEquals(Trim(true, true), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, navigating = false))
        assertEquals(Trim(true, true), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, navigating = false))
        // Map matching and rerouting use it every second during a drive.
        assertEquals(Trim(true, false), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, navigating = true))
    }

    @Test
    fun inFrontOnlyCriticalShortageTakesTheRoutingMemoryAndNeverTheVisibleTiles() {
        assertEquals(Trim(false, false), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, navigating = false))
        assertEquals(Trim(false, false), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, navigating = false))
        assertEquals(Trim(false, true), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, navigating = false))
        assertEquals(Trim(false, false), NavMemory.plan(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, navigating = true))
    }
}
