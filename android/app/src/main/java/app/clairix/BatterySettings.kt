package app.clairix

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.BatteryUse
import app.clairix.analysis.LowPower
import app.clairix.analysis.LowPowerMode
import app.clairix.analysis.ScanEffort
import app.clairix.analysis.ScanPower
import app.clairix.comms.CommsRepository
import app.clairix.comms.LinkTiming
import app.clairix.comms.mesh.Mesh
import app.clairix.nav.DrivePower
import app.clairix.ui.appResources
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.ToggleRow
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Battery: the "Low power" switch ([LowPower]), what the phone has used since
 * the scan started ([BatteryUse]) and one line per feature with what it runs, from the
 * app's own counters ([ScanEffort]). No milliamp figure is invented: Android tells an
 * app the battery level and charge, not its own draw.
 */
internal fun LazyListScope.batterySection() {
    item(key = "§battery-hdr") { SectionHeader(stringResource(R.string.settings_battery)) }
    item(key = "§battery") { BatteryCard() }
}

/** How often the readout is taken again while it is on screen. */
private const val REFRESH_MS = 30_000L

@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val c = ClairixTheme.colors
    val low by LowPowerMode.state.collectAsStateWithLifecycle()
    val scan by Registry.status.collectAsStateWithLifecycle()
    val comms by CommsRepository.state.collectAsStateWithLifecycle()
    val mesh by Mesh.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Only while this card is composed, which it is only while it is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }
    val sample = remember(now, scan.scanning) { LowPowerMode.sample(context, now) }
    val effort = remember(now, scan.scanning) { ScanPower.effort }
    val snapshot = remember(now, effort) { effort?.snapshot(now) }
    val text = BatteryText(appResources())

    ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        ToggleRow(
            title = stringResource(R.string.settings_low_power),
            subtitle = text.lowPower(low.reason),
            checked = low.switchOn,
            onCheckedChange = { v -> LowPowerMode.setSwitch(context, v) }
        )
        if (BuildConfig.FULL_ACCESS) {
            Hairline()
            CostLine(
                stringResource(R.string.settings_used_since_the_scan_started),
                text.used(
                    use = if (effort != null && sample != null) ScanPower.use(sample) else null,
                    scanning = effort != null,
                    charging = sample?.charging == true,
                    scanStartedAt = effort?.startedAt
                )
            )
            Hairline()
            CostLine(stringResource(R.string.settings_bluetooth_scan), snapshot?.let { text.bluetooth(it) } ?: text.scannerOff)
            CostLine(stringResource(R.string.settings_position), snapshot?.let { text.position(it) } ?: text.scannerOff)
            CostLine(stringResource(R.string.settings_cell_and_wi_fi_checks), snapshot?.let { text.checks(it) } ?: text.scannerOff)
        }
        CostLine(stringResource(R.string.settings_comms), text.comms(comms.online && CommsRepository.isRegistered(), CommsRepository.coverTraffic(), low.active))
        CostLine(stringResource(R.string.settings_offline_mesh), text.mesh(mesh.running, mesh.fullPace))
        if (BuildConfig.FULL_ACCESS) {
            CostLine(stringResource(R.string.settings_drive_mode), text.drive)
            CostLine(stringResource(R.string.settings_navigation), text.navigation)
        }
        Text(
            text.footnote,
            style = MaterialTheme.typography.bodySmall,
            color = c.textSubtle,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun CostLine(title: String, body: String) {
    val c = ClairixTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSubtle)
    }
}

/** The words of Settings > Battery, apart from the screen so they can be tested. */
internal class BatteryText(private val res: Resources) {

    val scannerOff: String get() = res.getString(R.string.battery_scanner_off)

    val drive: String get() = res.getString(R.string.battery_drive, DrivePower.CUTOFF_PCT)

    val navigation: String get() = res.getString(R.string.battery_navigation, DrivePower.CUTOFF_PCT)

    val footnote: String get() = res.getString(R.string.battery_footnote)

    private val lowPowerDoes: String get() = res.getString(R.string.battery_low_power_does)

    /** The switch's line: whether low power is on and why, and what it does. */
    fun lowPower(reason: LowPower.Reason?): String = when (reason) {
        LowPower.Reason.SWITCH -> res.getString(R.string.battery_low_power_switch, lowPowerDoes)
        LowPower.Reason.POWER_SAVE -> res.getString(R.string.battery_low_power_saver, lowPowerDoes)
        LowPower.Reason.BATTERY -> res.getString(
            R.string.battery_low_power_battery, LowPower.AUTO_BELOW_PCT, LowPower.AUTO_RESUME_PCT, lowPowerDoes
        )
        null -> res.getString(R.string.battery_low_power_off, LowPower.AUTO_BELOW_PCT, lowPowerDoes)
    }

