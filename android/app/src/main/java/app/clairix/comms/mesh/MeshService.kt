package app.clairix.comms.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.clairix.MainActivity

/**
 * Keeps the mesh running with the app closed: a connected-device foreground service,
 * with a notification that says so, for as long as the owner leaves the mesh on.
 */
class MeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Mesh.enabled(this) || !Mesh.permitted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        Mesh.startNode(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Mesh.stopNode()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline mesh", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while this phone carries messages over Bluetooth" }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Offline mesh on")
            .setContentText("Carrying messages phone to phone over Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL = "mesh"
        private const val NOTIFICATION_ID = 0x4D45
    }
}
