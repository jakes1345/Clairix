package app.clairix

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.CutCornerShape
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.viewinterop.AndroidView
import app.clairix.CatcherFinding
import app.clairix.analysis.CellMonitor
import app.clairix.analysis.CellularExposure
import app.clairix.analysis.Report
import app.clairix.analysis.ScanPacing
import app.clairix.analysis.ScanRecord
import app.clairix.analysis.ScannerHealth
import app.clairix.analysis.WifiScanner
import app.clairix.security.AppLock
import app.clairix.security.LockScreen
import app.clairix.security.QuickExit
import app.clairix.analysis.PhoneHealthMonitor
import app.clairix.analysis.SpywareHealth
import app.clairix.analysis.StalkwareWatch
import app.clairix.security.BiometricTripwire
import app.clairix.security.ScreenRecordingMonitor
import app.clairix.security.SosLocation
import app.clairix.security.EmergencyNumbers
import app.clairix.security.SosExtras
import app.clairix.security.HistoryRetention
import app.clairix.security.UnlockLedger
import app.clairix.detect.BleNames
import app.clairix.detect.KarmaTrap
import app.clairix.detect.VehicleTrips
import app.clairix.comms.CallManager
import app.clairix.comms.CallPhase
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import app.clairix.comms.CommsSecureWindow
import app.clairix.comms.KeepOffScreen
import app.clairix.comms.PairingCode
import app.clairix.comms.PanicResult
import app.clairix.comms.SosGate
import app.clairix.ui.appResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import app.clairix.ui.theme.ClairixTheme
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.clairix.ui.theme.ClairixMotion
import app.clairix.ui.components.*
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.monoLarge
import app.clairix.ui.theme.mono
import app.clairix.ui.components.setSensitiveText
import app.clairix.ui.SafetyFirst
import app.clairix.ui.SafetyFirstCard

// ── Permissions ─────────────────────────────────────────────────────────

/**
 * Everything the app asks for up front.
 *
 * Fine and coarse location are always requested together: from Android 12 a
 * request naming ACCESS_FINE_LOCATION without ACCESS_COARSE_LOCATION is ignored
 * outright, so the location prompt never appeared and the scanner could not start.
 */
private val REQUIRED = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS
)

/**
 * The subset without which scanning cannot run at all: the scan itself, and the
 * location permission that a location-typed foreground service is refused without.
 * Coarse location rides along because Android requires it in the same request;
 * a grant of fine location always includes it, so checking both is checking fine.
 *
 * Notifications are separate on purpose. Requiring them meant declining the
 * notification prompt silently blocked the whole detector.
 */
private val ESSENTIAL = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/** What the comms-only build asks for on its onboarding: alerts, and a position for SOS. */
private val COMMS_ESSENTIAL = arrayOf(
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/**
 * Route ids a [Registry.requestTab] call carries. They are the numbers the old
 * flat tab bar used, kept because message notifications already posted send
 * [CommsNotifications.COMMS_TAB_INDEX]; MainApp maps each onto a destination of
 * the tabbed shell, and ignores one the flavour does not have.
 */
internal const val ROUTE_NFC = 4
private const val ROUTE_COMMS = CommsNotifications.COMMS_TAB_INDEX

// ── Activity ───────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    /**
     * The NFC reader, card vault and card emulation, from the tier's source set
     * (src/suite); null in the comms-only build, which has none of them.
     */
    private val nfc: NfcHost? by lazy { Tier.nfcHost(this) }

    /**
     * The static device-health scan (accessibility services, device admins, debug
     * settings) needs no service, so the Activity runs it too — otherwise the Device
     * tab read "no issues" with the scanner off and nothing having looked.
     */
    private val phoneHealthMonitor by lazy { PhoneHealthMonitor(applicationContext) }

    /**
     * The Cell and WIFI tabs used to be blank until the scanner service had run: the
     * modem and the Wi-Fi radio are readable from the Activity just as well, so both
     * tabs now take their own reading while they are on screen. Only the IMSI-catcher
     * judgement needs the service, because that owns the baseline on disk.
     */
    private val cellMonitor by lazy { CellMonitor(applicationContext) }

    // focusProperties.enter, so focus cannot move into the app while it is locked.
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppSettings.load(this)
        AppLock.init(this)
        // Back from the duress wipe in a fresh process: the lock screen says what the last one said,
        // once (not when Android restores or recreates this screen from the same intent).
        AppLock.noteWipeRelaunch(
            this,
            relaunched = savedInstanceState == null &&
                intent?.getBooleanExtra(app.clairix.security.WipeRelaunchActivity.EXTRA_NOTICE, false) == true,
        )
        // Nothing this app shows belongs in a screenshot, a screen recording or
        // the Recents thumbnail: a card's UID and APDUs, the timeline with its
        // positions, a conversation, the Device tab. Secure always, passcode or
        // not; the screen-recording monitor reports attempts regardless.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Registry.bindTrustStore(this)

        if (BuildConfig.FULL_ACCESS) {
            // Platform hardening. The unlock ledger opens so the Device tab can read
            // it; this Activity's window is watched for being recorded; and the
            // enrolment tripwire is tested once per launch — Keystore work, off the
            // main thread. Each publishes straight into the Registry.
            UnlockLedger.init(this)
            // The offline mesh comes back on if the owner left it on.
            app.clairix.comms.mesh.Mesh.resume(this)
            ScreenRecordingMonitor.start(this, windowManager)
            lifecycleScope.launch(Dispatchers.IO) { runCatching { BiometricTripwire.check(applicationContext) } }
            // History past the owner's retention goes at each start too, scanning or not.
            lifecycleScope.launch(Dispatchers.IO) { runCatching { HistoryRetention.apply(applicationContext) } }

            // The NFC reader and the card vault (the comms-only build has neither).
            nfc?.onCreate()

            // Show the stored timeline even before the scanner has run this session.
            lifecycleScope.launch(Dispatchers.IO) { runCatching { TimelineLog.publish(applicationContext) } }
        }

        CommsRepository.init(applicationContext)
        CommsNotifications.ensureChannels(this)
        // The alert channel is secret on the lock screen; made now so an existing
        // install drops the old channel at once, not at its next alert.
        app.clairix.security.PlatformAlerts.ensureChannel(this)
        // Discreet mode: the launcher entry and the channel names follow the stored choice
        // (back to Clairix's after "Wipe Clairix"; kept by the duress decoy).
        app.clairix.security.DiscreetMode.reconcile(this)

        handleOpenThreadIntent(intent)

        setContent {
            // Every text field: the keyboard is asked not to learn from what is typed.
            app.clairix.ui.NoKeyboardLearning {
            // The colour theme the user picked in Settings > Appearance; switching it
            // there recolours everything at once, without a restart.
            val theme by AppSettings.theme.collectAsStateWithLifecycle()
            ClairixTheme(theme = theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val locked by AppLock.locked.collectAsStateWithLifecycle()

                    // The lock screen is drawn OVER the app, not INSTEAD of it. Taking
                    // MainApp out of composition while locked unregistered every
                    // rememberLauncherForActivityResult inside it (camera, gallery,
                    // video, QR scanner, background-location flow) mid-flight: the
                    // camera pauses Clairix, the relock fires, and the result came back
                    // to a launcher that no longer existed, so the capture was lost.
                    // Kept composed, the launchers stay registered and the result is
                    // delivered once the user unlocks. LockScreen fills the window with
                    // an opaque background, and FLAG_SECURE (set whenever a passcode is
                    // on) keeps what is underneath out of screenshots and Recents.
                    // Locking also drops whatever had the keyboard's focus underneath.
                    val focusManager = LocalFocusManager.current
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(locked) {
                        if (locked) {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Drawn over is not enough: accessibility services (and
                                // anything reading the window, such as uiautomator) see
                                // the semantics tree, not pixels, and could read and act
                                // on everything underneath; a hardware keyboard could
                                // move focus into it. While locked, the app has no
                                // semantics at all and focus cannot enter it.
                                .then(
                                    if (locked) Modifier
                                        .clearAndSetSemantics { }
                                        .focusProperties { enter = { FocusRequester.Cancel } }
                                        .focusGroup()
                                        .onPreviewKeyEvent { true }
                                    else Modifier
                                )
                                // Nothing underneath the lock may be tapped through it:
                                // every pointer event is consumed at the Initial pass,
                                // before any child sees it, for as long as locked is true.
                                .pointerInput(locked) {
                                    if (locked) awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                        }
                                    }
                                }
                        ) {
                            // Read the preference on first composition rather than once in
                            // onCreate, so a value captured before onboarding finished can
                            // never show onboarding again; onComplete flips it in place.
                            var onboardingDone by remember { mutableStateOf(isOnboardingDone(this@MainActivity)) }

                            val onboardingPermissions = rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestMultiplePermissions()
                            ) { grants ->
                                if (BuildConfig.FULL_ACCESS && ESSENTIAL.all { grants[it] == true }) startScanning()
                            }

                            if (!onboardingDone) {
                                OnboardingScreen(
                                    // Onboarding used to start the service straight off the
                                    // last page without ever asking for a permission, so the
                                    // service failed startForeground for want of location
                                    // access and stopped itself. Tapping "Start" appeared to
                                    // do nothing at all on a fresh install.
                                    startScanService = {
                                        if (BuildConfig.FULL_ACCESS) {
                                            val missing = REQUIRED.filterNot { granted(it) }
                                            if (missing.isEmpty()) startScanning()
                                            else onboardingPermissions.launch(missing.toTypedArray())
                                        } else {
                                            val missing = COMMS_ESSENTIAL.filterNot { granted(it) }
                                            if (missing.isNotEmpty()) onboardingPermissions.launch(missing.toTypedArray())
                                        }
                                    },
                                    onComplete = { onboardingDone = true }
                                )
                            } else {
                                MainApp(
                                    onStart = { startScanning() },
                                    onStop = { sendToService(ScanService.ACTION_STOP) },
                                    onClearData = { sendToService(ScanService.ACTION_CLEAR) },
                                    hasPermissions = { ESSENTIAL.all { granted(it) } },
                                    nfc = nfc,
                                    onRefreshDeviceHealth = { refreshDeviceHealth() },
                                    onRefreshCell = { refreshCell() },
                                    onRefreshWifi = { refreshWifi() }
                                )
                            }
                        }

                        // Instant on: a fade-in would show the app for a frame. A short
                        // fade-out on unlock is fine, the user has just proved who they are.
                        AnimatedVisibility(
                            visible = locked,
                            enter = fadeIn(animationSpec = tween(0)),
                            exit = fadeOut(animationSpec = tween(200))
                        ) {
                            LockScreen()
                        }
                        // Once past the lock: wrong passcodes entered while she was away.
                        WrongPasscodeNotice()
                        // An evidence export asked for anywhere: its period, warning and share.
                        EvidenceExportHost()
                        // Then, on a phone an earlier version armed a wipe on unasked: what wrong passcodes should do.
                        WipeLimitQuestion()
                    }
                }
            }
            }
        }
    }

    /**
     * Starts the scanner as a foreground service. Plain startService() is refused
     * whenever the app is not already foreground, which made restarting from a
     * notification or a cold intent unreliable.
     */
    private fun startScanning() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    /**
     * Control messages (stop, clear) use plain startService: the activity is by
     * definition in the foreground when the user taps those, and startForegroundService
     * would oblige the service to promote itself even when it is only being asked to
     * clear data and shut down again.
     */
    private fun sendToService(serviceAction: String) {
        val intent = Intent(this, ScanService::class.java)
        intent.action = serviceAction
        startService(intent)
    }

    /**
     * The scanner is meant to be running whenever the owner last left it on. An
     * update or a system kill ends the service without turning that off, and the
     * hub then read "Scanner off" as if she had stopped it. On every return to the
     * foreground the scanner is started again when it can be, and its own start
     * puts the lost time on the timeline (ScanService.onStartCommand). When it
     * cannot be (a permission taken away since), the gap is recorded here and the
     * status says when the scanner was lost, so the hub and the Trackers screen can
     * say so and offer to resume.
     */
    private fun reconcileScanner() {
        if (!BuildConfig.FULL_ACCESS || !AppSettings.scanEnabled) return
        if (Registry.status.value.scanning) return
        if (ESSENTIAL.all { granted(it) }) {
            startScanning()
            return
        }
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            // A live service that cannot scan says why in its error; only a dead one was
            // "stopped by the system", dated by its own record or, when the process was
            // killed outright (an update, a low-memory kill), by its last heartbeat.
            if (Registry.status.value.error != null) return@launch
            // A kill leaves no row behind; the gap in cover goes on the timeline once.
            ScanRecord.claimSystemStop(app, scanEnabled = true)?.let { TimelineLog.record(app, ScannerHealth.systemStopEvent(it)) }
            val at = ScanRecord.systemStopAt(app, scanEnabled = true) ?: return@launch
            Registry.update { if (it.scanning) it else it.copy(stoppedBySystem = true, stoppedAt = at) }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLock.onForeground(this)
        // Back from Settings, perhaps having set or removed the lock screen.
        app.clairix.comms.Vault.check()
        nfc?.onResume()
        if (BuildConfig.FULL_ACCESS) refreshDeviceHealth()
        reconcileScanner()
        // Envelopes that arrived while the app was closed are pulled on every
        // return to the foreground; the push endpoint is renewed if it was lost.
        // While the app stays on screen the relay socket is held open, so what
        // arrives after this sync lands live instead of waiting for the next one.
        CommsRepository.onAppVisible(true)
        CommsRepository.registerPushIfPossible()
        CommsRepository.syncInBackground()
        // At most once a day, in both editions: the relay list, the asset key
        // list and the note of the newest and oldest safe version (Settings >
        // About, the Scan and Comms banners, the brake on sending).
        lifecycleScope.launch { runCatching { app.clairix.comms.RelayDirectory.refreshIfStale(applicationContext) } }
    }

    override fun onPause() {
        super.onPause()
        AppLock.onBackground(this)
        CommsRepository.onAppVisible(false)
        nfc?.onPause()
    }

    /** The activity is singleTop, so a tag delivered while it is up lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfc?.onIntent(intent)
        handleOpenThreadIntent(intent)
    }

    /**
     * A message notification asks for the COMMS tab and the conversation it is
     * about. This Activity is exported, so any app can send it these extras;
     * they only ever navigate. Calls are answered in [CallActivity], which is
     * not exported.
     */
    private fun handleOpenThreadIntent(intent: Intent?) {
        // An invite link: https://clairix.app/i#<invite>, opened directly or by
        // the page there (the invite then in the "c" extra), or clairix://invite?c=…
        // from a relay's invite page before Beta 8. It only fills in the COMMS
        // setup or offers to add the inviter; nothing is registered or added
        // until the owner confirms on screen.
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            val text = when {
                data.scheme == "https" && data.host == PairingCode.INVITE_HOST && data.path == PairingCode.INVITE_PATH ->
                    intent.getStringExtra("c") ?: data.toString()
                data.scheme == "clairix" && data.host == "invite" -> data.getQueryParameter("c")
                else -> null
            }
            if (text != null) {
                Registry.requestInvite(text)
                intent.data = null
                intent.removeExtra("c")
                Registry.requestTab(CommsNotifications.COMMS_TAB_INDEX)
                return
            }
        }
        if (intent == null || !intent.hasExtra(CommsNotifications.EXTRA_TAB)) return
        val tab = intent.getIntExtra(CommsNotifications.EXTRA_TAB, -1)
        val peer = intent.getStringExtra(CommsNotifications.EXTRA_PEER)
        val group = intent.getStringExtra(CommsRepository.EXTRA_GROUP)
        intent.removeExtra(CommsNotifications.EXTRA_TAB)
        intent.removeExtra(CommsNotifications.EXTRA_PEER)
        intent.removeExtra(CommsRepository.EXTRA_GROUP)
        if (tab < 0) return
        Registry.requestTab(tab)
        if (peer != null) Registry.requestThread(peer)
        if (group != null) Registry.requestGroup(group)
    }

    /**
     * Runs the static findings scan off the main thread and publishes the result,
     * and brings the unlock ledger up to date from the usage log while it is there.
     */
    private fun refreshDeviceHealth() {
        // The stalkerware check behind "no known spyware": at most once a day, alongside, and on
        // the owner's screen, so what it finds goes on the timeline without a notification. It
        // adds its result to Phone check itself.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { StalkwareWatch.checkIfDue(applicationContext, foreground = true) } }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { phoneHealthMonitor.scanAndPublish() }
            runCatching { UnlockLedger.refresh(applicationContext) }
        }
    }

    /**
     * Reads the serving cell for the Cell tab while the scanner is off. While it is
     * on, the service publishes every 15 s with the heuristics applied, and this
     * must not overwrite that with a reading nothing has judged.
     */
    private fun refreshCell() {
        if (ScanPacing.scannerPublishes(Registry.status.value)) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val reason = cellMonitor.unavailableReason()
                val radio = runCatching { cellMonitor.radioInfo() }.getOrNull()
                if (reason != null) {
                    Registry.publishCell(CellStatus(available = false, reason = reason, radio = radio))
                    return@launch
                }
                val snapshot = cellMonitor.sampleFull()
                if (ScanPacing.scannerPublishes(Registry.status.value)) return@launch
                if (snapshot == null) {
                    Registry.publishCell(
                        CellStatus(
                            available = false, radio = radio,
                            reason = "The modem has not reported a serving cell yet"
                        )
                    )
                } else {
                    runCatching { app.clairix.analysis.CellularExposure.noteServing(this@MainActivity, snapshot.cell) }
                    Registry.publishCell(
                        CellStatus(
                            available = true, cell = snapshot.cell, radio = radio,
                            neighbors = snapshot.neighbors, analysing = false
                        )
                    )
                }
            }
        }
    }

    /**
     * Refreshes the scan and reads the connected network and what is in range for the
     * WIFI tab while the scanner is off. While it is on, the service asks for a scan
     * every two minutes with the screen on and publishes what the tab shows; this must
     * not ask Android for more of them.
     */
    private fun refreshWifi() {
        if (ScanPacing.scannerPublishes(Registry.status.value)) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // The snapshot requests a scan and waits for its results itself.
                Registry.publishWifiStatus(WifiScanner.snapshot(applicationContext))
                // The judgement on what is in range is cheap and service-free too.
                if (!ScanPacing.scannerPublishes(Registry.status.value)) Registry.publishWifi(WifiScanner.scan(applicationContext))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Decrypted cards never outlive the app being on screen. Emulation, once
        // armed, keeps running — it is used with Clairix in the background — and the
        // banner and STOP work from the armed card's id and label alone.
        Registry.lockVault()
    }

    override fun onDestroy() {
        // The recording callback is registered on this Activity's WindowManager;
        // a recreated Activity registers its own in onCreate.
        ScreenRecordingMonitor.stop()
        super.onDestroy()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

// ── Formatting helpers ──────────────────────────────────────────────────

private val fullFmt    = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val clockFmt   = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dayFmt     = SimpleDateFormat("MMM d", Locale.US)
private val dayYearFmt = SimpleDateFormat("MMM d yyyy", Locale.US)

internal fun fmtTime(ts: Long): String = fullFmt.format(Date(ts))
private fun fmtClock(ts: Long): String = clockFmt.format(Date(ts))

private fun fmtAgo(res: Resources, now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> res.getString(R.string.scan_seconds_ago, s)
        s < 3600 -> res.getString(R.string.comms_minutes_ago, s / 60)
        s < 86_400 -> res.getString(R.string.scan_hours_minutes_ago, s / 3600, (s % 3600) / 60)
        else -> res.getString(R.string.scan_days_ago, s / 86_400)
    }
}

