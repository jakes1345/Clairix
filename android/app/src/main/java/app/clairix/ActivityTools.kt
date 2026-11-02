package app.clairix

import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.LocationCluster
import app.clairix.analysis.LocationHistory
import app.clairix.analysis.PatternOfLife
import app.clairix.analysis.PatternReport
import app.clairix.analysis.clock
import app.clairix.security.AppLock
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
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.SafetyFirst
import app.clairix.ui.SafetyFirstCard

// The Activity tab's analysis pages: pattern of life and the unlock ledger.
// Both are a watcher's brief, so with a passcode set each asks for it every time
// it opens (OwnerGate); with none set each says plainly that anyone can read it.

// ── 4. Pattern of life ─────────────────────────────────────────────────────────

@Composable
internal fun PatternOfLifePage(onBack: (() -> Unit)? = null) {
    // Nothing is read from the location log until the page is open.
    OwnerGate(title = "Pattern of life", purpose = "open Patterns", onBack = onBack) {
        PatternOfLifeContent(onBack)
    }
}

@Composable
private fun PatternOfLifeContent(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PatternReport?>(null) }
    var fixes by remember { mutableIntStateOf(0) }
    var analysing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }
    var mapPlace by remember { mutableStateOf<LocationCluster?>(null) }
    val lockEnabled by AppLock.enabled.collectAsStateWithLifecycle()
    var settingPasscode by remember { mutableStateOf(false) }

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

    mapPlace?.let { place ->
        PlaceMapDialog(label = place.label, lat = place.lat, lon = place.lon, onDismiss = { mapPlace = null })
    }

    ToolPageList(title = "Pattern of life", onBack = onBack) {
        if (!lockEnabled || settingPasscode) item(key = "§no-passcode") {
            NoPasscodeBanner(setting = settingPasscode, onSettingChange = { settingPasscode = it })
        }
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
                        // A name, not coordinates: the owner knows where home is, and a
                        // glance at this card should not hand the address to anyone else.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                            GhostButton("Show on map", onClick = { mapPlace = c })
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

/** What the ledger asks as a source is switched on, before it judges anything. */
private const val SLEEP_QUESTION = "When do you usually sleep?"

/** The two times the sleep window is set with: asleep from, awake at. */
@OptIn(ExperimentalMaterial3Api::class)
private class SleepPickers(val start: TimePickerState, val end: TimePickerState) {
    val startMinutes: Int get() = start.hour * 60 + start.minute
    val endMinutes: Int get() = end.hour * 60 + end.minute
}

/** Pickers starting at [startMinutes] and [endMinutes] after midnight, in the phone's 12- or 24-hour style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberSleepPickers(startMinutes: Int, endMinutes: Int): SleepPickers {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    return SleepPickers(
        rememberTimePickerState(startMinutes / 60, startMinutes % 60, is24),
        rememberTimePickerState(endMinutes / 60, endMinutes % 60, is24)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepPickerFields(p: SleepPickers) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Asleep from", style = MaterialTheme.typography.labelMedium, color = ClairixTheme.colors.textMuted)
        TimeInput(p.start)
        Text("Awake at", style = MaterialTheme.typography.labelMedium, color = ClairixTheme.colors.textMuted)
        TimeInput(p.end)
    }
}

/**
 * "When do you usually sleep?", asked as the device admin or usage access is about to be switched
 * on, before the ledger judges anything; [onAnswer] gets the two times as minutes after midnight.
 */
@Composable
private fun SleepQuestionDialog(
    startMinutes: Int,
    endMinutes: Int,
    error: String?,
    onAnswer: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pickers = rememberSleepPickers(startMinutes, endMinutes)
    ClairixDialog(
        title = SLEEP_QUESTION,
        text = "An unlock between these times is reported as someone else using the phone. You can change " +
            "them later on this page.",
        confirmLabel = "Continue",
        onConfirm = { onAnswer(pickers.startMinutes, pickers.endMinutes) },
        onDismiss = onDismiss
    ) {
        SleepPickerFields(pickers)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
    }
}

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
    OwnerGate(title = "Unlock ledger", purpose = "open the unlock ledger", onBack = onBack) {
        UnlockLedgerContent(onBack)
    }
}

