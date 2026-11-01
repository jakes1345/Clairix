package app.clairix.security

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import app.clairix.AppSettings
import app.clairix.CardEmulationBridge
import app.clairix.ONBOARDING_DONE_KEY
import app.clairix.Registry
import app.clairix.ScanService
import app.clairix.TOUR_SEEN_KEY
import app.clairix.analysis.TlsCanary
import app.clairix.comms.CallService
import app.clairix.comms.CommsService
import app.clairix.detect.KarmaCanary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

/**
 * Full data removal. Erases every Clairix data store; only one wipe runs at a
 * time, and once started it cannot be stopped.
 *
 * Nothing here works from a list of what the app is known to store: the
 * stores are enumerated (every Keystore alias, every preferences file, every
 * database, everything under the private and external app directories), so a
 * store added later is wiped without anyone remembering to add it here.
 *
 * What it leaves depends on what started it.
 *  - The duress passcode leaves a decoy, not a first run. The lock screen it
 *    was typed into says "Wrong passcode" like any wrong guess and stays; a
 *    moment later Clairix comes back to it in a fresh process, so nothing of
 *    the old data is left even in memory ([WipeRelaunchActivity]), and the
 *    scanner, if it was on, starts again with its notification. From then on
 *    the duress passcode is the passcode: it opens Clairix as a fresh install
 *    looks once its first-run pages are done, with the same theme, scanner and
 *    lock settings, and no history, contacts or Clairix number.
 *  - The wrong-passcode limit, a verifier whose key has gone, and the Wipe page
 *    close Clairix; it opens next as a new install.
 *
 * The order, fastest destruction first: nothing that waits on the network or a
 * lock comes before the keys are gone, so whoever pulls the power a moment
 * after the duress code is typed finds only ciphertext.
 *   1. Mark the wipe pending, with its plan ([WipePlan]): what a decoy keeps,
 *      read before anything is erased, and the relay account deletions, signed
 *      in memory for the next day ([RelayFarewell]) because the identity key
 *      that signs them goes next. Tens of milliseconds.
 *   2. Delete every AndroidKeyStore key. From here on, whatever survives below
 *      by accident (the vault, cached message bodies, the identity pickle) is
 *      ciphertext nobody holds a key to, and nothing can make a new key for it
 *      ([app.clairix.comms.KeystoreBox] refuses while [isActive]).
 *   3. Duress only: make the decoy's lock, a fresh key and the code's verifier
 *      under it ([AppLock.makeDecoyLock]), and write it into the mark at once,
 *      so a wipe cut short from here on still leaves it.
 *   4. Stop the foreground services, so nothing keeps writing.
 *   5. What lives outside the app's files and would otherwise survive: every
 *      notification and notification channel (a message preview in the shade,
 *      a channel listed by name and purpose in Settings), the clipboard (a
 *      safety number or invite link copied earlier), the push registration,
 *      the device-admin role (an active admin cannot be uninstalled, which is
 *      itself evidence) and an armed card's answers to NFC readers. Each part
 *      of the app makes its channels again when it next starts.
 *   6. Clear and delete every SharedPreferences file.
 *   7. Delete every SQLite database.
 *   8. Delete everything under filesDir, cacheDir, code_cache, no_backup, any
 *      other private directory except the native-library link, and the
 *      external files and cache directories (map tiles, now in no_backup, were
 *      kept there by earlier versions, and tiles are a record of where the
 *      owner has been).
 *   9. Duress only: write the decoy's settings and lock.
 *  10. Keep the relay deletions in a plain file until the relay has taken one
 *      ([RelayFarewell]). It is sent as soon as Clairix runs again (at once,
 *      for the decoy), and an alarm keeps trying whether or not it does. A
 *      deletion signed with Tor on is tried through Tor before the app closes,
 *      and never sent directly.
 *  11. Delete the "wipe pending" mark, last of all, so a wipe cut short
 *      anywhere is resumed at the next start ([app.clairix.ClairixApp]).
 *  12. Back to the lock screen in a fresh process (duress), or close.
 *
 * [isActive] is also read by the stores that buffer writes ([app.clairix.Store]):
 * a flush after step 6 would put a plaintext timeline straight back on disk. It
 * stays set until the process ends.
 */
object Wiper {

    @Volatile
    var isActive = false
        private set

    /** How long the deletions that must go through Tor get, while this process still has Tor, before the app closes anyway. */
    private const val TOR_BUDGET_MS = 6_000L

