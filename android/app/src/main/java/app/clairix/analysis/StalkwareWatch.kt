package app.clairix.analysis

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.clairix.Alert
import app.clairix.BuildConfig
import app.clairix.EventKind
import app.clairix.Registry
import app.clairix.Severity
import app.clairix.Store
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus
import app.clairix.security.PlatformAlerts
import app.clairix.security.UnlockLedger
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the stalkerware check running without anyone opening its page: once a day (from the
 * scanner's health cycle and whenever Clairix comes to the front, [checkIfDue]), whenever Clairix
 * comes to the front after an app was installed, updated or removed, at boot and after an update
 * (BootReceiver), and a few seconds after an app is installed, changed or removed while the
 * scanner runs ([watchPackages]). Each check is compared with what has already been raised, and
 * what is new, an app that now scores HIGH or a grant nobody vouches for, goes on the timeline
 * through [Registry.publishAlert] as HIGH platform alerts, with one notification per check at
 * most; the HIGH apps go into Phone check, so the hub's verdict includes them. A check that an
 * install started holds its notification until the screen has gone off and the phone has been
 * unlocked again ([HeldNotice]): whoever installed the app may still be holding the phone.
 *
 * Each check also compares the phone's paired Bluetooth devices with the last ones seen
 * ([BondWatch]), and a device paired while the scanner runs is raised as it happens; and an app
 * installed within half an hour of an unlock the unlock ledger puts in the owner's sleep window
 * ([UnlockLedger.sleepAnomalies]) is said to be, in the stalkerware alert or one of its own.
 *
 * The record (the last check's findings, the paired devices last seen, and the grants the owner
 * marked as theirs with "This is mine") is a sealed [Store] in the app's private files.
 */
object StalkwareWatch {

    private const val TAG = "StalkwareWatch"
    internal const val FILE = "stalkerware.json"
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

    /**
     * What [current] holds that has not been raised before, and the record of what has been raised
     * after it. [alerted] is null before the first check, whose findings are the baseline: all of
     * them are returned (to go on the timeline without a notification) and recorded. A key stays
     * raised while its app is [installed], even if it drops out of a check and comes back (an app
     * whose hidden-service signal leaves the week's window and returns after a reboot); a mark
     * takes it out ([StalkwareRecord.unalert]), and so does uninstalling the app.
     */
    internal fun newFindings(current: Set<String>, alerted: Set<String>?, installed: (String) -> Boolean): Pair<Set<String>, Set<String>> {
        if (alerted == null) return current to current
        val kept = alerted.filterTo(HashSet()) { it in current || installed(packageOf(it)) }
        return (current - kept) to (kept + current)
    }

    /** The package a finding key ([findings]) is about. */
    internal fun packageOf(key: String): String = key.substringAfter(':')

    /**
     * The one notification for a check's [alerts], each of which went on the timeline without
     * its own: the alert itself when there is one, otherwise one that carries them all.
     */
    internal fun notice(alerts: List<Alert>, now: Long): Alert? {
        val first = alerts.firstOrNull() ?: return null
        if (alerts.size == 1) return first.copy(silent = false)
        return Alert(
            id = "stalkerware_notice@$now", ts = now,
            severity = alerts.maxOf { it.severity },
            title = alerts.joinToString("; ") { it.title },
            detail = alerts.joinToString("\n\n") { it.detail },
            kind = EventKind.PLATFORM,
            dedupeKey = "stalkerware_notice:" + alerts.mapNotNull { it.dedupeKey }.sorted().joinToString("|"),
        )
    }

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
    internal fun alerts(
        new: Set<String>,
        result: StalkwareScanner.Result,
        label: (String) -> String,
        now: Long,
        silent: Boolean,
        notes: Map<String, String> = emptyMap(),
    ): List<Alert> {
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
            val detail = apps.joinToString("\n") { a ->
                "${a.appLabel} (${a.packageName}): ${a.reasons.joinToString("; ")}." + (notes[a.packageName]?.let { " It was $it." } ?: "")
            } +
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

    // ── Installs after an unlock while the owner slept ──────────────────────

    /** How soon after an unlock in the sleep window an install is taken to have come from it. */
    internal const val AFTER_UNLOCK_MS = 30 * 60_000L

    /**
     * The apps of [installs] (package to first-install time) installed since the check at
     * [lastCheckAt]: none before the first check, whose apps are the baseline, and never [self].
     */
    internal fun newInstalls(installs: Map<String, Long>, lastCheckAt: Long?, self: String): Map<String, Long> {
        if (lastCheckAt == null) return emptyMap()
        return installs.filter { (pkg, at) -> pkg != self && at > lastCheckAt }
    }

    /** The latest of [unlocks] at most [AFTER_UNLOCK_MS] before [installedAt], or null. */
    internal fun unlockBefore(installedAt: Long, unlocks: List<Long>): Long? =
        unlocks.filter { it <= installedAt && installedAt - it <= AFTER_UNLOCK_MS }.maxOrNull()

    /**
     * For each of [installs] that came within [AFTER_UNLOCK_MS] of one of [unlocks] (unlocks the
     * unlock ledger put in the owner's sleep window), what to say about it: when it was installed
     * and how long after that unlock. Apps with no such unlock are left out.
     */
    internal fun sleepInstallNotes(installs: Map<String, Long>, unlocks: List<Long>, zone: TimeZone = TimeZone.getDefault()): Map<String, String> {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }
        val out = LinkedHashMap<String, String>()
        for ((pkg, at) in installs.entries.sortedBy { it.value }) {
            val unlock = unlockBefore(at, unlocks) ?: continue
            val minutes = (at - unlock) / 60_000L
            val gap = when (minutes) {
                0L -> "less than a minute"
                1L -> "1 minute"
                else -> "$minutes minutes"
            }
            out[pkg] = "installed at ${time.format(Date(at))}, $gap after the phone was unlocked inside your sleep window (at ${time.format(Date(unlock))})"
        }
        return out
    }

    /**
     * The alert for apps installed soon after an unlock while the owner slept ([notes], from
     * [sleepInstallNotes]), other than those [alreadySaid] in this check's stalkerware alert, which
     * carries the same words. An unlock-ledger anomaly: the safety card goes with it.
     */
    internal fun sleepInstallAlert(notes: Map<String, String>, alreadySaid: Set<String>, label: (String) -> String, now: Long, silent: Boolean): Alert? {
        val rest = notes.filterKeys { it !in alreadySaid }
        if (rest.isEmpty()) return null
        val title = rest.keys.singleOrNull()?.let { "${label(it)} was installed after an unlock while you slept" }
            ?: "${rest.size} apps were installed after an unlock while you slept"
        val detail = rest.entries.joinToString("\n") { (pkg, note) -> "${label(pkg)} ($pkg) was $note." } +
            "\nIf that was not you, someone used your phone while you slept and installed it. Save its evidence in " +
            "Phone check → Stalkerware before you remove anything, and change your screen lock."
        return Alert(
            id = "install_after_sleep@$now", ts = now, severity = Severity.HIGH, title = title, detail = detail,
            kind = EventKind.UNLOCK_ANOMALY, dedupeKey = "install_after_sleep:" + rest.keys.sorted().joinToString(","),
            silent = silent,
        )
    }

    /** [sleepInstallNotes] for [installs], from the unlock ledger; empty when it holds nothing in the sleep window. */
    private fun sleepInstallNotes(app: Context, installs: Map<String, Long>): Map<String, String> {
        if (installs.isEmpty()) return emptyMap()
        return runCatching {
            UnlockLedger.init(app)
            val since = installs.values.min() - AFTER_UNLOCK_MS
            sleepInstallNotes(installs, UnlockLedger.sleepAnomalies(since).map { it.ts })
        }.onFailure { Log.w(TAG, "could not read the unlock ledger", it) }.getOrDefault(emptyMap())
    }

    // ── Bluetooth pairings ──────────────────────────────────────────────────

    /**
     * Compares the phone's paired devices with the last ones seen and keeps the new list; the
     * alert for any paired since ([BondWatch]), or null. Nothing when they cannot be read, and
     * nothing but the list the first time.
     */
    private fun checkBonds(app: Context, rec: StalkwareRecord, now: Long, silent: Boolean): Alert? {
        val current = BondWatch.read(app) ?: return null
        val known = rec.bonds()
        val added = BondWatch.newBonds(current, known)
        rec.saveBonds(BondWatch.snapshot(current))
        return BondWatch.alert(added, now, silent)
    }

    /**
     * A device was paired while the scanner runs: raised now unless it was already known, with its
     * notification held like an install's ([HeldNotice]), since whoever paired it is holding the phone.
     */
    private suspend fun onBonded(app: Context, bond: BondWatch.Bond) {
        val alert = withContext(Dispatchers.IO) {
            mutex.withLock {
                val rec = record(app)
                val known = rec.bonds()
                if (known != null && BondWatch.newBonds(listOf(bond), known).isEmpty()) return@withLock null
                val base = known ?: BondWatch.read(app)?.let(BondWatch::snapshot) ?: emptySet()
                rec.saveBonds(base + BondWatch.snapshot(listOf(bond)))
                BondWatch.alert(listOf(bond), System.currentTimeMillis(), silent = true)
            }
        } ?: return
        Registry.publishAlert(alert)
        notice(listOf(alert), alert.ts)?.let { heldNotice.hold(it, screenOn = isInteractive(app)) }
    }

    // ── Running a check ─────────────────────────────────────────────────────

    /**
     * Scans now, compares the result with what has been raised before and raises what is new, then
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

    /**
     * [check], unless one ran within [CHECK_EVERY_MS] in this process. From a screen
     * ([foreground]) it also checks when an app was installed, updated or removed since the last
     * check, as Android counts them (PackageManager.getChangedPackages, which needs no receiver, so
     * this holds with the scanner off too). A caller that ran the check, and every call from a
     * screen, then asks for a newer stalkerware list ([StalkwareIoc.refreshIfStale] keeps that to
     * about once a day), so the scanner's daily check refreshes it as well.
     */
    suspend fun checkIfDue(context: Context, foreground: Boolean) {
        if (!BuildConfig.FULL_ACCESS) return
        val app = context.applicationContext
        val ran = withContext(Dispatchers.IO) {
            if (!dueNow(app, foreground)) return@withContext false
            mutex.withLock {
                // Another caller may have checked while this one waited.
                if (dueNow(app, foreground)) {
                    checkLocked(app, foreground)
                    true
                } else {
                    false
                }
            }
        }
        if (ran || foreground) refreshListThenRecheck(app, foreground)
    }

    private fun dueNow(app: Context, foreground: Boolean): Boolean {
        val last = StalkwareScanner.results.value?.scannedAt
        val changed = if (foreground && last != null) changedPackages(app) else null
        return dueOnResume(last, System.currentTimeMillis(), changed, app.packageName)
    }

    internal fun due(lastScannedAt: Long?, now: Long): Boolean =
        lastScannedAt == null || now - lastScannedAt >= CHECK_EVERY_MS || now < lastScannedAt

    /**
     * [due], or an app other than Clairix ([self]) was installed, updated or removed since the
     * last check ([changed], from PackageManager.getChangedPackages; null when nothing changed or
     * it was not asked).
     */
    internal fun dueOnResume(lastScannedAt: Long?, now: Long, changed: List<String>?, self: String): Boolean =
        due(lastScannedAt, now) || changed.orEmpty().any { it != self }

    /** Android's count of package changes when the last check in this process began; -1 before one. */
    @Volatile private var checkedAtSequence = -1

    /** Null from getChangedPackages means nothing changed since boot, which is count 0. */
    private fun packageSequence(app: Context): Int? =
        runCatching { app.packageManager.getChangedPackages(0)?.sequenceNumber ?: 0 }.getOrNull()

    /** The packages changed since the last check in this process; null when none did or that is unknown. */
    private fun changedPackages(app: Context): List<String>? {
        val since = checkedAtSequence
        if (since < 0) return null
        return runCatching { app.packageManager.getChangedPackages(since)?.packageNames }.getOrNull()
    }

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

    /**
     * One check. Every alert goes on the timeline without a notification of its own, and one
     * notification ([notice]) follows unless the check is [foreground] or the first: at once, or,
     * for a check an install started ([held]), once the screen has gone off and the phone has been
     * unlocked again ([HeldNotice]).
     */
    private fun checkLocked(app: Context, foreground: Boolean, held: Boolean = false): StalkwareScanner.Result {
        val rec = record(app)
        val lastCheckAt = rec.lastCheckedAt()
        val sequence = packageSequence(app)
        val result = StalkwareScanner.scan(app, rec.acknowledged(), StalkwareIoc.current(app))
        sequence?.let { checkedAtSequence = it }
        val now = result.scannedAt
        val current = findings(result)
        val alerted = rec.alerted()
        val (new, raised) = newFindings(current, alerted) { pkg -> installed(app, pkg) }
        rec.saveFindings(current, now)
        rec.saveAlerted(raised)
        Registry.updatePhoneHealth { SpywareHealth.merge(it, SpywareHealth.appFindings(result), now) }
        val labels = HashMap<String, String>()
        val label = { pkg: String -> labels.getOrPut(pkg) { StalkwareScanner.appLabel(app, pkg) } }
        val notes = sleepInstallNotes(app, newInstalls(result.installs, lastCheckAt, app.packageName))
        val stalkerware = alerts(new, result, label, now, silent = true, notes = notes)
        val inStalkerwareAlert = result.high.filter { "app:${it.packageName}" in new }.mapTo(HashSet()) { it.packageName }
        val raisedNow = stalkerware +
            listOfNotNull(sleepInstallAlert(notes, inStalkerwareAlert, label, now, silent = true)) +
            listOfNotNull(runCatching { checkBonds(app, rec, now, silent = true) }.onFailure { Log.w(TAG, "could not check pairings", it) }.getOrNull())
        raisedNow.forEach { Registry.publishAlert(it) }
        if (!foreground && alerted != null) {
            notice(raisedNow, now)?.let { n ->
                if (held) heldNotice.hold(n, screenOn = isInteractive(app)) else runCatching { PlatformAlerts.notify(app, n) }
            }
        }
        return result
    }

    private fun installed(app: Context, pkg: String): Boolean =
        runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun isInteractive(app: Context): Boolean =
        app.getSystemService(PowerManager::class.java)?.isInteractive ?: true

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

    /**
     * Forgets the last check's findings, what was raised and the paired devices last seen ("Clear
     * all data"); the owner's marks stay, like the trusted devices.
     */
    fun clear(context: Context) {
        record(context).clearFindings()
        heldNotice.drop()
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
        // Should the mark be undone, the grant is raised again as new.
        record(app).unalert(StalkwareScanner.grantKey(kind, packageName))
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
    private var screenReceiver: BroadcastReceiver? = null
    private var bondReceiver: BroadcastReceiver? = null
    /** The notification of a check an install started, until the phone has been put down and unlocked again. */
    private val heldNotice = HeldNotice()
    /** The check on its way after an install, if one is; guarded by this object, like [changed]. */
    private var pending: Job? = null
    /** An install or change arrived since the pending check began. */
    private var changed = false

    /**
     * Checks again [PACKAGE_SETTLE_MS] after an app is installed, updated, removed or has a
     * component switched on or off (how stalkerware hides its icon), for as long as [context] (the
     * scanner service) keeps it registered. Android delivers these only to a receiver registered at
     * run time. The screen going off and the phone being unlocked are watched alongside, to post a
     * held notification ([HeldNotice]).
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
                // Sent once an app is gone, not for the removal half of an update.
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
            val screen = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> heldNotice.screenOff()
                        Intent.ACTION_USER_PRESENT ->
                            heldNotice.userPresent()?.let { n -> runCatching { PlatformAlerts.notify(app, n) } }
                    }
                }
            }
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
                .onSuccess { packageReceiver = receiver; watchedBy = context }
                .onFailure { Log.w(TAG, "could not watch for installs", it) }
            runCatching { ContextCompat.registerReceiver(context, screen, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED) }
                .onSuccess { screenReceiver = screen }
                .onFailure { Log.w(TAG, "could not watch the screen", it) }
            // A device paired while the scanner runs ([BondWatch]). Android delivers this protected
            // broadcast to a receiver registered at run time, and only while BLUETOOTH_CONNECT is held.
            val bonds = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) != BluetoothDevice.BOND_BONDED) return
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val bond = BondWatch.bond(app, device)
                    scope.launch { runCatching { onBonded(app, bond) }.onFailure { Log.w(TAG, "could not record a pairing", it) } }
                }
            }
            runCatching {
                ContextCompat.registerReceiver(
                    context, bonds, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
                .onSuccess { bondReceiver = bonds }
                .onFailure { Log.w(TAG, "could not watch pairings", it) }
        }
    }

    /**
     * Stops watching. A notification still held stays held, and is posted at the first unlock after
     * the screen goes off once watching starts again; the alerts it carries are on the timeline.
     */
    fun stopWatchingPackages(context: Context) {
        synchronized(this) {
            val receiver = packageReceiver ?: return
            val owner = watchedBy ?: context
            runCatching { owner.unregisterReceiver(receiver) }
            screenReceiver?.let { r -> runCatching { owner.unregisterReceiver(r) } }
            bondReceiver?.let { r -> runCatching { owner.unregisterReceiver(r) } }
            packageReceiver = null
            screenReceiver = null
            bondReceiver = null
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
                    runCatching { checkAfterInstall(app) }.onFailure { Log.w(TAG, "check after an install failed", it) }
                    val again = synchronized(this@StalkwareWatch) { changed.also { if (!it) pending = null } }
                    if (!again) break
                }
            }
            pending = job
            job.start()
        }
    }

    /** A check an install, update or removal started: its notification is held ([HeldNotice]). */
    private suspend fun checkAfterInstall(app: Context) {
        withContext(Dispatchers.IO) { mutex.withLock { checkLocked(app, foreground = false, held = true) } }
    }
}

