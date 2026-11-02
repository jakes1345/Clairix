package app.clairix.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discreet mode's decisions: which launcher entry shows in which edition, the
 * channel names, the device-admin request, the lock screen, and what every
 * notification says while it is on.
 */
class DiscreetTest {

    /** Words that would tell someone looking at the shade or the settings what the app is or does. */
    private val tells = listOf(
        "clairix", "tracker", "follow", "stalker", "spy", "sos", "cell", "mesh", "comms", "encrypt", "relay",
        "scan", "detector", "bluetooth", "intercept", "surveil", "locked"
    )

    private fun assertNoTell(text: String?) {
        if (text == null) return
        val lower = text.lowercase()
        tells.forEach { assertFalse("\"$text\" says \"$it\"", lower.contains(it)) }
    }

    // ── Launcher entries ─────────────────────────────────────────────────

    @Test
    fun theFullEditionShowsTheChosenDisguiseOnlyWhileDiscreetModeIsOn() {
        assertEquals(LauncherEntry.COMPASS, LauncherPlan.shown(discreet = true, chosen = LauncherEntry.COMPASS, disguiseAllowed = true))
        assertEquals(LauncherEntry.NOTES, LauncherPlan.shown(discreet = true, chosen = LauncherEntry.NOTES, disguiseAllowed = true))
        assertEquals(LauncherEntry.CLAIRIX, LauncherPlan.shown(discreet = false, chosen = LauncherEntry.NOTES, disguiseAllowed = true))
        // A stored choice that is not a disguise shows the real entry.
        assertEquals(LauncherEntry.CLAIRIX, LauncherPlan.shown(discreet = true, chosen = LauncherEntry.CLAIRIX, disguiseAllowed = true))
    }

    @Test
    fun thePlayEditionAlwaysKeepsTheClairixEntry() {
        for (chosen in LauncherEntry.entries) for (discreet in listOf(true, false)) {
            assertEquals(LauncherEntry.CLAIRIX, LauncherPlan.shown(discreet, chosen, disguiseAllowed = false))
            // Its manifest has no disguise aliases, so none is ever named for switching.
            assertEquals(mapOf(LauncherEntry.CLAIRIX to true), LauncherPlan.states(discreet, chosen, disguiseAllowed = false))
        }
    }

    @Test
    fun exactlyOneEntryIsEnabledInTheFullEdition() {
        for (chosen in LauncherEntry.entries) for (discreet in listOf(true, false)) {
            val states = LauncherPlan.states(discreet, chosen, disguiseAllowed = true)
            assertEquals(LauncherEntry.entries.toSet(), states.keys)
            assertEquals("$chosen $discreet", 1, states.values.count { it })
            assertEquals(LauncherPlan.shown(discreet, chosen, true), states.entries.single { it.value }.key)
        }
    }

    @Test
    fun aStoredDisguiseReadsBackOrFallsBackToTheDefault() {
        assertEquals(LauncherEntry.NOTES, LauncherPlan.disguiseNamed("NOTES"))
        assertEquals(LauncherEntry.COMPASS, LauncherPlan.disguiseNamed("COMPASS"))
        assertEquals(LauncherPlan.DEFAULT_DISGUISE, LauncherPlan.disguiseNamed(null))
        assertEquals(LauncherPlan.DEFAULT_DISGUISE, LauncherPlan.disguiseNamed("CLAIRIX"))
        assertEquals(LauncherPlan.DEFAULT_DISGUISE, LauncherPlan.disguiseNamed("CALCULATOR"))
        assertEquals(listOf(LauncherEntry.COMPASS, LauncherEntry.NOTES), LauncherPlan.DISGUISES)
        // The aliases declared in src/main/AndroidManifest.xml (namespace app.clairix).
        assertEquals("app.clairix.LauncherClairix", LauncherEntry.CLAIRIX.component)
        assertEquals("app.clairix.LauncherCompass", LauncherEntry.COMPASS.component)
        assertEquals("app.clairix.LauncherNotes", LauncherEntry.NOTES.component)
    }

