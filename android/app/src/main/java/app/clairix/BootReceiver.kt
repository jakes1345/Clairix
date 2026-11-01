package app.clairix

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.clairix.analysis.StalkwareWatch
import app.clairix.comms.CommsRepository

/**
 * Brings scanning back after a reboot — but only for someone who actually had it
 * running, who turned on "Resume scanning after reboot", and only when the
 * permissions a location foreground service needs at boot are granted — and
 * after an update, which stops every service of the app, for anyone who had it
 * running: an update is not a reboot, and the scan it ended was hers minutes ago.
 *
 * Android only lets a location-typed foreground service start from BOOT_COMPLETED
 * or MY_PACKAGE_REPLACED with ACCESS_BACKGROUND_LOCATION ("Allow all the time").
 * Without it the service's startForeground() is refused, the service stops without
 * ever having called it, and the system kills the app for breaking the
 * startForegroundService() contract. Without that permission the scanner comes
 * back the next time Clairix is opened instead (MainActivity.onResume), and the
 * timeline shows when it was lost.
 *
 * Previously this started the service unconditionally, so every reboot put the phone
 * back into a continuous BLE and GPS scan whether or not the user had ever asked for
 * one, and on a device missing location permission it started a service that could
 * only immediately kill itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Encrypted calls and messages: an owner who keeps comms online gets the
        // relay connection back after a reboot, or after installing an update
        // (which stops it), instead of missing every call until they happen to
        // open Clairix. init() starts it only in that case.
        runCatching { CommsRepository.init(context.applicationContext) }
        // The comms-only build has no scanner service to bring back.
        if (!BuildConfig.FULL_ACCESS) return

        // A reboot clears alarms: history keeps ageing out on a phone whose owner
        // never opens Clairix (the unlock ledger is written on every unlock).
        app.clairix.security.HistoryRetention.ensureScheduled(context)

        // The stalkerware check, whether or not the scanner is on: after a reboot, and after an
        // update, which may bring a newer stalkerware list. Compared with the last check; what is
        // new is alerted. Runs off the main thread with the broadcast held open.
        runCatching { StalkwareWatch.checkForBroadcast(context, goAsync()) }

        AppSettings.load(context)
        if (!AppSettings.scanEnabled) return
        // A reboot brings the scanner back only for someone who asked for that; an
        // update ended a scan she had running, so it comes straight back.
        if (action == Intent.ACTION_BOOT_COMPLETED && !AppSettings.resumeAfterReboot) return

        val required = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        if (required.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        ) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScanService::class.java)
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Android refused the background start after all; the service guards its own
            // startForeground the same way, with a message saying what to do. Either way
            // opening Clairix starts it again, and the timeline shows when it was lost.
            Log.w(TAG, "could not restart the scanner after $action: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
