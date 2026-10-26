package app.clairix.security

import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import app.clairix.CardEmulationBridge
import app.clairix.Registry
import app.clairix.ScanService
import app.clairix.comms.CallService
import app.clairix.comms.CommsRepository
import app.clairix.comms.CommsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush
import java.io.File
import java.security.KeyStore

/**
 * Full data removal. Erases every Clairix data store, then kills the process.
 * Only one wipe can run at a time; once started it cannot be stopped.
 *
 * Nothing here works from a list of what the app is known to store: the
 * stores are enumerated (every Keystore alias, every preferences file, every
 * database, everything under the private and external app directories), so a
 * store added later is wiped without anyone remembering to add it here.
 *
 * The wipe order, fastest destruction first: nothing that can wait on the
 * network or a lock comes before the keys are gone, so whoever pulls the
 * power a second after the duress code is typed finds only ciphertext.
 *   1. Sign the relay's account deletion, in memory (it needs the identity
 *      keys that go next), to send at the very end.
 *   2. Delete every AndroidKeyStore key. From here on, whatever survives
 *      below by accident (the vault, cached message bodies, the identity
 *      pickle) is ciphertext nobody holds a key to, and nothing can make a
 *      new key ([app.clairix.comms.KeystoreBox] refuses while [isActive]).
 *   3. Stop the foreground services, so nothing keeps writing.
 *   4. What lives outside the app's files and would otherwise survive the
 *      process dying: every notification (a message preview or an SOS with a
 *      position stays in the shade after the app is gone), the clipboard (a
 *      safety number or invite link copied earlier), the push registration,
 *      the device-admin role (an active admin cannot be uninstalled, which is
 *      itself evidence) and an armed card's answers to NFC readers.
 *   5. Clear and delete every SharedPreferences file.
 *   6. Delete every SQLite database.
 *   7. Delete everything under filesDir, cacheDir, code_cache, no_backup, any
 *      other private directory except the native-library link, and the
 *      external files and cache directories (map tiles, now in no_backup, were
 *      kept there by earlier versions, and tiles are a record of where the
 *      owner has been). The "wipe
 *      pending" mark goes last of all, so a wipe cut short anywhere is resumed.
 *   8. Send the signed deletion to the relay, for a few seconds at most.
 *   9. Kill the process — Android restarts it to a blank slate.
 *
 * [isActive] is also read by the stores that buffer writes ([app.clairix.Store]):
 * a flush after step 5 would put a plaintext timeline straight back on disk.
 */
object Wiper {

    @Volatile
    var isActive = false
        private set

    /** How long the relay gets to acknowledge before the local wipe goes ahead without it. */
    private const val RELAY_BUDGET_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start the wipe. Idempotent. Returns immediately; the process will die. */
    fun start(context: Context) {
        synchronized(this) {
            if (isActive) return
            isActive = true
        }
        val app = context.applicationContext
        // A durable "wipe pending" mark, written before anything is erased. If the
        // process is killed mid-wipe (OOM, force-stop) the next launch finds it
        // ([AppLock.init]) and finishes the job before the app becomes usable;
        // otherwise a half-wiped app could come back with no passcode and data
        // still on disk. Step 5 deletes it along with the rest of filesDir.
        runCatching { pendingMarker(app).createNewFile() }
        scope.launch {
            try {
                val farewell = runCatching { CommsRepository.preparedRelayWipe() }.getOrNull()
                runCatching { wipeKeystore() }
                stopServices(app)
                runCatching { wipeOutsideFiles(app) }
                runCatching { wipePrefs(app) }
                runCatching { wipeDatabases(app) }
                runCatching { wipeFiles(app) }
                runCatching { pendingMarker(app).delete() }
                if (farewell != null) {
                    withTimeoutOrNull(RELAY_BUDGET_MS) {
                        runInterruptible { CommsRepository.sendRelayWipe(farewell, RELAY_BUDGET_MS / 1000) }
                    }
                }
            } finally {
                stopServices(app)
                // Last, and whatever happened above: the next launch finds nothing.
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** These foreground services run START_STICKY; without stopping them Android would restart them (and the process) after the kill. */
    private fun stopServices(app: Context) {
        runCatching { app.stopService(Intent(app, ScanService::class.java)) }
        runCatching { app.stopService(Intent(app, CommsService::class.java)) }
        runCatching { app.stopService(Intent(app, CallService::class.java)) }
    }

    /** The file whose presence means a wipe was started and has not been seen through. */
    internal fun pendingMarker(context: Context): File = File(context.filesDir, PENDING_MARKER)

    /** Whether a previous wipe was interrupted before it finished. */
    fun isPending(context: Context): Boolean = runCatching { pendingMarker(context).exists() }.getOrDefault(false)

    private const val PENDING_MARKER = "wipe_pending"

    /** Step 2: what the system holds on the app's behalf rather than in its files. */
    private fun wipeOutsideFiles(app: Context) {
        runCatching { NotificationManagerCompat.from(app).cancelAll() }
        runCatching { app.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
        runCatching { UnifiedPush.unregister(app) }
        runCatching {
            val admin = UnlockLedger.adminComponent(app)
            val dpm = app.getSystemService(DevicePolicyManager::class.java)
            if (dpm?.isAdminActive(admin) == true) dpm.removeActiveAdmin(admin)
        }
        runCatching { CardEmulationBridge.disarm(app) }
        Registry.setEmulating(null)
    }

    /** Every key in the app's Keystore namespace: the vault's, the lock's, the biometric one, COMMS'. */
    private fun wipeKeystore() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        for (alias in ks.aliases().toList()) {
            runCatching { ks.deleteEntry(alias) }
        }
    }

    private fun wipePrefs(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val onDisk = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()
        // Named as well as found: an instance already loaded in this process keeps
        // its map in memory and would write it back over a deleted file.
        val known = listOf(
            "prefs",            // AppSettings, onboarding
            "comms_identity",   // IdentityStore
            "comms_transport",  // TransportKeyStore
            "comms_vault",      // VaultBox's wrapped data key
            "comms",            // CommsConfig
            "comms_log",        // CommsLog
            "comms_ui",         // CommsScreen
            "card_vault",       // CardVault
            "osmdroid",         // map configuration
            AppLock.PREFS_NAME,
        )
        for (name in (onDisk + known).toSet()) {
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        }
        runCatching { dir.deleteRecursively() }
    }

    private fun wipeDatabases(context: Context) {
        val names = context.databaseList().toList() + listOf("comms_e2ee.db", "comms.db")
        for (name in names.toSet()) {
            runCatching { context.deleteDatabase(name) }
        }
        runCatching { File(context.applicationInfo.dataDir, "databases").deleteRecursively() }
    }

    private fun wipeFiles(context: Context) {
        val roots = ArrayList<File>()
        roots += context.filesDir
        roots += context.cacheDir
        roots += context.codeCacheDir
        roots += context.noBackupFilesDir
        context.getExternalFilesDirs(null)?.filterNotNull()?.let { roots += it }
        context.externalCacheDirs?.filterNotNull()?.let { roots += it }
        // Whatever else lives in the private data directory (getDir() folders,
        // WebView caches), apart from the link to the APK's native libraries.
        File(context.applicationInfo.dataDir).listFiles()
            ?.filter { it.name !in setOf("lib", "shared_prefs", "databases") }
            ?.let { roots += it }
        val marker = pendingMarker(context).canonicalFile
        for (root in roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }) {
            runCatching {
                val children = root.listFiles() ?: return@runCatching
                for (child in children) runCatching { if (child.canonicalFile != marker) child.deleteRecursively() }
            }
        }
    }
}
