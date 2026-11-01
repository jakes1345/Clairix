package app.clairix.comms

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app alive in the background while the owner has comms "online",
 * so [LiveLink] can hold the relay socket open and envelopes (and call offers)
 * arrive the moment they are queued.
 *
 * It is a foreground service of the special-use type: Android would otherwise
 * kill the process within minutes of the app leaving the screen. None of
 * Android's named types describes a socket to a relay held open so calls and
 * messages arrive without a Google push service, so the manifest gives that
 * reason in the service's PROPERTY_SPECIAL_USE_FGS_SUBTYPE. Special use may
 * still start from BOOT_COMPLETED on Android 15 (BootReceiver relies on it) and
 * has no running-time limit. Before Android 14 the service runs untyped.
 * The socket itself belongs to [LiveLink]; this service only takes a hold on
 * it and mirrors its state in the notification.
 */
class CommsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommsRepository.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CommsRepository.setOnline(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CommsRepository.isRegistered()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promote(LiveLink.connected.value)) return START_NOT_STICKY
        // Running again: whatever the watchdog counted of Android's refusals is over.
        LinkHealth.onRunning(this)
        LiveLink.hold(HOLD)
        LinkWatchdog.schedule(this)
        if (watcher?.isActive != true) {
            // Later changes only update the notification; going foreground again
            // could be refused from the background and would gain nothing.
            watcher = scope.launch {
                LiveLink.connected.collect { connected ->
                    runCatching {
                        getSystemService(NotificationManager::class.java)?.notify(
                            CommsNotifications.LINK_NOTIFICATION_ID,
                            CommsNotifications.linkNotification(this@CommsService, connected)
                        )
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Goes foreground. Android can refuse (a sticky restart while the app is in
     * the background, for one); an uncaught refusal used to crash the whole
     * process, taking a call being rung or a sync with it. A refusal now stops
     * the service; the app starts it again the next time it is opened.
     */
    private fun promote(connected: Boolean): Boolean = try {
        val notification = CommsNotifications.linkNotification(this, connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(CommsNotifications.LINK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(CommsNotifications.LINK_NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not go foreground", e)
        CommsLog.add("Android refused the background connection (${e.javaClass.simpleName}); it restarts when Clairix is opened")
        stopSelf()
        false
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        LiveLink.release(HOLD)
        if (!CommsRepository.state.value.online) LinkWatchdog.cancel(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CommsService"
        private const val HOLD = LiveLink.SERVICE_HOLD
        const val ACTION_STOP = "app.clairix.comms.STOP"

        /**
         * Starts the connection service. Android refuses a foreground start from
         * a background process (a push wake, for example) unless Clairix is exempt
         * from battery optimisation; opening the app starts it again
         * ([CommsRepository.onAppVisible]), and so does the connection watchdog
         * when Android allows it.
         */
        fun start(context: Context) {
            tryStart(context)
        }

        /**
         * [start], saying how it went: null when Android took the start, or what
         * it threw (ForegroundServiceStartNotAllowedException from the
         * background) for the watchdog to count ([LinkHealth]).
         */
        fun tryStart(context: Context): Exception? = try {
            context.startForegroundCompat(Intent(context, CommsService::class.java))
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not start the relay connection now: ${e.message}")
            e
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommsService::class.java))
        }

        /** Waits briefly for a background sync; used by the push receiver under its wake lock. */
        suspend fun syncWithin(ms: Long): Boolean = withTimeoutOrNull(ms) { CommsRepository.sync().isSuccess } ?: false
    }
}
