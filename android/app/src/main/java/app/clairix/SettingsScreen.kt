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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.mono
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone

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

// ── Private colour tokens (local to this file) ─────────────────────────────────

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
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false; backgroundOutcome(false) },
            containerColor = ClairixTheme.colors.surface,
            title = {
                Text("Allow location all the time", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            },
            text = {
                Text(
                    "Android only lets Clairix restart scanning after a reboot if location access " +
                    "is set to \"Allow all the time\". Android will open the location permission " +
                    "page for Clairix — choose \"Allow all the time\" there, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Continue",
                    onClick = {
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
                    }
                )
            },
            dismissButton = {
                GhostButton(
                    "Cancel",
                    onClick = { showBackgroundDialog = false; backgroundOutcome(false) },
                    tone = Tone.Neutral
                )
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = ClairixTheme.colors.surface,
            title = {
                Text("Clear all data?", style = MaterialTheme.typography.titleMedium, color = ClairixTheme.colors.text)
            },
            text = {
                Text(
                    "This removes all detections, the cellular baseline and all timeline events. " +
                    "This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textMuted
                )
            },
            confirmButton = {
                GhostButton(
                    "Clear",
                    onClick = {
                        // The scanner owns the baseline and the event log in memory, so it
                        // has to do the clearing. Deleting the files from here only worked
                        // while the service was stopped — otherwise the next flush wrote
                        // everything straight back.
                        onClearData()
                        showClearDialog = false
                    },
                    tone = Tone.Danger
                )
            },
            dismissButton = {
                GhostButton("Cancel", onClick = { showClearDialog = false }, tone = Tone.Neutral)
            }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(ClairixTheme.colors.base)
            // This screen replaces the whole tab tree, so it does not inherit the
            // insets applied around the tabs and has to keep clear of the system
            // bars itself — otherwise the BACK row sits under the status bar.
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header row
        item(key = "§header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostButton("‹ Back", onClick = onBack)
                Text(
                    "SETTINGS",
                    style = MaterialTheme.typography.headlineSmall,
                    color = ClairixTheme.colors.text
                )
                Spacer(Modifier.width(60.dp))
            }
        }

        // ── Scan settings ────────────────────────────────────────────────────

        if (BuildConfig.FULL_ACCESS) {
            item(key = "§scan-hdr") { SSettingsSectionLabel("SCAN SETTINGS") }

            item(key = "§aggressive") {
                Column(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Aggressive scan mode",
                                style = MaterialTheme.typography.titleSmall,
                                color = ClairixTheme.colors.text
                            )
                            Text(
                                "Higher detection rate at the cost of battery life",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClairixTheme.colors.textSubtle
                            )
                        }
                        Switch(
                            checked = aggressiveScan,
                            onCheckedChange = { v ->
                                aggressiveScan = v
                                AppSettings.setAggressiveScan(context, v)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ClairixTheme.colors.accent,
                                checkedTrackColor = ClairixTheme.colors.accent.copy(alpha = 0.4f)
                            )
                        )
                    }
                    Text(
                        if (aggressiveScan) "SCAN_MODE_LOW_LATENCY — faster detection, higher battery drain"
                        else "SCAN_MODE_BALANCED — standard detection, recommended for field use",
                        style = MaterialTheme.typography.monoSmall,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }

            item(key = "§resume-reboot") {
                val active = resumeWanted && backgroundGranted
                Column(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Resume scanning after reboot",
                                style = MaterialTheme.typography.titleSmall,
                                color = ClairixTheme.colors.text
                            )
                            Text(
                                "Restarts the scanner when the phone boots, if it was running before",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClairixTheme.colors.textSubtle
                            )
                        }
                        Switch(
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
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ClairixTheme.colors.accent,
                                checkedTrackColor = ClairixTheme.colors.accent.copy(alpha = 0.4f)
                            )
                        )
                    }
                    if (!backgroundGranted) {
                        Text(
                            "Needs location access set to \"Allow all the time\". Android asks for this " +
                            "separately: turning this on sends you to the location permission page for " +
                            "Clairix, where you pick \"Allow all the time\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
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
                            "\"Allow all the time\" is granted but not used while this is off. You can " +
                            "reduce it to \"Only while using the app\" in Android settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            }

            item(key = "§follow-thresh") {
                Column(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Following confirmation threshold",
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${followThreshold.toInt()} m",
                            style = MaterialTheme.typography.mono,
                            color = ClairixTheme.colors.accent
                        )
                    }
                    Text(
                        "Minimum displacement required to confirm a device is following you",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                    Slider(
                        value = followThreshold,
                        onValueChange = { followThreshold = it },
                        onValueChangeFinished = { AppSettings.setFollowThreshold(context, followThreshold) },
                        valueRange = 100f..500f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = ClairixTheme.colors.accent,
                            activeTrackColor = ClairixTheme.colors.accent,
                            inactiveTrackColor = ClairixTheme.colors.border
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("100 m", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                        Text("Default: 300 m", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                        Text("500 m", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                    }
                }
            }

            item(key = "§persist-thresh") {
                Column(
                    Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Persistence threshold",
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${persistenceThreshold.toInt()} min",
                            style = MaterialTheme.typography.mono,
                            color = ClairixTheme.colors.accent
                        )
                    }
                    Text(
                        "Minutes of continuous presence before a device is flagged as persistent. " +
                            "Capped at ${AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()} min: some trackers " +
                            "(Samsung SmartTag) change their Bluetooth address about every 15 minutes, and a " +
                            "longer threshold would let one rotate before it could ever be flagged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                    Slider(
                        value = persistenceThreshold,
                        onValueChange = { persistenceThreshold = it },
                        onValueChangeFinished = {
                            AppSettings.setPersistenceThreshold(context, persistenceThreshold)
                        },
                        valueRange = AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN..AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN,
                        // One stop per whole minute.
                        steps = (AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN - AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN).toInt() - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = ClairixTheme.colors.accent,
                            activeTrackColor = ClairixTheme.colors.accent,
                            inactiveTrackColor = ClairixTheme.colors.border
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${AppSettings.MIN_PERSISTENCE_THRESHOLD_MIN.toInt()} min", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                        Text("Default: ${AppSettings.DEFAULT_PERSISTENCE_THRESHOLD_MIN.toInt()} min", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                        Text("${AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()} min", style = MaterialTheme.typography.bodySmall, color = ClairixTheme.colors.textSubtle)
                    }
                }
            }

            // ── Trusted devices ──────────────────────────────────────────────────

            item(key = "§trusted-hdr") { SSettingsSectionLabel("TRUSTED DEVICES") }

            if (trusted.isEmpty()) {
                item(key = "§trusted-empty") {
                    Column(
                        Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("No trusted devices yet", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                        Text(
                            "Mark any detection card as safe on the SCAN tab. " +
                            "Its Bluetooth address is automatically added here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
            } else {
                items(trusted.sorted(), key = { "trusted|$it" }) { addr ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(ClairixTheme.colors.surface, ClairixShapes.Card)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            addr,
                            style = MaterialTheme.typography.mono,
                            color = ClairixTheme.colors.textMuted,
                            modifier = Modifier.weight(1f)
                        )
                        GhostButton("Remove", onClick = { Registry.untrust(addr) }, tone = Tone.Danger)
                    }
                }
                item(key = "§trusted-hint") {
                    Text(
                        "Tap MARK SAFE on a detection card on the Scan tab to add a device here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        }

        // ── Passcode lock ────────────────────────────────────────────────────

        item(key = "§lock-hdr") { SSettingsSectionLabel("APP PASSCODE") }

        item(key = "§lock-controls") {
            Column(
                Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (lockEnabled) "App passcode is ON" else "App passcode is OFF",
                            style = MaterialTheme.typography.titleSmall,
                            color = ClairixTheme.colors.text
                        )
                        Text(
                            if (lockEnabled)
                                if (wipeAfter > 0)
                                    "Clairix locks when you leave. Wrong passcode after $wipeAfter ${if (wipeAfter == 1) "attempt" else "attempts"} wipes everything."
                                else
                                    "Clairix locks when you leave. Repeated wrong passcodes lock you out for longer each time."
                            else
                                "Set a 6–12 digit passcode. All app data is wiped after too many wrong guesses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClairixTheme.colors.textSubtle
                        )
                    }
                }
                if (lockEnabled) {
                    SecondaryButton(
                        "Change passcode",
                        onClick = { showPasscodeSetup = true },
                        tone = Tone.Accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecondaryButton(
                        "Turn off passcode",
                        onClick = { showPasscodeDisable = true },
                        tone = Tone.Danger,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (canUseBiometric) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Biometric unlock",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ClairixTheme.colors.text
                                )
                                Text(
                                    "Use fingerprint or face to unlock instead of typing your passcode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ClairixTheme.colors.textSubtle
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = biometricEnabled,
                                onCheckedChange = { on ->
                                    scope.launch(Dispatchers.IO) {
                                        AppLock.setBiometricEnabled(context, on)
                                    }
                                }
                            )
                        }
                    }
                    SChoiceRow(
                        title = "Wrong passcode limit",
                        subtitle = "How many wrong guesses in a row wipe Clairix",
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
                        subtitle = "How long Clairix can be in the background before it asks again",
                        choices = AppLock.RELOCK_CHOICES,
                        selected = relockAfterMs,
                        label = ::relockLabel,
                        onSelect = { choice ->
                            relockAfterMs = choice
                            scope.launch(Dispatchers.IO) { AppLock.setRelockAfterMs(context, choice) }
                        }
                    )
                    HorizontalDivider(color = ClairixTheme.colors.border, thickness = 0.5.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (hasDuress) "Duress code is set" else "Duress code",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ClairixTheme.colors.text
                                )
                                Text(
                                    "A second code that silently wipes Clairix — looks like any wrong passcode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ClairixTheme.colors.textSubtle
                                )
                            }
                        }
                        if (hasDuress) {
                            SecondaryButton(
                                "Change duress code",
                                onClick = { showDuressSetup = true },
                                tone = Tone.Accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                            SecondaryButton(
                                "Remove duress code",
                                onClick = { showDuressRemove = true },
                                tone = Tone.Danger,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            SecondaryButton(
                                "Set duress code",
                                onClick = { showDuressSetup = true },
                                tone = Tone.Accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
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

        // ── Data management ──────────────────────────────────────────────────
        //
        // Evidence export and the scanner's clear-all; COMMS keeps its own delete
        // (DELETE IDENTITY AND NUMBER in COMMS settings).

        if (BuildConfig.FULL_ACCESS) item(key = "§data-hdr") { SSettingsSectionLabel("DATA") }

        if (BuildConfig.FULL_ACCESS) item(key = "§data-actions") {
            Column(
                Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrimaryButton(
                    "Export evidence",
                    onClick = {
                            val intent = Report.share(context, status, detections, timeline, cell, nfc)
                            context.startActivity(Intent.createChooser(intent, "Share evidence report"))
                        },
                    modifier = Modifier.fillMaxWidth()
                )
                SecondaryButton(
                    "Clear all data",
                    onClick = { showClearDialog = true },
                    tone = Tone.Danger,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── About ────────────────────────────────────────────────────────────

        item(key = "§about-hdr") { SSettingsSectionLabel("ABOUT") }

        item(key = "§about") {
            Column(
                Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SSettingsKv("App", context.getString(R.string.app_name))
                SSettingsKv(
                    "Version",
                    "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
                )
                SSettingsKv("Package", BuildConfig.APPLICATION_ID)
                SSettingsKv("Edition", if (BuildConfig.FULL_ACCESS) "Full — scanner, tools and COMMS" else "COMMS only")

                if (BuildConfig.FULL_ACCESS) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ClairixTheme.colors.border))

                    Text(
                        "Legal disclaimer",
                        style = MaterialTheme.typography.titleSmall,
                        color = ClairixTheme.colors.text
                    )
                    Text(
                        "Clairix is a counter-surveillance research tool intended for lawful personal " +
                        "and professional security use. Detection of a device is not conclusive evidence of " +
                        "surveillance. Always verify findings through multiple methods before acting. " +
                        "Users are responsible for complying with all applicable laws regarding electronic " +
                        "surveillance detection in their jurisdiction.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClairixTheme.colors.textSubtle
                    )
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(ClairixTheme.colors.border))

                Text(
                    "Privacy",
                    style = MaterialTheme.typography.titleSmall,
                    color = ClairixTheme.colors.text
                )
                Text(
                    if (BuildConfig.FULL_ACCESS)
                        "Detection data stays on this device. Location history, device identifiers, " +
                        "cellular baselines, the timeline and the card vault are stored locally and are " +
                        "never uploaded. The Map tab downloads map tiles from OpenStreetMap, which sees " +
                        "your IP address and the area you're viewing. Evidence exports are shared only " +
                        "when you explicitly trigger an export."
                    else
                        "Messages and calls are end-to-end encrypted; the relay sees neither their content " +
                        "nor who sent them. Your keys, contacts and message history are stored on this " +
                        "device only. Location is read at the moment you press SOS, to attach to that " +
                        "alert, and at no other time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClairixTheme.colors.textSubtle
                )
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SSettingsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = ClairixTheme.colors.accent,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SSettingsKv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
        Text(value, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textMuted)
    }
}

private fun wipeAfterLabel(attempts: Int): String = when (attempts) {
    0 -> "Never — lock out instead"
    1 -> "1 attempt"
    else -> "$attempts attempts"
}

private fun relockLabel(ms: Long): String = when (ms) {
    0L -> "Immediately"
    30_000L -> "30 seconds"
    60_000L -> "1 minute"
    else -> "${ms / 60_000L} minutes"
}

/**
 * A titled row with a small inline selector on the right: the current choice as a
 * text button that opens a [DropdownMenu] of every option.
 */
@Composable
private fun <T> SChoiceRow(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
        }
        Spacer(Modifier.width(12.dp))
        Box {
            GhostButton(label(selected), onClick = { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (choice in choices) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                label(choice),
                                fontWeight = if (choice == selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            expanded = false
                            if (choice != selected) onSelect(choice)
                        }
                    )
                }
            }
        }
    }
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

    Column(
        Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val title = when {
            isChanging && step == 0 -> "Enter your current passcode"
            step == 1 -> "Choose a new passcode (6–12 digits)"
            else -> "Confirm the new passcode"
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text)
        if (error.isNotEmpty()) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
        }
        OutlinedTextField(
            value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH)
                when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
                error = ""
            },
            label = { Text("Digits", color = ClairixTheme.colors.textSubtle) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ClairixTheme.colors.text,
                unfocusedTextColor = ClairixTheme.colors.text,
                focusedBorderColor = ClairixTheme.colors.accent,
                unfocusedBorderColor = ClairixTheme.colors.border
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Cancel", onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                if (step < 2) "Next" else "Save",
                onClick = {
                        scope.launch {
                            error = ""
                            when (step) {
                                0 -> {
                                    val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.attempt(context, currentCode, unlockOnSuccess = false)
                                    }
                                    when (v) {
                                        AppLock.Verdict.Accepted -> step = 1
                                        is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                                        is AppLock.Verdict.LockedOut -> error = "Too many attempts — try later"
                                        AppLock.Verdict.Wiping -> onCancel()
                                        is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                                    }
                                }
                                1 -> {
                                    AppLock.problem(code1)?.let { error = it; return@launch }
                                    step = 2
                                }
                                2 -> {
                                    if (code1 != code2) { error = "Passcodes do not match"; return@launch }
                                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.setPasscode(context, code1)
                                    }
                                    r.onSuccess { onDone() }.onFailure { error = it.message ?: "Failed" }
                                }
                            }
                        }
                    }
            )
        }
    }
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

    Column(
        Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Enter your passcode to turn off the lock",
            style = MaterialTheme.typography.titleSmall,
            color = ClairixTheme.colors.text
        )
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH); error = "" },
            label = { Text("Current passcode", color = ClairixTheme.colors.textSubtle) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ClairixTheme.colors.text,
                unfocusedTextColor = ClairixTheme.colors.text,
                focusedBorderColor = ClairixTheme.colors.accent,
                unfocusedBorderColor = ClairixTheme.colors.border
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Cancel", onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                "Turn off",
                onClick = {
                        scope.launch {
                            val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                AppLock.attempt(context, code, unlockOnSuccess = false)
                            }
                            when (v) {
                                AppLock.Verdict.Accepted -> {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.disable(context)
                                    }
                                    onDone()
                                }
                                is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                                is AppLock.Verdict.LockedOut -> error = "Too many attempts — try later"
                                AppLock.Verdict.Wiping -> onCancel()
                                is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                            }
                        }
                    },
                tone = Tone.Danger
            )
        }
    }
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

    Column(
        Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            when (step) {
                0 -> "Confirm your passcode"
                1 -> "Choose a duress code (6–12 digits)"
                else -> "Confirm the duress code"
            },
            style = MaterialTheme.typography.titleSmall,
            color = ClairixTheme.colors.text
        )
        if (step != 0) {
            Text(
                "Must differ from your main passcode. Entering it silently wipes Clairix.",
                style = MaterialTheme.typography.bodyMedium,
                color = ClairixTheme.colors.textSubtle
            )
        }
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
        OutlinedTextField(
            value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH)
                when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
                error = ""
            },
            label = { Text(if (step == 0) "Current passcode" else "Digits", color = ClairixTheme.colors.textSubtle) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ClairixTheme.colors.text,
                unfocusedTextColor = ClairixTheme.colors.text,
                focusedBorderColor = ClairixTheme.colors.accent,
                unfocusedBorderColor = ClairixTheme.colors.border
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Cancel", onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                if (step < 2) "Next" else "Save",
                onClick = {
                        scope.launch {
                            error = ""
                            when (step) {
                                0 -> {
                                    val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.attempt(context, currentCode, unlockOnSuccess = false)
                                    }
                                    when (v) {
                                        AppLock.Verdict.Accepted -> step = 1
                                        is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                                        is AppLock.Verdict.LockedOut -> error = "Too many attempts — try later"
                                        AppLock.Verdict.Wiping -> onCancel()
                                        is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                                    }
                                }
                                1 -> {
                                    AppLock.problem(code1)?.let { error = it; return@launch }
                                    step = 2
                                }
                                else -> {
                                    if (code1 != code2) { error = "Codes do not match"; return@launch }
                                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.setDuress(context, code1)
                                    }
                                    r.onSuccess { onDone() }.onFailure { error = it.message ?: "Failed" }
                                }
                            }
                        }
                    }
            )
        }
    }
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

    Column(
        Modifier.fillMaxWidth().background(ClairixTheme.colors.surface, ClairixShapes.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Enter your passcode to remove the duress code",
            style = MaterialTheme.typography.titleSmall,
            color = ClairixTheme.colors.text
        )
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(AppLock.MAX_LENGTH); error = "" },
            label = { Text("Passcode", color = ClairixTheme.colors.textSubtle) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ClairixTheme.colors.text,
                unfocusedTextColor = ClairixTheme.colors.text,
                focusedBorderColor = ClairixTheme.colors.accent,
                unfocusedBorderColor = ClairixTheme.colors.border
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Cancel", onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                "Remove",
                onClick = {
                        scope.launch {
                            val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                AppLock.attempt(context, code, unlockOnSuccess = false)
                            }
                            when (v) {
                                AppLock.Verdict.Accepted -> {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        AppLock.setDuress(context, null)
                                    }
                                    onDone()
                                }
                                is AppLock.Verdict.Rejected -> error = "Wrong passcode"
                                is AppLock.Verdict.LockedOut -> error = "Too many attempts — try later"
                                AppLock.Verdict.Wiping -> onCancel()
                                is AppLock.Verdict.Unavailable -> error = "Keystore unavailable"
                            }
                        }
                    },
                tone = Tone.Danger
            )
        }
    }
}
