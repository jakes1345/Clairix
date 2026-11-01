package app.clairix.comms

import app.clairix.comms.RestartRefusals.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the owner is told that Android stopped the background connection: twice in a row, once, until it runs again. */
class RestartRefusalsTest {

    @Test
    fun oneRefusalTellsNobody() {
        val first = RestartRefusals.onAttempt(State(), refused = true)
        assertFalse(first.notify)
        assertEquals(State(refusals = 1, notified = false), first.state)
        assertFalse(RestartRefusals.stopped(first.state))
    }

    @Test
    fun theSecondRefusalInARowTellsTheOwnerOnce() {
        val first = RestartRefusals.onAttempt(State(), refused = true)
        val second = RestartRefusals.onAttempt(first.state, refused = true)
        assertTrue(second.notify)
        assertTrue(RestartRefusals.stopped(second.state))
        assertEquals(RestartRefusals.NOTIFY_AFTER, second.state.refusals)
        // Every check after that is refused too; no second notification.
        var state = second.state
        repeat(10) {
            val next = RestartRefusals.onAttempt(state, refused = true)
            assertFalse(next.notify)
            state = next.state
        }
        assertTrue(RestartRefusals.stopped(state))
    }

    @Test
    fun aStartAndroidTookBreaksTheRun() {
        val first = RestartRefusals.onAttempt(State(), refused = true)
        val taken = RestartRefusals.onAttempt(first.state, refused = false)
        assertFalse(taken.notify)
        assertEquals(0, taken.state.refusals)
        // Refused again: that is one in a row, not two.
        assertFalse(RestartRefusals.onAttempt(taken.state, refused = true).notify)
    }

    @Test
    fun aTakenStartDoesNotRepeatTheNotificationWhileTheServiceHasNotRun() {
        val told = RestartRefusals.onAttempt(RestartRefusals.onAttempt(State(), refused = true).state, refused = true).state
        val taken = RestartRefusals.onAttempt(told, refused = false).state
        assertTrue(taken.notified)
        val again = RestartRefusals.onAttempt(RestartRefusals.onAttempt(taken, refused = true).state, refused = true)
        assertFalse(again.notify)
    }

    @Test
    fun theServiceRunningStartsTheCountAgainSoALaterStopIsToldAgain() {
        val told = RestartRefusals.onAttempt(RestartRefusals.onAttempt(State(), refused = true).state, refused = true).state
        val running = RestartRefusals.onRunning()
        assertEquals(State(), running)
        assertFalse(RestartRefusals.stopped(running))
        assertTrue(told.notified)
        val first = RestartRefusals.onAttempt(running, refused = true)
        val second = RestartRefusals.onAttempt(first.state, refused = true)
        assertFalse(first.notify)
        assertTrue(second.notify)
    }
}
