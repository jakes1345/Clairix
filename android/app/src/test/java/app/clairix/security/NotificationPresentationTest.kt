package app.clairix.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a notification outside Comms may say: nothing, unless the app is
 * unlocked and the owner asked for details; nothing at all for an alert raised
 * on the screen the owner is looking at; and never a count, a name or a number
 * from an ongoing notification.
 */
class NotificationPresentationTest {

    private val trackerTitle = "AirTag is following you"
    private val trackerText = "Heard 640 m apart. It is on you or your vehicle."
    private val trackerLong = "AirTag present at locations 640 m apart over 12 sightings. " +
        "Check wheel wells, bumper covers, OBD-II port, under seats, bag linings."

    private fun tracker(locked: Boolean, showDetails: Boolean) =
        NotificationPresentation.alert(trackerTitle, trackerText, trackerLong, locked = locked, showDetails = showDetails)

    @Test
    fun aTrackerAlertNamesNothingByDefault() {
        val shown = tracker(locked = false, showDetails = false)!!
        assertEquals(NotificationText("Clairix", "Unlock to read"), shown)
        assertFalse(shown.detailed)
        assertNull(shown.bigText)
    }

    @Test
    fun aTrackerAlertNamesNothingWhileLockedEvenWithDetailsOn() {
        val shown = tracker(locked = true, showDetails = true)!!
        assertEquals(NotificationPresentation.neutral(), shown)
        assertFalse(shown.toString(), shown.toString().contains("AirTag"))
        assertFalse(shown.toString(), shown.toString().contains("wheel"))
    }

    @Test
    fun aTrackerAlertIsNamedOnlyUnlockedWithDetailsOn() {
        val shown = tracker(locked = false, showDetails = true)!!
        assertTrue(shown.detailed)
        assertEquals(trackerTitle, shown.title)
        assertEquals(trackerText, shown.text)
        assertEquals(trackerLong, shown.bigText)
    }

    @Test
    fun aCellAlertFollowsTheSameRule() {
        val title = "Cell anomaly detected"
        val text = "Serving cell changed to 2G with no handover"
        assertEquals(NotificationPresentation.neutral(), NotificationPresentation.alert(title, text, locked = false, showDetails = false))
        assertEquals(NotificationPresentation.neutral(), NotificationPresentation.alert(title, text, locked = true, showDetails = true))
        assertEquals(NotificationPresentation.neutral(), NotificationPresentation.alert(title, text, locked = true, showDetails = false))
        assertEquals(NotificationText(title, text, detailed = true), NotificationPresentation.alert(title, text, locked = false, showDetails = true))
    }

    @Test
    fun aSecurityAlertFollowsTheSameRuleAndASilentOneRaisesNothing() {
        val title = "Your phone was unlocked while you slept"
        val detail = "2 unlocks between 23:00 and 07:00: at 02:14, 02:40. " +
            "If that was not you, someone had your PIN or your finger."
        val neutral = NotificationPresentation.alert(title, detail, detail, locked = false, showDetails = false)!!
        assertFalse(neutral.detailed)
        assertFalse(neutral.toString().contains("02:14"))
        val named = NotificationPresentation.alert(title, detail, detail, locked = false, showDetails = true)!!
        assertEquals(title, named.title)
        assertEquals(detail, named.bigText)
        // An optical camera finding is made with the sweep screen up, where it is
        // already shown: no notification, whatever the lock or the setting says.
        val glint = "Possible hidden camera — lens glint"
        assertNull(NotificationPresentation.alert(glint, detail, locked = false, showDetails = true, silent = true))
        assertNull(NotificationPresentation.alert(glint, detail, locked = true, showDetails = false, silent = true))
    }

    @Test
    fun theLinkNotificationNeverCarriesTheNumberAndHidesCommsWithPreviewsHidden() {
        val online = NotificationPresentation.link(connected = true, previewsHidden = false)
        assertEquals("Comms online", online.title)
        assertEquals("Connected to your relay · encrypted messages arrive instantly", online.text)
        assertEquals(
            NotificationText("Comms reconnecting", "Connecting to your relay"),
            NotificationPresentation.link(connected = false, previewsHidden = false)
        )
        assertEquals(NotificationText("Clairix", "Connected"), NotificationPresentation.link(connected = true, previewsHidden = true))
        assertEquals(NotificationText("Clairix", "Reconnecting"), NotificationPresentation.link(connected = false, previewsHidden = true))
        // There is no number to leak: the function is not given one, and nothing it says has a digit in it.
        for (hidden in listOf(true, false)) for (connected in listOf(true, false)) {
            val shown = NotificationPresentation.link(connected, hidden)
            assertFalse(shown.toString(), (shown.title + shown.text).any { it.isDigit() })
        }
    }

    @Test
    fun theOngoingNotificationSaysOnlyThatItScans() {
        assertEquals(NotificationText("Clairix", "Scanning"), NotificationPresentation.ongoing(paused = false))
        assertEquals(NotificationText("Clairix", "Scan paused — retrying"), NotificationPresentation.ongoing(paused = true))
    }

    @Test
    fun theNeutralVersionNamesNothingAndDetailsNeedBothTheUnlockAndTheSetting() {
        assertEquals(NotificationText("Clairix", "Unlock to read"), NotificationPresentation.neutral())
        assertEquals(
            NotificationText("Clairix", "Unlock to see alerts"),
            NotificationPresentation.neutral(NotificationPresentation.UNLOCK_TO_SEE_ALERTS)
        )
        assertEquals(NotificationText("Clairix", "Running"), NotificationPresentation.neutral(NotificationPresentation.RUNNING))
        assertTrue(NotificationPresentation.detailsAllowed(locked = false, showDetails = true))
        assertFalse(NotificationPresentation.detailsAllowed(locked = true, showDetails = true))
        assertFalse(NotificationPresentation.detailsAllowed(locked = false, showDetails = false))
        assertFalse(NotificationPresentation.detailsAllowed(locked = true, showDetails = false))
    }
}
