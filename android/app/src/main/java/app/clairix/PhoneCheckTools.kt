package app.clairix

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CaAuditScanner
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.PatternOfLife
import app.clairix.analysis.PatternReport
import app.clairix.analysis.StalkwareScanner
import app.clairix.analysis.SuspiciousCa
import app.clairix.analysis.TlsCanary
import app.clairix.analysis.TlsCanaryResult
import app.clairix.analysis.clock
import app.clairix.comms.CommsRepository
import app.clairix.comms.Contact
import app.clairix.comms.formatClairixNumber
import app.clairix.detect.BleCanary
import app.clairix.detect.BleCanaryState
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.LocatorState
import app.clairix.detect.RssiLocator
import app.clairix.detect.RssiLocatorView
import app.clairix.security.BiometricTripwire
import app.clairix.security.BiometricTripwireResult
import app.clairix.security.DuressPhrase
import app.clairix.security.FakeCall
import app.clairix.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone

// The Phone check category's on-demand pages: the stalkerware scan and the
// certificate and TLS audit.

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

    fun runScan() {
        if (scanning) return
        scanning = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { StalkwareScanner.scan(context) } }
            r.onSuccess { result = it }.onFailure { error = "The scan failed: ${it.javaClass.simpleName}: ${it.message}" }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { if (result == null) runScan() }
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
                    KeyValueRow("Notification access", "${r.notificationListeners.size} ${if (r.notificationListeners.size == 1) "app" else "apps"}")
                    KeyValueRow("Accessibility services", "${r.accessibilityServices.size}")
                    KeyValueRow("VPN-capable apps", "${r.vpnApps.size}")
                    KeyValueRow("Unknown keyboards", "${r.suspiciousImes.size}")
                    val unknownAdmins = r.activeDeviceAdmins.count { it !in r.vouchedAdmins }
                    KeyValueRow("Active device admins", "${r.activeDeviceAdmins.size} ($unknownAdmins unknown)")
                }
            }
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
                    }
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
            if (r.suspiciousImes.isNotEmpty()) {
                item(key = "§ime-hdr") { SectionHeader("Keyboards") }
                item(key = "§ime-note") {
                    Notice(
                        Tone.Caution,
                        "An enabled keyboard sees everything typed while it is in use, passwords and " +
                            "messages included. If you did not install it yourself, switch it off in " +
                            "keyboard settings, then uninstall it.",
                        title = "Unknown keyboard"
                    )
                }
                items(r.suspiciousImes, key = { "ime|$it" }) { pkg ->
                    GrantCard(
                        packageName = pkg,
                        flagged = r.apps.firstOrNull { it.packageName == pkg },
                        context = context,
                        actionLabel = "KEYBOARD SETTINGS",
                        action = StalkwareScanner.keyboardSettingsIntent(),
                    )
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
                    GrantCard(
                        packageName = pkg,
                        flagged = r.apps.firstOrNull { it.packageName == pkg },
                        context = context,
                        actionLabel = "OPEN IN SETTINGS",
                        action = StalkwareScanner.appDetailsIntent(pkg),
                        // A known name alone is not enough: the scanner vouches only for one from a store or the system image.
                        known = pkg in r.vouchedAdmins,
                    )
                }
            }
        }
    }
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
        SecondaryButton(actionLabel, onClick = { openExternal(context, action) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
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
        SecondaryButton("Open in settings", onClick = { openExternal(context, StalkwareScanner.appDetailsIntent(app.packageName)) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
    }
}

// ── 3. Certificates & TLS ──────────────────────────────────────────────────────

@Composable
internal fun CertificatesPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cas by remember { mutableStateOf<List<SuspiciousCa>?>(null) }
    var tls by remember { mutableStateOf<TlsCanaryResult?>(null) }
    var last by remember { mutableStateOf(TlsCanary.lastResult(context)) }
    var pinned by remember { mutableStateOf(TlsCanary.pinned(context)) }
    var checking by remember { mutableStateOf(false) }
    var confirmPin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cas = withContext(Dispatchers.IO) { runCatching { CaAuditScanner.scan(context) }.getOrDefault(emptyList()) }
    }

    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { TlsCanary.check(context).collect { tls = it } }
                .onFailure { tls = TlsCanaryResult(intercepted = false, method = null, proxyHost = null, error = "Check failed: ${it.message}") }
            last = TlsCanary.lastResult(context)
            pinned = TlsCanary.pinned(context)
            checking = false
        }
    }

    val pinCandidate = tls?.takeIf { it.untrustedUnpinned }
    if (confirmPin && pinCandidate?.relayHost != null && pinCandidate.observedSpki != null) {
        AlertDialog(
            onDismissRequest = { confirmPin = false },
            containerColor = ClairixTheme.colors.surface,
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
                    "PIN",
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
                } else {
                    KeyValueRow("Last result", "never run")
                }
                pinned?.let { (host, pin) -> KeyValueRow("Pinned ($host)", pin.take(24) + "…") }
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
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ClairixTheme.colors.accent, trackColor = ClairixTheme.colors.border)
                } else {
                    SecondaryButton("Check now", onClick = { checkNow() }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                    if (r != null && r.untrustedUnpinned && r.observedSpki != null) {
                        GhostButton(
                            "PIN this certificate (my own relay)",
                            onClick = { confirmPin = true },
                            tone = Tone.Neutral
                        )
                    }
                }
                if (pinned != null) {
                    GhostButton(
                        "Forget PIN (relay rotated its key)",
                        onClick = { TlsCanary.resetPin(context); pinned = null },
                        tone = Tone.Neutral
                    )
                }
            }
        }

        item(key = "§ca-hdr") { SectionHeader("Trust store audit") }
        val list = cas
        when {
            list == null -> item(key = "§ca-progress") { LoadingBlock("Reading the trust store…") }
            list.isEmpty() -> item(key = "§ca-clean") {
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
            else -> items(list, key = { "ca|${it.fingerprint}" }) { ca ->
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
