package app.clairix

import android.bluetooth.BluetoothManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.detect.BleCanary
import app.clairix.detect.BleCanaryState
import app.clairix.detect.DultInterrogator
import app.clairix.detect.DultResult
import app.clairix.detect.LocatorState
import app.clairix.detect.RssiLocator
import app.clairix.detect.RssiLocatorView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.ui.semantics.Role
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.LoadingBlock
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
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
                        if (d.following) StatusPill("Following", Tone.Danger)
                        else StatusPill(
                            d.threat.name,
                            when (d.threat) {
                                Threat.CRITICAL -> Tone.Danger; Threat.HIGH -> Tone.Accent
                                Threat.MEDIUM -> Tone.Caution; Threat.LOW -> Tone.Neutral; Threat.NONE -> Tone.Safe
                            }
                        )
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

@Composable
private fun TrackerLocator(detection: Detection, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Registry.status.collectAsStateWithLifecycle()
    val flow = remember(detection.address) { RssiLocator.track(detection.address, context) }
    val state by flow.collectAsStateWithLifecycle<LocatorState?>(initialValue = null)

    var soundJob by remember { mutableStateOf<Job?>(null) }
    var soundState by remember { mutableStateOf<DultResult?>(null) }
    var soundBusy by remember { mutableStateOf(false) }
    val canBeep = detection.dult?.answered == true

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

    ToolPageList(title = detection.tracker?.label?.uppercase(Locale.US) ?: "Locate", onBack = onBack) {
        item(key = "§who") {
            ClairixCard(spacing = 4.dp) {
                Text(detection.tracker?.label ?: detection.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                detection.dultLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) }
                KeyValueRow("Address", detection.address)
                KeyValueRow("Last RSSI", if (detection.rssi == 0) "—" else "${detection.rssi} dBm")
                if (detection.following) KeyValueRow("Status", "FOLLOWING", valueColor = ClairixTheme.colors.danger)
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
        item(key = "§beep") {
            val r = soundState
            ClairixCard {
                Text("Make it beep", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    if (canBeep)
                        "This tracker answered over the DULT non-owner service, so it is away from its owner " +
                        "and will play a sound on request."
                    else
                        "Only a tracker that has answered over the DULT non-owner service can be asked to " +
                        "sound. This one has not — it may still be with its owner, or not support DULT.",
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
                    PrimaryButton("Make it beep", onClick = { sound(true) }, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
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
    is BleCanaryState.Detected -> "Detected by the scanner at ${s.rssi} dBm (${s.address})"
    BleCanaryState.Stopped -> "Stopped — the minute is up"
    is BleCanaryState.Error -> "Error: ${s.message}"
}

private fun canaryTone(s: BleCanaryState): Tone = when (s) {
    BleCanaryState.Starting -> Tone.Neutral
    is BleCanaryState.Advertising -> Tone.Info
    is BleCanaryState.Detected -> Tone.Safe
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
                                detected -> "Passed — the detector heard the beacon"
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
                        detected -> StatusPill("Detected", Tone.Safe)
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
        }

        item(key = "§meaning-hdr") { SectionHeader("What the results mean") }
        item(key = "§meaning") {
            ClairixCard(spacing = 6.dp) {
                Text(
                    "Detected means the scanner received the fake AirTag's advertisement and matched its " +
                    "random key: the whole chain from radio to classifier is working on this phone. The " +
                    "address shown is the random one the Bluetooth stack chose for the test.",
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
