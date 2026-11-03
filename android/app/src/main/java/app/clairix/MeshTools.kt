package app.clairix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.mesh.Mesh
import app.clairix.comms.mesh.MeshPacing
import app.clairix.comms.mesh.MeshProtocol
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.Tone
import app.clairix.ui.theme.ClairixTheme

// The Comms menu's Offline mesh page.

@Composable
internal fun MeshPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val status by Mesh.status.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(Mesh.enabled(context)) }
    var denied by remember { mutableStateOf(false) }
    val now = rememberToolTick(5_000L)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) { Mesh.setEnabled(context, true); on = true; denied = false } else denied = true
    }

    ToolPageList(title = stringResource(R.string.settings_offline_mesh), onBack = onBack) {
        item(key = "§hero") {
            StatusHero(
                eyebrow = stringResource(R.string.comms_phone_to_phone),
                headline = if (on) stringResource(R.string.comms_on) else stringResource(R.string.comms_off),
                body = if (on) stringResource(R.string.comms_texts_group_texts_and_sos_that)
                    else stringResource(R.string.comms_turn_it_on_when_the_network),
                tone = if (on) Tone.Info else Tone.Neutral
            )
        }
        item(key = "§warn") {
            Notice(
                Tone.Caution, title = stringResource(R.string.comms_what_turning_it_on_shows),
                text = stringResource(R.string.comms_while_it_is_on_this_phone)
            )
        }
        item(key = "§toggle") {
            if (on) PrimaryButton(stringResource(R.string.settings_turn_off), onClick = { Mesh.setEnabled(context, false); on = false }, tone = Tone.Neutral)
            else PrimaryButton(stringResource(R.string.comms_turn_on), onClick = {
                if (Mesh.permitted(context)) { Mesh.setEnabled(context, true); on = true } else ask.launch(Mesh.PERMISSIONS)
            })
        }
        if (denied) item(key = "§denied") {
            Notice(Tone.Caution, stringResource(R.string.comms_the_mesh_needs_the_nearby_devices), title = stringResource(R.string.comms_permission_needed))
        }
        item(key = "§status-hdr") { SectionHeader(stringResource(R.string.comms_status)) }
        item(key = "§status") {
            ClairixCard {
                KeyValueRow(stringResource(R.string.comms_radio), when {
                    !on -> stringResource(R.string.comms_off)
                    status.advertising && status.scanning -> stringResource(R.string.comms_advertising_and_listening)
                    status.scanning -> stringResource(R.string.comms_listening)
                    status.running -> stringResource(R.string.comms_starting)
                    else -> stringResource(R.string.comms_not_running)
                })
                if (on && status.running) KeyValueRow(stringResource(R.string.comms_pace), if (status.fullPace) stringResource(R.string.comms_full) else stringResource(R.string.comms_low_power_nothing_to_carry))
                KeyValueRow(stringResource(R.string.comms_long_range), when {
                    !on || !status.running -> stringResource(R.string.comms_off)
                    status.longRange -> stringResource(R.string.comms_on)
                    !status.longRangeSupported -> stringResource(R.string.comms_this_phones_bluetooth_does_not_have)
                    status.still -> stringResource(R.string.comms_paused_while_the_phone_is_still)
                    else -> stringResource(R.string.comms_not_running)
                })
                KeyValueRow(stringResource(R.string.comms_meetings), "${status.meetings}")
                KeyValueRow(stringResource(R.string.comms_last_meeting), if (status.lastMeetingAt == 0L) stringResource(R.string.comms_none_yet) else stringResource(R.string.comms_min_ago, (now - status.lastMeetingAt) / 60_000))
                KeyValueRow(stringResource(R.string.comms_carrying), "${status.carrying} message${if (status.carrying == 1) "" else "s"}")
                KeyValueRow(stringResource(R.string.comms_received_for_you), "${status.receivedForYou}")
                KeyValueRow(stringResource(R.string.comms_handed_on), "${status.handedOn}")
                status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution) }
            }
        }
        item(key = "§battery") {
            Notice(
                Tone.Info, title = stringResource(R.string.settings_battery),
                text = stringResource(R.string.comms_while_it_is_on_the_mesh, MeshPacing.AFTER_MEETING_MS / 60_000)
            )
        }
        item(key = "§limits") {
            Notice(
                Tone.Info, title = stringResource(R.string.comms_limits),
                text = stringResource(R.string.comms_bluetooth_reaches_a_few_tens_of, MeshProtocol.TTL_MS / 3_600_000, MeshProtocol.MAX_HOPS)
            )
        }
    }
}
