package app.clairix.analysis

import android.location.LocationManager
import android.location.LocationRequest
import app.clairix.security.SosLocation

/**
 * Where position fixes come from, decided before any is asked for ([LocationPolicy.choose]).
 *
 * Google Play services' fused client used to be the only source. On a phone without Play
 * services (GrapheneOS or LineageOS without them, common among the people Clairix is
 * for) its request failed without a word: no fix ever came, following could never be
 * confirmed and an SOS went without a position, while the app said "No GPS fix" with GPS
 * on. Android has a location service of its own, with a fused provider apps can ask for
 * by name from Android 12, the oldest version Clairix runs on; that is used instead.
 */
enum class LocationSource {
    /** Google Play services' fused location client. */
    GOOGLE,

    /** Android's own location service: this phone has no Google Play services. */
    PHONE,

    /** Android's own location service: Google Play services is installed but failed a request. */
    PHONE_FALLBACK,

    /** The satellite receiver alone: the owner turned Satellite-only positioning on. */
    SATELLITE,

    /** The owner asked for satellites alone, and this phone has no satellite receiver. */
    SATELLITE_UNAVAILABLE,

    /** Nothing on this phone gives a position. */
    NONE
}

/** Whether the satellite position can be held against a Wi-Fi/cell one; see [GnssMonitor]. */
enum class NetworkCheck {
    AVAILABLE,

    /** The phone has no Wi-Fi/cell location provider at all. */
    NO_PROVIDER,

    /** The owner turned "Cross-check GPS against Wi-Fi/cell position" off. */
    OFF,

    /** Satellite-only positioning is on, which asks for no Wi-Fi/cell position. */
    SATELLITE_ONLY
}

/** How often and how precisely a caller wants fixes, in terms Google's client and Android's both take. */
data class FixRequest(
    val highAccuracy: Boolean,
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float = 0f,
    /** Google's client only: whether a high-accuracy request may hold back early coarse fixes; null leaves its default. */
    val waitForAccurate: Boolean? = null
)

/**
 * Which source serves a request, and how a request is put to Android's own providers, as
 * decisions apart from the clients that act on them ([LocationClient], [app.clairix.security.SosLocation]).
 */
object LocationPolicy {

    /** What the phone offers: Google Play services, and which of Android's own providers it has. */
    data class Platform(
        val playServices: Boolean,
        val fusedProvider: Boolean,
        val gpsProvider: Boolean,
        val networkProvider: Boolean
    )

    /**
     * The source for a request. Satellite-only positioning wins over everything: the owner
     * asked that no location service work out where the phone is from the networks around
     * it, and Google's client may do exactly that. Otherwise Google Play services where it
     * is installed and has not failed in this process ([googleFailed]), and Android's own
     * service where it is not or has.
     */
    fun choose(platform: Platform, satelliteOnly: Boolean, googleFailed: Boolean): LocationSource = when {
        satelliteOnly -> if (platform.gpsProvider) LocationSource.SATELLITE else LocationSource.SATELLITE_UNAVAILABLE
        platform.playServices && !googleFailed -> LocationSource.GOOGLE
        !platform.fusedProvider && !platform.gpsProvider && !platform.networkProvider -> LocationSource.NONE
        platform.playServices -> LocationSource.PHONE_FALLBACK
        else -> LocationSource.PHONE
    }

    /**
     * Android's providers to ask for [source]: its fused provider where the phone has one,
     * which mixes satellites and Wi-Fi/cell as the quality asks; otherwise the satellites for
     * a precise fix and Wi-Fi/cell for a coarse one, each standing in for the other where it
     * is missing. Satellite-only asks the satellite receiver alone. None for Google's
     * client, which is not one of Android's providers, and none where nothing can serve.
     */
    fun providers(source: LocationSource, platform: Platform, highAccuracy: Boolean): List<String> = when (source) {
        LocationSource.SATELLITE -> listOf(LocationManager.GPS_PROVIDER)
        LocationSource.PHONE, LocationSource.PHONE_FALLBACK -> listOfNotNull(
            when {
                platform.fusedProvider -> LocationManager.FUSED_PROVIDER
                highAccuracy && platform.gpsProvider -> LocationManager.GPS_PROVIDER
                platform.networkProvider -> LocationManager.NETWORK_PROVIDER
                platform.gpsProvider -> LocationManager.GPS_PROVIDER
                else -> null
            }
        )
        LocationSource.GOOGLE, LocationSource.SATELLITE_UNAVAILABLE, LocationSource.NONE -> emptyList()
    }

