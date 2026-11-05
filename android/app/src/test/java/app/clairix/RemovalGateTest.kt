package app.clairix

import android.provider.Settings
import app.clairix.analysis.DefaultRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The safety step before a flagged app is removed, switched off or replaced: which rows take it,
 * and that it says what it must, calmly: removing can alert whoever installed it and raise the
 * risk; save the evidence first; a helpline; then Continue or Cancel.
 */
@RunWith(RobolectricTestRunner::class)
class RemovalGateTest {

    private val res = RuntimeEnvironment.getApplication().resources

    @Test
    fun aPhoneCheckFindingAboutAnAppAsksFirst() {
        val outside = DefaultRoles.Holder(DefaultRoles.Role.SMS, "com.example.sync", "Sync", system = false, fromStore = false)
        val flagged = DefaultRoles.fact(listOf(outside))
        assertEquals("com.example.sync", RemovalGate.subjectOf(flagged))
    }

    @Test
    fun aRowInOrderOrWithNothingToOpenOpensStraightAway() {
        val fine = DefaultRoles.fact(listOf(DefaultRoles.Holder(DefaultRoles.Role.SMS, "com.google.android.apps.messaging", "Messages", system = true, fromStore = false)))
        assertNull(fine.subject)
        assertNull(RemovalGate.subjectOf(fine))
        val noPage = HealthFact("x", "Apps", "X", "y", Severity.MEDIUM, subject = "com.example.x")
        assertNull(RemovalGate.subjectOf(noPage))
        val noSeverity = HealthFact("x", "Apps", "X", "y", fixAction = Settings.ACTION_SETTINGS, subject = "com.example.x")
        assertNull(RemovalGate.subjectOf(noSeverity))
    }

    @Test
    fun theSheetSaysWhatRemovingCanDoAndWhatToDoFirst() {
        val alert = res.getString(R.string.scan_removal_can_alert)
        assertTrue(alert, alert.contains("alert whoever installed it"))
        assertTrue(alert, alert.contains("more dangerous"))
        val first = res.getString(R.string.scan_removal_first_steps)
        assertTrue(first, first.startsWith("Save the evidence first."))
        assertTrue(first, first.contains("helpline"))
        assertEquals("Save evidence now", res.getString(R.string.scan_save_evidence_now))
        assertEquals("Contact a helpline", res.getString(R.string.scan_contact_a_helpline))
        assertEquals("Continue", res.getString(R.string.scan_removal_continue))
        assertEquals("Cancel", res.getString(R.string.settings_cancel))
    }
}
