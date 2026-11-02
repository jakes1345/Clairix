package app.clairix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clairix.comms.lora.Lora
import app.clairix.comms.mesh.Mesh
import app.clairix.comms.lora.LoraLinkProtocol
import app.clairix.comms.lora.LoraMessenger
import app.clairix.security.SosLocation
import app.clairix.ui.components.ChoiceChips
import app.clairix.ui.components.ClairixCard
import app.clairix.ui.components.ClairixDialog
import app.clairix.ui.components.ClairixSlider
import app.clairix.ui.components.ClairixTextField
import app.clairix.ui.components.EmptyState
import app.clairix.ui.components.GhostButton
import app.clairix.ui.components.KeyValueRow
import app.clairix.ui.components.ListRow
import app.clairix.ui.components.Notice
import app.clairix.ui.components.PrimaryButton
import app.clairix.ui.components.SecondaryButton
import app.clairix.ui.components.SectionHeader
import app.clairix.ui.components.StatusHero
import app.clairix.ui.components.StatusPill
import app.clairix.ui.components.Tone
import app.clairix.ui.components.setSensitiveText
import app.clairix.ui.theme.ClairixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.clairix_comms_crypto.LoraPairState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// The Offline mesh page's LoRa radio: pairing the radio, its settings and network,
// and Lite messages with people paired over the air.

/** Where the mesh page has gone: the radio, the list of radio contacts, or one chat. */
internal sealed interface LoraScreen {
    data object Radio : LoraScreen
    data object Chats : LoraScreen
    data class Chat(val contact: String) : LoraScreen
}

/** The rows the mesh page shows for the radio, under its own header. */
internal fun androidx.compose.foundation.lazy.LazyListScope.loraRows(meshOn: Boolean, open: (LoraScreen) -> Unit) {
    item(key = "§lora-hdr") { SectionHeader("LoRa radio") }
    item(key = "§lora") {
        val status by Lora.status.collectAsStateWithLifecycle()
        val radio = when {
            !status.paired -> "None paired"
            status.connected -> "Connected to ${status.radioName.ifBlank { "the radio" }}"
            !meshOn -> "Paired; turn the mesh on to use it"
            else -> "Paired; not in reach"
        }
        ClairixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 16.dp)) {
            ListRow("Radio", subtitle = radio, onClick = { open(LoraScreen.Radio) }, onClickLabel = "Open the radio")
            if (status.paired) ListRow("Radio messages", subtitle = "Texts and SOS with handhelds paired over the air", onClick = { open(LoraScreen.Chats) })
        }
    }
}

/** Shows [screen], with the system back going to the mesh page or the chat list. */
@Composable
internal fun LoraScreens(screen: LoraScreen, go: (LoraScreen?) -> Unit) {
    val back: () -> Unit = { go(if (screen is LoraScreen.Chat) LoraScreen.Chats else null) }
    BackHandler { back() }
    when (screen) {
        LoraScreen.Radio -> LoraRadioPage(back)
        LoraScreen.Chats -> LoraChatsPage(back, open = { go(LoraScreen.Chat(it)) })
        is LoraScreen.Chat -> LoraChatPage(screen.contact, back)
    }
}

@Composable
private fun LoraRadioPage(onBack: () -> Unit) {
    val status by Lora.status.collectAsStateWithLifecycle()
    ToolPageList(title = "LoRa radio", onBack = onBack) {
        item(key = "§hero") {
            StatusHero(
                eyebrow = "Long range, no network",
                headline = when {
                    !status.paired -> "No radio"
                    status.connected -> "Connected"
                    else -> "Not in reach"
                },
                body = "A small LoRa radio paired over Bluetooth carries your texts and SOS kilometres, radio to radio, " +
                    "with no phone network and no internet: the same sealed messages the relay carries, and short Lite " +
                    "messages with people who carry a Clairix handheld instead of a phone.",
                tone = if (status.connected) Tone.Info else Tone.Neutral
            )
        }
        if (!status.paired) {
            item(key = "§find") { LoraFindRadio() }
        } else {
            item(key = "§status") { LoraRadioStatus(status) }
            item(key = "§settings-hdr") { SectionHeader("Radio settings") }
            item(key = "§settings") { LoraRadioSettings() }
            item(key = "§net-hdr") { SectionHeader("Network") }
            item(key = "§net") { LoraNetwork() }
            item(key = "§forget") { LoraForget() }
        }
        item(key = "§what") {
            Notice(
                Tone.Info, title = "What the radio protects, and what it does not",
                text = "Every frame on the air is encrypted with ChaCha20 under the network key and carries an 8-byte HMAC-SHA-512 tag, " +
                    "so radios outside your network cannot read or forge them. Each frame's first byte is a fresh hint derived from that " +
                    "key, so frames carry no fixed radio ID, and the network moves among 15 channels every ten minutes. " +
                    "Inside, full messages keep Clairix's end-to-end encryption and sealed sender; Lite messages are sealed to " +
                    "one contact with X25519 and ChaCha20-Poly1305 but have no forward secrecy. What no encryption hides: that a " +
                    "radio is transmitting, roughly how much, and from where. Direction finding can locate any transmitter, " +
                    "this one included; short bursts and relays through other radios make it harder, not impossible."
            )
        }
    }
}