    /**
     * The quality to ask Android's providers for. Precise is precise. A coarse fix is the
     * one Google's balanced request gives, Wi-Fi/cell only, and Android's own fused
     * provider gives that for its low-power quality: for any better one, balanced
     * included, it switches the satellites on as well, which on a phone sitting still is
     * the battery cost the settled pace exists to avoid. With no Wi-Fi/cell provider to
     * answer a low-power request ([networkProvider] false), balanced: Android 12's fused
     * provider would then switch nothing on at all, and the satellites must still give an
     * occasional fix.
     */
    fun quality(highAccuracy: Boolean, networkProvider: Boolean): Int = when {
        highAccuracy -> LocationRequest.QUALITY_HIGH_ACCURACY
        networkProvider -> LocationRequest.QUALITY_LOW_POWER
        else -> LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
    }

    /**
     * The providers whose last known position may stand in for [source]'s: the satellite
     * receiver's alone for Satellite-only positioning, any of Android's for the others.
     * Reading one sends nothing anywhere.
     */
    fun lastKnownProviders(source: LocationSource, platform: Platform): List<String> = when (source) {
        LocationSource.SATELLITE -> listOf(LocationManager.GPS_PROVIDER)
        LocationSource.GOOGLE, LocationSource.PHONE, LocationSource.PHONE_FALLBACK -> buildList {
            if (platform.fusedProvider) add(LocationManager.FUSED_PROVIDER)
            if (platform.gpsProvider) add(LocationManager.GPS_PROVIDER)
            if (platform.networkProvider) add(LocationManager.NETWORK_PROVIDER)
        }
        LocationSource.SATELLITE_UNAVAILABLE, LocationSource.NONE -> emptyList()
    }

    /**
     * Whether [GnssMonitor] may ask for a Wi-Fi/cell position to hold the satellites
     * against. Satellite-only positioning rules it out, whatever the cross-check says:
     * that position is worked out by sending the networks around the phone to a location
     * service, the thing Satellite-only positioning is there to avoid.
     */
    fun networkCheck(networkProvider: Boolean, crossCheck: Boolean, satelliteOnly: Boolean): NetworkCheck = when {
        satelliteOnly -> NetworkCheck.SATELLITE_ONLY
        !networkProvider -> NetworkCheck.NO_PROVIDER
        !crossCheck -> NetworkCheck.OFF
        else -> NetworkCheck.AVAILABLE
    }

    /**
     * The scanner's fixes for [pace], the same from every source. Moving: precise, every
     * fifteen seconds, and only once the phone has gone 25 m, since a coarse Wi-Fi/cell fix
     * of 100 m or worse places nothing (see [MAX_FIX_ACCURACY_M]). Settled ([MotionPolicy]):
     * a coarse fix every [settledFixMs], which keeps the position fresh for the cell checks
     * at a fraction of the cost.
     */
    fun scannerFixes(pace: MotionPolicy.Pace, settledFixMs: Long): FixRequest = when (pace) {
        MotionPolicy.Pace.MOVING -> FixRequest(highAccuracy = true, intervalMs = 15_000L, minIntervalMs = 10_000L, minDistanceM = 25f)
        MotionPolicy.Pace.STILL -> FixRequest(highAccuracy = false, intervalMs = settledFixMs, minIntervalMs = 60_000L)
    }
}

/**
 * A fix Android marks as mock ([android.location.Location.isMock]) comes from an app
 * supplying positions through the developer setting "Select mock location app", and
 * while one does, every fix the phone hands out is that app's invention. It can pin the
 * phone to one spot, so that nothing ever moves with it and no follower is confirmed, or
 * walk it down a road so that a companion's phone looks like one. So the scanner does not
 * use a mock fix at all: it places no sighting, moves no odometer, and the phone's
 * position is unknown until a real fix comes. The last real position stays in the status
 * only while it is recent enough for an SOS to send as where the phone is ([keepLastReal]).
 */
object MockFixes {

    /** A mock fix this recent and no real one since: positions are being faked ([PositionState.faking]). */
    const val MEMORY_MS = 10 * 60_000L

    /**
     * Takes one fix for [track]: a real one moves it on, a mock one leaves the phone's
     * position unknown ([LocationTrack.forget]) rather than standing at a fix from before.
     * Returns whether [fix] was real, for the caller to use it everywhere else or nowhere.
     */
    fun take(track: LocationTrack, fix: Fix, mock: Boolean): Boolean {
        if (mock) {
            track.forget()
            return false
        }
        track.update(fix)
        return true
    }

