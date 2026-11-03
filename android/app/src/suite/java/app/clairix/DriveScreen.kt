package app.clairix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.components.Tone
import app.clairix.ui.components.ButtonKind
import app.clairix.ui.components.ClairixButton
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.Notice
import app.clairix.ui.components.ToggleRow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clairix.nav.DrivePower
import app.clairix.nav.NavScreen
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import app.clairix.ui.theme.monoSmall
import app.clairix.ui.theme.monoLarge
import app.clairix.ui.theme.mono
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.SecondaryButton

private const val RPM_MAX = 8000f
private const val RPM_REDLINE_FRACTION = 0.75f
private const val COOLANT_COLD_C = 70
private const val COOLANT_HOT_C = 100
private const val KPH_TO_MPH = 0.621f

private const val SECTION_NAVIGATE = 0
private const val SECTION_RADIO = 1
private const val SECTION_VEHICLE = 2

private val DRIVE_SECTIONS = listOf(
    SECTION_NAVIGATE to "Navigate",
    SECTION_RADIO to "Radio",
    SECTION_VEHICLE to "Vehicle"
)

/**
 * The Drive tab: turn-by-turn navigation, the radio receivers on the SDR stick
 * (scanner and FM audio, ADS-B aircraft), or the vehicle itself over OBD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(viewModel: DriveViewModel = viewModel()) {
    var section by rememberSaveable { mutableIntStateOf(SECTION_NAVIGATE) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                DRIVE_SECTIONS.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = section == id,
                        onClick = { section = id },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DRIVE_SECTIONS.size)
                    ) { Text(label) }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    SECTION_NAVIGATE -> NavScreen(modifier = Modifier.fillMaxSize())
                    SECTION_RADIO -> DriveSensors(viewModel, showRadio = true, showVehicle = false)
                    else -> DriveSensors(viewModel, showRadio = false, showVehicle = true)
                }
            }
        }
    }
}

@Composable
private fun DriveSensors(viewModel: DriveViewModel, showRadio: Boolean, showVehicle: Boolean) {
    val obd by viewModel.obdData.collectAsStateWithLifecycle()
    val sdrState by viewModel.sdrState.collectAsStateWithLifecycle()
    val channels by viewModel.scannerChannels.collectAsStateWithLifecycle()
    val activeChannel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val squelchOpen by viewModel.squelchOpen.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val aircraft by viewModel.aircraft.collectAsStateWithLifecycle()
    val driveModeActive by viewModel.driveModeActive.collectAsStateWithLifecycle()
    val squelchLevel by viewModel.squelchLevel.collectAsStateWithLifecycle()
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    val lowBattery by viewModel.lowBattery.collectAsStateWithLifecycle()
    val recordingEnabled by viewModel.recordingEnabled.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val legacy by viewModel.legacyRecordings.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val radioNoticeAcked by viewModel.radioNoticeAcked.collectAsStateWithLifecycle()

    // The gauges are read faster while they are on screen; see DriveViewModel.setScreenVisible.
    LifecycleResumeEffect(viewModel, showRadio) {
        viewModel.setScreenVisible(true)
        if (showRadio) viewModel.refreshRecordings()
        onPauseOrDispose { viewModel.setScreenVisible(false) }
    }

    DriveContent(
        showRadio = showRadio,
        showVehicle = showVehicle,
        obd = obd,
        sdrState = sdrState,
        channels = channels,
        activeChannel = activeChannel,
        squelchOpen = squelchOpen,
        isRecording = isRecording,
        aircraft = aircraft,
        driveModeActive = driveModeActive,
        squelchLevel = squelchLevel,
        onSetSquelch = viewModel::setSquelch,
        onAddChannel = viewModel::addChannel,
        onRemoveChannel = viewModel::removeChannel,
        onSetChannelActive = viewModel::setChannelActive,
        onConnectObd = viewModel::connectObd,
        onDisconnectObd = viewModel::disconnectObd,
        onToggleDriveMode = {
            if (driveModeActive) viewModel.deactivateDriveMode() else viewModel.activateDriveMode()
        },
        radio = radio,
        lowBattery = lowBattery,
        onReconnect = viewModel::reconnectSdr,
        recordingEnabled = recordingEnabled,
        onSetRecording = viewModel::setRecording,
        recordings = recordings,
        legacyRecordings = legacy,
        playing = playing,
        onPlay = viewModel::playRecording,
        onPlayLegacy = viewModel::playLegacyRecording,
        onStopPlayback = viewModel::stopPlayback,
        onDeleteRecording = viewModel::deleteRecording,
        onDeleteAllRecordings = viewModel::deleteAllRecordings,
        onImportLegacy = viewModel::importLegacyRecordings,
        onDeleteLegacy = viewModel::deleteLegacyRecording,
        onDeleteAllLegacy = viewModel::deleteLegacyRecordings,
        radioNoticeAcked = radioNoticeAcked,
        onAckRadioNotice = viewModel::ackRadioNotice
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DriveContent(
    showRadio: Boolean,
    showVehicle: Boolean,
    obd: ObdData,
    sdrState: SdrState,
    channels: List<ScannerChannel>,
    activeChannel: ScannerChannel?,
    squelchOpen: Boolean,
    isRecording: Boolean,
    aircraft: Map<String, Aircraft>,
    driveModeActive: Boolean,
    squelchLevel: Float,
    onSetSquelch: (Float) -> Unit,
    onAddChannel: (String, Long) -> Unit,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit,
    onConnectObd: () -> Unit,
    onDisconnectObd: () -> Unit,
    onToggleDriveMode: () -> Unit,
    radio: DriveLink.Radio = DriveLink.Radio.OFF,
    lowBattery: Boolean = false,
    onReconnect: () -> Unit = {},
    recordingEnabled: Boolean = false,
    onSetRecording: (Boolean) -> Unit = {},
    recordings: List<ScannerRecordings.Info> = emptyList(),
    legacyRecordings: List<ScannerRecordings.LegacyRecording> = emptyList(),
    playing: String? = null,
    onPlay: (String) -> Unit = {},
    onPlayLegacy: (String) -> Unit = {},
    onStopPlayback: () -> Unit = {},
    onDeleteRecording: (String) -> Unit = {},
    onDeleteAllRecordings: () -> Unit = {},
    onImportLegacy: () -> Unit = {},
    onDeleteLegacy: (String) -> Unit = {},
    onDeleteAllLegacy: () -> Unit = {},
    radioNoticeAcked: Boolean = true,
    onAckRadioNotice: () -> Unit = {}
) {
    var scannerExpanded by remember { mutableStateOf(true) }
    var adsbExpanded by remember { mutableStateOf(true) }
    var obdExpanded by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    // The first drive mode, or the first channel, waits for the radio notice to be read.
    var pendingRadioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun afterRadioNotice(action: () -> Unit) {
        if (radioNoticeAcked) action() else pendingRadioAction = action
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBarRow(obd = obd, sdrState = sdrState, radio = radio)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (lowBattery) Notice(
                    Tone.Caution,
                    stringResource(R.string.drive_the_battery_is_below_and_not, DrivePower.CUTOFF_PCT),
                    title = stringResource(R.string.drive_battery_low)
                )
                if (showRadio && driveModeActive) RadioStickNotice(radio = radio, sdrState = sdrState, onReconnect = onReconnect)
                if (showRadio) ScannerCard(
                    expanded = scannerExpanded,
                    onToggle = { scannerExpanded = !scannerExpanded },
                    channels = channels,
                    activeChannel = activeChannel,
                    squelchOpen = squelchOpen,
                    isRecording = isRecording,
                    squelchLevel = squelchLevel,
                    onSquelchChange = onSetSquelch,
                    onAddClick = { afterRadioNotice { showAddDialog = true } },
                    onRemoveChannel = onRemoveChannel,
                    onSetChannelActive = onSetChannelActive,
                    recordingEnabled = recordingEnabled,
                    onSetRecording = onSetRecording,
                    recordings = recordings,
                    legacyRecordings = legacyRecordings,
                    playing = playing,
                    onPlay = onPlay,
                    onPlayLegacy = onPlayLegacy,
                    onStopPlayback = onStopPlayback,
                    onDeleteRecording = onDeleteRecording,
                    onDeleteAllRecordings = onDeleteAllRecordings,
                    onImportLegacy = onImportLegacy,
                    onDeleteLegacy = onDeleteLegacy,
                    onDeleteAllLegacy = onDeleteAllLegacy
                )
                if (showRadio) AdsbCard(
                    expanded = adsbExpanded,
                    onToggle = { adsbExpanded = !adsbExpanded },
                    aircraft = aircraft.values.toList()
                )
                if (showVehicle) ObdCard(
                    expanded = obdExpanded,
                    onToggle = { obdExpanded = !obdExpanded },
                    obd = obd,
                    paused = lowBattery,
                    onConnect = onConnectObd,
                    onDisconnect = onDisconnectObd
                )
            }

            // What drive mode costs, said where it is switched on.
            Text(
                style = MaterialTheme.typography.bodySmall,
                text = stringResource(R.string.drive_drive_mode_keeps_the_radio_stick, DrivePower.CUTOFF_PCT),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 88.dp, top = 8.dp)
            )
            // Clear of the SOS button in the bottom end corner.
            ClairixButton(
                if (driveModeActive) stringResource(R.string.drive_turn_off_drive_mode) else stringResource(R.string.drive_turn_on_drive_mode),
                onClick = { if (driveModeActive) onToggleDriveMode() else afterRadioNotice(onToggleDriveMode) },
                kind = if (driveModeActive) ButtonKind.Secondary else ButtonKind.Primary,
                tone = if (driveModeActive) Tone.Danger else Tone.Accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 88.dp, top = 8.dp, bottom = 12.dp)
                    .heightIn(min = 56.dp)
            )
        }
    }

    if (showAddDialog) {
        AddChannelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, freqHz ->
                onAddChannel(name, freqHz)
                showAddDialog = false
            }
        )
    }

    pendingRadioAction?.let { action ->
        RadioNoticeDialog(
            onAccept = {
                pendingRadioAction = null
                onAckRadioNotice()
                action()
            },
            onDismiss = { pendingRadioAction = null }
        )
    }
}

/**
 * Shown once, before the first drive mode or the first channel: receiving, and above all
 * recording, other people's radio traffic is restricted in some places, and only the
 * owner can know where they are.
 */
