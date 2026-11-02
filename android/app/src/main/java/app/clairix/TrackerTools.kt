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

    ToolPageList(title = "Find a tracker", onBack = onBack) {
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, "The locator listens to the scanner's packets. Start scanning in Scan > Trackers first.", title = "Scanner off")
            }
        }
        item(key = "§intro") {
            Text(
                "Pick a detection, then hold the phone in front of you and turn slowly. The signal is " +
                "strongest when the phone faces the tracker and weakest when your body is in the way.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (ordered.isEmpty()) {
            item(key = "§empty") {
                ClairixCard {
                    Text("Nothing detected right now", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text("Detections appear here as the scanner finds them; trusted devices are left out.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
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
                        .clickable(onClickLabel = "Find this tracker", role = Role.Button) { selectedKey = d.key }
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
                            d.resolved -> StatusPill("Found", Tone.Neutral)
                            d.following -> StatusPill(followingPill(d), d.threat.tone())
                            else -> StatusPill(levelLabel(d.threat.name), d.threat.tone())
                        }
                        Text(
                            if (d.rssi == 0) "— dBm" else "${d.rssi} dBm",
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
                soundState = DultResult(detection.address, error = "Bluetooth is unavailable or the address is malformed", complete = true)
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
            title = "Make it beep?",
            onDismiss = { confirmBeep = false },
            confirmLabel = "Make it beep",
            onConfirm = {
                confirmBeep = false
                sound(true)
            },
            text = "The tracker will play a sound that anyone near it can hear, including the person who " +
                "put it there if they are close by. If they might be, find it silently with the compass first.",
            confirmTone = Tone.Danger,
            dismissLabel = "Not now"
        )
    }

    ToolPageList(title = detection.tracker?.label?.uppercase(Locale.US) ?: "Locate", onBack = onBack) {
        item(key = "§who") {
            ClairixCard(spacing = 4.dp) {
                Text(detection.tracker?.label ?: detection.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                detection.dultLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) }
                KeyValueRow("Address", detection.address)
                KeyValueRow("Last RSSI", if (detection.rssi == 0) "—" else "${detection.rssi} dBm")
                if (detection.resolved) {
                    KeyValueRow("Status", "MARKED FOUND", valueColor = ClairixTheme.colors.textMuted)
                } else if (detection.following) {
                    // A recognised tracker follows; anything else only travels with you.
                    val tracker = detection.threat == Threat.CRITICAL
                    KeyValueRow(
                        "Status", if (tracker) "FOLLOWING" else "TRAVELLING WITH YOU",
                        valueColor = if (tracker) ClairixTheme.colors.danger else ClairixTheme.colors.caution
                    )
                }
            }
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, "No packets arrive while the scanner is stopped. Start it in Scan > Trackers.", title = "Scanner off")
            }
        }
        item(key = "§dial") {
            val s = state
            if (s == null) {
                LoadingBlock("Listening for ${detection.address}…")
            } else {
                Column(Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(12.dp)) {
                    RssiLocatorView(state = s, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Samples", "${s.samples}")
                    KeyValueRow("Confidence", "${(s.confidence * 100).toInt()}%")
                    s.lastRssi?.let { KeyValueRow("Last RSSI", "$it dBm") }
                    s.lastSeenAgoMs?.let { KeyValueRow("Last heard", if (it < 1000) "just now" else "${it / 1000} s ago") }
                }
            }
        }
        item(key = "§cues") {
            ClairixCard(spacing = 0.dp) {
                ToggleRow(
                    "Pulse as the signal gets stronger",
                    checked = pulse,
                    onCheckedChange = { pulse = it },
                    subtitle = "The phone vibrates faster the closer the tracker is, so you can search without watching the screen."
                )
                ToggleRow(
                    "Click with each pulse",
                    checked = click,
                    onCheckedChange = { click = it },
                    subtitle = "A short click from the speaker. Anyone nearby can hear it."
                )
            }
        }
        item(key = "§identify") {
            val line = DultSchedule.statusLine(
                detection.dult, DultSchedule.qualifies(detection), DultSchedule.dultCapable(detection.tracker)
            ) { askedClockFmt.format(Date(it)) }
            ClairixCard {
                Text("Ask it who it is", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "A tracker built to the industry standard for unwanted tracking (DULT) names its maker and " +
                        "model, and gives a serial its maker can trace, once it has been away from its owner for a " +
                        "while. Clairix asks on its own when one stays near you or is confirmed following, again as " +
                        "soon as it separates from its owner, and every 15 minutes while it stays, up to six times.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    line ?: "Not asked yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (detection.dult?.refused == true) ClairixTheme.colors.caution else ClairixTheme.colors.textMuted
                )
                SecondaryButton(
                    if (asking) "Asking…" else "Ask it who it is",
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
                Text("Make it beep", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    if (canBeep)
                        "This tracker answered over the DULT non-owner service, so it is away from its owner " +
                        "and will play a sound on request. Anyone near it will hear the sound, including the " +
                        "person who put it there if they are close by."
                    else
                        "Only a tracker that has answered over the DULT non-owner service can be asked to " +
                        "sound. This one has not yet: it may still be with its owner, or not support DULT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                if (r != null) {
                    when {
                        r.error != null -> Text(r.error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution)
                        r.soundStarted -> Text("Beeping — follow the sound.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.safe)
                        r.complete -> Text("Command sent.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    }
                }
                if (soundBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ClairixTheme.colors.accent, trackColor = ClairixTheme.colors.border)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Make it beep", onClick = { confirmBeep = true }, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                    SecondaryButton("Stop sound", onClick = { sound(false) }, tone = Tone.Accent, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
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

    ToolPageList(title = "Self-test beacon", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "The phone advertises as an AirTag that has been separated from its owner for sixty " +
                "seconds, with a random key, and the detector has to notice. The scanner recognises " +
                "the payload as its own and never turns it into a detection or an alert.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                Notice(Tone.Caution, "The test can only be heard by the running scanner. Start scanning in Scan > Trackers first.", title = "Scanner off")
            }
        }
        item(key = "§control") {
            ClairixCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> "Running — ${((startedAt + CANARY_DURATION_MS - now) / 1000L).coerceAtLeast(0L)} s left"
                                transitions.isEmpty() -> "Ready"
                                classified -> "Passed — heard and recognised as an AirTag"
                                detected -> "Failed — heard, but not recognised as an AirTag"
                                failed -> "Failed — see below"
                                advertising -> "Advertised, not heard"
                                else -> "Finished"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text
                        )
                        if (running) {
                            Text("Keep the phone where it is; the scanner needs a few seconds.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                        }
                    }
                    when {
                        running -> StatusPill("Live", Tone.Info)
                        classified -> StatusPill("Detected", Tone.Safe)
                        detected -> StatusPill("Not recognised", Tone.Danger)
                        failed -> StatusPill("Error", Tone.Danger)
                        advertising -> StatusPill("Not heard", Tone.Caution)
                        else -> Unit
                    }
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { ((now - startedAt).toFloat() / CANARY_DURATION_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = ClairixTheme.colors.accent, trackColor = ClairixTheme.colors.border
                    )
                    SecondaryButton("Stop", onClick = { stop() }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton("Start 60 s test", onClick = { start() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (transitions.isNotEmpty()) {
            item(key = "§log-hdr") { SectionHeader("What happened") }
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
                        "The advertiser worked, but this phone's Bluetooth controller does not report its own " +
                            "packets to the scanner — many do not. A second phone running Clairix nearby is what " +
                            "proves the scanner; it should show an AirTag during the test.",
                        title = "Not heard"
                    )
                }
            }
            if (detected && !classified) {
                item(key = "§misclassified") {
                    Notice(
                        Tone.Danger,
                        "The scanner received the test advertisement, but the tracker signatures did not " +
                            "recognise it as the separated AirTag it imitates, so a real one would be missed too. " +
                            "Please report this with the phone's model and Android version.",
                        title = "Not recognised"
                    )
                }
            }
        }

        item(key = "§meaning-hdr") { SectionHeader("What the results mean") }
        item(key = "§meaning") {
            ClairixCard(spacing = 6.dp) {
                Text(
                    "Detected means the scanner received the fake AirTag's advertisement, matched its random " +
                    "key, and ran it through the same tracker signatures every packet goes through, which " +
                    "recognised it as a separated AirTag: the radio and the classifier are working on this " +
                    "phone. It does not test the follow check, which needs you to travel with a device, and the " +
                    "test beacon never becomes a detection or an alert. The address shown is the random one the " +
                    "Bluetooth stack chose for the test.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Text(
                    "Advertising without Detected means the beacon went on the air but this controller does " +
                    "not hear itself. That is a property of the chip, not a fault in the detector.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
                Text(
                    "An error before advertising means Bluetooth is off, advertising is not permitted, or " +
                    "this phone cannot advertise at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }
    }
}
