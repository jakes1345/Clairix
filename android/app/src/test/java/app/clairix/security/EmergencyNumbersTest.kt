package app.clairix.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SOS sheet's emergency call button offers the number of the country the phone is in, and 112 when it cannot tell. */
class EmergencyNumbersTest {

    @Test
    fun theNetworkCountryDecides() {
        val c = EmergencyNumbers.choose(networkIso = "gb", simIso = "us")
        assertEquals("999", c.number)
        assertEquals("GB", c.country)
        assertEquals(EmergencyNumbers.Basis.NETWORK, c.basis)
    }

    @Test
    fun theSimCountryOnlyWithoutANetwork() {
        val c = EmergencyNumbers.choose(networkIso = "", simIso = "au")
        assertEquals("000", c.number)
        assertEquals(EmergencyNumbers.Basis.SIM, c.basis)
        assertEquals("000", EmergencyNumbers.choose(null, "AU").number)
    }

    @Test
    fun aKnownNetworkCountryNotInTheTableGets112EvenWithAListedSim() {
        // Abroad in a country the table lacks, with a SIM from home: home's number would be wrong there.
        val c = EmergencyNumbers.choose(networkIso = "aq", simIso = "us")
        assertEquals("112", c.number)
        assertEquals("AQ", c.country)
        assertEquals(EmergencyNumbers.Basis.NOT_IN_TABLE, c.basis)
        assertTrue(EmergencyNumbers.explain(c).contains("112"))
    }

    @Test
    fun noCountryAtAllGets112AndSaysWhy() {
        val c = EmergencyNumbers.choose(null, null)
        assertEquals(EmergencyNumbers.FALLBACK, c.number)
        assertNull(c.country)
        assertEquals(EmergencyNumbers.Basis.UNKNOWN, c.basis)
        val why = EmergencyNumbers.explain(c)
        assertTrue(why, why.contains("cannot tell which country"))
        assertTrue(why, why.contains("route 112"))
        // Malformed codes read as unknown, not as a country.
        assertEquals(EmergencyNumbers.Basis.UNKNOWN, EmergencyNumbers.choose("1x", "usa").basis)
    }

    @Test
    fun theFamiliarNumbers() {
        val expected = mapOf(
            "US" to "911", "CA" to "911", "MX" to "911", "GB" to "999", "IE" to "112", "DE" to "112",
            "FR" to "112", "ES" to "112", "AU" to "000", "NZ" to "111", "JP" to "110", "CN" to "110",
            "KR" to "112", "IN" to "112", "BR" to "190", "ZA" to "10111", "HK" to "999", "PH" to "911"
        )
        for ((country, number) in expected) {
            assertEquals(country, number, EmergencyNumbers.choose(country, null).number)
        }
    }

    @Test
    fun everyEuMemberStateDials112() {
        val eu = listOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
        )
        for (country in eu) assertEquals(country, "112", EmergencyNumbers.TABLE.getValue(country).number)
    }

    @Test
    fun theUkAliasIsReadAsGb() {
        assertEquals("999", EmergencyNumbers.choose("uk", null).number)
    }

    @Test
    fun everyNumberInTheTableIsDiallable() {
        for ((country, entry) in EmergencyNumbers.TABLE) {
            assertTrue(country, country.length == 2 && country.all { it in 'A'..'Z' })
            assertTrue(country, entry.number.isNotEmpty() && entry.number.all { it.isDigit() })
        }
    }

    @Test
    fun theExplanationNamesTheCountryAndTheOtherServices() {
        val jp = EmergencyNumbers.explain(EmergencyNumbers.choose("jp", null))
        assertTrue(jp, jp.startsWith("110 is the emergency number for Japan"))
        assertTrue(jp, jp.contains("119"))
        val sim = EmergencyNumbers.explain(EmergencyNumbers.choose(null, "nz"))
        assertTrue(sim, sim.contains("SIM card"))
    }
}
