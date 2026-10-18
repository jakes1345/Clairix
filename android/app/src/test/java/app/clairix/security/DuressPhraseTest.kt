package app.clairix.security

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A duress phrase must be long enough that nobody types it by accident. */
class DuressPhraseTest {

    @Test
    fun everydayRepliesAreRejected() {
        for (phrase in listOf("ok", "yes", "OK!", "  on   my  ", "help me", "call mom")) {
            assertNotNull(phrase, DuressPhrase.validate(phrase))
        }
    }

    @Test
    fun threeWordsOrTwelveCharactersAreAccepted() {
        assertNull(DuressPhrase.validate("feed the cat"))
        assertNull(DuressPhrase.validate("did you remember to feed the cat"))
        // Two words, but twelve characters or more.
        assertNull(DuressPhrase.validate("thunderstorm warning"))
        assertNull(DuressPhrase.validate("pineapplepie"))
    }

    @Test
    fun lengthIsMeasuredAfterSpacesAreCollapsed() {
        // Eleven characters once the padding and doubled space are gone.
        assertNotNull(DuressPhrase.validate("   hello     there  "))
        assertNull(DuressPhrase.validate("  hello   there   you "))
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
        val error = runCatching { DuressPhrase.set(unusable, "ok") }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals(DuressPhrase.TOO_SHORT_MESSAGE, error?.message)
    }
}
