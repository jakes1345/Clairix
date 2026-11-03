package app.clairix.security

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.clairix.R
import app.clairix.ui.theme.DecoyCallPalette
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.clairix.CallActivity
import app.clairix.comms.CallManager
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import app.clairix.comms.formatClairixNumber
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * A fake incoming call: a way out of a conversation or a situation that is
 * going wrong. The owner schedules it for 30 s, 2 min or 5 min ahead; the phone
 * then rings with its own ringtone and vibration and shows an incoming-call
 * screen for a trusted contact of their choosing. Answering either holds a
 * pretend call (a timer and an end button, so the owner can talk to nobody)
 * or, when a contact's Clairix number was given, places a real encrypted call
 * to them through [CallManager].
 *
 * The ring does not depend on any screen opening. The alarm starts
 * [FakeCallRingService], a short foreground service whose notification is the
 * incoming-call card (its full-screen intent opens [FakeCallActivity] over a
 * locked or dark screen); the service rings through [FakeCallRinger], which
 * holds a wake lock and ends the ring after [RING_MS]. The screen rings through
 * the same [FakeCallRinger], so whichever comes second adds nothing and the call
 * never sounds twice. When Android will not let the service start from the
 * background (no exact-alarm permission, so the alarm was inexact), or Clairix
 * cannot play the ringtone, the card itself rings instead: it goes out on
 * [CHANNEL_RINGTONE], whose sound is the phone's ringtone, repeated until the
 * card goes. Answer, decline and the page's Cancel all end the ring through
 * [stopRinging].
 *
 * What a ring needs is [readiness]: notifications and full-screen calls allowed
 * (the page schedules nothing without them), and exact alarms for a ring on the
 * second from the background.
 */
object FakeCall {

    const val DELAY_30S = 30_000L
    const val DELAY_2MIN = 2 * 60_000L
    const val DELAY_5MIN = 5 * 60_000L

    private const val PREFS = "fake_call"
    private const val KEY_CALLER_NAME = "caller_name"
    private const val KEY_CALLER_NUMBER = "caller_number"
    private const val KEY_PENDING_CONTACT = "pending_contact"
    private const val KEY_PENDING_AT = "pending_at"

    /**
     * The call card while Clairix rings the call itself: no sound of its own, so
     * the ringtone is heard once, plus the standard vibration, which survives
     * Android muting the app's own.
     */
    const val CHANNEL = "incoming_call"
    /**
     * The call card when Clairix cannot ring the call itself: the sound is the
     * phone's ringtone, repeated ([Notification.FLAG_INSISTENT]) until the card
     * goes. Channel settings are fixed once a channel exists, so the sound has a
     * channel of its own, and the old silent "fake_call" channel, whose name and
     * description gave the feature away, is deleted rather than changed.
     */
    const val CHANNEL_RINGTONE = "incoming_call_ringtone"
    private const val OLD_CHANNEL = "fake_call"
    const val NOTIFICATION_ID = 0x5D00_0001
    private const val ALARM_REQUEST = 0x5D00_0002

    const val ACTION_RING = "app.clairix.FAKE_CALL_RING"
    const val ACTION_SHOW = "app.clairix.FAKE_CALL_SHOW"
    const val ACTION_ANSWER = "app.clairix.FAKE_CALL_ANSWER"
    const val ACTION_DECLINE = "app.clairix.FAKE_CALL_DECLINE"
    const val EXTRA_CONTACT = "app.clairix.FAKE_CALL_CONTACT"

    /** How long the phone rings, as a real call does, before the call is "missed". */
    internal const val RING_MS = 45_000L
    /**
     * A moment longer than the ring: how long the quiet card lasts should nothing
     * take it down, and how long the ring's wake lock may be held at most.
     */
    internal const val CARD_TIMEOUT_MS = RING_MS + 5_000L

    /** Who the screen says is calling. */
    data class Caller(val name: String, val number: String?)

    /** The contact the fake call appears to come from. Defaults to a plausible one. */
    fun caller(context: Context): Caller {
        val p = prefs(context)
        return Caller(p.getString(KEY_CALLER_NAME, null)?.takeIf { it.isNotBlank() } ?: "Mum", p.getString(KEY_CALLER_NUMBER, null))
    }

    fun setCaller(context: Context, name: String, number: String?) {
        prefs(context).edit().putString(KEY_CALLER_NAME, name.trim().take(40)).putString(KEY_CALLER_NUMBER, number?.trim()).apply()
    }

