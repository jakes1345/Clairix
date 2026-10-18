package app.clairix.analysis

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkwareScannerTest {
    private val gboard = "com.google.android.inputmethod.latin"
    private val rogue = "com.example.keylogger"

    private fun score(
        name: String,
        declared: Set<String> = emptySet(),
        ime: Boolean = false,
        admin: Boolean = false,
    ): Int = StalkwareScanner.componentSignals(name, declared, ime, admin).sumOf { it.points }

    @Test
    fun anUnknownEnabledKeyboardScoresOnlyTheKeyboardSignal() {
        // Its own BIND_INPUT_METHOD service is what made it a keyboard; counted once, not twice.
        val signals = StalkwareScanner.componentSignals(rogue, setOf(Manifest.permission.BIND_INPUT_METHOD), true, false)
        assertEquals(StalkwareScanner.IME_ENABLED_SCORE, signals.sumOf { it.points })
        assertEquals(1, signals.size)
        assertTrue(signals.single().reason.contains("keystroke"))
    }

    @Test
    fun aKnownEnabledKeyboardIsListedButNotScored() {
        val signals = StalkwareScanner.componentSignals(gboard, setOf(Manifest.permission.BIND_INPUT_METHOD), true, false)
        assertEquals(0, signals.sumOf { it.points })
        assertEquals(listOf("is an enabled keyboard (a known app)"), signals.map { it.reason })
    }

    @Test
    fun aDeclaredButDisabledKeyboardIsAWeakSignalUnlessKnown() {
        assertEquals(1, score(rogue, setOf(Manifest.permission.BIND_INPUT_METHOD)))
        assertEquals(0, score(gboard, setOf(Manifest.permission.BIND_INPUT_METHOD)))
    }

    @Test
    fun anActiveDeviceAdminAddsToItsDeclaredReceiver() {
        assertEquals(1, score(rogue, setOf(Manifest.permission.BIND_DEVICE_ADMIN)))
        assertEquals(
            1 + StalkwareScanner.DEVICE_ADMIN_ACTIVE_SCORE,
            score(rogue, setOf(Manifest.permission.BIND_DEVICE_ADMIN), admin = true),
        )
    }

    @Test
    fun aHiddenKeyloggerWithAdminReachesHighOnTheseSignalsAlone() {
        val declared = setOf(Manifest.permission.BIND_INPUT_METHOD, Manifest.permission.BIND_DEVICE_ADMIN)
        // 4 (enabled keyboard) + 1 (admin receiver) + 3 (active admin), at or above HIGH (5).
        assertEquals(8, score(rogue, declared, ime = true, admin = true))
    }

    @Test
    fun vouchedBindingsStillExemptKnownApps() {
        assertEquals(0, score("com.google.android.marvin.talkback", setOf(Manifest.permission.BIND_ACCESSIBILITY_SERVICE)))
        assertEquals(2, score(rogue, setOf(Manifest.permission.BIND_ACCESSIBILITY_SERVICE)))
        assertEquals(0, score("com.whatsapp", setOf(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)))
        assertEquals(1, score(rogue, setOf(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)))
        assertEquals(0, score(rogue, setOf(Manifest.permission.BIND_VPN_SERVICE)))
    }

    @Test
    fun systemAppsAreScoredOnlyForGrantsOutsideTheStockImage() {
        assertFalse(StalkwareScanner.systemAppWorthScoring(gboard, listener = false, a11y = false, imeEnabled = true))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.preloaded.ime", listener = false, a11y = false, imeEnabled = true))
        assertFalse(StalkwareScanner.systemAppWorthScoring("com.android.systemui", listener = true, a11y = false, imeEnabled = false))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.spy", listener = true, a11y = false, imeEnabled = false))
        assertTrue(StalkwareScanner.systemAppWorthScoring("com.vendor.spy", listener = false, a11y = true, imeEnabled = false))
        assertFalse(StalkwareScanner.systemAppWorthScoring("com.vendor.anything", listener = false, a11y = false, imeEnabled = false))
    }

    @Test
    fun suspiciousImesDropKnownKeyboardsAndSort() {
        assertEquals(
            listOf("com.a.keys", rogue),
            StalkwareScanner.suspiciousImes(listOf(rogue, gboard, "com.a.keys", "com.samsung.android.honeyboard", rogue)),
        )
        assertEquals(emptyList<String>(), StalkwareScanner.suspiciousImes(emptySet()))
    }
}
