package app.clairix

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.detect.BleNames
import app.clairix.detect.SurveillanceHardware
import app.clairix.detect.VehicleTrips
import app.clairix.security.BluetoothExposure
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.Hairline
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.ListRow
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import app.clairix.ui.theme.monoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// The Trackers category's Bluetooth exposure page: what this phone and its paired
// accessories tell a roadside or in-store sensor, the one fix an app can make, and
// the surveillance hardware the radios have heard.

@Composable
internal fun BluetoothExposurePage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by Registry.connectedBluetooth.collectAsStateWithLifecycle()
    val sightings by Registry.infrastructure.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var renameMessage by remember { mutableStateOf<String?>(null) }
    val now = rememberToolTick(10_000L)

    // The adapter, the paired list and the live connections: re-read when a device
    // connects or disconnects, and after a rename. The profile proxies are asked
    // directly as well, so the page is right even when the scanner is off.
    val snapshot by produceState<BluetoothExposure.Snapshot?>(initialValue = null, connected, refresh) {
        value = withContext(Dispatchers.IO) {
            val live = connected + BluetoothExposure.connectedDevices(context)
            BluetoothExposure.snapshot(context, live)
        }
    }
    val accessoryUse by produceState<List<VehicleTrips.AccessoryUse>>(initialValue = emptyList(), refresh) {
        value = withContext(Dispatchers.IO) { ScanService.accessoryUse(context) }
    }

    ToolPageList(title = "Bluetooth exposure", onBack = onBack) {
        item(key = "§intro") {
            Notice(
                Tone.Info,
                title = "What a sensor by the road gets",
                text = "A Bluetooth receiver on a pole, in a shop or in a patrol car logs every address it hears, " +
                    "with the time. It cannot open the phone or read anything off it. The phone itself advertises on an " +
                    "address Android changes every quarter of an hour, so it is hard to follow; your accessories are not. " +
                    "A car's hands-free, most earbuds and many watches keep one address for life, and it is logged " +
                    "at every sensor they pass. Flock's plate readers themselves do not listen for Bluetooth; separate " +
                    "sensors do, and the two kinds of record are easy to join."
            )
        }

        item(key = "§phone-hdr") { SectionHeader("Your phone") }
        item(key = "§phone") {
            val s = snapshot
            ClairixCard {
                if (s == null) {
                    Text("Reading the Bluetooth adapter…", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                } else if (!s.hasAdapter) {
                    Text("This phone has no Bluetooth adapter.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                } else {
                    KeyValueRow("Bluetooth", if (s.enabled) "On" else "Off")
                    if (!s.readable) {
                        Text(
                            "The Nearby devices permission is needed to read the name and the paired list.",
                            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                KeyValueRow("Name", s.name?.let { "\"$it\"" } ?: "Unknown")
                            }
                            if (s.personalName) StatusPill("Personal", Tone.Danger)
                            else if (s.name != null) StatusPill("Anonymous", Tone.Safe)
                        }
                        if (s.personalName) {
                            Text(
                                "The name is sent to every device in range whenever Bluetooth is on and reads like a " +
                                    "person's. Anyone with a phone learns who is in the room, and in a car park, whose car. " +
                                    "The model name, \"${s.modelName}\", is what thousands of identical phones are called.",
                                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                            )
                        }
                    }
                    KeyValueRow(
                        "Discoverable",
                        when {
                            !s.enabled -> "No, radio off"
                            s.discoverable -> "YES, answering discovery now"
                            s.scanMode == null -> "Unknown"
                            else -> "No, paired devices only"
                        },
                        valueColor = if (s.discoverable) ClairixTheme.colors.caution else null
                    )
                    if (s.discoverable) {
                        Text(
                            "While discoverable the phone answers any scanner with its fixed Classic address and its name. " +
                                "Android does this only while the Bluetooth settings page is open; leave that page.",
                            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                        )
                    }
                    KeyValueRow("Advertising address", "Rotates (Android randomises it)")
                    Text(
                        "Android keeps the phone's fixed address for paired devices and advertises on a private one that " +
                            "changes about every 15 minutes, so a sensor cannot string the phone's own packets together for long.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                    renameMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.readable && s.personalName && s.enabled) {
                            PrimaryButton("Use the model name", onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { BluetoothExposure.renameToModel(context) }
                                    renameMessage = if (ok) "Renamed to \"${BluetoothExposure.defaultName()}\". Paired devices keep working."
                                    else "The phone refused the rename. Settings → Connected devices → Device name does the same."
                                    refresh++
                                }
                            })
                        }
                        SecondaryButton("Bluetooth settings", onClick = {
                            openExternal(context, Intent(BluetoothExposure.SETTINGS_ACTION))
                        })
                    }
                }
            }
        }

        item(key = "§acc-hdr") {
            val s = snapshot
            SectionHeader(
                "Paired accessories",
                supporting = s?.let { if (it.accessories.isEmpty()) null else "${it.connected.size} connected" }
            )
        }
        val s = snapshot
        if (s != null && s.readable && s.accessories.isEmpty()) {
            item(key = "§acc-none") {
                ClairixCard {
                    Text("Nothing is paired with this phone.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        "With no accessory on a fixed address, the phone's Bluetooth gives a sensor almost nothing to keep.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        } else if (s != null && s.accessories.isNotEmpty()) {
            item(key = "§acc-note") {
                Text(
                    "An accessory can only be logged while it is switched on and in range of the sensor. Turning the " +
                        "car's Bluetooth off for a drive, or leaving earbuds in their case, is the whole fix; an app cannot " +
                        "change an accessory's address. Forgetting one here opens the system page.",
                    style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                )
            }
            item(key = "§acc-list") {
                ListGroup {
                    s.accessories.forEachIndexed { i, a ->
                        if (i > 0) Hairline()
                        val tone = when {
                            a.connected && a.fixedAddress == true -> Tone.Caution
                            a.fixedAddress == true -> Tone.Neutral
                            a.fixedAddress == false -> Tone.Safe
                            else -> Tone.Neutral
                        }
                        ListRow(
                            title = a.name,
                            subtitle = buildString {
                                append(a.radio)
                                a.addressKind?.let { append(" · ").append(it) }
                                append(" · ").append(a.exposure)
                            },
                            trailing = {
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        a.connected -> StatusPill("Connected", tone)
                                        a.fixedAddress == true -> StatusPill("Fixed", tone)
                                        a.fixedAddress == false -> StatusPill("Rotates", tone)
                                        else -> StatusPill("Unknown", tone)
                                    }
                                    Text(a.address, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                }
                            }
                        )
                    }
                }
            }
        }

        item(key = "§drives-hdr") { SectionHeader("On your drives") }
        item(key = "§drives") {
            ClairixCard {
                if (accessoryUse.isEmpty()) {
                    Text(
                        "No drive with an accessory connected has been recorded yet.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
                    )
                    Text(
                        "While the scanner runs, each drive remembers which of your accessories was connected, so this " +
                            "can say which fixed address announces your car at every sensor it passes.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                } else {
                    val paired = s?.accessories?.associateBy { it.address } ?: emptyMap()
                    accessoryUse.forEach { u ->
                        val a = paired[u.address]
                        val fixed = a?.fixedAddress
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(u.name, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        a == null -> "No longer paired"
                                        fixed == true -> "Fixed address: the same number at every sensor on these drives"
                                        fixed == false -> "Rotating address"
                                        else -> a.radio
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                                )
                            }
                            Text(
                                "${u.trips} drive${if (u.trips == 1) "" else "s"}",
                                style = MaterialTheme.typography.monoSmall,
                                color = if (fixed == true) ClairixTheme.colors.caution else ClairixTheme.colors.textMuted
                            )
                        }
                    }
                }
            }
        }

        item(key = "§infra-hdr") {
            SectionHeader("Surveillance hardware heard", supporting = if (sightings.isEmpty()) null else "${sightings.size} this run")
        }
        if (!status.scanning) {
            item(key = "§infra-off") {
                Notice(Tone.Caution, "The radios are only listened to while the scanner runs. Start scanning in Scan > Trackers.", title = "Scanner off")
            }
        }
        if (sightings.isEmpty()) {
            item(key = "§infra-none") {
                ClairixCard {
                    Text("Nothing heard.", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        "Flock's cameras upload over LTE and most have had their Bluetooth turned down since 2025, so " +
                            "hearing nothing next to one is normal. What can still be heard is an older camera's battery " +
                            "pack, a Raven gunshot detector or a unit's setup Wi-Fi. The map list is the better guide to " +
                            "where the readers are; this catches one the map does not have.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        } else {
            items(sightings, key = { "infra|${it.address}" }) { x -> SightingCard(x, now) }
        }
    }
}

@Composable
private fun SightingCard(x: InfrastructureSighting, now: Long) {
    val tone = if (x.confidence == SurveillanceHardware.Confidence.CERTAIN) Tone.Danger else Tone.Caution
    ClairixCard(tone = tone, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(x.kind.label, style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.text, modifier = Modifier.weight(1f))
            StatusPill(x.confidence.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }, tone)
        }
        val ago = ((now - x.lastSeen) / 60_000L).coerceAtLeast(0)
        Text(
            "Last heard ${if (ago < 1) "just now" else "$ago min ago"} · ${x.rssi} dBm (${BleNames.signalLabel(x.rssi)}) · " +
                "${x.sightings} packet${if (x.sightings == 1) "" else "s"} since ${tTime(x.firstSeen)}",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
        Text(x.evidence.joinToString("; ").replaceFirstChar { it.titlecase(Locale.US) }, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        Text(
            when {
                x.nearestKnownM != null && x.nearestKnownM <= ScanService.KNOWN_CAMERA_MATCH_M ->
                    "Matches the plate reader mapped ${x.nearestKnownM.toInt()} m from where it was heard" +
                        (x.nearestKnownLabel?.let { " ($it)" } ?: "") + "."
                x.nearestKnownM != null ->
                    "Not on the map: the nearest mapped plate reader is ${(x.nearestKnownM / 1000).let { "%.1f".format(it) }} km away. " +
                        "This may be one OpenStreetMap does not have yet."
                x.lat == null -> "No position fix when it was heard, so it cannot be compared with the map."
                else -> "No plate-reader list is on this phone to compare it with; load a map region in Drive."
            },
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
        Text(x.address, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
    }
}