@Composable
private fun LoraFindRadio() {
    val context = LocalContext.current
    val found by Lora.found.collectAsStateWithLifecycle()
    var bonding by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    if (!Mesh.enabled(context) || !Mesh.permitted(context)) {
        Notice(
            Tone.Info, title = "Turn the Offline mesh on first",
            text = "The radio works through the mesh: go back, turn it on, and allow Nearby devices, which Android needs before " +
                "this phone can find and pair with a Bluetooth radio."
        )
        return
    }
    DisposableEffect(Unit) {
        Lora.scanForRadios(context)
        onDispose { Lora.stopScan() }
    }
    ClairixCard {
        Text("Pair your radio", style = MaterialTheme.typography.titleSmall)
        Text(
            "Press the radio's button once: its screen says Pairing for two minutes, and it shows up below. Tap it, then type " +
                "the six digits the radio's screen shows when Android asks. A radio with no screen (the XIAO) pairs without " +
                "digits, so pair that one somewhere nobody else is in Bluetooth range.",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
        )
        if (found.isEmpty()) Text("Looking for radios…", style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
        for (f in found) {
            ListRow(
                f.name, subtitle = "Signal ${f.rssi} dBm" + if (bonding == f.address) " · pairing…" else "",
                onClick = {
                    if (bonding == null) {
                        bonding = f.address
                        failed = false
                        Lora.pairRadio(context, f) { ok -> bonding = null; failed = !ok }
                    }
                }
            )
        }
        if (failed) Text("Pairing did not finish. Open the radio's pairing window again and retry.", color = ClairixTheme.colors.caution)
        GhostButton("Look again", onClick = { Lora.scanForRadios(context) })
    }
}

@Composable
private fun LoraRadioStatus(status: Lora.Status) {
    val info = status.info
    ClairixCard {
        KeyValueRow("Radio", status.radioName.ifBlank { "Clairix radio" })
        KeyValueRow("Link", if (status.connected) "Connected" else "Not connected")
        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.caution) }
        if (info != null) {
            KeyValueRow("Board", info.board.label)
            KeyValueRow("Firmware", info.firmware)
            KeyValueRow("Battery", if (info.batteryMv == 0) "USB power or not measured" else "%.2f V".format(Locale.getDefault(), info.batteryMv / 1000f))
            KeyValueRow("Channel", if (info.scanning) "Listening on all (no clock yet)" else "${info.channel + 1} of 15")
            KeyValueRow("Setting", LoraLinkProtocol.Preset.of(info.preset).label + ", ${info.txPower} dBm, ${info.hopLimit} hops")
            KeyValueRow("Network", if (info.publicNetwork) "Public" else "Private")
            KeyValueRow("Heard / sent / relayed", "${info.heard} / ${info.sent} / ${info.relayed}")
            KeyValueRow("Messages held", "${info.stored}")
            KeyValueRow("Airtime used", "%.1f%% of its budget".format(Locale.getDefault(), info.airtimePermille / 10f))
        }
        KeyValueRow("Bundles to the radio / from it / for you", "${status.bundlesOut} / ${status.bundlesIn} / ${status.forYou}")
        if (status.held > 0) KeyValueRow("Radio messages waiting for the unlock", "${status.held}")
    }
}

