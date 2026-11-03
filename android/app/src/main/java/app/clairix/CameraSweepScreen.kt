package app.clairix

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import app.clairix.analysis.IrCheck
import app.clairix.analysis.OpticalState
import app.clairix.analysis.HiddenCameraScanner
import app.clairix.analysis.LensGlintAnalyzer
import app.clairix.analysis.NetworkSweepCoverage
import app.clairix.analysis.OpticalMode
import app.clairix.analysis.SweepPhotos
import app.clairix.comms.CommsMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.US)

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
    val ctx = LocalContext.current
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = SweepTab.entries[tabIndex.coerceIn(0, SweepTab.entries.size - 1)]
    val findings by HiddenCameraScanner.findings.collectAsStateWithLifecycle()
    val strong = findings.count { it.confidence >= 0.7f }

    // What the last visit found, and the checklist as it was left, come back from disk.
    LaunchedEffect(Unit) { HiddenCameraScanner.restore(ctx) }

    // A finding made while this screen is up is shown here and raises no
    // notification; once the screen is paused or gone, findings notify again.
    LifecycleResumeEffect(Unit) {
        HiddenCameraScanner.sweepScreenResumed(true)
        onPauseOrDispose { HiddenCameraScanner.sweepScreenResumed(false) }
    }

    Column(Modifier.fillMaxSize().background(ClairixTheme.colors.base)) {
        // Shown on its own screen it brings its own bar; under the shell it has one already.
        if (onBack != null) ClairixTopBar(title = stringResource(R.string.scan_hidden_cameras), onBack = onBack)

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
                            strong > 0 -> if (strong == 1) stringResource(R.string.scan_1_strong_finding) else stringResource(R.string.scan_strong_findings, strong)
                            findings.isNotEmpty() -> stringResource(R.string.scan_to_check, findings.size)
                            else -> stringResource(R.string.scan_nothing_found_yet)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = ClairixTheme.colors.text
                    )
                    Text(
                        stringResource(R.string.scan_lens_glint_infrared_the_wi_fi),
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
    val scope = rememberCoroutineScope()
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
    var zoomed by rememberSaveable { mutableStateOf(false) }
    // The bound camera's zoom range; the zoom button shows only where there is one.
    var maxZoom by remember { mutableStateOf(1f) }
    var keeping by remember { mutableStateOf(false) }
    val state by HiddenCameraScanner.optical.collectAsStateWithLifecycle()
    val confirmedIr by HiddenCameraScanner.irConfirmedIds.collectAsStateWithLifecycle()
    val dismissedIr by HiddenCameraScanner.irDismissedIds.collectAsStateWithLifecycle()
    val irRecord by HiddenCameraScanner.irCheck.collectAsStateWithLifecycle()
    val irUntil by HiddenCameraScanner.irCheckUntil.collectAsStateWithLifecycle()
    val now = rememberNow(1_000L)
    val mine = findings.filter { it.method == CameraFindMethod.LENS_GLINT || it.method == CameraFindMethod.IR_ILLUMINATOR }
    // Kept here, not in the viewfinder, so "Keep a photo of this" can take what it shows.
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // A candidate is red when it is a confirmed reflection, or an IR light the owner said they cannot see;
    // an IR light they said they can see is an ordinary LED, grey like any other light.
    val isRed: (GlintBlob) -> Boolean = { b ->
        b.candidate && (mode == OpticalMode.GLINT || HiddenCameraScanner.opticalFindingId(mode, b) in confirmedIr)
    }
    val isLed: (GlintBlob) -> Boolean = { b ->
        mode == OpticalMode.IR && b.candidate && HiddenCameraScanner.opticalFindingId(mode, b) in dismissedIr
    }
    val red = state.tracks.filter(isRed)
    val unconfirmedIr = mode == OpticalMode.IR && state.tracks.any { it.candidate && !isRed(it) && !isLed(it) }

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
            // For ceiling fixtures: a pinhole two metres up gets twice the pixels.
            if (maxZoom > 1.01f) GhostButton(if (zoomed) "1×" else "2×", onClick = { zoomed = !zoomed }, tone = Tone.Neutral)
            GhostButton(if (live) stringResource(R.string.scan_pause) else stringResource(R.string.scan_resume), onClick = { live = !live }, tone = Tone.Neutral)
        }

        if (!granted) {
            ClairixCard(spacing = 4.dp) {
                Text(stringResource(R.string.scan_camera_permission), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.scan_the_optical_sweep_looks_through_the),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Spacer(Modifier.height(8.dp))
                if (blocked) {
                    // Android no longer shows the prompt once it has been refused for good: only Settings can grant it.
                    PrimaryButton(stringResource(R.string.settings_open_app_settings), onClick = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
                        )
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton(stringResource(R.string.scan_allow_camera), onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(ClairixShapes.Card).background(ClairixTheme.colors.base)
            ) {
                if (live) {
                    CameraViewfinder(mode, previewView, zoomed, onMaxZoom = { maxZoom = it })
                    GlintOverlay(state.tracks, isRed, isLed)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.scan_paused), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
                    }
                }
                // Status strip
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(ClairixTheme.colors.base.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val status = when {
                        state.error != null -> state.error!!
                        !state.running -> stringResource(R.string.scan_opening_camera)
                        mode == OpticalMode.GLINT && !state.hasTorch -> stringResource(R.string.scan_this_camera_has_no_torch_so)
                        state.checking -> stringResource(R.string.scan_checking_a_spot_torch_off_for)
                        state.tooBright -> stringResource(R.string.scan_too_bright_for_ir_darken_the)
                        state.washedOut -> stringResource(R.string.scan_washed_out_back_away_from_the)
                        red.isNotEmpty() -> if (mode == OpticalMode.GLINT) stringResource(R.string.scan_steady_reflection_look_for_a_pinhole) else stringResource(R.string.scan_a_light_you_cannot_see_look)
                        unconfirmedIr -> stringResource(R.string.scan_point_of_light_can_you_see)
                        state.torchUnseen -> stringResource(R.string.scan_could_not_see_the_torch_go)
                        state.tracks.any { !it.lightSource && !isLed(it) } -> stringResource(R.string.scan_bright_spot_s_hold_still, state.tracks.count { !it.lightSource && !isLed(it) })
                        state.tracks.isNotEmpty() -> stringResource(R.string.scan_only_light_sources_in_view)
                        else -> if (mode == OpticalMode.GLINT) stringResource(R.string.scan_hold_still_over_each_area_torch) else stringResource(R.string.scan_point_at_suspect_objects)
                    }
                    val col = when {
                        state.error != null -> ClairixTheme.colors.danger
                        red.isNotEmpty() -> ClairixTheme.colors.danger
                        state.tooBright || state.washedOut || unconfirmedIr || state.torchUnseen -> ClairixTheme.colors.caution
                        else -> ClairixTheme.colors.textMuted
                    }
                    Text(
                        status, style = MaterialTheme.typography.labelMedium, color = col, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // Read out as it changes, so a spot turning red reaches screen-reader users too.
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Text(
                        stringResource(R.string.scan_y_fps, state.meanLuma.roundToInt(), (state.lumaChange * 100).roundToInt(), state.fps.roundToInt()),
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }

            if (live && red.isNotEmpty()) {
                PrimaryButton(
                    stringResource(R.string.scan_keep_a_photo_of_this),
                    tone = Tone.Danger,
                    enabled = !keeping,
                    loading = keeping,
                    onClick = {
                        val shot = runCatching { previewView.bitmap }.getOrNull()
                        if (shot == null) {
                            Toast.makeText(ctx, ctx.getString(R.string.scan_the_camera_has_no_picture_yet), Toast.LENGTH_SHORT).show()
                            return@PrimaryButton
                        }
                        keeping = true
                        val marks = red
                        val density = ctx.resources.displayMetrics.density
                        scope.launch {
                            val kept = withContext(Dispatchers.IO) {
                                runCatching { HiddenCameraScanner.keepPhoto(ctx.applicationContext, mode, marks, shot, density) }.getOrDefault(false)
                            }
                            keeping = false
                            Toast.makeText(
                                ctx,
                                if (kept) "Photo kept, encrypted, with the finding on your timeline" else "The photo could not be kept",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.scan_saves_this_view_with_the_circle),
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }

        if (granted && mode == OpticalMode.GLINT && state.running && !state.hasTorch) {
            Notice(
                Tone.Caution,
                stringResource(R.string.scan_this_phones_back_camera_has_no),
                title = stringResource(R.string.scan_no_torch_on_this_camera)
            )
        }

        if (mode == OpticalMode.IR) {
            HiddenCameraScanner.irNotice(irRecord)?.let { Notice(Tone.Caution, it, title = HiddenCameraScanner.IR_UNSEEN_TITLE) }
            IrCheckCard(irRecord, irUntil, now, canRun = granted && live && state.running && state.error == null)
        }

        ClairixCard(spacing = 4.dp) {
            if (mode == OpticalMode.GLINT) {
                Text(stringResource(R.string.scan_how_glint_works), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.scan_the_torch_is_on_a_lens),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Text(
                    stringResource(R.string.scan_it_works_best_within_about_a),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            } else {
                Text(stringResource(R.string.scan_how_the_ir_view_works), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
                Text(
                    stringResource(R.string.scan_torch_off_front_camera_night_vision),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
                Text(
                    stringResource(R.string.scan_cameras_sold_as_no_glow_use),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            }
        }

        FindingsSection(
            mine, methodsFor(SweepTab.OPTICAL),
            if (mode == OpticalMode.GLINT) stringResource(R.string.scan_no_steady_reflections_recorded_yet) else HiddenCameraScanner.irEmptyText(irRecord)
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The IR view's self-test: can this phone's front camera see a TV remote's infrared? */
@Composable
private fun IrCheckCard(record: IrCheck, until: Long, now: Long, canRun: Boolean) {
    val checking = until > 0L
    ClairixCard(spacing = 4.dp) {
        Text(stringResource(R.string.scan_can_this_phone_see_infrared), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(
            when {
                checking -> stringResource(R.string.scan_hold_a_tv_remote_close_to, ((until - now) / 1000L).coerceAtLeast(0L))
                record.seen -> stringResource(R.string.scan_yes_its_front_camera_showed_a, dayFmt.format(Date(record.passedTs)))
                record.failedTs > 0L -> stringResource(R.string.scan_not_shown_yet_the_check_on, dayFmt.format(Date(record.failedTs)))
                else -> stringResource(R.string.scan_not_checked_yet_a_tv_remotes)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (record.seen && !checking) ClairixTheme.colors.safe else ClairixTheme.colors.textMuted
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            when {
                checking -> stringResource(R.string.scan_checking_2)
                record.seen -> stringResource(R.string.scan_check_again)
                else -> stringResource(R.string.scan_check_with_a_tv_remote)
            },
            onClick = { HiddenCameraScanner.startIrCheck() },
            enabled = canRun && !checking,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Binds CameraX to the lifecycle for [mode]: back camera with torch for glint, front
 * camera dark for IR. Preview and analysis both ask for 4:3 so the overlay's
 * normalised coordinates land on the right pixels, and the view is FIT_CENTER
 * inside a 3:4 box so nothing is cropped away. Analysis asks for up to 1280×960
 * (CameraX would otherwise give it 640×480), so a small glint further off is still
 * a few pixels. The torch's state as the camera reports it, and its confirmation
 * that a switch took effect, go to the analyzer, and so does the gyroscope.
 */
@Composable
private fun CameraViewfinder(mode: OpticalMode, previewView: PreviewView, zoomed: Boolean, onMaxZoom: (Float) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    // The bound camera, for the zoom; null while binding.
    var bound by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    DisposableEffect(mode) {
        HiddenCameraScanner.opticalStarting(mode)
        var provider: ProcessCameraProvider? = null
        var cancelled = false
        val main = ContextCompat.getMainExecutor(ctx)
        // Set once the camera is bound; the analyzer asks for the torch through it.
        var control: androidx.camera.core.CameraControl? = null
        // The torch as the analyzer last asked for it: off only while it tests a spot.
        var torchWanted = mode.torch
        var glint: LensGlintAnalyzer? = null
        val analyzer = LensGlintAnalyzer(
            mode = mode, mirrored = mode.frontCamera,
            torch = if (mode.torch) { on ->
                torchWanted = on
                main.execute {
                    val c = control ?: return@execute
                    runCatching {
                        val switched = c.enableTorch(on)
                        // Completes once a capture result shows the torch as asked: the camera's own word that it switched.
                        switched.addListener({ if (runCatching { switched.get() }.isSuccess) glint?.torchConfirmed(on) }, main)
                    }
                }
            } else null,
            onState = { HiddenCameraScanner.publishOptical(it) },
            onCandidate = { HiddenCameraScanner.opticalCandidate(ctx, mode, it) }
        )
        glint = analyzer
        val torchObserver = androidx.lifecycle.Observer<Int> { s -> analyzer.torchReportedOn = s == TorchState.ON }
        var torchLive: androidx.lifecycle.LiveData<Int>? = null
        val zoomObserver = androidx.lifecycle.Observer<ZoomState> { z -> onMaxZoom(z.maxZoomRatio) }
        var zoomLive: androidx.lifecycle.LiveData<ZoomState>? = null
        // A spot that stays put on the sensor while the phone turns is a hot pixel, not a light in the room.
        val sensors = ctx.getSystemService(SensorManager::class.java)
        val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val turns = object : SensorEventListener {
            private var lastNs = 0L
            override fun onSensorChanged(e: SensorEvent) {
                if (lastNs != 0L) {
                    val dt = (e.timestamp - lastNs) / 1_000_000_000f
                    if (dt > 0f && dt < 0.2f) {
                        analyzer.addTurn(Math.toDegrees((e.values[0] * dt).toDouble()).toFloat(), Math.toDegrees((e.values[1] * dt).toDouble()).toFloat())
                    }
                }
                lastNs = e.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensors != null && gyro != null) runCatching { sensors.registerListener(turns, gyro, SensorManager.SENSOR_DELAY_GAME) }
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
                val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysisResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                p.unbindAll()
                val camera = p.bindToLifecycle(owner, selector, preview, analysis)
                bound = camera
                zoomLive = camera.cameraInfo.zoomState.also { it.observe(owner, zoomObserver) }
                // Without a flash unit the glint mode never runs its torch test and says it cannot work.
                val hasTorch = mode.torch && camera.cameraInfo.hasFlashUnit()
                analyzer.torchAvailable = hasTorch
                if (hasTorch) {
                    control = camera.cameraControl
                    torchLive = camera.cameraInfo.torchState.also { it.observe(owner, torchObserver) }
                    camera.cameraControl.enableTorch(true)
                } else if (mode.torch) {
                    HiddenCameraScanner.publishOptical(OpticalState(mode = mode, running = true, torchOn = false, hasTorch = false))
                }
            } catch (e: Exception) {
                HiddenCameraScanner.opticalError(mode, e.message)
            }
        }, main)
        onDispose {
            cancelled = true
            owner.lifecycle.removeObserver(resumeObserver)
            torchLive?.removeObserver(torchObserver)
            zoomLive?.removeObserver(zoomObserver)
            if (sensors != null) runCatching { sensors.unregisterListener(turns) }
            analyzer.close()
            runCatching { provider?.unbindAll() }
            bound = null
            HiddenCameraScanner.opticalStopped(mode)
        }
    }

    LaunchedEffect(bound, zoomed) {
        val camera = bound ?: return@LaunchedEffect
        val top = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        runCatching { camera.cameraControl.setZoomRatio(if (zoomed) min(2f, top) else 1f) }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GlintOverlay(tracks: List<GlintBlob>, isRed: (GlintBlob) -> Boolean, isLed: (GlintBlob) -> Boolean) {
    val palette = ClairixTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        for (b in tracks) {
            val c = Offset(b.x * size.width, b.y * size.height)
            val r = max(14.dp.toPx(), b.radius * min(size.width, size.height) * 3f)
            val red = isRed(b)
            val led = isLed(b)
            val col = when {
                red -> palette.danger
                b.lightSource || led -> palette.textMuted.copy(alpha = 0.3f)
                // An IR light held still, waiting for the owner to say whether they can see it.
                b.candidate -> palette.caution
                b.heldMs >= GlintTracker.HOLD_MS / 2 -> palette.caution
                else -> palette.textMuted.copy(alpha = 0.45f)
            }
            drawCircle(col, r, c, style = Stroke(width = if (b.candidate && !led) 3.dp.toPx() else 1.5.dp.toPx()))
            if (red) {
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
            Text(stringResource(R.string.scan_this_network), style = MaterialTheme.typography.labelSmall, color = ClairixTheme.colors.textSubtle)
            val conn = wifi.connection
            Text(
                conn?.ssid ?: progress.ssid ?: stringResource(R.string.scan_not_connected_to_wi_fi),
                style = MaterialTheme.typography.titleMedium,
                color = if (conn != null || progress.ssid != null) ClairixTheme.colors.text else ClairixTheme.colors.textMuted
            )
            val sub = progress.subnet ?: conn?.ipv4?.let { stringResource(R.string.scan_phone_at, it) }
            if (sub != null) Text(sub, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
            Spacer(Modifier.height(8.dp))
            if (progress.running) {
                val frac = if (progress.hostsTotal > 0) progress.hostsDone.toFloat() / progress.hostsTotal else 0f
                ClairixProgressBar(frac)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(progress.phase, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(stringResource(R.string.scan_live, progress.hostsDone, progress.hostsTotal, progress.liveHosts), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.settings_cancel), onClick = { HiddenCameraScanner.cancelNetworkScan() }, modifier = Modifier.fillMaxWidth())
            } else {
                progress.error?.let { SweepWarning(it) }
                if (progress.lastScanTs > 0 && progress.error == null) {
                    Text(
                        "Last sweep ${fmtAgo(now, progress.lastScanTs)} · ${progress.liveHosts} live ${if (progress.liveHosts == 1) "host" else "hosts"} · ${progress.found} camera-like",
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                // "Nothing found" and "could not look" are different answers; this is the second.
                if (progress.error == null) progress.inconclusiveReason?.let { Notice(Tone.Caution, it, title = stringResource(R.string.scan_sweep_inconclusive)) }
                Spacer(Modifier.height(6.dp))
                PrimaryButton(stringResource(R.string.scan_sweep_the_wi_fi_for_cameras), onClick = { HiddenCameraScanner.startNetworkScan(ctx) }, modifier = Modifier.fillMaxWidth())
            }
        }

        ClairixCard(spacing = 4.dp) {
            Text(stringResource(R.string.scan_what_it_looks_for), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.scan_every_address_in_the_24_around),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
        }

        Notice(Tone.Info, HiddenCameraScanner.CLOUD_ONLY_NOTE, title = stringResource(R.string.scan_what_this_sweep_cannot_see))

        FindingsSection(mine, methodsFor(SweepTab.NETWORK), NetworkSweepCoverage.emptyText(progress))
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
            Text(stringResource(R.string.scan_wi_fi_setup_hotspots), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.scan_a_camera_nobody_has_finished_installing),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
            Spacer(Modifier.height(8.dp))
            state.wifiError?.let { SweepWarning(it) }
            if (state.wifiScannedTs > 0) {
                Text(
                    "Readings from ${fmtAgo(now, state.wifiScannedTs)} · ${state.wifiApsSeen} networks in range" +
                        if (now - state.wifiScannedTs > 30_000L) stringResource(R.string.scan_android_limits_wi_fi_scans_to) else "",
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.textSubtle
                )
                Spacer(Modifier.height(6.dp))
            }
            PrimaryButton(
                if (state.wifiScanning) stringResource(R.string.scan_scanning) else stringResource(R.string.scan_scan_for_setup_hotspots),
                onClick = { HiddenCameraScanner.startWifiSetupScan(ctx) },
                enabled = !state.wifiScanning,
                loading = state.wifiScanning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ClairixCard(spacing = 4.dp) {
            Text(stringResource(R.string.scan_bluetooth_setup_beacons), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(
                stringResource(R.string.scan_before_it_has_wi_fi_credentials),
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textMuted
            )
            Spacer(Modifier.height(8.dp))
            state.bleError?.let { SweepWarning(it) }
            if (state.bleListening) {
                val left = ((state.bleUntilTs - now) / 1000L).coerceAtLeast(0L)
                Text(
                    stringResource(R.string.scan_listening_s_left_devices_heard, left, state.bleDevicesSeen),
                    style = MaterialTheme.typography.monoSmall,
                    color = ClairixTheme.colors.accent
                )
                Spacer(Modifier.height(6.dp))
                SecondaryButton(stringResource(R.string.comms_stop), onClick = { HiddenCameraScanner.stopBleListening() }, modifier = Modifier.fillMaxWidth())
            } else {
                if (state.bleDevicesSeen > 0) {
                    Text(stringResource(R.string.scan_last_window_heard_devices, state.bleDevicesSeen), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                    Spacer(Modifier.height(6.dp))
                }
                SecondaryButton(stringResource(R.string.scan_listen_for_20_seconds), onClick = { HiddenCameraScanner.startBleListening(ctx, 20_000L) }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
            }
        }

        FindingsSection(mine, methodsFor(SweepTab.WIRELESS), stringResource(R.string.scan_nothing_in_range_is_announcing_itself))
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
                Text(stringResource(R.string.scan_magnetic_gauge), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
                ClairixButton(
                    if (mag.active) stringResource(R.string.comms_stop) else stringResource(R.string.scan_start),
                    onClick = { if (mag.active) HiddenCameraScanner.stopMagnetic() else HiddenCameraScanner.startMagnetic(ctx) },
                    kind = if (mag.active) ButtonKind.Secondary else ButtonKind.Primary
                )
            }
            Spacer(Modifier.height(8.dp))
            mag.error?.let { SweepWarning(it) }
            if (mag.active) {
                MagneticBar(mag.fieldUt, mag.baselineUt, mag.thresholdUt, mag.triggered && !mag.unreliable)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        when {
                            mag.unreliable -> stringResource(R.string.scan_paused_recalibrate)
                            mag.warmingUp -> stringResource(R.string.scan_settling)
                            mag.triggered -> stringResource(R.string.scan_anomaly_look_here)
                            else -> stringResource(R.string.scan_quiet)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            mag.unreliable -> ClairixTheme.colors.caution
                            mag.triggered -> ClairixTheme.colors.danger
                            mag.warmingUp -> ClairixTheme.colors.textSubtle
                            else -> ClairixTheme.colors.safe
                        }
                    )
                    Text(
                        stringResource(R.string.scan_t_base, mag.fieldUt.roundToInt(), mag.baselineUt.roundToInt(), mag.deltaUt.roundToInt(), mag.thresholdUt.roundToInt()),
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
                if (mag.unreliable) {
                    Text(
                        stringResource(R.string.scan_the_compass_reports_its_own_readings),
                        style = MaterialTheme.typography.bodySmall,
                        color = ClairixTheme.colors.caution
                    )
                }
            } else {
                Text(
                    stringResource(R.string.scan_start_the_gauge_then_slide_the),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            }
        }

        // Checklist
        SectionHeader(
            stringResource(R.string.scan_room_sweep_of, done.count { id -> steps.any { it.id == id } }, steps.size),
            action = if (done.isNotEmpty()) {
                { GhostButton(stringResource(R.string.scan_reset), onClick = { HiddenCameraScanner.resetSweep() }, tone = Tone.Neutral) }
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
        Notice(Tone.Caution, HiddenCameraScanner.ANALOG_RF_NOTE, title = stringResource(R.string.scan_what_the_phone_cant_see))

        FindingsSection(mine, methodsFor(SweepTab.GUIDED), stringResource(R.string.scan_no_magnetic_anomalies_recorded))
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
        stringResource(R.string.scan_findings),
        tone = if (strong > 0) Tone.Danger else Tone.Accent,
        supporting = if (rows.isEmpty()) null else "${rows.size}" + if (strong > 0) stringResource(R.string.scan_strong, strong) else "",
        action = if (rows.isNotEmpty()) {
            { GhostButton(stringResource(R.string.comms_clear), onClick = { methods.forEach { HiddenCameraScanner.clear(it) } }, tone = Tone.Neutral) }
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
        f.needsConfirmation -> Tone.Caution
        f.confidence >= 0.8f -> Tone.Danger
        f.confidence >= 0.5f -> Tone.Caution
        else -> Tone.Neutral
    }
    ClairixCard(tone = tone, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (f.needsConfirmation) stringResource(R.string.scan_ir_unconfirmed) else HiddenCameraScanner.methodLabel(f.method), tone)
            Spacer(Modifier.weight(1f))
            Text("${(f.confidence * 100).roundToInt()} %", style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
            Text(timeFmt.format(Date(f.ts)), style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
        }
        Text(f.location, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        if (f.lat != null && f.lon != null) {
            Text(
                String.format(Locale.US, stringResource(R.string.scan_5f_5f), f.lat, f.lon),
                style = MaterialTheme.typography.monoSmall,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (f.needsConfirmation) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.scan_can_you_see_this_light_with), style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.scan_i_can_see_it_led), onClick = { HiddenCameraScanner.dismissIr(f.id) }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.scan_i_cannot_see_it), onClick = { HiddenCameraScanner.confirmIr(f.id) }, tone = Tone.Danger, modifier = Modifier.weight(1f))
            }
        }
        f.photo?.let { SweepPhotoButton(it) }
    }
}

/**
 * "View photo" for a photo kept from the camera sweep, here and on the timeline.
 * The photo is opened (decrypted in memory) only while it is on screen.
 */
@Composable
internal fun SweepPhotoButton(attachment: String) {
    var open by remember(attachment) { mutableStateOf(false) }
    GhostButton(stringResource(R.string.scan_view_photo), onClick = { open = true })
    if (open) SweepPhotoDialog(attachment, onDismiss = { open = false })
}

@Composable
private fun SweepPhotoDialog(attachment: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // First: whether opening has finished; second: the picture, or null when it is gone or will not open.
    val photo by produceState<Pair<Boolean, ImageBitmap?>>(initialValue = false to null, attachment) {
        value = true to withContext(Dispatchers.IO) {
            runCatching { SweepPhotos.open(ctx.applicationContext.filesDir, attachment)?.let { CommsMedia.decode(it, 2048) }?.asImageBitmap() }.getOrNull()
        }
    }
    ClairixDialog(
        title = stringResource(R.string.scan_kept_photo),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.comms_close),
        onConfirm = onDismiss,
        dismissLabel = null
    ) {
        val (loaded, image) = photo
        when {
            image != null -> Image(
                image,
                contentDescription = stringResource(R.string.scan_the_photo_kept_from_the_camera),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
            loaded -> Text(stringResource(R.string.scan_this_photo_is_no_longer_on), style = MaterialTheme.typography.bodyMedium)
            else -> Text(stringResource(R.string.scan_opening), style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 5 -> stringResource(R.string.comms_just_now)
        s < 60 -> stringResource(R.string.scan_s_ago_2, s)
        s < 3600 -> stringResource(R.string.scan_m_ago, s / 60)
        else -> stringResource(R.string.scan_h_ago, s / 3600)
    }
}
