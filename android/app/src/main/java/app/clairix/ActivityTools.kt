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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
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

// The Activity tab's analysis pages: pattern of life and the unlock ledger.

// ── 4. Pattern of life ─────────────────────────────────────────────────────────

@Composable
internal fun PatternOfLifePage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PatternReport?>(null) }
    var fixes by remember { mutableIntStateOf(0) }
    var analysing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }

    fun analyse() {
        if (analysing) return
        analysing = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    fixes = LocationHistory.size(context)
                    PatternOfLife.analyze(context)
                }
            }
            r.onSuccess { report = it }.onFailure { error = "Analysis failed: ${it.javaClass.simpleName}: ${it.message}" }
            analysing = false
        }
    }

    LaunchedEffect(Unit) { analyse() }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = TPanelClr,
            title = { Text("Clear location history?", color = TInkClr, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This deletes the $fixes stored GPS fixes the route analysis reads. Detections and " +
                    "the timeline are not affected. This cannot be undone.",
                    color = TInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { LocationHistory.clear(context) } }
                        fixes = 0
                        report = null
                        analyse()
                    }
                }) {
                    Text("CLEAR", color = TCriticalClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text("CANCEL", color = TMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    ToolPageList(title = "PATTERN OF LIFE", onBack = onBack) {
        if (analysing) item(key = "§progress") { TProgress("Analysing $fixes fixes — stops, departures and routes…") }
        error?.let { e -> item(key = "§error") { TNotice(TCriticalClr, "ERROR", e) } }

        val r = report
        if (r != null && !analysing) {
            if (r.fixes == 0 || r.clusters.isEmpty()) {
                item(key = "§empty") {
                    TCard {
                        Text("Not enough history yet", color = TInkDimClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The analysis needs a few days of fixes with the scanner running and a GPS lock. " +
                            "Stops are found from the fixes alone; nothing is sent anywhere.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                        )
                        TKv("Fixes recorded", "$fixes")
                        TKv("Days covered", "${r.daysCovered}")
                    }
                }
            } else {
                item(key = "§predict") {
                    val score = r.predictabilityScore.coerceIn(0f, 1f)
                    val label = when {
                        score < 0.25f -> "Low"
                        score < 0.5f -> "Moderate"
                        score < 0.75f -> "High"
                        else -> "Clockwork"
                    }
                    TCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Predictability", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$label · ${(score * 100).toInt()}%", color = tMeterColor(score),
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(TRuleClr)) {
                            Box(Modifier.fillMaxWidth(score.coerceAtLeast(0.02f)).fillMaxHeight().background(tMeterColor(score)))
                        }
                        Text(
                            "How well someone who watched for a week could guess where you will be and when. " +
                            "0 is unpredictable, 100 is clockwork.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                        TKv(
                            "Weekday departure window",
                            if (r.weekdayDepartureWindowMin > 0) "${r.weekdayDepartureWindowMin} min" else "too few to say"
                        )
                        TKv("Route repetition", if (r.trips > 0) "%.0f%%".format(Locale.US, r.routeRepetitionPct) else "no trips yet")
                        TKv("Trips between places", "${r.trips}")
                        TKv("Days covered", "${r.daysCovered}")
                        TKv("Fixes analysed", "${r.fixes}")
                    }
                }

                item(key = "§places-hdr") { TSectionLabel("PLACES") }
                items(r.clusters, key = { "place|${it.id}" }) { c ->
                    TCard(spacing = 4) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "%.5f, %.5f".format(Locale.US, c.lat, c.lon),
                                color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        TKv("Visits", "${c.visits} on ${c.days} ${if (c.days == 1) "day" else "days"}")
                        TKv("Time here", "${c.dwellMinutes / 60} h ${c.dwellMinutes % 60} min")
                        TKv("Seen", "${tDay(c.firstSeen)} – ${tDay(c.lastSeen)}")
                        if (c.departures.isNotEmpty()) {
                            Text("Usual departures", color = TMutedClr, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            for (d in c.departures) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(d.label, color = TInkDimClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text("${d.samples}×", color = TMutedClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                if (r.suggestions.isNotEmpty()) {
                    item(key = "§suggest-hdr") { TSectionLabel("SUGGESTIONS") }
                    item(key = "§suggest") {
                        TCard(spacing = 6) {
                            for (s in r.suggestions) {
                                Text("• $s", color = TInkDimClr, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }

        item(key = "§data-hdr") { TSectionLabel("DATA") }
        item(key = "§data") {
            TCard {
                TKv("Fixes recorded", "$fixes of ${LocationHistory.CAP}")
                Text(
                    "The GPS log the analysis reads. It stays on this phone and is also removed by " +
                    "CLEAR ALL DATA in Settings.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                TButton("ANALYSE AGAIN", onClick = { analyse() }, enabled = !analysing)
                TButton("CLEAR HISTORY", onClick = { showClear = true }, style = TButtonStyle.CRITICAL, enabled = fixes > 0 && !analysing)
            }
        }
    }
}

// ── 5. Unlock ledger ───────────────────────────────────────────────────────────

private const val LEDGER_WINDOW_MS = 7L * 24 * 3600_000L
private const val LEDGER_MAX_ROWS = 200

private fun unlockIcon(kind: UnlockKind): ImageVector = when (kind) {
    UnlockKind.PASSWORD_SUCCEEDED -> Icons.Filled.Done
    UnlockKind.PASSWORD_FAILED -> Icons.Filled.Close
    UnlockKind.PASSWORD_CHANGED -> Icons.Filled.Edit
    UnlockKind.KEYGUARD_HIDDEN -> ClairixIcons.LockOpen
    UnlockKind.KEYGUARD_SHOWN -> Icons.Filled.Lock
    UnlockKind.SCREEN_INTERACTIVE -> ClairixIcons.LightMode
    UnlockKind.SCREEN_NON_INTERACTIVE -> ClairixIcons.DarkMode
    UnlockKind.ADMIN_ENABLED -> Icons.Filled.Add
    UnlockKind.ADMIN_DISABLED -> ClairixIcons.Remove
}

private fun unlockLabel(kind: UnlockKind): String = when (kind) {
    UnlockKind.PASSWORD_SUCCEEDED -> "Unlocked with credential"
    UnlockKind.PASSWORD_FAILED -> "Failed unlock attempt"
    UnlockKind.PASSWORD_CHANGED -> "Screen lock credential changed"
    UnlockKind.KEYGUARD_HIDDEN -> "Unlocked"
    UnlockKind.KEYGUARD_SHOWN -> "Locked"
    UnlockKind.SCREEN_INTERACTIVE -> "Screen on"
    UnlockKind.SCREEN_NON_INTERACTIVE -> "Screen off"
    UnlockKind.ADMIN_ENABLED -> "Ledger device admin enabled"
    UnlockKind.ADMIN_DISABLED -> "Ledger device admin disabled"
}

@Composable
internal fun UnlockLedgerPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adminActive by remember { mutableStateOf(UnlockLedger.isAdminActive(context)) }
    var usageAccess by remember { mutableStateOf(UnlockLedger.hasUsageAccess(context)) }
    var events by remember { mutableStateOf<List<UnlockEvent>?>(null) }
    var sleepStart by remember { mutableStateOf(UnlockLedger.sleepStartMinutes(context).toString()) }
    var sleepEnd by remember { mutableStateOf(UnlockLedger.sleepEndMinutes(context).toString()) }
    var sleepError by remember { mutableStateOf<String?>(null) }
    var sleepSaved by remember { mutableStateOf(false) }
    var tripwire by remember { mutableStateOf<BiometricTripwireResult?>(null) }
    val recent by UnlockLedger.recentFlow.collectAsStateWithLifecycle()

    fun reload() {
        scope.launch {
            events = withContext(Dispatchers.IO) {
                runCatching {
                    UnlockLedger.init(context)
                    UnlockLedger.backfill(context)
                    UnlockLedger.events(System.currentTimeMillis() - LEDGER_WINDOW_MS).take(LEDGER_MAX_ROWS)
                }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        tripwire = withContext(Dispatchers.IO) {
            runCatching { BiometricTripwire.check(context) }
                .getOrElse { BiometricTripwireResult.UNAVAILABLE("Could not test the key (${it.javaClass.simpleName})") }
        }
    }
    // A new row landing (admin callback or a backfill elsewhere) refreshes the list.
    LaunchedEffect(recent?.id) { if (events != null) reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Both grants happen on Android's own pages, so they are re-read on return.
        adminActive = UnlockLedger.isAdminActive(context)
        usageAccess = UnlockLedger.hasUsageAccess(context)
    }

    val startMin = sleepStart.toIntOrNull()
    val endMin = sleepEnd.toIntOrNull()

    ToolPageList(title = "UNLOCK LEDGER", onBack = onBack) {
        item(key = "§sources-hdr") { TSectionLabel("SOURCES") }
        item(key = "§sources") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Device admin", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Reports every successful and failed unlock and any PIN change as it happens. " +
                            "Clairix never locks, wipes or sets a policy with it.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (adminActive) "ACTIVE" else "OFF", if (adminActive) TClearClr else TCautionClr)
                }
                if (!adminActive) {
                    TButton("ENABLE DEVICE ADMIN", onClick = { openExternal(context, UnlockLedger.requestAdminIntent(context)) })
                }
                HorizontalDivider(color = TRuleClr, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Usage access", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Lets the ledger read the platform's own lock, unlock and screen events for the " +
                            "last few days, including from before the admin was enabled.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (usageAccess) "GRANTED" else "OFF", if (usageAccess) TClearClr else TCautionClr)
                }
                if (!usageAccess) {
                    TButton("GRANT USAGE ACCESS", onClick = { openExternal(context, UnlockLedger.usageAccessIntent()) })
                }
            }
        }

        item(key = "§tripwire") {
            val t = tripwire
            TCard(spacing = 4) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Biometric tripwire", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "A Keystore key that dies the moment a fingerprint or face is added or removed.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    when (t) {
                        null -> TBadge("CHECKING", TMutedClr)
                        BiometricTripwireResult.OK -> TBadge("INTACT", TClearClr)
                        BiometricTripwireResult.FIRST_INIT -> TBadge("ARMED", TBlueClr)
                        is BiometricTripwireResult.TRIGGERED -> TBadge("TRIGGERED", TCriticalClr)
                        is BiometricTripwireResult.UNAVAILABLE -> TBadge("UNAVAILABLE", TCautionClr)
                    }
                }
                when (t) {
                    is BiometricTripwireResult.TRIGGERED ->
                        Text("The enrolled biometrics changed; noticed ${tFull(t.ts)}.", color = TCriticalClr, fontSize = 12.sp, lineHeight = 16.sp)
                    is BiometricTripwireResult.UNAVAILABLE ->
                        Text(t.reason, color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    BiometricTripwireResult.FIRST_INIT ->
                        Text("Armed just now against the biometrics enrolled at this moment.", color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    else -> Unit
                }
            }
        }

        item(key = "§sleep-hdr") { TSectionLabel("SLEEP WINDOW") }
        item(key = "§sleep") {
            TCard {
                Text(
                    "An unlock inside this window is reported as an anomaly. Times are minutes after " +
                    "midnight (23:00 is 1380, 07:00 is 420); the window may cross midnight.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TField(
                        value = sleepStart,
                        onValueChange = { sleepStart = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "Start (min)", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)
                    )
                    TField(
                        value = sleepEnd,
                        onValueChange = { sleepEnd = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "End (min)", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)
                    )
                }
                TKv(
                    "Window",
                    if (startMin != null && endMin != null && startMin in 0 until 1440 && endMin in 0 until 1440)
                        "${clock(startMin)} – ${clock(endMin)}"
                    else "—"
                )
                sleepError?.let { Text(it, color = TCriticalClr, fontSize = 12.sp) }
                if (sleepSaved) Text("Saved", color = TClearClr, fontSize = 12.sp)
                TButton("SAVE SLEEP WINDOW", onClick = {
                    if (startMin == null || endMin == null || startMin !in 0 until 1440 || endMin !in 0 until 1440) {
                        sleepError = "Both values must be between 0 and 1439"
                    } else {
                        UnlockLedger.setSleepWindow(context, startMin, endMin)
                        sleepSaved = true
                    }
                })
            }
        }

        item(key = "§events-hdr") { TSectionLabel("LAST 7 DAYS") }
        val list = events
        when {
            list == null -> item(key = "§events-progress") { TProgress("Reading the ledger…") }
            list.isEmpty() -> item(key = "§events-empty") {
                TCard {
                    Text("No events recorded yet", color = TInkDimClr, fontSize = 13.sp)
                    Text(
                        "Rows appear once the device admin is active or usage access is granted and the " +
                        "phone has been locked and unlocked.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
            else -> items(list, key = { "ev|${it.id}" }) { e ->
                val anomaly = e.kind.isUnlock && UnlockLedger.inSleepWindow(e.ts)
                val tone = when {
                    anomaly -> TCautionClr
                    e.kind == UnlockKind.PASSWORD_FAILED || e.kind == UnlockKind.PASSWORD_CHANGED || e.kind == UnlockKind.ADMIN_DISABLED -> TCriticalClr
                    e.kind.isUnlock -> TInkClr
                    else -> TMutedClr
                }
                Row(
                    Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(unlockIcon(e.kind), contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(unlockLabel(e.kind) + if (anomaly) " · in sleep window" else "", color = tone, fontSize = 13.sp)
                        Text(
                            tFull(e.ts) + (e.extra?.let { "  ·  $it" } ?: ""),
                            color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
