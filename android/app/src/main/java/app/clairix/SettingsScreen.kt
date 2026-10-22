package app.clairix

import android.Manifest
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.Report
import app.clairix.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.clairix.ui.theme.ClairixThemeName
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.selection.selectableGroup
import app.clairix.ui.components.*
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.mono

// ── AppSettings singleton ──────────────────────────────────────────────────────

/**
 * Persisted settings backed by SharedPreferences (key: "prefs").
 * Call [load] once in the activity before accessing any field.
 * ScanService reads [scanMode] to choose the BLE scan power level.
 */
object AppSettings {

    const val PREFS_NAME = "prefs"
    private const val KEY_AGGRESSIVE_SCAN = "aggressive_scan"
    private const val KEY_FOLLOW_THRESHOLD = "follow_threshold_m"
    private const val KEY_PERSISTENCE_THRESHOLD = "persistence_threshold_min"
    private const val KEY_SCAN_ENABLED = "scan_enabled"
    private const val KEY_RESUME_AFTER_REBOOT = "resume_after_reboot"
    private const val KEY_THEME = "theme"

    const val DEFAULT_FOLLOW_THRESHOLD_M = 300f
    const val DEFAULT_PERSISTENCE_THRESHOLD_MIN = 10f
    const val MIN_PERSISTENCE_THRESHOLD_MIN = 5f
    /** Kept below the fastest tracker rotation; see [app.clairix.analysis.MAX_PERSIST_THRESHOLD_MS]. */
    const val MAX_PERSISTENCE_THRESHOLD_MIN = (app.clairix.analysis.MAX_PERSIST_THRESHOLD_MS / 60_000L).toFloat()

    @Volatile private var _aggressiveScan = false
    @Volatile private var _followThresholdM = DEFAULT_FOLLOW_THRESHOLD_M
    @Volatile private var _persistenceThresholdMin = DEFAULT_PERSISTENCE_THRESHOLD_MIN
    @Volatile private var _scanEnabled = false
    @Volatile private var _resumeAfterReboot = false
    private val _theme = MutableStateFlow(ClairixThemeName.ACID)

    /**
     * The colour theme the user picked in Settings > Appearance. Acid, the brand's
     * own colour, until they choose another. Every Activity's content follows it live.
     */
    val theme: StateFlow<ClairixThemeName> = _theme.asStateFlow()

    val aggressiveScan: Boolean get() = _aggressiveScan
    val followThresholdM: Float get() = _followThresholdM
    val persistenceThresholdMin: Float get() = _persistenceThresholdMin

    /** The persistence threshold in the units [app.clairix.analysis.Tracker] works in. */
    val persistenceThresholdMs: Long get() = (_persistenceThresholdMin * 60_000f).toLong()

    /**
     * Whether the user last left scanning switched on. Read at boot so the detector
     * comes back only for someone who actually had it running.
     */
    val scanEnabled: Boolean get() = _scanEnabled

    /**
     * The user's choice to have scanning come back after a reboot. Only takes effect
     * together with ACCESS_BACKGROUND_LOCATION — see [BootReceiver].
     */
    val resumeAfterReboot: Boolean get() = _resumeAfterReboot

