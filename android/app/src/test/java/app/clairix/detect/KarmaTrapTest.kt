package app.clairix.detect

import android.net.wifi.WifiManager
import app.clairix.detect.KarmaTrap.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Armed or not, from what Android actually said. An earlier version called the trap
 * armed the moment `addNetworkSuggestions` returned success, which it does before the
 * owner has answered the "allow suggestions?" question.
 */
class KarmaTrapTest {

    private val t0 = 1_790_000_000_000L
    private val off = KarmaTrap()

    private val success = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    private val disallowed = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED
    private val approved = WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER
    private val pending = WifiManager.STATUS_SUGGESTION_APPROVAL_PENDING
    private val rejected = WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER

    private fun judge(
        addStatus: Int?,
        approval: Int?,
        previous: KarmaTrap = off,
        now: Long = t0,
        running: Boolean = true,
        wifi: Boolean = true
    ) = KarmaTrap.judge(previous, running, wifi, addStatus, approval, now)

    @Test
    fun aSuccessfulAddIsNotArmedUntilTheOwnerAllowsIt() {
        assertEquals(State.PENDING, judge(success, null).state)
        assertEquals(State.PENDING, judge(success, pending).state)
        assertEquals(State.PENDING, judge(success, WifiManager.STATUS_SUGGESTION_APPROVAL_UNKNOWN).state)
        assertFalse(judge(success, pending).armed)

        val armed = judge(success, approved, previous = judge(success, pending), now = t0 + 5_000L)
        assertEquals(State.ARMED, armed.state)
        assertEquals("armed from the moment Android said yes", t0 + 5_000L, armed.since)
        assertEquals(State.ARMED, judge(success, WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_CARRIER_PRIVILEGE).state)
    }

    @Test
    fun aRotationKeepsTheArmingTime() {
        val armed = judge(success, approved, now = t0)
        val rotated = judge(success, approved, previous = armed, now = t0 + 6 * 3_600_000L)
        assertEquals(State.ARMED, rotated.state)
        assertEquals(t0, rotated.since)
    }

    @Test
    fun saidNoIsTrapOffWhicheverSignalCarriesIt() {
        assertEquals(State.DISALLOWED, judge(disallowed, null).state)
        assertEquals(State.DISALLOWED, judge(disallowed, approved).state)
        // "Don't allow" (or Wi-Fi control turned off) also makes Android drop the suggestion.
        val armed = judge(success, approved)
        val revoked = judge(success, rejected, previous = armed, now = t0 + 60_000L)
        assertEquals(State.DISALLOWED, revoked.state)
        assertEquals(0L, revoked.since)
        // Allowed again later: armed afresh, from then.
        val again = judge(success, approved, previous = revoked, now = t0 + 120_000L)
        assertEquals(t0 + 120_000L, again.since)
    }

    @Test
    fun otherRefusalsAreRefusedWithTheirCode() {
        val internal = judge(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL, approved)
        assertEquals(State.REFUSED, internal.state)
        assertEquals(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL, internal.code)
        assertEquals(State.REFUSED, judge(-1, approved).state)
        assertTrue(
            judge(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN, null).detail().contains("device policy")
        )
        // Before Android 11 an identical suggestion came back as a duplicate: still in place.
        assertEquals(State.ARMED, judge(WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE, approved).state)
    }

    @Test
    fun noScannerOrNoWifiServiceIsNeverArmed() {
        assertEquals(State.OFF, judge(success, approved, running = false).state)
        assertEquals(State.UNAVAILABLE, judge(null, null, wifi = false).state)
        assertEquals("running, nothing tried yet", State.PENDING, judge(null, approved).state)
    }

    @Test
    fun theTabSaysWhatTheAuditAskedFor() {
        assertEquals("Trap armed since Nov 1, 09:12", judge(success, approved).headline("Nov 1, 09:12"))
        val ask = "Trap not armed: allow Clairix to suggest networks"
        assertEquals(ask, judge(success, pending).headline(""))
        assertEquals(ask, judge(disallowed, null).headline(""))
        assertTrue(judge(disallowed, null).detail().contains("Wi-Fi control"))
        assertEquals("Trap off while the scanner is off", off.headline(""))
    }
}
