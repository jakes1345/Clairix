package app.clairix.security

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.AppSettings
import app.clairix.EventKind
import app.clairix.MainActivity
import app.clairix.ScanService
import app.clairix.Severity

/** The two channels the platform monitors' alerts go out on; see [PlatformAlerts.channelFor]. */
enum class AlertChannel {
    /** "Someone at your phone": high importance, an alarm. */
    SOMEONE_AT_PHONE,
    /** "Phone settings to check": default importance, a status. */
    SETTINGS_TO_CHECK
}

/**
 * The notification shade's view of the platform monitors. [app.clairix.Registry.publishAlert]
 * calls [notify] for every alert of MEDIUM or worse once the timeline has
 * accepted it, so a standing condition the timeline deduplicates is also
 * posted once, not on every poll.
 *
 * What a notification says is decided by [NotificationPresentation]: nothing,
 * unless the app is unlocked and the owner asked for alert details in the
 * shade. Both channels are secret on the lock screen, and every notification
 * carries a neutral public version besides, so a locked phone shows nothing
 * of it whatever Android's own lock-screen setting says. Locking the app
 * ([redact]) replaces every alert notification, the scanner's included, with
 * one that says only to unlock.
 *
 * Two channels, chosen by what the alert is about ([channelFor]): "Someone at
 * your phone" carries what means someone has used the phone or is reading it
 * (a fingerprint or face added, an unlock while the owner slept, the screen lock
 * changed, the unlock ledger switched off, stalkerware, interception) as an
 * alarm; "Phone settings to check" carries the settings worth a look (Bluetooth
 * left discoverable, a proxy, the screen being recorded, a scanner that cannot
 * hear) at default importance. When the two shared one alarm channel, the
 * Bluetooth reminder was what got the channel switched off, and with it the
 * 3 a.m. unlock.
 */
object PlatformAlerts {

    /**
     * Both secret on the lock screen. A channel's lock-screen visibility and
     * importance cannot change once it exists, so these are new channels; the
     * ones they replace ([RETIRED_CHANNELS]) are deleted, and their notifications
     * with them.
     */
    const val CHANNEL_SECURITY = "phone_security_secret"
    const val CHANNEL_SETTINGS = "phone_settings_secret"

    /**
     * The single alarm channel these replace ("Phone security alerts", secret on
     * the lock screen) and the one before it, which was not.
     */
    private val RETIRED_CHANNELS = listOf("platform_alerts_secret", "platform_alerts")

    /**
     * Notification ids live in their own range so they cannot collide with the
     * scanner's (1, 2, hash of a device key) or comms' (0x5C00_xxxx). The low
     * bits come from the alert's dedupe key, so a repeat of the same condition
     * replaces its earlier notification instead of stacking.
     */
    private const val ID_BASE = 0x7A00_0000
    private const val ID_MASK = 0x00FF_FFFF
    /** The one "unlock to see alerts" notification that stands in for every alert once the app locks. */
    const val LOCKED_SUMMARY_ID = 0x7B00_0001

    /** The channels whose notifications name a finding: this object's two and the scanner's two. */
    private val ALERT_CHANNELS = setOf(CHANNEL_SECURITY, CHANNEL_SETTINGS, ScanService.CHANNEL_ALERT, ScanService.CHANNEL_CELL)

    /**
     * Which channel an alert goes out on, by what it is about ([Alert.kind]). The phone settings
     * worth a look (the screen being recorded, the clock) go to [AlertChannel.SETTINGS_TO_CHECK].
     * [EventKind.PLATFORM] is the kind every other platform monitor files under: there the
     * reminders, which are MEDIUM (Bluetooth left discoverable, a proxy, a scanner that cannot
     * hear), are settings to check, and what is HIGH or worse (stalkerware, an intercepted
     * connection) is someone at the phone. Everything else (an unlock while the owner slept, the
     * screen lock changed, the unlock ledger switched off, a fingerprint or face added, a microphone
     * that became the call audio with the screen off, a tracker, a rogue access point) is someone
     * at the phone.
     */
    fun channelFor(kind: EventKind, severity: Severity): AlertChannel = when (kind) {
        EventKind.SCREEN_RECORDING, EventKind.CLOCK_DRIFT -> AlertChannel.SETTINGS_TO_CHECK
        EventKind.PLATFORM ->
            if (severity.ordinal >= Severity.HIGH.ordinal) AlertChannel.SOMEONE_AT_PHONE else AlertChannel.SETTINGS_TO_CHECK
        else -> AlertChannel.SOMEONE_AT_PHONE
    }

