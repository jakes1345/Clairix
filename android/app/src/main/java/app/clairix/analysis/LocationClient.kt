package app.clairix.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.clairix.Registry
import java.util.Collections
import java.util.WeakHashMap

/**
 * The phone's side of [LocationPolicy]: what it offers, what the owner chose, whether
 * Google Play services has failed in this process, and what the screens are told
 * ([Registry.positioning]).
 *
 * The owner's two choices live here, in both editions (the Comms edition reads a position
 * for an SOS): Satellite-only positioning, and, for the GPS check, whether to hold the
 * satellites against a Wi-Fi/cell position. They are read from storage each time, not
 * once at start, so a process that starts without the app's screens (an SOS from the
 * duress phrase) still honours them.
 */
object Positioning {

    private const val TAG = "Positioning"
    const val PREFS = "positioning"
    private const val K_SATELLITE_ONLY = "satellite_only"
    private const val K_NETWORK_CROSS_CHECK = "network_cross_check"

    /** Google Play services failed a location request in this process; every client goes to Android's own after that. */
    @Volatile private var googleFailed = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Fixes from the satellite receiver alone, and no Wi-Fi/cell position asked for anywhere. Off by default. */
    fun satelliteOnly(context: Context): Boolean = prefs(context).getBoolean(K_SATELLITE_ONLY, false)

    /** Whether the GPS check may ask for a Wi-Fi/cell position to compare with. On by default. */
    fun networkCrossCheck(context: Context): Boolean = prefs(context).getBoolean(K_NETWORK_CROSS_CHECK, true)

