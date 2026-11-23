package app.clairix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.lora.LoraFrames
import app.clairix.comms.lora.LoraLink
import app.clairix.comms.lora.LoraStatus
import app.clairix.comms.mesh.Mesh
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.ListGroup
import app.clairix.ui.components.ListRow
import app.clairix.ui.components.Notice
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.delay

// The Offline mesh page's LoRa radio section: a Meshtastic radio paired over
// Bluetooth carries the mesh's bundles over LoRa (LoraLink).

private const val SCAN_MS = 15_000L

@Composable
internal fun LoraRadioCard(meshOn: Boolean, now: Long) {
    val context = LocalContext.current
    val lora by Mesh.lora.collectAsStateWithLifecycle()
    var radio by remember { mutableStateOf(Mesh.loraRadio(context)) }
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClairixCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val chosen = radio
                if (chosen == null) {
                    val permitted = Mesh.permitted(context)
                    Text(
                        stringResource(if (permitted) R.string.lora_none_chosen else R.string.lora_needs_permission),
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
                    )
                    SecondaryButton(stringResource(R.string.lora_choose_radio), onClick = { picking = true }, enabled = permitted)
                } else {
                    KeyValueRow(stringResource(R.string.lora_radio), chosen.second)
                    KeyValueRow(stringResource(R.string.comms_status), loraStateText(lora, meshOn))
                    if (lora.state == LoraStatus.State.READY || lora.packetsSent > 0 || lora.bundlesHeard > 0) {
                        KeyValueRow(stringResource(R.string.lora_packets_sent), "${lora.packetsSent}")
                        KeyValueRow(stringResource(R.string.lora_bundles_heard), "${lora.bundlesHeard}")
                        KeyValueRow(stringResource(R.string.comms_received_for_you), "${lora.receivedForYou}")
                        KeyValueRow(
                            stringResource(R.string.lora_last_heard),
                            if (lora.lastHeardAt == 0L) stringResource(R.string.comms_none_yet)
                            else stringResource(R.string.comms_min_ago, (now - lora.lastHeardAt) / 60_000)
                        )
                    }
                    lora.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution) }
                    SecondaryButton(stringResource(R.string.lora_forget_radio), onClick = {
                        Mesh.setLoraRadio(context, null)
                        radio = null
                    })
                }
            }
        }
        Notice(
            Tone.Info, title = stringResource(R.string.lora_how_title),
            text = stringResource(R.string.lora_how_text, LoraFrames.MAX_ENVELOPE)
        )
    }

    if (picking) LoraRadioPicker(
        onPick = { addr, name ->
            picking = false
            LoraLink.pair(context, addr)
            Mesh.setLoraRadio(context, addr, name)
            radio = addr to name
        },
        onDismiss = { picking = false }
    )
}

@Composable
private fun loraStateText(s: LoraStatus, meshOn: Boolean): String = when {
    !meshOn -> stringResource(R.string.lora_starts_with_mesh)
    s.state == LoraStatus.State.READY -> stringResource(R.string.lora_connected)
    s.state == LoraStatus.State.CONFIGURING -> stringResource(R.string.lora_configuring)
    s.state == LoraStatus.State.CONNECTING -> stringResource(R.string.lora_connecting)
    s.state == LoraStatus.State.WAITING -> stringResource(R.string.lora_waiting)
    else -> stringResource(R.string.comms_off)
}

/** Lists Meshtastic radios in range for [SCAN_MS]. */
@Composable
private fun LoraRadioPicker(onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(true) }
    if (scanning) DisposableEffect(Unit) {
        val stop = LoraLink.scan(context) { addr, name -> if (found.none { it.first == addr }) found += addr to name }
        onDispose { stop() }
    }
    LaunchedEffect(scanning) { if (scanning) { delay(SCAN_MS); scanning = false } }

    ClairixDialog(
        title = stringResource(R.string.lora_choose_radio),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.lora_scan_again),
        confirmEnabled = !scanning,
        onConfirm = { found.clear(); scanning = true },
        text = if (scanning) stringResource(R.string.lora_scanning) else if (found.isEmpty()) stringResource(R.string.lora_none_found) else null,
    ) {
        if (found.isNotEmpty()) ListGroup {
            for ((addr, name) in found) {
                ListRow(title = name, subtitle = addr, onClick = { onPick(addr, name) })
            }
        }
    }
}