    /**
     * While fixes are mock, the scanner's status may keep the last real position for this
     * long after it was taken, and no longer: the age [SosLocation] allows a last known
     * position. An SOS raised while the status holds a position sends that one, without
     * looking for another and without saying how old it is, so a position from before an
     * app started faking (hours or days before, at the home someone fled) would read to
     * the people coming to help as where the phone is now.
     */
    const val LAST_REAL_KEPT_MS = SosLocation.LAST_KNOWN_MAX_AGE_MS

    /**
     * Whether the status keeps the last real position at [now] while fixes are mock, given
     * when that position was taken ([lastRealAt], 0 when there was none); both on the
     * phone's monotonic clock. Once it may not, the status holds no position and an SOS
     * looks for one itself, and says so when it finds only faked ones.
     */
    fun keepLastReal(lastRealAt: Long, now: Long): Boolean =
        lastRealAt > 0L && now >= lastRealAt && now - lastRealAt <= LAST_REAL_KEPT_MS
}

/** What the screens are told about positions: published on [app.clairix.Registry.positioning]. */
data class PositionState(
    /** Where fixes come from now; null until something has asked. */
    val source: LocationSource? = null,
    /** Whether the GPS check can compare with a Wi-Fi/cell position; null until known. */
    val networkCheck: NetworkCheck? = null,
    /** When the scanner's latest fix arrived, if Android marked it as mock; 0 when it was real, or none came. */
    val mockFixAt: Long = 0L
) {
    /** An app is supplying this phone's positions; see [MockFixes]. */
    fun faking(now: Long): Boolean = mockFixAt != 0L && now - mockFixAt <= MockFixes.MEMORY_MS
}

/** What the Trackers screen and the GPS page say about positions, in one place. */
object PositionText {

    /** Where fixes come from, or null when there is nothing to add: Google Play services, the usual case. */
    fun source(source: LocationSource?): String? = when (source) {
        LocationSource.PHONE -> "Using the phone's own location (no Google Play services)."
        LocationSource.PHONE_FALLBACK -> "Using the phone's own location: Google Play services did not answer the request."
        LocationSource.SATELLITE -> "Using the satellites alone: Satellite-only positioning is on in Settings."
        LocationSource.SATELLITE_UNAVAILABLE -> "Satellite-only positioning is on in Settings, and this phone has no satellite receiver."
        LocationSource.NONE -> "This phone has no location provider Clairix can use."
        LocationSource.GOOGLE, null -> null
    }

    /** Whether [source]'s line is a problem to act on rather than a fact. */
    fun sourceIsProblem(source: LocationSource?): Boolean =
        source == LocationSource.SATELLITE_UNAVAILABLE || source == LocationSource.NONE

    /** Why the satellite position cannot be held against a Wi-Fi/cell one, or null when it can. */
    fun network(check: NetworkCheck?): String? = when (check) {
        NetworkCheck.NO_PROVIDER -> "This phone has no Wi-Fi/cell position source; the comparison check cannot run here."
        NetworkCheck.OFF -> "Cross-check GPS against Wi-Fi/cell position is off in Settings; the comparison check does not run."
        NetworkCheck.SATELLITE_ONLY ->
            "Satellite-only positioning is on, so nothing asks for a Wi-Fi/cell position; the comparison check does not run."
        NetworkCheck.AVAILABLE, null -> null
    }

    /** Whether [check]'s line is a limit of the phone rather than the owner's choice. */
    fun networkIsProblem(check: NetworkCheck?): Boolean = check == NetworkCheck.NO_PROVIDER

    /** Said wherever a position is missing because an app supplies fake ones. */
    const val FAKED = "An app is faking this phone's position (Developer options, Select mock location app). " +
        "Clairix does not use faked positions, so following can't be confirmed or ruled out until it is turned off."

    /** The follow assessment of a device that keeps showing up while positions are faked. */
    const val FAKED_CONFIDENCE = "Position faked by an app, so movement can't be confirmed"

    const val FAKED_CONFIDENCE_DETAIL = "It keeps showing up. An app is supplying this phone's position (Developer " +
        "options, Select mock location app), and Clairix does not use faked positions, so whether it moves with you " +
        "can't be judged until that app is turned off."

    /** The Trackers details line while the scanner has no fix: why, as far as it is known. */
    fun noFix(source: LocationSource?, faking: Boolean): String = when {
        faking -> FAKED
        sourceIsProblem(source) -> "No position, so following can't be confirmed."
        source == LocationSource.SATELLITE -> "No satellite fix yet; indoors there may be none. Following can't be confirmed without one."
        else -> "No position fix yet. Following can't be confirmed without one."
    }
}
