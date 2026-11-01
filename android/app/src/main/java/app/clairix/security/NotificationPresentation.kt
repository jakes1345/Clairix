package app.clairix.security

/**
 * What a notification outside Comms says. The rule lives here, with nothing
 * from Android in it, so it can be tested without a phone:
 *
 * - An alert (a tracker, a cell anomaly, one of the platform monitors) names
 *   what it found only while Clairix is unlocked *and* the owner has turned
 *   "Show alert details in notifications" on ([app.clairix.AppSettings.showAlertDetails],
 *   off by default). Otherwise it reads "Clairix · Unlock to read" and the
 *   finding waits in the app.
 * - An alert raised on the screen the owner is looking at (an optical or
 *   magnetic camera finding, a network finding while the sweep screen is up) is
 *   [Alert.silent][app.clairix.Alert.silent]: it goes on the timeline and raises
 *   no notification.
 * - The ongoing notifications (the scanner, drive mode, the mesh, navigation,
 *   the relay connection) say that something runs, never what it has heard.
 *   The one that says Android stopped the relay connection ([linkStopped])
 *   says, with previews hidden, only that Android stopped Clairix.
 * - Whatever the shade shows, the version Android may put on the lock screen
 *   is [neutral]: the app's name and one line that names nothing.
 * - In discreet mode ([DiscreetMode]) nothing names Clairix at all: an ongoing
 *   notification reads "Running", an alert "New notification" (never its
 *   detail, whatever the details setting), and Comms' own wording drops the
 *   app's name ([unbranded]).
 *
 * Comms applies the same rule to messages in [app.clairix.comms.CommsNotifications].
 */
data class NotificationText(
    val title: String,
    val text: String,
    /** The text of the expanded notification, when there is more than fits on one line. */
    val bigText: String? = null,
    /** True when the notification carries the finding; false when it says only to unlock. */
    val detailed: Boolean = false
)

object NotificationPresentation {

    const val APP = "Clairix"
    const val UNLOCK_TO_READ = "Unlock to read"
    /** The one notification that stands in for every alert cancelled when the app locks. */
    const val UNLOCK_TO_SEE_ALERTS = "Unlock to see alerts"
    const val SCANNING = "Scanning"
    const val SCAN_PAUSED = "Scan paused — retrying"
    /** The scanner runs in low power (Settings > Battery); it says nothing of what was heard. */
    const val SCANNING_LOW_POWER = "Scanning · low power"
    /** What an ongoing service's notification says on the lock screen, and its title in discreet mode. */
    const val RUNNING = "Running"
    /** Discreet mode's one line for every notification: no name, no finding. */
    const val TAP_TO_OPEN = "Tap to open"
    /** Discreet mode's title for an alert: it says that there is something, not whose or what. */
    const val NEW_NOTIFICATION = "New notification"

    /**
     * The app's name and [text], which names nothing: the lock-screen version of
     * every notification, and the shade's default. In [discreet] mode not even the
     * name: "New notification · Tap to open".
     */
    fun neutral(text: String = UNLOCK_TO_READ, discreet: Boolean = false): NotificationText =
        if (discreet) NotificationText(NEW_NOTIFICATION, TAP_TO_OPEN) else NotificationText(APP, text)

    /** An ongoing service's notification in discreet mode, in the shade and on the lock screen alike. */
    val DISCREET_RUNNING = NotificationText(RUNNING, TAP_TO_OPEN)

    /**
     * The lock-screen ([android.app.Notification.publicVersion]) text of a notification whose
     * private text is [text]: [neutral] normally; in [discreet] mode "Running" for an ongoing
     * service ([SCANNING], [RUNNING]) and "New notification" for anything else.
     */
    fun publicText(text: String, discreet: Boolean): NotificationText = when {
        !discreet -> neutral(text)
        text == SCANNING || text == RUNNING || text == SCAN_PAUSED -> DISCREET_RUNNING
        else -> neutral(discreet = true)
    }

    /**
     * An ongoing service's own notification (drive mode, navigation, the mesh): [shown] as the
     * service words it, or only "Running" in [discreet] mode, with what runs left to the app.
     */
    fun service(shown: NotificationText, discreet: Boolean): NotificationText =
        if (discreet) DISCREET_RUNNING else shown

