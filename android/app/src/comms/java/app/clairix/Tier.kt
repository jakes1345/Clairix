package app.clairix

import android.app.Service
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import java.io.File

/**
 * The comms-only tier: encrypted messages, calls, groups and SOS with the safety
 * pages. It has no Drive mode, radio scanner, NFC reader, card vault or card
 * emulation; their code lives in src/suite and is not compiled into this build.
 * See [TierShell].
 */
internal object Tier : TierShell {

    /** No Drive tab: the comms-only build has the one Comms tab. */
    override val driveTab: (@Composable () -> Unit)? = null

    /** There is no Drive mode to be running. */
    override val driveActive: Boolean = false

    /** None beyond the shared ones: no Drive service. */
    override val foregroundServices: List<Class<out Service>> = emptyList()

    /**
     * This build records nothing. It shares the application id with the whole app,
     * though, so a phone that had the suite installed before it can still hold the
     * suite's sealed recordings, in the same folder the suite writes them to
     * (ScannerRecordings.dir in src/suite); every file there is one, and they go.
     */
    override fun deleteScannerRecordings(context: Context) {
        leftRecordings(context).forEach { it.delete() }
    }

    /** The same left-behind recordings, by when each was last written. */
    override fun pruneScannerRecordings(context: Context, cutoff: Long) {
        leftRecordings(context).filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }

    /** No card can be armed: this build has no card emulation. */
    override fun disarmCardEmulation(context: Context) = Unit

    /** No NFC tab. */
    override fun nfcHost(activity: AppCompatActivity): NfcHost? = null

    private fun leftRecordings(context: Context): List<File> =
        File(context.applicationContext.noBackupFilesDir, "scanner")
            .listFiles { f -> f.isFile && (f.name.endsWith(".rec") || f.name.endsWith(".rec.tmp")) }
            ?.toList().orEmpty()
}