@Composable
private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> stringResource(R.string.scan_seconds, s)
        s < 3600 -> stringResource(R.string.scan_minutes_seconds, s / 60, s % 60)
        else -> stringResource(R.string.settings_h_min, s / 3600, (s % 3600) / 60)
    }
}

private fun fmtMetres(m: Double): String = when {
    m < 10 -> "~%.1f m".format(Locale.US, m)
    m < 1000 -> "~%.0f m".format(Locale.US, m)
    else -> "~%.1f km".format(Locale.US, m / 1000.0)
}

private fun fmtDbm(rssi: Int): String = when {
    rssi == 0 -> "— dBm"
    rssi < 0 -> "−${-rssi} dBm"
    else -> "$rssi dBm"
}

internal fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

private fun compass(bearing: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45.0).toInt() % 8]
}

private fun dayLabel(res: Resources, ts: Long, now: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val thisYear = cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = cal.timeInMillis
    if (ts >= todayStart) return res.getString(R.string.scan_today)
    if (ts >= yesterdayStart) return res.getString(R.string.scan_yesterday)
    cal.timeInMillis = ts
    val fmt = if (cal.get(Calendar.YEAR) == thisYear) dayFmt else dayYearFmt
    return fmt.format(Date(ts))
}

// ── Shared building blocks ────────────────────────────────────────────────

/** Wall-clock that ticks every [periodMs] so relative times stay honest. */
@Composable
private fun rememberNow(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Calls [refresh] at once and then every [periodMs] while the screen is started, and
 * stops when the app goes to the background; a null period refreshes nothing. A plain
 * LaunchedEffect loop outlived ON_STOP until its composition was disposed, so a tab
 * left open kept reading the modem, the phone's settings or the Wi-Fi for nobody.
 */
@Composable
private fun RefreshWhileStarted(periodMs: Long?, refresh: () -> Unit) {
    val current by rememberUpdatedState(refresh)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(periodMs, lifecycle) {
        if (periodMs == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                current()
                delay(periodMs)
            }
        }
    }
}

// ── Threat explainer target ─────────────────────────────────────────────────

private sealed class ExplainerTarget {
    data class BleDevice(val detection: Detection) : ExplainerTarget()
    data class CellIndicator(val finding: CatcherFinding) : ExplainerTarget()
    /** A trip-memory finding nothing tied to the owner's car: "has been on 3 of your journeys". */
    data class Journeys(val event: TimelineEvent) : ExplainerTarget()
}

// ── SOS button ────────────────────────────────────────────────────────────

/** How long the SOS button must be held before the confirmation appears. */
private const val SOS_HOLD_MS = 1500L

/**
 * The SOS button: an octagon, like a stop sign and like the mark, in the danger
 * colour. Holding it fills it from the bottom; once [SOS_HOLD_MS] has passed,
 * [gate] (run off the main thread) says whether anyone would receive an SOS.
 * If so a confirm dialog appears, and [onConfirmed] runs only after the user
 * taps Send; if not, the dialog says that nobody would receive it and offers
 * [onSetUpComms] instead, so no one confirms an alert that goes nowhere. The
 * defaults are for previews and screenshots; the shell passes the real gate.
 *
 * Both dialogs also carry the extras the owner turned on in Settings, SOS
 * (SosExtrasRun.kt): a text to one number, sent with the SOS or, with nobody in
 * Clairix to send to, on its own; and a button that opens the phone app with the
 * local emergency number. Each says what came of it.
 */
@Composable
internal fun SosButton(
    onConfirmed: () -> Unit,
    gate: suspend () -> SosGate = { SosGate.SEND },
    onSetUpComms: () -> Unit = {}
) {
    val context = LocalContext.current
    var sosProgress by remember { mutableFloatStateOf(0f) }
    var sosPressing by remember { mutableStateOf(false) }
    // Held long enough; the gate is being asked. Its own effect, so a finger
    // lifting meanwhile (which restarts the hold effect) cannot cut it short.
    var sosHeld by remember { mutableStateOf(false) }
    var showSosConfirm by remember { mutableStateOf(false) }
    var sosBlocked by remember { mutableStateOf<SosGate?>(null) }
    // The extras as set when the button was held, and what they came to after Send.
    var extras by remember { mutableStateOf(SosExtras.Config.OFF) }
    var emergency by remember { mutableStateOf(EmergencyNumbers.choose(null, null)) }
    var showExtrasResult by remember { mutableStateOf(false) }

    LaunchedEffect(sosPressing) {
        if (sosPressing) {
            val start = System.currentTimeMillis()
            while (sosPressing) {
                sosProgress = (System.currentTimeMillis() - start) / SOS_HOLD_MS.toFloat()
                if (sosProgress >= 1f) {
                    sosHeld = true
                    sosPressing = false
                    break
                }
                delay(16)
            }
            if (!showSosConfirm && sosBlocked == null && !sosHeld) sosProgress = 0f
        } else if (!showSosConfirm && sosBlocked == null && !sosHeld) {
            sosProgress = 0f
        }
    }

    LaunchedEffect(sosHeld) {
        if (!sosHeld) return@LaunchedEffect
        val who = withContext(Dispatchers.IO) { runCatching { gate() }.getOrDefault(SosGate.NOT_REGISTERED) }
        extras = withContext(Dispatchers.IO) { runCatching { SosExtras.config(context) }.getOrDefault(SosExtras.Config.OFF) }
        emergency = EmergencyNumbers.local(context)
        SosExtrasRun.reset()
        if (who == SosGate.SEND) showSosConfirm = true else sosBlocked = who
        sosHeld = false
    }

    val c = ClairixTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .clip(ClairixShapes.Octagon)
            .background(c.danger)
            .border(2.dp, c.base.copy(alpha = 0.6f), ClairixShapes.Octagon)
            .semantics {
                contentDescription = context.getString(R.string.scan_sos_hold_to_send_an_emergency)
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        sosPressing = true
                        tryAwaitRelease()
                        sosPressing = false
                    }
                )
            }
    ) {
        // The hold fills the octagon from the bottom, in the dark ink of its label.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(sosProgress.coerceIn(0f, 1f))
                .background(c.onDanger.copy(alpha = 0.28f))
        )
        Text("SOS", style = MaterialTheme.typography.headlineSmall, color = c.onDanger)
    }

    if (showSosConfirm) {
        ClairixDialog(
            title = stringResource(R.string.scan_send_an_sos),
            text = stringResource(R.string.scan_every_member_of_your_groups_gets),
            confirmLabel = stringResource(R.string.scan_send_sos),
            confirmTone = Tone.Danger,
            onConfirm = {
                showSosConfirm = false
                sosProgress = 0f
                onConfirmed()
                extras.textTo?.let { SosExtrasRun.sendText(context, it) }
                if (extras.any) showExtrasResult = true
            },
            onDismiss = { showSosConfirm = false; sosProgress = 0f },
            content = if (extras.any) {
                { SosExtrasSheet(extras, emergency, offerText = false) }
            } else {
                null
            }
        )
    }

    if (showExtrasResult) {
        SosExtrasResultDialog(extras, emergency, onDismiss = { showExtrasResult = false })
    }

    // The honest state when there is no one to send to: said before anything
    // is sent, instead of a "sent" that would be false.
    sosBlocked?.let { why ->
        val notRegistered = why == SosGate.NOT_REGISTERED
        // With an extra set up there is still something to do here, and "nothing was
        // sent" would turn false once the text goes: the sentence names Clairix.
        val nothing = if (extras.any) stringResource(R.string.scan_no_clairix_sos_was_sent) else stringResource(R.string.scan_nothing_was_sent)
        ClairixDialog(
            title = stringResource(R.string.scan_nobody_would_receive_this),
            text = if (notRegistered) {
                stringResource(R.string.scan_an_sos_goes_to_the_members, nothing)
            } else {
                stringResource(R.string.scan_an_sos_goes_to_the_members_2, nothing)
            },
            content = if (extras.any) {
                { SosExtrasSheet(extras, emergency, offerText = true) }
            } else {
                null
            },
            confirmLabel = if (notRegistered) stringResource(R.string.scan_set_up_comms) else stringResource(R.string.scan_open_comms),
            onConfirm = {
                sosBlocked = null
                sosProgress = 0f
                onSetUpComms()
            },
            onDismiss = { sosBlocked = null; sosProgress = 0f },
            dismissLabel = stringResource(R.string.comms_not_now)
        )
    }
}