@Composable
private fun LoraRadioSettings() {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(Lora.preset(context)) }
    var power by remember { mutableFloatStateOf(Lora.power(context).toFloat()) }
    var hops by remember { mutableStateOf(Lora.hops(context)) }
    val apply = { Lora.setRadio(context, preset, power.toInt(), hops) }
    ClairixCard {
        Text("Speed and reach", style = MaterialTheme.typography.titleSmall)
        ChoiceChips(LoraLinkProtocol.Preset.entries.toList(), preset, { it.label }, { preset = it; apply() })
        Text(preset.detail, style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted)
        Text(
            "Every radio on one network must use the same setting: radios on different settings cannot hear each other.",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
        Text("Transmit power: ${power.toInt()} dBm", style = MaterialTheme.typography.titleSmall)
        ClairixSlider(
            power, { power = it }, LoraLinkProtocol.MIN_TX_DBM.toFloat()..LoraLinkProtocol.MAX_TX_DBM.toFloat(),
            steps = LoraLinkProtocol.MAX_TX_DBM - LoraLinkProtocol.MIN_TX_DBM - 1, onValueChangeFinished = apply
        )
        Text(
            "Lower power is heard less far, by your contacts and by anyone listening for transmitters.",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
        Text("Relay hops", style = MaterialTheme.typography.titleSmall)
        ChoiceChips((1..LoraLinkProtocol.MAX_HOP_LIMIT).toList(), hops, { "$it" }, { hops = it; apply() })
    }
}

@Composable
private fun LoraNetwork() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var public by remember { mutableStateOf(Lora.isPublicNetwork(context)) }
    var showCode by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    ClairixCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (public) "Public network" else "Private network", if (public) Tone.Caution else Tone.Safe)
        }
        Text(
            if (public) "Every Clairix radio shares the public network's key, so any of them can carry your messages, and any of them " +
                "can see your radio's frames go by. What is inside stays end-to-end encrypted either way."
            else "Only radios given this network's code hear anything on it; to every other radio its frames are noise.",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
        )
        if (!public) {
            if (showCode) {
                val c = Lora.networkCode(context)
                Text(c, style = MaterialTheme.typography.bodyLarge)
                GhostButton("Copy", onClick = { clipboard.setSensitiveText("Network code", c) })
            } else {
                SecondaryButton("Show the code", onClick = { showCode = true })
            }
        }
        if (joining) {
            ClairixTextField(
                code, { code = it; bad = false }, "Network code", singleLine = false,
                isError = bad, supportingText = if (bad) "That code does not read; check each character" else "11 groups of 5 characters"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Join", onClick = {
                    if (Lora.joinNetwork(context, code)) { joining = false; public = Lora.isPublicNetwork(context); code = "" } else bad = true
                })
                GhostButton("Cancel", onClick = { joining = false })
            }
        } else {
            SecondaryButton("Join with a code", onClick = { joining = true })
            SecondaryButton("Make a new private network", onClick = { confirmNew = true })
            if (!public) GhostButton("Go back to the public network", onClick = { Lora.usePublicNetwork(context); public = true })
        }
        Text(
            "The code is a secret: anyone who has it can read every frame's routing on that network, though never what is inside a " +
                "message. Give it only in person or over Clairix. A Heltec or XIAO radio keeps it in its flash unencrypted, so a " +
                "lost radio gives it away: make a new network then.",
            style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
        )
    }
    if (confirmNew) {
        ClairixDialog(
            title = "Make a new private network?", onDismiss = { confirmNew = false }, confirmLabel = "Make it",
            text = "Your radio stops hearing the network it is on now. Give the new code to everyone who should be on it.",
            onConfirm = { Lora.newPrivateNetwork(context); public = false; showCode = true; confirmNew = false }
        )
    }
}

@Composable
private fun LoraForget() {
    val context = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    GhostButton("Forget this radio", onClick = { ask = true }, tone = Tone.Danger)
    if (ask) {
        ClairixDialog(
            title = "Forget the radio?", onDismiss = { ask = false }, confirmLabel = "Forget", confirmTone = Tone.Danger,
            text = "This phone stops using it. To make the radio forget this phone too, hold its button for ten seconds.",
            onConfirm = { Lora.forgetRadio(context); ask = false }
        )
    }
}

