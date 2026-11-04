package app.clairix

import android.app.Service
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable

/**
 * The whole app's tier: Drive mode with the radio scanner, the NFC reader, the
 * card vault and (in the full edition) card emulation. See [TierShell].
 */
internal object Tier : TierShell {

    override val driveTab: (@Composable () -> Unit) = { DriveScreen() }

    override val driveActive: Boolean
        get() = DriveService.active

    override val foregroundServices: List<Class<out Service>> = listOf(DriveService::class.java)

    override fun deleteScannerRecordings(context: Context) {
        ScannerRecordings.deleteEverything(context)
    }

    override fun pruneScannerRecordings(context: Context, cutoff: Long) {
        ScannerRecordings.pruneOlderThan(context, cutoff)
    }

    override fun disarmCardEmulation(context: Context) = EmulationGuard.disarm(context)

    override fun nfcHost(activity: AppCompatActivity): NfcHost = NfcVaultHost(activity)
}