/** A short warning line: the warning icon and [text], both in [tone]. */
@Composable
internal fun WarningLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val fg = tone.color()
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Filled.Warning, contentDescription = null, tint = fg,
            modifier = Modifier.padding(top = 2.dp).size(16.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

// ── Root composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearData: () -> Unit,
    hasPermissions: () -> Boolean,
    nfc: NfcHost?,
    onRefreshDeviceHealth: () -> Unit,
    onRefreshCell: () -> Unit,
    onRefreshWifi: () -> Unit
) {
    // The comms-only build has the one tab and draws no tab bar under it.
    val tabs = homeTabsFor(BuildConfig.FULL_ACCESS)

    // Where the user is. Every piece is saved by name or index, so a rotation or
    // a process restart puts them back on the same tab, category and sub-tab.
    var tabName by rememberSaveable { mutableStateOf(tabs.first().name) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var trackerActionName by rememberSaveable { mutableStateOf<String?>(null) }
    var commsActionName by rememberSaveable { mutableStateOf<String?>(null) }
    var trackerSub by rememberSaveable { mutableIntStateOf(0) }
    var phoneSub by rememberSaveable { mutableIntStateOf(0) }
    var activitySub by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // The Get help page, drawn over everything else when Settings, a safety card
    // or the sheet before a flagged app's settings opens it.
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val tab = enumByName<HomeTab>(tabName)?.takeIf { it in tabs } ?: tabs.first()
    val category = enumByName<ScanCategory>(categoryName)
    // The tracker actions belong to the Trackers category, and the Comms menu
    // holds what this edition has (commsActionsFor); a saved name outside those
    // is dropped rather than shown.
    val trackerAction = enumByName<TrackerAction>(trackerActionName)?.takeIf { category == ScanCategory.TRACKERS }
    val commsAction = enumByName<CommsAction>(commsActionName)?.takeIf { it in commsActionsFor(BuildConfig.FULL_ACCESS) }

    val status by Registry.status.collectAsStateWithLifecycle()
    val unreadMessages by CommsRepository.unread.collectAsStateWithLifecycle()
    val activeCall by CallManager.call.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The tour runs once on its own, after onboarding, and from Settings → About after that.
    var showTour by rememberSaveable { mutableStateOf(!isTourSeen(context)) }

    // The hub cards' status lines, and the red dot on the Scan tab, come from the
    // same readings. The comms-only build has no scanner to read.
    val statuses = if (BuildConfig.FULL_ACCESS) rememberCategoryStatuses() else emptyMap()

    // Threat explainer state; the sheet is drawn over whichever screen is up.
    var explainerTarget by remember { mutableStateOf<ExplainerTarget?>(null) }

    fun openTab(target: HomeTab) {
        if (target !in tabs) return
        if (target == tab) {
            // Tapping the tab that is already showing goes back to its top.
            when (target) {
                HomeTab.SCAN -> { categoryName = null; trackerActionName = null }
                HomeTab.COMMS -> commsActionName = null
                HomeTab.DRIVE, HomeTab.ACTIVITY -> Unit
            }
        }
        tabName = target.name
    }

    fun openCategory(target: ScanCategory) {
        categoryName = target.name
        trackerActionName = null
    }

    // "Tap to resume" on the hub and the timeline: the scanner starts straight away
    // when it may; otherwise the Trackers screen asks for what it still needs.
    val resumeScan: () -> Unit = {
        if (hasPermissions()) onStart() else { openTab(HomeTab.SCAN); openCategory(ScanCategory.TRACKERS) }
    }

    // A call ringing or in progress takes the Comms tab regardless of where the
    // user was. Settings, Get help, the Comms safety pages and the explainer sheet are drawn
    // over or instead of it, so they close too; otherwise the call's ACCEPT button
    // sat hidden behind them.
    LaunchedEffect(activeCall?.id, activeCall?.phase) {
        if (activeCall != null && activeCall?.phase != CallPhase.ENDED && HomeTab.COMMS in tabs) {
            tabName = HomeTab.COMMS.name
            commsActionName = null
            showSettings = false
            showHelp = false
            showTour = false
            explainerTarget = null
        }
    }

    // A tag delivered by a system NFC intent lands on Scan > NFC; a message
    // notification or an invite link lands on the Comms tab.
    val tabRequest by Registry.tabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        if (tabRequest == null) return@LaunchedEffect
        when (Registry.takeTabRequest()) {
            ROUTE_COMMS -> if (HomeTab.COMMS in tabs) {
                tabName = HomeTab.COMMS.name
                commsActionName = null
                showSettings = false
            }
            ROUTE_NFC -> if (HomeTab.SCAN in tabs) {
                tabName = HomeTab.SCAN.name
                openCategory(ScanCategory.NFC)
                showSettings = false
            }
            else -> Unit
        }
    }
    // A safety card, or the sheet before a flagged app's settings, asks for the
    // Get help page: it opens over whatever is showing, the explainer sheet included.
    val helpRequest by Registry.helpRequest.collectAsStateWithLifecycle()
    LaunchedEffect(helpRequest) {
        if (helpRequest && Registry.takeHelpRequest()) {
            explainerTarget = null
            showHelp = true
        }
    }
    // Locking closes Get help, so an unlock someone else makes the owner do does
    // not open on a page of helplines. It closes the explainer sheet too: a sheet
    // is a window of its own, above the lock screen Clairix draws, and it carries
    // the safety card's export.
    LaunchedEffect(locked) {
        if (locked) {
            showHelp = false
            explainerTarget = null
            if (commsActionName == CommsAction.GET_HELP.name) commsActionName = null
        }
    }
    val threadRequest by Registry.threadRequest.collectAsStateWithLifecycle()
    val groupRequest by Registry.groupRequest.collectAsStateWithLifecycle()
    val inviteRequest by Registry.inviteRequest.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showTour) {
        fun close() { markTourSeen(context); showTour = false }
        TourScreen(
            onClose = { close() },
            onGo = { target ->
                close()
                showSettings = false
                when (target) {
                    is TourTarget.Tab -> {
                        if (target.tab == HomeTab.SCAN) { categoryName = null; trackerActionName = null }
                        if (target.tab == HomeTab.COMMS) commsActionName = null
                        tabName = target.tab.name
                    }
                    is TourTarget.Category -> {
                        tabName = HomeTab.SCAN.name
                        categoryName = target.category.name
                        trackerActionName = null
                        if (target.category == ScanCategory.TRACKERS) trackerSub = target.sub
                        if (target.category == ScanCategory.PHONE) phoneSub = target.sub
                    }
                    is TourTarget.TrackerTool -> {
                        tabName = HomeTab.SCAN.name
                        categoryName = ScanCategory.TRACKERS.name
                        trackerActionName = target.action.name
                    }
                    is TourTarget.CommsPage -> {
                        tabName = HomeTab.COMMS.name
                        commsActionName = target.action.name
                    }
                    is TourTarget.ActivityPage -> {
                        tabName = HomeTab.ACTIVITY.name
                        activitySub = target.sub
                    }
                    TourTarget.Settings -> showSettings = true
                }
            }
        )
        return
    }

    // Over Settings when opened from there, so its back arrow returns to Settings.
    if (showHelp) {
        GetHelpPage(onBack = { showHelp = false })
        return
    }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onClearData = onClearData,
            onTour = { showSettings = false; showTour = true },
            onGetHelp = { showHelp = true }
        )
        return
    }

    // One step back inside a tab: a tracker action to its category, a category to
    // the hub, a Comms safety page to the conversations. Screens with pages of
    // their own register their handlers later, so theirs are asked first.
    val popUp: (() -> Unit)? = when (tab) {
        HomeTab.SCAN -> when {
            trackerAction != null -> { { trackerActionName = null } }
            category != null -> { { categoryName = null; trackerActionName = null } }
            else -> null
        }
        HomeTab.COMMS -> if (commsAction != null) { { commsActionName = null } } else null
        HomeTab.DRIVE, HomeTab.ACTIVITY -> null
    }
    BackHandler(enabled = popUp != null) { popUp?.invoke() }
    // Quick exit: Back on the first screen (the Scan hub; Comms in the comms-only build)
    // sends Clairix to the background and locks it at once when a passcode is set.
    BackHandler(enabled = popUp == null && tab == tabs.first()) { QuickExit.leave(context) }

    val title = when (tab) {
        HomeTab.SCAN -> trackerAction?.title ?: category?.title ?: HomeTab.SCAN.label
        HomeTab.COMMS -> commsAction?.title ?: HomeTab.COMMS.label
        HomeTab.DRIVE, HomeTab.ACTIVITY -> tab.label
    }
    val menu = if (tab == HomeTab.COMMS && commsAction == null) {
        commsActionsFor(BuildConfig.FULL_ACCESS).map { action -> ShellMenuItem(action.title, action.icon) { commsActionName = action.name } }
    } else {
        emptyList()
    }

    fun alertFor(t: HomeTab): Boolean = when (t) {
        HomeTab.SCAN -> statuses.values.any { it.alert }
        HomeTab.COMMS -> unreadMessages > 0
        HomeTab.DRIVE, HomeTab.ACTIVITY -> false
    }

    ShellScaffold(
        title = title,
        onBack = popUp,
        onSettings = { showSettings = true },
        menu = menu,
        tabs = tabs,
        selected = tab,
        alert = { alertFor(it) },
        onSelectTab = { openTab(it) },
        header = {
            when {
                tab == HomeTab.SCAN && trackerAction == null && category == ScanCategory.TRACKERS -> {
                    ShellSubTabs(ScanCategory.TRACKERS.subTabs, trackerSub) { trackerSub = it }
                    TrackerActionsRow { action -> trackerActionName = action.name }
                }
                tab == HomeTab.SCAN && trackerAction == null && category == ScanCategory.PHONE ->
                    ShellSubTabs(ScanCategory.PHONE.subTabs, phoneSub) { phoneSub = it }
                tab == HomeTab.ACTIVITY ->
                    ShellSubTabs(ACTIVITY_SUB_TABS, activitySub) { activitySub = it }
            }
        },
        // SOS panic button, floating over the active screen. Hidden while the
        // phone is locked; no animation needed for a critical safety button.
        sos = if (locked) null else {
            {
                SosButton(
                    // Who would receive it is checked before the confirm dialog: with
                    // no Clairix number, or no group with anyone else in it, the dialog
                    // says so and offers Comms instead of a send that goes nowhere.
                    gate = { CommsRepository.sosGate() },
                    onSetUpComms = { openTab(HomeTab.COMMS) },
                    onConfirmed = {
                        scope.launch(Dispatchers.IO) {
                            suspend fun toast(text: String) = withContext(Dispatchers.Main) {
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                            }
                            // Members the relay could not take are tried again for a
                            // while; the last word follows when it differs from the first.
                            fun followUp(sent: PanicResult, subject: String) = launch {
                                val last = CommsRepository.panicResult.first { it == null || it.attempt != sent.attempt || !it.retrying }
                                if (last != null && last.attempt == sent.attempt && last.reached != sent.reached) toast(last.headline(subject))
                            }
                            // The alert goes out at once with whatever position is known;
                            // a fix found afterwards follows as a second alert. The toast
                            // says what happened: it comes after the sends, never before.
                            val s = Registry.status.value
                            val hadFix = s.lat != null && s.lon != null
                            val first = CommsRepository.sendSos()
                            val findingFix = !hadFix && first.blocked == null && first.error == null
                            toast(first.headline() + if (findingFix) context.getString(R.string.scan_finding_your_location) else "")
                            if (first.retrying) followUp(first, "SOS")
                            if (!findingFix) return@launch
                            // Never an app's faked position, and the toast says why there is none.
                            SosLocation.missing(SosLocation.locate(context))?.let { toast(it); return@launch }
                            val second = CommsRepository.sendSos()
                            toast(second.headline(context.getString(R.string.scan_your_location)))
                            if (second.retrying) followUp(second, "Your location")
                        }
                    }
                )
            }
        }
    ) {
        when (tab) {
            HomeTab.SCAN -> when {
                trackerAction == TrackerAction.LOCATE -> FindTrackerPage()
                trackerAction == TrackerAction.SELF_TEST -> SelfTestPage()
                trackerAction == TrackerAction.BLUETOOTH -> BluetoothExposurePage()
                category == null -> ScanHub(
                    scanning = status.scanning,
                    statuses = statuses,
                    onOpen = { openCategory(it) },
                    error = status.error,
                    stoppedBySystemAt = status.stoppedAt.takeIf { status.stoppedBySystem && it > 0L },
                    scanEnabled = AppSettings.scanEnabled,
                    onResumeScan = resumeScan
                )
                category == ScanCategory.TRACKERS -> if (trackerSub == 0) {
                    ScanScreen(
                        onStart, onStop, hasPermissions,
                        onShowExplainer = { d -> explainerTarget = ExplainerTarget.BleDevice(d) }
                    )
                } else {
                    // The trail of where she has been: behind the passcode whenever one is set.
                    OwnerGate(title = stringResource(R.string.scan_map), purpose = stringResource(R.string.scan_open_the_map), onBack = null) { MapScreen() }
                }
                category == ScanCategory.CAMERAS -> CameraSweepScreen()
                category == ScanCategory.CELL -> CellScreen(
                    onShowExplainer = { f -> explainerTarget = ExplainerTarget.CellIndicator(f) },
                    onShown = onRefreshCell
                )
                category == ScanCategory.GPS -> GnssScreen()
                category == ScanCategory.ULTRASOUND -> UltrasoundScreen()
                category == ScanCategory.WIFI -> WifiScreen(onShown = onRefreshWifi)
                category == ScanCategory.NFC -> nfc?.Screen()
                category == ScanCategory.PHONE -> when (phoneSub) {
                    0 -> DeviceScreen(onShown = onRefreshDeviceHealth, onOpenAccounts = { phoneSub = ACCOUNTS_SUB_TAB })
                    1 -> StalkerwarePage()
                    2 -> CertificatesPage()
                    else -> AccountChecklistPage()
                }
            }
            HomeTab.COMMS -> {
                // Every comms screen keeps the window out of screenshots while it is up.
                KeepOffScreen()
                when (commsAction) {
                    CommsAction.GET_HELP -> GetHelpPage()
                    CommsAction.FAKE_CALL -> FakeCallPage()
                    CommsAction.DURESS -> DuressPage()
                    CommsAction.MESH -> MeshPage()
                    CommsAction.WIPE -> WipePage()
                    null -> CommsScreen(
                        openPeer = threadRequest,
                        onPeerConsumed = { Registry.takeThreadRequest() },
                        openInvite = inviteRequest,
                        onInviteConsumed = { Registry.takeInviteRequest() },
                        openGroup = groupRequest,
                        onGroupConsumed = { Registry.takeGroupRequest() },
                        onAppSettings = { showSettings = true }
                    )
                }
            }
            HomeTab.DRIVE -> Tier.driveTab?.invoke()
            HomeTab.ACTIVITY -> when (activitySub) {
                0 -> TimelineScreen(
                    onResumeScan = resumeScan,
                    onShowExplainer = { e -> explainerTarget = ExplainerTarget.Journeys(e) }
                )
                1 -> PatternOfLifePage()
                else -> UnlockLedgerPage()
            }
        }

    }

    // Threat explainer bottom sheet
    val target = explainerTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { explainerTarget = null },
            sheetState = sheetState,
            containerColor = ClairixTheme.colors.surfaceRaised,
            shape = ClairixShapes.Sheet
        ) {
            ThreatExplainerSheet(target = target, onDismiss = { explainerTarget = null })
        }
    }
}

// ── Scan (BLE) screen ────────────────────────────────────────────────────