@Composable
private fun RadioNoticeDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ClairixDialog(
        title = stringResource(R.string.drive_before_you_listen),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.drive_i_understand),
        onConfirm = onAccept,
        text = stringResource(R.string.drive_receiving_and_recording_radio_traffic_is)
    ) {
        GhostButton(
            stringResource(R.string.drive_read_the_terms),
            onClick = { openLink(context, Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)), "No app on this phone can open $TERMS_URL") }
        )
    }
}

/** What happened to the stick while drive mode is on, and the way back. */
@Composable
private fun RadioStickNotice(radio: DriveLink.Radio, sdrState: SdrState, onReconnect: () -> Unit) {
    val reconnect: @Composable () -> Unit = { SecondaryButton(stringResource(R.string.drive_reconnect), onClick = onReconnect, tone = Tone.Accent) }
    when {
        radio == DriveLink.Radio.UNPLUGGED -> Notice(
            Tone.Caution,
            stringResource(R.string.drive_the_radio_stick_was_unplugged_so),
            title = stringResource(R.string.drive_radio_stick_unplugged)
        )
        radio == DriveLink.Radio.NEEDS_PERMISSION -> Notice(
            Tone.Caution,
            stringResource(R.string.drive_the_radio_stick_is_plugged_in),
            title = stringResource(R.string.drive_radio_stick_plugged_in),
            action = reconnect
        )
        radio == DriveLink.Radio.ON && sdrState is SdrState.Error -> Notice(
            Tone.Caution,
            stringResource(R.string.drive_the_radio_stopped_check_the_stick, sdrState.msg),
            title = stringResource(R.string.drive_radio_stopped),
            action = reconnect
        )
        else -> Unit
    }
}