    // ── Channels ─────────────────────────────────────────────────────────

    /** Every channel id the app creates, as ScanService, PlatformAlerts, CommsNotifications, FakeCall and the services name them. */
    private val channelIds = setOf(
        "scanning", "alerts_secret", "cell_alerts_secret",
        "phone_security_secret", "phone_settings_secret",
        "messages_private", "comms_link_secret", "calls_ringing", "calls", "group_sos_private",
        "mesh", "navigation", "drive_mode",
        "incoming_call", "incoming_call_ringtone",
    )

    @Test
    fun everyChannelHasANameInBothModes() {
        assertEquals(channelIds, ChannelNames.ids)
        assertEquals(PlatformAlerts.CHANNEL_SECURITY, "phone_security_secret")
        assertEquals(PlatformAlerts.CHANNEL_SETTINGS, "phone_settings_secret")
        assertEquals(FakeCall.CHANNEL, "incoming_call")
        assertEquals(FakeCall.CHANNEL_RINGTONE, "incoming_call_ringtone")
        for (id in channelIds) {
            assertTrue(id, ChannelNames.known(id))
            assertTrue(id, ChannelNames.of(id, discreet = false).name.isNotBlank())
            assertTrue(id, ChannelNames.of(id, discreet = true).name.isNotBlank())
        }
    }

    @Test
    fun ordinaryChannelNamesAreTheOnesTheAppAlwaysUsed() {
        assertEquals(ChannelText("Tracker alerts", "A device has been confirmed as following you"), ChannelNames.of("alerts_secret", false))
        assertEquals("Someone at your phone", ChannelNames.of("phone_security_secret", false).name)
        assertEquals("Group SOS", ChannelNames.of("group_sos_private", false).name)
        assertEquals("Offline mesh", ChannelNames.of("mesh", false).name)
        assertEquals(ChannelText("Drive mode", null), ChannelNames.of("drive_mode", false))
    }

    @Test
    fun discreetChannelNamesSayNothingAndCanBeToldApart() {
        val names = channelIds.map { ChannelNames.of(it, discreet = true) }
        names.forEach { assertNoTell(it.name); assertNoTell(it.description) }
        assertEquals("each discreet name is different", names.size, names.map { it.name }.toSet().size)
    }

    @Test
    fun anUnknownChannelGetsAGenericNameRatherThanFailing() {
        assertFalse(ChannelNames.known("not_a_channel"))
        assertEquals(ChannelText("Notifications", null), ChannelNames.of("not_a_channel", discreet = true))
        assertEquals("not_a_channel", ChannelNames.of("not_a_channel", discreet = false).name)
    }

    // ── Device admin and lock screen ─────────────────────────────────────

    @Test
    fun theDeviceAdminRequestDoesNotNameTheAppInDiscreetMode() {
        assertTrue(DiscreetText.adminExplanation(discreet = false).startsWith("Lets Clairix record"))
        val discreet = DiscreetText.adminExplanation(discreet = true)
        assertFalse(discreet, discreet.contains("Clairix"))
        assertTrue(discreet.contains("never wipes the phone"))
    }

    @Test
    fun theLockScreenDropsTheMarkAndLockedInDiscreetMode() {
        assertTrue(DiscreetText.lockShowsBrand(discreet = false))
        assertFalse(DiscreetText.lockShowsBrand(discreet = true))
        assertEquals("Clairix is locked", DiscreetText.lockHeading(discreet = false))
        assertEquals("Enter your passcode to continue", DiscreetText.lockPrompt(discreet = false))
        assertNoTell(DiscreetText.lockHeading(discreet = true))
        assertNoTell(DiscreetText.lockPrompt(discreet = true))
    }

    // ── Notifications ────────────────────────────────────────────────────

