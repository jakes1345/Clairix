package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theSettingsStopFollowsUpOnboardingWhileNoPasscodeIsSet() {
        fun settings(fullAccess: Boolean, passcodeSet: Boolean, offered: Boolean): String =
            tourStops(fullAccess, passcodeSet = passcodeSet, lockOffered = offered).single { it.id == "settings" }.how
        assertTrue(settings(fullAccess = true, passcodeSet = false, offered = true).startsWith("You started without a passcode"))
        assertTrue(settings(fullAccess = true, passcodeSet = false, offered = false).startsWith("Clairix has no passcode yet"))
        assertTrue(settings(fullAccess = false, passcodeSet = false, offered = true).startsWith("You started without a passcode"))
        // Once there is a passcode the stop says nothing about setting one.
        assertFalse(settings(fullAccess = true, passcodeSet = true, offered = true).contains("set one under App passcode"))
        assertTrue(settings(fullAccess = true, passcodeSet = true, offered = false).startsWith("With a passcode set, Patterns"))
        assertTrue(settings(fullAccess = false, passcodeSet = true, offered = true).startsWith("This tour is in Settings"))
    }

    @Test
    fun onboardingRecordsThatItOfferedAPasscode() {
        val context = PrefsContext()
        assertFalse(isLockOffered(context))
        markLockOffered(context)
        assertTrue(isLockOffered(context))
        assertTrue(context.prefs("prefs").getBoolean("onboarding_lock_offered", false))
    }

    @Test
    fun theCommsOnlyTourHasNothingTheCommsBuildLacks() {
        val ids = tourStops(fullAccess = false).map { it.id }
        assertTrue(ids.none { it.startsWith("cat_") || it.startsWith("tool_") || it.startsWith("comms_") || it.startsWith("act_") || it == "drive" })
        assertTrue("comms" in ids && "sos" in ids && "settings" in ids)
    }
}
