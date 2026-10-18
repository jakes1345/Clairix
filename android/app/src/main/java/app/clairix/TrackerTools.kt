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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

    ToolPageList(title = "FIND A TRACKER", onBack = onBack) {
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "The locator listens to the scanner's packets. Start scanning in Scan > Trackers first.")
            }
        }
        item(key = "§intro") {
            Text(
                "Pick a detection, then hold the phone in front of you and turn slowly. The signal is " +
                "strongest when the phone faces the tracker and weakest when your body is in the way.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        if (ordered.isEmpty()) {
            item(key = "§empty") {
                TCard {
                    Text("Nothing detected right now", color = TInkDimClr, fontSize = 13.sp)
                    Text("Detections appear here as the scanner finds them; trusted devices are left out.", color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        } else {
            items(ordered, key = { "det|${it.key}" }) { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TCardShape)
                        .background(TPanelClr, TCardShape)
                        .clickable { selectedKey = d.key }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.tracker?.label ?: d.name, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            d.dultLabel ?: d.summary, color = TMutedClr, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(d.address, color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.following) TBadge("FOLLOWING", TCriticalClr)
                        else TBadge(d.threat.name, when (d.threat) {
                            Threat.CRITICAL -> TCriticalClr; Threat.HIGH -> TAccentClr
                            Threat.MEDIUM -> TCautionClr; Threat.LOW -> TMutedClr; Threat.NONE -> TClearClr
                        })
                        Text(
                            if (d.rssi == 0) "— dBm" else "${d.rssi} dBm",
                            color = TInkDimClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TMutedClr)
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

    ToolPageList(title = detection.tracker?.label?.uppercase(Locale.US) ?: "LOCATE", onBack = onBack) {
        item(key = "§who") {
            TCard(spacing = 4) {
                Text(detection.tracker?.label ?: detection.name, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                detection.dultLabel?.let { Text(it, color = TInkDimClr, fontSize = 12.sp) }
                TKv("Address", detection.address)
                TKv("Last RSSI", if (detection.rssi == 0) "—" else "${detection.rssi} dBm")
                if (detection.following) TKv("Status", "FOLLOWING", TCriticalClr)
            }
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "No packets arrive while the scanner is stopped. Start it in Scan > Trackers.")
            }
        }
        item(key = "§dial") {
            val s = state
            if (s == null) {
                TProgress("Listening for ${detection.address}…")
            } else {
                Column(Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(12.dp)) {
                    RssiLocatorView(state = s, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TKv("Samples", "${s.samples}")
                    TKv("Confidence", "${(s.confidence * 100).toInt()}%")
                    s.lastRssi?.let { TKv("Last RSSI", "$it dBm") }
                    s.lastSeenAgoMs?.let { TKv("Last heard", if (it < 1000) "just now" else "${it / 1000} s ago") }
                }
            }
        }
        item(key = "§beep") {
            val r = soundState
            TCard {
                Text("Make it beep", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (canBeep)
                        "This tracker answered over the DULT non-owner service, so it is away from its owner " +
                        "and will play a sound on request."
                    else
                        "Only a tracker that has answered over the DULT non-owner service can be asked to " +
                        "sound. This one has not — it may still be with its owner, or not support DULT.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                if (r != null) {
                    when {
                        r.error != null -> Text(r.error, color = TCautionClr, fontSize = 12.sp)
                        r.soundStarted -> Text("Beeping — follow the sound.", color = TClearClr, fontSize = 12.sp)
                        r.complete -> Text("Command sent.", color = TInkDimClr, fontSize = 12.sp)
                    }
                }
                if (soundBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton("MAKE IT BEEP", onClick = { sound(true) }, style = TButtonStyle.PRIMARY, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                    TButton("STOP SOUND", onClick = { sound(false) }, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
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
    is BleCanaryState.Detected -> "DETECTED by the scanner at ${s.rssi} dBm (${s.address})"
    BleCanaryState.Stopped -> "Stopped — the minute is up"
    is BleCanaryState.Error -> "Error: ${s.message}"
}

private fun canaryColor(s: BleCanaryState): Color = when (s) {
    BleCanaryState.Starting -> TMutedClr
    is BleCanaryState.Advertising -> TBlueClr
    is BleCanaryState.Detected -> TClearClr
    BleCanaryState.Stopped -> TInkDimClr
    is BleCanaryState.Error -> TCriticalClr
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

    ToolPageList(title = "SELF-TEST BEACON", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "The phone advertises as an AirTag that has been separated from its owner for sixty " +
                "seconds, with a random key, and the detector has to notice. The scanner recognises " +
                "the payload as its own and never turns it into a detection or an alert.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "The test can only be heard by the running scanner. Start scanning in Scan > Trackers first.")
            }
        }
        item(key = "§control") {
            TCard {
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
                            color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (running) {
                            Text("Keep the phone where it is; the scanner needs a few seconds.", color = TMutedClr, fontSize = 12.sp)
                        }
                    }
                    when {
                        running -> TBadge("LIVE", TBlueClr)
                        detected -> TBadge("DETECTED", TClearClr)
                        failed -> TBadge("ERROR", TCriticalClr)
                        advertising -> TBadge("NOT HEARD", TCautionClr)
                        else -> Unit
                    }
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { ((now - startedAt).toFloat() / CANARY_DURATION_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr
                    )
                    TButton("STOP", onClick = { stop() }, style = TButtonStyle.CRITICAL)
                } else {
                    TButton("START 60 S TEST", onClick = { start() }, style = TButtonStyle.PRIMARY)
                }
            }
        }

        if (transitions.isNotEmpty()) {
            item(key = "§log-hdr") { TSectionLabel("WHAT HAPPENED") }
            itemsIndexed(transitions, key = { i, _ -> "t|$i" }) { _, (ts, state) ->
                Row(
                    Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tTime(ts), color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(canaryLabel(state), color = canaryColor(state), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                }
            }
            if (!running && advertising && !detected && !failed) {
                item(key = "§caveat") {
                    TNotice(
                        TCautionClr, "NOT HEARD",
                        "The advertiser worked, but this phone's Bluetooth controller does not report its own " +
                        "packets to the scanner — many do not. A second phone running Clairix nearby is what " +
                        "proves the scanner; it should show an AirTag during the test."
                    )
                }
            }
        }

        item(key = "§meaning-hdr") { TSectionLabel("WHAT THE RESULTS MEAN") }
        item(key = "§meaning") {
            TCard(spacing = 6) {
                Text(
                    "DETECTED means the scanner received the fake AirTag's advertisement and matched its " +
                    "random key: the whole chain from radio to classifier is working on this phone. The " +
                    "address shown is the random one the Bluetooth stack chose for the test.",
                    color = TInkDimClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Text(
                    "ADVERTISING without DETECTED means the beacon went on the air but this controller does " +
                    "not hear itself. That is a property of the chip, not a fault in the detector.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Text(
                    "An ERROR before advertising means Bluetooth is off, advertising is not permitted, or " +
                    "this phone cannot advertise at all.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}
