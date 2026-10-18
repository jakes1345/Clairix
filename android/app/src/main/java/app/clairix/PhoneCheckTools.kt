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

    ToolPageList(title = "STALKERWARE", onBack = onBack) {
        if (!usageAccess) {
            item(key = "§usage") {
                TCard {
                    Text("Usage access not granted", color = TCautionClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "With usage access Clairix can also see which apps run a hidden foreground service " +
                        "without ever being opened — the strongest single sign of stalkerware. Without it " +
                        "that signal is skipped.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    TButton("GRANT USAGE ACCESS", onClick = { openExternal(context, StalkwareScanner.usageAccessIntent()) })
                }
            }
        }

        if (scanning) {
            item(key = "§progress") { TProgress("Scanning installed apps…") }
        } else {
            item(key = "§rescan") { TButton("SCAN AGAIN", onClick = { runScan() }) }
        }

        error?.let { e -> item(key = "§error") { TNotice(TCriticalClr, "ERROR", e) } }

        val r = result
        if (r != null) {
            item(key = "§summary") {
                TCard(spacing = 4) {
                    TKv("Scanned", tFull(r.scannedAt))
                    TKv("Flagged", "${r.high.size} high · ${r.medium.size} medium")
                    TKv("Notification access", "${r.notificationListeners.size} ${if (r.notificationListeners.size == 1) "app" else "apps"}")
                    TKv("Accessibility services", "${r.accessibilityServices.size}")
                    TKv("VPN-capable apps", "${r.vpnApps.size}")
                }
            }
            if (r.high.isEmpty() && r.medium.isEmpty()) {
                item(key = "§clean") {
                    TCard {
                        Text("No suspicious apps found", color = TClearClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Nothing installed scores high enough to worry about. A clean result is not " +
                            "proof: stalkerware sold as a 'parental control' app can look like a store install.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
            if (r.high.isNotEmpty()) {
                item(key = "§high-hdr") { TSectionLabel("HIGH") }
                items(r.high, key = { "high|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            if (r.medium.isNotEmpty()) {
                item(key = "§medium-hdr") { TSectionLabel("MEDIUM") }
                items(r.medium, key = { "medium|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
        }
    }
}

@Composable
private fun SuspiciousAppCard(app: StalkwareScanner.SuspiciousApp, context: Context) {
    TCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(app.appLabel, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            TBadge("${app.severity.name} · ${app.score}", tSeverityColor(app.severity))
        }
        TKv("Installed", tDay(app.installTs))
        TKv("Installer", app.installerPackage ?: if (app.sideloaded) "unknown (sideloaded)" else "—")
        if (app.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (reason in app.reasons) {
                    Text("• $reason", color = TInkDimClr, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        TButton("OPEN IN SETTINGS", onClick = { openExternal(context, StalkwareScanner.appDetailsIntent(app.packageName)) })
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

    ToolPageList(title = "CERTIFICATES & TLS", onBack = onBack) {
        item(key = "§tls-hdr") { TSectionLabel("TLS CANARY") }
        item(key = "§tls") {
            val r = tls
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Relay connection", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Opens TLS to the comms relay and compares the server's key with the one pinned " +
                            "on first contact. An intercepting proxy cannot present the same key.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    when {
                        r == null -> Unit
                        r.intercepted -> TBadge("INTERCEPTED", TCriticalClr)
                        r.proxyOnly -> TBadge("PROXY", TCautionClr)
                        r.error != null && r.observedSpki == null -> TBadge("NO RESULT", TCautionClr)
                        r.pinnedNow -> TBadge("PINNED", TBlueClr)
                        else -> TBadge("CLEAR", TClearClr)
                    }
                }
                val lastRun = last
                if (lastRun != null) {
                    TKv("Last result", lastRun.second, if (lastRun.second.startsWith("INTERCEPTED")) TCriticalClr else TInkDimClr)
                    if (lastRun.first > 0) TKv("Checked", tFull(lastRun.first))
                } else {
                    TKv("Last result", "never run")
                }
                pinned?.let { (host, pin) -> TKv("Pinned ($host)", pin.take(24) + "…") }
                if (r != null) {
                    r.relayHost?.let { TKv("Relay", it) }
                    r.observedSpki?.let { TKv("Observed key", it.take(24) + "…") }
                    r.observedIssuer?.let { TKv("Issuer", it) }
                    r.chainTrusted?.let { TKv("Chain trusted", if (it) "yes" else "no", if (it) TClearClr else TCriticalClr) }
                    r.proxyHost?.let { TKv("Proxy", it, TCautionClr) }
                    r.method?.let {
                        val tone = when {
                            r.intercepted -> TCriticalClr
                            r.proxyOnly -> TCautionClr
                            else -> TInkDimClr
                        }
                        Text(it, color = tone, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    r.error?.let { Text(it, color = TCautionClr, fontSize = 12.sp, lineHeight = 16.sp) }
                }
                if (checking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr)
                } else {
                    TButton("CHECK NOW", onClick = { checkNow() })
                }
                if (pinned != null) {
                    TextButton(
                        onClick = { TlsCanary.resetPin(context); pinned = null },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("FORGET PIN (RELAY ROTATED ITS KEY)", color = TMutedClr, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        item(key = "§ca-hdr") { TSectionLabel("TRUST STORE AUDIT") }
        val list = cas
        when {
            list == null -> item(key = "§ca-progress") { TProgress("Reading the trust store…") }
            list.isEmpty() -> item(key = "§ca-clean") {
                TCard {
                    Text("No unexpected root certificates", color = TClearClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Nothing was installed by hand, and every system root is one Android ships with. " +
                        "A hand-installed root is how HTTPS traffic is read through a proxy by someone " +
                        "who has had the phone and its PIN.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
            else -> items(list, key = { "ca|${it.fingerprint}" }) { ca ->
                TCard(spacing = 4) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ca.subjectCn, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        TBadge(ca.source.uppercase(Locale.US), tSeverityColor(ca.severity))
                    }
                    TKv("Issuer", CaAuditScanner.cn(ca.issuerDn) ?: ca.issuerDn)
                    TKv("SHA-256", ca.fingerprint.take(23) + "…")
                    TKv("Valid", "${tDay(ca.validFrom)} – ${tDay(ca.validTo)}")
                    if (ca.reason.isNotBlank()) {
                        Text(ca.reason, color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
