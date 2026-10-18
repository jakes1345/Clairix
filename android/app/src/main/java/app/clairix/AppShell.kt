package app.clairix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CameraFinding
import app.clairix.analysis.HiddenCameraScanner

/*
 * The app shell: four top-level tabs (Scan, Comms, Drive, Activity) under a top
 * app bar that always carries the Settings gear. Scan opens on a hub of
 * category cards; each card opens that category's screen, which the top bar's
 * back arrow and the system back button both close again.
 *
 * What the destinations are, and the status line each hub card shows, live
 * here. Which existing screen is drawn for each destination is decided in
 * MainApp, which holds the callbacks those screens need.
 */

// ── Colour tokens (the app's dark scheme, as every screen file keeps them) ──

private val ShGround   = Color(0xFF0E1116)
private val ShPanel    = Color(0xFF161B23)
private val ShPanelHi  = Color(0xFF1D2430)
private val ShInk      = Color(0xFFE6EAF1)
private val ShInkDim   = Color(0xFFA8B2C1)
private val ShMuted    = Color(0xFF6F7A8B)
private val ShRule     = Color(0xFF262E3A)
private val ShAccent   = Color(0xFFFF7A3D)
private val ShCritical = Color(0xFFF2545B)
private val ShCaution  = Color(0xFFE8B33D)
private val ShClear    = Color(0xFF3DB88A)

private val HubCardShape = RoundedCornerShape(8.dp)

// ── Destinations ─────────────────────────────────────────────────────────────

/** The bottom-bar tabs, in bar order. */
internal enum class HomeTab(val label: String) {
    SCAN("Scan"),
    COMMS("Comms"),
    DRIVE("Drive"),
    ACTIVITY("Activity");

    val icon: ImageVector
        get() = when (this) {
            SCAN -> ClairixIcons.TrackChanges
            COMMS -> ClairixIcons.Forum
            DRIVE -> ClairixIcons.DirectionsCar
            ACTIVITY -> ClairixIcons.History
        }
}

/**
 * The tabs a build has. The comms-only build carries nothing but COMMS: no
 * scanner, no tools, no drive or activity features.
 */
internal fun homeTabsFor(fullAccess: Boolean): List<HomeTab> =
    if (fullAccess) HomeTab.entries.toList() else listOf(HomeTab.COMMS)

/** The cards on the Scan hub, in hub order. [subTabs] is empty for a single-screen category. */
internal enum class ScanCategory(val title: String, val description: String, val subTabs: List<String>) {
    TRACKERS("Trackers", "Bluetooth trackers travelling with you", listOf("Nearby", "Map")),
    CAMERAS("Hidden cameras", "Sweep a room for cameras", emptyList()),
    CELL("Cell towers", "Fake towers and forced downgrades", emptyList()),
    WIFI("Wi-Fi", "Rogue hotspots and unsafe networks", emptyList()),
    NFC("NFC", "Tags, cards and skimmers", emptyList()),
    PHONE("Phone check", "Spyware, risky settings and certificates", listOf("Health", "Stalkerware", "Certificates"));

    val icon: ImageVector
        get() = when (this) {
            TRACKERS -> ClairixIcons.BluetoothSearching
            CAMERAS -> ClairixIcons.Videocam
            CELL -> ClairixIcons.CellTower
            WIFI -> ClairixIcons.Wifi
            NFC -> ClairixIcons.Nfc
            PHONE -> ClairixIcons.PhoneAndroid
        }
}

/** The on-demand actions inside Trackers, each opened as its own screen. */
internal enum class TrackerAction(val title: String) {
    LOCATE("Find a tracker"),
    SELF_TEST("Self-test beacon");

    val icon: ImageVector
        get() = when (this) {
            LOCATE -> ClairixIcons.TrackChanges
            SELF_TEST -> ClairixIcons.BluetoothSearching
        }
}

/** The safety pages reached from the Comms menu, each opened as its own screen. */
internal enum class CommsAction(val title: String) {
    FAKE_CALL("Fake call"),
    DURESS("Duress phrase");

    val icon: ImageVector
        get() = when (this) {
            FAKE_CALL -> ClairixIcons.PhoneInTalk
            DURESS -> ClairixIcons.Shield
        }
}

/** The Activity tab's sub-tabs, in order. */
internal val ACTIVITY_SUB_TABS = listOf("Timeline", "Patterns", "Unlocks")

/**
 * Looks up an enum constant by the name kept in saved state, or null. Saved
 * names outlive an app update, so a name that no longer exists reads as none.
 */
