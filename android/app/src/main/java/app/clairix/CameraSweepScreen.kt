package app.clairix

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CameraFindMethod
import app.clairix.analysis.CameraFinding
import app.clairix.analysis.GlintBlob
import app.clairix.analysis.GlintTracker
import app.clairix.analysis.OpticalState
import app.clairix.analysis.HiddenCameraScanner
import app.clairix.analysis.LensGlintAnalyzer
import app.clairix.analysis.OpticalMode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import app.clairix.ui.components.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import app.clairix.ui.theme.monoSmall

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

private enum class SweepTab(val label: String) { OPTICAL("Optical"), NETWORK("Network"), WIRELESS("Wireless"), GUIDED("Guided") }

/**
 * The hidden-camera sweep: four tabs for the four ways a phone can look —
 * optically (lens glint, IR), on the Wi-Fi (RTSP/ONVIF/mDNS), over the air
 * (setup hotspots, BLE beacons) and by hand (the guided checklist with the
 * magnetometer gauge). Every method writes to [HiddenCameraScanner.findings];
 * each tab shows the rows its own methods produced.
 */
@Composable
fun CameraSweepScreen(onBack: (() -> Unit)? = null) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = SweepTab.entries[tabIndex.coerceIn(0, SweepTab.entries.size - 1)]
    val findings by HiddenCameraScanner.findings.collectAsStateWithLifecycle()
    val strong = findings.count { it.confidence >= 0.7f }

    Column(Modifier.fillMaxSize().background(ClairixTheme.colors.base)) {
        // Shown on its own screen it brings its own bar; under the shell it has one already.
        if (onBack != null) ClairixTopBar(title = "Hidden cameras", onBack = onBack)

        // ── Status first: what the sweep has found so far ──────────────────
        val tone = when {
            strong > 0 -> Tone.Danger
            findings.isNotEmpty() -> Tone.Caution
            else -> Tone.Neutral
        }
        ClairixCard(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            tone = if (tone == Tone.Neutral) null else tone,
            emphasis = strong > 0,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconWell(ClairixIcons.Videocam, if (tone == Tone.Neutral) Tone.Accent else tone, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            strong > 0 -> if (strong == 1) "1 strong finding" else "$strong strong findings"
                            findings.isNotEmpty() -> "${findings.size} to check"
                            else -> "Nothing found yet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = ClairixTheme.colors.text
                    )
                    Text(
                        "Lens glint, infrared, the Wi-Fi, the air and your hands",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        }

        SegmentedTabs(
            labels = SweepTab.entries.map { t ->
                val count = findings.count { it.method in methodsFor(t) }
                t.label + if (count > 0) " · $count" else ""
            },
            selected = tabIndex.coerceIn(0, SweepTab.entries.size - 1),
            onSelect = { tabIndex = it }
        )

        when (tab) {
            SweepTab.OPTICAL -> OpticalTab(findings)
            SweepTab.NETWORK -> NetworkTab(findings)
            SweepTab.WIRELESS -> WirelessTab(findings)
            SweepTab.GUIDED -> GuidedTab(findings)
        }
    }
}

private fun methodsFor(tab: SweepTab): Set<CameraFindMethod> = when (tab) {
    SweepTab.OPTICAL -> setOf(CameraFindMethod.LENS_GLINT, CameraFindMethod.IR_ILLUMINATOR)
    SweepTab.NETWORK -> setOf(CameraFindMethod.NETWORK)
    SweepTab.WIRELESS -> setOf(CameraFindMethod.WIFI_SETUP_AP, CameraFindMethod.BLE_SETUP_AP)
    SweepTab.GUIDED -> setOf(CameraFindMethod.MAGNETIC)
}

// ── OPTICAL ──────────────────────────────────────────────────────────────────