@Composable
private fun UnlockLedgerContent(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lockEnabled by AppLock.enabled.collectAsStateWithLifecycle()
    var settingPasscode by remember { mutableStateOf(false) }
    var adminActive by remember { mutableStateOf(UnlockLedger.isAdminActive(context)) }
    var usageAccess by remember { mutableStateOf(UnlockLedger.hasUsageAccess(context)) }
    var events by remember { mutableStateOf<List<UnlockEvent>?>(null) }
    // The ledger judges nothing until the owner has said when they sleep; switching a source on asks first.
    var sleepAnswered by remember { mutableStateOf(UnlockLedger.sleepWindowAnswered(context)) }
    /** The grant page to open once "When do you usually sleep?" is answered. */
    var askThen by remember { mutableStateOf<Intent?>(null) }
    // Counts saves: the pickers below start again from what was saved (the dialog's answer, say).
    var sleepSaves by remember { mutableIntStateOf(0) }
    val sleep = key(sleepSaves) { rememberSleepPickers(UnlockLedger.sleepStartMinutes(context), UnlockLedger.sleepEndMinutes(context)) }
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

    /**
     * Saves the sleep window; the first answer also has the ledger judge the days it held back,
     * now, with these hours. False, with the reason shown, for two equal times, which is no window.
     */
    fun saveSleep(startMinutes: Int, endMinutes: Int): Boolean {
        if (startMinutes == endMinutes) {
            sleepError = "Asleep from and awake at must be different times"
            return false
        }
        val first = !sleepAnswered
        UnlockLedger.setSleepWindow(context, startMinutes, endMinutes)
        sleepAnswered = true
        sleepError = null
        sleepSaved = true
        sleepSaves++
        if (first) scope.launch {
            withContext(Dispatchers.IO) { runCatching { UnlockLedger.checkSleepWindow(context) } }
            reload()
        }
        return true
    }

    /** Opens a source's grant page, asking when the owner sleeps first if nobody has said. */
    fun switchOn(grant: Intent) {
        if (sleepAnswered) openExternal(context, grant) else askThen = grant
    }

    askThen?.let { grant ->
        SleepQuestionDialog(
            startMinutes = UnlockLedger.sleepStartMinutes(context),
            endMinutes = UnlockLedger.sleepEndMinutes(context),
            error = sleepError,
            onAnswer = { start, end ->
                if (saveSleep(start, end)) {
                    askThen = null
                    openExternal(context, grant)
                }
            },
            onDismiss = { askThen = null; sleepError = null }
        )
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

    ToolPageList(title = "Unlock ledger", onBack = onBack) {
        if (!lockEnabled || settingPasscode) item(key = "§no-passcode") {
            NoPasscodeBanner(setting = settingPasscode, onSettingChange = { settingPasscode = it })
        }
        item(key = "§sources-hdr") { SectionHeader("Sources") }
        item(key = "§sources") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Device admin", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            "Reports every successful and failed unlock and any PIN change as it happens. " +
                            "Clairix locks the phone with it only when you press \"Schedule and lock\" on the " +
                            "Fake call page; it never wipes or sets a policy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (adminActive) "Active" else "Off", if (adminActive) Tone.Safe else Tone.Caution)
                }
                if (!adminActive) {
                    SecondaryButton("Enable device admin", onClick = { switchOn(UnlockLedger.requestAdminIntent(context)) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
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
                    SecondaryButton("Grant usage access", onClick = { switchOn(UnlockLedger.usageAccessIntent()) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
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

        // An added fingerprint or an unlock while you slept means someone has the phone's
        // way in. Changing the PIN or removing the fingerprint can tell them it was seen.
        val sleepUnlocks = events?.count { it.kind.isUnlock && UnlockLedger.inSleepWindow(it.ts) } ?: 0
        if (SafetyFirst.forLedger(tripwire is BiometricTripwireResult.TRIGGERED, sleepUnlocks)) {
            item(key = "§safety-first") { SafetyFirstCard(title = SafetyFirst.TITLE_PHONE) }
        }

        item(key = "§sleep-hdr") { SectionHeader(if (sleepAnswered) "Sleep window" else SLEEP_QUESTION) }
        item(key = "§sleep") {
            // Unanswered with a source on (usage access granted from another page, say): asked here.
            ClairixCard(tone = if (!sleepAnswered && (adminActive || usageAccess)) Tone.Caution else null) {
                Text(
                    if (sleepAnswered) "An unlock between these times is reported as someone else using the phone. " +
                        "The window may cross midnight."
                    else "An unlock between these times will be reported as someone else using the phone. Nothing " +
                        "is judged until you answer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                key(sleepSaves) { SleepPickerFields(sleep) }
                KeyValueRow("Window", "${clock(sleep.startMinutes)} – ${clock(sleep.endMinutes)}")
                if (askThen == null) sleepError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
                // "Saved" only while the pickers still show what was saved.
                val unchanged = sleep.startMinutes == UnlockLedger.sleepStartMinutes(context) &&
                    sleep.endMinutes == UnlockLedger.sleepEndMinutes(context)
                if (sleepSaved && unchanged) Text("Saved", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.safe)
                SecondaryButton(
                    if (sleepAnswered) "Save sleep window" else "Save",
                    onClick = { saveSleep(sleep.startMinutes, sleep.endMinutes) },
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
