package app.clairix

import app.clairix.ui.components.ClairixProgressBar
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.AudioRouteMonitor
import app.clairix.analysis.CaAudit
import app.clairix.analysis.CaAuditScanner
import app.clairix.analysis.CaPage
import app.clairix.analysis.StalkwareScanner
import app.clairix.analysis.StalkwareWatch
import app.clairix.analysis.TlsCanary
import app.clairix.analysis.TlsCanaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.SafetyFirst
import app.clairix.ui.SafetyFirstCard

// The Phone check category's on-demand pages: the stalkerware scan and the
// certificate and TLS audit, and the Device page's headsets and microphones.

// ── 2. Stalkerware ─────────────────────────────────────────────────────────────

@Composable
internal fun StalkerwarePage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val published by StalkwareScanner.results.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<StalkwareScanner.Result?>(published) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usageAccess by remember { mutableStateOf(StalkwareScanner.hasUsageAccess(context)) }
    /** The grant waiting for "This is mine" to be confirmed. */
    var confirmMine by remember { mutableStateOf<Pair<StalkwareScanner.GrantKind, String>?>(null) }

    fun runScan() {
        if (scanning) return
        scanning = true
        error = null
        scope.launch {
            // Through the watch, so the hub, the timeline and the next background check see it too.
            val r = runCatching { StalkwareWatch.check(context, foreground = true, refreshList = true) }
            r.onSuccess { result = it }.onFailure { error = "The scan failed: ${it.javaClass.simpleName}: ${it.message}" }
            scanning = false
        }
    }

    /** Marks or unmarks a grant as the owner's, then checks again so every screen agrees. */
    fun mine(kind: StalkwareScanner.GrantKind, packageName: String, yes: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (yes) StalkwareWatch.acknowledge(context, kind, packageName)
                else { StalkwareWatch.unacknowledge(context, kind, packageName); true }
            }
            if (!ok) error = "Could not read who signed $packageName, so it cannot be marked."
            runScan()
        }
    }

    confirmMine?.let { (kind, pkg) ->
        val enrolled = result?.status(kind, pkg) == StalkwareScanner.GrantStatus.CONFIRM
        MarkAsMineDialog(
            kind, StalkwareScanner.appLabel(context, pkg), enrolled,
            onConfirm = { confirmMine = null; mine(kind, pkg, true) },
            onDismiss = { confirmMine = null }
        )
    }
    val onMine: (StalkwareScanner.GrantKind, String, Boolean) -> Unit = { kind, pkg, yes ->
        if (yes) confirmMine = kind to pkg else mine(kind, pkg, false)
    }

    LaunchedEffect(Unit) { if (result == null) runScan() }
    // A check that ran in the background meanwhile (a newer list, an install) is shown too.
    LaunchedEffect(published) {
        val p = published ?: return@LaunchedEffect
        if (p.scannedAt > (result?.scannedAt ?: 0L)) result = p
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Usage access is granted on Android's own page, so it is re-read on return.
        usageAccess = StalkwareScanner.hasUsageAccess(context)
    }

    ToolPageList(title = "Stalkerware", onBack = onBack) {
        if (!usageAccess) {
            item(key = "§usage") {
                ClairixCard {
                    Text("Usage access not granted", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.caution)
                    Text(
                        "With usage access Clairix can also see which apps run a hidden foreground service " +
                        "without ever being opened — the strongest single sign of stalkerware. Without it " +
                        "that signal is skipped.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                    SecondaryButton("Grant usage access", onClick = { openExternal(context, StalkwareScanner.usageAccessIntent()) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (scanning) {
            item(key = "§progress") { LoadingBlock("Scanning installed apps…") }
        } else {
            item(key = "§rescan") { SecondaryButton("Scan again", onClick = { runScan() }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth()) }
        }

        error?.let { e -> item(key = "§error") { Notice(Tone.Danger, e, title = "Error") } }

        val r = result
        if (r != null) {
            item(key = "§summary") {
                ClairixCard(spacing = 4.dp) {
                    KeyValueRow("Scanned", tFull(r.scannedAt))
                    KeyValueRow("Flagged", "${r.high.size} high · ${r.medium.size} medium")
                    KeyValueRow("Notification access", "${r.notificationListeners.size} ${if (r.notificationListeners.size == 1) "app" else "apps"} (${unknownCount(r, StalkwareScanner.GrantKind.NOTIFICATION_LISTENER, r.notificationListeners)} unknown)")
                    KeyValueRow("Accessibility services", "${r.accessibilityServices.size} (${unknownCount(r, StalkwareScanner.GrantKind.ACCESSIBILITY, r.accessibilityServices)} unknown)")
                    KeyValueRow("VPN-capable apps", "${r.vpnApps.size}")
                    KeyValueRow("Keyboards", "${r.enabledImes.size} enabled (${r.suspiciousImes.size} unknown)")
                    val unknownAdmins = r.activeDeviceAdmins.count { it !in r.vouchedAdmins }
                    KeyValueRow("Active device admins", "${r.activeDeviceAdmins.size} ($unknownAdmins unknown)")
                    if (r.owners.isNotEmpty()) KeyValueRow("Managed by", r.owners.joinToString(", ") { StalkwareScanner.appLabel(context, it.packageName) }, mono = false)
                    KeyValueRow("Other profiles", if (r.otherProfiles == 0) "None visible" else "${r.otherProfiles}, not scanned", mono = false)
                    if (r.listApps > 0) KeyValueRow("Stalkerware list", "${r.listApps} apps · issued ${tDay(r.listIssued * 1000)}", mono = false)
                }
            }
            managedBySection(r, context, onMine)
            if (r.high.isEmpty() && r.medium.isEmpty()) {
                item(key = "§clean") {
                    ClairixCard {
                        Text("No suspicious apps found", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.safe)
                        Text(
                            "Nothing installed scores high enough to worry about. A clean result is not " +
                            "proof: stalkerware sold as a 'parental control' app can look like a store install.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                        Text(
                            profilesCaveat(r),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            }
            // Removing any of these can tell whoever installed it that it was found, so
            // the safety card leads the findings; its export saves each app's evidence
            // to the timeline first, so the report carries them.
            if (SafetyFirst.forStalkerware(r)) {
                item(key = "§safety-first") {
                    SafetyFirstCard(
                        title = SafetyFirst.TITLE_APP,
                        onExport = {
                            scope.launch {
                                withContext(Dispatchers.IO) { saveFlaggedEvidence(context, r) }
                                EvidenceExport.request()
                            }
                        }
                    )
                }
            }
            if (r.high.isNotEmpty()) {
                item(key = "§high-hdr") { SectionHeader("High") }
                items(r.high, key = { "high|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            if (r.medium.isNotEmpty()) {
                item(key = "§medium-hdr") { SectionHeader("Medium") }
                items(r.medium, key = { "medium|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            // Every enabled keyboard, the known ones marked as such.
            if (r.enabledImes.isNotEmpty()) {
                item(key = "§ime-hdr") { SectionHeader("Keyboards") }
                if (r.suspiciousImes.isNotEmpty()) item(key = "§ime-note") {
                    Notice(
                        Tone.Caution,
                        "An enabled keyboard sees everything typed while it is in use, passwords and " +
                            "messages included. If you did not install it yourself, switch it off in " +
                            "keyboard settings, then uninstall it.",
                        title = "Unknown keyboard"
                    )
                }
                items(r.enabledImes, key = { "ime|$it" }) { pkg ->
                    GrantRow(r, StalkwareScanner.GrantKind.KEYBOARD, pkg, onMine) {
                        GrantCard(
                            packageName = pkg,
                            flagged = r.apps.firstOrNull { it.packageName == pkg },
                            context = context,
                            actionLabel = "Keyboard settings",
                            action = StalkwareScanner.keyboardSettingsIntent(),
                            known = r.status(StalkwareScanner.GrantKind.KEYBOARD, pkg) == StalkwareScanner.GrantStatus.KNOWN,
                        )
                    }
                }
            }
            if (r.activeDeviceAdmins.isNotEmpty()) {
                item(key = "§admin-hdr") { SectionHeader("Device admins") }
                item(key = "§admin-note") {
                    Notice(
                        Tone.Caution,
                        "A device admin can stop its own uninstall and may lock or wipe the phone. Work " +
                            "profiles and Find My Device use it legitimately. To remove one you do not " +
                            "recognise, switch it off under Settings → Security → Device admin apps, then uninstall it.",
                        title = "Active device admin"
                    )
                }
                items(r.activeDeviceAdmins, key = { "admin|$it" }) { pkg ->
                    GrantRow(r, StalkwareScanner.GrantKind.DEVICE_ADMIN, pkg, onMine) {
                        GrantCard(
                            packageName = pkg,
                            flagged = r.apps.firstOrNull { it.packageName == pkg },
                            context = context,
                            actionLabel = "Open in Settings",
                            action = StalkwareScanner.appDetailsIntent(pkg),
                            // A known name alone is not enough: the scanner vouches only for one from a store or the system image.
                            known = pkg in r.vouchedAdmins,
                        )
                    }
                }
            }
            accessSections(r, context, onMine)
        }
    }
}

/** Grants among [packages] of [kind] that need a look: unknown, or a management app not yet confirmed. */
private fun unknownCount(r: StalkwareScanner.Result, kind: StalkwareScanner.GrantKind, packages: List<String>): Int =
    packages.count { r.status(kind, it).let { s -> s == StalkwareScanner.GrantStatus.UNKNOWN || s == StalkwareScanner.GrantStatus.CONFIRM } }

/** What the check did not look at: apps in any other profile. */
private fun profilesCaveat(r: StalkwareScanner.Result): String = buildString {
    r.ownProfile?.let { append("Clairix is installed in $it, so none of the phone's main apps were checked. ") }
    append(
        "Only apps in the profile Clairix runs in were checked: apps in a work profile, Secure Folder, " +
            "Private space or a second space were not."
    )
    if (r.otherProfiles > 0) {
        append(" This phone has ${r.otherProfiles} other ${if (r.otherProfiles == 1) "profile" else "profiles"}; look through its apps yourself.")
    }
    if (r.secureFolder) append(" Secure Folder is installed on this phone.")
}

/**
 * The device owner or profile owner, first after the summary: it can do more than any other app,
 * whatever its name (Family Link runs as Google Play services), so it is HIGH until the owner
 * confirms they enrolled the phone themselves.
 */
private fun LazyListScope.managedBySection(
    r: StalkwareScanner.Result,
    context: Context,
    onMine: (StalkwareScanner.GrantKind, String, Boolean) -> Unit,
) {
    if (r.owners.isEmpty()) return
    val confirmed = r.owners.all { r.status(it.kind, it.packageName) == StalkwareScanner.GrantStatus.YOURS }
    item(key = "§owner-hdr") { SectionHeader("Managed by", tone = if (confirmed) Tone.Accent else Tone.Danger) }
    items(r.owners, key = { "owner|${it.packageName}" }) { owner ->
        val label = remember(owner.packageName) { StalkwareScanner.appLabel(context, owner.packageName) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r.status(owner.kind, owner.packageName) == StalkwareScanner.GrantStatus.YOURS) {
                Notice(
                    Tone.Info,
                    "You confirmed you enrolled this phone with $label. It can still install and remove apps, hide apps, " +
                        "set policies and install certificates.",
                    title = "Managed by $label"
                )
            } else {
                Notice(
                    Tone.Danger,
                    (if (owner.deviceOwner) "$label is this phone's device owner. "
                    else "$label owns the profile Clairix runs in, as Family Link supervision and some work set-ups do. ") +
                        "An owner can install and remove apps without asking, hide apps, set location and other policies, " +
                        "and install certificates that let it read encrypted traffic, and Settings does not let you switch it " +
                        "off. Did you enrol this phone yourself (a work phone, or Family Link you set up)? If you did, confirm " +
                        "it below. If you did not, someone else enrolled it.",
                    title = "This phone is managed by $label"
                )
            }
            GrantRow(r, owner.kind, owner.packageName, onMine) {
                GrantCard(
                    packageName = owner.packageName,
                    flagged = r.apps.firstOrNull { it.packageName == owner.packageName },
                    context = context,
                    actionLabel = "Open in Settings",
                    action = StalkwareScanner.appDetailsIntent(owner.packageName),
                )
            }
        }
    }
}

/**
 * Every app holding notification access or an accessibility service, and every app that can act
 * as the VPN, the known ones included and marked, each with the Settings page that switches it off.
 */
private fun LazyListScope.accessSections(
    r: StalkwareScanner.Result,
    context: Context,
    onMine: (StalkwareScanner.GrantKind, String, Boolean) -> Unit,
) {
    fun grantList(kind: StalkwareScanner.GrantKind, title: String, note: String, packages: List<String>, settings: String, settingsLabel: String) {
        if (packages.isEmpty()) return
        item(key = "§${kind.key}-hdr") { SectionHeader(title, supporting = "${packages.size}") }
        item(key = "§${kind.key}-note") {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle, modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(packages, key = { "${kind.key}|$it" }) { pkg ->
            GrantRow(r, kind, pkg, onMine) {
                GrantCard(
                    packageName = pkg,
                    flagged = r.apps.firstOrNull { it.packageName == pkg },
                    context = context,
                    actionLabel = settingsLabel,
                    action = Intent(settings),
                    known = r.status(kind, pkg) == StalkwareScanner.GrantStatus.KNOWN,
                )
            }
        }
    }

    grantList(
        StalkwareScanner.GrantKind.ACCESSIBILITY, "Accessibility services",
        "An accessibility service reads every screen and can tap for you. Screen readers, password managers and " +
            "automation apps use one; so does almost every stalkerware app.",
        r.accessibilityServices, Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings"
    )
    grantList(
        StalkwareScanner.GrantKind.NOTIFICATION_LISTENER, "Notification access",
        "These apps read every notification as it arrives, one-time codes and message previews included. Watches, " +
            "car systems and messaging apps use it.",
        r.notificationListeners, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Notification access settings"
    )
    if (r.vpnApps.isNotEmpty()) {
        item(key = "§vpn-hdr") { SectionHeader("Apps that can be the VPN", supporting = "${r.vpnApps.size}") }
        item(key = "§vpn-note") {
            Text(
                "Once allowed, any of these can see all of the phone's traffic. Being able to is not the same as being on: " +
                    "the VPN settings page shows which one is connected or always on.",
                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        items(r.vpnApps, key = { "vpn|$it" }) { pkg ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GrantCard(
                    packageName = pkg,
                    flagged = r.apps.firstOrNull { it.packageName == pkg },
                    context = context,
                    actionLabel = "VPN settings",
                    action = Intent(Settings.ACTION_VPN_SETTINGS),
                )
                if (pkg in r.preloadedVpns) {
                    Text(
                        "Part of the phone's own system.",
                        style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
    // The clean card carries this when nothing is flagged; otherwise it is said here.
    if (r.high.isNotEmpty() || r.medium.isNotEmpty()) {
        item(key = "§profiles") {
            Notice(Tone.Neutral, profilesCaveat(r), title = "Not checked")
        }
    }
}

/**
 * A grant's card with what the owner can say about it: "This is mine" (or "I enrolled this phone
 * myself" for a management app) on one nobody vouches for, and an undo on one marked. Never on an
 * app on the stalkerware list, which no mark clears.
 */
@Composable
private fun GrantRow(
    r: StalkwareScanner.Result,
    kind: StalkwareScanner.GrantKind,
    packageName: String,
    onMine: (StalkwareScanner.GrantKind, String, Boolean) -> Unit,
    card: @Composable () -> Unit,
) {
    val status = r.status(kind, packageName)
    val listed = r.apps.any { it.packageName == packageName && it.knownAs != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        card()
        when {
            status == StalkwareScanner.GrantStatus.YOURS -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "You marked this ${kind.label} as yours.",
                    style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Undo", onClick = { onMine(kind, packageName, false) }, tone = Tone.Neutral)
            }
            !listed && (status == StalkwareScanner.GrantStatus.UNKNOWN || status == StalkwareScanner.GrantStatus.CONFIRM) -> GhostButton(
                if (status == StalkwareScanner.GrantStatus.CONFIRM) "I enrolled this phone myself" else "This is mine",
                onClick = { onMine(kind, packageName, true) },
                tone = Tone.Neutral
            )
        }
    }
}

/** Asks before a grant is marked as the owner's: the mark stops it counting as an issue. */
@Composable
private fun MarkAsMineDialog(
    kind: StalkwareScanner.GrantKind,
    label: String,
    enrolled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ClairixShapes.Sheet,
        containerColor = ClairixTheme.colors.surfaceRaised,
        title = {
            Text(
                if (enrolled) "Did you enrol this phone?" else "Is $label yours?",
                style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text
            )
        },
        text = {
            Text(
                (if (enrolled) "Only if you set this phone up with $label yourself: a work or school phone, or Family Link you set up. "
                else "Only if you installed $label and switched on its ${kind.label} yourself. ") +
                    "Clairix then lists it as yours and stops counting it as an issue. If someone else set it up, leave it. " +
                    "The mark goes on the timeline and can be undone here.",
                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
            )
        },
        confirmButton = { GhostButton(if (enrolled) "I enrolled it" else "It is mine", onClick = onConfirm, tone = Tone.Accent) },
        dismissButton = { GhostButton("Cancel", onClick = onDismiss, tone = Tone.Neutral) }
    )
}

/** One app holding a grant (an enabled keyboard or an active device admin), with its score when it was flagged. */
@Composable
private fun GrantCard(
    packageName: String,
    flagged: StalkwareScanner.SuspiciousApp?,
    context: Context,
    actionLabel: String,
    action: Intent,
    known: Boolean = false,
) {
    val label = remember(packageName) { StalkwareScanner.appLabel(context, packageName) }
    ClairixCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(packageName, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
            }
            if (flagged != null) StatusPill("${flagged.severity.name} · ${flagged.score}", flagged.severity.tone())
            else if (known) StatusPill("Known app", Tone.Safe)
        }
        FlaggedAppButton(actionLabel, packageName, flagged, gated = SafetyFirst.gatesSettings(flagged != null, known)) {
            openExternal(context, action)
        }
    }
}

@Composable
private fun SuspiciousAppCard(app: StalkwareScanner.SuspiciousApp, context: Context) {
    ClairixCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(app.appLabel, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(app.packageName, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
            }
            StatusPill("${app.severity.name} · ${app.score}", app.severity.tone())
        }
        KeyValueRow("Installed", tDay(app.installTs))
        KeyValueRow("Installer", app.installerPackage ?: if (app.sideloaded) "unknown (sideloaded)" else "—")
        if (app.imeEnabled) KeyValueRow("Keyboard", "enabled")
        if (app.deviceAdminActive) KeyValueRow("Device admin", "active")
        if (app.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (reason in app.reasons) {
                    Text("• $reason", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                }
            }
        }
        FlaggedAppButton("Open in settings", app.packageName, flagged = app) {
            openExternal(context, StalkwareScanner.appDetailsIntent(app.packageName))
        }
    }
}

// ── 3. Certificates & TLS ──────────────────────────────────────────────────────

@Composable
internal fun CertificatesPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var audit by remember { mutableStateOf<CaAudit?>(null) }
    var tls by remember { mutableStateOf<TlsCanaryResult?>(null) }
    var last by remember { mutableStateOf(TlsCanary.lastResult(context)) }
    var next by remember { mutableStateOf(TlsCanary.nextRun(context)) }
    var pinned by remember { mutableStateOf(TlsCanary.pinned(context)) }
    var strict by remember { mutableStateOf(TlsCanary.strict(context)) }
    var checking by remember { mutableStateOf(false) }
    var confirmPin by remember { mutableStateOf(false) }

    /** Reads the trust store again; a read that failed is shown as failed, never as an empty store. */
    fun readTrustStore() {
        audit = null
        scope.launch { audit = withContext(Dispatchers.IO) { CaAuditScanner.scan(context) } }
    }

    LaunchedEffect(Unit) { readTrustStore() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // A scheduled run may have happened while this page was away.
        last = TlsCanary.lastResult(context)
        next = TlsCanary.nextRun(context)
    }

    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { TlsCanary.check(context).collect { tls = it } }
                .onFailure { tls = TlsCanaryResult(intercepted = false, method = null, proxyHost = null, error = "Check failed: ${it.message}") }
            last = TlsCanary.lastResult(context)
            next = TlsCanary.nextRun(context)
            pinned = TlsCanary.pinned(context)
            checking = false
        }
    }

    val pinCandidate = tls?.takeIf { it.untrustedUnpinned }
    if (confirmPin && pinCandidate?.relayHost != null && pinCandidate.observedSpki != null) {
        AlertDialog(
            onDismissRequest = { confirmPin = false },
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text("Pin this certificate?", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    "Only for your own relay, served with a certificate from your own private CA. " +
                    "From now on, any other key from ${pinCandidate.relayHost} is reported as interception. " +
                    "If you did not set up this relay yourself, do not pin it: someone may be answering in its place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Pin certificate",
                    onClick = {
                        confirmPin = false
                        TlsCanary.pinManually(context, pinCandidate.relayHost, pinCandidate.observedSpki)
                        pinned = TlsCanary.pinned(context)
                        checkNow()
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = {
                GhostButton("Cancel", onClick = { confirmPin = false }, tone = Tone.Neutral)
            }
        )
    }

    ToolPageList(title = "Certificates & TLS", onBack = onBack) {
        item(key = "§tls-hdr") { SectionHeader("TLS canary") }
        item(key = "§tls") {
            val r = tls
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Relay connection", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "Opens TLS to the comms relay — directly and, when a proxy is set, through the proxy — " +
                            "and compares the server's key with the one pinned on first contact. An intercepting " +
                            "proxy cannot present the same key.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    when {
                        r == null -> Unit
                        r.intercepted -> StatusPill("Intercepted", Tone.Danger)
                        r.untrustedUnpinned -> StatusPill("Not trusted", Tone.Danger)
                        r.proxyOnly -> StatusPill("Proxy", Tone.Caution)
                        r.error != null && r.observedSpki == null -> StatusPill("No result", Tone.Caution)
                        r.pinnedNow -> StatusPill("Pinned", Tone.Info)
                        else -> StatusPill("Clear", Tone.Safe)
                    }
                }
                val lastRun = last
                if (lastRun != null) {
                    val alarming = lastRun.second.startsWith("INTERCEPTED") || lastRun.second.startsWith("NOT PUBLICLY TRUSTED")
                    KeyValueRow("Last result", lastRun.second, valueColor = if (alarming) ClairixTheme.colors.danger else ClairixTheme.colors.textMuted)
                    if (lastRun.first > 0) KeyValueRow("Checked", tFull(lastRun.first))
                }
                // The scheduled run: every four hours and on each network change, for as
                // long as comms are set up or the scanner is on. Nothing armed means neither is.
                val nextRun = next
                if (nextRun != null) {
                    KeyValueRow("Next check", tFull(nextRun))
                } else {
                    KeyValueRow("Next check", "Not scheduled: runs every four hours while Comms is set up or the scanner is on", mono = false)
                }
                pinned?.let { (host, pin) -> KeyValueRow("Pinned ($host)", pin.take(24) + "…") }
                if (pinned != null) {
                    ToggleRow(
                        title = "Refuse the relay if its key changes",
                        subtitle = "Every comms connection, not only this check, is held to the pinned key: a different one is " +
                            "refused even when the phone's trust store accepts it, which is what a coerced certificate " +
                            "authority would produce. Comms then stop until you forget the pin here. Expect that once " +
                            "whenever the relay's certificate is renewed with a new key.",
                        checked = strict,
                        onCheckedChange = { on -> strict = on; TlsCanary.setStrict(context, on) }
                    )
                }
                if (r != null) {
                    r.relayHost?.let { KeyValueRow("Relay", it) }
                    r.observedSpki?.let { KeyValueRow("Observed key", it.take(24) + "…") }
                    r.observedIssuer?.let { KeyValueRow("Issuer", it) }
                    r.chainTrusted?.let { KeyValueRow("Chain trusted", if (it) "yes" else "no", valueColor = if (it) ClairixTheme.colors.safe else ClairixTheme.colors.danger) }
                    r.proxyHost?.let { KeyValueRow("Proxy", it, valueColor = ClairixTheme.colors.caution) }
                    r.viaProxySpki?.let { KeyValueRow("Key through proxy", it.take(24) + "…", valueColor = if (r.proxyVerified == true) ClairixTheme.colors.safe else ClairixTheme.colors.danger) }
                    r.method?.let {
                        val tone = when {
                            r.intercepted || r.untrustedUnpinned -> ClairixTheme.colors.danger
                            r.proxyOnly -> ClairixTheme.colors.caution
                            else -> ClairixTheme.colors.textMuted
                        }
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = tone)
                    }
                    r.proxyNote?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = if (r.proxyVerified == true) ClairixTheme.colors.textMuted else ClairixTheme.colors.caution)
                    }
                    r.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution) }
                }
                if (checking) {
                    ClairixProgressBar(null)
                } else {
                    SecondaryButton("Check now", onClick = { checkNow() }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                    if (r != null && r.untrustedUnpinned && r.observedSpki != null) {
                        GhostButton(
                            "Pin this certificate (my own relay)",
                            onClick = { confirmPin = true },
                            tone = Tone.Neutral
                        )
                    }
                }
                if (pinned != null) {
                    GhostButton(
                        "Forget the pinned key (relay rotated its key)",
                        onClick = { TlsCanary.resetPin(context); pinned = null },
                        tone = Tone.Neutral
                    )
                }
            }
        }

        item(key = "§ca-hdr") { SectionHeader("Trust store audit") }
        when (val state = CaAuditScanner.page(audit)) {
            CaPage.Reading -> item(key = "§ca-progress") { LoadingBlock("Reading the trust store…") }
            // Only when both sources were read: a read that failed is never shown as a clean store.
            CaPage.Clean -> item(key = "§ca-clean") {
                ClairixCard {
                    Text("No unexpected root certificates", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.safe)
                    Text(
                        "Nothing was installed by hand, and every system root is one Android ships with. " +
                        "A hand-installed root is how HTTPS traffic is read through a proxy by someone " +
                        "who has had the phone and its PIN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
            is CaPage.Findings -> {
                val failure = state.failure
                if (failure != null) item(key = "§ca-failed") {
                    Notice(
                        Tone.Caution,
                        state.failureDetail.orEmpty(),
                        title = failure,
                        action = { GhostButton("Read it again", onClick = { readTrustStore() }, tone = Tone.Neutral) }
                    )
                }
                if (state.unreadableUser > 0) item(key = "§ca-unreadable") {
                    val n = state.unreadableUser
                    Notice(
                        Tone.Danger,
                        "${if (n == 1) "A certificate was" else "$n certificates were"} installed by hand on this phone, and " +
                            "${if (n == 1) "it" else "they"} would not open, so nothing more can be shown here. Unless you " +
                            "installed ${if (n == 1) "it" else "them"} yourself, remove ${if (n == 1) "it" else "them"} under " +
                            "Settings → Security → Encryption & credentials → User credentials.",
                        title = if (n == 1) "1 certificate installed by hand" else "$n certificates installed by hand"
                    )
                }
                items(state.roots, key = { "ca|${it.fingerprint}" }) { ca ->
                    ClairixCard(spacing = 4.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ca.subjectCn,
                                style = MaterialTheme.typography.titleSmall,
                                color = ClairixTheme.colors.text,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            StatusPill(ca.source.uppercase(Locale.US), ca.severity.tone())
                        }
                        KeyValueRow("Issuer", CaAuditScanner.cn(ca.issuerDn) ?: ca.issuerDn)
                        KeyValueRow("SHA-256", ca.fingerprint.take(23) + "…")
                        KeyValueRow("Valid", "${tDay(ca.validFrom)} – ${tDay(ca.validTo)}")
                        if (ca.reason.isNotBlank()) {
                            Text(ca.reason, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                        }
                    }
                }
            }
        }
    }
}

// ── 4. Headsets and microphones (on the Device page) ──────────────────────────

/**
 * What the Device page's microphone section shows and what its buttons do: the wireless and USB
 * microphones the scanner noticed this session (analysis/AudioRouteMonitor), and the ones marked
 * as the owner's. See [rememberAudioInputs] and [audioInputItems].
 */
internal class AudioInputs(
    val routes: List<AudioRouteEvent>,
    val marks: List<AudioRouteMonitor.Mark>,
    val onMine: (AudioRouteEvent) -> Unit,
    val onNotMine: (String) -> Unit
) {
    /** The newest entry for each microphone not marked as the owner's, newest first. */
    val noticed: List<AudioRouteEvent>
        get() {
            val mine = marks.mapTo(HashSet()) { it.key }
            return routes.distinctBy { it.key }.filter { it.key !in mine }
        }

    val isEmpty: Boolean get() = noticed.isEmpty() && marks.isEmpty()
}

@Composable
internal fun rememberAudioInputs(): AudioInputs {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routes by Registry.audioRoutes.collectAsStateWithLifecycle()
    val marks by AudioRouteMonitor.marks.collectAsStateWithLifecycle()
    // The marks live in a sealed file: read once the page is up; a change reloads them.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AudioRouteMonitor.loadMarks(context) } }
    return AudioInputs(
        routes = routes,
        marks = marks,
        onMine = { e -> scope.launch(Dispatchers.IO) { AudioRouteMonitor.acknowledge(context, e) } },
        onNotMine = { key -> scope.launch(Dispatchers.IO) { AudioRouteMonitor.unacknowledge(context, key) } }
    )
}

/**
 * The microphones on the Device page: each one noticed, with what was raised for it and "This is
 * my headset", then the owner's own with an undo. Nothing at all when there is neither.
 */
internal fun LazyListScope.audioInputItems(inputs: AudioInputs) {
    if (inputs.isEmpty) return
    item(key = "audio_inputs_hdr") { SectionHeader("Headsets and microphones") }
    item(key = "audio_inputs_note") {
        Text(
            "A Bluetooth or USB microphone that connects while nothing is playing is listed here and put on the " +
                "timeline. It sends a notification only if it becomes the phone's call audio with the screen off. One " +
                "you mark as yours is not reported again, and a paired one that was already connected on an earlier " +
                "day is left alone.",
            style = MaterialTheme.typography.bodyMedium,
            color = ClairixTheme.colors.textSubtle,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    items(inputs.noticed, key = { "audio|${it.key}" }) { e -> AudioInputRow(e, onMine = { inputs.onMine(e) }) }
    items(inputs.marks, key = { "audio_mine|${it.key}" }) { m -> MarkedAudioRow(m, onNotMine = { inputs.onNotMine(m.key) }) }
}

@Composable
private fun AudioInputRow(e: AudioRouteEvent, onMine: () -> Unit) {
    val c = ClairixTheme.colors
    val alerted = e.alerted
    val tone = alerted?.tone() ?: Tone.Neutral
    val line = when {
        alerted == Severity.MEDIUM ->
            "Became the phone's call audio with the screen off and no call up, ${tFull(e.ts)}."
        alerted != null ->
            "Connected while nothing was playing and no call was up, ${tFull(e.ts)}. On the timeline, without a notification."
        else -> "Already connected when scanning was switched on, so it was listed, not reported."
    }
    ClairixCard(tone = tone, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.deviceName,
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            StatusPill(if (alerted != null) levelLabel(alerted.name) else "Listed", tone)
        }
        Text(
            e.deviceTypeLabel + (e.address?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.monoSmall,
            color = c.textSubtle
        )
        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        GhostButton("This is my headset", onClick = onMine, tone = Tone.Neutral)
    }
}

@Composable
private fun MarkedAudioRow(m: AudioRouteMonitor.Mark, onNotMine: () -> Unit) {
    val c = ClairixTheme.colors
    ClairixCard(spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                m.name,
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            StatusPill("Yours", Tone.Safe)
        }
        Text(
            "Marked as your headset" + (if (m.at > 0L) " " + tFull(m.at) else "") + ": not reported again.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted
        )
        GhostButton("Not mine", onClick = onNotMine, tone = Tone.Neutral)
    }
}
