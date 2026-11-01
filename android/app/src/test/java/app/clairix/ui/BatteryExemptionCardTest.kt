package app.clairix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maker-specific line on the battery exemption card and the Comms screen. */
class BatteryExemptionCardTest {

    @Test
    fun samsungAndXiaomiGetTheirOwnSwitchNamed() {
        assertTrue(makerBackgroundHint("samsung")!!.contains("Never sleeping apps"))
        assertTrue(makerBackgroundHint("Xiaomi")!!.contains("Autostart"))
        assertTrue(makerBackgroundHint(" POCO ")!!.contains("Autostart"))
        assertTrue(makerBackgroundHint("Redmi")!!.contains("Autostart"))
    }

    @Test
    fun otherMakersAndAnUnknownOneGetNoHint() {
        assertNull(makerBackgroundHint("Google"))
        assertNull(makerBackgroundHint("Fairphone"))
        assertNull(makerBackgroundHint(""))
        assertNull(makerBackgroundHint(null))
    }

    @Test
    fun theCommsScreenStepsNameAKnownMakersSwitchAndOtherwiseInventNoMenu() {
        assertEquals(makerBackgroundHint("samsung"), makerBackgroundSteps("samsung"))
        assertEquals(makerBackgroundHint("Xiaomi"), makerBackgroundSteps("Xiaomi"))
        // Samsung's path moved between One UI versions, and the line says so.
        assertTrue(makerBackgroundSteps("samsung").contains("vary"))
        // Any other maker: where to look, and no menu name Clairix cannot vouch for.
        for (maker in listOf("Google", "OnePlus", "HUAWEI", "", null)) {
            val steps = makerBackgroundSteps(maker)
            assertTrue(steps, steps.contains("App info"))
            assertFalse(steps, steps.contains(" > "))
        }
    }
}
