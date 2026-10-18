package app.clairix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import app.clairix.analysis.CardVault
import app.clairix.analysis.CellMonitor
import app.clairix.analysis.NfcScanner
import app.clairix.analysis.WifiScanner
import app.clairix.security.AppLock
import app.clairix.security.LockScreen
import app.clairix.analysis.PhoneHealthMonitor
import app.clairix.security.BiometricTripwire
import app.clairix.security.ScreenRecordingMonitor
import app.clairix.security.UnlockLedger
import app.clairix.analysis.Report
import app.clairix.detect.BleNames
import app.clairix.comms.CallManager
import app.clairix.comms.CallPhase
import app.clairix.comms.CommsNotifications
import app.clairix.comms.CommsRepository
import android.nfc.cardemulation.CardEmulation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.content.ComponentName
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
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
import app.clairix.ui.theme.ClairixMotion
import app.clairix.ui.components.*
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.monoLarge
import app.clairix.ui.theme.mono

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
private const val ROUTE_NFC = 4
private const val ROUTE_COMMS = CommsNotifications.COMMS_TAB_INDEX

// ── Activity ───────────────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null

    // Constructing the vault touches nothing; the Keystore is only reached inside
    // its methods, which never throw — so no keystore state can take down launch.
    private val cardVault by lazy { CardVault(applicationContext) }
    private val hceComponent by lazy { ComponentName(this, CardEmulationService::class.java) }

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

    /** Whether reader mode is currently enabled on the adapter by this activity. */
    private var readerModeOn = false

    /**
     * True between onResume and onPause. The lifecycle's own state cannot be used
     * for this from inside onResume: androidx only marks the Activity RESUMED after
     * onResume returns, so a guard on it there always failed and reader mode was
     * never switched on.
     */
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppSettings.load(this)
        AppLock.init(this)
        // With a passcode set, what was on screen must not outlive the lock in the
        // Recents thumbnail (taken as the app leaves, before the relock delay is
        // up) or in a screenshot taken by another app.
        lifecycleScope.launch {
            AppLock.enabled.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        Registry.bindTrustStore(this)

        if (BuildConfig.FULL_ACCESS) {
            // Platform hardening. The unlock ledger opens so the Device tab can read
            // it; this Activity's window is watched for being recorded; and the
            // enrolment tripwire is tested once per launch — Keystore work, off the
            // main thread. Each publishes straight into the Registry.
            UnlockLedger.init(this)
            ScreenRecordingMonitor.start(this, windowManager)
            lifecycleScope.launch(Dispatchers.IO) { runCatching { BiometricTripwire.check(applicationContext) } }

            // The comms-only build declares no NFC permission or HCE service, so its
            // adapter stays null and every NFC path below is a no-op.
            nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
            cardEmulation = nfcAdapter?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }

            // Emulation state lives only in this process, so if nothing is armed now,
            // any AIDs still registered belong to a previous process — they survive
            // reboots — and would keep routing readers to the service. Drop them.
            if (Registry.emulating.value == null) clearHceAids()

            // Show the stored timeline even before the scanner has run this session.
            lifecycleScope.launch(Dispatchers.IO) { runCatching { TimelineLog.publish(applicationContext) } }
        }

        // Results of BiometricPrompt arrive through the Registry, so that whichever
        // Activity instance is on screen when they land carries the operation out.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Registry.vaultAuth.collect { pending ->
                    if (pending != null) Registry.takeVaultAuth()?.let { handleAuthResult(it) }
                }
            }
        }

        // A tag tapped while Clairix was not in reader mode (backgrounded, or picked
        // from the system's NFC chooser) arrives as the launching intent.
        handleNfcIntent(intent)

        CommsRepository.init(applicationContext)
        CommsNotifications.ensureChannels(this)

        handleOpenThreadIntent(intent)

        setContent {
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
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
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
                                    onAddToVault = { tag, label, replace -> addToVault(tag, label, replace) },
                                    onRemoveVault = { id -> removeFromVault(id) },
                                    onEmulate = { card -> if (card == null) stopEmulation() else armEmulation(card) },
                                    onUnlockVault = { unlockVault() },
                                    onLockVault = { Registry.lockVault() },
                                    onEraseVault = { eraseVault() },
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

    override fun onResume() {
        super.onResume()
        resumed = true
        AppLock.onForeground(this)
        applyNfcMode()
        if (BuildConfig.FULL_ACCESS) refreshDeviceHealth()
        // Envelopes that arrived while the app was closed are pulled on every
        // return to the foreground; the push endpoint is renewed if it was lost.
        // While the app stays on screen the relay socket is held open, so what
        // arrives after this sync lands live instead of waiting for the next one.
        CommsRepository.onAppVisible(true)
        CommsRepository.registerPushIfPossible()
        CommsRepository.syncInBackground()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        AppLock.onBackground(this)
        CommsRepository.onAppVisible(false)
        nfcAdapter?.let { adapter ->
            if (readerModeOn) runCatching { adapter.disableReaderMode(this) }
        }
        readerModeOn = false
        runCatching { cardEmulation?.unsetPreferredService(this) }
    }

    /** The activity is singleTop, so a tag delivered while it is up lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
        handleOpenThreadIntent(intent)
    }

    /**
     * A message notification asks for the COMMS tab and the conversation it is
     * about. This Activity is exported, so any app can send it these extras;
     * they only ever navigate. Calls are answered in [CallActivity], which is
     * not exported.
     */
    private fun handleOpenThreadIntent(intent: Intent?) {
        // An invite link (the relay's invite page opens clairix://invite?c=…). It
        // only fills in the COMMS setup or offers to add the inviter; nothing is
        // registered or added until the owner confirms on screen.
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "clairix" && data.host == "invite") {
            data.getQueryParameter("c")?.let { Registry.requestInvite(it) }
            intent.data = null
            Registry.requestTab(CommsNotifications.COMMS_TAB_INDEX)
            return
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
     * The manifest registers for NDEF, TECH and TAG discovery, which puts Clairix in
     * the system's NFC chooser and delivers tags tapped while it is backgrounded.
     * Those intents were never read, so choosing Clairix dropped the tag on the floor.
     */
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        // Consumed: a later onCreate (recreation) must not scan the same tag again.
        intent.action = null
        Registry.requestTab(ROUTE_NFC)
        // Parsing talks to the tag over the air and must not block the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val parsed = try {
                NfcScanner.parse(tag, NfcScanner.readsMifareClassic(this@MainActivity))
            } catch (_: Exception) { return@launch }
            Registry.addNfc(parsed)
            runCatching { recordNfc(parsed) }
        }
    }

    /**
     * Runs the static findings scan off the main thread and publishes the result,
     * and brings the unlock ledger up to date from the usage log while it is there.
     */
    private fun refreshDeviceHealth() {
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
        if (Registry.status.value.scanning) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val reason = cellMonitor.unavailableReason()
                val radio = runCatching { cellMonitor.radioInfo() }.getOrNull()
                if (reason != null) {
                    Registry.publishCell(CellStatus(available = false, reason = reason, radio = radio))
                    return@launch
                }
                val snapshot = cellMonitor.sampleFull()
                if (Registry.status.value.scanning) return@launch
                if (snapshot == null) {
                    Registry.publishCell(
                        CellStatus(
                            available = false, radio = radio,
                            reason = "The modem has not reported a serving cell yet"
                        )
                    )
                } else {
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

    /** Refreshes the scan and reads the connected network and what is in range for the WIFI tab. */
    private fun refreshWifi() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // The snapshot requests a scan and waits for its results itself.
                Registry.publishWifiStatus(WifiScanner.snapshot(applicationContext))
                // The judgement on what is in range is cheap and service-free too.
                if (!Registry.status.value.scanning) Registry.publishWifi(WifiScanner.scan(applicationContext))
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

    // ── NFC: reader mode versus card emulation ───────────────────────────────

    /**
     * Reader mode and card emulation are mutually exclusive: while reader mode is on,
     * the controller polls for tags and never answers a reader as a card. It used to
     * be switched on unconditionally in onResume, so emulation could not work at all
     * while Clairix was on screen. Now, while a card is armed, reader mode stays off
     * and Clairix is made the preferred HCE service for as long as it is in front.
     */
    private fun applyNfcMode() {
        // Reader mode is only valid on a resumed, live activity; the flag is kept by
        // hand because the lifecycle is still STARTED while onResume runs.
        if (!resumed || isFinishing) return
        val adapter = nfcAdapter ?: return
        if (Registry.emulating.value != null) {
            if (readerModeOn) runCatching { adapter.disableReaderMode(this) }
            readerModeOn = false
            runCatching { cardEmulation?.setPreferredService(this, hceComponent) }
        } else {
            runCatching { cardEmulation?.unsetPreferredService(this) }
            if (!readerModeOn) {
                readerModeOn = runCatching {
                    adapter.enableReaderMode(
                        this, readerCallback,
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                        null
                    )
                }.isSuccess
            }
        }
    }

    /** Runs on an NFC binder thread, so the timeline's disk write is fine here. */
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val parsed = try {
            NfcScanner.parse(tag, NfcScanner.readsMifareClassic(this@MainActivity))
        } catch (_: Exception) { return@ReaderCallback }
        Registry.addNfc(parsed)
        runCatching { recordNfc(parsed) }
    }

    private fun recordNfc(t: NfcTag) {
        val now = System.currentTimeMillis()
        val status = Registry.status.value
        val detail = buildString {
            append("UID ").append(t.uid)
            if (t.atqa != null && t.sak != null) append(" · ATQA ${t.atqa} SAK ${t.sak}")
            if (t.skimmerFlags.isNotEmpty()) append(" — flags: ").append(t.skimmerFlags.joinToString("; "))
            else if (t.suspicious) append(" — ").append(t.note)
        }
        TimelineLog.record(
            applicationContext,
            TimelineEvent(
                id = "nfc@${t.uid}@$now",
                kind = EventKind.NFC_TAG,
                ts = now,
                title = if (t.suspicious) "Suspicious NFC tag: ${t.type}" else "NFC tag scanned: ${t.type}",
                detail = detail,
                severity = if (t.suspicious) Severity.HIGH else Severity.LOW,
                lat = status.lat, lon = status.lon
            ),
            dedupeKey = "nfc@${t.uid}"
        )
    }

    // ── Card emulation ───────────────────────────────────────────────────────

    private fun armEmulation(card: VaultCard) {
        // Emulating replays the card's stored credential, so it needs the vault open
        // — the card has to come from an unlocked vault, not a stale UI row.
        val state = Registry.vault.value
        if (state !is VaultState.Unlocked || state.cards.none { it.id == card.id }) {
            unlockVault()
            return
        }
        val emulation = cardEmulation ?: run {
            toast("This phone does not support NFC card emulation")
            return
        }
        val aids = card.apduPairs.mapNotNull { (cmd, _) ->
            val bytes = cmd.replace(" ", "")
            if (bytes.length >= 10 && (
                bytes.startsWith("00A40400", ignoreCase = true) ||
                bytes.startsWith("00A40404", ignoreCase = true))) {
                val len = bytes.substring(8, 10).toIntOrNull(16) ?: 0
                bytes.substring(10, minOf(10 + len * 2, bytes.length))
            } else null
        }.distinct().ifEmpty { listOf("F000000000") }

        val loaded = runCatching { CardEmulationService.loadPairs(card.apduPairs) }.isSuccess
        val registered = loaded && runCatching {
            emulation.registerAidsForService(hceComponent, CardEmulation.CATEGORY_OTHER, aids)
        }.getOrDefault(false)
        if (!registered) {
            CardEmulationService.activePairs = emptyList()
            clearHceAids()
            // The pairs and AIDs are gone, so whatever was armed before — switching
            // from card A to card B that then failed — is gone with them. Say so,
            // or the banner keeps announcing A with nothing behind it.
            Registry.setEmulating(null)
            applyNfcMode()
            toast("Android refused this card's application IDs — it cannot be emulated")
            return
        }
        Registry.setEmulating(ArmedCard(card.id, card.label))
        applyNfcMode()
    }

    private fun stopEmulation() {
        CardEmulationService.activePairs = emptyList()
        clearHceAids()
        Registry.setEmulating(null)
        applyNfcMode()
    }

    /** Removes the dynamically registered AIDs, which otherwise persist across reboots. */
    private fun clearHceAids() {
        runCatching { cardEmulation?.removeAidsForService(hceComponent, CardEmulation.CATEGORY_OTHER) }
    }

    // ── Vault ────────────────────────────────────────────────────────────────

    /**
     * Decrypted cards are only ever shown to an Activity that is on screen. onStop
     * re-locks the vault, and a decryption or save still in flight at that moment
     * used to publish Unlocked(cards) straight after it, leaving the cards readable
     * from the background. Anything that would publish Unlocked goes through here.
     */
    private fun publishUnlocked(cards: List<VaultCard>) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Registry.publishVault(VaultState.Unlocked(cards))
        }
        // Otherwise onStop has locked it; the cards are discarded with the coroutine.
    }

    /** Authenticates, then decrypts and publishes the vault. */
    private fun unlockVault(attempt: Int = 0) {
        Registry.publishVault(VaultState.Unlocking)
        authenticate(VaultAuthPurpose.Unlock(attempt))
    }

    private fun loadVault(attempt: Int) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { cardVault.load() }
            when (result) {
                is CardVault.LoadResult.Loaded -> publishUnlocked(result.cards)
                is CardVault.LoadResult.Failed ->
                    if (result.failure is CardVault.Failure.AuthRequired && attempt == 0) {
                        // The auth did not reach the key (it can lapse between the prompt
                        // and the decrypt). One re-prompt — but only while the Activity
                        // is in front to show it; otherwise "Unlocking" would sit there
                        // forever with no prompt behind it.
                        if (resumed) unlockVault(attempt + 1) else Registry.publishVault(VaultState.Locked)
                    } else {
                        Registry.publishVault(failedState(result.failure))
                    }
            }
        }
    }

    private fun addToVault(tag: NfcTag, label: String, replace: Boolean) {
        val card = VaultCard(
            id        = UUID.randomUUID().toString(),
            uid       = tag.uid,
            label     = label,
            profile   = tag.profile,
            addedTs   = System.currentTimeMillis(),
            apduPairs = tag.apduPairs
        )
        Registry.setReplacePrompt(null)
        writeVault(VaultOp.Add(card, replace))
    }

    private fun removeFromVault(id: String) {
        writeVault(VaultOp.Remove(id))
    }

    /**
     * Runs a vault write. The key's authentication lasts 30 seconds, so a write some
     * time after unlocking needs the user again: on AuthRequired this prompts and
     * retries once. Any other failure leaves the stored vault untouched and says why.
     *
     * The operation is data ([VaultOp]) rather than a closure so the retry can be
     * carried out by whichever Activity instance receives the prompt's result.
     */
    private fun writeVault(op: VaultOp, attempt: Int = 0) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (op) {
                    is VaultOp.Add -> cardVault.add(op.card, op.replace)
                    is VaultOp.Remove -> cardVault.remove(op.id)
                }
            }
            when (result) {
                is CardVault.WriteResult.Saved -> {
                    // Always publish the fresh list: + VAULT is only offered while the
                    // vault is open, and a save that went through the prompt has just
                    // proved the user's identity. Either way the tag row's IN VAULT
                    // state comes from this list, so it has to be current.
                    publishUnlocked(result.cards)
                    when (op) {
                        is VaultOp.Add -> toast("Saved \"${op.card.label}\" to the vault")
                        is VaultOp.Remove -> if (Registry.emulating.value?.id == op.id) stopEmulation()
                    }
                }
                is CardVault.WriteResult.Failed -> when (val f = result.failure) {
                    CardVault.Failure.AuthRequired ->
                        if (attempt == 0 && resumed) {
                            authenticate(VaultAuthPurpose.Write(op, attempt + 1))
                        } else {
                            toast("Vault not changed — authentication did not take effect")
                        }
                    CardVault.Failure.NoLockScreen -> toast(NO_LOCK_SCREEN)
                    is CardVault.Failure.Duplicate -> {
                        // Only an Add can be refused this way; the UI asks before
                        // overwriting, and the answer comes back with replace = true.
                        val add = op as? VaultOp.Add ?: return@launch
                        val tag = Registry.nfc.value.firstOrNull { it.uid == add.card.uid }
                        if (tag != null) {
                            Registry.setReplacePrompt(ReplacePrompt(tag, add.card.label, f.existing))
                        } else {
                            toast("\"${f.existing.label}\" is already in the vault with this UID")
                        }
                    }
                    else -> {
                        toast("Vault not changed — it could not be read")
                        Registry.publishVault(failedState(f))
                    }
                }
            }
        }
    }

    /**
     * Deletes an unreadable vault at the user's explicit request (behind a confirm
     * dialog). Confirms identity first when the phone can, so a borrowed, unlocked
     * phone cannot be used to wipe it.
     */
    private fun eraseVault() {
        if (BiometricManager.from(this).canAuthenticate(VAULT_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            authenticate(VaultAuthPurpose.Erase)
        } else {
            doErase()
        }
    }

    private fun doErase() {
        lifecycleScope.launch {
            val erased = withContext(Dispatchers.IO) { cardVault.erase() }
            if (erased) {
                if (Registry.emulating.value != null) stopEmulation()
                publishUnlocked(emptyList())
                toast("Vault erased")
            } else {
                toast("The vault could not be erased")
            }
        }
    }

    /** Carries out the operation a completed BiometricPrompt was shown for. */
    private fun handleAuthResult(result: VaultAuthResult) {
        val purpose = result.purpose
        when (val outcome = result.outcome) {
            VaultAuthOutcome.Succeeded -> when (purpose) {
                is VaultAuthPurpose.Unlock -> loadVault(purpose.attempt)
                is VaultAuthPurpose.Write -> writeVault(purpose.op, purpose.attempt)
                VaultAuthPurpose.Erase -> doErase()
            }
            is VaultAuthOutcome.Failed -> when (purpose) {
                is VaultAuthPurpose.Unlock -> Registry.publishVault(
                    if (outcome.message == null) VaultState.Locked
                    else VaultState.Failed(outcome.message, retryable = true, erasable = false)
                )
                is VaultAuthPurpose.Write ->
                    toast(outcome.message ?: "Vault not changed — authentication cancelled")
                VaultAuthPurpose.Erase -> outcome.message?.let { toast(it) }
            }
        }
    }

    private fun failedState(f: CardVault.Failure): VaultState.Failed = when (f) {
        CardVault.Failure.AuthRequired -> VaultState.Failed(
            "Authentication did not reach the vault key. Try unlocking again.",
            retryable = true, erasable = false
        )
        CardVault.Failure.KeyInvalidated -> VaultState.Failed(
            "Android has permanently invalidated the vault key — this happens when the screen " +
                "lock is removed or reset. The stored cards can no longer be decrypted by anyone. " +
                "Nothing has been deleted; erase the vault to start a new one.",
            retryable = false, erasable = true
        )
        CardVault.Failure.NoLockScreen -> VaultState.Failed(
            NO_LOCK_SCREEN, retryable = true, erasable = false
        )
        // A refused overwrite is answered with a prompt, never a failed vault; this
        // only exists to keep the mapping total.
        is CardVault.Failure.Duplicate -> VaultState.Failed(
            "\"${f.existing.label}\" is already stored with this UID.",
            retryable = true, erasable = false
        )
        is CardVault.Failure.Error -> VaultState.Failed(
            "The vault could not be decrypted (${f.cause.javaClass.simpleName}). " +
                "Nothing has been changed or overwritten.",
            retryable = true, erasable = true
        )
    }

    /**
     * Shows BiometricPrompt allowing a strong biometric or the device PIN, pattern or
     * password — the same set the vault key accepts. The result is published to the
     * Registry as a [VaultAuthResult] for [purpose], not handed to a closure: the
     * callback then holds nothing of this Activity instance, and the instance alive
     * when the result lands (this one, or its replacement after a recreation) acts
     * on it.
     */
    private fun authenticate(purpose: VaultAuthPurpose) {
        fun fail(message: String?) =
            Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Failed(message)))

        when (val can = BiometricManager.from(this).canAuthenticate(VAULT_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { fail(NO_LOCK_SCREEN); return }
            else -> {
                fail("This phone cannot confirm your identity right now (code $can).")
                return
            }
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Registry.publishVaultAuth(VaultAuthResult(purpose, VaultAuthOutcome.Succeeded))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    Registry.publishVaultAuth(
                        VaultAuthResult(purpose, VaultAuthOutcome.Failed(if (cancelled) null else errString.toString()))
                    )
                }
                // onAuthenticationFailed is a single rejected attempt; the prompt
                // stays up for another try, so there is nothing to do.
            }
        )
        // Each operation says what it is: the erase and save prompts used to wear
        // the unlock's "view and use stored cards" copy.
        val (title, subtitle) = when (purpose) {
            is VaultAuthPurpose.Unlock -> "Unlock card vault" to "Confirm it's you to view and use stored cards"
            VaultAuthPurpose.Erase -> "Erase card vault" to "This permanently deletes every stored card"
            is VaultAuthPurpose.Write -> when (purpose.op) {
                is VaultOp.Add -> "Save card to vault" to "Confirm it's you to store this card"
                is VaultOp.Remove -> "Remove card from vault" to "Confirm it's you to change stored cards"
            }
        }
        // No negative button: with DEVICE_CREDENTIAL allowed the prompt supplies its
        // own "use PIN" path, and BiometricPrompt rejects a negative button then.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(VAULT_AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { fail("Could not show the ${title.lowercase(Locale.US)} prompt: ${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VAULT_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        const val NO_LOCK_SCREEN =
            "Set a screen lock (PIN, pattern or password) in Android settings to use the vault."
    }
}

