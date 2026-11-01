package app.clairix.nav

import app.clairix.nav.DriveRefresh.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Drive screen's minute loop: nothing fetched for a screen in the background, unless a drive is on. */
class DriveRefreshTest {

    private val nothing = Plan(live = false, providers = false, workZones = false)

    @Test
    fun theLoopSleepsInTheBackgroundUnlessADriveIsOn() {
        assertFalse(DriveRefresh.awake(navigating = false, resumed = false))
        assertTrue(DriveRefresh.awake(navigating = false, resumed = true))
        assertTrue(DriveRefresh.awake(navigating = true, resumed = false))
    }

    @Test
    fun theMapInFrontGetsLiveTraffic() {
        assertEquals(Plan(live = true, providers = false, workZones = false),
            DriveRefresh.plan(hasSession = true, navigating = false, previewing = false, resumed = true))
    }

    @Test
    fun aPreviewedRouteInFrontGetsIncidentsAndWorkZones() {
        assertEquals(Plan(live = true, providers = true, workZones = true),
            DriveRefresh.plan(hasSession = true, navigating = false, previewing = true, resumed = true))
    }

    @Test
    fun nothingIsFetchedForAScreenInTheBackground() {
        // The old loop kept fetching incidents and work zones for a preview left open behind another app.
        assertEquals(nothing, DriveRefresh.plan(hasSession = true, navigating = false, previewing = true, resumed = false))
        assertEquals(nothing, DriveRefresh.plan(hasSession = true, navigating = false, previewing = false, resumed = false))
    }

    @Test
    fun aDriveKeepsItsWorkZonesFreshInFrontOrNot() {
        // Traffic during a drive is the navigator's own loop.
        val driving = Plan(live = false, providers = false, workZones = true)
        assertEquals(driving, DriveRefresh.plan(hasSession = true, navigating = true, previewing = false, resumed = false))
        assertEquals(driving, DriveRefresh.plan(hasSession = true, navigating = true, previewing = true, resumed = true))
    }

    @Test
    fun withoutAMapNothingIsFetched() {
        assertEquals(nothing, DriveRefresh.plan(hasSession = false, navigating = false, previewing = true, resumed = true))
        assertEquals(nothing, DriveRefresh.plan(hasSession = false, navigating = true, previewing = false, resumed = true))
    }
}
