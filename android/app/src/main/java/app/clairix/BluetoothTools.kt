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
import androidx.compose.ui.res.stringResource
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

    ToolPageList(title = stringResource(R.string.scan_bluetooth_exposure), onBack = onBack) {
        item(key = "§intro") {
            Notice(
                Tone.Info,
                title = stringResource(R.string.scan_what_a_sensor_by_the_road),
                text = stringResource(R.string.scan_a_bluetooth_receiver_on_a_pole)
            )
        }

        item(key = "§phone-hdr") { SectionHeader(stringResource(R.string.scan_your_phone)) }
        item(key = "§phone") {
            val s = snapshot
            ClairixCard {
                if (s == null) {
                    Text(stringResource(R.string.scan_reading_the_bluetooth_adapter), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                } else if (!s.hasAdapter) {
                    Text(stringResource(R.string.scan_this_phone_has_no_bluetooth_adapter), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                } else {
                    KeyValueRow(stringResource(R.string.scan_bluetooth), if (s.enabled) stringResource(R.string.comms_on) else stringResource(R.string.comms_off))
                    if (!s.readable) {
                        Text(
                            stringResource(R.string.scan_the_nearby_devices_permission_is_needed),
                            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                KeyValueRow(stringResource(R.string.comms_name), s.name?.let { "\"$it\"" } ?: stringResource(R.string.scan_unknown))
                            }
                            if (s.personalName) StatusPill(stringResource(R.string.scan_personal), Tone.Danger)
                            else if (s.name != null) StatusPill(stringResource(R.string.scan_anonymous), Tone.Safe)
                        }
                        if (s.personalName) {
                            Text(
                                stringResource(R.string.scan_the_name_is_sent_to_every, s.modelName),
                                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                            )
                        }
                    }
                    KeyValueRow(
                        stringResource(R.string.scan_discoverable),
                        when {
                            !s.enabled -> stringResource(R.string.scan_no_radio_off)
                            s.discoverable -> stringResource(R.string.scan_yes_answering_discovery_now)
                            s.scanMode == null -> stringResource(R.string.scan_unknown)
                            else -> stringResource(R.string.scan_no_paired_devices_only)
                        },
                        valueColor = if (s.discoverable) ClairixTheme.colors.caution else null
                    )
                    if (s.discoverable) {
                        Text(
                            stringResource(R.string.scan_while_discoverable_the_phone_answers_any),
                            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                        )
                    }
                    KeyValueRow(stringResource(R.string.scan_advertising_address), stringResource(R.string.scan_rotates_android_randomises_it))
                    Text(
                        stringResource(R.string.scan_android_keeps_the_phones_fixed_address),
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                    renameMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.readable && s.personalName && s.enabled) {
                            PrimaryButton(stringResource(R.string.scan_use_the_model_name), onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { BluetoothExposure.renameToModel(context) }
                                    renameMessage = if (ok) context.getString(R.string.scan_renamed_to_paired_devices_keep_working, BluetoothExposure.defaultName())
                                    else "The phone refused the rename. Settings → Connected devices → Device name does the same."
                                    refresh++
                                }
                            })
                        }
                        SecondaryButton(stringResource(R.string.scan_bluetooth_settings), onClick = {
                            openExternal(context, Intent(BluetoothExposure.SETTINGS_ACTION))
                        })
                    }
                }
            }
        }

        item(key = "§acc-hdr") {
            val s = snapshot
            SectionHeader(
                stringResource(R.string.scan_paired_accessories),
                supporting = s?.let { if (it.accessories.isEmpty()) null else "${it.connected.size} connected" }
            )
        }
        val s = snapshot
        if (s != null && s.readable && s.accessories.isEmpty()) {
            item(key = "§acc-none") {
                ClairixCard {
                    Text(stringResource(R.string.scan_nothing_is_paired_with_this_phone), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        stringResource(R.string.scan_with_no_accessory_on_a_fixed),
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                    )
                }
            }
        } else if (s != null && s.accessories.isNotEmpty()) {
            item(key = "§acc-note") {
                Text(
                    stringResource(R.string.scan_an_accessory_can_only_be_logged),
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
                                        a.connected -> StatusPill(stringResource(R.string.scan_connected), tone)
                                        a.fixedAddress == true -> StatusPill(stringResource(R.string.scan_fixed), tone)
                                        a.fixedAddress == false -> StatusPill(stringResource(R.string.scan_rotates), tone)
                                        else -> StatusPill(stringResource(R.string.scan_unknown), tone)
                                    }
                                    Text(a.address, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
                                }
                            }
                        )
                    }
                }
            }
        }

        item(key = "§drives-hdr") { SectionHeader(stringResource(R.string.scan_on_your_drives)) }
        item(key = "§drives") {
            ClairixCard {
                if (accessoryUse.isEmpty()) {
                    Text(
                        stringResource(R.string.scan_no_drive_with_an_accessory_connected),
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
                    )
                    Text(
                        stringResource(R.string.scan_while_the_scanner_runs_each_drive),
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
                                        a == null -> stringResource(R.string.scan_no_longer_paired)
                                        fixed == true -> stringResource(R.string.scan_fixed_address_the_same_number_at)
                                        fixed == false -> stringResource(R.string.scan_rotating_address)
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
            SectionHeader(stringResource(R.string.scan_surveillance_hardware_heard), supporting = if (sightings.isEmpty()) null else stringResource(R.string.scan_this_run, sightings.size))
        }
        if (!status.scanning) {
            item(key = "§infra-off") {
                Notice(Tone.Caution, stringResource(R.string.scan_the_radios_are_only_listened_to), title = stringResource(R.string.scan_scanner_off))
            }
        }
        if (sightings.isEmpty()) {
            item(key = "§infra-none") {
                ClairixCard {
                    Text(stringResource(R.string.scan_nothing_heard), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
                    Text(
                        stringResource(R.string.scan_flocks_cameras_upload_over_lte_and),
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
                    stringResource(R.string.scan_matches_the_plate_reader_mapped_m, x.nearestKnownM.toInt(), (x.nearestKnownLabel?.let { " ($it)" } ?: ""))
                x.nearestKnownM != null ->
                    "Not on the map: the nearest mapped plate reader is ${(x.nearestKnownM / 1000).let { "%.1f".format(it) }} km away. " +
                        "This may be one OpenStreetMap does not have yet."
                x.lat == null -> stringResource(R.string.scan_no_position_fix_when_it_was)
                else -> stringResource(R.string.scan_no_plate_reader_list_is_on)
            },
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
        Text(x.address, style = MaterialTheme.typography.monoSmall, color = ClairixTheme.colors.textSubtle)
    }
}
