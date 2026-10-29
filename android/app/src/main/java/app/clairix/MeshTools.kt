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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.mesh.Mesh
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

    ToolPageList(title = "Offline mesh", onBack = onBack) {
        item(key = "§hero") {
            StatusHero(
                eyebrow = "Phone to phone",
                headline = if (on) "On" else "Off",
                body = if (on) "Texts, group texts and SOS that cannot reach the relay are carried phone to phone over Bluetooth until they reach the person they are for."
                    else "Turn it on when the network is down or not to be trusted. Every phone on the way needs it on too.",
                tone = if (on) Tone.Info else Tone.Neutral
            )
        }
        item(key = "§warn") {
            Notice(
                Tone.Caution, title = "What turning it on shows",
                text = "While it is on, this phone advertises a Bluetooth service that anyone scanning nearby can see: it says a " +
                    "Clairix phone is here, never whose, and the address it advertises from changes. What it carries is sealed end " +
                    "to end, exactly as the relay carries it; a phone carrying someone else's message cannot read it or tell who it " +
                    "is for. Leave it off when being seen to run Clairix is the bigger risk."
            )
        }
        item(key = "§toggle") {
            if (on) PrimaryButton("Turn off", onClick = { Mesh.setEnabled(context, false); on = false }, tone = Tone.Neutral)
            else PrimaryButton("Turn on", onClick = {
                if (Mesh.permitted(context)) { Mesh.setEnabled(context, true); on = true } else ask.launch(Mesh.PERMISSIONS)
            })
        }
        if (denied) item(key = "§denied") {
            Notice(Tone.Caution, "The mesh needs the Nearby devices permission to find and talk to other phones.", title = "Permission needed")
        }
        item(key = "§status-hdr") { SectionHeader("Status") }
        item(key = "§status") {
            ClairixCard {
                KeyValueRow("Radio", when {
                    !on -> "Off"
                    status.advertising && status.scanning -> "Advertising and listening"
                    status.scanning -> "Listening"
                    status.running -> "Starting"
                    else -> "Not running"
                })
                KeyValueRow("Long range", when {
                    !on || !status.running -> "Off"
                    status.longRange -> "On"
                    else -> "This phone's Bluetooth does not have it"
                })
                KeyValueRow("Meetings", "${status.meetings}")
                KeyValueRow("Last meeting", if (status.lastMeetingAt == 0L) "None yet" else "${(now - status.lastMeetingAt) / 60_000} min ago")
                KeyValueRow("Carrying", "${status.carrying} message${if (status.carrying == 1) "" else "s"}")
                KeyValueRow("Received for you", "${status.receivedForYou}")
                KeyValueRow("Handed on", "${status.handedOn}")
                status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution) }
            }
        }
        item(key = "§limits") {
            Notice(
                Tone.Info, title = "Limits",
                text = "Bluetooth reaches a few tens of metres; phones with Bluetooth's long-range mode (shown above) reach several " +
                    "times farther between each other, out in the open. A message moves when phones pass each other. Photos, videos and " +
                    "calls stay for the relay. A contact can be reached only after one message has gone through the relay, which is " +
                    "how the two phones' sessions start. Messages are carried for ${MeshProtocol.TTL_MS / 3_600_000} hours and at most " +
                    "${MeshProtocol.MAX_HOPS} hops. The relay keeps trying as well; whichever copy arrives second is dropped."
            )
        }
    }
}