    /**
     * After asking for the relaunch: still alive this long, this process was not
     * ended by it (Clairix was not on screen, so Android would not start it) and
     * ends itself; the decoy is on disk for the next start either way.
     */
    private const val RELAUNCH_GRACE_MS = 3_000L

    internal const val PENDING_MARKER = "wipe_pending"

    /**
     * Named as well as found: an instance already loaded in this process keeps
     * its map in memory and would write it back over a deleted file.
     */
    internal val KNOWN_PREFS = listOf(
        "prefs",            // AppSettings, onboarding
        "comms_identity",   // IdentityStore
        "comms_transport",  // TransportKeyStore
        "comms_vault",      // VaultBox's wrapped data key
        "comms_tor",        // TorTransport
        "comms",            // CommsConfig
        "comms_log",        // CommsLog
        "comms_ui",         // CommsScreen
        "card_vault",       // CardVault
        "osmdroid",         // map configuration
        AppLock.PREFS_NAME,
    )

    internal val KNOWN_DATABASES = listOf("comms_e2ee.db", "comms.db")

    /** Under the data directory, what step 8 leaves to others: the native-library link, and what steps 6 and 7 empty. */
    private val DATA_DIR_SKIP = setOf("lib", "shared_prefs", "databases")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts the wipe, or carries on with one a previous process left unfinished
     * (its plan is in the mark). [duressCode] is the duress passcode that started
     * it, for the decoy's lock; null for every other wipe. Idempotent. Returns at
     * once; the process will end.
     */
    fun start(context: Context, duressCode: String? = null) {
        if (!begin()) return
        val app = context.applicationContext
        val roots = WipeRoots.of(app)
        val resumed = readMarker(roots.marker)
        // A new wipe is planned now, before anything it reads is erased.
        val planned = resumed ?: WipePlan(decoy = if (duressCode != null) runCatching { readDecoy(app) }.getOrNull() else null)
        // The durable mark, before anything is erased. If the process is killed
        // mid-wipe (OOM, force-stop, the power) the next start finds it and
        // finishes the job before the app becomes usable; otherwise a half-wiped
        // app could come back with no passcode and data still on disk.
        if (resumed == null) runCatching { writeMarker(roots.marker, planned) }
        scope.launch {
            var plan = planned
            try {
                if (resumed == null) {
                    // Step 1's relay deletions, signed while the identity exists: one copy every
                    // RelayFarewell.STEP_MS for the next day.
                    val farewells = runCatching { RelayFarewell.prepareAll() }.getOrDefault(emptyList())
                    if (farewells.isNotEmpty()) {
                        plan = plan.copy(farewells = farewells)
                        runCatching { writeMarker(roots.marker, plan) }
                    }
                }
                plan = run(plan, duressCode, roots, AndroidWipeSystem(app))
            } finally {
                finish(app, plan)
            }
        }
    }

    /** Marks a wipe as running; false when one already is. */
    internal fun begin(): Boolean = synchronized(this) {
        if (isActive) {
            false
        } else {
            isActive = true
            true
        }
    }

    /** For JVM tests, which share one process: the wipe a test ran no longer refuses the next test's writes. */
    @androidx.annotation.VisibleForTesting
    internal fun endForTest() {
        synchronized(this) { isActive = false }
    }

    /** The file whose presence means a wipe was started and has not been seen through. */
    internal fun pendingMarker(context: Context): File = File(context.filesDir, PENDING_MARKER)

    /** Whether a previous wipe was interrupted before it finished. */
    fun isPending(context: Context): Boolean = runCatching { pendingMarker(context).exists() }.getOrDefault(false)

    /**
     * Steps 2 to 11, in order, over [roots], with what only Android can do
     * asked of [system]. Every step runs whatever happened to the one before it.
     * Returns the plan as it ended (with the decoy's lock, once made).
     */
    internal fun run(plan: WipePlan, duressCode: String?, roots: WipeRoots, system: WipeSystem): WipePlan {
        var current = plan
        // 2. A decoy lock already made (a wipe resumed past step 3) keeps its key:
        //    it was made after every other key had gone.
        runCatching { system.destroyKeys(if (current.decoyLock != null) setOf(AppLock.KEY_ALIAS) else emptySet()) }
        // 3.
        if (current.decoy != null && current.decoyLock == null && duressCode != null) {
            runCatching { system.makeDecoyLock(duressCode) }.getOrNull()?.let { lock ->
                current = current.copy(decoyLock = lock)
                runCatching { writeMarker(roots.marker, current) }
            }
        }
        runCatching { system.stopServices() }                       // 4
        runCatching { system.clearOutside() }                       // 5
        runCatching { wipePrefs(roots.prefsDir, system) }           // 6
        runCatching { wipeDatabases(roots.databasesDir, system) }   // 7
        runCatching { wipeFiles(roots) }                            // 8
        val decoy = current.decoy
        if (decoy != null) runCatching { system.writeDecoy(decoy, current.decoyLock) }   // 9
        val kept = current.farewells.filterNot { it.viaTor }
        if (kept.isNotEmpty()) runCatching { system.keepFarewells(kept) }               // 10
        runCatching { roots.marker.delete() }                       // 11
        return current
    }

