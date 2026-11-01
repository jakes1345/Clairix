package app.clairix

import app.clairix.security.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question that follows setting a passcode: it says what a wipe takes, in
 * the edition's own terms, and explains every answer it offers. (The numbers in
 * the lockout answer are the ones AppLockTest proves.)
 */
class WipeChoiceTest {

    @Test
    fun theQuestionSaysWhatAWipeTakes() {
        val expected = if (BuildConfig.FULL_ACCESS)
            "Wiping erases every message, contact, your Clairix number and the evidence timeline. There is no backup and no undo."
        else
            "Wiping erases every message, contact and your Clairix number. There is no backup and no undo."
        assertEquals(expected, WIPE_WARNING)
    }

    @Test
    fun everyAnswerIsExplained() {
        assertTrue(wipeAfterOutcome(null).startsWith("Pick one"))
        assertTrue(wipeAfterOutcome(0).startsWith("Nothing is wiped. After 5 wrong passcodes in a row Clairix waits 30 seconds"))
        assertTrue(wipeAfterOutcome(1).startsWith("The first wrong passcode wipes Clairix."))
        for (n in AppLock.WIPE_AFTER_CHOICES.filter { it > 1 }) {
            assertTrue(wipeAfterOutcome(n), wipeAfterOutcome(n).startsWith("$n wrong passcodes in a row wipe Clairix."))
        }
    }
}
