package app.clairix.analysis

import app.clairix.EventKind
import app.clairix.Severity
import app.clairix.analysis.BondWatch.Bond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which paired Bluetooth devices are new, and what the alert for them says. */
class BondWatchTest {

    private val car = Bond("00:11:22:33:44:55", "My Car")
    private val buds = Bond("66:77:88:99:aa:bb", "Buds")
    private val stranger = Bond("C0:FF:EE:00:00:01", "Keyboard K380")

    @Test
    fun theFirstLookIsOnlyTheBaseline() {
        assertTrue(BondWatch.newBonds(listOf(car, buds), known = null).isEmpty())
        assertEquals(setOf("00:11:22:33:44:55", "66:77:88:99:AA:BB"), BondWatch.snapshot(listOf(car, buds)))
    }

    @Test
    fun aDeviceNotSeenBeforeIsNewWhateverTheCaseOfItsAddress() {
        val known = BondWatch.snapshot(listOf(car, buds))
        assertEquals(listOf(stranger), BondWatch.newBonds(listOf(car, buds.copy(address = buds.address.uppercase()), stranger), known))
        assertTrue(BondWatch.newBonds(listOf(car), known).isEmpty())
        // The same device listed twice is one pairing.
        assertEquals(listOf(stranger), BondWatch.newBonds(listOf(stranger, stranger.copy(address = stranger.address.lowercase())), known))
    }

    @Test
    fun oneNewPairingIsAMediumPlatformAlertThatNamesIt() {
        val a = BondWatch.alert(listOf(stranger), now = 1_000L, silent = true)!!
        assertEquals("New Bluetooth device paired: Keyboard K380", a.title)
        assertEquals(Severity.MEDIUM, a.severity)
        assertEquals(EventKind.PLATFORM, a.kind)
        assertTrue(a.silent)
        assertTrue(a.detail, a.detail.startsWith("Keyboard K380 (C0:FF:EE:00:00:01)"))
        assertTrue(a.detail, a.detail.contains("Settings → Connected devices"))
        assertEquals("bt_bond:C0:FF:EE:00:00:01", a.dedupeKey)
    }

    @Test
    fun severalAreCountedAndAnUnnamedOneIsSaidToBe() {
        val a = BondWatch.alert(listOf(stranger, Bond("12:34:56:78:9A:BC", null)), now = 1_000L, silent = false)!!
        assertEquals("2 new Bluetooth devices paired", a.title)
        assertTrue(a.detail, a.detail.contains("an unnamed device (12:34:56:78:9A:BC)"))
        assertNull(BondWatch.alert(emptyList(), now = 1_000L, silent = false))
    }
}