// ── Formatting helpers ──────────────────────────────────────────────────

private val fullFmt    = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val clockFmt   = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dayFmt     = SimpleDateFormat("MMM d", Locale.US)
private val dayYearFmt = SimpleDateFormat("MMM d yyyy", Locale.US)

private fun fmtTime(ts: Long): String = fullFmt.format(Date(ts))
private fun fmtClock(ts: Long): String = clockFmt.format(Date(ts))

private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
        else -> "${s / 86_400} d ago"
    }
}

private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
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

private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

private fun compass(bearing: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45.0).toInt() % 8]
}

private fun dayLabel(ts: Long, now: Long): String {
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
    if (ts >= todayStart) return "Today"
    if (ts >= yesterdayStart) return "Yesterday"
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

// ── Threat explainer target ─────────────────────────────────────────────────

private sealed class ExplainerTarget {
    data class BleDevice(val detection: Detection) : ExplainerTarget()
    data class CellIndicator(val finding: CatcherFinding) : ExplainerTarget()
}

// ── SOS button ────────────────────────────────────────────────────────────

/** How long the SOS button must be held before the confirmation appears. */
private const val SOS_HOLD_MS = 1500L

/**
 * The SOS button: an octagon, like a stop sign and like the mark, in the danger
 * colour. Holding it fills it from the bottom; once [SOS_HOLD_MS] has passed a
 * confirm dialog appears, and [onConfirmed] runs only after the user taps Send.
 */
@Composable
internal fun SosButton(onConfirmed: () -> Unit) {
    var sosProgress by remember { mutableFloatStateOf(0f) }
    var sosPressing by remember { mutableStateOf(false) }
    var showSosConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(sosPressing) {
        if (sosPressing) {
            val start = System.currentTimeMillis()
            while (sosPressing) {
                sosProgress = (System.currentTimeMillis() - start) / SOS_HOLD_MS.toFloat()
                if (sosProgress >= 1f) {
                    showSosConfirm = true
                    sosPressing = false
                    break
                }
                delay(16)
            }
            if (!showSosConfirm) sosProgress = 0f
        } else if (!showSosConfirm) {
            sosProgress = 0f
        }
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
                contentDescription = "SOS. Hold to send an emergency alert to your groups"
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
            title = "Send an SOS?",
            text = "Your groups get an alert with your location right away.",
            confirmLabel = "Send SOS",
            confirmTone = Tone.Danger,
            onConfirm = {
                showSosConfirm = false
                sosProgress = 0f
                onConfirmed()
            },
            onDismiss = { showSosConfirm = false; sosProgress = 0f }
        )
    }
}