    /** BLE scan mode that ScanService should use. */
    val scanMode: Int
        get() = if (_aggressiveScan) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _aggressiveScan = p.getBoolean(KEY_AGGRESSIVE_SCAN, false)
        _followThresholdM = p.getFloat(KEY_FOLLOW_THRESHOLD, DEFAULT_FOLLOW_THRESHOLD_M)
        // A value saved when the slider went to 30 min is brought under the cap.
        _persistenceThresholdMin = p.getFloat(KEY_PERSISTENCE_THRESHOLD, DEFAULT_PERSISTENCE_THRESHOLD_MIN)
            .coerceIn(MIN_PERSISTENCE_THRESHOLD_MIN, MAX_PERSISTENCE_THRESHOLD_MIN)
        _scanEnabled = p.getBoolean(KEY_SCAN_ENABLED, false)
        _resumeAfterReboot = p.getBoolean(KEY_RESUME_AFTER_REBOOT, false)
        // A name from a later version that this one does not know reads as the default.
        val savedTheme = p.getString(KEY_THEME, null)
        _theme.value = ClairixThemeName.entries.firstOrNull { it.name == savedTheme } ?: ClairixThemeName.ACID
    }

    fun setTheme(context: Context, value: ClairixThemeName) {
        _theme.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value.name).apply()
    }

    fun setResumeAfterReboot(context: Context, value: Boolean) {
        _resumeAfterReboot = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESUME_AFTER_REBOOT, value).apply()
    }

    fun setScanEnabled(context: Context, value: Boolean) {
        _scanEnabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCAN_ENABLED, value).apply()
    }

    fun setAggressiveScan(context: Context, value: Boolean) {
        _aggressiveScan = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGGRESSIVE_SCAN, value).apply()
    }

    fun setFollowThreshold(context: Context, value: Float) {
        _followThresholdM = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FOLLOW_THRESHOLD, value).apply()
    }

    fun setPersistenceThreshold(context: Context, value: Float) {
        val capped = value.coerceIn(MIN_PERSISTENCE_THRESHOLD_MIN, MAX_PERSISTENCE_THRESHOLD_MIN)
        _persistenceThresholdMin = capped
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_PERSISTENCE_THRESHOLD, capped).apply()
    }
}

// ── SettingsScreen composable ──────────────────────────────────────────────────