internal inline fun <reified E : Enum<E>> enumByName(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

// ── Hub status lines ─────────────────────────────────────────────────────────

internal enum class StatusTone { NEUTRAL, CLEAR, CAUTION, ALERT }

/** The one-line summary under a hub card's title, and how worrying it is. */
internal data class CategoryStatus(val text: String, val tone: StatusTone) {
    val alert: Boolean get() = tone == StatusTone.ALERT
}

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/** A finding at or above this confidence counts as strong on its own, as the sweep screen counts it. */
private const val STRONG_CAMERA_CONFIDENCE = 0.7f

internal fun trackerStatus(status: ScanStatus, detections: List<Detection>, trusted: Set<String>): CategoryStatus {
    val following = detections.count { it.following && it.address !in trusted }
    return when {
        following > 0 -> CategoryStatus("${count(following, "tracker")} following you", StatusTone.ALERT)
        !status.scanning -> CategoryStatus("Scanner off", StatusTone.NEUTRAL)
        !status.bluetoothOn -> CategoryStatus("Bluetooth is off", StatusTone.CAUTION)
        else -> CategoryStatus("${count(status.nearbyCount, "device")} nearby, none following", StatusTone.CLEAR)
    }
}

internal fun cameraStatus(findings: List<CameraFinding>): CategoryStatus {
    val strong = findings.count { it.confidence >= STRONG_CAMERA_CONFIDENCE }
    return when {
        strong > 0 -> CategoryStatus(count(strong, "strong finding"), StatusTone.ALERT)
        findings.isNotEmpty() -> CategoryStatus("${count(findings.size, "possible finding")} to check", StatusTone.CAUTION)
        else -> CategoryStatus("No findings", StatusTone.NEUTRAL)
    }
}

internal fun cellStatus(cell: CellStatus): CategoryStatus = when {
    !cell.available -> CategoryStatus("No tower reading", StatusTone.NEUTRAL)
    !cell.analysing -> CategoryStatus("Scanner off, towers not checked", StatusTone.NEUTRAL)
    cell.level.ordinal >= Threat.HIGH.ordinal -> CategoryStatus("Suspicious tower detected", StatusTone.ALERT)
    cell.level == Threat.MEDIUM -> CategoryStatus("Unusual tower activity", StatusTone.CAUTION)
    !cell.mature -> CategoryStatus("Learning your usual towers", StatusTone.NEUTRAL)
    else -> CategoryStatus("No suspicious towers", StatusTone.CLEAR)
}

internal fun wifiStatus(anomalies: List<WifiAnomaly>, wifi: WifiStatus, scanning: Boolean): CategoryStatus {
    val risky = anomalies.count { it.threat.ordinal >= Threat.HIGH.ordinal }
    return when {
        risky > 0 -> CategoryStatus("${count(risky, "risky network")} nearby", StatusTone.ALERT)
        anomalies.isNotEmpty() -> CategoryStatus("${count(anomalies.size, "network")} to review", StatusTone.CAUTION)
        scanning || wifi.scannedTs > 0L -> CategoryStatus("No risky networks found", StatusTone.CLEAR)
        else -> CategoryStatus("Not checked yet", StatusTone.NEUTRAL)
    }
}

internal fun nfcStatus(tags: List<NfcTag>): CategoryStatus {
    val suspicious = tags.count { it.suspicious }
    return when {
        suspicious > 0 -> CategoryStatus(count(suspicious, "suspicious tag"), StatusTone.ALERT)
        tags.isNotEmpty() -> CategoryStatus("${count(tags.size, "tag")} read, none suspicious", StatusTone.CLEAR)
        else -> CategoryStatus("No tags read yet", StatusTone.NEUTRAL)
    }
}

internal fun phoneStatus(health: PhoneHealth): CategoryStatus {
    val issues = health.findings.size + health.problems.size
    return when {
        health.findingsScannedTs == 0L && health.facts.isEmpty() -> CategoryStatus("Not checked yet", StatusTone.NEUTRAL)
        // A live microphone or camera is what makes the level HIGH here; it was a
        // red dot on the old Device tab and stays an alert on the hub.
        health.level.ordinal >= Threat.HIGH.ordinal && issues == 0 && health.sensorActive ->
            CategoryStatus("Camera or microphone in use", StatusTone.ALERT)
        health.level.ordinal >= Threat.HIGH.ordinal -> CategoryStatus("${count(issues, "issue")} to fix", StatusTone.ALERT)
        issues > 0 -> CategoryStatus("${count(issues, "item")} to review", StatusTone.CAUTION)
        else -> CategoryStatus("No issues found", StatusTone.CLEAR)
    }
}

/** Every hub card's status, read live from the state the scanner and the screens already publish. */
@Composable
internal fun rememberCategoryStatuses(): Map<ScanCategory, CategoryStatus> {
    val status by Registry.status.collectAsStateWithLifecycle()
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val wifi by Registry.wifi.collectAsStateWithLifecycle()
    val wifiNow by Registry.wifiStatus.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val health by Registry.phoneHealth.collectAsStateWithLifecycle()
    val cameras by HiddenCameraScanner.findings.collectAsStateWithLifecycle()
    return mapOf(
        ScanCategory.TRACKERS to trackerStatus(status, detections, trusted),
        ScanCategory.CAMERAS to cameraStatus(cameras),
        ScanCategory.CELL to cellStatus(cell),
        ScanCategory.WIFI to wifiStatus(wifi, wifiNow, status.scanning),
        ScanCategory.NFC to nfcStatus(nfc),
        ScanCategory.PHONE to phoneStatus(health)
    )
}

private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.NEUTRAL -> ShMuted
    StatusTone.CLEAR -> ShClear
    StatusTone.CAUTION -> ShCaution
    StatusTone.ALERT -> ShCritical
}

