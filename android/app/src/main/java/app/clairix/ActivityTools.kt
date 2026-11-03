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
import androidx.compose.ui.res.stringResource
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
    OwnerGate(title = stringResource(R.string.safety_pattern_of_life), purpose = stringResource(R.string.safety_open_patterns), onBack = onBack) {
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
            r.onSuccess { report = it }.onFailure { error = context.getString(R.string.safety_analysis_failed, it.javaClass.simpleName, it.message.toString()) }
            analysing = false
        }
    }

    LaunchedEffect(Unit) { analyse() }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            shape = ClairixShapes.Sheet,
            containerColor = ClairixTheme.colors.surfaceRaised,
            title = { Text(stringResource(R.string.safety_clear_location_history), style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text) },
            text = {
                Text(
                    stringResource(R.string.safety_this_deletes_the_stored_gps_fixes, fixes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    stringResource(R.string.comms_clear),
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
                GhostButton(stringResource(R.string.settings_cancel), onClick = { showClear = false }, tone = Tone.Neutral)
            }
        )
    }

    mapPlace?.let { place ->
        PlaceMapDialog(label = place.label, lat = place.lat, lon = place.lon, onDismiss = { mapPlace = null })
    }

    ToolPageList(title = stringResource(R.string.safety_pattern_of_life), onBack = onBack) {
        if (!lockEnabled || settingPasscode) item(key = "§no-passcode") {
            NoPasscodeBanner(setting = settingPasscode, onSettingChange = { settingPasscode = it })
        }
        if (analysing) item(key = "§progress") { LoadingBlock(stringResource(R.string.safety_analysing_fixes_stops_departures_and_routes, fixes)) }
        error?.let { e -> item(key = "§error") { Notice(Tone.Danger, e, title = stringResource(R.string.scan_error_2)) } }

        val r = report
        if (r != null && !analysing) {
            if (r.fixes == 0 || r.clusters.isEmpty()) {
                item(key = "§empty") {
                    ClairixCard {
                        Text(stringResource(R.string.safety_not_enough_history_yet), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.textMuted)
                        Text(
                            stringResource(R.string.safety_the_analysis_needs_a_few_days),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                        KeyValueRow(stringResource(R.string.safety_fixes_recorded), "$fixes")
                        KeyValueRow(stringResource(R.string.safety_days_covered), "${r.daysCovered}")
                    }
                }
            } else {
                item(key = "§predict") {
                    val score = r.predictabilityScore.coerceIn(0f, 1f)
                    val label = when {
                        score < 0.25f -> stringResource(R.string.safety_low)
                        score < 0.5f -> stringResource(R.string.safety_moderate)
                        score < 0.75f -> stringResource(R.string.scan_high_2)
                        else -> stringResource(R.string.safety_clockwork)
                    }
                    ClairixCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.safety_predictability), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
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
                            stringResource(R.string.safety_how_well_someone_who_watched_for),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                        KeyValueRow(
                            stringResource(R.string.safety_weekday_departure_window),
                            if (r.weekdayDepartureWindowMin > 0) "${r.weekdayDepartureWindowMin} min" else stringResource(R.string.safety_too_few_to_say)
                        )
                        KeyValueRow(stringResource(R.string.safety_route_repetition), if (r.trips > 0) "%.0f%%".format(Locale.US, r.routeRepetitionPct) else stringResource(R.string.safety_no_trips_yet))
                        KeyValueRow(stringResource(R.string.safety_trips_between_places), "${r.trips}")
                        KeyValueRow(stringResource(R.string.safety_days_covered), "${r.daysCovered}")
                        KeyValueRow(stringResource(R.string.safety_fixes_analysed), "${r.fixes}")
                    }
                }

                item(key = "§places-hdr") { SectionHeader(stringResource(R.string.safety_places)) }
                items(r.clusters, key = { "place|${it.id}" }) { c ->
                    ClairixCard(spacing = 4.dp) {
                        // A name, not coordinates: the owner knows where home is, and a
                        // glance at this card should not hand the address to anyone else.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                            GhostButton(stringResource(R.string.safety_show_on_map), onClick = { mapPlace = c })
                        }
                        KeyValueRow(stringResource(R.string.safety_visits), "${c.visits} on ${c.days} ${if (c.days == 1) "day" else "days"}")
                        KeyValueRow(stringResource(R.string.safety_time_here), stringResource(R.string.settings_h_min, c.dwellMinutes / 60, c.dwellMinutes % 60))
                        KeyValueRow(stringResource(R.string.safety_seen), "${tDay(c.firstSeen)} – ${tDay(c.lastSeen)}")
                        if (c.departures.isNotEmpty()) {
                            Text(stringResource(R.string.safety_usual_departures), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle, modifier = Modifier.padding(top = 4.dp))
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
                    item(key = "§suggest-hdr") { SectionHeader(stringResource(R.string.safety_suggestions)) }
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

        item(key = "§data-hdr") { SectionHeader(stringResource(R.string.settings_data)) }
        item(key = "§data") {
            ClairixCard {
                KeyValueRow(stringResource(R.string.safety_fixes_recorded), "$fixes of ${LocationHistory.CAP}")
                Text(
                    stringResource(R.string.safety_the_gps_log_the_analysis_reads),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                SecondaryButton(stringResource(R.string.safety_analyse_again), onClick = { analyse() }, tone = Tone.Accent, enabled = !analysing, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.safety_clear_history), onClick = { showClear = true }, tone = Tone.Danger, enabled = fixes > 0 && !analysing, modifier = Modifier.fillMaxWidth())
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
        Text(stringResource(R.string.safety_asleep_from), style = MaterialTheme.typography.labelMedium, color = ClairixTheme.colors.textMuted)
        TimeInput(p.start)
        Text(stringResource(R.string.safety_awake_at), style = MaterialTheme.typography.labelMedium, color = ClairixTheme.colors.textMuted)
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
        text = stringResource(R.string.safety_an_unlock_between_these_times_is),
        confirmLabel = stringResource(R.string.settings_continue),
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
    OwnerGate(title = stringResource(R.string.safety_unlock_ledger), purpose = stringResource(R.string.safety_open_the_unlock_ledger), onBack = onBack) {
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
            sleepError = context.getString(R.string.safety_asleep_from_and_awake_at_must)
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

    ToolPageList(title = stringResource(R.string.safety_unlock_ledger), onBack = onBack) {
        if (!lockEnabled || settingPasscode) item(key = "§no-passcode") {
            NoPasscodeBanner(setting = settingPasscode, onSettingChange = { settingPasscode = it })
        }
        item(key = "§sources-hdr") { SectionHeader(stringResource(R.string.safety_sources)) }
        item(key = "§sources") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.scan_device_admin), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.safety_reports_every_successful_and_failed_unlock),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (adminActive) stringResource(R.string.safety_active) else stringResource(R.string.comms_off), if (adminActive) Tone.Safe else Tone.Caution)
                }
                if (!adminActive) {
                    SecondaryButton(stringResource(R.string.safety_enable_device_admin), onClick = { switchOn(UnlockLedger.requestAdminIntent(context)) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = ClairixTheme.colors.border, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.safety_usage_access), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.safety_lets_the_ledger_read_the_platforms),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    StatusPill(if (usageAccess) stringResource(R.string.safety_granted) else stringResource(R.string.comms_off), if (usageAccess) Tone.Safe else Tone.Caution)
                }
                if (!usageAccess) {
                    SecondaryButton(stringResource(R.string.scan_grant_usage_access), onClick = { switchOn(UnlockLedger.usageAccessIntent()) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "§tripwire") {
            val t = tripwire
            ClairixCard(spacing = 4.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.safety_biometric_tripwire), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                        Text(
                            stringResource(R.string.safety_a_keystore_key_that_dies_the),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                    when (t) {
                        null -> StatusPill(stringResource(R.string.scan_checking), Tone.Neutral)
                        BiometricTripwireResult.OK -> StatusPill(stringResource(R.string.safety_intact), Tone.Safe)
                        BiometricTripwireResult.FIRST_INIT -> StatusPill(stringResource(R.string.safety_armed), Tone.Info)
                        is BiometricTripwireResult.TRIGGERED -> StatusPill(stringResource(R.string.safety_triggered), Tone.Danger)
                        is BiometricTripwireResult.UNAVAILABLE -> StatusPill(stringResource(R.string.safety_unavailable), Tone.Caution)
                    }
                }
                when (t) {
                    is BiometricTripwireResult.TRIGGERED ->
                        Text(stringResource(R.string.safety_the_enrolled_biometrics_changed_noticed, tFull(t.ts)), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
                    is BiometricTripwireResult.UNAVAILABLE ->
                        Text(t.reason, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    BiometricTripwireResult.FIRST_INIT ->
                        Text(stringResource(R.string.safety_armed_just_now_against_the_biometrics), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
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

        item(key = "§sleep-hdr") { SectionHeader(if (sleepAnswered) stringResource(R.string.safety_sleep_window) else SLEEP_QUESTION) }
        item(key = "§sleep") {
            // Unanswered with a source on (usage access granted from another page, say): asked here.
            ClairixCard(tone = if (!sleepAnswered && (adminActive || usageAccess)) Tone.Caution else null) {
                Text(
                    if (sleepAnswered) stringResource(R.string.safety_an_unlock_between_these_times_is_2)
                    else stringResource(R.string.safety_an_unlock_between_these_times_will),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                key(sleepSaves) { SleepPickerFields(sleep) }
                KeyValueRow(stringResource(R.string.safety_window), "${clock(sleep.startMinutes)} – ${clock(sleep.endMinutes)}")
                if (askThen == null) sleepError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger) }
                // "Saved" only while the pickers still show what was saved.
                val unchanged = sleep.startMinutes == UnlockLedger.sleepStartMinutes(context) &&
                    sleep.endMinutes == UnlockLedger.sleepEndMinutes(context)
                if (sleepSaved && unchanged) Text(stringResource(R.string.safety_saved), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.safe)
                SecondaryButton(
                    if (sleepAnswered) stringResource(R.string.safety_save_sleep_window) else stringResource(R.string.settings_save),
                    onClick = { saveSleep(sleep.startMinutes, sleep.endMinutes) },
                    tone = Tone.Accent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item(key = "§events-hdr") { SectionHeader(stringResource(R.string.safety_last_7_days)) }
        val list = events
        when {
            list == null -> item(key = "§events-progress") { LoadingBlock(stringResource(R.string.safety_reading_the_ledger)) }
            list.isEmpty() -> item(key = "§events-empty") {
                ClairixCard {
                    Text(stringResource(R.string.safety_no_events_recorded_yet), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        stringResource(R.string.safety_rows_appear_once_the_device_admin),
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
                        Text(unlockLabel(e.kind) + if (anomaly) stringResource(R.string.safety_in_sleep_window) else "", style = MaterialTheme.typography.bodyMedium, color = tone)
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
