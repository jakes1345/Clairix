package app.clairix.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.clairix.LatLon
import app.clairix.Registry
import app.clairix.analysis.LocationPolicy
import app.clairix.analysis.LocationSource
import app.clairix.analysis.Positioning
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The position an SOS carries when the scanner holds none: the last known position the
 * phone's location source holds, else a fresh fix with a short wait. Shared by the SOS
 * button and the duress phrase, and needed in the comms-only build, which has no
 * scanner at all.
 *
 * The source is the scanner's ([Positioning.choose]): Google Play services where the
 * phone has it, Android's own location service where it does not or where Google's
 * fails the request, the satellite receiver alone with Satellite-only positioning on.
 * A position Android marks as mock is an app's invention and never goes in an SOS: it
 * would send the people coming to help wherever that app says. Nor does a last known
 * position older than [LAST_KNOWN_MAX_AGE_MS]: an SOS does not say how old its position
 * is, so an old one would read as where the phone is now.
 */
object SosLocation {

    /** How long the source's last known position is waited for. */
    private const val LAST_KNOWN_BUDGET_MS = 2_000L
    /** How long a fresh fix is waited for when there is no last known position. */
    private const val FRESH_FIX_BUDGET_MS = 6_000L
    /** A last known position older than this is not where the phone is now. */
    const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L

    /** What a lookup came to. */
    sealed interface Lookup {
        /** A position, now in the status the panic broadcast reads. */
        data class Found(val at: LatLon) : Lookup
        /** Location is not granted to Clairix. */
        data object NotPermitted : Lookup
        /** The only positions to be had were an app's (mock locations); none was taken. */
        data object OnlyFaked : Lookup
        /** No position in time. */
        data object NoFix : Lookup
    }

    /**
     * Finds a position and puts it in the status the panic broadcast reads
     * ([Registry.status]). Null when location is not granted or nothing usable could
     * be found in time; the broadcast then goes with whatever the status already holds,
     * or without a position. Blocks for up to eight seconds: call off the main thread
     * (the Tasks API refuses to wait on it).
     */
    fun find(context: Context): LatLon? = (locate(context) as? Lookup.Found)?.at

    /** As [find], saying why there is no position when there is none. */
    @SuppressLint("MissingPermission") // checked first thing
    fun locate(context: Context): Lookup {
        val app = context.applicationContext
        val granted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return Lookup.NotPermitted
        val deadline = SystemClock.elapsedRealtime() + LAST_KNOWN_BUDGET_MS + FRESH_FIX_BUDGET_MS
        val platform = Positioning.platform(app)
        var source = Positioning.choose(app, platform)
        var faked = false

        /** [loc] when it may go in the SOS; a mock one is noted and dropped. */
        fun take(loc: Location?, maxAgeMs: Long): Location? {
            if (loc == null) return null
            if (loc.isMock) faked = true
            return loc.takeIf { usable(it.isMock, ageMs(it), maxAgeMs) }
        }

        if (source == LocationSource.GOOGLE) {
            val client = LocationServices.getFusedLocationProviderClient(app)
            val last = runCatching { Tasks.await(client.lastLocation, LAST_KNOWN_BUDGET_MS, TimeUnit.MILLISECONDS) }
            take(last.getOrNull(), LAST_KNOWN_MAX_AGE_MS)?.let { return found(it) }
            // A task that failed ([ExecutionException]) is Google Play services refusing,
            // not an answer: Android's own service then, in the time left. No fix in time,
            // or none but an app's, is the answer.
            var failed = last.exceptionOrNull() as? ExecutionException
            if (failed == null) {
                val fresh = runCatching {
                    Tasks.await(
                        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token),
                        remaining(deadline), TimeUnit.MILLISECONDS
                    )
                }
                take(fresh.getOrNull(), Long.MAX_VALUE)?.let { return found(it) }
                failed = fresh.exceptionOrNull() as? ExecutionException
            }
            if (failed == null) return if (faked) Lookup.OnlyFaked else Lookup.NoFix
            Positioning.noteGoogleFailed(failed)
            source = Positioning.choose(app, platform)
        }

        take(Positioning.lastKnown(app, source, platform), LAST_KNOWN_MAX_AGE_MS)?.let { return found(it) }
        val lm = app.getSystemService(LocationManager::class.java)
        val provider = LocationPolicy.providers(source, platform, highAccuracy = true).firstOrNull()
        if (lm != null && provider != null) take(freshFix(lm, provider, remaining(deadline)), Long.MAX_VALUE)?.let { return found(it) }
        return if (faked) Lookup.OnlyFaked else Lookup.NoFix
    }

    /** The toast after an SOS that went without a position because [lookup] found none; null when it found one. */
    fun missing(lookup: Lookup): String? = when (lookup) {
        is Lookup.Found -> null
        Lookup.NotPermitted -> "Clairix is not allowed to read the location, so the SOS went without a position"
        Lookup.OnlyFaked -> "Only a faked position was found (an app supplies mock locations), so the SOS went without one"
        Lookup.NoFix -> "No position found to attach to the SOS"
    }

    /** Whether a position [ageMs] old, supplied by an app ([mock]) or not, may go in an SOS as where the phone is. */
    internal fun usable(mock: Boolean, ageMs: Long, maxAgeMs: Long): Boolean = !mock && ageMs <= maxAgeMs

    private fun found(loc: Location): Lookup.Found {
        Registry.update { it.copy(lat = loc.latitude, lon = loc.longitude) }
        return Lookup.Found(LatLon(loc.latitude, loc.longitude))
    }

    /** How long ago [loc] was taken, on the phone's monotonic clock; unknown reads as too old. */
    private fun ageMs(loc: Location): Long {
        val nanos = loc.elapsedRealtimeNanos
        return if (nanos > 0L) ((SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000L).coerceAtLeast(0L) else Long.MAX_VALUE
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** One fresh fix from Android's [provider], waited for up to [budgetMs]; null when none came. */
    @SuppressLint("MissingPermission") // checked by the caller; SecurityException otherwise
    private fun freshFix(lm: LocationManager, provider: String, budgetMs: Long): Location? {
        if (budgetMs <= 0L) return null
        val got = AtomicReference<Location?>()
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        try {
            lm.getCurrentLocation(provider, cancel, Executor { it.run() }) { loc ->
                got.set(loc)
                done.countDown()
            }
            if (!done.await(budgetMs, TimeUnit.MILLISECONDS)) cancel.cancel()
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: InterruptedException) {
            cancel.cancel()
            Thread.currentThread().interrupt()
            return null
        }
        return got.get()
    }
}
