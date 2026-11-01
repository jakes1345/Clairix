package app.clairix.analysis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.clairix.Alert
import app.clairix.BuildConfig
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.Store
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the stalkerware check running without anyone opening its page: once a day (from the
 * scanner's health cycle and whenever Clairix comes to the front, [checkIfDue]), at boot and after
 * an update (BootReceiver), and a few seconds after an app is installed or changed while the
 * scanner runs ([watchPackages]). Each check is compared with the last one on record, and what is
 * new, an app that now scores HIGH or a grant nobody vouches for, is raised through
 * [Registry.publishAlert] as a HIGH platform alert; the HIGH apps go into Phone check, so the
 * hub's verdict includes them.
 *
 * The record (the last check's findings, and the grants the owner marked as theirs with "This is
 * mine") is a sealed [Store] in the app's private files.
 */
object StalkwareWatch {

    private const val TAG = "StalkwareWatch"
    private const val FILE = "stalkerware.json"
    /** A check older than this is done again; see [checkIfDue]. */
    const val CHECK_EVERY_MS = 24 * 3600_000L
    /** Installs come in bursts (an update of several apps, a restore); one check after the last. */
    private const val PACKAGE_SETTLE_MS = 15_000L
    /** How long a boot or update broadcast is held open for its check; the check itself may run on. */
    private const val BROADCAST_BUDGET_MS = 8_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var record: StalkwareRecord? = null

    private fun record(context: Context): StalkwareRecord =
        record ?: synchronized(this) {
            record ?: StalkwareRecord(Store(context.applicationContext, FILE)).also { record = it }
        }

    // ── What is new since the last check ────────────────────────────────────

    /** What a check found that the owner should hear about: each HIGH app (`app:<package>`) and each grant needing a look ([StalkwareScanner.grantKey]). */
    internal fun findings(result: StalkwareScanner.Result): Set<String> {
        val out = HashSet<String>()
        result.high.forEach { out += "app:${it.packageName}" }
        result.grants.forEach { (key, status) -> if (status == GrantStatus.UNKNOWN || status == GrantStatus.CONFIRM) out += key }
        return out
    }

    /**
     * The alerts for [new] findings of [result]: one for the apps that now score HIGH and one for
     * the grants, each HIGH and a platform alert. [silent] for a check the owner is looking at, or
     * the very first, whose findings are on screen or are the baseline: those go on the timeline
     * without a notification.
     */
    internal fun alerts(new: Set<String>, result: StalkwareScanner.Result, label: (String) -> String, now: Long, silent: Boolean): List<Alert> {
        val out = ArrayList<Alert>(2)
        val apps = result.high.filter { "app:${it.packageName}" in new }
        if (apps.isNotEmpty()) {
            val one = apps.singleOrNull()
            val title = when {
                one?.knownAs != null -> "Known stalkerware on this phone: ${one.appLabel}"
                one != null -> "${one.appLabel} looks like stalkerware"
                apps.any { it.knownAs != null } -> "${apps.size} apps look like stalkerware, including known stalkerware"
                else -> "${apps.size} apps look like stalkerware"
            }
            val detail = apps.joinToString("\n") { a -> "${a.appLabel} (${a.packageName}): ${a.reasons.joinToString("; ")}." } +
                "\nPhone check → Stalkerware shows each one and what can be done."
            out += Alert(
                id = "stalkerware_apps@$now", ts = now, severity = Severity.HIGH, title = title, detail = detail,
                kind = EventKind.PLATFORM, dedupeKey = "stalkerware_apps:" + apps.map { it.packageName }.sorted().joinToString(","),
                silent = silent,
            )
        }
        val grants = new.filter { !it.startsWith("app:") }.sorted().mapNotNull { key ->
            val kind = GrantKind.entries.firstOrNull { key.startsWith("${it.key}:") } ?: return@mapNotNull null
            kind to key.removePrefix("${kind.key}:")
        }
        if (grants.isNotEmpty()) {
            val (kind, pkg) = grants.first()
            val title = when {
                grants.size > 1 -> "${grants.size} new kinds of special access to check"
                kind == GrantKind.DEVICE_OWNER || kind == GrantKind.PROFILE_OWNER -> "This phone is now managed by ${label(pkg)}"
                else -> "New ${kind.label}: ${label(pkg)}"
            }
            val detail = grants.joinToString("\n") { (k, p) -> "${label(p)} ($p): ${grantMeaning(k, result.status(k, p))}." } +
                "\nPhone check → Stalkerware lists every grant; mark one there as yours if you set it up."
            out += Alert(
                id = "stalkerware_grants@$now", ts = now, severity = Severity.HIGH, title = title, detail = detail,
                kind = EventKind.PLATFORM, dedupeKey = "stalkerware_grants:" + grants.joinToString(",") { "${it.first.key}:${it.second}" },
                silent = silent,
            )
        }
        return out
    }

    /** One line on what a grant lets an app do. */
    internal fun grantMeaning(kind: GrantKind, status: GrantStatus?): String = when (kind) {
        GrantKind.ACCESSIBILITY -> "accessibility service switched on, which reads every screen and every tap"
        GrantKind.NOTIFICATION_LISTENER -> "notification access, which reads every notification, one-time codes included"
        GrantKind.DEVICE_ADMIN ->
            if (status == GrantStatus.CONFIRM) "a work or school management app's device admin, which you have not confirmed you enrolled"
            else "device admin switched on, which can block its own uninstall and may lock or wipe the phone"
        GrantKind.KEYBOARD -> "a keyboard nobody vouches for, which sees everything typed with it"
        GrantKind.DEVICE_OWNER -> "the phone's device owner, which can install and remove apps silently, hide apps, set policies and install certificates"
        GrantKind.PROFILE_OWNER -> "this profile's owner, which can install and remove apps silently, hide apps, set policies and install certificates"
    }

    // ── Running a check ─────────────────────────────────────────────────────

    /**
     * Scans now, compares the result with the last check on record and raises what is new, then
     * updates Phone check. [foreground] for a check started from a screen the owner is looking at:
     * its alerts go on the timeline without a notification. With [refreshList] the release is then
     * asked for a newer stalkerware list, at most once a day ([StalkwareIoc.refreshIfStale]), in
     * the background, and the check is run again if one came: the result never waits on the network.
     */
    suspend fun check(context: Context, foreground: Boolean, refreshList: Boolean = false): StalkwareScanner.Result {
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) { mutex.withLock { checkLocked(app, foreground) } }
        if (refreshList) refreshListThenRecheck(app, foreground)
        return result
    }

    /** [check], unless one ran within [CHECK_EVERY_MS] in this process; from a screen it also refreshes the list. */
    suspend fun checkIfDue(context: Context, foreground: Boolean) {
        if (!BuildConfig.FULL_ACCESS) return
        if (!due(StalkwareScanner.results.value?.scannedAt, System.currentTimeMillis())) return
        val app = context.applicationContext
        val ran = withContext(Dispatchers.IO) {
            mutex.withLock {
                // Another caller may have checked while this one waited.
                if (due(StalkwareScanner.results.value?.scannedAt, System.currentTimeMillis())) {
                    checkLocked(app, foreground)
                    true
                } else {
                    false
                }
            }
        }
        if (ran && foreground) refreshListThenRecheck(app, foreground)
    }

    internal fun due(lastScannedAt: Long?, now: Long): Boolean =
        lastScannedAt == null || now - lastScannedAt >= CHECK_EVERY_MS || now < lastScannedAt

    /** Asks for a newer list in the background and checks again with it if one came. */
    private fun refreshListThenRecheck(app: Context, foreground: Boolean) {
        scope.launch {
            val before = StalkwareIoc.current(app)?.issued
            runCatching { StalkwareIoc.refreshIfStale(app) }
            if (StalkwareIoc.current(app)?.issued != before) {
                runCatching { check(app, foreground) }.onFailure { Log.w(TAG, "check with the new list failed", it) }
            }
        }
    }

    private fun checkLocked(app: Context, foreground: Boolean): StalkwareScanner.Result {
        val rec = record(app)
        val result = StalkwareScanner.scan(app, rec.acknowledged(), StalkwareIoc.current(app))
        val now = result.scannedAt
        val current = findings(result)
        val previous = rec.lastFindings()
        rec.saveFindings(current, now)
        Registry.updatePhoneHealth { SpywareHealth.merge(it, SpywareHealth.appFindings(result), now) }
        val new = if (previous == null) current else current - previous
        val labels = HashMap<String, String>()
        val label = { pkg: String -> labels.getOrPut(pkg) { StalkwareScanner.appLabel(app, pkg) } }
        for (alert in alerts(new, result, label, now, silent = foreground || previous == null)) Registry.publishAlert(alert)
        return result
    }

    /**
     * Runs a check for a boot or update broadcast: off the main thread, holding the broadcast
     * open for up to [BROADCAST_BUDGET_MS] and letting the check finish after that if it must.
     */
    fun checkForBroadcast(context: Context, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        // The alert goes on the timeline and to the shade only once the registry knows the app.
        Registry.bindTrustStore(app)
        scope.launch {
            val job = scope.launch {
                runCatching { check(app, foreground = false) }.onFailure { Log.w(TAG, "stalkerware check failed", it) }
            }
            withTimeoutOrNull(BROADCAST_BUDGET_MS) { job.join() }
            pending.finish()
        }
    }

    /** Forgets the last check's findings ("Clear all data"); the owner's marks stay, like the trusted devices. */
    fun clear(context: Context) {
        record(context).clearFindings()
    }

    // ── The owner's marks ───────────────────────────────────────────────────

    /** Every "This is mine" mark ([StalkwareScanner.ackKey]). */
    fun acknowledged(context: Context): Set<String> = runCatching { record(context).acknowledged() }.getOrDefault(emptySet())

    /**
     * Marks [kind] of [packageName] as the owner's, tied to the certificate it is signed with, and
     * puts that on the timeline: someone else holding the phone could press it too, and the
     * timeline is where that shows. False when the app is not installed or its signer unreadable.
     */
    fun acknowledge(context: Context, kind: GrantKind, packageName: String): Boolean {
        val app = context.applicationContext
        val signer = StalkwareScanner.Origins(app).signer(packageName)?.takeIf { it.isNotEmpty() } ?: return false
        val now = System.currentTimeMillis()
        record(app).acknowledge(StalkwareScanner.ackKey(kind, packageName, signer), now)
        val label = StalkwareScanner.appLabel(app, packageName)
        Registry.publishAlert(
            Alert(
                id = "stalkerware_mine@$now", ts = now, severity = Severity.LOW,
                title = "Marked as yours: $label (${kind.label})",
                detail = "Marked in Phone check → Stalkerware: Clairix no longer counts this ${kind.label} of $packageName as " +
                    "unknown. If you did not do this, undo it on the same page.",
                kind = EventKind.PLATFORM, dedupeKey = "stalkerware_mine:${kind.key}:$packageName", silent = true,
            )
        )
        return true
    }

    /** Takes back every mark on [kind] of [packageName]. */
    fun unacknowledge(context: Context, kind: GrantKind, packageName: String) {
        record(context).unacknowledge("${kind.key}|$packageName|")
    }

    // ── Installs and changes while the scanner runs ─────────────────────────

    private var watchedBy: Context? = null
    private var packageReceiver: BroadcastReceiver? = null
    /** The check on its way after an install, if one is; guarded by this object, like [changed]. */
    private var pending: Job? = null
    /** An install or change arrived since the pending check began. */
    private var changed = false

    /**
     * Checks again [PACKAGE_SETTLE_MS] after an app is installed, updated or has a component
     * switched on or off (how stalkerware hides its icon), for as long as [context] (the scanner
     * service) keeps it registered. Android delivers these only to a receiver registered at run time.
     */
    fun watchPackages(context: Context) {
        synchronized(this) {
            if (packageReceiver != null) return
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val pkg = intent?.data?.schemeSpecificPart ?: return
                    if (pkg == app.packageName) return
                    schedule(app)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
                .onSuccess { packageReceiver = receiver; watchedBy = context }
                .onFailure { Log.w(TAG, "could not watch for installs", it) }
        }
    }

    fun stopWatchingPackages(context: Context) {
        synchronized(this) {
            val receiver = packageReceiver ?: return
            runCatching { (watchedBy ?: context).unregisterReceiver(receiver) }
            packageReceiver = null
            watchedBy = null
            pending?.cancel()
            pending = null
        }
    }

    /**
     * Checks [PACKAGE_SETTLE_MS] after the first change of a burst; more changes in that time are
     * covered by the same check, and one that lands while it runs gets one more. A stream of
     * changes therefore cannot put the check off for ever.
     */
    private fun schedule(app: Context) {
        synchronized(this) {
            changed = true
            if (pending != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    delay(PACKAGE_SETTLE_MS)
                    synchronized(this@StalkwareWatch) { changed = false }
                    runCatching { check(app, foreground = false) }.onFailure { Log.w(TAG, "check after an install failed", it) }
                    val again = synchronized(this@StalkwareWatch) { changed.also { if (!it) pending = null } }
                    if (!again) break
                }
            }
            pending = job
            job.start()
        }
    }
}

