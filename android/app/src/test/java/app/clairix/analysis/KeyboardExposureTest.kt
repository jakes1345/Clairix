package app.clairix.analysis

import app.clairix.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardExposureTest {

    @Test
    fun aKeyboardThatCanReachTheInternetIsFlaggedWithTheWayToFixIt() {
        val f = KeyboardExposure.fact(KeyboardExposure.Keyboard("com.google.android.inputmethod.latin", "Gboard", internet = true, system = true))!!
        assertEquals(Severity.MEDIUM, f.severity)
        assertEquals("Gboard: can reach the internet", f.value)
        assertEquals(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, f.fixAction)
        assertTrue(!f.detail!!.contains("not part of the phone's own system"))
    }

    @Test
    fun anInstalledKeyboardWithInternetIsSaidToBeSo() {
        val f = KeyboardExposure.fact(KeyboardExposure.Keyboard("com.example.keys", "Keys", internet = true, system = false))!!
        assertTrue(f.detail!!.contains("not part of the phone's own system"))
    }

    @Test
    fun anOfflineKeyboardIsFine() {
        val f = KeyboardExposure.fact(KeyboardExposure.Keyboard("org.florisboard", "FlorisBoard", internet = false, system = false))!!
        assertTrue(f.ok)
        assertEquals("FlorisBoard: no internet access", f.value)
    }

    @Test
    fun noKeyboardNoRow() {
        assertNull(KeyboardExposure.fact(null))
    }
}
