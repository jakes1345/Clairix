package app.clairix.analysis

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.clairix.HealthFact
import app.clairix.Severity
import java.util.Locale

/**
 * "Install unknown apps": which apps on this phone may install other apps from outside an app
 * store, and Phone check's row for it. Android 8 replaced the one global "Unknown sources" switch
 * with this per-app special access, so the old switch (Settings.Secure "install_non_market_apps")
 * no longer means anything. An app allowed it is how stalkerware is usually put on a phone by
 * someone holding it: a browser or a file manager installs the file it was handed.
 *
 * Android keeps the access as an app op per app, the one Settings → Apps → Special app access →
 * Install unknown apps switches, and any app can read another's mode with
 * [AppOpsManager.unsafeCheckOpNoThrow]: checking an op, unlike noting one, needs no permission of
 * its own, only that the other app be visible, which QUERY_ALL_PACKAGES
 * (src/suite/AndroidManifest.xml) provides. As in Settings, only apps that ask for
 * REQUEST_INSTALL_PACKAGES in their manifest are listed, and only MODE_ALLOWED counts: an app
 * never switched on reads MODE_DEFAULT, which the package installer treats as blocked.
 */
internal object UnknownSources {

    /**
     * One app that asks for the access and what Android answered for it: [mode] is its app-op
     * mode, null when the check failed. [store] is true for an app store ([StalkwareScanner.isAppStore]),
     * which needs the access to do what it is for.
     */
    data class Requester(val packageName: String, val label: String, val store: Boolean, val mode: Int?)

    /** The apps allowed to install others, how many could not be checked, and how many ask at all. */
    data class Reading(val allowed: List<Requester>, val unchecked: Int, val requesters: Int)

    /** Whether an app-op mode lets the app install apps: only an explicit allow does. */
    fun allows(mode: Int): Boolean = mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND

    /** The reading from what each app that asks for the access was answered. */
    fun judge(requesters: List<Requester>): Reading = Reading(
        allowed = requesters.filter { r -> r.mode?.let(::allows) == true },
        unchecked = requesters.count { it.mode == null },
        requesters = requesters.size
    )

    /**
     * Phone check's row. Nothing allowed, or only app stores, is in order; any other app allowed is
     * LOW, worth a look rather than an alarm, since a browser or file manager the owner used to
     * install something keeps the access until it is switched off. Null [reading], or one whose every
     * check failed, says it could not be read.
     */
    fun fact(reading: Reading?): HealthFact {
        if (reading == null || (reading.requesters > 0 && reading.unchecked == reading.requesters)) return HealthFact(
            ID, CATEGORY, LABEL, "Not readable",
            detail = "Android did not say which apps may install other apps. Look yourself: $WHERE."
        )
        val sorted = reading.allowed.sortedBy { it.label.lowercase(Locale.ROOT) }
        val stores = sorted.filter { it.store }
        val others = sorted.filterNot { it.store }
        val unchecked = if (reading.unchecked > 0) {
            " ${reading.unchecked} more ${if (reading.unchecked == 1) "app" else "apps"} could not be checked; $WHERE lists them all."
        } else ""
        if (others.isEmpty()) {
            if (stores.isEmpty()) return HealthFact(
                ID, CATEGORY, LABEL, "No app allowed",
                detail = if (unchecked.isEmpty()) null else "No app checked may install other apps.$unchecked"
            )
            return HealthFact(
                ID, CATEGORY, LABEL, "App stores only: ${names(stores)}",
                detail = "Only ${names(stores, max = stores.size)} may install other apps, which an app store needs to " +
                    "install what you choose in it.$unchecked"
            )
        }
        val them = if (others.size == 1) "it" else "them"
        return HealthFact(
            ID, CATEGORY, LABEL,
            "${others.size} ${if (others.size == 1) "app" else "apps"} allowed: ${names(others)}",
            Severity.LOW,
            "${names(others, max = others.size)} can install other apps from outside an app store, with no store " +
                "checking what is installed. That is how stalkerware is usually put on a phone by someone holding it: a " +
                "browser or file manager installs the file it was given. If you did not allow $them, or no longer need " +
                "$them to, switch it off: $WHERE." +
                (if (stores.isNotEmpty()) " ${names(stores, max = stores.size)} " +
                    (if (stores.size == 1) "is an app store, which needs the access." else "are app stores, which need the access.") else "") +
                unchecked,
            fixAction = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        )
    }

    private fun names(list: List<Requester>, max: Int = 2): String =
        list.take(max).joinToString(", ") { it.label } + if (list.size > max) ", …" else ""

    /**
     * Asks the phone. Null when it would not list its apps or has no app-op service. The apps that
     * ask for the access are listed again only when a package has changed since the last look
     * ([PackageManager.getChangedPackages]), since Phone check runs every thirty seconds; their
     * modes are read every time, so switching one off shows at once.
     */
    fun read(context: Context): Reading? {
        val app = context.applicationContext
        val pm = app.packageManager
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return null
        val op = AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES) ?: return null
        val self = app.packageName
        val asking = requesters(pm) ?: return null
        val checked = asking.filter { it.packageName != self }.map { r ->
            Requester(
                packageName = r.packageName,
                label = r.packageName,
                store = StalkwareScanner.isAppStore(r.packageName),
                mode = runCatching { ops.unsafeCheckOpNoThrow(op, r.uid, r.packageName) }.getOrNull()
            )
        }
        val reading = judge(checked)
        // Labels only for the apps the row names.
        return reading.copy(allowed = reading.allowed.map { it.copy(label = StalkwareScanner.appLabel(app, it.packageName)) })
    }

    /** An app that asks for REQUEST_INSTALL_PACKAGES, and the uid its app op is kept under. */
    private data class Asking(val packageName: String, val uid: Int)

    /** The apps that ask for the access, and the package sequence number they were listed at. */
    @Volatile private var cache: Pair<Int, List<Asking>>? = null

    private fun requesters(pm: PackageManager): List<Asking>? {
        val cached = cache
        val changed = runCatching { pm.getChangedPackages(cached?.first ?: 0) }.getOrNull()
        if (cached != null && changed == null) return cached.second
        val packages = runCatching { installedWithPermissions(pm) }.getOrNull() ?: return cached?.second
        val asking = packages.mapNotNull { p ->
            if (p.requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) != true) return@mapNotNull null
            p.applicationInfo?.uid?.let { Asking(p.packageName, it) }
        }
        cache = (changed?.sequenceNumber ?: cached?.first ?: 0) to asking
        return asking
    }

    private fun installedWithPermissions(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    private const val ID = "install_unknown"
    private const val CATEGORY = "Access"
    private const val LABEL = "Install unknown apps"
    private const val WHERE = "Settings → Apps → Special app access → Install unknown apps"
}