    /** Step 6: every preferences file, found or known, emptied in memory and then deleted with its directory. */
    internal fun wipePrefs(dir: File, system: WipeSystem) {
        val onDisk = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()
        for (name in (onDisk + KNOWN_PREFS).toSet()) runCatching { system.clearLoadedPrefs(name) }
        runCatching { dir.deleteRecursively() }
    }

    /** Step 7: every database, found or known, deleted with its journals, then the directory. */
    internal fun wipeDatabases(dir: File, system: WipeSystem) {
        val names = (dir.list()?.toList() ?: emptyList()) + KNOWN_DATABASES
        for (name in names.toSet()) runCatching { system.deleteDatabase(name) }
        runCatching { dir.deleteRecursively() }
    }

    /**
     * Step 8: everything under the app's own directories, apart from the mark
     * (it goes last) and a relay deletion an earlier wipe is still trying to send.
     */
    internal fun wipeFiles(roots: WipeRoots) {
        val keep = setOf(canonical(roots.marker), canonical(roots.farewell))
        val dirs = ArrayList<File>()
        dirs += roots.filesDir
        dirs += roots.cacheDir
        dirs += roots.codeCacheDir
        dirs += roots.noBackupDir
        dirs += roots.externalDirs
        // Whatever else lives in the private data directory (getDir() folders,
        // WebView caches), apart from the link to the APK's native libraries.
        roots.dataDir.listFiles()?.forEach { child ->
            when {
                child.name in DATA_DIR_SKIP -> Unit
                child.isDirectory -> dirs += child
                else -> runCatching { child.delete() }
            }
        }
        for (dir in dirs.distinctBy { canonical(it) }) {
            runCatching {
                val children = dir.listFiles() ?: return@runCatching
                for (child in children) runCatching { if (canonical(child) !in keep) child.deleteRecursively() }
            }
        }
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.path)

    internal fun writeMarker(marker: File, plan: WipePlan) {
        val bytes = plan.toJson().toString().toByteArray(Charsets.UTF_8)
        marker.parentFile?.mkdirs()
        val tmp = File(marker.parentFile, "${marker.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(marker)) {
            FileOutputStream(marker).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            tmp.delete()
        }
    }

    /** The plan in [marker], or null when no wipe is pending. An empty mark (an earlier version's) or an unreadable one is a plain wipe. */
    internal fun readMarker(marker: File): WipePlan? {
        if (!marker.exists()) return null
        return runCatching { WipePlan.fromJson(marker.readText()) }.getOrDefault(WipePlan())
    }

