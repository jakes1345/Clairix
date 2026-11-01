package app.clairix

import app.clairix.security.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the pages that ask for the passcode again (Patterns,
 * Unlocks, the tracker map, the export), what the check says for each verdict,
 * and the sentence the owner reads after an unlock that followed wrong passcodes.
 */
class OwnerCheckTest {

    // ── When a gated page is open ──────────────────────────────────────────

    @Test
    fun withAPasscodeThePageWaitsForTheOwnerAndClosesWhenClairixIsLeft() {
        val gate = OwnerGateState(passcodeSet = true, locked = false)
        assertFalse(gate.isOpen(passcodeSet = true))
        gate.ownerConfirmed()
        assertTrue(gate.isOpen(passcodeSet = true))
        // Away for a moment, well inside the relock delay: closed again.
        gate.left()
        assertFalse(gate.isOpen(passcodeSet = true))
        gate.lockChanged(false)
        assertFalse("a lock that never came cannot open it", gate.isOpen(passcodeSet = true))
    }

    @Test
    fun withoutAPasscodeThePageIsOpen() {
        val gate = OwnerGateState(passcodeSet = false, locked = false)
        assertTrue(gate.isOpen(passcodeSet = false))
        gate.left()
        assertTrue(gate.isOpen(passcodeSet = false))
    }

    @Test
    fun aPasscodeSetFromThePageItselfLeavesItOpenUntilItIsLeft() {
        val gate = OwnerGateState(passcodeSet = false, locked = false)
        gate.passcodeChanged(true)
        assertTrue(gate.isOpen(passcodeSet = true))
        gate.left()
        assertFalse(gate.isOpen(passcodeSet = true))
    }

    @Test
    fun gettingPastTheLockScreenWithThePageUnderneathOpensIt() {
        val gate = OwnerGateState(passcodeSet = true, locked = false)
        gate.ownerConfirmed()
        gate.left()
        gate.lockChanged(true)
        assertFalse(gate.isOpen(passcodeSet = true))
        gate.lockChanged(false)
        assertTrue("the lock screen just took the passcode", gate.isOpen(passcodeSet = true))

        // A page first drawn underneath the lock screen at start-up opens with the unlock too.
        val atStart = OwnerGateState(passcodeSet = true, locked = true)
        assertFalse(atStart.isOpen(passcodeSet = true))
        atStart.lockChanged(false)
        assertTrue(atStart.isOpen(passcodeSet = true))
    }

    // ── What the check says ────────────────────────────────────────────────

    @Test
    fun theCheckSaysWhatHappenedWithoutGivingAwayAWipe() {
        assertNull(ownerCheckProblem(AppLock.Verdict.Accepted))
        assertEquals("Wrong passcode", ownerCheckProblem(AppLock.Verdict.Rejected(0L)))
        assertEquals("Wrong passcode. Try again in 30 seconds.", ownerCheckProblem(AppLock.Verdict.Rejected(30_000L)))
        assertEquals("Too many attempts. Try again in 2 minutes.", ownerCheckProblem(AppLock.Verdict.LockedOut(90_000L)))
        // The limit reached here reads like any wrong passcode until the process goes.
        assertEquals("Wrong passcode", ownerCheckProblem(AppLock.Verdict.Wiping))
        assertTrue(ownerCheckProblem(AppLock.Verdict.Unavailable("The Keystore is not responding"))!!.endsWith("The Keystore is not responding"))
    }

    @Test
    fun aWaitIsRoundedUpSoItIsNeverTooShort() {
        assertEquals("1 second", waitText(1L))
        assertEquals("30 seconds", waitText(30_000L))
        assertEquals("1 minute", waitText(60_000L))
        assertEquals("2 minutes", waitText(60_001L))
        assertEquals("1 hour", waitText(60 * 60_000L))
    }

    // ── After an unlock ────────────────────────────────────────────────────

    @Test
    fun theOwnerIsToldWhenAWrongPasscodeWasEntered() {
        val now = 1_790_000_000_000L
        fun said(count: Int, agoMs: Long) = wrongAttemptsMessage(AppLock.WrongAttempts(count, now - agoMs), now)
        assertEquals("A wrong passcode was entered 12 minutes ago.", said(1, 12 * 60_000L))
        assertEquals("A wrong passcode was entered less than a minute ago.", said(1, 30_000L))
        assertEquals("A wrong passcode was entered 1 minute ago.", said(1, 60_000L))
        assertEquals("A wrong passcode was entered 2 hours 5 minutes ago.", said(1, 125 * 60_000L))
        assertEquals("A wrong passcode was entered 3 hours ago.", said(1, 3 * 60 * 60_000L))
        assertEquals("A wrong passcode was entered 2 days ago.", said(1, 50 * 60 * 60_000L))
        assertEquals("3 wrong passcodes were entered, the last 12 minutes ago.", said(3, 12 * 60_000L))
        // With the clock set back past it there is no honest "ago" to give.
        assertEquals("A wrong passcode was entered since Clairix was last unlocked.", said(1, -5 * 60_000L))
        assertEquals("2 wrong passcodes were entered since Clairix was last unlocked.", said(2, -5 * 60_000L))
    }
}
