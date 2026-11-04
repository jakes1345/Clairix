package app.clairix

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable

/**
 * What a build's tier (the `tier` flavour dimension in app/build.gradle.kts)
 * adds to the code every build shares. Each tier's source set has the one
 * implementation, `object Tier : TierShell`:
 *
 *  - src/suite: Drive mode (the RTL-SDR radio scanner, ADS-B and OBD-II), the
 *    NFC reader, the card vault and card emulation.
 *  - src/comms: none of them. The comms-only build is compiled without those
 *    classes, so they are not in its APK at all (release.yml checks R8's mapping
 *    for it), not merely unreachable.
 *
 * The shared code (MainActivity, the wipe, history retention, the scanner
 * service) reaches those features only through here.
 */
interface TierShell {

    /** The Drive tab's content, or null in a tier without Drive mode. */
    val driveTab: (@Composable () -> Unit)?

    /** Whether Drive mode is running now. Always false in a tier without it. */
    val driveActive: Boolean

    /** The foreground services this tier adds to the shared ones, which the wipe must stop. */
    val foregroundServices: List<Class<out Service>>

    /** Deletes every radio-scanner recording, and the plain ones an earlier version left (Clear data). */
    fun deleteScannerRecordings(context: Context)

    /** Deletes the radio-scanner recordings last written before [cutoff] (history retention). */
    fun pruneScannerRecordings(context: Context, cutoff: Long)

    /** Stops an armed emulated card answering readers, wherever the app is (lock, wipe). */
    fun disarmCardEmulation(context: Context)

    /** The NFC reader, card vault and card emulation for [activity], or null in a tier without them. */
    fun nfcHost(activity: AppCompatActivity): NfcHost?
}

/**
 * The NFC tab's side of MainActivity: reader mode, tags delivered by intent, the
 * card vault and card emulation. MainActivity forwards its lifecycle here.
 */
interface NfcHost {

    /** From onCreate, once the Registry and the app lock are set up. */
    fun onCreate()

    /** From onResume. */
    fun onResume()

    /** From onPause. */
    fun onPause()

    /** A launching or new intent, which may carry a tapped tag. */
    fun onIntent(intent: Intent?)

    /** The NFC tab. */
    @Composable
    fun Screen()
}