/** A short warning line: the warning icon and [text], both in [tone]. */
@Composable
private fun WarningLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
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
    onAddToVault: (NfcTag, String, Boolean) -> Unit,
    onRemoveVault: (String) -> Unit,
    onEmulate: (VaultCard?) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onEraseVault: () -> Unit,
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

    val tab = enumByName<HomeTab>(tabName)?.takeIf { it in tabs } ?: tabs.first()
    val category = enumByName<ScanCategory>(categoryName)
    // The tracker actions belong to the Trackers category and the Comms menu to
    // the full build; a saved name outside those is dropped rather than shown.
    val trackerAction = enumByName<TrackerAction>(trackerActionName)?.takeIf { category == ScanCategory.TRACKERS }
    val commsAction = enumByName<CommsAction>(commsActionName)?.takeIf { BuildConfig.FULL_ACCESS }

    val status by Registry.status.collectAsStateWithLifecycle()
    val unreadMessages by CommsRepository.unread.collectAsStateWithLifecycle()
    val activeCall by CallManager.call.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // A call ringing or in progress takes the Comms tab regardless of where the
    // user was. Settings, the Comms safety pages and the explainer sheet are drawn
    // over or instead of it, so they close too; otherwise the call's ACCEPT button
    // sat hidden behind them.
    LaunchedEffect(activeCall?.id, activeCall?.phase) {
        if (activeCall != null && activeCall?.phase != CallPhase.ENDED && HomeTab.COMMS in tabs) {
            tabName = HomeTab.COMMS.name
            commsActionName = null
            showSettings = false
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
    val threadRequest by Registry.threadRequest.collectAsStateWithLifecycle()
    val groupRequest by Registry.groupRequest.collectAsStateWithLifecycle()
    val inviteRequest by Registry.inviteRequest.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onClearData = onClearData
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

    val title = when (tab) {
        HomeTab.SCAN -> trackerAction?.title ?: category?.title ?: HomeTab.SCAN.label
        HomeTab.COMMS -> commsAction?.title ?: HomeTab.COMMS.label
        HomeTab.DRIVE, HomeTab.ACTIVITY -> tab.label
    }
    val menu = if (tab == HomeTab.COMMS && commsAction == null && BuildConfig.FULL_ACCESS) {
        CommsAction.entries.map { action -> ShellMenuItem(action.title, action.icon) { commsActionName = action.name } }
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
                SosButton(onConfirmed = {
                    scope.launch(Dispatchers.IO) {
                        suspend fun toast(text: String) = withContext(Dispatchers.Main) {
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                        // The alert goes out at once with whatever position is known.
                        // Waiting for a fix first held it up by several seconds; now a
                        // fix found afterwards follows as a second alert.
                        val s = Registry.status.value
                        val hadFix = s.lat != null && s.lon != null
                        Registry.setPanicActive(true)
                        toast(if (hadFix) "SOS sent with your location" else "SOS sent. Finding your location…")
                        if (hadFix) return@launch
                        val loc = sosLocation(context)
                        if (loc == null) { toast("No GPS fix to attach to the SOS"); return@launch }
                        // The first broadcast lowers the flag when it is done; a second
                        // raise before that would be lost, so wait for it.
                        Registry.panicActive.first { !it }
                        Registry.setPanicActive(true)
                        toast("Your location followed the SOS")
                    }
                })
            }
        }
    ) {
        when (tab) {
            HomeTab.SCAN -> when {
                trackerAction == TrackerAction.LOCATE -> FindTrackerPage()
                trackerAction == TrackerAction.SELF_TEST -> SelfTestPage()
                category == null -> ScanHub(
                    scanning = status.scanning,
                    statuses = statuses,
                    onOpen = { openCategory(it) }
                )
                category == ScanCategory.TRACKERS -> if (trackerSub == 0) {
                    ScanScreen(
                        onStart, onStop, hasPermissions,
                        onShowExplainer = { d -> explainerTarget = ExplainerTarget.BleDevice(d) }
                    )
                } else {
                    MapScreen()
                }
                category == ScanCategory.CAMERAS -> CameraSweepScreen()
                category == ScanCategory.CELL -> CellScreen(
                    onShowExplainer = { f -> explainerTarget = ExplainerTarget.CellIndicator(f) },
                    onShown = onRefreshCell
                )
                category == ScanCategory.WIFI -> WifiScreen(onShown = onRefreshWifi)
                category == ScanCategory.NFC -> NfcScreen(
                    onAddToVault = onAddToVault,
                    onRemoveVault = onRemoveVault,
                    onEmulate = onEmulate,
                    onUnlockVault = onUnlockVault,
                    onLockVault = onLockVault,
                    onEraseVault = onEraseVault
                )
                category == ScanCategory.PHONE -> when (phoneSub) {
                    0 -> DeviceScreen(onShown = onRefreshDeviceHealth)
                    1 -> StalkerwarePage()
                    else -> CertificatesPage()
                }
            }
            HomeTab.COMMS -> when (commsAction) {
                CommsAction.FAKE_CALL -> FakeCallPage()
                CommsAction.DURESS -> DuressPage()
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
            HomeTab.DRIVE -> DriveScreen()
            HomeTab.ACTIVITY -> when (activitySub) {
                0 -> TimelineScreen()
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

/**
 * A position for an SOS when the scanner has none (scanner off, or the
 * comms-only build, which has no scanner): the platform's last known position,
 * else a fresh fix with a short wait. Whatever is found is put in the status the
 * panic broadcast reads. Null when location is not granted or nothing could be
 * found in time.
 */
private fun sosLocation(context: Context): LatLon? {
    val granted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fix = runCatching { Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS) }.getOrNull()
        ?: runCatching {
            Tasks.await(
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token),
                6, TimeUnit.SECONDS
            )
        }.getOrNull()
        ?: return null
    Registry.update { it.copy(lat = fix.latitude, lon = fix.longitude) }
    return LatLon(fix.latitude, fix.longitude)
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
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            item(key = "§details") { ScanDetailsCard(status, detections, trusted) }
        }

        // ── Findings ──
        // With the scanner off the hero already says so; an empty list adds nothing.
        if (detections.isEmpty()) {
            if (status.scanning) item(key = "§empty") {
                EmptyState(
                    title = "Nothing flagged yet",
                    body = "Proving something follows you takes 10 minutes of presence and a 300 m move. Leave it running.",
                    icon = ClairixIcons.BluetoothSearching,
                    tone = Tone.Safe
                )
            }
        } else {
            item(key = "§list-label") {
                SectionHeader(
                    "Flagged devices",
                    supporting = "${live.size} live · ${detections.size - live.size} trusted"
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
            SecondaryButton(
                "Export evidence report",
                onClick = {
                    val i = Report.share(context, status, detections, timeline, cell, nfc)
                    context.startActivity(Intent.createChooser(i, "Share evidence report"))
                },
                icon = Icons.Filled.Share,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}

/** The verdict the Trackers screen leads with, and the start/stop switch under it. */
@Composable
private fun TrackerStatusHero(status: ScanStatus, live: List<Detection>, onToggle: () -> Unit) {
    val following = live.filter { it.following }
    val persistent = live.count { it.persistent }
    val (headline, body, tone) = when {
        !status.scanning -> Triple(
            "Scanner off",
            "Start scanning to check for Bluetooth trackers travelling with you.",
            Tone.Neutral
        )
        following.size == 1 -> Triple(
            "Tracker on you",
            "${following[0].name} was heard ${following[0].displacementM.roundToInt()} m apart while you moved. " +
                "Open it below to see where to look.",
            Tone.Danger
        )
        following.size > 1 -> Triple(
            "${following.size} trackers on you",
            "They were heard in different places while you moved. Open each one below to see where to look.",
            Tone.Danger
        )
        persistent > 0 -> Triple(
            "Keep watching",
            "${plural(persistent, "device")} keep showing up. Following is only confirmed once you move about " +
                "${AppSettings.followThresholdM.toInt()} m with GPS on.",
            Tone.Caution
        )
        else -> Triple(
            "Nothing following",
            "Watching ${plural(status.nearbyCount, "device")} nearby. Nothing has moved with you.",
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = if (status.scanning) "Trackers · scanning" else "Trackers",
        headline = headline,
        body = body,
        tone = tone,
        modifier = Modifier.padding(top = 8.dp),
        footer = {
            if (status.scanning) {
                SecondaryButton("Stop scanning", onClick = onToggle, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton("Start scanning", onClick = onToggle, modifier = Modifier.fillMaxWidth())
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
            StatTile("$score", "Threat level of 100", Modifier.weight(1f), tone = scoreTone)
            StatTile("${status.nearbyCount}", "In range", Modifier.weight(1f))
            StatTile(
                "${detections.size}", "Flagged", Modifier.weight(1f),
                tone = if (live.any { it.following }) Tone.Danger else Tone.Neutral
            )
        }
        ClairixCard(spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Threat level",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.text,
                    modifier = Modifier.weight(1f)
                )
                Text("$score / 100", style = MaterialTheme.typography.mono, color = meterColor(fraction))
            }
            ClairixProgressBar(fraction, tone = scoreTone)
            Text(
                "Bluetooth peak $bleScore · cellular $cellScore. The higher of the two sets the level.",
                style = MaterialTheme.typography.bodySmall,
                color = ClairixTheme.colors.textSubtle
            )
        }
    }
}

/** What the scan is working with: position, filtered noise, trusted devices, errors. */
@Composable
private fun ScanDetailsCard(status: ScanStatus, detections: List<Detection>, trusted: Set<String>) {
    ClairixCard(spacing = 4.dp) {
        val scanText = when {
            !status.scanning -> "Idle"
            status.nearbyCount > detections.size -> "${detections.size} flagged of ${status.nearbyCount} in range"
            else -> "${plural(detections.size, "device")} flagged"
        }
        KeyValueRow("Scan", scanText)
        val filtered = status.nearbyCount - detections.size
        if (status.scanning && filtered > 0) {
            KeyValueRow("Filtered out", "$filtered passing signals")
        }
        if (status.hasFix) {
            KeyValueRow("Position", "%.4f, %.4f".format(Locale.US, status.lat ?: 0.0, status.lon ?: 0.0))
            KeyValueRow(
                "Movement",
                "${if (status.moving) "Moving" else "Still"} · %.1f km".format(Locale.US, status.travelledM / 1000.0)
            )
        } else {
            WarningLine("No GPS fix. Following can't be confirmed without one.", Tone.Caution)
        }
        if (trusted.isNotEmpty()) {
            KeyValueRow("Marked safe", "${plural(trusted.size, "device")}, left out of the threat level", mono = false)
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
        emphasis = d.following && !trusted,
        onClick = { expanded = !expanded },
        onClickLabel = if (expanded) "Hide details" else "Show details"
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
            if (trusted) StatusPill("Trusted", Tone.Neutral)
            else StatusPill(if (d.following) "Following" else levelLabel(d.threat.name), d.threat.tone())
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
                "${d.sightings}× · ${fmtAgo(now, d.lastSeen)}",
                style = MaterialTheme.typography.monoSmall,
                color = c.textSubtle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RssiBar(d.rssi)
        }

        if (d.following && !trusted) {
            WarningLine("Moving with you. Heard ${d.displacementM.roundToInt()} m apart.", Tone.Danger)
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
        SectionHeader("Is it following you?")
        val (line, lineTone) = when (d.confidence) {
            FollowConfidence.NONE -> "Not enough data yet" to Tone.Neutral
            FollowConfidence.NO_POSITION -> "No GPS, so movement can't be confirmed" to Tone.Caution
            FollowConfidence.NOT_MOVED_ENOUGH -> "Nearby, not following" to Tone.Caution
            FollowConfidence.CONFIRMED -> "Following you" to Tone.Danger
        }
        Text(line, style = MaterialTheme.typography.titleSmall, color = if (trusted) c.textSubtle else lineTone.color())
        val explanation = when (d.confidence) {
            FollowConfidence.NONE ->
                if (d.identified) "Known tracker type, but heard too briefly to show a pattern."
                else "Watching for a pattern of it staying with you."
            FollowConfidence.NO_POSITION ->
                "It keeps showing up. A GPS fix is needed to prove it moves with you."
            FollowConfidence.NOT_MOVED_ENOUGH ->
                "It keeps showing up, but only ${d.displacementM.roundToInt()} m of shared travel so far. " +
                    "Confirming it needs about 300 m."
            FollowConfidence.CONFIRMED ->
                "Heard ${d.displacementM.roundToInt()} m apart while moving with you, so it is on you or your vehicle. " +
                    "Check wheel wells, bumper covers, the OBD-II port, under seats and bag linings."
        }
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)

        // ── What it is ──
        SectionHeader("Tracker type")
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
                Text("Made by ${t.brand}", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                Text(t.notes, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        } else {
            Text(
                "No known tracker signature matched. Flagged on behaviour alone: how long it stays, how it moves and its signal.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSubtle
            )
        }

        // What the radio itself says, resolved through the Bluetooth SIG assigned numbers.
        SectionHeader("Identification")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValueRow("Advertised name", d.advertisedName ?: "None broadcast", valueColor = if (d.advertisedName != null) c.text else c.textSubtle)
            KeyValueRow("Manufacturer", d.manufacturer ?: "Not advertised", valueColor = if (d.manufacturer != null) c.textMuted else c.textSubtle)
            KeyValueRow("Radio", d.radio)
            KeyValueRow(
                "Services",
                if (d.services.isEmpty()) "None advertised" else d.services.joinToString(" / "),
                valueColor = if (d.services.isEmpty()) c.textSubtle else c.textMuted
            )
            KeyValueRow("Address", BleNames.formatMac(d.address) + (d.addressKind?.let { " · $it" } ?: ""))
        }

        SectionHeader("Observation")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValueRow("First seen", fmtTime(d.firstSeen))
            KeyValueRow("Last seen", fmtTime(d.lastSeen))
            KeyValueRow("Present for", fmtDuration(d.lastSeen - d.firstSeen))
            KeyValueRow(
                "Sightings",
                if (d.places > 0) "${d.sightings} across ${plural(d.places, "place")}"
                else "${d.sightings}, no location data"
            )
            KeyValueRow("Distance", d.approxMetres?.let { fmtMetres(it) } ?: "Unknown")
            KeyValueRow("Signal", "${fmtDbm(d.rssi)} · ${BleNames.signalLabel(d.rssi)}")
            KeyValueRow("Spread", "${d.displacementM.roundToInt()} m apart")
            KeyValueRow(
                "Address changes",
                if (d.rotations > 0) "${d.rotations}× · ${d.addresses} ${if (d.addresses == 1) "address" else "addresses"} seen"
                else "Stable address",
                valueColor = if (d.rotations > 0) c.caution else c.textMuted
            )
            KeyValueRow("Threat score", "${d.score} / 100", valueColor = if (trusted) c.textSubtle else d.threat.tone().color())
        }

        // ── Actions ──
        if (trusted) {
            SecondaryButton(
                "Put it back in the threat level",
                onClick = { Registry.untrust(d.address) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            if (onShowExplainer != null) {
                PrimaryButton("What is this, and what do I do?", onClick = onShowExplainer, modifier = Modifier.fillMaxWidth())
            }
            SecondaryButton(
                "Mark as mine",
                onClick = { Registry.trust(d.address) },
                tone = Tone.Safe,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Marking it as yours removes it from the threat level. You can undo this here or in Settings.",
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

/** Keep a downloaded tile this long past whatever the tile server's headers allow. */
private const val TILE_EXTRA_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

private var osmConfigured = false

/**
 * osmdroid's global configuration, applied once per process rather than on every
 * visit to the Map tab.
 *
 * The important part is the tile lifetime. osmdroid re-requests any tile that is
 * past its expiry on every single draw of the map, and the OSM tile server hands
 * out Cache-Control lifetimes as short as fifteen minutes. Left at the default,
 * a map that had been open for a while re-downloaded its whole viewport each time
 * it was redrawn. Extending every tile's lifetime by a week keeps the map on the
 * disk cache for the areas the user actually moves through. This respects the
 * server's own headers as a minimum, as the OSM tile usage policy asks.
 *
 * Main thread only (called from composition).
 */
private fun configureOsm(context: android.content.Context) {
    if (osmConfigured) return
    osmConfigured = true
    with(OsmConfig.getInstance()) {
        load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        userAgentValue = "Clairix/${BuildConfig.VERSION_NAME}"
        expirationExtendedDuration = TILE_EXTRA_LIFETIME_MS
    }
}

@Composable
private fun MapScreen() {
    val mapData by Registry.map.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val sharedThreats by Registry.sharedThreats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal
    val followingTrails = mapData.devices.count { it.following }
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
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Tiles are 256 px; drawn unscaled, a phone screen at zoom 17 is around
            // fifty of them, and every pan, zoom or visit to this tab fetched that
            // many. Scaled to the display density it is a dozen or so, and the map
            // labels become legible at arm's length.
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            isFlingEnabled = true
        }
    }

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
                snippet = "${threat.kind} · ${fmtAgo(now, threat.ts)}"
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
                    append(if (status.hasFix) "GPS fix" else "No fix")
                    if (status.lat != null) append(" · %.5f, %.5f".format(Locale.US, status.lat, status.lon))
                    if (mapData.devices.isNotEmpty()) append(" · ${plural(mapData.devices.size, "device")}")
                },
                tone = if (status.hasFix) Tone.Neutral else Tone.Caution
            )
            if (cellHot) MapChip("Sketchy cell tower nearby · ${levelLabel(cell.level.name)}", Tone.Caution)
        }

        if (followingTrails > 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 88.dp, bottom = 16.dp)
                    .background(ClairixTheme.colors.danger, ClairixShapes.Control)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = ClairixTheme.colors.onDanger, modifier = Modifier.size(18.dp))
                Text(
                    if (followingTrails == 1) "1 tracker on you" else "$followingTrails trackers on you",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.onDanger
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

@Composable
internal fun TimelineScreen() {
    val events by Registry.timeline.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)
    // Log is newest-first, so groups come out Today, Yesterday, then older days.
    val groups = remember(events, now / 60_000L) { events.groupBy { dayLabel(it.ts, now) } }
    val flagged = remember(events) { events.count { it.severity.ordinal >= Severity.HIGH.ordinal || it.kind == EventKind.PANIC } }
    val today = groups[dayLabel(now, now)]?.size ?: 0

    ScreenList {
        // ── Status first: how much is on record and how much of it matters ──
        item(key = "§figures") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("$today", "Today", Modifier.weight(1f))
                StatTile("$flagged", "Flagged", Modifier.weight(1f), tone = if (flagged > 0) Tone.Danger else Tone.Neutral)
                StatTile("${events.size}", "On record", Modifier.weight(1f))
            }
        }
        if (events.isEmpty()) {
            item(key = "§empty") {
                EmptyState(
                    title = "Nothing recorded yet",
                    body = "Every finding lands here with the time and place it happened. Start scanning to begin.",
                    icon = ClairixIcons.Timeline
                )
            }
        } else {
            groups.forEach { (label, list) ->
                item(key = "§day-$label") { SectionHeader(label, supporting = plural(list.size, "event")) }
                items(list, key = { it.id }) { TimelineRow(it, status) }
            }
        }
    }
}

/**
 * A short tag for event kinds that come from outside this phone's own sensors,
 * so a row from a group member is not mistaken for a local detection. Null for
 * the ordinary kinds, whose title already says what happened.
 */
private fun eventKindTag(kind: EventKind): Pair<String, Tone>? = when (kind) {
    EventKind.SHARED_THREAT -> "Community report" to Tone.Info
    EventKind.PANIC -> "SOS alert" to Tone.Danger
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
private fun TimelineRow(e: TimelineEvent, status: ScanStatus) {
    var open by remember(e.id) { mutableStateOf(false) }
    val tone = eventTone(e)
    val tag = eventKindTag(e.kind)
    val lat = e.lat
    val lon = e.lon
    val c = ClairixTheme.colors

    ClairixCard(
        tone = tone,
        onClick = { open = !open },
        onClickLabel = if (open) "Hide details" else "Show details",
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
        if (lat != null && lon != null) {
            Text("%.5f, %.5f".format(Locale.US, lat, lon), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        }
        if (open) {
            Hairline(Modifier.padding(vertical = 4.dp))
            KeyValueRow("Time", fmtTime(e.ts))
            KeyValueRow("Kind", e.kind.name.replace('_', ' ').lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }, mono = false)
            KeyValueRow("Severity", levelLabel(e.severity.name), valueColor = if (tone == Tone.Neutral) null else tone.color(), mono = false)
            if (lat != null && lon != null) {
                EventLocationSnapshot(lat, lon, status)
            } else {
                Text("No location recorded for this event", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
            }
        }
    }
}

/** A square showing where the event happened relative to where the phone is now. */
@Composable
private fun EventLocationSnapshot(lat: Double, lon: Double, status: ScanStatus) {
    val c = ClairixTheme.colors
    val curLat = status.lat
    val curLon = status.lon
    if (!status.hasFix || curLat == null || curLon == null) {
        Text("No current fix to compare with", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
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
                .semantics { contentDescription = "Event position relative to you" }
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
                if (dist < 1.0) "Right here" else "${fmtMetres(dist).removePrefix("~")} away",
                style = MaterialTheme.typography.titleSmall,
                color = c.text
            )
            if (dist >= 1.0) Text("${compass(bearing)} of you", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Text("Centre is where you are now", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
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
    // screen; with it on, the service's judged readings arrive on the same cadence.
    LaunchedEffect(status.scanning) {
        while (true) {
            onShown()
            delay(15_000L)
        }
    }

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
                    SectionHeader("Indicators", supporting = "${cell.findings.size} active", tone = Tone.Danger)
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
                        "Twelve checks run every 15 s against your baseline: forced 2G/3G downgrades, a tower in the " +
                            "wrong area, area changes while you stand still, signal above the area's record, unknown " +
                            "cells in familiar places, towers that appear and vanish, rapid switching, no neighbours, " +
                            "a transmitter within 78 m, signal spikes and technology flip-flopping.",
                        title = "No indicators right now"
                    )
                }
            }
            if (cell.score > 0) {
                item(key = "§meaning") { WhatThisMeans(cell.score) }
            }
            item(key = "§baseline") {
                ClairixCard(spacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Baseline", style = MaterialTheme.typography.titleSmall, color = palette.text, modifier = Modifier.weight(1f))
                        StatusPill(if (cell.mature) "Ready" else "Learning", if (cell.mature) Tone.Safe else Tone.Caution)
                    }
                    ClairixProgressBar(cell.maturity, tone = if (cell.mature) Tone.Safe else Tone.Caution)
                    Text(
                        if (cell.mature) "${plural(cell.knownCells, "cell")} · ${plural(cell.visits, "visit")}. Clairix knows your usual towers."
                        else "${cell.visits} of 10 visits. Keep scanning where you usually are; a visit is a return to an " +
                            "area after time spent somewhere else.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSubtle
                    )
                }
            }
        } else if (cell.available) {
            item(key = "§not-analysing") {
                Notice(
                    Tone.Caution,
                    "Live tower data is below, but fake-tower detection only runs while scanning is on. " +
                        "Start scanning from Trackers.",
                    title = "Not checked yet"
                )
            }
        }

        if (!cell.available && radio != null && (radio.operatorName != null || radio.simOperatorName != null)) {
            item(key = "§unavailable") {
                ClairixCard(spacing = 4.dp) {
                    KeyValueRow("Registered on", radio.operatorName ?: "Unknown", mono = false)
                    KeyValueRow("SIM", radio.simOperatorName ?: "Unknown", mono = false)
                    radio.simState?.let { KeyValueRow("SIM state", it, mono = false) }
                }
            }
        }

        if (c != null) {
            item(key = "§details-hdr") { SectionHeader("Serving tower") }
            // ── Operator & radio ──────────────────────────────────────────────
            item(key = "§operator") {
                ClairixCard(spacing = 4.dp) {
                    val decoded = Plmn.describe(c.mcc, c.mnc)
                    val operator = radio?.operatorName ?: c.plmnCarrier ?: "Unknown operator"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(operator, style = MaterialTheme.typography.titleLarge, color = palette.text, modifier = Modifier.weight(1f))
                        if (radio?.roaming == true) StatusPill("Roaming", Tone.Caution)
                    }
                    KeyValueRow("Network type", radio?.dataNetworkType ?: c.rat.label)
                    KeyValueRow("Radio", c.rat.label)
                    KeyValueRow("PLMN (MCC-MNC)", "${c.mcc ?: "?"}-${c.mnc ?: "?"}" + (decoded?.let { "  $it" } ?: ""))
                    if (radio != null && (radio.simOperatorName != null || radio.simPlmn != null)) {
                        val simLine = listOfNotNull(radio.simOperatorName, radio.simPlmn).joinToString(" · ")
                        KeyValueRow("SIM carrier", simLine)
                    }
                    if (radio != null) {
                        KeyValueRow("Mobile data", if (radio.dataConnected) "Connected" else "Not connected")
                        if (radio.simState != null && radio.simState != "Ready") KeyValueRow("SIM state", radio.simState)
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
                            "Not roaming, but the tower's network decodes to $decodedCarrier while the SIM is $simName. " +
                                "Usually a partner or merged network; a fake tower posing as your carrier can look like this too.",
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
                            Text(c.signalDbm?.let { fmtDbm(it) } ?: "— dBm", style = MaterialTheme.typography.monoLarge, color = palette.text)
                            Text(signalWord(bars, c.rat), style = MaterialTheme.typography.bodyMedium, color = palette.textSubtle)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (c.rsrq != null) Text("RSRQ ${c.rsrq} dB", style = MaterialTheme.typography.monoSmall, color = palette.textMuted)
                            if (c.sinr != null) Text("SINR ${c.sinr} dB", style = MaterialTheme.typography.monoSmall, color = palette.textMuted)
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
                    KeyValueRow("Cell ID", c.cellId)
                    if (c.rat == Rat.LTE) {
                        c.cellId.toLongOrNull()?.let { ci ->
                            KeyValueRow("eNodeB / sector", "${ci shr 8} / ${ci and 0xFF}")
                        }
                    }
                    if (c.rat == Rat.NR5G) {
                        c.cellId.toLongOrNull()?.let { nci ->
                            // gNB id length varies (22–32 bits); the common 24-bit split is shown.
                            KeyValueRow("gNodeB / cell (24-bit split)", "${nci shr 12} / ${nci and 0xFFF}")
                        }
                    }
                    KeyValueRow(if (c.rat.rank >= Rat.LTE.rank) "Tracking area (TAC)" else "Location area (LAC)", c.tac ?: "Not reported")
                    c.pci?.let {
                        KeyValueRow(
                            when (c.rat) { Rat.GSM -> "BSIC"; Rat.UMTS -> "Scrambling code"; else -> "Physical cell ID" },
                            it.toString()
                        )
                    }
                    KeyValueRow("Band", c.band ?: "Not reported")
                    c.arfcn?.let {
                        KeyValueRow(
                            when (c.rat) { Rat.LTE -> "EARFCN"; Rat.NR5G -> "NR-ARFCN"; Rat.UMTS -> "UARFCN"; else -> "ARFCN" },
                            it.toString()
                        )
                    }
                    c.bandwidthKhz?.let { KeyValueRow("Bandwidth", "${it / 1000} MHz") }
                    KeyValueRow("Neighbours heard", "${c.neighbors ?: "Not reported"}")
                    KeyValueRow("Read", "${fmtClock(c.ts)} · ${fmtAgo(now, c.ts)}")
                }
            }

            // ── Neighbours ────────────────────────────────────────────────────
            if (cell.neighbors.isNotEmpty()) {
                item(key = "§neighbours-hdr") { SectionHeader("Neighbouring cells", supporting = "${cell.neighbors.size} heard") }
                item(key = "§neighbours") {
                    ClairixCard(spacing = 8.dp) {
                        Text(
                            "What else the modem can hear. A real tower always has company; a lone transmitter does not.",
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
                            Text("+${cell.neighbors.size - 12} more", style = MaterialTheme.typography.bodySmall, color = palette.textSubtle)
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
    val (eyebrow, headline, body, tone) = when {
        !cell.available -> CellCopy(
            "Cell towers", "No reading",
            (cell.reason ?: "Reading the modem…").trimEnd('.') + ". Tower details appear here once the modem reports a serving cell.",
            Tone.Neutral
        )
        !cell.analysing -> CellCopy("Cell towers", "Not checked", "Tower details are live below. Start scanning to check them for fakes.", Tone.Neutral)
        cell.level == Threat.CRITICAL -> CellCopy(
            "Cell towers · analysing", "Fake tower likely",
            "Several signs point to a fake cell tower nearby. Avoid calls and SMS here; use Clairix calls over Wi-Fi.",
            Tone.Danger
        )
        cell.level == Threat.HIGH -> CellCopy(
            "Cell towers · analysing", "Sketchy tower",
            "This tower doesn't match your baseline. Avoid sensitive calls and SMS until you've moved on.",
            Tone.Danger
        )
        cell.level == Threat.MEDIUM -> CellCopy(
            "Cell towers · analysing", "Worth a look",
            "Some indicators fired. Not conclusive on its own; watch whether more appear.",
            Tone.Caution
        )
        !cell.mature -> CellCopy(
            "Cell towers · learning", "Learning",
            "Clairix is still learning your usual towers. Nothing suspicious so far.",
            Tone.Neutral
        )
        else -> CellCopy(
            "Cell towers · analysing", "Towers clear",
            "Your towers match the baseline. Nothing points to a fake tower.",
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = eyebrow,
        headline = headline,
        body = body + if (cell.available && cell.analysing) " Score ${cell.score} / 100." else "",
        tone = tone,
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

private fun signalWord(bars: Int, rat: Rat): String = when (bars) {
    4 -> "Excellent"
    3 -> "Good"
    2 -> "Fair"
    1 -> "Poor"
    else -> "No signal"
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
    val c = ClairixTheme.colors
    val on = if (tone == Tone.Neutral) c.textMuted else tone.color()
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = "$bars of 4 bars" }
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
            "What set it off",
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
                "On ${cc.rat.label} cell ${cc.cellId}" + (cc.band?.let { " · $it" } ?: "") +
                    (cc.signalDbm?.let { " · ${fmtDbm(it)}" } ?: ""),
                style = MaterialTheme.typography.monoSmall,
                color = c.textMuted
            )
        }
        Text(
            when (cell.level) {
                Threat.CRITICAL, Threat.HIGH -> "Avoid calls and SMS here. Use end-to-end encrypted messaging over Wi-Fi with a VPN, or airplane mode. Tap an indicator below for details."
                else -> "Not conclusive on its own. Watch whether more indicators appear; tap one below for what it means."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.text
        )
    }
}

@Composable
private fun WhatThisMeans(score: Int) {
    val text = when {
        score < 30 -> "A small change from your baseline. Usually normal network wobble."
        score < 50 -> "A moderate change from your baseline. Some risk of a spoofed tower; consider moving."
        score < 80 -> "A big change from your baseline. A fake tower may be nearby; avoid sensitive calls."
        else -> "Strong signs of a fake tower. Turn off mobile data and use Wi-Fi with a VPN."
    }
    val tone = when {
        score < 30 -> Tone.Neutral
        score < 50 -> Tone.Caution
        else -> Tone.Danger
    }
    Notice(tone, text, title = "What this means")
}

@Composable
private fun FindingRow(f: CatcherFinding, onShowExplainer: (() -> Unit)? = null) {
    val c = ClairixTheme.colors
    ClairixCard(
        tone = f.severity.tone(),
        onClick = onShowExplainer,
        onClickLabel = "Explain this indicator",
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
            Text("Tap for what it means and what to do", style = MaterialTheme.typography.labelMedium, color = c.accent)
        }
    }
}

// ── NFC screen ─────────────────────────────────────────────────────────

/**
 * DESFire and MIFARE Plus are ISO 14443-4 and so technically emulable, but they
 * authenticate with a challenge the phone cannot answer; only the static exchanges
 * a reader happens not to challenge can be replayed. Plain ISO 14443-4 is the case
 * HCE actually covers.
 */
private val CardProfile.hceLimited: Boolean
    get() = this == CardProfile.MIFARE_DESFIRE || this == CardProfile.MIFARE_PLUS

private const val HCE_LIMITED_CAVEAT = "Static replay only — readers that challenge the card will reject it"

@Composable
internal fun NfcScreen(
    onAddToVault: (NfcTag, String, Boolean) -> Unit,
    onRemoveVault: (String) -> Unit,
    onEmulate: (VaultCard?) -> Unit,
    onUnlockVault: () -> Unit,
    onLockVault: () -> Unit,
    onEraseVault: () -> Unit
) {
    val tags       by Registry.nfc.collectAsStateWithLifecycle()
    val vaultState by Registry.vault.collectAsStateWithLifecycle()
    val armed      by Registry.emulating.collectAsStateWithLifecycle()
    val replace    by Registry.replacePrompt.collectAsStateWithLifecycle()
    val emulId = armed?.id
    // Cards exist here only while the vault is unlocked.
    val vault = (vaultState as? VaultState.Unlocked)?.cards.orEmpty()
    val vaultOpen = vaultState is VaultState.Unlocked
    var confirmErase by remember { mutableStateOf(false) }
    val c = ClairixTheme.colors

    // The vault refused to overwrite a stored card with this UID; ask.
    val pendingReplace = replace
    if (pendingReplace != null) {
        ClairixDialog(
            title = "Replace the stored card?",
            text = "\"${pendingReplace.existing.label}\" is already in the vault with UID " +
                "${pendingReplace.existing.uid}. Replace it with this scan, saved as " +
                "\"${pendingReplace.label}\"? The stored copy is overwritten.",
            confirmLabel = "Replace",
            confirmTone = Tone.Caution,
            onConfirm = { onAddToVault(pendingReplace.tag, pendingReplace.label, true) },
            dismissLabel = "Keep existing",
            onDismiss = { Registry.setReplacePrompt(null) }
        )
    }

    if (confirmErase) {
        ClairixDialog(
            title = "Erase the vault?",
            text = "The stored cards can't be read, and erasing deletes them and the vault key for good. " +
                "Cards you still have can be scanned and saved again.",
            confirmLabel = "Erase vault",
            confirmTone = Tone.Danger,
            onConfirm = { confirmErase = false; onEraseVault() },
            onDismiss = { confirmErase = false }
        )
    }

    val suspicious = tags.count { it.suspicious }

    ScreenList {
        // ── Status first ──
        item(key = "hero") {
            StatusHero(
                eyebrow = "NFC",
                headline = when {
                    suspicious > 0 -> if (suspicious == 1) "Suspicious tag" else "$suspicious suspicious tags"
                    tags.isNotEmpty() -> "Tags clean"
                    else -> "Tap to check"
                },
                body = when {
                    suspicious > 0 -> "A tag below doesn't behave like an ordinary card or sticker. See what was flagged."
                    tags.isNotEmpty() -> "${plural(tags.size, "tag")} read this session. None look like a skimmer or tracker tag."
                    else -> "Hold the back of the phone to desk undersides, bag linings, door frames and car seats."
                },
                tone = when {
                    suspicious > 0 -> Tone.Danger
                    tags.isNotEmpty() -> Tone.Safe
                    else -> Tone.Neutral
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Active emulation banner
        val armedCard = armed
        if (armedCard != null) {
            item(key = "emul_banner") {
                Notice(
                    Tone.Info,
                    "${armedCard.label}. Tag reading is paused while emulating. Unlock the phone and hold its back " +
                        "to the reader; Clairix can stay open or sit in the background.",
                    title = "Emulating a card",
                    action = { GhostButton("Stop emulating", onClick = { onEmulate(null) }, tone = Tone.Danger) }
                )
            }
        }

        // ── Findings: what was read ──
        item(key = "divider") {
            SectionHeader(
                "Scanned tags",
                action = if (tags.isNotEmpty()) {
                    { GhostButton("Clear", onClick = { Registry.clearNfc() }, tone = Tone.Neutral) }
                } else null
            )
        }
        if (tags.isEmpty()) {
            item(key = "scan_empty") {
                EmptyState(
                    title = "No tags read yet",
                    body = "Any NFC tag or card the phone touches shows up here with what it is.",
                    icon = ClairixIcons.Nfc
                )
            }
        } else {
            items(tags, key = { "t_${it.uid}" }) { tag ->
                NfcTagRow(
                    t = tag,
                    inVault = vault.any { it.uid == tag.uid },
                    vaultOpen = vaultOpen,
                    onAddToVault = { label -> onAddToVault(tag, label, false) },
                    onUnlockVault = onUnlockVault
                )
            }
        }

        // ── The vault ──
        item(key = "vault_hdr") {
            SectionHeader(
                "Card vault",
                supporting = if (vaultState is VaultState.Unlocked) null else "Hardware-encrypted",
                action = if (vaultState is VaultState.Unlocked) {
                    { GhostButton("Lock", onClick = onLockVault, tone = Tone.Neutral) }
                } else null
            )
        }
        when (val state = vaultState) {
            VaultState.Locked, VaultState.Unlocking -> item(key = "vault_locked") {
                ClairixCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent)
                        Text("Vault locked", style = MaterialTheme.typography.titleMedium, color = c.text)
                    }
                    Text(
                        "Stored cards are encrypted with a hardware key that only works after you confirm it's you " +
                            "with your fingerprint, face or screen-lock PIN. AES-256-GCM in the Android Keystore.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    PrimaryButton(
                        if (state == VaultState.Unlocking) "Waiting for you to confirm…" else "Unlock vault",
                        onClick = onUnlockVault,
                        enabled = state == VaultState.Locked,
                        loading = state == VaultState.Unlocking,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            is VaultState.Failed -> item(key = "vault_failed") {
                Notice(
                    Tone.Danger,
                    state.message,
                    title = "Vault unavailable",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.retryable) GhostButton("Try again", onClick = onUnlockVault)
                            if (state.erasable) GhostButton("Erase vault", onClick = { confirmErase = true }, tone = Tone.Danger)
                        }
                    }
                )
            }
            is VaultState.Unlocked -> if (state.cards.isEmpty()) {
                item(key = "vault_empty") {
                    EmptyState(
                        title = "No cards stored",
                        body = "Scan a card and tap Save to vault to keep it here.",
                        icon = Icons.Filled.Lock
                    )
                }
            } else {
                items(state.cards, key = { "v_${it.id}" }) { card ->
                    VaultCardRow(card = card, emulId = emulId, onEmulate = onEmulate, onRemove = { onRemoveVault(card.id) })
                }
            }
        }
    }
}

@Composable
private fun VaultCardRow(
    card: VaultCard,
    emulId: String?,
    onEmulate: (VaultCard?) -> Unit,
    onRemove: () -> Unit
) {
    val isEmulating = card.id == emulId
    val c = ClairixTheme.colors
    ClairixCard(tone = if (isEmulating) Tone.Info else Tone.Safe, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                card.label, style = MaterialTheme.typography.titleMedium, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            when {
                isEmulating -> StatusPill("Active", Tone.Info)
                card.profile.hceLimited -> StatusPill("Replay only", Tone.Caution)
                card.profile.hceCapable -> StatusPill("Can emulate", Tone.Safe)
                else -> StatusPill("Stored", Tone.Neutral)
            }
        }
        Text("${card.profile.label} · UID ${card.uid}", style = MaterialTheme.typography.monoSmall, color = c.textMuted)
        if (!card.profile.hceCapable) {
            Text(
                "This card type uses its own radio protocol, so the phone can't stand in for it. The UID and type are kept for identification.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSubtle
            )
        } else if (card.profile.hceLimited) {
            Text(HCE_LIMITED_CAVEAT, style = MaterialTheme.typography.bodySmall, color = c.caution)
        }
        Text("Saved ${fmtTime(card.addedTs)}", style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.profile.hceCapable) {
                SecondaryButton(
                    if (isEmulating) "Stop" else "Emulate",
                    onClick = { onEmulate(if (isEmulating) null else card) },
                    tone = if (isEmulating) Tone.Danger else Tone.Info,
                    modifier = Modifier.weight(1f)
                )
            }
            GhostButton("Remove", onClick = onRemove, tone = Tone.Neutral)
        }
    }
}

@Composable
private fun NfcTagRow(
    t: NfcTag,
    inVault: Boolean,
    vaultOpen: Boolean,
    onAddToVault: (String) -> Unit,
    onUnlockVault: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(t.uid) { mutableStateOf(false) }
    var addingLabel by remember(t.uid) { mutableStateOf(false) }
    var labelText by remember(t.uid) { mutableStateOf("") }
    LaunchedEffect(copied) { if (copied) { delay(1500L); copied = false } }
    val c = ClairixTheme.colors

    val isPayment = t.profile in setOf(CardProfile.EMV_VISA, CardProfile.EMV_MASTERCARD,
        CardProfile.EMV_AMEX, CardProfile.EMV_OTHER)
    val tone = when {
        t.suspicious -> Tone.Danger
        isPayment    -> Tone.Caution
        else         -> Tone.Safe
    }

    ClairixCard(tone = tone, emphasis = t.suspicious) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.uid, style = MaterialTheme.typography.mono, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            StatusPill(
                when {
                    t.suspicious -> "Suspect"
                    isPayment    -> "Payment"
                    else         -> "Clean"
                },
                tone
            )
        }

        // Profile + ATQA/SAK
        val techLine = buildString {
            append(t.type)
            if (t.atqa != null && t.sak != null) append(" · ATQA ${t.atqa} SAK ${t.sak}")
            if (t.paymentNetwork != null) append(" · ${t.paymentNetwork}")
        }
        Text(techLine, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(t.note, style = MaterialTheme.typography.bodyMedium, color = if (t.suspicious) c.danger else c.textSubtle)

        // Skimmer flags
        t.skimmerFlags.forEach { flag -> WarningLine(flag, Tone.Danger) }
        if (isPayment) {
            Text(
                "Public card details only. Nothing secret can be read, and it isn't a tracker.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )
        }
        if (t.payload != null) {
            Text("Data: ${t.payload.take(80)}", style = MaterialTheme.typography.monoSmall, color = c.textMuted)
        }

        // HCE capability hint
        if (t.profile.hceLimited && !isPayment) {
            Text("Replay only: $HCE_LIMITED_CAVEAT. Save it to the vault to try.", style = MaterialTheme.typography.bodySmall, color = c.caution)
        } else if (t.profile.hceCapable && !isPayment) {
            Text("Your phone can stand in for this card. Save it to the vault to use it.", style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
        }

        // Add-to-vault inline input
        if (addingLabel) {
            ClairixTextField(
                value = labelText,
                onValueChange = { labelText = it },
                label = "Card label",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Save",
                    onClick = {
                        if (labelText.isNotBlank()) {
                            onAddToVault(labelText.trim())
                            addingLabel = false; labelText = ""
                        }
                    },
                    enabled = labelText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Cancel", onClick = { addingLabel = false; labelText = "" }, tone = Tone.Neutral)
            }
        }

        Text(fmtTime(t.ts), style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Whether the tag is already stored is only known while the vault
            // is open, so a locked vault offers the unlock, not the save: with
            // the key's auth window still warm, a save from here used to go
            // through without a prompt and replace the stored card unseen.
            if (!vaultOpen && !isPayment) {
                GhostButton("Unlock to save", onClick = onUnlockVault)
            } else if (!inVault && !isPayment && !addingLabel) {
                GhostButton("Save to vault", onClick = { addingLabel = true; labelText = t.profile.label })
            } else if (inVault) {
                StatusPill("In vault", Tone.Safe)
            }
            Spacer(Modifier.weight(1f))
            GhostButton(
                if (copied) "Copied" else "Copy UID",
                onClick = { clipboard.setText(AnnotatedString(t.uid)); copied = true },
                tone = if (copied) Tone.Safe else Tone.Neutral
            )
        }
    }
}

// ── Device health screen ────────────────────────────────────────────────

@Composable
internal fun DeviceScreen(onShown: () -> Unit) {
    val health by Registry.phoneHealth.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    // Live microphone and camera monitoring only runs inside the scanner service;
    // the static findings are re-checked by the Activity whenever this tab appears,
    // and every 30 s while it stays up so battery, USB and VPN rows stay current.
    val liveMonitoring = status.scanning
    LaunchedEffect(Unit) {
        while (true) {
            onShown()
            delay(30_000L)
        }
    }
    val problems = health.problems
    val checked = health.facts.size
    val issues = health.findings.size + problems.size
    val c = ClairixTheme.colors

    ScreenList {
        // ── Status first ───────────────────────────────────────────────────
        item(key = "hero") { PhoneVerdict(health, checked, issues) }

        item(key = "figures") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("$checked", "Checks run", Modifier.weight(1f))
                StatTile(
                    "$issues", if (issues == 1) "Issue" else "Issues", Modifier.weight(1f),
                    tone = when {
                        issues == 0 -> Tone.Safe
                        health.level.ordinal >= Threat.HIGH.ordinal -> Tone.Danger
                        else -> Tone.Caution
                    }
                )
                StatTile(
                    if (liveMonitoring) "On" else "Off", "Mic and camera watch", Modifier.weight(1f),
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
                    (if (health.activeRecordings == 1) "An app is recording audio right now. "
                    else "Apps are recording audio right now (${health.activeRecordings} recordings). ") + SENSOR_HINT,
                    title = "Microphone in use"
                )
            }
        }
        if (health.cameraInUse) {
            item(key = "cam_banner") {
                Notice(Tone.Caution, "Another app is using the camera right now. $SENSOR_HINT", title = "Camera in use")
            }
        }
        if (!health.sensorActive && !liveMonitoring) {
            item(key = "sensors_off") {
                Text(
                    "Microphone and camera use is only watched while scanning is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSubtle,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // ── Findings: things installed or granted that need acting on ────────
        if (health.findings.isNotEmpty()) {
            item(key = "findings_head") {
                SectionHeader("Apps with spy-level access", supporting = "${health.findings.size}", tone = Tone.Danger)
            }
            items(health.findings, key = { it.id }) { f -> HealthFindingRow(f) }
        }

        // ── Checklist ───────────────────────────────────────────────────────
        if (health.facts.isNotEmpty()) {
            val groups = health.facts.groupBy { it.category }
            val order = listOf("Integrity", "Access", "Software", "Network", "Hardware")
            for (cat in order + (groups.keys - order.toSet())) {
                val rows = groups[cat] ?: continue
                item(key = "cat_$cat") {
                    val flagged = rows.count { it.severity != null && it.severity.ordinal >= Severity.MEDIUM.ordinal }
                    SectionHeader(cat, supporting = if (flagged > 0) "$flagged to check" else "All passed")
                }
                item(key = "facts_$cat") {
                    ListGroup {
                        rows.forEachIndexed { i, f ->
                            if (i > 0) Hairline()
                            HealthFactRow(f)
                        }
                    }
                }
            }
        }
    }
}

/** Where Android shows which app holds the microphone or camera. */
private const val SENSOR_HINT =
    "Android doesn't tell Clairix which app. Tap the green privacy dot in the status bar, or open " +
        "Settings → Security & privacy → Privacy dashboard."

@Composable
private fun PhoneVerdict(health: PhoneHealth, checked: Int, issues: Int) {
    val sensor = when {
        health.activeRecordings > 0 && health.cameraInUse -> "Mic and camera on"
        health.activeRecordings > 0 -> "Mic in use"
        health.cameraInUse -> "Camera in use"
        else -> null
    }
    val (headline, body, tone) = when {
        health.findingsScannedTs == 0L -> Triple(
            "Checking",
            "Root, bootloader, verified boot, SELinux, encryption, debug access, accessibility services, " +
                "device admins and known spyware.",
            Tone.Neutral
        )
        issues > 0 -> Triple(
            if (issues == 1) "1 to fix" else "$issues to fix",
            (health.findings.map { it.title } + health.problems.map { it.label })
                .take(3).joinToString(" · ") + if (issues > 3) " · and more" else "",
            if (health.level.ordinal >= Threat.HIGH.ordinal) Tone.Danger else Tone.Caution
        )
        sensor != null -> Triple(
            sensor,
            "No spyware or risky settings found in $checked checks. Something is using a sensor right now; see below.",
            Tone.Caution
        )
        else -> Triple(
            "Phone clear",
            "$checked checks passed. No root, debug access, unknown admins or known spyware. " +
                "Checked ${fmtClock(health.findingsScannedTs)}.",
            Tone.Safe
        )
    }
    StatusHero(
        eyebrow = "Phone check",
        headline = headline,
        body = body,
        tone = tone,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun HealthFactRow(f: HealthFact) {
    val c = ClairixTheme.colors
    // Purely informational rows (a version number, a battery level) get a neutral
    // mark; a check that passed gets safe; a problem gets its severity's tone.
    val informational = f.severity == null && INFORMATIONAL_FACTS.contains(f.id)
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
}

/** Checklist rows that describe rather than judge, so a safe mark would overclaim. */
private val INFORMATIONAL_FACTS = setOf(
    "integrity_api", "android", "model", "build", "kernel", "cpu", "battery", "vpn", "pdns", "vpn_always"
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

private fun wifiReason(reason: String): String = when (reason) {
    "known_catcher_ssid" -> "Default SSID of known interception equipment"
    "carrier_open_network" -> "Carrier name on an unencrypted network — likely bait"
    "open_twin_of_secured" -> "Open copy of a network that is encrypted nearby — evil twin"
    "open_unsecured" -> "Open, unsecured network — traffic can be intercepted"
    else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
}

@Composable
internal fun WifiScreen(onShown: () -> Unit = {}) {
    val anomalies by Registry.wifi.collectAsStateWithLifecycle()
    val wifi by Registry.wifiStatus.collectAsStateWithLifecycle()
    val now = rememberNow(5_000L)
    val sorted = remember(anomalies) {
        anomalies.sortedWith(compareByDescending<WifiAnomaly> { it.threat.ordinal }.thenByDescending { it.ts })
    }

    // Android allows four scan requests per two minutes in the foreground; every
    // 30 s uses that budget exactly while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            onShown()
            delay(30_000L)
        }
    }

    val conn = wifi.connection
    val flagged = wifi.nearby.count { it.flags.isNotEmpty() }
    val open = wifi.nearby.count { !it.security.secure }
    val high = sorted.count { it.threat.ordinal >= Threat.HIGH.ordinal }
    val c = ClairixTheme.colors

    ScreenList {
        // ── Status first ──
        item(key = "§hero") {
            StatusHero(
                eyebrow = "Wi-Fi",
                headline = when {
                    high > 0 -> if (high == 1) "Bait network" else "$high bait networks"
                    sorted.isNotEmpty() -> "Worth a look"
                    conn != null && !conn.security.secure -> "Open network"
                    wifi.scannedTs > 0 -> "Wi-Fi clean"
                    else -> "Checking"
                },
                body = when {
                    high > 0 -> "Networks below look built to catch your traffic. Don't join them."
                    sorted.isNotEmpty() -> "${plural(sorted.size, "network")} nearby look off. Nothing you're joined to is flagged."
                    conn != null && !conn.security.secure -> "You're on an unencrypted network. Anyone nearby can read what isn't end-to-end encrypted."
                    wifi.scannedTs > 0 -> "${plural(wifi.nearby.size, "network")} in range, none flagged. Checked ${fmtAgo(now, wifi.scannedTs)}."
                    else -> "Reading what's in range. Results fill in within a few seconds."
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
                    "Suspicious networks",
                    supporting = "${sorted.size}" + if (high > 0) " · $high high" else "",
                    tone = if (high > 0) Tone.Danger else Tone.Caution
                )
            }
            itemsIndexed(sorted, key = { i, a -> "anomaly|${a.bssid}|${a.reason}|$i" }) { _, a -> WifiRow(a) }
        }

        // ── Connected network ─────────────────────────────────────────────────
        item(key = "§conn-head") { SectionHeader("Your connection") }
        if (!wifi.available) {
            item(key = "§unavailable") {
                EmptyState(title = wifi.reason ?: "Reading the Wi-Fi radio…", body = "Connection details appear here once Wi-Fi reports in.", icon = ClairixIcons.Wifi)
            }
        } else if (conn == null) {
            item(key = "§disconnected") {
                EmptyState(title = "Not connected", body = "Wi-Fi is on, but the phone hasn't joined a network.", icon = ClairixIcons.Wifi)
            }
        } else {
            item(key = "§connected") {
                ClairixCard(spacing = 4.dp, tone = if (conn.security.secure) null else Tone.Danger) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SignalBars(conn.level, tone = signalTone(conn.level))
                        Column(Modifier.weight(1f)) {
                            Text(
                                conn.ssid ?: "Name hidden (is location off?)",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(conn.bssid ?: "BSSID unavailable", style = MaterialTheme.typography.monoSmall, color = c.textSubtle)
                        }
                        StatusPill(conn.security.label, if (conn.security.secure) Tone.Safe else Tone.Danger)
                    }
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow("Signal", "${fmtDbm(conn.rssi)} · ${signalWord(conn.level, Rat.UNKNOWN).substringBefore(" (")}")
                    KeyValueRow("Frequency", buildString {
                        append(conn.band)
                        if (conn.frequencyMhz > 0) append(" · ${conn.frequencyMhz} MHz")
                        conn.channel?.let { append(" · ch $it") }
                    })
                    conn.standard?.let { KeyValueRow("Standard", it) }
                    KeyValueRow("Security", conn.security.label, valueColor = if (conn.security.secure) null else c.danger)
                    val speed = listOfNotNull(
                        conn.txMbps?.let { "↑$it" }, conn.rxMbps?.let { "↓$it" }
                    ).joinToString(" ").ifBlank { conn.linkSpeedMbps?.toString() ?: "" }
                    if (speed.isNotBlank()) KeyValueRow("Link speed", "$speed Mbps")
                    Hairline(Modifier.padding(vertical = 4.dp))
                    KeyValueRow("IPv4", conn.ipv4 ?: "None")
                    conn.ipv6?.let { KeyValueRow("IPv6", it) }
                    KeyValueRow("Gateway", conn.gateway ?: "Unknown")
                    KeyValueRow("DNS", if (conn.dnsServers.isEmpty()) "None" else conn.dnsServers.joinToString("\n"))
                    KeyValueRow("Private DNS", conn.privateDns ?: "Off", valueColor = if (conn.privateDns != null) c.safe else null)
                    KeyValueRow("VPN", if (conn.vpnActive) "Active" else "None", valueColor = if (conn.vpnActive) c.safe else null)
                    if (conn.metered) KeyValueRow("Metered", "Yes")
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
                    "In range",
                    supporting = buildString {
                        append("${wifi.nearby.size}")
                        if (open > 0) append(" · $open open")
                        if (flagged > 0) append(" · $flagged flagged")
                    }
                )
            }
            if (wifi.nearby.isEmpty()) {
                item(key = "§none-nearby") {
                    Text(
                        "No scan results yet. Android hands out cached results first; a fresh scan has been asked for and the list fills in within a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                }
            } else {
                item(key = "§nearby") {
                    ListGroup {
                        wifi.nearby.forEachIndexed { i, n ->
                            if (i > 0) Hairline()
                            NearbyWifiRow(n)
                        }
                    }
                }
            }
        }
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
                    if (n.hidden) "Hidden network" else n.ssid,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (n.hidden) c.textSubtle else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (n.connected) StatusPill("Connected", Tone.Safe)
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
                a.ssid.ifBlank { "Hidden network" }, style = MaterialTheme.typography.titleMedium, color = c.text,
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

private fun bleExplainerContent(d: Detection): ExplainerContent {
    val tone = if (d.threat.ordinal >= Threat.HIGH.ordinal) Tone.Danger else Tone.Caution
    return when {
        d.following -> ExplainerContent(
            headline = "Tracker on you: ${d.name}",
            whatIsThis = "A Bluetooth tracker has been verified as following your movements. " +
                "It appeared at multiple locations ${d.displacementM.roundToInt()} metres apart while you were " +
                "moving, which cannot be explained by proximity alone. ${d.tracker?.let { "This matches a known ${it.brand} ${it.label}." } ?: "No commercial tracker signature matched, but the movement pattern is conclusive."}",
            whatToDo = "Stop and search the vehicle or your belongings now. Check: wheel wells, bumper covers and cavities, " +
                "OBD-II diagnostic port (under dashboard), under seats and floor mats, inside bag linings and seams. " +
                "If found, do not discard it on the roadside — it is evidence. Photograph it in place before removing it. " +
                "Consider filing a police report and contacting legal counsel.",
            howCertain = "High confidence. Confirmation requires the device to appear at locations " +
                "${d.displacementM.roundToInt()} m apart during your movement. " +
                "Threat score: ${d.score}/100 across ${d.sightings} sightings.",
            tone = tone
        )
        d.persistent && d.identified -> ExplainerContent(
            headline = "Known tracker nearby: ${d.name}",
            whatIsThis = "A ${d.tracker?.brand ?: "commercial"} ${d.tracker?.label ?: "tracker"} has been " +
                "present for an extended period. Known trackers are purpose-built surveillance devices. " +
                "Extended presence without confirmed movement suggests it may be attached to your vehicle or belongings.",
            whatToDo = "Monitor this device. If it remains present as you travel, it will be promoted to " +
                "following automatically. If you believe it does not belong to you, physically search " +
                "your vehicle and belongings. Pay attention to whether the signal strength (RSSI) stays consistent, " +
                "which would indicate it is on or very near your person.",
            howCertain = "Medium-to-high confidence. Device is identified as a commercial tracker with " +
                "${d.sightings} sightings over an extended period. Following confirmation requires GPS movement. " +
                "Threat score: ${d.score}/100.",
            tone = tone
        )
        d.persistent -> ExplainerContent(
            headline = "Unknown device keeps showing up: ${d.name}",
            whatIsThis = "An unidentified Bluetooth device has been present continuously for an extended period. " +
                "This could be a benign device (a neighbour's router, a fixed sensor) or a tracker that does not " +
                "match the known signature database. Its persistence pattern is unusual for a passing device.",
            whatToDo = "Watch for this device across different locations. If it follows you as you travel, " +
                "it will be flagged as following. If it is stationary and you remain in one place, it is " +
                "likely part of the surroundings. Mark it as yours below if you know what it is.",
            howCertain = "Low-to-medium confidence. No known tracker signature matched — flagged on " +
                "behaviour alone. Threat score: ${d.score}/100 over ${d.sightings} sightings.",
            tone = tone
        )
        else -> ExplainerContent(
            headline = "${levelLabel(d.threat.name)} risk: ${d.name}",
            whatIsThis = "This device has characteristics associated with surveillance trackers. " +
                "${d.tracker?.let { "It matches the signature of a ${it.brand} ${it.label}." } ?: "No exact tracker signature matched, but its signal pattern warrants monitoring."}",
            whatToDo = "Continue monitoring. The scan needs more time and movement to determine whether " +
                "this device is following you. Keep the scan running as you travel.",
            howCertain = "Building confidence. Score ${d.score}/100 · ${d.sightings} sightings. " +
                "GPS confirmation of following requires approximately 300 m of shared movement.",
            tone = tone
        )
    }
}

private fun cellExplainerContent(f: CatcherFinding): ExplainerContent {
    val tone = f.severity.tone()
    // Findings that can fire for more than one cell carry it after a colon, so the
    // explainer keys off the heuristic name in front of it.
    val (what, todo, certain) = when (f.id.substringBefore(':')) {
        "rat_downgrade" -> Triple(
            "Your device has been forced from a newer radio technology (4G/5G) down to an older one (2G/3G). " +
            "IMSI catchers do this deliberately because older protocols have weaker encryption and are easier " +
            "to intercept. Legitimate networks only downgrade in areas with no newer coverage.",
            "Avoid making voice calls or sending SMS until you leave the area. Use end-to-end encrypted " +
            "messaging apps over WiFi or data. Do not transmit sensitive information. Move away from the " +
            "area and observe whether the technology level returns to normal.",
            "High confidence indicator. A drop to 2G/3G within half an hour of 4G/5G serving you at the " +
            "same spot, without you having moved, is a primary IMSI catcher signature. Moving resets " +
            "the comparison, so a drive into a basement car park does not count."
        )
        "cellid_tac_mismatch" -> Triple(
            "A cell tower ID that you have seen before is now claiming to be in a different area (tracking " +
            "area code). Real base stations have fixed, permanent area assignments. A portable IMSI catcher " +
            "moving through the area will produce inconsistent area codes.",
            "Monitor for additional indicators. A single mismatch could be a network reconfiguration, but " +
            "combined with other findings it is significant. Avoid sensitive calls in this area.",
            "High confidence indicator when combined with other findings. Alone it may reflect legitimate " +
            "network changes, but it warrants heightened caution."
        )
        "tac_change_stationary" -> Triple(
            "Your device registered a different tracking area while you were not moving. " +
            "Tracking area changes normally happen when you travel across cell boundaries. When you are " +
            "stationary, a change indicates a new transmitter has appeared near you and forced re-registration.",
            "Note your exact location and time. A stationary area change combined with other indicators " +
            "is a strong surveillance signal. Consider moving away from the area.",
            "Medium confidence. Stationary area changes can occur due to legitimate network maintenance, " +
            "but are uncommon and warrant attention alongside other findings."
        )
        "signal_outlier" -> Triple(
            "The cell signal is significantly stronger than the historical maximum for this area. " +
            "A portable transmitter placed near you — in a vehicle, building, or backpack — produces " +
            "a much stronger signal than a distant tower.",
            "Check your surroundings for parked vehicles with unusual equipment, people lingering nearby, " +
            "or recently placed objects. A strong signal alone is insufficient for action, but note the " +
            "location and observe whether it moves with you.",
            "Medium confidence. Signal strength varies for many reasons. This indicator is most meaningful " +
            "when combined with technology downgrade or unknown cell findings."
        )
        "unknown_cell" -> Triple(
            "A cell tower that has never appeared in this area before has become your serving cell. " +
            "You have visited this area at least 20 separate times, and the tower infrastructure here is well-established. " +
            "Portable IMSI catchers appear as unknown cells in familiar areas.",
            "A new cell at a familiar location deserves caution. If you also see technology downgrade or " +
            "signal strength anomalies, treat this as a serious surveillance indicator. Do not make sensitive " +
            "calls. Move away and observe whether the new cell disappears.",
            "Medium confidence. New towers do get installed occasionally, but this detection requires " +
            "at least 20 separate visits to the area (returns more than 30 minutes apart) before triggering."
        )
        "ephemeral_cell", "ephemeral_cell_strict" -> Triple(
            "A cell tower appeared briefly as your serving cell, then vanished. Real base stations " +
            "broadcast continuously — they do not appear for a few minutes and disappear. " +
            "A portable surveillance device driven into and away from an area looks exactly like this.",
            "Note the time and location. An ephemeral cell that appeared and disappeared suggests a " +
            "mobile surveillance asset that has moved on. Review your surroundings at the time of detection.",
            "Medium-to-high confidence. Ephemeral cells have few legitimate explanations. " +
            "A duration under 90 seconds is the strictest threshold."
        )
        "cell_flapping" -> Triple(
            "Your device has switched between multiple different cell towers rapidly while you were " +
            "stationary. IMSI catchers force repeated re-registrations to capture authentication events. " +
            "Normal network handover does not produce this pattern at a single location.",
            "Avoid making calls or sending messages while this is occurring. If it persists, " +
            "move away from the area. The flapping pattern suggests active interference with your " +
            "device's network registration.",
            "Medium confidence. Rapid cell switching can also result from poor coverage areas, " +
            "but is unusual when stationary in a normally covered location."
        )
        "no_neighbors" -> Triple(
            "Your serving cell is reporting zero neighbouring cells. Real base stations always have " +
            "overlapping coverage with adjacent towers — this is fundamental to how cellular networks " +
            "are designed. A portable IMSI catcher operating alone has no neighbours to report.",
            "This is a supporting indicator rather than an action trigger alone. Watch for it " +
            "alongside technology downgrade or unknown cell findings, at which point you should " +
            "follow the high-confidence response guidance.",
            "Low confidence as a standalone indicator. Meaningful when combined with other findings."
        )
        "timing_advance_zero" -> Triple(
            "LTE timing advance of zero means the transmitter is within approximately 78 metres of " +
            "your device. Macro cell towers are never this close to you. A portable IMSI catcher in " +
            "a parked vehicle or nearby building would produce exactly this reading.",
            "A transmitter within 78 metres is immediately actionable. Scan your visual surroundings " +
            "for parked vehicles with rooftop antennas or unusual equipment. Do not make sensitive " +
            "calls. Move away and observe whether the indicator follows you.",
            "High confidence. LTE timing advance is a precise physical measurement — zero means very " +
            "close proximity to the transmitter."
        )
        "signal_spike" -> Triple(
            "The signal strength on your current cell tower jumped suddenly between two consecutive " +
            "readings. A macro cell tower at a fixed location has a stable signal. A mobile transmitter " +
            "moving towards you — in a vehicle, for example — produces exactly this kind of spike.",
            "A sudden signal spike suggests a mobile surveillance asset moving closer to your position. " +
            "Be aware of your surroundings. If the spike is combined with other indicators, follow " +
            "the high-confidence response protocol immediately.",
            "Medium confidence. Signal spikes can result from device movements, building reflections, " +
            "or other environmental factors, but are notable when combined with other findings."
        )
        "rat_oscillation" -> Triple(
            "Your device has switched between multiple radio technologies (2G, 3G, 4G) rapidly in " +
            "a short window. IMSI catchers force devices through technology cycles to capture separate " +
            "authentication events on each technology, revealing the device's IMSI.",
            "Multiple technology switches in a short window strongly indicate active interference. " +
            "Avoid all calls and data use. Use WiFi with a VPN for any communications. " +
            "Move out of the area and observe whether the switching stops.",
            "High confidence. Rapid multi-technology oscillation is a strong signature of active " +
            "IMSI capture operations."
        )
        else -> Triple(
            "An anomaly was detected in your cellular environment that deviates from your established " +
            "baseline. The specific indicator ID is: ${f.id}. See the full detail for more context.",
            "Monitor for additional indicators. If multiple anomalies appear simultaneously, " +
            "treat the situation as a potential IMSI catcher and avoid sensitive communications.",
            "Confidence level: ${f.severity.name.lowercase()}."
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

@Composable
private fun ThreatExplainerSheet(target: ExplainerTarget, onDismiss: () -> Unit) {
    val content = when (target) {
        is ExplainerTarget.BleDevice -> bleExplainerContent(target.detection)
        is ExplainerTarget.CellIndicator -> cellExplainerContent(target.finding)
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
                    Tone.Danger -> "Act now"
                    Tone.Caution -> "Keep watching"
                    else -> "For your information"
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
        ExplainerSection("What to do", content.whatToDo, Tone.Accent)
        ExplainerSection("What this is", content.whatIsThis, Tone.Neutral)
        ExplainerSection("How sure we are", content.howCertain, Tone.Neutral)

        PrimaryButton("Got it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ExplainerSection(heading: String, body: String, tone: Tone) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(heading, tone = tone)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = ClairixTheme.colors.textMuted)
    }
}

