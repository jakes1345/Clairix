package app.clairix.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.google.android.gms.location.LocationRequest as GoogleLocationRequest

/**
 * The full and Play editions (src/gms is a source directory of both): Google Play
 * services' fused client is there, used where the phone has Play services and answers
 * as usable. The F-Droid edition's copy of this object (src/foss) has none.
 */
object EditionLocation {
    val fused: FusedLocation? = PlayServicesLocation
}

/** [FusedLocation] on Google Play services' fused location provider. */
private object PlayServicesLocation : FusedLocation {

    /**
     * Only [ConnectionResult.SUCCESS] counts: Play services missing, disabled, too old to
     * serve this library or updating all send requests to Android's own service instead.
     */
    override fun usable(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context.applicationContext) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override fun client(context: Context, onFixes: (List<Location>) -> Unit): FusedLocation.Client =
        PlayServicesClient(LocationServices.getFusedLocationProviderClient(context.applicationContext), onFixes)

    @SuppressLint("MissingPermission") // the caller checks; a SecurityException is no answer
    override fun awaitLast(context: Context, timeoutMs: Long): FusedAnswer =
        await(timeoutMs) { LocationServices.getFusedLocationProviderClient(context.applicationContext).lastLocation }

    @SuppressLint("MissingPermission") // the caller checks; a SecurityException is no answer
    override fun awaitCurrent(context: Context, timeoutMs: Long): FusedAnswer =
        await(timeoutMs) {
            LocationServices.getFusedLocationProviderClient(context.applicationContext)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
        }

    /**
     * Waits for the task [start] makes. A task that failed ([ExecutionException]) is Google
     * Play services refusing; a wait that ran out, was interrupted, or could not be made
     * at all is no answer.
     */
    private fun await(timeoutMs: Long, start: () -> Task<Location?>): FusedAnswer = try {
        FusedAnswer.Answered(Tasks.await(start(), timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS))
    } catch (e: ExecutionException) {
        FusedAnswer.Refused(e)
    } catch (ignored: TimeoutException) {
        FusedAnswer.NoAnswer
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        FusedAnswer.NoAnswer
    } catch (ignored: SecurityException) {
        FusedAnswer.NoAnswer
    } catch (ignored: IllegalStateException) {
        // Tasks.await refuses to wait on the main thread.
        FusedAnswer.NoAnswer
    }
}

private class PlayServicesClient(
    private val client: FusedLocationProviderClient,
    private val onFixes: (List<Location>) -> Unit
) : FusedLocation.Client {

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            onFixes(result.locations)
        }
    }

    @SuppressLint("MissingPermission") // the caller holds the permission; SecurityException otherwise
    override fun request(fixes: FixRequest, onFailure: (Exception) -> Unit) {
        val builder = GoogleLocationRequest.Builder(
            if (fixes.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            fixes.intervalMs
        )
            .setMinUpdateIntervalMillis(fixes.minIntervalMs)
            .setMinUpdateDistanceMeters(fixes.minDistanceM)
        fixes.waitForAccurate?.let { builder.setWaitForAccurateLocation(it) }
        // The same callback again replaces the earlier request. A refusal arrives later,
        // on the main thread, as the task's failure.
        client.requestLocationUpdates(builder.build(), callback, Looper.getMainLooper())
            .addOnFailureListener { e -> onFailure(e) }
    }

    override fun remove() {
        runCatching { client.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission") // the caller holds the permission; SecurityException otherwise
    override fun lastLocation(onResult: (Location?) -> Unit, onFailure: (Exception) -> Unit) {
        client.lastLocation
            .addOnSuccessListener { l -> onResult(l) }
            .addOnFailureListener { e -> onFailure(e) }
    }
}
