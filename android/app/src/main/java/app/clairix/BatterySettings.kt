package app.clairix

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
    item(key = "§battery-hdr") { SectionHeader("Battery") }
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

    ClairixCard(spacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        ToggleRow(
            title = "Low power",
            subtitle = BatteryText.lowPower(low.reason),
            checked = low.switchOn,
            onCheckedChange = { v -> LowPowerMode.setSwitch(context, v) }
        )
        if (BuildConfig.FULL_ACCESS) {
            Hairline()
            CostLine(
                "Used since the scan started",
                BatteryText.used(
                    use = if (effort != null && sample != null) ScanPower.use(sample) else null,
                    scanning = effort != null,
                    charging = sample?.charging == true,
                    scanStartedAt = effort?.startedAt
                )
            )
            Hairline()
            CostLine("Bluetooth scan", snapshot?.let { BatteryText.bluetooth(it) } ?: BatteryText.SCANNER_OFF)
            CostLine("Position", snapshot?.let { BatteryText.position(it) } ?: BatteryText.SCANNER_OFF)
            CostLine("Cell and Wi-Fi checks", snapshot?.let { BatteryText.checks(it) } ?: BatteryText.SCANNER_OFF)
        }
        CostLine("Comms", BatteryText.comms(comms.online && CommsRepository.isRegistered(), CommsRepository.coverTraffic(), low.active))
        CostLine("Offline mesh", BatteryText.mesh(mesh.running, mesh.fullPace))
        if (BuildConfig.FULL_ACCESS) {
            CostLine("Drive mode", BatteryText.DRIVE)
            CostLine("Navigation", BatteryText.NAVIGATION)
        }
        Text(
            BatteryText.FOOTNOTE,
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
internal object BatteryText {

    const val SCANNER_OFF = "Counted while the scanner runs."

    const val DRIVE = "Only while drive mode is on: the radio stick streams and the OBD-II adapter is polled. " +
        "The stick is powered from this phone's battery through the USB port. Below ${DrivePower.CUTOFF_PCT} % " +
        "with no charger, both stop until the phone charges."

    const val NAVIGATION = "Only during a route: a position every second, spoken directions and the traffic " +
        "checks. Below ${DrivePower.CUTOFF_PCT} % with no charger, traffic, speed reports and the check for a " +
        "faster route slow to every 5 minutes."

    const val FOOTNOTE = "These are Clairix's own counts of what it runs, not measured current: Android does not " +
        "tell an app what it draws. Android's battery page shows Clairix's share. Measured figures per phone " +
        "are pending."

    private const val LOW_POWER_DOES = "The scanner counts the phone as settled after 2 minutes still instead of 4, " +
        "scans Bluetooth at low power, reads the cell every 2 minutes and the Wi-Fi every 15; cover traffic " +
        "pauses and the mesh stays at its quiet pace unless it has something to carry. SOS, the duress " +
        "passcode and incoming calls are never slowed."

    /** The switch's line: whether low power is on and why, and what it does. */
    fun lowPower(reason: LowPower.Reason?): String = when (reason) {
        LowPower.Reason.SWITCH -> "On. $LOW_POWER_DOES"
        LowPower.Reason.POWER_SAVE -> "On while Android's battery saver is on. $LOW_POWER_DOES"
        LowPower.Reason.BATTERY -> "On: the battery is below ${LowPower.AUTO_BELOW_PCT} % and not charging. " +
            "It goes off when the phone charges or reaches ${LowPower.AUTO_RESUME_PCT} %. $LOW_POWER_DOES"
        null -> "Off. Comes on by itself below ${LowPower.AUTO_BELOW_PCT} % with no charger, and while " +
            "Android's battery saver is on. $LOW_POWER_DOES"
    }

    /** "Used since the scan started": the whole phone's drain, said as such; see [BatteryUse]. */
    fun used(use: BatteryUse.Use?, scanning: Boolean, charging: Boolean, scanStartedAt: Long?): String {
        if (!scanning) return SCANNER_OFF
        if (charging) return "Charging now. Counting starts again when the phone is unplugged."
        if (use == null) return "Waiting for a battery reading."
        val parts = buildList {
            use.pct?.let { add("$it %") }
            use.mAh?.let { add("$it mAh") }
        }
        val amount = when (parts.size) {
            0 -> "The phone did not report its level"
            1 -> parts[0]
            else -> "${parts[0]} (${parts[1]})"
        }
        val rate = use.pctPerHour?.let { ", about ${String.format(Locale.US, "%.1f", it)} % an hour" } ?: ""
        val since = if (scanStartedAt != null && use.sinceAt - scanStartedAt > UNPLUG_NOTE_MS) {
            " since the phone was unplugged at ${SimpleDateFormat("HH:mm", Locale.US).format(Date(use.sinceAt))}"
        } else ""
        return "$amount in ${duration(use.elapsedMs)}$since$rate. The whole phone's use, not Clairix's alone."
    }

    /** The Bluetooth scan's time in each mode; the duty cycles are Android's defaults, which phone makers may change. */
    fun bluetooth(s: ScanEffort.Snapshot): String {
        if (s.bleMs == 0L) return "Not running."
        val modes = buildList {
            if (s.bleLowPowerMs > 0) add("${duration(s.bleLowPowerMs)} at low power (listening about a tenth of the time)")
            if (s.bleBalancedMs > 0) add("${duration(s.bleBalancedMs)} balanced (about a quarter)")
            if (s.bleLowLatencyMs > 0) add("${duration(s.bleLowLatencyMs)} at low latency (all the time)")
        }
        val batched = if (s.bleBatchedMs > 0) " Results handed over in batches for ${duration(s.bleBatchedMs)}." else ""
        return "Running ${duration(s.bleMs)}: ${modes.joinToString(", ")}.$batched"
    }

    /** Time moving (precise fixes every few seconds) and settled (an occasional coarse fix). */
    fun position(s: ScanEffort.Snapshot): String {
        if (s.movingMs == 0L && s.settledMs == 0L) return "No positions: location access is off."
        return "Moving ${duration(s.movingMs)}, with a precise fix every 15 seconds; settled ${duration(s.settledMs)}, " +
            "with a coarse fix every 3 minutes."
    }

    /** How often the modem and Android's Wi-Fi scan were asked. */
    fun checks(s: ScanEffort.Snapshot): String =
        "${s.cellReadings} cell ${plural(s.cellReadings, "reading", "readings")} from the modem, " +
            "${s.wifiScans} Wi-Fi ${plural(s.wifiScans, "scan", "scans")} asked of Android."

    /** The relay connection and cover traffic. */
    fun comms(online: Boolean, coverTraffic: Boolean, lowPower: Boolean): String {
        if (!online) return "Offline: no connection is kept open."
        val cover = when {
            !coverTraffic -> ""
            lowPower -> " Cover traffic is paused while low power is on."
            else -> " Cover traffic sends an envelope at random intervals."
        }
        return "Online: one connection to your relay, which sends a heartbeat about every " +
            "${LinkTiming.HEARTBEAT_MS / 60_000L} minutes when nothing is happening.$cover"
    }

    /** The mesh's radio pace. */
    fun mesh(running: Boolean, fullPace: Boolean): String = when {
        !running -> "Off."
        fullPace -> "On, at full pace: scanning and advertising over Bluetooth."
        else -> "On, listening and advertising at low power."
    }

    /** "45 min", "3 h 5 min", "under a minute". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    /** A start later than the scan's by more than this was an unplug after a charge. */
    private const val UNPLUG_NOTE_MS = 60_000L
}
