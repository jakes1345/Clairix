package app.clairix.analysis

import android.content.Context
import android.location.Location

/**
 * Google Play services' fused location, as the rest of the app sees it. Only the editions
 * that carry the Play services library implement it (src/gms, compiled into the full and
 * Play editions); the F-Droid edition (src/foss) has no implementation at all, and
 * [EditionLocation.fused] is null there, so every fix comes from Android's own location
 * service ([LocationClient]'s platform path, [app.clairix.security.SosLocation]'s
 * `LocationManager` lookups).
 *
 * Nothing outside src/gms names a Google class: this interface is the whole boundary.
 */
interface FusedLocation {

    /** Whether Google Play services answers as usable on this phone now. Never throws. */
    fun usable(context: Context): Boolean

    /** A client for one owner; every fix it gets goes to [onFixes] on the main thread. */
    fun client(context: Context, onFixes: (List<Location>) -> Unit): Client

    /**
     * The last position Google's client holds, waited for up to [timeoutMs]. Blocks: call
     * off the main thread. Throws nothing; a refusal comes back as [FusedAnswer.Refused].
     */
    fun awaitLast(context: Context, timeoutMs: Long): FusedAnswer

    /** A fresh high-accuracy fix, waited for up to [timeoutMs]. Blocks, as [awaitLast]. */
    fun awaitCurrent(context: Context, timeoutMs: Long): FusedAnswer

    /** One owner's standing request to Google's client. */
    interface Client {
        /**
         * Asks for [fixes], replacing this client's earlier request. A refusal arrives
         * later, on the main thread, through [onFailure]. Throws [SecurityException]
         * without location permission.
         */
        fun request(fixes: FixRequest, onFailure: (Exception) -> Unit)

        /** Drops the standing request, if there is one. Never throws. */
        fun remove()

        /**
         * The last position Google's client holds, to [onResult] on the main thread, or
         * the refusal to [onFailure]. Throws [SecurityException] without permission.
         */
        fun lastLocation(onResult: (Location?) -> Unit, onFailure: (Exception) -> Unit)
    }
}

/** What a blocking lookup from Google's client came to. */
sealed interface FusedAnswer {
    /** Google's client answered; with null when it holds no position. */
    data class Answered(val location: Location?) : FusedAnswer

    /** Google Play services refused or failed the request: Android's own service should be asked instead. */
    data class Refused(val error: Exception) : FusedAnswer

    /** No answer in time (or the wait was interrupted): not a refusal, just nothing. */
    data object NoAnswer : FusedAnswer
}