/**
 * The stalkerware watch's record in a sealed [Store]: the findings of the last check (to tell
 * what is new) and the owner's "This is mine" marks with when each was made.
 */
internal class StalkwareRecord(private val store: Store) {

    private val lock = Any()

    private fun root(): JSONObject = store.json

    fun acknowledged(): Set<String> {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: return emptySet()
            return acks.keys().asSequence().toSet()
        }
    }

    fun acknowledge(key: String, at: Long) {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: JSONObject().also { root().put(ACKS, it) }
            acks.put(key, at)
            store.markDirty()
            store.flush()
        }
    }

    /** Removes every mark whose key starts with [prefix]. */
    fun unacknowledge(prefix: String) {
        synchronized(lock) {
            val acks = root().optJSONObject(ACKS) ?: return
            val gone = acks.keys().asSequence().filter { it.startsWith(prefix) }.toList()
            if (gone.isEmpty()) return
            gone.forEach { acks.remove(it) }
            store.markDirty()
            store.flush()
        }
    }

    /** The last check's findings, or null when there has been no check (or "Clear all data" since). */
    fun lastFindings(): Set<String>? {
        synchronized(lock) {
            val last = root().optJSONObject(LAST) ?: return null
            val keys = last.optJSONArray("keys") ?: return null
            return (0 until keys.length()).mapNotNullTo(HashSet()) { keys.optString(it).takeIf { k -> k.isNotEmpty() } }
        }
    }

    fun saveFindings(keys: Set<String>, at: Long) {
        synchronized(lock) {
            root().put(LAST, JSONObject().put("at", at).put("keys", JSONArray(keys.sorted())))
            store.markDirty()
            store.flush()
        }
    }

    fun clearFindings() {
        synchronized(lock) {
            if (root().remove(LAST) == null) return
            store.markDirty()
            store.flush()
        }
    }

    private companion object {
        const val ACKS = "acks"
        const val LAST = "last"
    }
}
