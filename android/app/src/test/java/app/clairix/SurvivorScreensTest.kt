package app.clairix

import app.clairix.analysis.AccountChecklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Health tab says about the accounts checklist, where the checklist
 * sits under Phone check, and what Settings says discreet mode does in each
 * edition.
 */
class SurvivorScreensTest {

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
    fun settingsSaysThePlayEditionKeepsTheClairixIcon() {
        val play = discreetModeSubtitle(on = true, disguiseAllowed = false, disguiseLabel = "Compass")
        assertTrue(play, play.contains("Google Play does not allow an app to disguise its icon"))
        assertFalse(play.contains("\"Compass\" instead"))
        val full = discreetModeSubtitle(on = true, disguiseAllowed = true, disguiseLabel = "Notes")
        assertTrue(full, full.startsWith("The home screen shows \"Notes\" instead of Clairix."))
        assertFalse(full.contains("Google Play"))
        // Every edition is told what Android still shows, and how to leave fast.
        assertTrue(DISCREET_LIMITS.contains("Settings, Apps"))
        assertTrue(QUICK_EXIT_TEXT.contains("hold the mark at the top left") && QUICK_EXIT_TEXT.contains("press Back"))
    }
}
