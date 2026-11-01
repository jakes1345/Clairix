package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spoken directions use a voice that synthesises on the phone, when it has one for its language. */
class VoicePickTest {

    private fun voice(
        name: String,
        language: String = "eng",
        country: String = "USA",
        needsNetwork: Boolean = false,
        installed: Boolean = true,
    ) = VoicePick.Candidate(name, language, country, needsNetwork, installed)

    @Test
    fun aVoiceThatNeedsTheNetworkIsNeverPicked() {
        // Not even when it is the engine's default.
        assertNull(VoicePick.pick(listOf(voice("en-us-x-net", needsNetwork = true)), "eng", "USA", defaultName = "en-us-x-net"))
    }

    @Test
    fun aVoiceListedButNotDownloadedIsNotPicked() {
        assertNull(VoicePick.pick(listOf(voice("en-us-x-local", installed = false)), "eng", "USA", null))
    }

    @Test
    fun theEnginesOwnDefaultWinsWhenItWorksOffline() {
        val voices = listOf(voice("b-local"), voice("a-local"), voice("x-net", needsNetwork = true))
        assertEquals("b-local", VoicePick.pick(voices, "eng", "USA", defaultName = "b-local")?.name)
    }

    @Test
    fun otherwiseTheSameCountryThenAnyOfTheLanguage() {
        val voices = listOf(voice("gb", country = "GBR"), voice("us", country = "USA"), voice("fr", language = "fra", country = "FRA"))
        assertEquals("us", VoicePick.pick(voices, "eng", "USA", defaultName = "en-us-x-net")?.name)
        assertEquals("gb", VoicePick.pick(listOf(voice("gb", country = "GBR")), "eng", "USA", null)?.name)
        assertNull(VoicePick.pick(voices, "deu", "DEU", null))
    }

    @Test
    fun codesAreComparedWithoutRegardToCase() {
        assertEquals("us", VoicePick.pick(listOf(voice("us", language = "ENG", country = "usa")), "eng", "USA", null)?.name)
    }
}