// ── Top and bottom bars ──────────────────────────────────────────────────────

/** An entry in the top bar's overflow menu. */
internal class ShellMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The top app bar: [title], a back arrow when [onBack] is given, an overflow
 * menu when [menu] has entries, and the Settings gear on every screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    menu: List<ShellMenuItem> = emptyList()
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (menu.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = ShPanelHi
                    ) {
                        menu.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label, color = ShInk) },
                                leadingIcon = { Icon(item.icon, contentDescription = null, tint = ShInkDim) },
                                onClick = {
                                    menuOpen = false
                                    item.onClick()
                                }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ShGround,
            titleContentColor = ShInk,
            navigationIconContentColor = ShInk,
            actionIconContentColor = ShInkDim
        )
    )
}

/** The bottom navigation bar. A tab for which [alert] is true carries a red dot. */
@Composable
internal fun ShellBottomBar(
    tabs: List<HomeTab>,
    selected: HomeTab,
    alert: (HomeTab) -> Boolean,
    onSelect: (HomeTab) -> Unit
) {
    NavigationBar(
        containerColor = ShPanel,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (alert(tab)) Badge(containerColor = ShCritical) }) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(tab.label, fontSize = 12.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ShAccent,
                    selectedTextColor = ShAccent,
                    indicatorColor = ShAccent.copy(alpha = 0.15f),
                    unselectedIconColor = ShMuted,
                    unselectedTextColor = ShMuted
                )
            )
        }
    }
}

/** Sub-tabs under the top bar, for a destination with more than one screen. */
@Composable
internal fun ShellSubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selected.coerceIn(0, labels.lastIndex),
        containerColor = ShGround,
        contentColor = ShAccent,
        divider = { HorizontalDivider(color = ShRule) }
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                text = { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                selectedContentColor = ShAccent,
                unselectedContentColor = ShMuted
            )
        }
    }
}

// ── Scan hub ─────────────────────────────────────────────────────────────────

/** The Scan tab's landing screen: one large card per category, each with its live status. */
@Composable
internal fun ScanHub(
    scanning: Boolean,
    statuses: Map<ScanCategory, CategoryStatus>,
    onOpen: (ScanCategory) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "§scanner") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(8.dp).background(if (scanning) ShClear else ShMuted, CircleShape))
                Text(
                    if (scanning) "Background scanner on" else "Background scanner off",
                    color = ShInkDim, fontSize = 13.sp
                )
            }
        }
        items(ScanCategory.entries, key = { it.name }) { category ->
            HubCard(
                category = category,
                status = statuses[category] ?: CategoryStatus("", StatusTone.NEUTRAL),
                onClick = { onOpen(category) }
            )
        }
        // Clear of the floating SOS button at the bottom right.
        item(key = "§footer") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun HubCard(category: ScanCategory, status: CategoryStatus, onClick: () -> Unit) {
    val tone = toneColor(status.tone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(HubCardShape)
            .background(ShPanel, HubCardShape)
            .then(if (status.alert) Modifier.border(1.dp, ShCritical.copy(alpha = 0.6f), HubCardShape) else Modifier)
            .clickable(onClickLabel = "Open ${category.title}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(48.dp).background(ShPanelHi, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(category.icon, contentDescription = null, tint = if (status.alert) ShCritical else ShAccent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(category.title, color = ShInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(category.description, color = ShMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.text.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(Modifier.size(6.dp).background(tone, CircleShape))
                    Text(status.text, color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ShMuted)
    }
}

/** The Trackers category's action buttons, above its sub-tab content. */
@Composable
internal fun TrackerActionsRow(onAction: (TrackerAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrackerAction.entries.forEach { action ->
            OutlinedButton(
                onClick = { onAction(action) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ShInk),
                border = androidx.compose.foundation.BorderStroke(1.dp, ShRule)
            ) {
                Icon(action.icon, contentDescription = null, tint = ShAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(action.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