@Composable
private fun StatusBarRow(obd: ObdData, sdrState: SdrState, radio: DriveLink.Radio) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    style = MaterialTheme.typography.headlineLarge,
                    text = obd.speedKph?.toString() ?: "--",
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    style = MaterialTheme.typography.bodyMedium,
                    text = "km/h",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            val mph = obd.speedKph?.let { (it * KPH_TO_MPH).roundToInt() }
            Text(
                style = MaterialTheme.typography.bodyMedium,
                text = if (mph != null) "$mph mph" else stringResource(R.string.drive_mph),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SdrIndicator(sdrState, radio)
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(
                color = if (obd.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(6.dp))
            Text(
                style = MaterialTheme.typography.bodyMedium,
                text = if (obd.connected) obd.deviceName ?: "OBD" else stringResource(R.string.drive_no_obd),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun SdrIndicator(state: SdrState, radio: DriveLink.Radio) {
    val ok = ClairixTheme.colors.safe
    val caution = ClairixTheme.colors.caution
    val bad = ClairixTheme.colors.danger
    val pulse = rememberInfiniteTransition(label = "sdrPulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sdrPulseAlpha"
    )
    val dotColor: Color
    val dotAlpha: Float
    val label: String
    when {
        radio == DriveLink.Radio.LOW_BATTERY -> {
            dotColor = caution
            dotAlpha = 1f
            label = stringResource(R.string.drive_paused_battery_low)
        }
        radio == DriveLink.Radio.UNPLUGGED -> {
            dotColor = bad
            dotAlpha = 1f
            label = stringResource(R.string.drive_stick_unplugged)
        }
        radio == DriveLink.Radio.NEEDS_PERMISSION -> {
            dotColor = caution
            dotAlpha = 1f
            label = stringResource(R.string.drive_stick_needs_permission)
        }
        state is SdrState.Ready -> {
            dotColor = ok
            dotAlpha = 1f
            label = stringResource(R.string.drive_sdr_ready)
        }
        state is SdrState.Opening -> {
            dotColor = caution
            dotAlpha = pulse.value
            label = stringResource(R.string.scan_opening)
        }
        state is SdrState.Error -> {
            dotColor = bad
            dotAlpha = 1f
            label = state.msg
        }
        else -> {
            dotColor = bad
            dotAlpha = 1f
            label = stringResource(R.string.drive_sdr_closed)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = dotColor, modifier = Modifier.alpha(dotAlpha))
        Spacer(Modifier.width(6.dp))
        Text(
            style = MaterialTheme.typography.bodyMedium,
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CardHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            style = MaterialTheme.typography.titleLarge,
            text = title,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        trailing()
        Spacer(Modifier.weight(1f))
        Text(
            style = MaterialTheme.typography.bodyMedium,
            text = if (expanded) "▲" else "▼",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScannerCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    channels: List<ScannerChannel>,
    activeChannel: ScannerChannel?,
    squelchOpen: Boolean,
    isRecording: Boolean,
    squelchLevel: Float,
    onSquelchChange: (Float) -> Unit,
    onAddClick: () -> Unit,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit,
    recordingEnabled: Boolean,
    onSetRecording: (Boolean) -> Unit,
    recordings: List<ScannerRecordings.Info>,
    legacyRecordings: List<ScannerRecordings.LegacyRecording>,
    playing: String?,
    onPlay: (String) -> Unit,
    onPlayLegacy: (String) -> Unit,
    onStopPlayback: () -> Unit,
    onDeleteRecording: (String) -> Unit,
    onDeleteAllRecordings: () -> Unit,
    onImportLegacy: () -> Unit,
    onDeleteLegacy: (String) -> Unit,
    onDeleteAllLegacy: () -> Unit
) {
    ClairixCard(contentPadding = PaddingValues(0.dp), spacing = 0.dp) {
        CardHeader(title = stringResource(R.string.drive_scanner), expanded = expanded, onToggle = onToggle) {
            if (isRecording) {
                val pulse = rememberInfiniteTransition(label = "recPulse").animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "recPulseAlpha"
                )
                StatusDot(
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.alpha(pulse.value),
                    size = 12
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    style = MaterialTheme.typography.titleSmall,
                    text = "REC",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActiveChannelDisplay(activeChannel)
                SquelchWave(squelchOpen = squelchOpen)
                ChannelList(
                    channels = channels,
                    onRemoveChannel = onRemoveChannel,
                    onSetChannelActive = onSetChannelActive
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        text = "${channels.size} channels",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = onAddClick) {
                        Text("+", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        text = stringResource(R.string.drive_squelch),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = squelchLevel,
                        onValueChange = onSquelchChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        style = MaterialTheme.typography.mono,
                        text = "${(squelchLevel * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                ToggleRow(
                    title = stringResource(R.string.drive_record_transmissions),
                    checked = recordingEnabled,
                    onCheckedChange = onSetRecording,
                    subtitle = stringResource(R.string.drive_turns_itself_off_when_drive_mode)
                )
                RecordingList(
                    recordings = recordings,
                    playing = playing,
                    onPlay = onPlay,
                    onStop = onStopPlayback,
                    onDelete = onDeleteRecording,
                    onDeleteAll = onDeleteAllRecordings
                )
                if (legacyRecordings.isNotEmpty()) LegacyRecordings(
                    legacy = legacyRecordings,
                    playing = playing,
                    onPlay = onPlayLegacy,
                    onStop = onStopPlayback,
                    onImport = onImportLegacy,
                    onDelete = onDeleteLegacy,
                    onDeleteAll = onDeleteAllLegacy
                )
            }
        }
    }
}

/** The recordings on this phone, newest first, each with play and delete. */
@Composable
private fun RecordingList(
    recordings: List<ScannerRecordings.Info>,
    playing: String?,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit
) {
    if (recordings.isEmpty()) return
    var confirmAll by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                style = MaterialTheme.typography.titleSmall,
                text = stringResource(R.string.drive_recordings, recordings.size, formatMegabytes(recordings.sumOf { it.bytes })),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            GhostButton(stringResource(R.string.drive_delete_all), onClick = { confirmAll = true }, tone = Tone.Danger)
        }
        recordings.forEach { r ->
            RecordingRow(
                title = if (r.readable) "${formatWhen(r.startedAt)} · ${formatMhz(r.freqHz)}" else stringResource(R.string.drive_a_recording_that_will_not_open),
                detail = when {
                    !r.readable -> stringResource(R.string.drive_damaged_it_can_only_be_deleted)
                    else -> listOfNotNull(
                        r.channel.takeIf { it.isNotBlank() },
                        formatDuration(r.durationMs),
                        if (r.complete) null else stringResource(R.string.drive_cut_short)
                    ).joinToString(" · ")
                },
                playing = playing == r.id,
                canPlay = r.readable,
                onPlay = { onPlay(r.id) },
                onStop = onStop,
                onDelete = { onDelete(r.id) }
            )
        }
    }
    if (confirmAll) {
        ClairixDialog(
            title = stringResource(R.string.drive_delete_every_recording),
            onDismiss = { confirmAll = false },
            confirmLabel = stringResource(R.string.drive_delete_all),
            confirmTone = Tone.Danger,
            onConfirm = {
                confirmAll = false
                onDeleteAll()
            },
            text = stringResource(R.string.drive_recordings_are_deleted_from_this_phone, recordings.size)
        )
    }
}

/**
 * Recordings from before they were sealed: still plain audio in the app's external storage.
 * They are offered, never moved or deleted unasked.
 */
@Composable
private fun LegacyRecordings(
    legacy: List<ScannerRecordings.LegacyRecording>,
    playing: String?,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit
) {
    var confirmAll by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Notice(
            Tone.Caution,
            stringResource(R.string.drive_recordings_from_an_earlier_version_are, legacy.size, formatMegabytes(legacy.sumOf { it.bytes })),
            title = stringResource(R.string.drive_unencrypted_recordings),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.drive_encrypt), onClick = onImport, tone = Tone.Accent)
                    GhostButton(stringResource(R.string.drive_delete_them), onClick = { confirmAll = true }, tone = Tone.Danger)
                }
            }
        )
        legacy.forEach { r ->
            val key = r.file.absolutePath
            RecordingRow(
                title = "${formatWhen(r.startedAt)} · ${formatMhz(r.freqHz)}",
                detail = stringResource(R.string.drive_unencrypted, formatDuration(r.bytes * 1000 / (ScannerRecordings.LEGACY_SAMPLE_RATE * 2L))),
                playing = playing == key,
                canPlay = true,
                onPlay = { onPlay(key) },
                onStop = onStop,
                onDelete = { onDelete(key) }
            )
        }
    }
    if (confirmAll) {
        ClairixDialog(
            title = stringResource(R.string.drive_delete_the_unencrypted_recordings),
            onDismiss = { confirmAll = false },
            confirmLabel = stringResource(R.string.drive_delete_them),
            confirmTone = Tone.Danger,
            onConfirm = {
                confirmAll = false
                onDeleteAll()
            },
            text = stringResource(R.string.drive_recordings_from_an_earlier_version_are_2, legacy.size)
        )
    }
}