    /** What the decoy keeps, read before anything is erased: see [Decoy]. */
    private fun readDecoy(app: Context): Decoy {
        val keep = AppSettings.DECOY_KEYS
        val prefs = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                if (key in keep && value != null && (value is Boolean || value is Int || value is Long || value is Float || value is String)) key to value else null
            }
            .toMap()
        return Decoy(prefs, HistoryRetention.days(app), AppLock.wipeAfter(app), AppLock.relockAfterMs(app), AppLock.isBiometricEnabled(app))
    }

    /** Step 10's Tor part, then step 12. */
    private suspend fun finish(app: Context, plan: WipePlan) {
        try {
            val viaTor = plan.farewells.filter { it.viaTor }
            if (viaTor.isNotEmpty()) {
                withTimeoutOrNull(TOR_BUDGET_MS) { runInterruptible { RelayFarewell.deliverNow(viaTor, TOR_BUDGET_MS / 1000) } }
            }
            if (RelayFarewell.waiting(app)) RelayFarewell.arm(app, RelayFarewell.FIRST_TRY_MS)
            if (plan.decoy != null && WipeRelaunchActivity.relaunch(app)) delay(RELAUNCH_GRACE_MS)
        } finally {
            stopServices(app)
            // A channel whose service was still winding down at step 5 could not be deleted then.
            deleteChannels(app)
            // Whatever happened above: no process carries the old data, even in memory.
            Process.killProcess(Process.myPid())
        }
    }

    /** These foreground services run START_STICKY; without stopping them Android would restart them, and the process, after it ends. */
    private fun stopServices(app: Context) {
        runCatching { app.stopService(Intent(app, ScanService::class.java)) }
        runCatching { app.stopService(Intent(app, CommsService::class.java)) }
        runCatching { app.stopService(Intent(app, CallService::class.java)) }
        // Alarms live with the system, not in the files erased below.
        runCatching { TlsCanary.stop(app) }
        runCatching { HistoryRetention.cancelSchedule(app) }
        // So does the Wi-Fi trap's network suggestion, which the scanner withdraws only
        // in onDestroy, and a wipe may end the process before that runs.
        runCatching { KarmaCanary.withdrawForWipe(app) }
    }

    /** Every notification channel and channel group the app has made. */
    private fun deleteChannels(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notificationChannels.forEach { channel -> runCatching { nm.deleteNotificationChannel(channel.id) } } }
        runCatching { nm.notificationChannelGroups.forEach { group -> runCatching { nm.deleteNotificationChannelGroup(group.id) } } }
    }

    /** What a wipe asks of the phone itself. */
    private class AndroidWipeSystem(private val app: Context) : WipeSystem {

        override fun destroyKeys(keep: Set<String>) {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            for (alias in ks.aliases().toList()) {
                if (alias !in keep) runCatching { ks.deleteEntry(alias) }
            }
        }

        override fun stopServices() = Wiper.stopServices(app)

        override fun clearOutside() {
            runCatching { NotificationManagerCompat.from(app).cancelAll() }
            deleteChannels(app)
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

        override fun clearLoadedPrefs(name: String) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }

        override fun deleteDatabase(name: String) {
            app.deleteDatabase(name)
        }

        override fun makeDecoyLock(code: String): DecoyLock = AppLock.makeDecoyLock(code)

        override fun writeDecoy(decoy: Decoy, lock: DecoyLock?) {
            // Committed, not applied: the process ends moments from now.
            val edit = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in decoy.prefs) {
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                }
            }
            edit.putBoolean(ONBOARDING_DONE_KEY, true).putBoolean(TOUR_SEEN_KEY, true).commit()
            HistoryRetention.writeDays(app, decoy.retentionDays)
            // Without a lock (a wipe cut short between steps 2 and 3, the code
            // never written down) the decoy opens unlocked rather than to a lock
            // nobody could pass.
            if (lock != null) AppLock.writeDecoy(app, lock, decoy.wipeAfter, decoy.relockMs, decoy.biometric)
        }

        override fun keepFarewells(list: List<RelayFarewell.Prepared>) = RelayFarewell.keep(app.filesDir, list)
    }
}

/** The app's directories a wipe empties, as plain files, so the JVM test can hand it a temporary tree. */
internal class WipeRoots(
    val dataDir: File,
    val filesDir: File,
    val cacheDir: File,
    val codeCacheDir: File,
    val noBackupDir: File,
    val externalDirs: List<File>,
    /** The "wipe pending" mark; a parameter so the JVM test can see when it goes. */
    val marker: File = File(filesDir, Wiper.PENDING_MARKER),
) {
    val prefsDir: File get() = File(dataDir, "shared_prefs")
    val databasesDir: File get() = File(dataDir, "databases")
    val farewell: File get() = RelayFarewell.file(filesDir)

    companion object {
        fun of(context: Context): WipeRoots {
            val external = ArrayList<File>()
            context.getExternalFilesDirs(null)?.filterNotNull()?.let { external += it }
            context.externalCacheDirs?.filterNotNull()?.let { external += it }
            return WipeRoots(
                dataDir = File(context.applicationInfo.dataDir),
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                codeCacheDir = context.codeCacheDir,
                noBackupDir = context.noBackupFilesDir,
                externalDirs = external,
            )
        }
    }
}

