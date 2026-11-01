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
import app.clairix.MainActivity
import app.clairix.R
import app.clairix.ScanService
import app.clairix.Severity

/**
 * The notification shade's view of the platform monitors. [app.clairix.Registry.publishAlert]
 * calls [notify] for every alert of MEDIUM or worse once the timeline has
 * accepted it, so a standing condition the timeline deduplicates is also
 * posted once, not on every poll.
 *
 * What a notification says is decided by [NotificationPresentation]: nothing,
 * unless the app is unlocked and the owner asked for alert details in the
 * shade. The channel is secret on the lock screen, and every notification
 * carries a neutral public version besides, so a locked phone shows nothing
 * of it whatever Android's own lock-screen setting says. Locking the app
 * ([redact]) replaces every alert notification, the scanner's included, with
 * one that says only to unlock.
 *
 * One channel for all of them: the alerts are rare and each is a different
 * kind of "someone may be at your phone", so splitting them across channels
 * would only give the user more switches to turn the warning off with.
 */
object PlatformAlerts {

    /**
     * Secret on the lock screen. A channel's lock-screen visibility cannot change
     * once it exists, so this is a new channel; the old one is deleted, and its
     * notifications with it.
     */
    const val CHANNEL = "platform_alerts_secret"
    private const val LEGACY_CHANNEL = "platform_alerts"

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

    /** The channels whose notifications name a finding: this object's and the scanner's two. */
    private val ALERT_CHANNELS = setOf(CHANNEL, ScanService.CHANNEL_ALERT, ScanService.CHANNEL_CELL)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone security alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "A biometric was enrolled, the screen was recorded, the phone was " +
                        "unlocked while you slept, a wireless microphone appeared, or the clock is wrong"
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
        val shown = NotificationPresentation.neutral(text)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_clairix)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .build()
    }

    /**
     * Posts [alert] as [NotificationPresentation.alert] decides: its title and
     * detail only while the app is unlocked and the owner has turned alert
     * details on, "Unlock to read" otherwise, nothing for a silent one. A no-op
     * without POST_NOTIFICATIONS; never throws.
     */
    fun notify(context: Context, alert: Alert) {
        if (!canPost(context)) return
        val shown = NotificationPresentation.alert(
            title = alert.title, text = alert.detail, bigText = alert.detail,
            locked = AppLock.isLocked(context), showDetails = AppSettings.showAlertDetails, silent = alert.silent
        ) ?: return
        ensureChannel(context)
        val icon = when {
            !shown.detailed -> R.drawable.ic_stat_clairix
            alert.severity == Severity.CRITICAL || alert.severity == Severity.HIGH -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.stat_sys_warning
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .apply { shown.bigText?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setWhen(alert.ts)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(context, CHANNEL))
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
        val shown = NotificationPresentation.neutral(NotificationPresentation.UNLOCK_TO_SEE_ALERTS)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setSmallIcon(R.drawable.ic_stat_clairix)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Posted as the screen goes off or the app is left: nothing to sound about.
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(context, CHANNEL, NotificationPresentation.UNLOCK_TO_SEE_ALERTS))
            .build()
        runCatching { manager.notify(LOCKED_SUMMARY_ID, n) }
    }

    /** The lock has been passed: the stand-in [redact] posted goes. */
    fun unlocked(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(LOCKED_SUMMARY_ID) }
    }
}