@Composable
private fun RecordingRow(
    title: String,
    detail: String,
    playing: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                style = MaterialTheme.typography.bodyMedium,
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                style = MaterialTheme.typography.bodySmall,
                text = detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canPlay) GhostButton(if (playing) stringResource(R.string.comms_stop) else stringResource(R.string.comms_play), onClick = if (playing) onStop else onPlay)
        GhostButton(stringResource(R.string.settings_delete), onClick = onDelete, tone = Tone.Danger)
    }
}

@Composable
private fun ActiveChannelDisplay(channel: ScannerChannel?) {
    if (channel == null) {
        Text(
            style = MaterialTheme.typography.bodyLarge,
            text = stringResource(R.string.drive_no_active_channel),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column {
        Text(
            style = MaterialTheme.typography.monoLarge,
            text = formatMhz(channel.freqHz),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            style = MaterialTheme.typography.bodyMedium,
            text = channel.name,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SquelchWave(squelchOpen: Boolean) {
    val pulse = rememberInfiniteTransition(label = "wavePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "wavePulseAlpha"
    )
    val phase = rememberInfiniteTransition(label = "wavePhase").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200)),
        label = "wavePhaseValue"
    )
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline
    val color = if (squelchOpen) activeColor else idleColor
    val alpha = if (squelchOpen) pulse.value else 0.5f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(alpha)
    ) {
        val midY = size.height / 2f
        val path = Path()
        val steps = 96
        val amplitude = if (squelchOpen) size.height * 0.4f else size.height * 0.04f
        for (i in 0..steps) {
            val x = size.width * i / steps
            val t = i.toFloat() / steps
            val envelope = sin(PI * t).toFloat()
            val y = midY + amplitude * envelope * sin(t * 6f * PI.toFloat() + phase.value)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
        if (!squelchOpen) {
            drawLine(
                color = color,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    channels: List<ScannerChannel>,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit
) {
    if (channels.isEmpty()) {
        Text(
            style = MaterialTheme.typography.bodyMedium,
            text = stringResource(R.string.drive_no_channels_saved),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
    ) {
        items(channels, key = { it.freqHz }) { channel ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        onRemoveChannel(channel.freqHz)
                        true
                    } else {
                        false
                    }
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            style = MaterialTheme.typography.titleSmall,
                            text = stringResource(R.string.settings_delete),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        style = MaterialTheme.typography.bodyLarge,
                        text = channel.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        style = MaterialTheme.typography.mono,
                        text = formatMhz(channel.freqHz),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = channel.active,
                        onCheckedChange = { onSetChannelActive(channel.freqHz, it) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AdsbCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    aircraft: List<Aircraft>
) {
    val anyEmergency = aircraft.any { it.isEmergency }
    ClairixCard(contentPadding = PaddingValues(0.dp), spacing = 0.dp) {
        CardHeader(title = stringResource(R.string.drive_aircraft), expanded = expanded, onToggle = onToggle) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (anyEmergency) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    style = MaterialTheme.typography.titleSmall,
                    text = aircraft.size.toString(),
                    color = if (anyEmergency) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                if (aircraft.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            style = MaterialTheme.typography.bodyLarge,
                            text = stringResource(R.string.drive_no_aircraft_detected),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            style = MaterialTheme.typography.bodyMedium,
                            text = stringResource(R.string.drive_sdr_must_be_on_1090_mhz),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val sorted = remember(aircraft) {
                        aircraft.sortedWith(
                            compareByDescending<Aircraft> { it.isEmergency }.thenBy { it.icao }
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(sorted, key = { it.icao }) { plane ->
                            AircraftRow(plane)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AircraftRow(plane: Aircraft) {
    val emergency = plane.isEmergency
    val rowBackground = if (emergency) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    val textColor = if (emergency) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface
    val dimColor = if (emergency) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                style = MaterialTheme.typography.mono,
                text = plane.callsign?.takeIf { it.isNotBlank() } ?: plane.icao,
                color = textColor
            )
            if (emergency) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            style = MaterialTheme.typography.labelSmall,
                            text = "EMERG",
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        style = MaterialTheme.typography.monoSmall,
                        text = plane.squawk ?: "----",
                        color = textColor
                    )
                }
            }
        }
        Text(
            style = MaterialTheme.typography.monoSmall,
            text = plane.altitudeFt?.let { "$it ft" } ?: stringResource(R.string.drive_ft),
            color = dimColor,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            style = MaterialTheme.typography.monoSmall,
            text = plane.speedKts?.let { "$it kts" } ?: stringResource(R.string.drive_kts),
            color = dimColor,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            style = MaterialTheme.typography.headlineMedium,
            text = "↑",
            color = textColor,
            modifier = Modifier
                .padding(start = 6.dp)
                .rotate(plane.headingDeg?.toFloat() ?: 0f)
                .alpha(if (plane.headingDeg == null) 0.3f else 1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObdCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    obd: ObdData,
    paused: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    ClairixCard(contentPadding = PaddingValues(0.dp), spacing = 0.dp) {
        CardHeader(title = "OBD-II", expanded = expanded, onToggle = onToggle) {
            Text(
                style = MaterialTheme.typography.bodyMedium,
                text = when {
                    obd.connected -> obd.deviceName ?: stringResource(R.string.scan_connected)
                    paused -> stringResource(R.string.drive_paused_battery_low)
                    else -> stringResource(R.string.scan_not_connected)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RpmGauge(rpm = obd.rpm)
                CoolantRow(tempC = obd.coolantTempC)

                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            style = MaterialTheme.typography.bodyMedium,
                            text = stringResource(R.string.drive_throttle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            style = MaterialTheme.typography.mono,
                            text = obd.throttlePct?.let { "$it%" } ?: "--",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { ((obd.throttlePct ?: 0) / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                }

                if (obd.dtcs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        obd.dtcs.forEach { code ->
                            AssistChip(
                                onClick = {},
                                label = { Text(code, style = MaterialTheme.typography.monoSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                    }
                }

                if (obd.connected) {
                    GhostButton(stringResource(R.string.drive_disconnect), onClick = onDisconnect)
                } else {
                    SecondaryButton(stringResource(R.string.drive_connect_obd), onClick = onConnect)
                }
            }
        }
    }
}

@Composable
private fun RpmGauge(rpm: Int?) {
    val fraction = ((rpm ?: 0) / RPM_MAX).coerceIn(0f, 1f)
    val low = MaterialTheme.colorScheme.primary
    val high = MaterialTheme.colorScheme.error
    val barColor = if (fraction <= RPM_REDLINE_FRACTION) {
        low
    } else {
        lerp(low, high, (fraction - RPM_REDLINE_FRACTION) / (1f - RPM_REDLINE_FRACTION))
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            style = MaterialTheme.typography.mono,
            text = rpm?.let { "$it RPM" } ?: "--",
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CoolantRow(tempC: Int?) {
    val color = when {
        tempC == null -> MaterialTheme.colorScheme.onSurfaceVariant
        tempC < COOLANT_COLD_C -> MaterialTheme.colorScheme.secondary
        tempC <= COOLANT_HOT_C -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = color, size = 12)
        Spacer(Modifier.width(10.dp))
        Text(
            style = MaterialTheme.typography.bodyMedium,
            text = stringResource(R.string.drive_coolant),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            style = MaterialTheme.typography.monoLarge,
            text = tempC?.let { "$it °C" } ?: "--",
            color = color
        )
    }
}

@Composable
private fun AddChannelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var freq by remember { mutableStateOf("") }
    val freqMhz = freq.toDoubleOrNull()
    val minMhz = SdrUsbDriver.MIN_FREQ_HZ / 1_000_000.0
    val maxMhz = SdrUsbDriver.MAX_FREQ_HZ / 1_000_000.0
    val valid = name.isNotBlank() && freqMhz != null && freqMhz in minMhz..maxMhz

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ClairixShapes.Sheet,
        containerColor = ClairixTheme.colors.surfaceRaised,
        title = { Text(stringResource(R.string.drive_add_channel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.drive_channel_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = freq,
                    onValueChange = { freq = it },
                    label = { Text("Frequency (MHz, %.0f–%.0f)".format(Locale.US, minMhz, maxMhz)) },
                    isError = freq.isNotEmpty() && (freqMhz == null || freqMhz !in minMhz..maxMhz),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            GhostButton(
                "OK",
                onClick = {
                        if (valid && freqMhz != null) {
                            onConfirm(name.trim(), (freqMhz * 1_000_000.0).roundToLong())
                        }
                    },
                enabled = valid
            )
        },
        dismissButton = {
            GhostButton(stringResource(R.string.settings_cancel), onClick = onDismiss)
        }
    )
}

private fun formatMhz(freqHz: Long): String =
    String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0)

/** When a recording was made: the date and time in the phone's own format. */
private fun formatWhen(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))

private fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

private const val TERMS_URL = "https://clairix.app/terms"

private fun openLink(context: Context, intent: Intent, otherwise: String) {
    val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!started) Toast.makeText(context, otherwise, Toast.LENGTH_LONG).show()
}

@Preview(showBackground = true)
@Composable
fun DriveScreenPreview() {
    ClairixTheme {
        DriveContent(
            showRadio = true,
            showVehicle = true,
            obd = ObdData(),
            sdrState = SdrState.Closed,
            channels = emptyList(),
            activeChannel = null,
            squelchOpen = false,
            isRecording = false,
            aircraft = emptyMap(),
            driveModeActive = false,
            squelchLevel = 0.3f,
            onSetSquelch = {},
            onAddChannel = { _, _ -> },
            onRemoveChannel = {},
            onSetChannelActive = { _, _ -> },
            onConnectObd = {},
            onDisconnectObd = {},
            onToggleDriveMode = {}
        )
    }
}