/**
 * The notification of a check an install started, held until the phone has been put down and
 * picked up again: the screen has gone off ([screenOff]) after the last [hold], and the phone has
 * then been unlocked ([userPresent]). Whoever installed or hid an app may still be holding the
 * phone a few seconds later, and a heads-up alarm then would tell them Clairix is there and saw it.
 * The alerts are on the timeline and the hub at once; only the notification waits. Checks that
 * land while one is held are carried in the same notification.
 */
internal class HeldNotice {

    private val held = ArrayList<Alert>()
    private var screenWentOff = false

    /** Holds [notice]. With the screen on it waits for the screen to go off first, even if it had before. */
    @Synchronized
    fun hold(notice: Alert, screenOn: Boolean) {
        held += notice
        screenWentOff = !screenOn
    }

    @Synchronized
    fun screenOff() {
        if (held.isNotEmpty()) screenWentOff = true
    }

    /** The one notification to post now the phone is unlocked; null when nothing is held or the screen has not gone off since. */
    @Synchronized
    fun userPresent(): Alert? {
        if (held.isEmpty() || !screenWentOff) return null
        val out = StalkwareWatch.notice(held.toList(), held.last().ts)
        drop()
        return out
    }

    @Synchronized
    fun drop() {
        held.clear()
        screenWentOff = false
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

    /** When the last check ran, or null before the first (or after "Clear all data"). */
    fun lastCheckedAt(): Long? {
        synchronized(lock) {
            return root().optJSONObject(LAST)?.optLong("at", 0L)?.takeIf { it > 0L }
        }
    }

    /** The paired devices' addresses at the last look ([BondWatch]), or null before the first. */
    fun bonds(): Set<String>? {
        synchronized(lock) {
            val keys = root().optJSONArray(BONDS) ?: return null
            return (0 until keys.length()).mapNotNullTo(HashSet()) { keys.optString(it).takeIf { k -> k.isNotEmpty() } }
        }
    }

    fun saveBonds(addresses: Set<String>) {
        synchronized(lock) {
            root().put(BONDS, JSONArray(addresses.sorted()))
            store.markDirty()
            store.flush()
        }
    }

    fun saveFindings(keys: Set<String>, at: Long) {
        synchronized(lock) {
            root().put(LAST, JSONObject().put("at", at).put("keys", JSONArray(keys.sorted())))
            store.markDirty()
            store.flush()
        }
    }

    /**
     * Every finding already raised ([StalkwareWatch.newFindings]), or null before the first check.
     * A record written before this was kept reads its last check's findings as the ones raised.
     */
    fun alerted(): Set<String>? {
        synchronized(lock) {
            val keys = root().optJSONArray(ALERTED) ?: return lastFindings()
            return (0 until keys.length()).mapNotNullTo(HashSet()) { keys.optString(it).takeIf { k -> k.isNotEmpty() } }
        }
    }

    fun saveAlerted(keys: Set<String>) {
        synchronized(lock) {
            root().put(ALERTED, JSONArray(keys.sorted()))
            store.markDirty()
            store.flush()
        }
    }

    /** Forgets that [key] was raised, so it is raised again should it come back. */
    fun unalert(key: String) {
        synchronized(lock) {
            val keys = root().optJSONArray(ALERTED) ?: return
            val kept = (0 until keys.length()).map { keys.optString(it) }.filter { it.isNotEmpty() && it != key }
            if (kept.size == keys.length()) return
            root().put(ALERTED, JSONArray(kept))
            store.markDirty()
            store.flush()
        }
    }

    fun clearFindings() {
        synchronized(lock) {
            val last = root().remove(LAST)
            val alerted = root().remove(ALERTED)
            val bonds = root().remove(BONDS)
            if (last == null && alerted == null && bonds == null) return
            store.markDirty()
            store.flush()
        }
    }

    private companion object {
        const val ACKS = "acks"
        const val LAST = "last"
        const val ALERTED = "alerted"
        const val BONDS = "bonds"
    }
}
