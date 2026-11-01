package app.clairix.security

/**
 * Discreet mode's decisions, with nothing from Android in them so they can be
 * tested without a phone. [DiscreetMode] applies them.
 *
 * For a survivor whose abuser looks through the phone, finding a
 * counter-surveillance app is itself the discovery. Discreet mode makes Clairix
 * less obvious at a glance: a neutral launcher entry where the edition allows it,
 * notifications that say only that something runs, generic notification channel
 * names, a neutral device-admin request and a lock screen without the logo or the
 * word LOCKED. What it cannot change is Android's own record of the package:
 * Settings → Apps and the device-admin list still name Clairix, because an app's
 * name in the package cannot change at run time.
 */

/**
 * The launcher entries the app declares, each an activity-alias of MainActivity
 * (src/main/AndroidManifest.xml). Exactly one is enabled at a time.
 * [CLAIRIX] is the real one; the other two exist only in the full edition (the
 * Play edition's manifest removes them, src/play/AndroidManifest.xml).
 */
enum class LauncherEntry(val component: String, val label: String) {
    CLAIRIX("app.clairix.LauncherClairix", "Clairix"),
    COMPASS("app.clairix.LauncherCompass", "Compass"),
    NOTES("app.clairix.LauncherNotes", "Notes");

    val disguise: Boolean get() = this != CLAIRIX
}

/** Which launcher entry shows, and which are switched on and off to get there. */
object LauncherPlan {

    /** The neutral entries the owner can choose between, in the order Settings offers them. */
    val DISGUISES: List<LauncherEntry> = LauncherEntry.entries.filter { it.disguise }

    /** The disguise picked when the owner has not chosen one. */
    val DEFAULT_DISGUISE: LauncherEntry = LauncherEntry.COMPASS

    /**
     * The entry the launcher should show. The disguise only with discreet mode on
     * *and* an edition that may disguise its icon ([disguiseAllowed], false on Google
     * Play, whose policy does not let an app disguise or hide its launcher icon);
     * otherwise the real Clairix entry, whatever was chosen.
     */
    fun shown(discreet: Boolean, chosen: LauncherEntry, disguiseAllowed: Boolean): LauncherEntry =
        if (discreet && disguiseAllowed && chosen.disguise) chosen else LauncherEntry.CLAIRIX

    /**
     * Every entry the edition declares and whether it should be enabled: exactly
     * one true. An edition without the disguises declares only [LauncherEntry.CLAIRIX],
     * so only it is named; switching a component the package does not have would throw.
     */
    fun states(discreet: Boolean, chosen: LauncherEntry, disguiseAllowed: Boolean): Map<LauncherEntry, Boolean> {
        val shown = shown(discreet, chosen, disguiseAllowed)
        val declared = if (disguiseAllowed) LauncherEntry.entries else listOf(LauncherEntry.CLAIRIX)
        return declared.associateWith { it == shown }
    }

    /** A stored disguise name read back: one of [DISGUISES], or [DEFAULT_DISGUISE] for anything else. */
    fun disguiseNamed(name: String?): LauncherEntry =
        DISGUISES.firstOrNull { it.name == name } ?: DEFAULT_DISGUISE
}

/** One notification channel's name and description as Android's settings show them. */
data class ChannelText(val name: String, val description: String?)

/**
 * Every notification channel the app creates, with its ordinary and its
 * discreet name and description. A channel keeps its id; [DiscreetMode] renames
 * the existing ones when the mode changes, and each place that creates a channel
 * asks [of] for the text to create it with. Comms, the fake call and the
 * scanner's services are all here, so no channel names Clairix or what it
 * watches while discreet mode is on.
 */
object ChannelNames {

    private class Entry(val normal: ChannelText, val discreet: ChannelText)

