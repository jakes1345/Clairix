package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every place in the app has a tour page, so nothing is left to be found by accident. */
class TourTest {

    @Test
    fun theFullTourVisitsEveryCategoryToolCommsPageAndActivityTab() {
        val stops = tourStops(fullAccess = true)
        val ids = stops.map { it.id }.toSet()
        assertEquals("tour ids must be unique", stops.size, ids.size)
        ScanCategory.entries.forEach { assertTrue("missing ${it.name}", "cat_${it.name}" in ids) }
        TrackerAction.entries.forEach { assertTrue("missing ${it.name}", "tool_${it.name}" in ids) }
        CommsAction.entries.forEach { assertTrue("missing ${it.name}", "comms_${it.name}" in ids) }
        ACTIVITY_SUB_TABS.forEach { assertTrue("missing $it", "act_$it" in ids) }
        assertTrue("sos" in ids && "settings" in ids && "drive" in ids && "comms" in ids)
        stops.forEach { s -> assertTrue("${s.id} has no text", s.what.length > 20 && s.how.length > 20) }
    }

    @Test
    fun theCommsOnlyTourHasNothingTheCommsBuildLacks() {
        val ids = tourStops(fullAccess = false).map { it.id }
        assertTrue(ids.none { it.startsWith("cat_") || it.startsWith("tool_") || it.startsWith("comms_") || it.startsWith("act_") || it == "drive" })
        assertTrue("comms" in ids && "sos" in ids && "settings" in ids)
    }
}