@Composable
internal fun ScanScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasPermissions: () -> Boolean,
    onShowExplainer: (Detection) -> Unit = {}
) {
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val now = rememberNow()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // Start as soon as the scan can actually run. Notifications being declined
        // costs the alert, not the detector.
        if (ESSENTIAL.all { grants[it] == true }) onStart()
    }

    // Devices the user has vouched for drop out of the threat picture and sink to the bottom.
    val live = remember(detections, trusted) { detections.filter { it.address !in trusted } }
    val ordered = remember(detections, trusted) { detections.sortedBy { it.address in trusted } }

    ScreenList {
        // ── Status first: what the scan says, and the switch that runs it ──
        item(key = "§status") {
            TrackerStatusHero(
                status = status,
                live = live,
                now = now,
                onToggle = {
                    if (status.scanning) onStop()
                    else if (hasPermissions()) onStart()
                    else launcher.launch(REQUIRED)
                }
            )
        }

        if (status.scanning) {
            item(key = "§figures") { ScanFigures(status, detections, live, cell) }
        }

        if (status.scanning || status.error != null) {
            item(key = "§details") { ScanDetailsCard(status, detections, trusted, now) }
        }

        // ── Findings ──
        // With the scanner off the hero already says so; an empty list adds nothing.
        if (detections.isEmpty()) {
            if (status.scanning) item(key = "§empty") {
                EmptyState(
                    title = stringResource(R.string.scan_nothing_flagged_yet),
                    // The thresholds as set in Settings, not the defaults.
                    body = stringResource(R.string.scan_proving_something_follows_you_takes_leave, FollowerStatus.proof(AppSettings.persistenceThresholdMin, AppSettings.followThresholdM)),
                    icon = ClairixIcons.BluetoothSearching,
                    tone = Tone.Safe
                )
            }
        } else {
            item(key = "§list-label") {
                SectionHeader(
                    stringResource(R.string.scan_flagged_devices),
                    supporting = stringResource(R.string.scan_live_trusted, live.size, detections.size - live.size)
                )
            }
            items(ordered, key = { it.key }) { d ->
                DetectionRow(
                    d = d,
                    trusted = d.address in trusted,
                    now = now,
                    onShowExplainer = if (d.threat == Threat.CRITICAL || d.threat == Threat.HIGH)
                        { { onShowExplainer(d) } } else null
                )
            }
        }

        // ── Actions ──
        item(key = "§export") {
            // With a passcode set, each export asks for it (or a biometric) every time;
            // then the period and the warning, then the share sheet.
            EvidenceExportButtons(stringResource(R.string.scan_export_evidence_report), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** The verdict the Trackers screen leads with, and the start/stop switch under it. */
@Composable
private fun TrackerStatusHero(status: ScanStatus, live: List<Detection>, now: Long, onToggle: () -> Unit) {
    val context = LocalContext.current
    // Branch on the threat, not on `following` alone: a recognised tracker that moved
    // with you is a tracker on you; an unidentified device that did is travelling with
    // you, which a companion's phone also does. Only what was heard in the last half
    // hour is on you now; a device marked found counts for nothing here.
    val trackers = live.filter { FollowerStatus.onYouNow(it, now) && it.threat == Threat.CRITICAL }
    val riders = live.filter { FollowerStatus.onYouNow(it, now) && it.threat != Threat.CRITICAL }
    val earlier = live.filter { FollowerStatus.followedEarlier(it, now) && it.threat == Threat.CRITICAL }
    val persistent = live.count { it.persistent && !it.resolved }
    val error = status.error
    val positioning by Registry.positioning.collectAsStateWithLifecycle()
    val faking = positioning.faking(System.currentTimeMillis())
    val (headline, body, tone) = when {
        // Wanted on and unable to scan (Bluetooth off, a refused restart): the cause,
        // which is what there is to act on, as the hub puts it first too.
        !status.scanning && error != null -> Triple(stringResource(R.string.scan_scanner_cannot_hear), error, Tone.Caution)
        // Ended by an update, a kill or a battery manager: said so, not "off" as if she had.
        !status.scanning && status.stoppedBySystem -> Triple(
            stringResource(R.string.scan_scanner_stopped),
            ScannerHealth.stoppedBySystemLine(status.stoppedAt),
            Tone.Caution
        )
        !status.scanning -> Triple(
            stringResource(R.string.scan_scanner_off),
            stringResource(R.string.scan_start_scanning_to_check_for_bluetooth),
            Tone.Neutral
        )
        trackers.size == 1 -> Triple(
            stringResource(R.string.scan_tracker_on_you),
            stringResource(R.string.scan_was_heard_m_apart_while_you, trackers[0].name, trackers[0].displacementM.roundToInt()),
            Tone.Danger
        )
        trackers.size > 1 -> Triple(
            stringResource(R.string.scan_trackers_on_you, trackers.size),
            stringResource(R.string.scan_they_were_heard_in_different_places),
            Tone.Danger
        )
        riders.size == 1 -> Triple(
            stringResource(R.string.scan_a_device_is_travelling_with_you),
            stringResource(R.string.scan_was_heard_m_apart_while_you_2, riders[0].name, riders[0].displacementM.roundToInt()),
            Tone.Caution
        )
        riders.size > 1 -> Triple(
            stringResource(R.string.scan_devices_are_travelling_with_you, riders.size),
            stringResource(R.string.scan_they_were_heard_in_different_places_2),
            Tone.Caution
        )
        // Confirmed earlier and quiet since: asleep, out of range, or still where it was put.
        earlier.isNotEmpty() -> Triple(
            if (earlier.size == 1) stringResource(R.string.scan_a_tracker_followed_you_earlier) else stringResource(R.string.scan_trackers_followed_you_earlier, earlier.size),
            stringResource(R.string.scan_a_tracker_can_sleep_or_be, (if (earlier.size == 1) stringResource(R.string.scan_was_last_heard, earlier[0].name, fmtAgo(appResources(), now, earlier[0].lastSeen))
            else stringResource(R.string.scan_the_last_was_heard, fmtAgo(appResources(), now, earlier.maxOf { it.lastSeen })))),
            Tone.Caution
        )
        // Registered and hearing nothing (Location off, five quiet minutes): a confirmed
        // follower or rider above still stands, but "nothing following" below would be unproven.
        error != null -> Triple(stringResource(R.string.scan_scanner_cannot_hear), error, Tone.Caution)
        // An app supplies the phone's positions: following can be neither confirmed nor
        // ruled out, so "nothing following" below would be unproven too.
        faking -> Triple(stringResource(R.string.scan_position_faked), app.clairix.analysis.PositionText.FAKED, Tone.Caution)
        persistent > 0 -> Triple(
            stringResource(R.string.scan_keep_watching),
            pluralStringResource(R.plurals.scan_devices_keep_showing_up, persistent, persistent, AppSettings.followThresholdM.toInt()),
            Tone.Caution
        )
        else -> Triple(
            stringResource(R.string.scan_nothing_following),
            pluralStringResource(R.plurals.scan_watching_devices, status.nearbyCount, status.nearbyCount),
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = if (status.scanning) stringResource(R.string.scan_trackers_scanning) else stringResource(R.string.scan_trackers),
        headline = headline,
        body = body,
        tone = tone,
        modifier = Modifier.padding(top = 8.dp),
        footer = {
            if (status.scanning) {
                if (error == ScannerHealth.LOCATION_OFF) {
                    SecondaryButton(
                        stringResource(R.string.scan_open_location_settings),
                        onClick = { openExternal(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        tone = Tone.Accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SecondaryButton(stringResource(R.string.scan_stop_scanning), onClick = onToggle, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(
                    if (status.stoppedBySystem) stringResource(R.string.scan_resume_scanning) else stringResource(R.string.scan_start_scanning),
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

/** Threat level, devices in range and devices flagged, side by side. */
@Composable
private fun ScanFigures(status: ScanStatus, detections: List<Detection>, live: List<Detection>, cell: CellStatus) {
    val bleScore = live.maxOfOrNull { it.score } ?: 0
    val cellScore = if (cell.available) cell.score else 0
    val score = maxOf(bleScore, cellScore).coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = ClairixMotion.reveal),
        label = "threat"
    )
    val scoreTone = when {
        score >= 70 -> Tone.Danger
        score >= 30 -> Tone.Caution
        else -> Tone.Safe
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("$score", stringResource(R.string.scan_threat_level_of_100), Modifier.weight(1f), tone = scoreTone)
            StatTile("${status.nearbyCount}", stringResource(R.string.scan_in_range), Modifier.weight(1f))
            StatTile(
                "${detections.size}", stringResource(R.string.scan_flagged), Modifier.weight(1f),
                tone = when {
                    live.any { it.following && it.threat == Threat.CRITICAL } -> Tone.Danger
                    live.any { it.following } -> Tone.Caution
                    else -> Tone.Neutral
                }
            )
        }
        ClairixCard(spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.scan_threat_level),
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.text,
                    modifier = Modifier.weight(1f)
                )
                Text("$score / 100", style = MaterialTheme.typography.mono, color = meterColor(fraction))
            }
            ClairixProgressBar(fraction, tone = scoreTone)
            Text(
                stringResource(R.string.scan_bluetooth_peak_cellular_the_higher_of, bleScore, cellScore),
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textSubtle
            )
        }
    }
}

/** What the scan is working with: position, filtered noise, trusted devices, errors. */
@Composable
private fun ScanDetailsCard(status: ScanStatus, detections: List<Detection>, trusted: Set<String>, now: Long) {
    // Where fixes come from, whether the GPS check can compare, whether an app fakes positions.
    val positioning by Registry.positioning.collectAsStateWithLifecycle()
    ClairixCard(spacing = 4.dp) {
        val scanText = when {
            !status.scanning -> stringResource(R.string.scan_idle)
            status.nearbyCount > detections.size -> stringResource(R.string.scan_flagged_of_in_range, detections.size, status.nearbyCount)
            else -> "${plural(detections.size, "device")} flagged"
        }
        KeyValueRow(stringResource(R.string.scan_scan), scanText)
        val filtered = status.nearbyCount - detections.size
        if (status.scanning && filtered > 0) {
            KeyValueRow(stringResource(R.string.scan_filtered_out), stringResource(R.string.scan_passing_signals, filtered))
        }
        // The scan's own pulse: a scan that is registered but hears nothing shows it here
        // first. The time is published to the half minute (see ScannerHealth), so it reads
        // to the minute.
        if (status.scanning) {
            val heard = status.lastPacketAt
            KeyValueRow(
                stringResource(R.string.scan_last_heard),
                when {
                    heard == 0L -> stringResource(R.string.scan_nothing_yet)
                    now - heard < 60_000L -> stringResource(R.string.scan_within_the_last_minute)
                    else -> fmtAgo(appResources(), now, heard)
                }
            )
        }
        if (status.packetErrors > 0) {
            KeyValueRow(stringResource(R.string.scan_packet_errors), "${status.packetErrors}", valueColor = ClairixTheme.colors.caution)
        }
        if (status.hasFix) {
            KeyValueRow(stringResource(R.string.settings_position), "%.4f, %.4f".format(Locale.US, status.lat ?: 0.0, status.lon ?: 0.0))
            KeyValueRow(
                stringResource(R.string.scan_movement),
                "${if (status.moving) "Moving" else "Still"} · %.1f km".format(Locale.US, status.travelledM / 1000.0)
            )
        } else {
            WarningLine(app.clairix.analysis.PositionText.noFix(positioning.source, positioning.faking(now)), Tone.Caution)
        }
        // Said when fixes do not come from Google Play services, and when the satellites
        // cannot be held against a Wi-Fi/cell position here.
        app.clairix.analysis.PositionText.source(positioning.source)?.let { line ->
            if (app.clairix.analysis.PositionText.sourceIsProblem(positioning.source)) WarningLine(line, Tone.Caution)
            else Text(line, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        }
        app.clairix.analysis.PositionText.network(positioning.networkCheck)?.let { line ->
            if (app.clairix.analysis.PositionText.networkIsProblem(positioning.networkCheck)) WarningLine(line, Tone.Caution)
            else Text(line, style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
        }
        if (trusted.isNotEmpty()) {
            KeyValueRow(stringResource(R.string.scan_marked_safe), pluralStringResource(R.plurals.scan_trusted_left_out, trusted.size, trusted.size), mono = false)
        }
        status.error?.let { WarningLine(it, Tone.Danger) }
    }
}

@Composable
private fun RssiBar(rssi: Int) {
    val strength = if (rssi == 0) 0f else ((rssi + 100) / 60f).coerceIn(0f, 1f)
    val tone = when {
        rssi == 0 -> Tone.Neutral
        rssi >= -75 -> Tone.Danger
        rssi >= -90 -> Tone.Caution
        else -> Tone.Neutral
    }
    val c = ClairixTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {}
    ) {
        Box(Modifier.width(48.dp).height(6.dp).background(c.border)) {
            Box(Modifier.fillMaxWidth(strength).fillMaxHeight().background(if (tone == Tone.Neutral) c.textSubtle else tone.color()))
        }
        Text(
            "${fmtDbm(rssi)} · ${BleNames.signalLabel(rssi)}",
            style = MaterialTheme.typography.monoSmall,
            color = c.textMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun DetectionRow(
    d: Detection,
    trusted: Boolean,
    now: Long,
    onShowExplainer: (() -> Unit)? = null
) {
    var expanded by remember(d.key) { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = ClairixMotion.base),
        label = "chevron"
    )
    val c = ClairixTheme.colors
    val tone = if (trusted) null else d.threat.tone()

    ClairixCard(
        tone = tone,
        emphasis = FollowerStatus.withYou(d) && d.threat == Threat.CRITICAL && !trusted,
        onClick = { expanded = !expanded },
        onClickLabel = if (expanded) stringResource(R.string.scan_hide_details) else stringResource(R.string.scan_show_details)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                d.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (trusted) c.textMuted else c.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                trusted -> StatusPill(stringResource(R.string.scan_trusted), Tone.Neutral)
                d.resolved -> StatusPill(stringResource(R.string.scan_found), Tone.Neutral)
                else -> StatusPill(if (d.following) followingPill(d) else levelLabel(d.threat.name), d.threat.tone())
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textSubtle,
                modifier = Modifier.rotate(chevron)
            )
        }

        val t = d.tracker
        if (t != null) {
            // A signature match is the strongest identification there is; the
            // manufacturer/service line would only repeat "Apple".
            Text("${t.label} · ${t.brand}", style = MaterialTheme.typography.bodyMedium, color = if (trusted) c.textSubtle else c.text)
        } else {
            Text(d.summary, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.sightings}× · ${fmtAgo(appResources(), now, d.lastSeen)}",
                style = MaterialTheme.typography.monoSmall,
                color = c.textSubtle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RssiBar(d.rssi)
        }

        if (d.resolved && !trusted) {
            Text(
                stringResource(R.string.scan_marked_found_at_it_no_longer, fmtClock(d.resolvedAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )
        } else if (d.following && !trusted) {
            if (d.threat == Threat.CRITICAL) {
                WarningLine(stringResource(R.string.scan_moving_with_you_heard_m_apart, d.displacementM.roundToInt()), Tone.Danger)
            } else {
                WarningLine(stringResource(R.string.scan_travelling_with_you_heard_m_apart, d.displacementM.roundToInt()), Tone.Caution)
            }
        }

        if (expanded) DetectionDetail(d, trusted, onShowExplainer)
    }
}

@Composable
private fun DetectionDetail(d: Detection, trusted: Boolean, onShowExplainer: (() -> Unit)? = null) {
    val c = ClairixTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()

        // ── Follow assessment first: it is the answer to "is this on me?" ──
        SectionHeader(stringResource(R.string.scan_is_it_following_you))
        // No position because an app supplies fake ones, said as that rather than as no GPS.
        val positioning by Registry.positioning.collectAsStateWithLifecycle()
        val faking = positioning.faking(System.currentTimeMillis())
        val (line, lineTone) = when (d.confidence) {
            FollowConfidence.NONE -> stringResource(R.string.scan_not_enough_data_yet) to Tone.Neutral
            FollowConfidence.NO_POSITION ->
                (if (faking) app.clairix.analysis.PositionText.FAKED_CONFIDENCE else stringResource(R.string.scan_no_position_fix_so_movement_cant)) to Tone.Caution
            FollowConfidence.NOT_MOVED_ENOUGH -> stringResource(R.string.scan_nearby_not_following) to Tone.Caution
            FollowConfidence.CONFIRMED ->
                if (d.threat == Threat.CRITICAL) stringResource(R.string.scan_following_you) to Tone.Danger else stringResource(R.string.scan_travelling_with_you) to Tone.Caution
        }
        Text(line, style = MaterialTheme.typography.titleSmall, color = if (trusted) c.textSubtle else lineTone.color())
        val explanation = when (d.confidence) {
            FollowConfidence.NONE ->
                if (d.identified) stringResource(R.string.scan_known_tracker_type_but_heard_too)
                else stringResource(R.string.scan_watching_for_a_pattern_of_it)
            FollowConfidence.NO_POSITION ->
                if (faking) app.clairix.analysis.PositionText.FAKED_CONFIDENCE_DETAIL
                else stringResource(R.string.scan_it_keeps_showing_up_a_position)
            FollowConfidence.NOT_MOVED_ENOUGH ->
                stringResource(R.string.scan_it_keeps_showing_up_but_only, d.displacementM.roundToInt(), AppSettings.followThresholdM.roundToInt())
            FollowConfidence.CONFIRMED ->
                if (d.threat == Threat.CRITICAL) {
                    stringResource(R.string.scan_heard_m_apart_while_moving_with, d.displacementM.roundToInt())
                } else {
                    stringResource(R.string.scan_heard_m_apart_while_moving_with_2, d.displacementM.roundToInt())
                }
        }
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)

        // ── What it is ──
        SectionHeader(stringResource(R.string.scan_tracker_type))
        val t = d.tracker
        if (t != null) {
            Column(
                Modifier.fillMaxWidth().background(c.surfaceRaised, ClairixShapes.Card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                    StatusPill(levelLabel(t.threat.name), t.threat.tone())
                }
                Text(stringResource(R.string.scan_made_by, t.brand), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                Text(t.notes, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        } else {
            Text(
                stringResource(R.string.scan_no_known_tracker_signature_matched_flagged),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSubtle
            )
        }

        // What the radio itself says, resolved through the Bluetooth SIG assigned numbers.
        SectionHeader(stringResource(R.string.scan_identification))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValueRow(stringResource(R.string.scan_advertised_name), d.advertisedName ?: stringResource(R.string.scan_none_broadcast), valueColor = if (d.advertisedName != null) c.text else c.textSubtle)
            KeyValueRow(stringResource(R.string.scan_manufacturer), d.manufacturer ?: stringResource(R.string.scan_not_advertised), valueColor = if (d.manufacturer != null) c.textMuted else c.textSubtle)
            KeyValueRow(stringResource(R.string.comms_radio), d.radio)
            KeyValueRow(
                stringResource(R.string.scan_services),
                if (d.services.isEmpty()) stringResource(R.string.scan_none_advertised) else d.services.joinToString(" / "),
                valueColor = if (d.services.isEmpty()) c.textSubtle else c.textMuted
            )
            KeyValueRow(stringResource(R.string.scan_address), BleNames.formatMac(d.address) + (d.addressKind?.let { " · $it" } ?: ""))
        }

        SectionHeader(stringResource(R.string.scan_observation))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValueRow(stringResource(R.string.scan_first_seen), fmtTime(d.firstSeen))
            KeyValueRow(stringResource(R.string.scan_last_seen), fmtTime(d.lastSeen))
            KeyValueRow(stringResource(R.string.scan_present_for), fmtDuration(d.lastSeen - d.firstSeen))
            KeyValueRow(
                stringResource(R.string.scan_sightings),
                if (d.places > 0) "${d.sightings} across ${plural(d.places, "place")}"
                else stringResource(R.string.scan_no_location_data, d.sightings)
            )
            KeyValueRow(stringResource(R.string.scan_distance), d.approxMetres?.let { fmtMetres(it) } ?: stringResource(R.string.scan_unknown))
            KeyValueRow(stringResource(R.string.scan_signal), "${fmtDbm(d.rssi)} · ${BleNames.signalLabel(d.rssi)}")
            KeyValueRow(stringResource(R.string.scan_spread), stringResource(R.string.scan_m_apart, d.displacementM.roundToInt()))
            KeyValueRow(
                stringResource(R.string.scan_address_changes),
                if (d.rotations > 0) pluralStringResource(R.plurals.scan_rotations_addresses, d.addresses, d.rotations, d.addresses)
                else stringResource(R.string.scan_stable_address),
                valueColor = if (d.rotations > 0) c.caution else c.textMuted
            )
            KeyValueRow(stringResource(R.string.scan_threat_score), "${d.score} / 100", valueColor = if (trusted) c.textSubtle else d.threat.tone().color())
        }

        // ── Actions ──
        val context = LocalContext.current
        val scanning = Registry.status.collectAsStateWithLifecycle().value.scanning
        if (trusted) {
            SecondaryButton(
                stringResource(R.string.scan_put_it_back_in_the_threat),
                onClick = { Registry.untrust(d.address) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            if (onShowExplainer != null) {
                PrimaryButton(stringResource(R.string.scan_what_is_this_and_what_do), onClick = onShowExplainer, modifier = Modifier.fillMaxWidth())
            }
            // Found and dealt with: it stops counting as on you, without teaching the
            // app to ignore it if it comes back. Acts on the running scanner's record.
            if (d.resolved) {
                SecondaryButton(
                    stringResource(R.string.scan_not_found_after_all),
                    onClick = { ScanService.sendFound(context, d.key, found = false) },
                    enabled = scanning,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (d.following || d.persistent) {
                SecondaryButton(
                    stringResource(R.string.scan_i_found_it),
                    onClick = { ScanService.sendFound(context, d.key, found = true) },
                    tone = Tone.Accent,
                    enabled = scanning,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.scan_once_you_have_found_and_removed),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSubtle
                )
            }
            val rotating = TrustBook.rotating(d)
            SecondaryButton(
                stringResource(R.string.scan_mark_as_mine),
                onClick = { Registry.trust(d.address, label = d.name, rotating = rotating) },
                tone = Tone.Safe,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (rotating) {
                    stringResource(R.string.scan_marking_it_as_yours_removes_it)
                } else {
                    stringResource(R.string.scan_marking_it_as_yours_removes_it_2)
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle
            )
        }
    }
}

// ── Map screen ─────────────────────────────────────────────────────────

private data class MarkerKey(val argbFill: Int, val sizeDp: Int, val outlineArgb: Int?)

/**
 * The theme's colours as the ARGB ints osmdroid draws with, read once per
 * composition so the map follows the selected theme like everything else.
 */
private data class MarkerPalette(
    val danger: Int,
    val caution: Int,
    val subtle: Int,
    val info: Int,
    val ring: Int
)

/**
 * Marker icons by (colour, size, outline). The overlays are rebuilt whenever a fix
 * or a device position changes and used to allocate a fresh bitmap for every marker
 * each time; there are only a handful of distinct icons, so they are drawn once and
 * shared.
 *
 * Touched only from the main thread (composition and effects).
 */
private val markerCache = HashMap<MarkerKey, BitmapDrawable>()

private fun markerBitmap(argbFill: Int, sizeDp: Int, outlineArgb: Int? = null): BitmapDrawable =
    markerCache.getOrPut(MarkerKey(argbFill, sizeDp, outlineArgb)) {
        drawMarkerBitmap(argbFill, sizeDp, outlineArgb)
    }

private fun drawMarkerBitmap(argbFill: Int, sizeDp: Int, outlineArgb: Int?): BitmapDrawable {
    val px = (sizeDp * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    val bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bm)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = argbFill
        style = AndroidPaint.Style.FILL
    }
    canvas.drawCircle(px / 2f, px / 2f, px / 2f - 1f, paint)
    if (outlineArgb != null) {
        paint.color = outlineArgb
        paint.style = AndroidPaint.Style.STROKE
        paint.strokeWidth = px * 0.18f
        canvas.drawCircle(px / 2f, px / 2f, px / 2f - 2f, paint)
    }
    return BitmapDrawable(Resources.getSystem(), bm)
}

private fun threatArgb(p: MarkerPalette, threat: Threat, following: Boolean): Int = when {
    following || threat.ordinal >= Threat.HIGH.ordinal -> p.danger
    threat.ordinal >= Threat.MEDIUM.ordinal -> p.caution
    else -> p.info
}

/**
 * Colour of a device's trail polyline, by threat level alone. LOW is the quiet
 * text colour so it reads as background next to the info-coloured GPS track.
 */
private fun trailArgb(p: MarkerPalette, threat: Threat): Int = when (threat) {
    Threat.CRITICAL, Threat.HIGH -> p.danger
    Threat.MEDIUM -> p.caution
    else -> p.subtle
}

/** osmdroid's global configuration, once per process: see [app.clairix.nav.MapTiles]. Main thread only (called from composition). */
private fun configureOsm(context: android.content.Context) = app.clairix.nav.MapTiles.configure(context)

@Composable
private fun MapScreen() {
    val mapData by Registry.map.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val sharedThreats by Registry.sharedThreats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal
    // A recognised tracker moving with you is a tracker on you; anything else that did
    // is only travelling with you, as a companion's phone does.
    val followingTrails = mapData.devices.count { it.following && it.threat == Threat.CRITICAL }
    val travellingTrails = mapData.devices.count { it.following && it.threat != Threat.CRITICAL }
    val theme = ClairixTheme.colors
    val palette = MarkerPalette(
        danger = theme.danger.toArgb(),
        caution = theme.caution.toArgb(),
        subtle = theme.textSubtle.toArgb(),
        info = theme.info.toArgb(),
        ring = theme.text.toArgb()
    )

    val mapView = remember {
        configureOsm(context)
        // The tile server is the signed tile setting's (OpenStreetMap's until one
        // names another), and every tile answer is counted for the notice below.
        app.clairix.nav.MapTiles.newMapView(context).apply {
            // Tiles are 256 px; drawn unscaled, a phone screen at zoom 17 is around
            // fifty of them, and every pan, zoom or visit to this tab fetched that
            // many. Scaled to the display density it is a dozen or so, and the map
            // labels become legible at arm's length.
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)
            // The zoom buttons stand at the right edge, so the bottom edge stays
            // free for the map credit and the banners above it.
            zoomController.display.setPositions(
                false,
                org.osmdroid.views.CustomZoomButtonsDisplay.HorizontalPosition.RIGHT,
                org.osmdroid.views.CustomZoomButtonsDisplay.VerticalPosition.CENTER
            )
            controller.setZoom(17.0)
            isFlingEnabled = true
        }
    }
    app.clairix.nav.FollowTileServer(mapView)

    // Only center the map on the very first GPS fix; after that the user controls it.
    var hasCentered by remember { mutableStateOf(false) }

    LaunchedEffect(status.lat, status.lon, mapData, sharedThreats, palette) {
        // Rebuild all overlays except the tile layer. This runs before the fix check
        // on purpose: ACTION_CLEAR resets the registry, which drops the fix to null,
        // and returning first left the cleared devices' markers and trails on screen.
        mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }

        // Community threat markers: reports from group members carry their own
        // position, so they are drawn whether or not this phone has a fix — a member
        // indoors with no GPS still needs to see what was reported around them.
        val now = System.currentTimeMillis()
        for (threat in sharedThreats) {
            if (threat.expiresAt < now) continue
            val markerArgb = when (threat.severity) {
                "CRITICAL", "HIGH" -> palette.danger
                "MEDIUM" -> palette.caution
                else -> palette.subtle
            }
            val marker = Marker(mapView).apply {
                position = GeoPoint(threat.lat, threat.lon)
                title = "${threat.fromName}: ${threat.title}"
                snippet = "${threat.kind} · ${fmtAgo(mapView.context.resources, now, threat.ts)}"
                icon = markerBitmap(markerArgb, 10, outlineArgb = palette.ring)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        val lat = status.lat
        val lon = status.lon
        if (lat == null || lon == null) {
            // No fix: nothing of our own to draw, but centre on the community reports
            // once so they are not off-screen somewhere in the default viewport.
            if (!hasCentered) {
                sharedThreats.firstOrNull { it.expiresAt >= now }?.let {
                    mapView.controller.setCenter(GeoPoint(it.lat, it.lon))
                    hasCentered = true
                }
            }
            mapView.invalidate()
            return@LaunchedEffect
        }

        // GPS track
        if (mapData.track.size > 1) {
            val line = Polyline(mapView).apply {
                outlinePaint.color = palette.info
                outlinePaint.strokeWidth = 8f
                outlinePaint.alpha = 180
                setPoints(mapData.track.map { GeoPoint(it.lat, it.lon) })
            }
            mapView.overlays.add(0, line)
        }

        // Device trails: the distinct places each tracker was heard, joined in the
        // order they were first visited, so the path it took alongside the user shows.
        val trailWidthPx = 4f * Resources.getSystem().displayMetrics.density
        for (trail in mapData.devices) {
            if (trail.points.size < 2) continue
            val line = Polyline(mapView).apply {
                outlinePaint.color = trailArgb(palette, trail.threat)
                outlinePaint.strokeWidth = trailWidthPx
                outlinePaint.alpha = 200
                setPoints(trail.points.map { GeoPoint(it.lat, it.lon) })
                title = trail.name
                snippet = plural(trail.points.size, "place")
            }
            mapView.overlays.add(line)
        }

        // Device markers
        for (trail in mapData.devices) {
            // points is a set of distinct places in insertion order, so its last entry
            // is the last *new* place, not the last fix. A→B→A would leave the marker at B.
            val pt = trail.lastHeardAt ?: trail.points.lastOrNull() ?: continue
            val argb = threatArgb(palette, trail.threat, trail.following)
            val sizeDp = if (trail.following) 22 else 16
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.lat, pt.lon)
                title = trail.name
                snippet = "%.6f, %.6f".format(pt.lat, pt.lon)
                icon = markerBitmap(argb, sizeDp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        // Cell anomaly markers (larger with outline)
        for (cm in mapData.cells) {
            val argb = threatArgb(palette, cm.level, false)
            val marker = Marker(mapView).apply {
                position = GeoPoint(cm.point.lat, cm.point.lon)
                title = cm.label
                snippet = "%.6f, %.6f".format(cm.point.lat, cm.point.lon)
                icon = markerBitmap(argb, 20, outlineArgb = palette.ring)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        // Current position: an info-coloured dot with a ring in the text colour
        val posMarker = Marker(mapView).apply {
            position = GeoPoint(lat, lon)
            title = "Your position"
            snippet = "%.6f, %.6f".format(lat, lon)
            icon = markerBitmap(palette.info, 20, outlineArgb = palette.ring)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(posMarker)

        if (!hasCentered) {
            mapView.controller.setCenter(GeoPoint(lat, lon))
            hasCentered = true
        }
        mapView.invalidate()
    }

    // The MapView follows the Activity's lifecycle, not just composition: it used to
    // be resumed once when the tab appeared and paused only when the tab was left,
    // so backgrounding Clairix on the Map tab left its tile threads and animations
    // running. Registering the observer on an already-resumed lifecycle replays
    // ON_RESUME, which is what brings the view up on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            // Leaving the tab discards this MapView (it lives in remember{}), so it has
            // to release its tile provider threads and caches, or every visit to the
            // Map tab leaked one. Overlays are dropped from the list first: detaching
            // a Marker recycles its icon, and the icons are shared via markerCache.
            // Plain removal from the list does not detach, so they survive for reuse.
            mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Overlays: where the fix is, how many devices are drawn, and any alert.
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapChip(
                text = buildString {
                    append(if (status.hasFix) stringResource(R.string.scan_gps_fix) else stringResource(R.string.scan_no_fix))
                    if (status.lat != null) append(" · %.5f, %.5f".format(Locale.US, status.lat, status.lon))
                    if (mapData.devices.isNotEmpty()) append(" · ${plural(mapData.devices.size, "device")}")
                },
                tone = if (status.hasFix) Tone.Neutral else Tone.Caution
            )
            if (cellHot) MapChip(stringResource(R.string.scan_sketchy_cell_tower_nearby, levelLabel(cell.level.name)), Tone.Caution)
            app.clairix.nav.TilesUnavailableNotice(stringResource(R.string.scan_your_position_the_devices_and_their))
        }

        // The data credit the licence asks for, on the map and clear of the SOS
        // button; the banners below sit above it.
        app.clairix.nav.MapAttribution(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp))

        if (followingTrails > 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 88.dp, bottom = 36.dp)
                    .background(ClairixTheme.colors.danger, ClairixShapes.Control)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = ClairixTheme.colors.onDanger, modifier = Modifier.size(18.dp))
                Text(
                    if (followingTrails == 1) stringResource(R.string.scan_1_tracker_on_you) else stringResource(R.string.scan_trackers_on_you, followingTrails),
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.onDanger
                )
            }
        } else if (travellingTrails > 0) {
            Box(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 88.dp, bottom = 36.dp)) {
                MapChip(
                    if (travellingTrails == 1) stringResource(R.string.scan_1_device_travelling_with_you) else stringResource(R.string.scan_devices_travelling_with_you, travellingTrails),
                    Tone.Caution
                )
            }
        }
    }
}

/** A small label floating over the map, on the raised surface so it reads over any tile. */
@Composable
private fun MapChip(text: String, tone: Tone) {
    val c = ClairixTheme.colors
    Row(
        Modifier
            .background(c.surfaceRaised.copy(alpha = 0.94f), ClairixShapes.Tag)
            .border(1.dp, c.border, ClairixShapes.Tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(6.dp).background(if (tone == Tone.Neutral) c.textSubtle else tone.color(), ClairixShapes.Tag))
        Text(text, style = MaterialTheme.typography.monoSmall, color = c.text)
    }
}

// ── Timeline screen ──────────────────────────────────────────────────────

/**
 * The timeline. [onShowExplainer] opens the co-traveller explanation for a trip-memory
 * finding nothing tied to the owner's car ([VehicleTrips.coTravellerEvent]).
 */
@Composable
internal fun TimelineScreen(onResumeScan: (() -> Unit)? = null, onShowExplainer: ((TimelineEvent) -> Unit)? = null) {
    val events by Registry.timeline.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)
    // Log is newest-first, so groups come out Today, Yesterday, then older days.
    val res = appResources()
    val groups = remember(events, now / 60_000L, res) { events.groupBy { dayLabel(res, it.ts, now) } }
    val flagged = remember(events) { events.count { it.severity.ordinal >= Severity.HIGH.ordinal || it.kind == EventKind.PANIC } }
    val today = groups[dayLabel(res, now, now)]?.size ?: 0
    // The newest stop on record. When it was the system's and the scanner is still
    // down, its row offers to resume; older stops are history.
    val latestStop = remember(events) { events.firstOrNull { it.kind == EventKind.SCAN_STOP } }
    // What the retention deletes, read again as the log changes (it prunes by the setting).
    val context = LocalContext.current
    val retentionFooter = remember(events) { Report.timelineFooter(HistoryRetention.days(context)) }

    ScreenList {
        // ── Status first: how much is on record and how much of it matters ──
        item(key = "§figures") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("$today", stringResource(R.string.scan_today), Modifier.weight(1f))
                StatTile("$flagged", stringResource(R.string.scan_flagged), Modifier.weight(1f), tone = if (flagged > 0) Tone.Danger else Tone.Neutral)
                StatTile("${events.size}", stringResource(R.string.scan_on_record), Modifier.weight(1f))
            }
        }
        if (events.isEmpty()) {
            item(key = "§empty") {
                EmptyState(
                    title = stringResource(R.string.scan_nothing_recorded_yet),
                    body = stringResource(R.string.scan_every_finding_lands_here_with_the),
                    icon = ClairixIcons.Timeline
                )
            }
        } else {
            groups.forEach { (label, list) ->
                item(key = "§day-$label") { SectionHeader(label, supporting = plural(list.size, "event")) }
                items(list, key = { it.id }) { e ->
                    val resumable = onResumeScan != null && e.id == latestStop?.id &&
                        e.id.startsWith(ScannerHealth.SYSTEM_STOP_ID_PREFIX) && !status.scanning && AppSettings.scanEnabled
                    val explain = onShowExplainer?.takeIf { VehicleTrips.coTravellerEvent(e.id) }
                    TimelineRow(
                        e, status,
                        onResume = if (resumable) onResumeScan else null,
                        onExplain = explain?.let { show -> { show(e) } }
                    )
                }
            }
        }
        // Always there, history or none: what goes, and how to keep it. Nothing when kept forever.
        retentionFooter?.let { footer ->
            item(key = "§retention") {
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClairixTheme.colors.textSubtle,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * A short tag for event kinds that come from outside this phone's own sensors,
 * so a row from a group member is not mistaken for a local detection. Null for
 * the ordinary kinds, whose title already says what happened.
 */
@Composable
private fun eventKindTag(kind: EventKind): Pair<String, Tone>? = when (kind) {
    EventKind.SHARED_THREAT -> stringResource(R.string.scan_community_report) to Tone.Info
    EventKind.PANIC -> stringResource(R.string.scan_sos_alert) to Tone.Danger
    else -> null
}

/**
 * The tone of a log row. Severity decides for local detections; a community
 * report is always info and an SOS is always danger, whatever severity the
 * sender attached.
 */
private fun eventTone(e: TimelineEvent): Tone = when (e.kind) {
    EventKind.SHARED_THREAT -> Tone.Info
    EventKind.PANIC -> Tone.Danger
    else -> e.severity.tone()
}

@Composable
private fun TimelineRow(
    e: TimelineEvent,
    status: ScanStatus,
    onResume: (() -> Unit)? = null,
    onExplain: (() -> Unit)? = null
) {
    var open by remember(e.id) { mutableStateOf(false) }
    val tone = eventTone(e)
    val tag = eventKindTag(e.kind)
    val lat = e.lat
    val lon = e.lon
    val c = ClairixTheme.colors

    ClairixCard(
        tone = tone,
        onClick = { open = !open },
        onClickLabel = if (open) stringResource(R.string.scan_hide_details) else stringResource(R.string.scan_show_details),
        spacing = 4.dp
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                e.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (tone == Tone.Neutral) c.textMuted else c.text,
                modifier = Modifier.weight(1f),
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(fmtClock(e.ts), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        }
        if (tag != null) StatusPill(tag.first, tag.second)
        if (e.detail.isNotBlank()) {
            Text(
                e.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSubtle,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // A scanner the system stopped, still down: "tap to resume" means this row.
        if (onResume != null) GhostButton(stringResource(R.string.scan_resume_scanning), onClick = onResume)
        // A device on several journeys that nothing tied to her car: the co-traveller explanation.
        if (onExplain != null) GhostButton(stringResource(R.string.scan_what_is_this_and_what_do), onClick = onExplain)
        if (lat != null && lon != null) {
            Text("%.5f, %.5f".format(Locale.US, lat, lon), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        }
        // A photo the owner kept from the camera sweep, sealed on the phone until opened here.
        e.attachment?.takeIf { app.clairix.analysis.SweepPhotos.isPhoto(it) }?.let { SweepPhotoButton(it) }
        if (open) {
            // An unlock while you slept, a changed PIN or an added fingerprint: what
            // the alert tells you to do next can also tell whoever did it.
            if (SafetyFirst.forEvent(e.kind)) SafetyFirstCard(title = SafetyFirst.TITLE_PHONE)
            Hairline(Modifier.padding(vertical = 4.dp))
            KeyValueRow(stringResource(R.string.scan_time), fmtTime(e.ts))
            KeyValueRow(stringResource(R.string.scan_kind), e.kind.name.replace('_', ' ').lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }, mono = false)
            KeyValueRow(stringResource(R.string.scan_severity), levelLabel(e.severity.name), valueColor = if (tone == Tone.Neutral) null else tone.color(), mono = false)
            if (lat != null && lon != null) {
                EventLocationSnapshot(lat, lon, status)
            } else {
                Text(stringResource(R.string.scan_no_location_recorded_for_this_event), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            }
        }
    }
}

/** A square showing where the event happened relative to where the phone is now. */
@Composable
private fun EventLocationSnapshot(lat: Double, lon: Double, status: ScanStatus) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val curLat = status.lat
    val curLon = status.lon
    if (!status.hasFix || curLat == null || curLon == null) {
        Text(stringResource(R.string.scan_no_current_fix_to_compare_with), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
        return
    }
    val mPerDegLat = 111320.0
    val mPerDegLon = 111320.0 * cos(Math.toRadians(curLat))
    val eastM = (lon - curLon) * mPerDegLon
    val southM = (curLat - lat) * mPerDegLat
    val dist = hypot(eastM, southM)
    val bearing = (Math.toDegrees(atan2(eastM, -southM)) + 360.0) % 360.0

    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Canvas(
            Modifier.size(104.dp)
                .background(c.base, ClairixShapes.Card)
                .border(1.dp, c.border, ClairixShapes.Card)
                .semantics { contentDescription = context.getString(R.string.scan_event_position_relative_to_you) }
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f - 10f
            drawCircle(c.border, radius = maxR, center = centre, style = Stroke(1f))
            drawCircle(c.border, radius = maxR / 2f, center = centre, style = Stroke(1f))
            val target = if (dist > 0.0) {
                val pxPerM = maxR / dist.toFloat()
                Offset(centre.x + (eastM * pxPerM).toFloat(), centre.y + (southM * pxPerM).toFloat())
            } else centre
            drawLine(c.textSubtle, centre, target, strokeWidth = 1f)
            drawCircle(c.text, radius = 5f, center = centre)
            drawCircle(c.info, radius = 3.5f, center = centre)
            drawCircle(c.danger, radius = 5f, center = target)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (dist < 1.0) stringResource(R.string.scan_right_here) else "${fmtMetres(dist).removePrefix("~")} away",
                style = MaterialTheme.typography.titleSmall,
                color = c.text
            )
            if (dist >= 1.0) Text(stringResource(R.string.scan_of_you, compass(bearing)), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Text(stringResource(R.string.scan_centre_is_where_you_are_now), style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
        }
    }
}

// ── Cell / IMSI screen ───────────────────────────────────────────────────

@Composable
internal fun CellScreen(onShowExplainer: ((CatcherFinding) -> Unit)? = null, onShown: () -> Unit = {}) {
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val now = rememberNow(5_000L)

    // With the scanner off, the tab takes its own reading every 15 s while it is on
    // screen and in front; with it on, the service's judged readings arrive on the
    // same cadence while the screen is on. See [ScanPacing.tabRefreshMs].
    RefreshWhileStarted(ScanPacing.tabRefreshMs(ScanPacing.Tab.CELL, status), onShown)

    val radio = cell.radio
    val c = cell.cell
    val palette = ClairixTheme.colors

    ScreenList {
        // ── The verdict first, because it is what the screen is for ──────────
        item(key = "§verdict") { CellVerdict(cell) }

        if (cell.available && cell.analysing && cell.level.ordinal >= Threat.MEDIUM.ordinal && cell.findings.isNotEmpty()) {
            item(key = "§alarm") { CatcherAlarm(cell) }
        }

        // ── Findings: every indicator that fired, each one explained on tap ──
        if (cell.available && cell.analysing) {
            if (cell.findings.isNotEmpty()) {
                item(key = "§indicators") {
                    SectionHeader(stringResource(R.string.scan_indicators), supporting = "${cell.findings.size} active", tone = Tone.Danger)
                }
                items(cell.findings, key = { it.id }) { f ->
                    FindingRow(
                        f = f,
                        onShowExplainer = if (f.severity == Severity.CRITICAL || f.severity == Severity.HIGH)
                            { { onShowExplainer?.invoke(f) } } else null
                    )
                }
            } else {
                item(key = "§no-indicators") {
                    Notice(
                        Tone.Safe,
                        stringResource(R.string.scan_twelve_checks_run_against_your_baseline),
                        title = stringResource(R.string.scan_no_indicators_right_now)
                    )
                }
            }
            if (cell.score > 0) {
                item(key = "§meaning") { WhatThisMeans(cell.score) }
            }
            item(key = "§baseline") {
                ClairixCard(spacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.scan_baseline), style = MaterialTheme.typography.titleSmall, color = palette.text, modifier = Modifier.weight(1f))
                        StatusPill(if (cell.mature) stringResource(R.string.scan_ready) else stringResource(R.string.scan_learning), if (cell.mature) Tone.Safe else Tone.Caution)
                    }
                    ClairixProgressBar(cell.maturity, tone = if (cell.mature) Tone.Safe else Tone.Caution)
                    Text(
                        if (cell.mature) stringResource(
                            R.string.scan_cells_visits_known,
                            pluralStringResource(R.plurals.scan_cells, cell.knownCells, cell.knownCells),
                            pluralStringResource(R.plurals.scan_visits, cell.visits, cell.visits)
                        )
                        else stringResource(R.string.scan_of_10_visits_keep_scanning_where, cell.visits),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSubtle
                    )
                }
            }
        } else if (cell.available) {
            item(key = "§not-analysing") {
                Notice(
                    Tone.Caution,
                    stringResource(R.string.scan_live_tower_data_is_below_but),
                    title = stringResource(R.string.scan_not_checked_yet)
                )
            }
        }

        if (!cell.available && radio != null && (radio.operatorName != null || radio.simOperatorName != null)) {
            item(key = "§unavailable") {
                ClairixCard(spacing = 4.dp) {
                    KeyValueRow(stringResource(R.string.scan_registered_on), radio.operatorName ?: stringResource(R.string.scan_unknown), mono = false)
                    KeyValueRow("SIM", radio.simOperatorName ?: stringResource(R.string.scan_unknown), mono = false)
                    radio.simState?.let { KeyValueRow(stringResource(R.string.scan_sim_state), it, mono = false) }
                }
            }
        }

        if (c != null) {
            item(key = "§details-hdr") { SectionHeader(stringResource(R.string.scan_serving_tower)) }
            // ── Operator & radio ──────────────────────────────────────────────
            item(key = "§operator") {
                ClairixCard(spacing = 4.dp) {
                    val decoded = Plmn.describe(c.mcc, c.mnc)
                    val operator = radio?.operatorName ?: c.plmnCarrier ?: stringResource(R.string.scan_unknown_operator)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(operator, style = MaterialTheme.typography.titleLarge, color = palette.text, modifier = Modifier.weight(1f))
                        if (radio?.roaming == true) StatusPill(stringResource(R.string.scan_roaming), Tone.Caution)
                    }
                    KeyValueRow(stringResource(R.string.scan_network_type), radio?.dataNetworkType ?: c.rat.label)
                    KeyValueRow(stringResource(R.string.comms_radio), c.rat.label)
                    KeyValueRow(stringResource(R.string.scan_plmn_mcc_mnc), "${c.mcc ?: "?"}-${c.mnc ?: "?"}" + (decoded?.let { "  $it" } ?: ""))
                    if (radio != null && (radio.simOperatorName != null || radio.simPlmn != null)) {
                        val simLine = listOfNotNull(radio.simOperatorName, radio.simPlmn).joinToString(" · ")
                        KeyValueRow(stringResource(R.string.scan_sim_carrier), simLine)
                    }
                    if (radio != null) {
                        KeyValueRow(stringResource(R.string.scan_mobile_data), if (radio.dataConnected) stringResource(R.string.scan_connected) else stringResource(R.string.scan_not_connected))
                        if (radio.simState != null && radio.simState != "Ready") KeyValueRow(stringResource(R.string.scan_sim_state), radio.simState)
                    }
                    // Roaming on a network whose PLMN decodes to a different carrier than
                    // the SIM's is the ordinary case; a home PLMN that the modem names
                    // differently from the table is worth a glance.
                    val simName = radio?.simOperatorName
                    val decodedCarrier = c.plmnCarrier
                    if (radio?.roaming == false && simName != null && decodedCarrier != null &&
                        !decodedCarrier.contains(simName, ignoreCase = true) && !simName.contains(decodedCarrier.substringBefore(' '), ignoreCase = true)
                    ) {
                        WarningLine(
                            stringResource(R.string.scan_not_roaming_but_the_towers_network, decodedCarrier, simName),
                            Tone.Caution,
                            Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ── Signal ─────────────────────────────────────────────────────────
            item(key = "§signal") {
                val bars = c.bars ?: barsFromDbm(c.signalDbm, c.rat)
                ClairixCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SignalBars(bars, tone = signalTone(bars))
                        Column(Modifier.weight(1f)) {
                            Text(c.signalDbm?.let { fmtDbm(it) } ?: stringResource(R.string.scan_dbm), style = MaterialTheme.typography.monoLarge, color = palette.text)
                            Text(signalWord(bars, c.rat), style = MaterialTheme.typography.bodyMedium, color = palette.textSubtle)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (c.rsrq != null) Text(stringResource(R.string.scan_rsrq_db, c.rsrq), style = MaterialTheme.typography.monoSmall, color = palette.textMuted)
                            if (c.sinr != null) Text(stringResource(R.string.scan_sinr_db, c.sinr), style = MaterialTheme.typography.monoSmall, color = palette.textMuted)
                            c.timingAdvance?.let { ta ->
                                val metres = if (c.rat == Rat.GSM) ta * 550 else ta * 78
                                Text(
                                    "TA $ta ≈ ${if (metres >= 1000) "%.1f km".format(Locale.US, metres / 1000.0) else "$metres m"}",
                                    style = MaterialTheme.typography.monoSmall,
                                    color = if (ta == 0 && c.rat == Rat.LTE) palette.caution else palette.textMuted
                                )
                            }
                        }
                    }
                }
            }

            // ── Serving cell identity ─────────────────────────────────────────
            item(key = "§serving") {
                ClairixCard(spacing = 4.dp) {
                    KeyValueRow(stringResource(R.string.scan_cell_id), c.cellId)
                    if (c.rat == Rat.LTE) {
                        c.cellId.toLongOrNull()?.let { ci ->
                            KeyValueRow(stringResource(R.string.scan_enodeb_sector), "${ci shr 8} / ${ci and 0xFF}")
                        }
                    }
                    if (c.rat == Rat.NR5G) {
                        c.cellId.toLongOrNull()?.let { nci ->
                            // gNB id length varies (22–32 bits); the common 24-bit split is shown.
                            KeyValueRow(stringResource(R.string.scan_gnodeb_cell_24_bit_split), "${nci shr 12} / ${nci and 0xFFF}")
                        }
                    }
                    KeyValueRow(if (c.rat.rank >= Rat.LTE.rank) stringResource(R.string.scan_tracking_area_tac) else stringResource(R.string.scan_location_area_lac), c.tac ?: stringResource(R.string.scan_not_reported))
                    c.pci?.let {
                        KeyValueRow(
                            when (c.rat) { Rat.GSM -> "BSIC"; Rat.UMTS -> "Scrambling code"; else -> "Physical cell ID" },
                            it.toString()
                        )
                    }
                    KeyValueRow(stringResource(R.string.scan_band), c.band ?: stringResource(R.string.scan_not_reported))
                    c.arfcn?.let {
                        KeyValueRow(
                            when (c.rat) { Rat.LTE -> "EARFCN"; Rat.NR5G -> "NR-ARFCN"; Rat.UMTS -> "UARFCN"; else -> "ARFCN" },
                            it.toString()
                        )
                    }
                    c.bandwidthKhz?.let { KeyValueRow(stringResource(R.string.scan_bandwidth), "${it / 1000} MHz") }
                    KeyValueRow(stringResource(R.string.scan_neighbours_heard), "${c.neighbors ?: "Not reported"}")
                    KeyValueRow(stringResource(R.string.comms_read), "${fmtClock(c.ts)} · ${fmtAgo(appResources(), now, c.ts)}")
                }
            }

            // ── Neighbours ────────────────────────────────────────────────────
            if (cell.neighbors.isNotEmpty()) {
                item(key = "§neighbours-hdr") { SectionHeader(stringResource(R.string.scan_neighbouring_cells), supporting = "${cell.neighbors.size} heard") }
                item(key = "§neighbours") {
                    ClairixCard(spacing = 8.dp) {
                        Text(
                            stringResource(R.string.scan_what_else_the_modem_can_hear),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSubtle
                        )
                        cell.neighbors.take(12).forEach { n ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SignalBars(n.bars ?: barsFromDbm(n.signalDbm, n.rat), tone = Tone.Neutral, height = 14.dp)
                                Text(n.rat.label, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, modifier = Modifier.width(56.dp))
                                Text(n.id, style = MaterialTheme.typography.monoSmall, color = palette.textMuted, modifier = Modifier.weight(1f))
                                Text(n.signalDbm?.let { fmtDbm(it) } ?: "—", style = MaterialTheme.typography.monoSmall, color = palette.textSubtle)
                            }
                        }
                        if (cell.neighbors.size > 12) {
                            Text(stringResource(R.string.scan_more, cell.neighbors.size - 12), style = MaterialTheme.typography.bodySmall, color = palette.textSubtle)
                        }
                    }
                }
            }
        }
    }
}

/** The headline of the Cell screen: one verdict from the detector, or why there is none. */
@Composable
private fun CellVerdict(cell: CellStatus) {
    val copy = when {
        !cell.available -> CellCopy(
            stringResource(R.string.scan_cell_towers), stringResource(R.string.scan_no_reading),
            stringResource(R.string.scan_tower_details_appear_here_once_the, (cell.reason ?: stringResource(R.string.scan_reading_the_modem)).trimEnd('.')),
            Tone.Neutral
        )
        !cell.analysing -> CellCopy(stringResource(R.string.scan_cell_towers), stringResource(R.string.scan_not_checked), stringResource(R.string.scan_tower_details_are_live_below_start), Tone.Neutral)
        cell.level == Threat.CRITICAL -> CellCopy(
            stringResource(R.string.scan_cell_towers_analysing), stringResource(R.string.scan_fake_tower_likely),
            stringResource(R.string.scan_several_signs_point_to_a_fake),
            Tone.Danger
        )
        cell.level == Threat.HIGH -> CellCopy(
            stringResource(R.string.scan_cell_towers_analysing), stringResource(R.string.scan_sketchy_tower),
            stringResource(R.string.scan_this_tower_doesnt_match_your_baseline),
            Tone.Danger
        )
        cell.level == Threat.MEDIUM -> CellCopy(
            stringResource(R.string.scan_cell_towers_analysing), stringResource(R.string.scan_worth_a_look),
            stringResource(R.string.scan_some_indicators_fired_not_conclusive_on),
            Tone.Caution
        )
        !cell.mature -> CellCopy(
            stringResource(R.string.scan_cell_towers_learning), stringResource(R.string.scan_learning),
            stringResource(R.string.scan_clairix_is_still_learning_your_usual),
            Tone.Neutral
        )
        else -> CellCopy(
            stringResource(R.string.scan_cell_towers_analysing), stringResource(R.string.scan_towers_clear),
            stringResource(R.string.scan_your_towers_match_the_baseline_nothing),
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = copy.eyebrow,
        headline = copy.headline,
        body = copy.body + if (cell.available && cell.analysing) stringResource(R.string.scan_score_100, cell.score) else "",
        tone = copy.tone,
        modifier = Modifier.padding(top = 8.dp)
    )
}

private data class CellCopy(val eyebrow: String, val headline: String, val body: String, val tone: Tone)

/** Bars 0..4 from dBm when the platform gives none — the platform's own LTE/NR thresholds. */
private fun barsFromDbm(dbm: Int?, rat: Rat): Int {
    if (dbm == null) return 0
    return when (rat) {
        Rat.LTE, Rat.NR5G -> when {
            dbm >= -95 -> 4; dbm >= -105 -> 3; dbm >= -115 -> 2; dbm >= -125 -> 1; else -> 0
        }
        else -> when {
            dbm >= -75 -> 4; dbm >= -85 -> 3; dbm >= -95 -> 2; dbm >= -105 -> 1; else -> 0
        }
    }
}

@Composable
private fun signalWord(bars: Int, rat: Rat): String = when (bars) {
    4 -> stringResource(R.string.scan_excellent)
    3 -> stringResource(R.string.scan_good)
    2 -> stringResource(R.string.scan_fair)
    1 -> stringResource(R.string.scan_poor)
    else -> stringResource(R.string.scan_no_signal)
} + when (rat) {
    Rat.LTE, Rat.NR5G -> " (RSRP)"
    else -> " (RSSI)"
}

private fun signalTone(bars: Int): Tone = when (bars) {
    4, 3 -> Tone.Safe
    2 -> Tone.Caution
    1 -> Tone.Danger
    else -> Tone.Neutral
}

@Composable
private fun SignalBars(bars: Int, tone: Tone, height: androidx.compose.ui.unit.Dp = 24.dp) {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val on = if (tone == Tone.Neutral) c.textMuted else tone.color()
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = context.getString(R.string.scan_of_4_bars, bars) }
    ) {
        for (i in 1..4) {
            Box(
                Modifier.width(height / 4).height(height * i / 4)
                    .background(if (i <= bars) on else c.border)
            )
        }
    }
}

/**
 * The card that says "this looks like a fake tower" and lists exactly what set
 * it off, so the reader knows what was seen rather than a bare "suspicious".
 */
@Composable
private fun CatcherAlarm(cell: CellStatus) {
    val tone = cell.level.tone()
    val c = ClairixTheme.colors
    ClairixCard(tone = tone, emphasis = true) {
        Text(
            stringResource(R.string.scan_what_set_it_off),
            style = MaterialTheme.typography.titleSmall,
            color = tone.color()
        )
        cell.findings.sortedByDescending { it.severity.ordinal }.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.title, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                StatusPill(levelLabel(f.severity.name), f.severity.tone())
            }
        }
        cell.cell?.let { cc ->
            Text(
                stringResource(R.string.scan_on_cell, cc.rat.label, cc.cellId, (cc.band?.let { " · $it" } ?: ""), (cc.signalDbm?.let { " · ${fmtDbm(it)}" } ?: "")),
                style = MaterialTheme.typography.monoSmall,
                color = c.textMuted
            )
        }
        Text(
            when (cell.level) {
                Threat.CRITICAL, Threat.HIGH -> stringResource(R.string.scan_avoid_calls_and_sms_here_use)
                else -> stringResource(R.string.scan_not_conclusive_on_its_own_watch)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.text
        )
    }
}

@Composable
private fun WhatThisMeans(score: Int) {
    val text = when {
        score < 30 -> stringResource(R.string.scan_a_small_change_from_your_baseline)
        score < 50 -> stringResource(R.string.scan_a_moderate_change_from_your_baseline)
        score < 80 -> stringResource(R.string.scan_a_big_change_from_your_baseline)
        else -> stringResource(R.string.scan_strong_signs_of_a_fake_tower)
    }
    val tone = when {
        score < 30 -> Tone.Neutral
        score < 50 -> Tone.Caution
        else -> Tone.Danger
    }
    Notice(tone, text, title = stringResource(R.string.scan_what_this_means))
}

@Composable
private fun FindingRow(f: CatcherFinding, onShowExplainer: (() -> Unit)? = null) {
    val c = ClairixTheme.colors
    ClairixCard(
        tone = f.severity.tone(),
        onClick = onShowExplainer,
        onClickLabel = stringResource(R.string.scan_explain_this_indicator),
        spacing = 4.dp
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(f.title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            StatusPill(levelLabel(f.severity.name), f.severity.tone())
        }
        Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        if (onShowExplainer != null) {
            Text(stringResource(R.string.scan_tap_for_what_it_means_and), style = MaterialTheme.typography.labelMedium, color = c.accent)
        }
    }
}

// ── Device health screen ────────────────────────────────────────────────

@Composable
internal fun DeviceScreen(onShown: () -> Unit, onOpenAccounts: () -> Unit = {}) {
    val health by Registry.phoneHealth.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    // Live microphone and camera monitoring only runs inside the scanner service. With
    // the scanner off, the static findings are re-checked by the Activity whenever this
    // tab appears and every 30 s while it stays in front, so battery, USB and VPN rows
    // stay current; with it on, the service re-checks them every 30 s while the screen
    // is on. See [ScanPacing.tabRefreshMs].
    val liveMonitoring = status.scanning
    RefreshWhileStarted(ScanPacing.tabRefreshMs(ScanPacing.Tab.DEVICE, status), onShown)
    val problems = health.problems
    val checked = health.facts.size
    val issues = health.findings.size + problems.size
    val c = ClairixTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A row's answer ("I turned 2G off") is stored off the main thread, then the
    // checklist is read again so the row shows what it now knows.
    val onAnswer: (String) -> Unit = { id ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { CellularExposure.answer(context.applicationContext, id) } }
            onShown()
        }
    }
    val audioInputs = rememberAudioInputs()

    ScreenList {
        // ── Status first ───────────────────────────────────────────────────
        item(key = "hero") { PhoneVerdict(health, checked, issues) }

        item(key = "figures") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("$checked", stringResource(R.string.scan_checks_run), Modifier.weight(1f))
                StatTile(
                    "$issues", if (issues == 1) stringResource(R.string.scan_issue) else stringResource(R.string.scan_issues), Modifier.weight(1f),
                    tone = when {
                        issues == 0 -> Tone.Safe
                        health.level.ordinal >= Threat.HIGH.ordinal -> Tone.Danger
                        else -> Tone.Caution
                    }
                )
                StatTile(
                    if (liveMonitoring) stringResource(R.string.comms_on) else stringResource(R.string.comms_off), stringResource(R.string.scan_mic_and_camera_watch), Modifier.weight(1f),
                    tone = if (liveMonitoring) Tone.Safe else Tone.Neutral
                )
            }
        }

        // ── Active mic / camera: the most urgent, always at the top ─────────
        // Android tells a third-party app that a recording or a camera is in use,
        // but not which app — so nothing here names one. The status-bar privacy
        // indicator and Settings → Privacy dashboard show the app itself.
        // A live sensor is caution, not danger: it is also what every video call
        // looks like. The notice says what is known and where to find the rest.
        if (health.activeRecordings > 0) {
            item(key = "mic_banner") {
                Notice(
                    Tone.Caution,
                    (if (health.activeRecordings == 1) stringResource(R.string.scan_an_app_is_recording_audio_right)
                    else stringResource(R.string.scan_apps_are_recording_audio_right_now, health.activeRecordings)) + SENSOR_HINT,
                    title = stringResource(R.string.scan_microphone_in_use)
                )
            }
        }
        if (health.cameraInUse) {
            item(key = "cam_banner") {
                Notice(Tone.Caution, stringResource(R.string.scan_another_app_is_using_the_camera, SENSOR_HINT), title = stringResource(R.string.scan_camera_in_use))
            }
        }
        if (!health.sensorActive && !liveMonitoring) {
            item(key = "sensors_off") {
                Text(
                    stringResource(R.string.scan_microphone_and_camera_use_is_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSubtle,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // ── Findings: things installed or granted that need acting on ────────
        if (health.findings.isNotEmpty()) {
            item(key = "findings_head") {
                SectionHeader(stringResource(R.string.scan_apps_with_spy_level_access), supporting = "${health.findings.size}", tone = Tone.Danger)
            }
            // These say how to revoke or uninstall, so the safety card comes first, as on the Stalkerware page.
            item(key = "findings_safety_first") { SafetyFirstCard(title = SafetyFirst.TITLE_APP) }
            items(health.findings, key = { it.id }) { f -> HealthFindingRow(f) }
        }

        // ── Microphones that attached themselves, and the owner's own headsets ──
        audioInputItems(audioInputs)

        // ── Who else can see this phone: when the accounts checklist was last done ──
        accountChecklistSummaryItems(onOpenAccounts)

        // ── Checklist ───────────────────────────────────────────────────────
        if (health.facts.isNotEmpty()) {
            val groups = health.facts.groupBy { it.category }
            val order = listOf("Integrity", "Access", "Software", "Network", "Hardware")
            for (cat in order + (groups.keys - order.toSet())) {
                val rows = groups[cat] ?: continue
                item(key = "cat_$cat") {
                    val flagged = rows.count { it.severity != null && it.severity.ordinal >= Severity.MEDIUM.ordinal }
                    SectionHeader(cat, supporting = if (flagged > 0) stringResource(R.string.scan_to_check, flagged) else stringResource(R.string.scan_all_passed))
                }
                item(key = "facts_$cat") {
                    ListGroup {
                        rows.forEachIndexed { i, f ->
                            if (i > 0) Hairline()
                            HealthFactRow(f, onAnswer)
                        }
                    }
                }
            }
        }
    }
}

/** Where Android shows which app holds the microphone or camera. */
private const val SENSOR_HINT =
    "Android doesn't tell Clairix which app. Tap the green privacy dot in the status bar, or open Settings → Security & privacy → Privacy dashboard."

@Composable
private fun PhoneVerdict(health: PhoneHealth, checked: Int, issues: Int) {
    val sensor = when {
        health.activeRecordings > 0 && health.cameraInUse -> stringResource(R.string.scan_mic_and_camera_on)
        health.activeRecordings > 0 -> stringResource(R.string.scan_mic_in_use)
        health.cameraInUse -> stringResource(R.string.scan_camera_in_use)
        else -> null
    }
    val (headline, body, tone) = when {
        health.findingsScannedTs == 0L -> Triple(
            stringResource(R.string.scan_checking),
            stringResource(R.string.scan_root_bootloader_verified_boot_selinux_encryption),
            Tone.Neutral
        )
        issues > 0 -> Triple(
            if (issues == 1) stringResource(R.string.scan_1_to_fix) else stringResource(R.string.scan_to_fix, issues),
            (health.findings.map { it.title } + health.problems.map { it.label })
                .take(3).joinToString(" · ") + if (issues > 3) " · and more" else "",
            if (health.level.ordinal >= Threat.HIGH.ordinal) Tone.Danger else Tone.Caution
        )
        sensor != null -> Triple(
            sensor,
            stringResource(R.string.scan_something_is_using_a_sensor_right, (if (health.spywareScannedTs > 0L) stringResource(R.string.scan_no_spyware_or_risky_settings_found, checked)
            else stringResource(R.string.scan_no_risky_settings_found_in_checks, checked))),
            Tone.Caution
        )
        // "Known spyware" is only said once the stalkerware check has run.
        health.spywareScannedTs == 0L -> Triple(
            stringResource(R.string.scan_checking_apps),
            stringResource(R.string.scan_checks_passed_no_root_debug_access, checked),
            Tone.Neutral
        )
        else -> Triple(
            stringResource(R.string.scan_phone_clear),
            // Two times: the settings pass runs often, the check of installed apps (the "known spyware") less often.
            stringResource(R.string.scan_checks_passed_no_root_debug_access_2, checked, fmtClock(health.findingsScannedTs), fmtTime(health.spywareScannedTs)),
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = stringResource(R.string.scan_phone_check),
        headline = headline,
        body = body,
        tone = tone,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun HealthFactRow(f: HealthFact, onAnswer: (String) -> Unit) {
    val c = ClairixTheme.colors
    val context = LocalContext.current
    // Purely informational rows (a version number, a battery level) get a neutral
    // mark, as do granted-access rows and the stock keyboard (SpywareHealth.informational);
    // a check that passed gets safe; a problem gets its severity's tone.
    val informational = f.severity == null && (INFORMATIONAL_FACTS.contains(f.id) || SpywareHealth.informational(f.id))
    val tone = when {
        f.severity != null -> f.severity.tone()
        informational -> Tone.Neutral
        else -> Tone.Safe
    }
    val mark = when {
        informational -> c.borderStrong
        tone == Tone.Neutral -> c.textSubtle
        else -> tone.color()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // The text reads as one item; the buttons under it stay buttons of their own.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(8.dp).background(mark, ClairixShapes.Tag))
                Text(f.label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Text(
                    f.value,
                    style = MaterialTheme.typography.mono,
                    color = if (f.severity == null) c.textMuted else tone.color(),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
            if (f.detail != null) {
                Text(
                    f.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (f.severity == null) c.textSubtle else tone.color(),
                    modifier = Modifier.padding(start = 20.dp)
                )
            }
        }
        // The page where the row is put right, and what only the owner can tell Clairix.
        val fix = f.fixAction
        val answer = f.answer
        if (fix != null || answer != null) {
            Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fix != null) GhostButton(stringResource(R.string.scan_open_settings), onClick = { openFix(context, fix) })
                if (answer != null) GhostButton(answer.label, onClick = { onAnswer(answer.id) })
            }
        }
    }
}

/**
 * Opens the Settings page a checklist row names. A phone without that page (an OEM
 * that renamed or dropped it) gets the security settings page instead, as
 * [HealthFact.fixAction] promises.
 */
private fun openFix(context: Context, action: String) {
    val opened = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) openExternal(context, Intent(Settings.ACTION_SECURITY_SETTINGS))
}

/** Checklist rows that describe rather than judge, so a safe mark would overclaim. */
private val INFORMATIONAL_FACTS = setOf(
    "integrity_api", "android", "model", "build", "kernel", "cpu", "battery", "vpn", "pdns", "vpn_always", "profiles",
    CellularExposure.NOTIFICATIONS_FACT_ID
)

@Composable
private fun HealthFindingRow(f: PhoneHealthFinding) {
    val c = ClairixTheme.colors
    val tone = f.severity.tone()
    ClairixCard(tone = tone, emphasis = f.severity == Severity.CRITICAL, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(f.title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            StatusPill(levelLabel(f.severity.name), tone)
        }
        Text(f.category, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
    }
}

// ── WiFi screen ────────────────────────────────────────────────────────

@Composable
private fun wifiReason(reason: String): String = when (reason) {
    "known_catcher_ssid" -> stringResource(R.string.scan_default_ssid_of_known_interception_equipment)
    "carrier_open_network" -> stringResource(R.string.scan_open_carrier_hotspot_captive_portal_unencrypted)
    "open_twin_of_secured" -> stringResource(R.string.scan_open_copy_of_a_network_that)
    "open_unsecured" -> stringResource(R.string.scan_open_unsecured_network_traffic_can_be)
    else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
}

/** When the Karma trap was armed, as the Wi-Fi tab says it. Main thread only, like the formatters above. */
private val trapSinceFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

@Composable
internal fun WifiScreen(onShown: () -> Unit = {}) {
    val anomalies by Registry.wifi.collectAsStateWithLifecycle()
    val wifi by Registry.wifiStatus.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val trap by Registry.karmaTrap.collectAsStateWithLifecycle()
    val now = rememberNow(5_000L)
    val sorted = remember(anomalies) {
        anomalies.sortedWith(compareByDescending<WifiAnomaly> { it.threat.ordinal }.thenByDescending { it.ts })
    }

    // While the scanner runs the tab shows the scanner's own results, from the scan it
    // asks for every two minutes while the screen is on, and requests none itself. With
    // the scanner off it asks for one a minute while it is in front: Android allows four
    // in two minutes. See [ScanPacing.tabRefreshMs].
    RefreshWhileStarted(ScanPacing.tabRefreshMs(ScanPacing.Tab.WIFI, status), onShown)

    val conn = wifi.connection
    val flagged = wifi.nearby.count { it.flags.isNotEmpty() }
    val open = wifi.nearby.count { !it.security.secure }
    val high = sorted.count { it.threat.ordinal >= Threat.HIGH.ordinal }
    // Open carrier hotspots are noted, not flagged as bait: xfinitywifi and attwifi are
    // open captive portals by design. When they are all there is, say what they are.
    val onlyHotspots = sorted.isNotEmpty() && sorted.all { it.reason == WifiScanner.REASON_OPEN_CARRIER_HOTSPOT }
    val c = ClairixTheme.colors

    ScreenList {
        // ── Status first ──
        item(key = "§hero") {
            StatusHero(
                eyebrow = stringResource(R.string.scan_wi_fi),
                // Being joined to an open network outranks notes about networks nearby,
                // as the tone below already does.
                headline = when {
                    high > 0 -> if (high == 1) stringResource(R.string.scan_bait_network) else stringResource(R.string.scan_bait_networks, high)
                    conn != null && !conn.security.secure -> stringResource(R.string.scan_open_network)
                    onlyHotspots -> stringResource(R.string.scan_open_hotspots_nearby)
                    sorted.isNotEmpty() -> stringResource(R.string.scan_worth_a_look)
                    wifi.scannedTs > 0 -> stringResource(R.string.scan_wi_fi_clean)
                    else -> stringResource(R.string.scan_checking)
                },
                body = when {
                    high > 0 -> stringResource(R.string.scan_networks_below_look_built_to_catch)
                    conn != null && !conn.security.secure -> stringResource(R.string.scan_youre_on_an_unencrypted_network_anyone)
                    onlyHotspots -> pluralStringResource(R.plurals.scan_wifi_hotspots_nearby, sorted.size, sorted.size)
                    sorted.isNotEmpty() -> pluralStringResource(R.plurals.scan_wifi_look_off, sorted.size, sorted.size)
                    wifi.scannedTs > 0 -> pluralStringResource(R.plurals.scan_wifi_in_range, wifi.nearby.size, wifi.nearby.size, fmtAgo(appResources(), now, wifi.scannedTs))
                    else -> stringResource(R.string.scan_reading_whats_in_range_results_fill)
                },
                tone = when {
                    high > 0 || (conn != null && !conn.security.secure) -> Tone.Danger
                    sorted.isNotEmpty() -> Tone.Caution
                    wifi.scannedTs > 0 -> Tone.Safe
                    else -> Tone.Neutral
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ── Findings: suspicious networks are the reason to look ───────────
        if (sorted.isNotEmpty()) {
            item(key = "§anomaly-head") {
                SectionHeader(
                    if (onlyHotspots) stringResource(R.string.scan_open_carrier_hotspots) else stringResource(R.string.scan_suspicious_networks),
                    supporting = "${sorted.size}" + if (high > 0) stringResource(R.string.scan_high, high) else "",
                    tone = if (high > 0) Tone.Danger else Tone.Caution
                )
            }
            itemsIndexed(sorted, key = { i, a -> "anomaly|${a.bssid}|${a.reason}|$i" }) { _, a -> WifiRow(a) }
        }

        // ── The Karma trap: armed only once Android may use the suggestion ───
        item(key = "§trap") {
            Notice(
                tone = when (trap.state) {
                    KarmaTrap.State.ARMED -> Tone.Safe
                    KarmaTrap.State.OFF -> Tone.Neutral
                    else -> Tone.Caution
                },
                text = trap.detail(),
                title = trap.headline(if (trap.since > 0L) trapSinceFmt.format(Date(trap.since)) else "")
            )
        }

        // ── Connected network ─────────────────────────────────────────────────
        item(key = "§conn-head") { SectionHeader(stringResource(R.string.scan_your_connection)) }
        if (!wifi.available) {
            item(key = "§unavailable") {
                EmptyState(title = wifi.reason ?: stringResource(R.string.scan_reading_the_wi_fi_radio), body = stringResource(R.string.scan_connection_details_appear_here_once_wi), icon = ClairixIcons.Wifi)
            }
        } else if (conn == null) {
            item(key = "§disconnected") {
                EmptyState(title = stringResource(R.string.scan_not_connected), body = stringResource(R.string.scan_wi_fi_is_on_but_the), icon = ClairixIcons.Wifi)
            }
        } else {
            item(key = "§connected") {
                ClairixCard(spacing = 4.dp, tone = if (conn.security.secure) null else Tone.Danger) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SignalBars(conn.level, tone = signalTone(conn.level))
                        Column(Modifier.weight(1f)) {
                            Text(
                                conn.ssid ?: stringResource(R.string.scan_name_hidden_is_location_off),
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(conn.bssid ?: stringResource(R.string.scan_bssid_unavailable), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
                        }
                        StatusPill(conn.security.label, if (conn.security.secure) Tone.Safe else Tone.Danger)
                    }
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow(stringResource(R.string.scan_signal), "${fmtDbm(conn.rssi)} · ${signalWord(conn.level, Rat.UNKNOWN).substringBefore(" (")}")
                    KeyValueRow(stringResource(R.string.scan_frequency), buildString {
                        append(conn.band)
                        if (conn.frequencyMhz > 0) append(stringResource(R.string.scan_mhz, conn.frequencyMhz))
                        conn.channel?.let { append(stringResource(R.string.scan_ch, it)) }
                    })
                    conn.standard?.let { KeyValueRow(stringResource(R.string.scan_standard), it) }
                    KeyValueRow(stringResource(R.string.scan_security), conn.security.label, valueColor = if (conn.security.secure) null else c.danger)
                    val speed = listOfNotNull(
                        conn.txMbps?.let { "↑$it" }, conn.rxMbps?.let { "↓$it" }
                    ).joinToString(" ").ifBlank { conn.linkSpeedMbps?.toString() ?: "" }
                    if (speed.isNotBlank()) KeyValueRow(stringResource(R.string.scan_link_speed), stringResource(R.string.scan_mbps, speed))
                    Hairline(Modifier.padding(vertical = 4.dp))
                    KeyValueRow("IPv4", conn.ipv4 ?: stringResource(R.string.scan_none))
                    conn.ipv6?.let { KeyValueRow("IPv6", it) }
                    KeyValueRow(stringResource(R.string.scan_gateway), conn.gateway ?: stringResource(R.string.scan_unknown))
                    KeyValueRow("DNS", if (conn.dnsServers.isEmpty()) stringResource(R.string.scan_none) else conn.dnsServers.joinToString("\n"))
                    KeyValueRow(stringResource(R.string.scan_private_dns), conn.privateDns ?: stringResource(R.string.comms_off), valueColor = if (conn.privateDns != null) c.safe else null)
                    // Whether Clairix's own traffic goes through a VPN: a VPN set to carry
                    // only some apps is not protection for the rest, and is not shown as such.
                    when (conn.vpn) {
                        VpnCoverage.COVERS_APP -> KeyValueRow("VPN", stringResource(R.string.scan_active_for_clairix), valueColor = c.safe)
                        VpnCoverage.EXCLUDES_APP -> KeyValueRow("VPN", stringResource(R.string.scan_active_not_covering_clairix), valueColor = c.caution)
                        VpnCoverage.APP_OFFLINE -> KeyValueRow("VPN", stringResource(R.string.scan_active_clairix_offline))
                        VpnCoverage.NONE -> KeyValueRow("VPN", stringResource(R.string.scan_none))
                    }
                    if (conn.metered) KeyValueRow(stringResource(R.string.scan_metered), stringResource(R.string.scan_yes))
                    conn.flags.forEach { flag ->
                        WarningLine(flag, if (conn.security.secure) Tone.Caution else Tone.Danger, Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ── Nearby ────────────────────────────────────────────────────────────
        if (wifi.available) {
            item(key = "§nearby-head") {
                SectionHeader(
                    stringResource(R.string.scan_in_range),
                    supporting = buildString {
                        append("${wifi.nearby.size}")
                        if (open > 0) append(stringResource(R.string.scan_open, open))
                        if (flagged > 0) append(stringResource(R.string.scan_flagged_2, flagged))
                    }
                )
            }
            if (wifi.nearby.isEmpty()) {
                item(key = "§none-nearby") {
                    Text(
                        if (status.scanning) {
                            stringResource(R.string.scan_nothing_in_range_when_the_scanner, fmtAgo(appResources(), now, wifi.scannedTs))
                        } else {
                            stringResource(R.string.scan_no_scan_results_yet_android_hands)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                }
            } else {
                // One lazy item per network, each drawing its slice of the group's
                // chamfered frame, so a long list of networks stays lazy.
                itemsIndexed(wifi.nearby, key = { _, n -> "ap|${n.bssid}" }) { i, n ->
                    NearbyWifiGroupItem(n, first = i == 0, last = i == wifi.nearby.lastIndex)
                }
            }
        }
    }
}

/**
 * One network in the "In range" group, as a lazy item. Each item draws its own
 * piece of the group's frame: the cut corner on top of the first, on the bottom
 * of the last, and a hairline between neighbours. The list spaces its items
 * 8 dp apart; every item but the last reports itself 8 dp shorter, so the next
 * one starts right under it and the group reads as one card.
 */
/** The gap ScreenList leaves between items. */
private val LIST_SPACING = 8.dp

@Composable
private fun NearbyWifiGroupItem(n: WifiNetwork, first: Boolean, last: Boolean) {
    val c = ClairixTheme.colors
    val shape = CutCornerShape(topStart = if (first) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp)
    Column(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val overlap = if (last) 0 else LIST_SPACING.roundToPx()
                layout(placeable.width, placeable.height - overlap) { placeable.place(0, 0) }
            }
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface, shape)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(c.border, size = Size(w, size.height))
                drawRect(c.border, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
                if (first) drawRect(c.border, size = Size(size.width, w))
                if (last) drawRect(c.border, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
            }
    ) {
        if (!first) Hairline()
        NearbyWifiRow(n)
    }
}

@Composable
private fun NearbyWifiRow(n: WifiNetwork) {
    val c = ClairixTheme.colors
    val tone = when {
        n.flags.any { it.contains("evil twin") || it.contains("interception") || it.contains("bait") } -> Tone.Danger
        !n.security.secure -> Tone.Caution
        n.flags.isNotEmpty() -> Tone.Caution
        n.connected -> Tone.Safe
        else -> Tone.Neutral
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SignalBars(n.level, tone = signalTone(n.level), height = 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (n.hidden) stringResource(R.string.scan_hidden_network) else n.ssid,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (n.hidden) c.textSubtle else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (n.connected) StatusPill(stringResource(R.string.scan_connected), Tone.Safe)
            }
            Text(
                "${n.bssid} · ${n.band}" + (n.channel?.let { " ch $it" } ?: "") + (n.standard?.let { " · ${it.substringBefore(" (")}" } ?: ""),
                style = MaterialTheme.typography.monoSmall,
                color = c.textSubtle
            )
            n.flags.forEach { flag -> WarningLine(flag, if (tone == Tone.Neutral || tone == Tone.Safe) Tone.Caution else tone) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fmtDbm(n.rssi), style = MaterialTheme.typography.monoSmall, color = c.textMuted)
            Text(
                n.security.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (n.security.secure) c.textSubtle else c.caution
            )
        }
    }
}

@Composable
private fun WifiRow(a: WifiAnomaly) {
    val c = ClairixTheme.colors
    val tone = a.threat.tone()
    ClairixCard(tone = tone, spacing = 4.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                a.ssid.ifBlank { stringResource(R.string.scan_hidden_network) }, style = MaterialTheme.typography.titleMedium, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            StatusPill(levelLabel(a.threat.name), tone)
        }
        Text(wifiReason(a.reason), style = MaterialTheme.typography.bodyMedium, color = c.text)
        Text("${a.bssid} · ${fmtDbm(a.rssi)} · ${fmtTime(a.ts)}", style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
    }
}

// ── Threat explainer bottom sheet ───────────────────────────────────────────

private data class ExplainerContent(
    val headline: String,
    val whatIsThis: String,
    val whatToDo: String,
    val howCertain: String,
    val tone: Tone
)

@Composable
private fun bleExplainerContent(d: Detection): ExplainerContent {
    val tone = if (d.threat.ordinal >= Threat.HIGH.ordinal) Tone.Danger else Tone.Caution
    return when {
        d.resolved -> ExplainerContent(
            headline = stringResource(R.string.scan_marked_found, d.name),
            whatIsThis = "You marked this device found at ${fmtClock(d.resolvedAt)}. ${(if (d.following) "Before that it was confirmed moving with you, heard at places ${d.displacementM.roundToInt()} metres apart."
                else "Before that it kept showing up near you.")}${(d.tracker?.let { " It identifies itself as ${it.label} (${it.brand})." } ?: "")}",
            whatToDo = stringResource(R.string.scan_keep_what_you_found_as_evidence),
            howCertain = stringResource(R.string.scan_the_record_is_what_was_confirmed, d.sightings, d.score),
            tone = Tone.Caution
        )
        d.following && d.threat == Threat.CRITICAL -> ExplainerContent(
            headline = stringResource(R.string.scan_tracker_on_you_2, d.name),
            whatIsThis = "A Bluetooth tracker has been verified as following your movements. It was heard at places ${d.displacementM.roundToInt()} metres apart while your own position showed you travelling between them, which cannot be explained by proximity alone.${(d.tracker?.let { " It identifies itself as ${it.label} (${it.brand}), away from its owner." } ?: "")}",
            whatToDo = stringResource(R.string.scan_stop_and_search_the_vehicle_or),
            howCertain = stringResource(R.string.scan_high_confidence_confirmation_requires_the_device, d.displacementM.roundToInt(), d.score, d.sightings),
            tone = tone
        )
        d.following -> {
            // Moved with you, but not a recognised tracker away from its owner: either
            // nothing identifies it, or it is a tag with its owner's phone in range.
            val known = d.tracker
            ExplainerContent(
                headline = stringResource(R.string.scan_travelling_with_you_2, d.name),
                whatIsThis = "This device was heard at places ${d.displacementM.roundToInt()} metres apart while you travelled, so it moved with you. ${(if (known != null) {
                        "It identifies itself as ${known.label}, which Clairix does not count as a tracker away from its owner. ${known.notes}"
                    } else {
                        "Nothing identifies it as a tracker: the phone, earbuds or watch of someone travelling with you look exactly like this, and so do a vehicle's own electronics. A hardwired GPS tracker looks the same, which is why it is shown."
                    })}",
                whatToDo = stringResource(R.string.scan_if_you_were_alone_check_the),
                howCertain = "That it moved with you is confirmed over ${d.sightings} sightings. ${(if (known != null) "It is not a tracker away from its owner, so Clairix does not say it is following you. "
                    else "What it is remains unknown, so Clairix does not call it a tracker. ")}Threat score: ${d.score}/100.",
                tone = Tone.Caution
            )
        }
        d.persistent && d.identified -> ExplainerContent(
            headline = stringResource(R.string.scan_known_tracker_nearby, d.name),
            whatIsThis = "A ${d.tracker?.brand ?: "commercial"} ${d.tracker?.label ?: "tracker"} has been " +
                "present for an extended period. Known trackers are purpose-built surveillance devices. " +
                "Extended presence without confirmed movement suggests it may be attached to your vehicle or belongings.",
            whatToDo = stringResource(R.string.scan_monitor_this_device_if_it_remains),
            howCertain = stringResource(R.string.scan_medium_to_high_confidence_device_is, d.sightings, d.score),
            tone = tone
        )
        d.persistent -> ExplainerContent(
            headline = stringResource(R.string.scan_unknown_device_keeps_showing_up, d.name),
            whatIsThis = stringResource(R.string.scan_an_unidentified_bluetooth_device_has_been),
            whatToDo = stringResource(R.string.scan_watch_for_this_device_across_different),
            howCertain = stringResource(R.string.scan_low_to_medium_confidence_no_known, d.score, d.sightings),
            tone = tone
        )
        else -> ExplainerContent(
            headline = "${levelLabel(d.threat.name)} risk: ${d.name}",
            whatIsThis = "This device has characteristics associated with surveillance trackers. " +
                "${d.tracker?.let { "It matches the signature of a ${it.brand} ${it.label}." } ?: "No exact tracker signature matched, but its signal pattern warrants monitoring."}",
            whatToDo = stringResource(R.string.scan_continue_monitoring_the_scan_needs_more),
            howCertain = stringResource(R.string.scan_building_confidence_score_100_sightings_gps, d.score, d.sightings, AppSettings.followThresholdM.roundToInt()),
            tone = tone
        )
    }
}

/**
 * What the phone itself can do against a fake tower, for the findings that point at
 * a forced drop to an older network: refuse 2G, and let Android warn about networks
 * that turn encryption off. Neither can be read or set by an app.
 */
private const val CELL_PLATFORM_DEFENCE =
    "For the next time: if your phone has the \"Allow 2G\" switch, turn it off (Phone check → Health shows it), and on Android 14 and later turn on Android's own cell network protections, on phones whose modem supports them, so the phone refuses or warns about a network that turns encryption off (Settings, search for \"network security\")."

@Composable
private fun cellExplainerContent(f: CatcherFinding): ExplainerContent {
    val tone = f.severity.tone()
    // Findings that can fire for more than one cell carry it after a colon, so the
    // explainer keys off the heuristic name in front of it.
    val (what, todo, certain) = when (f.id.substringBefore(':')) {
        "rat_downgrade" -> Triple(
            stringResource(R.string.scan_your_device_has_dropped_from_a),
            stringResource(R.string.scan_avoid_making_voice_calls_or_sending, CELL_PLATFORM_DEFENCE),
            stringResource(R.string.scan_high_confidence_indicator_a_drop_to)
        )
        "cellid_tac_mismatch" -> Triple(
            stringResource(R.string.scan_a_cell_tower_id_that_you),
            stringResource(R.string.scan_monitor_for_additional_indicators_a_single),
            stringResource(R.string.scan_high_confidence_indicator_when_combined_with)
        )
        "tac_change_stationary" -> Triple(
            stringResource(R.string.scan_your_device_registered_a_different_tracking),
            stringResource(R.string.scan_note_your_exact_location_and_time),
            stringResource(R.string.scan_medium_confidence_stationary_area_changes_can)
        )
        "signal_outlier" -> Triple(
            stringResource(R.string.scan_the_cell_signal_is_significantly_stronger),
            stringResource(R.string.scan_check_your_surroundings_for_parked_vehicles),
            stringResource(R.string.scan_medium_confidence_signal_strength_varies_for)
        )
        "unknown_cell" -> Triple(
            stringResource(R.string.scan_a_cell_tower_that_has_never),
            stringResource(R.string.scan_a_new_cell_at_a_familiar),
            stringResource(R.string.scan_medium_confidence_new_towers_do_get)
        )
        "ephemeral_cell", "ephemeral_cell_strict" -> Triple(
            stringResource(R.string.scan_a_cell_tower_appeared_briefly_as),
            stringResource(R.string.scan_note_the_time_and_location_an),
            stringResource(R.string.scan_medium_to_high_confidence_ephemeral_cells)
        )
        "cell_flapping" -> Triple(
            stringResource(R.string.scan_your_device_has_switched_between_multiple),
            stringResource(R.string.scan_avoid_making_calls_or_sending_messages),
            stringResource(R.string.scan_medium_confidence_rapid_cell_switching_can)
        )
        "no_neighbors" -> Triple(
            stringResource(R.string.scan_your_serving_cell_is_reporting_zero),
            stringResource(R.string.scan_this_is_a_supporting_indicator_rather),
            stringResource(R.string.scan_low_confidence_as_a_standalone_indicator)
        )
        "timing_advance_zero" -> Triple(
            stringResource(R.string.scan_lte_timing_advance_of_zero_means),
            stringResource(R.string.scan_nothing_on_its_own_if_it),
            stringResource(R.string.scan_low_confidence_alone_the_distance_is)
        )
        "signal_spike" -> Triple(
            stringResource(R.string.scan_the_signal_strength_on_your_current),
            stringResource(R.string.scan_a_sudden_signal_spike_suggests_a),
            stringResource(R.string.scan_medium_confidence_signal_spikes_can_result)
        )
        "rat_oscillation" -> Triple(
            stringResource(R.string.scan_your_device_has_switched_between_three),
            stringResource(R.string.scan_multiple_technology_switches_in_a_short, CELL_PLATFORM_DEFENCE),
            stringResource(R.string.scan_high_confidence_rapid_multi_technology_oscillati)
        )
        else -> Triple(
            stringResource(R.string.scan_an_anomaly_was_detected_in_your, f.id),
            stringResource(R.string.scan_monitor_for_additional_indicators_if_multiple),
            stringResource(R.string.scan_confidence_level, f.severity.name.lowercase())
        )
    }
    return ExplainerContent(
        headline = f.title,
        whatIsThis = what,
        whatToDo = todo,
        howCertain = certain,
        tone = tone
    )
}

/**
 * The co-traveller explanation of a trip-memory finding with no sign the journeys were
 * in the owner's car: probably the bus, the train or a fellow commuter, and what to do
 * if they were in her car after all.
 */
@Composable
private fun journeysExplainerContent(e: TimelineEvent): ExplainerContent = ExplainerContent(
    headline = e.title,
    whatIsThis = VehicleTrips.CO_TRAVELLER_WHAT_IS_THIS,
    whatToDo = VehicleTrips.CO_TRAVELLER_WHAT_TO_DO,
    howCertain = VehicleTrips.CO_TRAVELLER_HOW_CERTAIN,
    tone = Tone.Caution
)

@Composable
private fun ThreatExplainerSheet(target: ExplainerTarget, onDismiss: () -> Unit) {
    val content = when (target) {
        is ExplainerTarget.BleDevice -> bleExplainerContent(target.detection)
        is ExplainerTarget.CellIndicator -> cellExplainerContent(target.finding)
        is ExplainerTarget.Journeys -> journeysExplainerContent(target.event)
    }
    val c = ClairixTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            // These explainers run to several paragraphs. Without a scroll the tail of
            // the text and the Close button fall off the bottom of the sheet on a
            // normal-height phone, leaving no way to read or dismiss them.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                when (content.tone) {
                    Tone.Danger -> stringResource(R.string.scan_act_now)
                    Tone.Caution -> stringResource(R.string.scan_keep_watching)
                    else -> stringResource(R.string.scan_for_your_information)
                },
                content.tone
            )
        }
        Text(
            content.headline,
            style = MaterialTheme.typography.headlineSmall,
            color = c.text,
            modifier = Modifier.semantics { heading() }
        )

        // Most useful first: what to do, then what it is, then how sure.
        ExplainerSection(stringResource(R.string.scan_what_to_do), content.whatToDo, Tone.Accent)
        // A tracker on you, a device travelling with you or a known tracker that
        // stays: removing it can tell whoever placed it, so that is said next.
        val detection = (target as? ExplainerTarget.BleDevice)?.detection
        if (detection != null && SafetyFirst.forDetection(detection)) SafetyFirstCard()
        ExplainerSection(stringResource(R.string.scan_what_this_is), content.whatIsThis, Tone.Neutral)
        ExplainerSection(stringResource(R.string.scan_how_sure_we_are), content.howCertain, Tone.Neutral)

        PrimaryButton(stringResource(R.string.scan_got_it), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ExplainerSection(heading: String, body: String, tone: Tone) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(heading, tone = tone)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = ClairixTheme.colors.textMuted)
    }
}

