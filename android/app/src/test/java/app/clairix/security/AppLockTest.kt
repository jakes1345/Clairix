package app.clairix.security

import android.content.Context
import app.clairix.PrefsContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.ProviderException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The passcode check's promises, with a software HMAC key and clocks moved by
 * hand standing in for the Keystore and SystemClock, and a wipe that is only
 * recorded: every attempt is on disk before its verdict, the wipe comes at the
 * owner's limit and only once she has set one, the lockout doubles on the
 * monotonic clock, and a verifier whose key is gone wipes.
 */
class AppLockTest {

    /** What a process killed between the count and the verdict looks like from inside: nothing returns. */
    private class Killed : Error("killed mid-check")

    private class FakePlatform : AppLock.Seam {
        var key: SecretKey? = null
        var keystoreDown = false
        var killOnKeyRead = false
        var onKeyRead: () -> Unit = {}
        var keyReads = 0
        var elapsed = 5_000_000L
        var wall = 1_790_000_000_000L
        val wipes = ArrayList<Context>()
        val duressWipes = ArrayList<String>()

        override fun existingKey(): SecretKey? {
            keyReads++
            onKeyRead()
            if (killOnKeyRead) throw Killed()
            if (keystoreDown) throw ProviderException("Keystore not answering")
            return key
        }

        override fun createKey(): SecretKey =
            SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "HmacSHA256").also { key = it }

        override fun elapsedRealtime(): Long = elapsed

        override fun now(): Long = wall

        override fun wipe(context: Context) {
            wipes += context
        }

