package app.clairix.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maker-specific line on the battery exemption card. */
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
}