    private val TABLE: Map<String, Entry> = mapOf(
        // The scanner (ScanService).
        "scanning" to Entry(
            ChannelText("Scanning", "Shown while the detector is running"),
            ChannelText("Running", "Shown while the app runs in the background")
        ),
        "alerts_secret" to Entry(
            ChannelText("Tracker alerts", "A device has been confirmed as following you"),
            ChannelText("Alerts", "Alerts from the app")
        ),
        "cell_alerts_secret" to Entry(
            ChannelText("Phone network alerts", "A fake cell tower may be nearby, or your phone was pushed onto an older, weaker network"),
            ChannelText("Other alerts", "Other alerts from the app")
        ),
        // The platform monitors (PlatformAlerts).
        "phone_security_secret" to Entry(
            ChannelText(
                "Someone at your phone",
                "A fingerprint or face was added, the phone was unlocked while you slept, the " +
                    "screen lock was changed or unlock recording was switched off, and other findings that " +
                    "need you now: stalkerware, an intercepted connection, a device travelling with you, a " +
                    "microphone that became the call audio with the screen off"
            ),
            ChannelText("Important", "Notifications that need you now")
        ),
        "phone_settings_secret" to Entry(
            ChannelText(
                "Phone settings to check",
                "Bluetooth left discoverable, a proxy on your network, the screen being recorded, " +
                    "or a scanner that cannot hear"
            ),
            ChannelText("Reminders", "Things to look at when you have a minute")
        ),
        // Comms (CommsNotifications).
        "messages_private" to Entry(
            ChannelText("Messages", "Encrypted messages to your Clairix number"),
            ChannelText("Messages", "New messages")
        ),
        "comms_link_secret" to Entry(
            ChannelText("Comms connection", "Shown while Clairix keeps its connection to your relay open, and when Android stops it"),
            ChannelText("Connection", "Shown while the app stays connected")
        ),
        "calls_ringing" to Entry(
            ChannelText("Incoming calls", "Encrypted calls ringing on this phone"),
            ChannelText("Incoming calls", "Calls ringing on this phone")
        ),
        "calls" to Entry(
            ChannelText("Calls", "Incoming and ongoing encrypted calls"),
            ChannelText("Calls", "Incoming and ongoing calls")
        ),
        "group_sos_private" to Entry(
            ChannelText("Group SOS", "An SOS sent by a member of one of your groups"),
            ChannelText("Urgent", "Urgent notifications from your groups")
        ),
        // The offline mesh (MeshService), navigation and drive mode.
        "mesh" to Entry(
            ChannelText("Offline mesh", "Shown while this phone carries messages over Bluetooth"),
            ChannelText("Background", "Shown while the app works in the background")
        ),
        "navigation" to Entry(
            ChannelText("Navigation", null),
            ChannelText("Directions", null)
        ),
        "drive_mode" to Entry(
            ChannelText("Drive mode", null),
            ChannelText("Trip", null)
        ),
        // The fake call (FakeCall), already named like any app's call channels.
        "incoming_call" to Entry(
            ChannelText("Call screen", "Shows a ringing call over the lock screen"),
            ChannelText("Call screen", "Shows a ringing call over the lock screen")
        ),
        "incoming_call_ringtone" to Entry(
            ChannelText("Call ringtone", "Rings with the phone's ringtone when Clairix cannot play it itself"),
            ChannelText("Call ringtone", "Rings with the phone's ringtone")
        ),
    )

    /** The ids of every channel listed here. */
    val ids: Set<String> get() = TABLE.keys

    /** Whether [id] is one of the app's channels. */
    fun known(id: String): Boolean = id in TABLE

    /**
     * The name and description to create channel [id] with. A channel not listed
     * (none should be) gets a generic name in discreet mode and its id otherwise,
     * rather than failing the notification that needed it.
     */
    fun of(id: String, discreet: Boolean): ChannelText {
        val entry = TABLE[id] ?: return if (discreet) ChannelText("Notifications", null) else ChannelText(id, null)
        return if (discreet) entry.discreet else entry.normal
    }
}

/** The words discreet mode puts where Clairix would otherwise name itself or what it does. */
object DiscreetText {

    /**
     * What Android's "activate device admin" screen shows under the request, which
     * anyone looking at the phone can read. In discreet mode it does not name the app.
     */
    fun adminExplanation(discreet: Boolean): String =
        if (discreet) {
            "Lets this app record lock-screen events: unlocks, failed unlocks and PIN changes. It can also lock " +
                "the screen when you ask it to. It never wipes the phone or sets a password policy."
        } else {
            "Lets Clairix record lock-screen events: unlocks, failed unlocks and PIN changes. It can also " +
                "lock the screen when you ask it to. It never wipes the phone or sets a password policy."
        }

    /** Whether the lock screen shows the Clairix mark and the word LOCKED: not in discreet mode. */
    fun lockShowsBrand(discreet: Boolean): Boolean = !discreet

    /** The lock screen's heading for screen readers. */
    fun lockHeading(discreet: Boolean): String = if (discreet) "Enter passcode" else "Clairix is locked"

    /** The line under the lock screen's heading. */
    fun lockPrompt(discreet: Boolean): String = if (discreet) "Enter passcode" else "Enter your passcode to continue"
}
