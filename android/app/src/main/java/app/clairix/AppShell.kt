package app.clairix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.analysis.CameraFinding
import app.clairix.analysis.HiddenCameraScanner
import app.clairix.ui.components.BottomBarItem
import app.clairix.ui.components.ClairixBottomBar
import app.clairix.ui.components.ClairixShapes
import app.clairix.ui.components.ClairixTopBar
import app.clairix.ui.components.IconWell
import app.clairix.ui.components.LogoMark
import app.clairix.ui.components.ScreenList
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.SegmentedTabs
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.Tone
import app.clairix.ui.components.color
import app.clairix.ui.theme.ClairixTheme

/*
 * The app shell: four top-level tabs (Scan, Comms, Drive, Activity) under a top
 * app bar that always carries the Settings gear. Scan opens on a hub: a status
 * hero that says in one line whether anything needs a look, then a tile per
 * category with its live status. Each tile opens that category's screen, which
 * the top bar's back arrow and the system back button both close again.
 *
 * What the destinations are, and the status line each hub tile shows, live
 * here. Which existing screen is drawn for each destination is decided in
 * MainApp, which holds the callbacks those screens need.
 */

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
    DURESS("Duress phrase"),
    WIPE("Wipe Clairix");

    val icon: ImageVector
        get() = when (this) {
            FAKE_CALL -> ClairixIcons.PhoneInTalk
            DURESS -> ClairixIcons.Shield
            WIPE -> ClairixIcons.Remove
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

/** How a hub status reads, as a status tone. */
internal fun StatusTone.tone(): Tone = when (this) {
    StatusTone.NEUTRAL -> Tone.Neutral
    StatusTone.CLEAR -> Tone.Safe
    StatusTone.CAUTION -> Tone.Caution
    StatusTone.ALERT -> Tone.Danger
}

// ── Top and bottom bars ──────────────────────────────────────────────────────

/** An entry in the top bar's overflow menu. */
internal class ShellMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The top app bar: [title], the logo mark on a top-level screen or a back arrow
 * when [onBack] is given, an overflow menu when [menu] has entries, and the
 * Settings gear on every screen.
 */
@Composable
internal fun ShellTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    menu: List<ShellMenuItem> = emptyList()
) {
    var menuOpen by remember { mutableStateOf(false) }
    val c = ClairixTheme.colors
    ClairixTopBar(title = title, onBack = onBack) {
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = c.textMuted)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = c.surfaceRaised,
                    shape = ClairixShapes.Card
                ) {
                    menu.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label, style = MaterialTheme.typography.bodyLarge, color = c.text) },
                            leadingIcon = { Icon(item.icon, contentDescription = null, tint = c.accent) },
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
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.textMuted)
        }
    }
}

/** The bottom navigation bar. A tab for which [alert] is true carries a danger dot. */
@Composable
internal fun ShellBottomBar(
    tabs: List<HomeTab>,
    selected: HomeTab,
    alert: (HomeTab) -> Boolean,
    onSelect: (HomeTab) -> Unit
) {
    ClairixBottomBar(
        tabs.map { tab ->
            BottomBarItem(
                label = tab.label,
                icon = tab.icon,
                selected = tab == selected,
                badge = alert(tab),
                onClick = { onSelect(tab) }
            )
        }
    )
}

/**
 * The frame every top-level screen sits in: the top bar, an optional [header]
 * (sub-tabs, action rows), the [content], the floating [sos] button at the bottom
 * end, and the bottom navigation when the build has more than one tab.
 */
@Composable
internal fun ShellScaffold(
    title: String,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    menu: List<ShellMenuItem>,
    tabs: List<HomeTab>,
    selected: HomeTab,
    alert: (HomeTab) -> Boolean,
    onSelectTab: (HomeTab) -> Unit,
    header: @Composable ColumnScope.() -> Unit = {},
    sos: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(ClairixTheme.colors.base)) {
        ShellTopBar(title = title, onBack = onBack, onSettings = onSettings, menu = menu)
        header()
        Box(
            Modifier
                .weight(1f)
                // Without a tab bar the content reaches the bottom edge itself.
                .then(if (tabs.size == 1) Modifier.navigationBarsPadding() else Modifier)
        ) {
            content()
            if (sos != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)) { sos() }
            }
        }
        if (tabs.size > 1) ShellBottomBar(tabs = tabs, selected = selected, alert = alert, onSelect = onSelectTab)
    }
}