    /** Stores the choice, and every standing request and the GPS check follow it at once. */
    fun setSatelliteOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_SATELLITE_ONLY, value).apply()
        refresh(context)
        LocationClient.reapplyAll()
        GnssMonitor.settingsChanged()
    }

    fun setNetworkCrossCheck(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_NETWORK_CROSS_CHECK, value).apply()
        refresh(context)
        GnssMonitor.settingsChanged()
    }

    /**
     * What this phone offers now. Google Play services counts only in an edition that
     * carries it ([EditionLocation.fused]; the F-Droid edition does not) and only when it
     * answers as usable: missing, disabled, too old to serve the library or updating all
     * send the request to Android's own service instead.
     */
    fun platform(context: Context): LocationPolicy.Platform {
        val app = context.applicationContext
        val lm = app.getSystemService(LocationManager::class.java)
        fun has(provider: String) = lm != null && runCatching { lm.hasProvider(provider) }.getOrDefault(false)
        val play = EditionLocation.fused?.usable(app) == true
        return LocationPolicy.Platform(
            playServices = play,
            fusedProvider = has(LocationManager.FUSED_PROVIDER),
            gpsProvider = has(LocationManager.GPS_PROVIDER),
            networkProvider = has(LocationManager.NETWORK_PROVIDER)
        )
    }

    /** The source a request made now goes to. */
    fun choose(context: Context, platform: LocationPolicy.Platform = platform(context)): LocationSource =
        LocationPolicy.choose(platform, satelliteOnly(context), googleFailed)

    /** Whether the GPS check may ask for a Wi-Fi/cell position here; see [LocationPolicy.networkCheck]. */
    fun networkCheck(context: Context): NetworkCheck {
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
        val network = lm != null && runCatching { lm.hasProvider(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        return LocationPolicy.networkCheck(network, networkCrossCheck(context), satelliteOnly(context))
    }

    /** Google Play services refused or failed a request: Android's own service from now on, in this process. */
    fun noteGoogleFailed(e: Exception?) {
        if (!googleFailed) Log.w(TAG, "Google Play services failed a location request; using Android's own location service", e)
        googleFailed = true
    }

    /** Tells the screens where fixes come from and whether the GPS check can compare, as they stand now. */
    fun refresh(context: Context) = publish(context, choose(context))

    internal fun publish(context: Context, source: LocationSource) {
        val check = networkCheck(context)
        Registry.updatePositioning { it.copy(source = source, networkCheck = check) }
    }

    /**
     * The newest last known position among the providers that may stand in for [source]
     * ([LocationPolicy.lastKnownProviders]), or null. Reading one sends nothing anywhere;
     * how old it is, is for the caller to judge.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context, source: LocationSource, platform: LocationPolicy.Platform): Location? {
        val lm = context.applicationContext.getSystemService(LocationManager::class.java) ?: return null
        return LocationPolicy.lastKnownProviders(source, platform)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }
}

/**
 * Position fixes for one owner (the scanner, the Drive map, navigation), from the source
 * [Positioning.choose] picks when the request is made: Google Play services' fused client
 * where the phone has it, Android's own location service where it does not, the satellite
 * receiver alone when the owner asked for that. The owner gets every fix through
 * [onFixes] on the main thread, whichever served it.
 *
 * Google's client is decided on up front, and watched as well: a request it fails (Play
 * services disabled, broken, a stand-in without location) moves this client, and every
 * request after it in this process, to Android's own service. Before this a failed
 * request was never noticed, and no fix ever came.
 *
 * Thread-safe: requests and stops may come from any thread.
 */
class LocationClient(context: Context, private val onFixes: (List<Location>) -> Unit) {

    private val app = context.applicationContext
    private val manager: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(app)

    /**
     * Made only once Google's client is chosen: on a phone without Play services it is
     * never touched, and in the F-Droid edition there is none ([EditionLocation.fused]).
     */
    private var google: FusedLocation.Client? = null

    private fun googleClient(): FusedLocation.Client? =
        google ?: EditionLocation.fused?.client(app, onFixes)?.also { google = it }

    /** One listener per platform provider asked, so each can be dropped on its own. */
    private val platformListeners = HashMap<String, LocationListener>()

    /** The standing request, made again if the source changes; null when stopped. */
    private var want: FixRequest? = null

    /** Moves on with every request and stop, so a failure reported for an earlier request is ignored. */
    private var generation = 0

    /** Where the standing request went; null when there is none. */
    @Volatile
    var source: LocationSource? = null
        private set

    /**
     * Asks for [fixes], replacing any earlier request, and returns the source that serves
     * it. Throws [SecurityException] without location permission, as the platform and
     * Google's client both do.
     */
    @Synchronized
    fun request(fixes: FixRequest): LocationSource {
        val gen = ++generation
        want = fixes
        synchronized(live) { live.add(this) }
        val platform = Positioning.platform(app)
        val next = Positioning.choose(app, platform)
        if (source != next) dropRegistrations()
        source = next
        Positioning.publish(app, next)
        when (next) {
            LocationSource.GOOGLE -> requestGoogle(fixes, gen)
            LocationSource.PHONE, LocationSource.PHONE_FALLBACK, LocationSource.SATELLITE -> requestPlatform(next, platform, fixes)
            LocationSource.SATELLITE_UNAVAILABLE, LocationSource.NONE -> Unit
        }
        return next
    }

    /** Drops the standing request, whichever source served it. */
    @Synchronized
    fun stop() {
        generation++
        want = null
        synchronized(live) { live.remove(this) }
        dropRegistrations()
        source = null
    }

    /**
     * The newest position the source already holds, or null; delivered to [onResult],
     * on the main thread for Google's client and at once for Android's own. Never throws.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(onResult: (Location?) -> Unit) {
        val platform = Positioning.platform(app)
        val chosen = Positioning.choose(app, platform)
        if (chosen != LocationSource.GOOGLE) {
            onResult(Positioning.lastKnown(app, chosen, platform))
            return
        }
        val client = synchronized(this) { googleClient() }
        if (client == null) {
            onResult(Positioning.lastKnown(app, LocationSource.PHONE_FALLBACK, platform))
            return
        }
        try {
            client.lastLocation(
                onResult = { l -> onResult(l ?: Positioning.lastKnown(app, chosen, platform)) },
                onFailure = { e ->
                    Positioning.noteGoogleFailed(e)
                    onResult(Positioning.lastKnown(app, LocationSource.PHONE_FALLBACK, platform))
                }
            )
        } catch (e: SecurityException) {
            onResult(null)
        }
    }

    /** Throws [SecurityException] without permission, as Google's client does. */
    private fun requestGoogle(fixes: FixRequest, gen: Int) {
        // GOOGLE is chosen only where an edition supplies the client, so this is always
        // there; were it not, the request goes to Android's own service like any refusal.
        val client = googleClient()
            ?: return googleFailed(gen, IllegalStateException("This edition has no Google Play services client"))
        client.request(fixes) { e -> googleFailed(gen, e) }
    }

    /** Google's client failed the request [gen]: the same request goes to Android's own service. */
    @Synchronized
    private fun googleFailed(gen: Int, e: Exception) {
        if (gen != generation || source != LocationSource.GOOGLE) return
        Positioning.noteGoogleFailed(e)
        reapply()
    }

    @SuppressLint("MissingPermission") // the caller holds the permission; SecurityException otherwise
    private fun requestPlatform(source: LocationSource, platform: LocationPolicy.Platform, fixes: FixRequest) {
        val lm = manager ?: return
        val providers = LocationPolicy.providers(source, platform, fixes.highAccuracy)
        // A provider asked before and not now (Wi-Fi/cell once moving, satellites once
        // settled, on a phone without a fused provider) is dropped first.
        for (gone in platformListeners.keys - providers.toSet()) {
            platformListeners.remove(gone)?.let { runCatching { lm.removeUpdates(it) } }
        }
        val request = android.location.LocationRequest.Builder(fixes.intervalMs)
            .setQuality(LocationPolicy.quality(fixes.highAccuracy, networkProvider = source != LocationSource.SATELLITE && platform.networkProvider))
            .setMinUpdateIntervalMillis(fixes.minIntervalMs)
            .setMinUpdateDistanceMeters(fixes.minDistanceM)
            .build()
        for (provider in providers) {
            val listener = platformListeners.getOrPut(provider) { LocationListener { onFixes(listOf(it)) } }
            try {
                // The same listener again replaces its earlier request.
                lm.requestLocationUpdates(provider, request, mainExecutor, listener)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "No $provider provider: ${e.message}")
                platformListeners.remove(provider)
            }
        }
    }

    private fun dropRegistrations() {
        google?.remove()
        manager?.let { lm -> for (listener in platformListeners.values) runCatching { lm.removeUpdates(listener) } }
        platformListeners.clear()
    }

    /** The standing request again, to whichever source serves it now. */
    @Synchronized
    private fun reapply() {
        val fixes = want ?: return
        try {
            request(fixes)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location refused: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LocationClient"

        /** Clients with a standing request, held weakly so an owner that is gone is not kept alive by this. */
        private val live: MutableSet<LocationClient> = Collections.newSetFromMap(WeakHashMap())

        /** Where fixes may come from has changed (Satellite-only positioning): every standing request is made again. */
        fun reapplyAll() {
            val clients = synchronized(live) { live.toList() }
            for (client in clients) client.reapply()
        }
    }
}
