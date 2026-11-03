package app.clairix

import android.bluetooth.BluetoothManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.clairix.detect.BleCanary
import app.clairix.detect.BleCanaryState
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.DultSchedule
import app.clairix.detect.LocatorCues
import app.clairix.detect.LocatorState
import app.clairix.detect.RssiLocator
import app.clairix.detect.RssiLocatorView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.ui.semantics.Role
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.components.Tone
import app.clairix.ui.components.color

// The Trackers category's actions: walking to a tracker by signal strength
// and the self-test beacon.

// ── 8. Find a tracker ──────────────────────────────────────────────────────────

@Composable
internal fun FindTrackerPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    var selectedKey by remember { mutableStateOf<String?>(null) }

    val ordered = remember(detections, trusted) {
        detections
            .filter { it.address !in trusted }
            .sortedWith(compareByDescending<Detection> { it.following }.thenByDescending { it.threat.ordinal }.thenByDescending { it.rssi })
    }
    val selected = ordered.firstOrNull { it.key == selectedKey }

    // Back from the locator goes to the list first, then the menu.
    BackHandler(enabled = selected != null) { selectedKey = null }

    if (selected != null) {
        TrackerLocator(detection = selected, onBack = { selectedKey = null })
        return
    }

    ToolPageList(title = stringResource(R.string.scan_find_a_tracker), onBack = onBack) {
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, stringResource(R.string.scan_the_locator_listens_to_the_scanners), title = stringResource(R.string.scan_scanner_off))
            }
        }
        item(key = "§intro") {
            Text(
                stringResource(R.string.scan_pick_a_detection_then_hold_the),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (ordered.isEmpty()) {
            item(key = "§empty") {
                ClairixCard {
                    Text(stringResource(R.string.scan_nothing_detected_right_now), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(stringResource(R.string.scan_detections_appear_here_as_the_scanner), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                }
            }
        } else {
            items(ordered, key = { "det|${it.key}" }) { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ClairixShapes.Card)
                        .background(ClairixTheme.colors.surface, ClairixShapes.Card)
                        .border(1.dp, ClairixTheme.colors.border, ClairixShapes.Card)
                        .clickable(onClickLabel = stringResource(R.string.scan_find_this_tracker), role = Role.Button) { selectedKey = d.key }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.tracker?.label ?: d.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            d.dultLabel ?: d.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(d.address, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            d.resolved -> StatusPill(stringResource(R.string.scan_found), Tone.Neutral)
                            d.following -> StatusPill(followingPill(d), d.threat.tone())
                            else -> StatusPill(levelLabel(d.threat.name), d.threat.tone())
                        }
                        Text(
                            if (d.rssi == 0) stringResource(R.string.scan_dbm) else "${d.rssi} dBm",
                            style = MaterialTheme.typography.monoSmall,
                            color = ClairixTheme.colors.textMuted
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ClairixTheme.colors.textSubtle)
                }
            }
        }
    }
}

/** "Asked 12:04": the time of day a DULT exchange began. */
private val askedClockFmt = SimpleDateFormat("HH:mm", Locale.US)

/** One pulse of the locator's haptic guidance, and its strength (1–255). */
private const val PULSE_MS = 30L
private const val PULSE_AMPLITUDE = 128
/** The optional audible click with each pulse, and its volume (0–100). */
private const val CLICK_MS = 40
private const val CLICK_VOLUME = 60
/** How often the guidance looks again while the tracker is not being heard. */
private const val PULSE_IDLE_MS = 500L