@Composable
fun SettingsScreen(onBack: () -> Unit, onClearData: () -> Unit = {}) {
    val context = LocalContext.current
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()

    var aggressiveScan by remember { mutableStateOf(AppSettings.aggressiveScan) }
    var followThreshold by remember { mutableFloatStateOf(AppSettings.followThresholdM) }
    var persistenceThreshold by remember { mutableFloatStateOf(AppSettings.persistenceThresholdMin) }
    var showClearDialog by remember { mutableStateOf(false) }

    // ── Passcode state ───────────────────────────────────────────────────────
    val lockEnabled by AppLock.enabled.collectAsStateWithLifecycle()
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()
    var hasDuress by remember { mutableStateOf(AppLock.hasDuress(context)) }
    var wipeAfter by remember { mutableIntStateOf(AppLock.wipeAfter(context)) }
    var relockAfterMs by remember { mutableLongStateOf(AppLock.relockAfterMs(context)) }
    var showPasscodeSetup by remember { mutableStateOf(false) }
    var showPasscodeDisable by remember { mutableStateOf(false) }
    var showDuressSetup by remember { mutableStateOf(false) }
    var showDuressRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Only a strong (class 3) biometric can drive the enrolment-bound Keystore key
    // the lock screen's prompt needs, and only once one is enrolled.
    val canUseBiometric = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    // ── Resume after reboot ─────────────────────────────────────────────────
    //
    // Android only lets the scanner start at boot with background location ("Allow
    // all the time"), which has to be requested on its own, after foreground
    // location, and which Android grants only on its settings page — there is no
    // dialog for it. The toggle is on only when the user chose it *and* the
    // permission is actually there; it is re-read on every resume because the
    // grant happens outside the app.
    fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    // The persisted choice. It is only ever written true once the background
    // permission is actually held: writing it first and hoping for the grant left
    // the switch showing off with true stored underneath, so a tap could only re-send
    // true, and a grant made later for some other reason switched boot-resume on
    // unasked.
    var resumeWanted by remember { mutableStateOf(AppSettings.resumeAfterReboot) }
    var backgroundGranted by remember { mutableStateOf(has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    // The user has turned the switch on and the grant is still outstanding.
    var awaitingBackground by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }

    fun persistResume(value: Boolean) {
        resumeWanted = value
        AppSettings.setResumeAfterReboot(context, value)
    }

    /** Called with the outcome of any step of the permission flow. */
    fun backgroundOutcome(granted: Boolean) {
        backgroundGranted = granted
        if (!awaitingBackground) return
        if (granted) {
            awaitingBackground = false
            persistResume(true)
        } else {
            // The flow ended without the grant: nothing is stored as wanted. The
            // shortcut to the settings page stays offered while this screen is up.
            persistResume(false)
        }
    }

    // Heal a value stored true by an earlier version without the permission behind it.
    LaunchedEffect(Unit) {
        if (resumeWanted && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) persistResume(false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // The grant happens on Android's settings page, outside the app, so it is
        // re-read on every return.
        val granted = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (awaitingBackground) {
            if (granted) backgroundOutcome(true) else backgroundGranted = false
        } else {
            backgroundGranted = granted
        }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> backgroundOutcome(granted) }
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Background location can only be asked for once foreground location is held.
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            backgroundOutcome(false)
        }
    }

    if (showBackgroundDialog) {
        ClairixDialog(
            title = "Allow location all the time",
            text = "Android only lets Clairix restart scanning after a reboot if location access is set to " +
                "\"Allow all the time\". Android opens the location permission page for Clairix: choose " +
                "\"Allow all the time\" there, then come back.",
            confirmLabel = "Continue",
            onConfirm = {
                showBackgroundDialog = false
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    // Android 12+ ignores a request for fine location that does
                    // not also name coarse; both have to be in the same request.
                    foregroundLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            },
            onDismiss = { showBackgroundDialog = false; backgroundOutcome(false) }
        )
    }

    if (showClearDialog) {
        ClairixDialog(
            title = "Clear all data?",
            text = "This removes every detection, the cell tower baseline and the whole timeline. There's no undo.",
            confirmLabel = "Clear data",
            confirmTone = Tone.Danger,
            onConfirm = {
                // The scanner owns the baseline and the event log in memory, so it
                // has to do the clearing. Deleting the files from here only worked
                // while the service was stopped — otherwise the next flush wrote
                // everything straight back.
                onClearData()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false }
        )
    }

    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val c = ClairixTheme.colors

    // This screen replaces the whole tab tree, so it does not inherit the insets
    // applied around the tabs and keeps clear of the system bars itself.
    Column(Modifier.fillMaxSize().background(c.base)) {
        ClairixTopBar(title = "Settings", onBack = onBack)
        ScreenList(Modifier.weight(1f).navigationBarsPadding(), bottomPadding = 32) {

            // ── Appearance ───────────────────────────────────────────────────
            item(key = "§look-hdr") { SectionHeader("Appearance") }
            item(key = "§look") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Pick the colours the app uses. The logo and icon stay Acid in every theme.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // One radio group for all four themes, across both rows.
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClairixThemeName.entries.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { option ->
                                ThemeSwatch(
                                    theme = option,
                                    selected = option == theme,
                                    onSelect = { AppSettings.setTheme(context, option) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                    }
                }
            }

            // ── Scanning ─────────────────────────────────────────────────────
            if (BuildConfig.FULL_ACCESS) {
                item(key = "§scan-hdr") { SectionHeader("Scanning") }

                item(key = "§scan-toggles") {
                    ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        ToggleRow(
                            title = "Aggressive scan",
                            subtitle = if (aggressiveScan) "Faster detection, more battery. Low-latency Bluetooth scan."
                            else "Balanced scan. Recommended for everyday use.",
                            checked = aggressiveScan,
                            onCheckedChange = { v ->
                                aggressiveScan = v
                                AppSettings.setAggressiveScan(context, v)
                            }
                        )
                        Hairline()
                        val active = resumeWanted && backgroundGranted
                        ToggleRow(
                            title = "Resume after reboot",
                            subtitle = "Restarts the scanner when the phone boots, if it was running before.",
                            checked = active,
                            onCheckedChange = { v ->
                                if (!v) {
                                    awaitingBackground = false
                                    persistResume(false)
                                } else if (backgroundGranted) {
                                    persistResume(true)
                                } else {
                                    awaitingBackground = true
                                    showBackgroundDialog = true
                                }
                            }
                        )
                        if (!backgroundGranted) {
                            Text(
                                "Needs location set to \"Allow all the time\". Android asks for this separately: " +
                                    "turning this on takes you to Clairix's location permission page.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSubtle,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            if (awaitingBackground) {
                                // After a refusal Android stops opening the page on request, so
                                // offer the app's own settings page as the way there. The
                                // grant is picked up on return and only then stored as wanted.
                                GhostButton(
                                    "Open app settings",
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                )
                            }
                        } else if (!resumeWanted) {
                            Text(
                                "\"Allow all the time\" is granted but unused while this is off. You can set it back " +
                                    "to \"Only while using the app\" in Android settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSubtle,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }
                }

                item(key = "§follow-thresh") {
                    ThresholdCard(
                        title = "Following distance",
                        value = "${followThreshold.toInt()} m",
                        body = "How far a device has to move with you before it counts as following.",
                        slider = {
                            ClairixSlider(
                                value = followThreshold,
                                onValueChange = { followThreshold = it },
                                onValueChangeFinished = { AppSettings.setFollowThreshold(context, followThreshold) },
                                valueRange = 100f..500f,
                                steps = 7
                            )
                        },
                        scale = listOf("100 m", "Default 300 m", "500 m")
                    )
                }

                item(key = "§persist-thresh") {
                    ThresholdCard(
                        title = "Persistence time",
                        value = "${persistenceThreshold.toInt()} min",
                        body = "Minutes a device has to stay near you before it's flagged. Capped at " +
                            "${AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()} min: some trackers (Samsung SmartTag) " +
                            "change their Bluetooth address about every 15 minutes, and a longer wait would let " +
                            "one rotate before it could ever be flagged.",
                        slider = {
                            ClairixSlider(
                                value = persistenceThreshold,
                                onValueChange = { persistenceThreshold = it },
                                onValueChangeFinished = {
                                    AppSettings.setPersistenceThreshold(context, persistenceThreshold)
                                },
                                valueRange = AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN..AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN,
                                // One stop per whole minute.
                                steps = (AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN - AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN).toInt() - 1
                            )
                        },
                        scale = listOf(
                            "${AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN.toInt()} min",
                            "Default ${AppSettings.DEFAULT_PERSISTENCE_THRESHOLD_MIN.toInt()} min",
                            "${AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()} min"
                        )
                    )
                }

                // ── Trusted devices ──────────────────────────────────────────────
                item(key = "§trusted-hdr") {
                    SectionHeader("Trusted devices", supporting = if (trusted.isEmpty()) null else "${trusted.size}")
                }
                if (trusted.isEmpty()) {
                    item(key = "§trusted-empty") {
                        EmptyState(
                            title = "No trusted devices yet",
                            body = "Mark a flagged device as yours on the Trackers screen and its address lands here.",
                            icon = ClairixIcons.BluetoothSearching
                        )
                    }
                } else {
                    item(key = "§trusted") {
                        ListGroup {
                            trusted.sorted().forEachIndexed { i, addr ->
                                if (i > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(addr, style = MaterialTheme.typography.mono, color = c.textMuted, modifier = Modifier.weight(1f))
                                    GhostButton("Remove", onClick = { Registry.untrust(addr) }, tone = Tone.Danger)
                                }
                            }
                        }
                    }
                }
            }

            // ── Passcode lock ────────────────────────────────────────────────
            item(key = "§lock-hdr") { SectionHeader("App passcode") }

            item(key = "§lock-controls") {
                ClairixCard(spacing = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconWell(Icons.Filled.Lock, if (lockEnabled) Tone.Safe else Tone.Neutral)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (lockEnabled) "Passcode on" else "Passcode off",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text
                            )
                            Text(
                                if (lockEnabled)
                                    if (wipeAfter > 0)
                                        "Clairix locks when you leave. $wipeAfter wrong ${if (wipeAfter == 1) "attempt" else "attempts"} in a row wipe everything."
                                    else
                                        "Clairix locks when you leave. Each run of wrong passcodes locks you out for longer."
                                else
                                    "Set a 6 to 12 digit passcode. All app data is wiped after too many wrong guesses.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSubtle
                            )
                        }
                    }
                    if (lockEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Change", onClick = { showPasscodeSetup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
                            SecondaryButton("Turn off", onClick = { showPasscodeDisable = true }, tone = Tone.Danger, modifier = Modifier.weight(1f))
                        }
                        if (canUseBiometric) {
                            Hairline()
                            ToggleRow(
                                title = "Biometric unlock",
                                subtitle = "Use fingerprint or face instead of typing your passcode.",
                                checked = biometricEnabled,
                                onCheckedChange = { on ->
                                    scope.launch(Dispatchers.IO) {
                                        AppLock.setBiometricEnabled(context, on)
                                    }
                                }
                            )
                        }
                        Hairline()
                        SChoiceRow(
                            title = "Wrong passcode limit",
                            subtitle = "How many wrong guesses in a row wipe Clairix.",
                            choices = AppLock.WIPE_AFTER_CHOICES,
                            selected = wipeAfter,
                            label = ::wipeAfterLabel,
                            onSelect = { choice ->
                                wipeAfter = choice
                                scope.launch(Dispatchers.IO) { AppLock.setWipeAfter(context, choice) }
                            }
                        )
                        SChoiceRow(
                            title = "Lock after",
                            subtitle = "How long Clairix can sit in the background before it asks again.",
                            choices = AppLock.RELOCK_CHOICES,
                            selected = relockAfterMs,
                            label = ::relockLabel,
                            onSelect = { choice ->
                                relockAfterMs = choice
                                scope.launch(Dispatchers.IO) { AppLock.setRelockAfterMs(context, choice) }
                            }
                        )
                        Hairline()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Duress code", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                                StatusPill(if (hasDuress) "Set" else "Not set", if (hasDuress) Tone.Safe else Tone.Neutral)
                            }
                            Text(
                                "A second code that quietly wipes Clairix. It looks like any wrong passcode.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSubtle
                            )
                        }
                        if (hasDuress) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("Change", onClick = { showDuressSetup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
                                SecondaryButton("Remove", onClick = { showDuressRemove = true }, tone = Tone.Danger, modifier = Modifier.weight(1f))
                            }
                        } else {
                            SecondaryButton("Set duress code", onClick = { showDuressSetup = true }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        PrimaryButton("Set passcode", onClick = { showPasscodeSetup = true }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (showPasscodeSetup) {
                item(key = "§lock-setup") {
                    PasscodeSetupPanel(
                        context = context,
                        isChanging = lockEnabled,
                        onDone = { showPasscodeSetup = false },
                        onCancel = { showPasscodeSetup = false }
                    )
                }
            }

            if (showPasscodeDisable) {
                item(key = "§lock-disable") {
                    PasscodeDisablePanel(
                        context = context,
                        onDone = { showPasscodeDisable = false },
                        onCancel = { showPasscodeDisable = false }
                    )
                }
            }

            if (showDuressSetup) {
                item(key = "§duress-setup") {
                    DuressSetupPanel(
                        context = context,
                        onDone = { hasDuress = AppLock.hasDuress(context); showDuressSetup = false },
                        onCancel = { showDuressSetup = false }
                    )
                }
            }

            if (showDuressRemove) {
                item(key = "§duress-remove") {
                    DuressRemovePanel(
                        context = context,
                        onDone = { hasDuress = AppLock.hasDuress(context); showDuressRemove = false },
                        onCancel = { showDuressRemove = false }
                    )
                }
            }

            // ── Data management ──────────────────────────────────────────────
            //
            // Evidence export and the scanner's clear-all; Comms keeps its own delete
            // (Delete identity and number in Comms settings).

            if (BuildConfig.FULL_ACCESS) item(key = "§data-hdr") { SectionHeader("Data") }

            if (BuildConfig.FULL_ACCESS) item(key = "§data-actions") {
                ClairixCard(spacing = 8.dp) {
                    Text(
                        "Detection data stays on this phone. Export it when you want a record to keep or hand over.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    PrimaryButton(
                        "Export evidence",
                        onClick = {
                            val intent = Report.share(context, status, detections, timeline, cell, nfc)
                            context.startActivity(Intent.createChooser(intent, "Share evidence report"))
                        },
                        icon = Icons.Filled.Share,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecondaryButton("Clear all data", onClick = { showClearDialog = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }

            // ── About ────────────────────────────────────────────────────────

            item(key = "§about-hdr") { SectionHeader("About") }

            item(key = "§about") {
                ClairixCard(spacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogoMark(size = 40.dp, contentDescription = null)
                        Column {
                            Text(context.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.text)
                            Text("Nothing hides.", style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                        }
                    }
                    KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    KeyValueRow("Package", BuildConfig.APPLICATION_ID)
                    KeyValueRow("Edition", if (BuildConfig.FULL_ACCESS) "Full: scanner, tools and Comms" else "Comms only", mono = false)

                    if (BuildConfig.FULL_ACCESS) {
                        Hairline(Modifier.padding(vertical = 4.dp))
                        Text("Legal", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            "Clairix is a counter-surveillance research tool for lawful personal and professional " +
                                "security use. Finding a device is not proof of surveillance: check findings more than " +
                                "one way before you act. You're responsible for following the laws on surveillance " +
                                "detection where you are.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSubtle
                        )
                    }

                    Hairline(Modifier.padding(vertical = 4.dp))
                    Text("Privacy", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        if (BuildConfig.FULL_ACCESS)
                            "Detection data stays on this phone. Location history, device identifiers, cell tower " +
                                "baselines" + (if (BuildConfig.CARD_VAULT) ", the timeline and the card vault" else " and the timeline") +
                                " are stored locally, sealed under a key in this phone's secure hardware, and never " +
                                "uploaded. The map downloads tiles from OpenStreetMap and sends address searches to its " +
                                "Nominatim service; both see your IP address and the area you're looking at. Offline maps " +
                                "and work zones come signed from GitHub. Messages, calls and live traffic go through the " +
                                "Clairix relay, which cannot read them and is not told who sent them. Evidence leaves the " +
                                "phone only when you export it."
                        else
                            "Messages and calls are end-to-end encrypted; the relay sees neither what they say nor who " +
                                "sent them. Your keys, contacts and history are stored on this phone only. Location is " +
                                "read at the moment you press SOS, to attach to that alert, and at no other time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                }
            }
        }
    }
}

/** A setting with a value on the right, an explanation, a slider and its scale. */
@Composable
private fun ThresholdCard(
    title: String,
    value: String,
    body: String,
    slider: @Composable () -> Unit,
    scale: List<String>
) {
    val c = ClairixTheme.colors
    ClairixCard(spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.mono, color = c.accent)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
        slider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            scale.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSubtle) }
        }
    }
}

private fun wipeAfterLabel(attempts: Int): String = when (attempts) {
    0 -> "Never, lock out instead"
    1 -> "1 attempt"
    else -> "$attempts attempts"
}

private fun relockLabel(ms: Long): String = when (ms) {
    0L -> "Immediately"
    30_000L -> "30 seconds"
    60_000L -> "1 minute"
    else -> "${ms / 60_000L} minutes"
}

/** A titled setting with its choices as chips underneath; the picked one is filled. */
@Composable
private fun <T> SChoiceRow(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
        }
        ChoiceChips(
            choices = choices,
            selected = selected,
            label = label,
            onSelect = { choice -> if (choice != selected) onSelect(choice) }
        )
    }
}

/**
 * The step-by-step code entry that setting, changing and removing a passcode or
 * a duress code share: a prompt, an error line, the digits field and the
 * buttons.
 */
@Composable
private fun CodePanel(
    title: String,
    note: String?,
    error: String,
    value: String,
    fieldLabel: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    confirmTone: Tone,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ClairixCard(tone = confirmTone, spacing = 12.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
        if (error.isNotEmpty()) WarningText(error)
        ClairixTextField(
            value = value,
            onValueChange = { v -> onValueChange(v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH)) },
            label = fieldLabel,
            keyboardType = KeyboardType.NumberPassword,
            isError = error.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Cancel", onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            PrimaryButton(confirmLabel, onClick = onConfirm, tone = confirmTone)
        }
    }
}

@Composable
private fun WarningText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
}