    /**
     * Rings in [delayMs]. [contactNumber], when given, is the Clairix number of the
     * contact to really call if the owner answers. Uses an exact, idle-proof alarm
     * when the owner has allowed exact alarms; otherwise an inexact one, which can
     * land late while the phone is idle and then usually rings through the card's
     * own sound (see [ring]).
     */
    fun schedule(context: Context, delayMs: Long, contactNumber: String?) {
        val app = context.applicationContext
        ensureChannels(app)
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        prefs(app).edit().putString(KEY_PENDING_CONTACT, contactNumber).putLong(KEY_PENDING_AT, at).apply()
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(app, contactNumber)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Cancels a scheduled call, and ends one that is ringing. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app, null))
        prefs(app).edit().remove(KEY_PENDING_CONTACT).remove(KEY_PENDING_AT).apply()
        stopRinging(app)
    }

    /** When the pending fake call will ring, or null when none is scheduled. */
    fun pendingAt(context: Context): Long? =
        prefs(context).getLong(KEY_PENDING_AT, 0L).takeIf { it > System.currentTimeMillis() }

    /** Whether exact alarms are allowed; [requestExactAlarms] opens the setting when not. */
    fun canScheduleExactly(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun requestExactAlarms(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ── What a ring needs ─────────────────────────────────────────────────────

    /** What can stand between a scheduled call and a phone that rings with its call screen; see [readiness]. */
    enum class Missing {
        /** Notifications are off for Clairix: no card, no call screen, no banner. */
        NOTIFICATIONS,
        /** A call channel was switched off or set to silent: no call screen, or a card that rings silently. */
        CALL_CHANNEL,
        /** Full-screen calls are not allowed (Android 14 on): a locked phone shows a notification only. */
        FULL_SCREEN,
        /** Exact alarms are not allowed: the ring can be late while the phone is idle. */
        EXACT_ALARMS
    }

    /** The Fake call page's decision: what is missing, and whether a call may be scheduled at all. */
    data class Readiness(val missing: List<Missing>) {
        /** Notifications and full-screen calls both allowed: the page schedules nothing otherwise. */
        val canSchedule: Boolean get() = Missing.NOTIFICATIONS !in missing && Missing.FULL_SCREEN !in missing
        /** Exact alarms allowed: the ring lands on the second, phone idle or not. */
        val ringsOnTime: Boolean get() = Missing.EXACT_ALARMS !in missing
    }

    /** The decision from the four facts alone, missing ones in the order the page shows them. */
    fun readiness(canPost: Boolean, channelsOk: Boolean, canUseFullScreen: Boolean, canScheduleExactly: Boolean): Readiness =
        Readiness(
            buildList {
                if (!canPost) add(Missing.NOTIFICATIONS)
                if (!channelsOk) add(Missing.CALL_CHANNEL)
                if (!canUseFullScreen) add(Missing.FULL_SCREEN)
                if (!canScheduleExactly) add(Missing.EXACT_ALARMS)
            }
        )

    /** The same checks real calls get in Comms settings, read from the phone now. */
    fun readiness(context: Context): Readiness = readiness(
        canPost = CommsNotifications.canPost(context),
        channelsOk = silencedChannel(context) == null,
        canUseFullScreen = CommsNotifications.canUseFullScreen(context),
        canScheduleExactly = canScheduleExactly(context)
    )

    /**
     * The first call channel the owner (or a phone's notification manager) has
     * switched off or set to silent, or null when both still alert. A quiet card
     * on a silenced channel neither pops up nor opens the call screen; a ringing
     * card on one makes no sound.
     */
    private fun silencedChannel(context: Context): String? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        ensureChannels(context.applicationContext)
        return listOf(CHANNEL, CHANNEL_RINGTONE).firstOrNull { id ->
            nm.getNotificationChannel(id)?.let { it.importance < NotificationManager.IMPORTANCE_HIGH } == true
        }
    }

    /** Opens the system settings of the silenced call channel. */
    fun openChannelSettings(context: Context) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, silencedChannel(context) ?: CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { CommsNotifications.openAppNotificationSettings(context) }
    }

    // ── The ring's rules ──────────────────────────────────────────────────────

    /**
     * Whether Clairix itself has the ring in hand, so the card must stay quiet:
     * the ringtone is playing, or the ringer mode wants no ringtone (vibrate:
     * Clairix vibrates; silent: nothing sounds at all). Otherwise the card rings
     * with the ringtone as its own sound. One or the other, never both.
     */
    fun ringsItself(ringerMode: Int, ringtonePlaying: Boolean): Boolean =
        ringerMode != AudioManager.RINGER_MODE_NORMAL || ringtonePlaying

    /**
     * How much longer a ring that began at [startedAt] runs at [now]: the rest of
     * [RING_MS], never negative, and the whole of it should the clock have moved
     * back. The call screen's timer and the ringer's agree on the moment the
     * call is missed.
     */
    fun ringRemaining(startedAt: Long, now: Long): Long = (startedAt + RING_MS - now).coerceIn(0L, RING_MS)

    // ── Schedule and lock ─────────────────────────────────────────────────────

    /**
     * Whether "Schedule and lock" can lock the phone: Clairix's device admin (the
     * unlock ledger's, [UnlockLedger.adminComponent]) is active and was approved
     * with the force-lock policy. An admin approved before that policy was asked
     * for keeps feeding the ledger but cannot lock until it is approved again.
     */
    fun canLock(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = UnlockLedger.adminComponent(context)
        dpm.isAdminActive(admin) && dpm.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
    }.getOrDefault(false)

    /** Locks the phone now, where [canLock]; returns whether it did. */
    fun lockNow(context: Context): Boolean {
        if (!canLock(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching { dpm.lockNow() }.isSuccess
    }

    // ── Ringing ───────────────────────────────────────────────────────────────

    /**
     * Rings now. Starts the ring in [FakeCallRinger] (its wake lock and its
     * [RING_MS] timer) and [FakeCallRingService], which posts the call card as
     * its foreground notification and plays the ringtone. An exact alarm lets the
     * service start from the background; after an inexact one Android refuses,
     * and the card goes out ringing with its own sound instead. Opens the screen
     * directly as well where Clairix is allowed to (in the foreground, or just
     * out of it).
     */
    fun ring(context: Context, contactNumber: String?) {
        val app = context.applicationContext
        ensureChannels(app)
        prefs(app).edit().remove(KEY_PENDING_AT).apply()
        // A card left from an earlier call would make this one an update, which
        // does not pop up or ring; a ring still under way keeps its card.
        if (!FakeCallRinger.isRinging) app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        FakeCallRinger.begin(app)
        val service = Intent(app, FakeCallRingService::class.java).putExtra(EXTRA_CONTACT, contactNumber)
        // ForegroundServiceStartNotAllowedException from the background without an
        // exact alarm's exemption; null where the service is not declared.
        val serviceStarted = runCatching { app.startForegroundService(service) != null }.getOrDefault(false)
        if (!serviceStarted) {
            FakeCallRinger.handToCard()
            postRingtoneCard(app, contactNumber)
        }
        runCatching {
            app.startActivity(
                Intent(app, FakeCallActivity::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_CONTACT, contactNumber)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /**
     * Ends the ring wherever it sounds and takes the card down. The service and
     * the call screen hold listeners on [FakeCallRinger], so they end with it:
     * the service leaves the foreground and stops itself (never stopService(),
     * which could beat its startForeground() and get the app killed), and an
     * unanswered screen closes.
     */
    internal fun stopRinging(context: Context) {
        FakeCallRinger.stop()
        context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Posts the card that rings with its own sound. A no-op without notifications; never throws. */
    internal fun postRingtoneCard(context: Context, contactNumber: String?) {
        val app = context.applicationContext
        if (!CommsNotifications.canPost(app)) return
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, card(app, contactNumber, ringtone = true)) }
    }

    /**
     * The incoming-call card. With [ringtone] it goes out on [CHANNEL_RINGTONE]
     * and repeats that channel's sound and vibration until it is cancelled, for
     * [RING_MS] at most even with Clairix gone; without, on [CHANNEL], quiet
     * apart from the channel's vibration. Either alerts once only: posting it
     * again (a second start of the service) changes nothing that is heard.
     */
    internal fun card(context: Context, contactNumber: String?, ringtone: Boolean): Notification {
        val app = context.applicationContext
        ensureChannels(app)
        val who = caller(app)
        val show = screenIntent(app, ACTION_SHOW, contactNumber, 1)
        val answer = screenIntent(app, ACTION_ANSWER, contactNumber, 2)
        val decline = PendingIntent.getBroadcast(
            app, NOTIFICATION_ID + 3,
            Intent(app, FakeCallReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val person = Person.Builder().setName(who.name).setImportant(true).build()
        val n = NotificationCompat.Builder(app, if (ringtone) CHANNEL_RINGTONE else CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(app.getString(R.string.safety_fake_incoming_call))
            .setContentText(who.name)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(if (ringtone) RING_MS else CARD_TIMEOUT_MS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (ringtone) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /**
     * What [FakeCallRingService] shows when it starts after its ring has already
     * ended (declined or cancelled in the moment before): a service started in
     * the foreground must go foreground before it may stop, or Android kills the
     * app. Silent, with no full-screen intent; removed at once.
     */
    internal fun endedPlaceholder(context: Context): Notification {
        ensureChannels(context.applicationContext)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(context.getString(R.string.comms_call_ended))
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun screenIntent(app: Context, action: String, contact: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(
            app, NOTIFICATION_ID + code,
            Intent(app, FakeCallActivity::class.java).setAction(action).putExtra(EXTRA_CONTACT, contact)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmIntent(app: Context, contact: String?): PendingIntent =
        PendingIntent.getBroadcast(
            app, ALARM_REQUEST,
            Intent(app, FakeCallReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_CONTACT, contact),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * The two call channels, named and described like any app's call channels
     * (and unlike Comms' own "Calls", so the two can be told apart in settings).
     */
    private fun ensureChannels(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        val cadence = longArrayOf(0, 800, 1200, 800, 1200)
        // Named by ChannelNames; in discreet mode the descriptions do not name the app.
        val discreet = DiscreetMode.on(app)
        val screen = ChannelNames.of(CHANNEL, discreet)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, screen.name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = screen.description
                // Clairix plays the phone's own ringtone; the channel adds vibration
                // that survives Android muting the app's own.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = cadence
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val ringtone = ChannelNames.of(CHANNEL_RINGTONE, discreet)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGTONE, ringtone.name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = ringtone.description
                // The phone's default ringtone, whichever it is when the call rings.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = cadence
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The alarm lands here; also the Decline button of the notification. */
class FakeCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FakeCall.ACTION_RING -> FakeCall.ring(context, intent.getStringExtra(FakeCall.EXTRA_CONTACT))
            FakeCall.ACTION_DECLINE -> FakeCall.cancel(context)
        }
    }
}

/**
 * The ring itself: the one handle the service, the call screen and the card's
 * buttons share. [begin] marks a ring as under way, holds a partial wake lock
 * and arms the [FakeCall.RING_MS] timer; [start] adds the phone's actual
 * default ringtone, looping, and the standard vibration cadence, both
 * honouring the ringer mode, and does nothing more for a ring already sounding,
 * so a call never rings twice; [handToCard] leaves the sound to a card that
 * rings with its own; [stop] ends it all and tells whoever listens. Main thread
 * only.
 */
internal object FakeCallRinger {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var timeout: Runnable? = null
    private val stopListeners = mutableListOf<() -> Unit>()

    /** Wall-clock time the current ring began, or null while none is under way. */
    @Volatile
    var startedAt: Long? = null
        private set

    val isRinging: Boolean get() = startedAt != null

    /** Set once the card rings with its own sound; Clairix then stays quiet until the ring ends. */
    var cardRings: Boolean = false
        private set

    /** Marks a ring as begun at [now], unless one is under way. */
    fun begin(context: Context, now: Long = System.currentTimeMillis()) {
        if (startedAt != null) return
        val app = context.applicationContext
        startedAt = now
        wake = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clairix:fake-call")
            ?.apply { setReferenceCounted(false); acquire(FakeCall.CARD_TIMEOUT_MS) }
        timeout = Runnable { FakeCall.stopRinging(app) }.also { handler.postDelayed(it, FakeCall.RING_MS) }
    }

    /**
     * Rings: begins the ring if need be, then plays the ringtone and vibrates
     * unless they already are. Returns [FakeCall.ringsItself]: true when the
     * sound is in Clairix's hands, false when the card should ring instead (no
     * ringtone could be played, or the card already has the sound).
     */
    fun start(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        begin(context, now)
        if (cardRings) return false
        val app = context.applicationContext
        val mode = app.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL && ringtone == null) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = runCatching {
                RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && vibrator == null) {
            vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }?.also {
                val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0)
                // Tagged as a ringtone: Android drops untagged vibration from an app
                // that is not in the foreground, which is how this call arrives.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                }
            }
        }
        return FakeCall.ringsItself(mode, ringtone != null)
    }

    /** Leaves the sound to the card: Clairix's ringtone and vibration stop, the ring and its timer go on. */
    fun handToCard() {
        silence()
        cardRings = true
    }

    /** [listener] runs once, when the current ring ends. */
    fun addStopListener(listener: () -> Unit) {
        if (listener !in stopListeners) stopListeners += listener
    }

    fun removeStopListener(listener: () -> Unit) {
        stopListeners -= listener
    }

    fun stop() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        silence()
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        startedAt = null
        cardRings = false
        val listeners = stopListeners.toList()
        stopListeners.clear()
        listeners.forEach { it() }
    }

    private fun silence() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}

/**
 * Rings a [FakeCall] from the background: a short foreground service (no type
 * permission and no prerequisites; its three minutes are far more than the
 * 45 s ring needs) whose foreground notification is the incoming-call card.
 * Started by the alarm through [FakeCall.ring]; ends with the ring, through a
 * listener on [FakeCallRinger] — answer, decline, the page's Cancel, or the
 * ring's own timer.
 */
class FakeCallRingService : Service() {

    private val onRingStopped: () -> Unit = { leave() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contact = intent?.getStringExtra(FakeCall.EXTRA_CONTACT)
        // A ring ended in the moment between the alarm and here still owes
        // Android a startForeground() before this service may stop.
        val due = FakeCallRinger.isRinging
        val ringsItself = due && FakeCallRinger.start(this)
        if (due && !ringsItself) FakeCallRinger.handToCard()
        val notification = if (due) FakeCall.card(this, contact, ringtone = !ringsItself) else FakeCall.endedPlaceholder(this)
        val inForeground = runCatching {
            ServiceCompat.startForeground(this, FakeCall.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        }.isSuccess
        if (!inForeground) {
            // Refused after all: the card alone, ringing with its own sound.
            if (due) {
                FakeCallRinger.handToCard()
                FakeCall.postRingtoneCard(this, contact)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (FakeCallRinger.isRinging) FakeCallRinger.addStopListener(onRingStopped) else leave()
        return START_NOT_STICKY
    }

    /** Android 14: the short service's time is up. It never is (the ring ends at 45 s); ending is all that is left. */
    override fun onTimeout(startId: Int) {
        FakeCall.stopRinging(this)
        leave()
    }

    /** Android 15 on: the same, told with the service's type. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        FakeCall.stopRinging(this)
        leave()
    }

    private fun leave() {
        FakeCallRinger.removeStopListener(onRingStopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        FakeCallRinger.removeStopListener(onRingStopped)
        super.onDestroy()
    }
}

// The decoy deliberately does not wear the Clairix look: it copies a stock call
// screen, in Material's default type and the fixed DecoyCallPalette, so that a
// glance over the shoulder sees an ordinary incoming call whatever theme is set.
private val FcGround = DecoyCallPalette.Ground
private val FcInk = DecoyCallPalette.Ink
private val FcDim = DecoyCallPalette.Dim
private val FcAnswer = DecoyCallPalette.Answer
private val FcDecline = DecoyCallPalette.Decline
private val FcAvatar = DecoyCallPalette.Avatar

/**
 * The incoming-call screen of a [FakeCall]. Shows over the lock screen and turns
 * the display on; excluded from recents so nothing is left behind. Rings through
 * [FakeCallRinger], the ringer the service uses, so a screen that comes up to a
 * call already ringing adds nothing to it, and one that is first (Clairix in the
 * foreground) plays the phone's actual default ringtone and vibrates in the
 * standard cadence, both honouring the ringer mode. The ring ending anywhere —
 * declined on the card, timed out after [FakeCall.RING_MS], cancelled — closes
 * the screen unless the call was answered.
 */
class FakeCallActivity : ComponentActivity() {

    private var contactNumber: String? = null
    /** Answered, on this screen or with the card's Answer button; kept across a configuration change. */
    private val answered = mutableStateOf(false)
    private val onRingStopped: () -> Unit = { if (!answered.value) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        contactNumber = intent?.getStringExtra(FakeCall.EXTRA_CONTACT)
        val who = FakeCall.caller(this)
        answered.value = savedInstanceState?.getBoolean(STATE_ANSWERED) ?: (intent?.action == FakeCall.ACTION_ANSWER)
        FakeCallRinger.addStopListener(onRingStopped)
        // A screen recreated by a configuration change finds the ring where it left it.
        if (!answered.value && savedInstanceState == null) startRinging()
        // What is left of the ring, which the service may have begun before this screen came up.
        val now = System.currentTimeMillis()
        val remaining = FakeCall.ringRemaining(FakeCallRinger.startedAt ?: now, now)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = FcGround, surface = FcGround)) {
                Surface(color = FcGround, modifier = Modifier.fillMaxSize()) {
                    val isAnswered by answered
                    LaunchedEffect(isAnswered) { if (isAnswered) onAnswer() }
                    if (!isAnswered) {
                        LaunchedEffect(Unit) { delay(remaining); dismiss() }
                        Ringing(who, onDecline = { dismiss() }, onAnswer = { answered.value = true })
                    } else {
                        InCall(who, onEnd = { dismiss() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The card's Answer button with this screen already up: the same as Answer
        // here. Marked answered first, so the ring ending does not close the screen.
        if (intent.action == FakeCall.ACTION_ANSWER) {
            answered.value = true
            stopRinging()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWERED, answered.value)
    }

    /**
     * Answer: the ringing stops and the notification goes. With a contact's Clairix
     * number and the microphone permission, a real encrypted call is placed and
     * the real call screen takes over; otherwise this screen stays as a pretend
     * call in progress.
     */
    private fun onAnswer() {
        stopRinging()
        val number = contactNumber ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching { CommsRepository.init(applicationContext) }
        val contact = runCatching { CommsRepository.contact(number) }.getOrNull() ?: return
        CallManager.place(contact)
        startActivity(
            Intent(this, CallActivity::class.java).setAction("app.clairix.OPEN_CALL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun dismiss() {
        stopRinging()
        finish()
    }

    /**
     * Rings through [FakeCallRinger], which the service may already be driving.
     * Should no ringtone play here either, the card takes the sound, as it would
     * for the service.
     */
    private fun startRinging() {
        if (FakeCallRinger.start(this) || FakeCallRinger.cardRings) return
        FakeCallRinger.handToCard()
        FakeCall.postRingtoneCard(this, contactNumber)
    }

    /** Ends the ring wherever it sounds and takes the card down. */
    private fun stopRinging() = FakeCall.stopRinging(this)

    override fun onDestroy() {
        FakeCallRinger.removeStopListener(onRingStopped)
        if (!isChangingConfigurations) stopRinging()
        super.onDestroy()
    }

    private companion object {
        const val STATE_ANSWERED = "answered"
    }
}

@Composable
private fun Avatar(name: String) {
    val initials = name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    Box(
        Modifier.size(112.dp).background(FcAvatar, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, style = MaterialTheme.typography.headlineLarge, color = FcInk)
    }
}

@Composable
private fun Ringing(who: FakeCall.Caller, onDecline: () -> Unit, onAnswer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.comms_incoming_call), style = MaterialTheme.typography.bodyLarge, color = FcDim)
        Spacer(Modifier.height(28.dp))
        Avatar(who.name)
        Spacer(Modifier.height(20.dp))
        Text(who.name, style = MaterialTheme.typography.headlineLarge, color = FcInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(who.number?.let { formatClairixNumber(it) } ?: stringResource(R.string.safety_mobile), style = MaterialTheme.typography.bodyLarge, color = FcDim)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onDecline, colors = ButtonDefaults.buttonColors(containerColor = FcDecline), shape = CircleShape, modifier = Modifier.size(80.dp)) {
                Text(stringResource(R.string.safety_end), style = MaterialTheme.typography.labelLarge, color = FcInk)
            }
            Button(onClick = onAnswer, colors = ButtonDefaults.buttonColors(containerColor = FcAnswer), shape = CircleShape, modifier = Modifier.size(80.dp)) {
                Text(stringResource(R.string.comms_answer), style = MaterialTheme.typography.labelLarge, color = FcInk)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InCall(who: FakeCall.Caller, onEnd: () -> Unit) {
    var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        started = System.currentTimeMillis()
        while (true) { elapsed = System.currentTimeMillis() - started; delay(1_000L) }
    }
    val s = elapsed / 1000
    val clock = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(clock, style = MaterialTheme.typography.bodyLarge, color = FcDim)
        Spacer(Modifier.height(28.dp))
        Avatar(who.name)
        Spacer(Modifier.height(20.dp))
        Text(who.name, style = MaterialTheme.typography.headlineLarge, color = FcInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(who.number?.let { formatClairixNumber(it) } ?: stringResource(R.string.safety_mobile), style = MaterialTheme.typography.bodyLarge, color = FcDim)
        Spacer(Modifier.weight(1f))
        Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = FcDecline), shape = CircleShape, modifier = Modifier.size(80.dp)) {
            Text(stringResource(R.string.safety_end), style = MaterialTheme.typography.labelLarge, color = FcInk)
        }
        Spacer(Modifier.height(24.dp))
    }
}
