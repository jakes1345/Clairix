package app.clairix

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.clairix.security.ChannelNames
import app.clairix.security.DiscreetMode
import app.clairix.security.NotificationPresentation
import app.clairix.security.NotificationText
import app.clairix.security.PlatformAlerts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps the process in the foreground while drive mode is on, so the SDR stream, scanner
 * audio and OBD-II link keep running with the screen off. The work itself lives in
 * [DriveViewModel]; this service only holds the foreground state and the notification,
 * whose text the view model keeps current ([show]): the stick unplugged, the radio
 * stopped on an error, everything paused on a low battery.
 */
class DriveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground must come first: startForegroundService gives us only a few seconds.
        active = enterForeground()
        if (!active) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop: drive mode ends in the view model that runs it (the
            // radio, the recording, the OBD link), and this service with it either way.
            _stopRequests.tryEmit(Unit)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Drive mode is driven by the UI; if the process dies, do not come back on our own.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = ChannelNames.of(CHANNEL_ID, DiscreetMode.on(this))
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channel.name, NotificationManager.IMPORTANCE_LOW)
                .apply { description = channel.description }
        )
        // The location type is only allowed once location access is granted; without it the
        // whole startForeground call would be refused, so add it only when it will be accepted.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, text), type)
            true
        } catch (e: Exception) {
            // SecurityException (missing prerequisite permission) or
            // ForegroundServiceStartNotAllowedException (started from the background).
            false
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "drive_mode"
        private const val NOTIFICATION_ID = 0x0D21
        private const val ACTION_STOP = "app.clairix.DRIVE_STOP"

        private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** The notification's Stop was tapped; [DriveViewModel] ends drive mode on it. */
        val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

        /**
         * Whether drive mode is on in this process (the service holds the foreground).
         * The scanner's trip memory reads it as a sign the journey is in the owner's own car.
         */
        @Volatile
        var active: Boolean = false
            private set
        /** What the notification says; [DriveLink.ongoing] decides it. */
        @Volatile private var text: NotificationText =
            DriveLink.ongoing(DriveLink.Radio.ON, SdrState.Opening, obdConnected = false)

        /**
         * Says [shown] in the notification from now on: at once while the service runs, and
         * when it starts otherwise. Only ever the service's own notification is replaced.
         */
        fun show(context: Context, shown: NotificationText) {
            if (shown == text) return
            text = shown
            if (!active) return
            val app = context.applicationContext
            runCatching {
                app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(app, shown))
            }
        }

        private fun buildNotification(context: Context, wording: NotificationText): Notification {
            // Discreet mode: only "Running"; what the radio and OBD-II are doing stays in the app.
            val shown = NotificationPresentation.service(wording, DiscreetMode.on(context))
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, DriveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(DiscreetMode.smallIcon(context))
                .setContentTitle(shown.title)
                .setContentText(shown.text)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // On the lock screen: the app's name and that it runs, nothing of what.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(PlatformAlerts.publicVersion(context, CHANNEL_ID, NotificationPresentation.RUNNING))
                .build()
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