@Composable
private fun PasscodeSetupPanel(
    context: Context,
    isChanging: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableIntStateOf(if (isChanging) 0 else 1) }
    // step 0 = confirm current, step 1 = enter new, step 2 = confirm new
    var code1 by remember { mutableStateOf("") }
    var code2 by remember { mutableStateOf("") }
    var currentCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    CodePanel(
        title = when {
            isChanging && step == 0 -> "Enter your current passcode"
            step == 1 -> "Choose a new passcode"
            else -> "Confirm the new passcode"
        },
        note = if (step == 1) "6 to 12 digits." else null,
        error = error,
        value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
        fieldLabel = if (step == 0) "Current passcode" else "Digits",
        onValueChange = { digits ->
            when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
            error = ""
        },
        confirmLabel = if (step < 2) "Next" else "Save",
        confirmTone = Tone.Accent,
        onConfirm = {
            scope.launch {
                error = ""
                when (step) {
                    0 -> {
                        val v = withContext(Dispatchers.IO) {
                            AppLock.attempt(context, currentCode, unlockOnSuccess = false)
                        }
                        when (v) {
                            AppLock.Verdict.Accepted -> step = 1
                            is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                            is AppLock.Verdict.LockedOut -> error = "Too many attempts. Try again later."
                            AppLock.Verdict.Wiping -> onCancel()
                            is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                        }
                    }
                    1 -> {
                        AppLock.problem(code1)?.let { error = it; return@launch }
                        step = 2
                    }
                    2 -> {
                        if (code1 != code2) { error = "Those passcodes don't match"; return@launch }
                        val r = withContext(Dispatchers.IO) {
                            AppLock.setPasscode(context, code1)
                        }
                        r.onSuccess { onDone() }.onFailure { error = it.message ?: "Couldn't save the passcode" }
                    }
                }
            }
        },
        onCancel = onCancel
    )
}

