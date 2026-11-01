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
    fun genuineGboardAndSamsungKeyboardAreInformationNotAnIssue() {
        for ((pkg, label) in listOf("com.google.android.inputmethod.latin" to "Gboard", "com.samsung.android.honeyboard" to "Samsung Keyboard")) {
            val f = KeyboardExposure.fact(KeyboardExposure.Keyboard(pkg, label, internet = true, system = true, vouched = true))!!
            // Every stock Pixel and Galaxy has one: no severity, the same advice.
            assertNull(label, f.severity)
            assertEquals("$label: known keyboard, can reach the internet", f.value)
            assertTrue(f.detail!!.contains("turn off personalisation"))
            assertTrue(!f.detail!!.contains("treat it as spyware"))
            assertEquals(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, f.fixAction)
        }
    }

    @Test
    fun aCopyUnderTheirNameOrAnotherKnownKeyboardIsStillFlagged() {
        // The name without the vouching (a sideloaded copy) is not the stock keyboard.
        val copy = KeyboardExposure.fact(KeyboardExposure.Keyboard("com.google.android.inputmethod.latin", "Gboard", internet = true, system = false, vouched = false))!!
        assertEquals(Severity.MEDIUM, copy.severity)
        // Only Gboard and Samsung's keyboard are known to honour the no-learning request.
        val swiftkey = KeyboardExposure.fact(KeyboardExposure.Keyboard("com.touchtype.swiftkey", "SwiftKey", internet = true, system = false, vouched = true))!!
        assertEquals(Severity.MEDIUM, swiftkey.severity)
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
