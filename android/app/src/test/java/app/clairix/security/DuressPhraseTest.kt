package app.clairix.security

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A duress phrase must be long enough that nobody types it by accident. */
class DuressPhraseTest {

    @Test
    fun everydayRepliesAreRejected() {
        for (phrase in listOf(
            "ok", "yes", "OK!", "  on   my  ", "help me", "call mom",
            "on my way", "see you soon", "i love you"
        )) {
            assertNotNull(phrase, DuressPhrase.validate(phrase))
        }
    }

    @Test
    fun threeWordsAndTwelveLettersAreBothRequired() {
        assertNull(DuressPhrase.validate("did you remember to feed the cat"))
        assertNull(DuressPhrase.validate("bring the blue umbrella"))
        // Long, but two words.
        assertNotNull(DuressPhrase.validate("thunderstorm warning"))
        // Three words, but only ten letters.
        assertNotNull(DuressPhrase.validate("feed the cat"))
    }

    @Test
    fun spacesDoNotCountTowardsTheLength() {
        // Twelve characters with its spaces, ten without.
        assertNotNull(DuressPhrase.validate("see   you    soon"))
        assertNull(DuressPhrase.validate("  please   call grandma  "))
    }

    @Test
    fun aBlankPhraseIsValidBecauseItClearsTheSetting() {
        assertNull(DuressPhrase.validate(""))
        assertNull(DuressPhrase.validate("   "))
    }

    @Test
    fun setRefusesATooShortPhraseBeforeTouchingStorage() {
        // A context that would fail on any use: the rule must be enforced first.
        val unusable = ContextWrapper(null)
        for (phrase in listOf("ok", "on my way", "see you soon", "i love you")) {
            val error = runCatching { DuressPhrase.set(unusable, phrase) }.exceptionOrNull()
            assertEquals(phrase, IllegalArgumentException::class.java, error?.javaClass)
            assertEquals(DuressPhrase.TOO_SHORT_MESSAGE, error?.message)
        }
    }

    @Test
    fun aLegacyShortPhraseNeverMatches() {
        // Saved before the length rule existed.
        assertFalse(DuressPhrase.matches("ok", "ok"))
        assertFalse(DuressPhrase.matches("On my  way", "on my way"))
    }

    @Test
    fun aValidPhraseMatchesIgnoringCaseAndSpacing() {
        val stored = "did you remember to feed the cat"
        assertTrue(DuressPhrase.matches("  Did you remember  to FEED the cat ", stored))
        assertFalse(DuressPhrase.matches("did you remember to feed the dog", stored))
    }
}
