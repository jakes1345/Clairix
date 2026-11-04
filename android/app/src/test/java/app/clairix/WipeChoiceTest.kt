package app.clairix

import app.clairix.security.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The question that follows setting a passcode: it says what a wipe takes, in
 * the edition's own terms, and explains every answer it offers. (The numbers in
 * the lockout answer are the ones AppLockTest proves.)
 */
@RunWith(RobolectricTestRunner::class)
class WipeChoiceTest {

    private val res = RuntimeEnvironment.getApplication().resources

    @Test
    fun theQuestionSaysWhatAWipeTakes() {
        val expected = if (BuildConfig.FULL_ACCESS)
            "Wiping erases every message, contact, your Clairix number and the evidence timeline. There is no backup and no undo."
        else
            "Wiping erases every message, contact and your Clairix number. There is no backup and no undo."
        assertEquals(expected, wipeWarning(res))
    }

    @Test
    fun everyAnswerIsExplained() {
        assertTrue(wipeAfterOutcome(res, null).startsWith("Pick one"))
        assertTrue(wipeAfterOutcome(res, 0).startsWith("Nothing is wiped. After 5 wrong passcodes in a row Clairix waits 30 seconds"))
        assertTrue(wipeAfterOutcome(res, 1).startsWith("The first wrong passcode wipes Clairix."))
        for (n in AppLock.WIPE_AFTER_CHOICES.filter { it > 1 }) {
            assertTrue(wipeAfterOutcome(res, n), wipeAfterOutcome(res, n).startsWith("$n wrong passcodes in a row wipe Clairix."))
        }
    }

    @Test
    fun theQuestionIsNeverUpWhileTheLockIs() {
        assertTrue(wipeQuestionShown(asking = true, locked = false))
        assertFalse("left above the lock screen", wipeQuestionShown(asking = true, locked = true))
        assertFalse(wipeQuestionShown(asking = false, locked = false))
    }

    @Test
    fun theQuestionAfterAnUnlockWaitsForTheWrongPasscodeNotice() {
        assertTrue(wipeLimitQuestionDue(due = true, locked = false, wrongAttemptsPending = false))
        assertFalse("stacked on the wrong-passcode notice", wipeLimitQuestionDue(due = true, locked = false, wrongAttemptsPending = true))
        assertFalse("above the lock screen", wipeLimitQuestionDue(due = true, locked = true, wrongAttemptsPending = false))
        assertFalse(wipeLimitQuestionDue(due = false, locked = false, wrongAttemptsPending = false))
    }

    @Test
    fun theUpgradedPhoneIsToldItsLimitIsPaused() {
        val note = pausedWipeNote(res, 3)
        assertTrue(note, note.startsWith("Earlier versions set a wipe after 3 wrong passcodes in a row"))
        assertTrue(note, "until you choose, wrong passcodes lock you out for longer instead" in note)
    }
}
