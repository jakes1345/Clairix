package app.clairix.comms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.clairix.CallActivity
import app.clairix.MainActivity
import app.clairix.security.AppLock

/**
 * Notifications for the comms module: one per conversation for new messages,
 * updated in place as more arrive, and the quiet one the relay connection
 * service shows while comms are online.
 */
object CommsNotifications {

    /** Index of the COMMS tab in MainActivity's tab row. */
    const val COMMS_TAB_INDEX = 7

    /**
     * Its lock-screen visibility is private: on a locked phone a message shows
     * only as the redacted version, whatever Android's own lock-screen setting.
     * A channel's lock-screen visibility cannot change once it exists, so this
     * is a new channel; the old one ("messages") is deleted.
     */
    const val CHANNEL_MESSAGES = "messages_private"
    const val CHANNEL_LINK = "comms_link"
    const val CHANNEL_CALLS = "calls"
    /**
     * Incoming calls. The original calls channel had vibration off, so a ring
     * whose in-app vibration Android suppressed was silent in vibrate mode.
     * Channel settings are fixed once created, hence a new id.
     */
    const val CHANNEL_RINGING = "calls_ringing"
    /** An SOS from a member of one of the owner's groups: loud, and shown on the lock screen. */
    /** Private on the lock screen like messages (the old "group_sos" channel was public, and is deleted). */
    const val CHANNEL_SOS = "group_sos_private"

    /** Intent extras naming the tab and conversation MainActivity should open. */
    const val EXTRA_TAB = "app.clairix.TAB"
    const val EXTRA_PEER = "app.clairix.PEER"
    /**
     * Set when the notification's Answer button was tapped: accept the ringing
     * call. Read only by [CallActivity], which is not exported, and only for
     * the call named by [EXTRA_CALL_ID]; no other app can make Clairix answer.
     */
    const val EXTRA_ACCEPT_CALL = "app.clairix.ACCEPT_CALL"
    /** The call a call notification or its buttons are about. */
    const val EXTRA_CALL_ID = "app.clairix.CALL_ID"

