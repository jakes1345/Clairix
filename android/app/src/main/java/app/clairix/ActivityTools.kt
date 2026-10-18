package app.clairix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.PatternOfLife
import app.clairix.analysis.PatternReport
import app.clairix.analysis.clock
import app.clairix.security.BiometricTripwire
import app.clairix.security.BiometricTripwireResult
import app.clairix.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.mono
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone

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
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text("Clear location history?", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    "This deletes the $fixes stored GPS fixes the route analysis reads. Detections and " +
                    "the timeline are not affected. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Clear",
                    onClick = {
                        showClear = false
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { LocationHistory.clear(context) } }
                            fixes = 0
                            report = null
                            analyse()
                        }
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = {
                GhostButton("Cancel", onClick = { showClear = false }, tone = Tone.Neutral)
            }
        )
    }

    ToolPageList(title = "Pattern of life", onBack = onBack) {
        if (analysing) item(key = "§progress") { LoadingBlock("Analysing $fixes fixes — stops, departures and routes…") }
        error?.let { e -> item(key = "§error") { Notice(Tone.Danger, e, title = "Error") } }

        val r = report
        if (r != null && !analysing) {
            if (r.fixes == 0 || r.clusters.isEmpty()) {
                item(key = "§empty") {
                    ClairixCard {
                        Text("Not enough history yet", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.textMuted)
                        Text(
                            "The analysis needs a few days of fixes with the scanner running and a GPS lock. " +
                            "Stops are found from the fixes alone; nothing is sent anywhere.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                        KeyValueRow("Fixes recorded", "$fixes")
                        KeyValueRow("Days covered", "${r.daysCovered}")
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
                    ClairixCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Predictability", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                            Text(
                                "$label · ${(score * 100).toInt()}%",
                                style = MaterialTheme.typography.mono,
                                color = meterColor(score)
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(ClairixTheme.colors.border)) {
                            Box(Modifier.fillMaxWidth(score.coerceAtLeast(0.02f)).fillMaxHeight().background(meterColor(score)))
                        }
                        Text(
                            "How well someone who watched for a week could guess where you will be and when. " +
                            "0 is unpredictable, 100 is clockwork.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                        KeyValueRow(
                            "Weekday departure window",
                            if (r.weekdayDepartureWindowMin > 0) "${r.weekdayDepartureWindowMin} min" else "too few to say"
                        )
                        KeyValueRow("Route repetition", if (r.trips > 0) "%.0f%%".format(Locale.US, r.routeRepetitionPct) else "no trips yet")
                        KeyValueRow("Trips between places", "${r.trips}")
                        KeyValueRow("Days covered", "${r.daysCovered}")
                        KeyValueRow("Fixes analysed", "${r.fixes}")
                    }
                }

                item(key = "§places-hdr") { SectionHeader("Places") }
                items(r.clusters, key = { "place|${it.id}" }) { c ->
                    ClairixCard(spacing = 4.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                            Text(
                                "%.5f, %.5f".format(Locale.US, c.lat, c.lon),
                                style = MaterialTheme.typography.monoSmall,
                                color = ClairixTheme.colors.textSubtle
                            )
                        }
                        KeyValueRow("Visits", "${c.visits} on ${c.days} ${if (c.days == 1) "day" else "days"}")
                        KeyValueRow("Time here", "${c.dwellMinutes / 60} h ${c.dwellMinutes % 60} min")
                        KeyValueRow("Seen", "${tDay(c.firstSeen)} – ${tDay(c.lastSeen)}")
                        if (c.departures.isNotEmpty()) {
                            Text("Usual departures", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle, modifier = Modifier.padding(top = 4.dp))
                            for (d in c.departures) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(d.label, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
                                    Text("${d.samples}×", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                }
                            }
                        }
                    }
                }

                if (r.suggestions.isNotEmpty()) {
                    item(key = "§suggest-hdr") { SectionHeader("Suggestions") }
                    item(key = "§suggest") {
                        ClairixCard(spacing = 6.dp) {
                            for (s in r.suggestions) {
                                Text("• $s", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                            }
                        }
                    }
                }
            }
        }

        item(key = "§data-hdr") { SectionHeader("Data") }
        item(key = "§data") {
            ClairixCard {
                KeyValueRow("Fixes recorded", "$fixes of ${LocationHistory.CAP}")
                Text(
                    "The GPS log the analysis reads. It stays on this phone and is also removed by " +
                    "Clear all data in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                SecondaryButton("Analyse again", onClick = { analyse() }, tone = Tone.Accent, enabled = !analysing, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Clear history", onClick = { showClear = true }, tone = Tone.Danger, enabled = fixes > 0 && !analysing, modifier = Modifier.fillMaxWidth())
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

    ToolPageList(title = "Unlock ledger", onBack = onBack) {
        item(key = "§sources-hdr") { SectionHeader("Sources") }
        item(key = "§sources") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Device admin", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "Reports every successful and failed unlock and any PIN change as it happens. " +
                            "Clairix never locks, wipes or sets a policy with it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (adminActive) "Active" else "Off", if (adminActive) Tone.Safe else Tone.Caution)
                }
                if (!adminActive) {
                    SecondaryButton("Enable device admin", onClick = { openExternal(context, UnlockLedger.requestAdminIntent(context)) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = ClairixTheme.colors.border, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Usage access", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "Lets the ledger read the platform's own lock, unlock and screen events for the " +
                            "last few days, including from before the admin was enabled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (usageAccess) "Granted" else "Off", if (usageAccess) Tone.Safe else Tone.Caution)
                }
                if (!usageAccess) {
                    SecondaryButton("Grant usage access", onClick = { openExternal(context, UnlockLedger.usageAccessIntent()) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§tripwire") {
            val t = tripwire
            ClairixCard(spacing = 4.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Biometric tripwire", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "A Keystore key that dies the moment a fingerprint or face is added or removed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    when (t) {
                        null -> StatusPill("Checking", Tone.Neutral)
                        BiometricTripwireResult.OK -> StatusPill("Intact", Tone.Safe)
                        BiometricTripwireResult.FIRST_INIT -> StatusPill("Armed", Tone.Info)
                        is BiometricTripwireResult.TRIGGERED -> StatusPill("Triggered", Tone.Danger)
                        is BiometricTripwireResult.UNAVAILABLE -> StatusPill("Unavailable", Tone.Caution)
                    }
                }
                when (t) {
                    is BiometricTripwireResult.TRIGGERED ->
                        Text("The enrolled biometrics changed; noticed ${tFull(t.ts)}.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
                    is BiometricTripwireResult.UNAVAILABLE ->
                        Text(t.reason, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    BiometricTripwireResult.FIRST_INIT ->
                        Text("Armed just now against the biometrics enrolled at this moment.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    else -> Unit
                }
            }
        }

        item(key = "§sleep-hdr") { SectionHeader("Sleep window") }
        item(key = "§sleep") {
            ClairixCard {
                Text(
                    "An unlock inside this window is reported as an anomaly. Times are minutes after " +
                    "midnight (23:00 is 1380, 07:00 is 420); the window may cross midnight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClairixTextField(
                        value = sleepStart,
                        onValueChange = { sleepStart = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "Start (min)",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    ClairixTextField(
                        value = sleepEnd,
                        onValueChange = { sleepEnd = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "End (min)",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                }
                KeyValueRow(
                    "Window",
                    if (startMin != null && endMin != null && startMin in 0 until 1440 && endMin in 0 until 1440)
                            "${clock(startMin)} – ${clock(endMin)}"
                        else "—"
                )
                sleepError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
                if (sleepSaved) Text("Saved", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.safe)
                SecondaryButton(
                    "Save sleep window",
                    onClick = {
                        if (startMin == null || endMin == null || startMin !in 0 until 1440 || endMin !in 0 until 1440) {
                            sleepError = "Both values must be between 0 and 1439"
                        } else {
                            UnlockLedger.setSleepWindow(context, startMin, endMin)
                            sleepSaved = true
                        }
                    },
                    tone = Tone.Accent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item(key = "§events-hdr") { SectionHeader("Last 7 days") }
        val list = events
        when {
            list == null -> item(key = "§events-progress") { LoadingBlock("Reading the ledger…") }
            list.isEmpty() -> item(key = "§events-empty") {
                ClairixCard {
                    Text("No events recorded yet", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        "Rows appear once the device admin is active or usage access is granted and the " +
                        "phone has been locked and unlocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
            else -> items(list, key = { "ev|${it.id}" }) { e ->
                val anomaly = e.kind.isUnlock && UnlockLedger.inSleepWindow(e.ts)
                val tone = when {
                    anomaly -> ClairixTheme.colors.caution
                    e.kind == UnlockKind.PASSWORD_FAILED || e.kind == UnlockKind.PASSWORD_CHANGED || e.kind == UnlockKind.ADMIN_DISABLED -> ClairixTheme.colors.danger
                    e.kind.isUnlock -> ClairixTheme.colors.text
                    else -> ClairixTheme.colors.textSubtle
                }
                Row(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(unlockIcon(e.kind), contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(unlockLabel(e.kind) + if (anomaly) " · in sleep window" else "", style = MaterialTheme.typography.bodyMedium, color = tone)
                        Text(
                            tFull(e.ts) + (e.extra?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.monoSmall,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            }
        }
    }
}