        override fun duressWipe(context: Context, code: String) {
            duressWipes += code
        }
    }

    private val platform = FakePlatform()
    private val context = PrefsContext()
    private val passcode = "73915824"
    private val wrong = "26048137"

    private fun failedOnDisk(): Int = context.prefs(AppLock.PREFS_NAME).getInt("failed", 0)

    private fun guess(code: String = wrong, unlock: Boolean = true): AppLock.Verdict =
        AppLock.attempt(context, code, unlockOnSuccess = unlock)

    @Before
    fun setUp() {
        AppLock.seam = platform
        AppLock.setPasscode(context, passcode).getOrThrow()
        platform.keyReads = 0
    }

    @After
    fun tearDown() {
        AppLock.disable(context)
        AppLock.dismissWrongAttempts()
        AppLock.seam = AppLock.Platform
    }

    // ── The default ────────────────────────────────────────────────────────

    @Test
    fun aNewPasscodeWipesNothingUntilTheOwnerPicksALimit() {
        assertEquals(0, AppLock.DEFAULT_WIPE_AFTER)
        assertEquals(0, AppLock.wipeAfter(context))
        assertFalse("nobody has chosen yet", AppLock.wipeChoiceConfirmed(context))
        repeat(12) {
            val v = guess()
            assertTrue("wrong passcode $it gave $v", v is AppLock.Verdict.Rejected)
            platform.elapsed += 2 * 60 * 60_000L // past any lockout
        }
        assertTrue("wiped without a limit: ${platform.wipes}", platform.wipes.isEmpty())

        AppLock.setWipeAfter(context, 5)
        assertTrue(AppLock.wipeChoiceConfirmed(context))
        assertEquals(5, AppLock.wipeAfter(context))
        assertEquals("choosing a limit starts the count again", 0, failedOnDisk())
    }

    /** The file every version before the choice left: a limit of 3, written with the passcode, never asked about. */
    private fun seedUpgradedFile() {
        context.prefs(AppLock.PREFS_NAME).edit().putInt("wipe_after", 3).remove("wipe_after_chosen").commit()
    }

    @Test
    fun anUpgradedPhonesUnaskedLimitWipesNothingUntilTheOwnerConfirmsIt() {
        seedUpgradedFile()
        assertFalse(AppLock.wipeChoiceConfirmed(context))
        assertEquals("the unasked limit is not applied", 0, AppLock.wipeAfter(context))
        assertEquals("but it is remembered, to ask about", 3, AppLock.pausedWipeAfter(context))

        // Three wrong passcodes, then more: the lockout, never the wipe.
        repeat(4) { assertEquals("wrong passcode ${it + 1}", AppLock.Verdict.Rejected(0L), guess()) }
        assertEquals(AppLock.Verdict.Rejected(30_000L), guess())
        assertTrue("wiped on a limit nobody chose: ${platform.wipes}", platform.wipes.isEmpty())

        // She keeps 3 when asked: from then on it is hers, and it wipes.
        platform.elapsed += 60_000L
        AppLock.setWipeAfter(context, 3)
        assertEquals(0, AppLock.pausedWipeAfter(context))
        guess(); guess()
        assertTrue(platform.wipes.isEmpty())
        assertEquals(AppLock.Verdict.Wiping, guess())
        assertEquals(1, platform.wipes.size)
    }

    @Test
    fun theUnlockOnAnUpgradedPhoneAsksAndTheAnswerClearsTheQuestion() {
        seedUpgradedFile()
        AppLock.lock()
        assertFalse(AppLock.wipeChoiceDue.value)
        assertEquals(AppLock.Verdict.Accepted, guess(passcode, unlock = false))
        assertFalse("a check inside the app is not the unlock that asks", AppLock.wipeChoiceDue.value)

        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertTrue("the unlock did not ask", AppLock.wipeChoiceDue.value)

        AppLock.setWipeAfter(context, 0)
        assertFalse(AppLock.wipeChoiceDue.value)
        AppLock.lock()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertFalse("asked again after an answer", AppLock.wipeChoiceDue.value)
    }

    @Test
    fun aNewPasscodeIsAskedAboutWhereItIsSetNotAfterTheUnlock() {
        // Nothing stored but the default: the dialog after saving asks; the unlock has nothing to bring back.
        assertEquals(0, AppLock.pausedWipeAfter(context))
        AppLock.lock()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertFalse(AppLock.wipeChoiceDue.value)
    }

    @Test
    fun theLimitAppliedAndTheLimitHeldBack() {
        assertEquals(3, appliedWipeLimit(stored = 3, chosen = true))
        assertEquals(0, appliedWipeLimit(stored = 3, chosen = false))
        assertEquals(0, appliedWipeLimit(stored = 0, chosen = true))
        assertEquals(3, heldBackWipeLimit(stored = 3, chosen = false, hasPasscode = true))
        assertEquals(0, heldBackWipeLimit(stored = 3, chosen = true, hasPasscode = true))
        assertEquals(0, heldBackWipeLimit(stored = 0, chosen = false, hasPasscode = true))
        assertEquals(0, heldBackWipeLimit(stored = 3, chosen = false, hasPasscode = false))
    }

    // ── Counted before checked ─────────────────────────────────────────────

    @Test
    fun everyAttemptIsOnDiskBeforeItIsChecked() {
        var countSeenByTheCheck = -1
        platform.onKeyRead = { countSeenByTheCheck = failedOnDisk() }

        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertEquals("the wrong guess was not counted before the key was read", 1, countSeenByTheCheck)
        assertEquals(1, failedOnDisk())

        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertEquals("even the right one is counted until it is proven right", 2, countSeenByTheCheck)
        assertEquals(0, failedOnDisk())
    }

    @Test
    fun anAttemptKilledMidCheckStaysSpentAndTheLimitWipesBeforeTheNextCheck() {
        AppLock.setWipeAfter(context, 3)
        guess(); guess()
        assertEquals(2, failedOnDisk())

        platform.killOnKeyRead = true
        try {
            guess(passcode)
            fail("the check returned after the kill")
        } catch (expected: Killed) {
        }
        assertEquals("killing the app gave the attempt back", 3, failedOnDisk())
        assertTrue(platform.wipes.isEmpty())

        platform.killOnKeyRead = false
        platform.keyReads = 0
        assertEquals("the right passcode at the limit", AppLock.Verdict.Wiping, guess(passcode))
        assertEquals(1, platform.wipes.size)
        assertEquals("nothing was checked once the limit was reached", 0, platform.keyReads)
    }

    @Test
    fun aKeystoreFailureIsNotAWrongGuess() {
        platform.keystoreDown = true
        assertTrue(guess() is AppLock.Verdict.Unavailable)
        assertEquals("the attempt was not given back", 0, failedOnDisk())
        platform.keystoreDown = false
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertEquals(1, failedOnDisk())
    }

    @Test
    fun aCodeTooShortToBeAPasscodeIsNotCounted() {
        assertEquals(AppLock.Verdict.Rejected(0L), guess("12345"))
        assertEquals(0, failedOnDisk())
        assertEquals("a short code reached the Keystore", 0, platform.keyReads)
    }

    // ── The wipe limit ─────────────────────────────────────────────────────

    @Test
    fun theWrongPasscodeThatReachesTheLimitWipes() {
        AppLock.setWipeAfter(context, 3)
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertTrue(platform.wipes.isEmpty())
        assertEquals(2, failedOnDisk())
        assertEquals(AppLock.Verdict.Wiping, guess())
        assertEquals(1, platform.wipes.size)
    }

    @Test
    fun aLimitOfOneWipesOnTheFirstWrongPasscode() {
        AppLock.setWipeAfter(context, 1)
        assertEquals(AppLock.Verdict.Wiping, guess())
        assertEquals(1, platform.wipes.size)
    }

    @Test
    fun theRightPasscodeBeforeTheLimitStartsTheCountAgain() {
        AppLock.setWipeAfter(context, 3)
        guess(); guess()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertEquals(0, failedOnDisk())
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertTrue("two wrong after a right one wiped", platform.wipes.isEmpty())
    }

    @Test
    fun aWrongPasscodeWhereSettingsAskCountsTheSame() {
        AppLock.setWipeAfter(context, 3)
        guess(unlock = false); guess(unlock = false)
        assertEquals(AppLock.Verdict.Wiping, guess(unlock = false))
        assertEquals(1, platform.wipes.size)
    }

    @Test
    fun aProvenBiometricEndsTheRunOfWrongPasscodes() {
        AppLock.setWipeAfter(context, 3)
        guess(unlock = false); guess(unlock = false)     // two slips at a page that asks again
        assertEquals(2, failedOnDisk())
        AppLock.biometricProven(context)                 // ...then her fingerprint gets her in
        assertEquals(0, failedOnDisk())
        guess(); guess()                                 // two more, weeks later
        assertTrue("slips with her own fingerprint between them added up to the wipe", platform.wipes.isEmpty())
        assertEquals(AppLock.Verdict.Wiping, guess())    // three in a row still wipe
    }

    @Test
    fun aProvenBiometricKeepsTheWrongPasscodesItHasNotReportedYet() {
        guess()
        AppLock.biometricProven(context)
        assertTrue(context.prefs(AppLock.PREFS_NAME).contains("wrong_at"))
    }

    // ── The duress code inside the app ─────────────────────────────────────

    @Test
    fun theDuressCodeAtACheckInsideTheAppIsAnOrdinaryWrongPasscode() {
        val duressCode = "48261937"
        AppLock.setDuress(context, duressCode).getOrThrow()
        assertTrue(AppLock.hasDuress(context))

        assertEquals(AppLock.Verdict.Rejected(0L), guess(duressCode, unlock = false))
        assertEquals("counted like any wrong passcode", 1, failedOnDisk())
        assertTrue(platform.wipes.isEmpty())
        assertFalse("no wipe started from inside the app", Wiper.isActive)
        assertTrue("the duress code survives, for the lock screen", AppLock.hasDuress(context))

        // Under a limit it counts towards it, as any wrong passcode there does.
        AppLock.setWipeAfter(context, 1)
        assertEquals(AppLock.Verdict.Wiping, guess(duressCode, unlock = false))
        assertEquals(1, platform.wipes.size)
    }

    // ── The lockout ────────────────────────────────────────────────────────

    @Test
    fun theLockoutDoublesOnTheMonotonicClockAndIgnoresTheDate() {
        repeat(4) { assertEquals("free attempt ${it + 1}", AppLock.Verdict.Rejected(0L), guess()) }
        assertEquals(AppLock.Verdict.Rejected(30_000L), guess())

        // Refused unchecked and uncounted while it lasts.
        platform.keyReads = 0
        assertEquals(AppLock.Verdict.LockedOut(30_000L), guess(passcode))
        assertEquals(5, failedOnDisk())
        assertEquals(0, platform.keyReads)

        // Moving the date a day on does nothing; the monotonic clock is what counts.
        platform.wall += 24 * 60 * 60_000L
        assertEquals(AppLock.Verdict.LockedOut(30_000L), guess())
        platform.elapsed += 29_999L
        assertEquals(AppLock.Verdict.LockedOut(1L), guess())
        platform.elapsed += 1L

        var expected = 60_000L
        while (expected < 60 * 60_000L) {
            assertEquals(AppLock.Verdict.Rejected(expected), guess())
            platform.elapsed += expected
            expected *= 2
        }
        // Thirty seconds doubled past an hour stops at the hour.
        assertEquals(AppLock.Verdict.Rejected(60 * 60_000L), guess())
        assertTrue("the lockout wiped", platform.wipes.isEmpty())

        // A reboot restarts the monotonic clock below the last failure: the full hour starts again.
        platform.elapsed = 10_000L
        assertEquals(AppLock.Verdict.LockedOut(60 * 60_000L), guess(passcode))
        platform.elapsed += 60 * 60_000L

        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertEquals(0, failedOnDisk())
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
    }

    // ── The key ────────────────────────────────────────────────────────────

    @Test
    fun aVerifierWhoseKeyIsGoneWipes() {
        platform.key = null
        assertEquals(AppLock.Verdict.Wiping, guess(passcode))
        assertEquals(1, platform.wipes.size)
    }

    @Test
    fun aMissingKeyWipesOnAWrongPasscodeTooWithNoLimitSet() {
        assertEquals(0, AppLock.wipeAfter(context))
        platform.key = null
        assertEquals(AppLock.Verdict.Wiping, guess())
        assertEquals(1, platform.wipes.size)
    }

    // ── The duress passcode ────────────────────────────────────────────────

    private val duress = "48261937"

    @Test
    fun theDuressPasscodeStartsTheDuressWipeWithItsCode() {
        AppLock.setDuress(context, duress).getOrThrow()
        assertTrue(AppLock.hasDuress(context))
        assertEquals(AppLock.Verdict.Wiping, guess(duress))
        assertEquals("the decoy the wipe leaves must open to this code", listOf(duress), platform.duressWipes)
        assertTrue("a plain wipe instead of the decoy", platform.wipes.isEmpty())
    }

    @Test
    fun theDuressPasscodeIsCountedBeforeItIsRecognised() {
        AppLock.setDuress(context, duress).getOrThrow()
        var countSeenByTheCheck = -1
        platform.onKeyRead = { countSeenByTheCheck = failedOnDisk() }
        guess(duress)
        assertEquals("from the outside it is one more attempt, like any other", 1, countSeenByTheCheck)
    }

    @Test
    fun theDuressPasscodeWipesWithNoLimitSetAndAfterWrongGuesses() {
        assertEquals(0, AppLock.wipeAfter(context))
        AppLock.setDuress(context, duress).getOrThrow()
        guess(); guess()
        assertEquals(AppLock.Verdict.Wiping, guess(duress))
        assertEquals(listOf(duress), platform.duressWipes)
    }

    @Test
    fun withADuressPasscodeSetTheOthersBehaveAsBefore() {
        AppLock.setDuress(context, duress).getOrThrow()
        assertEquals(AppLock.Verdict.Rejected(0L), guess())
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertTrue(platform.duressWipes.isEmpty())
        assertTrue(platform.wipes.isEmpty())
    }

    @Test
    fun aRemovedDuressPasscodeIsJustAWrongOne() {
        AppLock.setDuress(context, duress).getOrThrow()
        AppLock.setDuress(context, null).getOrThrow()
        assertEquals(AppLock.Verdict.Rejected(0L), guess(duress))
        assertTrue(platform.duressWipes.isEmpty())
    }

    @Test
    fun theDuressAndAppPasscodesCannotBeTheSame() {
        assertTrue(AppLock.setDuress(context, passcode).isFailure)
        AppLock.setDuress(context, duress).getOrThrow()
        assertTrue(AppLock.setPasscode(context, duress).isFailure)
    }

    // ── Telling the owner ──────────────────────────────────────────────────

    @Test
    fun wrongPasscodesFromBeforeTheLockScreenAreReportedAtTheNextUnlock() {
        val t0 = platform.wall
        guess(unlock = false)                        // somebody in the unlocked app, at a Settings prompt
        platform.wall = t0 + 60_000L
        guess()                                      // somebody at the lock screen
        platform.wall = t0 + 10 * 60_000L
        AppLock.lock()                               // the screen goes off; the owner comes back later
        platform.wall = t0 + 12 * 60_000L
        guess()                                      // the owner's own slip on her way in
        assertNull("nothing is said before the lock is passed", AppLock.wrongAttempts.value)

        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertEquals(AppLock.WrongAttempts(count = 2, lastAt = t0 + 60_000L), AppLock.wrongAttempts.value)

        AppLock.dismissWrongAttempts()
        AppLock.lock()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertNull("reported twice", AppLock.wrongAttempts.value)
    }

    @Test
    fun theOwnersOwnSlipAtTheLockScreenIsNotReported() {
        AppLock.lock()
        platform.wall += 5_000L
        guess()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertNull(AppLock.wrongAttempts.value)
    }

    @Test
    fun aConfirmationInsideTheAppReportsNothingAndKeepsTheRecord() {
        guess()
        platform.wall += 1_000L
        assertEquals(AppLock.Verdict.Accepted, guess(passcode, unlock = false))
        assertNull("a Settings prompt is not an unlock", AppLock.wrongAttempts.value)

        platform.wall += 1_000L
        AppLock.lock()
        assertEquals(AppLock.Verdict.Accepted, guess(passcode))
        assertEquals(1, AppLock.wrongAttempts.value?.count)
    }

    @Test
    fun onlyTheAttemptsBeforeTheSittingCount() {
        assertNull(AppLock.wrongAttemptsBefore(emptyList(), sittingSince = 1_000L))
        assertNull(AppLock.wrongAttemptsBefore(listOf(1_000L, 1_500L), sittingSince = 1_000L))
        assertEquals(
            AppLock.WrongAttempts(count = 2, lastAt = 900L),
            AppLock.wrongAttemptsBefore(listOf(500L, 900L, 1_200L), sittingSince = 1_000L)
        )
    }

    @Test
    fun turningTheLockOffForgetsTheChoiceAndTheRecord() {
        AppLock.setWipeAfter(context, 3)
        guess()
        AppLock.disable(context)
        assertFalse(AppLock.wipeChoiceConfirmed(context))
        assertFalse(context.prefs(AppLock.PREFS_NAME).contains("wrong_at"))
        AppLock.setPasscode(context, passcode).getOrThrow()
        assertEquals("a new passcode starts with no wipe", 0, AppLock.wipeAfter(context))
    }

    // ── The duress decoy ───────────────────────────────────────────────────

    @Test
    fun theDuressDecoyOpensToTheDuressCodeAndLooksLikeALockSetUpToday() {
        // What a duress wipe leaves (security/Wiper.kt reads AppLock.wipeAfter):
        // the owner had picked a limit of 3.
        AppLock.setWipeAfter(context, 3)
        val duressCode = "48261937"
        val lock = AppLock.makeDecoyLock(duressCode)
        assertTrue(AppLock.writeDecoy(context, lock, wipeAfter = AppLock.wipeAfter(context), relockMs = AppLock.DEFAULT_RELOCK_MS, biometric = false))

        assertTrue("the limit carried over counts as chosen, as on any new lock", AppLock.wipeChoiceConfirmed(context))
        assertEquals(3, AppLock.wipeAfter(context))
        assertEquals("nothing counted against the decoy yet", 0, failedOnDisk())
        assertFalse("no duress passcode on the decoy", AppLock.hasDuress(context))
        assertEquals(AppLock.Verdict.Accepted, guess(duressCode))
        assertTrue("the old passcode opens nothing", guess(passcode) is AppLock.Verdict.Rejected)
    }

    @Test
    fun theDecoyOfAnUpgradedPhoneDoesNotArmTheLimitNobodyChose() {
        seedUpgradedFile()
        val lock = AppLock.makeDecoyLock("48261937")
        assertTrue(AppLock.writeDecoy(context, lock, wipeAfter = AppLock.wipeAfter(context), relockMs = AppLock.DEFAULT_RELOCK_MS, biometric = false))
        assertEquals("the decoy behaves as the lock did: no wipe", 0, AppLock.wipeAfter(context))
        assertEquals(0, AppLock.pausedWipeAfter(context))
    }
}
