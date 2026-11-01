package app.clairix.security

import app.clairix.InMemoryPrefs
import app.clairix.PrefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unlock ledger asks "When do you usually sleep?" before its first judgement: nothing is
 * judged, or marked as judged, until it has an answer, and an install that was already judging
 * with the default window before the question existed goes on as it was.
 */
class UnlockLedgerSleepWindowTest {

    @Test
    fun theQuestionIsAnsweredByTheOwnerOrByAnEarlierInstall() {
        assertFalse(UnlockLedger.sleepWindowAnswered(InMemoryPrefs()))
        val asked = InMemoryPrefs().apply { edit().putBoolean("sleep_window_asked", true).apply() }
        assertTrue(UnlockLedger.sleepWindowAnswered(asked))
        // A window saved before the question existed.
        val saved = InMemoryPrefs().apply { edit().putInt("sleep_start_min", 60).putInt("sleep_end_min", 9 * 60).apply() }
        assertTrue(UnlockLedger.sleepWindowAnswered(saved))
        // A ledger that was judging with the default window already.
        val judging = InMemoryPrefs().apply { edit().putLong("sleep_check_ts", 1_700_000_000_000L).apply() }
        assertTrue(UnlockLedger.sleepWindowAnswered(judging))
    }

    @Test
    fun savingTheTwoTimesAnswersIt() {
        val ctx = PrefsContext()
        assertFalse(UnlockLedger.sleepWindowAnswered(ctx))
        UnlockLedger.setSleepWindow(ctx, 22 * 60 + 30, 6 * 60)
        assertTrue(UnlockLedger.sleepWindowAnswered(ctx))
        assertEquals(22 * 60 + 30, UnlockLedger.sleepStartMinutes(ctx))
        assertEquals(6 * 60, UnlockLedger.sleepEndMinutes(ctx))
    }

    @Test
    fun nothingIsJudgedOrMarkedAsJudgedBeforeAnAnswer() {
        val ctx = PrefsContext()
        assertTrue(UnlockLedger.checkSleepWindow(ctx).isEmpty())
        // Not marked as judged, so the first judgement after the answer reaches back over the days held.
        assertFalse(ctx.prefs(UnlockLedger.PREFS_NAME).contains("sleep_check_ts"))
    }
}