@Composable
private fun LoraChatsPage(onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val rev by Lora.revision.collectAsStateWithLifecycle()
    val pairing by Lora.pairing.collectAsStateWithLifecycle()
    val status by Lora.status.collectAsStateWithLifecycle()
    val contacts = remember(rev) { Lora.contacts() }
    var name by rememberSaveable { mutableStateOf(Lora.pairName(context)) }
    ToolPageList(title = "Radio messages", onBack = onBack) {
        if (!Lora.liteOpen()) {
            item(key = "§sealed") { Notice(Tone.Info, "Unlock the phone and Clairix to read and send radio messages.", title = "Locked") }
            return@ToolPageList
        }
        item(key = "§fp") {
            Notice(
                Tone.Info, title = "This phone on the radio: ${Lora.fingerprint() ?: ""}",
                text = "Radio messages (Lite) go to people you paired with over the air, such as someone with a Clairix handheld. " +
                    "They are sealed to that one person with X25519 and ChaCha20-Poly1305, and only a contact's messages are opened. " +
                    "They have no forward secrecy: anyone who later takes this phone's key and kept the radio traffic could read them. " +
                    "For contacts who have the Clairix app, ordinary messages travel over the radio too, with their full protection."
            )
        }
        item(key = "§pair") {
            ClairixCard {
                Text("Pair with someone", style = MaterialTheme.typography.titleSmall)
                val p = pairing
                if (p == null) {
                    Text(
                        "Stand together, both on the same network, and start at the same moment: on a handheld, Contacts then Pair.",
                        style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textMuted
                    )
                    ClairixTextField(name, { name = it.take(LoraMessenger.NAME_MAX) }, "Your name on their screen")
                    PrimaryButton("Start pairing", onClick = { Lora.startPairing(context, name) }, enabled = status.connected)
                    if (!status.connected) Text("The radio is not connected.", color = ClairixTheme.colors.caution)
                } else {
                    LoraPairingCard(p)
                }
            }
        }
        item(key = "§list-hdr") { SectionHeader("Contacts") }
        if (contacts.isEmpty()) {
            item(key = "§empty") { EmptyState(title = "No radio contacts yet", body = "Pair with someone above.") }
        }
        items(contacts, key = { it.keyHex }) { c ->
            ListRow(
                c.name, subtitle = if (c.unread > 0) "${c.unread} new" else Lora.contactFingerprint(c),
                subtitleTone = if (c.unread > 0) Tone.Accent else null,
                onClick = { open(c.keyHex) }
            )
        }
    }
}