    /** Notification id of the connection service's foreground notification. */
    const val LINK_NOTIFICATION_ID = 0x5C00_0001
    const val INCOMING_CALL_NOTIFICATION_ID = 0x5C00_0002
    const val CALL_NOTIFICATION_ID = 0x5C00_0003
    /** The one "unlock to read" notification that stands in for every message notification once the app locks. */
    const val SUMMARY_NOTIFICATION_ID = 0x5C00_0004

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (old in listOf("messages", "group_sos")) runCatching { manager.deleteNotificationChannel(old) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Encrypted messages to your Clairix number"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Comms connection", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shown while Clairix keeps its connection to your relay open"
                    setShowBadge(false)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Encrypted calls ringing on this phone"
                    // The app plays the phone's own ringtone; the channel adds a
                    // vibration that still works when Android mutes the app's own.
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 1200, 800, 1200)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Incoming and ongoing encrypted calls"
                    // The app rings with the phone's own ringtone; the channel stays quiet.
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, "Group SOS", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "An SOS sent by a member of one of your groups"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setBypassDnd(true)
                }
        )
    }

    // ── Calls ────────────────────────────────────────────────────────────

    private fun person(contact: Contact) = Person.Builder()
        .setName(contact.name.ifBlank { formatClairixNumber(contact.number) })
        .setImportant(true)
        .build()

    /**
     * The call screen: [CallActivity], which shows over the lock screen and
     * turns the display on, unlike the main screen.
     */
    private fun openCallScreen(context: Context, callId: String, accept: Boolean, requestCode: Int): PendingIntent {
        val open = Intent(context, CallActivity::class.java)
            .setAction(if (accept) "app.clairix.ACCEPT_CALL" else "app.clairix.OPEN_CALL")
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_ACCEPT_CALL, accept)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Rings for an incoming call: a call-style card with Decline and Answer, full screen when allowed. */
    fun incomingCall(context: Context, call: ActiveCall) {
        if (!canPost(context)) return
        ensureChannels(context)
        val decline = PendingIntent.getBroadcast(
            context, INCOMING_CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_PEER, call.peer.number),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = openCallScreen(context, call.id, accept = true, requestCode = INCOMING_CALL_NOTIFICATION_ID + 2)
        val show = openCallScreen(context, call.id, accept = false, requestCode = INCOMING_CALL_NOTIFICATION_ID + 3)
        // The ringing card shows on the lock screen so the call can be answered;
        // with previews hidden it does not say who is calling until unlocked.
        val hidden = previewsHidden(context)
        val caller = if (hidden) Person.Builder().setName("Clairix call").setImportant(true).build() else person(call.peer)
        val notification = NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming encrypted call")
            .setContentText(if (hidden) "Unlock to see who is calling" else call.peer.name.ifBlank { formatClairixNumber(call.peer.number) } + if (call.peer.verified) "" else " (unverified)")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(50_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    /** The foreground notification behind [CallService] while a call is up. */
    fun ongoingCall(context: Context, call: ActiveCall): Notification {
        ensureChannels(context)
        val hangUp = PendingIntent.getBroadcast(
            context, CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_HANGUP)
                .putExtra(EXTRA_CALL_ID, call.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (call.phase) {
            CallPhase.DIALING -> if (call.ringing) "Ringing…" else "Calling…"
            CallPhase.CONNECTING -> "Connecting…"
            CallPhase.CONNECTED -> "Encrypted call in progress"
            CallPhase.RECONNECTING -> "Reconnecting…"
            CallPhase.INCOMING -> "Ringing"
            CallPhase.ENDED -> "Call ended"
        }
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(call.peer.name.ifBlank { formatClairixNumber(call.peer.number) })
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call.peer), hangUp))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openCallScreen(context, call.id, accept = false, requestCode = CALL_NOTIFICATION_ID + 2))
            .apply { if (call.connectedAt > 0L) setWhen(call.connectedAt).setUsesChronometer(true) }
            .build()
    }

    /** A call that rang out, or arrived while the phone was offline or busy. */
    fun missedCall(context: Context, contact: Contact) {
        if (!canPost(context)) return
        ensureChannels(context)
        if (previewsHidden(context)) {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(missedCallId(contact.number), unlockToRead(context, "Missed call", "Open Clairix to see who", contact.number.hashCode() xor 0x4D))
            return
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction("app.clairix.OPEN_THREAD")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_PEER, contact.number)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, contact.number.hashCode() xor 0x4D, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(contact.name.ifBlank { formatClairixNumber(contact.number) })
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setShowWhen(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(missedCallId(contact.number), notification)
    }

    /**
     * Whether an incoming call may take over the screen of a locked phone. From
     * Android 14 this is a special permission the owner can switch off (and
     * some phones ship with it off); without it the call rings as a heads-up
     * notification only.
     */
    fun canUseFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
        } else true

    /**
     * Whether the incoming-call channel can still ring: the owner (or a phone's
     * notification manager) can switch it off or lower it to silent, and then
     * a call arrives with no heads-up and no full-screen ring even though
     * notifications as a whole are allowed.
     */
    fun callsChannelOk(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        ensureChannels(context)
        val channel = manager.getNotificationChannel(CHANNEL_RINGING) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /** Opens the system settings of the incoming-call channel. */
    fun openCallsChannelSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_RINGING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppNotificationSettings(context) }
    }

    /** Opens Clairix's notification settings, where a permanently refused permission can be granted. */
    fun openAppNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens the system page where full-screen calls are allowed for Clairix (Android 14+). */
    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppNotificationSettings(context) }
    }

    /**
     * The foreground notification [CallService] shows when it starts after the
     * call has already ended: a service started in the foreground must go
     * foreground before it may stop, or Android kills the app.
     */
    fun callEndedPlaceholder(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call ended")
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    /**
     * Whether a message notification would be shown. POST_NOTIFICATIONS is a
     * runtime permission only from API 33; on 31 and 32 the permission check
     * reports "denied" for a permission that does not exist there, so those
     * versions ask the notification manager instead.
     */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** The owner's setting: whether notifications name no contact and quote nothing (see [CommsConfig.hidePreviews]). */
    fun hidePreviews(context: Context): Boolean = CommsConfig(context).hidePreviews()

    /**
     * Whether a notification posted now must name no one and quote nothing: the
     * owner's setting, or the app being locked. One that arrives while Clairix is
     * locked is redacted from the start, not only those showing when it locked.
     */
    private fun previewsHidden(context: Context): Boolean = hidePreviews(context) || AppLock.locked.value

    /** The one message notification shown while previews are hidden or the app is locked: it says only that there is something. */
    private fun unlockToRead(context: Context, title: String, text: String, requestCode: Int): Notification {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .build()
            )
            .build()
    }

    /**
     * The app has locked: every message and missed-call notification still
     * showing is replaced by one that names nobody and quotes nothing, so
     * whoever holds the unlocked phone learns only that something is waiting.
     * SOS alerts and calls are left: they are meant to be seen.
     */
    fun redact(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        var any = false
        runCatching {
            for (n in manager.activeNotifications) {
                val mine = n.notification.channelId == CHANNEL_MESSAGES || (n.id and 0x7F00_0000) == 0x5B00_0000
                if (!mine || n.id == SUMMARY_NOTIFICATION_ID) continue
                manager.cancel(n.tag, n.id)
                any = true
            }
        }
        if (any && canPost(context)) {
            ensureChannels(context)
            manager.notify(SUMMARY_NOTIFICATION_ID, unlockToRead(context, "Clairix", "Unlock to read", SUMMARY_NOTIFICATION_ID))
        }
    }

    /** Posts (or replaces) the notification for [contact] with the newest inbound messages. */
    fun notifyInbound(context: Context, contact: Contact, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        if (!canPost(context)) return
        ensureChannels(context)
        if (previewsHidden(context)) {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(notificationId(contact.number), unlockToRead(context, "New message", "Open Clairix to read", contact.number.hashCode()))
            return
        }

        val open = Intent(context, MainActivity::class.java)
            .setAction("app.clairix.OPEN_THREAD")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_PEER, contact.number)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, contact.number.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val newest = messages.maxBy { it.ts }
        val style = NotificationCompat.InboxStyle()
        messages.sortedBy { it.ts }.takeLast(5).forEach { style.addLine(it.preview()) }
        val title = contact.name.ifBlank { formatClairixNumber(contact.number) }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (contact.verified) title else "$title (unverified)")
            .setContentText(newest.preview())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setWhen(newest.ts)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("New message")
                    .setContentText("Unlock to read")
                    .build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(notificationId(contact.number), notification)
    }

    /**
     * Something from [contact]'s new keys is held for review: a notification
     * that opens the conversation, where the review card is. [alert] is true
     * for the first item held; later items only update a notification still
     * showing, quietly.
     */
    fun notifyHeld(context: Context, contact: Contact, held: Int, alert: Boolean) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = notificationId(contact.number)
        if (!alert && runCatching { manager.activeNotifications.none { it.id == id } }.getOrDefault(true)) return
        ensureChannels(context)
        if (previewsHidden(context)) {
            manager.notify(id, unlockToRead(context, "Safety number changed", "Open Clairix to review", contact.number.hashCode()))
            return
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction("app.clairix.OPEN_THREAD")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_PEER, contact.number)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, contact.number.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = contact.name.ifBlank { formatClairixNumber(contact.number) }
        val text = KeyChangeGuard.heldNotice(label, held)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Safety number changed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Safety number changed")
                    .setContentText("Unlock to review")
                    .build()
            )
            .build()
        manager.notify(id, notification)
    }

    /** The group conversation [gid] in MainActivity; see [CommsRepository.EXTRA_GROUP]. */
    private fun openGroup(context: Context, gid: String, requestCode: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .setAction(CommsRepository.ACTION_OPEN_GROUP)
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(CommsRepository.EXTRA_GROUP, gid)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Posts (or replaces) the notification for group [group] with its newest
     * unread messages, each prefixed with its sender as [senderName] gives it.
     * Keyed on the group id, so [cancel] with that id clears it.
     */
    fun notifyGroupInbound(context: Context, group: Group, messages: List<ChatMessage>, senderName: (String) -> String) {
        if (messages.isEmpty()) return
        if (!canPost(context)) return
        ensureChannels(context)
        if (previewsHidden(context)) {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(notificationId(group.id), unlockToRead(context, "New group message", "Open Clairix to read", group.id.hashCode()))
            return
        }
        val newest = messages.maxBy { it.ts }
        fun line(m: ChatMessage) = (m.sender?.let { "${senderName(it)}: " } ?: "") + m.preview()
        val style = NotificationCompat.InboxStyle()
        messages.sortedBy { it.ts }.takeLast(5).forEach { style.addLine(line(it)) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(group.name)
            .setContentText(line(newest))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openGroup(context, group.id, group.id.hashCode()))
            .setWhen(newest.ts)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("New group message")
                    .setContentText("Unlock to read")
                    .build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(notificationId(group.id), notification)
    }

    /**
     * An SOS from [fromName] in [group]: its own notification, never replaced
     * by a later message, with the position they sent (when they had one).
     */
    fun notifyPanic(context: Context, group: Group, panicId: String, fromName: String, lat: Double?, lon: Double?, note: String, ts: Long) {
        if (!canPost(context)) return
        ensureChannels(context)
        val where = if (lat != null && lon != null) String.format(java.util.Locale.US, "at %.5f, %.5f", lat, lon) else "no position attached"
        val text = buildString {
            append(group.name).append(" · ").append(where)
            if (note.isNotBlank()) append("\n").append(note)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_SOS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("SOS from $fromName")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(openGroup(context, group.id, panicId.hashCode()))
            .setWhen(ts)
            .setShowWhen(true)
            // Seen on a locked phone, it says an SOS came, not from whom or where:
            // a member's position is not for whoever holds a phone that is not theirs.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_SOS)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("SOS from a group member")
                    .setContentText("Unlock to see who and where")
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(panicId(panicId), notification)
    }

    /**
     * Clears every comms notification still showing: messages, missed calls,
     * SOS alerts, calls and the connection's own. Notifications of the rest of
     * the app (the scanner's, say) are left alone.
     */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val comms = setOf(CHANNEL_MESSAGES, CHANNEL_LINK, CHANNEL_CALLS, CHANNEL_RINGING, CHANNEL_SOS)
        runCatching {
            for (n in manager.activeNotifications) {
                if (n.notification.channelId in comms) manager.cancel(n.tag, n.id)
            }
        }
        manager.cancel(INCOMING_CALL_NOTIFICATION_ID)
        manager.cancel(CALL_NOTIFICATION_ID)
        manager.cancel(LINK_NOTIFICATION_ID)
    }

    /** Clears both the message and the missed-call notification for [peer]. */
    fun cancel(context: Context, peer: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(notificationId(peer))
        manager.cancel(missedCallId(peer))
    }

    /** The ongoing notification behind the relay connection service. */
    fun linkNotification(context: Context, connected: Boolean, number: String?): Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction("app.clairix.OPEN_COMMS")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, LINK_NOTIFICATION_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context, LINK_NOTIFICATION_ID + 1,
            Intent(context, CommsService::class.java).setAction(CommsService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(if (connected) "Comms online" else "Comms reconnecting")
            .setContentText(
                number?.let { "Clairix number ${formatClairixNumber(it)} · encrypted messages arrive instantly" }
                    ?: "Connecting to your relay"
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, "Go offline", stop)
            .build()
    }

    private fun notificationId(peer: String) = 0x5A00_0000 or (peer.hashCode() and 0x00FF_FFFF)
    private fun missedCallId(peer: String) = 0x5B00_0000 or (peer.hashCode() and 0x00FF_FFFF)
    private fun panicId(id: String) = 0x5D00_0000 or (id.hashCode() and 0x00FF_FFFF)
}
