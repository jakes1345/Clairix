package app.clairix

import app.clairix.analysis.AccountChecklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the Health tab says about the accounts checklist, where the checklist
 * sits under Phone check, and what Settings says discreet mode does in each
 * edition.
 */
@RunWith(RobolectricTestRunner::class)
class SurvivorScreensTest {

    private val res = RuntimeEnvironment.getApplication().resources

    private val date: (Long) -> String = { "at $it" }

    @Test
    fun theAccountsChecklistIsPhoneChecksFourthTab() {
        assertEquals("Accounts", ScanCategory.PHONE.subTabs[ACCOUNTS_SUB_TAB])
        assertEquals(listOf("Health", "Stalkerware", "Certificates", "Accounts"), ScanCategory.PHONE.subTabs)
    }

    @Test
    fun theHealthTabSaysWhenTheChecklistWasLastDone() {
        assertTrue(accountChecklistLine(AccountChecklist.Progress(0, 10, null), date).startsWith("Not done yet."))
        assertEquals("4 of 10 checked. Last ticked at 500.", accountChecklistLine(AccountChecklist.Progress(4, 10, 500L), date))
        assertEquals("All 10 checked. Last ticked at 900.", accountChecklistLine(AccountChecklist.Progress(10, 10, 900L), date))
    }

    @Test
    fun aRecoveryFileSavedInDiscreetModeIsNotNamedForClairix() {
        assertEquals("clairix-recovery-2026-11-01.clxr", recoveryFileName(discreet = false, day = "2026-11-01"))
        val discreet = recoveryFileName(discreet = true, day = "2026-11-01")
        assertFalse(discreet, discreet.contains("clairix", ignoreCase = true) || discreet.contains("recovery", ignoreCase = true))
        assertTrue(discreet, discreet.contains("2026-11-01"))
    }

    @Test
    fun settingsSaysThePlayEditionKeepsTheClairixIcon() {
        val play = discreetModeSubtitle(res, on = true, disguiseAllowed = false, disguiseLabel = "Compass")
        assertTrue(play, play.contains("Google Play does not allow an app to disguise its icon"))
        assertFalse(play.contains("\"Compass\" instead"))
        val full = discreetModeSubtitle(res, on = true, disguiseAllowed = true, disguiseLabel = "Notes")
        assertTrue(full, full.startsWith("The home screen shows \"Notes\" instead of Clairix."))
        assertFalse(full.contains("Google Play"))
        // Android's device-admin request shows the app's own name and icon whatever explanation the app
        // gives, so Settings claims only that Clairix's explanation is neutral, and lists the heading as a limit.
        for (subtitle in listOf(play, full, discreetModeSubtitle(res, on = false, disguiseAllowed = true, disguiseLabel = "Compass"))) {
            assertFalse(subtitle, subtitle.contains("request does not name Clairix") || subtitle.contains("request would not name Clairix"))
        }
        val limits = res.getString(R.string.settings_discreet_limits)
        val quickExit = res.getString(R.string.settings_quick_exit)
        assertTrue(limits.contains("the device-admin request"))
        // Every edition is told what Android still shows, and how to leave fast.
        assertTrue(limits.contains("Settings, Apps"))
        assertTrue(quickExit.contains("hold the mark at the top left") && quickExit.contains("press Back"))
    }
}