@Composable
private fun OpticalTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; askedOnce = true }
    val activity = ctx as? android.app.Activity
    // Re-read the permission on return from Settings.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    val blocked = askedOnce && !granted && activity != null &&
        !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    val mode = OpticalMode.entries[modeIndex.coerceIn(0, OpticalMode.entries.size - 1)]
    var live by rememberSaveable { mutableStateOf(true) }
    val state by HiddenCameraScanner.optical.collectAsStateWithLifecycle()
    val mine = findings.filter { it.method == CameraFindMethod.LENS_GLINT || it.method == CameraFindMethod.IR_ILLUMINATOR }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChoiceChips(
                choices = OpticalMode.entries,
                selected = mode,
                label = { it.label },
                onSelect = { modeIndex = OpticalMode.entries.indexOf(it) },
                modifier = Modifier.weight(1f)
            )
            GhostButton(if (live) "Pause" else "Resume", onClick = { live = !live }, tone = Tone.Neutral)
        }

        if (!granted) {
            ClairixCard(spacing = 4.dp) {
                Text("Camera permission", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "The optical sweep looks through the phone's camera for lenses looking back. Frames are analysed in memory and never saved or sent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Spacer(Modifier.height(8.dp))
                if (blocked) {
                    // Android no longer shows the prompt once it has been refused for good: only Settings can grant it.
                    PrimaryButton("Open app settings", onClick = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
                        )
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton("Allow camera", onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(ClairixShapes.Card).background(ClairixTheme.colors.base)
            ) {
                if (live) {
                    CameraViewfinder(mode)
                    GlintOverlay(state.tracks)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Paused", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    }
                }
                // Status strip
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(ClairixTheme.colors.base.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val status = when {
                        state.error != null -> state.error!!
                        !state.running -> "Opening camera…"
                        mode == OpticalMode.GLINT && !state.hasTorch -> "This camera has no torch, so the glint check cannot work"
                        state.checking -> "Checking a spot: torch off for a moment"
                        state.tooBright -> "Too bright for IR. Darken the room"
                        state.washedOut -> "Washed out. Back away from the surface"
                        state.tracks.any { it.candidate } -> "Steady reflection. Look for a pinhole"
                        state.tracks.any { !it.lightSource } -> "${state.tracks.count { !it.lightSource }} bright spot(s), hold still"
                        state.tracks.isNotEmpty() -> "Only light sources in view"
                        else -> if (mode == OpticalMode.GLINT) "Hold still over each area, torch on" else "Point at suspect objects"
                    }
                    val col = when {
                        state.error != null -> ClairixTheme.colors.danger
                        state.tracks.any { it.candidate } -> ClairixTheme.colors.danger
                        state.tooBright || state.washedOut -> ClairixTheme.colors.caution
                        else -> ClairixTheme.colors.textMuted
                    }
                    Text(
                        status, style = MaterialTheme.typography.labelMedium, color = col, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // Read out as it changes, so a spot turning red reaches screen-reader users too.
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Text(
                        "Y ${state.meanLuma.roundToInt()} · Δ${(state.lumaChange * 100).roundToInt()}% · ${state.fps.roundToInt()} fps",
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        }

        ClairixCard(spacing = 4.dp) {
            if (mode == OpticalMode.GLINT) {
                Text("How glint works", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "The torch is on. A lens, even a pinhole, sends light straight back along its own axis, so it shows as a tiny round spot. Hold the phone still over each suspect area for about a second. A spot that stays put is tested: the torch blinks off for a moment, and a reflection goes dark with it while a light (a standby LED, a screen) stays lit. Reflections turn red; lights are shown grey and never reported. Screw heads, glass and chrome flash too, but their highlights slide as you move.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            } else {
                Text("How the IR view works", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    "Torch off, front camera. Night-vision cameras light the room with 850 nm infrared LEDs the eye cannot see; some phones' front cameras let that light through as a white or violet dot. First check yours: point a TV remote at the front camera and press a button. If you see it flash here, this check works on your phone; if not, this phone cannot see infrared and an empty result means nothing. Then make the room as dark as you can (the view greys out if there is too much light) and point the screen side at smoke detectors, clocks, chargers and vents.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            }
        }

        FindingsSection(mine, methodsFor(SweepTab.OPTICAL), "No steady reflections recorded yet")
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Binds CameraX to the lifecycle for [mode]: back camera with torch for glint, front
 * camera dark for IR. Preview and analysis both ask for 4:3 so the overlay's
 * normalised coordinates land on the right pixels, and the view is FIT_CENTER
 * inside a 3:4 box so nothing is cropped away.
 */
@Composable
private fun CameraViewfinder(mode: OpticalMode) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    DisposableEffect(mode) {
        HiddenCameraScanner.opticalStarting(mode)
        var provider: ProcessCameraProvider? = null
        var cancelled = false
        // Set once the camera is bound; the analyzer asks for the torch through it.
        var control: androidx.camera.core.CameraControl? = null
        // The torch as the analyzer last asked for it: off only while it tests a spot.
        var torchWanted = mode.torch
        val analyzer = LensGlintAnalyzer(
            mode = mode, mirrored = mode.frontCamera,
            torch = if (mode.torch) { on ->
                torchWanted = on
                ContextCompat.getMainExecutor(ctx).execute { runCatching { control?.enableTorch(on) } }
            } else null,
            onState = { HiddenCameraScanner.publishOptical(it) },
            onCandidate = { HiddenCameraScanner.opticalCandidate(ctx, mode, it) }
        )
        // CameraX turns the torch off when the camera closes in the background and does not
        // turn it back on: do it on every resume.
        val resumeObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && mode.torch) runCatching { control?.enableTorch(torchWanted) }
        }
        owner.lifecycle.addObserver(resumeObserver)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (cancelled) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = if (mode.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(resolution).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                p.unbindAll()
                val camera = p.bindToLifecycle(owner, selector, preview, analysis)
                if (mode.torch && camera.cameraInfo.hasFlashUnit()) {
                    control = camera.cameraControl
                    camera.cameraControl.enableTorch(true)
                } else if (mode.torch) {
                    HiddenCameraScanner.publishOptical(OpticalState(mode = mode, running = true, torchOn = false, hasTorch = false))
                }
            } catch (e: Exception) {
                HiddenCameraScanner.opticalError(mode, e.message)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            cancelled = true
            owner.lifecycle.removeObserver(resumeObserver)
            analyzer.close()
            runCatching { provider?.unbindAll() }
            HiddenCameraScanner.opticalStopped(mode)
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GlintOverlay(tracks: List<GlintBlob>) {
    val palette = ClairixTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        for (b in tracks) {
            val c = Offset(b.x * size.width, b.y * size.height)
            val r = max(14.dp.toPx(), b.radius * min(size.width, size.height) * 3f)
            val col = when {
                b.candidate -> palette.danger
                b.lightSource -> palette.textMuted.copy(alpha = 0.3f)
                b.heldMs >= GlintTracker.HOLD_MS / 2 -> palette.caution
                else -> palette.textMuted.copy(alpha = 0.45f)
            }
            drawCircle(col, r, c, style = Stroke(width = if (b.candidate) 3.dp.toPx() else 1.5.dp.toPx()))
            if (b.candidate) {
                drawCircle(col.copy(alpha = 0.35f), r * 1.7f, c, style = Stroke(width = 1.dp.toPx()))
                drawLine(col, Offset(c.x - r * 1.9f, c.y), Offset(c.x - r * 1.2f, c.y), 1.5.dp.toPx())
                drawLine(col, Offset(c.x + r * 1.2f, c.y), Offset(c.x + r * 1.9f, c.y), 1.5.dp.toPx())
                drawLine(col, Offset(c.x, c.y - r * 1.9f), Offset(c.x, c.y - r * 1.2f), 1.5.dp.toPx())
                drawLine(col, Offset(c.x, c.y + r * 1.2f), Offset(c.x, c.y + r * 1.9f), 1.5.dp.toPx())
            }
        }
    }
}

// ── NETWORK ──────────────────────────────────────────────────────────────────

@Composable
private fun NetworkTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val progress by HiddenCameraScanner.network.collectAsStateWithLifecycle()
    val wifi by Registry.wifiStatus.collectAsStateWithLifecycle()
    val now = rememberNow(5_000L)
    val mine = findings.filter { it.method == CameraFindMethod.NETWORK }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        ClairixCard(spacing = 4.dp) {
            Text("This network", style = MaterialTheme.typography.labelSmall, color = ClairixTheme.colors.textSubtle)
            val conn = wifi.connection
            Text(
                conn?.ssid ?: progress.ssid ?: "Not connected to Wi-Fi",
                style = MaterialTheme.typography.titleMedium,
                color = if (conn != null || progress.ssid != null) ClairixTheme.colors.text else ClairixTheme.colors.textMuted
            )
            val sub = progress.subnet ?: conn?.ipv4?.let { "phone at $it" }
            if (sub != null) Text(sub, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
            Spacer(Modifier.height(8.dp))
            if (progress.running) {
                val frac = if (progress.hostsTotal > 0) progress.hostsDone.toFloat() / progress.hostsTotal else 0f
                ClairixProgressBar(frac)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(progress.phase, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text("${progress.hostsDone}/${progress.hostsTotal} · ${progress.liveHosts} live", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Cancel", onClick = { HiddenCameraScanner.cancelNetworkScan() }, modifier = Modifier.fillMaxWidth())
            } else {
                progress.error?.let { SweepWarning(it) }
                if (progress.lastScanTs > 0 && progress.error == null) {
                    Text(
                        "Last sweep ${fmtAgo(now, progress.lastScanTs)} · ${progress.liveHosts} live hosts · ${progress.found} camera-like",
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Sweep the Wi-Fi for cameras", onClick = { HiddenCameraScanner.startNetworkScan(ctx) }, modifier = Modifier.fillMaxWidth())
            }
        }

        ClairixCard(spacing = 4.dp) {
            Text("What it looks for", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                "Every address on the Wi-Fi's /24 is tried on the camera ports — RTSP (554, 8554), the Dahua and XMeye recorder ports (37777, 34567), the web ports — and whatever answers is asked for its banner. At the same time an ONVIF discovery probe goes out, which every ONVIF camera must answer, and mDNS is browsed for RTSP, Axis, PSIA, ONVIF and HomeKit camera services. A host with just a web page and no camera fingerprint is left out: that is every printer and router. Join the venue's Wi-Fi first — a guest network that isolates clients will hide everything, which is itself worth knowing.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
        }

        FindingsSection(mine, methodsFor(SweepTab.NETWORK), "No camera-like hosts found on the last sweep")
        Spacer(Modifier.height(24.dp))
    }
}

// ── WIRELESS ─────────────────────────────────────────────────────────────────

@Composable
private fun WirelessTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val state by HiddenCameraScanner.wireless.collectAsStateWithLifecycle()
    val now = rememberNow(1_000L)
    val mine = findings.filter { it.method == CameraFindMethod.WIFI_SETUP_AP || it.method == CameraFindMethod.BLE_SETUP_AP }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        ClairixCard(spacing = 4.dp) {
            Text("Wi-Fi setup hotspots", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                "A camera nobody has finished installing broadcasts its own hotspot — usually open, named for its maker with a few hex digits. The venue's own networks never look like that.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
            Spacer(Modifier.height(8.dp))
            state.wifiError?.let { SweepWarning(it) }
            if (state.wifiScannedTs > 0) {
                Text(
                    "Readings from ${fmtAgo(now, state.wifiScannedTs)} · ${state.wifiApsSeen} networks in range" +
                        if (now - state.wifiScannedTs > 30_000L) " · Android limits Wi-Fi scans to four every two minutes" else "",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textSubtle
                )
                Spacer(Modifier.height(6.dp))
            }
            PrimaryButton(
                if (state.wifiScanning) "Scanning…" else "Scan for setup hotspots",
                onClick = { HiddenCameraScanner.startWifiSetupScan(ctx) },
                enabled = !state.wifiScanning,
                loading = state.wifiScanning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ClairixCard(spacing = 4.dp) {
            Text("Bluetooth setup beacons", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                "Before it has Wi-Fi credentials a camera advertises over Bluetooth so an app can hand them over. Twenty seconds of listening catches everything in the room that is still waiting.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
            Spacer(Modifier.height(8.dp))
            state.bleError?.let { SweepWarning(it) }
            if (state.bleListening) {
                val left = ((state.bleUntilTs - now) / 1000L).coerceAtLeast(0L)
                Text(
                    "Listening · ${left}s left · ${state.bleDevicesSeen} devices heard",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.accent
                )
                Spacer(Modifier.height(6.dp))
                SecondaryButton("Stop", onClick = { HiddenCameraScanner.stopBleListening() }, modifier = Modifier.fillMaxWidth())
            } else {
                if (state.bleDevicesSeen > 0) {
                    Text("Last window heard ${state.bleDevicesSeen} devices", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                    Spacer(Modifier.height(6.dp))
                }
                SecondaryButton("Listen for 20 seconds", onClick = { HiddenCameraScanner.startBleListening(ctx, 20_000L) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
            }
        }

        FindingsSection(mine, methodsFor(SweepTab.WIRELESS), "Nothing in range is announcing itself as a camera")
        Spacer(Modifier.height(24.dp))
    }
}

// ── GUIDED ───────────────────────────────────────────────────────────────────

@Composable
private fun GuidedTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val done by HiddenCameraScanner.sweepDone.collectAsStateWithLifecycle()
    val mag by HiddenCameraScanner.magnetic.collectAsStateWithLifecycle()
    val mine = findings.filter { it.method == CameraFindMethod.MAGNETIC }
    val steps = HiddenCameraScanner.SWEEP_STEPS

    // The gauge stops with the tab: a magnetometer at 50 Hz is not free.
    DisposableEffect(Unit) { onDispose { HiddenCameraScanner.stopMagnetic() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Magnetometer gauge
        ClairixCard(spacing = 4.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Magnetic gauge", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                ClairixButton(
                    if (mag.active) "Stop" else "Start",
                    onClick = { if (mag.active) HiddenCameraScanner.stopMagnetic() else HiddenCameraScanner.startMagnetic(ctx) },
                    kind = if (mag.active) ButtonKind.Secondary else ButtonKind.Primary
                )
            }
            Spacer(Modifier.height(8.dp))
            mag.error?.let { SweepWarning(it) }
            if (mag.active) {
                MagneticBar(mag.fieldUt, mag.baselineUt, mag.thresholdUt, mag.triggered)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (mag.warmingUp) "Settling…" else if (mag.triggered) "Anomaly. Look here" else "Quiet",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (mag.triggered) ClairixTheme.colors.danger else if (mag.warmingUp) ClairixTheme.colors.textSubtle else ClairixTheme.colors.safe
                    )
                    Text(
                        "${mag.fieldUt.roundToInt()} µT · base ${mag.baselineUt.roundToInt()} · Δ${mag.deltaUt.roundToInt()} / ${mag.thresholdUt.roundToInt()}",
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                if (mag.unreliable) {
                    Text("Sensor reports itself unreliable — wave the phone in a figure of eight, away from metal.", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.caution)
                }
            } else {
                Text(
                    "Start the gauge, then slide the back of the phone slowly along the surfaces listed below. The baseline learns the room's field; a magnet inside something jumps the needle well past the trigger line.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            }
        }

        // Checklist
        SectionHeader(
            "Room sweep · ${done.count { id -> steps.any { it.id == id } }} of ${steps.size}",
            action = if (done.isNotEmpty()) {
                { GhostButton("Reset", onClick = { HiddenCameraScanner.resetSweep() }, tone = Tone.Neutral) }
            } else null
        )
        steps.forEachIndexed { i, step ->
            val checked = step.id in done
            Row(
                Modifier.fillMaxWidth()
                    .clip(ClairixShapes.Card)
                    .background(if (checked) ClairixTheme.colors.base else ClairixTheme.colors.surface, ClairixShapes.Card)
                    .border(1.dp, ClairixTheme.colors.border, ClairixShapes.Card)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { HiddenCameraScanner.toggleSweepStep(step.id) })
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = checked, onCheckedChange = null,
                    modifier = Modifier.padding(12.dp),
                    colors = CheckboxDefaults.colors(checkedColor = ClairixTheme.colors.safe, uncheckedColor = ClairixTheme.colors.textSubtle, checkmarkColor = ClairixTheme.colors.onSafe)
                )
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (checked) ClairixTheme.colors.textMuted else ClairixTheme.colors.text,
                            modifier = Modifier.weight(1f)
                        )
                        StatusPill(step.tool, Tone.Info)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = if (checked) ClairixTheme.colors.textSubtle else ClairixTheme.colors.textMuted)
                }
            }
        }

        // The honest limit
        Notice(Tone.Caution, HiddenCameraScanner.ANALOG_RF_NOTE, title = "What the phone can't see")

        FindingsSection(mine, methodsFor(SweepTab.GUIDED), "No magnetic anomalies recorded")
        Spacer(Modifier.height(24.dp))
    }
}

/** Field against baseline: the baseline sits at the centre, the trigger lines either side, the needle where the field is. */
@Composable
private fun MagneticBar(field: Float, baseline: Float, threshold: Float, triggered: Boolean) {
    val palette = ClairixTheme.colors
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val span = max(threshold * 2.5f, 60f)   // µT shown either side of the baseline
        val cx = size.width / 2f
        fun xOf(ut: Float): Float = (cx + (ut - baseline) / span * cx).coerceIn(0f, size.width)
        drawRoundRect(palette.border, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        if (threshold > 0f) {
            val l = xOf(baseline - threshold)
            val r = xOf(baseline + threshold)
            drawRect(palette.safe.copy(alpha = 0.18f), topLeft = Offset(l, 0f), size = androidx.compose.ui.geometry.Size(r - l, size.height))
            drawLine(palette.caution, Offset(l, 0f), Offset(l, size.height), 1.dp.toPx())
            drawLine(palette.caution, Offset(r, 0f), Offset(r, size.height), 1.dp.toPx())
        }
        drawLine(palette.textMuted, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
        val nx = xOf(field)
        drawLine(if (triggered) palette.danger else palette.text, Offset(nx, 2.dp.toPx()), Offset(nx, size.height - 2.dp.toPx()), 3.dp.toPx())
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun FindingsSection(rows: List<CameraFinding>, methods: Set<CameraFindMethod>, emptyText: String) {
    val strong = rows.count { it.confidence >= 0.7f }
    SectionHeader(
        "Findings",
        tone = if (strong > 0) Tone.Danger else Tone.Accent,
        supporting = if (rows.isEmpty()) null else "${rows.size}" + if (strong > 0) " · $strong strong" else "",
        action = if (rows.isNotEmpty()) {
            { GhostButton("Clear", onClick = { methods.forEach { HiddenCameraScanner.clear(it) } }, tone = Tone.Neutral) }
        } else null
    )
    if (rows.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
    } else {
        rows.forEach { FindingRow(it) }
    }
}

@Composable
private fun FindingRow(f: CameraFinding) {
    val tone = when {
        f.confidence >= 0.8f -> Tone.Danger
        f.confidence >= 0.5f -> Tone.Caution
        else -> Tone.Neutral
    }
    ClairixCard(tone = tone, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(HiddenCameraScanner.methodLabel(f.method), tone)
            Spacer(Modifier.weight(1f))
            Text("${(f.confidence * 100).roundToInt()} %", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
            Text(timeFmt.format(Date(f.ts)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        }
        Text(f.location, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        if (f.lat != null && f.lon != null) {
            Text(
                String.format(Locale.US, "%.5f, %.5f", f.lat, f.lon),
                style = MaterialTheme.typography.monoSmall,
                color = ClairixTheme.colors.textSubtle
            )
        }
    }
}

/** A message from one of the sweep methods, such as an error or a missing permission. */
@Composable
private fun SweepWarning(text: String) {
    Notice(Tone.Caution, text)
}

/** Wall-clock time that ticks every [periodMs] so "n s ago" labels and countdowns move. */
@Composable
private fun rememberNow(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