@Composable
private fun LoraPairingCard(p: Lora.Pairing) {
    var peerName by remember(p.peerName) { mutableStateOf(p.peerName) }
    when (p.state) {
        LoraPairState.MATCHED -> {
            Text("Do both screens show these digits?", style = MaterialTheme.typography.bodyMedium)
            Text("%03d %03d".format(Locale.US, p.digits / 1000, p.digits % 1000), style = MaterialTheme.typography.headlineMedium)
            Text(
                "If they differ, someone in between swapped a key: say no.",
                style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
            )
            ClairixTextField(peerName, { peerName = it.take(LoraMessenger.NAME_MAX) }, "Their name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("They match", onClick = { Lora.confirmPairing(peerName.trim().ifEmpty { p.peerName }) })
                GhostButton("No", onClick = { Lora.cancelPairing() }, tone = Tone.Danger)
            }
        }
        LoraPairState.FAILED -> {
            Text("Pairing failed or timed out. Start again on both sides.", color = ClairixTheme.colors.caution)
            GhostButton("Close", onClick = { Lora.cancelPairing() })
        }
        else -> {
            Text(
                when (p.state) {
                    LoraPairState.WAITING -> "Waiting for the other side…"
                    LoraPairState.COMMITTED, LoraPairState.ANSWERED -> "Exchanging keys…"
                    else -> "Starting…"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            GhostButton("Cancel", onClick = { Lora.cancelPairing() })
        }
    }
}

@Composable
private fun LoraChatPage(contactHex: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val rev by Lora.revision.collectAsStateWithLifecycle()
    val contact = remember(rev) { Lora.contacts().firstOrNull { it.keyHex == contactHex } }
    val messages = remember(rev) { Lora.messages(contactHex) }
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sosAsk by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf(false) }
    var sosNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rev) { Lora.markRead(contactHex) }
    if (contact == null) { LaunchedEffect(Unit) { onBack() }; return }
    val bytes = text.trim().toByteArray(Charsets.UTF_8).size
    ToolPageList(title = contact.name, onBack = onBack) {
        if (messages.isEmpty()) item(key = "§none") { EmptyState(title = "Nothing yet", body = "Messages go radio to radio; delivery can take minutes.") }
        items(messages, key = { "${it.outgoing}/${it.id}" }) { m -> LoraBubble(m) }
        item(key = "§compose") {
            ClairixCard {
                ClairixTextField(
                    text, { text = it }, "Message", singleLine = false, isError = bytes > LoraMessenger.TEXT_MAX,
                    supportingText = "$bytes / ${LoraMessenger.TEXT_MAX} bytes"
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Send", onClick = { if (Lora.sendText(contactHex, text)) text = "" }, enabled = bytes in 1..LoraMessenger.TEXT_MAX)
                    SecondaryButton("SOS", onClick = { sosAsk = true }, tone = Tone.Danger)
                }
                sosNote?.let { Text(it, color = ClairixTheme.colors.caution) }
            }
        }
        item(key = "§info") {
            ClairixCard {
                KeyValueRow("Their key", Lora.contactFingerprint(contact))
                KeyValueRow("Paired", DateFormat.getDateInstance().format(Date(contact.addedMs)))
                Text(
                    "Read the key out together to check it, or compare it with their handheld's Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle
                )
                GhostButton("Remove contact", onClick = { removeAsk = true }, tone = Tone.Danger)
            }
        }
    }
    if (sosAsk) {
        ClairixDialog(
            title = "Send an SOS to ${contact.name}?", onDismiss = { sosAsk = false }, confirmLabel = "Send SOS", confirmTone = Tone.Danger,
            text = "It carries where this phone is, if it can find out, and what you typed. It is sent again until their radio answers.",
            onConfirm = {
                sosAsk = false
                val note = text
                scope.launch {
                    val at = withContext(Dispatchers.IO) { SosLocation.find(context) }
                    val pos = at?.let { (it.lat * 1e7).toInt() to (it.lon * 1e7).toInt() }
                    sosNote = if (pos == null) "Sent without a position: none could be found." else null
                    if (Lora.sendSos(contactHex, pos, note)) text = ""
                }
            }
        )
    }
    if (removeAsk) {
        ClairixDialog(
            title = "Remove ${contact.name}?", onDismiss = { removeAsk = false }, confirmLabel = "Remove", confirmTone = Tone.Danger,
            text = "Everything said with them goes, and their messages are no longer opened. Pair again to talk again.",
            onConfirm = { Lora.removeContact(contactHex); removeAsk = false; onBack() }
        )
    }
}

@Composable
private fun LoraBubble(m: LoraMessenger.Message) {
    val sos = m.kind == LoraMessenger.KIND_SOS
    ClairixCard(
        tone = if (sos) Tone.Danger else if (m.outgoing) Tone.Accent else null,
        onClick = if (m.state == LoraMessenger.State.FAILED) ({ Lora.resend(m) }) else null,
        onClickLabel = "Send again"
    ) {
        if (sos) Text("SOS", style = MaterialTheme.typography.titleSmall, color = ClairixTheme.colors.danger)
        if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyLarge)
        if (m.hasPosition) Text("%.5f, %.5f".format(Locale.US, m.lat7 / 1e7, m.lon7 / 1e7), style = MaterialTheme.typography.bodyMedium)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.atMs))
        val state = when (m.state) {
            LoraMessenger.State.SENDING -> "waiting for the radio"
            LoraMessenger.State.SENT -> "sent"
            LoraMessenger.State.DELIVERED -> "delivered"
            LoraMessenger.State.FAILED -> "not delivered · tap to send again"
            LoraMessenger.State.RECEIVED -> m.rssi?.let { "heard at $it dBm" } ?: ""
        }
        Text(listOf(time, state).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = ClairixTheme.colors.textSubtle)
    }
}