/** What a wipe asks of Android, each in its turn; [Wiper.run] decides the order. */
internal interface WipeSystem {
    /** Deletes every key in the app's Keystore namespace but [keep]. */
    fun destroyKeys(keep: Set<String>)
    fun stopServices()
    /** Notifications and their channels, the clipboard, the push registration, the device-admin role, an armed card. */
    fun clearOutside()
    /** Empties the preferences this process may hold in memory under [name], so they cannot be written back. */
    fun clearLoadedPrefs(name: String)
    /** Deletes the database [name] with its journals. */
    fun deleteDatabase(name: String)
    /** The decoy's lock for the duress [code], under a key made now. */
    fun makeDecoyLock(code: String): DecoyLock
    /** Writes the decoy's settings and, when there is one, its lock. */
    fun writeDecoy(decoy: Decoy, lock: DecoyLock?)
    /** Keeps the relay deletions for sending once the wipe is over ([RelayFarewell]). */
    fun keepFarewells(list: List<RelayFarewell.Prepared>)
}

/** The duress code's verifier, under the key made for it once every other key had gone ([AppLock.makeDecoyLock]). */
internal data class DecoyLock(val salt: String, val verifier: String)

/**
 * What a duress wipe puts back, read before anything is erased: the look and
 * how the scanner runs ([AppSettings.DECOY_KEYS], typed as the preferences hold
 * them), how long history is kept, and the lock's settings. Nothing in it is a
 * record of anything.
 */
internal data class Decoy(
    val prefs: Map<String, Any>,
    val retentionDays: Int,
    val wipeAfter: Int,
    val relockMs: Long,
    val biometric: Boolean,
)

/** A wipe's plan, kept in the "wipe pending" mark so that a wipe cut short carries on as it began. */
internal data class WipePlan(
    val decoy: Decoy? = null,
    val decoyLock: DecoyLock? = null,
    val farewells: List<RelayFarewell.Prepared> = emptyList(),
) {
    fun toJson(): JSONObject {
        val out = JSONObject().put("v", 1)
        decoy?.let { d ->
            out.put(
                "decoy",
                JSONObject()
                    .put("prefs", encodePrefs(d.prefs))
                    .put("retentionDays", d.retentionDays)
                    .put("wipeAfter", d.wipeAfter)
                    .put("relockMs", d.relockMs)
                    .put("biometric", d.biometric)
            )
        }
        decoyLock?.let { out.put("decoyLock", JSONObject().put("salt", it.salt).put("verifier", it.verifier)) }
        if (farewells.isNotEmpty()) out.put("farewells", RelayFarewell.toJson(farewells))
        return out
    }

    companion object {
        fun fromJson(text: String): WipePlan {
            if (text.isBlank()) return WipePlan()
            val json = JSONObject(text)
            val decoy = json.optJSONObject("decoy")?.let { d ->
                Decoy(
                    prefs = decodePrefs(d.optJSONObject("prefs")),
                    retentionDays = d.optInt("retentionDays", HistoryRetention.DEFAULT_DAYS),
                    wipeAfter = d.optInt("wipeAfter", AppLock.DEFAULT_WIPE_AFTER),
                    relockMs = d.optLong("relockMs", AppLock.DEFAULT_RELOCK_MS),
                    biometric = d.optBoolean("biometric", false),
                )
            }
            val lock = json.optJSONObject("decoyLock")?.let { l ->
                val salt = l.optString("salt")
                val verifier = l.optString("verifier")
                if (salt.isEmpty() || verifier.isEmpty()) null else DecoyLock(salt, verifier)
            }
            val farewells = json.optJSONObject("farewells")?.let { RelayFarewell.fromJson(it) } ?: emptyList()
            return WipePlan(decoy, lock, farewells)
        }

        /** Each value with its type, so it goes back into the preferences as the same type. */
        private fun encodePrefs(map: Map<String, Any>): JSONObject {
            val out = JSONObject()
            for ((key, value) in map) {
                val typed = when (value) {
                    is Boolean -> JSONArray().put("b").put(value)
                    is Int -> JSONArray().put("i").put(value)
                    is Long -> JSONArray().put("l").put(value)
                    is Float -> JSONArray().put("f").put(value.toDouble())
                    is String -> JSONArray().put("s").put(value)
                    else -> continue
                }
                out.put(key, typed)
            }
            return out
        }

        private fun decodePrefs(json: JSONObject?): Map<String, Any> {
            if (json == null) return emptyMap()
            val out = LinkedHashMap<String, Any>()
            for (key in json.keys()) {
                val typed = json.optJSONArray(key) ?: continue
                out[key] = when (typed.optString(0)) {
                    "b" -> typed.optBoolean(1)
                    "i" -> typed.optInt(1)
                    "l" -> typed.optLong(1)
                    "f" -> typed.optDouble(1).toFloat()
                    "s" -> typed.optString(1)
                    else -> continue
                }
            }
            return out
        }
    }
}