    /** The channel id for [channel]. */
    fun channelId(channel: AlertChannel): String = when (channel) {
        AlertChannel.SOMEONE_AT_PHONE -> CHANNEL_SECURITY
        AlertChannel.SETTINGS_TO_CHECK -> CHANNEL_SETTINGS
    }

    /** Creates both channels and deletes the ones they replace. The name says "channel" for its callers. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (old in RETIRED_CHANNELS) runCatching { manager.deleteNotificationChannel(old) }
        // Named by ChannelNames: generic names while discreet mode is on.
        val discreet = DiscreetMode.on(context)
        val security = ChannelNames.of(CHANNEL_SECURITY, discreet)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SECURITY, security.name, NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = security.description
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
        )
        val settings = ChannelNames.of(CHANNEL_SETTINGS, discreet)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SETTINGS, settings.name, NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = settings.description
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
        )
    }

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * The version of a notification Android may show on the lock screen beside
     * a private one: the app's name, its own icon and [text], which names
     * nothing. Every alert and every service's ongoing notification carries one.
     */
    fun publicVersion(context: Context, channel: String, text: String = NotificationPresentation.UNLOCK_TO_READ): Notification {
        val shown = NotificationPresentation.publicText(text, DiscreetMode.on(context))
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(DiscreetMode.smallIcon(context))
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .build()
    }

    /**
     * Posts [alert] as [NotificationPresentation.alert] decides: its title and
     * detail only while the app is unlocked and the owner has turned alert
     * details on, "Unlock to read" otherwise, nothing for a silent one. On the
     * channel [channelFor] picks: an alarm for someone at the phone, a status
     * for a setting to check. A no-op without POST_NOTIFICATIONS; never throws.
     */
    fun notify(context: Context, alert: Alert) {
        if (!canPost(context)) return
        val shown = NotificationPresentation.alert(
            title = alert.title, text = alert.detail, bigText = alert.detail,
            locked = AppLock.isLocked(context), showDetails = AppSettings.showAlertDetails, silent = alert.silent,
            discreet = DiscreetMode.on(context)
        ) ?: return
        ensureChannel(context)
        val channel = channelFor(alert.kind, alert.severity)
        val channelId = channelId(channel)
        val atPhone = channel == AlertChannel.SOMEONE_AT_PHONE
        val icon = when {
            !shown.detailed -> DiscreetMode.smallIcon(context)
            alert.severity == Severity.CRITICAL || alert.severity == Severity.HIGH -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.stat_sys_warning
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .apply { shown.bigText?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setSmallIcon(icon)
            .setPriority(if (atPhone) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (atPhone) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setWhen(alert.ts)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(context, channelId))
            .build()
        val id = ID_BASE or ((alert.dedupeKey ?: alert.id).hashCode() and ID_MASK)
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    /**
     * The app has locked: every alert notification still showing, this
     * object's and the scanner's tracker and cell alerts, is cancelled and one
     * that names nothing stands in for them, so whoever holds the unlocked
     * phone learns only that something is waiting. The scanner's ongoing
     * notification is left: it says only that it is scanning. Nothing is posted
     * again on unlock; the timeline has every alert.
     */
    fun redact(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        var any = false
        runCatching {
            for (n in manager.activeNotifications) {
                if (n.notification.channelId !in ALERT_CHANNELS || n.id == LOCKED_SUMMARY_ID) continue
                manager.cancel(n.tag, n.id)
                any = true
            }
        }
        if (!any || !canPost(context)) return
        ensureChannel(context)
        val shown = NotificationPresentation.neutral(NotificationPresentation.UNLOCK_TO_SEE_ALERTS, DiscreetMode.on(context))
        // On the alarm channel, which is the one least likely to be switched off; silent below.
        val n = NotificationCompat.Builder(context, CHANNEL_SECURITY)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setSmallIcon(DiscreetMode.smallIcon(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Posted as the screen goes off or the app is left: nothing to sound about.
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(context, CHANNEL_SECURITY, NotificationPresentation.UNLOCK_TO_SEE_ALERTS))
            .build()
        runCatching { manager.notify(LOCKED_SUMMARY_ID, n) }
    }

    /** The lock has been passed: the stand-in [redact] posted goes. */
    fun unlocked(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(LOCKED_SUMMARY_ID) }
    }
}
