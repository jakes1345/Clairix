package app.clairix

import android.Manifest
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.CommsRepository
import app.clairix.security.AppLock
import app.clairix.security.HistoryRetention
import app.clairix.ui.appResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.clairix.ui.theme.ClairixThemeName
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Call
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
    private const val KEY_INFRASTRUCTURE_ALERTS = "infrastructure_alerts"
    private const val KEY_SHOW_ALERT_DETAILS = "show_alert_details"

    /**
     * What a duress wipe's decoy keeps of these settings (security/Wiper.kt):
     * the look, discreet mode and how the scanner runs, so the lock screen and
     * the app behind it look and behave as they did. None of them is a record
     * of anything.
     */
    internal val DECOY_KEYS: Set<String> = setOf(
        KEY_AGGRESSIVE_SCAN, KEY_FOLLOW_THRESHOLD, KEY_PERSISTENCE_THRESHOLD, KEY_SCAN_ENABLED,
        KEY_RESUME_AFTER_REBOOT, KEY_THEME, KEY_INFRASTRUCTURE_ALERTS, KEY_SHOW_ALERT_DETAILS,
        app.clairix.analysis.LowPowerMode.KEY_SWITCH,
        // Discreet mode: the decoy shows the same launcher entry, notifications and lock screen.
        app.clairix.security.DiscreetMode.KEY_ON, app.clairix.security.DiscreetMode.KEY_DISGUISE,
    )

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
    @Volatile private var _infrastructureAlerts = true
    @Volatile private var _showAlertDetails = false
    private val _theme = MutableStateFlow(ClairixThemeName.ACID)

    /**
     * Whether hearing surveillance hardware (a Flock Safety camera battery, a Raven
     * gunshot detector, a Flock setup hotspot) raises an alert as well as a timeline
     * row. On by default: it is the one radio signal of a plate reader the phone can hear.
     */
    val infrastructureAlerts: Boolean get() = _infrastructureAlerts

    /**
     * Whether an alert notification (a tracker, a cell anomaly, a platform
     * monitor) may name what it found while Clairix is unlocked. Off by default:
     * every alert then says only "Unlock to read" and the finding waits in the
     * app. While Clairix is locked an alert says only that, whatever this is set
     * to; see [app.clairix.security.NotificationPresentation].
     */
    val showAlertDetails: Boolean get() = _showAlertDetails

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
        _infrastructureAlerts = p.getBoolean(KEY_INFRASTRUCTURE_ALERTS, true)
        _showAlertDetails = p.getBoolean(KEY_SHOW_ALERT_DETAILS, false)
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

    fun setInfrastructureAlerts(context: Context, value: Boolean) {
        _infrastructureAlerts = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INFRASTRUCTURE_ALERTS, value).apply()
    }

    fun setShowAlertDetails(context: Context, value: Boolean) {
        _showAlertDetails = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ALERT_DETAILS, value).apply()
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
fun SettingsScreen(onBack: () -> Unit, onClearData: () -> Unit = {}, onTour: () -> Unit = {}, onGetHelp: () -> Unit = {}) {
    val context = LocalContext.current
    // One row per device the owner marked, whatever addresses the scanner has linked to it.
    val trusted by Registry.trustBook.collectAsStateWithLifecycle()

    var aggressiveScan by remember { mutableStateOf(AppSettings.aggressiveScan) }
    var infrastructureAlerts by remember { mutableStateOf(AppSettings.infrastructureAlerts) }
    var showAlertDetails by remember { mutableStateOf(AppSettings.showAlertDetails) }
    var followThreshold by remember { mutableFloatStateOf(AppSettings.followThresholdM) }
    var persistenceThreshold by remember { mutableFloatStateOf(AppSettings.persistenceThresholdMin) }
    // Where position fixes come from; stored by the positioning code itself, in both editions.
    var satelliteOnly by remember { mutableStateOf(app.clairix.analysis.Positioning.satelliteOnly(context)) }
    var networkCrossCheck by remember { mutableStateOf(app.clairix.analysis.Positioning.networkCrossCheck(context)) }
    var showClearDialog by remember { mutableStateOf(false) }

    // ── Passcode state ───────────────────────────────────────────────────────
    val lockEnabled by AppLock.enabled.collectAsStateWithLifecycle()
    val biometricEnabled by AppLock.biometricEnabled.collectAsStateWithLifecycle()
    var hasDuress by remember { mutableStateOf(AppLock.hasDuress(context)) }
    var wipeAfter by remember { mutableIntStateOf(AppLock.wipeAfter(context)) }
    var wipeChosen by remember { mutableStateOf(AppLock.wipeChoiceConfirmed(context)) }
    val pausedWipe = remember(wipeChosen) { AppLock.pausedWipeAfter(context) }
    // Answered in the question that follows an unlock (WipeLimitQuestion) while this page sat underneath.
    val wipeChoiceDue by AppLock.wipeChoiceDue.collectAsStateWithLifecycle()
    LaunchedEffect(wipeChoiceDue) {
        wipeAfter = AppLock.wipeAfter(context)
        wipeChosen = AppLock.wipeChoiceConfirmed(context)
    }
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
            // Google Play's prominent disclosure for background location: what location is
            // used for while the app is closed, and that it stays on the phone. The same
            // sentence is the opening shot of docs/play/background-location.md's video.
            title = stringResource(R.string.settings_allow_location_all_the_time),
            text = stringResource(R.string.settings_clairix_uses_your_location_when_the),
            confirmLabel = stringResource(R.string.settings_continue),
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
            title = stringResource(R.string.settings_clear_all_data),
            text = stringResource(R.string.settings_clear_all_data_body),
            confirmLabel = stringResource(R.string.settings_clear_data),
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

    // ── Wipe and delete ──────────────────────────────────────────────────
    //
    // The Wipe page opens here as well as from the Comms menu, so it is there to
    // find in Settings whatever else changes; the relay account can be given up
    // from here too, without the rest of the app.
    var showWipe by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val comms by CommsRepository.state.collectAsStateWithLifecycle()
    if (showWipe) {
        BackHandler { showWipe = false }
        Column(Modifier.fillMaxSize().background(c.base).navigationBarsPadding()) {
            WipePage(onBack = { showWipe = false })
        }
        return
    }
    if (confirmUnpair) {
        ClairixDialog(
            title = stringResource(R.string.settings_give_up_this_clairix_number),
            text = stringResource(R.string.settings_the_relay_deletes_your_mailbox_and),
            confirmLabel = stringResource(R.string.settings_delete_identity_and_number),
            confirmTone = Tone.Danger,
            onConfirm = {
                confirmUnpair = false
                scope.launch { CommsRepository.unpair() }
            },
            onDismiss = { confirmUnpair = false }
        )
    }

    // A newer version, from the signed note the app reads once a day: offered under About.
    val update by app.clairix.comms.AppUpdate.notice.collectAsStateWithLifecycle()
    // Which server the maps draw from: the signed tile setting, or OpenStreetMap's.
    val tileServer by app.clairix.nav.MapTiles.config.collectAsStateWithLifecycle()
    // About > Third-party licences: the list this build carries, full screen.
    var showLicences by rememberSaveable { mutableStateOf(false) }
    if (showLicences) {
        BackHandler { showLicences = false }
        Column(Modifier.fillMaxSize().background(c.base).navigationBarsPadding()) {
            ThirdPartyLicencesPage(onBack = { showLicences = false })
        }
        return
    }

    // Settings > Supporter: redeem a code; in the Play edition, buy it (SupporterSettings.kt).
    var showSupporter by rememberSaveable { mutableStateOf(false) }
    if (showSupporter) {
        BackHandler { showSupporter = false }
        Column(Modifier.fillMaxSize().background(c.base).navigationBarsPadding()) {
            SupporterPage(onBack = { showSupporter = false })
        }
        return
    }

    // This screen replaces the whole tab tree, so it does not inherit the insets
    // applied around the tabs and keeps clear of the system bars itself.
    Column(Modifier.fillMaxSize().background(c.base)) {
        ClairixTopBar(title = stringResource(R.string.settings_settings), onBack = onBack)
        ScreenList(Modifier.weight(1f).navigationBarsPadding(), bottomPadding = 32) {

            // ── Get help ─────────────────────────────────────────────────────
            //
            // First, so it is found without scrolling, in both editions.
            item(key = "§help") {
                ListGroup {
                    ListRow(
                        title = stringResource(R.string.settings_get_help),
                        subtitle = stringResource(R.string.settings_helplines_for_abuse_and_stalking_where),
                        icon = Icons.Filled.Call,
                        iconTone = Tone.Accent,
                        onClick = onGetHelp,
                        onClickLabel = stringResource(R.string.settings_open_get_help)
                    )
                }
            }

            // ── Appearance ───────────────────────────────────────────────────
            item(key = "§look-hdr") { SectionHeader(stringResource(R.string.settings_appearance)) }
            item(key = "§look") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_pick_the_colours_the_app_uses),
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
                item(key = "§scan-hdr") { SectionHeader(stringResource(R.string.settings_scanning)) }

                item(key = "§scan-toggles") {
                    ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.settings_aggressive_scan),
                            subtitle = if (aggressiveScan) stringResource(R.string.settings_faster_detection_more_battery_low_latency)
                            else stringResource(R.string.settings_balanced_scan_recommended_for_everyday_use),
                            checked = aggressiveScan,
                            onCheckedChange = { v ->
                                aggressiveScan = v
                                AppSettings.setAggressiveScan(context, v)
                            }
                        )
                        Hairline()
                        ToggleRow(
                            title = stringResource(R.string.settings_alert_on_surveillance_hardware),
                            subtitle = stringResource(R.string.settings_a_notification_when_a_flock_safety),
                            checked = infrastructureAlerts,
                            onCheckedChange = { v ->
                                infrastructureAlerts = v
                                AppSettings.setInfrastructureAlerts(context, v)
                            }
                        )
                        Hairline()
                        val active = resumeWanted && backgroundGranted
                        ToggleRow(
                            title = stringResource(R.string.settings_resume_after_reboot),
                            subtitle = stringResource(R.string.settings_restarts_the_scanner_when_the_phone),
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
                                stringResource(R.string.settings_needs_location_set_to_allow_all),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSubtle,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            if (awaitingBackground) {
                                // After a refusal Android stops opening the page on request, so
                                // offer the app's own settings page as the way there. The
                                // grant is picked up on return and only then stored as wanted.
                                GhostButton(
                                    stringResource(R.string.settings_open_app_settings),
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
                                stringResource(R.string.settings_allow_all_the_time_is_granted),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSubtle,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }
                }

                item(key = "§follow-thresh") {
                    ThresholdCard(
                        title = stringResource(R.string.settings_following_distance),
                        value = "${followThreshold.toInt()} m",
                        body = stringResource(R.string.settings_how_far_a_device_has_to),
                        slider = {
                            ClairixSlider(
                                value = followThreshold,
                                onValueChange = { followThreshold = it },
                                onValueChangeFinished = { AppSettings.setFollowThreshold(context, followThreshold) },
                                valueRange = 100f..500f,
                                steps = 7
                            )
                        },
                        scale = listOf("100 m", stringResource(R.string.settings_default_300_m), "500 m")
                    )
                }

                item(key = "§persist-thresh") {
                    ThresholdCard(
                        title = stringResource(R.string.settings_persistence_time),
                        value = "${persistenceThreshold.toInt()} min",
                        body = stringResource(R.string.settings_minutes_a_device_has_to_stay, AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()),
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
                            stringResource(R.string.settings_default_min, AppSettings.DEFAULT_PERSISTENCE_THRESHOLD_MIN.toInt()),
                            "${AppSettings.MAX_PERSISTENCE_THRESHOLD_MIN.toInt()} min"
                        )
                    )
                }

                // ── Trusted devices ──────────────────────────────────────────────
                item(key = "§trusted-hdr") {
                    SectionHeader(stringResource(R.string.settings_trusted_devices), supporting = if (trusted.devices.isEmpty()) null else "${trusted.devices.size}")
                }
                if (trusted.devices.isEmpty()) {
                    item(key = "§trusted-empty") {
                        EmptyState(
                            title = stringResource(R.string.settings_no_trusted_devices_yet),
                            body = stringResource(R.string.settings_mark_a_flagged_device_as_yours),
                            icon = ClairixIcons.BluetoothSearching
                        )
                    }
                } else {
                    item(key = "§trusted") {
                        ListGroup {
                            trusted.devices.sortedBy { it.addresses.first() }.forEachIndexed { i, device ->
                                if (i > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        device.label?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = c.text) }
                                        Text(device.addresses.joinToString("\n"), style = MaterialTheme.typography.mono, color = c.textMuted)
                                        // A device that changes its address is trusted a day at a time.
                                        if (device.until != 0L) {
                                            Text(
                                                stringResource(
                                                    R.string.settings_trusted_until,
                                                    java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date(device.until))
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = c.textSubtle
                                            )
                                        }
                                    }
                                    GhostButton(stringResource(R.string.settings_remove), onClick = { Registry.untrust(device.addresses.first()) }, tone = Tone.Danger)
                                }
                            }
                        }
                    }
                }
            }

            // ── Battery ──────────────────────────────────────────────────────
            //
            // Low power and what each feature runs, in both editions; see BatterySettings.kt.
            batterySection()

            // ── Location ─────────────────────────────────────────────────────
            //
            // Where position fixes come from, in both editions: the Comms edition reads
            // a position for an SOS. See app.clairix.analysis.Positioning.
            item(key = "§location-hdr") { SectionHeader(stringResource(R.string.settings_location)) }

            item(key = "§location") {
                ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_satellite_only_positioning),
                        subtitle = if (satelliteOnly)
                            stringResource(R.string.settings_positions_come_from_the_satellite_receiver)
                        else
                            stringResource(R.string.settings_one_worked_out_from_wi_fi, (if (BuildConfig.PLAY_SERVICES) stringResource(R.string.settings_positions_come_from_google_play_services) else stringResource(R.string.settings_positions_come_from_androids_own_location))),
                        checked = satelliteOnly,
                        onCheckedChange = { v ->
                            satelliteOnly = v
                            app.clairix.analysis.Positioning.setSatelliteOnly(context, v)
                        }
                    )
                    if (BuildConfig.FULL_ACCESS) {
                        Hairline()
                        ToggleRow(
                            title = stringResource(R.string.settings_cross_check_gps_against_wi_fi),
                            subtitle = when {
                                satelliteOnly ->
                                    stringResource(R.string.settings_off_while_satellite_only_positioning_is)
                                networkCrossCheck ->
                                    stringResource(R.string.settings_the_gps_check_compares_the_satellite)
                                else ->
                                    stringResource(R.string.settings_the_satellite_position_is_not_compared)
                            },
                            checked = networkCrossCheck && !satelliteOnly,
                            enabled = !satelliteOnly,
                            onCheckedChange = { v ->
                                networkCrossCheck = v
                                app.clairix.analysis.Positioning.setNetworkCrossCheck(context, v)
                            }
                        )
                    }
                }
            }

            // ── SOS ──────────────────────────────────────────────────────────
            //
            // The SOS sheet's optional text and emergency call, in both editions
            // (SosExtrasRun.kt). Both off until turned on.
            item(key = "§sos-hdr") { SectionHeader("SOS") }
            item(key = "§sos") { SosExtrasSettingsCard() }

            // ── Passcode lock ────────────────────────────────────────────────
            item(key = "§lock-hdr") { SectionHeader(stringResource(R.string.settings_app_passcode)) }

            item(key = "§lock-controls") {
                ClairixCard(spacing = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconWell(Icons.Filled.Lock, if (lockEnabled) Tone.Safe else Tone.Neutral)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (lockEnabled) stringResource(R.string.settings_passcode_on) else stringResource(R.string.settings_passcode_off),
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text
                            )
                            Text(
                                if (lockEnabled)
                                    if (wipeAfter > 0)
                                        pluralStringResource(R.plurals.settings_lock_on_wipe_after, wipeAfter, wipeAfter)
                                    else
                                        stringResource(R.string.settings_clairix_locks_when_you_leave_each)
                                else
                                    stringResource(R.string.settings_set_a_6_to_12_digit),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSubtle
                            )
                        }
                    }
                    if (lockEnabled && pausedWipe > 0 && !wipeChosen) {
                        // Earlier versions armed this limit with every new passcode and asked
                        // nothing; AppLock holds it back until she answers.
                        Notice(
                            Tone.Caution,
                            stringResource(R.string.settings_keep_it_or_pick_another_under, pausedWipeNote(appResources(), pausedWipe)),
                            title = stringResource(R.string.settings_check_your_wipe_limit),
                            action = {
                                GhostButton(
                                    stringResource(R.string.settings_keep, pausedWipe),
                                    onClick = {
                                        val keep = pausedWipe
                                        wipeAfter = keep
                                        wipeChosen = true
                                        scope.launch(Dispatchers.IO) { AppLock.setWipeAfter(context, keep) }
                                    }
                                )
                            }
                        )
                    }
                    if (lockEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.settings_change), onClick = { showPasscodeSetup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
                            SecondaryButton(stringResource(R.string.settings_turn_off), onClick = { showPasscodeDisable = true }, tone = Tone.Danger, modifier = Modifier.weight(1f))
                        }
                        if (canUseBiometric) {
                            Hairline()
                            ToggleRow(
                                title = stringResource(R.string.settings_biometric_unlock),
                                subtitle = stringResource(R.string.settings_use_fingerprint_or_face_instead_of),
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
                            title = stringResource(R.string.settings_wrong_passcode_limit),
                            subtitle = stringResource(R.string.settings_how_many_wrong_guesses_in_a),
                            choices = AppLock.WIPE_AFTER_CHOICES,
                            selected = wipeAfter,
                            label = { wipeAfterLabel(it) },
                            onSelect = { choice ->
                                wipeAfter = choice
                                wipeChosen = true
                                scope.launch(Dispatchers.IO) { AppLock.setWipeAfter(context, choice) }
                            }
                        )
                        SChoiceRow(
                            title = stringResource(R.string.settings_lock_after),
                            subtitle = stringResource(R.string.settings_how_long_clairix_can_sit_in),
                            choices = AppLock.RELOCK_CHOICES,
                            selected = relockAfterMs,
                            label = { relockLabel(it) },
                            onSelect = { choice ->
                                relockAfterMs = choice
                                scope.launch(Dispatchers.IO) { AppLock.setRelockAfterMs(context, choice) }
                            }
                        )
                        Hairline()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.settings_duress_code), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                                StatusPill(if (hasDuress) stringResource(R.string.settings_set) else stringResource(R.string.settings_not_set), if (hasDuress) Tone.Safe else Tone.Neutral)
                            }
                            Text(
                                stringResource(R.string.settings_a_second_code_that_quietly_wipes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSubtle
                            )
                        }
                        if (hasDuress) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(stringResource(R.string.settings_change), onClick = { showDuressSetup = true }, tone = Tone.Accent, modifier = Modifier.weight(1f))
                                SecondaryButton(stringResource(R.string.settings_remove), onClick = { showDuressRemove = true }, tone = Tone.Danger, modifier = Modifier.weight(1f))
                            }
                        } else {
                            SecondaryButton(stringResource(R.string.settings_set_duress_code), onClick = { showDuressSetup = true }, tone = Tone.Accent, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        PrimaryButton(stringResource(R.string.settings_set_passcode), onClick = { showPasscodeSetup = true }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (showPasscodeSetup) {
                item(key = "§lock-setup") {
                    PasscodeSetupPanel(
                        context = context,
                        isChanging = lockEnabled,
                        onDone = {
                            showPasscodeSetup = false
                            wipeAfter = AppLock.wipeAfter(context)
                            wipeChosen = AppLock.wipeChoiceConfirmed(context)
                        },
                        onCancel = { showPasscodeSetup = false },
                        onExportEvidence = if (BuildConfig.FULL_ACCESS) { { EvidenceExport.request() } } else null
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

            // ── Notifications ────────────────────────────────────────────────
            //
            // What an alert may say in the shade of the unlocked phone. The lock
            // screen is not up to this switch: the alert channels are secret there.

            if (BuildConfig.FULL_ACCESS) item(key = "§notif-hdr") { SectionHeader(stringResource(R.string.settings_notifications)) }

            if (BuildConfig.FULL_ACCESS) item(key = "§notif-toggles") {
                ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_show_alert_details_in_notifications),
                        subtitle = if (showAlertDetails)
                            stringResource(R.string.settings_while_clairix_is_unlocked_an_alert)
                        else
                            stringResource(R.string.settings_every_alert_says_only_unlock_to),
                        checked = showAlertDetails,
                        onCheckedChange = { v ->
                            showAlertDetails = v
                            AppSettings.setShowAlertDetails(context, v)
                        }
                    )
                }
            }

            // ── Discreet mode ────────────────────────────────────────────────
            //
            // Both editions: a plain launcher entry where the edition allows it,
            // neutral notifications and lock screen, and quick exit (DiscreetModeSettings.kt).

            discreetModeItems()

            // ── Data management ──────────────────────────────────────────────
            //
            // Evidence export and the scanner's clear-all. Giving up the Clairix
            // number is under Wipe and delete below (and in Comms settings).

            if (BuildConfig.FULL_ACCESS) item(key = "§data-hdr") { SectionHeader(stringResource(R.string.settings_data)) }

            if (BuildConfig.FULL_ACCESS) item(key = "§data-actions") {
                ClairixCard(spacing = 8.dp) {
                    Text(
                        stringResource(R.string.settings_detection_data_stays_on_this_phone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    var retentionDays by remember { mutableStateOf(HistoryRetention.days(context)) }
                    SChoiceRow(
                        title = stringResource(R.string.settings_keep_history_for),
                        subtitle = stringResource(R.string.settings_the_location_log_the_timeline_vehicle),
                        choices = HistoryRetention.CHOICES,
                        selected = retentionDays,
                        label = { retentionLabel(it) },
                        onSelect = { choice ->
                            retentionDays = choice
                            scope.launch(Dispatchers.IO) {
                                HistoryRetention.setDays(context, choice)
                                HistoryRetention.apply(context)
                            }
                        }
                    )
                    // With a passcode set, each export asks for it (or a biometric) every time.
                    EvidenceExportButtons(stringResource(R.string.settings_export_evidence), modifier = Modifier.fillMaxWidth(), primary = true)
                    SecondaryButton(stringResource(R.string.settings_clear_all_data_2), onClick = { showClearDialog = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                }
            }

            // ── Wipe and delete ──────────────────────────────────────────────

            item(key = "§wipe-hdr") { SectionHeader(stringResource(R.string.settings_wipe_and_delete)) }

            item(key = "§wipe") {
                ClairixCard(spacing = 8.dp) {
                    Text(stringResource(R.string.settings_wipe_clairix), style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        stringResource(R.string.settings_erases_everything_clairix_holds_on_this),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    SecondaryButton(stringResource(R.string.settings_wipe_clairix), onClick = { showWipe = true }, tone = Tone.Danger, modifier = Modifier.fillMaxWidth())
                    Hairline(Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.settings_delete_identity_and_number), style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        if (comms.registered)
                            stringResource(R.string.settings_gives_up_your_clairix_number_the)
                        else
                            stringResource(R.string.settings_this_phone_has_no_clairix_number),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    SecondaryButton(
                        stringResource(R.string.settings_delete_identity_and_number),
                        onClick = { confirmUnpair = true },
                        tone = Tone.Danger,
                        enabled = comms.registered && !comms.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Supporter ────────────────────────────────────────────────────
            //
            // Relay-cost limits only (docs/SUPPORTER.md); nothing above asks.
            supporterItems(onOpen = { showSupporter = true })

            // ── About ────────────────────────────────────────────────────────

            item(key = "§about-hdr") { SectionHeader(stringResource(R.string.settings_about)) }

            if (update.offer) item(key = "§update") { app.clairix.ui.UpdateOffer(update) }

            item(key = "§about") {
                ClairixCard(spacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogoMark(size = 40.dp, contentDescription = null)
                        Column {
                            Text(context.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.text)
                            Text(stringResource(R.string.settings_nothing_hides), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                        }
                    }
                    KeyValueRow(stringResource(R.string.settings_version), stringResource(R.string.settings_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                    KeyValueRow(stringResource(R.string.settings_package), BuildConfig.APPLICATION_ID)
                    KeyValueRow(stringResource(R.string.settings_edition), if (BuildConfig.FULL_ACCESS) stringResource(R.string.settings_full_scanner_tools_and_comms) else stringResource(R.string.settings_comms_only), mono = false)
                    SecondaryButton(stringResource(R.string.settings_take_the_tour), onClick = onTour, tone = Tone.Accent)
                    CrashReportSection()

                    if (BuildConfig.FULL_ACCESS) {
                        Hairline(Modifier.padding(vertical = 4.dp))
                        Text(stringResource(R.string.settings_legal), style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            stringResource(R.string.settings_clairix_is_a_counter_surveillance_research),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSubtle
                        )
                    }

                    Hairline(Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        stringResource(R.string.settings_copyright_2026_the_clairix_authors_clairix),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSubtle
                    )
                    if (BuildConfig.FULL_ACCESS) {
                        Text(
                            stringResource(R.string.settings_the_stalkerware_list_is_converted_from),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSubtle
                        )
                    }
                    // Map data credit (ODbL) and the third-party licences row.
                    AboutCredits(onOpenLicences = { showLicences = true })

                    Hairline(Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text(
                        if (BuildConfig.FULL_ACCESS)
                            stringResource(R.string.settings_detection_data_stays_on_this_phone_2, (if (BuildConfig.CARD_VAULT) stringResource(R.string.settings_the_timeline_and_the_card_vault) else stringResource(R.string.settings_and_the_timeline)), tileServer.name, (if (BuildConfig.PLAY_SERVICES) stringResource(R.string.settings_google_play_services_where_the_phone) else ""))
                        else
                            stringResource(R.string.settings_messages_and_calls_are_end_to, (if (BuildConfig.PLAY_SERVICES) stringResource(R.string.settings_google_play_services_where_the_phone) else "")),
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

@Composable
private fun retentionLabel(days: Int): String = when (days) {
    HistoryRetention.FOREVER -> stringResource(R.string.settings_forever)
    else -> pluralStringResource(R.plurals.settings_retention_days, days, days)
}

@Composable
private fun wipeAfterLabel(attempts: Int): String = when (attempts) {
    0 -> stringResource(R.string.settings_never_lock_out_instead)
    else -> pluralStringResource(R.plurals.settings_wipe_attempts, attempts, attempts)
}

/** What a wipe takes, in the words the dialog after a new passcode uses. */
internal fun wipeWarning(res: Resources): String =
    res.getString(if (BuildConfig.FULL_ACCESS) R.string.settings_wipe_warning_full else R.string.settings_wipe_warning_comms)

/** What a pick in [WipeChoiceDialog] means, in [AppLock]'s own numbers. */
internal fun wipeAfterOutcome(res: Resources, choice: Int?): String = when (choice) {
    null -> res.getString(R.string.settings_wipe_outcome_unpicked)
    0 -> res.getString(R.string.settings_wipe_outcome_lockout)
    1 -> res.getString(R.string.settings_wipe_outcome_first)
    else -> res.getQuantityString(R.plurals.settings_wipe_outcome_after, choice, choice)
}

/**
 * What wrong passcodes should do, asked the moment a passcode is saved, and
 * after the next unlock on a phone whose limit an earlier version set
 * ([WipeLimitQuestion], with a [note] that says so). It has no Cancel and
 * nothing is picked to begin with, so the owner makes the choice herself:
 * earlier versions armed a wipe after 3 without a word. Where there is
 * evidence to lose it offers to export it first. It is a window of its own,
 * above the lock screen Clairix draws, so callers show it only while Clairix
 * is unlocked ([wipeQuestionShown]).
 */
@Composable
internal fun WipeChoiceDialog(onExportEvidence: (() -> Unit)?, note: String? = null, onChosen: (Int) -> Unit) {
    var picked by remember { mutableStateOf<Int?>(null) }
    ClairixDialog(
        title = stringResource(R.string.settings_what_should_happen_after_wrong_passcodes),
        // Back and a tap outside do nothing: this needs an answer.
        onDismiss = {},
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { picked?.let(onChosen) },
        confirmEnabled = picked != null,
        dismissLabel = null
    ) {
        // Scrolls when large text makes it taller than the dialog.
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
            Text(wipeWarning(appResources()), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.text)
            ChoiceChips<Int?>(
                choices = AppLock.WIPE_AFTER_CHOICES,
                selected = picked,
                label = { wipeAfterLabel(it ?: 0) },
                onSelect = { picked = it }
            )
            Text(wipeAfterOutcome(appResources(), picked), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
            if (onExportEvidence != null) {
                SecondaryButton(
                    stringResource(R.string.settings_export_evidence),
                    onClick = onExportEvidence,
                    icon = Icons.Filled.Share,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun relockLabel(ms: Long): String = when (ms) {
    0L -> stringResource(R.string.settings_immediately)
    30_000L -> stringResource(R.string.settings_30_seconds)
    else -> (ms / 60_000L).toInt().let { pluralStringResource(R.plurals.settings_relock_minutes, it, it) }
}

/** A titled setting with its choices as chips underneath; the picked one is filled. */
@Composable
private fun <T> SChoiceRow(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
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
            GhostButton(stringResource(R.string.settings_cancel), onClick = onCancel, tone = Tone.Neutral)
            Spacer(Modifier.weight(1f))
            PrimaryButton(confirmLabel, onClick = onConfirm, tone = confirmTone)
        }
    }
}

@Composable
private fun WarningText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.danger)
}

/**
 * Setting or changing the passcode, step by step: in Settings, inline on the
 * onboarding page that asks whether anyone else uses the phone, and under the
 * banner on the pages that show the owner's routine. Saving a passcode on a
 * phone where nobody has chosen what wrong passcodes do goes straight on to
 * [WipeChoiceDialog]; [onDone] comes once that is answered.
 */
@Composable
internal fun PasscodeSetupPanel(
    context: Context,
    isChanging: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onExportEvidence: (() -> Unit)? = null
) {
    var step by remember { mutableIntStateOf(if (isChanging) 0 else 1) }
    // step 0 = confirm current, step 1 = enter new, step 2 = confirm new
    var code1 by remember { mutableStateOf("") }
    var code2 by remember { mutableStateOf("") }
    var currentCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var choosingWipe by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locked by AppLock.locked.collectAsStateWithLifecycle()

    // Hidden while the lock is up, and back once it is passed: its Export and its
    // limits are for the owner alone.
    if (wipeQuestionShown(asking = choosingWipe, locked = locked)) {
        WipeChoiceDialog(
            onExportEvidence = onExportEvidence,
            onChosen = { choice ->
                scope.launch {
                    withContext(Dispatchers.IO) { AppLock.setWipeAfter(context, choice) }
                    choosingWipe = false
                    onDone()
                }
            }
        )
    }

    CodePanel(
        title = when {
            isChanging && step == 0 -> stringResource(R.string.settings_enter_your_current_passcode)
            step == 1 -> stringResource(R.string.settings_choose_a_new_passcode)
            else -> stringResource(R.string.settings_confirm_the_new_passcode)
        },
        note = if (step == 1) stringResource(R.string.settings_6_to_12_digits) else null,
        error = error,
        value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
        fieldLabel = if (step == 0) stringResource(R.string.settings_current_passcode) else stringResource(R.string.settings_digits),
        onValueChange = { digits ->
            when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
            error = ""
        },
        confirmLabel = if (step < 2) stringResource(R.string.settings_next) else stringResource(R.string.settings_save),
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
                            is AppLock.Verdict.Rejected -> error = context.getString(R.string.settings_wrong_passcode)
                            is AppLock.Verdict.LockedOut -> error = context.getString(R.string.settings_too_many_attempts_try_again_later)
                            AppLock.Verdict.Wiping -> onCancel()
                            is AppLock.Verdict.Unavailable -> error = context.getString(R.string.settings_keystore_unavailable)
                        }
                    }
                    1 -> {
                        AppLock.problem(code1)?.let { error = it; return@launch }
                        step = 2
                    }
                    2 -> {
                        if (code1 != code2) { error = context.getString(R.string.settings_those_passcodes_dont_match); return@launch }
                        val r = withContext(Dispatchers.IO) {
                            AppLock.setPasscode(context, code1)
                        }
                        r.onSuccess {
                            // A first passcode never arms a wipe by itself: the owner picks.
                            if (AppLock.wipeChoiceConfirmed(context)) onDone() else choosingWipe = true
                        }.onFailure { error = it.message ?: context.getString(R.string.settings_couldnt_save_the_passcode) }
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
        title = stringResource(R.string.settings_enter_your_passcode_to_turn_the),
        note = null,
        error = error,
        value = code,
        fieldLabel = stringResource(R.string.settings_current_passcode),
        onValueChange = { code = it; error = "" },
        confirmLabel = stringResource(R.string.settings_turn_off),
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
                    is AppLock.Verdict.Rejected -> error = context.getString(R.string.settings_wrong_passcode)
                    is AppLock.Verdict.LockedOut -> error = context.getString(R.string.settings_too_many_attempts_try_again_later)
                    AppLock.Verdict.Wiping -> onCancel()
                    is AppLock.Verdict.Unavailable -> error = context.getString(R.string.settings_keystore_unavailable)
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
            0 -> stringResource(R.string.settings_confirm_your_passcode)
            1 -> stringResource(R.string.settings_choose_a_duress_code)
            else -> stringResource(R.string.settings_confirm_the_duress_code)
        },
        note = if (step != 0) stringResource(R.string.settings_6_to_12_digits_different_from) else null,
        error = error,
        value = when (step) { 0 -> currentCode; 1 -> code1; else -> code2 },
        fieldLabel = if (step == 0) stringResource(R.string.settings_current_passcode) else stringResource(R.string.settings_digits),
        onValueChange = { digits ->
            when (step) { 0 -> currentCode = digits; 1 -> code1 = digits; else -> code2 = digits }
            error = ""
        },
        confirmLabel = if (step < 2) stringResource(R.string.settings_next) else stringResource(R.string.settings_save),
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
                            is AppLock.Verdict.Rejected -> error = context.getString(R.string.settings_wrong_passcode)
                            is AppLock.Verdict.LockedOut -> error = context.getString(R.string.settings_too_many_attempts_try_again_later)
                            AppLock.Verdict.Wiping -> onCancel()
                            is AppLock.Verdict.Unavailable -> error = context.getString(R.string.settings_keystore_unavailable)
                        }
                    }
                    1 -> {
                        AppLock.problem(code1)?.let { error = it; return@launch }
                        step = 2
                    }
                    else -> {
                        if (code1 != code2) { error = context.getString(R.string.settings_those_codes_dont_match); return@launch }
                        val r = withContext(Dispatchers.IO) {
                            AppLock.setDuress(context, code1)
                        }
                        r.onSuccess { onDone() }.onFailure { error = it.message ?: context.getString(R.string.settings_couldnt_save_the_duress_code) }
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
        title = stringResource(R.string.settings_enter_your_passcode_to_remove_the),
        note = null,
        error = error,
        value = code,
        fieldLabel = stringResource(R.string.settings_passcode),
        onValueChange = { code = it; error = "" },
        confirmLabel = stringResource(R.string.settings_remove),
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
                    is AppLock.Verdict.Rejected -> error = context.getString(R.string.settings_wrong_passcode)
                    is AppLock.Verdict.LockedOut -> error = context.getString(R.string.settings_too_many_attempts_try_again_later)
                    AppLock.Verdict.Wiping -> onCancel()
                    is AppLock.Verdict.Unavailable -> error = context.getString(R.string.settings_keystore_unavailable)
                }
            }
        },
        onCancel = onCancel
    )
}