/** Sub-pages under the top bar, for a destination with more than one screen. */
@Composable
internal fun ShellSubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SegmentedTabs(labels, selected.coerceIn(0, labels.lastIndex), onSelect)
}

// ── Scan hub ─────────────────────────────────────────────────────────────────

/** What the hub's hero says, from every category's status. */
internal data class HubVerdict(val headline: String, val body: String, val tone: Tone)

internal fun hubVerdict(scanning: Boolean, statuses: Collection<CategoryStatus>): HubVerdict {
    val alerts = statuses.count { it.tone == StatusTone.ALERT }
    val cautions = statuses.count { it.tone == StatusTone.CAUTION }
    val toCheck = alerts + cautions
    return when {
        toCheck > 0 -> HubVerdict(
            headline = if (toCheck == 1) "1 thing needs a look" else "$toCheck things need a look",
            body = if (alerts > 0) "Open the flagged checks below. Each one says what was found and what to do next."
            else "Nothing urgent. Have a look at the checks marked below when you have a minute.",
            tone = if (alerts > 0) Tone.Danger else Tone.Caution
        )
        scanning -> HubVerdict(
            headline = "All clear",
            body = "Nothing found in what Clairix checked. The scanner keeps watching in the background.",
            tone = Tone.Safe
        )
        else -> HubVerdict(
            headline = "Standing by",
            body = "Nothing flagged so far. Turn the scanner on to watch for trackers and fake towers as you move.",
            tone = Tone.Neutral
        )
    }
}

/**
 * The Scan tab's landing screen: the status hero, then a tile per category with
 * its live status line.
 */
@Composable
internal fun ScanHub(
    scanning: Boolean,
    statuses: Map<ScanCategory, CategoryStatus>,
    onOpen: (ScanCategory) -> Unit
) {
    val verdict = hubVerdict(scanning, statuses.values)
    ScreenList {
        item(key = "§hero") {
            StatusHero(
                eyebrow = if (scanning) "Scanner live" else "Scanner off",
                headline = verdict.headline,
                body = verdict.body,
                tone = verdict.tone,
                modifier = Modifier.padding(top = 8.dp),
                trailing = { LogoMark(size = 48.dp, contentDescription = null) },
                footer = if (!scanning) {
                    {
                        SecondaryButton(
                            "Open the scanner",
                            onClick = { onOpen(ScanCategory.TRACKERS) },
                            tone = Tone.Accent,
                            icon = ClairixIcons.TrackChanges,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else null
            )
        }
        item(key = "§checks") { SectionHeader("Checks", supporting = "${ScanCategory.entries.size} areas") }
        ScanCategory.entries.chunked(2).forEach { pair ->
            item(key = "row-" + pair.joinToString("-") { it.name }) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { category ->
                        HubTile(
                            category = category,
                            status = statuses[category] ?: CategoryStatus("", StatusTone.NEUTRAL),
                            onClick = { onOpen(category) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HubTile(category: ScanCategory, status: CategoryStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ClairixTheme.colors
    val tone = status.tone.tone()
    val flagged = status.tone == StatusTone.ALERT || status.tone == StatusTone.CAUTION
    val shape = ClairixShapes.Card
    Column(
        modifier
            .heightIn(min = 152.dp)
            .clip(shape)
            .background(c.surface, shape)
            .border(1.dp, if (flagged) tone.color().copy(alpha = 0.6f) else c.border, shape)
            .clickable(onClickLabel = "Open ${category.title}", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${category.title}. ${category.description}. ${status.text}"
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconWell(category.icon, if (flagged) tone else Tone.Accent)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textSubtle)
        }
        Spacer(Modifier.weight(1f))
        Text(category.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status.text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .background(if (status.tone == StatusTone.NEUTRAL) c.textSubtle else tone.color(), ClairixShapes.Tag)
                )
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.tone == StatusTone.NEUTRAL) c.textSubtle else tone.color(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The Trackers category's action buttons, above its sub-tab content. */
@Composable
internal fun TrackerActionsRow(onAction: (TrackerAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrackerAction.entries.forEach { action ->
            SecondaryButton(
                action.title,
                onClick = { onAction(action) },
                icon = action.icon,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