    /** "Used since the scan started": the whole phone's drain, said as such; see [BatteryUse]. */
    fun used(use: BatteryUse.Use?, scanning: Boolean, charging: Boolean, scanStartedAt: Long?): String {
        if (!scanning) return scannerOff
        if (charging) return res.getString(R.string.battery_charging_now)
        if (use == null) return res.getString(R.string.battery_waiting_for_reading)
        val parts = buildList {
            use.pct?.let { add(res.getString(R.string.battery_percent, it)) }
            use.mAh?.let { add(res.getString(R.string.battery_mah, it)) }
        }
        val amount = when (parts.size) {
            0 -> res.getString(R.string.battery_no_level)
            1 -> parts[0]
            else -> res.getString(R.string.battery_percent_and_mah, parts[0], parts[1])
        }
        val rate = use.pctPerHour?.let { res.getString(R.string.battery_rate, String.format(Locale.US, "%.1f", it)) } ?: ""
        val since = if (scanStartedAt != null && use.sinceAt - scanStartedAt > UNPLUG_NOTE_MS) {
            res.getString(R.string.battery_since_unplugged, SimpleDateFormat("HH:mm", Locale.US).format(Date(use.sinceAt)))
        } else ""
        return res.getString(R.string.battery_used, amount, duration(use.elapsedMs), since, rate)
    }

    /** The Bluetooth scan's time in each mode; the duty cycles are Android's defaults, which phone makers may change. */
    fun bluetooth(s: ScanEffort.Snapshot): String {
        if (s.bleMs == 0L) return res.getString(R.string.battery_not_running)
        val modes = buildList {
            if (s.bleLowPowerMs > 0) add(res.getString(R.string.battery_ble_low_power, duration(s.bleLowPowerMs)))
            if (s.bleBalancedMs > 0) add(res.getString(R.string.battery_ble_balanced, duration(s.bleBalancedMs)))
            if (s.bleLowLatencyMs > 0) add(res.getString(R.string.battery_ble_low_latency, duration(s.bleLowLatencyMs)))
        }
        val batched = if (s.bleBatchedMs > 0) res.getString(R.string.battery_ble_batched, duration(s.bleBatchedMs)) else ""
        return res.getString(
            R.string.battery_ble_running, duration(s.bleMs), modes.joinToString(res.getString(R.string.battery_list_separator)), batched
        )
    }

    /** Time moving (precise fixes every few seconds) and settled (an occasional coarse fix). */
    fun position(s: ScanEffort.Snapshot): String {
        if (s.movingMs == 0L && s.settledMs == 0L) return res.getString(R.string.battery_no_positions)
        return res.getString(R.string.battery_position, duration(s.movingMs), duration(s.settledMs))
    }

    /** How often the modem and Android's Wi-Fi scan were asked. */
    fun checks(s: ScanEffort.Snapshot): String = res.getString(
        R.string.battery_checks,
        res.getQuantityString(R.plurals.battery_cell_readings, s.cellReadings, s.cellReadings),
        res.getQuantityString(R.plurals.battery_wifi_scans, s.wifiScans, s.wifiScans)
    )

    /** The relay connection and cover traffic. */
    fun comms(online: Boolean, coverTraffic: Boolean, lowPower: Boolean): String {
        if (!online) return res.getString(R.string.battery_comms_offline)
        val cover = when {
            !coverTraffic -> ""
            lowPower -> res.getString(R.string.battery_cover_paused)
            else -> res.getString(R.string.battery_cover_on)
        }
        val minutes = (LinkTiming.HEARTBEAT_MS / 60_000L).toInt()
        return res.getQuantityString(R.plurals.battery_comms_online, minutes, minutes, cover)
    }

    /** The mesh's radio pace. */
    fun mesh(running: Boolean, fullPace: Boolean): String = when {
        !running -> res.getString(R.string.battery_mesh_off)
        fullPace -> res.getString(R.string.battery_mesh_full)
        else -> res.getString(R.string.battery_mesh_low)
    }

    /** "45 min", "3 h 5 min", "under a minute". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> res.getString(R.string.battery_under_a_minute)
            minutes < 60 -> res.getString(R.string.battery_minutes, minutes)
            minutes % 60 == 0L -> res.getString(R.string.battery_hours, minutes / 60)
            else -> res.getString(R.string.battery_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    private companion object {
        /** A start later than the scan's by more than this was an unplug after a charge. */
        const val UNPLUG_NOTE_MS = 60_000L
    }
}
