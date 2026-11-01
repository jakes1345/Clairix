package app.clairix.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.clairix.LatLon
import app.clairix.Registry
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * The position an SOS carries when the scanner holds none: the platform's last
 * known position, else a fresh fix with a short wait. Shared by the SOS button
 * and the duress phrase, and needed in the comms-only build, which has no
 * scanner at all.
 */
object SosLocation {

    /** How long the platform's last known position is waited for. */
    private const val LAST_KNOWN_BUDGET_S = 2L
    /** How long a fresh fix is waited for when there is no last known position. */
    private const val FRESH_FIX_BUDGET_S = 6L

    /**
     * Finds a position and puts it in the status the panic broadcast reads
     * ([Registry.status]). Null when location is not granted or nothing could
     * be found in time; the broadcast then goes with whatever the status
     * already holds, or without a position. Blocks for up to eight seconds:
     * call off the main thread (the Tasks API refuses to wait on it).
     */
    fun find(context: Context): LatLon? {
        val granted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val fix = runCatching { Tasks.await(client.lastLocation, LAST_KNOWN_BUDGET_S, TimeUnit.SECONDS) }.getOrNull()
            ?: runCatching {
                Tasks.await(
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token),
                    FRESH_FIX_BUDGET_S, TimeUnit.SECONDS
                )
            }.getOrNull()
            ?: return null
        Registry.update { it.copy(lat = fix.latitude, lon = fix.longitude) }
        return LatLon(fix.latitude, fix.longitude)
    }
}