    @Test
    fun ongoingNotificationsReadRunningAndNothingElse() {
        val running = NotificationText("Running", "Tap to open")
        assertEquals(running, NotificationPresentation.ongoing(paused = false, discreet = true))
        assertEquals(running, NotificationPresentation.ongoing(paused = true, discreet = true))
        assertEquals(running, NotificationPresentation.service(NotificationText("Offline mesh on", "Carrying messages"), discreet = true))
        assertEquals(running, NotificationPresentation.link(connected = true, previewsHidden = false, discreet = true))
        assertEquals(running, NotificationPresentation.link(connected = false, previewsHidden = true, discreet = true))
        // Off, they are what they were.
        assertEquals(NotificationText("Clairix", "Scanning"), NotificationPresentation.ongoing(paused = false))
        val drive = NotificationText("Drive mode on", "Radio on · OBD-II connected")
        assertEquals(drive, NotificationPresentation.service(drive, discreet = false))
    }

    @Test
    fun anAlertNamesNothingInDiscreetModeWhateverTheDetailsSetting() {
        val shown = NotificationPresentation.alert(
            "AirTag is following you", "Heard 640 m apart.", locked = false, showDetails = true, discreet = true
        )!!
        assertEquals(NotificationText("New notification", "Tap to open"), shown)
        assertFalse(shown.detailed)
        // A silent alert stays silent.
        assertEquals(null, NotificationPresentation.alert("t", "x", locked = false, showDetails = true, silent = true, discreet = true))
        // Without discreet mode the details setting decides, as before.
        assertTrue(NotificationPresentation.alert("t", "x", locked = false, showDetails = true)!!.detailed)
    }

    @Test
    fun theLockScreenVersionNamesNothingInDiscreetMode() {
        for (text in listOf(NotificationPresentation.SCANNING, NotificationPresentation.RUNNING, NotificationPresentation.SCAN_PAUSED)) {
            assertEquals(NotificationText("Running", "Tap to open"), NotificationPresentation.publicText(text, discreet = true))
        }
        for (text in listOf(NotificationPresentation.UNLOCK_TO_READ, NotificationPresentation.UNLOCK_TO_SEE_ALERTS)) {
            assertEquals(NotificationText("New notification", "Tap to open"), NotificationPresentation.publicText(text, discreet = true))
            assertEquals(NotificationText("Clairix", text), NotificationPresentation.publicText(text, discreet = false))
        }
        assertEquals(NotificationText("New notification", "Tap to open"), NotificationPresentation.neutral(discreet = true))
    }

    @Test
    fun androidStoppingTheConnectionIsSaidWithoutTheName() {
        val shown = NotificationPresentation.linkStopped(previewsHidden = false, discreet = true)
        assertEquals("Stopped in the background", shown.title)
        assertNoTell(shown.title)
        assertNoTell(shown.text)
        assertEquals(NotificationPresentation.LINK_STOPPED_TITLE, NotificationPresentation.linkStopped(previewsHidden = false).title)
    }

    @Test
    fun commsWordingLosesTheAppsNameInDiscreetMode() {
        fun u(text: String) = NotificationPresentation.unbranded(text, discreet = true)
        assertEquals("New message", u("New Clairix message"))
        assertEquals("Call", u("Clairix call"))
        assertEquals("Incoming call", u("Incoming Clairix call"))
        assertEquals("Open the app to read", u("Open Clairix to read"))
        assertEquals("Unlock the app to see who and where", u("Unlock Clairix to see who and where"))
        assertEquals("Open the app to see the request", u("Open Clairix to see the request"))
        assertEquals("New notification", u("Clairix"))
        assertEquals("Unlock to read", u("Unlock to read"))
        listOf("New Clairix message", "Clairix call", "Open Clairix to see who", "Clairix").forEach {
            assertFalse(u(it).contains("Clairix"))
        }
        // Off, the wording is untouched.
        assertEquals("New Clairix message", NotificationPresentation.unbranded("New Clairix message", discreet = false))
    }
}