    /** Whether an alert notification may carry its detail: only while Clairix is unlocked, and only if the owner asked. */
    fun detailsAllowed(locked: Boolean, showDetails: Boolean): Boolean = showDetails && !locked

    /**
     * An alert as the shade shows it: [title], [text] and [bigText] when
     * [detailsAllowed], [neutral] otherwise, and null for a [silent] alert,
     * which raises no notification at all.
     */
    fun alert(
        title: String,
        text: String,
        bigText: String? = null,
        locked: Boolean,
        showDetails: Boolean,
        silent: Boolean = false,
        discreet: Boolean = false
    ): NotificationText? = when {
        silent -> null
        // Discreet mode overrides the details setting: the shade names nothing.
        discreet -> neutral(discreet = true)
        detailsAllowed(locked, showDetails) -> NotificationText(title, text, bigText, detailed = true)
        else -> neutral()
    }

    /**
     * The scanner's ongoing notification: that it runs, in low power or not, or that it
     * is retrying; the counts are in the app. In [discreet] mode only "Running", which
     * does not say that it scans.
     */
    fun ongoing(paused: Boolean, lowPower: Boolean = false, discreet: Boolean = false): NotificationText = when {
        discreet -> DISCREET_RUNNING
        paused -> neutral(SCAN_PAUSED)
        lowPower -> neutral(SCANNING_LOW_POWER)
        else -> neutral(SCANNING)
    }

    /**
     * Whether the scanner's ongoing notification carries its "Stop scanning" button. Not while the
     * app lock is armed (a passcode is set): the button works from the shade without the passcode,
     * so it would let anyone holding the phone switch the scanner off; the switch inside the app,
     * behind the lock, still does. Without a passcode it stays, asking for the phone's own unlock
     * when pressed on the lock screen. The notification's text is the same either way.
     */
    fun ongoingStopAction(lockArmed: Boolean): Boolean = !lockArmed

    /**
     * The relay connection's notification. It never carries the Clairix number;
     * with previews hidden it does not even say that it is comms.
     */
    fun link(connected: Boolean, previewsHidden: Boolean, discreet: Boolean = false): NotificationText = when {
        discreet -> DISCREET_RUNNING
        previewsHidden -> neutral(if (connected) "Connected" else "Reconnecting")
        connected -> NotificationText("Comms online", "Connected to your relay · encrypted messages arrive instantly")
        else -> NotificationText("Comms reconnecting", "Connecting to your relay")
    }

    /** What the notification says when Android has stopped the background connection; with previews hidden, that Android stopped Clairix and nothing about messages. */
    const val LINK_STOPPED_TITLE = "Android stopped Clairix's connection"
    const val LINK_STOPPED_TEXT = "Tap to allow it to run"
    const val LINK_STOPPED_NEUTRAL = "Android stopped it in the background. Tap to allow it to run"

    /**
     * Android refused, twice in a row, to restart the relay connection. With
     * previews hidden (the owner's setting, or Clairix locked) it does not say
     * that it is about a connection; the lock-screen version is always that.
     */
    fun linkStopped(previewsHidden: Boolean, discreet: Boolean = false): NotificationText = when {
        discreet -> NotificationText(LINK_STOPPED_DISCREET_TITLE, LINK_STOPPED_TEXT)
        previewsHidden -> neutral(LINK_STOPPED_NEUTRAL)
        else -> NotificationText(LINK_STOPPED_TITLE, LINK_STOPPED_TEXT)
    }

    /** [linkStopped]'s title in discreet mode: what to do, without the app's name. */
    const val LINK_STOPPED_DISCREET_TITLE = "Stopped in the background"

    /**
     * Comms' notification wording with the app's name taken out in [discreet] mode:
     * "New Clairix message" reads "New message", "Clairix call" reads "Call",
     * "Open Clairix to read" reads "Open the app to read", and the bare name reads
     * [NEW_NOTIFICATION]. Unchanged otherwise.
     */
    fun unbranded(text: String, discreet: Boolean): String {
        if (!discreet || !text.contains(APP)) return text
        if (text.trim() == APP) return NEW_NOTIFICATION
        val out = text
            .replace(Regex("\\b$APP (call|message|number)"), "$1")
            .replace(Regex("\\b$APP's\\b"), "the app's")
            .replace(Regex("\\b$APP\\b"), "the app")
        return out.replaceFirstChar { it.uppercaseChar() }
    }
}