@Composable
private fun TrackerLocator(detection: Detection, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Registry.status.collectAsStateWithLifecycle()
    // Keyed on the device, not its address: when the scanner links an address change
    // the row brings the new address, and the locator follows it without losing the
    // directions it has already surveyed.
    val address = remember(detection.key) { MutableStateFlow(detection.address) }
    LaunchedEffect(detection.address) { address.value = detection.address }
    val flow = remember(detection.key) { RssiLocator.track(address, context) }
    val state by flow.collectAsStateWithLifecycle<LocatorState?>(initialValue = null)

    var soundJob by remember { mutableStateOf<Job?>(null) }
    var soundState by remember { mutableStateOf<DultResult?>(null) }
    var soundBusy by remember { mutableStateOf(false) }
    var confirmBeep by remember { mutableStateOf(false) }
    val canBeep = detection.dult?.answered == true
    val asking = detection.dult?.let { !it.complete && it.askedAt != 0L } == true

    // Guidance that needs no screen: a pulse, faster as the signal strengthens, and,
    // only when asked for, a click with it, which anyone nearby can hear. Only while
    // the page is started: in the background the locator stops listening, and a pulse
    // read off its last state would go on for nothing.
    var pulse by remember { mutableStateOf(true) }
    var click by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pulse, click, lifecycle) {
        if (!pulse && !click) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val vibrator = if (pulse) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
            } else null
            val tone = if (click) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CLICK_VOLUME) }.getOrNull() else null
            try {
                while (true) {
                    val period = latest?.let { LocatorCues.pulsePeriodMs(it) }
                    if (period == null) {
                        delay(PULSE_IDLE_MS)
                        continue
                    }
                    vibrator?.let { v -> runCatching { v.vibrate(VibrationEffect.createOneShot(PULSE_MS, PULSE_AMPLITUDE)) } }
                    tone?.let { t -> runCatching { t.startTone(ToneGenerator.TONE_PROP_BEEP, CLICK_MS) } }
                    delay(period)
                }
            } finally {
                tone?.release()
            }
        }
    }

    fun sound(start: Boolean) {
        soundJob?.cancel()
        soundBusy = true
        soundState = null
        soundJob = scope.launch {
            val device = runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(detection.address)
            }.getOrNull()
            if (device == null) {
                soundState = DultResult(detection.address, error = context.getString(R.string.scan_bluetooth_is_unavailable_or_the_address), complete = true)
                soundBusy = false
                return@launch
            }
            runCatching { DultInterrogator.sound(device, context, start).collect { soundState = it } }
                .onFailure { soundState = DultResult(detection.address, error = it.message ?: it.javaClass.simpleName, complete = true) }
            soundBusy = false
        }
    }

    // The sound is the one thing here that someone else can hear: say so before it plays.
    if (confirmBeep) {
        ClairixDialog(
            title = stringResource(R.string.scan_make_it_beep),
            onDismiss = { confirmBeep = false },
            confirmLabel = stringResource(R.string.scan_make_it_beep_2),
            onConfirm = {
                confirmBeep = false
                sound(true)
            },
            text = stringResource(R.string.scan_the_tracker_will_play_a_sound),
            confirmTone = Tone.Danger,
            dismissLabel = stringResource(R.string.comms_not_now)
        )
    }

    ToolPageList(title = detection.tracker?.label?.uppercase(Locale.US) ?: stringResource(R.string.scan_locate), onBack = onBack) {
        item(key = "§who") {
            ClairixCard(spacing = 4.dp) {
                Text(detection.tracker?.label ?: detection.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                detection.dultLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) }
                KeyValueRow(stringResource(R.string.scan_address), detection.address)
                KeyValueRow(stringResource(R.string.scan_last_rssi), if (detection.rssi == 0) "—" else "${detection.rssi} dBm")
                if (detection.resolved) {
                    KeyValueRow(stringResource(R.string.comms_status), stringResource(R.string.scan_marked_found_2), valueColor = ClairixTheme.colors.textMuted)
                } else if (detection.following) {
                    // A recognised tracker follows; anything else only travels with you.
                    val tracker = detection.threat == Threat.CRITICAL
                    KeyValueRow(
                        stringResource(R.string.comms_status), if (tracker) "FOLLOWING" else stringResource(R.string.scan_travelling_with_you_3),
                        valueColor = if (tracker) ClairixTheme.colors.danger else ClairixTheme.colors.caution
                    )
                }
            }
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, stringResource(R.string.scan_no_packets_arrive_while_the_scanner), title = stringResource(R.string.scan_scanner_off))
            }
        }
        item(key = "§dial") {
            val s = state
            if (s == null) {
                LoadingBlock(stringResource(R.string.scan_listening_for, detection.address))
            } else {
                Column(Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(12.dp)) {
                    RssiLocatorView(state = s, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow(stringResource(R.string.scan_samples), "${s.samples}")
                    KeyValueRow(stringResource(R.string.scan_confidence), "${(s.confidence * 100).toInt()}%")
                    s.lastRssi?.let { KeyValueRow(stringResource(R.string.scan_last_rssi), "$it dBm") }
                    s.lastSeenAgoMs?.let { KeyValueRow(stringResource(R.string.scan_last_heard), if (it < 1000) stringResource(R.string.comms_just_now) else stringResource(R.string.scan_s_ago, it / 1000)) }
                }
            }
        }
        item(key = "§cues") {
            ClairixCard(spacing = 0.dp) {
                ToggleRow(
                    stringResource(R.string.scan_pulse_as_the_signal_gets_stronger),
                    checked = pulse,
                    onCheckedChange = { pulse = it },
                    subtitle = stringResource(R.string.scan_the_phone_vibrates_faster_the_closer)
                )
                ToggleRow(
                    stringResource(R.string.scan_click_with_each_pulse),
                    checked = click,
                    onCheckedChange = { click = it },
                    subtitle = stringResource(R.string.scan_a_short_click_from_the_speaker)
                )
            }
        }
        item(key = "§identify") {
            val line = DultSchedule.statusLine(
                detection.dult, DultSchedule.qualifies(detection), DultSchedule.dultCapable(detection.tracker)
            ) { askedClockFmt.format(Date(it)) }
            ClairixCard {
                Text(stringResource(R.string.scan_ask_it_who_it_is), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.scan_a_tracker_built_to_the_industry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    line ?: stringResource(R.string.scan_not_asked_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (detection.dult?.refused == true) ClairixTheme.colors.caution else ClairixTheme.colors.textMuted
                )
                SecondaryButton(
                    if (asking) stringResource(R.string.scan_asking) else stringResource(R.string.scan_ask_it_who_it_is),
                    onClick = { ScanService.sendDultAsk(context, detection.key, detection.address) },
                    tone = Tone.Accent,
                    enabled = status.scanning && !asking,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item(key = "§beep") {
            val r = soundState
            ClairixCard {
                Text(stringResource(R.string.scan_make_it_beep_2), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    if (canBeep)
                        stringResource(R.string.scan_this_tracker_answered_over_the_dult)
                    else
                        stringResource(R.string.scan_only_a_tracker_that_has_answered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                if (r != null) {
                    when {
                        r.error != null -> Text(r.error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution)
                        r.soundStarted -> Text(stringResource(R.string.scan_beeping_follow_the_sound), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.safe)
                        r.complete -> Text(stringResource(R.string.scan_command_sent), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    }
                }
                if (soundBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ClairixTheme.colors.accent, trackColor = ClairixTheme.colors.border)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.scan_make_it_beep_2), onClick = { confirmBeep = true }, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.scan_stop_sound), onClick = { sound(false) }, tone = Tone.Accent, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── 9. Self-test beacon ────────────────────────────────────────────────────────

private const val CANARY_DURATION_MS = 60_000L

private fun canaryLabel(s: BleCanaryState): String = when (s) {
    BleCanaryState.Starting -> "Starting the advertiser"
    is BleCanaryState.Advertising -> "Advertising as a separated AirTag"
    is BleCanaryState.Detected -> "Heard by the scanner at ${s.rssi} dBm (${s.address}). " +
        "Classified as a separated AirTag: ${if (s.classifiedAsAirTag) "yes" else "no"}"
    BleCanaryState.Stopped -> "Stopped — the minute is up"
    is BleCanaryState.Error -> "Error: ${s.message}"
}

private fun canaryTone(s: BleCanaryState): Tone = when (s) {
    BleCanaryState.Starting -> Tone.Neutral
    is BleCanaryState.Advertising -> Tone.Info
    is BleCanaryState.Detected -> if (s.classifiedAsAirTag) Tone.Safe else Tone.Danger
    BleCanaryState.Stopped -> Tone.Neutral
    is BleCanaryState.Error -> Tone.Danger
}

@Composable
internal fun SelfTestPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Registry.status.collectAsStateWithLifecycle()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var transitions by remember { mutableStateOf<List<Pair<Long, BleCanaryState>>>(emptyList()) }
    val now = rememberToolTick(500L)

    fun start() {
        job?.cancel()
        transitions = emptyList()
        running = true
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            runCatching {
                BleCanary.start(context, CANARY_DURATION_MS).collect { state ->
                    transitions = transitions + (System.currentTimeMillis() to state)
                }
            }.onFailure { e ->
                transitions = transitions + (System.currentTimeMillis() to BleCanaryState.Error(e.message ?: e.javaClass.simpleName))
            }
            running = false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (running) transitions = transitions + (System.currentTimeMillis() to BleCanaryState.Stopped)
        running = false
    }

    val detected = transitions.any { it.second is BleCanaryState.Detected }
    // Heard, and taken by the tracker signatures for the separated AirTag it is built as.
    val classified = transitions.any { (it.second as? BleCanaryState.Detected)?.classifiedAsAirTag == true }
    val advertising = transitions.any { it.second is BleCanaryState.Advertising }
    val failed = transitions.lastOrNull()?.second is BleCanaryState.Error

    ToolPageList(title = stringResource(R.string.scan_self_test_beacon), onBack = onBack) {
        item(key = "§intro") {
            Text(
                stringResource(R.string.scan_the_phone_advertises_as_an_airtag),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, stringResource(R.string.scan_the_test_can_only_be_heard), title = stringResource(R.string.scan_scanner_off))
            }
        }
        item(key = "§control") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> stringResource(R.string.scan_running_s_left, ((startedAt + CANARY_DURATION_MS - now) / 1000L).coerceAtLeast(0L))
                                transitions.isEmpty() -> stringResource(R.string.scan_ready)
                                classified -> stringResource(R.string.scan_passed_heard_and_recognised_as_an)
                                detected -> stringResource(R.string.scan_failed_heard_but_not_recognised_as)
                                failed -> stringResource(R.string.scan_failed_see_below)
                                advertising -> stringResource(R.string.scan_advertised_not_heard)
                                else -> stringResource(R.string.scan_finished)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text
                        )
                        if (running) {
                            Text(stringResource(R.string.scan_keep_the_phone_where_it_is), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                        }
                    }
                    when {
                        running -> StatusPill(stringResource(R.string.comms_live), Tone.Info)
                        classified -> StatusPill(stringResource(R.string.scan_detected), Tone.Safe)
                        detected -> StatusPill(stringResource(R.string.scan_not_recognised), Tone.Danger)
                        failed -> StatusPill(stringResource(R.string.scan_error_2), Tone.Danger)
                        advertising -> StatusPill(stringResource(R.string.scan_not_heard), Tone.Caution)
                        else -> Unit
                    }
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { ((now - startedAt).toFloat() / CANARY_DURATION_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = ClairixTheme.colors.accent, trackColor = ClairixTheme.colors.border
                    )
                    SecondaryButton(stringResource(R.string.comms_stop), onClick = { stop() }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton(stringResource(R.string.scan_start_60_s_test), onClick = { start() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (transitions.isNotEmpty()) {
            item(key = "§log-hdr") { SectionHeader(stringResource(R.string.scan_what_happened)) }
            itemsIndexed(transitions, key = { i, _ -> "t|$i" }) { _, (ts, state) ->
                Row(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tTime(ts), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                    Text(canaryLabel(state), style = MaterialTheme.typography.bodyMedium, color = canaryTone(state).color(), modifier = Modifier.weight(1f))
                }
            }
            if (!running && advertising && !detected && !failed) {
                item(key = "§caveat") {
                    Notice(
                        Tone.Caution,
                        stringResource(R.string.scan_the_advertiser_worked_but_this_phones),
                        title = stringResource(R.string.scan_not_heard)
                    )
                }
            }
            if (detected && !classified) {
                item(key = "§misclassified") {
                    Notice(
                        Tone.Danger,
                        stringResource(R.string.scan_the_scanner_received_the_test_advertisement),
                        title = stringResource(R.string.scan_not_recognised)
                    )
                }
            }
        }

        item(key = "§meaning-hdr") { SectionHeader(stringResource(R.string.scan_what_the_results_mean)) }
        item(key = "§meaning") {
            ClairixCard(spacing = 6.dp) {
                Text(
                    stringResource(R.string.scan_detected_means_the_scanner_received_the),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Text(
                    stringResource(R.string.scan_advertising_without_detected_means_the_beacon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    stringResource(R.string.scan_an_error_before_advertising_means_bluetooth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
    }
}