@Composable
private fun PasscodeDisablePanel(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    CodePanel(
        title = "Enter your passcode to turn the lock off",
        note = null,
        error = error,
        value = code,
        fieldLabel = "Current passcode",
        onValueChange = { code = it; error = "" },
        confirmLabel = "Turn off",
        confirmTone = Tone.Danger,
        onConfirm = {
            scope.launch {
                val v = withContext(Dispatchers.IO) {
                    AppLock.attempt(context, code, unlockOnSuccess = false)
                }
                when (v) {
                    AppLock.Verdict.Accepted -> {
                        withContext(Dispatchers.IO) { AppLock.disable(context) }
                        onDone()
                    }
                    is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                    is AppLock.Verdict.LockedOut -> error = "Too many attempts. Try again later."
                    AppLock.Verdict.Wiping -> onCancel()
                    is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                }
            }
        },
        onCancel = onCancel
    )
}

@Composable
private fun DuressSetupPanel(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    // step 0 = confirm current passcode, step 1 = enter duress, step 2 = confirm duress
    var step  by remember { mutableIntStateOf(0) }
    var currentCode by remember { mutableStateOf("") }
    var code1 by remember { mutableStateOf("") }
    var code2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    CodePanel(
        title = when (step) {
            0 -> "Confirm your passcode"
            1 -> "Choose a duress code"
            else -> "Confirm the duress code"
        },
        note = if (step != 0) "6 to 12 digits, different from your passcode. Entering it quietly wipes Clairix." else null,
        error = error,
        value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
        fieldLabel = if (step == 0) "Current passcode" else "Digits",
        onValueChange = { digits ->
            when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
            error = ""
        },
        confirmLabel = if (step < 2) "Next" else "Save",
        confirmTone = Tone.Accent,
        onConfirm = {
            scope.launch {
                error = ""
                when (step) {
                    0 -> {
                        val v = withContext(Dispatchers.IO) {
                            AppLock.attempt(context, currentCode, unlockOnSuccess = false)
                        }
                        when (v) {
                            AppLock.Verdict.Accepted -> step = 1
                            is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                            is AppLock.Verdict.LockedOut -> error = "Too many attempts. Try again later."
                            AppLock.Verdict.Wiping -> onCancel()
                            is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                        }
                    }
                    1 -> {
                        AppLock.problem(code1)?.let { error = it; return@launch }
                        step = 2
                    }
                    else -> {
                        if (code1 != code2) { error = "Those codes don't match"; return@launch }
                        val r = withContext(Dispatchers.IO) {
                            AppLock.setDuress(context, code1)
                        }
                        r.onSuccess { onDone() }.onFailure { error = it.message ?: "Couldn't save the duress code" }
                    }
                }
            }
        },
        onCancel = onCancel
    )
}

@Composable
private fun DuressRemovePanel(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var code  by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    CodePanel(
        title = "Enter your passcode to remove the duress code",
        note = null,
        error = error,
        value = code,
        fieldLabel = "Passcode",
        onValueChange = { code = it; error = "" },
        confirmLabel = "Remove",
        confirmTone = Tone.Danger,
        onConfirm = {
            scope.launch {
                val v = withContext(Dispatchers.IO) {
                    AppLock.attempt(context, code, unlockOnSuccess = false)
                }
                when (v) {
                    AppLock.Verdict.Accepted -> {
                        withContext(Dispatchers.IO) { AppLock.setDuress(context, null) }
                        onDone()
                    }
                    is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                    is AppLock.Verdict.LockedOut -> error = "Too many attempts. Try again later."
                    AppLock.Verdict.Wiping -> onCancel()
                    is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                }
            }
        },
        onCancel = onCancel
    )
}
