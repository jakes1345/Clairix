package app.clairix.analysis

import android.content.ContextWrapper
import app.clairix.Severity
import app.clairix.analysis.AdvancedProtection.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone check's Advanced Protection row by Android version: On or OFF on 16, what is true otherwise, never "Unknown". */
class AdvancedProtectionTest {

    @Test
    fun belowAndroid16TheRowSaysTheModeIsNotOnThisRelease() {
        for (sdk in listOf(31, 34, 35)) {
            // Below 16 the phone is not asked at all.
            assertEquals(State.NotOnThisRelease, AdvancedProtection.read(ContextWrapper(null), sdk))
        }
        val fact = AdvancedProtection.fact(State.NotOnThisRelease)
        assertEquals("Not available on this Android version", fact.value)
        assertNull(fact.severity)
        assertNull(AdvancedProtection.enabled(State.NotOnThisRelease))
    }

    @Test
    fun onAndroid16TheRowReadsOnOrOff() {
        val on = AdvancedProtection.fact(State.Answered(true))
        assertEquals("On", on.value)
        assertNull(on.severity)
        assertEquals(true, AdvancedProtection.enabled(State.Answered(true)))

        val off = AdvancedProtection.fact(State.Answered(false))
        assertEquals("OFF", off.value)
        assertEquals(Severity.HIGH, off.severity)
        assertEquals(android.provider.Settings.ACTION_SECURITY_SETTINGS, off.fixAction)
        assertEquals(false, AdvancedProtection.enabled(State.Answered(false)))
    }

    @Test
    fun bothAnswersSayASideloadedClairixCannotBeUpdatedWhileTheModeIsOn() {
        for (on in listOf(true, false)) {
            val detail = AdvancedProtection.fact(State.Answered(on)).detail!!
            assertTrue(detail, detail.contains(AdvancedProtection.SIDELOAD))
            // Memory tagging is hardware the phone may not have: never claimed outright.
            assertTrue(detail, !detail.contains("memory tagging is on"))
        }
    }

    @Test
    fun anAndroid16PhoneWithoutTheModeSaysSo() {
        // The SDK these tests run against has no AdvancedProtectionManager, like an Android 16 build
        // whose maker left the mode out.
        assertEquals(State.NotOnThisPhone, AdvancedProtection.read(ContextWrapper(null), AdvancedProtection.SINCE_SDK))
        val fact = AdvancedProtection.fact(State.NotOnThisPhone)
        assertEquals("Not available on this phone", fact.value)
        assertNull(fact.severity)
        assertNull(AdvancedProtection.enabled(State.NotOnThisPhone))
    }

    @Test
    fun theRowIsNeverABareUnknown() {
        val states = listOf(State.NotOnThisRelease, State.NotOnThisPhone, State.Answered(true), State.Answered(false), State.NoAnswer("SecurityException"))
        for (state in states) {
            val fact = AdvancedProtection.fact(state)
            assertNotEquals("$state", "Unknown", fact.value)
            assertEquals("adv_protection", fact.id)
            assertEquals("Protection", fact.category)
        }
        val refused = AdvancedProtection.fact(State.NoAnswer("SecurityException"))
        assertEquals("Could not be read", refused.value)
        assertTrue(refused.detail!!, refused.detail!!.contains("SecurityException"))
        assertNull(refused.severity)
        assertNull(AdvancedProtection.enabled(State.NoAnswer("SecurityException")))
    }
}
