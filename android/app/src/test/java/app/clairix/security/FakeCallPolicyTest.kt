package app.clairix.security

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake call's rules, all pure, so the Fake call page, the ringing service
 * and the call screen cannot disagree about them: whether a call may be
 * scheduled and what is missing, whether Clairix or the call card makes the
 * sound, and how long the phone rings.
 */
class FakeCallPolicyTest {

    @Test
    fun everythingAllowedIsReadyAndOnTime() {
        val r = FakeCall.readiness(canPost = true, channelsOk = true, canUseFullScreen = true, canScheduleExactly = true)
        assertTrue(r.canSchedule)
        assertTrue(r.ringsOnTime)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun notificationsAndFullScreenCallsEachBlockScheduling() {
        val noNotifications = FakeCall.readiness(canPost = false, channelsOk = true, canUseFullScreen = true, canScheduleExactly = true)
        assertFalse(noNotifications.canSchedule)
        assertEquals(listOf(FakeCall.Missing.NOTIFICATIONS), noNotifications.missing)

        val noFullScreen = FakeCall.readiness(canPost = true, channelsOk = true, canUseFullScreen = false, canScheduleExactly = true)
        assertFalse(noFullScreen.canSchedule)
        assertEquals(listOf(FakeCall.Missing.FULL_SCREEN), noFullScreen.missing)
    }

    @Test
    fun aSilencedChannelOrNoExactAlarmsIsShownButDoesNotBlock() {
        val silenced = FakeCall.readiness(canPost = true, channelsOk = false, canUseFullScreen = true, canScheduleExactly = true)
        assertTrue(silenced.canSchedule)
        assertTrue(silenced.ringsOnTime)
        assertEquals(listOf(FakeCall.Missing.CALL_CHANNEL), silenced.missing)

        val inexact = FakeCall.readiness(canPost = true, channelsOk = true, canUseFullScreen = true, canScheduleExactly = false)
        assertTrue(inexact.canSchedule)
        assertFalse(inexact.ringsOnTime)
        assertEquals(listOf(FakeCall.Missing.EXACT_ALARMS), inexact.missing)
    }

    @Test
    fun everythingMissingIsListedInTheOrderThePageShowsIt() {
        val r = FakeCall.readiness(canPost = false, channelsOk = false, canUseFullScreen = false, canScheduleExactly = false)
        assertFalse(r.canSchedule)
        assertFalse(r.ringsOnTime)
        assertEquals(FakeCall.Missing.entries.toList(), r.missing)
    }

    @Test
    fun clairixAndTheCardNeverBothMakeTheSound() {
        // Ringtone playing: Clairix rings, the card stays quiet.
        assertTrue(FakeCall.ringsItself(AudioManager.RINGER_MODE_NORMAL, ringtonePlaying = true))
        // The ringer is on but no ringtone could be played: the card rings with its own sound.
        assertFalse(FakeCall.ringsItself(AudioManager.RINGER_MODE_NORMAL, ringtonePlaying = false))
        // Vibrate: Clairix vibrates and the card adds no sound; silent: nothing sounds at all.
        assertTrue(FakeCall.ringsItself(AudioManager.RINGER_MODE_VIBRATE, ringtonePlaying = false))
        assertTrue(FakeCall.ringsItself(AudioManager.RINGER_MODE_SILENT, ringtonePlaying = false))
    }

    @Test
    fun theRingLastsFortyFiveSecondsFromWhenItBegan() {
        val start = 1_700_000_000_000L
        assertEquals(45_000L, FakeCall.RING_MS)
        assertEquals(FakeCall.RING_MS, FakeCall.ringRemaining(start, start))
        assertEquals(35_000L, FakeCall.ringRemaining(start, start + 10_000L))
        assertEquals(1L, FakeCall.ringRemaining(start, start + FakeCall.RING_MS - 1L))
        assertEquals(0L, FakeCall.ringRemaining(start, start + FakeCall.RING_MS))
        // A screen that comes up after the ring is over closes at once rather than ringing again.
        assertEquals(0L, FakeCall.ringRemaining(start, start + FakeCall.RING_MS + 60_000L))
    }

    @Test
    fun aClockThatWentBackRingsTheFullTimeNotForever() {
        val start = 1_700_000_000_000L
        assertEquals(FakeCall.RING_MS, FakeCall.ringRemaining(start, start - 3_600_000L))
    }

    @Test
    fun theQuietCardOutlivesTheRingByAMomentOnly() {
        // Its own timeout is a backstop for when nothing takes it down; a missed call must not linger.
        assertTrue(FakeCall.CARD_TIMEOUT_MS > FakeCall.RING_MS)
        assertTrue(FakeCall.CARD_TIMEOUT_MS - FakeCall.RING_MS <= 10_000L)
    }
}
