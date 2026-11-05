package app.clairix

import androidx.compose.ui.res.stringResource
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
            r.onSuccess { result = it }.onFailure { error = context.getString(R.string.scan_the_scan_failed, it.javaClass.simpleName, it.message.toString()) }
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
            if (!ok) error = context.getString(R.string.scan_could_not_read_who_signed_so, packageName)
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

    ToolPageList(title = stringResource(R.string.scan_stalkerware), onBack = onBack) {
        if (!usageAccess) {
            item(key = "§usage") {
                ClairixCard {
                    Text(stringResource(R.string.scan_usage_access_not_granted), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.caution)
                    Text(
                        stringResource(R.string.scan_with_usage_access_clairix_can_also),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                    SecondaryButton(stringResource(R.string.scan_grant_usage_access), onClick = { openExternal(context, StalkwareScanner.usageAccessIntent()) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (scanning) {
            item(key = "§progress") { LoadingBlock(stringResource(R.string.scan_scanning_installed_apps)) }
        } else {
            item(key = "§rescan") { SecondaryButton(stringResource(R.string.scan_scan_again), onClick = { runScan() }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth()) }
        }

        error?.let { e -> item(key = "§error") { Notice(Tone.Danger, e, title = stringResource(R.string.scan_error_2)) } }

        val r = result
        if (r != null) {
            item(key = "§summary") {
                ClairixCard(spacing = 4.dp) {
                    KeyValueRow(stringResource(R.string.scan_scanned), tFull(r.scannedAt))
                    KeyValueRow(stringResource(R.string.scan_flagged), stringResource(R.string.scan_high_medium, r.high.size, r.medium.size))
                    KeyValueRow(stringResource(R.string.scan_notification_access), "${r.notificationListeners.size} ${if (r.notificationListeners.size == 1) "app" else "apps"} (${unknownCount(r, StalkwareScanner.GrantKind.NOTIFICATION_LISTENER, r.notificationListeners)} unknown)")
                    KeyValueRow(stringResource(R.string.scan_accessibility_services), stringResource(R.string.scan_unknown_2, r.accessibilityServices.size, unknownCount(r, StalkwareScanner.GrantKind.ACCESSIBILITY, r.accessibilityServices)))
                    KeyValueRow(stringResource(R.string.scan_vpn_capable_apps), "${r.vpnApps.size}")
                    KeyValueRow(stringResource(R.string.scan_keyboards), stringResource(R.string.scan_enabled_unknown, r.enabledImes.size, r.suspiciousImes.size))
                    val unknownAdmins = r.activeDeviceAdmins.count { it !in r.vouchedAdmins }
                    KeyValueRow(stringResource(R.string.scan_active_device_admins), stringResource(R.string.scan_unknown_2, r.activeDeviceAdmins.size, unknownAdmins))
                    if (r.owners.isNotEmpty()) KeyValueRow(stringResource(R.string.scan_managed_by), r.owners.joinToString(", ") { StalkwareScanner.appLabel(context, it.packageName) }, mono = false)
                    KeyValueRow(stringResource(R.string.scan_other_profiles), if (r.otherProfiles == 0) stringResource(R.string.scan_none_visible) else stringResource(R.string.scan_not_scanned, r.otherProfiles), mono = false)
                    if (r.listApps > 0) KeyValueRow(stringResource(R.string.scan_stalkerware_list), stringResource(R.string.scan_apps_issued, r.listApps, tDay(r.listIssued * 1000)), mono = false)
                    else if (BuildConfig.FULL_ACCESS) KeyValueRow(stringResource(R.string.scan_stalkerware_list), stringResource(R.string.scan_not_loaded_apps_were_checked_by), mono = false)
                }
            }
            managedBySection(r, context, onMine)
            // Not while an owner or a grant needs a look: a green card beside them would say the phone is clean.
            if (r.clean) {
                item(key = "§clean") {
                    ClairixCard {
                        Text(stringResource(R.string.scan_no_suspicious_apps_found), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.safe)
                        Text(
                            stringResource(R.string.scan_nothing_installed_scores_high_enough_to),
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
                item(key = "§high-hdr") { SectionHeader(stringResource(R.string.scan_high_2)) }
                items(r.high, key = { "high|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            if (r.medium.isNotEmpty()) {
                item(key = "§medium-hdr") { SectionHeader(stringResource(R.string.scan_medium)) }
                items(r.medium, key = { "medium|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            // Every enabled keyboard, the known ones marked as such.
            if (r.enabledImes.isNotEmpty()) {
                item(key = "§ime-hdr") { SectionHeader(stringResource(R.string.scan_keyboards)) }
                if (r.suspiciousImes.isNotEmpty()) item(key = "§ime-note") {
                    Notice(
                        Tone.Caution,
                        stringResource(R.string.scan_an_enabled_keyboard_sees_everything_typed),
                        title = stringResource(R.string.scan_unknown_keyboard)
                    )
                }
                // Every row opens the same keyboard settings page, so each asks first while any keyboard there needs a look.
                val imeSubject = SafetyFirst.sharedPageSubject(r, StalkwareScanner.GrantKind.KEYBOARD, r.enabledImes)
                items(r.enabledImes, key = { "ime|$it" }) { pkg ->
                    GrantRow(r, StalkwareScanner.GrantKind.KEYBOARD, pkg, onMine) {
                        GrantCard(
                            packageName = pkg,
                            flagged = r.apps.firstOrNull { it.packageName == pkg },
                            context = context,
                            actionLabel = stringResource(R.string.scan_keyboard_settings),
                            action = StalkwareScanner.keyboardSettingsIntent(),
                            known = r.status(StalkwareScanner.GrantKind.KEYBOARD, pkg) == StalkwareScanner.GrantStatus.KNOWN,
                            sharedSubject = imeSubject,
                            sharedFlagged = r.apps.firstOrNull { it.packageName == imeSubject },
                        )
                    }
                }
            }
            if (r.activeDeviceAdmins.isNotEmpty()) {
                item(key = "§admin-hdr") { SectionHeader(stringResource(R.string.scan_device_admins)) }
                item(key = "§admin-note") {
                    Notice(
                        Tone.Caution,
                        stringResource(R.string.scan_a_device_admin_can_stop_its),
                        title = stringResource(R.string.scan_active_device_admin)
                    )
                }
                items(r.activeDeviceAdmins, key = { "admin|$it" }) { pkg ->
                    GrantRow(r, StalkwareScanner.GrantKind.DEVICE_ADMIN, pkg, onMine) {
                        GrantCard(
                            packageName = pkg,
                            flagged = r.apps.firstOrNull { it.packageName == pkg },
                            context = context,
                            actionLabel = stringResource(R.string.scan_open_in_settings),
                            action = StalkwareScanner.appDetailsIntent(pkg),
                            // A known name alone is not enough: the scanner vouches only for one from a store or the
                            // system image, and never for an owner or an unconfirmed management app, whatever its name.
                            known = r.adminStatus(pkg) == StalkwareScanner.GrantStatus.KNOWN,
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
        "Only apps in the profile Clairix runs in were checked: apps in a work profile, Secure Folder, Private space or a second space were not."
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
    item(key = "§owner-hdr") { SectionHeader(stringResource(R.string.scan_managed_by), tone = if (confirmed) Tone.Accent else Tone.Danger) }
    items(r.owners, key = { "owner|${it.packageName}" }) { owner ->
        val label = remember(owner.packageName) { StalkwareScanner.appLabel(context, owner.packageName) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r.status(owner.kind, owner.packageName) == StalkwareScanner.GrantStatus.YOURS) {
                Notice(
                    Tone.Info,
                    stringResource(R.string.scan_you_confirmed_you_enrolled_this_phone, label),
                    title = stringResource(R.string.scan_managed_by_2, label)
                )
            } else {
                Notice(
                    Tone.Danger,
                    stringResource(R.string.scan_an_owner_can_install_and_remove, (if (owner.deviceOwner) stringResource(R.string.scan_is_this_phones_device_owner, label)
                    else stringResource(R.string.scan_owns_the_profile_clairix_runs_in, label))),
                    title = stringResource(R.string.scan_this_phone_is_managed_by, label)
                )
            }
            GrantRow(r, owner.kind, owner.packageName, onMine) {
                GrantCard(
                    packageName = owner.packageName,
                    flagged = r.apps.firstOrNull { it.packageName == owner.packageName },
                    context = context,
                    actionLabel = stringResource(R.string.scan_open_in_settings),
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
        // Every row opens the same Settings list, so each asks first while any app on it needs a look.
        val subject = SafetyFirst.sharedPageSubject(r, kind, packages)
        items(packages, key = { "${kind.key}|$it" }) { pkg ->
            GrantRow(r, kind, pkg, onMine) {
                GrantCard(
                    packageName = pkg,
                    flagged = r.apps.firstOrNull { it.packageName == pkg },
                    context = context,
                    actionLabel = settingsLabel,
                    action = Intent(settings),
                    known = r.status(kind, pkg) == StalkwareScanner.GrantStatus.KNOWN,
                    sharedSubject = subject,
                    sharedFlagged = r.apps.firstOrNull { it.packageName == subject },
                )
            }
        }
    }

    grantList(
        StalkwareScanner.GrantKind.ACCESSIBILITY, "Accessibility services",
        "An accessibility service reads every screen and can tap for you. Screen readers, password managers and automation apps use one; so does almost every stalkerware app.",
        r.accessibilityServices, Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings"
    )
    grantList(
        StalkwareScanner.GrantKind.NOTIFICATION_LISTENER, "Notification access",
        "These apps read every notification as it arrives, one-time codes and message previews included. Watches, car systems and messaging apps use it.",
        r.notificationListeners, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Notification access settings"
    )
    if (r.vpnApps.isNotEmpty()) {
        item(key = "§vpn-hdr") { SectionHeader(stringResource(R.string.scan_apps_that_can_be_the_vpn), supporting = "${r.vpnApps.size}") }
        item(key = "§vpn-note") {
            Text(
                stringResource(R.string.scan_once_allowed_any_of_these_can),
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
                    actionLabel = stringResource(R.string.scan_vpn_settings),
                    action = Intent(Settings.ACTION_VPN_SETTINGS),
                )
                if (pkg in r.preloadedVpns) {
                    Text(
                        stringResource(R.string.scan_part_of_the_phones_own_system),
                        style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
    // The clean card carries this when nothing needs a look; otherwise it is said here.
    if (!r.clean) {
        item(key = "§profiles") {
            Notice(Tone.Neutral, profilesCaveat(r), title = stringResource(R.string.scan_not_checked))
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
                    stringResource(R.string.scan_you_marked_this_as_yours, kind.label),
                    style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle,
                    modifier = Modifier.weight(1f)
                )
                GhostButton(stringResource(R.string.scan_undo), onClick = { onMine(kind, packageName, false) }, tone = Tone.Neutral)
            }
            !listed && (status == StalkwareScanner.GrantStatus.UNKNOWN || status == StalkwareScanner.GrantStatus.CONFIRM) -> GhostButton(
                if (status == StalkwareScanner.GrantStatus.CONFIRM) stringResource(R.string.scan_i_enrolled_this_phone_myself) else stringResource(R.string.scan_this_is_mine),
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
            app.clairix.security.ShieldThisWindow()
            Text(
                if (enrolled) stringResource(R.string.scan_did_you_enrol_this_phone) else stringResource(R.string.scan_is_yours, label),
                style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text
            )
        },
        text = {
            Text(
                stringResource(R.string.scan_clairix_then_lists_it_as_yours, (if (enrolled) stringResource(R.string.scan_only_if_you_set_this_phone, label)
                else stringResource(R.string.scan_only_if_you_installed_and_switched, label, kind.label))),
                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
            )
        },
        confirmButton = { GhostButton(if (enrolled) stringResource(R.string.scan_i_enrolled_it) else stringResource(R.string.scan_it_is_mine), onClick = onConfirm, tone = Tone.Accent) },
        dismissButton = { GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss, tone = Tone.Neutral) }
    )
}

/**
 * One app holding a grant (an enabled keyboard or an active device admin), with its score when it was flagged.
 * When [action] opens a Settings list shared with other apps, [sharedSubject] is the app on that list that
 * needs a look ([SafetyFirst.sharedPageSubject]) and [sharedFlagged] the scan's record of it: a row that
 * would open straight away asks first about that app instead.
 */
@Composable
private fun GrantCard(
    packageName: String,
    flagged: StalkwareScanner.SuspiciousApp?,
    context: Context,
    actionLabel: String,
    action: Intent,
    known: Boolean = false,
    sharedSubject: String? = null,
    sharedFlagged: StalkwareScanner.SuspiciousApp? = null,
) {
    val label = remember(packageName) { StalkwareScanner.appLabel(context, packageName) }
    ClairixCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(packageName, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
            }
            if (flagged != null) StatusPill("${flagged.severity.name} · ${flagged.score}", flagged.severity.tone())
            else if (known) StatusPill(stringResource(R.string.scan_known_app), Tone.Safe)
        }
        val ownGate = SafetyFirst.gatesSettings(flagged != null, known)
        val subject = if (ownGate) packageName else sharedSubject
        FlaggedAppButton(
            actionLabel,
            subject ?: packageName,
            if (subject == packageName) flagged else sharedFlagged,
            gated = subject != null
        ) {
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
        KeyValueRow(stringResource(R.string.scan_installed), tDay(app.installTs))
        KeyValueRow(stringResource(R.string.scan_installer), app.installerPackage ?: if (app.sideloaded) stringResource(R.string.scan_unknown_sideloaded) else "—")
        if (app.grantedPermissions.isNotEmpty()) {
            KeyValueRow(stringResource(R.string.scan_permissions_granted), app.grantedPermissions.joinToString(", "), mono = false)
        }
        if (app.imeEnabled) KeyValueRow(stringResource(R.string.scan_keyboard), "enabled")
        if (app.deviceAdminActive) KeyValueRow(stringResource(R.string.scan_device_admin), "active")
        if (app.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (reason in app.reasons) {
                    Text("• $reason", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                }
            }
        }
        FlaggedAppButton(stringResource(R.string.scan_open_in_settings_2), app.packageName, flagged = app) {
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
                .onFailure { tls = TlsCanaryResult(intercepted = false, method = null, proxyHost = null, error = context.getString(R.string.scan_check_failed, it.message.toString())) }
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
            title = {
                app.clairix.security.ShieldThisWindow()
                Text(stringResource(R.string.scan_pin_this_certificate), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            },
            text = {
                Text(
                    stringResource(R.string.scan_only_for_your_own_relay_served, pinCandidate.relayHost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.scan_pin_certificate),
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
                GhostButton(stringResource(R.string.settings_cancel), onClick = { confirmPin = false }, tone = Tone.Neutral)
            }
        )
    }

    ToolPageList(title = stringResource(R.string.scan_certificates_tls), onBack = onBack) {
        item(key = "§tls-hdr") { SectionHeader(stringResource(R.string.scan_tls_canary)) }
        item(key = "§tls") {
            val r = tls
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.scan_relay_connection), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.scan_opens_tls_to_the_comms_relay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    when {
                        r == null -> Unit
                        r.intercepted -> StatusPill(stringResource(R.string.scan_intercepted), Tone.Danger)
                        r.untrustedUnpinned -> StatusPill(stringResource(R.string.scan_not_trusted), Tone.Danger)
                        r.proxyOnly -> StatusPill(stringResource(R.string.scan_proxy), Tone.Caution)
                        r.error != null && r.observedSpki == null -> StatusPill(stringResource(R.string.scan_no_result), Tone.Caution)
                        r.pinnedNow -> StatusPill(stringResource(R.string.scan_pinned), Tone.Info)
                        else -> StatusPill(stringResource(R.string.comms_clear), Tone.Safe)
                    }
                }
                val lastRun = last
                if (lastRun != null) {
                    val alarming = lastRun.second.startsWith("INTERCEPTED") || lastRun.second.startsWith("NOT PUBLICLY TRUSTED")
                    KeyValueRow(stringResource(R.string.scan_last_result), lastRun.second, valueColor = if (alarming) ClairixTheme.colors.danger else ClairixTheme.colors.textMuted)
                    if (lastRun.first > 0) KeyValueRow(stringResource(R.string.scan_checked), tFull(lastRun.first))
                }
                // The scheduled run: every four hours and on each network change, for as
                // long as comms are set up or the scanner is on. Nothing armed means neither is.
                val nextRun = next
                if (nextRun != null) {
                    KeyValueRow(stringResource(R.string.scan_next_check), tFull(nextRun))
                } else {
                    KeyValueRow(stringResource(R.string.scan_next_check), stringResource(R.string.scan_not_scheduled_runs_every_four_hours), mono = false)
                }
                pinned?.let { (host, pin) -> KeyValueRow(stringResource(R.string.scan_pinned_2, host), pin.take(24) + "…") }
                if (pinned != null) {
                    ToggleRow(
                        title = stringResource(R.string.scan_refuse_the_relay_if_its_key),
                        subtitle = stringResource(R.string.scan_every_comms_connection_not_only_this),
                        checked = strict,
                        onCheckedChange = { on -> strict = on; TlsCanary.setStrict(context, on) }
                    )
                }
                if (r != null) {
                    r.relayHost?.let { KeyValueRow(stringResource(R.string.comms_relay), it) }
                    r.observedSpki?.let { KeyValueRow(stringResource(R.string.scan_observed_key), it.take(24) + "…") }
                    r.observedIssuer?.let { KeyValueRow(stringResource(R.string.scan_issuer), it) }
                    r.chainTrusted?.let { KeyValueRow(stringResource(R.string.scan_chain_trusted), if (it) "yes" else "no", valueColor = if (it) ClairixTheme.colors.safe else ClairixTheme.colors.danger) }
                    r.proxyHost?.let { KeyValueRow(stringResource(R.string.scan_proxy), it, valueColor = ClairixTheme.colors.caution) }
                    r.viaProxySpki?.let { KeyValueRow(stringResource(R.string.scan_key_through_proxy), it.take(24) + "…", valueColor = if (r.proxyVerified == true) ClairixTheme.colors.safe else ClairixTheme.colors.danger) }
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
                    SecondaryButton(stringResource(R.string.scan_check_now), onClick = { checkNow() }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                    if (r != null && r.untrustedUnpinned && r.observedSpki != null) {
                        GhostButton(
                            stringResource(R.string.scan_pin_this_certificate_my_own_relay),
                            onClick = { confirmPin = true },
                            tone = Tone.Neutral
                        )
                    }
                }
                if (pinned != null) {
                    GhostButton(
                        stringResource(R.string.scan_forget_the_pinned_key_relay_rotated),
                        onClick = { TlsCanary.resetPin(context); pinned = null },
                        tone = Tone.Neutral
                    )
                }
            }
        }

        item(key = "§ca-hdr") { SectionHeader(stringResource(R.string.scan_trust_store_audit)) }
        when (val state = CaAuditScanner.page(audit)) {
            CaPage.Reading -> item(key = "§ca-progress") { LoadingBlock(stringResource(R.string.scan_reading_the_trust_store)) }
            // Only when both sources were read: a read that failed is never shown as a clean store.
            CaPage.Clean -> item(key = "§ca-clean") {
                ClairixCard {
                    Text(stringResource(R.string.scan_no_unexpected_root_certificates), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.safe)
                    Text(
                        stringResource(R.string.scan_nothing_was_installed_by_hand_and),
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
                        action = { GhostButton(stringResource(R.string.scan_read_it_again), onClick = { readTrustStore() }, tone = Tone.Neutral) }
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
                        title = if (n == 1) stringResource(R.string.scan_1_certificate_installed_by_hand) else stringResource(R.string.scan_certificates_installed_by_hand, n)
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
                        KeyValueRow(stringResource(R.string.scan_issuer), CaAuditScanner.cn(ca.issuerDn) ?: ca.issuerDn)
                        KeyValueRow("SHA-256", ca.fingerprint.take(23) + "…")
                        KeyValueRow(stringResource(R.string.scan_valid), "${tDay(ca.validFrom)} – ${tDay(ca.validTo)}")
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
    item(key = "audio_inputs_hdr") { SectionHeader(stringResource(R.string.scan_headsets_and_microphones)) }
    item(key = "audio_inputs_note") {
        Text(
            stringResource(R.string.scan_a_bluetooth_or_usb_microphone_that),
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
            stringResource(R.string.scan_became_the_phones_call_audio_with, tFull(e.ts))
        alerted != null ->
            stringResource(R.string.scan_connected_while_nothing_was_playing_and, tFull(e.ts))
        else -> stringResource(R.string.scan_already_connected_when_scanning_was_switched)
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
            StatusPill(if (alerted != null) levelLabel(alerted.name) else stringResource(R.string.scan_listed), tone)
        }
        Text(
            e.deviceTypeLabel + (e.address?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.monoSmall,
            color = c.textSubtle
        )
        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        GhostButton(stringResource(R.string.scan_this_is_my_headset), onClick = onMine, tone = Tone.Neutral)
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
            StatusPill(stringResource(R.string.scan_yours), Tone.Safe)
        }
        Text(
            stringResource(R.string.scan_marked_as_your_headset_not_reported, (if (m.at > 0L) " " + tFull(m.at) else "")),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted
        )
        GhostButton(stringResource(R.string.scan_not_mine), onClick = onNotMine, tone = Tone.Neutral)
    }
}
